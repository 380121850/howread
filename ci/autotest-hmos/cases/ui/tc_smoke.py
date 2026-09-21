# -*- coding: utf-8 -*-
"""L0 smoke cases for HowRead Pro (HarmonyOS). Signature: fn(dev, case_id, cfg, fixtures).

UI 词汇（zh-CN 模拟器实测，dumpLayout 文本）：
  底部 tab：首页/书库/我的文件/偏好（id: app_tab_0..3）
  首页：最近阅读 / 阅读统计 / 书签笔记 / 网上书库
  书库：搜索 / 全文 / 最近 / 全部/未读/在读/已读/☆收藏
  阅读器：页码 "N/M"；HRTEST 事件 book_open_done / page_change_done
  退出确认：标题 退出应用（id: exit_btn_cancel / exit_btn_ok）
"""
import os
import re
import time

from lib.hdcdriver import TestFail, TestSkip

TAB_IDS = ['app_tab_0', 'app_tab_1', 'app_tab_2', 'app_tab_3']


def _ensure_home(dev, cfg):
    dev.wake()
    dev.stop_app()
    dev.hilog_clear()
    dev.start_app()
    if not dev.wait_home(cfg.get('launcher_timeout_s', 30)):
        dev.save_dump('no_home')
        raise TestFail('app did not reach home page')


def sm01_install(dev, case_id, cfg, fixtures):
    hap = fixtures.get('hap')
    if not hap:
        raise TestSkip('no hap path (use --no-install?)')
    ver_hap = re.search(r'-v([\d.]+)-', os.path.basename(hap))
    with dev.step(case_id, 'install %s' % os.path.basename(hap)):
        dev.install(hap)
    with dev.step(case_id, 'verify version'):
        v = dev.installed_version()
        if not v:
            raise TestFail('bm dump has no versionName')
        if ver_hap and ver_hap.group(1) != v:
            raise TestFail('version mismatch: hap=%s device=%s' % (ver_hap.group(1), v))


def sm02_cold_start(dev, case_id, cfg, fixtures):
    dev.wake()
    with dev.step(case_id, 'cold start'):
        dev.hilog_clear()
        dev.stop_app()
        t_wall = __import__('time').time()
        dev.start_app()
        ev1 = dev.wait_event('ability_onCreate', timeout=20)
        ev2 = dev.wait_event('home_ready', timeout=cfg.get('launcher_timeout_s', 30))
        if not ev1 or not ev2:
            raise TestFail('missing HRTEST start events: onCreate=%s home_ready=%s' %
                           (bool(ev1), bool(ev2)))
        if not dev.wait_home(timeout=15):
            raise TestFail('home page not visible in dump')
        ms = ev2['ts'] - ev1['ts']
        if ms > 60000:
            raise TestFail('implausible cold start %dms' % ms)
        print('    [%s] cold start onCreate->home_ready = %dms (wall %.1fs)' %
              (case_id, ms, __import__('time').time() - t_wall))


def sm03_tabs(dev, case_id, cfg, fixtures):
    _ensure_home(dev, cfg)
    markers = {0: '最近阅读', 1: '未读', 3: '书库设置'}
    for idx in (1, 3, 0):
        with dev.step(case_id, 'tab %d -> %s' % (idx, markers[idx])):
            if not dev.click_id(TAB_IDS[idx]):
                # .id() 兜底：按 tab 文本定位
                names = ['首页', '书库', '我的文件', '偏好']
                if not dev.click_text(names[idx]):
                    raise TestFail('tab %d not found (id and text)' % idx)
            if not dev.wait_text(markers[idx], timeout=8):
                dev.save_dump('tab_%d_marker_missing' % idx)
                raise TestFail('tab %d marker %r not shown' % (idx, markers[idx]))
    # tab 2 (我的文件)：首页分区里也有同名文案，改断言浏览页专属标记
    with dev.step(case_id, 'tab 2 my-files'):
        if not dev.click_id(TAB_IDS[2]):
            if not dev.click_text('我的文件'):
                raise TestFail('tab 2 not found')
        ok = dev.wait_text('书库文件夹', timeout=8) or dev.wait_text('可阅读文件', timeout=4)
        if not ok:
            dev.save_dump('tab2_marker_missing')
            raise TestFail('my-files page marker not shown')


