# -*- coding: utf-8 -*-
"""测试报告汇总：结果 → Markdown（失败用例附截图/日志/备注）"""
import os
from datetime import datetime


def write_report(runs, out_path, meta=None):
    """runs: [{serial, meta, flavor, version, results:[...]}]；out_path 为结果目录内 report.md"""
    total = sum(len(r["results"]) for r in runs)
    passed = sum(1 for r in runs for c in r["results"] if c["status"] == "PASS")
    skipped = sum(1 for r in runs for c in r["results"] if c["status"] == "SKIP")
    failed = sum(1 for r in runs for c in r["results"] if c["status"] not in ("PASS", "SKIP"))
    lines = []
    lines.append("# HowRead 自动测试报告")
    lines.append("")
    lines.append("- 运行标识: %s" % (meta or {}).get("run_id", "-"))
    lines.append("- 层级: %s　flavor: %s　APK: %s" % ((meta or {}).get("level", "-"),
                                                     (meta or {}).get("flavor", "-"),
                                                     (meta or {}).get("apk", "-")))
    lines.append("- 生成时间: %s" % datetime.now().strftime("%Y-%m-%d %H:%M:%S"))
    lines.append("")
    lines.append("## 总览: %d 台设备 → **%d PASS / %d FAIL / %d SKIP**（共 %d）" %
                 (len(runs), passed, failed, skipped, total))
    lines.append("")

    for r in runs:
        p = sum(1 for c in r["results"] if c["status"] == "PASS")
        f = sum(1 for c in r["results"] if c["status"] not in ("PASS", "SKIP"))
        s = sum(1 for c in r["results"] if c["status"] == "SKIP")
        lines.append("## %s (%s, %s/SDK%s) — flavor=%s version=%s → %d PASS / %d FAIL / %d SKIP" %
                     (r["meta"].get("model", r["serial"]), r["serial"],
                      r["meta"].get("android", "?"), r["meta"].get("sdk", "?"),
                      r["flavor"], r.get("version", "?"), p, f, s))
        lines.append("")
        lines.append("| ID | 用例 | 层 | 优先级 | 结果 | 尝试 | 耗时 | 备注 | 证据目录 |")
        lines.append("|---|---|---|---|---|---|---|---|---|")
        for c in r["results"]:
            lines.append("| %s | %s | %s | %s | %s | %d | %.1fs | %s | %s |" %
                         (c["case_id"], c["name"], c.get("layer", ""), c.get("priority", ""),
                          c["status"], c.get("attempts", 1), c["ms"] / 1000.0,
                          (c["note"] or "").replace("|", "/").replace("\n", " ")[:160],
                          c.get("evidence", "")))
        lines.append("")

    # 通过用例截图索引：UI 类用例 PASS 时保存的截图作为确认依据（2026-09-12 覆盖扩展起约定）
    pass_shots = []
    for r in runs:
        for c in r["results"]:
            if c["status"] != "PASS":
                continue
            ev = os.path.join(os.path.dirname(out_path), "evidence", r["serial"], c["case_id"])
            if not os.path.isdir(ev):
                continue
            shots = [f for f in sorted(os.listdir(ev)) if f.endswith(".png")]
            if shots:
                pass_shots.append((r, c, shots))
    if pass_shots:
        lines.append("## 通过用例截图索引（UI 确认依据）")
        lines.append("")
        for r, c, shots in pass_shots:
            lines.append("- %s %s @%s: %s" % (c["case_id"], c["name"], r["serial"],
                         ", ".join("`%s/%s/%s`" % (r["serial"], c["case_id"], s) for s in shots)))
        lines.append("")

    fails = [(r, c) for r in runs for c in r["results"] if c["status"] not in ("PASS", "SKIP")]
    skips = [(r, c) for r in runs for c in r["results"] if c["status"] == "SKIP"]
    if fails:
        lines.append("## 失败用例详情")
        lines.append("")
        for r, c in fails:
            lines.append("### [%s] %s %s @%s (%s) — 尝试 %d 次" %
                         (c.get("priority", "-"), c["case_id"], c["name"], r["serial"],
                          c["status"], c.get("attempts", 1)))
            lines.append("")
            lines.append("- **问题描述**: %s" % (c["note"] or "-"))
            ev = os.path.join(os.path.dirname(out_path), "evidence", r["serial"], c["case_id"])
            if os.path.isdir(ev):
                shots = [f for f in sorted(os.listdir(ev)) if f.endswith(".png")]
                dumps = [f for f in sorted(os.listdir(ev)) if f.endswith(".xml")]
                logs = [f for f in sorted(os.listdir(ev)) if f.endswith(".txt")]
                if shots:
                    lines.append("- **截图**: %s" % ", ".join("`%s/%s/%s`" % (r["serial"], c["case_id"], s)
                                                               for s in shots[-3:]))
                if dumps:
                    lines.append("- **UI dump**: `%s/%s/%s`" % (r["serial"], c["case_id"], dumps[-1]))
                if logs:
                    lines.append("- **logcat**: `%s/%s/%s`" % (r["serial"], c["case_id"], logs[-1]))
            lines.append("")
    if skips:
        lines.append("## 跳过用例（环境限制/待勘探，不计失败）")
        lines.append("")
        for r, c in skips:
            lines.append("- [%s] %s %s @%s: %s" % (c.get("priority", "-"), c["case_id"],
                                                   c["name"], r["serial"], (c["note"] or "")[:200]))
        lines.append("")

    gate = "全部 PASS（SKIP 不计）→ **满足门禁**" if failed == 0 else "存在 %d 个失败 → **不满足门禁**" % failed
    lines.append("## 门禁结论: %s" % gate)
    lines.append("")
    with open(out_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    return out_path
