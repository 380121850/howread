# -*- coding: utf-8 -*-
"""HowRead UI 层自动测试入口（真机 / AVD）
用法:
  python run_all.py --level L0                          # 全部真机跑 L0 (pro)
  python run_all.py --level L1 --serial 48fee174        # 指定设备
  python run_all.py --level L0 --avd                    # 在 AVD 模拟器上跑（UI 层）
  python run_all.py --level L0 --flavor fdroid
  python run_all.py --level L1 --serial-exec            # 设备间串行（调试）
每次运行生成 results/<时间戳>_<层级>/（report.md + run.log + evidence/）。
"""
import argparse
import glob
import json
import os
import sys
import threading
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lib.driver import Device, adb, log  # noqa: E402
from lib.report import write_report  # noqa: E402

ROOT = os.path.dirname(os.path.abspath(__file__))
CFG_DIR = os.path.join(ROOT, "config")
ANDROID_ROOT = r"Z:\opt\librera\LibreraReader\android"


class Tee(object):
    """stdout 同时写控制台与 run.log。"""

    def __init__(self, path):
        self.file = open(path, "w", encoding="utf-8")
        self.stdout = sys.stdout
        self.lock = threading.Lock()

    def write(self, s):
        with self.lock:
            self.stdout.write(s)
            try:
                self.file.write(s)
                self.file.flush()
            except Exception:
                pass

    def flush(self):
        self.stdout.flush()


def load_yaml(path):
    try:
        import yaml
        return yaml.safe_load(open(path, encoding="utf-8"))
    except ImportError:
        pass
    cfg = {}
    stack = [(-1, cfg)]
    for raw in open(path, encoding="utf-8"):
        line = raw.split("#")[0].rstrip()
        if not line.strip():
            continue
        indent = len(line) - len(line.lstrip())
        key, _, val = line.strip().partition(":")
        val = val.strip().strip("'\"")
        while stack and indent <= stack[-1][0]:
            stack.pop()
        parent = stack[-1][1]
        if val == "":
            parent[key] = {}
            stack.append((indent, parent[key]))
        else:
            try:
                parent[key] = json.loads(val)
            except (ValueError, json.JSONDecodeError):
                parent[key] = val
    return cfg


def find_apk(flavor, abi):
    """按设备主 ABI 选包：arm64→*arm64*；armeabi-v7a→*-arm*；x86_64(AVD)→*x86_64* / uni；兜底 uni。"""
    base = os.path.join(ANDROID_ROOT, "app", "build", "outputs", "apk", flavor, "debug")
    if abi and "x86" in abi:
        pats = ["*x86_64*.apk", "*uni*.apk", "*.apk"]
    elif abi and "arm64" in abi:
        pats = ["*arm64*.apk", "*uni*.apk", "*.apk"]
    elif abi and "armeabi" in abi:
        pats = ["*-arm.apk", "*-arm-*.apk", "*uni*.apk", "*.apk"]
    else:
        pats = ["*uni*.apk", "*.apk"]
    for p in pats:
        hits = sorted(glob.glob(os.path.join(base, p)), key=os.path.getmtime, reverse=True)
        if hits:
            return hits[0]
    return None


def get_version(dev):
    out = dev.shell("dumpsys package %s | grep versionName" % dev.pkg)
    for line in out.splitlines():
        if "versionName" in line:
            return line.split("=")[-1].strip()
    return "?"


def push_fixtures(dev, fixtures):
    for key in ("pdf", "epub"):
        src = fixtures[key]
        dst = fixtures["device_pdf_path" if key == "pdf" else "device_epub_path"]
        adb("-s", dev.serial, "push", src, dst, timeout=120)


def case_list(level):
    """返回 [(case_id, name, fn, extra_kwargs)]，按层级选择用例模块。"""
    if level.upper() == "L0":
        from cases.ui import tc_smoke as tc
        return [(cid, name, fn, {}) for cid, name, fn in tc.ALL]
    if level.upper() == "L1":
        from cases.ui import tc_function as tc
        return [(cid, name, fn, kw or {}) for cid, name, fn, kw in tc.ALL]
    if level.upper() == "L2":
        from cases.ui import tc_special as tc
        return [(cid, name, fn, kw or {}) for cid, name, fn, kw in tc.ALL]
    raise ValueError("未知层级 " + level)


