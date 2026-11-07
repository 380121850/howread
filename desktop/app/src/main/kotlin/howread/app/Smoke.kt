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


/** 截图对比探针（Windows 宿主）：ShellScreen 真窗口内导航七页 + Robot 抓窗存 PNG 后退出（对齐 Qt --shotsmoke）。 */
object ShotsMain {
    private class Ref { @Volatile var win: java.awt.Window? = null }

    fun main(outDirArg: String?) {
        howread.core.AppPaths.init()
        val outDir = File(outDirArg ?: "shots").apply { mkdirs() }
        val ref = Ref()
        application {
            val st = remember { AppState() }
            Window(
                onCloseRequest = {}, title = "HowReadPro-Shots",
                state = rememberWindowState(width = 1280.dp, height = 820.dp),
            ) {
                val w = this.window
                androidx.compose.runtime.SideEffect { ref.win = w }
                ShellScreen(st, w)
            }
            LaunchedEffect(Unit) {
                TestLog.evt("shots_begin")
                fun shot(name: String) {
                    val w = ref.win ?: return
                    runCatching {
                        val r = java.awt.Robot()
                        val rect = java.awt.Rectangle(
                            w.locationOnScreen.x, w.locationOnScreen.y, w.width, w.height)
                        val img = r.createScreenCapture(rect)
                        javax.imageio.ImageIO.write(img, "png", File(outDir, name))
                        TestLog.evt("shots_png", "$name ${img.width}x${img.height}")
                    }.onFailure { TestLog.evt("shots_png_fail", "$name $it") }
                }
                delay(2800)
                shot("home.png")
                st.goto(MainTab.LIBRARY); delay(1300); shot("library.png")
                st.goto(MainTab.BROWSE); delay(1300); shot("browse.png")
                st.goto(MainTab.PREFS); delay(1300); shot("prefs.png")
                st.extra = ExtraContent.REMOTE; delay(1300); shot("remote.png")
                st.extra = ExtraContent.BOOKMARKS; delay(1100); shot("bookmarks.png")
                st.extra = ExtraContent.FAVORITES; delay(1100); shot("favorites.png")
                // v0.87.0 二轮：书库四视图
                st.goto(MainTab.LIBRARY); delay(600)
                st.libView = LibView.LIST; delay(1100); shot("view_list.png")
                st.libView = LibView.TABLE; delay(1100); shot("view_table.png")
                st.libView = LibView.SHELF; delay(1100); shot("view_shelf.png")
                st.libView = LibView.GROUP_AUTHOR; delay(1100); shot("view_gauthor.png")
                st.libView = LibPrefs.defaultView(); delay(400)
                // v0.87.0 二轮：夜间模式三页
                UiTheme.applyDark(true); delay(1200)
                st.goto(MainTab.HOME); st.extra = ExtraContent.NONE; delay(1200); shot("dark_home.png")
                st.goto(MainTab.LIBRARY); delay(1100); shot("dark_library.png")
                st.goto(MainTab.PREFS); delay(1100); shot("dark_prefs.png")
                UiTheme.applyDark(false); delay(600)
                val first = st.library.firstOrNull { it.path.isNotEmpty() }
                if (first != null) {
                    st.open(first.path); delay(8000); shot("reader.png")
                    st.closeReader(); delay(700)
                }
                TestLog.evt("shots_end")
                println("SHOTS DONE")
                exitProcess(0)
            }
        }
    }
}
