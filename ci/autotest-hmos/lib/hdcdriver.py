# -*- coding: utf-8 -*-
"""hdc device driver for the HowRead Pro (HarmonyOS) autotest CI.

Architecture mirrors ci/autotest/lib/driver.py (Android):
- HdcDevice provides the same method surface (shell/install/start_app/
  wait_home/wait_text/click_text/screenshot/save_dump/scan_crash/step/
  run_case), implemented over hdc + uitest + hilog instead of adb + u2.
- run_case keeps the Android skeleton: hard per-case timeout in a worker
  thread, retry loop, heartbeat lines, evidence capture on failure.
- App-side events come from the HRTEST instrumentation (model/TestLog.ets):
  hilog lines "evt=<name> ts=<epoch-ms> k=v ...".

Spike-verified facts (tmp/debug/p0_spike/SPIKE_CONCLUSIONS.md):
- emulator listens on 127.0.0.1:5555; hdc tconn to attach
- uitest dumpLayout/-p + file recv gives text/id/key/bounds AND the
  foreground bundleName/abilityName/pagePath (free foreground detection)
- uitest uiInput click/swipe/keyEvent/inputText inject events
- snapshot_display -f captures jpeg; hilog -x dumps; hilog -r clears
- crashes land in /data/log/faultlog/faultlogger/<kind>-<bundle>-*.log
"""
import json
import os
import re
import subprocess
import threading
import time
from contextlib import contextmanager

DEFAULT_HDC = (r'D:\Program Files\Huawei\DevEco Studio\sdk\default'
               r'\openharmony\toolchains\hdc.exe')
HDC = os.environ.get('HDC', DEFAULT_HDC)

DEV_TMP_LAYOUT = '/data/local/tmp/at_layout.json'
DEV_TMP_SHOT = '/data/local/tmp/at_shot.jpeg'
FAULTLOG_DIR = '/data/log/faultlog/faultlogger'
CRASH_KINDS = ('cppcrash', 'jscrash', 'appfreeze', 'jscrash_heap')

EVT_RE = re.compile(r'HRTEST: evt=(\S+) ts=(\d+)(?:\s+(.*))?$')
BOUNDS_RE = re.compile(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]')


class TestSkip(Exception):
    """Environment problem — case not counted as failure."""


class TestFail(Exception):
    """Assertion failure."""


def hdc(*args, timeout=60):
    """Run hdc with args; returns stdout text. Raises on nonzero exit."""
    cmd = [HDC] + [str(a) for a in args]
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout,
                       encoding='utf-8', errors='replace')
    if p.returncode != 0:
        raise RuntimeError('hdc %s failed rc=%d: %s' %
                           (' '.join(cmd[1:]), p.returncode, p.stderr.strip()[:300]))
    return p.stdout


class Heartbeat:
    """Print a progress line every interval while a long op runs."""

    def __init__(self, name, interval=30):
        self.name = name
        self.interval = interval
        self._stop = threading.Event()
        self._t = threading.Thread(target=self._loop, daemon=True)

    def _loop(self):
        n = 0
        while not self._stop.wait(self.interval):
            n += 1
            print('    ... %s running (%ds)' % (self.name, n * self.interval), flush=True)

    def __enter__(self):
        self._t.start()
        return self

    def __exit__(self, *a):
        self._stop.set()
        self._t.join(timeout=2)