def run_level(dev, level, apk_path, cfg, fixtures, run_dir, case_filter=None):
    version = get_version(dev)
    dev.results.append(dict(case_id="ENV", name="版本确认", status="PASS", ms=0, attempts=1,
                            layer="env", priority="-",
                            note="versionName=%s pkg=%s" % (version, dev.pkg),
                            evidence=""))
    cases = case_list(level)
    if case_filter:
        want = set(c.strip().upper() for c in case_filter.split(",") if c.strip())
        cases = [c for c in cases if c[0].upper() in want]
        log("[%s] --cases 过滤: %d 例" % (dev.serial, len(cases)))
    dev.progress_total = len(cases)
    for cid, name, fn, kw in cases:
        kw = dict(kw)
        argnames = fn.__code__.co_varnames[:fn.__code__.co_argcount]
        if "fixtures" in argnames:
            kw.setdefault("fixtures", dict(fixtures))
        if "cfg" in argnames:
            kw.setdefault("cfg", cfg)
        if cid == "SM-01":
            dev.run_case(cid, name, fn, apk_path=apk_path, **kw)
            push_fixtures(dev, fixtures)
        elif cid == "SM-04":
            kw.setdefault("pdf_path", fixtures["device_pdf_path"])
            dev.run_case(cid, name, fn, **kw)
        elif cid == "SM-05":
            kw.setdefault("epub_path", fixtures["device_epub_path"])
            dev.run_case(cid, name, fn, **kw)
        else:
            dev.run_case(cid, name, fn, **kw)
    return version


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--level", default="L0", choices=["L0", "L1", "L2"])
    ap.add_argument("--serial", action="append", help="指定设备 serial，可多次")
    ap.add_argument("--avd", action="store_true", help="使用 AVD 模拟器（UI 层）")
    ap.add_argument("--flavor", default="pro", choices=["pro", "fdroid"])
    ap.add_argument("--apk", help="显式指定 APK 路径")
    ap.add_argument("--cases", help="只跑指定用例（逗号分隔，如 FN-10,FN-12；ENV 版本确认保留）")
    ap.add_argument("--serial-exec", action="store_true", help="设备间串行执行（调试）")
    args = ap.parse_args()

    devices_cfg = json.load(open(os.path.join(CFG_DIR, "devices.json"), encoding="utf-8"))
    cases_cfg = load_yaml(os.path.join(CFG_DIR, "cases.yaml"))

    if args.avd:
        devices = devices_cfg.get("avd_devices", [])
        if not devices:
            print("config 中未配置 avd_devices"); sys.exit(1)
        layer_tag = "ui-avd"
    else:
        devices = devices_cfg["devices"]
        layer_tag = "ui-device"
    if args.serial:
        devices = [d for d in devices if d["serial"] in args.serial]
    if not devices:
        print("没有匹配的设备"); sys.exit(1)

    pkg = devices_cfg["flavors"][args.flavor]["applicationId"]
    run_id = "%s_%s_%s" % (datetime.now().strftime("%Y%m%d-%H%M%S"), args.level.upper(), layer_tag)
    run_dir = os.path.join(ROOT, "results", run_id)
    os.makedirs(run_dir, exist_ok=True)
    sys.stdout = Tee(os.path.join(run_dir, "run.log"))
    with open(os.path.join(ROOT, "results", "LATEST.txt"), "w") as f:
        f.write(run_id)

    log("运行标识: %s | flavor=%s pkg=%s level=%s devices=%s" %
        (run_id, args.flavor, pkg, args.level, [d["serial"] for d in devices]))
    fixtures = devices_cfg["fixtures"]

    def worker(meta):
        try:
            dev = Device(meta["serial"], meta, args.flavor, pkg, cases_cfg, run_dir=run_dir)
        except Exception as e:
            log("[%s] 设备不可用,本机用例记为 SKIP: %s" % (meta["serial"], e))
            return dict(serial=meta["serial"], meta=meta, flavor=args.flavor, version="offline",
                        results=[dict(case_id="ENV", name="设备连接", status="SKIP", ms=0, attempts=1,
                                      layer="env", priority="P0", note="device not online: %s" % e,
                                      evidence="")])
        dev.wake_unlock()
        abilist = dev.shell("getprop ro.product.cpu.abilist").strip()
        apk = args.apk or find_apk(args.flavor, abilist)
        if not apk:
            log("[%s] 找不到匹配 ABI(%s) 的 APK" % (meta["serial"], abilist))
            dev.results.append(dict(case_id="ENV", name="APK 选择", status="FAIL", ms=0, attempts=1,
                                    layer="env", priority="P0", note="no apk for abi " + abilist,
                                    evidence=""))
            return dict(serial=meta["serial"], meta=meta, flavor=args.flavor,
                        version="?", results=dev.results)
        log("[%s] APK: %s" % (meta["serial"], apk))
        ver = run_level(dev, args.level, apk, cases_cfg, fixtures, run_dir, case_filter=args.cases)
        return dict(serial=meta["serial"], meta=meta, flavor=args.flavor, version=ver,
                    results=dev.results)

    if args.serial_exec:
        runs = [worker(m) for m in devices]
    else:
        with ThreadPoolExecutor(max_workers=len(devices)) as ex:
            runs = list(ex.map(worker, devices))

    report_path = write_report(runs, os.path.join(run_dir, "report.md"),
                               meta=dict(run_id=run_id, level=args.level, flavor=args.flavor))
    total = sum(len(r["results"]) for r in runs)
    npass = sum(1 for r in runs for c in r["results"] if c["status"] == "PASS")
    nskip = sum(1 for r in runs for c in r["results"] if c["status"] == "SKIP")
    nfail = total - npass - nskip
    log("报告: %s" % report_path)
    log("结果: %d PASS / %d FAIL / %d SKIP（共 %d）" % (npass, nfail, nskip, total))
    sys.exit(0 if nfail == 0 else 2)


if __name__ == "__main__":
    main()
