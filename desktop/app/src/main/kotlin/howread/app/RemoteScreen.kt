package howread.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import howread.core.AppPaths
import howread.core.TestLog
import howread.core.data.Db
import howread.core.model.RemoteSource
import howread.core.remote.RemoteClient
import howread.core.remote.RemoteClients
import howread.core.remote.RemoteEntry
import howread.core.remote.downloadTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.File

private val DOWNLOADS_DIR = File(AppPaths.dataDir, "downloads")

@Composable
fun RemoteScreen(appState: AppState, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()

    var sources by remember { mutableStateOf(Db.listRemoteSources()) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var client by remember { mutableStateOf<RemoteClient?>(null) }
    // 检视修复：离开网上书库页时关闭已连接的 client（SFTP 持会话/SMB 持上下文，泄漏到进程退出）
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { runCatching { client?.close() } }
    }
    var curRel by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var status by remember { mutableStateOf("选择或添加服务器") }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(appState.remoteEditId?.let { id -> Db.listRemoteSources().find { it.id == id } }) }
    var scanningNow by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var searchQ by remember { mutableStateOf("") }
    var inSearch by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    val coverCache = remember { mutableStateMapOf<String, ImageBitmap?>() }

    fun refreshSources() { sources = Db.listRemoteSources() }

    fun disconnect() {
        runCatching { client?.close() }
        client = null; entries = emptyList(); curRel = ""
        inSearch = false; searching = false
    }

    fun opdsSearch() {
        val c = client ?: return
        if (searchQ.isBlank() || busy || searching) return
        searching = true
        status = L10n.s("搜索中…", "Searching…")
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { c.search(searchQ) }
                entries = r ?: emptyList()
                inSearch = r != null
                status = if (r == null) L10n.s("该目录不支持搜索", "Catalog search not supported")
                else L10n.s("搜索「" + searchQ + "」：" + entries.size + " 项", "Search \"" + searchQ + "\": " + entries.size + " hits")
                TestLog.evt("opds_search", "q=" + searchQ + " n=" + (if (r == null) -1 else entries.size))
            } catch (t: Throwable) {
                status = "搜索失败：" + t
            } finally {
                searching = false
            }
        }
    }

    fun scanSource(s: RemoteSource) {
        if (scanningNow.isNotEmpty()) return
        scanningNow = s.name
        Thread {
            val n = runCatching { howread.core.library.RemoteScanner.registerSource(s) }.getOrDefault(-1)
            javax.swing.SwingUtilities.invokeLater {
                appState.reloadLibrary()
                scanningNow = ""
                status = if (n >= 0) L10n.s("已登记 $n 本（点开自动缓存，不预下载）", "Registered $n books (cached on open)") else L10n.s("扫描失败", "Scan failed")
            }
        }.apply { isDaemon = true }.start()
    }

    fun connect(s: RemoteSource) {
        disconnect()
        busy = true
        status = "连接 ${s.name} …"
        scope.launch {
            try {
                val c = withContext(Dispatchers.IO) { RemoteClients.create(s).also { it.test() } }
                client = c
                selectedId = s.id
                curRel = ""
                entries = withContext(Dispatchers.IO) { c.list("") }
                status = "${s.name} 已连接"
                TestLog.evt("remote_list", "src=${s.name} n=${entries.size}")
            } catch (t: Throwable) {
                status = "连接失败：$t"
                TestLog.evt("remote_connect_fail", "src=${s.name} err=$t")
            } finally {
                busy = false
            }
        }
    }

    fun enter(rel: String) {
        val c = client ?: return
        busy = true
        scope.launch {
            try {
                entries = withContext(Dispatchers.IO) { c.list(rel) }
                curRel = rel
            } catch (t: Throwable) {
                status = "列目录失败：$t"
            } finally {
                busy = false
            }
        }
    }

    fun download(e: RemoteEntry, open: Boolean) {
        val c = client ?: return
        busy = true
        status = "下载 ${e.name} …"
        scope.launch {
            try {
                val dest = File(DOWNLOADS_DIR, e.name)
                var hit = false
                withContext(Dispatchers.IO) {
                    DOWNLOADS_DIR.mkdirs()
                    Db.addScanDir(DOWNLOADS_DIR.absolutePath)
                    // 缓存命中：同名同大小免下载直接开
                    if (dest.isFile && e.size > 0 && dest.length() == e.size) {
                        hit = true
                    } else {
                        c.downloadTo(e.relPath, dest)
                        Db.upsertFileMeta(dest.absolutePath, dest.nameWithoutExtension, dest.length(), dest.lastModified())
                    }
                }
                appState.reloadLibrary()
                status = if (hit) "已在缓存，直接打开：${e.name}" else "已下载：${e.name}"
                TestLog.evt(if (hit) "remote_cache_hit" else "remote_download_done", "name=${e.name} size=${dest.length()}")
                if (open) appState.open(dest.absolutePath)
            } catch (t: Throwable) {
                status = "下载失败：$t"
                TestLog.evt("remote_download_fail", "name=${e.name} err=$t")
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(UiTheme.p.bg)) {
        // 顶栏
        Row(Modifier.fillMaxWidth().height(40.dp).background(UiTheme.p.bar), verticalAlignment = Alignment.CenterVertically) {
            Text(L10n.s("远程书库", "Network library"), color = UiTheme.p.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 12.dp))
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { adding = true }, enabled = !busy) { Text(L10n.s("添加服务器", "Add server")) }
            TextButton(onClick = onClose) { Text(L10n.s("关闭", "Close")) }
        }

        Row(Modifier.fillMaxSize()) {
            // 左：服务器列表
            Column(
                Modifier.width(250.dp).fillMaxHeight().background(UiTheme.p.panel).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (sources.isEmpty()) Text(L10n.s("尚无服务器", "No servers yet"), color = UiTheme.p.textFaint, fontSize = 12.sp)
                for (s in sources) {
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (selectedId == s.id) UiTheme.p.rowActive else Color.Transparent)
                            .clickable(enabled = !busy) { connect(s) }
                            .padding(vertical = 6.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, color = UiTheme.p.textPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${s.protocol.lowercase()} · ${s.host}", color = UiTheme.p.textFaint, fontSize = 11.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (scanningNow == s.name) {
                            Text(L10n.s("扫描中…", "Scanning…"), color = UiTheme.p.textFaint, fontSize = 10.sp)
                        }
                        TextButton(onClick = { scanSource(s) }, enabled = scanningNow.isEmpty()) {
                            Text(L10n.s("扫描", "Scan"), fontSize = 11.sp, color = UiTheme.p.accent)
                        }
                        TextButton(onClick = { editing = s }) { Text(L10n.s("编辑", "Edit"), fontSize = 11.sp, color = UiTheme.p.textTertiary) }
                        TextButton(onClick = {
                            Db.deleteRemoteSource(s.id)
                            if (selectedId == s.id) { disconnect(); selectedId = null }
                            refreshSources()
                            TestLog.evt("remote_src_del", s.name)
                        }) { Text(L10n.s("删", "Del"), fontSize = 11.sp, color = UiTheme.p.danger) }
                    }
                }
            }

            // 右：浏览/编辑
            if (adding) {
                ServerForm(onCancel = { adding = false }) { s ->
                    val id = Db.addRemoteSource(s)
                    refreshSources()
                    adding = false
                    connect(s.copy(id = id))
                    TestLog.evt("remote_src_add", "${s.protocol} ${s.host}")
                }
            } else if (editing != null) {
                ServerForm(initial = editing, title = L10n.s("编辑服务器", "Edit server"), onCancel = { editing = null }) { s ->
                    Db.updateRemoteSource(s)
                    refreshSources()
                    editing = null
                    TestLog.evt("remote_src_edit", "${s.protocol} ${s.host}")
                }
            } else {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    // 面包屑
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { enter("") }, enabled = client != null && !busy) { Text(L10n.s("根", "Root")) }
                        if (curRel.isNotBlank()) {
                            val parts = curRel.trim('/').split('/')
                            var acc = ""
                            for (part in parts) {
                                acc = if (acc.isEmpty()) part else "$acc/$part"
                                val target = acc
                                Text("/"); TextButton(onClick = { enter(target) }, enabled = !busy) { Text(part) }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (client != null) {
                            TextButton(onClick = { disconnect(); status = "已断开" }) { Text(L10n.s("断开", "Disconnect"), color = UiTheme.p.danger) }
                        }
                    }
                    if (client is howread.core.remote.OpdsClient) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                            OutlinedTextField(
                                value = searchQ, onValueChange = { searchQ = it },
                                label = { Text(L10n.s("目录内搜索（书名/关键词）", "Search catalog")) },
                                singleLine = true, modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { opdsSearch() }, enabled = !busy && !searching && searchQ.isNotBlank()) {
                                Text(if (searching) L10n.s("搜索中…", "Searching…") else L10n.s("搜索", "Search"))
                            }
                            if (inSearch) {
                                TextButton(onClick = { inSearch = false; enter("") }, enabled = !busy) {
                                    Text(L10n.s("返回浏览", "Back"), color = UiTheme.p.danger)
                                }
                            }
                        }
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        items(entries, key = { it.relPath + "|" + it.name }) { e ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val bmp = if (e.coverUrl.isNotBlank()) coverCache[e.coverUrl] else null
                                if (bmp != null) {
                                    Image(bmp, contentDescription = null, modifier = Modifier.size(36.dp).padding(end = 2.dp))
                                } else {
                                    Text(if (e.isDir) "📁" else "📄", fontSize = 14.sp)
                                }
                                Column(Modifier.weight(1f).padding(start = 6.dp)) {
                                    Text(
                                        e.name, color = if (e.isDir) UiTheme.p.accent else UiTheme.p.textSecondary,
                                        fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.fillMaxWidth()
                                            .clickable(enabled = !busy) { if (e.isDir) enter(e.relPath) },
                                    )
                                    if (e.summary.isNotBlank()) {
                                        Text(e.summary, color = UiTheme.p.textFaintest, fontSize = 10.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                                    }
                                }
                                if (e.coverUrl.isNotBlank() && !coverCache.containsKey(e.coverUrl)) {
                                    LaunchedEffect(e.coverUrl) {
                                        val b = withContext(Dispatchers.IO) { client?.fetchBytes(e.coverUrl) }
                                        coverCache[e.coverUrl] = b?.let {
                                            runCatching { javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(it)) }.getOrNull()
                                        }?.toComposeImageBitmap()
                                    }
                                }
                                if (!e.isDir) {
                                    val cached = e.size > 0 && File(DOWNLOADS_DIR, e.name).let { it.isFile && it.length() == e.size }
                                    if (cached) {
                                        Text(L10n.s("已缓存", "Cached"), color = UiTheme.p.green, fontSize = 10.sp, modifier = Modifier.padding(end = 6.dp))
                                    }
                                    Text("${e.size / 1024} KB", color = UiTheme.p.textFaintest, fontSize = 11.sp)
                                    TextButton(onClick = { download(e, open = true) }, enabled = !busy) {
                                        Text(if (cached) L10n.s("打开", "Open") else L10n.s("下载并打开", "Download & open"), fontSize = 11.sp, color = UiTheme.p.accent)
                                    }
                                }
                            }
                        }
                    }
                    Text(status, color = UiTheme.p.textTertiary, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ServerForm(initial: RemoteSource? = null, title: String = L10n.s("添加服务器", "Add server"), onCancel: () -> Unit, onSave: (RemoteSource) -> Unit) {
    var protocol by remember { mutableStateOf(initial?.protocol ?: "WEBDAV") }
    var name by remember { mutableStateOf(initial?.name ?: "测试服务器") }
    var host by remember { mutableStateOf(initial?.host ?: "192.168.50.23") }
    var port by remember { mutableStateOf(initial?.port?.toString() ?: "8765") }
    var path by remember { mutableStateOf(initial?.path ?: "") }
    var user by remember { mutableStateOf(initial?.user ?: "howread") }
    var password by remember { mutableStateOf(initial?.password ?: "howread123") }
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var testMsg by remember { mutableStateOf("") }
    var browsing by remember { mutableStateOf(false) }

    fun build() = RemoteSource(initial?.id ?: 0, protocol, name.trim(), host.trim(),
        port.trim().toIntOrNull()?.coerceIn(1, 65535)
            ?: when (protocol) { "SMB" -> 445; "SFTP" -> 22; "OPDS" -> 80; else -> 8765 },
        path.trim().trim('/'), user.trim(), password)

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = UiTheme.p.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Row {
            for (p in listOf("WEBDAV", "SMB", "SFTP", "OPDS")) {
                TextButton(onClick = {
                    protocol = p
                    port = when (p) { "SMB" -> "445"; "SFTP" -> "22"; "OPDS" -> "80"; else -> "8765" }
                    if (p == "OPDS") path = ""
                }) {
                    Text(p, color = if (protocol == p) UiTheme.p.accent else UiTheme.p.textTertiary,
                        fontWeight = if (protocol == p) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        FormField(L10n.s("名称", "Name"), name) { name = it }
        FormField(if (protocol == "OPDS") L10n.s("目录地址(URL)", "Catalog URL") else L10n.s("主机", "Host"), host) { host = it }
        if (protocol == "OPDS") {
            Text(L10n.s("OPDS：地址填目录/书库的 OPDS 链接（端口/路径/账号可不填）。", "OPDS: fill the catalog URL (port/path/account optional)."),
                color = UiTheme.p.textFaintest, fontSize = 11.sp)
            Text(L10n.s("内置目录（点击填充）：", "Preset catalogs (click to fill):"), color = UiTheme.p.textFaintest, fontSize = 11.sp)
            Row {
                TextButton(onClick = { name = "Project Gutenberg"; host = "https://m.gutenberg.org/ebooks.opds/" }) {
                    Text("Project Gutenberg", fontSize = 11.sp)
                }
                TextButton(onClick = { name = "Standard Ebooks"; host = "https://standardebooks.org/feeds/opds" }) {
                    Text("Standard Ebooks", fontSize = 11.sp)
                }
            }
        }
        FormField(L10n.s("端口", "Port"), port) { port = it }
        // v0.47.0：浏览目录按钮移到路径/共享名框右侧（原在底部按钮行）
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = path, onValueChange = { path = it },
                label = { Text(L10n.s("路径/共享名", "Path / share")) },
                singleLine = true, modifier = Modifier.weight(1f),
            )
            if (protocol != "OPDS") {
                TextButton(onClick = { browsing = true }, enabled = !testing) {
                    Text(L10n.s("浏览目录", "Browse"), fontSize = 12.sp)
                }
            }
        }
        FormField(L10n.s("用户名", "User"), user) { user = it }
        FormField(L10n.s("密码", "Password"), password) { password = it }
        Row {
            Button(onClick = { onSave(build()) }, enabled = !testing) { Text(L10n.s("保存并连接", "Save & connect")) }
            TextButton(onClick = {
                testing = true; testMsg = L10n.s("测试中…", "Testing…")
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { RemoteClients.create(build()).use { c -> c.test() } }
                            .also { testMsg = it; TestLog.evt("remote_test_ok", "${protocol} ${host}") }
                    } catch (t: Throwable) {
                        testMsg = "失败：$t"; TestLog.evt("remote_test_fail", "${protocol} ${host} err=$t")
                    } finally { testing = false }
                }
            }, enabled = !testing) { Text(if (testing) L10n.s("测试中…", "Testing…") else L10n.s("测试连接", "Test")) }
            TextButton(onClick = onCancel) { Text(L10n.s("取消", "Cancel")) }
            Spacer(Modifier.weight(1f))
            Text(testMsg, color = UiTheme.p.textTertiary, fontSize = 12.sp)
        }
        if (browsing) {
            RemoteDirPickerDialog(src = build(), initialDir = path, onDismiss = { browsing = false }, onPicked = { picked ->
                path = picked
                browsing = false
                TestLog.evt("remote_dir_picked", protocol + " " + picked)
            })
        }
    }
}

