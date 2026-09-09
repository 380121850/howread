import fs from '@ohos.file.fs';
import image from '@ohos.multimedia.image';
import router from '@ohos.router';
import picker from '@ohos.file.picker';
import util from '@ohos.util';
import common from '@ohos.app.ability.common';
import window from '@ohos.window';
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
import { scanDeviceBooks, importPathToSandbox, searchLibraryText, findMissingBooks, ScanHit, LibSearchHit } from '../model/LibrarySearch';
import { initProfiles, listProfiles, createProfile, switchProfile, deleteProfile, getCurrentProfile, DEFAULT_PROFILE } from '../model/Profiles';
import { getTagList, addTagToBook, removeTagFromBook, getSeriesList } from '../model/Tags';
import { getReadingStats, getDailyStats, getMonthlyStats, ReadingStatsData, DayStat, MonthStat } from '../model/ReadingStats';
import { fetchOpds, OPDS_PRESETS, OpdsEntry } from '../model/Opds';
import { OpdsServer, DavServer, getOpdsServers, saveOpdsServer, removeOpdsServer, getDavServers, saveDavServer, removeDavServer } from '../model/Servers';
import { SyncConfig, SyncLogEntry, SYNC_CONFLICTS, defaultSyncConfig, loadSyncConfig, saveSyncConfig, getSyncLog, runSync, testSyncConnection } from '../model/Sync';
import { AiConfig, AiMessage, loadAiConfig, saveAiConfig, chatCompletion, listModels, testChat, aiConfigReady, AI_PROVIDERS, AI_PROVIDER_URLS } from '../model/AiClient';
import { addNote, AiNote } from '../model/Notes';
import { biometricAvailable, startBiometricAuth } from '../model/Biometric';
import { PlaylistMeta, getAllPlaylists, createPlaylist, deletePlaylist, getPlaylistItems, updatePlaylist, addToPlaylist } from '../model/Playlists';
import http from '@ohos.net.http';
import promptAction from '@ohos.promptAction';
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

/** Home 书签笔记 card model (one per book that has bookmarks). */
interface BookmarkCard {
  path: string;
  title: string;
  cover: string;
  percent: string;
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

  // ---- NAPI self-test states (Settings tab, hidden by default; toggle = tap title 3x) ----
  @State showApiTest: boolean = false;
  private apiTestTaps: number = 0;
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
  /* Round 5: WebDAV directory browsing + download progress */
  @State webdavPath: string = '';        /* relative dir path, '' = root */
  @State webdavDirs: string[] = [];      /* subdirectories of the current dir */
  @State webdavProgress: number = -1;    /* 0..100 while downloading, -1 = idle */
  @State webdavDownloading: string = ''; /* file name being downloaded */
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
  @State topTitle: string = this.L($r('app.string.tab_home'));
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
  /* ---- Round 6: full-text search + device scan + missing cleanup ---- */
  @State libSearchBusy: boolean = false;
  @State libSearchProgress: string = '';
  @State libSearchHits: LibSearchHit[] = [];
  @State missingCount: number = 0;
  private scanBusy: boolean = false;
  /* Round 6: profile management */
  @State showProfile: boolean = false;
  @State profileList: string[] = [DEFAULT_PROFILE];
  @State profileInput: string = '';
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
  /* Round 5: biometric unlock in progress */
  private biometricBusy: boolean = false;
  /** passcode input for the lock overlay */
  @State lockPin: string = '';
  /** cached cover images for the dashboard carousel */
  @State dashCovers: string[] = [];
  /** 阅读统计 chart dialog visibility (home stats header) */
  @State showStatsChart: boolean = false;
  /** per-book action menu target path ('' = hidden) */
  @State libMenuPath: string = '';
  /* round 4 B: playlists */
  @State showPlPick: boolean = false;      /* add-current-book picker */
  @State plAddPath: string = '';
  @State plPickName: string = '';
  @State plList: PlaylistMeta[] = [];
  @State showPlManage: boolean = false;    /* playlists management dialog */
  @State plNewName: string = '';
  @State showPlItems: boolean = false;     /* one playlist's items */
  @State plItemsName: string = '';
  @State plItems: string[] = [];
  // ---- Phase 10: AI 大模型接入 (Android hr08/13/17/18/19 parity) ----
  /** AI config dialog visibility (prefs) */
  @State showAiDialog: boolean = false;
  /** editable AI config bound to the dialog inputs */
  @State aiCfg: AiConfig = {
    provider: AI_PROVIDERS[0], url: AI_PROVIDER_URLS[0], key: '', model: '', maxTokens: 8192, thinking: false
  };
  @State aiTestIn: string = '';
  @State aiTestOut: string = '';
  @State aiBusy: boolean = false;
  @State aiModels: string[] = [];
  /** AI 简介 dialog */
  @State showAiIntro: boolean = false;
  @State aiIntroBusy: boolean = false;
  @State aiIntroText: string = '';
  @State aiIntroTitle: string = '';
  private aiIntroPath: string = '';
  // ---- Phase 10: OPDS/WebDAV server persistence (Android hr05/hr06) ----
  @State opdsServers: OpdsServer[] = [];
  @State davServers: DavServer[] = [];
  /** OPDS add/edit dialog: index into davServers, -1 = add */
  @State showOpdsEdit: boolean = false;
  @State opdsEditIdx: number = -1;
  @State opdsEditUrl: string = '';
  @State opdsEditName: string = '';
  /** WebDAV add/edit dialog (hr06) */
  @State showDavEdit: boolean = false;
  @State davEditIdx: number = -1;
  @State davEditUrl: string = '';
  @State davEditName: string = '';
  @State davEditUser: string = '';
  @State davEditPass: string = '';
  @State davEditTrusted: boolean = false;
  /** index of the WebDAV server used by webdavConnect */
  @State davSelected: number = -1;
  // ---- Phase 10: WebDAV sync basic edition (Android hr09/hr09b) ----
  @State showSyncDialog: boolean = false;
  @State syncCfg: SyncConfig = defaultSyncConfig();
  @State syncBusy: boolean = false;
  @State syncTestOut: string = '';
  @State showSyncLog: boolean = false;
  @State syncLogs: SyncLogEntry[] = [];

