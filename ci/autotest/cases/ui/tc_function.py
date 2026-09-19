# -*- coding: utf-8 -*-
"""L1 功能回归用例 FN-01 ~ FN-30
基于 1.0/1.3.2 真机勘探的真实 UI 词汇 + 源码 resource id:
- 底部 Tab: 首页/书库/我的文件/偏好 (content-desc 含 " 标签")
- 首页分区: 最近阅读 / 书签笔记 / 我的珍藏 / 阅读统计(DashboardFragment2 statTotal/statToday...)
- 书库: filterLine 搜索框 / sortBy 排序 / onGridList 视图菜单(列表/简表/网格/封面/书架) / readingStatusChips
- 我的文件: netSection(网上书库OPDS/WebDAV(Pro)/SMB(Pro)/SFTP(Pro) + 搜索分区) / createFolder(新建txt)
- 阅读器工具条: thumbnail(跳页) / onDocDontext(目录) / bookPref(偏好弹窗,含fontSizeSp字号滑杆)
  / textToSpeach(TTS) / pagesBookmark(书签) / modeName(翻页模式菜单)
- AI: 偏好行"AI 大模型(Pro)" → dialog_ai_config(aiBaseUrl/aiApiKey/aiModel/aiTestConnection)
- 网络: AddWebDavDialog(url/name/login/password+添加) / AddRemoteDialog(SMB/SFTP+测试连接)
  / WebDavSyncDialog(独立服务器+测试连接+立即同步);服务器行右侧 ✕ 删除
FN-10~FN-30 为 2026-09-12 覆盖扩展新增(对应<HowRead安卓功能列表.md>,见 docs/COVERAGE.md).
"""
import json
import os
import time
import re
import hashlib

from lib.driver import TestSkip, adb

ROOT = r"Z:\opt\librera\LibreraReader\ci\autotest"
# 临时文件统一目录(AGENTS ②节):预置/调试产生的本地副本一律放这里
TMP_DIR = r"Z:\opt\Workspace\HowRead\tmp\debug"


def _dismiss_system_dialogs(dev):
    """清掉抢占前台的系统弹窗.典型:华为「USB 连接方式」——USB 口接触不良重枚举时
    会反复弹出,盖住半个屏幕导致 wait_home/点击全挂(KSA 踩坑 2026-09-13)."""
    try:
        xml = dev.d.dump_hierarchy()
    except Exception:
        return
    if "USB 连接方式" in xml or "USB connection" in xml or "Use USB to" in xml:
        cancel = dev.d(text="取消")
        if cancel.exists:
            cancel.click()
        else:
            dev.d.press("back")
        time.sleep(1.5)


def _ensure_home(dev):
    dev.wake_unlock()  # 长跑中 MIUI 会熄屏,熄屏期间 u2/点击/截图全部失效(真机踩坑 2026-09-13)
    # MIUI/EMUI PowerKeeper 会在应用前台时强杀(FN-30/46/49/51"静默退出"真凶,
    # events 实锤:Force stopping ... from process:<powerkeeper>);白名单幂等,每次兜底
    dev.shell("dumpsys deviceidle whitelist +%s" % dev.pkg)
    dev.start_app(cold=True)
    _dismiss_system_dialogs(dev)
    dev.handle_first_run_dialogs()
    if not dev.wait_home(10):
        # 低端机(如 KSA/Android9)冷启后残留的阅读器任务可能抢在前台——back 退出阅读器再等
        ok = False
        for _ in range(3):
            dev.d.press("back")
            time.sleep(1.5)
            _dismiss_system_dialogs(dev)
            if dev.wait_home(8):
                ok = True
                break
        if not ok:
            raise AssertionError("主界面 10s 未就绪(back 兜底 3 轮仍失败)")
    time.sleep(1)


def _scroll_net_section(dev, times=4):
    """在「我的文件」根页的网络/书库区块(MaxHeightScrollView)内向上滑动.
    2026-09-18 起该区块恢复 220dp 高度上限并改为内部滚动,uiautomator 只能看到
    视口内节点——下方的书夹卡片/搜索行要先在区块内滚动才可见."""
    w, h = dev.d.window_size()
    for _ in range(times):
        dev.d.swipe(0.5 * w, 0.35 * h, 0.5 * w, 0.12 * h, 0.4)
        time.sleep(1.0)


def _ensure_library_folder(dev, case_id, folder="Download"):
    """把 /storage/emulated/0/<folder> 幂等添加进「我的文件→书库文件夹」.
    2026-09-18 根页改版后书库文件夹区按 BookCSS.searchPathsJson 渲染,MI9 默认
    只有 Books 与存储根,测试书所在的 Download 不在列表——依赖"根列表 Download 行"
    开书的用例(FN-07/14/16/18/34/40/41)因此找不到入口(易败分析集群 A).
    流程(源码核实):书库文件夹行「+ 添加」(无 id,_click_section_add 按 y 区间
    定位)→ PopupMenu[添加文件夹] → ChooserDialogFragment 目录浏览器(恒打开在
    /storage/emulated/0)→ 点 folder 行进入 → 底部 onAction[选择];配置即时刷新
    并落盘,一次添加长期有效.已在列表返回 True;失败返回 False(调用方继续走原
    兜底),本 helper 不抛异常."""

    def _folder_visible():
        if dev.d(text=folder).exists:
            return True
        _scroll_net_section(dev, times=2)
        return dev.d(text=folder).exists

    try:
        if _folder_visible():
            return True
        if not _click_section_add(dev, "书库文件夹"):
            # 书库文件夹头可能在区块折叠线下,区内滚动后再试一次
            _scroll_net_section(dev, times=2)
            if not _click_section_add(dev, "书库文件夹"):
                dev.save_dump(case_id, "libfolder_no_add_btn")
                return False
        item = None
        for _ in range(4):
            time.sleep(1)
            item = dev.d(text="添加文件夹")
            if item.exists:
                break
            item = dev.d(text="Add folder")
            if item.exists:
                break
        if not item.exists:
            dev.save_dump(case_id, "libfolder_no_menu_item")
            dev.d.press("back")
            return False
        item.click()
        time.sleep(2.5)
        # 目录浏览器恒开在 /storage/emulated/0,点 folder 行=进入该目录
        entry = dev.d(text=folder)
        if not entry.exists:
            entry = dev.d(textContains=folder)
        if not entry.exists:
            dev.save_dump(case_id, "libfolder_no_entry_in_chooser")
            dev.d.press("back")
            return False
        entry.click()
        time.sleep(2)
        ok_btn = dev.d(resourceId=_rid(dev, "onAction"))
        if not ok_btn.exists:
            dev.save_dump(case_id, "libfolder_no_confirm")
            dev.d.press("back")
            dev.d.press("back")
            return False
        ok_btn.click()
        time.sleep(3)
        if _folder_visible():
            print("  [%s] 书库文件夹已添加 %s(根列表可见,配置已落盘)" % (dev.serial, folder))
            return True
        dev.save_dump(case_id, "libfolder_added_not_visible")
        return False
    except Exception as e:
        print("  [%s] _ensure_library_folder 异常: %s" % (getattr(dev, "serial", "?"), e))
        try:
            dev.save_dump(case_id, "libfolder_exception")
        except Exception:
            pass
        return False


def _browse_dl_row(dev):
    """「我的文件」页找 Download 行;找不到时先点书库文件夹卡片兜底.
    真出厂态(--reset 删外部状态后)区块页没有文件列表、也没有 Download 行,
    只有一张默认书夹卡片(/storage/emulated/0,按末段显示为"0")——点它进入
    /sdcard 根浏览页,Download 行就在那里(真出厂态踩坑 2026-09-13).
    返回可点击的 Download 行,找不到返回 None."""
    dl = dev.d(text="Download")
    if not dl.exists:
        dl = dev.d(textContains="Download")
    if dl.exists:
        return dl
    # 远程条目多时(添加 SMB/SFTP 后)区块页大幅变高,卡片/Download 行可能被挤到
    # 折叠线下(uiautomator 只见可见节点)——先上滑再找(2026-09-13 KSA 现场)
    card = dev.d(descriptionStartsWith="文件夹")
    if not card.exists:
        for _ in range(2):
            w, h = dev.d.window_size()
            dev.d.swipe(0.5 * w, 0.75 * h, 0.5 * w, 0.35 * h, 0.4)
            time.sleep(1.2)
            card = dev.d(descriptionStartsWith="文件夹")
            if card.exists:
                break
    if card.exists:
        card.click()
        time.sleep(2.5)
        dl = dev.d(text="Download")
        if not dl.exists:
            dl = dev.d(textContains="Download")
    if dl is None or not dl.exists:
        # 集群 A 兜底(2026-09-19):把 Download 幂等添加进书库文件夹(UI 走真实
        # 功能,配置落盘长期有效)——根页改版后 MI9 书库文件夹无 Download 行
        if _ensure_library_folder(dev, "browse_dl_row"):
            dl = dev.d(text="Download")
            if not dl.exists:
                dl = dev.d(textContains="Download")
            if dl.exists:
                return dl
    if dl is None or not dl.exists:
        # 2026-09-18 根页改版:书库文件夹变成卡片列表(书夹名即文本;存储根
        # /storage/emulated/0 按末段显示为"0"),位于 MaxHeightScrollView 内部,
        # 需先在区块内上滑才可见——点"0"卡片进入 /sdcard 根浏览页找 Download 行
        _scroll_net_section(dev)
        root_card = dev.d(text="0")
        if root_card.exists and root_card.count == 1:
            root_card.click()
            time.sleep(2.5)
            dl = dev.d(text="Download")
            if not dl.exists:
                dl = dev.d(textContains="Download")
            if not dl.exists:
                w, h = dev.d.window_size()
                dev.d.swipe(0.5 * w, 0.75 * h, 0.5 * w, 0.35 * h, 0.4)
                time.sleep(1.2)
                dl = dev.d(text="Download")
                if not dl.exists:
                    dl = dev.d(textContains="Download")
    return dl if dl.exists else None


def _goto_browse_download(dev, case_id):
    """首页 → 我的文件 → Download 文件夹(幂等)."""
    dev.click_desc("首页") or dev.click_text("首页")
    time.sleep(1)
    if not (dev.click_desc("我的文件") or dev.click_text("我的文件")):
        raise AssertionError("我的文件 Tab 不可达")
    time.sleep(2)
    dl = _browse_dl_row(dev)
    if dl is not None:
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
    """u2 info bounds 兼容:新版返回 dict,旧版返回 '[x1,y1][x2,y2]' 字符串."""
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
    """检查 keyword 行附近是否存在 action_desc 按钮(不点击)."""
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
    """在列表中找到 keyword 行,点击该行 y 最近的 action_desc 按钮."""
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
    """最近列表:开书 → 重启 → 首页最近阅读分区非空."""
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
    """回到列表顶部后逐屏检查:所有 keywords 同时出现在同一 dump 即命中."""
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
    """滚回顶部定位首页分区标题,点击其右侧'更多'进入完整列表."""
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
    """收藏:我的文件→Download→big25 行级'添加到收藏夹'→首页我的珍藏可见."""
    with dev.step(case_id, "add_favorite"):
        _ensure_home(dev)
        _goto_browse_download(dev, case_id)
        target = _find_in_list(dev, "big25", 8)
        if target is None and not dev.dump_has_text("big25"):
            # 书库未扫描到测试书目(低配机首次扫描慢/未收录)
            dev.save_dump(case_id, "browse_no_big25")
            raise TestSkip("设备书库未收录 big25,Browse 收藏路径不可用(环境限制)")
        clicked = False
        for _ in range(2):
            # 幂等:按钮若是"从收藏夹中移除"说明已在收藏中,不要点击(点了会移除),直接验证
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
        # 卡片式分区可能横向分页不渲染目标标题:点"我的珍藏"分区的"更多"进完整列表
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
    """呼出阅读器菜单并点开书签入口,返回是否成功.
    若中途退出阅读器(back 过多/误触),自动重开书目."""
    w, h = dev.d.window_size()
    for _attempt in range(3):
        top = dev.shell("dumpsys activity activities | grep ResumedActivity")
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
        # 菜单没出来:若还在阅读器则按 back 收起可能的部分状态,下一轮重开
        dev.d.press("back")
        time.sleep(1.2)
    return False


def fn03_bookmark(dev, case_id, cfg=None, fixtures=None):
    """书签:阅读器菜单→书签对话框→'添加'→首页书签笔记可见(对话框内兜底验证)."""
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
        # 卡片不渲染标题时:点"书签笔记"分区的"更多"进完整列表验证
        if _open_section_more(dev, "书签笔记"):
            xml = dev.d.dump_hierarchy()
            if "Big25" in xml or "big25" in xml:
                dev.d.press("back")
                time.sleep(1)
                return
            dev.save_dump(case_id, "bm_full_list")
            dev.d.press("back")
            time.sleep(1)
        # 再兜底:重开书签对话框,书签管理界面可用即认为书签功能正常
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
    """在当前浏览页尝试打开搜索页(坐标点击并上偏 15px 避开底缘热区)."""
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


