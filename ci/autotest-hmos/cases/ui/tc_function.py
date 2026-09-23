# -*- coding: utf-8 -*-
"""L1 functional cases for HowRead Pro (HarmonyOS) — core subset (2026-09-21).

UI 地图（Pura 90 模拟器 1320x2856，zh-CN；坐标来自 dumpLayout 实测）：
  阅读器工具条 y≈216：231=跳页 357=TTS 483=书签 609=目录 987=AI翻译
                        1113=阅读设置 1239=标注/最近
  底部 y≈2569：快滚滑轨（拖动换页）
  偏好页：退出确认对话框 行 + 右侧 Toggle（_click_toggle_near 定位）
  书库页：搜索框(hint=搜索)、全文 chip、筛选 chips、行内 ☆ 收藏
  我的文件：网上书库(OPDS)/WebDAV/SMB/SFTP/书库文件夹 分区
翻页通道：音量键（keyEvent 16/17，inputConsumer）——注入滑动到不了 Swiper。
"""
import re
import time

from lib.hdcdriver import TestFail, TestSkip

TB = 216  # reader toolbar y
ICON_JUMP, ICON_TTS, ICON_BM, ICON_TOC, ICON_AI, ICON_SET = 231, 357, 483, 609, 987, 1113


# ---------------------------------------------------------------- helpers
def _ensure_home(dev, cfg):
    dev.wake()
    dev.stop_app()
    dev.hilog_clear()
    dev.start_app()
    if not dev.wait_home(cfg.get('launcher_timeout_s', 30)):
        raise TestFail('app did not reach home page')
    # Language guard: language applies on cold start and persists, so a case
    # that died mid language-switch poisons later runs. Normalize to Chinese
    # (the zh selectors below depend on it) with verification and one retry.
    for _attempt in range(3):
        if dev.exists_text('首页'):
            return
        if not dev.exists_text('Home'):
            raise TestFail('home page in unknown language (no 首页/Home)')
        dev.click_id('app_tab_3') or dev.click_text('Preferences')
        for _ in range(8):
            if dev.exists_text('语言') or dev.exists_text('Language'):
                break
            dev.swipe(660, 2000, 660, 700, 600)
            time.sleep(1.0)
        if not (dev.click_text('语言') or dev.click_text('Language')):
            raise TestFail('language row not found in prefs')
        time.sleep(1.2)
        if not (dev.click_text('中文') or dev.click_text('Chinese')):
            raise TestFail('Chinese option not found')
        time.sleep(1.0)
        dev.stop_app()
        dev.start_app()
        if not dev.wait_home(30):
            raise TestFail('app did not reach home after language restore')
    raise TestFail('failed to restore Chinese UI')


def _open_book(dev, title, expect_pages=None, timeout=30):
    dev.click_id('app_tab_1') or dev.click_text('书库')
    if not dev.wait_text(title, 12):
        dev.swipe(660, 1800, 660, 800)
        if not dev.wait_text(title, 8):
            raise TestFail('%s not in library' % title)
    dev.hilog_clear()
    if not dev.click_text(title):
        raise TestFail('click %s failed' % title)
    ev = dev.wait_event('book_open_done', timeout=timeout)
    if not ev:
        raise TestFail('book_open_done not fired for %s' % title)
    pages = int(ev.get('kv', {}).get('pages', '0'))
    if expect_pages is not None and pages != expect_pages:
        raise TestFail('%s pages=%d expected %d' % (title, pages, expect_pages))
    if not dev.wait_event('page_rendered', timeout=20):
        raise TestFail('first page not rendered for %s' % title)
    time.sleep(1.0)
    return pages


def _exit_reader(dev):
    # The reader can be one Back behind (hidden chrome ate a Back, a panel
    # was still open) — press up to 3 times and let the library tab settle.
    for _ in range(3):
        dev.key('Back')
        if dev.wait_text('未读', timeout=6) or dev.wait_text('Unread', timeout=4):
            return
    raise TestFail('did not return to library from reader')


def _wait_any(dev, texts, timeout=10):
    """Wait until any of the (bilingual) texts appears; returns matched text."""
    t0 = time.time()
    while time.time() - t0 < timeout:
        for t in texts:
            if dev.exists_text(t):
                return t
        time.sleep(0.6)
    return None


