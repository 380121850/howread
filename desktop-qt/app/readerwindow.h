/* M1 阅读器 MVP：翻页/滚动/双页/目录/书签/进度/主题（渲染走 hr_bridge，与现版同引擎同管线）。 */
#ifndef HRREADERWINDOW_H
#define HRREADERWINDOW_H

#include <atomic>
#include <QHash>
#include <QImage>
#include <QMainWindow>
#include <QPair>
#include <QRectF>
#include <QString>
#include <QTimer>
#include <QVector>

#include "hrdb.h"

class QEvent;
#include "readerm4.h"
class QCloseEvent;
class QKeyEvent;
class QLabel;
class QWidget;
class QPushButton;
class QWheelEvent;
class QResizeEvent;
class QScrollArea;
namespace hr { class SelOverlay; }
class QDockWidget;
class QTreeWidget;
class QListWidget;
class QTabWidget;
class QLineEdit;
class QSlider;
class QVBoxLayout;

namespace hr
{

/* 对齐 core/reader/ReadThemes.kt 五主题（bg/fg/暗色标记）。 */
struct Theme
{
    const char *id;
    const char *zh;
    unsigned bg;
    unsigned fg;
    bool dark;
};
extern const Theme kThemes[5];

class ReaderWindow : public QMainWindow
{
    Q_OBJECT
public:
    explicit ReaderWindow(QWidget *parent = nullptr);
    bool openBook(const QString &path, int jumpPage = -1);
    /* v0.103.0 P0-1：GUI 后台打开（打开/转换/排版在 worker，UI 只装配；smoke 仍走 openBook 同步路径） */
    bool openBookAsync(const QString &path, int jumpPage = -1);
    bool asyncFailed() const { return m_asyncFailed.load(); } /* --asyncsmoke 判定 */
    /* M3 远程流式打开：接管已打开的引擎句柄；keepAlive（如块缓存会话）随窗销毁 */
    bool adoptDoc(void *doc, const QString &displayName, QObject *keepAlive,
                  int jumpPage = -1, const QString &progressKey = QString());

    /* --smoke 无头支撑：定视口、强制翻页模式（同步渲染）、取当前页位图/页码 */
    void setSmokeViewport(int w, int h) { m_smokeW = w; m_smokeH = h; }
    void forcePageMode() { m_scrollMode = false; }
    QImage currentPageImage() const { return m_img; }
    int currentPage() const { return m_page; }
    int pageCount() const { return m_pages; }
    void flushProgress() { saveProgressNow(); } /* smoke 无事件循环时手动落盘 */
    void jumpToPublic(int page) { jumpTo(page); }
    /* v0.75.0 内嵌模式（书库壳 m_stack 页）：关闭钮=返回书库，全屏打到宿主窗 */
    void setEmbedded(bool on) { m_embedded = on; if (on && m_warmRw) m_warmRw->hide(); }
    bool embedded() const { return m_embedded; }
    bool confirmExit();  /* U4 退出确认抽取（closeEvent/内嵌返回共用） */
    void requestClose(); /* 关闭钮入口（内嵌→backRequested / 独立窗→close） */

