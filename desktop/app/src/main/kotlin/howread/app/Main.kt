package howread.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.material3.Text
import androidx.compose.ui.unit.sp
import howread.core.TestLog
import java.io.File

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--smoke") {
        SmokeMain.main(args.drop(1).toTypedArray())
        return
    }
    if (args.firstOrNull() == "--smokegui") {
        SmokeGuiMain.main(args.drop(1).firstOrNull { File(it).isFile })
        return
    }
    if (args.firstOrNull() == "--shots") {
        ShotsMain.main(args.drop(1).firstOrNull())
        return
    }
    // 数据目录解析+旧位置迁移（必须在任何 Db/偏好访问之前；v0.28.0 便携化）
    howread.core.AppPaths.init()
    // 恢复默认配置标记（v0.55.0）：确认复位后程序已退出，重启在最早处清空配置/索引/缓存
    AppProfiles.applyPendingReset()
    // 崩溃留痕（对齐安卓 crash.txt 全渠道留痕）：未捕获异常先落 data\logs\crash.txt，再走原处理器
    val prevUEH = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
        runCatching {
            val f = java.io.File(howread.core.AppPaths.logsDir, "crash.txt")
            f.parentFile?.mkdirs()
            f.appendText("=== " + java.time.LocalDateTime.now() + " thread=" + t.name + " ===\n" +
                e + "\n" + e.stackTrace.take(25).joinToString("\n") + "\n\n", Charsets.UTF_8)
            TestLog.evt("crash", e.toString())
        }
        prevUEH?.uncaughtException(t, e)
    }
    // 应用挂起的恢复（必须在任何 Db 使用之前）
    if (howread.core.Backup.applyPendingRestore()) {
        TestLog.evt("backup_restore_applied")
    }
    // Windows 任务栏图标（v0.30.0 白块修复）：进程级 Taskbar 图标 + 窗口 icon 双保险
    runCatching {
        java.awt.Taskbar.getTaskbar().iconImage =
            javax.imageio.ImageIO.read(Res::class.java.classLoader.getResource("howread_icon.png"))
        TestLog.evt("taskbar_icon", "ok")
    }.onFailure { TestLog.evt("taskbar_icon_fail", "$it") }
    TestLog.evt("app_start", "v=${howread.core.AppInfo.VERSION}")
    application {
        val appState = remember { AppState() }
        // 文件关联双击打开：命令行带书路径直接进阅读器
        val startFile = args.firstOrNull { !it.startsWith("--") && File(it).isFile }
        if (startFile != null) {
            LaunchedEffect(startFile) {
                TestLog.evt("open_via_assoc", startFile)
                appState.open(startFile)
            }
        }
        // 书籍在主窗口内渲染（单窗口体验）：阅读时标题跟随书名；窗口/任务栏图标 = 安卓平台同款
        val rb = appState.readerBook
        val appIcon = remember { Res.appIconPainter() }
        Window(
            onCloseRequest = ::exitApplication,
            title = if (rb != null) "HowReadPro - ${rb.name}" else "HowReadPro",
            icon = appIcon,
            state = rememberWindowState(width = 1200.dp, height = 780.dp),
        ) {
            // 应用密码锁（v0.51.0 对齐安卓 PasswordActivity：SHA-256 存偏好，冷启动门=进程内只过一次）
            val lockHash = LibPrefs.str("appLockPassword", "")
            var unlocked by remember { mutableStateOf(lockHash.isEmpty()) }
            if (!unlocked) {
                var pw by remember { mutableStateOf("") }
                var pwErr by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxSize().background(Color(0xFF101318)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("HowReadPro", color = Color.White, fontSize = 22.sp)
                        Spacer(Modifier.height(14.dp))
                        androidx.compose.material3.OutlinedTextField(
                            value = pw, onValueChange = { pw = it }, singleLine = true,
                            label = { Text(if (pwErr) L10n.s("密码错误", "Wrong password")
                                           else L10n.s("输入解锁密码", "Enter password"), color = Color(0xFF9AA3AF)) },
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            modifier = Modifier.width(300.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        androidx.compose.material3.Button(onClick = {
                            val h = java.security.MessageDigest.getInstance("SHA-256")
                                .digest(pw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                            if (h == lockHash) { unlocked = true; TestLog.evt("app_unlock", "ok") }
                            else { pwErr = true; TestLog.evt("app_unlock", "fail") }
                        }) { Text(L10n.s("解锁", "Unlock")) }
                    }
                }
            } else {
                // 蓝光滤镜（安卓 BrightnessHelper 桌面等价物）：全局暖色叠加层，无 pointerInput 不拦截点击
                Box(Modifier.fillMaxSize()) {
                    ShellScreen(appState, window) // Box 内 this=BoxScope，window 取外层 WindowScope
                    if (appState.blueFilter > 0) {
                        Box(Modifier.matchParentSize().background(
                            Color(if (appState.blueFilter >= 2) 0x3CFF9E4A else 0x24FF9E4A)))
                    }
                }
            }
        }
        if (appState.showAiConfig) {
            Window(
                onCloseRequest = { appState.showAiConfig = false },
                title = L10n.s("HowReadPro - AI 大模型配置", "HowReadPro - AI Setup"),
                state = rememberWindowState(width = 560.dp, height = 660.dp),
            ) {
                MaterialTheme(colorScheme = if (UiTheme.dark) darkColorScheme() else lightColorScheme()) {
                    AiConfigScreen(onClose = { appState.showAiConfig = false })
                }
            }
        }
    }
}
