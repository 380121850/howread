package howread.core.remote

import java.security.MessageDigest

/** RFC2617 Digest（MD5）最小实现——okhttp 无内置 Digest，OPDS 站点认证常用。 */
object HttpDigest {

    /** 解析 WWW-Authenticate: "Digest realm=\"x\", nonce=\"y\", qop=\"auth\"" → 小写键、去引号。 */
    fun parseChallenge(header: String): Map<String, String> {
        val body = header.trim().let { if (it.startsWith("Digest", true)) it.substring(6) else it }
        val out = LinkedHashMap<String, String>()
        for (part in body.split(',')) {
            val i = part.indexOf('=')
            if (i <= 0) continue
            val k = part.substring(0, i).trim().lowercase()
            var v = part.substring(i + 1).trim()
            if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length - 1)
            out[k] = v
        }
        return out
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** 生成 Authorization 头值；algorithm 非 MD5（不支持）返回 null。 */
    fun authorize(method: String, uri: String, user: String, pass: String, ch: Map<String, String>): String? {
        val realm = ch["realm"] ?: return null
        val nonce = ch["nonce"] ?: return null
        if ((ch["algorithm"] ?: "MD5").uppercase() != "MD5") return null
        val qop = ch["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it == "auth" }
        val ha1 = md5("$user:$realm:$pass")
        val ha2 = md5("$method:$uri")
        val cnonce = md5(nonce + System.nanoTime()).take(16)
        val nc = "00000001"
        val response = if (qop != null) md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2") else md5("$ha1:$nonce:$ha2")
        val sb = StringBuilder("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", ")
            .append("uri=\"$uri\", response=\"$response\"")
        ch["opaque"]?.let { sb.append(", opaque=\"").append(it).append('\"') }
        if (qop != null) sb.append(", qop=auth, nc=$nc, cnonce=\"$cnonce\", algorithm=MD5")
        return sb.toString()
    }
}
