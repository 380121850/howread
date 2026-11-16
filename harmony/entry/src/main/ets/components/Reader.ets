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
import display from '@ohos.display';
import webview from '@ohos.web.webview';
import window from '@ohos.window';
import promptAction from '@ohos.promptAction';
import picker from '@ohos.file.picker';
import pasteboard from '@ohos.pasteboard';
import common from '@ohos.app.ability.common';
import { tlog } from '../model/TestLog';
import { OpenPrefetch, OpenPrefetchEntry } from '../model/OpenPrefetch';
import inputConsumer from '@ohos.multimodalInput.inputConsumer';
import mmiKeyEvent from '@ohos.multimodalInput.keyEvent';
import { systemShare } from '@kit.ShareKit';
import { fileUri } from '@kit.CoreFileKit';
import { importUriToSandbox } from '../model/FileImport';
import {
  version, openDocument, pageCount, renderPageAsync, closeDocument, getToc, getPageSize, saveAccelerator,
  searchText, searchDocument, getText, getAnnotations, addHighlight, addInkStroke, deleteAnnotation, saveDocument,
  addMarkupAnnotation, addTextNote,
  layoutDocument, isReflowable, getDocumentInfo, getTextRects, loadFont,
  needsPassword, authenticateDocument,
  registerRemoteReader, unregisterRemoteReader, remoteReadDone, openDocumentRemoteAsync, docOpAsync,
  RenderResult, RenderOptions, TocEntry, CropRect, TextRect, TextLine, AnnotationInfo, DocumentInfo
} from 'libmupdf_napi.so';
import { saveRecentBook, getPageForPath, getRecentBookForPath, RecentBook } from '../model/ReadingProgress';
import { recordReadTime, recordPagesRead } from '../model/ReadingStats';
import { getBookmarks, addBookmark, removeBookmark, replaceBookmarks, exportBookmarks, recordBookmarkTombstone, Bookmark, BmAnchor } from '../model/Bookmarks';
import { AiConfig, loadAiConfig, chatCompletion, chatCompletionStream, aiConfigReady, AiMessage, lastTruncated} from '../model/AiClient';
import { getNotes, addNote, removeNote, AiNote } from '../model/Notes';
import { getAllPlaylists, createPlaylist, addToPlaylist, PlaylistMeta } from '../model/Playlists';
import { PdfPara, PdfTransSession, BiOverlay, buildPdfParagraphs,
  PDF_TRANS_PANEL, PDF_TRANS_FLOAT, PDF_TRANS_REFLOW, PDF_TRANS_REPLACE } from '../model/PdfTranslate';
import { exportNotesFile, ExportData } from '../model/Export';
import { isBilingualDoc, buildBilingual } from '../model/Bilingual';
import { convertToHtml, isConvertibleExt } from '../model/DocConvert';
import { TextUtils } from '../model/TextUtils';
import { loadSettings, saveSettings, THEME_NAMES, ReaderSettings } from '../model/Settings';
import { startTtsBackgroundTask, stopTtsBackgroundTask, showTtsNotification, cancelTtsNotification, syncTtsCardState } from '../service/TtsService';
import {
  RemoteSession, RemoteFileSource, WebDavFileSource, SmbFileSource, SftpFileSource,
  loadRemoteCacheSettings, parseRemoteUri, resolveWebDavServer, resolveSmbServer, resolveSftpServer,
  fileExtOf, isDirectOpenExt, isPageFormatExt, mobiIsEncrypted, sha256Hex,
  BlockCacheStore, RemoteProbe, readPageTreeSidecar, writePageTreeSidecar, PageTreeSidecar,  materializeBook,
  pinRemoteCacheKey, enforceRemoteCacheQuota, readSmallTextFile, writeSmallTextFile,
  trimOldestFiles } from '../model/RemoteBook';

/* 0.9.33 P2-11: one imported user font (filesDir/fonts copy of a picked
 * TTF/OTF/TTC). Applied through the engine @font-face channel (buildCss). */
interface UserFontRec {
  name: string;
  path: string;
}

const LEFT_ZONE_RATIO: number = 0.3;
const RIGHT_ZONE_RATIO: number = 0.3;
const MIN_ZOOM: number = 0.5;
const MAX_ZOOM: number = 4.0;
const ZOOM_STEP: number = 0.25;
/** Layout page size (points) used for reflowable documents. The width is
 * fixed (type density); the height follows the screen aspect so a page
 * fills the viewport (Android fit-viewport parity, 0.9.61). */
const REFLOW_W: number = 595;
const REFLOW_H: number = 842;

/** Screen-aspect reflow height (portrait口径; falls back to A4). */
function initialReflowH(): number {
  try {
    const d = display.getDefaultDisplaySync();
    let w = d.width;
    let h = d.height;
    if (w > h) {
      const t = w;
      w = h;
      h = t;
    }
    if (w > 0 && h > w) {
      return Math.round(595 * h / w);
    }
  } catch (_e) { /* fall back to A4 */ }
  return 842;
}

