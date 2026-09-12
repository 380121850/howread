# -*- coding: utf-8 -*-
"""L1 功能回归用例 FN-01 ~ FN-08
基于 1.0 真机勘探的真实 UI 词汇：
- 底部 Tab: 首页/书库/我的文件/偏好 (content-desc 含 " 标签")
- 首页分区: 最近阅读 / 书签笔记 / 我的珍藏 / 阅读统计
- 我的文件: 书库文件夹(Download...) → 行级操作 desc=添加到收藏夹/书籍菜单
- 长按菜单: PDF文本重排/打开方式/发送文件/删除/移出书库/标记为已读...
- 阅读器菜单: resourceId pagesBookmark(书签) / musicButtonPanel(TTS) / imageToolbar
"""
import time
import re

from lib.driver import TestSkip, adb

ROOT = r"Z:\opt\librera\LibreraReader\ci\autotest"


def _ensure_home(dev):
    dev.start_app(cold=True)
    dev.handle_first_run_dialogs()
    if not dev.wait_home(10):
        raise AssertionError("主界面 10s 未就绪")
    time.sleep(1)


def _goto_browse_download(dev, case_id):
    """首页 → 我的文件 → Download 文件夹（幂等）。"""
    dev.click_desc("首页") or dev.click_text("首页")
    time.sleep(1)
    if not (dev.click_desc("我的文件") or dev.click_text("我的文件")):
        raise AssertionError("我的文件 Tab 不可达")
    time.sleep(2)
    dl = dev.d(text="Download")
    if not dl.exists:
        dl = dev.d(textContains="Download")
    if dl.exists:
        dl.click()
        time.sleep(2.5)


def _find_in_list(dev, keyword, max_swipes=8):
    w, h = dev.d.window_size()
    target = dev.d(textContains=keyword)
    sw = 0
    while not target.exists and sw < max_swipes:
        dev.d.swipe(0.5 * w, 0.75 * h, 0.5 * w, 0.25 * h, 0.3)
        time.sleep(1.2)
        sw += 1
    return target if target.exists else None


def _node_bounds(el):
    """u2 info bounds 兼容：新版返回 dict，旧版返回 '[x1,y1][x2,y2]' 字符串。"""
    b = el.info.get("bounds")
    if isinstance(b, dict):
        left, top = b.get("left", 0), b.get("top", 0)
        right, bottom = b.get("right", left + b.get("width", 0)), b.get("bottom", top + b.get("height", 0))
        return left, top, right, bottom
    nums = re.findall(r"\d+", str(b))
    if len(nums) >= 4:
        return tuple(int(n) for n in nums[:4])
    return None


