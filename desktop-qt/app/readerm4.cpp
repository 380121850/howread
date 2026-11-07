#include "readerm4.h"
#include "hrai.h"
#include "hrl10n.h"
#include "hrtts.h"
#include "hr_bridge.h"
#include "hrpaths.h"
#include "readermark.h"
#include "readerwindow.h"

#include <QApplication>
#include <QCheckBox>
#include <QComboBox>
#include <QDateTime>
#include <QDialog>
#include <QDialogButtonBox>
#include <QFileDialog>
#include <QFormLayout>
#include <QFont>
#include <QHBoxLayout>
#include <QPointer>
#include <QRegularExpression>
#include <QSpinBox>
#include <QThreadPool>
#include <QTextBrowser>
#include <QTimer>
#include <QVector>
#include <QFileInfo>
#include <QLabel>
#include <QLineEdit>
#include <QListWidget>
#include <QMessageBox>
#include <QPlainTextEdit>
#include <QPushButton>
#include <QSettings>
#include <QVBoxLayout>

namespace hr
{

QStringList m4PageParas(ReaderWindow *w, int page)
{
    QStringList out;
    void *doc = w->hrDoc();
    if (!doc) return out;
    auto cb = [](void *ud, const char *para) -> int
    {
        reinterpret_cast<QStringList *>(ud)->append(QString::fromUtf8(para));
        return 1;
    };
    hr_qt_page_paragraphs(doc, page, cb, &out);
    return out;
}

/* RSVP 词块化：拉丁按词、CJK 每两字一组（批次三） */
static QStringList tokenizeRsvpParas(ReaderWindow *w)
{
    QStringList out;
    const QStringList paras = m4PageParas(w, w->currentPage());
    for (const QString &p0 : paras)
    {
        const QString p = p0.simplified();
        QString cur;
        for (int i = 0; i < p.size(); i++)
        {
            const QChar ch = p.at(i);
            if (ch.isSpace())
            {
                if (!cur.isEmpty()) { out << cur; cur.clear(); }
                continue;
            }
            const uint u = ch.unicode();
            const bool cjk = (u >= 0x4e00 && u <= 0x9fff) || (u >= 0x3400 && u <= 0x4dbf);
            if (cjk)
            {
                if (!cur.isEmpty()) { out << cur; cur.clear(); }
                QString t = ch;
                if (i + 1 < p.size())
                {
                    const uint u2 = p.at(i + 1).unicode();
                    if ((u2 >= 0x4e00 && u2 <= 0x9fff) || (u2 >= 0x3400 && u2 <= 0x4dbf))
                        t += p.at(++i);
                }
                out << t;
            }
            else cur += ch;
        }
        if (!cur.isEmpty()) out << cur;
    }
    return out;
}

/* ---- 搜索 ---- */

void m4SearchDialog(ReaderWindow *w, const QString &prefill)
{
    if (!w->hrDoc()) return;
    QDialog dlg(w);
    dlg.setWindowTitle(hr::l10n::s("全文搜索", "Full-text search"));
    dlg.resize(560, 640);
    QVBoxLayout *lay = new QVBoxLayout(&dlg);
    QHBoxLayout *row = new QHBoxLayout;
    QLineEdit *q = new QLineEdit(prefill, &dlg);
    q->setPlaceholderText(hr::l10n::s("搜索词（逐页匹配，EPUB/TXT/PDF 文字层）",
                                      "Search text (per page, EPUB/TXT/PDF text layer)"));
    QPushButton *go = new QPushButton(hr::l10n::s("搜索", "Search"), &dlg);
    row->addWidget(q, 1);
    row->addWidget(go);
    lay->addLayout(row);
    QListWidget *list = new QListWidget(&dlg);
    lay->addWidget(list, 1);
    QLabel *lb = new QLabel(hr::l10n::s("输入后回车/点搜索；双击结果跳页",
                                        "Type then Enter/Search; double-click a hit to jump"),
                            &dlg);
    lay->addWidget(lb);
    QObject::connect(go, &QPushButton::clicked, &dlg, [w, q, list, lb]
    {
        const QString needle = q->text().trimmed();
        if (needle.isEmpty()) return;
        list->clear();
        int total = 0;
        const auto hits = ReaderMark::searchAll(w, needle);
        for (const auto &h : hits)
        {
            list->addItem(QString(hr::l10n::s("第 %1 页    %2 处",
                                              "p%1    %2 hits"))
                              .arg(h.first + 1).arg(h.second));
            total += h.second;
        }
        lb->setText(QString(hr::l10n::s("%1 页命中，共 %2 处",
                                        "%1 pages hit, %2 in total"))
                        .arg(hits.size()).arg(total));
        hr::evt("search_all", QString("q=%1 pages=%2 hits=%3")
                                    .arg(needle.left(40)).arg(hits.size()).arg(total));
    });
    QObject::connect(q, &QLineEdit::returnPressed, go, &QPushButton::click);
    QObject::connect(list, &QListWidget::itemDoubleClicked, &dlg, [w, list, &dlg](QListWidgetItem *it)
    {
        const int page = it->text().section(' ', 1, 1).toInt() - 1;
        if (page >= 0)
        {
            w->jumpToPublic(page);
            dlg.accept();
        }
    });
    dlg.exec();
}

/* ---- TTS ---- */

void m4SpeakPage(ReaderWindow *w)
{
    if (!w->hrDoc()) return;
    const QStringList paras = m4PageParas(w, w->currentPage());
    QString text = paras.join("\n");
    if (text.trimmed().isEmpty())
    {
        QMessageBox::information(w, hr::l10n::s("朗读", "Speak"),
                                 hr::l10n::s("本页无可朗读文字（图片页或无文字层）",
                                             "No speakable text on this page"));
        return;
    }
    if (text.size() > 8000) text = text.left(8000);
    if (!Tts::available())
    {
        QMessageBox::warning(w, hr::l10n::s("朗读", "Speak"),
                             hr::l10n::s("SAPI 不可用（本机无 TTS 引擎）",
                                         "SAPI unavailable (no TTS engine)"));
        return;
    }
    if (Tts::speakAsync(text))
    {
        evt("tts_speak", QString("page=%1 paras=%2").arg(w->currentPage() + 1).arg(paras.size()));
        /* 批次三：定时自动翻页连读（tts/advanceSec，0=关） */
        QSettings st(hr::dataDir() + "/qt-prefs.ini", QSettings::IniFormat);
        const int adv = st.value("tts/advanceSec", 0).toInt();
        if (adv > 0) w->armTtsAdvance(adv);
    }
}

void m4StopSpeak(ReaderWindow *w)
{
    Tts::stop();
    if (w) w->disarmTtsAdvance(); /* 批次三：同步停定时翻页 */
}

void m4ExportWav(ReaderWindow *w)
{
    if (!w->hrDoc()) return;
    const QString dest = QFileDialog::getSaveFileName(
        w, hr::l10n::s("导出本页朗读 WAV", "Export page TTS WAV"), "page.wav",
        "WAV (*.wav)");
    if (dest.isEmpty()) return;
    const QStringList paras = m4PageParas(w, w->currentPage());
    QString err;
    if (Tts::exportWav(paras, dest, &err))
    {
        evt("tts_wav", QString("page=%1 paras=%2 size=%3")
                           .arg(w->currentPage() + 1).arg(paras.size())
                           .arg(QFileInfo(dest).size()));
        QMessageBox::information(w, hr::l10n::s("导出", "Export"),
                                 hr::l10n::s("WAV 已导出：", "WAV exported: ") + dest);
    }
    else
    {
        evt("tts_wav_fail", err.left(80));
        QMessageBox::warning(w, hr::l10n::s("导出", "Export"),
                             hr::l10n::s("失败：", "Failed: ") + err);
    }
}

/* ---- AI（批次三：配置 + 翻译/双语对照/简介 三模式） ---- */

void m4AiConfigDialog(QWidget *w)
{
    /* U6：协议行/获取模型列表/maxTokens/思考模式/测试输入+发送/状态行（对齐 AiConfigScreen） */
    AiConfig c = AiConfig::load();
    QDialog dlg(w);
    dlg.setWindowTitle(hr::l10n::s("AI 大模型配置（OpenAI 兼容）",
                                   "AI setup (OpenAI-compatible)"));
    dlg.resize(640, 470);
    QVBoxLayout *lay = new QVBoxLayout(&dlg);
    QFormLayout *form = new QFormLayout;
    lay->addLayout(form);
    QLineEdit *base = new QLineEdit(c.base, &dlg);
    QLineEdit *key = new QLineEdit(c.key, &dlg);
    key->setEchoMode(QLineEdit::Password);
    QComboBox *model = new QComboBox(&dlg);
    model->setEditable(true);
    model->addItem(c.model);
    model->setCurrentText(c.model);
    QSpinBox *mt = new QSpinBox(&dlg);
    mt->setRange(256, 32768);
    mt->setSingleStep(256);
    mt->setValue(c.maxTokens);
    QCheckBox *th = new QCheckBox(hr::l10n::s("思考模式（服务商支持时生效）",
                                              "Thinking mode (if supported)"),
                                  &dlg);
    th->setChecked(c.thinking);
    form->addRow("Base URL", base);
    form->addRow("API Key", key);
    {
        QWidget *row = new QWidget(&dlg);
        QHBoxLayout *h = new QHBoxLayout(row);
        h->setContentsMargins(0, 0, 0, 0);
        h->addWidget(model, 1);
        QPushButton *fm =
            new QPushButton(hr::l10n::s("获取模型列表", "Fetch models"), &dlg);
        h->addWidget(fm);
        form->addRow(hr::l10n::s("模型", "Model"), row);
        QObject::connect(fm, &QPushButton::clicked, fm, [base, key, model, fm]
        {
            AiConfig t;
            t.base = base->text().trimmed();
            t.key = key->text().trimmed();
            fm->setEnabled(false);
            fm->setText(hr::l10n::s("获取中…", "Fetching…"));
            QThreadPool::globalInstance()->start(QRunnable::create([t, model, fm]
            {
                QString err;
                const QStringList ids = aiModels(t, &err);
                QMetaObject::invokeMethod(fm, [ids, err, model, fm]
                {
                    fm->setEnabled(true);
                    fm->setText(hr::l10n::s("获取模型列表", "Fetch models"));
                    if (ids.isEmpty())
                        fm->setToolTip(hr::l10n::s("获取失败：", "Fetch failed: ") +
                                       err);
                    else
                    {
                        model->clear();
                        model->addItems(ids);
                        evt("ai_models", QString("n=%1").arg(ids.size()));
                    }
                }, Qt::QueuedConnection);
            }));
        });
    }
    form->addRow(hr::l10n::s("输出上限 maxTokens", "Max output tokens"), mt);
    form->addRow(QString(), th);
    QLabel *proto = new QLabel(
        hr::l10n::s("OpenAI 兼容 · POST {base}/chat/completions · GET {base}/models",
                    "OpenAI-compatible · POST {base}/chat/completions · GET {base}/models"),
        &dlg);
    proto->setStyleSheet("color:gray;");
    form->addRow(QString(), proto);
    QLabel *lb = new QLabel(
        hr::l10n::s("示例：https://open.bigmodel.cn/api/paas/v4/ + glm-4-flash；Key 加密存盘",
                    "Example: https://open.bigmodel.cn/api/paas/v4/ + glm-4-flash; key stored encrypted"),
        &dlg);
    lb->setWordWrap(true);
    form->addRow(QString(), lb);
    {
        /* U6：测试输入 + 发送（异步，状态行回显耗时与结果） */
        QWidget *row = new QWidget(&dlg);
        QHBoxLayout *h = new QHBoxLayout(row);
        h->setContentsMargins(0, 0, 0, 0);
        QLineEdit *tin = new QLineEdit("请只回答两个字母：OK", &dlg);
        QPushButton *ts = new QPushButton(hr::l10n::s("发送", "Send"), &dlg);
        h->addWidget(tin, 1);
        h->addWidget(ts);
        form->addRow(hr::l10n::s("测试", "Test"), row);
        QObject::connect(ts, &QPushButton::clicked, ts, [base, key, model, mt, th, tin, ts, lb]
        {
            AiConfig t;
            t.base = base->text().trimmed();
            t.key = key->text().trimmed();
            t.model = model->currentText().trimmed();
            t.maxTokens = mt->value();
            t.thinking = th->isChecked();
            const QString q = tin->text().trimmed();
            if (q.isEmpty()) return;
            ts->setEnabled(false);
            lb->setText(hr::l10n::s("请求中…", "Requesting…"));
            const qint64 t0 = QDateTime::currentMSecsSinceEpoch();
            QThreadPool::globalInstance()->start(QRunnable::create([t, q, ts, lb, t0]
            {
                QString err;
                const QString ans = aiAsk(t, q, &err, t.maxTokens);
                QMetaObject::invokeMethod(ts, [ans, err, lb, ts, t0]
                {
                    ts->setEnabled(true);
                    const qint64 ms = QDateTime::currentMSecsSinceEpoch() - t0;
                    lb->setText(ans.isEmpty()
                                    ? QString(hr::l10n::s("失败（%1ms）：",
                                                          "Failed (%1ms): ")).arg(ms) + err
                                    : QString(hr::l10n::s("通过（%1ms）：",
                                                          "OK (%1ms): ")).arg(ms) +
                                          ans.left(80));
                    evt("ai_test", QString("ok=%1 ms=%2").arg(ans.isEmpty() ? 0 : 1).arg(ms));
                }, Qt::QueuedConnection);
            }));
        });
    }
    QDialogButtonBox *bb = new QDialogButtonBox(QDialogButtonBox::Ok | QDialogButtonBox::Cancel, &dlg);
    bb->button(QDialogButtonBox::Ok)->setText(hr::l10n::s("保存", "Save"));
    bb->button(QDialogButtonBox::Cancel)->setText(hr::l10n::s("取消", "Cancel"));
    lay->addWidget(bb);
    QObject::connect(bb, &QDialogButtonBox::accepted, &dlg, &QDialog::accept);
    QObject::connect(bb, &QDialogButtonBox::rejected, &dlg, &QDialog::reject);
    if (dlg.exec() != QDialog::Accepted) return;
    c.base = base->text().trimmed();
    c.key = key->text().trimmed();
    c.model = model->currentText().trimmed();
    c.maxTokens = mt->value();
    c.thinking = th->isChecked();
    c.save();
    evt("ai_cfg_saved", QString("model=%1 key=%2 mt=%3 th=%4")
                            .arg(c.model, c.key.isEmpty() ? "0" : "1")
                            .arg(c.maxTokens)
                            .arg(c.thinking ? 1 : 0));
}

void m4AiDialog(ReaderWindow *w, int modeHint)
{
    if (!w->hrDoc()) return;
    const AiConfig cfg = AiConfig::load();
    QDialog dlg(w);
    dlg.setWindowTitle(hr::l10n::s("AI 大模型（本页）", "AI model (this page)"));
    dlg.resize(760, 660);
    QVBoxLayout *lay = new QVBoxLayout(&dlg);
    QHBoxLayout *row = new QHBoxLayout;
    row->addWidget(new QLabel(hr::l10n::s("功能:", "Mode:"), &dlg));
    QComboBox *mode = new QComboBox(&dlg);
    mode->addItem(hr::l10n::s("翻译此页", "Translate page"), "tr");
    mode->addItem(hr::l10n::s("双语对照", "Bilingual"), "bi");
    mode->addItem(hr::l10n::s("书籍简介", "Book intro"), "intro");
    if (modeHint > 0) mode->setCurrentIndex(qBound(0, modeHint, 2)); /* v0.83.0 W2 D7 */
    row->addWidget(mode);
    QLineEdit *model = new QLineEdit(cfg.model, &dlg);
    model->setMaximumWidth(160);
    row->addWidget(new QLabel(hr::l10n::s("模型:", "Model:"), &dlg));
    row->addWidget(model);
    QPushButton *btnCfg = new QPushButton(hr::l10n::s("配置…", "Setup…"), &dlg);
    row->addWidget(btnCfg);
    QPushButton *btnAsk = new QPushButton(cfg.usable() ? hr::l10n::s("执行", "Run")
                                                       : hr::l10n::s("先配置",
                                                                     "Set up first"),
                                          &dlg);
    row->addWidget(btnAsk);
    row->addStretch(1);
    lay->addLayout(row);
    QPlainTextEdit *pageBox = new QPlainTextEdit(&dlg);
    pageBox->setPlainText(m4PageParas(w, w->currentPage()).join("\n\n").left(6000));
    pageBox->setMaximumHeight(200);
    lay->addWidget(pageBox, 1);
    QTextBrowser *outBox = new QTextBrowser(&dlg);
    lay->addWidget(outBox, 2);

    QObject::connect(btnCfg, &QPushButton::clicked, &dlg, [w] { m4AiConfigDialog(w); });
    QObject::connect(btnAsk, &QPushButton::clicked, &dlg,
                     [cfg, model, mode, pageBox, outBox, btnAsk, w]
    {
        AiConfig c = cfg;
        c.model = model->text().trimmed();
        if (!c.usable())
        {
            outBox->setPlainText(hr::l10n::s(
                "未配置 API Key——点“配置…”填写（OpenAI 兼容协议）。\n"
                "例：base=https://open.bigmodel.cn/api/paas/v4/ model=glm-4-flash",
                "No API key — open Setup… (OpenAI-compatible).\n"
                "e.g. base=https://open.bigmodel.cn/api/paas/v4/ model=glm-4-flash"));
            return;
        }
        c.save();
        const QString m = mode->currentData().toString();
        QString prompt;
        if (m == "intro")
        {
            QStringList acc;
            for (int p = 0; p < qMin(5, w->pageCount()); p++)
            {
                const QStringList ps = m4PageParas(w, p);
                if (!ps.isEmpty()) acc.append(ps.join("\n"));
                if (acc.join("\n").size() > 3600) break;
            }
            prompt = "以下是一本书的开头内容。请用不超过 150 字概括这本书的主题与主要内容，"
                     "直接输出概述，不要客套：\n\n" + acc.join("\n").left(3800);
        }
        else if (m == "bi")
        {
            const QStringList ps = pageBox->toPlainText().split("\n\n", QString::SkipEmptyParts);
            QStringList numbered;
            for (int i = 0; i < ps.size(); i++)
                numbered += QString("%1. %2").arg(i + 1).arg(ps[i].left(1200));
            prompt = QString("下面是书页的 %1 个段落。请对每个编号段落给出中文翻译，"
                             "输出格式严格为：\n1. 译文\n2. 译文\n……\n不要输出其它内容：\n\n%2")
                         .arg(ps.size()).arg(numbered.join("\n"));
        }
        else
        {
            prompt = "请把下面的书页内容翻译成中文，保持段落结构，只输出译文：\n\n" +
                     pageBox->toPlainText();
        }
        /* U6 缺陷#9：AI 请求异步化（原阻塞 GUI 线程；对话框关闭即丢弃结果） */
        btnAsk->setEnabled(false);
        outBox->setPlainText(hr::l10n::s("请求中…", "Requesting…"));
        const QPointer<QTextBrowser> ob(outBox);
        const QPointer<QPushButton> ba(btnAsk);
        const QString pageText = pageBox->toPlainText();
        const QString mm = m;
        QThreadPool::globalInstance()->start(QRunnable::create(
            [c, prompt, ob, ba, mm, pageText]
        {
            QString err;
            const QString ans = aiAsk(c, prompt, &err, c.maxTokens);
            QMetaObject::invokeMethod(ob ? static_cast<QObject *>(ob.data()) : qApp,
            [ob, ba, mm, ans, err, pageText]
            {
                if (ba) ba->setEnabled(true);
                if (!ob) return; /* 对话框已关闭 */
                if (mm == "bi" && !ans.isEmpty())
                {
                    /* 解析 "N. 译文" 与原文段落配对渲染 */
                    const QStringList ps = pageText.split("\n\n", QString::SkipEmptyParts);
                    QVector<QString> trs(ps.size());
                    const QRegularExpression re("^\\s*(\\d{1,2})[\\.、\\)]\\s*(.+)$");
                    const QStringList lines = ans.split('\n');
                    for (const QString &ln0 : lines)
                    {
                        const QString ln = ln0.trimmed();
                        const QRegularExpressionMatch mt = re.match(ln);
                        if (mt.hasMatch())
                        {
                            const int idx = mt.captured(1).toInt() - 1;
                            if (idx >= 0 && idx < trs.size()) trs[idx] = mt.captured(2);
                        }
                    }
                    QString html;
                    int paired = 0;
                    for (int i = 0; i < ps.size(); i++)
                    {
                        if (trs[i].isEmpty()) continue;
                        paired++;
                        html += QString("<p><b style=\"color:#1a5fb4\">" +
                                            hr::l10n::s("译", "T") + "</b> %1</p>"
                                            "<p style=\"color:#777777\">" +
                                            hr::l10n::s("原", "Src") + " %2</p><hr>")
                                    .arg(trs[i].toHtmlEscaped(), ps[i].left(1200).toHtmlEscaped());
                    }
                    ob->setHtml(html.isEmpty()
                                    ? QString(ans).toHtmlEscaped().replace('\n', "<br>")
                                    : html);
                    evt("ai_bilingual", QString("paras=%1 paired=%2").arg(ps.size()).arg(paired));
                    return;
                }
                ob->setPlainText(ans.isEmpty()
                                     ? hr::l10n::s("失败：", "Failed: ") + err
                                     : ans);
                evt("ai_page", QString("mode=%1 ok=%2 len=%3")
                                   .arg(mm).arg(ans.isEmpty() ? 0 : 1).arg(ans.size()));
            }, Qt::QueuedConnection);
        }));
    });
    dlg.exec();
}

/* ---- 批次三：速读 RSVP（当前页词块轮播，拉丁按词/CJK 每两字一组） ---- */

void m4RsvpDialog(ReaderWindow *w)
{
    if (!w->hrDoc()) return;
    QDialog dlg(w);
    dlg.setWindowTitle(hr::l10n::s("速读 RSVP（本页）", "RSVP speed-reading (this page)"));
    dlg.resize(660, 320);
    QVBoxLayout *lay = new QVBoxLayout(&dlg);
    QLabel *big = new QLabel("…", &dlg);
    big->setAlignment(Qt::AlignCenter);
    QFont f = big->font();
    f.setPointSize(26);
    f.setBold(true);
    big->setFont(f);
    lay->addWidget(big, 1);
    QStringList toks = tokenizeRsvpParas(w);
    int idx = 0;
    bool pause = false;
    QTimer tm;
    QHBoxLayout *row = new QHBoxLayout;
    QSpinBox *wpm = new QSpinBox(&dlg);
    wpm->setRange(100, 1200);
    wpm->setSingleStep(50);
    wpm->setValue(300);
    row->addWidget(new QLabel(hr::l10n::s("词/分", "wpm"), &dlg));
    row->addWidget(wpm);
    QPushButton *pz = new QPushButton(hr::l10n::s("暂停", "Pause"), &dlg);
    QPushButton *pg = new QPushButton(hr::l10n::s("上一页", "Prev page"), &dlg);
    QPushButton *nx = new QPushButton(hr::l10n::s("下一页", "Next page"), &dlg);
    QPushButton *cl = new QPushButton(hr::l10n::s("关闭", "Close"), &dlg);
    row->addWidget(pz);
    row->addWidget(pg);
    row->addWidget(nx);
    row->addStretch(1);
    row->addWidget(cl);
    lay->addLayout(row);
    QLabel *lb = new QLabel(QString(hr::l10n::s("本页 %1 个词块",
                                                "%1 chunks on this page"))
                                .arg(toks.size()), &dlg);
    lay->addWidget(lb);

    QObject::connect(&tm, &QTimer::timeout, &dlg, [&]
    {
        if (pause || toks.isEmpty()) return;
        big->setText(toks[idx]);
        lb->setText(QString("%1 / %2").arg(idx + 1).arg(toks.size()));
        idx = (idx + 1) % toks.size();
        if (idx == 0) tm.stop(); /* 本页读完暂停 */
    });
    QObject::connect(wpm, static_cast<void (QSpinBox::*)(int)>(&QSpinBox::valueChanged), &dlg,
                     [&tm, wpm] { tm.setInterval(60000 / wpm->value()); });
    QObject::connect(pz, &QPushButton::clicked, &dlg, [&]
    {
        pause = !pause;
        pz->setText(pause ? hr::l10n::s("继续", "Resume")
                          : hr::l10n::s("暂停", "Pause"));
        if (!pause && !toks.isEmpty()) tm.start();
    });
    auto jumpPage = [w, &toks, &idx, &lb, &big, &tm, &pause](int d)
    {
        const int np = w->currentPage() + d;
        if (np < 0 || np >= w->pageCount()) return;
        w->jumpToPublic(np);
        toks = tokenizeRsvpParas(w);
        idx = 0;
        big->setText("…");
        lb->setText(QString(hr::l10n::s("本页 %1 个词块", "%1 chunks on this page"))
                        .arg(toks.size()));
        if (!pause) tm.start();
    };
    QObject::connect(pg, &QPushButton::clicked, &dlg, [&] { jumpPage(-1); });
    QObject::connect(nx, &QPushButton::clicked, &dlg, [&] { jumpPage(1); });
    QObject::connect(cl, &QPushButton::clicked, &dlg, &QDialog::accept);
    evt("rsvp_start", QString("toks=%1 page=%2").arg(toks.size()).arg(w->currentPage() + 1));
    tm.setInterval(60000 / wpm->value());
    tm.start();
    dlg.exec();
    tm.stop();
}

/* P1 适配（C7）：AI 问答（当前页 ≤4000 字上下文 + 4 轮历史；对齐 WIN Panel.QA） */
void m4QaDialog(ReaderWindow *w)
{
    if (!w->hrDoc()) return;
    if (!hr::AiConfig::load().usable())
    {
        m4AiConfigDialog(w);
        return;
    }
    QDialog dlg(w);
    dlg.setWindowTitle(hr::l10n::s("AI 问答（当前页）", "AI Q&A (this page)"));
    dlg.resize(600, 560);
    QVBoxLayout *lay = new QVBoxLayout(&dlg);
    QTextBrowser *hist = new QTextBrowser(&dlg);
    hist->setOpenExternalLinks(false);
    lay->addWidget(hist, 1);
    QHBoxLayout *row = new QHBoxLayout;
    QLineEdit *ask = new QLineEdit(&dlg);
    ask->setPlaceholderText(hr::l10n::s("就本页内容提问…", "Ask about this page…"));
    QPushButton *send = new QPushButton(hr::l10n::s("发送", "Send"), &dlg);
    row->addWidget(ask, 1);
    row->addWidget(send);
    lay->addLayout(row);
    QPushButton *close = new QPushButton(hr::l10n::s("关闭", "Close"), &dlg);
    lay->addWidget(close);
    QObject::connect(close, &QPushButton::clicked, &dlg, &QDialog::accept);

    const QStringList paras = m4PageParas(w, w->currentPage());
    QString ctx;
    for (const QString &pp : paras)
    {
        if (ctx.size() > 4000) break;
        ctx += pp + "\n";
    }
    QVector<QPair<QString, QString>> turns;
    hist->setHtml(hr::l10n::s("<i>基于当前页内容提问（%1 字上下文）。</i>",
                              "<i>Ask about this page (%1 chars context).</i>")
                      .arg(ctx.size()));

    auto doAsk = [&](const QString &q)
    {
        QString prompt = QString::fromUtf8("你是电子书阅读助手。以下是一本书当前页的内容：\n\n") +
                         ctx + QString::fromUtf8("\n\n请根据内容回答用户问题：\n\n") + q;
        const int from = qMax(0, turns.size() - 4);
        for (int i = from; i < turns.size(); i++)
            prompt += QString::fromUtf8("\n（历史）问：%1\n（历史）答：%2")
                          .arg(turns[i].first, turns[i].second.left(300));
        hist->append(QString("<b>Q:</b> %1").arg(q.toHtmlEscaped()));
        hist->append(hr::l10n::s("<i>思考中…</i>", "<i>Thinking…</i>"));
        send->setEnabled(false);
        evt("qa_ask", QString("len=%1 ctx=%2").arg(q.size()).arg(ctx.size()));
        QPointer<QDialog> dp(&dlg);
        QThreadPool::globalInstance()->start(QRunnable::create(
            [=, &hist, &send, &turns, prompt, q]
        {
            QString err;
            /* P2 适配（D9）：流式回答（150ms 节流刷新历史面板） */
            const QString ans = hr::aiAskStream(
                hr::AiConfig::load(), prompt,
                [dp, &hist](const QString &full)
                {
                    if (!dp) return;
                    QMetaObject::invokeMethod(
                        dp,
                        [dp, &hist, full]
                        {
                            hist->setHtml(QString("<b>A:</b> %1")
                                              .arg(full.toHtmlEscaped()
                                                       .replace("\n", "<br>")));
                        },
                        Qt::QueuedConnection);
                },
                &err, 2048);
            if (!dp) return; /* 对话框已关闭 */
            QMetaObject::invokeMethod(dp, [dp, &hist, &send, &turns, q, ans, err]
            {
                send->setEnabled(true);
                if (ans.isEmpty())
                    hist->append(QString("<span style='color:red'>%1</span>")
                                     .arg(hr::l10n::s("失败：", "Failed: ") +
                                          err.toHtmlEscaped()));
                else
                {
                    hist->append(QString("<b>A:</b> %1")
                                     .arg(ans.toHtmlEscaped().replace("\n", "<br>")));
                    turns.append(qMakePair(q, ans));
                }
                evt("qa_done", QString("ok=%1 len=%2")
                                   .arg(ans.isEmpty() ? 0 : 1)
                                   .arg(ans.size()));
            }, Qt::QueuedConnection);
        }));
    };
    QObject::connect(send, &QPushButton::clicked, &dlg, [&]()
    {
        const QString q = ask->text().trimmed();
        if (!q.isEmpty())
        {
            ask->clear();
            doAsk(q);
        }
    });
    QObject::connect(ask, &QLineEdit::returnPressed, &dlg, [&]()
    {
        const QString q = ask->text().trimmed();
        if (!q.isEmpty())
        {
            ask->clear();
            doAsk(q);
        }
    });
    dlg.exec();
}

} // namespace hr
