package howread.core.sync

import com.github.sardine.SardineFactory
import howread.core.AppPaths
import howread.core.TestLog
import howread.core.ai.AiConfigStore
import howread.core.data.Db
import howread.core.model.Bookmark
import howread.core.model.RemoteSource
import howread.core.reader.ReaderPrefsStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebDAV 双向同步（v2）：阅读进度 / 书签笔记 / 我的珍藏 + 本机配置（AI/排版/主题），
 * 单文件 JSON 存放在所选 WebDAV 服务器起始目录（HowRead-sync-desktop.json）。
 * 流程 = 拉取远端 -> 合并进本机 -> 合并态回传远端（一次"同步"完成双向）。
 * 合并规则：进度按 updated_at 新者胜；书签按 (path,page,label) 去重、笔记互补；
 * 珍藏取并集；配置块（ai.json/reader.json/ui.json）按"文件修改时间 vs 远端 at"新者胜。
 * 限制：删除不跨设备传播（无墓碑）；跨机器书文件路径不同则视为不同书；配置为整块覆盖不做字段级合并。
 */
object SyncEngine {

    class Result {
        var ok = false
        var error = ""   // ""|"auth"|"network"|"busy"|"other"
        var detail = ""
        var progressAdopted = 0
        var progressNewer = 0
        var bookmarksAdded = 0
        var bookmarksMerged = 0
        var favoritesAdded = 0
        var configsAdopted = 0   // 采纳的配置块数
        var configNames = ""     // 采纳的配置名（逗号分隔）
        var themeAdopted = false // ui.json 被采纳（app 层即时应用主题）
    }

    private const val FILE = "HowRead-sync-desktop.json"
    private val busy = AtomicBoolean(false)

    /** 参与同步的本机配置文件（dataDir 下）。 */
    private val CONFIG_FILES = listOf("ai.json", "reader.json", "ui.json")

    /** 同步目标文件完整 URL（服务器起始目录 + FILE）。 */
    fun fileUrl(src: RemoteSource): String {
        val base = (if (src.host.startsWith("http")) src.host else "http://" + src.host + ":" + src.port).trimEnd('/')
        val mid = src.path.trim('/')
        return if (mid.isEmpty()) "$base/$FILE" else "$base/$mid/$FILE"
    }

    fun sync(src: RemoteSource): Result {
        val r = Result()
        if (!busy.compareAndSet(false, true)) {
            r.error = "busy"
            return r
        }
        try {
            val sardine = SardineFactory.begin(src.user, src.password)
            val url = fileUrl(src)
            val remote = try {
                sardine.get(url).use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (e: Exception) {
                // 检视修复：只认 SardineException 的 404 状态码（字符串 contains 会把主机名/URL 恰含 404 的失败误判为不存在→PUT 覆盖远端）
                val code = (e as? com.github.sardine.impl.SardineException)?.statusCode
                if (code == 404) "" else throw e
            }
            var cfgs: Map<String, Long> = CONFIG_FILES.associateWith { localAt(it) }
            if (remote.isNotBlank()) {
                val j = JSONObject(remote)
                mergeProgress(j.optJSONArray("progress") ?: JSONArray(), r)
                mergeBookmarks(j.optJSONArray("bookmarks") ?: JSONArray(), r)
                mergeFavorites(j.optJSONArray("favorites") ?: JSONArray(), r)
                cfgs = mergeConfigs(j.optJSONObject("configs"), r)
            }
            sardine.put(url, exportLocal(cfgs).toString().toByteArray(Charsets.UTF_8))
            r.ok = true
            SyncPrefs.saveLastSyncAt(System.currentTimeMillis())
            TestLog.evt("sync_done", "adopt=${r.progressAdopted} newer=${r.progressNewer} " +
                "bmAdd=${r.bookmarksAdded} bmMerge=${r.bookmarksMerged} favAdd=${r.favoritesAdded} cfg=${r.configsAdopted}")
        } catch (e: Exception) {
            val s = e.toString()
            r.error = if (s.contains("401") || s.contains("403")) "auth" else "network"
            r.detail = s.take(160)
            TestLog.evt("sync_fail", r.error + " " + r.detail)
        } finally {
            busy.set(false)
        }
        return r
    }

    // ---- 配置块（新者胜；at=远端时间戳，本地=文件 mtime） ----
    private fun localAt(name: String): Long {
        val f = File(AppPaths.dataDir, name)
        return if (f.isFile) f.lastModified() else 0L
    }

    /** 返回 name -> 有效时间戳（采纳远端时为远端 at，否则本地 mtime）。 */
    private fun mergeConfigs(remoteCfg: JSONObject?, r: Result): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for (name in CONFIG_FILES) {
            val rem = remoteCfg?.optJSONObject(name)
            val remAt = rem?.optLong("at", 0L) ?: 0L
            val locAt = localAt(name)
            if (rem != null && remAt > locAt) {
                val j = JSONObject(rem.toString())
                j.remove("at")
                if (name == "ai.json") {
                    // 发布时已剥离 apiKey（各机自持，对齐安卓 KeyStore 不随同步走）——采纳时保留本机既有 Key
                    val loc = File(AppPaths.dataDir, name)
                    val locKey = if (loc.isFile) runCatching {
                        JSONObject(loc.readText(Charsets.UTF_8)).optString("apiKey", "")
                    }.getOrDefault("") else ""
                    if (locKey.isNotBlank()) j.put("apiKey", locKey)
                }
                val f = File(AppPaths.dataDir, name)
                f.parentFile?.mkdirs()
                f.writeText(j.toString(), Charsets.UTF_8)
                r.configsAdopted++
                r.configNames = (if (r.configNames.isEmpty()) "" else r.configNames + ",") + name
                if (name == "ui.json") r.themeAdopted = true
                if (name == "ai.json") AiConfigStore.invalidate()
                if (name == "reader.json") ReaderPrefsStore.invalidate()
                out[name] = remAt
            } else {
                out[name] = locAt
            }
        }
        return out
    }