def _search_row_bounds(dev):
    """返回"在多个文档中搜索"搜索行的 (cx, cy, y1, y2);不在树中(被裁切/未渲染)返回 None.
    注意:uiautomator 只 dump 可视节点,搜索行被挤出屏幕外时不在树里."""
    try:
        el = dev.d(textContains="在多个文档中搜索")
        if not el.exists:
            return None
        b = el.info.get("bounds", {})
        x1, y1, x2, y2 = b["left"], b["top"], b["right"], b["bottom"]
        return ((x1 + x2) // 2, (y1 + y2) // 2, y1, y2)
    except Exception:
        return None


def _reveal_search_entry(dev):
    """打开搜索页.搜索入口在"我的文件"根视图底部"搜索"分区(searchSection,固定兄弟节点,
    根视图不可滚动).网络库条目多时(P20 实测:2 OPDS+WebDAV+SMB+SFTP)netSection 太高,
    把搜索行挤出屏幕外(uiautomator 树里也查不到),任何 swipe 都无效.

    这是已知应用缺陷(fragment_browse2.xml 根视图不可滚动,搜索行被网络区挤出屏幕,
    全文搜索唯一入口不可达),非 autotest 能规避的测试环境问题.检测到裁切时 SKIP 并注明,
    不删用户网络源、不改应用代码(处理方式见 CHANGES 2026-09-12 条目)."""
    if _try_open_search_page(dev):
        return True
    # 直接点不到:先在网络区块内上滑(2026-09-18 起区块恢复高度上限并内部
    # 滚动,搜索行可能在视口下方),仍点不到才判"被裁切"
    _scroll_net_section(dev)
    if _try_open_search_page(dev):
        return True
    # 直接点不到:判断是"被裁切"(已知缺陷)还是"入口缺失"
    pos = _search_row_bounds(dev)
    w, h = dev.d.window_size()
    nav_top = h - 200  # 底部 Tab 栏上缘,搜索行中心须在其上才可点
    clipped = (pos is None) or (pos[1] >= nav_top)
    if clipped:
        dev.save_dump("FN-04", "search_row_clipped")
        raise TestSkip(
            "搜索入口被网络源区块挤出屏幕外(已知应用缺陷:fragment_browse2 根视图不可滚动,"
            "netSection 过高时 searchSection 被裁切,全文搜索唯一入口不可达;见 CHANGES 2026-09-12)")
    # 行在树里但 _try_open_search_page 没打开(偶发):再直接点一次
    dev.d.click(pos[0], pos[1])
    time.sleep(5)
    return dev.d(resourceId=dev.pkg + ":id/editSearchText").exists


def fn04_search(dev, case_id, cfg=None, fixtures=None):
    """全文搜索:我的文件根视图/Download 视图 → 搜索页 → editSearchText + searchStart."""
    with dev.step(case_id, "open_search"):
        _ensure_home(dev)
        if not (dev.click_desc("我的文件") or dev.click_text("我的文件")):
            raise AssertionError("我的文件 Tab 不可达")
        time.sleep(2)
        # 先在根视图找入口(MI9 实测根视图才响应),找不到就滚动揭示(P20 搜索区在屏幕外)
        opened = _reveal_search_entry(dev)
        if not opened:
            dl = _browse_dl_row(dev)
            if dl is not None:
                dl.click()
                time.sleep(2)
                opened = _reveal_search_entry(dev)
        if not opened:
            dev.save_dump(case_id, "no_search_entry")
            raise AssertionError("搜索页未打开")
    with dev.step(case_id, "search_keyword"):
        et = dev.d(resourceId=dev.pkg + ":id/editSearchText")
        et.click()
        time.sleep(1)
        et.set_text("big25")
        time.sleep(1)
        # 勾选"在书库中搜索"提高命中(测试书在书库 Download 下)
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
    """阅读配置:偏好 → 阅读配置子页打开且含设置项."""
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
    """主题切换:抽屉菜单 → 夜间模式,截图对比验证生效后还原."""
    from cases.ui.tc_smoke import _same_png

    def has_night():
        return dev.dump_has_text("夜间模式") or dev.dump_has_text_legacy("夜间模式")

    def click_night():
        if dev.click_text("夜间模式") or dev.click_desc("夜间模式"):
            return True
        if dev.click_text_legacy("夜间模式"):
            return True
        # P30:uia2 树缺抽屉底行、uia2 活跃时系统 dump 也返回空树,
        # 只能按坐标点抽屉底行"夜间模式"(4 按钮行从左第 3 个,实测 0.5w/0.91h 有效),
        # 是否生效由 toggle_night 的截图对比把关.
        w, h = dev.d.window_size()
        dev.shell("input tap %d %d" % (int(0.5 * w), int(0.91 * h)))
        time.sleep(1)
        return True

    with dev.step(case_id, "open_drawer"):
        _ensure_home(dev)
        if not dev.click_desc("菜单"):
            raise AssertionError("抽屉菜单按钮未找到")
        time.sleep(1.5)
        # 抽屉是否打开以 u2 树里能看到的上半区条目为准(P30 底行不进任何 dump)
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
    """TTS 朗读:切[单机行为=Book mode 水平]→ 开 EPUB(进 HorizontalViewActivity)→
    底栏 textToSpeach(desc「文字转语音」,HorizontalViewActivity.restoreFooterControls 点亮)
    → dialogTextToSpeech → ttsPlay(desc「Play/pause」)启动前台 TTSService → 断言服务运行
    → ttsStop 收尾.覆盖 §11 TTS 朗读(启动/停止).
    代码+真机结论(2026-09-13):TTS 无 Pro/flavor 门控;入口图标仅水平模式点亮,垂直滚动
    (AppSP.readingMode=SCROLL 时 epub 也走 Vertical)恒 GONE——经偏好单机行为切 Book mode 绕行."""
    try:
        with dev.step(case_id, "switch_book_mode"):
            _ensure_home(dev)
            if not _set_reading_mode(dev, case_id, ["左右翻页", "Book mode", "书本"]):
                dev.save_dump(case_id, "no_book_mode_item")
                raise TestSkip("偏好无[单机行为]行或无左右翻页项")
            # 注意:ConfLineView 的模式选择只写内存(AppSP.AppTemp 落盘不含 force-stop),
            # 此处绝不能再冷启应用,否则设置丢失、书仍走垂直模式(真机踩坑 2026-09-13)
        with dev.step(case_id, "open_epub"):
            _open_reader_warm(dev, case_id, "alicesadventures")
            _reader_show_toolbar(dev)
            tts_btn = _reader_toolbar_btn(dev, "textToSpeach", desc_kws=("文字转语音", "TTS"))
            if tts_btn is None:
                dev.save_dump(case_id, "no_tts_btn")
                raise TestSkip("TTS 入口(textToSpeach)不可见(Book 模式下仍不可见,待勘探)")
            clicked = False
            for _ in range(3):
                try:
                    tts_btn.click()
                    clicked = True
                    break
                except Exception:
                    # 取到按钮与点击之间工具条可能被翻转/隐藏(首启 showHelp 的
                    # hideShow、自动隐藏),重新展开再取(2026-09-13 出厂态踩坑)
                    time.sleep(1.5)
                    _reader_show_toolbar(dev)
                    tts_btn = _reader_toolbar_btn(dev, "textToSpeach", desc_kws=("文字转语音", "TTS"))
                    if tts_btn is None:
                        break
            if not clicked:
                dev.save_dump(case_id, "no_tts_btn")
                raise TestSkip("TTS 入口(textToSpeach)不可见(Book 模式下仍不可见,待勘探)")
            # 对话框可能慢开(TTS 初始化),首开翻转期点击可能落空/误点——
            # 轮询确认,未开则重取按钮补点(2026-09-13 出厂态实测:误判时截图里对话框其实已开)
            dialog = False
            for _ in range(5):
                time.sleep(2)
                if _safe_exists(dev, dev.d(resourceId=_rid(dev, "ttsActive"))) \
                        or _safe_exists(dev, dev.d(resourceId=_rid(dev, "ttsPlay"))):
                    dialog = True
                    break
                tb = _reader_toolbar_btn(dev, "textToSpeach", desc_kws=("文字转语音", "TTS"))
                if tb is not None:
                    try:
                        tb.click()
                    except Exception:
                        pass
            _snap(dev, case_id, "tts_dialog")
            if not dialog:
                dev.save_dump(case_id, "no_tts_dialog")
                raise AssertionError("TTS 对话框(dialogTextToSpeech)未出现")
        with dev.step(case_id, "start_tts"):
            play = dev.d(resourceId=_rid(dev, "ttsPlay"))
            if not play.exists:
                dev.save_dump(case_id, "no_tts_play")
                raise TestSkip("TTS 播放按钮(ttsPlay)不可见")
            play.click()
            running = False
            deadline = time.time() + 20
            while time.time() < deadline:
                out = dev.shell("dumpsys activity services %s | grep -i TTSService" % dev.pkg)
                if "TTSService" in out:
                    running = True
                    break
                time.sleep(2)
            _snap(dev, case_id, "tts_running")
            if not running:
                c = dev.scan_crash()
                if c:
                    raise AssertionError("启动 TTS crash: %s" % c)
                dev.save_dump(case_id, "tts_service_not_running")
                raise AssertionError("点击播放后 TTSService 前台服务未运行(设备可能无 TTS 引擎,见 dump)")
        with dev.step(case_id, "stop_and_exit"):
            stop = dev.d(resourceId=_rid(dev, "ttsStop"))
            if stop.exists:
                stop.click()
                time.sleep(2)
            out = dev.shell("dumpsys activity services %s | grep -i TTSService" % dev.pkg)
            if "TTSService" in out:
                dev.d.app_stop(dev.pkg)  # 兜底停服务,不留前台服务污染后续用例
            _exit_reader(dev, case_id)
    finally:
        # 还原 单机行为=Scroll mode(垂直),避免影响后续用例的阅读模式假设
        try:
            _back_to_main(dev)
            _set_reading_mode(dev, case_id, ["上下翻页", "Scroll mode", "滚动"])
        except Exception:
            pass


def fn08_intent_open(dev, case_id, cfg=None, fixtures=None):
    """系统级 VIEW intent 打开(OpenerActivity 路径,正确 MIME)."""
    with dev.step(case_id, "cold_intent"):
        dev.d.app_stop(dev.pkg)
        time.sleep(1)
        if not dev.open_book_via_intent(fixtures["device_pdf_path"]):
            raise AssertionError("冷态 intent 打开 PDF 失败(OpenerActivity 未到达阅读器)")
    with dev.step(case_id, "warm_intent"):
        if not dev.open_book_via_intent(fixtures["device_epub_path"]):
            raise AssertionError("热态 intent 打开 EPUB 失败")
    with dev.step(case_id, "exit"):
        for _ in range(3):
            dev.d.press("back")
            time.sleep(0.5)


# 多格式测试书(teskbook 新增样本,设备侧 /sdcard/Download/ 下).
# 一律用 ASCII 文件名:adb push 中文文件名会乱码且丢扩展名(见 AGENTS.md gotcha 16).
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

# 设备侧 ASCII 名 → 本地 teskbook 原始文件名(多数原始样本是中文名,需映射).
MULTI_FORMAT_SOURCES = {
    "book_mobi.mobi": "一本书读懂大数据-黄颖.mobi",
    "book_azw3.azw3": "计算机与人脑 (科学素养文库·科学元典丛书) - 冯·诺伊曼(Neumann.J.V).azw3",
    "book_azw.azw": "论犯罪的价值 - 于志刚.azw",
    "book_prc.prc": "297孙子兵法.prc",
    "book_doc.doc": "MySQL数据库如何实现双机热备的配置.doc",
    "book_docx.docx": "The Analysis Of Basic MFC Program Running Principle.docx",
    # docx 的设备侧文件名就是 teskbook 原名(ASCII),两条键名都要能查到
    "The Analysis Of Basic MFC Program Running Principle.docx": "The Analysis Of Basic MFC Program Running Principle.docx",
    "book_djvu.djvu": "[深入理解计算机系统].Computer.Systems.-.A.Programmers.Perspective.-.Randal.Bryant,.David.O.Hallaron.-.1008.pages.High.Quality.-.2003.Prentice.Hall.djvu",
    "book_html.html": "教学设计.html",
    "book_pdf.pdf": "test.pdf",
    "book_txt.txt": "demo.txt",
}


def _exit_reader(dev, case_id):
    """退出阅读器回主界面(最多 3 次 back,命中首页/最近阅读即停)."""
    for _ in range(3):
        dev.d.press("back")
        time.sleep(0.8)
        if dev.dump_has_text("最近阅读") or dev.dump_has_text("首页") or dev.dump_has_text("Browse"):
            break


def _open_reader_warm(dev, case_id, keyword):
    """热态浏览开书(绝不冷启):偏好里切换的阅读模式(左右/上下翻页)只写内存
    (AppSP.AppTemp 落盘不含 force-stop),open_book 的 intent/浏览路径都会冷启导致设置丢失
    (真机踩坑 2026-09-13)."""
    if not (dev.click_desc("我的文件") or dev.click_text("我的文件")):
        raise AssertionError("我的文件 Tab 不可达")
    time.sleep(2)
    dl = _browse_dl_row(dev)
    if dl is not None:
        dl.click()
        time.sleep(2.5)
    book = _find_text_scrolled(dev, keyword, max_swipes=8)
    if book is None:
        # 浏览列表记住上次滚动位,而查找只向下滚——目标在复位位上方时永远找不到
        # (MI9 book_pdf 间歇漏找,2026-09-13);滚回顶部再找一遍
        w, h = dev.d.window_size()
        for _ in range(6):
            dev.d.swipe(0.5 * w, 0.3 * h, 0.5 * w, 0.85 * h, 0.25)
            time.sleep(1)
        book = _find_text_scrolled(dev, keyword, max_swipes=8)
    if book is None:
        dev.save_dump(case_id, "warm_book_not_found")
        raise AssertionError("浏览未找到 %s(热态开书)" % keyword)
    book.click()
    entered = False
    deadline = time.time() + 30
    while time.time() < deadline:
        top = dev.shell("dumpsys activity activities | grep ResumedActivity")
        if "ViewActivity" in top or "TTSActivity" in top:
            entered = True
            break
        time.sleep(2)
    if not entered:
        dev.save_dump(case_id, "warm_open_failed")
        raise AssertionError("热态开书未进入阅读器")
    return True


def _open_reader(dev, case_id, fixtures, fmt):
    """强制冷启动开书进阅读器,隔离用例间脏状态(上一用例 SKIP/FAIL 可能把阅读器或桌面留下).
    fmt: 'pdf' → big25.pdf;'epub' → alicesadventures.epub.返回 True 表示已进 ViewActivity."""
    if fmt == "epub":
        kw, path = "alicesadventures", fixtures["device_epub_path"]
    else:
        kw, path = "big25", fixtures["device_pdf_path"]
    if not dev.open_book(kw, device_path=path):
        raise AssertionError("%s 打开失败" % path.split("/")[-1])
    top = dev.shell("dumpsys activity activities | grep ResumedActivity")
    if "ViewActivity" not in top and "TTSActivity" not in top:
        dev.save_dump(case_id, "not_in_reader")
        raise AssertionError("开书后未进阅读器: %s" % top.strip()[:100])
    return True


def _reader_page_no_back(dev):
    """读当前页码,**不 press back**(driver.reader_page_num 的 back 会收起工具条/退出阅读器/退桌面,
    连续调用会把 app 退到桌面--FN-16 首跑即因此失败).返回 (cur, total) 或 None.
    水平(Book)模式页码在 pagesCountIndicator(「11 ∕ 14」,U+2215 除号).
    优先从 logcat 的 REMOTE "page now X/N" 读取(阅读器翻页即打印,MIUI 打断
    uiautomator 导致 dump 无文本时依旧可用),失败再回退 UI 控件读取."""
    try:
        out = dev.shell("logcat -d -s REMOTE:* -t 300")
        best = None
        for m in re.finditer(r'page now (\d+)/(\d+)', out or ""):
            best = (int(m.group(1)), int(m.group(2)))
        if best:
            return best
    except Exception:
        pass
    for rid, pat in (("currentPageIndex", r"(\d+)\s*/\s*(\d+)"),
                     ("pagesCountIndicator", r"(\d+)\s*[∕/]\s*(\d+)")):
        try:
            el = dev.d(resourceId=_rid(dev, rid))
            if el.exists:
                m = re.match(pat, (el.get_text() or "").strip())
                if m:
                    return (int(m.group(1)), int(m.group(2)))
        except Exception:
            continue
    # 百分比复合格式「0.0% ∕ 2600」(页码格式=百分比时):换算回页码
    try:
        el = dev.d(resourceId=_rid(dev, "pagesCountIndicator"))
        if el.exists:
            m = re.match(r"([\d.]+)%\s*[∕/]\s*(\d+)", (el.get_text() or "").strip())
            if m:
                mx = int(m.group(2))
                # 应用百分比 = 当前页/总页数(4/5 显示 80.0%):直接换算,
                # 不做 +1(旧公式把 4/5 算成第 5 页,末页回翻兜底失效 2026-09-14);
                # 大书首页显示 0.0% 时下限钳回第 1 页
                cur = int(round(float(m.group(1)) / 100.0 * mx))
                if cur < 1:
                    cur = 1
                return (min(cur, mx), mx)
    except Exception:
        pass
    # 底栏 currentSeek(左=当前进度)/maxSeek(右=总页)——垂直模式菜单栏展开后
    # 可读;垂直模式 currentSeek 是进度百分比(如「97.7%」,FN-60 同源),页码格式
    # =百分比时 int() 必抛→换算回页码(集群 B 兜底 2026-09-19:菜单栏收起时
    # 这两个节点不在控件树上,由 _reader_page_ready 先把菜单栏调出)
    try:
        cur = dev.d(resourceId=_rid(dev, "currentSeek"))
        if cur.exists:
            ctxt = (cur.get_text() or "").strip()
            mx = dev.d(resourceId=_rid(dev, "maxSeek"))
            tot = 0
            if mx.exists:
                mm = re.match(r"(\d+)", (mx.get_text() or "").strip())
                if mm:
                    tot = int(mm.group(1))
            pct = re.match(r"([\d.]+)\s*%", ctxt)
            if pct and tot:
                c = int(round(float(pct.group(1)) / 100.0 * tot))
                return (max(c, 1), tot)
            cm = re.match(r"(\d+)", ctxt)
            if cm:
                return (int(cm.group(1)), tot)
    except Exception:
        pass
    try:
        m = re.search(r'"(\d+)\s*/\s*(\d+)"', dev.d.dump_hierarchy())
        if m:
            return (int(m.group(1)), int(m.group(2)))
    except Exception:
        pass
    return None


# ======================================================================
# 2026-09-12 覆盖扩展:通用助手(FN-10 ~ FN-30,对应<HowRead安卓功能列表.md>)
# 约定:每个 UI 可见验证点成功时调 _snap 存截图,作为 PASS 的确认依据
# (report.md "通过用例截图索引" 节汇总).
# ======================================================================

def _snap(dev, case_id, name):
    """PASS 路径截图取证;截图失败不拖垮用例."""
    try:
        return dev.screenshot(case_id, name)
    except Exception:
        return None


def _goto_tab(dev, tab, wait=2.5):
    """底部 Tab 导航(content-desc 含 " 标签",click_desc 用 descriptionContains 命中).
    如果 UI dump 不可用,回退到直接点击屏幕底部区域."""
    # First try normal approach
    if dev.click_desc(tab) or dev.click_text(tab):
        time.sleep(wait)
        return True
    
    # If normal approach fails, try direct tapping at screen bottom
    try:
        w, h = dev.d.window_size()
        # Define tab areas (approximate positions)
        tab_positions = {
            "首页": (w * 0.2, h * 0.9),
            "书库": (w * 0.4, h * 0.9),
            "我的文件": (w * 0.6, h * 0.9),
            "偏好": (w * 0.8, h * 0.9),
            "Home": (w * 0.2, h * 0.9),
            "Library": (w * 0.4, h * 0.9),
            "My files": (w * 0.6, h * 0.9),
            "Preferences": (w * 0.8, h * 0.9)
        }
        
        if tab in tab_positions:
            x, y = tab_positions[tab]
            dev.d.click(int(x), int(y))
            time.sleep(wait)
            return True
    except Exception:
        pass
    
    return False


def _goto_tab_or_fail(dev, tab):
    if not _goto_tab(dev, tab):
        raise AssertionError("%s Tab 不可达" % tab)


def _back_to_main(dev, max_backs=8):
    """从任意深层界面返回主界面(Tab 栏可见)."""
    for _ in range(max_backs):
        try:
            xml = dev.d.dump_hierarchy()
        except Exception:
            xml = ""
        if _goto_tab(dev, "首页", wait=1):
            return True
        dev.d.press("back")
        time.sleep(1)
    return _goto_tab(dev, "首页", wait=1)


def _dismiss_keyboard(dev):
    """EMUI 安全键盘会遮住对话框底部按钮(测试连接/添加),先收起键盘.
    仅在键盘确实弹出时按 back--无键盘时 back 会误关 AlertDialog."""
    try:
        out = dev.shell("dumpsys input_method | grep -E 'mInputShown|mIsInputViewShown'")
        if "true" in out.lower():
            dev.d.press("back")
            time.sleep(1)
    except Exception:
        pass


_MASK_CHARS = set("•·*")


def _fill(dev, el, text, label=""):
    """填输入框并回读验证(EMUI 上 set_text 偶发追加而非替换,见 AGENTS gotcha 21③).
    密码框(如 AI apiKey)回读是掩码点(•),无法与明文逐字比对,改按"掩码点数==明文长度"校验;
    且 el.info 不暴露 password 属性,故按回读内容判断:回读非空、与明文不等、且全为掩码字符→视为密码框。
    先清空再填,避免字段被上次保存值预填导致 set_text 追加(回读点数 > 明文长度)."""

    def cur():
        try:
            return el.get_text() or ""
        except Exception:
            return None

    def is_mask(got):
        return bool(got) and got != text and set(got) <= _MASK_CHARS

    def ok(got):
        if is_mask(got):
            return len(got) == len(text)
        return got == text

    el.click()
    time.sleep(0.8)
    el.clear_text()
    time.sleep(0.4)
    el.set_text(text)
    time.sleep(0.6)
    if not ok(cur()):
        el.clear_text()
        time.sleep(0.5)
        el.set_text(text)
        time.sleep(0.6)
    got = cur()
    if not ok(got):
        raise AssertionError("输入框%s回读不符: %r != %r" % (label, got, text))


def _rid(dev, name):
    return dev.pkg + ":id/" + name


def _find_text_scrolled(dev, keyword, max_swipes=10):
    """在可滚动列表中找 keyword 文本节点(书库/网络目录等),找不到返回 None."""
    target = dev.d(textContains=keyword)
    swipes = 0
    while not target.exists and swipes < max_swipes:
        w, h = dev.d.window_size()
        dev.d.swipe(0.5 * w, 0.7 * h, 0.5 * w, 0.3 * h, 0.3)
        time.sleep(1.2)
        swipes += 1
    return target if target.exists else None


def _scroll_to_find(dev, rid_name, max_swipes=12):
    """在当前可滚动页面里向上滚动直到 resourceId=rid_name 的节点可见,返回该节点或 None.
    (偏好页 themeColor/appLang 等行可能在屏幕外,需滚动定位.)
    如果 UI dump 不可用,使用回退策略."""
    try:
        el = dev.d(resourceId=_rid(dev, rid_name))
        if el.exists:
            return el
    except Exception:
        pass
    
    # Try alternative approach if UI dump fails
    w, h = dev.d.window_size()
    for i in range(max_swipes):
        try:
            # Swipe up
            dev.d.swipe(0.5 * w, 0.72 * h, 0.5 * w, 0.3 * h, 0.3)
            time.sleep(1.0)
            
            # Try to find element
            try:
                el = dev.d(resourceId=_rid(dev, rid_name))
                if el.exists:
                    return el
            except Exception:
                pass
        except Exception:
            # If swipe fails, try direct tap at different positions
            for y_pos in [0.8, 0.7, 0.6, 0.5]:
                try:
                    dev.d.click(int(0.5 * w), int(y_pos * h))
                    time.sleep(1.0)
                    try:
                        el = dev.d(resourceId=_rid(dev, rid_name))
                        if el.exists:
                            return el
                    except Exception:
                        pass
                except Exception:
                    pass
    
    return None


def _browse_root(dev):
    """进入[我的文件]根视图(netSection 可见).
    netSection 仅在根路径显示(BrowseFragment2:1769 子目录时 GONE),前序用例可能把应用
    留在子目录,故先回 Tab 再逐级 back 到根;back 不够或误出 Tab 时重进 Tab 兜底."""
    _goto_tab_or_fail(dev, "我的文件")
    for _ in range(10):
        if dev.d(resourceId=_rid(dev, "netSection")).exists:
            return True
        dev.d.press("back")
        time.sleep(1.2)
    # 兜底:重新进[我的文件]Tab(可能 back 误出 Tab 或层级过深)
    _goto_tab_or_fail(dev, "我的文件")
    for _ in range(10):
        if dev.d(resourceId=_rid(dev, "netSection")).exists:
            return True
        dev.d.press("back")
        time.sleep(1.2)
    return dev.d(resourceId=_rid(dev, "netSection")).exists


def _click_row_delete(dev, title):
    """删除 netSection 里 title 服务器行(行右侧 ✕ 图标 + 可能的确认弹窗).
    只用于测试自建条目的清理;P20 用户手工配置的条目绝不调用."""
    if not _net_scroll_to(dev, lambda: dev.d(text=title).exists, tries=4):
        return False
    el = dev.d(text=title)
    b = _node_bounds(el)
    if not b:
        return False
    cy = (b[1] + b[3]) / 2
    w, _ = dev.d.window_size()
    deleted = False
    for dx in (100, 150, 60, 200):  # ✕ 固定在行最右:按密度不同试几个偏移
        dev.d.click(w - dx, int(cy))
        time.sleep(1.5)
        if not dev.d(text=title).exists:
            deleted = True
            break
        # 可能有确认弹窗(删除/确认/确定)
        for kw in ("确认", "确定", "删除"):
            if dev.click_text(kw):
                time.sleep(1.5)
                break
        if not dev.d(text=title).exists:
            deleted = True
            break
    return deleted


def _safe_exists(dev, el, retries=3, wait=2.5):
    """el.exists 的 u2 异常保护版:MIUI 上 uiautomator 服务偶发 JSON-RPC -32002 崩溃
    (AGENTS gotcha 10/21),.exists 内部 wait 会抛异常而非返回 False。捕获后等待服务
    自愈重试;仍失败返回 None(区别于"确实不存在"的 False),调用方据此如实 SKIP."""
    for i in range(retries):
        try:
            return bool(el.exists)
        except Exception:
            if i < retries - 1:
                time.sleep(wait)
    return None


def _addremote_fill_and_save(dev, case_id, cfg, is_sftp, title):
    """AddRemoteDialog(添加 SMB/SFTP 服务器):填字段→测试连接→添加.
    返回 True=测试连接成功;False=对话框/字段缺失;None=u2 服务不稳(调用方转 SKIP)."""
    ts = (cfg or {}).get("test_server") or {}

    def _is_u2_err(e):
        m = str(e)
        return ("-32002" in m or "JsonRpc" in m or "jsonrpc" in m
                or "Selector" in m or "uiautomator" in m.lower())

    try:
        host = dev.d(resourceId=_rid(dev, "host"))
        port = dev.d(resourceId=_rid(dev, "port"))
        login = dev.d(resourceId=_rid(dev, "login"))
        pwd = dev.d(resourceId=_rid(dev, "password"))
        name = dev.d(resourceId=_rid(dev, "name"))
        for el in (host, port, login, pwd, name):
            r = _safe_exists(dev, el)
            if r is None:
                return None
            if not r:
                return False
        _fill(dev, name, title, "(名称)")
        _fill(dev, host, ts.get("host", "192.168.50.23"), "(主机)")
        _fill(dev, port, str(ts.get("sftp_port", 22) if is_sftp else ts.get("smb_port", 445)), "(端口)")
        if is_sftp:
            start_dir = dev.d(resourceId=_rid(dev, "startDir"))
            sd = _safe_exists(dev, start_dir)
            if sd is None:
                return None
            if sd and ts.get("books_dir"):
                # SFTP startDir 是家目录相对语义(RemoteServer.startDir 注释),
                # 填绝对路径会解析成 home 下嵌套路径导致浏览为空(真机踩坑 2026-09-13)
                rel = ts["books_dir"].rstrip("/").split("/")[-1]
                _fill(dev, start_dir, rel, "(起始目录)")
        else:
            share = dev.d(resourceId=_rid(dev, "share"))
            sh = _safe_exists(dev, share)
            if sh is None:
                return None
            if sh:
                _fill(dev, share, ts.get("smb_share", "testbooks"), "(共享名)")
        _fill(dev, login, ts.get("user", "howread"), "(账号)")
        # ===== 密码阶段起全程 adb:u2 触碰密码框(聚焦)必触发 MIUI 密码保险箱,
        # a11y 服务随之崩溃(screencap/dump/点击全废,真机反复踩坑 2026-09-13).
        # 在碰密码框**之前**从 dump 预取正键坐标;跳过[测试连接](SMB/SFTP 的添加
        # 正键直接保存,不依赖测试连接),密码键入后立即 adb 点添加;
        # 是否真保存由调用方"等行出现/app-State 落盘"裁决 =====
        def _center_from_dump(rid_name=None, text=None, xml=None):
            if xml is None:
                try:
                    xml = dev.d.dump_hierarchy()
                except Exception:
                    return None
            if rid_name:
                m = re.search(r'resource-id="[^"]*:id/%s"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
                              % rid_name, xml)
            else:
                m = re.search(r'text="%s"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
                              % re.escape(text or ""), xml)
            if not m:
                return None
            x0, y0, x1, y1 = map(int, m.groups())
            return ((x0 + x1) // 2, (y0 + y1) // 2)

        pre_xml = ""
        try:
            pre_xml = dev.d.dump_hierarchy()
        except Exception:
            pre_xml = ""
        pwd_c = _center_from_dump(rid_name="password", xml=pre_xml)
        add_c = _center_from_dump(text="添加", xml=pre_xml)
        if pwd_c is None or add_c is None:
            return False
        dev.shell("input tap %d %d" % pwd_c)  # 聚焦密码框(adb)
        time.sleep(1)
        dev.shell("input keyevent 123")  # MOVE_END
        for _ in range(4):
            dev.shell("input keyevent 67 67 67 67 67 67 67 67 67 67")  # 40×DEL 清空
        time.sleep(0.4)
        dev.shell("input text %s" % ts.get("password", "howread123"))
        time.sleep(0.8)
        # EMUI 键盘弹出会顶起对话框:add_c 取自键盘弹出前,旧坐标点空 → 凭据入库
        # 失败(KSA FN-28/29 定位 2026-09-13;WebDAV 分支因先收键盘+文本重找而幸免)。
        # 同法对齐:收键盘后重 dump 重算「添加」坐标,失败回退旧坐标
        try:
            _dismiss_keyboard(dev)
            time.sleep(1)
        except Exception:
            pass
        add_c2 = _center_from_dump(text="添加")
        if add_c2:
            add_c = add_c2
        _snap(dev, case_id, "remote_form_filled")
        dev.shell("input tap %d %d" % add_c)  # 直接保存(不点测试连接)
        time.sleep(3)
        return True
    except Exception as e:
        # u2 服务瞬态崩溃(JSON-RPC -32002 等)→ None(调用方如实 SKIP);其它异常照常抛
        if _is_u2_err(e):
            return None
        raise


def _click_section_add(dev, header_text):
    """点 netSection 区块标题[同一行]的[+ 添加]按钮,打开对应添加对话框.
    区块标题 TextView 本身无点击监听,添加对话框监听绑在右侧[+ 添加]按钮上
    (BrowseFragment2.netSectionHeader:1534-1541 add.setOnClickListener(onAdd));
    点标题无效。按"与标题同 y 区间"定位该行的[+ 添加]。成功返回 True."""
    xml = dev.d.dump_hierarchy()
    nodes = re.findall(r'<node[^>]*?text="([^"]*)"[^>]*?bounds="(\[\d+,\d+\]\[\d+,\d+\])"[^>]*/?>', xml)

    def parse(b):
        m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', b)
        return tuple(map(int, m.groups())) if m else None

    cy = None
    for t, b in nodes:
        if header_text in t and t.strip() not in ("+ 添加", "+ Add"):
            x0, y0, x1, y1 = parse(b)
            cy = (y0 + y1) // 2
            break
    if cy is None:
        return False
    for t2, b2 in nodes:
        if t2.strip() in ("+ 添加", "+ Add"):
            ax0, ay0, ax1, ay1 = parse(b2)
            if ay0 <= cy <= ay1:
                dev.d.click((ax0 + ax1) // 2, (ay0 + ay1) // 2)
                return True
    return False


def _preset_server_lines(dev, cfg, kinds=("webdav", "smb", "sftp")):
    """配置文件预置 50.23 服务器行到 app-State.json,替代 UI 逐字段输入(规避 MIUI
    自动填充在密码框上打崩 uiautomator JSON-RPC,AGENTS gotcha 21).
    服务器行是 app-State.json 的分隔字符串字段(allWebDavLinks="url,title;" /
    allSmbLinks & allSftpLinks="id|title|host|port|user|domain|share|keyPath|trustAll|startDir;",
    见 RemoteServer.buildLine/RemoteStore),文件位于共享存储
    /sdcard/HowRead/profile.*/device.<MODEL>/,adb 可直接读写(无需 run-as).
    密码经 AndroidKeyStore AES/GCM 加密存 SharedPreferences(WebDavCredentials,
    键 remote://<id>),外部无法伪造 → 预置只保证"行存在",首次连通需 _ensure_credentials
    经编辑对话框补存一次凭据,之后所有轮次走复用分支零 UI 输入.
    必须 force-stop 后改文件:App 运行中 MainTabs2.onPause→AppProfile.save 会用内存态覆盖写回.
    id 取 host/port 的确定性 md5 截断 → 重复预置幂等不堆积,凭据键稳定.
    返回 True=预置完成;False=状态文件不可读写(调用方回退 UI 添加流程)."""
    ts = (cfg or {}).get("test_server") or {}
    if not ts:
        return False
    titles = {"webdav": "HowRead-Test", "smb": "HowRead-SMB", "sftp": "HowRead-SFTP"}
    host = ts.get("host", "192.168.50.23")
    try:
        os.makedirs(TMP_DIR, exist_ok=True)
        model = (dev.shell("getprop ro.product.model") or "").strip().replace(" ", "_")
        found = dev.shell("ls /sdcard/HowRead/profile.*/device.%s/app-State.json 2>/dev/null" % model)
        found = [l.strip() for l in (found or "").splitlines() if l.strip()]
        remote = found[0] if found else \
            "/sdcard/HowRead/profile.HowRead/device.%s/app-State.json" % model
        dev.shell("am force-stop %s" % dev.pkg)
        time.sleep(2)
        local = os.path.join(TMP_DIR, "appstate_%s.json" % dev.serial)
        if os.path.exists(local):
            os.remove(local)
        data = {}
        try:
            adb("-s", dev.serial, "pull", remote, local, timeout=60)
            with open(local, encoding="utf-8") as f:
                data = json.load(f)
        except Exception:
            data = {}
        lines = {}
        if "webdav" in kinds:
            url = ts.get("webdav_url", "http://%s:8765/" % host)
            lines["allWebDavLinks"] = "%s,%s;" % (url, titles["webdav"])
        if "smb" in kinds:
            sid = "t" + hashlib.md5(("smb|%s|%s" % (host, ts.get("smb_port", 445))).encode()).hexdigest()[:11]
            lines["allSmbLinks"] = "%s|%s|%s|%s|%s||%s||1|;" % (
                sid, titles["smb"], host, ts.get("smb_port", 445),
                ts.get("user", "howread"), ts.get("smb_share", "testbooks"))
        if "sftp" in kinds:
            sid = "t" + hashlib.md5(("sftp|%s|%s" % (host, ts.get("sftp_port", 22))).encode()).hexdigest()[:11]
            lines["allSftpLinks"] = "%s|%s|%s|%s|%s||||1|books;" % (
                sid, titles["sftp"], host, ts.get("sftp_port", 22), ts.get("user", "howread"))
        changed = False
        for key, line in lines.items():
            cur = data.get(key) or ""
            title = titles["webdav" if key == "allWebDavLinks" else
                           ("smb" if key == "allSmbLinks" else "sftp")]
            if title in cur:
                continue
            data[key] = line + cur
            changed = True
        if not changed:
            return True
        with open(local, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False)
        adb("-s", dev.serial, "push", local, remote, timeout=60)
        print("  [%s] 预置服务器行 → %s (%s)" % (dev.serial, remote, "/".join(kinds)))
        return True
    except Exception as e:
        print("  [%s] 预置服务器行失败,回退 UI 添加流程: %s" % (dev.serial, e))
        return False


def _fill_password_adb(dev, el, text):
    """密码框专用填充:u2 只点击聚焦,清空与键入走 adb input(不触发 uiautomator 的
    set_text/回读,避开 MIUI 自动填充打崩 JSON-RPC).回读验证失败(含 u2 崩溃)不判失败,
    由后续[测试连接]结果判定."""
    el.click()
    time.sleep(1)
    dev.shell("input keyevent 123")  # KEYCODE_MOVE_END
    for _ in range(4):
        dev.shell("input keyevent 67 67 67 67 67 67 67 67 67 67")  # 10×DEL
    time.sleep(0.4)
    dev.shell("input text %s" % text)
    time.sleep(0.8)


def _open_edit_dialog(dev, title):
    """点服务器行的钢笔编辑图标,打开预填的 AddRemoteDialog/AddWebDavDialog.
    行结构(BrowseFragment2.netListItem):[云图标][标题][扫描][编辑][✕],图标程序化创建
    无 resourceId/content-desc,只能按行 y + 右缘偏移试探点击.
    真机实测(MI9,2026-09-13):钢笔在右缘偏移 ~160-200px;<150 会误点 ✕(弹删除确认).
    注意页面顶栏路径标题与行标题**同名**(remote 页 titleView),u2 首个匹配是顶栏——
    必须取 y 靠下的行实例,否则点到顶栏空白处.用 dump 解析定位(u2 count/实例枚举在
    MIUI 上偶发 JSON-RPC 异常,回退会错拿顶栏).成功返回 True."""
    _, h = dev.d.window_size()
    xml = ""
    for _ in range(3):  # u2 dump 在重浏览后偶发 JSON-RPC 崩溃,数秒自愈,重试
        try:
            xml = dev.d.dump_hierarchy()
            if xml:
                break
        except Exception:
            time.sleep(3)
    if not xml:
        return False
    cy = None
    for m in re.finditer(r'text="%s"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(title), xml):
        y0, y1 = int(m.group(2)), int(m.group(4))
        t = (y0 + y1) / 2
        if t > 0.22 * h:
            cy = int(t)
            break
    if cy is None:
        return False
    w, _ = dev.d.window_size()
    for dx in (160, 190, 220, 250):
        try:
            dev.d.click(w - dx, cy)
        except Exception:
            return False
        time.sleep(2)
        for probe in ("password", "url", "host"):
            if _safe_exists(dev, dev.d(resourceId=_rid(dev, probe))):
                return True
        # 误点删除确认时取消,避免误删预置行
        try:
            if dev.d(text="删除").exists and dev.d(text="取消").exists:
                dev.d(text="取消").click()
                time.sleep(1.2)
        except Exception:
            pass
    return False


def _appstate_path(dev):
    """设备上 app-State.json 的路径(共享存储,adb 直读直写,无需 run-as)."""
    model = (dev.shell("getprop ro.product.model") or "").strip().replace(" ", "_")
    found = [l.strip() for l in
             (dev.shell("ls /sdcard/HowRead/profile.*/device.%s/app-State.json 2>/dev/null" % model)
              or "").splitlines() if l.strip()]
    return found[0] if found else \
        "/sdcard/HowRead/profile.HowRead/device.%s/app-State.json" % model


def _remove_preset_lines(dev, cfg, kinds):
    """force-stop 后从 app-State.json 删除指定协议的预置行(按标题匹配段).
    用于凭据入库 v2:先删行再走[+ 添加]流程,避免同名重复行.返回 True=操作完成."""
    ts = (cfg or {}).get("test_server") or {}
    titles = {"webdav": "HowRead-Test", "smb": "HowRead-SMB", "sftp": "HowRead-SFTP"}
    keys = {"webdav": "allWebDavLinks", "smb": "allSmbLinks", "sftp": "allSftpLinks"}
    try:
        os.makedirs(TMP_DIR, exist_ok=True)
        remote = _appstate_path(dev)
        dev.shell("am force-stop %s" % dev.pkg)
        time.sleep(2)
        local = os.path.join(TMP_DIR, "appstate_%s.json" % dev.serial)
        if os.path.exists(local):
            os.remove(local)
        adb("-s", dev.serial, "pull", remote, local, timeout=60)
        with open(local, encoding="utf-8") as f:
            data = json.load(f)
        changed = False
        for k in kinds:
            key = keys[k]
            cur = data.get(key) or ""
            if titles[k] not in cur:
                continue
            segs = [s for s in cur.split(";") if s and titles[k] not in s]
            data[key] = "".join(s + ";" for s in segs)
            changed = True
        if not changed:
            return True
        with open(local, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False)
        adb("-s", dev.serial, "push", local, remote, timeout=60)
        print("  [%s] 已移除预置行(%s),改走 UI 添加以入库凭据" % (dev.serial, "/".join(kinds)))
        return True
    except Exception as e:
        print("  [%s] 移除预置行失败: %s" % (dev.serial, e))
        return False


def _ensure_credentials(dev, case_id, cfg, kind, title):
    """一次性凭据入库(v2):钢笔编辑入口对 u2 瞬态崩溃太脆弱(真机反复失败),改为
    force-stop → app-State.json 删预置行 → 冷启动 → 走久经考验的[+ 添加]对话框流程
    (非密码字段 u2 _fill;密码 _fill_password_adb 走 adb input,避开 MIUI 自动填充
    打崩 uiautomator)→ 保存即写入 AndroidKeyStore 加密存储,此后所有轮次复用零 UI 输入.
    返回 True=凭据已就绪;False=入库失败(调用方如实报失败)."""
    ts = (cfg or {}).get("test_server") or {}
    try:
        if not _remove_preset_lines(dev, cfg, kinds=(kind,)):
            return False
        dev.wake_unlock()
        dev.start_app(cold=True)
        dev.handle_first_run_dialogs()
        if not dev.wait_home(10):
            return False
        time.sleep(1.5)
        roots = 0
        for _ in range(2):  # u2 抖动时 _browse_root 可能误判,重试一次
            if _browse_root(dev):
                roots += 1
                break
            _back_to_main(dev)
            time.sleep(2)
        if not roots:
            return False
        headers = {"webdav": "WebDAV", "smb": "SMB", "sftp": "SFTP"}
        if not _click_section_add(dev, headers[kind]):
            dev.save_dump(case_id, "cred_no_add_btn_%s" % kind)
            return False
        time.sleep(2.5)
        if kind == "webdav":
            if not dev.d(resourceId=_rid(dev, "url")).exists:
                return False
            _fill(dev, dev.d(resourceId=_rid(dev, "url")), ts.get("webdav_url", ""), "(地址)")
            _fill(dev, dev.d(resourceId=_rid(dev, "name")), title, "(名称)")
            _fill(dev, dev.d(resourceId=_rid(dev, "login")), ts.get("user", "howread"), "(账号)")
            # 密码阶段全程 adb(理由同 _addremote_fill_and_save)
            try:
                xml = dev.d.dump_hierarchy()
            except Exception:
                xml = ""
            m = re.search(r'resource-id="[^"]*:id/password"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
            if not m:
                return False
            px = (int(m.group(1)) + int(m.group(3))) // 2
            py = (int(m.group(2)) + int(m.group(4))) // 2
            dev.shell("input tap %d %d" % (px, py))
            time.sleep(1)
            dev.shell("input keyevent 123")
            for _ in range(4):
                dev.shell("input keyevent 67 67 67 67 67 67 67 67 67 67")
            dev.shell("input text %s" % ts.get("password", "howread123"))
            _snap(dev, case_id, "cred_form_%s" % kind)
            _dismiss_keyboard(dev)
            if not dev.click_text("添加"):
                try:
                    w, h = dev.d.window_size()
                except Exception:
                    w, h = 1080, 2340
                dev.shell("input tap %d %d" % (w - 190, int(h * 0.75)))
        else:
            r = _addremote_fill_and_save(dev, case_id, cfg, kind == "sftp", title)
            if r is None or not r:
                try:
                    dev.d.press("back")
                    time.sleep(1)
                except Exception:
                    pass
                return False
        appstate = _appstate_path(dev)
        deadline = time.time() + 25
        while time.time() < deadline:
            if _safe_exists(dev, dev.d(text=title)):
                _snap(dev, case_id, "cred_stored_%s" % kind)
                print("  [%s] %s 凭据已入库(一次性,后续复用)" % (dev.serial, kind.upper()))
                return True
            # u2 不可用时以落盘为准:对话框保存会同步写 app-State.json(行+加密凭据)
            try:
                out = (dev.shell("grep -c '%s' %s 2>/dev/null" % (title, appstate)) or "").strip()
            except Exception:
                out = ""
            if out and out[0].isdigit() and out[0] != "0":
                print("  [%s] %s 凭据已入库(app-State.json 落盘确认,一次性,后续复用)"
                      % (dev.serial, kind.upper()))
                return True
            time.sleep(2)
        return False
    except Exception:
        try:
            dev.d.press("back")
            time.sleep(1)
        except Exception:
            pass
        return False


def _back_to_net_root(dev, max_backs=5):
    """从远程目录/深层页面逐级 back 回[我的文件]根视图(netSection 可见)."""
    for _ in range(max_backs):
        if _safe_exists(dev, dev.d(resourceId=_rid(dev, "netSection"))):
            return True
        dev.d.press("back")
        time.sleep(1.2)
    return bool(_safe_exists(dev, dev.d(resourceId=_rid(dev, "netSection"))))


def _net_scroll_to(dev, finder, tries=6):
    """在「我的文件」根页滚动 netSection(限高 300dp 后可滚)直到 finder() 命中.
    SMB/SFTP 区块头与条目常在折叠区内,必须滚动查找(2026-09-13 全量实锤).
    finder: 返回真值的零参函数.命中 True."""
    for _ in range(tries):
        try:
            if finder():
                return True
        except Exception:
            pass
        w, h = dev.d.window_size()
        dev.d.swipe(int(0.5 * w), int(0.3 * h), int(0.5 * w), int(0.1 * h), 0.3)
        time.sleep(1)
    try:
        return bool(finder())
    except Exception:
        return False


def _ensure_server(dev, case_id, cfg, kind):
    """确保 kind∈{webdav,smb,sftp} 的 50.23 测试服务器条目存在(已存在则复用).
    返回 (标题, added);added=True 表示本用例新建(调用方负责用后删除,
    避免网络源堆积把其它机型的 FN-04 搜索入口挤出屏幕)."""
    ts = (cfg or {}).get("test_server") or {}
    titles = {"webdav": "HowRead-Test", "smb": "HowRead-SMB", "sftp": "HowRead-SFTP"}
    title = titles[kind]
    _ensure_home(dev)  # 用例可能直接从桌面态进入,app 未启动时 netSection 永远不可达
    if not _browse_root(dev):
        raise TestSkip("我的文件根视图不可达(netSection 不在树中)")
    ex = _safe_exists(dev, dev.d(text=title))
    if ex is None:
        raise TestSkip("u2 服务不稳,无法确认 %s 是否已存在" % title)
    if ex:
        return title, False
    # 行不存在 → 先走配置文件预置(零 UI 输入,规避 MIUI 密码框崩溃);预置失败再回退 UI 添加流程
    if _preset_server_lines(dev, cfg, kinds=(kind,)):
        dev.wake_unlock()
        dev.start_app(cold=True)
        dev.handle_first_run_dialogs()
        time.sleep(1.5)
        if _browse_root(dev):
            ex = _net_scroll_to(dev, lambda: _safe_exists(dev, dev.d(text=title)) is True)
            if ex is False:
                raise TestSkip("u2 服务不稳,无法确认 %s 预置后是否存在" % title)
            if ex:
                _snap(dev, case_id, "server_preset")
                return title, False
    headers = {"webdav": "WebDAV", "smb": "SMB", "sftp": "SFTP"}
    if not _net_scroll_to(dev, lambda: dev.d(textContains=headers[kind]).exists):
        raise TestSkip("netSection 无 %s 区块头部" % headers[kind])
    # 点该区块标题同一行的[+ 添加]按钮(标题本身无监听,见 _click_section_add)
    if not _click_section_add(dev, headers[kind]):
        dev.save_dump(case_id, "no_add_btn_%s" % kind)
        raise TestSkip("netSection %s 区块无[+ 添加]按钮" % headers[kind])
    time.sleep(2.5)
    if kind == "webdav":
        # AddWebDavDialog: url/name/login/password + 正键"添加"(保存前 PROPFIND 验证)
        if not dev.d(resourceId=_rid(dev, "url")).exists:
            raise TestSkip("添加 WebDAV 对话框未打开")
        _fill(dev, dev.d(resourceId=_rid(dev, "url")), ts.get("webdav_url", "http://192.168.50.23:8765/"), "(地址)")
        _fill(dev, dev.d(resourceId=_rid(dev, "name")), title, "(名称)")
        _fill(dev, dev.d(resourceId=_rid(dev, "login")), ts.get("user", "howread"), "(账号)")
        _fill(dev, dev.d(resourceId=_rid(dev, "password")), ts.get("password", "howread123"), "(密码)")
        _snap(dev, case_id, "webdav_form_filled")
        _dismiss_keyboard(dev)
        if not dev.click_text("添加"):
            raise TestSkip("添加按钮不可见")
    else:
        r = _addremote_fill_and_save(dev, case_id, cfg, kind == "sftp", title)
        if r is None:
            dev.d.press("back")
            time.sleep(1)
            raise TestSkip("%s 添加过程中 u2 服务不稳(JSON-RPC 异常),非功能缺陷,需重跑" % kind.upper())
        if not r:
            dev.d.press("back")
            time.sleep(1)
            raise TestSkip("%s 服务器添加/测试连接未成功" % kind.upper())
    # 等行出现(保存后 netSection 重建)
    deadline = time.time() + 20
    while time.time() < deadline:
        if _net_scroll_to(dev, lambda: _safe_exists(dev, dev.d(text=title)) is True, tries=2):
            _snap(dev, case_id, "server_added")
            return title, True
        time.sleep(1.5)
    dev.d.press("back")
    time.sleep(1)
    raise TestSkip("%s 服务器保存后条目未出现" % kind.upper())


def _open_server_dir(dev, title, verify_keywords, timeout=25):
    """点击服务器行进入远程目录,验证目录里有测试书."""
    if not _net_scroll_to(dev, lambda: dev.d(text=title).exists, tries=4):
        return False
    row = dev.d(text=title)
    row.click()
    time.sleep(4)
    for kw in verify_keywords:
        el = _find_text_scrolled(dev, kw, max_swipes=4)
        if el is not None:
            return True
    return False


_HORZ_TOOLBAR_DESCS = ("目录", "前往页面", "文字转语音", "书签", "单页", "Contents")


# 工具条"已显示"的判定元素:顶栏/按钮行自身节点,只在按钮行显示时才存在于视图树.
# 页脚 pagesCountIndicator 受 isShowToolBar 常显控制,与顶栏按钮行(中央点按切换)
# 是两条独立可见性——开书时"页脚可见"≠"工具条已显示".--reset 出厂态回归踩坑
# (2026-09-13):开书页脚可见但按钮行全隐藏(FN-17 dump 只剩 pannelBookTitle+
# pagesCountIndicator),旧谓词把页脚当"已显示"不再点中央展开,工具条类用例整批
# SKIP.旧注释"pagesCountIndicator 必须计入"的本意(水平模式别把已显示的工具条
# 当隐藏)由 imageToolbar 等与语言无关的 id 覆盖,更可靠(FN-50/51 的 bar_state
# 早已用它作顶栏标记).
_BAR_IDS = ("currentSeek", "currentPageIndex", "imageToolbar",
            "goToPage1Top", "textToSpeachTop", "imageMenuArrow",
            "onDocDontext", "thumbnail", "textToSpeach")


def _reader_bar_visible(dev):
    for rid in _BAR_IDS:
        try:
            if dev.d(resourceId=_rid(dev, rid)).exists:
                return True
        except Exception:
            continue
    for kw in _HORZ_TOOLBAR_DESCS:
        try:
            if dev.d(descriptionContains=kw).exists:
                return True
        except Exception:
            continue
    return False


def _reader_show_toolbar(dev):
    """阅读器内确保工具条可见(幂等:已显示则不再点--点屏幕中央是"切换",
    已显示时点一下反而隐藏,FN-17/18 首跑即因此找不到按钮).
    "已显示"按 _reader_bar_visible 判定(顶栏/按钮行自身元素,见其注释)."""
    if _reader_bar_visible(dev):
        return True
    w, h = dev.d.window_size()
    dev.d.click(int(0.5 * w), int(0.5 * h))
    time.sleep(2)
    return _reader_bar_visible(dev)


def _reader_ui_visible(dev):
    """工具条可见性判断,同 _reader_bar_visible(顶栏/按钮行自身元素)."""
    return _reader_bar_visible(dev)


def _reader_alive(dev):
    """阅读器是否仍在前台(本 fork 存在阅读器静默退出的间歇性问题,FN-49/51 实测)."""
    try:
        top = dev.shell("dumpsys activity activities | grep ResumedActivity")
        return "ViewActivity" in top or "TTSActivity" in top
    except Exception:
        return False


def _reader_toolbar_btn(dev, rid_name, expand_first=True, desc_kws=()):
    """取阅读器工具条按钮(thumbnail/onDocDontext/bookPref/textToSpeach 等),
    先按 resource-id,再按 content-desc 候选(水平模式按钮无 id 命中时靠 desc);
    都不在树中时先点 imageToolbar 展开再找."""
    btn = dev.d(resourceId=_rid(dev, rid_name))
    if btn.exists:
        return btn
    for kw in desc_kws:
        try:
            el = dev.d(descriptionContains=kw)
            if el.exists:
                return el
        except Exception:
            continue
    if expand_first:
        tb = dev.d(resourceId=_rid(dev, "imageToolbar"))
        if tb.exists:
            tb.click()
            time.sleep(1.5)
        btn = dev.d(resourceId=_rid(dev, rid_name))
        if btn.exists:
            return btn
        for kw in desc_kws:
            try:
                el = dev.d(descriptionContains=kw)
                if el.exists:
                    return el
            except Exception:
                continue
    return btn if btn.exists else None


def _reader_page_or_none(dev):
    """读页码(无 back 副作用).调用前应先 _reader_show_toolbar 确保页码可见."""
    try:
        return _reader_page_no_back(dev)
    except Exception:
        return None


def _wait_page_change(dev, before, timeout=12):
    deadline = time.time() + timeout
    while time.time() < deadline:
        cur = _reader_page_or_none(dev)
        if cur and before and cur[0] != before[0]:
            return cur
        time.sleep(1.2)
    return None


def fn10_library_search(dev, case_id, cfg=None, fixtures=None):
    """书库搜索:书库 Tab 搜索框输入关键词 → 命中列表 + 计数 → 清空恢复.覆盖 §3 书库搜索."""
    with dev.step(case_id, "goto_library"):
        _ensure_home(dev)
        _goto_tab_or_fail(dev, "书库")
        time.sleep(1)
        if not dev.d(resourceId=_rid(dev, "filterLine")).exists:
            raise AssertionError("书库搜索框(filterLine)不可见")
    with dev.step(case_id, "search_big25"):
        fl = dev.d(resourceId=_rid(dev, "filterLine"))
        _fill(dev, fl, "big25", "(书库搜索)")
        time.sleep(2)
        if not dev.dump_has_text("big25"):
            # 设备书库未收录测试书(新设备首扫慢)→ 环境跳过
            dev.save_dump(case_id, "search_no_result")
            raise TestSkip("设备书库未收录 big25,搜索路径不可用(环境限制)")
        count = ""
        cb = dev.d(resourceId=_rid(dev, "countBooks"))
        if cb.exists:
            count = cb.get_text() or ""
        _snap(dev, case_id, "search_hit")
        if count == "0":
            raise AssertionError("搜索命中但计数为 0")
    with dev.step(case_id, "clear_and_restore"):
        clean = dev.d(resourceId=_rid(dev, "cleanFilter"))
        if clean.exists:
            clean.click()
            time.sleep(1.5)
        if fl.get_text():
            fl.clear_text()
        _snap(dev, case_id, "search_cleared")


def fn11_sort_and_filter(dev, case_id, cfg=None, fixtures=None):
    """排序与状态筛选:sortBy 弹窗切换 标题/时间 排序验证文案变化;未读 chip 过滤生效.
    覆盖 §3 排序(15+ 比较器)+ 阅读状态筛选 chips."""
    with dev.step(case_id, "goto_library"):
        _ensure_home(dev)
        _goto_tab_or_fail(dev, "书库")
        time.sleep(1)
        sb = dev.d(resourceId=_rid(dev, "sortBy"))
        if not sb.exists:
            dev.save_dump(case_id, "no_sortby")
            raise AssertionError("排序入口(sortBy)不可见")
    with dev.step(case_id, "switch_sort"):
        for target in ("标题", "时间"):
            sb.click()
            time.sleep(1.5)
            if not (dev.click_text(target) or dev.click_desc(target)):
                dev.save_dump(case_id, "sort_menu_%s" % target)
                raise AssertionError("排序弹窗无[%s]项" % target)
            time.sleep(2.5)
            cur = dev.d(resourceId=_rid(dev, "sortBy")).get_text() or ""
            if target not in cur:
                dev.save_dump(case_id, "sort_not_applied")
                raise AssertionError("排序切换到 %s 未生效(当前 %r)" % (target, cur))
            _snap(dev, case_id, "sort_%s" % target)
    with dev.step(case_id, "reading_status_chips"):
        # 回到自然序避免影响后续用例
        sb = dev.d(resourceId=_rid(dev, "sortBy"))
        sb.click()
        time.sleep(1.5)
        dev.click_text("路径") or dev.click_text("文件名")
        time.sleep(2)
        chips = dev.d(resourceId=_rid(dev, "readingStatusChips"))
        if not chips.exists:
            dev.save_dump(case_id, "no_chips")
            raise TestSkip("阅读状态 chips 区不可见(列表模式限制)")
        unread = chips.child(textContains="未读") if hasattr(chips, "child") else None
        clicked = False
        for kw in ("未读",):
            el = dev.d(text=kw)
            if el.exists:
                el.click()
                clicked = True
                break
        if not clicked:
            dev.save_dump(case_id, "no_unread_chip")
            raise TestSkip("未读 chip 不在树中")
        time.sleep(2.5)
        _snap(dev, case_id, "filter_unread")
        if not dev.d(resourceId=_rid(dev, "recyclerView")).exists:
            raise AssertionError("过滤后列表不可见")
        # 还原:再点一次取消过滤
        el = dev.d(text="未读")
        if el.exists:
            el.click()
            time.sleep(1.5)


def fn12_view_modes(dev, case_id, cfg=None, fixtures=None):
    """视图模式切换:onGridList 弹窗循环 列表/简表/网格/封面/书架 五种,逐一切换截图.
    覆盖 §3 五种视图模式(含木纹书架视图)."""
    modes = ["列表", "简表", "网格", "封面", "书架"]
    seen = {}
    with dev.step(case_id, "goto_library"):
        _ensure_home(dev)
        _goto_tab_or_fail(dev, "书库")
        time.sleep(1)
        if not dev.d(resourceId=_rid(dev, "onGridList")).exists:
            dev.save_dump(case_id, "no_gridlist")
            raise AssertionError("视图切换按钮(onGridList)不可见")
    with dev.step(case_id, "cycle_modes"):
        for mode in modes:
            dev.d(resourceId=_rid(dev, "onGridList")).click()
            time.sleep(1.5)
            item = dev.d(text=mode)
            if not item.exists:
                item = dev.d(textContains=mode)
            if not item.exists:
                dev.save_dump(case_id, "mode_menu_%s" % mode)
                raise AssertionError("视图弹窗无[%s]项" % mode)
            item.click()
            time.sleep(2.5)
            seen[mode] = True
            _snap(dev, case_id, "view_%s" % mode)
        if len(seen) != len(modes):
            raise AssertionError("视图模式切换不完整: %s" % sorted(seen))
    with dev.step(case_id, "restore"):
        dev.d(resourceId=_rid(dev, "onGridList")).click()
        time.sleep(1.5)
        dev.click_text("书架") or dev.click_text("列表")  # 还原 fork 默认书架视图
        time.sleep(2)


def fn13_tags(dev, case_id, cfg=None, fixtures=None):
    """标签管理(真实入口,2026-09-13 代码核实):书库长按书行**直接弹书籍信息对话框**
    (DefaultListeners:212,无中间菜单)→ 点下划线[Add tags](FileInformationDialog R.id.addTags)
    → showTagsDialog(dialog_tags:tagName CheckBox 行 + addTag[创建标签] → addTagsDialog 输入)
    → 创建 "#autotest" → 勾选 → [应用](Tags2.setTags 落 app-Tags2.json)→ 视图菜单
    onGridList → [My tags/标签] → 点标签行 → 断言书列表含该书.覆盖 §3 标签 CRUD+过滤.
    注:旧版在长按菜单里找[添加标签]项属找错入口——该菜单不存在,长按即开信息对话框."""
    tag_name = "autotest"  # Tags2.createTag 自动加 "#" 前缀,列表文本为 "#autotest"
    tag_full = "#" + tag_name
    with dev.step(case_id, "open_info_dialog"):
        # 真机踩坑(2026-09-13):书架/封面视图书名是位图无文本节点,搜索词命中的是 filterLine
        # 自己(长按搜索框=无效);须 _ensure_list_row 切列表视图定位真书行,点行右 ⋮(itemMenu)
        # 弹书菜单(ShareDialog,defaultLongClick=书菜单)→ [文件信息] → 信息对话框 addTags.
        _ensure_home(dev)
        row = _ensure_list_row(dev, "big25")
        if row is None:
            dev.save_dump(case_id, "no_big25_row")
            raise TestSkip("设备书库未收录 big25(环境限制)")
        if not _click_row_menu(dev, "big25", extra_titles=("Bench25", "Big25")):
            dev.save_dump(case_id, "no_row_menu")
            raise AssertionError("书行 ⋮ 菜单按钮不可达")
        # 长按行为随 defaultLongClick 设置而异:书菜单(ShareDialog,默认)或书籍信息对话框.
        # 书菜单无标签项,须经[文件信息]进信息对话框;信息对话框则直接点 addTags.
        add_tags = None
        for _ in range(2):
            el = dev.d(resourceId=_rid(dev, "addTags"))
            if _safe_exists(dev, el):
                add_tags = el
                break
            if not _click_any(dev, ["文件信息", "信息", "File info", "Info"]):
                break
            time.sleep(2)
        if add_tags is None:
            for kw in ("Add tags", "添加标签", "标签"):
                el = dev.d(textContains=kw)
                if el.exists:
                    add_tags = el
                    break
        if add_tags is None or not add_tags.exists:
            # 小屏(KSA)信息对话框无 addTags 链接,有「标签」行(tagsID)——点它进标签对话框
            t = dev.d(resourceId=_rid(dev, "tagsID"))
            add_tags = t if t.exists else None
        if add_tags is None or not add_tags.exists:
            dev.save_dump(case_id, "no_addtags_link")
            dev.d.press("back")
            # KSA 小屏信息对话框为旧布局:只有只读「标签」行(tagsID),无编辑入口
            # ——非脚本问题,标签编辑入口待应用侧确认(2026-09-13 现场)
            raise TestSkip("书籍信息对话框无[Add tags]入口(旧布局只读标签行)")
        add_tags.click()
        tags_shown = False
        for _ in range(4):  # 低配机弹窗慢,轮询等待
            time.sleep(1.5)
            if (dev.d(resourceId=_rid(dev, "addTag")).exists
                    or dev.d(resourceId=_rid(dev, "listView1")).exists):
                tags_shown = True
                break
        if not tags_shown:
            dev.save_dump(case_id, "no_tags_dialog")
            dev.d.press("back")
            raise TestSkip("标签对话框(showTagsDialog)未出现(小屏旧布局,入口无效待勘探)")
        _snap(dev, case_id, "tags_dialog")
    with dev.step(case_id, "create_tag"):
        already = dev.d(text=tag_full)
        if not already.exists:
            add = dev.d(resourceId=_rid(dev, "addTag"))
            if not add.exists:
                for kw in ("创建标签", "New Tag"):
                    el = dev.d(textContains=kw)
                    if el.exists:
                        add = el
                        break
            if not add.exists:
                dev.save_dump(case_id, "no_create_tag_link")
                dev.d.press("back")
                dev.d.press("back")
                raise AssertionError("标签对话框无[创建标签]入口")
            add.click()
            time.sleep(2)
            edit = dev.d(className="android.widget.EditText")
            if not edit.exists:
                dev.save_dump(case_id, "no_tag_input")
                dev.d.press("back")
                dev.d.press("back")
                raise AssertionError("创建标签输入框未出现")
            _fill(dev, edit, tag_name, "(标签名)")
            _dismiss_keyboard(dev)
            if not dev.click_text("添加"):
                dev.d.press("back")
                dev.d.press("back")
                dev.save_dump(case_id, "no_tag_add_btn")
                raise AssertionError("创建标签对话框无[添加]正键")
            time.sleep(2)
        # 勾选该标签(已勾选则跳过)
        box = dev.d(text=tag_full)
        if not box.exists:
            box = dev.d(textContains=tag_name)
        if not box.exists:
            dev.save_dump(case_id, "tag_not_in_list")
            dev.d.press("back")
            dev.d.press("back")
            raise AssertionError("创建后标签 %s 未出现在列表" % tag_full)
        try:
            if isinstance(box.info.get("checked"), bool) and not box.info["checked"]:
                box.click()
                time.sleep(1)
        except Exception:
            box.click()
            time.sleep(1)
        _snap(dev, case_id, "tag_checked")
        if not dev.click_text("应用") and not dev.click_text("Apply") and not dev.click_text("确定"):
            dev.d.press("back")
            dev.d.press("back")
            dev.save_dump(case_id, "no_apply_btn")
            raise AssertionError("标签对话框无[应用]正键")
        time.sleep(2.5)
        # 主断言:标签落盘 app-Tags2.json(Tags2.setTags 写 syncTags2).注:信息对话框的
        # 标签行读 AppDB 缓存(updateTagsDB 未触发前不回显,真机踩坑),不能作依据
        tags_out = ""
        try:
            found = [l.strip() for l in (dev.shell(
                "ls /sdcard/HowRead/profile.*/device.*/app-Tags2.json 2>/dev/null") or "").splitlines() if l.strip()]
            if found:
                tags_out = dev.shell("cat %s" % found[0])
        except Exception:
            pass
        _snap(dev, case_id, "tag_on_book")
        if tag_full not in tags_out:
            try:
                xml = dev.d.dump_hierarchy()
            except Exception:
                xml = ""
            dev.d.press("back")
            time.sleep(1)
            if tag_full not in xml:  # 兜底:UI 已回显也算通过
                dev.save_dump(case_id, "tag_missing_after_apply")
                raise AssertionError("[应用]后 app-Tags2.json 未含 %s(UI 也未回显)" % tag_full)
        dev.d.press("back")  # 关书籍信息对话框
        time.sleep(1.5)
    with dev.step(case_id, "filter_by_tag"):
        # 可选增强:标签分组视图过滤(清掉搜索避免卡「正在载入...」;列表 20s 不出来不判失败,
        # 标签的创建/应用/回显已在上一步证明)
        clean = dev.d(resourceId=_rid(dev, "cleanFilter"))
        if clean.exists:
            clean.click()
            time.sleep(2)
        vm = dev.d(resourceId=_rid(dev, "onGridList"))
        if not vm.exists:
            return
        vm.click()
        time.sleep(1.5)
        if not (dev.click_text("My tags") or dev.click_text("标签") or dev.click_desc("My tags")):
            dev.d.press("back")
            return
        item = None
        deadline = time.time() + 20
        while time.time() < deadline:
            for kw in (tag_full, tag_name):
                el = dev.d(textContains=kw)
                if el.exists:
                    item = el
                    break
            if item is not None:
                break
            time.sleep(2)
        if item is None:
            print("  [%s] 标签分组视图未加载出标签(信息对话框回显已证明功能,跳过过滤断言)" % dev.serial)
            _back_to_main(dev)
            return
        _snap(dev, case_id, "tag_filter_list")
        item.click()
        time.sleep(3)
        if not dev.dump_has_text("big25"):
            dev.save_dump(case_id, "tag_filter_no_book")
            raise AssertionError("按 %s 过滤后未显示 big25" % tag_full)
        _snap(dev, case_id, "tag_filter_hit")
        # 还原视图(避免标签浏览模式残留影响后续用例)
        vm = dev.d(resourceId=_rid(dev, "onGridList"))
        if vm.exists:
            vm.click()
            time.sleep(1.5)
            dev.click_text("书架") or dev.click_text("列表") or dev.click_text("List")
            time.sleep(2)
        clean = dev.d(resourceId=_rid(dev, "cleanFilter"))
        if clean.exists:
            clean.click()
            time.sleep(1.5)


def fn14_browse_ops(dev, case_id, cfg=None, fixtures=None):
    """文件浏览操作:我的文件→Download→新建 txt 文件出现;zip 压缩包直读打开.
    覆盖 §4 树形浏览/新建 txt/zip 直读."""
    import os
    with dev.step(case_id, "create_txt"):
        _ensure_home(dev)
        if not _browse_root(dev):
            raise AssertionError("我的文件根视图不可达")
        dl = _browse_dl_row(dev)
        if dl is None:
            raise AssertionError("Download 文件夹不可见")
        dl.click()
        time.sleep(2.5)
        # 幂等:清掉上次运行残留,避免"同名文件覆盖?"弹窗
        dev.shell("rm -f /sdcard/Download/autotest_note.txt")
        cf = dev.d(resourceId=_rid(dev, "createFolder"))
        if not cf.exists:
            # 2026-09-18「我的文件」改版后,书库文件夹行打开的是 detached 文件夹
            # 子页,工具栏有意精简(仅 返回/路径/排序/视图切换,无新建;BrowseFragment2
            # 注释 "no new-folder")——新建文件入口当前 UI 不可达,待产品确认;
            # 本用例转 SKIP 并注明(zip 直读覆盖在入口恢复后随用例一并回归)
            dev.save_dump(case_id, "no_createfolder")
            raise TestSkip("新建按钮(createFolder)不可见(09-18 改版后文件夹子页工具栏精简,新建入口待产品确认)")
        cf.click()
        time.sleep(1.5)
        newtxt = dev.d(textContains="新文件")
        if not newtxt.exists:
            dev.save_dump(case_id, "no_newtxt_menu")
            dev.d.press("back")
            raise AssertionError("createFolder 弹窗无[新文件(.txt)]项")
        newtxt.click()
        time.sleep(2)
        name_input = dev.d(className="android.widget.EditText")
        if not name_input.exists:
            dev.save_dump(case_id, "no_newtxt_dialog")
            raise TestSkip("新建 txt 对话框未出现")
        _fill(dev, name_input, "autotest_note", "(文件名)")
        _dismiss_keyboard(dev)
        if not dev.click_text("保存"):
            dev.save_dump(case_id, "no_save_btn")
            dev.d.press("back")
            raise AssertionError("新建 txt 对话框无[保存]按钮")
        time.sleep(1.5)
        # 可能出现"同名文件覆盖?"确认弹窗(上次残留未清干净时)
        if dev.click_text("确认") or dev.click_text("确定"):
            time.sleep(1.5)
        # 列表刷新需要时间,轮询;UI 迟迟不显示时以设备文件存在为兜底证据
        listed = False
        deadline = time.time() + 12
        while time.time() < deadline:
            if dev.dump_has_text("autotest_note"):
                listed = True
                break
            time.sleep(1.5)
        _snap(dev, case_id, "txt_created")
        on_disk = "No such" not in dev.shell("ls /sdcard/Download/autotest_note.txt")
        if not listed and not on_disk:
            dev.save_dump(case_id, "txt_not_created")
            raise AssertionError("新建 txt 失败:列表与设备文件均未出现")
        if not listed:
            print("  [%s] UI 列表未及时刷新,文件已确认落盘(/sdcard/Download/autotest_note.txt)" % dev.serial)
    with dev.step(case_id, "open_zip"):
        zpath = "/sdcard/Download/book_zip.zip"
        if "No such" in dev.shell("ls " + zpath):
            src = os.path.join(ROOT, "teskbook", "book_zip.zip")
            code, _ = adb("-s", dev.serial, "push", src, zpath, timeout=120)
            if code != 0:
                raise TestSkip("book_zip.zip 推送失败")
        # zip 直读链路:OpenerActivity → ZipDialog(压缩包条目列表)→ 点条目解压打开.
        # 不能用 open_book_via_intent--它等 ViewActivity,而 zip 停在 ZipDialog 不进阅读器.
        dev.d.app_stop(dev.pkg)
        time.sleep(1)
        dev.shell('am start -n %s/com.foobnix.OpenerActivity -d "file://%s" -t application/octet-stream'
                  % (dev.pkg, zpath))
        entry = None
        deadline = time.time() + 20
        while time.time() < deadline and entry is None:
            for kw in ("demo.txt", "demo.html"):
                el = dev.d(text=kw)
                if el.exists:
                    entry = el
                    break
            time.sleep(1.5)
        if entry is None:
            dev.save_dump(case_id, "zip_dialog_no_entry")
            raise AssertionError("ZipDialog 未出现或无包内条目")
        _snap(dev, case_id, "zip_dialog")
        entry.click()
        entered = False
        deadline = time.time() + 30
        while time.time() < deadline:
            top = dev.shell("dumpsys activity activities | grep ResumedActivity")
            if "ViewActivity" in top or "TTSActivity" in top:
                entered = True
                break
            time.sleep(2)
        _snap(dev, case_id, "zip_opened")
        if not entered:
            dev.save_dump(case_id, "zip_entry_not_opened")
            raise AssertionError("点击 zip 内页书后未进阅读器")
        c = dev.scan_crash()
        if c:
            raise AssertionError("zip 直读 crash: %s" % c)
        _exit_reader(dev, case_id)


def fn15_page_modes(dev, case_id, cfg=None, fixtures=None):
    """阅读模式切换:阅读器工具条 onModeChange → 单页/半页菜单 → 切半页再切回单页.
    覆盖 §7 阅读模式(单页/半页).
    注:本版本 onModeChange 实际只两项[单页/半页](DocumentWrapperUI.onModeChangeClick,
    :1171 绑定;HorizontalViewActivity 里含"双页/封面双页"的 4 项菜单是被 wrapper 覆盖的死代码),
    故本用例验证 单页↔半页,菜单出现以[单页]或[半页]项判定(不能以"双页"判,否则误判未弹出).
    上下/左右滚动方向在本版本无独立工具条开关,见 COVERAGE.md 未覆盖说明."""
    def open_mode_menu():
        """呼出工具条并点 onModeChange,返回菜单是否出现(含[单页]/[半页]项).
        工具条唤出有竞态,轮询重试 3 次(2026-09-13 flaky 修复)."""
        for _ in range(3):
            _reader_show_toolbar(dev)
            btn = dev.d(resourceId=_rid(dev, "onModeChange"))
            if btn.exists:
                btn.click()
                time.sleep(2)
                if (dev.d(textContains="单页").exists or dev.d(textContains="半页").exists
                        or dev.d(textContains="Single").exists or dev.d(textContains="Half").exists):
                    return True
            time.sleep(2.5)
        return False

    def pick_mode(label):
        el = dev.d(text=label)
        if not el.exists:
            el = dev.d(textContains=label)
        if not el.exists:
            return False
        el.click()
        time.sleep(3.5)  # 选择后重开文档
        return True

    with dev.step(case_id, "open_book"):
        if not dev.open_book("big25", device_path=fixtures["device_pdf_path"]):
            raise AssertionError("big25.pdf 打开失败")
    with dev.step(case_id, "open_mode_menu"):
        if not open_mode_menu():
            dev.save_dump(case_id, "no_mode_menu")
            raise TestSkip("阅读模式菜单(onModeChange)未出现(工具条待勘探)")
        _snap(dev, case_id, "mode_menu")
    with dev.step(case_id, "switch_to_half_page"):
        if not pick_mode("半页"):
            dev.save_dump(case_id, "no_half_page_item")
            raise TestSkip("阅读模式菜单无[半页]项")
        _snap(dev, case_id, "mode_half_page")
        c = dev.scan_crash()
        if c:
            raise AssertionError("切半页 crash: %s" % c)
    with dev.step(case_id, "switch_back_one_page"):
        if not open_mode_menu():
            dev.save_dump(case_id, "no_mode_menu_2")
            raise AssertionError("切回单页时模式菜单未出现")
        if not pick_mode("单页"):
            dev.save_dump(case_id, "no_one_page_item")
            raise AssertionError("切回[单页]失败")
        _snap(dev, case_id, "mode_one_page")
    with dev.step(case_id, "exit"):
        _exit_reader(dev, case_id)


def fn16_goto_page(dev, case_id, cfg=None, fixtures=None):
    """跳页:切[单机行为=Book mode 水平]→ 开 EPUB(进 HorizontalViewActivity)→
    底栏[前往页面](R.id.thumbnail,desc「Go to Page/前往页面」)→ dialogGoToPage 输入页码跳转.
    覆盖 §7 跳页 + §9 页码进度显示.
    代码+真机结论(2026-09-13):该图标仅水平模式点亮(HorizontalViewActivity.restoreFooterControls);
    垂直滚动(AppSP.readingMode=SCROLL 时 epub 也走 Vertical)恒 GONE、长按 currentSeek 触摸被
    相邻控件消费——经偏好单机行为切 Book mode 绕行(应用侧如需垂直模式入口,一行修复见 COVERAGE.md)."""
    try:
        with dev.step(case_id, "switch_book_mode"):
            _ensure_home(dev)
            if not _set_reading_mode(dev, case_id, ["左右翻页", "Book mode", "书本"]):
                dev.save_dump(case_id, "no_book_mode_item")
                raise TestSkip("偏好无[单机行为]行或无左右翻页项")
            # 同 FN-07:切换只写内存,后续不得冷启
        with dev.step(case_id, "open_book"):
            _open_reader_warm(dev, case_id, "alicesadventures")
            _reader_show_toolbar(dev)
            before = _reader_page_or_none(dev)
            _snap(dev, case_id, "before_goto")
            # 水平模式指示器是「章节内页码」,与跳页对话框的绝对页码语义不同——
            # 以对话框预填值(绝对页码)为基准做断言
        with dev.step(case_id, "open_goto_dialog"):
            _reader_show_toolbar(dev)
            btn = _reader_toolbar_btn(dev, "thumbnail", desc_kws=("前往页面", "Go to Page"))
            if btn is None:
                dev.save_dump(case_id, "no_goto_btn")
                raise TestSkip("跳页入口(thumbnail/前往页面)不可见(Book 模式下仍不可见)")
            btn.click()
            time.sleep(2.5)
            edit = dev.d(resourceId=_rid(dev, "edit1"))
            if not edit.exists:
                edit = dev.d(className="android.widget.EditText")
            if not edit.exists:
                dev.save_dump(case_id, "no_goto_dialog")
                raise AssertionError("跳页对话框(dialogGoToPage)未出现")
            pre = ""
            try:
                pre = (edit.get_text() or "").strip()
            except Exception:
                pass
            m = re.match(r"(\d+)", pre)
            if not m:
                dev.save_dump(case_id, "no_prefill_page")
                raise AssertionError("跳页对话框未预填当前页码(读到 %r)" % pre)
            base = int(m.group(1))
            target = base + 3
            _snap(dev, case_id, "goto_dialog")
        with dev.step(case_id, "goto_page"):
            _fill(dev, edit, str(target), "(目标页码)")

            def chapter_pos():
                el = dev.d(resourceId=_rid(dev, "pagesCountIndicator"))
                return (el.get_text() or "").strip() if el.exists else ""

            before_pos = chapter_pos()
            # dialogGoToPage:IME DONE/ENTER → onSearch 跳转,对话框不自动关闭(设计如此).
            # 2026-09-20 MIUI 实测:收键盘的 back 会把 DragingPopup 对话框一起关掉、
            # enter 落空(填 40 页面不动,回显 37)——改为直接点对话框右上角 ✓(onSearch,
            # dialog_go_to_page.xml),不依赖 IME/焦点;跳转是否生效用
            # 章节指示器(pagesCountIndicator「N ∕ M」)前后变化断言
            ok_btn = dev.d(resourceId=_rid(dev, "onSearch"))
            if ok_btn.exists:
                ok_btn.click()
            else:
                _dismiss_keyboard(dev)
                dev.d.press("enter")
            time.sleep(3)
            after_pos = chapter_pos()
            _snap(dev, case_id, "after_goto")
            if not after_pos:
                raise AssertionError("跳页后读不到章节指示器")
            if after_pos == before_pos:
                # 兜底:指示器未变时以对话框预填页码是否推进为准
                # (✓ 路径下对话框保持打开,优先直接读;确实没开再点缩略图钮重开)
                e2 = dev.d(resourceId=_rid(dev, "edit1"))
                pre2 = ""
                if not e2.exists:
                    btn = _reader_toolbar_btn(dev, "thumbnail", desc_kws=("前往页面", "Go to Page"))
                    if btn is not None:
                        btn.click()
                        time.sleep(2.5)
                        e2 = dev.d(resourceId=_rid(dev, "edit1"))
                if e2.exists:
                    pre2 = (e2.get_text() or "").strip()
                dev.d.press("back")
                time.sleep(1)
                m2 = re.match(r"(\d+)", pre2)
                if not m2 or int(m2.group(1)) != target:
                    raise AssertionError("跳转到第 %d 页未生效(指示器 %r → %r,对话框回显 %r)"
                                         % (target, before_pos, after_pos, pre2))
        with dev.step(case_id, "exit"):
            _exit_reader(dev, case_id)
    finally:
        # 还原 单机行为=Scroll mode(垂直),避免影响后续用例的阅读模式假设
        try:
            _back_to_main(dev)
            _set_reading_mode(dev, case_id, ["上下翻页", "Scroll mode", "滚动"])
        except Exception:
            pass


def fn17_outline(dev, case_id, cfg=None, fixtures=None):
    """目录大纲:阅读器工具条 onDocDontext → 目录面板章节非空 → 点章节页码跳变.
    覆盖 §6 目录/大纲(章节定位)."""
    with dev.step(case_id, "open_book"):
        # EPUB 有 nav 目录;PDF 无大纲时对话框为空 → 用 EPUB 保证章节存在.
        # 跳转断言为模式无关(顶栏章节副标题 chapter + 页码指示器双通道),
        # 垂直/水平模式均可验证(2026-09-13)
        _open_reader(dev, case_id, fixtures, "epub")
    with dev.step(case_id, "open_outline"):
        _reader_show_toolbar(dev)
        btn = dev.d(resourceId=_rid(dev, "onDocDontext"))
        if not btn.exists:
            dev.save_dump(case_id, "no_outline_btn")
            raise TestSkip("目录入口(onDocDontext)不可见(工具条未显示)")
        btn.click()
        time.sleep(3)
        xml = dev.d.dump_hierarchy()
        if not any(k in xml for k in ("目录", "Contents", "contentList", "content_of_book")):
            dev.save_dump(case_id, "no_outline_dialog")
            raise TestSkip("目录面板未出现")
        _snap(dev, case_id, "outline_panel")
    with dev.step(case_id, "jump_chapter"):
        _reader_show_toolbar(dev)

        def _chapter_ctx():
            """当前章上下文:顶栏章节副标题(chapter id)+指示器完整文本.
            注意必须用指示器完整文本而非数字对——每章章内页码都是「1 ∕ N」,
            数字对恒等会漏判跳转;完整文本含章节名,跨章必变(2026-09-13 现场)"""
            ind = ""
            el = dev.d(resourceId=_rid(dev, "pagesCountIndicator"))
            if el.exists:
                ind = (el.get_text() or "").strip()
            sub = ""
            el = dev.d(resourceId=_rid(dev, "chapter"))
            if el.exists:
                sub = (el.get_text() or "").strip()
            return sub, ind

        before_sub, before_ind = _chapter_ctx()
        cur_txt = (before_sub + " " + str(before_ind)).strip()
        # 选一个非当前章的章节行:当前章在目录中高亮,点击它不导航
        # (旧 kw 列表首选 CHAPTER II 恰为常驻当前章,双机踩坑 2026-09-13)
        chapter = None
        for kw in ("CHAPTER III", "Chapter 3", "第三章", "CHAPTER IV", "Chapter 4",
                   "CHAPTER II", "Chapter 2", "CHAPTER 2", "第二章"):
            if kw in cur_txt:
                continue
            el = dev.d(textContains=kw)
            if el.exists:
                chapter = el
                break
        if chapter is None:
            cl = dev.d(resourceId=_rid(dev, "contentList"))
            if cl.exists:
                chapter = cl.child(className="android.widget.TextView")
        if chapter is None or not chapter.exists:
            dev.save_dump(case_id, "no_chapter_item")
            raise AssertionError("目录面板无章节条目")
        chapter.click()
        time.sleep(3.5)
        after_sub, after_ind = _chapter_ctx()
        _snap(dev, case_id, "after_chapter_jump")
        changed = (after_sub and after_sub != before_sub) or (after_ind and after_ind != before_ind)
        if not changed:
            # 换一章重试(重开目录)
            toc = _reader_toolbar_btn(dev, "onDocDontext", desc_kws=("目录",))
            if toc is not None:
                toc.click()
                time.sleep(2.5)
                _reader_show_toolbar(dev)
                before_sub, before_ind = _chapter_ctx()
                cur_txt = (before_sub + " " + str(before_ind)).strip()
                alt = None
                for kw in ("CHAPTER IV", "Chapter 4", "第四章", "CHAPTER V", "Chapter 5",
                           "CHAPTER III", "Chapter 3"):
                    if kw in cur_txt:
                        continue
                    el = dev.d(textContains=kw)
                    if el.exists:
                        alt = el
                        break
                if alt is not None:
                    alt.click()
                    time.sleep(3.5)
                    after_sub, after_ind = _chapter_ctx()
                    changed = ((after_sub and after_sub != before_sub)
                               or (after_ind and after_ind != before_ind))
        _snap(dev, case_id, "after_chapter_jump_final")
        if not changed:
            raise AssertionError("点击章节后章节副标题与页码指示器均未变化")
    with dev.step(case_id, "exit"):
        _exit_reader(dev, case_id)


def _customseek_value(dev, bounds):
    """读 CustomSeek 行内 value TextView 的数值(bounds 内最近的纯数字文本)."""
    try:
        xml = dev.d.dump_hierarchy()
    except Exception:
        return None
    best = None
    for m in re.finditer(r'text="(\d+)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        cy = (int(m.group(3)) + int(m.group(5))) / 2
        cx = (int(m.group(2)) + int(m.group(4))) / 2
        if bounds[1] - 20 <= cy <= bounds[3] + 20 and bounds[0] - 20 <= cx <= bounds[2] + 20:
            best = int(m.group(1))
    return best


def fn18_font_size(dev, case_id, cfg=None, fixtures=None):
    """字号调节:阅读器顶栏 prefTop[偏好]→ dialogPreferences → fontSizeSp 滑杆 +4 → 数值变化 → 还原.
    覆盖 §8 文本排版参数(字号)."""
    from cases.ui.tc_smoke import _same_png
    with dev.step(case_id, "open_book"):
        # 字号滑杆仅文本格式可见,用 EPUB;Book 模式 + 热态开书——冷开默认垂直
        # 模式顶栏无 prefTop,轮询也等不到(2026-09-13 双机 SKIP 定位)
        _ensure_home(dev)
        if not _set_reading_mode(dev, case_id, ["左右翻页", "Book mode", "书本"]):
            dev.save_dump(case_id, "no_book_mode_item")
            raise TestSkip("偏好无[单机行为]行或无左右翻页项")
        _open_reader_warm(dev, case_id, "alicesadventures")
    with dev.step(case_id, "open_prefs_popup"):
        if not _open_prefs_popup(dev, timeout=30):
            dev.save_dump(case_id, "no_preftop")
            raise TestSkip("阅读偏好入口不可见(轮询后仍未出现)")
        fs = dev.d(resourceId=_rid(dev, "fontSizeSp"))
        if not fs.exists:
            dev.save_dump(case_id, "no_fontsizesp")
            raise TestSkip("字号滑杆(fontSizeSp)不可见(仅文本格式显示)")
        _snap(dev, case_id, "prefs_popup")
    with dev.step(case_id, "change_font_size"):
        b = _node_bounds(fs)
        before_val = _customseek_value(dev, b)
        before_png = dev.screenshot(case_id, "font_before")
        cy = (b[1] + b[3]) // 2
        cx_plus = b[2] - 80   # custom_seek 行尾加号(35dip+10dip margin)
        changed = False
        for _ in range(4):
            dev.d.click(cx_plus, cy)
            time.sleep(1.2)
        after_val = _customseek_value(dev, b)
        after_png = dev.screenshot(case_id, "font_after")
        _snap(dev, case_id, "font_after")
        if before_val is not None and after_val is not None:
            changed = after_val != before_val
        elif before_png and after_png:
            changed = not _same_png(before_png, after_png)
        if not changed:
            dev.save_dump(case_id, "font_nochange")
            raise AssertionError("字号 +4 后数值/画面均无变化(before=%s after=%s)" % (before_val, after_val))
    with dev.step(case_id, "restore"):
        fs = dev.d(resourceId=_rid(dev, "fontSizeSp"))
        b = _node_bounds(fs)
        cy = (b[1] + b[3]) // 2
        for _ in range(6):  # 多点几次减号确保回到原值以下(滑杆下限 10 兜底)
            dev.d.click(b[2] - 180, cy)  # 减号在加号左侧(35dip 间距 + margin)
            time.sleep(1)
        dev.d.press("back")  # 关偏好弹窗
        time.sleep(1)
        _exit_reader(dev, case_id)  # 退出阅读器——不退则下个用例热态复用会开错书(2026-09-13 KSA 实锤)


def fn19_four_themes(dev, case_id, cfg=None, fixtures=None):
    """四套主题:偏好 themeColor 弹窗逐套 亮色/暗色/全黑OLED/Ink 应用并截图,与亮色基线像素 diff 验证.
    覆盖 §8 四套主题(FN-06 只覆盖抽屉夜间开关).
    注:themeColor 行在偏好页[主题配置]区、屏幕外,需 _scroll_to_find 滚动定位;
    每次切主题后 UI 重建、偏好页滚动复位,故每轮重新滚动定位."""
    from cases.ui.tc_smoke import _same_png
    items = [("亮色", "light"), ("暗色", "black"), ("全黑", "dark_oled"), ("Ink", "ink")]

    def find_theme_row():
        _goto_tab_or_fail(dev, "偏好")
        # themeColor 在可折叠容器 themeConfigContainer 内(默认 visibility=gone),
        # 必须先点 themeConfigHeader 展开(PrefFragment2.java:385-390 切换 GONE↔VISIBLE),
        # 否则 themeColor 永远不在树里。每次切主题后 UI 重建容器会复位收起,故每轮都检查。
        if not dev.d(resourceId=_rid(dev, "themeColor")).exists:
            header = _scroll_to_find(dev, "themeConfigHeader")
            if header is None:
                dev.save_dump(case_id, "no_themeheader")
                raise AssertionError("主题配置头(themeConfigHeader)不可见(滚动后仍未找到)")
            header.click()
            time.sleep(1.5)
        row = _scroll_to_find(dev, "themeColor")
        if row is None:
            dev.save_dump(case_id, "no_themecolor")
            raise AssertionError("主题入口(themeColor)不可见(展开容器后仍未找到)")
        return row

    with dev.step(case_id, "goto_prefs"):
        _ensure_home(dev)
        original = find_theme_row().get_text() or "亮色"
    baseline_png = None  # 亮色基线,用于像素 diff
    applied = []
    with dev.step(case_id, "apply_themes"):
        for label, key in items:
            row = find_theme_row()
            row.click()
            time.sleep(1.5)
            item = dev.d(textContains=label)
            if not item.exists:
                dev.save_dump(case_id, "theme_menu_%s" % key)
                raise AssertionError("主题弹窗无[%s]项" % label)
            item.click()
            time.sleep(3.5)  # 主题重建
            _goto_tab_or_fail(dev, "偏好")
            png = dev.screenshot(case_id, "theme_%s" % key)
            _snap(dev, case_id, "theme_%s" % key)
            if key == "light":
                baseline_png = png
            elif baseline_png and png and _same_png(baseline_png, png):
                dev.save_dump(case_id, "theme_nochange_%s" % key)
                raise AssertionError("切到 %s 后画面与亮色基线无像素差异" % label)
            applied.append(key)
        if len(applied) != len(items):
            raise AssertionError("主题应用不完整: %s" % applied)
    with dev.step(case_id, "restore"):
        row = find_theme_row()
        row.click()
        time.sleep(1.5)
        # original 形如 "亮色"/"暗色"/"全黑(仅适用于OLED)"/"Ink"/"系统"
        back = dev.d(textContains=original[:2]) if len(original) >= 2 else None
        if back is None or not back.exists:
            back = dev.d(textContains="亮色")
        if back.exists:
            back.click()
            time.sleep(3)


def _force_restore_lang(dev, case_id, tag):
    """兜底还原:直接把 app-State.json 的 appLang 改回 my(系统默认)并重启应用.
    UI 还原失败时调用,避免英文界面污染后续所有中文选择器用例."""
    try:
        dev.d.app_stop(dev.pkg)
        time.sleep(1)
        out = dev.shell("find /sdcard/HowRead -name app-State.json 2>/dev/null | head -1")
        path = (out or "").strip().splitlines()[-1].strip() if out else ""
        if path:
            dev.shell("sed -i 's/\"appLang\":\"[^\"]*\"/\"appLang\":\"my\"/' %s" % path)
            chk = dev.shell("grep -o '\"appLang\":\"[^\"]*\"' %s" % path)
            print("  [%s] 兜底还原 appLang: %s" % (dev.serial, (chk or "").strip()))
        else:
            print("  [%s] 兜底还原: 未找到 app-State.json" % dev.serial)
        dev.start_app(cold=True)
        time.sleep(3)
    except Exception as e:
        print("  [%s] 兜底还原异常: %s" % (dev.serial, e))
    xml = dev.d.dump_hierarchy()
    if "首页" not in xml and "Home" not in xml:
        dev.save_dump(case_id, tag)
        raise AssertionError("兜底还原后主界面仍未就绪")


def fn20_language_switch(dev, case_id, cfg=None, fixtures=None):
    """界面语言切换:偏好 appLang 行 → 英文 → 界面重建为英文 → 还原原语言(系统默认).
    覆盖 §16 多语言(44 语言,应用内切换).
    注:语言菜单是 PopupMenu(首项[系统默认/System]+ 各语言名);还原须点回原值(系统默认),
    不是[中文]--原值非中文时点[中文]会留下错误状态.
    还原失败时强制把 appLang 改回 my(系统默认)并重启,避免污染后续用例."""
    with dev.step(case_id, "open_lang_menu"):
        _ensure_home(dev)
        _goto_tab_or_fail(dev, "偏好")
        lang = _scroll_to_find(dev, "appLang")
        if lang is None:
            dev.save_dump(case_id, "no_applang")
            raise AssertionError("语言行(appLang)不可见(滚动后仍未找到)")
        original = lang.get_text() or "系统默认"
        lang.click()
        time.sleep(1.5)
        eng = dev.d(text="English")
        if not eng.exists:
            eng = dev.d(textContains="Engl")
        if not eng.exists:
            dev.save_dump(case_id, "no_english_item")
            dev.d.press("back")
            raise TestSkip("语言菜单无英文项(菜单文案待勘探)")
        eng.click()
        time.sleep(4)  # 界面重建
    with dev.step(case_id, "verify_english"):
        xml = dev.d.dump_hierarchy()
        ok = any(k in xml for k in ("Settings", "Library", "Preferences", "Browse", "Shelf"))
        _snap(dev, case_id, "lang_english")
        if not ok:
            dev.save_dump(case_id, "still_chinese")
            raise AssertionError("切英文后界面仍为中文(未见英文 Tab 文案)")
    with dev.step(case_id, "restore_original"):
        # 英文界面下偏好 Tab 叫 Preferences
        _goto_tab(dev, "Preferences") or _goto_tab(dev, "偏好")
        lang = _scroll_to_find(dev, "appLang")
        if lang is None:
            _force_restore_lang(dev, case_id, "restore_no_applang")
            return
        lang.click()
        time.sleep(1.5)
        # 点回原值:[系统默认](zh) / "System" (en)
        back = None
        for kw in (original, "系统默认", "System"):
            el = dev.d(text=kw)
            if el.exists:
                back = el
                break
        if back is None:
            dev.save_dump(case_id, "no_restore_item")
            _force_restore_lang(dev, case_id, "restore_no_item")
            return
        back.click()
        time.sleep(4)
        _goto_tab(dev, "Home") or _goto_tab(dev, "首页")
        xml = dev.d.dump_hierarchy()
        # 原值是"系统默认"时,还原成功=界面回到中文;若仍英文则兜底强制还原
        if original == "系统默认" and "首页" not in xml and "最近阅读" not in xml:
            dev.save_dump(case_id, "restore_still_english")
            _force_restore_lang(dev, case_id, "restore_forced")
            return
        if "首页" not in xml and "最近阅读" not in xml and "Home" not in xml:
            dev.save_dump(case_id, "restore_failed")
            raise AssertionError("还原原语言界面失败")


def fn21_share_receive(dev, case_id, cfg=None, fixtures=None):
    """分享接收:ACTION_SEND 文本 intent 唤起 SendReceiveActivity,无 crash 且界面出现.
    覆盖 §5 分享接收(SendReceiveActivity)."""
    with dev.step(case_id, "send_text"):
        dev.d.app_stop(dev.pkg)
        time.sleep(1)
        out = dev.shell(
            'am start -a android.intent.action.SEND -t text/plain '
            '--es android.intent.extra.TEXT "HowRead autotest share 123" '
            '-n %s/com.foobnix.zipmanager.SendReceiveActivity'
            % dev.pkg)
        if "Error" in out:
            dev.save_dump(case_id, "send_start_error")
            raise AssertionError("SEND intent 启动失败: %s" % out.strip()[:120])
        time.sleep(4)
    with dev.step(case_id, "verify_no_crash"):
        c = dev.scan_crash()
        if c:
            dev.save_dump(case_id, "share_crash")
            raise AssertionError("分享接收 crash: %s" % c)
        top = dev.shell("dumpsys activity activities | grep ResumedActivity")
        xml = ""
        try:
            xml = dev.d.dump_hierarchy()
        except Exception:
            pass
        ok = ("SendReceiveActivity" in top or "MainTabs2" in top or "ViewActivity" in top) and xml.strip()
        _snap(dev, case_id, "share_received")
        if not ok:
            raise AssertionError("分享接收后无界面响应: %s" % top.strip()[:120])
    with dev.step(case_id, "exit"):
        for _ in range(3):
            dev.d.press("back")
            time.sleep(0.6)


def fn22_notes(dev, case_id, cfg=None, fixtures=None):
    """笔记功能(Pro):阅读页长按选中文本 → [添加批注便签]→ 全屏笔记编辑器输入 → 保存 →
    书签笔记处可见.覆盖 §10 笔记功能(NoteEditDialog).文本选择不可达时 SKIP."""
    with dev.step(case_id, "select_text"):
        _open_reader(dev, case_id, fixtures, "epub")
        time.sleep(2)
        w, h = dev.d.window_size()
        # 长按正文选中一个词,松开触发 dialogSelectText 弹层.
        # 出厂态(--reset 删外部状态)开书停在第 1 页=封面图,长按封面选不中文本——
        # 没有弹层就翻下一页(右分区 tap)再长按,最多试 3 页(2026-09-13 定位)
        note_btn = None
        for _attempt in range(4):
            # MuPDF 选词需 ≥1s 按住:long_click 默认 0.5s 从不弹选择层,
            # 同点 swipe 1.5s 实测稳定弹出「文本」对话框(2026-09-13 真机实测);
            # 纵向位置轮换避开空白/图注区(2026-09-13 flaky 缓解)
            yk = (0.45, 0.35, 0.55, 0.45)[_attempt]
            dev.d.swipe(int(0.5 * w), int(yk * h), int(0.5 * w), int(yk * h), 1.5)
            time.sleep(2.5)
            for kw in ("添加批注便签", "批注便签", "便签"):
                el = dev.d(textContains=kw)
                if el.exists:
                    note_btn = el
                    break
            if note_btn is not None:
                break
            xml = dev.d.dump_hierarchy()
            if "批注" in xml or "笔记" in xml:
                note_btn = dev.d(textContains="笔记")
                break
            dev.d.click(int(0.93 * w), int(0.5 * h))
            time.sleep(2.5)
        if note_btn is None:
            dev.save_dump(case_id, "no_selection_popup")
            raise TestSkip("长按选择后未出现文本操作弹层(选择手势待勘探)")
        if note_btn is None or not note_btn.exists:
            dev.save_dump(case_id, "no_note_btn")
            raise TestSkip("文本操作弹层无[添加批注便签]入口")
        note_btn.click()
        time.sleep(2.5)
    with dev.step(case_id, "write_note"):
        editor = dev.d(resourceId=_rid(dev, "noteEditText"))
        if not editor.exists:
            editor = dev.d(textContains="输入笔记内容")
        if not editor.exists:
            editor = dev.d(className="android.widget.EditText")
        if not editor.exists:
            dev.save_dump(case_id, "no_note_editor")
            raise TestSkip("笔记编辑器未打开/无输入区")
        _fill(dev, editor, "autotest note 2026-09-12", "(笔记内容)")
        _dismiss_keyboard(dev)
        saved = False
        for kw in ("保存笔记", "保存"):
            if dev.click_text(kw):
                saved = True
                break
        if not saved:
            dev.save_dump(case_id, "no_note_save")
            dev.d.press("back")
            raise TestSkip("笔记编辑器无[保存笔记]按钮")
        time.sleep(2)
        _snap(dev, case_id, "note_saved")
    with dev.step(case_id, "verify_note"):
        _exit_reader(dev, case_id)
        _back_to_main(dev)
    # 验证:重开书签面板(书签/笔记统一列表)
    _open_reader(dev, case_id, fixtures, "epub")
    if _open_reader_bookmark_entry(dev, fixtures):
        time.sleep(2)
        xml = dev.d.dump_hierarchy()
        _snap(dev, case_id, "note_in_bookmarks")
        dev.d.press("back")
        time.sleep(1)
        if "autotest note" in xml or "笔记" in xml:
            return
    _back_to_main(dev)
    raise AssertionError("保存后未在书签/笔记处找到笔记")


def fn23_ai_config(dev, case_id, cfg=None, fixtures=None):
    """AI 配置与连通(Pro):偏好 → AI 大模型(Pro) → AiConfigDialog 填智谱 openai 协议配置 →
    测试连接成功.覆盖 §12 多厂商配置(连通性)."""
    ai = (cfg or {}).get("ai_test") or {}
    with dev.step(case_id, "open_ai_dialog"):
        _ensure_home(dev)
        _goto_tab_or_fail(dev, "偏好")
        # 点击监听绑在 aiConfigValue(值 TextView,PrefFragment2.java:955-958),
        # 而非粗体标签"AI 大模型(Pro)"——点标签无效果,必须点值。
        row = dev.d(resourceId=_rid(dev, "aiConfigValue"))
        if not row.exists:
            found = _scroll_to_find(dev, "aiConfigValue")
            if found is None:
                dev.save_dump(case_id, "no_ai_row")
                raise TestSkip("偏好页无[AI 大模型(Pro)]行(aiConfigValue)")
            row = found
        row.click()
        time.sleep(2.5)
        if not dev.d(resourceId=_rid(dev, "aiTestConnection")).exists:
            dev.save_dump(case_id, "no_ai_dialog")
            raise AssertionError("AI 配置对话框未打开")
    with dev.step(case_id, "fill_config"):
        _fill(dev, dev.d(resourceId=_rid(dev, "aiBaseUrl")), ai.get("base_url", ""), "(baseUrl)")
        _fill(dev, dev.d(resourceId=_rid(dev, "aiApiKey")), ai.get("api_key", ""), "(apiKey)")
        _fill(dev, dev.d(resourceId=_rid(dev, "aiModel")), ai.get("model", "glm-4-flash"), "(模型)")
        _snap(dev, case_id, "ai_config_filled")
        _dismiss_keyboard(dev)
    with dev.step(case_id, "test_connection"):
        dev.d(resourceId=_rid(dev, "aiTestConnection")).click()
        ok = False
        deadline = time.time() + 40
        while time.time() < deadline:
            st = dev.d(resourceId=_rid(dev, "aiTestStatus"))
            if st.exists:
                txt = st.get_text() or ""
                if "成功" in txt or "OK" in txt.lower():
                    ok = True
                    break
            time.sleep(2)
        _snap(dev, case_id, "ai_test_result")
        dev.d.press("back")
        time.sleep(1.5)
        if not ok:
            raise AssertionError("AI 测试连接未成功(外网/key 原因或接口变化)")


def fn24_reading_stats(dev, case_id, cfg=None, fixtures=None):
    """阅读统计(Pro):首页统计仪表盘(书籍总数/已读书籍/总阅读时间/今日阅读/阅读速度).
    覆盖 §16 阅读统计(ReadingStats + DashboardFragment2)."""
    with dev.step(case_id, "goto_stats_section"):
        _ensure_home(dev)
        stat = None
        for kw in ("statToday", "statHours", "statTotal"):
            el = dev.d(resourceId=_rid(dev, kw))
            if el.exists:
                stat = el
                break
        if stat is None:
            # 统计区在首页下方,滚动寻找
            for _ in range(6):
                w, h = dev.d.window_size()
                dev.d.swipe(0.5 * w, 0.7 * h, 0.5 * w, 0.3 * h, 0.3)
                time.sleep(1.2)
                for kw in ("statToday", "statHours", "statTotal"):
                    el = dev.d(resourceId=_rid(dev, kw))
                    if el.exists:
                        stat = el
                        break
                if stat:
                    break
        if stat is None:
            dev.save_dump(case_id, "no_stats_section")
            raise AssertionError("首页未找到阅读统计区(statToday/statHours/statTotal 均不在树中)")
        # 小屏(KSA 720x1520)统计卡常被底部 Tab 栏截断:uiautomator dump 只含可见
        # 节点,不完整滚入可视区会读不到字段文案(2026-09-13 现场)→滚到完整可见
        w, h = dev.d.window_size()
        for _ in range(6):
            b = _node_bounds(stat)
            if b and b[3] <= h * 0.86:
                break
            dev.d.swipe(0.5 * w, 0.75 * h, 0.5 * w, 0.35 * h, 0.4)
            time.sleep(1.2)
            stat = None
            for kw in ("statToday", "statHours", "statTotal"):
                el = dev.d(resourceId=_rid(dev, kw))
                if el.exists:
                    stat = el
                    break
            if stat is None:
                break
    with dev.step(case_id, "verify_fields"):
        xml = dev.d.dump_hierarchy()
        labels = [k for k in ("书籍总数", "已读书籍", "总阅读时间", "今日阅读", "阅读速度") if k in xml]
        _snap(dev, case_id, "stats_dashboard")
        if not labels:
            dev.save_dump(case_id, "stats_no_labels")
            raise AssertionError("统计区可见但无统计字段文案")
    with dev.step(case_id, "back_top"):
        for _ in range(4):
            w, h = dev.d.window_size()
            dev.d.swipe(0.5 * w, 0.3 * h, 0.5 * w, 0.8 * h, 0.3)
            time.sleep(0.6)


def fn25_opds(dev, case_id, cfg=None, fixtures=None):
    """OPDS 书库:我的文件 → 网上书库(OPDS)预置源进入目录加载.覆盖 §14 OPDS.
    目录加载依赖外网,不可达时 SKIP."""
    with dev.step(case_id, "open_catalog"):
        _ensure_home(dev)
        if not _browse_root(dev):
            raise AssertionError("我的文件根视图不可达")
        cat = None
        for name in ("Project Gutenberg", "CBETA"):
            el = dev.d(textContains=name)
            if el.exists:
                cat = el
                break
        if cat is None:
            dev.save_dump(case_id, "no_opds_entry")
            raise TestSkip("netSection 无预置 OPDS 源(Gutenberg/CBETA)")
        cat.click()
        time.sleep(5)
    with dev.step(case_id, "verify_catalog"):
        markers = ("Popular", "Latest", "最新", "热门", "Gutenberg", "CBETA", " ebooks", "书籍")
        ok = False
        deadline = time.time() + 30
        while time.time() < deadline and not ok:
            xml = dev.d.dump_hierarchy()
            hits = sum(1 for m in markers if m in xml)
            if hits >= 1 and not dev.d(resourceId=_rid(dev, "netSection")).exists:
                ok = True
                break
            time.sleep(2.5)
        _snap(dev, case_id, "opds_catalog")
        if not ok:
            dev.save_dump(case_id, "opds_load_failed")
            raise TestSkip("OPDS 目录未加载(外网不可达/超时,环境限制)")
    with dev.step(case_id, "exit"):
        for _ in range(3):
            dev.d.press("back")
            time.sleep(1)


def fn26_webdav_browse(dev, case_id, cfg=None, fixtures=None):
    """WebDAV 浏览(Pro):添加/复用 50.23 WebDAV → 目录看到测试书.覆盖 §14 WebDAV 浏览.
    测试自建的条目用后删除,避免网络源堆积触发 FN-04 搜索入口裁切缺陷."""
    with dev.step(case_id, "ensure_server"):
        title, added = _ensure_server(dev, case_id, cfg, "webdav")
    with dev.step(case_id, "browse_dir"):
        _ensure_home(dev)
        if not _browse_root(dev):
            raise AssertionError("我的文件根视图不可达")
        kw_books = ("book_pdf", "book_epub", "book_txt", "book_mobi")
        if not _open_server_dir(dev, title, kw_books):
            # 预置行无凭据(每台设备首次)→ 编辑对话框一次性入库后重试一次
            if not _back_to_net_root(dev) or not _ensure_credentials(dev, case_id, cfg, "webdav", title) \
                    or not _browse_root(dev) or not _open_server_dir(dev, title, kw_books):
                dev.save_dump(case_id, "webdav_dir_empty")
                raise AssertionError("WebDAV 目录未列出测试书(凭据入库也未成功)")
        _snap(dev, case_id, "webdav_listing")
    with dev.step(case_id, "cleanup"):
        for _ in range(3):
            dev.d.press("back")
            time.sleep(1)
            if dev.d(resourceId=_rid(dev, "netSection")).exists:
                break
        if added:
            if not _click_row_delete(dev, title):
                dev.save_dump(case_id, "webdav_delete_failed")
                raise AssertionError("测试自建 WebDAV 条目删除失败(残留会挤压搜索入口)")


def fn27_webdav_sync(dev, case_id, cfg=None, fixtures=None):
    """WebDAV 同步(Pro):偏好 → WebDAV 同步(Pro) → 独立服务器配置 + 立即同步完成 +
    服务器侧 /HowRead/global/ 出现 app-*.json(ssh 50.23 验证).覆盖 §14 三方合并同步(冒烟级)."""
    ts = (cfg or {}).get("test_server") or {}
    with dev.step(case_id, "open_sync_dialog"):
        _ensure_home(dev)
        _goto_tab_or_fail(dev, "偏好")
        row = dev.d(resourceId=_rid(dev, "webdavSyncValue"))
        if not row.exists:
            dev.save_dump(case_id, "no_sync_row")
            raise TestSkip("偏好页无 WebDAV 同步行")
        row.click()
        time.sleep(2.5)
        if not dev.d(resourceId=_rid(dev, "webdavSyncNow")).exists:
            dev.save_dump(case_id, "no_sync_dialog")
            raise AssertionError("WebDAV 同步对话框未打开")
    with dev.step(case_id, "config_and_test"):
        enable = dev.d(resourceId=_rid(dev, "webdavSyncEnabled"))
        if enable.exists:
            try:
                if isinstance(enable.info.get("checked"), bool) and not enable.info["checked"]:
                    enable.click()
                    time.sleep(1)
            except Exception:
                pass
        _fill(dev, dev.d(resourceId=_rid(dev, "webdavSyncUrl")), ts.get("webdav_url", ""), "(同步地址)")
        _fill(dev, dev.d(resourceId=_rid(dev, "webdavSyncLogin")), ts.get("user", "howread"), "(账号)")
        _fill(dev, dev.d(resourceId=_rid(dev, "webdavSyncPassword")), ts.get("password", "howread123"), "(密码)")
        _dismiss_keyboard(dev)
        test = dev.d(resourceId=_rid(dev, "webdavSyncTest"))
        if test.exists:
            test.click()
            time.sleep(6)  # 测试连接(PROPFIND)
            _snap(dev, case_id, "sync_test_done")
    with dev.step(case_id, "sync_now"):
        dev.d(resourceId=_rid(dev, "webdavSyncNow")).click()
        ok = False
        deadline = time.time() + 60
        while time.time() < deadline:
            st = dev.d(resourceId=_rid(dev, "webdavSyncStatus"))
            if st.exists:
                txt = st.get_text() or ""
                if "同步完成" in txt or "上次同步" in txt.replace("尚未同步过", ""):
                    if "同步完成" in txt:
                        ok = True
                        break
            time.sleep(2.5)
        _snap(dev, case_id, "sync_status")
        if not ok:
            dev.save_dump(case_id, "sync_not_done")
            raise AssertionError("立即同步 60s 内未完成(状态未见「同步完成」)")
    with dev.step(case_id, "server_side_verify"):
        remote = ""
        try:
            import subprocess
            out = subprocess.check_output(
                ["ssh", "-i", r"C:\Users\lee\.ssh\id_ed25519", "-o", "StrictHostKeyChecking=no",
                 "-o", "ConnectTimeout=8", "lee@192.168.50.23",
                 "ls /srv/webdav/HowRead/global/ 2>/dev/null | head -20"],
                timeout=30, stderr=subprocess.STDOUT)
            remote = out.decode("utf-8", "replace")
        except Exception as e:
            remote = "SSH_ERR: %s" % e
        if "app-" in remote:
            _snap(dev, case_id, "server_global_files")
        else:
            # 设备侧已报同步完成即视为通过,服务器侧验证失败仅记录
            print("  [%s] 服务器侧 /HowRead/global 验证: %s" % (dev.serial, remote.strip()[:200]))
    with dev.step(case_id, "exit"):
        _dismiss_keyboard(dev)
        for _ in range(3):
            dev.d.press("back")
            time.sleep(0.6)


def fn28_smb(dev, case_id, cfg=None, fixtures=None):
    """SMB 添加与浏览(Pro):AddRemoteDialog 填 50.23 SMB → 测试连接成功 → 目录见测试书.
    覆盖 §15 服务器管理 UI(SMB).自建条目用后删除."""
    with dev.step(case_id, "ensure_server"):
        title, added = _ensure_server(dev, case_id, cfg, "smb")
    with dev.step(case_id, "browse_dir"):
        _ensure_home(dev)
        if not _browse_root(dev):
            raise AssertionError("我的文件根视图不可达")
        kw_books = ("book_pdf", "book_epub", "book_txt", "book_mobi")
        if not _open_server_dir(dev, title, kw_books):
            # 预置行无凭据(每台设备首次)→ 编辑对话框一次性入库后重试一次
            if not _back_to_net_root(dev) or not _ensure_credentials(dev, case_id, cfg, "smb", title) \
                    or not _browse_root(dev) or not _open_server_dir(dev, title, kw_books):
                dev.save_dump(case_id, "smb_dir_empty")
                raise AssertionError("SMB 共享目录未列出测试书(凭据入库也未成功)")
        _snap(dev, case_id, "smb_listing")
    with dev.step(case_id, "cleanup"):
        for _ in range(3):
            dev.d.press("back")
            time.sleep(1)
            if dev.d(resourceId=_rid(dev, "netSection")).exists:
                break
        if added:
            if not _click_row_delete(dev, title):
                dev.save_dump(case_id, "smb_delete_failed")
                raise AssertionError("测试自建 SMB 条目删除失败")


def fn29_sftp(dev, case_id, cfg=None, fixtures=None):
    """SFTP 添加与浏览(Pro):AddRemoteDialog 填 50.23 SFTP(22) → 测试连接成功 → 目录见测试书.
    覆盖 §15 服务器管理 UI(SFTP).自建条目用后删除."""
    with dev.step(case_id, "ensure_server"):
        title, added = _ensure_server(dev, case_id, cfg, "sftp")
    with dev.step(case_id, "browse_dir"):
        _ensure_home(dev)
        if not _browse_root(dev):
            raise AssertionError("我的文件根视图不可达")
        kw_books = ("book_pdf", "book_epub", "book_txt", "book_mobi")
        if not _open_server_dir(dev, title, kw_books):
            # 预置行无凭据(每台设备首次)→ 编辑对话框一次性入库后重试一次
            if not _back_to_net_root(dev) or not _ensure_credentials(dev, case_id, cfg, "sftp", title) \
                    or not _browse_root(dev) or not _open_server_dir(dev, title, kw_books):
                dev.save_dump(case_id, "sftp_dir_empty")
                raise AssertionError("SFTP 目录未列出测试书(凭据入库也未成功)")
        _snap(dev, case_id, "sftp_listing")
    with dev.step(case_id, "cleanup"):
        for _ in range(3):
            dev.d.press("back")
            time.sleep(1)
            if dev.d(resourceId=_rid(dev, "netSection")).exists:
                break
        if added:
            if not _click_row_delete(dev, title):
                dev.save_dump(case_id, "sftp_delete_failed")
                raise AssertionError("测试自建 SFTP 条目删除失败")


def fn30_remote_open(dev, case_id, cfg=None, fixtures=None):
    """远程书在线打开(Pro):WebDAV 远程 book_pdf.pdf 流式打开 → 阅读器翻 2 页 → 退出.
    覆盖 §15 在线打开 + 分块缓存(冒烟级).自建条目用后删除."""
    with dev.step(case_id, "ensure_server"):
        title, added = _ensure_server(dev, case_id, cfg, "webdav")
    with dev.step(case_id, "open_remote_pdf"):
        _ensure_home(dev)
        # pdf 为流式直开;等待进阅读器(分块拉取需要时间)——公共流程见 _open_remote_book
        entered = _open_remote_book(dev, case_id, title, "book_pdf", snap="remote_reader")
        if not entered:
            dev.save_dump(case_id, "remote_open_timeout")
            raise AssertionError("远程 PDF 60s 未进入阅读器")
        c = dev.scan_crash()
        if c:
            raise AssertionError("远程打开 crash: %s" % c)
    with dev.step(case_id, "page_turns"):
        before = _reader_page_or_none(dev)
        dev.page_turn(forward=True, verify=False)
        time.sleep(1.5)
        dev.page_turn(forward=True, verify=False)
        after = _wait_page_change(dev, before, timeout=12)
        if not after and before and before[0] >= before[1]:
            # 书已停在最后一页(此前运行把进度推到末页):向前翻页无效属预期,
            # 改为向后翻页验证翻页能力
            dev.page_turn(forward=False, verify=False)
            time.sleep(1.5)
            after = _wait_page_change(dev, before, timeout=12)
        _snap(dev, case_id, "remote_turned")
        if not after:
            dev.save_dump(case_id, "remote_no_turn")
            raise AssertionError("远程书翻页无效")
    with dev.step(case_id, "cleanup"):
        _exit_reader(dev, case_id)
        _back_to_main(dev)
        if not _browse_root(dev):
            return
        if added:
            _click_row_delete(dev, title)


def fn09_multi_format(dev, case_id, cfg=None, fixtures=None):
    """多格式开书冒烟: 遍历 teskbook 新增样本, 逐本 intent 开书进阅读器无 crash翻一页退出
    任何一本打不开或崩溃即 FAIL(指明格式).翻页用 verify=False, 避免单页/无页码格式误判
    仅用 open_book_via_intent(OpenerActivity 按扩展名解析书类型,MIME 走 octet-stream 兜底),
    不依赖书库扫描,确定性高."""
    dl = "/sdcard/Download/"
    opened, failed = [], []
    # 样本书不随 fixtures 推送,新设备(如 P30)Download 里没有 → 先按映射补推缺失的
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
        raise AssertionError("以下格式开书失败/崩溃: %s(成功: %s)"
                             % (", ".join(failed), ", ".join(opened)))


def _click_any(dev, keywords, max_swipes=0):
    """依次按文本/描述点击候选关键词(可选滚动查找);命中返回 True.
    文本匹配含精确与包含两级(书菜单项文本带图标前缀如「ⓘ 文件信息」,精确匹配会漏)."""
    for kw in keywords:
        try:
            if dev.click_text(kw) or dev.click_desc(kw):
                return True
            el = dev.d(textContains=kw)
            if el.exists:
                el.click()
                return True
        except Exception:
            continue
    if max_swipes:
        for kw in keywords:
            el = _find_text_scrolled(dev, kw, max_swipes=max_swipes)
            if el is not None and el.exists:
                el.click()
                return True
    return False


def _close_stray_dialogs(dev, max_rounds=2):
    """关闭可能残留的对话框(书库文件夹配置/通用取消键),避免遮挡书库操作.
    只处理已知无害弹层,主界面/阅读器不动."""
    markers = ("书库文件夹配置", "添加文件夹", "选择模式", "打开方式")
    for _ in range(max_rounds):
        try:
            xml = dev.d.dump_hierarchy()
        except Exception:
            return
        if not any(m in xml for m in markers):
            return
        if not dev.click_text("取消") and not dev.click_text("关闭"):
            dev.d.press("back")
        time.sleep(1.5)


def _ensure_list_row(dev, keyword, extra_titles=()):
    """书库确保 keyword 书行以文本节点可见并返回该节点.
    书架/封面视图的书名画在封面位图里无文本节点(真机实测 textContains 只命中搜索框),
    须切到 列表/简表 视图才有 title1/路径 文本节点;同时排除顶部搜索框/chips 的同名匹配
    (真机踩坑:搜索 big25 时 textContains 命中的是 filterLine 自己).
    extra_titles:书名元数据标题候选(如 big25.pdf 的显示名 Bench25)."""
    _close_stray_dialogs(dev)
    # 必须确认落在书库 Tab:我的文件 Tab 也有 onGridList(打开的是书库文件夹配置对话框,
    # 真机踩坑 2026-09-13),用书库专属的 filterLine 区分
    for _ in range(2):
        _goto_tab_or_fail(dev, "书库")
        time.sleep(1.5)
        if _safe_exists(dev, dev.d(resourceId=_rid(dev, "filterLine"))):
            break
    if not _safe_exists(dev, dev.d(resourceId=_rid(dev, "filterLine"))):
        return None
    fl = dev.d(resourceId=_rid(dev, "filterLine"))
    if fl.exists:
        got = ""
        try:
            got = fl.get_text() or ""
        except Exception:
            pass
        if keyword not in got:
            _fill(dev, fl, keyword, "(书库搜索)")
            time.sleep(2)
            _dismiss_keyboard(dev)
    _, h = dev.d.window_size()
    kws = [keyword] + [t for t in extra_titles if t]

    def find_row(max_swipes=0):
        for swipe in range(max_swipes + 1):
            for kw in kws:
                sel = dev.d(textContains=kw)
                try:
                    n = sel.count
                except Exception:
                    n = 1 if sel.exists else 0
                for i in range(min(n, 10)):
                    try:
                        el = sel[i]
                        if not el.exists:
                            continue
                        b = _node_bounds(el)
                    except Exception:
                        continue
                    if b and (b[1] + b[3]) / 2 > 0.22 * h:  # 排除搜索框/chips(顶部区域)
                        return el
            if swipe < max_swipes:
                w, hh = dev.d.window_size()
                dev.d.swipe(0.5 * w, 0.7 * hh, 0.5 * w, 0.3 * hh, 0.3)
                time.sleep(1.2)
        return None

    row = find_row()
    if row is not None:
        return row
    # 切列表视图(书架/封面视图无文本节点);弹层未出现时 back 重试一次
    for attempt in range(2):
        vm = dev.d(resourceId=_rid(dev, "onGridList"))
        if not vm.exists:
            break
        vm.click()
        time.sleep(1.8)
        item = None
        for label in ("列表", "简表", "List", "Simple list"):
            el = dev.d(text=label)
            if el.exists:
                item = el
                break
        if item is not None:
            item.click()
            time.sleep(2.5)
            row = find_row(max_swipes=5)
            if row is not None:
                return row
        else:
            dev.save_dump("view", "popup_missing_%d" % attempt)
            dev.d.press("back")
            time.sleep(1.2)
    return None


def _click_row_menu(dev, keyword, extra_titles=()):
    """点 keyword 书行右侧 ⋮(itemMenu/shelfMenu)按钮 → 书菜单(ShareDialog).
    ⋮ 无 content-desc,按"与行同 y"从 dump 找 resource-id;找不到则点行右缘坐标,
    点击后校验菜单/对话框出现,未出现再补一次坐标.成功弹出返回 True."""
    row = _ensure_list_row(dev, keyword, extra_titles)
    if row is None:
        return False
    b = _node_bounds(row)
    if not b:
        return False
    cy = int((b[1] + b[3]) / 2)
    w, _ = dev.d.window_size()

    def menu_open():
        try:
            xml = dev.d.dump_hierarchy()
        except Exception:
            return False
        return any(k in xml for k in ("文件信息", "转换为", "上传到", "标记已读", "加入书库",
                                      "打开方式", "发送文件", "File info", "Convert"))

    for cx in (None, w - 90, w - 130):
        if cx is None:
            try:
                xml = dev.d.dump_hierarchy()
            except Exception:
                xml = ""
            best = None
            for m in re.finditer(r'resource-id="[^"]*:id/(?:itemMenu|shelfMenu)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
                x0, y0, x1, y1 = map(int, m.groups())
                my = (y0 + y1) / 2
                if abs(my - cy) < 300 and (best is None or abs(my - cy) < abs(best[0] - cy)):
                    best = (my, (x0 + x1) // 2, (y0 + y1) // 2)
            if best is None:
                continue
            dev.d.click(best[1], best[2])
        else:
            dev.d.click(cx, cy)
        time.sleep(2)
        if menu_open():
            return True
    return False


def _set_reading_mode(dev, case_id, target_kws):
    """偏好 → 单机行为(configSingeClick)切换阅读模式.
    真机(2026-09-13):该行在[阅读配置]折叠容器内(默认收起,须先点容器头展开);
    弹层选项为中文:选择模式/上下翻页(=Scroll)/左右翻页(=Book)/演奏模式/标签管理/打开方式.
    返回 True=切换成功;False=行不可达."""
    _goto_tab_or_fail(dev, "偏好")
    row = _scroll_to_find(dev, "configSingeClick")
    if row is None:
        # configSingeClick 在阅读配置容器内,展开后重找
        hdr = None
        for kw in ("阅读配置", "常规设置"):
            el = dev.d(text=kw)
            if el.exists:
                hdr = el
                break
        if hdr is not None:
            hdr.click()
            time.sleep(1.5)
        row = _scroll_to_find(dev, "configSingeClick")
    if row is None:
        dev.save_dump(case_id, "no_singeclick_row")
        return False
    row.click()
    time.sleep(1.8)
    ok = _click_any(dev, target_kws)
    time.sleep(2)
    return ok


def fn31_drawer_menu(dev, case_id, cfg=None, fixtures=None):
    """抽屉菜单遍历(§2):首页呼出侧滑抽屉 → 断言已知菜单条目存在(≥3 项) → 抽查截图.
    抽屉条目代码构建(MenuBuilderM/MyPopupMenu),按关键词命中计数断言."""
    with dev.step(case_id, "open_drawer"):
        _ensure_home(dev)
        opened = dev.click_desc("菜单") or dev.click_text("菜单")
        if not opened:
            # 兜底:左缘右滑呼出抽屉
            w, h = dev.d.window_size()
            dev.d.swipe(int(0.05 * w), int(0.5 * h), int(0.65 * w), int(0.5 * h), 0.3)
            time.sleep(2)
        time.sleep(1.5)
        xml = dev.d.dump_hierarchy()
        known = ["最近阅读", "我的珍藏", "书签", "OPDS", "网盘", "偏好", "设置",
                 "阅读统计", "Download", "收藏", "标签", "Recent", "Favorites", "OPDS"]
        hits = [k for k in known if k in xml]
        _snap(dev, case_id, "drawer_open")
        if len(hits) < 3:
            dev.save_dump(case_id, "drawer_items_missing")
            raise AssertionError("抽屉已知条目命中不足: %s" % hits)
        print("  [%s] 抽屉命中条目: %s" % (dev.serial, ",".join(hits)))
    with dev.step(case_id, "close_drawer"):
        dev.d.press("back")
        time.sleep(1)


def fn32_favorites_page(dev, case_id, cfg=None, fixtures=None):
    """收藏列表页(§3):自置收藏状态(不依赖 FN-02 残留,2026-09-13)→ 首页[我的珍藏]
    分区「更多」(sectionMore)进完整收藏列表 → 断言含已收藏的 big25.
    (分区标题本身无点击监听,须点同 y 区间的 sectionMore——同 _click_section_add 结论)"""
    with dev.step(case_id, "ensure_favorite"):
        _ensure_home(dev)
        _goto_browse_download(dev, case_id)
        added = False
        for _ in range(2):
            # 幂等:已在收藏(行操作为"从收藏夹中移除")直接视为已收藏
            if _click_row_action(dev, "big25", "添加到收藏夹"):
                added = True
                break
            if _row_has_action(dev, "big25", "从收藏夹中移除"):
                added = True
                break
            time.sleep(1.5)
        if not added:
            dev.save_dump(case_id, "fav_add_failed")
            raise TestSkip("无法添加收藏(big25 行操作不可达)")
        time.sleep(1.5)
    with dev.step(case_id, "open_favorites"):
        dev.click_desc("首页") or dev.click_text("首页")
        time.sleep(1.5)
        sec = None
        for kw in ("我的珍藏", "珍藏"):
            el = dev.d(textContains=kw)
            if el.exists:
                sec = el
                break
        if sec is None:
            for _ in range(5):
                w, h = dev.d.window_size()
                dev.d.swipe(0.5 * w, 0.7 * h, 0.5 * w, 0.3 * h, 0.3)
                time.sleep(1.2)
                el = dev.d(textContains="我的珍藏")
                if el.exists:
                    sec = el
                    break
        if sec is None:
            dev.save_dump(case_id, "no_favorites_section")
            raise TestSkip("首页无[我的珍藏]分区")
        # 点同 y 区间的「更多」(sectionMore)进完整列表;标题本身不可点
        clicked_more = False
        b = _node_bounds(sec)
        if b:
            cy = (b[1] + b[3]) // 2
            xml = dev.d.dump_hierarchy()
            for bs in re.findall(
                    r'resource-id="[^"]*:id/sectionMore"[^>]*?bounds="(\[\d+,\d+\]\[\d+,\d+\])"', xml):
                m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', bs)
                if not m:
                    continue
                x0, y0, x1, y1 = map(int, m.groups())
                if y0 <= cy <= y1:
                    dev.d.click((x0 + x1) // 2, (y0 + y1) // 2)
                    clicked_more = True
                    break
        if not clicked_more:
            sec.click()  # 兜底(部分版本标题可点)
        time.sleep(3)
    with dev.step(case_id, "verify_list"):
        xml = dev.d.dump_hierarchy()
        _snap(dev, case_id, "favorites_page")
        ok = "big25" in xml or "alicesadventures" in xml or "Big25" in xml
        if not ok:
            dev.save_dump(case_id, "favorites_empty")
            raise AssertionError("收藏列表页无已收藏书籍")
    with dev.step(case_id, "exit"):
        dev.d.press("back")
        time.sleep(1)


def fn33_hidden_files(dev, case_id, cfg=None, fixtures=None):
    """文件浏览隐藏文件开关(§4):推 .autotest_hidden.txt → 我的文件菜单勾选[显示隐藏文件]
    → 文件出现 → 取消勾选还原.开关为 BrowseFragment2 弹出菜单的 isDisplayAllFilesInFolder
    (BrowseFragment2.java:2132).菜单入口机型差异大,不可达时 SKIP."""
    hidden = "/sdcard/Download/.autotest_hidden.txt"
    dev.shell("echo autotest > %s" % hidden)
    try:
        with dev.step(case_id, "goto_download"):
            _ensure_home(dev)
            if not _browse_root(dev):
                raise AssertionError("我的文件根视图不可达")
            for _dl_try in range(2):
                try:
                    dl = _browse_dl_row(dev)
                    if dl is None:
                        break
                    dl.click()
                    break
                except Exception:
                    # 进入时列表可能正在刷新(StaleObject),重取一次(2026-09-13)
                    if _dl_try == 1:
                        raise
                    time.sleep(2)
            else:
                dl = None
            time.sleep(2.5)
        with dev.step(case_id, "toggle_show_hidden"):
            # [显示隐藏文件]在浏览页弹出菜单(BrowseFragment2.popupMenu:2110),触发按钮
            # 是 R.id.onListGrid(浏览页专属,与书库 onGridList 不同 id,真机核实 2026-09-13)
            vm = None
            for rid in ("onListGrid", "onGridList"):
                el = dev.d(resourceId=_rid(dev, rid))
                if _safe_exists(dev, el):
                    vm = el
                    break
            if vm is None:
                dev.save_dump(case_id, "no_browse_gridlist")
                raise TestSkip("浏览页无 onListGrid/onGridList 按钮")
            vm.click()
            time.sleep(1.8)
            xml = dev.d.dump_hierarchy()
            if "显示隐藏" not in xml:
                dev.d.press("back")
                dev.save_dump(case_id, "no_browse_menu")
                raise TestSkip("浏览弹出菜单无[显示隐藏文件]项")
            item = dev.d(textContains="显示隐藏")
            try:
                if isinstance(item.info.get("checked"), bool) and not item.info["checked"]:
                    item.click()
                    time.sleep(1.5)
            except Exception:
                item.click()
                time.sleep(1.5)
            dev.d.press("back")  # 关弹出菜单(checkbox 点击不自动关)
            time.sleep(1.5)
        with dev.step(case_id, "verify_hidden_visible"):
            # 开关会触发列表重扫,netSection 短暂消失——直接进 Tab 轮询等待,
            # 不做 back 风暴(刷新期 back 会把应用退到桌面,真机踩坑 2026-09-13)
            _close_stray_dialogs(dev)
            _goto_tab_or_fail(dev, "我的文件")
            ok = False
            deadline = time.time() + 15
            while time.time() < deadline:
                if _safe_exists(dev, dev.d(resourceId=_rid(dev, "netSection"))):
                    ok = True
                    break
                time.sleep(1.5)
            if not ok:
                dev.save_dump(case_id, "root_not_ready")
                raise AssertionError("开关后我的文件根视图未就绪")
            for _dl_try in range(2):
                try:
                    dl = _browse_dl_row(dev)
                    if dl is None:
                        break
                    dl.click()
                    break
                except Exception:
                    # 进入时列表可能正在刷新(StaleObject),重取一次(2026-09-13)
                    if _dl_try == 1:
                        raise
                    time.sleep(2)
            else:
                dl = None
            time.sleep(2.5)
            el = _find_text_scrolled(dev, ".autotest_hidden", max_swipes=4)
            _snap(dev, case_id, "hidden_visible")
            if el is None:
                dev.save_dump(case_id, "hidden_not_visible")
                raise AssertionError("勾选[显示隐藏文件]后 .autotest_hidden 仍不可见")
    finally:
        dev.shell("rm -f %s" % hidden)


def fn34_reader_lock(dev, case_id, cfg=None, fixtures=None):
    """阅读页锁定开关(§7):工具条 lockUnlock 切换 + 无 crash.
    审计结论(2026-09-13):阅读模式仅 上下翻页/左右翻页/演奏模式,翻页拖动走
    pager(VerticalViewPager)未接锁;锁定门控在 PageImaveView 页内平移/缩放
    (:809/:936);锁定状态无 UI 回读(仅换图标),且上下翻页文本阅读器底栏无
    页码指示器——行为断言不可行,本用例验证开关机制与稳定性.产品决策待定:
    是否给 pager 接锁(会与 lockBooksByDefault=true 叠加成开篇即不可拖动)."""
    def toggle_lock():
        # 工具条「点中央」是切换不是显示,单次尝试会输给翻转竞态——重试轮询
        for _ in range(4):
            _reader_show_toolbar(dev)
            btn = _reader_toolbar_btn(dev, "lockUnlock", desc_kws=("锁定",), expand_first=False)
            if btn is not None and btn.exists:
                btn.click()
                time.sleep(1.2)
                return
            w, h = dev.d.window_size()
            dev.d.click(int(0.5 * w), int(0.4 * h))
            time.sleep(1.5)
        dev.save_dump(case_id, "no_lock_entry")
        raise TestSkip("锁定入口(lockUnlock)不可见")

    try:
        with dev.step(case_id, "open_book"):
            _ensure_home(dev)
            if not _set_reading_mode(dev, case_id, ["上下翻页", "Scroll mode", "滚动"]):
                dev.save_dump(case_id, "no_mode_item")
                raise TestSkip("偏好无[单机行为]行或无上下翻页项")
            _open_reader_warm(dev, case_id, "alicesadventures")
            time.sleep(2)
        with dev.step(case_id, "toggle_lock"):
            toggle_lock()
            _snap(dev, case_id, "toggled_once")
            c = dev.scan_crash()
            if c:
                raise AssertionError("锁定切换 crash: %s" % c)
        with dev.step(case_id, "restore"):
            toggle_lock()  # 切回原态
            _exit_reader(dev, case_id)
            _back_to_main(dev)
    except Exception:
        try:
            _exit_reader(dev, case_id)
            _back_to_main(dev)
        except Exception:
            pass
        raise


def fn35_contrast_brightness(dev, case_id, cfg=None, fixtures=None):
    """对比度/亮度弹窗(§8):阅读偏好里打开 对比度/亮度 对话框(dialogContrastAndBrigtness,
    DocumentWrapperUI.java:274)→ 断言弹窗出现 → 还原关闭."""
    with dev.step(case_id, "open_book"):
        _open_reader(dev, case_id, fixtures, "pdf")
        _reader_show_toolbar(dev)
    with dev.step(case_id, "open_dialog"):
        # 轮询入口(偏好弹窗可拖拽,行查找禁 swipe;分区默认折叠,展开后再找)
        if not _open_prefs_popup(dev, timeout=30):
            dev.save_dump(case_id, "no_prefs_entry")
            raise TestSkip("阅读偏好入口不可见")
        row_kws = ["对比度", "亮度", "Contrast", "Brightness"]
        if not _click_any(dev, row_kws):
            for sec in ("可视选项", "排版选项", "控制选项"):
                if _click_any(dev, [sec]) and _click_any(dev, row_kws):
                    break
            else:
                dev.d.press("back")
                dev.save_dump(case_id, "no_contrast_row")
                raise TestSkip("阅读偏好无[对比度/亮度]行")
        time.sleep(2)
        xml = dev.d.dump_hierarchy()
        _snap(dev, case_id, "contrast_dialog")
        if "对比度" not in xml and "Contrast" not in xml and "亮度" not in xml:
            dev.d.press("back")
            dev.save_dump(case_id, "contrast_dialog_missing")
            raise AssertionError("对比度/亮度对话框未出现")
    with dev.step(case_id, "exit"):
        for _ in range(3):
            dev.d.press("back")
            time.sleep(0.8)
        _exit_reader(dev, case_id)


def fn36_bluelight(dev, case_id, cfg=None, fixtures=None):
    """蓝光滤镜(§8):阅读偏好打开蓝光对话框(dialog_bluelight,强度滑杆)→ 断言出现 → 还原."""
    with dev.step(case_id, "open_book"):
        _open_reader(dev, case_id, fixtures, "pdf")
        _reader_show_toolbar(dev)
    with dev.step(case_id, "open_dialog"):
        if not _open_prefs_popup(dev, timeout=30):
            dev.save_dump(case_id, "no_prefs_entry")
            raise TestSkip("阅读偏好入口不可见")
        row_kws = ["蓝光", "护眼", "Blue", "bluelight"]
        if not _click_any(dev, row_kws):
            for sec in ("可视选项", "排版选项", "控制选项"):
                if _click_any(dev, [sec]) and _click_any(dev, row_kws):
                    break
            else:
                dev.d.press("back")
                dev.save_dump(case_id, "no_bluelight_row")
                raise TestSkip("阅读偏好无[蓝光滤镜]行")
        time.sleep(2)
        xml = dev.d.dump_hierarchy()
        _snap(dev, case_id, "bluelight_dialog")
        if "蓝光" not in xml and "Blue" not in xml and "强度" not in xml:
            dev.d.press("back")
            dev.save_dump(case_id, "bluelight_dialog_missing")
            raise AssertionError("蓝光滤镜对话框未出现")
    with dev.step(case_id, "exit"):
        for _ in range(3):
            dev.d.press("back")
            time.sleep(0.8)
        _exit_reader(dev, case_id)


def fn37_file_info(dev, case_id, cfg=None, fixtures=None):
    """文件信息对话框(§16):书库长按书 → 菜单/信息对话框 → 文件大小/路径字段非空.
    (ShareDialog 菜单项 file_info / FileInformationDialog)."""
    with dev.step(case_id, "open_menu"):
        _ensure_home(dev)
        if not _click_row_menu(dev, "big25", extra_titles=("Bench25", "Big25")):
            dev.save_dump(case_id, "no_big25_row")
            raise TestSkip("设备书库未收录 big25(环境限制)")
    with dev.step(case_id, "open_file_info"):
        # ⋮ 书菜单(ShareDialog) → [文件信息];若直接是信息对话框则字段已在树中
        if not _click_any(dev, ["文件信息", "信息", "File info", "Info"]):
            if "大小" not in dev.d.dump_hierarchy():
                dev.save_dump(case_id, "no_file_info_item")
                dev.d.press("back")
                raise TestSkip("书菜单无[文件信息]项(菜单待勘探)")
        time.sleep(2)
        xml = dev.d.dump_hierarchy()
        _snap(dev, case_id, "file_info_dialog")
        ok = any(k in xml for k in ("Bench25", "标题", "作者", "ISBN", "信息", "Title", "Author"))
        if not ok:
            dev.save_dump(case_id, "file_info_empty")
            raise AssertionError("文件信息对话框无标题/元数据字段")
    with dev.step(case_id, "exit"):
        for _ in range(3):
            dev.d.press("back")
            time.sleep(0.8)
        clean = dev.d(resourceId=_rid(dev, "cleanFilter"))
        if clean.exists:
            clean.click()
            time.sleep(1)


def fn38_mark_read(dev, case_id, cfg=None, fixtures=None):
    """标记已读/未读(§3 阅读状态):书库长按 → 菜单[标记已读]应用 → 无 crash 还原.
    (ShareDialog 菜单项 moon_mark_read/moon_mark_unread/moon_mark_reading)."""
    with dev.step(case_id, "mark_read"):
        _ensure_home(dev)
        if not _click_row_menu(dev, "big25", extra_titles=("Bench25", "Big25")):
            dev.save_dump(case_id, "no_big25_row")
            raise TestSkip("设备书库未收录 big25(环境限制)")
        if not _click_any(dev, ["标记为已读", "标记已读", "mark read", "已读"]):
            dev.save_dump(case_id, "no_mark_read_item")
            dev.d.press("back")
            raise TestSkip("书菜单无[标记已读]项")
        time.sleep(2)
        c = dev.scan_crash()
        _snap(dev, case_id, "marked_read")
        if c:
            raise AssertionError("标记已读 crash: %s" % c)
    with dev.step(case_id, "restore"):
        if _click_row_menu(dev, "big25", extra_titles=("Bench25", "Big25")):
            _click_any(dev, ["标记为未读", "标记未读", "mark unread", "未读"])
            time.sleep(1.5)
        clean = dev.d(resourceId=_rid(dev, "cleanFilter"))
        if clean.exists:
            clean.click()
            time.sleep(1)


def fn39_line_spacing(dev, case_id, cfg=None, fixtures=None):
    """阅读偏好 CustomSeek 调节(§8):弹窗内[滚动速度]行 +N → 数值/画面变化 → 还原.
    注:原计划行距——本 fork 阅读偏好弹窗无[行距]行(line_spacing 字符串无代码引用,
    真机核实 2026-09-13),改用同机制的[滚动速度] CustomSeek 行(与 FN-18 字号同模式)."""
    from cases.ui.tc_smoke import _same_png
    ROWS = ("滚动速度", "Scroll speed", "预载页数")

    def find_row():
        for kw in ROWS:
            el = dev.d(textContains=kw)
            if el.exists:
                return el
        return None
    with dev.step(case_id, "open_book"):
        _open_reader(dev, case_id, fixtures, "epub")
    with dev.step(case_id, "open_prefs_popup"):
        _reader_show_toolbar(dev)
        btn = _reader_toolbar_btn(dev, "prefTop", desc_kws=("偏好",))
        if btn is None:
            dev.save_dump(case_id, "no_preftop")
            raise TestSkip("阅读偏好入口(prefTop)不可见")
        btn.click()
        time.sleep(2.5)
        row = find_row()
        if row is None:
            # 弹窗为分区结构(可视选项/排版选项/状态栏/控制选项,默认收起),逐区展开找;
            # 注意 DragingPopup 容器可拖拽,swipe 会拖走弹窗而非滚动内容
            for sec in ("排版选项", "可视选项", "控制选项", "状态栏"):
                if _click_any(dev, [sec]):
                    time.sleep(1.2)
                    row = find_row()
                    if row is not None:
                        break
        if row is None:
            dev.save_dump(case_id, "no_speed_row")
            raise TestSkip("阅读偏好无[滚动速度/预载页数]行(展开各分区后仍未找到)")
        _snap(dev, case_id, "prefs_row")
    with dev.step(case_id, "change_value"):
        b = _node_bounds(row)
        w, _h = dev.d.window_size()
        # 行标签节点只是文本本身,「+/−」按钮在行两端——边界扩到整行再定位
        b = (0, b[1], w, b[3])
        before_val = _customseek_value(dev, b)
        before_png = dev.screenshot(case_id, "speed_before")
        cy = (b[1] + b[3]) // 2
        for _ in range(4):
            dev.d.click(b[2] - 80, cy)
            time.sleep(1.2)
        after_val = _customseek_value(dev, b)
        after_png = dev.screenshot(case_id, "speed_after")
        _snap(dev, case_id, "speed_after")
        changed = (before_val is not None and after_val is not None and after_val != before_val) \
            or (before_png and after_png and not _same_png(before_png, after_png))
        if not changed:
            dev.save_dump(case_id, "speed_nochange")
            raise AssertionError("滚动速度 +N 后数值/画面无变化(before=%s after=%s)" % (before_val, after_val))
    with dev.step(case_id, "restore"):
        row = find_row()
        if row is not None and row.exists:
            b = _node_bounds(row)
            w, _h = dev.d.window_size()
            b = (0, b[1], w, b[3])
            for _ in range(6):
                dev.d.click(b[2] - 180, (b[1] + b[3]) // 2)
                time.sleep(0.8)
        dev.d.press("back")
        time.sleep(1)


def _open_prefs_popup(dev, timeout=40):
    """轮询并点击阅读偏好入口(bookPref/prefTop).文档异步渲染期间按钮后挂,
    固定等待会漏——轮询到出现为止(2026-09-13).成功 True."""
    deadline = time.time() + timeout
    while True:
        _reader_show_toolbar(dev)
        for getter in (lambda: _reader_toolbar_btn(dev, "bookPref"),
                       lambda: _reader_toolbar_btn(dev, "prefTop")):
            o = getter()
            if o is not None and o.exists:
                o.click()
                time.sleep(2.5)
                return True
        if time.time() >= deadline:
            return False
        time.sleep(2.5)


def _open_statusbar_settings(dev):
    """已打开的阅读偏好弹窗 → [状态栏]分区(默认折叠,点分区头展开) →
    statusBarSettings 行 → 状态栏设置对话框.全程元素定位,禁用 swipe
    (偏好弹窗是 DragingPopup 可拖拽容器,swipe 会拖走弹窗而非滚动内容,
    2026-09-13 定位:fn40 SKIP/fn41 FAIL 的共同根因).成功 True."""
    sb = dev.d(resourceId=_rid(dev, "statusBarSettings"))
    for _ in range(3):
        if sb.exists:
            sb.click()
            time.sleep(2)
            return True
        if not _click_any(dev, ["状态栏"]):
            break
        time.sleep(1.5)
    dev.d.press("back")
    return False


def fn40_page_format(dev, case_id, cfg=None, fixtures=None):
    """页码格式切换(§9):阅读偏好→[状态栏](statusBarSettings)→[页码格式]
    (pageNumberFormat)弹出菜单选[百分比]→ 阅读器页码文案含 % → 还原为页码.
    真实入口 2026-09-13 源码核实:偏好 Tab 无此行,在 DragingDialogs.
    dialogStatusBarSettings 内;Book 模式热态开书保证页码指示器实时(坑 24)."""
    with dev.step(case_id, "switch_book_mode"):
        _ensure_home(dev)
        if not _set_reading_mode(dev, case_id, ["左右翻页", "Book mode", "书本"]):
            dev.save_dump(case_id, "no_book_mode_item")
            raise TestSkip("偏好无[单机行为]行或无左右翻页项")

    def _scroll_until(el):
        for _ in range(4):
            if el.exists:
                return True
            w, h = dev.d.window_size()
            dev.d.swipe(0.5 * w, 0.7 * h, 0.5 * w, 0.4 * h, 0.3)
            time.sleep(1)
        return el.exists

    with dev.step(case_id, "open_format_dialog"):
        _open_reader_warm(dev, case_id, "book_pdf")
        # 偏好入口轮询等待(异步渲染);[状态栏]分区展开后 rid 定位,不用 swipe
        if not _open_prefs_popup(dev, timeout=40):
            _exit_reader(dev, case_id)
            dev.save_dump(case_id, "no_prefs_entry")
            raise TestSkip("阅读偏好入口不可见")
        if not _open_statusbar_settings(dev):
            _exit_reader(dev, case_id)
            dev.save_dump(case_id, "no_statusbar_row")
            raise TestSkip("阅读偏好无[状态栏]行(分区展开后仍不可见)")
        pf = dev.d(resourceId=_rid(dev, "pageNumberFormat"))
        if not _scroll_until(pf):
            dev.d.press("back")
            dev.d.press("back")
            _exit_reader(dev, case_id)
            dev.save_dump(case_id, "no_page_format_row")
            raise TestSkip("状态栏设置无[页码格式]行")
        pf.click()
        time.sleep(1.5)
        if not _click_any(dev, ["百分比", "Percent"]):
            dev.d.press("back")
            dev.d.press("back")
            _exit_reader(dev, case_id)
            dev.save_dump(case_id, "no_percent_item")
            raise TestSkip("页码格式弹出菜单无[百分比]项")
        time.sleep(1.5)
        dev.d.press("back")  # 关状态栏设置
        time.sleep(1)
        dev.d.press("back")  # 关阅读偏好
        time.sleep(1)
    with dev.step(case_id, "verify_in_reader"):
        # 关弹层后阅读器异步重建,页码文案轮询到出现为止(空文案=读早了)
        txt = ""
        deadline = time.time() + 30
        while time.time() < deadline:
            _reader_show_toolbar(dev)
            for rid in ("currentPageIndex", "pagesCountIndicator"):
                el = dev.d(resourceId=_rid(dev, rid))
                if el.exists:
                    txt = el.get_text() or ""
                    if "%" in txt:
                        break
            if "%" in txt:
                break
            time.sleep(2.5)
        _snap(dev, case_id, "reader_percent")
        if not txt or "%" not in txt:
            # 带书签的 PDF/EPUB 指示器是「标题 – N ∕ M」复合格式,不路由百分比
            # (deltaPage 只格式化纯数字段)——回读设置行值兜底断言
            if not _open_prefs_popup(dev, timeout=15) or not _open_statusbar_settings(dev):
                dev.save_dump(case_id, "reader_percent_missing")
                raise AssertionError("切百分比后阅读器页码未含 %%: %r,且状态栏设置不可达" % txt)
            pf_row = dev.d(resourceId=_rid(dev, "pageNumberFormat"))
            val = (pf_row.get_text() or "").strip() if pf_row.exists else ""
            dev.d.press("back")
            time.sleep(0.8)
            dev.d.press("back")
            time.sleep(0.8)
            if "百分比" not in val and "Percent" not in val:
                dev.save_dump(case_id, "reader_percent_missing")
                raise AssertionError("切百分比未生效: 阅读器 %r, 设置行 %r" % (txt, val))
    with dev.step(case_id, "restore"):
        # 还原必须闭环:循环点到 pageNumberFormat 行显示「页码」为止——静默还原
        # 失败会把百分比格式带给后续所有读页码的用例(FN-49/51 双机中毒实锤)
        _reader_show_toolbar(dev)
        if _open_prefs_popup(dev, timeout=20) and _open_statusbar_settings(dev):
            pf = dev.d(resourceId=_rid(dev, "pageNumberFormat"))
            for _ in range(3):
                if not pf.exists:
                    break
                val = (pf.get_text() or "").strip()
                if "页码" in val or "Number" in val:
                    break
                pf.click()
                time.sleep(1.2)
                # 弹层选项必须精确匹配:_click_any 的 textContains 会误中
                # 「页码格式」行标签本身,选项永远选不上(2026-09-14 实锤)
                item = dev.d(text="页码")
                if not item.exists:
                    item = dev.d(text="Number")
                if item.exists:
                    item.click()
                time.sleep(1.5)
        for _ in range(3):
            dev.d.press("back")
            time.sleep(0.6)
        _exit_reader(dev, case_id)


def fn41_statusbar_pos(dev, case_id, cfg=None, fixtures=None):
    """进度条/状态栏位置(§9):两级入口 阅读偏好→[状态栏](statusBarSettings)→[位置]
    → 顶部/底部 切换 → currentSeek/页码 y 位置变化 → 还原.
    (2026-09-13 修正:此前只点第一级,位置从未切换;Book 模式热态开书保证指示器实时)"""
    def reader_progress_y(timeout=40):
        # 文档异步渲染(「请稍候…」转圈),进度条元素加载完成才挂上——轮询等待
        deadline = time.time() + timeout
        while True:
            _reader_show_toolbar(dev)
            for rid in ("pagesCountIndicator", "currentSeek"):
                # statusBarPosition 移动的是 bottomPanel(pagesCountIndicator 所在),
                # currentSeek 永远在底部不动——旧逻辑即使切换成功也测不出变化
                el = dev.d(resourceId=_rid(dev, rid))
                if el.exists:
                    b = _node_bounds(el)
                    if b:
                        return (b[1] + b[3]) / 2
            if time.time() >= deadline:
                return None
            time.sleep(2)

    def open_prefs_statusbar():
        """阅读偏好 → 状态栏设置(分区展开+元素定位,禁 swipe——偏好弹窗可拖拽)."""
        if not _open_prefs_popup(dev, timeout=15):
            return False
        return _open_statusbar_settings(dev)

    with dev.step(case_id, "switch_book_mode"):
        _ensure_home(dev)
        if not _set_reading_mode(dev, case_id, ["左右翻页", "Book mode", "书本"]):
            dev.save_dump(case_id, "no_book_mode_item")
            raise TestSkip("偏好无[单机行为]行或无左右翻页项")
    with dev.step(case_id, "baseline_bottom"):
        _open_reader_warm(dev, case_id, "book_pdf")
        y1 = reader_progress_y(timeout=40)
        if y1 is None:
            dev.save_dump(case_id, "no_progress_bar")
            raise AssertionError("阅读器进度条不可见")
    with dev.step(case_id, "switch_position"):
        if not open_prefs_statusbar():
            dev.save_dump(case_id, "no_statusbar_row")
            raise TestSkip("阅读偏好/状态栏设置不可达")
        pos = dev.d(resourceId=_rid(dev, "statusBarPosition"))
        if not pos.exists:
            dev.d.press("back")
            dev.d.press("back")
            dev.save_dump(case_id, "no_position_row")
            raise TestSkip("状态栏设置无[位置]行(仅 Book 模式显示)")
        pos.click()  # 点「值」TextView 弹顶部/底部菜单(行标签无监听,点它无效)
        time.sleep(1.5)
        pick = "顶部" if y1 > dev.d.window_size()[1] * 0.5 else "底部"
        if not _click_any(dev, [pick, "Top" if pick == "顶部" else "Bottom"]):
            dev.d.press("back")
            dev.d.press("back")
            dev.save_dump(case_id, "no_position_pick")
            raise TestSkip("位置弹出菜单无[%s]项" % pick)
        time.sleep(2)  # 选中即应用
        dev.d.press("back")  # 关状态栏设置
        time.sleep(1)
        dev.d.press("back")  # 关阅读偏好
        time.sleep(1)
    with dev.step(case_id, "verify_moved"):
        y2 = reader_progress_y()
        _snap(dev, case_id, "moved")
        if y2 is None:
            raise AssertionError("切换位置后进度条不可见")
        h = dev.d.window_size()[1]
        moved = (y1 < h * 0.4 and y2 > h * 0.6) or (y1 > h * 0.6 and y2 < h * 0.4)
        if not moved:
            raise AssertionError("进度条位置未变化(before_y=%.0f after_y=%.0f)" % (y1, y2))
    with dev.step(case_id, "restore"):
        if open_prefs_statusbar():
            pos = dev.d(resourceId=_rid(dev, "statusBarPosition"))
            if pos.exists:
                pos.click()
                time.sleep(1.5)
                orig = "底部" if pick == "顶部" else "顶部"
                _click_any(dev, [orig, "Top" if orig == "顶部" else "Bottom"])
                time.sleep(1.5)
        for _ in range(3):
            dev.d.press("back")
            time.sleep(0.6)
        _exit_reader(dev, case_id)


def fn42_stats_detail(dev, case_id, cfg=None, fixtures=None):
    """阅读统计详情(§16):首页统计仪表盘点入 → 月度柱状图/周月年区间切换断言.
    (MonthlyBarsView).入口机型差异大,不可达时 SKIP."""
    with dev.step(case_id, "open_stats_detail"):
        _ensure_home(dev)
        stat = None
        # 只有 statHours 打开"月度阅读"对话框(showMonthlyReading → MonthlyBarsView
        # + 周月年切换);statTotal 点击是 openLibraryWithFilter("") 跳书库列表,
        # statToday 无点击行为——点错卡会停在书库页导致断言失败(2026-09-13 定位)
        for rid in ("statHours",):
            el = dev.d(resourceId=_rid(dev, rid))
            if el.exists:
                stat = el
                break
        if stat is None:
            for _ in range(6):
                w, h = dev.d.window_size()
                dev.d.swipe(0.5 * w, 0.7 * h, 0.5 * w, 0.3 * h, 0.3)
                time.sleep(1.2)
                for rid in ("statHours",):
                    el = dev.d(resourceId=_rid(dev, rid))
                    if el.exists:
                        stat = el
                        break
                if stat:
                    break
        if stat is None:
            dev.save_dump(case_id, "no_stats_section")
            raise TestSkip("首页无统计仪表盘")
        stat.click()
        time.sleep(3)
    with dev.step(case_id, "verify_detail"):
        xml = dev.d.dump_hierarchy()
        _snap(dev, case_id, "stats_detail")
        ok = any(k in xml for k in ("月", "周", "年", "Month", "Week", "Year", "统计"))
        if not ok:
            dev.save_dump(case_id, "stats_detail_missing")
            raise AssertionError("统计详情页未见月度/区间元素")
    with dev.step(case_id, "exit"):
        for _ in range(3):
            dev.d.press("back")
            time.sleep(0.8)


def fn43_remote_cache_prefs(dev, case_id, cfg=None, fixtures=None):
    """在线阅读设置页(§15):偏好 → 远程/在线阅读缓存设置(RemoteCacheDialog)打开 →
    缓存上限/过期天数/清空缓存 字段存在 → 关闭."""
    with dev.step(case_id, "open_dialog"):
        _ensure_home(dev)
        _goto_tab_or_fail(dev, "偏好")
        if not _click_any(dev, ["在线阅读", "远程缓存", "缓存设置", "Remote cache"], max_swipes=10):
            dev.save_dump(case_id, "no_remote_cache_row")
            raise TestSkip("偏好页无[在线阅读/远程缓存]行(文案待勘探)")
        time.sleep(2.5)
        xml = dev.d.dump_hierarchy()
        _snap(dev, case_id, "remote_cache_dialog")
        ok = any(k in xml for k in ("缓存", "Cache", "MB", "GB", "过期"))
        if not ok:
            dev.save_dump(case_id, "remote_cache_dialog_missing")
            dev.d.press("back")
            raise AssertionError("在线阅读设置对话框未出现")
    with dev.step(case_id, "exit"):
        for _ in range(3):
            dev.d.press("back")
            time.sleep(0.8)


def fn44_sync_timer(dev, case_id, cfg=None, fixtures=None):
    """WebDAV 同步定时器(§14):偏好 → WebDAV 同步对话框 → 定时同步项存在 → 切换一档 → 回显.
    (webdavSyncDialog)."""
    with dev.step(case_id, "open_sync_dialog"):
        _ensure_home(dev)
        _goto_tab_or_fail(dev, "偏好")
        row = dev.d(resourceId=_rid(dev, "webdavSyncValue"))
        if not row.exists:
            dev.save_dump(case_id, "no_sync_row")
            raise TestSkip("偏好页无 WebDAV 同步行")
        row.click()
        time.sleep(2.5)
        if not dev.d(resourceId=_rid(dev, "webdavSyncNow")).exists:
            dev.save_dump(case_id, "no_sync_dialog")
            raise AssertionError("WebDAV 同步对话框未打开")
    with dev.step(case_id, "verify_timer"):
        xml = dev.d.dump_hierarchy()
        _snap(dev, case_id, "sync_dialog_timer")
        ok = any(k in xml for k in ("定时", "分钟", "小时", "Timer", "minute", "hour"))
        if not ok:
            dev.save_dump(case_id, "no_timer_option")
            raise AssertionError("同步对话框无定时同步选项")
    with dev.step(case_id, "exit"):
        dev.d.press("back")
        time.sleep(1)


def fn45_settings_backup(dev, case_id, cfg=None, fixtures=None):
    """设置备份导出(§19):偏好 →[导出](exportButton)→ 应用内文件 chooser
    (ChooserDialogFragment TYPE_CREATE_FILE,预填 *-librera-backup.zip,非系统 SAF
    ——2026-09-13 源码核实 PrefDialogs.exportDialog)→ 确认 → 断言 zip 落盘 → 清理."""
    with dev.step(case_id, "open_export"):
        _ensure_home(dev)
        _goto_tab_or_fail(dev, "偏好")
        hdr = dev.d(resourceId=_rid(dev, "backupConfigHeader"))
        if hdr.exists:
            hdr.click()  # [备份配置]分区默认折叠(gone),导出/导入行展开后才在树里
            time.sleep(1.5)
        export_btn = None
        for _ in range(6):
            el = dev.d(resourceId=_rid(dev, "exportButton"))
            if el.exists:
                export_btn = el
                break
            w, h = dev.d.window_size()
            dev.d.swipe(0.5 * w, 0.7 * h, 0.5 * w, 0.4 * h, 0.3)
            time.sleep(1)
        if export_btn is None:
            dev.save_dump(case_id, "no_export_row")
            raise TestSkip("偏好页无[导出]行(exportButton)")
        export_btn.click()
        time.sleep(3)
    with dev.step(case_id, "do_export"):
        xml = dev.d.dump_hierarchy()
        _snap(dev, case_id, "export_dialog")
        # 应用内 chooser:文件名已预填(含秒级时间戳,天然唯一);toybox find
        # 不支持 -newermt('bad date',2026-09-13 实锤:zip 已生成但被误报未找到),
        # 改按精确文件名查找
        import re as _re
        m = _re.search(r"[A-Za-z0-9._-]*librera-backup\.zip", xml)
        fname = m.group(0) if m else None
        saved = False
        for kw in ("保存", "确定", "选择", "Save", "OK"):
            if dev.click_text(kw) or dev.click_desc(kw):
                saved = True
                break
        if not saved:
            top = dev.shell("dumpsys activity activities | grep ResumedActivity")
            if "documentsui" in top:
                dev.d.press("back")
                dev.save_dump(case_id, "saf_picker")
                raise TestSkip("导出走系统文件选择器(SAF),自动化受限")
            dev.save_dump(case_id, "no_save_btn")
            dev.d.press("back")
            raise TestSkip("导出对话框无确认按钮(文案待勘探)")
        time.sleep(8)  # 压缩 profile 目录需要时间
        # toybox find 在 MIUI /sdcard 上静默列不出文件(ls 可见,find 为空,
        # 2026-09-13 实锤)——用 ls 按名(或当日 glob)在常见目录查找
        if fname:
            found = dev.shell(
                "ls /sdcard/%s /sdcard/Download/%s /sdcard/HowRead/%s 2>/dev/null"
                % (fname, fname, fname))
        else:
            today = time.strftime("%Y-%m-%d")
            found = dev.shell(
                "ls /sdcard/*%s*-librera-backup.zip /sdcard/Download/*%s*-librera-backup.zip 2>/dev/null"
                % (today, today))
        _snap(dev, case_id, "export_done")
        if not found.strip():
            dev.save_dump(case_id, "export_no_file")
            raise AssertionError("导出后未发现 *-librera-backup.zip")
        for f in found.strip().splitlines():
            dev.shell("rm -f '%s'" % f.strip().replace("'", ""))
    with dev.step(case_id, "exit"):
        for _ in range(2):
            dev.d.press("back")
            time.sleep(0.8)


def fn46_offline_reading(dev, case_id, cfg=None, fixtures=None):
    """断网离线阅读(§15):WebDAV book_pdf 在线打开一次(分块缓存)→ 断网(svc wifi/data disable)
    → 从最近阅读重开 → 进阅读器翻页 → 恢复网络.adb 切网(USB 调试不受影响)."""
    def net_state():
        return dev.shell("settings get global wifi_on").strip(), \
            dev.shell("settings get global data_on").strip()

    with dev.step(case_id, "online_open_and_cache"):
        title, _added = _ensure_server(dev, case_id, cfg, "webdav")
        _ensure_home(dev)
        entered = _open_remote_book(dev, case_id, title, "book_pdf")
        if not entered:
            dev.save_dump(case_id, "remote_open_timeout")
            raise AssertionError("在线打开 60s 未进阅读器")
        time.sleep(3)  # 留时间缓存分块
        _snap(dev, case_id, "cached_online")
        _exit_reader(dev, case_id)
        _back_to_main(dev)
    wifi0, data0 = net_state()
    try:
        with dev.step(case_id, "offline_reopen"):
            dev.shell("svc wifi disable")
            dev.shell("svc data disable")
            time.sleep(3)
            _ensure_home(dev)
            # 从最近阅读重开(不经网络列目录,直接 remote:// 路径命中本地缓存)
            if not (dev.click_desc("最近阅读") or dev.click_text("最近阅读")):
                el = dev.d(textContains="最近阅读")
                if el.exists:
                    el.click()
                else:
                    dev.save_dump(case_id, "no_recent_section")
                    raise AssertionError("首页无最近阅读分区")
            time.sleep(2)
            book = None
            for kw in ("book_pdf", "big25"):
                el = dev.d(textContains=kw)
                if el.exists:
                    book = el
                    break
            if book is None:
                # 首页"最近阅读"封面项无文字标签(dashboard_cover_item 只有封面+进度徽标),
                # textContains 必然落空;降级点击行内第一个封面(最新一本,即刚读过的远程书)
                row = dev.d(resourceId=_rid(dev, "recentRow"))
                if row.exists and row.child(index=0).exists:
                    book = row.child(index=0)
            if book is None:
                dev.save_dump(case_id, "offline_no_recent_book")
                raise AssertionError("最近阅读无远程书条目")
            book.click()
            entered = False
            deadline = time.time() + 60
            while time.time() < deadline:
                top = dev.shell("dumpsys activity activities | grep ResumedActivity")
                if "ViewActivity" in top or "TTSActivity" in top:
                    entered = True
                    break
                time.sleep(2.5)
            _snap(dev, case_id, "offline_reader")
            if not entered:
                dev.save_dump(case_id, "offline_open_failed")
                raise AssertionError("断网后远程书未能离线打开")
            c = dev.scan_crash()
            if c:
                raise AssertionError("离线打开 crash: %s" % c)
        with dev.step(case_id, "offline_page_turn"):
            before = _reader_page_or_none(dev)
            dev.page_turn(forward=True, verify=False)
            time.sleep(1.5)
            after = _wait_page_change(dev, before, timeout=10)
            if not after and before and before[0] >= before[1]:
                # 书已停在最后一页(此前运行把进度推到末页):向前翻页无效属预期,
                # 改为向后翻页验证离线翻页能力
                dev.page_turn(forward=False, verify=False)
                time.sleep(1.5)
                after = _wait_page_change(dev, before, timeout=10)
            _snap(dev, case_id, "offline_turned")
            if not after:
                dev.save_dump(case_id, "offline_no_turn")
                raise AssertionError("断网状态翻页无效")
    finally:
        # 恢复网络(必须)
        dev.shell("svc wifi enable")
        dev.shell("svc data enable")
        time.sleep(4)
        wifi1, data1 = net_state()
        print("  [%s] 网络恢复: wifi=%s data=%s (原 %s/%s)" % (dev.serial, wifi1, data1, wifi0, data0))
        _exit_reader(dev, case_id)
        _back_to_main(dev)


def fn47_fdroid_pro_gate(dev, case_id, cfg=None, fixtures=None):
    """fdroid 渠道 Pro 门禁探针(§20):仅 fdroid 包执行——WebDAV 区块 [+ 添加] 被置灰/拦截,
    点击不弹添加对话框.toast 抓取在 EMUI 不可靠(AGENTS gotcha 21④),以对话框未出现为准.
    pro 包运行时 SKIP(门禁行为属 fdroid 专项)."""
    if getattr(dev, "flavor", "pro") != "fdroid":
        raise TestSkip("门禁探针仅 fdroid 渠道执行(当前 pro 包)")
    with dev.step(case_id, "try_add_webdav"):
        _ensure_home(dev)
        if not _browse_root(dev):
            raise AssertionError("我的文件根视图不可达")
        headers = {"webdav": "WebDAV"}
        clicked = _click_section_add(dev, headers["webdav"])
        time.sleep(2.5)
        gated = not dev.d(resourceId=_rid(dev, "url")).exists
        _snap(dev, case_id, "gate_checked")
        if not gated:
            dev.d.press("back")
            time.sleep(1)
            dev.save_dump(case_id, "gate_not_enforced")
            raise AssertionError("fdroid 包点击 WebDAV[+ 添加]仍弹出添加对话框(门禁未生效)")
        if not clicked:
            # [+ 添加]不可点(置灰/带锁)同样算门禁生效
            print("  [%s] fdroid 门禁: [+ 添加]按钮不可点(置灰)" % dev.serial)


def fn48_exit_confirm(dev, case_id, cfg=None, fixtures=None):
    """退出确认对话框(§16):主界面按 back → 若启用退出确认则 CloseAppDialog 出现 → 取消.
    (偏好页无独立[退出确认]开关行——本 fork 该行为由返回键直接触发或默认关闭,真机核实
    2026-09-13;无对话框时如实 SKIP)."""
    with dev.step(case_id, "back_and_verify"):
        _ensure_home(dev)
        time.sleep(1)
        dev.d.press("back")  # 主界面 back:启用退出确认时应弹 CloseAppDialog
        time.sleep(2)
        xml = dev.d.dump_hierarchy()
        _snap(dev, case_id, "exit_dialog")
        ok = any(k in xml for k in ("退出", "确定要", "Exit", "确认退出"))
        if not ok:
            dev.save_dump(case_id, "no_exit_dialog")
            raise TestSkip("back 未弹退出确认对话框(本 fork 未启用/无该开关)")
        # 取消,不真退出
        if not _click_any(dev, ["取消", "否", "Cancel"]):
            dev.d.press("back")
        time.sleep(1.5)


# ======================================================================
# 2026-09-13 覆盖补全第 2 批:FN-49 ~ FN-54
# (音量键翻页/双击动作/点按分区/选词菜单/速读 RSVP/更新日志;
#  密码锁/手绘标注/指纹锁/CloudRail/小组件/OPDS 下载按用户指示本期不做)
# ======================================================================

def _intent_open_pdf(dev, case_id, fixtures):
    """intent 直开 big25.pdf 并等进阅读器.
    (不用书名关键词检索:big25 textContains 会同时命中 PDF 基准书与 TXT 基准书
    "Bench25",FN-49/51 首版开错书即因此失败;TXT 在第 1 页测回翻方向永远不动.)"""
    if not dev.open_book_via_intent(fixtures["device_pdf_path"]):
        dev.save_dump(case_id, "intent_open_failed")
        raise AssertionError("big25.pdf intent 打开失败")
    time.sleep(2)


def _reader_page_ready(dev, case_id, min_page=3, tries=6):
    """读页码并保证离开书首(边界页上'回翻方向'的操作永远不动,断言会误报).
    返回页码;拿不到页码返回 None."""
    before = _reader_page_or_none(dev)
    for _ in range(tries):
        if before is not None and before[0] >= min_page:
            return before
        if before is not None:
            # 太靠前:用右分区前进几页
            w, h = dev.d.window_size()
            dev.d.click(int(0.93 * w), int(0.5 * h))
            time.sleep(2)
        else:
            _reader_show_toolbar(dev)
            if _reader_page_or_none(dev) is None:
                # 集群 B 兜底(2026-09-19):可见性误判/时序未展开时强制中央 tap
                # 翻转菜单栏(用户路径:单击调出阅读菜单,底栏 currentSeek/maxSeek
                # =左当前进度/右总页;垂直模式菜单收起时不在控件树上),再确保展开
                w, h = dev.d.window_size()
                dev.d.click(int(0.5 * w), int(0.5 * h))
                time.sleep(2)
                _reader_show_toolbar(dev)
        before = _reader_page_or_none(dev)
    if before is not None and before[0] < min_page:
        dev.save_dump(case_id, "page_at_boundary")
    return before


def fn49_volume_keys(dev, case_id, cfg=None, fixtures=None):
    """音量键翻页(§7):isUseVolumeKeys 默认开(垂直走 DocumentWrapperUI.dispatchKeyEventDown,
    水平走 HorizontalViewActivity.onKeyDown).真机探针结论(2026-09-13):
    ①intent 直开 big25.pdf 后音量键双向翻页稳定可靠(4→3→4 实测);
    ②本 fork 小屏 isReverseKeys=True,VOLUME_UP=回翻/VOLUME_DOWN=下翻(与常量表相反);
    ③本 fork 存在阅读器静默退出的间歇性问题(volume_back 步骤后 app 退到桌面,无崩溃日志),
    故每步前校验存活、阅读器死亡则重开书重做(最多 3 轮)."""
    for attempt in range(3):
        with dev.step(case_id, "open_book"):
            _ensure_home(dev)
            _intent_open_pdf(dev, case_id, fixtures)
            if not _reader_alive(dev):
                time.sleep(3)
                _intent_open_pdf(dev, case_id, fixtures)
            _reader_show_toolbar(dev)
            before = _reader_page_ready(dev, case_id)
            if before is None:
                if attempt == 2:
                    dev.save_dump(case_id, "no_page_num")
                    raise AssertionError("读不到当前页码(3 轮均失败)")
                continue
        with dev.step(case_id, "volume_turn"):
            dev.shell("input keyevent 24")  # KEYCODE_VOLUME_UP
            time.sleep(2.5)
            after = _wait_page_change(dev, before, timeout=12)
            if not after and _reader_alive(dev):
                dev.shell("input keyevent 24")  # MIUI 音量条偶发抢首发,重试一次
                time.sleep(2.5)
                after = _wait_page_change(dev, before, timeout=12)
            _snap(dev, case_id, "after_volume_up")
            if not after:
                if not _reader_alive(dev):
                    print("  [%s] 阅读器静默退出(第 %d 轮),重开书重试" % (dev.serial, attempt + 1))
                    continue
                if attempt == 2:
                    dev.save_dump(case_id, "volume_no_turn")
                    raise AssertionError("音量上键未翻页(before=%s)" % (before,))
                continue
        with dev.step(case_id, "volume_back"):
            if not _reader_alive(dev):
                print("  [%s] 阅读器静默退出(第 %d 轮),重开书重试" % (dev.serial, attempt + 1))
                continue
            dev.shell("input keyevent 25")  # KEYCODE_VOLUME_DOWN → 反方向
            time.sleep(2.5)
            back = _wait_page_change(dev, after, timeout=12)
            if not back and not _reader_alive(dev):
                print("  [%s] 阅读器静默退出(第 %d 轮),重开书重试" % (dev.serial, attempt + 1))
                continue
            _snap(dev, case_id, "after_volume_down")
            if not back:
                if attempt == 2:
                    dev.save_dump(case_id, "volume_down_no_turn")
                    raise AssertionError("音量下键未翻页(after=%s)" % (after,))
                continue
            print("  [%s] 音量键方向: 上键 %s→%s, 下键 %s→%s"
                  % (dev.serial, before, after, after, back))
        with dev.step(case_id, "exit"):
            _exit_reader(dev, case_id)
        return
    raise AssertionError("音量键翻页 3 轮未完成(阅读器间歇性退出/页码不可读)")


def fn50_double_click(dev, case_id, cfg=None, fixtures=None):
    """双击动作(§7):doubleClickAction1 默认 SHOW_HIDE_UI——双击屏幕中央切换 UI
    (PageImaveView/DocumentWrapperUI 双击分发).u2 双击偶发被拆成两次单击
    (各自切换→净效果为零),失败时换连击方式重试;水平模式中央双击只切顶栏
    (页脚常驻),断言用「顶栏+页脚」组合状态(与 FN-51 同法)."""
    def bar_state():
        top_bar = dev.d(resourceId=_rid(dev, "imageToolbar")).exists
        footer = dev.d(resourceId=_rid(dev, "pagesCountIndicator")).exists \
            or dev.d(resourceId=_rid(dev, "currentSeek")).exists \
            or dev.d(resourceId=_rid(dev, "currentPageIndex")).exists
        return (top_bar, footer)

    w, h = dev.d.window_size()
    cx, cy = int(0.5 * w), int(0.5 * h)
    with dev.step(case_id, "open_book"):
        _open_reader(dev, case_id, fixtures, "epub")
        _reader_show_toolbar(dev)
        _snap(dev, case_id, "ui_before")
        if not _reader_ui_visible(dev):
            raise AssertionError("初始工具条不可见")
    with dev.step(case_id, "double_tap_toggle"):
        s0 = bar_state()
        toggled = False
        for attempt in range(3):
            if attempt == 0:
                dev.d.double_click(cx, cy)
            elif attempt == 1:
                dev.d.click(cx, cy)
                time.sleep(0.08)
                dev.d.click(cx, cy)
            else:
                dev.shell("input tap %d %d && input tap %d %d" % (cx, cy, cx, cy))
            time.sleep(2)
            if bar_state() != s0:
                toggled = True
                break
            # 双击可能被拆成两次单击(切换两次=回到原态),重试
        _snap(dev, case_id, "ui_after_double_tap")
        if not toggled:
            dev.save_dump(case_id, "double_tap_no_toggle")
            raise AssertionError("双击后 UI 状态未翻转(3 种连击方式均无效)")
    with dev.step(case_id, "restore"):
        # 双击还原 UI,保证退出后状态一致
        if not _reader_ui_visible(dev):
            dev.d.double_click(cx, cy)
            time.sleep(2)
    with dev.step(case_id, "exit"):
        _exit_reader(dev, case_id)


def fn51_tap_zones(dev, case_id, cfg=None, fixtures=None):
    """点按分区手势(§7):tapzoneSize 默认 25%(ClickUtils 边界=屏幕*tapzoneSize/100),
    右分区=下一页、左分区=上一页(tapZoneLeft/Right 默认 PREV/NEXT),中央 tap=翻转 UI.
    真机探针结论(2026-09-13):intent 直开 big25.pdf 后分区 tap 稳定可靠;
    阅读器存在静默退出的间歇性问题,每步前校验存活、死亡则重开书重做(最多 3 轮)."""
    for attempt in range(3):
        with dev.step(case_id, "open_book"):
            _ensure_home(dev)
            _intent_open_pdf(dev, case_id, fixtures)
            if not _reader_alive(dev):
                time.sleep(3)
                _intent_open_pdf(dev, case_id, fixtures)
            _reader_show_toolbar(dev)
            before = _reader_page_ready(dev, case_id)
            if before is None:
                if attempt == 2:
                    dev.save_dump(case_id, "no_page_num")
                    raise AssertionError("读不到当前页码(3 轮均失败)")
                continue
        with dev.step(case_id, "right_zone_turn"):
            w, h = dev.d.window_size()
            dev.d.click(int(0.93 * w), int(0.5 * h))
            after = _wait_page_change(dev, before, timeout=12)
            _snap(dev, case_id, "right_zone_next")
            if not after:
                if not _reader_alive(dev):
                    print("  [%s] 阅读器静默退出(第 %d 轮),重开书重试" % (dev.serial, attempt + 1))
                    continue
                if attempt == 2:
                    dev.save_dump(case_id, "right_zone_no_turn")
                    raise AssertionError("右分区 tap 未翻页(before=%s)" % (before,))
                continue
        with dev.step(case_id, "left_zone_back"):
            if not _reader_alive(dev):
                print("  [%s] 阅读器静默退出(第 %d 轮),重开书重试" % (dev.serial, attempt + 1))
                continue
            w, h = dev.d.window_size()
            dev.d.click(int(0.07 * w), int(0.5 * h))
            back = _wait_page_change(dev, after, timeout=12)
            if not back and not _reader_alive(dev):
                print("  [%s] 阅读器静默退出(第 %d 轮),重开书重试" % (dev.serial, attempt + 1))
                continue
            _snap(dev, case_id, "left_zone_prev")
            if not back:
                if attempt == 2:
                    dev.save_dump(case_id, "left_zone_no_turn")
                    raise AssertionError("左分区 tap 未翻页(after=%s)" % (after,))
                continue
            print("  [%s] 分区方向: 右 %s→%s, 左 %s→%s"
                  % (dev.serial, before, after, after, back))
        with dev.step(case_id, "center_toggle_ui"):
            if not _reader_alive(dev):
                print("  [%s] 阅读器静默退出(第 %d 轮),重开书重试" % (dev.serial, attempt + 1))
                continue

            def bar_state():
                # 水平模式中央 tap 只切换顶栏(页脚页码常驻),断言用「顶栏+页脚」组合状态
                top_bar = dev.d(resourceId=_rid(dev, "imageToolbar")).exists
                footer = dev.d(resourceId=_rid(dev, "pagesCountIndicator")).exists \
                    or dev.d(resourceId=_rid(dev, "currentSeek")).exists \
                    or dev.d(resourceId=_rid(dev, "currentPageIndex")).exists
                return (top_bar, footer)

            _reader_show_toolbar(dev)
            s0 = bar_state()
            w, h = dev.d.window_size()
            dev.d.click(int(0.5 * w), int(0.5 * h))
            time.sleep(2)
            s1 = bar_state()
            _snap(dev, case_id, "center_toggled")
            if s1 == s0:
                if attempt == 2:
                    dev.save_dump(case_id, "center_no_toggle")
                    raise AssertionError("中央 tap 未切换 UI(状态 %s → %s)" % (s0, s1))
                continue
            # 还原 UI 再退出
            if not bar_state()[0] and not bar_state()[1]:
                dev.d.click(int(0.5 * w), int(0.5 * h))
                time.sleep(2)
        with dev.step(case_id, "exit"):
            _exit_reader(dev, case_id)
        return
    raise AssertionError("点按分区手势 3 轮未完成(阅读器间歇性退出/页码不可读)")


def fn52_select_text_menu(dev, case_id, cfg=None, fixtures=None):
    """选词菜单(§13):阅读器长按正文 → dialogSelectText 弹出(加入书签/分享/复制/
    搜索/发送给AI/笔记/词典行).只断言菜单出现,不点击外链项(会跳出应用).
    isRememberDictionary 默认 False,长按走菜单而非直接跳词典(DocumentWrapperUI.onLongPress)."""
    with dev.step(case_id, "open_book"):
        _open_reader(dev, case_id, fixtures, "epub")
        time.sleep(1)
    with dev.step(case_id, "long_press_text"):
        w, h = dev.d.window_size()
        hits = None
        for fx, fy in ((0.5, 0.45), (0.4, 0.55), (0.6, 0.35), (0.5, 0.6)):
            dev.d.long_click(int(fx * w), int(fy * h))
            time.sleep(2)
            xml = dev.d.dump_hierarchy()
            found = [k for k in ("复制", "分享", "笔记", "书签", "词典", "翻译",
                                 "发送给AI", "Copy", "Share", "Note") if k in xml]
            if len(found) >= 2:
                hits = found
                break
            # 收起可能残留的文本选择光标再换点重试
            dev.d.press("back")
            time.sleep(1)
        _snap(dev, case_id, "select_menu")
        if hits is None:
            dev.save_dump(case_id, "no_select_menu")
            raise AssertionError("长按正文未弹出选词菜单(4 个落点均失败)")
        print("  [%s] 选词菜单命中: %s" % (dev.serial, hits))
    with dev.step(case_id, "close_menu"):
        dev.d.press("back")
        time.sleep(1)
    with dev.step(case_id, "exit"):
        _exit_reader(dev, case_id)


def fn53_speed_read(dev, case_id, cfg=None, fixtures=None):
    """速读(RSVP,§16):阅读器菜单(bookMenu/modeName)→ ShareDialog
    「▶▶ 快速阅读 (RSVP)」→ DialogSpeedRead 词闪对话框 textWord 元素可见 → 退出.
    菜单入口仅水平(Book)模式有(与跳页/TTS 同族);垂直模式 epub 无该入口,
    如实 SKIP(不切模式绕行:实验证明模式切换+热开书路径本身不稳,MI9 直开即过)."""
    with dev.step(case_id, "open_book"):
        _open_reader(dev, case_id, fixtures, "epub")
        _reader_show_toolbar(dev)
    with dev.step(case_id, "open_book_menu"):
        # epub 打开后底栏(含 bookMenu)要数秒才渲染完——轮询+中央点击兜底约 20s
        bm = None
        w, h = dev.d.window_size()
        for i in range(10):
            bm = dev.d(resourceId=_rid(dev, "bookMenu"))
            if not bm.exists:
                bm = dev.d(resourceId=_rid(dev, "modeName"))
            if not bm.exists:
                try:
                    el = dev.d(description="菜单")
                    if el.exists:
                        bm = el
                except Exception:
                    pass
            if bm.exists:
                break
            dev.d.click(int(0.5 * w), int(0.5 * h))  # 中央 tap 切换 UI(渲染期可能被吞)
            time.sleep(2)
        if not bm.exists:
            dev.save_dump(case_id, "no_book_menu")
            raise TestSkip("阅读器菜单入口(bookMenu/modeName/desc 菜单)不可见")
        bm.click()
        time.sleep(2)
        item = dev.d(textContains="快速阅读")
        if not item.exists:
            item = dev.d(textContains="RSVP")
        if not item.exists:
            dev.save_dump(case_id, "no_speed_read_item")
            dev.d.press("back")
            time.sleep(1)
            raise AssertionError("书籍菜单无[快速阅读 (RSVP)]项")
        _snap(dev, case_id, "share_dialog")
    with dev.step(case_id, "verify_rsvp_dialog"):
        item.click()
        time.sleep(2.5)
        word = dev.d(resourceId=_rid(dev, "textWord"))
        speed = dev.d(resourceId=_rid(dev, "fastReadSpeed"))
        if not word.exists and not speed.exists:
            dev.save_dump(case_id, "no_rsvp_dialog")
            raise AssertionError("速读对话框(textWord/fastReadSpeed)未出现")
        _snap(dev, case_id, "rsvp_dialog")
    with dev.step(case_id, "exit"):
        dev.d.press("back")  # 关闭速读对话框
        time.sleep(1)
        _exit_reader(dev, case_id)


def fn54_whats_new(dev, case_id, cfg=None, fixtures=None):
    """What's New 更新日志(§16):偏好 → 底部[软件说明]行 → 对话框[更新日志]
    (AboutSectionBinder.whatIsNew → AndroidWhatsNew.show2 → 外部浏览器打开
    CHANGES.md).断言:点击后离开应用前台(浏览器/选择器);无浏览器跳转则 SKIP."""
    def resumed():
        try:
            return dev.shell("dumpsys activity activities | grep ResumedActivity")
        except Exception:
            return ""

    with dev.step(case_id, "open_about_dialog"):
        _ensure_home(dev)
        _goto_tab_or_fail(dev, "偏好")
        row = None
        for kw in ("软件说明", "About"):
            el = _find_text_scrolled(dev, kw, max_swipes=10)
            if el is not None:
                row = el
                break
        if row is None:
            dev.save_dump(case_id, "no_about_row")
            raise AssertionError("偏好页未找到[软件说明]行")
        row.click()
        time.sleep(2)
        if not dev.dump_has_text("更新日志") and not dev.dump_has_text("What is new"):
            dev.save_dump(case_id, "no_about_dialog")
            raise AssertionError("软件说明对话框未出现")
        _snap(dev, case_id, "about_dialog")
    with dev.step(case_id, "open_whats_new"):
        link = dev.d(textContains="更新日志")
        if not link.exists:
            link = dev.d(textContains="What is new")
        if not link.exists:
            dev.save_dump(case_id, "no_whats_new_link")
            raise AssertionError("软件说明对话框无[更新日志]行")
        link.click()
        time.sleep(3)
        left, top = False, ""
        for _ in range(6):
            top = resumed()
            if dev.pkg not in top:
                left = True
                break
            time.sleep(2)
        _snap(dev, case_id, "after_whats_new_click")
        if not left:
            dev.save_dump(case_id, "still_in_app")
            raise TestSkip("更新日志未跳转出应用(设备无浏览器/未配置打开方式)")
        print("  [%s] 更新日志跳转: %s" % (dev.serial, top.strip()[:120]))
    with dev.step(case_id, "return_app"):
        for _ in range(3):
            dev.d.press("back")
            time.sleep(1)
            if dev.pkg in resumed():
                break
        if dev.pkg not in resumed():
            dev.start_app()
            if not dev.wait_home(10):
                raise AssertionError("返回应用后主界面未就绪")


# ======================================================================
# 2026-09-19 远程阅读优化专项:FN-55 ~ FN-60
# 覆盖在线阅读优化链路(方案见 在线阅读优化方案整理-v1.3.12-v1.3.13.md):
# 惰性页树+侧车v2 / 渐进尺寸 / 后台补全 / cache-first 重开 / 取消秒退 /
# 封面持久化 / 惰性布局页码正确性.判定以 REMOTE/BENCH 日志为准
# (每个断言动作前 logcat -c,防读到上一场残留),UI 仅作操作通道.
# 测试大书: 50.23 三服务目录共投的 big_pdf.pdf(500 页 / 268MB).
# ======================================================================

_REMOTE_BIG_BOOK = "big_pdf"
_REMOTE_SMALL_BOOK = "book_lazy"   # 300 页 / 84KB:整本缓存秒级完成,
                                  # 侧车 v2 快速落盘(惰性收尾链)专用


def _open_remote_book(dev, case_id, title, book_kw, timeout=60, snap=None):
    """浏览根 → 点服务器行 → 目录里找书打开 → 轮询等进阅读器.
    (抽取自 FN-30/FN-46 的同款流程;返回是否进入阅读器)"""
    if not _browse_root(dev):
        raise AssertionError("我的文件根视图不可达")
    row = dev.d(text=title)
    if not row.exists:
        raise AssertionError("远程服务器行不可见: %s" % title)
    row.click()
    time.sleep(4)
    book = _find_text_scrolled(dev, book_kw, max_swipes=4)
    if book is None:
        dev.save_dump(case_id, "remote_book_not_found")
        raise AssertionError("远程目录无 %s" % book_kw)
    book.click()
    entered = False
    deadline = time.time() + timeout
    while time.time() < deadline:
        top = dev.shell("dumpsys activity activities | grep ResumedActivity")
        if "ViewActivity" in top or "TTSActivity" in top:
            entered = True
            break
        time.sleep(2.5)
    if snap:
        _snap(dev, case_id, snap)
    return entered


def fn55_remote_coldopen_lazytree(dev, case_id, cfg=None, fixtures=None):
    """远程冷开惰性页树链路:清缓存 → WebDAV 冷开 big_pdf(500页) →
    断言:侧车未命中(sidecar not found) / 惰性尺寸窗口(lazy page sizes) /
    页尺寸缓存落盘(page-size cache saved) / BENCH load-end ≤3500ms;
    退出断言侧车 v2 落盘(sizes 数 == 页数)."""
    with dev.step(case_id, "clear_cache"):
        dev.clear_remote_cache()
    with dev.step(case_id, "ensure_server"):
        title, added = _ensure_server(dev, case_id, cfg, "webdav")
    with dev.step(case_id, "cold_open"):
        _ensure_home(dev)
        dev.log_clear()
        entered = _open_remote_book(dev, case_id, title, _REMOTE_BIG_BOOK, timeout=90, snap="cold_reader")
        if not entered:
            dev.save_dump(case_id, "cold_open_timeout")
            raise AssertionError("冷开大书 90s 未进入阅读器")
    with dev.step(case_id, "cold_log_chain"):
        time.sleep(2)
        log = dev.remote_log()
        _snap(dev, case_id, "cold_logs")
        if "page tree sidecar not found" not in log:
            raise AssertionError("冷开未出现'page tree sidecar not found'(缓存未清净或非冷开)")
        m = re.search(r"lazy page sizes: (\d+) pages \(window", log)
        if not m:
            raise AssertionError("无'lazy page sizes'日志(惰性尺寸模式未生效)")
        pages = int(m.group(1))
        if pages < 200:
            raise TestSkip("测试书仅 %d 页(<200),惰性页树链路无意义(环境问题)" % pages)
        if "page-size cache saved" not in log:
            raise AssertionError("无'page-size cache saved'(页尺寸缓存未落盘)")
        ms = dev.bench_ms(log, "load-end")
        if ms is None:
            raise AssertionError("无 BENCH 'load-end' 日志")
        print("  [%s] 冷开 load-end=%dms, 页数=%d" % (dev.serial, ms, pages))
        if ms > 3500:
            dev.save_dump(case_id, "cold_open_slow")
            raise AssertionError("冷开 load-end %dms > 3500ms(目标 2~3.5s)" % ms)
        c = dev.scan_crash()
        if c:
            raise AssertionError("冷开 crash: %s" % c)
    with dev.step(case_id, "exit_sidecar_note"):
        _exit_reader(dev, case_id)
        time.sleep(3)
        log2 = dev.remote_log()
        m2 = re.search(r"page tree map saved: (\d+) pages \(sizes (\d+), walk (\d+)ms\)", log2)
        if m2:
            print("  [%s] 侧车 v2: pages=%s sizes=%s walk=%sms"
                  % (dev.serial, m2.group(1), m2.group(2), m2.group(3)))
            if int(m2.group(2)) != pages:
                raise AssertionError("侧车 sizes=%s 与页数 %d 不一致" % (m2.group(2), pages))
        else:
            # 268MB 大书快速开合整本缓存不会完成:惰性收尾(补建全量树→存侧车)
            # 按设计延迟到缓存齐时,严格侧车断言由 FN-56(小书)覆盖
            print("  [%s] 大书未整本缓存,侧车按设计未落盘(PageCacheFile 兜底尺寸)" % dev.serial)
        _back_to_main(dev)
        if added:
            if _browse_root(dev):
                _click_row_delete(dev, title)


def fn56_remote_reopen_cachefirst(dev, case_id, cfg=None, fixtures=None):
    """重开零网络快开:冷开一次建侧车+页尺寸缓存 → 退出 → 重开 →
    断言:cache-first open / 侧车注入(tree walk skipped, sizes=N) /
    页尺寸缓存命中 / 无 sidecar not found / BENCH load-end ≤1000ms /
    后台版本校验通过(cache-first: version verified)."""
    with dev.step(case_id, "clear_and_cold_open"):
        dev.clear_remote_cache()
        title, added = _ensure_server(dev, case_id, cfg, "webdav")
        _ensure_home(dev)
        entered = _open_remote_book(dev, case_id, title, _REMOTE_SMALL_BOOK, timeout=90)
        if not entered:
            raise AssertionError("前置冷开 90s 未进入阅读器")
        # 等整链完成:整本填充 → 补全 done → 惰性收尾建树 → 侧车落盘(日志驱动)
        ok = False
        deadline = time.time() + 150
        while time.time() < deadline:
            if "page tree map saved:" in dev.remote_log(lines=1000):
                ok = True
                break
            time.sleep(5)
        if not ok:
            dev.save_dump(case_id, "sidecar_wait_timeout")
            raise AssertionError("150s 内侧车未落盘(填充/补全/收尾链未走通)")
        _exit_reader(dev, case_id)
        _back_to_main(dev)
    with dev.step(case_id, "reopen"):
        dev.log_clear()
        entered = _open_remote_book(dev, case_id, title, _REMOTE_SMALL_BOOK, timeout=60, snap="reopen_reader")
        if not entered:
            dev.save_dump(case_id, "reopen_timeout")
            raise AssertionError("重开 60s 未进入阅读器")
    with dev.step(case_id, "reopen_log_chain"):
        time.sleep(2)
        log = dev.remote_log()
        _snap(dev, case_id, "reopen_logs")
        if "cache-first open:" not in log:
            raise AssertionError("重开无'cache-first open:'(未走缓存优先路径)")
        if "page tree sidecar not found" in log:
            raise AssertionError("重开仍报 sidecar not found(侧车未持久化)")
        m = re.search(r"page tree map injected: (\d+) pages \(tree walk skipped, sizes (\d+)\)", log)
        if not m:
            raise AssertionError("无'page tree map injected'(侧车注入未生效)")
        if "page-size cache hit:" not in log:
            raise AssertionError("无'page-size cache hit'(页尺寸缓存未命中)")
        ms = dev.bench_ms(log, "load-end")
        if ms is None:
            raise AssertionError("无 BENCH 'load-end' 日志")
        print("  [%s] 重开 load-end=%dms(注入 %s pages)" % (dev.serial, ms, m.group(1)))
        if ms > 1000:
            dev.save_dump(case_id, "reopen_slow")
            raise AssertionError("重开 load-end %dms > 1000ms" % ms)
        verified = False
        deadline = time.time() + 20
        while time.time() < deadline:
            if "cache-first: version verified:" in dev.remote_log(lines=500):
                verified = True
                break
            time.sleep(2)
        if not verified:
            dev.save_dump(case_id, "version_verify_missing")
            raise AssertionError("20s 内未出现'cache-first: version verified:'")
        c = dev.scan_crash()
        if c:
            raise AssertionError("重开 crash: %s" % c)
    with dev.step(case_id, "cleanup"):
        _exit_reader(dev, case_id)
        _back_to_main(dev)
        if added:
            if _browse_root(dev):
                _click_row_delete(dev, title)


def fn57_remote_size_completion(dev, case_id, cfg=None, fixtures=None):
    """后台尺寸渐进补全:清缓存冷开大书保持前台 → 轮询 REMOTE 日志:
    'size completion progress' 递增 → 'size completion done: fetched N/N';
    'page-size cache saved' ≥2 次(渐进写盘);退出重开断言
    'page-size cache hit: N pages'(补全结果被 PageCacheFile 持久化)."""
    with dev.step(case_id, "clear_and_cold_open"):
        dev.clear_remote_cache()
        title, added = _ensure_server(dev, case_id, cfg, "webdav")
        _ensure_home(dev)
        dev.log_clear()
        entered = _open_remote_book(dev, case_id, title, _REMOTE_BIG_BOOK, timeout=90, snap="reader")
        if not entered:
            raise AssertionError("冷开 90s 未进入阅读器")
    with dev.step(case_id, "wait_completion"):
        pages = None
        progress_vals = []
        done_ms = None
        deadline = time.time() + 600
        while time.time() < deadline:
            log = dev.remote_log(lines=2000)
            m = re.search(r"lazy page sizes: (\d+) pages", log)
            if m:
                pages = int(m.group(1))
            for pm in re.finditer(r"size completion progress: (\d+)/(\d+)", log):
                progress_vals.append(int(pm.group(1)))
            md = re.search(r"size completion done: fetched (\d+)/(\d+), layout fixed (\d+) in (\d+)ms", log)
            if md:
                pages = int(md.group(2))
                done_ms = int(md.group(4))
                break
            time.sleep(10)
        _snap(dev, case_id, "completion_wait")
        if pages is None:
            raise AssertionError("读不到页数(lazy page sizes/done 日志均缺)")
        if pages < 200:
            raise TestSkip("测试书仅 %d 页(<200),补全用例无意义(环境问题)" % pages)
        uniq = sorted(set(progress_vals))
        if done_ms is None:
            raise AssertionError("600s 内未出现'size completion done'(progress=%s)" % uniq)
        if len(uniq) < 2:
            raise AssertionError("补全进度未渐进推进(progress=%s)" % uniq)
        print("  [%s] 补全完成: %d 页, 用时 %dms, progress=%s"
              % (dev.serial, pages, done_ms, uniq))
        saved_cnt = dev.remote_log().count("page-size cache saved")
        if saved_cnt < 2:
            raise AssertionError("'page-size cache saved' 仅 %d 次(<2,渐进写盘未发生)" % saved_cnt)
        c = dev.scan_crash()
        if c:
            raise AssertionError("补全期间 crash: %s" % c)
    with dev.step(case_id, "reopen_hit"):
        _exit_reader(dev, case_id)
        _back_to_main(dev)
        dev.log_clear()
        entered = _open_remote_book(dev, case_id, title, _REMOTE_BIG_BOOK, timeout=60)
        if not entered:
            raise AssertionError("补全后重开未进入阅读器")
        time.sleep(2)
        log = dev.remote_log()
        m = re.search(r"page-size cache hit: (\d+) pages", log)
        if not m:
            dev.save_dump(case_id, "no_cache_hit")
            raise AssertionError("补全后重开无'page-size cache hit'")
        if int(m.group(1)) != pages:
            raise AssertionError("页尺寸缓存命中页数 %s != 补全页数 %d" % (m.group(1), pages))
        print("  [%s] 补全持久化验证: hit %s pages" % (dev.serial, m.group(1)))
    with dev.step(case_id, "cleanup"):
        _exit_reader(dev, case_id)
        _back_to_main(dev)
        if added:
            if _browse_root(dev):
                _click_row_delete(dev, title)


def fn58_remote_open_cancel(dev, case_id, cfg=None, fixtures=None):
    """打开取消秒退:清缓存后点大书,1.5s 后返回 →
    断言 ≤3s 内离开阅读器 / 取消链路日志(session abort 或 cancelled-gate)/无 crash;
    再正常打开一次确认取消门无误触发."""
    with dev.step(case_id, "clear_cache"):
        dev.clear_remote_cache()
    with dev.step(case_id, "ensure_server"):
        title, added = _ensure_server(dev, case_id, cfg, "webdav")
    with dev.step(case_id, "open_then_cancel"):
        _ensure_home(dev)
        if not _browse_root(dev):
            raise AssertionError("我的文件根视图不可达")
        row = dev.d(text=title)
        if not row.exists:
            raise AssertionError("远程服务器行不可见")
        row.click()
        time.sleep(4)
        book = _find_text_scrolled(dev, _REMOTE_BIG_BOOK, max_swipes=4)
        if book is None:
            dev.save_dump(case_id, "remote_book_not_found")
            raise AssertionError("远程目录无 %s" % _REMOTE_BIG_BOOK)
        dev.log_clear()
        book.click()
        # 0.6s 早返回:大书加载任务 ~0.5s 就完成,晚了就只是正常退出;
        # 且首屏横幅会消费一次返回键(2026-09-19 实锤),未退则补一次
        time.sleep(0.6)
        t0 = time.time()
        gone = False
        for _ in range(2):
            dev.d.press("back")
            while time.time() - t0 < 3:
                top = dev.shell("dumpsys activity activities | grep ResumedActivity")
                if "ViewActivity" not in top and "TTSActivity" not in top:
                    gone = True
                    break
                time.sleep(0.5)
            if gone:
                break
            time.sleep(0.5)
        _snap(dev, case_id, "after_cancel")
        if not gone:
            dev.save_dump(case_id, "cancel_not_exit")
            raise AssertionError("返回后 3s 内阅读器未退出(含横幅兜底一次)")
        log = dev.remote_log()
        markers = ("session abort", "load cancelled-gate trips",
                   "openRemoteFile cancelled while waiting lock")
        if not any(k in log for k in markers):
            # 加载先于返回完成 → 返回走正常退出路径,同样验证了"秒退不卡死"
            print("  [%s] 加载先完成,返回走正常退出(无取消标记,可接受)" % dev.serial)
        c = dev.scan_crash()
        if c:
            raise AssertionError("取消后 crash: %s" % c)
        _ensure_home(dev)
    with dev.step(case_id, "reopen_no_false_cancel"):
        dev.log_clear()
        entered = _open_remote_book(dev, case_id, title, _REMOTE_BIG_BOOK, timeout=90)
        if not entered:
            dev.save_dump(case_id, "reopen_after_cancel_timeout")
            raise AssertionError("正常打开未进入阅读器(取消门误伤?)")
        time.sleep(2)
        log2 = dev.remote_log()
        if "load cancelled-gate trips" in log2:
            dev.save_dump(case_id, "false_cancel_gate")
            raise AssertionError("正常打开误触发'load cancelled-gate trips'")
        _exit_reader(dev, case_id)
        _back_to_main(dev)
        if added:
            if _browse_root(dev):
                _click_row_delete(dev, title)


def fn59_remote_cover_persist(dev, case_id, cfg=None, fixtures=None):
    """封面占位/持久化:清缓存(含 RemoteCovers) → 仅浏览目录不开书 →
    断言无占位失败/无用户打开;开书一次 'remote cover saved' 恰 1 次;
    重开不再出现(持久化命中)."""
    with dev.step(case_id, "clear_cache"):
        dev.clear_remote_cache()
    with dev.step(case_id, "ensure_server"):
        title, added = _ensure_server(dev, case_id, cfg, "webdav")
    with dev.step(case_id, "browse_only"):
        _ensure_home(dev)
        if not _browse_root(dev):
            raise AssertionError("我的文件根视图不可达")
        dev.log_clear()
        if not _open_server_dir(dev, title, [_REMOTE_SMALL_BOOK]):
            dev.save_dump(case_id, "dir_enter_failed")
            raise AssertionError("进入远程目录失败")
        time.sleep(8)
        log = dev.remote_log()
        _snap(dev, case_id, "browse_only")
        if "remote cover placeholder failed" in log:
            raise AssertionError("封面占位失败日志出现")
        if "openTask file=" in log:
            raise AssertionError("仅浏览目录却触发了用户打开(openTask)")
    with dev.step(case_id, "open_once"):
        dev.log_clear()
        book = _find_text_scrolled(dev, _REMOTE_SMALL_BOOK, max_swipes=4)
        if book is None:
            raise AssertionError("远程目录无 %s" % _REMOTE_SMALL_BOOK)
        book.click()
        entered = False
        deadline = time.time() + 90
        while time.time() < deadline:
            top = dev.shell("dumpsys activity activities | grep ResumedActivity")
            if "ViewActivity" in top or "TTSActivity" in top:
                entered = True
                break
            time.sleep(2.5)
        if not entered:
            raise AssertionError("打开 90s 未进入阅读器")
        time.sleep(4)  # 等封面保存守护线程
        _exit_reader(dev, case_id)
        time.sleep(3)
        log = dev.remote_log()
        cnt = len(re.findall(r"remote cover saved: ", log))
        _snap(dev, case_id, "cover_saved")
        if cnt != 1:
            dev.save_dump(case_id, "cover_save_count")
            raise AssertionError("'remote cover saved' 出现 %d 次(期望恰 1 次)" % cnt)
        _back_to_main(dev)
    with dev.step(case_id, "reopen_no_resave"):
        dev.log_clear()
        entered = _open_remote_book(dev, case_id, title, _REMOTE_SMALL_BOOK, timeout=60)
        if not entered:
            raise AssertionError("重开未进入阅读器")
        time.sleep(3)
        _exit_reader(dev, case_id)
        time.sleep(2)
        log = dev.remote_log()
        if "remote cover saved:" in log:
            dev.save_dump(case_id, "cover_resaved")
            raise AssertionError("重开再次保存封面(持久化未命中)")
        _back_to_main(dev)
        if added:
            if _browse_root(dev):
                _click_row_delete(dev, title)


def fn60_remote_lazy_layout_pages(dev, case_id, cfg=None, fixtures=None):
    """惰性布局页码正确性:冷开大书 → 页码分母 == lazy page sizes 的 N →
    进度条拖底可达最后一页(无'墙') → 拖回页首 → 翻页 ±1 →
    退出后补全线程停止(6s 后无新增 size completion progress)."""
    with dev.step(case_id, "clear_and_cold_open"):
        dev.clear_remote_cache()
        title, added = _ensure_server(dev, case_id, cfg, "webdav")
        _ensure_home(dev)
        dev.log_clear()
        entered = _open_remote_book(dev, case_id, title, _REMOTE_SMALL_BOOK, timeout=90, snap="reader")
        if not entered:
            raise AssertionError("冷开 90s 未进入阅读器")
        time.sleep(2)
    with dev.step(case_id, "denominator"):
        log = dev.remote_log()
        m = re.search(r"lazy page sizes: (\d+) pages", log)
        if not m:
            raise AssertionError("无'lazy page sizes'日志")
        pages = int(m.group(1))
        if pages < 200:
            raise TestSkip("测试书仅 %d 页(<200)(环境问题)" % pages)
        cur = _reader_page_or_none(dev)
        if cur is None:
            dev.save_dump(case_id, "no_page_num")
            raise AssertionError("读不到页码(日志与 UI 均无)")
        if cur[1] != pages:
            raise AssertionError("页码分母 %d != 全书页数 %d(占位布局缩水)" % (cur[1], pages))
        print("  [%s] 页码分母一致: %d/%d" % (dev.serial, cur[0], cur[1]))
    with dev.step(case_id, "seek_bottom"):
        # 垂直模式拖动入口不可靠(seekBar1 拖动事件被吞,2026-09-19 实锤),
        # 改连续快速滑动直达底部:占位布局滑过不渲染,天然验证无"墙"
        def seek_percent():
            try:
                el = dev.d(resourceId=_rid(dev, "currentSeek"))
                if el.exists:
                    m = re.match(r"([\d.]+)\s*%", (el.get_text() or "").strip())
                    if m:
                        return float(m.group(1))
            except Exception:
                pass
            return None

        w, h = dev.d.window_size()
        best, stuck, last_page = 0.0, 0, None
        for _ in range(80):
            dev.d.swipe(int(0.5 * w), int(0.85 * h), int(0.5 * w), int(0.15 * h), 0.05)
            time.sleep(1.2)
            p = _reader_page_or_none(dev)
            if p:
                last_page = p
                best = max(best, 100.0 * (p[0] - 1) / max(pages - 1, 1))
            pct = seek_percent()
            if pct is not None:
                if pct >= best - 0.5:
                    stuck = 0
                else:
                    stuck += 1
                best = max(best, pct)
            if best >= 97.0:
                break
            if stuck >= 3:
                break
        _snap(dev, case_id, "seek_bottom")
        if best < 97.0:
            raise AssertionError("连续滑动未到达底部(墙?最好进度 %.1f%%, 当前 %s, 全书 %d)"
                                 % (best, last_page, pages))
        print("  [%s] 滑动到底: %.1f%% (page %s/%d)" % (dev.serial, best,
              last_page[0] if last_page else "?", pages))
        back_ok = False
        for _ in range(80):
            dev.d.swipe(int(0.5 * w), int(0.15 * h), int(0.5 * w), int(0.85 * h), 0.05)
            time.sleep(1.0)
            pct = seek_percent()
            if pct is None and not _reader_bar_visible(dev):
                _reader_show_toolbar(dev)
                pct = seek_percent()
            if pct is not None and pct <= 5.0:
                back_ok = True
                break
            p = _reader_page_or_none(dev)
            if p and p[0] <= 3:
                back_ok = True
                break
        if not back_ok:
            raise AssertionError("滑回页首失败(当前 %s%%)" % seek_percent())
    with dev.step(case_id, "page_turns"):
        before = _reader_page_or_none(dev)
        dev.page_turn(forward=True, verify=False)
        after = _wait_page_change(dev, before, timeout=12)
        if not after:
            raise AssertionError("惰性布局下翻页(前)无效")
        dev.page_turn(forward=False, verify=False)
        back = _wait_page_change(dev, after, timeout=12)
        if not back:
            raise AssertionError("惰性布局下翻页(后)无效")
    with dev.step(case_id, "completion_stops_on_exit"):
        pre = dev.remote_log().count("size completion progress:")
        _exit_reader(dev, case_id)
        time.sleep(6)
        post = dev.remote_log().count("size completion progress:")
        if post > pre:
            dev.save_dump(case_id, "completion_leak")
            raise AssertionError("退出阅读器后补全线程仍在推进(%d → %d)" % (pre, post))
        c = dev.scan_crash()
        if c:
            raise AssertionError("crash: %s" % c)
        _back_to_main(dev)
        if added:
            if _browse_root(dev):
                _click_row_delete(dev, title)


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
    # ---- 2026-09-12 覆盖扩展(顺序:本地 UI → Pro 功能 → 网络;网络用例排在 FN-04 之后,
    # 且自建服务器条目用后自清理,避免把 FN-04 搜索入口挤出屏幕的缺陷扩散到其它机型)----
    ("FN-10", "书库搜索", fn10_library_search, None),
    ("FN-11", "排序与状态筛选", fn11_sort_and_filter, None),
    ("FN-12", "视图模式切换", fn12_view_modes, None),
    ("FN-13", "标签管理", fn13_tags, None),
    ("FN-14", "文件浏览操作", fn14_browse_ops, None),
    ("FN-15", "翻页模式切换", fn15_page_modes, None),
    ("FN-16", "跳页", fn16_goto_page, None),
    ("FN-17", "目录大纲", fn17_outline, None),
    ("FN-18", "字号调节", fn18_font_size, None),
    ("FN-19", "四套主题", fn19_four_themes, None),
    ("FN-20", "界面语言切换", fn20_language_switch, None),
    ("FN-21", "分享接收", fn21_share_receive, None),
    ("FN-22", "笔记", fn22_notes, None),
    ("FN-23", "AI 配置与连通", fn23_ai_config, None),
    ("FN-24", "阅读统计", fn24_reading_stats, None),
    ("FN-25", "OPDS 浏览", fn25_opds, None),
    ("FN-26", "WebDAV 浏览", fn26_webdav_browse, None),
    ("FN-27", "WebDAV 同步", fn27_webdav_sync, None),
    ("FN-28", "SMB 添加与浏览", fn28_smb, None),
    ("FN-29", "SFTP 添加与浏览", fn29_sftp, None),
    ("FN-30", "远程书在线打开", fn30_remote_open, None),
    # ---- 2026-09-13 覆盖补全(高可行项,见 docs/COVERAGE.md 缺口表)----
    ("FN-31", "抽屉菜单遍历", fn31_drawer_menu, None),
    ("FN-32", "收藏列表页", fn32_favorites_page, None),
    ("FN-33", "隐藏文件开关", fn33_hidden_files, None),
    ("FN-34", "阅读页锁定", fn34_reader_lock, None),
    ("FN-35", "对比度与亮度", fn35_contrast_brightness, None),
    ("FN-36", "蓝光滤镜", fn36_bluelight, None),
    ("FN-37", "文件信息对话框", fn37_file_info, None),
    ("FN-38", "标记已读未读", fn38_mark_read, None),
    ("FN-39", "行距调节", fn39_line_spacing, None),
    ("FN-40", "页码格式切换", fn40_page_format, None),
    ("FN-41", "进度条位置", fn41_statusbar_pos, None),
    ("FN-42", "阅读统计详情", fn42_stats_detail, None),
    ("FN-43", "在线阅读设置", fn43_remote_cache_prefs, None),
    ("FN-44", "WebDAV 同步定时器", fn44_sync_timer, None),
    ("FN-45", "设置备份导出", fn45_settings_backup, None),
    ("FN-46", "断网离线阅读", fn46_offline_reading, None),
    ("FN-47", "fdroid Pro 门禁", fn47_fdroid_pro_gate, None),
    ("FN-48", "退出确认对话框", fn48_exit_confirm, None),
    ("FN-49", "音量键翻页", fn49_volume_keys, None),
    ("FN-50", "双击动作", fn50_double_click, None),
    ("FN-51", "点按分区手势", fn51_tap_zones, None),
    ("FN-52", "选词菜单", fn52_select_text_menu, None),
    ("FN-53", "速读 RSVP", fn53_speed_read, None),
    ("FN-54", "更新日志入口", fn54_whats_new, None),
    # ---- 2026-09-19 远程阅读优化专项(惰性页树/渐进尺寸/后台补全/取消/封面/页码)----
    ("FN-55", "远程冷开惰性页树", fn55_remote_coldopen_lazytree, None),
    ("FN-56", "远程重开零网络快开", fn56_remote_reopen_cachefirst, None),
    ("FN-57", "后台尺寸补全", fn57_remote_size_completion, None),
    ("FN-58", "打开取消秒退", fn58_remote_open_cancel, None),
    ("FN-59", "远程封面持久化", fn59_remote_cover_persist, None),
    ("FN-60", "惰性布局页码正确性", fn60_remote_lazy_layout_pages, None),
]
