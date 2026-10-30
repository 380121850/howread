package howread.core.model

data class RemoteSource(
    val id: Long,
    val protocol: String,   // WEBDAV / SMB / SFTP
    val name: String,
    val host: String,
    val port: Int,
    val path: String,       // 起始目录（WebDAV 为路径段，SMB 为共享名[/子目录]，SFTP 为绝对目录）
    val user: String,
    val password: String,
)