def sm04_open_pdf_and_flip(dev, case_id, cfg, fixtures):
    pdf_url = fixtures.get('seed_pdf_url')
    if not pdf_url:
        raise TestSkip('no seed pdf url')
    with dev.step(case_id, 'seed big25.pdf'):
        ev = dev.seed_book(pdf_url, timeout=120)
        if 'big25.pdf' not in ev.get('kv', {}).get('name', ''):
            raise TestFail('seeded wrong book: %s' % ev['raw'])
    with dev.step(case_id, 'seeded big25 visible in library'):
        if not dev.click_id('app_tab_1'):
            dev.click_text('书库')
        if not dev.wait_text('big25.pdf', timeout=15):
            dev.swipe(660, 1800, 660, 800)
            if not dev.wait_text('big25.pdf', timeout=8):
                dev.save_dump('pdf_not_in_library')
                raise TestFail('big25.pdf not visible in library')
        # NOTE: opening big25 (2600p) destabilized the session in early runs
        # (appfreeze 2026-09-21 00:34 + post-close open failures, pid churn —
        # filed as findings); smoke only verifies the seed, big25 stays for L2.
    with dev.step(case_id, 'open demo.cbz for flips'):
        dev.hilog_clear()  # drop stale events so assertions can't match old ones
        if not dev.click_text('demo.cbz'):
            raise TestFail('demo.cbz not found in library')
        ev = dev.wait_event('book_open_done', timeout=30)
        if not ev or ev.get('kv', {}).get('pages') != '5':
            dev.save_dump('cbz_open_bad')
            raise TestFail('demo.cbz book_open_done wrong: %s' % (ev and ev['raw']))
        if not dev.wait_text('/5', timeout=10):
            dev.save_dump('cbz_indicator_missing')
            raise TestFail('page indicator 1/5 not shown after open')
    with dev.step(case_id, 'flip pages (volume keys)'):
        # Injected swipes / side-zone taps do NOT reach the horizontal Swiper
        # on the emulator (full-size gesture layer + centered zone rows in the
        # Reader Stack — see PITFALLS_HMOS). Volume keys (inputConsumer,
        # keyEvent 16/17) are the deterministic page-turn channel.
        dev.hilog_clear()
        flipped = 0
        for i in range(5):
            dev.key('17')  # volume down -> next page
            ev = dev.wait_event('page_change_done', timeout=6)
            if ev:
                flipped += 1
            time.sleep(0.8)
        if flipped < 3:
            dev.save_dump('flip_failed')
            raise TestFail('only %d/5 page turns detected (volume keys)' % flipped)
    with dev.step(case_id, 'back to home'):
        dev.key('Back')
        if not dev.wait_text('未读', timeout=12):
            raise TestFail('did not return to library after Back')