    /* M4 受控出口（ReaderMark/搜索/TTS/AI 用） */
    void *hrDoc() const { return m_doc; }
    void releaseEngineDoc(); /* v0.106.0（P0-4）：归还引擎文档（retire/close/析构共用） */
    bool phase2Busy() const { return m_phase2Busy.load(); } /* v0.107.2：retireReader 延迟放行轮询 */
    ~ReaderWindow();
    void beginTeardown(bool wait = true); /* v0.107.0（P1-5 补齐）：销毁前收 phase2；v0.107.2：wait=false=只作废 gen 即返回 */
    QString outlineCachePath() const; /* v0.107.0（P2-6）：outline 每书缓存键 */
    void outlineCacheTrim();          /* v0.107.0（P2-6）：outline 缓存 LRU 留 50 */
    int m_searchGenLoad() const { return m_searchGen; } /* P2（C6）：搜索换代读取 */
    void toggleImmersive();        /* U3a：沉浸（全屏+隐双栏，Esc 退出） */
    void armTtsAdvance(int sec);   /* 批次三：朗读定时自动翻页 */
    void disarmTtsAdvance();
    QRectF pageImageRect() const;
    int renderW() const { return renderWidthPx(); }
    signals:
    void searchWord(const QString &w);
    void backRequested(); /* v0.75.0：内嵌模式请求返回书库 */
    void openPathRequested(const QString &path); /* v0.83.0 W2：换书（最近书单直达开书） */
    void openFailed(const QString &path); /* v0.103.0：后台打开失败（书库收尾空阅读页） */

protected:
    void keyPressEvent(QKeyEvent *e) override;
    void resizeEvent(QResizeEvent *e) override;
    void closeEvent(QCloseEvent *e) override; /* U4：退出确认（reader/exitConfirm） */
    bool eventFilter(QObject *obj, QEvent *ev) override;

private slots:
    void onScrollValue(int val);
    void onToc();
    void onBookmarks();
    void onAddBookmark();
    void onThemeNext();
    void onFontBigger();
    void onFontSmaller();
    void onOpen();
    void onPrefs();
    void translateSelection(const QString &text, int tgtMode = 0); /* U3b：0=自动 1=中文 2=英文 */
    void sideSearch(const QString &pre); /* U3b：划词"搜索同词"走侧板 */
    void dictLookup(const QString &word);

private:
    /* engine */
    void *m_doc = nullptr;
    QString m_path, m_bookName;
    int m_pages = 0, m_page = 0;
    float m_em = 12.0f;
    int m_theme = 0;
    bool m_scrollMode = true; /* 默认滚动（与现版 defaultReaderMode 一致） */
    bool m_twoPage = false;
    /* v0.59.0 排版偏好（Compose ReaderPrefs 基础版同默认：行距 180/边距 4,10/缩进 2em/段距 0.2em） */
    int m_lineHeight = 180;                    /* %（100~250） */
    int m_marginT = 4, m_marginB = 4;          /* pt（0~60） */
    int m_marginL = 10, m_marginR = 10;        /* pt（0~60） */
    int m_textIndent = 20;                     /* 0.1em（0~50，20=2em） */
    int m_paraSpacing = 1;                     /* 0.1em（0~20，1=0.2em） */
    QString m_align = "justify";               /* P1：对齐四态 left/justify/right/center */

    /* render 状态 */
    QImage m_img, m_img2; /* 当前页与右页（双页时） */
    int m_viewW = 900, m_viewH = 1200;
    int m_smokeW = 0, m_smokeH = 0;
    int m_estH = 1200; /* 占位页高估计 */
    bool m_busy = false;      /* 单飞行渲染 */
    int m_pendingPage = -1;   /* 渲染期间的最新请求 */
    int m_sliderMoving = 0;

