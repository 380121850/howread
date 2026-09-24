# -*- coding: utf-8 -*-
"""L2 专项用例 PF-01 冷启动耗时 / PF-03 内存趋势 / ST-01 受控 monkey
签名: fn(dev, case_id, cfg=None, fixtures=None)
"""
import re
import statistics
import time


def pf01_cold_start(dev, case_id, cfg=None, fixtures=None, runs=3):
    """冷启动耗时：force-stop 后 am start，读 logcat Displayed，取中位数。"""
    model = dev.meta.get("model", "")
    thresholds = cfg.get("cold_start_threshold_ms", {})
    threshold = thresholds.get(model, thresholds.get("default", 2000))
    times = []
    with dev.step(case_id, "measure_x%d" % runs):
        for i in range(runs):
            dev.shell("logcat -c")
            dev.d.app_stop(dev.pkg)
            time.sleep(1)
            t0 = time.time()
            dev.shell("am start -n %s/%s" % (dev.pkg, cfg["main_activity"]))
            while time.time() - t0 < 15:
                if dev.current_pkg() == dev.pkg:
                    break
                time.sleep(0.2)
            time.sleep(2)
            # 包名必须动态取（2026-09-07 重品牌后 pro=com.leestudio.howread.pro.reader，
            # 写死 com.howread 会 grep 不到 Displayed 行，静默退化成含 2s 固定 sleep 的墙钟计时）
            out = dev.shell("logcat -d | grep -E 'Displayed %s'" % re.escape(dev.pkg))
            ms = None
            for line in out.splitlines():
                # 实际格式: "Displayed com.x.y/.Main: +903ms"（<1s）或 "+1s294ms"（≥1s，秒+毫秒）。
                # 旧正则只认纯毫秒且漏了类名后的冒号 → 永远匹配不到 → 静默退化成墙钟计时。
                # API34 起 ActivityTaskManager 行含 " for user 0:"(Android11 无), 必须兼容,
                # 否则永不匹配 -> 退化成含 2s 固定 sleep 的墙钟计时 -> 误报超阈值
                m = re.search(r"Displayed [\w.]+/[\w.$]+(?:\s+for\s+user\s+\d+)?:?\s*\+?(?:(\d+)s)?(\d+)ms", line)
                if m:
                    secs = int(m.group(1)) if m.group(1) else 0
                    ms = secs * 1000 + int(m.group(2))
            if ms is None:
                # 兜底：墙钟计时（含 2s 固定 sleep 与 adb 往返，偏大），在备注里显式标注
                ms = int((time.time() - t0) * 1000)
                print("  [%s] 冷启动 #%d: %dms（墙钟兜底，未读到 Displayed 行）" % (dev.serial, i + 1, ms))
                times.append(ms)
                continue
            times.append(ms)
            print("  [%s] 冷启动 #%d: %dms" % (dev.serial, i + 1, ms))
            dev.d.app_stop(dev.pkg)
            time.sleep(1)
        med = statistics.median(times)
        note = "冷启动中位数 %dms（阈值 %dms），各次=%s" % (med, threshold, times)
        print("  [%s] %s" % (dev.serial, note))
        if med > threshold:
            raise AssertionError(note)


def pf03_meminfo(dev, case_id, cfg=None, fixtures=None):
    """内存趋势：打开大书前后 + 翻页 30 次后的 PSS 对比。"""
    def pss():
        for _ in range(5):
            out = dev.shell("dumpsys meminfo %s | grep -i TOTAL" % dev.pkg)
            m = re.search(r"TOTAL(?:\s+PSS)?:?\s+([\d,]+)", out)
            if m:
                return int(m.group(1).replace(",", ""))
            time.sleep(1.5)
        return None

    with dev.step(case_id, "baseline"):
        dev.d.app_stop(dev.pkg)
        time.sleep(1)
        dev.d.app_start(dev.pkg, cfg["main_activity"])
        time.sleep(3)
        base = pss()
        if base is None:
            raise AssertionError("无法读取 meminfo")
    with dev.step(case_id, "open_big_pdf"):
        dev.open_book("big25", device_path=fixtures["device_pdf_path"])
        time.sleep(5)
        opened = pss()
    with dev.step(case_id, "page_turn_x30"):
        w, h = dev.d.window_size()
        for i in range(30):
            # 点击分区翻页：右=下一页，左=上一页
            if i % 2:
                dev.d.click(int(0.1 * w), int(0.5 * h))
            else:
                dev.d.click(int(0.9 * w), int(0.5 * h))
            time.sleep(0.5)
        after = pss()
    with dev.step(case_id, "analyze"):
        note = "PSS 基线=%sKB 开书=%sKB 翻30页后=%sKB" % (base, opened, after)
        print("  [%s] %s" % (dev.serial, note))
        # 泄漏判定看开书后的增长（开书本身会拉起渲染缓存，与基线比无意义）
        if opened and after:
            growth = (after - opened) / max(opened, 1)
            if growth > 0.30:
                raise AssertionError("开书后翻页内存增长 %.0f%% 疑似泄漏: %s" % (growth * 100, note))


