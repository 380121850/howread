# -*- coding: utf-8 -*-
"""HowRead 自动测试驱动封装（四层体系 · 真机/AVD UI 层）
- 进度显示：时间戳日志 + 用例计数 + >30s 操作心跳行
- 单用例超时：独立线程 + future.result(timeout)，超时判 FAIL(TIMEOUT) 并强停 app
- 最大重试：失败自动重跑（默认 1 次），结果记录尝试次数
- 结果目录：由 run_all 传入时间戳目录，证据按 设备/用例 归档
"""
import os
import re
import subprocess
import threading
import time
import traceback
from concurrent.futures import ThreadPoolExecutor
from concurrent.futures import TimeoutError
from contextlib import contextmanager
from datetime import datetime

ADB = os.environ.get("ADB", r"C:\Users\lee\AppData\Local\Android\Sdk\platform-tools\adb.exe")


def now():
    return datetime.now().strftime("%H:%M:%S")


def log(msg):
    print("[%s] %s" % (now(), msg), flush=True)


def adb(*args, timeout=60):
    cmd = [ADB] + list(args)
    p = subprocess.run(cmd, capture_output=True, timeout=timeout)
    out = (p.stdout or b"").decode("utf-8", "replace") + (p.stderr or b"").decode("utf-8", "replace")
    return p.returncode, out


class TestSkip(Exception):
    """环境性跳过（不计失败）。"""


class TestFail(AssertionError):
    pass


class Heartbeat:
    """长操作心跳：每 interval 秒打印一行，证明未卡死。"""

    def __init__(self, name, interval=30):
        self.name = name
        self.interval = interval
        self._stop = threading.Event()
        self._t = None

    def _run(self):
        n = 0
        while not self._stop.wait(self.interval):
            n += 1
            log("  ⏳ 心跳 | %s 已运行 %ds" % (self.name, int(self.interval * n)))

    def __enter__(self):
        self._t = threading.Thread(target=self._run, daemon=True)
        self._t.start()
        return self

    def __exit__(self, *a):
        self._stop.set()


