package howread.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.Icon
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import howread.core.TestLog
import howread.core.data.Db
import howread.core.data.DayStat

/** 书签笔记（跨书聚合）：点击直达该书该页。 */
@Composable
fun BookmarksPage(appState: AppState) {
    val bms = remember { Db.allBookmarks() }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text(L10n.s("书签笔记（${bms.size}）", "Notes (${bms.size})"), color = UiTheme.p.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp))
        if (bms.isEmpty()) {
            Text(L10n.s("还没有书签——在阅读器中点「书签」即可在第 x 页添加标签与笔记。", "No bookmarks yet — add one in the reader."), color = UiTheme.p.textFaint, fontSize = 12.sp)
        }
        LazyColumn {
            items(bms, key = { it.first.id }) { (bm, bookTitle) ->
                Column(
                    Modifier.fillMaxWidth().clickable {
                        TestLog.evt("bm_jump", "page=" + (bm.page + 1) + " book=" + bookTitle)
                        appState.openAt(bm.path, bm.page)
                    }.padding(vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Filled.Bookmark, contentDescription = null, tint = UiTheme.p.accent, modifier = Modifier.size(18.dp))
                        Text(
                            bookTitle, color = UiTheme.p.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        Text(bm.label, color = UiTheme.p.textFaint, fontSize = 11.sp)
                    }
                    if (bm.note.isNotBlank()) {
                        Text(bm.note, color = UiTheme.p.textTertiary, fontSize = 12.sp, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** 我的珍藏：收藏书目封面墙。 */
@Composable
fun FavoritesPage(appState: AppState) {
    val favs = remember(appState.library) { appState.library.filter { it.isFavorite } }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text(L10n.s("我的珍藏（${favs.size}）", "Favorites (${favs.size})"), color = UiTheme.p.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp))
        if (favs.isEmpty()) {
            Text(L10n.s("还没有珍藏——书库封面右上角 ☆ 点亮即可收藏。", "No favorites yet — tap ☆ on a cover."), color = UiTheme.p.textFaint, fontSize = 12.sp)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(favs, key = { it.path }) { b -> BookCard(b, appState, modifier = Modifier.fillMaxWidth()) }
            }
        }
    }
}

/** 周/月/年聚合桶（阅读时长弹窗用）。 */
data class StatBucket(val label: String, val seconds: Int)

/** 日累计 -> 周/月/年聚合（含当前周期，旧->新；口径同首页 read_stats 累计）。 */
object PeriodStats {
    private fun ld(day: Long): java.time.LocalDate = java.time.LocalDate.ofEpochDay(day)

    /** 近 n 个自然周（周一为界，含当前周）。 */
    fun weekly(days: List<DayStat>, n: Int = 12): List<StatBucket> {
        val map = HashMap<Long, Int>()
        for (d in days) {
            val v = ld(d.day)
            val monday = v.minusDays(((v.dayOfWeek.value + 6) % 7).toLong())
            map[monday.toEpochDay()] = (map[monday.toEpochDay()] ?: 0) + d.seconds
        }
        val now = java.time.LocalDate.now()
        val cur = now.minusDays(((now.dayOfWeek.value + 6) % 7).toLong())
        val out = ArrayList<StatBucket>()
        for (i in n - 1 downTo 0) {
            val m = cur.minusDays(i * 7L)
            out.add(StatBucket("%02d/%02d".format(m.monthValue, m.dayOfMonth), map[m.toEpochDay()] ?: 0))
        }
        return out
    }

    /** 近 n 个自然月（含当前月）。 */
    fun monthly(days: List<DayStat>, n: Int = 12): List<StatBucket> {
        val map = HashMap<Int, Int>()
        for (d in days) {
            val v = ld(d.day)
            map[v.year * 100 + v.monthValue] = (map[v.year * 100 + v.monthValue] ?: 0) + d.seconds
        }
        val cur = java.time.LocalDate.now()
        val out = ArrayList<StatBucket>()
        for (i in n - 1 downTo 0) {
            val m = cur.minusMonths(i.toLong())
            out.add(StatBucket((m.year % 100).toString() + "/" + m.monthValue, map[m.year * 100 + m.monthValue] ?: 0))
        }
        return out
    }

    /** 全部年份（含当前年，旧->新）。 */
    fun yearly(days: List<DayStat>): List<StatBucket> {
        val map = java.util.TreeMap<Int, Int>()
        for (d in days) {
            val y = ld(d.day).year
            map[y] = (map[y] ?: 0) + d.seconds
        }
        if (map.isEmpty()) map[java.time.LocalDate.now().year] = 0
        return map.map { StatBucket(it.key.toString(), it.value) }
    }
}

/** 聚合桶柱状图（周/月/年弹窗用，样式同统计页柱图）。 */
@Composable
private fun BucketBars(title: String, buckets: List<StatBucket>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(UiTheme.p.panel).padding(14.dp)) {
        Text(title, color = UiTheme.p.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp))
        if (buckets.all { it.seconds <= 0 }) {
            Text(L10n.s("开始阅读后，这里按周期累积。", "Reading time accumulates here."), color = UiTheme.p.textFaint, fontSize = 12.sp)
        } else {
            val maxSec = maxOf(60, buckets.maxOf { it.seconds })
            Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                val n = buckets.size
                val gap = (if (n > 14) 3 else 10).dp.toPx()
                val barW = (size.width - gap * (n + 1)) / n
                buckets.forEachIndexed { i, b ->
                    val h = size.height * b.seconds / maxSec
                    drawRect(
                        color = if (i == n - 1) UiTheme.p.accent else UiTheme.p.accent.copy(alpha = 0.45f),
                        topLeft = Offset(gap + i * (barW + gap), size.height - h),
                        size = Size(barW, h),
                    )
                }
            }
            Row(Modifier.fillMaxWidth()) {
                for (b in buckets) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(b.label, color = UiTheme.p.textFaintest, fontSize = 8.sp, maxLines = 1)
                        Text(
                            if (b.seconds >= 60) L10n.s("${b.seconds / 60}分", "${b.seconds / 60}m")
                            else if (b.seconds > 0) L10n.s("${b.seconds}秒", "${b.seconds}s") else "—",
                            color = UiTheme.p.textTertiary, fontSize = 8.sp, maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** 总阅读时间点开：按周 / 月 / 年查看阅读时长。 */
@Composable
fun ReadingTimeDialog(onDismiss: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val days = remember { Db.allDays() }
    val totalSec = days.sumOf { it.seconds.toLong() }
    val weeks = remember(days) { PeriodStats.weekly(days) }
    val months = remember(days) { PeriodStats.monthly(days) }
    val years = remember(days) { PeriodStats.yearly(days) }
    LaunchedEffect(Unit) {
        TestLog.evt("stats_time_dlg", "days=" + days.size + " total=" + totalSec)
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.width(560.dp).clip(RoundedCornerShape(14.dp))) {
            Column(Modifier.padding(18.dp)) {
                Text(L10n.s("阅读时间统计", "Reading time"), color = UiTheme.p.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    L10n.s("累计 " + fmtDur(totalSec) + " · 按周 / 月 / 年查看", "Total " + fmtDur(totalSec) + " · weekly / monthly / yearly"),
                    color = UiTheme.p.textFaint, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((i, label) in listOf(L10n.s("按周", "Weekly"), L10n.s("按月", "Monthly"), L10n.s("按年", "Yearly")).withIndex()) {
                        TextButton(onClick = { tab = i }) {
                            Text(label, color = if (tab == i) UiTheme.p.accent else UiTheme.p.textTertiary,
                                fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(L10n.s("关闭", "Close")) }
                }
                when (tab) {
                    0 -> BucketBars(L10n.s("近 12 周阅读时长", "Weekly reading (last 12)"), weeks)
                    1 -> BucketBars(L10n.s("近 12 个月阅读时长", "Monthly reading (last 12)"), months)
                    else -> BucketBars(L10n.s("各年阅读时长", "Yearly reading"), years)
                }
            }
        }
    }
}
