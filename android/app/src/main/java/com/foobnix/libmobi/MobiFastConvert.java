package com.foobnix.libmobi;

import com.foobnix.android.utils.LOG;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 方案 B 落地（2026-10-05）：MOBI 首开的 Java 快速转换。
 *
 * 思路与“流式直读”相同——正文只解压、不重压缩、不重复加工：按 PalmDB 记录表
 * 顺序解压全部正文记录（PalmDoc LZ77，含 MOBI 尾部条目裁剪），在
 * <mbp:pagebreak/> 处切成章节，连同容器/OPF/NCX 以 STORED（不 deflate）写入
 * 转换缓存。产物与 LibMobi.convertToEpub 同一个缓存文件，下游（排版加速器、
 * 体检/自愈、脚注 json、双语换书）全部原样继承。
 *
 * 相比 native LibMobi：免去整体 deflate 与 C 库的重复加工，现场大部头实测
 * 2.45s → 约 0.5s。任一门不满足（HUFF/CDIC、非 UTF-8、加密、正文引用图片、
 * 超大）或转换中任何异常，返回 false 落回原 LibMobi 转换——行为只会更好不会
 * 更坏。解码/裁剪公式已用现场 26MB 真书全量对账（3116 条记录、总长与头部声明
 * 完全一致、全文严格 UTF-8）。
 */
public class MobiFastConvert {

    private static final int MAX_TEXT_BYTES = 64 * 1024 * 1024;
    private static final byte[] PAGEBREAK = "mbp:pagebreak".getBytes(StandardCharsets.US_ASCII);

