/**
 * Reader component for Librera Reader (HarmonyOS).
 *
 * Features:
 * - Dual scroll modes: horizontal Swiper paging + vertical continuous List scrolling
 * - Pinch-to-zoom gesture (renderPageAsync with new zoom)
 * - Night mode (invertColor via renderPageAsync options)
 * - TOC sidebar (getToc → tap to jump page)
 * - Menu overlay: progress, zoom %, mode toggle, night toggle, TOC button
 * - Tap zones: left=back, right=next, center=toggle menu
 * - Reading progress persistence on every page change
 * - TTS reading (sprint D): Web Speech API via hidden ArkWeb host, sentence by
 *   sentence with rate/pitch control, auto-advances to the next page
 * - Speed reading RSVP (sprint D): CJK-aware word flashes with WPM control
 */

import fs from '@ohos.file.fs';
import image from '@ohos.multimedia.image';
import router from '@ohos.router';
import brightness from '@system.brightness';
import batteryInfo from '@ohos.batteryInfo';
import webview from '@ohos.web.webview';
import promptAction from '@ohos.promptAction';
import picker from '@ohos.file.picker';
import pasteboard from '@ohos.pasteboard';
import common from '@ohos.app.ability.common';
import { systemShare } from '@kit.ShareKit';
import { importUriToSandbox } from '../model/FileImport';
import {
  version, openDocument, pageCount, renderPageAsync, closeDocument, getToc, getPageSize,
  searchText, searchDocument, getText, getAnnotations, addHighlight, addInkStroke, deleteAnnotation, saveDocument,
  addMarkupAnnotation, addTextNote,
  layoutDocument, isReflowable, getDocumentInfo, getTextRects, loadFont,
  RenderResult, RenderOptions, TocEntry, CropRect, TextRect, TextLine, AnnotationInfo, DocumentInfo
} from 'libmupdf_napi.so';
import { saveRecentBook, getPageForPath, RecentBook } from '../model/ReadingProgress';
import { recordReadTime, recordPagesRead } from '../model/ReadingStats';
import { getBookmarks, addBookmark, removeBookmark, Bookmark } from '../model/Bookmarks';
import { AiConfig, loadAiConfig, chatCompletion, aiConfigReady, AiMessage } from '../model/AiClient';
import { getNotes, addNote, removeNote, AiNote } from '../model/Notes';
import { exportNotesFile, ExportData } from '../model/Export';
import { isBilingualDoc, buildBilingual } from '../model/Bilingual';
import { TextUtils } from '../model/TextUtils';
import { loadSettings, saveSettings, THEME_NAMES, ReaderSettings } from '../model/Settings';
import { startTtsBackgroundTask, stopTtsBackgroundTask, showTtsNotification, cancelTtsNotification } from '../service/TtsService';

const LEFT_ZONE_RATIO: number = 0.3;
const RIGHT_ZONE_RATIO: number = 0.3;
const MIN_ZOOM: number = 0.5;
const MAX_ZOOM: number = 4.0;
const ZOOM_STEP: number = 0.25;
/** Layout page size (points) used for reflowable documents */
const REFLOW_W: number = 595;
const REFLOW_H: number = 842;
/** Round 5: page-split halves (normalized crop rects) */
const SPLIT_LEFT: CropRect = { x0: 0, y0: 0, x1: 0.5, y1: 1 };
const SPLIT_RIGHT: CropRect = { x0: 0.5, y0: 0, x1: 1, y1: 1 };
/** Round 5: thumbnails per grid window */
const THUMB_WINDOW: number = 9;
/** Round 5: max page-jump history entries */
const PAGE_HISTORY_MAX: number = 32;

/** Round 5: 4x5 color matrix that adjusts contrast (c = 0..100, 50 = neutral). */
function contrastMatrix(c: number): number[] {
  const s: number = Math.max(0.1, c / 50);
  const off: number = 0.5 * (1 - s);
  const m: number[] = [
    s, 0, 0, 0, off,
    0, s, 0, 0, off,
    0, 0, s, 0, off,
    0, 0, 0, 1, 0
  ];
  return m;
}

/**
 * Data source for page list (shared by Swiper and List modes).
 */
class PageDataSource implements IDataSource {
  private pageIndices: number[] = [];
  private listeners: DataChangeListener[] = [];
  private docHandle: ESObject | undefined = undefined;
  private docPath: string = '';
  private zoom: number = 1.5;
  private invert: boolean = false;

  setZoom(zoom: number): void {
    this.zoom = zoom;
  }

  setInvert(invert: boolean): void {
    this.invert = invert;
  }

  async renderPage(pageNumber: number, cropRect?: CropRect): Promise<RenderResult> {
    if (this.docHandle === undefined) {
      if (this.docPath.length > 0) {
        console.info('[Reader] Document handle lost, re-opening');
        this.openDoc(this.docPath);
      } else {
        throw new Error('Document not opened');
      }
    }
    const opts: RenderOptions = { zoom: this.zoom, invert: this.invert };
    if (cropRect !== undefined) {
      opts.crop = cropRect;
    }
    return await renderPageAsync(this.docHandle, pageNumber, opts);
  }

  getPageCount(): number {
    let count = 0;
    if (this.docHandle !== undefined) {
      count = pageCount(this.docHandle);
    }
    return count;
  }

  getDocHandle(): ESObject | undefined {
    return this.docHandle;
  }

  openDoc(path: string): void {
    try {
      this.docPath = path;
      if (this.docHandle !== undefined) {
        try { closeDocument(this.docHandle); } catch (_e) {}
      }
      this.docHandle = openDocument(path);
      const total: number = pageCount(this.docHandle);
      this.pageIndices = [];
      for (let i = 0; i < total; i++) {
        this.pageIndices.push(i);
      }
      for (let j = 0; j < this.listeners.length; j++) {
        this.listeners[j].onDataReloaded();
      }
    } catch (e) {
      console.error('[Reader] Failed to open document');
      throw e as Error;
    }
  }

  closeDoc(): void {
    if (this.docHandle !== undefined) {
      try {
        closeDocument(this.docHandle);
      } catch (e) {
        console.error('[Reader] Error closing document');
      }
      this.docHandle = undefined;
    }
  }

  totalCount(): number {
    return this.pageIndices.length;
  }

  getData(index: number): string {
    if (index < 0 || index >= this.pageIndices.length) {
      return '0';
    }
    return String(this.pageIndices[index]);
  }

  registerDataChangeListener(listener: DataChangeListener): void {
    if (this.listeners.indexOf(listener) < 0) {
      this.listeners.push(listener);
    }
  }

  unregisterDataChangeListener(listener: DataChangeListener): void {
    const pos = this.listeners.indexOf(listener);
    if (pos >= 0) {
      this.listeners.splice(pos, 1);
    }
  }
}

/**
 * Single page renderer (used in Swiper, List, double-page and musician modes).
 * cropRect (optional) selects a normalized sub-rectangle of the page.
 * searchHits (optional) is a list of normalized (0..1) hit rectangles to
 * highlight; coordinates are relative to the full (uncropped) page.
 */
@Component
struct PageRenderer {
  pageIndex: number = 0;
  dataSource: PageDataSource = new PageDataSource();
  cropRect: CropRect | undefined = undefined;
  searchHits: TextRect[] = [];
  annotations: AnnotationInfo[] = [];
  selPreview: TextRect[] = [];
  @State pixelMap: image.PixelMap | undefined = undefined;
  @State isLoading: boolean = true;
  @State errorText: string = '';
  @State renderW: number = 1;
  @State renderH: number = 1;
  /* Round 5: contrast 0..100, 50 = neutral (colorFilter on the page image) */
  contrastLevel: number = 50;

  aboutToAppear(): void {
    this.loadData();
  }

  async loadData(): Promise<void> {
    if (this.dataSource === undefined) {
      return;
    }
    try {
      this.isLoading = true;
      this.errorText = '';
      const renderResult: RenderResult = await this.dataSource.renderPage(this.pageIndex, this.cropRect);
      const opts: image.InitializationOptions = {
        size: { width: renderResult.width, height: renderResult.height },
        pixelFormat: image.PixelMapFormat.RGBA_8888
      };
      this.pixelMap = await image.createPixelMap(renderResult.data, opts);
      this.renderW = renderResult.width;
      this.renderH = renderResult.height;
      console.info(`[Reader] Page ${this.pageIndex} rendered OK: ${renderResult.width}x${renderResult.height}`);
    } catch (e) {
      this.errorText = 'Render error';
      const msg: string = e instanceof Error ? e.message : String(e);
      console.error(`[Reader] Page ${this.pageIndex} render failed: ${msg}`);
    } finally {
      this.isLoading = false;
    }
  }

  build() {
    Column() {
      if (this.isLoading) {
        Row() {
          Progress({ value: 30, total: 100 })
            .width(48).height(48).color(Color.Blue)
          Text('Rendering...')
            .fontSize(12).fontColor(Color.Gray).margin({ left: 8 })
        }
        .justifyContent(FlexAlign.Center)
      } else if (this.errorText.length > 0) {
        Text(this.errorText)
          .fontSize(14).fontColor(Color.Red).textAlign(TextAlign.Center)
      } else if (this.pixelMap !== undefined) {
        Stack() {
          Image(this.pixelMap)
            .objectFit(ImageFit.Contain)
            .width('100%')
            .borderRadius(2)
            .colorFilter(new ColorFilter(contrastMatrix(this.contrastLevel)))
          /* search hit highlights (yellow) */
          ForEach(this.searchHits, (hit: TextRect) => {
            Column()
              .width(`${(hit.x1 - hit.x0) * 100}%`)
              .height(`${(hit.y1 - hit.y0) * 100}%`)
              .position({ x: `${hit.x0 * 100}%`, y: `${hit.y0 * 100}%` })
              .backgroundColor('#33ffeb3b')
          }, (hit: TextRect, idx: number) => `hit_${idx}_${hit.x0}`)

          /* annotation overlays per type (Phase 9: underline/strikeout/squiggly/text) */
          ForEach(this.annotations, (ann: AnnotationInfo) => {
            if (ann.type === 'highlight') {
              Column()
                .width(`${(ann.x1 - ann.x0) * 100}%`)
                .height(`${(ann.y1 - ann.y0) * 100}%`)
                .position({ x: `${ann.x0 * 100}%`, y: `${ann.y0 * 100}%` })
                .backgroundColor('#55ff9800')
            } else if (ann.type === 'underline' || ann.type === 'squiggly') {
              /* line at the bottom of the annotation rect */
              Column() {
                Column()
                  .width('100%').height(3)
                  .backgroundColor(ann.type === 'underline' ? '#1e88e5' : '#8e24aa')
                  .borderRadius(1)
              }
              .width(`${(ann.x1 - ann.x0) * 100}%`)
              .height(`${(ann.y1 - ann.y0) * 100}%`)
              .position({ x: `${ann.x0 * 100}%`, y: `${ann.y0 * 100}%` })
              .justifyContent(FlexAlign.End)
              .hitTestBehavior(HitTestMode.None)
            } else if (ann.type === 'strikeout') {
              /* line through the middle of the annotation rect */
              Column() {
                Column()
                  .width('100%').height(3)
                  .backgroundColor('#e53935')
                  .borderRadius(1)
              }
              .width(`${(ann.x1 - ann.x0) * 100}%`)
              .height(`${(ann.y1 - ann.y0) * 100}%`)
              .position({ x: `${ann.x0 * 100}%`, y: `${ann.y0 * 100}%` })
              .justifyContent(FlexAlign.Center)
              .hitTestBehavior(HitTestMode.None)
            } else if (ann.type === 'text') {
              /* sticky note icon at the annotation anchor */
              Text('💬')
                .fontSize(16)
                .width(20).height(20)
                .position({ x: `${ann.x0 * 100}%`, y: `${ann.y0 * 100}%` })
                .hitTestBehavior(HitTestMode.None)
            }
          }, (ann: AnnotationInfo, idx: number) => `ann_${idx}_${ann.type}_${ann.x0}`)

          /* Phase 9: live selection preview (translucent blue) */
          ForEach(this.selPreview, (rect: TextRect, idx: number) => {
            Column()
              .width(`${(rect.x1 - rect.x0) * 100}%`)
              .height(`${(rect.y1 - rect.y0) * 100}%`)
              .position({ x: `${rect.x0 * 100}%`, y: `${rect.y0 * 100}%` })
              .backgroundColor('#3342A5F5')
          }, (rect: TextRect, idx: number) => `sel_${idx}_${rect.x0}_${rect.y0}`)
        }
        .width('100%')
      } else {
        Column() {
          Text(`Page ${this.pageIndex + 1}`)
            .fontSize(18).fontColor(Color.Gray)
        }
        .width('80%').height('70%')
        .justifyContent(FlexAlign.Center)
        .backgroundColor('#F5F5F5').borderRadius(8)
      }
    }
    .width('100%')
    .height('100%')
  }
}

/**
 * TOC sidebar panel.
 */
@Component
struct TocPanel {
  @State tocEntries: TocEntry[] = [];
  onJumpPage: (page: number) => void = () => {};

  aboutToAppear(): void {
    this.loadToc();
  }

  async loadToc(): Promise<void> {
    /* TOC is loaded by the parent and passed via refresh */
  }

  setEntries(entries: TocEntry[]): void {
    this.tocEntries = entries;
    console.info(`[Reader] TOC loaded: ${entries.length} entries`);
  }

  build() {
    Column() {
      Text('目录 (TOC)')
        .fontSize(16).fontWeight(FontWeight.Bold)
        .margin({ bottom: 8 })

      if (this.tocEntries.length === 0) {
        Text('(无目录)')
          .fontSize(13).fontColor(Color.Gray)
      } else {
        List() {
          ForEach(this.tocEntries, (entry: TocEntry) => {
            ListItem() {
              Row() {
                Text(entry.title.length > 24 ? entry.title.substring(0, 24) + '…' : entry.title)
                  .fontSize(13).fontColor('#333')
                  .margin({ left: entry.depth * 12 })
                  .layoutWeight(1)
                if (entry.page >= 0) {
                  Text(`p${entry.page + 1}`)
                    .fontSize(11).fontColor(Color.Gray)
                }
              }
              .width('100%')
              .padding({ top: 6, bottom: 6, left: 8, right: 8 })
              .onClick(() => {
                if (entry.page >= 0) {
                  console.info(`[Reader] TOC jump to page ${entry.page + 1}`);
                  this.onJumpPage(entry.page);
                }
              })
            }
          }, (item: TocEntry, index?: number) => `toc_${index}_${item.title}`)
        }
        .layoutWeight(1)
      }
    }
    .width('85%')
    .height('100%')
    .backgroundColor('#fff')
    .padding(12)
    .shadow({ radius: 8, color: '#40000000', offsetX: -2, offsetY: 0 })
  }
}

/**
 * Double-page renderer: left page = pageIndex*2, right page = pageIndex*2+1.
 * Used in double-page (双页) reading mode.
 */
@Component
struct DoublePageRenderer {
  pageIndex: number = 0; /* the pair index */
  dataSource: PageDataSource = new PageDataSource();
  @State leftPage: number = 0;
  @State rightPage: number = 0;

  aboutToAppear(): void {
    this.leftPage = this.pageIndex * 2;
    this.rightPage = this.pageIndex * 2 + 1;
  }

  build() {
    Row({ space: 2 }) {
      PageRenderer({ pageIndex: this.leftPage, dataSource: this.dataSource })
        .layoutWeight(1)
      if (this.rightPage < this.dataSource.getPageCount()) {
        PageRenderer({ pageIndex: this.rightPage, dataSource: this.dataSource })
          .layoutWeight(1)
      } else {
        Column()
          .layoutWeight(1)
          .height('100%')
          .backgroundColor('#F5F5F5')
      }
    }
    .width('100%')
    .height('100%')
  }
}

/**
 * Musician page renderer: shows one half of the page at a time via crop,
 * tapping the right zone advances top -> bottom -> next page.
 */
const MUSICIAN_TOP: CropRect = { x0: 0, y0: 0, x1: 1, y1: 0.5 };
const MUSICIAN_BOTTOM: CropRect = { x0: 0, y0: 0.5, x1: 1, y1: 1 };

@Component
struct MusicianPageRenderer {
  pageIndex: number = 0;
  dataSource: PageDataSource = new PageDataSource();
  @State half: number = 0; /* 0=top half, 1=bottom half */
  onHalfChange: (page: number, half: number) => void = () => {};

  build() {
    Column() {
      if (this.half === 0) {
        PageRenderer({ pageIndex: this.pageIndex, dataSource: this.dataSource, cropRect: MUSICIAN_TOP })
          .width('100%')
          .height('100%')
      } else {
        PageRenderer({ pageIndex: this.pageIndex, dataSource: this.dataSource, cropRect: MUSICIAN_BOTTOM })
          .width('100%')
          .height('100%')
      }
    }
    .width('100%')
    .height('100%')
  }
}

/**
 * Main Reader component.
 */
/* Languages for the AI translation dialog (hr18). */
const TRANS_SRC: string[] = ['\u81EA\u52A8\u68C0\u6D4B', '\u4E2D\u6587', '\u82F1\u6587', '\u65E5\u6587', '\u6CD5\u6587', '\u5FB7\u6587', '\u4FC4\u6587', '\u897F\u73ED\u7259\u6587'];
const TRANS_DST: string[] = ['\u4E2D\u6587', '\u82F1\u6587', '\u65E5\u6587', '\u6CD5\u6587', '\u5FB7\u6587', '\u4FC4\u6587', '\u897F\u73ED\u7259\u6587'];

@Entry
@Component
struct Reader {
  @State currentPage: number = 0;
  @State totalPages: number = 0;
  @State showMenu: boolean = false;
  @State pageList: number[] = [];
  @State docTitle: string = '';
  @State scrollMode: number = 0; /* 0=horizontal Swiper, 1=vertical List */
  @State pageMode: number = 0;   /* 0=single, 1=double, 2=musician */
  @State zoomLevel: number = 1.5;
  @State nightMode: boolean = false;
  @State showToc: boolean = false;
  @State tocEntries: TocEntry[] = [];
  @State showSearch: boolean = false;
  @State searchQuery: string = '';
  @State searchHits: TextRect[] = [];
  /* Sprint O2: full search results */
  @State searchResults: number[][] = []; /* [page, count] pairs */
  @State searchTotalHits: number = 0;
  @State searchCurrentIdx: number = -1;       /* normalized hits on the current page */
  @State searchHitPage: number = -1;        /* page the hits belong to */
  @State brightnessLevel: number = 128;     /* 1..255 system brightness */
  @State autoFlip: boolean = false;
  @State autoFlipInterval: number = 3;      /* seconds between flips */
  @State autoScroll: boolean = false;       /* continuous auto-scroll, vertical mode (A) */
  @State autoScrollSpeed: number = 2;       /* px per 50ms tick (2/4/6/8) */
  @State showBookmarks: boolean = false;    /* bookmark list panel */
  @State bookmarks: Bookmark[] = [];
  /* ---- Phase 10: AI 大模型接入 (hr13/17/18 parity) ---- */
  @State showTrans: boolean = false;        /* AI 翻译 dialog (hr18) */
  @State transSrcIdx: number = 0;
  @State transDstIdx: number = 1;
  @State transSave: boolean = true;
  @State transBusy: boolean = false;
  @State transResult: string = '';
  @State showTransPanel: boolean = false;   /* bottom sheet with the translation */
  @State aiNotes: AiNote[] = [];            /* AI notes for the bookmark panel (hr17) */
  @State showExportDialog: boolean = false; /* notes/bookmarks export format picker (A) */
  @State transInPage: boolean = false;      /* in-page bilingual toggle (D, EPUB/TXT only) */
  private biOriginalPath: string = '';      /* set while reading the rebuilt bilingual file */
  /* Round 5: top bar clock + battery (Android document_title_bar parity) */
  @State topClock: string = '';
  @State topBattery: string = '';
  @State showTopBarInfo: boolean = false;
  private topInfoTimerId: number = -1;
  /* Round 5: TTS notification remote control */
  private ttsLastCmdKey: string = '';
  /* Round 5: whole-book annotation list panel */
  @State showAnnPanel: boolean = false;
  @State annAll: AnnotationInfo[] = [];   /* flattened across pages (page encoded below) */
  @State annPages: number[] = [];          /* parallel page index for each annAll entry */
  @State annScanning: boolean = false;
  /* Round 5: page split (wide pages shown as two halves) */
  @State pageSplit: boolean = false;
  /* Round 5: contrast 0..100 (50 = neutral) */
  @State contrastLevel: number = 50;
  /* Round 5: page thumbnail grid */
  @State showThumbs: boolean = false;
  @State thumbMaps: image.PixelMap[] = [];
  @State thumbStart: number = 0;
  @State thumbBusy: boolean = false;
  /* Round 5: page-jump history (返回上一位置) */
  private pageHistory: number[] = [];
  private aiCfg: AiConfig = { provider: '', url: '', key: '', model: '', maxTokens: 8192, thinking: false };
  @State annotations: AnnotationInfo[] = []; /* current page annotations */
  @State drawMode: boolean = false;         /* freehand drawing mode */
  @State drawPoints: TextRect[] = [];
  private flipTimerId: number = -1;
  private scrollTimerId: number = -1;
  private vScroller: Scroller = new Scroller();
  private documentPath: string = '';
  private dataSource: PageDataSource = new PageDataSource();

  /* ---- TTS reading (D1) ---- */
  @State showTts: boolean = false;
  @State ttsStatus: string = '就绪';
  @State ttsEngineOk: boolean = true;
  @State ttsRate: number = 1.0;             /* 0.5..2.0 */
  @State ttsPitch: number = 1.0;            /* 0.5..2.0 */
  /* Sprint M1: voice + language */
  @State ttsVoiceIndex: number = 0;
  @State ttsLang: string = '';
  @State ttsVoices: string[] = [];          /* display names */
  @State ttsSentences: string[] = [];
  @State ttsIndex: number = 0;
  @State ttsPaused: boolean = false;
  private ttsStopped: boolean = true;
  private ttsPollId: number = -1;
  private ttsPollBusy: boolean = false;
  private ttsAdvancing: boolean = false;

