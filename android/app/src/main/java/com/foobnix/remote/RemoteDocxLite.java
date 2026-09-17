package com.foobnix.remote;

import com.foobnix.android.utils.LOG;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Restricted online reading for DOCX (Phase 2). A .docx is a ZIP whose text
 * lives in a handful of small XML parts (word/document.xml, its rels,
 * styles, numbering, headers/footers) while the file bulk is word/media/*
 * images. This class extracts ONLY the text parts through the remote block
 * cache (Range reads), strips the image drawings, and writes a small local
 * "lite" .docx that the regular mammoth conversion path (DocxContext) opens
 * — the media bytes are never downloaded.
 *
 * Bounds: per-part and total uncompressed caps. Anything outside them (a
 * giant document.xml, ZIP64, exotic compression methods, encrypted/DRM
 * parts) returns null and the caller silently falls back to the proven
 * whole-book download.
 */
public class RemoteDocxLite {

    /** per-part uncompressed cap (document.xml of a huge report) */
    private static final long PART_MAX = 32L * 1024 * 1024;
    /** total uncompressed cap across all extracted parts */
    private static final long TOTAL_MAX = 48L * 1024 * 1024;
    private static final int CD_READ_CAP = 16 * 1024 * 1024;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Logs why the restricted path cannot serve this book, then degrades. */
    private static File skip(String why) {
        android.util.Log.i("REMOTE", "docx lite skip: " + why);
        return null;
    }

