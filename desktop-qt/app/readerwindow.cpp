#include "readerwindow.h"
#include "hr_bridge.h"
#include "hrdb.h"
#include "hranchor.h" /* v1.1.0：章节锚折算 */
#include <functional>
#include "hrsync.h"
#include "hrbcache.h" /* v1.9.0（P1-6/P1-7）：CacheProgressSource */
#include "hraccels.h" /* v1.9.0（P1-5）：accelPathFor/accelCleanup 去 static 后声明 */
#include "hrpaths.h"
#include "readermark.h"
#include "readerm4.h"
#include "hrai.h"
#include "hrtts.h"
#include <QInputDialog> /* v0.83.0 W2：连读定时/速度间隔输入 */
#include "hrstats.h"
#include "readerjson.h"
#include "hrcvt.h"
#include "hrl10n.h"
#include "hrdict.h"

#include <QApplication>
#include <QMutex>
#include <QMutexLocker> /* P2：纹理缓存 */
#include <QClipboard>
#include <QContextMenuEvent> /* v1.7.0：右键弹阅读菜单 */
#include <QMenu>
#include <QPainter>
#include <QDateTime>
#include <QElapsedTimer> /* P2：accel 计时 */
#include <QJsonDocument> /* v0.107.0（P2-6）：outline 缓存 */
#include <QJsonArray>
#include <QJsonObject>
#include <QCryptographicHash>
#include <QCheckBox>
#include <QCloseEvent>
#include <QFile>
#include <QComboBox>
#include <QDialog>
#include <QDialogButtonBox>
#include <QDesktopServices>
#include <QDockWidget>
#include <QLineEdit>
#include <QTabWidget>
#include <QUrl>
#include <QUrlQuery>
#include <QDoubleSpinBox>
#include <QFormLayout>
#include <QFileDialog>
#include <QFileInfo>
#include <QHBoxLayout>
#include <QKeyEvent>
#include <QLabel>
#include <QMessageBox>
#include <QRegularExpression>
#include <QSpinBox>
#include <QTextBrowser>
#include <QListWidget>
#include <QMouseEvent>
#include <QPointer>
#include <QPushButton>
#include <QToolButton>
#include "hricons.h" /* v0.77.0 V2：顶栏/底栏图标钮 */
#include <QCryptographicHash>
#include <QScrollBar>
#include <QScrollArea>
#include <QSettings>
#include <QSlider>
#include <QStatusBar>
#include <QThreadPool>
#include <QPalette> /* v1.8.0（P0-2）：占位纸底 */
#include <algorithm> /* v1.8.0（P0-1）：待渲队列排序 */
#include <QTreeWidget>
#include <QVBoxLayout>

#include <cmath>