    int renderWidthPx() const;
    int maxCharsWidth() const; /* P1：版心宽（maxChars>0 时钳制；否则=视口宽） */
    QRectF scrollPageRect(int page) const; /* P1（D2）：滚动模式页矩形（视口坐标） */
    int scrollPageAt(const QPoint &vp) const; /* P1（D2）：视口坐标→命中页号 */
    void applyThemeToJob(int *mode, int *tint) const;
    bool renderRaw(int page, int width, QImage *out);
    void renderNow(int page);   /* 翻页模式：同步渲染当前页 */
    void requestPage(int page); /* 滚动模式：异步填充 */
    void nav(int delta);
    void relayoutAfterChange(bool metrics = true); /* v0.109.0：metrics=false=主题/模式切换（分章书不重数） */
    void finishRelayoutWindowed(int gen, void *doc, const QVector<int> &all, int anchorCh, int anchorPg, int nc); /* v0.109.0：分章书窗口化重排落地 */
    void updateModeUi(); /* v0.109.0：滚动模式进度条移右缘 */
    bool afterOpen(int jumpPage); /* 打开/接管后的公共装配（视口/排版/进度/首渲染） */
    /* v0.103.0 P0-1/P0-4：后台打开装配 + 翻页邻页预取 */
    void showOpenOverlay();
    void hideOpenOverlay();
    void finishOpen(void *doc, int pages, int chapters, int restored, qint64 convMs,
                    qint64 openMs, qint64 layoutMs, const QString &err, int gen,
                    bool accelOk, qint64 accelMs, int partialFrom = 0,
                    bool partial = false, QVector<int> chapPages = QVector<int>());
    void startPhase2Count(int fromChapter, int partialPages, int chapters); /* P1-5 */
    void applyPhase2Pages(int total, int chapters, QVector<int> all = QVector<int>()); /* P1-5 */
    void scheduleStackGrow(); /* v0.107.1：滚动条目分批补建 */
    void kickPagePrefetch(int page);
    void storePrefetch(int gen, int page, int w, const QImage &img);
    void finishRelayout(int gen, void *doc, int pages, int chapters); /* v0.104.0：后台重排装配 */

    /* U3a 顶栏/底栏/沉浸 */
    QLabel *m_lbBook = nullptr;      /* 顶栏书名 */
    QWidget *m_topBar = nullptr, *m_botBar = nullptr;
    bool m_immersive = false;
    bool m_embedded = false; /* v0.75.0：被书库壳内嵌（m_stack 页） */
    bool m_prevFull = false;
    /* U3b 侧板（QDockWidget 三 tab，默认隐藏） */
    QDockWidget *m_side = nullptr;
    QTabWidget *m_sideTabs = nullptr;
    QTreeWidget *m_tocTree = nullptr;
    QListWidget *m_bmList = nullptr;
    QListWidget *m_schList = nullptr;
    QLineEdit *m_schEdit = nullptr;
    QLabel *m_schLb = nullptr;
    QList<BookmarkRow> m_bmRows;
    /* U-ann 书签板高亮分区 */
    QListWidget *m_hlList = nullptr;
    QList<Db::AnnotationRow> m_hlRows;
    QLabel *m_hlLb = nullptr;
    /* ui */
    QScrollArea *m_scroll = nullptr;
    QWidget *m_stack = nullptr;
    QVBoxLayout *m_stackLay = nullptr;
    QVector<QLabel *> m_labels;
    QVector<int> m_labelW;
    QLabel *m_lbPos = nullptr;
    QSlider *m_slider = nullptr;
    QTimer m_resizeTimer;
    void buildStack();
    void ensureStackTo(int page); /* v0.110.3：滚动栈自愈补建 */
    void showPageView();
    void fillVisible();
    void onPageChanged();
    void updateTitle();
    void jumpTo(int page);
    /* U3b 侧板 */
    void showPanel(int tab);
    void refreshTocTree();
    void refreshBookmarkList();
    /* U-ann 注解管理 */
    void refreshHighlightList();
    void refreshHighlights();
    void doSideSearch();