/** 远程目录选择器（v0.38.0，对齐安卓 RemoteDirPicker）：用表单当前凭据浏览（未保存也可用），
 *  只列子目录（SMB 的 path 为空时首层即共享列表）；「选择此目录」回填相对路径，「上一级」返回，OPDS 不提供。 */
@Composable
fun RemoteDirPickerDialog(src: RemoteSource, initialDir: String, onDismiss: () -> Unit, onPicked: (String) -> Unit,
    files: Boolean = false, onOpenFile: (RemoteEntry) -> Unit = {}) {
    var cur by remember { mutableStateOf(initialDir.trim('/')) }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    var dirs by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(cur) {
        loading = true; err = ""
        // v0.49.0：浏览按「服务器根锚定」路径——建连客户端的起始目录 path 置空。
        // cur 本来就是根锚定路径（初值=起始目录），而客户端 list(cur) 把 cur 当
        // 「起始目录的相对子路径」二次拼 base：点「上一级」爬到比起始目录浅的位置时
        // （起始 Documents/05 其它 → 上一级 → Documents）拼出 .../05 其它/Documents，
        // 服务器回 PATH_NOT_FOUND（"The system cannot find the file specified."）。
        // SFTP 的空路径根由 SftpClient 映射为家目录，与起始目录语义一致。
        val res = withContext(Dispatchers.IO) {
            runCatching { RemoteClients.create(src.copy(path = "")).use { c -> c.list(cur).let { es -> if (files) es else es.filter { e -> e.isDir } } } }
        }
        loading = false
        res.onSuccess { dirs = it }.onFailure { err = it.message ?: it.toString(); dirs = emptyList() }
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.material3.Surface(
            Modifier.width(460.dp).padding(vertical = 24.dp),
            color = UiTheme.p.panel,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(L10n.s(if (files) "浏览服务器文件" else "选择起始目录", if (files) "Browse server" else "Pick start folder"),
                    color = UiTheme.p.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (cur.isEmpty()) L10n.s("（服务器根目录）", "(server root)") else "/" + cur,
                    color = UiTheme.p.textTertiary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (loading) Text(L10n.s("加载中…", "Loading…"), color = UiTheme.p.textFaint, fontSize = 12.sp)
                if (err.isNotEmpty()) {
                    Text(L10n.s("读取失败：", "Read failed: ") + err, color = UiTheme.p.danger, fontSize = 12.sp, maxLines = 3)
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(dirs, key = { it.relPath }) { e ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (files && !e.isDir) onOpenFile(e) else cur = e.relPath
                            }.padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(e.name, color = UiTheme.p.textPrimary, fontSize = 13.sp,
                                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (files && !e.isDir) {
                                Text(L10n.s("打开 ›", "Open ›"), color = UiTheme.p.accent, fontSize = 12.sp)
                            }
                        }
                    }
                    if (!loading && err.isEmpty() && dirs.isEmpty()) {
                        item { Text(L10n.s("（此处没有子目录）", "(no subfolders here)"), color = UiTheme.p.textFaint, fontSize = 12.sp) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!files) {
                        Button(onClick = { onPicked(cur); onDismiss() }) {
                            Text(L10n.s("选择此目录", "Pick this folder"), fontSize = 12.sp)
                        }
                    }
                    TextButton(onClick = { cur = cur.substringBeforeLast('/', "") }, enabled = cur.isNotEmpty()) {
                        Text(L10n.s("上一级", "Up"), fontSize = 12.sp)
                    }
                    TextButton(onClick = onDismiss) { Text(L10n.s("关闭", "Close"), fontSize = 12.sp) }
                }
            }
        }
    }
}