  /* ---- Speed reading RSVP (D2) ---- */
  @State showRsvp: boolean = false;
  @State rsvpWord: string = '';
  @State rsvpProgress: string = '0/0';
  @State rsvpWpm: number = 300;             /* 100..800 */
  @State rsvpRunning: boolean = false;
  @State rsvpPaused: boolean = false;
  private rsvpTokens: string[] = [];
  private rsvpIndex: number = 0;
  private rsvpTimerId: number = -1;
  /** Phase 3: 30s reading-time reporting timer */
  private readTimerId: number = -1;
  /** Phase 6: reading-reminder one-shot timer */
  private reminderTimerId: number = -1;
  private rsvpAdvancing: boolean = false;

  /* Hidden ArkWeb host that exposes speechSynthesis for TTS */
  private webController: webview.WebviewController = new webview.WebviewController();

  /* ---- Settings & theme (sprint E) ---- */
  @State showSettings: boolean = false;
  @State theme: number = 0;             /* 0=light 1=dark 2=oled 3=ink */
  @State isReflow: boolean = false;     /* reflowable (EPUB/HTML) vs fixed (PDF) */
  @State fontSize: number = 16;         /* em, reflowable only */
  @State lineHeight: number = 1.6;      /* CSS line-height, reflowable only */
  @State pageMargin: number = 20;       /* CSS body margin pt, reflowable only */
  @State reflowVersion: number = 0;

  // ---- Sprint G6: File info dialog ----
  @State showFileInfo: boolean = false;
  @State fileInfoText: string = '';

  // ---- Sprint H: Text selection + typography ----
  @State textRectsJson: string = '[]';
  @State selectedLineIdx: number = -1;
  /* Phase 9: precise char-level selection + markup annotations */
  @State selMode: boolean = false;          /* selection mode active */
  @State selPreview: TextRect[] = [];       /* selection quads (normalized) */
  @State selText: string = '';              /* selected text content */
  @State showSelNoteInput: boolean = false; /* note input row visible */
  @State selNoteInput: string = '';
  private selLines: TextLine[] = [];        /* parsed text lines for the page */
  private selStartLine: number = -1;
  private selStartChar: number = -1;
  private selEndLine: number = -1;
  private selEndChar: number = -1;
  private pageGlobalX: number = 0;          /* page area origin for coord conversion */
  private pageGlobalY: number = 0;
  private pageAreaW: number = 0;
  private pageAreaH: number = 0;
  @State showTypographyPanel: boolean = false;
  @State textAlign: number = 0; /* 0=left, 1=center, 2=right, 3=justify */
  @State paragraphSpacing: number = 8;
  @State hyphenationEnabled: boolean = false;
  /* Sprint L: full CSS typography */
  @State textIndent: number = 2;
  @State dropCapEnabled: boolean = false;
  @State fontFamilyIdx: number = 0; /* 0=default,1=serif,2=sans-serif,3=monospace */
  @State cropEnabled: boolean = false; /* H6: 10% margin trim */

  // ---- Sprint I: Advanced reading features ----
  @State isFullscreen: boolean = false;       /* I1: immersive mode */
  @State blueLightFilter: number = 0;         /* I5: 0=off, 1-100=intensity % */
  @State bgImagePath: string = '';            /* I6: custom background image path */
  @State bgImageAlpha: number = 30;           /* I6: background opacity 0-100 */
  @State rtlEnabled: boolean = false;         /* I2: right-to-left reading direction */
  @State showGestureConfig: boolean = false;  /* I4: gesture configuration dialog */
  @State tapLeftAction: number = 0;           /* I4: 0=prev, 1=menu, 2=toc, 3=bookmark */
  @State tapCenterAction: number = 0;         /* I4: 0=menu, 1=fullscreen, 2=brightness */
  @State tapRightAction: number = 0;          /* I4: 0=next, 1=menu, 2=toc, 3=bookmark */
  @State showTextReplace: boolean = false;    /* I7: text replacement panel */
  @State replaceFrom: string = '';            /* I7: search text */
  @State replaceTo: string = '';              /* I7: replacement text */
  @State einkDetected: boolean = false;       /* I8: E-ink device auto-detect */
  @State renderQualityLevel: number = 1;      /* K4: 0=fast 1=balanced 2=high */

  aboutToAppear(): void {
    try {
      const ctx = getContext(this);
      /* Phase 3: report reading time in 30s chunks while the reader is open */
      this.readTimerId = setInterval(() => {
        this.tickReadTime();
      }, 30000);
      /* Round 5: clock+battery in the top bar, refresh every 30s */
      this.refreshTopInfo();
      this.topInfoTimerId = setInterval(() => {
        this.refreshTopInfo();
      }, 30000);
      this.initSettings(ctx).then(() => {
        const params = router.getParams() as Record<string, string>;
        if (params !== null && params !== undefined && params['docPath'] !== undefined) {
          this.documentPath = params['docPath'];
          this.docTitle = params['title'] !== undefined ? params['title'] : 'Document';
          console.info(`[Reader] Opening picked file: ${this.documentPath}`);
        } else {
          this.documentPath = ctx.cacheDir + '/test.pdf';
          this.docTitle = 'test.pdf';
          this.ensureTestPdf(this.documentPath).then(() => {
            this.initReader();
          }).catch((err: Error) => {
            console.error('[Reader] Init failed: ' + JSON.stringify(err));
          });
          return;
        }

        getPageForPath(ctx, this.documentPath).then((savedPage: number) => {
          this.currentPage = savedPage;
          console.info(`[Reader] Restoring page ${savedPage + 1}`);
          this.initReader();
        }).catch(() => {
          this.initReader();
        });
      });
    } catch (e) {
      console.error('[Reader] Init failed');
    }
  }

  /** Round 5: refresh clock + battery shown in the top bar. */
  private refreshTopInfo(): void {
    try {
      const d = new Date();
      const hh = d.getHours().toString().padStart(2, '0');
      const mm = d.getMinutes().toString().padStart(2, '0');
      this.topClock = hh + ':' + mm;
      const soc: number = batteryInfo.batterySOC;
      this.topBattery = (soc >= 0 ? soc : 0) + '%';
    } catch (e) {
      console.warn('[Reader] top info refresh failed');
    }
  }

  /** Load persisted settings and apply them to the runtime state. */
  private async initSettings(ctx: Context): Promise<void> {
    const s: ReaderSettings = await loadSettings(ctx);
    this.applySettings(s);
    /* I8: E-ink device auto-detection (heuristic: theme=3 means ink/OLED) */
      this.einkDetected = (this.theme === 3);
      if (this.einkDetected) {
        console.info('[Reader] E-ink mode active: high contrast');
      }

    console.info(`[Reader] Settings applied: theme=${s.theme} zoom=${s.zoom.toFixed(2)} font=${s.fontSize} lh=${s.lineHeight.toFixed(1)}`);
    /* Phase 6: reading reminder (one-shot after N minutes) */
    if (s.readReminderMinutes > 0) {
      this.reminderTimerId = setTimeout(() => {
        try {
          promptAction.showToast({ message: '\u5DF2\u8FDE\u7EED\u9605\u8BFB ' + s.readReminderMinutes + ' \u5206\u949F\uff0c\u5EFA\u8BAE\u4F11\u606F\u4E00\u4E0B' });
        } catch (e) {
          console.error('[Reader] reminder toast failed');
        }
        this.reminderTimerId = -1;
      }, s.readReminderMinutes * 60000);
    }
  }

  private applySettings(s: ReaderSettings): void {
    this.theme = s.theme;
    this.showTopBarInfo = s.showTopBarInfo;
    /* K4: Apply render quality multiplier */
      const qualityMults: number[] = [0.75, 1.0, 1.5];
      this.renderQualityLevel = s.renderQuality;
      this.zoomLevel = s.zoom * (qualityMults[s.renderQuality] !== undefined ? qualityMults[s.renderQuality] : 1.0);
    this.scrollMode = s.scrollMode;
    this.pageMode = s.pageMode;
    this.nightMode = s.theme === 1 || s.theme === 2;
    this.brightnessLevel = s.brightness;
    this.autoFlip = s.autoFlip;
    this.autoFlipInterval = s.autoFlipInterval;
    this.autoScroll = s.autoScroll;
    this.autoScrollSpeed = s.autoScrollSpeed;
    this.ttsRate = s.ttsRate;
    this.ttsPitch = s.ttsPitch;
    this.rsvpWpm = s.rsvpWpm;
    this.fontSize = s.fontSize;
    this.lineHeight = s.lineHeight;
    this.pageMargin = s.margin;
    this.blueLightFilter = s.blueLightFilter;
    this.bgImagePath = s.bgImagePath;
    this.dataSource.setZoom(this.zoomLevel);
    this.dataSource.setInvert(this.nightMode);
    if (this.brightnessLevel > 0) {
      try {
        brightness.setValue({ value: this.brightnessLevel });
      } catch (e) {
        /* brightness may be unavailable on some devices */
      }
    }
  }

  /** Persist the current runtime settings (called on exit and panel changes). */
  private async persistSettings(): Promise<void> {
    const s: ReaderSettings = {
      theme: this.theme,
      zoom: this.zoomLevel,
      scrollMode: this.scrollMode,
      pageMode: this.pageMode,
      nightMode: this.nightMode,
      autoFlip: this.autoFlip,
      autoFlipInterval: this.autoFlipInterval,
      autoScroll: this.autoScroll,
      autoScrollSpeed: this.autoScrollSpeed,
      brightness: this.brightnessLevel,
      ttsRate: this.ttsRate,
      ttsPitch: this.ttsPitch,
      rsvpWpm: this.rsvpWpm,
      fontSize: this.fontSize,
      lineHeight: this.lineHeight,
      margin: this.pageMargin,
      appLockEnabled: false, /* loaded from global prefs on Index side */
      appLockPass: '1234',
      readReminderMinutes: 0,
      blueLightFilter: this.blueLightFilter,
      bgImagePath: this.bgImagePath,
      proxyHost: '',
      proxyPort: 8080,
      proxyType: 0,
      renderQuality: this.renderQualityLevel,
      ttsVoiceIndex: this.ttsVoiceIndex,
      ttsLang: this.ttsLang,
      textIndent: this.textIndent,
      dropCapEnabled: this.dropCapEnabled,
      fontFamily: ['default', 'serif', 'sans-serif', 'monospace'][this.fontFamilyIdx],
      flipAnimation: 0,
      tabPositionTop: false, /* managed on the Index side */
      showTopBarInfo: this.showTopBarInfo,
      appLockBiometric: false /* managed on the Index side */
    };
    await saveSettings(getContext(this), s);
  }

  /** Phase 7: pick a background image via the system picker and persist its sandbox path. */
  private async pickBgImage(): Promise<void> {
    try {
      const ctx = getContext(this);
      let documentPicker = new picker.DocumentViewPicker(ctx);
      let options: picker.DocumentSelectOptions = {
        fileSuffixFilters: ['JPG|jpg|jpeg', 'PNG|png', 'WEBP|webp'],
        maxSelectNumber: 1
      };
      const uris: Array<string> = await documentPicker.select(options);
      if (uris.length === 0) {
        console.info('[Reader] BG image picker cancelled');
        return;
      }
      const target: string = importUriToSandbox(ctx, uris[0]);
      this.bgImagePath = target;
      this.bgImageAlpha = 60;
      this.persistSettings();
      console.info(`[Reader] Background image set: ${target}`);
    } catch (e) {
      console.error('[Reader] pickBgImage failed');
    }
  }

  /** Apply a theme: drives invert + UI chrome colors. */
  private setTheme(t: number): void {
    this.theme = t;
    this.nightMode = t === 1 || t === 2;
    this.dataSource.setInvert(this.nightMode);
    this.persistSettings();
    console.info(`[Reader] Theme: ${THEME_NAMES[t]}`);
  }

  /** Reading-area / bottom-bar background per theme. */
  private chromeBg(): string {
    if (this.theme === 3) {
      return '#eaddc3'; /* ink: warm paper */
    }
    if (this.nightMode) {
      return '#101010';
    }
    return '#f5f5f5';
  }

  /** Top-bar background per theme (Android hr10: brand blue / night navy / ink brown). */
  private barBg(): string {
    if (this.theme === 3) {
      return '#4a3a28'; /* ink: dark brown bar */
    }
    if (this.nightMode) {
      return '#282b40';
    }
    return '#3949AB';
  }

  /** Re-layout a reflowable doc with the current typography settings. */
  private async applyReflow(): Promise<void> {
    const handle: ESObject | undefined = this.dataSource.getDocHandle();
    if (handle === undefined || !this.isReflow) {
      return;
    }
    try {
      const css = this.buildCss();
      layoutDocument(handle, REFLOW_W, REFLOW_H, this.fontSize, css);
      const newTotal: number = pageCount(handle);
      this.pageList = [];
      for (let i = 0; i < newTotal; i++) {
        this.pageList.push(i);
      }
      this.totalPages = newTotal;
      this.currentPage = Math.min(this.currentPage, newTotal - 1);
      this.reflowVersion++;
      this.onPageChanged(this.currentPage);
      this.loadToc();
      this.persistSettings();
      console.info(`[Reader] Reflow applied: ${newTotal} pages (em=${this.fontSize}, lh=${this.lineHeight.toFixed(1)}, m=${this.pageMargin})`);
    } catch (e) {
      console.error('[Reader] applyReflow failed');
    }
  }

  private initReader(): void {
    try {
      this.dataSource.openDoc(this.documentPath);
      const handle: ESObject | undefined = this.dataSource.getDocHandle();
      if (handle !== undefined) {
        try {
          this.isReflow = isReflowable(handle);
          console.info(`[Reader] Reflowable document: ${this.isReflow}`);
        } catch (e) {
          this.isReflow = false;
        }
        if (this.isReflow) {
          try {
            const css = `body{margin:${this.pageMargin}pt;line-height:${this.lineHeight.toFixed(2)}}`;
            layoutDocument(handle, REFLOW_W, REFLOW_H, this.fontSize, css);
            console.info(`[Reader] Reflow layout: em=${this.fontSize} lh=${this.lineHeight.toFixed(1)} m=${this.pageMargin}`);
          } catch (e) {
            console.error('[Reader] Reflow layout failed');
          }
        }
      }
      this.totalPages = this.dataSource.getPageCount();
      for (let i = 0; i < this.totalPages; i++) {
        this.pageList.push(i);
      }
      /* Load TOC, bookmarks and page annotations in background */
      this.loadToc();
      this.loadBookmarks();
      this.refreshAnnotations();
      console.info(`[Reader] Document opened: ${this.totalPages} pages, starting at page ${this.currentPage + 1}`);
    } catch (e) {
      console.error('[Reader] Failed to open document: ' + JSON.stringify(e));
    }
  }

  private async loadToc(): Promise<void> {
    try {
      const handle: ESObject | undefined = this.dataSource.getDocHandle();
      if (handle !== undefined) {
        this.tocEntries = getToc(handle);
        console.info(`[Reader] TOC loaded: ${this.tocEntries.length} entries`);
      }
    } catch (e) {
      console.info('[Reader] No TOC available');
    }
  }

