package howread.core

import java.io.File

/**
 * 桌面版数据根目录（v0.28.0 便携化）。
 *
 * Windows 默认与软件根目录保持一致（<根>\data，绿色便携：书库、进度、书签、配置、缓存随软件走）；
 * 可用软件根目录下 data-dir.txt（一行 UTF-8 路径）或环境变量 HOWREAD_DATA 改到任意位置。
 * Linux 保持 ~/.howread 不变。
 *
 * dataDir 首次访问时解析并定死；Main 启动最早处调用 init() 完成解析、建目录与
 * 旧位置（%LOCALAPPDATA%\HowRead）数据的一次性自动迁移。
 */
object AppPaths {

    val isWindows: Boolean = System.getProperty("os.name", "").lowercase().contains("win")

    /** 软件根目录：程序 jar 位于 <根>\app\ 时取其父目录，否则取 jar 所在目录（开发态 dist）。
     *  网络盘 location 是带 authority 的 file:// URI，File(URI) 会抛 -> 转 UNC。 */
    val rootDir: File by lazy {
        runCatching {
            val loc = AppPaths::class.java.protectionDomain.codeSource.location
            val jar = try {
                File(loc.toURI())
            } catch (_: IllegalArgumentException) {
                File("\\" + loc.authority + loc.path.replace('/', '\\'))
            }
            val appDir = jar.parentFile
            if (appDir != null && appDir.name.equals("app", true) && appDir.parentFile != null)
                appDir.parentFile
            else appDir ?: File(".")
        }.getOrDefault(File("."))
    }

    private fun overridePath(): String? {
        System.getenv("HOWREAD_DATA")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        if (!isWindows) return null
        val cfg = File(rootDir, "data-dir.txt")
        if (cfg.isFile) runCatching {
            val line = cfg.readText().trimStart('\uFEFF'.toInt().toChar()).trim()
            if (line.isNotEmpty()) return line
        }
        return null
    }

    /** 偏好中是否设置了自定义数据目录。 */
    fun overrideConfigured(): Boolean = overridePath() != null

    val dataDir: File by lazy {
        val want = overridePath()?.let { File(it) }
            ?: if (isWindows) File(rootDir, "data") else File(System.getProperty("user.home"), ".howread")
        runCatching { want.mkdirs() }
        if (want.isDirectory) want
        else {
            // 自定义/根目录位不可写（只读盘等）：回退系统位，保证可用
            val fb = if (isWindows) {
                val la = System.getenv("LOCALAPPDATA")
                if (la != null) File(la, "HowRead") else File(System.getProperty("user.home"), ".howread")
            } else File(System.getProperty("user.home"), ".howread")
            runCatching { fb.mkdirs() }
            TestLog.evt("datadir_fallback", "want=" + want.absolutePath.take(100))
            fb
        }
    }

    val dbFile: File get() = File(dataDir, "howread.db")
    val cacheDir: File get() = File(dataDir, "cache").apply { mkdirs() }
    val coversDir: File get() = File(cacheDir, "covers")
    val logsDir: File get() = File(dataDir, "logs")
    val fontsDir: File get() = File(dataDir, "fonts")

    /** 启动最早处调用（任何 Db/偏好访问之前）：解析数据目录并迁移旧位置数据。 */
    fun init() {
        runCatching { cacheDir.mkdirs() }
        migrateLegacyIfNeeded(dataDir)
    }

    /** 偏好更改数据目录：写 <根>\data-dir.txt，重启后生效。 */
    fun changeDataDir(newPath: String): Boolean = runCatching {
        File(rootDir, "data-dir.txt").writeText(newPath.trim() + "\n")
        true
    }.getOrDefault(false)

    /** 恢复默认（删除 data-dir.txt），重启后生效。 */
    fun clearDataDirOverride(): Boolean = runCatching {
        File(rootDir, "data-dir.txt").delete()
        true
    }.getOrDefault(false)

    /** 旧位置（%LOCALAPPDATA%\HowRead）有库而当前位置没有 -> 一次性整目录复制（不覆盖已有文件）。 */
    private fun migrateLegacyIfNeeded(target: File) {
        if (!isWindows) return
        val local = System.getenv("LOCALAPPDATA") ?: return
        val legacy = File(local, "HowRead")
        if (!File(legacy, "howread.db").isFile) return
        // 检视修复：完成标记取代"db 已存在"哨兵——复制中断后可续补（否则永久留下不完整库）
        if (File(target, ".hr_migrated").exists()) return
        if (runCatching { target.canonicalPath == legacy.canonicalPath }.getOrDefault(true)) return
        val t0 = System.currentTimeMillis()
        var n = 0
        val ok = runCatching {
            legacy.walkTopDown().forEach { src ->
                if (!src.isFile) return@forEach
                val rel = src.relativeTo(legacy).path.replace('\\', '/')
                if (rel == "logs" || rel.startsWith("logs/")) return@forEach
                val dst = File(target, rel)
                dst.parentFile?.mkdirs()
                if (!dst.exists()) runCatching { src.copyTo(dst); n++ }
            }
            true
        }.getOrDefault(false)
        TestLog.evt("migrate_legacy_data", "ok=$ok files=$n ms=" + (System.currentTimeMillis() - t0) + " to=" + target.absolutePath.take(110))
        if (ok) runCatching { File(target, ".hr_migrated").createNewFile() }
    }
}
