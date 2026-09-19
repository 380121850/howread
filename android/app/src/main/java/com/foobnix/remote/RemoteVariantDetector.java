package com.foobnix.remote;

import android.os.SystemClock;

import com.foobnix.android.utils.IO;
import com.foobnix.android.utils.LOG;

import org.mozilla.universalchardet.UniversalDetector;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

/**
 * Open-time packaging-variant probe for the direct-open remote formats
 * (round 12). The click decision used to be extension-only; this class
 * classifies the actual container/document variant through a handful of
 * Range reads so that variants which can never stream degrade BEFORE the
 * reader opens:
 *
 * - epub:  DRM (encryption.xml covering content) -> UNSUPPORTED;
 *          spine<=2 with one giant XHTML (DOM layout needs the whole file)
 *          -> DOWNLOAD prompt.
 * - txt:   non-engine-readable encodings (GBK/UTF-16 w/o BOM...) or books
 *          too big for the whole-file engine read -> silent DOWNLOAD.
 * - fb2:   gzip/zip wrappers, non-UTF-8, too big -> silent DOWNLOAD.
 * - html:  MHTML, non-UTF-8, too big -> silent DOWNLOAD.
 * - pdf / cbz / xps / oxps: page-granular, always STREAM (no probe).
 *
 * Iron rule: every probe failure (parse error, ZIP64 oddity, timeout)
 * resolves to STREAM — the probe must never block a book the previous
 * behaviour opened fine. Non-STREAM verdicts are cached per book+version
 * in the block-cache dir (variant.txt) so re-opens skip the probe.
 */
public class RemoteVariantDetector {

    public static final int STREAM = 0;
    public static final int DOWNLOAD = 1;
    public static final int UNSUPPORTED = 2;

    /** epub: spine<=2 with one text item this large cannot show content
     * before the whole file is cached (DOM parse + layout) — degrade. */
    private static final long EPUB_MAX_SINGLE_XHTML = 30L * 1024 * 1024;
    /** the engine reads the whole TXT into memory (fz_read_all) */
    private static final long TXT_MAX = 30L * 1024 * 1024;
    /** fb2 additionally decodes every <binary> image at parse time */
    private static final long FB2_MAX = 20L * 1024 * 1024;
    private static final long HTML_MAX = 10L * 1024 * 1024;
    /** wall-clock budget; past it the verdict is STREAM (unknown) */
    private static final long PROBE_BUDGET_MS = 3000;
    private static final int CD_READ_CAP = 16 * 1024 * 1024;
    private static final int ENTRY_READ_CAP = 256 * 1024;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Probe outcome: action + whether the user sees a dialog for it. */
    public static class Verdict {
        public final int action;
        public final boolean prompt;
        public final String detail;

        Verdict(int action, boolean prompt, String detail) {
            this.action = action;
            this.prompt = prompt;
            this.detail = detail;
        }

        public String toString() {
            return "action=" + action + " prompt=" + prompt + " detail=" + detail;
        }
    }

    private static Verdict stream() {
        return new Verdict(STREAM, false, null);
    }

    private static Verdict download(boolean prompt, String detail) {
        return new Verdict(DOWNLOAD, prompt, detail);
    }

    /** Runs the probe for one remote book; never throws. */
    public static Verdict check(RemoteBookSession s, String ext) {
        long t0 = SystemClock.elapsedRealtime();
        try {
            Verdict cached = readCached(s.remotePath, s.versionTag);
            if (cached != null) {
                android.util.Log.i("REMOTE", "variant cached " + ext + ": " + cached);
                return cached;
            }
            Verdict v;
            if ("epub".equals(ext) || "epub2".equals(ext)) {
                v = checkEpub(s, t0);
            } else if ("txt".equals(ext)) {
                v = checkTxt(s, t0);
            } else if ("fb2".equals(ext)) {
                v = checkFb2(s, t0);
            } else if ("html".equals(ext) || "htm".equals(ext)) {
                v = checkHtml(s, t0);
            } else {
                v = stream();
            }
            android.util.Log.i("REMOTE", "variant=" + ext + " -> " + v);
            if (v.action != STREAM) {
                storeCached(s.remotePath, s.versionTag, v);
            }
            return v;
        } catch (Throwable t) {
            LOG.e(t);
            android.util.Log.i("REMOTE", "variant probe failed -> stream: " + t);
            return stream();
        }
    }