  private async ensureTestPdf(target: string): Promise<void> {
    try {
      const content: Uint8Array = await getContext(this).resourceManager.getRawFileContent('test.pdf');
      const file = fs.openSync(target, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      fs.writeSync(file.fd, content.buffer);
      fs.closeSync(file);
      console.info(`[Reader] test.pdf extracted to ${target}, size=${content.byteLength}`);
    } catch (e) {
      console.warn('[Reader] test.pdf already exists or extract failed');
    }
  }

  aboutToDisappear(): void {
    if (this.readTimerId >= 0) {
      clearInterval(this.readTimerId);
      this.readTimerId = -1;
    }
    if (this.reminderTimerId >= 0) {
      clearTimeout(this.reminderTimerId);
      this.reminderTimerId = -1;
    }
    if (this.topInfoTimerId >= 0) {
      clearInterval(this.topInfoTimerId);
      this.topInfoTimerId = -1;
    }
    this.stopAutoFlip();
    this.ttsStopped = true;
    this.stopTtsPoll();
    stopTtsBackgroundTask(getContext(this));
    cancelTtsNotification();
    this.webController.runJavaScript('ttsStop()').then((r: string) => {
    }).catch(() => {
    });
    this.stopRsvpTimer();
    this.persistSettings();
    if (this.documentPath.length > 0 && this.totalPages > 0) {
      const ctx = getContext(this);
      const book: RecentBook = {
        path: this.documentPath,
        title: this.docTitle,
        page: this.currentPage,
        totalPages: this.totalPages,
        lastRead: new Date().toISOString(),
        star: false,
        coverPath: '',
        author: '',
        tags: [],
        series: '',
        genre: '',
        status: 0
      };
      saveRecentBook(ctx, book);
    }
    this.dataSource.closeDoc();
  }

  /** Phase 3: accumulate a 30s reading-time chunk. */
  private tickReadTime(): void {
    try {
      recordReadTime(getContext(this), 30000).catch((e: Error) => {
      });
    } catch (e) {
      console.error('[Reader] read time tick failed');
    }
  }

  private onPageChanged(index: number): void {
    const prevPage = this.currentPage;
    this.currentPage = index;
    if (this.selMode) {
      this.exitSelMode();
    }
    console.info(`[Reader] Page changed to ${index + 1}/${this.totalPages}`);
    /* Phase 3: count a flipped page only when the page actually changed */
    if (prevPage !== index) {
      try {
        recordPagesRead(getContext(this), 1).catch((e: Error) => {
        });
      } catch (e) {
        /* non-fatal */
      }
    }
    this.saveProgress(index);
    this.refreshAnnotations();
    /* Re-anchor active reading modes (TTS / RSVP) to the new page */
    if (this.showTts && !this.ttsStopped && !this.ttsAdvancing) {
      this.ttsAdvancing = true;
      this.ttsIndex = 0;
      this.loadTtsSentences(index).then(() => {
        this.ttsAdvancing = false;
        if (this.showTts && !this.ttsStopped) {
          this.ttsSpeakCurrent();
        }
      });
    }
    if (this.showRsvp && this.rsvpRunning && !this.rsvpAdvancing) {
      this.rsvpIndex = 0;
      this.loadRsvpTokens(index).then(() => {
        if (this.showRsvp && this.rsvpRunning) {
          this.updateRsvpDisplay();
        }
      });
    }
  }

  private saveProgress(page: number): void {
    if (this.documentPath.length > 0 && this.totalPages > 0) {
      const ctx = getContext(this);
      const book: RecentBook = {
        path: this.documentPath,
        title: this.docTitle,
        page: page,
        totalPages: this.totalPages,
        lastRead: new Date().toISOString(),
        star: false,
        coverPath: '',
        author: '',
        tags: [],
        series: '',
        genre: '',
        status: 0
      };
      saveRecentBook(ctx, book);
    }
  }

  private zoomIn(): void {
    const newZoom = Math.min(this.zoomLevel + ZOOM_STEP, MAX_ZOOM);
    if (newZoom !== this.zoomLevel) {
      this.zoomLevel = newZoom;
      this.dataSource.setZoom(newZoom);
      console.info(`[Reader] Zoom in: ${this.zoomLevel.toFixed(2)}x`);
    }
  }

  private zoomOut(): void {
    const newZoom = Math.max(this.zoomLevel - ZOOM_STEP, MIN_ZOOM);
    if (newZoom !== this.zoomLevel) {
      this.zoomLevel = newZoom;
      this.dataSource.setZoom(newZoom);
      console.info(`[Reader] Zoom out: ${this.zoomLevel.toFixed(2)}x`);
    }
  }

  private toggleNightMode(): void {
    this.nightMode = !this.nightMode;
    this.theme = this.nightMode ? 1 : 0;
    this.dataSource.setInvert(this.nightMode);
    this.persistSettings();
    console.info(`[Reader] Night mode: ${this.nightMode ? 'ON' : 'OFF'} (theme=${THEME_NAMES[this.theme]})`);
  }

  private toggleScrollMode(): void {
    this.scrollMode = this.scrollMode === 0 ? 1 : 0;
    console.info(`[Reader] Scroll mode: ${this.scrollMode === 0 ? 'horizontal' : 'vertical'}`);
  }

  private togglePageMode(): void {
    this.pageMode = (this.pageMode + 1) % 3;
    /* keep the current page visible after mode switch */
    this.currentPage = Math.min(this.currentPage, this.totalPages - 1);
    const names: string[] = ['single', 'double', 'musician'];
    console.info(`[Reader] Page mode: ${names[this.pageMode]}`);
  }

  private openSearch(): void {
    this.showSearch = true;
    this.searchHits = [];
    this.searchHitPage = -1;
    console.info('[Reader] Search bar opened');
  }

  private closeSearch(): void {
    this.showSearch = false;
    this.searchHits = [];
    this.searchHitPage = -1;
    console.info('[Reader] Search bar closed');
  }

  /** Sprint O2: search whole book using NAPI searchDocument, build hit list. */
  private async doSearch(): Promise<void> {
    const q: string = this.searchQuery.trim();
    if (q.length === 0 || this.totalPages <= 0) {
      return;
    }
    const handle: ESObject | undefined = this.dataSource.getDocHandle();
    if (handle === undefined) {
      return;
    }
    console.info(`[Reader] Search '${q}'...`);
    try {
      const jsonStr: string = searchDocument(handle, q);
      const parsed: object = JSON.parse(jsonStr) as object;
      const pagesArr: object[] = (parsed['pages'] !== undefined ? parsed['pages'] : []) as object[];
      this.searchTotalHits = parsed['totalHits'] !== undefined ? parsed['totalHits'] as number : 0;
      this.searchResults = [];
      for (let i = 0; i < pagesArr.length; i++) {
        const pg: number = pagesArr[i]['page'] as number;
        const ct: number = pagesArr[i]['count'] as number;
        this.searchResults.push([pg, ct]);
      }
      if (this.searchResults.length > 0) {
        /* Jump to first hit page and load its rects */
        const firstPage: number = this.searchResults[0][0];
        this.searchCurrentIdx = 0;
        this.highlightPageHits(firstPage);
        console.info(`[Reader] Search '${q}': ${this.searchTotalHits} hits on ${this.searchResults.length} pages`);
      } else {
        this.searchHits = [];
        this.searchHitPage = -1;
        console.info(`[Reader] Search '${q}': no hits`);
      }
    } catch (e) {
      console.error('[Reader] searchDocument failed: ' + String(e));
    }
  }

  /** Sprint O2: load and normalize hit rects for a specific page. */
  private highlightPageHits(pageIdx: number): void {
    const handle: ESObject | undefined = this.dataSource.getDocHandle();
    if (handle === undefined) return;
    try {
      const q: string = this.searchQuery.trim();
      const rects: TextRect[] = searchText(handle, q, pageIdx);
      const size: TextRect = getPageSize(handle, pageIdx);
      const pw = (size.x1 - size.x0) > 0 ? (size.x1 - size.x0) : 1;
      const ph = (size.y1 - size.y0) > 0 ? (size.y1 - size.y0) : 1;
      const norm: TextRect[] = [];
      for (let i = 0; i < rects.length; i++) {
        norm.push({
          x0: (rects[i].x0 - size.x0) / pw,
          y0: (rects[i].y0 - size.y0) / ph,
          x1: (rects[i].x1 - size.x0) / pw,
          y1: (rects[i].y1 - size.y0) / ph
        });
      }
      this.searchHits = norm;
      this.searchHitPage = pageIdx;
      this.currentPage = pageIdx;
      this.onPageChanged(pageIdx);
    } catch (e) {
      console.error('[Reader] highlightPageHits failed');
    }
  }

  /** Sprint O2: navigate to next hit page. */
  private nextHitPage(): void {
    if (this.searchResults.length === 0 || this.searchCurrentIdx < 0) return;
    const next = this.searchCurrentIdx + 1;
    if (next >= this.searchResults.length) return;
    this.searchCurrentIdx = next;
    this.highlightPageHits(this.searchResults[next][0]);
    console.info(`[Reader] Next hit: page ${this.searchResults[next][0] + 1} (${next + 1}/${this.searchResults.length})`);
  }

  /** Sprint O2: navigate to previous hit page. */
  private prevHitPage(): void {
    if (this.searchResults.length === 0 || this.searchCurrentIdx < 0) return;
    const prev = this.searchCurrentIdx - 1;
    if (prev < 0) return;
    this.searchCurrentIdx = prev;
    this.highlightPageHits(this.searchResults[prev][0]);
    console.info(`[Reader] Prev hit: page ${this.searchResults[prev][0] + 1} (${prev + 1}/${this.searchResults.length})`);
  }



  // ---- Bookmarks (C4) ----
  private async toggleBookmark(): Promise<void> {
    const ctx = getContext(this);
    if (this.documentPath.length === 0) {
      return;
    }
    const list = await getBookmarks(ctx, this.documentPath);
    let found = false;
    for (let i = 0; i < list.length; i++) {
      if (list[i].page === this.currentPage) {
        found = true;
        break;
      }
    }
    if (found) {
      await removeBookmark(ctx, this.documentPath, this.currentPage);
      console.info(`[Reader] Bookmark removed at page ${this.currentPage + 1}`);
    } else {
      const ok = await addBookmark(ctx, this.documentPath, this.currentPage, '');
      if (ok) {
        console.info(`[Reader] Bookmark added at page ${this.currentPage + 1}`);
      }
    }
    await this.loadBookmarks();
  }

  private async loadBookmarks(): Promise<void> {
    if (this.documentPath.length === 0) {
      this.bookmarks = [];
      this.aiNotes = [];
      return;
    }
    const ctx = getContext(this);
    this.bookmarks = await getBookmarks(ctx, this.documentPath);
    this.loadAiNotes();
    console.info(`[Reader] Bookmarks loaded: ${this.bookmarks.length}`);
  }

  private jumpToBookmark(page: number): void {
    this.pushPageHistory();
    this.currentPage = Math.max(0, Math.min(page, this.totalPages - 1));
    this.onPageChanged(this.currentPage);
    this.showBookmarks = false;
    console.info(`[Reader] Jumped to bookmark page ${page + 1}`);
  }

  // ---- Annotations (C2 highlight) ----
  private async refreshAnnotations(): Promise<void> {
    const handle: ESObject | undefined = this.dataSource.getDocHandle();
    if (handle === undefined) {
      return;
    }
    try {
      this.annotations = getAnnotations(handle, this.currentPage);
      console.info(`[Reader] Page ${this.currentPage + 1} annotations: ${this.annotations.length}`);
    } catch (e) {
      this.annotations = [];
      console.error('[Reader] getAnnotations failed');
    }
  }

  /** Add a highlight over the current search hit region (or page center). */
  private async addHighlightOnPage(): Promise<void> {
    const handle: ESObject | undefined = this.dataSource.getDocHandle();
    if (handle === undefined) {
      return;
    }
    let x0 = 0.2, y0 = 0.3, x1 = 0.8, y1 = 0.5; /* default region */
    if (this.searchHits.length > 0) {
      x0 = this.searchHits[0].x0;
      y0 = this.searchHits[0].y0;
      x1 = this.searchHits[0].x1;
      y1 = this.searchHits[0].y1;
    }
    try {
      addHighlight(handle, this.currentPage, x0, y0, x1, y1, '#ffff00');
      await this.refreshAnnotations();
      console.info(`[Reader] Highlight added on page ${this.currentPage + 1}`);
    } catch (e) {
      console.error('[Reader] addHighlight failed');
    }
  }

  private async deleteAnnotationAt(index: number): Promise<void> {
    const handle: ESObject | undefined = this.dataSource.getDocHandle();
    if (handle === undefined) {
      return;
    }
    try {
      deleteAnnotation(handle, this.currentPage, index);
      await this.refreshAnnotations();
      console.info(`[Reader] Annotation ${index} deleted`);
    } catch (e) {
      console.error('[Reader] deleteAnnotation failed');
    }
  }

  private async saveAnnotatedDoc(): Promise<void> {
    const handle: ESObject | undefined = this.dataSource.getDocHandle();
    if (handle === undefined || this.documentPath.length === 0) {
      return;
    }
    try {
      saveDocument(handle, this.documentPath);
      console.info('[Reader] Document saved with annotations');
    } catch (e) {
      console.error('[Reader] saveDocument failed');
    }
  }

  /* ---- Round 5: whole-book annotation list panel ---- */

  /** Open the panel and scan annotations across all pages (capped). */
  private async openAnnPanel(): Promise<void> {
    this.showAnnPanel = true;
    this.annScanning = true;
    this.annAll = [];
    this.annPages = [];
    const handle: ESObject | undefined = this.dataSource.getDocHandle();
    if (handle === undefined) {
      this.annScanning = false;
      return;
    }
    try {
      const maxScan: number = Math.min(this.totalPages, 500);
      for (let p = 0; p < maxScan; p++) {
        const list: AnnotationInfo[] = getAnnotations(handle, p);
        for (let i = 0; i < list.length; i++) {
          this.annAll.push(list[i]);
          this.annPages.push(p);
        }
      }
      console.info(`[Reader] Annotation scan: ${this.annAll.length} items in ${maxScan} pages`);
    } catch (e) {
      console.error('[Reader] Annotation scan failed');
    }
    this.annScanning = false;
  }

  /** Delete one entry from the whole-book list (page-local index needed). */
  private async deleteAnnEntry(idx: number): Promise<void> {
    const handle: ESObject | undefined = this.dataSource.getDocHandle();
    if (handle === undefined) {
      return;
    }
    try {
      deleteAnnotation(handle, this.annPages[idx], this.annAll[idx].index);
      await this.openAnnPanel();
      await this.refreshAnnotations();
    } catch (e) {
      console.error('[Reader] ann entry delete failed');
    }
  }

  /** Jump to the page holding an annotation. */
  private jumpAnnPage(idx: number): void {
    const p: number = this.annPages[idx];
    if (p >= 0 && p < this.totalPages) {
      this.currentPage = p;
      this.showAnnPanel = false;
      this.onPageChanged(p);
      console.info(`[Reader] Jump to annotation page ${p + 1}`);
    }
  }

  /* ---- Round 5: page-jump history (返回上一位置) ---- */

  private pushPageHistory(): void {
    if (this.currentPage >= 0) {
      this.pageHistory.push(this.currentPage);
      if (this.pageHistory.length > PAGE_HISTORY_MAX) {
        this.pageHistory.shift();
      }
    }
  }

  /** Pop back to the last jumped-from page. */
  private popPageHistory(): void {
    if (this.pageHistory.length === 0) {
      promptAction.showToast({ message: $r('app.string.menu_history_empty') });
      return;
    }
    const p: number = this.pageHistory.pop() as number;
    this.currentPage = Math.min(p, this.totalPages - 1);
    this.onPageChanged(this.currentPage);
    console.info(`[Reader] Back to page ${this.currentPage + 1} (history)`);
  }

  /* ---- Round 5: page thumbnail grid (Android page thumbnails parity) ---- */

  private openThumbs(): void {
    this.showThumbs = true;
    this.renderThumbWindow(this.currentPage);
  }

  /** Render a window of THUMB_WINDOW pages starting at `start`. */
  private async renderThumbWindow(start: number): Promise<void> {
    const handle: ESObject | undefined = this.dataSource.getDocHandle();
    if (handle === undefined || this.thumbBusy) {
      return;
    }
    const first: number = Math.max(0, Math.min(start, this.totalPages - 1));
    this.thumbStart = first;
    this.thumbBusy = true;
    this.thumbMaps = [];
    try {
      const count: number = Math.min(THUMB_WINDOW, this.totalPages - first);
      for (let i = 0; i < count; i++) {
        const p: number = first + i;
        const res: RenderResult = await renderPageAsync(handle, p, { zoom: 0.12 });
        const opts: image.InitializationOptions = {
          size: { width: res.width, height: res.height },
          pixelFormat: image.PixelMapFormat.RGBA_8888
        };
        const pm: image.PixelMap = await image.createPixelMap(res.data, opts);
        this.thumbMaps.push(pm);
      }
      console.info(`[Reader] Thumbnails rendered: ${this.thumbMaps.length} from page ${first + 1}`);
    } catch (e) {
      console.error('[Reader] Thumbnail render failed');
    }
    this.thumbBusy = false;
  }

  private shiftThumbs(delta: number): void {
    const next: number = this.thumbStart + delta * THUMB_WINDOW;
    if (next < 0 || next >= this.totalPages) {
      return;
    }
    this.renderThumbWindow(next);
  }

  // ---- Freehand drawing (C3) ----
  private toggleDrawMode(): void {
    this.drawMode = !this.drawMode;
    this.drawPoints = [];
    console.info(`[Reader] Draw mode: ${this.drawMode ? 'ON' : 'OFF'}`);
  }

  private onDrawTouch(event: TouchEvent): void {
    if (!this.drawMode) {
      return;
    }
    if (event.touches.length > 0) {
      const t = event.touches[0];
      const x = t.x;
      const y = t.y;
      /* capture points as normalized coords approximated via container size */
      this.drawPoints.push({ x0: x, y0: y, x1: x, y1: y });
    }
    if (event.type === TouchType.Up && this.drawPoints.length > 1) {
      this.finishInkStroke();
    }
  }

  private async finishInkStroke(): Promise<void> {
    const handle: ESObject | undefined = this.dataSource.getDocHandle();
    if (handle === undefined || this.drawPoints.length < 2) {
      this.drawPoints = [];
      return;
    }
    /* normalize using a nominal 1000x1500 coordinate space (matches reader area) */
    const norm: TextRect[] = [];
    for (let i = 0; i < this.drawPoints.length; i++) {
      norm.push({
        x0: this.drawPoints[i].x0 / 1000.0,
        y0: this.drawPoints[i].y0 / 1500.0,
        x1: 0, y1: 0
      });
    }
    try {
      addInkStroke(handle, this.currentPage, norm);
      await this.refreshAnnotations();
      console.info(`[Reader] Ink stroke saved (${norm.length} points)`);
    } catch (e) {
      console.error('[Reader] addInkStroke failed');
    }
    this.drawPoints = [];
  }

  private setBrightness(level: number): void {
    this.brightnessLevel = Math.max(1, Math.min(255, Math.round(level)));
    try {
      brightness.setValue({ value: this.brightnessLevel });
      console.info(`[Reader] Brightness: ${this.brightnessLevel}`);
    } catch (e) {
      console.error('[Reader] Brightness set failed');
    }
  }

  private toggleAutoFlip(): void {
    this.autoFlip = !this.autoFlip;
    if (this.autoFlip) {
      this.startAutoFlip();
    } else {
      this.stopAutoFlip();
    }
    console.info(`[Reader] Auto-flip: ${this.autoFlip ? 'ON' : 'OFF'} (${this.autoFlipInterval}s)`);
  }

  private startAutoFlip(): void {
    this.stopAutoFlip();
    this.flipTimerId = setInterval(() => {
      if (this.totalPages > 0 && this.currentPage < this.totalPages - 1) {
        this.currentPage = this.currentPage + 1;
        this.onPageChanged(this.currentPage);
      } else {
        /* reached the end: stop */
        this.autoFlip = false;
        this.stopAutoFlip();
        console.info('[Reader] Auto-flip: finished (last page)');
      }
    }, this.autoFlipInterval * 1000);
  }

  private stopAutoFlip(): void {
    if (this.flipTimerId >= 0) {
      clearInterval(this.flipTimerId);
      this.flipTimerId = -1;
    }
  }

  /* ---- A: continuous auto-scroll (vertical scroll mode) ---- */

  private toggleAutoScroll(): void {
    this.autoScroll = !this.autoScroll;
    if (this.autoScroll) {
      if (this.scrollMode === 0) {
        /* scrolling needs the vertical List; switch automatically */
        this.toggleScrollMode();
      }
      this.startAutoScroll();
    } else {
      this.stopAutoScroll();
    }
    console.info(`[Reader] Auto-scroll: ${this.autoScroll ? 'ON' : 'OFF'} (${this.autoScrollSpeed}px/tick)`);
  }

  private startAutoScroll(): void {
    this.stopAutoScroll();
    this.scrollTimerId = setInterval(() => {
      try {
        this.vScroller.scrollBy(0, this.autoScrollSpeed);
      } catch (e) {
        /* scroller not attached yet (List still mounting) — keep ticking */
      }
    }, 50);
  }

  private stopAutoScroll(): void {
    if (this.scrollTimerId >= 0) {
      clearInterval(this.scrollTimerId);
      this.scrollTimerId = -1;
    }
  }

  // ---- TTS reading (D1) ----

  private openTtsPanel(): void {
    this.showRsvp = false;
    this.stopRsvpTimer();
    this.showTts = true;
    this.ttsStopped = false;
    this.ttsPaused = false;
    this.ttsIndex = 0;
    this.ttsStatus = '准备中...';
    this.probeTtsEngine();
    this.loadTtsSentences(this.currentPage).then(() => {
      console.info(`[Reader] TTS panel opened: page ${this.currentPage + 1}, ${this.ttsSentences.length} sentences`);
      if (this.showTts && !this.ttsStopped) {
        this.ttsSpeakCurrent();
      }
    });
  }

  private closeTtsPanel(): void {
    this.ttsStopped = true;
    this.stopTtsPoll();
    this.webController.runJavaScript('ttsStop()').then((r: string) => {
    }).catch(() => {
    });
    stopTtsBackgroundTask(getContext(this));
    cancelTtsNotification();
    this.showTts = false;
    console.info('[Reader] TTS panel closed');
  }

  private async probeTtsEngine(): Promise<void> {
    try {
      const res: string = await this.webController.runJavaScript('ttsProbe()');
      const count: number = parseInt(res, 10);
      if (count <= 0) {
        this.ttsEngineOk = false;
        this.ttsStatus = count === -1 ? '语音引擎不可用' : '未检测到语音包';
        console.warn(`[Reader] TTS probe: ${count}`);
      } else {
        this.ttsEngineOk = true;
        console.info(`[Reader] TTS probe: ${count} voices`);
      }
    } catch (e) {
      this.ttsEngineOk = false;
      this.ttsStatus = 'Web 引擎不可用';
      console.error('[Reader] TTS probe failed');
    }
  }

  private async loadTtsSentences(page: number): Promise<void> {
    const handle: ESObject | undefined = this.dataSource.getDocHandle();
    if (handle === undefined) {
      this.ttsSentences = [];
      return;
    }
    try {
      const raw: string = getText(handle, page, 1.0);
      this.ttsSentences = TextUtils.splitSentences(TextUtils.normalize(raw));
      this.ttsIndex = 0;
    } catch (e) {
      this.ttsSentences = [];
      console.error('[Reader] TTS getText failed');
    }
  }

  private async ttsSpeakCurrent(): Promise<void> {
    if (this.ttsStopped || !this.showTts) {
      return;
    }
    if (this.ttsIndex >= this.ttsSentences.length) {
      this.ttsAdvancePage();
      return;
    }
    const sent: string = this.ttsSentences[this.ttsIndex].trim();
    if (sent.length === 0) {
      this.ttsIndex++;
      this.ttsSpeakCurrent();
      return;
    }
    try {
      const res: string = await this.webController.runJavaScript(
        `ttsSpeak(${JSON.stringify(sent)},${this.ttsRate},${this.ttsPitch},${this.ttsIndex})`);
      const code: number = parseInt(res, 10);
      if (code !== 0) {
        this.ttsEngineOk = false;
        this.ttsStopped = true;
        this.ttsStatus = code === -2 ? '未检测到语音包' : '语音引擎不可用';
        this.stopTtsPoll();
        console.warn(`[Reader] TTS speak rejected: ${code}`);
        return;
      }
      this.ttsStatus = '朗读中';
      this.ttsPaused = false;
      this.startTtsPoll();
      /* Round 5: keep app alive + ongoing notification while reading aloud */
      startTtsBackgroundTask(getContext(this));
      this.refreshTtsNotification();
      console.info(`[Reader] TTS speaking ${this.ttsIndex + 1}/${this.ttsSentences.length} (page ${this.currentPage + 1})`);
    } catch (e) {
      this.ttsEngineOk = false;
      this.ttsStopped = true;
      this.ttsStatus = 'Web 引擎不可用';
      this.stopTtsPoll();
      console.error('[Reader] TTS speak JS call failed');
    }
  }

  private startTtsPoll(): void {
    this.stopTtsPoll();
    this.ttsPollId = setInterval(() => {
      this.ttsPollTick();
    }, 400);
  }

  /** Round 5: apply a command received from the notification control buttons. */
  private consumeTtsRemoteCmd(): void {
    const v = AppStorage.get<string>('ttsCmd');
    if (v === undefined || v.length === 0 || v === this.ttsLastCmdKey) {
      return;
    }
    this.ttsLastCmdKey = v;
    const cmd: string = v.substring(0, v.indexOf('#') >= 0 ? v.indexOf('#') : v.length);
    console.info(`[Reader] TTS remote cmd: ${cmd}`);
    if (cmd === 'prev') {
      this.ttsPrevSentence();
    } else if (cmd === 'next') {
      this.ttsNextSentence();
    } else if (cmd === 'toggle') {
      this.ttsTogglePause();
      this.refreshTtsNotification();
    }
  }

  /** Round 5: update the ongoing notification to the current sentence/state. */
  private refreshTtsNotification(): void {
    const sent: string = this.ttsIndex >= 0 && this.ttsIndex < this.ttsSentences.length
      ? this.ttsSentences[this.ttsIndex] : '';
    showTtsNotification(getContext(this), this.docTitle, sent, this.ttsPaused);
  }

  private stopTtsPoll(): void {
    if (this.ttsPollId >= 0) {
      clearInterval(this.ttsPollId);
      this.ttsPollId = -1;
    }
  }

  private async ttsPollTick(): Promise<void> {
    if (this.ttsPollBusy) {
      return;
    }
    this.ttsPollBusy = true;
    try {
      this.consumeTtsRemoteCmd();
      if (this.ttsStopped || !this.showTts) {
        this.stopTtsPoll();
        return;
      }
      const res: string = await this.webController.runJavaScript('ttsStatus()');
      const code: number = parseInt(res, 10);
      if (code === 2) {
        this.ttsPaused = true;
        this.ttsStatus = '已暂停';
        return;
      }
      if (code === 1) {
        this.ttsPaused = false;
        return;
      }
      /* idle (0) or engine gone (-1): current sentence finished → speak next */
      this.ttsPaused = false;
      this.ttsIndex++;
      this.ttsSpeakCurrent();
    } catch (e) {
      this.stopTtsPoll();
    } finally {
      this.ttsPollBusy = false;
    }
  }

  private ttsTogglePause(): void {
    if (this.ttsStopped) {
      return;
    }
    if (this.ttsPaused) {
      this.webController.runJavaScript('ttsResume()').then((r: string) => {
        this.ttsPaused = false;
        this.ttsStatus = '朗读中';
        this.refreshTtsNotification();
        console.info('[Reader] TTS resumed');
      }).catch(() => {
      });
    } else {
      this.webController.runJavaScript('ttsPause()').then((r: string) => {
        this.ttsPaused = true;
        this.ttsStatus = '已暂停';
        this.refreshTtsNotification();
        console.info('[Reader] TTS paused');
      }).catch(() => {
      });
    }
  }

  private ttsStop(): void {
    this.ttsStopped = true;
    this.ttsPaused = false;
    this.stopTtsPoll();
    this.webController.runJavaScript('ttsStop()').then((r: string) => {
    }).catch(() => {
    });
    stopTtsBackgroundTask(getContext(this));
    cancelTtsNotification();
    this.ttsStatus = '已停止';
    console.info('[Reader] TTS stopped');
  }

  private ttsPrevSentence(): void {
    if (this.ttsSentences.length === 0) {
      return;
    }
    this.ttsIndex = Math.max(0, this.ttsIndex - 1);
    this.ttsSpeakCurrent();
  }

  private ttsNextSentence(): void {
    if (this.ttsSentences.length === 0) {
      return;
    }
    this.ttsIndex = Math.min(this.ttsIndex + 1, this.ttsSentences.length - 1);
    this.ttsSpeakCurrent();
  }

  private ttsAdvancePage(): void {
    if (this.currentPage >= this.totalPages - 1) {
      this.ttsStopped = true;
      this.stopTtsPoll();
      this.ttsStatus = '朗读完成';
      console.info('[Reader] TTS finished (last page)');
      return;
    }
    this.currentPage = this.currentPage + 1;
    this.ttsAdvancing = true;
    this.onPageChanged(this.currentPage);
    this.ttsAdvancing = false;
    this.ttsIndex = 0;
    this.loadTtsSentences(this.currentPage).then(() => {
      if (this.showTts && !this.ttsStopped) {
        this.ttsSpeakCurrent();
      }
    });
  }

  /** Sprint M2: export current page text to sandbox .txt file. */
  private async exportTtsText(): Promise<void> {
    try {
      const h: ESObject | undefined = this.dataSource.getDocHandle();
      if (h === undefined) return;
      const raw: string = getText(h, this.currentPage);
      const ctx = getContext(this);
      const path: string = ctx.cacheDir + '/tts_export.txt';
      let f = fs.openSync(path, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      /* Convert string to UTF-8 bytes for file write */
      const bytes: Uint8Array = new Uint8Array(new ArrayBuffer(raw.length * 3));
      let offset = 0;
      for (let ci = 0; ci < raw.length; ci++) {
        const code = raw.charCodeAt(ci);
        if (code < 0x80) {
          bytes[offset++] = code;
        } else if (code < 0x800) {
          bytes[offset++] = 0xC0 | (code >> 6);
          bytes[offset++] = 0x80 | (code & 0x3F);
        } else {
          bytes[offset++] = 0xE0 | (code >> 12);
          bytes[offset++] = 0x80 | ((code >> 6) & 0x3F);
          bytes[offset++] = 0x80 | (code & 0x3F);
        }
      }
      const trimmed: Uint8Array = bytes.subarray(0, offset);
      fs.writeSync(f.fd, trimmed.buffer);
      fs.closeSync(f);
      this.ttsStatus = `\u5DF2\u5BFC\u51FA: tts_export.txt (${raw.length} \u5B57)`;
      console.info(`[Reader] TTS text exported: ${path}`);
    } catch (e) {
      this.ttsStatus = '\u5BFC\u51FA\u5931\u8D25';
    }
  }

  private setTtsRate(v: number): void {
    this.ttsRate = Math.round(v * 10) / 10;
    console.info(`[Reader] TTS rate: ${this.ttsRate.toFixed(1)}x`);
    if (!this.ttsStopped && this.showTts) {
      this.ttsSpeakCurrent();
    }
  }

  private setTtsPitch(v: number): void {
    this.ttsPitch = Math.round(v * 10) / 10;
    console.info(`[Reader] TTS pitch: ${this.ttsPitch.toFixed(1)}x`);
    if (!this.ttsStopped && this.showTts) {
      this.ttsSpeakCurrent();
    }
  }

  // ---- Speed reading RSVP (D2) ----

  private openRsvpPanel(): void {
    this.showTts = false;
    this.ttsStopped = true;
    this.stopTtsPoll();
    this.showRsvp = true;
    this.rsvpPaused = false;
    this.rsvpIndex = 0;
    this.loadRsvpTokens(this.currentPage).then(() => {
      this.updateRsvpDisplay();
      console.info(`[Reader] RSVP panel opened: ${this.rsvpTokens.length} tokens on page ${this.currentPage + 1}`);
    });
  }

  private closeRsvpPanel(): void {
    this.stopRsvpTimer();
    this.rsvpRunning = false;
    this.rsvpPaused = false;
    this.showRsvp = false;
    console.info('[Reader] RSVP panel closed');
  }

  private async loadRsvpTokens(page: number): Promise<void> {
    const handle: ESObject | undefined = this.dataSource.getDocHandle();
    if (handle === undefined) {
      this.rsvpTokens = [];
      return;
    }
    try {
      const raw: string = getText(handle, page, 1.0);
      this.rsvpTokens = TextUtils.splitTokens(TextUtils.normalize(raw));
      this.rsvpIndex = 0;
    } catch (e) {
      this.rsvpTokens = [];
      console.error('[Reader] RSVP getText failed');
    }
  }

  private updateRsvpDisplay(): void {
    const idx: number = Math.min(this.rsvpIndex, this.rsvpTokens.length - 1);
    this.rsvpWord = idx >= 0 ? this.rsvpTokens[idx] : '';
    this.rsvpProgress = `${Math.min(this.rsvpIndex + 1, this.rsvpTokens.length)}/${this.rsvpTokens.length}`;
  }

  private rsvpToggle(): void {
    if (this.rsvpRunning) {
      return;
    }
    this.rsvpPaused = false;
    if (this.rsvpIndex >= this.rsvpTokens.length) {
      this.rsvpIndex = 0;
    }
    if (this.rsvpTokens.length === 0) {
      this.loadRsvpTokens(this.currentPage).then(() => {
        if (this.showRsvp) {
          this.updateRsvpDisplay();
          this.rsvpBegin();
        }
      });
      return;
    }
    this.rsvpBegin();
  }

  private rsvpBegin(): void {
    this.stopRsvpTimer();
    this.rsvpRunning = true;
    this.rsvpPaused = false;
    const delay: number = Math.max(60, Math.round(60000 / this.rsvpWpm));
    this.rsvpTimerId = setInterval(() => {
      this.rsvpTick();
    }, delay);
    console.info(`[Reader] RSVP started: ${this.rsvpWpm} wpm, ${this.rsvpTokens.length} tokens`);
  }

  private rsvpPause(): void {
    if (!this.rsvpRunning) {
      return;
    }
    this.stopRsvpTimer();
    this.rsvpRunning = false;
    this.rsvpPaused = true;
    console.info('[Reader] RSVP paused');
  }

  private rsvpStop(): void {
    this.stopRsvpTimer();
    this.rsvpRunning = false;
    this.rsvpPaused = false;
    this.rsvpIndex = 0;
    this.updateRsvpDisplay();
    console.info('[Reader] RSVP stopped');
  }

  private rsvpTick(): void {
    if (this.rsvpIndex >= this.rsvpTokens.length) {
      this.rsvpAdvancePage();
      return;
    }
    this.updateRsvpDisplay();
    this.rsvpIndex++;
  }

  private rsvpAdvancePage(): void {
    if (this.currentPage >= this.totalPages - 1) {
      this.stopRsvpTimer();
      this.rsvpRunning = false;
      this.rsvpWord = '';
      this.rsvpProgress = '完成';
      console.info('[Reader] RSVP finished (last page)');
      return;
    }
    this.currentPage = this.currentPage + 1;
    this.rsvpAdvancing = true;
    this.onPageChanged(this.currentPage);
    this.rsvpAdvancing = false;
    this.rsvpIndex = 0;
    this.loadRsvpTokens(this.currentPage).then(() => {
      if (this.rsvpRunning) {
        this.updateRsvpDisplay();
        console.info(`[Reader] RSVP continue on page ${this.currentPage + 1}`);
      }
    });
  }

  private stopRsvpTimer(): void {
    if (this.rsvpTimerId >= 0) {
      clearInterval(this.rsvpTimerId);
      this.rsvpTimerId = -1;
    }
  }

  private setRsvpWpm(v: number): void {
    this.rsvpWpm = Math.round(v / 50) * 50;
    if (this.rsvpRunning) {
      this.stopRsvpTimer();
      const delay: number = Math.max(60, Math.round(60000 / this.rsvpWpm));
      this.rsvpTimerId = setInterval(() => {
        this.rsvpTick();
      }, delay);
    }
    console.info(`[Reader] RSVP speed: ${this.rsvpWpm} wpm`);
  }

  /** Toggle fullscreen/immersive mode (sprint I1).
   * Uses UI-level approach: hides top/bottom bars for immersive reading.
   * System-level window API requires @ohos.window which may not bundle correctly. */
  private toggleFullscreen(): void {
    this.isFullscreen = !this.isFullscreen;
    console.info(`[Reader] Fullscreen (UI): ${this.isFullscreen ? 'ON' : 'OFF'}`);
  }

  /** Apply typography settings to reflowable documents (sprint H4/H5). */
  /** Sprint L: unified CSS builder (replaces divergent inline strings). */
  private buildCss(): string {
    const m = Math.round(this.pageMargin);
    const alignNames: string[] = ['left', 'center', 'right', 'justify'];
    const fontFamilies: string[] = ['inherit', 'serif', 'sans-serif', 'monospace'];
    let css = `body { margin: ${m}pt;`;
    css += ` line-height: ${this.lineHeight.toFixed(2)};`;
    css += ` text-align: ${alignNames[this.textAlign]};`;
    css += ` font-family: ${fontFamilies[this.fontFamilyIdx]};`;
    if (this.hyphenationEnabled) {
      css += ` hyphens: auto;`;
    }
    css += ' }';
    css += ` p { margin-top: 0; margin-bottom: ${this.paragraphSpacing}px; text-indent: ${this.textIndent}em; }`;
    if (this.dropCapEnabled) {
      css += ` .dropcap::first-letter, p:first-of-type::first-letter { font-size: 2.5em; float: left; line-height: 0.8; margin-right: 4px; }`;
    }
    return css;
  }

  private applyTypography(): void {
    try {
      const h: ESObject | undefined = this.dataSource.getDocHandle();
      if (h === undefined) return;
      if (!this.isReflow) return;

      const css = this.buildCss();
      console.info(`[Reader] Typography applied: align=${this.textAlign} para=${this.paragraphSpacing} indent=${this.textIndent} dropcap=${this.dropCapEnabled} font=${this.fontFamilyIdx}`);
      layoutDocument(h, REFLOW_W, REFLOW_H, this.fontSize, css);
      /* L2: refresh page count after re-layout */
      const newTotal: number = pageCount(h);
      this.pageList = [];
      for (let i = 0; i < newTotal; i++) {
        this.pageList.push(i);
      }
      this.totalPages = newTotal;
      this.currentPage = Math.min(this.currentPage, newTotal - 1);
      this.reflowVersion++;
    } catch (e) {
      console.error('[Reader] applyTypography failed');
    }
  }

  /** Load text line rectangles for the current page (sprint H2). */
  private loadTextRects(): void {
    try {
      const h: ESObject | undefined = this.dataSource.getDocHandle();
      if (h === undefined) return;
      const json: string = getTextRects(h, this.currentPage);
      this.textRectsJson = json;
      console.info(`[Reader] Text rects loaded for page ${this.currentPage}: ${json.length} chars JSON`);
    } catch (e) {
      this.textRectsJson = '[]';
    }
  }

  /* ---- Phase 9: precise text selection + markup annotations ---- */

  /** Parse the cached textRectsJson into typed lines (text + per-char x bounds). */
  private parseSelLines(): void {
    this.selLines = [];
    try {
      const arr: Array<TextLine> = JSON.parse(this.textRectsJson) as Array<TextLine>;
      for (let i = 0; i < arr.length; i++) {
        const l = arr[i];
        if (l !== null && l !== undefined && l.text !== undefined && l.chars !== undefined
          && l.chars !== null && l.chars.length >= 2) {
          this.selLines.push(l);
        }
      }
    } catch (e) {
      this.selLines = [];
    }
    console.info(`[Reader] Selection lines parsed: ${this.selLines.length}`);
  }

  /** Find the line index whose vertical range contains ny, or the nearest one. */
  private findLineAt(ny: number): number {
    let best = -1;
    let bestDist = 1e9;
    for (let i = 0; i < this.selLines.length; i++) {
      const l = this.selLines[i];
      if (ny >= l.y0 && ny <= l.y1) {
        return i;
      }
      const d = ny < l.y0 ? l.y0 - ny : ny - l.y1;
      if (d < bestDist) {
        bestDist = d;
        best = i;
      }
    }
    return best;
  }

  /** Find the nearest char boundary in line `li` for normalized x = nx. */
  private findCharAt(li: number, nx: number): number {
    const l = this.selLines[li];
    const n = Math.floor(l.chars.length / 2);
    if (n === 0) {
      return 0;
    }
    if (nx <= l.chars[0]) {
      return 0;
    }
    for (let i = 0; i < n; i++) {
      const cx0: number = Number(l.chars[i * 2]);
      const cx1: number = Number(l.chars[i * 2 + 1]);
      if (nx <= cx1) {
        return (nx - cx0) < (cx1 - nx) ? i : i + 1;
      }
    }
    return n;
  }

  /** (Re)compute the selection quads + selected text from start/end anchors. */
  private updateSelPreview(): void {
    this.selPreview = [];
    this.selText = '';
    if (this.selStartLine < 0 || this.selEndLine < 0) {
      return;
    }
    const lo = Math.min(this.selStartLine, this.selEndLine);
    const hi = Math.max(this.selStartLine, this.selEndLine);
    const forward = this.selStartLine < this.selEndLine
      || (this.selStartLine === this.selEndLine && this.selStartChar <= this.selEndChar);
    const cStart = forward ? this.selStartChar : this.selEndChar;
    const cEnd = forward ? this.selEndChar : this.selStartChar;
    for (let li = lo; li <= hi; li++) {
      const l = this.selLines[li];
      if (l === undefined) {
        continue;
      }
      const n = Math.floor(l.chars.length / 2);
      const c0 = li === lo ? Math.max(0, Math.min(cStart, n)) : 0;
      let c1 = li === hi ? Math.max(0, Math.min(cEnd, n)) : n;
      if (c1 <= c0) {
        if (lo === hi && c0 < n) {
          /* collapsed single-point selection still previews one char */
          c1 = c0 + 1;
        } else {
          continue;
        }
      }
      const x0: number = Number(l.chars[c0 * 2]);
      const x1: number = Number(l.chars[(c1 - 1) * 2 + 1]);
      this.selPreview.push({ x0: x0, y0: l.y0, x1: x1, y1: l.y1 });
      this.selText += l.text.substring(c0, c1);
    }
    console.info(`[Reader] Selection: ${this.selText.length} chars, ${this.selPreview.length} quads`);
  }

  /** Set a selection anchor from window-global gesture coordinates. */
  private setSelAnchor(globalX: number, globalY: number, isStart: boolean): void {
    if (this.pageAreaW <= 0 || this.pageAreaH <= 0 || this.selLines.length === 0) {
      return;
    }
    const nx = (globalX - this.pageGlobalX) / this.pageAreaW;
    const ny = (globalY - this.pageGlobalY) / this.pageAreaH;
    const li = this.findLineAt(ny);
    if (li < 0) {
      return;
    }
    const ci = this.findCharAt(li, nx);
    if (isStart) {
      this.selStartLine = li;
      this.selStartChar = ci;
      this.selEndLine = li;
      this.selEndChar = ci;
    } else {
      this.selEndLine = li;
      this.selEndChar = ci;
    }
    this.updateSelPreview();
  }

  private exitSelMode(): void {
    this.selMode = false;
    this.selPreview = [];
    this.selText = '';
    this.selStartLine = -1;
    this.selEndLine = -1;
    this.showSelNoteInput = false;
    this.selNoteInput = '';
  }

  /** Apply a markup annotation (underline/strikeout/squiggly/highlight) to the selection. */
  private async applySelMarkup(type: string): Promise<void> {
    const handle: ESObject | undefined = this.dataSource.getDocHandle();
    if (handle === undefined || this.selPreview.length === 0) {
      return;
    }
    let color = '#fdd835';
    if (type === 'underline') color = '#1e88e5';
    else if (type === 'strikeout') color = '#e53935';
    else if (type === 'squiggly') color = '#8e24aa';
    /* pass a plain local copy — @State proxy arrays are not NAPI arrays */
    const rects: TextRect[] = [];
    for (let i = 0; i < this.selPreview.length; i++) {
      const r = this.selPreview[i];
      rects.push({ x0: r.x0, y0: r.y0, x1: r.x1, y1: r.y1 });
    }
    try {
      addMarkupAnnotation(handle, this.currentPage, rects, type, color);
      await this.refreshAnnotations();
      console.info(`[Reader] ${type} annotation added: ${this.selPreview.length} quads`);
      this.exitSelMode();
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      console.error(`[Reader] addMarkupAnnotation ${type} failed: ${msg}`);
    }
  }

  /** Add a text (sticky) note anchored at the selection start. */
  private async applySelNote(): Promise<void> {
    const handle: ESObject | undefined = this.dataSource.getDocHandle();
    if (handle === undefined || this.selNoteInput.length === 0) {
      return;
    }
    let x = 0.5;
    let y = 0.5;
    if (this.selPreview.length > 0) {
      x = this.selPreview[0].x0;
      y = this.selPreview[0].y0;
    }
    try {
      addTextNote(handle, this.currentPage, x, y, this.selNoteInput, '#43a047');
      await this.refreshAnnotations();
      console.info('[Reader] Text note added');
      this.selNoteInput = '';
      this.showSelNoteInput = false;
      this.exitSelMode();
    } catch (e) {
      console.error('[Reader] addTextNote failed');
    }
  }

  /** Copy the selected text to the system clipboard. */
  private copySelText(): void {
    if (this.selText.length === 0) {
      return;
    }
    try {
      const data = pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, this.selText);
      pasteboard.getSystemPasteboard().setData(data);
      console.info(`[Reader] Copied ${this.selText.length} chars to clipboard`);
      this.exitSelMode();
    } catch (e) {
      console.error('[Reader] clipboard copy failed');
    }
  }

  /** K5: Get display color for annotation type. */
  private annTypeColor(type: string): string {
    if (type === 'highlight') return '#FFD700';
    if (type === 'underline') return '#42A5F5';
    if (type === 'strikeout') return '#EF5350';
    if (type === 'ink') return '#AB47BC';
    if (type === 'text') return '#66BB6A';
    return '#FF9800'; /* unknown */
  }

  /** K5: Get display icon for annotation type. */
  private annTypeIcon(type: string): string {
    if (type === 'highlight') return '\u{1F4DD}';
    if (type === 'underline') return '\u0332';
    if (type === 'strikeout') return '\u2718';
    if (type === 'ink') return '\u270D\uFE0F';
    if (type === 'text') return '\u{1F4AC}';
    return '\u2699';
  }

  /** K1: Share the current book file via system share sheet. */
  private async shareBook(): Promise<void> {
    try {
      const ctx = getContext(this);
      /* Use want to launch system share - simplified: log + clipboard fallback */
      const fileName = this.docTitle;
      console.info(`[Reader] Share: ${fileName} (${this.documentPath})`);
      /* In production, use @ohos.app.ability.wantAgent or systemShare API */
      /* For now, show a confirmation via hilog (system share requires additional setup) */
    } catch (e) {
      console.error('[Reader] Share failed: ' + JSON.stringify(e));
    }
  }

  /** Execute configurable tap zone action (sprint I4). */
  private onTapZoneAction(action: number): void {
    if (action === 0) {
      /* prev page / next page / menu (default) */
      if (this.currentPage > 0) {
        this.currentPage = this.currentPage - 1;
        this.onPageChanged(this.currentPage);
      } else if (this.totalPages > 0 && this.currentPage < this.totalPages - 1) {
        this.currentPage = this.currentPage + 1;
        this.onPageChanged(this.currentPage);
      } else {
        this.showMenu = !this.showMenu;
      }
    } else if (action === 1) {
      /* menu / fullscreen */
      if (this.tapCenterAction === 1) {
        this.toggleFullscreen();
      } else {
        this.showMenu = !this.showMenu;
      }
    } else if (action === 2) {
      /* TOC / brightness */
      if (this.tapCenterAction === 2) {
        this.brightnessLevel = this.brightnessLevel > 200 ? 64 : this.brightnessLevel + 32;
        this.setBrightness(this.brightnessLevel);
      } else {
        this.showToc = true;
      }
    } else if (action === 3) {
      /* bookmark */
      this.toggleBookmark();
    }
    console.info(`[Reader] Tap zone action: ${action}`);
  }

  /** Open file info dialog (sprint G6): show title/author/pages/format/size. */
  private openFileInfo(): void {
    try {
      const parts: string[] = [];
      parts.push(`文件名: ${this.docTitle}`);

      /* Try to get document info via NAPI (handle lives in dataSource) */
      const h: ESObject | undefined = this.dataSource.getDocHandle();
      if (h !== undefined) {
        try {
          const info: DocumentInfo = getDocumentInfo(h);
          if (info.title && info.title.length > 0) {
            parts.push(`标题: ${info.title}`);
          }
          if (info.author && info.author.length > 0) {
            parts.push(`作者: ${info.author}`);
          }
          if (info.producer && info.producer.length > 0) {
            parts.push(`生成器: ${info.producer}`);
          }
        } catch (_e) {}
      }

      parts.push(`页数: ${this.totalPages > 0 ? this.totalPages + ' 页' : '未知'}`);
      parts.push(`当前页: p${this.currentPage + 1}`);

      /* File size */
      try {
        const stat = fs.statSync(this.documentPath);
        const sizeKB = Math.round(stat.size / 1024);
        parts.push(`大小: ${sizeKB} KB`);
      } catch (_e) {}

      /* Format (from extension) */
      const dotIdx = this.documentPath.lastIndexOf('.');
      const ext = dotIdx >= 0 ? this.documentPath.substring(dotIdx + 1).toUpperCase() : 'UNKNOWN';
      parts.push(`格式: ${ext}`);

      this.fileInfoText = parts.join('\n');
      console.info(`[Reader] File info generated for ${this.docTitle}`);
    } catch (e) {
      this.fileInfoText = '获取信息失败';
    }
  }

  /* ---- Phase 10: AI 翻译 + AI 笔记 (hr17/18 parity) ---- */

  /** Load AI notes for this book (shown in the bookmark panel). */
  private loadAiNotes(): void {
    if (this.documentPath.length === 0) {
      this.aiNotes = [];
      return;
    }
    getNotes(getContext(this), this.documentPath).then((n: AiNote[]) => {
      this.aiNotes = n;
    });
  }

  /** Export bookmarks + AI notes of this book to TXT/Markdown (A). */
  private doExportNotes(markdown: boolean): void {
    const data: ExportData = {
      docTitle: this.docTitle,
      bookmarks: this.bookmarks,
      notes: this.aiNotes
    };
    exportNotesFile(getContext(this), data, markdown).then((msg: string) => {
      if (msg.length > 0) {
        promptAction.showToast({ message: msg });
        this.showExportDialog = false;
        this.showBookmarks = false;
      }
    });
  }

  private openTransDialog(): void {
    loadAiConfig(getContext(this)).then((c: AiConfig) => {
      this.aiCfg = c;
    });
    this.transResult = '';
    this.transBusy = false;
    this.showTrans = true;
    console.info('[Reader] AI translate dialog opened');
  }

  /** In-page bilingual is only possible for reflowable EPUB/TXT/HTML books. */
  private biSupported(): boolean {
    return isBilingualDoc(this.documentPath);
  }

  /** Swap the open document in place (keep page, rebuild page list). */
  private async swapDoc(newPath: string): Promise<void> {
    try {
      this.dataSource.openDoc(newPath);
    } catch (e) {
      console.error('[Reader] swapDoc open failed: ' + String(e));
      promptAction.showToast({ message: '\u53CC\u8BED\u7248\u6253\u5F00\u5931\u8D25\uFF0C\u5DF2\u4FDD\u7559\u539F\u6587' });
      return;
    }
    const handle: ESObject | undefined = this.dataSource.getDocHandle();
    try {
      this.isReflow = false;
      if (handle !== undefined) {
        try {
          this.isReflow = isReflowable(handle);
        } catch (e) {
          this.isReflow = false;
        }
        if (this.isReflow) {
          try {
            const css = `body{margin:${this.pageMargin}pt;line-height:${this.lineHeight.toFixed(2)}}`;
            layoutDocument(handle, REFLOW_W, REFLOW_H, this.fontSize, css);
          } catch (e) {
            console.error('[Reader] swapDoc reflow layout failed');
          }
        }
      }
      this.pageList = [];
      this.totalPages = this.dataSource.getPageCount();
      for (let i = 0; i < this.totalPages; i++) {
        this.pageList.push(i);
      }
      this.reflowVersion++;
      this.currentPage = Math.min(this.currentPage, this.totalPages - 1);
      this.onPageChanged(this.currentPage);
      this.loadToc();
      this.loadBookmarks();
    } catch (e) {
      console.error('[Reader] swapDoc rebuild failed: ' + String(e));
    }
    console.info(`[Reader] Document swapped to ${newPath}`);
  }

  /** Whole-page text for translation (falls back to the selection first). */
  private pageTextForTrans(): string {
    this.loadTextRects();
    this.parseSelLines();
    let t = '';
    for (let i = 0; i < this.selLines.length; i++) {
      t += this.selLines[i].text + '\n';
    }
    return t.trim();
  }

  private startTranslate(): void {
    if (this.transBusy) {
      return;
    }
    if (!aiConfigReady(this.aiCfg)) {
      promptAction.showToast({ message: '\u8BF7\u5148\u5728\u504F\u597D\u4E2D\u914D\u7F6E AI \u5927\u6A21\u578B' });
      return;
    }
    const dst: string = TRANS_DST[this.transDstIdx];
    /* D: in-page bilingual mode (EPUB/TXT rebuild pipeline) */
    if (this.transInPage && this.biSupported()) {
      if (this.biOriginalPath.length > 0) {
        /* currently reading the bilingual copy → restore the original */
        this.transBusy = true;
        this.transResult = '\u6B63\u5728\u6062\u590D\u539F\u6587\u2026';
        const orig = this.biOriginalPath;
        this.swapDoc(orig).then(() => {
          this.biOriginalPath = '';
          this.transBusy = false;
          this.transResult = '';
          this.showTrans = false;
          promptAction.showToast({ message: '\u5DF2\u6062\u590D\u539F\u6587' });
        });
        return;
      }
      this.transBusy = true;
      this.transResult = '\u51C6\u5907\u4E2D\u2026';
      const srcPath = this.documentPath;
      buildBilingual(getContext(this), this.aiCfg, srcPath, dst, (msg: string) => {
        this.transResult = msg;
      }).then((outPath: string) => {
        this.transBusy = false;
        if (outPath.length === 0) {
          /* failure message already in transResult; make it visible */
          promptAction.showToast({ message: this.transResult.substring(0, 60) });
          return;
        }
        this.biOriginalPath = srcPath;
        this.swapDoc(outPath).then(() => {
          this.showTrans = false;
          promptAction.showToast({ message: '\u53CC\u8BED\u7248\u5DF2\u751F\u6210\uFF0C\u8BD1\u6587\u4EE5\u84DD\u8272\u663E\u793A' });
        }).catch((e: Object) => {
          this.transBusy = false;
          promptAction.showToast({ message: '\u53CC\u8BED\u7248\u5E94\u7528\u5931\u8D25\uFF1A' + String(e).substring(0, 40) });
        });
      });
      return;
    }
    const text = this.selText.length > 0 ? this.selText : this.pageTextForTrans();
    if (text.length === 0) {
      promptAction.showToast({ message: '\u5F53\u524D\u9875\u65E0\u53EF\u63D0\u53D6\u6587\u672C' });
      return;
    }
    this.transBusy = true;
    this.transResult = '';
    const src: string = TRANS_SRC[this.transSrcIdx];
    const from = src === TRANS_SRC[0] ? '' : '\u4ECE' + src;
    const msgs: AiMessage[] = [
      { role: 'system', content: '\u4F60\u662F\u4E13\u4E1A\u7684\u7FFB\u8BD1\uFF0C\u53EA\u8F93\u51FA\u8BD1\u6587\uFF0C\u4E0D\u8981\u89E3\u91CA\u3002' },
      { role: 'user', content: `\u8BF7\u628A\u4E0B\u9762\u7684\u6587\u672C${from}\u7FFB\u8BD1\u6210${dst}\uFF0C\u53EA\u8F93\u51FA\u8BD1\u6587\uFF1A\n\n` + text }
    ];
    chatCompletion(this.aiCfg, msgs, 0).then((out: string) => {
      this.transResult = out;
      this.transBusy = false;
      this.showTransPanel = true;
      this.showTrans = false;
    }).catch((e: Object) => {
      this.transResult = '\u7FFB\u8BD1\u5931\u8D25\uFF1A' + (e instanceof Error ? e.message : String(e));
      this.transBusy = false;
      this.showTransPanel = true;
      this.showTrans = false;
    });
  }

  private saveTransNote(): void {
    if (this.transResult.length === 0 || this.transResult.startsWith('\u7FFB\u8BD1\u5931\u8D25')) {
      return;
    }
    const n: AiNote = {
      path: this.documentPath,
      title: this.docTitle,
      page: this.currentPage,
      date: new Date().toISOString(),
      kind: 'AI\u7FFB\u8BD1',
      text: this.transResult
    };
    addNote(getContext(this), n).then((ok: boolean) => {
      promptAction.showToast({ message: ok ? '\u8BD1\u6587\u5DF2\u4FDD\u5B58\u5230\u7B14\u8BB0' : '\u4FDD\u5B58\u5931\u8D25' });
      if (ok) {
        this.loadAiNotes();
      }
    });
  }

  private toSelectOptions(list: string[]): SelectOption[] {
    const arr: SelectOption[] = [];
    for (let i = 0; i < list.length; i++) {
      arr.push({ value: list[i] });
    }
    return arr;
  }

  /** Round 5: page thumbnail grid (3x3 window, tap to jump). */
  @Builder
  buildThumbDialog() {
    Stack({ alignContent: Alignment.Center }) {
      Column()
        .width('100%').height('100%')
        .backgroundColor('#66000000')
        .onClick(() => {
          this.showThumbs = false;
        })

      Column({ space: 10 }) {
        Row() {
          Text($r('app.string.thumb_title'))
            .fontSize(15).fontWeight(FontWeight.Bold).fontColor('#333')
            .layoutWeight(1)
          Image($r('app.media.ic_close'))
            .width(20).height(20).fillColor('#888')
            .onClick(() => {
              this.showThumbs = false;
            })
        }
        .width('100%')

        if (this.thumbBusy && this.thumbMaps.length === 0) {
          Row({ space: 8 }) {
            Progress({ value: 40, total: 100 }).width(28).height(28)
            Text($r('app.string.thumb_rendering')).fontSize(13).fontColor('#666')
          }
          .justifyContent(FlexAlign.Center)
          .width('100%').height(200)
        } else if (this.thumbMaps.length === 0) {
          Text($r('app.string.thumb_empty')).fontSize(13).fontColor(Color.Gray)
            .width('100%').height(120).textAlign(TextAlign.Center)
        } else {
          Grid() {
            ForEach(this.thumbMaps, (pm: image.PixelMap, idx: number) => {
              GridItem() {
                Column({ space: 3 }) {
                  Image(pm)
                    .width('100%').height(110)
                    .objectFit(ImageFit.Contain)
                    .backgroundColor('#f5f5f5')
                    .borderRadius(4)
                  Text(`${this.thumbStart + idx + 1}`)
                    .fontSize(11).fontColor('#555')
                }
                .onClick(() => {
                  const target: number = this.thumbStart + idx;
                  this.pushPageHistory();
                  this.currentPage = target;
                  this.onPageChanged(target);
                  this.showThumbs = false;
                  console.info(`[Reader] Thumbnail jump to page ${target + 1}`);
                })
              }
            }, (pm: image.PixelMap, idx: number) => `th_${this.thumbStart}_${idx}`)
          }
          .columnsTemplate('1fr 1fr 1fr')
          .rowsGap(8)
          .columnsGap(8)
          .width('100%')
          .height(360)
        }

        Row({ space: 12 }) {
          Button($r('app.string.thumb_prev'))
            .fontSize(12).height(32).layoutWeight(1)
            .backgroundColor('#e0e0e0').fontColor('#333')
            .onClick(() => {
              this.shiftThumbs(-1);
            })
          Button($r('app.string.thumb_next'))
            .fontSize(12).height(32).layoutWeight(1)
            .backgroundColor('#e0e0e0').fontColor('#333')
            .onClick(() => {
              this.shiftThumbs(1);
            })
        }
        .width('100%')
      }
      .width('86%')
      .padding(14)
      .backgroundColor(Color.White)
      .borderRadius(10)
    }
    .width('100%').height('100%')
  }

  /** Round 5: page thumbnail grid dialog end. */

  /** hr18: AI 翻译 dialog (panel-mode bilingual note + save checkbox). */
  @Builder
  buildTransDialog() {
    Stack({ alignContent: Alignment.Center }) {
      Column()
        .width('100%').height('100%')
        .backgroundColor('#66000000')
        .onClick(() => {
          this.showTrans = false;
        })
      Column({ space: 12 }) {
        Row() {
          Text('AI\u7FFB\u8BD1')
            .fontSize(16).fontWeight(FontWeight.Bold).fontColor('#333')
            .layoutWeight(1)
          Image($r('app.media.ic_close'))
            .width(20).height(20).fillColor('#888')
            .onClick(() => {
              this.showTrans = false;
            })
        }
        .width('100%')

        Row({ space: 8 }) {
          Text('\u6E90\u8BED\u8A00').fontSize(14).fontColor('#333').width(70)
          Select(this.toSelectOptions(TRANS_SRC))
            .selected(this.transSrcIdx)
            .value(TRANS_SRC[this.transSrcIdx])
            .font({ size: 14 })
            .width(140)
            .onSelect((idx: number) => {
              this.transSrcIdx = idx;
            })
        }
        .width('100%')

        Row({ space: 8 }) {
          Text('\u76EE\u6807\u8BED\u8A00').fontSize(14).fontColor('#333').width(70)
          Select(this.toSelectOptions(TRANS_DST))
            .selected(this.transDstIdx)
            .value(TRANS_DST[this.transDstIdx])
            .font({ size: 14 })
            .width(140)
            .onSelect((idx: number) => {
              this.transDstIdx = idx;
            })
        }
        .width('100%')

        Row({ space: 6 }) {
          Checkbox({ name: 'transBilingual' })
            .width(18).height(18)
            .selectedColor($r('app.color.brand_primary'))
            .select(this.transInPage && this.biSupported())
            .enabled(this.biSupported())
            .onChange((v: boolean) => {
              this.transInPage = v;
            })
          Text('\u5728\u9875\u9762\u5185\u663E\u793A\u8BD1\u6587\uFF08\u53CC\u8BED\u5BF9\u7167\uFF09')
            .fontSize(13).fontColor(this.biSupported() ? '#333' : '#999')
          if (this.biSupported()) {
            Text('\u672C\u4E66\u53EF\u7528')
              .fontSize(11).fontColor('#00897B')
              .border({ width: 1, color: '#00897B' })
              .borderRadius(8)
              .padding({ left: 6, right: 6, top: 1, bottom: 1 })
          } else {
            Text('\u9762\u677F\u6A21\u5F0F')
              .fontSize(11).fontColor($r('app.color.brand_primary'))
              .border({ width: 1, color: $r('app.color.brand_primary') })
              .borderRadius(8)
              .padding({ left: 6, right: 6, top: 1, bottom: 1 })
          }
        }
        .width('100%')

        Row({ space: 6 }) {
          Checkbox({ name: 'transSave' })
            .width(18).height(18)
            .selectedColor($r('app.color.brand_primary'))
            .select(this.transSave)
            .onChange((v: boolean) => {
              this.transSave = v;
            })
          Text('AI\u7FFB\u8BD1\u7ED3\u679C\u4FDD\u5B58').fontSize(13).fontColor('#333')
        }
        .width('100%')

        if (this.transBusy && this.transResult.length > 0) {
          Text(this.transResult)
            .fontSize(12).fontColor($r('app.color.brand_primary'))
            .width('100%').maxLines(2)
        }

        Row({ space: 12 }) {
          Blank()
          Button('\u53D6\u6D88')
            .fontSize(14).height(36)
            .backgroundColor('#e0e0e0').fontColor('#333')
            .onClick(() => {
              this.showTrans = false;
            })
          Button('\u5F00\u59CB\u7FFB\u8BD1')
            .fontSize(14).height(36)
            .backgroundColor($r('app.color.brand_primary')).fontColor(Color.White)
            .onClick(() => {
              this.startTranslate();
            })
        }
        .width('100%')
      }
      .width('82%')
      .padding(14)
      .backgroundColor(Color.White)
      .borderRadius(8)
    }
    .width('100%').height('100%')
  }

  /** Translation result bottom sheet (panel mode). */
  @Builder
  buildTransPanel() {
    Stack({ alignContent: Alignment.Bottom }) {
      Column()
        .width('100%').height('100%')
        .backgroundColor('#66000000')
        .onClick(() => {
          this.showTransPanel = false;
        })
      Column({ space: 10 }) {
        Row() {
          Text('AI \u7FFB\u8BD1\u7ED3\u679C')
            .fontSize(15).fontWeight(FontWeight.Bold).fontColor('#333')
            .layoutWeight(1)
          Image($r('app.media.ic_close'))
            .width(20).height(20).fillColor('#888')
            .onClick(() => {
              this.showTransPanel = false;
            })
        }
        .width('100%')

        Scroll() {
          Text(this.transResult)
            .fontSize(14).fontColor('#333')
            .width('100%')
        }
        .width('100%')
        .height(260)
        .backgroundColor('#f5f5f5')
        .borderRadius(4)
        .padding(8)

        Row({ space: 12 }) {
          Blank()
          Button('\u5173\u95ED')
            .fontSize(14).height(36)
            .backgroundColor('#e0e0e0').fontColor('#333')
            .onClick(() => {
              this.showTransPanel = false;
            })
          Button('\u4FDD\u5B58\u5230\u7B14\u8BB0')
            .fontSize(14).height(36)
            .backgroundColor('#00897B').fontColor(Color.White)
            .onClick(() => {
              this.saveTransNote();
            })
        }
        .width('100%')
      }
      .width('100%')
      .padding(14)
      .backgroundColor(Color.White)
      .borderRadius({ topLeft: 10, topRight: 10 })
    }
    .width('100%').height('100%')
  }

  /** One row of the selection dialog menu (Android hr12). */
  @Builder
  selMenuRow(icon: Resource, label: string, action: number) {
    Row({ space: 16 }) {
      Image(icon)
        .width(22).height(22).fillColor('#00897B')
      Text(label)
        .fontSize(16).fontColor('#00897B')
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 11, bottom: 11 })
    .onClick(() => {
      if (action === 1) {
        this.toggleBookmark();
        this.exitSelMode();
      } else if (action === 2) {
        this.copySelText();
        this.exitSelMode();
      } else if (action === 3) {
        this.searchQuery = this.selText;
        this.openSearch();
        this.doSearch();
        this.exitSelMode();
      } else if (action === 4) {
        this.showSelNoteInput = true;
      } else if (action === 5) {
        /* 分享 (system share sheet; falls back to clipboard) */
        this.shareSelText();
        this.exitSelMode();
      } else if (action === 6) {
        /* 发送给AI (Android hr13): jump to the dedicated AI chat page */
        this.exitSelMode();
        router.pushUrl({
          url: 'pages/AiChat',
          params: {
            docPath: this.documentPath,
            title: this.docTitle,
            page: '' + this.currentPage,
            selText: this.selText
          }
        });
        console.info('[Reader] Sent selection to AiChat page');
      } else if (action === 7) {
        /* 网络搜索(谷歌等): open the browser */
        this.openWebSearch(this.selText);
        this.exitSelMode();
      } else if (action === 8) {
        /* 网络词典(牛津词典等): open youdao in the browser */
        this.openWebDictionary(this.selText);
        this.exitSelMode();
      }
    })
  }

