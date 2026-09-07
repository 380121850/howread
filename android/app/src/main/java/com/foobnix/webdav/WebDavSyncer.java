package com.foobnix.webdav;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.foobnix.android.utils.Dips;
import com.foobnix.android.utils.FileHash;
import com.foobnix.android.utils.IO;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.dao2.FileMeta;
import com.foobnix.LibreraApp;
import com.foobnix.pdf.search.activity.msg.UpdateAllFragments;
import com.foobnix.model.AppBookmark;
import com.foobnix.model.AppData;
import com.foobnix.model.AppProfile;
import com.foobnix.model.AppSP;
import com.foobnix.model.AppState;
import com.foobnix.model.MyPath;
import com.foobnix.model.ProfileStateIO;
import com.foobnix.model.SimpleMeta;
import com.foobnix.pdf.info.AppsConfig;
import com.foobnix.pdf.info.BookmarksData;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.model.BookCSS;
import com.foobnix.ui2.AppDB;
import com.thegrizzlylabs.sardineandroid.DavResource;
import com.thegrizzlylabs.sardineandroid.Sardine;

import org.ebookdroid.common.settings.books.SharedBooks;
import org.greenrobot.eventbus.EventBus;
import org.librera.JSONArray;
import org.librera.LinkedJSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.Arrays;

import javax.net.ssl.SSLException;

/**
 * Reading-data sync over WebDAV, following the Anx Reader incremental-sync
 * idea: entries are merged individually instead of overwriting whole files,
 * so a device with an older snapshot never clobbers a newer reading position
 * from another device.
 *
 * Remote layout (under the configured server root):
 *   /&lt;dir&gt;/global/app-*.json     global config files, synced FIELD-LEVEL
 *                                      three-way (local vs server vs the local
 *                                      base snapshot of the last merge): fields
 *                                      changed on only one device survive the
 *                                      other device's sync; a field changed on
 *                                      BOTH keeps the local value and publishes
 *                                      it. Change detection compares content
 *                                      against the base, never the clock.
 *   /&lt;dir&gt;/global/app-Recent.json  SimpleMeta lists, entry-level union
 *   /&lt;dir&gt;/global/app-Favorite.json (keyed by path, newer "time" wins)
 *   /&lt;dir&gt;/books/&lt;hash&gt;.json      per-book info: name + content hash identity,
 *                                      reading progress and bookmarks/AI notes.
 *                                      Created ONLY for books the user actually
 *                                      opened on some device. The book FILE
 *                                      itself is never synced.
 *
 * The base snapshots (app-*.json.base next to the config files) are local-only
 * and never uploaded. Device-bound fields (screen metrics, absolute paths,
 * session residue, per-device credentials) never leave the device.
 *
 * Book identity: MD5 of the book file content (FileHash, cached). On restore
 * each book info is applied independently ("per-book restore"): the local
 * candidate file is matched by name and confirmed by hash — a hash match
 * fully associates progress + bookmarks with that file (bookmark paths are
 * rewritten to the local file); a hash mismatch (same name, different file)
 * never overwrites local progress; books without a local file yet are kept
 * "pending" and associate automatically once the file appears. One corrupt
 * remote book info never aborts the rest.
 */
public class WebDavSyncer {

    public static final String REMOTE_DIR = "HowRead";
    /** legacy sync folder name before the HowRead rebrand; a stale configured
     * value is mapped back to HowRead (the folder itself is never touched) */
    public static final String REMOTE_LEGACY_DIR = "Librera";
    private static final String REMOTE_GLOBAL = "global";
    private static final String REMOTE_BOOKS = "books";
    private static final String STATE_FILE = "app-State.json";
    private static final String CSS_FILE = "app-CSS.json";

    /**
     * AppSP fields (inside app-Misc.json) that identify the device/profile and
     * never migrate: they are always kept local during the three-way merge.
     */
    private static final Set<String> APPSP_IDENTITY_FIELDS = new HashSet<String>(Arrays.asList(
            "rootPath1", "currentProfile", "syncRootID", "lastBookPath", "lastBookPage"));

    /**
     * Device-bound app-State.json fields (screen metrics, absolute paths,
     * session residue, per-device credentials): excluded from the sync
     * comparison, stripped from the uploaded copy and re-applied locally
     * after a download, so devices never overwrite each other with values
     * that only make sense on one machine.
     */
    private static final Set<String> STATE_DEVICE_FIELDS = new HashSet<String>(Arrays.asList(
            "displayPath", "installationDate",
            "statusBarTextSizeAdv", "statusBarTextSizeEasy", "progressLineHeight",
            "tapzoneSize", "coverBigSize", "isReverseKeys", "pageQuality",
            "isCutRTL", "isRTLByDefault", "selectingByLetters",
            "fileToDelete", "myAutoCompleteDb",
            "bgImageDayPath", "bgImageNightPath",
            "proxyEnable", "proxyServer", "proxyPort", "proxyUser", "proxyPassword",
            "selectedText", "searchQuery", "isAutoScroll",
            "hashCode", "webdavLastSyncTime", "webdavLastSyncInfo"));

    /** Device-bound app-CSS.json fields (absolute paths and the SAF URI). */
    private static final Set<String> CSS_DEVICE_FIELDS = new HashSet<String>(Arrays.asList(
            "searchPathsJson", "cachePath", "downlodsPath", "ttsSpeakPath", "backupPath",
            "dictPath", "fontFolder", "dirLastPath", "pathSAF", "mp3BookPathJson",
            "syncDropboxPath", "syncGdrivePath", "syncOneDrivePath",
            // the removed-fallback-folders memory holds device-specific
            // absolute paths: one device hiding ITS storage root must not
            // hide another device's root
            "searchPathsHiddenJson",
            "hashCode"));

    /**
     * The sync setup fields: setting them is what ENABLES the first sync on
     * a fresh device, so "just configured sync" must not make that device
     * look personalized — they are ignored by the default-config detection
     * and kept local when the server copy is adopted.
     */
    private static final Set<String> SYNC_CONFIG_FIELDS = new HashSet<String>(Arrays.asList(
            "webdavSyncEnabled", "webdavSyncServer", "webdavSyncRemoteDir",
            "webdavSyncPolicy", "webdavSyncIntervalMin"));

    public static class SyncResult {
        public boolean ok = false;
        /** "", "auth", "ssl", "network", "no_server", "other" */
        public String error = "";
        public int progressUp, progressDown;
        public int bookmarksUp, bookmarksDown;
        /** number of per-book info files merged or uploaded */
        public int booksSynced;
        /** number of books whose hash matched a local file (full restore) */
        public int booksAssociated;
        /** server info files removed because their book was deleted locally */
        public int booksDeleted;
        public long durationMs;
    }

    public interface Listener {
        void onStep(String step);

        void onFinish(SyncResult result);
    }

    /** True while a doSync runs on the worker thread: the lastSync stamps
     * that doSync itself writes through AppState.save must never trigger
     * another automatic sync (that would never stop). */
    private static volatile boolean syncingNow;

    private static final Handler SYNC_SCHEDULER = new Handler(Looper.getMainLooper());
    private static final long CONFIG_SYNC_DEBOUNCE_MS = 3 * 1000;
    private static Runnable pendingConfigSync;
    private static Runnable pendingPeriodicSync;

    /** Run the sync on a background thread; callbacks arrive on the main thread. */
    public static void syncAsync(final Context c, final Listener listener) {
        final Handler main = new Handler(Looper.getMainLooper());
        AppsConfig.executorServiceSingle.execute(() -> {
            syncingNow = true;
            try {
                if (listener != null) {
                    main.post(() -> listener.onStep("sync"));
                }
                final SyncResult result = doSync(c);
                if (listener != null) {
                    main.post(() -> listener.onFinish(result));
                }
            } finally {
                syncingNow = false;
            }
        });
    }

