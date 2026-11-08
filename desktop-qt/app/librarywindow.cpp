#include "librarywindow.h"
#include "hr_bridge.h"
#include "hrbcache.h"
#include "hrdb.h"
#include "hrpaths.h"
#include "hrsync.h"
#include "hrwebdav.h"
#include "hrcvt.h"
#include "hrl10n.h"
#include "hrssh.h"
#include "hrsmb.h"
#include "hropds.h"
#include "hrfetch.h"
#include "hrai.h"
#include "readerm4.h"
#include "readerwindow.h" /* v0.75.0 内嵌阅读 */
#include "hricons.h" /* v0.76.0 V1 图标 */
#include "hrtheme.h" /* v0.79.0 V4 主题色槽位（UiTheme，避免与阅读器 Theme 撞名） */
#include <map> /* v0.82.0 W1：分组视图组表 */
#include <numeric> /* P2：统计聚合求和 */
#include <QPainterPath>
#include <QRandomGenerator>
#include "hrcred.h"
#include "hrstats.h"

#include <memory>
#include <atomic>

#include <algorithm>
#include <QImage>
#include <QListView>

#include <QApplication>
#include <QDateTime>
#include <QDesktopServices>
#include <QDialogButtonBox>
#include <QDir>
#include <QDirIterator>
#include <QFileDialog>
#include <QCryptographicHash>
#include <QFileInfo>
#include <QCursor>
#include <QFormLayout>
#include <QHash>
#include <QHBoxLayout>
#include <QHeaderView>
#include <QInputDialog>
#include <QJsonDocument>
#include <QJsonObject>
#include <QLabel>
#include <QLineEdit>
#include <QCheckBox>
#include <QComboBox>
#include <QMessageBox>
#include <QMouseEvent> /* v0.91.0 W12：LocRowWidget 整行点击 */
#include <QStyleOption> /* v0.91.0 W12：QWidget 子类 QSS 背景绘制 */
#include <QProgressDialog>
#include <QListWidget>
#include <QMenu>
#include <QPushButton>
#include <QRegularExpression>
#include <QSettings>
#include <QStackedWidget>
#include <QStatusBar>
#include <QCloseEvent>
#include <QScreen>
#include <QThreadPool>
#include <QCache>  /* v0.107.2：封面位图内存缓存 */
#include <QMutex>  /* v0.107.2 */
#include <QPointer> /* P0：晋升任务持弱引用防 UAF */
#include <QThread> /* P0：晋升等待 sleep */
#include <QTimer>
#include <QTreeWidget>
#include <QPalette>
#include <QUrl>
#include <QVBoxLayout>
#include <QScrollArea>
#include <QSlider>
#include <QSpinBox>
#include <QGridLayout>
#include <QToolButton>
#include <QPainter>
#include <QStackedLayout>
#include <QStandardPaths>
#include <QTabWidget>
#include <QTextBrowser>

#include <functional>

namespace hr
{
/* v0.78.0 V3：侧栏横幅（对齐 Compose 112dp 圆角 10 + 安卓抽屉横幅）：封面图 cover 裁剪 + 标语白字投影；
 * 图片资源缺失时退化为品牌渐变。日/夜两图随主题切换。 */
class BannerWidget : public QWidget
{
public:
    explicit BannerWidget(QWidget *parent = nullptr) : QWidget(parent)
    {
        setFixedHeight(112);
        m_pm[0].load(QString::fromLatin1(":/res/banner_day.jpg")); /* v0.86.0 W5 H3：Compose 同源横幅 */
        m_pm[1].load(QString::fromLatin1(":/res/banner_night.jpg")); /* v0.86.0 W5 H3 */
    }
    void setDark(bool dark)
    {
        m_dark = dark;
        update();
    }
    bool hasImage() const { return !m_pm[0].isNull(); }

protected:
    void paintEvent(QPaintEvent *) override
    {
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing);
        p.setRenderHint(QPainter::SmoothPixmapTransform);
        const QRectF r = rect();
        QPainterPath clip;
        clip.addRoundedRect(r, 10, 10);
        p.setClipPath(clip);
        const QPixmap &pm = m_pm[m_dark ? 1 : 0];
        if (!pm.isNull())
        {
            const qreal sc = qMax(r.width() / pm.width(), r.height() / pm.height());
            const qreal w = pm.width() * sc, h = pm.height() * sc;
            p.drawPixmap(QRectF((r.width() - w) / 2, (r.height() - h) / 2, w, h), pm,
                         QRectF(pm.rect()));
        }
        else
        {
            QLinearGradient g(0, 0, r.width(), r.height());
            g.setColorAt(0, QColor(0x2E, 0x6B, 0xD6));
            g.setColorAt(1, QColor(0x1F, 0x83, 0x7C));
            p.fillRect(r, g);
        }
        QFont f = p.font();
        f.setPixelSize(hr::Ui::fsPx(20));
        f.setBold(true);
        p.setFont(f);
        const QString slogan = hr::l10n::s("值得读，好好读", "Worth reading, read well");
        p.setPen(QColor(0, 0, 0, 120));
        p.drawText(r.adjusted(0, 1, 0, 1), Qt::AlignCenter, slogan);
        p.setPen(Qt::white);
        p.drawText(r, Qt::AlignCenter, slogan);
    }

private:
    QPixmap m_pm[2];
    bool m_dark = false;
};

/* 读书格言（与 Compose Quotes.kt 同源 24 条，选自工作区《读书阅读格言1000条》） */
static const char *const kQuotes[][2] = {
    {"学而不思则罔，思而不学则殆", "《论语·为政》"},
    {"温故而知新，可以为师矣", "《论语·为政》"},
    {"知之者不如好之者", "《论语·雍也》"},
    {"好之者不如乐之者", "《论语·雍也》"},
    {"学而不厌，诲人不倦", "孔子"},
    {"敏而好学，不耻下问", "《论语·公冶长》"},
    {"三人行，必有我师焉", "《论语·述而》"},
    {"玉不琢，不成器", "《礼记·学记》"},
    {"人不学，不知道", "《礼记·学记》"},
    {"学然后知不足", "《礼记·学记》"},
    {"独学而无友，则孤陋而寡闻", "《礼记·学记》"},
    {"博学而笃志，切问而近思", "《论语·子张》"},
    {"学如不及，犹恐失之", "《论语·泰伯》"},
    {"业精于勤，荒于嬉", "韩愈"},
    {"读书破万卷，下笔如有神", "杜甫"},
    {"书籍是人类进步的阶梯", "高尔基"},
    {"读一本好书，就是和许多高尚的人谈话", "笛卡尔"},
    {"书山有路勤为径", "韩愈"},
    {"黑发不知勤学早，白首方悔读书迟", "颜真卿"},
    {"纸上得来终觉浅，绝知此事要躬行", "陆游"},
    {"问渠那得清如许，为有源头活水来", "朱熹"},
    {"旧书不厌百回读，熟读深思子自知", "苏轼"},
    {"立身以立学为先，立学以读书为本", "欧阳修"},
    {"读书之法，在循序而渐进，熟读而精思", "朱熹"},
};



static QPixmap coverTile(const QString &path, const QString &title, int w, int h);
static QPixmap w8RoundedCover(QPixmap pm, bool fav); /* v0.89.0 W8：封面卡合成 */
static QPixmap coverTileBase(const QString &path, const QString &title, int w, int h); /* W8 前置 */
static QString currentProfileName(); /* U8：配置名（定义在文末 U8 区） */

/* U8 书架视图（对齐 Compose ShelfView：固定列数封面行 + 木质板条视觉） */
class ShelfFlow : public QWidget
{
public:
    explicit ShelfFlow(QWidget *parent = nullptr) : QWidget(parent) {}
    QVector<QPair<QString, QString>> books; /* path, title */
    QHash<QString, int> pct; /* v0.81.0 V6：阅读百分比徽标（path -> 0~100，0=无） */
    std::function<void(const QString &)> onOpen;
    enum
    {
        kCardW = 132,
        kCardH = 190,
        kGap = 20,
        kBoardH = 8,
        kTop = 8
    };
    void rebuild()
    {
        for (QToolButton *b : m_btns) b->deleteLater();
        m_btns.clear();
        for (const auto &bk : books)
        {
            QToolButton *tb = new QToolButton(this);
            tb->setToolButtonStyle(Qt::ToolButtonTextUnderIcon);
            QPixmap pm = coverTile(bk.first, bk.second, 112, 156);
            const int pcVal = pct.value(bk.first, 0); /* v0.81.0 V6：百分比徽标 */
            if (pcVal > 0)
            {
                QPainter pp(&pm);
                QFont bf = pp.font();
                bf.setPixelSize(11);
                bf.setBold(true);
                pp.setFont(bf);
                const QString txt = QString::number(pcVal) + QString::fromLatin1("%");
                const int bw = pp.fontMetrics().horizontalAdvance(txt) + 10;
                const QRectF br(pm.width() - bw - 4, pm.height() - 16 - 4, bw, 16);
                pp.setPen(Qt::NoPen);
                pp.setBrush(QColor(0, 0, 0, 150));
                pp.drawRoundedRect(br, 4, 4);
                pp.setPen(Qt::white);
                pp.drawText(br, Qt::AlignCenter, txt);
                pp.end();
            }
            tb->setIcon(QIcon(pm));
            tb->setIconSize(QSize(112, 156));
            tb->setFixedSize(kCardW, kCardH);
            tb->setText(tb->fontMetrics().elidedText(bk.second, Qt::ElideRight, kCardW - 8));
            tb->setToolTip(bk.second);
            tb->setCursor(Qt::PointingHandCursor);
            tb->setStyleSheet(QString::fromLatin1(
                "QToolButton{border:none; background:transparent; font-size:%1px;}"
                "QToolButton:hover{background:rgba(0,0,0,18); border-radius:6px;}").arg(hr::Ui::fsPx(11)));
            tb->show();
            const QString p = bk.first;
            connect(tb, &QToolButton::clicked, this, [this, p]
            {
                if (onOpen) onOpen(p);
            });
            m_btns.append(tb);
        }
        relayout();
    }

protected:
    void resizeEvent(QResizeEvent *e) override
    {
        QWidget::resizeEvent(e);
        relayout();
    }
    void paintEvent(QPaintEvent *) override
    {
        QPainter p(this);
        p.setRenderHint(QPainter::Antialiasing);
        const int cols = colsFor(width());
        const int rows = m_btns.isEmpty() ? 0 : (m_btns.size() + cols - 1) / cols;
        const int rowH = kCardH + 4 + kBoardH + 16;
        for (int r = 0; r < rows; r++)
        {
            const int y = kTop + r * rowH + kCardH + 4;
            p.setPen(Qt::NoPen);
            p.setBrush(QColor(0x9A, 0x7B, 0x53));
            p.drawRoundedRect(QRect(8, y, width() - 16, kBoardH), 2, 2);
            p.setBrush(QColor(0x6E, 0x55, 0x37));
            p.drawRect(QRect(8, y + kBoardH - 2, width() - 16, 2));
        }
    }

private:
    static int colsFor(int w)
    {
        return qMax(1, (w - 16 + kGap) / (kCardW + kGap));
    }
    void relayout()
    {
        const int cols = colsFor(width());
        const int rowH = kCardH + 4 + kBoardH + 16;
        const int rowW = cols * kCardW + (cols - 1) * kGap;
        const int x0 = qMax(8, (width() - rowW) / 2);
        for (int i = 0; i < m_btns.size(); i++)
            m_btns[i]->move(x0 + (i % cols) * (kCardW + kGap), kTop + (i / cols) * rowH);
        const int rows = m_btns.isEmpty() ? 0 : (m_btns.size() + cols - 1) / cols;
        setMinimumHeight(kTop + rows * rowH + 8);
        update();
    }
    QVector<QToolButton *> m_btns;
};


/* v0.61.0 批次三：+docx/odt/rtf（hr_qt_office 转换链）
 * v0.103.0 P1-5：+md/pdb/tif/tiff（引擎直开通道：md→txt 分章、pdb→mobi 处理器、tif→img 处理器）
 * v0.106.0 P0-2：+mhtml（v0.105 转换通道已在，书库白名单补漏） */
static const char *kExts[] = {"pdf", "epub", "txt",  "mobi", "azw3", "azw",
                              "prc", "fb2",  "html", "htm",  "xps",  "cbz",
                              "doc", "djvu", "docx", "rtf",  "odt",  "zip",
                              "md",  "pdb",  "tif",  "tiff", "cbr", "mht",
                              "mhtml"}; /* P2（B2）；v0.106.0（P0-2）+mhtml */

/* P1 适配（E9）：同步防重入（手动+自动共用；文件作用域供 ctor 定时器与 onSync 共用） */
static std::atomic<bool> g_syncBusy(false);

/* v0.110.3：后台探测互斥/队列（m_probeQueue 由 UI 与探测线程共同访问） */
static QMutex g_probeMu;

/* ---------- v0.104.0（P1-6）：预开（对齐安卓 OpenPrefetch 思路的桌面版）----------
 * 单击选中书目即后台预热转换缓存（doc/docx/azw3/大 mobi 等重转换在点开时命中即秒进）；
 * 单槽去重；只预热不持引擎句柄（无生命周期风险）；远程书跳过。 */
namespace
{
QMutex g_preOpenMu;
QString g_preOpenPath;
} // namespace

static void preOpenBook(const QString &path)
{
    if (path.isEmpty() || path.startsWith(QString::fromLatin1("remote://"))) return;
    {
        QMutexLocker lk(&g_preOpenMu);
        if (path == g_preOpenPath) return; /* 已预热/在预热 */
        g_preOpenPath = path;
    }
    QThreadPool::globalInstance()->start(QRunnable::create([path]
    {
        const qint64 t0 = QDateTime::currentMSecsSinceEpoch();
        const QString op = hr::openablePath(path);
        evt("preopen_warm", QString("ms=%1 converted=%2 path=%3")
                                .arg(QDateTime::currentMSecsSinceEpoch() - t0)
                                .arg(op != path ? 1 : 0)
                                .arg(path.left(70)));
    }));
}

LibraryWindow::LibraryWindow(QWidget *parent) : QMainWindow(parent)
{
    setWindowTitle("HowRead Qt — " + hr::l10n::s("书架", "Bookshelf")); /* v1.0.0 更名 */
    resize(980, 720);
    for (const char *e : kExts) m_exts.append(QString::fromLatin1(e));
    /* U4：启用格式从偏好载入（lib/exts；缺省=全开） */
    {
        QSettings st(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
        if (st.contains("lib/exts"))
        {
            const QStringList sv =
                st.value("lib/exts").toString().split(',', QString::SkipEmptyParts);
            if (!sv.isEmpty()) m_exts = sv;
        }
    }

    QPushButton *btnAdd = new QPushButton(hr::l10n::s("+ 添加书籍", "+ Add book")); /* W22：状态行右侧入口 */
    /* v0.99.2 W22：工具行撤并入状态行——这些按钮不再入布局，挂窗口父级（菜单触发 click） */
    QPushButton *btnScan = new QPushButton(hr::l10n::s("重新扫描", "Rescan"), this);
    QPushButton *btnFav = new QPushButton(hr::l10n::s("只看收藏", "Favorites"), this);
    btnFav->setCheckable(true);
    QPushButton *btnAssoc = new QPushButton(hr::l10n::s("文件关联", "File assoc."), this);
    QPushButton *btnOpen = new QPushButton(hr::l10n::s("打开…", "Open…"), this);
    QPushButton *btnSync = new QPushButton(hr::l10n::s("WebDAV 同步…", "WebDAV sync…"), this);
    QPushButton *btnRemote = new QPushButton(hr::l10n::s("远程书…", "Remote…"), this);
    QPushButton *btnStats = new QPushButton(hr::l10n::s("统计", "Stats"), this);
    m_btnCover = new QPushButton(hr::l10n::s("封面", "Covers"));
    m_btnCover->setCheckable(true);
    m_sortC = new QComboBox;
    m_sortC->addItem(hr::l10n::s("按标题", "Title"), "title");
    m_sortC->addItem(hr::l10n::s("按文件名", "File name"), "filename");
    m_sortC->addItem(hr::l10n::s("按格式", "Format"), "format");
    m_sortC->addItem(hr::l10n::s("按最近", "Recent"), "mtime");
    m_sortC->addItem(hr::l10n::s("按页数", "Pages"), "pages");
    m_sortC->addItem(hr::l10n::s("按大小", "Size"), "size");
    /* v0.82.0 W1 D2：排序对齐 Compose 9 项——补 语言/作者/系列 */
    m_sortC->addItem(hr::l10n::s("按语言", "By language"), "lang");
    m_sortC->addItem(hr::l10n::s("按作者", "By author"), "author");
    m_sortC->addItem(hr::l10n::s("按系列", "By series"), "series");
    m_statusC = new QComboBox;
    m_statusC->addItem(hr::l10n::s("全部", "All"), "all");
    m_statusC->addItem(hr::l10n::s("未读", "Unread"), "unread");
    m_statusC->addItem(hr::l10n::s("在读", "Reading"), "reading");
    m_statusC->addItem(hr::l10n::s("已读", "Finished"), "finished");
    m_viewC = new QComboBox;
    m_viewC->addItem(hr::l10n::s("列表", "List"), "list");
    m_viewC->addItem(hr::l10n::s("网格", "Grid"), "grid");
    m_viewC->addItem(hr::l10n::s("封面", "Covers"), "cover");
    m_viewC->addItem(hr::l10n::s("书架", "Shelf"), "shelf");
    /* v0.82.0 W1 D3：视图对齐 Compose 9 项——补 简表 + 按作者/系列/语言/格式分组 */
    m_viewC->addItem(hr::l10n::s("简表", "Table"), "table");
    m_viewC->addItem(hr::l10n::s("按作者", "By author"), "gauthor");
    m_viewC->addItem(hr::l10n::s("按系列", "By series"), "gseries");
    m_viewC->addItem(hr::l10n::s("按语言", "By language"), "glang");
    m_viewC->addItem(hr::l10n::s("按格式", "By format"), "gformat");
    m_search = new QLineEdit;
    m_search->setPlaceholderText(hr::l10n::s("搜索书名/路径", "Search title/path"));
    m_search->setClearButtonEnabled(true);
    m_lbStat = new QLabel(hr::l10n::s("0 本", "0 books"));

    /* v0.89.0 W8 LIB-1：顶栏对齐 Compose——大搜索框行 / 状态 chips+排序视图行 / 工具行 */
    m_search->setPlaceholderText(hr::l10n::s("搜索书名 / 路径", "Search title / path"));
    m_search->setMinimumHeight(36);
    m_search->setStyleSheet(QString::fromLatin1(
        "QLineEdit{background:palette(base); border:1px solid palette(mid);"
        " border-radius:9px; padding:4px 12px; font-size:%1px;}"
        "QLineEdit:focus{border-color:palette(highlight);}").arg(hr::Ui::fsPx(13)));
    m_lbStat->setStyleSheet(QString::fromLatin1("color:palette(mid); font-size:%1px;")
                                .arg(hr::Ui::fsPx(12)));
    QHBoxLayout *row1 = new QHBoxLayout;
    row1->addWidget(m_search, 1);
    row1->addWidget(m_lbStat);

    m_statusC->setVisible(false); /* W8：状态由 chips 接管（combo 保留为状态载体） */
    QHBoxLayout *row2 = new QHBoxLayout;
    row2->setSpacing(6);
    const QString chipQss = QString::fromLatin1(
        "QPushButton#statusChip{background:transparent; border:1px solid palette(mid);"
        " border-radius:13px; padding:4px 16px; color:palette(text);}"
        "QPushButton#statusChip:checked{background:palette(highlight);"
        " border-color:palette(highlight); color:palette(highlighted-text); font-weight:bold;}");
    for (int i = 0; i < m_statusC->count(); i++)
    {
        QPushButton *chip = new QPushButton(m_statusC->itemText(i));
        chip->setCheckable(true);
        chip->setObjectName(QString::fromLatin1("statusChip"));
        chip->setCursor(Qt::PointingHandCursor);
        chip->setStyleSheet(chipQss);
        chip->setChecked(i == m_statusC->currentIndex());
        connect(chip, &QPushButton::clicked, this, [this, i]
        {
            evt("status_chip", m_statusC->itemData(i).toString());
            m_statusC->setCurrentIndex(i);
        });
        m_statusChips.append(chip);
        row2->addWidget(chip);
    }
    connect(m_statusC, static_cast<void (QComboBox::*)(int)>(&QComboBox::currentIndexChanged),
            this, [this](int c)
    {
        for (int i = 0; i < m_statusChips.size(); i++)
        {
            m_statusChips[i]->blockSignals(true);
            m_statusChips[i]->setChecked(i == c);
            m_statusChips[i]->blockSignals(false);
        }
    });
    /* v0.99.2 W22：状态行第五项“全文搜索”（引擎逐页真搜，后台线程） */
    QPushButton *ftsChip = new QPushButton(hr::l10n::s("全文搜索", "Full-text"));
    ftsChip->setObjectName(QString::fromLatin1("statusChip"));
    ftsChip->setCursor(Qt::PointingHandCursor);
    ftsChip->setStyleSheet(chipQss);
    connect(ftsChip, &QPushButton::clicked, this, &LibraryWindow::onFullTextSearch);
    row2->addWidget(ftsChip);
    row2->addStretch(1);
    row2->addWidget(new QLabel(hr::l10n::s("排序:", "Sort:")));
    row2->addWidget(m_sortC);
    row2->addWidget(new QLabel(hr::l10n::s("视图:", "View:")));
    row2->addWidget(m_viewC);
    /* W22：右侧其余入口——+ 添加书籍 / 多选开关 / ⋯ 更多（历史工具收拢，功能不丢） */
    row2->addWidget(btnAdd);
    QPushButton *multiBtn = new QPushButton(hr::l10n::s("多选", "Multi"));
    multiBtn->setCheckable(true);
    multiBtn->setCursor(Qt::PointingHandCursor);
    connect(multiBtn, &QPushButton::toggled, this, [this](bool on)
    {
        evt("lib_multi", on ? "on" : "off");
        m_tree->setSelectionMode(on ? QAbstractItemView::ExtendedSelection
                                    : QAbstractItemView::SingleSelection);
        m_cover->setSelectionMode(on ? QAbstractItemView::ExtendedSelection
                                     : QAbstractItemView::SingleSelection);
    });
    row2->addWidget(multiBtn);
    QMenu *libMoreMenu = new QMenu(this);
    libMoreMenu->addAction(btnScan->text(), btnScan, [btnScan] { btnScan->click(); });
    libMoreMenu->addAction(btnFav->text(), btnFav, [btnFav] { btnFav->click(); });
    libMoreMenu->addAction(btnOpen->text(), btnOpen, [btnOpen] { btnOpen->click(); });
    libMoreMenu->addAction(btnSync->text(), btnSync, [btnSync] { btnSync->click(); });
    libMoreMenu->addAction(btnRemote->text(), btnRemote, [btnRemote] { btnRemote->click(); });
    libMoreMenu->addAction(btnAssoc->text(), btnAssoc, [btnAssoc] { btnAssoc->click(); });
    libMoreMenu->addAction(btnStats->text(), btnStats, [btnStats] { btnStats->click(); });
    /* v1.0.0：工具行按钮撤出布局后仅作菜单载体——显式隐藏（此前悬浮窗口左上角
     * 互相叠盖，最上层"统计"泄漏到阅读视图，用户点名去除；click() 对隐藏钮有效） */
    btnScan->setVisible(false);
    btnFav->setVisible(false);
    btnAssoc->setVisible(false);
    btnOpen->setVisible(false);
    btnSync->setVisible(false);
    btnRemote->setVisible(false);
    btnStats->setVisible(false);
    QToolButton *libMoreBtn = new QToolButton(this);
    libMoreBtn->setText(QString::fromUtf8("\xE2\x8B\xAF")); /* ⋯ */
    libMoreBtn->setPopupMode(QToolButton::InstantPopup);
    libMoreBtn->setCursor(Qt::PointingHandCursor);
    libMoreBtn->setToolTip(hr::l10n::s("更多工具", "More tools"));
    libMoreBtn->setMenu(libMoreMenu);
    row2->addWidget(libMoreBtn);

    /* v0.99.2 W22：工具行已撤——btnAdd 入状态行右侧，其余收进“⋯ 更多”菜单（用户点名单行顶栏） */

    m_tree = new QTreeWidget;
    m_tree->setColumnCount(4);
    m_tree->setHeaderLabels(QStringList()
                            << hr::l10n::s("标题", "Title")
                            << hr::l10n::s("页数", "Pages")
                            << hr::l10n::s("大小", "Size")
                            << hr::l10n::s("路径", "Path"));
    m_tree->header()->setSectionResizeMode(0, QHeaderView::Stretch);
    m_tree->setIconSize(QSize(40, 56)); /* v0.81.0 V6：列表行封面缩略图 */
    m_tree->setRootIsDecorated(false);
    m_tree->setAlternatingRowColors(true);
    m_tree->setSelectionMode(QAbstractItemView::SingleSelection); /* W22：默认单选，“多选”开关启用扩展 */
    m_tree->setContextMenuPolicy(Qt::CustomContextMenu);
    connect(m_tree, &QTreeWidget::customContextMenuRequested, this,
            &LibraryWindow::onTreeMenu);

    m_cover = new QListWidget;
    m_cover->setViewMode(QListView::IconMode);
    m_cover->setResizeMode(QListView::Adjust);
    m_cover->setMovement(QListView::Static);
    m_cover->setWrapping(true);
    m_cover->setGridSize(QSize(150, 224));
    m_cover->setIconSize(QSize(132, 186));
    m_cover->setWordWrap(true);
    m_cover->setVisible(false);
    m_cover->setContextMenuPolicy(Qt::CustomContextMenu);
    /* U4：封面墙封面宽度（ui/coverSize，110~280，默认 132） */
    {
        QSettings st(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
        const int w = qBound(110, st.value("ui/coverSize", 132).toInt(), 280);
        m_cover->setIconSize(QSize(w, w * 186 / 132));
        m_cover->setGridSize(QSize(w + 18, w * 186 / 132 + 66)); /* v0.89.0 W8：卡片三行 */
    }
    connect(m_cover, &QListWidget::customContextMenuRequested, this,
            &LibraryWindow::onCoverMenu);

    /* U1 导航壳：书库原内容收进 stack 第 1 页 */
    m_libPage = new QWidget;
    QVBoxLayout *lay = new QVBoxLayout(m_libPage);
    lay->setContentsMargins(6, 6, 6, 6);
    lay->setSpacing(4);
    lay->addLayout(row1);
    lay->addLayout(row2);
    lay->addWidget(m_tree, 1);
    lay->addWidget(m_cover, 1);
    /* U8 书架视图（封面+木质板条，QScrollArea 包裹） */
    QScrollArea *shelfWrap = new QScrollArea;
    shelfWrap->setWidgetResizable(true);
    shelfWrap->setFrameShape(QFrame::NoFrame);
    m_shelf = new ShelfFlow;
    m_shelf->onOpen = [this](const QString &p)
    {
        evt("lib_open_book", "path=" + QFileInfo(p).fileName().left(80));
        openBookOrRemote(p);
    };
    m_shelf->setVisible(false);
    shelfWrap->setWidget(m_shelf);
    shelfWrap->setVisible(false);
    m_shelfWrap = shelfWrap;
    lay->addWidget(m_shelfWrap, 1);

    /* v0.78.0 V3：侧栏壳（横幅 + 列表 + 格言条 + 扫描提示），总宽 204 / 内边距 10（对齐 Compose） */
    m_sideWrap = new QWidget;
    m_sideWrap->setFixedWidth(204);
    QVBoxLayout *sideLay = new QVBoxLayout(m_sideWrap);
    sideLay->setContentsMargins(10, 14, 10, 10);
    sideLay->setSpacing(8);
    m_banner = new BannerWidget;
    sideLay->addWidget(m_banner);
    m_side = new QListWidget;
    m_side->setFrameShape(QFrame::NoFrame);
    /* v0.95.0 W16：导航字号调大 1/3+（11pt≈15px → fsPx(18)≈20px，用户点名） */
    /* v0.96.0 W17#1：导航字号 25px（用户点名），行上下 padding 拉大间隔 */
    m_side->setStyleSheet(QString::fromLatin1("QListWidget{font-size:25px;}"
                                              "QListWidget::item{padding:10px 2px;}"));
    /* v1.0.0：同步安卓 11-06 导航更名——我的文件→我的书库；抽屉撤"网上书库"行
     * （功能保留，仍从首页"网上书库"区块进入，与安卓同构） */
    const QStringList sideItems = {
        hr::l10n::s("最近阅读", "Recent"),
        hr::l10n::s("我的书架", "Bookshelf"),
        hr::l10n::s("我的书库", "My library"),
        hr::l10n::s("书签笔记", "Bookmarks"),
    };
    for (const QString &s : sideItems) m_side->addItem(s);
    /* v0.76.0 V1：侧栏图标（与安卓抽屉同 glyphicons），随亮暗重染 */
    m_sideIconFiles.clear();
    m_sideIconFiles << QString::fromLatin1("glyphicons_55_clock")
                    << QString::fromLatin1("glyphicons_589_book_open")
                    << QString::fromLatin1("glyphicons_145_folder_open")
                    << QString::fromLatin1("glyphicons_73_bookmark"); /* v1.0.0：globe 随网上书库行撤 */ /* v0.95.0 W16：说明/夜间图标随两行移出列表（底部横排按钮自带） */
    m_side->setIconSize(QSize(20, 20));
    /* v0.95.0 W16：拉伸/软件说明/夜间三行不再入列表——底部横排置底、格言挂件在其上方 */
    /* v0.84.0 W3：静态行图标文件写入行 data（applySideIcons 改 data 驱动，插标签行后索引稳） */
    for (int i = 0; i < m_side->count() && i < m_sideIconFiles.size(); i++)
    {
        QListWidgetItem *itI = m_side->item(i);
        if (itI && !m_sideIconFiles.at(i).isEmpty())
            itI->setData(Qt::UserRole + 1, m_sideIconFiles.at(i));
    }
    {
        const bool dark0 = QSettings(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat)
                               .value("ui/dark", false)
                               .toBool();
        evt("cold_dark", QString("v=%1").arg(dark0 ? 1 : 0)); /* v0.88.0 W7：冷启动暗色读取诊断 */
        hr::UiTheme::apply(dark0); /* v0.79.0 V4：启动即应用 Compose 双色板（亮/暗） */
        hr::Icons::setDark(dark0);
    }
    applySideIcons(); /* v0.76.0 V1：全部条目就位后再挂图标 */
    refreshSideTags(); /* v0.93.0 W14：侧栏不再放标签条目（清理历史残留动态行） */
    if (m_banner) m_banner->setDark(hr::Icons::isDark());
    sideLay->addWidget(m_side, 1);
    {
        /* 格言条：每次启动随机一条 */
        const int qn = int(sizeof(kQuotes) / sizeof(kQuotes[0]));
        const int qi = int(QRandomGenerator::global()->bounded(qn));
        m_quoteLb = new QLabel(QString::fromUtf8("「%1」\n—— %2")
                                   .arg(QString::fromUtf8(kQuotes[qi][0]),
                                        QString::fromUtf8(kQuotes[qi][1])));
        m_quoteLb->setWordWrap(true);
        m_quoteLb->setStyleSheet(QString::fromLatin1(
            "color:palette(mid); font-size:15px; background:transparent;")); /* W18#1：格言 15PX */
        /* v0.95.0 W16：格言条不再是列表行——侧栏挂件，恰在底部横排（说明/夜间）之上 */
        m_quoteLb->setContentsMargins(2, 4, 6, 2);
        sideLay->addWidget(m_quoteLb);
        sideLay->addSpacing(30); /* W17#2：格言整体较原位置向上 30px（用户点名） */
        m_scanLb = new QLabel(hr::l10n::s("扫描书库中…", "Scanning…"));
        m_scanLb->setStyleSheet(
            QString::fromLatin1("color:palette(mid); font-size:%1px; background:transparent;")
                .arg(hr::Ui::fsPx(11)));
        m_scanLb->hide();
        sideLay->addWidget(m_scanLb);
        evt("side_shell", QString("banner_img=%1 quote=%2")
                              .arg(m_banner && m_banner->hasImage() ? 1 : 0).arg(qi));
    }

    /* v0.95.0 W16：侧栏最底横排 [软件说明][夜间模式]（用户点名：置底、横排） */
    {
        const QString sBotQss = QString::fromLatin1(
            "QToolButton{border:none;background:transparent;color:palette(text);"
            "border-radius:8px;padding:5px 2px;font-size:13px;}" /* v0.95.0 W16b：86px 半宽装下 图标+4字 */
            "QToolButton:hover{background:palette(alternate-base);}");
        m_sideAboutBtn = new QToolButton;
        m_sideAboutBtn->setToolButtonStyle(Qt::ToolButtonTextUnderIcon); /* W17#3：文在图下 */
        m_sideAboutBtn->setCursor(Qt::PointingHandCursor);
        m_sideAboutBtn->setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Fixed);
        m_sideAboutBtn->setStyleSheet(sBotQss);
        m_sideAboutBtn->setText(hr::l10n::s("软件说明", "About"));
        connect(m_sideAboutBtn, &QToolButton::clicked, this, [this] { showAboutDialog(); });
        m_sideNightBtn = new QToolButton;
        m_sideNightBtn->setToolButtonStyle(Qt::ToolButtonTextUnderIcon); /* W17#3：文在图下 */
        m_sideNightBtn->setCursor(Qt::PointingHandCursor);
        m_sideNightBtn->setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Fixed);
        m_sideNightBtn->setStyleSheet(sBotQss);
        connect(m_sideNightBtn, &QToolButton::clicked, this, [this] { toggleDark(); });
        const bool darkNow = hr::Icons::isDark();
        m_sideNightBtn->setText(darkNow ? hr::l10n::s("日间模式", "Day mode")
                                        : hr::l10n::s("夜间模式", "Night mode"));
        m_sideNightBtn->setIcon(hr::Icons::get(
            darkNow ? QString::fromLatin1("glyphicons_232_sun")
                    : QString::fromLatin1("glyphicons_231_moon"),
            hr::Icons::tint(), 18));
        hr::Icons::bind(m_sideAboutBtn, QString::fromLatin1("glyphicons_636_circle_info"), 18);
        QWidget *sBot = new QWidget;
        QHBoxLayout *sbl = new QHBoxLayout(sBot);
        sbl->setContentsMargins(0, 4, 0, 2);
        sbl->setSpacing(6);
        sbl->addWidget(m_sideAboutBtn, 1);
        sbl->addWidget(m_sideNightBtn, 1);
        sideLay->addWidget(sBot);
        evt("side_bottom", "row=about|night");
    }

    buildHomePage(); /* U7：首页六区块 + FAB */

    m_browsePage = new QWidget;
    buildBrowsePage(); /* U5：我的文件真页（本地位置+网络分组） */

    buildPrefsPage(); /* U4：真偏好页（一级分组 + 二级组逐项） */

    buildRemotePage(); /* U5：网上书库独立页 */

    m_stack = new QStackedWidget;
    m_stack->addWidget(m_homePage);   /* 0 首页 */
    m_stack->addWidget(m_libPage);    /* 1 书库 */
    m_stack->addWidget(m_browsePage); /* 2 我的文件 */
    m_stack->addWidget(m_prefsPage);  /* 3 偏好 */
    m_stack->addWidget(m_remotePage); /* 4 网上书库（侧栏进入，U5） */
    buildBookmarksPage();             /* U7：书签笔记聚合页 */
    buildFavPage();                   /* U7：珍藏页（封面网格） */
    m_stack->addWidget(m_bmPage);     /* 5 书签笔记（U7） */
    m_stack->addWidget(m_favPage);    /* 6 我的珍藏（U7） */

    /* v0.93.0 W14：原 body 横布局（侧栏|内容）撤并进 central 装配——侧栏全高 */

    m_tabBar = new QWidget; /* v0.75.0：包壳，阅读时整体隐藏 */
    m_tabBar->setObjectName(QString::fromLatin1("hrTabBar")); /* v0.95.0 W16：主题色底 */
    m_tabBar->setFixedHeight(52); /* v0.94.0 W15：压低底栏——比图标+名称略高即可（原 68 上下空白过多，用户点名） */
    QHBoxLayout *tabs = new QHBoxLayout(m_tabBar);
    tabs->setContentsMargins(6, 2, 6, 2);
    /* v1.0.0：同步安卓——书库→书架、我的文件→我的书库 */
    const QStringList tabNames = {
        hr::l10n::s("首页", "Home"), hr::l10n::s("书架", "Bookshelf"),
        hr::l10n::s("我的书库", "My library"), hr::l10n::s("偏好", "Settings"),
    };
    /* v0.76.0 V1：底 tab 图标化（与安卓 glyphicons 同形），56dp 高 + 图标上/文字下 */
    static const char *kTabIcons[4] = {"glyphicons_21_home", "glyphicons_422_book_library",
                                       "glyphicons_336_folder", "glyphicons_5_settings"};
    for (int i = 0; i < 4; i++)
    {
        QToolButton *tb = new QToolButton;
        tb->setToolButtonStyle(Qt::ToolButtonTextUnderIcon);
        tb->setText(tabNames.at(i)); /* v0.90.0 W11：图标下显示名称（首页/书架/我的书库/偏好） */
        tb->setIconSize(QSize(22, 22));
        tb->setCheckable(true);
        tb->setAutoRaise(true);
        tb->setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Expanding); /* v0.86.0 W5 H1：四 tab 等宽铺满 */
        {
            QFont tf = tb->font(); /* v0.77.0 V2：tab 标签 11px（七档） */
            tf.setPixelSize(hr::Ui::fsPx(11));
            tb->setFont(tf);
        }
        m_tabBtns.append(tb);
        tabs->addWidget(tb);
        connect(tb, &QToolButton::clicked, this, [this, i] { showTab(i); });
        hr::Icons::bindTab(tb, QString::fromLatin1(kTabIcons[i]), 22); /* v0.86.0 W5 H1 */
    }
    /* v0.95.0 W16：底栏底色=主题色（palette(highlight) 随主题色预设联动，用户点名）；
     * 文字白色、选中=深底+加粗（原选中用 palette(highlight) 文字会与新底色同色） */
    m_tabBar->setStyleSheet(QString::fromLatin1(
        "QWidget#hrTabBar{background:palette(highlight);}"
        "QToolButton{border:none;background:transparent;color:#ffffff;}"
        "QToolButton:hover{background:rgba(255,255,255,50);border-radius:6px;}"
        "QToolButton:checked{background:rgba(0,0,0,60);color:#ffffff;font-weight:bold;}"));

    /* v0.93.0 W14：侧栏直通窗口底边，四个底 tab 靠右移——底栏只盖内容区（用户点名） */
    QWidget *central = new QWidget;
    QHBoxLayout *outer = new QHBoxLayout(central);
    outer->setContentsMargins(0, 0, 0, 0);
    outer->setSpacing(0);
    outer->addWidget(m_sideWrap); /* v0.78.0 V3：侧栏壳（v0.93.0 W14 起全高到底） */
    QVBoxLayout *rightCol = new QVBoxLayout;
    rightCol->setContentsMargins(0, 0, 0, 0);
    rightCol->setSpacing(0);
    rightCol->addWidget(m_stack, 1);
    rightCol->addWidget(m_tabBar);
    outer->addLayout(rightCol, 1);
    setCentralWidget(central);

    connect(btnAdd, &QPushButton::clicked, this, &LibraryWindow::onManageDirs);
    connect(btnScan, &QPushButton::clicked, this, &LibraryWindow::onRescan);
    connect(m_search, &QLineEdit::textChanged, this, [this] { loadTable(); });
    connect(m_search, &QLineEdit::editingFinished, this, [this]
    {
        QSettings st(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
        /* U4：记住搜索词可关 */
        if (st.value("reader/rememberSearch", true).toBool())
            st.setValue("lib/lastQuery", m_search->text().trimmed());
    });
    connect(m_statusC, static_cast<void (QComboBox::*)(int)>(&QComboBox::currentIndexChanged),
            this, [this](int) { loadTable(); });
    connect(m_viewC, static_cast<void (QComboBox::*)(int)>(&QComboBox::currentIndexChanged),
            this, [this](int)
    {
        const QString v = m_viewC->currentData().toString();
        QSettings st(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
        st.setValue("lib/view", v);
        if (v == "list" || v == "table" || v == "gauthor" || v == "gseries" ||
            v == "glang" || v == "gformat") /* v0.82.0 W1：树视图族（列表/简表/4 分组） */
        {
            m_tree->setVisible(true);
            m_cover->setVisible(false);
            m_shelfWrap->setVisible(false);
        }
        else if (v == "shelf")
        {
            m_tree->setVisible(false);
            m_cover->setVisible(false);
            m_shelfWrap->setVisible(true); /* U8：书架视图 */
        }
        else
        {
            m_tree->setVisible(false);
            m_shelfWrap->setVisible(false);
            m_cover->setVisible(true);
            /* v0.102.0 fix16：封面墙尺寸统一由 ui/coverSize 驱动——此前此处硬编码，
               构造期（setCurrentIndex 触发本回调）与每次切视图都会覆盖滑条配置，
               重启后封面宽度"回到 132"；改读同一偏好（与构造器/滑条回调同公式） */
            {
                QSettings stv(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
                const int wv = qBound(110, stv.value("ui/coverSize", 132).toInt(), 280);
                m_cover->setIconSize(QSize(wv, wv * 186 / 132));
                m_cover->setGridSize(QSize(wv + 18, wv * 186 / 132 + 66));
            }
            if (v == "cover" && m_cover->count() > 0) startCoverGen();
        }
        loadTable();
    });
    connect(btnFav, &QPushButton::toggled, this, [this](bool on)
    {
        m_onlyFav = on;
        loadTable();
    });
    connect(btnAssoc, &QPushButton::clicked, this, &LibraryWindow::onAssoc);
    connect(btnSync, &QPushButton::clicked, this, &LibraryWindow::onSync);
    connect(btnRemote, &QPushButton::clicked, this, &LibraryWindow::onRemote);
    connect(btnOpen, &QPushButton::clicked, this, [this]
    {
        QTreeWidgetItem *it = m_tree->currentItem();
        if (it) openBookOrRemote(it->data(0, Qt::UserRole).toString());
    });
    connect(m_tree, &QTreeWidget::itemDoubleClicked, this, &LibraryWindow::onOpenBook);
    /* v0.104.0（P1-6）：单击选中即预开 */
    connect(m_tree, &QTreeWidget::itemClicked, this, [this](QTreeWidgetItem *it, int)
    {
        if (it) preOpenBook(it->data(0, Qt::UserRole).toString());
    });
    connect(m_cover, &QListWidget::itemClicked, this, [this](QListWidgetItem *it)
    {
        if (it) preOpenBook(it->data(Qt::UserRole).toString());
    }); /* v0.104.0（P1-6）：预开 */
    connect(m_cover, &QListWidget::itemDoubleClicked, this, [this](QListWidgetItem *it)
    {
        if (!it) return;
        evt("lib_open_book",
            "path=" + QFileInfo(it->data(Qt::UserRole).toString()).fileName().left(80));
        openBookOrRemote(it->data(Qt::UserRole).toString());
    });
    connect(btnStats, &QPushButton::clicked, this, &LibraryWindow::onStats);
    /* U2：封面 toggle 退役——"封面/网格"视图由 lib/view 接管（保持 ini 兼容） */
    connect(m_sortC, static_cast<void (QComboBox::*)(int)>(&QComboBox::currentIndexChanged),
            this, [this](int)
    {
        QSettings st(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
        st.setValue("lib/sort", m_sortC->currentData().toString());
        loadTable();
    });

    connect(m_side, &QListWidget::currentRowChanged, this, &LibraryWindow::onSide);
    /* v0.88.0 W7 DIM-2 修复：原 if(dark) toggleDark() 语义自反——ini=true 时启动又被切回亮 */
    setDarkMode(QSettings(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat)
                    .value("ui/dark", false).toBool(),
                false);
    showTab(1); /* 默认落书库页 */
    m_iconFillTimer = new QTimer(this);
    m_iconFillTimer->setInterval(25);
    connect(m_iconFillTimer, &QTimer::timeout, this, &LibraryWindow::pumpDeferredFill);
    m_scanDebounce = new QTimer(this);
    m_scanDebounce->setSingleShot(true);
    m_scanDebounce->setInterval(600);
    connect(m_scanDebounce, &QTimer::timeout, this, [this]
    {
        if (!m_scanning) scanAll(); /* 勾选变化 → 防抖后统一扫描入库并刷新 */
        scanRemoteSources();
    });
    refreshHome(); /* U7：首页数据首刷（evt home_render） */
    applyBlueLight(); /* U8：蓝光滤镜暖色叠加（ui/blueLight） */

    QSettings st0(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
    m_sortC->setCurrentIndex(qMax(0, m_sortC->findData(st0.value("lib/sort", "title").toString())));
    const QString savedView = st0.value("lib/view", "list").toString();
    m_viewC->setCurrentIndex(qMax(0, m_viewC->findData(savedView)));
    /* U4：记住搜索词可关（reader/rememberSearch，默认开） */
    if (st0.value("reader/rememberSearch", true).toBool())
        m_search->setText(st0.value("lib/lastQuery", QString()).toString());
    /* 兼容旧 lib/covers 开关：曾开封面 → 迁移为封面视图 */
    if (!st0.contains("lib/view") && st0.value("lib/covers", false).toBool())
        m_viewC->setCurrentIndex(m_viewC->findData("cover"));
    /* U4：显示左侧栏（ui/sideBar，默认开） */
    if (!st0.value("ui/sideBar", true).toBool()) m_sideWrap->hide();

    loadTable();
    if (QCoreApplication::arguments().contains("--libscan"))
        ; /* 无头模式不自动扫描（由 main 显式触发，避免与目录注入竞态） */
    else if (st0.value("lib/scanAtLaunch", true).toBool())
        scanAll(); /* U4：lib/scanAtLaunch（启动时扫描新书，默认开） */

    /* P1 适配（E6）：定时自动同步（web/syncEnabled + web/intervalMin，60s tick 门控） */
    {
        QTimer *syncT = new QTimer(this);
        syncT->setInterval(60 * 1000);
        connect(syncT, &QTimer::timeout, this, [this]
        {
            QSettings sta(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
            if (!sta.value("web/syncEnabled", false).toBool()) return;
            const int iv = sta.value("web/intervalMin", 0).toInt();
            if (iv <= 0) return;
            const long long last = sta.value("web/lastSyncAt", 0).toLongLong();
            if (QDateTime::currentMSecsSinceEpoch() - last < iv * 60000LL) return;
            /* DavCreds/loadCreds 定义在 ctor 之后——此处直读凭据键（DPAPI 解密） */
            const QString su = sta.value("web/webdav/url").toString();
            if (su.isEmpty()) return;
            const QString sun = sta.value("web/webdav/user").toString();
            const QString sup = hr::unprotectSecret(sta.value("web/webdav/pass").toString());
            const QString sdir = sta.value("web/webdav/dir", "HowRead").toString();
            const QString spol = sta
                                      .value("web/webdav/policy",
                                             sta.value("web/policy", "newer").toString())
                                      .toString();
            sta.setValue("web/lastSyncAt", QDateTime::currentMSecsSinceEpoch());
            if (g_syncBusy.exchange(true)) return;
            evt("auto_sync_start", QString("iv=%1").arg(iv));
            QThreadPool::globalInstance()->start(QRunnable::create([this, su, sun, sup, sdir, spol]
            {
                const hr::SyncResult r = hr::androidSync(su, sun, sup, sdir, spol);
                QMetaObject::invokeMethod(this, [this, r]
                {
                    evt("auto_sync_done", QString("ok=%1 err=%2")
                                              .arg(r.ok ? 1 : 0)
                                              .arg(r.error.left(60)));
                }, Qt::QueuedConnection);
                g_syncBusy = false;
            }));
        });
        syncT->start();
    }

    /* U1：启动打开上次读的书（reader/openLastOnStart，默认关；偏好行 U4 接线） */
    QSettings st2(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
    if (st2.value("reader/openLastOnStart", false).toBool())
    {
        const QString last = Db::lastRecent();
        if (!last.isEmpty() && QFileInfo::exists(last))
            QTimer::singleShot(0, this, [this, last]
            {
                evt("open_last", "path=" + last.left(100));
                openBookOrRemote(last);
            });
    }
}

/* 封面缓存：dataDir/cache/covers/<md5(路径#大小#mtime)>.png（源变化自动重建） */
static QString coverPathFor(const QString &src)
{
    if (src.startsWith(QString::fromLatin1("remote://")))
    {
        /* 缺陷#4：远程书封面键 = r-<md5(path)>.png（ReaderWindow 首渲时写入） */
        return hr::dataDir() + "/cache/covers/r-" +
               QString::fromLatin1(QCryptographicHash::hash(src.toUtf8(),
                                                            QCryptographicHash::Md5)
                                       .toHex().left(12)) + ".png";
    }
    const QFileInfo fi(src);
    const QString seed = fi.absoluteFilePath() + "#" + QString::number(fi.size()) + "#" +
                         QString::number(fi.lastModified().toMSecsSinceEpoch());
    const QString key = QString::fromLatin1(
        QCryptographicHash::hash(seed.toUtf8(), QCryptographicHash::Md5).toHex().left(12));
    return hr::dataDir() + "/cache/covers/" + key + ".png";
}

/* ---------- v0.107.2：封面位图内存缓存 ----------
 * 书库列表行/封面墙/首页瓦片/珍藏/书签卡在每次回库、启动、搜索、排序时都在
 * UI 线程重复"磁盘读 PNG + 平滑缩放"（132 本书一次可触发 150+ 次）——关书
 * 回库与启动卡顿主源。键=png|mtime|宽x高（mtime 入键：重扫重生成封面自动
 * 失效）；QImage 缓存线程安全，出缓存再转 QPixmap；代价按像素 kB，24MB LRU。 */
static QImage coverPngScaledImage(const QString &png, int w, int h)
{
    if (png.isEmpty() || w <= 0 || h <= 0 || !QFileInfo(png).isFile()) return QImage();
    const qint64 mt = QFileInfo(png).lastModified().toMSecsSinceEpoch();
    const QString key = QString::fromLatin1("%1|%2|%3x%4").arg(png).arg(mt).arg(w).arg(h);
    static QMutex s_mu;
    static QCache<QString, QImage> s_cache;
    QMutexLocker lk(&s_mu);
    if (s_cache.maxCost() == 0) s_cache.setMaxCost(24 * 1024); /* kB ≈ 24MB */
    if (QImage *hit = s_cache.object(key)) return *hit;
    lk.unlock();
    QImage img(png);
    if (img.isNull()) return QImage();
    img = img.scaled(w, h, Qt::KeepAspectRatioByExpanding,
                     Qt::SmoothTransformation).copy(0, 0, w, h);
    lk.relock();
    s_cache.insert(key, new QImage(img), qMax(1, img.width() * img.height() * 4 / 1024));
    return img;
}
static QPixmap coverPngScaled(const QString &png, int w, int h)
{
    const QImage img = coverPngScaledImage(png, w, h);
    return img.isNull() ? QPixmap() : QPixmap::fromImage(img);
}


/* ---------- v0.82.0 W1：文件名系列解析（对齐 Compose core/library/SeriesParser.kt，
 * 保守策略宁可漏判不误判；引擎无标准系列元数据字段，与 Compose 同口径） ---------- */
static bool titleHasCjk(const QString &t)
{
    for (QChar c : t)
    {
        const ushort u = c.unicode();
        if (u >= 0x4E00 && u <= 0x9FFF) return true;
    }
    return false;
}
static bool spIsYear(const QString &s)
{
    return s.size() == 4 && (s.startsWith(QLatin1String("19")) || s.startsWith(QLatin1String("20")));
}
static QString spTrimOf(const QString &s, const QString &chars)
{
    int a = 0, b = s.size() - 1;
    while (a <= b && chars.contains(s[a])) a++;
    while (b >= a && chars.contains(s[b])) b--;
    return s.mid(a, b - a + 1);
}
static QString spStrip(const QString &s)
{
    return spTrimOf(s, QString::fromUtf8(" -_\xC2\xB7[]()\xE3\x80\x90\xE3\x80\x91\xEF\xBC\x88\xEF\xBC\x89"));
}
static int spDigit(QChar c)
{
    switch (c.unicode())
    {
    case 0x4E00: return 1; /* 一 */
    case 0x4E8C: return 2; /* 二 */
    case 0x4E09: return 3; /* 三 */
    case 0x56DB: return 4; /* 四 */
    case 0x4E94: return 5; /* 五 */
    case 0x516D: return 6; /* 六 */
    case 0x4E03: return 7; /* 七 */
    case 0x516B: return 8; /* 八 */
    case 0x4E5D: return 9; /* 九 */
    }
    return -1;
}
static int spCnNum(const QString &s)
{
    bool ok = false;
    const int d = s.toInt(&ok);
    if (ok) return d;
    const QChar shi(0x5341); /* 十 */
    if (s.size() == 1) return s[0] == shi ? 10 : spDigit(s[0]);
    if (s.size() == 2)
    {
        if (s[0] == shi) return spDigit(s[1]) > 0 ? 10 + spDigit(s[1]) : -1;
        if (s[1] == shi) return spDigit(s[0]) > 0 ? spDigit(s[0]) * 10 : -1;
        return -1;
    }
    if (s.size() == 3 && s[1] == shi && spDigit(s[0]) > 0 && spDigit(s[2]) > 0)
        return spDigit(s[0]) * 10 + spDigit(s[2]);
    return -1;
}
/* 文件名系列模式：括号组带独立数字 / 第N卷册部集 / A-01-B / 尾部数字 / 尾部补零 / 头部数字取父目录 */
static void spParseSeries(const QString &path, const QString &title, QString *outSeries, int *outIdx)
{
    const QString name = title.trimmed();
    *outSeries = QString();
    *outIdx = -1;
    if (name.size() < 3) return;
    const QString dash = QString::fromUtf8("-\xE2\x80\x93\xE2\x80\x94"); /* - – — */
    const QString dashTrim = dash + QString::fromLatin1(" _\xC2\xB7");
    /* 1. 括号组内含独立数字：[系列 01] / （系列 3） / 【哈利波特05】 */
    static const std::pair<QString, QString> pairs[] = {
        {QString::fromLatin1("["), QString::fromLatin1("]")},
        {QString::fromUtf8("\xEF\xBC\x88"), QString::fromUtf8("\xEF\xBC\x89")}, /* （） */
        {QString::fromLatin1("("), QString::fromLatin1(")")},
        {QString::fromUtf8("\xE3\x80\x90"), QString::fromUtf8("\xE3\x80\x91")}, /* 【】 */
    };
    static const QRegularExpression numRe(QStringLiteral("(?<!\\d)(\\d{1,4})(?!\\d)"));
    for (const auto &pr : pairs)
    {
        const int i = name.indexOf(pr.first);
        if (i < 0) continue;
        const int j = name.indexOf(pr.second, i + 1);
        if (j <= i + 1) continue;
        const QString inner = name.mid(i + 1, j - i - 1);
        const QRegularExpressionMatch m = numRe.match(inner);
        if (!m.hasMatch()) continue;
        if (spIsYear(m.captured(1))) continue;
        const int idx = m.captured(1).toInt();
        QString series = spTrimOf(QString(inner).remove(m.captured(1)),
                                  QString::fromUtf8(" -_\xC2\xB7\xEF\xBC\x8C,"));
        if (series.size() < 2)
            series = spTrimOf(name.left(i) + name.mid(j + 1),
                              QString::fromUtf8(" -_\xC2\xB7\xEF\xBC\x8C,"));
        series = spStrip(series);
        if (series.size() >= 2)
        {
            *outSeries = series;
            *outIdx = idx;
            return;
        }
    }
    /* 2. 第N卷/册/部/集 */
    static const QRegularExpression volRe(
        QStringLiteral("([\xE4\xB8\x80\xE4\xBA\x8C\xE4\xB8\x89\xE5\x9B\x9B\xE4\xBA\x94\xE5\x85\xAD\xE4\xB8\x83\xE5\x85\xAB\xE4\xB9\x9D\xE5\x8D\x81\\d]{1,3})\\s*[\xE5\x8D\xB7\xE5\x86\x8C\xE9\x83\xA8\xE9\x9B\x86]"));
    static const QRegularExpression diRe(QStringLiteral("\xE7\xAC\xAC")); /* 第 */
    const int di = name.indexOf(diRe);
    if (di >= 0)
    {
        const QRegularExpressionMatch m = volRe.match(name, di + 1);
        if (m.hasMatch() && m.capturedStart() == di + 1)
        {
            const int idx = spCnNum(m.captured(1));
            const QString before = spTrimOf(name.left(di), QString::fromUtf8(" -_\xC2\xB7"));
            if (idx > 0 && before.size() >= 2)
            {
                *outSeries = spStrip(before);
                *outIdx = idx;
                return;
            }
        }
    }
    /* 3. 中划线段：系列名 - 01 - 书名 */
    static const QRegularExpression midRe(
        QStringLiteral("^\\s*(.{2,60}?)\\s*[-\xE2\x80\x93\xE2\x80\x94]\\s*(\\d{1,3})\\s*[-\xE2\x80\x93\xE2\x80\x94]"));
    {
        const QRegularExpressionMatch m = midRe.match(name);
        if (m.hasMatch() && !spIsYear(m.captured(2)))
        {
            *outSeries = m.captured(1).trimmed();
            *outIdx = m.captured(2).toInt();
            return;
        }
    }
    /* 4. 尾部数字（带分隔符） */
    static const QRegularExpression tailRe(
        QStringLiteral("^(.{2,60}?)\\s*[\\s_\\-\xE2\x80\x93\xE2\x80\x94]\\s*(\\d{1,3})$"));
    {
        const QRegularExpressionMatch m = tailRe.match(name);
        if (m.hasMatch() && !spIsYear(m.captured(2)))
        {
            *outSeries = spStrip(m.captured(1).trimmed());
            *outIdx = m.captured(2).toInt();
            return;
        }
    }
    /* 5. 尾部补零紧贴（仅 0 开头，避免 "Excel2007" 误判） */
    static const QRegularExpression padRe(QStringLiteral("^(.{2,60}?)(?<!\\d)(0\\d{1,2})$"));
    {
        const QRegularExpressionMatch m = padRe.match(name);
        if (m.hasMatch())
        {
            *outSeries = spStrip(m.captured(1).trimmed());
            *outIdx = m.captured(2).toInt();
            return;
        }
    }
    /* 6. 头部数字 → 父目录名当系列（"三体系列/01 xxx" 命名惯例） */
    static const QRegularExpression headRe(
        QStringLiteral("^\\s*(\\d{1,3})\\s*[\\s_\\-\xE2\x80\x93\xE2\x80\x94]\\s*(.+)$"));
    {
        const QRegularExpressionMatch m = headRe.match(name);
        if (m.hasMatch() && !spIsYear(m.captured(1)))
        {
            const QString dir = spTrimOf(QFileInfo(path).dir().dirName(),
                                         QString::fromLatin1(" -_"));
            if (dir.size() >= 2)
            {
                *outSeries = spStrip(dir);
                *outIdx = m.captured(1).toInt();
            }
        }
    }
}

void LibraryWindow::loadTable()
{
    const qint64 t0 = QDateTime::currentMSecsSinceEpoch(); /* v0.107.2：lib_table 打点 */
    m_tree->clear();
    m_iconTodo.clear();
    m_wallTodo.clear();
    m_wallRows.clear(); /* v0.109.0：清上一轮分批填充队列 */
    /* U2：一次性取进度，状态过滤用（path -> page/pageCount） */
    QHash<QString, QPair<int, int>> prog;
    for (const Db::ProgressRow &pr : Db::allProgress())
        prog.insert(pr.path, qMakePair(pr.page, pr.pageCount));
    const QString query = m_search ? m_search->text().trimmed() : QString();
    const QString status = m_statusC ? m_statusC->currentData().toString()
                                     : QString::fromLatin1("all");
    QList<Db::MetaRow> rows;
    /* U4：书库显示偏好（显示文件名/隐藏读完/启用格式过滤） */
    QSettings stP(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
    const bool showFn = stP.value("lib/showFileName", false).toBool();
    const bool useMeta = stP.value("lib/usePdfMeta", true).toBool();
    const bool serNum = stP.value("lib/seriesNumInTitle", true).toBool(); /* P1（A7） */
    const bool hideFin = stP.value("lib/hideFinished", false).toBool();
    for (const Db::MetaRow &r : Db::listLibrary())
    {
        if (!m_exts.contains(QFileInfo(r.path).suffix().toLower())) continue;
        if (m_onlyFav && !Db::isFavorite(r.path)) continue;
        if (!m_tagFilter.isEmpty()) /* v0.84.0 W3 D4：按标签过滤（逗号分隔字段精确匹配） */
        {
            bool tagHit = false;
            const QStringList ts = r.tags.split(QLatin1Char(','), Qt::SkipEmptyParts);
            for (QString t : ts)
                if ((t = t.trimmed()) == m_tagFilter) { tagHit = true; break; }
            if (!tagHit) continue;
        }
        if (!query.isEmpty() &&
            !r.title.contains(query, Qt::CaseInsensitive) &&
            !r.path.contains(query, Qt::CaseInsensitive))
            continue;
        /* P0 适配（D3）：hideFinished 全状态生效（对齐 WIN hideReadBooks） */
        const auto it = prog.constFind(r.path);
        const bool hasProg = (it != prog.constEnd());
        if (hideFin && hasProg)
        {
            const int pg = it->first + 1, pc = qMax(1, it->second);
            if (pg >= pc) continue;
        }
        if (status != "all")
        {
            if (!hasProg)
            {
                if (status != "unread") continue;
            }
            else
            {
                const int pg = it->first + 1, pc = qMax(1, it->second);
                if (status == "unread") continue;          /* 有进度≠未读 */
                if (status == "reading" && pg >= pc) continue;
                if (status == "finished" && pg < pc) continue;
            }
        }
        rows.append(r);
    }
    const QString sk = m_sortC ? m_sortC->currentData().toString()
                               : QString::fromLatin1("title");
    if (sk == "pages")
        std::sort(rows.begin(), rows.end(), [](const Db::MetaRow &a, const Db::MetaRow &b)
        { return a.pages > b.pages; });
    else if (sk == "size")
        std::sort(rows.begin(), rows.end(), [](const Db::MetaRow &a, const Db::MetaRow &b)
        { return a.size > b.size; });
    else if (sk == "mtime")
        std::sort(rows.begin(), rows.end(), [](const Db::MetaRow &a, const Db::MetaRow &b)
        { return a.mtime > b.mtime; });
    else if (sk == "format")
        std::sort(rows.begin(), rows.end(), [](const Db::MetaRow &a, const Db::MetaRow &b)
        {
            const QString ea = QFileInfo(a.path).suffix().toLower();
            const QString eb = QFileInfo(b.path).suffix().toLower();
            return ea == eb
                       ? (a.title.isEmpty() ? QFileInfo(a.path).fileName() : a.title)
                             .localeAwareCompare(b.title.isEmpty()
                                                     ? QFileInfo(b.path).fileName()
                                                     : b.title) < 0
                       : ea < eb;
        });
    else if (sk == "filename")
        std::sort(rows.begin(), rows.end(), [](const Db::MetaRow &a, const Db::MetaRow &b)
        {
            return QFileInfo(a.path).fileName().localeAwareCompare(
                       QFileInfo(b.path).fileName()) < 0;
        });
    else if (sk == "author") /* v0.82.0 W1 D2：无作者排末尾；P1 适配（A7）姓氏在前开关 */
        std::sort(rows.begin(), rows.end(), [](const Db::MetaRow &a, const Db::MetaRow &b)
        {
            const QString ta = a.title.isEmpty() ? QFileInfo(a.path).fileName() : a.title;
            const QString tb = b.title.isEmpty() ? QFileInfo(b.path).fileName() : b.title;
            if (a.author.isEmpty() != b.author.isEmpty()) return b.author.isEmpty();
            const bool snf = QSettings(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat)
                                 .value("lib/authorSurnameFirst", false).toBool();
            const auto key = [snf](const QString &au)
            {
                const int sp = au.indexOf(QLatin1Char(' '));
                return (snf && sp > 0) ? au.mid(sp + 1) + au.left(sp) : au;
            };
            const QString ka = key(a.author), kb = key(b.author);
            if (ka.compare(kb, Qt::CaseInsensitive) != 0)
                return ka.compare(kb, Qt::CaseInsensitive) < 0;
            return ta.localeAwareCompare(tb) < 0;
        });
    else if (sk == "series") /* v0.82.0 W1 D2：系列名→系列内序号→标题 */
        std::sort(rows.begin(), rows.end(), [](const Db::MetaRow &a, const Db::MetaRow &b)
        {
            const QString ta = a.title.isEmpty() ? QFileInfo(a.path).fileName() : a.title;
            const QString tb = b.title.isEmpty() ? QFileInfo(b.path).fileName() : b.title;
            if (a.series.isEmpty() != b.series.isEmpty()) return b.series.isEmpty();
            if (a.series.compare(b.series, Qt::CaseInsensitive) != 0)
                return a.series.compare(b.series, Qt::CaseInsensitive) < 0;
            if (a.seriesIdx != b.seriesIdx) return a.seriesIdx < b.seriesIdx;
            return ta.localeAwareCompare(tb) < 0;
        });
    else if (sk == "lang") /* v0.82.0 W1 D2：标题含 CJK=中文在前（Compose cjkTitle 口径） */
        std::sort(rows.begin(), rows.end(), [](const Db::MetaRow &a, const Db::MetaRow &b)
        {
            const QString ta = a.title.isEmpty() ? QFileInfo(a.path).fileName() : a.title;
            const QString tb = b.title.isEmpty() ? QFileInfo(b.path).fileName() : b.title;
            const bool ca = titleHasCjk(ta), cb = titleHasCjk(tb);
            if (ca != cb) return ca;
            return ta.localeAwareCompare(tb) < 0;
        });
    else
        std::sort(rows.begin(), rows.end(), [](const Db::MetaRow &a, const Db::MetaRow &b)
        {
            const QString na = a.title.isEmpty() ? QFileInfo(a.path).fileName() : a.title;
            const QString nb = b.title.isEmpty() ? QFileInfo(b.path).fileName() : b.title;
            return na.localeAwareCompare(nb) < 0;
        });
    /* v0.82.0 W1 D3：行构建 lambda 化 + 分组视图（组头=组名·数量，对齐 Compose GROUP_*） */
    const QString viewKey =
        m_viewC ? m_viewC->currentData().toString() : QString::fromLatin1("list");
    const bool tableView = (viewKey == QLatin1String("table"));
    const bool groupView = (viewKey == QLatin1String("gauthor") ||
                            viewKey == QLatin1String("gseries") ||
                            viewKey == QLatin1String("glang") ||
                            viewKey == QLatin1String("gformat"));
    m_tree->setIconSize(tableView ? QSize(0, 0) : QSize(40, 56));
    auto mkRowItem = [this, &prog, showFn, useMeta, tableView, serNum](QTreeWidgetItem *parent,
                                                               const Db::MetaRow &r)
        -> QTreeWidgetItem *
    {
        QString title = (showFn || !useMeta || r.title.isEmpty())
                            ? QFileInfo(r.path).fileName()
                            : r.title; /* U4：lib/showFileName·usePdfMeta */
        /* P1 适配（A7）：标题显示丛书号（对齐 WIN seriesNumInTitle） */
        if (serNum && !r.series.isEmpty() && r.seriesIdx >= 0)
            title += QString(hr::l10n::s("（%1 #%2）", " (%1 #%2)"))
                         .arg(r.series)
                         .arg(r.seriesIdx);
        const auto pit = prog.constFind(r.path);
        if (pit != prog.constEnd())
        {
            const int pg = pit->first + 1, pc = qMax(1, pit->second);
            if (pg < pc) /* 在读：标题缀进度（Compose subLine 口径） */
                title += QString(hr::l10n::s("　·%1/%2", "  ·%1/%2")).arg(pg).arg(pc);
        }
        const QStringList cols = QStringList()
            << title << QString::number(r.pages) << QString::number(r.size / 1024) + " KB"
            << QDir::toNativeSeparators(r.path);
        QTreeWidgetItem *it =
            parent ? new QTreeWidgetItem(parent, cols) : new QTreeWidgetItem(m_tree, cols);
        if (!tableView)
            m_iconTodo.append(qMakePair(it, qMakePair(r.path, title))); /* v0.109.0：图标分批填 */
        it->setData(0, Qt::UserRole, r.path);
        return it;
    };
    int n = 0;
    if (groupView)
    {
        std::map<QString, QVector<int>> groups;
        for (int i = 0; i < rows.size(); i++)
        {
            const Db::MetaRow &r = rows[i];
            QString k;
            if (viewKey == QLatin1String("gauthor"))
                k = r.author.isEmpty() ? hr::l10n::s("未知作者", "Unknown") : r.author;
            else if (viewKey == QLatin1String("gseries"))
                k = r.series.isEmpty() ? hr::l10n::s("无系列", "No series") : r.series;
            else if (viewKey == QLatin1String("glang"))
                k = titleHasCjk(r.title.isEmpty() ? QFileInfo(r.path).fileName() : r.title)
                        ? hr::l10n::s("中文", "Chinese")
                        : hr::l10n::s("其他", "Other");
            else
            {
                k = QFileInfo(r.path).suffix().toUpper();
                if (k.isEmpty()) k = QString::fromLatin1("?");
            }
            groups[k].append(i);
        }
        for (const auto &g : groups)
        {
            QTreeWidgetItem *gi = new QTreeWidgetItem(
                m_tree, QStringList() << g.first + QString::fromUtf8(" \xC2\xB7 ")
                                            + QString::number(g.second.size()));
            QFont gf = gi->font(0);
            gf.setBold(true);
            gi->setFont(0, gf);
            gi->setFirstColumnSpanned(true);
            for (int i : g.second) mkRowItem(gi, rows[i]);
            gi->setExpanded(true);
            n += int(g.second.size());
        }
    }
    else
    {
        for (const Db::MetaRow &r : rows)
        {
            mkRowItem(nullptr, r);
            n++;
        }
    }
    m_lbStat->setText(QString(hr::l10n::s("%1 本", "%1 books")).arg(n));
    /* 封面墙镜像（同排序行集；缺图标项由 startCoverGen 异步补）。
     * v0.108.1：仅封面/网格视图重建——列表族/书架视图墙不可见，84 卡
     * 整树重建 ~0.5s 全落在关书回库关键链上；切到墙视图由视图回调
     * 设可见后 loadTable 自然重建，不丢数据。 */
    if (m_cover && (viewKey == QLatin1String("cover") || viewKey == QLatin1String("grid")))
    {
        m_cover->clear();
        QSet<QString> wallFavs; /* v0.107.2：收藏一次取齐（原每卡一次 SQL） */
        for (const QString &f : Db::listFavorites())
            wallFavs.insert(f);
        const QSize cis = m_cover->iconSize();
        m_wallCw = qMax(80, cis.width());
        m_wallCh = qMax(110, cis.height());
        for (const Db::MetaRow &r : rows)
        {
            const QString png = coverPathFor(r.path);
            const bool remote = r.path.startsWith(QString::fromLatin1("remote://"));
            const bool hasCover = remote || QFileInfo(png).isFile();
            QListWidgetItem *it = new QListWidgetItem(m_cover);
            it->setData(Qt::UserRole, r.path);
            it->setData(Qt::UserRole + 3, hasCover ? 1 : 0); /* W8：封面就绪标记 */
            m_wallRows.append(r);
            m_wallTodo.append(it); /* v0.109.0：卡片分批挂载 */
        }
        m_wallFavs = wallFavs;
        m_wallProg = prog;
        m_iconFillTimer->start(); /* v0.109.0：卡片分批挂，关书回库/冷启动不整段卡 */
        startCoverGen(); /* v0.108.1：墙内缺封面在此补（视图回调 count>0 前置在首次切换时不成立） */
    }
    /* U8：书架视图镜像（同排序行集；仅书架可见时构建） */
        /* P2 适配（A1）：书架只显示勾选目录下的书（对齐 WIN shelf 归属；无勾选=全在架） */
        QSet<QString> shelfDirs;
        for (const Db::ScanDirRow &dr : Db::scanDirRows())
            if (dr.shelf)
                shelfDirs.insert(QDir::fromNativeSeparators(dr.path).toLower());
        auto underShelf = [&shelfDirs](const QString &p)
        {
            if (shelfDirs.isEmpty()) return true;
            const QString lp = QDir::fromNativeSeparators(p).toLower();
            for (const QString &sd : shelfDirs)
                if (lp == sd || lp.startsWith(sd + "/")) return true;
            return false;
        };
    if (m_shelf && m_shelfWrap && !m_shelfWrap->isHidden())
    {
        m_shelf->books.clear();
        m_shelf->pct.clear();
        for (const Db::MetaRow &r : rows)
        {
            const QString title =
                r.title.isEmpty() ? QFileInfo(r.path).fileName() : r.title;
            int spct = 0; /* v0.81.0 V6：书架卡百分比徽标 */
            const auto pit = prog.constFind(r.path);
            if (pit != prog.constEnd() && pit->second > 0)
                spct = (pit->first + 1) * 100 / pit->second;
            m_shelf->pct.insert(r.path, spct);
            if (underShelf(r.path))
                m_shelf->books.append(qMakePair(r.path, title)); /* P2（A1） */
        }
        m_shelf->rebuild();
        evt("shelf_render", QString("n=%1").arg(m_shelf->books.size()));
    }
    evt("lib_table", QString("rows=%1 ms=%2").arg(n).arg(
        QDateTime::currentMSecsSinceEpoch() - t0)); /* v0.107.2：关书回库/启动耗时定位 */
}

/* v0.109.0：封面墙卡片挂载（自 loadTable 抽出，分批填充用） */
void LibraryWindow::wallAttachCard(const Db::MetaRow &r, QListWidgetItem *it)
{
    if (!it || !m_cover) return;
    const QString title = r.title.isEmpty() ? QFileInfo(r.path).fileName() : r.title;
    const QString png = coverPathFor(r.path);
    const bool remote = r.path.startsWith(QString::fromLatin1("remote://"));
    /* v0.89.0 W8 LIB-2/4/5：封面墙卡片化——圆角封面+收藏星标+书名+作者·进度 */
    QPixmap pm;
    if (remote)
        pm = coverTile(r.path, title, m_wallCw, m_wallCh);
    else if (QFileInfo(png).isFile())
        pm = coverPngScaled(png, m_wallCw, m_wallCh);
    else
        pm = coverTileBase(r.path, title, m_wallCw, m_wallCh);
    pm = w8RoundedCover(pm, m_wallFavs.contains(r.path));
    it->setIcon(QIcon(pm));
    QWidget *card = new QWidget;
    card->setStyleSheet(QString::fromLatin1("background:transparent;"));
    QVBoxLayout *cl = new QVBoxLayout(card);
    cl->setContentsMargins(2, 2, 2, 2);
    cl->setSpacing(2);
    QLabel *cv = new QLabel;
    cv->setPixmap(pm);
    cv->setAlignment(Qt::AlignHCenter | Qt::AlignTop);
    cv->setStyleSheet(QString::fromLatin1("background:transparent;"));
    cl->addWidget(cv);
    QLabel *t1 = new QLabel(fontMetrics().elidedText(title, Qt::ElideRight, m_wallCw));
    t1->setAlignment(Qt::AlignHCenter);
    t1->setStyleSheet(QString::fromLatin1(
        "color:palette(text); font-size:%1px; background:transparent;")
                          .arg(hr::Ui::fsPx(11)));
    cl->addWidget(t1);
    int pg = 0, pc = 0;
    const auto wpit = m_wallProg.constFind(r.path);
    const bool hasProg = (wpit != m_wallProg.constEnd());
    if (hasProg) { pg = wpit->first; pc = wpit->second; }
    QString sub = r.author;
    if (sub.isEmpty())
        sub = hr::l10n::s("未知作者", "Unknown author");
    if (hasProg && pc > 0)
        sub += QString::fromUtf8(" \xC2\xB7 ") +
               QString(hr::l10n::s("%1%", "%1%")).arg((pg + 1) * 100 / pc);
    QLabel *t2 = new QLabel(fontMetrics().elidedText(sub, Qt::ElideRight, m_wallCw));
    t2->setAlignment(Qt::AlignHCenter);
    t2->setStyleSheet(QString::fromLatin1(
        "color:palette(mid); font-size:%1px; background:transparent;")
                          .arg(hr::Ui::fsPx(10)));
    cl->addWidget(t2);
    m_cover->setItemWidget(it, card);
}

/* v0.109.0：书库图标/墙卡分批填充泵——关书回库与冷启动的关键链只建行文本，
 * 位图解码与卡片合成在 25ms 节拍里分批补齐，不整段冻结界面 */
void LibraryWindow::pumpDeferredFill()
{
    int tn = 0;
    while (tn < 20 && !m_iconTodo.isEmpty())
    {
        const QPair<QTreeWidgetItem *, QPair<QString, QString>> t = m_iconTodo.takeFirst();
        if (t.first)
            t.first->setIcon(0, QIcon(coverTile(t.second.first, t.second.second, 40, 56)));
        tn++;
    }
    int wn = 0;
    while (wn < 4 && !m_wallTodo.isEmpty())
    {
        QListWidgetItem *it = m_wallTodo.takeFirst();
        const Db::MetaRow r = m_wallRows.takeFirst();
        wallAttachCard(r, it);
        wn++;
    }
    if (m_iconTodo.isEmpty() && m_wallTodo.isEmpty() && m_iconFillTimer)
        m_iconFillTimer->stop();
}

void LibraryWindow::onAddDir() { onManageDirs(); } /* U2：转发目录管理对话框 */

void LibraryWindow::onRescan() { scanAll(); }

/* P1 适配（A5）：Calibre metadata.opf 标题（同目录 metadata.opf / 书名.opf 的 dc:title；
 * 对齐 WIN Scanner.calibreOpfTitle） */
static QString calibreOpfTitle(const QString &bookPath)
{
    const QDir d = QFileInfo(bookPath).dir();
    const QStringList cands = {
        d.filePath(QString::fromLatin1("metadata.opf")),
        d.filePath(QFileInfo(bookPath).completeBaseName() + QString::fromLatin1(".opf"))};
    for (const QString &opf : cands)
    {
        QFile f(opf);
        if (!f.open(QIODevice::ReadOnly)) continue;
        const QByteArray x = f.read(262144);
        f.close();
        QRegularExpression re(QString::fromLatin1("<dc:title[^>]*>([^<]+)</dc:title>"),
                              QRegularExpression::CaseInsensitiveOption);
        const QRegularExpressionMatch m = re.match(QString::fromUtf8(x));
        if (m.hasMatch())
        {
            const QString t = m.captured(1).trimmed();
            if (!t.isEmpty()) return t;
        }
    }
    return QString();
}

void LibraryWindow::scanAll(const QStringList &onlyDirs)
{
    if (m_scanning) return;
    m_scanning = true;
    if (m_scanLb) QMetaObject::invokeMethod(m_scanLb, "show", Qt::QueuedConnection);
    /* v0.91.0 W12：可只扫指定目录（我的文件本地行“扫描书籍”） */
    /* v0.110.0：只扫勾选目录（未勾选目录的书由 pruneMissing 级联移出书库） */
    QStringList dirs;
    if (!onlyDirs.isEmpty())
        dirs = onlyDirs;
    else
        for (const Db::ScanDirRow &dr : Db::scanDirRows())
            if (dr.shelf) dirs << dr.path;
    const QStringList exts = m_exts;
    qint64 t0 = QDateTime::currentMSecsSinceEpoch();
    QThreadPool::globalInstance()->start(QRunnable::create([this, dirs, exts, t0, onlyDirs]
    {
        /* 一次载入已有清单：path->mtime，mtime 相同则跳过重开 */
        QHash<QString, long long> known;
        for (const Db::MetaRow &r : Db::listLibrary())
            known.insert(r.path, r.mtime);
        int added = 0, updated = 0;
        /* U4：.nomedia 跳过开关（lib/nomediaSkip，默认开） */
        const bool noMediaSkip =
            QSettings(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat)
                .value("lib/nomediaSkip", true)
                .toBool();
        QString lastDir;
        bool lastSkip = false;
        QStringList newFiles; /* v0.110.3：新登记书 → 后台引擎探测补页数 */
        for (const QString &dir : dirs)
        {
            QDirIterator it(dir, QDir::Files, QDirIterator::Subdirectories);
            while (it.hasNext())
            {
                const QString p = it.next();
                const QString ext = QFileInfo(p).suffix().toLower();
                if (!exts.contains(ext)) continue;
                /* U4：.nomedia 目录跳过（lib/nomediaSkip；目录级缓存判定） */
                if (noMediaSkip)
                {
                    const QString fdir = QFileInfo(p).absolutePath();
                    if (fdir != lastDir)
                    {
                        lastDir = fdir;
                        lastSkip = QFileInfo::exists(fdir + "/.nomedia");
                    }
                    if (lastSkip) continue;
                }
                QFileInfo fi(p);
                const long long mt = fi.lastModified().toMSecsSinceEpoch();
                const bool isKnown = known.contains(p);
                if (isKnown && known.value(p) == mt) continue;
                /* v0.110.3：扫描只登记（标题=文件名、页数 0），引擎探测移交后台
                 * 逐本补齐——云盘占位文件一开就下载、大 mobi 还会触发转换，
                 * 64 本新书曾让扫描卡 140s（scan_done ms=140502 实测） */
                QString ser;
                int sidx = -1;
                spParseSeries(p, QFileInfo(p).completeBaseName(), &ser, &sidx);
                Db::upsertFileMeta(p, QFileInfo(p).completeBaseName(), 0,
                                   fi.size(), mt, QString(), ser, sidx);
                newFiles.append(p);
                if (isKnown) updated++;
                else added++;
            }
        }
        /* P0 适配（A3）：prune——文件/目录消失后级联清理+墓碑（全量扫描才做；对齐 WIN） */
        if (onlyDirs.isEmpty())
        {
            QString pd;
            /* v0.110.2：扫描只补增+清理文件消失的书；勾选治理（未勾选移出）
             * 改在勾选变化/刷新点击时同步生效——扫描中按旧快照删书曾把勾选
             * 目录的书一并误删（用户实测），且云盘慢扫时移除迟到数十秒 */
            if (Db::pruneMissing(dirs, &pd))
                evt("scan_prune", pd.isEmpty() ? QString("pruned=0") : pd);
        }
        const qint64 ms = QDateTime::currentMSecsSinceEpoch() - t0;
        QMetaObject::invokeMethod(this, [this, added, updated, ms, dirs, newFiles]
        {
            loadTable();
            m_scanning = false;
            if (m_scanLb) QMetaObject::invokeMethod(m_scanLb, "hide", Qt::QueuedConnection);
            evt("scan_done", QString("dirs=%1 added=%2 updated=%3 ms=%4")
                                 .arg(dirs.size()).arg(added).arg(updated).arg(ms));
            /* v0.110.3：引擎探测移交后台（新登记书 + 历史 0 页书，单次限 200 本） */
            QStringList probe = newFiles;
            for (const Db::MetaRow &r : Db::listLibrary())
                if (r.pages <= 0 && !r.path.startsWith(QString::fromLatin1("remote://"))
                    && QFileInfo(r.path).isFile() && !probe.contains(r.path))
                    probe.append(r.path);
            if (probe.size() > 200) probe = probe.mid(0, 200);
            startScanProbe(probe);
        }, Qt::QueuedConnection);
    }));
}

void LibraryWindow::onOpenBook(QTreeWidgetItem *item, int)
{
    if (!item) return;
    const QString p = item->data(0, Qt::UserRole).toString();
    if (p.isEmpty()) /* v0.82.0 W1：分组组头行——双击=展开/折叠 */
    {
        item->setExpanded(!item->isExpanded());
        return;
    }
    evt("lib_open_book", "path=" + QFileInfo(p).fileName().left(80));
    openBookOrRemote(p);
}

void LibraryWindow::onToggleFav()
{
    QTreeWidgetItem *it = m_tree->currentItem();
    if (!it) return;
    const QString p = it->data(0, Qt::UserRole).toString();
    Db::setFavorite(p, !Db::isFavorite(p));
    loadTable();
}

/* U0 修复#1 接线 + U2 菜单扩 8 项（对齐 Compose BookMenu；标签/AI 简介随 U6/U7 填实） */
void LibraryWindow::onTreeMenu(const QPoint &pos)
{
    QTreeWidgetItem *it = m_tree->itemAt(pos);
    if (!it) return;
    const QList<QTreeWidgetItem *> sel = m_tree->selectedItems();
    const bool multi = sel.size() > 1;
    QStringList paths;
    for (QTreeWidgetItem *s : sel) paths << s->data(0, Qt::UserRole).toString();
    const QString p = it->data(0, Qt::UserRole).toString();
    const bool fav = Db::isFavorite(p);
    const bool isRm = !multi && p.startsWith(QString::fromLatin1("remote://"));
    QMenu menu(this);
    QAction *infoAct = multi ? nullptr
                             : menu.addAction(hr::l10n::s("文件信息", "File info"));
    QAction *tagAct = multi ? nullptr : menu.addAction(hr::l10n::s("标签…", "Tags…"));
    /* P1 适配（A8）：远程书 guard——AI 简介/打开文件夹/删除文件仅本地书 */
    QAction *aiAct = (multi || isRm) ? nullptr
                                     : menu.addAction(hr::l10n::s("AI 简介", "AI intro"));
    QAction *folderAct = (multi || isRm)
                             ? nullptr
                             : menu.addAction(hr::l10n::s("打开所在文件夹", "Open folder"));
    QAction *favAct = menu.addAction(multi
                                         ? QString(hr::l10n::s("收藏所选(%1)", "Favoritize (%1)"))
                                               .arg(sel.size())
                                         : (fav ? hr::l10n::s("取消收藏", "Unfavorite")
                                                : hr::l10n::s("加入收藏", "Favorite")));
    QAction *rmLibAct = menu.addAction(multi
                                           ? QString(hr::l10n::s("移出书库(%1)", "Remove (%1)"))
                                                 .arg(sel.size())
                                           : hr::l10n::s("移出书库", "Remove from library"));
    QAction *delAct = isRm ? nullptr
                           : menu.addAction(hr::l10n::s("删除文件…", "Delete files…"));
    if (delAct) delAct->setData(QString("red"));
    menu.addSeparator();
    menu.addAction(hr::l10n::s("取消", "Cancel"));
    QAction *act = menu.exec(m_tree->viewport()->mapToGlobal(pos));
    if (!act) return;
    if (act == favAct)
    {
        const bool turnOn = multi ? true : !fav;
        for (const QString &x : paths) Db::setFavorite(x, turnOn);
        evt("lib_fav", QString("on=%1 n=%2").arg(turnOn ? 1 : 0).arg(paths.size()));
        loadTable();
    }
    else if (act == rmLibAct)
    {
        if (QMessageBox::question(
                this, "HowRead Qt",
                QString(hr::l10n::s("把 %1 本移出书库（保留文件）？", "Remove %1 from library (keep files)?"))
                    .arg(paths.size())) != QMessageBox::Yes)
            return;
        for (const QString &x : paths) Db::removeBookFull(x); /* P0：级联+墓碑 */
        evt("lib_remove", QString("n=%1").arg(paths.size()));
        loadTable();
    }
    else if (act == delAct)
    {
        onDeleteFiles(paths);
    }
    else if (act == folderAct)
    {
        QDesktopServices::openUrl(QUrl::fromLocalFile(QFileInfo(p).absolutePath()));
    }
    else if (act == infoAct)
    {
        const Db::MetaRow *m = nullptr;
        for (const Db::MetaRow &r : Db::listLibrary())
            if (r.path == p) { m = &r; break; }
        const QFileInfo fi(p);
        /* P1 适配（A7）：描述行（lib/showDescription；AI 简介存库后可见） */
        const QString desc = Db::descriptionOf(p);
        const QString descLine =
            (QSettings(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat)
                     .value("lib/showDescription", true).toBool() &&
             !desc.isEmpty())
                ? QString("\n%1: %2").arg(hr::l10n::s("描述", "Description"), desc.left(200))
                : QString();
        QMessageBox::information(
            this, hr::l10n::s("文件信息", "File info"),
            QString("%1\n%2\n%3: %4\n%5: %6 KB\n%7: %8\n%9: %10%11")
                .arg(m && !m->title.isEmpty() ? m->title : fi.fileName())
                .arg(QDir::toNativeSeparators(p))
                .arg(hr::l10n::s("页数", "Pages"))
                .arg(m ? m->pages : 0)
                .arg(hr::l10n::s("大小", "Size"))
                .arg(fi.size() / 1024)
                .arg(hr::l10n::s("修改时间", "Modified"))
                .arg(fi.lastModified().toString("yyyy-MM-dd HH:mm"))
                .arg(hr::l10n::s("收藏", "Favorite"))
                .arg(Db::isFavorite(p) ? hr::l10n::s("是", "Yes")
                                       : hr::l10n::s("否", "No"))
                .arg(descLine));
    }
    else if (act == tagAct)
    {
        /* U6：标签体系（file_meta.tags，逗号分隔） */
        QDialog dg(this);
        dg.setWindowTitle(hr::l10n::s("标签", "Tags"));
        dg.resize(420, 150);
        QVBoxLayout *vl = new QVBoxLayout(&dg);
        vl->addWidget(new QLabel(hr::l10n::s("标签（逗号分隔）：", "Tags (comma separated):"),
                                 &dg));
        QLineEdit *ed = new QLineEdit(Db::tagsOf(p), &dg);
        ed->setPlaceholderText(hr::l10n::s("历史, 小说, 待读…", "history, novel, to-read…"));
        vl->addWidget(ed);
        QDialogButtonBox *bb2 =
            new QDialogButtonBox(QDialogButtonBox::Ok | QDialogButtonBox::Cancel, &dg);
        bb2->button(QDialogButtonBox::Ok)->setText(hr::l10n::s("保存", "Save"));
        bb2->button(QDialogButtonBox::Cancel)->setText(hr::l10n::s("取消", "Cancel"));
        vl->addWidget(bb2);
        connect(bb2, &QDialogButtonBox::accepted, &dg, &QDialog::accept);
        connect(bb2, &QDialogButtonBox::rejected, &dg, &QDialog::reject);
        if (dg.exec() == QDialog::Accepted)
        {
            Db::setTags(p, ed->text().trimmed());
            evt("lib_tags", "tags=" + ed->text().trimmed().left(40));
            statusBar()->showMessage(hr::l10n::s("标签已保存", "Tags saved"), 5000);
            refreshSideTags(); /* v0.84.0 W3 D4：标签区即时更新 */
        }
    }
    else if (act == aiAct)
    {
        /* U6：AI 简介（前 5 页→AI 概括→可存书库信息；异步不阻塞） */
        const AiConfig c = AiConfig::load();
        if (!c.usable())
        {
            hr::m4AiConfigDialog(this);
            return;
        }
        statusBar()->showMessage(hr::l10n::s("AI 简介生成中…", "Generating intro…"), 15000);
        evt("ai_intro_start", "path=" + p.left(80));
        QThreadPool::globalInstance()->start(QRunnable::create([this, c, p]
        {
            QString text;
            void *doc = hr_qt_open(hr::openablePath(p).toUtf8().constData());
            if (doc)
            {
                const int pages = qMin(5, hr_qt_count(doc));
                QStringList acc;
                for (int pg = 0; pg < pages; pg++)
                {
                    float box[4] = {0, 0, 0, 0};
                    hr_qt_page_box(doc, pg, box);
                    char *tx = nullptr;
                    float *rc = nullptr;
                    int rn = 0;
                    if (hr_qt_selection(doc, pg, box[0], box[1], box[2], box[3], &tx, &rc,
                                        &rn) == 0 &&
                        tx)
                    {
                        const QString s = QString::fromUtf8(tx);
                        if (!s.trimmed().isEmpty()) acc << s;
                    }
                    free(tx);
                    free(rc);
                    if (acc.join("\n").size() > 3600) break;
                }
                hr_qt_close(doc);
                text = acc.join("\n").left(3800);
            }
            QString err, ans;
            if (!text.isEmpty())
            {
                const QString prompt =
                    QString::fromUtf8("以下是一本书的开头内容。请用不超过 150 字概括这本书的主题"
                                      "与主要内容，直接输出概述，不要客套：\n\n") +
                    text;
                ans = aiAsk(c, prompt, &err, c.maxTokens);
            }
            else
                err = QString::fromUtf8("无可提取文本");
            QMetaObject::invokeMethod(this, [this, p, ans, err]
            {
                statusBar()->clearMessage();
                QDialog dg(this);
                dg.setWindowTitle(hr::l10n::s("AI 简介", "AI intro"));
                dg.resize(560, 420);
                QVBoxLayout *vl = new QVBoxLayout(&dg);
                QTextBrowser *tb = new QTextBrowser(&dg);
                vl->addWidget(tb, 1);
                tb->setPlainText(ans.isEmpty() ? hr::l10n::s("生成失败：", "Failed: ") + err
                                               : ans);
                QDialogButtonBox *bb2 = new QDialogButtonBox(QDialogButtonBox::Ok, &dg);
                bb2->button(QDialogButtonBox::Ok)->setText(hr::l10n::s("关闭", "Close"));
                QPushButton *sv = bb2->addButton(hr::l10n::s("存入书库信息", "Save to library"),
                                                 QDialogButtonBox::ActionRole);
                sv->setEnabled(!ans.isEmpty());
                vl->addWidget(bb2);
                connect(bb2, &QDialogButtonBox::accepted, &dg, &QDialog::accept);
                connect(sv, &QPushButton::clicked, &dg, [this, p, ans, &dg]
                {
                    Db::setDescription(p, ans);
                    evt("ai_intro_save", "path=" + p.left(80));
                    statusBar()->showMessage(hr::l10n::s("简介已存入书库信息", "Intro saved"),
                                             5000);
                    dg.accept();
                });
                evt("ai_intro_done",
                    QString("ok=%1 len=%2").arg(ans.isEmpty() ? 0 : 1).arg(ans.size()));
                dg.exec();
            }, Qt::QueuedConnection);
        }));
    }
}

void LibraryWindow::onDeleteFiles(const QStringList &paths)
{
    if (paths.isEmpty()) return;
    if (QMessageBox::warning(this, "HowRead Qt",
                             QString(hr::l10n::s("永久删除 %1 个文件？此操作不可撤销。",
                                                 "Permanently delete %1 file(s)? Irreversible."))
                                 .arg(paths.size()),
                             QMessageBox::Yes | QMessageBox::No) != QMessageBox::Yes)
        return;
    int ok = 0, fail = 0;
    for (const QString &x : paths)
    {
        if (QFile::remove(x))
        {
            Db::removeBookFull(x); /* P0：级联+墓碑 */
            ok++;
        }
        else fail++;
    }
    if (fail > 0)
        QMessageBox::warning(this, "HowRead Qt",
                             QString(hr::l10n::s("%1 个删除失败（可能被占用）", "%1 delete failed"))
                                 .arg(fail));
    evt("lib_delete", QString("ok=%1 fail=%2").arg(ok).arg(fail));
    loadTable();
}

static QSet<int> remoteScanIds();
static void setRemoteScanId(int id, bool on);
/* U2：添加书籍·书库来源（v0.110.0：本地位置+网上书库双组勾选——勾选即扫描入库
 * 并刷新书库；取消勾选把对应书籍移出书库。本地勾选沿用 scan_dirs.shelf，
 * 网上勾选存 qt-prefs.ini [scan] remoteOn） */
void LibraryWindow::onManageDirs()
{
    QDialog dlg(this);
    dlg.setWindowTitle(hr::l10n::s("书库来源（勾选后扫描入库）", "Library sources"));
    dlg.resize(580, 480);
    QVBoxLayout *lay = new QVBoxLayout(&dlg);
    QLabel *hint = new QLabel(
        hr::l10n::s("勾选的目录/服务器会被扫描入库并刷新书库；取消勾选会把对应书籍移出书库。",
                    "Checked folders/servers are scanned into the library; unchecking removes their books."),
        &dlg);
    hint->setWordWrap(true);
    hint->setStyleSheet(QString::fromLatin1("color:palette(mid);"));
    lay->addWidget(hint);
    QListWidget *list = new QListWidget(&dlg);

    auto addSep = [list](const QString &txt)
    {
        QListWidgetItem *sep = new QListWidgetItem(txt, list);
        sep->setFlags(Qt::NoItemFlags);
        QFont f = sep->font();
        f.setBold(true);
        sep->setFont(f);
    };
    addSep(hr::l10n::s("— 本地位置 —", "- Local folders -"));
    for (const Db::ScanDirRow &dr : Db::scanDirRows())
    {
        QListWidgetItem *it =
            new QListWidgetItem(QDir::toNativeSeparators(dr.path), list);
        it->setFlags(it->flags() | Qt::ItemIsUserCheckable);
        it->setCheckState(dr.shelf ? Qt::Checked : Qt::Unchecked);
        it->setData(Qt::UserRole, 0);
        it->setToolTip(hr::l10n::s("勾选=扫描入库并纳入书架；取消=移出书库",
                                   "Checked = scanned into library; unchecking removes its books"));
    }
    addSep(hr::l10n::s("— 网上书库 —", "- Network servers -"));
    const QSet<int> rOn = remoteScanIds();
    for (const Db::RemoteSrc &s : Db::listRemoteSrc())
    {
        const QString nm = s.name.isEmpty() ? s.host : s.name;
        QListWidgetItem *it = new QListWidgetItem(
            QString::fromLatin1("[%1] %2").arg(s.protocol.toUpper(), nm), list);
        it->setFlags(it->flags() | Qt::ItemIsUserCheckable);
        it->setCheckState(rOn.contains(s.id) ? Qt::Checked : Qt::Unchecked);
        it->setData(Qt::UserRole, 1);
        it->setData(Qt::UserRole + 1, s.id);
        it->setToolTip(s.protocol.toLower() == QString::fromLatin1("opds")
                           ? hr::l10n::s("OPDS 暂不参与批量扫描（打开时登记）",
                                         "OPDS is registered on open, not bulk-scanned")
                           : hr::l10n::s("勾选=递归扫描服务器上的书（不下载，打开仍流式）",
                                         "Checked = recursively scan books on the server"));
    }
    connect(list, &QListWidget::itemChanged, &dlg, [this](QListWidgetItem *it)
    {
        const int kind = it->data(Qt::UserRole).toInt();
        const bool on = it->checkState() == Qt::Checked;
        if (kind == 0)
        {
            Db::setScanDirShelf(QDir::fromNativeSeparators(it->text()), on);
            evt("lib_src_local", QString("on=%1").arg(on ? 1 : 0));
        }
        else if (kind == 1)
        {
            const int id = it->data(Qt::UserRole + 1).toInt();
            setRemoteScanId(id, on);
            evt("lib_src_remote", QString("id=%1 on=%2").arg(id).arg(on ? 1 : 0));
        }
        /* v0.110.2：治理立即同步生效（本地 DB 操作，秒级）——未勾选条目的书
         * 即刻移出书库（源文件不动）；勾选条目交给防抖扫描补增 */
        governSources();
        if (m_scanDebounce) m_scanDebounce->start(); /* 防抖扫描补增勾选条目 */
    });
    lay->addWidget(list, 1);
    QHBoxLayout *btns = new QHBoxLayout;
    QPushButton *refresh = new QPushButton(hr::l10n::s("刷新", "Refresh"), &dlg);
    QPushButton *add = new QPushButton(hr::l10n::s("添加目录…", "Add…"), &dlg);
    QPushButton *rm = new QPushButton(hr::l10n::s("移除所选", "Remove selected"), &dlg);
    QPushButton *close = new QPushButton(hr::l10n::s("关闭", "Close"), &dlg);
    btns->addWidget(refresh);
    btns->addWidget(add);
    btns->addWidget(rm);
    btns->addStretch(1);
    btns->addWidget(close);
    lay->addLayout(btns);
    connect(add, &QPushButton::clicked, &dlg, [&]()
    {
        QString d = QFileDialog::getExistingDirectory(
            &dlg, hr::l10n::s("选择书库目录", "Choose library folder"));
        if (d.isEmpty()) return;
        Db::addScanDir(d);
        QListWidgetItem *it =
            new QListWidgetItem(QDir::toNativeSeparators(d), list);
        it->setFlags(it->flags() | Qt::ItemIsUserCheckable);
        it->setCheckState(Qt::Checked);
        it->setData(Qt::UserRole, 0);
        if (m_scanDebounce) m_scanDebounce->start();
        evt("lib_adddir", "path=" + d.left(120));
    });
    connect(rm, &QPushButton::clicked, &dlg, [&]()
    {
        int i = list->currentRow();
        if (i < 0) return;
        QListWidgetItem *cit = list->item(i);
        if (!cit || cit->data(Qt::UserRole).toInt() != 0) return; /* 仅本地目录可移除 */
        const QString d = cit->text();
        Db::removeScanDir(QDir::fromNativeSeparators(d));
        delete list->takeItem(i);
        if (m_scanDebounce) m_scanDebounce->start();
        evt("lib_rmdir", "path=" + d.left(120));
    });
    connect(refresh, &QPushButton::clicked, &dlg, [this]()
    {
        /* v0.110.2：刷新=立即治理（同步秒级）+ 后台扫描补增——未勾选条目的书
         * 即刻移出书库（源文件不动），勾选条目扫描入库 */
        governSources();
        loadTable();
        scanAll();
        scanRemoteSources();
        evt("lib_src_refresh", "");
    });
    connect(close, &QPushButton::clicked, &dlg, &QDialog::accept);
    dlg.exec();
    /* v0.92.0 W13：对话框关闭后立即重建"我的文件·本地位置"，新增/移除的目录即刻显示 */
    refreshLocalHome();
    scanAll();
    scanRemoteSources();
}


void LibraryWindow::onCoverMenu(const QPoint &pos)
{
    QListWidgetItem *it = m_cover->itemAt(pos);
    if (!it) return;
    const QString p = it->data(Qt::UserRole).toString();
    const bool fav = Db::isFavorite(p);
    QMenu menu(this);
    QAction *favAct = menu.addAction(fav ? hr::l10n::s("取消收藏", "Unfavorite")
                                         : hr::l10n::s("加入收藏", "Favorite"));
    QAction *act = menu.exec(m_cover->viewport()->mapToGlobal(pos));
    if (act == favAct)
    {
        Db::setFavorite(p, !fav);
        evt("lib_fav", QString("on=%1 path=%2")
                           .arg(!fav ? 1 : 0).arg(QFileInfo(p).fileName().left(80)));
        loadTable();
    }
}

/* ---- U1 导航壳 ---- */

/* ---------- v0.75.0 内嵌阅读：阅读器作为 m_stack 页，不再另开窗 ---------- */

void LibraryWindow::embedReader(ReaderWindow *rw)
{
    if (m_reader)
        retireReader(false); /* 换书：先收旧阅读器（进度落盘），栈页不回书库 */
    else
        m_prevIdx = m_stack->currentIndex();
    m_reader = rw;
    rw->setWindowFlag(Qt::Window, false); /* QMainWindow 构造默认 Qt::Window：显式降为 Widget 才能真嵌入 */
    rw->setEmbedded(true);
    rw->setAttribute(Qt::WA_DeleteOnClose, false);
    rw->setFocusPolicy(Qt::StrongFocus);
    connect(rw, &ReaderWindow::backRequested, this, &LibraryWindow::onReaderBack);
    m_stack->addWidget(rw);
    m_stack->setCurrentWidget(rw);
    m_sideWrap->hide();
    if (m_tabBar) m_tabBar->hide();
    if (m_stack->layout()) m_stack->layout()->activate(); /* 先拿到真实几何再开书，免首渲后重排 */
    rw->setFocus();
    evt("reader_embed", QString("prev=%1").arg(m_prevIdx));
}

void LibraryWindow::retireReader(bool refresh)
{
    ReaderWindow *rw = m_reader;
    if (!rw) return;
    m_reader = nullptr;
    const qint64 t0 = QDateTime::currentMSecsSinceEpoch();
    rw->flushProgress();
    rw->beginTeardown(false); /* v0.107.2：只作废 gen 不等待——phase2 在下一章边界自断 */
    m_stack->removeWidget(rw);
    rw->hide();
    /* v0.107.2：延迟归还引擎文档与销毁窗——phase2 worker 逐章引用 m_doc，
     * doc 必须活到 worker 退出；改 40ms 轮询放行，返回书库不再被 beginTeardown
     * 的同步等待（上限 15s）卡住。closeEvent 退出路径仍同步等。 */
    QTimer *pt = new QTimer(rw);
    pt->setInterval(40);
    connect(pt, &QTimer::timeout, rw, [pt, rw]
    {
        if (rw->phase2Busy()) return;
        pt->stop();
        rw->releaseEngineDoc();
        rw->deleteLater();
    });
    pt->start();
    evt("reader_retire", QString("prep_ms=%1").arg(QDateTime::currentMSecsSinceEpoch() - t0));
    if (!refresh) return;
    m_sideWrap->show();
    if (m_tabBar) m_tabBar->show();
    showTab(m_prevIdx < 0 || m_prevIdx > 6 ? 0 : m_prevIdx);
    reloadAfterReader();
    evt("reader_back", QString("to=%1").arg(m_prevIdx));
}

void LibraryWindow::onReaderBack() { retireReader(true); }

void LibraryWindow::openInReader(const QString &path, int page)
{
    ReaderWindow *rw = new ReaderWindow(m_stack);
    embedReader(rw);
    connect(rw, &ReaderWindow::openPathRequested, this, [this](const QString &p)
    { openInReader(p, -1); }); /* v0.83.0 W2 D8：换书直达开书 */
    /* v0.103.0 P0-1：打开已在后台进行，失败改为异步回调收尾（不留空阅读页） */
    connect(rw, &ReaderWindow::openFailed, this, [this](const QString &p)
    {
        retireReader(true);
        statusBar()->showMessage(hr::l10n::s("打开失败：", "Open failed: ") +
                                     QFileInfo(p).fileName(),
                                 6000);
        evt("reader_embed_fail", p.left(70));
    });
    if (!rw->openBookAsync(path, page))
    {
        retireReader(true); /* 已有打开在途：不留空阅读页 */
        statusBar()->showMessage(hr::l10n::s("正在打开另一本书…", "Already opening…"), 4000);
        evt("reader_embed_busy", path.left(70));
    }
}

void LibraryWindow::openRemoteInReader(void *doc, const QString &name, QObject *keepAlive,
                                       const QString &key)
{
    ReaderWindow *rw = new ReaderWindow(m_stack);
    embedReader(rw);
    rw->adoptDoc(doc, name, keepAlive, -1, key);
}

bool LibraryWindow::readerIsCurrent() const
{
    return m_reader && m_stack->currentWidget() == m_reader;
}

int LibraryWindow::readerPages() const { return m_reader ? m_reader->pageCount() : -1; }

bool LibraryWindow::chromeHidden() const
{
    return m_reader && !m_sideWrap->isVisible() && (!m_tabBar || !m_tabBar->isVisible());
}

bool LibraryWindow::chromeShown() const
{
    return !m_reader && m_sideWrap->isVisible() && (!m_tabBar || m_tabBar->isVisible());
}

QString LibraryWindow::embedGeomInfo() const
{
    const QRect g = geometry(), f = frameGeometry(), s = m_stack->geometry();
    const QRect r = m_reader ? m_reader->geometry() : QRect();
    const QSize mh = minimumSizeHint();
    const QRect scr = QApplication::primaryScreen() ? QApplication::primaryScreen()->geometry() : QRect();
    QString out;
    out += QString("win=%1x%2@%3,%4 ").arg(g.width()).arg(g.height()).arg(g.x()).arg(g.y());
    out += QString("frame=%1x%2@%3,%4 ").arg(f.width()).arg(f.height()).arg(f.x()).arg(f.y());
    out += QString("max=%1 full=%2 ").arg(isMaximized() ? 1 : 0).arg(isFullScreen() ? 1 : 0);
    out += QString("stack=%1x%2@%3,%4 ").arg(s.width()).arg(s.height()).arg(s.x()).arg(s.y());
    out += QString("reader=%1x%2 isWindow=%3 ").arg(r.width()).arg(r.height())
               .arg(m_reader && m_reader->isWindow() ? 1 : 0);
    out += QString("minhint=%1x%2 screen=%3x%4 ").arg(mh.width()).arg(mh.height())
               .arg(scr.width()).arg(scr.height());
    out += QString("side_vis=%1 tab_vis=%2 topcount=%3")
               .arg(m_sideWrap->isVisible() ? 1 : 0).arg(m_tabBar && m_tabBar->isVisible() ? 1 : 0)
               .arg(QApplication::topLevelWidgets().size());
    return out;
}

/* 阅读中关主窗 = 结束阅读（同 reader/exitConfirm 确认 + 进度落盘） */
void LibraryWindow::closeEvent(QCloseEvent *e)
{
    if (m_reader && !m_reader->confirmExit())
    {
        e->ignore();
        return;
    }
    if (m_reader) m_reader->flushProgress();
    QMainWindow::closeEvent(e);
}

void LibraryWindow::showTab(int idx)
{
    if (idx < 0 || idx > 6) return; /* 5=书签笔记 6=珍藏（U7，侧栏/首页进入，无底 tab） */
    m_stack->setCurrentIndex(idx);
    /* v0.86.0 W5 H2：窗口标题随页刷新（对齐页名；此前恒为“书库”） */
    {
        static const struct { const char *zh, *en; } kPg[7] = {
            {"首页", "Home"}, {"书架", "Bookshelf"}, {"我的书库", "My library"},
            {"偏好", "Settings"}, {"网上书库", "Network"}, {"书签笔记", "Notes"},
            {"我的珍藏", "Favorites"},
        }; /* v1.0.0：书库→书架、我的文件→我的书库 */
        setWindowTitle("HowRead Qt — " + hr::l10n::s(kPg[idx].zh, kPg[idx].en));
    }
    if (idx == 0) refreshHome(); /* U7：进首页即刷新数据 */
    for (int i = 0; i < m_tabBtns.size(); i++)
        m_tabBtns[i]->setChecked(i == idx);
    hr::Icons::retint(); /* v0.86.0 W5 H1：选中 tab 图标 accent 重染 */
    evt("shell_tab", QString("idx=%1").arg(idx));
}

/* 侧栏：0 最近阅读 1 我的书架 2 我的书库 3 书签笔记（v1.0.0 网上书库行撤）5 拉伸 6 软件说明 7 夜间/白天 */
void LibraryWindow::onSide(int row)
{
    if (row < 0) return;
    QListWidgetItem *sIt = m_side->item(row);
    const QString role = sIt ? sIt->data(Qt::UserRole).toString() : QString();
    if (role.startsWith(QLatin1String("tag:"))) /* v0.84.0 W3 D4：标签过滤（再点取消） */
    {
        const QString tg = role.mid(4);
        m_tagFilter = (m_tagFilter == tg) ? QString() : tg;
        if (m_tagFilter.isEmpty())
        {
            m_side->blockSignals(true);
            m_side->setCurrentRow(-1);
            m_side->blockSignals(false);
        }
        refreshSideTags();
        loadTable();
        showTab(1); /* 书库 */
        evt("nav_tag", QString("%1 active=%2").arg(tg.left(30)).arg(m_tagFilter.isEmpty() ? 0 : 1));
        return;
    }
    if (role == QLatin1String("about")) /* v0.84.0 W3：说明行 data 驱动 */
    {
        showAboutDialog();
        m_side->blockSignals(true);
        m_side->setCurrentRow(-1);
        m_side->blockSignals(false);
        return;
    }
    if (role == QLatin1String("night")) /* v0.84.0 W3：夜间行 data 驱动 */
    {
        toggleDark();
        m_side->blockSignals(true);
        m_side->setCurrentRow(-1);
        m_side->blockSignals(false);
        return;
    }
    switch (row)
    {
    case 0: /* 最近阅读：暂落首页（U7 上最近列表） */
        showTab(0);
        break;
    case 1: /* 我的书架 */
        showTab(1);
        break;
    case 2: /* 我的书库（v1.0.0 更名） */
        showTab(2);
        break;
    case 3: /* 书签笔记（U7 聚合页）——v1.0.0 网上书库行撤，索引前移 */
        showTab(5);
        break;
    default:
        break;
    }
    if (row >= 3) /* 动作类条目不保留选中态 */
    {
        m_side->blockSignals(true);
        m_side->setCurrentRow(-1);
        m_side->blockSignals(false);
    }
}

/* U1 夜间/白天：应用级简单暗色调色板（U8 换完整 QSS 主题体系） */
/* v0.88.0 W7：亮暗应用统一入口——启动与切换同路径；persist=false 不写偏好 */
void LibraryWindow::setDarkMode(bool dark, bool persist)
{
    if (persist)
    {
        QSettings st(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
        st.setValue("ui/dark", dark);
    }
    hr::UiTheme::apply(dark); /* v0.79.0 V4：Compose UiPalette 双色板（替代 VS Code 风硬编码） */
    /* v0.95.0 W16：夜间行已出列表——底部按钮文本/图标在 setDark 之后同步 */
    /* v0.76.0 V1：图标随亮暗重染 */
    hr::Icons::setDark(dark);
    if (m_sideNightBtn) /* v0.95.0 W16：底部横排夜间按钮随亮暗同步（tint 依赖 setDark 后状态） */
    {
        m_sideNightBtn->setText(dark ? hr::l10n::s("日间模式", "Day mode")
                                     : hr::l10n::s("夜间模式", "Night mode"));
        m_sideNightBtn->setIcon(hr::Icons::get(
            dark ? QString::fromLatin1("glyphicons_232_sun")
                 : QString::fromLatin1("glyphicons_231_moon"),
            hr::Icons::tint(), 18));
    }
    if (m_banner) m_banner->setDark(dark);
    applySideIcons();
    refreshSideTags(); /* v0.84.0 W3：标签区随亮暗重染/高亮色刷新 */
    hr::Icons::retint();
    /* v0.88.0 W7 DIM-1：强制重 polish——QSS palette() 引用（卡片/标签）随亮暗重算 */
    const QWidgetList ws = QApplication::allWidgets();
    for (QWidget *w : ws)
    {
        w->style()->unpolish(w);
        w->style()->polish(w);
        w->update();
    }
    evt("ui_dark", QString("on=%1").arg(dark ? 1 : 0));
}

void LibraryWindow::toggleDark()
{
    QSettings st(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
    setDarkMode(!st.value("ui/dark", false).toBool(), true);
}

/* v0.85.0 W4 探针：偏好页滚动内容整页自绘（不受视口高度限制） */
QPixmap LibraryWindow::grabPrefsContent() const
{
    if (!m_prefsPage) return QPixmap();
    QScrollArea *sa = m_prefsPage->findChild<QScrollArea *>();
    if (sa && sa->widget()) return sa->widget()->grab();
    return m_prefsPage->grab();
}

/* v0.87.0 截图对比探针：切书库视图（--shotsviews） */
void LibraryWindow::setViewForProbe(const QString &v)
{
    if (!m_viewC) return;
    const int i = m_viewC->findData(v);
    if (i >= 0) m_viewC->setCurrentIndex(i);
}

/* v0.76.0 V1：侧栏条目图标（拉伸占位行无图标；夜间行随状态换 月/日） */
void LibraryWindow::applySideIcons()
{
    if (!m_side) return;
    /* v0.84.0 W3：改行 data 驱动（UserRole+1=图标文件；标签区行同样带文件随亮暗重染） */
    for (int i = 0; i < m_side->count(); i++)
    {
        QListWidgetItem *it = m_side->item(i);
        if (!it) continue;
        QString f = it->data(Qt::UserRole + 1).toString();
        if (f.isEmpty()) continue; /* 拉伸/组头行无图标 */
        if (f == QLatin1String("glyphicons_231_moon"))
            f = hr::Icons::isDark() ? QString::fromLatin1("glyphicons_232_sun")
                                    : QString::fromLatin1("glyphicons_231_moon");
        it->setIcon(hr::Icons::get(f, hr::Icons::tint(), 20));
    }
}

/* v0.84.0 W3 D4：侧栏标签区（对齐 Compose ShellScreen 标签分组：聚合 file_meta.tags，
 * 行="标签名 · 数量"，点击=书库按该标签过滤再点取消；插在导航 5 行之后、拉伸之前） */
void LibraryWindow::refreshSideTags()
{
    /* v0.93.0 W14：用户点名——侧栏不放书签/标签条目（“书签笔记”行下不再挂任何动态行）。
     * 聚合展示已移除，仅保留清理逻辑（老版本残留的动态行会被移除）。 */
    if (!m_side) return;
    m_side->blockSignals(true);
    for (QListWidgetItem *it : m_tagItems)
    {
        const int row = m_side->row(it);
        if (row >= 0) delete m_side->takeItem(row);
    }
    m_tagItems.clear();
    m_side->blockSignals(false);
}

int LibraryWindow::libCount() const { return m_tree ? m_tree->topLevelItemCount() : 0; }

void LibraryWindow::onAssoc()
{
    /* HKCU 注册表关联（免管理员）：HowReadQt.<ext> → exe "%1" */
    const QString exe = QDir::toNativeSeparators(QCoreApplication::applicationFilePath());
    int n = 0;
    for (const QString &ext : m_exts)
    {
        const QString key = "HKEY_CURRENT_USER\\Software\\Classes\\HowReadQt." + ext +
                            "\\shell\\open\\command";
        QSettings st(key, QSettings::NativeFormat);
        st.setValue(".", QString("\"%1\" \"%2\"").arg(exe, "%1"));
        QSettings ic("HKEY_CURRENT_USER\\Software\\Classes\\." + ext +
                         "\\OpenWithProgids",
                     QSettings::NativeFormat);
        ic.setValue("HowReadQt." + ext, "");
        n++;
    }
    evt("file_assoc", QString("exts=%1").arg(n));
    /* U0 修复#15：瞬时提示走状态栏，不再覆盖右侧"N 本"计数 */
    statusBar()->showMessage(
        QString(hr::l10n::s("%1 本（已关联 %2 种格式）", "%1 books (%2 formats assoc.)"))
            .arg(m_tree->topLevelItemCount()).arg(n), 8000);
}

/* P1 适配（F1）：撤销文件关联（删 HKCU ProgID + OpenWithProgids，对齐 WIN disassociate） */
void LibraryWindow::onAssocUndo()
{
    int n = 0;
    for (const QString &ext : m_exts)
    {
        const QString pid = QString::fromLatin1("HowReadQt.") + ext;
        QSettings cls("HKEY_CURRENT_USER\\Software\\Classes", QSettings::NativeFormat);
        cls.remove(pid);
        QSettings ic("HKEY_CURRENT_USER\\Software\\Classes\\." + ext +
                         "\\OpenWithProgids",
                     QSettings::NativeFormat);
        ic.remove(pid);
        n++;
    }
    evt("file_assoc_undo", QString("exts=%1").arg(n));
    statusBar()->showMessage(
        QString(hr::l10n::s("已撤销 %1 种格式关联（重开“打开方式”列表生效）",
                            "Removed %1 associations"))
            .arg(n), 8000);
}

/* ---- M3：WebDAV 凭据 + 同步对话框 + 远程书库对话框 ---- */

struct DavCreds
{
    QString url, user, pass, dir, policy;
};

/* U0 修复#6：远程三协议此前共用一份凭据——按协议分键存取（web/webdav|sftp|opds/*），
 * 旧单份 web/* 键作为迁移回退（老用户三协议自动继承原密码，之后各自可改）。 */
static QString credPrefix(const QString &proto)
{
    return "web/" + (proto.isEmpty() ? QString("webdav") : proto.toLower());
}

static DavCreds loadCreds(const QString &proto = QString("webdav"))
{
    QSettings st(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
    const QString pfx = credPrefix(proto);
    const bool isDav = (pfx == "web/webdav");
    const QString legacyUser = st.value("web/user", "howread").toString();
    const QString legacyPass = st.value("web/pass", "howread123").toString();
    DavCreds c;
    c.url = st.value(pfx + "/url",
                     isDav ? st.value("web/url", "http://192.168.50.23:8765/").toString()
                           : QString()).toString();
    c.user = st.value(pfx + "/user", legacyUser).toString();
    QString savedPass = st.value(pfx + "/pass").toString();
    if (savedPass.isEmpty()) savedPass = legacyPass; /* 迁移：旧单份密码先共享给三协议 */
    /* dpapi: 前缀解密；无前缀历史明文原样通过（v0.61.0 起新存值一律加密） */
    c.pass = hr::unprotectSecret(savedPass);
    c.dir = st.value(pfx + "/dir",
                     isDav ? st.value("web/dir", "HowRead").toString() : QString()).toString();
    c.policy = st.value("web/policy", "newer").toString();
    return c;
}

static void saveCreds(const DavCreds &c, const QString &proto = QString("webdav"))
{
    QSettings st(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
    const QString pfx = credPrefix(proto);
    st.setValue(pfx + "/url", c.url);
    st.setValue(pfx + "/user", c.user);
    st.setValue(pfx + "/pass", hr::protectSecret(c.pass));
    st.setValue(pfx + "/dir", c.dir);
    if (pfx == "web/webdav") st.setValue("web/policy", c.policy);
}

void LibraryWindow::onSync()
{
    DavCreds c = loadCreds("webdav");
    QSettings st(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
    QDialog dlg(this);
    dlg.setWindowTitle(hr::l10n::s("WebDAV 同步（与安卓互通）", "WebDAV sync (Android interop)"));
    dlg.resize(520, 300);
    QFormLayout *form = new QFormLayout(&dlg);
    QLineEdit *url = new QLineEdit(c.url, &dlg);
    QLineEdit *user = new QLineEdit(c.user, &dlg);
    QLineEdit *pass = new QLineEdit(c.pass, &dlg);
    pass->setEchoMode(QLineEdit::Password);
    QLineEdit *dir = new QLineEdit(c.dir, &dlg);
    QComboBox *policy = new QComboBox(&dlg);
    policy->addItem(hr::l10n::s("新者胜 (newer)", "Newest wins"), "newer");
    policy->addItem(hr::l10n::s("仅本地缺失 (local)", "Local missing only"), "local");
    policy->addItem(hr::l10n::s("服务器优先 (server)", "Server first"), "server");
    policy->addItem(hr::l10n::s("更靠前优先 (farther)", "Farther wins"), "farther"); /* P1（E10） */
    policy->setCurrentIndex(qMax(0, policy->findData(c.policy)));
    /* P1 适配（E6）：启用开关 + 定时间隔 */
    QCheckBox *en = new QCheckBox(
        hr::l10n::s("启用自动同步（按定时间隔）", "Enable auto sync (by interval)"), &dlg);
    en->setChecked(st.value("web/syncEnabled", false).toBool());
    QComboBox *iv = new QComboBox(&dlg);
    iv->addItem(hr::l10n::s("关闭", "Off"), 0);
    iv->addItem("5 min", 5);
    iv->addItem("15 min", 15);
    iv->addItem("30 min", 30);
    iv->addItem("60 min", 60);
    iv->addItem("3 h", 180);
    iv->addItem("6 h", 360);
    iv->addItem("24 h", 1440);
    iv->setCurrentIndex(qMax(0, iv->findData(st.value("web/intervalMin", 0).toInt())));
    form->addRow(hr::l10n::s("服务器", "Server"), url);
    form->addRow(hr::l10n::s("账号", "User"), user);
    form->addRow(hr::l10n::s("密码", "Password"), pass);
    form->addRow(hr::l10n::s("远程目录", "Remote dir"), dir);
    form->addRow(hr::l10n::s("合并策略", "Merge policy"), policy);
    form->addRow(QString(), en);
    form->addRow(hr::l10n::s("自动同步间隔", "Auto interval"), iv);
    QLabel *lb = new QLabel(hr::l10n::s("books/<内容MD5>.json 协议；进度/书签/删除墓碑",
                                        "books/<md5>.json; progress/bookmarks/tombstones"), &dlg);
    form->addRow(lb);
    QDialogButtonBox *bb = new QDialogButtonBox(QDialogButtonBox::Ok | QDialogButtonBox::Cancel,
                                                &dlg);
    bb->button(QDialogButtonBox::Ok)->setText(hr::l10n::s("立即同步", "Sync now"));
    form->addRow(bb);
    connect(bb, &QDialogButtonBox::accepted, &dlg, &QDialog::accept);
    connect(bb, &QDialogButtonBox::rejected, &dlg, &QDialog::reject);
    /* P1 适配（E7/E8）：测试连接 + 同步日志查看 */
    QPushButton *testB = new QPushButton(hr::l10n::s("测试连接", "Test connection"), &dlg);
    QPushButton *logB = new QPushButton(hr::l10n::s("查看同步日志…", "Sync log…"), &dlg);
    QHBoxLayout *trow = new QHBoxLayout;
    trow->addWidget(testB);
    trow->addWidget(logB);
    trow->addStretch(1);
    form->addRow(trow);
    connect(testB, &QPushButton::clicked, &dlg,
            [this, url, user, pass, dir]
    {
        hr::WebDavClient dav(url->text().trimmed(), user->text(), pass->text());
        QVector<hr::DavEntry> es;
        QString err;
        const bool ok = dav.list(dir->text().trimmed().isEmpty()
                                     ? QString::fromLatin1("/")
                                     : dir->text().trimmed(),
                                 &es, &err);
        evt("sync_test", QString("ok=%1 n=%2").arg(ok ? 1 : 0).arg(es.size()));
        QMessageBox::information(this, hr::l10n::s("测试连接", "Test"),
                                 ok ? hr::l10n::s("连接成功，%1 项。", "Connected, %1 items.")
                                          .arg(es.size())
                                    : hr::l10n::s("失败：%1", "Failed: %1").arg(err.left(120)));
    });
    connect(logB, &QPushButton::clicked, this, [this]
    {
        QFile lf(hr::dataDir() + "/logs/hrtest.log");
        QString tail;
        if (lf.open(QIODevice::ReadOnly | QIODevice::Text))
        {
            const QStringList all =
                QString::fromUtf8(lf.readAll()).split(QLatin1Char('\n'));
            tail = all.mid(qMax(0, all.size() - 200)).join(QLatin1Char('\n'));
        }
        if (tail.isEmpty()) tail = hr::l10n::s("（暂无日志）", "(no log)");
        QDialog ld(this);
        ld.setWindowTitle(hr::l10n::s("调试日志（末 200 行）", "Log (last 200 lines)"));
        ld.resize(680, 480);
        QVBoxLayout *ll = new QVBoxLayout(&ld);
        QTextBrowser *tv = new QTextBrowser(&ld);
        tv->setPlainText(tail);
        ll->addWidget(tv);
        ld.exec();
    });

    if (dlg.exec() != QDialog::Accepted) return;

    c.url = url->text().trimmed();
    c.user = user->text();
    c.pass = pass->text();
    c.dir = dir->text().trimmed();
    c.policy = policy->currentData().toString();
    saveCreds(c, "webdav");
    st.setValue("web/syncEnabled", en->isChecked());
    st.setValue("web/intervalMin", iv->currentData().toInt());
    if (g_syncBusy.exchange(true))
    {
        statusBar()->showMessage(hr::l10n::s("同步正在进行…", "Sync already running"), 4000);
        return;
    }
    statusBar()->showMessage(hr::l10n::s("同步中…", "Syncing…"));
    evt("sync_start", "policy=" + c.policy);
    QThreadPool::globalInstance()->start(QRunnable::create([this, c]
    {
        const hr::SyncResult r = hr::androidSync(c.url, c.user, c.pass, c.dir, c.policy);
        QMetaObject::invokeMethod(this, [this, r]
        {
            statusBar()->showMessage(r.ok
                                  ? QString(hr::l10n::s(
                                        "同步完成：进度下%1/上%2 书签下%3 书%4（%5ms）",
                                        "Sync done: prog d%1/u%2 bm d%3 books %4 (%5ms)"))
                                        .arg(r.progressDown).arg(r.progressUp)
                                        .arg(r.bookmarksDown).arg(r.booksSynced)
                                        .arg(r.durationMs)
                                  : QString(hr::l10n::s("同步失败：%1", "Sync failed: %1"))
                                        .arg(r.error.left(80)), 15000);
            loadTable();
        }, Qt::QueuedConnection);
        g_syncBusy = false;
    }));
}

/* 块缓存读回调桥（mupdf 任意线程 → BlockCacheStore） */
static int cacheReadCb(void *ud, long long pos, unsigned char *buf, int len)
{
    return static_cast<hr::BlockCacheStore *>(ud)->readAt(pos, buf, len);
}

/* 块缓存会话随阅读窗销毁（adoptDoc 里 setParent）；协议会话/熔断策略同生命周期 */
class BcsHolder : public QObject
{
public:
    hr::BlockCacheStore *s = nullptr;
    std::shared_ptr<hr::SshClient> ssh;
    std::shared_ptr<hr::WebDavClient> dav;
    std::shared_ptr<hr::SmbClient> smb;
    std::shared_ptr<hr::FetchPolicy> fp;
    ~BcsHolder() override { delete s; }
};

void LibraryWindow::onRemote()
{
    QSettings pst(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
    const QString protoSaved = pst.value("web/protocol", "WebDAV").toString();
    DavCreds c = loadCreds(protoSaved.toLower());

    QDialog dlg(this);
    dlg.setWindowTitle(hr::l10n::s("远程书库（WebDAV / SFTP / OPDS）",
                                   "Remote books (WebDAV / SFTP / OPDS)"));
    dlg.resize(680, 600);
    QVBoxLayout *lay = new QVBoxLayout(&dlg);
    QHBoxLayout *rowP = new QHBoxLayout;
    QComboBox *protoC = new QComboBox(&dlg);
    protoC->addItem("WebDAV");
    protoC->addItem("SFTP");
    protoC->addItem("OPDS");
    protoC->setCurrentIndex(qMax(0, protoC->findText(protoSaved)));
    rowP->addWidget(protoC, 1);
    lay->addLayout(rowP);

    QHBoxLayout *row = new QHBoxLayout;
    QLineEdit *url = new QLineEdit(c.url, &dlg);
    QLineEdit *dir = new QLineEdit(c.dir, &dlg);
    QPushButton *btnList = new QPushButton(hr::l10n::s("列出", "List"), &dlg);
    row->addWidget(url, 3);
    row->addWidget(dir, 1);
    row->addWidget(btnList);
    lay->addLayout(row);

    QHBoxLayout *rowS = new QHBoxLayout;
    QLineEdit *search = new QLineEdit(&dlg);
    search->setPlaceholderText(hr::l10n::s("OPDS 目录内搜索（回车）", "OPDS search (Enter)"));
    QPushButton *btnSearch = new QPushButton(hr::l10n::s("搜索", "Search"), &dlg);
    rowS->addWidget(search, 1);
    rowS->addWidget(btnSearch);
    lay->addLayout(rowS);

    QListWidget *list = new QListWidget(&dlg);
    lay->addWidget(list, 1);
    QLabel *lb = new QLabel(hr::l10n::s("双击打开（按需取块）；目录双击进入", 
                                        "Double-click to open (block-cached); dirs navigate"), &dlg);
    lay->addWidget(lb);

    auto protoOf = [protoC]() { return protoC->currentText(); };
    auto saveProto = [protoC, &pst]() { pst.setValue("web/protocol", protoC->currentText()); };

    /* 打开远程书（三协议共用）：块缓存 + 熔断 + 流式打开 */
    struct OpenInfo
    {
        int kind = 0; /* 0=webdav 1=sftp 2=opds */
        QString url;  /* webdav=完整 URL；sftp=绝对路径；opds=acquisition URL */
        long long size = 0;
        QString name;
    };
    auto openBook = [this, &dlg, url, protoOf, lb](const OpenInfo &oi)
    {
        const DavCreds cc = loadCreds(protoOf().toLower());
        const QString ext = QFileInfo(oi.name).suffix().toLower();
        const QString key = "qt-" +
            QString::fromLatin1(QCryptographicHash::hash(
                                    (protoOf() + "|" + oi.url).toUtf8(),
                                    QCryptographicHash::Md5)
                                    .toHex()) + "-" + QString::number(oi.size);
        const int blockSize =
            (ext == "pdf" || ext == "cbz" || ext == "xps" || ext == "djvu") ? 64 * 1024 : 1024 * 1024;
        const int fillThreads =
            qBound(1, QSettings(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat)
                              .value(QString::fromLatin1("net/fillThreads"), 4)
                              .toInt(), 8);
        BcsHolder *holder = new BcsHolder();
        holder->fp = std::make_shared<hr::FetchPolicy>();
        if (oi.kind == 1)
        {
            const QString hp = url->text().trimmed();
            const QString host = hp.section(':', 0, 0);
            const int port = hp.contains(':') ? hp.section(':', 1, 1).toInt() : 22;
            holder->ssh = std::make_shared<hr::SshClient>(host, port, cc.user, cc.pass);
            if (!holder->ssh->ok())
            {
                lb->setText(hr::l10n::s("SFTP 连接失败：", "SFTP connect failed: ") +
                            holder->ssh->error());
                delete holder;
                return;
            }
            const QString rel = oi.url;
            auto fetch = [holder, rel](long long pos, int len) -> QByteArray
            {
                return holder->fp->fetch(
                    [holder, rel](long long p, int l)
                    { return holder->ssh->readRange(rel, p, l); },
                    pos, len, "sftp");
            };
            holder->s = new hr::BlockCacheStore(key, oi.size, blockSize, fetch, 32LL * 1024 * 1024, fillThreads);
        }
        else /* webdav / opds：HTTP Range 取块 */
        {
            holder->dav = std::make_shared<hr::WebDavClient>(url->text().trimmed(), cc.user, cc.pass);
            const QString url1 = oi.url;
            auto fetch = [holder, url1](long long pos, int len) -> QByteArray
            {
                return holder->fp->fetch(
                    [holder, url1](long long p, int l)
                    { return holder->dav->getRange(url1, p, l); },
                    pos, len, "webdav");
            };
            holder->s = new hr::BlockCacheStore(key, oi.size, blockSize, fetch, 32LL * 1024 * 1024, fillThreads);
        }
        {
            const bool tailFmt = (ext == "pdf" || ext == "cbz" || ext == "xps" ||
                                  ext == "djvu" || ext == "epub" || ext == "zip");
            const int tailBlk = tailFmt ? (blockSize <= 64 * 1024 ? 2 : 1) : 0;
            holder->s->prefillEnds(2, tailBlk, 8000); /* v0.111.0：引擎 open 前预取头尾 */
        }
        const QString magic = ext.isEmpty() ? QString("epub") : ext;
        void *doc = hr_qt_open_stream(magic.toUtf8().constData(), oi.size, cacheReadCb, holder->s);
        if (!doc)
        {
            delete holder;
            evt("remote_open_fail", QString::fromUtf8(hr_qt_last_error()).left(100));
            lb->setText(hr::l10n::s("打开失败：", "Open failed: ") +
                        QString::fromUtf8(hr_qt_last_error()).left(80));
            return;
        }
        emit openRemoteRequested(doc, oi.name, holder, QString()); /* 旧对话框路径：键回退 remote://<名> */
        dlg.accept();
    };

    /* U0 修复#5：WebDAV 此前列表跳过目录无法下钻——目录项+上一级，双击进入 */
    QString davSub; /* 相对 <url>/<dir> 的当前子目录（不带首尾斜杠） */
    auto fillWebdav = [this, &dlg, url, dir, list, lb](const QString &sub)
    {
        list->clear();
        QVector<DavEntry> es;
        QString err;
        const DavCreds dc = loadCreds("webdav");
        hr::WebDavClient dav(url->text().trimmed(), dc.user, dc.pass);
        QString full = url->text().trimmed();
        full.remove(QRegularExpression("/+$"));
        const QString rel = dir->text().trimmed().remove(QRegularExpression("^/+|/+$"));
        if (!rel.isEmpty()) full += "/" + rel;
        if (!sub.isEmpty()) full += "/" + sub;
        if (!dav.list(full, &es, &err))
        {
            lb->setText(hr::l10n::s("列出失败：", "List failed: ") + err);
            return;
        }
        int n = 0;
        if (!sub.isEmpty())
        {
            QListWidgetItem *up = new QListWidgetItem(hr::l10n::s("..（上一级）", ".. (up)"), list);
            up->setData(Qt::UserRole, "webdav");
            up->setData(Qt::UserRole + 1, QString(".."));
            up->setData(Qt::UserRole + 3, true);
            up->setData(Qt::UserRole + 4, QString(".."));
        }
        for (const DavEntry &e : es)
        {
            QListWidgetItem *it = nullptr;
            if (e.isDir)
            {
                it = new QListWidgetItem(e.name + "/", list);
            }
            else
            {
                const QString ext = QFileInfo(e.name).suffix().toLower();
                if (!m_exts.contains(ext)) continue;
                it = new QListWidgetItem(
                    QString(hr::l10n::s("%1   (%2 KB)", "%1   (%2 KB)"))
                        .arg(e.name).arg(e.size / 1024), list);
                n++;
            }
            it->setData(Qt::UserRole, "webdav");
            it->setData(Qt::UserRole + 1, e.name);
            it->setData(Qt::UserRole + 2, (qlonglong)e.size);
            it->setData(Qt::UserRole + 3, e.isDir);
            it->setData(Qt::UserRole + 4, e.name);
        }
        lb->setText(QString(hr::l10n::s("%1 本可开", "%1 openable")).arg(n));
        evt("remote_list", QString("proto=webdav n=%1 dir=%2 sub=%3")
                                .arg(n).arg(dir->text()).arg(sub));
    };

    auto fillSftp = [this, &dlg, url, dir, list, lb, openBook]()
    {
        list->clear();
        const QString hp = url->text().trimmed();
        const QString host = hp.section(':', 0, 0);
        const int port = hp.contains(':') ? hp.section(':', 1, 1).toInt() : 22;
        lb->setText(hr::l10n::s("SFTP 连接中…", "SFTP connecting…"));
        const DavCreds dc = loadCreds("sftp");
        auto ssh = std::make_shared<hr::SshClient>(host, port, dc.user, dc.pass);
        if (!ssh->ok())
        {
            lb->setText(hr::l10n::s("SFTP 连接失败：", "SFTP connect failed: ") + ssh->error());
            return;
        }
        QString err;
        const QList<hr::SshEntry> es = ssh->list(dir->text().trimmed(), &err);
        if (es.isEmpty() && !err.isEmpty())
        {
            lb->setText(hr::l10n::s("列出失败：", "List failed: ") + err);
            return;
        }
        int n = 0;
        for (const hr::SshEntry &e : es)
        {
            QString label = e.isDir ? e.name + "/" : e.name;
            if (!e.isDir) label += QString("   (%1 KB)").arg(e.size / 1024);
            QListWidgetItem *it = new QListWidgetItem(label, list);
            it->setData(Qt::UserRole, "sftp");
            it->setData(Qt::UserRole + 1, e.relPath);
            it->setData(Qt::UserRole + 2, (qlonglong)e.size);
            it->setData(Qt::UserRole + 3, e.isDir);
            it->setData(Qt::UserRole + 4, e.name);
            if (!e.isDir) n++;
        }
        lb->setText(QString(hr::l10n::s("%1 本可开", "%1 openable")).arg(n));
        evt("remote_list", QString("proto=sftp n=%1 err=%2").arg(n).arg(err.left(40)));
        /* 连接移交：双击打开时由 OpenInfo 重建（列表连接仅为预检） */
    };

    auto opdsRoot = [url]() { return url->text().trimmed(); };
    auto fillOpds = [this, &dlg, url, dir, list, lb, opdsRoot](const QString &feedUrl)
    {
        list->clear();
        lb->setText(hr::l10n::s("OPDS 读取中…", "Reading feed…"));
        const DavCreds dc = loadCreds("opds");
        auto op = std::make_shared<hr::OpdsClient>(opdsRoot(), dc.user, dc.pass);
        QString err;
        const QList<hr::OpdsEntry> es = op->list(feedUrl.isEmpty() ? QString() : feedUrl, &err);
        if (es.isEmpty())
        {
            lb->setText(hr::l10n::s("列出失败：", "List failed: ") + err);
            return;
        }
        for (const hr::OpdsEntry &e : es)
        {
            QString label = e.isDir ? e.name + "  ›" : e.name;
            if (!e.isDir && !e.summary.isEmpty())
                label += "   — " + e.summary.left(60);
            QListWidgetItem *it = new QListWidgetItem(label, list);
            it->setToolTip(e.summary);
            it->setData(Qt::UserRole, "opds");
            it->setData(Qt::UserRole + 1, e.url);
            it->setData(Qt::UserRole + 3, e.isDir);
            it->setData(Qt::UserRole + 4, e.name);
        }
        lb->setText(QString(hr::l10n::s("%1 项（含下一页/目录）", "%1 entries")).arg(es.size()));
        evt("opds_feed", QString("n=%1 tpl=%2").arg(es.size()).arg(op->searchTemplate().isEmpty() ? 0 : 1));
    };

    /* U0：切协议前把当前字段存回旧协议（未保存编辑不丢），再载入新协议凭据 */
    QString prevProto = protoSaved;
    auto applyProto = [url, dir, &prevProto](const QString &p)
    {
        if (prevProto != p)
        {
            DavCreds cur;
            cur.url = url->text().trimmed();
            cur.dir = dir->text().trimmed();
            const DavCreds old = loadCreds(prevProto.toLower());
            cur.user = old.user;
            cur.pass = old.pass;
            cur.policy = old.policy;
            saveCreds(cur, prevProto.toLower());
            prevProto = p;
        }
        const DavCreds pc = loadCreds(p.toLower());
        if (!pc.url.isEmpty()) url->setText(pc.url);
        if (!pc.dir.isEmpty()) dir->setText(pc.dir);
        if (p == "SFTP")
        {
            url->setPlaceholderText("host:port");
            if (dir->text().trimmed().isEmpty()) dir->setText("/home/howread/books");
        }
        else if (p == "OPDS")
        {
            url->setPlaceholderText("http://host:port/opds");
        }
        else
        {
            url->setPlaceholderText("http://192.168.50.23:8765/");
            if (dir->text().trimmed().isEmpty()) dir->setText("HowRead");
        }
    };
    connect(protoC, &QComboBox::currentTextChanged, &dlg, applyProto);
    applyProto(protoC->currentText());

    connect(btnList, &QPushButton::clicked, &dlg,
            [this, protoOf, saveProto, fillWebdav, fillSftp, fillOpds, dir, list, &davSub]()
    {
        saveProto();
        const QString p = protoOf();
        if (p == "SFTP") fillSftp();
        else if (p == "OPDS") fillOpds(QString());
        else
        {
            davSub.clear();
            fillWebdav(QString());
        }
    });
    connect(btnSearch, &QPushButton::clicked, &dlg,
            [this, &dlg, url, search, list, lb]()
    {
        const QString q = search->text().trimmed();
        if (q.isEmpty()) return;
        list->clear();
        lb->setText(hr::l10n::s("搜索中…", "Searching…"));
        const DavCreds dc = loadCreds("opds");
        auto op = std::make_shared<hr::OpdsClient>(url->text().trimmed(), dc.user, dc.pass);
        QString err;
        const QList<hr::OpdsEntry> es = op->search(q, &err);
        if (es.isEmpty())
        {
            lb->setText(hr::l10n::s("搜索失败：", "Search failed: ") + err);
            return;
        }
        for (const hr::OpdsEntry &e : es)
        {
            QListWidgetItem *it = new QListWidgetItem(
                e.isDir ? e.name + "  ›" : e.name, list);
            it->setData(Qt::UserRole, "opds");
            it->setData(Qt::UserRole + 1, e.url);
            it->setData(Qt::UserRole + 3, e.isDir);
            it->setData(Qt::UserRole + 4, e.name);
        }
        evt("opds_search", QString("q=%1 n=%2").arg(q.left(40)).arg(es.size()));
    });
    connect(search, &QLineEdit::returnPressed, &dlg, [btnSearch] { emit btnSearch->click(); });

    connect(list, &QListWidget::itemDoubleClicked, &dlg,
            [this, &dlg, protoOf, url, dir, list, lb, fillOpds, fillSftp, fillWebdav, openBook,
             &davSub](QListWidgetItem *it)
    {
        const QString kind = it->data(Qt::UserRole).toString();
        const QString path = it->data(Qt::UserRole + 1).toString();
        const long long size = it->data(Qt::UserRole + 2).toLongLong();
        const bool isDir = it->data(Qt::UserRole + 3).toBool();
        const QString name = it->data(Qt::UserRole + 4).toString();
        if (kind == "sftp")
        {
            if (isDir)
            {
                dir->setText(path);
                fillSftp();
                return;
            }
            OpenInfo oi;
            oi.kind = 1;
            oi.url = path;
            oi.size = size > 0 ? size : 0;
            oi.name = name;
            openBook(oi);
        }
        else if (kind == "opds")
        {
            if (isDir)
            {
                fillOpds(path);
                return;
            }
            OpenInfo oi;
            oi.kind = 2;
            oi.url = path;
            QString ext = QFileInfo(QUrl(path).path()).suffix().toLower();
            if (ext.isEmpty()) ext = "epub";
            oi.name = name.contains('.') ? name : name + "." + ext;
            oi.size = size;
            /* acquisition 无 size：先探一次 HEAD 语义（Range 首块）拿总长 */
            if (oi.size <= 0)
            {
                const DavCreds dc = loadCreds("opds");
                hr::WebDavClient dav(url->text().trimmed(), dc.user, dc.pass);
                const QByteArray head = dav.getRange(path, 0, 1);
                oi.size = dav.contentLength(path);
                if (oi.size <= 0)
                {
                    lb->setText(hr::l10n::s("无法确定书目大小", "Unknown book size"));
                    return;
                }
            }
            openBook(oi);
        }
        else
        {
            if (isDir)
            {
                /* U0 修复#5：WebDAV 目录双击下钻/上一级 */
                if (path == "..")
                {
                    const int cut = davSub.lastIndexOf('/');
                    davSub = (cut >= 0) ? davSub.left(cut) : QString();
                }
                else
                {
                    davSub = davSub.isEmpty() ? path : davSub + "/" + path;
                }
                fillWebdav(davSub);
                return;
            }
            OpenInfo oi;
            oi.kind = 0;
            oi.url = url->text().trimmed();
            oi.url.remove(QRegularExpression("/+$"));
            const QString rel = dir->text().trimmed().remove(QRegularExpression("^/+|/+$"));
            if (!rel.isEmpty()) oi.url += "/" + rel;
            if (!davSub.isEmpty()) oi.url += "/" + davSub;
            oi.url += "/" + QUrl::toPercentEncoding(name);
            oi.size = size;
            oi.name = name;
            openBook(oi);
        }
    });

    dlg.exec();
}

void LibraryWindow::startCoverGen()
{
    if (!m_cover || m_coverGenActive) return;
    QStringList todo;
    for (int i = 0; i < m_cover->count() && todo.size() < 300; i++)
    {
        QListWidgetItem *it = m_cover->item(i);
        if (it && it->data(Qt::UserRole + 3).toInt() == 0) /* v0.89.0 W8：封面就绪标记 */
        {
            const QString cp = it->data(Qt::UserRole).toString();
            if (!cp.startsWith(QString::fromLatin1("remote://")))
                todo.append(cp); /* remote 封面由打开时生成（缺陷#4） */
        }
    }
    if (todo.isEmpty()) return;
    m_coverGenActive = true;
    const QStringList tasks = todo;
    QThreadPool::globalInstance()->start(QRunnable::create([this, tasks]
    {
        int done = 0;
        for (const QString &p : tasks)
        {
            const QString png = coverPathFor(p);
            if (QFileInfo(png).isFile()) { done++; }
            else
            {
                void *doc = hr_qt_open(hr::openablePath(p).toUtf8().constData());
                if (!doc) continue;
                int w = 0, h = 0;
                unsigned char *rgb = nullptr;
                if (hr_qt_render(doc, 0, 320, 0, 0, &w, &h, &rgb) == 0 && rgb)
                {
                    QImage img(rgb, w, h, w * 3, QImage::Format_RGB888);
                    QDir().mkpath(QFileInfo(png).absolutePath());
                    if (img.copy().save(png)) done++;
                    free(rgb);
                }
                hr_qt_close(doc);
            }
            const QImage ic(png);
            QMetaObject::invokeMethod(this, [this, p, ic]
            {
                if (!m_cover) return;
                for (int i = 0; i < m_cover->count(); i++)
                {
                    QListWidgetItem *it = m_cover->item(i);
                    if (it && it->data(Qt::UserRole).toString() == p &&
                        it->data(Qt::UserRole + 3).toInt() == 0)
                    {
                        it->setIcon(QIcon(QPixmap::fromImage(ic)));
                        it->setData(Qt::UserRole + 3, 1);
                        /* v0.89.0 W8：卡片内封面 QLabel 同步刷新 */
                        QWidget *cw2 = m_cover->itemWidget(it);
                        QLabel *cv2 = cw2 ? cw2->findChild<QLabel *>() : nullptr;
                        if (cv2)
                        {
                            QPixmap np = QPixmap::fromImage(ic);
                            if (cv2->pixmap() && !cv2->pixmap()->isNull())
                                np = np.scaled(cv2->pixmap()->size(),
                                               Qt::KeepAspectRatioByExpanding,
                                               Qt::SmoothTransformation);
                            cv2->setPixmap(w8RoundedCover(np, Db::isFavorite(p)));
                        }
                    }
                }
            }, Qt::QueuedConnection);
        }
        QMetaObject::invokeMethod(this, [this, done]
        {
            m_coverGenActive = false;
            evt("cover_gen", QString("done=%1").arg(done));
        }, Qt::QueuedConnection);
    }));
}

int LibraryWindow::coversGenSync(int limit)
{
    loadTable(); /* 行集 → 封面镜像项 */
    QList<Db::MetaRow> rows;
    for (const Db::MetaRow &r : Db::listLibrary())
        if (!m_onlyFav || Db::isFavorite(r.path)) rows.append(r);
    int done = 0;
    for (const Db::MetaRow &r : rows)
    {
        if (done >= limit) break;
        const QString png = coverPathFor(r.path);
        if (QFileInfo(png).isFile()) { done++; continue; }
        void *doc = hr_qt_open(hr::openablePath(r.path).toUtf8().constData());
        if (!doc) continue;
        int w = 0, h = 0;
        unsigned char *rgb = nullptr;
        if (hr_qt_render(doc, 0, 320, 0, 0, &w, &h, &rgb) == 0 && rgb)
        {
            QImage img(rgb, w, h, w * 3, QImage::Format_RGB888);
            QDir().mkpath(QFileInfo(png).absolutePath());
            if (img.copy().save(png)) done++;
            free(rgb);
        }
        hr_qt_close(doc);
    }
    evt("covers_sync", QString("done=%1").arg(done));
    return done;
}

/* v0.99.2 W22：全文搜索——引擎逐页匹配书库（后台线程，引擎全局锁保证安全）；
 * 命中列表 书名/页码，双击经 openAtRequested 跳页打开 */
void LibraryWindow::onFullTextSearch()
{
    const QString kw = QInputDialog::getText(
        this, hr::l10n::s("全文搜索", "Full-text search"),
        hr::l10n::s("在书库内搜索正文关键词（每本最多前 80 页）：",
                    "Search book text (first 80 pages per book):"));
    const QString needle = kw.trimmed();
    if (needle.isEmpty()) return;
    evt("fts_start", "kw=" + needle.left(40));
    const QList<Db::MetaRow> rows = Db::listLibrary();
    statusBar()->showMessage(hr::l10n::s("正在全文搜索…", "Searching…"), 0);
    struct FtsHit
    {
        QString path, title;
        int page;
    };
    QThreadPool::globalInstance()->start(QRunnable::create([this, needle, rows]
    {
        QVector<FtsHit> hits;
        const QByteArray nb = needle.toUtf8();
        for (const Db::MetaRow &r : rows)
        {
            void *doc = hr_qt_open(hr::openablePath(r.path).toUtf8().constData());
            if (!doc) continue;
            const int pages = qMin(hr_qt_count(doc), 80);
            for (int pg = 0; pg < pages && hits.size() < 80; ++pg)
            {
                if (hr_qt_search_page(doc, pg, nb.constData()) > 0)
                {
                    FtsHit h;
                    h.path = r.path;
                    h.title = r.title.isEmpty() ? QFileInfo(r.path).fileName() : r.title;
                    h.page = pg;
                    hits.append(h);
                }
            }
            hr_qt_close(doc);
            if (hits.size() >= 80) break;
        }
        QMetaObject::invokeMethod(this, [this, hits]
        {
            statusBar()->clearMessage();
            evt("fts_done", QString("hits=%1").arg(hits.size()));
            if (hits.isEmpty())
            {
                QMessageBox::information(this, hr::l10n::s("全文搜索", "Full-text search"),
                                         hr::l10n::s("书库内未找到该关键词。", "No matches in library."));
                return;
            }
            QDialog dlg(this);
            dlg.setWindowTitle(hr::l10n::s("全文搜索结果", "Full-text results"));
            dlg.resize(640, 480);
            QVBoxLayout *lay = new QVBoxLayout(&dlg);
            QTreeWidget *tw = new QTreeWidget(&dlg);
            tw->setColumnCount(2);
            tw->setHeaderLabels(QStringList() << hr::l10n::s("书名", "Title")
                                              << hr::l10n::s("页码", "Page"));
            tw->header()->setSectionResizeMode(0, QHeaderView::Stretch);
            tw->setRootIsDecorated(false);
            tw->setAlternatingRowColors(true);
            for (const FtsHit &h : hits)
            {
                QTreeWidgetItem *it = new QTreeWidgetItem(tw);
                it->setText(0, h.title);
                it->setText(1, QString::number(h.page + 1));
                it->setData(0, Qt::UserRole, h.path);
                it->setData(0, Qt::UserRole + 1, h.page);
            }
            lay->addWidget(tw, 1);
            connect(tw, &QTreeWidget::itemDoubleClicked, &dlg,
                    [this, &dlg](QTreeWidgetItem *it, int)
            {
                evt("fts_open", "page=" + it->text(1));
                dlg.accept();
                emit openAtRequested(it->data(0, Qt::UserRole).toString(),
                                     it->data(0, Qt::UserRole + 1).toInt());
            });
            QDialogButtonBox *bb = new QDialogButtonBox(QDialogButtonBox::Close, &dlg);
            connect(bb, &QDialogButtonBox::rejected, &dlg, &QDialog::reject);
            lay->addWidget(bb);
            dlg.exec();
        }, Qt::QueuedConnection);
    }));
}

/* P2 适配（D12）：聚合桶柱状（周/月/年；最后一根=强调色，样式对齐 WIN BucketBars） */
class StatsBars : public QWidget
{
public:
    QVector<QPair<QString, int>> buckets; /* label, minutes */
    explicit StatsBars(QWidget *parent = nullptr) : QWidget(parent) { setMinimumHeight(130); }
protected:
    void paintEvent(QPaintEvent *) override
    {
        QPainter p(this);
        const int n = buckets.size();
        if (n <= 0) return;
        const QColor accent = palette().color(QPalette::Highlight);
        const QColor soft = QColor(accent.red(), accent.green(), accent.blue(), 110);
        int mx = 1;
        for (const auto &b : buckets) mx = qMax(mx, b.second);
        const int gap = n > 14 ? 3 : 10;
        const qreal bw = (qreal)(width() - gap * (n + 1)) / n;
        QFont lf = font();
        lf.setPointSize(7);
        p.setFont(lf);
        for (int i = 0; i < n; i++)
        {
            const int v = buckets[i].second;
            const int h = (int)((qreal)(height() - 26) * v / mx);
            p.fillRect(QRectF(gap + i * (bw + gap), 12 + (height() - 26 - h), bw, h),
                       i == n - 1 ? accent : soft);
            p.setPen(palette().color(QPalette::PlaceholderText));
            p.drawText(QRectF(gap + i * (bw + gap), height() - 26, bw, 12),
                       Qt::AlignCenter, buckets[i].first);
            p.drawText(QRectF(gap + i * (bw + gap), height() - 14, bw, 12),
                       Qt::AlignCenter,
                       v > 0 ? QString("%1分").arg(v) : QString("—"));
        }
    }
};

void LibraryWindow::onStats()
{
    const QVector<hr::Stats::DayRow> rows = hr::Stats::rows(14);
    const QVector<hr::Stats::DayRow> all = hr::Stats::allDays(); /* P2（D12） */
    QDialog dlg(this);
    dlg.setWindowTitle(hr::l10n::s("阅读统计", "Reading stats"));
    dlg.resize(520, 560);
    QVBoxLayout *lay = new QVBoxLayout(&dlg);
    QTabWidget *tabs = new QTabWidget(&dlg);
    lay->addWidget(tabs, 1);
    /* P2 适配（D12）：周/月/年聚合（镜像 WIN PeriodStats：周一锚/自然月/全部年份） */
    auto weeklyBk = [&all]()
    {
        QMap<QDate, int> mp;
        const QDate cur = QDate::currentDate();
        const QDate curMon = cur.addDays(-(cur.dayOfWeek() - 1));
        for (const auto &d : all)
        {
            const QDate dt = QDate::fromString(d.day, "yyyy-MM-dd");
            if (!dt.isValid()) continue;
            mp[dt.addDays(-(dt.dayOfWeek() - 1))] += d.minutes;
        }
        QVector<QPair<QString, int>> out;
        for (int i = 11; i >= 0; i--)
        {
            const QDate m = curMon.addDays(-i * 7);
            out.append(qMakePair(QString("%1/%2").arg(m.month(), 2, 10, QChar('0'))
                                                 .arg(m.day(), 2, 10, QChar('0')),
                                  mp.value(m, 0)));
        }
        return out;
    };
    auto monthlyBk = [&all]()
    {
        QMap<int, int> mp;
        const QDate cur = QDate::currentDate();
        for (const auto &d : all)
        {
            const QDate dt = QDate::fromString(d.day, "yyyy-MM-dd");
            if (dt.isValid()) mp[dt.year() * 100 + dt.month()] += d.minutes;
        }
        QVector<QPair<QString, int>> out;
        for (int i = 11; i >= 0; i--)
        {
            const QDate m = cur.addMonths(-i);
            out.append(qMakePair(QString("%1/%2").arg(m.year() % 100).arg(m.month()),
                                  mp.value(m.year() * 100 + m.month(), 0)));
        }
        return out;
    };
    auto yearlyBk = [&all]()
    {
        QMap<int, int> mp;
        for (const auto &d : all)
        {
            const QDate dt = QDate::fromString(d.day, "yyyy-MM-dd");
            if (dt.isValid()) mp[dt.year()] += d.minutes;
        }
        if (mp.isEmpty()) mp[QDate::currentDate().year()] = 0;
        QVector<QPair<QString, int>> out;
        for (auto it = mp.constBegin(); it != mp.constEnd(); ++it)
            out.append(qMakePair(QString::number(it.key()), it.value()));
        return out;
    };
    auto mkBars = [&tabs](const QVector<QPair<QString, int>> &bk, const QString &title)
    {
        QWidget *pg = new QWidget;
        QVBoxLayout *pl = new QVBoxLayout(pg);
        pl->addWidget(new QLabel(title, pg));
        StatsBars *bars = new StatsBars(pg);
        bars->buckets = bk;
        pl->addWidget(bars, 1);
        tabs->addTab(pg, title);
    };
    mkBars(weeklyBk(), hr::l10n::s("近 12 周（阅读分钟）", "Last 12 weeks (min)"));
    mkBars(monthlyBk(), hr::l10n::s("近 12 月（阅读分钟）", "Last 12 months (min)"));
    mkBars(yearlyBk(), hr::l10n::s("全部年份（阅读分钟）", "All years (min)"));
    QWidget *dayPg = new QWidget;
    QVBoxLayout *dayLay = new QVBoxLayout(dayPg);
    tabs->addTab(dayPg, hr::l10n::s("近 14 天明细", "14-day detail"));
    QTreeWidget *tw = new QTreeWidget(dayPg);
    tw->setColumnCount(4);
    tw->setHeaderLabels(QStringList() << hr::l10n::s("日期", "Day")
                                      << hr::l10n::s("分钟", "Min")
                                      << hr::l10n::s("页数", "Pages")
                                      << hr::l10n::s("书目", "Books"));
    tw->setRootIsDecorated(false);
    tw->setAlternatingRowColors(true);
    dayLay->addWidget(tw, 1);
    int tm = 0, tp = 0;
    for (const hr::Stats::DayRow &r : rows)
    {
        new QTreeWidgetItem(tw, QStringList() << r.day << QString::number(r.minutes)
                                              << QString::number(r.pages)
                                              << QString::number(r.books));
        tm += r.minutes;
        tp += r.pages;
    }
    QLabel *lb = new QLabel(
        QString(hr::l10n::s("近 14 天：%1 分钟 / %2 页；累计书目 %3 本",
                            "14 days: %1 min / %2 pages; %3 books"))
            .arg(tm).arg(tp).arg(hr::Stats::totalBooks()), dayPg);
    dayLay->addWidget(lb);
    QHBoxLayout *bl = new QHBoxLayout;
    QPushButton *clr = new QPushButton(hr::l10n::s("清空统计…", "Clear…"), &dlg);
    QPushButton *close = new QPushButton(hr::l10n::s("关闭", "Close"), &dlg);
    bl->addWidget(clr);
    bl->addStretch(1);
    bl->addWidget(close);
    lay->addLayout(bl);
    connect(clr, &QPushButton::clicked, &dlg, [this, &dlg]
    {
        dlg.accept();
        onClearStatsDialog();
    });
    connect(close, &QPushButton::clicked, &dlg, &QDialog::accept);
    evt("stats_view", QString("days=%1").arg(rows.size()));
    dlg.exec();
}

/* ---------- U4 偏好页（一级行序照 PrefsScreen；二级组照《偏好二级对照表》9 组） ---------- */

void LibraryWindow::buildPrefsPage()
{
    m_prefsPage = new QWidget;
    QVBoxLayout *pl = new QVBoxLayout(m_prefsPage);
    pl->setContentsMargins(0, 0, 0, 0);
    QScrollArea *sa = new QScrollArea;
    sa->setWidgetResizable(true);
    sa->setFrameShape(QFrame::NoFrame);
    QWidget *root = new QWidget;
    QVBoxLayout *lay = new QVBoxLayout(root);
    lay->setContentsMargins(12, 8, 12, 16);
    lay->setSpacing(6);
    QVBoxLayout *cur = lay; /* v0.80.0 V5：当前组容器（group() 切换） */
    sa->setWidget(root);
    pl->addWidget(sa);

    /* v0.85.0 W4：两级结构对齐 Compose PrefsScreen——
     * section() = SectionBar（不折叠的色条头，3 个）；sub() = ExpandRow（可折叠二级组 ▾/▸，默认展开）。
     * 行助手均捕获 &cur，section/sub 重定向 cur 即可把后续行装进对应容器。 */
    auto section = [&](const QString &zh, const QString &en)
    {
        QLabel *hd = new QLabel(hr::l10n::s(zh, en));
        hd->setStyleSheet(QString::fromLatin1(
            "background:palette(highlight); color:white; padding:4px 8px; font-weight:bold;"));
        lay->addWidget(hd);
        cur = lay; /* 节级行直接进主布局 */
    };
    auto sub = [&](const QString &zh, const QString &en)
    {
        const QString base = hr::l10n::s(zh, en);
        QPushButton *hd = new QPushButton(base + QString::fromUtf8("  \xE2\x96\xBE"));
        hd->setCursor(Qt::PointingHandCursor);
        hd->setStyleSheet(QString::fromLatin1(
            "QPushButton{background:transparent; color:palette(text); padding:6px 4px 4px 4px;"
            " font-weight:bold; text-align:left; border:none;"
            " border-bottom:1px solid palette(mid); font-size:%1px;}")
                              .arg(hr::Ui::fsPx(13)));
        QWidget *box = new QWidget;
        QVBoxLayout *bl = new QVBoxLayout(box);
        bl->setContentsMargins(12, 4, 0, 6); /* 二级组内容缩进 12，对齐安卓二级层级 */
        bl->setSpacing(6);
        lay->addWidget(hd);
        lay->addWidget(box);
        cur = bl;
        connect(hd, &QPushButton::clicked, hd, [hd, box, base]
        {
            const bool show = !box->isVisible();
            box->setVisible(show);
            hd->setText(base + (show ? QString::fromUtf8("  \xE2\x96\xBE")
                                     : QString::fromUtf8("  \xE2\x96\xB8")));
        });
    };
    auto saveKey = [](const QString &key, const QVariant &v)
    {
        QSettings st(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
        st.setValue(key, v);
    };
    auto readKey = [](const QString &key, const QVariant &def) -> QVariant
    {
        return QSettings(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat)
            .value(key, def);
    };
    auto chk = [this, &cur, saveKey, readKey](const QString &zh, const QString &en,
                                             const QString &key, bool def,
                                             const std::function<void(bool)> &on = {})
        -> QCheckBox *
    {
        QCheckBox *c = new QCheckBox(hr::l10n::s(zh, en));
        c->setChecked(readKey(key, def).toBool());
        connect(c, &QCheckBox::toggled, this, [this, key, on, saveKey](bool v)
        {
            saveKey(key, v);
            evt("pref_set", key + "=" + (v ? "1" : "0"));
            if (on) on(v);
        });
        cur->addWidget(c);
        return c;
    };
    auto btnRow = [&cur](const QString &zh, const QString &en, const QString &btnZh,
                        const QString &btnEn, const std::function<void()> &on,
                        bool enabled = true, const QString &tip = QString())
    {
        QWidget *row = new QWidget;
        QHBoxLayout *h = new QHBoxLayout(row);
        h->setContentsMargins(0, 0, 0, 0);
        h->addWidget(new QLabel(hr::l10n::s(zh, en)), 1);
        QPushButton *b = new QPushButton(hr::l10n::s(btnZh, btnEn));
        b->setEnabled(enabled);
        if (!tip.isEmpty()) b->setToolTip(tip);
        QObject::connect(b, &QPushButton::clicked, on);
        h->addWidget(b);
        cur->addWidget(row);
    };
    auto spinRow = [&cur, saveKey, readKey](const QString &zh, const QString &en,
                                           const QString &key, int def, int lo, int hi,
                                           int step = 1)
    {
        QWidget *row = new QWidget;
        QHBoxLayout *h = new QHBoxLayout(row);
        h->setContentsMargins(0, 0, 0, 0);
        h->addWidget(new QLabel(hr::l10n::s(zh, en)), 1);
        QSpinBox *sp = new QSpinBox;
        sp->setRange(lo, hi);
        sp->setSingleStep(step);
        sp->setValue(readKey(key, def).toInt());
        QObject::connect(sp, QOverload<int>::of(&QSpinBox::valueChanged),
                         [key, saveKey](int v) { saveKey(key, v); });
        h->addWidget(sp);
        cur->addWidget(row);
    };
    auto sliderRow = [this, &cur, saveKey, readKey](
                         const QString &zh, const QString &en, const QString &key, int def,
                         int lo, int hi, const std::function<void(int)> &on)
    {
        QWidget *row = new QWidget;
        QHBoxLayout *h = new QHBoxLayout(row);
        h->setContentsMargins(0, 0, 0, 0);
        h->addWidget(new QLabel(hr::l10n::s(zh, en)));
        QSlider *sl = new QSlider(Qt::Horizontal);
        sl->setRange(lo, hi);
        const int curV = readKey(key, def).toInt();
        sl->setValue(curV);
        QLabel *vl = new QLabel(QString::number(curV));
        vl->setMinimumWidth(34);
        connect(sl, &QSlider::valueChanged, this, [this, key, on, vl, saveKey](int v)
        {
            vl->setText(QString::number(v));
            saveKey(key, v);
            evt("pref_set", key + "=" + QString::number(v));
            if (on) on(v);
        });
        h->addWidget(sl, 1);
        h->addWidget(vl);
        cur->addWidget(row);
    };
    auto comboRow = [&cur, saveKey, readKey](
                        const QString &zh, const QString &en, const QString &key,
                        const QStringList &names, const QVariantList &datas,
                        const QVariant &curVal,
                        const std::function<void(const QVariant &)> &on = {}) -> QComboBox *
    {
        QWidget *row = new QWidget;
        QHBoxLayout *h = new QHBoxLayout(row);
        h->setContentsMargins(0, 0, 0, 0);
        h->addWidget(new QLabel(hr::l10n::s(zh, en)), 1);
        QComboBox *cb = new QComboBox;
        for (int i = 0; i < names.size(); i++) cb->addItem(names[i], datas[i]);
        if (!curVal.isNull()) cb->setCurrentIndex(qMax(0, cb->findData(curVal)));
        QObject::connect(cb, QOverload<int>::of(&QComboBox::currentIndexChanged),
                         [key, cb, on, saveKey](int i)
        {
            const QVariant d = cb->itemData(i);
            if (!key.isEmpty())
            {
                saveKey(key, d);
                evt("pref_set", key + "=" + d.toString().left(30));
            }
            if (on) on(d);
        });
        h->addWidget(cb);
        cur->addWidget(row);
        return cb;
    };
    auto note = [&cur](const QString &zh, const QString &en)
    {
        QLabel *lb = new QLabel(hr::l10n::s(zh, en));
        lb->setStyleSheet("color:gray;");
        lb->setWordWrap(true);
        cur->addWidget(lb);
    };

    /* --- 标题行 + 退出程序 --- */
    {
        QWidget *row = new QWidget;
        QHBoxLayout *h = new QHBoxLayout(row);
        h->setContentsMargins(0, 0, 0, 0);
        QLabel *tt = new QLabel(hr::l10n::s("偏好", "Preferences"));
        tt->setStyleSheet(QString::fromLatin1("font-size:%1px; font-weight:bold;").arg(hr::Ui::fsPx(16)));
        QPushButton *quit = new QPushButton(hr::l10n::s("退出程序", "Quit"));
        quit->setFlat(true);
        quit->setCursor(Qt::PointingHandCursor);
        quit->setStyleSheet("color:palette(highlight); text-decoration:underline; border:none;");
        connect(quit, &QPushButton::clicked, this, [this]
        {
            if (QMessageBox::question(this, hr::l10n::s("退出程序", "Quit"),
                                      hr::l10n::s("确定退出 HowRead？", "Quit HowRead?")) ==
                QMessageBox::Yes)
            {
                evt("app_quit", "menu");
                qApp->quit();
            }
        });
        h->addWidget(tt, 1);
        h->addWidget(quit);
        cur->addWidget(row);
    }

    /* --- 配置文件行 --- */
    {
        QWidget *row = new QWidget;
        QHBoxLayout *h = new QHBoxLayout(row);
        h->setContentsMargins(0, 0, 0, 0);
        QLabel *av = new QLabel(currentProfileName().left(1).toUpper());
        av->setFixedSize(28, 28);
        av->setAlignment(Qt::AlignCenter);
        av->setStyleSheet(
            "background:palette(highlight); color:white; border-radius:14px; font-weight:bold;");
        h->addWidget(av);
        h->addWidget(new QLabel(QString(hr::l10n::s("当前配置：%1（便携）",
                                                    "Profile: %1 (portable)"))
                                    .arg(currentProfileName()),
                                row),
                     1);
        QPushButton *mg = new QPushButton(hr::l10n::s("管理…", "Manage…"));
        connect(mg, &QPushButton::clicked, this, &LibraryWindow::onProfilesDialog);
        h->addWidget(mg);
        cur->addWidget(row);
    }
    {
        QWidget *row = new QWidget;
        QHBoxLayout *h = new QHBoxLayout(row);
        h->setContentsMargins(0, 0, 0, 0);
        QPushButton *rs = new QPushButton(hr::l10n::s("恢复默认配置", "Restore defaults"));
        connect(rs, &QPushButton::clicked, this, [this]
        {
            if (QMessageBox::warning(
                    this, hr::l10n::s("恢复默认配置", "Restore defaults"),
                    hr::l10n::s("将清除全部偏好/阅读排版/语言设置，重启后生效。继续？",
                                "Clears preferences, reader layout and language settings. "
                                "Takes effect after restart. Continue?"),
                    QMessageBox::Yes | QMessageBox::No, QMessageBox::No) !=
                QMessageBox::Yes)
                return;
            QFile f(hr::dataDir() + "/.hr_reset_defaults");
            f.open(QIODevice::WriteOnly);
            f.close();
            evt("pref_reset_marker", QString());
            QMessageBox::information(this, hr::l10n::s("恢复默认配置", "Restore defaults"),
                                     hr::l10n::s("已标记，重启应用后恢复出厂配置。",
                                                 "Marked. Restart to apply."));
        });
        QPushButton *dd = new QPushButton(hr::l10n::s("更改数据目录", "Change data folder"));
        connect(dd, &QPushButton::clicked, this, [this]
        {
            const QString d = QFileDialog::getExistingDirectory(
                this, hr::l10n::s("选择数据目录（重启生效）", "Choose data folder (restart)"));
            if (d.isEmpty()) return;
            QFile f(QCoreApplication::applicationDirPath() + "/data-dir.txt");
            if (f.open(QIODevice::WriteOnly | QIODevice::Truncate))
            {
                f.write(d.toUtf8());
                evt("pref_datadir", "path=" + d.left(90));
            }
            QMessageBox::information(this, hr::l10n::s("更改数据目录", "Change data folder"),
                                     hr::l10n::s("已写入 data-dir.txt，重启后生效。",
                                                 "data-dir.txt written. Restart to apply."));
        });
        h->addWidget(rs);
        h->addWidget(dd);
        h->addStretch(1);
        cur->addWidget(row);
    }
    cur->addWidget(new QLabel(hr::l10n::s("数据目录：", "Data folder: ") + hr::dataDir()));

    /* --- ■ 书库设置 --- */
    section("书库设置", "Library");

    /* ① 格式配置：13 组勾选（17 扩展名归组，带计数），改动即重扫+过滤 */
    sub("格式配置", "Formats"); /* v0.85.0 W4 二级组 */
    {
        QStringList en;
        if (QSettings(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat)
                .contains("lib/exts"))
            en = readKey("lib/exts", QString())
                     .toString()
                     .split(',', QString::SkipEmptyParts);
        else
            en = m_exts; /* 默认全开 */
        QHash<QString, int> cnt;
        for (const Db::MetaRow &r : Db::listLibrary())
            cnt[QFileInfo(r.path).suffix().toLower()]++;
        struct Fmt
        {
            const char *zh, *en, *exts;
        };
        const Fmt fmts[13] = {
            {"PDF", "PDF", "pdf"},           {"EPUB", "EPUB", "epub"},
            {"TXT", "TXT", "txt"},           {"MOBI/PRC", "MOBI/PRC", "mobi,prc"},
            {"AZW/AZW3", "AZW/AZW3", "azw,azw3"}, {"FB2", "FB2", "fb2"},
            {"HTML", "HTML", "html,htm"},    {"XPS", "XPS", "xps"},
            {"CBZ", "CBZ", "cbz"},           {"DOC/DOCX", "DOC/DOCX", "doc,docx"},
            {"DJVU", "DJVU", "djvu"},        {"RTF", "RTF", "rtf"},
            {"ODT", "ODT", "odt"}};
        QWidget *gridW = new QWidget;
        QGridLayout *gg = new QGridLayout(gridW);
        gg->setContentsMargins(12, 0, 0, 0);
        for (int i = 0; i < 13; i++)
        {
            const QStringList ge = QString::fromLatin1(fmts[i].exts).split(',');
            int n = 0;
            for (const QString &e : ge) n += cnt.value(e, 0);
            QCheckBox *c = new QCheckBox(
                QString("%1（%2）").arg(hr::l10n::s(fmts[i].zh, fmts[i].en)).arg(n));
            bool all = true;
            for (const QString &e : ge)
                if (!en.contains(e)) all = false;
            c->setChecked(all);
            gg->addWidget(c, i / 4, i % 4);
            const QStringList extsOf = ge;
            connect(c, &QCheckBox::toggled, this, [this, extsOf](bool on)
            {
                QSettings st(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
                QStringList cur = st.contains("lib/exts")
                                      ? st.value("lib/exts")
                                            .toString()
                                            .split(',', QString::SkipEmptyParts)
                                      : m_exts;
                for (const QString &e : extsOf)
                {
                    cur.removeAll(e);
                    if (on) cur.append(e);
                }
                cur.removeDuplicates();
                st.setValue("lib/exts", cur.join(','));
                m_exts = cur;
                evt("pref_set", QString("lib/exts=%1").arg(cur.join('+').left(80)));
                loadTable();
                scanAll();
            });
        }
        cur->addWidget(gridW);
        note("勾选即重新扫描并过滤书库；取消的格式不再入库显示。",
             "Toggling rescans and filters the library.");
    }

    /* ② 书库设置 */
    sub("书库设置", "Library settings"); /* v0.85.0 W4 二级组 */
    chk("启动时扫描新书", "Scan at launch", "lib/scanAtLaunch", true);
    chk("忽略 .nomedia 目录", "Skip .nomedia folders", "lib/nomediaSkip", true,
        [this](bool) { scanAll(); });
    chk("显示元数据书名（否则显示文件名）", "Use metadata title", "lib/usePdfMeta", true,
        [this](bool) { loadTable(); });
    chk("总是显示文件名", "Always show file name", "lib/showFileName", false,
        [this](bool) { loadTable(); });
    chk("隐藏读完的书", "Hide finished books", "lib/hideFinished", false,
        [this](bool) { loadTable(); });
    chk("外部打开自动入库", "Auto-add externally opened books", "reader/autoAdd", false);
    /* P1 适配（A5/A7）：Calibre opf + 元数据开关（对齐 WIN 书库设置组） */
    chk("Calibre metadata.opf 标题（扫描时优先采用）", "Calibre metadata.opf title",
        "lib/useCalibreOpf", true);
    chk("标题显示丛书号（系列 #序）", "Series number in title", "lib/seriesNumInTitle", true);
    chk("作者姓氏在前（排序）", "Author surname first", "lib/authorSurnameFirst", false);
    chk("文件信息显示书籍描述", "Show description in file info", "lib/showDescription", true);

    /* ③ 书库显示配置 */
    sub("书库显示配置", "Library display"); /* v0.85.0 W4 二级组 */
    {
        QLabel *lb = new QLabel(hr::l10n::s("列表行封面 40×56（固定，对齐安卓列表行形态）",
                                            "List row cover 40x56 (fixed, Android list row)"));
        lb->setStyleSheet("color:gray;");
        cur->addWidget(lb);
    }
    sliderRow("封面墙封面宽度", "Cover wall width", "ui/coverSize", 132, 110, 280,
              [this](int v)
              {
                  m_cover->setIconSize(QSize(v, v * 186 / 132));
                  m_cover->setGridSize(QSize(v + 18, v * 186 / 132 + 66)); /* v0.102.0 fix15：三行卡片，与构造器一致 */
                  /* v0.102.0 fix15：封面墙卡片为 setItemWidget 自绘——封面位图在 loadTable() 里
                     按当时 iconSize 一次性生成，只改网格度量拖动仅"变一点"（Compose 的网格滑杆
                     直接驱动单元格尺寸重组重绘，故无此问题）。防抖 150ms 后按新尺寸重建条目；
                     valueChanged 拖拽逐 tick 触发，singleShot 重启合并为拖拽结束后一次重建。 */
                  if (!m_coverRebuildTimer)
                  {
                      m_coverRebuildTimer = new QTimer(this);
                      m_coverRebuildTimer->setSingleShot(true);
                      connect(m_coverRebuildTimer, &QTimer::timeout, this,
                              [this] { loadTable(); });
                  }
                  m_coverRebuildTimer->start(150);
              });
    cur->addWidget(new QLabel(hr::l10n::s("网格列数：自适应窗口", "Grid columns: adaptive")));

    /* ④ 书籍封面配置（Compose：封面裁剪/边框/立体感；Qt 封面墙为等比裁切，置灰说明） */
    sub("书籍封面配置", "Book covers"); /* v0.85.0 W4 二级组 */
    {
        QLabel *lb = new QLabel(hr::l10n::s(
            "封面裁剪/边框/立体感：Qt 版封面墙按等比居中裁切显示，无需单独配置。",
            "Cover crop/border/3D: Qt cover wall center-crops proportionally; no settings needed."));
        lb->setStyleSheet("color:gray;");
        lb->setWordWrap(true);
        cur->addWidget(lb);
    }

    /* ⑤ 阅读配置（书库设置节内二级组，v0.80.0 V5 对齐安卓/Compose 三组序） */
    sub("阅读配置", "Reading"); /* v0.85.0 W4：subHead → 可折叠二级组 */
    comboRow("默认阅读模式（开书生效）", "Default reading mode", "reader/scrollMode",
             QStringList{ hr::l10n::s("滚动模式", "Scroll"),
                          hr::l10n::s("翻页模式", "Page") },
             QVariantList{ true, false },
             readKey("reader/scrollMode", true).toBool());
    chk("启动时打开上次读的书", "Open last book at start", "reader/openLastOnStart", false);
    {
        /* U8：密码锁行（对齐 PrefsScreen「打开应用时需输入密码」：勾选态=已设；点击行→设置/清除） */
        QSettings stL(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
        QWidget *rowL = new QWidget;
        QHBoxLayout *hh = new QHBoxLayout(rowL);
        hh->setContentsMargins(0, 0, 0, 0);
        QCheckBox *lc =
            new QCheckBox(hr::l10n::s("打开应用时需输入密码", "Password on start"), rowL);
        lc->setChecked(!stL.value("security/appLockPassword", QString())
                            .toString()
                            .isEmpty());
        QPushButton *pw = new QPushButton(hr::l10n::s("密码", "Password"), rowL);
        pw->setFlat(true);
        pw->setStyleSheet(QString::fromLatin1(
            "color:palette(link); text-decoration:underline; border:none;"));
        hh->addWidget(lc, 1);
        hh->addWidget(pw);
        cur->addWidget(rowL);
        connect(lc, &QCheckBox::clicked, this, [this, lc]
        {
            onLockPasswordDialog();
            QSettings stL2(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
            const bool has = !stL2.value("security/appLockPassword", QString())
                                  .toString()
                                  .isEmpty();
            lc->blockSignals(true);
            lc->setChecked(has);
            lc->blockSignals(false);
        });
        connect(pw, &QPushButton::clicked, rowL, [lc] { lc->click(); });
    }
    chk("总是从第 1 页打开", "Always open at page 1", "reader/openFirstPage", false);
    chk("记住搜索词", "Remember search text", "reader/rememberSearch", true);
    chk("退出阅读前确认", "Confirm before closing book", "reader/exitConfirm", false);
    /* v0.85.0 W4 勾验：原“密码锁（U8 上线）”灰注已过期（密码锁行已在上方实装），删除 */

    /* --- ■ 常规设置 --- */
    section("常规设置", "General");

    /* 调试日志（Compose ExpandRow trailing=日志路径 → 首行灰字路径） */
    sub("调试日志", "Debug log"); /* v0.85.0 W4 二级组 */
    {
        QLabel *lp = new QLabel(QDir::toNativeSeparators(hr::logsDir()));
        lp->setStyleSheet("color:gray;");
        cur->addWidget(lp);
    }
    chk("记录调试日志", "Write debug log", "dbg/logEnabled", true);
    btnRow("调试日志", "Debug log", "打开日志文件夹", "Open log folder", [this]
    { QDesktopServices::openUrl(QUrl::fromLocalFile(hr::logsDir())); });
    btnRow("", "", "清空日志", "Clear log", [this]
    {
        QFile::remove(hr::logsDir() + "/hrtest.log");
        evt("pref_log_clear", QString());
    });

    /* 屏幕与语言（节级行，Compose 无对应二级组） */
    cur = lay; /* v0.85.0 W4：回到节级 */
    comboRow("启动屏幕", "Startup window", "ui/maximized",
             QStringList{ hr::l10n::s("正常", "Normal"), hr::l10n::s("最大化", "Maximized") },
             QVariantList{ false, true }, readKey("ui/maximized", false).toBool());
    {
        /* U8 缺陷#12：首项“跟随系统”(data="")，有 lang.json 时选当前语言，修复初始空白 */
        QStringList langNames{ hr::l10n::s("跟随系统", "System") };
        QVariantList langData{ QString() };
        for (const QString &s : hr::l10n::languages())
        {
            langNames << s;
            langData << s;
        }
        const QString curLang =
            QFile::exists(hr::dataDir() + "/lang.json") ? hr::l10n::currentLang()
                                                        : QString();
        comboRow("语言（重启生效）", "Language (restart)", "", langNames, langData,
                 curLang,
                 [](const QVariant &v)
                 {
                     hr::l10n::setLang(v.toString());
                     evt("pref_set", "lang=" + v.toString().left(20));
                 });
    }

    /* WebDAV 同步 / AI / 文件关联 */
    btnRow("WebDAV 同步 (Pro)", "WebDAV sync (Pro)", "打开同步设置…", "Open…", [this]
    { onSync(); });
    btnRow("AI 大模型 (Pro)", "AI model (Pro)", "打开 AI 配置…", "Open…", [this]
    { hr::m4AiConfigDialog(this); });
    sub("备份配置", "Backup"); /* v0.85.0 W4 二级组（块前移对齐 Compose 顺序） */
    btnRow("备份配置", "Backup", "导出配置…", "Export…", [this]
    {
        const QString d = QFileDialog::getExistingDirectory(
            this, hr::l10n::s("导出配置到目录", "Export configuration to folder"));
        if (d.isEmpty()) return;
        QDir(d).mkpath(QString::fromLatin1("books"));
        int ok = 0;
        for (const QString &n :
             {QString::fromLatin1("qt-prefs.ini"), QString::fromLatin1("reader.json"),
              QString::fromLatin1("lang.json")})
            if (QFile::copy(hr::dataDir() + "/" + n, d + "/" + n)) ok++;
        /* P0 适配（F2）：备份含 howread.db（VACUUM INTO 一致性快照——书库/进度/书签/注解） */
        QFile::remove(d + "/howread.db");
        if (Db::vacuumInto(d + "/howread.db"))
            ok++;
        else
            evt("backup_db_fail", QString());
        int nb = 0;
        QDir bd(hr::dataDir() + "/books");
        for (const QFileInfo &fi : bd.entryInfoList(QDir::Files))
            if (QFile::copy(fi.absoluteFilePath(), d + "/books/" + fi.fileName())) nb++;
        evt("pref_backup_export", QString("files=%1 books=%2").arg(ok).arg(nb));
        QMessageBox::information(this, hr::l10n::s("备份配置", "Backup"),
                                 hr::l10n::s("已导出配置与 %1 个同步书档案到 %2",
                                             "Exported config and %1 sync files to %2")
                                     .arg(nb)
                                     .arg(d));
    });
    btnRow("", "", "导入配置…", "Import…", [this]
    {
        const QString d = QFileDialog::getExistingDirectory(
            this, hr::l10n::s("从目录导入配置", "Import configuration from folder"));
        if (d.isEmpty()) return;
        int ok = 0;
        for (const QString &n :
             {QString::fromLatin1("qt-prefs.ini"), QString::fromLatin1("reader.json"),
              QString::fromLatin1("lang.json")})
            if (QFile::copy(d + "/" + n, hr::dataDir() + "/" + n)) ok++;
        /* P0 适配（F2）：导入 howread.db——落 .import 暂存，重启时替换（见 main.cpp） */
        if (QFile::exists(d + "/howread.db"))
        {
            QFile::remove(hr::dataDir() + "/howread.db.import");
            if (QFile::copy(d + "/howread.db", hr::dataDir() + "/howread.db.import")) ok++;
        }
        int nb = 0;
        QDir bd(d + "/books");
        for (const QFileInfo &fi : bd.entryInfoList(QDir::Files))
            if (QFile::copy(fi.absoluteFilePath(),
                            hr::dataDir() + "/books/" + fi.fileName()))
                nb++;
        evt("pref_backup_import", QString("files=%1 books=%2").arg(ok).arg(nb));
        QMessageBox::information(this, hr::l10n::s("备份配置", "Backup"),
                                 hr::l10n::s("已导入（配置 %1 项、书档案 %2 个），重启后生效。",
                                             "Imported (%1 configs, %2 books). Restart to apply.")
                                     .arg(ok)
                                     .arg(nb));
    });


    sub("文件关联配置", "File association"); /* v0.85.0 W4 二级组 */
    btnRow("文件关联配置", "File associations", "打开文件关联…", "Open…", [this]
    { onAssoc(); });
    btnRow("文件关联配置", "File associations", "撤销全部关联", "Undo associations",
           [this]
    { onAssocUndo(); });

    /* 在线阅读（Qt 增设二级组；参数已接块缓存/熔断/重试策略） */
    sub("在线阅读 (Pro)", "Online reading (Pro)"); /* v0.85.0 W4 二级组 */
    note("在线阅读（Pro）——块缓存与重试参数（块缓存/断网熔断/HTTP Range 已接线）：",
         "Online reading (Pro) — block cache & retry parameters:");
    chk("在线优先", "Online first", "net/onlineFirst", false);
    /* v0.106.0（P0-1）：移除"仅 Wi-Fi"开关（桌面无计费网概念且原为死偏好）；
     * 整本阈值接通为"整本下载确认阈值"（0=不确认） */
    spinRow("整本下载确认阈值 (MB)", "Whole-book download confirm threshold (MB)",
            "net/thresholdMB", 20, 0, 200, 5);
    spinRow("前向窗口 (%)", "Forward window (%)", "net/windowFwdPct", 20, 0, 100, 5);
    spinRow("后向窗口 (%)", "Backward window (%)", "net/windowBackPct", 30, 0, 100, 5);
    spinRow("重试次数", "Retries", "net/retryN", 3, 1, 10);
    spinRow("重试间隔 (ms)", "Retry interval (ms)", "net/retryMs", 100, 50, 5000, 50);

    /* 缓存管理 */
    sub("缓存管理", "Cache"); /* v0.85.0 W4 二级组 */
    {
        auto dirSize = [](const QString &p) -> qint64
        {
            QDir d(p);
            qint64 s = 0;
            for (const QFileInfo &fi : d.entryInfoList(QDir::Files))
                s += fi.size();
            return s;
        };
        const qint64 covers = dirSize(hr::dataDir() + "/cache/covers");
        const qint64 blocks = dirSize(hr::dataDir() + "/cache/remote-blocks");
        cur->addWidget(new QLabel(
            hr::l10n::s("缓存占用：封面 %1 KB · 远程块 %2 KB",
                        "Cache: covers %1 KB, remote blocks %2 KB")
                .arg(covers / 1024)
                .arg(blocks / 1024)));
    }
    btnRow("缓存管理", "Cache", "清空封面缓存", "Clear covers", [this]
    {
        QDir(hr::dataDir() + "/cache/covers").removeRecursively();
        evt("pref_cache_clear", "covers");
    });
    btnRow("", "", "清空远程块缓存", "Clear remote blocks", [this]
    {
        QDir(hr::dataDir() + "/cache/remote-blocks").removeRecursively();
        evt("pref_cache_clear", "blocks");
    });
    spinRow("远程块缓存上限 (MB)", "Remote block cache cap (MB)", "cache/capMB", 500, 50,
            10000, 50);

    /* --- ■ UI配置 --- */
    section("UI配置", "UI");
    sub("主题配置", "Theme"); /* v0.85.0 W4 二级组 */
    {
        QCheckBox *dark =
            chk("暗色主题", "Dark theme", "ui/dark", false, [this](bool v)
            {
                /* chk 已把键写成 v；toggleDark 按“当前键取反”切换——先反向再切，净效果=v */
                QSettings st(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
                st.setValue("ui/dark", !v);
                toggleDark();
            });
        Q_UNUSED(dark);
    }
    /* v0.79.0 V4：主题颜色 / 着重色（对齐安卓主题配置组与 Compose 色块行：8 预设 + 默认，即时生效） */
    auto colorRow = [this, &cur](const QString &zh, const QString &en,
                                const QVector<QColor> &presets, const QColor &curC,
                                const std::function<void(const QColor &)> &set)
    {
        QWidget *row = new QWidget;
        QHBoxLayout *h = new QHBoxLayout(row);
        h->setContentsMargins(0, 0, 0, 0);
        h->addWidget(new QLabel(hr::l10n::s(zh, en)), 1);
        for (const QColor &c : presets)
        {
            QToolButton *b = new QToolButton;
            b->setFixedSize(22, 22);
            b->setCursor(Qt::PointingHandCursor);
            b->setToolTip(c.name(QColor::HexRgb));
            b->setStyleSheet(
                QString::fromLatin1("QToolButton{background:%1; border:%2; border-radius:11px;}")
                    .arg(c.name(QColor::HexRgb),
                         curC == c ? QString::fromLatin1("2px solid palette(text)")
                                  : QString::fromLatin1("1px solid rgba(0,0,0,40)")));
            connect(b, &QToolButton::clicked, this, [set, c] { set(c); });
            h->addWidget(b);
        }
        QPushButton *d = new QPushButton(hr::l10n::s("默认", "Default"));
        connect(d, &QPushButton::clicked, this, [set] { set(QColor()); });
        h->addWidget(d);
        cur->addWidget(row);
    };
    colorRow("主题颜色", "Theme color", hr::UiTheme::accentPresets(), hr::UiTheme::accentOverride(),
             [this](const QColor &c)
             {
                 hr::UiTheme::setAccent(c);
                 evt("pref_accent", hr::UiTheme::hex(c));
             });
    colorRow("着重色", "Accent color", hr::UiTheme::tealPresets(), hr::UiTheme::tealOverride(),
             [this](const QColor &c)
             {
                 hr::UiTheme::setAccentTeal(c);
                 evt("pref_accent_teal", hr::UiTheme::hex(c));
             });
    comboRow("字体大小（重启生效）", "Font size (restart)", "ui/appFontPct",
             QStringList{ hr::l10n::s("自动", "Auto"), "70%", "85%", "110%", "125%",
                          "150%", "175%", "200%" },
             QVariantList{ 100, 70, 85, 110, 125, 150, 175, 200 },
             readKey("ui/appFontPct", 100).toInt());
    comboRow("蓝光滤镜（暖色护眼）", "Blue-light filter (warm)", "ui/blueLight",
             QStringList{ hr::l10n::s("关", "Off"), hr::l10n::s("轻", "Light"),
                          hr::l10n::s("中", "Medium") },
             QVariantList{ 0, 1, 2 }, readKey("ui/blueLight", 0).toInt(),
             [this](const QVariant &v)
             {
                 QSettings stB(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
                 stB.setValue("ui/blueLight", v.toInt());
                 applyBlueLight();
             });
    note("阅读主题 5 款在阅读窗顶栏“主题”切换。",
         "Reader themes: switch from the reader window top bar.");

    /* 标签栏配置（Compose：显示左侧标签栏开关；安卓的位置/动画/拖拽排序桌面不适用） */
    sub("标签栏配置", "Tabs"); /* v0.85.0 W4 二级组 */
    chk("显示左侧栏（重启生效）", "Show side bar (restart)", "ui/sideBar", true);
    note("底部标签栏位置/动画/顺序拖拽为安卓专属，桌面版固定四标签。",
         "Tab position/animation/reorder are Android-only; desktop keeps 4 fixed tabs.");
}

/* ---------- U5 我的文件（BrowseScreen 对齐） / 网上书库独立页（RemoteScreen 对齐） ---------- */

/* v0.91.0 W12：本地位置行——整行可点进入浏览（行内按钮自行消费点击）；
 * QWidget 子类需自绘 QSS 背景（PE_Widget）才有 hover 高亮 */
class LocRowWidget : public QWidget
{
public:
    std::function<void()> onOpen;

protected:
    void mousePressEvent(QMouseEvent *e) override
    {
        if (onOpen) onOpen();
        QWidget::mousePressEvent(e);
    }
    void paintEvent(QPaintEvent *) override
    {
        QStyleOption opt;
        opt.initFrom(this);
        QPainter p(this);
        style()->drawPrimitive(QStyle::PE_Widget, &opt, &p, this);
    }
};

static QString remoteGroupStyle()
{
    /* v0.98.0 W19：节头去主题色底——透明背景+粗体正文色（对齐 Compose，用户点名背景太突兀） */
    return QString::fromLatin1(
        "background:transparent; color:palette(text); font-weight:bold; font-size:%1px;")
        .arg(hr::Ui::fsPx(15));
}

/* 协议根：webdav/opds → http://host[:port]；sftp 仅 host */
static QString remoteRootOf(const Db::RemoteSrc &s)
{
    QString h = s.host;
    if (s.protocol == "webdav" || s.protocol == "opds")
    {
        if (!h.startsWith("http://") && !h.startsWith("https://"))
            h = "http://" + h + ":" + QString::number(s.port > 0 ? s.port : (s.protocol == "opds" ? 80 : 8765));
        while (h.endsWith('/')) h.chop(1);
    }
    return h;
}

static QString joinSub(const QString &a, const QString &b)
{
    if (a.isEmpty()) return b;
    if (b.isEmpty()) return a;
    return a + "/" + b;
}

static QString parentSub(const QString &sub)
{
    const int i = sub.lastIndexOf('/');
    return i < 0 ? QString() : sub.left(i);
}

/* 网络书库组头行（我的文件页用）：协议名+添加按钮（回调由成员侧提供） */
static void addProtoRow(QVBoxLayout *lay, const QString &proto, const QString &zh,
                        const QString &en, bool enabled, const std::function<void()> &onAdd)
{
    QWidget *row = new QWidget;
    QHBoxLayout *h = new QHBoxLayout(row);
    h->setContentsMargins(0, 0, 0, 0);
    h->addWidget(new QLabel(hr::l10n::s(zh, en)), 1);
    QPushButton *b = new QPushButton(hr::l10n::s("添加…", "Add…"));
    b->setEnabled(enabled);
    if (!enabled)
        b->setToolTip(hr::l10n::s("SMB 需独立协议库，后续批次接入",
                                  "SMB needs a dedicated lib; arrives in a later batch"));
    QObject::connect(b, &QPushButton::clicked, onAdd);
    h->addWidget(b);
    lay->addWidget(row);
}

bool LibraryWindow::remoteServerForm(Db::RemoteSrc *io)
{
    QDialog dlg(this);
    dlg.setWindowTitle(hr::l10n::s("服务器", "Server"));
    dlg.resize(520, 340);
    QVBoxLayout *lay = new QVBoxLayout(&dlg);
    QTabWidget *tabs = new QTabWidget(&dlg);
    lay->addWidget(tabs);

    struct Proto
    {
        QString key, title;
        int defPort;
    };
    const Proto protos[4] = {{"webdav", "WEBDAV", 8765},
                             {"smb", "SMB", 445},
                             {"sftp", "SFTP", 22},
                             {"opds", "OPDS", 80}};
    QLineEdit *name[4], *host[4], *port[4], *path[4], *user[4], *pass[4];
    for (int i = 0; i < 4; i++)
    {
        QWidget *pg = new QWidget;
        QFormLayout *f = new QFormLayout(pg);
        name[i] = new QLineEdit(io->name, pg);
        host[i] = new QLineEdit(io->host, pg);
        host[i]->setPlaceholderText(protos[i].key == "webdav"
                                        ? "http://192.168.50.23:8765/"
                                        : (protos[i].key == "opds" ? "http://host/" : "192.168.50.23"));
        port[i] = new QLineEdit(pg);
        port[i]->setPlaceholderText(QString::number(protos[i].defPort));
        if (io->port > 0 && io->protocol == protos[i].key)
            port[i]->setText(QString::number(io->port));
        path[i] = new QLineEdit(io->path, pg);
        path[i]->setPlaceholderText("/");
        user[i] = new QLineEdit(io->user, pg);
        pass[i] = new QLineEdit(io->password, pg);
        pass[i]->setEchoMode(QLineEdit::Password);
        f->addRow(hr::l10n::s("名称", "Name"), name[i]);
        f->addRow(hr::l10n::s("主机", "Host"), host[i]);
        f->addRow(hr::l10n::s("端口", "Port"), port[i]);
        f->addRow(hr::l10n::s("路径", "Path"), path[i]);
        f->addRow(hr::l10n::s("用户名", "User"), user[i]);
        f->addRow(hr::l10n::s("密码", "Password"), pass[i]);
        if (protos[i].key == "smb")
            path[i]->setPlaceholderText("testbooks"); /* SMB：Path=共享名（可带子目录） */
        tabs->addTab(pg, protos[i].title);
    }
    /* 编辑预填/切 tab 自动补默认端口 */
    int cur = 0;
    for (int i = 0; i < 4; i++)
        if (io->protocol == protos[i].key) cur = i;
    tabs->setCurrentIndex(cur);
    if (io->id == 0 && port[cur]->text().isEmpty())
        port[cur]->setText(QString::number(protos[cur].defPort));
    connect(tabs, &QTabWidget::currentChanged, &dlg, [tabs, protos, port](int i)
    {
        if (port[i]->text().isEmpty())
            port[i]->setText(QString::number(protos[i].defPort));
    });

    QDialogButtonBox *bb = new QDialogButtonBox(
        QDialogButtonBox::Ok | QDialogButtonBox::Cancel, &dlg);
    bb->button(QDialogButtonBox::Ok)->setText(hr::l10n::s("保存", "Save"));
    bb->button(QDialogButtonBox::Cancel)->setText(hr::l10n::s("取消", "Cancel"));
    lay->addWidget(bb);
    connect(bb, &QDialogButtonBox::accepted, &dlg, &QDialog::accept);
    connect(bb, &QDialogButtonBox::rejected, &dlg, &QDialog::reject);

    /* 测试连接（当前 tab 协议，阻塞式，与旧对话框一致） */
    QPushButton *test = new QPushButton(hr::l10n::s("测试连接", "Test"), &dlg);
    lay->addWidget(test);
    connect(test, &QPushButton::clicked, &dlg, [&]
    {
        const int i = tabs->currentIndex();
        Db::RemoteSrc t;
        t.protocol = protos[i].key;
        t.host = host[i]->text().trimmed();
        t.port = port[i]->text().toInt();
        t.path = path[i]->text().trimmed();
        t.user = user[i]->text();
        t.password = pass[i]->text();
        QString err;
        bool ok = false;
        int n = 0;
        if (t.protocol == "webdav")
        {
            hr::WebDavClient dav(remoteRootOf(t), t.user, t.password);
            QVector<hr::DavEntry> es;
            ok = dav.list(joinSub(t.path, "/"), &es, &err);
            n = es.size();
        }
        else if (t.protocol == "sftp")
        {
            hr::SshClient ssh(t.host, t.port > 0 ? t.port : 22, t.user, t.password);
            ok = ssh.ok();
            if (ok)
            {
                const QList<hr::SshEntry> es = ssh.list(t.path, &err);
                n = es.size();
                ok = es.isEmpty() ? err.isEmpty() : true;
            }
            else err = ssh.error();
        }
        else if (t.protocol == "opds")
        {
            hr::OpdsClient op(remoteRootOf(t), t.user, t.password);
            const QList<hr::OpdsEntry> es = op.list(QString(), &err);
            n = es.size();
            ok = !es.isEmpty() || err.isEmpty();
        }
        else
        {
            err = hr::l10n::s("SMB 未接入", "SMB not available");
        }
        evt("remote_test", QString("proto=%1 ok=%2 n=%3 err=%4")
                               .arg(t.protocol).arg(ok ? 1 : 0).arg(n).arg(err.left(50)));
        QMessageBox::information(&dlg, hr::l10n::s("测试连接", "Test"),
                                 ok ? hr::l10n::s("连接成功，%1 项。", "Connected, %1 items.").arg(n)
                                    : hr::l10n::s("失败：%1", "Failed: %1").arg(err.left(120)));
    });

    /* P1 适配（E3）：OPDS 预置源（对齐 WIN 预置填充） */
    QPushButton *presetB =
        new QPushButton(hr::l10n::s("预置 OPDS 源…", "OPDS presets…"), &dlg);
    lay->addWidget(presetB);
    connect(presetB, &QPushButton::clicked, &dlg, [&]()
    {
        QMenu pm(&dlg);
        QAction *g = pm.addAction("Project Gutenberg");
        QAction *se = pm.addAction("Standard Ebooks");
        QAction *a = pm.exec(QCursor::pos());
        if (a == g)
        {
            host[3]->setText("https://m.gutenberg.org/ebooks.opds/");
            name[3]->setText("Project Gutenberg");
            port[3]->setText("443");
        }
        else if (a == se)
        {
            host[3]->setText("https://standardebooks.org/feeds/opds");
            name[3]->setText("Standard Ebooks");
            port[3]->setText("443");
        }
    });

    /* P1 适配（E2）：浏览远程目录选定路径（对齐 WIN RemoteDirPicker；webdav/sftp） */
    QPushButton *browseB =
        new QPushButton(hr::l10n::s("浏览目录选路径…", "Browse folders…"), &dlg);
    lay->addWidget(browseB);
    connect(browseB, &QPushButton::clicked, &dlg, [&]()
    {
        const int bi = tabs->currentIndex();
        if (protos[bi].key == "opds")
        {
            QMessageBox::information(&dlg, hr::l10n::s("浏览", "Browse"),
                                     hr::l10n::s("OPDS 请保存后进入浏览页导航。",
                                                 "Browse OPDS after saving."));
            return;
        }
        QDialog pd(&dlg);
        pd.setWindowTitle(hr::l10n::s("选择目录", "Choose folder"));
        pd.resize(480, 440);
        QVBoxLayout *pl = new QVBoxLayout(&pd);
        QLabel *curL = new QLabel(hr::l10n::s("当前：/", "Current: /"), &pd);
        QListWidget *lw = new QListWidget(&pd);
        pl->addWidget(curL);
        pl->addWidget(lw, 1);
        QDialogButtonBox *pb =
            new QDialogButtonBox(QDialogButtonBox::Ok | QDialogButtonBox::Cancel, &pd);
        pb->button(QDialogButtonBox::Ok)
            ->setText(hr::l10n::s("选择此目录", "Pick this folder"));
        pl->addWidget(pb);
        QString cur = path[bi]->text().trimmed();
        if (cur.isEmpty()) cur = QString::fromLatin1("/");
        std::function<void()> fill = [&]()
        {
            lw->clear();
            curL->setText(hr::l10n::s("当前：", "Current: ") + cur);
            QListWidgetItem *up = new QListWidgetItem(
                hr::l10n::s("..（上一级）", "..(up)"), lw);
            up->setData(Qt::UserRole, QString(".."));
            QString err;
            if (protos[bi].key == "webdav")
            {
                Db::RemoteSrc t;
                t.protocol = QString::fromLatin1("webdav");
                t.host = host[bi]->text().trimmed();
                t.port = port[bi]->text().toInt();
                t.path = path[bi]->text().trimmed();
                t.user = user[bi]->text();
                t.password = pass[bi]->text();
                hr::WebDavClient dav(remoteRootOf(t), t.user, t.password);
                QVector<hr::DavEntry> es;
                if (dav.list(cur, &es, &err))
                    for (const hr::DavEntry &e : es)
                        if (e.isDir && e.name != QString("."))
                        {
                            QListWidgetItem *it =
                                new QListWidgetItem(e.name + "/", lw);
                            it->setData(Qt::UserRole, e.name);
                        }
            }
            else if (protos[bi].key == "sftp")
            {
                hr::SshClient ssh(host[bi]->text().trimmed(),
                                  port[bi]->text().toInt() > 0
                                      ? port[bi]->text().toInt()
                                      : 22,
                                  user[bi]->text(), pass[bi]->text());
                if (!ssh.ok())
                    err = ssh.error();
                else
                    for (const hr::SshEntry &e : ssh.list(cur, &err))
                        if (e.isDir)
                        {
                            QListWidgetItem *it =
                                new QListWidgetItem(e.name + "/", lw);
                            it->setData(Qt::UserRole, e.name);
                        }
            }
            else
                err = hr::l10n::s("SMB 请手填 共享名/子目录。",
                                  "SMB: type share/subdir manually.");
            if (!err.isEmpty())
                lw->addItem(hr::l10n::s("（列表失败：", "(list failed: ") +
                            err.left(80) + "）");
        };
        fill();
        connect(lw, &QListWidget::itemDoubleClicked, &pd, [&]()
        {
            QListWidgetItem *it = lw->currentItem();
            if (!it) return;
            const QString nm = it->data(Qt::UserRole).toString();
            if (nm == QLatin1String(".."))
            {
                QString t2 = cur;
                while (t2.endsWith('/')) t2.chop(1);
                const int sp = t2.lastIndexOf('/');
                cur = sp <= 0 ? QString("/") : t2.left(sp + 1);
            }
            else if (!nm.isEmpty())
            {
                if (!cur.endsWith('/')) cur += '/';
                cur += nm;
            }
            fill();
        });
        connect(pb, &QDialogButtonBox::accepted, &pd, [&]()
        {
            path[bi]->setText(cur);
            pd.accept();
        });
        connect(pb, &QDialogButtonBox::rejected, &pd, &QDialog::reject);
        pd.exec();
    });

    if (dlg.exec() != QDialog::Accepted) return false;
    const int i = tabs->currentIndex();
    io->id = io->id; /* 新增时 0；编辑时调用方保留 */
    io->protocol = protos[i].key;
    io->name = name[i]->text().trimmed();
    io->host = host[i]->text().trimmed();
    io->port = port[i]->text().toInt();
    io->path = path[i]->text().trimmed();
    io->user = user[i]->text();
    io->password = pass[i]->text();
    if (io->name.isEmpty()) io->name = io->host;
    return true;
}

void LibraryWindow::refreshRemoteServers()
{
    if (!m_srvList) return;
    /* v0.96.0 W17#5：同款 double-free 隐患，clear() 自动回收行控件即可 */
    m_srvList->clear();
    const QList<Db::RemoteSrc> srcs = Db::listRemoteSrc();
    for (const Db::RemoteSrc &s : srcs)
    {
        QListWidgetItem *it = new QListWidgetItem(m_srvList);
        it->setData(Qt::UserRole, s.id);
        const QString label =
            QString("%1  ·  %2 %3:%4 %5")
                .arg(s.name, s.protocol, s.host)
                .arg(s.port)
                .arg(s.path);
        it->setText(label);
        m_srvList->addItem(it);
        QWidget *row = new QWidget;
        QHBoxLayout *h = new QHBoxLayout(row);
        h->setContentsMargins(4, 0, 4, 0);
        h->addWidget(new QLabel(label), 1);
        auto act = [this, s](int kind)
        {
            if (kind == 0) { remoteBrowse(s, QString()); showTab(4); }
            else if (kind == 1)
            {
                Db::RemoteSrc e = s;
                if (remoteServerForm(&e))
                {
                    Db::updateRemoteSrc(e);
                    evt("remote_srv_edit", QString("id=%1").arg(s.id));
                    refreshRemoteServers();
                    refreshLocalHome();
                }
            }
            else if (kind == 2)
            {
                if (QMessageBox::question(this, hr::l10n::s("删除服务器", "Delete server"),
                                          hr::l10n::s("删除“%1”？", "Delete \"%1\"?").arg(s.name)) ==
                    QMessageBox::Yes)
                {
                    Db::deleteRemoteSrc(s.id);
                    evt("remote_srv_del", QString("id=%1").arg(s.id));
                    refreshRemoteServers();
                    refreshLocalHome();
                }
            }
        };
        QPushButton *b0 = new QPushButton(hr::l10n::s("扫描", "Scan"));
        QPushButton *b1 = new QPushButton(hr::l10n::s("编辑", "Edit"));
        QPushButton *b2 = new QPushButton(hr::l10n::s("删", "Del"));
        connect(b0, &QPushButton::clicked, this, [act] { act(0); });
        connect(b1, &QPushButton::clicked, this, [act] { act(1); });
        connect(b2, &QPushButton::clicked, this, [act] { act(2); });
        h->addWidget(b0);
        h->addWidget(b1);
        h->addWidget(b2);
        m_srvList->setItemWidget(it, row);
    }
}

void LibraryWindow::buildRemotePage()
{
    m_remotePage = new QWidget;
    QVBoxLayout *rl = new QVBoxLayout(m_remotePage);
    rl->setContentsMargins(0, 0, 0, 0);
    m_remoteSw = new QStackedWidget;
    rl->addWidget(m_remoteSw);

    /* 视图 0：服务器列表 */
    QWidget *home = new QWidget;
    QVBoxLayout *hl = new QVBoxLayout(home);
    hl->setContentsMargins(12, 8, 12, 12);
    QLabel *hd = new QLabel(hr::l10n::s("网上书库", "Online library"));
    hd->setStyleSheet(remoteGroupStyle());
    hl->addWidget(hd);
    QPushButton *add = new QPushButton(hr::l10n::s("添加服务器…", "Add server…"));
    connect(add, &QPushButton::clicked, this, [this]
    {
        Db::RemoteSrc r;
        if (remoteServerForm(&r))
        {
            Db::addRemoteSrc(r);
            evt("remote_srv_add", QString("proto=%1 host=%2").arg(r.protocol, r.host.left(60)));
            refreshRemoteServers();
            refreshLocalHome();
        }
    });
    hl->addWidget(add, 0, Qt::AlignLeft);
    m_srvList = new QListWidget;
    m_srvList->setSpacing(2);
    hl->addWidget(m_srvList, 1);
    hl->addWidget(new QLabel(hr::l10n::s(
        "扫描进入浏览；双击书=在线流式打开（块缓存+断网熔断）；右键书=下载并打开。",
        "Scan to browse; double-click streams (block-cached); right-click to download.")));
    m_remoteSw->addWidget(home);

    /* 视图 1：浏览 */
    QWidget *br = new QWidget;
    QVBoxLayout *bl = new QVBoxLayout(br);
    bl->setContentsMargins(8, 6, 8, 8);
    {
        QWidget *row = new QWidget;
        QHBoxLayout *h = new QHBoxLayout(row);
        h->setContentsMargins(0, 0, 0, 0);
        QPushButton *back = new QPushButton(hr::l10n::s("‹ 服务器列表", "‹ Servers"));
        connect(back, &QPushButton::clicked, this, [this]
        {
            m_remoteSw->setCurrentIndex(0);
            refreshRemoteServers();
        });
        m_crumbLb = new QLabel;
        h->addWidget(back);
        h->addWidget(m_crumbLb, 1);
        bl->addWidget(row);
    }
    {
        m_opdsRow = new QWidget;
        QHBoxLayout *h = new QHBoxLayout(m_opdsRow);
        h->setContentsMargins(0, 0, 0, 0);
        m_opdsQ = new QLineEdit;
        m_opdsQ->setPlaceholderText(hr::l10n::s("OPDS 目录内搜索（回车）", "OPDS search (Enter)"));
        QPushButton *go = new QPushButton(hr::l10n::s("搜索", "Search"));
        h->addWidget(m_opdsQ, 1);
        h->addWidget(go);
        bl->addWidget(m_opdsRow);
        connect(go, &QPushButton::clicked, this, [this]
        {
            const QString q = m_opdsQ->text().trimmed();
            if (q.isEmpty()) return;
            m_fileList->clear();
            hr::OpdsClient op(remoteRootOf(m_curSrc), m_curSrc.user, m_curSrc.password);
            QString err;
            const QList<hr::OpdsEntry> es = op.search(q, &err);
            for (const hr::OpdsEntry &e : es)
            {
                QListWidgetItem *it = new QListWidgetItem(
                    e.isDir ? e.name + "  ›" : e.name, m_fileList);
                it->setData(Qt::UserRole, "opds");
                it->setData(Qt::UserRole + 1, e.name);
                it->setData(Qt::UserRole + 2, (qlonglong)e.size);
                it->setData(Qt::UserRole + 3, e.isDir);
                it->setData(Qt::UserRole + 4, e.url);
            }
            m_remoteLb->setText(es.isEmpty()
                                    ? hr::l10n::s("无结果 %1", "No results %1").arg(err.left(40))
                                    : QString(hr::l10n::s("%1 条结果", "%1 results")).arg(es.size()));
            evt("remote_search", QString("q=%1 n=%2").arg(q.left(40)).arg(es.size()));
        });
        connect(m_opdsQ, &QLineEdit::returnPressed, go, &QPushButton::click);
    }
    m_fileList = new QListWidget;
    bl->addWidget(m_fileList, 1);
    m_remoteLb = new QLabel;
    bl->addWidget(m_remoteLb);
    m_remoteSw->addWidget(br);

    connect(m_fileList, &QListWidget::itemDoubleClicked, this, [this](QListWidgetItem *it)
    {
        if (it->data(Qt::UserRole + 3).toBool())
            remoteBrowse(m_curSrc, it->data(Qt::UserRole + 4).toString());
        else
            remoteOpenStream(m_curSrc, it);
    });
    connect(m_fileList, &QListWidget::customContextMenuRequested, this,
            [this](const QPoint &pos)
    {
        QListWidgetItem *it = m_fileList->itemAt(pos);
        if (!it || it->data(Qt::UserRole + 3).toBool()) return;
        QMenu menu(this);
        QAction *o = menu.addAction(hr::l10n::s("打开（流式）", "Open (stream)"));
        QAction *d = menu.addAction(hr::l10n::s("下载并打开", "Download && open"));
        menu.addSeparator();
        menu.addAction(hr::l10n::s("取消", "Cancel"));
        QAction *a = menu.exec(m_fileList->viewport()->mapToGlobal(pos));
        if (a == o) remoteOpenStream(m_curSrc, it);
        else if (a == d) remoteDownloadOpen(m_curSrc, it);
    });
    m_fileList->setContextMenuPolicy(Qt::CustomContextMenu);

    refreshRemoteServers();
}


/* SMB：s.path = "<share>[/<共享内基准目录>]"；拆出 share 与共享内路径 */
static void smbShareSplit(const QString &path, QString *share, QString *base)
{
    const int slash = path.indexOf(QLatin1Char('/'));
    if (slash < 0)
    {
        *share = path;
        *base = QString();
    }
    else
    {
        *share = path.left(slash);
        *base = path.mid(slash + 1);
    }
    while (base->startsWith(QLatin1Char('/'))) base->remove(0, 1);
    while (share->endsWith(QLatin1Char('/'))) share->chop(1);
}

void LibraryWindow::remoteBrowse(const Db::RemoteSrc &s, const QString &sub)
{
    m_curSrc = s;
    m_remoteSw->setCurrentIndex(1);
    m_fileList->clear();
    m_opdsRow->setVisible(s.protocol == "opds");
    m_crumbLb->setText(QString("%1 · %2").arg(s.name, sub.isEmpty() ? QString("/") : sub));
    const QString proto = s.protocol;

    if (proto == "webdav")
    {
        hr::WebDavClient dav(remoteRootOf(s), s.user, s.password);
        QString full = remoteRootOf(s);
        QString rel = s.path;
        rel.remove(QRegularExpression("^/+|/+$"));
        if (!rel.isEmpty()) full += "/" + rel;
        if (!sub.isEmpty()) full += "/" + sub;
        QVector<hr::DavEntry> es;
        QString err;
        if (!dav.list(full, &es, &err))
        {
            m_remoteLb->setText(hr::l10n::s("列出失败：", "List failed: ") + err);
            return;
        }
        int n = 0;
        if (!sub.isEmpty())
        {
            QListWidgetItem *up = new QListWidgetItem(hr::l10n::s("..（上一级）", ".. (up)"),
                                                      m_fileList);
            up->setData(Qt::UserRole, "webdav");
            up->setData(Qt::UserRole + 3, true);
            up->setData(Qt::UserRole + 4, parentSub(sub));
        }
        for (const hr::DavEntry &e : es)
        {
            QListWidgetItem *it = new QListWidgetItem(
                e.isDir ? e.name + "/" :
                          QString(hr::l10n::s("%1   (%2 KB)", "%1   (%2 KB)"))
                              .arg(e.name)
                              .arg(e.size / 1024),
                m_fileList);
            it->setData(Qt::UserRole, "webdav");
            it->setData(Qt::UserRole + 1, e.name);
            it->setData(Qt::UserRole + 2, (qlonglong)e.size);
            it->setData(Qt::UserRole + 3, e.isDir);
            it->setData(Qt::UserRole + 4, e.isDir ? joinSub(sub, e.name) : sub);
            if (!e.isDir) n++;
        }
        m_remoteLb->setText(QString(hr::l10n::s("%1 本可开", "%1 openable")).arg(n));
        evt("remote_list", QString("proto=webdav n=%1 sub=%2").arg(n).arg(sub.left(60)));
        return;
    }

    if (proto == "sftp")
    {
        hr::SshClient ssh(s.host, s.port > 0 ? s.port : 22, s.user, s.password);
        if (!ssh.ok())
        {
            m_remoteLb->setText(hr::l10n::s("SFTP 连接失败：", "SFTP connect failed: ") +
                                ssh.error());
            return;
        }
        const QString dir = joinSub(s.path, sub);
        QString err;
        const QList<hr::SshEntry> es = ssh.list(dir, &err);
        if (es.isEmpty() && !err.isEmpty())
        {
            m_remoteLb->setText(hr::l10n::s("列出失败：", "List failed: ") + err);
            return;
        }
        int n = 0;
        if (!dir.isEmpty())
        {
            QListWidgetItem *up = new QListWidgetItem(hr::l10n::s("..（上一级）", ".. (up)"),
                                                      m_fileList);
            up->setData(Qt::UserRole, "sftp");
            up->setData(Qt::UserRole + 3, true);
            up->setData(Qt::UserRole + 4, parentSub(dir));
        }
        for (const hr::SshEntry &e : es)
        {
            QListWidgetItem *it = new QListWidgetItem(
                e.isDir ? e.name + "/"
                        : e.name + QString("   (%1 KB)").arg(e.size / 1024),
                m_fileList);
            it->setData(Qt::UserRole, "sftp");
            it->setData(Qt::UserRole + 1, e.name);
            it->setData(Qt::UserRole + 2, (qlonglong)e.size);
            it->setData(Qt::UserRole + 3, e.isDir);
            it->setData(Qt::UserRole + 4, e.relPath);
            if (!e.isDir) n++;
        }
        m_remoteLb->setText(QString(hr::l10n::s("%1 本可开", "%1 openable")).arg(n));
        evt("remote_list", QString("proto=sftp n=%1 dir=%2").arg(n).arg(dir.left(60)));
        return;
    }

    if (proto == "smb")
    {
        /* U-SMB：target/目录均为共享内路径（不含 share）；share 从 s.path 首段拆出 */
        QString share, base;
        smbShareSplit(s.path, &share, &base);
        hr::SmbClient smb(s.host + ":" + QString::number(s.port > 0 ? s.port : 445),
                          share, s.user, s.password);
        if (!smb.ok())
        {
            m_remoteLb->setText(hr::l10n::s("SMB 连接失败：", "SMB connect failed: ") +
                                smb.error());
            return;
        }
        const QString dir = joinSub(base, sub);
        QString err;
        const QList<hr::SmbEntry> es = smb.list(dir, &err);
        if (es.isEmpty() && !err.isEmpty())
        {
            m_remoteLb->setText(hr::l10n::s("列出失败：", "List failed: ") + err);
            return;
        }
        int n = 0;
        if (!dir.isEmpty())
        {
            QListWidgetItem *up = new QListWidgetItem(hr::l10n::s("..（上一级）", ".. (up)"),
                                                      m_fileList);
            up->setData(Qt::UserRole, "smb");
            up->setData(Qt::UserRole + 3, true);
            up->setData(Qt::UserRole + 4, parentSub(dir));
        }
        for (const hr::SmbEntry &e : es)
        {
            QListWidgetItem *it = new QListWidgetItem(
                e.isDir ? e.name + "/"
                        : QString(hr::l10n::s("%1   (%2 KB)", "%1   (%2 KB)"))
                              .arg(e.name).arg(e.size / 1024),
                m_fileList);
            it->setData(Qt::UserRole, "smb");
            it->setData(Qt::UserRole + 1, e.name);
            it->setData(Qt::UserRole + 2, (qlonglong)e.size);
            it->setData(Qt::UserRole + 3, e.isDir);
            it->setData(Qt::UserRole + 4, e.relPath);
            if (!e.isDir) n++;
        }
        m_remoteLb->setText(QString(hr::l10n::s("%1 本可开", "%1 openable")).arg(n));
        evt("remote_list", QString("proto=smb n=%1 dir=%2").arg(n).arg(dir.left(60)));
        return;
    }

    if (proto == "opds")
    {
        hr::OpdsClient op(remoteRootOf(s), s.user, s.password);
        m_remoteLb->setText(hr::l10n::s("OPDS 读取中…", "Reading feed…"));
        QString err;
        const QList<hr::OpdsEntry> es = op.list(sub, &err);
        if (es.isEmpty())
        {
            m_remoteLb->setText(hr::l10n::s("列出失败：", "List failed: ") + err);
            return;
        }
        if (!sub.isEmpty())
        {
            QListWidgetItem *up = new QListWidgetItem(hr::l10n::s("..（返回根）", ".. (root)"),
                                                      m_fileList);
            up->setData(Qt::UserRole, "opds");
            up->setData(Qt::UserRole + 3, true);
            up->setData(Qt::UserRole + 4, QString());
        }
        for (const hr::OpdsEntry &e : es)
        {
            QString label = e.isDir ? e.name + "  ›" : e.name;
            if (!e.isDir && !e.summary.isEmpty())
                label += "   — " + e.summary.left(60);
            QListWidgetItem *it = new QListWidgetItem(label, m_fileList);
            it->setToolTip(e.summary);
            it->setData(Qt::UserRole, "opds");
            it->setData(Qt::UserRole + 1, e.name);
            it->setData(Qt::UserRole + 2, (qlonglong)e.size);
            it->setData(Qt::UserRole + 3, e.isDir);
            it->setData(Qt::UserRole + 4, e.url);
        }
        m_remoteLb->setText(QString(hr::l10n::s("%1 项", "%1 items")).arg(es.size()));
        evt("remote_list", QString("proto=opds n=%1").arg(es.size()));
    }
}

void LibraryWindow::remoteOpenStream(const Db::RemoteSrc &s, QListWidgetItem *it)
{
    openRemoteItem(s, it->data(Qt::UserRole + 1).toString(),
                   it->data(Qt::UserRole + 2).toLongLong(),
                   it->data(Qt::UserRole + 4).toString());
}

/* v0.110.3：后台引擎探测——逐本打开数页数/取真标题并回写（书库"页数 0"补齐）。
 * 云盘文件一开就下载、大 mobi 触发转换，故逐本进行+每本间让路 250ms；
 * 每探 5 本刷新一次书库显示。与扫描解耦：扫描秒回，页数慢慢变准。 */
void LibraryWindow::startScanProbe(const QStringList &paths)
{
    if (paths.isEmpty()) return;
    {
        QMutexLocker lk(&g_probeMu);
        if (m_probeBusy)
        {
            m_probeQueue.append(paths);
            return;
        }
        m_probeBusy = true;
        m_probeQueue = paths;
    }
    QThreadPool::globalInstance()->start(QRunnable::create([this]
    {
        int done = 0;
        for (;;)
        {
            QString p;
            {
                QMutexLocker lk(&g_probeMu);
                if (m_probeQueue.isEmpty()) break;
                p = m_probeQueue.takeFirst();
            }
            void *doc = hr_qt_open(hr::openablePath(p).toUtf8().constData());
            if (doc)
            {
                const int pages = hr_qt_count(doc);
                QString title = QString::fromUtf8(hr_qt_meta(doc, 0));
                const QString author = QString::fromUtf8(hr_qt_meta(doc, 1));
                hr_qt_close(doc);
                if (QSettings(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat)
                        .value("lib/useCalibreOpf", true).toBool())
                {
                    const QString ot = calibreOpfTitle(p);
                    if (!ot.isEmpty()) title = ot;
                }
                if (pages > 0)
                {
                    QFileInfo fi(p);
                    Db::upsertFileMeta(p,
                                       title.isEmpty() ? QFileInfo(p).fileName() : title,
                                       pages, fi.size(), fi.lastModified().toMSecsSinceEpoch(),
                                       author);
                }
            }
            done++;
            evt("scan_probe", QString("done=%1").arg(done));
            if (done % 5 == 0)
                QMetaObject::invokeMethod(this, [this] { loadTable(); },
                                          Qt::QueuedConnection);
            QThread::msleep(250); /* 引擎让路（阅读渲染/后台补页优先） */
        }
        QMetaObject::invokeMethod(this, [this]
        {
            loadTable();
            {
                QMutexLocker lk(&g_probeMu);
                m_probeBusy = false;
            }
            evt("scan_probe_done", "");
        }, Qt::QueuedConnection);
    }));
}

/* v0.110.2：书库来源治理——未勾选目录/服务器的书即刻移出书库（只删登记与
 * 级联进度书签，源文件不动）；UI 线程同步调用（本地 DB，秒级） */
void LibraryWindow::governSources()
{
    QStringList keep;
    for (const Db::ScanDirRow &dr : Db::scanDirRows())
        if (dr.shelf) keep << dr.path;
    QString pd;
    Db::pruneOutsideDirs(keep, &pd);
    const QSet<int> on = remoteScanIds();
    for (const Db::RemoteSrc &s : Db::listRemoteSrc())
        if (!on.contains(s.id))
            Db::pruneRemoteSource(s.id, QSet<QString>());
    if (!pd.isEmpty())
        evt("lib_src_govern", pd);
}

/* v0.110.0：网上书库扫描勾选集（qt-prefs.ini [scan] remoteOn=id,id…） */
static QSet<int> remoteScanIds()
{
    QSet<int> out;
    const QString csv = QSettings(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat)
                            .value("scan/remoteOn")
                            .toString();
    const QStringList ps = csv.split(QLatin1Char(','), Qt::SkipEmptyParts);
    for (const QString &p : ps)
        out.insert(p.trimmed().toInt());
    return out;
}
static void setRemoteScanId(int id, bool on)
{
    QSet<int> cur = remoteScanIds();
    if (on) cur.insert(id);
    else cur.remove(id);
    QStringList cs;
    for (int v : cur) cs << QString::number(v);
    QSettings st(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
    st.setValue("scan/remoteOn", cs.join(QLatin1Char(',')));
}

/* v0.110.0：勾选的网上书库递归扫描入库——只登记 remote://<id>/<target> 元数据
 * （不下载文件，打开时仍走流式）；键与 openRemoteItem 登记完全一致，进度/封面同键。
 * webdav=子路径拼名，sftp/smb=条目 relPath（与浏览页 target 语义相同）；OPDS 不参与。
 * 限制：每源深度 4、文件 800，防误勾全盘。 */
void LibraryWindow::scanRemoteSources()
{
    if (m_remoteScanBusy) return;
    const QVector<int> ids = [&]
    {
        QVector<int> out;
        for (int v : remoteScanIds()) out.append(v);
        return out;
    }();
    if (ids.isEmpty()) return;
    m_remoteScanBusy = true;
    const QStringList exts = m_exts;
    QThreadPool::globalInstance()->start(QRunnable::create([this, ids, exts]
    {
        const qint64 t0 = QDateTime::currentMSecsSinceEpoch();
        int added = 0, srcs = 0;
        QSet<QString> known;
        for (const Db::MetaRow &r : Db::listLibrary())
            known.insert(r.path);
        for (int id : ids)
        {
            const Db::RemoteSrc s = [&]
            {
                for (const Db::RemoteSrc &x : Db::listRemoteSrc())
                    if (x.id == id) return x;
                return Db::RemoteSrc();
            }();
            if (s.id == 0) continue;
            const QString proto = s.protocol.toLower();
            srcs++;
            if (proto == QString::fromLatin1("webdav"))
            {
                hr::WebDavClient dav(remoteRootOf(s), s.user, s.password);
                QString base = s.path;
                while (base.startsWith(QLatin1Char('/'))) base.remove(0, 1);
                while (base.endsWith(QLatin1Char('/'))) base.chop(1);
                QVector<QPair<QString, int>> queue;
                queue.append(qMakePair(QString(), 0));
                int files = 0;
                while (!queue.isEmpty() && files < 800)
                {
                    const QPair<QString, int> cur = queue.takeFirst();
                    QString full = remoteRootOf(s);
                    if (!base.isEmpty()) full += "/" + base;
                    if (!cur.first.isEmpty()) full += "/" + cur.first;
                    QVector<hr::DavEntry> es;
                    QString err;
                    if (!dav.list(full, &es, &err)) continue;
                    for (const hr::DavEntry &e : es)
                    {
                        if (e.name.startsWith(QLatin1Char('.'))) continue;
                        if (e.isDir)
                        {
                            if (cur.second < 4)
                                queue.append(qMakePair(
                                    cur.first.isEmpty() ? e.name : cur.first + "/" + e.name,
                                    cur.second + 1));
                            continue;
                        }
                        if (!exts.contains(QFileInfo(e.name).suffix().toLower())) continue;
                        const QString rel =
                            cur.first.isEmpty() ? e.name : cur.first + "/" + e.name;
                        const QString key = QString::fromLatin1("remote://%1/%2")
                                                .arg(s.id)
                                                .arg(rel);
                        if (known.contains(key)) continue;
                        known.insert(key);
                        Db::upsertFileMeta(key,
                                           QFileInfo(e.name).completeBaseName(),
                                           0, e.size, e.mtime);
                        added++;
                        files++;
                    }
                }
            }
            else if (proto == QString::fromLatin1("sftp"))
            {
                hr::SshClient ssh(s.host, s.port > 0 ? s.port : 22, s.user, s.password);
                if (!ssh.ok()) continue;
                QVector<QPair<QString, int>> queue;
                queue.append(qMakePair(QString(), 0));
                int files = 0;
                while (!queue.isEmpty() && files < 800)
                {
                    const QPair<QString, int> cur = queue.takeFirst();
                    const QString dir = joinSub(s.path, cur.first);
                    const QList<hr::SshEntry> es = ssh.list(dir, nullptr);
                    for (const hr::SshEntry &e : es)
                    {
                        if (e.name.startsWith(QLatin1Char('.'))) continue;
                        if (e.isDir)
                        {
                            if (cur.second < 4)
                                queue.append(qMakePair(
                                    cur.first.isEmpty() ? e.name : cur.first + "/" + e.name,
                                    cur.second + 1));
                            continue;
                        }
                        if (!exts.contains(QFileInfo(e.name).suffix().toLower())) continue;
                        const QString key = QString::fromLatin1("remote://%1/%2")
                                                .arg(s.id)
                                                .arg(e.relPath);
                        if (known.contains(key)) continue;
                        known.insert(key);
                        Db::upsertFileMeta(key,
                                           QFileInfo(e.name).completeBaseName(),
                                           0, e.size, 0);
                        added++;
                        files++;
                    }
                }
            }
            else if (proto == QString::fromLatin1("smb"))
            {
                QString share, base;
                smbShareSplit(s.path, &share, &base);
                hr::SmbClient smb(s.host + ":" + QString::number(s.port > 0 ? s.port : 445),
                                  share, s.user, s.password);
                if (!smb.ok()) continue;
                QVector<QPair<QString, int>> queue;
                queue.append(qMakePair(QString(), 0));
                int files = 0;
                while (!queue.isEmpty() && files < 800)
                {
                    const QPair<QString, int> cur = queue.takeFirst();
                    const QList<hr::SmbEntry> es = smb.list(joinSub(base, cur.first), nullptr);
                    for (const hr::SmbEntry &e : es)
                    {
                        if (e.name.startsWith(QLatin1Char('.'))) continue;
                        if (e.isDir)
                        {
                            if (cur.second < 4)
                                queue.append(qMakePair(
                                    cur.first.isEmpty() ? e.name : cur.first + "/" + e.name,
                                    cur.second + 1));
                            continue;
                        }
                        if (!exts.contains(QFileInfo(e.name).suffix().toLower())) continue;
                        const QString key = QString::fromLatin1("remote://%1/%2")
                                                .arg(s.id)
                                                .arg(e.relPath);
                        if (known.contains(key)) continue;
                        known.insert(key);
                        Db::upsertFileMeta(key,
                                           QFileInfo(e.name).completeBaseName(),
                                           0, e.size, 0);
                        added++;
                        files++;
                    }
                }
            }
            /* opds：目录型 feed，不参与批量扫描（保持打开时登记） */
        }
        QMetaObject::invokeMethod(
            this,
            [this, added, srcs, t0]
            {
                loadTable();
                m_remoteScanBusy = false;
                evt("remote_scan",
                    QString("srcs=%1 added=%2 ms=%3")
                        .arg(srcs)
                        .arg(added)
                        .arg(QDateTime::currentMSecsSinceEpoch() - t0));
            },
            Qt::QueuedConnection);
    }));
}

/* 缺陷#4：流式打开核心；成功即登记 file_meta（remote://<id>/<target>），
 * 封面由 ReaderWindow 首渲落盘（coverPathFor remote 分支同键） */
void LibraryWindow::openRemoteItem(const Db::RemoteSrc &s, const QString &name, long long size,
                                   const QString &target)
{
    const QString proto = s.protocol;
    const QString ext = QFileInfo(name).suffix().toLower();
    /* P0 适配（缺陷#14）：WebDAV 的 remote:// 键补文件名——原键=父目录，
     * 同目录多本书共享进度/书签/封面/角标键；sftp/smb 的 target 本就含文件名 */
    QString fullRel = target;
    if (proto == QString::fromLatin1("webdav") &&
        !(target == name || target.endsWith("/" + name)))
        fullRel = target.isEmpty() ? name : target + "/" + name;

    /* v0.106.0（P0-1）：net/* 死偏好接线——在线优先（有整本缓存也在线直读）、
     * 整本下载分级确认（重格式超阈值先问；仅 Wi-Fi 键随本版移除） */
    QSettings stNet(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
    const bool onlineFirst = stNet.value(QString::fromLatin1("net/onlineFirst"), false).toBool();

    /* P0 适配（E1）：整本晋升缓存命中 → 直接本地打开（进度仍记在 remote:// 键） */
    {
        const QString local = hr::dataDir() + QString::fromLatin1("/cache/remote/") +
                              QString::number(s.id) + "/" + name;
        if (!onlineFirst && size > 0 && QFileInfo(local).size() == size)
        {
            /* v0.107.0（P2-9）：HTTP 系协议轻量 HEAD 校验远端尺寸（versionTag 等价）——
             * 失配丢缓存改在线（对齐安卓语义）；探测失败（离线）不阻塞就近直开；
             * sftp/smb 握手开销大，维持纯尺寸匹配。 */
            bool stale = false;
            if (proto == QString::fromLatin1("webdav") ||
                proto == QString::fromLatin1("opds"))
            {
                QString u1 = target;
                if (proto == QString::fromLatin1("webdav"))
                {
                    u1 = remoteRootOf(s);
                    if (!s.path.isEmpty()) u1 += "/" + s.path;
                    if (!target.isEmpty()) u1 += "/" + target;
                    u1 += "/" + QString::fromLatin1(QUrl::toPercentEncoding(name));
                }
                hr::WebDavClient dav(remoteRootOf(s), s.user, s.password);
                const long long rlen = dav.contentLength(u1);
                if (rlen >= 0 && rlen != size)
                {
                    stale = true;
                    QFile::remove(local);
                }
                evt(QString::fromLatin1("remote_cache_check"),
                    QString::fromLatin1("proto=%1 local=%2 remote=%3")
                        .arg(proto)
                        .arg(size)
                        .arg(rlen));
            }
            if (!stale)
            {
                void *ld = hr_qt_open(hr::openablePath(local).toUtf8().constData());
                if (ld)
                {
                    const QString rk =
                        QString::fromLatin1("remote://%1/%2").arg(s.id).arg(fullRel);
                    Db::upsertFileMeta(rk, name, hr_qt_count(ld), size,
                                       QDateTime::currentMSecsSinceEpoch());
                    emit openRemoteRequested(ld, name, nullptr, rk);
                    evt("remote_cache_hit",
                        QString("proto=%1 local=%2").arg(proto, local.left(90)));
                    return;
                }
            }
        }
    }

    /* P0 适配（D4）：net/* 假接线——重试参数真读偏好（原恒默认 3/100） */
    const long long memLimit = 32LL * 1024 * 1024;
    /* v0.111.0：后台补齐线程数（webdav/opds 每线程独立连接真并发；sftp/smb 客户端
     * 内部单锁串行——多线程退化为不重复排队，无收益也无害）；偏好 net/fillThreads */
    const int fillThreads =
        qBound(1, stNet.value(QString::fromLatin1("net/fillThreads"), 4).toInt(), 8);
    /* v0.106.0（P0-1）：整本下载分级确认（对齐安卓 confirmUnsupportedFetch）——
     * 重格式（mobi 族/djvu/doc/cbr）且超过阈值（net/thresholdMB，0=不确认）先问 */
    const bool heavyFmt =
        (ext == QString::fromLatin1("mobi") || ext == QString::fromLatin1("azw") ||
         ext == QString::fromLatin1("azw3") || ext == QString::fromLatin1("prc") ||
         ext == QString::fromLatin1("djvu") || ext == QString::fromLatin1("doc") ||
         ext == QString::fromLatin1("cbr"));
    const long long thMB =
        stNet.value(QString::fromLatin1("net/thresholdMB"), 20).toLongLong();
    if (heavyFmt && thMB > 0 && size > thMB * 1024 * 1024)
    {
        const QMessageBox::StandardButton r = QMessageBox::question(
            this, hr::l10n::s("在线打开", "Open online"),
            hr::l10n::s("《%1》约 %2 MB，整本下载后阅读更流畅。\n整本下载，还是直接在线阅读？",
                        "\"%1\" is about %2 MB. Downloading the whole book reads smoother.\n"
                        "Download it, or read online directly?")
                .arg(name.left(40))
                .arg((size + 1024 * 1024 - 1) / (1024 * 1024)),
            QMessageBox::Yes | QMessageBox::No, QMessageBox::Yes);
        evt(QString::fromLatin1("remote_confirm_full"),
            QString::fromLatin1("ext=%1 size=%2 pick=%3")
                .arg(ext)
                .arg(size)
                .arg(r == QMessageBox::Yes ? QString::fromLatin1("download")
                                           : QString::fromLatin1("stream")));
        if (r == QMessageBox::Yes)
        {
            remoteDownloadOpen(s, name, target);
            return;
        }
    }
    BcsHolder *holder = new BcsHolder();
    holder->fp = std::make_shared<hr::FetchPolicy>(
        qBound(1, stNet.value("net/retryN", 3).toInt(), 10),
        qBound(50, stNet.value("net/retryMs", 100).toInt(), 5000));
    const int blockSize =
        (ext == "pdf" || ext == "cbz" || ext == "xps" || ext == "djvu") ? 64 * 1024
                                                                       : 1024 * 1024;
    const QString key = "qt-" +
        QString::fromLatin1(QCryptographicHash::hash((proto + "|" + fullRel).toUtf8(),
                                                     QCryptographicHash::Md5)
                                .toHex()) +
        "-" + QString::number(size);

    if (proto == "sftp")
    {
        holder->ssh = std::make_shared<hr::SshClient>(s.host, s.port > 0 ? s.port : 22,
                                                      s.user, s.password);
        if (!holder->ssh->ok())
        {
            m_remoteLb->setText(hr::l10n::s("SFTP 连接失败：", "SFTP connect failed: ") +
                                holder->ssh->error());
            delete holder;
            return;
        }
        const QString rel = target;
        auto fetch = [holder, rel](long long pos, int len) -> QByteArray
        {
            return holder->fp->fetch(
                [holder, rel](long long p, int l)
                { return holder->ssh->readRange(rel, p, l); },
                pos, len, "sftp");
        };
        holder->s = new hr::BlockCacheStore(key, size, blockSize, fetch, memLimit, fillThreads);
    }

    else if (proto == "smb")
    {
        QString share, base;
        smbShareSplit(s.path, &share, &base);
        holder->smb = std::make_shared<hr::SmbClient>(
            s.host + ":" + QString::number(s.port > 0 ? s.port : 445), share,
            s.user, s.password);
        if (!holder->smb->ok())
        {
            m_remoteLb->setText(hr::l10n::s("SMB 连接失败：", "SMB connect failed: ") +
                                holder->smb->error());
            delete holder;
            return;
        }
        const QString rel = target; /* 共享内路径 */
        auto fetch = [holder, rel](long long pos, int len) -> QByteArray
        {
            return holder->fp->fetch(
                [holder, rel](long long p, int l)
                { return holder->smb->readRange(rel, p, l); },
                pos, len, "smb");
        };
        holder->s = new hr::BlockCacheStore(key, size, blockSize, fetch, memLimit, fillThreads);
    }
    else /* webdav / opds：HTTP Range 取块 */
    {
        QString url1 = target; /* opds：条目自带绝对 URL */
        if (proto == "webdav")
        {
            /* 完整 URL = root + /path + /sub（目录段不编码，U0 口径） + /文件名(percent) */
            QString u1 = remoteRootOf(s);
            if (!s.path.isEmpty()) u1 += "/" + s.path;
            if (!target.isEmpty()) u1 += "/" + target;
            u1 += "/" + QString::fromLatin1(QUrl::toPercentEncoding(name));
            url1 = u1;
        }
        holder->dav = std::make_shared<hr::WebDavClient>(remoteRootOf(s), s.user, s.password);
        const QString u = url1;
        auto fetch = [holder, u](long long pos, int len) -> QByteArray
        {
            return holder->fp->fetch(
                [holder, u](long long p, int l) { return holder->dav->getRange(u, p, l); },
                pos, len, "webdav");
        };
        holder->s = new hr::BlockCacheStore(key, size, blockSize, fetch, memLimit, fillThreads);
    }
    /* P0 适配（D4）：前后窗口深度按偏好 %（窗口字节/块长）；P0 适配（D5）：上限接线（在读书保护） */
    holder->s->setDepths(
        (int)(memLimit * qBound(0, stNet.value("net/windowFwdPct", 20).toInt(), 100) / 100 /
              blockSize),
        (int)(memLimit * qBound(0, stNet.value("net/windowBackPct", 30).toInt(), 100) / 100 /
              blockSize));
    hr::BlockCacheStore::enforceCap(stNet.value("cache/capMB", 500).toLongLong(),
                                    hr::dataDir() + "/cache", holder->s->dirPath());
    /* v0.111.0：开引擎前并行预取头/尾块——格式头（mobi header/zip 头）与容器索引
     * （PDF xref/EPUB 中央目录在尾部）先行进缓存，引擎 open 全命中；
     * 此前引擎读路径逐块串行取网、warmTail 异步赶不上开引擎 */
    {
        const bool tailFmt = (ext == "pdf" || ext == "cbz" || ext == "xps" ||
                              ext == "djvu" || ext == "epub" || ext == "zip");
        const int tailBlk = tailFmt ? (blockSize <= 64 * 1024 ? 2 : 1) : 0;
        holder->s->prefillEnds(2, tailBlk, 8000);
    }
    const QString magic = ext.isEmpty() ? QString("epub") : ext;
    void *doc = hr_qt_open_stream(magic.toUtf8().constData(), size, cacheReadCb, holder->s);
    if (!doc)
    {
        delete holder;
        evt("remote_open_fail", QString::fromUtf8(hr_qt_last_error()).left(100));
        m_remoteLb->setText(hr::l10n::s("打开失败：", "Open failed: ") +
                            QString::fromUtf8(hr_qt_last_error()).left(80));
        return;
    }
    /* 缺陷#4：登记书库（键与 ReaderWindow 进度键一致；P0 起含文件名） */
    const QString rkey = QString::fromLatin1("remote://%1/%2").arg(s.id).arg(fullRel);
    Db::upsertFileMeta(rkey, name, hr_qt_count(doc), size,
                       QDateTime::currentMSecsSinceEpoch());
    /* 旧父目录键行清理（仅 file_meta，无墓碑——同一本书的历史键格式） */
    if (fullRel != target)
        Db::deleteMeta(QString::fromLatin1("remote://%1/%2").arg(s.id).arg(target));
    evt("meta_remote", QString("key=%1 pages=%2").arg(rkey.left(70)).arg(hr_qt_count(doc)));
    emit openRemoteRequested(doc, name, holder, rkey);
    evt("remote_open", QString("proto=%1 name=%2 size=%3").arg(proto, name.left(50)).arg(size));

    /* P0 适配（E1）：整书后台补齐后晋升整本缓存（对齐 WIN remote_cache_promoted）；
     * 持 QPointer 弱引用——阅读窗提前关闭（holder 析构）即中止 */
    {
        QPointer<BcsHolder> hp(holder);
        const int sid = s.id;
        const long long sz = size;
        const QString nm = name, rk = rkey;
        QThreadPool::globalInstance()->start(QRunnable::create([this, hp, sid, sz, nm, rk]
        {
            for (int i = 0; i < 300; i++)
            {
                if (!hp || !hp->s) return; /* 阅读窗已关 */
                if (hp->s->fullyCached()) break;
                QThread::sleep(2);
            }
            if (!hp || !hp->s || !hp->s->fullyCached() || sz <= 0) return;
            const int bs = hp->s->blockSizeFor();
            const QString dir = hr::dataDir() + QString::fromLatin1("/cache/remote/") +
                                QString::number(sid);
            QDir().mkpath(dir);
            const QString dest = dir + "/" + nm;
            const QString part = dest + QString::fromLatin1(".part");
            QFile pf(part);
            if (!pf.open(QIODevice::WriteOnly | QIODevice::Truncate)) return;
            QByteArray buf(bs > 65536 ? bs : 65536, Qt::Uninitialized);
            long long off = 0;
            bool ok = true;
            while (off < sz)
            {
                const int want = (int)qMin<long long>(buf.size(), sz - off);
                const int got = hp->s->readAt(off, (unsigned char *)buf.data(), want);
                if (got != want)
                {
                    ok = false;
                    break;
                }
                pf.write(buf.constData(), got);
                off += got;
            }
            pf.close();
            if (!ok || QFileInfo(part).size() != sz)
            {
                QFile::remove(part);
                return;
            }
            QFile::remove(dest);
            QFile::rename(part, dest);
            evt("remote_cache_promoted", QString("key=%1 size=%2").arg(rk.left(70)).arg(sz));
        }));
    }
}

/* remote://<id>/<target> → 找服务器 → 流式重开（书库/首页/最近阅读点击入口） */
bool LibraryWindow::openRemoteByPath(const QString &p)
{
    if (!p.startsWith(QString::fromLatin1("remote://"))) return false;
    const QString body = p.mid(9);
    const int slash = body.indexOf(QLatin1Char('/'));
    if (slash <= 0) return false;
    bool okId = false;
    const int id = body.left(slash).toInt(&okId);
    const QString target = slash + 1 < body.size() ? body.mid(slash + 1) : QString();
    if (!okId || target.isEmpty()) return false;
    for (const Db::RemoteSrc &s : Db::listRemoteSrc())
    {
        if (s.id != id) continue;
        QString name = QFileInfo(target).fileName();
        qint64 size = 0;
        for (const Db::MetaRow &m : Db::listLibrary())
            if (m.path == p)
            {
                if (!m.title.isEmpty()) name = m.title;
                size = m.size;
                break;
            }
        openRemoteItem(s, name, size, target);
        return true;
    }
    return false;
}

void LibraryWindow::openBookOrRemote(const QString &p)
{
    if (p.startsWith(QString::fromLatin1("remote://")))
    {
        if (openRemoteByPath(p)) return;
        statusBar()->showMessage(hr::l10n::s("远程书源不可用——请检查网上书库服务器",
                                             "Remote source unavailable — check servers"),
                                 6000);
        evt("remote_reopen_fail", p.left(70));
        return;
    }
    emit openRequested(p);
}

void LibraryWindow::remoteDownloadOpen(const Db::RemoteSrc &s, QListWidgetItem *it)
{
    remoteDownloadOpen(s, it->data(Qt::UserRole + 1).toString(),
                       it->data(Qt::UserRole + 4).toString());
}

/* v0.106.0（P0-1）：分级确认"整本下载"入口（openRemoteItem 弹窗直接给 name/target） */
void LibraryWindow::remoteDownloadOpen(const Db::RemoteSrc &s, const QString &name,
                                       const QString &target)
{
    const QString proto = s.protocol;
    statusBar()->showMessage(hr::l10n::s("下载中…", "Downloading…"));
    evt("remote_download_start", QString("proto=%1 name=%2").arg(proto, name.left(50)));
    QThreadPool::globalInstance()->start(QRunnable::create([this, s, proto, name, target]
    {
        QString err;
        QByteArray data;
        if (proto == "opds")
        {
            hr::OpdsClient op(remoteRootOf(s), s.user, s.password);
            data = op.fetchBytes(target, &err);
        }
        else if (proto == "sftp")
        {
            hr::SshClient ssh(s.host, s.port > 0 ? s.port : 22, s.user, s.password);
            if (!ssh.ok()) err = ssh.error();
            const long long sz = ssh.statSize(target, &err);
            long long off = 0;
            while (off < sz && err.isEmpty())
            {
                const QByteArray chunk = ssh.readRange(target, off, 1024 * 1024, &err);
                if (chunk.isEmpty() && !err.isEmpty()) break;
                data += chunk;
                off += chunk.size();
            }
        }

        else if (proto == "smb")
        {
            QString share, base;
            smbShareSplit(s.path, &share, &base);
            hr::SmbClient smb(s.host + ":" + QString::number(s.port > 0 ? s.port : 445),
                              share, s.user, s.password);
            if (!smb.ok()) err = smb.error();
            const long long sz = smb.statSize(target, &err);
            long long off = 0;
            while (off < sz && err.isEmpty())
            {
                const QByteArray chunk = smb.readRange(target, off, 1024 * 1024, &err);
                if (chunk.isEmpty() && !err.isEmpty()) break;
                data += chunk;
                off += chunk.size();
            }
        }
        else
        {
            QString url1 = remoteRootOf(s);
            const QString rel = s.path;
            if (!rel.isEmpty()) url1 += "/" + rel;
            if (!target.isEmpty()) url1 += "/" + target;
            url1 += "/" + QString::fromLatin1(QUrl::toPercentEncoding(name));
            hr::WebDavClient dav(remoteRootOf(s), s.user, s.password);
            int code = 0;
            data = dav.get(url1, &code);
            if (data.isEmpty() || (code && code >= 400)) err = "HTTP " + QString::number(code);
        }
        if (data.isEmpty())
        {
            QMetaObject::invokeMethod(this, [this, err]
            {
                statusBar()->showMessage(hr::l10n::s("下载失败：%1", "Download failed: %1")
                                             .arg(err.left(80)), 8000);
                evt("remote_download_fail", err.left(80));
            }, Qt::QueuedConnection);
            return;
        }
        QString dir = QStandardPaths::writableLocation(QStandardPaths::DownloadLocation);
        if (dir.isEmpty()) dir = QDir::homePath();
        dir += "/HowRead";
        QDir().mkpath(dir);
        QString dest = dir + "/" + name;
        int k = 1;
        while (QFileInfo::exists(dest))
            dest = dir + "/" + QFileInfo(name).completeBaseName() +
                   QString("(%1).").arg(k++) + QFileInfo(name).suffix();
        QFile f(dest);
        if (!f.open(QIODevice::WriteOnly | QIODevice::Truncate) || f.write(data) != data.size())
        {
            QMetaObject::invokeMethod(this, [this]
            {
                statusBar()->showMessage(hr::l10n::s("写入失败", "Write failed"), 8000);
            }, Qt::QueuedConnection);
            return;
        }
        f.close();
        /* 引擎直开补页数/标题后入库 */
        void *d = hr_qt_open(hr::openablePath(dest).toUtf8().constData());
        int pages = 0;
        QString title;
        if (d)
        {
            pages = hr_qt_count(d);
            title = QString::fromUtf8(hr_qt_meta(d, 0));
            hr_qt_close(d);
        }
        Db::upsertFileMeta(dest, title, pages, (long long)data.size(),
                           QFileInfo(dest).lastModified().toMSecsSinceEpoch());
        QMetaObject::invokeMethod(this, [this, dest, proto, name, ms = data.size()]
        {
            loadTable();
            statusBar()->showMessage(
                hr::l10n::s("已下载并入库：%1", "Downloaded: %1").arg(dest.left(70)), 8000);
            evt("remote_download",
                QString("proto=%1 name=%2 size=%3").arg(proto, name.left(50)).arg(ms));
            openBookOrRemote(dest);
        }, Qt::QueuedConnection);
    }));
}

void LibraryWindow::refreshLocalHome()
{
    if (!m_loSw) return;
    QWidget *w = m_loSw->widget(0);
    /* v0.99.1 W21 崩溃根修：本函数只允许重建“位置组”页（objectName=loHome）。
     * 此前无条件移除 widget(0)——首次调用时 widget(0) 是浏览视图 br（m_loList 的父页），
     * deleteLater 后真实事件循环将其销毁，m_loList 悬空；首页点“我的文件”条目
     * → fillLocal→m_loList->clear() 必崩（WER 0x14c5110 selectionModel 悬空实证）。
     * 探针不复现因 processEvents 不处理 DeferredDelete，br 一直未真正销毁。 */
    if (w && w->objectName() != QLatin1String("loHome"))
        return;
    if (w)
    {
        m_loSw->removeWidget(w);
        w->deleteLater(); /* v0.92.0 W13：行内按钮触发整页重建时按钮仍在信号栈内，须延迟释放 */
    }

    QWidget *home = new QWidget;
    home->setObjectName(QString::fromLatin1("loHome")); /* W21：重建守卫的识别标记 */
    QVBoxLayout *lay = new QVBoxLayout(home);
    lay->setContentsMargins(12, 8, 12, 12);

    /* v0.98.0 W19#1：页面顶部“我的文件”标题+说明行（对齐 Compose 我的文件页） */
    QLabel *pgTitle = new QLabel(hr::l10n::s("我的文件", "My Files"));
    {
        QFont tf = pgTitle->font();
        tf.setBold(true);
        tf.setPointSizeF(tf.pointSizeF() * 1.4);
        pgTitle->setFont(tf);
    }
    lay->addWidget(pgTitle);
    QLabel *pgSub = new QLabel(hr::l10n::s("选择一个位置开始浏览；书籍文件点击即可打开阅读。",
                                           "Pick a location to browse; click a book file to read."));
    pgSub->setStyleSheet(QString::fromLatin1(
        "color:palette(mid); background:transparent; font-size:%1px;").arg(hr::Ui::fsPx(13)));
    lay->addWidget(pgSub);
    lay->addSpacing(4);

    /* v0.97.0 W18#4：网络书库按 WebDAV / SMB / SFTP 分组显示，每组节头右侧自带
     * “+ 添加”按钮（用户点名恢复分组+组级添加；OPDS 归网上书库页管）。
     * 服务器行改为普通布局行，随整页 deleteLater 重建回收——不再走
     * setItemWidget/itemWidget（W17#5 的二次释放坑从结构上消除）。 */
    auto addServer = [this](const QString &proto)
    {
        Db::RemoteSrc r;
        r.protocol = proto;
        if (remoteServerForm(&r))
        {
            Db::addRemoteSrc(r);
            evt("remote_srv_add", QString("proto=%1 host=%2").arg(proto, r.host.left(60)));
            refreshRemoteServers();
            refreshLocalHome();
        }
    };
    auto srvRow = [this](QVBoxLayout *box, const Db::RemoteSrc &s)
    {
        QWidget *row = new QWidget;
        QHBoxLayout *h = new QHBoxLayout(row);
        h->setContentsMargins(10, 2, 4, 2);
        h->addWidget(new QLabel(QString("%1  ·  %2 %3:%4 %5")
                                    .arg(s.name, s.protocol, s.host)
                                    .arg(s.port)
                                    .arg(s.path)), 1);
        QPushButton *sc = new QPushButton(hr::l10n::s("扫描", "Scan"));
        QPushButton *ed = new QPushButton(hr::l10n::s("编辑", "Edit"));
        QPushButton *dl = new QPushButton(hr::l10n::s("删", "Del"));
        connect(sc, &QPushButton::clicked, this, [this, s]
        {
            remoteBrowse(s, QString());
            showTab(4);
        });
        connect(ed, &QPushButton::clicked, this, [this, s]
        {
            Db::RemoteSrc e = s;
            if (remoteServerForm(&e))
            {
                Db::updateRemoteSrc(e);
                refreshRemoteServers();
                refreshLocalHome();
            }
        });
        connect(dl, &QPushButton::clicked, this, [this, s]
        {
            if (QMessageBox::question(this, hr::l10n::s("删除服务器", "Delete server"),
                                      hr::l10n::s("删除“%1”？", "Delete \"%1\"?").arg(s.name)) ==
                QMessageBox::Yes)
            {
                Db::deleteRemoteSrc(s.id);
                refreshRemoteServers();
                refreshLocalHome();
            }
        });
        h->addWidget(sc);
        h->addWidget(ed);
        h->addWidget(dl);
        row->setStyleSheet(QString::fromLatin1(
            "QWidget{background:transparent; border-radius:8px;}"
            "QWidget:hover{background:palette(alternate-base);}")); /* W19：行透明贴齐 Compose */
        box->addWidget(row);
    };
    auto addProtoGroup = [this, &addServer, &srvRow](QVBoxLayout *layX, const char *zh,
                                                     const char *en, const char *proto)
    {
        QWidget *hr2 = new QWidget;
        QHBoxLayout *hh = new QHBoxLayout(hr2);
        hh->setContentsMargins(0, 0, 0, 0);
        QLabel *lb = new QLabel(hr::l10n::s(zh, en), hr2);
        lb->setStyleSheet(remoteGroupStyle());
        hh->addWidget(lb);
        hh->addStretch(1);
        QPushButton *b = new QPushButton(hr::l10n::s("+ 添加", "+ Add"), hr2);
        b->setFlat(true);
        b->setCursor(Qt::PointingHandCursor);
        b->setStyleSheet(QString::fromLatin1(
            "QPushButton{color:palette(link); border:none; font-weight:bold;}"));
        const QString p = QString::fromLatin1(proto);
        connect(b, &QPushButton::clicked, this, [addServer, p] { addServer(p); });
        hh->addWidget(b);
        layX->addWidget(hr2);
        for (const Db::RemoteSrc &s : Db::listRemoteSrc())
        {
            if (s.protocol.toLower() != p) continue;
            srvRow(layX, s);
        }
    };
    /* v0.98.0 W19#2：分组顺序对齐 Compose——网上书库(OPDS)/WebDAV/SMB/SFTP/本地位置 */
    addProtoGroup(lay, "网上书库（OPDS）", "Network (OPDS)", "opds");
    addProtoGroup(lay, "WebDAV", "WebDAV", "webdav");
    addProtoGroup(lay, "SMB", "SMB", "smb");
    addProtoGroup(lay, "SFTP", "SFTP", "sftp");
    lay->addSpacing(8);

    /* v0.91.0 W12：本地位置=书库目录动态行（对齐 Compose）——去写死的 主目录/下载/文档/桌面 */
    QWidget *lhr = new QWidget;
    QHBoxLayout *lhh = new QHBoxLayout(lhr);
    lhh->setContentsMargins(0, 0, 0, 0);
    QLabel *lh = new QLabel(hr::l10n::s("本地位置", "Local locations"));
    lh->setStyleSheet(remoteGroupStyle());
    lhh->addWidget(lh);
    lhh->addStretch(1);
    QPushButton *addLoc = new QPushButton(hr::l10n::s("+ 添加本地目录", "+ Add local folder"));
    addLoc->setFlat(true);
    addLoc->setCursor(Qt::PointingHandCursor);
    addLoc->setStyleSheet(QString::fromLatin1(
        "QPushButton{color:palette(link); border:none; font-weight:bold;}"));
    connect(addLoc, &QPushButton::clicked, this, [this] { onManageDirs(); });
    lhh->addWidget(addLoc);
    lay->addWidget(lhr);

    const QStringList locDirs = Db::scanDirs();
    if (locDirs.isEmpty())
    {
        QLabel *empty = new QLabel(hr::l10n::s(
            "尚未添加本地目录：点右上“+ 添加本地目录”，或到书库页添加。",
            "No local folder yet: use \"+ Add local folder\" above or the Library page."));
        empty->setStyleSheet(QString::fromLatin1("color:palette(mid);"));
        lay->addWidget(empty);
    }
    const QString locLinkQss = QString::fromLatin1(
        "QPushButton{color:palette(link); border:none; background:transparent;}"
        "QPushButton:hover{text-decoration:underline;}");
    for (const QString &d : locDirs)
    {
        const QDir dd(d);
        const QString name = dd.dirName().isEmpty() ? d : dd.dirName();
        LocRowWidget *row = new LocRowWidget;
        row->setCursor(Qt::PointingHandCursor);
        row->setStyleSheet(QString::fromLatin1(
            "LocRowWidget{background:transparent; border-radius:8px;}"
            "LocRowWidget:hover{background:palette(alternate-base);}"));
        QHBoxLayout *h = new QHBoxLayout(row);
        h->setContentsMargins(8, 8, 8, 8);
        h->setSpacing(10);
        QLabel *ic = new QLabel;
        ic->setPixmap(hr::Icons::pix(QString::fromLatin1("glyphicons_336_folder"),
                                     QColor(0, 150, 136), 22));
        h->addWidget(ic);
        QVBoxLayout *tc = new QVBoxLayout;
        tc->setContentsMargins(0, 0, 0, 0);
        tc->setSpacing(1);
        QLabel *t1 = new QLabel(hr::l10n::s("书库 · %1", "Library · %1").arg(name));
        t1->setStyleSheet(QString::fromLatin1(
            "color:palette(text); font-weight:bold; font-size:%1px;")
                              .arg(hr::Ui::fsPx(13)));
        QLabel *t2 = new QLabel(QDir::toNativeSeparators(d));
        t2->setStyleSheet(QString::fromLatin1(
            "color:palette(mid); font-size:%1px;").arg(hr::Ui::fsPx(11)));
        tc->addWidget(t1);
        tc->addWidget(t2);
        h->addLayout(tc, 1);
        QPushButton *sc = new QPushButton(hr::l10n::s("扫描书籍", "Scan"));
        sc->setFlat(true);
        sc->setCursor(Qt::PointingHandCursor);
        sc->setStyleSheet(locLinkQss);
        connect(sc, &QPushButton::clicked, this, [this, d]
        {
            statusBar()->showMessage(hr::l10n::s("正在扫描：", "Scanning: ") +
                                     QDir::toNativeSeparators(d), 6000);
            scanAll(QStringList{d});
            evt("loc_scan", "path=" + d.left(120));
        });
        QPushButton *ed = new QPushButton(hr::l10n::s("编辑", "Edit"));
        ed->setFlat(true);
        ed->setCursor(Qt::PointingHandCursor);
        ed->setStyleSheet(locLinkQss);
        connect(ed, &QPushButton::clicked, this, [this] { onManageDirs(); });
        QPushButton *dl = new QPushButton(hr::l10n::s("删除", "Delete"));
        dl->setFlat(true);
        dl->setCursor(Qt::PointingHandCursor);
        dl->setStyleSheet(QString::fromLatin1(
            "QPushButton{color:#d32f2f; border:none; background:transparent;}"
            "QPushButton:hover{text-decoration:underline;}"));
        connect(dl, &QPushButton::clicked, this, [this, d, name]
        {
            if (QMessageBox::question(
                    this, hr::l10n::s("从书库移除", "Remove from library"),
                    hr::l10n::s("从书库移除“%1”？\n（不删除磁盘上的文件）",
                                "Remove \"%1\" from library?\n(Files on disk are kept)")
                        .arg(name)) == QMessageBox::Yes)
            {
                Db::removeScanDir(d);
                evt("lib_rmdir", "path=" + d.left(120));
                refreshLocalHome();
                loadTable();
            }
        });
        h->addWidget(sc);
        h->addWidget(ed);
        h->addWidget(dl);
        row->onOpen = [this, d]
        {
            m_loDir = d;
            fillLocal(d);
            m_loSw->setCurrentIndex(1);
            evt("loc_open", "path=" + d.left(120));
        };
        lay->addWidget(row);
    }
    lay->addStretch(1);
    m_loSw->insertWidget(0, home);
    m_loSw->setCurrentIndex(0);
}

/* v0.96.0 W17#5 探针：临时插一条测试服务器 → 连续重建“我的文件”两次
 * （第二次即“删除已注册行控件”路径 = 修复前 double-free 崩溃路径）→ 删除临时项。
 * 进程存活到 browse2 evt 即修复生效。 */
void LibraryWindow::probeBrowse2()
{
    Db::RemoteSrc t;
    t.protocol = QString::fromLatin1("webdav");
    t.name = QString::fromLatin1("W17-SELFTEST");
    t.host = QString::fromLatin1("127.0.0.1");
    t.port = 1;
    t.path = QString::fromLatin1("/");
    Db::addRemoteSrc(t);
    refreshLocalHome();
    refreshRemoteServers();
    QCoreApplication::processEvents();
    refreshLocalHome();
    refreshRemoteServers();
    QCoreApplication::processEvents();
    for (const Db::RemoteSrc &s : Db::listRemoteSrc())
        if (s.name == t.name)
        {
            Db::deleteRemoteSrc(s.id);
            break;
        }
    refreshRemoteServers();
    refreshLocalHome();
    evt("browse2", "rounds=2 survived");
}

/* v0.99.0 W20#1 探针：重放“目录管理对话框关闭”尾部路径（refreshLocalHome+scanAll，
 * 用户 lib_adddir 后崩溃的最后事件序列），供 gdb 抓栈定位第二个内存破坏源 */
void LibraryWindow::debugBrowseRoundtrip()
{
    refreshLocalHome();
    scanAll();
}

/* v0.99.0 W20 取证：清空 file_meta（可由扫描无损重建）强制引擎逐本重开用户书，
 * 复现“加目录后首扫崩溃”（引擎解析用户真实书文件时踩堆的嫌疑） */
void LibraryWindow::debugWipeMetaRescan()
{
    const QList<Db::MetaRow> rows = Db::listLibrary();
    for (const Db::MetaRow &r : rows)
        Db::deleteMeta(r.path);
    evt("w20repro", QString("meta wiped n=%1").arg(rows.size()));
    scanAll();
}

/* v0.99.0 W20 取证：重放首页“我的文件”圆钮完整 handler（fillLocal+切浏览页） */
void LibraryWindow::debugOpenFirstScanDir()
{
    const QStringList dirs = Db::scanDirs();
    for (const QString &d : dirs) /* W20：逐个目录重放（用户点的是新增目录） */
    {
        evt("w20repro", "open=" + d.left(60));
        if (QFileInfo(d).isDir())
        {
            fillLocal(d);
            if (m_loSw) m_loSw->setCurrentIndex(1);
        }
        showTab(2);
    }
}

void LibraryWindow::fillLocal(const QString &dir)
{
    m_loDir = dir;
    evt("fl_step", QString("1-begin %1").arg(dir.left(60))); /* W20 定位 */
    m_loList->clear();
    evt("fl_step", "2-cleared");
    QDir d(dir);
    QLabel *crumb = m_browsePage->findChild<QLabel *>("u5_lo_crumb");
    if (crumb) crumb->setText(QDir::toNativeSeparators(dir));
    const QFileInfoList ds =
        d.entryInfoList(QDir::Dirs | QDir::NoDotAndDotDot, QDir::Name | QDir::DirsFirst);
    evt("fl_step", QString("3-dirs=%1").arg(ds.size()));
    for (const QFileInfo &fi : ds)
    {
        QListWidgetItem *it = new QListWidgetItem(fi.fileName() + "/", m_loList);
        it->setData(Qt::UserRole, true);
        it->setData(Qt::UserRole + 1, fi.absoluteFilePath());
    }
    int n = 0;
    const QFileInfoList fs =
        d.entryInfoList(QDir::Files, QDir::Name);
    evt("fl_step", QString("4-files=%1").arg(fs.size()));
    for (const QFileInfo &fi : fs)
    {
        if (!m_exts.contains(fi.suffix().toLower())) continue;
        QListWidgetItem *it = new QListWidgetItem(
            QString("%1   (%2 KB)").arg(fi.fileName()).arg(fi.size() / 1024), m_loList);
        it->setData(Qt::UserRole, false);
        it->setData(Qt::UserRole + 1, fi.absoluteFilePath());
        n++;
    }
    evt("local_list", QString("dir=%1 n=%2").arg(dir.left(80)).arg(n));
    evt("fl_step", "5-done");
}

void LibraryWindow::buildBrowsePage()
{
    QVBoxLayout *bl = new QVBoxLayout(m_browsePage);
    bl->setContentsMargins(0, 0, 0, 0);
    m_loSw = new QStackedLayout;
    bl->addLayout(m_loSw);

    /* 视图 1：本地目录浏览 */
    QWidget *br = new QWidget;
    QVBoxLayout *v = new QVBoxLayout(br);
    v->setContentsMargins(8, 6, 8, 8);
    {
        QWidget *row = new QWidget;
        QHBoxLayout *h = new QHBoxLayout(row);
        h->setContentsMargins(0, 0, 0, 0);
        QPushButton *back = new QPushButton(hr::l10n::s("‹ 返回", "‹ Back"));
        connect(back, &QPushButton::clicked, this, [this]
        {
            refreshLocalHome();
            m_loSw->setCurrentIndex(0);
        });
        QLabel *crumb = new QLabel;
        crumb->setObjectName("u5_lo_crumb");
        QPushButton *add = new QPushButton(hr::l10n::s("加入书库", "Add to library"));
        connect(add, &QPushButton::clicked, this, [this]
        {
            if (m_loDir.isEmpty()) return;
            Db::addScanDir(m_loDir);
            evt("lib_adddir", "path=" + m_loDir.left(120));
            statusBar()->showMessage(hr::l10n::s("已加入书库并扫描", "Added to library"), 6000);
            loadTable();
        });
        QPushButton *up = new QPushButton(hr::l10n::s("上一级", "Up"));
        connect(up, &QPushButton::clicked, this, [this]
        {
            const QDir d(m_loDir);
            const QString p = d.absolutePath();
            if (p != "/" && !p.isEmpty())
                fillLocal(QFileInfo(p).absolutePath()), m_loSw->setCurrentIndex(1);
        });
        h->addWidget(back);
        h->addWidget(crumb, 1);
        h->addWidget(up);
        h->addWidget(add);
        v->addWidget(row);
    }
    m_loList = new QListWidget;
    v->addWidget(m_loList, 1);
    v->addWidget(new QLabel(hr::l10n::s(
        "双击进入目录/打开书；加入书库后该书库目录内全部受支持格式入库。",
        "Double-click to navigate/open; add-to-library scans all supported formats.")));
    m_loSw->addWidget(br);
    connect(m_loList, &QListWidget::itemDoubleClicked, this, [this](QListWidgetItem *it)
    {
        if (it->data(Qt::UserRole).toBool())
        {
            fillLocal(it->data(Qt::UserRole + 1).toString());
            m_loSw->setCurrentIndex(1);
        }
        else
        {
            openBookOrRemote(it->data(Qt::UserRole + 1).toString());
        }
    });

    /* 视图 0：位置组（网络分组+本地位置） */
    /* v0.99.1 W21：先放占位页占住 index 0，让浏览视图 br 稳定位于 index 1，
     * 首跑 refreshLocalHome 只替换占位页。
     * v0.99.3 W23 修复：占位页必须也带 loHome 标记——否则重建守卫把它当外页直接
     * return，位置组页从未构建，“我的文件”页（标签栏入口）只剩空白占位。 */
    {
        QWidget *ph = new QWidget;
        ph->setObjectName(QString::fromLatin1("loHome"));
        m_loSw->insertWidget(0, ph);
    }
    refreshLocalHome();
}

/* ---------- U7 首页（HomeScreen 六区块 + FAB）与 ExtraPages ---------- */

/* 清空一行布局（删子控件/子布局） */
static void rowClear(QLayout *lay)
{
    if (!lay) return;
    while (QLayoutItem *it = lay->takeAt(0))
    {
        if (QWidget *w = it->widget()) w->deleteLater();
        else if (QLayout *l = it->layout()) rowClear(l);
        delete it;
    }
}


/* ---------- v0.82.0 W1 D5：远程书云缓存角标（对齐 Compose RemoteCacheBadge：
 * 整本=云徽绿底；缓存中=下载徽+百分比；未缓存=下载徽。缓存键=qt-md5(proto|target)-size，
 * 与 openRemoteItem 构造一致；块位图 cache/remote-blocks/<key>/blocks.bin ---------- */
static QPixmap coverTileBase(const QString &path, const QString &title, int w, int h);

static int remoteCachePct(const QString &path)
{
    const QString body = path.mid(9); /* 去 remote:// */
    const int slash = body.indexOf(QLatin1Char('/'));
    if (slash <= 0) return -1;
    const int id = body.left(slash).toInt();
    const QString target = body.mid(slash + 1);
    QString proto;
    for (const Db::RemoteSrc &s : Db::listRemoteSrc())
    {
        if (s.id == id)
        {
            proto = s.protocol;
            break;
        }
    }
    if (proto.isEmpty()) return -1;
    const QString md5s = QString::fromLatin1(
        QCryptographicHash::hash((proto + "|" + target).toUtf8(),
                                 QCryptographicHash::Md5).toHex());
    const QDir root(hr::dataDir() + "/cache/remote-blocks");
    const QStringList dirs = root.entryList(
        QStringList() << (QString::fromLatin1("qt-") + md5s + QString::fromLatin1("-*")),
        QDir::Dirs | QDir::NoDotAndDotDot);
    if (dirs.isEmpty()) return -1;
    QFile bf(root.filePath(dirs.first()) + QString::fromLatin1("/blocks.bin"));
    if (!bf.open(QIODevice::ReadOnly)) return 0;
    const QByteArray bits = bf.readAll();
    if (bits.isEmpty()) return 0;
    int ones = 0;
    for (char c : bits)
        if (c) ones++;
    return ones * 100 / int(bits.size());
}

static void drawCloudBadge(QPixmap *pm, int pct)
{
    if (!pm || pm->isNull()) return;
    QPainter p(pm);
    p.setRenderHint(QPainter::Antialiasing);
    const bool done = (pct >= 100);
    const QString txt =
        (pct > 0 && pct < 100) ? QString::number(pct) + QString::fromLatin1("%") : QString();
    QFont f = p.font();
    f.setPixelSize(10);
    f.setBold(true);
    p.setFont(f);
    const int iconSz = 12;
    const int tw = txt.isEmpty() ? 0 : p.fontMetrics().horizontalAdvance(txt) + 2;
    const int bw = 5 + iconSz + 3 + tw + 4;
    const QRectF br(4, 4, bw, 18);
    p.setPen(Qt::NoPen);
    p.setBrush(done ? QColor(0x2E, 0x8B, 0x57, 215) : QColor(0, 0, 0, 150));
    p.drawRoundedRect(br, 5, 5);
    if (hr::Icons::have())
    {
        const QPixmap ic = hr::Icons::pix(done ? QString::fromLatin1("glyphicons_544_cloud")
                                               : QString::fromLatin1("glyphicons_302_square_download"),
                                          QColor(Qt::white), iconSz, 1.0);
        p.drawPixmap(QPointF(br.left() + 4, br.top() + 3), ic);
    }
    if (!txt.isEmpty())
    {
        p.setPen(Qt::white);
        p.drawText(QRectF(br.left() + 5 + iconSz + 3, br.top(), tw, br.height()),
                   Qt::AlignVCenter | Qt::AlignLeft, txt);
    }
    p.end();
}

/* 封面瓦片：有缓存 PNG 用之，否则灰底+标题字（远程书出瓦片时叠云缓存角标） */
static QPixmap coverTile(const QString &path, const QString &title, int w, int h)
{
    QPixmap pm = coverTileBase(path, title, w, h);
    if (path.startsWith(QString::fromLatin1("remote://")))
        drawCloudBadge(&pm, remoteCachePct(path));
    return pm;
}

/* v0.89.0 W8：封面卡合成——圆角 8 + 边框 + 收藏星标（右上） */
static QPixmap w8RoundedCover(QPixmap pm, bool fav)
{
    QPixmap out(pm.size());
    out.fill(Qt::transparent);
    QPainter pt(&out);
    pt.setRenderHint(QPainter::Antialiasing);
    QPainterPath ppth;
    ppth.addRoundedRect(0, 0, pm.width(), pm.height(), 8, 8);
    pt.setClipPath(ppth, Qt::IntersectClip);
    pt.drawPixmap(0, 0, pm);
    pt.setClipping(false);
    pt.setPen(QPen(QApplication::palette().color(QPalette::Mid), 1));
    pt.drawRoundedRect(0, 0, pm.width() - 1, pm.height() - 1, 8, 8);
    if (fav)
    {
        pt.setPen(QColor(0xF5, 0xA6, 0x23));
        QFont sf = pt.font();
        sf.setPixelSize(16);
        sf.setBold(true);
        pt.setFont(sf);
        pt.drawText(QRect(pm.width() - 24, 3, 20, 20), Qt::AlignCenter,
                    QString::fromUtf8("\xE2\x98\x85"));
    }
    pt.end();
    return out;
}

/* 封面瓦片：有缓存 PNG 用之，否则灰底+标题字 */
static QPixmap coverTileBase(const QString &path, const QString &title, int w, int h)
{
    const QString png = coverPathFor(path);
    const QPixmap hit = coverPngScaled(png, w, h); /* v0.107.2：内存缓存命中直接回 */
    if (!hit.isNull())
        return hit;
    QPixmap pm(w, h);
    pm.fill(QColor(0xEC, 0xEC, 0xEC));
    QPainter p(&pm);
    p.setPen(QColor(0x7A, 0x7A, 0x7A));
    QFont f = p.font();
    f.setPointSize(qMax(9, h / 10));
    p.setFont(f);
    /* v0.87.0 W6 H6：占位文字先中部省略再绘制（长文件名不再两侧裁切） */
    const QRect box = pm.rect().adjusted(3, 3, -3, -3);
    const QFontMetrics fm(f);
    QString ph = title.left(24);
    if (fm.horizontalAdvance(ph) > box.width())
        ph = fm.elidedText(ph, Qt::ElideMiddle, box.width());
    p.drawText(box, Qt::AlignCenter | Qt::TextWordWrap, ph);
    return pm;
}

/* 圆形图标（我的文件/网上书库区块条目） */
static QIcon circleIcon(const QString &glyph, const QColor &bg, const QColor &fg)
{
    QPixmap pm(52, 52);
    pm.fill(Qt::transparent);
    QPainter p(&pm);
    p.setRenderHint(QPainter::Antialiasing);
    p.setBrush(bg);
    p.setPen(Qt::NoPen);
    p.drawEllipse(1, 1, 50, 50);
    p.setPen(fg);
    QFont f = p.font();
    f.setBold(true);
    f.setPointSize(glyph.size() > 1 ? 11 : 16);
    p.setFont(f);
    p.drawText(pm.rect(), Qt::AlignCenter, glyph);
    return QIcon(pm);
}

/* v0.76.0 V1：圆形底+真图标（首页圆钮；无 QtSvg 时由调用方回退 circleIcon） */
static QIcon circleIconFile(const QString &file, const QColor &bg)
{
    QPixmap pm(52, 52);
    pm.fill(Qt::transparent);
    QPainter p(&pm);
    p.setRenderHint(QPainter::Antialiasing);
    p.setBrush(bg);
    p.setPen(Qt::NoPen);
    p.drawEllipse(1, 1, 50, 50);
    p.end();
    const QPixmap ic = hr::Icons::pix(file, QColor(0x12, 0x5A, 0x9C), 28,
                                      qApp->devicePixelRatio());
    if (!ic.isNull())
    {
        QPainter p2(&pm);
        p2.drawPixmap((52 - 28) / 2, (52 - 28) / 2, 28, 28, ic);
        p2.end();
    }
    return QIcon(pm);
}

/* 时长格式（安卓同款：X小时Y分 / Y分钟） */
static QString fmtDurMin(int m)
{
    if (m >= 60)
        return QString(hr::l10n::s("%1小时%2分", "%1h %2m")).arg(m / 60).arg(m % 60);
    return QString(hr::l10n::s("%1分钟", "%1m")).arg(m);
}

/* 区块标题行：标题 + 副标题 + [齿轮] + 更多 › */
static QWidget *mkHeader(const QString &title, QLabel **subOut, QPushButton **moreOut,
                         QPushButton **gearOut)
{
    QWidget *w = new QWidget;
    QHBoxLayout *hl = new QHBoxLayout(w);
    hl->setContentsMargins(0, 0, 0, 0);
    hl->setSpacing(8);
    QLabel *tt = new QLabel(title, w);
    QFont f = tt->font();
    f.setBold(true);
    if (f.pointSizeF() > 0.0)
        f.setPointSizeF(f.pointSizeF() * 1.2); /* v0.96.0 W17#4：六区块标题字号调大一级 */
    tt->setFont(f);
    hl->addWidget(tt);
    QLabel *sb = new QLabel(w);
    sb->setStyleSheet(QString::fromLatin1("color:palette(mid); font-size:%1px;").arg(hr::Ui::fsPx(11)));
    hl->addWidget(sb);
    if (subOut) *subOut = sb;
    hl->addStretch(1);
    if (gearOut)
    {
        QPushButton *g = new QPushButton(QString::fromUtf8("\xE2\x9A\x99"), w);
        g->setFlat(true);
        g->setFixedSize(26, 22);
        g->setCursor(Qt::PointingHandCursor);
        g->setToolTip(hr::l10n::s("阅读统计配置", "Stats settings"));
        hl->addWidget(g);
        *gearOut = g;
    }
    QPushButton *m = new QPushButton(hr::l10n::s("更多 ›", "More \xE2\x80\xBA"), w);
    m->setFlat(true);
    m->setCursor(Qt::PointingHandCursor);
    m->setStyleSheet(QString::fromLatin1(
        "QPushButton{color:palette(mid); font-size:%1px; border:none; padding:2px 4px;}").arg(hr::Ui::fsPx(12)));
    hl->addWidget(m);
    if (moreOut) *moreOut = m;
    return w;
}

/* 统计小卡（数字+标签；clickable 时为按钮） */
static QWidget *mkStatCell(const QString &label, QLabel **numOut, bool clickable,
                           QPushButton **btnOut)
{
    QWidget *fr;
    if (clickable)
    {
        QPushButton *b = new QPushButton;
        b->setFlat(true);
        b->setCursor(Qt::PointingHandCursor);
        if (btnOut) *btnOut = b;
        fr = b;
    }
    else
        fr = new QFrame;
    fr->setMinimumHeight(62); /* W17#4：随放大后的数字加高 */
    fr->setStyleSheet(QString::fromLatin1(
        "QWidget{background:palette(base); border-radius:8px; border:none;}"
        "QPushButton:hover{background:palette(alternate-base);}"));
    QVBoxLayout *vl = new QVBoxLayout(fr);
    vl->setContentsMargins(10, 6, 10, 6);
    vl->setSpacing(1);
    QLabel *num = new QLabel(QString::fromLatin1("-"), fr);
    num->setStyleSheet(QString::fromLatin1(
        "color:palette(link); font-size:%1px; font-weight:bold; background:transparent;").arg(hr::Ui::fsPx(18))); /* W17#4 */
    QLabel *lb = new QLabel(label + (clickable ? QString::fromUtf8(" \xE2\x80\xBA")
                                               : QString()), fr);
    lb->setStyleSheet(QString::fromLatin1("color:palette(mid); font-size:%1px; background:transparent;").arg(hr::Ui::fsPx(10)));
    vl->addWidget(num);
    vl->addWidget(lb);
    if (numOut) *numOut = num;
    return fr;
}

QString LibraryWindow::homeLastBook() const
{
    QList<Db::ProgressRow> rec = Db::allProgress();
    std::sort(rec.begin(), rec.end(), [](const Db::ProgressRow &a, const Db::ProgressRow &b)
              { return a.updatedAt > b.updatedAt; });
    for (const Db::ProgressRow &r : rec)
        if (r.path.startsWith(QString::fromLatin1("remote://")) || QFileInfo::exists(r.path))
            return r.path;
    return QString();
}

void LibraryWindow::buildHomePage()
{
    if (!m_homePage) m_homePage = new QWidget;
    QVBoxLayout *hl = new QVBoxLayout(m_homePage);
    hl->setContentsMargins(18, 12, 18, 8);
    hl->setSpacing(8);
    QLabel *title = new QLabel(hr::l10n::s("首页", "Home"), m_homePage);
    QFont tf = title->font();
    tf.setBold(true);
    tf.setPointSize(tf.pointSize() + 3);
    title->setFont(tf);
    hl->addWidget(title);

    QScrollArea *sa = new QScrollArea(m_homePage);
    sa->setWidgetResizable(true);
    sa->setFrameShape(QFrame::NoFrame);
    QWidget *root = new QWidget;
    QVBoxLayout *lay = new QVBoxLayout(root);
    lay->setContentsMargins(0, 0, 8, 0);
    lay->setSpacing(6);
    sa->setWidget(root);
    /* v0.95.0 W16：sa 改挂尾部网格叠放单元格（FAB 悬浮其上），不再独立占行 */

    auto mkRow = [](QHBoxLayout **out) -> QWidget *
    {
        QWidget *w = new QWidget;
        QHBoxLayout *r = new QHBoxLayout(w);
        r->setContentsMargins(0, 0, 0, 0);
        r->setSpacing(12);
        *out = r;
        return w;
    };
    QPushButton *more = nullptr, *gear = nullptr;

    /* 1 最近阅读 */
    lay->addWidget(mkHeader(hr::l10n::s("最近阅读", "Recent"), &m_hSubRecent, &more, nullptr));
    { /* v0.81.0 V6：最近横排放横向滚动容器（对齐 Compose LazyRow；书多出横向滚动条不挤压） */
        QWidget *rowW = mkRow(&m_hRecent);
        QScrollArea *rs = new QScrollArea;
        rs->setWidgetResizable(true);
        rs->setFrameShape(QFrame::NoFrame);
        rs->setFixedHeight(258); /* W18#2：随再放大的封面瓦片加高 */
        rs->setVerticalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
        rs->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff); /* v0.86.0 W5 H5：对齐 LazyRow 无滚动条 */
        rs->setWidget(rowW);
        lay->addWidget(rs);
    }
    connect(more, &QPushButton::clicked, this, [this]
    {
        if (m_statusC)
        {
            const int i = m_statusC->findData(QString::fromLatin1("reading"));
            if (i >= 0) m_statusC->setCurrentIndex(i);
        }
        evt("home_recent_more", "");
        showTab(1);
    });
    lay->addSpacing(8);

    /* 2 书签笔记 */
    lay->addWidget(mkHeader(hr::l10n::s("书签笔记", "Notes"), &m_hSubBm, &more, nullptr));
    lay->addWidget(mkRow(&m_hBm));
    connect(more, &QPushButton::clicked, this, [this] { evt("home_bm_more", ""); showTab(5); });
    lay->addSpacing(8);

    /* 3 我的珍藏 */
    lay->addWidget(mkHeader(hr::l10n::s("我的珍藏", "Favorites"), &m_hSubFav, &more, nullptr));
    lay->addWidget(mkRow(&m_hFav));
    connect(more, &QPushButton::clicked, this, [this] { evt("home_fav_more", ""); showTab(6); });
    lay->addSpacing(8);

    /* 4 阅读统计（2×2 + 总时长大卡；齿轮=清空两击确认） */
    lay->addWidget(mkHeader(hr::l10n::s("阅读统计", "Stats"), &m_hSubStats, &more, &gear));
    connect(more, &QPushButton::clicked, this, [this]
    {
        evt("home_stats_total_open", "more");
        onStats();
    });
    connect(gear, &QPushButton::clicked, this, &LibraryWindow::onClearStatsDialog);
    {
        QWidget *sw = new QWidget;
        QHBoxLayout *sl = new QHBoxLayout(sw);
        sl->setContentsMargins(0, 0, 0, 0);
        sl->setSpacing(10);
        QWidget *gw = new QWidget;
        QGridLayout *gl = new QGridLayout(gw);
        gl->setContentsMargins(0, 0, 0, 0);
        gl->setSpacing(10);
        QPushButton *readBtn = nullptr;
        gl->addWidget(mkStatCell(hr::l10n::s("书籍总数", "Books"), &m_hStBooks, false, nullptr),
                      0, 0);
        gl->addWidget(mkStatCell(hr::l10n::s("已读书籍", "Read"), &m_hStRead, true, &readBtn),
                      0, 1);
        gl->addWidget(mkStatCell(hr::l10n::s("今日阅读", "Today"), &m_hStToday, false, nullptr),
                      1, 0);
        gl->addWidget(mkStatCell(hr::l10n::s("阅读速度(页/分)", "Pages/min"), &m_hStSpeed,
                                 false, nullptr), 1, 1);
        sl->addWidget(gw, 22);
        QPushButton *totalBtn = nullptr;
        QWidget *tc = mkStatCell(hr::l10n::s("总阅读时间", "Total"), &m_hStTotal, true,
                                 &totalBtn);
        tc->setMinimumHeight(118);
        sl->addWidget(tc, 10);
        lay->addWidget(sw);
        connect(readBtn, &QPushButton::clicked, this, [this]
        {
            evt("home_stats_read_jump", m_hStRead ? "n=" + m_hStRead->text() : QString());
            if (m_statusC)
            {
                const int i = m_statusC->findData(QString::fromLatin1("finished"));
                if (i >= 0) m_statusC->setCurrentIndex(i);
            }
            showTab(1);
        });
        connect(totalBtn, &QPushButton::clicked, this, [this]
        {
            evt("home_stats_total_open", "");
            onStats();
        });
    }
    lay->addSpacing(8);

    /* 5 我的文件（圆形条目） */
    lay->addWidget(mkHeader(hr::l10n::s("我的书库", "My library"), &m_hSubFiles, &more, nullptr)); /* v1.0.0 */
    lay->addWidget(mkRow(&m_hFiles));
    connect(more, &QPushButton::clicked, this, [this] { evt("home_files_more", ""); showTab(2); });
    lay->addSpacing(8);

    /* 6 网上书库（圆形条目） */
    lay->addWidget(mkHeader(hr::l10n::s("网上书库", "Network"), &m_hSubSrv, &more, nullptr));
    lay->addWidget(mkRow(&m_hSrv));
    connect(more, &QPushButton::clicked, this, [this] { evt("home_net_more", ""); showTab(4); });
    lay->addStretch(1);

    /* FAB：继续阅读（右下；最近一本，无则去书库） */
    QHBoxLayout *fbl = new QHBoxLayout;
    fbl->setContentsMargins(0, 0, 12, 12); /* v0.95.0 W16：悬浮 FAB 与边缘留呼吸位 */
    fbl->addStretch(1);
    /* v0.77.0 V2：FAB 对齐安卓/Compose——56px 圆钮 + 开卷图标（无图标资源时退回文字胶囊） */
    m_homeFab = new QToolButton(m_homePage);
    m_homeFab->setCursor(Qt::PointingHandCursor);
    m_homeFab->setToolTip(hr::l10n::s("继续阅读", "Continue"));
    if (hr::Icons::have())
    {
        m_homeFab->setFixedSize(56, 56);
        m_homeFab->setIconSize(QSize(26, 26));
        m_homeFab->setIcon(hr::Icons::get(QString::fromLatin1("glyphicons_589_book_open"),
                                          QColor(0xFF, 0xFF, 0xFF), 26));
        m_homeFab->setStyleSheet(QString::fromLatin1(
            "QToolButton{background:#151515; border:none; border-radius:28px;}"
            "QToolButton:hover{background:#000000;}"));
    }
    else
    {
        m_homeFab->setText(hr::l10n::s("继续阅读", "Continue"));
        m_homeFab->setFixedHeight(44);
        m_homeFab->setMinimumWidth(120);
        m_homeFab->setStyleSheet(QString::fromLatin1(
            "QToolButton{background:#151515; color:white; border-radius:22px; font-size:%1px;"
            " padding:0 18px;} QToolButton:hover{background:#000000;}").arg(hr::Ui::fsPx(13)));
    }
    fbl->addWidget(m_homeFab);
    /* v0.95.0 W16：FAB 叠放滚动区右下角（同网格单元）——原独立一行占 56+间距，
     * 形成内容与底栏之间空白带；压缩后这块竖向空间还给首页（用户点名） */
    /* v0.99.0 W20#2：FAB 回到内容流右下独立行——悬浮叠放会随机遮挡视口右下的
     * “更多”按钮（W18 的 72px 留白只救了滚到底的场景，统计标题行在中间滚动位置
     * 仍被盖，用户只见齿轮不见“更多”）；独立行总高与留白方案相当且永不遮挡。 */
    hl->addWidget(sa, 1);
    lay->addLayout(fbl);
    connect(m_homeFab, &QToolButton::clicked, this, [this]
    {
        const QString last = homeLastBook();
        if (!last.isEmpty())
        {
            evt("home_fab", "path=" + last.left(80));
            openBookOrRemote(last);
        }
        else
        {
            evt("home_fab", "empty->library");
            showTab(1);
        }
    });
}

void LibraryWindow::refreshHome()
{
    const qint64 t0 = QDateTime::currentMSecsSinceEpoch(); /* v0.107.2：home_render ms */
    if (!m_hRecent || !m_hBm || !m_hFav || !m_hFiles || !m_hSrv) return;
    /* 数据一次取齐 */
    QHash<QString, QString> titles;
    for (const Db::MetaRow &r : Db::listLibrary())
        titles.insert(r.path, r.title.isEmpty() ? QFileInfo(r.path).fileName() : r.title);
    auto titleOf = [&titles](const QString &p)
    { return titles.value(p, QFileInfo(p).fileName()); };
    QList<Db::ProgressRow> rec = Db::allProgress();
    std::sort(rec.begin(), rec.end(), [](const Db::ProgressRow &a, const Db::ProgressRow &b)
              { return a.updatedAt > b.updatedAt; });
    QHash<QString, QPair<int, int>> prog;
    int readN = 0;
    for (const Db::ProgressRow &r : rec)
    {
        prog.insert(r.path, qMakePair(r.page, r.pageCount));
        if (r.pageCount > 0 && r.page + 1 >= r.pageCount) readN++;
    }
    const QList<QString> favs = Db::listFavorites();
    QList<BookmarkRow> bms = Db::allBookmarks();
    std::sort(bms.begin(), bms.end(), [](const BookmarkRow &a, const BookmarkRow &b)
              { return a.createdAt > b.createdAt; });
    const QList<Db::RemoteSrc> srcs = Db::listRemoteSrc();
    const QVector<hr::Stats::DayRow> days = hr::Stats::rows(400);
    const QString today = QDate::currentDate().toString(QString::fromLatin1("yyyy-MM-dd"));
    int totalMin = 0, totalPg = 0, todayMin = 0;
    for (const hr::Stats::DayRow &d : days)
    {
        totalMin += d.minutes;
        totalPg += d.pages;
        if (d.day == today) todayMin = d.minutes;
    }

    /* 封面瓦片按钮（最近/珍藏共用） */
    auto tile = [this, &titleOf](const QString &p, const char *ev) -> QToolButton *
    {
        const QString t = titleOf(p);
        QToolButton *tb = new QToolButton;
        tb->setToolButtonStyle(Qt::ToolButtonTextUnderIcon);
        /* W18#2：封面瓦片再放大 120×166→144×200（等比） */
        tb->setIcon(QIcon(coverTile(p, t, 144, 200)));
        tb->setIconSize(QSize(144, 200));
        tb->setFixedSize(158, 242);
        tb->setText(tb->fontMetrics().elidedText(t, Qt::ElideRight, 150));
        tb->setToolTip(t);
        tb->setCursor(Qt::PointingHandCursor);
        tb->setStyleSheet(QString::fromLatin1(
            "QToolButton{border:none; background:transparent; font-size:%1px;}"
            "QToolButton:hover{background:palette(alternate-base); border-radius:6px;}").arg(hr::Ui::fsPx(11)));
        const QString evn = QString::fromLatin1(ev);
        connect(tb, &QToolButton::clicked, this, [this, p, t, evn]
        {
            evt(evn, "name=" + t.left(60));
            openBookOrRemote(p);
        });
        return tb;
    };
    auto emptyLb = [](const QString &s) -> QLabel *
    {
        QLabel *l = new QLabel(s);
        l->setStyleSheet(QString::fromLatin1("color:palette(mid); font-size:%1px;").arg(hr::Ui::fsPx(12)));
        return l;
    };

    /* 1 最近阅读（本地且存在，≤12） */
    rowClear(m_hRecent);
    int nRecent = 0;
    for (const Db::ProgressRow &r : rec)
    {
        if (nRecent >= 12) break;
        if (!r.path.startsWith(QString::fromLatin1("remote://")) && !QFileInfo::exists(r.path))
            continue;
        m_hRecent->addWidget(tile(r.path, "home_recent_open"));
        nRecent++;
    }
    if (nRecent == 0)
        m_hRecent->addWidget(emptyLb(hr::l10n::s(
            "还没有开始阅读——从书库或我的文件打开一本书",
            "Nothing read yet — open a book from the library or My Files")));
    m_hRecent->addStretch(1);
    m_hSubRecent->setText(QString(hr::l10n::s("共 %1 本", "%1 books")).arg(nRecent));

    /* 2 书签笔记横卡（≤12；封面+书名+note/label+N%·第x页） */
    rowClear(m_hBm);
    int nBm = 0;
    for (const BookmarkRow &b : bms)
    {
        if (nBm >= 12) break;
        const QString t = titleOf(b.path);
        QPushButton *card = new QPushButton;
        card->setFlat(true);
        card->setFixedSize(288, 110); /* W18#2：书签卡再放大 250×96→288×110 */
        card->setCursor(Qt::PointingHandCursor);
        card->setStyleSheet(QString::fromLatin1(
            "QPushButton{background:palette(base); border:none; border-radius:8px;}"
            "QPushButton:hover{background:palette(alternate-base);}"));
        QHBoxLayout *cl = new QHBoxLayout(card);
        cl->setContentsMargins(8, 8, 8, 8);
        cl->setSpacing(8);
        QLabel *cv = new QLabel(card);
        cv->setFixedSize(64, 90); /* W18#2：书签卡封面再放大 54×76→64×90 */
        cv->setPixmap(coverTile(b.path, t, 64, 90));
        cv->setStyleSheet(QString::fromLatin1("background:transparent;"));
        cl->addWidget(cv);
        QVBoxLayout *tx = new QVBoxLayout;
        tx->setContentsMargins(0, 0, 0, 0);
        tx->setSpacing(2);
        QLabel *l1 = new QLabel(card->fontMetrics().elidedText(t, Qt::ElideRight, 196), card); /* W18#2：随卡再加宽 */
        l1->setStyleSheet(QString::fromLatin1(
            "font-size:%1px; font-weight:bold; background:transparent;").arg(hr::Ui::fsPx(12)));
        QString sub = b.note.trimmed();
        if (sub.isEmpty())
            sub = (b.label.trimmed().isEmpty() ? hr::l10n::s("书签", "Bookmark") : b.label) +
                  QString::fromLatin1("  ") +
                  QDateTime::fromMSecsSinceEpoch(b.createdAt)
                      .toString(QString::fromLatin1("MM-dd HH:mm"));
        QLabel *l2 = new QLabel(card->fontMetrics().elidedText(sub, Qt::ElideRight, 196), card); /* W18#2：按卡内实际宽省略 */
        l2->setWordWrap(true);
        l2->setStyleSheet(QString::fromLatin1(
            "color:palette(mid); font-size:%1px; background:transparent;").arg(hr::Ui::fsPx(11)));
        l2->setMaximumHeight(30);
        int pct = 0;
        const auto pit = prog.constFind(b.path);
        if (pit != prog.constEnd() && pit->second > 0)
            pct = (pit->first + 1) * 100 / pit->second;
        QLabel *l3 = new QLabel(
            QString(hr::l10n::s("%1% \xC2\xB7 \xE7\xAC\xAC %2 \xE9\xA1\xB5",
                                "%1% \xC2\xB7 p%2")).arg(pct).arg(b.page + 1), card);
        l3->setStyleSheet(QString::fromLatin1(
            "color:palette(mid); font-size:%1px; background:transparent;").arg(hr::Ui::fsPx(10)));
        tx->addWidget(l1);
        tx->addWidget(l2);
        tx->addWidget(l3);
        cl->addLayout(tx, 1);
        const QString bp = b.path;
        const int bpg = b.page;
        connect(card, &QPushButton::clicked, this, [this, bp, bpg]
        {
            evt("home_bm_jump", QString("page=%1").arg(bpg + 1));
            emit openAtRequested(bp, bpg);
        });
        m_hBm->addWidget(card);
        nBm++;
    }
    if (nBm == 0)
        m_hBm->addWidget(emptyLb(hr::l10n::s("还没有书签——阅读器中点「书签」即可添加",
                                             "No bookmarks yet — add one in the reader")));
    m_hBm->addStretch(1);
    m_hSubBm->setText(QString(hr::l10n::s("共 %1 条", "%1 items")).arg(bms.size()));

    /* 3 我的珍藏（≤12） */
    rowClear(m_hFav);
    int nFav = 0;
    for (const QString &p : favs)
    {
        if (nFav >= 12) break;
        if (!p.startsWith(QString::fromLatin1("remote://")) && !QFileInfo::exists(p)) continue;
        m_hFav->addWidget(tile(p, "home_fav_open"));
        nFav++;
    }
    if (nFav == 0)
        m_hFav->addWidget(emptyLb(hr::l10n::s("书库封面右上角 ☆ 点亮即可收藏",
                                              "Tap the star on a cover to favorite")));
    m_hFav->addStretch(1);
    m_hSubFav->setText(QString(hr::l10n::s("共 %1 本", "%1 books")).arg(favs.size()));

    /* 4 阅读统计数字 */
    const int booksN = titles.size();
    m_hStBooks->setText(QString::number(booksN));
    m_hStRead->setText(QString::number(readN));
    m_hStToday->setText(fmtDurMin(todayMin));
    m_hStSpeed->setText(QString::number(
        totalMin > 0 ? double(totalPg) / double(totalMin) : 0.0, 'f', 1));
    m_hStTotal->setText(fmtDurMin(totalMin));
    m_hSubStats->setText(QString(hr::l10n::s("%1 本在册", "%1 books")).arg(booksN));

    /* 5 我的文件：书库目录（≤2）+ 主目录/下载/文档/桌面 */
    rowClear(m_hFiles);
    auto circle = [this](QHBoxLayout *row, const QString &name, const QString &glyph,
                         const QColor &bg, const QString &iconFile, int iconSz, int btnSz,
                         int elideW, std::function<void()> onClick)
    {
        QToolButton *c = new QToolButton;
        c->setToolButtonStyle(Qt::ToolButtonTextUnderIcon);
        c->setIcon(iconFile.isEmpty() ? circleIcon(glyph, bg, QColor(0x12, 0x5A, 0x9C))
                                      : circleIconFile(iconFile, bg));
        c->setIconSize(QSize(iconSz, iconSz)); /* W18#2：圆钮尺寸参数化（我的文件大号） */
        c->setFixedSize(btnSz, btnSz);
        c->setText(c->fontMetrics().elidedText(name, Qt::ElideRight, elideW));
        c->setToolTip(name);
        c->setCursor(Qt::PointingHandCursor);
        c->setStyleSheet(QString::fromLatin1(
            "QToolButton{border:none; background:transparent; font-size:%1px;}"
            "QToolButton:hover{background:palette(alternate-base); border-radius:6px;}").arg(hr::Ui::fsPx(10)));
        connect(c, &QToolButton::clicked, this, [onClick] { onClick(); });
        row->addWidget(c);
    };
    int nFiles = 0;
    const QStringList sdirs = Db::scanDirs();
    for (int i = 0; i < sdirs.size() && i < 2; i++)
    {
        const QString d = sdirs.at(i);
        circle(m_hFiles, hr::l10n::s("书库 · ", "Lib \xC2\xB7 ") + QDir(d).dirName(),
               hr::l10n::s("库", "Lib"), QColor(0xE3, 0xF2, 0xFD),
               QString::fromLatin1("glyphicons_422_book_library"), 80, 116, 104, [this, d]
               {
                   evt("home_files", "lib=" + QDir(d).dirName().left(40));
                   evt("hf_step", "1-click"); /* v0.99.0 W20：点击路径逐步定位，崩溃看最后一条 */
                   if (QFileInfo(d).isDir())
                   {
                       evt("hf_step", "2-isdir-ok");
                       fillLocal(d);
                       evt("hf_step", "4-filllocal-done");
                       if (m_loSw) m_loSw->setCurrentIndex(1);
                   }
                   else
                   {
                       evt("hf_step", "2-isdir-false");
                   }
                   evt("hf_step", "5-showtab-begin");
                   showTab(2);
                   evt("hf_step", "7-showtab-done");
               });
        nFiles++;
    }
    /* v0.96.0 W17#6：首页“我的文件”不再写死 主目录/下载/文档/桌面——
     * 只显示“我的文件”页里添加的本地书库目录；无则给引导文案 */
    if (nFiles == 0)
        m_hFiles->addWidget(emptyLb(hr::l10n::s("在“我的文件”页添加本地目录",
                                                "Add local folders in My Files")));
    m_hFiles->addStretch(1);
    m_hSubFiles->setText(hr::l10n::s("本地浏览", "Local"));

    /* 6 网上书库：服务器圆钮（协议字母） */
    rowClear(m_hSrv);
    for (const Db::RemoteSrc &s : srcs)
    {
        const QString pr = s.protocol.toLower();
        const QString ic = /* v0.76.0 V1：协议图标 */
            pr == QString::fromLatin1("webdav") ? QString::fromLatin1("glyphicons_544_cloud")
            : pr == QString::fromLatin1("smb")  ? QString::fromLatin1("glyphicons_522_monitors")
            : pr == QString::fromLatin1("sftp") ? QString::fromLatin1("glyphicons_217_lock")
                                                : QString::fromLatin1("glyphicons_417_globe");
        const QString g = pr == QString::fromLatin1("webdav") ? QString::fromLatin1("W")
                          : pr == QString::fromLatin1("smb")  ? QString::fromLatin1("S")
                          : pr == QString::fromLatin1("sftp") ? QString::fromLatin1("F")
                          : pr == QString::fromLatin1("opds") ? QString::fromLatin1("O")
                                                              : QString::fromLatin1("N");
        const QString nm = s.name.isEmpty() ? s.host : s.name;
        circle(m_hSrv, nm, g, QColor(0xFF, 0xF3, 0xE0), ic, 64, 96, 88, [this, nm, pr]
               {
                   evt("home_server", pr + " " + nm.left(40));
                   showTab(4);
               });
    }
    if (srcs.isEmpty())
        m_hSrv->addWidget(emptyLb(hr::l10n::s("添加 WebDAV/SMB/SFTP 服务器，家里的书直接读",
                                              "Add a WebDAV/SMB/SFTP server to read your books")));
    m_hSrv->addStretch(1);
    m_hSubSrv->setText(srcs.isEmpty() ? hr::l10n::s("未配置", "Not set")
                                      : QString(hr::l10n::s("%1 个服务器", "%1 servers"))
                                            .arg(srcs.size()));

    evt("home_render", QString("recent=%1 bm=%2 fav=%3 srv=%4 files=%5 totalmin=%6 books=%7 ms=%8")
                           .arg(nRecent).arg(nBm).arg(nFav).arg(srcs.size()).arg(nFiles)
                           .arg(totalMin).arg(booksN)
                           .arg(QDateTime::currentMSecsSinceEpoch() - t0));
}

/* 统计齿轮：清空两击确认（缺陷#3） */
void LibraryWindow::onClearStatsDialog()
{
    evt("stats_gear", "");
    QDialog dg(this);
    dg.setWindowTitle(hr::l10n::s("清空阅读统计", "Clear statistics"));
    dg.resize(420, 190);
    QVBoxLayout *vl = new QVBoxLayout(&dg);
    QLabel *t1 = new QLabel(hr::l10n::s("清空阅读统计", "Clear statistics"), &dg);
    QFont f = t1->font();
    f.setBold(true);
    t1->setFont(f);
    vl->addWidget(t1);
    QLabel *t2 = new QLabel(
        hr::l10n::s("将删除全部阅读时长与阅读页数统计（各书进度、书签、珍藏不受影响）。此操作不可恢复。",
                    "Deletes all reading time and page statistics (progress, bookmarks and "
                    "favorites are kept). This cannot be undone."),
        &dg);
    t2->setWordWrap(true);
    vl->addWidget(t2);
    QLabel *warn = new QLabel(&dg);
    warn->setStyleSheet(QString::fromLatin1("color:#d32f2f; font-size:%1px;").arg(hr::Ui::fsPx(11)));
    warn->hide();
    vl->addWidget(warn);
    QDialogButtonBox *bb = new QDialogButtonBox(QDialogButtonBox::Cancel, &dg);
    bb->button(QDialogButtonBox::Cancel)->setText(hr::l10n::s("取消", "Cancel"));
    QPushButton *clr =
        bb->addButton(hr::l10n::s("清空统计", "Clear stats"), QDialogButtonBox::ActionRole);
    vl->addWidget(bb);
    connect(bb, &QDialogButtonBox::rejected, &dg, &QDialog::reject);
    connect(clr, &QPushButton::clicked, &dg, [this, &dg, clr, warn]
    {
        if (!clr->property("armed").toBool())
        {
            clr->setProperty("armed", true);
            clr->setText(hr::l10n::s("确认清空", "Confirm clear"));
            clr->setStyleSheet(QString::fromLatin1("color:#d32f2f; font-weight:bold;"));
            warn->setText(hr::l10n::s("请再次点击「确认清空」执行。",
                                      "Click the confirm button once more to proceed."));
            warn->show();
            evt("stats_clear_arm", "");
            return;
        }
        hr::Stats::clearAll();
        evt("stats_cleared", "");
        statusBar()->showMessage(hr::l10n::s("阅读统计已清空", "Statistics cleared"), 5000);
        refreshHome();
        dg.accept();
    });
    dg.exec();
}

/* U7 书签笔记聚合页（书名+位置+标签+笔记；双击 openAt，右键删除/打开） */
void LibraryWindow::buildBookmarksPage()
{
    m_bmPage = new QWidget;
    QVBoxLayout *vl = new QVBoxLayout(m_bmPage);
    vl->setContentsMargins(10, 8, 10, 8);
    vl->setSpacing(6);
    QLabel *tt = new QLabel(hr::l10n::s("书签笔记", "Notes"), m_bmPage);
    QFont f = tt->font();
    f.setBold(true);
    f.setPointSize(f.pointSize() + 2);
    tt->setFont(f);
    vl->addWidget(tt);
    m_bmTree = new QTreeWidget(m_bmPage);
    m_bmTree->setColumnCount(4);
    m_bmTree->setHeaderLabels(QStringList() << hr::l10n::s("书名", "Book")
                                            << hr::l10n::s("位置", "Position")
                                            << hr::l10n::s("标签", "Label")
                                            << hr::l10n::s("笔记", "Note"));
    m_bmTree->setRootIsDecorated(false);
    m_bmTree->setAlternatingRowColors(true);
    m_bmTree->setSelectionMode(QAbstractItemView::ExtendedSelection);
    m_bmTree->header()->setSectionResizeMode(0, QHeaderView::Stretch);
    m_bmTree->header()->setSectionResizeMode(1, QHeaderView::ResizeToContents);
    m_bmTree->header()->setSectionResizeMode(2, QHeaderView::ResizeToContents);
    m_bmTree->header()->setSectionResizeMode(3, QHeaderView::Stretch);
    vl->addWidget(m_bmTree, 1);
    vl->addWidget(new QLabel(hr::l10n::s("双击跳转到书签；右键可删除。", "Double-click to jump; right-click to delete."), m_bmPage));
    connect(m_bmTree, &QTreeWidget::itemDoubleClicked, this, [this](QTreeWidgetItem *it, int)
    {
        const QString p = it->data(0, Qt::UserRole).toString();
        const int pg = it->data(0, Qt::UserRole + 1).toInt();
        if (!p.isEmpty())
        {
            evt("bm_page_open", QString("page=%1").arg(pg + 1));
            emit openAtRequested(p, pg);
        }
    });
    m_bmTree->setContextMenuPolicy(Qt::CustomContextMenu);
    connect(m_bmTree, &QWidget::customContextMenuRequested, this, [this](const QPoint &pos)
    {
        QTreeWidgetItem *it = m_bmTree->itemAt(pos);
        if (!it) return;
        QMenu menu(this);
        QAction *op = menu.addAction(hr::l10n::s("打开", "Open"));
        QAction *del = menu.addAction(hr::l10n::s("删除书签", "Delete bookmark"));
        QAction *act = menu.exec(m_bmTree->viewport()->mapToGlobal(pos));
        const QString p = it->data(0, Qt::UserRole).toString();
        const int pg = it->data(0, Qt::UserRole + 1).toInt();
        const long long id = it->data(0, Qt::UserRole + 2).toLongLong();
        if (act == del && id > 0)
        {
            Db::deleteBookmark(id);
            evt("bm_page_del", QString("id=%1").arg(id));
            refreshBookmarksPage();
            refreshHome();
        }
        else if (act == op && !p.isEmpty())
            emit openAtRequested(p, pg);
    });
    refreshBookmarksPage();
}

void LibraryWindow::refreshBookmarksPage()
{
    if (!m_bmTree) return;
    QHash<QString, QString> titles;
    QHash<QString, QPair<int, int>> prog;
    for (const Db::MetaRow &r : Db::listLibrary())
        titles.insert(r.path, r.title.isEmpty() ? QFileInfo(r.path).fileName() : r.title);
    for (const Db::ProgressRow &r : Db::allProgress())
        prog.insert(r.path, qMakePair(r.page, r.pageCount));
    QList<BookmarkRow> bms = Db::allBookmarks();
    std::sort(bms.begin(), bms.end(), [](const BookmarkRow &a, const BookmarkRow &b)
              { return a.createdAt > b.createdAt; });
    m_bmTree->clear();
    for (const BookmarkRow &b : bms)
    {
        const QString t = titles.value(b.path, QFileInfo(b.path).fileName());
        int pct = 0;
        const auto pit = prog.constFind(b.path);
        if (pit != prog.constEnd() && pit->second > 0)
            pct = (pit->first + 1) * 100 / pit->second;
        QTreeWidgetItem *it = new QTreeWidgetItem(
            QStringList() << t
                          << QString(hr::l10n::s("%1% \xC2\xB7 \xE7\xAC\xAC %2 \xE9\xA1\xB5",
                                                 "%1% \xC2\xB7 p%2")).arg(pct).arg(b.page + 1)
                          << b.label << b.note);
        it->setData(0, Qt::UserRole, b.path);
        it->setData(0, Qt::UserRole + 1, b.page);
        it->setData(0, Qt::UserRole + 2, (qlonglong)b.id);
        m_bmTree->addTopLevelItem(it);
    }
    evt("bm_page_render", QString("n=%1").arg(bms.size()));
}

/* U7 珍藏页（封面网格；双击打开，右键取消收藏） */
void LibraryWindow::buildFavPage()
{
    m_favPage = new QWidget;
    QVBoxLayout *vl = new QVBoxLayout(m_favPage);
    vl->setContentsMargins(10, 8, 10, 8);
    vl->setSpacing(6);
    QLabel *tt = new QLabel(hr::l10n::s("我的珍藏", "Favorites"), m_favPage);
    QFont f = tt->font();
    f.setBold(true);
    f.setPointSize(f.pointSize() + 2);
    tt->setFont(f);
    vl->addWidget(tt);
    m_favList = new QListWidget(m_favPage);
    m_favList->setViewMode(QListView::IconMode);
    m_favList->setResizeMode(QListWidget::Adjust);
    m_favList->setMovement(QListWidget::Static);
    m_favList->setGridSize(QSize(128, 190));
    m_favList->setIconSize(QSize(96, 133));
    m_favList->setSelectionMode(QAbstractItemView::ExtendedSelection);
    m_favList->setContextMenuPolicy(Qt::CustomContextMenu);
    vl->addWidget(m_favList, 1);
    vl->addWidget(new QLabel(hr::l10n::s("双击打开；右键可取消收藏。", "Double-click to open; right-click to unfavorite."), m_favPage));
    connect(m_favList, &QListWidget::itemClicked, this, [this](QListWidgetItem *it)
    {
        if (it) preOpenBook(it->data(Qt::UserRole).toString());
    }); /* v0.104.0（P1-6）：预开 */
    connect(m_favList, &QListWidget::itemDoubleClicked, this, [this](QListWidgetItem *it)
    {
        const QString p = it->data(Qt::UserRole).toString();
        if (!p.isEmpty())
        {
            evt("fav_page_open", "name=" + it->text().left(60));
            openBookOrRemote(p);
        }
    });
    connect(m_favList, &QWidget::customContextMenuRequested, this, [this](const QPoint &pos)
    {
        QListWidgetItem *it = m_favList->itemAt(pos);
        if (!it) return;
        QMenu menu(this);
        QAction *un = menu.addAction(hr::l10n::s("取消收藏", "Unfavorite"));
        QAction *op = menu.addAction(hr::l10n::s("打开", "Open"));
        QAction *act = menu.exec(m_favList->viewport()->mapToGlobal(pos));
        const QString p = it->data(Qt::UserRole).toString();
        if (act == un && !p.isEmpty())
        {
            Db::setFavorite(p, false);
            evt("fav_page_unfav", "path=" + p.left(60));
            refreshFavPage();
            refreshHome();
        }
        else if (act == op && !p.isEmpty())
            openBookOrRemote(p);
    });
    refreshFavPage();
}

void LibraryWindow::refreshFavPage()
{
    if (!m_favList) return;
    QHash<QString, QString> titles;
    for (const Db::MetaRow &r : Db::listLibrary())
        titles.insert(r.path, r.title.isEmpty() ? QFileInfo(r.path).fileName() : r.title);
    m_favList->clear();
    int n = 0;
    for (const QString &p : Db::listFavorites())
    {
        const QString t = titles.value(p, QFileInfo(p).fileName());
        QListWidgetItem *it = new QListWidgetItem(QIcon(coverTile(p, t, 96, 133)), t, m_favList);
        it->setData(Qt::UserRole, p);
        it->setTextAlignment(Qt::AlignHCenter);
        n++;
    }
    evt("fav_page_render", QString("n=%1").arg(n));
}

/* ---------- U8 全局收尾 ---------- */

/* 当前配置名（data-dir.txt 基名；无=default） */
static QString currentProfileName()
{
    QFile f(QCoreApplication::applicationDirPath() + QString::fromLatin1("/data-dir.txt"));
    if (f.open(QIODevice::ReadOnly | QIODevice::Text))
    {
        const QString line = QString::fromUtf8(f.readAll()).trimmed();
        if (!line.isEmpty())
            return QFileInfo(line).fileName();
    }
    return QString::fromLatin1("default");
}

void LibraryWindow::applyBlueLight()
{
    QSettings st(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
    const int lvl = st.value("ui/blueLight", 0).toInt();
    if (lvl <= 0)
    {
        if (m_warm)
        {
            m_warm->deleteLater();
            m_warm = nullptr;
        }
        return;
    }
    if (!m_warm)
    {
        QLabel *w = new QLabel(this);
        w->setAttribute(Qt::WA_TransparentForMouseEvents);
        w->setStyleSheet(QString::fromLatin1("background: rgba(255,158,74,%1);")
                             .arg(lvl >= 2 ? 60 : 36));
        m_warm = w;
        if (!property("hr_warm_filter").toBool())
        {
            installEventFilter(this);
            setProperty("hr_warm_filter", true);
        }
    }
    m_warm->setGeometry(0, 0, width(), height());
    m_warm->raise();
    evt("blue_on", QString("lvl=%1").arg(lvl));
}

bool LibraryWindow::eventFilter(QObject *obj, QEvent *ev)
{
    if (obj == this && ev->type() == QEvent::Resize && m_warm)
        m_warm->setGeometry(0, 0, width(), height());
    return QMainWindow::eventFilter(obj, ev);
}

/* 软件说明（对齐安卓 about_section：版本头条色底 + 描述 + Pro 行 + 各行 label150+值右 + 链接行） */
void LibraryWindow::showAboutDialog()
{
    QDialog dg(this);
    dg.setWindowTitle(hr::l10n::s("软件说明", "About"));
    dg.resize(520, 540);
    QVBoxLayout *vl = new QVBoxLayout(&dg);
    QLabel *banner =
        new QLabel(QString::fromLatin1("HowRead: v%1").arg(appVersion()), &dg);
    banner->setStyleSheet(QString::fromLatin1(
        "background:palette(link); color:white; font-weight:bold; padding:6px 10px;"
        " border-radius:4px;"));
    vl->addWidget(banner);
    vl->addWidget(new QLabel(hr::l10n::s("一个专注于个人阅读体验的开源电子书阅读软件",
                                         "An open-source ebook reader focused on personal "
                                         "reading experience"),
                             &dg));
    vl->addSpacing(6);
    auto row = [&vl, &dg, this](const QString &label, const QString &value,
                                const QString &url, bool accent, bool bold)
    {
        QWidget *rw = new QWidget(&dg);
        QHBoxLayout *h = new QHBoxLayout(rw);
        h->setContentsMargins(0, 0, 0, 0);
        QLabel *la = new QLabel(label, rw);
        la->setFixedWidth(170);
        la->setStyleSheet(QString::fromLatin1("color:palette(mid);"));
        QLabel *va = new QLabel(rw);
        QFont f = va->font();
        if (bold) f.setBold(true);
        va->setFont(f);
        if (!url.isEmpty())
        {
            va->setText(QString::fromLatin1("<a href=\"%1\">%2</a>")
                            .arg(url, value.toHtmlEscaped()));
            va->setTextFormat(Qt::RichText);
            connect(va, &QLabel::linkActivated, this, [url](const QString &)
            {
                QDesktopServices::openUrl(QUrl(url));
                evt("about_link", url.left(80));
            });
        }
        else
            va->setText(value);
        if (accent)
            va->setStyleSheet(QString::fromLatin1("color:palette(link);"));
        h->addWidget(la);
        h->addWidget(va, 1);
        vl->addWidget(rw);
    };
    row(hr::l10n::s("高级功能", "Pro features"),
        hr::l10n::s("桌面版：全部功能已解锁", "Desktop: all features unlocked"), QString(),
        true, false);
    QLabel *noteLb = new QLabel(
        hr::l10n::s("解锁方式：本地 key · 购买时间： · 订单号后四位：",
                    "Unlock: local key · Purchased at: · Order tail:"),
        &dg);
    noteLb->setStyleSheet(QString::fromLatin1("color:palette(mid); font-size:%1px;").arg(hr::Ui::fsPx(10)));
    vl->addWidget(noteLb);
    vl->addSpacing(6);
    row(hr::l10n::s("开发者", "Developer"), QString::fromLatin1("LeeStudio"), QString(),
        false, true);
    row(hr::l10n::s("更新日志", "What is new"),
        QString(hr::l10n::s("HowRead（好好读）v%1", "HowRead v%1")).arg(appVersion()),
        QString(), false, false);
    row(hr::l10n::s("隐私政策", "Privacy policy"),
        hr::l10n::s("HowRead 隐私政策", "HowRead privacy policy"),
        QString::fromLatin1("https://github.com/380121850/howread"), false, false);
    row(hr::l10n::s("开放源代码许可", "Open source licence (GNU GPL v3.0)"),
        hr::l10n::s("第三方库许可", "Licenses for libraries"),
        QString::fromLatin1("https://github.com/380121850/howread"), false, false);
    row(hr::l10n::s("支持和问题反馈", "Support and feedback"),
        hr::l10n::s("HowRead 问题反馈", "HowRead issues"),
        QString::fromLatin1("https://github.com/380121850/howread/issues"), false, false);
    row(hr::l10n::s("邮箱", "Email"), QString::fromLatin1("leestudio_support"),
        QString::fromLatin1("mailto:leestudio_support@163.com"), false, false);
    row(hr::l10n::s("官网", "Website"), QString::fromLatin1("HowRead"),
        QString::fromLatin1("https://howread.app"), false, false);
    QLabel *fork = new QLabel(hr::l10n::s("This is a fork of Librera Reader",
                                          "This is a fork of Librera Reader"),
                              &dg);
    fork->setStyleSheet(QString::fromLatin1("color:palette(mid); font-size:%1px;").arg(hr::Ui::fsPx(10)));
    vl->addWidget(fork);
    vl->addStretch(1);
    QDialogButtonBox *bb = new QDialogButtonBox(QDialogButtonBox::Ok, &dg);
    bb->button(QDialogButtonBox::Ok)->setText(hr::l10n::s("确定", "OK"));
    vl->addWidget(bb);
    connect(bb, &QDialogButtonBox::accepted, &dg, &QDialog::accept);
    evt("about_open", QString("v=%1").arg(appVersion()));
    dg.exec();
}

/* 配置文件管理（对齐 AppProfiles：profiles/<名> 目录 + data-dir.txt 切换，重启生效） */
void LibraryWindow::onProfilesDialog()
{
    const QString root = QCoreApplication::applicationDirPath();
    QDialog dg(this);
    dg.setWindowTitle(hr::l10n::s("配置文件", "Profiles"));
    dg.resize(440, 320);
    QVBoxLayout *vl = new QVBoxLayout(&dg);
    QLabel *cur = new QLabel(
        QString(hr::l10n::s("当前配置：%1（便携）", "Profile: %1 (portable)"))
            .arg(currentProfileName()),
        &dg);
    vl->addWidget(cur);
    QListWidget *ls = new QListWidget(&dg);
    vl->addWidget(ls, 1);
    const QDir prDir(root + QString::fromLatin1("/profiles"));
    int n = 0;
    for (const QString &d :
         prDir.entryList(QDir::Dirs | QDir::NoDotAndDotDot, QDir::Name))
    {
        QListWidgetItem *it = new QListWidgetItem(d, ls);
        it->setData(Qt::UserRole, d);
        n++;
    }
    QHBoxLayout *nh = new QHBoxLayout;
    QLineEdit *nm = new QLineEdit(&dg);
    nm->setPlaceholderText(hr::l10n::s("新配置名称（字母/数字/-/_）",
                                       "New profile name (letters/digits/-/_)"));
    QPushButton *mk = new QPushButton(hr::l10n::s("新建并切换", "Create & switch"), &dg);
    QPushButton *use = new QPushButton(hr::l10n::s("切换到所选", "Switch to selected"), &dg);
    QPushButton *def = new QPushButton(hr::l10n::s("恢复 default", "Back to default"), &dg);
    nh->addWidget(nm, 1);
    nh->addWidget(mk);
    vl->addLayout(nh);
    QHBoxLayout *uh = new QHBoxLayout;
    uh->addWidget(use);
    uh->addWidget(def);
    uh->addStretch(1);
    vl->addLayout(uh);
    QLabel *hint = new QLabel(
        hr::l10n::s("切换后需重启应用生效（写入 data-dir.txt）。",
                    "Takes effect after restart (writes data-dir.txt)."),
        &dg);
    hint->setStyleSheet(QString::fromLatin1("color:palette(mid); font-size:%1px;").arg(hr::Ui::fsPx(11)));
    vl->addWidget(hint);
    auto writeDirFile = [root](const QString &rel)
    {
        QFile f(root + QString::fromLatin1("/data-dir.txt"));
        if (rel.isEmpty())
            f.remove();
        else if (f.open(QIODevice::WriteOnly | QIODevice::Truncate | QIODevice::Text))
            f.write(rel.toUtf8());
    };
    auto doSwitch = [this, &dg, &writeDirFile, &root](const QString &name)
    {
        writeDirFile(QString::fromLatin1("profiles/") + name);
        evt("prof_switch", "name=" + name.left(40));
        statusBar()->showMessage(hr::l10n::s("已切换配置，重启应用生效。",
                                             "Profile switched; restart to apply."),
                                 6000);
        dg.accept();
    };
    connect(use, &QPushButton::clicked, &dg, [&dg, &ls, &doSwitch]
    {
        QListWidgetItem *it = ls->currentItem();
        if (it) doSwitch(it->data(Qt::UserRole).toString());
    });
    connect(ls, &QListWidget::itemDoubleClicked, &dg,
            [&doSwitch](QListWidgetItem *it) { if (it) doSwitch(it->data(Qt::UserRole).toString()); });
    connect(def, &QPushButton::clicked, &dg, [this, &dg, &writeDirFile]
    {
        writeDirFile(QString());
        evt("prof_switch", "name=default");
        statusBar()->showMessage(hr::l10n::s("已恢复 default 配置，重启应用生效。",
                                             "Back to default; restart to apply."),
                                 6000);
        dg.accept();
    });
    connect(mk, &QPushButton::clicked, &dg, [&dg, &nm, &root, &prDir, &doSwitch, &ls]
    {
        const QString name = nm->text().trimmed();
        QRegularExpression re(QString::fromLatin1("^[A-Za-z0-9_-]{1,32}$"));
        if (!re.match(name).hasMatch())
        {
            QMessageBox::warning(&dg, hr::l10n::s("配置文件", "Profiles"),
                                 hr::l10n::s("名称只能用字母/数字/-/_，长度 1-32。",
                                             "Name: letters/digits/-/_, length 1-32."));
            return;
        }
        if (!prDir.mkpath(name))
        {
            QMessageBox::warning(&dg, hr::l10n::s("配置文件", "Profiles"),
                                 hr::l10n::s("创建失败。", "Create failed."));
            return;
        }
        evt("prof_create", "name=" + name.left(40));
        QListWidgetItem *it = new QListWidgetItem(name, ls);
        it->setData(Qt::UserRole, name);
        ls->setCurrentItem(it);
    });
    evt("prof_list", QString("n=%1 cur=%2").arg(n).arg(currentProfileName().left(20)));
    dg.exec();
}

/* 密码锁设置/清除（SHA-256 存 security/appLockPassword；空=清除） */
void LibraryWindow::onLockPasswordDialog()
{
    QDialog dg(this);
    dg.setWindowTitle(hr::l10n::s("应用密码锁", "App password lock"));
    dg.resize(380, 170);
    QVBoxLayout *vl = new QVBoxLayout(&dg);
    vl->addWidget(new QLabel(hr::l10n::s("设置解锁密码（留空保存=清除密码锁）：",
                                         "Set unlock password (save empty to clear):"),
                             &dg));
    QLineEdit *e1 = new QLineEdit(&dg);
    e1->setEchoMode(QLineEdit::Password);
    QLineEdit *e2 = new QLineEdit(&dg);
    e2->setEchoMode(QLineEdit::Password);
    e2->setPlaceholderText(hr::l10n::s("再输一遍确认", "Repeat to confirm"));
    vl->addWidget(e1);
    vl->addWidget(e2);
    QDialogButtonBox *bb =
        new QDialogButtonBox(QDialogButtonBox::Ok | QDialogButtonBox::Cancel, &dg);
    bb->button(QDialogButtonBox::Ok)->setText(hr::l10n::s("保存", "Save"));
    bb->button(QDialogButtonBox::Cancel)->setText(hr::l10n::s("取消", "Cancel"));
    vl->addWidget(bb);
    connect(bb, &QDialogButtonBox::accepted, &dg, &QDialog::accept);
    connect(bb, &QDialogButtonBox::rejected, &dg, &QDialog::reject);
    if (dg.exec() != QDialog::Accepted) return;
    QSettings stL(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
    const QString pw1 = e1->text();
    if (pw1.isEmpty())
    {
        stL.remove("security/appLockPassword");
        evt("pref_applock", "cleared");
        statusBar()->showMessage(hr::l10n::s("密码锁已清除", "Password lock cleared"), 5000);
    }
    else if (pw1 != e2->text())
    {
        QMessageBox::warning(this, hr::l10n::s("应用密码锁", "App password lock"),
                             hr::l10n::s("两次输入不一致，未保存。",
                                         "The two entries differ; not saved."));
        evt("pref_applock", "mismatch");
    }
    else
    {
        const QString hex = QString::fromLatin1(
            QCryptographicHash::hash(pw1.toUtf8(), QCryptographicHash::Sha256).toHex());
        stL.setValue("security/appLockPassword", hex);
        evt("pref_applock", "set");
        statusBar()->showMessage(hr::l10n::s("密码锁已设置，下次启动生效。",
                                             "Password lock set; active on next launch."),
                                 5000);
    }
}

} // namespace hr
