package com.foobnix.ai;

import com.foobnix.android.utils.FileHash;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.ext.CacheZipUtils;
import com.foobnix.ext.Fb2Extractor;
import com.foobnix.sys.ArchiveEntry;
import com.foobnix.sys.ZipArchiveInputStream;
import com.foobnix.sys.Zips;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipOutputStream;

/**
 * Builds the "bilingual edition" of a text book for the in-page AI translate
 * mode.
 *
 * Rendering in this app is native MuPDF layout of a (possibly pre-processed)
 * epub: there is no per-page DOM to inject into, so the only way to get a
 * translation to actually render inside the page is to append a translated
 * block right after the source paragraph in the book content and re-layout.
 * This class rewrites an epub-like file (base) into a new file where every
 * paragraph whose cleaned text has a done translation in the cache is followed
 * by
 *
 *     <p class="aitran">鈥ranslation鈥?/p>
 *
 * The .aitran user-CSS rule in BookCSS gives that block its distinct
 * background. The output file name embeds a snapshot hash of the translated
 * md5 set, so the MuPDF accelerator key (already content-versioned) is
 * naturally invalidated/reused per version.
 *
 * Paragraphs are identified by md5 of their cleaned text only ("h<md5>" /
 * TranslationCache.doneByTextHash) 鈥?stable across pid conventions and across
 * reflows, and identical repeated text is translated once.
 */
public class BilingualBuilder {

    /** One source paragraph of the base file. */
    public static class Para {
        public final String file;   // zip entry name inside the base epub
        public final int ordinal;   // global paragraph order
        public final String text;   // cleaned paragraph text
        public final String md5;    // md5 of text

        Para(String file, int ordinal, String text, String md5) {
            this.file = file;
            this.ordinal = ordinal;
            this.text = text;
            this.md5 = md5;
        }
    }

    // The base file the reader actually opened in bilingual mode (the original
    // book for epub, the txt鈫抏pub/fb2鈫抏pub cache for converted formats). Set on
    // every successful build so a BilingualSession enumerates the exact same
    // paragraphs the document was laid out from.
    private static volatile String lastOriginalPath;
    private static volatile String lastBasePath;

    public static synchronized File baseFor(File originalBook) {
        if (lastOriginalPath != null && lastBasePath != null
                && lastOriginalPath.equals(originalBook == null ? null : originalBook.getPath())) {
            return new File(lastBasePath);
        }
        return originalBook;
    }

    /**
     * Publish the edition MuPDF is about to open for this original book (the
     * reader's working copy, or the bilingual edition built from it). The
     * translation session must enumerate THIS exact file: enumerating a
     * different base than the bilingual build uses made every paragraph md5
     * disagree and the build inject nothing. Called synchronously from the
     * document-open path — before a session can attach and enumerate.
     */
    public static void noteOpenEdition(String originalPath, String openPath) {
        lastOriginalPath = originalPath;
        lastBasePath = openPath;
    }

    /** Deterministic output file for the current translated-md5 snapshot. */
    private static File targetFile(File base, Map<String, String> done) {
        StringBuilder key = new StringBuilder();
        List<String> md5s = new ArrayList<String>(done.keySet());
        Collections.sort(md5s);
        for (String m : md5s) {
            key.append(m);
        }
        String snap = FileHash.md5(key.toString());
        if (snap != null && snap.length() > 12) {
            snap = snap.substring(0, 12);
        }
        String name = base.getName();
        int dot = name.lastIndexOf('.');
        String noExt = dot > 0 ? name.substring(0, dot) : name;
        File dir = CacheZipUtils.CACHE_TEMP != null ? CacheZipUtils.CACHE_TEMP : CacheZipUtils.CACHE_BOOK_DIR;
        return new File(dir, noExt + "__bi_" + snap + ".epub");
    }