  /** Open a URL in the system browser (API 12 openLink). */
  private openExternal(url: string): void {
    try {
      (getContext(this) as common.UIAbilityContext).openLink(url);
      console.info('[Reader] openLink: ' + url);
    } catch (e) {
      promptAction.showToast({ message: '\u65E0\u6CD5\u6253\u5F00\u6D4F\u89C8\u5668' });
      console.error('[Reader] openLink failed');
    }
  }

  private openWebSearch(text: string): void {
    if (text.trim().length === 0) {
      promptAction.showToast({ message: '\u672A\u9009\u4E2D\u6587\u672C' });
      return;
    }
    this.openExternal('https://www.google.com/search?q=' + encodeURIComponent(text));
  }

  private openWebDictionary(text: string): void {
    if (text.trim().length === 0) {
      promptAction.showToast({ message: '\u672A\u9009\u4E2D\u6587\u672C' });
      return;
    }
    this.openExternal('https://dict.youdao.com/result?word=' + encodeURIComponent(text) + '&lang=en');
  }

  /** System share sheet for the selected text; clipboard fallback. */
  private shareSelText(): void {
    if (this.selText.trim().length === 0) {
      promptAction.showToast({ message: '\u672A\u9009\u4E2D\u6587\u672C' });
      return;
    }
    try {
      const rec: systemShare.SharedRecord = {
        utd: 'general.text',
        content: this.selText,
        title: this.docTitle
      };
      const data = new systemShare.SharedData(rec);
      const controller = new systemShare.ShareController(data);
      controller.show(getContext(this) as common.UIAbilityContext, {})
        .catch(() => {
          this.copySelText();
          promptAction.showToast({ message: '\u5DF2\u590D\u5236\u5230\u526A\u8D34\u677F\uFF08\u7CFB\u7EDF\u5206\u4EAB\u4E0D\u53EF\u7528\uFF09' });
        });
    } catch (e) {
      this.copySelText();
      promptAction.showToast({ message: '\u5DF2\u590D\u5236\u5230\u526A\u8D34\u677F\uFF08\u7CFB\u7EDF\u5206\u4EAB\u4E0D\u53EF\u7528\uFF09' });
    }
  }