    // ------------------------------------------------------------- TXT

    private static Verdict checkTxt(RemoteBookSession s, long t0) {
        /* no size gate any more: the chunked-txt document parses the text
         * 256KB at a time, so huge text files stream like small ones */
        byte[] head = readRange(s, 0, (int) Math.min(65536, s.size));
        if (overBudget(t0) || head == null || head.length == 0) {
            return stream();
        }
        if (hasBom(head, 0xEF, 0xBB, 0xBF) || hasBom(head, 0xFF, 0xFE) || hasBom(head, 0xFE, 0xFF)) {
            return stream(); // engine reads BOM'd UTF-8/16 natively
        }
        String cs = detectCharset(head);
        if (cs != null) {
            String c = cs.toUpperCase(Locale.US);
            // engine handles UTF-8 / Latin-1 style single-byte only; CJK and
            // BOM-less UTF-16 would render as mojibake -> conversion path
            if (c.contains("UTF-16") || c.contains("GBK") || c.contains("GB2312")
                    || c.contains("GB18030") || c.contains("BIG5") || c.contains("SHIFT_JIS")
                    || c.contains("EUC") || c.contains("WINDOWS-1251") || c.contains("KOI8")) {
                return download(false, "charset " + cs);
            }
            return stream();
        }
        return utf8Valid(head, head.length) ? stream() : download(false, "non-utf8 bytes");
    }

    // ------------------------------------------------------------- FB2

    private static Verdict checkFb2(RemoteBookSession s, long t0) {
        if (s.size > FB2_MAX) {
            return download(false, "size " + s.size);
        }
        byte[] head = readRange(s, 0, 8192);
        if (overBudget(t0) || head == null || head.length < 4) {
            return stream();
        }
        if (head[0] == 'P' && head[1] == 'K') {
            return download(false, "zip-wrapped fb2");
        }
        if ((head[0] & 0xFF) == 0x1F && (head[1] & 0xFF) == 0x8B) {
            return download(false, "gzip fb2");
        }
        String enc = firstMatch(new String(head, UTF8), "encoding\\s*=\\s*[\"']([\\w.-]+)[\"']");
        if (enc != null && !enc.toLowerCase(Locale.US).contains("utf-8")) {
            return download(false, "charset " + enc);
        }
        return utf8Valid(head, head.length) ? stream() : download(false, "non-utf8 bytes");
    }

    // ------------------------------------------------------------ HTML

    private static Verdict checkHtml(RemoteBookSession s, long t0) {
        if (s.size > HTML_MAX) {
            return download(false, "size " + s.size);
        }
        byte[] head = readRange(s, 0, 8192);
        if (overBudget(t0) || head == null || head.length < 4) {
            return stream();
        }
        String h = new String(head, UTF8);
        if (h.substring(0, Math.min(512, h.length())).toUpperCase(Locale.US).contains("MIME-VERSION")) {
            return download(false, "mhtml");
        }
        if (hasBom(head, 0xEF, 0xBB, 0xBF)) {
            return stream();
        }
        String enc = firstMatch(h, "charset\\s*=\\s*[\"']?([\\w.-]+)");
        if (enc != null && !enc.toLowerCase(Locale.US).contains("utf-8")
                && !enc.toLowerCase(Locale.US).contains("ascii")) {
            return download(false, "charset " + enc);
        }
        return utf8Valid(head, head.length) ? stream() : download(false, "non-utf8 bytes");
    }

    // ------------------------------------------------------------ EPUB

