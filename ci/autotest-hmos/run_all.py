# -*- coding: utf-8 -*-
"""HowRead Pro (HarmonyOS) autotest entry — hdc/uitest/hilog black-box CI.

Usage (Windows host, emulator running):
    python run_all.py                     # L0 on the Pura 90 emulator
    python run_all.py --level L1
    python run_all.py --serial <realdevice-serial>          # real device
    python run_all.py --cases SM-02,SM-04
    python run_all.py --reset             # bm clean + reinstall
    python run_all.py --no-install        # keep current app
Exit code 0 = all PASS (SKIP not counted) — CI gate.
"""
import argparse
import glob
import json
import os
import re
import socket
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from lib.hdcdriver import HdcDevice, run_case  # noqa: E402
from lib import report as report_mod           # noqa: E402

LEVELS = {
    'L0': 'cases.ui.tc_smoke',
    'L1': 'cases.ui.tc_function',
    'L2': 'cases.ui.tc_special',
}


class Tee:
    def __init__(self, path):
        self.f = open(path, 'w', encoding='utf-8', buffering=1)

    def write(self, s):
        sys.__stdout__.write(s)
        self.f.write(s)

    def flush(self):
        sys.__stdout__.flush()
        self.f.flush()


def load_yaml(path):
    """PyYAML if present, else a compact indent parser (dict/str/list only)."""
    try:
        import yaml
        with open(path, encoding='utf-8') as f:
            return yaml.safe_load(f)
    except ImportError:
        pass
    root = {}
    stack = [(-1, root)]
    with open(path, encoding='utf-8') as f:
        for raw in f:
            line = raw.rstrip('\n')
            if not line.strip() or line.lstrip().startswith('#'):
                continue
            indent = len(line) - len(line.lstrip())
            body = line.strip()
            while stack and indent <= stack[-1][0]:
                stack.pop()
            parent = stack[-1][1]
            m = re.match(r'^([^:]+):\s*(.*)$', body)
            if not m:
                continue
            k, v = m.group(1).strip(), m.group(2).strip()
            if v == '':
                node = {}
                parent[k] = node
                stack.append((indent, node))
            else:
                v = v.strip('"\'')

                def conv(s):
                    return int(s) if re.match(r'^\d+$', s) else s
                if v.startswith('[') and v.endswith(']'):
                    parent[k] = [conv(x.strip()) for x in v[1:-1].split(',') if x.strip()]
                else:
                    parent[k] = conv(v)
    return root


def host_lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('8.8.8.8', 80))
        return s.getsockname()[0]
    except Exception:
        return '127.0.0.1'
    finally:
        s.close()


