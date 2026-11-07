/* M4 阅读器功能模块：搜索/朗读/导出WAV/AI 译页（free functions，经受控访问器操作窗口）。 */
#ifndef HRREADERM4_H
#define HRREADERM4_H

#include <QString>
#include <QStringList>

class QWidget;

namespace hr
{

class ReaderWindow;

void m4SearchDialog(ReaderWindow *w, const QString &prefill = QString());
void m4SpeakPage(ReaderWindow *w);
void m4StopSpeak(ReaderWindow *w = nullptr); /* 批次三：带窗指针停定时翻页 */
void m4ExportWav(ReaderWindow *w);
void m4AiDialog(ReaderWindow *w, int modeHint = 0); /* W2：0=译页 1=双语 2=简介（底栏直达预选） */
void m4AiConfigDialog(QWidget *parent); /* 批次三：AI 配置（base/key/model+测试）；U4 起任意窗可开 */
void m4RsvpDialog(ReaderWindow *w);     /* 批次三：速读 RSVP */
QStringList m4PageParas(ReaderWindow *w, int page);

void m4QaDialog(hr::ReaderWindow *w); /* P1（C7）AI 问答（当前页上下文+4 轮历史） */

} // namespace hr


#endif /* HRREADERM4_H */