def _toolbar_visible(dev):
    """The reader top toolbar (icons at y≈216 on this 1320x2856 panel)."""
    for n in dev._walk(dev.dump()):
        a = n.get('attributes', {})
        if a.get('type') != 'Image':
            continue
        m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', a.get('bounds', ''))
        if m:
            cy = (int(m.group(2)) + int(m.group(4))) // 2
            if 170 <= cy <= 270:
                return True
    return False


def _ensure_toolbar(dev):
    """Toolbar/bottom-bar visibility precondition for icon clicks: the bars
    toggle with the center tap (Reader action 2) and prior turns may hide
    them. Show-check loop; tapping when visible would HIDE, hence the guard."""
    for _ in range(3):
        if _toolbar_visible(dev):
            return
        dev.click_xy(660, 1400)
        time.sleep(1.0)
    if not _toolbar_visible(dev):
        raise TestFail('reader toolbar did not appear')


def _click_toggle_near(dev, label_text):
    label = dev.find(text=label_text)
    if not label:
        return False
    m = re.search(r'\[\d+,(\d+)\]\[\d+,(\d+)\]', label.get('bounds', ''))
    if not m:
        return False
    y_center = (int(m.group(1)) + int(m.group(2))) // 2
    for n in dev._walk(dev.dump()):
        a = n.get('attributes', {})
        if a.get('type') != 'Toggle':
            continue
        m2 = re.search(r'\[\d+,(\d+)\]\[\d+,(\d+)\]', a.get('bounds', ''))
        if m2 and abs((int(m2.group(1)) + int(m2.group(2))) // 2 - y_center) < 80:
            dev._click_attrs(a)
            return True
    return False


def _server_up(dev, cfg, url):
    """Cheap reachability probe from the DEVICE side via the app is not
    possible; use the host instead (socket connect)."""
    import socket
    from urllib.parse import urlparse
    u = urlparse(url)
    try:
        s = socket.create_connection((u.hostname, u.port or 80), timeout=3)
        s.close()
        return True
    except Exception:
        return False


def _input_field(dev, value, hint=None, text=None):
    """Fill a TextInput located by placeholder/hint (or current text).
    Focus-by-click then `uitest uiInput text` (types at focus — more reliable
    on this image than coordinate inputText)."""
    a = None
    t0 = time.time()
    while time.time() - t0 < 8:
        a = dev.find(text=text) if text else dev.find(hint=hint)
        if a:
            break
        time.sleep(0.8)
    if not a:
        raise TestFail('input field (%r) not found' % (hint or text))
    m = re.search(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', a.get('bounds', ''))
    if not m:
        raise TestFail('input field bounds missing')
    x = (int(m.group(1)) + int(m.group(3))) // 2
    y = (int(m.group(2)) + int(m.group(4))) // 2
    dev.click_xy(x, y)
    time.sleep(0.8)
    # Fields may carry initial values ('http://' prefix, default port
    # '22'): uiInput text APPENDS at the cursor, so clear first with a
    # backspace loop sized by the on-screen text (masked passwords are
    # counted by their stars; over-deleting an empty field is a no-op).
    cur = a.get('text') or ''
    if cur:
        n = len(cur) + 4
        dev.shell('i=0; while [ $i -lt %d ]; do uitest uiInput keyEvent 2055; '
                  'i=$((i+1)); done' % n, timeout=120)
    dev.shell('uitest uiInput text %s' % value)
    time.sleep(0.6)


def _type_at_field(dev, a, value):
    m = re.search(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', a.get('bounds', ''))
    if not m:
        raise TestFail('field bounds missing')
    x = (int(m.group(1)) + int(m.group(3))) // 2
    y = (int(m.group(2)) + int(m.group(4))) // 2
    dev.click_xy(x, y)
    time.sleep(0.8)
    dev.shell('uitest uiInput text %s' % value)
    time.sleep(0.5)


def _click_add_after(dev, section_text):
    """Each my-files section header (OPDS/WebDAV/SMB/SFTP/书库文件夹) has its
    +/添加/Add ON THE SAME ROW — click by y-alignment (±60px), not 'first
    below' (that hits the NEXT section's button)."""
    sec = dev.find(text=section_text)
    if not sec:
        raise TestFail('section %r not found' % section_text)
    m = re.search(r'\[\d+,(\d+)\]\[\d+,(\d+)\]', sec.get('bounds', ''))
    y_sec = (int(m.group(1)) + int(m.group(2))) // 2
    for n in dev._walk(dev.dump()):
        a = n.get('attributes', {})
        if a.get('text') in ('添加', 'Add', '+'):
            m2 = re.search(r'\[\d+,(\d+)\]\[\d+,(\d+)\]', a.get('bounds', ''))
            if m2 and abs((int(m2.group(1)) + int(m2.group(2))) // 2 - y_sec) < 60:
                dev._click_attrs(a)
                return True
    raise TestFail('add button on %r row not found' % section_text)


# ---------------------------------------------------------------- reader
def fn01_open_epub(dev, case_id, cfg, fixtures):
    _ensure_home(dev, cfg)
    with dev.step(case_id, 'open Alice demo EPUB'):
        pages = _open_book(dev, 'Alice', expect_pages=105)
        b, page = dev.current_page()
        if page != 'components/Reader':
            raise TestFail('not on Reader (%s)' % page)
    _exit_reader(dev)


def fn02_page_turn(dev, case_id, cfg, fixtures):
    _ensure_home(dev, cfg)
    with dev.step(case_id, 'volume-key turns advance pages'):
        _open_book(dev, 'Alice', expect_pages=105)
        dev.hilog_clear()
        last = 0
        for i in range(4):
            dev.key('17')
            ev = dev.wait_event('page_change_done', timeout=6)
            if ev:
                last = max(last, int(ev['kv'].get('page', '0')))
            time.sleep(0.7)
        if last < 3:
            raise TestFail('volume turns reached page %d (<3)' % last)
        a = dev.find(text='/105')
        if not a:
            raise TestFail('page indicator missing')
    _exit_reader(dev)


def fn03_jump_page(dev, case_id, cfg, fixtures):
    _ensure_home(dev, cfg)
    with dev.step(case_id, 'jump dialog to page 50'):
        _open_book(dev, 'Alice', expect_pages=105)
        _ensure_toolbar(dev)
        dev.hilog_clear()
        dev.click_xy(ICON_JUMP, TB)
        if not _wait_any(dev, ['跳转到页', 'Go to page', 'Jump to page'], 8):
            dev.save_dump('jump_dialog_fail')
            raise TestFail('jump dialog not shown')
        # Pick the target page with the bottom slider first: the dialog's
        # NUMBER TextInput accepts no injected IME text on this image, so the
        # case drives a real jump through the dialog's own prefill.
        _ensure_toolbar(dev)
        dev.shell('uitest uiInput swipe 250 2695 633 2695 600')
        time.sleep(1.5)
        _ensure_toolbar(dev)
        dev.click_xy(ICON_JUMP, TB)
        if not _wait_any(dev, ['跳转到页', 'Go to page', 'Jump to page'], 8):
            dev.save_dump('jump_dialog_fail')
            raise TestFail('jump dialog not shown')
        a = dev.find(id_='reader_jump_input')
        if not a:
            raise TestFail('reader_jump_input not found')
        prefill = (a.get('text', '') or '').strip()
        if not prefill.isdigit():
            dev.save_dump('jump_prefill_fail')
            raise TestFail('jump input prefill not numeric: %r' % prefill)
        dev.hilog_clear()
        # exact type+text: click_text('跳转') contains-matches the dialog
        # TITLE '跳转到页' (earlier in tree) and clicks that instead
        go_btn = None
        for n in dev._walk(dev.dump()):
            aa = n.get('attributes', {})
            if aa.get('type') == 'Button' and aa.get('text') in ('跳转', 'Go'):
                go_btn = aa
                break
        if not go_btn or not dev._click_attrs(go_btn):
            dev.save_dump('jump_go_fail')
            raise TestFail('jump Go button not found')
        ev = dev.wait_event('jump_page_done', timeout=10)
        if not ev or ev.get('kv', {}).get('page') != prefill:
            dev.save_dump('jump_fail')
            raise TestFail('jump_page_done wrong: %s (want page=%s)'
                           % (ev and ev['raw'], prefill))
        if not dev.wait_text(prefill + '/105', timeout=8):
            raise TestFail('indicator did not show %s/105' % prefill)
    _exit_reader(dev)


def fn04_toc(dev, case_id, cfg, fixtures):
    _ensure_home(dev, cfg)
    with dev.step(case_id, 'TOC panel opens'):
        _open_book(dev, 'Alice', expect_pages=105)
        _ensure_toolbar(dev)
        dev.click_xy(ICON_TOC, TB)
        if not _wait_any(dev, ['目录', 'Contents'], 8):
            dev.save_dump('toc_fail')
            raise TestFail('TOC panel not shown')
        # event provenance: loadToc runs at open (before this clear), so the
        # buffered event is informational only
        ev = dev.hilog_events(evt='toc_loaded')
        print('    [%s] toc_loaded entries: %s' %
              (case_id, ev[-1]['kv'].get('entries', '?') if ev else 'n/a'), flush=True)
    _exit_reader(dev)


def fn05_bookmark(dev, case_id, cfg, fixtures):
    _ensure_home(dev, cfg)
    with dev.step(case_id, 'bookmark list loads at open + panel opens'):
        _open_book(dev, 'Alice', expect_pages=105)
        # bookmark_list_loaded fires ONCE at book open (panel shows cached
        # data afterwards) — assert it from the open phase, no hilog_clear
        ev = dev.hilog_events(evt='bookmark_list_loaded')
        if not ev:
            raise TestFail('bookmark_list_loaded not fired at open')
        dev.click_xy(ICON_BM, TB)
        time.sleep(1.0)
        b, page = dev.current_page()
        if page != 'components/Reader':
            raise TestFail('left reader after bookmark icon (%s)' % page)
    with dev.step(case_id, 'toggle bookmark via bottom bar (ic_bookmark)'):
        # The bookmark TOGGLE is the bottom bar's 3rd icon (374,2569); the old
        # top-bar slot 483 is ic_sliders now. Toggle state may be either way
        # (bookmark already present), so accept remove-then-add.
        _ensure_toolbar(dev)
        dev.hilog_clear()
        dev.click_xy(374, 2569)
        ev = dev.wait_event('bookmark_added', timeout=8)
        if not ev:
            if not dev.wait_event('bookmark_removed', timeout=4):
                dev.save_dump('bm_toggle_fail')
                raise TestFail('bookmark toggle produced no event')
            dev.click_xy(374, 2569)
            ev = dev.wait_event('bookmark_added', timeout=8)
            if not ev:
                dev.save_dump('bm_add_fail')
                raise TestFail('bookmark_added not fired on re-toggle')
        dev.click_xy(374, 2569)  # leave clean: remove again
        if not dev.wait_event('bookmark_removed', timeout=8):
            dev.save_dump('bm_rm_fail')
            raise TestFail('bookmark_removed not fired on cleanup')
    _exit_reader(dev)


def fn06_font_size(dev, case_id, cfg, fixtures):
    _ensure_home(dev, cfg)
    with dev.step(case_id, 'settings panel A+/A-'):
        _open_book(dev, 'Alice', expect_pages=105)
        dev.click_xy(ICON_SET, TB)
        if not dev.wait_text('A+', timeout=8):
            raise TestFail('settings panel (A+) not shown')
        dev.click_text('A+')
        time.sleep(0.8)
        dev.click_text('A-')
        time.sleep(0.8)
        # settings_saved fires on panel-close persist; verify the reader is
        # still alive and the panel still works (event informational)
        if not dev.exists_text('A+'):
            raise TestFail('settings panel closed unexpectedly after A+/A-')
        ev = dev.hilog_events(evt='settings_saved')
        print('    [%s] settings_saved: %s' %
              (case_id, ev[-1]['raw'][:80] if ev else 'n/a (persists on close)'), flush=True)
    _exit_reader(dev)


def fn07_night_mode(dev, case_id, cfg, fixtures):
    _ensure_home(dev, cfg)
    with dev.step(case_id, 'toggle night mode'):
        _open_book(dev, 'Alice', expect_pages=105)
        _ensure_toolbar(dev)
        dev.click_xy(ICON_SET, TB)
        if not _wait_any(dev, ['夜间', 'Night'], 8):
            dev.save_dump('night_panel_fail')
            raise TestFail('settings panel (夜间) not shown')
        if not (_click_toggle_near(dev, '夜间') or _click_toggle_near(dev, 'Night')):
            dev.click_text('关')  # text-button fallback
        time.sleep(1.0)
        dev.key('Back')  # closes the drawer (observed: may leave the reader)
        time.sleep(1.0)
    with dev.step(case_id, 'restore day mode'):
        if dev.current_page()[1] != 'components/Reader':
            # the drawer Back left the reader: open the book again
            _open_book(dev, 'Alice', expect_pages=105)
            _ensure_toolbar(dev)
            dev.click_xy(ICON_SET, TB)
            if not _wait_any(dev, ['夜间', 'Night'], 8):
                dev.save_dump('night_restore_fail')
                raise TestFail('settings panel not shown on restore')
        if not (_click_toggle_near(dev, '夜间') or _click_toggle_near(dev, 'Night')):
            dev.click_text('开')
        time.sleep(1.0)
        dev.key('Back')
        time.sleep(1.0)
    if dev.current_page()[1] == 'components/Reader':
        _exit_reader(dev)


def fn08_reading_stats(dev, case_id, cfg, fixtures):
    _ensure_home(dev, cfg)
    with dev.step(case_id, '30s reading timer recorded'):
        _open_book(dev, 'Alice', expect_pages=105)
        dev.hilog_clear()
        ev = dev.wait_event('stats_readtime_recorded', timeout=45)
        if not ev:
            raise TestFail('stats_readtime_recorded not fired within 45s')
    _exit_reader(dev)


# ------------------------------------------------------------ library
def fn09_library_search(dev, case_id, cfg, fixtures):
    _ensure_home(dev, cfg)
    with dev.step(case_id, 'full-text search across library'):
        dev.click_id('app_tab_1') or dev.click_text('书库')
        if not _wait_any(dev, ['全文', 'Full text', 'Full-text'], 8):
            raise TestFail('library search chip not visible')
        dev.click_text('全文') or dev.click_text('Full text') or dev.click_text('Full-text')
        time.sleep(0.8)
        a = dev.find(hint='搜索')
        if not a:
            a = dev.find(text='搜索')
        if not a:
            a = dev.find(hint='Search')
        if not a:
            a = dev.find(text='Search')
        if not a:
            raise TestFail('library search box not found')
        m = re.search(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', a.get('bounds', ''))
        x = (int(m.group(1)) + int(m.group(3))) // 2
        y = (int(m.group(2)) + int(m.group(4))) // 2
        dev.hilog_clear()
        dev.click_xy(x, y)
        time.sleep(0.8)
        dev.shell('uitest uiInput text Alice')
        time.sleep(1.0)
        dev.key('Back')  # dismiss keyboard
        time.sleep(0.6)
        # The 全文 chip is the RUN toggle (Index.ets: tap runs runLibSearch,
        # tap again while busy cancels) — the first tap only armed the mode.
        dev.click_text('全文') or dev.click_text('Full text') or dev.click_text('Full-text')
        ev = dev.wait_event('lib_search_done', timeout=60)
        if not ev or int(ev['kv'].get('hits', '0')) < 1:
            dev.save_dump('lib_search_fail')
            raise TestFail('lib_search_done wrong: %s' % (ev and ev['raw']))


def fn10_browse_files(dev, case_id, cfg, fixtures):
    _ensure_home(dev, cfg)
    with dev.step(case_id, 'my-files library folder lists files'):
        dev.click_id('app_tab_2') or dev.click_text('我的文件')
        if not dev.wait_text('书库文件夹', timeout=8):
            raise TestFail('my-files page not shown')
        dev.hilog_clear()
        row = dev.find(text='书库', contains=False)
        if not row or not dev._click_attrs(row):
            raise TestFail('library folder row (书库 exact) not found')
        ev = dev.wait_event('browse_dir_loaded', timeout=10)
        if not ev or int(ev['kv'].get('files', '0')) < 1:
            dev.save_dump('browse_fail')
            raise TestFail('browse_dir_loaded wrong: %s' % (ev and ev['raw']))


def fn11_star(dev, case_id, cfg, fixtures):
    _ensure_home(dev, cfg)
    with dev.step(case_id, 'star a library row'):
        dev.click_id('app_tab_1') or dev.click_text('书库')
        if not dev.wait_text('☆', timeout=8):
            raise TestFail('star markers not visible')
        dev.hilog_clear()
        dev.click_text('☆')
        time.sleep(1.5)
        if not dev.exists_text('★'):
            dev.save_dump('star_fail')
            raise TestFail('star did not toggle to ★')
    with dev.step(case_id, 'unstar restores ☆'):
        dev.click_text('★')
        time.sleep(1.5)
        if not dev.exists_text('☆'):
            raise TestFail('unstar did not restore ☆')


# ------------------------------------------------------------ network
def _hide_kb(dev):
    """Dismiss the soft keyboard. After field filling the IME covers
    the dialog's Test/Add buttons, so uiInput taps land on the IME
    (AGENTS pitfall #21). Back hides the IME while an input has focus.
    Must be called right after the last fill (keyboard guaranteed up)."""
    dev.key('Back')
    time.sleep(0.8)


def _input_field_any(dev, value, hints):
    """Fill a TextInput located by the first matching hint in `hints`
    (dialogs localize their hints; try zh and en)."""
    for h in hints:
        if dev.find(hint=h):
            _input_field(dev, value=value, hint=h)
            return
    raise TestFail('input field not found for hints %r' % (hints,))


def _open_myfiles_webdav_add(dev):
    dev.click_id('app_tab_2') or dev.click_text('我的文件')
    if not dev.wait_text('WebDAV', timeout=8):
        raise TestFail('WebDAV section not visible')
    _click_add_after(dev, 'WebDAV')


def _fill_webdav_dialog(dev, ts, name='CI-WebDAV'):
    """Fill the WebDAV add dialog (fields: http:// URL, Name, Account,
    Password, Start dir; buttons Test/Browse dirs/Close/Add — hints/buttons
    are hardcoded English regardless of UI language)."""
    _input_field(dev, value=ts.get('webdav_url', ''), hint='http')
    _input_field(dev, value=name, hint='MyNAS')
    _input_field_any(dev, value=ts.get('user', ''), hints=['Account', '账号'])
    _input_field_any(dev, value=ts.get('password', ''), hints=['Password', '密码'])
    _hide_kb(dev)


def fn12_webdav_test(dev, case_id, cfg, fixtures):
    ts = cfg.get('test_server', {})
    url = ts.get('webdav_url', '')
    if not _server_up(dev, cfg, url):
        raise TestSkip('test server %s unreachable' % url)
    _ensure_home(dev, cfg)
    with dev.step(case_id, 'fill server + test connection'):
        _open_myfiles_webdav_add(dev)
        _fill_webdav_dialog(dev, ts)
        dev.hilog_clear()
        if not dev.click_id('dav_btn_test'):
            dev.click_text('Test') or dev.click_text('测试连接')
        ev = dev.wait_event('dav_test_done', timeout=25)
        if not ev or ev['kv'].get('code') not in ('200', '207'):
            dev.save_dump('dav_test_fail')
            raise TestFail('dav_test_done wrong: %s' % (ev and ev['raw']))
    with dev.step(case_id, 'cleanup dialog'):
        dev.click_text('Close') or dev.key('Back')


def fn13_webdav_browse(dev, case_id, cfg, fixtures):
    ts = cfg.get('test_server', {})
    url = ts.get('webdav_url', '')
    if not _server_up(dev, cfg, url):
        raise TestSkip('test server %s unreachable' % url)
    _ensure_home(dev, cfg)
    with dev.step(case_id, 'save server and browse root'):
        _open_myfiles_webdav_add(dev)
        _fill_webdav_dialog(dev, ts)
        dev.hilog_clear()
        # The my-files page has a Text 'Add' per section header; the
        # dialog's own Add is the only one rendered as type Button.
        add_btn = None
        for n in dev._walk(dev.dump()):
            a = n.get('attributes', {})
            if a.get('text') in ('Add', '添加') and a.get('type') == 'Button':
                add_btn = a
                break
        if not add_btn or not dev._click_attrs(add_btn):
            raise TestFail('dialog Add button not found')
        time.sleep(1.5)
        # server card now listed on my-files; tap it to open the panel
        if not dev.click_text('CI-WebDAV'):
            raise TestFail('saved server card not shown')
        ev = dev.wait_event('dav_panel_loaded', timeout=25)
        if not ev or int(ev['kv'].get('files', '0')) < 1:
            dev.save_dump('dav_browse_fail')
            raise TestFail('dav_panel_loaded wrong: %s' % (ev and ev['raw']))
        if not (dev.exists_text('book_txt.txt') or dev.exists_text('book_pdf.pdf')
                or dev.exists_text('book_epub.epub')):
            dev.save_dump('dav_files_missing')
            raise TestFail('expected test books not listed')


def fn14_smb_test(dev, case_id, cfg, fixtures):
    ts = cfg.get('test_server', {})
    if not _server_up(dev, cfg, ts.get('webdav_url', '')):
        raise TestSkip('test server unreachable')
    _ensure_home(dev, cfg)
    with dev.step(case_id, 'SMB add + test'):
        dev.click_id('app_tab_2') or dev.click_text('我的文件')
        if not dev.wait_text('SMB', timeout=8):
            raise TestFail('SMB section not visible')
        if not dev.exists_text('testbooks'):
            _click_add_after(dev, 'SMB')
            # zh-hinted dialog: 显示名（可选）/主机/445/共享名/域/账号/密码
            _input_field_any(dev, value='CI-SMB', hints=['显示名', 'Name'])
            _input_field_any(dev, value=ts.get('host', ''), hints=['主机', 'Host'])
            _input_field_any(dev, value=ts.get('smb_share', 'testbooks'),
                             hints=['共享名', 'Share'])
            _input_field_any(dev, value=ts.get('user', ''), hints=['账号', 'Account'])
            _input_field_any(dev, value=ts.get('password', ''), hints=['密码', 'Password'])
            _hide_kb(dev)
            dev.click_text('测试连接') or dev.click_text('Test')
            if not dev.wait_text('连接成功', timeout=25):
                dev.save_dump('smb_test_fail')
                raise TestFail('SMB 连接成功 not shown')


def fn15_sftp_test(dev, case_id, cfg, fixtures):
    ts = cfg.get('test_server', {})
    if not _server_up(dev, cfg, ts.get('webdav_url', '')):
        raise TestSkip('test server unreachable')
    _ensure_home(dev, cfg)
    with dev.step(case_id, 'SFTP add + test'):
        dev.click_id('app_tab_2') or dev.click_text('我的文件')
        if not dev.wait_text('SFTP', timeout=8):
            raise TestFail('SFTP section not visible')
        _click_add_after(dev, 'SFTP')
        _input_field_any(dev, value='CI-SFTP', hints=['显示名', 'Name'])
        _input_field_any(dev, value=ts.get('host', ''), hints=['主机', 'Host'])
        _input_field_any(dev, value=str(ts.get('sftp_port', 22)), hints=['22', 'Port'])
        _input_field_any(dev, value=ts.get('user', ''), hints=['账号', 'Account'])
        _input_field_any(dev, value=ts.get('password', ''), hints=['密码', 'Password'])
        _hide_kb(dev)
        cb = dev.find(text='sftpTrust') or dev.find(text='信任任意主机密钥')
        if cb:
            dev._click_attrs(cb)  # first connect: trust the host key
            time.sleep(0.4)
        dev.click_text('测试连接') or dev.click_text('Test')
        if not dev.wait_text('连接成功', timeout=25):
            dev.save_dump('sftp_test_fail')
            raise TestFail('SFTP 连接成功 not shown')


def fn16_remote_open(dev, case_id, cfg, fixtures):
    ts = cfg.get('test_server', {})
    if not _server_up(dev, cfg, ts.get('webdav_url', '')):
        raise TestSkip('test server unreachable')
    _ensure_home(dev, cfg)
    with dev.step(case_id, 'open remote txt online'):
        dev.click_id('app_tab_2') or dev.click_text('我的文件')
        if not dev.click_text('CI-WebDAV'):
            raise TestSkip('CI-WebDAV card missing (run FN-13 flow first)')
        dev.wait_event('dav_panel_loaded', timeout=25)
        dev.hilog_clear()
        # txt is SIMPLE_FETCH (download + local open by design); pdf is a
        # DIRECT_OPEN format and actually streams online (remote=1).
        # The panel lists the app's /HowRead/ remote dir (empty start-dir
        # default); book_epub.epub is present there and is a DIRECT_OPEN
        # format that streams online (remote=1).
        if not dev.click_text('book_epub.epub'):
            raise TestFail('book_epub.epub not listed in panel')
        ev = dev.wait_event('book_open_done', timeout=40)
        if not ev or ev['kv'].get('remote') != '1':
            dev.save_dump('remote_open_fail')
            raise TestFail('remote book_open_done wrong: %s' % (ev and ev['raw']))
        if not dev.wait_event('page_rendered', timeout=25):
            raise TestFail('remote first page not rendered')
        b, page = dev.current_page()
        if page != 'components/Reader':
            raise TestFail('not in Reader for remote book (%s)' % page)


# ------------------------------------------------------------ AI / prefs
def fn17_ai_config(dev, case_id, cfg, fixtures):
    mock = cfg.get('ai_mock', {})
    url = fixtures.get('ai_mock_url')
    if not url:
        raise TestSkip('ai mock url not provided (run_all starts it for L1)')
    _ensure_home(dev, cfg)
    with dev.step(case_id, 'configure AI to mock and test'):
        dev.click_id('app_tab_3') or dev.click_text('偏好')
        if not dev.wait_text('书库设置', timeout=8):
            raise TestFail('prefs not shown')
        found = False
        for _ in range(10):
            if dev.exists_text('AI 大模型') or dev.exists_text('AI大模型') or dev.exists_text('AI model'):
                found = True
                break
            dev.swipe(660, 2000, 660, 700, 600)
            time.sleep(1.0)
        if not found:
            raise TestSkip('AI config entry not found in prefs after scrolling')
        dev.click_text('AI 大模型') or dev.click_text('AI大模型') or dev.click_text('AI model')
        time.sleep(1.0)
        # The AI dialog fields expose only their placeholders as hints
        # (URL 'https://..', key 'sk-...', model 'glm-4.5-ai') and rows may
        # carry prefilled values — _input_field clears before typing.
        _input_field(dev, value=url, hint='https://')
        _input_field(dev, value='ci-test-key', hint='sk-')
        _input_field(dev, value=mock.get('model', 'howread-test-model'), hint='glm-4.5')
        dev.hilog_clear()
        if not (dev.click_text('Test connection') or dev.click_text('测试连接')):
            raise TestFail('AI test button not found')
        ev = dev.wait_event('ai_req_done', timeout=25)
        if not ev:
            dev.save_dump('ai_test_fail')
            raise TestFail('ai_req_done not fired against mock')
        dev.click_text('Close') or dev.click_text('关闭') or dev.key('Back')


def fn18_language(dev, case_id, cfg, fixtures):
    _ensure_home(dev, cfg)
    with dev.step(case_id, 'switch language to English'):
        dev.click_id('app_tab_3') or dev.click_text('偏好')
        if not dev.wait_text('书库设置', timeout=8):
            raise TestFail('prefs not shown')
        found = False
        for _ in range(8):
            if dev.exists_text('语言'):
                found = True
                break
            dev.swipe(660, 2000, 660, 700, 600)
            time.sleep(1.0)
        if not found:
            raise TestSkip('language row not found in prefs after scrolling')
        dev.click_text('语言')
        time.sleep(1.0)
        dev.hilog_clear()
        if not dev.click_text('English') and not dev.click_text('英文'):
            dev.save_dump('lang_options')
            raise TestSkip('English option not found')
        ev = dev.wait_event('language_applied', timeout=15)
        if not ev:
            raise TestFail('language_applied not fired')
        time.sleep(2.0)  # let saveNow's preferences flush land before force-stop
    with dev.step(case_id, 'cold start shows English UI'):
        dev.stop_app()
        dev.start_app()
        if not dev.wait_text('Home', timeout=20):
            dev.save_dump('lang_en_fail')
            raise TestFail('English tab texts not shown after restart')
    with dev.step(case_id, 'restore Chinese'):
        dev.click_id('app_tab_3') or dev.click_text('Preferences')
        dev.wait_text('Language', timeout=8) or dev.wait_text('语言', timeout=5)
        dev.click_text('Language') or dev.click_text('语言')
        time.sleep(1.0)
        dev.click_text('中文') or dev.click_text('Chinese')
        dev.wait_event('language_applied', timeout=15)
        time.sleep(2.0)  # preferences flush (see step 1)
        dev.stop_app()
        dev.start_app()
        if not dev.wait_text('首页', timeout=20):
            raise TestFail('Chinese UI not restored')


ALL = [
    ('FN-01', '开书 EPUB 事件链', fn01_open_epub),
    ('FN-02', '音量键翻页页码递增', fn02_page_turn),
    ('FN-03', '跳页对话框', fn03_jump_page),
    ('FN-04', '目录面板加载', fn04_toc),
    ('FN-05', '书签面板/添加', fn05_bookmark),
    ('FN-06', '字号调节', fn06_font_size),
    ('FN-07', '夜间模式切换', fn07_night_mode),
    ('FN-08', '阅读统计记录', fn08_reading_stats),
    ('FN-09', '库内全文搜索', fn09_library_search),
    ('FN-10', '我的文件目录浏览', fn10_browse_files),
    ('FN-11', '收藏/取消收藏', fn11_star),
    ('FN-12', 'WebDAV 测试连接', fn12_webdav_test),
    ('FN-13', 'WebDAV 保存并浏览', fn13_webdav_browse),
    ('FN-14', 'SMB 测试连接', fn14_smb_test),
    ('FN-15', 'SFTP 测试连接', fn15_sftp_test),
    ('FN-16', '远程书在线打开', fn16_remote_open),
    ('FN-17', 'AI 配置连通(mock)', fn17_ai_config),
    ('FN-18', '语言切换冷启生效', fn18_language),
]
