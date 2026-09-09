/**
 * TTS playback-control service card (round 6, Android TTSWidget parity).
 * 2x4 card: current book title + sentence + prev/toggle/next buttons.
 * Buttons route to EntryAbility with `tts_cmd`, which is forwarded to the
 * Reader through AppStorage (same channel as the TTS notification buttons).
 */
const cardStorage = new LocalStorage();

@Entry(cardStorage)
@Component
struct TtsCard {
  @LocalStorageProp('ttsTitle') ttsTitle: string = '';
  @LocalStorageProp('ttsSentence') ttsSentence: string = '';
  @LocalStorageProp('playing') playing: boolean = false;

  build() {
    Column({ space: 4 }) {
      Row({ space: 6 }) {
        Image($r('app.media.ic_headphones'))
          .width(14).height(14).fillColor('#3949AB')
        Text(this.ttsTitle.length > 0 ? this.ttsTitle : 'HowRead TTS')
          .fontSize(11).fontColor('#333333').maxLines(1)
          .layoutWeight(1)
      }
      .width('100%')

      Text(this.ttsSentence.length > 0 ? this.ttsSentence : '未在朗读')
        .fontSize(10).fontColor('#888888')
        .maxLines(3)
        .width('100%')

      Blank()

      Row({ space: 20 }) {
        Image($r('app.media.ic_rewind'))
          .width(22).height(22).fillColor('#3949AB')
          .onClick(() => {
            postCardAction(this, {
              'action': 'router',
              'abilityName': 'EntryAbility',
              'params': { 'tts_cmd': 'prev' }
            });
          })
        Image(this.playing ? $r('app.media.ic_pause') : $r('app.media.ic_play'))
          .width(26).height(26).fillColor('#3949AB')
          .onClick(() => {
            postCardAction(this, {
              'action': 'router',
              'abilityName': 'EntryAbility',
              'params': { 'tts_cmd': 'toggle' }
            });
          })
        Image($r('app.media.ic_skip_next'))
          .width(22).height(22).fillColor('#3949AB')
          .onClick(() => {
            postCardAction(this, {
              'action': 'router',
              'abilityName': 'EntryAbility',
              'params': { 'tts_cmd': 'next' }
            });
          })
      }
      .justifyContent(FlexAlign.Center)
      .width('100%')
      .padding({ bottom: 4 })
    }
    .width('100%')
    .height('100%')
    .padding(8)
    .backgroundColor('#F5F2EC')
    .borderRadius(12)
  }
}
