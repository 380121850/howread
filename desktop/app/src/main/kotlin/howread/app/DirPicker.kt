package howread.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import howread.core.TestLog
import java.io.File
import java.text.Collator
import java.util.Locale

/**
 * 应用内现代样式目录选择器（v0.29.0）：替代 Swing JFileChooser 的上古观感。
 * 面包屑可点 + 侧栏快速位置/磁盘 + 子目录列表（单击进入）+ 可编辑路径栏；
 * 「打开」返回当前所在目录。Windows/Linux 观感一致，跟随应用主题。
 */
@Composable
fun DirPickerDialog(
    title: String,
    initial: String? = null,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val home = remember { File(System.getProperty("user.home")) }
    var cur by remember {
        mutableStateOf(
            (initial?.let { p -> runCatching { File(p).takeIf { it.isDirectory } }.getOrNull() } ?: home).canonicalFile
        )
    }
    var pathText by remember(cur) { mutableStateOf(cur.absolutePath) }
    val collator = remember { Collator.getInstance(Locale.CHINA) }

    fun go(f: File) {
        val t = if (f.isDirectory) f else f.parentFile ?: return
        cur = runCatching { t.canonicalFile }.getOrDefault(t)
        pathText = cur.absolutePath
    }

    val quick = remember(home) {
        listOf(
            Triple(L10n.s("桌面", "Desktop"), File(home, "Desktop"), Icons.Filled.DesktopWindows),
            Triple(L10n.s("下载", "Downloads"), File(home, "Downloads"), Icons.Filled.Download),
            Triple(L10n.s("文档", "Documents"), File(home, "Documents"), Icons.Filled.Description),
            Triple(L10n.s("图片", "Pictures"), File(home, "Pictures"), Icons.Filled.Photo),
            Triple(L10n.s("音乐", "Music"), File(home, "Music"), Icons.Filled.MusicNote),
            Triple(L10n.s("视频", "Videos"), File(home, "Videos"), Icons.Filled.VideoLibrary),
        ).filter { it.second.isDirectory }
    }
    val roots = remember { File.listRoots().filter { it.exists() } }
    val children = remember(cur) {
        runCatching {
            cur.listFiles { f -> f.isDirectory && !f.isHidden }
                ?.sortedWith(compareBy(collator) { it.name.lowercase() })
                ?: emptyList()
        }.getOrDefault(emptyList())
    }
    // 面包屑：根 → … → 当前
    val crumbs = remember(cur) {
        val acc = ArrayList<File>()
        var f: File? = cur
        while (f != null) {
            acc.add(0, f)
            f = f.parentFile
        }
        acc
    }

    TestLog.evt("dirpick_open", cur.absolutePath.take(120))
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.size(760.dp, 560.dp).clip(RoundedCornerShape(12.dp)),
            color = UiTheme.p.panel,
        ) {
            Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, color = UiTheme.p.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)

                // ---- 面包屑 + 上级 ----
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(
                        onClick = { cur.parentFile?.let { go(it) } },
                        enabled = cur.parentFile != null,
                    ) {
                        Icon(Icons.Filled.ArrowUpward, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(2.dp))
                        Text(L10n.s("上级", "Up"), fontSize = 12.sp)
                    }
                    Row(
                        Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(UiTheme.p.bar)
                            .horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        crumbs.forEachIndexed { i, f ->
                            if (i > 0) Text(" › ", color = UiTheme.p.textFaintest, fontSize = 12.sp)
                            Text(
                                f.name.ifEmpty { f.absolutePath },
                                color = if (i == crumbs.lastIndex) UiTheme.p.accent else UiTheme.p.textSecondary,
                                fontSize = 12.sp,
                                fontWeight = if (i == crumbs.lastIndex) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                modifier = Modifier.clip(RoundedCornerShape(4.dp))
                                    .clickable { if (i != crumbs.lastIndex) go(f) }.padding(horizontal = 2.dp),
                            )
                        }
                    }
                }

                // ---- 侧栏 + 目录列表 ----
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(
                        Modifier.width(172.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp))
                            .background(UiTheme.p.bar).verticalScroll(rememberScrollState()).padding(6.dp)
                    ) {
                        for ((label, f, ic) in quick) {
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                    .clickable { go(f) }.padding(horizontal = 6.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(ic, contentDescription = null, tint = UiTheme.p.accentTeal, modifier = Modifier.size(16.dp))
                                Text(label, fontSize = 12.sp, color = UiTheme.p.textSecondary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Text(
                            L10n.s("此电脑", "This PC"),
                            color = UiTheme.p.textFaintest, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 2.dp),
                        )
                        for (r in roots) {
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                    .clickable { go(r) }.padding(horizontal = 6.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Filled.Storage, contentDescription = null,
                                    tint = UiTheme.p.accentTeal, modifier = Modifier.size(16.dp))
                                Text(r.absolutePath, fontSize = 12.sp, color = UiTheme.p.textSecondary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (children.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            Text(L10n.s("（无子文件夹）", "(no subfolders)"), color = UiTheme.p.textFaint, fontSize = 12.sp)
                        }
                    } else {
                        LazyColumn(Modifier.weight(1f).fillMaxHeight()) {
                            items(children.size) { i ->
                                val d = children[i]
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                        .clickable { go(d) }
                                        .padding(horizontal = 8.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(Icons.Filled.Folder, contentDescription = null,
                                        tint = UiTheme.p.accentTeal, modifier = Modifier.size(18.dp))
                                    Text(d.name, fontSize = 13.sp, color = UiTheme.p.textPrimary,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }

                // ---- 路径栏 + 操作 ----
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pathText,
                        onValueChange = { pathText = it },
                        label = { Text(L10n.s("目录", "Folder"), fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { val f = File(pathText.trim()); if (f.isDirectory) go(f) }) {
                        Text(L10n.s("前往", "Go"), fontSize = 12.sp)
                    }
                    Button(onClick = {
                        TestLog.evt("dirpick_pick", cur.absolutePath.take(120))
                        onPick(cur.absolutePath)
                    }) { Text(L10n.s("打开", "Open")) }
                    OutlinedButton(onClick = onDismiss) { Text(L10n.s("取消", "Cancel")) }
                }
                Text(
                    L10n.s("单击文件夹进入；「打开」选择当前所在目录。", "Click a folder to enter; Open selects the current folder."),
                    color = UiTheme.p.textFaintest, fontSize = 10.sp,
                )
            }
        }
    }
}
