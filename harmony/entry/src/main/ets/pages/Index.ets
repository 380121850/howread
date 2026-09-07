import fs from '@ohos.file.fs';
import image from '@ohos.multimedia.image';
import router from '@ohos.router';
import picker from '@ohos.file.picker';
import util from '@ohos.util';
import common from '@ohos.app.ability.common';
import {
  version, openDocument, pageCount, renderPage, renderPageAsync,
  getText, searchText, searchDocument, getDocumentInfo, getToc, getPageSize, layoutDocument, closeDocument,
  getAnnotations, addHighlight, deleteAnnotation, saveDocument, isReflowable,
  RenderResult, TextRect, DocumentInfo, TocEntry, RenderOptions, CropRect, AnnotationInfo
} from 'libmupdf_napi.so';
import {
  saveRecentBook, getRecentBooks, getRecentBooksStarred, toggleStar,
  exportRecentBooks, replaceRecentBooks, setBookStatus, setBooksStatus,
  RecentBook
} from '../model/ReadingProgress';
import { exportBookmarks, replaceBookmarks, getBookmarks, removeBookmark, addBookmark, Bookmark } from '../model/Bookmarks';
import {
  loadSettings, saveSettings, defaultSettings, THEME_NAMES, ReaderSettings
} from '../model/Settings';
import { importUriToSandbox } from '../model/FileImport';
import { getTagList, addTagToBook, removeTagFromBook, getSeriesList } from '../model/Tags';
import { getReadingStats, getDailyStats, getMonthlyStats, ReadingStatsData, DayStat, MonthStat } from '../model/ReadingStats';
import { fetchOpds, OPDS_PRESETS, OpdsEntry } from '../model/Opds';
import http from '@ohos.net.http';
import { KEY_PENDING_URI } from '../entryability/EntryAbility';

/** Backup payload shape (sprint E). */
interface BackupFile {
  app: string;
  version: number;
  exportedAt: string;
  settings: ReaderSettings;
  recents: RecentBook[];
  bookmarks: Bookmark[];
}

/** A configured 书库文件夹 entry (Phase 4). */
interface LibFolder {
  name: string;
  path: string;
}

/**
 * Library page: multi-tab shell (Recent / Favorites / Settings).
 * Recent and Favorites show cover thumbnails, progress, and a star toggle.
 * The Settings tab hosts the reader settings + backup/restore, followed by
 * the NAPI self-test output.
 */
@Entry
@Component
struct Index {
  @State currentTab: number = 0;
  @State title: string = 'HowRead';
  @State status: string = 'initializing...';
  @State recentBooks: RecentBook[] = [];
  @State starredBooks: RecentBook[] = [];

  // ---- Sprint G: Bookmark manager + Browse tab ----
  @State allBookmarks: Bookmark[] = [];
  @State bookmarkFilter: string = '';
  @State browseViewMode: number = 0; /* 0=list, 1=grid */
  @State browseCurrentDir: string = '';

  // ---- Settings (sprint E) ----
  @State settings: ReaderSettings = defaultSettings();
  @State backupText: string = '';

  // ---- NAPI self-test states (Settings tab) ----
  @State pixelMap: image.PixelMap | undefined = undefined;
  @State pageInfo: string = '';
  @State textResult: string = '';
  @State docInfo: string = '';
  @State tocText: string = '';
  @State pageSizeText: string = '';
  @State asyncRenderText: string = '';
  @State rotatedText: string = '';
  @State invertedText: string = '';
  @State epubText: string = '';
  @State cropText: string = '';
  @State layoutText: string = '';
  @State fmtText: string = '';
  @State currentPage: number = 0;
  /* Sprint J6: WebDAV cloud drive */
  @State webdavUrl: string = '';
  @State webdavFiles: string[] = [];
  @State webdavStatus: string = 'Enter server URL and tap Connect';
  @State webdavConnecting: boolean = false;
  /* Sprint N: tags + author browsing */
  @State allTags: string[] = [];
  @State activeTagFilter: string = '';
  @State recentSortMode: number = 0; /* 0=recent, 1=title A-Z, 2=author */
  @State recentViewMode: number = 0; /* 0=list, 1=by author, 2=by series */
  @State showTagDialog: boolean = false;
  @State tagEditTarget: string = '';
  @State newTagInput: string = '';

  // ---- Phase 1: Android-aligned shell (top bar + 4 tabs + left drawer + FAB) ----
  /** top bar title = current tab name (Android 18sp bold white) */
  @State topTitle: string = '\u9996\u9875';
  /** left drawer visibility (Android DrawerLayout) */
  @State showDrawer: boolean = false;
  /** modal overlay: 0=none 1=bookmarks 2=network(OPDS/WebDAV) 3=about */
  @State activeOverlay: number = 0;
  /** random reading quote shown in the drawer (refreshed each open) */
  @State drawerQuote: string = '';
  /** cached quote pool loaded once from rawfile/reading_quotes.txt */
  quotePool: string[] = [];
  /** library tab: true → show only starred books */
  @State libStarFilter: boolean = false;
  /* ---- Phase 2: library search / status chips / sort / view modes / batch select ---- */
  /** library search query (title/author/tag) */
  @State libQuery: string = '';
  /** status chip filter: -1=all 0=unread 1=reading 2=finished */
  @State libStatusFilter: number = -1;
  /** view mode: 0=list 1=compact 2=grid 3=cover 4=bookshelf */
  @State libViewMode: number = 0;
  /** sort mode: 0=recent 1=name 2=date 3=author 4=series */
  @State libSortMode: number = 0;
  /** batch selection mode (long-press a row to enter) */
  @State selMode: boolean = false;
  /** selected book paths in batch mode */
  @State selSet: string[] = [];
  // ---- Phase 3: dashboard reading stats ----
  /** aggregated stats for the 5 dashboard cards */
  @State statsData: ReadingStatsData = { totalMs: 0, todayMs: 0, monthMs: 0, todayPages: 0, speedPpm: 0 };
  /** daily stats, last 30 days (most recent last) */
  @State dailyStats: DayStat[] = [];
  /** monthly stats, last 12 months (most recent last) */
  @State monthlyStats: MonthStat[] = [];
  /** chart range: 0=week 1=month 2=year */
  @State chartRange: number = 0;
  /** chart series bound by updateChartData() (reactive @Builder args are not tracked) */
  @State chartValues: number[] = [];
  @State chartLabels: string[] = [];
  // ---- Phase 4: my-files browser ----
  /** subdirectories of the current browse dir */
  @State browseDirs: string[] = [];
  /** files of the current browse dir */
  @State browseFiles: string[] = [];
  /** 书库文件夹 list: {name, path} */
  @State libFolders: LibFolder[] = [];
  /** new-folder input dialog */
  @State showNewFolder: boolean = false;
  @State newFolderName: string = '';
  /** file/dir long-press ops dialog */
  @State opsFile: string = '';
  @State showFileOps: boolean = false;
  /** rename dialog */
  @State showRename: boolean = false;
  @State renameInput: string = '';
  // ---- Phase 5: OPDS 网上书库 ----
  /** 0=OPDS 目录 1=WebDAV 云盘 (network overlay sections) */
  @State netSection: number = 0;
  /** OPDS feed navigation history (urls) */
  @State opdsStack: string[] = [];
  /** current OPDS feed entries */
  @State opdsEntries: OpdsEntry[] = [];
  @State opdsStatus: string = '';
  @State opdsLoading: boolean = false;
  /** custom OPDS feed URL input */
  @State opdsUrl: string = '';
  // ---- Phase 6: app lock ----
  /** true once the passcode has been accepted this session */
  @State appUnlocked: boolean = false;
  /** passcode input for the lock overlay */
  @State lockPin: string = '';
  /** cached cover images for the dashboard carousel */
  @State dashCovers: string[] = [];

  aboutToAppear(): void {
    this.runApiTest();
    this.loadLibrary();
    this.ensureDemoBook();
    this.consumePendingUri();
    this.initSettings();
    this.loadAllBookmarks();
    this.initBrowseDir();
    this.loadQuotes();
    this.loadStats();
  }

  /** Phase 3: refresh stats + library whenever the Index page becomes visible
   *  again (e.g. after returning from the Reader via router back). */
  onPageShow(): void {
    this.loadStats();
    this.loadLibrary();
    if (this.settings.appLockEnabled && !this.appUnlocked) {
      this.checkAppLock();
    }
  }

  private async initSettings(): Promise<void> {
    const ctx = getContext(this);
    this.settings = await loadSettings(ctx);
    console.info(`[Index] Settings loaded: theme=${this.settings.theme} font=${this.settings.fontSize}`);
    this.checkAppLock();
  }

  /** Phase 6: show the lock overlay if the app lock is enabled and not yet unlocked. */
  private checkAppLock(): void {
    if (this.settings.appLockEnabled && !this.appUnlocked) {
      this.lockPin = '';
      this.activeOverlay = 7;
      console.info('[Index] App lock requested');
    }
  }

  /** Verify the entered passcode; unlock on match. */
  private tryUnlock(): void {
    if (this.lockPin.trim() === this.settings.appLockPass) {
      this.appUnlocked = true;
      this.activeOverlay = 0;
      console.info('[Index] App unlocked');
    } else {
      this.lockPin = '';
      console.info('[Index] App lock: wrong passcode');
    }
  }

  private async saveNow(): Promise<void> {
    await saveSettings(getContext(this), this.settings);
  }

  /** Load all bookmarks across documents for the Bookmark manager tab (sprint G). */
  private async loadAllBookmarks(): Promise<void> {
    try {
      const ctx = getContext(this);
      this.allBookmarks = await exportBookmarks(ctx);
      console.info(`[Index] All bookmarks loaded: ${this.allBookmarks.length}`);
    } catch (e) {
      console.error('[Index] loadAllBookmarks failed');
    }
  }

  /** Phase 4: start at the my-files: root and seed the 书库文件夹 list. */
  private initBrowseDir(): void {
    try {
      const ctx = getContext(this);
      this.browseCurrentDir = '';
      this.browseDirs = [];
      this.browseFiles = [];
      this.libFolders = [{ name: '\u4E66\u5E93', path: ctx.cacheDir }];
      console.info('[Index] Browse root initialized');
    } catch (e) {
      this.browseCurrentDir = '';
    }
  }

  /** Get filtered bookmarks based on search query. */
  private getFilteredBookmarks(): Bookmark[] {
    if (this.bookmarkFilter.length === 0) {
      return this.allBookmarks;
    }
    const q: string = this.bookmarkFilter.toLowerCase();
    const result: Bookmark[] = [];
    for (let i = 0; i < this.allBookmarks.length; i++) {
      const bm = this.allBookmarks[i];
      const name = bm.path.substring(bm.path.lastIndexOf('/') + 1).toLowerCase();
      if (name.indexOf(q) >= 0 || bm.label.toLowerCase().indexOf(q) >= 0) {
        result.push(bm);
      }
    }
    return result;
  }

  /** Open a book from a bookmark entry. */
  private openBookmarkBook(bm: Bookmark): void {
    const name = bm.path.substring(bm.path.lastIndexOf('/') + 1);
    router.pushUrl({ url: 'components/Reader', params: { docPath: bm.path, title: name } })
      .then(() => {
        console.info(`[Index] Opened book from bookmark: ${name} page=${bm.page}`);
      })
      .catch((err: Error) => {
        console.error('[Index] Navigate to bookmark book failed');
      });
  }

  /** Delete a bookmark entry. */
  private async deleteBookmarkEntry(bm: Bookmark): Promise<void> {
    try {
      const ctx = getContext(this);
      await removeBookmark(ctx, bm.path, bm.page);
      await this.loadAllBookmarks();
      console.info(`[Index] Bookmark deleted: page ${bm.page} of ${bm.path}`);
    } catch (e) {
      console.error('[Index] deleteBookmarkEntry failed');
    }
  }

  /** List files in current browse directory. */
  private getBrowseFiles(): string[] {
    try {
      const entries: string[] = fs.listFileSync(this.browseCurrentDir);
      /* filter to known book extensions */
      const exts: string[] = ['.pdf', '.epub', '.txt', '.html', '.cbz', '.mobi', '.xps', '.tif', '.tiff'];
      const result: string[] = [];
      for (let i = 0; i < entries.length; i++) {
        const lower = entries[i].toLowerCase();
        for (let j = 0; j < exts.length; j++) {
          if (lower.endsWith(exts[j])) {
            result.push(entries[i]);
            break;
          }
        }
      }
      return result;
    } catch (e) {
      console.error('[Index] getBrowseFiles failed');
      return [];
    }
  }

  /** Open a file from the browse tab. */
  private openBrowseFile(fileName: string): void {
    const fullPath = this.browseCurrentDir + '/' + fileName;
    const book: RecentBook = {
      path: fullPath,
      title: fileName,
      page: 0,
      totalPages: 0,
      lastRead: new Date().toISOString(),
      star: false,
      coverPath: '',
      author: '',
          tags: [],
          series: '',
          genre: '',
          status: 0
    };
    const ctx = getContext(this);
    saveRecentBook(ctx, book).then(() => {
      this.generateCover(ctx, book);
      this.loadLibrary();
    });
    router.pushUrl({ url: 'components/Reader', params: { docPath: fullPath, title: fileName } })
      .then(() => {
        console.info(`[Index] Opened from browse: ${fileName}`);
      })
      .catch((err: Error) => {
        console.error('[Index] Navigate to browse file failed');
      });
  }

