/* HowRead Qt 入口：
 *   HowReadQt.exe [book]           图形界面
 *   HowReadQt.exe --smoke book out.png [page]   无头冒烟：开书→渲染→存 PNG→写 evt→退出码 0/3
 * 数据目录：HOWREAD_DATA env > <exe根>\data-dir.txt > <exe根>\data（与 Java 版同协议）。
 */
#include "hr_bridge.h"
#include <cstdio>
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h> /* v0.99.0 W20：崩溃取证 */

/* v0.99.0 W20：未处理异常过滤器——崩溃瞬间把 RVA 调用链写入 exe 目录 logs/crash.log
 *（免堆 WinAPI 直写；随后 EXCEPTION_CONTINUE_SEARCH 保留 WER 原通道）。 */
static LONG WINAPI hr_crash_filter(EXCEPTION_POINTERS *ep)
{
    void *fr[26];
    WORD n = RtlCaptureStackBackTrace(0, 26, fr, NULL);
    HMODULE base = GetModuleHandleA(NULL);
    char line[1024];
    int off = snprintf(line, sizeof line, "evt=crash code=0x%08lx frames=",
                       (unsigned long)ep->ExceptionRecord->ExceptionCode);
    for (WORD i = 0; i < n && off < (int)sizeof line - 16; ++i)
        off += snprintf(line + off, sizeof(line) - off, "%lx ",
                        (unsigned long)((char *)fr[i] - (char *)base));
    if (off > 0 && off < (int)sizeof line) off += snprintf(line + off, sizeof(line) - off, "\n");
    char exep[MAX_PATH];
    if (GetModuleFileNameA(NULL, exep, MAX_PATH))
    {
        char *sl = strrchr(exep, '\\');
        if (sl) *sl = 0;
        char path[MAX_PATH];
        snprintf(path, sizeof path, "%s\\data\\logs\\crash.log", exep); /* W21：data\logs 必然存在 */
        HANDLE hf = CreateFileA(path, FILE_APPEND_DATA, FILE_SHARE_READ | FILE_SHARE_WRITE,
                                NULL, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
        if (hf != INVALID_HANDLE_VALUE)
        {
            DWORD w = 0;
            WriteFile(hf, line, (DWORD)off, &w, NULL);
            CloseHandle(hf);
        }
    }
    return EXCEPTION_CONTINUE_SEARCH;
}

#include "hrbcache.h"
#include "hrdb.h"
#include "hrpaths.h"
#include "hrcvt.h"
#include "hrssh.h"
#include "hrsmb.h"
#include "hricons.h"
#include "hrtheme.h"
#include <QPalette>
#include "hropds.h"
#include "hrfetch.h"

#include <memory>
#include "hrsync.h"
#include "hrai.h"
#include "hrtts.h"
#include "hrstats.h"
#include "readerjson.h"
#include "hrwebdav.h"
#include "librarywindow.h"
#include "readerwindow.h"

#include <QApplication>
#include <QCryptographicHash>
#include <QDialog>
#include <QLabel>
#include <QLineEdit>
#include <QPushButton>
#include <QVBoxLayout>
#include "hrl10n.h"
#include <QDateTime>
#include <QDir>
#include <QEventLoop>
#include <QFile>
#include <QFileInfo>
#include <QFont>
#include <QSettings>
#include <QImage>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QString>
#include <QThread>
#include <QThreadPool>
#include <QUrl>
#include <QtGlobal>
#include <cstdio>
#include <cstdlib>

/* 块缓存读回调（remote smoke 用） */
static int hrCacheReadCb(void *ud, long long pos, unsigned char *buf, int len)
{
    return static_cast<hr::BlockCacheStore *>(ud)->readAt(pos, buf, len);
}

#ifdef QT_STATIC
#include <QtPlugin>
Q_IMPORT_PLUGIN(QWindowsIntegrationPlugin)
Q_IMPORT_PLUGIN(QSQLiteDriverPlugin)
#endif

using namespace hr;

int main(int argc, char **argv)
{
    SetUnhandledExceptionFilter(hr_crash_filter); /* W20 */
    QApplication app(argc, argv);

    /* v0.90.0 W11：窗口图标与安卓 APP 同图（desktop howread_icon.png；exe 内嵌 ICO 走 app.rc） */
    app.setWindowIcon(QIcon(QString::fromLatin1(":/res/app_icon.png")));

    /* U4：恢复默认配置（出厂复位标记：清偏好/阅读排版/语言后重启生效） */
    {
        const QString marker = hr::dataDir() + "/.hr_reset_defaults";
        if (QFile::exists(marker))
        {
            QFile::remove(hr::dataDir() + "/qt-prefs.ini");
            QFile::remove(hr::dataDir() + "/reader.json");
            QFile::remove(hr::dataDir() + "/lang.json");
            QFile::remove(marker);
            hr::evt("reset_defaults_done");
        }
    }
    /* U4：应用级字体大小（ui/appFontPct，70~200；100=自动） */
    {
        QSettings st(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
        const int pct = qBound(70, st.value("ui/appFontPct", 100).toInt(), 200);
        /* v0.77.0 V2：全局字体显式化（对齐 Compose 桌面默认：Latin=Segoe UI，CJK 自动回退微软雅黑），
         * 基准 11pt（v0.90.0 W11：10pt→11pt 页面字体整体调大）再按 appFontPct 缩放；
         * QSS 像素字号经 hr::Ui::fsPx 同步缩放（scale 同步 ×1.1） */
        QFont f = app.font();
        f.setFamily(QString::fromLatin1("Segoe UI"));
        f.setPointSize(qMax(6, qRound(11.0 * pct / 100.0)));
        app.setFont(f);
        hr::Ui::setQssScale(pct / 100.0 * 1.1);
        evt("ui_font", QString("family=%1 pt=%2 pct=%3").arg(f.family()).arg(f.pointSize()).arg(pct));
    }

    bool smoke = false, libscan = false, assoc = false, remoteSmoke = false, syncSmoke = false;
    bool selSmoke = false, ttsSmoke = false, aiSmoke = false, homeHook = false;
    bool asyncSmoke = false; /* v0.103.0：后台打开全链无头回归 */
    bool smbHook = false, smbSmoke = false;
    bool annHook = false, annSeed = false;
    bool embedSmoke = false; /* v0.75.0 内嵌阅读无头回归 */
    bool prefSmoke = false;  /* v0.85.0 W4：偏好页自绘截图 */
    bool shotsSmoke = false, browse2Smoke = false, w20Repro = false, w20Rescan = false; /* v0.87.0 截图对比探针：七页自绘 */
    bool shotsViews = false; /* v0.87.0 二轮：书库四视图自绘 */
    bool shotsDark = false;  /* v0.87.0 二轮：运行时夜间切换自绘 */
    bool iconSmoke = false;  /* v0.76.0 V1 图标管线无头回归 */
    bool themeSmoke = false; /* v0.79.0 V4 主题色槽位无头回归 */
    bool sshSmoke = false, opdsSmoke = false, statsSmoke = false, coverSmoke = false;
    bool rsHook = false;
    QString sshDir;
    QString book, outPng, libDir, remoteArg2, remoteArg3, syncUser, syncPass;
    QString smbArg1, smbArg2, smbArg4;
    QString annArg;
    QString embedArg;
    int page = -1;
    for (int i = 1; i < argc; i++)
    {
        QString a = QString::fromLocal8Bit(argv[i]);
        if (a == "--smoke" && i + 2 < argc)
        {
            smoke = true;
            book = QString::fromLocal8Bit(argv[i + 1]);
            outPng = QString::fromLocal8Bit(argv[i + 2]);
            i += 2;
        }
        else if (a == "--asyncsmoke" && i + 2 < argc)
        {
            /* v0.103.0 P0-1：后台打开路径无头回归（worker 打开/排版→UI 装配→首渲全链） */
            asyncSmoke = true;
            smoke = true;
            book = QString::fromLocal8Bit(argv[i + 1]);
            outPng = QString::fromLocal8Bit(argv[i + 2]);
            i += 2;
        }
        else if (a == "--remotesmoke" && i + 4 < argc)
        {
            remoteSmoke = true;
            book = QString::fromLocal8Bit(argv[i + 1]); /* 书的完整 URL */
            outPng = QString::fromLocal8Bit(argv[i + 2]);
            remoteArg2 = QString::fromLocal8Bit(argv[i + 3]); /* user */
            remoteArg3 = QString::fromLocal8Bit(argv[i + 4]); /* pass */
            i += 4;
        }
        else if (a == "--syncsmoke" && i + 5 < argc)
        {
            syncSmoke = true;
            book = QString::fromLocal8Bit(argv[i + 1]); /* base url */
            remoteArg2 = QString::fromLocal8Bit(argv[i + 2]); /* 远程目录 */
            remoteArg3 = QString::fromLocal8Bit(argv[i + 3]); /* policy */
            syncUser = QString::fromLocal8Bit(argv[i + 4]);
            syncPass = QString::fromLocal8Bit(argv[i + 5]);
            i += 5;
        }
        else if (a == "--selsmoke" && i + 1 < argc)
        {
            selSmoke = true;
            book = QString::fromLocal8Bit(argv[i + 1]);
            i += 1;
        }
        else if (a == "--sshsmoke" && i + 5 < argc)
    {
        sshSmoke = true;
        book = QString::fromLocal8Bit(argv[i + 1]);  /* host[:port] */
        remoteArg2 = QString::fromLocal8Bit(argv[i + 2]); /* user */
        remoteArg3 = QString::fromLocal8Bit(argv[i + 3]); /* pass */
        sshDir = QString::fromLocal8Bit(argv[i + 4]);     /* 起始目录 */
        outPng = QString::fromLocal8Bit(argv[i + 5]);
        i += 5;
    }
    else if (a == "--opdssmoke" && i + 4 < argc)
    {
        opdsSmoke = true;
        book = QString::fromLocal8Bit(argv[i + 1]);  /* feed URL 或本地 xml */
        remoteArg2 = QString::fromLocal8Bit(argv[i + 2]);
        remoteArg3 = QString::fromLocal8Bit(argv[i + 3]);
        outPng = QString::fromLocal8Bit(argv[i + 4]);
        i += 4;
    }
    else if (a == "--coversmoke" && i + 1 < argc)
    {
        coverSmoke = true;
        libDir = QString::fromLocal8Bit(argv[i + 1]);
        i += 1;
    }
    else if (a == "--statssmoke")
    {
        statsSmoke = true;
    }
    else if (a == "--ttssmoke")
    {
        ttsSmoke = true;
    }
        else if (a == "--aismoke")
        {
            aiSmoke = true;
        }
        else if (a == "--homehook")
        {
            homeHook = true;
        }
        else if (a == "--smbhook")
        {
            smbHook = true;
        }
        else if (a == "--annhook")
        {
            annHook = true;
        }
        else if (a == "--annseed" && i + 1 < argc)
        {
            annSeed = true;
            annArg = QString::fromLocal8Bit(argv[i + 1]);
            i += 1;
        }
        else if (a == "--embedsmoke" && i + 1 < argc)
        {
            embedSmoke = true;
            embedArg = QString::fromLocal8Bit(argv[i + 1]);
            i += 1;
        }
        else if (a == "--prefsmoke")
            prefSmoke = true;
        else if (a == "--shotsmoke")
            shotsSmoke = true;
        else if (a == "--browse2") /* v0.96.0 W17#5：browse 页 double-free 回归探针 */
            browse2Smoke = true;
        else if (a == "--w20repro") /* v0.99.0 W20#1：selectionModel 崩溃重放探针 */
            w20Repro = true;
        else if (a == "--w20rescan") /* v0.99.0 W20：引擎全书重开取证探针 */
            w20Rescan = true;
        else if (a == "--shotsviews")
            shotsViews = true;
        else if (a == "--shotsdark")
            shotsDark = true;
        else if (a == "--iconsmoke")
        {
            iconSmoke = true;
        }
        else if (a == "--themesmoke")
        {
            themeSmoke = true;
        }
        else if (a == "--smbsmoke" && i + 6 < argc)
        {
            smbSmoke = true;
            smbArg1 = QString::fromLocal8Bit(argv[i + 1]); /* host:port */
            smbArg2 = QString::fromLocal8Bit(argv[i + 2]); /* share */
            remoteArg2 = QString::fromLocal8Bit(argv[i + 3]); /* user */
            remoteArg3 = QString::fromLocal8Bit(argv[i + 4]); /* pass */
            smbArg4 = QString::fromLocal8Bit(argv[i + 5]); /* 共享内文件路径 */
            outPng = QString::fromLocal8Bit(argv[i + 6]);
            i += 6;
        }
        else if (a == "--libscan" && i + 1 < argc)
        {
            libscan = true;
            libDir = QString::fromLocal8Bit(argv[i + 1]);
            i += 1;
        }
        else if (a == "--rshook")
        {
            rsHook = true;
        }
        else if (a == "--assoc")
        {
            assoc = true;
        }
        else if (a == "--page" && i + 1 < argc)
        {
            page = atoi(argv[i + 1]) - 1;
            i += 1;
        }
        else if (book.isEmpty())
            book = a;
    }

    /* P1 适配（B9）：用户字体目录随 data 走（hr_user_fonts 读 HR_FONTS_DIR；
     * 原引擎兜底 %LOCALAPPDATA%\HowRead\fonts 旧路径，与便携 data 脱节） */
    {
        const QString fontsDir = hr::dataDir() + QString::fromLatin1("/fonts");
        QDir().mkpath(fontsDir);
        qputenv("HR_FONTS_DIR", QDir::toNativeSeparators(fontsDir).toUtf8());
    }
    if (hr_qt_init() != 0)
    {
        evt("engine_init_fail", QString::fromUtf8(hr_qt_last_error()).left(120));
        return 2;
    }
    /* P0 适配（F2）：导入配置携带的 howread.db 替换（Db::open 前应用） */
    {
        const QString pend = hr::dataDir() + QString::fromLatin1("/howread.db.import");
        if (QFile::exists(pend))
        {
            QFile::remove(hr::dataDir() + QString::fromLatin1("/howread.db-wal"));
            QFile::remove(hr::dataDir() + QString::fromLatin1("/howread.db-shm"));
            QFile::remove(hr::dataDir() + QString::fromLatin1("/howread.db"));
            QFile::rename(pend, hr::dataDir() + QString::fromLatin1("/howread.db"));
            hr::evt(QString::fromLatin1("backup_db_applied"));
        }
    }
    Db::open();
    hr::importAndroidCssIfNeeded(); /* 批次三：安卓 app-CSS.json/app-State.json 导入（mtime 门控） */
    evt("app_start", QString("v=%1 qt=%2").arg(appVersion()).arg(QT_VERSION_STR));

    /* v0.76.0 V1 --iconsmoke：QtSvg 渲染+双色染色+非空像素 无头回归 */
    if (iconSmoke)
    {
        static const char *ic[] = {"glyphicons_21_home", "glyphicons_422_book_library",
                                   "glyphicons_336_folder", "glyphicons_5_settings",
                                   "glyphicons_73_bookmark", "glyphicons_28_search",
                                   "glyphicons_498_more_vertical",
                                   "glyphicons_589_book_open"};
        const bool have = hr::Icons::have();
        int okN = 0;
        for (const char *n : ic)
        {
            const QImage dark = hr::Icons::pix(QString::fromLatin1(n),
                                               QColor(0xC8, 0xC8, 0xC8), 22).toImage();
            const QImage light = hr::Icons::pix(QString::fromLatin1(n),
                                                QColor(0x5D, 0x66, 0x78), 22).toImage();
            int solid = 0;
            for (int y = 0; y < dark.height(); y++)
                for (int x = 0; x < dark.width(); x++)
                    if (QColor::fromRgba(dark.pixel(x, y)).alpha() > 40) solid++;
            const bool ok = have && !dark.isNull() && !light.isNull() && solid > 8;
            evt("icon_smoke", QString("%1 solid=%2 ok=%3").arg(n).arg(solid).arg(ok ? 1 : 0));
            if (ok) okN += 1;
        }
        fprintf(stderr, "ICONSMOKE have=%d ok=%d total=%d\n", (int)have, okN,
                (int)(sizeof(ic) / sizeof(ic[0])));
        hr_qt_shutdown();
        return okN == 8 ? 0 : 5;
    }

    /* v0.79.0 V4 --themesmoke：双色板/主题色槽位无头回归（apply 不写 ui/dark；accent 改动结束即恢复） */
    if (themeSmoke)
    {
        QSettings stT(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
        const QString keepAcc = stT.value("ui/accentColor", "").toString();
        hr::UiTheme::apply(true);
        const bool d1 = qApp->palette().color(QPalette::Window) == QColor(0x17, 0x1A, 0x21);
        hr::UiTheme::apply(false);
        const bool l1 = qApp->palette().color(QPalette::Window) == QColor(0xF3, 0xF5, 0xF9);
        hr::UiTheme::setAccent(QColor());
        const bool a0 = qApp->palette().color(QPalette::Highlight) == QColor(0x2E, 0x6B, 0xD6);
        hr::UiTheme::setAccent(QColor(0x39, 0x49, 0xAB));
        const bool a1 = qApp->palette().color(QPalette::Highlight) == QColor(0x39, 0x49, 0xAB) &&
                        qApp->palette().color(QPalette::Link) == QColor(0x39, 0x49, 0xAB);
        hr::UiTheme::setAccent(keepAcc.isEmpty() ? QColor() : QColor(keepAcc));
        const bool a2 = keepAcc.isEmpty()
                            ? qApp->palette().color(QPalette::Highlight) == QColor(0x2E, 0x6B, 0xD6)
                            : true;
        evt("theme_smoke", QString("dark=%1 light=%2 acc=%3%4%5").arg(d1).arg(l1).arg(a0).arg(a1).arg(a2));
        fprintf(stderr, "THEMESMOKE dark=%d light=%d acc=%d%d%d\n", (int)d1, (int)l1, (int)a0,
                (int)a1, (int)a2);
        hr_qt_shutdown();
        return (d1 && l1 && a0 && a1 && a2) ? 0 : 5;
    }

    ReaderWindow win;
    if (selSmoke)
    {
        /* 划词等价回归：页中带 2/3 高度横带 → 选中文字；并全本搜索"的" */
        win.setSmokeViewport(900, 1200);
        win.forcePageMode();
        if (!win.openBook(book, 0))
        {
            evt("selsmoke_fail", "open");
            return 3;
        }
        void *doc = win.hrDoc();
        /* 前 4 页取首个有文字者（EPUB 首页常为封面图） */
        QString sel;
        int rn = 0, rc = -1, selPage = -1, hits = 0;
        for (int p = 0; p < 12 && p < win.pageCount(); p++)
        {
            float box[4] = {0, 0, 0, 0};
            hr_qt_page_box(doc, p, box);
            char *text = NULL;
            float *rects = NULL;
            int r = 0;
            const float y0 = box[1] + (box[3] - box[1]) * 0.30f;
            const float y1 = box[1] + (box[3] - box[1]) * 0.70f;
            rc = hr_qt_selection(doc, p, box[0] + 1, y0, box[2] - 1, y1, &text, &rects, &r);
            const QString s = QString::fromUtf8(text ? text : "");
            free(text);
            free(rects);
            if (!s.trimmed().isEmpty())
            {
                sel = s; rn = r; selPage = p;
                hits = hr_qt_search_page(doc, p, "\xe7\x9a\x84"); /* "的" */
                break;
            }
        }
        (void)selPage;
        /* PDF：把选区矩形写为高亮注解另存副本并回读注解数 */
        int pdfOk = -1, annCnt = -1;
        if (book.endsWith(".pdf", Qt::CaseInsensitive) && rn > 0)
        {
            QVector<float> quads;
            float box2[4] = {0, 0, 0, 0};
            hr_qt_page_box(doc, 0, box2);
            char *t2 = NULL;
            float *r2 = NULL;
            int r2n = 0;
            const float ya = box2[1] + (box2[3] - box2[1]) * 0.30f;
            const float yb = box2[1] + (box2[3] - box2[1]) * 0.70f;
            if (hr_qt_selection(doc, 0, box2[0] + 1, ya, box2[2] - 1, yb, &t2, &r2, &r2n) == 0 &&
                r2n > 0)
            {
                for (int k = 0; k < r2n; k++)
                    quads << r2[k * 4 + 0] << r2[k * 4 + 1] << r2[k * 4 + 2] << r2[k * 4 + 3];
            }
            free(t2);
            free(r2);
            if (!quads.isEmpty())
            {
                const float *qp = quads.constData();
                const int qn = quads.size() / 4;
                const int pg = 0;
                const int argb = 0xFFFF00;
                const QString dest = book + ".annot.pdf";
                pdfOk = hr_qt_pdf_export_highlights(doc, dest.toUtf8().constData(),
                                                    &pg, &argb, &qp, &qn, 1);
                if (pdfOk)
                {
                    void *doc2 = hr_qt_open(book.toUtf8().constData());
                    if (doc2)
                    {
                        annCnt = hr_qt_pdf_annotation_count(doc2, 0);
                        hr_qt_close(doc2);
                    }
                }
            }
        }
        evt("selsmoke_done",
            QString("rc=%1 len=%2 rects=%3 hits=%4 pdf=%5 ann=%6")
                .arg(rc).arg(sel.size()).arg(rn).arg(hits).arg(pdfOk).arg(annCnt));
        fprintf(stderr, "SELSMOKE rc=%d len=%d rects=%d hits=%d pdf=%d ann=%d\n",
                rc, sel.size(), rn, hits, pdfOk, annCnt);
        printf("SELTEXT=%s\n", sel.left(120).toUtf8().constData());
        QThreadPool::globalInstance()->waitForDone();
        hr_qt_shutdown();
        return (rc == 0 && (sel.size() > 0 || hits > 0)) ? 0 : 9;
    }
    if (ttsSmoke)
    {
        /* TTS：连读链路（SAPI 异步 Speak + 停止）；WAV 导出见报告已知问题 */
        if (!hr::Tts::available())
        {
            evt("ttssmoke_fail", "sapi unavailable");
            return 10;
        }
        const bool sp = hr::Tts::speakAsync("好好读桌面版，语音连读冒烟测试。");
        QThread::msleep(1200);
        hr::Tts::stop();
        /* 批次三：WAV 导出验证（TEMP 中转） */
        QString werr;
        const QString wtmp = QDir::temp().absoluteFilePath("hr_tts_smoke.wav");
        QFile::remove(wtmp);
        const bool wav = hr::Tts::exportWav(QStringList() << "桌面版语音导出冒烟测试。", wtmp, &werr);
        const qint64 wsz = QFileInfo(wtmp).exists() ? QFileInfo(wtmp).size() : 0;
        if (wsz > 0) QFile::remove(wtmp);
        evt("ttssmoke_wav", QString("ok=%1 size=%2 err=%3").arg(wav).arg(wsz).arg(werr.left(60)));
        fprintf(stderr, "TTSSMOKE speak=%d wav=%d size=%lld err=%s\n", (int)sp, (int)wav,
                (long long)wsz, werr.toUtf8().constData());
        evt("ttssmoke_done", QString("speak=%1 wav=%2").arg(sp).arg(wav));
        hr_qt_shutdown();
        return (sp && wav) ? 0 : 10;
    }
    if (aiSmoke)
    {
        const hr::AiConfig c = hr::AiConfig::load();
        QString err;
        const QString ans = hr::aiAsk(c, "请只回答一个字：一加一等于几？", &err, 16);
        /* U6：模型列表拉取探针（GET {base}/models） */
        QString merr;
        const QStringList models = hr::aiModels(c, &merr);
        evt("aismoke_done", QString("ok=%1 len=%2 err=%3 models=%4 merr=%5")
                                .arg(ans.isEmpty() ? 0 : 1).arg(ans.size()).arg(err.left(60))
                                .arg(models.size()).arg(merr.left(40)));
        fprintf(stderr, "AISMOKE ok=%d ans=%s\n", (int)!ans.isEmpty(),
                ans.left(40).toUtf8().constData());
        return ans.isEmpty() ? 11 : 0;
    }
    if (homeHook)
    {
        /* U7 无头探针：首页数据装配（进度/珍藏/书签/统计）+ 清空往返 */
        hr::Db::open();
        const qint64 now = QDateTime::currentMSecsSinceEpoch();
        const QString p1 = hr::dataDir() + QString::fromLatin1("/probe_book_a.epub");
        const QString p2 = hr::dataDir() + QString::fromLatin1("/probe_book_b.pdf");
        hr::Db::upsertFileMeta(p1, QString::fromLatin1("Probe A"), 120, 12345, now);
        hr::Db::upsertFileMeta(p2, QString::fromLatin1("Probe B"), 200, 54321, now - 1000);
        hr::Db::saveProgressRaw(p1, 4, 120, 1000, now);
        hr::Db::saveProgressRaw(p2, 1, 200, 1000, now - 60000);
        hr::Db::setFavorite(p1, true);
        hr::Db::setFavorite(p2, true);
        hr::Db::addBookmarkRaw(p1, 3, QString::fromLatin1("probe label"),
                               QString::fromLatin1("probe note"), now);
        hr::Db::addBookmarkRaw(p1, 9, QString(), QString(), now - 1000);
        hr::Stats::addReading(QString::fromLatin1("Probe A"), 12, 5);
        hr::Stats::addReading(QString::fromLatin1("Probe B"), 3, 2);
        /* 读回（同首页装配路径） */
        const QList<hr::Db::ProgressRow> prog = hr::Db::allProgress();
        const int favN = hr::Db::listFavorites().size();
        const int bmN = hr::Db::allBookmarks().size();
        const auto days = hr::Stats::rows(14);
        int tm = 0;
        for (const auto &d : days) tm += d.minutes;
        const int booksN = hr::Stats::totalBooks();
        evt(QString::fromLatin1("home_hook"),
            QString::fromLatin1("recent=%1 fav=%2 bm=%3 statmin=%4 books=%5")
                .arg(prog.size()).arg(favN).arg(bmN).arg(tm).arg(booksN));
        hr::Stats::clearAll();
        evt(QString::fromLatin1("home_hook_clear"),
            QString::fromLatin1("rows=%1").arg(hr::Stats::rows(14).size()));
        /* U8：密码锁哈希函数探针（SHA-256 hex=64 位） */
        const QString sha = QString::fromLatin1(
            QCryptographicHash::hash(QByteArray("1234"), QCryptographicHash::Sha256)
                .toHex());
        evt(QString::fromLatin1("u8_sha"), QString::fromLatin1("len=%1").arg(sha.size()));
        fprintf(stderr, "HOMEHOOK recent=%d fav=%d bm=%d statmin=%d books=%d\n",
                prog.size(), favN, bmN, tm, booksN);
        /* 探针自清（不污染数据目录书库） */
        hr::Db::deleteMeta(p1);
        hr::Db::deleteMeta(p2);
        hr_qt_shutdown();
        return (prog.size() == 2 && favN == 2 && bmN == 2 && tm == 7 && booksN == 2) ? 0 : 12;
    }

    if (smbHook)
    {
        /* SMB 无头探针：连接/列目/stat/readRange（50.104 testbooks，不落数据库） */
        hr::SmbClient smb("192.168.50.104:445", "testbooks", "howread", "howread123");
        if (!smb.ok())
        {
            evt("smb_hook", QString("fail connect: %1").arg(smb.error().left(60)));
            fprintf(stderr, "SMBHOOK connect fail: %s\n",
                    smb.error().toUtf8().constData());
            hr_qt_shutdown();
            return 14;
        }
        const QList<hr::SmbEntry> es = smb.list("", nullptr);
        QString err;
        const long long sz = smb.statSize("book_epub.epub", &err);
        const QByteArray head = smb.readRange("book_epub.epub", 0, 64, &err);
        evt("smb_hook", QString("n=%1 size=%2 magic=%3 err=%4")
                            .arg(es.size()).arg(sz)
                            .arg(QString::fromLatin1(head.left(2).toHex()))
                            .arg(err.left(30)));
        fprintf(stderr, "SMBHOOK n=%d size=%lld magic=%s\n", es.size(), (long long)sz,
                head.left(2).toHex().constData());
        hr_qt_shutdown();
        return (es.size() > 0 && sz > 0 && head.left(2) == "PK") ? 0 : 14;
    }

    if (annHook || annSeed)
    {
        /* U-ann 无头探针：annotation 表往返（add/list/delete）；--annseed <书路径>
         * 额外种一条 page0 真实页数高亮供 GUI 叠加验证 */
        hr::Db::open();
        const QString seedPath =
            annArg.isEmpty() ? hr::dataDir() + QString::fromLatin1("/probe_ann.pdf") : annArg;
        int pc = 120;
        if (!annArg.isEmpty())
        {
            void *doc = hr_qt_open(hr::openablePath(annArg).toUtf8().constData());
            if (doc)
            {
                pc = hr_qt_count(doc);
                hr_qt_close(doc);
            }
        }
        const long long id = hr::Db::addAnnotation(
            seedPath, 2, 0xFFFFFF00, QString::fromLatin1("10.0,20.0,110.0,40.0;"),
            QString::fromLatin1("probe highlight"), pc);
        const QList<hr::Db::AnnotationRow> cur = hr::Db::listAnnotations(seedPath, 2);
        const QList<hr::Db::AnnotationRow> all = hr::Db::listAnnotations(seedPath, -1);
        hr::Db::deleteAnnotation(id);
        const QList<hr::Db::AnnotationRow> after = hr::Db::listAnnotations(seedPath, -1);
        evt("ann_hook", QString("add=%1 cur=%2 all=%3 after=%4")
                            .arg(id > 0 ? 1 : 0).arg(cur.size()).arg(all.size())
                            .arg(after.size()));
        fprintf(stderr, "ANNHOOK add=%lld cur=%d all=%d after=%d pc=%d\n",
                (long long)id, cur.size(), all.size(), after.size(), pc);
        if (annSeed)
        {
            hr::Db::addAnnotation(annArg, 0, 0xFFFFEB3B,
                                  QString::fromLatin1("80.0,60.0,420.0,95.0;"),
                                  QString::fromLatin1("probe highlight"), pc);
            evt("ann_seed", QString("path=%1 pc=%2").arg(annArg.left(60)).arg(pc));
        }
        hr_qt_shutdown();
        const bool pass =
            id > 0 && cur.size() == 1 && all.size() == 1 && after.size() == 0;
        return pass ? 0 : 15;
    }
    if (statsSmoke)
    {
        /* U0 修复#14：冒烟此前直写真实 stats.json——先快照后还原，零污染 */
        const QString statsPath = hr::dataDir() + "/stats.json";
        const QByteArray statsBak =
            QFile::exists(statsPath) ? QFile(statsPath).readAll() : QByteArray();
        hr::Stats::addReading("book_epub.epub", 3, 2);
        hr::Stats::addReading("book_pdf.pdf", 1, 1);
        const auto rr = hr::Stats::rows(7);
        int tm = 0, tp = 0;
        for (const auto &d : rr) { tm += d.minutes; tp += d.pages; }
        const int tb = hr::Stats::totalBooks();
        if (statsBak.isEmpty()) QFile::remove(statsPath);
        else
        {
            QFile wf(statsPath);
            wf.open(QIODevice::WriteOnly | QIODevice::Truncate);
            wf.write(statsBak);
        }
        evt("statssmoke_done", QString("days=%1 min=%2 pages=%3 books=%4")
                                   .arg(rr.size()).arg(tm).arg(tp).arg(tb));
        fprintf(stderr, "STATSSMOKE days=%d min=%d pages=%d books=%d\n", rr.size(), tm, tp, tb);
        hr_qt_shutdown();
        return (tm >= 3 && tp >= 4 && tb >= 2) ? 0 : 14;
    }
    if (coverSmoke)
    {
        if (!libDir.isEmpty()) Db::addScanDir(libDir);
        LibraryWindow lib;
        lib.rescan();
        int waited = 0;
        while (lib.scanning() && waited < 240000)
        {
            app.processEvents();
            QThread::msleep(40);
            waited += 40;
        }
        app.processEvents();
        const int n = lib.coversGenSync(5);
        evt("coversmoke_exit", QString("covers=%1").arg(n));
        fprintf(stderr, "COVERSMOKE covers=%d\n", n);
        QThreadPool::globalInstance()->waitForDone();
        hr_qt_shutdown();
        return n > 0 ? 0 : 15;
    }

    if (sshSmoke)
    {
#ifdef HR_HAVE_LIBSSH2
        /* SFTP 等价冒烟：list + stat + Range 探针 + 块缓存流式打开渲染 p1 */
        const QString hp = book;
        const QString host = hp.section(':', 0, 0);
        const int port = hp.contains(':') ? hp.section(':', 1, 1).toInt() : 22;
        qint64 t0 = QDateTime::currentMSecsSinceEpoch();
        auto ssh = std::make_shared<hr::SshClient>(host, port, remoteArg2, remoteArg3);
        if (!ssh->ok())
        {
            evt("sshsmoke_fail", "connect: " + ssh->error().left(100));
            fprintf(stderr, "SSHSMOKE connect_fail %s\n", ssh->error().toUtf8().constData());
            hr_qt_shutdown();
            return 12;
        }
        QString le;
        const QList<hr::SshEntry> es = ssh->list(sshDir, &le);
        evt("ssh_list", QString("n=%1 err=%2").arg(es.size()).arg(le.left(60)));
        fprintf(stderr, "SSH_LIST n=%d err=%s\n", es.size(), le.toUtf8().constData());
        QString rel, name;
        long long size = -1;
        for (const hr::SshEntry &e2 : es)
        {
            if (e2.isDir) continue;
            if (e2.name == "book_epub.epub" || rel.isEmpty())
            {
                rel = e2.relPath;
                name = e2.name;
                size = e2.size;
                if (e2.name == "book_epub.epub") break;
            }
        }
        if (rel.isEmpty())
        {
            evt("sshsmoke_fail", "no book");
            hr_qt_shutdown();
            return 12;
        }
        const long long sz = ssh->statSize(rel, &le);
        const QByteArray head = ssh->readRange(rel, 0, 64, &le);
        evt("ssh_range", QString("stat=%1 head=%2 err=%3")
                             .arg(sz).arg(QString::fromLatin1(head.left(4).toHex())).arg(le.left(40)));
        fprintf(stderr, "SSH_RANGE stat=%lld head=%s\n", sz, QString::fromLatin1(head.left(4).toHex()).toUtf8().constData());
        if (sz <= 0 || !head.startsWith("PK"))
        {
            evt("sshsmoke_fail", "range probe");
            hr_qt_shutdown();
            return 12;
        }
        const QString ext = QFileInfo(name).suffix().toLower();
        const int blockSize = (ext == "pdf" || ext == "cbz" || ext == "xps") ? 64 * 1024 : 1024 * 1024;
        const QString key = "ssmoke-" + QString::fromLatin1(
            QCryptographicHash::hash(rel.toUtf8(), QCryptographicHash::Md5).toHex());
        auto fp = std::make_shared<hr::FetchPolicy>();
        auto fetch = [ssh, fp, rel](long long pos, int len) -> QByteArray
        { return fp->fetch([ssh, rel](long long p, int l) { return ssh->readRange(rel, p, l); },
                           pos, len, "sftp"); };
        const int fillThreads =
            qBound(1, QSettings(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat)
                              .value(QString::fromLatin1("net/fillThreads"), 4)
                              .toInt(), 8);
        hr::BlockCacheStore session(key, sz, blockSize, fetch, 32LL * 1024 * 1024, fillThreads);
        /* U4：远程块缓存磁盘上限（cache/capMB，默认 500MB，LRU 整目录裁剪） */
        hr::BlockCacheStore::enforceCap(
            QSettings(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat)
                .value("cache/capMB", 500)
                .toInt(),
            hr::dataDir() + "/cache");
        qint64 t1 = QDateTime::currentMSecsSinceEpoch();
        { /* v0.111.0：引擎 open 前预取头/尾块 */
            const bool tailFmt = (ext == "pdf" || ext == "cbz" || ext == "xps" ||
                                  ext == "djvu" || ext == "epub" || ext == "zip");
            const int tailBlk = tailFmt ? (blockSize <= 64 * 1024 ? 2 : 1) : 0;
            session.prefillEnds(2, tailBlk);
        }
        win.setSmokeViewport(900, 1200);
        win.forcePageMode();
        if (!win.adoptDoc(hr_qt_open_stream(ext.toUtf8().constData(), sz, &hrCacheReadCb, &session),
                          name, nullptr, -1))
        {
            evt("sshsmoke_fail", "open");
            QThreadPool::globalInstance()->waitForDone();
            hr_qt_shutdown();
            return 12;
        }
        QImage img = win.currentPageImage();
        const bool ok = !img.isNull() && img.save(outPng);
        win.flushProgress();
        QThreadPool::globalInstance()->waitForDone();
        evt("sshsmoke_done", QString("list=%1 open_ms=%2 render=%3")
                                 .arg(es.size()).arg(t1 - t0).arg(ok ? 1 : 0));
        fprintf(stderr, "SSHSMOKE_OK list=%d ms=%lld png=%d\n", es.size(),
                (long long)(t1 - t0), (int)ok);
        hr_qt_shutdown();
        return ok ? 0 : 12;
#else
        (void)sshDir;
        evt("sshsmoke_fail", "built without libssh2");
        fprintf(stderr, "SSHSMOKE no_libssh2\n");
        hr_qt_shutdown();
        return 12;
#endif
    }
    if (opdsSmoke)
    {
        /* OPDS 冒烟：feed 解析（本地 xml 或 URL）+ 搜索模板发现 + acquisition Range 探针 */
        const QString opU = remoteArg2 == "-" ? QString() : remoteArg2;
        const QString opP = remoteArg3 == "-" ? QString() : remoteArg3;
        hr::OpdsClient op(book, opU, opP);
        QList<hr::OpdsEntry> es;
        QString e;
        if (QFileInfo(book).isFile())
        {
            QFile f(book);
            f.open(QIODevice::ReadOnly);
            const bool okp = op.parseFeedData(f.readAll(), "http://feed.local/", &es, &e);
            evt("opds_feed_local", QString("ok=%1 n=%2 err=%3")
                                       .arg(okp ? 1 : 0).arg(es.size()).arg(e.left(60)));
            fprintf(stderr, "OPDS_LOCAL ok=%d n=%d err=%s\n", (int)okp, es.size(),
                    e.toUtf8().constData());
        }
        else
        {
            es = op.list(QString(), &e);
            evt("opds_feed", QString("n=%1 err=%2 tpl=%3")
                                 .arg(es.size()).arg(e.left(60))
                                 .arg(op.searchTemplate().isEmpty() ? 0 : 1));
            fprintf(stderr, "OPDS_LIST n=%d err=%s tpl=%d\n", es.size(), e.toUtf8().constData(),
                    (int)!op.searchTemplate().isEmpty());
        }
        int probe = 0;
        for (const hr::OpdsEntry &e2 : es)
        {
            if (e2.isDir || e2.url.isEmpty()) continue;
            const QByteArray head = op.fetchBytes(e2.url); /* 连通性探针：普通 GET（Range 拉块另要求服务器支持） */
            probe = head.startsWith("PK") ? 2 : (head.isEmpty() ? 0 : 1);
            evt("opds_probe", QString("url=%1 head=%2")
                                  .arg(e2.url.left(80)).arg(QString::fromLatin1(head.left(4).toHex())));
            fprintf(stderr, "OPDS_PROBE %s head=%s\n", e2.url.left(70).toUtf8().constData(),
                    QString::fromLatin1(head.left(4).toHex()).toUtf8().constData());
            break;
        }
        evt("opdsmoke_done", QString("entries=%1 probe=%2").arg(es.size()).arg(probe));
        fprintf(stderr, "OPDSMOKE_DONE entries=%d probe=%d\n", es.size(), probe);
        hr_qt_shutdown();
        return es.isEmpty() ? 13 : 0;
    }
    if (remoteSmoke)
    {
        /* 远程流式打开冒烟：WebDAV Range + 块缓存 + fz_stream → 渲染对比 */
        const QString url = book; /* 书的完整 URL */
        hr::WebDavClient dav(QFileInfo(url).path(), remoteArg2, remoteArg3);
        const long long size = dav.contentLength(url);
        if (size <= 0)
        {
            evt("remotesmoke_fail", "no size");
            return 7;
        }
        const QString name = QFileInfo(url).fileName();
        const QString ext = QFileInfo(name).suffix().toLower();
        const QString key = "smoke-" + QString::fromLatin1(
            QCryptographicHash::hash(url.toUtf8(), QCryptographicHash::Md5).toHex());
        const int blockSize = (ext == "pdf" || ext == "cbz" || ext == "xps") ? 64 * 1024
                                                                             : 1024 * 1024;
        auto fetch = [&dav, url](long long pos, int len) -> QByteArray
        { return dav.getRange(url, pos, len); };
        /* 首块探针：直见 Qt 侧拿到的字节（curl Range 已证 206 正常） */
        {
            const QByteArray probe = fetch(0, 64);
            evt("remotesmoke_probe", QString::fromLatin1(probe.toHex()) +
                                         " size=" + QString::number(probe.size()));
        }
        const int fillThreads =
            qBound(1, QSettings(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat)
                              .value(QString::fromLatin1("net/fillThreads"), 4)
                              .toInt(), 8);
        hr::BlockCacheStore session(key, size, blockSize, fetch, 32LL * 1024 * 1024, fillThreads);
        /* U4：同上（GUI 远程打开会话） */
        hr::BlockCacheStore::enforceCap(
            QSettings(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat)
                .value("cache/capMB", 500)
                .toInt(),
            hr::dataDir() + "/cache");
        { /* v0.111.0：引擎 open 前预取头/尾块 */
            const bool tailFmt = (ext == "pdf" || ext == "cbz" || ext == "xps" ||
                                  ext == "djvu" || ext == "epub" || ext == "zip");
            const int tailBlk = tailFmt ? (blockSize <= 64 * 1024 ? 2 : 1) : 0;
            session.prefillEnds(2, tailBlk);
        }
        qint64 t0 = QDateTime::currentMSecsSinceEpoch();
        void *doc = hr_qt_open_stream(ext.toUtf8().constData(), size, &hrCacheReadCb, &session);
        if (!doc)
        {
            evt("remotesmoke_fail", QString::fromUtf8(hr_qt_last_error()).left(120));
            return 7;
        }
        qint64 t1 = QDateTime::currentMSecsSinceEpoch();
        win.setSmokeViewport(900, 1200);
        win.forcePageMode();
        if (!win.adoptDoc(doc, name, nullptr, -1))
        {
            evt("remotesmoke_fail", "adopt");
            return 7;
        }
        QImage img = win.currentPageImage();
        if (img.isNull() || !img.save(outPng))
        {
            evt("remotesmoke_fail", "save");
            return 7;
        }
        evt("remotesmoke_done", QString("name=%1 size=%2 pages=%3 open_ms=%4 cache=%5")
                                    .arg(name).arg(size).arg(win.pageCount()).arg(t1 - t0)
                                    .arg(session.fullyCached()));
        /* 缺陷#4：登记 remote:// 元数据 + 生成远程封面（headless 证据链） */
        {
            const QString rpath = QString::fromLatin1("remote://probe/") + name;
            hr::Db::open();
            hr::Db::upsertFileMeta(rpath, name, win.pageCount(), size,
                                   QDateTime::currentMSecsSinceEpoch());
            const QString cpng = hr::dataDir() + "/cache/covers/r-" +
                QString::fromLatin1(QCryptographicHash::hash(rpath.toUtf8(),
                                                             QCryptographicHash::Md5)
                                        .toHex().left(12)) + ".png";
            QDir().mkpath(QFileInfo(cpng).absolutePath());
            const bool cok =
                img.scaledToWidth(220, Qt::SmoothTransformation).save(cpng);
            evt("cover_remote", QString("key=%1 ok=%2").arg(rpath.left(60)).arg(cok ? 1 : 0));
            fprintf(stderr, "REMOTESMOKE meta=1 cover=%d\n", (int)cok);
        }
        fprintf(stderr, "REMOTESMOKE_OK name=%s pages=%d open_ms=%lld fully=%d\n",
                name.toUtf8().constData(), win.pageCount(), (long long)(t1 - t0),
                (int)session.fullyCached());
        QThreadPool::globalInstance()->waitForDone();
        hr_qt_shutdown();
        return 0;
    }

    if (smbSmoke)
    {
        /* SMB 流式打开冒烟：libsmb2 Range 读 + 块缓存 + fz_stream → 渲染对比 */
        hr::SmbClient smb(smbArg1, smbArg2, remoteArg2, remoteArg3);
        if (!smb.ok())
        {
            evt("smbsmoke_fail", smb.error().left(100));
            fprintf(stderr, "SMBSMOKE connect fail: %s\n", smb.error().toUtf8().constData());
            hr_qt_shutdown();
            return 13;
        }
        const QString file = smbArg4;
        QString err;
        const long long size = smb.statSize(file, &err);
        if (size <= 0)
        {
            evt("smbsmoke_fail", "size " + err.left(60));
            fprintf(stderr, "SMBSMOKE stat fail: %s\n", err.toUtf8().constData());
            hr_qt_shutdown();
            return 13;
        }
        const QString name = QFileInfo(file).fileName();
        const QString ext = QFileInfo(name).suffix().toLower();
        const QString key = "smbsmoke-" + QString::fromLatin1(
            QCryptographicHash::hash((smbArg1 + "/" + smbArg2 + "/" + file).toUtf8(),
                                     QCryptographicHash::Md5).toHex());
        const int blockSize = (ext == "pdf" || ext == "cbz" || ext == "xps") ? 64 * 1024
                                                                             : 1024 * 1024;
        auto fetch = [&smb, file](long long pos, int len) -> QByteArray
        { return smb.readRange(file, pos, len); };
        const int fillThreads =
            qBound(1, QSettings(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat)
                              .value(QString::fromLatin1("net/fillThreads"), 4)
                              .toInt(), 8);
        hr::BlockCacheStore session(key, size, blockSize, fetch, 32LL * 1024 * 1024, fillThreads);
        hr::BlockCacheStore::enforceCap(
            QSettings(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat)
                .value("cache/capMB", 500)
                .toInt(),
            hr::dataDir() + "/cache");
        { /* v0.111.0：引擎 open 前预取头/尾块 */
            const bool tailFmt = (ext == "pdf" || ext == "cbz" || ext == "xps" ||
                                  ext == "djvu" || ext == "epub" || ext == "zip");
            const int tailBlk = tailFmt ? (blockSize <= 64 * 1024 ? 2 : 1) : 0;
            session.prefillEnds(2, tailBlk);
        }
        qint64 t0 = QDateTime::currentMSecsSinceEpoch();
        void *doc = hr_qt_open_stream(ext.toUtf8().constData(), size, &hrCacheReadCb, &session);
        if (!doc)
        {
            evt("smbsmoke_fail", QString::fromUtf8(hr_qt_last_error()).left(120));
            fprintf(stderr, "SMBSMOKE open fail: %s\n", hr_qt_last_error());
            QThreadPool::globalInstance()->waitForDone();
            hr_qt_shutdown();
            return 13;
        }
        qint64 t1 = QDateTime::currentMSecsSinceEpoch();
        win.setSmokeViewport(900, 1200);
        win.forcePageMode();
        if (!win.adoptDoc(doc, name, nullptr, -1))
        {
            evt("smbsmoke_fail", "adopt");
            QThreadPool::globalInstance()->waitForDone();
            hr_qt_shutdown();
            return 13;
        }
        QImage img = win.currentPageImage();
        if (img.isNull() || !img.save(outPng))
        {
            evt("smbsmoke_fail", "save");
            QThreadPool::globalInstance()->waitForDone();
            hr_qt_shutdown();
            return 13;
        }
        evt("smbsmoke_done", QString("name=%1 size=%2 pages=%3 open_ms=%4 cache=%5")
                                 .arg(name).arg(size).arg(win.pageCount()).arg(t1 - t0)
                                 .arg(session.fullyCached()));
        fprintf(stderr, "SMBSMOKE_OK name=%s pages=%d open_ms=%lld fully=%d\n",
                name.toUtf8().constData(), win.pageCount(), (long long)(t1 - t0),
                (int)session.fullyCached());
        QThreadPool::globalInstance()->waitForDone();
        hr_qt_shutdown();
        return 0;
    }
    if (syncSmoke)
    {
        const hr::SyncResult r = hr::androidSync(book, syncUser, syncPass, remoteArg2,
                                                 remoteArg3.isEmpty() ? "newer" : remoteArg3);
        fprintf(stderr, "SYNCSMOKE ok=%d pDown=%d pUp=%d bDown=%d synced=%d ms=%lld err=%s\n",
                (int)r.ok, r.progressDown, r.progressUp, r.bookmarksDown, r.booksSynced,
                (long long)r.durationMs, r.error.toUtf8().constData());
        QThreadPool::globalInstance()->waitForDone();
        hr_qt_shutdown();
        return r.ok ? 0 : 8;
    }
    if (!book.isEmpty())
    {
        if (smoke)
        {
            win.setSmokeViewport(900, 1200);
            win.forcePageMode(); /* 同步渲染，位图立即可取 */
        }
        /* U4：外部打开自动入库（reader/autoAdd，默认关；无头 smoke 不入库） */
        if (!smoke &&
            QSettings(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat)
                .value("reader/autoAdd", false)
                .toBool() &&
            QFileInfo::exists(book) && !book.startsWith("remote://"))
        {
            bool known = false;
            for (const hr::Db::MetaRow &r : hr::Db::listLibrary())
                if (r.path == book) { known = true; break; }
            if (!known)
            {
                void *d = hr_qt_open(hr::openablePath(book).toUtf8().constData());
                if (d)
                {
                    const int pages = hr_qt_count(d);
                    if (pages > 0)
                    {
                        hr::Db::upsertFileMeta(
                            book, QString::fromUtf8(hr_qt_meta(d, 0)), pages,
                            QFileInfo(book).size(),
                            QFileInfo(book).lastModified().toMSecsSinceEpoch());
                        hr::evt("auto_add", "path=" + book.left(90));
                    }
                    hr_qt_close(d);
                }
            }
        }
        if (asyncSmoke)
        {
            /* v0.103.0 P0-1：后台打开路径无头回归（worker 打开/排版→UI 装配→首渲全链） */
            win.setSmokeViewport(900, 1200);
            if (!win.openBookAsync(book, -1))
            {
                evt("smoke_fail", "open_async_busy");
                return 3;
            }
            const qint64 aw = QDateTime::currentMSecsSinceEpoch();
            while (win.pageCount() <= 0 && !win.asyncFailed() &&
                   QDateTime::currentMSecsSinceEpoch() - aw < 30000)
                QCoreApplication::processEvents();
            evt("smoke_async_open", QString("pages=%1 ms=%2 failed=%3")
                                        .arg(win.pageCount())
                                        .arg(QDateTime::currentMSecsSinceEpoch() - aw)
                                        .arg((int)win.asyncFailed()));
            while (win.currentPageImage().isNull() && !win.asyncFailed() &&
                   QDateTime::currentMSecsSinceEpoch() - aw < 40000)
                QCoreApplication::processEvents();
        }
        else if (!win.openBook(book, page))
        {
            evt("smoke_fail", "open");
            return 3;
        }
    }

    if (smoke)
    {
        if (book.isEmpty() || win.pageCount() <= 0)
        {
            evt("smoke_fail", "no_book");
            return 3;
        }
        QImage img = win.currentPageImage();
        if (img.isNull() || !img.save(outPng))
        {
            evt("smoke_fail", "save");
            return 4;
        }
        evt("smoke_done", QString("page=%1/%2 png=%3")
                              .arg(win.currentPage() + 1).arg(win.pageCount())
                              .arg(QFileInfo(outPng).fileName()));
        /* Db 往返：进度落盘读回 + 书签加/列/删（与 Java 版同 howread.db 同 schema） */
        win.flushProgress();
        int sp = 0, spc = 0, svw = 0;
        bool has = Db::loadProgress(book, &sp, &spc, &svw);
        evt("smoke_progress", QString("has=%1 page=%2/%3 vw=%4").arg(has).arg(sp + 1).arg(spc).arg(svw));
        long long bid = Db::addBookmark(book, 0, "smoke", "auto");
        int bmn = Db::listBookmarks(book).size();
        if (!Db::listBookmarks(book).isEmpty()) Db::deleteBookmark(bid);
        evt("smoke_bm", QString("id=%1 list=%2").arg(bid).arg(bmn));
        /* 目录桥接证据（转换层格式经 openablePath） */
        int tocn = 0;
        void *doc2 = hr_qt_open(hr::openablePath(book).toUtf8().constData());
        if (doc2)
        {
            tocn = hr_qt_outline(doc2, [](void *, int, const char *, int) -> int
                                 { return 1; }, nullptr);
            hr_qt_close(doc2);
        }
        evt("smoke_toc", QString("items=%1").arg(tocn));
        fprintf(stderr, "SMOKE_OK page=%d/%d %dx%d db=%d toc=%d\n", win.currentPage() + 1,
                win.pageCount(), img.width(), img.height(), (int)has, tocn);
        QThreadPool::globalInstance()->waitForDone();
        hr_qt_shutdown();
        return (has && bid > 0 && bmn >= 1) ? 0 : 5;
    }

    if (book.isEmpty())
    {
        if (assoc)
        {
            /* 无头注册表关联（HKCU，免管理员） */
            LibraryWindow lib;
            lib.assocNow();
            evt("assoc_exit", "ok");
            fprintf(stderr, "ASSOC_OK\n");
            QThreadPool::globalInstance()->waitForDone();
            hr_qt_shutdown();
            return 0;
        }
        if (rsHook)
        {
            /* U5 无头探针：remote_source 增改查删 + DPAPI 密码往返 */
            hr::Db::open();
            hr::Db::RemoteSrc r;
            r.protocol = "webdav";
            r.name = "probe";
            r.host = "192.168.50.23";
            r.port = 8765;
            r.path = "/";
            r.user = "howread";
            r.password = "howread123";
            const int id = hr::Db::addRemoteSrc(r);
            const QList<hr::Db::RemoteSrc> a1 = hr::Db::listRemoteSrc();
            hr::Db::RemoteSrc e = a1.last();
            e.name = "probe2";
            const bool up = hr::Db::updateRemoteSrc(e);
            const QList<hr::Db::RemoteSrc> a2 = hr::Db::listRemoteSrc();
            const bool del = hr::Db::deleteRemoteSrc(id);
            const QList<hr::Db::RemoteSrc> a3 = hr::Db::listRemoteSrc();
            const bool passOk =
                !a2.isEmpty() && a2.last().name == "probe2" &&
                a2.last().password == "howread123";
            /* U6：标签 / AI 简介列往返 */
            hr::Db::upsertFileMeta("probe://tag", "probe", 1, 1, 1);
            hr::Db::setTags("probe://tag", "t1,t2");
            const bool tagOk = hr::Db::tagsOf("probe://tag") == QString("t1,t2");
            hr::Db::setDescription("probe://tag", "intro-ok");
            const bool descOk = hr::Db::descriptionOf("probe://tag") == QString("intro-ok");
            fprintf(stderr,
                    "RSHOOK id=%d n1=%d up=%d n2=%d del=%d n3=%d pass=%s tag=%s desc=%s\n",
                    id, a1.size(), up ? 1 : 0, a2.size(), del ? 1 : 0, a3.size(),
                    passOk ? "ok" : "bad", tagOk ? "ok" : "bad", descOk ? "ok" : "bad");
            hr::evt("rshook_done",
                    QString("id=%1 n1=%2 n2=%3 n3=%4 pass=%5 tag=%6 desc=%7")
                        .arg(id)
                        .arg(a1.size())
                        .arg(a2.size())
                        .arg(a3.size())
                        .arg(passOk ? "ok" : "bad")
                        .arg(tagOk ? "ok" : "bad")
                        .arg(descOk ? "ok" : "bad"));
            hr_qt_shutdown();
            return passOk && up && del && a3.isEmpty() && tagOk && descOk ? 0 : 7;
        }
        if (libscan)
        {
            /* 无头书库扫描回归：注入目录→扫描→等完成→报数退出 */
            if (!libDir.isEmpty()) Db::addScanDir(libDir);
            LibraryWindow lib;
            lib.rescan();
            int waited = 0;
            while (lib.scanning() && waited < 240000)
            {
                app.processEvents();
                QThread::msleep(40);
                waited += 40;
            }
            app.processEvents();
            const int n = lib.libCount();
            evt("libscan_exit", QString("count=%1 waited_ms=%2").arg(n).arg(waited));
            fprintf(stderr, "LIBSCAN count=%d\n", n);
            QThreadPool::globalInstance()->waitForDone();
            hr_qt_shutdown();
            return n > 0 ? 0 : 6;
        }
        /* v0.75.0 --embedsmoke <书>：内嵌阅读无头回归（壳内开书→阅读页→返回→书库刷新） */
        if (embedSmoke)
        {
            LibraryWindow lib;
            lib.show();
            app.processEvents();
            lib.openInReader(embedArg, -1);
            int k = 0;
            while (k < 250 && lib.readerPages() <= 0)
            {
                app.processEvents();
                QThread::msleep(40);
                k += 1;
            }
            const bool open = lib.readerActive() && lib.readerIsCurrent();
            const bool chromeHidden = lib.chromeHidden();
            const int pages = lib.readerPages();
            for (int j = 0; j < 15; ++j) { app.processEvents(); QThread::msleep(40); } /* 让布局/重排落定 */
            evt("embed_geom_open", lib.embedGeomInfo());
            lib.grab().save(hr::dataDir() + "/embed_open.png"); /* 自绘截图（非桌面截屏） */
            for (QWidget *tw : QApplication::topLevelWidgets())
                if (tw->isVisible())
                    evt("embed_toplevel", QString("cls=%1 title=%2 geom=%3x%4@%5,%6")
                                              .arg(tw->metaObject()->className())
                                              .arg(tw->windowTitle().left(40))
                                              .arg(tw->width()).arg(tw->height())
                                              .arg(tw->x()).arg(tw->y()));
            evt("embedsmoke_open", QString("ok=%1 chrome=%2 pages=%3 waited_ms=%4")
                                       .arg(open ? 1 : 0).arg(chromeHidden ? 1 : 0)
                                       .arg(pages).arg(k * 40));
            lib.onReaderBack();
            for (int j = 0; j < 25; ++j)
            {
                app.processEvents();
                QThread::msleep(40);
            }
            const bool back = !lib.readerActive();
            const bool shown = lib.chromeShown();
            evt("embedsmoke_back", QString("ok=%1 shown=%2").arg(back ? 1 : 0).arg(shown ? 1 : 0));
            evt("embed_geom_back", lib.embedGeomInfo());
            lib.grab().save(hr::dataDir() + "/embed_back.png");
            fprintf(stderr, "EMBEDSMOKE open=%d chrome_hidden=%d back=%d chrome_shown=%d pages=%d\n",
                    (int)open, (int)chromeHidden, (int)back, (int)shown, pages);
            QThreadPool::globalInstance()->waitForDone();
            hr_qt_shutdown();
            return (open && chromeHidden && back && shown && pages > 0) ? 0 : 5;
        }
        /* v0.85.0 W4 --prefsmoke：偏好页自绘截图（高窗口抓全前两节的二级折叠组） */
        if (prefSmoke)
        {
            LibraryWindow lib;
            lib.resize(1100, 1700);
            lib.show();
            app.processEvents();
            lib.gotoTab(3);
            for (int j = 0; j < 20; ++j) { app.processEvents(); QThread::msleep(40); }
            lib.grab().save(hr::dataDir() + "/prefs_grab.png");
            lib.grabPrefsContent().save(hr::dataDir() + "/prefs_full.png"); /* 整页内容 */
            lib.gotoTab(0); /* v0.86.0 W5 探针：首页整窗截图（底栏铺满/横幅/侧栏序/横滚条） */
            for (int j = 0; j < 20; ++j) { app.processEvents(); QThread::msleep(40); }
            lib.grab().save(hr::dataDir() + "/home_grab.png");
            evt("prefsmoke", "saved=prefs_grab.png");
            fprintf(stderr, "PREFSMOKE ok=1 title=%s\n", lib.windowTitle().toUtf8().constData());
            QThreadPool::globalInstance()->waitForDone();
            hr_qt_shutdown();
            return 0;
        }
        /* v0.87.0 --shotsmoke：七页整窗自绘（首页/书库/我的文件/偏好/网上书库/书签笔记/珍藏） */
        if (shotsSmoke)
        {
            LibraryWindow lib;
            lib.resize(1280, 820);
            lib.show();
            app.processEvents();
            static const char *kPg[7] = {"home", "library", "browse", "prefs", "remote", "bookmarks", "favorites"};
            for (int i = 0; i < 7; ++i)
            {
                lib.gotoTab(i);
                for (int j = 0; j < 25; ++j) { app.processEvents(); QThread::msleep(30); }
                lib.grab().save(hr::dataDir() + QString("/shots_qt_%1.png").arg(QLatin1String(kPg[i])));
            }
            evt("shotsmoke", "done");
            fprintf(stderr, "SHOTSMOKE ok=7\n");
            QThreadPool::globalInstance()->waitForDone();
            hr_qt_shutdown();
            return 0;
        }
        /* v0.96.0 --browse2：“我的文件”页重建 double-free 回归探针 */
        if (browse2Smoke)
        {
            LibraryWindow lib;
            lib.resize(1280, 820);
            lib.show();
            app.processEvents();
            lib.gotoTab(2);
            for (int j = 0; j < 15; ++j) { app.processEvents(); QThread::msleep(30); }
            lib.probeBrowse2();
            for (int j = 0; j < 15; ++j) { app.processEvents(); QThread::msleep(30); }
            fprintf(stderr, "BROWSE2 ok=1\n");
            QThreadPool::globalInstance()->waitForDone();
            hr_qt_shutdown();
            return 0;
        }
        /* v0.99.0 --w20repro：重放 refreshHome/browse 往返 + 对话框关闭路径，
         * 30 轮并发 scanAll，逼出 selectionModel 悬空崩溃（gdb 抓栈用） */
        if (w20Repro)
        {
            LibraryWindow lib;
            lib.resize(1280, 820);
            lib.show();
            app.processEvents();
            for (int round = 0; round < 30; ++round)
            {
                lib.gotoTab(0);
                for (int j = 0; j < 12; ++j)
                {
                    app.processEvents();
                    QCoreApplication::sendPostedEvents(nullptr, QEvent::DeferredDelete); /* W21：模拟真实循环，处理延迟删除 */
                    QThread::msleep(25);
                }
                lib.debugOpenFirstScanDir(); /* 首页“我的文件”圆钮完整路径重放 */
                for (int j = 0; j < 12; ++j)
                {
                    app.processEvents();
                    QCoreApplication::sendPostedEvents(nullptr, QEvent::DeferredDelete);
                    QThread::msleep(25);
                }
                lib.debugBrowseRoundtrip();
                for (int j = 0; j < 12; ++j)
                {
                    app.processEvents();
                    QCoreApplication::sendPostedEvents(nullptr, QEvent::DeferredDelete);
                    QThread::msleep(25);
                }
            }
            evt("w20repro", "rounds=30 survived");
            fprintf(stderr, "W20REPRO ok=1\n");
            QThreadPool::globalInstance()->waitForDone();
            hr_qt_shutdown();
            return 0;
        }
        /* v0.99.0 --w20rescan：清 file_meta 强制引擎逐本重开用户书（取证专用，会重扫全部书） */
        if (w20Rescan)
        {
            LibraryWindow lib;
            lib.resize(1280, 820);
            lib.show();
            app.processEvents();
            lib.debugWipeMetaRescan();
            for (int j = 0; j < 600; ++j) { app.processEvents(); QThread::msleep(50); }
            evt("w20rescan", "done");
            fprintf(stderr, "W20RESCAN ok=1\n");
            QThreadPool::globalInstance()->waitForDone();
            hr_qt_shutdown();
            return 0;
        }
        /* v0.87.0 二轮：书库四视图（列表/简表/书架/按作者分组）自绘 */
        if (shotsViews)
        {
            LibraryWindow lib;
            lib.resize(1280, 820);
            lib.show();
            app.processEvents();
            lib.gotoTab(1);
            for (int j = 0; j < 20; ++j) { app.processEvents(); QThread::msleep(30); }
            static const char *kVw[5] = {"list", "cover", "table", "shelf", "gauthor"};
            for (int i = 0; i < 5; ++i)
            {
                lib.setViewForProbe(QLatin1String(kVw[i]));
                for (int j = 0; j < 25; ++j) { app.processEvents(); QThread::msleep(30); }
                lib.grab().save(hr::dataDir() + QString("/shots_qt_view_%1.png").arg(QLatin1String(kVw[i])));
            }
            evt("shotsviews", "done");
            fprintf(stderr, "SHOTSVIEWS ok=5\n");
            QThreadPool::globalInstance()->waitForDone();
            hr_qt_shutdown();
            return 0;
        }
        /* v0.87.0 二轮：运行时夜间切换（toggleDark=侧栏夜间行同路径）后抓三页 */
        if (shotsDark)
        {
            LibraryWindow lib;
            lib.resize(1280, 820);
            lib.show();
            app.processEvents();
            for (int j = 0; j < 15; ++j) { app.processEvents(); QThread::msleep(30); }
            lib.toggleDarkForProbe();
            for (int j = 0; j < 25; ++j) { app.processEvents(); QThread::msleep(40); }
            static const int kPg[3] = {0, 1, 3};
            static const char *kNm[3] = {"home", "library", "prefs"};
            for (int i = 0; i < 3; ++i)
            {
                lib.gotoTab(kPg[i]);
                for (int j = 0; j < 25; ++j) { app.processEvents(); QThread::msleep(30); }
                lib.grab().save(hr::dataDir() + QString("/shots_qt_dark_%1.png").arg(QLatin1String(kNm[i])));
            }
            evt("shotsdark", "done");
            fprintf(stderr, "SHOTSDARK ok=3\n");
            QThreadPool::globalInstance()->waitForDone();
            hr_qt_shutdown();
            return 0;
        }
        /* 无参启动：书库窗口；双击/打开 → 阅读器嵌为 m_stack 页（v0.75.0 起不再另开窗） */
        LibraryWindow lib;
        QObject::connect(&lib, &LibraryWindow::openRequested, &lib,
                         [&lib](const QString &p) { lib.openInReader(p, -1); });
        QObject::connect(&lib, &LibraryWindow::openAtRequested, &lib,
                         [&lib](const QString &p, int page) { lib.openInReader(p, page); });
        QObject::connect(&lib, &LibraryWindow::openRemoteRequested, &lib,
                         [&lib](void *doc, const QString &name, QObject *keep, const QString &key)
                         { lib.openRemoteInReader(doc, name, keep, key); });

        /* U8：应用密码锁冷启动门（对齐安卓 PasswordActivity：SHA-256 存 security/appLockPassword） */
        {
            const QString lockHash =
                QSettings(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat)
                    .value("security/appLockPassword", QString())
                    .toString();
            if (!lockHash.isEmpty())
            {
                evt("app_lock_gate", "on");
                QDialog gateWin;
                gateWin.setWindowTitle(QString::fromLatin1("HowRead"));
                gateWin.setFixedSize(360, 190);
                gateWin.setWindowFlags(gateWin.windowFlags() &
                                       ~Qt::WindowCloseButtonHint);
                QVBoxLayout *gl = new QVBoxLayout(&gateWin);
                QLabel *gt = new QLabel(QString::fromLatin1("HowRead"), &gateWin);
                QFont gf = gt->font();
                gf.setBold(true);
                gf.setPointSize(gf.pointSize() + 5);
                gt->setFont(gf);
                gt->setAlignment(Qt::AlignCenter);
                QLineEdit *ge = new QLineEdit(&gateWin);
                ge->setEchoMode(QLineEdit::Password);
                ge->setPlaceholderText(hr::l10n::s("输入解锁密码", "Enter password"));
                QLabel *gerr = new QLabel(&gateWin);
                gerr->setStyleSheet(QString::fromLatin1("color:#d32f2f; font-size:%1px;").arg(hr::Ui::fsPx(11)));
                gerr->hide();
                QPushButton *gok =
                    new QPushButton(hr::l10n::s("解锁", "Unlock"), &gateWin);
                gl->addStretch(1);
                gl->addWidget(gt);
                gl->addWidget(ge);
                gl->addWidget(gerr);
                gl->addWidget(gok);
                gl->addStretch(1);
                auto verify = [ge, &lockHash]() -> bool
                {
                    const QString h = QString::fromLatin1(
                        QCryptographicHash::hash(ge->text().toUtf8(),
                                                 QCryptographicHash::Sha256)
                            .toHex());
                    return h == lockHash;
                };
                QObject::connect(gok, &QPushButton::clicked, &gateWin,
                                 [&gateWin, &ge, &gerr, &verify]
                {
                    if (verify())
                    {
                        evt("app_unlock", "ok");
                        gateWin.accept();
                    }
                    else
                    {
                        evt("app_unlock", "fail");
                        gerr->setText(hr::l10n::s("密码错误", "Wrong password"));
                        gerr->show();
                        ge->selectAll();
                        ge->setFocus();
                    }
                });
                QObject::connect(ge, &QLineEdit::returnPressed, &gateWin, [gok]
                { gok->click(); });
                if (gateWin.exec() != QDialog::Accepted)
                {
                    hr_qt_shutdown();
                    return 0; /* 未解锁直接退出（无绕过） */
                }
            }
        }
        lib.show();
        /* U4：启动屏幕形态（ui/maximized，默认正常） */
        if (QSettings(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat)
                .value("ui/maximized", false)
                .toBool())
            lib.showMaximized();
        return app.exec();
    }

    win.show();
    return app.exec();
}
