package howread.core.dict

import howread.core.AppPaths
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream

/**
 * StarDict 离线词典：.ifo/.idx[.gz]/.dict[.dz]。
 * 安装方式：把词典文件夹放入 dataDir/dicts/（一层或平铺均可，最深 3 层）。
 * .dz 为 dictzip（合法 gzip 流）：首次查询整包解压到缓存后随机访问。
 */
class StarDict(val ifo: File) {

    val name: String by lazy {
        runCatching {
            ifo.readLines().firstOrNull { it.startsWith("bookname=") }?.substringAfter("=")?.trim()
        }.getOrNull() ?: ifo.parentFile?.name ?: ifo.name
    }

    private val sameTypes: String? by lazy {
        runCatching {
            ifo.readLines().firstOrNull { it.startsWith("sametypesequence=") }?.substringAfter("=")?.trim()
        }.getOrNull()
    }

    private val index: Map<String, LongArray>? by lazy { loadIndex() }
    private val dictData: File? by lazy { resolveDictFile() }

    private fun idxFile(): File? {
        val gz = File(ifo.parentFile, ifo.nameWithoutExtension + ".idx.gz")
        if (gz.isFile) return gz
        val p = File(ifo.parentFile, ifo.nameWithoutExtension + ".idx")
        return if (p.isFile) p else null
    }

    private fun loadIndex(): HashMap<String, LongArray>? {
        val f = idxFile() ?: return null
        val bytes = runCatching {
            if (f.name.endsWith(".gz")) GZIPInputStream(FileInputStream(f)).use { it.readBytes() }
            else f.readBytes()
        }.getOrNull() ?: return null
        val map = HashMap<String, LongArray>(65536)
        var i = 0
        while (i < bytes.size) {
            val z = indexOfZero(bytes, i)
            if (z < 0) break
            if (z > i && z + 9 <= bytes.size) {
                val word = String(bytes, i, z - i, Charsets.UTF_8)
                map.putIfAbsent(word, longArrayOf(readU32(bytes, z + 1), readU32(bytes, z + 5)))
            }
            i = z + 9
        }
        return map
    }

    private fun indexOfZero(b: ByteArray, from: Int): Int {
        for (i in from until b.size) if (b[i] == 0.toByte()) return i
        return -1
    }

    private fun readU32(b: ByteArray, i: Int): Long =
        ((b[i].toLong() and 0xFF) shl 24) or ((b[i + 1].toLong() and 0xFF) shl 16) or
            ((b[i + 2].toLong() and 0xFF) shl 8) or (b[i + 3].toLong() and 0xFF)

    private fun resolveDictFile(): File? {
        val dir = ifo.parentFile ?: return null
        val plain = File(dir, ifo.nameWithoutExtension + ".dict")
        if (plain.isFile) return plain
        val dz = File(dir, ifo.nameWithoutExtension + ".dict.dz")
        if (dz.isFile) {
            val key = Integer.toHexString((dz.absolutePath + "|" + dz.length()).hashCode())
            val out = File(File(AppPaths.cacheDir, "dicts"), key + ".dict")
            if (!out.isFile() || out.length() == 0L) {
                val ok = runCatching {
                    out.parentFile?.mkdirs()
                    GZIPInputStream(FileInputStream(dz)).use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    }
                }.isSuccess
                if (!ok) return null
            }
            if (out.isFile() && out.length() > 0) return out
        }
        return null
    }

    /** 查词（精确优先，其次忽略大小写）；返回正文文本。 */
    fun lookup(word: String): String? {
        val idx = index ?: return null
        val data = dictData ?: return null
        val hit = idx[word]
            ?: idx.entries.firstOrNull { it.key.equals(word, ignoreCase = true) }?.value
            ?: return null
        val off = hit[0]
        val len = hit[1]
        if (len <= 0 || len > 4_000_000) return null
        val buf = ByteArray(len.toInt())
        RandomAccessFile(data, "r").use { raf ->
            raf.seek(off)
            raf.readFully(buf)
        }
        return decodeArticle(buf)
    }

    private fun decodeArticle(b: ByteArray): String {
        fun clean(s: String) = s.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
        return runCatching {
            val st = sameTypes
            val utf = String(b, Charsets.UTF_8)
            when {
                st == "x" || st == "h" -> clean(utf)
                st == "t" || st == "y" || st == "k" || st == "w" -> utf.trim()
                utf.contains("\uFFFD") -> clean(runCatching { String(b, charset("GBK")) }.getOrDefault("").ifBlank { utf })
                else -> clean(utf)
            }
        }.getOrNull() ?: ""
    }
}

/** 词典集合：扫描/查词/目录管理。 */
object Dicts {
    @Volatile private var cache: List<StarDict>? = null

    fun dictDir(): File = File(AppPaths.dataDir, "dicts").apply { mkdirs() }

    fun installed(refresh: Boolean = false): List<StarDict> {
        if (!refresh) cache?.let { return it }
        val out = ArrayList<StarDict>()
        val root = dictDir()
        if (root.isDirectory) {
            root.walkTopDown().maxDepth(3)
                .filter { it.isFile && it.name.endsWith(".ifo") }
                .forEach { out.add(StarDict(it)) }
        }
        cache = out
        return out
    }

    /** 全部词典查词，返回 (词典名, 正文) 只含有命中的。 */
    fun lookupAll(word: String): List<Pair<String, String>> {
        val w = word.trim()
        if (w.isEmpty()) return emptyList()
        return installed().mapNotNull { d ->
            runCatching { d.lookup(w) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { d.name to it }
        }
    }
}
