/* 与安卓（WebDavSyncer）互通的 WebDAV 阅读数据同步 —— core/sync/AndroidSync.kt 的 C++ 移植（books 通道）。
 * 协议：<服务器>/<远程目录>/books/<内容MD5>.json
 *   info = { name, hash, t, progress:{p,pg,pgc,t}, bookmarks:{"<创建毫秒>":{path,text,note,p,pg,t}}, dk:{key:删除毫秒} }
 * 书身份=内容 MD5；进度=策略合并整 entry 替换；书签=按 key union；dk/本地墓碑双向抑制删除复活。 */
#ifndef HRSYNC_H
#define HRSYNC_H

#include <QString>

namespace hr
{

struct SyncResult
{
    bool ok = false;
    QString error;
    int progressDown = 0, progressUp = 0, bookmarksDown = 0, booksSynced = 0;
    long long durationMs = 0;
};

/* 书文件内容 MD5（跨设备同书同 hash；进程内缓存 path+mtime+len） */
QString syncMd5(const QString &path);

/* 删除书签时写墓碑（bm; book=内容 MD5） */
void addBmTombstoneFor(const QString &bookPath, const QString &bmKey);

SyncResult androidSync(const QString &baseUrl, const QString &user, const QString &pass,
                       const QString &remoteDir, const QString &policy /*newer|local|server*/);

} // namespace hr

#endif /* HRSYNC_H */