def st01_monkey(dev, case_id, cfg=None, fixtures=None, duration_s=None):
    """受控 monkey：限定被测包名，固定 seed；结束后扫描 crash/ANR。"""
    m = cfg.get("stability_monkey", {})
    seconds = duration_s or int(m.get("duration_s", 1800))
    throttle = m.get("throttle_ms", 300)
    seed = m.get("seed", 20260906)
    with dev.step(case_id, "monkey_%ds" % seconds):
        dev.shell("logcat -c")
        # monkey 事件数 = duration / throttle
        events = int(seconds * 1000 / throttle)
        dev.shell("monkey -p %s --throttle %d -s %d --pct-syskeys 0 --ignore-security-exceptions "
                  "--ignore-timeouts --monitor-native-crashes -v %d > /dev/null 2>&1 &"
                  % (dev.pkg, throttle, seed, events), timeout=30)
        # 分段等待并巡检
        elapsed = 0
        while elapsed < seconds:
            time.sleep(min(60, seconds - elapsed))
            elapsed += 60
            out = dev.shell("logcat -d -b crash")
            if "FATAL EXCEPTION" in out:
                dev.screenshot(case_id, "monkey_crash")
                raise AssertionError("monkey 期间 crash:\n" + out[:600])
        dev.d.app_stop(dev.pkg)
        time.sleep(1)
        out = dev.shell("logcat -d -b crash")
        if "FATAL EXCEPTION" in out or ("ANR in" in out and dev.pkg in out):
            raise AssertionError("monkey 后缓冲区残留 crash/ANR:\n" + out[:600])


def pf04_remote_firstpaint(dev, case_id, cfg=None, fixtures=None):
    """远程首屏性能基线(在线阅读优化):清缓存冷开 WebDAV big_pdf(500页) →
    记录 BENCH 段耗时(doc-open-done/load-end),断言冷开 ≤3500ms;
    重开 x3 断言 ≤1000ms 并取中位数(阈值可在 cases.yaml remote_firstpaint 调整)."""
    from cases.ui import tc_function as tf
    th = (cfg or {}).get("remote_firstpaint") or {}
    cold_cap = int(th.get("cold_ms", 3500))
    warm_cap = int(th.get("warm_ms", 1000))
    with dev.step(case_id, "cold_open"):
        dev.clear_remote_cache()
        title, added = tf._ensure_server(dev, case_id, cfg, "webdav")
        tf._ensure_home(dev)
        dev.log_clear()
        entered = tf._open_remote_book(dev, case_id, cfg, title, tf._REMOTE_BIG_BOOK, timeout=90, snap="cold_reader")
        if not entered:
            dev.save_dump(case_id, "cold_open_timeout")
            raise AssertionError("冷开 90s 未进入阅读器")
        time.sleep(2)
        log = dev.remote_log()
        cold = dev.bench_ms(log, "load-end")
        docopen = dev.bench_ms(log, "doc-open-done")
        if cold is None:
            raise AssertionError("无 BENCH 'load-end' 日志")
        print("  [%s] 冷开: doc-open=%sms load-end=%sms(阈值 %d)" % (dev.serial, docopen, cold, cold_cap))
        if cold > cold_cap:
            dev.save_dump(case_id, "cold_slow")
            raise AssertionError("冷开 load-end %dms > %dms" % (cold, cold_cap))
    with dev.step(case_id, "exit"):
        tf._exit_reader(dev, case_id)
        tf._back_to_main(dev)
    times = []
    with dev.step(case_id, "reopen_x3"):
        for i in range(3):
            dev.log_clear()
            entered = tf._open_remote_book(dev, case_id, cfg, title, tf._REMOTE_BIG_BOOK, timeout=60)
            if not entered:
                raise AssertionError("重开 #%d 未进入阅读器" % (i + 1))
            time.sleep(2)
            warm = dev.bench_ms(dev.remote_log(), "load-end")
            if warm is None:
                raise AssertionError("重开 #%d 无 BENCH load-end" % (i + 1))
            times.append(warm)
            print("  [%s] 重开 #%d: %dms" % (dev.serial, i + 1, warm))
            tf._exit_reader(dev, case_id)
            tf._back_to_main(dev)
        med = statistics.median(times)
        if med > warm_cap:
            dev.save_dump(case_id, "warm_slow")
            raise AssertionError("重开中位数 %dms > %dms(各次=%s)" % (med, warm_cap, times))
        print("  [%s] 重开中位数 %dms(阈值 %d), 各次=%s" % (dev.serial, med, warm_cap, times))
    with dev.step(case_id, "cleanup"):
        if added:
            if tf._browse_root(dev):
                tf._click_row_delete(dev, title)


ALL = [
    ("PF-01", "冷启动耗时", pf01_cold_start, None),
    ("PF-03", "内存趋势", pf03_meminfo, None),
    ("PF-04", "远程首屏性能基线", pf04_remote_firstpaint, None),
    ("ST-01", "稳定性 monkey", st01_monkey, None),
]
