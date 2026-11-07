/* HowRead Qt v0.105.0（P1-5）：CBR→目录书。unrar 7.1.6 vendored 静态库（Builder/unrar），
 * 经 dll.hpp 的 RAR* API 解包全部条目到 destDir；路径含 ".." 或绝对路径的条目跳过；
 * 加密 rar（无密码 UI）返回 4；未编 unrar 返回 100。 */
#include "hr_bridge.h"

#if defined(HR_HAVE_UNRAR)
#include <windows.h> /* v0.105.0: must precede dll.hpp (needs CALLBACK/LPARAM types) */
#include "dll.hpp"
#include <stdio.h>
#include <string.h>

static int g_rarPw = 0; /* 加密 rar：回调置位 */

static int CALLBACK rarPwCb(UINT msg, LPARAM lParam, LPARAM P1, LPARAM P2)
{
    (void)lParam; (void)P1; (void)P2;
    /* v0.105.0: 解包每块都会回调 UCM_PROCESSDATA, 返回 -1 会中断解包;
     * 只对密码询问返回 -1(本实现无密码 UI), 其余消息返回 0 继续。 */
    if (msg == UCM_NEEDPASSWORD || msg == UCM_NEEDPASSWORDW)
    {
        g_rarPw = 1;
        return -1; /* 取消（无密码 UI） */
    }
    return 0;
}

extern "C" int hr_qt_rar_extract(const char *rarPath, const char *destDir,
                                 char *outName, int outLen)
{
    if (!rarPath || !destDir) return 1;
    wchar_t wsrc[1024] = {0}, wdst[1024] = {0};
    MultiByteToWideChar(CP_UTF8, 0, rarPath, -1, wsrc, 1024);
    MultiByteToWideChar(CP_UTF8, 0, destDir, -1, wdst, 1024);

    RAROpenArchiveDataEx od;
    memset(&od, 0, sizeof od);
    od.ArcNameW = wsrc;
    od.OpenMode = RAR_OM_EXTRACT;
    od.Callback = rarPwCb;
    HANDLE h = RAROpenArchiveEx(&od);
    if (!h || od.OpenResult != 0)
    {
        if (h) RARCloseArchive(h);
        return 2;
    }
    int rc = 0, files = 0, lastRr = 0;
    RARHeaderDataEx hd;
    for (;;)
    {
        memset(&hd, 0, sizeof hd);
        const int rr = RARReadHeaderEx(h, &hd);
        if (rr != 0) { lastRr = rr; break; } /* ERAR_END_ARCHIVE 或错误 */
        const wchar_t *nm = hd.FileNameW;
        int bad = 0;
        for (const wchar_t *p = nm; *p; ++p)
            if (p[0] == L'.' && p[1] == L'.') { bad = 1; break; }
        if (bad || nm[0] == L'/' || nm[0] == L'\\' || (nm[0] && nm[1] == L':'))
        {
            if (RARProcessFile(h, RAR_SKIP, NULL, NULL) != 0) { rc = 3; break; }
            continue;
        }
        if (files >= 5000)
        {
            RARProcessFile(h, RAR_SKIP, NULL, NULL);
            continue;
        }
        if (RARProcessFileW(h, RAR_EXTRACT, wdst, NULL) != 0)
        {
            if (g_rarPw) { rc = 4; break; } /* 加密 rar：整体失败 */
            continue; /* 单条目失败跳过 */
        }
        files++;
    }
    RARCloseArchive(h);
    if (rc == 0 && files == 0) rc = g_rarPw ? 4 : 6;
    if (outName && outLen > 0)
        snprintf(outName, outLen, rc == 0 ? "cbr" : "rr=%d files=%d", lastRr, files);
    return rc;
}
#else
extern "C" int hr_qt_rar_extract(const char *, const char *, char *, int)
{
    return 100; /* 未编 unrar（缺库自动降级） */
}
#endif