namespace hr
{

/* 选区覆盖层：视口子控件，鼠标穿透，绘制当前划词矩形 */
class SelOverlay : public QWidget
{
public:
    QVector<QRectF> rects;
    explicit SelOverlay(QWidget *parent) : QWidget(parent)
    {
        setAttribute(Qt::WA_TransparentForMouseEvents);
        setAttribute(Qt::WA_NoSystemBackground);
    }
protected:
    void paintEvent(QPaintEvent *) override
    {
        QPainter p(this);
        p.setPen(Qt::NoPen);
        p.setBrush(QColor(255, 210, 0, 90));
        for (const QRectF &r : rects) p.drawRoundRect(r, 3, 3);
    }
};


const Theme kThemes[5] = {
    {"day", "白", 0xFFFFFF, 0x000000, false},
    {"milk", "米黄", 0xF5EFE0, 0x3A3A3A, false},
    {"parchment", "羊皮", 0xF5E6C8, 0x5B4636, false},
    {"gray", "深灰", 0x1E1E1E, 0xC8C8C8, true},
    {"night", "夜间", 0x000000, 0x8CFFB5, true},
};

ReaderWindow::ReaderWindow(QWidget *parent) : QMainWindow(parent)
{
    setWindowTitle("HowRead Qt");
    resize(1000, 1320);

    /* U3a 顶栏：书名 + 精简按钮 + 更多菜单（原按钮对象/connect 全保留，菜单转发） */
    m_lbBook = new QLabel(hr::l10n::s("(未打开)", "(no book)"));
    m_lbBook->setMaximumWidth(320);
    /* v0.77.0 V2：顶栏/底栏图标钮（与安卓 glyphicons 同形，悬停显示文案；无图标资源时显示文字） */
    auto mkTb = [](const QString &text, const char *icon, bool checkable = false,
                   bool withText = false)
    {
        QToolButton *b = new QToolButton;
        b->setAutoRaise(true);
        b->setCheckable(checkable);
        b->setToolTip(text);
        b->setIconSize(QSize(20, 20));
        if (withText) b->setToolButtonStyle(Qt::ToolButtonTextBesideIcon); /* v1.0.0：顶栏图标旁带文字 */
        if (hr::Icons::have())
            hr::Icons::bind(b, QString::fromLatin1(icon), 20);
        else
            b->setText(text);
        return b;
    };
    QPushButton *btnOpen = new QPushButton(hr::l10n::s("打开", "Open"));
    QToolButton *btnToc = mkTb(hr::l10n::s("目录", "TOC"), "glyphicons_160_playlist", false, true);
    QToolButton *btnBm = mkTb(hr::l10n::s("书签", "Bookmarks"), "glyphicons_73_bookmark", false, true);
    QToolButton *btnAddBm = mkTb(hr::l10n::s("+签", "+Mark"), "glyphicons_371_plus", false, true);
    QToolButton *btnNote = mkTb(hr::l10n::s("笔记", "Note"), "my_glyphicons_pen", false, true); /* v1.6.0（B1）：✏️ 笔记=安卓 pencil 入口 */
    QPushButton *btnFontDown = new QPushButton("A-");
    QPushButton *btnFontUp = new QPushButton("A+");
    QToolButton *btnTheme = mkTb(hr::l10n::s("主题", "Theme"), "glyphicons_140_adjust", false, true);
    QPushButton *btnTwo = new QPushButton(hr::l10n::s("双页", "2-up"));
    QToolButton *btnSearch = mkTb(hr::l10n::s("搜索", "Search"), "glyphicons_28_search", false, true);
    QToolButton *btnTrim = mkTb(hr::l10n::s("裁白边", "Trim"), "glyphicons_94_crop", true, true); /* v0.83.0 W2 D6 */
    QPushButton *btnSpeak = new QPushButton(hr::l10n::s("朗读", "Speak"));
    QPushButton *btnStop = new QPushButton(hr::l10n::s("停止", "Stop"));
    QPushButton *btnWav = new QPushButton(hr::l10n::s("导出WAV", "WAV"));
    QPushButton *btnAi = new QPushButton(hr::l10n::s("AI译页", "AI"));
    QPushButton *btnMode = new QPushButton(hr::l10n::s("滚动", "Scroll"));
    QPushButton *btnAuto = new QPushButton(hr::l10n::s("自动", "Auto"));
    QPushButton *btnRsvp = new QPushButton(hr::l10n::s("速读", "RSVP"));
    QToolButton *btnPrefs = mkTb(hr::l10n::s("排版", "Layout"), "glyphicons_108_text_resize", false, true);
    QToolButton *btnMore = mkTb(hr::l10n::s("更多", "More"), "glyphicons_498_more_vertical", false, true);
    QToolButton *btnClose = mkTb(hr::l10n::s("关闭", "Close"), "glyphicons_599_menu_close", false, true);
    btnMode->setCheckable(true);
    btnAuto->setCheckable(true);
    btnMode->setChecked(m_scrollMode);
    updateModeUi(); /* v0.109.0：初进按模式摆好进度条位置 */
    btnTrim->setChecked(m_trimWhite); /* v0.83.0 W2 */
    btnTwo->setCheckable(true);
    QHBoxLayout *top = new QHBoxLayout;
    top->addWidget(m_lbBook, 1);
    top->addWidget(btnToc);
    top->addWidget(btnBm);
    top->addWidget(btnAddBm);
    top->addWidget(btnNote); /* v1.6.0（B1） */
    top->addWidget(btnSearch);
    top->addWidget(btnTrim); /* v0.83.0 W2 D6 */
    top->addWidget(btnTheme);
    top->addWidget(btnPrefs);
    top->addWidget(btnMore);
    top->addWidget(btnClose);

    /* U3a 底部动作行：朗读⇄停止 / 自动 / 速读 / 沉浸 */
    QToolButton *botSpeak = mkTb(hr::l10n::s("朗读", "Speak"), "glyphicons_77_headphones", true);
    QToolButton *botAuto = mkTb(hr::l10n::s("自动", "Auto"), "glyphicons_211_arrow_down", true);
    QToolButton *botRsvp = mkTb(hr::l10n::s("速读", "RSVP"), "glyphicons_439_video_play_empty");
    QToolButton *botImmerse = mkTb(hr::l10n::s("沉浸", "Immerse"), "glyphicons_216_fullscreen", true);
    QToolButton *botTr = mkTb(hr::l10n::s("译", "Tr"), "glyphicons_580_translate"); /* v0.83.0 W2 D7 */
    QToolButton *botBi = mkTb(hr::l10n::s("双语", "Bi"), "glyphicons_my_ai_translate");

    /* 内容区 */
    m_scroll = new QScrollArea;
    m_scroll->setWidgetResizable(false);
    m_scroll->setAlignment(Qt::AlignHCenter | Qt::AlignTop);
    m_scroll->setFrameShape(QFrame::NoFrame);
    m_scroll->viewport()->installEventFilter(this);

    /* 底栏 */
    m_lbPos = new QLabel("-");
    m_slider = new QSlider(Qt::Horizontal);
    m_slider->setRange(0, 0);
    QHBoxLayout *bot = new QHBoxLayout;
    bot->addWidget(m_lbPos, 0);
    bot->addWidget(m_slider, 1);
    bot->addWidget(botTr); /* v0.83.0 W2 D7 */
    bot->addWidget(botBi);
    bot->addWidget(botSpeak);
    bot->addWidget(botAuto);
    bot->addWidget(botRsvp);
    bot->addWidget(botImmerse);

    /* 双栏容器（沉浸时隐藏） */
    m_topBar = new QWidget;
    m_topBar->setLayout(top);
    m_botBar = new QWidget;
    m_botBar->setLayout(bot);

    updateModeUi(); /* v0.110.0：滚动模式只留原生连续滚动条（右缘），底条=翻页模式专用 */

    QWidget *central = new QWidget;
    QVBoxLayout *lay = new QVBoxLayout(central);
    lay->setContentsMargins(4, 4, 4, 4);
    lay->setSpacing(4);
    lay->addWidget(m_topBar);
    lay->addWidget(m_scroll, 1);
    lay->addWidget(m_botBar);
    setCentralWidget(central);

    /* U3b 侧板：目录/书签/搜索 三 tab（默认隐藏，顶栏按钮唤出） */
    m_side = new QDockWidget(hr::l10n::s("侧栏", "Panel"), this);
    m_side->setAllowedAreas(Qt::LeftDockWidgetArea | Qt::RightDockWidgetArea);
    m_side->setFeatures(QDockWidget::DockWidgetClosable);
    m_side->hide();
    m_sideTabs = new QTabWidget(m_side);

    /* tab0 目录：单击跳页 */
    m_tocTree = new QTreeWidget;
    m_tocTree->setHeaderLabel(hr::l10n::s("标题", "Title"));
    m_tocTree->setColumnCount(1);
    connect(m_tocTree, &QTreeWidget::itemClicked, this, [this](QTreeWidgetItem *it)
    {
        const int p = it->data(0, Qt::UserRole).toInt();
        if (p >= 0) jumpTo(p);
    });
    m_sideTabs->addTab(m_tocTree, hr::l10n::s("目录", "TOC"));

    /* tab1 书签：跳转 + 删除（写同步墓碑） */
    QWidget *bmPage = new QWidget;
    QVBoxLayout *bmLay = new QVBoxLayout(bmPage);
    m_bmList = new QListWidget;
    bmLay->addWidget(m_bmList, 1);
    QHBoxLayout *bmBtns = new QHBoxLayout;
    QPushButton *bmGo = new QPushButton(hr::l10n::s("跳转", "Go"));
    QPushButton *bmDel = new QPushButton(hr::l10n::s("删除", "Delete"));
    bmBtns->addWidget(bmGo);
    bmBtns->addWidget(bmDel);
    bmBtns->addStretch(1);
    bmLay->addLayout(bmBtns);

    /* U-ann 高亮分区：色块+页码+摘录；跳转/删除；写回PDF（全量导出标注副本） */
    m_hlLb = new QLabel(hr::l10n::s("高亮（0）", "Highlights (0)"), bmPage);
    m_hlLb->setStyleSheet("font-weight:bold;");
    QHBoxLayout *hlHead = new QHBoxLayout;
    hlHead->addWidget(m_hlLb, 1);
    QPushButton *hlWb = new QPushButton(hr::l10n::s("写回PDF", "Save PDF"), bmPage);
    hlHead->addWidget(hlWb);
    bmLay->addLayout(hlHead);
    m_hlList = new QListWidget(bmPage);
    bmLay->addWidget(m_hlList, 1);
    QHBoxLayout *hlBtns = new QHBoxLayout;
    QPushButton *hlGo = new QPushButton(hr::l10n::s("跳转", "Go"), bmPage);
    QPushButton *hlDel = new QPushButton(hr::l10n::s("删除", "Delete"), bmPage);
    hlBtns->addWidget(hlGo);
    hlBtns->addWidget(hlDel);
    hlBtns->addStretch(1);
    bmLay->addLayout(hlBtns);
    connect(m_hlList, &QListWidget::itemDoubleClicked, this, [this](QListWidgetItem *)
    {
        const int i = m_hlList->currentRow();
        if (i >= 0 && i < m_hlRows.size()) jumpTo(m_hlRows[i].page);
    });
    connect(hlGo, &QPushButton::clicked, this, [this]
    {
        const int i = m_hlList->currentRow();
        if (i >= 0 && i < m_hlRows.size()) jumpTo(m_hlRows[i].page);
    });
    connect(hlDel, &QPushButton::clicked, this, [this]
    {
        const int i = m_hlList->currentRow();
        if (i < 0 || i >= m_hlRows.size()) return;
        { /* v1.3.0（F'）：注解删除落墓碑（ann；book=内容MD5，随侧车同步抑制复活） */
            const QString h = hr::syncMd5(m_path);
            Db::tombstoneAdd("ann", h.isEmpty() ? m_path : h,
                             QString::number(m_hlRows[i].createdAt));
        }
        Db::deleteAnnotation(m_hlRows[i].id);
        evt("hl_del", QString("left=%1").arg(m_hlRows.size() - 1));
        refreshHighlights();
        refreshHighlightList();
    });
    connect(hlWb, &QPushButton::clicked, this, [this]
    {
        if (QFileInfo(m_path).suffix().toLower() != "pdf")
        {
            statusBar()->showMessage(hr::l10n::s("写回仅支持 PDF",
                                                 "Write-back supports PDF only"), 5000);
            return;
        }
        QVector<int> pages, argbs, quadNs;
        QVector<const float *> quadsArr;
        QVector<QVector<float>> storage;
        for (const Db::AnnotationRow &r : m_hlRows)
        {
            if (r.pageCount != m_pages) continue;
            QVector<float> f;
            const QStringList parts = r.quads.split(';', Qt::SkipEmptyParts);
            for (const QString &part : parts)
            {
                const QStringList xywh = part.split(',');
                if (xywh.size() != 4) continue;
                bool o1 = false, o2 = false, o3 = false, o4 = false;
                f << xywh[0].toFloat(&o1) << xywh[1].toFloat(&o2)
                  << xywh[2].toFloat(&o3) << xywh[3].toFloat(&o4);
                if (!(o1 && o2 && o3 && o4)) f.resize(f.size() - 4);
            }
            if (f.isEmpty()) continue;
            storage.append(f);
            pages.append(r.page);
            argbs.append(r.color);
            quadNs.append(f.size() / 4);
        }
        if (storage.isEmpty())
        {
            statusBar()->showMessage(hr::l10n::s("无可写回的高亮",
                                                 "No highlights to write"), 5000);
            return;
        }
        for (const auto &f : storage) quadsArr.append(f.constData());
        const QString dest = QFileInfo(m_path).dir().filePath(
            QFileInfo(m_path).completeBaseName() + "-标注.pdf");
        const int ok = hr_qt_pdf_export_highlights(
            m_doc, dest.toUtf8().constData(), pages.constData(), argbs.constData(),
            quadsArr.constData(), quadNs.constData(), storage.size());
        statusBar()->showMessage(ok ? hr::l10n::s("已写回：", "Written: ") + dest
                                    : hr::l10n::s("写回失败", "Write-back failed"), 8000);
        evt(ok ? "pdf_writeback" : "pdf_writeback_fail", dest.left(80));
    });
    connect(bmGo, &QPushButton::clicked, this, [this]
    {
        const int i = m_bmList->currentRow();
        if (i >= 0 && i < m_bmRows.size()) jumpToBookmark(i);
    });
    connect(m_bmList, &QListWidget::itemDoubleClicked, this, [this](QListWidgetItem *)
    {
        const int i = m_bmList->currentRow();
        if (i < 0 || i >= m_bmRows.size()) return;
        /* v1.12.2：点击分流（安卓 BookmarksFragment2.showNoteDialog 同语义）——
         * 笔记行看正文、书签行跳位；跳转保留右键"跳转"与"跳转"按钮 */
        if (m_bmRows[i].isAiNote)
            showNoteBody(i);
        else
            jumpToBookmark(i);
    });
    /* P1 适配（C1）：书签笔记——右键编辑备注（bookmark.note 列与 WIN/安卓互通） */
    m_bmList->setContextMenuPolicy(Qt::CustomContextMenu);
    connect(m_bmList, &QListWidget::customContextMenuRequested, this,
            [this](const QPoint &pos)
    {
        const int i = m_bmList->row(m_bmList->itemAt(pos));
        if (i < 0 || i >= m_bmRows.size()) return;
        QMenu bmMenu(this);
        QAction *goA = bmMenu.addAction(hr::l10n::s("跳转", "Go"));
        /* v1.2.0（B2/B3）：AI 笔记只读查看；普通书签加收藏标切换 */
        QAction *aiA = nullptr, *noteA = nullptr, *favA = nullptr;
        if (m_bmRows[i].isAiNote)
            /* v1.6.0（B5）：手工笔记（答复空）=查看笔记正文；AI 笔记=看答复（D4 渲染口径） */
            aiA = bmMenu.addAction(m_bmRows[i].aiAnswer.isEmpty()
                                       ? hr::l10n::s("查看笔记", "View note")
                                       : hr::l10n::s("查看 AI 笔记", "View AI note"));
        else
        {
            noteA = bmMenu.addAction(
                m_bmRows[i].note.isEmpty()
                    ? hr::l10n::s("添加备注…", "Add note…")
                    : hr::l10n::s("编辑备注…", "Edit note…"));
            favA = bmMenu.addAction(m_bmRows[i].isF
                                        ? hr::l10n::s("取消收藏标", "Unstar")
                                        : hr::l10n::s("收藏标★", "Star"));
        }
        QAction *act = bmMenu.exec(m_bmList->mapToGlobal(pos));
        if (act == goA)
            jumpToBookmark(i);
        else if (act == aiA && aiA)
            showNoteBody(i); /* v1.12.2：正文弹窗抽公共（右键/双击同口径 evt bm_note_view） */
        else if (act == favA && favA)
        {
            const bool on = !m_bmRows[i].isF;
            Db::setBookmarkFav(m_bmRows[i].id, on);
            m_bmRows[i].isF = on ? 1 : 0;
            evt("bm_fav", QString("id=%1 on=%2").arg(m_bmRows[i].id).arg(on ? 1 : 0));
            refreshBookmarkList();
        }
        else if (act == noteA && noteA)
        {
            bool okD = false;
            const QString t = QInputDialog::getMultiLineText(
                this, hr::l10n::s("书签备注", "Bookmark note"),
                hr::l10n::s("备注（留空清除）", "Note (empty to clear)"),
                m_bmRows[i].note, &okD);
            if (!okD) return;
            Db::updateBookmarkNote(m_bmRows[i].id, t.trimmed());
            m_bmRows[i].note = t.trimmed();
            m_bmList->item(i)->setText(
                QString(hr::l10n::s("%1    第%2页", "%1    p.%2"))
                    .arg(m_bmRows[i].label)
                    .arg(m_bmRows[i].page + 1) +
                (m_bmRows[i].note.isEmpty()
                     ? QString()
                     : QString(hr::l10n::s("　·%1", "  ·%1")).arg(m_bmRows[i].note.left(24))));
            m_bmList->item(i)->setToolTip(m_bmRows[i].note);
            evt("bm_note", QString("id=%1 len=%2").arg(m_bmRows[i].id).arg(t.size()));
        }
    });
    connect(bmDel, &QPushButton::clicked, this, [this]
    {
        const int i = m_bmList->currentRow();
        if (i < 0 || i >= m_bmRows.size()) return;
        /* U0 修复#2 同口径：删除书签必须写同步墓碑 */
        addBmTombstoneFor(m_path, QString::number(m_bmRows[i].createdAt));
        Db::deleteBookmark(m_bmRows[i].id);
        m_bmRows.removeAt(i);
        delete m_bmList->takeItem(i);
        evt("bm_del_side", QString("left=%1").arg(m_bmRows.size()));
    });
    m_sideTabs->addTab(bmPage, hr::l10n::s("书签", "Marks"));

    /* tab2 搜索：逐页匹配，双击结果跳页 */
    QWidget *schPage = new QWidget;
    QVBoxLayout *schLay = new QVBoxLayout(schPage);
    QHBoxLayout *schRow = new QHBoxLayout;
    m_schEdit = new QLineEdit;
    m_schEdit->setPlaceholderText(hr::l10n::s("搜索词（逐页匹配）", "Search text (per page)"));
    QPushButton *schGo = new QPushButton(hr::l10n::s("搜索", "Search"));
    schRow->addWidget(m_schEdit, 1);
    schRow->addWidget(schGo);
    schLay->addLayout(schRow);
    m_schList = new QListWidget;
    schLay->addWidget(m_schList, 1);
    m_schLb = new QLabel(hr::l10n::s("回车/点搜索；双击结果跳页",
                                     "Enter to search; double-click to jump"));
    schLay->addWidget(m_schLb);
    connect(schGo, &QPushButton::clicked, this, [this] { doSideSearch(); });
    connect(m_schEdit, &QLineEdit::returnPressed, this, [this] { doSideSearch(); });
    connect(m_schList, &QListWidget::itemDoubleClicked, this, [this](QListWidgetItem *it)
    {
        const int page = it->text().section(' ', 1, 1).toInt() - 1;
        if (page >= 0) jumpTo(page);
    });
    m_sideTabs->addTab(schPage, hr::l10n::s("搜索", "Search"));

    m_side->setWidget(m_sideTabs);
    addDockWidget(Qt::RightDockWidgetArea, m_side);
    resizeDocks({ m_side }, { 300 }, Qt::Horizontal);

    connect(btnOpen, &QPushButton::clicked, this, &ReaderWindow::onOpen);
    connect(btnPrefs, &QPushButton::clicked, this, &ReaderWindow::onPrefs);
    connect(btnToc, &QPushButton::clicked, this, [this] { showPanel(0); });
    connect(btnBm, &QPushButton::clicked, this, [this] { showPanel(1); });
    connect(btnAddBm, &QPushButton::clicked, this, &ReaderWindow::onAddBookmark);
    connect(btnNote, &QToolButton::clicked, this, [this] { hr::noteEditDialog(this); }); /* v1.6.0（B1） */
    connect(btnFontDown, &QPushButton::clicked, this, &ReaderWindow::onFontSmaller);
    connect(btnFontUp, &QPushButton::clicked, this, &ReaderWindow::onFontBigger);
    connect(btnTheme, &QPushButton::clicked, this, &ReaderWindow::onThemeNext);
    connect(btnSearch, &QPushButton::clicked, this, [this] { showPanel(2); });
    connect(btnTrim, &QToolButton::toggled, this, [this, btnTrim](bool on) /* v0.83.0 W2 D6 */
    {
        if (on && m_scrollMode)
        {
            statusBar()->showMessage(
                hr::l10n::s("裁白边仅在翻页模式生效", "Trim white works in page mode only"), 5000);
            evt("trim_block_scroll", "1");
            btnTrim->blockSignals(true);
            btnTrim->setChecked(false);
            btnTrim->blockSignals(false);
            return;
        }
        m_trimWhite = on;
        savePrefs();
        if (!m_scrollMode) renderNow(m_page);
        evt("trim_white", on ? "1" : "0");
    });
    connect(this, &ReaderWindow::searchWord, this, &ReaderWindow::sideSearch);
    connect(btnSpeak, &QPushButton::clicked, this, [this]
    { hr::m4SpeakPage(this); });
    connect(btnStop, &QPushButton::clicked, this, [this] { hr::m4StopSpeak(this); });
    connect(btnWav, &QPushButton::clicked, this, [this]
    { hr::m4ExportWav(this); });
    connect(btnAi, &QPushButton::clicked, this, [this]
    { hr::m4AiDialog(this); });
    connect(btnTwo, &QPushButton::toggled, this, [this, btnTwo](bool on)
    {
        if (on && m_scrollMode)
        {
            /* U3a 修复（缺陷#11）：双页仅翻页模式有效——滚动模式回弹+提示 */
            statusBar()->showMessage(
                hr::l10n::s("双页仅在翻页模式生效（可先用“更多→翻页模式”）",
                            "Two pages works in page mode only"), 5000);
            evt("twopage_block_scroll", "1");
            btnTwo->blockSignals(true);
            btnTwo->setChecked(false);
            btnTwo->blockSignals(false);
            return;
        }
        m_twoPage = on;
        savePrefs();
        if (!m_scrollMode) renderNow(m_page);
    });
    connect(btnMode, &QPushButton::toggled, this, [this](bool on)
    {
        m_scrollMode = on;
        savePrefs();
        updateModeUi(); /* v0.109.0：滚动模式进度条移右缘 */
        relayoutAfterChange(false); /* 模式切换不重数（分章书页数不变） */
    });
    connect(btnAuto, &QPushButton::toggled, this, [this, btnAuto](bool on)
    {
        if (on)
        {
            /* v0.83.0 W2 D8：滚动模式用毫秒间隔，翻页模式用翻页间隔秒 */
            const int ms = m_scrollMode ? (m_autoMs > 0 ? m_autoMs : 500)
                                        : qMax(1, m_autoFlipSec) * 1000;
            m_autoTimer.start(ms);
            evt("autoscroll_on", QString("ms=%1").arg(ms));
        }
        else
        {
            m_autoTimer.stop();
            evt("autoscroll_off", QString());
        }
    });
    connect(&m_autoTimer, &QTimer::timeout, this, [this, btnAuto]
    {
        if (!m_doc) { m_autoTimer.stop(); btnAuto->setChecked(false); return; }
        if (m_scrollMode)
        {
            QScrollBar *sb = m_scroll->verticalScrollBar();
            if (sb->value() >= sb->maximum() && m_page >= m_pages - 1)
            {
                m_autoTimer.stop();
                btnAuto->setChecked(false);
                evt("autoscroll_end", QString());
                return;
            }
            /* v1.8.0（P0-7）：自动滚动与渲染解耦——在途/待渲未清空时本 tick 原地等
             * （最多让 300ms 防弱机死等）；步长由固定 56px 改视口高 1/3（与页高
             * 成比例，大页不挪半行、小页不跳屏） */
            const qint64 now = QDateTime::currentMSecsSinceEpoch();
            if ((!m_flight.isEmpty() || !m_pendQueue.isEmpty()) &&
                now - m_lastAutoAdvance < 300)
                return;
            m_lastAutoAdvance = now;
            sb->setValue(sb->value() +
                         qBound(48, m_scroll->viewport()->height() / 3, 400));
        }
        else
        {
            /* v1.8.0（P0-7）：自动翻页 render-then-wait——上一页异步渲染在途则本
             * tick 跳过（防定时器把未完成翻页按间隔堆叠）；固定间隔语义保留 */
            if (m_pageFlight) return;
            nav(1);
        }
    });
    connect(btnRsvp, &QPushButton::clicked, this, [this] { hr::m4RsvpDialog(this); });

    /* U3a 更多菜单：转发到原按钮，aboutToShow 同步勾选态 */
    QMenu *moreMenu = new QMenu(btnMore);
    moreMenu->addAction(hr::l10n::s("打开…", "Open…"), this, [this] { onOpen(); });
    moreMenu->addAction(hr::l10n::s("AI 问答…", "AI Q&A…"), this,
                        [this] { m4QaDialog(this); }); /* P1（C7） */
    moreMenu->addSeparator();
    /* v0.83.0 W2 D8：换书（最近 10 本直达）+ 阅读位置回退/前进（对齐 Compose MoreMenu） */
    QMenu *swMenu = moreMenu->addMenu(hr::l10n::s("换书", "Switch book"));
    connect(swMenu, &QMenu::aboutToShow, this, [this, swMenu]
    {
        swMenu->clear();
        int cnt = 0;
        for (const QString &p : Db::listRecent(14))
        {
            if (p == m_path || p.startsWith(QString::fromLatin1("remote://"))) continue;
            swMenu->addAction(QFileInfo(p).fileName(), this, [this, p]
            { emit openPathRequested(p); });
            if (++cnt >= 10) break;
        }
        if (cnt == 0)
            swMenu->addAction(hr::l10n::s("（暂无最近）", "(no recent)"))->setEnabled(false);
    });
    QAction *aBack = moreMenu->addAction(hr::l10n::s("回退", "Back"));
    connect(aBack, &QAction::triggered, this, [this]
    {
        if (m_navBack.isEmpty()) return;
        m_navGuard++;
        m_navFwd.append(m_page);
        jumpTo(m_navBack.takeLast());
        m_navGuard--;
        evt("nav_back", QString("p=%1").arg(m_page + 1));
    });
    QAction *aFwd = moreMenu->addAction(hr::l10n::s("前进", "Fwd"));
    connect(aFwd, &QAction::triggered, this, [this]
    {
        if (m_navFwd.isEmpty()) return;
        m_navGuard++;
        m_navBack.append(m_page);
        jumpTo(m_navFwd.takeLast());
        m_navGuard--;
        evt("nav_fwd", QString("p=%1").arg(m_page + 1));
    });
    moreMenu->addSeparator();
    QAction *aFontUp = moreMenu->addAction(hr::l10n::s("放大字体", "Bigger font"));
    connect(aFontUp, &QAction::triggered, btnFontUp, &QPushButton::click);
    QAction *aFontDown = moreMenu->addAction(hr::l10n::s("缩小字体", "Smaller font"));
    connect(aFontDown, &QAction::triggered, btnFontDown, &QPushButton::click);
    QAction *aTwo = moreMenu->addAction(hr::l10n::s("双页并排", "Two pages"));
    aTwo->setCheckable(true);
    connect(aTwo, &QAction::triggered, btnTwo, &QPushButton::toggle);
    QAction *aMode = moreMenu->addAction(m_scrollMode ? hr::l10n::s("翻页模式", "Page mode")
                                                      : hr::l10n::s("滚动模式", "Scroll mode"));
    connect(aMode, &QAction::triggered, btnMode, &QPushButton::toggle);
    moreMenu->addSeparator();
    QAction *aSpeak = moreMenu->addAction(hr::l10n::s("朗读本页", "Speak page"));
    connect(aSpeak, &QAction::triggered, btnSpeak, &QPushButton::click);
    QAction *aStop = moreMenu->addAction(hr::l10n::s("停止朗读", "Stop speaking"));
    connect(aStop, &QAction::triggered, btnStop, &QPushButton::click);
    QAction *aWav = moreMenu->addAction(hr::l10n::s("导出朗读 WAV", "Export WAV"));
    connect(aWav, &QAction::triggered, btnWav, &QPushButton::click);
    QAction *aAi = moreMenu->addAction(hr::l10n::s("AI（译页/双语/简介）", "AI"));
    connect(aAi, &QAction::triggered, btnAi, &QPushButton::click);
    moreMenu->addSeparator();
    /* v0.83.0 W2 D8：连读（本页读完自动翻页续读）+ 定时朗读（N 分钟后停止） */
    QAction *aCont = moreMenu->addAction(hr::l10n::s("连读", "Auto read"));
    aCont->setCheckable(true);
    connect(aCont, &QAction::toggled, this, [this](bool on)
    {
        m_ttsCont = on;
        if (on)
        {
            m_ttsSuppress = false;
            m_ttsWasSpeaking = hr::Tts::isSpeaking();
            m_ttsContTimer.start(600);
        }
        else
        {
            m_ttsContTimer.stop();
        }
        evt("tts_cont", on ? "1" : "0");
    });
    QAction *aTimer = moreMenu->addAction(hr::l10n::s("定时朗读·关", "Timer off"));
    connect(aTimer, &QAction::triggered, this, [this]
    {
        bool ok = false;
        const int v = QInputDialog::getInt(this, hr::l10n::s("定时朗读", "TTS timer"),
            hr::l10n::s("分钟后停止朗读(0=关闭)", "Stop speaking after minutes (0=off)"),
            m_ttsStopMin, 0, 240, 5, &ok);
        if (!ok) return;
        m_ttsStopMin = v;
        if (v > 0) m_ttsStopTimer.start(v * 60000);
        else m_ttsStopTimer.stop();
        evt("tts_timer", QString("min=%1").arg(v));
    });
    moreMenu->addSeparator();
    QAction *aAuto = moreMenu->addAction(hr::l10n::s("自动滚动/翻页", "Auto scroll"));
    aAuto->setCheckable(true);
    connect(aAuto, &QAction::triggered, btnAuto, &QPushButton::toggle);
    QAction *aRsvp = moreMenu->addAction(hr::l10n::s("速读 RSVP", "RSVP"));
    connect(aRsvp, &QAction::triggered, btnRsvp, &QPushButton::click);
    /* v0.83.0 W2 D8：滚动速度/翻页间隔（Compose 滚动速度 x / 翻页间隔 s 同语义） */
    QAction *aSpeed = moreMenu->addAction(hr::l10n::s("滚动速度…", "Scroll speed…"));
    connect(aSpeed, &QAction::triggered, this, [this]
    {
        bool ok = false;
        const int v = QInputDialog::getInt(this, hr::l10n::s("滚动速度", "Scroll speed"),
            hr::l10n::s("自动滚动间隔(毫秒)", "Auto-scroll interval (ms)"),
            m_autoMs, 100, 5000, 50, &ok);
        if (!ok) return;
        m_autoMs = v;
        savePrefs();
        if (m_autoTimer.isActive() && m_scrollMode) m_autoTimer.start(v);
        evt("autoscroll_speed", QString("ms=%1").arg(v));
    });
    QAction *aFlip = moreMenu->addAction(hr::l10n::s("翻页间隔…", "Flip interval…"));
    connect(aFlip, &QAction::triggered, this, [this]
    {
        bool ok = false;
        const int v = QInputDialog::getInt(this, hr::l10n::s("翻页间隔", "Flip interval"),
            hr::l10n::s("自动翻页间隔(秒)", "Auto flip interval (s)"),
            m_autoFlipSec, 1, 600, 1, &ok);
        if (!ok) return;
        m_autoFlipSec = v;
        savePrefs();
        if (m_autoTimer.isActive() && !m_scrollMode) m_autoTimer.start(v * 1000);
        evt("autoflip_sec", QString("s=%1").arg(v));
    });
    connect(btnMore, &QPushButton::clicked, this,
            [this, moreMenu, aTwo, aAuto, aMode, aCont, aTimer, aBack, aFwd, btnMore]
    {
        aTwo->setChecked(m_twoPage);
        aAuto->setChecked(m_autoTimer.isActive());
        aMode->setText(m_scrollMode ? hr::l10n::s("翻页模式", "Page mode")
                                    : hr::l10n::s("滚动模式", "Scroll mode"));
        aCont->setChecked(m_ttsCont); /* v0.83.0 W2 */
        aTimer->setText(m_ttsStopMin > 0
                            ? hr::l10n::s("定时朗读 ", "Timer ") + QString::number(m_ttsStopMin) + "m"
                            : hr::l10n::s("定时朗读·关", "Timer off"));
        aBack->setEnabled(!m_navBack.isEmpty());
        aFwd->setEnabled(!m_navFwd.isEmpty());
        moreMenu->exec(btnMore->mapToGlobal(QPoint(0, btnMore->height())));
    });
    connect(btnClose, &QPushButton::clicked, this, &ReaderWindow::requestClose); /* v0.75.0：内嵌时=返回书库 */

    /* v0.83.0 W2 D8：连读轮询（本页读完→翻页续读）+ 定时朗读停止 */
    connect(&m_ttsContTimer, &QTimer::timeout, this, [this]
    {
        if (!m_ttsCont) { m_ttsContTimer.stop(); return; }
        const bool sp = hr::Tts::isSpeaking();
        if (m_ttsWasSpeaking && !sp)
        {
            if (m_ttsSuppress)
            {
                m_ttsSuppress = false;
                m_ttsWasSpeaking = sp;
                return;
            }
            if (m_page + 1 < m_pages)
            {
                jumpTo(m_page + 1);
                QTimer::singleShot(400, this, [this]
                { if (m_ttsCont && m_doc) hr::m4SpeakPage(this); });
            }
            else
            {
                m_ttsCont = false;
                m_ttsContTimer.stop();
                evt("tts_cont_end", "1");
            }
        }
        m_ttsWasSpeaking = sp;
    });
    connect(&m_ttsStopTimer, &QTimer::timeout, this, [this]
    {
        m_ttsStopTimer.stop();
        m_ttsStopMin = 0;
        hr::m4StopSpeak(this);
        statusBar()->showMessage(hr::l10n::s("定时朗读时间到，已停止", "TTS timer elapsed"), 5000);
        evt("tts_timer_fire", "1");
    });

    /* 底部动作行 */
    connect(botSpeak, &QPushButton::toggled, this, [this, botSpeak](bool on)
    {
        if (on) { hr::m4SpeakPage(this); evt("bot_speak", "1"); }
        else { hr::m4StopSpeak(this); m_ttsSuppress = true; m_ttsStopTimer.stop(); m_ttsStopMin = 0; evt("bot_speak", "0"); }
        botSpeak->setText(on ? hr::l10n::s("停止", "Stop") : hr::l10n::s("朗读", "Speak"));
    });
    connect(btnStop, &QPushButton::clicked, this, [botSpeak]
    { botSpeak->setChecked(false); });
    connect(botAuto, &QPushButton::toggled, this, [this, btnAuto](bool on)
    {
        if (btnAuto->isChecked() != on) btnAuto->setChecked(on);
    });
    connect(btnAuto, &QPushButton::toggled, this, [botAuto](bool on)
    {
        if (botAuto->isChecked() != on) botAuto->setChecked(on);
    });
    connect(botRsvp, &QPushButton::clicked, this, [this] { hr::m4RsvpDialog(this); });
    connect(botTr, &QPushButton::clicked, this, [this] { hr::m4AiDialog(this, 0); }); /* v0.83.0 W2 D7 */
    connect(botBi, &QPushButton::clicked, this, [this] { hr::m4AiDialog(this, 1); });
    connect(botImmerse, &QPushButton::toggled, this, [this](bool on)
    {
        if (m_immersive != on) toggleImmersive();
    });

    connect(m_slider, &QSlider::sliderPressed, this, [this] { m_sliderMoving = 1; });
    connect(m_slider, &QSlider::sliderReleased, this, [this]
    {
        m_sliderMoving = 0;
        jumpTo(m_slider->value());
    });
    connect(m_slider, &QSlider::valueChanged, this, [this](int v)
    {
        if (m_sliderMoving && m_pages > 0)
            m_lbPos->setText(QString("%1 / %2").arg(v + 1).arg(m_pages));
    });

    connect(m_scroll->verticalScrollBar(), &QScrollBar::valueChanged, this,
            &ReaderWindow::onScrollValue);

    m_saveTimer.setSingleShot(true);
    m_saveTimer.setInterval(800);
    connect(&m_saveTimer, &QTimer::timeout, this, &ReaderWindow::saveProgressNow);
    m_statTimer.setInterval(60000);
    connect(&m_statTimer, &QTimer::timeout, this, [this]
    {
        if (m_doc) hr::Stats::addReading(m_path, 0, 1);
    });
    connect(&m_ttsTimer, &QTimer::timeout, this, [this]
    {
        m_ttsTimer.stop();
        if (m_doc && m_page + 1 < m_pages)
        {
            jumpTo(m_page + 1);
            hr::m4SpeakPage(this);
        }
    });

    m_resizeTimer.setSingleShot(true);
    m_resizeTimer.setInterval(500);
    connect(&m_resizeTimer, &QTimer::timeout, this, [this]
    {
        int vw = m_scroll->viewport()->width();
        if (m_doc && vw > 300 && qAbs(vw - m_viewW) > 40) relayoutAfterChange();
    });

    m_selOverlay = new hr::SelOverlay(m_scroll->viewport());
    m_selOverlay->hide();

    /* v1.12.0：滚动跟随——拖选中按页内局部锚点整段重算；非拖选时高亮贴内容移动 */
    connect(m_scroll->verticalScrollBar(), &QScrollBar::valueChanged, this, [this](int)
    {
        if (m_dragging)
        {
            /* v1.12.1：滚动中端点跟随光标——自动滚动/滚轮时不产生 MouseMove，
             * 端点须从 m_selLastVp 重算，否则选区尾不随视口扩展（高亮甩在原地） */
            m_selP1 = selClampPageAt(m_selLastVp);
            m_selPt1 = selToLocal(m_selP1, m_selLastVp);
            updateSelection();
        }
        else if (!m_selQuads.isEmpty())
            refreshSelOverlay();
    });
    m_selAuto.setInterval(30);
    connect(&m_selAuto, &QTimer::timeout, this, &ReaderWindow::selAutoScrollTick);

    /* v1.8.0（P0-6）：翻页异步渲染"渲染中…"角标（viewport 右上角，鼠标穿透） */
    m_pageBusy = new QLabel(m_scroll->viewport());
    m_pageBusy->setAttribute(Qt::WA_TransparentForMouseEvents);
    m_pageBusy->setStyleSheet(QString::fromLatin1(
        "background:rgba(25,25,25,170);color:#ffffff;padding:4px 10px;"));
    m_pageBusy->setText(hr::l10n::s("渲染中…", "Rendering…"));
    m_pageBusy->adjustSize();
    m_pageBusy->hide();

    /* v0.103.0 P0-1：后台打开遮罩（打开/排版期间挡输入并给反馈；几何随 resizeEvent） */
    m_openOv = new QWidget(this);
    m_openOv->setStyleSheet(QString::fromLatin1("background:rgba(25,25,25,150);"));
    m_openOvLb = new QLabel(hr::l10n::s("正在打开…", "Opening…"), m_openOv);
    m_openOvLb->setAlignment(Qt::AlignCenter);
    m_openOvLb->setStyleSheet(
        QString::fromLatin1("color:#ffffff;font-size:15pt;background:transparent;"));
    {
        QVBoxLayout *ovl = new QVBoxLayout(m_openOv);
        ovl->setContentsMargins(0, 0, 0, 0);
        ovl->addWidget(m_openOvLb);
    }
    m_openOv->hide();

    loadPrefs();
}

/* ---------- 工具 ---------- */

int ReaderWindow::maxCharsWidth() const /* P1：每行字数版心（0=全宽视口） */
{
    if (m_maxChars > 0)
        return qMin(m_viewW, (int)(m_maxChars * m_em));
    return m_viewW;
}

int ReaderWindow::renderWidthPx() const
{
    int w = maxCharsWidth() - 24;
    if (w < 200) w = 200;
    if (m_fixedLayout && m_zoom != 1.0) /* P2（B7）：渲染宽×缩放（上限 4096 防 4K×4x 巨图） */
        w = qBound(200, (int)(w * m_zoom), 4096);
    if (!m_scrollMode && m_twoPage) w = w / 2;
    return w;
}

void ReaderWindow::applyThemeToJob(int *mode, int *tint) const
{
    const Theme &t = kThemes[m_theme];
    if (t.dark) { *mode = 2; *tint = 0; }
    else { *mode = 1; *tint = (int)(t.bg & 0xFFFFFF); }
}

/* v1.9.0（P1-2）：QImage 外部缓冲 cleanup——桥返回的 malloc 缓冲免 .copy() 直构 */
static void hr_imgFree(void *p) { free(p); }

bool ReaderWindow::renderRaw(int page, int width, QImage *out)
{
    if (!m_doc) return false;
    int mode = 0, tint = 0;
    applyThemeToJob(&mode, &tint);
    int w = 0, h = 0;
    unsigned char *rgb = nullptr;
    if (hr_qt_render(m_doc, page, width, mode, tint, &w, &h, &rgb) != 0 || !rgb)
        return false;
    /* v1.9.0（P1-2）：外部缓冲直构（cleanup 释放），免整页 .copy() */
    *out = QImage(rgb, w, h, w * 3, QImage::Format_RGB888, hr_imgFree, rgb);
    compositeHighlights(page, out); /* U-ann：持久高亮合成（翻页模式） */
    applyTexture(out); /* P2（B6） */
    return true;
}

void ReaderWindow::jumpTo(int page)
{
    if (m_openBusy.load()) return; /* v0.103.0：后台打开期间拒绝跳页（书尚未就绪） */
    if (m_pages <= 0) return;
    /* v1.12.1：翻页模式程序化跳页清选区（页内局部 quad 对新页失义，防高亮悬浮
     * 错位；滚动模式选区贴内容随滚动，保留） */
    if (!m_scrollMode && !m_selQuads.isEmpty()) clearSelection();
    page = qBound(0, page, m_pages - 1);
    /* v0.85.0 W2 补遗：非相邻跳转（目录/书签/滑条/HOME/END/搜索）入“回退”历史；
       相邻翻页与回退/前进的程序化跳转（m_navGuard 非零）不入栈 */
    if (m_navGuard == 0 && qAbs(page - m_page) > 1)
    {
        m_navBack.append(m_page);
        if (m_navBack.size() > 50) m_navBack.removeFirst();
        m_navFwd.clear();
    }
    m_page = page;
    if (m_scrollMode)
    {
        /* v1.11.1（P1-1）：按真实标签几何落位（estH 均匀假设在长短页混排时漂移，
         * page×estH 会落错页再被渲染修正"跳正"）；未渲染占位仍按 estH 累计 */
        int y = 0;
        const int nLab = m_stackY.size() - 1; /* v1.15.13：落位读 Y 前缀表（原逐标签累计） */
        if (nLab >= 0)
        {
            if (page < nLab)
                y = m_stackY[page];
            else
                y = m_stackY.last() + (page - nLab) * m_estH; /* 未建尾部按 estH 外推 */
        }
        /* v1.15.13：量程滞后兜底——真窗口里 QScrollArea 要等下一次重绘才处理容器的挂起
         * resize 事件，补页/建栈刚结束时 maximum 可能仍停在旧高度，远跳被钳到旧量程末尾
         * （实测 5239 页的书跳第 3001 页落到第 2611 页；无头窗口更是永不刷新）。按容器
         * 真实高度补齐量程再落位；QScrollArea 随后的自发刷新算出的是同一个值 */
        syncStackHeight();
        QScrollBar *vsb = m_scroll->verticalScrollBar();
        if (m_stack && vsb && m_scroll->viewport())
        {
            const int needMax = m_stack->height() - m_scroll->viewport()->height();
            if (needMax > vsb->maximum()) vsb->setRange(0, needMax);
        }
        m_scroll->verticalScrollBar()->setValue(y);
        fillVisible();
    }
    /* v1.8.0（P0-6）：翻页渲染异步化——旧页留存+角标，后台完成一次换页；
     * 冒烟无头路径保持同步（位图立即可取）。同步 renderNow 仅余：开书首渲/
     * 主题·裁边·双页切换/预取命中快路径 */
    else if (m_smokeW > 0)
        renderNow(page);
    else
        renderPageAsync(page);
}

/* v1.15.10：重排落地后的滚动位恢复——buildStack 的 setWidget/resize 后滚动条
 * range 随布局事件异步生效，同步 setValue 会被 QScrollBar 钳到 0，fillVisible
 * 遂从书首重渲并把 m_page/进度写回第 1 页（真机日志 gen=4/5 实锤：渲染 1~5 页
 * + progress_save page=1/325，"最大化/还原跳回首頁"根因）。延迟一拍待布局
 * 事件落地后按 v1.11.1 真实几何 jumpTo 落位（含 fillVisible/滚动锚定）。 */
void ReaderWindow::restorePosDeferred(int pg)
{
    /* v1.15.11：显式页快照——调用方在重排动作前捕获页号传入（过渡事件可能扰动
     * m_page，事后捕获=拿到被扰动的值）；默认 -1 取当前值 */
    if (pg < 0)
        pg = m_page;
    QTimer::singleShot(0, this, [this, pg]
    {
        if (m_doc && m_scrollMode && m_pages > 0)
            jumpTo(qBound(0, pg, m_pages - 1));
    });
}

/* ---------- 打开书 ---------- */

/* P2 适配（B3）：EPUB 排版加速器路径（cache/accels/<md5-12>-<size>-<mtime>.accel；仅本地 epub） */
QString accelPathFor(const QString &path) /* v1.9.0（P1-5）：去 static 供 warmer 共用（hraccels.h） */
{
    /* v0.104.0（P2-8）：accels 扩全本地格式（引擎 fz_save_accelerator 支持 pdf/epub，
     * 其余格式保存失败自动跳过；打开时无 accel 文件自然回退普通打开） */
    const QFileInfo f(path);
    if (!f.isFile()) return QString();
    const QString dir = dataDir() + QString::fromLatin1("/cache/accels");
    QDir().mkpath(dir);
    return dir + "/" +
           QString::fromLatin1(QCryptographicHash::hash(path.toUtf8(),
                                                        QCryptographicHash::Md5)
                                   .toHex()
                                   .left(12)) +
           "-" + QString::number(f.size()) + "-" +
           QString::number(f.lastModified().toMSecsSinceEpoch()) + ".accel";
}

/* P2 适配（B3）：清理同书旧加速器 + 全局 LRU 留 8（对齐 WIN cleanupAccels） */
/* v0.107.1：分章页数表缓存（对齐安卓 PageCacheFile 精神）——逐章计数是 mobi 等
 * 分章书 30s 级的开销（每章=排版+分页），把产物按 path#size#mtime#视口#em 落盘，
 * 重开直接得全量页数：免 phase2、免与渲染抢锁。键变（重排/改字号）自动失效。 */
static QString countCachePath(const QString &path, int vw, int vh, int em)
{
    if (path.startsWith(QString::fromLatin1("remote://")))
    {
        /* v0.107.1：远程书也缓存页数表——键用书库 file_meta 的 size（服务器文件
         * 变化经晋升/重扫更新 size 后键自然失效；同 size 换内容由 HEAD 校验另防） */
        long long sz = 0;
        for (const Db::MetaRow &m : Db::listLibrary())
            if (m.path == path)
            {
                sz = m.size;
                break;
            }
        if (sz <= 0) return QString();
        const QByteArray k = QCryptographicHash::hash(
            (path + "#" + QString::number(sz) + "#" + QString::number(vw) + "x" +
             QString::number(vh) + "#" + QString::number(em))
                .toUtf8(),
            QCryptographicHash::Md5).toHex();
        return dataDir() + QString::fromLatin1("/cache/counts/") + k.left(12) +
               QString::fromLatin1(".json");
    }
    const QFileInfo fi(path);
    if (!fi.isFile()) return QString();
    const QByteArray k = QCryptographicHash::hash(
        (path + "#" + QString::number(fi.size()) + "#" +
         QString::number(fi.lastModified().toMSecsSinceEpoch()) + "#" +
         QString::number(vw) + "x" + QString::number(vh) + "#" + QString::number(em))
            .toUtf8(),
        QCryptographicHash::Md5).toHex();
    return dataDir() + QString::fromLatin1("/cache/counts/") + k.left(12) +
           QString::fromLatin1(".json");
}

static void saveCountCache(const QString &path, int vw, int vh, int em,
                           const QVector<int> &per)
{
    const QString cp = countCachePath(path, vw, vh, em);
    if (cp.isEmpty() || per.isEmpty()) return;
    QDir().mkpath(QFileInfo(cp).absolutePath());
    QFile wf(cp);
    if (!wf.open(QIODevice::WriteOnly | QIODevice::Truncate)) return;
    QJsonArray ja;
    for (int v : per) ja.append(v);
    wf.write(QJsonDocument(ja).toJson(QJsonDocument::Compact));
    wf.close();
    const QFileInfoList rest =
        QDir(QFileInfo(cp).absolutePath()).entryInfoList(QDir::Files, QDir::Time);
    for (int i = 50; i < rest.size(); i++)
        QFile::remove(rest[i].absoluteFilePath()); /* LRU 留 50 */
}

static QVector<int> loadCountCache(const QString &path, int vw, int vh, int em, int nc)
{
    const QString cp = countCachePath(path, vw, vh, em);
    if (cp.isEmpty() || nc <= 0) return QVector<int>();
    QFile cf(cp);
    if (!cf.open(QIODevice::ReadOnly)) return QVector<int>();
    const QJsonArray ja = QJsonDocument::fromJson(cf.readAll()).array();
    cf.close();
    if (ja.size() != nc) return QVector<int>();
    QVector<int> per;
    per.reserve(nc);
    for (const QJsonValue &v : ja) per.append(v.toInt());
    return per;
}

void accelCleanup(const QString &path, const QString &keep) /* v1.9.0（P1-5）：去 static（hraccels.h） */
{
    const QString pre = QString::fromLatin1(QCryptographicHash::hash(path.toUtf8(),
                                                                    QCryptographicHash::Md5)
                                              .toHex()
                                              .left(12)) +
                         "-";
    QDir d(dataDir() + QString::fromLatin1("/cache/accels"));
    /* v0.101.0 fix14：按文件名比较（f.absoluteFilePath 用 OS 分隔符，与 keep 的 "/" 路径
       在 Windows 上恒不相等 → 刚写出的文件被误删；同目录内文件名唯一，语义等价且更稳） */
    const QString keepName = QFileInfo(keep).fileName();
    const QFileInfoList all = d.entryInfoList(QDir::Files);
    for (const QFileInfo &f : all)
        if (f.fileName().startsWith(pre) && f.fileName() != keepName)
            QFile::remove(f.absoluteFilePath());
    const QFileInfoList rest = d.entryInfoList(QDir::Files, QDir::Time);
    for (int i = 8; i < rest.size(); i++) QFile::remove(rest[i].absoluteFilePath());
}

/* P2 适配（B6）：质感纹理乘色叠加（仅浅色主题；资源 exeDir/tex/<id>.jpg 缺失回退纯色） */
static QPixmap texPixmapFor(const QString &id, int w, int h)
{
    static QMutex mu;
    static QHash<QString, QPixmap> cache;
    const QString key = QString("%1-%2x%3").arg(id).arg(w).arg(h);
    QMutexLocker lk(&mu);
    const QHash<QString, QPixmap>::const_iterator it = cache.constFind(key);
    if (it != cache.constEnd()) return it.value();
    const QString fp =
        QCoreApplication::applicationDirPath() + QString::fromLatin1("/tex/") + id + ".jpg";
    QPixmap src(fp);
    if (src.isNull()) return QPixmap();
    QPixmap pm = src.scaled(w, h, Qt::IgnoreAspectRatio, Qt::SmoothTransformation);
    if (cache.size() > 24) cache.clear();
    cache.insert(key, pm);
    return pm;
}

bool ReaderWindow::openBook(const QString &path, int jumpPage)
{
    /* 单飞行渲染在途时等它落地（避免关书 UAF；单飞行保证最多等一页） */
    while (!m_flight.isEmpty() || m_auxJobs.load() > 0)
        QApplication::processEvents(); /* v1.8.0：等滚动飞行+翻页/预取辅助任务落地再动 doc */
    if (m_doc) { hr_qt_close(m_doc); m_doc = nullptr; }
    m_path = path;
    m_bookName = QFileInfo(path).fileName();
    /* P2 适配（B3）：EPUB 加速器开书（损坏/缺失回退普通打开） */
    m_doc = nullptr;
    if (QFileInfo(path).isFile()) /* v0.104.0：全格式尝试 accel（无文件自然回退） */
    {
        const QString acc = accelPathFor(path);
        if (!acc.isEmpty() && QFileInfo::exists(acc))
        {
            m_doc = hr_qt_open_accel(hr::openablePath(path).toUtf8().constData(),
                                     acc.toUtf8().constData());
            if (m_doc) evt("accel_open", "hit");
        }
    }
    if (!m_doc)
        m_doc = hr_qt_open(hr::openablePath(path).toUtf8().constData());
    if (!m_doc)
    {
        evt("book_open_fail", "path=" + path.left(120) + " err=" +
                                   QString::fromUtf8(hr_qt_last_error()).left(120));
        return false;
    }
    return afterOpen(jumpPage);
}

/* v0.104.0（P1-4）：转换缓存损坏探针记忆（probe.ini；worker 线程专用，互斥保护）。
 * kept 记忆避免"真坏书每次打开都重转"；对齐安卓 codec_probe 语义。 */
static bool convProbeAllowed(const QString &origPath)
{
    static QMutex mu;
    QMutexLocker lk(&mu);
    QSettings st(dataDir() + QString::fromLatin1("/cache/probe.ini"), QSettings::IniFormat);
    return !st.contains(
        QString::fromLatin1("kept/") +
        QString::fromLatin1(QCryptographicHash::hash(origPath.toUtf8(), QCryptographicHash::Md5)
                                .toHex()
                                .left(12)));
}

static void convProbeKeep(const QString &origPath)
{
    static QMutex mu;
    QMutexLocker lk(&mu);
    QSettings st(dataDir() + QString::fromLatin1("/cache/probe.ini"), QSettings::IniFormat);
    st.setValue(QString::fromLatin1("kept/") +
                    QString::fromLatin1(QCryptographicHash::hash(origPath.toUtf8(), QCryptographicHash::Md5)
                                            .toHex()
                                            .left(12)),
                1);
}

/* azw3/zip 产物是"键目录/内文件"：删整目录；其余删单文件 */
static void convArtifactRemove(const QString &openable, const QString &origPath)
{
    if (openable == origPath) return;
    const QString dir = QFileInfo(openable).absolutePath();
    if (dir.contains(QString::fromLatin1("/cache/convert/azw3-")) ||
        dir.contains(QString::fromLatin1("/cache/zip/")))
        QDir(dir).removeRecursively();
    else
        QFile::remove(openable);
}

/* v0.104.0：前 3 页可读文字探针（对齐安卓 probeTextReadable：纯文本垃圾缓存
 * 也能"打开且有页"，0 页判定漏检——改为检查首 3 页是否存在非空白段落） */
static int probeParaCb(void *ud, const char *para)
{
    int *hits = (int *)ud;
    if (para)
    {
        for (const char *p = para; *p; ++p)
        {
            const char c = *p;
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n')
            {
                (*hits)++;
                break;
            }
        }
    }
    return 1; /* 继续 */
}

static bool convTextReadable(void *doc, int pages)
{
    if (!doc || pages <= 0) return false;
    const int n = pages < 3 ? pages : 3;
    for (int p = 0; p < n; p++)
    {
        int hits = 0;
        hr_qt_page_paragraphs(doc, p, probeParaCb, &hits);
        if (hits > 0) return true;
    }
    return false;
}

/* ---------- v0.103.0 P0-1：GUI 后台打开（对齐安卓 OpenPrefetch+FirstPaintGate 体验：
   打开/转换/整书排版在 worker，UI 只做装配与首渲；遮罩挡输入给反馈） ---------- */

void ReaderWindow::showOpenOverlay()
{
    if (!m_openOv) return;
    m_openOvLb->setText(hr::l10n::s("正在打开…", "Opening…"));
    m_openOv->setGeometry(rect());
    m_openOv->raise();
    m_openOv->show();
}

void ReaderWindow::hideOpenOverlay()
{
    if (m_openOv) m_openOv->hide();
}

bool ReaderWindow::openBookAsync(const QString &path, int jumpPage)
{
    if (m_openBusy.load())
    {
        evt("open_busy", path.left(60));
        return false;
    }
    /* 单飞行渲染在途时等它落地（最多一页，与同步路径同守卫） */
    while (!m_flight.isEmpty() || m_auxJobs.load() > 0)
        QApplication::processEvents(); /* v1.8.0：等滚动飞行+翻页/预取辅助任务落地再动 doc */
    /* v0.104.0：等在途后台重排落地（旧 doc 由本流程关，互锁防 UAF） */
    if (m_relayoutBusy.load() || m_accelSaving.load())
    {
        m_relayoutGen.fetch_add(1); /* 作废挂起的重排完成回调 */
        const qint64 tw = QDateTime::currentMSecsSinceEpoch();
        while ((m_relayoutBusy.load() || m_accelSaving.load()) &&
               QDateTime::currentMSecsSinceEpoch() - tw < 10000)
            QApplication::processEvents();
    }
    m_openBusy.store(true);
    m_asyncFailed.store(false);
    const int gen = m_openGen.fetch_add(1) + 1;
    /* v0.107.0（P1-5）：窗口计数 phase2 在飞时先等它退出（gen 已变，它会很快自断），
     * 再移交旧 doc——避免后台计数踩已释放句柄 */
    {
        const qint64 tw2 = QDateTime::currentMSecsSinceEpoch();
        while (m_phase2Busy.load() &&
               QDateTime::currentMSecsSinceEpoch() - tw2 < 15000)
            QApplication::processEvents();
        m_phase2Busy.store(false); /* 超时兜底清位（gen 检查已使旧 phase2 无害） */
    }

    /* UI 线程先定好的量：视口/固定版式/CSS/首开首页/版心宽（worker 不碰 widget 与 QSettings） */
    if (m_smokeW > 0)
    {
        m_viewW = m_smokeW;
        m_viewH = m_smokeH;
    }
    else
    {
        m_viewW = m_scroll->viewport()->width();
        m_viewH = m_scroll->viewport()->height();
        if (m_viewW < 300) { m_viewW = width() - 40; m_viewH = height() - 160; }
    }
    m_fixedLayout = QStringList{"pdf", "djvu", "cbz", "xps"}
                        .contains(QFileInfo(path).suffix().toLower());
    m_zoom = 1.0;
    /* v1.9.1（修复）：css 下沉 worker——set_css 是 g_glock ctx 级操作，warmer
     * 预热排版占 g_glock 时 UI 线程调用=整窗冻结；worker 在 layout 前设置，
     * 语义不变。此处只在 UI 线程定格成员取值。 */
    const QByteArray cssUtf8 = userCss().toUtf8();
    const int useDocCss = m_useDocCss ? 1 : 0;
    const bool openFirst =
        QSettings(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat)
            .value("reader/openFirstPage", false)
            .toBool();
    const int layW = maxCharsWidth();

    /* 旧书句柄移交 worker（此后 UI 不再持有；渲染已停 + 遮罩挡输入） */
    void *oldDoc = m_doc;
    m_doc = nullptr;
    m_pages = 0;
    m_page = 0;
    m_img = QImage();
    m_img2 = QImage();
    m_pfCache.clear();
    m_path = path;
    m_bookName = QFileInfo(path).fileName();
    updateTitle();
    showOpenOverlay();
    evt("open_begin", "path=" + path.left(120));

    QPointer<ReaderWindow> gw(this);
    const int em = m_em;
    const int viewW = m_viewW, viewH = m_viewH;
    const int genV = gen;
    QThreadPool::globalInstance()->start(QRunnable::create(
        [gw, path, jumpPage, genV, em, viewW, viewH, layW, oldDoc, openFirst,
         cssUtf8, useDocCss]
    {
        const qint64 t0 = QDateTime::currentMSecsSinceEpoch();
        /* 转换层（txt/md 转码、zip 解包、azw3·大 mobi 转 EPUB、doc/docx/odt/rtf 转 HTML；命中即回） */
        const QString openable = hr::openablePath(path);
        const qint64 t1 = QDateTime::currentMSecsSinceEpoch();
        QString acc;
        void *doc = nullptr;
        if (QFileInfo(path).isFile()) /* v0.104.0：全格式尝试 accel */
        {
            acc = accelPathFor(path);
            if (!acc.isEmpty() && QFileInfo::exists(acc))
                doc = hr_qt_open_accel(openable.toUtf8().constData(),
                                       acc.toUtf8().constData());
        }
        if (!doc) doc = hr_qt_open(openable.toUtf8().constData());
        /* v1.15.13：转换产物打不开（典型：转换被中断留下的截断 EPUB/HTML）→ 删产物强制
         * 重转一次。缓存键只认源文件路径+大小+时间，坏产物会被永久当成命中，此后每次
         * 开书都失败（实测截断缓存：cannot recognize zip archive） */
        if (!doc && openable != path)
        {
            evt("conv_artifact_bad", "openable=" + openable.left(80));
            convArtifactRemove(openable, path);
            const QString retryPath = hr::openablePath(path, true);
            if (!retryPath.isEmpty())
                doc = hr_qt_open(retryPath.toUtf8().constData());
        }
        if (!gw)
        {
            if (doc) hr_qt_close(doc);
            if (oldDoc) hr_qt_close(oldDoc);
            return;
        }
        if (!doc)
        {
            if (oldDoc) hr_qt_close(oldDoc);
            const QString err = QString::fromUtf8(hr_qt_last_error());
            QMetaObject::invokeMethod(
                gw.data(),
                [gw, genV, err]
                { gw->finishOpen(nullptr, 0, 0, 0, 0, 0, 0, err, genV, false, 0,
                                 0, false); },
                Qt::QueuedConnection);
            return;
        }
        if (oldDoc) hr_qt_close(oldDoc); /* 旧书此刻已无人引用 */

        /* v0.103.0 P1-2：大 EPUB（>=100MB）排版期延迟图解（ctx 级开关，排版完即关） */
        const bool bigEpub =
            QFileInfo(path).suffix().toLower() == QString::fromLatin1("epub") &&
            QFileInfo(path).size() >= 100LL * 1024 * 1024;
        hr_qt_set_css(cssUtf8.constData(), useDocCss); /* v1.9.1：css 移入 worker */
        if (bigEpub) hr_qt_defer_images(1);
        hr_qt_layout(doc, (float)layW, (float)viewH, em);
        const qint64 t2 = QDateTime::currentMSecsSinceEpoch();
        int nc = 0;
        /* v0.107.0（P1-5）：窗口化计数（对齐安卓 uptoPage 本地档）——只数到
         * 「阅读页 + max(80, pg/4)」即开门，剩余章节由 phase2 后台补齐；
         * 无阅读进度/单章书无窗口可言，仍整书计数 */
        int cntPartialCh = 0; /* 窗口截断时已数完的章节数（phase2 起点） */
        bool cntPartial = false;
        int uptoPg = -1;
        if (jumpPage >= 0)
            uptoPg = jumpPage;
        else if (!openFirst)
        {
            int sp0 = 0, spc0 = 0, svw0 = 0;
            if (Db::loadProgress(path, &sp0, &spc0, &svw0) && sp0 > 0)
                uptoPg = sp0;
            else
                uptoPg = 0; /* 无进度=从首页读：窗口 80 页（对齐安卓 pg=0 档） */
        }
        auto countPages = [&](bool *partial, int *cdone, QVector<int> *per) -> int
        {
            int pg = 0;
            nc = hr_qt_count_chapters(doc);
            *partial = false;
            *cdone = 0;
            if (nc > 1)
            {
                const int limit =
                    uptoPg >= 0 ? uptoPg + qMax(160, uptoPg / 4) : 0x7fffffff; /* v1.15.13：v1.0.1 的
                     "初始前沿 80→160 页"当时只改在了同步 afterOpen（旧路径），后台开书路径漏改 */
                for (int ci = 0; ci < nc && gw; ci += 16)
                {
                    for (int k = ci; k < qMin(ci + 16, nc); k++)
                    {
                        const int cp = hr_qt_count_pages_of(doc, k);
                        pg += cp;
                        per->append(cp); /* v0.107.1：逐章表（phase2 续算/落盘） */
                        if (pg >= limit)
                        {
                            *partial = true;
                            *cdone = k + 1;
                            return pg;
                        }
                    }
                }
                if (pg <= 0) pg = hr_qt_count(doc);
            }
            else
                pg = hr_qt_count(doc);
            return pg;
        };
        QVector<int> chapPages;
        /* v0.107.1：先查分章页数表缓存——命中直接全量页数（免 30s 逐章重排） */
        const int ncPre = hr_qt_count_chapters(doc);
        QVector<int> cachedCh = loadCountCache(path, viewW, viewH, em, ncPre);
        const qint64 tcw0 = QDateTime::currentMSecsSinceEpoch();
        int pages;
        if (!cachedCh.isEmpty())
        {
            pages = 0;
            for (int v : cachedCh) pages += v;
            cntPartial = false;
            cntPartialCh = 0;
            /* v1.15.13：缓存命中的全量章页表同样交给 finishOpen——原在此丢弃，m_chapPages
             * 为空，此后每次重排都因"无表"退化成整书排版（v0.109.0 要避免的卡顿路径） */
            chapPages = cachedCh;
        }
        else
            pages = countPages(&cntPartial, &cntPartialCh, &chapPages);
        if (nc <= 0) nc = ncPre; /* 缓存命中路径补章数（打点/finishOpen 用） */
        evt(QString::fromLatin1("count_done"),
            QString::fromLatin1("ms=%1 chapters=%2 partial=%3 winCh=%4 cached=%5")
                .arg(QDateTime::currentMSecsSinceEpoch() - tcw0)
                .arg(nc)
                .arg(cntPartial ? 1 : 0)
                .arg(cntPartialCh)
                .arg(cachedCh.isEmpty() ? 0 : 1));
        /* v0.104.0（P1-4）：损坏探针——转换产物打不开、0 页、或前 3 页无可读文字=坏缓存：
         * 删产物强制重转一次；重转后能开且有页则保留打开态（误报保护，如图文为主的文档），
         * 仍坏记忆 kept 不再每次重试（对齐安卓 AbstractCodecContext 探针语义） */
        /* v1.15.13：纯图产物（CBR/CBZ 及图片）不做"前 3 页有文字"判定——图片集永远没有
         * 文字段，原逻辑首开必判坏缓存：删产物→整本重解一遍→再判→才记忆保留 */
        const QString innerExt = QFileInfo(openable).suffix().toLower();
        const bool imageOnly =
            QFileInfo(path).suffix().toLower() == QString::fromLatin1("cbr") ||
            QStringList{"cbz", "cbr", "jpg", "jpeg", "png", "gif", "bmp", "webp"}
                .contains(innerExt);
        if (openable != path && convProbeAllowed(path))
        {
            bool probeBad = pages <= 0 || (!imageOnly && !convTextReadable(doc, pages));
            if (probeBad)
            {
                evt("conv_probe_fail",
                    QString("openable=%1 pages=%2").arg(openable.left(80)).arg(pages));
                hr_qt_close(doc);
                doc = nullptr;
                convArtifactRemove(openable, path);
                const QString retry = hr::openablePath(path, true);
                if (!retry.isEmpty())
                {
                    doc = hr_qt_open(retry.toUtf8().constData());
                    if (doc)
                    {
                        hr_qt_layout(doc, (float)layW, (float)viewH, em);
                        pages = countPages(&cntPartial, &cntPartialCh, &chapPages);
                    }
                }
                if (!doc || pages <= 0 || (!imageOnly && !convTextReadable(doc, pages)))
                {
                    convProbeKeep(path);
                    evt("conv_probe_kept", "path=" + path.left(90));
                    if (doc && pages > 0)
                        evt("conv_probe_kept_open", QString("pages=%1").arg(pages));
                    else if (doc)
                    {
                        hr_qt_close(doc);
                        doc = nullptr;
                    }
                }
            }
        }
        if (bigEpub) hr_qt_defer_images(0);
        const qint64 t3 = QDateTime::currentMSecsSinceEpoch();

        /* 元数据/最近（Db 每线程独立连接；remote:// 不入册不进同步） */
        if (!path.startsWith(QString::fromLatin1("remote://")))
        {
            Db::upsertFileMeta(path, QString::fromUtf8(hr_qt_meta(doc, 0)), pages,
                               QFileInfo(path).size(),
                               QFileInfo(path).lastModified().toMSecsSinceEpoch());
            Db::touchRecent(path);
        }

        /* v0.103.0 P0-3 + v0.104.0：加速器保存移后台；仅对引擎支持的 pdf/epub 尝试保存
         * （其余格式 fz_save_accelerator 不支持，省一次失败调用） */
        bool accelOk = false;
        qint64 accelMs = 0;
        const QString accExt = QFileInfo(path).suffix().toLower();
        if (!acc.isEmpty() && (accExt == QString::fromLatin1("epub") ||
                               accExt == QString::fromLatin1("pdf")))
        {
            QElapsedTimer at;
            at.start();
            accelOk = (hr_qt_save_accel(doc, acc.toUtf8().constData()) == 0);
            accelMs = at.elapsed();
            if (accelOk) accelCleanup(path, acc);
        }

        /* 进度恢复（与 afterOpen 同语义；pct 回落需要新页数，在 worker 算好） */
        int restored = 0;
        if (jumpPage >= 0)
            restored = jumpPage;
        else
        {
            int sp = 0, spc = 0, svw = 0;
            if (Db::loadProgress(path, &sp, &spc, &svw) && spc > 0 && sp >= 0)
            {
                if (pages > 0 && sp < pages)
                    restored = sp;
                else if (pages > 0)
                    restored = qBound(0, (int)((double)sp / spc * pages), pages - 1);
            }
            if (openFirst) restored = 0;
        }

        if (!gw)
        {
            hr_qt_close(doc);
            return;
        }
        QMetaObject::invokeMethod(
            gw.data(),
            [gw, genV, doc, pages, nc, restored, t0, t1, t2, t3, accelOk, accelMs,
             cntPartial, cntPartialCh, chapPages]
            {
                gw->finishOpen(doc, pages, nc, restored, t1 - t0, t2 - t1, t3 - t2,
                               QString(), genV, accelOk, accelMs, cntPartialCh,
                               cntPartial, chapPages);
            },
            Qt::QueuedConnection);
    }));
    return true;
}

void ReaderWindow::finishOpen(void *doc, int pages, int chapters, int restored,
                              qint64 convMs, qint64 openMs, qint64 layoutMs,
                              const QString &err, int gen, bool accelOk, qint64 accelMs,
                              int partialFrom, bool partial, QVector<int> chapPages)
{
    m_chapPages = chapPages; /* v0.107.1：窗口期部分表（phase2 续算/落盘用） */
    if (gen != m_openGen.load())
    {
        if (doc) hr_qt_close(doc);
        m_openBusy.store(false);
        hideOpenOverlay();
        return;
    }
    if (!doc)
    {
        m_asyncFailed.store(true);
        m_openBusy.store(false);
        hideOpenOverlay();
        evt("book_open_fail", "path=" + m_path.left(120) + " err=" + err.left(120));
        emit openFailed(m_path);
        return;
    }
    m_doc = doc;
    m_pages = qMax(0, pages);
    m_page = qBound(0, restored, qMax(0, m_pages - 1));
    /* v1.8.0：渲染状态复位（多飞行/待渲/翻页异步/首帧门控） */
    m_flight.clear(); m_flightT0.clear(); m_flightSeq.clear(); m_pendQueue.clear();
    m_pageFlight = false; m_gatePending = false;
    m_cacheProg = nullptr;
    m_img = QImage();
    m_img2 = QImage();
    m_estH = m_viewH > 0 ? m_viewH : 1200; calibEstH(); /* v1.0.0：锚排版页高，防占位细条海 */
    buildStack();
    m_slider->setRange(0, qMax(0, m_pages - 1));
    m_slider->setValue(m_page);
    /* v1.15.12：页数已知立即显示——首次 onPageChanged 要等首渲/门控（大书"-"空窗数秒） */
    m_lbPos->setText(QString("%1 / %2").arg(m_page + 1).arg(m_pages));
    m_lastStatPage = m_page;
    m_statTimer.start();
    m_openBusy.store(false);
    if (m_scrollMode)
    {
        m_scroll->verticalScrollBar()->setValue(m_page * (m_estH));
        const qint64 tff = QDateTime::currentMSecsSinceEpoch();
        /* v1.8.0（P0-3）：首帧门控——遮罩等首个滚动页渲染成功（或 300ms 超时降级）
         * 再摘，消除"遮罩已摘、正文还是占位页码"的开书瞬间空白 */
        m_gatePending = true;
        fillVisible();
        evt(QString::fromLatin1("first_fill"),
            QString::fromLatin1("ms=%1 pages=%2")
                .arg(QDateTime::currentMSecsSinceEpoch() - tff)
                .arg(m_pages));
        QTimer::singleShot(300, this, [this]
        {
            if (m_gatePending)
            {
                m_gatePending = false;
                hideOpenOverlay();
                evt("first_gate_timeout", QString::fromLatin1("ms=300"));
            }
        });
    }
    else
    {
        const qint64 tff = QDateTime::currentMSecsSinceEpoch();
        renderNow(m_page); /* 同步首渲：返回即有首帧 */
        hideOpenOverlay();
        evt(QString::fromLatin1("first_fill"),
            QString::fromLatin1("ms=%1 pages=%2 pagemode=1")
                .arg(QDateTime::currentMSecsSinceEpoch() - tff)
                .arg(m_pages));
    }
    updateTitle();
    /* v0.103.0 P0-2：打开链路打点（open_begin→conv/open/layout→open_end；口径对齐安卓 BENCH） */
    evt("doc_open_done", QString("conv_ms=%1 open_ms=%2").arg(convMs).arg(openMs));
    evt("layout_done",
        QString("ms=%1 pages=%2 chapters=%3").arg(layoutMs).arg(m_pages).arg(chapters));
    evt("book_open", QString("pages=%1 em=%2 layout_ms=%3 open_ms=%4 conv_ms=%5 path=%6")
                         .arg(m_pages).arg(m_em).arg(layoutMs).arg(openMs).arg(convMs)
                         .arg(m_bookName.left(80)));
    evt("open_end", QString("total_ms=%1").arg(convMs + openMs + layoutMs));
    if (accelMs > 0)
        evt("accel_save",
            accelOk ? QString("ok ms=%1").arg(accelMs) : QString::fromLatin1("fail"));
    /* v0.107.0（P1-5）：窗口计数开门 → phase2 后台补齐剩余章节页数 */
    if (partial && m_pages > 0 && chapters > partialFrom)
        startPhase2Count(partialFrom, m_pages, chapters);
}

/* v0.107.0（P1-5）：phase2 后台补页——数完剩余章节（对齐安卓 phase2 分块推进），
 * 完成后回 UI 更新页数/滑条/书库。生命周期经 m_phase2Busy 互锁：开书启动点等待、
 * adoptDoc 等待、releaseEngineDoc 跳过；每章循环核对 openGen，换书即自断。 */
void ReaderWindow::startPhase2Count(int fromChapter, int partialPages, int chapters)
{
    const int gen = m_openGen.load();
    QPointer<ReaderWindow> gw(this);
    const QVector<int> pre = m_chapPages; /* 窗口期已数的章页数表（UI 线程拷贝） */
    const int vw = m_viewW, vh = m_viewH;
    const float emf = m_em;
    const QString pth = m_path; /* worker 直接落盘（UI 回调在退出时不执行） */
    m_phase2Busy.store(true);
    const qint64 t0 = QDateTime::currentMSecsSinceEpoch();
    evt("phase2_start",
        QString("from=%1 pages=%2 chapters=%3").arg(fromChapter).arg(partialPages).arg(chapters));
    QThreadPool::globalInstance()->start(QRunnable::create(
        [gw, gen, fromChapter, partialPages, chapters, t0, pre, vw, vh, emf, pth]
    {
        int extra = 0;
        QVector<int> all = pre;
        void *doc = nullptr;
        if (gw && gw->m_openGen.load() == gen)
        {
            doc = gw->m_doc; /* finishOpen(QueuedConnection) 之后读，此后 busy 互锁保护 */
            for (int k = fromChapter; k < chapters; k++)
            {
                if (!gw || gw->m_openGen.load() != gen)
                {
                    doc = nullptr;
                    break;
                }
                /* v1.0.1：撤除"1.5s 内有渲染请求即冻结"让路——用户边翻页边等时
                 * 补数完全停摆，已数窗口之外全是空白（v1.0.0 会话日志复证实锤）。
                 * 改靠引擎锁自然串行：逐章计数 20-56ms 与渲染 10-60ms 交错，互相
                 * 等锁的代价 ≤ 一次计数；补数前沿 ~百页/秒跟进，翻页追不上 */
                if (!gw || gw->m_openGen.load() != gen)
                {
                    doc = nullptr;
                    break;
                }
                const qint64 tpc = QDateTime::currentMSecsSinceEpoch();
                const int cp = hr_qt_count_pages_of(doc, k);
                extra += cp;
                all.append(cp);
                if ((k - fromChapter) % 4 == 3)
                    evt(QString::fromLatin1("phase2_step"),
                        QString::fromLatin1("ch=%1 ms=%2")
                            .arg(k)
                            .arg(QDateTime::currentMSecsSinceEpoch() - tpc));
                /* v1.0.1：每 16 章增量落地页数/标签——旧口径全部数完才扩一次，
                 * 大书前沿数十秒不可翻；增量后前沿持续生长（gen 守卫防与
                 * 字号重排竞态：重排会重建页表，旧布局的页数不得再扩入） */
                if ((k - fromChapter) % 16 == 15)
                {
                    QMetaObject::invokeMethod(
                        gw.data(),
                        [gw, gen, tot = partialPages + extra, chn = all.size(), tbl = all]
                        {
                            if (gw && gw->m_openGen.load() == gen)
                                gw->applyPhase2Pages(tot, chn, tbl);
                        },
                        Qt::QueuedConnection);
                }
            }
        }
        if (gw) gw->m_phase2Busy.store(false);
        if (!gw || gw->m_openGen.load() != gen || extra <= 0) return;
        const qint64 ms = QDateTime::currentMSecsSinceEpoch() - t0;
        /* v0.107.1：缓存与 evt 在 worker 落地（不依赖 UI 事件循环存活） */
        if (all.size() == chapters)
            saveCountCache(pth, vw, vh, int(emf), all);
        evt("phase2_pages", QString("total=%1 ms=%2").arg(partialPages + extra).arg(ms));
        QMetaObject::invokeMethod(
            gw.data(),
            [gw, gen, total = partialPages + extra, chapters, all]
            {
                /* v1.0.1：gen 守卫（同上，防重排竞态错扩） */
                if (gw && gw->m_openGen.load() == gen)
                    gw->applyPhase2Pages(total, chapters, all);
            },
            Qt::QueuedConnection);
    }));
}

void ReaderWindow::applyPhase2Pages(int total, int chapters, QVector<int> all)
{
    /* v1.15.13：章页表随补页同步增长（原调用方不传表，且这里在全表到达时把表清掉）——
     * 表停在窗口期那几章：读到窗口之外再改字号/最大化，重排按残表定位锚章，阅读位
     * 跳回窗口末章（实测 3000 页→79 页） */
    if (!all.isEmpty() && all.size() >= m_chapPages.size())
        m_chapPages = all;
    if (total <= m_pages) return;
    m_pages = total;
    /* v0.107.1 fix：窗口化开门后页数增长——滚动内容栈同步补建新增页条目，
     * 否则 fillVisible/requestPage 按 m_pages 索引 m_labels/m_labelW 越界，
     * 且滚动几何（滑条范围 vs 实际条目数）错乱拖动发飘 */
    if (m_scrollMode && m_stack && m_labels.size() < m_pages)
    {
        if (m_pages - m_labels.size() > 300)
            scheduleStackGrow(); /* v0.107.1：大批量分批建，防 UI 一次卡数百 ms */
        else
        {
            const int w = renderWidthPx();
            const qint64 tb0 = QDateTime::currentMSecsSinceEpoch();
            for (int i = m_labels.size(); i < m_pages; i++)
            {
                QLabel *lb = new QLabel;
                lb->setFixedSize(w, m_estH);
                initPlaceholder(lb, i); /* v1.8.0（P0-2） */
                stackAppendLabel(m_stack, lb, w, m_estH);
                m_labels.append(lb);
                m_labelW.append(-1);
            }
            evt(QString::fromLatin1("ui_stack_grow"),
                QString::fromLatin1("to=%1 ms=%2")
                    .arg(m_pages)
                    .arg(QDateTime::currentMSecsSinceEpoch() - tb0));
            syncStackHeight(); /* v1.6.1 */
        }
    }
    m_slider->setRange(0, qMax(0, m_pages - 1));
    /* v1.15.12：页数增长即刷新页码栏分母（原只刷标题，页码栏停在补页前总数） */
    m_lbPos->setText(QString("%1 / %2").arg(m_page + 1).arg(m_pages));
    /* 书库页数从窗口期写入的部分值补正为全量；v1.9.0（P1-6）：远程书同样补登
     * （size 从 file_meta 保真回填，countCache 键依赖 size 不得清零） */
    if (m_doc && !m_path.startsWith(QString::fromLatin1("remote://")))
    {
        Db::upsertFileMeta(m_path, QString::fromUtf8(hr_qt_meta(m_doc, 0)), m_pages,
                           QFileInfo(m_path).size(),
                           QFileInfo(m_path).lastModified().toMSecsSinceEpoch());
    }
    else if (m_doc && m_path.startsWith(QString::fromLatin1("remote://")))
    {
        long long sz = 0;
        for (const Db::MetaRow &m : Db::listLibrary())
            if (m.path == m_path)
            {
                sz = m.size;
                break;
            }
        Db::upsertFileMeta(m_path, m_bookName, m_pages, sz,
                           QDateTime::currentMSecsSinceEpoch());
    }
    updateTitle();
}

/* v0.107.1：滚动条目分批补建（200 条/40ms）——大书 phase2 完成时一次性建
 * 数千个 QLabel 会把 UI 卡住数百 ms 到秒级 */
void ReaderWindow::scheduleStackGrow()
{
    if (!m_scrollMode || !m_stack || m_labels.size() >= m_pages) return;
    const int w = renderWidthPx();
    const int target = qMin(m_pages, m_labels.size() + 200);
    const qint64 tb0 = QDateTime::currentMSecsSinceEpoch();
    while (m_labels.size() < target)
    {
        QLabel *lb = new QLabel;
        lb->setFixedSize(w, m_estH);
        initPlaceholder(lb, m_labels.size()); /* v1.8.0（P0-2） */
        stackAppendLabel(m_stack, lb, w, m_estH);
        m_labels.append(lb);
        m_labelW.append(-1);
    }
    evt(QString::fromLatin1("ui_stack_grow"),
        QString::fromLatin1("to=%1 ms=%2 batch=1")
            .arg(m_labels.size())
            .arg(QDateTime::currentMSecsSinceEpoch() - tb0));
    syncStackHeight(); /* v1.6.1：前沿随批次同步增长，用户可连续滚入 */
    if (m_labels.size() < m_pages)
        QTimer::singleShot(40, this, &ReaderWindow::scheduleStackGrow);
}

bool ReaderWindow::adoptDoc(void *doc, const QString &displayName, QObject *keepAlive,
                            int jumpPage, const QString &progressKey)
{
    while (!m_flight.isEmpty() || m_auxJobs.load() > 0)
        QApplication::processEvents(); /* v1.8.0：等滚动飞行+翻页/预取辅助任务落地再动 doc */
    while (m_phase2Busy.load()) /* v0.107.0（P1-5）：等窗口计数 phase2 落地再动 doc */
        QApplication::processEvents();
    if (m_relayoutBusy.load() || m_accelSaving.load()) /* v0.104.0：等在途后台重排落地 */
    {
        m_relayoutGen.fetch_add(1);
        const qint64 tw = QDateTime::currentMSecsSinceEpoch();
        while ((m_relayoutBusy.load() || m_accelSaving.load()) &&
               QDateTime::currentMSecsSinceEpoch() - tw < 10000)
            QApplication::processEvents();
    }
    if (m_doc) { hr_qt_close(m_doc); m_doc = nullptr; }
    m_doc = doc;
    m_path = progressKey.isEmpty() ? QString::fromLatin1("remote://") + displayName
                                   : progressKey;
    m_bookName = displayName;
    if (keepAlive) keepAlive->setParent(this); /* 会话随窗销毁 */
    if (!afterOpen(jumpPage)) return false;
    evt("remote_open", "name=" + displayName.left(80));
    return true;
}

/* v1.9.0（P1-6）：远程书后台装配——引擎句柄已在库侧开好（头尾预取即开，"保持即开"
 * 语义不变），排版/窗口计数移到 worker：UI 不冻结，遮罩实时显示块缓存下载进度
 * （原同步 afterOpen 在 UI 线程整书读流=整窗假死且无进度可见）。 */
bool ReaderWindow::adoptDocAsync(void *doc, const QString &displayName, QObject *keepAlive,
                                 int jumpPage, const QString &progressKey)
{
    if (m_openBusy.load())
    {
        evt("open_busy", displayName.left(60));
        return false;
    }
    while (!m_flight.isEmpty() || m_auxJobs.load() > 0)
        QApplication::processEvents();
    if (m_relayoutBusy.load() || m_accelSaving.load())
    {
        m_relayoutGen.fetch_add(1); /* 作废挂起的重排完成回调 */
        const qint64 tw = QDateTime::currentMSecsSinceEpoch();
        while ((m_relayoutBusy.load() || m_accelSaving.load()) &&
               QDateTime::currentMSecsSinceEpoch() - tw < 10000)
            QApplication::processEvents();
    }
    m_openBusy.store(true);
    m_asyncFailed.store(false);
    const int gen = m_openGen.fetch_add(1) + 1;
    {
        const qint64 tw2 = QDateTime::currentMSecsSinceEpoch();
        while (m_phase2Busy.load() &&
               QDateTime::currentMSecsSinceEpoch() - tw2 < 15000)
            QApplication::processEvents();
        m_phase2Busy.store(false);
    }
    if (m_smokeW > 0)
    {
        m_viewW = m_smokeW;
        m_viewH = m_smokeH;
    }
    else
    {
        m_viewW = m_scroll->viewport()->width();
        m_viewH = m_scroll->viewport()->height();
        if (m_viewW < 300) { m_viewW = width() - 40; m_viewH = height() - 160; }
    }
    m_fixedLayout = QStringList{"pdf", "djvu", "cbz", "xps"}
                        .contains(QFileInfo(displayName).suffix().toLower());
    m_zoom = 1.0;
    /* v1.9.1（修复）：css 下沉 worker（同 openBookAsync——UI 线程不再碰 g_glock） */
    const QByteArray cssUtf8 = userCss().toUtf8();
    const int useDocCss = m_useDocCss ? 1 : 0;
    const bool openFirst =
        QSettings(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat)
            .value("reader/openFirstPage", false)
            .toBool();
    const int layW = maxCharsWidth();
    void *oldDoc = m_doc;
    m_doc = nullptr;
    m_pages = 0;
    m_page = 0;
    m_img = QImage();
    m_img2 = QImage();
    m_pfCache.clear();
    m_flight.clear(); m_flightT0.clear(); m_flightSeq.clear(); m_pendQueue.clear();
    m_pageFlight = false; m_gatePending = false;
    m_cacheProg = dynamic_cast<hr::CacheProgressSource *>(keepAlive); /* v1.9.0（P1-6） */
    m_path = progressKey.isEmpty() ? QString::fromLatin1("remote://") + displayName
                                   : progressKey;
    m_bookName = displayName;
    if (keepAlive) keepAlive->setParent(this); /* 会话随窗销毁（BcsHolder） */
    updateTitle();
    showOpenOverlay();
    /* v1.9.0（P1-6）：遮罩显示块缓存进度（500ms 轮询；finishOpen 摘遮罩后自停） */
    {
        QTimer *ppt = new QTimer(this);
        connect(ppt, &QTimer::timeout, this, [this, ppt]
        {
            if (!m_openOv || !m_openOv->isVisible() || !m_cacheProg)
            {
                ppt->stop();
                ppt->deleteLater();
                return;
            }
            const long long tot = m_cacheProg->cacheTotalBytes();
            if (tot > 0)
            {
                const int pct = (int)qMin<long long>(
                    100, m_cacheProg->cacheCachedBytes() * 100 / tot);
                m_openOvLb->setText(hr::l10n::s("正在打开… 已缓存 %1%",
                                                "Opening… cached %1%")
                                        .arg(pct));
            }
        });
        ppt->start(500);
    }
    evt("open_begin", "remote=" + m_path.left(120));
    QPointer<ReaderWindow> gw(this);
    const int em = m_em;
    const int viewW = m_viewW, viewH = m_viewH;
    const int genV = gen;
    QThreadPool::globalInstance()->start(QRunnable::create(
        [gw, doc, oldDoc, genV, em, viewW, viewH, layW, jumpPage, openFirst,
         pth = m_path, cssUtf8, useDocCss]
    {
        const qint64 t0 = QDateTime::currentMSecsSinceEpoch();
        if (oldDoc) hr_qt_close(oldDoc); /* 渲染已排水，旧书此刻无人引用 */
        hr_qt_set_css(cssUtf8.constData(), useDocCss); /* v1.9.1：css 移入 worker */
        hr_qt_layout(doc, (float)layW, (float)viewH, em);
        const qint64 t2 = QDateTime::currentMSecsSinceEpoch();
        int nc = 0;
        int cntPartialCh = 0;
        bool cntPartial = false;
        int uptoPg = -1;
        if (jumpPage >= 0)
            uptoPg = jumpPage;
        else if (!openFirst)
        {
            int sp0 = 0, spc0 = 0, svw0 = 0;
            if (Db::loadProgress(pth, &sp0, &spc0, &svw0) && sp0 > 0)
                uptoPg = sp0;
            else
                uptoPg = 0;
        }
        auto countPages = [&](bool *partial, int *cdone, QVector<int> *per) -> int
        {
            int pg = 0;
            nc = hr_qt_count_chapters(doc);
            *partial = false;
            *cdone = 0;
            if (nc > 1)
            {
                const int limit =
                    uptoPg >= 0 ? uptoPg + qMax(3, qMin(10, uptoPg / 4)) : 0x7fffffff;
                for (int ci = 0; ci < nc && gw; ci += 16)
                {
                    for (int k = ci; k < qMin(ci + 16, nc); k++)
                    {
                        const int cp = hr_qt_count_pages_of(doc, k);
                        pg += cp;
                        per->append(cp);
                        if (pg >= limit)
                        {
                            *partial = true;
                            *cdone = k + 1;
                            return pg;
                        }
                    }
                }
                if (pg <= 0) pg = hr_qt_count(doc);
            }
            else
                pg = hr_qt_count(doc);
            return pg;
        };
        QVector<int> chapPages;
        const int ncPre = hr_qt_count_chapters(doc);
        QVector<int> cachedCh = loadCountCache(pth, viewW, viewH, em, ncPre);
        const qint64 tcw0 = QDateTime::currentMSecsSinceEpoch();
        int pages;
        if (!cachedCh.isEmpty())
        {
            pages = 0;
            for (int v : cachedCh) pages += v;
            cntPartial = false;
            cntPartialCh = 0;
            /* v1.15.13：缓存命中的全量章页表同样交给 finishOpen——原在此丢弃，m_chapPages
             * 为空，此后每次重排都因"无表"退化成整书排版（v0.109.0 要避免的卡顿路径） */
            chapPages = cachedCh;
        }
        else
            pages = countPages(&cntPartial, &cntPartialCh, &chapPages);
        if (nc <= 0) nc = ncPre;
        evt(QString::fromLatin1("count_done"),
            QString::fromLatin1("ms=%1 chapters=%2 partial=%3 winCh=%4 cached=%5 remote=1")
                .arg(QDateTime::currentMSecsSinceEpoch() - tcw0)
                .arg(nc)
                .arg(cntPartial ? 1 : 0)
                .arg(cntPartialCh)
                .arg(cachedCh.isEmpty() ? 0 : 1));
        /* v1.9.0（P1-6）：远程开书即登记页数（库侧 upsert 只有 size；worker 补齐） */
        if (pages > 0)
        {
            long long sz = 0;
            for (const Db::MetaRow &m : Db::listLibrary())
                if (m.path == pth)
                {
                    sz = m.size;
                    break;
                }
            Db::upsertFileMeta(pth, gw ? gw->m_bookName : pth.mid(9), pages, sz,
                               QDateTime::currentMSecsSinceEpoch());
        }
        int restored = 0;
        if (jumpPage >= 0)
            restored = jumpPage;
        else
        {
            int sp = 0, spc = 0, svw = 0;
            if (Db::loadProgress(pth, &sp, &spc, &svw) && spc > 0 && sp >= 0)
            {
                if (pages > 0 && sp < pages)
                    restored = sp;
                else if (pages > 0)
                    restored = qBound(0, (int)((double)sp / spc * pages), pages - 1);
            }
            if (openFirst) restored = 0;
        }
        if (!gw)
        {
            hr_qt_close(doc);
            return;
        }
        QMetaObject::invokeMethod(
            gw.data(),
            [gw, genV, doc, pages, nc, restored, t0, t2, cntPartial, cntPartialCh,
             chapPages]
            {
                gw->finishOpen(doc, pages, nc, restored, 0, t2 - t0, 0,
                               QString(), genV, false, 0, cntPartialCh,
                               cntPartial, chapPages);
            },
            Qt::QueuedConnection);
    }));
    return true;
}

bool ReaderWindow::afterOpen(int jumpPage)
{
    /* 视口（smoke 用固定值；否则取窗口内容区） */
    if (m_smokeW > 0)
    {
        m_viewW = m_smokeW;
        m_viewH = m_smokeH;
    }
    else
    {
        m_viewW = m_scroll->viewport()->width();
        m_viewH = m_scroll->viewport()->height();
        if (m_viewW < 300) { m_viewW = width() - 40; m_viewH = height() - 160; }
    }
    /* P2 适配（B7）：固定版式判定（页面缩放分派）；每开书重置缩放 */
    m_fixedLayout = QStringList{"pdf", "djvu", "cbz", "xps"}
                        .contains(QFileInfo(m_path).suffix().toLower());
    m_zoom = 1.0;
    m_chapPages.clear(); /* v1.15.13：换书清旧表（同窗重开时上一本的章页表不得残留） */
    applyUserCss(); /* v0.59.0：排版偏好 user CSS（layout 前生效） */
    qint64 t0 = QDateTime::currentMSecsSinceEpoch();
    hr_qt_layout(m_doc, (float)maxCharsWidth(), (float)m_viewH, m_em);
    const qint64 tct0 = QDateTime::currentMSecsSinceEpoch();
    /* P2 适配（B4）：分章渐进计数（16 章一批、批间抽事件防冻结；对齐 WIN countChapters）。
     * v0.107.0（P1-5 补齐）：同步路径同样窗口化——远程档 upto+max(3,min(10,upto/4))、
     * 本地档 upto+max(80,upto/4)（对齐安卓两档）；截断即开门，剩余章由 phase2 补齐。
     * 冒烟（m_smokeW>0）豁免：全本搜索等探针依赖全量页数。 */
    const int nc = hr_qt_count_chapters(m_doc);
    if (nc > 1)
    {
        /* v0.107.1：先查分章页数表缓存（命中=免逐章重排，直开大书不再卡） */
        QVector<int> cachedCh =
            loadCountCache(m_path, m_viewW, m_viewH, int(m_em), nc);
        if (!cachedCh.isEmpty())
        {
            m_pages = 0;
            for (int v : cachedCh) m_pages += v;
            m_chapPages = cachedCh; /* v1.15.13：同上（同步路径） */
            m_slider->setRange(0, qMax(0, m_pages - 1));
            evt(QString::fromLatin1("count_done"),
                QString::fromLatin1("ms=0 chapters=%1 pages=%2 cached=1 sync=1")
                    .arg(nc)
                    .arg(m_pages));
        }
        else
        {
        int uptoPg = jumpPage >= 0 ? jumpPage : -1;
        if (uptoPg < 0)
        {
            int sp0 = 0, spc0 = 0, svw0 = 0;
            if (Db::loadProgress(m_path, &sp0, &spc0, &svw0) && sp0 > 0)
                uptoPg = sp0;
            else
                uptoPg = 0;
        }
        const bool remotePath = m_path.startsWith(QString::fromLatin1("remote://"));
        const int limit =
            m_smokeW > 0
                ? 0x7fffffff
                : (remotePath ? uptoPg + qMax(3, qMin(10, uptoPg / 4))
                              : uptoPg + qMax(160, uptoPg / 4)); /* v1.0.1：初始前沿 80→160 页 */
        m_pages = 0;
        int cdone = 0;
        bool partial = false;
        QVector<int> per;
        for (int ci = 0; ci < nc; ci += 16)
        {
            const int be = qMin(ci + 16, nc);
            for (int k = ci; k < be; k++)
            {
                const int cp = hr_qt_count_pages_of(m_doc, k);
                m_pages += cp;
                per.append(cp);
                if (m_smokeW <= 0 && m_pages >= limit)
                {
                    partial = true;
                    cdone = k + 1;
                    break;
                }
            }
            if (partial) break;
            m_slider->setRange(0, qMax(0, m_pages - 1));
            QApplication::processEvents();
        }
        if (m_pages <= 0) m_pages = hr_qt_count(m_doc);
        m_slider->setRange(0, qMax(0, m_pages - 1));
        if (partial && m_pages > 0 && nc > cdone)
        {
            m_chapPages = per; /* v0.107.1：窗口部分表交 phase2 续算/落盘 */
            startPhase2Count(cdone, m_pages, nc);
        }
        else if (!partial && m_pages > 0)
        {
            m_chapPages = per; /* v1.15.13：全量表留用（原不存，重排走整书排版） */
            saveCountCache(m_path, m_viewW, m_viewH, int(m_em), per); /* 全量表落盘 */
        }
        }
    }
    else
        m_pages = hr_qt_count(m_doc);
    qint64 t1 = QDateTime::currentMSecsSinceEpoch();
    evt("layout_chapters", QString("chapters=%1").arg(nc));
    evt(QString::fromLatin1("count_done"),
        QString::fromLatin1("ms=%1 chapters=%2 pages=%3 sync=1")
            .arg(QDateTime::currentMSecsSinceEpoch() - tct0)
            .arg(nc)
            .arg(m_pages));
    evt("book_open", QString("pages=%1 em=%2 layout_ms=%3 path=%4")
                         .arg(m_pages).arg(m_em).arg(t1 - t0).arg(m_bookName.left(80)));

    /* 元数据/最近（与 Java 版同库；remote:// 不入册不进同步） */
    if (!m_path.startsWith("remote://"))
    {
        Db::upsertFileMeta(m_path, QString::fromUtf8(hr_qt_meta(m_doc, 0)), m_pages,
                           QFileInfo(m_path).size(),
                           QFileInfo(m_path).lastModified().toMSecsSinceEpoch());
        Db::touchRecent(m_path);
    }

    m_page = 0;
    if (jumpPage >= 0) m_page = jumpPage;
    else
    {
        int sp = 0, spc = 0, svw = 0;
        if (Db::loadProgress(m_path, &sp, &spc, &svw) && spc > 0 && sp >= 0)
        {
            if (sp < m_pages)
            {
                m_page = sp;
                evt("progress_restore", QString("page=%1/%2").arg(sp + 1).arg(spc));
            }
            else if (m_pages > 0)
            {
                /* P1 适配（B11）：页数变化（跨端/字号）按百分比回落（对齐 WIN，防丢位置） */
                m_page = qBound(0, (int)((double)sp / spc * m_pages), m_pages - 1);
                evt("progress_restore_pct",
                    QString("sp=%1 spc=%2 new=%3/%4")
                        .arg(sp).arg(spc).arg(m_page + 1).arg(m_pages));
            }
        }
    }
    /* U4：打开第 1 页（reader/openFirstPage，忽略上次进度） */
    if (jumpPage < 0 &&
        QSettings(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat)
            .value("reader/openFirstPage", false)
            .toBool())
        m_page = 0;

    /* v1.8.0：渲染状态复位（多飞行/待渲/翻页异步/首帧门控） */
    m_flight.clear(); m_flightT0.clear(); m_flightSeq.clear(); m_pendQueue.clear();
    m_pageFlight = false; m_gatePending = false;
    m_cacheProg = nullptr;
    m_img = QImage();
    m_img2 = QImage();
    m_pfCache.clear(); /* v0.103.0：邻页预取随书/排版换代 */
    m_estH = m_viewH > 0 ? m_viewH : 1200; calibEstH(); /* v1.0.0：锚排版页高，防占位细条海 */
    buildStack();
    m_slider->setRange(0, qMax(0, m_pages - 1));
    m_slider->setValue(m_page);
    /* v1.15.12：页数已知立即显示——首次 onPageChanged 要等首渲/门控（大书"-"空窗数秒） */
    m_lbPos->setText(QString("%1 / %2").arg(m_page + 1).arg(m_pages));
    m_lastStatPage = m_page;
    m_statTimer.start();
    if (m_scrollMode)
    {
        m_scroll->verticalScrollBar()->setValue(m_page * (m_estH));
        fillVisible();
    }
    else renderNow(m_page);
    updateTitle();
    /* P2 适配（B3）+ v0.107.0（P2-7）：EPUB 排版加速器保存改后台线程（原 UI 线程
     * 同步，大书序列化数百 ms 卡开门尾）；复用 m_relayoutBusy 互锁防 doc 早关 */
    if (!m_path.startsWith(QString::fromLatin1("remote://")) &&
        QFileInfo(m_path).suffix().toLower() == QString::fromLatin1("epub"))
    {
        const QString acc = accelPathFor(m_path);
        if (!acc.isEmpty())
        {
            void *doc = m_doc;
            QPointer<ReaderWindow> gw(this);
            /* v1.15.13：原借用 m_relayoutBusy——保存期间（大书数百 ms~秒级）滚动模式
             * fillVisible 整段让路且结束后不补渲；也可能清掉刚启动的重排的 busy */
            m_accelSaving.store(true);
            QThreadPool::globalInstance()->start(QRunnable::create([gw, doc, acc]
            {
                QElapsedTimer at;
                at.start();
                const bool ok =
                    (hr_qt_save_accel(doc, acc.toUtf8().constData()) == 0);
                const qint64 ms = at.elapsed();
                if (!gw) return; /* 窗口已销毁：busy 成员随之消亡，无悬挂 */
                QMetaObject::invokeMethod(
                    gw.data(),
                    [gw, ok, ms]
                    {
                        if (!gw) return;
                        gw->m_accelSaving.store(false);
                        evt("accel_save",
                            ok ? QString("ok ms=%1 async").arg(ms)
                               : QString::fromLatin1("fail"));
                    },
                    Qt::QueuedConnection);
            }));
        }
    }
    return true;
}

void ReaderWindow::updateTitle()
{
    const QString nm = m_bookName.isEmpty() ? hr::l10n::s("(未打开)", "(no book)") : m_bookName;
    const QString t = QString("HowRead Qt — %1  %2/%3").arg(nm).arg(m_page + 1).arg(m_pages);
    if (t != m_lastTitle) /* v1.15.13：同题不重复 setWindowTitle（大书逐页刷新引发非客户区重绘风暴） */
    {
        m_lastTitle = t;
        setWindowTitle(t);
    }
    if (m_lbBook && m_lbBook->text() != nm)
        m_lbBook->setText(nm);
}

/* U3a 沉浸：全屏+隐藏顶/底栏；再切退出（F11/底部钮/Esc） */
void ReaderWindow::toggleImmersive()
{
    m_immersive = !m_immersive;
    if (m_immersive)
    {
        m_prevFull = window()->isFullScreen(); /* v0.75.0：内嵌时全屏打到宿主窗 */
        if (!m_prevFull) window()->showFullScreen();
        if (m_topBar) m_topBar->hide();
        if (m_botBar) m_botBar->hide();
        evt("immerse_on", QString());
    }
    else
    {
        if (!m_prevFull) window()->showNormal();
        if (m_topBar) m_topBar->show();
        if (m_botBar) m_botBar->show();
        evt("immerse_off", QString());
    }
}

/* ---------- 滚动容器 ---------- */

void ReaderWindow::buildStack()
{
    const qint64 tb0 = QDateTime::currentMSecsSinceEpoch();
    QWidget *content = new QWidget; /* v1.15.13：标签几何改手工管理（原 QVBoxLayout 级联见 m_stackY 注） */
    /* 检视修复：二次建栈（relayout/换书）前必须清旧表——旧 dangling QLabel 若留数组，
     * 下次 requestPage 的 QPointer 构造即撞 UAF（GUI 起书秒退根因，v0.58 潜伏） */
    m_labels.clear();
    m_labelW.clear();
    m_stackY.clear();
    m_lru.clear();          /* v1.8.0（P0-5）：随栈重建清 LRU/字节账 */
    m_labelBytes.clear();
    m_lastTop = -1;
    clearSelection(); /* v1.12.0：页内局部坐标随栈重建失效，选区一并清 */
    const int w = renderWidthPx();
    for (int i = 0; i < m_pages; i++)
    {
        QLabel *lb = new QLabel;
        lb->setFixedSize(w, m_estH);
        initPlaceholder(lb, i); /* v1.8.0（P0-2）：占位纸底+页码+载入提示 */
        stackAppendLabel(content, lb, w, m_estH);
        m_labels.append(lb);
        m_labelW.append(-1);
    }
    QWidget *old = m_scroll->takeWidget(); /* 收回所有权再删，避免双重释放 */
    delete old;
    m_stack = content;
    m_scroll->setWidget(m_stack);
    evt("stack_step", "post_setwidget"); /* v1.15.11：取证桩（量程塌缩/重锚发生在本调用内） */
    syncStackHeight(); /* v1.6.1：初始即按 layout 高装配 */
    evt("stack_step", "post_sync");
    evt(QString::fromLatin1("ui_stack"),
        QString::fromLatin1("entries=%1 ms=%2")
            .arg(m_pages)
            .arg(QDateTime::currentMSecsSinceEpoch() - tb0));
}


/* ---------- v0.83.0 W2 D6：裁白边（对齐 Compose trimWhite；翻页模式） ---------- */
static QImage trimWhiteImg(const QImage &in)
{
    if (in.isNull() || in.width() < 40 || in.height() < 40) return in;
    const QImage src = in.convertToFormat(QImage::Format_RGB32);
    const int W = src.width(), H = src.height();
    const int thr = 242; /* 灰度阈值：>= 视为白 */
    const int step = 2;  /* 隔行扫描提速 */
    int top = H, bottom = -1, left = W, right = -1;
    for (int y = 0; y < H && top >= H; y += step)
    {
        const QRgb *row = reinterpret_cast<const QRgb *>(src.constScanLine(y));
        for (int x = 0; x < W; x += step)
            if (qGray(row[x]) < thr) { top = y; break; }
    }
    if (top >= H) return in; /* 全白页 */
    for (int y = H - 1; y > top && bottom < 0; y -= step)
    {
        const QRgb *row = reinterpret_cast<const QRgb *>(src.constScanLine(y));
        for (int x = 0; x < W; x += step)
            if (qGray(row[x]) < thr) { bottom = y; break; }
    }
    if (bottom < 0) return in;
    for (int x = 0; x < W && left >= W; x += step)
        for (int y = top; y <= bottom; y += step)
        {
            const QRgb *row = reinterpret_cast<const QRgb *>(src.constScanLine(y));
            if (qGray(row[x]) < thr) { left = x; break; }
        }
    for (int x = W - 1; x > left && right < 0; x -= step)
        for (int y = top; y <= bottom; y += step)
        {
            const QRgb *row = reinterpret_cast<const QRgb *>(src.constScanLine(y));
            if (qGray(row[x]) < thr) { right = x; break; }
        }
    if (right < 0) return in;
    const int m = 8; /* 留 8px 呼吸边 */
    left = qMax(0, left - m);
    top = qMax(0, top - m);
    right = qMin(W - 1, right + m);
    bottom = qMin(H - 1, bottom + m);
    return in.copy(left, top, right - left + 1, bottom - top + 1);
}

/* v1.8.0（P0-2）：占位不空白——未渲染页=主题纸底色+弱化页码+载入提示（原只写页码
 * 无底色，视觉上就是"空白页"）。主题色随栈重建烘焙（主题切换会 buildStack）。 */
/* v1.15.13：滚动栈手工几何。原 QVBoxLayout 把 8000+ 个页标签全部纳入布局管理：任何一页
 * 渲染落地改高（setFixedSize）都会触发布局重算，把落地点之后的所有标签逐个搬移一遍——
 * 每次落地 O(N) 条 Move 事件，渐进渲染几千页 × 数千标签 = 数千万条事件（事件聚合实测
 * Move:QLabel 250–290 万条/2 秒），大书开书/落地 GUI 冻结 10–28 秒。改为前缀表 m_stackY
 * 记录每页 Y（末项=总高）：改高只做 O(N) 整数加法 + 视口附近几十个标签的真实搬移；
 * 视口外的几何延迟到滚近时（fillVisible→stackSyncGeomVisible）再落位。 */
void ReaderWindow::stackAppendLabel(QWidget *stack, QLabel *lb, int w, int h)
{
    const int y = m_stackY.isEmpty() ? 0 : m_stackY.last(); /* 末项=当前总高，即新页 Y */
    m_stackY.append(y + h); /* 追加后末项仍=总高 */
    if (stack)
    {
        lb->setParent(stack);
        lb->setGeometry(0, y, w, h);
        if (stack->isVisible())
            lb->show();
    }
}

void ReaderWindow::stackSyncGeom(int y0, int y1)
{
    if (!m_stack || m_stackY.isEmpty()) return;
    const int n = m_stackY.size() - 1;
    for (int i = 0; i < n; i++)
    {
        if (m_stackY[i + 1] <= y0) continue;
        if (m_stackY[i] >= y1) break;
        QLabel *lb = i < m_labels.size() ? m_labels[i] : nullptr;
        if (lb && lb->y() != m_stackY[i])
            lb->move(0, m_stackY[i]);
    }
}

void ReaderWindow::stackSyncGeomVisible()
{
    if (!m_scroll || !m_stack || m_stackY.isEmpty()) return;
    const int top = m_scroll->verticalScrollBar()->value();
    const int vh = m_scroll->viewport()->height();
    stackSyncGeom(top - vh, top + 2 * vh); /* 视口上下各放一屏余量（锚定/预取读 y） */
}

void ReaderWindow::stackAfterResize(int page, int newH)
{
    if (page < 0 || page + 1 >= m_stackY.size()) return;
    const int dy = newH - (m_stackY[page + 1] - m_stackY[page]);
    if (dy != 0)
        for (int i = page + 1; i < m_stackY.size(); i++) m_stackY[i] += dy;
    QLabel *lb = page < m_labels.size() ? m_labels[page] : nullptr;
    if (lb && lb->y() != m_stackY[page])
        lb->move(0, m_stackY[page]); /* 本页自身落位（预取页可能落在视口外） */
    stackSyncGeomVisible(); /* 视口内其余标签随 dy 搬移（仅几十个，原为全量级联） */
}

void ReaderWindow::initPlaceholder(QLabel *lb, int page)
{
    const Theme &t = kThemes[m_theme];
    lb->setText(hr::l10n::s("第 %1 页 · 载入中…", "p. %1 · loading…").arg(page + 1));
    lb->setAlignment(Qt::AlignHCenter | Qt::AlignVCenter);
    lb->setAutoFillBackground(true);
    QPalette pal = lb->palette();
    pal.setColor(QPalette::Window, (QRgb)t.bg);
    const QColor bg((QRgb)t.bg), fg((QRgb)t.fg);
    pal.setColor(QPalette::WindowText,
                 QColor((2 * bg.red() + fg.red()) / 3,
                        (2 * bg.green() + fg.green()) / 3,
                        (2 * bg.blue() + fg.blue()) / 3)); /* fg 1/3 混入=弱化 */
    lb->setPalette(pal);
}

/* v1.8.0（P0-5）：位图字节预算 LRU 淘汰（替代 ±8 页硬淘汰）——视口±1 永不淘汰，
 * 其余按 m_lru（最旧在前）清到位图总字节 ≤ 预算（默认 64MB，qt-prefs.ini
 * reader/bitmapBudgetMB）。高分辨率不爆内存、低分辨率不过度回收；几何不变。 */
void ReaderWindow::evictByBudget(int first, int last)
{
    if (m_bitmapBudgetMB <= 0) return;
    const qint64 budget = (qint64)m_bitmapBudgetMB * 1024 * 1024;
    qint64 total = 0;
    for (auto it = m_labelBytes.constBegin(); it != m_labelBytes.constEnd(); ++it)
        total += it.value();
    if (total <= budget) return;
    const int lo = first - 1, hi = last + 1;
    QList<int> victims;
    for (int p : m_lru)
        if (!(p >= lo && p <= hi) && p >= 0 && p < m_labelW.size() &&
            p < m_labels.size() && m_labelW[p] >= 0 && m_labelBytes.contains(p))
            victims.append(p);
    for (int p : victims)
    {
        if (total <= budget) break;
        if (!m_labels[p]) continue;
        m_labels[p]->clear(); /* fixedSize 不变保几何（原 P2-9 口径） */
        initPlaceholder(m_labels[p], p);
        m_labelW[p] = -1;
        total -= m_labelBytes.value(p);
        m_labelBytes.remove(p);
        m_lru.removeAll(p);
    }
}

void ReaderWindow::fillVisible()
{
    if (m_openBusy.load() || !m_scrollMode || m_pages == 0) return;
    /* v1.15.11：重排过渡期禁锚——buildStack 的 setWidget/resize 会让滚动条量程
     * 瞬时塌缩，valueChanged→fillVisible 把 m_page 重锚回 0（真机日志实锤：重排
     * 未落地进度已被写回第 1 页，v1.15.10 残余"最大化后跳回首"根因）；过渡期旧
     * 栈渲染也让路，落位统一交 restorePosDeferred 按重排前页号收回 */
    if (m_stackSwap) return; /* v1.15.13：v1.15.11 的过渡期让路收窄为"换栈同步窗口"——量程塌缩
     * 只发生在 buildStack 内；重排等待期旧栈几何仍真实，改为只跟踪页码（见下） */
    stackSyncGeomVisible(); /* v1.15.13：滚近区域标签几何先落位（懒同步补齐） */
    const int top = m_scroll->verticalScrollBar()->value();
    const int bottom = top + m_scroll->viewport()->height();
    /* v1.8.0（P0-4）：滚动方向记忆（+1 下 / -1 上）——预取按方向加宽前方 */
    if (m_lastTop >= 0)
        m_scrollDir = top > m_lastTop ? 1 : (top < m_lastTop ? -1 : m_scrollDir);
    m_lastTop = top;
    /* v0.111.1：可见页定位改按真实标签几何累计——渲染完成会把标签改为真实高，
     * estH 均匀假设在短页/长页混排时漂移（短页越多漂越远）：向错误的页派渲染
     * 并清除区间外的标签，真实可见页反而无人渲染=空白。按标签逐个累计高即精确。 */
    int first = -1, last = -1;
    {
        int acc = 0;
        const int n = m_labels.size();
        for (int i = 0; i < n && i + 1 < m_stackY.size(); i++)
        {
            const int h = m_stackY[i + 1] - m_stackY[i]; /* v1.15.13：Y 前缀表（含已渲染真实高） */
            const int y0 = acc;
            acc += h;
            if (first < 0 && acc > top) first = i;
            if (y0 < bottom && acc > top) last = i; /* 与视口相交 */
            if (first >= 0 && acc > bottom) break;
        }
        if (first < 0) first = n > 0 ? n - 1 : 0;
        if (last < first) last = first;
    }
    /* v1.15.13：重排等待期只跟踪阅读位/页码，不派渲染、不清标签——v1.15.11"过渡期旧栈
     * 渲染让路"的防崩语义不变。原整段让路有两处副作用：① 等待期拖动时页码/进度冻结
     * （大书重排可达数十秒）；② 让路信号仍在函数入口刷新，滚动一下就把后台重排按停
     * 1.5s，越滚重排越不结束 */
    if (m_relayoutBusy.load())
    {
        if (m_page != first)
        {
            m_page = first;
            onPageChanged();
        }
        return;
    }
    m_lastRenderReq.store(QDateTime::currentMSecsSinceEpoch()); /* v0.107.1 让路信号 */
    for (int p = first; p <= last; p++)
        requestPage(p);
    /* v1.8.0（P0-4）：预取加宽+方向加权——窗口外前后各 2 页，滚动方向侧再 +2 */
    {
        const int ahead = 2 + (m_scrollDir > 0 ? 2 : 0);
        const int behind = 2 + (m_scrollDir < 0 ? 2 : 0);
        const int plo = qMax(0, first - behind), phi = qMin(m_pages - 1, last + ahead);
        for (int p = plo; p <= phi; p++)
            if (p < first || p > last)
                requestPage(p);
    }
    /* v1.11.1（P0-2）：丢弃视口窗口外的过期待渲——快速拖动路径长尾（≤96 页）松手后
     * 不再逐个渲染（用户早已离开；渲染它们浪费 CPU 且每次改高制造位移） */
    {
        const int plo2 = qMax(0, first - 6); /* 预取最宽 ±4 再放 2 */
        const int phi2 = qMin(m_pages - 1, last + 6);
        int dropped = 0;
        for (int i = m_pendQueue.size() - 1; i >= 0; i--)
            if (m_pendQueue[i] < plo2 || m_pendQueue[i] > phi2)
            {
                m_pendQueue.removeAt(i);
                dropped++;
            }
        if (dropped > 0)
            evt("pend_drop", QString("n=%1 keep=%2").arg(dropped).arg(m_pendQueue.size()));
    }
    evictByBudget(first, last); /* v1.8.0（P0-5）：字节预算 LRU（替代 ±8 硬淘汰） */
    if (m_page != first)
    {
        m_page = first;
        onPageChanged();
    }
}

void ReaderWindow::onScrollValue(int) { fillVisible(); }

/* ---------- 异步渲染（v1.8.0 多飞行 + 视口中心距待渲队列） ---------- */

/* v1.8.0（P0-1）：多飞行上限（2~3 档取 3；MuPDF 单文档引擎锁内仍串行，
 * 多飞行=下一页提前排队 + 在途回调拷贝与渲染重叠） */
static const int kMaxFlight = 3;

/* v0.110.2：滚动渲染专用线程池——全局池被扫描/后台计数/封面生成占满时
 * （云盘慢扫一次可达数十秒），渲染 runnable 排不上队=整场空白页（日志实证：
 * phase2_start 后 22.5s 才首步、滚动渲染零完成、切翻页模式 UI 线程直渲即正常）。
 * 专用池保证渲染永远有线程可用；翻页模式邻页预取共用。 */
static QThreadPool *renderPool()
{
    static QThreadPool *p = new QThreadPool;
    static const bool init = (p->setMaxThreadCount(qMax(2, QThread::idealThreadCount() / 2)), true);
    Q_UNUSED(init);
    return p;
}

/* v0.110.3：栈自愈——页数增长后标签未跟上（分批建栈被换书/重排打断、滑条
 * 直达新区）时，渲染前同步补建到目标页；此前静默返回=该页永久空白且无日志 */
void ReaderWindow::ensureStackTo(int page)
{
    if (!m_scrollMode || !m_stack || page < m_labels.size()) return;
    const int w = renderWidthPx();
    const int target = qMin(m_pages, page + 1);
    const qint64 tb0 = QDateTime::currentMSecsSinceEpoch();
    while (m_labels.size() < target)
    {
        QLabel *lb = new QLabel;
        lb->setFixedSize(w, m_estH);
        initPlaceholder(lb, m_labels.size()); /* v1.8.0（P0-2） */
        stackAppendLabel(m_stack, lb, w, m_estH);
        m_labels.append(lb);
        m_labelW.append(-1);
    }
    evt("stack_selfheal",
        QString("to=%1 ms=%2").arg(m_labels.size())
            .arg(QDateTime::currentMSecsSinceEpoch() - tb0));
    syncStackHeight(); /* v1.6.1 */
}

void ReaderWindow::requestPage(int page)
{
    if (m_openBusy.load()) return; /* v0.103.0：后台打开期间不派渲染 */
    if (!m_doc || page < 0 || page >= m_pages) return;
    if (page >= m_labels.size())
    {
        ensureStackTo(page); /* v0.110.3：补建后继续，不再静默丢弃 */
        if (page >= m_labels.size()) return;
    }
    const int targetW = renderWidthPx();
    if (m_labelW[page] == targetW) return; /* 已是目标宽 */
    if (m_flight.contains(page)) return; /* v1.8.0（P0-1）：同页在途去重 */
    if (m_flight.size() >= kMaxFlight)
    {
        reapStuckFlights(); /* v0.110.1 看门狗语义：卡死在途作废放行（seq 防旧回调） */
        if (m_flight.size() >= kMaxFlight)
        {
            /* v1.8.0（P0-1）：池满入待渲队列（去重；完成回调按视口中心距派发） */
            if (!m_pendQueue.contains(page))
                m_pendQueue.append(page);
            if (m_pendQueue.size() > 96)
                m_pendQueue.removeFirst(); /* 防快速滚动失速堆积 */
            return;
        }
    }
    dispatchPage(page);
}

/* v1.11.1（P0-1）：渲染改高滚动锚定——占位页高 estH 换真实页高时若不补偿滚动值，
 * 视口上方页的改高会把内容整体顶动（快速拖动松手后"连续上下刷新"根因）。
 * 标签整体在视口上缘之上 → 滚动值 +Δ；横跨视口顶 → 按比例锚定保持视口内相对位置；
 * 视口下方 → 不动。须在 syncStackHeight() 之后调用（滚动范围已更新）。 */
void ReaderWindow::anchorScrollOnResize(int page, QLabel *lb, int oldH, int newH)
{
    if (!lb || !m_scrollMode || !m_scroll) return;
    QScrollBar *sb = m_scroll->verticalScrollBar();
    if (!sb) return;
    const int dy = newH - oldH;
    if (dy == 0) return;
    const int top = sb->value();
    const int ly = lb->y();
    int nv = top;
    if (ly + oldH <= top)
        nv = top + dy; /* 标签整体在视口上方：内容随改高整体推移 Δ，补偿之 */
    else if (ly < top && top < ly + oldH)
        nv = ly + (int)((top - ly) * (double)newH / qMax(1, oldH)); /* 横跨视口顶：比例锚定 */
    else
        return; /* 视口下方：不影响可视区 */
    if (nv != top)
    {
        sb->setValue(nv); /* 语义位置不变：进度/保存不受影响 */
        evt("scroll_anchor", QString("page=%1 dy=%2 %3->%4")
                .arg(page + 1).arg(dy).arg(top).arg(nv));
    }
}

/* v1.8.0（P0-1）：派发单页渲染——多飞行=多页提前排队（MuPDF 单文档非线程安全，
 * 引擎内仍串行；在途回调的 RGB 拷贝/落图与下一页引擎渲染重叠），保留看门狗
 * （reapStuckFlights）与 seq 代际（旧回调整体丢弃，不再有"单 pending 折叠"）。
 * v1.9.0（P1-3 变体）：固定版式自适应渐进——该书上次全宽渲染 >400ms 时，未渲染页
 * 先出半宽快图（光栅 ~4x 快）放大铺位，全宽图随后由队列补渲替换；快书直接全宽。
 * v1.9.0（P1-2）：桥返回缓冲 QImage 外部直构（cleanup 释放），免整页 .copy()。 */
void ReaderWindow::dispatchPage(int page)
{
    if (m_openBusy.load() || !m_doc || page < 0 || page >= m_pages) return;
    if (page >= m_labels.size())
    {
        ensureStackTo(page);
        if (page >= m_labels.size()) return;
    }
    const int targetW = renderWidthPx();
    if (m_labelW[page] == targetW || m_flight.contains(page)) return;
    const bool quick = (m_fixedLayout && m_labelW[page] == -1 && m_fixedSlowMs > 400);
    const int rendW = quick ? qMax(200, targetW / 2) : targetW;
    const qint64 t0 = QDateTime::currentMSecsSinceEpoch();
    m_flight.insert(page);
    m_flightT0.insert(page, t0);
    const int seq = ++m_dispSeq;
    m_flightSeq.insert(page, seq);
    m_labelW[page] = -2; /* 在途标记 */
    evt("render_req", QString("page=%1 flight=%2 q=%3")
                          .arg(page + 1)
                          .arg(m_flight.size())
                          .arg(m_pendQueue.size()));
    int mode = 0, tint = 0;
    applyThemeToJob(&mode, &tint); /* GUI 线程先取定参数，避免与主题切换竞态 */
    void *doc = m_doc;
    QPointer<QLabel> lb(m_labels[page]);
    renderPool()->start(QRunnable::create(
        [this, doc, page, rendW, mode, tint, lb, seq, t0, quick, targetW]
    {
        int w = 0, h = 0;
        unsigned char *rgb = nullptr;
        QImage img;
        if (hr_qt_render(doc, page, rendW, mode, tint, &w, &h, &rgb) == 0 && rgb)
            img = QImage(rgb, w, h, w * 3, QImage::Format_RGB888, hr_imgFree, rgb);
        QMetaObject::invokeMethod(
            this,
            [this, page, img, targetW, lb, seq, t0, quick, rendW]
            {
                /* v1.8.0：seq 防串——看门狗作废/重派后的旧回调整体丢弃 */
                if (m_flightSeq.value(page, -1) != seq) return;
                m_flight.remove(page);
                m_flightT0.remove(page);
                m_flightSeq.remove(page);
                if (!img.isNull() && lb)
                {
                    QImage im2 = img;
                    compositeHighlights(page, &im2); /* U-ann：持久高亮合成（滚动模式） */
                    applyTexture(&im2); /* P2（B6） */
                    maybeSaveRemoteCover(page, im2); /* 缺陷#4：远程封面（滚动模式首渲） */
                    if (quick)
                    {
                        /* v1.9.0（P1-3 变体）：半宽快图放大铺位（几何即全宽比例尺寸）；
                         * m_labelW=rendW≠targetW → 全宽图随后由队列补渲替换 */
                        const int dispH = qMax(1, im2.height() * targetW / qMax(1, rendW));
                        QImage up = im2.scaled(targetW, dispH, Qt::IgnoreAspectRatio,
                                               Qt::SmoothTransformation);
                        const int oldH = lb->height(); /* v1.11.1（P0-1）：改高前快照 */
                        lb->setPixmap(QPixmap::fromImage(up));
                        lb->setFixedSize(targetW, dispH);
                        stackAfterResize(page, dispH); /* v1.15.13：前缀表+可视区几何（无布局级联） */
                        m_labelW[page] = rendW;
                        m_labelBytes.insert(page, (qint64)up.sizeInBytes());
                        m_lru.removeAll(page);
                        m_lru.append(page);
                        syncStackHeight();
                        anchorScrollOnResize(page, lb, oldH, dispH);
                        evt("render_quick", QString("page=%1 w=%2").arg(page + 1).arg(rendW));
                        requestPage(page); /* 全宽补渲入队（池满则待渲队列） */
                    }
                    else
                    {
                        if (m_trimWhite) im2 = trimWhiteImg(im2); /* v0.83.0 W2（开关滚动恒关） */
                        const int oldH = lb->height(); /* v1.11.1（P0-1）：改高前快照 */
                        /* v1.15.13：±1 像素不搬移——占位高已按引擎页尺寸对齐（calibEstH），标准页真实高
                         * 与占位至多差取整的 1 像素；改高会让其下全部标签在可见窗口里逐个搬移 */
                        const int newLh = qAbs(im2.height() - oldH) <= 1 ? oldH : im2.height();
                        lb->setPixmap(QPixmap::fromImage(im2));
                        lb->setFixedSize(im2.width(), newLh);
                        stackAfterResize(page, newLh); /* v1.15.13：前缀表+可视区几何（无布局级联） */
                        m_labelW[page] = targetW;
                        /* v1.8.0（P0-5）：字节账 + LRU 触点 */
                        m_labelBytes.insert(page, (qint64)im2.sizeInBytes());
                        m_lru.removeAll(page);
                        m_lru.append(page);
                        /* v1.9.0（P1-3 变体）：固定版式全宽耗时采样（>400ms 之书启用快图） */
                        if (m_fixedLayout)
                        {
                            const qint64 ms = QDateTime::currentMSecsSinceEpoch() - t0;
                            if (ms > 400) m_fixedSlowMs = (int)ms;
                        }
                        syncStackHeight(); /* v1.6.1：真实页高替换 estH 后同步 */
                        anchorScrollOnResize(page, lb, oldH, newLh); /* v1.11.1（P0-1） */
                        /* v1.0.0：矮页（封面/标题行，高仅几十 px）不参与校准——
                         * 此前 estH 被第 0 页带塌，整本占位标签成细条海（用户附图实证） */
                        if (page == 0 && m_viewH > 0 && img.height() >= m_viewH / 2)
                            m_estH = img.height();
                        /* v1.11.1（P0-3）：固定版式 estH 一次校准——页高全书一致且 ≠estH 时
                         * 批量把未渲染占位对齐真实高（配合 P0-1 锚定不可见），此后每页渲染 Δ=0 */
                        if (m_fixedLayout && m_viewH > 0 &&
                            qAbs(im2.height() - m_estH) > 8 && im2.height() >= m_viewH / 4)
                        {
                            const int oldEst = m_estH;
                            QScrollBar *sb0 = m_scroll ? m_scroll->verticalScrollBar() : nullptr;
                            const int top0 = sb0 ? sb0->value() : 0;
                            int shift = 0, fixed = 0;
                            const int wNow = renderWidthPx();
                            if (!m_estHExact) m_estH = im2.height();
                            const int dH = m_estH - oldEst;
                            int dAcc = 0; /* v1.15.13：前缀表整体改高，可视区几何即时对齐 */
                            for (int i = 0; i < m_labels.size() && i + 1 < m_stackY.size() &&
                                            i < m_labelW.size(); i++)
                            {
                                m_stackY[i] += dAcc;
                                if (!m_labels[i] || m_labelW[i] != -1) continue; /* 仅未渲染占位 */
                                const int ly = m_stackY[i];
                                if (ly + oldEst <= top0)
                                    shift += dH;
                                else if (ly < top0 && top0 < ly + oldEst)
                                    shift += (int)((top0 - ly) * (double)dH / qMax(1, oldEst));
                                m_labels[i]->setFixedSize(wNow, m_estH);
                                if (m_labels[i]->y() != m_stackY[i])
                                    m_labels[i]->move(0, m_stackY[i]);
                                dAcc += dH;
                                fixed++;
                            }
                            if (dAcc != 0 && !m_stackY.isEmpty())
                                m_stackY[m_stackY.size() - 1] += dAcc;
                            stackSyncGeomVisible();
                            syncStackHeight();
                            if (sb0 && shift != 0)
                                sb0->setValue(top0 + shift);
                            evt("esth_recal", QString("old=%1 new=%2 fixed=%3 shift=%4")
                                    .arg(oldEst).arg(m_estH).arg(fixed).arg(shift));
                        }
                        evt("render_ok", QString("page=%1 ms=%2").arg(page + 1)
                                .arg(QDateTime::currentMSecsSinceEpoch() - t0));
                        /* v1.8.0（P0-3）：首帧门控——开书遮罩等首个成功渲染再摘 */
                        if (m_gatePending)
                        {
                            m_gatePending = false;
                            hideOpenOverlay();
                            evt("first_gate", QString("page=%1").arg(page + 1));
                        }
                    }
                }
                else if (lb && m_labelW[page] == -2)
                {
                    m_labelW[page] = -1; /* 失败可重试 */
                    evt("render_fail", QString("page=%1").arg(page + 1));
                }
                pumpPending(); /* v1.8.0（P0-1）：待渲队列按视口中心距补派 */
                /* v0.111.1：渲染落地后补派可见窗口——收敛链：补请求→渲染→再补
                 * 直到窗口齐；渲染失败不补派防打转空转 */
                if (!img.isNull()) fillVisible();
            },
            Qt::QueuedConnection);
    }));
}

/* v1.8.0（P0-1）：待渲队列按"视口中心页距"排序派发——原单 pending 折叠在滚动中
 * 互相覆盖（只记住最后一页），多飞行+队列让窗口填充并行化、停手即收敛 */
void ReaderWindow::pumpPending()
{
    if (m_pendQueue.isEmpty() || m_flight.size() >= kMaxFlight) return;
    if (m_relayoutBusy.load()) return; /* v1.15.13：重排等待期不派待渲队列——引擎已按新排版分页，按旧页号渲出来的是别页 */
    const int top = m_scroll->verticalScrollBar()->value();
    const int mid = top + m_scroll->viewport()->height() / 2;
    int center = m_page;
    int acc = 0;
    for (int i = 0; i < m_labels.size(); i++)
    {
        const int h = m_labels[i] ? m_labels[i]->height() : m_estH;
        if (acc + h > mid) { center = i; break; }
        acc += h;
    }
    std::sort(m_pendQueue.begin(), m_pendQueue.end(),
              [center](int a, int b) { return qAbs(a - center) < qAbs(b - center); });
    while (!m_pendQueue.isEmpty() && m_flight.size() < kMaxFlight)
        dispatchPage(m_pendQueue.takeFirst());
}

/* v1.8.0（P0-1）：渲染看门狗（原单飞行 8s 语义扩展到多飞行）——引擎锁被网络停顿
 * 长期持有时作废卡死在途放行重试；作废回调因 seq 失配自动丢弃 */
void ReaderWindow::reapStuckFlights()
{
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    QList<int> stuck;
    for (auto it = m_flightT0.constBegin(); it != m_flightT0.constEnd(); ++it)
        if (now - it.value() > 8000)
            stuck.append(it.key());
    for (int p : stuck)
    {
        evt("render_stuck",
            QString("page=%1 wait=%2").arg(p + 1).arg(now - m_flightT0.value(p)));
        m_flight.remove(p);
        m_flightT0.remove(p);
        m_flightSeq.remove(p);
        if (p >= 0 && p < m_labelW.size() && m_labelW[p] == -2)
            m_labelW[p] = -1;
    }
}

/* ---------- 翻页模式 ---------- */

void ReaderWindow::showPageView()
{
    QWidget *content = new QWidget;
    QHBoxLayout *l = new QHBoxLayout(content);
    l->setContentsMargins(0, 0, 0, 0);
    l->setSpacing(8);
    m_firstLabel = nullptr;
    if (!m_img.isNull())
    {
        QLabel *a = new QLabel;
        a->setPixmap(QPixmap::fromImage(m_img));
        a->setFixedSize(m_img.size());
        l->addWidget(a);
        m_firstLabel = a;
    }
    if (m_twoPage && !m_img2.isNull())
    {
        QLabel *b = new QLabel;
        b->setPixmap(QPixmap::fromImage(m_img2));
        b->setFixedSize(m_img2.size());
        l->addWidget(b);
    }
    l->addStretch(1);
    QWidget *old = m_scroll->takeWidget();
    delete old;
    m_stack = content;
    m_stackY.clear(); /* v1.15.13：页模式无滚动栈前缀表 */
    m_scroll->setWidget(content);
    m_scroll->verticalScrollBar()->setValue(0);
}

void ReaderWindow::renderNow(int page)
{
    m_lastRenderReq.store(QDateTime::currentMSecsSinceEpoch()); /* v0.107.1 让路信号 */
    if (m_openBusy.load() || !m_doc || m_pages <= 0) return;
    page = qBound(0, page, m_pages - 1);
    const int w = renderWidthPx();
    qint64 t0 = QDateTime::currentMSecsSinceEpoch();
    /* v0.103.0 P0-4：邻页预取命中即出图（宽度校验防 zoom/版心变化串图） */
    const bool pfHit = m_pfCache.contains(page) && m_pfCache.value(page).first == w;
    if (pfHit)
        m_img = m_pfCache.take(page).second;
    else if (!renderRaw(page, w, &m_img))
        return;
    if (m_twoPage && page + 1 < m_pages)
    {
        const bool pf2 =
            m_pfCache.contains(page + 1) && m_pfCache.value(page + 1).first == w;
        if (pf2)
            m_img2 = m_pfCache.take(page + 1).second;
        else
            renderRaw(page + 1, w, &m_img2);
    }
    else m_img2 = QImage();
    if (page == 0 && m_viewH > 0 && m_img.height() >= m_viewH / 2) /* v1.0.0：矮页不校准 */
        if (!m_estHExact) m_estH = m_img.height(); /* 未裁剪原高（滚动布局口径） */
    if (m_trimWhite) /* v0.83.0 W2 D6：裁白边（翻页模式） */
    {
        m_img = trimWhiteImg(m_img);
        if (!m_img2.isNull()) m_img2 = trimWhiteImg(m_img2);
    }
    qint64 t1 = QDateTime::currentMSecsSinceEpoch();
    m_page = page;
    showPageView();
    maybeSaveRemoteCover(page, m_img); /* 缺陷#4：远程封面（翻页模式首渲） */
    evt("render_done", QString("page=%1 %2x%3 ms=%4%5")
                           .arg(page + 1).arg(m_img.width()).arg(m_img.height())
                           .arg(t1 - t0)
                           .arg(pfHit ? QString::fromLatin1(" pf=1") : QString()));
    onPageChanged();
    kickPagePrefetch(page); /* v0.103.0 P0-4：邻页（±1）后台预取 */
}

/* v0.103.0 P0-4：翻页邻页预取（后台渲 ±1 页入缓存；m_openGen 换代/开书防串） */
void ReaderWindow::kickPagePrefetch(int page)
{
    if (m_scrollMode || m_openBusy.load() || !m_doc || m_pages <= 0) return;
    if (m_relayoutBusy.load()) return; /* v1.15.13：重排等待期不起翻页预取（同下） */
    const int w = renderWidthPx();
    int mode = 0, tint = 0;
    applyThemeToJob(&mode, &tint); /* GUI 线程先取定参数（与 requestPage 同口径） */
    void *doc = m_doc;
    const int gen = m_openGen.load();
    /* v1.8.0（P0-4）：预取 ±1→±3（远跳后前后都有缓冲；双页按页对语义等价放宽） */
    for (int d = -3; d <= 3; d++)
    {
        if (d == 0) continue;
        const int p = page + d;
        if (p < 0 || p >= m_pages || m_pfCache.contains(p)) continue;
        m_auxJobs.fetch_add(1); /* v1.8.0：辅助任务计数（换书/关书等清零防 UAF） */
        QPointer<ReaderWindow> gw(this);
        renderPool()->start(QRunnable::create(
            [gw, doc, p, w, mode, tint, gen]
        {
            int iw = 0, ih = 0;
            unsigned char *rgb = nullptr;
            QImage img;
            if (hr_qt_render(doc, p, w, mode, tint, &iw, &ih, &rgb) == 0 && rgb)
            {
                /* v1.9.0（P1-2）：外部缓冲直构 */
                img = QImage(rgb, iw, ih, iw * 3, QImage::Format_RGB888, hr_imgFree, rgb);
            }
            if (gw) gw->m_auxJobs.fetch_sub(1); /* v1.8.0：任务收尾（窗亡放任防 UAF） */
            if (gw && !img.isNull())
                QMetaObject::invokeMethod(
                    gw.data(),
                    [gw, gen, p, w, img] { gw->storePrefetch(gen, p, w, img); },
                    Qt::QueuedConnection);
        }));
    }
}

void ReaderWindow::storePrefetch(int gen, int page, int w, const QImage &img)
{
    if (gen != m_openGen.load() || img.isNull() || m_relayoutBusy.load()) return; /* v1.15.13：重排等待期的预取结果不入缓存（可能是新排版的页） */
    if (m_pfCache.size() > 8) m_pfCache.clear(); /* v1.8.0（P0-4）：±3 预取需 8 档 */
    m_pfCache.insert(page, qMakePair(w, img));
}

/* v1.8.0（P0-6）：翻页模式异步渲染——旧页位图留存+右上角"渲染中…"角标，后台完成
 * 后一次换页（seq 防串页 + 8s 看门狗降级）。原 renderNow 在 UI 线程同步等引擎锁
 * （大页 PDF/重排首访页/远程缺块读网=整窗冻结数秒，自动翻页按间隔重演冻结）；
 * 预取命中仍走同步快路径（毫秒级）。 */
void ReaderWindow::renderPageAsync(int page)
{
    if (m_openBusy.load() || !m_doc || m_pages <= 0) return;
    page = qBound(0, page, m_pages - 1);
    const int w = renderWidthPx();
    const bool pfHit = m_pfCache.contains(page) && m_pfCache.value(page).first == w;
    const bool pf2 = m_twoPage && page + 1 < m_pages &&
                     m_pfCache.contains(page + 1) &&
                     m_pfCache.value(page + 1).first == w;
    if (pfHit && (!m_twoPage || pf2))
    {
        renderNow(page); /* 预取命中快路径（毫秒级，无冻结感） */
        return;
    }
    onPageChanged(); /* 进度/标题/滑条即时反馈（旧页位图仍显示=旧页留存） */
    if (m_relayoutBusy.load()) return; /* v1.15.13：重排等待期不起异步渲染——引擎已按新排版分页，
     * 按旧页号渲出来的是别页；落地时按折算后的页号重渲 */
    m_pageFlight = true;
    m_pageReqT0 = QDateTime::currentMSecsSinceEpoch();
    const int seq = ++m_pageSeq;
    if (m_pageBusy)
    {
        m_pageBusy->adjustSize();
        m_pageBusy->move(m_scroll->viewport()->width() - m_pageBusy->width() - 16, 12);
        m_pageBusy->raise();
        m_pageBusy->show();
    }
    evt("page_async_req", QString("page=%1 seq=%2").arg(page + 1).arg(seq));
    int mode = 0, tint = 0;
    applyThemeToJob(&mode, &tint);
    void *doc = m_doc;
    const bool two = m_twoPage;
    const int npg = m_pages;
    m_auxJobs.fetch_add(1);
    QPointer<ReaderWindow> gw(this);
    renderPool()->start(QRunnable::create([gw, doc, page, w, mode, tint, seq, two, npg]
    {
        QImage img, img2;
        /* m_pageSeq 跨线程读竞争无害（int 读；最坏多渲一页，回调仍被 seq 丢弃） */
        if (gw && seq == gw->m_pageSeq)
        {
            auto render1 = [&](int p, QImage *out)
            {
                int rw = 0, rh = 0;
                unsigned char *rgb = nullptr;
                if (hr_qt_render(doc, p, w, mode, tint, &rw, &rh, &rgb) == 0 && rgb)
                {
                    /* v1.9.0（P1-2）：外部缓冲直构 */
                    *out = QImage(rgb, rw, rh, rw * 3, QImage::Format_RGB888,
                                  hr_imgFree, rgb);
                }
            };
            render1(page, &img);
            if (two && page + 1 < npg && gw && seq == gw->m_pageSeq)
                render1(page + 1, &img2);
        }
        if (gw) gw->m_auxJobs.fetch_sub(1); /* 换书/关书等待清零；窗亡放任防 UAF */
        if (!gw) return;
        QMetaObject::invokeMethod(
            gw.data(),
            [gw, page, seq, img, img2]
            { gw->onPageJobDone(page, seq, img, img2); },
            Qt::QueuedConnection);
    }));
    /* 8s 看门狗降级（复用滚动看门狗语义）：超时保留旧页+状态栏提示 */
    QTimer::singleShot(8000, this, [this, seq]
    {
        if (!m_pageFlight || seq != m_pageSeq) return;
        m_pageFlight = false;
        if (m_pageBusy) m_pageBusy->hide();
        evt("page_async_stuck", QString("seq=%1").arg(seq));
        statusBar()->showMessage(
            hr::l10n::s("页面渲染超时，已保留当前页", "Page render timeout"), 4000);
    });
}

/* v1.8.0（P0-6）：翻页异步完成回调——seq/开书态/模式校验后一次换页；
 * 新翻页已推进 seq → 本回调整体丢弃（防串页） */
void ReaderWindow::onPageJobDone(int page, int seq, const QImage &img,
                                 const QImage &img2)
{
    if (seq != m_pageSeq || m_openBusy.load() || !m_doc) return;
    m_pageFlight = false;
    if (m_pageBusy) m_pageBusy->hide();
    if (m_scrollMode) return; /* 翻页期间切到滚动模式：交 fillVisible 接管 */
    if (img.isNull())
    {
        evt("render_fail", QString("pagemode page=%1").arg(page + 1));
        return; /* 保留旧页位图 */
    }
    m_img = img;
    m_img2 = (m_twoPage && page + 1 < m_pages) ? img2 : QImage();
    compositeHighlights(page, &m_img); /* U-ann：与 renderRaw 同口径 */
    applyTexture(&m_img);
    if (!m_img2.isNull())
    {
        compositeHighlights(page + 1, &m_img2);
        applyTexture(&m_img2);
    }
    if (page == 0 && m_viewH > 0 && m_img.height() >= m_viewH / 2) /* v1.0.0：矮页不校准 */
        if (!m_estHExact) m_estH = m_img.height();
    if (m_trimWhite) /* v0.83.0 W2 D6：裁白边（翻页模式） */
    {
        m_img = trimWhiteImg(m_img);
        if (!m_img2.isNull()) m_img2 = trimWhiteImg(m_img2);
    }
    showPageView();
    maybeSaveRemoteCover(page, m_img);
    evt("page_async_ok",
        QString("page=%1 seq=%2 ms=%3")
            .arg(page + 1)
            .arg(seq)
            .arg(QDateTime::currentMSecsSinceEpoch() - m_pageReqT0));
    onPageChanged();
    kickPagePrefetch(page); /* 邻页后台预取 */
}

void ReaderWindow::onPageChanged()
{
    /* v1.9.0（P1-7②）：阅读位置→字节窗口主动驱动（原仅 readAt 被动更新窗口）——
     * 远程书按页号平滑推算字节位置，后台补齐线程优先取当前阅读区 */
    if (m_cacheProg && m_pages > 0)
        m_cacheProg->cacheSetWindow((long long)m_page *
                                    m_cacheProg->cacheTotalBytes() / m_pages);
    m_lbPos->setText(QString("%1 / %2").arg(m_page + 1).arg(m_pages));
    if (!m_sliderMoving) m_slider->setValue(m_page);
    updateTitle();
    if (m_page > m_lastStatPage && m_page - m_lastStatPage <= 5)
        hr::Stats::addReading(m_path, m_page - m_lastStatPage, 0);
    m_lastStatPage = m_page;
    m_saveTimer.start();
}

/* v0.109.0：分章书窗口化重排落地——页表=窗口各章，位置按锚定章+章内页回放；
 * 余下章节交回 phase2 后台推进（完成时照常落页数缓存） */
void ReaderWindow::finishRelayoutWindowed(int gen, void *doc, const QVector<int> &all,
                                          int anchorCh, int anchorPg, int nc)
{
    /* v1.15.12：busy 保持到 buildStack+滑条同步完成——setWidget 的量程塌缩窗口内
     * fillVisible 不得重锚（m_page 瞬态归 0 → 页码闪"1 / N"+ 无效 render_req 连发，
     * 用户实测最大化后页码先跳 1 再跳回）；早期丢弃路径在此处清位 */
    if (gen != m_relayoutGen.load() || doc != m_doc || all.isEmpty())
    {
        /* v1.15.13：仅当本代仍是最近启动的一代才清 busy——被更新一代重排顶替时 busy
         * 归新一代所有；此处抢清会让返回书库时的 releaseEngineDoc 关掉新一代 worker
         * 正在使用的 doc（UAF） */
        if (m_relayoutStartGen.load() == gen)
            m_relayoutBusy.store(false);
        return;
    }
    int sum = 0;
    for (int v : all) sum += qMax(0, v);
    /* v1.15.13：等待期用户可能已滚走——按当前 m_page 在旧表里重定位锚（此刻 m_chapPages
     * 仍是重排前排版的表）；滚出本次已数窗口则夹到窗口末章 */
    int oldCnt = 0; /* 锚章在旧排版里的页数（章内按比例折算） */
    if (!m_chapPages.isEmpty())
    {
        int accO = 0;
        for (int k = 0; k < m_chapPages.size(); k++)
        {
            const int cp = qMax(0, m_chapPages[k]);
            if (accO + cp > m_page || k == m_chapPages.size() - 1)
            {
                anchorCh = qMin(k, all.size() - 1);
                anchorPg = qBound(0, m_page - accO, qMax(0, cp - 1));
                oldCnt = cp;
                break;
            }
            accO += cp;
        }
    }
    m_chapPages = all;
    m_pages = qMax(1, sum);
    int acc = 0;
    for (int k = 0; k < anchorCh && k < all.size(); k++) acc += qMax(0, all[k]);
    if (anchorCh >= 0 && anchorCh < all.size())
    {
        /* v1.15.13：章内页号按比例折算——重排现在真的改分页（worker 先交新排版参数），
         * 原"章内页号原样保留"随章长漂移：6 页的章放大字号变 9 页，第 5 页还落第 5 页，
         * 在章内的位置就从 83% 退到 44% */
        const int nc1 = qMax(0, all[anchorCh]);
        const int newPg = oldCnt > 0
                              ? (int)qRound(anchorPg * (double)qMax(1, nc1) / oldCnt)
                              : anchorPg;
        m_page = acc + qBound(0, newPg, qMax(0, nc1 - 1));
    }
    m_page = qBound(0, m_page, m_pages - 1);
    const int pgRestore = m_page; /* v1.15.11：锚定章回放页快照（buildStack 前取） */
    m_img = QImage();
    m_img2 = QImage();
    m_pfCache.clear(); /* 排版参数变了，预取位图全部失效 */
    m_estH = m_viewH > 0 ? m_viewH : 1200; calibEstH(); /* v1.0.0：锚排版页高，防占位细条海 */
    /* v1.8.0：渲染状态复位（多飞行/待渲/翻页异步/首帧门控） */
    m_flight.clear(); m_flightT0.clear(); m_flightSeq.clear(); m_pendQueue.clear();
    m_pageFlight = false; m_gatePending = false;
    ++m_pageSeq; /* v1.15.13：作废重排前派发的翻页渲染回调——落地走 renderNow 不推进 seq，
    旧排版页图完成后仍能通过 seq 校验并覆盖新排版的当前页 */
    m_cacheProg = nullptr;
    m_stackSwap = true; /* v1.15.13：换栈同步窗口内 fillVisible 完全禁入 */
    buildStack();
    m_slider->setRange(0, qMax(0, m_pages - 1));
    m_slider->setValue(m_page);
    m_stackSwap = false;
    m_relayoutBusy.store(false); /* v1.15.12：换栈+滑条同步完成后再放行 fillVisible */
    if (m_scrollMode)
        restorePosDeferred(pgRestore); /* v1.15.11：显式页快照落位 */
    else renderNow(m_page);
    updateTitle();
    onPageChanged();
    /* v1.1.0：排版变更后目录页码与书签折算页随新排版刷新（安卓 0.9.66 同语义） */
    evt("finrl", "pre_toc"); /* v1.15.11：取证桩 */
    refreshTocTree();
    evt("finrl", "post_toc");
    refreshBookmarkList();
    evt("layout", QString("pages=%1 em=%2 theme=%3 scroll=%4 win=1 ch=%5")
                      .arg(m_pages).arg(m_em).arg(kThemes[m_theme].id)
                      .arg((int)m_scrollMode).arg(all.size()));
    if (all.size() < nc)
        startPhase2Count(all.size(), m_pages, nc);
}

void ReaderWindow::nav(int delta)
{
    if (m_pages <= 0) return;
    int np = m_page + delta;
    if (np < 0 || np >= m_pages) return;
    jumpTo(np);
}

/* ---------- 重排 ---------- */

/* v0.110.0：滚动模式只留原生连续滚动条（右缘），底部横条=翻页模式专用。
 * 构造器早期（btnMode 初始态）即被调——控件未建齐时判空 */
void ReaderWindow::updateModeUi()
{
    if (m_slider)
        m_slider->setVisible(!m_scrollMode);
    if (m_pageBusy) m_pageBusy->hide(); /* v1.8.0（P0-6）：切模式收翻页角标 */
}

void ReaderWindow::relayoutAfterChange(bool metrics)
{
    if (!m_doc) return;
    if (m_openBusy.load()) return; /* v0.104.0：开书排版在途，以其结果为准 */
    if (m_smokeW <= 0)
    {
        m_viewW = m_scroll->viewport()->width();
        m_viewH = m_scroll->viewport()->height();
        if (m_viewW < 300) { m_viewW = width() - 40; m_viewH = height() - 160; }
    }
    applyUserCss(); /* v0.59.0：排版偏好 user CSS（layout 前生效；ctx 级） */
    /* v0.104.0（P0-5）：固定版式页面尺寸不随排版变——保留同步快速路径 */
    if (m_fixedLayout)
    {
        m_pages = hr_qt_count(m_doc);
        m_page = qBound(0, m_page, qMax(0, m_pages - 1));
        const int pg0 = m_page; /* v1.15.11：重排入口页快照 */
        /* v1.9.0（P1-4）：旧位图保留——缩放瞬时按新旧宽比矩阵缩放即时反馈 */
        m_pfCache.clear();
        m_estH = m_viewH > 0 ? m_viewH : 1200; calibEstH(); /* v1.0.0：锚排版页高，防占位细条海 */
        /* v1.8.0：渲染状态复位（多飞行/待渲/翻页异步/首帧门控） */
        m_flight.clear(); m_flightT0.clear(); m_flightSeq.clear(); m_pendQueue.clear();
        m_pageFlight = false; m_gatePending = false;
        ++m_pageSeq; /* v1.15.13：同上，作废重排前的翻页渲染回调 */
        m_cacheProg = nullptr;
        m_stackSwap = true; /* v1.15.13：同步换栈窗口禁入 fillVisible；不再借用重排 busy——那是在飞
         * worker 的归属标志，这里 store(false) 会让返回书库时关掉它正在用的 doc */
        buildStack();
        m_slider->setRange(0, qMax(0, m_pages - 1));
        m_slider->setValue(m_page);
        m_stackSwap = false;
        if (m_scrollMode)
            restorePosDeferred(pg0); /* v1.15.11：显式页快照落位 */
        /* v1.9.0（P1-4）：缩放渐进——旧位图按新旧宽比缩放即时显示，全宽重渲走
         * 翻页异步链（旧页留存+角标）；冒烟保持同步 */
        else if (m_smokeW > 0)
        {
            renderNow(m_page);
        }
        else
        {
            const int nw = renderWidthPx();
            if (!m_img.isNull() && nw > 0 && m_img.width() > 0 && nw != m_img.width())
            {
                const double zf = (double)nw / m_img.width();
                m_img = m_img.scaled(nw, qMax(1, (int)(m_img.height() * zf)),
                                     Qt::IgnoreAspectRatio, Qt::SmoothTransformation);
                if (!m_img2.isNull())
                    m_img2 = m_img2.scaled(nw, qMax(1, (int)(m_img2.height() * zf)),
                                           Qt::IgnoreAspectRatio, Qt::SmoothTransformation);
            }
            showPageView();
            onPageChanged();
            renderPageAsync(m_page);
        }
        updateTitle();
        evt("layout", QString("pages=%1 em=%2 theme=%3 scroll=%4 fixed=1")
                          .arg(m_pages).arg(m_em).arg(kThemes[m_theme].id).arg((int)m_scrollMode));
        return;
    }
    /* v0.109.0：分章书（窗口化打开过的可重排书）改排版——全书 hr_qt_layout
     * 单次持锁数十秒，翻页渲染全部排队（大 mobi 改字号卡住根因）。改走窗口化：
     * 先作废 phase2 并等它退出，再逐章数到"当前章+余量"（章间让路渲染，
     * 同 phase2 口径），其余章节交回 phase2 后台推进（完成照常落页数缓存）。
     * metrics=false（主题/模式切换）页数不变：仅重建栈重渲，不重数。 */
    const int nc0 = hr_qt_count_chapters(m_doc);
    if (nc0 > 1 && !m_chapPages.isEmpty())
    {
        if (!metrics)
        {
            m_img = QImage();
            m_img2 = QImage();
            m_pfCache.clear();
            const int pg0 = m_page; /* v1.15.11：buildStack 过渡事件可能扰动 m_page，先快照 */
            m_stackSwap = true; /* v1.15.13：同步换栈窗口禁入 fillVisible；不再借用重排 busy——那是在飞
         * worker 的归属标志，这里 store(false) 会让返回书库时关掉它正在用的 doc */
            buildStack();
            m_slider->setRange(0, qMax(0, m_pages - 1));
            m_slider->setValue(m_page);
            m_stackSwap = false;
            if (m_scrollMode)
                restorePosDeferred(pg0); /* v1.15.11：显式页快照落位 */
            else renderNow(m_page);
            updateTitle();
            evt("layout", QString("pages=%1 em=%2 theme=%3 scroll=%4 win=keep")
                              .arg(m_pages).arg(m_em).arg(kThemes[m_theme].id).arg((int)m_scrollMode));
            return;
        }
        void *doc2 = m_doc;
        const int layW2 = maxCharsWidth();
        const int em2 = m_em, vw2 = m_viewW, vh2 = m_viewH;
        QPointer<ReaderWindow> gw(this);
        /* 锚定当前章+章内页（用旧表定位；新表落地后章内页号夹紧） */
        int anchorCh = 0, anchorPg = 0, acc = 0;
        for (int k = 0; k < m_chapPages.size(); k++)
        {
            const int cp = qMax(0, m_chapPages[k]);
            if (acc + cp > m_page || k == m_chapPages.size() - 1)
            {
                anchorCh = k;
                anchorPg = m_page - acc;
                break;
            }
            acc += cp;
        }
        m_openGen.fetch_add(1); /* 作废在途 phase2（章界自断；缓存由新 phase2 落） */
        {
            const qint64 tw = QDateTime::currentMSecsSinceEpoch();
            while (m_phase2Busy.load() &&
                   QDateTime::currentMSecsSinceEpoch() - tw < 10000)
                QApplication::processEvents();
            m_phase2Busy.store(false);
        }
        m_relayoutBusy.store(true);
        const int gen2 = m_relayoutGen.fetch_add(1) + 1;
        m_relayoutStartGen.store(gen2); /* v1.15.13：busy 归属代号 */
        const int winCh = qMin(nc0, anchorCh + 1 + qMax(40, anchorCh / 4));
        QThreadPool::globalInstance()->start(QRunnable::create(
            [gw, gen2, doc2, layW2, em2, vw2, vh2, winCh, nc0, anchorCh, anchorPg]
        {
            if (!gw) return;
            QVector<int> all;
            all.reserve(winCh);
            /* v1.15.13：先把新排版参数交给引擎。fz_layout_document 对 EPUB/分章 mobi 只记录
             * 宽/高/字号并把各章标记待重排，逐章重排推迟到下面 count_pages_of 触达时才做
             * （正是窗口化的本意）。原缺这一步：改字号/改窗口只按旧排版重数页——页数不变、
             * 页面内容照旧（用户日志实锤：同一本 325 页的书连续 5 次重排都是 325 页；
             * 走整书路径的同一批书是 668→287、1098→518） */
            if (hr_qt_layout(doc2, (float)layW2, (float)vh2, em2) != 0)
                evt("rlayout_params_fail",
                    QString("gen=%1 err=%2").arg(gen2).arg(QString::fromLocal8Bit(hr_qt_last_error())));
            /* v1.15.13：作废路径也必须回 GUI 线程清 busy——原静默 return：开书/adopt 作废
             * 在途重排后 m_relayoutBusy 永不清除（等待循环空转满 10s；v1.15.11 起滚动模式
             * fillVisible 还会永久让路=新书全是占位页） */
            auto bail = [gw, gen2, doc2, nc0, anchorCh, anchorPg]
            {
                QMetaObject::invokeMethod(
                    gw.data(),
                    [gw, gen2, doc2, nc0, anchorCh, anchorPg]
                    { gw->finishRelayoutWindowed(gen2, doc2, QVector<int>(), anchorCh, anchorPg, nc0); },
                    Qt::QueuedConnection);
            };
            for (int k = 0; k < winCh; k++)
            {
                if (!gw) return;
                if (gw->m_relayoutGen.load() != gen2)
                {
                    bail();
                    return;
                }
                /* 交互让路（同 phase2）：1.5s 内有渲染请求则暂停重数 */
                while (gw && gw->m_relayoutGen.load() == gen2 &&
                       QDateTime::currentMSecsSinceEpoch() -
                               gw->m_lastRenderReq.load() <
                           1500)
                    QThread::msleep(120);
                if (!gw) return;
                if (gw->m_relayoutGen.load() != gen2)
                {
                    bail();
                    return;
                }
                all.append(hr_qt_count_pages_of(doc2, k));
            }
            QMetaObject::invokeMethod(
                gw.data(),
                [gw, gen2, doc2, all, nc0, anchorCh, anchorPg]
                { gw->finishRelayoutWindowed(gen2, doc2, all, anchorCh, anchorPg, nc0); },
                Qt::QueuedConnection);
        }));
        evt("layout_async", QString("gen=%1 win=1 winCh=%2").arg(gen2).arg(winCh));
        return;
    }
    /* v0.104.0（P0-5）：可重排格式整书重排移后台（改字号/换主题/缩放窗口不再冻结）；
     * 旧排版页继续显示，完成后换栈恢复阅读位；与开书经 m_relayoutBusy 互锁防旧 doc UAF */
    const int gen = m_relayoutGen.fetch_add(1) + 1;
    m_relayoutOldPages = m_pages; /* v1.15.13：整书重排前的页数（落地按全书比例折算阅读位） */
    m_relayoutStartGen.store(gen); /* v1.15.13：busy 归属代号 */
    m_relayoutBusy.store(true);
    const int layW = maxCharsWidth();
    const int em = m_em, viewW = m_viewW, viewH = m_viewH;
    void *doc = m_doc;
    QPointer<ReaderWindow> gw(this);
    QThreadPool::globalInstance()->start(QRunnable::create(
        [gw, gen, doc, layW, em, viewW, viewH]
    {
        if (!gw) return;
        hr_qt_layout(doc, (float)layW, (float)viewH, em);
        const int pages = hr_qt_count(doc);
        const int nc = hr_qt_count_chapters(doc);
        QMetaObject::invokeMethod(
            gw.data(),
            [gw, gen, doc, pages, nc] { gw->finishRelayout(gen, doc, pages, nc); },
            Qt::QueuedConnection);
    }));
    evt("layout_async", QString("gen=%1").arg(gen));
}

void ReaderWindow::finishRelayout(int gen, void *doc, int pages, int chapters)
{
    /* v1.15.12：busy 保持到 buildStack+滑条同步完成（同 finishRelayoutWindowed——
     * 量程塌缩窗口内禁锚，防页码瞬态"1 / N"）；换代/换书丢弃路径在此处清位 */
    if (gen != m_relayoutGen.load() || doc != m_doc)
    {
        /* v1.15.13：仅当本代仍是最近启动的一代才清 busy（同 finishRelayoutWindowed） */
        if (m_relayoutStartGen.load() == gen)
            m_relayoutBusy.store(false);
        return; /* 换代/换书：丢弃 */
    }
    /* v1.15.13：页数变了（真改排版）按全书比例折算阅读位——原"页号原样保留"在页数变化时
     * 漂移：96 页的书读到第 20 页（21%），窗口放大后变 52 页还落第 20 页（38%）。
     * 与书签跨排版的"比例回落"（resolveBookmarkPage）同一口径 */
    const int oldPages = m_relayoutOldPages;
    const int newPages = qMax(0, pages);
    int np = m_page;
    if (newPages > 0 && oldPages > 0 && oldPages != newPages)
        np = (int)qRound(m_page * (double)newPages / oldPages);
    m_pages = newPages;
    m_page = qBound(0, np, qMax(0, m_pages - 1));
    const int pgRestore = m_page; /* v1.15.11：重排入口页快照（buildStack 前取） */
    m_img = QImage();
    m_img2 = QImage();
    m_pfCache.clear(); /* 排版参数变了，预取位图全部失效 */
    m_estH = m_viewH > 0 ? m_viewH : 1200; calibEstH(); /* v1.0.0：锚排版页高，防占位细条海 */
    /* v1.8.0：渲染状态复位（多飞行/待渲/翻页异步/首帧门控） */
    m_flight.clear(); m_flightT0.clear(); m_flightSeq.clear(); m_pendQueue.clear();
    m_pageFlight = false; m_gatePending = false;
    ++m_pageSeq; /* v1.15.13：作废重排前派发的翻页渲染回调——落地走 renderNow 不推进 seq，
    旧排版页图完成后仍能通过 seq 校验并覆盖新排版的当前页 */
    m_cacheProg = nullptr;
    m_stackSwap = true; /* v1.15.13：换栈同步窗口内 fillVisible 完全禁入 */
    buildStack();
    m_slider->setRange(0, qMax(0, m_pages - 1));
    m_slider->setValue(m_page);
    m_stackSwap = false;
    m_relayoutBusy.store(false); /* v1.15.12：换栈+滑条同步完成后再放行 fillVisible */
    if (m_scrollMode)
        restorePosDeferred(pgRestore); /* v1.15.11：显式页快照落位 */
    else renderNow(m_page);
    updateTitle();
    /* U3b：开书刷新侧板（目录/书签），搜索结果清空；
     * v1.1.0：目录页表（章节锚数据源）无条件载入——书签捕获/折算不依赖侧板可见 */
    evt("finrl", "pre_toc"); /* v1.15.11：取证桩 */
    refreshTocTree();
    evt("finrl", "post_toc");
    if (m_side)
    {
        refreshBookmarkList();
        if (m_schList) m_schList->clear();
        if (m_schLb)
            m_schLb->setText(hr::l10n::s("回车/点搜索；双击结果跳页",
                                         "Enter to search; double-click to jump"));
    }
    evt("layout", QString("pages=%1 em=%2 theme=%3 scroll=%4")
                      .arg(m_pages).arg(m_em).arg(kThemes[m_theme].id).arg((int)m_scrollMode));
}

/* ---------- 交互 ---------- */

void ReaderWindow::keyPressEvent(QKeyEvent *e)
{
    /* P2 适配（C5）：Alt+←/→ = 回退/前进（对齐 WIN 链接历史键） */
    if (e->modifiers() & Qt::AltModifier)
    {
        if (e->key() == Qt::Key_Left && !m_navBack.isEmpty())
        {
            m_navGuard++;
            m_navFwd.append(m_page);
            jumpTo(m_navBack.takeLast());
            m_navGuard--;
            evt("nav_back", QString("p=%1").arg(m_page + 1));
            return;
        }
        if (e->key() == Qt::Key_Right && !m_navFwd.isEmpty())
        {
            m_navGuard++;
            m_navBack.append(m_page);
            jumpTo(m_navFwd.takeLast());
            m_navGuard--;
            evt("nav_fwd", QString("p=%1").arg(m_page + 1));
            return;
        }
    }
    switch (e->key())
    {
    case Qt::Key_Left:
    case Qt::Key_PageUp: nav(-1); return;
    case Qt::Key_Right:
    case Qt::Key_PageDown:
    case Qt::Key_Space: nav(1); return;
    case Qt::Key_Home: jumpTo(0); return;
    case Qt::Key_End: jumpTo(m_pages - 1); return;
    case Qt::Key_F11: toggleImmersive(); return;
    case Qt::Key_Escape:
        if (m_immersive) { toggleImmersive(); return; }
        if (!m_selText.isEmpty()) { clearSelection(); return; } /* v1.12.0：Esc 清选区 */
        break;
    default:
        break;
    }
    QMainWindow::keyPressEvent(e);
}

/* v1.7.0：阅读菜单（原拖选释放即弹的划词菜单抽出）——右键调用；
 * 有选区作用于选区，无选区文字类项置灰。 */
void ReaderWindow::showSelMenu(const QPoint &gp)
{
    if (!m_doc || m_pages <= 0) return;
    /* U3b 划词菜单增强：网搜/选区存图/高亮5色/翻译目标语三选 */
    QMenu menu(this);
    QAction *cp = menu.addAction(hr::l10n::s("复制", "Copy"));
    QAction *web = menu.addAction(hr::l10n::s("网搜…", "Web search…"));
    QMenu *hlMenu = menu.addMenu(hr::l10n::s("高亮(PDF)", "Highlight (PDF)"));
    struct HlColor { const char *zh, *en; unsigned argb; };
    const HlColor hls[5] = {
        {"黄", "Yellow", 0xFFFF00}, {"绿", "Green", 0x66BB00},
        {"蓝", "Blue", 0x2196F3},   {"红", "Red", 0xE53935},
        {"紫", "Purple", 0x8E24AA}};
    for (const HlColor &hc : hls)
        hlMenu->addAction(hr::l10n::s(hc.zh, hc.en))
            ->setData(uint(hc.argb));
    QAction *img =
        menu.addAction(hr::l10n::s("选区存图", "Snapshot selection"));
    QMenu *trMenu = menu.addMenu(hr::l10n::s("翻译", "Translate"));
    /* U6：目标语记忆（reader/translateTgt 0/1/2，勾选态同步） */
    QSettings pst(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
    const int lastTgt = pst.value("reader/translateTgt", 0).toInt();
    QAction *trAuto = trMenu->addAction(hr::l10n::s("自动判断", "Auto"));
    trAuto->setCheckable(true);
    trAuto->setChecked(lastTgt == 0);
    QAction *trZh = trMenu->addAction(hr::l10n::s("译成中文", "To Chinese"));
    trZh->setCheckable(true);
    trZh->setChecked(lastTgt == 1);
    QAction *trEn = trMenu->addAction(hr::l10n::s("译成英文", "To English"));
    trEn->setCheckable(true);
    trEn->setChecked(lastTgt == 2);
    QAction *sr = menu.addAction(hr::l10n::s("搜索同词", "Search word"));
    QAction *spk = menu.addAction(hr::l10n::s("朗读", "Speak")); /* P1（C3） */
    QAction *bmAdd = menu.addAction(hr::l10n::s("加书签", "Bookmark")); /* P1（C2） */
    QAction *noteAct = menu.addAction(hr::l10n::s("笔记…", "Note…")); /* v1.6.0（B2）=安卓 onNoteToEdit */
    QAction *dcAct = menu.addAction(hr::l10n::s("词典", "Dictionary"));
    menu.addSeparator();
    QAction *cancelAct = menu.addAction(hr::l10n::s("取消", "Cancel"));
        /* v1.7.0：无选词时弹菜单但文字类项置灰（右键必出菜单体验） */
    const bool hasText = !m_selText.trimmed().isEmpty();
    if (!hasText)
    {
        for (QAction *a : menu.actions())
        {
            if (QMenu *sm = a->menu())
            {
                for (QAction *s2 : sm->actions())
                    s2->setEnabled(false);
                continue;
            }
            if (!a->isSeparator())
                a->setEnabled(a == cancelAct);
        }
    }
    QAction *act = menu.exec(gp);

    auto doHighlight = [this](int argb)
    {
        /* v1.12.0：逐页写回——跨页选区每页用各自的精确 quad 落页坐标
         * （原实现取覆盖层矩形=全页行框，高亮范围错误且跨页选区只写锚定页）。
         * U-ann：持久化 annotation 表（与 Compose/安卓同 schema 互通） */
        for (auto it = m_selQuads.constBegin(); it != m_selQuads.constEnd(); ++it)
        {
            const int pg = it.key();
            const QRectF ir = selPageRect(pg);
            if (ir.width() <= 0) continue;
            float box[4] = {0, 0, 0, 0};
            hr_qt_page_box(m_doc, pg, box);
            const double scale = ir.width() / qMax(1.0f, box[2] - box[0]);
            QString quadsRaw;
            for (const QRectF &r : it.value()) /* 页内局部像素 → 页坐标 */
            {
                quadsRaw +=
                    QString::number(r.left() / scale + box[0], 'f', 2) + "," +
                    QString::number(r.top() / scale + box[1], 'f', 2) + "," +
                    QString::number(r.right() / scale + box[0], 'f', 2) + "," +
                    QString::number(r.bottom() / scale + box[1], 'f', 2) + ";";
            }
            Db::addAnnotation(m_path, pg, 0xFF000000 | argb, quadsRaw,
                              m_selText.trimmed().left(120), m_pages);
            evt("sel_highlight",
                QString("page=%1 quads=%2 argb=%3 db=1")
                    .arg(pg + 1).arg(it.value().size())
                    .arg(argb, 6, 16, QChar('0')));
        }
        refreshHighlights();
        refreshHighlightList();
    };
    if (act == cp)
    {
        QApplication::clipboard()->setText(m_selText);
        evt("sel_copy", QString("len=%1 page=%2")
                            .arg(m_selText.size()).arg(m_page + 1));
    }
    else if (act == web)
    {
        /* U3b：默认浏览器网搜（P1 适配 C4：引擎可选 google/bing/baidu/duckduckgo） */
        static const char *engines[][2] = {
            {"google", "https://www.google.com/search"},
            {"bing", "https://www.bing.com/search"},
            {"baidu", "https://www.baidu.com/s"},
            {"duckduckgo", "https://duckduckgo.com/"},
        };
        const QString eng = pst.value("reader/webSearchEngine", "bing").toString();
        QUrl u(QString::fromLatin1(engines[1][1]));
        for (auto &e : engines)
            if (eng == QLatin1String(e[0]))
                u = QUrl(QString::fromLatin1(e[1]));
        QUrlQuery qu;
        qu.addQueryItem("q", m_selText.trimmed());
        u.setQuery(qu);
        QDesktopServices::openUrl(u);
        evt("sel_web", QString("len=%1").arg(m_selText.size()));
    }
    else if (act && hlMenu->actions().contains(act))
    {
        /* U3b：高亮 5 色（原固定黄） */
        doHighlight((int)act->data().toUInt());
    }
    else if (act == img)
    {
        /* U3b：选区存图（P1 D2：滚动模式取锚定页位图） */
        QImage selImg = m_img;
        const QLabel *sl =
            (m_scrollMode && m_selPage >= 0 && m_selPage < m_labels.size())
                ? m_labels[m_selPage]
                : nullptr;
        if (sl && sl->pixmap() && !sl->pixmap()->isNull())
            selImg = sl->pixmap()->toImage();
        /* v1.12.0：取锚定页选区的页内局部 quad——即位图像素区（跨页选区的
         * 快照限锚定页；原实现取覆盖层矩形含其它页内容会拼错范围） */
        QRectF acc;
        for (const QRectF &r : m_selQuads.value(m_selP0 >= 0 ? m_selP0 : m_selPage))
            acc = acc.isNull() ? QRectF(r) : acc.united(r);
        if (!acc.isNull() && !selImg.isNull())
        {
            const QImage cut =
                selImg.copy(acc.toRect().intersected(selImg.rect()));
            QApplication::clipboard()->setImage(cut);
            evt("sel_image", QString("page=%1 w=%2 h=%3")
                                 .arg(m_page + 1)
                                 .arg(cut.width()).arg(cut.height()));
        }
    }
    else if (act == trAuto || act == trZh || act == trEn)
    {
        const int tg = act == trZh ? 1 : (act == trEn ? 2 : 0);
        pst.setValue("reader/translateTgt", tg); /* U6：记住目标语 */
        translateSelection(m_selText, tg);
    }
    else if (act == sr)
    {
        emit searchWord(m_selText.trimmed());
    }
    else if (act == spk)
    {
        /* P1 适配（C3）：选区朗读 */
        if (Tts::available() && Tts::speakAsync(m_selText))
            evt("sel_speak", QString("len=%1").arg(m_selText.size()));
        else
            statusBar()->showMessage(
                hr::l10n::s("SAPI 不可用", "SAPI unavailable"), 4000);
    }
    else if (act == bmAdd)
    {
        /* P1 适配（C2）：划词加书签（note=摘录≤200，对齐 WIN sel_bm） */
        int asec = -1; double asr = 0; QString ast;
        captureAnchor(m_selPage, &asec, &asr, &ast);
        long long id = Db::addBookmark(
            m_path, m_selPage,
            QString(hr::l10n::s("第%1页", "p.%1")).arg(m_selPage + 1),
            m_selText.trimmed().left(200),
            asec, asr, ast, m_pages);
        refreshBookmarkList();
        evt("sel_bm", QString("id=%1 page=%2 sec=%3")
                          .arg(id).arg(m_selPage + 1).arg(asec));
    }
    else if (act == noteAct)
    {
        /* v1.6.0（B2）：选文进笔记编辑器预填（安卓 DragingDialogs:2012-2022 语义） */
        hr::noteEditDialog(this, m_selText.trimmed());
    }
    else if (act == dcAct)
    {
        dictLookup(m_selText);
    }
}

bool ReaderWindow::eventFilter(QObject *obj, QEvent *ev)
{
    if (obj == m_scroll->viewport() && m_doc && m_pages > 0) /* P1（D2）：滚动模式开放划词 */
    {
        if (ev->type() == QEvent::MouseButtonPress)
        {
            QMouseEvent *me = static_cast<QMouseEvent *>(ev);
            if (me->button() == Qt::LeftButton)
            {
                m_dragStart = me->pos();
                m_dragging = false;
                m_selAuto.stop();
                clearSelection();
                /* v1.12.0：锚定按下点（页内局部坐标为权威）——跨页拖选/滚动跟随基准 */
                const int pg = selClampPageAt(me->pos());
                m_selP0 = m_selP1 = pg;
                m_selPt0 = m_selPt1 = selToLocal(pg, me->pos());
                m_selPage = pg;
            }
        }
        else if (ev->type() == QEvent::MouseMove)
        {
            QMouseEvent *me = static_cast<QMouseEvent *>(ev);
            if (!(QApplication::mouseButtons() & Qt::LeftButton))
                return QMainWindow::eventFilter(obj, ev);
            if (!m_dragging && (me->pos() - m_dragStart).manhattanLength() > 10)
                m_dragging = true;
            if (m_dragging)
            {
                /* v1.12.0：跨页拖选——锚点(页内局部)到当前点整段重算，滚动中自动跟随 */
                m_selLastVp = me->pos();
                m_selP1 = selClampPageAt(me->pos());
                m_selPt1 = selToLocal(m_selP1, me->pos());
                m_selSnap = 0;
                updateSelection();
                const QRectF vr = m_scroll->viewport()->rect();
                if (m_selLastVp.y() < vr.top() + 40 ||
                    m_selLastVp.y() > vr.bottom() - 40)
                    m_selAuto.start(); /* 上下缘 40px 内自动滚动（valueChanged 内重算） */
                else
                    m_selAuto.stop();
                return true;
            }
        }
        else if (ev->type() == QEvent::MouseButtonDblClick)
        {
            /* v1.12.0：双击选词（引擎词吸附），配合右键菜单操作 */
            QMouseEvent *me = static_cast<QMouseEvent *>(ev);
            if (me->button() != Qt::LeftButton)
                return QMainWindow::eventFilter(obj, ev);
            m_selAuto.stop();
            const int pg = selClampPageAt(me->pos());
            m_selP0 = m_selP1 = pg;
            m_selPt0 = m_selPt1 = selToLocal(pg, me->pos());
            m_selPage = pg;
            m_selSnap = 1;
            updateSelection();
            if (m_selText.trimmed().isEmpty())
                clearSelection();
            evt("sel_dbl", QString("page=%1 len=%2")
                            .arg(pg + 1).arg(m_selText.trimmed().size()));
            m_selDblAck = true; /* v1.12.1：双击的收尾 release 不再触发单击翻页 */
            return true;
        }
        else if (ev->type() == QEvent::MouseButtonRelease)
        {
            QMouseEvent *me = static_cast<QMouseEvent *>(ev);
            if (me->button() != Qt::LeftButton)
                return QMainWindow::eventFilter(obj, ev);
            const bool dblAck = m_selDblAck;
            m_selDblAck = false;
            if (dblAck && !m_dragging)
                return true; /* v1.12.1：双击选词的收尾 release 消费掉，不再翻页 */
            if (m_dragging)
            {
                m_dragging = false;
                m_selAuto.stop();
                /* v1.7.0：拖选只负责选中（菜单右键带出）；v1.12.0：跨页选区按页内
                 * 局部坐标落定，后续滚动高亮跟随内容 */
                if (m_selText.trimmed().isEmpty())
                    clearSelection();
                else
                {
                    refreshSelOverlay();
                    evt("sel_drag", QString("pages=%1-%2 len=%3")
                            .arg(qMin(m_selP0, m_selP1) + 1)
                            .arg(qMax(m_selP0, m_selP1) + 1)
                            .arg(m_selText.trimmed().size()));
                }
                return true;
            }
            const int w = m_scroll->viewport()->width();
            const int x = me->pos().x();
            if (!m_scrollMode) /* P1（D2）：滚动模式点击不翻页 */
            {
                if (x < w * 0.3) nav(-1);
                else if (x > w * 0.7) nav(1);
            }
            return true;
        }
        else if (ev->type() == QEvent::ContextMenu)
        {
            /* v1.7.0：正文右键=阅读菜单（复用划词菜单）。已有选区直接作用其上；
             * 无选区先按点自动选词（安卓长按同款），仍无文字也弹菜单（文字项置灰） */
            QContextMenuEvent *ce = static_cast<QContextMenuEvent *>(ev);
            m_dragging = false;
            const QPoint p = ce->pos();
            if (m_selText.trimmed().isEmpty())
            {
                /* v1.12.0：右键自动选词改词吸附（原 ±2px 矩形常落空，菜单全置灰） */
                const int pg = selClampPageAt(p);
                m_selP0 = m_selP1 = pg;
                m_selPt0 = m_selPt1 = selToLocal(pg, p);
                m_selPage = pg;
                m_selSnap = 1;
                updateSelection();
                if (!m_selText.trimmed().isEmpty())
                    refreshSelOverlay();
                evt("sel_rmb", QString("page=%1 len=%2")
                                .arg(pg + 1).arg(m_selText.trimmed().size()));
            }
            showSelMenu(ce->globalPos());
            return true;
        }
        else if (ev->type() == QEvent::Wheel)
        {
            if (!m_scrollMode)
            {
                /* U3a 修复（缺陷#8）：翻页模式滚轮=翻页 */
                QWheelEvent *we = static_cast<QWheelEvent *>(ev);
                const int dy = we->angleDelta().y();
                if (dy < 0) nav(1);
                else if (dy > 0) nav(-1);
                return true;
            }
            /* v0.109.0：滚动模式滚轮=前后页连续自由滚动（不拦截，交给滚动区） */
            return QMainWindow::eventFilter(obj, ev);
        }
    }
    return QMainWindow::eventFilter(obj, ev);
}

void ReaderWindow::onOpen()
{
    QString f = QFileDialog::getOpenFileName(this, hr::l10n::s("打开书", "Open book"),
                                             QString(),
        hr::l10n::s("书籍", "Books") +
            QString::fromLatin1(" (*.pdf *.epub *.txt *.mobi *.azw3 *.azw *.prc *.fb2 "
                                "*.html *.htm *.xps *.cbz *.doc *.docx *.rtf *.odt "
                                "*.djvu *.zip *.md *.pdb *.tif *.tiff *.cbr *.mht *.mhtml);;") +
            hr::l10n::s("所有文件 (*)", "All files (*)"));
    if (f.isEmpty()) return;
    /* v1.15.13：原走同步 openBook——转换+整书排版压在界面线程（没有"正在打开"遮罩），
     * 且不等/不作废在飞的后台重排与 phase2 计数就直接关旧 doc（UAF 风险），打开失败
     * 还静默。内嵌时交书库按"换书直达"新开阅读器（后台打开+失败收尾）；独立窗走后台打开 */
    if (receivers(SIGNAL(openPathRequested(QString))) > 0)
        emit openPathRequested(f);
    else
        openBookAsync(f, -1);
}

void ReaderWindow::onThemeNext()
{
    m_theme = (m_theme + 1) % 5;
    savePrefs();
    relayoutAfterChange(false); /* v0.109.0：主题切换不重数（分章书页数不变） */
}

void ReaderWindow::onFontBigger()
{
    if (m_fixedLayout)
    {
        /* P2 适配（B7）：固定版式 = 页面缩放 ×1.15（0.5..4，对齐 WIN zoomPdf） */
        m_zoom = qBound(0.5, m_zoom * 1.15, 4.0);
        relayoutAfterChange();
        return;
    }
    if (m_em < 32) { m_em += 0.5f; savePrefs(); relayoutAfterChange(); }
}

void ReaderWindow::onFontSmaller()
{
    if (m_fixedLayout)
    {
        m_zoom = qBound(0.5, m_zoom / 1.15, 4.0);
        relayoutAfterChange();
        return;
    }
    if (m_em > 8) { m_em -= 0.5f; savePrefs(); relayoutAfterChange(); }
}

void ReaderWindow::saveProgressNow()
{
    if (!m_doc || m_pages <= 0) return;
    Db::saveProgress(m_path, m_page, m_pages, m_viewW);
    evt("progress_save",
        QString("page=%1/%2 vw=%3").arg(m_page + 1).arg(m_pages).arg(m_viewW));
}

/* ---------- 目录 / 书签 ---------- */

/* ---------- U3b 侧板：目录/书签/搜索（顶栏按钮与划词"搜索同词"共用） ---------- */

void ReaderWindow::showPanel(int tab)
{
    if (!m_side) return;
    if (!m_side->isVisible()) m_side->show();
    m_sideTabs->setCurrentIndex(tab);
    if (tab == 0) refreshTocTree();
    else if (tab == 1) refreshBookmarkList();
    else if (tab == 2 && m_schEdit) m_schEdit->setFocus();
    evt("side_tab", QString("idx=%1").arg(tab));
}

/* v0.107.0（P2-6）：outline 缓存键——本地书 path#size#mtime 的 md5 前 12；
 * remote:// 或路径不可得时返回空（不缓存，与 accel 同口径） */
QString ReaderWindow::outlineCachePath() const
{
    if (m_path.startsWith(QString::fromLatin1("remote://"))) return QString();
    const QFileInfo fi(m_path);
    if (!fi.isFile()) return QString();
    /* v1.1.0：键带排版盐——字号/窗口变更后目录页码按新排版重算
     *（章节锚与目录显示都以当前排版为准；旧键缓存由 LRU 自然淘汰） */
    const QByteArray k = QCryptographicHash::hash(
        (m_path + "#" + QString::number(fi.size()) + "#" +
         QString::number(fi.lastModified().toMSecsSinceEpoch()) + "#" +
         QString::number(m_em) + "#v" + QString::number(m_viewW) +
         "h" + QString::number(m_viewH))
            .toUtf8(),
        QCryptographicHash::Md5).toHex();
    return dataDir() + QString::fromLatin1("/cache/outline/") + k.left(12) +
           QString::fromLatin1(".json");
}

/* v0.107.0（P2-6）：outline 缓存 LRU 留 50（mtime 新→旧，尾部的最旧先删） */
void ReaderWindow::outlineCacheTrim()
{
    const QDir d(dataDir() + QString::fromLatin1("/cache/outline"));
    if (!d.exists()) return;
    const QFileInfoList rest = d.entryInfoList(QDir::Files, QDir::Time);
    for (int i = 50; i < rest.size(); i++)
        QFile::remove(rest[i].absoluteFilePath());
}

void ReaderWindow::refreshTocTree()
{
    if (!m_tocTree) return;
    m_tocTree->clear();
    if (!m_doc) return;
    QVector<int> lvl, page;
    QVector<QString> title;
    /* v0.107.0（P2-6）：先试每书缓存，未命中再全量 hr_qt_outline 并落盘 */
    const QString cp = outlineCachePath();
    bool fromCache = false;
    if (!cp.isEmpty())
    {
        QFile cf(cp);
        if (cf.open(QIODevice::ReadOnly))
        {
            const QJsonArray ja = QJsonDocument::fromJson(cf.readAll()).array();
            for (const QJsonValue &v : ja)
            {
                const QJsonObject o = v.toObject();
                lvl.append(o.value(QString::fromLatin1("l")).toInt());
                title.append(o.value(QString::fromLatin1("t")).toString());
                page.append(o.value(QString::fromLatin1("p")).toInt());
            }
            cf.close();
            fromCache = !ja.isEmpty();
        }
    }
    int n = lvl.size();
    if (!fromCache)
    {
        struct Ctx
        {
            QVector<int> *lvl;
            QVector<QString> *title;
            QVector<int> *page;
        } ctx{&lvl, &title, &page};
        auto cb = +[](void *ud, int level, const char *t, int pg) -> int
        {
            auto *c = (Ctx *)ud;
            c->lvl->append(level);
            c->title->append(QString::fromUtf8(t));
            c->page->append(pg);
            return 1;
        };
        const qint64 ot0 = QDateTime::currentMSecsSinceEpoch();
        evt("toc_step", "outline_pre"); /* v1.15.11：取证桩 */
        n = hr_qt_outline(m_doc, cb, &ctx);
        evt("toc_step", QString("outline_post n=%1 ms=%2")
                             .arg(n).arg(QDateTime::currentMSecsSinceEpoch() - ot0));
        if (!cp.isEmpty() && n > 0)
        {
            QJsonArray ja;
            for (int i = 0; i < lvl.size(); i++)
            {
                QJsonObject o;
                o.insert(QString::fromLatin1("l"), lvl[i]);
                o.insert(QString::fromLatin1("t"), title[i]);
                o.insert(QString::fromLatin1("p"), page[i]);
                ja.append(o);
            }
            QDir().mkpath(QFileInfo(cp).absolutePath());
            QFile wf(cp);
            if (wf.open(QIODevice::WriteOnly | QIODevice::Truncate))
            {
                wf.write(QJsonDocument(ja).toJson(QJsonDocument::Compact));
                wf.close();
                outlineCacheTrim();
            }
        }
    }
    /* v1.1.0：目录页表入成员（章节锚捕获/折算、书签落点显示共用） */
    m_tocPage = page;
    m_tocTitle = title;
    QVector<QTreeWidgetItem *> stack(1, nullptr);
    for (int i = 0; i < lvl.size(); i++)
    {
        while ((int)stack.size() > lvl[i] + 1) stack.pop_back();
        while ((int)stack.size() < lvl[i] + 1) stack.append(stack.last());
        QTreeWidgetItem *it = new QTreeWidgetItem(QStringList()
            << (page[i] >= 0
                    ? QString(hr::l10n::s("%1   （第%2页）", "%1   (p.%2)"))
                          .arg(title[i]).arg(page[i] + 1)
                    : title[i]));
        if (stack.last()) stack.last()->addChild(it);
        else m_tocTree->addTopLevelItem(it);
        it->setData(0, Qt::UserRole, page[i]);
        if ((int)stack.size() == lvl[i] + 1) stack.append(it);
        else stack[lvl[i] + 1] = it;
    }
    m_tocTree->expandAll();
    evt("toc_side", QString("items=%1 cached=%2").arg(n).arg(fromCache ? 1 : 0));
}

void ReaderWindow::refreshBookmarkList()
{
    if (!m_bmList) return;
    m_bmRows = Db::listBookmarks(m_path);
    m_bmList->clear();
    for (const BookmarkRow &r : m_bmRows)
    {
        /* v1.2.0（B2/B3）：收藏标★ / AI 笔记🤖 前缀（安卓键面消费展示） */
        QString prefix;
        if (r.isF) prefix += QString::fromUtf8("\xe2\x98\x85 ");   /* ★ */
        if (r.isAiNote) /* v1.6.0（B5）：答复非空=AI 笔记🤖；手工笔记=📝（正文=label） */
            prefix += (r.aiAnswer.isEmpty() ? QString::fromUtf8("\xf0\x9f\x93\x9d ")
                                            : QString::fromUtf8("\xf0\x9f\xa4\x96 "));
        QListWidgetItem *it = new QListWidgetItem(
            prefix +
            QString(hr::l10n::s("%1    第%2页", "%1    p.%2"))
                .arg(r.label)
                .arg(resolveBookmarkPage(r) + 1) +
            (r.isAiNote
                 ? (r.aiAnswer.isEmpty() /* v1.6.0（B5）：手工笔记答复列空着不显示占位 */
                        ? QString()
                        : QString(hr::l10n::s("　·%1", "  ·%1")).arg(r.aiAnswer.left(24)))
                 : (r.note.isEmpty()
                        ? QString()
                        : QString(hr::l10n::s("　·%1", "  ·%1")).arg(r.note.left(24)))));
        it->setToolTip(r.isAiNote ? (r.aiAnswer.isEmpty() ? r.label : r.aiAnswer)
                                  : r.note); /* P1（C1）/v1.6.0（B5）：手工笔记 tooltip=正文 */
        m_bmList->addItem(it);
    }
    evt("bm_side", QString("rows=%1").arg(m_bmRows.size()));
    refreshHighlightList(); /* U-ann：书签板高亮分区同步刷新 */
}

void ReaderWindow::refreshHighlightList()
{
    if (!m_hlList) return;
    m_hlRows = Db::listAnnotations(m_path, -1);
    m_hlList->clear();
    for (const Db::AnnotationRow &r : m_hlRows)
    {
        QPixmap pm(12, 12);
        pm.fill(QColor(QRgb(0xFF000000 | (r.color & 0xFFFFFF))));
        QListWidgetItem *it = new QListWidgetItem(
            QIcon(pm), QString(hr::l10n::s("第%1页    %2", "p.%1    %2"))
                           .arg(r.page + 1)
                           .arg(r.note.isEmpty() ? hr::l10n::s("高亮", "Highlight") : r.note));
        it->setData(Qt::UserRole, (qlonglong)r.id);
        it->setData(Qt::UserRole + 1, r.page);
        m_hlList->addItem(it);
    }
    m_hlLb->setText(QString(hr::l10n::s("高亮（%1）", "Highlights (%1)"))
                        .arg(m_hlRows.size()));
    evt("hl_side", QString("rows=%1").arg(m_hlRows.size()));
}

/* U-ann：高亮缓存重载 + 重渲当前页（增删后调用；两模式统一走位图合成） */
void ReaderWindow::refreshHighlights()
{
    loadHighlightCache();
    if (!m_doc) return;
    if (m_scrollMode)
    {
        if (m_page >= 0 && m_page < m_labelW.size()) m_labelW[m_page] = -1; /* 失效重渲 */
        fillVisible();
    }
    else
        renderNow(m_page);
}

/* U-ann：按页缓存本书全部高亮（pageCount 不符者跳过，防重排错位——Compose 同口径） */
void ReaderWindow::loadHighlightCache()
{
    m_hlCache.clear();
    m_hlCachePath = m_path;
    if (m_path.isEmpty()) return;
    for (const Db::AnnotationRow &r : Db::listAnnotations(m_path, -1))
    {
        if (r.pageCount != m_pages) continue;
        QVector<QRectF> quads;
        const QStringList parts = r.quads.split(';', Qt::SkipEmptyParts);
        for (const QString &part : parts)
        {
            const QStringList xywh = part.split(',');
            if (xywh.size() != 4) continue;
            bool o1 = false, o2 = false, o3 = false, o4 = false;
            const float x0 = xywh[0].toFloat(&o1), y0 = xywh[1].toFloat(&o2),
                        x1 = xywh[2].toFloat(&o3), y1 = xywh[3].toFloat(&o4);
            if (o1 && o2 && o3 && o4)
                quads.append(QRectF(qMin(x0, x1), qMin(y0, y1), qAbs(x1 - x0), qAbs(y1 - y0)));
        }
        if (!quads.isEmpty()) m_hlCache[r.page].append(qMakePair(quads, (unsigned)r.color));
    }
}

/* U-ann：把该页高亮按位图比例合成（页坐标→像素：(q-box)*scale） */
void ReaderWindow::compositeHighlights(int page, QImage *img)
{
    if (!img || img->isNull() || !m_doc) return;
    if (m_hlCachePath != m_path) loadHighlightCache();
    const auto it = m_hlCache.constFind(page);
    if (it == m_hlCache.constEnd() || it->isEmpty()) return;
    float box[4] = {0, 0, 0, 0};
    hr_qt_page_box(m_doc, page, box);
    const float bw = box[2] - box[0];
    if (bw <= 0) return;
    const double scale = img->width() / (double)bw;
    if (img->format() != QImage::Format_RGB32 && img->format() != QImage::Format_ARGB32)
        *img = img->convertToFormat(QImage::Format_RGB32);
    QPainter p(img);
    p.setPen(Qt::NoPen);
    for (const auto &item : *it)
    {
        QColor c(QRgb(0xFF000000 | (item.second & 0xFFFFFF)));
        c.setAlpha(90);
        p.setBrush(c);
        for (const QRectF &q : item.first)
            p.drawRoundedRect(QRectF((q.left() - box[0]) * scale, (q.top() - box[1]) * scale,
                                     q.width() * scale, q.height() * scale), 3, 3);
    }
    evt("hl_render", QString("page=%1 n=%2").arg(page + 1).arg(it->size()));
}


/* 缺陷#4：远程书首渲第 0 页 → 缩略图落盘（r-<md5(key)>.png，coverPathFor remote 分支同键） */
void ReaderWindow::maybeSaveRemoteCover(int page, const QImage &img)
{
    if (page != 0 || img.isNull() || !m_path.startsWith(QString::fromLatin1("remote://")))
        return;
    const QString png = hr::dataDir() + "/cache/covers/r-" +
                        QString::fromLatin1(QCryptographicHash::hash(m_path.toUtf8(),
                                                                     QCryptographicHash::Md5)
                                                .toHex().left(12)) + ".png";
    if (QFileInfo(png).isFile()) return;
    QDir().mkpath(QFileInfo(png).absolutePath());
    if (img.scaledToWidth(320, Qt::SmoothTransformation).save(png))
        evt("cover_remote", QString("key=%1").arg(m_path.left(70)));
}

void ReaderWindow::doSideSearch()
{
    if (!m_doc || !m_schEdit || !m_schList || !m_schLb) return;
    /* P2 适配（C6）：再次触发=取消（原同步循环无进度无取消） */
    if (m_searchRunning)
    {
        m_searchCancel = true;
        m_schLb->setText(hr::l10n::s("取消中…", "Cancelling…"));
        return;
    }
    const QString needle = m_schEdit->text().trimmed();
    if (needle.isEmpty()) return;
    m_schList->clear();
    m_searchRunning = true;
    m_searchCancel = false;
    const int gen = ++m_searchGen;
    const int pages = m_pages;
    evt("search_begin", QString("q=%1 pages=%2").arg(needle.left(40)).arg(pages));
    QPointer<ReaderWindow> self(this);
    QThreadPool::globalInstance()->start(QRunnable::create([self, gen, needle, pages]
    {
        if (!self) return;
        const QByteArray nb = needle.toUtf8();
        void *doc = self->hrDoc();
        int total = 0, lastDone = 0;
        for (int p = 0; p < pages; ++p)
        {
            if (!self || self->m_searchGenLoad() != gen) return; /* 换书作废 */
            if (self->m_searchCancel)
            {
                QMetaObject::invokeMethod(self, [self]
                {
                    if (!self) return;
                    self->m_searchRunning = false;
                    self->m_schLb->setText(hr::l10n::s("已取消", "Cancelled"));
                    evt("search_cancel", QString());
                }, Qt::QueuedConnection);
                return;
            }
            const int c = hr_qt_search_page(doc, p, nb.constData());
            if (c > 0)
            {
                total += c;
                QMetaObject::invokeMethod(self, [self, p, c]
                {
                    if (!self || !self->m_schList) return;
                    QListWidgetItem *wi = new QListWidgetItem(
                        QString(hr::l10n::s("第 %1 页    %2 处", "p.%1    %2 hits"))
                            .arg(p + 1)
                            .arg(c));
                    wi->setData(Qt::UserRole, p); /* 跳页用数据，不依赖文本格式 */
                    self->m_schList->addItem(wi);
                }, Qt::QueuedConnection);
            }
            if (p + 1 - lastDone >= 5 || p + 1 == pages)
            {
                lastDone = p + 1;
                QMetaObject::invokeMethod(self, [self, done = p + 1, total, pages]
                {
                    if (!self || !self->m_schLb || !self->m_searchRunning) return;
                    self->m_schLb->setText(
                        hr::l10n::s("扫描中 %1/%2 · %3 处（再点=取消）",
                                    "Scanning %1/%2 · %3 hits")
                            .arg(done)
                            .arg(pages)
                            .arg(total));
                }, Qt::QueuedConnection);
            }
        }
        QMetaObject::invokeMethod(self, [self, total, needle]
        {
            if (!self || !self->m_schLb) return;
            self->m_searchRunning = false;
            self->m_schLb->setText(hr::l10n::s("%1 页命中，共 %2 处",
                                               "%1 pages, %2 hits")
                                       .arg(self->m_schList ? self->m_schList->count() : 0)
                                       .arg(total));
            evt("search_side",
                QString("q=%1 hits=%2 total=%3")
                    .arg(needle.left(40))
                    .arg(self->m_schList ? self->m_schList->count() : 0)
                    .arg(total));
        }, Qt::QueuedConnection);
    }));
}

void ReaderWindow::sideSearch(const QString &pre)
{
    showPanel(2);
    if (!pre.isEmpty())
    {
        m_schEdit->setText(pre);
        doSideSearch();
    }
}

void ReaderWindow::onToc() { showPanel(0); }

/* ---------- v1.1.0：章节锚（安卓 1.3.21 互通） ---------- */

void ReaderWindow::captureAnchor(int page, int *sec, double *sr, QString *st)
{
    *sec = -1;
    *sr = 0;
    *st = QString();
    if (!hr::anchorCapture(m_tocPage, page, m_pages, sec, sr)) return;
    if (*sec >= 0 && *sec < m_tocTitle.size()) *st = m_tocTitle[*sec];
    else { *sec = -1; *sr = 0; *st = QString(); }
}

int ReaderWindow::resolveBookmarkPage(const BookmarkRow &r) const
{
    /* 安卓 1.3.21 跳转优先级：同排版纪元绝对页精确 → 章节锚折算 → 比例回落 */
    const bool stOk = r.st.isEmpty() ||
                      (r.sec >= 0 && r.sec < m_tocTitle.size() &&
                       m_tocTitle[r.sec] == r.st);
    if (r.sec >= 0 && r.sec < m_tocPage.size() && stOk)
    {
        const int pg = hr::anchorResolve(m_tocPage, m_pages, r.sec, r.sr);
        if (pg >= 0) return pg;
    }
    if (r.pgs > 0 && r.pgs != m_pages)
        return qBound(0, (int)qRound((r.page + 1.0) / r.pgs * m_pages) - 1, m_pages - 1);
    return r.page;
}

void ReaderWindow::jumpToBookmark(int idx)
{
    if (idx < 0 || idx >= m_bmRows.size()) return;
    const BookmarkRow r = m_bmRows[idx];
    const int pg = resolveBookmarkPage(r);
    evt("bm_jump", QString("id=%1 from=%2 to=%3 sec=%4 sr=%5 pgs=%6")
                       .arg(r.id).arg(r.page + 1).arg(pg + 1).arg(r.sec)
                       .arg(r.sr, 0, 'f', 4).arg(r.pgs));
    jumpTo(pg);
}

/* v1.12.2：笔记行只读弹正文（aiAnswer 优先、label 兜底）——安卓
 * BookmarksFragment2.showNoteDialog 同语义；侧板双击/右键共用 */
void ReaderWindow::showNoteBody(int i)
{
    if (i < 0 || i >= m_bmRows.size()) return;
    const BookmarkRow &r = m_bmRows[i];
    const QString body = hr::noteBodyText(r);
    QMessageBox::information(this,
                             r.aiAnswer.isEmpty() ? hr::l10n::s("笔记", "Note")
                                                  : hr::l10n::s("AI 笔记", "AI note"),
                             body.isEmpty() ? hr::l10n::s("（无正文）", "(empty)") : body);
    evt("bm_note_view", QString("id=%1 ai=%2").arg(r.id).arg(r.aiAnswer.isEmpty() ? 0 : 1));
}

void ReaderWindow::onAddBookmark()
{
    if (!m_doc) return;
    int sec = -1; double sr = 0; QString st;
    captureAnchor(m_page, &sec, &sr, &st);
    long long id = Db::addBookmark(
        m_path, m_page,
        QString(hr::l10n::s("第%1页", "p.%1")).arg(m_page + 1), QString(),
        sec, sr, st, m_pages);
    evt("bm_add", QString("id=%1 page=%2 sec=%3 sr=%4 st=%5")
                       .arg(id).arg(m_page + 1).arg(sec)
                       .arg(sr, 0, 'f', 4).arg(st.left(24)));
}

/* v1.6.0（B1-B3）：保存笔记——安卓 NoteEditDialog 同构：isAiNote=true 书签、正文→label（=text
 * 键面，D2：笔记正文绝不写 note 列）；落笔即带锚（captureAnchor+pgs，D3）。返回创建毫秒（union 键）。 */
long long ReaderWindow::addNoteBookmark(const QString &label, const QString &aiAnswer, int page)
{
    if (m_path.isEmpty() || m_pages <= 0) return -1;
    int sec = -1; double sr = 0; QString st;
    captureAnchor(page, &sec, &sr, &st);
    /* union 键=创建毫秒（§7.3 同毫秒后到让位）：连续保存单调化，冒烟/批存零撞 */
    static long long s_lastMs = 0;
    long long now = QDateTime::currentMSecsSinceEpoch();
    if (now <= s_lastMs) now = s_lastMs + 1;
    s_lastMs = now;
    Db::addBookmarkRaw(m_path, page, label, QString(), now, sec, sr, st, m_pages,
                       0, 1, aiAnswer);
    refreshBookmarkList();
    evt("note_add", QString("page=%1 sec=%2 sr=%3 pgs=%4 ai=%5 len=%6")
                        .arg(page + 1).arg(sec).arg(sr, 0, 'f', 4).arg(m_pages)
                        .arg(aiAnswer.isEmpty() ? 0 : 1).arg(label.size()));
    return now;
}

void ReaderWindow::onBookmarks() { showPanel(1); }

/* ---------- v0.59.0 偏好页基础版 ---------- */

QString ReaderWindow::userCss() const
{
    /* ReaderPrefs.css 基础版移植：@page 边距 / 行距 / 缩进 / 对齐 / 字号复位 / 段距；
     * 默认值与 Compose ReaderPrefs 默认一致（1.8em 行距、2em 缩进、0.2em 段距、两端对齐）。 */
    QString c;
    if (!m_fontFamily.isEmpty())
    {
        /* P1 适配（B9）：字体族——dataDir\fonts 下同名 ttf/otf=@font-face；关键字不加引号 */
        const QString base = dataDir() + QString::fromLatin1("/fonts/") + m_fontFamily;
        const QString fp =
            QFileInfo::exists(base + QString::fromLatin1(".ttf"))
                ? base + QString::fromLatin1(".ttf")
                : (QFileInfo::exists(base + QString::fromLatin1(".otf"))
                       ? base + QString::fromLatin1(".otf")
                       : QString());
        if (!fp.isEmpty())
            c += QString("@font-face { font-family: '%1'; src: url('%2'); }\n")
                     .arg(m_fontFamily, QUrl::fromLocalFile(fp).toString());
        const bool kw = (m_fontFamily == QString::fromLatin1("sans-serif") ||
                         m_fontFamily == QString::fromLatin1("monospace") ||
                         m_fontFamily == QString::fromLatin1("serif"));
        c += QString("body, p, div, span, pre { font-family: %1 !important; }\n")
                 .arg(kw ? m_fontFamily : "'" + m_fontFamily + "'");
    }
    c += QString("@page { margin-top: %1pt !important; margin-right: %2pt !important; "
                 "margin-bottom: %3pt !important; margin-left: %4pt !important; }\n")
             .arg(m_marginT).arg(m_marginR).arg(m_marginB).arg(m_marginL);
    c += QString("body, div, p, span { line-height: %1em !important; "
                 "text-indent: %2em !important; text-align: %3; font-size: medium !important; }\n")
             .arg(m_lineHeight / 100.0, 0, 'f', 2)
             .arg(m_textIndent / 10.0, 0, 'f', 1)
             .arg(m_align);
    if (m_paraSpacing > 0)
        c += QString("div, p { margin: %1em 0 !important; }\n")
                 .arg(m_paraSpacing / 5.0, 0, 'f', 1);
    c += "code, pre, pre>* { white-space: pre-wrap; font-size: 0.8em; }\n";
    return c;
}

void ReaderWindow::applyUserCss() const
{
    hr_qt_set_css(userCss().toUtf8().constData(), m_useDocCss ? 1 : 0); /* P1（B10） */
}

void ReaderWindow::onPrefs()
{
    QDialog dlg(this);
    dlg.setWindowTitle(hr::l10n::s("偏好", "Preferences"));
    dlg.resize(460, 560);
    QVBoxLayout *lay = new QVBoxLayout(&dlg);
    QFormLayout *form = new QFormLayout;

    QDoubleSpinBox *em = new QDoubleSpinBox(&dlg);
    em->setRange(8.0, 32.0);
    em->setSingleStep(0.5);
    em->setValue(m_em);
    QSpinBox *lh = new QSpinBox(&dlg);
    lh->setRange(100, 250);
    lh->setValue(m_lineHeight);
    QSpinBox *ti = new QSpinBox(&dlg);
    ti->setRange(0, 50);
    ti->setValue(m_textIndent);
    QSpinBox *ps = new QSpinBox(&dlg);
    ps->setRange(0, 20);
    ps->setValue(m_paraSpacing);
    QSpinBox *mt = new QSpinBox(&dlg);
    mt->setRange(0, 60);
    mt->setValue(m_marginT);
    QSpinBox *mb = new QSpinBox(&dlg);
    mb->setRange(0, 60);
    mb->setValue(m_marginB);
    QSpinBox *ml = new QSpinBox(&dlg);
    ml->setRange(0, 60);
    ml->setValue(m_marginL);
    QSpinBox *mr = new QSpinBox(&dlg);
    mr->setRange(0, 60);
    mr->setValue(m_marginR);
    QSpinBox *as = new QSpinBox(&dlg);
    as->setRange(100, 5000);
    as->setSingleStep(100);
    as->setValue(m_autoMs);
    QSpinBox *ta = new QSpinBox(&dlg);
    ta->setRange(0, 600);
    ta->setSingleStep(10);
    ta->setValue(m_ttsAdvanceSec);
    QWidget *wTB = new QWidget(&dlg);
    QHBoxLayout *hTB = new QHBoxLayout(wTB);
    hTB->setContentsMargins(0, 0, 0, 0);
    hTB->addWidget(mt);
    hTB->addWidget(mb);
    QWidget *wLR = new QWidget(&dlg);
    QHBoxLayout *hLR = new QHBoxLayout(wLR);
    hLR->setContentsMargins(0, 0, 0, 0);
    hLR->addWidget(ml);
    hLR->addWidget(mr);

    QComboBox *alignC = new QComboBox(&dlg); /* P1：对齐四态（WIN align 键互通） */
    alignC->addItem(hr::l10n::s("两端对齐", "Justify"), "justify");
    alignC->addItem(hr::l10n::s("左对齐", "Left"), "left");
    alignC->addItem(hr::l10n::s("右对齐", "Right"), "right");
    alignC->addItem(hr::l10n::s("居中", "Center"), "center");
    alignC->setCurrentIndex(qMax(0, alignC->findData(m_align)));
    QSpinBox *mc = new QSpinBox(&dlg); /* P1：每行字数版心（0=全宽） */
    mc->setRange(0, 120);
    mc->setValue(m_maxChars);
    mc->setSpecialValueText(hr::l10n::s("全宽", "Full width"));
    QCheckBox *udc = new QCheckBox(hr::l10n::s("遵循书内样式", "Use document CSS"), &dlg);
    udc->setChecked(m_useDocCss);
    QComboBox *theme = new QComboBox(&dlg);
    for (int i = 0; i < 5; i++)
        theme->addItem(hr::l10n::s(QString::fromUtf8(kThemes[i].zh), QString::fromUtf8(kThemes[i].id)),
                       i);
    theme->setCurrentIndex(m_theme);
    QCheckBox *scroll = new QCheckBox(hr::l10n::s("滚动模式", "Scroll mode"), &dlg);
    scroll->setChecked(m_scrollMode);
    QCheckBox *two = new QCheckBox(hr::l10n::s("双页", "Two pages"), &dlg);
    two->setChecked(m_twoPage);
    QComboBox *lang = new QComboBox(&dlg);
    const QFile langF(dataDir() + "/lang.json");
    lang->addItem(hr::l10n::s("跟随系统", "System"), "");
    const QStringList langs = hr::l10n::languages();
    for (const QString &l : langs) lang->addItem(l, l);
    if (langF.exists())
    {
        const int ix = lang->findData(hr::l10n::currentLang());
        lang->setCurrentIndex(ix >= 0 ? ix : 0);
    }
    else lang->setCurrentIndex(0);

    form->addRow(hr::l10n::s("字号", "Font size"), em);
    form->addRow(hr::l10n::s("行距 %", "Line height %"), lh);
    form->addRow(hr::l10n::s("首行缩进 (0.1em)", "Text indent (0.1em)"), ti);
    form->addRow(hr::l10n::s("段距 (0.1em)", "Para spacing (0.1em)"), ps);
    form->addRow(hr::l10n::s("页边距 上/下 (pt)", "Margin T/B (pt)"), wTB);
    form->addRow(hr::l10n::s("页边距 左/右 (pt)", "Margin L/R (pt)"), wLR);
    form->addRow(hr::l10n::s("对齐", "Alignment"), alignC);
    form->addRow(hr::l10n::s("每行字数(0=全宽)", "Chars per line (0=full)"), mc);
    form->addRow(QString(), udc);
    form->addRow(hr::l10n::s("阅读主题", "Theme"), theme);
    /* P2 适配（B6）：阅读质感（5 纹理，浅色主题生效） */
    QComboBox *texC = new QComboBox(&dlg);
    texC->addItem(hr::l10n::s("无", "None"), "none");
    texC->addItem(hr::l10n::s("牛皮纸", "Kraft"), "paper");
    texC->addItem(hr::l10n::s("米色纤维", "Fiber"), "fiber");
    texC->addItem(hr::l10n::s("亚麻布", "Linen"), "linen");
    texC->addItem(hr::l10n::s("羊皮纸", "Parchment"), "parchment");
    texC->addItem(hr::l10n::s("帆布", "Canvas"), "canvas");
    texC->setCurrentIndex(qMax(0, texC->findData(m_texture)));
    form->addRow(hr::l10n::s("阅读质感", "Texture"), texC);
    form->addRow(QString(), scroll);
    form->addRow(QString(), two);
    form->addRow(hr::l10n::s("自动滚动间隔(ms)", "Auto-scroll (ms)"), as);
    form->addRow(hr::l10n::s("朗读自动翻页(秒,0=不)", "TTS advance (s, 0=off)"), ta);
    /* P1 适配（D6）：朗读参数（语速/音量/音色；SAPI 注入） */
    QSettings stTts(dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
    QSpinBox *tRate = new QSpinBox(&dlg);
    tRate->setRange(-10, 10);
    tRate->setValue(stTts.value("tts/rate", 0).toInt());
    QSpinBox *tVol = new QSpinBox(&dlg);
    tVol->setRange(0, 100);
    tVol->setValue(stTts.value("tts/volume", 100).toInt());
    QComboBox *tVoice = new QComboBox(&dlg);
    tVoice->addItem(hr::l10n::s("系统默认", "System default"), QString());
    const QStringList vs = Tts::voices();
    for (const QString &v : vs) tVoice->addItem(v, v);
    {
        const int ix = tVoice->findData(stTts.value("tts/voice").toString());
        tVoice->setCurrentIndex(ix >= 0 ? ix : 0);
    }
    form->addRow(hr::l10n::s("朗读语速(-10..10)", "TTS rate (-10..10)"), tRate);
    form->addRow(hr::l10n::s("朗读音量(0..100)", "TTS volume (0..100)"), tVol);
    form->addRow(hr::l10n::s("朗读音色", "TTS voice"), tVoice);
    /* P1 适配（B9）：字体族（书内默认/CSS 关键字/dataDir\fonts 用户字体） */
    QComboBox *famC = new QComboBox(&dlg);
    famC->addItem(hr::l10n::s("书内默认", "Document default"), QString());
    famC->addItem("sans-serif", "sans-serif");
    famC->addItem("monospace", "monospace");
    famC->addItem("serif", "serif");
    {
        QDir fd(dataDir() + "/fonts");
        for (const QFileInfo &ffi :
             fd.entryInfoList(QStringList{"*.ttf", "*.otf"}, QDir::Files))
            famC->addItem(ffi.completeBaseName(), ffi.completeBaseName());
    }
    famC->setCurrentIndex(qMax(0, famC->findData(m_fontFamily)));
    form->addRow(hr::l10n::s("字体族", "Font family"), famC);
    form->addRow(hr::l10n::s("界面语言（重启生效）", "UI language (restart to apply)"), lang);
    lay->addLayout(form);

    QDialogButtonBox *bb =
        new QDialogButtonBox(QDialogButtonBox::Ok | QDialogButtonBox::Cancel, &dlg);
    bb->button(QDialogButtonBox::Ok)->setText(hr::l10n::s("确定", "OK"));
    bb->button(QDialogButtonBox::Cancel)->setText(hr::l10n::s("取消", "Cancel"));
    lay->addWidget(bb);
    connect(bb, &QDialogButtonBox::accepted, &dlg, &QDialog::accept);
    connect(bb, &QDialogButtonBox::rejected, &dlg, &QDialog::reject);
    if (dlg.exec() != QDialog::Accepted) return;

    m_em = (float)em->value();
    m_lineHeight = lh->value();
    m_textIndent = ti->value();
    m_paraSpacing = ps->value();
    m_marginT = mt->value();
    m_marginB = mb->value();
    m_marginL = ml->value();
    m_marginR = mr->value();
    m_align = alignC->currentData().toString();
    m_maxChars = mc->value();
    m_useDocCss = udc->isChecked();
    stTts.setValue("tts/rate", tRate->value());
    stTts.setValue("tts/volume", tVol->value());
    stTts.setValue("tts/voice", tVoice->currentData().toString());
    m_fontFamily = famC->currentData().toString();
    m_texture = texC->currentData().toString();
    m_theme = theme->currentData().toInt();
    m_scrollMode = scroll->isChecked();
    m_twoPage = two->isChecked();
    m_autoMs = as->value();
    m_ttsAdvanceSec = ta->value();
    hr::l10n::setLang(lang->currentData().toString());
    savePrefs();
    relayoutAfterChange();
}


/* ---------- v0.60 批次二：划词翻译 / 离线词典 ---------- */

void ReaderWindow::translateSelection(const QString &text, int tgtMode)
{
    const QString t = text.trimmed();
    if (t.isEmpty()) return;
    const hr::AiConfig c = hr::AiConfig::load();
    if (!c.usable())
    {
        hr::m4AiConfigDialog(this);
        return;
    }
    const bool srcCjk = t.contains(QRegularExpression("[\\x{4e00}-\\x{9fff}]"));
    /* U3b：目标语三选 0=自动（原文中日韩→英文，否则→中文） 1=固定中文 2=固定英文 */
    QString tgt;
    if (tgtMode == 1) tgt = hr::l10n::s("简体中文", "Simplified Chinese");
    else if (tgtMode == 2) tgt = QString("English");
    else tgt = srcCjk ? QString("English")
                      : hr::l10n::s("简体中文", "Simplified Chinese");
    const QString prompt = QString("将下面的内容翻译成%1，只输出译文，不要解释：\n\n%2").arg(tgt, t);

    QDialog dlg(this);
    dlg.setWindowTitle(hr::l10n::s("翻译", "Translate") + " — " + tgt);
    dlg.resize(560, 420);
    QVBoxLayout *lay = new QVBoxLayout(&dlg);
    QTextBrowser *tb = new QTextBrowser(&dlg);
    tb->setPlainText(hr::l10n::s("翻译中…", "Translating…"));
    lay->addWidget(tb, 1);
    QPushButton *close = new QPushButton(hr::l10n::s("关闭", "Close"), &dlg);
    lay->addWidget(close);
    connect(close, &QPushButton::clicked, &dlg, &QDialog::accept);
    dlg.show();

    QThreadPool::globalInstance()->start(QRunnable::create([this, &dlg, tb, prompt, tgt, t]
    {
        QString err;
        /* P1 适配（D11）：划词翻译会话级缓存（同词同目标语不重复计费） */
        const QString ans = hr::aiAskCached(
            hr::AiConfig::load(),
            QString::fromLatin1("sel|%1|%2").arg(
                tgt, QString::fromLatin1(QCryptographicHash::hash(t.toUtf8(),
                                          QCryptographicHash::Md5).toHex())),
            prompt, &err, 2048);
        QMetaObject::invokeMethod(this, [this, &dlg, tb, ans, err]
        {
            tb->setPlainText(ans.isEmpty() ? (hr::l10n::s("翻译失败：", "Translate failed: ") + err) : ans);
            evt("sel_translate", QString("len=%1 ok=%2 err=%3")
                                      .arg(ans.size() ? 1 : 0).arg(ans.size()).arg(err.left(60)));
        }, Qt::QueuedConnection);
    }));
}

void ReaderWindow::dictLookup(const QString &text)
{
    const QString w = text.trimmed();
    if (w.isEmpty()) return;
    QDialog dlg(this);
    dlg.setWindowTitle(hr::l10n::s("词典", "Dictionary") + " — " + w.left(40));
    dlg.resize(560, 460);
    QVBoxLayout *lay = new QVBoxLayout(&dlg);
    QTextBrowser *tb = new QTextBrowser(&dlg);
    tb->setPlainText(hr::l10n::s("查词中…", "Looking up…"));
    lay->addWidget(tb, 1);
    QPushButton *close = new QPushButton(hr::l10n::s("关闭", "Close"), &dlg);
    lay->addWidget(close);
    connect(close, &QPushButton::clicked, &dlg, &QDialog::accept);
    dlg.show();

    QThreadPool::globalInstance()->start(QRunnable::create([this, &dlg, tb, w]
    {
        hr::Dicts::refresh(); /* 首查装载词典索引 */
        const QList<hr::DictHit> hits = hr::Dicts::lookupAll(w);
        QMetaObject::invokeMethod(this, [this, &dlg, tb, hits]
        {
            if (hits.isEmpty())
            {
                tb->setPlainText(hr::l10n::s(
                    "未命中——把 StarDict 词典文件夹放入数据目录 dicts/ 后重启即可（.ifo/.idx/.dict）",
                    "No hits — put StarDict folders into <data>/dicts and restart"));
            }
            else
            {
                QString html;
                for (const hr::DictHit &h : hits)
                    html += QString("<h3>%1</h3><p>%2</p>").arg(h.book.toHtmlEscaped(),
                                                                h.text.toHtmlEscaped());
                tb->setHtml(html);
            }
            evt("dict_lookup", QString("hits=%1 dicts=%2")
                                   .arg(hits.size()).arg(hr::Dicts::installedCount()));
        }, Qt::QueuedConnection);
    }));
}

/* ---------- 批次三：TTS 定时翻页（计时器在 ctor 接线） ---------- */

void ReaderWindow::armTtsAdvance(int sec)
{
    if (sec <= 0) return;
    m_ttsTimer.start(sec * 1000);
}

void ReaderWindow::disarmTtsAdvance()
{
    const bool wasArmed = m_ttsTimer.isActive();
    m_ttsTimer.stop();
    /* v1.3.0（I）：朗读会话结束落"快速书签"（安卓 TTSService fastTTSBookmakr 同语义；
     * 同页去重；tts/autoBookmark 默认开，偏好 tts/autoBookmark=0 关闭） */
    if (wasArmed && m_doc &&
        QSettings(dataDir() + "/qt-prefs.ini", QSettings::IniFormat)
            .value("tts/autoBookmark", 1).toInt() != 0)
    {
        bool have = false;
        for (const BookmarkRow &r : m_bmRows)
            if (r.page == m_page) { have = true; break; }
        if (!have)
        {
            int sec = -1;
            double sr = 0;
            QString st;
            captureAnchor(m_page, &sec, &sr, &st);
            const long long id = Db::addBookmark(
                m_path, m_page, hr::l10n::s("快速书签", "Quick bookmark"), QString(),
                sec, sr, st, m_pages);
            evt("bm_tts_add", QString("id=%1 page=%2 sec=%3").arg(id).arg(m_page + 1).arg(sec));
            refreshBookmarkList();
        }
    }
}

/* ---------- 偏好 ---------- */


void ReaderWindow::loadPrefs()
{
    QSettings st(dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
    /* v0.105.0（P2-5）：排版默认字号对齐安卓 fontSizeSp20（用户决策；一次性迁移——
     * 老配置首次加载即升到 20，此后用户改字号照常保留） */
    if (!st.contains(QString::fromLatin1("reader/emAligned20")))
    {
        st.setValue(QString::fromLatin1("reader/emAligned20"), 1);
        st.setValue(QString::fromLatin1("reader/em"), 20.0);
        m_em = 20.0f;
        evt("em_align20", QString::fromLatin1("default=20"));
    }
    m_em = st.value("reader/em", 20.0f).toFloat();
    m_theme = st.value("reader/theme", 0).toInt();
    m_scrollMode = st.value("reader/scrollMode", true).toBool();
    m_twoPage = st.value("reader/twoPage", false).toBool();
    m_lineHeight = st.value("reader/lineHeight", m_lineHeight).toInt();
    m_marginT = st.value("reader/marginTop", m_marginT).toInt();
    m_marginB = st.value("reader/marginBottom", m_marginB).toInt();
    m_marginL = st.value("reader/marginLeft", m_marginL).toInt();
    m_marginR = st.value("reader/marginRight", m_marginR).toInt();
    m_textIndent = st.value("reader/textIndent", m_textIndent).toInt();
    m_paraSpacing = st.value("reader/paraSpacing", m_paraSpacing).toInt();
    /* P1：对齐四态（旧 reader/justify 键迁移）+ 版心 + 书内样式 */
    m_align = st.value("reader/align",
                       st.value("reader/justify", true).toBool() ? QString("justify")
                                                                 : QString("left"))
                  .toString();
    if (m_align != "left" && m_align != "justify" && m_align != "right" &&
        m_align != "center")
        m_align = "justify";
    m_maxChars = qBound(0, st.value("reader/maxChars", 0).toInt(), 120);
    m_useDocCss = st.value("reader/useDocCss", true).toBool();
    m_fontFamily = st.value("reader/fontFamily").toString();
    m_texture = st.value("reader/texture", "none").toString();
    m_theme = qBound(0, m_theme, 4);
    m_em = qBound(8.0f, m_em, 32.0f);
    m_lineHeight = qBound(100, m_lineHeight, 250);
    m_marginT = qBound(0, m_marginT, 60); m_marginB = qBound(0, m_marginB, 60);
    m_marginL = qBound(0, m_marginL, 60); m_marginR = qBound(0, m_marginR, 60);
    m_textIndent = qBound(0, m_textIndent, 50);
    m_paraSpacing = qBound(0, m_paraSpacing, 20);
    m_autoMs = st.value("reader/autoScrollMs", 500).toInt();
    m_autoMs = qBound(100, m_autoMs, 5000);
    m_autoFlipSec = qBound(1, st.value("reader/autoFlipSec", 5).toInt(), 600); /* v0.83.0 W2 */
    m_trimWhite = st.value("reader/trimWhite", false).toBool();
    /* v1.8.0（P0-5）：滚动位图字节预算（MB，默认 64；16~512） */
    m_bitmapBudgetMB = qBound(16, st.value("reader/bitmapBudgetMB", 64).toInt(), 512);
    m_ttsAdvanceSec = st.value("tts/advanceSec", 0).toInt();
    m_ttsAdvanceSec = qBound(0, m_ttsAdvanceSec, 600);
    /* reader.json 配置互通（Compose 桌面版同文件；存在则覆盖 ini 值） */
    hr::RJson rj;
    if (rj.load())
    {
        m_lineHeight = rj.lineHeight;
        m_marginT = rj.marginTop;
        m_marginR = rj.marginRight;
        m_marginB = rj.marginBottom;
        m_marginL = rj.marginLeft;
        m_textIndent = rj.textIndent;
        m_paraSpacing = rj.paraSpacing;
        m_em = rj.em;
        m_align = rj.align;
        m_useDocCss = rj.useDocCss;
        for (int i = 0; i < 5; i++)
            if (rj.readTheme == QString::fromUtf8(kThemes[i].id)) m_theme = i;
    }
}

void ReaderWindow::savePrefs()
{
    QSettings st(dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
    st.setValue("reader/em", m_em);
    st.setValue("reader/theme", m_theme);
    st.setValue("reader/scrollMode", m_scrollMode);
    st.setValue("reader/twoPage", m_twoPage);
    st.setValue("reader/lineHeight", m_lineHeight);
    st.setValue("reader/marginTop", m_marginT);
    st.setValue("reader/marginBottom", m_marginB);
    st.setValue("reader/marginLeft", m_marginL);
    st.setValue("reader/marginRight", m_marginR);
    st.setValue("reader/textIndent", m_textIndent);
    st.setValue("reader/paraSpacing", m_paraSpacing);
    st.setValue("reader/align", m_align);
    st.setValue("reader/justify", m_align == QString("justify")); /* 旧键兼容 */
    st.setValue("reader/maxChars", m_maxChars);
    st.setValue("reader/useDocCss", m_useDocCss);
    st.setValue("reader/fontFamily", m_fontFamily);
    st.setValue("reader/texture", m_texture);
    st.setValue("reader/autoScrollMs", m_autoMs);
    st.setValue("reader/autoFlipSec", m_autoFlipSec); /* v0.83.0 W2 */
    st.setValue("reader/trimWhite", m_trimWhite);
    st.setValue("reader/bitmapBudgetMB", m_bitmapBudgetMB); /* v1.8.0（P0-5） */
    st.setValue("tts/advanceSec", m_ttsAdvanceSec);
    /* reader.json 同步写（Compose 桌面版同文件互通） */
    hr::RJson rj;
    rj.lineHeight = m_lineHeight;
    rj.marginTop = m_marginT;
    rj.marginRight = m_marginR;
    rj.marginBottom = m_marginB;
    rj.marginLeft = m_marginL;
    rj.textIndent = m_textIndent;
    rj.paraSpacing = m_paraSpacing;
    rj.em = m_em;
    rj.align = m_align;
    rj.useDocCss = m_useDocCss;
    rj.readTexture = m_texture;
    rj.readTheme = QString::fromUtf8(kThemes[m_theme].id);
    rj.save();
}

/* P2 适配（B6）：质感乘色叠加（仅浅色主题；对齐 WIN ReadTextures 纯显示层） */
void ReaderWindow::applyTexture(QImage *img)
{
    if (!img || img->isNull() || m_texture == QString::fromLatin1("none")) return;
    if (kThemes[m_theme].dark) return;
    const QPixmap pm = texPixmapFor(m_texture, img->width(), img->height());
    if (pm.isNull()) return;
    QPainter p(img);
    p.setCompositionMode(QPainter::CompositionMode_Multiply);
    p.drawPixmap(0, 0, pm);
}

QRectF ReaderWindow::pageImageRect() const
{
    if (!m_firstLabel || !m_scroll->widget()) return QRectF();
    const QPoint p = m_firstLabel->pos() + m_scroll->widget()->pos();
    return QRectF(p, m_firstLabel->size());
}

/* P1 适配（D2）：滚动模式——视口坐标 → 指定页位图矩形/命中页（m_labels 布局同 pageImageRect） */
QRectF ReaderWindow::scrollPageRect(int page) const
{
    if (page < 0 || page >= m_labels.size() || !m_labels[page] || !m_scroll->widget())
        return QRectF();
    /* v1.15.13：Y 取前缀表真值——几何懒同步下未同步标签的 y() 可能滞后于改高搬移 */
    const int ly = page + 1 < m_stackY.size() ? m_stackY[page] : m_labels[page]->y();
    const QPoint p(m_labels[page]->pos().x() + m_scroll->widget()->pos().x(),
                   ly + m_scroll->widget()->pos().y());
    return QRectF(p, m_labels[page]->size());
}

int ReaderWindow::scrollPageAt(const QPoint &vp) const
{
    for (int i = 0; i < m_labels.size(); i++)
        if (m_labels[i] && scrollPageRect(i).contains(vp)) return i;
    return -1;
}

/* v1.12.0：划词选择——按锚点(P0,Pt0)/当前点(P1,Pt1)整段重算。点用页内局部像素，
 * 滚动/改布局后仍可重投影；跨页逐页调引擎（矩形端点），文本按页序拼接 */
QRectF ReaderWindow::selPageRect(int page) const
{
    return m_scrollMode ? scrollPageRect(page) : pageImageRect();
}

int ReaderWindow::selClampPageAt(const QPointF &vp) const
{
    if (!m_scrollMode) return m_page;
    int best = -1;
    double bd = 1e18;
    for (int i = 0; i < m_labels.size(); i++)
    {
        if (!m_labels[i]) continue;
        const QRectF r = scrollPageRect(i);
        if (r.height() <= 0) continue;
        if (r.contains(vp)) return i;
        const double dy = vp.y() < r.top() ? r.top() - vp.y()
                          : (vp.y() > r.bottom() ? vp.y() - r.bottom() : 0.0);
        if (dy < bd) { bd = dy; best = i; }
    }
    return best;
}

QPointF ReaderWindow::selToLocal(int page, const QPointF &vp) const
{
    const QRectF r = selPageRect(page);
    return QPointF(qBound(0.0, vp.x() - r.left(), qMax(0.0, r.width())),
                   qBound(0.0, vp.y() - r.top(), qMax(0.0, r.height())));
}

void ReaderWindow::clearSelection()
{
    m_selText.clear();
    m_selQuads.clear();
    m_selOverlay->rects.clear();
    m_selOverlay->hide();
    m_selAuto.stop();
}

void ReaderWindow::updateSelection()
{
    m_selText.clear();
    m_selQuads.clear();
    if (!m_doc || m_pages <= 0 || m_selP0 < 0 || m_selP1 < 0)
    {
        refreshSelOverlay();
        return;
    }
    const int lo = qMin(m_selP0, m_selP1), hi = qMax(m_selP0, m_selP1);
    const bool fwd = m_selP1 >= m_selP0;
    QString text;
    for (int pg = lo; pg <= hi; pg++)
    {
        const QRectF ir = selPageRect(pg);
        if (ir.width() <= 0) continue;
        /* 本页选择矩形（视口坐标）：端点页取点，中间页取整页——引擎按矩形取字 */
        QPointF a, b;
        if (lo == hi)
        {
            a = ir.topLeft() + m_selPt0;
            b = ir.topLeft() + m_selPt1;
        }
        else if (pg == lo)
        {
            /* v1.12.1：端点按方向取点——反向拖（自下而上）时锚点在 hi 页、
             * 当前点在 lo 页，原实现把 pt0/pt1 用反，两端页边界全错 */
            a = ir.topLeft() + (fwd ? m_selPt0 : m_selPt1);
            b = fwd ? ir.bottomRight() : ir.topLeft();
        }
        else if (pg == hi)
        {
            a = fwd ? ir.topLeft() : ir.bottomRight();
            b = ir.topLeft() + (fwd ? m_selPt1 : m_selPt0);
        }
        else
        {
            a = ir.topLeft();
            b = ir.bottomRight();
        }
        const hr::SelResult r = hr::ReaderMark::select(this, pg, ir, a, b, m_selSnap);
        if (!r.text.isEmpty())
            text += (text.isEmpty() ? QString() : QString::fromLatin1("\n")) + r.text;
        if (!r.rectsPx.isEmpty())
        {
            QVector<QRectF> loc;
            for (const QRectF &q : r.rectsPx)
                loc.append(q.translated(-ir.topLeft()));
            m_selQuads.insert(pg, loc);
        }
    }
    m_selText = text;
    refreshSelOverlay();
}

void ReaderWindow::refreshSelOverlay()
{
    m_selOverlay->rects.clear();
    for (auto it = m_selQuads.constBegin(); it != m_selQuads.constEnd(); ++it)
    {
        const QRectF ir = selPageRect(it.key());
        if (ir.width() <= 0) continue;
        for (const QRectF &q : it.value())
            m_selOverlay->rects.append(q.translated(ir.topLeft()));
    }
    if (m_selOverlay->rects.isEmpty())
    {
        m_selOverlay->hide();
        return;
    }
    m_selOverlay->setGeometry(m_scroll->viewport()->rect());
    m_selOverlay->update();
    m_selOverlay->show();
    m_selOverlay->raise();
}

void ReaderWindow::selAutoScrollTick()
{
    if (!m_dragging || !m_scrollMode) { m_selAuto.stop(); return; }
    const QRectF vr(m_scroll->viewport()->rect());
    const int zone = 40;
    int dy = 0;
    if (m_selLastVp.y() < vr.top() + zone)
        dy = -(int)(zone - (m_selLastVp.y() - vr.top())) - 6;
    else if (m_selLastVp.y() > vr.bottom() - zone)
        dy = (int)(m_selLastVp.y() - (vr.bottom() - zone)) + 6;
    if (dy == 0) { m_selAuto.stop(); return; }
    QScrollBar *sb = m_scroll->verticalScrollBar();
    sb->setValue(sb->value() + qBound(-24, dy, 24)); /* valueChanged 内重算选区 */
}

bool ReaderWindow::probeSelectRange(int p0, const QPointF &a, int p1, const QPointF &b)
{
    m_selP0 = p0; m_selPt0 = a;
    m_selP1 = p1; m_selPt1 = b;
    m_selSnap = 0;
    updateSelection();
    return !m_selText.trimmed().isEmpty() &&
           m_selQuads.contains(p0) && m_selQuads.contains(p1);
}

void ReaderWindow::resizeEvent(QResizeEvent *e)
{
    QMainWindow::resizeEvent(e);
    if (m_selOverlay)
    {
        m_selOverlay->setGeometry(m_scroll->viewport()->rect());
        refreshSelOverlay(); /* v1.12.1：resize 后页矩形移位，重投影选区高亮 */
    }
    /* P2 适配（F4）：独立阅读窗蓝光滤镜（内嵌模式由书库壳覆盖，双层避免） */
    if (!m_warmRw && !m_embedded &&
        QSettings(dataDir() + "/qt-prefs.ini", QSettings::IniFormat)
                .value("ui/blueLight", 0)
                .toInt() > 0)
    {
        QLabel *warm = new QLabel(this);
        warm->setAttribute(Qt::WA_TransparentForMouseEvents);
        warm->setStyleSheet(
            QString::fromLatin1("background:rgba(255,158,74,%1);")
                .arg(QSettings(dataDir() + "/qt-prefs.ini", QSettings::IniFormat)
                         .value("ui/blueLight", 0)
                         .toInt() == 1
                         ? 36
                         : 60));
        warm->setGeometry(rect());
        warm->show();
        warm->raise();
        m_warmRw = warm;
    }
    if (m_warmRw) m_warmRw->setGeometry(rect());
    if (m_openOv && m_openOv->isVisible()) m_openOv->setGeometry(rect()); /* v0.103.0 */
    if (m_doc && m_smokeW <= 0) m_resizeTimer.start();
}

/* U4：退出确认抽取（closeEvent / v0.75.0 内嵌返回共用）；false=用户取消 */
bool ReaderWindow::confirmExit()
{
    if (m_doc &&
        QSettings(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat)
            .value("reader/exitConfirm", false)
            .toBool())
    {
        const QMessageBox::StandardButton r = QMessageBox::question(
            this, hr::l10n::s("退出阅读", "Exit reading"),
            hr::l10n::s("正在阅读《%1》——确定退出？", "Reading \"%1\" — exit?")
                .arg(m_bookName));
        if (r != QMessageBox::Yes)
            return false;
        evt("exit_confirm_ok", QString());
    }
    return true;
}

void ReaderWindow::closeEvent(QCloseEvent *e)
{
    if (!confirmExit())
    {
        e->ignore();
        return;
    }
    beginTeardown(); /* v0.107.0（P1-5 补齐）：先收 phase2 再归还 doc */
    releaseEngineDoc(); /* v0.106.0（P0-4） */
    QMainWindow::closeEvent(e);
}

/* v0.106.0（P0-4）：归还引擎文档——retireReader/closeEvent/析构共用。
 * 渲染(m_flight/m_auxJobs)/开书(m_openBusy)/后台重排(m_relayoutBusy)任一在飞时跳过，
 * 交给既有代际关闭兜底，避免 UAF；朗读先停。 */
void ReaderWindow::releaseEngineDoc()
{
    hr::m4StopSpeak(this);
    if (m_openBusy.load() || m_relayoutBusy.load() || m_accelSaving.load() ||
        m_phase2Busy.load() ||
        !m_flight.isEmpty() || m_auxJobs.load() > 0) /* v1.8.0：多飞行/辅助任务 */
        return;
    void *d = m_doc;
    m_doc = nullptr;
    if (d) hr_qt_close(d);
}

ReaderWindow::~ReaderWindow()
{
    releaseEngineDoc(); /* 析构兜底（deleteLater 路径） */
}

/* v0.107.0（P1-5 补齐）：窗口销毁前收尾——作废 gen 令在飞 phase2 尽快自断并等它
 * 退出，防远程 doc 的流上下文（BcsHolder 随窗析构）被后台计数触碰 */
void ReaderWindow::beginTeardown(bool wait)
{
    m_openGen.fetch_add(1);
    if (!wait)
        return; /* v0.107.2：非阻塞收尾——retireReader 轮询 m_phase2Busy 后再归还 doc/销毁窗 */
    const qint64 tw = QDateTime::currentMSecsSinceEpoch();
    while (m_phase2Busy.load() && QDateTime::currentMSecsSinceEpoch() - tw < 15000)
        QApplication::processEvents();
    m_phase2Busy.store(false);
}

/* v0.75.0：内嵌模式"关闭"=请求返回书库（壳收尾进度/刷新）；独立窗仍走 close() */
void ReaderWindow::requestClose()
{
    if (m_embedded)
    {
        if (confirmExit())
            emit backRequested();
        return;
    }
    close();
}

/* v1.6.1：--scrollsmoke 探针（无头定位滚动栈空白页根因：QScrollArea widgetResizable=false
 * 下容器 widget 尺寸不随 layout 增高，增页后滚动条 range 停在开书期高度） */
/* v1.6.1：滚动模式空白页根修——QScrollArea widgetResizable=false 下容器 widget 尺寸不随
 * layout 增高：窗口化开书后 phase2 每 16 章扩页数百条、render_ok 又把标签换成真实图高，
 * 滚动条 range 始终停在开书时刻的旧高度——新页区根本滚不进去，翻页只见占位空白
 * （v1.6.0 会话日志实锤：epub 343→771/361→1086 增页后 24s 翻页零 render_req；
 * 探针实锤：sbmax=48278 仅覆盖 needh=381069 的 12.7%）。栈内容任何变化后同步一次。 */
void ReaderWindow::syncStackHeight()
{
    if (!m_scrollMode || !m_stack || m_stackY.isEmpty()) return;
    const QSize want(renderWidthPx(), m_stackY.last()); /* v1.15.13：前缀表末项=栈总高 */
    if (want.width() > 0 && want.height() > 0 && want != m_stack->size())
        m_stack->resize(want); /* QScrollArea 据 widget 尺寸算滚动范围 */
}
int ReaderWindow::probeScrollMax() const
{
    return (m_scroll && m_scroll->verticalScrollBar())
               ? m_scroll->verticalScrollBar()->maximum()
               : -1;
}
int ReaderWindow::probeViewportH() const
{
    return (m_scroll && m_scroll->viewport()) ? m_scroll->viewport()->height() : -1;
}
int ReaderWindow::probeStackNeedH() const
{
    if (!m_scrollMode || m_stackY.isEmpty()) return -1; /* v1.15.13：前缀表末项（原布局 sizeHint） */
    return m_stackY.last();
}
int ReaderWindow::stdPageHeight() const
{
    if (!m_doc || m_pages <= 0 || m_fixedLayout) return 0;
    /* 取一个"必非章末页"的页面：章首页且本章 >= 2 页 */
    int pg = -1, acc = 0;
    for (int k = 0; k < m_chapPages.size(); k++)
    {
        const int cp = qMax(0, m_chapPages[k]);
        if (acc + cp > m_pages) break;
        if (cp >= 2)
        {
            pg = acc;
            break;
        }
        acc += cp;
    }
    if (pg < 0 && m_chapPages.isEmpty() && m_pages >= 2) pg = 0; /* 无章表=整书单章 */
    if (pg < 0) return 0;
    float box[4];
    if (hr_qt_page_box(m_doc, pg, box) != 0) return 0;
    const double pw = (double)box[2] - box[0], ph = (double)box[3] - box[1];
    const int w = renderWidthPx();
    if (pw < 1.0 || ph < 1.0 || w < 2) return 0;
    /* 与桥的渲染口径一致（hr_qt_render）：pixmap 高 = ceil(下边 x 比例) - floor(上边 x 比例) */
    const double s = (double)w / pw;
    const int h = (int)std::ceil((double)box[3] * s) - (int)std::floor((double)box[1] * s);
    return (h > 0 && h < 20000) ? h : 0;
}

void ReaderWindow::calibEstH()
{
    /* v1.15.13：可重排书的占位页高直接用引擎页尺寸折算的真实页高。原先一律用视口高，而
     * 真实页高=版面高 x (渲染宽/版面宽)，两者差十几到几十像素：滚动模式每渲染出一页，标签
     * 改高后其下全部标签都要重排——可见窗口里是逐个搬移，耗时正比于"总标签数 x 下方标签
     * 数"（实测 7844 页的书读第 3000 页，落地后一批 3 页卡 10.8 秒）。页高对齐后标准页
     * 渲染落地不再搬移，只有每章末页（更矮）仍会搬一次。取不到（djvu/异常）回退原口径 */
    m_estHExact = false;
    const int sh = stdPageHeight();
    if (sh > 0)
    {
        m_estH = sh;
        m_estHExact = true;
    }
}

int ReaderWindow::probeChapterOf(int page) const /* v1.15.13：重排位保门禁——页所在章（按章页表累计；无表 -1） */
{
    if (page < 0 || m_chapPages.isEmpty()) return -1;
    int acc = 0;
    for (int k = 0; k < m_chapPages.size(); k++)
    {
        acc += qMax(0, m_chapPages[k]);
        if (page < acc) return k;
    }
    return m_chapPages.size() - 1;
}
bool ReaderWindow::probePageRendered(int pg) const
{
    return pg >= 0 && pg < m_labelW.size() && m_labelW[pg] == renderWidthPx();
}

} // namespace hr