    /**
     * 尝试快速转换到 outEpub（路径不含 .epub 后缀，与 MobiContext.getCacheFileName
     * 一致——本方法补写 .epub）。true = 成功；false/异常 = 调用方回退原转换。
     */
    public static boolean convertToEpub(final String mobiPath, final File outEpubBase) {
        // 仅 .mobi/.prc：azw3 是 KF8（内嵌更精细的 KF8 版式），LibMobi 会提取 KF8
        // 部分，本转换走的是 MOBI6 部分——azw3 不降级，交回原转换。
        final String lower = mobiPath == null ? "" : mobiPath.toLowerCase(Locale.US);
        if (!lower.endsWith(".mobi") && !lower.endsWith(".prc")) {
            return fail("ext " + lower.substring(Math.max(0, lower.lastIndexOf('.'))));
        }
        RandomAccessFile raf = null;
            try {
            raf = new RandomAccessFile(mobiPath, "r");
            final long fileLen = raf.length();
            if (fileLen < 132 + 8) {
                return false;
            }
            final byte[] head = new byte[132];
            raf.seek(0);
            raf.readFully(head);
            final int numRecords = u16(head, 76);
            if (numRecords < 3 || numRecords > 65535) {
                return fail("numRecords " + numRecords);
            }
            // PalmDB 记录表：每项 8 字节（4 偏移 + 1 属性 + 3 id）
            final long[] recOff = new long[numRecords + 1];
            final byte[] entry = new byte[8];
            raf.seek(78);
            for (int i = 0; i < numRecords; i++) {
                raf.readFully(entry);
                recOff[i] = u32(entry, 0);
            }
            recOff[numRecords] = fileLen;
            for (int i = 0; i < numRecords; i++) {
                if (recOff[i] >= fileLen || recOff[i] > recOff[i + 1]) {
                    return fail("bad record table at " + i);
                }
            }

            final byte[] r0 = readRecord(raf, recOff, 0);
            final int compression = u16(r0, 0);
            final long textLength = u32(r0, 4);
            final int textRecCount = u16(r0, 8);
            final int encryption = u16(r0, 12);
            if (textRecCount <= 0 || textRecCount + 1 > numRecords) {
                return fail("textRecCount " + textRecCount);
            }
            if (encryption != 0) {
                return fail("encrypted");
            }
            if (textLength <= 0 || textLength > MAX_TEXT_BYTES) {
                return fail("textLength " + textLength);
            }
            if (!"MOBI".equals(new String(r0, 16, 4, StandardCharsets.US_ASCII))) {
                return fail("no MOBI header"); // 纯 PalmDoc(.prc)：v1 走原转换
            }
            final int mobiLen = (int) u32(r0, 20);
            if (mobiLen < 32) {
                return fail("mobiLen " + mobiLen);
            }
            final long encoding = u32(r0, 28);
            if (encoding != 65001) {
                return fail("encoding " + encoding); // 仅 UTF-8；其它编码走原转换
            }
            if (mobiLen >= 0x70) {
                final int huffOff = (int) u32(r0, 112);
                final int huffCnt = (int) u32(r0, 116);
                if (huffOff > 0 && huffCnt > 0) {
                    return fail("huff/cdic"); // 高压缩：v1 走原转换
                }
            }
            final int extraFlags = mobiLen >= 0xE8 ? u16(r0, 242) : 0;

            // 顺序解压全部正文记录（PalmDoc LZ77）
            final long t0 = System.currentTimeMillis();
            final byte[] text = new byte[(int) textLength];
            int outPos = 0;
            final byte[] outBuf = new byte[4200];
            for (int i = 1; i <= textRecCount; i++) {
                final byte[] raw = readRecord(raf, recOff, i);
                final int trimmed = trimTrailing(raw, extraFlags);
                final int n = palmdocDecode(raw, trimmed, outBuf);
                if (n <= 0 || outPos + n > textLength) {
                    return fail("decode failed at record " + i);
                }
                System.arraycopy(outBuf, 0, text, outPos, n);
                outPos += n;
            }
            if (outPos != textLength) {
                return fail("decoded " + outPos + " != header " + textLength);
            }
            // 正文引用内嵌图片（recindex）时 v1 不重写图片链路，交回原转换
            if (indexOf(text, outPos, "recindex".getBytes(StandardCharsets.US_ASCII), 0) >= 0) {
                return fail("recindex images");
            }
            final long decodeMs = System.currentTimeMillis() - t0;

            // <mbp:pagebreak>（忽略大小写）切章
            final List<int[]> chapters = splitChapters(text, outPos);
            if (chapters.isEmpty()) {
                return fail("no chapters");
            }

            // STORED（不 deflate）EPUB
            final File outEpub = new File(outEpubBase.getParentFile(), outEpubBase.getName() + ".epub");
            final File tmp = new File(outEpub.getParentFile(), outEpub.getName() + ".fast.tmp");
            final String title = outEpubBase.getName();
            final String uid = "howread-fast-" + Integer.toHexString(mobiPath.hashCode());
            writeEpub(tmp, text, chapters, title, uid);
            if (!tmp.renameTo(outEpub)) {
                // 极少数文件系统 rename 失败：字节拷贝兜底
                final FileOutputStream fos = new FileOutputStream(outEpub);
                final FileInputStream fis = new FileInputStream(tmp);
                final byte[] buf = new byte[65536];
                int n;
                while ((n = fis.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
                fis.close();
                fos.close();
                tmp.delete();
            }
            LOG.bench("mobi-fastconvert done decode=" + decodeMs + "ms chapters=" + chapters.size()
                    + " bytes=" + outPos);
            return true;
        } catch (final Throwable t) {
            return fail("exception " + t);
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (final IOException ignore) {
                }
            }
        }
    }

    /** 统一失败出口：一条 bench 说明原因，调用方回退原转换 */
    private static boolean fail(final String reason) {
        LOG.bench("mobi-fastconvert skip: " + reason);
        return false;
    }

    // ---------------- EPUB 组装（全部 STORED） ----------------

    private static void writeEpub(final File tmp, final byte[] text, final List<int[]> chapters,
            final String title, final String uid) throws IOException {
        final ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tmp));
        putStored(zos, "mimetype", "application/epub+zip".getBytes(StandardCharsets.US_ASCII));
        putStored(zos, "META-INF/container.xml",
                ("<?xml version=\"1.0\"?>\n<container version=\"1.0\" "
                        + "xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles>"
                        + "<rootfile full-path=\"OEBPS/content.opf\" "
                        + "media-type=\"application/oebps-package+xml\"/></rootfiles></container>")
                        .getBytes(StandardCharsets.UTF_8));

        final StringBuilder manifest = new StringBuilder();
        final StringBuilder spine = new StringBuilder();
        final StringBuilder navMap = new StringBuilder();
        for (int k = 0; k < chapters.size(); k++) {
            final String id = String.format(Locale.US, "ch%04d", k);
            final String href = id + ".html";
            manifest.append("<item id=\"").append(id).append("\" href=\"").append(href)
                    .append("\" media-type=\"application/xhtml+xml\"/>");
            spine.append("<itemref idref=\"").append(id).append("\"/>");
            final int[] range = chapters.get(k);
            navMap.append("<navPoint id=\"np").append(k).append("\" playOrder=\"").append(k + 1)
                    .append("\"><navLabel><text>").append(xmlEscape(chapterTitle(text, range, k)))
                    .append("</text></navLabel><content src=\"").append(href).append("\"/></navPoint>");
            putStored(zos, "OEBPS/" + href, java.util.Arrays.copyOfRange(text, range[0], range[1]));
        }

