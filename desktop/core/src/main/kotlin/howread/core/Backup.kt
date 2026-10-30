package howread.core

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份与恢复：zip 打包（sqlite 一致性快照 + 配置 json）。
 * 恢复走 restore-staging 暂存目录，下次启动时（Db 打开前）自动应用，避免文件占用冲突。
 */
object Backup {

    private fun configFiles(): List<File> {
        val d = AppPaths.dataDir
        return listOf("ai.json", "reader.json", "ui.json", "sync.json", "lib.properties")
            .map { File(d, it) }.filter { it.isFile }
    }

    fun exportTo(dest: File): String {
        val n = ArrayList<File>()
        val snap = File(AppPaths.dataDir, "backup-snapshot.tmp.db")
        runCatching { snap.delete() }
        try {
            howread.core.data.Db.backupSnapshotTo(snap)
            // 检视修复：先写 .part 再 rename——中断不留残缺 zip
            val tmp = File(dest.parentFile, dest.name + ".part")
            runCatching { tmp.delete() }
            ZipOutputStream(FileOutputStream(tmp)).use { zos ->
                zos.putNextEntry(ZipEntry(AppPaths.dbFile.name))
                FileInputStream(snap).use { it.copyTo(zos) }
                zos.closeEntry()
                for (f in configFiles()) {
                    zos.putNextEntry(ZipEntry(f.name))
                    FileInputStream(f).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            return "已备份 ${configFiles().size + 1} 个文件（数据库一致性快照 + 配件）→ ${dest.absolutePath}"
        } finally {
            runCatching { snap.delete() }
        }
    }

    /** 解包到暂存目录，重启后由 applyPendingRestore 应用。 */
    fun stageRestore(zip: File): String {
        val staging = File(AppPaths.dataDir, "restore-staging")
        staging.mkdirs()
        var n = 0
        ZipInputStream(FileInputStream(zip)).use { zis ->
            // 检视修复：白名单 + 跳过目录条目 + .part 原子落盘（防同名覆盖/中断半套）
            val allowed = setOf("howread.db", "ai.json", "reader.json", "ui.json", "sync.json", "lib.properties")
            while (true) {
                val e = zis.nextEntry ?: break
                if (e.isDirectory) continue
                val name = File(e.name).name  // 防路径穿越，只收根层文件
                if (name.isBlank() || name !in allowed) continue
                val tmp = File(staging, name + ".part")
                tmp.outputStream().use { zis.copyTo(it) }
                val fin = File(staging, name)
                if (fin.exists()) fin.delete()
                if (!tmp.renameTo(fin)) tmp.delete() else n++
            }
        }
        return "已读取 $n 个文件到暂存区，重启应用后自动生效"
    }

    /** 启动早期（打开数据库之前）调用；返回是否应用了恢复。 */
    fun applyPendingRestore(): Boolean {
        val staging = File(AppPaths.dataDir, "restore-staging")
        val files = staging.listFiles() ?: return false
        var applied = 0
        for (f in files) {
            val target = File(AppPaths.dataDir, f.name)
            runCatching {
                if (!f.renameTo(target)) {
                    // 检视修复：先写临时文件再原子替换——写中崩溃不损坏目标库/配置
                    val tmp = File(AppPaths.dataDir, f.name + ".restore.tmp")
                    f.copyTo(tmp, overwrite = true)
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                    f.delete()
                }
                applied++
            }
        }
        staging.delete()
        return applied > 0
    }
}
