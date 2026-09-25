package com.foobnix.remote;

import com.foobnix.ai.AiTranslator;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.pdf.info.model.BookCSS;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Offline base file for the AI in-page bilingual mode on remote books.
 *
 * The bilingual rewrite needs a real local epub/html file; a remote book
 * qualifies only when it is fully available offline — either the user
 * downloaded a complete copy (Remote/books/&lt;sha&gt;/&lt;name&gt; with a
 * matching .tag) or the read-through block cache has been filled to 100%
 * (meta.json fullyCached). The base is then the copy itself, or a file
 * assembled from the block cache with cache-only block reads (never the
 * network). Everything lives inside the block-cache book dir, so a server
 * version change wipes it together with the cache.
 */
public class RemoteBilingualBase {

    private RemoteBilingualBase() {
    }

    /** Cheap check (meta/tag reads only, UI-thread safe): can this remote
     * book be rewritten for the bilingual mode right now? Only epub-like and
     * single-html bases qualify — the other remote text formats have no
     * offline conversion chain here. */
    public static boolean isFullyAvailable(String remotePath) {
        String p = RemoteBook.fixCollapsed(remotePath);
        if (!rewritableRemote(p)) {
            return false;
        }
        if (currentCopy(p) != null) {
            return true;
        }
        BlockCacheStore cache = BlockCacheStore.openExisting(RemoteBook.cacheKey(p));
        if (cache == null) {
            return false;
        }
        try {
            return cache.isFullyCached();
        } finally {
            closeQuietly(cache);
        }
    }

    /**
     * The local base file for the bilingual rewrite; assembles it from the
     * block cache on first use (disk-only work — do not call on the UI
     * thread). Returns null when the book is not fully cached offline.
     */
    public static File resolveLocalBaseForBilingual(String remotePath) {
        String p = RemoteBook.fixCollapsed(remotePath);
        if (!rewritableRemote(p)) {
            return null;
        }
        File copy = currentCopy(p);
        if (copy != null) {
            return copy;
        }
        BlockCacheStore cache = BlockCacheStore.openExisting(RemoteBook.cacheKey(p));
        if (cache == null) {
            return null;
        }
        try {
            if (!cache.isFullyCached()) {
                return null;
            }
            return assembledFile(cache);
        } finally {
            closeQuietly(cache);
        }
    }

    /** The downloaded full copy when it matches the current size+version. */
    private static File currentCopy(String p) {
        File dir = RemoteBookOpener.cacheBookFile(p).getParentFile();
        String name = RemoteBookOpener.cacheBookFile(p).getName();
        File target = new File(dir, name);
        File tag = new File(dir, name + ".tag");
        if (!target.isFile() || target.length() <= 0 || !tag.isFile()) {
            return null;
        }
        long size = BlockCacheStore.peekSize(p);
        String version = BlockCacheStore.peekVersionTag(RemoteBook.cacheKey(p));
        if (size <= 0 || target.length() != size || TxtUtils.isEmpty(version)) {
            return null;
        }
        try {
            FileInputStream in = new FileInputStream(tag);
            try {
                byte[] b = new byte[(int) tag.length()];
                int read = in.read(b);
                String cached = read <= 0 ? "" : new String(b, 0, read, "UTF-8");
                return cached.equals(version) ? target : null;
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Assemble the base from the fully-cached blocks (cache-only reads). */
    private static File assembledFile(BlockCacheStore cache) {
        File out = new File(cache.dirPath(), "bilingual-base.epub");
        File meta = new File(cache.dirPath(), "meta.json");
        if (out.isFile() && out.length() == cache.getFileSize()
                && out.lastModified() >= meta.lastModified()) {
            return out;
        }
        long size = cache.getFileSize();
        int blockSize = cache.getBlockSize();
        long blocks = (size + blockSize - 1) / blockSize;
        File tmp = new File(cache.dirPath(), "bilingual-base.tmp");
        tmp.delete();
        long written = 0;
        try {
            OutputStream o = new FileOutputStream(tmp);
            try {
                String tag = cache.getVersionTag();
                for (long i = 0; i < blocks; i++) {
                    byte[] b = cache.getBlock(i, tag);
                    if (b == null) {
                        return null; // not fully cached after all
                    }
                    o.write(b, 0, b.length);
                    written += b.length;
                }
            } finally {
                o.close();
            }
        } catch (Exception e) {
            LOG.e(e);
            tmp.delete();
            return null;
        }
        if (written != size) {
            tmp.delete();
            return null;
        }
        if (!tmp.renameTo(out)) {
            out.delete();
            if (!tmp.renameTo(out)) {
                tmp.delete();
                return null;
            }
        }
        android.util.Log.i("BENCH", "RemoteBilingualBase assembled size=" + written
                + " out=" + out.getName());
        return out;
    }

    private static boolean rewritableRemote(String p) {
        if (!RemoteBook.isRemotePath(p) || !AiTranslator.isSupportedFormat(p)) {
            return false;
        }
        String ext = RemoteBook.getExt(p);
        // only formats whose offline base the bilingual engine can rewrite:
        // epub containers and single converted html files
        return "epub".equals(ext) || "epub2".equals(ext)
                || "html".equals(ext) || "htm".equals(ext);
    }

    private static File booksDir(String p) {
        return new File(new File(new File(BookCSS.get().cachePath, "Remote"), "books"),
                RemoteBook.cacheKey(p));
    }

    private static void closeQuietly(BlockCacheStore cache) {
        try {
            cache.close();
        } catch (Exception ignored) {
        }
    }
}