class Device:
    """单台设备的测试驱动。flavor 决定被测包名；run_dir 决定证据目录。"""

    def __init__(self, serial, meta, flavor, pkg, cases_cfg, run_dir=None):
        import uiautomator2 as u2
        self.serial = serial
        self.meta = meta
        self.flavor = flavor
        self.pkg = pkg
        self.cfg = cases_cfg
        self.d = u2.connect(serial)
        self.results = []
        self._logcat_lines = 0
        self._sync_logcat()
        base = run_dir or os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                                       "results", "adhoc")
        self.evidence_root = os.path.join(base, "evidence", serial)
        self.progress_index = 0
        self.progress_total = 0

    # ---------- adb / 设备 ----------
    def shell(self, cmd, timeout=60):
        code, out = adb("-s", self.serial, "shell", cmd, timeout=timeout)
        return out

    def install(self, apk_path):
        def _miui():
            adb("-s", self.serial, "push", apk_path, "/data/local/tmp/howread_autotest.apk")
            stop = threading.Event()
            t = threading.Thread(target=self._watch_install_dialog, args=(stop,), daemon=True)
            t.start()
            code, out = adb("-s", self.serial, "shell",
                            "pm install -i com.android.vending -r -t /data/local/tmp/howread_autotest.apk",
                            timeout=180)
            stop.set()
            return code, out

        is_miui = "MI" in self.meta.get("model", "") or self.serial == "48fee174"
        if is_miui:
            code, out = _miui()
        else:
            code, out = adb("-s", self.serial, "install", "-r", "-t", apk_path)
        if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in out:
            adb("-s", self.serial, "uninstall", self.pkg)
            if is_miui:
                code, out = _miui()
            else:
                code, out = adb("-s", self.serial, "install", "-r", "-t", apk_path)
        ok = "Success" in out
        return ok, out.strip().splitlines()[-1] if out.strip() else ""

    def _watch_install_dialog(self, stop_event):
        keywords = ("继续安装", "仍要安装", "安装", "Install anyway", "Continue")
        while not stop_event.is_set():
            try:
                for kw in keywords:
                    el = self.d(text=kw)
                    if el.exists:
                        el.click()
                        return
            except Exception:
                pass
            stop_event.wait(0.5)

    def current_pkg(self):
        try:
            return self.d.app_current().get("package")
        except Exception:
            return None

    def front_pkg_via_adb(self):
        out = self.shell("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' | head -1")
        m = re.search(r"u0\s+([\w.]+)/", out)
        return m.group(1) if m else None

    # ---------- logcat crash 守护 ----------
    def _sync_logcat(self):
        out = self.shell("logcat -d | wc -l")
        try:
            self._logcat_lines = int(out.strip().splitlines()[-1])
        except (ValueError, IndexError):
            self._logcat_lines = 0

    def scan_crash(self):
        out = self.shell("logcat -d")
        lines = out.splitlines()
        new = lines[self._logcat_lines:]
        self._logcat_lines = len(lines)
        joined = "\n".join(new)
        if "FATAL EXCEPTION" in joined:
            i = joined.find("FATAL EXCEPTION")
            return "FATAL EXCEPTION:\n" + joined[i:i + 800]
        if re.search(r"ANR in %s" % re.escape(self.pkg), joined):
            i = joined.find("ANR in")
            return "ANR:\n" + joined[i:i + 500]
        return None

    # ---------- UI 操作与断言 ----------
    def wait_text(self, text, timeout=None):
        timeout = timeout or self.cfg.get("ui_timeout_s", 8)
        try:
            if self.d(text=text).wait(timeout=timeout):
                return True
        except Exception:
            pass
        return self.dump_has_text(text)

    def dump_has_text(self, text):
        try:
            xml = self.d.dump_hierarchy()
            if text in xml:
                return True
            try:
                fixed = xml.encode("gbk", errors="ignore").decode("utf-8", errors="ignore")
                return text in fixed
            except Exception:
                return False
        except Exception:
            return False

    def exists_text(self, text):
        return self.dump_has_text(text)

    def click_desc(self, kw):
        el = self.d(descriptionContains=kw)
        if el.exists:
            try:
                el.click()
                return True
            except Exception:
                pass
        return False

    def click_text(self, text, timeout=None):
        if self.wait_text(text, timeout):
            try:
                self.d(text=text).click()
                return True
            except Exception:
                el = self.d.xpath('//*[@text="%s"]' % text)
                if el.exists:
                    el.click()
                    return True
        return False

    def legacy_dump(self):
        """系统 uiautomator dump 兜底：uia2(wetest) 服务器在部分 EMUI 机型上
        会漏抽屉底部一行节点（实测 P30：设置选项/软件说明/夜间模式/退出不进 uia2 树，
        系统 dump 里有）。注意：界面刚切换后 ~3s 内 dump 会静默失败（空输出），
        须重试。"""
        for _ in range(4):
            try:
                self.shell("uiautomator dump /sdcard/autotest_uic.xml", timeout=30)
                xml = self.shell("cat /sdcard/autotest_uic.xml")
                if xml and "<node" in xml:
                    return xml
            except Exception:
                pass
            time.sleep(2)
        self.shell("rm -f /sdcard/autotest_uic.xml")
        return ""

    def dump_has_text_legacy(self, text):
        try:
            return text in self.legacy_dump()
        except Exception:
            return False

    def click_text_legacy(self, text):
        """按系统 dump 的 bounds 用 input tap 点击（uia2 树缺节点时的兜底）。"""
        try:
            m = re.search(r'text="%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % text,
                          self.legacy_dump())
        except Exception:
            return False
        if not m:
            return False
        x = (int(m.group(1)) + int(m.group(3))) // 2
        y = (int(m.group(2)) + int(m.group(4))) // 2
        self.shell("input tap %d %d" % (x, y))
        return True

    def start_app(self, cold=True):
        if cold:
            self.d.app_stop(self.pkg)
            time.sleep(1)
        self.d.app_start(self.pkg, self.cfg["main_activity"])
        t0 = time.time()
        deadline = t0 + self.cfg.get("launcher_timeout_s", 10)
        while time.time() < deadline:
            if self.current_pkg() == self.pkg:
                return time.time() - t0
            time.sleep(0.2)
        while time.time() < deadline + 3:
            if self.front_pkg_via_adb() == self.pkg:
                return time.time() - t0
            time.sleep(0.2)
        raise TestFail("启动 %ss 后前台包仍不是 %s（当前: %s / %s）" %
                       (self.cfg.get("launcher_timeout_s", 10), self.pkg,
                        self.current_pkg(), self.front_pkg_via_adb()))

    def open_book_via_intent(self, device_path):
        """VIEW intent 打开书（OpenerActivity 只接受特定 MIME）。
        实测 intent 仅在 app 冷态生效（warm 态走 onNewIntent 被 app 忽略），
        故先 force-stop 再投递。选择器（多包名并存时）在统一窗口内轮询点掉。"""
        self.d.app_stop(self.pkg)
        time.sleep(1.2)
        lower = device_path.lower()
        if lower.endswith(".epub"):
            mime = "application/epub"
        elif lower.endswith(".pdf"):
            mime = "application/pdf"
        elif lower.endswith(".mobi") or lower.endswith(".azw3"):
            mime = "application/x-mobipocket-ebook"
        else:
            mime = "application/octet-stream"
        # 指定组件直达，不走系统 MIME 解析：P30 等装有 WPS/华为查看器的机型会把
        # octet-stream/文档类 VIEW intent 直接路由给第三方默认应用，选择器根本不弹。
        self.shell('am start -n %s/com.foobnix.OpenerActivity -a android.intent.action.VIEW -d "file://%s" -t %s'
                   % (self.pkg, device_path, mime))
        deadline = time.time() + max(15, self.cfg.get("launcher_timeout_s", 10) + 5)
        resolver_hits = 0
        while time.time() < deadline:
            top = self.shell("dumpsys activity activities | grep mResumedActivity")
            if "ViewActivity" in top or "TTSActivity" in top:
                time.sleep(2.5)
                return True
            # 选择器处理：点本应用，再点"仅此一次"
            for label in ("好好读", "HowRead"):
                el = self.d(text=label)
                if el.exists:
                    try:
                        el.click()
                        resolver_hits += 1
                        time.sleep(1)
                        break
                    except Exception:
                        pass
            once = self.d(text="仅此一次") if self.d(text="仅此一次").exists else self.d(text="Just once")
            if once.exists:
                try:
                    once.click()
                    resolver_hits += 1
                    time.sleep(1.5)
                except Exception:
                    pass
            # 选择器反复出现超过 4 轮仍进不去：放弃（避免死循环，外层还有重试与超时）
            if resolver_hits >= 8:
                break
            time.sleep(0.5)
        self.screenshot("intent_debug", "intent_fail")
        self.save_dump("intent_debug", "intent_fail_dump")
        return False

    def open_book(self, file_keyword, folder="Download", device_path=None):
        """开书：intent 优先，UI 浏览兜底。"""
        if device_path and self.open_book_via_intent(device_path):
            return True
        return self._open_book_via_browse(file_keyword, folder)

    # ---------- 权限/首启/常亮 ----------
    def wake_unlock(self):
        self.shell("input keyevent KEYCODE_WAKEUP")
        time.sleep(0.8)
        self.shell("settings put global stay_on_while_plugged_in 7")
        self.shell("wm dismiss-keyguard")
        time.sleep(0.5)

    def wait_home(self, timeout=None):
        timeout = timeout or self.cfg.get("launcher_timeout_s", 10)
        deadline = time.time() + timeout
        while time.time() < deadline:
            if (self.exists_text("首页") or self.exists_text("Dashboard")
                    or self.exists_text("最近阅读") or self.exists_text("Recent")
                    or self.click_desc("首页")):
                return True
            time.sleep(0.5)
        return False

    def grant_setup(self):
        for perm in ("android.permission.READ_EXTERNAL_STORAGE",
                     "android.permission.WRITE_EXTERNAL_STORAGE"):
            self.shell("pm grant %s %s" % (self.pkg, perm))
        self.shell("appops set %s MANAGE_EXTERNAL_STORAGE allow" % self.pkg)

    def handle_first_run_dialogs(self):
        handled = False
        for _ in range(3):
            if self.d(text="是").exists and self.dump_has_text("许可"):
                self.d(text="是").click()
                handled = True
                time.sleep(2)
                if self.dump_has_text("所有文件") or self.dump_has_text("All files"):
                    sw = self.d(className="android.widget.Switch")
                    if sw.exists and not sw.info.get("checked"):
                        sw.click()
                        time.sleep(1)
                    self.d.press("back")
                    time.sleep(1.5)
                continue
            break
        return handled

    def _open_book_via_browse(self, file_keyword, folder="Download"):
        self.start_app(cold=True)
        time.sleep(1.5)
        self.handle_first_run_dialogs()
        if not self.wait_home(10):
            raise TestFail("主界面 10s 内未就绪")
        entered = False
        for _ in range(3):
            if self.click_desc("我的文件") or self.click_text("我的文件") \
                    or self.click_desc("Browse") or self.click_text("Browse"):
                time.sleep(1.5)
                if self.dump_has_text("网上书库") or self.dump_has_text("Browse") \
                        or self.dump_has_text("书库文件夹") or self.dump_has_text("OPDS"):
                    entered = True
                    break
        if not entered:
            self.save_dump("open_book", "no_browse_tab")
            raise TestFail("无法进入我的文件 Tab")
        el = self.d(text=folder)
        if not el.exists:
            el = self.d(textContains=folder)
        fw = 0
        while not el.exists and fw < 5:
            time.sleep(2)
            el = self.d(text=folder)
            if not el.exists:
                el = self.d(textContains=folder)
            fw += 1
        if el.exists:
            el.click()
            time.sleep(2.5)
        target = self.d(textContains=file_keyword)
        w, h = self.d.window_size()
        swipes = 0
        while not target.exists and swipes < 8:
            self.d.swipe(0.5 * w, 0.75 * h, 0.5 * w, 0.25 * h, 0.3)
            time.sleep(1.0)
            swipes += 1
        if not target.exists:
            self.save_dump("open_book", "notfound_" + file_keyword)
            raise TestFail("在 %s 中未找到 %s" % (folder, file_keyword))
        target.click()
        deadline = time.time() + self.cfg.get("launcher_timeout_s", 10)
        while time.time() < deadline:
            top = self.shell("dumpsys activity activities | grep mResumedActivity")
            if "ViewActivity" in top or "TTSActivity" in top:
                time.sleep(2.5)
                return True
            time.sleep(0.5)
        self.save_dump("open_book", "noreader_" + file_keyword)
        return False

    def reader_page_num(self):
        w, h = self.d.window_size()
        self.d.click(int(0.5 * w), int(0.5 * h))
        time.sleep(1.8)
        n = None
        try:
            xml = self.d.dump_hierarchy()
            m = re.search(r'"(\d+)\s*/\s*(\d+)"', xml)
            if m:
                n = (int(m.group(1)), int(m.group(2)))
        except Exception:
            pass
        self.d.press("back")
        time.sleep(1)
        return n

    def page_turn(self, forward=True, verify=True):
        w, h = self.d.window_size()
        before = self.reader_page_num() if verify else None
        x = 0.9 * w if forward else 0.1 * w
        self.d.click(int(x), int(0.5 * h))
        time.sleep(1.2)
        if not verify:
            return True
        after = self.reader_page_num()
        if before and after and after[0] == before[0]:
            x1, x2 = (0.8 * w, 0.2 * w) if forward else (0.2 * w, 0.8 * w)
            self.d.swipe(x1, 0.5 * h, x2, 0.5 * h, 0.25)
            time.sleep(1.5)
            after = self.reader_page_num()
        return before, after

    # ---------- 证据 ----------
    def _case_dir(self, case_id):
        d = os.path.join(self.evidence_root, case_id)
        os.makedirs(d, exist_ok=True)
        return d

    def screenshot(self, case_id, name):
        d = self._case_dir(case_id)
        path = os.path.join(d, "%s_%s.png" % (name, datetime.now().strftime("%H%M%S")))
        try:
            self.d.screenshot(path)
        except Exception:
            pass
        return path

    def save_dump(self, case_id, name="ui"):
        d = self._case_dir(case_id)
        path = os.path.join(d, "%s.xml" % name)
        try:
            with open(path, "w", encoding="utf-8") as f:
                f.write(self.d.dump_hierarchy())
        except Exception:
            pass
        return path

    def save_logcat(self, case_id, tag="logcat"):
        d = self._case_dir(case_id)
        path = os.path.join(d, "%s.txt" % tag)
        try:
            out = self.shell("logcat -d -t 2000")
            with open(path, "w", encoding="utf-8", errors="replace") as f:
                f.write(out)
        except Exception:
            pass
        return path

    # ---------- 用例执行骨架：进度 + 超时 + 重试 ----------
    @contextmanager
    def step(self, case_id, name):
        t0 = time.time()
        try:
            yield
            self._sync_logcat()
            crash = self.scan_crash()
            if crash:
                self.screenshot(case_id, "crash_" + name)
                raise TestFail("检测到 crash/ANR @%s: %s" % (name, crash[:400]))
        except TestSkip:
            raise
        except TestFail:
            self.screenshot(case_id, "fail_" + name)
            self.save_dump(case_id, "fail_" + name)
            self.save_logcat(case_id, "fail_logcat")
            raise
        except Exception as e:
            self.screenshot(case_id, "fail_" + name)
            self.save_dump(case_id, "fail_" + name)
            raise TestFail("步骤 %s 异常: %s" % (name, e)) from e
        finally:
            _ = time.time() - t0

    def _run_once(self, case_id, name, fn, args, kwargs, timeout):
        """在工作线程执行单次用例，强制超时。"""
        result = {"status": None, "note": ""}

        def target():
            try:
                self._sync_logcat()
                fn(self, case_id, *args, **kwargs)
                crash = self.scan_crash()
                if crash:
                    result["status"], result["note"] = "FAIL", "crash/ANR: " + crash[:300]
                else:
                    result["status"] = "PASS"
            except TestSkip as e:
                result["status"], result["note"] = "SKIP", str(e)[:400]
            except TestFail as e:
                result["status"], result["note"] = "FAIL", str(e)[:600]
            except AssertionError as e:
                result["status"], result["note"] = "FAIL", str(e)[:600]
            except Exception as e:
                result["status"] = "FAIL"
                result["note"] = "异常: %s | %s" % (e, traceback.format_exc()[-300:])

        th = ThreadPoolExecutor(max_workers=1)
        fut = th.submit(target)
        try:
            fut.result(timeout=timeout)
        except TimeoutError:
            result["status"] = "FAIL(TIMEOUT)"
            result["note"] = "用例超时（>%ds），已放弃等待并强停应用" % timeout
        th.shutdown(wait=False)
        if result["status"] == "FAIL(TIMEOUT)":
            try:
                self.screenshot(case_id, "timeout")
                self.save_dump(case_id, "timeout_dump")
                self.d.app_stop(self.pkg)
                self._sync_logcat()
            except Exception:
                pass
        return result

    def run_case(self, case_id, name, fn, *args, timeout=None, retries=None, **kwargs):
        cfg = self.cfg.get("case_meta", {}).get(case_id, {})
        timeout = timeout or cfg.get("timeout", self.cfg.get("case_timeout_s", 300))
        retries = cfg.get("retries", self.cfg.get("case_retries", 1) if retries is None else retries)
        layer = cfg.get("layer", "ui-device")
        prio = cfg.get("priority", "-")

        self.progress_index += 1
        tag = "[%s][%d/%d]" % (self.serial, self.progress_index, self.progress_total)
        log("%s ▶ %s(%s/%s) %s 开始 [timeout=%ss retries=%s]" %
            (tag, case_id, layer, prio, name, timeout, retries))

        t0 = time.time()
        status, note, attempts = "PASS", "", 0
        d = self._case_dir(case_id)
        with Heartbeat("%s %s" % (case_id, name), interval=self.cfg.get("heartbeat_s", 30)):
            for attempt in range(1, retries + 2):
                attempts = attempt
                res = self._run_once(case_id, name, fn, args, kwargs, timeout)
                status, note = res["status"], res["note"]
                if status in ("PASS", "SKIP"):
                    break
                if attempt <= retries:
                    log("%s ↻ %s 第 %d 次失败，自动重试（%s）" % (tag, case_id, attempt, note[:120]))
                    time.sleep(2)

        ms = int((time.time() - t0) * 1000)
        marker = {"PASS": "✅", "SKIP": "⏭️"}.get(status, "❌" if "FAIL" in status else "•")
        log("%s %s %s %s 结束 %.1fs (尝试%d次)%s" %
            (tag, marker, case_id, name, ms / 1000.0, attempts, (" | " + note[:150]) if note else ""))
        self.results.append(dict(case_id=case_id, name=name, status=status, ms=ms, note=note,
                                 attempts=attempts, layer=layer, priority=prio,
                                 evidence="%s/%s" % (self.serial, case_id)))
        return status == "PASS"
