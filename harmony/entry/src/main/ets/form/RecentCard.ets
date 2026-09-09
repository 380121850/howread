/**
 * Recent-books 2x2 service card (round 5, Android RecentBooksWidget parity).
 * Bound data: rows = [{title, progress, cover, path}], empty = boolean.
 * Tapping a row reopens the book through postCardAction → EntryAbility.
 */
const storage = new LocalStorage();

@Entry(storage)
@Component
struct RecentCard {
  @LocalStorageProp('rows') rows: object[] = [];
  @LocalStorageProp('empty') empty: boolean = true;

  build() {
    Column() {
      if (this.empty || this.rows.length === 0) {
        Column({ space: 6 }) {
          Text('📖')
            .fontSize(30)
          Text('暂无最近阅读')
            .fontSize(12)
            .fontColor('#888888')
        }
        .width('100%')
        .height('100%')
        .justifyContent(FlexAlign.Center)
      } else {
        Column({ space: 4 }) {
          ForEach(this.rows, (row: ESObject) => {
            Row({ space: 6 }) {
              if (row.cover !== undefined && row.cover !== '') {
                Image(row.cover)
                  .width(34).height(46)
                  .objectFit(ImageFit.Cover)
                  .borderRadius(3)
              } else {
                Text('📄')
                  .fontSize(18)
                  .width(34).height(46)
                  .textAlign(TextAlign.Center)
              }
              Column({ space: 2 }) {
                Text(row.title)
                  .fontSize(11)
                  .fontColor('#333333')
                  .maxLines(1)
                Text(row.progress + '%')
                  .fontSize(10)
                  .fontColor('#3949AB')
              }
              .alignItems(HorizontalAlign.Start)
              .layoutWeight(1)
            }
            .width('100%')
            .padding({ left: 6, right: 6, top: 2, bottom: 2 })
            .borderRadius(6)
            .backgroundColor('#FFFFFF')
            .onClick(() => {
              postCardAction(this, {
                'action': 'router',
                'abilityName': 'EntryAbility',
                'params': { 'cardOpen': row.path }
              });
            })
          }, (row: ESObject) => row.path)
        }
        .width('100%')
        .height('100%')
        .padding(6)
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#F5F2EC')
    .borderRadius(12)
  }
}