    /**
     * The local configuration changed (AppState.save / BookCSS.save): run one
     * silent sync shortly after, coalescing a burst of saves into a single
     * run. Dropped while a sync is in progress — the only saves made inside
     * doSync are its own lastSync stamps — so periodic/manual syncs stay the
     * safety net for anything changed during those few seconds.
     */
    public static void notifyConfigChanged(final Context c) {
        try {
            if (syncingNow) {
                return;
            }
            if (pendingConfigSync != null) {
                SYNC_SCHEDULER.removeCallbacks(pendingConfigSync);
            }
            pendingConfigSync = () -> {
                pendingConfigSync = null;
                if (syncingNow) {
                    return;
                }
                if (AppState.get().webdavSyncEnabled && TxtUtils.isNotEmpty(AppState.get().webdavSyncServer)) {
                    syncAsync(c.getApplicationContext(), null);
                }
            };
            SYNC_SCHEDULER.postDelayed(pendingConfigSync, CONFIG_SYNC_DEBOUNCE_MS);
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /**
     * Periodic background sync while the app is alive. The interval is
     * re-read on every cycle, so a new value picked in the dialog (or synced
     * from another device) applies from the next cycle on;
     * webdavSyncIntervalMin &lt;= 0 disables the periodic sync.
     */
    public static void scheduleNextPeriodic(final Context c) {
        if (pendingPeriodicSync != null) {
            SYNC_SCHEDULER.removeCallbacks(pendingPeriodicSync);
        }
        final int min = AppState.get().webdavSyncIntervalMin;
        if (min <= 0) {
            return;
        }
        pendingPeriodicSync = () -> {
            pendingPeriodicSync = null;
            final Context app = c.getApplicationContext();
            if (AppState.get().webdavSyncEnabled && TxtUtils.isNotEmpty(AppState.get().webdavSyncServer)) {
                syncAsync(app, null);
            }
            scheduleNextPeriodic(app);
        };
        SYNC_SCHEDULER.postDelayed(pendingPeriodicSync, min * 60 * 1000L);
    }

    /**
     * Effective sync server config: the sync settings when configured, else
     * (as a default only) the first browsing WebDAV server from "My files"
     * with its stored credentials.
     *
     * @return {url, login, password} or null when nothing is configured.
     */
    public static String[] resolveConfig(Context c) {
        String url = AppState.get().webdavSyncServer;
        if (TxtUtils.isEmpty(url)) {
            List<WebDavServer> servers = WebDavStore.load();
            if (!servers.isEmpty()) {
                url = servers.get(0).url;
            }
        }
        if (TxtUtils.isEmpty(url)) {
            return null;
        }
        String[] creds = WebDavCredentials.load(c, url);
        return new String[]{url, creds != null ? creds[0] : "", creds != null ? creds[1] : ""};
    }

    /** Configured server-side sync directory, cleaned of slashes. */
    public static String remoteDir() {
        String dir = AppState.get().webdavSyncRemoteDir;
        // the legacy default follows the rebrand; a stale "Librera" may also
        // come back through a synced app-State.json (importAppState)
        if (TxtUtils.isEmpty(dir) || REMOTE_LEGACY_DIR.equals(dir)) {
            dir = REMOTE_DIR;
        }
        dir = dir.replace("\\", "/");
        while (dir.startsWith("/")) {
            dir = dir.substring(1);
        }
        while (dir.endsWith("/")) {
            dir = dir.substring(0, dir.length() - 1);
        }
        return TxtUtils.isEmpty(dir) ? REMOTE_DIR : dir;
    }

    public static SyncResult doSync(Context c) {
        SyncResult res = new SyncResult();
        long start = System.currentTimeMillis();
        try {
            String[] cfg = resolveConfig(c);
            if (cfg == null) {
                res.error = "no_server";
                return res;
            }
            String serverUrl = cfg[0];
            boolean trustAll = WebDavCredentials.isTrustAll(c, serverUrl);
            Sardine s = WebDavClient.sardine(cfg[1], cfg[2], trustAll);

            String root = WebDavStore.trimSlash(serverUrl) + "/" + remoteDir();
            String globalUrl = root + "/" + REMOTE_GLOBAL;
            String booksUrl = root + "/" + REMOTE_BOOKS;
            mkDirs(s, root, globalUrl, booksUrl);

            SyncChangeLog.begin();

            // ---- global config: field-level three-way merge (local vs server
            // vs the base snapshot of the last merged result). Devices changing
            // different fields never overwrite each other; change detection is
            // content-based, never clock-based.
            ProfileStateIO.exportNetworkSources();
            syncThreeWayFile(s, globalUrl, AppProfile.syncState, true, false);
            syncThreeWayFile(s, globalUrl, AppProfile.syncCSS, false, true);

            // ---- whole-file state: mirror stats / AI key / misc to files, then
            // sync every list and config file.
            ProfileStateIO.exportStats();
            ProfileStateIO.exportAi(c);
            ProfileStateIO.exportMisc(c);
            boolean listsUpdated = syncMetaUnion(s, globalUrl, AppProfile.syncRecent);
            listsUpdated |= syncMetaUnion(s, globalUrl, AppProfile.syncFavorite);
            if (listsUpdated) {
                AppData.get().invalidateListCache();
            }
            syncMergedObjectFile(s, globalUrl, AppProfile.syncBookStates, WebDavSyncer::mergeStatesMaxWins);
            // reading statistics converge per key with the max of both sides
            // (counters and time buckets only ever grow), not mtime
            syncMergedObjectFile(s, globalUrl, AppProfile.syncStats, ProfileStateIO::mergeStats);
            // the AI key file is merged, not whole-file: a reset device exports
            // an empty key right before the sync, and plain newer-wins would
            // let that fresh empty file clobber the server copy (and then
            // importAi would have nothing to restore)
            syncMergedObjectFile(s, globalUrl, AppProfile.syncAI, ProfileStateIO::mergeAi);
            syncThreeWayFile(s, globalUrl, AppProfile.syncMisc, false, false);
            ProfileStateIO.importAi(c);
            ProfileStateIO.importMisc(c);
            // re-apply the synced reading statistics AFTER importMisc: the
            // misc import restores the whole AppSP object from app-Misc.json
            // and would otherwise overwrite the synced statistics with the
            // stale values carried inside it
            ProfileStateIO.importStats(c);
            // apply the synced global settings (AI model config, …) to the
            // running app; AppState.save() at the end would otherwise write
            // the stale in-memory state back over the synced file
            ProfileStateIO.importAppState();
            // same for the styling: re-apply the merged app-CSS.json to the
            // live BookCSS, otherwise AppProfile.save() below writes the stale
            // in-memory copy back over the merged file and the second CSS
            // sync of this round would publish the stale values to the server
            ProfileStateIO.importCss();

            // ---- network sources (OPDS catalogs, WebDAV servers, 书库文件夹):
            // dedicated three-way sync, applied AFTER the global files so the
            // app-State.json merge can never override the lists; entries the
            // user deleted locally and did not change remotely stay deleted
            syncThreeWayFile(s, globalUrl, AppProfile.syncNetworkSources, false, false);
            ProfileStateIO.importNetworkSources();
            AppProfile.save(c);
            syncThreeWayFile(s, globalUrl, AppProfile.syncState, true, false);
            syncThreeWayFile(s, globalUrl, AppProfile.syncCSS, false, true);

            // ---- local state: progress per book + bookmarks by creation time
            final String syncPolicy = AppState.get().webdavSyncPolicy;
            // stale leftovers of books deleted before the delete-flow cleanup
            // existed: drop (and tombstone) them so the merge below cannot
            // keep resurrecting bookmarks of books that no longer exist
            try {
                com.foobnix.pdf.info.BookmarksData.get().pruneDeletedBooks();
            } catch (Exception pruneError) {
                LOG.e(pruneError);
            }
            // deletion markers older than DK_MAX_AGE_MS (buildDeletedKeys)
            // have lost their protective power anyway: drop them so they can
            // never suppress or delete data of a much later re-share
            try {
                SharedBooks.DeletedBooks.expireOlderThan(DK_MAX_AGE_MS);
            } catch (Exception expireError) {
                LOG.e(expireError);
            }
            final LinkedJSONObject localP = IO.readJsonObject(AppProfile.syncProgress);
            final LinkedJSONObject localB = IO.readJsonObject(AppProfile.syncBookmarks);

            // ---- local per-book info, keyed by book file name
            final Map<String, LinkedJSONObject> localBooks = buildLocalBooks(localP, localB);

            // ---- local files: name → existing file (bookmarks + library DB)
            final Map<String, File> candidates = buildLocalCandidates();

            // ---- remote per-book infos, hash → info
            final boolean[] booksListFailed = new boolean[]{false};
            final Map<String, LinkedJSONObject> remoteBooks =
                    listRemoteBooks(s, booksUrl, booksListFailed);
            if (booksListFailed[0]) {
                // some book files could not be fetched this round: their
                // tombstones (if any) stay untouched and are retried next round
                LOG.d("WebDavSyncer remote book listing incomplete (network error)");
            }

            int pDown = 0, bDown = 0, associated = 0;
            Set<String> namesCovered = new HashSet<>();
            Set<String> uploadedHashes = new HashSet<>();
            // every book name that exists on the server this round (for the
            // stale-tombstone cleanup below)
            final Set<String> remoteNames = new HashSet<>();
            // locally deleted progress/bookmarks (marked-unread, bookmark
            // removal): never merged back, server file removed when nothing
            // remains to keep it alive
            final LinkedJSONObject deletedBooks = SharedBooks.DeletedBooks.all();
            final List<String> hashesToDelete = new ArrayList<String>();
            // tombstones are only dropped once the deletion is confirmed on
            // the server (file deleted, or the merged info published); the
            // rest is kept for the next round so a transient failure cannot
            // resurrect deleted bookmarks
            final Set<String> consumedNames = new HashSet<>();
            final Map<String, String> deletedHashName = new HashMap<>();
            // real uploads this round (the old "every iterated book" count was
            // meaningless: every matched book re-PUT on every round)
            final int[] putCount = {0};

            // ---- apply every remote book info INDEPENDENTLY (per-book restore:
            // one corrupt or conflicting book never blocks the others)
            for (Map.Entry<String, LinkedJSONObject> e : remoteBooks.entrySet()) {
                String rHash = e.getKey();
                LinkedJSONObject info = e.getValue();
                try {
                    String name = info.optString("name");
                    if (TxtUtils.isEmpty(name)) {
                        continue;
                    }
                    remoteNames.add(name);
                    final boolean delProgress = markKind(deletedBooks, name, "p");
                    final boolean delBookmarks = markKind(deletedBooks, name, "b");
                    // specific bookmark keys the user deleted for this book:
                    // never re-merged from the server, and dropped from the
                    // published info so the server converges (a partial delete
                    // whose book still has progress/other notes would otherwise
                    // be union-merged back on a later round)
                    final Set<String> deletedKeys = SharedBooks.DeletedBooks.keysOf(deletedBooks, name);
                    // deletion record carried by the server info (key → time):
                    // other devices' bookmark deletions. A record newer than
                    // the bookmark's creation key applies the deletion locally;
                    // an older record means the bookmark was re-created later
                    // and must survive.
                    final LinkedJSONObject remoteDk = info.optJSONObject("dk");
                    if (remoteDk != null) {
                        for (Iterator<String> dks = remoteDk.keys(); dks.hasNext();) {
                            final String dkKey = dks.next();
                            final long dkTime = remoteDk.optLong(dkKey, 0);
                            long created = Long.MAX_VALUE;
                            try {
                                created = Long.parseLong(dkKey);
                            } catch (NumberFormatException notAKey) {
                            }
                            if (localB.has(dkKey) && dkTime > created) {
                                localB.remove(dkKey);
                                SyncChangeLog.add("books", name + " · 书签", "down", "(删除)", dkKey);
                            }
                        }
                    }
                    if ((delProgress || delBookmarks)
                            && !localP.has(name) && subsetFor(localB, name).length() == 0) {
                        // everything the user deleted locally is gone here too:
                        // remove the server copy instead of restoring it
                        hashesToDelete.add(rHash);
                        deletedHashName.put(rHash, name);
                        namesCovered.add(name);
                        continue;
                    }
                    File localFile = candidates.get(name);
                    boolean matched = localFile != null && rHash.equals(FileHash.md5(localFile));
                    boolean conflict = localFile != null && !matched;
                    if (matched) {
                        associated++;
                    }
                    if (!conflict) {
                        // covered: hash-confirmed, or a book this device does not
                        // have yet (pending). A same-name DIFFERENT file is a
                        // separate book and is uploaded under its own hash below.
                        namesCovered.add(name);
                    }

                    // progress: only a hash-confirmed book (or a book this device
                    // does not have yet) may change the local reading position
                    LinkedJSONObject rp = info.optJSONObject("progress");
                    if (rp != null && !delProgress && (matched || localFile == null)) {
                        if (mergeProgressEntry(localP, name, rp, syncPolicy)) {
                            pDown++;
                            SyncChangeLog.add("books", name + " · 进度", "down", null,
                                    "p=" + rp.optDouble("p", 0));
                        }
                    }

                    // bookmarks: union by creation-time key; on a hash match the
                    // incoming entries are re-pointed at the local file so notes
                    // and bookmarks follow the book whatever the source path was
                    LinkedJSONObject rbs = info.optJSONObject("bookmarks");
                    if (rbs != null && !delBookmarks) {
                        Iterator<String> keys = rbs.keys();
                        while (keys.hasNext()) {
                            String key = keys.next();
                            if (localB.has(key) || deletedKeys.contains(key)) {
                                continue;
                            }
                            // deleted on another device after this key was
                            // created: the server's dk record suppresses it
                            if (remoteDk != null && remoteDk.has(key)) {
                                continue;
                            }
                            LinkedJSONObject bm = rbs.optJSONObject(key);
                            if (bm == null) {
                                continue;
                            }
                            if (matched) {
                                bm.put("path", MyPath.toRelative(localFile.getPath()));
                            }
                            localB.put(key, bm);
                            bDown++;
                            SyncChangeLog.add("books", name + " · 书签", "down", null,
                                    bm.optString("path", ""));
                        }
                    }

                    // publish the merged info so the next device converges too —
                    // but ONLY from a device that actually has this book (hash
                    // confirmed): a book this device never opened is maintained
                    // by the device it was opened on, and a name conflict means
                    // the info file belongs to the OTHER book with that name
                    if (matched) {
                        LinkedJSONObject pubBm = subsetFor(localB, name);
                        if (!deletedKeys.isEmpty()) {
                            for (String dk : deletedKeys) {
                                pubBm.remove(dk);
                            }
                        }
                        // carry the server's timestamp: comparing without "t"
                        // below means a book whose progress/bookmarks did not
                        // change is NOT re-PUT on every round (the previous
                        // unconditional t=now made the equality check always
                        // fail → one full PUT per book per device per round)
                        LinkedJSONObject merged = buildInfoWithHash(name, rHash,
                                localP.optJSONObject(name), pubBm, info.optLong("t", 0));
                        final LinkedJSONObject pubDk = buildDeletedKeys(name, remoteDk, deletedBooks);
                        if (pubDk != null) {
                            merged.put("dk", pubDk);
                        }
                        if (merged != null && !sameIgnoringT(merged, info)) {
                            merged.put("t", System.currentTimeMillis());
                            try {
                                putBookInfo(s, booksUrl, rHash, merged);
                                putCount[0]++;
                                uploadedHashes.add(rHash);
                                // the merged info is on the server: the deleted
                                // progress/bookmarks/keys are gone there too, so
                                // the whole tombstone for this book is consumed
                                if (delProgress || delBookmarks || !deletedKeys.isEmpty()) {
                                    consumedNames.add(name);
                                }
                            } catch (Exception putError) {
                                // keep the tombstone for the next round
                                LOG.d("WebDavSyncer put", rHash, putError.getMessage());
                            }
                        }
                    }
                    res.booksSynced++;
                } catch (Exception bookError) {
                    LOG.e(bookError, "WebDavSyncer book", rHash);
                }
            }

            // ---- local books the server has not seen yet (or same-name
            // different-file variants that live under their own hash).
            // Skipped entirely when the listing was incomplete: with a broken
            // listing "not on the server" is unproven, and re-uploading every
            // local info would clobber the (newer, unreadable) server state.
            if (!booksListFailed[0]) {
                for (Map.Entry<String, LinkedJSONObject> e : localBooks.entrySet()) {
                    String name = e.getKey();
                    try {
                        if (namesCovered.contains(name)) {
                            continue;
                        }
                        LinkedJSONObject info = e.getValue();
                        String hash = info.optString("hash");
                        if (TxtUtils.isEmpty(hash) || uploadedHashes.contains(hash)) {
                            continue;
                        }
                        LinkedJSONObject remote = remoteBooks.get(hash);
                        if (remote != null && sameIgnoringT(remote, info)) {
                            continue;
                        }
                        putBookInfo(s, booksUrl, hash, info);
                        putCount[0]++;
                        SyncChangeLog.add("books", name + " · 书籍信息", "up", null, hash);
                    } catch (Exception bookError) {
                        LOG.e(bookError, "WebDavSyncer upload", name);
                    }
                }

                // remove server copies of books whose progress/bookmarks were
                // deleted locally and have nothing left to keep them alive
                for (String h : hashesToDelete) {
                    try {
                        s.delete(booksUrl + "/" + h + ".json");
                        res.booksDeleted++;
                        SyncChangeLog.add("books", deletedHashName.get(h) + " · 书籍信息", "up", "(存在)", "(已删除)");
                        // server copy gone: the tombstone for this book is consumed
                        String n = deletedHashName.get(h);
                        if (n != null) {
                            consumedNames.add(n);
                        }
                    } catch (Exception delError) {
                        LOG.d("WebDavSyncer delete", h, delError.getMessage());
                    }
                }

                // stale tombstone cleanup: the listing succeeded, so a
                // tombstone whose book name appears NOWHERE on the server
                // targets a provably gone file — keep it and it would one day
                // suppress or delete an unrelated future book with that name
                try {
                    final LinkedJSONObject delNow = SharedBooks.DeletedBooks.all();
                    final Set<String> stale = new HashSet<>();
                    for (Iterator<String> it = delNow.keys(); it.hasNext();) {
                        final String n = it.next();
                        if (!remoteNames.contains(n)) {
                            stale.add(n);
                        }
                    }
                    if (!stale.isEmpty()) {
                        SharedBooks.DeletedBooks.clearNames(stale);
                        LOG.d("WebDavSyncer", "stale tombstones cleared", stale.size());
                    }
                } catch (Exception staleError) {
                    LOG.e(staleError);
                }
            } else {
                LOG.d("WebDavSyncer", "remote listing incomplete: upload/delete phase skipped this round");
            }
            res.booksSynced = putCount[0];
            // drop only the tombstones whose deletion is confirmed on the
            // server this round (consumedNames holds exactly those names);
            // everything else is kept so the next round retries instead of
            // union-merging the "deleted" entries back from the server
            if (!consumedNames.isEmpty()) {
                SharedBooks.DeletedBooks.clearNames(consumedNames);
            }

            // writeback with a mid-round merge: the UI thread keeps writing
            // progress/bookmarks/tombstones while the network phase runs, and
            // writing the start-of-round snapshots back used to revert all of
            // it (lost positions, lost bookmarks, undeletable bookmarks)
            mergeSnapshotsAtEnd(localP, localB);

            // in-memory progress cache is now stale
            SharedBooks.cache.clear();

            AppState.get().webdavLastSyncTime = System.currentTimeMillis();
            res.progressDown = pDown;
            res.progressUp = Math.max(0, localP.length() - pDown);
            res.bookmarksDown = bDown;
            res.bookmarksUp = Math.max(0, localB.length() - bDown);
            res.booksAssociated = associated;
            res.durationMs = System.currentTimeMillis() - start;
            AppState.get().webdavLastSyncInfo = "\u2191" + res.booksSynced + "\u672c \u00b7 \u5173\u8054" + associated
                    + (res.booksDeleted > 0 ? " \u00b7 \u5220" + res.booksDeleted : "")
                    + " \u00b7 " + res.durationMs + "ms";
            android.util.Log.i("BENCH", "sync books: synced=" + res.booksSynced + " associated=" + associated
                    + " deleted=" + res.booksDeleted);
            AppState.get().save(c);
            SyncChangeLog.commit(AppState.get().webdavLastSyncInfo);
            // apply the synced configuration immediately: when this round
            // changed anything (config fields, down-synced progress/bookmarks),
            // rebuild the tab pages (My files lists, reading prefs) right away
            // instead of waiting for their next natural recreation
            if (SyncChangeLog.hasItems()) {
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() {
                        try {
                            EventBus.getDefault().post(new UpdateAllFragments());
                        } catch (Exception uiError) {
                            LOG.e(uiError);
                        }
                    }
                });
            }

            res.ok = true;
        } catch (Exception e) {
            LOG.e(e);
            res.error = classify(e);
            res.durationMs = System.currentTimeMillis() - start;
            SyncChangeLog.commit("同步失败：" + res.error);
        }
        return res;
    }

