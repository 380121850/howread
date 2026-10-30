package com.foobnix;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.widget.Toast;

import com.foobnix.android.utils.Cursors;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.MyMath;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.dao2.FileMeta;
import com.foobnix.model.AppData;
import com.foobnix.model.AppProfile;
import com.foobnix.model.AppSP;
import com.foobnix.model.SimpleMeta;
import com.foobnix.mobi.parser.IOUtils;
import com.foobnix.pdf.info.Clouds;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.pdf.info.R;
import com.foobnix.ui2.AppDB;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Shared open-intent resolution, extracted from the legacy OpenerActivity
 * trampoline (batch 2, 2026-10-21): system VIEW intents now land on
 * VerticalViewActivity directly, and both entries resolve the target file
 * through this one code path (content:// copy, Telegram/Downloads fallbacks,
 * TempDownloads copy) so their behavior cannot drift apart.
 */
public class OpenerIntents {

    public static String TAG = "OpenerActivity";

    /** Resolve a VIEW intent to a readable local file, applying the same
     *  fallback chain the legacy opener used (direct path, content id,
     *  MediaStore Downloads lookup, TempDownloads copy). Shows the error
     *  toast and returns null when nothing resolves. */
    public static File resolve(final Activity context, final Intent intent) {
        if (intent == null) {
            return null;
        }
        Uri uri = intent.getData();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            try {
                if (uri == null && intent.getClipData() != null) {
                    intent.setData(intent.getClipData().getItemAt(0).getUri());
                    uri = intent.getData();
                }
            } catch (Exception e) {
                LOG.e(e);
            }
        }
        if (uri == null) {
            Toast.makeText(context, R.string.msg_unexpected_error, Toast.LENGTH_SHORT).show();
            return null;
        }

        LOG.d(TAG, intent);
        LOG.d(TAG, " Uri", uri);
        LOG.d(TAG, " dataPath", getDataPath(intent));
        LOG.d(TAG, " Scheme", intent.getScheme());
        LOG.d(TAG, " Mime", intent.getType());

        File downloadsDir = AppSP.get().getTempDownloadBooks(context.getApplicationContext());
        downloadsDir.mkdirs();

        File file = new File("");

        if ("file".equals(uri.getScheme())) {
            LOG.d(TAG, "Find file in file");
            file = new File(uri.getPath());
        }

        String name = getCursorValue(context, MediaStore.MediaColumns.DISPLAY_NAME);
        String id = uri.getLastPathSegment();
        String size = getCursorValue(context, MediaStore.MediaColumns.SIZE);
        String dataPath = getDataPath(intent);

        LOG.d(TAG, "id", id, "name", name, "size", size);

        if (!file.isFile()) {
            LOG.d(TAG, "Find file in getLastPathSegment");
            if (id != null) {
                String utfID = Uri.decode(id).replace("file://", "");
                LOG.d(TAG, "utfID", utfID);
                file = new File(utfID);
                LOG.d(TAG, "File:", file.getPath());
            }
        }

        if (!file.isFile()) {
            LOG.d(TAG, "Find file in getDataPath");
            file = new File(dataPath);
            LOG.d(TAG, "File:", file.getPath());
        }
        if (!file.isFile()) {
            LOG.d(TAG, "Find file in getDataPath /indexOf 1");
            int beginIndex = dataPath.indexOf("/", 1);
            if (beginIndex > 0) {
                file = new File(dataPath.substring(beginIndex));
            }
            LOG.d(TAG, "File:", file.getPath());
        }
        if (!file.isFile()) {
            LOG.d(TAG, "Find file in getDataPath /indexOf 2");
            int beginIndex = dataPath.indexOf("/", 1);
            if (beginIndex > 0) {
                file = new File(dataPath.substring(beginIndex));
            }
            LOG.d(TAG, "File:", file.getPath());
        }

        if (!file.isFile()) {
            LOG.d(TAG, "Find file in Telegram");
            if (dataPath.startsWith("/media/Android/data/org.telegram.messenger/files/")) {
                file = new File(AppProfile.DOWNLOADS_DIR, "Telegram/" + name);
            }
            LOG.d(TAG, "File:", file.getPath());
        }