  /* Round 6: resolve a string resource into a plain string */
  private L(r: Resource): string {
    return getContext(this).resourceManager.getStringSync(r);
  }

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
    loadAiConfig(getContext(this)).then((c: AiConfig) => {
      this.aiCfg = c;
    });
    this.loadServers();
    loadSyncConfig(getContext(this)).then((c: SyncConfig) => {
      this.syncCfg = c;
      this.armAutoSync();
    });
  }

  /** hr05: load persisted OPDS/WebDAV server lists. */
  private loadServers(): void {
    getOpdsServers(getContext(this)).then((l: OpdsServer[]) => {
      this.opdsServers = l;
    });
    getDavServers(getContext(this)).then((l: DavServer[]) => {
      this.davServers = l;
      if (this.davSelected >= l.length) {
        this.davSelected = l.length - 1;
      }
    });
  }

  /** Credentials of the selected WebDAV server ('' when none/basic disabled). */
  private davAuthHeader(): string {
    if (this.davSelected < 0 || this.davSelected >= this.davServers.length) {
      return '';
    }
    const s = this.davServers[this.davSelected];
    if (s.user.length === 0) {
      return '';
    }
    const raw = s.user + ':' + s.password;
    return 'Basic ' + new util.Base64Helper().encodeToStringSync(new Uint8Array(this.toBytes(raw)));
  }

  private toBytes(s: string): number[] {
    const arr: number[] = [];
    for (let i = 0; i < s.length; i++) {
      arr.push(s.charCodeAt(i) & 0xff);
    }
    return arr;
  }

  /** Phase 3: refresh stats + library whenever the Index page becomes visible
   *  again (e.g. after returning from the Reader via router back). */
  onPageShow(): void {
    this.loadStats();
    this.loadLibrary();
    if (this.settings.appLockEnabled && !this.appUnlocked) {
      this.checkAppLock();
    }
    this.consumeCardOpen();
    this.consumeSharedText();
  }

  /** Round 5: save text received via system share as a TXT book. */
  private consumeSharedText(): void {
    const t = AppStorage.get<string>('sharedText');
    if (t === undefined || t.length === 0) {
      return;
    }
    AppStorage.setOrCreate('sharedText', '');
    try {
      const ctx = getContext(this);
      const dir = ctx.filesDir + '/shared';
      if (!fs.accessSync(dir)) {
        fs.mkdirSync(dir, true);
      }
      const path = dir + '/shared_' + Date.now() + '.txt';
      const f = fs.openSync(path, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      fs.writeSync(f.fd, t);
      fs.closeSync(f);
      const name = path.substring(path.lastIndexOf('/') + 1);
      const book: RecentBook = {
        path: path,
        title: name,
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
      saveRecentBook(ctx, book).then(() => {
        this.loadLibrary();
        promptAction.showToast({ message: $r('app.string.shared_saved') });
      });
      console.info(`[Index] Shared text saved: ${name}`);
    } catch (e) {
      console.error('[Index] Shared text save failed');
    }
  }

  /** Round 5: open a book requested from the desktop service card. */
  private consumeCardOpen(): void {
    const p = AppStorage.get<string>('cardOpenPath');
    if (p === undefined || p.length === 0) {
      return;
    }
    AppStorage.setOrCreate('cardOpenPath', '');
    const name = p.substring(p.lastIndexOf('/') + 1);
    const book: RecentBook = {
      path: p,
      title: name,
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
    this.openBook(book);
    console.info(`[Index] Card requested book open: ${name}`);
  }

  private async initSettings(): Promise<void> {
    const ctx = getContext(this);
    /* Round 6: load the active profile before any profile-aware store opens */
    await initProfiles(ctx);
    this.settings = await loadSettings(ctx);
    console.info(`[Index] Settings loaded: theme=${this.settings.theme} font=${this.settings.fontSize}`);
    /* Round 6: honor the preferred screen orientation */
    if (this.settings.screenOrientation > 0) {
      this.applyOrientation();
    }
    /* Round 6: optional device book scan on startup (block B) */
    if (this.settings.autoScanOnStart) {
      this.runDeviceScan();
    }
    this.checkAppLock();
  }

  /** Phase 6: show the lock overlay if the app lock is enabled and not yet unlocked. */
  private checkAppLock(): void {
    if (this.settings.appLockEnabled && !this.appUnlocked) {
      this.lockPin = '';
      this.activeOverlay = 7;
      console.info('[Index] App lock requested');
      /* Round 5: auto-trigger biometric unlock when enabled + available */
      if (this.settings.appLockBiometric && biometricAvailable()) {
        this.tryBiometric();
      }
    }
  }

  /** Round 5: biometric unlock (fingerprint/face) with passcode fallback. */
  private tryBiometric(): void {
    if (this.biometricBusy) {
      return;
    }
    this.biometricBusy = true;
    startBiometricAuth(() => {
      this.biometricBusy = false;
      this.appUnlocked = true;
      this.activeOverlay = 0;
      console.info('[Index] Biometric unlock success');
    }, (msg: string) => {
      this.biometricBusy = false;
      console.warn(`[Index] Biometric unlock failed: ${msg}`);
    });
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
      this.libFolders = [{ name: this.L($r('app.string.tab_library')), path: ctx.cacheDir }];
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
      this.backupText = this.L($r('app.string.common_export_failed'));
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
        this.backupText = this.L($r('app.string.backup_invalid'));
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
      this.backupText = this.L($r('app.string.backup_restore_failed'));
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
      /* Round 6: refresh the missing-files badge */
      this.missingCount = findMissingBooks(this.recentBooks).length;
    } catch (e) {
      console.error('[Index] loadLibrary failed');
    }
  }

  /* ---- Round 6: device scan / full-text search / missing cleanup (block B) ---- */

  /** Scan well-known folders and import books that are not in the library yet. */
  async runDeviceScan(): Promise<void> {
    if (this.scanBusy) {
      return;
    }
    this.scanBusy = true;
    try {
      const ctx = getContext(this);
      const hits: ScanHit[] = scanDeviceBooks(ctx);
      const known: string[] = [];
      for (let i = 0; i < this.recentBooks.length; i++) {
        known.push(this.recentBooks[i].path);
      }
      let added: number = 0;
      for (let i = 0; i < hits.length && added < 20; i++) {
        if (known.indexOf(hits[i].path) >= 0) {
          continue;
        }
        const target: string = importPathToSandbox(ctx, hits[i].path);
        if (target.length === 0) {
          continue;
        }
        const book: RecentBook = {
          path: target,
          title: hits[i].name,
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
        added++;
      }
      await this.loadLibrary();
      promptAction.showToast({ message: `\u626B\u63CF\u5B8C\u6210\uFF1A\u53D1\u73B0 ${hits.length} \u4E2A\u6587\u4EF6\uFF0C\u65B0\u5BFC\u5165 ${added} \u672C` });
      console.info(`[Index] Device scan: ${hits.length} files, ${added} imported`);
    } finally {
      this.scanBusy = false;
    }
  }

  /** Run the full-text search over the whole library with progress. */
  async runLibSearch(): Promise<void> {
    const q: string = this.libQuery.trim();
    if (q.length === 0 || this.libSearchBusy) {
      return;
    }
    this.libSearchBusy = true;
    this.libSearchHits = [];
    this.libSearchProgress = '0/' + this.recentBooks.length;
    const hits: LibSearchHit[] = await searchLibraryText(this.recentBooks, q, (done: number, total: number) => {
      this.libSearchProgress = done + '/' + total;
      return this.libSearchBusy; /* false when cancelled */
    });
    this.libSearchHits = hits;
    this.libSearchBusy = false;
    promptAction.showToast({ message: `\u5168\u6587\u641C\u7D22\uFF1A${hits.length} \u5904\u547D\u4E2D` });
    console.info(`[Index] Full-text search '${q}': ${hits.length} hits`);
  }

  /** Cancel a running full-text search. */
  cancelLibSearch(): void {
    this.libSearchBusy = false;
  }

  /** Open a full-text hit at its page. */
  openLibHit(hit: LibSearchHit): void {
    router.pushUrl({ url: 'components/Reader', params: { docPath: hit.path, title: hit.title, jumpPage: String(hit.page) } });
  }

  /** Remove library entries whose backing files have disappeared. */
  async cleanupMissing(): Promise<void> {
    const missing: string[] = findMissingBooks(this.recentBooks);
    if (missing.length === 0) {
      return;
    }
    const keep: RecentBook[] = this.recentBooks.filter((b: RecentBook) => missing.indexOf(b.path) < 0);
    await replaceRecentBooks(getContext(this), keep);
    await this.loadLibrary();
    promptAction.showToast({ message: `\u5DF2\u79FB\u9664 ${missing.length} \u6761\u5931\u6548\u8BB0\u5F55` });
  }

  /* ---- Round 6: profile management (block G) ---- */

  /** Open the profile panel and refresh the profile list. */
  async openProfilePanel(): Promise<void> {
    this.profileList = await listProfiles(getContext(this));
    this.profileInput = '';
    this.showProfile = true;
  }

  /** Create a new empty profile. */
  async doCreateProfile(): Promise<void> {
    const ok: boolean = await createProfile(getContext(this), this.profileInput);
    if (!ok) {
      promptAction.showToast({ message: $r('app.string.prof_invalid') });
      return;
    }
    this.profileInput = '';
    this.profileList = await listProfiles(getContext(this));
    promptAction.showToast({ message: $r('app.string.prof_created') });
  }

  /** Switch the active profile and reload all profile-scoped data. */
  async doSwitchProfile(name: string): Promise<void> {
    const ok: boolean = await switchProfile(getContext(this), name);
    if (!ok) {
      return;
    }
    this.showProfile = false;
    this.settings = await loadSettings(getContext(this));
    await this.loadLibrary();
    this.loadAllBookmarks();
    this.loadStats();
    promptAction.showToast({ message: `\u5DF2\u5207\u6362\u5230\u6863\u6848\uFF1A${name}` });
    console.info(`[Index] Profile switched to ${name}`);
  }

  /** Delete a non-default profile (its data becomes unreachable). */
  async doDeleteProfile(name: string): Promise<void> {
    const ok: boolean = await deleteProfile(getContext(this), name);
    if (!ok) {
      promptAction.showToast({ message: $r('app.string.prof_nodelete') });
      return;
    }
    if (getCurrentProfile() === DEFAULT_PROFILE) {
      this.settings = await loadSettings(getContext(this));
      await this.loadLibrary();
      this.loadAllBookmarks();
      this.loadStats();
    }
    this.profileList = await listProfiles(getContext(this));
    promptAction.showToast({ message: `\u6863\u6848 ${name} \u5DF2\u5220\u9664` });
  }

  /** Profile management overlay (list / switch / create / delete). */
  @Builder
  buildProfileOverlay() {
    Column({ space: 10 }) {
      Text($r('app.string.prof_title'))
        .fontSize(16).fontWeight(FontWeight.Medium).fontColor('#212121')
      Text($r('app.string.prof_hint'))
        .fontSize(11).fontColor('#888')
      Text(`\u5F53\u524D\uFF1A${getCurrentProfile()}`)
        .fontSize(12).fontColor($r('app.color.brand_primary'))
      List() {
        ForEach(this.profileList, (name: string) => {
          ListItem() {
            Row({ space: 8 }) {
              Text(name === DEFAULT_PROFILE ? $r('app.string.common_default') : name)
                .fontSize(13).fontColor('#333').layoutWeight(1)
              if (name !== getCurrentProfile()) {
                Text($r('app.string.common_switch')).fontSize(12).fontColor($r('app.color.brand_primary'))
                  .onClick(() => {
                    this.doSwitchProfile(name);
                  })
                Text($r('app.string.common_delete')).fontSize(12).fontColor('#b71c1c')
                  .onClick(() => {
                    this.doDeleteProfile(name);
                  })
              } else {
                Text('\u2713').fontSize(14).fontColor('#2a7')
              }
            }
            .width('100%').padding({ top: 8, bottom: 8, left: 8, right: 8 })
          }
        }, (name: string) => name)
      }
      .height(140)
      .divider({ strokeWidth: 1, color: '#f0f0f0', startMargin: 8, endMargin: 8 })

      Row({ space: 8 }) {
        TextInput({ placeholder: $r('app.string.prof_new_name'), text: this.profileInput })
          .fontSize(13).height(34).layoutWeight(1)
          .onChange((v: string) => {
            this.profileInput = v;
          })
        Button($r('app.string.common_new'))
          .fontSize(13).height(34)
          .backgroundColor($r('app.color.brand_primary')).fontColor(Color.White)
          .onClick(() => {
            this.doCreateProfile();
          })
      }
      .width('100%')

      Button($r('app.string.common_close'))
        .fontSize(13).height(34).width('100%')
        .backgroundColor('#e0e0e0').fontColor('#333')
        .onClick(() => {
          this.showProfile = false;
        })
    }
    .width('78%')
    .padding(14)
    .borderRadius(12)
    .backgroundColor(Color.White)
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
          Image($r('app.media.ic_menu'))
            .width(24).height(24).fillColor(Color.White)
            .width(44).height(44).padding(10)
            .onClick(() => {
              this.showDrawer = true;
              this.pickRandomQuote();
            })
          Text(this.topTitle)
            .fontSize(18).fontWeight(FontWeight.Bold).fontColor(Color.White)
            .layoutWeight(1)
            .textAlign(TextAlign.Start)
            .padding({ left: 6 })
          Image($r('app.media.ic_add'))
            .width(24).height(24).fillColor(Color.White)
            .width(44).height(44).padding(10)
            .onClick(() => {
              this.openFilePicker();
            })
        }
        .width('100%').height(48)
        .backgroundColor($r('app.color.brand_primary'))

        // 4 tabs, bottom by default; top position optional (Android tapPositionTop)
        Tabs({ barPosition: this.settings.tabPositionTop ? BarPosition.Start : BarPosition.End, index: this.currentTab }) {
          TabContent() {
            this.buildDashboardTab()
          }
          .tabBar(this.buildTabLabel($r('app.media.ic_home'), $r('app.string.tab_home'), 0))

          TabContent() {
            this.buildLibraryTab()
          }
          .tabBar(this.buildTabLabel($r('app.media.ic_book_open'), $r('app.string.tab_library'), 1))

          TabContent() {
            this.buildBrowseTab()
          }
          .tabBar(this.buildTabLabel($r('app.media.ic_folder'), $r('app.string.tab_my_files'), 2))

          TabContent() {
            this.buildSettingsTab()
          }
          .tabBar(this.buildTabLabel($r('app.media.ic_wrench'), $r('app.string.tab_prefs'), 3))
        }
        .width('100%')
        .layoutWeight(1)
        .barMode(BarMode.Fixed)
        .barBackgroundColor($r('app.color.brand_primary'))
        .onChange((index: number) => {
          this.switchTab(index);
        })
      }
      .width('100%')
      .height('100%')

      // ---- FAB "continue last book" (Android fabLastBook: home + library only) ----
      if (this.currentTab === 0 || this.currentTab === 1) {
        Button() {
          Image($r('app.media.ic_book_open'))
            .width(24).height(24).fillColor(Color.White)
        }
        .width(52).height(52)
        .backgroundColor($r('app.color.brand_primary'))
        .borderRadius(26)
        .shadow({ radius: 8, color: '#44000000', offsetX: 0, offsetY: 2 })
        .margin({ right: 16, bottom: this.settings.tabPositionTop ? 24 : 62 })
        .onClick(() => {
          this.openLastBook();
        })
      }

      // ---- per-book action menu (Android hr19) ----
      if (this.libMenuPath.length > 0) {
        this.buildLibMenuDialog()
      }

      // ---- 阅读统计 chart dialog (Android stats chart popup) ----
      if (this.showStatsChart) {
        this.buildStatsChartDialog()
      }

      // ---- AI 大模型接入 dialog (Android hr08) ----
      if (this.showAiDialog) {
        this.buildAiDialog()
      }

      // ---- AI 简介书籍 dialog (Android hr19 + hr17) ----
      if (this.showAiIntro) {
        this.buildAiIntroDialog()
      }

      // ---- playlists dialogs (round 4 B) ----
      if (this.showPlPick) {
        this.buildPlaylistPickDialog()
      }
      if (this.showPlManage) {
        this.buildPlManageDialog()
      }
      if (this.showPlItems) {
        this.buildPlItemsDialog()
      }

      // ---- OPDS add/edit dialog (hr05) ----
      if (this.showOpdsEdit) {
        this.buildOpdsEditDialog()
      }

      // ---- WebDAV add/edit dialog (hr06) ----
      if (this.showDavEdit) {
        this.buildDavEditDialog()
      }

      // ---- WebDAV 同步 dialog (hr09) ----
      if (this.showSyncDialog) {
        this.buildSyncDialog()
      }

      // ---- 同步日志 page (hr09b) ----
      if (this.showSyncLog) {
        this.buildSyncLogDialog()
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
      /* Round 6: profile management overlay */
      if (this.showProfile) {
        Column() {}
          .width('100%').height('100%')
          .backgroundColor('#66000000')
          .onClick(() => {
            this.showProfile = false;
          })
        this.buildProfileOverlay()
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
      const label = key.length > 0 ? key : this.L($r('app.string.tag_none'));
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
          Button(this.recentSortMode === 0 ? $r('app.string.sort_recent') : this.recentSortMode === 1 ? 'A-Z' : $r('app.string.sort_author'))
            .fontSize(11).height(26)
            .onClick(() => { this.recentSortMode = (this.recentSortMode + 1) % 3; })
          Button(this.recentViewMode === 0 ? $r('app.string.view_list') : this.recentViewMode === 1 ? $r('app.string.sort_author') : $r('app.string.sort_series'))
            .fontSize(11).height(26).margin({ left: 6 })
            .onClick(() => { this.recentViewMode = (this.recentViewMode + 1) % 3; })
          if (this.allTags.length > 0) {
            Text(this.activeTagFilter ? '\u7B5B:' + this.activeTagFilter : $r('app.string.lib_tags'))
              .fontSize(11).fontColor('#2a7').height(26)
              .margin({ left: 6 })
              .onClick(() => {
                const idx = this.allTags.indexOf(this.activeTagFilter);
                this.activeTagFilter = idx < 0 ? (this.allTags.length > 0 ? this.allTags[0] : '') : (idx + 1 >= this.allTags.length ? '' : this.allTags[idx + 1]);
              })
          }
        }.width('94%').margin({ bottom: 8 })

        if (this.getFilteredRecent().length === 0) {
          Text($r('app.string.lib_empty'))
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
          Text($r('app.string.star_empty'))
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
  /** Full URL of the current WebDAV directory (root + webdavPath). */
  private webdavDirUrl(): string {
    let u = this.webdavUrl.replace(/\/+$/, '');
    if (this.webdavPath.length > 0) {
      u = u + '/' + this.webdavPath;
    }
    return u.endsWith('/') ? u : u + '/';
  }

  /** Navigate into a subdirectory (relative to the current dir). */
  private davOpenDir(dir: string): void {
    this.webdavPath = this.webdavPath + dir;
    this.webdavConnect();
  }

  /** Go up one directory level. */
  private davUp(): void {
    const p = this.webdavPath.replace(/\/+$/, '');
    const cut = p.lastIndexOf('/');
    this.webdavPath = cut >= 0 ? p.substring(0, cut + 1) : '';
    this.webdavConnect();
  }

  /** Decode %XX escapes for display; falls back to the raw name. */
  private davDecodeName(s: string): string {
    try {
      return decodeURIComponent(s);
    } catch (e) {
      return s;
    }
  }

  private async webdavConnect(): Promise<void> {
    if (this.webdavUrl.length === 0) {
      this.webdavStatus = 'Please enter a WebDAV URL';
      return;
    }
    this.webdavConnecting = true;
    this.webdavStatus = 'Connecting...';
    try {
      const url: string = this.webdavDirUrl();
      const hdr: Record<string, string> = { 'Depth': '1' };
      const auth: string = this.davAuthHeader();
      if (auth.length > 0) {
        hdr['Authorization'] = auth;
      }
      let xml = '';
      /* Round 5: real PROPFIND first (RequestMethod is a string enum); GET fallback */
      try {
        const req1 = http.createHttp();
        const r1 = await req1.request(url, {
          method: 'PROPFIND' as ESObject as http.RequestMethod,
          header: hdr,
          connectTimeout: 10000,
          readTimeout: 15000
        });
        xml = String(r1.result);
        req1.destroy();
        if (r1.responseCode !== 207 && r1.responseCode !== 200) {
          xml = '';
        }
      } catch (_e) {
        xml = '';
      }
      if (xml.length === 0) {
        let req = http.createHttp();
        let resp = await req.request(url, {
          method: http.RequestMethod.GET,
          header: hdr,
          connectTimeout: 10000,
          readTimeout: 15000
        });
        if (resp.responseCode === 207 || resp.responseCode === 200) {
          xml = String(resp.result);
        } else {
          this.webdavStatus = `Server responded: ${resp.responseCode}`;
          this.webdavConnecting = false;
          req.destroy();
          return;
        }
        req.destroy();
      }
      /* Parse entries: multistatus <href> tags (PROPFIND 207, any namespace
       * prefix like <D:href>) or HTML directory listing <a href="..."> links. */
      const dirs: string[] = [];
      const files: string[] = [];
      let pos: number = 0;
      while (true) {
        const a1: number = xml.indexOf('<D:href>', pos);
        const a2: number = xml.indexOf('<href>', pos);
        let start: number = -1;
        let tagLen: number = 6;
        if (a1 >= 0 && a2 >= 0) {
          start = Math.min(a1, a2);
          tagLen = start === a1 ? 8 : 6;
        } else if (a1 >= 0) {
          start = a1;
          tagLen = 8;
        } else if (a2 >= 0) {
          start = a2;
          tagLen = 6;
        } else {
          break;
        }
        const close: number = xml.indexOf('</', start + tagLen);
        if (close < 0) {
          break;
        }
        const gt: number = xml.indexOf('>', close);
        const href: string = xml.substring(start + tagLen, close).trim();
        this.davClassifyEntry(href, dirs, files);
        pos = gt >= 0 ? gt + 1 : close + 2;
      }
      if (dirs.length === 0 && files.length === 0) {
        pos = 0;
        while (true) {
          const hStart: number = xml.indexOf('href="', pos);
          if (hStart < 0) {
            break;
          }
          const hEnd: number = xml.indexOf('"', hStart + 6);
          if (hEnd < 0) {
            break;
          }
          const href: string = xml.substring(hStart + 6, hEnd).trim();
          this.davClassifyEntry(href, dirs, files);
          pos = hEnd + 1;
        }
      }
      this.webdavDirs = dirs;
      this.webdavFiles = files;
      this.webdavStatus = `Connected! ${dirs.length} dirs, ${files.length} files`;
    } catch (e) {
      this.webdavStatus = 'Connection failed: ' + String(e);
    }
    this.webdavConnecting = false;
  }

  /** Classify one entry href (multistatus or HTML listing) into dirs/files. */
  private davClassifyEntry(rawHref: string, dirs: string[], files: string[]): void {
    if (rawHref.length === 0 || rawHref.startsWith('http') && rawHref.indexOf('://') < 0) {
      return;
    }
    let href = rawHref;
    /* strip query string; strip URL prefix up to the path */
    const q: number = href.indexOf('?');
    if (q >= 0) {
      href = href.substring(0, q);
    }
    const schemeEnd: number = href.indexOf('://');
    if (schemeEnd > 0) {
      const pathStart: number = href.indexOf('/', schemeEnd + 3);
      href = pathStart >= 0 ? href.substring(pathStart) : href;
    }
    /* normalize both sides to absolute paths (leading slash) */
    if (!href.startsWith('/') && href.indexOf('://') < 0) {
      href = '/' + href;
    }
    const baseRel: string = this.webdavPath.length === 0 ? '/'
      : (this.webdavPath.startsWith('/') ? this.webdavPath : '/' + this.webdavPath);
    if (!href.startsWith(baseRel) || href.length <= baseRel.length) {
      return;
    }
    let rel: string = href.substring(baseRel.length).replace(/^\/+|\/+$/g, '');
    if (rel.length === 0 || rel.indexOf('/') >= 0) {
      return; /* only single segments inside the current dir */
    }
    const isDir: boolean = href.length > href.replace(/\/+$/, '').length;
    if (isDir) {
      dirs.push(this.davDecodeName(rel));
    } else {
      files.push(this.davDecodeName(rel));
    }
  }

  /** Round 5: stream-download a file with a progress callback (requestInStream). */
  private async webdavDownload(fileName: string): Promise<void> {
    if (this.webdavUrl.length === 0 || fileName.length === 0) return;
    const url: string = this.webdavDirUrl() + encodeURIComponent(fileName);
    const hdr: Record<string, string> = {};
    const auth: string = this.davAuthHeader();
    if (auth.length > 0) {
      hdr['Authorization'] = auth;
    }
    this.webdavDownloading = fileName;
    this.webdavProgress = 0;
    try {
      let total = 0;
      const chunks: ArrayBuffer[] = [];
      let req = http.createHttp();
      req.on('headersReceive', (h: Object) => {
        try {
          const head = h as Record<string, string>;
          const cl: string = head['content-length'] !== undefined ? head['content-length']
            : head['Content-Length'] !== undefined ? head['Content-Length'] : '';
          if (cl.length > 0) {
            total = parseInt(cl, 10);
          }
        } catch (_e) { /* ignore */ }
      });
      req.on('dataReceive', (chunk: ArrayBuffer) => {
        chunks.push(chunk);
      });
      req.on('dataReceiveProgress', (info: http.DataReceiveProgressInfo) => {
        this.webdavProgress = total > 0 ? Math.min(100, Math.round(info.receiveSize * 100 / total))
          : Math.min(99, Math.round(info.receiveSize / 10240));
      });
      const code: number = await req.requestInStream(url, {
        method: http.RequestMethod.GET,
        header: hdr,
        connectTimeout: 10000,
        readTimeout: 60000
      });
      req.destroy();
      if (code === 200) {
        let size = 0;
        for (let i = 0; i < chunks.length; i++) {
          size += chunks[i].byteLength;
        }
        const merged = new Uint8Array(size);
        let off = 0;
        for (let i = 0; i < chunks.length; i++) {
          merged.set(new Uint8Array(chunks[i]), off);
          off += chunks[i].byteLength;
        }
        /* Save to sandbox cache */
        const ctx = getContext(this);
        const path: string = ctx.cacheDir + '/' + fileName;
        let f = fs.openSync(path, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
        fs.writeSync(f.fd, merged.buffer);
        fs.closeSync(f);
        this.webdavStatus = `Downloaded: ${fileName} (${size} bytes)`;
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
        this.webdavStatus = `Download failed: ${code}`;
      }
    } catch (e) {
      this.webdavStatus = 'Download error: ' + String(e);
    }
    this.webdavProgress = -1;
    this.webdavDownloading = '';
  }

  // ---- Phase 5: OPDS 网上书库 ----

  /** Open an OPDS feed URL (pushed onto the nav stack). */
  private async opdsOpen(url: string): Promise<void> {
    if (url.length === 0) {
      return;
    }
    this.opdsLoading = true;
    this.opdsStatus = this.L($r('app.string.common_loading'));
    try {
      const entries: OpdsEntry[] = await fetchOpds(url, 15000, getContext(this));
      this.opdsEntries = entries;
      this.opdsStatus = entries.length > 0 ? '\u5171 ' + entries.length + ' \u9879' : this.L($r('app.string.dash_empty'));
      this.opdsStack.push(url);
      console.info(`[Index] OPDS opened: ${url} -> ${entries.length} entries`);
    } catch (e) {
      this.opdsStatus = this.L($r('app.string.common_load_failed'));
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
    this.opdsStatus = this.L($r('app.string.common_loading'));
    fetchOpds(prev, 15000, getContext(this)).then((entries: OpdsEntry[]) => {
      this.opdsEntries = entries;
      this.opdsStatus = entries.length > 0 ? '\u5171 ' + entries.length + ' \u9879' : this.L($r('app.string.common_empty'));
      this.opdsLoading = false;
    }).catch(() => {
      this.opdsStatus = this.L($r('app.string.common_load_failed'));
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
      this.opdsStatus = this.L($r('app.string.dav_download_error'));
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

      if (this.webdavProgress >= 0) {
        Text(`Downloading ${this.webdavDownloading} ${this.webdavProgress}%`)
          .fontSize(11).fontColor('#3949AB').width('90%')
        Progress({ value: this.webdavProgress, total: 100 })
          .width('90%').height(6).margin({ top: 4 })
      }

      if (this.webdavPath.length > 0) {
        Row() {
          Text($r('app.string.dav_up_dir')).fontSize(13).fontColor('#3949AB')
          Text(this.webdavPath).fontSize(11).fontColor('#888').margin({ left: 10 }).layoutWeight(1).maxLines(1)
        }
        .width('90%').padding({ top: 6, bottom: 6 })
        .onClick(() => { this.davUp(); })
      }

      if (this.webdavDirs.length > 0 || this.webdavFiles.length > 0) {
        List() {
          ForEach(this.webdavDirs, (dir: string) => {
            ListItem() {
              Row() {
                Text('📁').fontSize(14).margin({ right: 8 })
                Text(dir).fontSize(13).layoutWeight(1).fontColor('#3949AB')
              }
              .width('90%').padding({ top: 6, bottom: 6 })
              .onClick(() => { this.davOpenDir(dir + '/'); })
            }
          }, (dir: string) => 'd_' + dir)
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
  buildTabLabel(icon: Resource, text: ResourceStr, idx: number) {
    Column({ space: 2 }) {
      Image(icon)
        .width(22).height(22)
        .fillColor(this.currentTab === idx ? '#FFFFFF' : '#B3FFFFFF')
      Text(text).fontSize(10).fontColor(this.currentTab === idx ? '#FFFFFF' : '#DDFFFFFF')
    }
    .width('100%')
    .alignItems(HorizontalAlign.Center)
  }

  // ---- Phase 1: shell helpers (top bar / drawer / dashboard / overlays) ----

  /** Switch bottom tab and refresh the top bar title (Android: title = tab name). */
  switchTab(index: number): void {
    this.currentTab = index;
    const names: string[] = [this.L($r('app.string.tab_home')), this.L($r('app.string.tab_library')), this.L($r('app.string.tab_my_files')), this.L($r('app.string.tab_prefs'))];
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
            .backgroundColor($r('app.color.brand_primary'))
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

  /** Section title row: bold dark title + blue 更多 link (Android DashboardFragment2).
   * moreAction: 0..3 = switch tab, 7 = stats chart dialog, 8 = network overlay, 9 = bookmarks overlay, -1 = none. */
  @Builder
  sectionHeader(title: string, moreAction: number) {
    Row() {
      Text(title)
        .fontSize(17).fontWeight(FontWeight.Medium).fontColor('#333')
      Blank()
      if (moreAction >= 0) {
        Text($r('app.string.common_more'))
          .fontSize(14).fontColor($r('app.color.brand_primary'))
          .onClick(() => {
            if (moreAction === 7) {
              this.updateChartData();
              this.showStatsChart = true;
            } else if (moreAction === 8) {
              this.activeOverlay = 2;
            } else if (moreAction === 9) {
              this.activeOverlay = 1;
            } else {
              this.switchTab(moreAction);
            }
          })
      }
    }
    .width('94%')
    .padding({ top: 14, bottom: 8 })
  }

  /** Big cover tile for the home carousels (no caption, Android hr01 style). */
  @Builder
  coverTile(book: RecentBook) {
    if (book.coverPath.length > 0) {
      Image(book.coverPath)
        .width(92).height(126)
        .borderRadius(3)
        .objectFit(ImageFit.Cover)
        .backgroundColor('#ddd')
        .onClick(() => {
          this.openBook(book);
        })
    } else {
      Column() {
        Text(book.title.length > 6 ? book.title.substring(0, 6) : book.title)
          .fontSize(12).fontColor('#fff').maxLines(3).textAlign(TextAlign.Center)
      }
      .width(92).height(126)
      .justifyContent(FlexAlign.Center)
      .padding(6)
      .borderRadius(3)
      .backgroundColor('#8d7b74')
      .onClick(() => {
        this.openBook(book);
      })
    }
  }

  /** White stat card: big blue number + grey caption (Android hr01).
   * kind: 0=书籍总数 1=已读书籍 2=总阅读时间 3=今日阅读 4=阅读速度.
   * Values are read via statValue() inside build so @State changes re-render. */
  @Builder
  statCard(kind: number) {
    Column({ space: 6 }) {
      Text(this.statValue(kind))
        .fontSize(24).fontWeight(FontWeight.Bold).fontColor($r('app.color.brand_primary')).maxLines(1)
      Text(this.statLabel(kind))
        .fontSize(13).fontColor('#666').maxLines(1)
    }
    .layoutWeight(1)
    .height(96)
    .justifyContent(FlexAlign.Center)
    .backgroundColor(Color.White)
    .borderRadius(4)
    .shadow({ radius: 2, color: '#22000000', offsetX: 0, offsetY: 1 })
  }

  statValue(kind: number): string {
    if (kind === 0) {
      return '' + this.recentBooks.length;
    }
    if (kind === 1) {
      return '' + this.countFinishedBooks();
    }
    if (kind === 2) {
      return this.fmtDuration(this.statsData.totalMs);
    }
    if (kind === 3) {
      return this.fmtDuration(this.statsData.todayMs);
    }
    return this.statsData.speedPpm + '';
  }

  statLabel(kind: number): string {
    if (kind === 0) {
      return this.L($r('app.string.stat_books'));
    }
    if (kind === 1) {
      return this.L($r('app.string.stat_finished'));
    }
    if (kind === 2) {
      return this.L($r('app.string.stat_time'));
    }
    if (kind === 3) {
      return this.L($r('app.string.stat_today'));
    }
    return this.L($r('app.string.stat_speed'));
  }

  /** Quick-access circle: colored disc + icon + caption (Android 我的文件/网上书库 rows). */
  @Builder
  shortcutCircle(icon: Resource, color: string, label: string, target: number) {
    Column({ space: 8 }) {
      Column() {
        Image(icon).width(28).height(28).fillColor(Color.White)
      }
      .width(64).height(64)
      .justifyContent(FlexAlign.Center)
      .backgroundColor(color)
      .borderRadius(32)
      Text(label)
        .fontSize(13).fontColor('#666').maxLines(1)
    }
    .onClick(() => {
      this.switchTab(target);
    })
  }

  /** Group allBookmarks into one card per book (cover + title + 快速书签 + percent). */
  private bookmarkCards(): BookmarkCard[] {
    const cards: BookmarkCard[] = [];
    for (let i = 0; i < this.allBookmarks.length; i++) {
      const b: Bookmark = this.allBookmarks[i];
      let found: BookmarkCard | undefined = undefined;
      for (let j = 0; j < cards.length; j++) {
        if (cards[j].path === b.path) {
          found = cards[j];
          break;
        }
      }
      if (found === undefined) {
        let cover = '';
        let title = b.path.substring(b.path.lastIndexOf('/') + 1);
        let total = 0;
        for (let k = 0; k < this.recentBooks.length; k++) {
          if (this.recentBooks[k].path === b.path) {
            cover = this.recentBooks[k].coverPath;
            title = this.recentBooks[k].title;
            total = this.recentBooks[k].totalPages;
            break;
          }
        }
        const card: BookmarkCard = { path: b.path, title: title, cover: cover, percent: '' };
        if (total > 0) {
          const pct: number = Math.floor((b.page + 1) * 100 / total);
          card.percent = pct + '%';
        }
        cards.push(card);
      }
    }
    return cards;
  }

  /** Home tab (Android hr01/hr03): recents shelf + bookmark cards + favorites + stats + shortcuts + OPDS. */
  @Builder
  buildDashboardTab() {
    Scroll() {
      Column() {
        /* 最近阅读: horizontal cover shelf */
        this.sectionHeader(this.L($r('app.string.sec_recent')), 0)
        Scroll() {
          Row({ space: 12 }) {
            ForEach(this.recentBooks, (book: RecentBook) => {
              this.coverTile(book)
            }, (item: RecentBook) => item.path)
          }
          .padding({ left: 12, right: 12 })
        }
        .scrollable(ScrollDirection.Horizontal)
        .width('100%')

        /* 书签笔记: per-book cards */
        this.sectionHeader(this.L($r('app.string.sec_bookmarks')), 9)
        if (this.bookmarkCards().length === 0) {
          Text($r('app.string.dash_no_bookmarks'))
            .fontSize(12).fontColor('#999')
            .width('94%').padding({ left: 12, top: 2, bottom: 6 })
        } else {
          Scroll() {
            Row({ space: 12 }) {
              ForEach(this.bookmarkCards(), (card: BookmarkCard) => {
                Row({ space: 10 }) {
                  if (card.cover.length > 0) {
                    Image(card.cover).width(48).height(64).borderRadius(2).objectFit(ImageFit.Cover).backgroundColor('#eee')
                  } else {
                    Column() {
                      Text(card.title.length > 4 ? card.title.substring(0, 4) : card.title)
                        .fontSize(10).fontColor('#fff').maxLines(2).textAlign(TextAlign.Center)
                    }
                    .width(48).height(64).justifyContent(FlexAlign.Center).backgroundColor('#8d7b74').borderRadius(2)
                  }
                  Column({ space: 4 }) {
                    Text(card.title.length > 16 ? card.title.substring(0, 16) + '\u2026' : card.title)
                      .fontSize(14).fontWeight(FontWeight.Medium).fontColor('#333').maxLines(1)
                    Text($r('app.string.quick_bookmark')).fontSize(12).fontColor('#888')
                    Text(card.percent).fontSize(12).fontColor('#999')
                  }
                  .alignItems(HorizontalAlign.Start)
                  .layoutWeight(1)
                }
                .width(250)
                .padding(10)
                .backgroundColor(Color.White)
                .borderRadius(4)
                .shadow({ radius: 2, color: '#22000000', offsetX: 0, offsetY: 1 })
                .onClick(() => {
                  this.activeOverlay = 1;
                })
              }, (card: BookmarkCard) => card.path)
            }
            .padding({ left: 12, right: 12 })
          }
          .scrollable(ScrollDirection.Horizontal)
          .width('100%')
        }

        /* 我的珍藏 */
        if (this.starredBooks.length > 0) {
          this.sectionHeader(this.L($r('app.string.sec_favorites')), 1)
          Scroll() {
            Row({ space: 12 }) {
              ForEach(this.starredBooks, (book: RecentBook) => {
                this.coverTile(book)
              }, (item: RecentBook) => item.path)
            }
            .padding({ left: 12, right: 12 })
          }
          .scrollable(ScrollDirection.Horizontal)
          .width('100%')
        }

        /* 阅读统计: 3 + 2 white cards */
        this.sectionHeader(this.L($r('app.string.sec_stats')), 7)
        Row({ space: 10 }) {
          this.statCard(0)
          this.statCard(1)
          this.statCard(2)
        }
        .width('94%')
        Row({ space: 10 }) {
          this.statCard(3)
          this.statCard(4)
        }
        .width('94%')
        .margin({ top: 10 })

        /* 我的文件: quick circles */
        this.sectionHeader(this.L($r('app.string.tab_my_files')), 2)
        Row({ space: 20 }) {
          if (this.webdavUrl.length > 0) {
            this.shortcutCircle($r('app.media.ic_cloud'), '#4db6ac', 'WebDAV', 2)
          }
          this.shortcutCircle($r('app.media.ic_folder'), '#bdbdbd', 'Download', 2)
        }
        .width('94%')
        .padding({ top: 4, bottom: 6 })

        /* 网上书库: OPDS circles (persisted server list, first 2) */
        this.sectionHeader(this.L($r('app.string.sec_online')), 8)
        Row({ space: 20 }) {
          ForEach(this.opdsServers.slice(0, 2), (s: OpdsServer) => {
            Column({ space: 6 }) {
              Column() {
                Text('OPDS')
                  .fontSize(12).fontWeight(FontWeight.Bold).fontColor(Color.White)
              }
              .width(64).height(64)
              .justifyContent(FlexAlign.Center)
              .backgroundColor($r('app.color.brand_primary'))
              .borderRadius(32)
              Text(s.name.length > 8 ? s.name.substring(0, 8) + '\u2026' : s.name)
                .fontSize(12).fontColor('#666').maxLines(1)
            }
            .onClick(() => {
              this.activeOverlay = 2;
            })
          }, (s: OpdsServer) => s.url)
        }
        .width('94%')
        .padding({ top: 4, bottom: 24 })
      }
      .width('100%')
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#f0f2f5')
    .scrollBar(BarState.Off)
  }

  /** 阅读统计 chart dialog (Android hr02): range tabs + bar chart + 确认. */
  @Builder
  buildStatsChartDialog() {
    Stack({ alignContent: Alignment.Center }) {
      Column()
        .width('100%').height('100%')
        .backgroundColor('#66000000')
        .onClick(() => {
          this.showStatsChart = false;
        })
      Column({ space: 14 }) {
        Row({ space: 28 }) {
          ForEach([$r('app.string.range_week'), $r('app.string.range_month'), $r('app.string.range_year')], (label: string, idx: number) => {
            Column({ space: 4 }) {
              Text(label)
                .fontSize(15)
                .fontColor(this.chartRange === idx ? $r('app.color.brand_primary') : '#666')
              Divider()
                .width(label.length * 15 + 8)
                .strokeWidth(2)
                .color(this.chartRange === idx ? $r('app.color.brand_primary') : Color.Transparent)
            }
            .onClick(() => {
              this.chartRange = idx;
              this.updateChartData();
            })
          }, (label: string, idx: number) => `rng_${idx}`)
        }
        .width('100%')
        this.buildBarChart()
        Row() {
          Blank()
          Text($r('app.string.common_confirm'))
            .fontSize(16).fontColor('#00897B')
            .padding({ left: 20, top: 6, bottom: 2 })
            .onClick(() => {
              this.showStatsChart = false;
            })
        }
        .width('100%')
      }
      .width('86%')
      .padding(16)
      .backgroundColor(Color.White)
      .borderRadius(8)
    }
    .width('100%').height('100%')
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
      /* search + tools row (Android hr04: white search box on brand blue + sort/refresh/view) */
      Column({ space: 8 }) {
        Row({ space: 8 }) {
          Row({ space: 6 }) {
            Image($r('app.media.ic_search'))
              .width(18).height(18).fillColor('#999')
            TextInput({ placeholder: $r('app.string.common_search'), text: this.libQuery })
              .fontSize(14).height(38).layoutWeight(1)
              .backgroundColor(Color.Transparent)
              .padding(0)
              .onChange((v: string) => {
                this.libQuery = v;
              })
            if (this.selMode) {
              Button($r('app.string.common_cancel')).fontSize(12).height(28).backgroundColor($r('app.color.brand_primary')).fontColor(Color.White)
                .onClick(() => {
                  this.exitSel();
                })
            } else {
              Text('' + this.getLibraryBooks().length)
                .fontSize(14).fontColor($r('app.color.brand_primary'))
            }
          }
          .layoutWeight(1).height(38)
          .backgroundColor(Color.White)
          .borderRadius(3)
          .padding({ left: 8, right: 8 })

          /* Round 6: full-text search trigger (tap again to cancel) */
          Column({ space: 2 }) {
            Text($r('app.string.lib_fulltext')).fontSize(13).fontColor(Color.White)
            if (this.libSearchBusy) {
              Text(this.libSearchProgress).fontSize(9).fontColor('#c5cae9')
            }
          }
          .height(38).justifyContent(FlexAlign.Center).padding({ left: 4, right: 4 })
          .onClick(() => {
            if (this.libSearchBusy) {
              this.cancelLibSearch();
            } else {
              this.runLibSearch();
            }
          })

          /* Round 6: device scan + import */
          Image($r('app.media.ic_import'))
            .width(20).height(20).fillColor(Color.White).margin({ right: 4 })
            .onClick(() => {
              this.runDeviceScan();
            })

          /* sort: label + chevron */
          Row({ space: 2 }) {
            Text(this.sortName(this.libSortMode))
              .fontSize(13).fontColor(Color.White)
            Image($r('app.media.ic_chevron_up'))
              .width(14).height(14).fillColor(Color.White)
          }
          .height(38)
          .padding({ left: 8, right: 4 })
          .onClick(() => {
            this.libSortMode = (this.libSortMode + 1) % 5;
          })

          Image($r('app.media.ic_refresh'))
            .width(20).height(20).fillColor(Color.White)
            .onClick(() => {
              this.loadLibrary();
            })

          Image($r('app.media.ic_shelf'))
            .width(22).height(22).fillColor(Color.White)
            .onClick(() => {
              this.libViewMode = (this.libViewMode + 1) % 5;
            })
        }
        .width('100%')
      }
      .width('100%')
      .padding({ left: 8, right: 8, top: 6, bottom: 8 })
      .backgroundColor($r('app.color.brand_primary'))

      /* status chips: 全部 / 未读 / 在读 / 已读 (white chip + blue border, selected = blue bg) */
      Scroll() {
        Row({ space: 8 }) {
          ForEach([$r('app.string.chip_all'), $r('app.string.chip_unread'), $r('app.string.chip_reading'), $r('app.string.chip_finished')], (label: string, idx: number) => {
            Text(label)
              .fontSize(13)
              .fontColor(this.libStatusFilter === idx - 1 ? Color.White : $r('app.color.brand_primary'))
              .height(28)
              .padding({ left: 14, right: 14 })
              .borderRadius(3)
              .backgroundColor(this.libStatusFilter === idx - 1 ? $r('app.color.brand_primary') : Color.White)
              .border({ width: 1, color: this.libStatusFilter === idx - 1 ? $r('app.color.brand_primary') : '#9FA8DA' })
              .onClick(() => {
                this.libStatusFilter = this.libStatusFilter === idx - 1 ? -1 : idx - 1;
              })
          }, (label: string, idx: number) => `chip_${idx}`)
          Text(this.libStarFilter ? $r('app.string.chip_star_on') : $r('app.string.chip_star_off'))
            .fontSize(13)
            .fontColor(this.libStarFilter ? Color.White : $r('app.color.brand_primary'))
            .height(28)
            .padding({ left: 14, right: 14 })
            .borderRadius(3)
            .backgroundColor(this.libStarFilter ? $r('app.color.brand_primary') : Color.White)
            .border({ width: 1, color: this.libStarFilter ? $r('app.color.brand_primary') : '#9FA8DA' })
            .onClick(() => {
              this.libStarFilter = !this.libStarFilter;
              console.info(`[Index] Library star filter: ${this.libStarFilter}`);
            })
          Text($r('app.string.chip_playlist'))
            .fontSize(13)
            .fontColor($r('app.color.brand_primary'))
            .height(28)
            .padding({ left: 14, right: 14 })
            .borderRadius(3)
            .backgroundColor(Color.White)
            .border({ width: 1, color: '#9FA8DA' })
            .onClick(() => {
              this.openPlManage();
            })
        }
      }
      .scrollable(ScrollDirection.Horizontal)
      .width('94%').margin({ top: 8 })

      /* Round 6: missing-files cleanup banner */
      if (this.missingCount > 0) {
        Text(`\u26A0 ${this.missingCount} \u672C\u4E66\u6587\u4EF6\u5DF2\u4E22\u5931\uFF0C\u70B9\u51FB\u6E05\u7406\u4E66\u5E93\u8BB0\u5F55`)
          .fontSize(12).fontColor('#b71c1c')
          .height(30).padding({ left: 12, right: 12 }).margin({ top: 6 })
          .borderRadius(3).backgroundColor('#ffcdd2')
          .onClick(() => {
            this.cleanupMissing();
          })
      }

      /* Round 6: full-text search progress + results panel */
      if (this.libSearchBusy || this.libSearchHits.length > 0) {
        Column({ space: 4 }) {
          Row({ space: 8 }) {
            Text(this.libSearchBusy ? `\u5168\u6587\u641C\u7D22\u4E2D ${this.libSearchProgress}` : `\u5168\u6587\u641C\u7D22\u7ED3\u679C\uFF1A${this.libSearchHits.length} \u5904`)
              .fontSize(12).fontColor('#555').layoutWeight(1)
            if (this.libSearchBusy) {
              Text($r('app.string.common_cancel')).fontSize(12).fontColor($r('app.color.brand_primary'))
                .onClick(() => {
                  this.cancelLibSearch();
                })
            } else {
              Text($r('app.string.common_close')).fontSize(12).fontColor('#999')
                .onClick(() => {
                  this.libSearchHits = [];
                })
            }
          }
          .width('100%')

          List() {
            ForEach(this.libSearchHits, (hit: LibSearchHit) => {
              ListItem() {
                Column({ space: 2 }) {
                  Text(`${hit.title} \u00B7 \u7B2C ${hit.page + 1} \u9875`).fontSize(12).fontColor('#333')
                  Text(hit.excerpt).fontSize(11).fontColor('#888').maxLines(2)
                }
                .alignItems(HorizontalAlign.Start)
                .width('100%').padding({ top: 6, bottom: 6, left: 8, right: 8 })
                .onClick(() => {
                  this.openLibHit(hit);
                })
              }
            }, (hit: LibSearchHit) => hit.path + '_' + hit.page)
          }
          .height(180)
          .divider({ strokeWidth: 1, color: '#f0f0f0', startMargin: 8, endMargin: 8 })
        }
        .width('94%').margin({ top: 6 }).padding(8)
        .backgroundColor(Color.White).borderRadius(6)
      }

      /* batch selection bar */
      if (this.selMode) {
        Row({ space: 6 }) {
          Text(this.selSet.length + ' \u9879\u5DF2\u9009').fontSize(12).fontColor($r('app.color.brand_primary'))
          Button($r('app.string.lib_select_all')).fontSize(11).height(26).backgroundColor($r('app.color.brand_primary')).fontColor(Color.White)
            .onClick(() => {
              this.selectAll();
            })
          Button($r('app.string.chip_finished')).fontSize(11).height(26).backgroundColor('#e0e0e0').fontColor('#333')
            .onClick(() => {
              this.batchMarkStatus(2);
            })
          Button($r('app.string.chip_unread')).fontSize(11).height(26).backgroundColor('#e0e0e0').fontColor('#333')
            .onClick(() => {
              this.batchMarkStatus(0);
            })
          Button($r('app.string.chip_reading')).fontSize(11).height(26).backgroundColor('#e0e0e0').fontColor('#333')
            .onClick(() => {
              this.batchMarkStatus(1);
            })
        }
        .width('94%').margin({ top: 6 })
      }

      /* content by view mode */
      if (this.getLibraryBooks().length === 0) {
        Text($r('app.string.lib_empty_import'))
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
        /* grid / cover / bookshelf (wood shelf background, Android hr04) */
        Scroll() {
          Stack({ alignContent: Alignment.TopStart }) {
            Image($r('app.media.bg_wood'))
              .width('100%').height('100%')
              .objectFit(ImageFit.Cover)
            Column() {
              Grid() {
                ForEach(this.getLibraryBooks(), (book: RecentBook) => {
                  GridItem() {
                    this.LibGridCell(book)
                  }
                }, (item: RecentBook) => `${item.path}_${item.page}_${item.status}_${item.star}`)
              }
              .columnsTemplate(this.coverColsTemplate())
              .columnsGap(8).rowsGap(12)
              .width('94%')
              .padding({ top: 4 })
            }
            .width('100%')
            .padding({ top: 4, bottom: 16 })
          }
          .width('100%')
        }
        .layoutWeight(1).width('100%')
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
      /* Round 6: hide fully-read books (pref hideReadBooks) */
      if (this.settings.hideReadBooks && b.status === 2) {
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
    return mode === 0 ? this.L($r('app.string.sort_recent')) : mode === 1 ? this.L($r('app.string.common_name')) : mode === 2 ? this.L($r('app.string.sort_date')) : mode === 3 ? this.L($r('app.string.sort_author')) : this.L($r('app.string.sort_series'));
  }

  /** Round 6: grid columns template from the coverColumns pref (2..4). */
  coverColsTemplate(): string {
    const n: number = Math.max(2, Math.min(4, this.settings.coverColumns));
    let t: string = '';
    for (let i = 0; i < n; i++) {
      t = t + (i > 0 ? ' ' : '') + '1fr';
    }
    return t;
  }

  /** Round 6: apply the preferred screen orientation (auto/portrait/landscape). */
  applyOrientation(): void {
    window.getLastWindow(getContext(this)).then((win: window.Window) => {
      const o: number = this.settings.screenOrientation;
      const target = o === 1 ? window.Orientation.PORTRAIT
        : o === 2 ? window.Orientation.LANDSCAPE
        : window.Orientation.UNSPECIFIED;
      win.setPreferredOrientation(target).then(() => {
        console.info(`[Index] Orientation set to ${o}`);
      }).catch((err: Error) => {
        console.error('[Index] setPreferredOrientation failed: ' + JSON.stringify(err));
      });
    }).catch((err: Error) => {
      console.error('[Index] getLastWindow failed: ' + JSON.stringify(err));
    });
  }

  viewName(mode: number): string {
    return mode === 0 ? this.L($r('app.string.view_list')) : mode === 1 ? this.L($r('app.string.view_compact')) : mode === 2 ? this.L($r('app.string.view_grid')) : mode === 3 ? this.L($r('app.string.view_cover')) : this.L($r('app.string.view_shelf'));
  }

  statusLabel(s: number): string {
    return s === 0 ? this.L($r('app.string.chip_unread')) : s === 1 ? this.L($r('app.string.chip_reading')) : this.L($r('app.string.chip_finished'));
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
          .fontSize(20).fontColor($r('app.color.brand_primary')).width(28).textAlign(TextAlign.Center)
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
            .width('100%').height(3).color($r('app.color.brand_primary')).backgroundColor('#e0e0e0')
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

  /** Library grid / cover / bookshelf cell (Android hr04: cover + progress/star/more overlay). */
  @Builder
  LibGridCell(book: RecentBook) {
    Column({ space: 4 }) {
      Stack({ alignContent: Alignment.Bottom }) {
        /* shelf plank under the book (hr04 bookshelf) */
        Column()
          .width('108%').height(12)
          .backgroundColor('#4e342e')
          .borderRadius(2)
          .shadow({ radius: 6, color: '#77000000', offsetX: 0, offsetY: 4 })
          .offset({ y: 6 })
        if (book.coverPath.length > 0) {
          Image(book.coverPath)
            .width('100%').aspectRatio(0.72)
            .objectFit(ImageFit.Cover).backgroundColor('#eee')
        } else {
          Column() {
            Text(book.title.length > 8 ? book.title.substring(0, 8) : book.title)
              .fontSize(14).fontColor('#fff').maxLines(3).textAlign(TextAlign.Center)
          }
          .width('100%').aspectRatio(0.72)
          .justifyContent(FlexAlign.Center).padding(6).backgroundColor('#8d7b74')
        }
        if (!this.selMode) {
          Row({ space: 6 }) {
            if (book.totalPages > 0) {
              Text('' + Math.floor((book.page + 1) * 100 / book.totalPages) + '%')
                .fontSize(11).fontColor(book.status === 2 ? '#7cb342' : '#ffffff')
                .backgroundColor('#66000000')
                .width(44).height(44).borderRadius(22).textAlign(TextAlign.Center)
            }
            Blank()
            Column() {
              Image(book.star ? $r('app.media.ic_star') : $r('app.media.ic_star_border'))
                .width(20).height(20).fillColor(Color.White)
            }
            .width(36).height(36).justifyContent(FlexAlign.Center)
            .backgroundColor('#66000000').borderRadius(18)
            .onClick(() => {
              this.onToggleStar(book);
            })
            Column() {
              Image($r('app.media.ic_more_vert'))
                .width(20).height(20).fillColor(Color.White)
            }
            .width(36).height(36).justifyContent(FlexAlign.Center)
            .backgroundColor('#66000000').borderRadius(18)
            .onClick(() => {
              this.libMenuPath = book.path;
              console.info(`[Index] Book menu: ${book.title}`);
            })
          }
          .width('100%')
          .padding({ left: 6, right: 6, bottom: 6 })
          .onClick(() => {
            /* swallow taps on the overlay row so the cover tap doesn't fire */
          })
        }
      }
      .width('100%')
      .borderRadius(3)
      .clip(true)
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
      if (this.libViewMode === 2) {
        Text(book.title.length > 10 ? book.title.substring(0, 10) + '\u2026' : book.title)
          .fontSize(11).fontColor('#333').maxLines(1)
      }
    }
  }

  /* ---- Phase 10: AI 大模型接入 (Android hr08) ---- */

  private saveAiDialog(): void {
    if (this.aiCfg.maxTokens <= 0) {
      this.aiCfg.maxTokens = 8192;
    }
    saveAiConfig(getContext(this), this.aiCfg).then(() => {
      this.status = this.L($r('app.string.ai_saved'));
      this.showAiDialog = false;
    });
  }

  private pickAiProvider(idx: number): void {
    this.aiCfg.provider = AI_PROVIDERS[idx];
    if (idx < AI_PROVIDER_URLS.length && AI_PROVIDER_URLS[idx].length > 0) {
      this.aiCfg.url = AI_PROVIDER_URLS[idx];
    }
  }

  private fetchAiModels(): void {
    if (this.aiBusy) {
      return;
    }
    this.aiBusy = true;
    this.aiModels = [];
    listModels(this.aiCfg, getContext(this)).then((ids: string[]) => {
      this.aiModels = ids;
      this.aiBusy = false;
      this.status = '\u83B7\u53D6\u5230 ' + ids.length + ' \u4E2A\u6A21\u578B';
    }).catch((e: Object) => {
      this.aiTestOut = '\u83B7\u53D6\u6A21\u578B\u5931\u8D25\uFF1A' + (e instanceof Error ? e.message : String(e));
      this.aiBusy = false;
    });
  }

  private aiTestSend(): void {
    if (this.aiBusy) {
      return;
    }
    this.aiBusy = true;
    this.aiTestOut = '';
    saveAiConfig(getContext(this), this.aiCfg);
    testChat(this.aiCfg, this.aiTestIn, getContext(this)).then((out: string) => {
      this.aiTestOut = out;
      this.aiBusy = false;
    }).catch((e: Object) => {
      this.aiTestOut = '\u6D4B\u8BD5\u5931\u8D25\uFF1A' + (e instanceof Error ? e.message : String(e));
      this.aiBusy = false;
    });
  }

  /** hr08: AI 大模型接入 dialog (provider/url/key/model/tokens/thinking/test). */
  @Builder
  buildAiDialog() {
    Stack({ alignContent: Alignment.Center }) {
      Column()
        .width('100%').height('100%')
        .backgroundColor('#66000000')
        .onClick(() => {
          this.showAiDialog = false;
        })
      Column({ space: 10 }) {
        Row() {
          Text($r('app.string.ai_config_title'))
            .fontSize(16).fontWeight(FontWeight.Bold).fontColor('#333')
            .layoutWeight(1)
          Image($r('app.media.ic_close'))
            .width(20).height(20).fillColor('#888')
            .onClick(() => {
              this.showAiDialog = false;
            })
        }
        .width('100%')

        Row({ space: 8 }) {
          Text($r('app.string.ai_protocol')).fontSize(14).fontColor('#333').width(70)
          Text($r('app.string.ai_openai_compat'))
            .fontSize(14).fontColor($r('app.color.brand_primary'))
        }
        .width('100%')

        Row({ space: 8 }) {
          Text($r('app.string.ai_provider')).fontSize(14).fontColor('#333').width(70)
          ForEach(AI_PROVIDERS, (p: string, idx: number) => {
            Text(p)
              .fontSize(12)
              .fontColor(this.aiCfg.provider === p ? Color.White : $r('app.color.brand_primary'))
              .backgroundColor(this.aiCfg.provider === p ? $r('app.color.brand_primary') : Color.Transparent)
              .border({ width: 1, color: $r('app.color.brand_primary') })
              .borderRadius(10)
              .padding({ left: 8, right: 8, top: 3, bottom: 3 })
              .onClick(() => {
                this.pickAiProvider(idx);
              })
          }, (p: string) => p)
        }
        .width('100%')

        Row({ space: 8 }) {
          Text($r('app.string.ai_url')).fontSize(14).fontColor('#333').width(70)
          TextInput({ text: this.aiCfg.url, placeholder: 'https://...' })
            .fontSize(13).height(38).layoutWeight(1)
            .backgroundColor(Color.White)
            .border({ width: 1, color: '#cccccc' })
            .borderRadius(4)
            .onChange((v: string) => {
              this.aiCfg.url = v;
            })
        }
        .width('100%')

        Row({ space: 8 }) {
          Text($r('app.string.ai_key')).fontSize(14).fontColor('#333').width(70)
          TextInput({ text: this.aiCfg.key, placeholder: 'sk-...' })
            .type(InputType.Password)
            .fontSize(13).height(38).layoutWeight(1)
            .backgroundColor(Color.White)
            .border({ width: 1, color: '#cccccc' })
            .borderRadius(4)
            .onChange((v: string) => {
              this.aiCfg.key = v;
            })
        }
        .width('100%')

        Row({ space: 8 }) {
          Text($r('app.string.ai_model')).fontSize(14).fontColor('#333').width(70)
          TextInput({ text: this.aiCfg.model, placeholder: 'glm-4.5-air' })
            .fontSize(13).height(38).layoutWeight(1)
            .backgroundColor(Color.White)
            .border({ width: 1, color: '#cccccc' })
            .borderRadius(4)
            .onChange((v: string) => {
              this.aiCfg.model = v;
            })
          Text($r('app.string.ai_list_models'))
            .fontSize(13).fontColor('#00897B')
            .onClick(() => {
              this.fetchAiModels();
            })
        }
        .width('100%')

        if (this.aiModels.length > 0) {
          Scroll() {
            Column() {
              ForEach(this.aiModels, (m: string) => {
                Text(m)
                  .fontSize(13).fontColor('#333')
                  .width('100%')
                  .padding({ top: 6, bottom: 6 })
                  .onClick(() => {
                    this.aiCfg.model = m;
                    this.aiModels = [];
                  })
              }, (m: string) => m)
            }
            .width('100%')
          }
          .width('100%')
          .height(110)
          .backgroundColor('#f5f5f5')
          .borderRadius(4)
        }

        Row({ space: 8 }) {
          Text($r('app.string.ai_max_tokens')).fontSize(14).fontColor('#333').width(70)
          TextInput({ text: this.aiCfg.maxTokens + '' })
            .type(InputType.Number)
            .fontSize(13).height(38).width(120)
            .backgroundColor(Color.White)
            .border({ width: 1, color: '#cccccc' })
            .borderRadius(4)
            .onChange((v: string) => {
              this.aiCfg.maxTokens = parseInt(v.length > 0 ? v : '0');
            })
          Blank()
          Row({ space: 6 }) {
            Text($r('app.string.ai_thinking')).fontSize(13).fontColor('#333')
            Checkbox({ name: 'thinking' })
              .select(this.aiCfg.thinking)
              .selectedColor($r('app.color.brand_primary'))
              .width(18).height(18)
              .onChange((v: boolean) => {
                this.aiCfg.thinking = v;
              })
          }
        }
        .width('100%')

        Text($r('app.string.common_test_connection'))
          .fontSize(13).fontColor('#00897B')
          .width('100%')
          .onClick(() => {
            this.aiTestSend();
          })

        TextInput({ text: this.aiTestIn, placeholder: $r('app.string.ai_test_hint') })
          .fontSize(13).height(38).width('100%')
          .backgroundColor(Color.White)
          .border({ width: 1, color: '#cccccc' })
          .borderRadius(4)
          .onChange((v: string) => {
            this.aiTestIn = v;
          })

        if (this.aiTestOut.length > 0 || this.aiBusy) {
          Text(this.aiBusy ? $r('app.string.ai_requesting') : this.aiTestOut)
            .fontSize(12).fontColor(this.aiTestOut.startsWith(this.L($r('app.string.common_test_failed'))) || this.aiTestOut.startsWith(this.L($r('app.string.ai_models_failed'))) ? '#d32f2f' : '#333')
            .width('100%')
            .maxLines(6)
            .padding(8)
            .backgroundColor('#f5f5f5')
            .borderRadius(4)
        }

        Row({ space: 12 }) {
          Blank()
          Button($r('app.string.common_close'))
            .fontSize(14).height(36)
            .backgroundColor('#e0e0e0').fontColor('#333')
            .onClick(() => {
              this.showAiDialog = false;
            })
          Button($r('app.string.common_save'))
            .fontSize(14).height(36)
            .backgroundColor($r('app.color.brand_primary')).fontColor(Color.White)
            .onClick(() => {
              this.saveAiDialog();
            })
        }
        .width('100%')
      }
      .width('86%')
      .padding(14)
      .backgroundColor(Color.White)
      .borderRadius(8)
    }
    .width('100%').height('100%')
  }

  /** hr19+hr17: AI 简介 — ask the model about the book, show + save as AI note. */
  private startAiIntro(): void {
    const book = this.libMenuBook();
    if (book === undefined) {
      return;
    }
    if (!aiConfigReady(this.aiCfg)) {
      loadAiConfig(getContext(this)).then((c: AiConfig) => {
        this.aiCfg = c;
        if (!aiConfigReady(c)) {
          this.status = this.L($r('app.string.ai_not_configured'));
          this.showAiDialog = true;
          loadAiConfig(getContext(this));
        } else {
          this.aiIntroRun(book);
        }
      });
      return;
    }
    this.aiIntroRun(book);
  }

  private aiIntroRun(book: RecentBook): void {
    this.libMenuPath = '';
    this.aiIntroPath = book.path;
    this.aiIntroTitle = book.title;
    this.aiIntroText = '';
    this.aiIntroBusy = true;
    this.showAiIntro = true;
    const author = book.author.length > 0 ? book.author : $r('app.string.common_unknown');
    const msgs: AiMessage[] = [
      { role: 'system', content: '\u4F60\u662F\u4E13\u4E1A\u7684\u9605\u8BFB\u52A9\u624B\uFF0C\u8BF7\u7528\u7B80\u4F53\u4E2D\u6587\u56DE\u7B54\u3002' },
      {
        role: 'user',
        content: '\u8BF7\u7528\u7B80\u4F53\u4E2D\u6587\u4E3A\u8FD9\u672C\u4E66\u5199\u4E00\u6BB5 150 \u5B57\u5DE6\u53F3\u7684\u7B80\u4ECB\uFF0C\u5E76\u7B80\u8981\u70B9\u8BC4\u3002\u4E66\u540D\uFF1A' + book.title + '\uFF0C\u4F5C\u8005\uFF1A' + author
      }
    ];
    chatCompletion(this.aiCfg, msgs, 1024, getContext(this)).then((out: string) => {
      this.aiIntroText = out;
      this.aiIntroBusy = false;
    }).catch((e: Object) => {
      this.aiIntroText = '\u8BF7\u6C42\u5931\u8D25\uFF1A' + (e instanceof Error ? e.message : String(e));
      this.aiIntroBusy = false;
    });
  }

  private saveAiIntro(): void {
    if (this.aiIntroText.length === 0 || this.aiIntroBusy) {
      return;
    }
    const n: AiNote = {
      path: this.aiIntroPath,
      title: this.aiIntroTitle,
      page: 0,
      date: new Date().toISOString(),
      kind: this.L($r('app.string.ai_intro_title')),
      text: this.aiIntroText
    };
    addNote(getContext(this), n).then((ok: boolean) => {
      promptAction.showToast({ message: ok ? $r('app.string.ai_intro_saved') : $r('app.string.common_save_failed') });
      if (ok) {
        this.showAiIntro = false;
      }
    });
  }

  @Builder
  buildAiIntroDialog() {
    Stack({ alignContent: Alignment.Center }) {
      Column()
        .width('100%').height('100%')
        .backgroundColor('#66000000')
        .onClick(() => {
          this.showAiIntro = false;
        })
      Column({ space: 10 }) {
        Row({ space: 8 }) {
          Image($r('app.media.ic_sparkle'))
            .width(20).height(20).fillColor($r('app.color.brand_primary'))
          Text($r('app.string.ai_intro_book'))
            .fontSize(16).fontWeight(FontWeight.Bold).fontColor('#333')
            .layoutWeight(1)
          Image($r('app.media.ic_close'))
            .width(20).height(20).fillColor('#888')
            .onClick(() => {
              this.showAiIntro = false;
            })
        }
        .width('100%')

        Text(this.aiIntroTitle)
          .fontSize(14).fontColor('#666').maxLines(1)
          .width('100%')

        Scroll() {
          Column() {
            if (this.aiIntroBusy) {
              Row({ space: 8 }) {
                LoadingProgress().width(20).height(20).color($r('app.color.brand_primary'))
                Text($r('app.string.ai_intro_generating')).fontSize(13).fontColor('#777')
              }
            } else {
              Text(this.aiIntroText)
                .fontSize(14).fontColor('#333')
                .width('100%')
            }
          }
          .width('100%')
        }
        .width('100%')
        .height(220)
        .backgroundColor('#f5f5f5')
        .borderRadius(4)
        .padding(8)

        Row({ space: 12 }) {
          Blank()
          Button($r('app.string.common_close'))
            .fontSize(14).height(36)
            .backgroundColor('#e0e0e0').fontColor('#333')
            .onClick(() => {
              this.showAiIntro = false;
            })
          Button($r('app.string.trans_save_note'))
            .fontSize(14).height(36)
            .backgroundColor('#00897B').fontColor(Color.White)
            .opacity(this.aiIntroText.length > 0 && !this.aiIntroBusy ? 1 : 0.4)
            .onClick(() => {
              this.saveAiIntro();
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

  /* ---- Phase 10: WebDAV sync basic edition (hr09/hr09b) ---- */

  private syncTimerId: number = -1;

  /** (Re)arm the scheduled sync interval (round 4 C; only while app alive). */
  private armAutoSync(): void {
    if (this.syncTimerId >= 0) {
      clearInterval(this.syncTimerId);
      this.syncTimerId = -1;
    }
    if (!this.syncCfg.enabled || !this.syncCfg.autoSync || this.syncCfg.autoSyncMin <= 0) {
      return;
    }
    const ms = this.syncCfg.autoSyncMin * 60000;
    this.syncTimerId = setInterval(() => {
      this.syncRunNow();
    }, ms);
    console.info('[Index] Auto-sync armed every ' + this.syncCfg.autoSyncMin + ' min');
  }

  private saveSyncDialog(): void {
    saveSyncConfig(getContext(this), this.syncCfg).then(() => {
      this.armAutoSync();
      this.status = this.L($r('app.string.sync_saved'));
      this.showSyncDialog = false;
    });
  }

  private syncTest(): void {
    if (this.syncBusy) {
      return;
    }
    this.syncBusy = true;
    this.syncTestOut = '';
    saveSyncConfig(getContext(this), this.syncCfg);
    testSyncConnection(this.syncCfg, getContext(this)).then((out: string) => {
      this.syncTestOut = out;
      this.syncBusy = false;
    });
  }

  private syncRunNow(): void {
    if (this.syncBusy) {
      return;
    }
    this.syncBusy = true;
    this.syncTestOut = this.L($r('app.string.sync_running'));
    saveSyncConfig(getContext(this), this.syncCfg);
    runSync(getContext(this), this.syncCfg).then((entry: SyncLogEntry) => {
      this.syncCfg.lastSyncTime = entry.time;
      this.syncCfg.lastUp = entry.up;
      this.syncCfg.lastLinked = entry.linked;
      this.syncCfg.lastMs = entry.ms;
      this.syncTestOut = `\u540C\u6B65\u5B8C\u6210\uFF1A\u5173\u8054 ${entry.linked} \u672C\uFF0C\u4E0A\u4F20 ${entry.up} \u672C\uFF0C\u8017\u65F6 ${entry.ms}ms`;
      this.syncBusy = false;
      this.status = this.L($r('app.string.sync_done'));
    }).catch((e: Object) => {
      this.syncTestOut = '\u540C\u6B65\u5931\u8D25\uFF1A' + (e instanceof Error ? e.message : String(e));
      this.syncBusy = false;
    });
  }

  private openSyncLog(): void {
    getSyncLog(getContext(this)).then((l: SyncLogEntry[]) => {
      this.syncLogs = l;
      this.showSyncLog = true;
    });
  }

  private fmtLogTime(iso: string): string {
    return iso.substring(0, 16).replace('T', ' ');
  }

  /** hr09: WebDAV 同步 dialog (basic: progress + bookmarks, newer-wins). */
  @Builder
  buildSyncDialog() {
    Stack({ alignContent: Alignment.Center }) {
      Column()
        .width('100%').height('100%')
        .backgroundColor('#66000000')
        .onClick(() => {
          this.showSyncDialog = false;
        })
      Scroll() {
        Column({ space: 10 }) {
          Row() {
            Text($r('app.string.sync_title'))
              .fontSize(16).fontWeight(FontWeight.Bold).fontColor('#333')
              .layoutWeight(1)
            Image($r('app.media.ic_close'))
              .width(20).height(20).fillColor('#888')
              .onClick(() => {
                this.showSyncDialog = false;
              })
          }
          .width('100%')

          Row({ space: 6 }) {
            Checkbox({ name: 'syncEnabled' })
              .width(18).height(18)
              .selectedColor($r('app.color.brand_primary'))
              .select(this.syncCfg.enabled)
              .onChange((v: boolean) => {
                this.syncCfg.enabled = v;
              })
            Text($r('app.string.sync_enable')).fontSize(13).fontColor('#333')
          }
          .width('100%')

          TextInput({ text: this.syncCfg.url, placeholder: $r('app.string.sync_addr_hint') })
            .fontSize(13).height(38).width('100%')
            .backgroundColor(Color.White)
            .border({ width: 1, color: '#cccccc' })
            .borderRadius(4)
            .onChange((v: string) => {
              this.syncCfg.url = v;
            })
          TextInput({ text: this.syncCfg.user, placeholder: $r('app.string.common_account') })
            .fontSize(13).height(38).width('100%')
            .backgroundColor(Color.White)
            .border({ width: 1, color: '#cccccc' })
            .borderRadius(4)
            .onChange((v: string) => {
              this.syncCfg.user = v;
            })
          TextInput({ text: this.syncCfg.password, placeholder: $r('app.string.common_password') })
            .type(InputType.Password)
            .fontSize(13).height(38).width('100%')
            .backgroundColor(Color.White)
            .border({ width: 1, color: '#cccccc' })
            .borderRadius(4)
            .onChange((v: string) => {
              this.syncCfg.password = v;
            })

          Row({ space: 6 }) {
            Checkbox({ name: 'syncTrust' })
              .width(18).height(18)
              .selectedColor($r('app.color.brand_primary'))
              .select(this.syncCfg.trusted)
              .onChange((v: boolean) => {
                this.syncCfg.trusted = v;
              })
            Text($r('app.string.sync_trust_selfsigned')).fontSize(13).fontColor('#333')
          }
          .width('100%')

          Row({ space: 8 }) {
            Text($r('app.string.sync_path')).fontSize(14).fontColor('#333').width(70)
            TextInput({ text: this.syncCfg.remotePath })
              .fontSize(13).height(38).layoutWeight(1)
              .backgroundColor(Color.White)
              .border({ width: 1, color: '#cccccc' })
              .borderRadius(4)
              .onChange((v: string) => {
                this.syncCfg.remotePath = v;
              })
          }
          .width('100%')

          Row({ space: 8 }) {
            Text($r('app.string.sync_conflict')).fontSize(14).fontColor('#333').width(70)
            Select(SYNC_CONFLICTS.map((v: string) => {
              const o: SelectOption = { value: v };
              return o;
            }))
              .selected(SYNC_CONFLICTS.indexOf(this.syncCfg.conflict) < 0 ? 0 : SYNC_CONFLICTS.indexOf(this.syncCfg.conflict))
              .value(this.syncCfg.conflict)
              .font({ size: 13 })
              .layoutWeight(1)
              .onSelect((idx: number) => {
                this.syncCfg.conflict = SYNC_CONFLICTS[idx];
              })
          }
          .width('100%')

          Row({ space: 6 }) {
            Checkbox({ name: 'syncAuto' })
              .width(18).height(18)
              .selectedColor($r('app.color.brand_primary'))
              .select(this.syncCfg.autoSync)
              .onChange((v: boolean) => {
                this.syncCfg.autoSync = v;
              })
            Text($r('app.string.sync_auto')).fontSize(13).fontColor('#333')
          }
          .width('100%')

          if (this.syncCfg.autoSync) {
            Row({ space: 8 }) {
              Text($r('app.string.sync_interval')).fontSize(14).fontColor('#333').width(70)
              TextInput({ text: '' + this.syncCfg.autoSyncMin })
                .fontSize(13).height(38).layoutWeight(1)
                .backgroundColor(Color.White)
                .border({ width: 1, color: '#cccccc' })
                .borderRadius(4)
                .type(InputType.Number)
                .onChange((v: string) => {
                  const n = parseInt(v);
                  this.syncCfg.autoSyncMin = n > 0 ? n : 5;
                })
            }
            .width('100%')
          }

          Row({ space: 16 }) {
            Text($r('app.string.common_test_connection'))
              .fontSize(13).fontColor('#00897B')
              .onClick(() => {
                this.syncTest();
              })
            Text($r('app.string.sync_log'))
              .fontSize(13).fontColor('#00897B')
              .onClick(() => {
                this.openSyncLog();
              })
            Text($r('app.string.sync_now'))
              .fontSize(13).fontColor('#00897B')
              .onClick(() => {
                this.syncRunNow();
              })
          }
          .width('100%')

          if (this.syncTestOut.length > 0) {
            Text(this.syncTestOut)
              .fontSize(12).fontColor('#333')
              .width('100%')
              .maxLines(4)
              .padding(8)
              .backgroundColor('#f5f5f5')
              .borderRadius(4)
          }

          if (this.syncCfg.lastSyncTime.length > 0) {
            Text(`\u4E0A\u6B21\u540C\u6B65\uFF1A${this.fmtLogTime(this.syncCfg.lastSyncTime)} \u00B7 \u2191${this.syncCfg.lastUp}\u672C \u00B7 \u5173\u8054${this.syncCfg.lastLinked} \u00B7 ${this.syncCfg.lastMs}ms`)
              .fontSize(12).fontColor('#666')
              .width('100%')
            Text($r('app.string.sync_independent'))
              .fontSize(11).fontColor('#999')
              .width('100%')
          }

          Row({ space: 12 }) {
            Blank()
            Button($r('app.string.common_close'))
              .fontSize(14).height(36)
              .backgroundColor('#e0e0e0').fontColor('#333')
              .onClick(() => {
                this.showSyncDialog = false;
              })
            Button($r('app.string.common_save'))
              .fontSize(14).height(36)
              .backgroundColor($r('app.color.brand_primary')).fontColor(Color.White)
              .onClick(() => {
                this.saveSyncDialog();
              })
          }
          .width('100%')
        }
        .width('86%')
        .padding(14)
        .backgroundColor(Color.White)
        .borderRadius(8)
      }
      .width('100%').height('100%')
    }
    .width('100%').height('100%')
  }

  /** hr09b: 同步日志 page (reverse chronological, per-book details). */
  @Builder
  buildSyncLogDialog() {
    Stack({ alignContent: Alignment.Center }) {
      Column()
        .width('100%').height('100%')
        .backgroundColor('#66000000')
        .onClick(() => {
          this.showSyncLog = false;
        })
      Column({ space: 10 }) {
        Row() {
          Text($r('app.string.sync_log'))
            .fontSize(16).fontWeight(FontWeight.Bold).fontColor('#333')
            .layoutWeight(1)
          Image($r('app.media.ic_close'))
            .width(20).height(20).fillColor('#888')
            .onClick(() => {
              this.showSyncLog = false;
            })
        }
        .width('100%')

        if (this.syncLogs.length === 0) {
          Text($r('app.string.sync_log_empty'))
            .fontSize(13).fontColor('#999')
            .width('100%')
            .textAlign(TextAlign.Center)
            .padding({ top: 30, bottom: 30 })
        } else {
          List({ space: 8 }) {
            ForEach(this.syncLogs, (e: SyncLogEntry) => {
              ListItem() {
                Column({ space: 4 }) {
                  Text(`${this.fmtLogTime(e.time)} \u00B7 \u2191${e.up}\u672C \u00B7 \u5173\u8054${e.linked} \u00B7 ${e.ms}ms`)
                    .fontSize(12).fontColor($r('app.color.brand_primary'))
                  ForEach(e.details, (d: string, di: number) => {
                    Text(d)
                      .fontSize(11).fontColor('#555')
                      .width('100%')
                  }, (d: string, di: number) => `${e.time}_${di}`)
                }
                .alignItems(HorizontalAlign.Start)
                .width('100%')
                .padding(8)
                .backgroundColor('#f7f7f7')
                .borderRadius(4)
              }
            }, (e: SyncLogEntry) => e.time)
          }
          .width('100%')
          .layoutWeight(1)
        }

        Button($r('app.string.common_close'))
          .fontSize(14).height(36).width('40%')
          .backgroundColor($r('app.color.brand_primary')).fontColor(Color.White)
          .onClick(() => {
            this.showSyncLog = false;
          })
      }
      .width('86%')
      .height('70%')
      .padding(14)
      .backgroundColor(Color.White)
      .borderRadius(8)
    }
    .width('100%').height('100%')
  }

  /** Resolve the book targeted by the per-book menu. */
  private libMenuBook(): RecentBook | undefined {
    for (let i = 0; i < this.recentBooks.length; i++) {
      if (this.recentBooks[i].path === this.libMenuPath) {
        return this.recentBooks[i];
      }
    }
    return undefined;
  }

  /** Per-book actions (Android hr19 subset): status marks / star / delete. */
  @Builder
  buildLibMenuDialog() {
    Stack({ alignContent: Alignment.Center }) {
      Column()
        .width('100%').height('100%')
        .backgroundColor('#66000000')
        .onClick(() => {
          this.libMenuPath = '';
        })
      Column() {
        this.libMenuAiItem()
        Divider().color('#eeeeee')
        this.libMenuPlItem()
        Divider().color('#eeeeee')
        this.libMenuItem($r('app.media.ic_check'), this.L($r('app.string.menu_mark_finished')), 2)
        this.libMenuItem($r('app.media.ic_circle_half'), this.L($r('app.string.menu_mark_reading')), 1)
        this.libMenuItem($r('app.media.ic_circle_empty'), this.L($r('app.string.menu_mark_unread')), 0)
        Divider().color('#eeeeee')
        this.libMenuStarItem()
        Divider().color('#eeeeee')
        this.libMenuDeleteItem()
      }
      .width('78%')
      .padding({ top: 8, bottom: 8 })
      .backgroundColor(Color.White)
      .borderRadius(6)
    }
    .width('100%').height('100%')
  }

  @Builder
  libMenuAiItem() {
    Row({ space: 14 }) {
      Image($r('app.media.ic_sparkle')).width(20).height(20).fillColor($r('app.color.brand_primary'))
      Text($r('app.string.ai_intro_book')).fontSize(15).fontColor('#333')
    }
    .width('100%')
    .padding({ left: 18, right: 18, top: 13, bottom: 13 })
    .onClick(() => {
      this.startAiIntro();
    })
  }

  @Builder
  libMenuPlItem() {
    Row({ space: 14 }) {
      Image($r('app.media.ic_list')).width(20).height(20).fillColor($r('app.color.brand_primary'))
      Text($r('app.string.pl_add')).fontSize(15).fontColor('#333')
    }
    .width('100%')
    .padding({ left: 18, right: 18, top: 13, bottom: 13 })
    .onClick(() => {
      this.plAddPath = this.libMenuPath;
      this.libMenuPath = '';
      this.refreshPlList();
      this.showPlPick = true;
    })
  }

  @Builder
  libMenuItem(icon: Resource, label: string, status: number) {
    Row({ space: 14 }) {
      Image(icon).width(20).height(20).fillColor($r('app.color.brand_primary'))
      Text(label).fontSize(15).fontColor('#333')
    }
    .width('100%')
    .padding({ left: 18, right: 18, top: 13, bottom: 13 })
    .onClick(() => {
      const path: string = this.libMenuPath;
      this.libMenuPath = '';
      setBookStatus(getContext(this), path, status).then(() => {
        return this.loadLibrary();
      });
    })
  }

  @Builder
  libMenuStarItem() {
    Row({ space: 14 }) {
      Image(this.libMenuBook()?.star ? $r('app.media.ic_star_border') : $r('app.media.ic_star'))
        .width(20).height(20).fillColor($r('app.color.brand_primary'))
      Text(this.libMenuBook()?.star ? $r('app.string.menu_unstar') : $r('app.string.menu_star'))
        .fontSize(15).fontColor('#333')
    }
    .width('100%')
    .padding({ left: 18, right: 18, top: 13, bottom: 13 })
    .onClick(() => {
      const path: string = this.libMenuPath;
      this.libMenuPath = '';
      toggleStar(getContext(this), path).then(() => {
        return this.loadLibrary();
      });
    })
  }

  @Builder
  libMenuDeleteItem() {
    Row({ space: 14 }) {
      Image($r('app.media.ic_close')).width(20).height(20).fillColor('#d32f2f')
      Text($r('app.string.common_delete')).fontSize(15).fontColor('#d32f2f')
    }
    .width('100%')
    .padding({ left: 18, right: 18, top: 13, bottom: 13 })
    .onClick(() => {
      const path: string = this.libMenuPath;
      this.libMenuPath = '';
      try {
        fs.unlinkSync(path);
        console.info(`[Index] Deleted book file: ${path}`);
      } catch (e) {
        console.error('[Index] delete book failed');
      }
      this.loadLibrary();
    })
  }

  /* ---- Round 4 B: playlists (Android Playlists.java parity) ---- */

  private refreshPlList(): void {
    this.plList = getAllPlaylists(getContext(this));
  }

  private openPlManage(): void {
    this.plNewName = '';
    this.refreshPlList();
    this.showPlManage = true;
  }

  private plBookTitle(path: string): string {
    for (let i = 0; i < this.recentBooks.length; i++) {
      if (this.recentBooks[i].path === path) {
        return this.recentBooks[i].title;
      }
    }
    const parts = path.replace(/\\/g, '/').split('/');
    return parts[parts.length - 1];
  }

  private openPlItems(name: string): void {
    this.plItemsName = name;
    this.plItems = getPlaylistItems(getContext(this), name);
    this.showPlItems = true;
  }

  private plMoveItem(idx: number, delta: number): void {
    const to = idx + delta;
    if (to < 0 || to >= this.plItems.length) {
      return;
    }
    const tmp = this.plItems[idx];
    this.plItems[idx] = this.plItems[to];
    this.plItems[to] = tmp;
    updatePlaylist(getContext(this), this.plItemsName, this.plItems);
  }

  private plRemoveItem(idx: number): void {
    const kept: string[] = [];
    for (let i = 0; i < this.plItems.length; i++) {
      if (i !== idx) {
        kept.push(this.plItems[i]);
      }
    }
    this.plItems = kept;
    updatePlaylist(getContext(this), this.plItemsName, kept);
  }

  private plOpenPath(path: string): void {
    for (let i = 0; i < this.recentBooks.length; i++) {
      if (this.recentBooks[i].path === path) {
        this.showPlItems = false;
        this.showPlManage = false;
        this.openBook(this.recentBooks[i]);
        return;
      }
    }
    promptAction.showToast({ message: $r('app.string.pl_not_in_library') });
  }

  /** Add-to-playlist picker (from the per-book menu). */
  @Builder
  buildPlaylistPickDialog() {
    Stack({ alignContent: Alignment.Center }) {
      Column()
        .width('100%').height('100%')
        .backgroundColor('#66000000')
        .onClick(() => {
          this.showPlPick = false;
        })
      Column({ space: 10 }) {
        Text($r('app.string.pl_add'))
          .fontSize(16).fontWeight(FontWeight.Bold)
        if (this.plList.length === 0) {
          Text($r('app.string.pl_empty'))
            .fontSize(12).fontColor('#999')
        }
        Scroll() {
          Column() {
            ForEach(this.plList, (p: PlaylistMeta) => {
              Row() {
                Text('\u25B6').fontSize(14).fontColor($r('app.color.brand_primary'))
                Text(p.name)
                  .fontSize(14).fontColor('#333').layoutWeight(1)
                  .margin({ left: 10 }).maxLines(1)
              }
              .width('100%')
              .padding({ top: 10, bottom: 10 })
              .onClick(() => {
                const added = addToPlaylist(getContext(this), p.name, this.plAddPath);
                promptAction.showToast({
                  message: added ? '\u5DF2\u52A0\u5165\u300C' + p.name + '\u300D' : $r('app.string.pl_already')
                });
                this.showPlPick = false;
              })
            }, (p: PlaylistMeta) => p.name)
          }
          .width('100%')
        }
        .constraintSize({ maxHeight: 220 })

        Row({ space: 8 }) {
          TextInput({ placeholder: $r('app.string.pl_new_name'), text: this.plPickName })
            .fontSize(13).height(36).layoutWeight(1)
            .onChange((v: string) => {
              this.plPickName = v;
            })
          Button($r('app.string.pl_new_add'))
            .fontSize(12).height(36)
            .backgroundColor($r('app.color.brand_primary')).fontColor(Color.White)
            .onClick(() => {
              const err = createPlaylist(getContext(this), this.plPickName);
              if (err.length > 0) {
                promptAction.showToast({ message: err });
                return;
              }
              addToPlaylist(getContext(this), this.plPickName.trim(), this.plAddPath);
              promptAction.showToast({ message: $r('app.string.pl_created_added') });
              this.showPlPick = false;
            })
        }
        .width('100%')

        Button($r('app.string.common_cancel'))
          .fontSize(13).height(34).width('60%')
          .backgroundColor('#e0e0e0').fontColor('#333')
          .onClick(() => {
            this.showPlPick = false;
          })
      }
      .width('80%')
      .padding(14)
      .backgroundColor(Color.White)
      .borderRadius(10)
    }
    .width('100%').height('100%')
  }

  /** Playlists management (library chip entry). */
  @Builder
  buildPlManageDialog() {
    Stack({ alignContent: Alignment.Center }) {
      Column()
        .width('100%').height('100%')
        .backgroundColor('#66000000')
        .onClick(() => {
          this.showPlManage = false;
        })
      Column({ space: 10 }) {
        Text($r('app.string.pl_title'))
          .fontSize(16).fontWeight(FontWeight.Bold)
        if (this.plList.length === 0) {
          Text($r('app.string.pl_empty'))
            .fontSize(12).fontColor('#999')
        }
        Scroll() {
          Column() {
            ForEach(this.plList, (p: PlaylistMeta) => {
              Row() {
                Text('\u25B6').fontSize(14).fontColor($r('app.color.brand_primary'))
                Text(p.name)
                  .fontSize(14).fontColor('#333').layoutWeight(1)
                  .margin({ left: 10 }).maxLines(1)
                Text('\u203A').fontSize(16).fontColor('#bbb')
              }
              .width('100%')
              .padding({ top: 10, bottom: 10 })
              .onClick(() => {
                this.showPlManage = false;
                this.openPlItems(p.name);
              })
            }, (p: PlaylistMeta) => p.name)
          }
          .width('100%')
        }
        .constraintSize({ maxHeight: 260 })

        Row({ space: 8 }) {
          TextInput({ placeholder: $r('app.string.pl_new_name'), text: this.plNewName })
            .fontSize(13).height(36).layoutWeight(1)
            .onChange((v: string) => {
              this.plNewName = v;
            })
          Button($r('app.string.common_create'))
            .fontSize(12).height(36)
            .backgroundColor($r('app.color.brand_primary')).fontColor(Color.White)
            .onClick(() => {
              const err = createPlaylist(getContext(this), this.plNewName);
              if (err.length > 0) {
                promptAction.showToast({ message: err });
                return;
              }
              this.plNewName = '';
              this.refreshPlList();
            })
        }
        .width('100%')

        Button($r('app.string.common_close'))
          .fontSize(13).height(34).width('60%')
          .backgroundColor('#e0e0e0').fontColor('#333')
          .onClick(() => {
            this.showPlManage = false;
          })
      }
      .width('80%')
      .padding(14)
      .backgroundColor(Color.White)
      .borderRadius(10)
    }
    .width('100%').height('100%')
  }

  /** One playlist's ordered items (open / reorder / remove). */
  @Builder
  buildPlItemsDialog() {
    Stack({ alignContent: Alignment.Center }) {
      Column()
        .width('100%').height('100%')
        .backgroundColor('#66000000')
        .onClick(() => {
          this.showPlItems = false;
        })
      Column({ space: 10 }) {
        Text('\u25B6 ' + this.plItemsName)
          .fontSize(16).fontWeight(FontWeight.Bold)
          .maxLines(1)
        if (this.plItems.length === 0) {
          Text($r('app.string.pl_no_items'))
            .fontSize(12).fontColor('#999')
        }
        Scroll() {
          Column() {
            ForEach(this.plItems, (path: string, idx: number) => {
              Row({ space: 6 }) {
                Text((idx + 1) + '. ' + this.plBookTitle(path))
                  .fontSize(13).fontColor('#333')
                  .layoutWeight(1)
                  .maxLines(1)
                  .onClick(() => {
                    this.plOpenPath(path);
                  })
                Text('\u2191')
                  .fontSize(14).fontColor($r('app.color.brand_primary')).padding(6)
                  .onClick(() => {
                    this.plMoveItem(idx, -1);
                  })
                Text('\u2193')
                  .fontSize(14).fontColor($r('app.color.brand_primary')).padding(6)
                  .onClick(() => {
                    this.plMoveItem(idx, 1);
                  })
                Text('\u2715')
                  .fontSize(14).fontColor('#e57373').padding(6)
                  .onClick(() => {
                    this.plRemoveItem(idx);
                  })
              }
              .width('100%')
              .padding({ top: 8, bottom: 8 })
            }, (path: string, idx: number) => `${idx}_${path}`)
          }
          .width('100%')
        }
        .constraintSize({ maxHeight: 320 })

        Row({ space: 8 }) {
          Button($r('app.string.pl_play_first'))
            .fontSize(12).height(34).layoutWeight(1)
            .backgroundColor('#00897B').fontColor(Color.White)
            .onClick(() => {
              if (this.plItems.length > 0) {
                this.plOpenPath(this.plItems[0]);
              }
            })
          Button($r('app.string.pl_delete'))
            .fontSize(12).height(34).layoutWeight(1)
            .backgroundColor('#e57373').fontColor(Color.White)
            .onClick(() => {
              deletePlaylist(getContext(this), this.plItemsName);
              this.showPlItems = false;
              this.refreshPlList();
              this.showPlManage = true;
            })
        }
        .width('100%')

        Button($r('app.string.common_close'))
          .fontSize(13).height(34).width('60%')
          .backgroundColor('#e0e0e0').fontColor('#333')
          .onClick(() => {
            this.showPlItems = false;
          })
      }
      .width('84%')
      .padding(14)
      .backgroundColor(Color.White)
      .borderRadius(10)
    }
    .width('100%').height('100%')
  }

  /** Left drawer panel (Android DrawerLayout: banner + nav + quote + bottom bar). */
  @Builder
  buildDrawerPanel() {
    Column() {
      /* banner with brand tagline */
      Column() {
        Text($r('app.string.drawer_tagline'))
          .fontSize(30).fontWeight(FontWeight.Bold).fontColor(Color.White)
          .textAlign(TextAlign.Center)
      }
      .width('100%').height(170)
      .justifyContent(FlexAlign.Center)
      .linearGradient({ angle: 135, colors: [[$r('app.color.brand_primary'), 0.0], ['#5C6BC0', 1.0]] })

      /* nav items: 最近阅读 / 书库 / 我的文件 / 网上书库 / 书签笔记 */
      Column({ space: 0 }) {
        this.drawerNavItem($r('app.media.ic_toc'), this.L($r('app.string.sec_recent')), 0)
        this.drawerNavItem($r('app.media.ic_book_open'), this.L($r('app.string.tab_library')), 1)
        this.drawerNavItem($r('app.media.ic_folder'), this.L($r('app.string.tab_my_files')), 2)
        this.drawerNavItem($r('app.media.ic_globe'), this.L($r('app.string.sec_online')), 3)
        this.drawerNavItem($r('app.media.ic_bookmark'), this.L($r('app.string.sec_bookmarks')), 4)
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
        this.drawerBottomButton($r('app.media.ic_settings'), this.L($r('app.string.drawer_settings')), 0)
        this.drawerBottomButton($r('app.media.ic_info'), this.L($r('app.string.drawer_about')), 1)
        this.drawerBottomButton($r('app.media.ic_brightness'), this.L($r('app.string.drawer_night')), 2)
        this.drawerBottomButton($r('app.media.ic_close'), this.L($r('app.string.common_exit')), 3)
      }
      .width('100%')
    }
    .width(280)
    .height('100%')
    .backgroundColor(this.drawerBg())
  }

  /** One drawer nav row. target: 0/1=library, 2=my files, 3=network, 4=bookmarks. */
  @Builder
  drawerNavItem(icon: Resource, label: string, target: number) {
    Row({ space: 10 }) {
      Image(icon).width(20).height(20).fillColor(this.settings.theme > 0 ? '#ddd' : $r('app.color.brand_primary'))
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
  drawerBottomButton(icon: Resource, label: string, kind: number) {
    Column({ space: 2 }) {
      Image(icon).width(18).height(18).fillColor(this.settings.theme > 0 ? '#ddd' : $r('app.color.brand_primary'))
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
        Text($r('app.string.sec_bookmarks'))
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
        TextInput({ placeholder: $r('app.string.bm_search_placeholder') })
          .fontSize(13).height(34).layoutWeight(1)
          .onChange((value: string) => {
            this.bookmarkFilter = value;
          })
      }
      .width('94%').margin({ top: 6 })
      Scroll() {
        Column({ space: 6 }) {
          if (this.getFilteredBookmarks().length === 0) {
            Text(this.allBookmarks.length === 0 ? $r('app.string.bookmarks_empty_alt') : $r('app.string.bm_no_match'))
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
        Text($r('app.string.sec_online'))
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
        Button(this.netSection === 0 ? $r('app.string.opds_star_title') : $r('app.string.opds_title'))
          .fontSize(12).height(30).layoutWeight(1)
          .backgroundColor(this.netSection === 0 ? $r('app.color.brand_primary') : '#e0e0e0')
          .fontColor(this.netSection === 0 ? Color.White : '#333')
          .onClick(() => {
            this.netSection = 0;
          })
        Button(this.netSection === 1 ? '\u2605 WebDAV' : 'WebDAV')
          .fontSize(12).height(30).layoutWeight(1)
          .backgroundColor(this.netSection === 1 ? $r('app.color.brand_primary') : '#e0e0e0')
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
            ForEach(this.opdsServers, (s: OpdsServer) => {
              Row() {
                Text('\u{1F4D6}').fontSize(18)
                Column({ space: 2 }) {
                  Text(s.name).fontSize(13).fontColor('#333').maxLines(1)
                  Text(s.url).fontSize(9).fontColor('#999').maxLines(1)
                }
                .layoutWeight(1).alignItems(HorizontalAlign.Start).margin({ left: 10 })
                Text('\u203A').fontSize(16).fontColor('#bbb')
              }
              .width('94%').padding(10).backgroundColor('#f5f5f5').borderRadius(8)
              .onClick(() => {
                this.opdsOpen(s.url);
              })
            }, (s: OpdsServer) => s.url)
            Row({ space: 8 }) {
              TextInput({ placeholder: $r('app.string.opds_custom'), text: this.opdsUrl })
                .fontSize(12).height(34).layoutWeight(1)
                .onChange((v: string) => {
                  this.opdsUrl = v;
                })
              Button($r('app.string.common_open'))
                .fontSize(12).height(34).backgroundColor($r('app.color.brand_primary')).fontColor(Color.White)
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
          Button($r('app.string.opds_back'))
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
      Text($r('app.string.dav_cloud_title'))
        .fontSize(13).fontWeight(FontWeight.Bold).fontColor('#333')
        .margin({ top: 10, bottom: 6 })

      TextInput({ placeholder: 'https://example.com/webdav/', text: this.webdavUrl })
        .width('90%').height(36)
        .onChange((v: string) => {
          this.webdavUrl = v;
        })

      Button(this.webdavConnecting ? 'Connecting...' : 'Connect')
        .width('90%').height(34).margin({ top: 8 })
        .backgroundColor($r('app.color.brand_primary')).fontColor(Color.White)
        .onClick(() => {
          this.webdavConnect();
        })

      Text(this.webdavStatus)
        .fontSize(12).fontColor('#666')
        .width('90%').margin({ top: 6 }).textAlign(TextAlign.Center)

      if (this.webdavProgress >= 0) {
        Text(`Downloading ${this.webdavDownloading} ${this.webdavProgress}%`)
          .fontSize(11).fontColor('#3949AB').width('90%')
        Progress({ value: this.webdavProgress, total: 100 })
          .width('90%').height(6).margin({ top: 4 })
      }

      if (this.webdavPath.length > 0) {
        Row() {
          Text($r('app.string.dav_up')).fontSize(13).fontColor('#3949AB')
          Text(this.webdavPath).fontSize(11).fontColor('#888').margin({ left: 10 }).layoutWeight(1).maxLines(1)
        }
        .width('90%').padding({ top: 6, bottom: 6 })
        .onClick(() => {
          this.davUp();
        })
      }

      if (this.webdavDirs.length > 0 || this.webdavFiles.length > 0) {
        List() {
          ForEach(this.webdavDirs, (dir: string) => {
            ListItem() {
              Row() {
                Text('\u{1F4C1}').fontSize(14).margin({ right: 8 })
                Text(dir).fontSize(13).layoutWeight(1).fontColor('#3949AB')
              }
              .width('90%').padding({ top: 6, bottom: 6 })
              .onClick(() => {
                this.davOpenDir(dir + '/');
              })
            }
          }, (dir: string) => 'd_' + dir)
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
        Text($r('app.string.drawer_about'))
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
        Text($r('app.string.about_brand'))
          .fontSize(20).fontWeight(FontWeight.Bold).fontColor('#333')
        Text($r('app.string.about_version'))
          .fontSize(13).fontColor('#666')
        Text($r('app.string.about_based'))
          .fontSize(12).fontColor('#999')
        Text($r('app.string.about_engine'))
          .fontSize(12).fontColor('#999')
        Text($r('app.string.drawer_tagline'))
          .fontSize(15).fontColor($r('app.color.brand_primary')).margin({ top: 10 })
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
      Text($r('app.string.browse_new_folder'))
        .fontSize(15).fontWeight(FontWeight.Bold).fontColor('#333')
        .margin({ top: 16, bottom: 12 })
      TextInput({ placeholder: $r('app.string.browse_folder_name'), text: this.newFolderName })
        .fontSize(13).height(38).width('86%')
        .onChange((v: string) => {
          this.newFolderName = v;
        })
      Row({ space: 12 }) {
        Button($r('app.string.common_cancel'))
          .fontSize(13).height(36).layoutWeight(1).backgroundColor('#e0e0e0').fontColor('#333')
          .onClick(() => {
            this.showNewFolder = false;
            this.activeOverlay = 0;
          })
        Button($r('app.string.common_create'))
          .fontSize(13).height(36).layoutWeight(1).backgroundColor($r('app.color.brand_primary')).fontColor(Color.White)
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
      Text(this.isBookFile(this.opsFile) ? $r('app.string.browse_books') : $r('app.string.browse_files'))
        .fontSize(10).fontColor('#999').margin({ bottom: 8 })
      Divider().color('#eee')
      Row() {
        Text($r('app.string.browse_open')).fontSize(14).fontColor('#333').layoutWeight(1).padding({ left: 12 })
        Text('\u203A').fontSize(16).fontColor('#ccc').margin({ right: 12 })
      }
      .width('100%').height(46)
      .onClick(() => {
        this.showFileOps = false;
        this.activeOverlay = 0;
        this.openBrowseEntry(this.opsFile);
      })
      Row() {
        Text($r('app.string.browse_rename')).fontSize(14).fontColor('#333').layoutWeight(1).padding({ left: 12 })
        Text('\u203A').fontSize(16).fontColor('#ccc').margin({ right: 12 })
      }
      .width('100%').height(46)
      .onClick(() => {
        this.showRename = true;
        this.activeOverlay = 6;
      })
      Row() {
        Text($r('app.string.browse_delete')).fontSize(14).fontColor('#d32f2f').layoutWeight(1).padding({ left: 12 })
        Text('\u203A').fontSize(16).fontColor('#ccc').margin({ right: 12 })
      }
      .width('100%').height(46)
      .onClick(() => {
        this.deleteEntry();
        this.activeOverlay = 0;
      })
      Divider().color('#eee')
      Button($r('app.string.common_close'))
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
      Text($r('app.string.common_rename'))
        .fontSize(15).fontWeight(FontWeight.Bold).fontColor('#333')
        .margin({ top: 16, bottom: 12 })
      TextInput({ placeholder: $r('app.string.common_new_name'), text: this.renameInput })
        .fontSize(13).height(38).width('86%')
        .onChange((v: string) => {
          this.renameInput = v;
        })
      Row({ space: 12 }) {
        Button($r('app.string.common_cancel'))
          .fontSize(13).height(36).layoutWeight(1).backgroundColor('#e0e0e0').fontColor('#333')
          .onClick(() => {
            this.showRename = false;
            this.activeOverlay = 0;
          })
        Button($r('app.string.common_ok'))
          .fontSize(13).height(36).layoutWeight(1).backgroundColor($r('app.color.brand_primary')).fontColor(Color.White)
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
        Text($r('app.string.about_brand'))
          .fontSize(18).fontWeight(FontWeight.Bold).fontColor(Color.White).margin({ top: 8 })
        Text($r('app.string.lock_title'))
          .fontSize(12).fontColor('#ddd').margin({ top: 4 })
      }
      .margin({ top: 40 })

      TextInput({ placeholder: $r('app.string.lock_placeholder'), text: this.lockPin })
        .fontSize(14).height(42).width('70%')
        .type(InputType.NUMBER_PASSWORD)
        .onChange((v: string) => {
          this.lockPin = v;
        })
        .margin({ top: 30 })

      Button($r('app.string.common_unlock'))
        .fontSize(14).height(40).width('70%').margin({ top: 16 })
        .backgroundColor($r('app.color.brand_primary')).fontColor(Color.White)
        .onClick(() => {
          this.tryUnlock();
        })

      if (this.settings.appLockBiometric && biometricAvailable()) {
        Button($r('app.string.lock_biometric'))
          .fontSize(13).height(38).width('70%').margin({ top: 12 })
          .backgroundColor('#54FFFFFF').fontColor(Color.White)
          .onClick(() => {
            this.tryBiometric();
          })
      }
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
        TextInput({ placeholder: $r('app.string.bm_search_placeholder') })
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
            Text(this.allBookmarks.length === 0 ? $r('app.string.bm_empty_index') : $r('app.string.bm_no_match'))
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
          Button('\u2302').fontSize(14).width(40).height(32).backgroundColor($r('app.color.brand_primary')).fontColor(Color.White)
            .onClick(() => {
              this.browseHome();
            })
          Button('\u2191').fontSize(14).width(40).height(32).backgroundColor('#e0e0e0').fontColor('#333')
            .onClick(() => {
              this.browseUp();
            })
          Text(this.browseCurrentDir.length > 34 ? '\u2026' + this.browseCurrentDir.substring(this.browseCurrentDir.length - 34) : this.browseCurrentDir)
            .fontSize(10).fontColor('#666').maxLines(1).layoutWeight(1)
          Button(this.browseViewMode === 0 ? $r('app.string.browse_grid_btn') : $r('app.string.browse_list_btn'))
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
              Text($r('app.string.browse_empty')).fontSize(12).fontColor('#999').margin({ top: 40 })
            }
          }
          .width('100%')
        }
        .layoutWeight(1).width('100%')
      }

      /* bottom action row */
      Row({ space: 8 }) {
        Button($r('app.string.browse_new_folder'))
          .fontSize(12).height(34).layoutWeight(1).backgroundColor($r('app.color.brand_primary')).fontColor(Color.White)
          .onClick(() => {
            this.showNewFolder = true;
            this.activeOverlay = 4;
          })
        Button($r('app.string.lib_import'))
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

  /** 我的文件 root (Android hr05): OPDS / WebDAV / 书库文件夹 / 搜索 sections. */
  @Builder
  browseSectionHeader(title: string, addKind: number) {
    Row() {
      Text(title)
        .fontSize(16).fontWeight(FontWeight.Medium).fontColor($r('app.color.brand_primary'))
      Blank()
      if (addKind > 0) {
        Row({ space: 2 }) {
          Text('+').fontSize(16).fontColor($r('app.color.brand_primary'))
          Text($r('app.string.common_add')).fontSize(14).fontColor($r('app.color.brand_primary'))
        }
        .onClick(() => {
          if (addKind === 1) {
            this.openOpdsEdit(-1);
          } else if (addKind === 2) {
            this.openDavEdit(-1);
          } else {
            this.openFilePicker();
          }
        })
      }
    }
    .width('94%')
    .padding({ top: 18, bottom: 4 })
  }

  /* ---- Phase 10: OPDS/WebDAV server management (hr05/hr06) ---- */

  private openOpdsEdit(idx: number): void {
    if (idx >= 0 && idx < this.opdsServers.length) {
      const s = this.opdsServers[idx];
      this.opdsEditUrl = s.url;
      this.opdsEditName = s.name;
      this.opdsEditIdx = idx;
    } else {
      this.opdsEditUrl = '';
      this.opdsEditName = '';
      this.opdsEditIdx = -1;
    }
    this.showOpdsEdit = true;
  }

  private saveOpdsEdit(): void {
    if (this.opdsEditUrl.trim().length === 0) {
      this.status = this.L($r('app.string.opds_url_required'));
      return;
    }
    const original: string = this.opdsEditIdx >= 0 && this.opdsEditIdx < this.opdsServers.length
      ? this.opdsServers[this.opdsEditIdx].url : '';
    const s: OpdsServer = {
      url: this.opdsEditUrl.trim(),
      name: this.opdsEditName.trim().length > 0 ? this.opdsEditName.trim() : this.opdsEditUrl.trim()
    };
    saveOpdsServer(getContext(this), s, original).then(() => {
      this.showOpdsEdit = false;
      this.loadServers();
      this.status = this.L($r('app.string.opds_saved'));
    });
  }

  private deleteOpdsServerAt(idx: number): void {
    if (idx < 0 || idx >= this.opdsServers.length) {
      return;
    }
    const url = this.opdsServers[idx].url;
    removeOpdsServer(getContext(this), url).then(() => {
      this.loadServers();
      this.status = this.L($r('app.string.opds_deleted'));
    });
  }

  private openDavEdit(idx: number): void {
    if (idx >= 0 && idx < this.davServers.length) {
      const s = this.davServers[idx];
      this.davEditUrl = s.url;
      this.davEditName = s.name;
      this.davEditUser = s.user;
      this.davEditPass = s.password;
      this.davEditTrusted = s.trusted;
      this.davEditIdx = idx;
    } else {
      this.davEditUrl = 'http://';
      this.davEditName = '';
      this.davEditUser = '';
      this.davEditPass = '';
      this.davEditTrusted = false;
      this.davEditIdx = -1;
    }
    this.showDavEdit = true;
  }

  private saveDavEdit(): void {
    if (this.davEditUrl.trim().length === 0 || this.davEditUrl.trim() === 'http://') {
      this.status = this.L($r('app.string.dav_url_required'));
      return;
    }
    const original: string = this.davEditIdx >= 0 && this.davEditIdx < this.davServers.length
      ? this.davServers[this.davEditIdx].url : '';
    const s: DavServer = {
      url: this.davEditUrl.trim(),
      name: this.davEditName.trim().length > 0 ? this.davEditName.trim() : this.davEditUrl.trim(),
      user: this.davEditUser,
      password: this.davEditPass,
      trusted: this.davEditTrusted
    };
    saveDavServer(getContext(this), s, original).then(() => {
      this.showDavEdit = false;
      this.loadServers();
      this.status = this.L($r('app.string.dav_saved'));
    });
  }

  private deleteDavServerAt(idx: number): void {
    if (idx < 0 || idx >= this.davServers.length) {
      return;
    }
    const url = this.davServers[idx].url;
    removeDavServer(getContext(this), url).then(() => {
      if (this.davSelected === idx) {
        this.davSelected = -1;
      }
      this.loadServers();
      this.status = this.L($r('app.string.dav_deleted'));
    });
  }

  /** hr05: OPDS server row with edit/delete actions. */
  @Builder
  browseOpdsRow(s: OpdsServer) {
    Row({ space: 12 }) {
      Image($r('app.media.ic_globe'))
        .width(24).height(24).fillColor($r('app.color.brand_primary'))
      Column({ space: 2 }) {
        Text(s.name)
          .fontSize(15).fontColor('#555').maxLines(1)
        Text(s.url)
          .fontSize(11).fontColor('#999').maxLines(1)
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)
      Image($r('app.media.ic_pencil'))
        .width(20).height(20).fillColor($r('app.color.brand_primary'))
        .onClick(() => {
          this.openOpdsEdit(this.opdsServers.indexOf(s));
        })
      Image($r('app.media.ic_close'))
        .width(20).height(20).fillColor('#999999')
        .onClick(() => {
          this.deleteOpdsServerAt(this.opdsServers.indexOf(s));
        })
    }
    .width('94%')
    .padding({ left: 12, right: 12, top: 10, bottom: 10 })
    .backgroundColor(Color.White)
    .borderRadius(3)
    .margin({ bottom: 8 })
    .shadow({ radius: 2, color: '#22000000', offsetX: 0, offsetY: 1 })
    .onClick(() => {
      this.netSection = 0;
      this.opdsOpen(s.url);
      this.activeOverlay = 2;
    })
  }

  /** hr05: WebDAV server row with edit/delete; tap = connect + open cloud panel. */
  @Builder
  browseDavRow(s: DavServer, idx: number) {
    Row({ space: 12 }) {
      Image($r('app.media.ic_cloud'))
        .width(24).height(24).fillColor($r('app.color.brand_primary'))
      Column({ space: 2 }) {
        Text(s.name)
          .fontSize(15).fontColor('#555').maxLines(1)
        Text(s.url)
          .fontSize(11).fontColor('#999').maxLines(1)
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)
      Image($r('app.media.ic_pencil'))
        .width(20).height(20).fillColor($r('app.color.brand_primary'))
        .onClick(() => {
          this.openDavEdit(idx);
        })
      Image($r('app.media.ic_close'))
        .width(20).height(20).fillColor('#999999')
        .onClick(() => {
          this.deleteDavServerAt(idx);
        })
    }
    .width('94%')
    .padding({ left: 12, right: 12, top: 10, bottom: 10 })
    .backgroundColor(Color.White)
    .borderRadius(3)
    .margin({ bottom: 8 })
    .shadow({ radius: 2, color: '#22000000', offsetX: 0, offsetY: 1 })
    .onClick(() => {
      this.webdavUrl = s.url;
      this.davSelected = idx;
      this.netSection = 1;
      this.webdavConnect();
      this.activeOverlay = 2;
    })
  }

  /** hr05: OPDS add/edit dialog (URL + name). */
  @Builder
  buildOpdsEditDialog() {
    Stack({ alignContent: Alignment.Center }) {
      Column()
        .width('100%').height('100%')
        .backgroundColor('#66000000')
        .onClick(() => {
          this.showOpdsEdit = false;
        })
      Column({ space: 12 }) {
        Text(this.opdsEditIdx >= 0 ? $r('app.string.opds_edit') : $r('app.string.opds_add'))
          .fontSize(16).fontWeight(FontWeight.Bold).fontColor('#333')
          .width('100%')
        TextInput({ text: this.opdsEditUrl, placeholder: 'https://.../opds/' })
          .fontSize(13).height(38).width('100%')
          .backgroundColor(Color.White)
          .border({ width: 1, color: '#cccccc' })
          .borderRadius(4)
          .onChange((v: string) => {
            this.opdsEditUrl = v;
          })
        TextInput({ text: this.opdsEditName, placeholder: $r('app.string.opds_name_hint') })
          .fontSize(13).height(38).width('100%')
          .backgroundColor(Color.White)
          .border({ width: 1, color: '#cccccc' })
          .borderRadius(4)
          .onChange((v: string) => {
            this.opdsEditName = v;
          })
        Row({ space: 12 }) {
          Blank()
          Button($r('app.string.common_close'))
            .fontSize(14).height(36)
            .backgroundColor('#e0e0e0').fontColor('#333')
            .onClick(() => {
              this.showOpdsEdit = false;
            })
          Button(this.opdsEditIdx >= 0 ? $r('app.string.common_save') : $r('app.string.common_add'))
            .fontSize(14).height(36)
            .backgroundColor($r('app.color.brand_primary')).fontColor(Color.White)
            .onClick(() => {
              this.saveOpdsEdit();
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

  /** hr06: WebDAV add/edit dialog (URL/name/user/password/trust). */
  @Builder
  buildDavEditDialog() {
    Stack({ alignContent: Alignment.Center }) {
      Column()
        .width('100%').height('100%')
        .backgroundColor('#66000000')
        .onClick(() => {
          this.showDavEdit = false;
        })
      Column({ space: 10 }) {
        Text(this.davEditIdx >= 0 ? $r('app.string.dav_edit') : $r('app.string.dav_add'))
          .fontSize(16).fontWeight(FontWeight.Bold).fontColor('#333')
          .width('100%')
        TextInput({ text: this.davEditUrl, placeholder: 'http://' })
          .fontSize(13).height(38).width('100%')
          .backgroundColor(Color.White)
          .border({ width: 1, color: '#cccccc' })
          .borderRadius(4)
          .onChange((v: string) => {
            this.davEditUrl = v;
          })
        TextInput({ text: this.davEditName, placeholder: $r('app.string.dav_name_hint') })
          .fontSize(13).height(38).width('100%')
          .backgroundColor(Color.White)
          .border({ width: 1, color: '#cccccc' })
          .borderRadius(4)
          .onChange((v: string) => {
            this.davEditName = v;
          })
        TextInput({ text: this.davEditUser, placeholder: $r('app.string.common_account') })
          .fontSize(13).height(38).width('100%')
          .backgroundColor(Color.White)
          .border({ width: 1, color: '#cccccc' })
          .borderRadius(4)
          .onChange((v: string) => {
            this.davEditUser = v;
          })
        TextInput({ text: this.davEditPass, placeholder: $r('app.string.common_password') })
          .type(InputType.Password)
          .fontSize(13).height(38).width('100%')
          .backgroundColor(Color.White)
          .border({ width: 1, color: '#cccccc' })
          .borderRadius(4)
          .onChange((v: string) => {
            this.davEditPass = v;
          })
        Row({ space: 6 }) {
          Checkbox({ name: 'davTrust' })
            .width(18).height(18)
            .selectedColor($r('app.color.brand_primary'))
            .select(this.davEditTrusted)
            .onChange((v: boolean) => {
              this.davEditTrusted = v;
            })
          Text($r('app.string.sync_trust_selfsigned')).fontSize(13).fontColor('#333')
        }
        .width('100%')
        Row({ space: 12 }) {
          Blank()
          Button($r('app.string.common_close'))
            .fontSize(14).height(36)
            .backgroundColor('#e0e0e0').fontColor('#333')
            .onClick(() => {
              this.showDavEdit = false;
            })
          Button(this.davEditIdx >= 0 ? $r('app.string.common_save') : $r('app.string.common_add'))
            .fontSize(14).height(36)
            .backgroundColor($r('app.color.brand_primary')).fontColor(Color.White)
            .onClick(() => {
              this.saveDavEdit();
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

  /** Root entry row: leading icon + blue label (OPDS/WebDAV items, Android hr05). */
  @Builder
  browseEntryRow(icon: Resource, label: string) {
    Row({ space: 14 }) {
      Image(icon)
        .width(24).height(24).fillColor($r('app.color.brand_primary'))
      Text(label)
        .fontSize(16).fontColor($r('app.color.brand_primary'))
        .maxLines(1)
        .layoutWeight(1)
    }
    .width('94%')
    .padding({ left: 12, right: 12, top: 12, bottom: 12 })
    .onClick(() => {
      this.netSection = 0;
      this.activeOverlay = 2;
    })
  }

  /** 书库文件夹 row: folder icon + name + star (Android hr05). */
  @Builder
  browseFolderRow(f: LibFolder) {
    Row({ space: 14 }) {
      Image($r('app.media.ic_folder'))
        .width(26).height(26).fillColor($r('app.color.brand_primary'))
      Text(f.name)
        .fontSize(16).fontColor('#555')
        .maxLines(1)
        .layoutWeight(1)
      Image($r('app.media.ic_star_border'))
        .width(20).height(20).fillColor($r('app.color.brand_primary'))
    }
    .width('94%')
    .padding({ left: 12, right: 12, top: 12, bottom: 12 })
    .backgroundColor(Color.White)
    .borderRadius(3)
    .margin({ bottom: 8 })
    .shadow({ radius: 2, color: '#22000000', offsetX: 0, offsetY: 1 })
    .onClick(() => {
      this.enterBrowseAbs(f.path);
    })
  }

  @Builder
  buildBrowseRoot() {
    Scroll() {
      Column() {
        /* 网上书库（OPDS）: persisted server list (hr05) */
        this.browseSectionHeader(this.L($r('app.string.browse_opds')), 1)
        if (this.opdsServers.length === 0) {
          Text($r('app.string.opds_none'))
            .fontSize(13).fontColor('#999')
            .width('94%').padding({ left: 12, bottom: 8 })
        }
        ForEach(this.opdsServers, (s: OpdsServer) => {
          this.browseOpdsRow(s)
        }, (s: OpdsServer) => s.url)

        Divider().color('#e8e8e8').margin({ top: 8 })

        /* WebDAV: persisted server list (hr05/hr06) */
        this.browseSectionHeader('WebDAV', 2)
        if (this.davServers.length === 0) {
          Text($r('app.string.dav_none'))
            .fontSize(13).fontColor('#999')
            .width('94%').padding({ left: 12, bottom: 8 })
        }
        ForEach(this.davServers, (s: DavServer, idx: number) => {
          this.browseDavRow(s, idx)
        }, (s: DavServer) => s.url)

        Divider().color('#e8e8e8').margin({ top: 8 })

        /* 书库文件夹 */
        this.browseSectionHeader(this.L($r('app.string.browse_library_folder')), 3)
        ForEach(this.libFolders, (f: LibFolder) => {
          this.browseFolderRow(f)
        }, (f: LibFolder) => f.path)
        Row({ space: 14 }) {
          Image($r('app.media.ic_book_open'))
            .width(24).height(24).fillColor($r('app.color.brand_primary'))
          Text($r('app.string.browse_imported'))
            .fontSize(16).fontColor('#555')
            .layoutWeight(1)
        }
        .width('94%')
        .padding({ left: 12, right: 12, top: 12, bottom: 12 })
        .onClick(() => {
          this.enterBrowseAbs(this.libFolders.length > 0 ? this.libFolders[0].path : '');
        })

        Divider().color('#e8e8e8').margin({ top: 8 })

        /* 搜索 */
        this.browseSectionHeader(this.L($r('app.string.common_search')), 0)
        Row({ space: 14 }) {
          Image($r('app.media.ic_db_search'))
            .width(24).height(24).fillColor($r('app.color.brand_primary'))
          Text($r('app.string.browse_search_docs'))
            .fontSize(16).fontColor($r('app.color.brand_primary'))
            .layoutWeight(1)
        }
        .width('94%')
        .padding({ left: 12, right: 12, top: 12, bottom: 24 })
        .onClick(() => {
          this.switchTab(1);
        })
      }
      .width('100%')
      .padding({ bottom: 16 })
    }
    .width('100%')
    .layoutWeight(1)
    .scrollBar(BarState.Off)
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

  /** Blue section header bar (Android hr07: 书库设置/常规设置/UI配置). */
  @Builder
  settingsSectionBar(title: string) {
    Text(title)
      .fontSize(15).fontWeight(FontWeight.Medium).fontColor(Color.White)
      .width('100%')
      .padding({ left: 12, top: 9, bottom: 9 })
      .backgroundColor($r('app.color.brand_primary'))
      .borderRadius(2)
  }

  /** Key-value row (Android hr07: label + blue underlined value). */
  @Builder
  settingsKvRow(label: string, value: string) {
    Row() {
      Text(label)
        .fontSize(14).fontColor('#333').width(120)
      Text(value)
        .fontSize(14).fontColor($r('app.color.brand_primary'))
        .decoration({ type: TextDecorationType.Underline, color: $r('app.color.brand_primary') })
        .maxLines(1)
    }
    .width('100%')
    .padding({ left: 12, right: 12, top: 9, bottom: 9 })
  }

  @Builder
  buildSettingsTab() {
    Scroll() {
      Column({ space: 8 }) {
        /* ===== 偏好 sub-bar + profile (Android hr07) ===== */
        Row() {
          Text($r('app.string.tab_prefs'))
            .fontSize(15).fontWeight(FontWeight.Medium).fontColor(Color.White)
            .onClick(() => {
              /* hidden diagnostics: tap the title 3 times to toggle the NAPI self-test */
              this.apiTestTaps++;
              if (this.apiTestTaps >= 3) {
                this.showApiTest = !this.showApiTest;
                this.apiTestTaps = 0;
                console.info(`[Index] API test panel: ${this.showApiTest}`);
              }
            })
          Blank()
          Text($r('app.string.drawer_exit_app'))
            .fontSize(14).fontColor(Color.White)
            .decoration({ type: TextDecorationType.Underline, color: Color.White })
            .onClick(() => {
              (getContext(this) as common.UIAbilityContext).terminateSelf();
              console.info('[Index] Exit app requested from prefs');
            })
        }
        .width('100%')
        .height(40)
        .padding({ left: 12, right: 12 })
        .backgroundColor('#4a5ac0')

        /* 配置文件 row: avatar + name + gear */
        Row({ space: 12 }) {
          Text($r('app.string.drawer_config'))
            .fontSize(14).fontColor('#333')
          Blank()
          Column() {
            Text('H')
              .fontSize(18).fontWeight(FontWeight.Bold).fontColor(Color.White)
          }
          .width(42).height(42)
          .justifyContent(FlexAlign.Center)
          .backgroundColor($r('app.color.brand_primary'))
          .borderRadius(21)
          Text('HowRead')
            .fontSize(15).fontColor($r('app.color.brand_primary'))
            .decoration({ type: TextDecorationType.Underline, color: $r('app.color.brand_primary') })
          Image($r('app.media.ic_settings'))
            .width(22).height(22).fillColor('#00897B')
        }
        .width('100%')
        .padding({ left: 12, right: 12, top: 10, bottom: 2 })

        Text($r('app.string.common_reset'))
          .fontSize(14).fontColor($r('app.color.brand_primary'))
          .decoration({ type: TextDecorationType.Underline, color: $r('app.color.brand_primary') })
          .width('100%')
          .padding({ left: 12, bottom: 4 })
          .onClick(() => {
            this.settings = defaultSettings();
            saveSettings(getContext(this), this.settings);
            this.status = '\u5DF2\u91CD\u7F6E\u4E3A\u9ED8\u8BA4\u8BBE\u7F6E';
            console.info('[Index] Settings reset to defaults');
          })

        /* ===== 常规设置: key-value rows ===== */
        this.settingsSectionBar(this.L($r('app.string.set_general')))
        this.settingsKvRow(this.L($r('app.string.set_screen')), this.L($r('app.string.set_screen_normal')))
        this.settingsKvRow(this.L($r('app.string.set_orientation')), this.L($r('app.string.set_system')))
        this.settingsKvRow(this.L($r('app.string.set_language')), this.L($r('app.string.set_lang_system')))
        Row() {
          Text($r('app.string.sync_title'))
            .fontSize(14).fontColor('#333').width(120)
          Text(this.syncCfg.enabled
            ? (this.syncCfg.lastSyncTime.length > 0 ? '\u4E0A\u6B21\uFF1A' + this.syncCfg.lastSyncTime.substring(0, 16).replace('T', ' ') : $r('app.string.set_enabled'))
            : $r('app.string.set_disabled'))
            .fontSize(14).fontColor($r('app.color.brand_primary'))
            .decoration({ type: TextDecorationType.Underline, color: $r('app.color.brand_primary') })
            .maxLines(1)
            .layoutWeight(1)
        }
        .width('100%')
        .padding({ left: 12, right: 12, top: 9, bottom: 9 })
        .onClick(() => {
          loadSyncConfig(getContext(this)).then((c: SyncConfig) => {
            this.syncCfg = c;
            this.syncTestOut = '';
            this.showSyncDialog = true;
          });
        })
        Row() {
          Text($r('app.string.set_ai_model'))
            .fontSize(14).fontColor('#333').width(120)
          Text(this.aiCfg.model.length > 0 ? this.aiCfg.provider + ' \u00B7 ' + this.aiCfg.model : $r('app.string.set_not_configured'))
            .fontSize(14).fontColor($r('app.color.brand_primary'))
            .decoration({ type: TextDecorationType.Underline, color: $r('app.color.brand_primary') })
            .maxLines(1)
            .layoutWeight(1)
        }
        .width('100%')
        .padding({ left: 12, right: 12, top: 9, bottom: 9 })
        .onClick(() => {
          loadAiConfig(getContext(this)).then((c: AiConfig) => {
            this.aiCfg = c;
            this.aiTestIn = '';
            this.aiTestOut = '';
            this.aiModels = [];
            this.showAiDialog = true;
          });
        })

        /* ===== Reader settings (sprint E) ===== */
        this.settingsSectionBar(this.L($r('app.string.set_reading')))

        // Theme
        Row({ space: 8 }) {
          Text($r('app.string.set_theme'))
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
          Text($r('app.string.set_default_zoom'))
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
          Text($r('app.string.set_page_turn'))
            .fontSize(13).fontColor('#555')
          Button(this.settings.scrollMode === 0 ? $r('app.string.mode_horizontal') : $r('app.string.mode_vertical'))
            .fontSize(12).height(32)
            .backgroundColor('#e0e0e0').fontColor('#333')
            .onClick(() => {
              this.settings.scrollMode = this.settings.scrollMode === 0 ? 1 : 0;
              this.saveNow();
            })
          Button(this.settings.pageMode === 0 ? $r('app.string.page_single') : (this.settings.pageMode === 1 ? $r('app.string.page_double') : $r('app.string.page_music')))
            .fontSize(12).height(32)
            .backgroundColor('#e0e0e0').fontColor('#333')
            .onClick(() => {
              this.settings.pageMode = (this.settings.pageMode + 1) % 3;
              this.saveNow();
            })
        }

        // Brightness
        Row({ space: 8 }) {
          Text($r('app.string.reader_brightness'))
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
          Text($r('app.string.reader_autoflip'))
            .fontSize(13).fontColor('#555')
          Button(this.settings.autoFlip ? $r('app.string.common_on') : $r('app.string.common_off'))
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
          Text($r('app.string.set_tts_rate'))
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
          Text($r('app.string.set_tts_pitch'))
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
          Text($r('app.string.set_rsvp'))
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
        Text($r('app.string.set_reflow_typography'))
          .fontSize(13).fontColor('#555')
          .margin({ top: 4 })

        Row({ space: 8 }) {
          Text($r('app.string.set_font_size'))
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
          Text($r('app.string.set_line_height'))
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
          Text($r('app.string.set_margin'))
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

        /* ===== UI 配置 (Android hr07) ===== */
        this.settingsSectionBar(this.L($r('app.string.set_ui')))
        Row({ space: 12 }) {
          Image($r('app.media.ic_info'))
            .width(22).height(22).fillColor('#00897B')
          Text($r('app.string.drawer_about'))
            .fontSize(15).fontColor('#00897B')
            .decoration({ type: TextDecorationType.Underline, color: '#00897B' })
            .layoutWeight(1)
        }
        .width('100%')
        .padding({ left: 12, right: 12, top: 9, bottom: 9 })
        .onClick(() => {
          this.activeOverlay = 3;
        })

        // ---- Sprint K: System integration settings ----
        this.settingsSectionBar(this.L($r('app.string.set_integration')))

        /* K2: App lock */
        Row({ space: 8 }) {
          Text($r('app.string.set_app_lock'))
            .fontSize(13).fontColor('#555')
          Button(this.settings.appLockEnabled ? $r('app.string.common_on') : $r('app.string.common_off'))
            .fontSize(12).height(32)
            .backgroundColor(this.settings.appLockEnabled ? '#e57373' : '#e0e0e0')
            .fontColor(this.settings.appLockEnabled ? Color.White : '#333')
            .onClick(() => {
              this.settings.appLockEnabled = !this.settings.appLockEnabled;
              this.saveNow();
              console.info(`[Index] App lock: ${this.settings.appLockEnabled}`);
            })
          Text($r('app.string.set_app_lock_hint'))
            .fontSize(10).fontColor('#999')
        }

        /* Phase 6: app lock passcode */
        Row({ space: 8 }) {
          Text($r('app.string.set_lock_pass'))
            .fontSize(13).fontColor('#555')
          TextInput({ placeholder: $r('app.string.set_lock_pass_hint'), text: this.settings.appLockPass })
            .fontSize(12).height(32).layoutWeight(1)
            .onChange((v: string) => {
              this.settings.appLockPass = v;
              this.saveNow();
            })
        }

        /* Round 5: biometric unlock toggle */
        Row({ space: 8 }) {
          Text($r('app.string.pref_biometric'))
            .fontSize(13).fontColor('#555')
          Button(this.settings.appLockBiometric ? $r('app.string.common_on') : $r('app.string.common_off'))
            .fontSize(12).height(32)
            .backgroundColor(this.settings.appLockBiometric && biometricAvailable() ? '#2a7' : '#e0e0e0')
            .fontColor(this.settings.appLockBiometric && biometricAvailable() ? Color.White : '#333')
            .onClick(() => {
              if (!biometricAvailable()) {
                promptAction.showToast({ message: $r('app.string.pref_biometric_na') });
                return;
              }
              this.settings.appLockBiometric = !this.settings.appLockBiometric;
              this.saveNow();
              console.info(`[Index] Biometric unlock: ${this.settings.appLockBiometric}`);
            })
          Text($r('app.string.set_biometric_hint'))
            .fontSize(10).fontColor('#999')
        }

        /* Round 6: screen orientation */
        Row({ space: 8 }) {
          Text($r('app.string.set_orientation2'))
            .fontSize(13).fontColor('#555')
          Button(this.settings.screenOrientation === 0 ? $r('app.string.orient_auto') : (this.settings.screenOrientation === 1 ? $r('app.string.orient_portrait') : $r('app.string.orient_landscape')))
            .fontSize(12).height(32)
            .backgroundColor(this.settings.screenOrientation > 0 ? '#2a7' : '#e0e0e0')
            .fontColor(this.settings.screenOrientation > 0 ? Color.White : '#333')
            .onClick(() => {
              this.settings.screenOrientation = (this.settings.screenOrientation + 1) % 3;
              this.saveNow();
              this.applyOrientation();
              console.info(`[Index] Screen orientation: ${this.settings.screenOrientation}`);
            })
          Text($r('app.string.set_orientation_hint'))
            .fontSize(10).fontColor('#999')
        }

        /* Round 6: auto scan on startup */
        Row({ space: 8 }) {
          Text($r('app.string.set_autoscan'))
            .fontSize(13).fontColor('#555')
          Button(this.settings.autoScanOnStart ? $r('app.string.common_on') : $r('app.string.common_off'))
            .fontSize(12).height(32)
            .backgroundColor(this.settings.autoScanOnStart ? '#2a7' : '#e0e0e0')
            .fontColor(this.settings.autoScanOnStart ? Color.White : '#333')
            .onClick(() => {
              this.settings.autoScanOnStart = !this.settings.autoScanOnStart;
              this.saveNow();
              console.info(`[Index] Auto scan on start: ${this.settings.autoScanOnStart}`);
            })
          Text($r('app.string.set_autoscan_hint'))
            .fontSize(10).fontColor('#999')
        }

        /* Round 6: cover grid columns */
        Row({ space: 8 }) {
          Text($r('app.string.set_cover_columns'))
            .fontSize(13).fontColor('#555')
          Button('' + this.settings.coverColumns)
            .fontSize(12).height(32).width(52)
            .backgroundColor('#e0e0e0').fontColor('#333')
            .onClick(() => {
              this.settings.coverColumns = this.settings.coverColumns >= 4 ? 2 : this.settings.coverColumns + 1;
              this.saveNow();
              console.info(`[Index] Cover columns: ${this.settings.coverColumns}`);
            })
          Text($r('app.string.set_cover_columns_hint'))
            .fontSize(10).fontColor('#999')
        }

        /* Round 6: desktop card prefs */
        Row({ space: 8 }) {
          Text($r('app.string.set_card'))
            .fontSize(13).fontColor('#555')
          Button(this.settings.cardSource === 0 ? $r('app.string.sort_recent') : $r('app.string.card_starred'))
            .fontSize(12).height(32)
            .backgroundColor($r('app.color.brand_primary')).fontColor(Color.White)
            .onClick(() => {
              this.settings.cardSource = this.settings.cardSource === 0 ? 1 : 0;
              this.saveNow();
              console.info(`[Index] Card source: ${this.settings.cardSource}`);
            })
          Button('' + this.settings.cardCount + ' \u672C')
            .fontSize(12).height(32)
            .backgroundColor('#e0e0e0').fontColor('#333')
            .onClick(() => {
              this.settings.cardCount = this.settings.cardCount >= 3 ? 1 : this.settings.cardCount + 1;
              this.saveNow();
              console.info(`[Index] Card count: ${this.settings.cardCount}`);
            })
          Text($r('app.string.set_card_hint'))
            .fontSize(10).fontColor('#999')
        }

        /* Round 6: profile management */
        Row({ space: 8 }) {
          Text($r('app.string.set_profile'))
            .fontSize(13).fontColor('#555')
          Button(getCurrentProfile())
            .fontSize(12).height(32)
            .backgroundColor($r('app.color.brand_primary')).fontColor(Color.White)
            .onClick(() => {
              this.openProfilePanel();
            })
          Text($r('app.string.prof_hint2'))
            .fontSize(10).fontColor('#999')
        }

        /* Phase 6: reading reminder */
        Row({ space: 8 }) {
          Text($r('app.string.set_reminder'))
            .fontSize(13).fontColor('#555')
          Button(this.settings.readReminderMinutes === 0 ? $r('app.string.common_off') : this.settings.readReminderMinutes + ' \u5206\u949F')
            .fontSize(12).height(32)
            .backgroundColor(this.settings.readReminderMinutes > 0 ? '#2a7' : '#e0e0e0')
            .fontColor(this.settings.readReminderMinutes > 0 ? Color.White : '#333')
            .onClick(() => {
              this.settings.readReminderMinutes = this.settings.readReminderMinutes >= 120 ? 0 : (this.settings.readReminderMinutes === 0 ? 30 : this.settings.readReminderMinutes + 30);
              this.saveNow();
            })
          Text($r('app.string.set_reminder_hint'))
            .fontSize(10).fontColor('#999')
        }

        /* Round 5: tab bar top/bottom */
        Row({ space: 8 }) {
          Text($r('app.string.pref_tab_pos'))
            .fontSize(13).fontColor('#555')
          Button(this.settings.tabPositionTop ? $r('app.string.pref_tab_top') : $r('app.string.pref_tab_bottom'))
            .fontSize(12).height(32)
            .backgroundColor(this.settings.tabPositionTop ? $r('app.color.brand_primary') : '#e0e0e0')
            .fontColor(this.settings.tabPositionTop ? Color.White : '#333')
            .onClick(() => {
              this.settings.tabPositionTop = !this.settings.tabPositionTop;
              this.saveNow();
              console.info(`[Index] Tab position top: ${this.settings.tabPositionTop}`);
            })
          Text($r('app.string.set_tabpos_hint'))
            .fontSize(10).fontColor('#999')
        }

        /* Round 5: reader top bar clock+battery */
        Row({ space: 8 }) {
          Text($r('app.string.pref_topbar_info'))
            .fontSize(13).fontColor('#555')
          Button(this.settings.showTopBarInfo ? $r('app.string.common_on') : $r('app.string.common_off'))
            .fontSize(12).height(32)
            .backgroundColor(this.settings.showTopBarInfo ? '#2a7' : '#e0e0e0')
            .fontColor(this.settings.showTopBarInfo ? Color.White : '#333')
            .onClick(() => {
              this.settings.showTopBarInfo = !this.settings.showTopBarInfo;
              this.saveNow();
              console.info(`[Index] Top bar info: ${this.settings.showTopBarInfo}`);
            })
          Text($r('app.string.set_topbar_hint'))
            .fontSize(10).fontColor('#999')
        }

        /* K3: Proxy settings */
        Row({ space: 8 }) {
          Text($r('app.string.set_proxy'))
            .fontSize(13).fontColor('#555')
          Button(this.settings.proxyType === 0 ? $r('app.string.common_off') : (this.settings.proxyType === 1 ? 'HTTP' : 'SOCKS'))
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
          Text($r('app.string.set_quality'))
            .fontSize(13).fontColor('#555')
          Button(this.settings.renderQuality === 0 ? $r('app.string.quality_fast') : (this.settings.renderQuality === 1 ? $r('app.string.quality_balanced') : $r('app.string.quality_high')))
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
          Text($r('app.string.set_flip_anim'))
            .fontSize(13).fontColor('#555')
          Button(this.settings.flipAnimation === 0 ? $r('app.string.anim_slide') : (this.settings.flipAnimation === 1 ? $r('app.string.anim_curl') : $r('app.string.anim_fade')))
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
          Button($r('app.string.backup_export'))
            .fontSize(13).height(34).layoutWeight(1)
            .backgroundColor('#2a7').fontColor(Color.White)
            .onClick(() => this.doBackup())
          Button($r('app.string.backup_restore'))
            .fontSize(13).height(34).layoutWeight(1)
            .backgroundColor('#ff9800').fontColor(Color.White)
            .onClick(() => this.doRestore())
        }

        if (this.backupText.length > 0) {
          Text(this.backupText)
            .fontSize(11).fontColor('#888').textAlign(TextAlign.Center).width('100%')
        }

        Divider().color('#ddd')

        /* ===== NAPI self-test (diagnostics, hidden by default; tap 3x on title) ===== */
        if (this.showApiTest) {
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
