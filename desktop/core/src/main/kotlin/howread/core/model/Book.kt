package howread.core.model

data class Book(
    val path: String,
    val title: String,
    val pages: Int,        // -1 = 扫描时未取（懒取）
    val size: Long,
    val mtime: Long,
    val isFavorite: Boolean,
    val lastReadAt: Long,  // 0 = 未读过
    val author: String = "",
    val series: String = "",     // 文件名解析的系列名（空=无）
    val seriesIdx: Int = -1,     // 系列内序号（-1=无）
    val metaTitle: String = "",  // 引擎元数据标题（v0.45.0，打开书籍时回填）
    val description: String = "", // 引擎元数据描述（v0.45.0，文件信息"显示书籍描述"用）
    val tag: String = "",          // 用户标签（v0.51.0，对齐安卓 Tags2；空=无）
)

data class ReadingProgress(
    val page: Int,          // 0-based
    val pageCount: Int,     // 保存时的总页数（纪元校验用）
    val viewportW: Int,     // 保存时的渲染宽度（纪元校验用）
    val updatedAt: Long,
)

data class Bookmark(
    val id: Long,
    val path: String,
    val page: Int,          // 0-based
    val label: String,
    val note: String,
    val createdAt: Long,
)
