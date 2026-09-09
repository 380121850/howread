import FormExtensionAbility from '@ohos.app.form.FormExtensionAbility';
import formBindingData from '@ohos.app.form.formBindingData';
import formInfo from '@ohos.app.form.formInfo';
import formProvider from '@ohos.app.form.formProvider';
import Want from '@ohos.app.ability.Want';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { getRecentBooks, RecentBook } from '../model/ReadingProgress';

/**
 * Recent-books service card (round 5, Android RecentBooksWidget parity).
 * 2x2 card showing the top 3 recent books; tapping a row reopens the book
 * via postCardAction → EntryAbility with parameter `cardOpen`.
 */

const TAG = 'FormAbility';

interface CardRow {
  title: string;
  progress: number;
  cover: string;
  path: string;
}

function toRows(books: RecentBook[]): CardRow[] {
  const rows: CardRow[] = [];
  for (let i = 0; i < books.length && i < 3; i++) {
    const b = books[i];
    rows.push({
      title: b.title.length > 14 ? b.title.substring(0, 14) + '…' : b.title,
      progress: b.totalPages > 0 ? Math.round((b.page + 1) * 100 / b.totalPages) : 0,
      cover: b.coverPath.length > 0 ? 'file://' + b.coverPath : '',
      path: b.path
    });
  }
  return rows;
}

export default class FormAbility extends FormExtensionAbility {
  /** Build the card payload from persisted recent books. */
  private async buildPayload(): Promise<formBindingData.FormBindingData> {
    let rows: CardRow[] = [];
    try {
      const books: RecentBook[] = await getRecentBooks(this.context);
      rows = toRows(books);
    } catch (e) {
      hilog.warn(0x0000, TAG, 'load recent books failed: %{public}s', String(e));
    }
    const obj: Record<string, Object> = {
      'rows': rows as Object,
      'empty': rows.length === 0
    };
    return formBindingData.createFormBindingData(obj);
  }

  onAddForm(want: Want): formBindingData.FormBindingData {
    hilog.info(0x0000, TAG, 'onAddForm');
    const formId: string = want.parameters !== undefined
      ? (want.parameters[formInfo.FormParam.IDENTITY_KEY] as string) : '';
    if (formId.length > 0) {
      AppStorage.setOrCreate('cardFormIds', formId);
    }
    /* synchronous empty payload first; onUpdateForm refreshes with real data */
    const obj: Record<string, Object> = {
      'rows': [] as Object,
      'empty': true
    };
    return formBindingData.createFormBindingData(obj);
  }

  async onUpdateForm(formId: string): Promise<void> {
    hilog.info(0x0000, TAG, 'onUpdateForm %{public}s', formId);
    try {
      const payload = await this.buildPayload();
      await formProvider.updateForm(formId, payload);
    } catch (e) {
      hilog.warn(0x0000, TAG, 'updateForm failed: %{public}s', String(e));
    }
  }

  onCastToNormalForm(formId: string): void {
    hilog.info(0x0000, TAG, 'onCastToNormalForm %{public}s', formId);
  }

  onRemoveForm(formId: string): void {
    hilog.info(0x0000, TAG, 'onRemoveForm %{public}s', formId);
  }
}
