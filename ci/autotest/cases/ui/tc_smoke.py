# -*- coding: utf-8 -*-
"""L0 冒烟用例 SM-01 ~ SM-07"""
import time


def _same_png(p1, p2):
    try:
        from PIL import Image
        import numpy as np
        a = np.asarray(Image.open(p1).convert("L").resize((64, 64)), dtype=float)
        b = np.asarray(Image.open(p2).convert("L").resize((64, 64)), dtype=float)
        return float(abs(a - b).mean()) < 1.5
    except ImportError:
        import hashlib
        h1 = hashlib.md5(open(p1, "rb").read()).hexdigest()
        h2 = hashlib.md5(open(p2, "rb").read()).hexdigest()
        return h1 == h2


def sm01_install(dev, case_id, apk_path=None):
    assert apk_path, "SM-01 需要 apk_path"
    with dev.step(case_id, "install"):
        ok, msg = dev.install(apk_path)
        if not ok:
            raise AssertionError("安装失败: " + msg)
    with dev.step(case_id, "grant_perms"):
        dev.grant_setup()


def sm02_cold_start(dev, case_id):
    with dev.step(case_id, "cold_start"):
        t = dev.start_app(cold=True)
        dev.handle_first_run_dialogs()
        if not dev.wait_home(10):
            dev.save_dump(case_id, "cold_start_stuck")
            raise AssertionError("主界面关键元素 10s 未出现（启动 %.1fs）" % t)


def sm03_tabs(dev, case_id):
    """底部 Tab 遍历：首页 / 书库 / 我的文件 / 偏好（双语关键字 + content-desc 兜底）。"""
    with dev.step(case_id, "traverse"):
        tabs = [
            (("首页", "Dashboard", "Home"), True),
            (("书库", "Library", "Clouds", "OPDS"), False),
            # "My files": en 底栏 Tab 文案(moon_home_files)——旧词 Browse/Files
            # 已随 10-06 改版漂移,AVD 英文环境两词都不存在导致 SM-03 误报
            (("我的文件", "My files", "Browse", "Files"), False),
            (("偏好", "Settings", "Preferences"), False),
        ]
        unreachable = []
        for kws, is_home in tabs:
            reached = False
            for kw in kws:
                if dev.click_desc(kw) or dev.click_text(kw):
                    reached = True
                    break
            if is_home and not reached:
                reached = dev.exists_text("首页") or dev.exists_text("Dashboard")
            if not reached:
                unreachable.append(kws[0])
                continue
            time.sleep(1.5)
            c = dev.scan_crash()
            if c:
                raise AssertionError("Tab %s crash: %s" % (kws[0], c[:200]))
        if unreachable:
            dev.save_dump(case_id, "tabs_dump")
            raise AssertionError("Tab 不可达: %s" % unreachable)
        # 回首页
        dev.click_desc("首页") or dev.click_text("首页")
        time.sleep(1)


def _open_and_read(dev, case_id, device_path, file_keyword):
    with dev.step(case_id, "open"):
        if not dev.open_book(file_keyword, device_path=device_path):
            raise AssertionError("打开 %s 未进入阅读器" % file_keyword)
    with dev.step(case_id, "page_turn_x5"):
        w, h = dev.d.window_size()
        seen = set()
        for i in range(5):
            res = dev.page_turn(forward=True)
            if isinstance(res, tuple):
                before, after = res
                if before and after and after[0] == before[0]:
                    raise AssertionError("第 %d 次翻页页码未变 %s->%s" % (i + 1, before, after))
                if after:
                    seen.add(after[0])
            c = dev.scan_crash()
            if c:
                raise AssertionError("翻页 %d crash: %s" % (i + 1, c[:200]))
    with dev.step(case_id, "exit_reader"):
        for _ in range(3):
            dev.d.press("back")
            time.sleep(0.8)
            if dev.dump_has_text("最近阅读") or dev.dump_has_text("首页") or dev.dump_has_text("Browse"):
                break


def sm04_open_pdf(dev, case_id, pdf_path=None):
    _open_and_read(dev, case_id, pdf_path, "big25")


def sm05_open_epub(dev, case_id, epub_path=None):
    _open_and_read(dev, case_id, epub_path, "alicesadventures")


def sm06_restart_persistence(dev, case_id):
    with dev.step(case_id, "restart"):
        dev.start_app(cold=True)
        dev.handle_first_run_dialogs()
        deadline = time.time() + 15
        ok = False
        while time.time() < deadline:
            if (dev.exists_text("big25") or dev.exists_text("最近阅读")
                    or dev.exists_text("alicesadventures") or dev.exists_text("Recent")):
                ok = True
                break
            time.sleep(1)
        if not ok:
            dev.save_dump(case_id, "restart_stuck")
            raise AssertionError("重启 15s 后首页未显示最近阅读/已读书目")


def sm07_no_crash(dev, case_id):
    with dev.step(case_id, "final_scan"):
        c = dev.scan_crash()
        if c:
            raise AssertionError("残留 crash/ANR: " + c[:200])


ALL = [
    ("SM-01", "安装/升级安装", sm01_install),
    ("SM-02", "冷启动", sm02_cold_start),
    ("SM-03", "Tab 遍历", sm03_tabs),
    ("SM-04", "打开 PDF 翻页", sm04_open_pdf),
    ("SM-05", "打开 EPUB 翻页", sm05_open_epub),
    ("SM-06", "退出重进持久性", sm06_restart_persistence),
    ("SM-07", "全程无 crash", sm07_no_crash),
]
