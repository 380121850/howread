package howread.core.remote

import howread.core.model.RemoteSource
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/**
 * OPDS 目录客户端（v2）：Atom 浏览/翻页 + 目录内搜索（OpenSearch 描述发现，feed 级
 * rel=search）+ Basic/Digest 认证（401 Digest 按 RFC2617 MD5 重试一次）+ 条目封面/
 * 摘要。src.host 存目录地址（完整 URL 或 host[:port]+path）；relPath 一律绝对 URL。
 */
class OpdsClient(private val src: RemoteSource) : RemoteClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** OpenSearch 搜索模板（绝对 URL，list 时从 feed 级 rel=search 描述发现并缓存）。 */
    @Volatile var searchTemplate: String? = null
        private set

    private fun rootUrl(): String {
        var u = src.host.trim()
        if (!u.startsWith("http")) u = "http://$u"
        val hostPart = u.substringAfter("://").substringBefore('/')
        if (':' !in hostPart && src.port !in listOf(0, 80, 443)) {
            u = u.replaceFirst("://", "://" + hostPart + ":" + src.port)
        }
        if (src.path.isNotBlank()) u = u.trimEnd('/') + "/" + src.path.trim('/')
        return u
    }

    private fun basicToken(): String? =
        if (src.user.isNotBlank()) java.util.Base64.getEncoder()
            .encodeToString((src.user + ":" + src.password).toByteArray(Charsets.UTF_8))
        else null

    private fun rawGet(url: String, digestAuth: String? = null): Response {
        val b = Request.Builder().url(url).get()
        if (digestAuth != null) b.header("Authorization", digestAuth)
        else basicToken()?.let { b.header("Authorization", "Basic $it") }
        return http.newCall(b.build()).execute()
    }

    private fun get(url: String): InputStream {
        val r1 = rawGet(url)
        if (r1.code == 401) {
            val ch = r1.header("WWW-Authenticate") ?: ""
            r1.close()
            if (ch.trim().startsWith("Digest", ignoreCase = true)) {
                val params = HttpDigest.parseChallenge(ch)
                val u = URI(url)
                val uri = (u.rawPath ?: "") + (u.rawQuery?.let { "?" + it } ?: "")
                val auth = HttpDigest.authorize("GET", uri, src.user, src.password, params)
                if (auth != null) {
                    val r2 = rawGet(url, auth)
                    if (r2.isSuccessful) return r2.body!!.byteStream()
                    val code2 = r2.code
                    r2.close()
                    throw RuntimeException("HTTP $code2 $url (digest)")
                }
            }
            throw RuntimeException("HTTP 401 $url")
        }
        if (!r1.isSuccessful) {
            val code = r1.code
            r1.close()
            throw RuntimeException("HTTP $code $url")
        }
        return r1.body!!.byteStream()
    }

    private fun absolute(base: String, href: String): String = runCatching {
        URI(base).resolve(href).toString()
    }.getOrDefault(href)

    /** OpenSearch 模板转绝对 URL——模板含 {searchTerms} 花括号，URI.resolve 直接抛
     *  URISyntaxException，须手工拼或先转义再还原。 */
    private fun resolveTemplate(descUrl: String, tpl: String): String = when {
        tpl.startsWith("http", true) -> tpl
        tpl.startsWith("/") -> runCatching {
            val u = URI(descUrl)
            u.scheme + "://" + (u.rawAuthority ?: "") + tpl
        }.getOrDefault("")
        else -> runCatching {
            URI(descUrl).resolve(tpl.replace("{", "%7B").replace("}", "%7D")).toString()
                .replace("%7B", "{").replace("%7D", "}")
        }.getOrDefault("")
    }

    private fun stripHtml(s: String): String = s
        .replace(Regex("<[^>]*>"), " ")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("\\s+"), " ")

    /** 解析 Atom feed：条目（subsection/collection=目录、acquisition=书，含封面/摘要）
     *  + feed 级 next 翻页 + rel=search 搜索描述（发现后缓存模板）。 */
    fun parseFeed(xml: InputStream, baseUrl: String): List<RemoteEntry> {
        val out = ArrayList<RemoteEntry>()
        var nextHref: String? = null
        var searchDescUrl: String? = null
        val text = StringBuilder()
        var capture = ""            // 当前捕获的 entry 级文本元素：title/summary/content
        var inEntry = false
        var entryTitle = ""
        var entrySummary = ""
        val links = ArrayList<Triple<String, String, String>>() // rel, href, type
        val f = XMLInputFactory.newInstance()
        f.setProperty(XMLInputFactory.IS_COALESCING, true)
        try {
            val r = f.createXMLStreamReader(xml)
            while (r.hasNext()) {
                when (r.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (r.localName) {
                        "entry" -> { inEntry = true; links.clear(); entryTitle = ""; entrySummary = ""; text.setLength(0) }
                        "title" -> if (inEntry) { capture = "title"; text.setLength(0) }
                        "summary", "content" -> if (inEntry) { capture = r.localName; text.setLength(0) }
                        "link" -> {
                            val rel = r.getAttributeValue(null, "rel") ?: ""
                            val href = r.getAttributeValue(null, "href") ?: ""
                            val type = r.getAttributeValue(null, "type") ?: ""
                            if (inEntry) links.add(Triple(rel, href, type))
                            else when {
                                rel == "next" && href.isNotBlank() -> nextHref = absolute(baseUrl, href)
                                rel == "search" && href.isNotBlank() && type.contains("opensearch") ->
                                    searchDescUrl = absolute(baseUrl, href)
                            }
                        }
                        else -> if (capture.isNotEmpty()) text.append(' ') // 嵌套标签当分隔
                    }
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA ->
                        if (inEntry && capture.isNotEmpty()) text.append(r.text)
                    XMLStreamConstants.END_ELEMENT -> when (r.localName) {
                        "title" -> if (capture == "title") { entryTitle = text.toString().replace("\n", " ").trim(); capture = "" }
                        "summary", "content" -> if (capture == r.localName) {
                            if (entrySummary.isEmpty()) entrySummary = stripHtml(text.toString()).trim()
                            capture = ""
                        }
                        "entry" -> {
                            inEntry = false
                            if (entryTitle.isNotEmpty()) {
                                val sub = links.firstOrNull { it.first.contains("subsection") || it.first.contains("collection") }
                                val acq = links.firstOrNull { it.first.contains("acquisition") }
                                val cover = links.firstOrNull { it.first.contains("image/thumbnail") && it.second.isNotBlank() }
                                    ?: links.firstOrNull { it.first.contains("image") && it.second.isNotBlank() }
                                    ?: links.firstOrNull { it.third.startsWith("image/") && it.second.isNotBlank() }
                                when {
                                    sub != null && sub.second.isNotBlank() ->
                                        out.add(RemoteEntry(entryTitle, true, 0, absolute(baseUrl, sub.second),
                                            cover?.let { absolute(baseUrl, it.second) } ?: "", entrySummary.take(300)))
                                    acq != null && acq.second.isNotBlank() ->
                                        out.add(RemoteEntry(entryTitle, false, 0, absolute(baseUrl, acq.second),
                                            cover?.let { absolute(baseUrl, it.second) } ?: "", entrySummary.take(300)))
                                }
                            }
                            links.clear()
                        }
                    }
                }
            }
        } finally {
            runCatching { xml.close() }
        }
        searchDescUrl?.let { d ->
            if (searchTemplate == null) {
                runCatching {
                    get(d).use { ins ->
                        val tpl = Regex("template\\s*=\\s*[\"']([^\"']*)[\"']").find(ins.readBytes().toString(Charsets.UTF_8))?.groupValues?.get(1) ?: ""
                        if (tpl.isNotBlank()) searchTemplate = resolveTemplate(d, tpl)
                    }
                }
            }
        }
        nextHref?.let { out.add(RemoteEntry("下一页 ›", true, 0, it)) }
        return out
    }

    override fun list(relDir: String): List<RemoteEntry> {
        val url = if (relDir.isBlank()) rootUrl() else relDir
        return parseFeed(get(url), url)
    }

    override fun search(query: String): List<RemoteEntry>? {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        if (searchTemplate == null) runCatching { list("") } // 根 feed 发现一次搜索描述
        val tpl = searchTemplate ?: return null
        val url = tpl.replace("{searchTerms}", URLEncoder.encode(q, "UTF-8"))
            .replace(Regex("\\{(?!searchTerms\\})[^}]*\\}"), "") // 其余 OpenSearch 参数留空
        return parseFeed(get(url), url)
    }

    override fun fetchBytes(url: String): ByteArray? = try {
        get(url).use { it.readBytes() }
    } catch (_: Exception) {
        null
    }

    override fun open(relPath: String): InputStream = get(relPath)

    override fun test(): String {
        val n = list("").size
        return "OPDS 连接成功，目录 $n 项" + (if (searchTemplate != null) "，支持目录内搜索" else "")
    }

    override fun close() {}
}