    /**
     * Extracts the text parts of the remote docx into a small local zip.
     *
     * @param s      open remote session (block-cache backed)
     * @param out    destination (written only on success)
     * @param cancel polled between parts; aborts with null
     * @return the written file, or null when the restriction cannot apply
     */
    public static File extract(RemoteBookSession s, File out,
                               java.util.concurrent.atomic.AtomicBoolean cancel) {
        long t0 = android.os.SystemClock.elapsedRealtime();
        try {
            // 1. locate the EOCD from the tail (ZIP64 -> give up)
            long cdSize = -1, cdOff = -1;
            int win = 65536;
            while (win <= 4 * 1024 * 1024) {
                long from = Math.max(0, s.size - win);
                byte[] tail = readRange(s, from, (int) Math.min(win, s.size));
                int e = tail == null ? -1 : findEocd(tail);
                if (e >= 0) {
                    cdSize = u32(tail, e + 12);
                    cdOff = u32(tail, e + 16);
                    int entries = u16(tail, e + 10);
                    if (cdSize == 0xFFFFFFFFL || cdOff == 0xFFFFFFFFL || entries == 0xFFFF) {
                        return skip("zip64");
                    }
                    break;
                }
                win *= 4;
            }
            if (cdSize <= 0 || cdOff < 0 || cdOff >= s.size || cdSize > CD_READ_CAP) {
                return skip("cd sanity size=" + s.size + " cdSize=" + cdSize + " cdOff=" + cdOff);
            }
            // 2. central directory -> name -> {method, csize, usize, offset}
            byte[] cd = readRange(s, cdOff, (int) Math.min(cdSize, CD_READ_CAP));
            if (cd == null) {
                return skip("cd read failed");
            }
            Map<String, long[]> ent = parseCd(cd);
            if (ent.isEmpty()) {
                return skip("cd empty");
            }
            // 3. pull the text parts (cap-checked); media never read
            Map<String, byte[]> parts = new LinkedHashMap<String, byte[]>();
            long total = 0;
            String[] wanted = {
                    "[Content_Types].xml", "_rels/.rels",
                    "word/document.xml", "word/_rels/document.xml.rels",
                    "word/styles.xml", "word/numbering.xml",
                    "word/settings.xml", "word/fontTable.xml",
            };
            for (String name : wanted) {
                if (cancel != null && cancel.get()) {
                    return skip("cancelled");
                }
                byte[] b = readPart(s, ent, name);
                if (b == null) {
                    continue; // absent or oversized optional part
                }
                total += b.length;
                if (total > TOTAL_MAX) {
                    return skip("total cap " + total);
                }
                parts.put(name, b);
            }
            // headers / footers: small text parts mammoth may consult
            for (Map.Entry<String, long[]> e : ent.entrySet()) {
                String n = e.getKey();
                String ln = n.toLowerCase(Locale.US);
                if (cancel != null && cancel.get()) {
                    return skip("cancelled");
                }
                if (ln.startsWith("word/header") || ln.startsWith("word/footer")) {
                    if (ln.endsWith(".xml") || ln.endsWith(".rels")) {
                        byte[] b = readPart(s, ent, n);
                        if (b != null) {
                            total += b.length;
                            if (total > TOTAL_MAX) {
                                return skip("total cap " + total);
                            }
                            parts.put(n, b);
                        }
                    }
                }
            }
            byte[] doc = parts.get("word/document.xml");
            if (doc == null) {
                return skip("no document.xml part (missing/oversized) parts=" + parts.keySet());
            }
            // must look like XML: a DRM-encrypted part inflates to binary
            // garbage and fails this check -> whole-book path
            String docXml = new String(doc, UTF8);
            if (!docXml.trim().startsWith("<")) {
                return skip("document.xml not xml (encrypted?)");
            }
            docXml = stripDrawings(docXml);
            parts.put("word/document.xml", docXml.getBytes(UTF8));
            for (Map.Entry<String, byte[]> e : new LinkedHashMap<String, byte[]>(parts).entrySet()) {
                String n = e.getKey();
                if (n.startsWith("word/header") && n.endsWith(".xml")
                        || n.startsWith("word/footer") && n.endsWith(".xml")) {
                    parts.put(n, stripDrawings(new String(e.getValue(), UTF8)).getBytes(UTF8));
                }
            }
            if (parts.size() < 2) {
                return skip("too few parts " + parts.keySet());
            }
            // 4. write the lite docx (caller named it *.part)
            out.getParentFile().mkdirs();
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out));
            zos.setLevel(Deflater.BEST_SPEED);
            for (Map.Entry<String, byte[]> e : parts.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
            zos.finish();
            zos.close();
            android.util.Log.i("REMOTE", "docx lite ok parts=" + parts.size()
                    + " bytes=" + out.length()
                    + " ms=" + (android.os.SystemClock.elapsedRealtime() - t0));
            return out;
        } catch (Throwable t) {
            LOG.w(t);
            android.util.Log.i("REMOTE", "docx lite failed: " + t);
            try {
                out.delete();
            } catch (Throwable ignore) {
            }
            return null;
        }
    }

    /** Removes drawing blocks so mammoth never resolves their media rels. */
    private static String stripDrawings(String xml) {
        xml = Pattern.compile("<mc:AlternateContent[\\s\\S]*?</mc:AlternateContent>")
                .matcher(xml).replaceAll("");
        xml = Pattern.compile("<w:drawing[\\s\\S]*?</w:drawing>").matcher(xml).replaceAll("");
        xml = Pattern.compile("<w:pict[\\s\\S]*?</w:pict>").matcher(xml).replaceAll("");
        return xml;
    }

    // ---------------------------------------------------- zip plumbing
    // (mirrors RemoteVariantDetector; parts may be MBs, so no probe budget)

    private static byte[] readPart(RemoteBookSession s, Map<String, long[]> ent, String name) {
        long[] v = ent.get(name);
        if (v == null) {
            for (Map.Entry<String, long[]> e : ent.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    v = e.getValue();
                    break;
                }
            }
        }
        if (v == null || v[2] > PART_MAX || v[1] > PART_MAX) {
            android.util.Log.i("REMOTE", "docx lite part " + name + " v=" + java.util.Arrays.toString(v));
            return null;
        }
        byte[] lh = readRange(s, v[3], 30);
        if (lh == null || lh.length < 30 || u32(lh, 0) != 0x04034b50L) {
            android.util.Log.i("REMOTE", "docx lite part " + name + " lh bad len="
                    + (lh == null ? -1 : lh.length)
                    + " sig=" + (lh == null ? "-" : Long.toHexString(u32(lh, 0))));
            return null;
        }
        int nameLen = u16(lh, 26), extraLen = u16(lh, 28);
        long data = v[3] + 30 + nameLen + extraLen;
        byte[] raw = readRange(s, data, (int) v[1]);
        if (raw == null || raw.length < v[1]) {
            android.util.Log.i("REMOTE", "docx lite part " + name + " raw short want=" + v[1]
                    + " got=" + (raw == null ? -1 : raw.length));
            return null;
        }
        if (v[0] == 0) {
            return raw;
        }
        if (v[0] == 8) {
            try {
                Inflater inf = new Inflater(true); // ZIP raw deflate (RFC 1951), not zlib
                inf.setInput(raw);
                byte[] outBuf = new byte[(int) v[2]];
                int n = inf.inflate(outBuf);
                inf.end();
                return n == outBuf.length ? outBuf : java.util.Arrays.copyOf(outBuf, n);
            } catch (Throwable t) {
                android.util.Log.i("REMOTE", "docx lite part " + name + " inflate fail: " + t);
                return null;
            }
        }
        android.util.Log.i("REMOTE", "docx lite part " + name + " method unsupported: " + v[0]);
        return null;
    }

    private static int findEocd(byte[] b) {
        for (int i = b.length - 22; i >= 0; i--) {
            if (u32(b, i) == 0x06054b50L) {
                return i;
            }
        }
        return -1;
    }

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
            if (csize != 0xFFFFFFFFL && usize != 0xFFFFFFFFL && off != 0xFFFFFFFFL) {
                m.put(new String(cd, p + 46, nameLen, UTF8),
                        new long[]{method, csize, usize, off});
            }
            p += 46 + nameLen + extraLen + commLen;
        }
        return m;
    }

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
            android.util.Log.i("REMOTE", "docx lite range fail off=" + off + " len=" + len + " : " + t);
            return null;
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
}