    /* 持久化 */
    QTimer m_saveTimer;
    /* 批次三：自动滚动 / 阅读统计 / TTS 定时 */
    QTimer m_autoTimer, m_statTimer, m_ttsTimer;
    int m_autoMs = 500;        /* 自动滚动间隔 ms */
    int m_ttsAdvanceSec = 0;   /* 朗读自动翻页秒数（0=关） */
    /* v0.83.0 W2：连读/定时朗读/翻页间隔/裁白边/位置导航（对齐 Compose MoreMenu 语义） */
    QTimer m_ttsContTimer, m_ttsStopTimer;
    bool m_ttsCont = false;
    bool m_ttsWasSpeaking = false;
    bool m_ttsSuppress = false;  /* 手动停止后本轮不再续读 */
    int m_ttsStopMin = 0;
    int m_autoFlipSec = 5;       /* 翻页模式自动翻页间隔秒 */
    bool m_trimWhite = false;    /* 裁白边（翻页模式） */
    int m_maxChars = 0;          /* P1：每行字数版心（0=全宽 24~120，对齐 WIN maxChars） */
    bool m_useDocCss = true;     /* P1：遵循书内样式（WIN useDocumentCss） */
    QString m_fontFamily;        /* P1：字体族（空=书内；sans-serif/monospace/serif 或 dataDir\fonts 文件名） */
    int m_selPage = -1;          /* P1（D2）：划词锚定页（滚动模式=按下位置所在页） */
    std::atomic<bool> m_searchRunning{false}; /* P2（C6）：搜索异步态 */
    std::atomic<bool> m_searchCancel{false};  /* P2（C6） */
    std::atomic<int> m_searchGen{0};          /* P2（C6）：换代防护（开书/换书作废旧搜索） */
    double m_zoom = 1.0;         /* P2（B7）：页面缩放（固定版式 0.5..4，每开书重置） */
    bool m_fixedLayout = false;  /* P2（B7）：固定版式（pdf/djvu/cbz/xps） */
    QString m_texture;           /* P2（B6）：阅读质感（none/paper/fiber/linen/parchment/canvas） */
    QWidget *m_warmRw = nullptr; /* P2（F4）：独立窗蓝光层 */
    QVector<int> m_navBack, m_navFwd;
    int m_navGuard = 0;          /* 回退/前进内部跳转不计入历史 */
    int m_lastStatPage = 0;
    /* v0.103.0：后台打开状态 + 翻页邻页预取缓存（宽度校验防 zoom/版心变化串图） */
    QWidget *m_openOv = nullptr;
    QLabel *m_openOvLb = nullptr;
    std::atomic<bool> m_openBusy{false};
    std::atomic<bool> m_asyncFailed{false};
    std::atomic<int> m_openGen{0};
    QHash<int, QPair<int, QImage>> m_pfCache;
    /* v0.104.0：后台重排状态（与开书经 m_relayoutBusy 互锁防旧 doc UAF） */
    std::atomic<bool> m_relayoutBusy{false};
    std::atomic<int> m_relayoutGen{0};
    /* v0.107.0（P1-5）：窗口计数 phase2 后台补页（busy 互锁同上） */
    std::atomic<bool> m_phase2Busy{false};
    std::atomic<qint64> m_lastRenderReq{0}; /* v0.107.1：最近渲染请求时刻（phase2 让路用） */
    std::atomic<qint64> m_renderT0{0}; /* v0.110.1：在途渲染起始时刻（看门狗用） */
    /* v0.110.0：m_vSlider/m_mid 已移除——滚动模式用原生连续滚动条 */
    QVector<int> m_chapPages; /* v0.107.1：窗口截断时的已数章页数表（phase2 续算/落盘） */
    /* M4 划词状态（PAGE 模式，事件在 m_scroll viewport eventFilter 处理） */
    QPoint m_dragStart;
    bool m_dragging = false;
    QString m_selText;
    hr::SelOverlay *m_selOverlay = nullptr;
    /* U-ann 持久高亮：按页缓存（页坐标 quads + ARGB），渲染时合成进位图（两模式统一） */
    QHash<int, QVector<QPair<QVector<QRectF>, unsigned>>> m_hlCache;
    QString m_hlCachePath;
    void loadHighlightCache();
    void compositeHighlights(int page, QImage *img);
    void applyTexture(QImage *img); /* P2（B6）：质感乘色叠加（仅浅色） */
    QLabel *m_firstLabel = nullptr; /* 翻页模式当前页 label（imgRect 定位） */
    void saveProgressNow();
    void maybeSaveRemoteCover(int page, const QImage &img); /* 缺陷#4：远程封面落盘 */
    void loadPrefs();
    void savePrefs();
    QString userCss() const;   /* ReaderPrefs.css 基础版移植 */
    void applyUserCss() const; /* hr_qt_set_css（layout 前调用） */
};

} // namespace hr

#endif /* HRREADERWINDOW_H */