  /** Export settings + recents + bookmarks to a JSON file in the sandbox. */
  private async doBackup(): Promise<void> {
    try {
      const ctx = getContext(this);
      const recents = await exportRecentBooks(ctx);
      const bookmarks = await exportBookmarks(ctx);
      const payload: BackupFile = {
        app: 'librera-harmony',
        version: 1,
        exportedAt: new Date().toISOString(),
        settings: this.settings,
        recents: recents,
        bookmarks: bookmarks
      };
      const target = ctx.filesDir + '/librera_backup.json';
      const f = fs.openSync(target, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      fs.writeSync(f.fd, JSON.stringify(payload));
      fs.closeSync(f);
      this.backupText = `已导出 ${recents.length} 本记录 / ${bookmarks.length} 条书签 → ${target}`;
      console.info(`[Index] Backup written: ${target}`);
    } catch (e) {
      this.backupText = '导出失败';
      console.error('[Index] backup failed');
    }
  }

  /** Restore settings + recents + bookmarks from the sandbox backup file. */
  private async doRestore(): Promise<void> {
    try {
      const ctx = getContext(this);
      const target = ctx.filesDir + '/librera_backup.json';
      const content = fs.readTextSync(target);
      const payload = JSON.parse(content) as BackupFile;
      if (payload.app !== 'librera-harmony' || payload.settings === undefined) {
        this.backupText = '备份文件无效';
        return;
      }
      await saveSettings(ctx, payload.settings);
      this.settings = payload.settings;
      await replaceRecentBooks(ctx, payload.recents);
      await replaceBookmarks(ctx, payload.bookmarks);
      await this.loadLibrary();
      this.backupText = `已恢复设置 + ${payload.recents.length} 本记录 + ${payload.bookmarks.length} 条书签`;
      console.info(`[Index] Backup restored from ${target}`);
    } catch (e) {
      this.backupText = '恢复失败（请先导出）';
      console.error('[Index] restore failed');
    }
  }

  /**
   * Seed a demo book (rawfile test.pdf) into the library on first run and
   * exercise the cover-generation pipeline (metadata + thumbnail PNG).
   */
  private async ensureDemoBook(): Promise<void> {
    try {
      const ctx = getContext(this);
      const demoPath = ctx.cacheDir + '/test.pdf';
      let existing: RecentBook | undefined = undefined;
      const list = await getRecentBooks(ctx);
      for (let i = 0; i < list.length; i++) {
        if (list[i].path === demoPath) {
          existing = list[i];
          break;
        }
      }
      if (existing !== undefined) {
        console.info(`[Index] Demo book exists: totalPages=${existing.totalPages} cover='${existing.coverPath}'`);
        if (existing.coverPath.length > 0 && existing.totalPages > 0) {
          return; /* demo book already has a cover */
        }
        await this.generateCover(ctx, existing);
      } else {
        /* extract rawfile into the sandbox and add a library entry */
        const content: Uint8Array = await ctx.resourceManager.getRawFileContent('test.pdf');
        const f = fs.openSync(demoPath, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
        fs.writeSync(f.fd, content.buffer);
        fs.closeSync(f);
        const book: RecentBook = {
          path: demoPath,
          title: 'test.pdf (demo)',
          page: 0,
          totalPages: 0,
          lastRead: new Date().toISOString(),
          star: false,
          coverPath: '',
          author: '',
          tags: [],
          series: '',
          genre: '',
          status: 0
        };
        await saveRecentBook(ctx, book);
        console.info('[Index] Demo book seeded');
        await this.generateCover(ctx, book);
      }

      /* Also seed a reflowable demo (Alice in Wonderland) so the EPUB
       * pipeline (layout / font size / reflow) is reachable from the UI. */
      const epubPath = ctx.cacheDir + '/test.epub';
      let epubExisting: RecentBook | undefined = undefined;
      const epubList = await getRecentBooks(ctx);
      for (let i = 0; i < epubList.length; i++) {
        if (epubList[i].path === epubPath) {
          epubExisting = epubList[i];
          break;
        }
      }
      if (epubExisting === undefined) {
        const epubContent: Uint8Array = await ctx.resourceManager.getRawFileContent('test.epub');
        const ef = fs.openSync(epubPath, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
        fs.writeSync(ef.fd, epubContent.buffer);
        fs.closeSync(ef);
        const epubBook: RecentBook = {
          path: epubPath,
          title: 'Alice (demo EPUB)',
          page: 0,
          totalPages: 0,
          lastRead: new Date().toISOString(),
          star: false,
          coverPath: '',
          author: 'Lewis Carroll',
          tags: [],
          series: '',
          genre: '',
          status: 0
        };
        await saveRecentBook(ctx, epubBook);
        console.info('[Index] Demo EPUB seeded');
        await this.generateCover(ctx, epubBook);
      } else if (epubExisting.coverPath.length === 0 || epubExisting.totalPages === 0) {
        await this.generateCover(ctx, epubExisting);
      }

      /* Format demos (sprint F): TXT / HTML (reflowable) + CBZ (image comic) */
      await this.seedFormatDemo(ctx, 'demo.txt', 'demo.txt (TXT)', '');
      await this.seedFormatDemo(ctx, 'demo.html', 'demo.html (HTML)', '');
      await this.seedFormatDemo(ctx, 'demo.cbz', 'demo.cbz (CBZ)', '');
      /* Sprint J: MOBI / XPS / TIFF format demos */
      await this.seedFormatDemo(ctx, 'demo.mobi', 'demo.mobi (MOBI)', '');
      await this.seedFormatDemo(ctx, 'demo.xps', 'demo.xps (XPS)', '');
      await this.seedFormatDemo(ctx, 'demo.tiff', 'demo.tiff (TIFF)', '');
      await this.loadLibrary();
    } catch (e) {
      console.error('[Index] ensureDemoBook failed');
    }
  }

  /** Seed a rawfile demo book into the library, generating its cover once. */
  private async seedFormatDemo(ctx: Context, rawName: string, title: string, author: string): Promise<void> {
    try {
      const path = ctx.cacheDir + '/' + rawName;
      const list = await getRecentBooks(ctx);
      let existing: RecentBook | undefined = undefined;
      for (let i = 0; i < list.length; i++) {
        if (list[i].path === path) {
          existing = list[i];
          break;
        }
      }
      if (existing === undefined) {
        const content: Uint8Array = await ctx.resourceManager.getRawFileContent(rawName);
        const f = fs.openSync(path, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
        fs.writeSync(f.fd, content.buffer);
        fs.closeSync(f);
        const book: RecentBook = {
          path: path,
          title: title,
          page: 0,
          totalPages: 0,
          lastRead: new Date().toISOString(),
          star: false,
          coverPath: '',
          author: author,
          tags: [],
          series: '',
          genre: '',
          status: 0
        };
        await saveRecentBook(ctx, book);
        console.info(`[Index] Demo seeded: ${title}`);
        await this.generateCover(ctx, book);
      } else if (existing.coverPath.length === 0 || existing.totalPages === 0) {
        await this.generateCover(ctx, existing);
      }
    } catch (e) {
      console.error(`[Index] seedFormatDemo failed: ${rawName}`);
    }
  }

  private async loadLibrary(): Promise<void> {
    try {
      const ctx = getContext(this);
      this.recentBooks = await getRecentBooks(ctx);
      this.starredBooks = await getRecentBooksStarred(ctx);
      /* Sprint N: load tag list */
      this.allTags = await getTagList(ctx);
      console.info(`[Index] Library loaded: ${this.recentBooks.length} recent, ${this.starredBooks.length} starred`);
    } catch (e) {
      console.error('[Index] loadLibrary failed');
    }
  }

  /**
   * Handle "open with" file-association launches: EntryAbility stashed the
   * want URI in AppStorage; import it and jump straight to the Reader.
   */
  private consumePendingUri(): void {
    const uri: string | undefined = AppStorage.get<string>(KEY_PENDING_URI);
    if (uri === undefined || uri.length === 0) {
      return;
    }
    AppStorage.setOrCreate(KEY_PENDING_URI, '');
    console.info(`[Index] Pending open-with URI: ${uri}`);
    try {
      const ctx = getContext(this);
      const target = importUriToSandbox(ctx, uri);
      const lastSlash: number = target.lastIndexOf('/');
      const fileName = lastSlash >= 0 ? target.substring(lastSlash + 1) : target;
      const book: RecentBook = {
        path: target,
        title: fileName,
        page: 0,
        totalPages: 0,
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
      this.generateCover(ctx, book);
      router.pushUrl({ url: 'components/Reader', params: { docPath: target, title: fileName } })
        .then(() => {
          console.info(`[Index] Navigated to Reader (open-with: ${fileName})`);
        })
        .catch((err: Error) => {
          console.error('[Index] Navigate failed: ' + JSON.stringify(err));
        });
    } catch (e) {
      console.error('[Index] Open-with import failed: ' + JSON.stringify(e));
    }
  }

  /**
   * Generate a cover thumbnail PNG for a book: open the doc, read metadata,
   * render page 0 at low zoom, encode to PNG in cacheDir/covers/.
   * Also backfills author + totalPages into the persisted record (B2).
   */
  private async generateCover(ctx: Context, book: RecentBook): Promise<void> {
    let handle: ESObject = undefined;
    try {
      handle = openDocument(book.path);
      const pages: number = pageCount(handle);
      book.totalPages = pages;

      /* B2: extract author metadata */
      try {
        const info: DocumentInfo = getDocumentInfo(handle);
        if (info.author !== undefined && info.author !== null && info.author.length > 0) {
          book.author = info.author;
        }
      } catch (e) {
        /* metadata optional */
      }

      /* render page 0 at low zoom for the cover */
      const coverOpts: RenderOptions = { zoom: 0.18 };
      const res: RenderResult = await renderPageAsync(handle, 0, coverOpts);
      const opts: image.InitializationOptions = {
        size: { width: res.width, height: res.height },
        pixelFormat: image.PixelMapFormat.RGBA_8888
      };
      const pm: image.PixelMap = await image.createPixelMap(res.data, opts);

      /* encode to PNG in cacheDir/covers/ */
      const coversDir = ctx.cacheDir + '/covers';
      try {
        fs.mkdirSync(coversDir);
      } catch (e) {
        /* already exists (2nd+ cover) — fine */
      }
      const safe = book.path.replace(/[\/\\:]/g, '_').substring(0, 60);
      const coverFile = coversDir + '/' + safe + '.png';
      const f = fs.openSync(coverFile, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      const packer: image.ImagePacker = image.createImagePacker();
      const pOpts: image.PackingOption = { format: 'image/png', quality: 80 };
      await packer.packToFile(pm, f.fd, pOpts);
      packer.release();
      fs.closeSync(f);
      book.coverPath = coverFile;
      /* persist cover + totalPages + author together */
      await saveRecentBook(ctx, book);
      console.info(`[Index] Cover generated: ${coverFile} (${res.width}x${res.height}), author='${book.author}'`);
    } catch (e) {
      const msg: string = e instanceof Error ? e.message : JSON.stringify(e);
      console.error(`[Index] Cover generation failed for ${book.title}: ${msg}`);
    } finally {
      if (handle !== undefined) {
        try { closeDocument(handle); } catch (_e) {}
      }
    }
  }

  private async openFilePicker(): Promise<void> {
    try {
      const ctx = getContext(this);
      let documentPicker = new picker.DocumentViewPicker(ctx);
      let options: picker.DocumentSelectOptions = {
        fileSuffixFilters: ['PDF|pdf', 'EPUB|epub', 'TXT|txt', 'HTML|html', 'CBZ|cbz', 'MOBI|mobi', 'XPS|xps', 'TIFF|tiff|tif'],
        maxSelectNumber: 20,
      };

      const uris: Array<string> = await documentPicker.select(options);
      if (uris.length === 0) {
        console.info('[Index] Picker cancelled');
        return;
      }
      console.info(`[Index] Picked ${uris.length} file(s)`);

      /* import each, save to library, generate cover, then open the first */
      let firstPath = '';
      let firstTitle = '';
      for (let i = 0; i < uris.length; i++) {
        const target = importUriToSandbox(ctx, uris[i]);
        const lastSlash: number = target.lastIndexOf('/');
        const fileName = lastSlash >= 0 ? target.substring(lastSlash + 1) : target;
        const book: RecentBook = {
          path: target,
          title: fileName,
          page: 0,
          totalPages: 0,
          lastRead: new Date().toISOString(),
          star: false,
          coverPath: '',
          author: '',
          tags: [],
          series: '',
          genre: '',
          status: 0
        };
        await saveRecentBook(ctx, book);
        this.generateCover(ctx, book);
        if (i === 0) {
          firstPath = target;
          firstTitle = fileName;
        }
      }
      await this.loadLibrary();

      /* Navigate to Reader with the first picked document */
      router.pushUrl({ url: 'components/Reader', params: { docPath: firstPath, title: firstTitle } })
        .then(() => {
          console.info(`[Index] Navigated to Reader (picked ${uris.length} file(s))`);
        })
        .catch((err: Error) => {
          console.error('[Index] Navigate failed: ' + JSON.stringify(err));
        });
    } catch (e) {
      console.error('[Index] Picker error: ' + JSON.stringify(e));
    }
  }

  private async onToggleStar(book: RecentBook): Promise<void> {
    try {
      const ctx = getContext(this);
      const newStar = await toggleStar(ctx, book.path);
      book.star = newStar;
      await this.loadLibrary();
      console.info(`[Index] Star ${book.title} -> ${newStar}`);
    } catch (e) {
      console.error('[Index] toggleStar failed');
    }
  }

  private openBook(book: RecentBook): void {
    router.pushUrl({ url: 'components/Reader', params: { docPath: book.path, title: book.title } })
      .then(() => {
        console.info(`[Index] Opened book: ${book.title}`);
      })
      .catch((err: Error) => {
        console.error('[Index] Navigate to book failed');
      });
  }

  /** Render a single library list row with cover, meta and star. */
  @Builder
  BookRow(book: RecentBook) {
    Row() {
      /* cover thumbnail */
      if (book.coverPath.length > 0) {
        Image(book.coverPath)
          .width(52).height(70)
          .objectFit(ImageFit.Cover)
          .borderRadius(4)
          .backgroundColor('#eee')
      } else {
        Column() {
          Text('📄')
            .fontSize(26)
        }
        .width(52).height(70)
        .justifyContent(FlexAlign.Center)
        .backgroundColor('#f0f0f0')
        .borderRadius(4)
      }

      Column({ space: 2 }) {
        Text(book.title.length > 22 ? book.title.substring(0, 22) + '…' : book.title)
          .fontSize(13)
          .fontColor('#333')
          .maxLines(1)
        if (book.author.length > 0) {
          Text(book.author)
            .fontSize(11)
            .fontColor('#888')
            .maxLines(1)
        }
        Text(book.totalPages > 0 ? `p${book.page + 1}/${book.totalPages}` : '')
          .fontSize(11)
          .fontColor(Color.Gray)
      }
      .layoutWeight(1)
      .alignItems(HorizontalAlign.Start)
      .margin({ left: 8 })

      /* star toggle */
      Text(book.star ? '★' : '☆')
        .fontSize(22)
        .fontColor(book.star ? '#f5a623' : '#bbb')
        .margin({ right: 6 })
        .onClick(() => {
          this.onToggleStar(book);
        })
    }
    .width('100%')
    .padding({ left: 8, right: 8, top: 6, bottom: 6 })
    .onClick(() => {
      this.openBook(book);
    })
  }

  /** Android-aligned shell: top bar + 4 bottom tabs + left drawer + FAB + overlays. */
  build() {
    Stack({ alignContent: Alignment.BottomEnd }) {
      // ---- main column: top bar + tabs ----
      Column() {
        // Top bar (Android imageParent1: 48dp brand color #3949AB, hamburger + title + import)
        Row() {
          Text('\u2630')
            .fontSize(22).fontColor(Color.White)
            .width(44).height(44)
            .textAlign(TextAlign.Center)
            .onClick(() => {
              this.showDrawer = true;
              this.pickRandomQuote();
            })
          Text(this.topTitle)
            .fontSize(18).fontWeight(FontWeight.Bold).fontColor(Color.White)
            .layoutWeight(1)
            .textAlign(TextAlign.Start)
            .padding({ left: 6 })
          Text('\uff0b')
            .fontSize(26).fontColor(Color.White)
            .width(44).height(44)
            .textAlign(TextAlign.Center)
            .onClick(() => {
              this.openFilePicker();
            })
        }
        .width('100%').height(48)
        .backgroundColor('#3949AB')

        // 4 bottom tabs (Android: 首页 / 书库 / 我的文件 / 偏好)
        Tabs({ barPosition: BarPosition.End, index: this.currentTab }) {
          TabContent() {
            this.buildDashboardTab()
          }
          .tabBar(this.buildTabLabel('\u{1F3E0}', '\u9996\u9875', 0))

          TabContent() {
            this.buildLibraryTab()
          }
          .tabBar(this.buildTabLabel('\u{1F4DA}', '\u4E66\u5E93', 1))

          TabContent() {
            this.buildBrowseTab()
          }
          .tabBar(this.buildTabLabel('\u{1F4C1}', '\u6211\u7684\u6587\u4EF6', 2))

          TabContent() {
            this.buildSettingsTab()
          }
          .tabBar(this.buildTabLabel('\u2699', '\u504F\u597D', 3))
        }
        .width('100%')
        .layoutWeight(1)
        .barMode(BarMode.Fixed)
        .barBackgroundColor('#3949AB')
        .onChange((index: number) => {
          this.switchTab(index);
        })
      }
      .width('100%')
      .height('100%')

      // ---- FAB "continue last book" (Android fabLastBook: home + library only) ----
      if (this.currentTab === 0 || this.currentTab === 1) {
        Button('\u7EE7\u7EED\u9605\u8BFB')
          .fontSize(13).fontColor(Color.White)
          .backgroundColor('#3949AB')
          .height(42)
          .borderRadius(21)
          .margin({ right: 16, bottom: 62 })
          .onClick(() => {
            this.openLastBook();
          })
      }

      // ---- left drawer (Android DrawerLayout overlay) ----
      if (this.showDrawer) {
        Column() {}
          .width('100%').height('100%')
          .backgroundColor('#66000000')
          .onClick(() => {
            this.showDrawer = false;
          })
        /* Row guarantees the panel sits at the LEFT; the weight-1 area closes it */
        Row() {
          this.buildDrawerPanel()
          Column() {}
            .layoutWeight(1).height('100%')
            .onClick(() => {
              this.showDrawer = false;
            })
        }
        .width('100%').height('100%')
      }

      // ---- modal overlays: bookmarks / network(OPDS+WebDAV) / about ----
      if (this.activeOverlay === 1) {
        Column() {}
          .width('100%').height('100%')
          .backgroundColor('#66000000')
          .onClick(() => {
            this.activeOverlay = 0;
          })
        this.buildBookmarkOverlay()
      }
      if (this.activeOverlay === 2) {
        Column() {}
          .width('100%').height('100%')
          .backgroundColor('#66000000')
          .onClick(() => {
            this.activeOverlay = 0;
          })
        this.buildNetworkOverlay()
      }
      if (this.activeOverlay === 3) {
        Column() {}
          .width('100%').height('100%')
          .backgroundColor('#66000000')
          .onClick(() => {
            this.activeOverlay = 0;
          })
        this.buildAboutOverlay()
      }
      if (this.activeOverlay === 4) {
        Column() {}
          .width('100%').height('100%')
          .backgroundColor('#66000000')
          .onClick(() => {
            this.activeOverlay = 0;
            this.showNewFolder = false;
          })
        this.buildNewFolderOverlay()
      }
      if (this.activeOverlay === 5) {
        Column() {}
          .width('100%').height('100%')
          .backgroundColor('#66000000')
          .onClick(() => {
            this.activeOverlay = 0;
            this.showFileOps = false;
          })
        this.buildFileOpsOverlay()
      }
      if (this.activeOverlay === 6) {
        Column() {}
          .width('100%').height('100%')
          .backgroundColor('#66000000')
          .onClick(() => {
            this.activeOverlay = 0;
            this.showRename = false;
          })
        this.buildRenameOverlay()
      }
      if (this.activeOverlay === 7) {
        /* app lock: full-screen, not dismissible by scrim */
        this.buildLockOverlay()
      }
    }
    .width('100%')
    .height('100%')
  }

  /* Sprint O3: cross-library full-text search */
  @State libSearchQuery: string = '';
  @State libSearchResults: [string, number][] = []; /* [bookTitle, hitCount] */
  @State libSearching: boolean = false;

  private async doLibrarySearch(): Promise<void> {
    const q: string = this.libSearchQuery.trim();
    if (q.length === 0 || this.recentBooks.length === 0) return;
    this.libSearching = true;
    this.libSearchResults = [];
    console.info(`[Index] Library search '${q}' across ${this.recentBooks.length} books...`);
    const maxBooks: number = Math.min(this.recentBooks.length, 20);
    for (let i = 0; i < maxBooks; i++) {
      try {
        const h: ESObject = openDocument(this.recentBooks[i].path);
        const jsonStr: string = searchDocument(h, q);
        closeDocument(h);
        const parsed: object = JSON.parse(jsonStr) as object;
        const totalHits: number = parsed['totalHits'] !== undefined ? parsed['totalHits'] as number : 0;
        if (totalHits > 0) {
          this.libSearchResults.push([this.recentBooks[i].title, totalHits]);
        }
      } catch (e) {
        /* skip unreadable docs */
      }
    }
    this.libSearching = false;
    console.info(`[Index] Library search '${q}': ${this.libSearchResults.length} books with hits`);
  }

  /** Sprint N: return recent books filtered by star + active tag + sorted. */
  private getFilteredRecent(): RecentBook[] {
    let list: RecentBook[] = [];
    for (let i = 0; i < this.recentBooks.length; i++) {
      if (this.libStarFilter && !this.recentBooks[i].star) {
        continue;
      }
      if (this.activeTagFilter.length === 0 ||
          this.recentBooks[i].tags.includes(this.activeTagFilter)) {
        list.push(this.recentBooks[i]);
      }
    }
    if (this.recentSortMode === 1) {
      list = list.sort((a: RecentBook, b: RecentBook) => a.title.localeCompare(b.title));
    } else if (this.recentSortMode === 2) {
      list = list.sort((a: RecentBook, b: RecentBook) => a.author.localeCompare(b.author));
    }
    return list;
  }

  /** Sprint N: group books by author or series. */
  private getGroupedBooks(): [string, RecentBook[]][] {
    const groups: Map<string, RecentBook[]> = new Map();
    const filtered = this.getFilteredRecent();
    for (let i = 0; i < filtered.length; i++) {
      const book = filtered[i];
      const key = this.recentViewMode === 2 ? book.series : book.author;
      const label = key.length > 0 ? key : '\u672A\u5206\u7C7B';
      if (groups.has(label)) {
        groups.get(label)!.push(book);
      } else {
        groups.set(label, [book]);
      }
    }
    const result: [string, RecentBook[]][] = [];
    groups.forEach((val: RecentBook[], keyStr: string) => {
      result.push([keyStr, val]);
    });
    return result;
  }

  @Builder
  buildRecentTab() {
    Scroll() {
      Column({ space: 8 }) {
        /* Sprint N: sort + filter bar */
        Row() {
          Button(this.recentSortMode === 0 ? '\u6700\u8FD1' : this.recentSortMode === 1 ? 'A-Z' : '\u4F5C\u8005')
            .fontSize(11).height(26)
            .onClick(() => { this.recentSortMode = (this.recentSortMode + 1) % 3; })
          Button(this.recentViewMode === 0 ? '\u5217\u8868' : this.recentViewMode === 1 ? '\u4F5C\u8005' : '\u7CFB\u5217')
            .fontSize(11).height(26).margin({ left: 6 })
            .onClick(() => { this.recentViewMode = (this.recentViewMode + 1) % 3; })
          if (this.allTags.length > 0) {
            Text(this.activeTagFilter ? '\u7B5B:' + this.activeTagFilter : '\u6807\u7B7E')
              .fontSize(11).fontColor('#2a7').height(26)
              .margin({ left: 6 })
              .onClick(() => {
                const idx = this.allTags.indexOf(this.activeTagFilter);
                this.activeTagFilter = idx < 0 ? (this.allTags.length > 0 ? this.allTags[0] : '') : (idx + 1 >= this.allTags.length ? '' : this.allTags[idx + 1]);
              })
          }
        }.width('94%').margin({ bottom: 8 })

        if (this.getFilteredRecent().length === 0) {
          Text('\u8FD8\u6CA1\u6709\u9605\u8BFB\u8BB0\u5F55')
            .fontSize(13).fontColor('#999')
            .margin({ top: 40 })
        } else if (this.recentViewMode > 0) {
          /* Sprint N: grouped view */
          ForEach(this.getGroupedBooks(), (group: [string, RecentBook[]]) => {
            Column() {
              Text(group[0] + ' (' + group[1].length + ')')
                .fontSize(13).fontWeight(FontWeight.Bold)
                .width('94%').margin({ top: 8, bottom: 4 })
              ForEach(group[1], (book: RecentBook) => {
                Column() {
                  this.BookRow(book)
                  Divider().color('#eee')
                }
              }, (item: RecentBook) => item.path)
            }
          }, (g: [string, RecentBook[]]) => g[0])
        } else {
          ForEach(this.getFilteredRecent(), (book: RecentBook) => {
            Column() {
              this.BookRow(book)
              Divider().color('#eee')
            }
          }, (item: RecentBook) => item.path)
        }
      }
      .width('100%')
      .padding({ left: 8, right: 8, top: 8 })
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  buildStarredTab() {
    Scroll() {
      Column({ space: 8 }) {
        if (this.starredBooks.length === 0) {
          Text('还没有收藏，点列表右侧 ☆ 收藏书籍')
            .fontSize(13).fontColor('#999')
            .margin({ top: 40 })
        } else {
          ForEach(this.starredBooks, (book: RecentBook) => {
            Column() {
              this.BookRow(book)
              Divider().color('#eee')
            }
          }, (item: RecentBook) => item.path)
        }
      }
      .width('100%')
      .padding({ left: 8, right: 8, top: 8 })
    }
    .width('100%')
    .height('100%')
  }

  /* ── Sprint J6: WebDAV cloud drive ─────────────────────────────── */
  private async webdavConnect(): Promise<void> {
    if (this.webdavUrl.length === 0) {
      this.webdavStatus = 'Please enter a WebDAV URL';
      return;
    }
    this.webdavConnecting = true;
    this.webdavStatus = 'Connecting...';
    try {
      const url: string = this.webdavUrl.endsWith('/') ? this.webdavUrl : this.webdavUrl + '/';
      /* WebDAV PROPFIND (using GET as fallback since RequestMethod lacks PROPFIND) */
      let req = http.createHttp();
      let resp = await req.request(url, {
        method: http.RequestMethod.GET,
        header: { 'Depth': '1' },
        connectTimeout: 10000,
        readTimeout: 15000
      });
      if (resp.responseCode === 207 || resp.responseCode === 200) {
        /* Parse multistatus XML response - extract hrefs */
        const xml: string = String(resp.result);
        this.webdavFiles = [];
        let pos: number = 0;
        while (true) {
          const start: number = xml.indexOf('<href>', pos);
          if (start < 0) break;
          const close: number = xml.indexOf('</href>', start);
          if (close < 0) break;
          let href: string = xml.substring(start + 6, close);
          /* Skip the root URL itself */
          if (href !== url && href !== '/' && !href.endsWith('/')) {
            const name: number = href.lastIndexOf('/');
            this.webdavFiles.push(href.substring(name + 1));
          }
          pos = close + 6;
        }
        this.webdavStatus = `Connected! ${this.webdavFiles.length} files found`;
      } else {
        this.webdavStatus = `Server responded: ${resp.responseCode}`;
      }
      req.destroy();
    } catch (e) {
      this.webdavStatus = 'Connection failed: ' + String(e);
    }
    this.webdavConnecting = false;
  }

  private async webdavDownload(fileName: string): Promise<void> {
    if (this.webdavUrl.length === 0 || fileName.length === 0) return;
    try {
      const url: string = this.webdavUrl.endsWith('/') ? this.webdavUrl + fileName : this.webdavUrl + '/' + fileName;
      let req = http.createHttp();
      let resp = await req.request(url, {
        method: http.RequestMethod.GET,
        connectTimeout: 10000,
        readTimeout: 30000
      });
      if (resp.responseCode === 200) {
        const data: ArrayBuffer = resp.result as ArrayBuffer;
        /* Save to sandbox cache */
        const ctx = getContext(this);
        const path: string = ctx.cacheDir + '/' + fileName;
        let f = fs.openSync(path, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
        fs.writeSync(f.fd, new Uint8Array(data).buffer);
        fs.closeSync(f);
        this.webdavStatus = `Downloaded: ${fileName} (${data.byteLength} bytes)`;
        /* Add to library */
        const book: RecentBook = {
          path: path,
          title: fileName,
          page: 0,
          totalPages: 0,
          lastRead: new Date().toISOString(),
          star: false,
          coverPath: '',
          author: '',
          tags: [],
          series: '',
          genre: '',
          status: 0
        };
        await saveRecentBook(ctx, book);
        await this.loadLibrary();
      } else {
        this.webdavStatus = `Download failed: ${resp.responseCode}`;
      }
      req.destroy();
    } catch (e) {
      this.webdavStatus = 'Download error: ' + String(e);
    }
  }

  // ---- Phase 5: OPDS 网上书库 ----

  /** Open an OPDS feed URL (pushed onto the nav stack). */
  private async opdsOpen(url: string): Promise<void> {
    if (url.length === 0) {
      return;
    }
    this.opdsLoading = true;
    this.opdsStatus = '\u52A0\u8F7D\u4E2D...';
    try {
      const entries: OpdsEntry[] = await fetchOpds(url, 15000);
      this.opdsEntries = entries;
      this.opdsStatus = entries.length > 0 ? '\u5171 ' + entries.length + ' \u9879' : '\u65E0\u5185\u5BB9\u6216\u52A0\u8F7D\u5931\u8D25';
      this.opdsStack.push(url);
      console.info(`[Index] OPDS opened: ${url} -> ${entries.length} entries`);
    } catch (e) {
      this.opdsStatus = '\u52A0\u8F7D\u5931\u8D25';
    }
    this.opdsLoading = false;
  }

  /** Back to the previous OPDS feed (or the preset home). */
  private opdsBack(): void {
    if (this.opdsStack.length <= 1) {
      this.opdsStack = [];
      this.opdsEntries = [];
      this.opdsStatus = '';
      return;
    }
    this.opdsStack.pop();
    const prev: string = this.opdsStack[this.opdsStack.length - 1];
    this.opdsLoading = true;
    this.opdsStatus = '\u52A0\u8F7D\u4E2D...';
    fetchOpds(prev, 15000).then((entries: OpdsEntry[]) => {
      this.opdsEntries = entries;
      this.opdsStatus = entries.length > 0 ? '\u5171 ' + entries.length + ' \u9879' : '\u65E0\u5185\u5BB9';
      this.opdsLoading = false;
    }).catch(() => {
      this.opdsStatus = '\u52A0\u8F7D\u5931\u8D25';
      this.opdsLoading = false;
    });
  }

  /** Tap an OPDS entry: sub-catalog navigates, book downloads. */
  private opdsOpenEntry(e: OpdsEntry): void {
    if (e.isSub) {
      this.opdsOpen(e.href);
    } else {
      this.opdsDownload(e);
    }
  }

  /** Download an OPDS acquisition entry into the library. */
  private async opdsDownload(e: OpdsEntry): Promise<void> {
    try {
      this.opdsStatus = '\u4E0B\u8F7D ' + e.title + ' ...';
      let req = http.createHttp();
      let resp = await req.request(e.href, {
        method: http.RequestMethod.GET,
        connectTimeout: 15000,
        readTimeout: 60000
      });
      if (resp.responseCode === 200) {
        const data: ArrayBuffer = resp.result as ArrayBuffer;
        /* derive a filename: last URL segment with extension, else title */
        let fileName: string = e.title;
        const lastSlash: number = e.href.lastIndexOf('/');
        let lastSeg: string = lastSlash >= 0 ? e.href.substring(lastSlash + 1) : e.href;
        if (lastSeg.length > 0 && lastSeg.indexOf('.') > 0) {
          fileName = lastSeg;
        } else {
          fileName = fileName.replace(/[\\/:*?"<>|]/g, '_') + '.epub';
        }
        const ctx = getContext(this);
        const path: string = ctx.cacheDir + '/' + fileName;
        let f = fs.openSync(path, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
        fs.writeSync(f.fd, new Uint8Array(data).buffer);
        fs.closeSync(f);
        const book: RecentBook = {
          path: path,
          title: fileName,
          page: 0,
          totalPages: 0,
          lastRead: new Date().toISOString(),
          star: false,
          coverPath: '',
          author: '',
          tags: [],
          series: '',
          genre: '',
          status: 0
        };
        await saveRecentBook(ctx, book);
        await this.loadLibrary();
        this.opdsStatus = '\u5DF2\u4E0B\u8F7D: ' + fileName;
        console.info(`[Index] OPDS downloaded: ${fileName} (${data.byteLength} bytes)`);
      } else {
        this.opdsStatus = '\u4E0B\u8F7D\u5931\u8D25: ' + resp.responseCode;
      }
      req.destroy();
    } catch (e) {
      this.opdsStatus = '\u4E0B\u8F7D\u9519\u8BEF';
    }
  }

  @Builder
  buildWebdavTab() {
    Column() {
      Text('☁ WebDAV Cloud Drive')
        .fontSize(16).fontWeight(FontWeight.Bold)
        .margin({ top: 12, bottom: 8 })

      TextInput({ placeholder: 'https://example.com/webdav/', text: this.webdavUrl })
        .width('90%').height(36)
        .onChange((v: string) => { this.webdavUrl = v; })

      Button(this.webdavConnecting ? 'Connecting...' : 'Connect')
        .width('90%').height(36).margin({ top: 8 })
        .onClick(() => { this.webdavConnect(); })

      Text(this.webdavStatus)
        .fontSize(12).fontColor('#666')
        .width('90%').margin({ top: 8 }).textAlign(TextAlign.Center)

      if (this.webdavFiles.length > 0) {
        List() {
          ForEach(this.webdavFiles, (file: string) => {
            ListItem() {
              Row() {
                Text('📄').fontSize(14).margin({ right: 8 })
                Text(file).fontSize(13).layoutWeight(1)
                Button('⬇')
                  .width(32).height(28).fontSize(12)
                  .onClick(() => { this.webdavDownload(file); })
              }
              .width('90%').padding({ top: 6, bottom: 6 })
            }
          }, (file: string) => file)
        }
        .width('100%')
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').alignItems(HorizontalAlign.Center)
  }

  /** Tab bar label with icon + text (Android: selected white / unselected #ddffffff). */
  @Builder
  buildTabLabel(icon: string, text: string, idx: number) {
    Column({ space: 2 }) {
      Text(icon).fontSize(18).fontColor(this.currentTab === idx ? '#FFFFFF' : '#DDFFFFFF')
      Text(text).fontSize(10).fontColor(this.currentTab === idx ? '#FFFFFF' : '#DDFFFFFF')
    }
    .width('100%')
    .alignItems(HorizontalAlign.Center)
  }

  // ---- Phase 1: shell helpers (top bar / drawer / dashboard / overlays) ----

  /** Switch bottom tab and refresh the top bar title (Android: title = tab name). */
  switchTab(index: number): void {
    this.currentTab = index;
    const names: string[] = ['\u9996\u9875', '\u4E66\u5E93', '\u6211\u7684\u6587\u4EF6', '\u504F\u597D'];
    this.topTitle = names[index];
    /* Phase 3: refresh stats whenever the dashboard becomes visible (also after Reader back) */
    if (index === 0) {
      this.loadStats();
    }
    console.info(`[Index] Tab switched to ${index} (${this.topTitle})`);
  }

  /** FAB: open the most recently read book. */
  openLastBook(): void {
    if (this.recentBooks.length === 0) {
      return;
    }
    this.openBook(this.recentBooks[0]);
  }

  /** Load the reading-quote pool from rawfile/reading_quotes.txt (Android assets). */
  loadQuotes(): void {
    if (this.quotePool.length > 0) {
      return;
    }
    try {
      const ctx = getContext(this);
      ctx.resourceManager.getRawFileContent('reading_quotes.txt').then((content: Uint8Array) => {
        const decoder: util.TextDecoder = util.TextDecoder.create('utf-8');
        const text: string = decoder.decodeToString(content);
        const lines: string[] = text.split('\n');
        const pool: string[] = [];
        for (let i = 0; i < lines.length; i++) {
          const line: string = lines[i].trim();
          if (line.length > 0) {
            pool.push(line);
          }
        }
        this.quotePool = pool;
        console.info(`[Index] Quotes loaded: ${pool.length}`);
      }).catch((e: Error) => {
        console.error('[Index] loadQuotes failed: ' + e.message);
      });
    } catch (e) {
      console.error('[Index] loadQuotes error');
    }
  }

  /** Pick a random quote for the drawer (refreshed every drawer open). */
  pickRandomQuote(): void {
    if (this.quotePool.length === 0) {
      this.drawerQuote = '';
      return;
    }
    const idx: number = Math.floor(Math.random() * this.quotePool.length);
    this.drawerQuote = this.quotePool[idx];
  }

  /** Drawer "night mode" toggle: switch light <-> dark theme. */
  toggleNightMode(): void {
    this.settings.theme = this.settings.theme === 0 ? 1 : 0;
    this.saveNow();
    console.info(`[Index] Night mode -> theme ${this.settings.theme}`);
  }

  /** Drawer "exit" button: terminate the ability. */
  exitApp(): void {
    try {
      const ctx = getContext(this) as common.UIAbilityContext;
      ctx.terminateSelf();
    } catch (e) {
      console.error('[Index] exitApp failed');
    }
  }

  /** Theme-aware drawer background. */
  drawerBg(): string {
    return this.settings.theme > 0 ? '#1e1e1e' : '#ffffff';
  }

  /** Theme-aware drawer foreground text. */
  drawerFg(): string {
    return this.settings.theme > 0 ? '#ddd' : '#333';
  }

  // ---- Phase 3: reading stats ----

  /** Load aggregated stats + chart series from the ReadingStats store. */
  async loadStats(): Promise<void> {
    try {
      const ctx = getContext(this);
      this.statsData = await getReadingStats(ctx);
      this.dailyStats = await getDailyStats(ctx, 30);
      this.monthlyStats = await getMonthlyStats(ctx, 12);
      this.updateChartData();
      console.info(`[Index] Stats loaded: total=${this.statsData.totalMs} today=${this.statsData.todayMs} pages=${this.statsData.todayPages}`);
    } catch (e) {
      console.error('[Index] loadStats failed');
    }
  }

  /** Recompute the chart series for the active 周/月/年 range. */
  updateChartData(): void {
    this.chartValues = this.getChartValues();
    this.chartLabels = this.getChartLabels();
  }

  /** Count of finished (status=2) books in the library. */
  countFinishedBooks(): number {
    let n = 0;
    for (let i = 0; i < this.recentBooks.length; i++) {
      if (this.recentBooks[i].status === 2) {
        n++;
      }
    }
    return n;
  }

  /** Format a duration compactly (e.g. 1h5m / 32m / 12s). */
  fmtDuration(ms: number): string {
    if (ms <= 0) {
      return '0m';
    }
    const h = Math.floor(ms / 3600000);
    const m = Math.floor((ms % 3600000) / 60000);
    const s = Math.floor((ms % 60000) / 1000);
    if (h > 0) {
      return h + 'h' + m + 'm';
    }
    if (m > 0) {
      return m + 'm';
    }
    return s + 's';
  }

  /** Max value of the active chart series (avoid div-by-zero). */
  chartMax(values: number[]): number {
    let m = 0;
    for (let i = 0; i < values.length; i++) {
      if (values[i] > m) {
        m = values[i];
      }
    }
    return m > 0 ? m : 1;
  }

  /** Active chart series values for 周/月/年. */
  getChartValues(): number[] {
    const out: number[] = [];
    if (this.chartRange === 0) {
      for (let i = Math.max(0, this.dailyStats.length - 7); i < this.dailyStats.length; i++) {
        out.push(this.dailyStats[i].ms);
      }
    } else if (this.chartRange === 1) {
      for (let i = 0; i < this.dailyStats.length; i++) {
        out.push(this.dailyStats[i].ms);
      }
    } else {
      for (let i = 0; i < this.monthlyStats.length; i++) {
        out.push(this.monthlyStats[i].ms);
      }
    }
    return out;
  }

  /** Active chart series labels for 周/月/年 (short). */
  getChartLabels(): string[] {
    const out: string[] = [];
    if (this.chartRange === 0) {
      for (let i = Math.max(0, this.dailyStats.length - 7); i < this.dailyStats.length; i++) {
        out.push(this.dailyStats[i].day.substring(5));
      }
    } else if (this.chartRange === 1) {
      for (let i = 0; i < this.dailyStats.length; i++) {
        out.push(this.dailyStats[i].day.substring(5));
      }
    } else {
      for (let i = 0; i < this.monthlyStats.length; i++) {
        out.push(this.monthlyStats[i].month.substring(5));
      }
    }
    return out;
  }

  /** Simple vertical bar chart (reads chartValues/chartLabels so it stays reactive). */
  @Builder
  buildBarChart() {
    Row({ space: 4 }) {
      ForEach(this.chartValues, (v: number, idx: number) => {
        Column({ space: 2 }) {
          Column() {}
            .width(16)
            .height(v > 0 ? Math.max(4, Math.round(v / this.chartMax(this.chartValues) * 88)) : 3)
            .backgroundColor('#3949AB')
            .borderRadius(2)
          Text(this.chartLabels[idx].length > 5 ? this.chartLabels[idx].substring(5) : this.chartLabels[idx])
            .fontSize(7).fontColor('#999')
        }
        .layoutWeight(1)
        .alignItems(HorizontalAlign.Center)
        .justifyContent(FlexAlign.End)
      }, (v: number, idx: number) => `bar_${idx}_${v}`)
    }
    .width('92%').height(118)
    .alignItems(VerticalAlign.Bottom)
  }

  /** Home tab: continue-reading card + recent carousel + favorites + quick grid. */
  @Builder
  buildDashboardTab() {
    Scroll() {
      Column({ space: 12 }) {
        /* continue reading the last book */
        if (this.recentBooks.length > 0) {
          Row() {
            if (this.recentBooks[0].coverPath.length > 0) {
              Image(this.recentBooks[0].coverPath)
                .width(52).height(70).borderRadius(4).objectFit(ImageFit.Cover).backgroundColor('#eee')
            } else {
              Column() {
                Text('\u{1F4C4}').fontSize(26)
              }
              .width(52).height(70)
              .justifyContent(FlexAlign.Center)
              .backgroundColor('#f0f0f0').borderRadius(4)
            }
            Column({ space: 3 }) {
              Text('\u7EE7\u7EED\u9605\u8BFB')
                .fontSize(11).fontColor('#888')
              Text(this.recentBooks[0].title.length > 18 ? this.recentBooks[0].title.substring(0, 18) + '\u2026' : this.recentBooks[0].title)
                .fontSize(15).fontWeight(FontWeight.Bold).fontColor('#333').maxLines(1)
              Text(this.recentBooks[0].totalPages > 0 ? '\u7B2C ' + (this.recentBooks[0].page + 1) + ' / ' + this.recentBooks[0].totalPages + ' \u9875' : '')
                .fontSize(11).fontColor('#888')
            }
            .layoutWeight(1)
            .alignItems(HorizontalAlign.Start)
            .margin({ left: 10 })
            Text('\u203A')
              .fontSize(26).fontColor('#3949AB')
          }
          .width('92%')
          .padding(10)
          .backgroundColor('#eef1fb')
          .borderRadius(10)
          .onClick(() => {
            this.openBook(this.recentBooks[0]);
          })
        }

        /* recent covers carousel */
        Text('\u6700\u8FD1\u9605\u8BFB')
          .fontSize(14).fontWeight(FontWeight.Bold).fontColor('#333').width('92%')
        Scroll() {
          Row({ space: 10 }) {
            ForEach(this.recentBooks, (book: RecentBook) => {
              this.coverThumb(book)
            }, (item: RecentBook) => item.path)
          }
        }
        .scrollable(ScrollDirection.Horizontal)
        .width('92%')

        /* favorites */
        if (this.starredBooks.length > 0) {
          Text('\u6211\u7684\u73CD\u85CF')
            .fontSize(14).fontWeight(FontWeight.Bold).fontColor('#333').width('92%')
          Scroll() {
            Row({ space: 10 }) {
              ForEach(this.starredBooks, (book: RecentBook) => {
                this.coverThumb(book)
              }, (item: RecentBook) => item.path)
            }
          }
          .scrollable(ScrollDirection.Horizontal)
          .width('92%')
        }

        /* quick grid: 我的文件 / 网上书库 / 书签笔记 / 偏好 */
        Row({ space: 10 }) {
          Column({ space: 4 }) {
            Text('\u{1F4C1}').fontSize(22)
            Text('\u6211\u7684\u6587\u4EF6').fontSize(11).fontColor('#333')
          }
          .layoutWeight(1).height(72)
          .justifyContent(FlexAlign.Center)
          .backgroundColor('#f5f5f5').borderRadius(8)
          .onClick(() => {
            this.switchTab(2);
          })

          Column({ space: 4 }) {
            Text('\u{1F310}').fontSize(22)
            Text('\u7F51\u4E0A\u4E66\u5E93').fontSize(11).fontColor('#333')
          }
          .layoutWeight(1).height(72)
          .justifyContent(FlexAlign.Center)
          .backgroundColor('#f5f5f5').borderRadius(8)
          .onClick(() => {
            this.activeOverlay = 2;
          })

          Column({ space: 4 }) {
            Text('\u{1F516}').fontSize(22)
            Text('\u4E66\u7B7E\u7B14\u8BB0').fontSize(11).fontColor('#333')
          }
          .layoutWeight(1).height(72)
          .justifyContent(FlexAlign.Center)
          .backgroundColor('#f5f5f5').borderRadius(8)
          .onClick(() => {
            this.activeOverlay = 1;
          })

          Column({ space: 4 }) {
            Text('\u2699').fontSize(22)
            Text('\u504F\u597D').fontSize(11).fontColor('#333')
          }
          .layoutWeight(1).height(72)
          .justifyContent(FlexAlign.Center)
          .backgroundColor('#f5f5f5').borderRadius(8)
          .onClick(() => {
            this.switchTab(3);
          })
        }
        .width('92%')

        /* 阅读统计: 5 cards (Android DashboardFragment2) */
        Text('\u9605\u8BFB\u7EDF\u8BA1')
          .fontSize(14).fontWeight(FontWeight.Bold).fontColor('#333').width('92%')
        Row({ space: 6 }) {
          Column({ space: 2 }) {
            Text('' + this.recentBooks.length)
              .fontSize(14).fontWeight(FontWeight.Bold).fontColor('#3949AB').maxLines(1)
            Text('\u603B\u6570').fontSize(9).fontColor('#888')
          }
          .layoutWeight(1).padding({ top: 8, bottom: 8 }).backgroundColor('#f0f3fb').borderRadius(6)

          Column({ space: 2 }) {
            Text('' + this.countFinishedBooks())
              .fontSize(14).fontWeight(FontWeight.Bold).fontColor('#3949AB').maxLines(1)
            Text('\u5DF2\u8BFB').fontSize(9).fontColor('#888')
          }
          .layoutWeight(1).padding({ top: 8, bottom: 8 }).backgroundColor('#f0f3fb').borderRadius(6)

          Column({ space: 2 }) {
            Text(this.fmtDuration(this.statsData.totalMs))
              .fontSize(14).fontWeight(FontWeight.Bold).fontColor('#3949AB').maxLines(1)
            Text('\u603B\u65F6\u957F').fontSize(9).fontColor('#888')
          }
          .layoutWeight(1).padding({ top: 8, bottom: 8 }).backgroundColor('#f0f3fb').borderRadius(6)

          Column({ space: 2 }) {
            Text(this.fmtDuration(this.statsData.todayMs))
              .fontSize(14).fontWeight(FontWeight.Bold).fontColor('#3949AB').maxLines(1)
            Text('\u4ECA\u65E5').fontSize(9).fontColor('#888')
          }
          .layoutWeight(1).padding({ top: 8, bottom: 8 }).backgroundColor('#f0f3fb').borderRadius(6)

          Column({ space: 2 }) {
            Text(this.statsData.speedPpm + '\u9875/\u5206')
              .fontSize(13).fontWeight(FontWeight.Bold).fontColor('#3949AB').maxLines(1)
            Text('\u901F\u5EA6').fontSize(9).fontColor('#888')
          }
          .layoutWeight(1).padding({ top: 8, bottom: 8 }).backgroundColor('#f0f3fb').borderRadius(6)
        }
        .width('92%')

        /* bar chart: 周 / 月 / 年 */
        Row({ space: 8 }) {
          ForEach(['\u5468', '\u6708', '\u5E74'], (label: string, idx: number) => {
            Button(label)
              .fontSize(11).height(26)
              .backgroundColor(this.chartRange === idx ? '#3949AB' : '#e0e0e0')
              .fontColor(this.chartRange === idx ? Color.White : '#333')
              .onClick(() => {
                this.chartRange = idx;
                this.updateChartData();
              })
          }, (label: string, idx: number) => `range_${idx}`)
        }
        .width('92%')
        this.buildBarChart()

        /* dev entry: raw test.pdf reader */
        Text('\u6D4B\u8BD5 PDF (Reader)')
          .fontSize(12).fontColor('#2a7')
          .onClick(() => {
            router.pushUrl({ url: 'components/Reader' })
              .then(() => {
                console.info('[Index] Navigated to Reader (test pdf)');
              })
              .catch((err: Error) => {
                console.error('[Index] Navigate failed: ' + JSON.stringify(err));
              });
          })
      }
      .width('100%')
      .padding({ top: 12, bottom: 20 })
    }
    .width('100%')
    .height('100%')
  }

  /** Cover thumbnail used in dashboard carousels. */
  @Builder
  coverThumb(book: RecentBook) {
    Column({ space: 4 }) {
      if (book.coverPath.length > 0) {
        Image(book.coverPath)
          .width(72).height(96).borderRadius(4).objectFit(ImageFit.Cover).backgroundColor('#eee')
      } else {
        Column() {
          Text('\u{1F4C4}').fontSize(30)
        }
        .width(72).height(96)
        .justifyContent(FlexAlign.Center)
        .backgroundColor('#f0f0f0').borderRadius(4)
      }
      Text(book.title.length > 8 ? book.title.substring(0, 8) + '\u2026' : book.title)
        .fontSize(10).fontColor('#555').maxLines(1)
    }
    .onClick(() => {
      this.openBook(book);
    })
  }

  /** Library tab (Android SearchFragment2): search + status chips + sort + 5 views + batch select. */
  @Builder
  buildLibraryTab() {
    Column() {
      /* search bar */
      Row({ space: 6 }) {
        Text('\u{1F50D}').fontSize(14).fontColor('#888')
        TextInput({ placeholder: '\u641C\u7D22\u4E66\u540D/\u4F5C\u8005/\u6807\u7B7E', text: this.libQuery })
          .fontSize(13).height(34).layoutWeight(1)
          .onChange((v: string) => {
            this.libQuery = v;
          })
        if (this.libQuery.length > 0) {
          Text('\u2716').fontSize(14).fontColor('#aaa').width(26).height(34).textAlign(TextAlign.Center)
            .onClick(() => {
              this.libQuery = '';
            })
        }
        if (this.selMode) {
          Button('\u53D6\u6D88').fontSize(12).height(30).backgroundColor('#e0e0e0').fontColor('#333')
            .onClick(() => {
              this.exitSel();
            })
        }
      }
      .width('94%').margin({ top: 6 })

      /* status chips: 全部 / 未读 / 在读 / 已读 */
      Scroll() {
        Row({ space: 8 }) {
          ForEach(['\u5168\u90E8', '\u672A\u8BFB', '\u5728\u8BFB', '\u5DF2\u8BFB'], (label: string, idx: number) => {
            Button(label)
              .fontSize(12).height(28)
              .backgroundColor(this.libStatusFilter === idx - 1 ? '#3949AB' : '#e0e0e0')
              .fontColor(this.libStatusFilter === idx - 1 ? Color.White : '#333')
              .onClick(() => {
                this.libStatusFilter = this.libStatusFilter === idx - 1 ? -1 : idx - 1;
              })
          }, (label: string, idx: number) => `chip_${idx}`)
        }
      }
      .scrollable(ScrollDirection.Horizontal)
      .width('94%').margin({ top: 6 })

      /* tool row: sort + view + star filter + count */
      Row({ space: 8 }) {
        Button('\u6392\u5E8F:' + this.sortName(this.libSortMode))
          .fontSize(11).height(28).backgroundColor('#e0e0e0').fontColor('#333')
          .onClick(() => {
            this.libSortMode = (this.libSortMode + 1) % 5;
          })
        Button('\u89C6\u56FE:' + this.viewName(this.libViewMode))
          .fontSize(11).height(28).backgroundColor('#e0e0e0').fontColor('#333')
          .onClick(() => {
            this.libViewMode = (this.libViewMode + 1) % 5;
          })
        Button(this.libStarFilter ? '\u2605\u6536\u85CF' : '\u5168\u90E8')
          .fontSize(11).height(28)
          .backgroundColor(this.libStarFilter ? '#3949AB' : '#e0e0e0')
          .fontColor(this.libStarFilter ? Color.White : '#333')
          .onClick(() => {
            this.libStarFilter = !this.libStarFilter;
            console.info(`[Index] Library star filter: ${this.libStarFilter}`);
          })
        Blank()
        Text('\u5171 ' + this.getLibraryBooks().length + ' \u672C')
          .fontSize(11).fontColor('#888')
      }
      .width('94%').margin({ top: 6 })

      /* batch selection bar */
      if (this.selMode) {
        Row({ space: 6 }) {
          Text(this.selSet.length + ' \u9879\u5DF2\u9009').fontSize(12).fontColor('#3949AB')
          Button('\u5168\u9009').fontSize(11).height(26).backgroundColor('#3949AB').fontColor(Color.White)
            .onClick(() => {
              this.selectAll();
            })
          Button('\u5DF2\u8BFB').fontSize(11).height(26).backgroundColor('#e0e0e0').fontColor('#333')
            .onClick(() => {
              this.batchMarkStatus(2);
            })
          Button('\u672A\u8BFB').fontSize(11).height(26).backgroundColor('#e0e0e0').fontColor('#333')
            .onClick(() => {
              this.batchMarkStatus(0);
            })
          Button('\u5728\u8BFB').fontSize(11).height(26).backgroundColor('#e0e0e0').fontColor('#333')
            .onClick(() => {
              this.batchMarkStatus(1);
            })
        }
        .width('94%').margin({ top: 6 })
      }

      /* content by view mode */
      if (this.getLibraryBooks().length === 0) {
        Text('\u8FD8\u6CA1\u6709\u9605\u8BFB\u8BB0\u5F55\uff0c\u70B9\u53F3\u4E0A\u89D2 \uff0b \u5BFC\u5165\u4E66\u7C4D')
          .fontSize(13).fontColor('#999').margin({ top: 60 })
      } else if (this.libViewMode <= 1) {
        /* list / compact */
        Scroll() {
          Column({ space: 0 }) {
            ForEach(this.getLibraryBooks(), (book: RecentBook) => {
              this.LibRow(book, this.libViewMode === 1)
              Divider().color('#f0f0f0')
            }, (item: RecentBook) => `${item.path}_${item.page}_${item.status}_${item.star}`)
          }
          .width('100%').padding({ left: 8, right: 8, top: 4 })
        }
        .layoutWeight(1).width('100%')
      } else {
        /* grid / cover / bookshelf (wood background) */
        Scroll() {
          Column() {
            Grid() {
              ForEach(this.getLibraryBooks(), (book: RecentBook) => {
                GridItem() {
                  this.LibGridCell(book)
                }
              }, (item: RecentBook) => `${item.path}_${item.page}_${item.status}_${item.star}`)
            }
            .columnsTemplate('1fr 1fr 1fr')
            .columnsGap(8).rowsGap(12)
            .width('94%')
            .padding({ top: 4 })
          }
          .width('100%')
          .padding({ top: 4, bottom: 16 })
        }
        .layoutWeight(1).width('100%')
        .backgroundColor(this.libViewMode === 4 ? '#7a4f28' : Color.Transparent)
      }
    }
    .width('100%')
    .height('100%')
  }

  /** Filtered + sorted library list (search / star / status + sort). */
  getLibraryBooks(): RecentBook[] {
    const list: RecentBook[] = [];
    const q: string = this.libQuery.trim().toLowerCase();
    for (let i = 0; i < this.recentBooks.length; i++) {
      const b = this.recentBooks[i];
      if (this.libStarFilter && !b.star) {
        continue;
      }
      if (this.libStatusFilter >= 0 && b.status !== this.libStatusFilter) {
        continue;
      }
      if (q.length > 0) {
        const hay: string = (b.title + ' ' + b.author + ' ' + b.series + ' ' + b.tags.join(' ')).toLowerCase();
        if (hay.indexOf(q) < 0) {
          continue;
        }
      }
      list.push(b);
    }
    if (this.libSortMode === 1) {
      list.sort((a: RecentBook, b2: RecentBook) => a.title.localeCompare(b2.title));
    } else if (this.libSortMode === 2) {
      list.sort((a: RecentBook, b2: RecentBook) => b2.lastRead.localeCompare(a.lastRead));
    } else if (this.libSortMode === 3) {
      list.sort((a: RecentBook, b2: RecentBook) => a.author.localeCompare(b2.author));
    } else if (this.libSortMode === 4) {
      list.sort((a: RecentBook, b2: RecentBook) => a.series.localeCompare(b2.series));
    }
    return list;
  }

  sortName(mode: number): string {
    return mode === 0 ? '\u6700\u8FD1' : mode === 1 ? '\u540D\u79F0' : mode === 2 ? '\u65E5\u671F' : mode === 3 ? '\u4F5C\u8005' : '\u7CFB\u5217';
  }

  viewName(mode: number): string {
    return mode === 0 ? '\u5217\u8868' : mode === 1 ? '\u7D27\u51D1' : mode === 2 ? '\u7F51\u683C' : mode === 3 ? '\u5C01\u9762' : '\u4E66\u67B6';
  }

  statusLabel(s: number): string {
    return s === 0 ? '\u672A\u8BFB' : s === 1 ? '\u5728\u8BFB' : '\u5DF2\u8BFB';
  }

  statusColor(s: number): string {
    return s === 0 ? '#999' : s === 1 ? '#2a7' : '#3949AB';
  }

  toggleSelect(path: string): void {
    const idx: number = this.selSet.indexOf(path);
    if (idx >= 0) {
      this.selSet.splice(idx, 1);
    } else {
      this.selSet.push(path);
    }
  }

  selectAll(): void {
    const books: RecentBook[] = this.getLibraryBooks();
    const all: string[] = [];
    for (let i = 0; i < books.length; i++) {
      all.push(books[i].path);
    }
    this.selSet = all;
  }

  exitSel(): void {
    this.selMode = false;
    this.selSet = [];
  }

  /** Batch-mark selected books with a reading status (0/1/2). */
  async batchMarkStatus(status: number): Promise<void> {
    if (this.selSet.length === 0) {
      return;
    }
    await setBooksStatus(getContext(this), this.selSet, status);
    await this.loadLibrary();
    this.exitSel();
  }

  /** Library list / compact row with selection + progress + star. */
  @Builder
  LibRow(book: RecentBook, compact: boolean) {
    Row() {
      if (this.selMode) {
        Text(this.selSet.indexOf(book.path) >= 0 ? '\u2611' : '\u2610')
          .fontSize(20).fontColor('#3949AB').width(28).textAlign(TextAlign.Center)
      }
      if (book.coverPath.length > 0) {
        Image(book.coverPath)
          .width(compact ? 40 : 52).height(compact ? 54 : 70)
          .objectFit(ImageFit.Cover).borderRadius(4).backgroundColor('#eee')
      } else {
        Column() {
          Text('\u{1F4C4}').fontSize(compact ? 20 : 26)
        }
        .width(compact ? 40 : 52).height(compact ? 54 : 70)
        .justifyContent(FlexAlign.Center).backgroundColor('#f0f0f0').borderRadius(4)
      }
      Column({ space: 2 }) {
        Text(book.title.length > 24 ? book.title.substring(0, 24) + '\u2026' : book.title)
          .fontSize(compact ? 12 : 13).fontColor('#333').maxLines(1)
        if (!compact && book.author.length > 0) {
          Text(book.author).fontSize(11).fontColor('#888').maxLines(1)
        }
        Row({ space: 8 }) {
          Text(this.statusLabel(book.status)).fontSize(10).fontColor(this.statusColor(book.status))
          Text(book.totalPages > 0 ? 'p' + (book.page + 1) + '/' + book.totalPages : '')
            .fontSize(10).fontColor(Color.Gray)
        }
        if (book.totalPages > 0) {
          Progress({ value: Math.min(book.page + 1, book.totalPages), total: book.totalPages, type: ProgressType.Linear })
            .width('100%').height(3).color('#3949AB').backgroundColor('#e0e0e0')
        }
      }
      .layoutWeight(1)
      .alignItems(HorizontalAlign.Start)
      .margin({ left: 8 })

      if (!this.selMode) {
        Text(book.star ? '\u2605' : '\u2606')
          .fontSize(22).fontColor(book.star ? '#f5a623' : '#bbb')
          .onClick(() => {
            this.onToggleStar(book);
          })
      }
    }
    .width('100%')
    .padding({ left: 8, right: 8, top: 6, bottom: 6 })
    .onClick(() => {
      if (this.selMode) {
        this.toggleSelect(book.path);
      } else {
        this.openBook(book);
      }
    })
    .gesture(LongPressGesture({ repeat: false, duration: 500 }).onAction(() => {
      if (!this.selMode) {
        this.selMode = true;
        this.toggleSelect(book.path);
      }
    }))
  }

  /** Library grid / cover / bookshelf cell. */
  @Builder
  LibGridCell(book: RecentBook) {
    Column({ space: 4 }) {
      if (book.coverPath.length > 0) {
        Image(book.coverPath)
          .width('100%').aspectRatio(0.72)
          .objectFit(ImageFit.Cover).borderRadius(4).backgroundColor('#eee')
      } else {
        Column() {
          Text('\u{1F4C4}').fontSize(34)
        }
        .width('100%').aspectRatio(0.72)
        .justifyContent(FlexAlign.Center).backgroundColor('#f0f0f0').borderRadius(4)
      }
      Text(book.title.length > 10 ? book.title.substring(0, 10) + '\u2026' : book.title)
        .fontSize(11).fontColor('#333').maxLines(1)
      Text(this.statusLabel(book.status))
        .fontSize(9).fontColor(this.statusColor(book.status))
    }
    .onClick(() => {
      if (this.selMode) {
        this.toggleSelect(book.path);
      } else {
        this.openBook(book);
      }
    })
    .gesture(LongPressGesture({ repeat: false, duration: 500 }).onAction(() => {
      if (!this.selMode) {
        this.selMode = true;
        this.toggleSelect(book.path);
      }
    }))
  }

  /** Left drawer panel (Android DrawerLayout: banner + nav + quote + bottom bar). */
  @Builder
  buildDrawerPanel() {
    Column() {
      /* banner with brand tagline */
      Column() {
        Text('\u503C\u5F97\u8BFB\uff0c\u597D\u597D\u8BFB')
          .fontSize(30).fontWeight(FontWeight.Bold).fontColor(Color.White)
          .textAlign(TextAlign.Center)
      }
      .width('100%').height(170)
      .justifyContent(FlexAlign.Center)
      .linearGradient({ angle: 135, colors: [['#3949AB', 0.0], ['#5C6BC0', 1.0]] })

      /* nav items: 最近阅读 / 书库 / 我的文件 / 网上书库 / 书签笔记 */
      Column({ space: 0 }) {
        this.drawerNavItem('\u23F3', '\u6700\u8FD1\u9605\u8BFB', 0)
        this.drawerNavItem('\u{1F4DA}', '\u4E66\u5E93', 1)
        this.drawerNavItem('\u{1F4C1}', '\u6211\u7684\u6587\u4EF6', 2)
        this.drawerNavItem('\u{1F310}', '\u7F51\u4E0A\u4E66\u5E93', 3)
        this.drawerNavItem('\u{1F516}', '\u4E66\u7B7E\u7B14\u8BB0', 4)
      }
      .width('100%').margin({ top: 6 })

      /* random reading quote, refreshed every drawer open */
      Text(this.drawerQuote)
        .fontSize(15)
        .fontColor(this.settings.theme > 0 ? '#bbb' : '#555')
        .lineHeight(22)
        .padding({ left: 12, right: 12, top: 8, bottom: 8 })
        .width('100%')
        .margin({ top: 4 })

      Blank()

      Divider().color(this.settings.theme > 0 ? '#333' : '#e0e0e0')

      /* bottom bar: 设置选项 / 软件说明 / 晚上模式 / 退出 */
      Row() {
        this.drawerBottomButton('\u2699', '\u8BBE\u7F6E\u9009\u9879', 0)
        this.drawerBottomButton('\u2139', '\u8F6F\u4EF6\u8BF4\u660E', 1)
        this.drawerBottomButton('\u263D', '\u665A\u4E0A\u6A21\u5F0F', 2)
        this.drawerBottomButton('\u2B05', '\u9000\u51FA', 3)
      }
      .width('100%')
    }
    .width(280)
    .height('100%')
    .backgroundColor(this.drawerBg())
  }

  /** One drawer nav row. target: 0/1=library, 2=my files, 3=network, 4=bookmarks. */
  @Builder
  drawerNavItem(icon: string, label: string, target: number) {
    Row({ space: 10 }) {
      Text(icon).fontSize(18)
      Text(label).fontSize(14).fontColor(this.drawerFg())
    }
    .width('100%').height(48)
    .padding({ left: 16 })
    .onClick(() => {
      this.showDrawer = false;
      if (target === 0 || target === 1) {
        this.switchTab(1);
      } else if (target === 2) {
        this.switchTab(2);
      } else if (target === 3) {
        this.activeOverlay = 2;
      } else if (target === 4) {
        this.activeOverlay = 1;
      }
      console.info(`[Index] Drawer nav: ${label}`);
    })
  }

  /** One drawer bottom-bar button. kind: 0=settings, 1=about, 2=night, 3=exit. */
  @Builder
  drawerBottomButton(icon: string, label: string, kind: number) {
    Column({ space: 2 }) {
      Text(icon).fontSize(16).fontColor(this.settings.theme > 0 ? '#ddd' : '#3949AB')
      Text(label).fontSize(10).fontColor(this.settings.theme > 0 ? '#999' : '#666')
    }
    .layoutWeight(1)
    .height(52)
    .justifyContent(FlexAlign.Center)
    .onClick(() => {
      if (kind === 0) {
        this.switchTab(3);
        this.showDrawer = false;
      } else if (kind === 1) {
        this.activeOverlay = 3;
        this.showDrawer = false;
      } else if (kind === 2) {
        this.toggleNightMode();
      } else if (kind === 3) {
        this.exitApp();
      }
    })
  }

  /** Modal overlay: bookmark manager (drawer 书签笔记). */
  @Builder
  buildBookmarkOverlay() {
    Column() {
      Row() {
        Text('\u4E66\u7B7E\u7B14\u8BB0')
          .fontSize(15).fontWeight(FontWeight.Bold).fontColor('#333').layoutWeight(1)
        Text('\u2716')
          .fontSize(18).fontColor('#888').width(36).height(36).textAlign(TextAlign.Center)
          .onClick(() => {
            this.activeOverlay = 0;
          })
      }
      .width('100%').padding({ left: 12, right: 6, top: 8 })
      Divider().color('#eee')
      Row({ space: 8 }) {
        TextInput({ placeholder: '\u641C\u7D22\u4E66\u7B7E...' })
          .fontSize(13).height(34).layoutWeight(1)
          .onChange((value: string) => {
            this.bookmarkFilter = value;
          })
      }
      .width('94%').margin({ top: 6 })
      Scroll() {
        Column({ space: 6 }) {
          if (this.getFilteredBookmarks().length === 0) {
            Text(this.allBookmarks.length === 0 ? '\u8FD8\u6CA1\u6709\u4E66\u7B7E\uff0c\u5728\u9605\u8BFB\u5668\u4E2D\u70B9 \u{1F516} \u6DFB\u52A0' : '\u65E0\u5339\u914D\u7ED3\u679C')
              .fontSize(13).fontColor('#999').margin({ top: 40 })
          } else {
            ForEach(this.getFilteredBookmarks(), (bm: Bookmark) => {
              Row() {
                Column({ space: 2 }) {
                  Text(bm.label.length > 0 ? bm.label : `\u7B2C ${bm.page + 1} \u9875`)
                    .fontSize(13).fontColor('#333').maxLines(1)
                  Text(bm.path.substring(bm.path.lastIndexOf('/') + 1))
                    .fontSize(11).fontColor('#888').maxLines(1)
                }
                .layoutWeight(1)
                .alignItems(HorizontalAlign.Start)

                Text('\u2716')
                  .fontSize(14).fontColor('#ccc').margin({ right: 8 })
                  .onClick(() => {
                    this.deleteBookmarkEntry(bm);
                  })
              }
              .width('100%')
              .padding({ left: 8, right: 8, top: 8, bottom: 8 })
              .backgroundColor('#fafafa')
              .borderRadius(6)
              .onClick(() => {
                this.openBookmarkBook(bm);
              })
            }, (bm: Bookmark) => `${bm.path}_${bm.page}`)
          }
        }
        .width('100%')
        .padding({ left: 8, right: 8, top: 4 })
      }
      .layoutWeight(1)
      .width('100%')
    }
    .width('92%').height('86%')
    .backgroundColor('#ffffff')
    .borderRadius(12)
    .align(Alignment.Center)
  }

  /** Modal overlay: network library (OPDS 网上书库 + WebDAV 云盘; OPDS in Phase 5). */
  @Builder
  buildNetworkOverlay() {
    Column() {
      Row() {
        Text('\u7F51\u4E0A\u4E66\u5E93')
          .fontSize(15).fontWeight(FontWeight.Bold).fontColor('#333').layoutWeight(1)
        Text('\u2716')
          .fontSize(18).fontColor('#888').width(36).height(36).textAlign(TextAlign.Center)
          .onClick(() => {
            this.activeOverlay = 0;
          })
      }
      .width('100%').padding({ left: 12, right: 6, top: 8 })
      Divider().color('#eee')

      /* section toggle: OPDS 目录 / WebDAV 云盘 */
      Row({ space: 8 }) {
        Button(this.netSection === 0 ? '\u2605 OPDS \u76EE\u5F55' : 'OPDS \u76EE\u5F55')
          .fontSize(12).height(30).layoutWeight(1)
          .backgroundColor(this.netSection === 0 ? '#3949AB' : '#e0e0e0')
          .fontColor(this.netSection === 0 ? Color.White : '#333')
          .onClick(() => {
            this.netSection = 0;
          })
        Button(this.netSection === 1 ? '\u2605 WebDAV' : 'WebDAV')
          .fontSize(12).height(30).layoutWeight(1)
          .backgroundColor(this.netSection === 1 ? '#3949AB' : '#e0e0e0')
          .fontColor(this.netSection === 1 ? Color.White : '#333')
          .onClick(() => {
            this.netSection = 1;
          })
      }
      .width('90%').margin({ top: 8 })

      if (this.netSection === 0) {
        this.buildOpdsPanel()
      } else {
        this.buildWebdavPanel()
      }
    }
    .width('92%').height('86%')
    .backgroundColor('#ffffff')
    .borderRadius(12)
    .align(Alignment.Center)
  }

  /** OPDS 目录 panel (built-in catalogs + drill-down feeds + download). */
  @Builder
  buildOpdsPanel() {
    Column() {
      if (this.opdsStack.length === 0) {
        /* preset home */
        Scroll() {
          Column({ space: 8 }) {
            ForEach(OPDS_PRESETS, (p: OpdsEntry) => {
              Row() {
                Text('\u{1F4D6}').fontSize(18)
                Column({ space: 2 }) {
                  Text(p.title).fontSize(13).fontColor('#333').maxLines(1)
                  Text(p.summary).fontSize(9).fontColor('#999').maxLines(1)
                }
                .layoutWeight(1).alignItems(HorizontalAlign.Start).margin({ left: 10 })
                Text('\u203A').fontSize(16).fontColor('#bbb')
              }
              .width('94%').padding(10).backgroundColor('#f5f5f5').borderRadius(8)
              .onClick(() => {
                this.opdsOpen(p.href);
              })
            }, (p: OpdsEntry) => p.href)
            Row({ space: 8 }) {
              TextInput({ placeholder: '\u81EA\u5B9A\u4E49 OPDS \u5730\u5740', text: this.opdsUrl })
                .fontSize(12).height(34).layoutWeight(1)
                .onChange((v: string) => {
                  this.opdsUrl = v;
                })
              Button('\u6253\u5F00')
                .fontSize(12).height(34).backgroundColor('#3949AB').fontColor(Color.White)
                .onClick(() => {
                  this.opdsOpen(this.opdsUrl);
                })
            }
            .width('94%')
          }
          .width('100%').padding({ top: 10 })
        }
        .layoutWeight(1).width('100%')
      } else {
        /* feed drill-down */
        Row({ space: 6 }) {
          Button('\u2190 \u8FD4\u56DE')
            .fontSize(12).height(30).backgroundColor('#e0e0e0').fontColor('#333')
            .onClick(() => {
              this.opdsBack();
            })
          Text(this.opdsStatus)
            .fontSize(11).fontColor('#888').layoutWeight(1).maxLines(1)
        }
        .width('94%').margin({ top: 8 })

        Scroll() {
          Column({ space: 6 }) {
            ForEach(this.opdsEntries, (e: OpdsEntry) => {
              Row() {
                Text(e.isSub ? '\u{1F4C1}' : '\u{1F4C4}').fontSize(16)
                Column({ space: 2 }) {
                  Text(e.title.length > 26 ? e.title.substring(0, 26) + '\u2026' : e.title)
                    .fontSize(13).fontColor('#333').maxLines(1)
                  if (e.summary.length > 0) {
                    Text(e.summary.length > 40 ? e.summary.substring(0, 40) + '\u2026' : e.summary)
                      .fontSize(9).fontColor('#999').maxLines(1)
                  }
                }
                .layoutWeight(1).alignItems(HorizontalAlign.Start).margin({ left: 8 })
                if (!e.isSub) {
                  Button('\u2B07').width(34).height(28).fontSize(12)
                    .onClick(() => {
                      this.opdsDownload(e);
                    })
                }
              }
              .width('94%').padding(8).backgroundColor('#fafafa').borderRadius(6)
              .onClick(() => {
                this.opdsOpenEntry(e);
              })
            }, (e: OpdsEntry) => e.href + '_' + e.title)
          }
          .width('100%').padding({ top: 6 })
        }
        .layoutWeight(1).width('100%')
      }
    }
    .width('100%').layoutWeight(1)
  }

  /** WebDAV 云盘 panel. */
  @Builder
  buildWebdavPanel() {
    Column() {
      Text('\u2601 WebDAV \u4E91\u76D8')
        .fontSize(13).fontWeight(FontWeight.Bold).fontColor('#333')
        .margin({ top: 10, bottom: 6 })

      TextInput({ placeholder: 'https://example.com/webdav/', text: this.webdavUrl })
        .width('90%').height(36)
        .onChange((v: string) => {
          this.webdavUrl = v;
        })

      Button(this.webdavConnecting ? 'Connecting...' : 'Connect')
        .width('90%').height(34).margin({ top: 8 })
        .backgroundColor('#3949AB').fontColor(Color.White)
        .onClick(() => {
          this.webdavConnect();
        })

      Text(this.webdavStatus)
        .fontSize(12).fontColor('#666')
        .width('90%').margin({ top: 6 }).textAlign(TextAlign.Center)

      if (this.webdavFiles.length > 0) {
        List() {
          ForEach(this.webdavFiles, (file: string) => {
            ListItem() {
              Row() {
                Text('\u{1F4C4}').fontSize(14).margin({ right: 8 })
                Text(file).fontSize(13).layoutWeight(1)
                Button('\u2B07')
                  .width(32).height(28).fontSize(12)
                  .onClick(() => {
                    this.webdavDownload(file);
                  })
              }
              .width('90%').padding({ top: 6, bottom: 6 })
            }
          }, (file: string) => file)
        }
        .width('100%')
        .layoutWeight(1)
      }
    }
    .width('100%').layoutWeight(1)
  }

  /** Modal overlay: about (软件说明). */
  @Builder
  buildAboutOverlay() {
    Column() {
      Row() {
        Text('\u8F6F\u4EF6\u8BF4\u660E')
          .fontSize(15).fontWeight(FontWeight.Bold).fontColor('#333').layoutWeight(1)
        Text('\u2716')
          .fontSize(18).fontColor('#888').width(36).height(36).textAlign(TextAlign.Center)
          .onClick(() => {
            this.activeOverlay = 0;
          })
      }
      .width('100%').padding({ left: 12, right: 6, top: 8 })
      Divider().color('#eee')
      Column({ space: 8 }) {
        Text('HowRead \u597D\u597D\u8BFB')
          .fontSize(20).fontWeight(FontWeight.Bold).fontColor('#333')
        Text('\u9E3F\u8499\u79FB\u690D\u7248 1.0.0')
          .fontSize(13).fontColor('#666')
        Text('\u57FA\u4E8E Android V1.0.0 \u79FB\u690D\u9002\u914D')
          .fontSize(12).fontColor('#999')
        Text('MuPDF 1.23.7 \u5F15\u64CE \u00B7 \u591A\u683C\u5F0F\u9605\u8BFB')
          .fontSize(12).fontColor('#999')
        Text('\u503C\u5F97\u8BFB\uff0c\u597D\u597D\u8BFB')
          .fontSize(15).fontColor('#3949AB').margin({ top: 10 })
      }
      .width('100%')
      .padding(20)
      .alignItems(HorizontalAlign.Center)
    }
    .width('86%')
    .backgroundColor('#ffffff')
    .borderRadius(12)
    .align(Alignment.Center)
  }

  /** Modal overlay: create a new folder in the current browse dir. */
  @Builder
  buildNewFolderOverlay() {
    Column() {
      Text('\u65B0\u5EFA\u6587\u4EF6\u5939')
        .fontSize(15).fontWeight(FontWeight.Bold).fontColor('#333')
        .margin({ top: 16, bottom: 12 })
      TextInput({ placeholder: '\u6587\u4EF6\u5939\u540D\u79F0', text: this.newFolderName })
        .fontSize(13).height(38).width('86%')
        .onChange((v: string) => {
          this.newFolderName = v;
        })
      Row({ space: 12 }) {
        Button('\u53D6\u6D88')
          .fontSize(13).height(36).layoutWeight(1).backgroundColor('#e0e0e0').fontColor('#333')
          .onClick(() => {
            this.showNewFolder = false;
            this.activeOverlay = 0;
          })
        Button('\u521B\u5EFA')
          .fontSize(13).height(36).layoutWeight(1).backgroundColor('#3949AB').fontColor(Color.White)
          .onClick(() => {
            this.confirmNewFolder();
            this.activeOverlay = 0;
          })
      }
      .width('86%').margin({ top: 14, bottom: 14 })
    }
    .width('80%')
    .backgroundColor('#ffffff')
    .borderRadius(12)
    .align(Alignment.Center)
  }

  /** Modal overlay: long-press file/dir operations (open / rename / delete / share). */
  @Builder
  buildFileOpsOverlay() {
    Column() {
      Text(this.opsFile)
        .fontSize(13).fontColor('#333').maxLines(1)
        .margin({ top: 16, bottom: 6 })
        .padding({ left: 12, right: 12 })
      Text(this.isBookFile(this.opsFile) ? '\u53EF\u9605\u8BFB\u6587\u4EF6' : '\u6587\u4EF6/\u76EE\u5F55')
        .fontSize(10).fontColor('#999').margin({ bottom: 8 })
      Divider().color('#eee')
      Row() {
        Text('\u{1F4C4} \u6253\u5F00').fontSize(14).fontColor('#333').layoutWeight(1).padding({ left: 12 })
        Text('\u203A').fontSize(16).fontColor('#ccc').margin({ right: 12 })
      }
      .width('100%').height(46)
      .onClick(() => {
        this.showFileOps = false;
        this.activeOverlay = 0;
        this.openBrowseEntry(this.opsFile);
      })
      Row() {
        Text('\u270F \u91CD\u547D\u540D').fontSize(14).fontColor('#333').layoutWeight(1).padding({ left: 12 })
        Text('\u203A').fontSize(16).fontColor('#ccc').margin({ right: 12 })
      }
      .width('100%').height(46)
      .onClick(() => {
        this.showRename = true;
        this.activeOverlay = 6;
      })
      Row() {
        Text('\u2716 \u5220\u9664').fontSize(14).fontColor('#d32f2f').layoutWeight(1).padding({ left: 12 })
        Text('\u203A').fontSize(16).fontColor('#ccc').margin({ right: 12 })
      }
      .width('100%').height(46)
      .onClick(() => {
        this.deleteEntry();
        this.activeOverlay = 0;
      })
      Divider().color('#eee')
      Button('\u5173\u95ED')
        .fontSize(13).height(40).width('90%').margin({ top: 8, bottom: 12 })
        .backgroundColor('#e0e0e0').fontColor('#333')
        .onClick(() => {
          this.showFileOps = false;
          this.activeOverlay = 0;
        })
    }
    .width('80%')
    .backgroundColor('#ffffff')
    .borderRadius(12)
    .align(Alignment.Center)
  }

  /** Modal overlay: rename the selected file/dir. */
  @Builder
  buildRenameOverlay() {
    Column() {
      Text('\u91CD\u547D\u540D')
        .fontSize(15).fontWeight(FontWeight.Bold).fontColor('#333')
        .margin({ top: 16, bottom: 12 })
      TextInput({ placeholder: '\u65B0\u540D\u79F0', text: this.renameInput })
        .fontSize(13).height(38).width('86%')
        .onChange((v: string) => {
          this.renameInput = v;
        })
      Row({ space: 12 }) {
        Button('\u53D6\u6D88')
          .fontSize(13).height(36).layoutWeight(1).backgroundColor('#e0e0e0').fontColor('#333')
          .onClick(() => {
            this.showRename = false;
            this.activeOverlay = 0;
          })
        Button('\u786E\u5B9A')
          .fontSize(13).height(36).layoutWeight(1).backgroundColor('#3949AB').fontColor(Color.White)
          .onClick(() => {
            this.confirmRename();
            this.activeOverlay = 0;
          })
      }
      .width('86%').margin({ top: 14, bottom: 14 })
    }
    .width('80%')
    .backgroundColor('#ffffff')
    .borderRadius(12)
    .align(Alignment.Center)
  }

  /** Full-screen app lock overlay (Phase 6): passcode gate. */
  @Builder
  buildLockOverlay() {
    Column() {
      Column() {
        Text('\u{1F512}').fontSize(40)
        Text('HowRead \u597D\u597D\u8BFB')
          .fontSize(18).fontWeight(FontWeight.Bold).fontColor(Color.White).margin({ top: 8 })
        Text('\u5E94\u7528\u5DF2\u9501\u5B9A\uff0c\u8BF7\u8F93\u5165\u5BC6\u7801')
          .fontSize(12).fontColor('#ddd').margin({ top: 4 })
      }
      .margin({ top: 40 })

      TextInput({ placeholder: '\u8BF7\u8F93\u5165\u5BC6\u7801', text: this.lockPin })
        .fontSize(14).height(42).width('70%')
        .type(InputType.NUMBER_PASSWORD)
        .onChange((v: string) => {
          this.lockPin = v;
        })
        .margin({ top: 30 })

      Button('\u89E3\u9501')
        .fontSize(14).height(40).width('70%').margin({ top: 16 })
        .backgroundColor('#3949AB').fontColor(Color.White)
        .onClick(() => {
          this.tryUnlock();
        })
    }
    .width('100%').height('100%')
    .backgroundColor('#30344d')
    .alignItems(HorizontalAlign.Center)
  }

  @Builder
  buildBookmarkTab() {
    Column({ space: 8 }) {
      /* Search bar */
      Row({ space: 8 }) {
        TextInput({ placeholder: '搜索书签...' })
          .fontSize(13)
          .height(34)
          .layoutWeight(1)
          .onChange((value: string) => {
            this.bookmarkFilter = value;
          })
      }
      .width('94%')

      /* Bookmark list */
      Scroll() {
        Column({ space: 6 }) {
          if (this.getFilteredBookmarks().length === 0) {
            Text(this.allBookmarks.length === 0 ? '还没有书签，在阅读器中点 🔖 添加' : '无匹配结果')
              .fontSize(13).fontColor('#999')
              .margin({ top: 40 })
          } else {
            ForEach(this.getFilteredBookmarks(), (bm: Bookmark) => {
              Row() {
                Column({ space: 2 }) {
                  Text(bm.label.length > 0 ? bm.label : `第 ${bm.page + 1} 页`)
                    .fontSize(13).fontColor('#333').maxLines(1)
                  Text(bm.path.substring(bm.path.lastIndexOf('/') + 1))
                    .fontSize(11).fontColor('#888').maxLines(1)
                }
                .layoutWeight(1)
                .alignItems(HorizontalAlign.Start)

                Text('✖')
                  .fontSize(14).fontColor('#ccc')
                  .margin({ right: 8 })
                  .onClick(() => {
                    this.deleteBookmarkEntry(bm);
                  })
              }
              .width('100%')
              .padding({ left: 8, right: 8, top: 8, bottom: 8 })
              .backgroundColor('#fafafa')
              .borderRadius(6)
              .onClick(() => {
                this.openBookmarkBook(bm);
              })
            }, (bm: Bookmark) => `${bm.path}_${bm.page}`)
          }
        }
        .width('100%')
        .padding({ left: 8, right: 8, top: 4 })
      }
      .layoutWeight(1)
      .width('100%')
    }
    .width('100%')
    .height('100%')
    .padding({ top: 8 })
  }

  /** My Files tab (Android BrowseFragment2): my-files: root (网络区 + 书库文件夹) or a folder browser. */
  @Builder
  buildBrowseTab() {
    Column({ space: 6 }) {
      if (this.browseCurrentDir.length === 0) {
        this.buildBrowseRoot()
      } else {
        /* folder browser: breadcrumb bar (home / up / path / view) */
        Row({ space: 6 }) {
          Button('\u2302').fontSize(14).width(40).height(32).backgroundColor('#3949AB').fontColor(Color.White)
            .onClick(() => {
              this.browseHome();
            })
          Button('\u2191').fontSize(14).width(40).height(32).backgroundColor('#e0e0e0').fontColor('#333')
            .onClick(() => {
              this.browseUp();
            })
          Text(this.browseCurrentDir.length > 34 ? '\u2026' + this.browseCurrentDir.substring(this.browseCurrentDir.length - 34) : this.browseCurrentDir)
            .fontSize(10).fontColor('#666').maxLines(1).layoutWeight(1)
          Button(this.browseViewMode === 0 ? '\u25A6 \u7F51\u683C' : '\u2261 \u5217\u8868')
            .fontSize(10).height(32).backgroundColor('#e0e0e0').fontColor('#333')
            .onClick(() => {
              this.browseViewMode = this.browseViewMode === 0 ? 1 : 0;
            })
        }
        .width('96%')

        Scroll() {
          Column({ space: 2 }) {
            ForEach(this.browseDirs, (name: string) => {
              Row() {
                Text('\u{1F4C1}').fontSize(16)
                Text(name).fontSize(13).fontColor('#333').margin({ left: 8 }).maxLines(1).layoutWeight(1)
                Text('\u203A').fontSize(16).fontColor('#bbb')
              }
              .width('100%').padding({ left: 8, right: 8, top: 6, bottom: 6 })
              .onClick(() => {
                this.enterBrowseDir(name);
              })
              .gesture(LongPressGesture({ repeat: false, duration: 500 }).onAction(() => {
                this.openOps(name);
              }))
              Divider().color('#f0f0f0')
            }, (name: string) => 'd_' + name)

            if (this.browseViewMode === 1) {
              /* list view */
              ForEach(this.browseFiles, (name: string) => {
                Row() {
                  Text('\u{1F4C4}').fontSize(16)
                  Text(name).fontSize(13).fontColor('#333').margin({ left: 8 }).maxLines(1).layoutWeight(1)
                }
                .width('100%').padding({ left: 8, right: 8, top: 6, bottom: 6 })
                .onClick(() => {
                  this.openBrowseEntry(name);
                })
                .gesture(LongPressGesture({ repeat: false, duration: 500 }).onAction(() => {
                  this.openOps(name);
                }))
                Divider().color('#f0f0f0')
              }, (name: string) => 'f_' + name)
            } else {
              /* grid view */
              Grid() {
                ForEach(this.browseFiles, (name: string) => {
                  GridItem() {
                    Column({ space: 4 }) {
                      Text('\u{1F4C4}').fontSize(32)
                      Text(name.length > 10 ? name.substring(0, 10) + '\u2026' : name)
                        .fontSize(10).fontColor('#555').maxLines(1)
                    }
                    .width('100%').padding(8).backgroundColor('#f5f5f5').borderRadius(6)
                    .onClick(() => {
                      this.openBrowseEntry(name);
                    })
                    .gesture(LongPressGesture({ repeat: false, duration: 500 }).onAction(() => {
                      this.openOps(name);
                    }))
                  }
                }, (name: string) => 'g_' + name)
              }
              .columnsTemplate('1fr 1fr 1fr').columnsGap(8).rowsGap(8)
              .width('96%').padding({ top: 4 })
            }

            if (this.browseDirs.length === 0 && this.browseFiles.length === 0) {
              Text('\u7A7A\u76EE\u5F55').fontSize(12).fontColor('#999').margin({ top: 40 })
            }
          }
          .width('100%')
        }
        .layoutWeight(1).width('100%')
      }

      /* bottom action row */
      Row({ space: 8 }) {
        Button('\u65B0\u5EFA\u6587\u4EF6\u5939')
          .fontSize(12).height(34).layoutWeight(1).backgroundColor('#3949AB').fontColor(Color.White)
          .onClick(() => {
            this.showNewFolder = true;
            this.activeOverlay = 4;
          })
        Button('\u5BFC\u5165\u4E66\u7C4D')
          .fontSize(12).height(34).layoutWeight(1).backgroundColor('#2a7').fontColor(Color.White)
          .onClick(() => {
            this.openFilePicker();
          })
      }
      .width('92%').padding({ bottom: 6 })
    }
    .width('100%')
    .height('100%')
    .padding({ top: 8 })
  }

  /** my-files: root — 网络区 (OPDS/WebDAV) + 书库文件夹 (Android BrowseFragment2). */
  @Builder
  buildBrowseRoot() {
    Scroll() {
      Column({ space: 8 }) {
        Text('\u7F51\u7EDC\u533A')
          .fontSize(13).fontWeight(FontWeight.Bold).fontColor('#333').width('92%')
        Row() {
          Text('\u{1F310}').fontSize(18)
          Text('\u7F51\u4E0A\u4E66\u5E93 (OPDS)').fontSize(13).fontColor('#333').margin({ left: 10 }).layoutWeight(1)
          Text('\u203A').fontSize(16).fontColor('#bbb')
        }
        .width('92%').padding(10).backgroundColor('#f5f5f5').borderRadius(8)
        .onClick(() => {
          this.activeOverlay = 2;
        })
        Row() {
          Text('\u2601').fontSize(18)
          Text('WebDAV \u670D\u52A1\u5668').fontSize(13).fontColor('#333').margin({ left: 10 }).layoutWeight(1)
          Text('\u203A').fontSize(16).fontColor('#bbb')
        }
        .width('92%').padding(10).backgroundColor('#f5f5f5').borderRadius(8)
        .onClick(() => {
          this.activeOverlay = 2;
        })

        Text('\u4E66\u5E93\u6587\u4EF6\u5939')
          .fontSize(13).fontWeight(FontWeight.Bold).fontColor('#333').width('92%')
        ForEach(this.libFolders, (f: LibFolder) => {
          Row() {
            Text('\u{1F4C2}').fontSize(18)
            Column({ space: 2 }) {
              Text(f.name).fontSize(13).fontColor('#333').maxLines(1)
              Text(f.path).fontSize(9).fontColor('#999').maxLines(1)
            }
            .layoutWeight(1).alignItems(HorizontalAlign.Start).margin({ left: 10 })
            Text('\u203A').fontSize(16).fontColor('#bbb')
          }
          .width('92%').padding(10).backgroundColor('#f0f3fb').borderRadius(8)
          .onClick(() => {
            this.enterBrowseAbs(f.path);
          })
        }, (f: LibFolder) => f.path)

        Text('\u5FEB\u6377\u76EE\u5F55')
          .fontSize(13).fontWeight(FontWeight.Bold).fontColor('#333').width('92%')
        Row() {
          Text('\u{1F4C2}').fontSize(18)
          Text('\u5DF2\u5BFC\u5165\u7684\u4E66 (\u4E66\u5E93)').fontSize(13).fontColor('#333').margin({ left: 10 }).layoutWeight(1)
          Text('\u203A').fontSize(16).fontColor('#bbb')
        }
        .width('92%').padding(10).backgroundColor('#f5f5f5').borderRadius(8)
        .onClick(() => {
          this.enterBrowseAbs(this.libFolders.length > 0 ? this.libFolders[0].path : '');
        })
      }
      .width('100%').padding({ top: 4, bottom: 16 })
    }
    .width('100%').layoutWeight(1)
  }

  // ---- Phase 4: browse helpers ----

  /** List dirs + files of the current browse dir into browseDirs/browseFiles. */
  private listBrowseDir(): void {
    const dir: string = this.browseCurrentDir;
    this.browseDirs = [];
    this.browseFiles = [];
    if (dir.length === 0) {
      return;
    }
    try {
      const entries: string[] = fs.listFileSync(dir);
      const dirs: string[] = [];
      const files: string[] = [];
      for (let i = 0; i < entries.length; i++) {
        const full: string = dir + '/' + entries[i];
        let isDir = false;
        try {
          isDir = fs.statSync(full).isDirectory();
        } catch (e) {
          isDir = false;
        }
        if (isDir) {
          dirs.push(entries[i]);
        } else {
          files.push(entries[i]);
        }
      }
      dirs.sort();
      files.sort();
      this.browseDirs = dirs;
      this.browseFiles = files;
    } catch (e) {
      console.error('[Index] listBrowseDir failed');
    }
  }

  /** Enter an absolute sandbox path. */
  private enterBrowseAbs(path: string): void {
    if (path.length === 0) {
      return;
    }
    this.browseCurrentDir = path;
    this.listBrowseDir();
  }

  /** Enter a subdirectory of the current browse dir. */
  private enterBrowseDir(name: string): void {
    this.browseCurrentDir = this.browseCurrentDir + '/' + name;
    this.listBrowseDir();
  }

  /** Go to the my-files: root. */
  private browseHome(): void {
    this.browseCurrentDir = '';
    this.browseDirs = [];
    this.browseFiles = [];
  }

  /** Go up one directory. */
  private browseUp(): void {
    const idx: number = this.browseCurrentDir.lastIndexOf('/');
    if (idx > 0) {
      this.browseCurrentDir = this.browseCurrentDir.substring(0, idx);
      this.listBrowseDir();
    } else {
      this.browseHome();
    }
  }

  /** True if a file name has a readable book extension. */
  private isBookFile(name: string): boolean {
    const lower: string = name.toLowerCase();
    const exts: string[] = ['.pdf', '.epub', '.txt', '.html', '.cbz', '.mobi', '.xps', '.tif', '.tiff'];
    for (let i = 0; i < exts.length; i++) {
      if (lower.endsWith(exts[i])) {
        return true;
      }
    }
    return false;
  }

  /** Tap a file: open if book, otherwise show the ops dialog. */
  private openBrowseEntry(name: string): void {
    if (this.isBookFile(name)) {
      this.openBrowseFile(name);
    } else {
      this.openOps(name);
    }
  }

  /** Long-press: show the file/dir ops dialog. */
  private openOps(name: string): void {
    this.opsFile = name;
    this.renameInput = name;
    this.showFileOps = true;
    this.activeOverlay = 5;
  }

  /** Create a new folder in the current browse dir. */
  private confirmNewFolder(): void {
    const name: string = this.newFolderName.trim();
    if (name.length === 0) {
      return;
    }
    try {
      fs.mkdirSync(this.browseCurrentDir + '/' + name);
    } catch (e) {
      console.error('[Index] mkdir failed');
    }
    this.newFolderName = '';
    this.showNewFolder = false;
    this.listBrowseDir();
  }

  /** Delete the selected file/dir. */
  private deleteEntry(): void {
    const full: string = this.browseCurrentDir + '/' + this.opsFile;
    try {
      const st = fs.statSync(full);
      if (st.isDirectory()) {
        fs.rmdirSync(full);
      } else {
        fs.unlinkSync(full);
      }
      console.info(`[Index] Deleted ${this.opsFile}`);
    } catch (e) {
      console.error('[Index] deleteEntry failed');
    }
    this.showFileOps = false;
    this.listBrowseDir();
  }

  /** Rename the selected file/dir. */
  private confirmRename(): void {
    const newName: string = this.renameInput.trim();
    if (newName.length === 0) {
      return;
    }
    try {
      fs.renameSync(this.browseCurrentDir + '/' + this.opsFile, this.browseCurrentDir + '/' + newName);
      console.info(`[Index] Renamed ${this.opsFile} -> ${newName}`);
    } catch (e) {
      console.error('[Index] rename failed');
    }
    this.showRename = false;
    this.showFileOps = false;
    this.listBrowseDir();
  }

  @Builder
  buildSettingsTab() {
    Scroll() {
      Column({ space: 8 }) {
        /* ===== Reader settings (sprint E) ===== */
        Text('阅读设置')
          .fontSize(15).fontWeight(FontWeight.Bold)
          .margin({ top: 4 })

        // Theme
        Row({ space: 8 }) {
          Text('主题:')
            .fontSize(13).fontColor('#555')
          ForEach(THEME_NAMES, (name: string, idx: number) => {
            Button(name)
              .fontSize(12).height(32)
              .backgroundColor(this.settings.theme === idx ? '#2a7' : '#e0e0e0')
              .fontColor(this.settings.theme === idx ? Color.White : '#333')
              .onClick(() => {
                this.settings.theme = idx;
                this.saveNow();
              })
          }, (name: string, idx: number) => `theme_${idx}`)
        }

        // Default zoom
        Row({ space: 8 }) {
          Text('默认缩放:')
            .fontSize(13).fontColor('#555')
          Slider({ value: this.settings.zoom, min: 0.5, max: 4, step: 0.25 })
            .layoutWeight(1)
            .onChange((value: number, mode: SliderChangeMode) => {
              this.settings.zoom = value;
              if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
                this.saveNow();
              }
            })
          Text(`${Math.round(this.settings.zoom * 100)}%`)
            .fontSize(11).fontColor('#777').width(40)
        }

        // Scroll mode & page mode
        Row({ space: 8 }) {
          Text('翻页:')
            .fontSize(13).fontColor('#555')
          Button(this.settings.scrollMode === 0 ? '水平翻页' : '垂直滚动')
            .fontSize(12).height(32)
            .backgroundColor('#e0e0e0').fontColor('#333')
            .onClick(() => {
              this.settings.scrollMode = this.settings.scrollMode === 0 ? 1 : 0;
              this.saveNow();
            })
          Button(this.settings.pageMode === 0 ? '单页' : (this.settings.pageMode === 1 ? '双页' : '乐谱'))
            .fontSize(12).height(32)
            .backgroundColor('#e0e0e0').fontColor('#333')
            .onClick(() => {
              this.settings.pageMode = (this.settings.pageMode + 1) % 3;
              this.saveNow();
            })
        }

        // Brightness
        Row({ space: 8 }) {
          Text('亮度:')
            .fontSize(13).fontColor('#555')
          Slider({ value: this.settings.brightness, min: 1, max: 255, step: 4 })
            .layoutWeight(1)
            .onChange((value: number, mode: SliderChangeMode) => {
              this.settings.brightness = value;
              if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
                this.saveNow();
              }
            })
          Text(`${Math.round(this.settings.brightness / 2.55)}%`)
            .fontSize(11).fontColor('#777').width(36)
        }

        // Auto flip
        Row({ space: 8 }) {
          Text('自动翻页:')
            .fontSize(13).fontColor('#555')
          Button(this.settings.autoFlip ? '开' : '关')
            .fontSize(12).height(32)
            .backgroundColor(this.settings.autoFlip ? '#2a7' : '#e0e0e0')
            .fontColor(this.settings.autoFlip ? Color.White : '#333')
            .onClick(() => {
              this.settings.autoFlip = !this.settings.autoFlip;
              this.saveNow();
            })
          Text(`${this.settings.autoFlipInterval}s`)
            .fontSize(11).fontColor('#777')
            .onClick(() => {
              this.settings.autoFlipInterval = this.settings.autoFlipInterval >= 5 ? 1 : this.settings.autoFlipInterval + 1;
              this.saveNow();
            })
        }

        // TTS rate / pitch / RSVP speed
        Row({ space: 8 }) {
          Text('TTS语速:')
            .fontSize(13).fontColor('#555')
          Slider({ value: this.settings.ttsRate, min: 0.5, max: 2.0, step: 0.1 })
            .layoutWeight(1)
            .onChange((value: number, mode: SliderChangeMode) => {
              this.settings.ttsRate = value;
              if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
                this.saveNow();
              }
            })
          Text(`${this.settings.ttsRate.toFixed(1)}x`)
            .fontSize(11).fontColor('#777').width(36)
        }

        Row({ space: 8 }) {
          Text('TTS音调:')
            .fontSize(13).fontColor('#555')
          Slider({ value: this.settings.ttsPitch, min: 0.5, max: 2.0, step: 0.1 })
            .layoutWeight(1)
            .onChange((value: number, mode: SliderChangeMode) => {
              this.settings.ttsPitch = value;
              if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
                this.saveNow();
              }
            })
          Text(`${this.settings.ttsPitch.toFixed(1)}x`)
            .fontSize(11).fontColor('#777').width(36)
        }

        Row({ space: 8 }) {
          Text('速读:')
            .fontSize(13).fontColor('#555')
          Slider({ value: this.settings.rsvpWpm, min: 100, max: 800, step: 50 })
            .layoutWeight(1)
            .onChange((value: number, mode: SliderChangeMode) => {
              this.settings.rsvpWpm = value;
              if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
                this.saveNow();
              }
            })
          Text(`${this.settings.rsvpWpm}`)
            .fontSize(11).fontColor('#777').width(36)
        }

        // Reflowable typography
        Text('重排文档（EPUB/HTML）排版:')
          .fontSize(13).fontColor('#555')
          .margin({ top: 4 })

        Row({ space: 8 }) {
          Text('字号:')
            .fontSize(13).fontColor('#555')
          Slider({ value: this.settings.fontSize, min: 10, max: 28, step: 1 })
            .layoutWeight(1)
            .onChange((value: number, mode: SliderChangeMode) => {
              this.settings.fontSize = value;
              if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
                this.saveNow();
              }
            })
          Text(`${this.settings.fontSize}`)
            .fontSize(11).fontColor('#777').width(30)
        }

        Row({ space: 8 }) {
          Text('行距:')
            .fontSize(13).fontColor('#555')
          Slider({ value: this.settings.lineHeight, min: 1.0, max: 2.5, step: 0.1 })
            .layoutWeight(1)
            .onChange((value: number, mode: SliderChangeMode) => {
              this.settings.lineHeight = value;
              if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
                this.saveNow();
              }
            })
          Text(`${this.settings.lineHeight.toFixed(1)}`)
            .fontSize(11).fontColor('#777').width(30)
        }

        Row({ space: 8 }) {
          Text('页边距:')
            .fontSize(13).fontColor('#555')
          Slider({ value: this.settings.margin, min: 0, max: 80, step: 4 })
            .layoutWeight(1)
            .onChange((value: number, mode: SliderChangeMode) => {
              this.settings.margin = value;
              if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) {
                this.saveNow();
              }
            })
          Text(`${this.settings.margin}pt`)
            .fontSize(11).fontColor('#777').width(36)
        }

        // ---- Sprint K: System integration settings ----
        Text('\u7CFB\u7EDF\u96C6\u6210')
          .fontSize(15).fontWeight(FontWeight.Bold)
          .margin({ top: 8 })

        /* K2: App lock */
        Row({ space: 8 }) {
          Text('\u5E94\u7528\u9501:')
            .fontSize(13).fontColor('#555')
          Button(this.settings.appLockEnabled ? '\u5F00' : '\u5173')
            .fontSize(12).height(32)
            .backgroundColor(this.settings.appLockEnabled ? '#e57373' : '#e0e0e0')
            .fontColor(this.settings.appLockEnabled ? Color.White : '#333')
            .onClick(() => {
              this.settings.appLockEnabled = !this.settings.appLockEnabled;
              this.saveNow();
              console.info(`[Index] App lock: ${this.settings.appLockEnabled}`);
            })
          Text('(\u6307\u7EB9/\u5BC6\u7801)')
            .fontSize(10).fontColor('#999')
        }

        /* Phase 6: app lock passcode */
        Row({ space: 8 }) {
          Text('\u9501\u5C4F\u5BC6\u7801:')
            .fontSize(13).fontColor('#555')
          TextInput({ placeholder: '\u9ED8\u8BA4 1234', text: this.settings.appLockPass })
            .fontSize(12).height(32).layoutWeight(1)
            .onChange((v: string) => {
              this.settings.appLockPass = v;
              this.saveNow();
            })
        }

        /* Phase 6: reading reminder */
        Row({ space: 8 }) {
          Text('\u9605\u8BFB\u63D0\u9192:')
            .fontSize(13).fontColor('#555')
          Button(this.settings.readReminderMinutes === 0 ? '\u5173' : this.settings.readReminderMinutes + ' \u5206\u949F')
            .fontSize(12).height(32)
            .backgroundColor(this.settings.readReminderMinutes > 0 ? '#2a7' : '#e0e0e0')
            .fontColor(this.settings.readReminderMinutes > 0 ? Color.White : '#333')
            .onClick(() => {
              this.settings.readReminderMinutes = this.settings.readReminderMinutes >= 120 ? 0 : (this.settings.readReminderMinutes === 0 ? 30 : this.settings.readReminderMinutes + 30);
              this.saveNow();
            })
          Text('(\u8FDE\u7EED\u9605\u8BFB\u63D0\u9192)')
            .fontSize(10).fontColor('#999')
        }

        /* K3: Proxy settings */
        Row({ space: 8 }) {
          Text('\u4EE3\u7406:')
            .fontSize(13).fontColor('#555')
          Button(this.settings.proxyType === 0 ? '\u5173' : (this.settings.proxyType === 1 ? 'HTTP' : 'SOCKS'))
            .fontSize(12).height(32)
            .backgroundColor(this.settings.proxyType > 0 ? '#2a7' : '#e0e0e0')
            .fontColor(this.settings.proxyType > 0 ? Color.White : '#333')
            .onClick(() => {
              this.settings.proxyType = (this.settings.proxyType + 1) % 3;
              this.saveNow();
              console.info(`[Index] Proxy type: ${this.settings.proxyType}`);
            })
          if (this.settings.proxyType > 0) {
            Text(`${this.settings.proxyHost}:${this.settings.proxyPort}`)
              .fontSize(11).fontColor('#777')
          }
        }

        /* K4: Render quality */
        Row({ space: 8 }) {
          Text('\u6E32\u67D3\u8D28\u91CF:')
            .fontSize(13).fontColor('#555')
          Button(this.settings.renderQuality === 0 ? '\u5FEB\u901F' : (this.settings.renderQuality === 1 ? '\u5E73\u8861' : '\u9AD8\u6E05'))
            .fontSize(12).height(32)
            .backgroundColor('#e0e0e0').fontColor('#333')
            .onClick(() => {
              this.settings.renderQuality = (this.settings.renderQuality + 1) % 3;
              this.saveNow();
              console.info(`[Index] Render quality: ${this.settings.renderQuality}`);
            })
        }

        /* K6: Page flip animation */
        Row({ space: 8 }) {
          Text('\u7FFB\u9875\u52A8\u753B:')
            .fontSize(13).fontColor('#555')
          Button(this.settings.flipAnimation === 0 ? '\u6ED1\u52A8' : (this.settings.flipAnimation === 1 ? '\u7FFB\u8F6C' : '\u6DE1\u5165'))
            .fontSize(12).height(32)
            .backgroundColor('#e0e0e0').fontColor('#333')
            .onClick(() => {
              this.settings.flipAnimation = (this.settings.flipAnimation + 1) % 3;
              this.saveNow();
              console.info(`[Index] Flip animation: ${this.settings.flipAnimation}`);
            })
        }

        Divider().color('#ddd')

        // Backup / restore
        Row({ space: 8 }) {
          Button('导出备份')
            .fontSize(13).height(34).layoutWeight(1)
            .backgroundColor('#2a7').fontColor(Color.White)
            .onClick(() => this.doBackup())
          Button('恢复备份')
            .fontSize(13).height(34).layoutWeight(1)
            .backgroundColor('#ff9800').fontColor(Color.White)
            .onClick(() => this.doRestore())
        }

        if (this.backupText.length > 0) {
          Text(this.backupText)
            .fontSize(11).fontColor('#888').textAlign(TextAlign.Center).width('100%')
        }

        Divider().color('#ddd')

        /* ===== NAPI self-test (kept for diagnostics) ===== */
        Text(this.status)
          .fontSize(12)
          .fontColor(Color.Gray)
          .textAlign(TextAlign.Center)
          .width('94%')

        Text(this.pageInfo)
          .fontSize(12)
          .fontColor(Color.Blue)

        if (this.pixelMap) {
          Image(this.pixelMap)
            .objectFit(ImageFit.Contain)
            .width('70%')
            .borderRadius(6)
        }

        Column() {
          Text(this.textResult)
            .fontSize(10)
            .fontColor('#666')
            .maxLines(3)
            .textOverflow({ overflow: TextOverflow.Clip })
            .width('92%')
        }
        .padding(8)
        .backgroundColor(Color.White)
        .borderRadius(8)
        .width('94%')

        Text(this.docInfo)
          .fontSize(10)
          .fontColor(Color.Green)

        Column({ space: 4 }) {
          Text(this.tocText).fontSize(11).fontColor('#555')
          Text(this.pageSizeText).fontSize(11).fontColor('#555')
          Text(this.asyncRenderText).fontSize(11).fontColor('#2a7')
          Text(this.rotatedText).fontSize(11).fontColor('#2a7')
          Text(this.invertedText).fontSize(11).fontColor('#2a7')
          Text(this.epubText).fontSize(11).fontColor('#b60')
          Text(this.cropText).fontSize(11).fontColor('#2a7')
          Text(this.layoutText).fontSize(11).fontColor('#2a7')
          Text(this.fmtText).fontSize(11).fontColor('#b60')
        }
        .width('94%')
        .padding(8)
        .backgroundColor('#f0f4ff')
        .borderRadius(8)
      }
      .width('100%')
      .padding({ top: 8, bottom: 16 })
    }
    .width('100%')
    .height('100%')
  }

  // ---- NAPI self-test (runs once on launch, results shown in Settings tab) ----
  private async runApiTest(): Promise<void> {
    let handle: ESObject = undefined;
    try {
      const ctx = getContext(this);
      const target = ctx.cacheDir + '/test.pdf';

      const content: Uint8Array = await ctx.resourceManager.getRawFileContent('test.pdf');
      const file = fs.openSync(target, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      fs.writeSync(file.fd, content.buffer);
      fs.closeSync(file);

      const mupdfVersion: string = version();
      handle = openDocument(target);
      const pages: number = pageCount(handle);

      // 1. Sync render page 0
      const res: RenderResult = renderPage(handle, 0, 2.0);
      this.currentPage = 0;
      const imgOpts: image.InitializationOptions = {
        size: { width: res.width, height: res.height },
        pixelFormat: image.PixelMapFormat.RGBA_8888
      };
      this.pixelMap = await image.createPixelMap(res.data, imgOpts);
      console.info(`[Index] sync render OK: ${res.width}x${res.height}`);

      // 2. getText
      try {
        const text: string = getText(handle, 0, 1.0);
        this.textResult = text.length > 500 ? text.substring(0, 500) + '...' : text;
        console.info(`[Index] getText OK: ${text.length} chars`);
      } catch (e) {
        this.textResult = 'text: (N/A)';
        console.error('[Index] getText FAILED');
      }

      // 3. getDocumentInfo
      try {
        const info: DocumentInfo = getDocumentInfo(handle);
        const parts: string[] = [];
        if (info.title) parts.push(`title=${info.title}`);
        if (info.author) parts.push(`author=${info.author}`);
        if (info.creator) parts.push(`creator=${info.creator}`);
        if (info.producer) parts.push(`producer=${info.producer}`);
        this.docInfo = parts.length > 0 ? parts.join(' | ') : 'no metadata';
        console.info(`[Index] getDocumentInfo OK`);
      } catch (e) {
        this.docInfo = 'no metadata';
        console.error('[Index] getDocumentInfo FAILED');
      }

      // 4. searchText
      try {
        const rects: TextRect[] = searchText(handle, 'PDF', 0);
        if (rects.length > 0) {
          this.pageInfo = `found ${rects.length} "PDF" on page 1`;
        } else {
          this.pageInfo = `page 1 | ${rects.length} matches`;
        }
        console.info(`[Index] searchText OK: ${rects.length} hits`);
      } catch (e) {
        this.pageInfo = 'page 1 | search N/A';
        console.error('[Index] searchText FAILED');
      }

      // 5. getToc
      try {
        const toc: TocEntry[] = getToc(handle);
        if (toc.length === 0) {
          this.tocText = 'TOC: (empty)';
          console.info('[Index] getToc OK: 0 entries');
        } else {
          const lines: string[] = [];
          for (let i = 0; i < toc.length && i < 10; i++) {
            lines.push(`  ${' '.repeat(toc[i].depth * 2)}${toc[i].title} [p${toc[i].page}]`);
          }
          this.tocText = `TOC (${toc.length}): ${lines.join(' | ')}`;
          console.info(`[Index] getToc OK: ${toc.length} entries`);
        }
      } catch (e) {
        this.tocText = 'TOC: error';
        console.error('[Index] getToc FAILED');
      }

      // 6. getPageSize
      try {
        const sz: TextRect = getPageSize(handle, 0);
        const w = ((sz.x1 - sz.x0) / 1000).toFixed(1);
        const h = ((sz.y1 - sz.y0) / 1000).toFixed(1);
        this.pageSizeText = `Page size: ${w} x ${h} pt`;
        console.info(`[Index] getPageSize OK: ${w}x${h}`);
      } catch (e) {
        this.pageSizeText = 'Page size: error';
        console.error('[Index] getPageSize FAILED');
      }

      // 7. renderPageAsync
      try {
        const aOpts: RenderOptions = { zoom: 1.5 };
        const aRes: RenderResult = await renderPageAsync(handle, 0, aOpts);
        this.asyncRenderText = `async render OK: ${aRes.width}x${aRes.height}`;
        console.info(`[Index] renderPageAsync OK: ${aRes.width}x${aRes.height}`);
      } catch (e) {
        this.asyncRenderText = 'async render: error';
        console.error('[Index] renderPageAsync FAILED');
      }

      // 8. renderPageAsync with rotation 90
      try {
        const rOpts: RenderOptions = { zoom: 1.0, rotationDeg: 90 };
        const rRes: RenderResult = await renderPageAsync(handle, 0, rOpts);
        this.rotatedText = `rotated 90 OK: ${rRes.width}x${rRes.height}`;
        console.info(`[Index] renderPageAsync rot=90 OK: ${rRes.width}x${rRes.height}`);
      } catch (e) {
        this.rotatedText = 'rotated 90: error';
        console.error('[Index] renderPageAsync rot=90 FAILED');
      }

      // 9. renderPageAsync with invert
      try {
        const iOpts: RenderOptions = { zoom: 1.0, invert: true };
        const iRes: RenderResult = await renderPageAsync(handle, 0, iOpts);
        this.invertedText = `inverted OK: ${iRes.width}x${iRes.height}`;
        console.info(`[Index] renderPageAsync invert OK: ${iRes.width}x${iRes.height}`);
      } catch (e) {
        this.invertedText = 'inverted: error';
        console.error('[Index] renderPageAsync invert FAILED');
      }

      // 10. renderPageAsync with crop
      try {
        const cropRect: CropRect = { x0: 0.1, y0: 0.1, x1: 0.9, y1: 0.9 };
        const cOpts: RenderOptions = { zoom: 1.0, crop: cropRect };
        const cRes: RenderResult = await renderPageAsync(handle, 0, cOpts);
        this.cropText = `crop 10% OK: ${cRes.width}x${cRes.height}`;
        console.info(`[Index] renderPageAsync crop OK: ${cRes.width}x${cRes.height}`);
      } catch (e) {
        this.cropText = 'crop: error';
        console.error('[Index] renderPageAsync crop FAILED');
      }

      // 11. layoutDocument
      try {
        layoutDocument(handle, 595, 842, 12);
        this.layoutText = 'layout OK (reflow target 595x842 em=12)';
        console.info('[Index] layoutDocument OK (no-op on PDF)');
      } catch (e) {
        this.layoutText = 'layout: error';
        console.error('[Index] layoutDocument FAILED');
      }

      // 12. closeDocument
      try {
        closeDocument(handle);
        console.info('[Index] closeDocument OK');
        handle = undefined;
      } catch (e) {
        console.error('[Index] closeDocument FAILED');
      }

      // 13. EPUB open + render
      try {
        const epubTarget = ctx.cacheDir + '/test.epub';
        const epubContent: Uint8Array = await ctx.resourceManager.getRawFileContent('test.epub');
        const epubFile = fs.openSync(epubTarget, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
        fs.writeSync(epubFile.fd, epubContent.buffer);
        fs.closeSync(epubFile);

        const epubHandle: ESObject = openDocument(epubTarget);
        const epubPages: number = pageCount(epubHandle);
        const epubRes: RenderResult = renderPage(epubHandle, 0, 1.5);
        this.epubText = `EPUB OK: ${epubPages} pages, page0=${epubRes.width}x${epubRes.height}`;
        console.info(`[Index] EPUB open+render OK: ${epubPages} pages`);

        try {
          const epubToc: TocEntry[] = getToc(epubHandle);
          if (epubToc.length > 0) {
            this.epubText += ` | TOC=${epubToc.length} entries`;
            console.info(`[Index] EPUB getToc OK: ${epubToc.length} entries, first='${epubToc[0].title}'`);
          }
        } catch (_e) {
          console.info('[Index] EPUB getToc: no outline');
        }

        closeDocument(epubHandle);
      } catch (e) {
        this.epubText = 'EPUB: error - ' + JSON.stringify(e);
        console.error('[Index] EPUB FAILED: ' + JSON.stringify(e));
      }

      // 14. Annotations round-trip (PDF only; re-open since handle was closed)
      let annHandle: ESObject = undefined;
      try {
        annHandle = openDocument(target);
        addHighlight(annHandle, 0, 0.2, 0.3, 0.8, 0.5, '#ffff00');
        console.info('[Index] addHighlight OK');

        const anns: AnnotationInfo[] = getAnnotations(annHandle, 0);
        console.info(`[Index] getAnnotations OK: ${anns.length} on page 1 (type=${anns.length > 0 ? anns[0].type : 'none'})`);

        if (anns.length > 0) {
          deleteAnnotation(annHandle, 0, 0);
          const after: AnnotationInfo[] = getAnnotations(annHandle, 0);
          console.info(`[Index] deleteAnnotation OK: now ${after.length}`);
        }

        saveDocument(annHandle, target);
        console.info('[Index] saveDocument OK (annotations persisted)');
      } catch (e) {
        const msg: string = e instanceof Error ? e.message : JSON.stringify(e);
        console.error('[Index] Annotation test FAILED: ' + msg);
      } finally {
        if (annHandle !== undefined) {
          try { closeDocument(annHandle); } catch (_e) {}
        }
      }

      // 15. Format sweep (sprint F): TXT / HTML / CBZ
      try {
        const formats: string[] = ['demo.txt', 'demo.html', 'demo.cbz', 'demo.mobi', 'demo.xps', 'demo.tiff'];
        let sweep = '';
        for (let i = 0; i < formats.length; i++) {
          try {
            const name = formats[i];
            const fmtTarget = ctx.cacheDir + '/' + name;
            const content: Uint8Array = await ctx.resourceManager.getRawFileContent(name);
            const f = fs.openSync(fmtTarget, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
            fs.writeSync(f.fd, content.buffer);
            fs.closeSync(f);
            const h: ESObject = openDocument(fmtTarget);
            const n: number = pageCount(h);
            let reflowable = false;
            try {
              reflowable = isReflowable(h);
            } catch (e) {
              /* not supported → fixed layout */
            }
            const r: RenderResult = renderPage(h, 0, 1.0);
            let chars = -1;
            if (reflowable) {
              try {
                chars = getText(h, 0, 1.0).length;
              } catch (e) {
                /* no text layer */
              }
            }
            sweep += `${name}:${n}p${reflowable ? '/reflow' : '/fixed'}/${r.width}x${r.height}` +
              (chars >= 0 ? `/${chars}ch` : '') + '  ';
            console.info(`[Index] FMT ${name} OK: ${n} pages reflow=${reflowable} render=${r.width}x${r.height} text=${chars}`);
            closeDocument(h);
          } catch (e) {
            sweep += `${formats[i]}:ERR  `;
            console.error(`[Index] FMT ${formats[i]} FAILED`);
          }
        }
        this.fmtText = sweep;
      } catch (e) {
        this.fmtText = 'format sweep failed';
      }

      this.status = `mupdf ${mupdfVersion} | pages=${pages} | ${res.width}x${res.height}`;
    } catch (e) {
      this.status = 'error: ' + JSON.stringify(e);
      if (handle !== undefined) {
        try { closeDocument(handle); } catch (_e) {}
      }
    }
  }
}
