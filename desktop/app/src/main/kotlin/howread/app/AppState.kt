package howread.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import howread.core.data.Db
import howread.core.library.Scanner
import howread.core.model.Book
import java.io.File
import javax.swing.SwingUtilities

/** 底部标签栏 / 侧栏主导航（对齐安卓 MainTabs2 四 tab）。 */
enum class MainTab(val zh: String, val en: String) { HOME("首页", "Home"), LIBRARY("书库", "Library"), BROWSE("我的文件", "Files"), PREFS("偏好", "Prefs") }

/** 侧栏快捷内容页（对齐安卓抽屉入口 + 首页宫格）。 */
enum class ExtraContent(val zh: String, val en: String) {
    NONE("", ""), BOOKMARKS("书签笔记", "Notes"), FAVORITES("我的珍藏", "Favorites"), REMOTE("网上书库", "Network")
}

/** 书库排序键（全部状态下生效；文件名升序，其余降序；语言=中文书在前、格式=扩展名字母序）。 */
enum class SortKey(val zh: String, val en: String) {
    TITLE("文件名", "Name"), RECENT("最近", "Recent"), SIZE("大小", "Size"), DATE("时间", "Date"),
    AUTHOR("作者", "Author"), SERIES("系列", "Series"), LANG("语言", "Language"), FORMAT("格式", "Format"), PAGES("页数", "Pages")
}

/** 书库视图模式（v0.30.0 五种，对齐安卓视图菜单）。 */
enum class LibView(val zh: String, val en: String) { LIST("列表", "List"), TABLE("简表", "Table"), GRID("网格", "Grid"), COVER("封面", "Cover"), SHELF("书架", "Shelf"),
    GROUP_AUTHOR("按作者", "By author"), GROUP_SERIES("按系列", "By series"), GROUP_LANG("按语言", "By language"), GROUP_FORMAT("按格式", "By format") }

/** 阅读状态过滤（全部/未读/在读/已读）。 */
enum class BookStatus(val zh: String, val en: String) { ALL("全部", "All"), UNREAD("未读", "Unread"), READING("在读", "Reading"), FINISHED("已读", "Finished") }

class AppState {
    data class ReaderBook(val path: String, val name: String, val page: Int = -1)

    var readerBook by mutableStateOf<ReaderBook?>(null)
        private set

    var library by mutableStateOf<List<Book>>(emptyList())
        private set

    var mainTab by mutableStateOf(MainTab.HOME)
    var extra by mutableStateOf(ExtraContent.NONE)

    var query by mutableStateOf(if (LibPrefs.bool("rememberSearch", false)) LibPrefs.str("lastQuery", "") else "")

    /** 书库排序键（全部状态下生效；名称升序，其余降序）。 */
    var sortKey by mutableStateOf(LibPrefs.defaultSort())

    /** 书库视图模式（v0.30.0：列表/简表/网格/封面/书架）。 */
    var libView by mutableStateOf(LibPrefs.defaultView())

    /** 阅读状态过滤（全部/未读/在读/已读；v0.36.0 起书库唯一过滤行，原全部/最近/收藏 tab 行取消）。 */
    var statusFilter by mutableStateOf(BookStatus.ALL)

    /** 书库扩展名过滤（v0.50.0 安卓 ExtFilter 等价：chips 多选，空集合=不过滤）。 */
    var extFilter by mutableStateOf<Set<String>>(emptySet())
    var tagFilter by mutableStateOf("")   // 侧栏标签过滤（v0.51.0 对齐安卓 Tags2；空=不过滤）

    /** 蓝光滤镜级别（v0.50.0 对齐安卓蓝光护眼：0=关 1=轻 2=中，主窗口暖色叠加层）。 */
    var blueFilter by mutableStateOf(LibPrefs.str("blueFilter", "0").toIntOrNull() ?: 0)

    /** 网络书缓存角标重算时点（v0.36.0）：书库有远程书时每 2s +1，触发角标重组。 */
    var cacheTick by mutableStateOf(0)

    /** 我的文件→网上书库"编辑"带出的服务器 id（RemoteScreen 打开时消费）。 */
    var remoteEditId by mutableStateOf<Long?>(null)

    /** 书库多选模式与选中路径集合。 */
    var selectionMode by mutableStateOf(false)
    var selected by mutableStateOf<Set<String>>(emptySet())