    // ------------------------------------------------------------------ global

    /** Merge callback for the global state files. */
    interface JsonMerger {
        LinkedJSONObject merge(LinkedJSONObject local, LinkedJSONObject remote);
    }

    /**
     * Union by key of the per-book read-state overrides (0 unread, 1 reading,
     * 2 read); the "further along" state wins, so marks converge on every
     * device instead of each device overwriting the server with its own copy.
     */
    static LinkedJSONObject mergeStatesMaxWins(LinkedJSONObject local, LinkedJSONObject remote) {
        LinkedJSONObject out = new LinkedJSONObject(local.toString());
        Iterator<String> keys = remote.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            int r = remote.optInt(k, -1);
            if (r < 0) {
                continue;
            }
            int l = out.optInt(k, -1);
            if (l < 0 || r > l) {
                out.put(k, r);
            }
        }
        return out;
    }

    // -------------------------------------------------- three-way merge (field level)

    /** Local-only shadow of the last merged result of one synced config file. */
    static File baseFileOf(File local) {
        return new File(local.getParentFile(), local.getName() + ".base");
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : String.valueOf(a).equals(String.valueOf(b));
    }

    /**
     * Field-level three-way merge of one JSON object: base = the last merged
     * result this device saw, local = the current local object, remote = the
     * server object. A field changed on only one side since the base wins
     * (including deletions); a field changed on BOTH sides keeps the local
     * value (the device in use carries the user's latest intent) — arrays
     * union instead, so additions from both devices survive; keys present on
     * {@code keepLocal} (device/profile identity inside app-Misc.json) always
     * keep the local value. Nested JSON objects present on all three sides are
     * merged recursively (misc sections, the AppSP snapshot).
     */
    static LinkedJSONObject merge3(String file, LinkedJSONObject base, LinkedJSONObject local,
            LinkedJSONObject remote, Set<String> keepLocal) {
        final LinkedJSONObject out = new LinkedJSONObject();
        final Set<String> keys = new LinkedHashSet<String>();
        for (Iterator<String> it = local.keys(); it.hasNext();) {
            keys.add(it.next());
        }
        for (Iterator<String> it = remote.keys(); it.hasNext();) {
            keys.add(it.next());
        }
        for (Iterator<String> it = base.keys(); it.hasNext();) {
            keys.add(it.next());
        }
        for (String k : keys) {
            if (keepLocal != null && keepLocal.contains(k)) {
                if (local.has(k)) {
                    out.put(k, local.opt(k));
                }
                continue;
            }
            final boolean lb = local.has(k), rb = remote.has(k), bb = base.has(k);
            final Object lv = lb ? local.opt(k) : null;
            final Object rv = rb ? remote.opt(k) : null;
            final Object bv = bb ? base.opt(k) : null;
            final boolean localChanged = lb != bb || (lb && !eq(lv, bv));
            final boolean remoteChanged = rb != bb || (rb && !eq(rv, bv));
            if (bb && lb && rb && bv instanceof LinkedJSONObject
                    && lv instanceof LinkedJSONObject && rv instanceof LinkedJSONObject) {
                out.put(k, merge3(file + "." + k, (LinkedJSONObject) bv, (LinkedJSONObject) lv,
                        (LinkedJSONObject) rv, keepLocal));
                continue;
            }
            if (!bb && lb && rb && lv instanceof LinkedJSONObject && rv instanceof LinkedJSONObject) {
                // added on both devices independently (no base entry): merge
                // structurally — the scalar both-changed rule below would
                // silently drop the entire remote object
                out.put(k, firstSyncMerge(file + "." + k, (LinkedJSONObject) lv,
                        (LinkedJSONObject) rv, keepLocal));
                continue;
            }
            if (localChanged && remoteChanged) {
                if (lv instanceof JSONArray || rv instanceof JSONArray) {
                    out.put(k, unionArrays(lv, rv));
                } else if (lb) {
                    out.put(k, lv);
                }
                // lb == false: deleted locally AND changed remotely → stay deleted
            } else if (remoteChanged) {
                if (rb) {
                    out.put(k, rv);
                }
                // rb == false: the field was deleted on the server and not
                // changed locally → the deletion propagates
            } else if (lb) {
                out.put(k, lv);
            }
        }
        return out;
    }

    /** Union of two JSON arrays preserving the original element types (the
     * previous optString-based union corrupted object/number elements into
     * strings); local order first, remote-only items appended. */
    static JSONArray unionArrays(Object lv, Object rv) {
        final JSONArray out = new JSONArray();
        final Set<String> seen = new HashSet<String>();
        for (Object arr : new Object[]{lv, rv}) {
            if (!(arr instanceof JSONArray)) {
                // scalar side of a both-changed conflict: keep it as a value
                // instead of silently dropping it
                if (arr != null && !isEmptyValue(arr)) {
                    final String key = String.valueOf(arr);
                    if (!seen.contains(key)) {
                        seen.add(key);
                        out.put(arr);
                    }
                }
                continue;
            }
            final JSONArray a = (JSONArray) arr;
            for (int i = 0; i < a.length(); i++) {
                final Object v = a.opt(i);
                final String key = String.valueOf(v);
                if (!seen.contains(key)) {
                    seen.add(key);
                    out.put(v);
                }
            }
        }
        return out;
    }

    /**
     * Log every key where {@code to} differs from {@code from} as one sync
     * change item (dotted keys for nested objects).
     */
    static void logDiff(String file, LinkedJSONObject from, LinkedJSONObject to, String action) {
        try {
            final Set<String> keys = new LinkedHashSet<String>();
            for (Iterator<String> it = from.keys(); it.hasNext();) {
                keys.add(it.next());
            }
            for (Iterator<String> it = to.keys(); it.hasNext();) {
                keys.add(it.next());
            }
            for (String k : keys) {
                final boolean fo = from.has(k), t2 = to.has(k);
                final Object ov = fo ? from.opt(k) : null;
                final Object nv = t2 ? to.opt(k) : null;
                if (fo != t2 || !eq(ov, nv)) {
                    if (fo && t2 && ov instanceof LinkedJSONObject && nv instanceof LinkedJSONObject) {
                        logDiff(file + "." + k, (LinkedJSONObject) ov, (LinkedJSONObject) nv, action);
                        continue;
                    }
                    SyncChangeLog.add(file, k, action, fo ? ov : null, t2 ? nv : null);
                }
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /**
     * Field-level three-way sync of one global config file. Replaces the old
     * whole-file "newer mtime wins": change detection compares each field
     * against the local base snapshot of the last successful merge (app-*.json
     * .base, local-only), never the clock — so a stale local mtime can no
     * longer clobber the server (the root cause of WebDAV servers / 书库文件夹
     * never syncing down) and two devices changing different fields both keep
     * their changes.
     *
     * First sync without a base (fresh install / reinstall): per field the
     * side that HAS a value beats the empty side, both set → the server copy
     * wins, lists union — a reinstated device can never publish its stale
     * empties over the server; the sync setup just typed on this device and
     * the device-bound fields survive locally.
     *
     * Device-bound fields never leave the device: stripped from the uploaded
     * copy and re-applied locally after a download. Transient fetch failures
     * touch nothing; a 404 seeds the local copy up.
     *
     * @param stateFile true for app-State.json (AppState defaults + SYNC_CONFIG_FIELDS handling)
     * @param cssFile   true for app-CSS.json (BookCSS defaults)
     */
    static void syncThreeWayFile(Sardine s, String globalUrl, File local, boolean stateFile, boolean cssFile) {
        if (local == null || !local.isFile()) {
            return;
        }
        final String name = local.getName();
        final String url = globalUrl + "/" + name;
        try {
            final Set<String> deviceFields;
            if (stateFile) {
                deviceFields = STATE_DEVICE_FIELDS;
            } else if (cssFile) {
                deviceFields = CSS_DEVICE_FIELDS;
            } else {
                deviceFields = new HashSet<String>();
            }
            final String localFull = readText(local);
            final String remoteText = fetchText(s, url);
            if (remoteText == null) {
                // transient GET failure: touch neither the server nor the local
                // file (and leave the base alone) — otherwise one network error
                // could publish a stale local snapshot for good
                SyncChangeLog.add(name, "(整个文件)", "down", "(未同步：服务器暂不可达)", null);
                return;
            }
            final LinkedJSONObject localObj = new LinkedJSONObject(localFull);
            final LinkedJSONObject remoteObj = remoteText.trim().isEmpty()
                    ? new LinkedJSONObject() : new LinkedJSONObject(remoteText);
            final LinkedJSONObject localCmp = withoutFields(localObj, deviceFields);
            final LinkedJSONObject remoteCmp = withoutFields(remoteObj, deviceFields);

            final File baseF = baseFileOf(local);
            LinkedJSONObject baseObj = null;
            if (baseF.isFile()) {
                final String baseText = readText(baseF);
                if (baseText != null && baseText.trim().startsWith("{")) {
                    baseObj = new LinkedJSONObject(baseText);
                }
            }

            final LinkedJSONObject mergedFull;
            if (baseObj == null) {
                // ---- first sync for this file on this device
                if (remoteObj.length() == 0) {
                    // 404/empty server file: seed it with the local copy
                    mergedFull = localObj;
                } else {
                    // no common ancestor: non-empty beats empty, both set →
                    // the server copy wins, lists union (see firstSyncMerge)
                    final LinkedJSONObject mergedCmp =
                            firstSyncMerge(name, localCmp, remoteCmp, APPSP_IDENTITY_FIELDS);
                    mergedFull = new LinkedJSONObject(mergedCmp.toString());
                    keepLocalFields(mergedFull, localObj, deviceFields);
                    if (stateFile) {
                        // the sync setup typed on this device survives the first
                        // adoption (it is what makes this first sync possible)
                        keepLocalFields(mergedFull, localObj, SYNC_CONFIG_FIELDS);
                    }
                }
            } else if (remoteObj.length() == 0) {
                // ---- steady state but the server file is gone (404/empty):
                // this is NOT "every field was deleted remotely" — treating it
                // that way emptied the local config (minus device-bound
                // fields) and published the wipe to every other device. Seed
                // the server back up from the local copy instead, exactly
                // like the first-sync-without-remote case above.
                LOG.d("WebDavSyncer three-way", name, "server file gone: seeding from local");
                mergedFull = localObj;
            } else {
                // ---- steady state: merge against the stored base
                final LinkedJSONObject baseCmp = withoutFields(baseObj, deviceFields);
                // a local file that still equals the out-of-the-box defaults
                // never wins: its personal fields adopt the server copy
                final boolean localIsDefault = (stateFile || cssFile)
                        && isDefaultConfig(localFull, defaultIgnoreSet(deviceFields, stateFile), stateFile);
                final LinkedJSONObject effLocal = localIsDefault ? baseCmp : localCmp;
                final LinkedJSONObject mergedCmp = merge3(name, baseCmp, effLocal, remoteCmp, APPSP_IDENTITY_FIELDS);
                mergedFull = new LinkedJSONObject(mergedCmp.toString());
                keepLocalFields(mergedFull, localObj, deviceFields);
                if (localIsDefault && stateFile) {
                    keepLocalFields(mergedFull, localObj, SYNC_CONFIG_FIELDS);
                }
            }

            // ---- apply: write local, publish merged, refresh the base
            final LinkedJSONObject mergedCmp = withoutFields(mergedFull, deviceFields);
            logDiff(name, localCmp, mergedCmp, "down");
            logDiff(name, remoteCmp, mergedCmp, "up");
            boolean localChanged = !normalize(mergedFull.toString()).equals(normalize(localFull));
            if (localChanged) {
                IO.writeObjSync(local, mergedFull);
                android.util.Log.i("BENCH", "sync " + name + ": three-way merged (local updated)");
            }
            if (!normalize(mergedCmp.toString()).equals(normalize(remoteCmp.toString()))) {
                s.put(url, mergedCmp.toString().getBytes("UTF-8"));
            }
            final String baseText = baseF.isFile() ? readText(baseF) : null;
            if (baseText == null || !normalize(baseText).equals(normalize(mergedFull.toString()))) {
                IO.writeObjSync(baseF, mergedFull);
            }
        } catch (Exception e) {
            LOG.e(e, "WebDavSyncer three-way", name);
        }
    }

    /**
     * First-sync merge without a base snapshot (fresh install, reinstall, or
     * a file this device never synced before): per field the side that HAS a
     * value beats the side that has none — a reinstated device carrying a
     * stale config must never publish its empties over the server (that is
     * exactly how a reinstall wiped the AI model config and the My-files
     * WebDAV/书库 lists on the server for the other devices too). When BOTH
     * sides have a value the SERVER wins (it is the converged truth of the
     * other devices), so a just-typed local value survives only while the
     * server has none yet. Lists (arrays) union instead, so entries from both
     * devices survive. Nested objects merge recursively with the same rule.
     */
    static LinkedJSONObject firstSyncMerge(String file, LinkedJSONObject local,
            LinkedJSONObject remote, Set<String> keepLocal) {
        final LinkedJSONObject out = new LinkedJSONObject();
        final Set<String> keys = new LinkedHashSet<String>();
        for (Iterator<String> it = local.keys(); it.hasNext();) {
            keys.add(it.next());
        }
        for (Iterator<String> it = remote.keys(); it.hasNext();) {
            keys.add(it.next());
        }
        for (String k : keys) {
            if (keepLocal != null && keepLocal.contains(k)) {
                if (local.has(k)) {
                    out.put(k, local.opt(k));
                }
                continue;
            }
            final boolean lb = local.has(k), rb = remote.has(k);
            final Object lv = lb ? local.opt(k) : null;
            final Object rv = rb ? remote.opt(k) : null;
            final boolean lEmpty = isEmptyValue(lv), rEmpty = isEmptyValue(rv);
            if (lb && rb && lv instanceof LinkedJSONObject && rv instanceof LinkedJSONObject) {
                out.put(k, firstSyncMerge(file + "." + k, (LinkedJSONObject) lv,
                        (LinkedJSONObject) rv, keepLocal));
                continue;
            }
            if (lEmpty && rEmpty) {
                continue; // nothing configured on either side
            }
            if (lEmpty) {
                out.put(k, rv); // server-only value: adopt it
            } else if (rEmpty) {
                out.put(k, lv); // local-only value: keep it AND publish (heals the server)
            } else if (lv instanceof JSONArray || rv instanceof JSONArray) {
                out.put(k, unionArrays(lv, rv)); // lists: keep both devices' entries
            } else {
                out.put(k, rv); // both set and different: the server copy wins
            }
        }
        return out;
    }

    /** True when a value counts as "nothing configured" (absent, blank text, empty array/object). */
    static boolean isEmptyValue(Object v) {
        if (v == null) {
            return true;
        }
        if (v instanceof CharSequence) {
            // string-typed state fields may hold serialized JSON ("[]" for an
            // empty list, e.g. aiConfigs) — an empty container is NOT a value
            String s = v.toString().trim();
            return TxtUtils.isEmpty(s) || "[]".equals(s) || "{}".equals(s);
        }
        if (v instanceof JSONArray) {
            return ((JSONArray) v).length() == 0;
        }
        if (v instanceof LinkedJSONObject) {
            return ((LinkedJSONObject) v).length() == 0;
        }
        return false;
    }

    /** Fields a fresh-install detection must ignore on top of the device-bound ones. */
    private static Set<String> defaultIgnoreSet(Set<String> deviceFields, boolean stateFile) {
        if (!stateFile) {
            return deviceFields;
        }
        final Set<String> ignore = new HashSet<String>(deviceFields);
        ignore.addAll(SYNC_CONFIG_FIELDS);
        return ignore;
    }

    /**
     * Entry-level union sync of a SimpleMeta array file (recent / favorite):
     * entries keyed by their path, the newer "time" wins per entry. Both
     * devices converge to the same union — an entry added offline is kept and
     * nothing disappears in an mtime race.
     *
     * @return true when the LOCAL file was (re)written.
     */
    static boolean syncMetaUnion(Sardine s, String globalUrl, File local) {
        if (local == null || !local.isFile()) {
            return false;
        }
        final String name = local.getName();
        final String url = globalUrl + "/" + name;
        try {
            final String localText = readText(local);
            final String remoteText = fetchText(s, url);
            if (remoteText == null) {
                SyncChangeLog.add(name, "(整个列表)", "down", "(未同步：服务器暂不可达)", null);
                return false;
            }
            final JSONArray localArr = localText.trim().isEmpty() ? new JSONArray() : new JSONArray(localText);
            final JSONArray remoteArr = remoteText.trim().isEmpty() ? new JSONArray() : new JSONArray(remoteText);
            final Map<String, LinkedJSONObject> merged = new LinkedHashMap<String, LinkedJSONObject>();
            for (int i = 0; i < localArr.length(); i++) {
                final LinkedJSONObject e = localArr.optJSONObject(i);
                final String k = e == null ? "" : e.optString(SimpleMeta.JSON_PATH, "");
                if (e != null && k.length() > 0) {
                    merged.put(k, e);
                }
            }
            int added = 0, updated = 0;
            for (int i = 0; i < remoteArr.length(); i++) {
                final LinkedJSONObject e = remoteArr.optJSONObject(i);
                final String k = e == null ? "" : e.optString(SimpleMeta.JSON_PATH, "");
                if (e == null || k.length() == 0) {
                    continue;
                }
                final LinkedJSONObject l = merged.get(k);
                if (l == null) {
                    merged.put(k, e);
                    added++;
                } else if (e.optLong(SimpleMeta.JSON_TIME, 0) > l.optLong(SimpleMeta.JSON_TIME, 0)) {
                    merged.put(k, e);
                    updated++;
                }
            }
            final JSONArray outArr = new JSONArray();
            for (LinkedJSONObject e : merged.values()) {
                outArr.put(e);
            }
            final String outText = outArr.toString();
            boolean localChanged = !normalize(outText).equals(normalize(localText));
            if (localChanged) {
                IO.writeString(local, outText);
                SyncChangeLog.add(name, "(条目)", "down", "共" + localArr.length() + "条",
                        "共" + outArr.length() + "条（新" + added + " 更新" + updated + "）");
                android.util.Log.i("BENCH", "sync " + name + ": meta union local updated (+" + added + ")");
            }
            if (!normalize(outText).equals(normalize(remoteText))) {
                s.put(url, outText.getBytes("UTF-8"));
            }
            return localChanged;
        } catch (Exception e) {
            LOG.e(e, "WebDavSyncer meta", name);
            return false;
        }
    }


    /** Two-way merge of a JSON-object state file via the given merger. */
    static void syncMergedObjectFile(Sardine s, String globalUrl, File local, JsonMerger merger) {
        try {
            if (local == null || !local.isFile()) {
                return;
            }
            final String url = globalUrl + "/" + local.getName();
            LinkedJSONObject localObj = IO.readJsonObject(local);
            String remoteText = fetchText(s, url);
            if (remoteText == null) {
                // transient GET failure: touch neither the server nor the local file
                android.util.Log.i("BENCH", "sync " + local.getName() + ": remote error, skipped");
                return;
            }
            if (remoteText.isEmpty()) {
                if (localObj.length() > 0) {
                    s.put(url, localObj.toString().getBytes("UTF-8"));
                }
                return;
            }
            LinkedJSONObject remoteObj;
            try {
                remoteObj = new LinkedJSONObject(remoteText);
            } catch (Exception badPayload) {
                remoteObj = new LinkedJSONObject();
            }
            if (remoteObj.length() == 0) {
                if (localObj.length() > 0) {
                    s.put(url, localObj.toString().getBytes("UTF-8"));
                }
                return;
            }
            LinkedJSONObject merged = merger.merge(localObj, remoteObj);
            if (merged == null || merged.length() == 0) {
                return;
            }
            if (!merged.toString().equals(localObj.toString())) {
                IO.writeObjSync(local, merged);
            }
            if (!merged.toString().equals(remoteObj.toString())) {
                s.put(url, merged.toString().getBytes("UTF-8"));
            }
        } catch (Exception e) {
            LOG.e(e, "WebDavSyncer object", local.getName());
        }
    }

    /** Copy of the object without the device-bound (and volatile) fields. */
    private static LinkedJSONObject withoutFields(LinkedJSONObject obj, Set<String> fields) {
        LinkedJSONObject copy = new LinkedJSONObject();
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            if (fields.contains(k)) {
                continue;
            }
            try {
                copy.put(k, obj.get(k));
            } catch (Exception ignored) {
            }
        }
        return copy;
    }

    /** Re-apply the local device-bound fields over an object won by the server. */
    private static void keepLocalFields(LinkedJSONObject merged, LinkedJSONObject localObj, Set<String> fields) {
        for (String k : fields) {
            try {
                if (localObj.has(k)) {
                    merged.put(k, localObj.get(k));
                } else {
                    merged.remove(k);
                }
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * True when the local file still equals the out-of-the-box defaults in
     * every personal (non device-bound) field: the user has never customized
     * this device, so the server copy must win the first sync instead of
     * being clobbered by freshly generated defaults.
     */
    static boolean isDefaultConfig(String localText, Set<String> deviceFields, boolean stateFile) {
        try {
            final LinkedJSONObject def;
            if (stateFile) {
                def = new LinkedJSONObject(com.foobnix.android.utils.Objects.toJSONString(defaultAppState()));
            } else {
                BookCSS css = new BookCSS();
                String hyphenLang = AppSP.get().hypenLang;
                css.resetToDefault(null);
                AppSP.get().hypenLang = hyphenLang; // resetToDefault touches the global AppSP
                def = new LinkedJSONObject(com.foobnix.android.utils.Objects.toJSONString(css));
            }
            return normalize(withoutFields(def, deviceFields).toString())
                    .equals(normalize(withoutFields(new LinkedJSONObject(localText), deviceFields).toString()));
        } catch (Exception e) {
            LOG.e(e, "WebDavSyncer isDefaultConfig");
            return false;
        }
    }

    /**
     * A fresh AppState as defaults() writes it on first run: the localized
     * mode labels, the theme following the system dark mode, the rebrand
     * day/night colors and the What's-New default. The accessibility and
     * e-ink adjustments of defaults() are deliberately not reproduced (they
     * mutate global singletons): on such devices default detection simply
     * stays false and the classic mtime rule applies.
     */
    private static AppState defaultAppState() {
        AppState st = new AppState();
        final Context a = LibreraApp.context;
        if (a != null) {
            st.nameVerticalMode = a.getString(R.string.mode_vertical);
            st.nameHorizontalMode = a.getString(R.string.mode_horizontally);
            st.nameMusicianMode = a.getString(R.string.mode_musician);
            st.musicText = a.getString(R.string.musician);
            final String pkg = com.foobnix.android.utils.Apps.getPackageName(a);
            if (!AppsConfig.LIBRERA_READER.equals(pkg)
                    && !AppsConfig.PRO_LIBRERA_READER.equals(pkg)
                    && !AppsConfig.FDROID_LIBRERA_READER.equals(pkg)) {
                st.isShowWhatIsNewDialog = false;
            }
        }
        // mirrors defaults(): brand default is LIGHT regardless of system dark mode
        st.appTheme = AppState.THEME_LIGHT;
        // first-run migration in loadInit() flips this once (the tab merge);
        // the saved default file always carries the migrated value
        st.networkTabMerged = true;
        st.colorDayText = AppState.COLOR_BLACK;
        st.isUseBGImageDay = true;
        st.colorDayBg = AppState.COLOR_WHITE;
        st.colorDayForeground = AppState.COLOR_DAY_FG;
        st.isUseBGImageNight = true;
        st.colorNigthText = AppState.COLOR_WHITE;
        st.colorNigthBg = AppState.COLOR_BLACK;
        st.colorNigthForeground = AppState.COLOR_NIGHT_FG;
        return st;
    }

    private static String normalize(String json) {
        try {
            return new LinkedJSONObject(json).toString();
        } catch (Exception e) {
            return json == null ? "" : json.trim();
        }
    }

    // ------------------------------------------------------------------ books

    /**
     * Local per-book info keyed by book file name: reading progress (own
     * device entry) + bookmarks/AI notes grouped by book. The hash identifies
     * the book file; books whose file is missing locally are NOT included —
     * they never create server info files (see the identity loop below).
     */
    static Map<String, LinkedJSONObject> buildLocalBooks(LinkedJSONObject localP, LinkedJSONObject localB) {
        final Map<String, LinkedJSONObject> books = new LinkedHashMap<String, LinkedJSONObject>();
        final Map<String, LinkedJSONObject> marks = new LinkedHashMap<String, LinkedJSONObject>();
        final Map<String, File> paths = new HashMap<String, File>();

        Iterator<String> bk = localB.keys();
        while (bk.hasNext()) {
            String key = bk.next();
            LinkedJSONObject bm = localB.optJSONObject(key);
            if (bm == null) {
                continue;
            }
            String path = bm.optString("path", null);
            if (TxtUtils.isEmpty(path)) {
                continue;
            }
            String name = ExtUtils.getFileName(MyPath.toAbsolute(path));
            if (TxtUtils.isEmpty(name)) {
                continue;
            }
            LinkedJSONObject set = marks.get(name);
            if (set == null) {
                set = new LinkedJSONObject();
                marks.put(name, set);
            }
            try {
                set.put(key, bm);
            } catch (Exception ignored) {
            }
            File f = new File(MyPath.toAbsolute(path));
            if (f.isFile()) {
                paths.put(name, f);
            }
        }

        Iterator<String> pk = localP.keys();
        while (pk.hasNext()) {
            String name = pk.next();
            LinkedJSONObject entry = localP.optJSONObject(name);
            if (entry == null) {
                continue;
            }
            LinkedJSONObject info = ensure(books, name);
            info.put("progress", entry);
            info.put("t", Math.max(info.optLong("t", 0), entry.optLong("t", 0)));
        }
        for (Map.Entry<String, LinkedJSONObject> e : marks.entrySet()) {
            LinkedJSONObject info = ensure(books, e.getKey());
            info.put("bookmarks", e.getValue());
            long newest = 0;
            Iterator<String> it = e.getValue().keys();
            while (it.hasNext()) {
                LinkedJSONObject bm = e.getValue().optJSONObject(it.next());
                if (bm != null) {
                    newest = Math.max(newest, bm.optLong("t", 0));
                }
            }
            info.put("t", Math.max(info.optLong("t", 0), newest));
        }
        // fill identity: name + content hash — and only for books whose file
        // actually exists here. A book the user never opened on this device
        // (or whose file is gone) must NOT create a books/<hash>.json on the
        // server: the device that opened the book maintains its server copy.
        for (Iterator<Map.Entry<String, LinkedJSONObject>> it = books.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, LinkedJSONObject> e = it.next();
            String name = e.getKey();
            File f = paths.containsKey(name) ? paths.get(name) : findInLibrary(name);
            if (f == null) {
                it.remove();
                continue;
            }
            e.getValue().put("name", name);
            e.getValue().put("hash", FileHash.md5(f));
        }
        return books;
    }

    private static LinkedJSONObject ensure(Map<String, LinkedJSONObject> map, String key) {
        LinkedJSONObject o = map.get(key);
        if (o == null) {
            o = new LinkedJSONObject();
            map.put(key, o);
        }
        return o;
    }

    /** Bookmark subset of one book, in the per-book info shape ({"t": bm}). */
    static LinkedJSONObject subsetFor(LinkedJSONObject localB, String name) {
        LinkedJSONObject out = new LinkedJSONObject();
        Iterator<String> keys = localB.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            LinkedJSONObject bm = localB.optJSONObject(key);
            if (bm == null) {
                continue;
            }
            String p = bm.optString("path", null);
            if (TxtUtils.isEmpty(p)) {
                continue;
            }
            if (name.equals(ExtUtils.getFileName(MyPath.toAbsolute(p)))) {
                try {
                    out.put(key, bm);
                } catch (Exception ignored) {
                }
            }
        }
        return out;
    }

    /** Existing local files by book file name: bookmark paths + library DB. */
    static Map<String, File> buildLocalCandidates() {
        final Map<String, File> candidates = new HashMap<String, File>();
        for (AppBookmark b : BookmarksData.get().getAll()) {
            String p = b.getPath();
            if (p == null) {
                continue;
            }
            File f = new File(p);
            if (f.isFile()) {
                candidates.put(ExtUtils.getFileName(p), f);
            }
        }
        try {
            for (FileMeta m : AppDB.get().getAll()) {
                String p = m.getPath();
                if (TxtUtils.isEmpty(p) || p.startsWith("content:")) {
                    continue;
                }
                File f = new File(p);
                if (f.isFile() && !candidates.containsKey(ExtUtils.getFileName(p))) {
                    candidates.put(ExtUtils.getFileName(p), f);
                }
            }
        } catch (Exception e) {
            LOG.e(e);
        }
        return candidates;
    }

    /** Library-DB lookup used when no bookmark path points at the book. */
    static File findInLibrary(String fileName) {
        try {
            for (FileMeta m : AppDB.get().getAll()) {
                String p = m.getPath();
                if (TxtUtils.isEmpty(p) || p.startsWith("content:")) {
                    continue;
                }
                File f = new File(p);
                if (f.isFile() && ExtUtils.getFileName(p).equals(fileName)) {
                    return f;
                }
            }
        } catch (Exception e) {
            LOG.e(e);
        }
        return null;
    }

    /**
     * Remote per-book infos keyed by the hash in their file name.
     *
     * @param listFailedOut [0] is set to true when the directory listing or a
     * single-file GET failed with a network error (as opposed to a 404). A
     * book that is missing for that reason is NOT in the map, so the caller
     * must not consume deletion tombstones this round — otherwise a transient
     * error would clear the tombstone and the next round would union-merge
     * the "deleted" bookmarks back from the server.
     */
    static Map<String, LinkedJSONObject> listRemoteBooks(Sardine s, String booksUrl, boolean[] listFailedOut) {
        final Map<String, LinkedJSONObject> out = new LinkedHashMap<String, LinkedJSONObject>();
        List<DavResource> list;
        try {
            list = s.list(booksUrl, 1);
        } catch (Exception e) {
            LOG.d("WebDavSyncer list books", e.getMessage());
            if (listFailedOut != null) {
                listFailedOut[0] = true;
            }
            return out;
        }
        for (DavResource r : list) {
            try {
                if (r.isDirectory()) {
                    continue;
                }
                String n = r.getName();
                if (n == null || !n.endsWith(".json")) {
                    continue;
                }
                String hash = n.substring(0, n.length() - ".json".length());
                LinkedJSONObject info = fetchJson(s, booksUrl + "/" + n);
                if (info == null) {
                    if (listFailedOut != null) {
                        listFailedOut[0] = true;
                    }
                    continue;
                }
                if (info.length() > 0) {
                    out.put(hash, info);
                }
            } catch (Exception e) {
                LOG.e(e, "WebDavSyncer remote book");
                if (listFailedOut != null) {
                    listFailedOut[0] = true;
                }
            }
        }
        return out;
    }

    static void putBookInfo(Sardine s, String booksUrl, String hash, LinkedJSONObject info) throws IOException {
        s.put(booksUrl + "/" + hash + ".json", info.toString().getBytes("UTF-8"));
    }

    /** Deletion records expire after this long; a much later re-share of the
     * same bookmark key could union back after expiry (accepted trade-off). */
    static final long DK_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000;

    /**
     * Semantic comparison of two book info objects that ignores the display
     * timestamp "t": the previous equality checks included a freshly stamped
     * t=now, so a matched book was re-PUT on every round from every device.
     */
    static boolean sameIgnoringT(LinkedJSONObject a, LinkedJSONObject b) {
        if (a == null || b == null) {
            return a == b;
        }
        try {
            final LinkedJSONObject x = new LinkedJSONObject(a.toString());
            x.remove("t");
            final LinkedJSONObject y = new LinkedJSONObject(b.toString());
            y.remove("t");
            return normalize(x.toString()).equals(normalize(y.toString()));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Merge the deletion records (bookmark creation-key → deletion time) this
     * device and the server know about the book: the newer record per key
     * wins. Records superseded by a re-creation (deletion time older than the
     * key itself) and expired records are dropped.
     */
    static LinkedJSONObject buildDeletedKeys(String name, LinkedJSONObject remoteDk,
                                             LinkedJSONObject localMarkers) {
        try {
            final LinkedJSONObject out = new LinkedJSONObject();
            if (remoteDk != null) {
                for (Iterator<String> it = remoteDk.keys(); it.hasNext();) {
                    final String k = it.next();
                    out.put(k, remoteDk.optLong(k, 0));
                }
            }
            if (localMarkers != null) {
                final LinkedJSONObject marks = localMarkers.optJSONObject(name);
                final LinkedJSONObject localKeys = marks == null ? null : marks.optJSONObject("keys");
                if (localKeys != null) {
                    for (Iterator<String> it = localKeys.keys(); it.hasNext();) {
                        final String k = it.next();
                        final long t = localKeys.optLong(k, 0);
                        if (t > out.optLong(k, 0)) {
                            out.put(k, t);
                        }
                    }
                }
            }
            final long now = System.currentTimeMillis();
            for (Iterator<String> it = out.keys(); it.hasNext();) {
                final String k = it.next();
                final long dkTime = out.optLong(k, 0);
                boolean recreated = false;
                try {
                    recreated = dkTime <= Long.parseLong(k);
                } catch (NumberFormatException notAKey) {
                }
                if (recreated || now - dkTime > DK_MAX_AGE_MS) {
                    it.remove();
                }
            }
            return out.length() > 0 ? out : null;
        } catch (Exception e) {
            LOG.e(e);
            return null;
        }
    }

    /**
     * End-of-round writeback that cannot revert mid-round user actions: the
     * UI thread keeps writing progress/bookmarks while the network phase
     * runs, and writing the start-of-round snapshots back used to lose those
     * writes. Progress entries converge per key with the newer "t"; bookmarks
     * union (round additions survive; keys tombstoned — also mid-round — are
     * removed so a deletion can never be republished through the snapshot).
     */
    private static void mergeSnapshotsAtEnd(LinkedJSONObject snapP, LinkedJSONObject snapB) {
        try {
            final LinkedJSONObject freshP = IO.readJsonObject(AppProfile.syncProgress);
            for (Iterator<String> it = snapP.keys(); it.hasNext();) {
                final String k = it.next();
                final LinkedJSONObject ours = snapP.optJSONObject(k);
                if (ours == null) {
                    continue;
                }
                final LinkedJSONObject cur = freshP.optJSONObject(k);
                if (cur == null || ours.optLong("t", 0) > cur.optLong("t", 0)) {
                    freshP.put(k, ours);
                }
            }
            IO.writeObjSync(AppProfile.syncProgress, freshP);

            final LinkedJSONObject freshB = IO.readJsonObject(AppProfile.syncBookmarks);
            for (Iterator<String> it = snapB.keys(); it.hasNext();) {
                final String k = it.next();
                if (!freshB.has(k)) {
                    final Object v = snapB.opt(k);
                    if (v != null) {
                        freshB.put(k, v);
                    }
                }
            }
            try {
                final LinkedJSONObject freshDel = SharedBooks.DeletedBooks.all();
                for (Iterator<String> it = freshDel.keys(); it.hasNext();) {
                    final String name = it.next();
                    for (String dkKey : SharedBooks.DeletedBooks.keysOf(freshDel, name)) {
                        freshB.remove(dkKey);
                    }
                }
            } catch (Exception delError) {
                LOG.e(delError);
            }
            IO.writeObjSync(AppProfile.syncBookmarks, freshB);
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /**
     * Per-book info document: the book file name and content hash (identity),
     * reading progress and the bookmarks/AI-notes map. "t" is the newest
     * update time among the entries, for display/debug.
     */
    static LinkedJSONObject buildInfoWithHash(String name, String hash, LinkedJSONObject progress,
                                              LinkedJSONObject bookmarks) {
        return buildInfoWithHash(name, hash, progress, bookmarks, 0);
    }

    /** Same as above, but keeps the given timestamp (0 = stamp now). */
    static LinkedJSONObject buildInfoWithHash(String name, String hash, LinkedJSONObject progress,
                                              LinkedJSONObject bookmarks, long t) {
        try {
            LinkedJSONObject info = new LinkedJSONObject();
            info.put("name", name);
            info.put("hash", hash == null ? "" : hash);
            info.put("t", t > 0 ? t : System.currentTimeMillis());
            if (progress != null) {
                info.put("progress", progress);
            }
            if (bookmarks != null && bookmarks.length() > 0) {
                info.put("bookmarks", bookmarks);
            }
            return info;
        } catch (Exception e) {
            LOG.e(e);
            return null;
        }
    }

    /**
     * Apply one remote progress entry for one book; the configured policy
     * decides the winner: "newer" — newer edit time ("t"), "farther" — the
     * position closer to the end of the book ("p"), "local" — this device
     * always wins, "server" — the server copy always wins.
     */
    static boolean mergeProgressEntry(LinkedJSONObject localP, String name, LinkedJSONObject r, String policy) {
        LinkedJSONObject l = localP.optJSONObject(name);
        boolean remoteWins;
        if ("local".equals(policy)) {
            remoteWins = l == null;
        } else if ("server".equals(policy)) {
            remoteWins = true;
        } else if (l == null) {
            remoteWins = true;
        } else if ("farther".equals(policy)) {
            float rp = (float) r.optDouble("p", 0);
            float lp = (float) l.optDouble("p", 0);
            remoteWins = rp > lp || (rp == lp && r.optLong("t", 0) > l.optLong("t", 0));
        } else {
            remoteWins = r.optLong("t", 0) > l.optLong("t", 0);
        }
        if (remoteWins) {
            localP.put(name, r);
        }
        return remoteWins;
    }

    // ------------------------------------------------------------------ io helpers

    private static void mkDirs(Sardine s, String... dirs) {
        for (String dir : dirs) {
            // 405 "already exists" is the normal case on every sync after the first
            try {
                s.createDirectory(dir);
            } catch (IOException e) {
                LOG.d("WebDavSyncer mkcol", e.getMessage());
            }
        }
    }

    /**
     * GET a remote JSON object.
     *
     * @return the object; an empty object when the file does not exist (404)
     * or the payload is empty; null on any OTHER failure (auth, network, SSL,
     * timeout) — the caller must treat the round as unreliable, because an
     * empty object would look like "the file is gone" and let a deletion
     * tombstone be consumed while the server copy is still there.
     */
    static LinkedJSONObject fetchJson(Sardine s, String url) {
        try {
            String text = fetchText(s, url);
            return text == null ? null : new LinkedJSONObject(text);
        } catch (Exception e) {
            LOG.d("WebDavSyncer fetch", url, e.getMessage());
            return null;
        }
    }

    /**
     * GET a remote text file. "" when it does not exist (404 — the caller may
     * seed-upload a local copy); null on any OTHER failure (auth, network,
     * SSL, timeout) — the caller must then touch neither the server nor the
     * local file, otherwise one transient error would publish a possibly
     * incomplete local list over the converged server copy for good.
     */
    static String fetchText(Sardine s, String url) {
        InputStream is = null;
        try {
            is = s.get(url);
            return IO.readString(is);
        } catch (Exception e) {
            LOG.d("WebDavSyncer fetch", url, e.getMessage());
            return isNotFound(e) ? "" : null;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** True when the failure chain reports HTTP 404 / "not found". */
    private static boolean isNotFound(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            try {
                Object status = c.getClass().getMethod("getStatusCode").invoke(c);
                if (status instanceof Integer && (Integer) status == 404) {
                    return true;
                }
            } catch (Exception ignored) {
            }
            // substring fallback only for short status-like messages: a book
            // hash containing "404" inside a full URL/message must not make a
            // server error look like "file doesn't exist" (that read path
            // seeds an empty copy over the live server file)
            final String msg = c.getMessage();
            if (msg != null && msg.contains("404") && !msg.contains("://") && msg.length() < 80) {
                return true;
            }
        }
        return false;
    }

    /** True when the deleted-books marker file records the given kind ("p"/"b") for the book. */
    static boolean markKind(LinkedJSONObject markers, String name, String kind) {
        LinkedJSONObject t = markers.optJSONObject(name);
        return t != null && t.optLong(kind, 0) > 0;
    }

    private static String readText(File f) throws IOException {
        InputStream is = null;
        try {
            is = new FileInputStream(f);
            return IO.readString(is);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    static String classify(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof SSLException) {
                return "ssl";
            }
            if (c instanceof UnknownHostException) {
                return "network";
            }
            String msg = c.getMessage();
            if (msg != null && (msg.contains("401") || msg.contains("403"))) {
                return "auth";
            }
            String simple = c.getClass().getSimpleName();
            if (simple.contains("Unauthorized") || simple.contains("Forbidden")) {
                return "auth";
            }
            if (simple.contains("Timeout") || simple.contains("Connect") || simple.contains("Socket")) {
                return "network";
            }
        }
        return "other";
    }
}
