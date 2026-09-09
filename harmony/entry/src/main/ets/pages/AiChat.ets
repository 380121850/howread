import router from '@ohos.router';
import promptAction from '@ohos.promptAction';
import { AiConfig, loadAiConfig, chatCompletion, aiConfigReady, AiMessage } from '../model/AiClient';
import { addNote, AiNote } from '../model/Notes';

/**
 * 发送给AI page (Android hr13): editable selected text + optional question +
 * AI answer area + 保存到笔记. Opened from the Reader selection menu (action 6).
 */
/** Round 7: dynamic theme color shared via AppStorage (prefs themeColor). */
function thmColor(): string {
  const c: string | undefined = AppStorage.get<string>('themeColorHex');
  return c !== undefined && c.length > 0 ? c : '#3949AB';
}

@Entry
@Component
struct AiChat {
  @State docPath: string = '';
  @State bookTitle: string = '';
  @State pageNo: number = 0;
  @State selEditable: string = '';
  @State question: string = '';
  @State answer: string = '';
  @State busy: boolean = false;
  @State cfgNotReady: boolean = false;
  private cfg: AiConfig = {
    provider: '', url: '', key: '', model: '', maxTokens: 8192, thinking: false
  };

  /* Round 6: resolve a string resource into a plain string */
  private L(r: Resource): string {
    return getContext(this).resourceManager.getStringSync(r);
  }

  aboutToAppear(): void {
    const params = router.getParams() as Record<string, string>;
    if (params !== undefined && params !== null) {
      this.docPath = typeof params['docPath'] === 'string' ? params['docPath'] : '';
      this.bookTitle = typeof params['title'] === 'string' ? params['title'] : '';
      this.selEditable = typeof params['selText'] === 'string' ? params['selText'] : '';
      const p = params['page'];
      this.pageNo = typeof p === 'string' ? parseInt(p) : (typeof p === 'number' ? p as number : 0);
    }
    loadAiConfig(getContext(this)).then((c: AiConfig) => {
      this.cfg = c;
      this.cfgNotReady = !aiConfigReady(c);
    });
  }

  private buildMessages(): AiMessage[] {
    const msgs: AiMessage[] = [];
    msgs.push({ role: 'system', content: '\u4F60\u662F\u4E13\u4E1A\u7684\u9605\u8BFB\u52A9\u624B\uFF0C\u8BF7\u7528\u7B80\u4F53\u4E2D\u6587\u56DE\u7B54\u3002' });
    const text = this.selEditable;
    let user: string;
    if (this.question.length > 0) {
      user = this.question + '\n\n\u6587\u672C\u5185\u5BB9\uFF1A\n' + text;
    } else {
      user = '\u8BF7\u89E3\u8BFB\u4EE5\u4E0B\u6587\u672C\uFF0C\u6982\u62EC\u5927\u610F\u5E76\u6307\u51FA\u8981\u70B9\uFF1A\n' + text;
    }
    msgs.push({ role: 'user', content: user });
    return msgs;
  }

  private send(): void {
    if (this.busy) {
      return;
    }
    if (!aiConfigReady(this.cfg)) {
      this.cfgNotReady = true;
      promptAction.showToast({ message: $r('app.string.aichat_not_configured') });
      return;
    }
    if (this.selEditable.trim().length === 0) {
      promptAction.showToast({ message: $r('app.string.aichat_no_text') });
      return;
    }
    this.busy = true;
    this.answer = '';
    chatCompletion(this.cfg, this.buildMessages(), 0).then((out: string) => {
      this.answer = out;
      this.busy = false;
    }).catch((e: Object) => {
      this.answer = '\u8BF7\u6C42\u5931\u8D25\uFF1A' + (e instanceof Error ? e.message : String(e));
      this.busy = false;
    });
  }

  private saveToNotes(): void {
    if (this.answer.length === 0 || this.busy) {
      return;
    }
    const n: AiNote = {
      path: this.docPath,
      title: this.bookTitle,
      page: this.pageNo,
      date: new Date().toISOString(),
      kind: 'AI\u95EE\u7B54',
      text: (this.question.length > 0 ? '\u95EE\uFF1A' + this.question + '\n\n' : '') + this.answer
    };
    addNote(getContext(this), n).then((ok: boolean) => {
      promptAction.showToast({ message: ok ? $r('app.string.aichat_saved') : $r('app.string.common_save_failed') });
    });
  }

  build() {
    Column() {
      /* top bar (hr13: teal with back arrow) */
      Row({ space: 12 }) {
        Image($r('app.media.ic_back'))
          .width(22).height(22).fillColor(Color.White)
          .onClick(() => {
            router.back();
          })
        Text($r('app.string.sel_send_ai'))
          .fontSize(16).fontWeight(FontWeight.Medium).fontColor(Color.White)
          .layoutWeight(1)
      }
      .width('100%').height(46)
      .padding({ left: 12, right: 12 })
      .backgroundColor('#00897B')

      Scroll() {
        Column({ space: 10 }) {
          if (this.cfgNotReady) {
            Text($r('app.string.aichat_not_configured2'))
              .fontSize(13).fontColor('#d32f2f')
              .width('100%')
          }

          Text($r('app.string.aichat_text_hint'))
            .fontSize(13).fontColor('#555')
            .width('100%')
          TextArea({ text: this.selEditable, placeholder: $r('app.string.aichat_text_placeholder') })
            .height(120)
            .fontSize(14)
            .backgroundColor(Color.White)
            .borderRadius(4)
            .onChange((value: string) => {
              this.selEditable = value;
            })

          Text($r('app.string.aichat_question_hint'))
            .fontSize(13).fontColor('#555')
            .width('100%')
          TextInput({ placeholder: $r('app.string.aichat_question_example'), text: this.question })
            .height(40)
            .fontSize(14)
            .backgroundColor(Color.White)
            .borderRadius(4)
            .onChange((value: string) => {
              this.question = value;
            })

          Row({ space: 10 }) {
            Button($r('app.string.aichat_send'))
              .fontSize(14).height(38)
              .backgroundColor(this.busy ? '#9fa8da' : thmColor()).fontColor(Color.White)
              .layoutWeight(1)
              .onClick(() => {
                this.send();
              })
            Button($r('app.string.trans_save_note'))
              .fontSize(14).height(38)
              .backgroundColor('#00897B').fontColor(Color.White)
              .layoutWeight(1)
              .opacity(this.answer.length > 0 && !this.busy ? 1 : 0.4)
              .onClick(() => {
                this.saveToNotes();
              })
          }
          .width('100%')

          Text($r('app.string.aichat_answer'))
            .fontSize(13).fontColor('#555')
            .width('100%')
          Column() {
            if (this.busy) {
              Row({ space: 8 }) {
                LoadingProgress().width(22).height(22).color(thmColor())
                Text($r('app.string.ai_requesting')).fontSize(13).fontColor('#777')
              }
            } else if (this.answer.length > 0) {
              Text(this.answer)
                .fontSize(14).fontColor('#333')
                .width('100%')
            } else {
              Text($r('app.string.aichat_answer_hint'))
                .fontSize(13).fontColor(Color.Gray)
            }
          }
          .width('100%')
          .constraintSize({ minHeight: 120 })
          .padding(10)
          .backgroundColor(Color.White)
          .borderRadius(4)
          .alignItems(HorizontalAlign.Start)
        }
        .width('100%')
        .padding(12)
      }
      .width('100%')
      .layoutWeight(1)
      .scrollBar(BarState.Auto)
      .backgroundColor('#f0f2f5')
    }
    .width('100%').height('100%')
  }
}
