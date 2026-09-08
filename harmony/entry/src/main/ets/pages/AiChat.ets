import router from '@ohos.router';
import promptAction from '@ohos.promptAction';
import { AiConfig, loadAiConfig, chatCompletion, aiConfigReady, AiMessage } from '../model/AiClient';
import { addNote, AiNote } from '../model/Notes';

/**
 * 发送给AI page (Android hr13): editable selected text + optional question +
 * AI answer area + 保存到笔记. Opened from the Reader selection menu (action 6).
 */
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
      promptAction.showToast({ message: '\u8BF7\u5148\u5728\u504F\u597D-\u5E38\u89C4\u8BBE\u7F6E\u4E2D\u914D\u7F6E AI \u5927\u6A21\u578B' });
      return;
    }
    if (this.selEditable.trim().length === 0) {
      promptAction.showToast({ message: '\u8BF7\u5148\u8F93\u5165\u6216\u7C98\u8D34\u6587\u672C' });
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
      promptAction.showToast({ message: ok ? '\u5DF2\u4FDD\u5B58\u5230\u7B14\u8BB0' : '\u4FDD\u5B58\u5931\u8D25' });
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
        Text('\u53D1\u9001\u7ED9AI')
          .fontSize(16).fontWeight(FontWeight.Medium).fontColor(Color.White)
          .layoutWeight(1)
      }
      .width('100%').height(46)
      .padding({ left: 12, right: 12 })
      .backgroundColor('#00897B')

      Scroll() {
        Column({ space: 10 }) {
          if (this.cfgNotReady) {
            Text('\u5C1A\u672A\u914D\u7F6E AI \u5927\u6A21\u578B\uFF0C\u8BF7\u5148\u5728\u504F\u597D-\u5E38\u89C4\u8BBE\u7F6E\u4E2D\u586B\u5199 API \u4FE1\u606F')
              .fontSize(13).fontColor('#d32f2f')
              .width('100%')
          }

          Text('\u6240\u9009\u6587\u672C\uFF08\u53EF\u7F16\u8F91\uFF09')
            .fontSize(13).fontColor('#555')
            .width('100%')
          TextArea({ text: this.selEditable, placeholder: '\u8F93\u5165\u6216\u7C98\u8D34\u6587\u672C\u2026' })
            .height(120)
            .fontSize(14)
            .backgroundColor(Color.White)
            .borderRadius(4)
            .onChange((value: string) => {
              this.selEditable = value;
            })

          Text('\u4F60\u7684\u95EE\u9898\uFF08\u53EF\u9009\uFF0C\u7559\u7A7A\u9ED8\u8BA4\u89E3\u8BFB\u6587\u672C\uFF09')
            .fontSize(13).fontColor('#555')
            .width('100%')
          TextInput({ placeholder: '\u4F8B\uFF1A\u8FD9\u6BB5\u8BDD\u7684\u542B\u4E49\u662F\u4EC0\u4E48\uFF1F', text: this.question })
            .height(40)
            .fontSize(14)
            .backgroundColor(Color.White)
            .borderRadius(4)
            .onChange((value: string) => {
              this.question = value;
            })

          Row({ space: 10 }) {
            Button('\u53D1\u9001')
              .fontSize(14).height(38)
              .backgroundColor(this.busy ? '#9fa8da' : '#3949AB').fontColor(Color.White)
              .layoutWeight(1)
              .onClick(() => {
                this.send();
              })
            Button('\u4FDD\u5B58\u5230\u7B14\u8BB0')
              .fontSize(14).height(38)
              .backgroundColor('#00897B').fontColor(Color.White)
              .layoutWeight(1)
              .opacity(this.answer.length > 0 && !this.busy ? 1 : 0.4)
              .onClick(() => {
                this.saveToNotes();
              })
          }
          .width('100%')

          Text('AI \u56DE\u7B54')
            .fontSize(13).fontColor('#555')
            .width('100%')
          Column() {
            if (this.busy) {
              Row({ space: 8 }) {
                LoadingProgress().width(22).height(22).color('#3949AB')
                Text('\u6B63\u5728\u8BF7\u6C42\u6A21\u578B\u2026').fontSize(13).fontColor('#777')
              }
            } else if (this.answer.length > 0) {
              Text(this.answer)
                .fontSize(14).fontColor('#333')
                .width('100%')
            } else {
              Text('(\u70B9\u51FB\u201C\u53D1\u9001\u201D\u540E\uFF0C\u6A21\u578B\u56DE\u7B54\u5C06\u663E\u793A\u5728\u8FD9\u91CC)')
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