        if (!file.isFile() && id != null) {
            LOG.d(TAG, "Find file in all Downloads by name and id", name, id);
            String documentID = id.replaceAll("\\D", "");
            LOG.d(TAG, "documentID", documentID);
            String fileInDownlaods = findFileInDownloads(context, name, documentID);
            if (fileInDownlaods != null) {
                LOG.d(TAG, "findFileInDownloads", fileInDownlaods);
                file = new File(fileInDownlaods);
            }
            LOG.d(TAG, "File:", file.getPath());
        }

        if (!file.isFile()) {
            LOG.d(TAG, "Find file in [Librera/Downloads]");
            downloadsDir.mkdirs();
            LOG.d(TAG, "Find file in [Downloads]");
            if (TxtUtils.isEmpty(ExtUtils.getFileExtension(name))) {
                String extByMimeType = ExtUtils.getExtByMimeType(intent.getType());
                LOG.d("extByMimeType", extByMimeType, intent.getType());
                name = name + "." + extByMimeType;
            }
            name = TxtUtils.fixFileName(name);
            file = new File(downloadsDir, name);

            if (file.length() != MyMath.longValueOfNoException(size)) {
                file.delete();
                LOG.d(TAG, "Delete old file");
            }
            LOG.d(TAG, "File:", file.getPath());
        }

        if (!file.isFile()) {
            LOG.d(TAG, "Create file in [Librera/Downloads]");
            try {
                FileOutputStream out = new FileOutputStream(file);
                InputStream inputStream = context.getContentResolver().openInputStream(uri);
                IOUtils.copyClose(inputStream, out);
            } catch (Exception e) {
                LOG.e(e);
            }
            LOG.d(TAG, "File:", file.getPath());
        }

        if (file == null || !file.canRead() || !file.isFile()) {
            Toast.makeText(context, R.string.msg_unexpected_error, Toast.LENGTH_SHORT).show();
            return null;
        }
        return file;
    }

    /** A book opened from ANOTHER app joins the library by default — the
     *  same flag write the manual "add to library" action uses. Cache
     *  copies (the content:// fallback lands in TempDownloads), remote /
     *  cloud paths and books the user explicitly removed from the library
     *  (excluded list) are never auto-added. */
    public static void autoAddToLibrary(final Context c, final File file) {
        try {
            final String p = file.getPath();
            final boolean cacheCopy = p.startsWith(AppSP.get().getTempDownloadBooks(c).getPath())
                    || p.startsWith(c.getCacheDir().getPath())
                    || (c.getExternalCacheDir() != null && p.startsWith(c.getExternalCacheDir().getPath()));
            final boolean excluded = AppData.get().getAllExcluded()
                    .contains(SimpleMeta.SyncSimpleMeta(p));
            if (com.foobnix.model.AppState.get().isAutoAddToLibrary && !cacheCopy && !excluded
                    && !com.foobnix.remote.RemoteBook.isRemotePathLoose(p)
                    && !Clouds.isCloud(p)) {
                FileMeta load = AppDB.get().getOrCreate(p);
                if (load.getIsSearchBook() == null || !load.getIsSearchBook()) {
                    load.setIsSearchBook(true);
                    AppDB.get().update(load);
                    AppData.get().removeExcluded(load);
                    Toast.makeText(c, R.string.add_to_library, Toast.LENGTH_SHORT).show();
                }
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    public static String findFileInDownloads(Context context, String name, String id) {
        try {
            ContentResolver contentResolver = context.getContentResolver();
            Uri uri = MediaStore.Files.getContentUri("external");

            String[] projection = {
                    MediaStore.Files.FileColumns.DATA
            };

            String selection = MediaStore.Files.FileColumns._ID + " = ? AND " +
                    MediaStore.Files.FileColumns.DISPLAY_NAME + " = ?";

            String[] selectionArgs = new String[]{id, name};

            try (Cursor cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int filePathId = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA);
                    String filePath = cursor.getString(filePathId);
                    LOG.d(TAG, "FileFinder", "File Path: " + filePath);
                    return filePath;
                }
            }
        } catch (Exception e) {
            LOG.e(e);
        }

        return null;
    }

    private static String getCursorValue(Activity context, String id) {
        try {
            return Cursors.getValue(context, id);
        } catch (Exception e) {
            LOG.e(e);
            return null;
        }
    }

    private static String getDataPath(Intent intent) {
        if (intent == null) {
            return "";
        }
        if (intent.getData() == null) {
            return "";
        }

        String path = intent.getData().getPath();
        if (path == null) {
            return "";
        }

        return path;
    }
}