/** 我的文件→网络分组"添加"就地弹窗（v0.37.0：按分组预填协议/端口，复用 ServerForm，保存不自动连接）。 */
@Composable
fun RemoteAddDialog(protocol: String, onDismiss: () -> Unit, onSave: (RemoteSource) -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.material3.Surface(
            Modifier.width(540.dp).padding(vertical = 24.dp),
            color = UiTheme.p.panel,
        ) {
            ServerForm(
                initial = RemoteSource(0L, protocol, "测试服务器", "192.168.50.23", when (protocol) { "SMB" -> 445; "SFTP" -> 22; "OPDS" -> 80; else -> 8765 }, "", "howread", "howread123"),
                title = L10n.s("添加服务器", "Add server"),
                onCancel = onDismiss,
                onSave = onSave,
            )
        }
    }
}

/** 我的文件→网上书库"编辑"弹框（复用 ServerForm，保存不自动连接）。 */
@Composable
fun RemoteEditDialog(source: RemoteSource, onDismiss: () -> Unit, onSave: (RemoteSource) -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.material3.Surface(
            Modifier.width(540.dp).padding(vertical = 24.dp),
            color = UiTheme.p.panel,
        ) {
            ServerForm(initial = source, title = L10n.s("编辑服务器", "Edit server"), onCancel = onDismiss, onSave = onSave)
        }
    }
}

@Composable
private fun FormField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
}
