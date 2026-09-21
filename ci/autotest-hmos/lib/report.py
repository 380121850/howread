# -*- coding: utf-8 -*-
"""Markdown report for the hmos autotest run (Android report.py parity)."""
import os
import time


def write_report(runs, out_path, meta):
    """runs: {serial: {'meta': {...}, 'results': [case dicts]}}"""
    lines = []
    lines.append('# HowRead Pro (HarmonyOS) 自动测试报告')
    lines.append('')
    lines.append('- 时间：%s' % time.strftime('%Y-%m-%d %H:%M:%S'))
    lines.append('- 层级：%s | 设备数：%d' % (meta.get('level', '?'), len(runs)))
    lines.append('- HAP：%s' % meta.get('hap', '?'))
    lines.append('- 门禁：**%s**' % meta.get('gate', '?'))
    lines.append('')

    for serial, r in runs.items():
        m = r['meta']
        results = r['results']
        n_pass = sum(1 for x in results if x['result'] == 'PASS')
        n_fail = sum(1 for x in results if x['result'] == 'FAIL')
        n_skip = sum(1 for x in results if x['result'] == 'SKIP')
        lines.append('## %s（%s，%s）' % (serial, m.get('model', '?'), m.get('abi', '?')))
        lines.append('')
        lines.append('PASS %d / FAIL %d / SKIP %d' % (n_pass, n_fail, n_skip))
        lines.append('')
        lines.append('| ID | 用例 | 层 | 优先级 | 结果 | 次数 | 耗时 | 备注 |')
        lines.append('|---|---|---|---|---|---|---|---|')
        for x in results:
            lines.append('| %s | %s | %s | %s | %s | %d | %.1fs | %s |' % (
                x['id'], x['name'], x['layer'], x['priority'],
                x['result'], x['attempts'], x['secs'], x['note'].replace('|', '\\|')[:110]))
        lines.append('')
        fails = [x for x in results if x['result'] == 'FAIL']
        if fails:
            lines.append('### 失败详情')
            for x in fails:
                lines.append('')
                lines.append('- **%s %s**：%s（证据目录 `%s`）' %
                             (x['id'], x['name'], x['note'][:200], x.get('evidence', '')))
            lines.append('')
        skips = [x for x in results if x['result'] == 'SKIP']
        if skips:
            lines.append('### 跳过')
            for x in skips:
                lines.append('- %s %s：%s' % (x['id'], x['name'], x['note'][:120]))
            lines.append('')

    with open(out_path, 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines))
    return out_path
