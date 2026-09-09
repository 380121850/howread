# -*- coding: utf-8 -*-
import sys, time
sys.path.insert(0, r"Z:\opt\librera\LibreraReader\ci\autotest")
from lib.driver import Device
import json

meta = [d for d in json.load(open(r"Z:\opt\librera\LibreraReader\ci\autotest\config\devices.json", encoding="utf-8"))["devices"] if d["serial"] == "3JJ4C18904004595"][0]
cfg = {"main_activity": "com.foobnix.ui2.MainTabs2", "launcher_timeout_s": 10, "ui_timeout_s": 8}
dev = Device("3JJ4C18904004595", meta, "pro", "com.leestudio.howread.pro.reader", cfg)
dev.wake_unlock()

print("ls Download:", dev.shell("ls /sdcard/Download/ | grep -E 'big25|alice'").strip())
dev.d.app_stop(dev.pkg)
time.sleep(1.5)
dev.shell('am start -a android.intent.action.VIEW -d "file:///sdcard/Download/big25.pdf" -t application/pdf com.leestudio.howread.pro.reader')
for i in range(14):
    time.sleep(1)
    top = dev.shell("dumpsys activity activities | grep mResumedActivity").strip()
    print(i, top[:130], flush=True)
    if "ViewActivity" in top:
        print("READER OK")
        break
