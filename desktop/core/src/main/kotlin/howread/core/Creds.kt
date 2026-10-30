package howread.core

import java.util.Base64

/**
 * 凭据本机加密（v0.50.0 对齐安卓 AiCredentials 的 "enc:v1:" 方案）。
 * Windows：DPAPI CryptProtectData（当前用户绑定，落盘密文，本机透明还原）；
 * 其它平台/异常：明文直通（开发机可跑）。旧明文值首次写入时自动升级为密文。
 */
object Creds {
    private const val PREFIX = "enc:v1:"
    private val win: Boolean = System.getProperty("os.name", "").lowercase().contains("windows")

    @Volatile
    private var tmpEnsured = false

    /** JNA 解包原生 dll 依赖可写临时目录：一律指到 data\tmp（TMP 不可写/受限环境免疫，且便携目录自包含）。 */
    private fun ensureJnaTempDir() {
        if (tmpEnsured) return
        tmpEnsured = true
        runCatching {
            val t = java.io.File(AppPaths.dataDir, "tmp")
            t.mkdirs()
            System.setProperty("jna.tmpdir", t.absolutePath)
            System.setProperty("jna.nosys", "true")
            TestLog.evt("creds_jna_tmpdir", t.absolutePath)
        }
    }

    fun protect(v: String): String {
        if (v.isEmpty() || !win || v.startsWith(PREFIX)) return v
        ensureJnaTempDir()
        return runCatching {
            val enc = com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(v.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.getEncoder().encodeToString(enc)
        }.onFailure { TestLog.evt("creds_protect_fail", it.toString()) }.getOrDefault(v)
    }

    fun unprotect(v: String): String {
        if (!v.startsWith(PREFIX)) return v
        ensureJnaTempDir()
        return runCatching {
            String(com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(
                Base64.getDecoder().decode(v.substring(PREFIX.length))), Charsets.UTF_8)
        }.onFailure { TestLog.evt("creds_unprotect_fail", it.toString()) }.getOrDefault("")
    }
}