    var showAiConfig by mutableStateOf(false)

    var scanning by mutableStateOf(false)
        private set

    fun reloadLibrary() {
        library = Db.allBooks()
        finishedMap = library.associate { b ->
            val fin = Db.loadProgress(b.path)?.let { it.pageCount > 0 && it.page >= it.pageCount - 1 } ?: false
            b.path to fin
        }
    }

    /** 已读完结表（reloadLibrary 时重建，避免 visibleBooks 每次重组查库）。 */
    var finishedMap by mutableStateOf<Map<String, Boolean>>(emptyMap())
        private set

    fun visibleBooks(): List<Book> {
        val q = query.trim().lowercase()
        var list = library.filter { q.isEmpty() || it.title.lowercase().contains(q) || it.path.lowercase().contains(q) }
        if (extFilter.isNotEmpty()) list = list.filter { it.path.substringAfterLast('.', "").lowercase() in extFilter }
        if (tagFilter.isNotEmpty()) list = list.filter { it.tag == tagFilter }
        when (statusFilter) {
            BookStatus.UNREAD -> list = list.filter { it.lastReadAt == 0L }
            BookStatus.READING -> list = list.filter { it.lastReadAt > 0L && finishedMap[it.path] != true }
            BookStatus.FINISHED -> list = list.filter { finishedMap[it.path] == true }
            BookStatus.ALL -> {}
        }
        if (LibPrefs.bool("hideReadBooks", false)) list = list.filter { finishedMap[it.path] != true }
        list = when (sortKey) {
                SortKey.TITLE -> list
                SortKey.RECENT -> list.sortedByDescending { it.lastReadAt }
                SortKey.SIZE -> list.sortedByDescending { it.size }
                SortKey.DATE -> list.sortedByDescending { it.mtime }
                SortKey.AUTHOR -> {
                    val surnameFirst = LibPrefs.bool("authorSurnameFirst", false)
                    list.sortedWith(compareBy(
                        { it.author.isBlank() },
                        { a -> if (surnameFirst) a.author.lowercase().substringAfterLast(' ') else a.author.lowercase() },
                        { it.title.lowercase() },
                    ))
                }
                SortKey.SERIES -> list.sortedWith(compareBy({ it.series.isBlank() }, { it.series.lowercase() }, { it.seriesIdx }, { it.title.lowercase() }))
                SortKey.LANG -> list.sortedWith(compareBy({ if (cjkTitle(it.title)) 0 else 1 }, { it.title.lowercase() }))
                SortKey.FORMAT -> list.sortedWith(compareBy({ it.path.substringAfterLast('.', "").lowercase() }, { it.title.lowercase() }))
                SortKey.PAGES -> list.sortedByDescending { it.pages }
        }
        return list
    }

    /** 标题含 CJK 视为中文书（语言排序启发式）。 */
    private fun cjkTitle(t: String): Boolean = t.any { it.code in 0x4E00..0x9FFF }

    /** 切主 tab（清 extra）。 */
    fun goto(t: MainTab) {
        mainTab = t
        extra = ExtraContent.NONE
    }

    /** 全局增量扫描（启动/偏好页手动触发）。 */
    fun rescan() {
        if (scanning) return
        scanning = true
        Thread {
            Scanner.scanAll(LibPrefs.bool("ignoreNomedia", true))
            SwingUtilities.invokeLater {
                reloadLibrary()
                scanning = false
            }
        }.start()
    }

    fun open(path: String) {
        if (LibPrefs.bool("openAutoAdd", true)) {
            val f = File(path)
            if (f.isFile && howread.core.library.Scanner.isBookFile(path)) {
                runCatching { Db.upsertFileMeta(f.absolutePath, f.nameWithoutExtension, f.length(), f.lastModified()) }
            }
        }
        Db.markRecent(path)
        readerBook = ReaderBook(path, File(path).name)
        reloadLibrary()
    }

    /** 打开并直接落到指定页（书签/笔记跳转）。 */
    fun openAt(path: String, page: Int) {
        Db.markRecent(path)
        readerBook = ReaderBook(path, File(path).name, page)
        reloadLibrary()
    }

    fun closeReader() {
        readerBook = null
    }
}