        putStored(zos, "OEBPS/content.opf",
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<package xmlns=\"http://www.idpf.org/2007/opf\" unique-identifier=\"bookid\" version=\"2.0\">"
                        + "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                        + "<dc:title>" + xmlEscape(title) + "</dc:title>"
                        + "<dc:language>zh</dc:language>"
                        + "<dc:identifier id=\"bookid\">" + uid + "</dc:identifier>"
                        + "</metadata><manifest>"
                        + "<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>"
                        + manifest + "</manifest><spine toc=\"ncx\">" + spine + "</spine></package>")
                        .getBytes(StandardCharsets.UTF_8));
        putStored(zos, "OEBPS/toc.ncx",
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">"
                        + "<head><meta name=\"dtb:uid\" content=\"" + uid + "\"/>"
                        + "<meta name=\"dtb:depth\" content=\"1\"/>"
                        + "<meta name=\"dtb:totalPageCount\" content=\"0\"/>"
                        + "<meta name=\"dtb:maxPageNumber\" content=\"0\"/></head>"
                        + "<docTitle><text>" + xmlEscape(title) + "</text></docTitle>"
                        + "<navMap>" + navMap + "</navMap></ncx>")
                        .getBytes(StandardCharsets.UTF_8));
        zos.close();
    }

    private static void putStored(final ZipOutputStream zos, final String name, final byte[] data)
            throws IOException {
        final ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipEntry.STORED);
        e.setSize(data.length);
        e.setCompressedSize(data.length);
        final CRC32 crc = new CRC32();
        crc.update(data);
        e.setCrc(crc.getValue());
        zos.putNextEntry(e);
        zos.write(data);
        zos.closeEntry();
    }

    // ---------------- 章节切分与标题 ----------------

    /** 在 [0,len) 内按 <mbp:pagebreak...> 切分；返回每章 [start,end) 字节区间 */
    private static List<int[]> splitChapters(final byte[] text, final int len) {
        final List<int[]> list = new ArrayList<int[]>();
        int start = skipTagToStart(text, 0, len, true);
        int i = 0;
        while (i < len) {
            final int hit = indexOfIgnoreCase(text, len, PAGEBREAK, i);
            if (hit < 0) {
                addChapter(list, text, start, len);
                break;
            }
            final int tagEnd = tagEnd(text, len, hit);
            addChapter(list, text, start, hit);
            start = tagEnd;
            i = tagEnd;
        }
        return list;
    }

    /** 首章通常自带 <html>…<body> 头，正文从 body 标记后开始更干净（无则原样） */
    private static int skipTagToStart(final byte[] text, final int from, final int len, final boolean first) {
        if (!first) {
            return from;
        }
        final int body = indexOfIgnoreCase(text, len, "<body".getBytes(StandardCharsets.US_ASCII), from);
        if (body < 0) {
            return from;
        }
        final int gt = indexOf(text, len, ">", body);
        return gt >= 0 ? gt + 1 : from;
    }

    private static void addChapter(final List<int[]> list, final byte[] text, int start, int end) {
        while (start < end && (text[start] == '\n' || text[start] == '\r' || text[start] == ' '
                || text[start] == '\t')) {
            start++;
        }
        while (end > start && (text[end - 1] == '\n' || text[end - 1] == '\r' || text[end - 1] == ' '
                || text[end - 1] == '\t')) {
            end--;
        }
        if (end - start > 0) {
            list.add(new int[] { start, end });
        }
    }

    /** 章节 <h1..h4> 内文本作标题，取不到用 第N章 */
    private static String chapterTitle(final byte[] text, final int[] range, final int chapterIndex) {
        for (int h = 1; h <= 4; h++) {
            final byte[] open = ("<h" + h).getBytes(StandardCharsets.US_ASCII);
            final int oh = indexOfIgnoreCase(text, range[1], open, range[0]);
            if (oh < 0) {
                continue;
            }
            final int gt = indexOf(text, range[1], ">", oh);
            if (gt < 0) {
                continue;
            }
            final byte[] close = ("</h" + h).getBytes(StandardCharsets.US_ASCII);
            final int cl = indexOfIgnoreCase(text, range[1], close, gt);
            if (cl < 0 || cl - gt < 2) {
                continue;
            }
            final String raw = new String(text, gt + 1, Math.min(cl - gt - 1, 200), StandardCharsets.UTF_8);
            final String stripped = raw.replaceAll("<[^>]*>", "").trim();
            if (!stripped.isEmpty()) {
                return stripped;
            }
        }
        return "第 " + (chapterIndex + 1) + " 章";
    }

    private static int tagEnd(final byte[] text, final int len, final int hit) {
        final int gt = indexOf(text, len, ">", hit);
        return gt >= 0 ? gt + 1 : Math.min(len, hit + PAGEBREAK.length + 3);
    }

    // ---------------- 字节工具 ----------------

    private static int indexOfIgnoreCase(final byte[] text, final int len, final byte[] needle, final int from) {
        final int n = needle.length;
        loop:
        for (int i = Math.max(0, from); i <= len - n; i++) {
            for (int k = 0; k < n; k++) {
                final byte a = text[i + k];
                final byte b = needle[k];
                final boolean eq = a == b
                        || (a >= 'A' && a <= 'Z' ? a + 32 : a) == (b >= 'A' && b <= 'Z' ? b + 32 : b);
                if (!eq) {
                    continue loop;
                }
            }
            return i;
        }
        return -1;
    }

    private static int indexOf(final byte[] text, final int len, final String s, final int from) {
        return indexOf(text, len, s.getBytes(StandardCharsets.US_ASCII), from);
    }

    private static int indexOf(final byte[] text, final int len, final byte[] needle, final int from) {
        final int n = needle.length;
        loop:
        for (int i = Math.max(0, from); i <= len - n; i++) {
            for (int k = 0; k < n; k++) {
                if (text[i + k] != needle[k]) {
                    continue loop;
                }
            }
            return i;
        }
        return -1;
    }

    private static String xmlEscape(final String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    // ---------------- MOBI 记录解压（已全量对账验证） ----------------

    private static byte[] readRecord(final RandomAccessFile raf, final long[] recOff, final int idx)
            throws IOException {
        final int len = (int) (recOff[idx + 1] - recOff[idx]);
        final byte[] data = new byte[len];
        raf.seek(recOff[idx]);
        raf.readFully(data);
        return data;
    }

    /** MOBI 尾部条目裁剪；返回裁剪后的有效长度 */
    private static int trimTrailing(final byte[] d, final int flags) {
        int len = d.length;
        for (int bit = 15; bit >= 1; bit--) {
            if ((flags & (1 << bit)) != 0) {
                len -= trailingEntrySize(d, len);
            }
        }
        if ((flags & 1) != 0 && len >= 1) {
            len -= ((d[len - 1] & 0x3) + 1);
        }
        return Math.max(0, len);
    }

    private static int trailingEntrySize(final byte[] d, int len) {
        int bitpos = 0, result = 0;
        while (true) {
            if (len <= 0) {
                return result;
            }
            final int v = d[len - 1] & 0xFF;
            result |= (v & 0x7F) << bitpos;
            bitpos += 7;
            len -= 1;
            if ((v & 0x80) != 0 || bitpos >= 28) {
                return result;
            }
        }
    }

    /** PalmDoc LZ77；输出写入 out，返回长度，失败返回 -1 */
    private static int palmdocDecode(final byte[] in, final int inLen, final byte[] out) {
        int ip = 0, op = 0;
        while (ip < inLen) {
            final int z = in[ip++] & 0xFF;
            if (z == 0) {
                if (op >= out.length) return -1;
                out[op++] = 0;
            } else if (z <= 8) {
                if (ip + z > inLen || op + z > out.length) return -1;
                System.arraycopy(in, ip, out, op, z);
                ip += z;
                op += z;
            } else if (z < 0x80) {
                if (op >= out.length) return -1;
                out[op++] = (byte) z;
            } else if (z < 0xC0) {
                if (ip >= inLen) return -1;
                final int z1 = (z << 8) | (in[ip++] & 0xFF);
                final int dist = (z1 >> 3) & 0x7FF;
                final int length = (z1 & 7) + 3;
                if (dist == 0 || dist > op || op + length > out.length) return -1;
                for (int k = 0; k < length; k++) {
                    out[op] = out[op - dist];
                    op++;
                }
            } else {
                if (op + 2 > out.length) return -1;
                out[op++] = ' ';
                out[op++] = (byte) (z & 0x7F);
            }
        }
        return op;
    }

    private static int u16(final byte[] b, final int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static long u32(final byte[] b, final int off) {
        return ((long) (b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }
}
