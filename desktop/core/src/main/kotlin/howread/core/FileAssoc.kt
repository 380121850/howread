package howread.core

import java.io.File

/** Windows 文件关联（HKCU 当前用户注册表，无需管理员）。 */
object FileAssoc {
    val EXTS = listOf("epub", "pdf", "mobi", "azw3", "fb2", "txt", "cbz")
    private const val PROGID = "HowReadPro.Book"

    fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")

    /** 启动命令（javaw + 本程序实际安装位置的 engine/app 路径 + "%1" 书路径）。 */
    fun openCommand(): String? = runCatching {
        // 软件根目录统一由 AppPaths.rootDir 推导（v0.28.0；UNC 转换也在其中）
        val dist = AppPaths.rootDir
        val appDir = File(dist, "app")
        val javaw = File(System.getProperty("java.home"), "bin/javaw.exe")
        if (!javaw.isFile || !File(dist, "engine").isDirectory) return@runCatching null
        "\"" + javaw.absolutePath + "\" -Djava.library.path=\"" + File(dist, "engine").absolutePath +
            "\" -cp \"" + File(appDir, "*").absolutePath + "\" howread.app.MainKt \"%1\""
    }.getOrNull()

    private fun reg(vararg args: String): Boolean = runCatching {
        val p = ProcessBuilder(listOf("reg", *args)).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        p.waitFor() == 0
    }.getOrDefault(false)

    /** reg.exe 的 /d 无法承载内嵌引号（argv 原样传也报无效语法），改写临时 .reg 导入。 */
    private fun regImport(body: String): Boolean = runCatching {
        val tmp = File.createTempFile("howread-assoc", ".reg")
        try {
            val content = "\uFEFFWindows Registry Editor Version 5.00\r\n\r\n" + body
            java.io.DataOutputStream(java.io.FileOutputStream(tmp)).use { dos ->
                dos.write(content.toByteArray(Charsets.UTF_16LE))
            }
            val p = ProcessBuilder("reg", "import", tmp.absolutePath).redirectErrorStream(true).start()
            p.inputStream.readBytes()
            p.waitFor() == 0
        } finally {
            tmp.delete()
        }
    }.getOrDefault(false)

    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    fun associate(): String {
        val cmd = openCommand() ?: return "无法定位程序目录（dev 运行或目录不完整）"
        val sb = StringBuilder()
        sb.append("[HKEY_CURRENT_USER\\Software\\Classes\\").append(PROGID).append("]\r\n")
            .append("@=\"HowRead 电子书\"\r\n")
        sb.append("[HKEY_CURRENT_USER\\Software\\Classes\\").append(PROGID).append("\\shell\\open\\command]\r\n")
            .append("@=\"").append(esc(cmd)).append("\"\r\n")
        for (e in EXTS) {
            sb.append("[HKEY_CURRENT_USER\\Software\\Classes\\.").append(e).append("]\r\n")
                .append("@=\"").append(PROGID).append("\"\r\n")
        }
        val ok = regImport(sb.toString())
        return if (ok) "已建立关联：" + EXTS.joinToString(" ") { "." + it } else "写入失败（reg import 返回非 0）"
    }

    fun disassociate(): String {
        reg("delete", "HKCU\\Software\\Classes\\$PROGID", "/f")
        var n = 0
        for (e in EXTS) {
            val q = runCatching {
                val p = ProcessBuilder("reg", "query", "HKCU\\Software\\Classes\\.$e", "/ve")
                    .redirectErrorStream(true).start()
                String(p.inputStream.readBytes(), Charsets.UTF_8).also { p.waitFor() }
            }.getOrDefault("")
            if (q.contains(PROGID)) {
                reg("delete", "HKCU\\Software\\Classes\\.$e", "/ve", "/f")
                n++
            }
        }
        return "已解除关联（清理 $n 个扩展的默认值与程序项）"
    }
}
