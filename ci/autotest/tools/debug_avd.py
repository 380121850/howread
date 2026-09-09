# -*- coding: utf-8 -*-
import sys, time
sys.path.insert(0, r"Z:\opt\librera\LibreraReader\ci\autotest")
from lib.driver import Device, adb
import json

meta = json.load(open(r"Z:\opt\librera\LibreraReader\ci\autotest\config\devices.json", encoding="utf-8"))["avd_devices"][0]
cfg = {"main_activity": "com.foobnix.ui2.MainTabs2", "launcher_timeout_s": 10, "ui_timeout_s": 8}
dev = Device("emulator-5554", meta, "pro", "com.leestudio.howread.pro.reader", cfg)
dev.wake_unlock()

print("resumed:", dev.shell("dumpsys activity activities | grep mResumedActivity").strip()[:130])
dev.d.app_stop(dev.pkg)
time.sleep(1)
dev.d.app_start(dev.pkg, cfg["main_activity"])
time.sleep(8)
print("resumed:", dev.shell("dumpsys activity activities | grep mResumedActivity").strip()[:130])
dev.d.screenshot(r"Z:\opt\librera\LibreraReader\ci\autotest\results\avd_manual.png")
xml = dev.d.dump_hierarchy()
print("dump pkgs:", sorted(set(__import__("re").findall(r'package="(com\.[\w.]+)"', xml))))
print("logcat crash:", dev.shell("logcat -d -b crash | tail -20"))
print("logcat howread:", dev.shell("logcat -d | grep -iE 'howread|foobnix|ANR' | tail -15"))