    private fun mergeProgress(arr: JSONArray, r: Result) {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val path = o.optString("path")
            if (path.isBlank()) continue
            val page = o.optInt("page", 0)
            val pc = o.optInt("pageCount", 0)
            val vw = o.optInt("viewportW", 0)
            val ts = o.optLong("updatedAt", 0L)
            val local = Db.loadProgress(path)
            if (local == null || ts > local.updatedAt) {
                Db.saveProgressRaw(path, page, pc, vw, ts)
                r.progressAdopted++
            } else {
                r.progressNewer++
            }
        }
    }

    private fun mergeBookmarks(arr: JSONArray, r: Result) {
        val byKey = HashMap<String, Bookmark>()
        for ((b, _) in Db.allBookmarks(200000)) byKey[b.path + "|" + b.page + "|" + b.label] = b
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val path = o.optString("path")
            if (path.isBlank()) continue
            val page = o.optInt("page", 0)
            val label = o.optString("label", "")
            val note = o.optString("note", "")
            val created = o.optLong("createdAt", 0L)
            val hit = byKey[path + "|" + page + "|" + label]
            if (hit == null) {
                Db.addBookmarkRaw(path, page, label, note, created)
                r.bookmarksAdded++
            } else if (hit.note.isBlank() && note.isNotBlank()) {
                Db.updateBookmarkNote(hit.id, note)
                r.bookmarksMerged++
            }
        }
    }

    private fun mergeFavorites(arr: JSONArray, r: Result) {
        val local = HashSet(Db.listFavoritePaths())
        for (i in 0 until arr.length()) {
            val p = arr.optString(i)
            if (p.isNotBlank() && p !in local && Db.addFavoriteIfMissing(p)) {
                local.add(p)
                r.favoritesAdded++
            }
        }
    }

    private fun exportLocal(cfgs: Map<String, Long>): JSONObject {
        val prog = JSONArray()
        for (p in Db.listAllProgress()) {
            prog.put(JSONObject()
                .put("path", p.path).put("page", p.page).put("pageCount", p.pageCount)
                .put("viewportW", p.viewportW).put("updatedAt", p.updatedAt))
        }
        val bms = JSONArray()
        for ((b, _) in Db.allBookmarks(200000)) {
            bms.put(JSONObject()
                .put("path", b.path).put("page", b.page).put("label", b.label)
                .put("note", b.note).put("createdAt", b.createdAt))
        }
        val favs = JSONArray()
        for (f in Db.listFavoritePaths()) favs.put(f)
        val cfg = JSONObject()
        for ((name, at) in cfgs) {
            val f = File(AppPaths.dataDir, name)
            val j = if (f.isFile) runCatching { JSONObject(f.readText(Charsets.UTF_8)) }.getOrNull() else null
            if (j != null) {
                if (name == "ai.json") j.remove("apiKey") // 对齐安卓：API Key 不进同步包
                cfg.put(name, j.put("at", at))
            }
        }
        return JSONObject()
            .put("version", 2)
            .put("device", deviceName())
            .put("exportedAt", System.currentTimeMillis())
            .put("progress", prog)
            .put("bookmarks", bms)
            .put("favorites", favs)
            .put("configs", cfg)
    }

    fun deviceName(): String =
        System.getenv("COMPUTERNAME")
            ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("desktop")
}

