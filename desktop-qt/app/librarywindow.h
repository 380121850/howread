/* M2 书库窗口：扫描目录 / 元数据 / 收藏 / 双击开书 / 注册表关联。 */
#ifndef HRLIBRARYWINDOW_H
#define HRLIBRARYWINDOW_H

#include <QMainWindow>
#include <QPoint>
#include <QString>

#include "hrdb.h"
#include <QStringList>
#include <QVector>
#include <QDateTime> /* v0.107.2：reloadAfterReader 内联打点 */
#include <QSet>
#include <QHash>
#include <QPair>
#include <QStackedWidget> /* v0.107.2：内联读 currentIndex 需完整类型 */
#include <QTimer> /* v0.107.2：内联 singleShot 需完整类型（原仅前置声明） */

class QTreeWidget;
class QTreeWidgetItem;
class QLabel;
class QListWidgetItem;
class QStackedLayout;
class QComboBox;
class QListWidget;
class QStackedWidget;
class QPushButton;
class QLineEdit;
class QHBoxLayout;
class QCloseEvent;
class QEvent;
class QToolButton;
class QTimer;

namespace hr
{

class ShelfFlow; /* U8 书架视图（librarywindow.cpp 内定义） */
class ReaderWindow; /* v0.75.0 内嵌阅读 */
class BannerWidget; /* v0.78.0 V3 侧栏横幅 */

class LibraryWindow : public QMainWindow
{
    Q_OBJECT
public:
    explicit LibraryWindow(QWidget *parent = nullptr);
    void reloadAfterReader()
    {
        /* v0.107.2：可见页同步刷、其余延后一拍——关书回库先画出当前页。
         * 原四页连环重建（书库网格+首页+书签+珍藏，各带全量封面解码）整段
         * 堵在返回链上，是关书卡顿主体；cur=0 时首页已由 showTab 刷过。 */
        const int cur = m_stack ? m_stack->currentIndex() : 0;
        if (cur == 1)
        {
            loadTable();
            QTimer::singleShot(0, this, [this] { refreshHome(); });
        }
        else if (cur == 0)
        {
            QTimer::singleShot(0, this, [this] { loadTable(); });
        }
        else
        {
            QTimer::singleShot(0, this, [this]
            {
                loadTable();
                refreshHome();
            });
        }
        QTimer::singleShot(0, this, [this] { refreshBookmarksPage(); });
        QTimer::singleShot(0, this, [this] { refreshFavPage(); });
    } /* U1/U7：阅读窗关闭后回写书库与首页（v0.107.2 可见页优先） */
    bool scanning() const { return m_scanning; }
    int libCount() const;
    void rescan() { scanAll(); } /* --libscan 无头回归入口 */
    void assocNow() { onAssoc(); } /* --assoc 无头回归入口 */
    int coversGenSync(int limit); /* --coversmoke 无头：同步生成前 limit 本封面 */
    /* v0.75.0 内嵌阅读：书在书库壳内打开（阅读器=m_stack 页），返回即刷新书库 */
    void openInReader(const QString &path, int page = -1);
    void openRemoteInReader(void *doc, const QString &name, QObject *keepAlive,
                            const QString &key);
    void onReaderBack();
    bool readerActive() const { return m_reader != nullptr; }
    bool readerIsCurrent() const;
    int readerPages() const;
    bool chromeHidden() const;
    bool chromeShown() const;
    QString embedGeomInfo() const;
    void gotoTab(int idx) { showTab(idx); } /* v0.85.0 W4：--prefsmoke 探针切页 */
    void probeBrowse2();
    void debugBrowseRoundtrip();
    void debugWipeMetaRescan();
    void debugOpenFirstScanDir(); /* W20 取证：圆钮路径重放 */ /* W20 取证：清 meta 强制引擎重扫 */ /* v0.99.0 W20 复现探针 */ /* v0.96.0 W17#5 双重释放回归探针 */
    QPixmap grabPrefsContent() const;       /* v0.85.0 W4：偏好滚动内容整页自绘 */
    void setViewForProbe(const QString &v); /* v0.87.0 截图对比探针：切书库视图 */
    void toggleDarkForProbe() { toggleDark(); } /* v0.87.0 二轮：夜间切换（真实路径） */ /* 探针：壳/栈/阅读器几何自检 */

signals:
    void openRequested(const QString &path);
    void openAtRequested(const QString &path, int page); /* U7：书签跳转开书 */
    void openRemoteRequested(void *doc, const QString &name, QObject *keepAlive,
                             const QString &key); /* 缺陷#4：remote://<id>/<target> 注册键 */

private slots:
    void onAddDir();
    void onRescan();
    void onAssoc();
    void onAssocUndo(); /* P1（F1）撤销文件关联 */
    void onOpenBook(QTreeWidgetItem *item, int col);
    void onSync();
    void onRemote();
    void onStats();
    void onFullTextSearch(); /* v0.99.2 W22：状态行“全文搜索” */

private:
    void loadTable(); /* file_meta -> 树/封面墙镜像 */
    void scanAll(const QStringList &onlyDirs = QStringList()); /* 所有 scan_dirs 增量扫描；v0.91.0 W12 可只扫指定目录 */
    void onToggleFav();
    void onTreeMenu(const QPoint &pos);  /* U0：书目右键菜单（收藏接线） */
    void onCoverMenu(const QPoint &pos); /* U0：封面墙右键收藏 */
    void startCoverGen(); /* 封面异步生成（缺图标项，≤300） */
    /* U2 书库页 */
    void onManageDirs(); /* 目录管理对话框（添加/移除扫描目录） */
    void buildPrefsPage(); /* U4：偏好页真页面（一级分组+二级组逐项） */
    /* U5 我的文件 / 网上书库 */
    void buildBrowsePage();
    void buildRemotePage();
    void refreshRemoteServers();
    void refreshLocalHome();
    void fillLocal(const QString &dir);
    void remoteBrowse(const Db::RemoteSrc &s, const QString &sub);
    void remoteOpenStream(const Db::RemoteSrc &s, QListWidgetItem *it);
    /* 缺陷#4：流式打开核心（列表项/书库 remote:// 条目共用）+ 重连 + 统一入口 */
    void openRemoteItem(const Db::RemoteSrc &s, const QString &name, long long size,
                        const QString &target);
    bool openRemoteByPath(const QString &p);
    void openBookOrRemote(const QString &p);
    void remoteDownloadOpen(const Db::RemoteSrc &s, QListWidgetItem *it);
    void remoteDownloadOpen(const Db::RemoteSrc &s, const QString &name,
                            const QString &target); /* v0.106.0（P0-1） */
    bool remoteServerForm(Db::RemoteSrc *io); /* 四 tab 表单；返回是否保存 */
    void onDeleteFiles(const QStringList &paths); /* 删除文件（确认+失败反馈） */
    /* U1 导航壳 */
    void showTab(int idx);
    void onSide(int row);
    void toggleDark();
    void setDarkMode(bool dark, bool persist); /* v0.88.0 W7：亮暗应用统一入口 */
    /* U7 首页六区块 + FAB / 书签聚合页 / 珍藏页 */
    void buildHomePage();
    void refreshHome();
    void buildBookmarksPage();
    void refreshBookmarksPage();
    void buildFavPage();
    void refreshFavPage();
    void onClearStatsDialog(); /* 统计齿轮：清空两击确认（缺陷#3） */
    QString homeLastBook() const;
    /* U8 全局收尾 */
    void applyBlueLight();       /* 蓝光滤镜暖色叠加（ui/blueLight 0/1/2） */
    void showAboutDialog();      /* 软件说明 22 行（侧栏 case 6） */
    void onProfilesDialog();     /* 配置文件管理（profiles/<名> + data-dir.txt） */
    void onLockPasswordDialog(); /* 密码锁设置/清除（SHA-256） */
protected:
    bool eventFilter(QObject *obj, QEvent *ev) override; /* U8：暖色层随窗缩放 */
    void closeEvent(QCloseEvent *e) override; /* v0.75.0：阅读中关主窗=退出阅读（确认+进度落盘） */
private:

    QTreeWidget *m_tree = nullptr;
    QLabel *m_lbStat = nullptr;
    QListWidget *m_cover = nullptr;   /* 封面墙（批次三） */
    QTimer *m_coverRebuildTimer = nullptr; /* v0.102.0 fix15：封面宽度滑杆防抖重建 */
    QComboBox *m_sortC = nullptr;     /* 排序（标题/最近/页数/大小） */
    QPushButton *m_btnCover = nullptr;
    volatile bool m_coverGenActive = false;
    QTimer *m_iconFillTimer = nullptr; /* v0.109.0：书库图标/墙卡分批填充泵 */
    QVector<QPair<QTreeWidgetItem *, QPair<QString, QString>>> m_iconTodo;
    QVector<QListWidgetItem *> m_wallTodo;
    QVector<Db::MetaRow> m_wallRows;
    QSet<QString> m_wallFavs;
    QHash<QString, QPair<int, int>> m_wallProg;
    int m_wallCw = 0, m_wallCh = 0;
    void pumpDeferredFill(); /* v0.109.0：分批填充泵 */
    void wallAttachCard(const Db::MetaRow &r, QListWidgetItem *it); /* v0.109.0：墙卡挂载 */
    void scanRemoteSources(); /* v0.110.0：勾选服务器递归扫描入库（webdav/smb/sftp） */
    void startScanProbe(const QStringList &paths); /* v0.110.3：后台逐本补页数/真标题 */
    QStringList m_probeQueue; /* v0.110.3：探测队列（g_probeMu 保护） */
    bool m_probeBusy = false;
    void governSources(); /* v0.110.2：未勾选来源的书即刻移出（同步） */
    QTimer *m_scanDebounce = nullptr;  /* v0.110.0：勾选后防抖统一扫描 */
    bool m_remoteScanBusy = false;     /* v0.110.0：远程扫描防重入（UI 线程置/清） */
    bool m_onlyFav = false;
    QStringList m_exts;
    volatile bool m_scanning = false;
    /* U2 书库页 */
    QLineEdit *m_search = nullptr;   /* 标题/路径过滤（记住查询） */
    QComboBox *m_statusC = nullptr;  /* 全部/未读/在读/已读 */
    QComboBox *m_viewC = nullptr;    /* 列表/网格/封面 */
    QVector<QPushButton *> m_statusChips; /* v0.89.0 W8：状态 chips（全部/未读/在读/已读） */
    /* U5 我的文件/网上书库 */
    QWidget *m_remotePage = nullptr;
    Db::RemoteSrc m_curSrc;
    QStackedWidget *m_remoteSw = nullptr; /* 0 服务器列表 / 1 浏览 */
    QListWidget *m_srvList = nullptr, *m_fileList = nullptr;
    QListWidget *m_loList = nullptr;
    QLabel *m_crumbLb = nullptr, *m_remoteLb = nullptr;
    QWidget *m_opdsRow = nullptr;
    QLineEdit *m_opdsQ = nullptr;
    QStackedLayout *m_loSw = nullptr; /* 0 位置组 / 1 本地目录 */
    QString m_loDir;
    /* U1 导航壳 */
    QListWidget *m_side = nullptr;
    QStackedWidget *m_stack = nullptr;
    QWidget *m_libPage = nullptr, *m_homePage = nullptr, *m_browsePage = nullptr,
            *m_prefsPage = nullptr;
    QVector<QToolButton *> m_tabBtns; /* v0.76.0 V1：QToolButton 支持 图标上/文字下 */
    /* U7 首页 + ExtraPages */
    QWidget *m_bmPage = nullptr, *m_favPage = nullptr;
    QTreeWidget *m_bmTree = nullptr;
    QListWidget *m_favList = nullptr;
    QHBoxLayout *m_hRecent = nullptr, *m_hBm = nullptr, *m_hFav = nullptr,
                *m_hFiles = nullptr, *m_hSrv = nullptr;
    QLabel *m_hSubRecent = nullptr, *m_hSubBm = nullptr, *m_hSubFav = nullptr,
           *m_hSubStats = nullptr, *m_hSubFiles = nullptr, *m_hSubSrv = nullptr;
    QLabel *m_hStBooks = nullptr, *m_hStRead = nullptr, *m_hStToday = nullptr,
           *m_hStSpeed = nullptr, *m_hStTotal = nullptr;
    QToolButton *m_homeFab = nullptr; /* v0.77.0 V2：56px 圆形图标 FAB */
    QToolButton *m_sideAboutBtn = nullptr; /* v0.95.0 W16：侧栏最底横排——软件说明/夜间模式 */
    QToolButton *m_sideNightBtn = nullptr;
    /* U8 */
    QWidget *m_warm = nullptr;    /* 蓝光暖色叠加层 */
    ShelfFlow *m_shelf = nullptr; /* 书架视图（内容） */
    QWidget *m_shelfWrap = nullptr; /* 书架滚动区（可见性切换目标） */
    /* v0.75.0 内嵌阅读 */
    ReaderWindow *m_reader = nullptr; /* 当前内嵌阅读器（至多一个） */
    QWidget *m_tabBar = nullptr;      /* 底部标签条包壳（阅读时整体隐藏） */
    int m_prevIdx = 0;                /* 进入阅读前的栈页（返回时恢复） */
    QStringList m_sideIconFiles;      /* v0.76.0 V1：侧栏图标文件（随亮暗重染） */
    QString m_tagFilter; /* v0.84.0 W3 D4：当前标签过滤（空=无；Compose tagFilter 同语义） */
    QVector<QListWidgetItem *> m_tagItems; /* v0.84.0 W3：侧栏标签区动态行 */
    /* v0.78.0 V3：侧栏壳 = 横幅 + 列表 + 格言条 + 扫描提示 */
    QWidget *m_sideWrap = nullptr;
    BannerWidget *m_banner = nullptr;
    QLabel *m_quoteLb = nullptr;
    QLabel *m_scanLb = nullptr;
    void applySideIcons();
    void refreshSideTags(); /* v0.84.0 W3 D4：侧栏标签区聚合/重建 */
    void embedReader(ReaderWindow *rw);
    void retireReader(bool refresh);
};

} // namespace hr

#endif /* HRLIBRARYWINDOW_H */