class SeedServer:
    """python -m http.server for the hrSeedUrl hook (started lazily)."""

    def __init__(self, cfg):
        self.dir = cfg.get('seed_server', {}).get('host_dir')
        self.port = int(cfg.get('seed_server', {}).get('port', 8790))
        self.proc = None

    def url(self, name):
        self.start()
        return 'http://%s:%d/%s' % (host_lan_ip(), self.port, name)

    def start(self):
        if self.proc and self.proc.poll() is None:
            return
        self.proc = subprocess.Popen(
            [sys.executable, '-m', 'http.server', str(self.port),
             '--directory', self.dir],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def stop(self):
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(5)
            except Exception:
                self.proc.kill()


def find_hap(devices_cfg, want=None):
    base = devices_cfg['bundle']['pro']
    pats = [want] if want else [base['hap_glob_debug']]
    for pat in pats:
        path = os.path.normpath(os.path.join(HERE, pat))
        hits = sorted(glob.glob(path), key=os.path.getmtime)
        if hits:
            return hits[-1]
    return None


def prepare_device(dev, args, cfg, hap):
    """Version check / install / reset — mirrors Android prepare_device."""
    if args.no_install:
        print('  [prep] --no-install: keep installed app', flush=True)
        if not dev.installed_version():
            raise RuntimeError('app not installed and --no-install given')
        return
    if not hap:
        raise RuntimeError('no HAP found (build first, or pass --hap)')
    dev_ver = dev.installed_version()
    hap_ver = re.search(r'-v([\d.]+)-', os.path.basename(hap))
    same = dev_ver and hap_ver and dev_ver == hap_ver.group(1)
    if same and not args.reset:
        print('  [prep] version %s matches, skip install' % dev_ver, flush=True)
        return
    print('  [prep] install %s (device=%s)' % (os.path.basename(hap), dev_ver), flush=True)
    if args.reset:
        dev.shell('bm clean -n %s -d' % dev.bundle, timeout=30)
        dev.shell('bm clean -n %s -c' % dev.bundle, timeout=30)
    dev.install(hap)


class AiMock:
    """tools/ai_mock.py subprocess (OpenAI-compatible :8770)."""

    def __init__(self, cfg):
        am = cfg.get('ai_mock', {})
        self.script = os.path.join(HERE, am.get('script', 'tools/ai_mock.py'))
        self.port = int(am.get('port', 8770))
        self.proc = None

    def url(self):
        self.start()
        return 'http://%s:%d/v1' % (host_lan_ip(), self.port)

    def start(self):
        if self.proc and self.proc.poll() is None:
            return
        if not os.path.exists(self.script):
            return
        self.proc = subprocess.Popen(
            [sys.executable, self.script, str(self.port)],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def stop(self):
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(5)
            except Exception:
                self.proc.kill()


def worker(entry, args, cfg, hap, level, run_dir, seed):
    serial, meta = entry
    print('== device %s (%s) ==' % (serial, meta.get('model', '?')), flush=True)
    results = []
    ai = None
    bundle = cfg_dev_bundle()
    try:
        dev = HdcDevice(serial, meta=meta, bundle=bundle['bundleName'],
                        ability=cfg.get('ability', 'EntryAbility'), run_dir=run_dir)
    except Exception as e:
        results.append({'id': 'ENV', 'name': 'device connect', 'attempts': 1,
                        'layer': 'ui', 'priority': 'P0', 'result': 'FAIL',
                        'note': str(e)[:200], 'secs': 0.0, 'evidence': ''})
        return serial, meta, results
    try:
        dev.wake()
        prepare_device(dev, args, cfg, hap)
        mod = __import__(LEVELS[level], fromlist=['ALL'])
        case_meta = cfg.get('case_meta', {})
        fixtures = {
            'hap': hap,
            'seed_epub_url': seed.url(cfg_fixtures()['epub']) if seed else None,
            'seed_pdf_url': seed.url(cfg_fixtures()['pdf']) if seed else None,
        }
        ai = None
        if level in ('L1', 'L2') and cfg.get('ai_mock'):
            ai = AiMock(cfg)
            fixtures['ai_mock_url'] = ai.url()
        # ENV row: version confirmation
        v = dev.installed_version()
        results.append({'id': 'ENV', 'name': '版本确认 %s' % (v or '?'), 'attempts': 1,
                        'layer': 'ui', 'priority': 'P0', 'result': 'PASS',
                        'note': '', 'secs': 0.0, 'evidence': ''})
        for cid, name, fn in mod.ALL:
            cm = case_meta.get(cid, {})
            if cm.get('enabled') is False:
                print('  %s %-8s %s (disabled, skip)' % (cid, 'SKIP', name), flush=True)
                continue
            if args.cases and cid not in args.cases:
                continue
            run_case(dev, fn, cid, name, cm, results, cfg=cfg, fixtures=fixtures)
    finally:
        if seed:
            seed.stop()
        if ai:
            ai.stop()
    return serial, meta, results


_DEVICES_CFG = None
_CFG = None


def cfg_dev_bundle():
    return _DEVICES_CFG['bundle']['pro']


def cfg_fixtures():
    return _DEVICES_CFG.get('fixtures', {})


def main():
    global _DEVICES_CFG, _CFG
    ap = argparse.ArgumentParser()
    ap.add_argument('--level', default='L0', choices=list(LEVELS))
    ap.add_argument('--serial', action='append', default=[])
    ap.add_argument('--emulator', action='store_true')
    ap.add_argument('--hap')
    ap.add_argument('--cases', help='comma separated case ids')
    ap.add_argument('--reset', action='store_true')
    ap.add_argument('--no-install', action='store_true')
    args = ap.parse_args()
    if args.reset and args.no_install:
        ap.error('--reset and --no-install are mutually exclusive')

    with open(os.path.join(HERE, 'config', 'devices.json'), encoding='utf-8') as f:
        _DEVICES_CFG = json.load(f)
    _CFG = load_yaml(os.path.join(HERE, 'config', 'cases.yaml'))

    if args.serial:
        entries = [(s, {'model': 'real device', 'abi': '?'}) for s in args.serial]
        tag = 'ui-hmos-device'
    else:
        emus = _DEVICES_CFG.get('emulator_devices', [])
        if not emus and not args.emulator:
            print('no emulator in devices.json and no --serial given', file=sys.stderr)
            return 2
        entries = [(e['serial'], e) for e in emus]
        tag = 'ui-hmos-emu'

    run_id = time.strftime('%Y%m%d-%H%M%S') + '_%s_%s' % (args.level, tag)
    run_dir = os.path.join(HERE, 'results', run_id)
    os.makedirs(run_dir, exist_ok=True)
    tee = Tee(os.path.join(run_dir, 'run.log'))
    sys.stdout = tee

    hap = find_hap(_DEVICES_CFG, args.hap)
    print('run %s | level=%s devices=%d hap=%s' % (run_id, args.level, len(entries), hap))

    seed = SeedServer(_CFG) if _CFG.get('seed_server') else None
    runs = {}
    if len(entries) == 1:
        out = worker(entries[0], args, _CFG, hap, args.level, run_dir, seed)
        runs[out[0]] = {'meta': out[1], 'results': out[2]}
    else:
        with ThreadPoolExecutor(max_workers=len(entries)) as ex:
            futs = [ex.submit(worker, e, args, _CFG, hap, args.level, run_dir, seed)
                    for e in entries]
            for fu in futs:
                out = fu.result()
                runs[out[0]] = {'meta': out[1], 'results': out[2]}

    n_fail = sum(1 for r in runs.values() for x in r['results'] if x['result'] == 'FAIL')
    gate = 'PASS' if n_fail == 0 else 'FAIL(%d)' % n_fail
    rp = report_mod.write_report(runs, os.path.join(run_dir, 'report.md'),
                                 {'level': args.level, 'hap': hap or '?', 'gate': gate})
    with open(os.path.join(HERE, 'results', 'LATEST.txt'), 'w') as f:
        f.write(run_dir)
    print('report: %s' % rp)
    print('GATE: %s' % gate)
    sys.stdout = sys.__stdout__
    return 0 if n_fail == 0 else 2


if __name__ == '__main__':
    sys.exit(main())