/** 同步偏好（服务器 id / 自动同步间隔分钟 / 上次同步时间；dataDir/sync.json）。 */
object SyncPrefs {
    private val file: File get() = File(AppPaths.dataDir, "sync.json")

    @Synchronized
    private fun edit(fn: (JSONObject) -> Unit) {
        val j = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrDefault(JSONObject())
        fn(j)
        runCatching { file.writeText(j.toString(), Charsets.UTF_8) }
    }

    fun loadServerId(default: Long): Long =
        runCatching { JSONObject(file.readText(Charsets.UTF_8)).optLong("serverId", default) }.getOrDefault(default)
    fun saveServerId(id: Long) = edit { it.put("serverId", id) }

    /** 自动同步间隔分钟（0=关闭）。 */
    fun loadIntervalMin(): Int =
        runCatching { JSONObject(file.readText(Charsets.UTF_8)).optInt("intervalMin", 0) }.getOrDefault(0)
    fun saveIntervalMin(v: Int) = edit { it.put("intervalMin", v) }

    fun loadLastSyncAt(): Long =
        runCatching { JSONObject(file.readText(Charsets.UTF_8)).optLong("lastSyncAt", 0L) }.getOrDefault(0L)
    fun saveLastSyncAt(v: Long) = edit { it.put("lastSyncAt", v) }

    // ---- v0.45.0：同步连接配置（对齐安卓 WebDAV 同步对话框：地址/账号/密码独立填写，与网上书库服务器解耦） ----
    fun loadSyncUrl(default: String): String =
        runCatching { JSONObject(file.readText(Charsets.UTF_8)).optString("syncUrl", default) }.getOrDefault(default)
    fun saveSyncUrl(v: String) = edit { it.put("syncUrl", v) }
    fun loadSyncUser(default: String): String =
        runCatching { JSONObject(file.readText(Charsets.UTF_8)).optString("syncUser", default) }.getOrDefault(default)
    fun saveSyncUser(v: String) = edit { it.put("syncUser", v) }
    fun loadSyncPass(default: String): String =
        runCatching { JSONObject(file.readText(Charsets.UTF_8)).optString("syncPass", default) }.getOrDefault(default)
    fun saveSyncPass(v: String) = edit { it.put("syncPass", v) }

    /** v0.54 同步目录（服务器上的同步根，默认 /HowRead，对齐安卓 dialog_webdav_sync）。 */
    fun loadSyncDir(default: String): String =
        runCatching { JSONObject(file.readText(Charsets.UTF_8)).optString("syncDir", default) }.getOrDefault(default)
    fun saveSyncDir(v: String) = edit { it.put("syncDir", v) }

    /** 由地址/账号/密码构建同步客户端（http(s)://host[:port]/path → RemoteSource）；解析失败返回 null。 */
    fun syncSource(url: String, user: String, pass: String, dir: String = "HowRead"): RemoteSource? {
        val u = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = u.host ?: return null
        val port = if (u.port > 0) u.port else if (scheme == "https") 443 else 80
        // v0.54 同步目录独立可配（不再兼用 url 路径）；空回退 HowRead
        return RemoteSource(0L, "WEBDAV", "sync", host, port, dir.trim('/').ifEmpty { "HowRead" }, user, pass)
    }
}