class HdcDevice:
    """One hdc-attached HarmonyOS device (emulator via tconn or real device)."""

    def __init__(self, serial, meta=None, bundle='com.leestudio.howread.pro.reader.hmos',
                 ability='EntryAbility', run_dir='.'):
        self.serial = serial
        self.meta = meta or {}
        self.bundle = bundle
        self.ability = ability
        self.run_dir = run_dir
        self.evidence_dir = os.path.join(run_dir, 'evidence', serial.replace(':', '_'))
        os.makedirs(self.evidence_dir, exist_ok=True)
        self._local_seq = 0
        self._crash_seen = set()
        if ':' in serial and serial.startswith('127.0.0.1'):
            hdc('tconn', serial)  # idempotent
        targets = hdc('list', 'targets')
        if serial not in targets:
            raise TestSkip('device %s not visible to hdc (targets: %s)' % (serial, targets.strip()))
        self._scan_crash_files()

    # ------------------------------------------------------------ basics
    def shell(self, cmd, timeout=60):
        """Run a shell command on the device; returns combined stdout."""
        out = hdc('-t', self.serial, 'shell', cmd, timeout=timeout)
        return out

    def install(self, hap_path, replace=True):
        if replace:
            self.shell('bm uninstall -n %s' % self.bundle, timeout=30)
        out = hdc('-t', self.serial, 'install', hap_path, timeout=180)
        if 'successfully' not in out:
            raise TestFail('install failed: %s' % out.strip()[:300])

    def uninstall(self):
        self.shell('bm uninstall -n %s' % self.bundle, timeout=30)

    def installed_version(self):
        out = self.shell('bm dump -n %s' % self.bundle, timeout=30)
        m = re.search(r'"versionName":\s*"([^"]+)"', out)
        return m.group(1) if m and m.group(1) else None

    def start_app(self, params=None, clear_log=False):
        """Launch the app. params: dict of want --ps parameters."""
        if clear_log:
            self.hilog_clear()
        cmd = 'aa start -a %s -b %s' % (self.ability, self.bundle)
        for k, v in (params or {}).items():
            cmd += ' --ps %s %s' % (k, v)
        out = self.shell(cmd, timeout=30)
        if 'successfully' not in out and 'exist' not in out:
            raise TestFail('aa start failed: %s' % out.strip()[:200])

    def stop_app(self):
        self.shell('aa force-stop %s' % self.bundle, timeout=30)

    def wake(self):
        self.shell('power-shell wakeup', timeout=15)
        self.shell('power-shell setmode 602', timeout=15)  # keep screen on while plugged

    # ------------------------------------------------------------- UI dump
    def dump(self):
        """Fresh uitest dumpLayout → parsed tree (dict). Retries once (the
        recv can race the device-side write on slow loads)."""
        local = self._local_path('layout')
        for attempt in (1, 2):
            try:
                self.shell('uitest dumpLayout -p %s' % DEV_TMP_LAYOUT, timeout=30)
                hdc('-t', self.serial, 'file', 'recv', DEV_TMP_LAYOUT, local, timeout=60)
                with open(local, encoding='utf-8') as f:
                    return json.load(f)
            except Exception:
                if attempt == 2:
                    raise
                time.sleep(1.0)

    def _local_path(self, tag):
        self._local_seq += 1
        return os.path.join(self.evidence_dir, '%s_%d.json' % (tag, self._local_seq))

    @staticmethod
    def _walk(n):
        yield n
        for c in (n.get('children') or []):
            for x in HdcDevice._walk(c):
                yield x

    def find(self, text=None, id_=None, hint=None, desc=None, contains=True):
        """First node whose text/id/hint/description matches; None if absent."""
        for n in self._walk(self.dump()):
            a = n.get('attributes', {})
            for val, key in ((text, 'text'), (id_, 'id'), (hint, 'hint'), (desc, 'description')):
                if val is None:
                    continue
                hay = a.get(key, '') or ''
                ok = (val in hay) if contains else (val == hay)
                if ok:
                    return a
        return None

    def current_page(self):
        """(bundleName, pagePath) of the foreground app window, from the tree."""
        for n in self._walk(self.dump()):
            a = n.get('attributes', {})
            if a.get('bundleName'):
                return a.get('bundleName'), a.get('pagePath', '')
        return None, None

    def exists_text(self, text):
        return self.find(text=text) is not None

    def wait_text(self, text, timeout=10):
        t0 = time.time()
        while time.time() - t0 < timeout:
            if self.find(text=text):
                return True
            time.sleep(0.8)
        return False

    def click_text(self, text, timeout=10):
        """Locate by text, click its center. Returns True on success."""
        t0 = time.time()
        while time.time() - t0 < timeout:
            a = self.find(text=text)
            if a:
                return self._click_attrs(a)
            time.sleep(0.8)
        return False

    def click_id(self, id_, timeout=10):
        t0 = time.time()
        while time.time() - t0 < timeout:
            a = self.find(id_=id_)
            if a:
                return self._click_attrs(a)
            time.sleep(0.8)
        return False

    def _click_attrs(self, a):
        m = BOUNDS_RE.search(a.get('bounds', ''))
        if not m:
            return False
        x1, y1, x2, y2 = map(int, m.groups())
        cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
        self.shell('uitest uiInput click %d %d' % (cx, cy), timeout=15)
        return True

    def click_xy(self, x, y):
        self.shell('uitest uiInput click %d %d' % (int(x), int(y)), timeout=15)

    def swipe(self, x1, y1, x2, y2, velocity=600):
        self.shell('uitest uiInput swipe %d %d %d %d %d' %
                   (x1, y1, x2, y2, velocity), timeout=15)

    def key(self, name):
        self.shell('uitest uiInput keyEvent %s' % name, timeout=15)

    def input_text_at(self, x, y, text):
        """Type text at a coordinate (focus a TextInput by tapping first)."""
        self.click_xy(x, y)
        time.sleep(0.5)
        self.shell('uitest uiInput inputText %d %d %s' % (x, y, text), timeout=15)

    def wait_home(self, timeout=30):
        """Wait for the app home page (bundle + pages/Index + tab bar text)."""
        t0 = time.time()
        while time.time() - t0 < timeout:
            b, page = self.current_page()
            if b == self.bundle and page == 'pages/Index' and self.exists_text('首页'):
                return True
            time.sleep(1.0)
        return False

    # ------------------------------------------------------------- hilog
    def hilog_clear(self):
        self.shell('hilog -r', timeout=15)

    def hilog_events(self, evt=None, since_ts=0):
        """Parse HRTEST events from hilog. Returns list of dicts:
        {evt, ts, kv:{...}, raw, dev_ts}. ts = app-side Date.now() ms."""
        out = self.shell('hilog -x', timeout=30)
        events = []
        for line in out.splitlines():
            if 'HRTEST' not in line:
                continue
            m = EVT_RE.search(line)
            if not m:
                continue
            e = {'evt': m.group(1), 'ts': int(m.group(2)), 'kv': {}, 'raw': line.strip()}
            if m.group(3):
                for tok in m.group(3).split():
                    if '=' in tok:
                        k, v = tok.split('=', 1)
                        e['kv'][k] = v
            dm = re.match(r'(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d\d\d)', line)
            if dm:
                e['dev_ts'] = '%s-%s %s:%s:%s.%s' % dm.groups()
            if e['ts'] >= since_ts and (evt is None or e['evt'] == evt):
                events.append(e)
        return events

    def wait_event(self, evt, timeout=15, since_ts=0):
        """Wait until an HRTEST event appears; returns the event dict or None."""
        t0 = time.time()
        while time.time() - t0 < timeout:
            evts = self.hilog_events(evt=evt, since_ts=since_ts)
            if evts:
                return evts[-1]
            time.sleep(1.0)
        return None

    # ------------------------------------------------------------- memory
    def pid(self):
        out = self.shell('pidof %s' % self.bundle, timeout=15)
        out = out.strip()
        return int(out.split()[0]) if out else None

    def mem_pss_kb(self):
        """Total PSS of the app process via hidumper --mem (kB).

        Reads the report's own 'Total' row (first number). Do NOT sum the
        detail rows: the breakdown repeats native heap as heap/brk/mmap
        sub-rows AND includes the Total itself, so summing inflates the
        number >2x (PF-02 2026-09-22 correction; real open working set on
        Pura 90 was 245MB, not 553MB)."""
        pid = self.pid()
        if not pid:
            return None
        out = self.shell('hidumper --mem %d' % pid, timeout=30)
        for ln in out.splitlines():
            s = ln.strip()
            if s.startswith('Total'):
                for p in re.split(r'\s+', s)[1:]:
                    if p.isdigit():
                        return int(p)
        return None

    # -------------------------------------------------------------- crash
    def _crash_files(self):
        out = self.shell('ls %s 2>/dev/null' % FAULTLOG_DIR, timeout=15)
        files = set()
        for ln in out.splitlines():
            ln = ln.strip()
            for kind in CRASH_KINDS:
                if ln.startswith(kind) and self.bundle in ln:
                    files.add(ln)
        return files

    def _scan_crash_files(self):
        try:
            self._crash_seen = self._crash_files()
        except Exception:
            self._crash_seen = set()

    def scan_crash(self):
        """New crash/freeze files for THIS bundle since last scan → list."""
        try:
            now = self._crash_files()
        except Exception:
            return []
        new = sorted(now - self._crash_seen)
        self._crash_seen = now
        return new

    # ------------------------------------------------------------ evidence
    def screenshot(self, tag='shot'):
        local = os.path.join(self.evidence_dir, '%s_%d.jpeg' % (tag, int(time.time())))
        self.shell('snapshot_display -f %s' % DEV_TMP_SHOT, timeout=30)
        hdc('-t', self.serial, 'file', 'recv', DEV_TMP_SHOT, local, timeout=60)
        return local

    def save_dump(self, tag='dump'):
        tree = self.dump()
        local = os.path.join(self.evidence_dir, '%s_%d.json' % (tag, int(time.time())))
        with open(local, 'w', encoding='utf-8') as f:
            json.dump(tree, f, ensure_ascii=False, indent=1)
        return local

    def save_hilog(self, tag='hilog'):
        out = self.shell('hilog -x', timeout=30)
        local = os.path.join(self.evidence_dir, '%s_%d.txt' % (tag, int(time.time())))
        with open(local, 'w', encoding='utf-8') as f:
            f.write(out)
        return local

    # --------------------------------------------------------------- steps
    @contextmanager
    def step(self, case_id, name):
        """One verified step: crash-scan after; on exception capture evidence."""
        print('    [%s] %s' % (case_id, name), flush=True)
        try:
            yield self
            crashes = self.scan_crash()
            if crashes:
                raise TestFail('crash detected during step %s: %s' % (name, crashes))
        except Exception:
            try:
                self.screenshot('fail_%s' % name)
                self.save_dump('fail_%s' % name)
                self.save_hilog('fail_%s' % name)
            except Exception:
                pass
            raise

    # --------------------------------------------------------------- seeds
    def seed_book(self, url, timeout=60):
        """Cold-start the app with hrSeedUrl; wait for seed_done event."""
        self.hilog_clear()
        self.stop_app()
        self.start_app(params={'hrSeedUrl': url})
        ev = self.wait_event('seed_done', timeout=timeout)
        if not ev:
            fail = self.hilog_events(evt='seed_fail')
            raise TestFail('seed failed for %s: %s' %
                           (url, fail[-1]['raw'] if fail else 'no seed_done event'))
        return ev