    /**
     * Make sure the bilingual edition covering the given translated set exists
     * and return it; null when there is nothing translated yet (caller should
     * just open the base file) or when the base is not a rewritable epub.
     */
    public static synchronized File ensure(File originalBook, File base, TranslationCache cache,
            String src, String tgt) {
        if (base == null || !base.isFile() || cache == null) {
            return null;
        }
        // Remember what the reader opened even when nothing is translated yet,
        // so a session later enumerates the exact same paragraphs (txt/fb2 are
        // opened through their converted epub cache, not the original file).
        lastOriginalPath = originalBook == null ? null : originalBook.getPath();
        lastBasePath = base.getPath();
        Map<String, String> done = cache.doneByTextHash(src, tgt);
        if (done.isEmpty()) {
            LOG.d("BilingualBuilder", "no done translations, open base", base.getPath());
            return null;
        }
        if (isSingleHtmlBase(base)) {
            return ensureHtml(originalBook, base, done);
        }
        File out = targetFile(base, done);
        if (out.isFile() && out.length() > 0 && isReadableZip(out)) {
            LOG.d("BilingualBuilder", "cached bilingual", out.getPath());
            return out;
        }
        try {
            // build to a temp name and rename atomically: a direct write that
            // dies mid-zip used to leave a truncated file whose length>0 let
            // every later open() trust it forever (book unopenable until the
            // cache folder was cleared by hand)
            File tmp = new File(out.getParentFile(), out.getName() + ".tmp");
            tmp.delete();
            build(base, done, tmp);
            if (!isReadableZip(tmp)) {
                throw new IllegalStateException("built bilingual epub is not a readable zip");
            }
            if (!tmp.renameTo(out)) {
                out.delete();
                if (!tmp.renameTo(out)) {
                    throw new IllegalStateException("rename failed");
                }
            }
            cleanOldVersions(base, out);
            return out;
        } catch (Throwable t) {
            LOG.e(t);
            android.util.Log.i("BENCH", "BilingualBuilder FAIL " + t.getClass().getName() + " " + t.getMessage());
            new File(out.getParentFile(), out.getName() + ".tmp").delete();
            return null;
        }
    }

