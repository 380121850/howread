package howread.core.sync

import howread.core.TestLog
import howread.core.data.Db
import howread.core.model.RemoteSource
import java.io.File
import java.security.MessageDigest

/**
 * 与安卓（WebDavSyncer）互通的 WebDAV 阅读数据同步（v0.48.0）。
 *
 * 协议（对齐安卓 com.foobnix.webdav.WebDavSyncer）：
 *   远端布局：<服务器>/<远程目录=HowRead>/books/<MD5>.json
 *     info = { name: 文件名, hash: 内容MD5, t: 最新时间,
 *              progress: { p:百分0..1, pg:绝对页0基, pgc:全页数, t, ...安卓排版字段 },
 *              bookmarks: { "<创建时间key>": { path, text, p, pg, t, isF, ... } },
 *              dk: { "<key>": 删除时间 }（书签删除墓碑，其它设备传播） }
 *   书身份 = 书文件内容 MD5（跨设备同书同 hash，与路径无关）；
 *   进度合并 = 策略（newer=t 新者胜 / local / server / farther），整 entry 替换；
 *   书签合并 = 按 key 的 union，matched 时 path 重写为本地文件；
 *   安卓 global 目录里 app-State 等自身字段仍黑盒；桌面配置另走独立命名通道（下）。
 *
 * v0.51.0 两项对齐安卓的补齐：
 *   墓碑：deleteBookmark/removeBook 写 tombstone 表 → 发布时合入 dk 向其它设备传播删除，
 *   下行同时受远端 dk 与本地墓碑双重抑制（删了不再复活）；书籍墓碑还抑制"本机已移出此书"后的进度回灌。
 *   global 整文件通道：<远程目录>/global/HowRead-desktop-<文件>.json 信封 {at, data}，基线时间戳存
 *   data/sync-base；本地新→推、远端新→采、双新本地胜；未知名文件对安卓只透传不读=共存安全。
 */
object AndroidSync {

    private const val REMOTE_BOOKS = "books"

    /** #26②：字段映射采纳本地配置后通知应用刷新缓存（LibPrefs 等），应用侧在同步入口设置。 */
    var onPrefsImported: () -> Unit = {}