def run_case(dev, fn, case_id, name, meta, results, cfg=None, fixtures=None):
    """Execute one case with hard timeout + retries; record result."""
    timeout = (meta or {}).get('timeout', cfg.get('case_timeout_s', 300) if cfg else 300)
    retries = (meta or {}).get('retries', cfg.get('case_retries', 1) if cfg else 1)
    attempts = 0
    while True:
        attempts += 1
        t0 = time.time()
        res = {'id': case_id, 'name': name, 'attempts': attempts,
               'layer': (meta or {}).get('layer', 'ui'),
               'priority': (meta or {}).get('priority', 'P1'),
               'result': 'PASS', 'note': '', 'secs': 0.0,
               'evidence': os.path.relpath(dev.evidence_dir, dev.run_dir)}
        with Heartbeat('%s %s' % (case_id, name), cfg.get('heartbeat_s', 30) if cfg else 30):
            holder = {}

            def _worker():
                try:
                    fn(dev, case_id, cfg, fixtures)
                    holder['r'] = 'PASS'
                except TestSkip as e:
                    holder['r'] = 'SKIP'
                    holder['n'] = str(e)
                except Exception as e:
                    holder['r'] = 'FAIL'
                    holder['n'] = '%s: %s' % (type(e).__name__, e)

            th = threading.Thread(target=_worker, daemon=True)
            th.start()
            th.join(timeout)
            if th.is_alive():
                res['result'] = 'FAIL'
                res['note'] = 'TIMEOUT after %ds' % timeout
                try:
                    dev.screenshot('timeout')
                    dev.save_dump('timeout')
                    dev.save_hilog('timeout')
                    dev.stop_app()
                except Exception:
                    pass
            else:
                res['result'] = holder.get('r', 'FAIL')
                res['note'] = holder.get('n', '')
        res['secs'] = round(time.time() - t0, 1)
        results.append(res)
        print('  %s %-8s %s (%.1fs) %s' %
              (case_id, res['result'], name, res['secs'], res['note'][:80]), flush=True)
        if res['result'] in ('PASS', 'SKIP') or attempts > retries:
            return res
        time.sleep(2)