    private static Verdict checkEpub(RemoteBookSession s, long t0) {
        long size = s.size;
        // 1. locate EOCD from the tail (growing window; ZIP64 placeholders)
        long cdSize = -1, cdOff = -1;
        int win = 65536;
        while (win <= 4 * 1024 * 1024) {
            long from = Math.max(0, size - win);
            byte[] tail = readRange(s, from, (int) Math.min(win, size));
            if (overBudget(t0)) {
                return stream();
            }
            int e = tail == null ? -1 : findEocd(tail);
            if (e >= 0) {
                cdSize = u32(tail, e + 12);
                cdOff = u32(tail, e + 16);
                int entries = u16(tail, e + 10);
                if (cdSize == 0xFFFFFFFFL || cdOff == 0xFFFFFFFFL || entries == 0xFFFF) {
                    // ZIP64: the locator sits right before the EOCD
                    byte[] loc = e >= 20 ? slice(tail, e - 20, 20) : null;
                    if (loc == null || u32(loc, 0) != 0x07064b50L) {
                        return stream();
                    }
                    byte[] z = readRange(s, u64(loc, 8), 56);
                    if (overBudget(t0)) {
                        return stream();
                    }
                    if (z == null || z.length < 56 || u32(z, 0) != 0x06064b50L) {
                        return stream();
                    }
                    cdSize = u64(z, 40);
                    cdOff = u64(z, 48);
                }
                break;
            }
            win *= 4;
        }
        if (cdSize <= 0 || cdOff < 0 || cdOff >= size || cdSize > CD_READ_CAP) {
            return stream();
        }
        // 2. central directory -> name -> {method, csize, usize, offset}
        byte[] cd = readRange(s, cdOff, (int) Math.min(cdSize, CD_READ_CAP));
        if (overBudget(t0) || cd == null) {
            return stream();
        }
        Map<String, long[]> ent = parseCd(cd);
        if (ent.isEmpty()) {
            return stream();
        }
        // 3. encryption.xml: full-content DRM vs font-only obfuscation
        byte[] encXml = readEntry(s, ent, "META-INF/encryption.xml", t0);
        if (encXml != null) {
            String x = new String(encXml, UTF8);
            if (x.contains("EncryptedData")) {  // tolerate namespace prefixes (<enc:EncryptedData>)
                boolean fontOnly = true;
                boolean any = false;
                Matcher m = Pattern.compile("URI\\s*=\\s*\"([^\"]+)\"").matcher(x);
                while (m.find()) {
                    any = true;
                    String u = m.group(1).toLowerCase(Locale.US);
                    if (!(u.endsWith(".ttf") || u.endsWith(".otf") || u.endsWith(".woff")
                            || u.endsWith(".woff2") || u.endsWith(".ttc"))) {
                        fontOnly = false;
                        break;
                    }
                }
                if (any && !fontOnly) {
                    return new Verdict(UNSUPPORTED, false, "drm encryption.xml");
                }
            }
        }
        // 4. container.xml -> OPF -> manifest/spine metrics
        byte[] cont = readEntry(s, ent, "META-INF/container.xml", t0);
        if (overBudget(t0) || cont == null) {
            return stream();
        }
        String rootfile = firstMatch(new String(cont, UTF8), "rootfile[^>]*full-path\\s*=\\s*\"([^\"]+)\"");
        if (rootfile == null) {
            return stream();
        }
        byte[] opf = readEntry(s, ent, rootfile, t0);
        if (overBudget(t0) || opf == null) {
            return stream();
        }
        String opfStr = new String(opf, UTF8);
        int spineCount = 0;
        Matcher ir = Pattern.compile("<itemref\\b[^>]*>").matcher(opfStr);
        while (ir.find()) {
            spineCount++;
        }
        long maxText = 0, fonts = 0, images = 0;
        for (Map.Entry<String, long[]> en : ent.entrySet()) {
            long[] v = en.getValue();
            String n = en.getKey().toLowerCase(Locale.US);
            if (n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm")) {
                maxText = Math.max(maxText, v[2]);
            } else if (n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".woff")
                    || n.endsWith(".woff2") || n.endsWith(".ttc")) {
                fonts += v[2];
            } else if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                    || n.endsWith(".gif") || n.endsWith(".webp")) {
                images += v[2];
            }
        }
        android.util.Log.i("REMOTE", "epub variant: spine=" + spineCount + " maxXhtml=" + maxText
                + " fonts=" + fonts + " images=" + images);
        if (spineCount >= 1 && spineCount <= 2 && maxText > EPUB_MAX_SINGLE_XHTML) {
            return download(true, "giant single xhtml " + maxText);
        }
        return stream();
    }

    // ---------------------------------------------------- zip plumbing

    /** name -> {method, csize, usize, localOffset} (ZIP64 entries skipped). */
    private static Map<String, long[]> parseCd(byte[] cd) {
        Map<String, long[]> m = new LinkedHashMap<String, long[]>();
        int p = 0;
        while (p + 46 <= cd.length) {
            if (u32(cd, p) != 0x02014b50L) {
                break;
            }
            int method = u16(cd, p + 10);
            long csize = u32(cd, p + 20), usize = u32(cd, p + 24);
            int nameLen = u16(cd, p + 28), extraLen = u16(cd, p + 30), commLen = u16(cd, p + 32);
            long off = u32(cd, p + 42);
            if (p + 46 + nameLen > cd.length) {
                break;
            }
            String name = decodeName(new String(cd, p + 46, nameLen, UTF8));
            if (csize != 0xFFFFFFFFL && usize != 0xFFFFFFFFL && off != 0xFFFFFFFFL) {
                m.put(name, new long[]{method, csize, usize, off});
            }
            p += 46 + nameLen + extraLen + commLen;
        }
        return m;
    }

    /** Reads one small archive entry through the block cache (capped). */
    private static byte[] readEntry(RemoteBookSession s, Map<String, long[]> ent, String name, long t0) {
        long[] v = ent.get(decodeName(name));
        if (v == null) {
            String suffix = "/" + decodeName(name);
            for (Map.Entry<String, long[]> e : ent.entrySet()) {
                if (e.getKey().endsWith(suffix)) {
                    v = e.getValue();
                    break;
                }
            }
        }
        // v[1] = csize: the read below allocates it unchecked, so a malformed
        // zip entry could claim a giant compressed size (memory spike)
        if (v == null || v[1] > ENTRY_READ_CAP || v[2] > ENTRY_READ_CAP) {
            return null;
        }
        byte[] lh = readRange(s, v[3], 30);
        if (lh == null || lh.length < 30 || u32(lh, 0) != 0x04034b50L) {
            return null;
        }
        int nameLen = u16(lh, 26), extraLen = u16(lh, 28);
        long data = v[3] + 30 + nameLen + extraLen;
        byte[] raw = readRange(s, data, (int) v[1]);
        if (raw == null) {
            return null;
        }
        if (v[0] == 0) {
            return raw;
        }
        if (v[0] == 8) {
            try {
                Inflater inf = new Inflater(true); // ZIP raw deflate (RFC 1951), not zlib
                inf.setInput(raw);
                byte[] out = new byte[(int) v[2]];
                int n = inf.inflate(out);
                inf.end();
                return n == out.length ? out : java.util.Arrays.copyOf(out, n);
            } catch (Throwable t) {
                return null;
            }
        }
        return null; // unsupported method -> unknown -> stream by caller
    }

    /** Reverse-scans the tail for the EOCD signature (skips the comment). */
    private static int findEocd(byte[] b) {
        for (int i = b.length - 22; i >= 0; i--) {
            if (u32(b, i) == 0x06054b50L) {
                return i;
            }
        }
        return -1;
    }

    // -------------------------------------------------------- helpers

    private static byte[] readRange(RemoteBookSession s, long off, int len) {
        try {
            if (len <= 0 || off < 0 || off >= s.size) {
                return null;
            }
            len = (int) Math.min(len, s.size - off);
            byte[] b = new byte[len];
            int done = 0;
            while (done < len) {
                int n = s.readAt(off + done, b, done, len - done);
                if (n <= 0) {
                    break;
                }
                done += n;
            }
            if (done <= 0) {
                return null;
            }
            return done == len ? b : java.util.Arrays.copyOf(b, done);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean overBudget(long t0) {
        return SystemClock.elapsedRealtime() - t0 > PROBE_BUDGET_MS;
    }

    private static boolean hasBom(byte[] b, int... bom) {
        if (b.length < bom.length) {
            return false;
        }
        for (int i = 0; i < bom.length; i++) {
            if ((b[i] & 0xFF) != bom[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean utf8Valid(byte[] b, int len) {
        int i = 0;
        if (len >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            i = 3;
        }
        while (i < len) {
            int c = b[i] & 0xFF;
            if (c < 0x80) {
                i++;
                continue;
            }
            int n;
            if ((c & 0xE0) == 0xC0) {
                n = 1;
            } else if ((c & 0xF0) == 0xE0) {
                n = 2;
            } else if ((c & 0xF8) == 0xF0) {
                n = 3;
            } else {
                return false;
            }
            if (i + n >= len) {
                return true; // truncated at the sample edge: accept
            }
            for (int k = 1; k <= n; k++) {
                if ((b[i + k] & 0xC0) != 0x80) {
                    return false;
                }
            }
            i += n + 1;
        }
        return true;
    }

    private static String detectCharset(byte[] b) {
        try {
            UniversalDetector d = new UniversalDetector(null);
            d.handleData(b, 0, b.length);
            d.dataEnd();
            return d.getDetectedCharset();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String firstMatch(String s, String regex) {
        try {
            Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(s);
            return m.find() ? m.group(1) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String attr(String tag, String name) {
        return firstMatch(tag, name + "\\s*=\\s*\"([^\"]*)\"");
    }

    private static String decodeName(String name) {
        try {
            return java.net.URLDecoder.decode(name, "UTF-8");
        } catch (Throwable t) {
            return name;
        }
    }

    private static long u32(byte[] b, int p) {
        if (p < 0 || p + 4 > b.length) {
            return -1;
        }
        return (b[p] & 0xFFL) | (b[p + 1] & 0xFFL) << 8 | (b[p + 2] & 0xFFL) << 16 | (b[p + 3] & 0xFFL) << 24;
    }

    private static int u16(byte[] b, int p) {
        if (p < 0 || p + 2 > b.length) {
            return -1;
        }
        return (b[p] & 0xFF) | (b[p + 1] & 0xFF) << 8;
    }

    private static long u64(byte[] b, int p) {
        return u32(b, p) & 0xFFFFFFFFL | (u32(b, p + 4) & 0xFFFFFFFFL) << 32;
    }

    private static byte[] slice(byte[] b, int off, int len) {
        if (off < 0 || off + len > b.length) {
            return null;
        }
        return java.util.Arrays.copyOfRange(b, off, off + len);
    }

    // ------------------------------------------- per-book verdict cache

    private static File variantFile(String remotePath) {
        return new File(new File(BlockCacheStore.rootDir(), RemoteBook.cacheKey(remotePath)), "variant.txt");
    }

    private static Verdict readCached(String path, String tag) {
        try {
            File f = variantFile(path);
            if (!f.isFile()) {
                return null;
            }
            /* v2 verdicts carry a "2" marker: entries written before the
             * chunked-txt engine must not keep their old DOWNLOAD verdicts */
            String[] parts = IO.readString(f).split("\\|", -1);
            if (parts.length < 4 || !"2".equals(parts[0])
                    || !parts[1].equals(tag == null ? "" : tag)) {
                return null;
            }
            return new Verdict(Integer.parseInt(parts[2]), "1".equals(parts[3]),
                    parts.length > 4 ? parts[4] : null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void storeCached(String path, String tag, Verdict v) {
        FileOutputStream o = null;
        try {
            File f = variantFile(path);
            f.getParentFile().mkdirs();
            o = new FileOutputStream(f);
            o.write(("2|" + (tag == null ? "" : tag) + "|" + v.action + "|" + (v.prompt ? 1 : 0)
                    + "|" + (v.detail == null ? "" : v.detail)).getBytes("UTF-8"));
        } catch (Throwable t) {
            LOG.w(t);
        } finally {
            if (o != null) {
                try {
                    o.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }
}
