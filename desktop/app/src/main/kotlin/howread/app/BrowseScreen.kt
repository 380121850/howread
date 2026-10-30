package howread.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import howread.core.TestLog
import howread.core.ZipBooks
import howread.core.data.Db
import howread.core.library.RemoteScanner
import howread.core.library.Scanner
import howread.core.model.RemoteSource
import java.io.File

/** 我的文件：本地位置（可添加/编辑/删除/扫描）+ 网络服务器（OPDS/WebDAV/SMB/SFTP，编辑/删除/扫描书籍）。 */
@Composable
fun BrowseScreen(appState: AppState) {
    var cur by remember { mutableStateOf<File?>(null) }
    var zipCur by remember { mutableStateOf<File?>(null) }
    var scanRows by remember { mutableStateOf(Db.listScanDirRows()) }
    var servers by remember { mutableStateOf(Db.listRemoteSources()) }
    var addLocal by remember { mutableStateOf(false) }
    var editLocal by remember { mutableStateOf<String?>(null) }
    var editRemote by remember { mutableStateOf<RemoteSource?>(null) }
    var addRemoteProto by remember { mutableStateOf<String?>(null) }   // v0.37.0 就地添加远程服务器（协议分组头触发）
    var remoteBusy by remember { mutableStateOf("") }   // 正在扫描的源名（提示用）
    var browseFor by remember { mutableStateOf<RemoteSource?>(null) }   // v0.47.0 条目直达文件浏览

    fun refresh() {
        scanRows = Db.listScanDirRows()
        servers = Db.listRemoteSources()
    }

    if (cur == null) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text(L10n.s("我的文件", "My Files"), color = UiTheme.p.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(L10n.s("选择一个位置开始浏览；书籍文件点击即可打开阅读。", "Pick a location to browse; tap a book file to read."), color = UiTheme.p.textFaint, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))

            val roots = remember(scanRows) {
                val list = ArrayList<Pair<String, String>>()
                for (d in scanRows) list.add((L10n.s("书库 · ", "Lib · ") + File(d.path).name) to d.path)
                // v0.36.0：主目录/下载/文档/桌面等写死快捷入口取消，本地位置只列已添加的扫描目录
                list
            }

            RowButtonList(
                roots = roots,
                scanRows = scanRows,
                servers = servers,
                remoteBusy = remoteBusy,
                onOpen = { p -> cur = File(p); zipCur = null },
                onAddLocal = { addLocal = true },
                onEditLocal = { editLocal = it },
                onDelLocal = { p -> Db.removeScanDir(p); TestLog.evt("scan_dir_remove", p); appState.rescan(); refresh() },
                onScanLocal = { appState.rescan() },
                onRemoteAdd = { proto -> addRemoteProto = proto; TestLog.evt("browse_net_add", proto) },
                onRemoteOpen = { sv ->
                    TestLog.evt("browse_net_open", sv.protocol + " " + sv.name)
                    // v0.47.0：WebDAV/SMB/SFTP 条目直接进入该服务器的文件浏览；OPDS 仍走网上书库页
                    if (sv.protocol.equals("OPDS", ignoreCase = true)) appState.extra = ExtraContent.REMOTE
                    else browseFor = sv
                },
                onRemoteEdit = { editRemote = it },
                onRemoteDel = { s -> Db.deleteRemoteSource(s.id); TestLog.evt("remote_src_del", s.name); refresh() },
                onRemoteScan = { s ->
                    if (remoteBusy.isNotEmpty()) return@RowButtonList
                    remoteBusy = s.name
                    TestLog.evt("remote_scan_begin", "src=${s.name}")
                    Thread {
                        val n = runCatching { RemoteScanner.registerSource(s) }.getOrDefault(-1)
                        javax.swing.SwingUtilities.invokeLater {
                            appState.reloadLibrary()
                            remoteBusy = ""
                            TestLog.evt("remote_scan_ui", "src=${s.name} n=$n")
                        }
                    }.apply { isDaemon = true }.start()
                },
            )
        }
        if (addLocal) {
            DirPickerDialog(
                title = L10n.s("添加本地目录", "Add local folder"),
                initial = null,
                onPick = { p ->
                    addLocal = false
                    Db.addScanDir(p)
                    TestLog.evt("scan_dir_add", p)
                    appState.rescan()
                    refresh()
                },
                onDismiss = { addLocal = false },
            )
        }
        editLocal?.let { old ->
            DirPickerDialog(
                title = L10n.s("编辑本地目录", "Edit local folder"),
                initial = old,
                onPick = { p ->
                    editLocal = null
                    if (p != old) {
                        Db.removeScanDir(old)
                        Db.addScanDir(p)
                        TestLog.evt("scan_dir_edit", "$old -> $p")
                        appState.rescan()
                        refresh()
                    }
                },
                onDismiss = { editLocal = null },
            )
        }
        editRemote?.let { src ->
            RemoteEditDialog(source = src, onDismiss = { editRemote = null }) { updated ->
                editRemote = null
                Db.updateRemoteSource(updated)
                TestLog.evt("remote_src_edit", updated.name)
                refresh()
            }
        }
        addRemoteProto?.let { proto ->
            RemoteAddDialog(protocol = proto, onDismiss = { addRemoteProto = null }) { added ->
                Db.addRemoteSource(added)
                addRemoteProto = null
                TestLog.evt("remote_src_add", added.protocol + " " + added.host + " src=myfiles")
                refresh()
            }
        }
        browseFor?.let { sv ->
            RemoteDirPickerDialog(
                src = sv, initialDir = sv.path, files = true,
                onDismiss = { browseFor = null },
                onOpenFile = { e ->
                    TestLog.evt("browse_net_file_open", sv.protocol + " " + e.relPath)
                    val rp = "remote://" + sv.id + "/" + e.relPath
                    // 检视修复：直达打开也登记 file_meta——否则书库/最近阅读看不到这本书
                    runCatching { Db.upsertFileMeta(rp, e.name, e.size, System.currentTimeMillis()) }
                        .onSuccess { appState.reloadLibrary() }
                    appState.open(rp)
                    browseFor = null
                },
                onPicked = {},
            )
        }
    } else {
        BrowseDirPage(
            appState = appState,
            dir = cur!!,
            zipCur = zipCur,
            setZip = { zipCur = it },
            setDir = { cur = it },
            goUp = {
                val dir = cur!!
                val rootPaths = rootsPathSet(scanRows)
                cur = if (dir.absolutePath in rootPaths || dir.parentFile == null) null else dir.parentFile
                zipCur = null
            },
        )
    }
}