/** 0.9.61: one label/value row of the file-info dialog (Android parity) */
interface FiRow {
  label: string;
  value: string;
}
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
    /* Round 8: pageIndices is authoritative for remote-streamed docs too
     * (calling the sync native pageCount would self-deadlock on them) */
    return this.pageIndices.length;
  }

  getDocHandle(): ESObject | undefined {
    return this.docHandle;
  }

  openDoc(path: string, lazyCount: boolean = false, accelPath: string = ''): void {
    try {
      this.docPath = path;
      if (this.docHandle !== undefined) {
        try { closeDocument(this.docHandle); } catch (_e) {}
      }
      /* 0.9.27 P1-5: accelerated open when the caller supplied an
       * accelerator path (falls back inside the binding if it is stale) */
      this.docHandle = openDocument(path, accelPath.length > 0 ? accelPath : undefined);
      /* 0.9.16 staged open: lazyCount skips the (possibly huge) full page
       * count here; the caller grows the page list progressively instead. */
      const total: number = lazyCount ? 0 : pageCount(this.docHandle);
      this.setPageCount(total);
    } catch (e) {
      console.error('[Reader] Failed to open document');
      throw e as Error;
    }
  }

  /** 0.9.34 P2-14: adopt a handle already opened on a worker thread
   * (OpenPrefetch). Mirrors openDoc bookkeeping minus the open cost; the page
   * count starts at 0 and grows through the same staged layout that a
   * lazyCount openDoc would drive. */
  adoptDoc(path: string, handle: ESObject): void {
    this.docPath = path;
    if (this.docHandle !== undefined) {
      try { closeDocument(this.docHandle); } catch (_e) {}
    }
    this.docHandle = handle;
    this.setPageCount(0);
    console.info(`[Reader] Adopted prefetched open: ${path}`);
  }

  /** Replace the page index array and notify the views (0.9.16). */
  setPageCount(total: number): void {
    this.pageIndices = [];
    for (let i = 0; i < total; i++) {
      this.pageIndices.push(i);
    }
    for (let j = 0; j < this.listeners.length; j++) {
      this.listeners[j].onDataReloaded();
    }
  }

  /** Round 8: adopt a handle opened by openDocumentRemoteAsync (remote:// docs). */
  adoptRemote(handle: ESObject, total: number): void {
    this.docPath = '';
    if (this.docHandle !== undefined) {
      try { closeDocument(this.docHandle); } catch (_e) {}
    }
    this.docHandle = handle;
    this.pageIndices = [];
    for (let i = 0; i < total; i++) {
      this.pageIndices.push(i);
    }
    for (let j = 0; j < this.listeners.length; j++) {
      this.listeners[j].onDataReloaded();
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

  getData(index: number): number {
    /* P2-17 (0.9.36): LazyForEach item payload is the page index itself. */
    if (index < 0 || index >= this.pageIndices.length) {
      return 0;
    }
    return this.pageIndices[index];
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
  /* P2-17 (0.9.36): LazyForEach items are created once; only alive cells
   * receive prop updates. Scoped overlays are handed over in full and
   * filtered inside (pageIndex === currentPage / searchHitPage). */
  @Prop @Watch('onBiChanged') currentPage: number = -1;
  @Prop searchHitPage: number = -1;
  @Prop searchHits: TextRect[] = [];
  @Prop annotations: AnnotationInfo[] = [];
  @Prop selPreview: TextRect[] = [];
  /* 0.9.20: PDF bilingual overlays (Android PdfBilingualPageDraw parity).
   * rects are normalized to the full page. P2-10 (0.9.36): the translation
   * text is drawn INTO the page bitmap at composition time (it moves, zooms,
   * rotates and exports with the page by construction); the ArkUI overlay in
   * build() remains only as a device fallback if canvas compositing fails. */
  @Prop @Watch('onBiChanged') biOverlays: BiOverlay[] = [];
  @Prop @Watch('onBiChanged') biMode: number = PDF_TRANS_REFLOW;
  private compositeFailed: boolean = false;
  private composited: boolean = false;
  private baseData: ArrayBuffer | undefined = undefined;
  private baseW: number = 0;
  private baseH: number = 0;
  private viewW: number = 0;
  @Prop biFlash: TextRect[] = [];
  onBiBadge: (idx: number) => void = (idx: number): void => {};
  onPageArea: (x: number, y: number, w: number, h: number) => void =
    (x: number, y: number, w: number, h: number): void => {};
  /* 0.9.34 P2-14: first-render callback (releases the parent's paint gate) */
  onRendered: (page: number, ok: boolean) => void = (p: number, ok: boolean): void => {};
  @State pixelMap: image.PixelMap | undefined = undefined;
  @State isLoading: boolean = true;
  @State errorText: string = '';
  @State renderW: number = 1;
  @State renderH: number = 1;
  /* Round 5: contrast 0..100, 50 = neutral (colorFilter on the page image) */
  @Prop contrastLevel: number = 50;
  /* Night/day fix: invert is pushed from the parent so a theme change re-renders.
   * (the flag alone only affects future renders — this page was rendered before
   *  async initSettings applied the stored theme on cold open) */
  @Prop @Watch('onInvertChanged') nightInvert: boolean = false;

  aboutToAppear(): void {
    this.loadData();
  }

  onInvertChanged(): void {
    this.loadData();
  }

  /* P2-17 (0.9.36): scoped views of the full overlay arrays. */
  private hitsOf(): TextRect[] {
    return this.pageIndex === this.searchHitPage ? this.searchHits : [];
  }

  private annsOf(): AnnotationInfo[] {
    return this.pageIndex === this.currentPage ? this.annotations : [];
  }

  private selsOf(): TextRect[] {
    return this.pageIndex === this.currentPage ? this.selPreview : [];
  }

  private flashesOf(): TextRect[] {
    return this.pageIndex === this.currentPage ? this.biFlash : [];
  }

  private oversOf(): BiOverlay[] {
    return this.pageIndex === this.currentPage ? this.biOverlays : [];
  }

  /* P2-10 (0.9.36): translation overlays that belong in the bitmap. */
  private oversToPaint(): BiOverlay[] {
    if (this.biMode !== PDF_TRANS_REFLOW && this.biMode !== PDF_TRANS_REPLACE) {
      return [];
    }
    return this.oversOf();
  }

  /* P2-10 (0.9.36): translation data/mode/scope changed after the render -
   * recomposite from the retained bitmap, or revert to the plain page. */
  private onBiChanged(): void {
    if (this.baseData === undefined) {
      return;
    }
    const overs: BiOverlay[] = this.oversToPaint();
    if (overs.length > 0) {
      this.compositeTranslation(this.baseData, overs, this.baseW, this.baseH)
        .then((pm: image.PixelMap) => {
          this.composited = true;
          this.pixelMap = pm;
          this.renderW = this.baseW;
          this.renderH = this.baseH;
        })
        .catch((e: Error) => {
          console.warn('[Reader] bi recomposite failed: ' + e.message);
          this.compositeFailed = true;
        });
    } else if (this.composited) {
      this.composited = false;
      const opts: image.InitializationOptions = {
        size: { width: this.baseW, height: this.baseH },
        pixelFormat: image.PixelMapFormat.RGBA_8888
      };
      image.createPixelMap(this.baseData, opts).then((pm: image.PixelMap) => {
        this.pixelMap = pm;
      }).catch((_e: Error) => {});
    }
  }

  /* P2-10 (0.9.36): bake the translation cards into the page bitmap with an
   * offscreen canvas. Styling mirrors the ArkUI overlay it replaces. */
  private async compositeTranslation(data: ArrayBuffer, overs: BiOverlay[],
    w: number, h: number): Promise<image.PixelMap> {
    const opts: image.InitializationOptions = {
      size: { width: w, height: h },
      pixelFormat: image.PixelMapFormat.RGBA_8888
    };
    const base: image.PixelMap = await image.createPixelMap(data, opts);
    const canvas: OffscreenCanvas = new OffscreenCanvas(w, h);
    const ctx = canvas.getContext('2d') as OffscreenCanvasRenderingContext2D;
    ctx.drawImage(base, 0, 0, w, h);
    /* bitmap px per vp: the overlay text sizes were vp-based over the view */
    const scale: number = this.viewW > 0 ? w / this.viewW : 2;
    for (const ov of overs) {
      const x: number = ov.x0 * w;
      const y: number = ov.y0 * h;
      const bw: number = (ov.x1 - ov.x0) * w;
      const bh: number = (ov.y1 - ov.y0) * h;
      ctx.fillStyle = this.nightInvert ? '#121212' : '#ffffff';
      ctx.fillRect(x, y, bw, bh);
      let pad: number = 2 * scale;
      if (this.biMode === PDF_TRANS_REFLOW) {
        ctx.fillStyle = '#1565C0';
        ctx.fillRect(x, y, Math.max(1, 2 * scale), bh);
        pad += 2 * scale;
      }
      ctx.textBaseline = 'top';
      let ty: number = y + 2 * scale;
      if (this.biMode === PDF_TRANS_REFLOW) {
        const srcPx: number = Math.max(8, Math.round(9 * scale));
        ctx.fillStyle = this.nightInvert ? '#8a8a8a' : '#9e9e9e';
        ctx.font = `${srcPx}px sans-serif`;
        ty = this.drawWrapped(ctx, ov.src, x + pad, ty, x + bw - pad, bh + y,
          srcPx * 1.25, 2);
      }
      const dstPx: number = Math.max(10, Math.round(13 * scale));
      ctx.fillStyle = this.nightInvert ? '#e0e0e0'
        : (this.biMode === PDF_TRANS_REPLACE ? '#222222' : '#1565C0');
      ctx.font = `${dstPx}px sans-serif`;
      this.drawWrapped(ctx, ov.dst, x + 2 * scale, ty, x + bw - 2 * scale, bh + y,
        dstPx * 1.25, 8);
    }
    const shot = ctx.getImageData(0, 0, w, h);
    const buf: ArrayBuffer = shot.data.buffer as ArrayBuffer;
    return await image.createPixelMap(buf, opts);
  }

  /* Greedy char-wrap for canvas text; returns the y after the last line. */
  private drawWrapped(ctx: OffscreenCanvasRenderingContext2D, text: string,
    x: number, y: number, right: number, bottom: number, lineH: number,
    maxLines: number): number {
    let line: string = '';
    let ty: number = y;
    let lines: number = 0;
    for (const ch of text) {
      const test: string = line + ch;
      if (ctx.measureText(test).width > right - x && line.length > 0) {
        ctx.fillText(line, x, ty);
        lines++;
        ty += lineH;
        if (lines >= maxLines || ty + lineH > bottom) {
          return ty;
        }
        line = ch;
      } else {
        line = test;
      }
    }
    if (line.length > 0 && lines < maxLines && ty < bottom) {
      ctx.fillText(line, x, ty);
      ty += lineH;
    }
    return ty;
  }

  async loadData(retryDepth: number = 0): Promise<void> {
    if (this.dataSource === undefined) {
      return;
    }
    const benchD0: number = Date.now();
    try {
      this.isLoading = true;
      this.errorText = '';
      let renderResult: RenderResult;
      try {
        renderResult = await this.dataSource.renderPage(this.pageIndex, this.cropRect);
        if (this.pageIndex === 0) {
          tlog('bench_decode', 'ms=' + (Date.now() - benchD0));
        }
      } catch (rendErr) {
        const rendMsg: string = rendErr instanceof Error ? rendErr.message : String(rendErr);
        if (rendMsg.indexOf('CANCELLED') >= 0 && retryDepth < 8) {
          /* Native backlog cap: a newer page turn superseded this render.
           * Keep the loading state and retry quietly - the churn has settled
           * by the time the retry runs (stability fix, 2026-09-21).
           * 2026-11-05: the settle-epoch remap storm on staged chunked books
           * (measured: GBK 5MB txt, ~4s of cancellations) exceeded the old
           * 2-retry cap and left the visible page stuck on 'Render error'
           * while sibling cells rendered the very same page fine.
           * CANCELLED is always supersession, never a real failure: back off
           * 250->1500ms and allow a longer quiet chain before surfacing it. */
          setTimeout(() => {
            this.loadData(retryDepth + 1);
          }, Math.min(250 + 250 * retryDepth, 1500));
          return;
        }
        throw rendErr as Error;
      }
      const opts: image.InitializationOptions = {
        size: { width: renderResult.width, height: renderResult.height },
        pixelFormat: image.PixelMapFormat.RGBA_8888
      };
      /* P2-10 (0.9.36): keep the raw bitmap around so a translation that
       * arrives after the render (or a mode/scope change) can recomposite
       * without hitting the engine again. */
      this.baseData = renderResult.data;
      this.baseW = renderResult.width;
      this.baseH = renderResult.height;
      const overs: BiOverlay[] = this.oversToPaint();
      let composed: image.PixelMap | undefined = undefined;
      if (overs.length > 0) {
        try {
          composed = await this.compositeTranslation(renderResult.data, overs,
            renderResult.width, renderResult.height);
          this.composited = true;
        } catch (e) {
          const em: string = e instanceof Error ? e.message : String(e);
          console.warn(`[Reader] bi composite failed: ${em}`);
          this.compositeFailed = true;
        }
      }
      this.pixelMap = composed !== undefined ? composed
        : await image.createPixelMap(renderResult.data, opts);
      if (this.pageIndex === 0) {
        tlog('bench_first_frame', 'ms=' + (Date.now() - benchD0));
      }
      this.renderW = renderResult.width;
      this.renderH = renderResult.height;
      console.info(`[Reader] Page ${this.pageIndex} rendered OK: ${renderResult.width}x${renderResult.height}`);
      tlog('page_rendered', 'page=' + this.pageIndex + ' ' + renderResult.width + 'x' + renderResult.height);
    } catch (e) {
      this.errorText = 'Render error';
      const msg: string = e instanceof Error ? e.message : String(e);
      console.error(`[Reader] Page ${this.pageIndex} render failed: ${msg}`);
    } finally {
      this.isLoading = false;
      this.onRendered(this.pageIndex, this.errorText.length === 0);
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
          ForEach(this.hitsOf(), (hit: TextRect) => {
            Column()
              .width(`${(hit.x1 - hit.x0) * 100}%`)
              .height(`${(hit.y1 - hit.y0) * 100}%`)
              .position({ x: `${hit.x0 * 100}%`, y: `${hit.y0 * 100}%` })
              .backgroundColor('#33ffeb3b')
          }, (hit: TextRect, idx: number) => `hit_${idx}_${hit.x0}`)

          /* annotation overlays per type (Phase 9: underline/strikeout/squiggly/text) */
          ForEach(this.annsOf(), (ann: AnnotationInfo) => {
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
          ForEach(this.selsOf(), (rect: TextRect, idx: number) => {
            Column()
              .width(`${(rect.x1 - rect.x0) * 100}%`)
              .height(`${(rect.y1 - rect.y0) * 100}%`)
              .position({ x: `${rect.x0 * 100}%`, y: `${rect.y0 * 100}%` })
              .backgroundColor('#3342A5F5')
          }, (rect: TextRect, idx: number) => `sel_${idx}_${rect.x0}_${rect.y0}`)

          /* 0.9.20: tap-to-locate flash (panel mode) */
          ForEach(this.flashesOf(), (fr: TextRect, idx: number) => {
            Column()
              .width(`${(fr.x1 - fr.x0) * 100}%`)
              .height(`${(fr.y1 - fr.y0) * 100}%`)
              .position({ x: `${fr.x0 * 100}%`, y: `${fr.y0 * 100}%` })
              .backgroundColor('#662196F3')
              .border({ width: 1, color: '#2196F3' })
              .hitTestBehavior(HitTestMode.None)
          }, (fr: TextRect, idx: number) => `biflash_${idx}_${fr.x0}`)

          /* 0.9.20: bilingual reflow / in-place replace — text laid over the
           * page image; it moves and zooms with the page naturally */
          if (this.compositeFailed && (this.biMode === PDF_TRANS_REFLOW
            || this.biMode === PDF_TRANS_REPLACE)) {
            /* P2-10 (0.9.36): the translation is normally baked into the page
             * bitmap; this overlay renders only if compositing failed. */
            ForEach(this.oversOf(), (ov: BiOverlay) => {
              Column({ space: 2 }) {
                if (this.biMode === PDF_TRANS_REFLOW) {
                  Text(ov.src)
                    .fontSize(9)
                    .fontColor(this.nightInvert ? '#8a8a8a' : '#9e9e9e')
                    .maxLines(2)
                    .lineHeight(11)
                    .width('100%')
                }
                Text(ov.dst)
                  .fontSize(13)
                  .fontColor(this.nightInvert ? '#e0e0e0'
                    : (this.biMode === PDF_TRANS_REPLACE ? '#222222' : '#1565C0'))
                  .fontWeight(FontWeight.Medium)
                  .maxLines(8)
                  .width('100%')
              }
              .width(`${(ov.x1 - ov.x0) * 100}%`)
              .height(`${(ov.y1 - ov.y0) * 100}%`)
              .position({ x: `${ov.x0 * 100}%`, y: `${ov.y0 * 100}%` })
              .padding(2)
              .alignItems(HorizontalAlign.Start)
              .backgroundColor(this.nightInvert ? '#121212' : '#ffffff')
              .borderRadius(2)
              .border(this.biMode === PDF_TRANS_REFLOW
                ? { width: { left: 2 }, color: { left: '#1565C0' } }
                : { width: 0, color: '#00000000' })
              .hitTestBehavior(HitTestMode.None)
            }, (ov: BiOverlay) => `bio_${ov.idx}_${ov.dst.length}`)
          }

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
    .onAreaChange((_o: Area, n: Area) => {
      try {
        const gp = n.globalPosition;
        const px: number = typeof gp.x === 'number' ? gp.x : 0;
        const py: number = typeof gp.y === 'number' ? gp.y : 0;
        const pw: number = typeof n.width === 'number' ? n.width : 0;
        const ph: number = typeof n.height === 'number' ? n.height : 0;
        this.viewW = pw; /* P2-10 (0.9.36): bitmap-px-per-vp for canvas fonts */
        this.onPageArea(px, py, pw, ph);
      } catch (e) { /* geometry report is best-effort */ }
    })
  }
}

/**
 * TOC sidebar panel.
 */
@Component
struct TocPanel {
  /* 0.9.33: parent-owned entries (was a dead local @State that
   * never got filled - panel always showed 无目录) */
  @Prop tocEntries: TocEntry[] = [];
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
      Text($r('app.string.reader_toc'))
        .fontSize(16).fontWeight(FontWeight.Bold)
        .margin({ bottom: 8 })

      if (this.tocEntries.length === 0) {
        Text($r('app.string.reader_toc_empty'))
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
  @Prop nightInvert: boolean = false;
  @State leftPage: number = 0;
  @State rightPage: number = 0;

  aboutToAppear(): void {
    this.leftPage = this.pageIndex * 2;
    this.rightPage = this.pageIndex * 2 + 1;
  }

  build() {
    Row({ space: 2 }) {
      PageRenderer({ pageIndex: this.leftPage, dataSource: this.dataSource, nightInvert: this.nightInvert })
        .layoutWeight(1)
      if (this.rightPage < this.dataSource.getPageCount()) {
        PageRenderer({ pageIndex: this.rightPage, dataSource: this.dataSource, nightInvert: this.nightInvert })
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
  @Prop nightInvert: boolean = false;
  @State half: number = 0; /* 0=top half, 1=bottom half */
  onHalfChange: (page: number, half: number) => void = () => {};

  build() {
    Column() {
      if (this.half === 0) {
        PageRenderer({ pageIndex: this.pageIndex, dataSource: this.dataSource, cropRect: MUSICIAN_TOP, nightInvert: this.nightInvert })
          .width('100%')
          .height('100%')
      } else {
        PageRenderer({ pageIndex: this.pageIndex, dataSource: this.dataSource, cropRect: MUSICIAN_BOTTOM, nightInvert: this.nightInvert })
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

  /* Round 6: resolve a string resource into a plain string */
  private L(r: Resource): string {
    return getContext(this).resourceManager.getStringSync(r);
  }
  @State currentPage: number = 0;
  @State totalPages: number = 0;
  @State showMenu: boolean = false;
  /* ---- 0.9.59: reader pref dialog (4-tab 偏好) + bar visibility ---- */
  @State showPref: boolean = false;
  @State prefTab: number = 0;              /* 0=可视 1=排版 2=状态栏 3=控制 */
  @State barsVisible: boolean = true;      /* center tap shows/hides both bars */
  @State colorPickTarget: number = 0;      /* 1=link day 2=link night 3=status color */
  @State showFontChips: boolean = false;
  @State showStatusBar: boolean = true;
  @State showTitleTop: boolean = true;
  @State showClockTop: boolean = true;
  @State showBatteryTop: boolean = true;
  @State showProgressBar: boolean = true;
  @State showChapterTicks: boolean = true;
  @State showSubChapterTicks: boolean = false;
  @State seekNav: boolean = true;
  @State statusColorDay: string = '';
  @State statusBarFontPx: number = 14;
  @State progressBarH: number = 4;
  @State progressScale: number = 0;
  @State chapterFmtIdx: number = 0;
  @State bmStripMusician: boolean = true;
  @State bmStripVert: boolean = false;
  @State bmStripHorz: boolean = false;
  @State tapEdgePercent: number = 25;
  @State antialiasLevel: number = 8;
  @State autoOffMinutes: number = 5;
  @State mirrorMode: boolean = false;
  @State allowTextSelection: boolean = true;
  @State perCharSelect: boolean = false;
  @State edgeBrightness: boolean = true;
  @State allowZeroBright: boolean = false;
  @State dayImageReplace: boolean = false;
  @State amoledEnhance: boolean = false;
  @State loopPaging: boolean = false;
  @State zoomLockedPages: boolean = true;
  @State backConfirmClose: boolean = false;
  @State selectionVibrate: boolean = true;
  @State decodeThreads: number = 10;
  @State drawThreads: number = 5;
  @State letterSpacing: number = 0;
  @State hyphenLang: string = 'zh';
  @State hyphenLangDefault: boolean = false;
  @State inlineNotes: boolean = false;
  @State imageZoomEnabled: boolean = true;
  @State imageZoomScale: number = 2.0;
  @State styleMode: number = 2;
  @State userCssName: string = '';
  @State dictLookup: boolean = false;
  @State useSystemBrightness: boolean = false;
  @State invertVolume: boolean = false;
  @State marginT: number = 9;
  @State marginB: number = 6;
  @State marginLeft: number = 10;
  @State marginRight: number = 10;
  @State linkColorDay: string = '';
  @State linkColorNight: string = '';
  @State scrOrient: number = 0;
  @State restRemindMin: number = 0;
  @State fontsFolderDisp: string = '';
  @State tapTopAction: number = 0;         /* top tap zone (vertical mode) */
  @State tapBottomAction: number = 1;      /* bottom tap zone (vertical mode) */
  @State pageList: number[] = [];
  @State docTitle: string = '';
  @State scrollMode: number = 0; /* 0=horizontal Swiper, 1=vertical List */
  @State pageMode: number = 0;   /* 0=single, 1=double, 2=musician */
  @State zoomLevel: number = 1.5;
  /* 2026-09-20 port: the comfortable baseline zoom (Android ADJUST_PAGE target) */
  @State fitZoom: number = 1.5;
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
  @State brightHintShow: boolean = false;   /* left-edge drag: brightness bubble */
  @State brightHintText: string = '';
  @State autoFlip: boolean = false;
  @State autoFlipInterval: number = 3;      /* seconds between flips */
  @State autoScroll: boolean = false;       /* continuous auto-scroll, vertical mode (A) */
  @State autoScrollSpeed: number = 2;       /* px per 50ms tick (2/4/6/8) */
  @State showBookmarks: boolean = false;    /* bookmark list panel */
  @State bookmarks: Bookmark[] = [];
  /* ---- Phase 10: AI 大模型接入 (hr13/17/18 parity) ---- */
  @State showTrans: boolean = false;        /* AI 翻译 dialog (hr18) */
  @State biRemoteReady: boolean = false;     /* remote book fully cached (bilingual ok) */
  @State transSrcIdx: number = 0;
  @State transDstIdx: number = 1;
  @State transSave: boolean = true;
  @State transBusy: boolean = false;
  @State transResult: string = '';
  @State showTransPanel: boolean = false;   /* bottom sheet with the translation */
  @State aiNotes: AiNote[] = [];            /* AI notes for the bookmark panel (hr17) */
  @State bmOnPage: boolean = false;         /* 0.9.61: current page bookmarked (🔖 highlight) */
  @State showBmList: boolean = false;       /* 0.9.62: bookmark dialog (Android dialogShowBookmarks parity) */
  @State showBmAdd: boolean = false;        /* 0.9.62: named-bookmark input dialog */
  @State showBmSort: boolean = false;       /* 0.9.62: ⋮ sort/share menu of the bookmark dialog */
  @State bmSearch: string = '';             /* 0.9.62: bookmark dialog search query */
  @State bmAddName: string = '';            /* 0.9.62: named-bookmark input */
  @State bmSortOrder: number = 0;           /* 0.9.62: 0 newest-first / 1 oldest-first / 2 page asc / 3 page desc */
  @State showExportDialog: boolean = false; /* notes/bookmarks export format picker (A) */
  @State transInPage: boolean = false;      /* in-page bilingual toggle (D, EPUB/TXT only) */
  private biOriginalPath: string = '';      /* set while reading the rebuilt bilingual file */
  private transToken: number = 0;           /* dialog generation: stale stream results are dropped */
  /* 0.9.20: PDF AI translation — four modes (Android 10-01/10-02 parity) */
  @State pdfBiActive: boolean = false;
  @State pdfBiMode: number = 0;             /* 0 panel 1 float 2 reflow 3 replace */
  @State pdfBiParas: BiOverlay[] = [];      /* translated overlays for the current page */
  @State pdfBiFlash: TextRect[] = [];       /* tap-to-locate highlight */
  @State showPdfBiPanel: boolean = false;   /* comparison panel bottom sheet */
  @State pdfBiCard: BiOverlay | undefined = undefined; /* float badge card */
  @State pdfBiTick: number = 0;             /* bumps panel/overlay re-render */
  private pdfSession: PdfTransSession | undefined = undefined;
  private pdfParasByPage: Map<number, PdfPara[]> = new Map();
  @State pdfBadgeMarks: BiOverlay[] = [];   /* root-level float badges */
  @State pdfPageArea: number[] = [0, 457, 1320, 2492]; /* x0,y0,x1,y1 px */
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
  /* 0.9.60 (Android ShareDialog/NoteEditDialog parity): bottom-bar book menu,
   * note editor, playlist picker, AI intro */
  @State showBookMenu: boolean = false;    /* ShareDialog-style book menu (bottom-bar ⋮) */
  @State showNoteEdit: boolean = false;    /* NoteEditDialog (bottom-bar ✏) */
  @State noteEditText: string = '';
  @State showPlPick: boolean = false;      /* add-to-playlist picker */
  @State plNames: string[] = [];
  @State plNewName: string = '';
  @State showAiIntro: boolean = false;     /* AI book intro dialog */
  @State aiIntroText: string = '';
  @State aiIntroBusy: boolean = false;
  @State showJump: boolean = false;         /* jump-to-page dialog */
  @State jumpInput: string = '';            /* target page typed by the user */
  @State fastScrolling: boolean = false;    /* vertical fast-scroll rail active */
  private fastStartPage: number = 0;
  /* Round 6: encrypted-document unlock */
  @State showPwd: boolean = false;          /* password dialog */
  @State pwdInput: string = '';
  @State renderEpoch: number = 0;           /* bumped after unlock to force re-render */
  private aiCfg: AiConfig = { protocol: 'openai', provider: '', url: '', key: '', model: '', maxTokens: 8192, thinking: false };
  /* 0.9.61: reflow layout viewport (width fixed, height = screen aspect) */
  private reflowW: number = 595;
  private reflowH: number = initialReflowH();
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
  @State ttsStatus: string = this.L($r('app.string.tts_ready'));
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
  @State theme: number = 0;             /* 0=light 1=dark 2=oled 3=ink */
  @State isReflow: boolean = false;     /* reflowable (EPUB/HTML) vs fixed (PDF) */
  @State fontSize: number = 16;         /* em, reflowable only */
  @State lineHeight: number = 1.6;      /* CSS line-height, reflowable only */
  @State pageMargin: number = 20;       /* CSS body margin pt, reflowable only */
  private swiperCtrl: SwiperController = new SwiperController();
  /* 0.9.22 fix3: true once the current open finished; Swiper events that
   * arrive earlier (empty-list init onChange(0)) would wipe the restored
   * reading position. */
  private openSettled: boolean = false;
  /* 0.9.23 (Android 10-09 parity): true once the FULL page count of the
   * current layout era is known (phase two done / full layout / reflow).
   * Until then this.totalPages is only a moving window end. */
  private layoutComplete: boolean = false;
  /* 0.9.23: pending deep-jump waits (lands when the canvas has grown).
   * 0.9.65: an array — the old single jumpWaitTimer handle was overwritten
   * when a second wait started, silently dropping the first wait's
   * completion and leaking its interval. */
  private jumpWaitTimers: number[] = [];
  @State reflowVersion: number = 0;

  // ---- Sprint G6: File info dialog ----
  @State showFileInfo: boolean = false;
  @State fiRows: FiRow[] = [];              /* 0.9.61: structured rows (Android dialog_file_info parity) */
  @State fiCover: image.PixelMap | undefined = undefined;
  @State fiCoverPath: string = '';         /* 0.9.61: shelf cover PNG (preferred) */

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
  @State textAlign: number = 0; /* 0=left, 1=center, 2=right, 3=justify */
  @State paragraphSpacing: number = 8;
  @State hyphenationEnabled: boolean = false;
  /* Sprint L: full CSS typography */
  @State textIndent: number = 2;
  @State dropCapEnabled: boolean = false;
  @State fontFamilyIdx: number = 0; /* 0=default,1=serif,2=sans-serif,3=monospace */
  /* 0.9.33 P2-11: user fonts (applied via @font-face; empty = built-ins) */
  @State userFontPath: string = '';
  @State userFontName: string = '';
  @State userFontList: string = '[]';
  @State userFonts: UserFontRec[] = [];
  @State cropEnabled: boolean = false; /* H6: 10% margin trim */

  // ---- Sprint I: Advanced reading features ----
  @State isFullscreen: boolean = false;       /* I1: immersive mode */
  @State blueLightFilter: number = 0;         /* I5: 0=off, 1-100=intensity % */
  @State bgImagePath: string = '';            /* I6: custom background image path */
  @State bgImageAlpha: number = 30;           /* I6: background opacity 0-100 */
  @State rtlEnabled: boolean = false;         /* I2: right-to-left reading direction */
  /* Round 9: unified tap-zone action codes (shared across zones):
   * 0=prev 1=next 2=menu 3=toc 4=bookmark 5=brightness 6=fullscreen 7=none */
  @State tapLeftAction: number = 0;           /* default: previous page */
  /* Round 7: prefs long-press action: 0=text selection, 1=file info, 2=menu, 3=none */
  @State longTapActionPref: number = 0;
  /* 0.9.55: 阅读配置 alignment — touch lock + double-tap action */
  @State inputLocked: boolean = false;
  @State doubleClickPref: number = 0;
  @State tapCenterAction: number = 2;         /* default: toggle menu */
  @State tapRightAction: number = 1;          /* default: next page */
  /* Round 9: logical source path — set when docx/rtf was converted to HTML,
   * so reading progress/bookmarks keep the ORIGINAL file as their key */
  private logicalPath: string = '';

  /* Round 9: volume keys: 0=off, 1=page turn, 2=zoom (auto-scroll speed while scrolling) */
  private volumeKeyAction: number = 1;
  @State showTextReplace: boolean = false;    /* I7: text replacement panel */
  @State replaceFrom: string = '';            /* I7: search text */
  @State replaceTo: string = '';              /* I7: replacement text */
  @State einkDetected: boolean = false;       /* I8: E-ink device auto-detect */
  @State renderQualityLevel: number = 1;      /* K4: 0=fast 1=balanced 2=high */

  // ---- Round 8: remote (online cache reading) ----
  @State isRemote: boolean = false;           /* docPath is a remote:// streamed book */
  @State remoteOpening: boolean = false;      /* streaming open in progress */
  @State firstPaintArmed: boolean = false;      /* 0.9.34 P2-14: first-paint gate overlay */
  private firstPaintTimer: number = -1;
  /* 0.9.25 P0-3: P3 filler progress for the bottom banner (-1 = idle) */
  @State remoteFillPercent: number = -1;
  /* 0.9.26 P0-1 tail: "whole book cached, readable offline" fires once */
  private offlineHintShown: boolean = false;
  /* 0.9.27 P1-8: remote TOC cache key (uri#size#versionTag), set at open */
  private remoteTocKey: string = '';
  @State remoteOpenMsg: string = '';          /* open progress text */
  private remoteUri: string = '';
  private remoteReaderId: number = -1;
  private remoteSession: RemoteSession | undefined = undefined;
  /* P1-9 (0.9.35): pagemap convergence intent for a streaming remote pdf
   * opened without a sidecar; fires once the background block filler has
   * cached the whole book, so op13's page-tree walk is all cache hits. */
  private pmConvArmed: boolean = false;
  private pmConvFired: boolean = false;
  private pmConvHandle: number = -1;
  private pmConvKey: string = '';
  private pmConvSize: number = 0;
  private pmConvTag: string = '';
  /* 0.9.16 staged open: bump to cancel the running phase2 layout loop */
  private stageGen: number = 0;

  /** Generic async document op for remote-streamed documents. */
  private async docOp(op: number, a?: number | string, b?: number, c?: number, s?: string): Promise<string | undefined> {
    const h: ESObject | undefined = this.dataSource.getDocHandle();
    if (h === undefined) {
      return undefined;
    }
    const t0: number = Date.now();
    try {
      const r: string | undefined = await docOpAsync(h, op, a, b, c, s);
      tlog('docop_done', 'op=' + op + ' ms=' + (Date.now() - t0));
      return r;
    } catch (e) {
      tlog('docop_fail', 'op=' + op + ' ms=' + (Date.now() - t0));
      return undefined;
    }
  }

  /** Round 8: open a remote:// book through the block-cache stream bridge. */
  private async initRemoteReader(uri: string): Promise<void> {
    this.isRemote = true;
    this.remoteUri = uri;
    this.remoteOpening = true;
    this.remoteOpenMsg = '连接服务器...';
    try {
      const ctx = getContext(this);
      const parts = parseRemoteUri(uri);
      if (parts === null || (parts.type !== 'webdav' && parts.type !== 'smb' && parts.type !== 'sftp')) {
        throw new Error('bad remote uri');
      }
      const settings = await loadRemoteCacheSettings(ctx);
      this.remoteOpenMsg = '加载缓存...';
      let source: RemoteFileSource;
      if (parts.type === 'webdav') {
        const server = await resolveWebDavServer(ctx, parts.serverId);
        if (server === null) {
          throw new Error('远程服务器配置不存在，请重新添加');
        }
        source = new WebDavFileSource(parts.fileUrl, server.user, server.password,
          settings.retryCount, settings.retryIntervalMs);
      } else if (parts.type === 'smb') {
        /* parts.fileUrl = '<host>[:<port>]|<share>|<path-on-share>' */
        const segs: string[] = parts.fileUrl.split('|');
        if (segs.length < 3) {
          throw new Error('bad smb uri');
        }
        const server = await resolveSmbServer(ctx, parts.serverId);
        if (server === null) {
          throw new Error('远程服务器配置不存在，请重新添加');
        }
        source = new SmbFileSource(segs[0], segs[1], segs[2], server.user, server.domain, server.password);
      } else {
        /* sftp: parts.fileUrl = '<host>[:<port>]|<absolute-path>' */
        const segs: string[] = parts.fileUrl.split('|');
        if (segs.length < 2) {
          throw new Error('bad sftp uri');
        }
        const server = await resolveSftpServer(ctx, parts.serverId);
        if (server === null) {
          throw new Error('远程服务器配置不存在，请重新添加');
        }
        const hp: string[] = segs[0].split(':');
        source = new SftpFileSource(hp[0], hp.length > 1 ? parseInt(hp[1], 10) : 22,
          server.user, server.password, server.keyPath, server.keyPass,
          server.trustAll, server.fingerprint);
        (source as SftpFileSource).openPath = segs[1];
      }
      const session = new RemoteSession(ctx, uri, await sha256Hex(uri), source, settings);
      /* Round 8: fully-cached books open with zero network (Android openOffline);
       * otherwise a failed probe aborts the open */
      const cacheKey: string = await sha256Hex(uri);
      const cached = await BlockCacheStore.readMeta(ctx, cacheKey);
      let probe: RemoteProbe;
      if (cached !== null && cached.fullyCached) {
        await session.store.loadOrCreate(cached.size, cached.versionTag, cached.blockSize);
        probe = { size: cached.size, versionTag: cached.versionTag, supportsRange: true };
        console.info('[Reader] Remote open from local cache (fully cached)');
      } else {
        probe = await session.open();
      }
      this.remoteTocKey = `${uri}#${probe.size}#${probe.versionTag}`;
      this.remoteSession = session;
      this.remoteReaderId = registerRemoteReader((seq: number, offset: number, len: number) => {
        session.readAt(offset, len).then((buf: ArrayBuffer) => {
          remoteReadDone(this.remoteReaderId, seq, buf);
        }).catch((err: Error) => {
          console.error(`[Reader] remote read FAIL off=${offset} len=${len}: ${err.message}`);
          remoteReadDone(this.remoteReaderId, seq, undefined);
        });
      });
      this.remoteOpenMsg = '解析文档...';
      const ext: string = fileExtOf(parts.fileUrl);
      const magic: string = isDirectOpenExt(ext) ? ext : (isPageFormatExt(ext) ? ext : '');
      const css: string = `body{margin:${Math.round(this.pageMargin)}pt;line-height:${this.lineHeight.toFixed(2)}}`;
      /* 0.9.25 P0-3: the cap that existed in settings since 0.9.6 without a
       * consumer - enforce it now that this book's cache is hot, pinning the
       * book being opened */
      pinRemoteCacheKey(cacheKey);
      enforceRemoteCacheQuota(ctx, settings.cacheMaxMB, cacheKey).catch((e: Error): void => {
        console.warn('[Reader] cache quota failed: ' + e.message);
      });
      /* 0.9.25 P0-2 remote lazy layout (Android remote fast-open parity):
       * lazy=true defers the open-time count+layout for reflow docs -
       * fz_count_pages over a stream lays out the WHOLE book before the
       * first frame. meta comes back pageCount=-1 and the staged block
       * below counts the first window via docOp(15) - the exact local
       * openDoc model. Fixed-format docs keep the cheap full count. */
      const res: ESObject = await openDocumentRemoteAsync(this.remoteReaderId, magic, probe.size, true,
        this.reflowW, this.reflowH, this.fontSize, css, true);
      const rec: Record<string, ESObject> = res as Record<string, ESObject>;
      const meta: Record<string, ESObject> = JSON.parse(String(rec['meta'])) as Record<string, ESObject>;
      let total: number = Number(meta['pageCount']);
      const reflowNow: boolean = meta['isReflow'] === true;
      if (total < 0 && reflowNow) {
        /* staged remote open: first window + shared phase2 (+400/round) and
         * the settle epoch machinery already used by the local path */
        this.dataSource.adoptRemote(rec['handle'], 0);
        if (meta['needsPassword'] === true) {
          this.showPwd = true;
        }
        session.onFillerProgress = (p: number): void => {
          this.remoteFillPercent = p >= 100 ? -1 : p;
          if (p >= 100 && !this.offlineHintShown) {
            this.offlineHintShown = true;
            promptAction.showToast({ message: '整本已缓存，离线也可阅读' });
          }
        };
        session.maybeStartFiller();
        /* 0.9.33 P2-11: full typography css (incl. user font) before the
         * staged count - the open css only carried margin/line-height */
        try {
          await this.docOp(16, 0, 0, 0, this.buildCss());
        } catch (_e) { /* best effort */ }
        const gen: number = ++this.stageGen;
        const target: number = Math.max(0, this.currentPage);
        this.layoutComplete = false;
        const prog: string | undefined = await this.docOp(15, this.reflowW, this.reflowH, this.fontSize,
          String(target + 400));
        const n1: number = parseInt(prog === undefined ? '-1' : prog);
        tlog('bench_stage1', 'remote=1 n1=' + n1 + ' from=' + target);
        if (n1 >= 0) {
          this.totalPages = n1;
          this.dataSource.setPageCount(n1);
          this.rebuildPageList();
          this.currentPage = Math.min(this.currentPage, Math.max(0, n1 - 1));
          this.loadBookmarks();
          this.refreshAnnotations();
          this.remoteOpening = false;
          this.openSettled = true;
          tlog('book_open_done', 'remote=1 pages=' + n1 + ' staged=1');
          console.info(`[Reader] Remote doc opened (staged): ${n1} pages`);
          this.landOnRestoredPage();
          this.stagedOpen = true;
          this.startPhaseTwo(gen);
          return;
        }
        /* no chapter support (odd reflow doc): one full layout+count */
        const full: string | undefined = await this.docOp(3, this.reflowW, this.reflowH, this.fontSize, css);
        const nf: number = parseInt(full === undefined ? '-1' : full);
        console.info(`[Reader] Remote staged fallback full count: ${nf}`);
        total = nf >= 0 ? nf : 0;
        this.loadToc();
      }
      this.dataSource.adoptRemote(rec['handle'], total);
      /* 2026-09-20 port: persisted page-tree map (Android MuPdfDocument parity).
       * A matching sidecar re-opens a cached remote pdf with zero page-tree
       * traffic; a fully-cached book without one builds it from cache hits. */
      if (ext === 'pdf') {
        const pmHandle: number = rec['handle'] as number;
        const pmTag: string = cached !== null ? cached.versionTag : probe.versionTag;
        console.info(`[Reader] pagemap flow: ext=${ext} cached=${cached !== null ? cached.fullyCached : 'null'}`);
        /* op 13/14 run on the docOpAsync worker: the lazy-tree walk reads
         * through the remote stream, whose callbacks need the JS thread -
         * synchronous NAPI calls here would self-deadlock (appfreeze). */
        readPageTreeSidecar(ctx, cacheKey, probe.size, pmTag).then(async (pm: PageTreeSidecar | null) => {
          if (pm !== null) {
            await docOpAsync(pmHandle, 14, 0, 0, 0, JSON.stringify({ nums: pm.nums, sizes: pm.sizes }));
            console.info(`[Reader] pagemap injected: ${pm.nums.length} pages`);
          } else if (cached !== null && cached.fullyCached) {
            const raw: string = await docOpAsync(pmHandle, 13);
            const j: Record<string, ESObject> = JSON.parse(raw) as Record<string, ESObject>;
            const nums: ESObject = j['nums'];
            if (nums !== null && nums !== undefined && nums.length > 0) {
              const sizes: ESObject = j['sizes'];
              writePageTreeSidecar(ctx, cacheKey, probe.size, pmTag,
                nums as number[], sizes !== null && sizes !== undefined ? sizes as number[] : []);
              console.info(`[Reader] pagemap saved: ${nums.length} pages`);
            } else {
              console.info('[Reader] pagemap build: engine returned no map');
            }
          } else {
            /* P1-9 (0.9.35): streaming pdf without a pagemap sidecar - arm
             * background convergence: when the block filler finishes caching
             * the whole book, op13's one page-tree walk is all cache hits
             * (sub-second even for 1000-page books) and the full nums+sizes
             * map is persisted, so the NEXT open of this book skips all
             * tree traffic (Android lazy-sizes convergence parity). */
            this.pmConvArmed = true;
            this.pmConvFired = false;
            this.pmConvHandle = pmHandle;
            this.pmConvKey = cacheKey;
            this.pmConvSize = probe.size;
            this.pmConvTag = pmTag;
          }
        }).catch((err: Error) => {
          console.error(`[Reader] pagemap flow failed: ${err.message}`);
        });
      }
      this.isReflow = meta['isReflow'] === true;
      this.totalPages = total;
      this.pageList = [];
      for (let i = 0; i < total; i++) {
        this.pageList.push(i);
      }
      if (meta['needsPassword'] === true) {
        this.showPwd = true;
      }
      const tocArr: ESObject = meta['toc'];
      this.tocEntries = (tocArr !== undefined && tocArr !== null) ? tocArr as TocEntry[] : [];
      this.loadBookmarks();
      this.refreshAnnotations();
      /* P3: whole-book background fill for small books. 0.9.25 P0-3: show
       * the filler progress in the bottom banner (Android parity) */
      session.onFillerProgress = (p: number): void => {
        this.remoteFillPercent = p >= 100 ? -1 : p;
        if (p >= 100 && !this.offlineHintShown) {
          this.offlineHintShown = true;
          promptAction.showToast({ message: '整本已缓存，离线也可阅读' });
        }
        if (p >= 100) {
          this.maybeConvergePagemap(); /* P1-9 (0.9.35) */
        }
      };
      session.maybeStartFiller();
      console.info(`[Reader] Remote doc opened: ${total} pages, reflow=${this.isReflow}, size=${probe.size}`);
    } catch (e) {
      const msg: string = e instanceof Error ? e.message : String(e);
      console.error(`[Reader] Remote open failed: ${msg}`);
      promptAction.showToast({ message: `远程打开失败：${msg}` });
      this.remoteOpening = false;
      router.back();
      return;
    }
    this.remoteOpening = false;
    console.info(`[Reader] Remote document opened: ${this.totalPages} pages, page ${this.currentPage + 1}`);
    this.openSettled = true;
    this.layoutComplete = true; /* remote opens are fully laid out */
    tlog('book_open_done', 'remote=1 pages=' + this.totalPages);
    this.landOnRestoredPage();
  }

  aboutToAppear(): void {
    try {
      const ctx = getContext(this);
      /* Round 9: subscribe the volume keys while the reader is open
       * (Android enable_volume_keys; API 16+ inputConsumer). */
      this.registerVolumeKeys();
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

        /* Round 6: optional direct jump page from library full-text search hits.
         * Round 7: alwaysPage1 pref forces page 1 and ignores saved progress. */
        const jump: number = params !== null && params !== undefined && params['jumpPage'] !== undefined
          ? parseInt(String(params['jumpPage']), 10) : -1;
        const alwaysP1: boolean = params['alwaysPage1'] === '1';
        getPageForPath(ctx, this.documentPath).then((savedPage: number) => {
          this.currentPage = jump >= 0 ? jump : (alwaysP1 ? 0 : savedPage);
          console.info(`[Reader] Restoring page ${this.currentPage + 1}`);
          this.initOrRemote();
        }).catch(() => {
          if (jump >= 0) {
            this.currentPage = jump;
          }
          this.initOrRemote();
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

  /** 0.9.34 P2-14 (Android FirstPaintGate parity): a solid branded opening
   * screen until the first page has actually rendered, so a fresh open never
   * flashes a blank page area. A hard 12s cap releases the gate no matter
   * what, so a slow open can never wedge the reader. */
  private armFirstPaint(): void {
    this.firstPaintArmed = true;
    if (this.firstPaintTimer !== -1) {
      clearTimeout(this.firstPaintTimer);
    }
    this.firstPaintTimer = setTimeout(() => {
      this.releaseFirstPaint('cap');
    }, 12000);
    tlog('first_paint_arm', 'doc=' + this.documentPath.substring(this.documentPath.lastIndexOf('/') + 1));
  }

  private releaseFirstPaint(reason: string): void {
    if (!this.firstPaintArmed) {
      return;
    }
    this.firstPaintArmed = false;
    if (this.firstPaintTimer !== -1) {
      clearTimeout(this.firstPaintTimer);
      this.firstPaintTimer = -1;
    }
    tlog('first_paint_release', 'reason=' + reason);
  }

  /** Load persisted settings and apply them to the runtime state. */
  private async initSettings(ctx: Context): Promise<void> {
    const s: ReaderSettings = await loadSettings(ctx);
    this.applySettings(s);
    this.settingsLoaded = true;
    this.pdfBiMode = s.pdfTransMode >= 0 && s.pdfTransMode <= 3 ? s.pdfTransMode : 0;
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
      this.fitZoom = this.zoomLevel;
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
    /* 0.9.33 P2-11: font family + user font readback (fontFamily was
     * persisted but never applied on load before this) */
    {
      const fams: string[] = ['default', 'serif', 'sans-serif', 'monospace'];
      const fi: number = fams.indexOf(s.fontFamily);
      this.fontFamilyIdx = fi >= 0 ? fi : 0;
      this.userFontPath = typeof s.userFontPath === 'string' ? s.userFontPath : '';
      this.userFontName = typeof s.userFontName === 'string' ? s.userFontName : '';
      this.userFontList = typeof s.userFontList === 'string' ? s.userFontList : '[]';
      this.refreshUserFonts();
    }
    this.blueLightFilter = s.blueLightFilter;
    this.bgImagePath = s.bgImagePath;
    /* Round 9: tap-zone actions from the gesture panel (unified codes);
     * prefs singleTapAction still overrides the center zone like Android
     * readingMode: 0 = reader-panel default (keep the panel value),
     * 1 = menu toggle, 2 = no action */
    this.tapLeftAction = s.tapLeftAction;
    this.tapRightAction = s.tapRightAction;
    this.tapCenterAction = s.tapCenterAction;
    if (s.singleTapAction === 1) {
      this.tapCenterAction = 2;
    } else if (s.singleTapAction === 2) {
      this.tapCenterAction = 7;
    }
    this.longTapActionPref = s.longTapAction;
    this.volumeKeyAction = s.volumeKeyAction;
    /* 0.9.55: 阅读配置 alignment (Android lockBooksByDefault / doubleClickAction1 /
     * readingMode SELECT_MODE ask-on-open) */
    this.doubleClickPref = s.doubleClickAction;
    this.inputLocked = s.lockBooksByDefault;
    /* 0.9.59: 4-tab reader pref fields */
    this.showStatusBar = s.showStatusBar;
    this.showTitleTop = s.showTitleTop;
    this.showClockTop = s.showClockTop;
    this.showBatteryTop = s.showBatteryTop;
    this.showProgressBar = s.showProgressBar;
    this.showChapterTicks = s.showChapterTicks;
    this.showSubChapterTicks = s.showSubChapterTicks;
    this.seekNav = s.seekNav;
    this.statusColorDay = s.statusColorDay;
    this.statusBarFontPx = s.statusBarFontPx;
    this.progressBarH = s.progressBarH;
    this.progressScale = s.progressScale;
    this.chapterFmtIdx = s.chapterFmtIdx;
    this.bmStripMusician = s.bmStripMusician;
    this.bmStripVert = s.bmStripVert;
    this.bmStripHorz = s.bmStripHorz;
    this.tapEdgePercent = s.tapEdgePercent;
    this.antialiasLevel = s.antialiasLevel;
    this.autoOffMinutes = s.autoOffMinutes;
    this.mirrorMode = s.mirrorMode;
    this.allowTextSelection = s.allowTextSelection;
    this.perCharSelect = s.perCharSelect;
    this.edgeBrightness = s.edgeBrightness;
    this.allowZeroBright = s.allowZeroBright;
    this.dayImageReplace = s.dayImageReplace;
    this.amoledEnhance = s.amoledEnhance;
    this.loopPaging = s.loopPaging;
    this.zoomLockedPages = s.zoomLockedPages;
    this.backConfirmClose = s.backConfirmClose;
    this.selectionVibrate = s.selectionVibrate;
    this.decodeThreads = s.decodeThreads;
    this.drawThreads = s.drawThreads;
    this.textAlign = s.textAlign;
    this.letterSpacing = s.letterSpacing;
    this.paragraphSpacing = s.paragraphSpacing;
    this.hyphenationEnabled = s.hyphenationEnabled;
    this.hyphenLang = s.hyphenLang;
    this.hyphenLangDefault = s.hyphenLangDefault;
    this.inlineNotes = s.inlineNotes;
    this.imageZoomEnabled = s.imageZoomEnabled;
    this.imageZoomScale = s.imageZoomScale;
    this.styleMode = s.styleMode;
    this.userCssName = s.userCssName;
    this.dictLookup = s.dictLookup;
    this.useSystemBrightness = s.useSystemBrightness;
    this.invertVolume = s.invertVolume;
    this.marginT = s.marginT;
    this.marginB = s.marginB;
    this.marginLeft = s.marginLeft;
    this.marginRight = s.marginRight;
    this.linkColorDay = s.linkColorDay;
    this.linkColorNight = s.linkColorNight;
    this.scrOrient = s.screenOrientation;
    this.restRemindMin = s.readReminderMinutes;
    this.fontsFolderDisp = s.fontsFolder;
    this.tapTopAction = s.tapTopAction;
    this.tapBottomAction = s.tapBottomAction;
    if (s.readingModePref === 0) {
      setTimeout(() => this.askReadingMode(), 500);
    }
    this.dataSource.setZoom(this.zoomLevel);
    this.dataSource.setInvert(this.nightMode);
    if (this.brightnessLevel > 0 && !this.useSystemBrightness) {
      try {
        brightness.setValue({ value: this.brightnessLevel });
      } catch (e) {
        /* brightness may be unavailable on some devices */
      }
    }
  }

  /** 0.9.55: ask-on-open reading mode (Android READING_MODE_SELECT_MODE). */
  private askReadingMode(): void {
    AlertDialog.show({
      title: '选择阅读模式',
      message: '选择本次阅读的翻页方式（可在 偏好-阅读配置 里改为固定）',
      primaryButton: {
        value: '上下滚动',
        action: () => {
          /* 0.9.65: same position carry as toggleScrollMode — answering
           * after the book has landed (the normal case) otherwise leaves
           * the List at page 1 and rewrites the saved progress. */
          const keep: number = this.currentPage;
          this.scrollMode = 1;
          if (this.totalPages > 0) {
            this.landAfterModeSwitch(keep);
          }
          console.info('[Reader] Session reading mode: vertical scroll');
        }
      },
      secondaryButton: {
        value: '左右翻页',
        action: () => {
          this.scrollMode = 0;
          console.info('[Reader] Session reading mode: horizontal pages');
        }
      }
    });
  }

  /** guard: true once initSettings has applied stored settings (prevents a
   *  too-early persist from overwriting stored values with initial defaults). */
  private settingsLoaded: boolean = false;

  /** Persist the current runtime settings (called on exit and panel changes).
   *  Round 7: merge over the stored settings so Index-managed fields
   *  (lock, proxy, library, tabs, colors, …) survive reader-side saves. */
  private async persistSettings(): Promise<void> {
    if (!this.settingsLoaded) {
      return;
    }
    const ctx = getContext(this);
    const cur: ReaderSettings = await loadSettings(ctx);
    /* store the user zoom without the render-quality multiplier
     * (applySettings multiplies it back on load, else it snowballs) */
    const qualityMults: number[] = [0.75, 1.0, 1.5];
    const qm: number = qualityMults[this.renderQualityLevel] !== undefined ? qualityMults[this.renderQualityLevel] : 1.0;
    const s: ReaderSettings = {
      ttsFolder: cur.ttsFolder,
      theme: this.theme,
      zoom: qm > 0 ? this.zoomLevel / qm : this.zoomLevel,
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
      appLockEnabled: cur.appLockEnabled,
      appLockPass: cur.appLockPass,
      readReminderMinutes: this.restRemindMin,
      blueLightFilter: this.blueLightFilter,
      bgImagePath: this.bgImagePath,
      proxyHost: cur.proxyHost,
      proxyPort: cur.proxyPort,
      proxyType: cur.proxyType,
      renderQuality: this.renderQualityLevel,
      ttsVoiceIndex: this.ttsVoiceIndex,
      ttsLang: this.ttsLang,
      textIndent: this.textIndent,
      dropCapEnabled: this.dropCapEnabled,
      fontFamily: ['default', 'serif', 'sans-serif', 'monospace'][this.fontFamilyIdx],
      userFontPath: this.userFontPath,
      userFontName: this.userFontName,
      userFontList: this.userFontList,
      flipAnimation: cur.flipAnimation,
      tabPositionTop: cur.tabPositionTop,
      showTopBarInfo: this.showTopBarInfo,
      appLockBiometric: cur.appLockBiometric,
      screenOrientation: this.scrOrient,
      autoScanOnStart: cur.autoScanOnStart,
      hideReadBooks: cur.hideReadBooks,
      coverColumns: cur.coverColumns,
      cardSource: cur.cardSource,
      cardCount: cur.cardCount,
      appLang: cur.appLang,
      scanFormats: cur.scanFormats,
      remoteBatchDir: cur.remoteBatchDir,
      pdfTransMode: this.pdfBiMode,
      debugLogExportPath: cur.debugLogExportPath,
      coverListSize: cur.coverListSize,
      coverGridSize: cur.coverGridSize,
      showBookCovers: cur.showBookCovers,
      coverEffect: cur.coverEffect,
      cropCovers: cur.cropCovers,
      coverBorderShadow: cur.coverBorderShadow,
      showBookDescription: cur.showBookDescription,
      authorSurnameFirst: cur.authorSurnameFirst,
      skipNoMediaFolders: cur.skipNoMediaFolders,
      singleTapAction: cur.singleTapAction,
      longTapAction: cur.longTapAction,
      openLastBook: cur.openLastBook,
      alwaysPage1: cur.alwaysPage1,
      showExitDialog: cur.showExitDialog,
      fullscreen: cur.fullscreen,
      appFontScale: cur.appFontScale,
      themeColor: cur.themeColor,
      accentColor: cur.accentColor,
      accentTextColor: cur.accentTextColor,
      tabIconOnly: cur.tabIconOnly,
      showPrefsInDrawer: cur.showPrefsInDrawer,
      tabsOrder: cur.tabsOrder,
      rootFolder: cur.rootFolder,
      fontsFolder: cur.fontsFolder,
      downloadsFolder: cur.downloadsFolder,
      remoteOnlineFirst: cur.remoteOnlineFirst,
      remoteCacheMaxMB: cur.remoteCacheMaxMB,
      remotePrefetchWifiOnly: cur.remotePrefetchWifiOnly,
      remoteWholeBookThresholdMB: cur.remoteWholeBookThresholdMB,
      remoteWindowBeforePct: cur.remoteWindowBeforePct,
      remoteWindowAfterPct: cur.remoteWindowAfterPct,
      remoteRetryCount: cur.remoteRetryCount,
      remoteRetryIntervalMs: cur.remoteRetryIntervalMs,
      tapLeftAction: this.tapLeftAction,
      tapCenterAction: this.tapCenterAction,
      tapRightAction: this.tapRightAction,
      volumeKeyAction: this.volumeKeyAction,
      /* 0.9.55 prefs-alignment fields pass through unchanged */
      authorTitleFromMetaPDF: cur.authorTitleFromMetaPDF,
      showOnlyOriginalFileNames: cur.showOnlyOriginalFileNames,
      useCalibreOpf: cur.useCalibreOpf,
      seriesNumberInTitle: cur.seriesNumberInTitle,
      autoAddToLibrary: cur.autoAddToLibrary,
      restoreSearchQuery: cur.restoreSearchQuery,
      lockBooksByDefault: cur.lockBooksByDefault,
      accessibilityMode: cur.accessibilityMode,
      contextMenuIntegration: cur.contextMenuIntegration,
      readingModePref: cur.readingModePref,
      doubleClickAction: cur.doubleClickAction,
      widgetLayout: cur.widgetLayout,
      lastLibQuery: cur.lastLibQuery,
      /* 0.9.59: reader pref 4-tab fields */
      showStatusBar: this.showStatusBar,
      showTitleTop: this.showTitleTop,
      showClockTop: this.showClockTop,
      showBatteryTop: this.showBatteryTop,
      showProgressBar: this.showProgressBar,
      showChapterTicks: this.showChapterTicks,
      showSubChapterTicks: this.showSubChapterTicks,
      seekNav: this.seekNav,
      statusColorDay: this.statusColorDay,
      statusBarFontPx: this.statusBarFontPx,
      progressBarH: this.progressBarH,
      progressScale: this.progressScale,
      chapterFmtIdx: this.chapterFmtIdx,
      bmStripMusician: this.bmStripMusician,
      bmStripVert: this.bmStripVert,
      bmStripHorz: this.bmStripHorz,
      tapEdgePercent: this.tapEdgePercent,
      antialiasLevel: this.antialiasLevel,
      autoOffMinutes: this.autoOffMinutes,
      mirrorMode: this.mirrorMode,
      allowTextSelection: this.allowTextSelection,
      perCharSelect: this.perCharSelect,
      edgeBrightness: this.edgeBrightness,
      allowZeroBright: this.allowZeroBright,
      dayImageReplace: this.dayImageReplace,
      amoledEnhance: this.amoledEnhance,
      loopPaging: this.loopPaging,
      zoomLockedPages: this.zoomLockedPages,
      backConfirmClose: this.backConfirmClose,
      selectionVibrate: this.selectionVibrate,
      decodeThreads: this.decodeThreads,
      drawThreads: this.drawThreads,
      textAlign: this.textAlign,
      letterSpacing: this.letterSpacing,
      paragraphSpacing: this.paragraphSpacing,
      hyphenationEnabled: this.hyphenationEnabled,
      hyphenLang: this.hyphenLang,
      hyphenLangDefault: this.hyphenLangDefault,
      inlineNotes: this.inlineNotes,
      imageZoomEnabled: this.imageZoomEnabled,
      imageZoomScale: this.imageZoomScale,
      styleMode: this.styleMode,
      userCssName: this.userCssName,
      dictLookup: this.dictLookup,
      useSystemBrightness: this.useSystemBrightness,
      invertVolume: this.invertVolume,
      marginT: this.marginT,
      marginB: this.marginB,
      marginLeft: this.marginLeft,
      marginRight: this.marginRight,
      linkColorDay: this.linkColorDay,
      linkColorNight: this.linkColorNight,
      tapTopAction: this.tapTopAction,
      tapBottomAction: this.tapBottomAction
    };
    await saveSettings(ctx, s);
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
    if (this.isReflow && !this.isRemote) {
      /* 0.9.34 P2-13: local reflowables re-tint through user CSS (crisp text
       * on the real background color) instead of pixel inversion. */
      this.dataSource.setInvert(false);
      this.applyReflow();
    } else {
      this.dataSource.setInvert(this.nightMode);
    }
    this.persistSettings();
    console.info(`[Reader] Theme: ${THEME_NAMES[t]}`);
  }

  /* Round 7: observed AppStorage mirror for the dynamic brand color */
  @StorageProp('themeColorHex') stThemeHex: string = '#3949AB';

  /** Round 7: dynamic theme (brand) color from AppStorage (prefs themeColor). */
  private thm(): string {
    return this.stThemeHex.length > 0 ? this.stThemeHex : '#3949AB';
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
    return this.thm(); /* Round 7: dynamic brand color */
  }

  /** Re-layout a reflowable doc with the current typography settings. */
  private async applyReflow(): Promise<void> {
    const handle: ESObject | undefined = this.dataSource.getDocHandle();
    if (handle === undefined || !this.isReflow) {
      return;
    }
    try {
      const css = this.buildCss();
      let newTotal = -1;
      if (this.isRemote) {
        const r = await this.docOp(3, this.reflowW, this.reflowH, this.fontSize, css);
        if (r !== undefined) {
          newTotal = parseInt(r, 10);
        }
      } else {
        layoutDocument(handle, this.reflowW, this.reflowH, this.fontSize, css);
        newTotal = pageCount(handle);
      }
      if (newTotal >= 0) {
        this.pageList = [];
        for (let i = 0; i < newTotal; i++) {
          this.pageList.push(i);
        }
        this.totalPages = newTotal;
        this.layoutComplete = true; /* full re-layout done - new era */
        this.currentPage = Math.min(this.currentPage, newTotal - 1);
        this.reflowVersion++;
        /* 0.9.65: seekToPage = onPageChanged + vertical List alignment (the
         * List has no index binding; onPageChanged alone left it wherever
         * the rebuild parked it) */
        this.seekToPage(this.currentPage, true);
        this.loadToc();
        this.persistSettings();
        this.saveAccel(); /* 0.9.27: the new era's accelerator replaces the old */
        /* 0.9.23 (Android 10-09 parity): a typography change re-lays out the
         * whole book - settle the archived progress entry to the new era
         * immediately, so the absolute page (valid in the new geometry) is
         * saved with a fresh epoch instead of a stale one. */
        this.saveProgress(this.currentPage);
        tlog('reflow_settled', 'pages=' + newTotal + ' page=' + (this.currentPage + 1));
        console.info(`[Reader] Reflow applied: ${newTotal} pages (em=${this.fontSize}, lh=${this.lineHeight.toFixed(1)}, m=${this.pageMargin})`);
      }
    } catch (e) {
      console.error('[Reader] applyReflow failed');
    }
  }

  /** Round 8: route local docs to the sync opener, remote:// docs to the stream bridge.
   *  Batch 2: .docx/.rtf are converted to cached HTML first; .doc shows an explicit
   *  unsupported toast (the engine has no Word-97 handler). */
  private initOrRemote(): void {
    if (this.documentPath.startsWith('remote://')) {
      this.initRemoteReader(this.documentPath);
      return;
    }
    const ext: string = fileExtOf(this.documentPath);
    if (isConvertibleExt(ext)) {
      this.openConverted();
      return;
    }
    if (ext === 'doc') {
      console.error('[Reader] Legacy .doc is not supported');
      promptAction.showToast({ message: '暂不支持旧版 .doc 文件，请先另存为 .docx' });
      router.back();
      return;
    }
    this.initReader();
  }

  /** Batch 2: convert docx/rtf to cached HTML, then open the converted copy.
   *  Reading progress keeps the ORIGINAL path as its key (logicalPath). */
  private async openConverted(): Promise<void> {
    const src: string = this.documentPath;
    const ext: string = fileExtOf(src);
    try {
      const html: string = await convertToHtml(getContext(this), src, ext);
      tlog('convert_ok', 'ext=' + ext + ' out=' + html.substring(html.lastIndexOf('/') + 1));
      this.logicalPath = src;
      this.documentPath = html;
      this.initReader();
    } catch (e) {
      const msg: string = e instanceof Error ? e.message : String(e);
      tlog('convert_fail', 'ext=' + ext + ' err=' + msg.substring(0, 180));
      console.error(`[Reader] ${ext} convert failed: ${msg}`);
      promptAction.showToast({ message: `文档转换失败：${msg}` });
      router.back();
    }
  }

  /* 0.9.17: staged (chunked fb2) opens build the TOC off the main thread */
  private stagedOpen: boolean = false;

  private async initReader(): Promise<void> {
    /* 0.9.15: open-pipeline stage timing (Android BENCH 09-30 parity) */
    const benchT0: number = Date.now();
    const gen: number = ++this.stageGen;
    try {
      /* 0.9.27 P1-5: reuse a persisted accelerator when one matches this
       * file AND the current typography (the key is salted with both) */
      let useAccel: string = '';
      const ap: string = this.accelPathOf(this.documentPath);
      if (ap.length > 0) {
        try {
          if (fs.accessSync(ap)) {
            useAccel = ap;
          }
        } catch (e) { /* none yet */ }
      }
      /* 0.9.34 P2-14: adopt a worker-prefetched open (Index tap -> Reader init
       * gap; Android OpenPrefetch parity). A stale or missed entry falls
       * through to the plain sync open below. */
      const pf: OpenPrefetchEntry | null = OpenPrefetch.take(this.documentPath);
      if (pf !== null) {
        this.dataSource.adoptDoc(this.documentPath, pf.handle);
        tlog('prefetch_adopt', 'ms=' + (Date.now() - benchT0) + ' reflow=' + pf.isReflow);
      } else {
        this.dataSource.openDoc(this.documentPath, true, useAccel);
        tlog('bench_open_doc', 'ms=' + (Date.now() - benchT0));
      }
      this.armFirstPaint();
      const handle: ESObject | undefined = this.dataSource.getDocHandle();
      /* Round 6: encrypted document? ask for the password before rendering */
      if (handle !== undefined) {
        try {
          if (needsPassword(handle) as boolean) {
            this.showPwd = true;
            console.info('[Reader] Encrypted document: password required');
          }
        } catch (_e) {
          /* needsPassword probe failed — treat as unencrypted */
        }
      }
      if (handle !== undefined) {
        try {
          this.isReflow = isReflowable(handle);
          console.info(`[Reader] Reflowable document: ${this.isReflow}`);
        } catch (e) {
          this.isReflow = false;
        }
        tlog('bench_reflow_probe', 'ms=' + (Date.now() - benchT0) + ' reflow=' + this.isReflow);
      }
      if (!this.isReflow) {
        /* pdf and friends: chapter-lazy counting does not apply; the plain
         * count is cheap - finish the page list the old way. NOTE: the
         * dataSource opened lazily, so its pageIndices are empty - count
         * via the native handle (sm-04 cbz pages=0 regression, 0.9.16). */
        const handleP: ESObject | undefined = this.dataSource.getDocHandle();
        this.totalPages = handleP !== undefined ? pageCount(handleP) : 0;
        this.dataSource.setPageCount(this.totalPages);
        tlog('bench_pagelist', 'ms=' + (Date.now() - benchT0) + ' total=' + this.totalPages);
        this.rebuildPageList();
        this.finishReaderOpen();
        return;
      }
      /* 0.9.16 staged open (Android progressive layout parity): count the
       * first chapter batch only, show the first screen, then grow the page
       * canvas in the background. -1 = no chapter support -> old full path.
       * The old synchronous full layoutDocument is GONE from this path -
       * op15's fz_layout_document already fixed the geometry for the pages
       * it counted. */
      /* 0.9.22 (Android 10-05 parity): count up to the saved reading
       * position plus one window so the initial canvas always contains the
       * page that must be shown - counting only the head 400 clamped deep
       * positions back into the first window and lost the reader's place
       * on reopen. */
      /* 0.9.33 P2-11/P1-6: the staged path never applied the typography
       * css (openDocument makes a fresh context per book; op15 has no css
       * channel) - set it before the first chunk parses. */
      try {
        await this.docOp(16, 0, 0, 0, this.buildCss());
      } catch (_e) { /* best effort */ }
      const target: number = Math.max(0, this.currentPage);
      this.layoutComplete = false; /* the count below is a window, not the era total */
      const prog = await this.docOp(15, this.reflowW, this.reflowH, this.fontSize, String(target + 400));
      const n1: number = parseInt(prog === undefined ? '-1' : prog);
      tlog('bench_stage1', 'ms=' + (Date.now() - benchT0) + ' n1=' + n1 + ' from=' + target);
      if (isNaN(n1) || n1 < 0) {
        /* fallback: one full layout + count (previous behaviour) */
        const handle2: ESObject | undefined = this.dataSource.getDocHandle();
        if (handle2 !== undefined) {
          try {
            const css = `body{margin:${this.pageMargin}pt;line-height:${this.lineHeight.toFixed(2)}}`;
            layoutDocument(handle2, this.reflowW, this.reflowH, this.fontSize, css);
          } catch (e) {
            console.error('[Reader] Reflow layout failed');
          }
        }
        this.totalPages = this.dataSource.getPageCount();
        this.dataSource.setPageCount(this.totalPages);
        tlog('bench_pagelist', 'ms=' + (Date.now() - benchT0) + ' total=' + this.totalPages);
        this.rebuildPageList();
        this.finishReaderOpen();
        return;
      }
      /* 0.9.34 P2-13: the staged path applies the palette through user CSS
       * (op16 buildCss) - keep the pixmap invert off for local reflowables. */
      this.dataSource.setInvert(false);
      this.totalPages = n1;
      this.dataSource.setPageCount(n1);
      tlog('bench_pagelist', 'ms=' + (Date.now() - benchT0) + ' total=' + this.totalPages);
      this.rebuildPageList();
      this.currentPage = Math.min(this.currentPage, Math.max(0, this.totalPages - 1));
      console.info(`[Reader] Document opened (staged): ${this.totalPages} pages, starting at page ${this.currentPage + 1}`);
      tlog('book_open_done', 'pages=' + this.totalPages + ' staged=1');
      this.openSettled = true;
      this.landOnRestoredPage();
      /* 0.9.43: staged opens must fire bookmark_list_loaded like every
       * other open path (non-staged/remote already do; CI FN-05) */
      this.loadBookmarks();
      this.stagedOpen = true;
      this.startPhaseTwo(gen);
    } catch (e) {
      console.error('[Reader] Failed to open document: ' + JSON.stringify(e));
      tlog('book_open_fail', 'err=1');
    }
  }

  /* 0.9.22 fix2: the page list is empty at the Swiper's first build, so
   * the .index() binding does not re-apply when items arrive - without
   * this, reopening a book saved at a deep page showed page 1 (slider and
   * indicator at 1) while currentPage held the restored value. */
  private landOnRestoredPage(): void {
    if (this.currentPage <= 0) {
      return;
    }
    /* 0.9.64: capture the restored target at open time — a stray
     * mount-time onChange/onScrollIndex(0) must not redirect the landing. */
    const target: number = this.currentPage;
    setTimeout(() => {
      if (target <= 0 || this.totalPages <= 0) {
        return;
      }
      if (this.scrollMode === 1) {
        /* 0.9.64: the vertical List has no .index() binding — the 0.9.22
         * nudge below only fixed the horizontal Swiper, so a book reopened
         * in scroll mode always showed page 1. Scroll the List once it
         * holds the target (staged opens may still be growing the canvas). */
        this.whenCanvasReady(() => this.totalPages > target, (): void => {
          this.seekToPage(target, true);
          console.info(`[Reader] Landed on restored page ${this.currentPage + 1} (vertical)`);
          tlog('restore_land', 'page=' + (this.currentPage + 1) + ' mode=v');
          /* belt & braces: the Scroller may attach a beat later than the
           * first scroll; a second no-animation scroll to the same index
           * is idempotent */
          setTimeout((): void => {
            try {
              this.vScroller.scrollToIndex(Math.min(target, this.totalPages - 1), false);
            } catch (_e) { /* scroller not attached */ }
          }, 500);
        });
        return;
      }
      /* .index() only re-applies when the state VALUE changes; at open it
       * was already the restored value at the Swiper's first (empty)
       * build. Nudge 0 -> target: the Swiper is at 0, so the intermediate
       * value causes no visible move. (0.9.22 fix2) */
      this.currentPage = 0;
      setTimeout(() => {
        this.currentPage = target;
      }, 50);
    }, 200);
  }

  /** (Re)build the Swiper page list from totalPages. */
  private rebuildPageList(): void {
    this.pageList = [];
    for (let i = 0; i < this.totalPages; i++) {
      this.pageList.push(i);
    }
    this.reflowVersion++;
  }

  /* 0.9.27 P1-5: accelerator file for this document (Android Temp/accel
   * parity). Key = path#size#mtime plus the typography settings, so a
   * re-layout with different parameters never hits a stale layout cache. */
  private accelPathOf(p: string): string {
    try {
      const st = fs.statSync(p);
      const key: string = `${p}#${st.size}#${Math.floor(st.mtime)}#${this.fontSize}#${this.lineHeight.toFixed(2)}#${Math.round(this.pageMargin)}#${this.reflowW}x${this.reflowH}`;
      return getContext(this).cacheDir + '/accels/' + sha256Hex(key) + '.accel';
    } catch (e) {
      return '';
    }
  }

  /** 0.9.27 P1-5: persist the layout accelerator once counting is done. */
  private saveAccel(): void {
    const handle: ESObject | undefined = this.dataSource.getDocHandle();
    if (handle === undefined || this.isRemote) {
      return;
    }
    const ap: string = this.accelPathOf(this.documentPath);
    if (ap.length === 0) {
      return;
    }
    setTimeout(() => {
      try {
        /* 0.9.32: the accels dir must exist - fz_save_accelerator fails on
         * a missing parent (the desktop round's rc=-3 lesson) */
        const dir: string = getContext(this).cacheDir + '/accels';
        try {
          if (!fs.accessSync(dir)) {
            fs.mkdirSync(dir, true);
          }
        } catch (e) {
          fs.mkdirSync(dir, true);
        }
        saveAccelerator(handle, ap);
        trimOldestFiles(dir, 8);
        tlog('accel_saved', '1');
      } catch (e) { /* best effort */ }
    }, 50);
  }

  /* 0.9.27 P1-8: serialized TOC cache (Android TOC LRU 50 parity). */
  private async tocCachePath(): Promise<string> {
    let base: string = '';
    if (this.isRemote) {
      base = this.remoteTocKey;
    } else {
      try {
        const st = fs.statSync(this.documentPath);
        base = `${this.documentPath}#${st.size}#${Math.floor(st.mtime)}`;
      } catch (e) { /* uncacheable */ }
    }
    if (base.length === 0) {
      return '';
    }
    /* 0.9.67: append the layout-era salt (accelPathOf parity) — a re-layout
     * with different parameters (font/line-height/margins/viewport) must
     * never serve a stale TOC: the 0.9.27 key predated the 0.9.61 salt
     * lesson, and stale TOC page numbers would also poison the 0.9.67
     * section anchors (capture + fold both read this.tocEntries). */
    const hex: string = await sha256Hex(base + '#' + this.fontSize + '#' + this.lineHeight.toFixed(2) + '#' + Math.round(this.pageMargin) + '#' + this.reflowW + 'x' + this.reflowH);
    return getContext(this).cacheDir + '/toc/' + hex + '.json';
  }

  /** Common tail of the open flow (0.9.15 first-frame priority kept). */
  private finishReaderOpen(): void {
    this.openSettled = true;
    this.layoutComplete = true; /* non-staged opens know their full count */
    this.rebuildPageList();
    this.landOnRestoredPage();
    this.loadToc();
    this.loadBookmarks();
    this.refreshAnnotations();
    this.saveAccel();
    tlog('book_open_done', 'pages=' + this.totalPages);
  }

  /**
   * 0.9.16 phase two (Android startPhaseTwoLayout parity): grow the page
   * canvas in ~400-page steps with the native lock released between steps.
   * The page list only grows at the tail, so the reading position stays
   * visually stable; the TOC loads after the full count (Android order).
   * Bumping stageGen (re-open / teardown) cancels the loop.
   */
  private async startPhaseTwo(gen: number): Promise<void> {
    const t0: number = Date.now();
    let total: number = this.totalPages;
    let requested: number = total + 400;
    while (gen === this.stageGen) {
      const ns = await this.docOp(15, this.reflowW, this.reflowH, this.fontSize, String(requested));
      const n: number = parseInt(ns === undefined ? '-1' : ns);
      if (gen !== this.stageGen || isNaN(n) || n < 0 || n <= total) {
        break;
      }
      total = n;
      this.totalPages = total;
      this.dataSource.setPageCount(total);
      this.rebuildPageList();
      if (n < requested) {
        break; /* document end reached */
      }
      requested = total + 400;
    }
    if (gen !== this.stageGen) {
      console.info('[Reader] phase2 cancelled at ' + total);
      return;
    }
    tlog('bench_phase2_end', 'total=' + total + ' ms=' + (Date.now() - t0));
    console.info(`[Reader] phase2 done: ${total} pages in ${Date.now() - t0}ms`);
    this.layoutComplete = true;
    this.settleProgressEpoch(total);
    this.loadToc();
    this.saveAccel();
  }

  private async loadToc(): Promise<void> {
    try {
      const handle: ESObject | undefined = this.dataSource.getDocHandle();
      if (handle !== undefined) {
        /* 0.9.27 P1-8: serialized TOC cache first - remote outlines run a
         * layout in the engine and big books take seconds; the cache key
         * covers the file identity so a changed book re-loads live. */
        const ck: string = await this.tocCachePath();
        if (ck.length > 0) {
          const cached: string = readSmallTextFile(ck);
          if (cached.length > 0) {
            try {
              this.tocEntries = JSON.parse(cached) as TocEntry[];
              console.info(`[Reader] TOC cache hit: ${this.tocEntries.length} entries`);
              tlog('toc_loaded', 'entries=' + this.tocEntries.length + ' cached=1');
              return;
            } catch (e) { /* corrupt entry: fall through to the live load */ }
          }
        }
        /* 0.9.67 rev2: reflow outlines are computed by laying the book out in
         * the engine, so the era-salted cache (frequent live loads now) must
         * never pay that on the main thread — THREAD_BLOCK_6S appfreeze at
         * open. Local reflow joins the remote/staged async docOp path; only
         * fixed-layout books (cheap page-table outline) keep the sync call. */
        if (this.isRemote || this.stagedOpen || this.isReflow) {
          const j = await this.docOp(1);
          this.tocEntries = j !== undefined ? JSON.parse(j) as TocEntry[] : [];
        } else {
          this.tocEntries = getToc(handle);
        }
        console.info(`[Reader] TOC loaded: ${this.tocEntries.length} entries`);
        tlog('toc_loaded', 'entries=' + this.tocEntries.length);
        if (ck.length > 0 && this.tocEntries.length > 0) {
          try {
            writeSmallTextFile(ck, JSON.stringify(this.tocEntries));
            trimOldestFiles(getContext(this).cacheDir + '/toc', 50);
          } catch (e) { /* best effort */ }
        }
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

  /** P1-9 (0.9.35): one background page-tree walk for a streaming remote
   * pdf whose pagemap sidecar is still missing. Runs when the block filler
   * has cached the whole book, so op13's lazy-tree finish reads only local
   * cache hits; the full nums+sizes map is persisted for walk-free re-opens. */
  private maybeConvergePagemap(): void {
    if (!this.pmConvArmed || this.pmConvFired) {
      return;
    }
    this.pmConvFired = true;
    const handle: number = this.pmConvHandle;
    const key: string = this.pmConvKey;
    const size: number = this.pmConvSize;
    const tag: string = this.pmConvTag;
    docOpAsync(handle, 13).then((raw: string) => {
      const j: Record<string, ESObject> = JSON.parse(raw) as Record<string, ESObject>;
      const nums: ESObject = j['nums'];
      if (nums !== null && nums !== undefined && nums.length > 0) {
        const sizes: ESObject = j['sizes'];
        const ctx = getContext(this);
        writePageTreeSidecar(ctx, key, size, tag,
          nums as number[], sizes !== null && sizes !== undefined ? sizes as number[] : []);
        tlog('pagemap_converged', 'pages=' + nums.length);
        console.info(`[Reader] pagemap converged (streaming): ${nums.length} pages`);
      } else {
        console.info('[Reader] pagemap converge: engine returned no map');
      }
    }).catch((err: Error) => {
      console.warn('[Reader] pagemap converge failed: ' + err.message);
    });
  }

  aboutToDisappear(): void {
    this.openSettled = false;
    this.layoutComplete = false;
    for (const t of this.jumpWaitTimers) {
      clearInterval(t);
    }
    this.jumpWaitTimers = [];
    this.stageGen++; /* cancel a running staged-layout loop (0.9.16) */
    this.unregisterVolumeKeys();
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
        path: this.logicalPath.length > 0 ? this.logicalPath : this.documentPath,
        title: this.docTitle,
        page: this.currentPage,
        totalPages: this.layoutComplete ? this.totalPages : -1,
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
    /* 0.9.20: tear down the PDF translation session + drop stale streams */
    this.stopPdfBi('destroy');
    this.transToken++;
    this.dataSource.closeDoc();
    /* Round 8: tear down the remote stream session after the document */
    if (this.remoteSession !== undefined) {
      this.remoteSession.close();
      this.remoteSession = undefined;
    }
    if (this.remoteReaderId >= 0) {
      try {
        unregisterRemoteReader(this.remoteReaderId);
      } catch (_e) { /* already gone */ }
      this.remoteReaderId = -1;
    }
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
    if (!this.openSettled) {
      /* Swiper init fires onChange(0) on the empty pre-open list; swallow
       * it so the restored position survives (0.9.22 fix3). */
      return;
    }
    const prevPage = this.currentPage;
    this.currentPage = index;
    if (this.selMode) {
      this.exitSelMode();
    }
    if (this.pdfBiActive) {
      this.pdfWindow(index);
      this.refreshPdfOverlay();
    }
    console.info(`[Reader] Page changed to ${index + 1}/${this.totalPages}`);
    tlog('page_change_done', 'page=' + (index + 1) + ' of=' + this.totalPages);
    /* Phase 3: count a flipped page only when the page actually changed */
    if (prevPage !== index) {
      /* 0.9.26 P0-3 tail: direction-aware remote prefetch */
      if (this.remoteSession !== undefined) {
        this.remoteSession.prefetchForPageTurn(index > prevPage ? 1 : -1);
      }
      try {
        recordPagesRead(getContext(this), 1).catch((e: Error) => {
        });
      } catch (e) {
        /* non-fatal */
      }
    }
    this.saveProgress(index);
    this.refreshAnnotations();
    this.refreshBmOnPage();
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
        path: this.logicalPath.length > 0 ? this.logicalPath : this.documentPath,
        title: this.docTitle,
        page: page,
        /* 0.9.23: only a completed full count may publish an epoch - the
         * staged window end would poison the era check on reopen */
        totalPages: this.layoutComplete ? this.totalPages : -1,
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

  /* 2026-09-20 port: Android DOUBLE_CLICK_ADJUST_PAGE ("\u7F29\u653E\u5230\u5408\u9002\u5927\u5C0F") -
   * double-tap returns to the comfortable fit zoom; again zooms back in. */
  private doubleTapFit(): void {
    const target: number = this.zoomLevel > this.fitZoom + 0.01 ? this.fitZoom
      : Math.min(this.fitZoom * 2, MAX_ZOOM);
    if (Math.abs(target - this.zoomLevel) < 0.001) {
      return;
    }
    this.zoomLevel = target;
    this.dataSource.setZoom(target);
    console.info(`[Reader] Double-tap zoom: ${target.toFixed(2)}x`);
  }

  private toggleNightMode(): void {
    this.nightMode = !this.nightMode;
    this.theme = this.nightMode ? 1 : 0;
    if (this.isReflow && !this.isRemote) {
      /* 0.9.34 P2-13: re-tint via user CSS + re-layout instead of invert */
      this.dataSource.setInvert(false);
      this.applyReflow();
    } else {
      this.dataSource.setInvert(this.nightMode);
    }
    this.persistSettings();
    tlog('night_mode', this.nightMode ? 'on' : 'off');
    console.info(`[Reader] Night mode: ${this.nightMode ? 'ON' : 'OFF'} (theme=${THEME_NAMES[this.theme]})`);
  }

  /* 0.9.65: shared vertical-mode landing for every scrollMode 0→1 entry
   * (menu-row toggle, ask-reading-mode dialog). The vertical List mounts at
   * offset 0 — without this a mount-time onScrollIndex(0) rewrites both the
   * page and the saved progress to page 1 (the reopen / bookmark-jump bug
   * family). Seek right away, then re-scroll once with a raw no-animation
   * scrollToIndex in case the Scroller attached a beat later than the first
   * attempt (same belt & braces as landOnRestoredPage). */
  private landAfterModeSwitch(keep: number): void {
    setTimeout(() => {
      this.seekToPage(keep, true);
    }, 200);
    setTimeout(() => {
      try {
        this.vScroller.scrollToIndex(Math.min(keep, this.totalPages - 1), false);
      } catch (_e) { /* scroller not attached */ }
    }, 700);
  }

  private toggleScrollMode(): void {
    /* 0.9.64: carry the reading position across the switch — the vertical
     * List has no index binding, so switching into it previously always
     * showed page 1 (same root as the bookmark-jump / reopen reports). */
    const keep: number = this.currentPage;
    this.scrollMode = this.scrollMode === 0 ? 1 : 0;
    if (this.scrollMode === 1 && this.totalPages > 0) {
      this.landAfterModeSwitch(keep);
    }
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
      let jsonStr: string;
      if (this.isRemote) {
        const r = await this.docOp(6, q);
        if (r === undefined) {
          return;
        }
        jsonStr = r;
      } else {
        jsonStr = searchDocument(handle, q);
      }
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
        tlog('book_search_done', 'hits=' + this.searchTotalHits + ' pages=' + this.searchResults.length);
      } else {
        this.searchHits = [];
        this.searchHitPage = -1;
        console.info(`[Reader] Search '${q}': no hits`);
        tlog('book_search_done', 'hits=0');
      }
    } catch (e) {
      console.error('[Reader] searchDocument failed: ' + String(e));
    }
  }

  /** Sprint O2: load and normalize hit rects for a specific page. */
  private async highlightPageHits(pageIdx: number): Promise<void> {
    const handle: ESObject | undefined = this.dataSource.getDocHandle();
    if (handle === undefined) return;
    try {
      const q: string = this.searchQuery.trim();
      let rects: TextRect[];
      let size: TextRect;
      if (this.isRemote) {
        const rj = await this.docOp(5, q, pageIdx);
        const sj = await this.docOp(2, pageIdx);
        if (rj === undefined || sj === undefined) {
          return;
        }
        rects = JSON.parse(rj) as TextRect[];
        const rawSize = JSON.parse(sj) as TextRect;
        size = { x0: rawSize.x0, y0: rawSize.y0, x1: rawSize.x1, y1: rawSize.y1 };
      } else {
        rects = searchText(handle, q, pageIdx);
        size = getPageSize(handle, pageIdx);
      }
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
      this.seekToPage(pageIdx);
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
  /** 0.9.61: derive the 🔖 highlight from the current page's bookmark. */
  private refreshBmOnPage(): void {
    let on = false;
    for (let i = 0; i < this.bookmarks.length; i++) {
      if (this.bookmarks[i].page === this.currentPage) {
        on = true;
        break;
      }
    }
    this.bmOnPage = on;
  }

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
      tlog('bookmark_removed', 'page=' + (this.currentPage + 1));
      promptAction.showToast({ message: $r('app.string.bm_removed') });
    } else {
      /* 0.9.67: capture the chapter anchor at add time (Android ListBoxHelper.addBookmark parity) */
      const ok = await addBookmark(ctx, this.documentPath, this.currentPage, '', this.layoutComplete ? this.totalPages : -1, this.captureBmAnchor());
      if (ok) {
        console.info(`[Reader] Bookmark added at page ${this.currentPage + 1}`);
        tlog('bookmark_added', 'page=' + (this.currentPage + 1));
        promptAction.showToast({ message: $r('app.string.bm_added') });
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
    tlog('bookmark_list_loaded', 'n=' + this.bookmarks.length);
    this.refreshBmOnPage();
  }

  /* 0.9.62: meta line for the named-bookmark dialog (第 X / N 页). */
  private bmPageMeta(): string {
    const pages = this.layoutComplete ? this.totalPages : 0;
    return pages > 0 ? `第 ${this.currentPage + 1} / ${pages} 页` : `第 ${this.currentPage + 1} 页`;
  }

  /* 0.9.62 (Android BookmarksAdapter parity): dialog rows — while searching,
   * quick bookmarks are excluded (Android parity); sort order 0 newest-first /
   * 1 oldest-first / 2 page asc / 3 page desc. */
  private bmRows(): Bookmark[] {
    const quick = this.L($r('app.string.fast_bookmark')).toLowerCase();
    const query = this.bmSearch.trim().toLowerCase();
    const rows: Bookmark[] = this.bookmarks.filter((bm: Bookmark): boolean => {
      if (query.length === 0) {
        return true;
      }
      const text = (bm.label.length > 0 ? bm.label : this.L($r('app.string.reader_bookmark'))).toLowerCase();
      return text !== quick && text.includes(query);
    });
    if (this.bmSortOrder === 1) {
      rows.sort((a: Bookmark, b: Bookmark): number => Date.parse(a.date) - Date.parse(b.date));
    } else if (this.bmSortOrder === 2) {
      rows.sort((a: Bookmark, b: Bookmark): number => a.page - b.page);
    } else if (this.bmSortOrder === 3) {
      rows.sort((a: Bookmark, b: Bookmark): number => b.page - a.page);
    } else {
      rows.sort((a: Bookmark, b: Bookmark): number => Date.parse(b.date) - Date.parse(a.date));
    }
    return rows;
  }

  /* 0.9.62 (Android TTSEngine.fastTTSBookmakr parity): the quick bookmark
   * lands only when the page has none; the dialog closes either way. */
  private async quickAddBookmark(): Promise<void> {
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
    if (!found) {
      /* 0.9.67: capture the chapter anchor (Android TTSEngine.fastTTSBookmakr parity) */
      const ok = await addBookmark(ctx, this.documentPath, this.currentPage, this.L($r('app.string.fast_bookmark')), this.layoutComplete ? this.totalPages : -1, this.captureBmAnchor());
      if (ok) {
        console.info(`[Reader] Quick bookmark added at page ${this.currentPage + 1}`);
        tlog('bookmark_added', 'page=' + (this.currentPage + 1) + ' src=dialog');
        promptAction.showToast({ message: `${this.L($r('app.string.fast_bookmark'))} — 第 ${this.currentPage + 1} 页` });
      }
    } else {
      console.info('[Reader] Quick bookmark skipped: page already bookmarked');
    }
    await this.loadBookmarks();
    this.showBmList = false;
  }

  /* 0.9.62 (Android ListBoxHelper.showAddDialog parity): named bookmark on
   * the current page (the floating-bookmark checkbox has no HarmonyOS port). */
  private async addNamedBookmark(): Promise<void> {
    const ctx = getContext(this);
    const name = this.bmAddName.trim();
    if (this.documentPath.length === 0 || name.length === 0) {
      promptAction.showToast({ message: $r('app.string.incorrect_value') });
      return;
    }
    /* 0.9.67: capture the chapter anchor (Android ListBoxHelper.showAddDialog parity) */
    const ok = await addBookmark(ctx, this.documentPath, this.currentPage, name, this.layoutComplete ? this.totalPages : -1, this.captureBmAnchor());
    if (ok) {
      console.info(`[Reader] Named bookmark added at page ${this.currentPage + 1}`);
      tlog('bookmark_added', 'page=' + (this.currentPage + 1) + ' src=named');
      promptAction.showToast({ message: $r('app.string.bm_added') });
    }
    this.bmAddName = '';
    this.showBmAdd = false;
    await this.loadBookmarks();
  }

  /* 0.9.62: per-row delete (Android BookmarksAdapter remove2 parity). */
  private async deleteBm(bm: Bookmark): Promise<void> {
    await removeBookmark(getContext(this), bm.path, bm.page);
    console.info(`[Reader] Bookmark deleted at page ${bm.page + 1}`);
    tlog('bookmark_removed', 'page=' + (bm.page + 1) + ' src=dialog');
    await this.loadBookmarks();
  }

  /* 0.9.62 (Android ExtUtils.sendBookmarksTo parity): share the bookmark
   * list as text via the system share sheet. */
  private bmShare(): void {
    try {
      if (this.documentPath.length === 0) {
        return;
      }
      const lines: string[] = [];
      const rows = this.bmRows();
      for (let i = 0; i < rows.length; i++) {
        const label = rows[i].label.length > 0 ? rows[i].label : this.L($r('app.string.reader_bookmark'));
        lines.push(`${this.bmLandingPage(rows[i]) + 1}: ${label}`);
      }
      const rec: systemShare.SharedRecord = {
        utd: 'general.text',
        content: this.docTitle + '\n' + lines.join('\n'),
        title: this.docTitle
      };
      const data = new systemShare.SharedData(rec);
      const controller = new systemShare.ShareController(data);
      controller.show(getContext(this) as common.UIAbilityContext, {}).catch(() => {
      });
      console.info(`[Reader] Share bookmarks: ${lines.length} rows`);
    } catch (e) {
      console.error('[Reader] Share bookmarks failed: ' + JSON.stringify(e));
    }
  }

  /* 0.9.67 (Android ListBoxHelper.captureSectionAnchor parity): record the
   * chapter identity of the current page at add time — chapter index in the
   * device-local TOC + in-chapter ratio + title fingerprint. The fold is
   * resolved on the reading device (possibly another phone/PAD with a
   * different page count), so precision rises from "whole-book percent" to
   * "in-chapter ratio". Skipped — keeping plain percent semantics — when the
   * TOC is absent/unloaded, before the first chapter, or the entry has no
   * usable title fingerprint. */
  private captureBmAnchor(): BmAnchor | undefined {
    try {
      const pages: number = this.totalPages;
      if (this.tocEntries.length === 0 || pages <= 0 || this.currentPage < 0) {
        return undefined;
      }
      let best: number = -1;
      for (let i = 0; i < this.tocEntries.length; i++) {
        const tp: number = this.tocEntries[i].page;
        if (tp >= 0 && tp <= this.currentPage) {
          best = i; /* last match wins: same-page nested entries → deeper level */
        }
      }
      if (best < 0) {
        return undefined; /* before the first chapter (cover/colophon) */
      }
      const title: string = this.tocEntries[best].title;
      if (title.length === 0) {
        return undefined; /* no fingerprint → no trustworthy anchor */
      }
      const start0: number = this.tocEntries[best].page;
      let end0: number = pages; /* last chapter: section ends at book end */
      for (let j = best + 1; j < this.tocEntries.length; j++) {
        const tpj: number = this.tocEntries[j].page;
        if (tpj > start0) {
          end0 = tpj;
          break;
        }
      }
      if (end0 <= start0 || this.currentPage >= end0) {
        return undefined; /* TOC page numbers unusable */
      }
      const sr: number = (this.currentPage - start0) / (end0 - start0);
      tlog('bookmark_anchor_captured', 'sec=' + best + ' sr=' + sr.toFixed(6) + ' pg=' + (this.currentPage + 1) + ' pgs=' + pages);
      console.info(`[Reader] Bookmark anchor captured: sec=${best} sr=${sr.toFixed(6)} pg=${this.currentPage + 1}/${pages}`);
      const anchor: BmAnchor = { sec: best, sr: sr, st: title };
      return anchor;
    } catch (e) {
      return undefined;
    }
  }

  /* 0.9.67 (Android ListBoxHelper.resolveSectionLanding parity): fold the
   * chapter anchor back to a page on THIS device. -1 = unusable (TOC not
   * loaded / entry missing / title fingerprint mismatch / bad interval) —
   * the caller falls back to percent. The fingerprint check is the safety
   * boundary: the two ends may have different TOC entry counts, so only a
   * title match allows indexing the interval by ordinal. */
  private resolveSectionLanding(bm: Bookmark): number {
    try {
      if (this.totalPages <= 0 || this.tocEntries.length === 0) {
        return -1;
      }
      const sec: number = bm.sec !== undefined ? bm.sec : -1;
      const sr: number = bm.sr !== undefined ? bm.sr : -1;
      const st: string = bm.st !== undefined ? bm.st : '';
      if (sec < 0 || sr < 0 || sr >= 1 || sec >= this.tocEntries.length) {
        return -1;
      }
      const item: TocEntry = this.tocEntries[sec];
      if (st.length === 0 || st !== item.title) {
        return -1; /* fingerprint mismatch */
      }
      const start0: number = item.page;
      if (start0 < 0 || start0 >= this.totalPages) {
        return -1;
      }
      let end0: number = this.totalPages;
      for (let j = sec + 1; j < this.tocEntries.length; j++) {
        const tpj: number = this.tocEntries[j].page;
        if (tpj > start0) {
          end0 = tpj;
          break;
        }
      }
      if (end0 <= start0) {
        return -1;
      }
      const target: number = start0 + Math.round(sr * (end0 - start0));
      return Math.max(start0, Math.min(end0 - 1, Math.min(target, this.totalPages - 1)));
    } catch (e) {
      return -1;
    }
  }

  /* 0.9.67 (Android ListBoxHelper.resolveLandingPage parity): landing
   * priority — era-exact absolute page → chapter anchor → percent fallback.
   * Shared by the jump and every displayed bookmark page number so the shown
   * page always equals the landing. */
  private bmLandingPage(bm: Bookmark): number {
    if (bm.pgs > 0 && bm.pgs === this.totalPages && bm.page >= 0 && bm.page < this.totalPages) {
      return bm.page;
    }
    const bySec: number = this.resolveSectionLanding(bm);
    if (bySec >= 0) {
      return bySec;
    }
    if (bm.pgs > 0) {
      const target: number = Math.round(Math.max(0, Math.min(bm.page / bm.pgs, 0.999)) * this.totalPages);
      return Math.max(0, Math.min(target, this.totalPages - 1));
    }
    return Math.max(0, Math.min(bm.page, Math.max(this.totalPages - 1, 0)));
  }

  /* 0.9.23 (Android 10-09 parity): a bookmark's absolute page is a valid
   * anchor only inside its layout era. pgs = the total the book had when
   * the bookmark was made; -1 = legacy (no epoch, absolute anchor).
   * Cross-era (typography change, sync from another device): the percentage
   * position is stable across eras, so land at page/pgs x full count; while
   * the staged canvas is still growing, wait for phase two (15s cap, then
   * clamp) instead of silently landing short. */
  private jumpToBookmark(bm: Bookmark): void {
    const closePanel: () => void = () => {
      this.showBookmarks = false;
    };
    const land: (t: number) => void = (t: number) => {
      this.pushPageHistory();
      this.seekToPage(t);
      closePanel();
    };
    /* 0.9.67 (Android ListBoxHelper.jumpToBookmark/resolveLandingPage parity):
     * landing priority — era-exact absolute page → chapter anchor (survives
     * phone/PAD page-count differences and re-layouts) → percent fallback.
     * Any non-exact landing needs the final layout, so wait for phase two
     * (whenCanvasReady: 15s cap, then best effort with the current canvas). */
    const landResolved: () => void = (): void => {
      if (bm.pgs > 0 && bm.pgs === this.totalPages && bm.page >= 0 && bm.page < this.totalPages) {
        tlog('bookmark_jump', 'page=' + (bm.page + 1) + ' pgs=' + bm.pgs);
        console.info(`[Reader] Jumped to bookmark page ${bm.page + 1}`);
        land(bm.page);
        return;
      }
      const bySec: number = this.resolveSectionLanding(bm);
      if (bySec >= 0) {
        const secN: number = bm.sec !== undefined ? bm.sec : -1;
        tlog('bookmark_anchor_jump', 'sec=' + secN + ' from=' + (bm.page + 1) + ' to=' + (bySec + 1) + ' full=' + this.totalPages);
        console.info(`[Reader] Bookmark anchor jump: sec ${secN} -> page ${bySec + 1}/${this.totalPages}`);
        land(bySec);
        return;
      }
      if (bm.pgs > 0) {
        const target: number = Math.max(0, Math.min(
          Math.round(Math.max(0, Math.min(bm.page / bm.pgs, 0.999)) * this.totalPages),
          this.totalPages - 1));
        tlog('bookmark_era_jump', 'from=' + (bm.page + 1) + ' pgs=' + bm.pgs + ' to=' + (target + 1) + ' full=' + this.totalPages);
        console.info(`[Reader] Bookmark era jump: page ${bm.page + 1}/${bm.pgs} -> ${target + 1}/${this.totalPages}`);
        land(target);
        return;
      }
      /* legacy no-epoch bookmark: best effort inside the current layout */
      land(Math.max(0, Math.min(bm.page, this.totalPages - 1)));
    };
    if (this.layoutComplete) {
      landResolved();
    } else {
      this.whenCanvasReady(() => this.layoutComplete, landResolved);
    }
  }

  /* 0.9.23: run onDone once the staged canvas satisfies pred(); polls every
   * 500ms with a 15s cap, then fires on whatever the canvas holds (the same
   * clamp a user sees when the book genuinely ends early). */
  private whenCanvasReady(pred: () => boolean, onDone: () => void): void {
    /* 0.9.65: local timer tracked in jumpWaitTimers — concurrent waits no
     * longer overwrite each other's handle (the old shared jumpWaitTimer
     * made the second wait drop the first one's completion and leak its
     * interval). */
    let timer: number = -1;
    const start: number = Date.now();
    const fire: () => void = () => {
      if (timer >= 0) {
        clearInterval(timer);
        const idx: number = this.jumpWaitTimers.indexOf(timer);
        if (idx >= 0) {
          this.jumpWaitTimers.splice(idx, 1);
        }
        timer = -1;
      }
      onDone();
    };
    if (pred()) {
      fire();
      return;
    }
    timer = setInterval(() => {
      if (pred() || Date.now() - start >= 15000) {
        fire();
      }
    }, 500);
    this.jumpWaitTimers.push(timer);
  }

  /* 0.9.23 (Android 10-09 pgc era-restore parity): the phase-two full count
   * is now the era of record. A window-period page flip saved the position
   * with the moving window end as the epoch - rewrite the entry with the full
   * count. If the stored epoch disagrees with the full count, the book was
   * re-laid out since the last save (typography change, sync from another
   * device): the absolute page no longer points at the same content, so
   * re-land by percentage (stable across eras) and resave. */
  private async settleProgressEpoch(fullTotal: number): Promise<void> {
    if (this.documentPath.length === 0) {
      return;
    }
    const ctx = getContext(this);
    const pathKey: string = this.logicalPath.length > 0 ? this.logicalPath : this.documentPath;
    const book: RecentBook | undefined = await getRecentBookForPath(ctx, pathKey);
    if (book === undefined) {
      return;
    }
    const epoch: number = book.totalPages;
    if (epoch > 0 && epoch !== fullTotal) {
      const landAt: number = Math.max(0, Math.min(
        Math.round(book.page / epoch * fullTotal), fullTotal - 1));
      if (landAt !== this.currentPage) {
        tlog('era_restore', 'from=' + (this.currentPage + 1) + ' to=' + (landAt + 1) + ' epoch=' + epoch + ' full=' + fullTotal);
        this.seekToPage(landAt);
      }
    }
    const settled: RecentBook = {
      path: book.path,
      title: book.title,
      page: this.currentPage,
      totalPages: fullTotal,
      lastRead: book.lastRead.length > 0 ? book.lastRead : new Date().toISOString(),
      star: book.star,
      coverPath: book.coverPath,
      author: book.author,
      tags: book.tags,
      series: book.series,
      genre: book.genre,
      status: book.status
    };
    saveRecentBook(ctx, settled);
    tlog('progress_settled', 'page=' + (this.currentPage + 1) + ' epoch=' + fullTotal);
  }

  // ---- Annotations (C2 highlight) ----
  private async refreshAnnotations(): Promise<void> {
    const handle: ESObject | undefined = this.dataSource.getDocHandle();
    if (handle === undefined) {
      return;
    }
    try {
      /* op 11 (annots) runs on the docOpAsync worker for local and remote
       * documents alike. The sync getAnnotations() call here used to block
       * the UI thread on g_mu behind queued page renders - THREAD_BLOCK_6S
       * appfreeze on rapid page turns of large PDFs (2026-09-21). */
      const j = await this.docOp(11, this.currentPage);
      this.annotations = j !== undefined ? JSON.parse(j) as AnnotationInfo[] : [];
      console.info(`[Reader] Page ${this.currentPage + 1} annotations: ${this.annotations.length}`);
    } catch (e) {
      this.annotations = [];
      console.error('[Reader] getAnnotations failed');
    }
  }

  /** Add a highlight over the current search hit region (or page center). */
  private async addHighlightOnPage(): Promise<void> {
    if (this.isRemote) {
      promptAction.showToast({ message: '远程文档暂不支持批注编辑（下载后可编辑）' });
      return;
    }
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
    if (this.isRemote) {
      promptAction.showToast({ message: '远程文档暂不支持批注编辑（下载后可编辑）' });
      return;
    }
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
    if (this.isRemote) {
      promptAction.showToast({ message: '远程文档暂不支持批注编辑（下载后可编辑）' });
      return;
    }
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
    if (this.isRemote) {
      promptAction.showToast({ message: '远程文档暂不支持批注编辑（下载后可编辑）' });
      return;
    }
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
    if (this.isRemote) {
      promptAction.showToast({ message: '远程文档暂不支持批注编辑（下载后可编辑）' });
      return;
    }
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
    if (p < 0) {
      return;
    }
    /* 0.9.23: a deep annotation can sit beyond the still-growing staged
     * canvas - land when it grows instead of silently no-oping. */
    this.whenCanvasReady(() => this.totalPages > p, () => {
      const deferred: number = this.totalPages > p ? 0 : 1;
      this.showAnnPanel = false;
      tlog('ann_jump', 'page=' + (p + 1) + ' deferred=' + deferred);
      this.seekToPage(p);
      console.info(`[Reader] Jump to annotation page ${this.currentPage + 1}`);
    });
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
    this.seekToPage(p);
    console.info(`[Reader] Back to page ${this.currentPage + 1} (history)`);
  }

  /* 0.9.64: the single programmatic seek used by every jump entry (jump
   * dialog, bookmarks, TOC, thumbnails, search hits, annotation list, page
   * history, seek slider, tap zones / volume keys, auto-flip, TTS / RSVP).
   * Horizontal flips through the Swiper .index() value-change replay; the
   * vertical List has no index binding, so it needs an explicit
   * scrollToIndex — without it only the progress bar moved while the page
   * stayed put (bookmark-jump / reopen-position reports). */
  private seekToPage(target: number, forceScroll: boolean = false): void {
    const t: number = Math.max(0, Math.min(target, this.totalPages - 1));
    if (this.scrollMode === 1 && (forceScroll || t !== this.currentPage)) {
      try {
        this.vScroller.scrollToIndex(t, false);
      } catch (_e) {
        /* scroller not attached yet (List still mounting): the state below
         * lands the indicator, but the List itself stays where it mounted —
         * callers add an idempotent re-scroll to recover (landOnRestoredPage
         * / landAfterModeSwitch) */
      }
    }
    this.currentPage = t;
    this.onPageChanged(t);
  }

  /* ---- Round 6: jump-to-page dialog + vertical fast-scroll rail ---- */

  /** Apply the typed page number (1-based input, clamped). */
  private doJump(): void {
    const n: number = parseInt(this.jumpInput, 10);
    if (isNaN(n) || n < 1 || n > this.totalPages) {
      promptAction.showToast({ message: `页码范围 1-${this.totalPages}` });
      return;
    }
    this.pushPageHistory();
    this.showJump = false;
    const target: number = n - 1;
    this.seekToPage(target);
    console.info(`[Reader] Jump to page ${n}`);
    tlog('jump_page_done', 'page=' + n);
  }

  /** Fast-scroll rail: map vertical pan distance onto the page index. */
  private fastPanUpdate(offsetY: number): void {
    const railH: number = this.pageAreaH > 0 ? this.pageAreaH : 600;
    const delta: number = Math.round(offsetY / Math.max(railH, 1) * this.totalPages);
    const target: number = Math.max(0, Math.min(this.fastStartPage + delta, this.totalPages - 1));
    if (target !== this.currentPage) {
      this.seekToPage(target);
    }
    try {
      this.vScroller.scrollToIndex(target, false);
    } catch (_e) { /* scroller not attached */ }
  }

  /** Round 6: try to unlock the encrypted document with the typed password. */
  private async doUnlockDoc(): Promise<void> {
    const handle: ESObject | undefined = this.dataSource.getDocHandle();
    if (handle === undefined || this.pwdInput.length === 0) {
      return;
    }
    try {
      let r = 0;
      if (this.isRemote) {
        const j = await this.docOp(12, this.pwdInput);
        r = j !== undefined ? parseInt(j, 10) : 0;
      } else {
        r = authenticateDocument(handle, this.pwdInput) as number;
      }
      if (r > 0) {
        this.showPwd = false;
        this.pwdInput = '';
        /* force every page renderer to retry now that the doc is unlocked */
        this.renderEpoch++;
        console.info('[Reader] Document unlocked');
      } else {
        promptAction.showToast({ message: $r('app.string.pwd_wrong') });
      }
    } catch (e) {
      promptAction.showToast({ message: $r('app.string.pwd_unlock_failed') });
    }
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
    if (this.useSystemBrightness) {
      console.info('[Reader] Brightness: system-managed, skip override');
      return;
    }
    this.brightnessLevel = Math.max(this.allowZeroBright ? 0 : 1, Math.min(255, Math.round(level)));
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
        this.seekToPage(this.currentPage + 1);
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
    this.ttsStatus = this.L($r('app.string.tts_preparing'));
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
        this.ttsStatus = count === -1 ? this.L($r('app.string.tts_engine_unavailable')) : this.L($r('app.string.tts_no_voice'));
        console.warn(`[Reader] TTS probe: ${count}`);
      } else {
        this.ttsEngineOk = true;
        console.info(`[Reader] TTS probe: ${count} voices`);
      }
    } catch (e) {
      this.ttsEngineOk = false;
      this.ttsStatus = this.L($r('app.string.tts_web_unavailable'));
      console.error('[Reader] TTS probe failed');
    }
  }

  /** Page text via sync getText (local) or docOpAsync (remote-stream safe). */
  private async pageText(page: number): Promise<string> {
    if (this.isRemote) {
      const j = await this.docOp(4, page, 1.0);
      if (j === undefined) {
        return '';
      }
      try {
        return JSON.parse(j) as string;
      } catch (e) {
        return '';
      }
    }
    /* Local docs go through docOpAsync too: sync getText() would hold the
     * UI thread on g_mu behind queued page renders (TTS/RSVP re-anchor on
     * every page change; appfreeze fix 2026-09-21). */
    try {
      const j = await this.docOp(4, page, 1.0);
      if (j === undefined) {
        return '';
      }
      return JSON.parse(j) as string;
    } catch (e) {
      return '';
    }
  }

  private async loadTtsSentences(page: number): Promise<void> {
    const handle: ESObject | undefined = this.dataSource.getDocHandle();
    if (handle === undefined) {
      this.ttsSentences = [];
      return;
    }
    try {
      const raw: string = await this.pageText(page);
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
        this.ttsStatus = code === -2 ? this.L($r('app.string.tts_no_voice')) : this.L($r('app.string.tts_engine_unavailable'));
        this.stopTtsPoll();
        console.warn(`[Reader] TTS speak rejected: ${code}`);
        return;
      }
      this.ttsStatus = this.L($r('app.string.tts_speaking'));
      this.ttsPaused = false;
      this.startTtsPoll();
      /* Round 5: keep app alive + ongoing notification while reading aloud */
      startTtsBackgroundTask(getContext(this));
      this.refreshTtsNotification();
      console.info(`[Reader] TTS speaking ${this.ttsIndex + 1}/${this.ttsSentences.length} (page ${this.currentPage + 1})`);
    } catch (e) {
      this.ttsEngineOk = false;
      this.ttsStopped = true;
      this.ttsStatus = this.L($r('app.string.tts_web_unavailable'));
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
    /* Round 6: mirror the state to the TtsCard desktop card */
    syncTtsCardState(getContext(this), this.docTitle, sent, !this.ttsStopped && !this.ttsPaused);
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
        this.ttsStatus = this.L($r('app.string.tts_paused'));
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
        this.ttsStatus = this.L($r('app.string.tts_speaking'));
        this.refreshTtsNotification();
        console.info('[Reader] TTS resumed');
      }).catch(() => {
      });
    } else {
      this.webController.runJavaScript('ttsPause()').then((r: string) => {
        this.ttsPaused = true;
        this.ttsStatus = this.L($r('app.string.tts_paused'));
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
    /* Round 6: clear the TtsCard state */
    syncTtsCardState(getContext(this), this.docTitle, '', false);
    this.ttsStatus = this.L($r('app.string.tts_stopped'));
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
      this.ttsStatus = this.L($r('app.string.tts_finished'));
      console.info('[Reader] TTS finished (last page)');
      return;
    }
    this.ttsAdvancing = true;
    this.seekToPage(this.currentPage + 1);
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
      const raw: string = await this.pageText(this.currentPage);
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
      this.ttsStatus = this.L($r('app.string.common_export_failed'));
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
      const raw: string = await this.pageText(page);
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
      this.rsvpProgress = this.L($r('app.string.common_done'));
      console.info('[Reader] RSVP finished (last page)');
      return;
    }
    this.rsvpAdvancing = true;
    this.seekToPage(this.currentPage + 1);
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
  /* ---- 0.9.33 P2-11: user fonts ---- */

  /** Re-read the imported-font list, dropping entries whose file is gone. */
  private refreshUserFonts(): void {
    let list: UserFontRec[] = [];
    try {
      list = JSON.parse(this.userFontList) as UserFontRec[];
    } catch (e) {
      list = [];
    }
    const keep: UserFontRec[] = [];
    for (const f of list) {
      let alive = false;
      try {
        alive = fs.accessSync(f.path) === true;
      } catch (e) {
        alive = false;
      }
      if (alive) {
        keep.push(f);
      }
    }
    this.userFonts = keep;
    if (this.userFontPath.length > 0) {
      let found = false;
      for (const f of keep) {
        if (f.path === this.userFontPath) {
          found = true;
          break;
        }
      }
      if (!found) {
        this.userFontPath = '';
        this.userFontName = '';
      }
    }
  }

  /** Import a font file through the system picker into filesDir/fonts. */
  private async importUserFont(): Promise<void> {
    try {
      const ctx = getContext(this);
      let documentPicker = new picker.DocumentViewPicker(ctx);
      let options: picker.DocumentSelectOptions = {
        fileSuffixFilters: ['TTF|ttf', 'OTF|otf', 'TTC|ttc'],
        maxSelectNumber: 1
      };
      const uris: Array<string> = await documentPicker.select(options);
      if (uris.length === 0) {
        return;
      }
      const tmp: string = importUriToSandbox(ctx, uris[0]);
      if (tmp.length === 0) {
        promptAction.showToast({ message: '字体导入失败' });
        return;
      }
      let base: string = tmp.substring(tmp.lastIndexOf('/') + 1);
      const dot: number = base.lastIndexOf('.');
      if (dot > 0) {
        base = base.substring(0, dot);
      }
      base = base.replace(/[^0-9A-Za-z_-]/g, '_');
      if (base.length === 0) {
        base = 'font';
      }
      const dir: string = ctx.filesDir + '/fonts';
      try {
        fs.mkdirSync(dir, true);
      } catch (e) { /* exists */ }
      const dest: string = dir + '/' + base + '.ttf';
      /* copy out of cacheDir: the system may purge cache at any time */
      const srcFile: fs.File = fs.openSync(tmp, fs.OpenMode.READ_ONLY);
      const st: fs.Stat = fs.statSync(srcFile.fd);
      const buf: ArrayBuffer = new ArrayBuffer(st.size);
      fs.readSync(srcFile.fd, buf);
      fs.closeSync(srcFile);
      const outFile: fs.File = fs.openSync(dest,
        fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      try {
        fs.writeSync(outFile.fd, buf);
      } finally {
        fs.closeSync(outFile);
      }
      try {
        fs.unlinkSync(tmp);
      } catch (e) { /* best effort */ }
      let list: UserFontRec[] = [];
      try {
        list = JSON.parse(this.userFontList) as UserFontRec[];
      } catch (e) {
        list = [];
      }
      let dup = false;
      for (const f of list) {
        if (f.path === dest) {
          dup = true;
          break;
        }
      }
      if (!dup) {
        const rec: UserFontRec = { name: base, path: dest };
        list.push(rec);
        this.userFontList = JSON.stringify(list);
      }
      this.userFontPath = dest;
      this.userFontName = base;
      this.refreshUserFonts();
      this.applyTypography();
      this.persistSettings();
      promptAction.showToast({ message: '字体已导入：' + base });
    } catch (e) {
      console.error('[Reader] importUserFont failed');
    }
  }

  /** Activate an imported font (re-parse picks it up via the css change). */
  private selectUserFont(f: UserFontRec): void {
    if (this.userFontPath === f.path) {
      return;
    }
    this.userFontPath = f.path;
    this.userFontName = f.name;
    this.applyTypography();
    this.persistSettings();
  }

  private buildCss(): string {
    /* 0.9.59: 4-way page margins (Android dialog_reading_pref 页边距) */
    const mt = Math.round(this.marginT);
    const mb = Math.round(this.marginB);
    const ml = Math.round(this.marginLeft);
    const mr = Math.round(this.marginRight);
    const ls = Math.round(this.letterSpacing);
    const alignNames: string[] = ['left', 'center', 'right', 'justify'];
    const fontFamilies: string[] = ['inherit', 'serif', 'sans-serif', 'monospace'];
    const lsCss: string = ls > 0 ? ` letter-spacing: ${ls}px;` : '';
    let css = '';
    if (this.userFontPath.length > 0) {
      /* 0.9.33 P2-11: the engine resolves the font file itself
       * (fz_add_css_font_face falls back to fz_read_file on the raw src) */
      css += `@font-face { font-family: 'custom'; src: url('${this.userFontPath}'); }`;
      css += ` body { margin: ${mt}pt ${mr}pt ${mb}pt ${ml}pt;`;
      css += ` line-height: ${this.lineHeight.toFixed(2)};`;
      css += ` text-align: ${alignNames[this.textAlign]};${lsCss}`;
      css += ` font-family: 'custom', serif;`;
    } else {
      css += `body { margin: ${mt}pt ${mr}pt ${mb}pt ${ml}pt;`;
      css += ` line-height: ${this.lineHeight.toFixed(2)};`;
      css += ` text-align: ${alignNames[this.textAlign]};${lsCss}`;
      css += ` font-family: ${fontFamilies[this.fontFamilyIdx]};`;
    }
    if (this.hyphenationEnabled) {
      css += ` hyphens: auto;`;
    }
    css += ' }';
    css += ` p { margin-top: 0; margin-bottom: ${this.paragraphSpacing}px; text-indent: ${this.textIndent}em; }`;
    if (this.dropCapEnabled) {
      css += ` .dropcap::first-letter, p:first-of-type::first-letter { font-size: 2.5em; float: left; line-height: 0.8; margin-right: 4px; }`;
    }
    /* 0.9.34 P2-13 (Android MagicHelper parity): night/ink palettes for LOCAL
     * reflowable docs are injected as user CSS so the engine paints the real
     * background + text colors instead of the reader inverting the pixmap
     * (which blurs glyphs). Remote reflow keeps the invert path. */
    if (this.isReflow && !this.isRemote) {
      if (this.nightMode) {
        const bg: string = this.theme === 2 ? '#000000' : '#101010';
        const fg: string = this.theme === 2 ? '#b0b0b0' : '#d0d0d0';
        css += `html { background-color: ${bg} !important; }`;
        css += `body { background-color: ${bg} !important; color: ${fg} !important; }`;
        css += this.linkColorNight.length > 0 ? ` a { color: ${this.linkColorNight} !important; }` : ` a { color: #7aa2ff !important; }`;
      } else if (this.theme === 3) {
        css += `html { background-color: #eaddc3 !important; }`;
        css += `body { background-color: #eaddc3 !important; color: #2a2118 !important; }`;
      }
    }
    if (this.linkColorDay.length > 0 && !this.nightMode && this.theme !== 3) {
      css += ` a { color: ${this.linkColorDay} !important; }`;
    }
    return css;
  }

  private async applyTypography(): Promise<void> {
    try {
      const h: ESObject | undefined = this.dataSource.getDocHandle();
      if (h === undefined) return;
      if (!this.isReflow) return;

      const css = this.buildCss();
      console.info(`[Reader] Typography applied: align=${this.textAlign} para=${this.paragraphSpacing} indent=${this.textIndent} dropcap=${this.dropCapEnabled} font=${this.fontFamilyIdx}`);
      let newTotal = -1;
      if (this.isRemote) {
        const r = await this.docOp(3, this.reflowW, this.reflowH, this.fontSize, css);
        if (r !== undefined) {
          newTotal = parseInt(r, 10);
        }
      } else {
        layoutDocument(h, this.reflowW, this.reflowH, this.fontSize, css);
        /* L2: refresh page count after re-layout */
        newTotal = pageCount(h);
      }
      if (newTotal < 0) {
        return;
      }
      this.pageList = [];
      for (let i = 0; i < newTotal; i++) {
        this.pageList.push(i);
      }
      this.totalPages = newTotal;
      this.currentPage = Math.min(this.currentPage, newTotal - 1);
      this.reflowVersion++;
      /* 0.9.65: parity with applyReflow — notify the indicator/progress when
       * the clamp moved the page and force the vertical List (no index
       * binding) onto the clamped page (the scroll part no-ops horizontally) */
      this.seekToPage(this.currentPage, true);
      /* 0.9.65 rev2: parity with applyReflow — persist the new era's
       * accelerator, or the next first-open lays out from the STALE accel
       * and lands in a mismatched era (observed 80 vs 82 on emulator) */
      this.saveAccel();
      /* 0.9.66: finish applyReflow parity — refresh the TOC page numbers
       * for the new era and persist the typography right away (before this,
       * the change lived only in component state until exit, and TOC kept
       * the old era's page numbers) */
      this.loadToc();
      this.persistSettings();
    } catch (e) {
      console.error('[Reader] applyTypography failed');
    }
  }

  /** Load text line rectangles for the current page (sprint H2). */
  private async loadTextRects(): Promise<void> {
    try {
      const h: ESObject | undefined = this.dataSource.getDocHandle();
      if (h === undefined) return;
      let json: string;
      if (this.isRemote) {
        const j = await this.docOp(7, this.currentPage);
        json = j !== undefined ? j : '[]';
      } else {
        json = getTextRects(h, this.currentPage);
      }
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

  /** Round 9: labels for the unified tap-zone action codes (index = code). */
  /** Execute a tap-zone action. Round 9: unified action codes shared by all
   *  zones (and by the volume keys): 0=prev 1=next 2=menu 3=toc 4=bookmark
   *  5=brightness 6=fullscreen 7=none. */
  /* ---- 0.9.59: 4-tab reader pref helpers ---- */

  private edgeRatio(): number {
    return this.tapEdgePercent >= 5 && this.tapEdgePercent <= 45 ? this.tapEdgePercent / 100 : 0.25;
  }

  private prefTabs(): Array<Resource> {
    return [
      $r('app.string.pf_visual'),
      $r('app.string.pf_layout'),
      $r('app.string.pf_status'),
      $r('app.string.pf_controls')
    ];
  }

  private zoneActionNames(): Array<Resource> {
    return [
      $r('app.string.action_prev_page'),
      $r('app.string.action_next_page'),
      $r('app.string.pf_a_bars'),
      $r('app.string.reader_contents'),
      $r('app.string.reader_bookmark'),
      $r('app.string.reader_brightness_n'),
      $r('app.string.action_fullscreen'),
      $r('app.string.set_action_none')
    ];
  }

  private actionName(i: number): string {
    const arr: Array<Resource> = this.zoneActionNames();
    return this.L(arr[Math.max(0, Math.min(arr.length - 1, i))]);
  }

  private orientName(): string {
    if (this.scrOrient === 2) {
      return this.L($r('app.string.orient_landscape'));
    }
    if (this.scrOrient === 3) {
      return this.L($r('app.string.orient_portrait'));
    }
    return this.L($r('app.string.orient_system'));
  }

  private cycleOrientation(): void {
    this.scrOrient = this.scrOrient === 0 ? 2 : (this.scrOrient === 2 ? 3 : 0);
    this.persistSettings();
    this.applyOrientationNow();
    console.info(`[Reader] Screen orientation: ${this.scrOrient}`);
  }

  private applyOrientationNow(): void {
    try {
      window.getLastWindow(getContext(this)).then((win: window.Window) => {
        const o: number = this.scrOrient;
        const target = o === 2 ? window.Orientation.LANDSCAPE
          : o === 3 ? window.Orientation.PORTRAIT
            : window.Orientation.UNSPECIFIED;
        win.setPreferredOrientation(target).catch((err: Error) => {
          console.error('[Reader] setPreferredOrientation failed');
        });
      });
    } catch (e) {
      console.error('[Reader] orientation apply failed');
    }
  }

  private restName(): string {
    return this.restRemindMin === 0 ? this.L($r('app.string.pf_never')) : `${this.restRemindMin} min`;
  }

  private cycleRestReminder(): void {
    const steps: number[] = [0, 15, 30, 60];
    const idx: number = steps.indexOf(this.restRemindMin);
    this.restRemindMin = steps[(idx + 1) % steps.length];
    this.restartReminder();
    this.persistSettings();
  }

  private restartReminder(): void {
    if (this.reminderTimerId >= 0) {
      clearTimeout(this.reminderTimerId);
      this.reminderTimerId = -1;
    }
    if (this.restRemindMin > 0) {
      const mins: number = this.restRemindMin;
      this.reminderTimerId = setTimeout(() => {
        try {
          promptAction.showToast({ message: '已连续阅读 ' + mins + ' 分钟，建议休息一下' });
        } catch (e) {
          console.error('[Reader] reminder toast failed');
        }
        this.reminderTimerId = -1;
      }, mins * 60000);
    }
  }

  private doubleName(): string {
    return this.doubleClickPref === 1 ? this.L($r('app.string.pf_crop_borders')) : this.L($r('app.string.pf_fit_page'));
  }

  private volKeyName(): string {
    return this.volumeKeyAction === 0 ? this.L($r('app.string.pf_disabled')) : this.L($r('app.string.pf_enabled'));
  }

  private tapEdgeName(): string {
    return `${this.tapEdgePercent}% ${this.L($r('app.string.pf_customize'))}`;
  }

  private cycleTapEdge(): void {
    const steps: number[] = [10, 15, 20, 25, 30, 40];
    const idx: number = steps.indexOf(this.tapEdgePercent);
    this.tapEdgePercent = steps[(idx + 1) % steps.length];
    this.persistSettings();
  }

  private progressScaleName(): string {
    return this.progressScale === 1 ? this.L($r('app.string.pf_percent')) : this.L($r('app.string.pf_number'));
  }

  private chapterFmtName(): string {
    return this.chapterFmtIdx === 1 ? '第2章 – 3 / 20' : 'Chapter II – 3 / 20';
  }

  private alignName(): string {
    const names: Array<Resource> = [$r('app.string.pf_align_left'), $r('app.string.pf_align_center'), $r('app.string.pf_align_right'), $r('app.string.pf_align_justify')];
    return this.L(names[Math.max(0, Math.min(3, this.textAlign))]);
  }

  private famName(idx: number): string {
    const names: Array<Resource> = [$r('app.string.pf_font_default'), $r('app.string.pf_font_serif'), $r('app.string.pf_font_sans'), $r('app.string.pf_font_mono')];
    return this.L(names[Math.max(0, Math.min(3, idx))]);
  }

  private fontDisplayName(): string {
    if (this.userFontName.length > 0) {
      return this.userFontName;
    }
    return this.famName(this.fontFamilyIdx);
  }

  private styleName(): string {
    if (this.styleMode === 0) {
      return this.L($r('app.string.pf_style_doc'));
    }
    if (this.styleMode === 1) {
      return this.L($r('app.string.pf_style_user'));
    }
    return this.L($r('app.string.pf_style_doc_user'));
  }

  private hyphenLangName(): string {
    return this.hyphenLangDefault ? this.L($r('app.string.pf_default_value')) : this.L($r('app.string.pf_lang_zh'));
  }

  private resetTab0(): void {
    this.fontSize = 16;
    this.lineHeight = 1.6;
    this.letterSpacing = 0;
    this.paragraphSpacing = 8;
    this.textIndent = 2;
    this.marginT = 9;
    this.marginB = 6;
    this.marginLeft = 10;
    this.marginRight = 10;
    this.textAlign = 0;
    this.hyphenationEnabled = false;
    this.hyphenLangDefault = false;
    this.imageZoomScale = 2.0;
    this.styleMode = 2;
    this.userCssName = '';
    this.linkColorDay = '';
    this.linkColorNight = '';
    this.colorPickTarget = 0;
    this.applyReflow();
  }

  private resetTab1(): void {
    this.tapEdgePercent = 25;
    this.restRemindMin = 0;
    this.restartReminder();
    this.doubleClickPref = 0;
    this.autoScrollSpeed = 2;
    this.autoOffMinutes = 5;
    this.antialiasLevel = 8;
    this.dropCapEnabled = false;
    this.mirrorMode = false;
    this.allowTextSelection = true;
    this.perCharSelect = false;
    this.edgeBrightness = true;
    this.allowZeroBright = false;
    this.dayImageReplace = false;
    this.amoledEnhance = false;
    this.loopPaging = false;
    this.zoomLockedPages = true;
    this.backConfirmClose = false;
    this.selectionVibrate = true;
    this.decodeThreads = 10;
    this.drawThreads = 5;
    this.persistSettings();
  }

  /* 0.9.59: 返回手势关闭书籍时需确认 (排版选项; Android showTheBookCloseDialog) */
  onBackPress(): boolean {
    if (!this.backConfirmClose) {
      return false;
    }
    AlertDialog.show({
      title: this.L($r('app.string.pf_close_book')),
      message: this.L($r('app.string.pf_back_confirm')),
      primaryButton: {
        value: this.L($r('app.string.common_cancel')),
        action: (): void => {
        }
      },
      secondaryButton: {
        value: this.L($r('app.string.pf_confirm')),
        action: (): void => {
          router.back();
        }
      }
    });
    return true;
  }

  private onTapZoneAction(action: number, fromVolume: boolean = false): void {
    /* 0.9.55: locked books ignore TOUCH tap zones; the hardware volume keys
     * still turn pages (Android isLocked semantics; SM-04/FN-02) */
    if (this.inputLocked && !fromVolume) {
      promptAction.showToast({ message: $r('app.string.reader_locked') });
      return;
    }
    if (action === 0) {
      /* previous page (0.9.59: 循环翻页 wraps at the ends) */
      if (this.currentPage > 0) {
        this.seekToPage(this.currentPage - 1);
      } else if (this.loopPaging && this.totalPages > 1) {
        this.seekToPage(this.totalPages - 1);
      }
    } else if (action === 1) {
      /* next page */
      if (this.totalPages > 0 && this.currentPage < this.totalPages - 1) {
        this.seekToPage(this.currentPage + 1);
      } else if (this.loopPaging && this.totalPages > 1) {
        this.seekToPage(0);
      }
    } else if (action === 2) {
      /* 0.9.59: center tap shows/hides the top+bottom bars (Android hideShow) */
      this.barsVisible = !this.barsVisible;
      console.info(`[Reader] Bars toggled: ${this.barsVisible}`);
    } else if (action === 3) {
      this.showToc = true;
    } else if (action === 4) {
      this.toggleBookmark();
    } else if (action === 5) {
      /* brightness cycle (same stepping as the top-bar button) */
      this.brightnessLevel = this.brightnessLevel > 200 ? 64 : this.brightnessLevel + 32;
      this.setBrightness(this.brightnessLevel);
    } else if (action === 6) {
      this.toggleFullscreen();
    }
    /* 7 = no action */
    console.info(`[Reader] Tap zone action: ${action}`);
  }

  /** Round 9: volume-key navigation (Android enable_volume_keys).
   *  Returns true when the key was consumed (so the system does not adjust
   *  the volume). While auto-scrolling the keys adjust the scroll speed. */
  /** True while a modal panel/dialog covers the reading area. The tap zones
   *  must stop claiming touches then (hitTestBehavior below), or buttons in
   *  the jump/password dialogs and panel lists never receive them. */
  private zonesDisabled(): boolean {
    return this.showJump || this.showPwd || this.showToc || this.showBookmarks
      || this.showMenu || this.showThumbs || this.showTrans || this.showTransPanel
      || this.showExportDialog || this.showAnnPanel || this.showSearch
      || this.showSelNoteInput || this.showFileInfo || this.showPref
      || this.showTextReplace || this.showBookMenu || this.showNoteEdit
      || this.showPlPick || this.showAiIntro
      || this.showBmList || this.showBmAdd || this.showBmSort;
  }

  private onVolumeKey(keyCode: number): boolean {
    if (this.volumeKeyAction === 0) {
      return false;
    }
    const volUp: boolean = keyCode === 16; /* KEYCODE_VOLUME_UP */
    if (!volUp && keyCode !== 17 /* KEYCODE_VOLUME_DOWN */) {
      return false;
    }
    if (this.autoScroll) {
      if (volUp && this.autoScrollSpeed < 10) {
        this.autoScrollSpeed = this.autoScrollSpeed + 1;
      } else if (!volUp && this.autoScrollSpeed > 2) {
        this.autoScrollSpeed = this.autoScrollSpeed - 1;
      }
      console.info(`[Reader] Volume key: auto-scroll speed ${this.autoScrollSpeed}`);
      return true;
    }
    if (this.volumeKeyAction === 2) {
      if (volUp) {
        this.zoomIn();
      } else {
        this.zoomOut();
      }
      return true;
    }
    /* page turn (default) — bypasses the touch lock (hardware keys);
     * 0.9.59: 反转键位 swaps up/down */
    const up: boolean = this.invertVolume ? !volUp : volUp;
    this.onTapZoneAction(up ? 0 : 1, true);
    console.info(`[Reader] Volume key: ${volUp ? 'prev' : 'next'} page`);
    return true;
  }

  /** Round 9: subscribe the volume keys via inputConsumer 'keyPressed'
   *  (API 16+, the official app-side path for VOLUME_UP/DOWN). Silently
   *  skipped when the capability is unavailable. */
  private registerVolumeKeys(): void {
    try {
      const cfgUp: inputConsumer.KeyPressedConfig = { key: 16, action: 1, isRepeat: false };
      const cfgDown: inputConsumer.KeyPressedConfig = { key: 17, action: 1, isRepeat: false };
      inputConsumer.on('keyPressed', cfgUp, (e: mmiKeyEvent.KeyEvent) => {
        this.onVolumeKey(e.key.code);
      });
      inputConsumer.on('keyPressed', cfgDown, (e: mmiKeyEvent.KeyEvent) => {
        this.onVolumeKey(e.key.code);
      });
      console.info('[Reader] Volume keys registered');
    } catch (e) {
      console.info('[Reader] Volume keys unavailable: ' + (e instanceof Error ? e.message : String(e)));
    }
  }

  private unregisterVolumeKeys(): void {
    try {
      inputConsumer.off('keyPressed');
    } catch (e) {
      /* capability absent — nothing to clean up */
    }
  }

  /** Open file info dialog (sprint G6): show title/author/pages/format/size. */
  /** Human-readable remote address: full URL / smb://host/share/path / sftp://host/path. */
  private prettyRemoteUri(uri: string): string {
    const parts = parseRemoteUri(uri);
    if (parts === null) {
      return uri;
    }
    if (parts.type === 'webdav') {
      return parts.fileUrl;
    }
    const segs: string[] = parts.fileUrl.split('|');
    if (parts.type === 'smb' && segs.length >= 3) {
      return 'smb://' + segs[0] + '/' + segs[1] + segs[2];
    }
    if (parts.type === 'sftp' && segs.length >= 2) {
      return 'sftp://' + segs[0] + segs[1];
    }
    return uri;
  }

  /* Left-edge brightness drag (Android BrightnessHelper parity) */
  private brightPanBase: number = 0;
  private brightHintTimer: number = -1;

  private brightGestureStart(): void {
    this.brightPanBase = this.brightnessLevel;
    this.brightHintShow = true;
    this.brightHintText = '\u4eae\u5ea6 ' + Math.round(this.brightnessLevel / 255 * 100) + '%';
  }

  private brightGestureMove(offsetY: number): void {
    const lv: number = Math.max(1, Math.min(255, Math.round(this.brightPanBase + offsetY * 0.5)));
    this.setBrightness(lv);
    this.brightHintText = '\u4eae\u5ea6 ' + Math.round(lv / 255 * 100) + '%';
  }

  private brightGestureEnd(): void {
    if (this.brightHintTimer >= 0) {
      clearTimeout(this.brightHintTimer);
    }
    this.brightHintTimer = setTimeout(() => {
      this.brightHintShow = false;
    }, 1500);
  }

  private async openFileInfo(): Promise<void> {
    const rows: FiRow[] = [];
    try {
      /* 0.9.61: structured file info (Android FileInformationDialog parity) */
      const meta: Record<string, string> = {};
      const h: ESObject | undefined = this.dataSource.getDocHandle();
      if (h !== undefined) {
        if (this.isRemote) {
          try {
            const j = await this.docOp(8);
            if (j !== undefined) {
              const info = JSON.parse(j) as Record<string, string>;
              meta['title'] = info['title'] !== undefined ? String(info['title']) : '';
              meta['author'] = info['author'] !== undefined ? String(info['author']) : '';
              meta['subject'] = info['subject'] !== undefined ? String(info['subject']) : '';
              meta['producer'] = info['producer'] !== undefined ? String(info['producer']) : '';
              meta['creationDate'] = info['creationDate'] !== undefined ? String(info['creationDate']) : '';
            }
          } catch (_e) { /* ignore */ }
        } else {
          try {
            const info: DocumentInfo = getDocumentInfo(h);
            meta['title'] = info.title !== undefined ? info.title : '';
            meta['author'] = info.author !== undefined ? info.author : '';
            meta['subject'] = info.subject !== undefined ? info.subject : '';
            meta['producer'] = info.producer !== undefined ? info.producer : '';
            meta['creationDate'] = info.creationDate !== undefined ? info.creationDate : '';
          } catch (_e) { /* ignore */ }
        }
      }
      const cd = meta['creationDate'] || '';
      const yi = cd.search(/\d{4}/);
      const year = yi >= 0 ? cd.substring(yi, yi + 4) : '';
      rows.push({ label: this.L($r('app.string.fi_label_title')), value: meta['title'] || this.docTitle });
      rows.push({ label: this.L($r('app.string.fi_label_author')), value: meta['author'] });
      rows.push({ label: this.L($r('app.string.fi_label_subject')), value: meta['subject'] });
      rows.push({ label: this.L($r('app.string.fi_label_year')), value: year });
      rows.push({ label: this.L($r('app.string.fi_label_publisher')), value: meta['producer'] });
      rows.push({ label: this.L($r('app.string.fi_label_isbn')), value: '' });
      if (this.isRemote) {
        rows.push({ label: this.L($r('app.string.fi_remote_source')), value: this.prettyRemoteUri(this.remoteUri) });
      } else {
        try {
          const stat = fs.statSync(this.documentPath);
          rows.push({ label: this.L($r('app.string.fi_label_size')), value: Math.round(stat.size / 1024) + ' KB' });
        } catch (_e) { /* ignore */ }
        const dotIdx = this.documentPath.lastIndexOf('.');
        const ext = dotIdx >= 0 ? this.documentPath.substring(dotIdx + 1).toUpperCase() : 'UNKNOWN';
        rows.push({ label: this.L($r('app.string.fi_label_format')), value: ext });
      }
      rows.push({ label: this.L($r('app.string.fi_label_pages')), value: this.totalPages > 0 ? String(this.totalPages) : this.L($r('app.string.common_unknown')) });
      this.fiRows = rows;
      console.info(`[Reader] File info generated for ${this.docTitle}`);
    } catch (e) {
      this.fiRows = [];
    }
    /* cover (Android dialog_file_info shows the cover): prefer the shelf
     * cover PNG, fall back to rendering page 0 */
    this.fiCoverPath = '';
    this.fiCover = undefined;
    try {
      const rec: RecentBook | undefined = await getRecentBookForPath(getContext(this), this.logicalPath.length > 0 ? this.logicalPath : this.documentPath);
      if (rec !== undefined && rec.coverPath.length > 0) {
        this.fiCoverPath = rec.coverPath;
      }
    } catch (_e) { /* ignore */ }
    if (this.fiCoverPath.length === 0) {
      try {
        const h2: ESObject | undefined = this.dataSource.getDocHandle();
        if (h2 !== undefined) {
          const res: RenderResult = await renderPageAsync(h2, 0, { zoom: 0.18 });
          this.fiCover = await image.createPixelMap(res.data, { size: { width: res.width, height: res.height } });
        }
      } catch (_e) {
        this.fiCover = undefined;
      }
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

  /* ===== 0.9.60: Android ShareDialog/NoteEditDialog parity ===== */

  /** Android NoteEditDialog parity: open the note editor prefilled with the
   * current page's bookmark label (the note text is the bookmark label). */
  private openNoteEdit(): void {
    this.noteEditText = '';
    getBookmarks(getContext(this), this.documentPath).then((list: Bookmark[]) => {
      for (let i = 0; i < list.length; i++) {
        if (list[i].page === this.currentPage) {
          this.noteEditText = list[i].label;
          break;
        }
      }
    });
    this.showNoteEdit = true;
    console.info(`[Reader] Note editor opened on page ${this.currentPage + 1}`);
  }

  /** Save the typed note as/into the bookmark of the current page. */
  private async saveNoteEdit(): Promise<void> {
    const txt: string = this.noteEditText.trim();
    if (txt.length === 0) {
      promptAction.showToast({ message: $r('app.string.incorrect_value') });
      return;
    }
    const ctx = getContext(this);
    try {
      const all: Bookmark[] = await exportBookmarks(ctx);
      let hit = -1;
      for (let i = 0; i < all.length; i++) {
        if (all[i].path === this.documentPath && all[i].page === this.currentPage) {
          hit = i;
          break;
        }
      }
      if (hit >= 0) {
        const b: Bookmark = all[hit];
        /* 0.9.67 (Android showEditDeleteDialog parity): editing keeps the
         * anchor captured at add time — no re-capture here. */
        const updated: Bookmark = {
          path: b.path,
          page: b.page,
          label: txt,
          date: b.date,
          pgs: b.pgs,
          isAiNote: b.isAiNote,
          aiAnswer: b.aiAnswer,
          sec: b.sec,
          sr: b.sr,
          st: b.st
        };
        all[hit] = updated;
        await replaceBookmarks(ctx, all);
        console.info(`[Reader] Note updated on page ${this.currentPage + 1}`);
      } else {
        /* 0.9.67: capture the chapter anchor (Android NoteEditDialog parity) */
        await addBookmark(ctx, this.documentPath, this.currentPage, txt, this.layoutComplete ? this.totalPages : -1, this.captureBmAnchor());
        tlog('bookmark_added', 'page=' + (this.currentPage + 1));
      }
      tlog('note_saved', 'page=' + (this.currentPage + 1));
      promptAction.showToast({ message: $r('app.string.note_saved') });
      this.showNoteEdit = false;
      this.loadBookmarks();
    } catch (e) {
      console.error('[Reader] Note save failed: ' + JSON.stringify(e));
    }
  }

  /** Meta line of the note editor: date · 第 X / N 页 (Android noteEditMeta). */
  private noteMetaLine(): string {
    const d = new Date();
    const p = (n: number): string => (n < 10 ? '0' + n : '' + n);
    const dateStr = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
    const pages = this.layoutComplete ? this.totalPages : 0;
    const pagePart = pages > 0 ? `第 ${this.currentPage + 1} / ${pages} 页` : '';
    return pagePart.length > 0 ? `${dateStr} · ${pagePart}` : dateStr;
  }

  /** Android ExtUtils.sendFileTo parity: hand the book file itself to the
   * system share sheet (0.9.65: systemShare grants the receiver temporary
   * read access to the app-sandbox file; a rejected file record falls back
   * to the old name+path text record). */
  private sendBookFile(): void {
    try {
      if (this.documentPath.length === 0) {
        return;
      }
      let data: systemShare.SharedData;
      try {
        const fileRec: systemShare.SharedRecord = {
          utd: 'general.file',
          uri: fileUri.getUriFromPath(this.documentPath),
          title: this.docTitle
        };
        data = new systemShare.SharedData(fileRec);
      } catch (_e) {
        const rec: systemShare.SharedRecord = {
          utd: 'general.text',
          content: this.docTitle + '\n' + this.documentPath,
          title: this.docTitle
        };
        data = new systemShare.SharedData(rec);
      }
      const controller = new systemShare.ShareController(data);
      controller.show(getContext(this) as common.UIAbilityContext, {}).catch(() => {
      });
      console.info(`[Reader] Send file via share sheet: ${this.docTitle}`);
    } catch (e) {
      console.error('[Reader] Send file failed: ' + JSON.stringify(e));
    }
  }

  /** Android DialogsPlaylist parity: playlist picker for the current book. */
  private openPlPick(): void {
    const metas: PlaylistMeta[] = getAllPlaylists(getContext(this));
    const names: string[] = [];
    for (let i = 0; i < metas.length; i++) {
      names.push(metas[i].name);
    }
    this.plNames = names;
    this.plNewName = '';
    this.showPlPick = true;
  }

  private plAdd(name: string): void {
    const n = name.trim();
    if (n.length === 0) {
      return;
    }
    const ok: boolean = addToPlaylist(getContext(this), n, this.documentPath);
    promptAction.showToast({ message: ok ? $r('app.string.bm_pl_added') : $r('app.string.bm_pl_exists') });
    if (ok) {
      this.showPlPick = false;
    }
  }

  private plCreateAndAdd(): void {
    const name: string = this.plNewName.trim();
    if (name.length === 0) {
      promptAction.showToast({ message: $r('app.string.incorrect_value') });
      return;
    }
    createPlaylist(getContext(this), name);
    this.plAdd(name);
  }

  /** Android ShareDialog.showAiIntro parity: AI book intro (Pro). */
  private startAiIntro(): void {
    if (this.aiIntroBusy) {
      return;
    }
    loadAiConfig(getContext(this)).then((c: AiConfig) => {
      this.aiCfg = c;
      if (!aiConfigReady(this.aiCfg)) {
        promptAction.showToast({ message: $r('app.string.ai_not_configured') });
        return;
      }
      this.aiIntroBusy = true;
      this.aiIntroText = this.L($r('app.string.ai_intro_saving'));
      this.showAiIntro = true;
      this.pageText(0).then((t: string) => {
        const head: string = t.length > 800 ? t.substring(0, 800) : t;
        const msgs: AiMessage[] = [
          { role: 'user', content: '请根据以下书籍信息写一段简介，不需要思考，直接输出回答：\n书名：' + this.docTitle + '\n正文开头：\n' + head }
        ];
        chatCompletion(this.aiCfg, msgs, 1024).then((reply: string) => {
          this.aiIntroText = reply;
          this.aiIntroBusy = false;
        }).catch(() => {
          this.aiIntroText = this.L($r('app.string.common_save_failed'));
          this.aiIntroBusy = false;
        });
      }).catch(() => {
        this.aiIntroText = this.L($r('app.string.common_save_failed'));
        this.aiIntroBusy = false;
      });
    });
  }

  /** 保存到笔记 of the AI intro dialog (AiNote kind=AI简介). */
  private saveAiIntroNote(): void {
    if (this.aiIntroText.length === 0 || this.aiIntroBusy) {
      return;
    }
    const n: AiNote = {
      path: this.documentPath,
      title: this.docTitle,
      page: 0,
      date: new Date().toISOString(),
      kind: 'AI简介',
      text: this.aiIntroText
    };
    addNote(getContext(this), n).then((ok: boolean) => {
      promptAction.showToast({ message: ok ? $r('app.string.trans_saved') : $r('app.string.common_save_failed') });
      if (ok) {
        this.loadAiNotes();
        this.showAiIntro = false;
      }
    });
  }

  private openTransDialog(): void {
    loadAiConfig(getContext(this)).then((c: AiConfig) => {
      this.aiCfg = c;
    });
    this.transToken++;
    this.transResult = '';
    this.transBusy = false;
    /* 0.9.14: remote books get the bilingual checkbox only once the whole
     * book is offline (Android RemoteBilingualBase.isFullyAvailable parity) */
    this.biRemoteReady = false;
    if (this.isRemote && this.remoteUri.length > 0) {
      const low = this.remoteUri.toLowerCase();
      if (low.endsWith('.epub') || low.endsWith('.html') || low.endsWith('.htm')) {
        const ctx2 = getContext(this);
        sha256Hex(this.remoteUri).then((key2: string) => {
          BlockCacheStore.readMeta(ctx2, key2).then((meta) => {
            this.biRemoteReady = meta !== null && meta.fullyCached;
            console.info('[Reader] remote bilingual available: ' + this.biRemoteReady);
          });
        });
      }
    }
    this.showTrans = true;
    console.info('[Reader] AI translate dialog opened');
  }

  /** In-page bilingual: local reflowable books, or a remote book whose
   * full content is already cached offline (0.9.14, Android parity). */
  private biSupported(): boolean {
    if (this.isRemote) {
      const low = this.remoteUri.toLowerCase();
      const okExt = low.endsWith('.epub') || low.endsWith('.html') || low.endsWith('.htm');
      return okExt && this.biRemoteReady;
    }
    return isBilingualDoc(this.documentPath);
  }

  /** Swap the open document in place (keep page, rebuild page list). */
  private async swapDoc(newPath: string): Promise<void> {
    try {
      this.dataSource.openDoc(newPath);
      this.armFirstPaint();
    } catch (e) {
      console.error('[Reader] swapDoc open failed: ' + String(e));
      promptAction.showToast({ message: $r('app.string.trans_bi_open_failed') });
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
            layoutDocument(handle, this.reflowW, this.reflowH, this.fontSize, css);
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
      /* 0.9.65: seekToPage = onPageChanged + vertical List alignment */
      this.seekToPage(this.currentPage, true);
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

  /* ==== 0.9.20: PDF AI translation — four modes (Android 10-01/10-02) ==== */

  private isPdfDoc(): boolean {
    const low: string = (this.isRemote ? this.remoteUri : this.documentPath).toLowerCase();
    return low.endsWith('.pdf');
  }

  private transModeNames(): string[] {
    return [
      this.L($r('app.string.trans_mode_panel')),
      this.L($r('app.string.trans_mode_float')),
      this.L($r('app.string.trans_mode_reflow')),
      this.L($r('app.string.trans_mode_replace'))
    ];
  }

  private startPdfBi(): void {
    tlog('pdfbi_start', 'mode=' + this.pdfBiMode + ' page=' + this.currentPage);
    this.showTrans = false;
    if (this.pdfBiActive) {
      this.stopPdfBi('restart');
    }
    const handle: ESObject | undefined = this.dataSource.getDocHandle();
    if (!this.isRemote && handle === undefined) {
      return;
    }
    this.pdfBiActive = true;
    this.pdfBiTick++;
    this.persistSettings();
    this.pdfSession = new PdfTransSession(this.aiCfg, TRANS_SRC[this.transSrcIdx],
      TRANS_DST[this.transDstIdx], getContext(this), this.documentPath, {
        onPara: (page: number, idx: number, dst: string): void => {
          if (!this.pdfBiActive) {
            return;
          }
          this.pdfBiTick++;
          if (idx < 0 || page === this.currentPage) {
            this.refreshPdfOverlay();
          }
        },
        onFail: (page: number, idx: number, msg: string): void => {
          console.error('[Reader] pdf trans p' + page + '#' + idx + ' failed: ' + msg);
        }
      });
    if (this.pdfBiMode === PDF_TRANS_PANEL) {
      this.showPdfBiPanel = true;
    }
    this.pdfWindow(this.currentPage);
  }

  /** Rolling window: current page first, then +1..+3, then -1 (Android parity). */
  private async pdfWindow(page: number): Promise<void> {
    const hi: number = Math.min(this.totalPages - 1, page + 3);
    const lo: number = Math.max(0, page - 1);
    await this.enqueuePdfPage(page);
    for (let p: number = page + 1; p <= hi; p++) {
      await this.enqueuePdfPage(p);
    }
    if (lo < page) {
      await this.enqueuePdfPage(lo);
    }
  }

  private async enqueuePdfPage(page: number): Promise<void> {
    const s: PdfTransSession | undefined = this.pdfSession;
    if (s === undefined) {
      return;
    }
    let paras: PdfPara[] | undefined = this.pdfParasByPage.get(page);
    if (paras === undefined) {
      let lines: TextLine[] = [];
      try {
        if (this.isRemote) {
          const j = await this.docOp(7, page);
          lines = j !== undefined && j !== null ? JSON.parse(j) as TextLine[] : [];
        } else {
          const h: ESObject | undefined = this.dataSource.getDocHandle();
          if (h === undefined) {
            return;
          }
          lines = JSON.parse(getTextRects(h, page)) as TextLine[];
        }
      } catch (e) {
        lines = [];
      }
      paras = buildPdfParagraphs(page, lines);
      this.pdfParasByPage.set(page, paras);
      tlog('pdfbi_paras', 'page=' + page + ' lines=' + lines.length + ' paras=' + paras.length);
    }
    tlog('pdfbi_enqueue', 'page=' + page);
    s.enqueuePage(page, paras);
  }

  /** Rebuild the overlay list for the current page from finished translations. */
  private refreshPdfOverlay(): void {
    const s: PdfTransSession | undefined = this.pdfSession;
    if (!this.pdfBiActive || s === undefined || this.pdfBiMode === PDF_TRANS_PANEL) {
      this.pdfBiParas = [];
      return;
    }
    const page: number = this.currentPage;
    const paras: PdfPara[] | undefined = this.pdfParasByPage.get(page);
    if (paras === undefined) {
      this.pdfBiParas = [];
      this.enqueuePdfPage(page);
      return;
    }
    s.loadCache(page);
    const out: BiOverlay[] = [];
    for (let i: number = 0; i < paras.length; i++) {
      const p: PdfPara = paras[i];
      const dst: string = s.cached(page, p.idx);
      if (dst.length > 0) {
        out.push({ idx: p.idx, x0: p.rect.x0, y0: p.rect.y0, x1: p.rect.x1, y1: p.rect.y1, src: p.text, dst: dst });
      }
    }
    this.pdfBiParas = out;
    this.pdfBadgeMarks = this.pdfBiMode === PDF_TRANS_FLOAT ? out : [];
    this.pdfBiTick++;
  }

  private stopPdfBi(why: string): void {
    tlog('pdfbi_stop', why);
    if (this.pdfSession !== undefined) {
      this.pdfSession.cancel();
      this.pdfSession = undefined;
    }
    this.pdfBiActive = false;
    this.pdfBiParas = [];
    this.pdfBadgeMarks = [];
    this.pdfBiFlash = [];
    this.showPdfBiPanel = false;
    this.pdfBiCard = undefined;
    this.pdfBiTick++;
  }

  private pdfPanelParas(): PdfPara[] {
    const v: PdfPara[] | undefined = this.pdfParasByPage.get(this.currentPage);
    return v !== undefined ? v : [];
  }

  private pdfPanelDst(p: PdfPara): string {
    const s: PdfTransSession | undefined = this.pdfSession;
    if (s === undefined) {
      return '';
    }
    s.loadCache(this.currentPage);
    return s.cached(this.currentPage, p.idx);
  }

  private flashPdfPara(p: PdfPara): void {
    this.pdfBiFlash = [{ x0: p.rect.x0, y0: p.rect.y0, x1: p.rect.x1, y1: p.rect.y1 }];
    setTimeout(() => {
      this.pdfBiFlash = [];
    }, 1200);
  }

  private showPdfBadgeCard(idx: number): void {
    for (let i: number = 0; i < this.pdfBiParas.length; i++) {
      if (this.pdfBiParas[i].idx === idx) {
        this.pdfBiCard = this.pdfBiParas[i];
        return;
      }
    }
  }

  /** Comparison panel bottom sheet: per-paragraph source/translation of the page. */
  @Builder
  buildPdfBiPanel() {
    Stack({ alignContent: Alignment.Bottom }) {
      Column()
        .width('100%').height('100%')
        .backgroundColor('#66000000')
        .onClick(() => {
          this.showPdfBiPanel = false;
        })
      Column({ space: 8 }) {
        Row({ space: 8 }) {
          Text($r('app.string.trans_result'))
            .fontSize(15).fontWeight(FontWeight.Bold).fontColor('#333')
            .layoutWeight(1)
          Text((this.currentPage + 1) + '/' + this.totalPages)
            .fontSize(12).fontColor('#888')
          Text('\u2715')
            .fontSize(14).fontColor('#888')
            .onClick(() => {
              this.showPdfBiPanel = false;
            })
        }
        .width('100%')

        List({ space: 6 }) {
          ForEach(this.pdfPanelParas(), (p: PdfPara) => {
            ListItem() {
            Column({ space: 4 }) {
              Text(p.text)
                .fontSize(12).fontColor('#888').maxLines(3)
                .width('100%')
              Text(this.pdfPanelDst(p))
                .fontSize(14).fontColor(this.pdfPanelDst(p).length > 0 ? '#222222' : '#bbbbbb')
                .width('100%')
            }
            .width('100%')
            .padding(8)
            .borderRadius(6)
            .backgroundColor('#f5f5f5')
            .onClick(() => {
              this.flashPdfPara(p);
            })
            }
          }, (p: PdfPara) => 'pp_' + p.idx + '_' + this.pdfBiTick)
        }
        .width('100%')
        .layoutWeight(1)
      }
      .width('100%')
      .height('55%')
      .padding(12)
      .backgroundColor(Color.White)
      .borderRadius({ topLeft: 12, topRight: 12 })
    }
    .width('100%').height('100%')
  }

  /** Float-mode badge card (opened by tapping a badge on the page). */
  @Builder
  buildPdfBadgeCard() {
    Stack({ alignContent: Alignment.Center }) {
      Column()
        .width('100%').height('100%')
        .backgroundColor('#33000000')
        .onClick(() => {
          this.pdfBiCard = undefined;
        })
      Column({ space: 10 }) {
        Row() {
          Text($r('app.string.trans_result'))
            .fontSize(15).fontWeight(FontWeight.Bold).fontColor('#333')
            .layoutWeight(1)
          Text('\u2715')
            .fontSize(14).fontColor('#888')
            .onClick(() => {
              this.pdfBiCard = undefined;
            })
        }
        .width('100%')
        Scroll() {
          Column({ space: 8 }) {
            if (this.pdfBiCard !== undefined) {
              Text(this.pdfBiCard.src)
                .fontSize(12).fontColor('#888888')
                .width('100%')
              Text(this.pdfBiCard.dst)
                .fontSize(15).fontColor('#222222')
                .width('100%')
            }
          }
          .width('100%')
        }
        .constraintSize({ maxHeight: 320 })
      }
      .width('82%')
      .padding(14)
      .backgroundColor(Color.White)
      .borderRadius(8)
    }
    .width('100%').height('100%')
  }

  /** Float-mode badges at root level (tap zones would shadow in-page clicks). */
  @Builder
  buildPdfBadgeMarks() {
    Stack({ alignContent: Alignment.TopStart }) {
      ForEach(this.pdfBadgeMarks, (ov: BiOverlay) => {
        Text('\u8BD1')
          .fontSize(10).fontColor(Color.White)
          .width(16).height(16)
          .textAlign(TextAlign.Center)
          .borderRadius(8)
          .backgroundColor('#CC1565C0')
          .position({
            x: this.pdfPageArea[0] + ov.x1 * (this.pdfPageArea[2] - this.pdfPageArea[0]) - 16,
            y: this.pdfPageArea[1] + ov.y0 * (this.pdfPageArea[3] - this.pdfPageArea[1])
          })
          .onClick(() => {
            this.showPdfBadgeCard(ov.idx);
          })
      }, (ov: BiOverlay) => 'bm_' + ov.idx + '_' + ov.dst.length)
    }
    .width('100%').height('100%')
    .hitTestBehavior(HitTestMode.None)
  }

  /** Active-translation chip with exit (top-right; touches pass through). */
  @Builder
  buildPdfBiChip() {
    Stack({ alignContent: Alignment.TopEnd }) {
      Row({ space: 6 }) {
        Text(this.transModeNames()[this.pdfBiMode])
          .fontSize(11).fontColor(Color.White)
        Text('\u2715')
          .fontSize(12).fontColor(Color.White)
      }
      .padding({ left: 10, right: 8, top: 5, bottom: 5 })
      .borderRadius(14)
      .backgroundColor('#99000000')
      .margin({ top: 100, right: 12 })
      .onClick(() => {
        this.stopPdfBi('chip');
      })
    }
    .width('100%').height('100%')
    .hitTestBehavior(HitTestMode.None)
  }

  private startTranslate(): void {
    if (this.transBusy) {
      return;
    }
    if (!aiConfigReady(this.aiCfg)) {
      promptAction.showToast({ message: $r('app.string.ai_not_configured') });
      return;
    }
    const dst: string = TRANS_DST[this.transDstIdx];
    /* 0.9.20: PDF keeps its fixed layout → dedicated four-mode session */
    if (this.isPdfDoc()) {
      this.startPdfBi();
      return;
    }
    /* D: in-page bilingual mode (EPUB/TXT rebuild pipeline) */
    if (this.transInPage && this.biSupported()) {
      if (this.biOriginalPath.length > 0) {
        /* currently reading the bilingual copy → restore the original */
        this.transBusy = true;
        this.transResult = this.L($r('app.string.trans_restoring'));
        const orig = this.biOriginalPath;
        this.swapDoc(orig).then(() => {
          this.biOriginalPath = '';
          this.transBusy = false;
          this.transResult = '';
          this.showTrans = false;
          promptAction.showToast({ message: $r('app.string.trans_restored') });
        });
        return;
      }
      this.transBusy = true;
      this.transResult = this.L($r('app.string.tts_preparing2'));
      const buildFrom = (localPath: string): void => {
        buildBilingual(getContext(this), this.aiCfg, localPath, dst, (msg: string) => {
          this.transResult = msg;
        }).then((outPath: string) => {
          this.transBusy = false;
          if (outPath.length === 0) {
            /* failure message already in transResult; make it visible */
            promptAction.showToast({ message: this.transResult.substring(0, 60) });
            return;
          }
          this.biOriginalPath = localPath;
          this.swapDoc(outPath).then(() => {
            this.showTrans = false;
            promptAction.showToast({ message: $r('app.string.trans_bi_done') });
          }).catch((e: Object) => {
            this.transBusy = false;
            promptAction.showToast({ message: '\u53CC\u8BED\u7248\u5E94\u7528\u5931\u8D25\uFF1A' + String(e).substring(0, 40) });
          });
        });
      };
      if (this.isRemote && this.remoteSession !== undefined) {
        /* 0.9.14 (Android RemoteBilingualBase): assemble the offline base
         * from the block cache, rewrite it, and swap; restoring swaps back
         * to the assembled local original, no network needed */
        const ext = this.remoteUri.toLowerCase().endsWith('.epub') ? 'epub' : 'html';
        this.transResult = '\u6B63\u5728\u7EC4\u88C5\u79BB\u7EBF\u57FA\u5E95\u2026';
        materializeBook(this.remoteSession, ext).then((localPath: string) => {
          buildFrom(localPath);
        }).catch((e: Object) => {
          this.transBusy = false;
          this.transResult = '\u7EC4\u88C5\u5931\u8D25\uFF1A' + String(e).substring(0, 60);
        });
        return;
      }
      buildFrom(this.documentPath);
      return;
    }
    const text = this.selText.length > 0 ? this.selText : this.pageTextForTrans();
    if (text.length === 0) {
      promptAction.showToast({ message: $r('app.string.trans_no_text') });
      return;
    }
    this.transBusy = true;
    this.transResult = '';
    const tok = this.transToken;
    const src: string = TRANS_SRC[this.transSrcIdx];
    const from = src === TRANS_SRC[0] ? '' : '\u4ECE' + src;
    const msgs: AiMessage[] = [
      { role: 'system', content: '\u4F60\u662F\u4E13\u4E1A\u7684\u7FFB\u8BD1\uFF0C\u53EA\u8F93\u51FA\u8BD1\u6587\uFF0C\u4E0D\u8981\u89E3\u91CA\u3002' },
      { role: 'user', content: `\u8BF7\u628A\u4E0B\u9762\u7684\u6587\u672C${from}\u7FFB\u8BD1\u6210${dst}\uFF0C\u53EA\u8F93\u51FA\u8BD1\u6587\uFF1A\n\n` + text }
    ];
    /* streaming (Android 2026-09-24): the translation grows word by word;
     * closing the dialog stops feeding and the stale result is discarded */
    chatCompletionStream(this.aiCfg, msgs, 0, (full: string) => {
      if (tok === this.transToken) {
        this.transResult = full;
      }
    }, undefined, (): boolean => {
      return tok !== this.transToken;
    }).then((out: string) => {
      if (tok !== this.transToken) {
        return;
      }
      this.transResult = lastTruncated ? out + '\n' + this.L($r('app.string.ai_reply_truncated')) : out;
      this.transBusy = false;
      this.showTransPanel = true;
      this.showTrans = false;
    }).catch((e: Object) => {
      if (tok !== this.transToken) {
        return;
      }
      this.transResult = '\u7FFB\u8BD1\u5931\u8D25\uFF1A' + (e instanceof Error ? e.message : String(e));
      this.transBusy = false;
      this.showTransPanel = true;
      this.showTrans = false;
    });
  }

  private saveTransNote(): void {
    if (this.transResult.length === 0 || this.transResult.startsWith(this.L($r('app.string.trans_failed')))) {
      return;
    }
    const n: AiNote = {
      path: this.documentPath,
      title: this.docTitle,
      page: this.currentPage,
      date: new Date().toISOString(),
      kind: this.L($r('app.string.trans_title')),
      text: this.transResult
    };
    addNote(getContext(this), n).then((ok: boolean) => {
      promptAction.showToast({ message: ok ? $r('app.string.trans_saved') : $r('app.string.common_save_failed') });
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
                  this.seekToPage(target);
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
          Text($r('app.string.trans_title'))
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
          Text($r('app.string.trans_src')).fontSize(14).fontColor('#333').width(70)
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
          Text($r('app.string.trans_dst')).fontSize(14).fontColor('#333').width(70)
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

        if (!this.isPdfDoc()) {
          Row({ space: 6 }) {
            Checkbox({ name: 'transBilingual' })
              .width(18).height(18)
              .selectedColor(this.thm())
              .select(this.transInPage && this.biSupported())
              .enabled(this.biSupported())
              .onChange((v: boolean) => {
                this.transInPage = v;
              })
            Text($r('app.string.trans_bilingual'))
              .fontSize(13).fontColor(this.biSupported() ? '#333' : '#999')
            if (this.biSupported()) {
              Text($r('app.string.trans_bi_ok'))
                .fontSize(11).fontColor('#00897B')
                .border({ width: 1, color: '#00897B' })
                .borderRadius(8)
                .padding({ left: 6, right: 6, top: 1, bottom: 1 })
            } else {
              Text($r('app.string.trans_panel_mode'))
                .fontSize(11).fontColor(this.thm())
                .border({ width: 1, color: this.thm() })
                .borderRadius(8)
                .padding({ left: 6, right: 6, top: 1, bottom: 1 })
            }
          }
          .width('100%')
        } else {
          Row({ space: 8 }) {
            Text($r('app.string.trans_mode')).fontSize(14).fontColor('#333').width(70)
            Select(this.toSelectOptions(this.transModeNames()))
              .selected(this.pdfBiMode)
              .value(this.transModeNames()[this.pdfBiMode])
              .font({ size: 14 })
              .width(160)
              .onSelect((idx: number) => {
                this.pdfBiMode = idx;
              })
          }
          .width('100%')
        }

        Row({ space: 6 }) {
          Checkbox({ name: 'transSave' })
            .width(18).height(18)
            .selectedColor(this.thm())
            .select(this.transSave)
            .onChange((v: boolean) => {
              this.transSave = v;
            })
          Text($r('app.string.trans_result_save')).fontSize(13).fontColor('#333')
        }
        .width('100%')

        if (this.transBusy && this.transResult.length > 0) {
          Text(this.transResult)
            .fontSize(12).fontColor(this.thm())
            .width('100%').maxLines(2)
        }

        Row({ space: 12 }) {
          Blank()
          Button($r('app.string.common_cancel'))
            .fontSize(14).height(36)
            .backgroundColor('#e0e0e0').fontColor('#333')
            .onClick(() => {
              this.showTrans = false;
            })
          Button($r('app.string.trans_start'))
            .fontSize(14).height(36)
            .backgroundColor(this.thm()).fontColor(Color.White)
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
          Text($r('app.string.trans_result'))
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
          Button($r('app.string.common_close'))
            .fontSize(14).height(36)
            .backgroundColor('#e0e0e0').fontColor('#333')
            .onClick(() => {
              this.showTransPanel = false;
            })
          Button($r('app.string.trans_save_note'))
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
      promptAction.showToast({ message: $r('app.string.browser_unavailable') });
      console.error('[Reader] openLink failed');
    }
  }

  private openWebSearch(text: string): void {
    if (text.trim().length === 0) {
      promptAction.showToast({ message: $r('app.string.sel_none') });
      return;
    }
    this.openExternal('https://www.google.com/search?q=' + encodeURIComponent(text));
  }

  private openWebDictionary(text: string): void {
    if (text.trim().length === 0) {
      promptAction.showToast({ message: $r('app.string.sel_none') });
      return;
    }
    this.openExternal('https://dict.youdao.com/result?word=' + encodeURIComponent(text) + '&lang=en');
  }

  /** System share sheet for the selected text; clipboard fallback. */
  private shareSelText(): void {
    if (this.selText.trim().length === 0) {
      promptAction.showToast({ message: $r('app.string.sel_none') });
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
          promptAction.showToast({ message: $r('app.string.sel_copied_fallback') });
        });
    } catch (e) {
      this.copySelText();
      promptAction.showToast({ message: $r('app.string.sel_copied_fallback') });
    }
  }

  /* ---- 0.9.59: 4-tab reader preference dialog (偏好) ---- */

  @Builder
  prefLinkRow(label: Resource, getValue: () => string, onTap: () => void) {
    Row() {
      Text(label).fontSize(13).fontColor('#333').layoutWeight(1).maxLines(2)
      Text(getValue()).fontSize(13).fontColor('#1a6aa8').textAlign(TextAlign.End)
        .constraintSize({ maxWidth: '58%' })
        .onClick(() => {
          onTap();
        })
    }
    .width('100%').height(34)
  }

  @Builder
  prefCheckRow(label: Resource, get: () => boolean, onTap: () => void) {
    Row({ space: 8 }) {
      Text(get() ? '☑' : '☐')
        .fontSize(17).fontColor(get() ? '#1a6aa8' : '#999999')
        .onClick(() => {
          onTap();
        })
      Text(label).fontSize(13).fontColor('#333').layoutWeight(1)
        .onClick(() => {
          onTap();
        })
    }
    .width('100%').height(32)
  }

  @Builder
  prefCheckRowSub(label: Resource, get: () => boolean, onTap: () => void) {
    Row({ space: 8 }) {
      Text(get() ? '☑' : '☐')
        .fontSize(17).fontColor(get() ? '#1a6aa8' : '#999999')
        .onClick(() => {
          onTap();
        })
      Text(label).fontSize(13).fontColor('#555').layoutWeight(1)
        .onClick(() => {
          onTap();
        })
    }
    .width('100%').height(32)
    .padding({ left: 24 })
  }

  @Builder
  prefSliderRow(label: Resource, min: number, max: number, step: number, get: () => number, set: (v: number) => void, suffix: string) {
    Row({ space: 6 }) {
      Text(label).fontSize(13).fontColor('#333').layoutWeight(1).maxLines(2)
      Text(`${get()}${suffix}`)
        .fontSize(12).fontColor('#555').width(42).textAlign(TextAlign.Center)
      Slider({ value: get(), min: min, max: max, step: step })
        .layoutWeight(1).height(24)
        .selectedColor('#3949AB').trackColor('#c5cae9').blockColor('#3949AB')
        .onChange((value: number, mode: SliderChangeMode) => {
          if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
            set(Math.max(min, Math.min(max, value)));
          }
        })
      Text('-')
        .fontSize(16).fontColor('#333')
        .width(30).height(28).textAlign(TextAlign.Center)
        .backgroundColor('#e0e0e0').borderRadius(14)
        .onClick(() => {
          set(Math.max(min, get() - step));
        })
      Text('+')
        .fontSize(16).fontColor('#333')
        .width(30).height(28).textAlign(TextAlign.Center)
        .backgroundColor('#e0e0e0').borderRadius(14)
        .onClick(() => {
          set(Math.min(max, get() + step));
        })
    }
    .width('100%').height(36)
  }

  @Builder
  prefColorRow(label: Resource, target: number, get: () => string) {
    Row({ space: 8 }) {
      Text(label).fontSize(13).fontColor('#333').layoutWeight(1)
      Text('')
        .width(36).height(20).borderRadius(3)
        .backgroundColor(get().length > 0 ? get() : '#8a8a8a')
        .onClick(() => {
          this.colorPickTarget = this.colorPickTarget === target ? 0 : target;
        })
    }
    .width('100%').height(34)
    if (this.colorPickTarget === target) {
      Row({ space: 8 }) {
        ForEach(['', '#1a237e', '#0d47a1', '#b71c1c', '#1565c0', '#00695c', '#37474f'], (c: string) => {
          Text(c.length === 0 ? this.L($r('app.string.pf_default_value')) : '')
            .fontSize(10).fontColor('#333')
            .width(32).height(22).textAlign(TextAlign.Center)
            .borderRadius(3)
            .backgroundColor(c.length === 0 ? '#eeeeee' : c)
            .onClick(() => {
              this.applyColorPick(target, c);
            })
        }, (c: string) => `PC${c}`)
      }
      .width('100%')
      .padding({ left: 24, top: 4, bottom: 4 })
    }
  }

  private applyColorPick(target: number, c: string): void {
    if (target === 1) {
      this.linkColorDay = c;
    } else if (target === 2) {
      this.linkColorNight = c;
    } else if (target === 3) {
      this.statusColorDay = c;
    }
    this.colorPickTarget = 0;
    this.applyTypography();
    this.persistSettings();
  }

  /* 0.9.59: reader preference dialog — 4 tabs (可视选项/排版选项/状态栏/控制选项,
   * Android dialog_reading_pref / dialog_adv_preferences / dialog_status_bar_settings
   * / ControlOptionsDialog parity; ⚙ opens it, content reads state inline so
   * every row refreshes live) */
  @Builder
  buildPrefDialog() {
    Stack({ alignContent: Alignment.Center }) {
      Column()
        .width('100%').height('100%')
        .backgroundColor('#66000000')
        .onClick(() => {
          this.showPref = false;
        })

      Column() {
        Row() {
          Text($r('app.string.reader_settings'))
            .fontSize(16).fontWeight(FontWeight.Bold).fontColor(Color.White).layoutWeight(1)
          Image($r('app.media.ic_close'))
            .width(20).height(20).fillColor(Color.White)
            .id('pf_close')
            .onClick(() => {
              this.showPref = false;
            })
        }
        .width('100%').height(44)
        .padding({ left: 14, right: 12 })
        .backgroundColor('#3949AB')

        Row() {
          ForEach(this.prefTabs(), (t: Resource, idx: number) => {
            Text(t)
              .fontSize(13)
              .fontColor(this.prefTab === idx ? '#1a237e' : Color.White)
              .fontWeight(this.prefTab === idx ? FontWeight.Medium : FontWeight.Normal)
              .textAlign(TextAlign.Center)
              .layoutWeight(1).height(38)
              .backgroundColor(this.prefTab === idx ? Color.White : '#00000000')
              .onClick(() => {
                this.prefTab = idx;
              })
          }, (t: Resource, idx: number) => `PT${idx}_${this.prefTab}`)
        }
        .width('100%').height(38)
        .backgroundColor('#3949AB')

        Scroll() {
          Column({ space: 12 }) {
            if (this.prefTab === 0) {
              this.prefLinkRow($r('app.string.pf_orientation'), (): string => { return this.orientName(); }, (): void => { this.cycleOrientation(); })
              /* 字体大小 (dedicated row: CI id hooks on the -/+ buttons) */
              Row({ space: 6 }) {
                Text($r('app.string.set_font_size')).fontSize(13).fontColor('#333').layoutWeight(1)
                Text(`${this.fontSize}pt`)
                  .fontSize(12).fontColor('#555').width(48).textAlign(TextAlign.Center)
                Slider({ value: this.fontSize, min: 10, max: 28, step: 1 })
                  .layoutWeight(1).height(24)
                  .selectedColor('#3949AB').trackColor('#c5cae9').blockColor('#3949AB')
                  .onChange((value: number, mode: SliderChangeMode) => {
                    if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
                      this.fontSize = Math.round(value);
                      this.applyReflow();
                    }
                  })
                Text('-')
                  .fontSize(16).fontColor('#333')
                  .width(30).height(28).textAlign(TextAlign.Center)
                  .backgroundColor('#e0e0e0').borderRadius(14)
                  .id('pf_font_minus')
                  .onClick(() => {
                    this.fontSize = Math.max(10, this.fontSize - 1);
                    this.applyReflow();
                  })
                Text('+')
                  .fontSize(16).fontColor('#333')
                  .width(30).height(28).textAlign(TextAlign.Center)
                  .backgroundColor('#e0e0e0').borderRadius(14)
                  .id('pf_font_plus')
                  .onClick(() => {
                    this.fontSize = Math.min(28, this.fontSize + 1);
                    this.applyReflow();
                  })
              }
              .width('100%').height(36)
              this.prefLinkRow($r('app.string.pf_font_name'), (): string => { return this.fontDisplayName(); }, (): void => { this.showFontChips = !this.showFontChips; })
              if (this.showFontChips) {
                Flex({ wrap: FlexWrap.Wrap, alignItems: ItemAlign.Center }) {
                  ForEach(['default', 'serif', 'sans-serif', 'monospace'], (fam: string, idx: number) => {
                    Text(this.famName(idx))
                      .fontSize(12).height(26)
                      .padding({ left: 10, right: 10 })
                      .borderRadius(13)
                      .margin(3)
                      .backgroundColor(this.fontFamilyIdx === idx && this.userFontPath.length === 0 ? '#3949AB' : '#e0e0e0')
                      .fontColor(this.fontFamilyIdx === idx && this.userFontPath.length === 0 ? Color.White : '#333')
                      .onClick(() => {
                        this.fontFamilyIdx = idx;
                        this.userFontPath = '';
                        this.userFontName = '';
                        this.applyTypography();
                        this.persistSettings();
                      })
                  }, (fam: string) => fam)
                  ForEach(this.userFonts, (f: UserFontRec) => {
                    Text(f.name)
                      .fontSize(12).height(26)
                      .padding({ left: 10, right: 10 })
                      .borderRadius(13)
                      .margin(3)
                      .backgroundColor(this.userFontPath === f.path ? '#3949AB' : '#e0e0e0')
                      .fontColor(this.userFontPath === f.path ? Color.White : '#333')
                      .onClick(() => {
                        this.selectUserFont(f);
                      })
                  }, (f: UserFontRec) => f.path)
                }
                .width('100%')
              }
              Text($r('app.string.pf_text_bg_color')).fontSize(13).fontColor('#333').width('100%')
              Row({ space: 8 }) {
                Button(this.L($r('app.string.pf_day')))
                  .fontSize(12).height(30)
                  .backgroundColor(!this.nightMode ? '#3949AB' : '#e0e0e0')
                  .fontColor(!this.nightMode ? Color.White : '#333')
                  .onClick(() => {
                    this.setTheme(0);
                    this.persistSettings();
                  })
                Button(this.L($r('app.string.pf_night')))
                  .fontSize(12).height(30)
                  .backgroundColor(this.nightMode ? '#3949AB' : '#e0e0e0')
                  .fontColor(this.nightMode ? Color.White : '#333')
                  .onClick(() => {
                    this.setTheme(1);
                    this.persistSettings();
                  })
                ForEach([3, 1, 2], (tidx: number) => {
                  Text('')
                    .width(24).height(24).borderRadius(12)
                    .backgroundColor(tidx === 3 ? '#eaddc3' : (tidx === 2 ? '#000000' : '#101010'))
                    .border({ width: 1, color: '#cccccc' })
                    .onClick(() => {
                      this.setTheme(tidx);
                      this.persistSettings();
                    })
                }, (tidx: number) => `TH${tidx}`)
              }
              .width('100%')
              /* 亮度: 系统亮度 + slider + -/+ */
              Row({ space: 6 }) {
                Text(this.useSystemBrightness ? '☑' : '☐')
                  .fontSize(17).fontColor(this.useSystemBrightness ? '#1a6aa8' : '#999999')
                  .onClick(() => {
                    this.useSystemBrightness = !this.useSystemBrightness;
                    if (!this.useSystemBrightness) {
                      this.setBrightness(this.brightnessLevel);
                    }
                    this.persistSettings();
                  })
                Text($r('app.string.pf_system_brightness')).fontSize(13).fontColor('#333')
                Slider({ value: this.brightnessLevel, min: this.allowZeroBright ? 0 : 1, max: 255, step: 1 })
                  .layoutWeight(1).height(24)
                  .selectedColor('#3949AB').trackColor('#c5cae9').blockColor('#3949AB')
                  .enabled(!this.useSystemBrightness)
                  .onChange((value: number, mode: SliderChangeMode) => {
                    if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
                      this.brightnessLevel = Math.round(value);
                      this.setBrightness(this.brightnessLevel);
                      this.persistSettings();
                    }
                  })
                Text(`${this.brightnessLevel}`)
                  .fontSize(12).fontColor('#555').width(30).textAlign(TextAlign.Center)
                Text('-')
                  .fontSize(16).fontColor('#333')
                  .width(30).height(28).textAlign(TextAlign.Center)
                  .backgroundColor('#e0e0e0').borderRadius(14)
                  .onClick(() => {
                    this.setBrightness(Math.max(this.allowZeroBright ? 0 : 1, this.brightnessLevel - 16));
                  })
                Text('+')
                  .fontSize(16).fontColor('#333')
                  .width(30).height(28).textAlign(TextAlign.Center)
                  .backgroundColor('#e0e0e0').borderRadius(14)
                  .onClick(() => {
                    this.setBrightness(Math.min(255, this.brightnessLevel + 16));
                  })
              }
              .width('100%').height(36)
              this.prefCheckRow($r('app.string.pf_dict_lookup'), (): boolean => { return this.dictLookup; }, (): void => {
                this.dictLookup = !this.dictLookup;
                this.persistSettings();
              })
              this.prefCheckRow($r('app.string.pf_bluelight_f'), (): boolean => { return this.blueLightFilter > 0; }, (): void => {
                this.blueLightFilter = this.blueLightFilter > 0 ? 0 : 30;
                this.persistSettings();
              })
              Divider().color('#e0e0e0').width('100%').strokeWidth(1)
              this.prefLinkRow($r('app.string.set_fonts_folder'), (): string => { return this.fontsFolderDisp.length > 0 ? this.fontsFolderDisp : this.L($r('app.string.pf_default_value')); }, (): void => {
                promptAction.showToast({ message: this.L($r('app.string.pf_fonts_hint')) });
              })
              this.prefLinkRow($r('app.string.pf_text_align'), (): string => { return this.alignName(); }, (): void => {
                this.textAlign = (this.textAlign + 1) % 4;
                this.applyReflow();
              })
              this.prefLinkRow($r('app.string.pf_style'), (): string => { return this.styleName(); }, (): void => {
                this.styleMode = (this.styleMode + 1) % 3;
                this.persistSettings();
              })
              this.prefLinkRow($r('app.string.pf_style_user'), (): string => { return this.userCssName.length > 0 ? this.userCssName : this.L($r('app.string.pf_none')); }, (): void => {
                this.userCssName = this.userCssName.length > 0 ? '' : 'custom.css';
                this.persistSettings();
              })
              this.prefCheckRow($r('app.string.pf_hyphen'), (): boolean => { return this.hyphenationEnabled; }, (): void => {
                this.hyphenationEnabled = !this.hyphenationEnabled;
                this.applyReflow();
              })
              this.prefLinkRow($r('app.string.pf_hyphen_lang'), (): string => { return this.hyphenLangName(); }, (): void => {
                this.hyphenLangDefault = !this.hyphenLangDefault;
                this.persistSettings();
              })
              this.prefCheckRow($r('app.string.pf_inline_notes'), (): boolean => { return this.inlineNotes; }, (): void => {
                this.inlineNotes = !this.inlineNotes;
                this.persistSettings();
              })
              this.prefCheckRow($r('app.string.pf_image_zoom'), (): boolean => { return this.imageZoomEnabled; }, (): void => {
                this.imageZoomEnabled = !this.imageZoomEnabled;
                this.persistSettings();
              })
              this.prefSliderRow($r('app.string.pf_image_scale'), 1, 4, 0.1, (): number => { return this.imageZoomScale; }, (v: number): void => {
                this.imageZoomScale = Math.round(v * 10) / 10;
                this.persistSettings();
              }, 'x')
              this.prefSliderRow($r('app.string.set_line_height'), 1.0, 2.5, 0.1, (): number => { return this.lineHeight; }, (v: number): void => {
                this.lineHeight = Math.round(v * 10) / 10;
                this.applyReflow();
              }, '')
              this.prefSliderRow($r('app.string.pf_letter_spacing'), 0, 10, 1, (): number => { return this.letterSpacing; }, (v: number): void => {
                this.letterSpacing = Math.round(v);
                this.applyReflow();
              }, 'px')
              this.prefSliderRow($r('app.string.pf_para_spacing'), 0, 40, 1, (): number => { return this.paragraphSpacing; }, (v: number): void => {
                this.paragraphSpacing = Math.round(v);
                this.applyReflow();
              }, 'px')
              this.prefSliderRow($r('app.string.pf_indent'), 0, 8, 1, (): number => { return this.textIndent; }, (v: number): void => {
                this.textIndent = Math.round(v);
                this.applyReflow();
              }, 'em')
              Text($r('app.string.pf_page_margin')).fontSize(13).fontWeight(FontWeight.Medium).fontColor('#333').width('100%')
              this.prefSliderRow($r('app.string.pf_side_top'), 0, 40, 1, (): number => { return this.marginT; }, (v: number): void => {
                this.marginT = Math.round(v);
                this.applyReflow();
              }, 'pt')
              this.prefSliderRow($r('app.string.pf_side_bottom'), 0, 40, 1, (): number => { return this.marginB; }, (v: number): void => {
                this.marginB = Math.round(v);
                this.applyReflow();
              }, 'pt')
              this.prefSliderRow($r('app.string.pf_side_left'), 0, 40, 1, (): number => { return this.marginLeft; }, (v: number): void => {
                this.marginLeft = Math.round(v);
                this.applyReflow();
              }, 'pt')
              this.prefSliderRow($r('app.string.pf_side_right'), 0, 40, 1, (): number => { return this.marginRight; }, (v: number): void => {
                this.marginRight = Math.round(v);
                this.applyReflow();
              }, 'pt')
              this.prefColorRow($r('app.string.pf_link_day'), 1, (): string => { return this.linkColorDay; })
              this.prefColorRow($r('app.string.pf_link_night'), 2, (): string => { return this.linkColorNight; })
              Text($r('app.string.common_reset'))
                .fontSize(14).fontColor('#1a6aa8')
                .onClick(() => {
                  this.resetTab0();
                })
            }
            if (this.prefTab === 1) {
              this.prefLinkRow($r('app.string.pf_tap_zone'), (): string => { return this.tapEdgeName(); }, (): void => { this.cycleTapEdge(); })
              this.prefLinkRow($r('app.string.pf_rest_remind'), (): string => { return this.restName(); }, (): void => { this.cycleRestReminder(); })
              this.prefLinkRow($r('app.string.pf_double_center'), (): string => { return this.doubleName(); }, (): void => {
                this.doubleClickPref = this.doubleClickPref === 1 ? 0 : 1;
                this.persistSettings();
              })
              this.prefSliderRow($r('app.string.pf_scroll_speed'), 2, 10, 1, (): number => { return this.autoScrollSpeed; }, (v: number): void => {
                this.autoScrollSpeed = Math.round(v);
                this.persistSettings();
              }, '')
              this.prefSliderRow($r('app.string.pf_auto_off'), 0, 30, 1, (): number => { return this.autoOffMinutes; }, (v: number): void => {
                this.autoOffMinutes = Math.round(v);
                this.persistSettings();
              }, '')
              this.prefSliderRow($r('app.string.pf_antialias'), 0, 8, 1, (): number => { return this.antialiasLevel; }, (v: number): void => {
                this.antialiasLevel = Math.round(v);
                this.persistSettings();
              }, '')
              this.prefCheckRow($r('app.string.pf_dropcap'), (): boolean => { return this.dropCapEnabled; }, (): void => {
                this.dropCapEnabled = !this.dropCapEnabled;
                this.applyReflow();
              })
              this.prefCheckRow($r('app.string.pf_mirror'), (): boolean => { return this.mirrorMode; }, (): void => {
                this.mirrorMode = !this.mirrorMode;
                this.persistSettings();
              })
              this.prefCheckRow($r('app.string.pf_allow_select'), (): boolean => { return this.allowTextSelection; }, (): void => {
                this.allowTextSelection = !this.allowTextSelection;
                this.persistSettings();
              })
              this.prefCheckRow($r('app.string.pf_perchar'), (): boolean => { return this.perCharSelect; }, (): void => {
                this.perCharSelect = !this.perCharSelect;
                this.persistSettings();
              })
              this.prefCheckRow($r('app.string.pf_edge_bright'), (): boolean => { return this.edgeBrightness; }, (): void => {
                this.edgeBrightness = !this.edgeBrightness;
                this.persistSettings();
              })
              this.prefCheckRow($r('app.string.pf_zero_bright'), (): boolean => { return this.allowZeroBright; }, (): void => {
                this.allowZeroBright = !this.allowZeroBright;
                this.persistSettings();
              })
              this.prefCheckRow($r('app.string.pf_day_replace'), (): boolean => { return this.dayImageReplace; }, (): void => {
                this.dayImageReplace = !this.dayImageReplace;
                this.persistSettings();
              })
              this.prefCheckRow($r('app.string.pf_amoled'), (): boolean => { return this.amoledEnhance; }, (): void => {
                this.amoledEnhance = !this.amoledEnhance;
                this.persistSettings();
              })
              this.prefCheckRow($r('app.string.pf_loop'), (): boolean => { return this.loopPaging; }, (): void => {
                this.loopPaging = !this.loopPaging;
                this.persistSettings();
              })
              this.prefCheckRow($r('app.string.pf_vol_zoom'), (): boolean => { return this.volumeKeyAction === 2; }, (): void => {
                this.volumeKeyAction = this.volumeKeyAction === 2 ? 1 : 2;
                this.persistSettings();
              })
              this.prefCheckRow($r('app.string.pf_zoom_locked'), (): boolean => { return this.zoomLockedPages; }, (): void => {
                this.zoomLockedPages = !this.zoomLockedPages;
                this.persistSettings();
              })
              this.prefCheckRow($r('app.string.pf_back_confirm'), (): boolean => { return this.backConfirmClose; }, (): void => {
                this.backConfirmClose = !this.backConfirmClose;
                this.persistSettings();
              })
              this.prefCheckRow($r('app.string.pf_vibrate'), (): boolean => { return this.selectionVibrate; }, (): void => {
                this.selectionVibrate = !this.selectionVibrate;
                this.persistSettings();
              })
              this.prefSliderRow($r('app.string.pf_decode_thr'), 1, 16, 1, (): number => { return this.decodeThreads; }, (v: number): void => {
                this.decodeThreads = Math.round(v);
                this.persistSettings();
              }, '')
              this.prefSliderRow($r('app.string.pf_draw_thr'), 1, 16, 1, (): number => { return this.drawThreads; }, (v: number): void => {
                this.drawThreads = Math.round(v);
                this.persistSettings();
              }, '')
              Text($r('app.string.common_reset'))
                .fontSize(14).fontColor('#1a6aa8')
                .onClick(() => {
                  this.resetTab1();
                })
            }
            if (this.prefTab === 2) {
              this.prefColorRow($r('app.string.pf_status_color'), 3, (): string => { return this.statusColorDay; })
              this.prefCheckRow($r('app.string.pf_show_status'), (): boolean => { return this.showStatusBar; }, (): void => {
                this.showStatusBar = !this.showStatusBar;
                this.persistSettings();
              })
              this.prefCheckRow($r('app.string.pf_show_title'), (): boolean => { return this.showTitleTop; }, (): void => {
                this.showTitleTop = !this.showTitleTop;
                this.persistSettings();
              })
              this.prefCheckRow($r('app.string.pf_show_progress'), (): boolean => { return this.showProgressBar; }, (): void => {
                this.showProgressBar = !this.showProgressBar;
                this.persistSettings();
              })
              this.prefCheckRow($r('app.string.pf_chapter_ticks'), (): boolean => { return this.showChapterTicks; }, (): void => {
                this.showChapterTicks = !this.showChapterTicks;
                this.persistSettings();
              })
              this.prefCheckRow($r('app.string.pf_sub_ticks'), (): boolean => { return this.showSubChapterTicks; }, (): void => {
                this.showSubChapterTicks = !this.showSubChapterTicks;
                this.persistSettings();
              })
              this.prefCheckRow($r('app.string.pf_show_time'), (): boolean => { return this.showClockTop; }, (): void => {
                this.showClockTop = !this.showClockTop;
                this.persistSettings();
              })
              this.prefCheckRow($r('app.string.pf_show_battery'), (): boolean => { return this.showBatteryTop; }, (): void => {
                this.showBatteryTop = !this.showBatteryTop;
                this.persistSettings();
              })
              this.prefCheckRow($r('app.string.pf_seek_nav'), (): boolean => { return this.seekNav; }, (): void => {
                this.seekNav = !this.seekNav;
                this.persistSettings();
              })
              Text($r('app.string.pf_show_bmbar')).fontSize(13).fontColor('#333').width('100%')
              this.prefCheckRowSub($r('app.string.pf_musician'), (): boolean => { return this.bmStripMusician; }, (): void => {
                this.bmStripMusician = !this.bmStripMusician;
                this.persistSettings();
              })
              this.prefCheckRowSub($r('app.string.pf_vert_flip'), (): boolean => { return this.bmStripVert; }, (): void => {
                this.bmStripVert = !this.bmStripVert;
                this.persistSettings();
              })
              this.prefCheckRowSub($r('app.string.pf_horz_flip'), (): boolean => { return this.bmStripHorz; }, (): void => {
                this.bmStripHorz = !this.bmStripHorz;
                this.persistSettings();
              })
              this.prefLinkRow($r('app.string.pf_scale'), (): string => { return this.progressScaleName(); }, (): void => {
                this.progressScale = this.progressScale === 1 ? 0 : 1;
                this.persistSettings();
              })
              this.prefLinkRow($r('app.string.pf_chapter_fmt'), (): string => { return this.chapterFmtName(); }, (): void => {
                this.chapterFmtIdx = this.chapterFmtIdx === 1 ? 0 : 1;
                this.persistSettings();
              })
              this.prefSliderRow($r('app.string.set_font_size'), 8, 20, 1, (): number => { return this.statusBarFontPx; }, (v: number): void => {
                this.statusBarFontPx = Math.round(v);
                this.persistSettings();
              }, '')
              this.prefSliderRow($r('app.string.pf_progress_h'), 2, 10, 1, (): number => { return this.progressBarH; }, (v: number): void => {
                this.progressBarH = Math.round(v);
                this.persistSettings();
              }, '')
            }
            if (this.prefTab === 3) {
              this.prefLinkRow($r('app.string.pf_left_side'), (): string => { return this.actionName(this.tapLeftAction); }, (): void => {
                this.tapLeftAction = (this.tapLeftAction + 1) % 8;
                this.persistSettings();
              })
              this.prefLinkRow($r('app.string.pf_right_side'), (): string => { return this.actionName(this.tapRightAction); }, (): void => {
                this.tapRightAction = (this.tapRightAction + 1) % 8;
                this.persistSettings();
              })
              this.prefLinkRow($r('app.string.pf_top_side'), (): string => { return this.actionName(this.tapTopAction); }, (): void => {
                this.tapTopAction = (this.tapTopAction + 1) % 8;
                this.persistSettings();
              })
              this.prefLinkRow($r('app.string.pf_bottom_side'), (): string => { return this.actionName(this.tapBottomAction); }, (): void => {
                this.tapBottomAction = (this.tapBottomAction + 1) % 8;
                this.persistSettings();
              })
              this.prefLinkRow($r('app.string.pf_double_center'), (): string => { return this.doubleName(); }, (): void => {
                this.doubleClickPref = this.doubleClickPref === 1 ? 0 : 1;
                this.persistSettings();
              })
              this.prefLinkRow($r('app.string.pf_vol_keys'), (): string => { return this.volKeyName(); }, (): void => {
                this.volumeKeyAction = this.volumeKeyAction === 0 ? 1 : 0;
                this.persistSettings();
              })
              this.prefLinkRow($r('app.string.pf_invert_keys'), (): string => { return this.invertVolume ? this.L($r('app.string.pf_enabled')) : this.L($r('app.string.pf_disabled')); }, (): void => {
                this.invertVolume = !this.invertVolume;
                this.persistSettings();
              })
              Row() {
                Blank().layoutWeight(1)
                Text($r('app.string.common_close'))
                  .fontSize(14).fontColor('#1a6aa8')
                  .onClick(() => {
                    this.showPref = false;
                  })
              }
              .width('100%')
            }
          }
          .width('100%')
          .padding({ left: 12, right: 12, top: 10, bottom: 16 })
        }
        .width('100%')
        .constraintSize({ maxHeight: '52%' })
        .align(Alignment.Top)
        .scrollBar(BarState.Auto)
      }
      .width('88%')
      .borderRadius(10)
      .backgroundColor(Color.White)
      .clip(true)
      .shadow({ radius: 16, color: '#40000000', offsetX: 0, offsetY: 4 })
    }
  }

  /* ===== 0.9.62 builders: bookmark dialog (Android dialogShowBookmarks parity) ===== */

  @Builder
  buildBmListDialog() {
    Stack({ alignContent: Alignment.Center }) {
      Column()
        .width('100%').height('100%')
        .backgroundColor('#66000000')
        .onClick(() => {
          this.showBmList = false;
        })

      Column() {
        Row({ space: 10 }) {
          Text($r('app.string.reader_bookmark'))
            .fontSize(16).fontWeight(FontWeight.Bold).fontColor(Color.White)
            .layoutWeight(1)
          Text('⋮')
            .fontSize(18).fontColor(Color.White)
            .padding(4)
            .id('reader_bm_menu')
            .onClick(() => {
              this.showBmSort = true;
            })
          Text('✕')
            .fontSize(16).fontColor(Color.White)
            .padding(4)
            .id('reader_bm_close')
            .onClick(() => {
              this.showBmList = false;
            })
        }
        .width('100%')
        .padding({ left: 14, right: 14, top: 10, bottom: 10 })
        .backgroundColor(this.barBg())

        Row({ space: 8 }) {
          TextInput({ placeholder: $r('app.string.bm_search_placeholder'), text: this.bmSearch })
            .layoutWeight(1)
            .height(40)
            .fontSize(14)
            .backgroundColor(Color.White)
            .onChange((value: string) => {
              this.bmSearch = value;
            })
          if (this.bmSearch.length > 0) {
            Text('✕')
              .fontSize(14).fontColor('#999')
              .padding(6)
              .id('reader_bm_search_clear')
              .onClick(() => {
                this.bmSearch = '';
              })
          }
        }
        .width('100%')
        .padding({ left: 14, right: 14, top: 12 })

        Row({ space: 10 }) {
          Button($r('app.string.common_add'))
            .fontSize(14).height(38).layoutWeight(1)
            .backgroundColor('#e0e0e0').fontColor('#333')
            .id('reader_bm_add')
            .onClick(() => {
              this.bmAddName = '';
              this.showBmAdd = true;
            })
          Button($r('app.string.fast_bookmark'))
            .fontSize(14).height(38).layoutWeight(1)
            .backgroundColor('#e0e0e0').fontColor('#333')
            .id('reader_bm_quick')
            .onClick(() => {
              this.quickAddBookmark();
            })
        }
        .width('100%')
        .padding({ left: 14, right: 14, top: 10 })

        if (this.bmRows().length === 0) {
          Text($r('app.string.bookmarks_empty'))
            .fontSize(13).fontColor(Color.Gray)
            .width('100%')
            .textAlign(TextAlign.Center)
            .padding({ top: 24, bottom: 24 })
        } else {
          List() {
            ForEach(this.bmRows(), (bm: Bookmark) => {
              ListItem() {
                Row() {
                  Text(`${this.bmLandingPage(bm) + 1}: ${bm.label.length > 0 ? bm.label : this.L($r('app.string.reader_bookmark'))}`)
                    .fontSize(14).fontColor('#333')
                    .maxLines(3)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                    .layoutWeight(1)
                    .onClick(() => {
                      this.showBmList = false;
                      this.jumpToBookmark(bm);
                    })
                  Text('✕')
                    .fontSize(16).fontColor('#e57373')
                    .padding(6)
                    .id('reader_bm_del')
                    .onClick(() => {
                      this.deleteBm(bm);
                    })
                }
                .width('100%')
                .padding({ top: 8, bottom: 8 })
              }
            }, (bm: Bookmark) => `${bm.page}_${bm.date}`)
          }
          .width('100%')
          .height(280)
          .padding({ left: 14, right: 14, bottom: 10 })
        }
      }
      .width('86%')
      .backgroundColor(Color.White)
      .borderRadius(6)
      .clip(true)
      .shadow({ radius: 12, color: '#40000000', offsetX: 0, offsetY: 3 })
    }
    .width('100%').height('100%')
  }

  @Builder
  buildBmAddDialog() {
    Stack({ alignContent: Alignment.Center }) {
      Column()
        .width('100%').height('100%')
        .backgroundColor('#66000000')
        .onClick(() => {
          this.showBmAdd = false;
        })

      Column() {
        Text($r('app.string.reader_bookmark'))
          .fontSize(16).fontWeight(FontWeight.Bold).fontColor(Color.White)
          .width('100%')
          .padding({ left: 14, right: 14, top: 12, bottom: 12 })
          .backgroundColor(this.barBg())
        Text(this.bmPageMeta())
          .fontSize(12).fontColor('#666')
          .width('100%')
          .padding({ left: 14, right: 14, top: 10, bottom: 4 })
        TextInput({ placeholder: $r('app.string.bm_name_hint'), text: this.bmAddName })
          .fontSize(14)
          .width('100%')
          .height(44)
          .margin({ left: 14, right: 14 })
          .backgroundColor('#fafafa')
          .onChange((v: string) => {
            this.bmAddName = v;
          })
        Row({ space: 16 }) {
          Button($r('app.string.common_cancel'))
            .fontSize(14).height(40).width(110)
            .backgroundColor(Color.White).fontColor('#26a69a')
            .border({ width: 1, color: '#26a69a' })
            .onClick(() => {
              this.showBmAdd = false;
            })
          Button($r('app.string.common_add'))
            .fontSize(14).height(40).width(140)
            .backgroundColor(Color.White).fontColor('#26a69a')
            .border({ width: 1, color: '#26a69a' })
            .onClick(() => {
              this.addNamedBookmark();
            })
        }
        .width('100%')
        .justifyContent(FlexAlign.Center)
        .padding({ top: 12, bottom: 14 })
      }
      .width('86%')
      .backgroundColor(Color.White)
      .borderRadius(6)
      .clip(true)
    }
    .width('100%').height('100%')
  }

  @Builder
  buildBmSortMenu() {
    Stack({ alignContent: Alignment.Center }) {
      Column()
        .width('100%').height('100%')
        .backgroundColor('#33000000')
        .onClick(() => {
          this.showBmSort = false;
        })

      Column() {
        this.bmRow($r('app.string.bm_share_bms'), (): void => {
          this.showBmSort = false;
          this.bmShare();
        })
        this.bmRow($r('app.string.bm_sort_pasc'), (): void => {
          this.bmSortOrder = 2;
          this.showBmSort = false;
        })
        this.bmRow($r('app.string.bm_sort_pdesc'), (): void => {
          this.bmSortOrder = 3;
          this.showBmSort = false;
        })
        this.bmRow($r('app.string.bm_sort_new'), (): void => {
          this.bmSortOrder = 0;
          this.showBmSort = false;
        })
        this.bmRow($r('app.string.bm_sort_old'), (): void => {
          this.bmSortOrder = 1;
          this.showBmSort = false;
        })
      }
      .width('62%')
      .padding({ top: 8, bottom: 8 })
      .backgroundColor(Color.White)
      .borderRadius(8)
      .clip(true)
    }
    .width('100%').height('100%')
  }

  /* ===== 0.9.61 builders: search popup / file info ===== */

  @Builder
  buildSearchDialog() {
    Stack({ alignContent: Alignment.TopStart }) {
      /* click-catcher: tap anywhere outside the card closes (no dim) */
      Column()
        .width('100%').height('100%')
        .onClick(() => {
          this.closeSearch();
        })

      Column() {
        Row({ space: 8 }) {
          Text($r('app.string.common_search'))
            .fontSize(16).fontWeight(FontWeight.Bold).fontColor(Color.White)
            .layoutWeight(1)
          Text('✕')
            .fontSize(16).fontColor(Color.White)
            .padding(4)
            .onClick(() => {
              this.closeSearch();
            })
        }
        .width('100%')
        .padding({ left: 14, right: 14, top: 10, bottom: 10 })
        .backgroundColor(this.barBg())

        Row({ space: 8 }) {
          TextInput({ placeholder: $r('app.string.search_placeholder'), text: this.searchQuery })
            .layoutWeight(1)
            .height(40)
            .fontSize(14)
            .backgroundColor(Color.White)
            .onChange((value: string) => {
              this.searchQuery = value;
            })
          Text('✕')
            .fontSize(14).fontColor('#999')
            .padding(6)
            .onClick(() => {
              this.searchQuery = '';
            })
        }
        .width('100%')
        .padding({ left: 14, right: 14, top: 12 })

        Row({ space: 10 }) {
          Button($r('app.string.common_search'))
            .fontSize(14).height(38)
            .backgroundColor('#2a7').fontColor(Color.White)
            .onClick(() => {
              this.doSearch();
            })
          if (this.searchTotalHits > 0) {
            Text(`${this.searchCurrentIdx + 1}/${this.searchTotalHits}`)
              .fontSize(13).fontColor('#555')
            Button('◀')
              .fontSize(13).height(34).width(40)
              .backgroundColor('#e0e0e0').fontColor('#333')
              .onClick(() => this.prevHitPage())
            Button('▶')
              .fontSize(13).height(34).width(40)
              .backgroundColor('#e0e0e0').fontColor('#333')
              .onClick(() => this.nextHitPage())
          }
        }
        .width('100%')
        .padding({ left: 14, right: 14, top: 10, bottom: 14 })
        .justifyContent(FlexAlign.Start)
      }
      .width('86%')
      .margin({ top: 95 })
      .backgroundColor(Color.White)
      .borderRadius(6)
      .clip(true)
      .shadow({ radius: 12, color: '#40000000', offsetX: 0, offsetY: 3 })
    }
    .width('100%').height('100%')
  }

  @Builder
  buildFileInfoDialog() {
    Stack({ alignContent: Alignment.Center }) {
      Column()
        .width('100%').height('100%')
        .backgroundColor('#66000000')
        .onClick(() => {
          this.showFileInfo = false;
        })

      Column() {
        Scroll() {
          Column() {
            if (this.fiCoverPath.length > 0) {
              Image(this.fiCoverPath)
                .width('44%')
                .height(300)
                .objectFit(ImageFit.Cover)
                .margin({ top: 14 })
                .border({ width: 1, color: '#eeeeee' })
            } else if (this.fiCover !== undefined) {
              Image(this.fiCover)
                .width('44%')
                .height(300)
                .objectFit(ImageFit.Cover)
                .margin({ top: 14 })
                .border({ width: 1, color: '#eeeeee' })
            }
            ForEach(this.fiRows, (r: FiRow) => {
              Column() {
                Text(r.label)
                  .fontSize(13).fontColor('#555')
                  .width('100%')
                  .padding({ left: 14, right: 14, top: 8, bottom: 8 })
                  .backgroundColor('#f0f0f0')
                Text(r.value.length > 0 ? r.value : '—')
                  .fontSize(14).fontColor('#222')
                  .width('100%')
                  .padding({ left: 14, right: 14, top: 6, bottom: 6 })
              }
              .width('100%')
            }, (r: FiRow) => r.label)
            Row() {
              Text(this.L($r('app.string.fi_label_tags')))
                .fontSize(13).fontColor('#555')
                .layoutWeight(1)
              Text(this.L($r('app.string.fi_create_tag')))
                .fontSize(13).fontColor('#2a7')
                .padding(6)
                .onClick(() => {
                  promptAction.showToast({ message: $r('app.string.fi_tag_unsupported') });
                })
            }
            .width('100%')
            .padding({ left: 14, right: 14, top: 6, bottom: 6 })
          }
          .width('100%')
        }
        .width('100%')
        .height(430)

        Row({ space: 14 }) {
          Button($r('app.string.common_close'))
            .fontSize(14).height(40).layoutWeight(1)
            .backgroundColor('#f0f0f0').fontColor('#555')
            .onClick(() => {
              this.showFileInfo = false;
            })
          Button($r('app.string.fi_read'))
            .fontSize(14).height(40).layoutWeight(1)
            .backgroundColor('#2a7').fontColor(Color.White)
            .onClick(() => {
              this.showFileInfo = false;
            })
        }
        .width('100%')
        .padding({ left: 14, right: 14, top: 10, bottom: 14 })
      }
      .width('82%')
      .backgroundColor(Color.White)
      .borderRadius(8)
      .clip(true)
    }
    .width('100%').height('100%')
  }

  /* ===== 0.9.60 builders: book menu / note editor / playlist picker / AI intro ===== */

  @Builder
  bmRow(label: Resource, onTap: () => void) {
    Row() {
      Text(label)
        .fontSize(15).fontColor('#333')
        .layoutWeight(1)
    }
    .width('100%')
    .height(44)
    .padding({ left: 16, right: 16 })
    .onClick(() => {
      onTap();
    })
  }

  @Builder
  buildBookMenuDialog() {
    Stack({ alignContent: Alignment.Center }) {
      Column()
        .width('100%').height('100%')
        .backgroundColor('#66000000')
        .onClick(() => {
          this.showBookMenu = false;
        })

      Column() {
        this.bmRow(this.scrollMode === 0 ? $r('app.string.bm_mode_book') : $r('app.string.bm_mode_scroll'), (): void => {
          this.showBookMenu = false;
          this.toggleScrollMode();
        })
        this.bmRow($r('app.string.bm_musician'), (): void => {
          this.showBookMenu = false;
          /* 0.9.66: toggle — musician mode previously had no UI exit (the
           * only other caller of togglePageMode lives in the unreachable
           * legacy panel) and pageMode persists, stranding the user in the
           * layout. Enter and exit through the same row; clamp in both
           * directions like togglePageMode does. */
          this.pageMode = this.pageMode === 2 ? 0 : 2;
          this.currentPage = Math.min(this.currentPage, this.totalPages - 1);
          console.info(`[Reader] Page mode: ${this.pageMode === 2 ? 'musician' : 'single'} (book menu)`);
        })
        this.bmRow($r('app.string.bm_rsvp'), (): void => {
          this.showBookMenu = false;
          this.openRsvpPanel();
        })
        this.bmRow($r('app.string.bm_open_with'), (): void => {
          this.showBookMenu = false;
          promptAction.showToast({ message: $r('app.string.bm_open_with_unsupported') });
        })
        this.bmRow($r('app.string.bm_send'), (): void => {
          this.showBookMenu = false;
          this.sendBookFile();
        })
        this.bmRow($r('app.string.bm_playlist'), (): void => {
          this.showBookMenu = false;
          this.openPlPick();
        })
        this.bmRow($r('app.string.bm_ai_intro'), (): void => {
          this.showBookMenu = false;
          this.startAiIntro();
        })
        this.bmRow($r('app.string.bm_file_info'), (): void => {
          this.showBookMenu = false;
          this.openFileInfo();
          this.showFileInfo = true;
        })
      }
      .width('78%')
      .padding({ top: 8, bottom: 8 })
      .backgroundColor(Color.White)
      .borderRadius(8)
      .clip(true)
    }
    .width('100%').height('100%')
  }

  @Builder
  buildNoteEditDialog() {
    Stack({ alignContent: Alignment.Center }) {
      Column()
        .width('100%').height('100%')
        .backgroundColor('#66000000')
        .onClick(() => {
          this.showNoteEdit = false;
        })

      Column() {
        Text($r('app.string.reading_note'))
          .fontSize(16).fontWeight(FontWeight.Bold).fontColor(Color.White)
          .width('100%')
          .padding({ left: 14, right: 14, top: 12, bottom: 12 })
          .backgroundColor(this.barBg())
        Text(this.noteMetaLine())
          .fontSize(12).fontColor('#666')
          .width('100%')
          .padding({ left: 14, right: 14, top: 10, bottom: 4 })
        TextArea({ text: this.noteEditText, placeholder: $r('app.string.note_edit_hint') })
          .fontSize(14)
          .width('100%')
          .height(260)
          .backgroundColor('#fafafa')
          .onChange((v: string) => {
            this.noteEditText = v;
          })
        Row({ space: 16 }) {
          Button($r('app.string.common_cancel'))
            .fontSize(14).height(40).width(110)
            .backgroundColor(Color.White).fontColor('#26a69a')
            .border({ width: 1, color: '#26a69a' })
            .onClick(() => {
              this.showNoteEdit = false;
            })
          Button($r('app.string.note_edit_save'))
            .fontSize(14).height(40).width(140)
            .backgroundColor(Color.White).fontColor('#26a69a')
            .border({ width: 1, color: '#26a69a' })
            .onClick(() => {
              this.saveNoteEdit();
            })
        }
        .width('100%')
        .justifyContent(FlexAlign.Center)
        .padding({ top: 12, bottom: 14 })
      }
      .width('86%')
      .backgroundColor(Color.White)
      .borderRadius(6)
      .clip(true)
    }
    .width('100%').height('100%')
  }

  @Builder
  buildPlPickDialog() {
    Stack({ alignContent: Alignment.Center }) {
      Column()
        .width('100%').height('100%')
        .backgroundColor('#66000000')
        .onClick(() => {
          this.showPlPick = false;
        })

      Column({ space: 10 }) {
        Text($r('app.string.bm_pl_title'))
          .fontSize(16).fontWeight(FontWeight.Bold).fontColor('#333')
        if (this.plNames.length === 0) {
          Text($r('app.string.bm_pl_empty'))
            .fontSize(13).fontColor('#999')
            .width('100%')
        } else {
          Column() {
            ForEach(this.plNames, (n: string) => {
              Row() {
                Text(n)
                  .fontSize(14).fontColor('#333')
                  .layoutWeight(1)
              }
              .width('100%')
              .height(40)
              .onClick(() => {
                this.plAdd(n);
              })
            }, (n: string) => n)
          }
          .width('100%')
          .constraintSize({ maxHeight: 220 })
        }
        Row({ space: 8 }) {
          TextInput({ placeholder: $r('app.string.bm_pl_new_hint'), text: this.plNewName })
            .layoutWeight(1)
            .height(38)
            .fontSize(14)
            .onChange((v: string) => {
              this.plNewName = v;
            })
          Button($r('app.string.bm_pl_create'))
            .fontSize(13).height(38)
            .backgroundColor('#5c6bc0').fontColor(Color.White)
            .onClick(() => {
              this.plCreateAndAdd();
            })
        }
        .width('100%')
      }
      .width('78%')
      .padding(16)
      .backgroundColor(Color.White)
      .borderRadius(8)
    }
    .width('100%').height('100%')
  }

  @Builder
  buildAiIntroDialog() {
    Stack({ alignContent: Alignment.Center }) {
      Column()
        .width('100%').height('100%')
        .backgroundColor('#66000000')
        .onClick(() => {
          if (!this.aiIntroBusy) {
            this.showAiIntro = false;
          }
        })

      Column({ space: 10 }) {
        Text($r('app.string.ai_intro_title'))
          .fontSize(16).fontWeight(FontWeight.Bold).fontColor('#333')
        Scroll() {
          Text(this.aiIntroText)
            .fontSize(14).fontColor('#444')
            .width('100%')
        }
        .width('100%')
        .height(300)
        .backgroundColor('#fafafa')
        .borderRadius(6)
        Row({ space: 12 }) {
          Button($r('app.string.ai_intro_save_note'))
            .fontSize(14).height(40)
            .backgroundColor('#26a69a').fontColor(Color.White)
            .onClick(() => {
              this.saveAiIntroNote();
            })
          Button($r('app.string.common_close'))
            .fontSize(14).height(40)
            .backgroundColor('#f0f0f0').fontColor('#555')
            .onClick(() => {
              this.showAiIntro = false;
            })
        }
        .width('100%')
        .justifyContent(FlexAlign.Center)
      }
      .width('82%')
      .padding(16)
      .backgroundColor(Color.White)
      .borderRadius(8)
    }
    .width('100%').height('100%')
  }

  build() {
    Stack({ alignContent: Alignment.TopStart }) {
      /* 0.9.60 (Android activity_vertical_view parity): the reading area is
       * full-screen and the toolbars below are overlay siblings (盖遮) —
       * show/hide never re-layouts the content (no blank half when hidden). */
      Column() {
        // Main reading area
        if (this.scrollMode === 0) {
          /* Horizontal Swiper mode */
          Stack() {
            Swiper(this.swiperCtrl) {
              /* P2-17 (0.9.36): LazyForEach over the page data source - page
               * components exist only for the visible+cached window (the
               * 4696-page book measured ~294MB RSS with the eager ForEach)
               * and alive cells follow updates through @Prop. */
              LazyForEach(this.dataSource, (pageIdx: number) => {
                if (this.pageMode === 1) {
                  /* double-page: each swiper page shows a pair */
                  DoublePageRenderer({ pageIndex: pageIdx, dataSource: this.dataSource, nightInvert: this.nightMode })
                } else if (this.pageSplit) {
                  /* Round 5: page split — one wide page shown as two halves side by side */
                  Row() {
                    PageRenderer({
                      pageIndex: pageIdx,
                      dataSource: this.dataSource,
                      onRendered: (p: number, ok: boolean): void => { this.releaseFirstPaint('render'); },
                      cropRect: SPLIT_LEFT,
                      contrastLevel: this.contrastLevel,
                      nightInvert: this.nightMode
                    }).layoutWeight(1)
                    PageRenderer({
                      pageIndex: pageIdx,
                      dataSource: this.dataSource,
                      onRendered: (p: number, ok: boolean): void => { this.releaseFirstPaint('render'); },
                      cropRect: SPLIT_RIGHT,
                      contrastLevel: this.contrastLevel,
                      nightInvert: this.nightMode
                    }).layoutWeight(1)
                  }
                  .width('100%').height('100%')
                } else {
                  PageRenderer({
                    pageIndex: pageIdx,
                    dataSource: this.dataSource,
                    currentPage: this.currentPage,
                    searchHitPage: this.searchHitPage,
                    onRendered: (p: number, ok: boolean): void => { this.releaseFirstPaint('render'); },
                    searchHits: this.searchHits,
                    biOverlays: this.pdfBiParas,
                    biMode: this.pdfBiMode,
                    biFlash: this.pdfBiFlash,
                    onBiBadge: (bidx: number): void => {
                      this.showPdfBadgeCard(bidx);
                    },
                    onPageArea: (x: number, y: number, w: number, h: number): void => {
                      this.pdfPageArea = [x, y, x + w, y + h];
                    },
                    annotations: this.annotations,
                    selPreview: this.selPreview,
                    contrastLevel: this.contrastLevel,
                    nightInvert: this.nightMode
                  })
                }
              }, (item: number) => `${item}_${this.zoomLevel}_${this.nightMode}_${this.pageMode}_${this.reflowVersion}_${this.pageSplit}_${this.contrastLevel}_${this.renderEpoch}`)
            }
            .index(this.rtlEnabled ? (this.totalPages - 1 - this.currentPage) : this.currentPage)
            .width('100%').height('100%')
            .loop(false).indicator(false).autoPlay(false)
            .disableSwipe(this.inputLocked)
            .cachedCount(1)
            .onChange((index: number) => {
              /* 0.9.65: un-mirror the physical index — .index() above binds
               * the RTL mirrored position, so the raw callback must be
               * mapped back before it becomes currentPage, or the first
               * swipe desyncs the binding */
              this.onPageChanged(this.rtlEnabled ? this.totalPages - 1 - index : index);
            })

            /* Pinch gesture for zoom */
            Column()
              .width('100%').height('100%')
              .hitTestBehavior(this.zonesDisabled() ? HitTestMode.None : HitTestMode.Default)
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
              .width(`${this.edgeRatio() * 100}%`).height('100%')
              /* 0.9.62: pin to the left edge — Stack default alignment
               * centered this row (dump-proven), so edge taps fell into the
               * pinch layer's no-op tap and never fired. */
              .position({ x: 0, y: 0 })
              .backgroundColor(Color.Transparent)
              .hitTestBehavior(this.zonesDisabled() ? HitTestMode.None : HitTestMode.Default)
              .onClick(() => {
                this.onTapZoneAction(this.tapLeftAction);
              })

            // Right tap zone (configurable action, sprint I4)
            Row()
              .width(`${this.edgeRatio() * 100}%`).height('100%')
              /* 0.9.62: pin to the right edge — .align(Alignment.End) is
               * content alignment, not position-in-parent (dump-proven the row
               * sat centered); explicit percent position works. */
              .position({ x: `${(1 - this.edgeRatio()) * 100}%`, y: 0 })
              .backgroundColor(Color.Transparent)
              .hitTestBehavior(this.zonesDisabled() ? HitTestMode.None : HitTestMode.Default)
              .onClick(() => {
                this.onTapZoneAction(this.tapRightAction);
              })

            // Center tap zone (toggle menu) + long-press for text selection (sprint H2)
            Row()
              .width(`${(1 - 2 * this.edgeRatio()) * 100}%`).height('100%')
              /* 0.9.62: pinned explicitly (centered by default anyway, but do
               * not rely on Stack alignment — see the left/right zone fix). */
              .position({ x: `${this.edgeRatio() * 100}%`, y: 0 })
              .backgroundColor(Color.Transparent)
              .hitTestBehavior(this.zonesDisabled() ? HitTestMode.None : HitTestMode.Default)
              .gesture(
                GestureGroup(GestureMode.Exclusive,
                  TapGesture({ count: 2 })
                    .onAction(() => {
                      /* 0.9.55: 阅读时双击 pref — zoom-to-fit or crop toggle */
                      if (this.doubleClickPref === 1) {
                        this.cropEnabled = !this.cropEnabled;
                        this.reflowVersion++;
                        console.info(`[Reader] Double-tap crop: ${this.cropEnabled}`);
                      } else {
                        this.doubleTapFit();
                      }
                    }),
                  LongPressGesture({ repeat: false, duration: 600 })
                  .onAction((event: LongPressGestureEvent) => {
                    /* Round 7: prefs longTapAction: 0=selection, 1=file info, 2=menu, 3=none */
                    if (this.longTapActionPref === 1) {
                      this.openFileInfo();
                      this.showFileInfo = true;
                      return;
                    }
                    if (this.longTapActionPref === 2) {
                      this.showMenu = !this.showMenu;
                      return;
                    }
                    if (this.longTapActionPref === 3) {
                      return;
                    }
                    this.loadTextRects();
                    this.parseSelLines();
                    this.selMode = true;
                    if (event !== undefined && event.fingerList.length > 0) {
                      this.setSelAnchor(event.fingerList[0].globalX, event.fingerList[0].globalY, true);
                    }
                    console.info(`[Reader] Text selection activated on page ${this.currentPage}`);
                  }),
                  TapGesture({ count: 1 })
                    .onAction(() => {
                      this.onTapZoneAction(this.tapCenterAction);
                    })
                )
              )

            /* v1.3.4 parity: drag the left screen edge to adjust brightness */
            Stack({ alignContent: Alignment.Center }) {
              if (this.brightHintShow) {
                Text(this.brightHintText)
                  .fontSize(13).fontColor('#333')
                  .width(140).textAlign(TextAlign.Center).offset({ x: 70 })
                  .padding({ left: 12, right: 12, top: 4, bottom: 4 })
                  .borderRadius(14)
                  .backgroundColor('#f5f5f5')
              }
            }
            .width(32).height('100%')
            .position({ x: 0, y: 0 })
            .hitTestBehavior(HitTestMode.Transparent)
            .visibility(this.edgeBrightness ? Visibility.Visible : Visibility.None)
            .gesture(
              PanGesture({ fingers: 1, distance: 5, direction: PanDirection.Vertical })
                .onActionStart(() => {
                  this.brightGestureStart();
                })
                .onActionUpdate((event: PanGestureEvent) => {
                  this.brightGestureMove(event.offsetY);
                })
                .onActionEnd(() => {
                  this.brightGestureEnd();
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
          .width('100%').height('100%')
          .onAreaChange((oldArea: Area, newArea: Area) => {
            this.pageAreaW = typeof newArea.width === 'number' ? newArea.width : 0;
            this.pageAreaH = typeof newArea.height === 'number' ? newArea.height : 0;
            const gp = newArea.globalPosition;
            this.pageGlobalX = typeof gp.x === 'number' ? gp.x : 0;
            this.pageGlobalY = typeof gp.y === 'number' ? gp.y : 0;
          })
        } else {
          /* Vertical continuous scroll mode + Round 6 fast-scroll rail */
          Stack({ alignContent: Alignment.End }) {
            List({ scroller: this.vScroller }) {
              /* P2-17 (0.9.36): LazyForEach (see the Swiper note). */
              LazyForEach(this.dataSource, (pageIdx: number) => {
                ListItem() {
                  PageRenderer({
                    pageIndex: pageIdx,
                    dataSource: this.dataSource,
                    currentPage: this.currentPage,
                    onRendered: (p: number, ok: boolean): void => { this.releaseFirstPaint('render'); },
                    contrastLevel: this.contrastLevel,
                    nightInvert: this.nightMode,
                    biOverlays: this.pdfBiParas,
                    biMode: this.pdfBiMode,
                    biFlash: this.pdfBiFlash,
                    onBiBadge: (bidx: number): void => {
                      this.showPdfBadgeCard(bidx);
                    },
                    onPageArea: (x: number, y: number, w: number, h: number): void => {
                      this.pdfPageArea = [x, y, x + w, y + h];
                    }
                  })
                }
                .width('100%')
                .height('100vh')
              }, (item: number) => `v_${item}_${this.zoomLevel}_${this.nightMode}_${this.reflowVersion}_${this.contrastLevel}_${this.renderEpoch}`)
            }
            .width('100%').height('100%')
            .cachedCount(1)
            .scrollBar(BarState.Auto)
            .edgeEffect(EdgeEffect.Spring)
            .enableScrollInteraction(!this.inputLocked)
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

            /* Round 6: fast-scroll rail (drag to seek pages, Android FastScroller parity) */
            Stack({ alignContent: Alignment.Center }) {
              if (this.fastScrolling) {
                Text(`${this.currentPage + 1}/${this.totalPages}`)
                  .fontSize(13).fontColor('#333')
                  .padding({ left: 10, right: 10, top: 4, bottom: 4 })
                  .borderRadius(14)
                  .backgroundColor('#f5f5f5')
              }
            }
            .width(36).height('100%')
            .gesture(
              PanGesture({ fingers: 1, distance: 5 })
                .onActionStart(() => {
                  this.fastStartPage = this.currentPage;
                  this.fastScrolling = true;
                })
                .onActionUpdate((event: PanGestureEvent) => {
                  this.fastPanUpdate(event.offsetY);
                })
                .onActionEnd(() => {
                  this.fastScrolling = false;
                })
            )

            /* 0.9.62: center tap zone (vertical mode) — without it the bars
             * could never be hidden in vertical scroll mode. Single tap runs
             * tapCenterAction (default 2 = bars hide/show). Transparent hit
             * mode keeps the List scroll (and the fast-scroll rail under it)
             * alive through the zone. */
            Row()
              .width('100%').height(`${(1 - 2 * this.edgeRatio()) * 100}%`)
              .position({ x: 0, y: `${this.edgeRatio() * 100}%` })
              .backgroundColor(Color.Transparent)
              .hitTestBehavior(this.zonesDisabled() ? HitTestMode.None : HitTestMode.Transparent)
              .onClick(() => {
                this.onTapZoneAction(this.tapCenterAction);
              })

            /* 0.9.59: top/bottom tap zones (控制选项 顶部/底部); the bottom
             * strip rides the Stack's End alignment */
            Row()
              .width('100%').height(`${this.edgeRatio() * 100}%`)
              .position({ x: 0, y: 0 })
              .backgroundColor(Color.Transparent)
              .hitTestBehavior(this.zonesDisabled() ? HitTestMode.None : HitTestMode.Default)
              .onClick(() => {
                this.onTapZoneAction(this.tapTopAction);
              })
            Row()
              .width('100%').height(`${this.edgeRatio() * 100}%`)
              /* 0.9.62: pin to the real bottom — neither the Stack-level
               * alignContent:End nor child .align(End) moved this row (dump
               * showed it centered since 0.9.59, eating the middle taps);
               * explicit percent position is the dump-proven mechanism here. */
              .position({ x: 0, y: `${(1 - this.edgeRatio()) * 100}%` })
              .backgroundColor(Color.Transparent)
              .hitTestBehavior(this.zonesDisabled() ? HitTestMode.None : HitTestMode.Default)
              .onClick(() => {
                this.onTapZoneAction(this.tapBottomAction);
              })

            /* v1.3.4 parity: left-edge brightness strip (vertical mode) */
            Stack({ alignContent: Alignment.Center }) {
              if (this.brightHintShow) {
                Text(this.brightHintText)
                  .fontSize(13).fontColor('#333')
                  .width(140).textAlign(TextAlign.Center).offset({ x: 70 })
                  .padding({ left: 12, right: 12, top: 4, bottom: 4 })
                  .borderRadius(14)
                  .backgroundColor('#f5f5f5')
              }
            }
            .width(32).height('100%')
            .position({ x: 0, y: 0 })
            .hitTestBehavior(HitTestMode.Transparent)
            .visibility(this.edgeBrightness ? Visibility.Visible : Visibility.None)
            .gesture(
              PanGesture({ fingers: 1, distance: 5, direction: PanDirection.Vertical })
                .onActionStart(() => {
                  this.brightGestureStart();
                })
                .onActionUpdate((event: PanGestureEvent) => {
                  this.brightGestureMove(event.offsetY);
                })
                .onActionEnd(() => {
                  this.brightGestureEnd();
                })
            )
          }
          .width('100%').height('100%')
          .onAreaChange((oldArea: Area, newArea: Area) => {
            this.pageAreaW = typeof newArea.width === 'number' ? newArea.width : 0;
            this.pageAreaH = typeof newArea.height === 'number' ? newArea.height : 0;
          })
        }
      }
      .width('100%').height('100%')
      .backgroundColor(this.chromeBg())

      /* Top chrome overlay (Android titleBar + menuLayout parity): floats over
       * the content; HitTestMode.None keeps the wrapper click-through so the
       * rows themselves still consume their own taps. */
      Column() {
        /* 0.9.59 (Android document_title + document_title_buttons parity):
         * the top chrome is two rows — status info row + right-aligned icon
         * band; both bars show/hide with a single center tap (barsVisible). */
        if (this.barsVisible && this.showStatusBar) {
          Row() {
            Column({ space: 1 }) {
              Text(this.docTitle.length > 16 ? this.docTitle.substring(0, 16) + '…' : (this.docTitle.length > 0 ? this.docTitle : ''))
                .fontSize(this.statusBarFontPx).fontColor(Color.White).maxLines(1).fontWeight(FontWeight.Medium)
              Text(this.totalPages > 0 ? `${this.currentPage + 1}/${this.totalPages}` : '...')
                .fontSize(Math.max(9, this.statusBarFontPx - 4)).fontColor('#c5cae9')
            }
            .alignItems(HorizontalAlign.Start)
            .layoutWeight(1)

            if (this.showClockTop || this.showBatteryTop) {
              Row({ space: 4 }) {
                if (this.showClockTop) {
                  Image($r('app.media.ic_clock'))
                    .width(14).height(14).fillColor('#c5cae9')
                  Text(this.topClock)
                    .fontSize(Math.max(9, this.statusBarFontPx - 3)).fontColor('#c5cae9')
                }
                if (this.showBatteryTop) {
                  Image($r('app.media.ic_battery'))
                    .width(14).height(14).fillColor('#c5cae9').margin({ left: 8 })
                  Text(this.topBattery)
                    .fontSize(Math.max(9, this.statusBarFontPx - 3)).fontColor('#c5cae9')
                }
              }
              .margin({ right: 12 })
            }
          }
          .width('100%').height(40)
          .padding({ left: 12, right: 12 })
          .backgroundColor(this.statusColorDay.length > 0 && !this.nightMode ? this.statusColorDay : this.barBg())
        }
        /* icon band — 6 keys, right-aligned (Android menuLayout:
         * 对齐文档/日夜切换/全屏/AI 对照翻译/阅读偏好/关书) */
        if (this.barsVisible) {
          Row({ space: 20 }) {
            Image($r('app.media.ic_rdr_align'))
              .width(22).height(22).fillColor(Color.White)
              .id('reader_btn_align')
              .onClick(() => {
                this.doubleTapFit();
                console.info('[Reader] Align document (top band)');
              })
            Image(this.nightMode ? $r('app.media.ic_rdr_moon') : $r('app.media.ic_rdr_sun'))
              .width(22).height(22).fillColor(Color.White)
              .id('reader_btn_night')
              .onClick(() => {
                this.toggleNightMode();
              })
            Image($r('app.media.ic_rdr_full'))
              .width(22).height(22).fillColor(Color.White)
              .id('reader_btn_full')
              .onClick(() => {
                this.toggleFullscreen();
              })
            Image($r('app.media.ic_rdr_ai'))
              .width(22).height(22)
              .id('reader_btn_ai')
              .onClick(() => {
                this.openTransDialog();
              })
            Image($r('app.media.ic_rdr_pref'))
              .width(22).height(22).fillColor(Color.White)
              .id('reader_btn_pref')
              .onClick(() => {
                this.showPref = true;
                console.info('[Reader] Pref dialog opened (top band)');
              })
            Image($r('app.media.ic_rdr_close'))
              .width(22).height(22).fillColor(Color.White)
              .id('reader_btn_close')
              .onClick(() => {
                router.back();
                console.info('[Reader] Close reader');
              })
          }
          .width('100%').height(44)
          .padding({ left: 12, right: 14 })
          .justifyContent(FlexAlign.End)
          .backgroundColor(this.barBg())
        }
        /* thin reading-progress line + chapter ticks (Android ProgressDraw);
         * 0.9.59: gated by 显示进度条/章节进度条, height = 进度条高度 */
        if (this.barsVisible && this.showProgressBar) {
          Stack({ alignContent: Alignment.Start }) {
            Progress({ value: this.totalPages > 0 ? this.currentPage + 1 : 0, total: this.totalPages > 0 ? this.totalPages : 1, type: ProgressType.Linear })
              .width('100%').height(this.progressBarH)
              .style({ strokeWidth: Math.max(2, Math.round(this.progressBarH / 2)) })
              .color(Color.White)
              .backgroundColor('#5c6bc0')
            if (this.totalPages > 0 && this.showChapterTicks && this.tocEntries.length > 1 && this.tocEntries.length <= 120) {
              ForEach(this.tocEntries, (t: TocEntry) => {
                Row()
                  .width(1).height(this.progressBarH)
                  .backgroundColor('#59FFFFFF')
                  .position({ x: `${Math.min(100, Math.round(t.page * 100 / this.totalPages))}%`, y: 0 })
              }, (t: TocEntry) => `tick_${t.page}_${t.title}`)
            }
          }
          .width('100%').height(this.progressBarH)
        }
      }
      .width('100%')
      .hitTestBehavior(HitTestMode.None)

      /* Bottom chrome overlay (Android bottomBar/document_footer parity): floats
       * over the content, packed to the bottom edge. */
      Column() {
        /* 0.9.25 P0-3: whole-book cache progress banner (Android remote
         * progress banner) - visible while the P3 filler runs */
        if (this.isRemote && this.remoteFillPercent >= 0 && this.remoteFillPercent < 100) {
          Row() {
            Text(`后台缓存 ${this.remoteFillPercent}%`)
              .fontSize(11).fontColor('#b0bec5')
          }
          .width('100%').height(20).justifyContent(FlexAlign.Center)
        }
        /* 0.9.23 (Android 10-10 parity): the page seek row moved ABOVE the
         * icon row (document_footer.xml order). */
        if (this.barsVisible) {
        Row({ space: 10 }) {
          Text(this.totalPages > 0 ? (this.progressScale === 1 ? `${Math.round((this.currentPage + 1) * 100 / this.totalPages)}%` : '' + (this.currentPage + 1)) : '0')
            .fontSize(12).fontColor(Color.White).width(36).textAlign(TextAlign.Center)
            .id('reader_seek_cur')
            .onClick(() => {
              this.jumpInput = String(this.currentPage + 1);
              this.showJump = true;
              console.info('[Reader] Jump-to-page dialog opened (seek row)');
            })
          if (this.seekNav) {
          Slider({ value: this.currentPage, min: 0, max: Math.max(this.totalPages - 1, 1), step: 1 })
            .layoutWeight(1).height(24)
            .selectedColor(Color.White)
            .trackColor('#5c6bc0')
            .blockColor(Color.White)
            .onChange((value: number, mode: SliderChangeMode) => {
              if (!this.openSettled) {
                /* The Slider clamps its initial value on first build
                 * (totalPages is still 0) and fires onChange — do not let
                 * it stomp the restored reading position (0.9.22 fix5). */
                return;
              }
              if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
                const target: number = Math.max(0, Math.min(Math.round(value), this.totalPages - 1));
                if (target !== this.currentPage) {
                  this.pushPageHistory();
                  this.seekToPage(target);
                  console.info(`[Reader] Seek to page ${target + 1}`);
                }
              }
            })
          } else {
            Blank().layoutWeight(1)
          }
          Text(this.totalPages > 0 ? '' + this.totalPages : '0')
            .fontSize(12).fontColor(Color.White).width(36).textAlign(TextAlign.Center)
        }
        .width('100%')
        .padding({ left: 8, right: 8, top: 2, bottom: 6 })
        .backgroundColor(this.barBg())
        }
        /* 0.9.59 (Android document_footer parity): right-aligned 6-key row —
         * 搜索/选择/书签开关/目录/触摸锁定/书籍菜单; the extra keys moved to
         * the book menu (TTS/缩略图/历史/滚动模式) */
        if (this.barsVisible) {
          Row({ space: 22 }) {
            Image($r('app.media.ic_rdr_search'))
              .width(22).height(22).fillColor(Color.White)
              .id('reader_btn_search')
              .onClick(() => this.openSearch())

            Image($r('app.media.ic_rdr_note'))
              .width(22).height(22).fillColor(Color.White)
              .id('reader_btn_edit')
              .onClick(() => {
                /* 0.9.60 (Android editTop2 parity): the pencil is the note editor */
                this.openNoteEdit();
              })

            Image($r('app.media.ic_rdr_bookmark'))
              .width(22).height(22).fillColor(this.bmOnPage ? '#ffd54f' : Color.White)
              .id('reader_btn_bm_toggle')
              .onClick(() => {
                /* 0.9.62 (Android dialogShowBookmarks parity): the bookmark
                 * key opens the bookmark dialog — add / quick add / list */
                this.loadBookmarks().then(() => {
                  this.bmSearch = '';
                  this.showBmList = true;
                });
              })

            Image($r('app.media.ic_rdr_toc'))
              .width(22).height(22).fillColor(Color.White)
              .id('reader_btn_doc_toc')
              .onClick(() => {
                this.showToc = true;
                console.info('[Reader] TOC opened (bottom bar)');
              })

            Image(this.inputLocked ? $r('app.media.ic_rdr_lock') : $r('app.media.ic_rdr_lock_open'))
              .width(22).height(22).fillColor(this.inputLocked ? '#ffd54f' : Color.White)
              .id('reader_btn_lock')
              .onClick(() => {
                /* 0.9.55: real touch-lock toggle (Android lock button parity) */
                this.inputLocked = !this.inputLocked;
                promptAction.showToast({ message: this.inputLocked ? $r('app.string.reader_locked') : $r('app.string.reader_unlocked') });
                console.info(`[Reader] Touch lock toggled: ${this.inputLocked}`);
              })

            Image($r('app.media.ic_rdr_more'))
              .width(22).height(22).fillColor(Color.White)
              .id('reader_btn_more')
              .onClick(() => {
                /* 0.9.60 (Android bookMenu parity): the dots open the ShareDialog-style menu */
                this.showBookMenu = true;
              })
          }
          .width('100%').height(44)
          .padding({ left: 16, right: 16 })
          .justifyContent(FlexAlign.End)
          .backgroundColor(this.barBg())
        }
      }
      .width('100%').height('100%')
      .justifyContent(FlexAlign.End)
      .hitTestBehavior(HitTestMode.None)

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
              Text($r('app.string.sel_text'))
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
            .backgroundColor(this.thm())

            /* selected text + TTS entries */
            Row({ space: 10 }) {
              Text(this.selText.length > 0
                ? (this.selText.length > 18 ? this.selText.substring(0, 18) + '…' : this.selText)
                : $r('app.string.sel_pick'))
                .fontSize(15).fontColor('#333')
                .maxLines(1)
                .layoutWeight(1)
              Image($r('app.media.ic_sparkle'))
                .width(22).height(22).fillColor('#00897B')
                .onClick(() => {
                  promptAction.showToast({ message: $r('app.string.sel_ai_todo') });
                })
              Image($r('app.media.ic_book_open'))
                .width(22).height(22).fillColor('#00897B')
                .onClick(() => this.openTtsPanel())
            }
            .width('100%')
            .padding({ left: 14, right: 14, top: 10, bottom: 10 })

            /* markup row */
            Row({ space: 6 }) {
              Button($r('app.string.sel_underline'))
                .fontSize(11).height(30).layoutWeight(1).padding({ left: 0, right: 0 })
                .backgroundColor('#1e88e5').fontColor(Color.White)
                .onClick(() => this.applySelMarkup('underline'))
              Button($r('app.string.common_delete'))
                .fontSize(11).height(30).layoutWeight(1).padding({ left: 0, right: 0 })
                .backgroundColor('#e53935').fontColor(Color.White)
                .onClick(() => this.applySelMarkup('strikeout'))
              Button($r('app.string.sel_squiggly'))
                .fontSize(11).height(30).layoutWeight(1).padding({ left: 0, right: 0 })
                .backgroundColor('#8e24aa').fontColor(Color.White)
                .onClick(() => this.applySelMarkup('squiggly'))
              Button($r('app.string.ann_highlight'))
                .fontSize(11).height(30).layoutWeight(1).padding({ left: 0, right: 0 })
                .backgroundColor('#fbc02d').fontColor(Color.White)
                .onClick(() => this.applySelMarkup('highlight'))
            }
            .width('100%')
            .padding({ left: 14, right: 14, bottom: 6 })

            if (this.showSelNoteInput) {
              Row({ space: 8 }) {
                TextInput({ text: this.selNoteInput, placeholder: $r('app.string.sel_note_hint') })
                  .layoutWeight(1).height(36).fontSize(13)
                  .onChange((value: string) => {
                    this.selNoteInput = value;
                  })
                Button($r('app.string.common_add'))
                  .fontSize(12).height(32)
                  .backgroundColor('#00897B').fontColor(Color.White)
                  .onClick(() => this.applySelNote())
              }
              .width('100%')
              .padding({ left: 14, right: 14, bottom: 6 })
            }

            Divider().color('#eeeeee')

            /* menu rows (Android hr12 list) */
            this.selMenuRow($r('app.media.ic_bookmark'), this.L($r('app.string.sel_add_bookmark')), 1)
            this.selMenuRow($r('app.media.ic_share'), this.L($r('app.string.reader_share')), 5)
            this.selMenuRow($r('app.media.ic_copy'), this.L($r('app.string.sel_copy')), 2)
            this.selMenuRow($r('app.media.ic_search'), this.L($r('app.string.sel_inbook_search')), 3)
            this.selMenuRow($r('app.media.ic_sparkle'), this.L($r('app.string.sel_send_ai')), 6)
            this.selMenuRow($r('app.media.ic_edit'), this.L($r('app.string.sel_note')), 4)
            Divider().color('#eeeeee')
            this.selMenuRow($r('app.media.ic_globe'), this.L($r('app.string.sel_web_search')), 7)
            this.selMenuRow($r('app.media.ic_book_open'), this.L($r('app.string.sel_web_dict')), 8)
          }
          .width('82%')
          .backgroundColor(Color.White)
          .borderRadius(4)
          .clip(true)
        }
        .width('100%').height('100%')
      }

      /* Menu overlay (modal: full-screen dim + centered panel).
       * 0.9.63: the book-menu 更多设置 entry was removed, so this legacy
       * full settings panel has no regular entry any more — only the
       * restore-only longTapActionPref===2 long-press can open it. Code
       * kept for zero loss; entry migration is a pending decision. */
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
              Text($r('app.string.reader_settings'))
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
            Text($r('app.string.reader_mode'))
              .fontSize(13).fontColor('#555')
            Button(this.scrollMode === 0 ? $r('app.string.mode_horizontal') : $r('app.string.mode_vertical'))
              .fontSize(12).height(32)
              .backgroundColor('#e0e0e0').fontColor('#333')
              .onClick(() => this.toggleScrollMode())
          }

          // Page mode toggle (single/double/musician)
          Row({ space: 8 }) {
            Text($r('app.string.reader_page'))
              .fontSize(13).fontColor('#555')
            Button(this.pageMode === 0 ? $r('app.string.page_single') : (this.pageMode === 1 ? $r('app.string.page_double') : $r('app.string.page_music')))
              .fontSize(12).height(32)
              .backgroundColor('#e0e0e0').fontColor('#333')
              .onClick(() => this.togglePageMode())
          }

          // Night mode toggle
          Row({ space: 8 }) {
            Text($r('app.string.reader_night'))
              .fontSize(13).fontColor('#555')
            Button(this.nightMode ? $r('app.string.common_on') : $r('app.string.common_off'))
              .fontSize(12).height(32)
              .backgroundColor(this.nightMode ? '#333' : '#e0e0e0')
              .fontColor(this.nightMode ? Color.White : '#333')
              .onClick(() => this.toggleNightMode())
          }

          // Auto-flip toggle
          Row({ space: 8 }) {
            Text($r('app.string.reader_autoflip'))
              .fontSize(13).fontColor('#555')
            Button(this.autoFlip ? $r('app.string.common_on') : $r('app.string.common_off'))
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
            Text($r('app.string.reader_autoscroll'))
              .fontSize(13).fontColor('#555')
            Button(this.autoScroll ? $r('app.string.common_on') : $r('app.string.common_off'))
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
            Button($r('app.string.ann_highlight'))
              .fontSize(12).height(32)
              .backgroundColor('#ff9800').fontColor(Color.White)
              .onClick(() => this.addHighlightOnPage())
            Button($r('app.string.ann_ink'))
              .fontSize(12).height(32)
              .backgroundColor(this.drawMode ? '#333' : '#e0e0e0')
              .fontColor(this.drawMode ? Color.White : '#333')
              .onClick(() => this.toggleDrawMode())
            Button($r('app.string.common_save'))
              .fontSize(12).height(32)
              .backgroundColor('#2a7').fontColor(Color.White)
              .onClick(() => this.saveAnnotatedDoc())
          }

          // H6: Quick crop toggle (10% margin trim)
          Row({ space: 8 }) {
            Text($r('app.string.reader_crop'))
              .fontSize(13).fontColor('#555')
            Button(this.cropEnabled ? $r('app.string.common_on') : $r('app.string.common_off'))
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
            Text($r('app.string.reader_fullscreen'))
              .fontSize(13).fontColor('#555')
            Button(this.isFullscreen ? $r('app.string.common_exit') : $r('app.string.reader_fs_enter'))
              .fontSize(12).height(32)
              .backgroundColor(this.isFullscreen ? '#e57373' : '#e0e0e0')
              .fontColor(this.isFullscreen ? Color.White : '#333')
              .onClick(() => {
                this.toggleFullscreen();
              })
          }

          /* I2: RTL reading direction */
          Row({ space: 8 }) {
            Text('RTL:')
              .fontSize(13).fontColor('#555')
            Button(this.rtlEnabled ? $r('app.string.common_on') : $r('app.string.common_off'))
              .fontSize(12).height(32)
              .backgroundColor(this.rtlEnabled ? '#2a7' : '#e0e0e0')
              .fontColor(this.rtlEnabled ? Color.White : '#333')
              .onClick(() => {
                this.rtlEnabled = !this.rtlEnabled;
                /* For reflowable docs, apply CSS direction */
                if (this.isReflow) {
                  this.applyTypography();
                }
                /* RTL is the mirrored .index() binding at the Swiper
                 * (.index(totalPages-1-currentPage)) plus the matching
                 * un-mirror in onChange (0.9.65) */
                console.info(`[Reader] RTL mode: ${this.rtlEnabled ? 'ON' : 'OFF'}`);
              })
          }

          /* I6: Background image (Phase 7: real picker → sandbox path) */
          Row({ space: 8 }) {
            Button(this.bgImagePath.length > 0 ? $r('app.string.reader_bgimage_on') : $r('app.string.reader_bgimage'))
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
            Button($r('app.string.reader_text_replace'))
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
              Button($r('app.string.reader_del_first'))
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

          /* 0.9.59: bookmark panel entry (the bottom row is the right-aligned
           * 6-key set now; 🔖 there is the quick-add toggle) */
          Row({ space: 8 }) {
            Button($r('app.string.reader_bm_panel'))
              .fontSize(12).height(32)
              .backgroundColor('#5c6bc0').fontColor(Color.White)
              .onClick(() => {
                this.showMenu = false;
                this.loadBookmarks().then(() => {
                  this.showBookmarks = true;
                });
              })
          }

          // TTS & RSVP entries (sprint D)
          Row({ space: 8 }) {
            Button($r('app.string.menu_tts'))
              .fontSize(12).height(32)
              .backgroundColor('#7b68ee').fontColor(Color.White)
              .onClick(() => {
                this.showMenu = false;
                this.openTtsPanel();
              })
            Button($r('app.string.menu_rsvp'))
              .fontSize(12).height(32)
              .backgroundColor('#e91e63').fontColor(Color.White)
              .onClick(() => {
                this.showMenu = false;
                this.openRsvpPanel();
              })
          }

          // TOC button
          if (this.tocEntries.length > 0) {
            Button($r('app.string.reader_toc')).id('reader_btn_toc')
              .fontSize(13).height(36)
              .backgroundColor('#2a7').fontColor(Color.White)
              .onClick(() => {
                this.showMenu = false; /* 0.9.65: parity with the panel's other entry rows */
                this.showToc = true;
                console.info('[Reader] TOC panel opened');
              })
          }

          /* K1: Share book */
          Row({ space: 8 }) {
            Button($r('app.string.reader_share'))
              .fontSize(12).height(32)
              .backgroundColor('#42a5f5').fontColor(Color.White)
              .onClick(() => {
                this.showMenu = false;
                this.shareBook();
                console.info('[Reader] Share book triggered');
              })
          }

          // File info (sprint G6)
          Button($r('app.string.reader_file_info'))
            .fontSize(13).height(36)
            .backgroundColor('#90a4ae').fontColor(Color.White)
            .onClick(() => {
              this.showMenu = false;
              this.openFileInfo();
              this.showFileInfo = true;
              console.info('[Reader] File info dialog opened');
            })

          // Close menu
          Button($r('app.string.common_close'))
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

      if (this.showTextReplace) {
        Stack({ alignContent: Alignment.Center }) {
          Column()
            .width('100%').height('100%')
            .backgroundColor('#66000000')
            .onClick(() => { this.showTextReplace = false; })

          Column({ space: 12 }) {
            Text($r('app.string.repl_title'))
              .fontSize(15).fontWeight(FontWeight.Bold)

            TextInput({ placeholder: $r('app.string.repl_find_placeholder') })
              .fontSize(13).height(36)
              .onChange((v: string) => { this.replaceFrom = v; })

            TextInput({ placeholder: $r('app.string.repl_replace') })
              .fontSize(13).height(36)
              .onChange((v: string) => { this.replaceTo = v; })

            Row({ space: 8 }) {
              Button($r('app.string.common_apply'))
                .fontSize(12).height(32).layoutWeight(1)
                .backgroundColor('#2a7').fontColor(Color.White)
                .onClick(() => {
                  /* Apply text replacement via CSS content or post-processing */
                  console.info(`[Reader] Text replace: '${this.replaceFrom}' -> '${this.replaceTo}'`);
                  this.showTextReplace = false;
                })
              Row() {
                Button($r('app.string.repl_export'))
                  .fontSize(12).height(30)
                  .onClick(() => { this.exportTtsText(); })
              }.width('90%').margin({ top: 8 })

              Button($r('app.string.common_close'))
                .fontSize(12).height(32).layoutWeight(1)
                .backgroundColor('#e0e0e0').fontColor('#333')
                .onClick(() => { this.showTextReplace = false; })
            }

            Text($r('app.string.repl_hint'))
              .fontSize(10).fontColor('#999')
          }
          .width('80%')
          .padding(20)
          .backgroundColor(Color.White)
          .borderRadius(12)
        }
      }

      /* 0.9.61: file info (Android FileInformationDialog parity) */
      if (this.showFileInfo) {
        this.buildFileInfoDialog()
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
            tocEntries: this.tocEntries,
            onJumpPage: (page: number) => {
              this.pushPageHistory();
              this.seekToPage(page);
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
              Text($r('app.string.reader_bookmark'))
                .fontSize(16).fontWeight(FontWeight.Bold)
              Blank()
              Text($r('app.string.reader_export'))
                .fontSize(13).fontColor(this.thm())
                .onClick(() => {
                  this.showExportDialog = true;
                })
            }
            .width('100%')
            .margin({ bottom: 8 })

            if (this.bookmarks.length === 0) {
              Text($r('app.string.bookmarks_empty'))
                .fontSize(13).fontColor(Color.Gray)
            } else {
              List() {
                ForEach(this.bookmarks, (bm: Bookmark) => {
                  ListItem() {
                    Row() {
                      Text(`p${this.bmLandingPage(bm) + 1}  ${bm.label.length > 0 ? bm.label : this.L($r('app.string.reader_bookmark'))}`)
                        .fontSize(13).fontColor('#333')
                        .layoutWeight(1)
                        .onClick(() => {
                          this.jumpToBookmark(bm);
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
              Text($r('app.string.ai_notes'))
                .fontSize(14).fontWeight(FontWeight.Bold).fontColor('#333')
              Text(`(${this.aiNotes.length})`)
                .fontSize(12).fontColor('#999')
            }
            .width('100%')

            if (this.aiNotes.length === 0) {
              Text($r('app.string.ai_notes_empty'))
                .fontSize(12).fontColor(Color.Gray)
            } else {
              List() {
                ForEach(this.aiNotes, (n: AiNote) => {
                  ListItem() {
                    Row({ space: 8 }) {
                      Text(n.kind)
                        .fontSize(10).fontColor(Color.White)
                        .backgroundColor(this.thm())
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
                            /* 0.9.44: deletion tombstone so the WebDAV sync
                             * propagates the AI-note removal (android dk) */
                            const msKey = Date.parse(n.date);
                            if (!Number.isNaN(msKey) && this.documentPath.length > 0) {
                              recordBookmarkTombstone(getContext(this),
                                this.documentPath.substring(this.documentPath.lastIndexOf('/') + 1),
                                String(msKey)).then(() => {
                                this.loadAiNotes();
                              });
                            } else {
                              this.loadAiNotes();
                            }
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
              Text($r('app.string.common_refresh'))
                .fontSize(13).fontColor(this.thm())
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

      /* Round 6: encrypted-document password dialog */
      if (this.showPwd) {
        Column({ space: 12 }) {
          Text($r('app.string.pwd_dialog_title'))
            .fontSize(16).fontWeight(FontWeight.Medium).fontColor('#212121')
          Text($r('app.string.pwd_dialog_hint'))
            .fontSize(12).fontColor('#888')
          TextInput({ placeholder: $r('app.string.common_password'), text: this.pwdInput })
            .type(InputType.Password)
            .height(40).width('100%').fontSize(14)
            .onChange((v: string) => {
              this.pwdInput = v;
            })
          Row({ space: 10 }) {
            Button($r('app.string.common_cancel'))
              .fontSize(14).height(36).layoutWeight(1)
              .backgroundColor('#e0e0e0').fontColor('#333')
              .onClick(() => {
                this.showPwd = false;
                router.back();
              })
            Button($r('app.string.common_unlock'))
              .fontSize(14).height(36).layoutWeight(1)
              .backgroundColor(this.thm()).fontColor(Color.White)
              .onClick(() => {
                this.doUnlockDoc();
              })
          }
          .width('100%')
        }
        .width('72%')
        .padding(16)
        .borderRadius(12)
        .backgroundColor(this.chromeBg())
      }

      /* Round 6: jump-to-page dialog */
      if (this.showJump) {
        Column({ space: 12 }) {
          Text($r('app.string.jump_title'))
            .fontSize(16).fontWeight(FontWeight.Medium).fontColor('#212121')
          TextInput({ placeholder: `1-${this.totalPages}`, text: this.jumpInput }).id('reader_jump_input')
            .type(InputType.Number)
            .height(40).width('100%').fontSize(14)
            .onChange((v: string) => {
              this.jumpInput = v;
            })
          Row({ space: 10 }) {
            Button($r('app.string.common_cancel'))
              .fontSize(14).height(36).layoutWeight(1)
              .backgroundColor('#e0e0e0').fontColor('#333')
              .onClick(() => {
                this.showJump = false;
              })
            Button($r('app.string.jump_go'))
              .fontSize(14).height(36).layoutWeight(1)
              .backgroundColor(this.thm()).fontColor(Color.White)
              .onClick(() => {
                this.doJump();
              })
          }
          .width('100%')
        }
        .width('72%')
        .padding(16)
        .borderRadius(12)
        .backgroundColor(this.chromeBg())
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

      /* 0.9.20: PDF bilingual panel / badge card / active chip */
      if (this.showPdfBiPanel) {
        this.buildPdfBiPanel()
      }
      if (this.pdfBiCard !== undefined) {
        this.buildPdfBadgeCard()
      }
      if (this.pdfBiActive) {
        this.buildPdfBiChip()
      }
      if (this.pdfBiActive && this.pdfBiMode === PDF_TRANS_FLOAT) {
        this.buildPdfBadgeMarks()
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
            Text($r('app.string.export_title'))
              .fontSize(15).fontWeight(FontWeight.Bold)
            Text($r('app.string.export_hint'))
              .fontSize(11).fontColor('#999')
            Button('TXT')
              .width('80%').height(38).fontSize(13)
              .backgroundColor(this.thm()).fontColor(Color.White)
              .onClick(() => {
                this.doExportNotes(false);
              })
            Button('Markdown')
              .width('80%').height(38).fontSize(13)
              .backgroundColor('#00897B').fontColor(Color.White)
              .onClick(() => {
                this.doExportNotes(true);
              })
            Button($r('app.string.common_cancel'))
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
              Text($r('app.string.tts_panel_title'))
                .fontSize(16).fontWeight(FontWeight.Bold)

              Text(this.ttsStatus)
                .fontSize(13).fontColor(this.ttsEngineOk ? '#2a7' : '#e57373')

              if (!this.ttsEngineOk) {
                Text($r('app.string.tts_no_engine_hint'))
                  .fontSize(11).fontColor('#e57373').textAlign(TextAlign.Center)
              }

              Text(this.ttsSentences.length > 0 && this.ttsIndex < this.ttsSentences.length
                ? this.ttsSentences[this.ttsIndex] : $r('app.string.tts_no_text'))
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
                Button($r('app.string.tts_prev_sentence'))
                  .fontSize(12).height(40)
                  .backgroundColor('#e0e0e0').fontColor('#333')
                  .onClick(() => this.ttsPrevSentence())
                Button($r('app.string.tts_next_sentence'))
                  .fontSize(12).height(40)
                  .backgroundColor('#e0e0e0').fontColor('#333')
                  .onClick(() => this.ttsNextSentence())
              }

              Row({ space: 8 }) {
                Text($r('app.string.tts_rate'))
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
                Text($r('app.string.tts_pitch'))
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
                Button($r('app.string.tts_switch_rsvp'))
                  .fontSize(12).height(36)
                  .backgroundColor('#e91e63').fontColor(Color.White)
                  .onClick(() => {
                    this.closeTtsPanel();
                    this.openRsvpPanel();
                  })
                Button($r('app.string.common_close'))
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
              Text($r('app.string.rsvp_title'))
                .fontSize(16).fontWeight(FontWeight.Bold)

              Text(`${this.rsvpWpm} 字/分`)
                .fontSize(13).fontColor('#e91e63')

              /* word flash area */
              Column() {
                Text(this.rsvpWord.length > 0 ? this.rsvpWord : $r('app.string.rsvp_start'))
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
                Button(this.rsvpPaused ? $r('app.string.rsvp_resume') : $r('app.string.rsvp_start'))
                  .fontSize(13).height(40)
                  .backgroundColor('#e91e63').fontColor(Color.White)
                  .onClick(() => this.rsvpToggle())
                Button($r('app.string.rsvp_pause'))
                  .fontSize(13).height(40)
                  .backgroundColor('#ff9800').fontColor(Color.White)
                  .onClick(() => this.rsvpPause())
                Button($r('app.string.rsvp_stop'))
                  .fontSize(13).height(40)
                  .backgroundColor('#e57373').fontColor(Color.White)
                  .onClick(() => this.rsvpStop())
              }

              Row({ space: 8 }) {
                Text($r('app.string.rsvp_speed'))
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

              Text($r('app.string.rsvp_hint'))
                .fontSize(11).fontColor('#999').textAlign(TextAlign.Center)

              Button($r('app.string.rsvp_switch_tts'))
                .fontSize(12).height(36)
                .backgroundColor('#7b68ee').fontColor(Color.White)
                .onClick(() => {
                  this.closeRsvpPanel();
                  this.openTtsPanel();
                })

              Button($r('app.string.common_close'))
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

      /* 0.9.59: 4-tab reader preference dialog (偏好) */
      if (this.showPref) {
        this.buildPrefDialog()
      }

      /* 0.9.60 (Android ShareDialog parity): bottom-bar ⋮ book menu */
      if (this.showBookMenu) {
        this.buildBookMenuDialog()
      }
      /* 0.9.60 (Android NoteEditDialog parity): bottom-bar ✏ note editor */
      if (this.showNoteEdit) {
        this.buildNoteEditDialog()
      }
      /* 0.9.60: add-to-playlist picker */
      if (this.showPlPick) {
        this.buildPlPickDialog()
      }
      /* 0.9.60 (Android ai_intro_book parity): AI book intro */
      if (this.showAiIntro) {
        this.buildAiIntroDialog()
      }
      /* 0.9.61 (Android search popup parity) */
      if (this.showSearch) {
        this.buildSearchDialog()
      }
      /* 0.9.62 (Android dialogShowBookmarks parity): bookmark dialog */
      if (this.showBmList) {
        this.buildBmListDialog()
      }
      /* 0.9.62: named-bookmark input dialog */
      if (this.showBmAdd) {
        this.buildBmAddDialog()
      }
      /* 0.9.62: bookmark sort/share menu */
      if (this.showBmSort) {
        this.buildBmSortMenu()
      }

      /* Round 8: remote streaming open overlay */
      if (this.remoteOpening) {
        Column({ space: 12 }) {
          Progress({ value: 40, total: 100 })
            .width(46).height(46).color(Color.White)
          Text(`正在打开远程文档…\n${this.remoteOpenMsg}`)
            .fontSize(13).fontColor(Color.White).textAlign(TextAlign.Center)
        }
        .justifyContent(FlexAlign.Center)
        .width('100%').height('100%')
        .backgroundColor('#96000000')
      }
      /* 0.9.34 P2-14: first-paint gate (Android FirstPaintGate parity) - solid
       * opening screen until the first page has actually rendered. */
      if (this.firstPaintArmed) {
        Column({ space: 12 }) {
          Progress({ value: 40, total: 100 })
            .width(46).height(46).color(this.thm())
          Text($r('app.string.common_loading'))
            .fontSize(13).fontColor(this.nightMode ? '#d0d0d0' : '#5a5a5a')
        }
        .justifyContent(FlexAlign.Center)
        .width('100%').height('100%')
        .backgroundColor(this.nightMode ? '#121212' : '#fafafa')
      }
    }
    .width('100%')
    .height('100%')
    /* Round 9: volume-key navigation; intercept at the root so the system
     * volume is not changed while the reader consumes the keys */
    .onKeyEventDispatch((event: KeyEvent): boolean => {
      /* KeyType.Down = 0: act once per press */
      if (event.type !== 0) {
        return false;
      }
      return this.onVolumeKey(event.keyCode);
    })
  }
}