    /** Cheap structural check that the file is a readable zip container. */
    private static boolean isReadableZip(File f) {
        java.util.zip.ZipFile z = null;
        try {
            z = new java.util.zip.ZipFile(f);
            return z.size() > 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (z != null) {
                try {
                    z.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void cleanOldVersions(File base, File keep) {
        try {
            File dir = keep.getParentFile();
            if (dir == null) {
                return;
            }
            String name = base.getName();
            int dot = name.lastIndexOf('.');
            String prefix = (dot > 0 ? name.substring(0, dot) : name) + "__bi_";
            File[] files = dir.listFiles();
            if (files == null) {
                return;
            }
            for (File f : files) {
                if (f.isFile() && f.getName().startsWith(prefix)
                        && !f.getAbsolutePath().equals(keep.getAbsolutePath())) {
                    f.delete();
                }
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /** True when the base is a single converted HTML file (the html / doc /
     * docx / odt / rtf chains produce one html cache) rather than a zip epub:
     * the bilingual rewrite then patches the HTML text in place instead of
     * rebuilding zip entries. */
    private static boolean isSingleHtmlBase(File base) {
        String n = base == null ? null : base.getName().toLowerCase(Locale.US);
        return n != null && (n.endsWith(".html") || n.endsWith(".htm"));
    }

    /** Split a converted single-html cache on empty-paragraph separators
     * (some converters, e.g. the rtf chain, emit bare text with &lt;p&gt;&lt;/p&gt;
     * as paragraph breaks instead of &lt;p&gt;text&lt;/p&gt;). The segment before the
     * first separator (document head) stays at index 0. Returns null when the
     * file is normal &lt;p&gt;text&lt;/p&gt; content and the shared splitter applies. */
    private static List<String> splitHtmlSegments(String content) {
        // only when the document has NO real <p>text</p> paragraphs at all
        // (a stray whitespace-only <p> inside an otherwise normal document
        // must not flip it into separator mode: segment-level md5s would then
        // never match the per-paragraph text the rendered page reports)
        java.util.regex.Matcher pm = P_P.matcher(content);
        while (pm.find()) {
            if (!TxtUtils.isEmpty(clean(pm.group(1)))) {
                return null;
            }
        }
        String norm = content.replaceAll("<p\\s*>\\s*</p>", "\u0001");
        if (norm.indexOf('\u0001') < 0) {
            return null;
        }
        return new ArrayList<String>(java.util.Arrays.asList(norm.split("\u0001", -1)));
    }

    /** Single-HTML counterpart of {@link #ensure}: rewrite the converted html
     * cache into a bilingual copy. The output name is keyed by the ORIGINAL
     * book (several chains share fixed-name caches like temp.html / txt.html,
     * so keying by the base would collide across books) plus the translated
     * md5 snapshot, exactly like the epub branch keys its snapshots. */
    private static File ensureHtml(File originalBook, File base, Map<String, String> done) {
        File out = targetFileHtml(originalBook, done);
        // a regenerated base (mtime newer than the snapshot) invalidates it
        if (out.isFile() && out.length() > 0 && out.lastModified() >= base.lastModified()) {
            LOG.d("BilingualBuilder", "cached bilingual html", out.getPath());
            return out;
        }
        try {
            java.io.InputStream in = new java.io.FileInputStream(base);
            String content;
            try {
                content = readAll(in);
            } finally {
                in.close();
            }
            StringBuilder sb = new StringBuilder(content.length() + 512);
            int injected = 0;
            List<String> segs = splitHtmlSegments(content);
            if (segs == null) {
                java.util.regex.Matcher m = P_P.matcher(content);
                int last = 0;
                while (m.find()) {
                    int start = m.start();
                    sb.append(content, last, start);
                    sb.append(m.group(0));
                    last = m.end();
                    String clean = clean(m.group(1));
                    if (TxtUtils.isEmpty(clean)) {
                        continue;
                    }
                    String tran = done.get(FileHash.md5(clean));
                    if (TxtUtils.isNotEmpty(tran)) {
                        sb.append("\n<p class=\"aitran\">").append(escape(tran)).append("</p>");
                        injected++;
                    }
                }
                sb.append(content, last, content.length());
            } else {
                sb.append(segs.get(0));
                for (int i = 1; i < segs.size(); i++) {
                    sb.append("<p></p>");
                    String seg = segs.get(i);
                    sb.append(seg);
                    String clean = clean(seg);
                    if (!TxtUtils.isEmpty(clean)) {
                        String tran = done.get(FileHash.md5(clean));
                        if (TxtUtils.isNotEmpty(tran)) {
                            sb.append("<p class=\"aitran\">").append(escape(tran)).append("</p>");
                            injected++;
                        }
                    }
                }
            }
            if (injected == 0) {
                return null; // nothing translated yet: keep the plain base open
            }
            File tmp = new File(out.getParentFile(), out.getName() + ".tmp");
            tmp.delete();
            FileOutputStream fos = new FileOutputStream(tmp);
            try {
                fos.write(sb.toString().getBytes("UTF-8"));
            } finally {
                fos.close();
            }
            if (!tmp.renameTo(out)) {
                out.delete();
                if (!tmp.renameTo(out)) {
                    throw new IllegalStateException("rename failed");
                }
            }
            cleanOldVersionsHtml(originalBook, out);
            android.util.Log.i("BENCH", "BilingualBuilder buildHtml base=" + base.getName()
                    + " out=" + out.getName() + " injected=" + injected + " done=" + done.size());
            return out;
        } catch (Throwable t) {
            LOG.e(t);
            android.util.Log.i("BENCH", "BilingualBuilder buildHtml FAIL "
                    + t.getClass().getName() + " " + t.getMessage());
            new File(out.getParentFile(), out.getName() + ".tmp").delete();
            return null;
        }
    }

    /** Snapshot file for the single-HTML branch (keyed by the original book). */
    private static File targetFileHtml(File original, Map<String, String> done) {
        StringBuilder key = new StringBuilder();
        List<String> md5s = new ArrayList<String>(done.keySet());
        Collections.sort(md5s);
        for (String m : md5s) {
            key.append(m);
        }
        String snap = FileHash.md5(key.toString());
        if (snap != null && snap.length() > 12) {
            snap = snap.substring(0, 12);
        }
        String name = original.getName();
        int dot = name.lastIndexOf('.');
        String noExt = dot > 0 ? name.substring(0, dot) : name;
        File dir = CacheZipUtils.CACHE_TEMP != null ? CacheZipUtils.CACHE_TEMP : CacheZipUtils.CACHE_BOOK_DIR;
        return new File(dir, noExt + "__bi_" + snap + ".html");
    }

    /** Drop stale single-HTML snapshots of the same original book. */
    private static void cleanOldVersionsHtml(File original, File keep) {
        try {
            File dir = keep.getParentFile();
            if (dir == null) {
                return;
            }
            String name = original.getName();
            int dot = name.lastIndexOf('.');
            String prefix = (dot > 0 ? name.substring(0, dot) : name) + "__bi_";
            File[] files = dir.listFiles();
            if (files == null) {
                return;
            }
            for (File f : files) {
                if (f.isFile() && f.getName().startsWith(prefix)
                        && !f.getAbsolutePath().equals(keep.getAbsolutePath())) {
                    f.delete();
                }
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /** Enumerate all source paragraphs of a bilingual-capable base file. */
    public static List<Para> enumerateParagraphs(File base) {
        List<Para> out = new ArrayList<Para>();
        if (base == null || !base.isFile()) {
            return out;
        }
        if (isSingleHtmlBase(base)) {
            try {
                java.io.InputStream in = new java.io.FileInputStream(base);
                String content;
                try {
                    content = readAll(in);
                } finally {
                    in.close();
                }
                int ordinal = 0;
                List<String> segs = splitHtmlSegments(content);
                if (segs == null) {
                    for (String text : splitParagraphs(content)) {
                        String clean = clean(text);
                        if (TxtUtils.isEmpty(clean)) {
                            continue;
                        }
                        out.add(new Para(base.getName(), ordinal++, clean, FileHash.md5(clean)));
                    }
                } else {
                    for (int i = 1; i < segs.size(); i++) {
                        String clean = clean(segs.get(i));
                        if (TxtUtils.isEmpty(clean)) {
                            continue;
                        }
                        out.add(new Para(base.getName(), ordinal++, clean, FileHash.md5(clean)));
                    }
                }
            } catch (Throwable t) {
                LOG.e(t);
            }
            return out;
        }
        try {
            ZipArchiveInputStream in = Zips.buildZipArchiveInputStream(base.getPath());
            try {
                int ordinal = 0;
                ArchiveEntry entry;
                while ((entry = in.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (!isHtmlEntry(name)) {
                        continue;
                    }
                    String content = readAll(in);
                    for (String text : splitParagraphs(content)) {
                        String clean = clean(text);
                        if (TxtUtils.isEmpty(clean)) {
                            continue;
                        }
                        out.add(new Para(name, ordinal++, clean, FileHash.md5(clean)));
                    }
                }
            } finally {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        } catch (Throwable t) {
            LOG.e(t);
        }
        return out;
    }

    private static boolean isHtmlEntry(String name) {
        if (name == null) {
            return false;
        }
        String low = name.toLowerCase(Locale.US);
        // fb2.fb2: Fb2Extractor's converted epub keeps its xhtml content in an
        // entry named after the original extension (opf declares it as
        // application/xhtml+xml) — treat it as a content entry too
        return low.endsWith(".html") || low.endsWith(".htm") || low.endsWith(".xhtml")
                || low.endsWith(".fb2");
    }

    /** Split an XHTML body into the text of its <p>...</p> paragraphs. */
    private static List<String> splitParagraphs(String content) {
        List<String> paras = new ArrayList<String>();
        if (content == null || content.indexOf("<p") < 0) {
            return paras;
        }
        java.util.regex.Matcher m = P_P.matcher(content);
        while (m.find()) {
            // skip the injected bilingual translations: re-enumerating a
            // bilingual edition must never turn old translations into source
            // text
            if (m.group(0).contains("aitran")) {
                continue;
            }
            paras.add(m.group(1));
        }
        return paras;
    }

    private static final java.util.regex.Pattern P_P = java.util.regex.Pattern.compile(
            "<p\\b[^>]*>(.*?)</p>", java.util.regex.Pattern.DOTALL);

    /** Strip tags/entities and collapse whitespace (same semantics as AiTranslator.clean). */
    public static String clean(String s) {
        if (s == null) {
            return "";
        }
        s = s.replaceAll("<[^>]*>", " ");
        // soft hyphens: the reader's working copy of a book re-encodes words
        // with &shy; breaks ("be&shy;gin&shy;ning") — without stripping them
        // the paragraph md5s never match the session's cached translations
        // and the bilingual build injected 0 translations with a full cache
        s = s.replace("&shy;", "").replace("\u00ad", "");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
        return s.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }

    private static void build(File base, Map<String, String> done, File out) throws Exception {
        long t0 = System.currentTimeMillis();
        int files = 0, paras = 0, injected = 0;
        try {
            if (out.getParentFile() != null) {
                out.getParentFile().mkdirs();
            }
            ZipArchiveInputStream in = Zips.buildZipArchiveInputStream(base.getPath());
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out));
            zos.setLevel(0);
            try {
                ArchiveEntry entry;
                while ((entry = in.getNextEntry()) != null) {
                    String name = entry.getName();
                    byte[] data;
                    if (isHtmlEntry(name)) {
                        String content = new String(readAllBytes(in), "UTF-8");
                        if (content.indexOf("<p") >= 0) {
                            java.util.regex.Matcher m = P_P.matcher(content);
                            StringBuilder sb = new StringBuilder(content.length() + 512);
                            int last = 0;
                            while (m.find()) {
                                int start = m.start();
                                sb.append(content, last, start);
                                sb.append(m.group(0));
                                last = m.end();
                                paras++;
                                String clean = clean(m.group(1));
                                if (TxtUtils.isEmpty(clean)) {
                                    continue;
                                }
                                String tran = done.get(FileHash.md5(clean));
                                if (TxtUtils.isNotEmpty(tran)) {
                                    sb.append("\n<p class=\"aitran\">").append(escape(tran)).append("</p>");
                                    injected++;
                                }
                            }
                            sb.append(content, last, content.length());
                            data = sb.toString().getBytes("UTF-8");
                            files++;
                        } else {
                            // no paragraphs: keep the entry byte-identical
                            data = readAllBytes(in);
                        }
                    } else {
                        data = readAllBytes(in);
                    }
                    writeStoredEntry(zos, name, data);
                }
            } finally {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
                zos.close();
            }
        } finally {
            android.util.Log.i("BENCH", "BilingualBuilder build base=" + base.getPath() + " out=" + out.getName()
                    + " files=" + files + " paras=" + paras + " injected=" + injected + " done=" + done.size()
                    + " ms=" + (System.currentTimeMillis() - t0));
        }
    }

    /**
     * Write one STORED entry with its real size and CRC in the local header.
     * MuPDF's epub reader chokes on streaming-encoded STORED entries (no size /
     * CRC in the header): a bilingual edition produced that way lost the whole
     * .aitran paragraphs when opened, while a zip with explicit sizes read fine.
     */
    private static void writeStoredEntry(ZipOutputStream zos, String name, byte[] data) throws Exception {
        java.util.zip.ZipEntry ze = new java.util.zip.ZipEntry(name);
        ze.setMethod(java.util.zip.ZipEntry.STORED);
        ze.setSize(data.length);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);
        ze.setCrc(crc.getValue());
        zos.putNextEntry(ze);
        zos.write(data);
        zos.closeEntry();
    }

    private static byte[] readAllBytes(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        s = s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').replaceAll("\\s+", " ").trim();
        return TxtUtils.escapeHtml(s);
    }

    private static String readAll(InputStream in) throws Exception {
        return new String(readAllBytes(in), "UTF-8");
    }
}