    /**
     * #26②（v0.53.0）安卓 app-CSS.json / app-State.json 阅读字段 ⇄ 桌面 reader.json / lib.properties 字段映射。
     * 三方合并：快照 dataDir/sync-base/android-fields.json 记录“上次同步”的值——
     * 无快照（首次）=以安卓为基准导入；本地改过=回写安卓（整文件仅替换映射键，其它字段原样保留）；
     * 仅远端改=采纳到本地。单位：字号 sp↔%（×5）、行高 0.1em↔%（×10）、对齐枚举↔字符串、
     * 缩进/段距同单位直映、边距数值级直映（安卓 0.2em 档≈桌面 pt 档，视觉近似）、双页布尔直映。
     */
    private fun mergeAndroidFields(globalUrl: String, hrGet: (String) -> String?, hrPut: (String, String) -> Boolean) {
        val css = runCatching { org.json.JSONObject(hrGet("$globalUrl/app-CSS.json") ?: return) }.getOrNull() ?: return
        val st = runCatching { org.json.JSONObject(hrGet("$globalUrl/app-State.json") ?: "{}") }.getOrDefault(org.json.JSONObject())
        val dataDir = howread.core.AppPaths.dataDir
        val baseDir = File(dataDir, "sync-base")
        baseDir.mkdirs()
        val snapF = File(baseDir, "android-fields.json")
        val snap = runCatching { org.json.JSONObject(snapF.readText(Charsets.UTF_8)) }.getOrElse { org.json.JSONObject() }
        val readerF = File(dataDir, "reader.json")
        val rd = runCatching { org.json.JSONObject(readerF.readText(Charsets.UTF_8)) }.getOrElse { org.json.JSONObject() }
        val libF = File(dataDir, "lib.properties")
        val props = java.util.Properties()
        runCatching { libF.inputStream().use { props.load(it) } }
        var adopted = 0
        var pushed = 0
        var rdDirty = false
        var cssDirty = false
        var stDirty = false
        var libDirty = false

        fun ifield(localKey: String, getLocal: () -> Int, setLocal: (Int) -> Unit,
                   src: org.json.JSONObject, srvKey: String,
                   toLocal: (Int) -> Int, toSrv: (Int) -> Int, markPush: () -> Unit) {
            if (!src.has(srvKey)) return
            val srvRaw = runCatching { src.getInt(srvKey) }.getOrNull() ?: return
            val srv = toLocal(srvRaw)
            val local = getLocal()
            if (!snap.has(localKey)) {
                if (srv != local) { setLocal(srv); rdDirty = true; adopted++ }
                snap.put(localKey, srv)
                return
            }
            val prev = snap.optInt(localKey)
            if (local != prev) {
                if (srvRaw != toSrv(local)) { src.put(srvKey, toSrv(local)); markPush(); pushed++ }
                snap.put(localKey, local)
            } else if (srv != local) {
                setLocal(srv); rdDirty = true; adopted++
                snap.put(localKey, srv)
            }
        }

        val A2S = mapOf(0 to "justify", 1 to "left", 2 to "right", 3 to "center")
        if (css.has("textAlign")) {
            val srv = A2S[runCatching { css.getInt("textAlign") }.getOrDefault(-1)]
            if (srv != null) {
                val local = rd.optString("align", "justify")
                if (!snap.has("align")) {
                    if (srv != local) { rd.put("align", srv); rdDirty = true; adopted++ }
                    snap.put("align", srv)
                } else {
                    val prev = snap.optString("align")
                    if (local != prev) {
                        if (local != srv) {
                            css.put("textAlign", A2S.entries.firstOrNull { it.value == local }?.key ?: 0)
                            cssDirty = true; pushed++
                        }
                        snap.put("align", local)
                    } else if (srv != local) {
                        rd.put("align", srv); rdDirty = true; adopted++
                        snap.put("align", srv)
                    }
                }
            }
        }
        ifield("fontScale", { rd.optInt("fontScale", 0) }, { rd.put("fontScale", it) }, css, "fontSizeSp",
            { sp -> if (sp <= 0) 100 else (sp * 5).coerceIn(60, 220) },
            { p -> if (p <= 0) 20 else (p / 5).coerceIn(6, 60) }) { cssDirty = true }
        ifield("lineHeight", { rd.optInt("lineHeight", 180) }, { rd.put("lineHeight", it) }, css, "lineHeight12",
            { v -> (v * 10).coerceIn(100, 250) }, { p -> (p / 10).coerceIn(10, 30) }) { cssDirty = true }
        ifield("marginTop", { rd.optInt("marginTop", 4) }, { rd.put("marginTop", it) }, css, "marginTop",
            { v -> v.coerceIn(0, 60) }, { v -> v.coerceIn(0, 60) }) { cssDirty = true }
        ifield("marginRight", { rd.optInt("marginRight", 10) }, { rd.put("marginRight", it) }, css, "marginRight",
            { v -> v.coerceIn(0, 60) }, { v -> v.coerceIn(0, 60) }) { cssDirty = true }
        ifield("marginBottom", { rd.optInt("marginBottom", 4) }, { rd.put("marginBottom", it) }, css, "marginBottom",
            { v -> v.coerceIn(0, 60) }, { v -> v.coerceIn(0, 60) }) { cssDirty = true }
        ifield("marginLeft", { rd.optInt("marginLeft", 10) }, { rd.put("marginLeft", it) }, css, "marginLeft",
            { v -> v.coerceIn(0, 60) }, { v -> v.coerceIn(0, 60) }) { cssDirty = true }
        ifield("textIndent", { rd.optInt("textIndent", 20) }, { rd.put("textIndent", it) }, css, "textIndent",
            { v -> v.coerceIn(0, 100) }, { v -> v.coerceIn(0, 100) }) { cssDirty = true }
        ifield("paraSpacing", { rd.optInt("paraSpacing", 1) }, { rd.put("paraSpacing", it) }, css, "paragraphHeight",
            { v -> v.coerceIn(0, 10) }, { v -> v.coerceIn(0, 10) }) { cssDirty = true }
        if (st.has("alwaysTwoPages")) {
            val srv = st.optBoolean("alwaysTwoPages")
            val local = props.getProperty("twoPage", "false").toBoolean()
            if (!snap.has("twoPage")) {
                if (srv != local) { props.setProperty("twoPage", srv.toString()); libDirty = true; adopted++ }
                snap.put("twoPage", if (srv) 1 else 0)
            } else {
                val prev = snap.optInt("twoPage") == 1
                if (local != prev) {
                    if (local != srv) { st.put("alwaysTwoPages", local); stDirty = true; pushed++ }
                    snap.put("twoPage", if (local) 1 else 0)
                } else if (srv != local) {
                    props.setProperty("twoPage", srv.toString()); libDirty = true; adopted++
                    snap.put("twoPage", if (srv) 1 else 0)
                }
            }
        }
        if (rdDirty) {
            runCatching { readerF.writeText(rd.toString(), Charsets.UTF_8) }
            howread.core.reader.ReaderPrefsStore.invalidate()
            runCatching { onPrefsImported() }
        }
        if (libDirty) {
            runCatching { libF.outputStream().use { props.store(it, "HowRead desktop prefs") } }
            runCatching { onPrefsImported() }
        }
        if (cssDirty && !hrPut("$globalUrl/app-CSS.json", css.toString())) TestLog.evt("field_push_css_fail", "")
        if (stDirty && !hrPut("$globalUrl/app-State.json", st.toString())) TestLog.evt("field_push_state_fail", "")
        runCatching { snapF.writeText(snap.toString(), Charsets.UTF_8) }
        TestLog.evt("field_merge", "adopt=$adopted push=$pushed")
    }

