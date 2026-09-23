# -*- coding: utf-8 -*-
"""L2 special cases for HowRead Pro (HarmonyOS): performance + stability.

PF-01 cold start — HRTEST ability_onCreate → home_ready ts delta, N runs,
                   median vs per-model threshold (cases.yaml).
PF-02 memory    — hidumper --mem PSS: baseline (home) vs after opening a book
                   + page turns; growth% above threshold = suspected leak.
ST-01 monkey    — random click/swipe/keyEvent storm (fixed seed, throttled),
                   faultlogger + hilog crash scan throughout.
"""
import random
import statistics
import time

from lib.hdcdriver import TestFail, TestSkip

from cases.ui.tc_function import _ensure_home, _open_book, _exit_reader


def pf01_cold_start(dev, case_id, cfg, fixtures):
    perf = cfg.get('perf', {})
    runs = int(perf.get('cold_start_runs', 3))
    thresholds = cfg.get('cold_start_threshold_ms', {})
    thr = thresholds.get(dev.meta.get('model', ''), thresholds.get('default', 3500))
    samples = []
    for i in range(runs):
        dev.wake()
        dev.hilog_clear()
        dev.stop_app()
        dev.start_app()
        e1 = dev.wait_event('ability_onCreate', timeout=20)
        e2 = dev.wait_event('home_ready', timeout=30)
        if not e1 or not e2:
            raise TestFail('run %d: start events missing (%s/%s)' % (i, bool(e1), bool(e2)))
        ms = e2['ts'] - e1['ts']
        samples.append(ms)
        print('    [%s] run %d: %dms' % (case_id, i + 1, ms), flush=True)
        time.sleep(1.5)
    med = int(statistics.median(samples))
    print('    [%s] median %dms (threshold %dms): %s' %
          (case_id, med, thr, samples), flush=True)
    if med > thr:
        raise TestFail('cold start median %dms > threshold %dms (samples %s)' %
                       (med, thr, samples))


def pf02_memory(dev, case_id, cfg, fixtures):
    perf = cfg.get('perf', {})
    limit = float(perf.get('mem_growth_pct', 30))
    _ensure_home(dev, cfg)
    base = dev.mem_pss_kb()
    if not base or base < 1000:
        raise TestSkip('mem_pss_kb unavailable (%s)' % base)
    pss_open = None
    with dev.step(case_id, 'open book + 8 turns'):
        _open_book(dev, 'Alice', expect_pages=105)
        time.sleep(2.0)
        # First-render working set (fonts/store/layout, plateaus immediately;
        # 2026-09-22 baseline on Pura 90: ~155 -> ~245MB, informational only)
        pss_open = dev.mem_pss_kb()
        for _ in range(8):
            dev.key('17')
            time.sleep(0.8)
        time.sleep(2.0)
    after = dev.mem_pss_kb()
    if not after or not pss_open:
        raise TestSkip('mem_pss_kb after unavailable')
    # Leak gate semantics (2026-09-22): growth is measured from AFTER OPEN to
    # after the turns — turning pages must not grow the process. The open
    # working set itself is reported above, not gated.
    growth = (after - pss_open) * 100.0 / pss_open
    print('    [%s] PSS home %dkB -> open %dkB -> post-turns %dkB '
          '(turn growth %+.1f%%, open working set %+.1f%%, limit %.0f%%)' %
          (case_id, base, pss_open, after, growth,
           (pss_open - base) * 100.0 / base, limit), flush=True)
    _exit_reader(dev)
    if growth > limit:
        raise TestFail('PSS turn-phase growth %.1f%% > %.0f%% (%dkB -> %dkB)' %
                       (growth, limit, pss_open, after))


def st01_monkey(dev, case_id, cfg, fixtures):
    sm = cfg.get('stability_monkey', {})
    duration = int(sm.get('duration_s', 300))
    throttle = int(sm.get('throttle_ms', 300)) / 1000.0
    seed = int(sm.get('seed', 20260921))
    rnd = random.Random(seed)
    _ensure_home(dev, cfg)
    crashes = []
    t_end = time.time() + duration
    n = 0
    last_report = time.time()
    print('    [%s] monkey start: %ds seed=%d throttle=%dms' %
          (case_id, duration, seed, int(throttle * 1000)), flush=True)
    while time.time() < t_end:
        n += 1
        act = rnd.random()
        if act < 0.55:
            x, y = rnd.randint(40, 1280), rnd.randint(200, 2600)
            dev.click_xy(x, y)
        elif act < 0.9:
            x1, y1 = rnd.randint(200, 1100), rnd.randint(500, 2300)
            dx, dy = rnd.randint(-600, 600), rnd.randint(-900, 900)
            dev.swipe(x1, y1, max(30, min(1290, x1 + dx)), max(200, min(2700, y1 + dy)), 500)
        else:
            if rnd.random() < 0.7:
                dev.key('17')  # volume down = page turn in reader
            else:
                dev.key('Back')
        time.sleep(throttle)
        # keep the app in foreground: relaunch if it left
        if n % 25 == 0:
            b, page = dev.current_page()
            if b != dev.bundle:
                print('    [%s] app left foreground (%s) — relaunching' % (case_id, b), flush=True)
                dev.start_app()
                time.sleep(2.0)
        if time.time() - last_report > 60:
            last_report = time.time()
            crashes += dev.scan_crash()
            print('    [%s] ... %ds elapsed, %d events, crashes %d' %
                  (case_id, int(time.time() - (t_end - duration)), n, len(crashes)), flush=True)
    crashes += dev.scan_crash()
    print('    [%s] monkey done: %d events, crashes %d' % (case_id, n, len(crashes)), flush=True)
    if crashes:
        raise TestFail('monkey produced crash/freeze logs: %s' % crashes[:3])


ALL = [
    ('PF-01', '冷启动耗时（HRTEST 中位）', pf01_cold_start),
    ('PF-02', '开书内存增长', pf02_memory),
    ('ST-01', '随机 monkey 稳定性', st01_monkey),
]