  build() {
    Stack({ alignContent: Alignment.TopStart }) {
      /* Main content area */
      Column() {
        // Top bar - single row (Android hr10/11): title + TOC/brightness/fullscreen/AI/settings/close
        Row() {
          Column({ space: 1 }) {
            Text(this.docTitle.length > 16 ? this.docTitle.substring(0, 16) + '…' : (this.docTitle.length > 0 ? this.docTitle : ''))
              .fontSize(14).fontColor(Color.White).maxLines(1).fontWeight(FontWeight.Medium)
            Text(this.totalPages > 0 ? `${this.currentPage + 1}/${this.totalPages}` : '...')
              .fontSize(10).fontColor('#c5cae9')
          }
          .alignItems(HorizontalAlign.Start)
          .layoutWeight(1)

          if (this.showTopBarInfo) {
            Row({ space: 4 }) {
              Image($r('app.media.ic_clock'))
                .width(14).height(14).fillColor('#c5cae9')
              Text(this.topClock)
                .fontSize(11).fontColor('#c5cae9')
              Image($r('app.media.ic_battery'))
                .width(14).height(14).fillColor('#c5cae9').margin({ left: 8 })
              Text(this.topBattery)
                .fontSize(11).fontColor('#c5cae9')
            }
            .margin({ right: 12 })
          }

          Image($r('app.media.ic_toc'))
            .width(22).height(22).fillColor(Color.White).margin({ right: 18 })
            .onClick(() => {
              this.showToc = true;
              console.info('[Reader] TOC opened (top bar)');
            })
          Image($r('app.media.ic_brightness'))
            .width(22).height(22).fillColor(Color.White).margin({ right: 18 })
            .onClick(() => {
              this.brightnessLevel = this.brightnessLevel > 200 ? 64 : this.brightnessLevel + 32;
              this.setBrightness(this.brightnessLevel);
            })
          Image($r('app.media.ic_fullscreen'))
            .width(22).height(22).fillColor(Color.White).margin({ right: 18 })
            .onClick(() => {
              this.toggleFullscreen();
            })
          Image($r('app.media.ic_ai'))
            .width(22).height(22).fillColor(Color.White).margin({ right: 18 })
            .onClick(() => {
              this.openTransDialog();
            })
          Image($r('app.media.ic_settings'))
            .width(22).height(22).fillColor(Color.White).margin({ right: 18 })
            .onClick(() => {
              this.showMenu = !this.showMenu;
              console.info(`[Reader] Menu toggled (top bar): ${this.showMenu}`);
            })
          Image($r('app.media.ic_close'))
            .width(22).height(22).fillColor(Color.White)
            .onClick(() => {
              router.back();
              console.info('[Reader] Close reader');
            })
        }
        .width('100%').height(46)
        .padding({ left: 12, right: 12 })
        .backgroundColor(this.barBg())

        // Search bar (visible when opened)
        if (this.showSearch) {
          Row({ space: 8 }) {
            TextInput({ placeholder: '搜索...', text: this.searchQuery })
              .layoutWeight(1)
              .height(36)
              .fontSize(14)
              .backgroundColor('#fff')
              .borderRadius(4)
              .onChange((value: string) => {
                this.searchQuery = value;
              })
            Button('搜索')
              .fontSize(13).height(36)
              .backgroundColor('#2a7').fontColor(Color.White)
              .onClick(() => {
                this.doSearch();
              })
            Button('▾')
              .fontSize(13).height(36).width(40)
              .backgroundColor('#e0e0e0').fontColor('#333')
              .onClick(() => this.nextHitPage())
            Button('✕')
              .fontSize(13).height(36).width(40)
              .backgroundColor('#e0e0e0').fontColor('#333')
              .onClick(() => this.closeSearch())
          }
          .width('96%')
          .padding({ top: 6, bottom: 6 })
          .backgroundColor(this.nightMode ? '#222' : '#f0f0f0')
        }

        // Main reading area
        if (this.scrollMode === 0) {
          /* Horizontal Swiper mode */
          Stack() {
            Swiper() {
              ForEach(this.pageList, (pageIdx: number) => {
                if (this.pageMode === 1) {
                  /* double-page: each swiper page shows a pair */
                  DoublePageRenderer({ pageIndex: pageIdx, dataSource: this.dataSource })
                } else if (this.pageSplit) {
                  /* Round 5: page split — one wide page shown as two halves side by side */
                  Row() {
                    PageRenderer({
                      pageIndex: pageIdx,
                      dataSource: this.dataSource,
                      cropRect: SPLIT_LEFT,
                      contrastLevel: this.contrastLevel
                    }).layoutWeight(1)
                    PageRenderer({
                      pageIndex: pageIdx,
                      dataSource: this.dataSource,
                      cropRect: SPLIT_RIGHT,
                      contrastLevel: this.contrastLevel
                    }).layoutWeight(1)
                  }
                  .width('100%').height('100%')
                } else {
                  PageRenderer({
                    pageIndex: pageIdx,
                    dataSource: this.dataSource,
                    searchHits: pageIdx === this.searchHitPage ? this.searchHits : [],
                    annotations: pageIdx === this.currentPage ? this.annotations : [],
                    selPreview: pageIdx === this.currentPage ? this.selPreview : [],
                    contrastLevel: this.contrastLevel
                  })
                }
              }, (item: number) => `${item}_${this.zoomLevel}_${this.nightMode}_${this.pageMode}_${this.reflowVersion}_${this.pageSplit}_${this.contrastLevel}`)
            }
            .index(this.rtlEnabled ? (this.totalPages - 1 - this.currentPage) : this.currentPage)
            .width('100%').height('100%')
            .loop(false).indicator(false).autoPlay(false)
            .cachedCount(1)
            .onChange((index: number) => {
              this.onPageChanged(index);
            })

            /* Pinch gesture for zoom */
            Column()
              .width('100%').height('100%')
              .gesture(
                GestureGroup(GestureMode.Parallel,
                  PinchGesture({ fingers: 2 })
                    .onActionEnd((event: PinchGestureEvent) => {
                      if (event.scale > 1.05) {
                        this.zoomIn();
                      } else if (event.scale < 0.95) {
                        this.zoomOut();
                      }
                    }),
                  TapGesture({ count: 1 })
                    .onAction((event: TapGestureEvent) => {
                      /* Handled by tap zones below */
                    })
                )
              )

            // Left tap zone (configurable action, sprint I4)
            Row()
              .width(`${LEFT_ZONE_RATIO * 100}%`).height('100%')
              .backgroundColor(Color.Transparent)
              .onClick(() => {
                this.onTapZoneAction(this.tapLeftAction);
              })

            // Right tap zone (configurable action, sprint I4)
            Row()
              .width(`${RIGHT_ZONE_RATIO * 100}%`).height('100%')
              .align(Alignment.End)
              .backgroundColor(Color.Transparent)
              .onClick(() => {
                this.onTapZoneAction(this.tapRightAction);
              })

            // Center tap zone (toggle menu) + long-press for text selection (sprint H2)
            Row()
              .width(`${(1 - LEFT_ZONE_RATIO - RIGHT_ZONE_RATIO) * 100}%`).height('100%')
              .align(Alignment.Center)
              .backgroundColor(Color.Transparent)
              .onClick(() => {
                this.onTapZoneAction(this.tapCenterAction);
              })
              .gesture(
                LongPressGesture({ repeat: false, duration: 600 })
                  .onAction((event: LongPressGestureEvent) => {
                    this.loadTextRects();
                    this.parseSelLines();
                    this.selMode = true;
                    if (event !== undefined && event.fingerList.length > 0) {
                      this.setSelAnchor(event.fingerList[0].globalX, event.fingerList[0].globalY, true);
                    }
                    console.info(`[Reader] Text selection activated on page ${this.currentPage}`);
                  })
              )

            // Phase 9: while in selection mode, this full-page catcher converts
            // taps into the selection end anchor (long-press restarts the anchor)
            if (this.selMode) {
              Column()
                .width('100%').height('100%')
                .backgroundColor(Color.Transparent)
                .gesture(
                  GestureGroup(GestureMode.Exclusive,
                    LongPressGesture({ repeat: false, duration: 600 })
                      .onAction((event: LongPressGestureEvent) => {
                        if (event !== undefined && event.fingerList.length > 0) {
                          this.setSelAnchor(event.fingerList[0].globalX, event.fingerList[0].globalY, true);
                        }
                      }),
                    TapGesture({ count: 1 })
                      .onAction((event: TapGestureEvent) => {
                        const fl = event !== undefined ? event.fingerList : [];
                        if (fl.length > 0) {
                          this.setSelAnchor(fl[0].globalX, fl[0].globalY, false);
                        }
                      })
                  )
                )
            }
          }
          .width('100%').layoutWeight(1)
          .onAreaChange((oldArea: Area, newArea: Area) => {
            this.pageAreaW = typeof newArea.width === 'number' ? newArea.width : 0;
            this.pageAreaH = typeof newArea.height === 'number' ? newArea.height : 0;
            const gp = newArea.globalPosition;
            this.pageGlobalX = typeof gp.x === 'number' ? gp.x : 0;
            this.pageGlobalY = typeof gp.y === 'number' ? gp.y : 0;
          })
        } else {
          /* Vertical continuous scroll mode */
          List({ scroller: this.vScroller }) {
            ForEach(this.pageList, (pageIdx: number) => {
              ListItem() {
                PageRenderer({ pageIndex: pageIdx, dataSource: this.dataSource, contrastLevel: this.contrastLevel })
              }
              .width('100%')
              .height('100vh')
            }, (item: number) => `v_${item}_${this.zoomLevel}_${this.nightMode}_${this.reflowVersion}_${this.contrastLevel}`)
          }
          .width('100%').layoutWeight(1)
          .cachedCount(1)
          .scrollBar(BarState.Auto)
          .edgeEffect(EdgeEffect.Spring)
          .onScrollIndex((index: number) => {
            if (index !== this.currentPage) {
              this.onPageChanged(index);
            }
          })
          .onReachEnd(() => {
            if (this.autoScroll) {
              this.autoScroll = false;
              this.stopAutoScroll();
            }
          })
          .onTouch(() => {
            /* any manual touch stops the auto-scroll */
            if (this.autoScroll) {
              this.autoScroll = false;
              this.stopAutoScroll();
            }
          })
        }

        // Bottom toolbar (Android hr10/11: brand blue bar, white icons)
        Row() {
          Image($r('app.media.ic_search'))
            .width(22).height(22).fillColor(Color.White).margin({ right: 22 })
            .onClick(() => this.openSearch())

          Image($r('app.media.ic_edit'))
            .width(22).height(22).fillColor(Color.White).margin({ right: 22 })
            .onClick(() => {
              this.loadTextRects();
              this.parseSelLines();
              this.selMode = true;
              console.info(`[Reader] Selection mode (bottom bar) on page ${this.currentPage}`);
            })

          Image($r('app.media.ic_bookmark'))
            .width(22).height(22).fillColor(Color.White).margin({ right: 22 })
            .onClick(() => {
              this.toggleBookmark();
            })

          Image($r('app.media.ic_list'))
            .width(22).height(22).fillColor(Color.White).margin({ right: 22 })
            .onClick(() => {
              this.loadBookmarks().then(() => {
                this.showBookmarks = true;
                console.info('[Reader] Bookmark panel opened (bottom bar)');
              });
            })

          Blank()

          Image($r('app.media.ic_lock'))
            .width(22).height(22).fillColor(Color.White).margin({ right: 22 })
            .onClick(() => {
              this.toggleFullscreen();
              console.info('[Reader] Lock/fullscreen toggled (bottom bar)');
            })

          Image($r('app.media.ic_more_vert'))
            .width(22).height(22).fillColor(Color.White)
            .onClick(() => {
              this.showMenu = !this.showMenu;
              console.info(`[Reader] Menu toggled (bottom bar): ${this.showMenu}`);
            })
        }
        .width('100%').height(44)
        .padding({ left: 16, right: 16 })
        .backgroundColor(this.barBg())

        /* Page seek slider row (Android hr10: page number left, total right, white on blue) */
        Row({ space: 10 }) {
          Text(this.totalPages > 0 ? '' + (this.currentPage + 1) : '0')
            .fontSize(12).fontColor(Color.White).width(36).textAlign(TextAlign.Center)
          Slider({ value: this.currentPage, min: 0, max: Math.max(this.totalPages - 1, 1), step: 1 })
            .layoutWeight(1).height(24)
            .selectedColor(Color.White)
            .trackColor('#5c6bc0')
            .blockColor(Color.White)
            .onChange((value: number, mode: SliderChangeMode) => {
              if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
                const target: number = Math.max(0, Math.min(Math.round(value), this.totalPages - 1));
                if (target !== this.currentPage) {
                  this.pushPageHistory();
                  this.currentPage = target;
                  this.onPageChanged(target);
                  console.info(`[Reader] Seek to page ${target + 1}`);
                }
              }
            })
          Text(this.totalPages > 0 ? '' + this.totalPages : '0')
            .fontSize(12).fontColor(Color.White).width(36).textAlign(TextAlign.Center)
        }
        .width('100%')
        .padding({ left: 8, right: 8, top: 2, bottom: 6 })
        .backgroundColor(this.barBg())
      }
      .width('100%').height('100%')
      .backgroundColor(this.chromeBg())

      /* I5: Blue light filter overlay (warm color, touches pass through) */
      if (this.blueLightFilter > 0) {
        Column()
          .width('100%').height('100%')
          .backgroundColor('#FF8C00')
          .opacity(this.blueLightFilter / 300.0) /* max ~33% opacity at 100 */
          .hitTestBehavior(HitTestMode.None)
      }

      /* I6: Custom background image (behind content, touches pass through) */
      if (this.bgImagePath.length > 0 && this.bgImageAlpha > 0 && this.bgImagePath !== 'placeholder') {
        Image(this.bgImagePath)
          .width('100%').height('100%')
          .objectFit(ImageFit.Cover)
          .opacity(this.bgImageAlpha / 100.0)
          .hitTestBehavior(HitTestMode.None)
      } else if (this.bgImagePath === 'placeholder' && this.bgImageAlpha > 0) {
        Column()
          .width('100%').height('100%')
          .backgroundColor('#f5f5dc') /* legacy placeholder until picker works */
          .opacity(this.bgImageAlpha / 100.0)
          .hitTestBehavior(HitTestMode.None)
      }

      /* Sepia wash for the ink theme (touches pass through) */
      if (this.theme === 3) {
        Column()
          .width('100%').height('100%')
          .backgroundColor('#2ED2B48C')
          .hitTestBehavior(HitTestMode.None)
      }

      /* Phase 9: selection action dialog (Android hr12: 文本 popup) */
      if (this.selMode) {
        Stack({ alignContent: Alignment.Center }) {
          Column()
            .width('100%').height('100%')
            .backgroundColor('#33000000')
            .onClick(() => {
              this.exitSelMode();
            })
          Column() {
            /* blue header: 文本 + close */
            Row() {
              Text('\u6587\u672C')
                .fontSize(16).fontColor(Color.White)
              Blank()
              Image($r('app.media.ic_close'))
                .width(20).height(20).fillColor(Color.White)
                .onClick(() => {
                  this.exitSelMode();
                })
            }
            .width('100%')
            .height(42)
            .padding({ left: 14, right: 14 })
            .backgroundColor($r('app.color.brand_primary'))

            /* selected text + TTS entries */
            Row({ space: 10 }) {
              Text(this.selText.length > 0
                ? (this.selText.length > 18 ? this.selText.substring(0, 18) + '…' : this.selText)
                : '\u9009\u62E9\u6587\u672C')
                .fontSize(15).fontColor('#333')
                .maxLines(1)
                .layoutWeight(1)
              Image($r('app.media.ic_sparkle'))
                .width(22).height(22).fillColor('#00897B')
                .onClick(() => {
                  promptAction.showToast({ message: 'AI \u529F\u80FD\u5C06\u5728\u540E\u7EED\u7248\u672C\u63D0\u4F9B' });
                })
              Image($r('app.media.ic_book_open'))
                .width(22).height(22).fillColor('#00897B')
                .onClick(() => this.openTtsPanel())
            }
            .width('100%')
            .padding({ left: 14, right: 14, top: 10, bottom: 10 })

            /* markup row */
            Row({ space: 6 }) {
              Button('\u4E0B\u5212')
                .fontSize(11).height(30).layoutWeight(1).padding({ left: 0, right: 0 })
                .backgroundColor('#1e88e5').fontColor(Color.White)
                .onClick(() => this.applySelMarkup('underline'))
              Button('\u5220\u9664')
                .fontSize(11).height(30).layoutWeight(1).padding({ left: 0, right: 0 })
                .backgroundColor('#e53935').fontColor(Color.White)
                .onClick(() => this.applySelMarkup('strikeout'))
              Button('\u6CE2\u6D6A')
                .fontSize(11).height(30).layoutWeight(1).padding({ left: 0, right: 0 })
                .backgroundColor('#8e24aa').fontColor(Color.White)
                .onClick(() => this.applySelMarkup('squiggly'))
              Button('\u9AD8\u4EAE')
                .fontSize(11).height(30).layoutWeight(1).padding({ left: 0, right: 0 })
                .backgroundColor('#fbc02d').fontColor(Color.White)
                .onClick(() => this.applySelMarkup('highlight'))
            }
            .width('100%')
            .padding({ left: 14, right: 14, bottom: 6 })

            if (this.showSelNoteInput) {
              Row({ space: 8 }) {
                TextInput({ text: this.selNoteInput, placeholder: '\u7B14\u8BB0\u5185\u5BB9' })
                  .layoutWeight(1).height(36).fontSize(13)
                  .onChange((value: string) => {
                    this.selNoteInput = value;
                  })
                Button('\u6DFB\u52A0')
                  .fontSize(12).height(32)
                  .backgroundColor('#00897B').fontColor(Color.White)
                  .onClick(() => this.applySelNote())
              }
              .width('100%')
              .padding({ left: 14, right: 14, bottom: 6 })
            }

            Divider().color('#eeeeee')

            /* menu rows (Android hr12 list) */
            this.selMenuRow($r('app.media.ic_bookmark'), '\u52A0\u5165\u4E66\u7B7E', 1)
            this.selMenuRow($r('app.media.ic_share'), '\u5206\u4EAB', 5)
            this.selMenuRow($r('app.media.ic_copy'), '\u590D\u5236', 2)
            this.selMenuRow($r('app.media.ic_search'), '\u6587\u5185\u641C\u7D22', 3)
            this.selMenuRow($r('app.media.ic_sparkle'), '\u53D1\u9001\u7ED9AI', 6)
            this.selMenuRow($r('app.media.ic_edit'), '\u7B14\u8BB0', 4)
            Divider().color('#eeeeee')
            this.selMenuRow($r('app.media.ic_globe'), '\u7F51\u7EDC\u641C\u7D22(\u8C37\u6B4C\u7B49)', 7)
            this.selMenuRow($r('app.media.ic_book_open'), '\u7F51\u7EDC\u8BCD\u5178(\u725B\u6D25\u8BCD\u5178\u7B49)', 8)
          }
          .width('82%')
          .backgroundColor(Color.White)
          .borderRadius(4)
          .clip(true)
        }
        .width('100%').height('100%')
      }

      /* Menu overlay (modal: full-screen dim + centered panel) */
      if (this.showMenu) {
        Stack({ alignContent: Alignment.Center }) {
          /* dim background to close menu */
          Column()
            .width('100%').height('100%')
            .backgroundColor('#66000000')
            .onClick(() => {
              this.showMenu = false;
              console.info('[Reader] Menu closed (background)');
            })

          Scroll() {
            Column({ space: 14 }) {
              Text('阅读设置')
                .fontSize(16).fontWeight(FontWeight.Bold)

          // Zoom controls
          Row({ space: 12 }) {
            Button('A-')
              .fontSize(14).height(36).width(50)
              .onClick(() => this.zoomOut())
            Text(`${Math.round(this.zoomLevel * 100)}%`)
              .fontSize(14).fontColor('#333').width(50).textAlign(TextAlign.Center)
            Button('A+')
              .fontSize(14).height(36).width(50)
              .onClick(() => this.zoomIn())
          }

          // Mode toggle
          Row({ space: 8 }) {
            Text('模式:')
              .fontSize(13).fontColor('#555')
            Button(this.scrollMode === 0 ? '水平翻页' : '垂直滚动')
              .fontSize(12).height(32)
              .backgroundColor('#e0e0e0').fontColor('#333')
              .onClick(() => this.toggleScrollMode())
          }

          // Page mode toggle (single/double/musician)
          Row({ space: 8 }) {
            Text('页面:')
              .fontSize(13).fontColor('#555')
            Button(this.pageMode === 0 ? '单页' : (this.pageMode === 1 ? '双页' : '乐谱'))
              .fontSize(12).height(32)
              .backgroundColor('#e0e0e0').fontColor('#333')
              .onClick(() => this.togglePageMode())
          }

          // Night mode toggle
          Row({ space: 8 }) {
            Text('夜间:')
              .fontSize(13).fontColor('#555')
            Button(this.nightMode ? '开' : '关')
              .fontSize(12).height(32)
              .backgroundColor(this.nightMode ? '#333' : '#e0e0e0')
              .fontColor(this.nightMode ? Color.White : '#333')
              .onClick(() => this.toggleNightMode())
          }

          // Brightness slider
          Row({ space: 8 }) {
            Text('亮度:')
              .fontSize(13).fontColor('#555')
            Slider({
              value: this.brightnessLevel,
              min: 1,
              max: 255,
              step: 4
            })
              .layoutWeight(1)
              .onChange((value: number) => {
                this.setBrightness(value);
              })
            Text(`${Math.round(this.brightnessLevel / 2.55)}%`)
              .fontSize(11).fontColor('#777').width(36)
          }

          // Auto-flip toggle
          Row({ space: 8 }) {
            Text('自动翻页:')
              .fontSize(13).fontColor('#555')
            Button(this.autoFlip ? '开' : '关')
              .fontSize(12).height(32)
              .backgroundColor(this.autoFlip ? '#2a7' : '#e0e0e0')
              .fontColor(this.autoFlip ? Color.White : '#333')
              .onClick(() => this.toggleAutoFlip())
            Text(`${this.autoFlipInterval}s`)
              .fontSize(11).fontColor('#777')
              .onClick(() => {
                this.autoFlipInterval = this.autoFlipInterval >= 5 ? 1 : this.autoFlipInterval + 1;
                if (this.autoFlip) {
                  this.startAutoFlip();
                }
                console.info(`[Reader] Auto-flip interval: ${this.autoFlipInterval}s`);
              })
          }

          // A: auto-scroll toggle (vertical scroll mode)
          Row({ space: 8 }) {
            Text('\u81EA\u52A8\u6EDA\u52A8:')
              .fontSize(13).fontColor('#555')
            Button(this.autoScroll ? '\u5F00' : '\u5173')
              .fontSize(12).height(32)
              .backgroundColor(this.autoScroll ? '#2a7' : '#e0e0e0')
              .fontColor(this.autoScroll ? Color.White : '#333')
              .onClick(() => {
                this.toggleAutoScroll();
                this.persistSettings();
              })
            Text(this.autoScrollSpeed + 'px')
              .fontSize(11).fontColor('#777')
              .onClick(() => {
                this.autoScrollSpeed = this.autoScrollSpeed >= 8 ? 2 : this.autoScrollSpeed + 2;
                if (this.autoScroll) {
                  this.startAutoScroll();
                }
                this.persistSettings();
                console.info(`[Reader] Auto-scroll speed: ${this.autoScrollSpeed}px`);
              })
          }

          // Annotations & bookmarks
          Row({ space: 8 }) {
            Button('高亮')
              .fontSize(12).height(32)
              .backgroundColor('#ff9800').fontColor(Color.White)
              .onClick(() => this.addHighlightOnPage())
            Button('手绘')
              .fontSize(12).height(32)
              .backgroundColor(this.drawMode ? '#333' : '#e0e0e0')
              .fontColor(this.drawMode ? Color.White : '#333')
              .onClick(() => this.toggleDrawMode())
            Button('保存')
              .fontSize(12).height(32)
              .backgroundColor('#2a7').fontColor(Color.White)
              .onClick(() => this.saveAnnotatedDoc())
          }

          // H6: Quick crop toggle (10% margin trim)
          Row({ space: 8 }) {
            Text('\u88C1\u526A:')
              .fontSize(13).fontColor('#555')
            Button(this.cropEnabled ? '\u5F00' : '\u5173')
              .fontSize(12).height(32)
              .backgroundColor(this.cropEnabled ? '#2a7' : '#e0e0e0')
              .fontColor(this.cropEnabled ? Color.White : '#333')
              .onClick(() => {
                this.cropEnabled = !this.cropEnabled;
                this.reflowVersion++; /* force re-render with new crop */
                console.info(`[Reader] Crop mode: ${this.cropEnabled ? 'ON (10% trim)' : 'OFF'}`);
              })
          }

          // ---- Sprint I: Advanced features ----

          /* I1: Fullscreen toggle */
          Row({ space: 8 }) {
            Text('\u5168\u5C4F:')
              .fontSize(13).fontColor('#555')
            Button(this.isFullscreen ? '\u9000\u51FA' : '\u8FDB\u5165')
              .fontSize(12).height(32)
              .backgroundColor(this.isFullscreen ? '#e57373' : '#e0e0e0')
              .fontColor(this.isFullscreen ? Color.White : '#333')
              .onClick(() => {
                this.toggleFullscreen();
              })
          }

          /* I5: Blue light filter */
          Row({ space: 8 }) {
            Text('\u84DD\u5149:')
              .fontSize(13).fontColor('#555')
            Slider({ value: this.blueLightFilter, min: 0, max: 100, step: 5 })
              .layoutWeight(1)
              .onChange((value: number, mode: SliderChangeMode) => {
                this.blueLightFilter = Math.round(value);
                if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
                  console.info(`[Reader] Blue light filter: ${this.blueLightFilter}%`);
                  this.persistSettings();
                }
              })
            Text(this.blueLightFilter > 0 ? `${this.blueLightFilter}%` : '\u5173')
              .fontSize(11).fontColor('#777').width(36)
          }

          /* I2: RTL reading direction */
          Row({ space: 8 }) {
            Text('RTL:')
              .fontSize(13).fontColor('#555')
            Button(this.rtlEnabled ? '\u5F00' : '\u5173')
              .fontSize(12).height(32)
              .backgroundColor(this.rtlEnabled ? '#2a7' : '#e0e0e0')
              .fontColor(this.rtlEnabled ? Color.White : '#333')
              .onClick(() => {
                this.rtlEnabled = !this.rtlEnabled;
                /* For reflowable docs, apply CSS direction */
                if (this.isReflow) {
                  this.applyTypography();
                }
                /* For Swiper, the RTL is handled by reverse() in UI */
                console.info(`[Reader] RTL mode: ${this.rtlEnabled ? 'ON' : 'OFF'}`);
              })
          }

          /* I4: Gesture configuration */
          Row({ space: 8 }) {
            Button('\u624B\u52BF\u914D\u7F6E')
              .fontSize(12).height(32)
              .backgroundColor('#7e57c2').fontColor(Color.White)
              .onClick(() => {
                this.showMenu = false;
                this.showGestureConfig = true;
                console.info('[Reader] Gesture config opened');
              })
          }

          /* I6: Background image (Phase 7: real picker → sandbox path) */
          Row({ space: 8 }) {
            Button(this.bgImagePath.length > 0 ? '\u80CC\u666F\u56FE \u2713' : '\u80CC\u666F\u56FE')
              .fontSize(12).height(32)
              .backgroundColor('#4db6ac').fontColor(Color.White)
              .onClick(() => {
                this.pickBgImage();
              })
            if (this.bgImagePath.length > 0) {
              Slider({ value: this.bgImageAlpha, min: 0, max: 100, step: 5 })
                .width(80)
                .onChange((value: number, mode: SliderChangeMode) => {
                  this.bgImageAlpha = Math.round(value);
                  if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
                    console.info(`[Reader] BG image alpha: ${this.bgImageAlpha}%`);
                    this.persistSettings();
                  }
                })
            }
          }

          /* I7: Text replacement */
          Row({ space: 8 }) {
            Button('\u6587\u672C\u66FF\u6362')
              .fontSize(12).height(32)
              .backgroundColor('#ff8a65').fontColor(Color.White)
              .onClick(() => {
                this.showMenu = false;
                this.showTextReplace = true;
                console.info('[Reader] Text replace panel opened');
              })
          }

          if (this.annotations.length > 0) {
            Row({ space: 8 }) {
              Text(`标注 ${this.annotations.length}:`)
                .fontSize(12).fontColor('#555')
              Button(`删除第1条`)
                .fontSize(11).height(28)
                .backgroundColor('#e57373').fontColor(Color.White)
                .onClick(() => this.deleteAnnotationAt(0))
            }
          }

          /* Round 5: whole-book annotation list (Android annotation manager parity) */
          Row({ space: 8 }) {
            Button($r('app.string.menu_annotations'))
              .fontSize(12).height(32)
              .backgroundColor('#00897B').fontColor(Color.White)
              .onClick(() => {
                this.showMenu = false;
                this.openAnnPanel();
              })
          }

          /* Round 5: thumbnails + page history + split + contrast */
          Row({ space: 8 }) {
            Button($r('app.string.menu_thumbs'))
              .fontSize(12).height(32)
              .backgroundColor('#5c6bc0').fontColor(Color.White)
              .onClick(() => {
                this.showMenu = false;
                this.openThumbs();
              })
            Button($r('app.string.menu_history'))
              .fontSize(12).height(32)
              .backgroundColor(this.pageHistory.length > 0 ? '#5c6bc0' : '#b0bec5')
              .fontColor(Color.White)
              .onClick(() => {
                this.popPageHistory();
              })
          }

          Row({ space: 8 }) {
            Button(this.pageSplit ? $r('app.string.menu_split_on') : $r('app.string.menu_split_off'))
              .fontSize(12).height(32)
              .backgroundColor(this.pageSplit ? '#2a7' : '#e0e0e0')
              .fontColor(this.pageSplit ? Color.White : '#333')
              .onClick(() => {
                this.pageSplit = !this.pageSplit;
                console.info(`[Reader] Page split: ${this.pageSplit}`);
              })
            Text($r('app.string.menu_contrast'))
              .fontSize(12).fontColor('#555')
            Slider({ value: this.contrastLevel, min: 0, max: 100, step: 5 })
              .layoutWeight(1).height(24)
              .onChange((value: number, mode: SliderChangeMode) => {
                if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
                  this.contrastLevel = Math.round(value);
                  console.info(`[Reader] Contrast: ${this.contrastLevel}`);
                }
              })
            Text(`${this.contrastLevel}`)
              .fontSize(11).fontColor('#777').width(24)
          }

          // TTS & RSVP entries (sprint D)
          Row({ space: 8 }) {
            Button('🔊 TTS 朗读')
              .fontSize(12).height(32)
              .backgroundColor('#7b68ee').fontColor(Color.White)
              .onClick(() => {
                this.showMenu = false;
                this.openTtsPanel();
              })
            Button('⚡ 速读')
              .fontSize(12).height(32)
              .backgroundColor('#e91e63').fontColor(Color.White)
              .onClick(() => {
                this.showMenu = false;
                this.openRsvpPanel();
              })
          }

          // Settings button (sprint E)
          Row({ space: 8 }) {
            Button('⚙ 设置')
              .fontSize(12).height(32)
              .backgroundColor('#607d8b').fontColor(Color.White)
              .onClick(() => {
                this.showMenu = false;
                this.showSettings = true;
                console.info('[Reader] Settings panel opened');
              })
          }

          // Typography panel toggle (sprint H4/H5) - only for reflowable docs
          if (this.isReflow) {
            Row({ space: 8 }) {
              Button('\u6392\u7248')
                .fontSize(12).height(32)
                .backgroundColor('#ff7043').fontColor(Color.White)
                .onClick(() => {
                  this.showMenu = false;
                  this.showTypographyPanel = true;
                  console.info('[Reader] Typography panel opened');
                })
            }
          }

          // TOC button
          if (this.tocEntries.length > 0) {
            Button('目录 (TOC)')
              .fontSize(13).height(36)
              .backgroundColor('#2a7').fontColor(Color.White)
              .onClick(() => {
                this.showToc = true;
                console.info('[Reader] TOC panel opened');
              })
          }

          /* K1: Share book */
          Row({ space: 8 }) {
            Button('\u5206\u4EAB')
              .fontSize(12).height(32)
              .backgroundColor('#42a5f5').fontColor(Color.White)
              .onClick(() => {
                this.showMenu = false;
                this.shareBook();
                console.info('[Reader] Share book triggered');
              })
          }

          // File info (sprint G6)
          Button('📄 文件信息')
            .fontSize(13).height(36)
            .backgroundColor('#90a4ae').fontColor(Color.White)
            .onClick(() => {
              this.showMenu = false;
              this.openFileInfo();
              this.showFileInfo = true;
              console.info('[Reader] File info dialog opened');
            })

          // Close menu
          Button('关闭')
            .fontSize(13).height(36)
            .backgroundColor('#f0f0f0').fontColor('#555')
            .onClick(() => {
              this.showMenu = false;
              console.info('[Reader] Menu closed');
            })
          }
          .width('100%')
          .padding(20)
          .backgroundColor(Color.White)
          .borderRadius(12)
          .shadow({ radius: 16, color: '#40000000', offsetX: 0, offsetY: 4 })
          }
          .width('72%')
          .height('85%')
          .align(Alignment.Center)
        }
      }

      /* I4: Gesture configuration dialog */
      if (this.showGestureConfig) {
        Stack({ alignContent: Alignment.Center }) {
          Column()
            .width('100%').height('100%')
            .backgroundColor('#66000000')
            .onClick(() => { this.showGestureConfig = false; })

          Scroll() {
            Column({ space: 12 }) {
              Text('\u624B\u52BF\u914D\u7F6E')
                .fontSize(15).fontWeight(FontWeight.Bold)

              /* Left tap zone */
              Row({ space: 6 }) {
                Text('\u5DE6\u533A:')
                  .fontSize(12).fontColor('#555').width(40)
                Button('\u4E0A\u9875').fontSize(11).height(28)
                  .backgroundColor(this.tapLeftAction === 0 ? '#2a7' : '#e0e0e0')
                  .fontColor(this.tapLeftAction === 0 ? Color.White : '#333')
                  .onClick(() => { this.tapLeftAction = 0; })
                Button('\u83DC\u5355').fontSize(11).height(28)
                  .backgroundColor(this.tapLeftAction === 1 ? '#2a7' : '#e0e0e0')
                  .fontColor(this.tapLeftAction === 1 ? Color.White : '#333')
                  .onClick(() => { this.tapLeftAction = 1; })
                Button('\u76EE\u5F55').fontSize(11).height(28)
                  .backgroundColor(this.tapLeftAction === 2 ? '#2a7' : '#e0e0e0')
                  .fontColor(this.tapLeftAction === 2 ? Color.White : '#333')
                  .onClick(() => { this.tapLeftAction = 2; })
                Button('\u4E66\u7B7E').fontSize(11).height(28)
                  .backgroundColor(this.tapLeftAction === 3 ? '#2a7' : '#e0e0e0')
                  .fontColor(this.tapLeftAction === 3 ? Color.White : '#333')
                  .onClick(() => { this.tapLeftAction = 3; })
              }

              /* Center tap zone */
              Row({ space: 6 }) {
                Text('\u4E2D\u533A:')
                  .fontSize(12).fontColor('#555').width(40)
                Button('\u83DC\u5355').fontSize(11).height(28)
                  .backgroundColor(this.tapCenterAction === 0 ? '#2a7' : '#e0e0e0')
                  .fontColor(this.tapCenterAction === 0 ? Color.White : '#333')
                  .onClick(() => { this.tapCenterAction = 0; })
                Button('\u5168\u5C4F').fontSize(11).height(28)
                  .backgroundColor(this.tapCenterAction === 1 ? '#2a7' : '#e0e0e0')
                  .fontColor(this.tapCenterAction === 1 ? Color.White : '#333')
                  .onClick(() => { this.tapCenterAction = 1; })
                Button('\u4EAE\u5EA6').fontSize(11).height(28)
                  .backgroundColor(this.tapCenterAction === 2 ? '#2a7' : '#e0e0e0')
                  .fontColor(this.tapCenterAction === 2 ? Color.White : '#333')
                  .onClick(() => { this.tapCenterAction = 2; })
              }

              /* Right tap zone */
              Row({ space: 6 }) {
                Text('\u53F3\u533A:')
                  .fontSize(12).fontColor('#555').width(40)
                Button('\u4E0B\u9875').fontSize(11).height(28)
                  .backgroundColor(this.tapRightAction === 0 ? '#2a7' : '#e0e0e0')
                  .fontColor(this.tapRightAction === 0 ? Color.White : '#333')
                  .onClick(() => { this.tapRightAction = 0; })
                Button('\u83DC\u5355').fontSize(11).height(28)
                  .backgroundColor(this.tapRightAction === 1 ? '#2a7' : '#e0e0e0')
                  .fontColor(this.tapRightAction === 1 ? Color.White : '#333')
                  .onClick(() => { this.tapRightAction = 1; })
                Button('\u76EE\u5F55').fontSize(11).height(28)
                  .backgroundColor(this.tapRightAction === 2 ? '#2a7' : '#e0e0e0')
                  .fontColor(this.tapRightAction === 2 ? Color.White : '#333')
                  .onClick(() => { this.tapRightAction = 2; })
                Button('\u4E66\u7B7E').fontSize(11).height(28)
                  .backgroundColor(this.tapRightAction === 3 ? '#2a7' : '#e0e0e0')
                  .fontColor(this.tapRightAction === 3 ? Color.White : '#333')
                  .onClick(() => { this.tapRightAction = 3; })
              }

              Button('\u4FDD\u5B58')
                .fontSize(13).height(34)
                .backgroundColor('#2a7').fontColor(Color.White)
                .margin({ top: 8 })
                .onClick(() => {
                  this.showGestureConfig = false;
                  console.info(`[Reader] Gestures saved: L=${this.tapLeftAction} C=${this.tapCenterAction} R=${this.tapRightAction}`);
                })
            }
            .width('100%')
            .padding(20)
            .backgroundColor(Color.White)
          }
          .width('80%')
          .height('55%')
          .borderRadius(12)
        }
      }

      /* I7: Text replacement panel */
      if (this.showTextReplace) {
        Stack({ alignContent: Alignment.Center }) {
          Column()
            .width('100%').height('100%')
            .backgroundColor('#66000000')
            .onClick(() => { this.showTextReplace = false; })

          Column({ space: 12 }) {
            Text('\u6587\u672C\u66FF\u6362 (reflow)')
              .fontSize(15).fontWeight(FontWeight.Bold)

            TextInput({ placeholder: '\u641C\u7D22\u6587\u672C...' })
              .fontSize(13).height(36)
              .onChange((v: string) => { this.replaceFrom = v; })

            TextInput({ placeholder: '\u66FF\u6362\u4E3A...' })
              .fontSize(13).height(36)
              .onChange((v: string) => { this.replaceTo = v; })

            Row({ space: 8 }) {
              Button('\u5E94\u7528')
                .fontSize(12).height(32).layoutWeight(1)
                .backgroundColor('#2a7').fontColor(Color.White)
                .onClick(() => {
                  /* Apply text replacement via CSS content or post-processing */
                  console.info(`[Reader] Text replace: '${this.replaceFrom}' -> '${this.replaceTo}'`);
                  this.showTextReplace = false;
                })
              Row() {
                Button('\uD83D\uDCE5 \u5BFC\u51FA\u6587\u672C')
                  .fontSize(12).height(30)
                  .onClick(() => { this.exportTtsText(); })
              }.width('90%').margin({ top: 8 })

              Button('\u5173\u95ED')
                .fontSize(12).height(32).layoutWeight(1)
                .backgroundColor('#e0e0e0').fontColor('#333')
                .onClick(() => { this.showTextReplace = false; })
            }

            Text('\u6CE8: \u4EC5\u5BF9 reflowable \u6587\u6863 (EPUB/HTML) \u751F\u6548')
              .fontSize(10).fontColor('#999')
          }
          .width('80%')
          .padding(20)
          .backgroundColor(Color.White)
          .borderRadius(12)
        }
      }

      /* Typography settings panel (sprint H4/H5) */
      if (this.showTypographyPanel) {
        Stack({ alignContent: Alignment.Center }) {
          Column()
            .width('100%').height('100%')
            .backgroundColor('#66000000')
            .onClick(() => {
              this.showTypographyPanel = false;
            })

          Scroll() {
            Column({ space: 12 }) {
              Text('\u6392\u7248\u8BBE\u7F6E (EPUB/HTML)')
                .fontSize(15).fontWeight(FontWeight.Bold)

              /* H4: Text alignment */
              Row({ space: 6 }) {
                Text('\u5BF9\u9F50:')
                  .fontSize(12).fontColor('#555')
                Button('\u5DE6').fontSize(11).height(30)
                  .backgroundColor(this.textAlign === 0 ? '#2a7' : '#e0e0e0')
                  .fontColor(this.textAlign === 0 ? Color.White : '#333')
                  .onClick(() => { this.textAlign = 0; this.applyTypography(); })
                Button('\u4E2D').fontSize(11).height(30)
                  .backgroundColor(this.textAlign === 1 ? '#2a7' : '#e0e0e0')
                  .fontColor(this.textAlign === 1 ? Color.White : '#333')
                  .onClick(() => { this.textAlign = 1; this.applyTypography(); })
                Button('\u53F3').fontSize(11).height(30)
                  .backgroundColor(this.textAlign === 2 ? '#2a7' : '#e0e0e0')
                  .fontColor(this.textAlign === 2 ? Color.White : '#333')
                  .onClick(() => { this.textAlign = 2; this.applyTypography(); })
                Button('\u4E24\u7AEF').fontSize(11).height(30)
                  .backgroundColor(this.textAlign === 3 ? '#2a7' : '#e0e0e0')
                  .fontColor(this.textAlign === 3 ? Color.White : '#333')
                  .onClick(() => { this.textAlign = 3; this.applyTypography(); })
              }

              /* H4: Paragraph spacing */
              Row({ space: 8 }) {
                Text('\u6BB5\u8D70:')
                  .fontSize(12).fontColor('#555')
                Slider({ value: this.paragraphSpacing, min: 0, max: 32, step: 2 })
                  .layoutWeight(1)
                  .onChange((value: number, mode: SliderChangeMode) => {
                    this.paragraphSpacing = Math.round(value);
                    if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
                      this.applyTypography();
                    }
                  })
                Text(`${this.paragraphSpacing}px`)
                  .fontSize(11).fontColor('#777').width(36)
              }

              /* H5: Hyphenation toggle */
              Row({ space: 8 }) {
                Text('\u8FDE\u5B57\u7B26:')
                  .fontSize(12).fontColor('#555')
                Button(this.hyphenationEnabled ? '\u5F00' : '\u5173')
                  .fontSize(12).height(30)
                  .backgroundColor(this.hyphenationEnabled ? '#2a7' : '#e0e0e0')
                  .fontColor(this.hyphenationEnabled ? Color.White : '#333')
                  .onClick(() => {
                    this.hyphenationEnabled = !this.hyphenationEnabled;
                    this.applyTypography();
                  })
                Text('(\u82F1\u6587)')
                  .fontSize(10).fontColor('#999')
              }


                /* Sprint L: text indent slider */
                Row() {
                  Text('\u7F29\u8FDB').fontSize(12).fontColor('#555').width(50)
                  Slider({ value: this.textIndent, min: 0, max: 4, step: 0.5 })
                    .layoutWeight(1)
                    .onChange((v: number) => { this.textIndent = v; })
                }.width('90%').margin({ top: 8 })

                /* Sprint L: drop cap toggle */
                Row() {
                  Text('\u9996\u5B57\u4E0B\u6C89').fontSize(12).fontColor('#555')
                  Button(this.dropCapEnabled ? '\u5F00' : '\u5173')
                    .fontSize(12).height(30)
                    .backgroundColor(this.dropCapEnabled ? '#2a7' : '#e0e0e0')
                    .fontColor(this.dropCapEnabled ? Color.White : '#333')
                    .onClick(() => {
                      this.dropCapEnabled = !this.dropCapEnabled;
                      this.applyTypography();
                    })
                }.width('90%').margin({ top: 8 })

                /* Sprint L: font family selector */
                Row() {
                  Text('\u5B57\u4F53').fontSize(12).fontColor('#555').width(50)
                  Button('\u9ED8\u8BA4')
                    .height(28).fontSize(11).layoutWeight(1)
                    .backgroundColor(this.fontFamilyIdx === 0 ? '#2a7' : '#e0e0e0')
                    .fontColor(this.fontFamilyIdx === 0 ? Color.White : '#333')
                    .onClick(() => { this.fontFamilyIdx = 0; this.applyTypography(); })
                  Button('\u886C\u7EBF')
                    .height(28).fontSize(11).layoutWeight(1).margin({ left: 4 })
                    .backgroundColor(this.fontFamilyIdx === 1 ? '#2a7' : '#e0e0e0')
                    .fontColor(this.fontFamilyIdx === 1 ? Color.White : '#333')
                    .onClick(() => { this.fontFamilyIdx = 1; this.applyTypography(); })
                  Button('\u65E0\u886C')
                    .height(28).fontSize(11).layoutWeight(1).margin({ left: 4 })
                    .backgroundColor(this.fontFamilyIdx === 2 ? '#2a7' : '#e0e0e0')
                    .fontColor(this.fontFamilyIdx === 2 ? Color.White : '#333')
                    .onClick(() => { this.fontFamilyIdx = 2; this.applyTypography(); })
                  Button('\u7B49\u5BBD')
                    .height(28).fontSize(11).layoutWeight(1).margin({ left: 4 })
                    .backgroundColor(this.fontFamilyIdx === 3 ? '#2a7' : '#e0e0e0')
                    .fontColor(this.fontFamilyIdx === 3 ? Color.White : '#333')
                    .onClick(() => { this.fontFamilyIdx = 3; this.applyTypography(); })
                }.width('90%').margin({ top: 8 })
              Button('\u5B8C\u6210')
                .fontSize(13).height(34)
                .backgroundColor('#e0e0e0').fontColor('#333')
                .margin({ top: 8 })
                .onClick(() => {
                  this.showTypographyPanel = false;
                })
            }
            .width('100%')
            .padding(20)
            .backgroundColor(Color.White)
          }
          .width('75%')
          .height('60%')
          .borderRadius(12)
        }
      }

      /* File info dialog (sprint G6) */
      if (this.showFileInfo) {
        Stack({ alignContent: Alignment.Center }) {
          Column()
            .width('100%').height('100%')
            .backgroundColor('#66000000')
            .onClick(() => {
              this.showFileInfo = false;
              console.info('[Reader] File info closed');
            })

          Column({ space: 10 }) {
            Text('📄 文件信息')
              .fontSize(16).fontWeight(FontWeight.Bold)

            if (this.fileInfoText.length > 0) {
              Text(this.fileInfoText)
                .fontSize(12).fontColor('#444')
                .width('100%')
                .textAlign(TextAlign.Start)
            } else {
              Text('加载中...')
                .fontSize(13).fontColor('#999')
            }

            Button('关闭')
              .fontSize(13).height(34)
              .backgroundColor('#e0e0e0').fontColor('#333')
              .margin({ top: 8 })
              .onClick(() => {
                this.showFileInfo = false;
              })
          }
          .width('75%')
          .padding(20)
          .backgroundColor(Color.White)
          .borderRadius(12)
        }
      }

      /* TOC sidebar (slides in from right) */
      if (this.showToc) {
        Row() {
          /* Dimmed background to close TOC */
          Column()
            .layoutWeight(1)
            .height('100%')
            .backgroundColor('#40000000')
            .onClick(() => {
              this.showToc = false;
              console.info('[Reader] TOC panel closed');
            })

          TocPanel({
            onJumpPage: (page: number) => {
              this.pushPageHistory();
              this.currentPage = page;
              this.onPageChanged(page);
              this.showToc = false;
            }
          })
        }
        .width('100%').height('100%')
        .justifyContent(FlexAlign.End)
      }

      /* Bookmark panel (slides in from right) */
      if (this.showBookmarks) {
        Row() {
          Column()
            .layoutWeight(1)
            .height('100%')
            .backgroundColor('#40000000')
            .onClick(() => {
              this.showBookmarks = false;
              console.info('[Reader] Bookmark panel closed');
            })

          Column() {
            Row({ space: 8 }) {
              Text('书签')
                .fontSize(16).fontWeight(FontWeight.Bold)
              Blank()
              Text('\u5BFC\u51FA')
                .fontSize(13).fontColor($r('app.color.brand_primary'))
                .onClick(() => {
                  this.showExportDialog = true;
                })
            }
            .width('100%')
            .margin({ bottom: 8 })

            if (this.bookmarks.length === 0) {
              Text('(暂无书签，点顶栏 🔖 添加)')
                .fontSize(13).fontColor(Color.Gray)
            } else {
              List() {
                ForEach(this.bookmarks, (bm: Bookmark) => {
                  ListItem() {
                    Row() {
                      Text(`p${bm.page + 1}  ${bm.label.length > 0 ? bm.label : '书签'}`)
                        .fontSize(13).fontColor('#333')
                        .layoutWeight(1)
                        .onClick(() => {
                          this.jumpToBookmark(bm.page);
                        })
                      Text('✕')
                        .fontSize(15).fontColor('#e57373')
                        .onClick(() => {
                          removeBookmark(getContext(this), bm.path, bm.page).then(() => {
                            this.loadBookmarks();
                          });
                        })
                    }
                    .width('100%')
                    .padding({ top: 6, bottom: 6 })
                  }
                }, (bm: Bookmark) => `${bm.page}_${bm.date}`)
              }
              .layoutWeight(1)
            }

            /* AI 笔记 (hr17): AI 回答 / 简介 / 翻译，按时间倒序 */
            Divider().color('#eeeeee').margin({ top: 8, bottom: 8 })
            Row({ space: 6 }) {
              Text('AI \u7B14\u8BB0')
                .fontSize(14).fontWeight(FontWeight.Bold).fontColor('#333')
              Text(`(${this.aiNotes.length})`)
                .fontSize(12).fontColor('#999')
            }
            .width('100%')

            if (this.aiNotes.length === 0) {
              Text('(\u6682\u65E0\uFF0C\u9009\u4E2D\u6587\u672C\u53D1\u9001\u7ED9AI\u6216\u4F7F\u7528AI\u7FFB\u8BD1)')
                .fontSize(12).fontColor(Color.Gray)
            } else {
              List() {
                ForEach(this.aiNotes, (n: AiNote) => {
                  ListItem() {
                    Row({ space: 8 }) {
                      Text(n.kind)
                        .fontSize(10).fontColor(Color.White)
                        .backgroundColor($r('app.color.brand_primary'))
                        .borderRadius(4)
                        .padding({ left: 5, right: 5, top: 2, bottom: 2 })
                      Column({ space: 2 }) {
                        Text(`${n.date.substring(0, 16).replace('T', ' ')} \u00B7 \u7B2C ${n.page + 1} \u9875`)
                          .fontSize(10).fontColor('#999')
                        Text(n.text)
                          .fontSize(12).fontColor('#333')
                          .maxLines(3)
                          .textOverflow({ overflow: TextOverflow.Ellipsis })
                      }
                      .alignItems(HorizontalAlign.Start)
                      .layoutWeight(1)
                      Text('\u2715')
                        .fontSize(14).fontColor('#e57373')
                        .onClick(() => {
                          removeNote(getContext(this), n.date).then(() => {
                            this.loadAiNotes();
                          });
                        })
                    }
                    .width('100%')
                    .padding({ top: 6, bottom: 6 })
                  }
                }, (n: AiNote) => n.date)
              }
              .height(180)
            }
          }
          .width('70%')
          .height('100%')
          .backgroundColor('#fff')
          .padding(12)
          .shadow({ radius: 8, color: '#40000000', offsetX: -2, offsetY: 0 })
        }
        .width('100%').height('100%')
        .justifyContent(FlexAlign.End)
      }

      /* Round 5: whole-book annotation list panel (slides in from right) */
      if (this.showAnnPanel) {
        Row() {
          Column()
            .layoutWeight(1)
            .height('100%')
            .backgroundColor('#40000000')
            .onClick(() => {
              this.showAnnPanel = false;
            })

          Column() {
            Row({ space: 8 }) {
              Text($r('app.string.ann_panel_title'))
                .fontSize(16).fontWeight(FontWeight.Bold)
              Text(`(${this.annAll.length})`)
                .fontSize(12).fontColor('#999')
              Blank()
              Text('刷新')
                .fontSize(13).fontColor($r('app.color.brand_primary'))
                .onClick(() => {
                  this.openAnnPanel();
                })
            }
            .width('100%')
            .margin({ bottom: 8 })

            if (this.annScanning) {
              Text($r('app.string.ann_scan'))
                .fontSize(12).fontColor(Color.Gray)
            } else if (this.annAll.length === 0) {
              Text($r('app.string.ann_empty'))
                .fontSize(13).fontColor(Color.Gray)
            } else {
              List() {
                ForEach(this.annAll, (ann: AnnotationInfo, idx: number) => {
                  ListItem() {
                    Row({ space: 8 }) {
                      Text(ann.type)
                        .fontSize(10).fontColor(Color.White)
                        .backgroundColor('#00897B')
                        .borderRadius(4)
                        .padding({ left: 5, right: 5, top: 2, bottom: 2 })
                      Column({ space: 2 }) {
                        Text(`第 ${this.annPages[idx] + 1} 页`)
                          .fontSize(10).fontColor('#999')
                        Text(ann.contents.length > 0 ? ann.contents : ann.type)
                          .fontSize(12).fontColor('#333')
                          .maxLines(2)
                          .textOverflow({ overflow: TextOverflow.Ellipsis })
                      }
                      .alignItems(HorizontalAlign.Start)
                      .layoutWeight(1)
                      .onClick(() => {
                        this.jumpAnnPage(idx);
                      })
                      Text('✕')
                        .fontSize(15).fontColor('#e57373')
                        .onClick(() => {
                          this.deleteAnnEntry(idx);
                        })
                    }
                    .width('100%')
                    .padding({ top: 6, bottom: 6 })
                  }
                }, (ann: AnnotationInfo, idx: number) => `ann_${idx}_${ann.type}_${ann.x0}`)
              }
              .layoutWeight(1)
            }
          }
          .width('70%')
          .height('100%')
          .backgroundColor('#fff')
          .padding(12)
          .shadow({ radius: 8, color: '#40000000', offsetX: -2, offsetY: 0 })
        }
        .width('100%').height('100%')
        .justifyContent(FlexAlign.End)
      }

      /* Round 5: page thumbnail grid dialog */
      if (this.showThumbs) {
        this.buildThumbDialog()
      }

      /* AI 翻译 dialog (Android hr18, panel mode) */
      if (this.showTrans) {
        this.buildTransDialog()
      }

      /* AI 翻译结果 bottom sheet */
      if (this.showTransPanel) {
        this.buildTransPanel()
      }

      /* Notes/bookmarks export dialog (A: TXT / Markdown) */
      if (this.showExportDialog) {
        Stack({ alignContent: Alignment.Center }) {
          Column()
            .width('100%').height('100%')
            .backgroundColor('#66000000')
            .onClick(() => {
              this.showExportDialog = false;
            })

          Column({ space: 12 }) {
            Text('\u5BFC\u51FA\u7B14\u8BB0\u4E0E\u4E66\u7B7E')
              .fontSize(15).fontWeight(FontWeight.Bold)
            Text('\u5305\u542B\u4E66\u7B7E\u4E0E AI \u7B14\u8BB0\uFF0C\u6BCF\u6761\u5E26\u4F4D\u7F6E\u4E0E\u65F6\u95F4')
              .fontSize(11).fontColor('#999')
            Button('TXT')
              .width('80%').height(38).fontSize(13)
              .backgroundColor($r('app.color.brand_primary')).fontColor(Color.White)
              .onClick(() => {
                this.doExportNotes(false);
              })
            Button('Markdown')
              .width('80%').height(38).fontSize(13)
              .backgroundColor('#00897B').fontColor(Color.White)
              .onClick(() => {
                this.doExportNotes(true);
              })
            Button('\u53D6\u6D88')
              .width('80%').height(34).fontSize(13)
              .backgroundColor('#e0e0e0').fontColor('#333')
              .onClick(() => {
                this.showExportDialog = false;
              })
          }
          .width('70%')
          .padding(16)
          .backgroundColor(Color.White)
          .borderRadius(12)
        }
        .width('100%').height('100%')
      }

      /* Hidden ArkWeb host exposing speechSynthesis for TTS (sprint D) */
      Web({ src: $rawfile('tts.html'), controller: this.webController })
        .width(1).height(1)
        .position({ x: -20, y: -20 })
        .opacity(0)
        .focusable(false)
        .javaScriptAccess(true)
        .onPageEnd(() => {
          this.probeTtsEngine();
        })
        .onErrorReceive((event: OnErrorReceiveEvent) => {
          this.ttsEngineOk = false;
          console.error('[Reader] TTS host page load error');
        })

      /* TTS reading panel (modal) */
      if (this.showTts) {
        Stack({ alignContent: Alignment.Center }) {
          /* dim background to close the panel */
          Column()
            .width('100%').height('100%')
            .backgroundColor('#66000000')
            .onClick(() => {
              this.closeTtsPanel();
            })

          Scroll() {
            Column({ space: 12 }) {
              Text('朗读 (TTS)')
                .fontSize(16).fontWeight(FontWeight.Bold)

              Text(this.ttsStatus)
                .fontSize(13).fontColor(this.ttsEngineOk ? '#2a7' : '#e57373')

              if (!this.ttsEngineOk) {
                Text('当前设备未提供语音引擎，可改用速读模式。')
                  .fontSize(11).fontColor('#e57373').textAlign(TextAlign.Center)
              }

              Text(this.ttsSentences.length > 0 && this.ttsIndex < this.ttsSentences.length
                ? this.ttsSentences[this.ttsIndex] : '(本页无文本)')
                .fontSize(12).fontColor('#333').maxLines(2)
                .textOverflow({ overflow: TextOverflow.Ellipsis })

              Text(`第 ${Math.min(this.ttsIndex + 1, this.ttsSentences.length)}/${this.ttsSentences.length} 句 · 第 ${this.currentPage + 1} 页`)
                .fontSize(11).fontColor('#777')

              Row({ space: 10 }) {
                Button(this.ttsPaused ? '▶' : '⏸')
                  .fontSize(16).height(40).width(56)
                  .backgroundColor('#7b68ee').fontColor(Color.White)
                  .onClick(() => this.ttsTogglePause())
                Button('⏹')
                  .fontSize(16).height(40).width(56)
                  .backgroundColor('#e57373').fontColor(Color.White)
                  .onClick(() => this.ttsStop())
                Button('上一句')
                  .fontSize(12).height(40)
                  .backgroundColor('#e0e0e0').fontColor('#333')
                  .onClick(() => this.ttsPrevSentence())
                Button('下一句')
                  .fontSize(12).height(40)
                  .backgroundColor('#e0e0e0').fontColor('#333')
                  .onClick(() => this.ttsNextSentence())
              }

              Row({ space: 8 }) {
                Text('语速:')
                  .fontSize(13).fontColor('#555')
                Slider({
                  value: this.ttsRate,
                  min: 0.5,
                  max: 2.0,
                  step: 0.1
                })
                  .layoutWeight(1)
                  .onChange((value: number) => this.setTtsRate(value))
                Text(`${this.ttsRate.toFixed(1)}x`)
                  .fontSize(11).fontColor('#777').width(36)
              }

              Row({ space: 8 }) {
                Text('音调:')
                  .fontSize(13).fontColor('#555')
                Slider({
                  value: this.ttsPitch,
                  min: 0.5,
                  max: 2.0,
                  step: 0.1
                })
                  .layoutWeight(1)
                  .onChange((value: number) => this.setTtsPitch(value))
                Text(`${this.ttsPitch.toFixed(1)}x`)
                  .fontSize(11).fontColor('#777').width(36)
              }

              Row({ space: 8 }) {
                Button('⚡ 转速读')
                  .fontSize(12).height(36)
                  .backgroundColor('#e91e63').fontColor(Color.White)
                  .onClick(() => {
                    this.closeTtsPanel();
                    this.openRsvpPanel();
                  })
                Button('关闭')
                  .fontSize(13).height(36)
                  .backgroundColor('#f0f0f0').fontColor('#555')
                  .onClick(() => this.closeTtsPanel())
              }
            }
            .width('100%')
            .padding(20)
          }
          .width('85%')
          .height('80%')
          .align(Alignment.Center)
          .backgroundColor(Color.White)
          .borderRadius(12)
          .shadow({ radius: 16, color: '#40000000', offsetX: 0, offsetY: 4 })
        }
      }

      /* Speed reading RSVP panel (modal) */
      if (this.showRsvp) {
        Stack({ alignContent: Alignment.Center }) {
          /* dim background to close the panel */
          Column()
            .width('100%').height('100%')
            .backgroundColor('#66000000')
            .onClick(() => {
              this.closeRsvpPanel();
            })

          Scroll() {
            Column({ space: 12 }) {
              Text('速读 (RSVP)')
                .fontSize(16).fontWeight(FontWeight.Bold)

              Text(`${this.rsvpWpm} 字/分`)
                .fontSize(13).fontColor('#e91e63')

              /* word flash area */
              Column() {
                Text(this.rsvpWord.length > 0 ? this.rsvpWord : '▶ 开始')
                  .fontSize(44).fontWeight(FontWeight.Bold)
                  .fontColor('#222')
                  .textAlign(TextAlign.Center)
                  .maxLines(1)
              }
              .width('100%').height(120)
              .justifyContent(FlexAlign.Center)
              .backgroundColor('#f5f5f5')
              .borderRadius(8)

              Text(this.rsvpProgress +
                (this.rsvpPaused ? ' (已暂停)' : (this.rsvpRunning ? '' : ' (已停止)')))
                .fontSize(11).fontColor('#777')

              Row({ space: 10 }) {
                Button(this.rsvpPaused ? '▶ 继续' : '▶ 开始')
                  .fontSize(13).height(40)
                  .backgroundColor('#e91e63').fontColor(Color.White)
                  .onClick(() => this.rsvpToggle())
                Button('⏸ 暂停')
                  .fontSize(13).height(40)
                  .backgroundColor('#ff9800').fontColor(Color.White)
                  .onClick(() => this.rsvpPause())
                Button('⏹ 停止')
                  .fontSize(13).height(40)
                  .backgroundColor('#e57373').fontColor(Color.White)
                  .onClick(() => this.rsvpStop())
              }

              Row({ space: 8 }) {
                Text('速度:')
                  .fontSize(13).fontColor('#555')
                Slider({
                  value: this.rsvpWpm,
                  min: 100,
                  max: 800,
                  step: 50
                })
                  .layoutWeight(1)
                  .onChange((value: number) => this.setRsvpWpm(value))
                Text(`${this.rsvpWpm}`)
                  .fontSize(11).fontColor('#777').width(36)
              }

              Text('读完当前页自动进入下一页 · 手动翻页会重置到新页')
                .fontSize(11).fontColor('#999').textAlign(TextAlign.Center)

              Button('🔊 转朗读')
                .fontSize(12).height(36)
                .backgroundColor('#7b68ee').fontColor(Color.White)
                .onClick(() => {
                  this.closeRsvpPanel();
                  this.openTtsPanel();
                })

              Button('关闭')
                .fontSize(13).height(36)
                .backgroundColor('#f0f0f0').fontColor('#555')
                .onClick(() => this.closeRsvpPanel())
            }
            .width('100%')
            .padding(20)
          }
          .width('85%')
          .height('80%')
          .align(Alignment.Center)
          .backgroundColor(Color.White)
          .borderRadius(12)
          .shadow({ radius: 16, color: '#40000000', offsetX: 0, offsetY: 4 })
        }
      }

      /* Settings panel (modal, sprint E) */
      if (this.showSettings) {
        Stack({ alignContent: Alignment.Center }) {
          /* dim background to close the panel */
          Column()
            .width('100%').height('100%')
            .backgroundColor('#66000000')
            .onClick(() => {
              this.showSettings = false;
              console.info('[Reader] Settings panel closed');
            })

          Scroll() {
            Column({ space: 12 }) {
              Text('阅读设置')
                .fontSize(16).fontWeight(FontWeight.Bold)

              Row({ space: 8 }) {
                Text('主题:')
                  .fontSize(13).fontColor('#555')
                ForEach(THEME_NAMES, (name: string, idx: number) => {
                  Button(name)
                    .fontSize(12).height(32)
                    .backgroundColor(this.theme === idx ? '#2a7' : '#e0e0e0')
                    .fontColor(this.theme === idx ? Color.White : '#333')
                    .onClick(() => this.setTheme(idx))
                }, (name: string, idx: number) => `t_${idx}`)
              }

              if (this.isReflow) {
                Row({ space: 8 }) {
                  Text('字号:')
                    .fontSize(13).fontColor('#555')
                  Slider({ value: this.fontSize, min: 10, max: 28, step: 1 })
                    .layoutWeight(1)
                    .onChange((value: number, mode: SliderChangeMode) => {
                      this.fontSize = value;
                      if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
                        this.applyReflow();
                      }
                    })
                  Text(`${this.fontSize}`)
                    .fontSize(11).fontColor('#777').width(30)
                }

                Row({ space: 8 }) {
                  Text('行距:')
                    .fontSize(13).fontColor('#555')
                  Slider({ value: this.lineHeight, min: 1.0, max: 2.5, step: 0.1 })
                    .layoutWeight(1)
                    .onChange((value: number, mode: SliderChangeMode) => {
                      this.lineHeight = value;
                      if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
                        this.applyReflow();
                      }
                    })
                  Text(`${this.lineHeight.toFixed(1)}`)
                    .fontSize(11).fontColor('#777').width(30)
                }

                Row({ space: 8 }) {
                  Text('页边距:')
                    .fontSize(13).fontColor('#555')
                  Slider({ value: this.pageMargin, min: 0, max: 80, step: 4 })
                    .layoutWeight(1)
                    .onChange((value: number, mode: SliderChangeMode) => {
                      this.pageMargin = value;
                      if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
                        this.applyReflow();
                      }
                    })
                  Text(`${this.pageMargin}pt`)
                    .fontSize(11).fontColor('#777').width(36)
                }
              } else {
                Text('PDF 为固定版面：字号/行距/页边距不可调，使用 A-/A+ 缩放')
                  .fontSize(11).fontColor('#888').textAlign(TextAlign.Center)
              }

              Text('主题变更即时生效：深色/OLED 反色渲染，墨色为纸张暖色')
                .fontSize(11).fontColor('#999').textAlign(TextAlign.Center)

              Button('关闭')
                .fontSize(13).height(36)
                .backgroundColor('#f0f0f0').fontColor('#555')
                .onClick(() => {
                  this.showSettings = false;
                  console.info('[Reader] Settings panel closed');
                })
            }
            .width('100%')
            .padding(20)
          }
          .width('85%')
          .height('70%')
          .align(Alignment.Center)
          .backgroundColor(Color.White)
          .borderRadius(12)
          .shadow({ radius: 16, color: '#40000000', offsetX: 0, offsetY: 4 })
        }
      }
    }
    .width('100%')
    .height('100%')
  }
}