def _click_toggle_near(dev, label_text):
    """Prefs rows pair a Text label with a Toggle on the right — click the
    Toggle itself (clicking the label does nothing). Returns True/False."""
    import re as _re
    label = dev.find(text=label_text)
    if not label:
        return False
    m = _re.search(r'\[\d+,(\d+)\]\[\d+,(\d+)\]', label.get('bounds', ''))
    if not m:
        return False
    y_center = (int(m.group(1)) + int(m.group(2))) // 2
    tree = dev.dump()
    for n in dev._walk(tree):
        a = n.get('attributes', {})
        if a.get('type') != 'Toggle':
            continue
        m2 = _re.search(r'\[\d+,(\d+)\]\[\d+,(\d+)\]', a.get('bounds', ''))
        if not m2:
            continue
        if abs((int(m2.group(1)) + int(m2.group(2))) // 2 - y_center) < 80:
            dev._click_attrs(a)
            return True
    return False


def _prefs_scroll_to(dev, text, max_swipes=8):
    """Scroll the prefs list until a text is visible; returns True."""
    for _ in range(max_swipes):
        if dev.exists_text(text):
            return True
        dev.swipe(660, 2000, 660, 700, 600)
        time.sleep(1.0)
    return dev.exists_text(text)


def _prefs_scroll_top(dev, max_swipes=10):
    for _ in range(max_swipes):
        if dev.exists_text('退出程序'):
            return True
        dev.swipe(660, 800, 660, 2100, 600)
        time.sleep(0.8)
    return dev.exists_text('退出程序')


def sm05_exit_confirm(dev, case_id, cfg, fixtures):
    """Exit-confirm is OFF by default: enable the prefs toggle, verify the
    dialog, cancel, then restore the toggle to off."""
    _ensure_home(dev, cfg)
    with dev.step(case_id, 'open prefs'):
        if not dev.click_id('app_tab_3') and not dev.click_text('偏好'):
            raise TestFail('prefs tab not found')
        if not dev.wait_text('书库设置', timeout=8):
            raise TestFail('prefs page not shown')
    with dev.step(case_id, 'enable exit-confirm toggle'):
        if not _prefs_scroll_to(dev, '退出确认对话框'):
            dev.save_dump('toggle_not_found')
            raise TestFail('退出确认对话框 toggle not found in prefs')
        dev.hilog_clear()
        if not _click_toggle_near(dev, '退出确认对话框'):
            raise TestFail('Toggle control not found next to 退出确认对话框')
        if not dev.wait_event('settings_saved', timeout=8):
            raise TestFail('settings_saved not fired after toggle')
    with dev.step(case_id, 'exit via 退出程序 shows dialog'):
        if not _prefs_scroll_top(dev):
            dev.save_dump('exit_btn_not_found')
            raise TestFail('退出程序 not reachable at prefs top')
        dev.hilog_clear()
        dev.click_text('退出程序')
        shown = dev.wait_event('exit_confirm_shown', timeout=8)
        if not shown and not dev.exists_text('退出应用'):
            raise TestFail('exit confirm dialog not shown after enabling toggle')
    with dev.step(case_id, 'cancel keeps app alive'):
        if not (dev.click_id('exit_btn_cancel') or dev.click_text('取消')):
            raise TestFail('cancel button not found')
        if not dev.wait_text('书库设置', timeout=8):
            raise TestFail('app left prefs after cancel')
    with dev.step(case_id, 'restore toggle off'):
        if _prefs_scroll_to(dev, '退出确认对话框'):
            dev.hilog_clear()
            _click_toggle_near(dev, '退出确认对话框')
            dev.wait_event('settings_saved', timeout=8)


def sm06_reinstall(dev, case_id, cfg, fixtures):
    hap = fixtures.get('hap')
    if not hap:
        raise TestSkip('no hap path')
    with dev.step(case_id, 'uninstall + install'):
        dev.uninstall()
        dev.install(hap, replace=False)
    with dev.step(case_id, 'fresh start rebuilds demo library'):
        dev.hilog_clear()
        dev.start_app()
        ev = dev.wait_event('home_ready', timeout=40)
        if not ev:
            raise TestFail('home_ready after reinstall missing')
        if not dev.wait_home(timeout=20):
            raise TestFail('home not visible after reinstall')


def sm07_evidence(dev, case_id, cfg, fixtures):
    _ensure_home(dev, cfg)
    with dev.step(case_id, 'screenshot'):
        p = dev.screenshot('sm07')
        if not os.path.exists(p) or os.path.getsize(p) < 20000:
            raise TestFail('screenshot too small: %s' % p)
    with dev.step(case_id, 'layout dump'):
        tree = dev.dump()
        n = sum(1 for _ in dev._walk(tree))
        if n < 30:
            raise TestFail('layout tree too small: %d nodes' % n)
    with dev.step(case_id, 'hilog events'):
        evts = dev.hilog_events()
        if not any(e['evt'] == 'home_ready' for e in evts):
            raise TestFail('no home_ready in hilog (HRTEST broken?)')


ALL = [
    ('SM-01', '安装与版本确认', sm01_install),
    ('SM-02', '冷启动（HRTEST 计时）', sm02_cold_start),
    ('SM-03', '四 tab 切换', sm03_tabs),
    ('SM-04', '开书与翻页（种子 PDF）', sm04_open_pdf_and_flip),
    ('SM-05', '退出确认对话框', sm05_exit_confirm),
    ('SM-06', '重装与自愈', sm06_reinstall),
    ('SM-07', '取证链路（截图/dump/hilog）', sm07_evidence),
]