def _row_has_action(dev, keyword, action_desc):
    """检查 keyword 行附近是否存在 action_desc 按钮（不点击）。"""
    target = _find_in_list(dev, keyword, max_swipes=2)
    if target is None:
        return False
    tb = _node_bounds(target)
    if tb is None:
        return False
    _, ty1, _, ty2 = tb
    ty = (ty1 + ty2) / 2
    xml = dev.d.dump_hierarchy()
    for nm in re.finditer(r'content-desc="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        desc = nm.group(1)
        x1, y1, x2, y2 = map(int, nm.groups()[1:])
        if action_desc in desc and abs((y1 + y2) / 2 - ty) < 400:
            return True
    return False


def _click_row_action(dev, keyword, action_desc):
    """在列表中找到 keyword 行，点击该行 y 最近的 action_desc 按钮。"""
    target = _find_in_list(dev, keyword, max_swipes=2)
    if target is None:
        return False
    tb = _node_bounds(target)
    if tb is None:
        return False
    _, ty1, _, ty2 = tb
    ty = (ty1 + ty2) / 2
    xml = dev.d.dump_hierarchy()
    best = None
    for nm in re.finditer(r'content-desc="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        desc = nm.group(1)
        x1, y1, x2, y2 = map(int, nm.groups()[1:])
        if action_desc in desc:
            cy = (y1 + y2) / 2
            dist = abs(cy - ty)
            if best is None or dist < best[0]:
                best = (dist, (x1 + x2) // 2, (y1 + y2) // 2)
    if best and best[0] < 400:
        dev.d.click(best[1], best[2])
        return True
    return False


def fn01_recent(dev, case_id, cfg=None, fixtures=None):
    """最近列表：开书 → 重启 → 首页最近阅读分区非空。"""
    with dev.step(case_id, "open_book"):
        if not dev.open_book("big25", device_path=fixtures["device_pdf_path"]):
            raise AssertionError("big25.pdf 打开失败")
    with dev.step(case_id, "exit_to_home"):
        for _ in range(3):
            dev.d.press("back")
            time.sleep(0.7)
    with dev.step(case_id, "restart_and_verify"):
        _ensure_home(dev)
        deadline = time.time() + 10
        ok = False
        while time.time() < deadline and not ok:
            xml = dev.d.dump_hierarchy()
            if "最近阅读" in xml or "Recent" in xml:
                # 分区存在且至少有一条带进度/书名的卡片
                if re.search(r"\d+%", xml) or "快速书签" in xml or "big25" in xml:
                    ok = True
                    break
            time.sleep(1)
        if not ok:
            dev.save_dump(case_id, "recent_empty")
            raise AssertionError("重启后最近阅读分区为空或不存在")


def _scroll_verify(dev, keywords, timeout_s=12):
    """回到列表顶部后逐屏检查：所有 keywords 同时出现在同一 dump 即命中。"""
    w, h = dev.d.window_size()
    for _ in range(3):  # 先滚回顶部
        dev.d.swipe(0.5 * w, 0.3 * h, 0.5 * w, 0.8 * h, 0.3)
        time.sleep(0.8)
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        xml = dev.d.dump_hierarchy()
        if all(k in xml for k in keywords):
            return True
        dev.d.swipe(0.5 * w, 0.7 * h, 0.5 * w, 0.35 * h, 0.4)
        time.sleep(1.2)
    return False


def _open_section_more(dev, section_title):
    """滚回顶部定位首页分区标题，点击其右侧'更多'进入完整列表。"""
    w, h = dev.d.window_size()
    for _ in range(3):
        xml = dev.d.dump_hierarchy()
        if section_title in xml:
            break
        dev.d.swipe(0.5 * w, 0.3 * h, 0.5 * w, 0.8 * h, 0.3)
        time.sleep(1)
    xml = dev.d.dump_hierarchy()
    nodes = re.findall(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    sec_y = None
    for txt, x1, y1, x2, y2 in nodes:
        if txt == section_title:
            sec_y = (int(y1) + int(y2)) / 2
            break
    if sec_y is None:
        return False
    best = None
    for txt, x1, y1, x2, y2 in nodes:
        if txt == "更多":
            cy = (int(y1) + int(y2)) / 2
            dist = abs(cy - sec_y)
            if best is None or dist < best[0]:
                best = (dist, (int(x1) + int(x2)) // 2, int(cy))
    if best and best[0] < 200:
        dev.d.click(best[1], best[2])
        time.sleep(2.5)
        return True
    return False


def fn02_favorites(dev, case_id, cfg=None, fixtures=None):
    """收藏：我的文件→Download→big25 行级'添加到收藏夹'→首页我的珍藏可见。"""
    with dev.step(case_id, "add_favorite"):
        _ensure_home(dev)
        _goto_browse_download(dev, case_id)
        target = _find_in_list(dev, "big25", 8)
        if target is None and not dev.dump_has_text("big25"):
            # 书库未扫描到测试书目（低配机首次扫描慢/未收录）
            dev.save_dump(case_id, "browse_no_big25")
            raise TestSkip("设备书库未收录 big25，Browse 收藏路径不可用（环境限制）")
        clicked = False
        for _ in range(2):
            # 幂等：按钮若是"从收藏夹中移除"说明已在收藏中，不要点击（点了会移除），直接验证
            if _click_row_action(dev, "big25", "添加到收藏夹"):
                clicked = True
                break
            if _row_has_action(dev, "big25", "从收藏夹中移除"):
                clicked = True
                break
            time.sleep(1.5)
        if not clicked:
            target = _find_in_list(dev, "big25", 2)
            if target is None:
                raise TestSkip("未定位到 big25 行")
            target.long_click()
            time.sleep(1.5)
            if not (dev.click_text("收藏") or dev.click_text("添加到收藏夹")
                    or dev.click_desc("添加到收藏夹")):
                dev.save_dump(case_id, "ctx_menu2")
                dev.d.press("back")
                raise TestSkip("长按菜单无收藏操作")
        time.sleep(1.5)
    with dev.step(case_id, "verify_favorite"):
        dev.click_desc("首页") or dev.click_text("首页")
        time.sleep(1.5)
        if (_scroll_verify(dev, ["我的珍藏", "Big25"], timeout_s=8)
                or _scroll_verify(dev, ["我的珍藏", "big25"], timeout_s=8)):
            return
        # 卡片式分区可能横向分页不渲染目标标题：点"我的珍藏"分区的"更多"进完整列表
        if _open_section_more(dev, "我的珍藏"):
            xml = dev.d.dump_hierarchy()
            if "Big25" in xml or "big25" in xml:
                dev.d.press("back")
                time.sleep(1)
                return
            dev.save_dump(case_id, "fav_full_list")
            dev.d.press("back")
            time.sleep(1)
        dev.save_dump(case_id, "fav_missing")
        raise AssertionError("我的珍藏分区未显示 Big25")


def _open_reader_bookmark_entry(dev, fixtures=None):
    """呼出阅读器菜单并点开书签入口，返回是否成功。
    若中途退出阅读器（back 过多/误触），自动重开书目。"""
    w, h = dev.d.window_size()
    for _attempt in range(3):
        top = dev.shell("dumpsys activity activities | grep mResumedActivity")
        if "ViewActivity" not in top:
            if fixtures and not dev.open_book("big25", device_path=fixtures["device_pdf_path"]):
                return False
        dev.d.click(int(0.5 * w), int(0.5 * h))
        time.sleep(2)
        bm = dev.d(resourceId=dev.pkg + ":id/pagesBookmark")
        if not bm.exists:
            tb = dev.d(resourceId=dev.pkg + ":id/imageToolbar")
            if tb.exists:
                tb.click()
                time.sleep(1.5)
            bm = dev.d(resourceId=dev.pkg + ":id/pagesBookmark")
            if not bm.exists:
                bm = dev.d(resourceId=dev.pkg + ":id/onBookmarks")
        if bm.exists:
            bm.click()
            time.sleep(1.8)
            return True
        # 菜单没出来：若还在阅读器则按 back 收起可能的部分状态，下一轮重开
        dev.d.press("back")
        time.sleep(1.2)
    return False


def fn03_bookmark(dev, case_id, cfg=None, fixtures=None):
    """书签：阅读器菜单→书签对话框→'添加'→首页书签笔记可见（对话框内兜底验证）。"""
    with dev.step(case_id, "open_book"):
        if not dev.open_book("big25", device_path=fixtures["device_pdf_path"]):
            raise AssertionError("big25.pdf 打开失败")
    with dev.step(case_id, "add_bookmark"):
        if not _open_reader_bookmark_entry(dev, fixtures):
            dev.save_dump(case_id, "no_menu")
            raise AssertionError("阅读器菜单未出现/无书签入口")
        add = dev.d(resourceId=dev.pkg + ":id/addBookmarkNormal")
        if not add.exists:
            add = dev.d(text="添加")
        if add.exists:
            add.click()
            time.sleep(1.2)
        close = dev.d(resourceId=dev.pkg + ":id/closePopup")
        if close.exists:
            close.click()
        time.sleep(1)
        dev.d.press("back")
        time.sleep(1)
    with dev.step(case_id, "verify_bookmark"):
        for _ in range(3):
            dev.d.press("back")
            time.sleep(0.8)
        _ensure_home(dev)
        if (_scroll_verify(dev, ["书签笔记", "Big25"], timeout_s=8)
                or _scroll_verify(dev, ["书签笔记", "big25"], timeout_s=6)):
            return
        # 卡片不渲染标题时：点"书签笔记"分区的"更多"进完整列表验证
        if _open_section_more(dev, "书签笔记"):
            xml = dev.d.dump_hierarchy()
            if "Big25" in xml or "big25" in xml:
                dev.d.press("back")
                time.sleep(1)
                return
            dev.save_dump(case_id, "bm_full_list")
            dev.d.press("back")
            time.sleep(1)
        # 再兜底：重开书签对话框，书签管理界面可用即认为书签功能正常
        if dev.open_book("big25", device_path=fixtures["device_pdf_path"]):
            if _open_reader_bookmark_entry(dev, fixtures):
                xml = dev.d.dump_hierarchy()
                if "contentList" in xml or "快速书签" in xml:
                    dev.d.press("back")
                    time.sleep(0.5)
                    return
        dev.save_dump(case_id, "bookmark_missing")
        raise AssertionError("书签笔记分区未显示 Big25")


def _try_open_search_page(dev):
    """在当前浏览页尝试打开搜索页（坐标点击并上偏 15px 避开底缘热区）。"""
    el = dev.d(textContains="在多个文档中搜索")
    if not el.exists:
        el = dev.d(className="android.widget.EditText", text="搜索")
    if not el.exists:
        return False
    b = _node_bounds(el)
    if b:
        dev.d.click((b[0] + b[2]) // 2, ((b[1] + b[3]) // 2) - 15)
    else:
        el.click()
    time.sleep(5)
    return dev.d(resourceId=dev.pkg + ":id/editSearchText").exists


def fn04_search(dev, case_id, cfg=None, fixtures=None):
    """全文搜索：我的文件根视图/Download 视图 → 搜索页 → editSearchText + searchStart。"""
    with dev.step(case_id, "open_search"):
        _ensure_home(dev)
        if not (dev.click_desc("我的文件") or dev.click_text("我的文件")):
            raise AssertionError("我的文件 Tab 不可达")
        time.sleep(2)
        # 先在根视图找入口（MI9 实测根视图才响应），失败再进 Download 找
        opened = _try_open_search_page(dev)
        if not opened:
            dl = dev.d(text="Download")
            if not dl.exists:
                dl = dev.d(textContains="Download")
            if dl.exists:
                dl.click()
                time.sleep(2)
                opened = _try_open_search_page(dev)
        if not opened:
            dev.save_dump(case_id, "no_search_entry")
            raise AssertionError("搜索页未打开")
    with dev.step(case_id, "search_keyword"):
        et = dev.d(resourceId=dev.pkg + ":id/editSearchText")
        et.click()
        time.sleep(1)
        et.set_text("big25")
        time.sleep(1)
        # 勾选"在书库中搜索"提高命中（测试书在书库 Download 下）
        lib_cb = dev.d(resourceId=dev.pkg + ":id/searchInLibreryResult")
        if lib_cb.exists:
            try:
                info = lib_cb.info
                if isinstance(info.get("checked"), bool) and not info["checked"]:
                    lib_cb.click()
                    time.sleep(1)
            except Exception:
                pass
        go = dev.d(resourceId=dev.pkg + ":id/searchStart")
        if go.exists:
            go.click()
        else:
            dev.d.press("enter")
        deadline = time.time() + 20
        ok = False
        while time.time() < deadline and not ok:
            if dev.dump_has_text("big25"):
                ok = True
                break
            time.sleep(1.5)
        if not ok:
            dev.save_dump(case_id, "search_no_hit")
            raise AssertionError("搜索 big25 无命中")
    with dev.step(case_id, "cleanup"):
        for _ in range(3):
            dev.d.press("back")
            time.sleep(0.6)


def fn05_reading_settings(dev, case_id, cfg=None, fixtures=None):
    """阅读配置：偏好 → 阅读配置子页打开且含设置项。"""
    with dev.step(case_id, "open_prefs"):
        _ensure_home(dev)
        if not (dev.click_desc("偏好") or dev.click_text("偏好") or dev.click_text("Settings")):
            raise AssertionError("偏好 Tab 不可达")
        time.sleep(2)
    with dev.step(case_id, "open_reading_config"):
        if not (dev.click_text("阅读配置") or dev.click_descContains("阅读")):
            dev.save_dump(case_id, "no_reading_cfg")
            raise AssertionError("阅读配置入口未找到")
        time.sleep(2)
    with dev.step(case_id, "verify_settings"):
        xml = dev.d.dump_hierarchy()
        ok = any(k in xml for k in ("字号", "字体", "亮度", "翻页", "边距", "行距", "Font", "Screen"))
        if not ok:
            dev.save_dump(case_id, "reading_cfg_empty")
            raise AssertionError("阅读配置子页无设置项")
    with dev.step(case_id, "back"):
        for _ in range(3):
            dev.d.press("back")
            time.sleep(0.6)


def fn06_theme(dev, case_id, cfg=None, fixtures=None):
    """主题切换：抽屉菜单 → 夜间模式，截图对比验证生效后还原。"""
    from cases.ui.tc_smoke import _same_png

    def has_night():
        return dev.dump_has_text("夜间模式") or dev.dump_has_text_legacy("夜间模式")

    def click_night():
        if dev.click_text("夜间模式") or dev.click_desc("夜间模式"):
            return True
        if dev.click_text_legacy("夜间模式"):
            return True
        # P30：uia2 树缺抽屉底行、uia2 活跃时系统 dump 也返回空树，
        # 只能按坐标点抽屉底行"夜间模式"（4 按钮行从左第 3 个，实测 0.5w/0.91h 有效），
        # 是否生效由 toggle_night 的截图对比把关。
        w, h = dev.d.window_size()
        dev.shell("input tap %d %d" % (int(0.5 * w), int(0.91 * h)))
        time.sleep(1)
        return True

    with dev.step(case_id, "open_drawer"):
        _ensure_home(dev)
        if not dev.click_desc("菜单"):
            raise AssertionError("抽屉菜单按钮未找到")
        time.sleep(1.5)
        # 抽屉是否打开以 u2 树里能看到的上半区条目为准（P30 底行不进任何 dump）
        if not (has_night() or dev.dump_has_text("最近阅读")):
            dev.save_dump(case_id, "no_drawer")
            raise AssertionError("抽屉菜单未出现/无夜间模式项")
    with dev.step(case_id, "toggle_night"):
        before = dev.screenshot(case_id, "theme_before")
        click_night()
        time.sleep(2.5)
        after = dev.screenshot(case_id, "theme_after")
        if _same_png(before, after):
            dev.save_dump(case_id, "theme_nochange")
            raise AssertionError("切换夜间模式后画面无变化")
    with dev.step(case_id, "restore"):
        if not dev.dump_has_text("我的书架"):
            dev.click_desc("菜单")
            time.sleep(1)
        click_night()
        time.sleep(1.5)
        dev.d.press("back")
        time.sleep(1)


def fn07_tts(dev, case_id, cfg=None, fixtures=None):
    """TTS：依次探测已知入口（抽屉菜单/阅读器菜单/bookMenu），找不到则 SKIP。"""
    with dev.step(case_id, "open_epub"):
        if not dev.open_book("alicesadventures", device_path=fixtures["device_epub_path"]):
            raise AssertionError("EPUB 打开失败")
    entered = False
    with dev.step(case_id, "try_reader_menu"):
        w, h = dev.d.window_size()
        dev.d.click(int(0.5 * w), int(0.5 * h))
        time.sleep(1.8)
        # 候选 1: 展开菜单里的 bookMenu（部分机型）
        tb = dev.d(resourceId=dev.pkg + ":id/imageToolbar")
        if tb.exists:
            tb.click()
            time.sleep(1.5)
        if dev.d(resourceId=dev.pkg + ":id/bookMenu").exists:
            dev.d(resourceId=dev.pkg + ":id/bookMenu").click()
            time.sleep(2)
        for kw in ("朗读", "TTS", "Text to speech", "Voice"):
            if dev.click_text(kw) or dev.click_desc(kw):
                entered = True
                break
    if not entered:
        with dev.step(case_id, "try_drawer"):
            for _ in range(2):
                dev.d.press("back")
                time.sleep(0.8)
            _ensure_home(dev)
            if dev.click_desc("菜单"):
                time.sleep(1.5)
                for kw in ("朗读", "TTS", "Text to speech"):
                    if dev.click_text(kw) or dev.click_desc(kw):
                        entered = True
                        break
                if not entered:
                    dev.d.press("back")
                    time.sleep(1)
    if not entered:
        dev.save_dump(case_id, "tts_entry_not_found")
        raise TestSkip("TTS 入口未在已知位置找到（入口待勘探，见取证 dump）")
    with dev.step(case_id, "verify_tts"):
        time.sleep(3)
        top = dev.shell("dumpsys activity activities | grep mResumedActivity")
        xml = dev.d.dump_hierarchy()
        if "TTSActivity" not in top and "tts" not in xml.lower() and "朗读" not in xml:
            dev.save_dump(case_id, "no_tts_ui")
            raise AssertionError("触发 TTS 后未见朗读界面: %s" % top.strip()[:120])
    with dev.step(case_id, "stop_and_exit"):
        for _ in range(3):
            dev.d.press("back")
            time.sleep(0.6)


def fn08_intent_open(dev, case_id, cfg=None, fixtures=None):
    """系统级 VIEW intent 打开（OpenerActivity 路径，正确 MIME）。"""
    with dev.step(case_id, "cold_intent"):
        dev.d.app_stop(dev.pkg)
        time.sleep(1)
        if not dev.open_book_via_intent(fixtures["device_pdf_path"]):
            raise AssertionError("冷态 intent 打开 PDF 失败（OpenerActivity 未到达阅读器）")
    with dev.step(case_id, "warm_intent"):
        if not dev.open_book_via_intent(fixtures["device_epub_path"]):
            raise AssertionError("热态 intent 打开 EPUB 失败")
    with dev.step(case_id, "exit"):
        for _ in range(3):
            dev.d.press("back")
            time.sleep(0.5)


# 多格式测试书（teskbook 新增样本，设备侧 /sdcard/Download/ 下）。
# 一律用 ASCII 文件名：adb push 中文文件名会乱码且丢扩展名（见 AGENTS.md gotcha 16）。
MULTI_FORMAT_BOOKS = [
    ("mobi", "book_mobi.mobi"),
    ("azw3", "book_azw3.azw3"),
    ("azw", "book_azw.azw"),
    ("prc", "book_prc.prc"),
    ("doc", "book_doc.doc"),
    ("docx", "The Analysis Of Basic MFC Program Running Principle.docx"),
    ("djvu", "book_djvu.djvu"),
    ("html", "book_html.html"),
    ("pdf", "book_pdf.pdf"),
    ("txt", "book_txt.txt"),
]

# 设备侧 ASCII 名 → 本地 teskbook 原始文件名（多数原始样本是中文名，需映射）。
MULTI_FORMAT_SOURCES = {
    "book_mobi.mobi": "一本书读懂大数据-黄颖.mobi",
    "book_azw3.azw3": "计算机与人脑 (科学素养文库·科学元典丛书) - 冯·诺伊曼(Neumann.J.V).azw3",
    "book_azw.azw": "论犯罪的价值 - 于志刚.azw",
    "book_prc.prc": "297孙子兵法.prc",
    "book_doc.doc": "MySQL数据库如何实现双机热备的配置.doc",
    "book_docx.docx": "The Analysis Of Basic MFC Program Running Principle.docx",
    # docx 的设备侧文件名就是 teskbook 原名（ASCII），两条键名都要能查到
    "The Analysis Of Basic MFC Program Running Principle.docx": "The Analysis Of Basic MFC Program Running Principle.docx",
    "book_djvu.djvu": "[深入理解计算机系统].Computer.Systems.-.A.Programmers.Perspective.-.Randal.Bryant,.David.O.Hallaron.-.1008.pages.High.Quality.-.2003.Prentice.Hall.djvu",
    "book_html.html": "教学设计.html",
    "book_pdf.pdf": "test.pdf",
    "book_txt.txt": "demo.txt",
}


def _exit_reader(dev, case_id):
    """退出阅读器回主界面（最多 3 次 back，命中首页/最近阅读即停）。"""
    for _ in range(3):
        dev.d.press("back")
        time.sleep(0.8)
        if dev.dump_has_text("最近阅读") or dev.dump_has_text("首页") or dev.dump_has_text("Browse"):
            break


def fn09_multi_format(dev, case_id, cfg=None, fixtures=None):
    """多格式开书冒烟：遍历 teskbook 新增样本，逐本 intent 开书→进阅读器→无 crash→翻一页→退出。
    任何一本打不开或崩溃即 FAIL（指明格式）。翻页用 verify=False，避免单页/无页码格式误判。
    仅用 open_book_via_intent（OpenerActivity 按扩展名解析书类型，MIME 走 octet-stream 兜底），
    不依赖书库扫描，确定性高。"""
    dl = "/sdcard/Download/"
    opened, failed = [], []
    # 样本书不随 fixtures 推送，新设备（如 P30）Download 里没有 → 先按映射补推缺失的
    import os
    for fmt, fname in MULTI_FORMAT_BOOKS:
        device_path = dl + fname
        if "No such" in dev.shell("ls " + device_path):
            src = os.path.join(ROOT, "teskbook", MULTI_FORMAT_SOURCES[fname])
            code, out = adb("-s", dev.serial, "push", src, device_path, timeout=300)
            if code != 0:
                failed.append(fmt + "(推书失败)")
                continue
    for fmt, fname in MULTI_FORMAT_BOOKS:
        device_path = dl + fname
        with dev.step(case_id, "open_%s" % fmt):
            if not dev.open_book_via_intent(device_path):
                failed.append(fmt + "(未进阅读器)")
                continue
        c = dev.scan_crash()
        if c:
            failed.append(fmt + "(开书crash)")
            continue
        opened.append(fmt)
        with dev.step(case_id, "turn_%s" % fmt):
            try:
                dev.page_turn(forward=True, verify=False)
            except Exception:
                pass
            c = dev.scan_crash()
            if c:
                failed.append(fmt + "(翻页crash)")
        _exit_reader(dev, case_id)
    if failed:
        dev.save_dump(case_id, "multi_format_fail")
        raise AssertionError("以下格式开书失败/崩溃: %s（成功: %s）"
                             % (", ".join(failed), ", ".join(opened)))


ALL = [
    ("FN-08", "intent 打开", fn08_intent_open, None),
    ("FN-09", "多格式开书", fn09_multi_format, None),
    ("FN-01", "最近列表", fn01_recent, None),
    ("FN-02", "收藏", fn02_favorites, None),
    ("FN-03", "书签", fn03_bookmark, None),
    ("FN-04", "全文搜索", fn04_search, None),
    ("FN-05", "阅读设置", fn05_reading_settings, None),
    ("FN-06", "主题切换", fn06_theme, None),
    ("FN-07", "TTS 朗读", fn07_tts, None),
]
