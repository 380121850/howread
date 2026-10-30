package howread.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import howread.core.TestLog
import howread.core.data.Db
import howread.engine.DocumentHandle
import howread.engine.EngineLib
import java.io.File
import kotlin.system.exitProcess
import kotlinx.coroutines.delay

/** 无 GUI 冒烟（服务器/CI 用）：每本书渲前 3 页 @800px，evt 打桩 + 退出码。 */
object SmokeMain {
    fun main(args: Array<String>) {
        TestLog.evt("smoke_begin")
        EngineLib.ensure()
        var ok = true
        for (p in args) {
            val t0 = System.currentTimeMillis()
            var d: DocumentHandle? = null
            try {
                d = DocumentHandle(p)
                val n = d.pageCount()
                var lastDim = ""
                for (pg in 0 until minOf(3, n)) {
                    val img = d.render(pg, 800)
                    if (img.width < 10 || img.height < 10) ok = false
                    lastDim = "${img.width}x${img.height}"
                }
                TestLog.evt("smoke_book_ok", "name=${File(p).name} pages=$n dim=$lastDim ${System.currentTimeMillis() - t0}ms")
                println("OK ${File(p).name} pages=$n")
            } catch (t: Throwable) {
                ok = false
                TestLog.evt("smoke_book_fail", "name=${File(p).name} err=$t")
                println("FAIL ${File(p).name} : $t")
            } finally {
                runCatching { d?.close() }   // 检视修复：渲染抛异常时句柄也要关
            }
        }
        TestLog.evt("smoke_end", if (ok) "ALL_OK" else "FAILURES")
        println(if (ok) "SMOKE RESULT: ALL OK" else "SMOKE RESULT: FAILURES")
        exitProcess(if (ok) 0 else 2)
    }
}

/** GUI 冒烟（Windows 宿主）：真窗口组 ShellScreen（横幅/格言侧栏）+ 周/月/年弹窗，断言已读过滤，evt 打桩后自动退出。 */
object SmokeGuiMain {
    fun main(readerBook: String? = null) {
        application {
            val st = remember { AppState() }
            Window(
                onCloseRequest = {}, title = "HowReadPro-Smoke",
                state = rememberWindowState(width = 1100.dp, height = 720.dp),
            ) {
                ShellScreen(st, this.window)
            }
            Window(
                onCloseRequest = {}, title = "HowReadPro-Smoke-Dlg",
                state = rememberWindowState(width = 640.dp, height = 540.dp),
            ) {
                MaterialTheme(colorScheme = if (UiTheme.dark) darkColorScheme() else lightColorScheme()) {
                    ReadingTimeDialog(onDismiss = { TestLog.evt("smokegui_dlg_dismiss") })
                }
            }
            if (readerBook != null) {
                val rws = rememberWindowState(width = 1000.dp, height = 760.dp)
                Window(
                    onCloseRequest = {}, title = "HowReadPro-Smoke-Reader",
                    state = rws,
                ) {
                    MaterialTheme(colorScheme = if (UiTheme.dark) darkColorScheme() else lightColorScheme()) {
                        ReaderScreen(readerBook, onClose = {}, smoke = true, smokeWindow = rws)
                    }
                }
            }
            LaunchedEffect(Unit) {
                TestLog.evt("smokegui_up")
                if (readerBook != null) {
                    var g = 0
                    while (!Sm29Probe.done && g < 450) { delay(100); g++ }
                }
                delay(if (readerBook != null) 800 else 4000)
                st.statusFilter = BookStatus.READING
                st.goto(MainTab.LIBRARY)
                val bad = st.visibleBooks().count { it.lastReadAt <= 0 }
                val all = Db.allDays()
                val ysum = PeriodStats.yearly(all).sumOf { it.seconds.toLong() }
                val wsum = PeriodStats.weekly(all).sumOf { it.seconds.toLong() }
                val inv = ysum == all.sumOf { it.seconds.toLong() }
                val ok = bad == 0 && inv && wsum <= ysum && (readerBook == null || Sm29Probe.ok)
                TestLog.evt("smokegui_assert", "bad=$bad inv=$inv w=$wsum y=$ysum")
                TestLog.evt("smokegui_end", if (ok) "OK" else "FAIL")
                println("SMOKEGUI " + if (ok) "OK" else "FAIL")
                exitProcess(if (ok) 0 else 2)
            }
        }
    }
}
