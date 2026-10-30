package howread.core.library

import java.io.File

/** 文件名解析出的系列信息（书库"系列"排序用——引擎无标准系列元数据字段）。 */
data class SeriesInfo(val series: String, val index: Int)

/**
 * 文件名系列模式解析（保守策略，宁可漏判不误判）：
 *  1. 带独立数字的括号组：[系列 01]xxx / xxx（系列 3）【哈利波特05】
 *  2. 第N卷/册/部/集（N=数字或一~九十九中文数字）：系列名 第3卷 xxx
 *  3. 中划线段：系列名 - 01 - 书名
 *  4. 尾部数字：系列名 01 / 系列名-01（分隔符版任意 1~3 位非年份）
 *  5. 尾部补零紧贴：系列名01（仅 0 开头 2~3 位，避免 "Excel2007" 类误判）
 *  6. 头部数字：01 - 书名 → 系列名取父目录（"三体系列/01 xxx" 命名惯例）
 * 年份（19xx/20xx 四位）一律排除；罗马数字（三体III）不支持。
 */
object SeriesParser {

    fun parse(path: String, title: String): SeriesInfo? {
        val name = title.trim()
        if (name.length < 3) return null

        // 1. 括号组内含独立数字
        for ((open, close) in listOf("[" to "]", "（" to "）", "(" to ")", "【" to "】")) {
            val i = name.indexOf(open)
            if (i < 0) continue
            val j = name.indexOf(close, i + 1)
            if (j <= i + 1) continue
            val inner = name.substring(i + 1, j)
            val m = Regex("(?<!\\d)(\\d{1,4})(?!\\d)").find(inner) ?: continue
            if (isYear(m.groupValues[1])) continue
            val idx = m.groupValues[1].toInt()
            var series = inner.replace(m.value, "").trim(' ', '-', '_', '·', '，', ',')
            if (series.length < 2) series = (name.substring(0, i) + name.substring(j + 1))
                .trim(' ', '-', '_', '·', '，', ',')
            series = stripBrackets(series)
            if (series.length >= 2) return SeriesInfo(series, idx)
        }

        // 2. 第N卷/册/部/集
        Regex("第([0-9]{1,3}|[一二三四五六七八九十]{1,3})\\s*[卷册部集]").find(name)?.let { m ->
            val idx = cnNum(m.groupValues[1])
            val before = stripBrackets(name.substring(0, m.range.first).trim(' ', '-', '_', '·'))
            if (idx != null && before.length >= 2) return SeriesInfo(before, idx)
        }

        // 3. 中划线段：A - 01 - B
        Regex("^\\s*(.{2,60}?)\\s*[-–—]\\s*(\\d{1,3})\\s*[-–—]").find(name)?.let { m ->
            if (!isYear(m.groupValues[2])) return SeriesInfo(m.groupValues[1].trim(), m.groupValues[2].toInt())
        }

        // 4. 尾部数字（带分隔符）
        Regex("^(.{2,60}?)\\s*[\\s_\\-–—]\\s*(\\d{1,3})$").find(name)?.let { m ->
            if (!isYear(m.groupValues[2])) return SeriesInfo(stripBrackets(m.groupValues[1].trim()), m.groupValues[2].toInt())
        }

        // 5. 尾部补零紧贴（仅 0 开头）
        Regex("^(.{2,60}?)(?<!\\d)(0\\d{1,2})$").find(name)?.let { m ->
            return SeriesInfo(stripBrackets(m.groupValues[1].trim()), m.groupValues[2].toInt())
        }

        // 6. 头部数字 → 父目录名当系列
        Regex("^\\s*(\\d{1,3})\\s*[\\s_\\-–—]\\s*(.+)$").find(name)?.let { m ->
            if (isYear(m.groupValues[1])) return null
            val dir = File(path).parentFile?.name?.trim(' ', '-', '_') ?: ""
            if (dir.length >= 2) return SeriesInfo(stripBrackets(dir), m.groupValues[1].toInt())
        }
        return null
    }

    private fun isYear(s: String): Boolean = s.length == 4 && (s.startsWith("19") || s.startsWith("20"))

    private fun stripBrackets(s: String): String =
        s.trim(' ', '-', '_', '·', '[', ']', '（', '）', '(', ')', '【', '】')

    /** 中文数字一~九十九 → Int；失败 null。 */
    private fun cnNum(s: String): Int? {
        if (s.all { it.isDigit() }) return s.toInt()
        val map = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
            '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        if (s.length == 1) return if (s[0] == '十') 10 else map[s[0]]
        if (s.length == 2) {
            if (s[0] == '十') return 10 + (map[s[1]] ?: return null)
            if (s[1] == '十') return (map[s[0]] ?: return null) * 10
            return null
        }
        if (s.length == 3 && s[1] == '十') return ((map[s[0]] ?: return null) * 10) + (map[s[2]] ?: return null)
        return null
    }
}