private fun rootsPathSet(scanRows: List<Db.ScanDirRow>): Set<String> =
    scanRows.map { File(it.path).absolutePath }.toSet()

/** 根页列表（网络四分组 + 本地位置），独立组件便于回调带参。 */
@Composable
private fun RowButtonList(
    roots: List<Pair<String, String>>,
    scanRows: List<Db.ScanDirRow>,
    servers: List<RemoteSource>,
    remoteBusy: String,
    onOpen: (String) -> Unit,
    onAddLocal: () -> Unit,
    onEditLocal: (String) -> Unit,
    onDelLocal: (String) -> Unit,
    onScanLocal: () -> Unit,
    onRemoteAdd: (String) -> Unit,
    onRemoteOpen: (RemoteSource) -> Unit,
    onRemoteEdit: (RemoteSource) -> Unit,
    onRemoteDel: (RemoteSource) -> Unit,
    onRemoteScan: (RemoteSource) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        // ---- 网络服务器四分组 ----
        for (proto in listOf("OPDS", "WEBDAV", "SMB", "SFTP")) {
            val list = servers.filter { it.protocol.equals(proto, ignoreCase = true) }
            item(key = "hdr_$proto") {
                Row(
                    Modifier.fillMaxWidth().clickable { onRemoteAdd(proto) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when (proto) {
                            "OPDS" -> L10n.s("网上书库（OPDS）", "OPDS Catalogs")
                            "WEBDAV" -> L10n.s("WebDAV (Pro)", "WebDAV (Pro)")
                            "SMB" -> L10n.s("SMB (Pro)", "SMB (Pro)")
                            else -> L10n.s("SFTP (Pro)", "SFTP (Pro)")
                        },
                        color = UiTheme.p.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(L10n.s("+ 添加", "+ Add"), color = UiTheme.p.accent, fontSize = 13.sp)
                }
            }
            for (sv in list) {
                item(key = "srv_${sv.id}") {
                    Row(
                        Modifier.fillMaxWidth().clickable { onRemoteOpen(sv) }.padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Filled.Public, contentDescription = null, tint = UiTheme.p.accentTeal, modifier = Modifier.size(20.dp))
                        Column(Modifier.weight(1f)) {
                            Text(sv.name, color = UiTheme.p.textPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${sv.protocol.lowercase()} · ${sv.host}", color = UiTheme.p.textFaintest, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (remoteBusy == sv.name) {
                            Text(L10n.s("扫描中…", "Scanning…"), color = UiTheme.p.textFaint, fontSize = 11.sp)
                        }
                        TextButton(onClick = { onRemoteScan(sv) }, enabled = remoteBusy.isEmpty()) {
                            Text(L10n.s("扫描书籍", "Scan"), fontSize = 11.sp, color = UiTheme.p.accent)
                        }
                        TextButton(onClick = { onRemoteEdit(sv) }) { Text(L10n.s("编辑", "Edit"), fontSize = 11.sp, color = UiTheme.p.textTertiary) }
                        TextButton(onClick = { onRemoteDel(sv) }) { Text(L10n.s("删除", "Del"), fontSize = 11.sp, color = UiTheme.p.danger) }
                    }
                }
            }
        }
        // ---- 本地位置 ----
        item(key = "hdr_local") {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(L10n.s("本地位置", "Local"), color = UiTheme.p.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onAddLocal) {
                    Text(L10n.s("+ 添加本地目录", "+ Add local folder"), color = UiTheme.p.accent, fontSize = 13.sp)
                }
            }
        }
        for (d in scanRows) {
            item(key = "dir_${d.path}") {
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(d.path) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Filled.Folder, contentDescription = null, tint = UiTheme.p.accentTeal, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text(L10n.s("书库 · ", "Lib · ") + File(d.path).name, color = UiTheme.p.textPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(d.path, color = UiTheme.p.textFaintest, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = onScanLocal) { Text(L10n.s("扫描书籍", "Scan"), fontSize = 11.sp, color = UiTheme.p.accent) }
                    TextButton(onClick = { onEditLocal(d.path) }) { Text(L10n.s("编辑", "Edit"), fontSize = 11.sp, color = UiTheme.p.textTertiary) }
                    TextButton(onClick = { onDelLocal(d.path) }) { Text(L10n.s("删除", "Del"), fontSize = 11.sp, color = UiTheme.p.danger) }
                }
            }
        }
        for ((label, path) in roots) {
            if (scanRows.any { it.path == path }) continue
            item(key = "q_$path") {
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(path) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Filled.Folder, contentDescription = null, tint = UiTheme.p.accentTeal, modifier = Modifier.size(20.dp))
                    Text(label, color = UiTheme.p.textPrimary, fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    Text(path, color = UiTheme.p.textFaintest, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** 目录浏览子页（原 BrowseScreen 目录态，逻辑不变）。 */
@Composable
private fun BrowseDirPage(
    appState: AppState,
    dir: File,
    zipCur: File?,
    setZip: (File?) -> Unit,
    setDir: (File) -> Unit,
    goUp: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        val listing = remember(dir) {
            dir.listFiles()
                ?.filter { !it.name.startsWith(".") && (it.isDirectory || Scanner.isBookFile(it.name) || it.name.lowercase().endsWith(".zip")) }
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?: emptyList()
        }
        val inScan = remember(dir) { Db.listScanDirs().contains(dir.absolutePath) }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TextButton(onClick = goUp) { Text(L10n.s("← 上一级", "← Up"), color = UiTheme.p.accent, fontSize = 12.sp) }
            Text(
                dir.absolutePath, color = UiTheme.p.textFaint, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (!inScan) {
                TextButton(onClick = {
                    Db.addScanDir(dir.absolutePath)
                    TestLog.evt("browse_addscan", dir.absolutePath)
                    appState.rescan()
                }) { Text(L10n.s("加入书库", "Add to library"), color = UiTheme.p.accent, fontSize = 12.sp) }
            }
        }
        if (zipCur != null) {
            val zf = zipCur!!
            val zipEntries = remember(zf) { ZipBooks.listEntries(zf.absolutePath) }
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { setZip(null) }) { Text(L10n.s("← 返回目录", "← Back"), color = UiTheme.p.accent, fontSize = 12.sp) }
                Text(zf.name, color = UiTheme.p.textFaint, fontSize = 11.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (zipEntries.isNotEmpty()) Text(L10n.s("${zipEntries.size} 本书", "${zipEntries.size} books"), color = UiTheme.p.textFaintest, fontSize = 11.sp)
            }
            if (zipEntries.isEmpty()) {
                Text(L10n.s("压缩包内没有可读图书（支持 epub/pdf/mobi/txt 等）", "No readable books in this archive (epub/pdf/mobi/txt…)"), color = UiTheme.p.textFaint, fontSize = 12.sp)
            }
            LazyColumn {
                items(zipEntries, key = { it }) { e ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            val f = ZipBooks.extract(zf.absolutePath, e)
                            if (f != null) {
                                TestLog.evt("browse_zip_entry_open", e)
                                appState.open(f.absolutePath)
                            }
                        }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Filled.MenuBook, contentDescription = null, tint = UiTheme.p.accent, modifier = Modifier.size(20.dp))
                        Text(File(e).name, color = UiTheme.p.textPrimary, fontSize = 13.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }
            }
        } else {
            if (listing.isEmpty()) {
                Text(L10n.s("（空目录）", "(empty)"), color = UiTheme.p.textFaint, fontSize = 12.sp)
            }
            LazyColumn {
                items(listing, key = { it.absolutePath }) { f ->
                    if (f.isDirectory) {
                        val n = remember(f) { f.listFiles()?.count { !it.name.startsWith(".") } ?: 0 }
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                TestLog.evt("browse_dir", f.name)
                                setZip(null)
                                setDir(f)
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(Icons.Filled.Folder, contentDescription = null, tint = UiTheme.p.accentTeal, modifier = Modifier.size(20.dp))
                            Text(
                                f.name, color = UiTheme.p.textSecondary, fontSize = 13.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            Text(L10n.s("$n 项", "$n items"), color = UiTheme.p.textFaintest, fontSize = 11.sp)
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (f.name.lowercase().endsWith(".zip")) {
                                    TestLog.evt("browse_zip_open", f.name)
                                    setZip(f)
                                } else {
                                    TestLog.evt("browse_open", "name=" + f.name)
                                    appState.open(f.absolutePath)
                                }
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(Icons.Filled.MenuBook, contentDescription = null, tint = UiTheme.p.accent, modifier = Modifier.size(20.dp))
                            Text(
                                f.name, color = UiTheme.p.textPrimary, fontSize = 13.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            Text("${f.length() / 1024} KB", color = UiTheme.p.textFaintest, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