    class Result(
        val ok: Boolean,
        val error: String = "",
        val progressDown: Int = 0,
        val progressUp: Int = 0,
        val bookmarksDown: Int = 0,
        val booksSynced: Int = 0,
        val durationMs: Long = 0,
        val globalUp: Int = 0,
        val globalDown: Int = 0,
    )

    // ---- 书身份：内容 MD5（进程内缓存 path+len+mtime，安卓 FileHash 同款口径） ----
    private val md5Cache = HashMap<String, Array<String>>()

    fun md5(f: File): String {
        if (!f.isFile) return ""
        val key = f.absolutePath
        synchronized(md5Cache) {
            val c = md5Cache[key]
            if (c != null && c[0].toLong() == f.lastModified() && c[1].toLong() == f.length()) return c[2]
        }
        val h = runCatching {
            val md = MessageDigest.getInstance("MD5")
            f.inputStream().use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrDefault("")
        if (h.isNotEmpty()) synchronized(md5Cache) { md5Cache[key] = arrayOf("" + f.lastModified(), "" + f.length(), h) }
        return h
    }

    /** 与安卓互通的同步入口。onStep 汇报进度文本。 */
    fun sync(src: RemoteSource, policy: String, onStep: (String) -> Unit = {}): Result {
        val t0 = System.currentTimeMillis()
        val sardine = com.github.sardine.SardineFactory.begin(src.user, src.password)
        var pDown = 0
        var bDown = 0
        var pUp = 0
        var synced = 0
        try {
            val rootBase = if (src.host.startsWith("http")) src.host.trimEnd('/') else "http://${src.host}:${src.port}"
            val remoteDir = src.path.trim('/').ifEmpty { "HowRead" }
            val booksUrl = "$rootBase/$remoteDir/$REMOTE_BOOKS"
            runCatching {
                if (!sardine.exists("$rootBase/$remoteDir")) sardine.createDirectory("$rootBase/$remoteDir")
                if (!sardine.exists(booksUrl)) sardine.createDirectory(booksUrl)
            }

            // ---- 本地数据收集（桌面 Db → 协议 json） ----
            val candidates = HashMap<String, File>()          // 书名 → 本地文件
            val localP = org.json.JSONObject()                // name → progress entry（含远端保留字段）
            val localB = org.json.JSONObject()                // key → bookmark 对象
            val nameByPath = HashMap<String, String>()        // path → 书名
            for (b in Db.allBooks()) {
                if (b.path.startsWith("remote://")) continue
                val f = File(b.path)
                if (!f.isFile) continue
                candidates[f.name] = f
                nameByPath[b.path] = f.name
            }
            for (pr in Db.allProgress()) {
                val name = nameByPath[pr.path] ?: continue
                var entry: org.json.JSONObject
                val prev = localP.optJSONObject(name)
                entry = if (prev != null) prev else org.json.JSONObject()
                val pc = pr.pageCount.coerceAtLeast(1)
                entry.put("p", (pr.page + 1f) / pc)
                entry.put("pg", pr.page)
                entry.put("pgc", pr.pageCount)
                entry.put("t", pr.updatedAt)
                localP.put(name, entry)
            }
            for ((bm, title) in Db.allBookmarks()) {
                val key = bm.createdAt.toString()
                val o = org.json.JSONObject()
                val name = nameByPath[bm.path] ?: title
                o.put("path", bm.path)
                o.put("text", bm.label)
                o.put("note", bm.note)
                val pc = 1
                o.put("p", (bm.page + 1f) / pc)
                o.put("pg", bm.page)
                o.put("t", bm.createdAt)
                if (localB.has(key)) continue   // 同毫秒冲突：保留先到
                localB.put(key, o)
                // 书名键修正（bm.path 可能是 remote/绝对）
                nameByPath[bm.path] = File(title).name.ifEmpty { name }
            }

            // ---- 远端逐书 info 列表 ----
            val remoteBooks = LinkedHashMap<String, org.json.JSONObject>()
            val listed = runCatching {
                for (r in sardine.list(booksUrl)) {
                    val nm = r.name
                    if (r.isDirectory || !nm.endsWith(".json")) continue
                    val hash = nm.removeSuffix(".json")
                    val txt = runCatching { sardine.get("$booksUrl/$nm").use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
                    val o = if (txt != null) runCatching { org.json.JSONObject(txt) }.getOrNull() else null
                    if (o != null) remoteBooks[hash] = o
                }
                true
            }.getOrDefault(false)
            if (!listed) return Result(false, "网络：无法读取远端 books 目录", durationMs = System.currentTimeMillis() - t0)
            onStep("远端 ${remoteBooks.size} 本")

            // ---- 应用远端（逐书独立，坏一条不影响其余） ----
            for ((rHash, info) in remoteBooks) {
                try {
                    val name = info.optString("name")
                    if (name.isEmpty()) continue
                    val localFile = candidates[name]
                    val matched = localFile != null && rHash == md5(localFile)
                    val remoteDk = info.optJSONObject("dk")

                    // 进度：仅 hash 确认（或本机没有此书）可改本地阅读位置
                    // v0.51.0 墓碑：本机已整体移出此书且晚于远端进度时间 → 不再回灌复活（安卓 DeletedBooks 同语义）
                    val delT = Db.tombstoneBookT(rHash)
                    val rp = info.optJSONObject("progress")
                    if (rp != null && (matched || localFile == null) &&
                        !(localFile == null && delT != null && delT >= rp.optLong("t", 0))
                    ) {
                        val l = localP.optJSONObject(name)
                        val remoteWins = when (policy) {
                            "local" -> l == null
                            "server" -> true
                            else -> l == null || rp.optLong("t", 0) > l.optLong("t", 0)   // newer（farther 并入 newer：桌面无页差诉求）
                        }
                        if (remoteWins) {
                            localP.put(name, rp)
                            val lf = localFile ?: candidates[name]
                            if (lf != null) {
                                val pg = rp.optInt("pg", -1)
                                val pgc = rp.optInt("pgc", -1)
                                if (pg >= 0) {
                                    Db.saveProgressRaw(lf.absolutePath, pg, if (pgc > 0) pgc else 1, 0, rp.optLong("t", 0))
                                    pDown++
                                }
                            }
                        }
                    }

                    // 书签：按 key 的 union；远端 dk 新于条目时抑制
                    val rbs = info.optJSONObject("bookmarks")
                    if (rbs != null) {
                        val localTk = Db.tombstoneBmMap(rHash)   // v0.51.0：本地删除墓碑同样抑制下行复活
                        for (key in rbs.keys()) {
                            if (localB.has(key)) continue
                            val rTomb = localTk[key]
                            if (rTomb != null && rTomb >= (rbs.optJSONObject(key)?.optLong("t", 0) ?: 0)) continue
                            if (remoteDk != null && remoteDk.has(key) &&
                                remoteDk.optLong(key, 0) >= rbs.optJSONObject(key)?.optLong("t", 0) ?: 0
                            ) continue
                            val bm = rbs.optJSONObject(key) ?: continue
                            val createdAt = key.toLongOrNull() ?: continue
                            val text = bm.optString("text")
                            val bmPg = bm.optInt("pg", -1)
                            var localPath = bm.optString("path")
                            // 路径解析：安卓相对路径或异机绝对路径 → 按书名匹配本地库文件
                            val byName = candidates[File(localPath).name] ?: candidates[name]
                            if (byName != null) localPath = byName.absolutePath
                            if (localPath.isEmpty()) continue
                            runCatching {
                                Db.addBookmarkRaw(localPath, bmPg, text, bm.optString("note", ""), createdAt)
                                bDown++
                            }
                            localB.put(key, bm)
                        }
                    }

                    // 发布合并后的 info（仅 hash 确认的书维护其服务器副本）
                    if (matched && localFile != null) {
                        val pub = org.json.JSONObject()
                        pub.put("name", name)
                        pub.put("hash", rHash)
                        pub.put("t", maxOf(info.optLong("t", 0), localP.optJSONObject(name)?.optLong("t", 0) ?: 0))
                        localP.optJSONObject(name)?.let { pub.put("progress", it) }
                        val subset = org.json.JSONObject()
                        for (key in localB.keys()) {
                            val bm = localB.optJSONObject(key) ?: continue
                            if (nameByPath[bm.optString("path")] == name) subset.put(key, bm)
                        }
                        // 检视修复：桌面该书本地书签为空时保留服务器书签——桌面删除不写墓碑，
                        // 空 subset 会把其它设备的书签清掉（等它们下一轮再 union 回来，多一次振荡）
                        val rbm = info.optJSONObject("bookmarks")
                        if (subset.length() > 0 || rbm == null) pub.put("bookmarks", subset) else pub.put("bookmarks", rbm)
                        // v0.51.0 墓碑传播：本地删除的书签/整本书墓碑合入 dk 发布（安卓 dk 同款 key→删除毫秒）
                        val dkM = org.json.JSONObject()
                        info.optJSONObject("dk")?.let { old -> for (k in old.keys()) dkM.put(k, old.get(k)) }
                        for ((k, t) in Db.tombstoneBmMap(rHash)) dkM.put(k, t)
                        Db.tombstoneBookT(rHash)?.let { dkM.put("bookDelT", it) }
                        if (dkM.length() > 0) pub.put("dk", dkM)
                        sardine.put("$booksUrl/$rHash.json", pub.toString().toByteArray(Charsets.UTF_8), "application/json")
                        synced++
                        pUp++
                    }
                } catch (t: Throwable) {
                    TestLog.evt("android_sync_book_fail", (t.message ?: t.toString()).take(120))
                }
            }

            // ---- 上传本机有而远端没有的书 ----
            for ((name, f) in candidates) {
                val h = md5(f)
                if (h.isEmpty() || remoteBooks.containsKey(h)) continue
                val entry = localP.optJSONObject(name)
                val hasBm = hasBookmarkFor(localB, name)
                if (entry == null && !hasBm) continue   // 从未打开过的书不上传（安卓同款）
                val pub = org.json.JSONObject()
                pub.put("name", name)
                pub.put("hash", h)
                pub.put("t", entry?.optLong("t", 0) ?: 0)
                entry?.let { pub.put("progress", it) }
                val subset = org.json.JSONObject()
                for (key in localB.keys()) {
                    val bm = localB.optJSONObject(key) ?: continue
                    if (nameByPath[bm.optString("path")] == name) subset.put(key, bm)
                }
                pub.put("bookmarks", subset)
                runCatching {
                    sardine.put("$booksUrl/$h.json", pub.toString().toByteArray(Charsets.UTF_8), "application/json")
                    synced++
                }.onFailure { System.err.println("AS-UPFAIL " + name + " " + (it.message ?: it.toString()).take(140)) }
            }
            // ---- global/ 桌面配置整文件通道（v0.51.0，适配方案 #26①）----
            // 独立命名 HowRead-desktop-<文件>.json：安卓只读自己 global 里的 9 个文件、忽略未知名=共存安全。
            // ai.json 不走此通道（API Key 本机 DPAPI 绑定，各设备各持，见 SyncEngine 通道）。
            var gUp = 0
            var gDown = 0
            runCatching {
                val globalUrl = "$rootBase/$remoteDir/global"
                if (!sardine.exists(globalUrl)) sardine.createDirectory(globalUrl)
                val baseDir = File(howread.core.AppPaths.dataDir, "sync-base")
                baseDir.mkdirs()
                // 信封读写走独立短连接（Basic 头）：sardine 长连接复用曾出现"响应落后一拍"的陈旧读，
                // 整文件配置通道对新鲜度敏感，故不走共享连接池。
                val hrAuth = "Basic " + java.util.Base64.getEncoder()
                    .encodeToString("${src.user}:${src.password}".toByteArray(Charsets.UTF_8))
                fun hrGet(u: String): String? = runCatching {
                    val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
                    c.requestMethod = "GET"
                    c.setRequestProperty("Authorization", hrAuth)
                    c.connectTimeout = 15000
                    c.readTimeout = 30000
                    c.instanceFollowRedirects = true
                    c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
                fun hrPut(u: String, body: String): Boolean = runCatching {
                    val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
                    c.requestMethod = "PUT"
                    c.doOutput = true
                    c.setRequestProperty("Authorization", hrAuth)
                    c.setRequestProperty("Content-Type", "application/json")
                    c.connectTimeout = 15000
                    c.readTimeout = 30000
                    c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val code = c.responseCode
                    code in 200..299
                }.getOrDefault(false)
                for (nm in listOf("reader.json", "ui.json", "lib.properties", "sync.json")) {
                    try {
                        val lf = File(howread.core.AppPaths.dataDir, nm)
                        val url = "$globalUrl/HowRead-desktop-${nm.substringBeforeLast('.')}.json"
                        val baseF = File(baseDir, nm + ".at")
                        val baseAt = runCatching { baseF.readText().trim().toLongOrNull() ?: 0L }.getOrDefault(0L)
                        val remote = runCatching {
                            val txt = hrGet(url) ?: return@runCatching null
                            val env = org.json.JSONObject(txt)
                            env.optLong("at", 0L) to env.optString("data", "")
                        }.getOrNull()
                        val rAt = remote?.first ?: 0L
                        val localAt = if (lf.isFile) lf.lastModified() else 0L
                        TestLog.evt("global_file", "$nm l=$localAt r=$rAt b=$baseAt has=${remote != null}")
                        if (localAt > baseAt && localAt > 0) {   // 本地更新（双新=本地胜，安卓 newer 同口径）
                            val env = org.json.JSONObject().put("at", localAt).put("data", lf.readText(Charsets.UTF_8))
                            if (!hrPut(url, env.toString())) {
                                TestLog.evt("global_push_fail", nm)
                            } else {
                                baseF.writeText(localAt.toString())
                                gUp++
                            }
                        } else if (remote != null && rAt > baseAt) {
                            lf.writeText(remote.second, Charsets.UTF_8)
                            runCatching { lf.setLastModified(rAt) }   // 拉取=把本地时间钉回基线，避免下轮乒乓回推
                            baseF.writeText(rAt.toString())
                            gDown++
                            if (nm == "reader.json") runCatching { howread.core.reader.ReaderPrefsStore.invalidate() }
                        }
                    } catch (t: Throwable) {
                        TestLog.evt("global_file_fail", nm + " " + (t.message ?: t.toString()).take(80))
                    }
                }
                // ---- #26② 安卓阅读配置字段映射（v0.53.0，适配方案 #26②）----
                runCatching {
                    mergeAndroidFields(globalUrl, { u -> hrGet(u) }, { u, b -> hrPut(u, b) })
                }.onFailure { TestLog.evt("field_map_fail", (it.message ?: it.toString()).take(120)) }
            }.onFailure { TestLog.evt("global_sync_fail", (it.message ?: it.toString()).take(120)) }
            TestLog.evt("android_sync_done", "pDown=$pDown bDown=$bDown synced=$synced gUp=$gUp gDown=$gDown")
            return Result(true, progressDown = pDown, progressUp = pUp, bookmarksDown = bDown,
                booksSynced = synced, durationMs = System.currentTimeMillis() - t0,
                globalUp = gUp, globalDown = gDown)
        } catch (t: Throwable) {
            TestLog.evt("android_sync_fail", (t.message ?: t.toString()).take(160))
            return Result(false, t.message ?: t.toString(), durationMs = System.currentTimeMillis() - t0)
        } finally {
            runCatching { (sardine as? java.io.Closeable)?.close() }
        }
    }

    private fun hasBookmarkFor(localB: org.json.JSONObject, name: String): Boolean {
        for (key in localB.keys()) {
            val bm = localB.optJSONObject(key) ?: continue
            val bp = bm.optString("path")
            if (File(bp).name == name || bp.endsWith("/$name") || bp.endsWith("\\$name")) return true
        }
        return false
    }
}
