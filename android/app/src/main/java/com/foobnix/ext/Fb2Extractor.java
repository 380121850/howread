package com.foobnix.ext;

import android.graphics.Bitmap;
import android.text.TextUtils;
import android.util.Base64;

import com.BaseExtractor;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.foobnix.LibreraApp;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.StreamUtils;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.hypen.HypenUtils;
import com.foobnix.model.AppData;
import com.foobnix.model.AppSP;
import com.foobnix.model.AppState;
import com.foobnix.model.SimpleMeta;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.pdf.info.model.BookCSS;
import com.foobnix.pdf.info.model.OutlineLinkWrapper;
import com.foobnix.sys.TempHolder;

import org.ebookdroid.core.codec.OutlineLink;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class Fb2Extractor extends BaseExtractor {
    public static final String FOOTER_NOTES_SIGN = "***";
    public static final String FOOTER_AFTRER_BOODY = "[!]";

    public static final String DIVIDER = "~@~";
    private static final int BUFFER_SIZE = 16 * 1024;
    public static String container_xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + //
            "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n" + //
            "  <rootfiles>\n" + //
            "    <rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>\n" + //
            "  </rootfiles>\n" + //
            "</container>";//
    public static String content_opf = "<?xml version=\"1.0\"?>\n" + //
            "<package version=\"2.0\" unique-identifier=\"uid\" xmlns=\"http://www.idpf.org/2007/opf\">\n" + //
            " <metadata xmlns:opf=\"http://www.idpf.org/2007/opf\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n" + //
            "  <dc:title>%title%</dc:title>\n" + //
            "  <dc:creator>%creator%</dc:creator>\n" + //
            "<meta name=\"cover\" content=\"cover.jpg\" />\n" + //
            " </metadata>\n" + //
            "\n" + "<manifest>\n" + //
            "  <item id=\"idBookFb2\" href=\"fb2.fb2\" media-type=\"application/xhtml+xml\"/>\n" + //
            "  <item id=\"idResourceFb2\" href=\"fb2.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n" + //
            " </manifest>\n" + //
            " \n" + //
            "<spine toc=\"idResourceFb2\">\n" + //
            "  <itemref idref=\"idBookFb2\"/>\n" + //
            "</spine>\n" + //
            "</package>";//
    public static String NCX = "<?xml version=\"1.0\"?>\n" + //
            "<ncx version=\"2005-1\" xml:lang=\"en\" xmlns=\"http://www.daisy.org/z3986/2005/ncx/\">\n" + //
            " <head>\n" + //
            " </head>\n" + //
            " <docTitle>\n" + //
            "  <text>title</text>\n" + //
            " </docTitle>\n" + //
            " <navMap>" + //
            "%nav% \n" + //
            "   \n" + //
            " </navMap>\n" + //
            "</ncx>";//
    public static Map<Integer, String> epub3Pages = new HashMap<>();
    static Fb2Extractor inst = new Fb2Extractor();
    static Pattern pattern = Pattern.compile("<a id=\"page(\\d+)\"");
    public Map<String, String> genresRus = new HashMap<>();

    private Fb2Extractor() {
    }

    public static Fb2Extractor get() {
        return inst;
    }

    public static boolean convertFolderToEpub(File inputFolder, File outputFile, String author, String title, List<OutlineLink> outline) {

        ZipOutputStream zos = null;
        try {
            zos = new ZipOutputStream(new FileOutputStream(outputFile));
            zos.setLevel(0);

            writeToZip(zos, "mimetype", "application/epub+zip");
            writeToZip(zos, "META-INF/container.xml", container_xml);


            String meta = content_opf.replace("fb2.fb2", "temp" + ExtUtils.REFLOW_HTML);

            if (author != null) {
                author = TextUtils.htmlEncode(author);
                meta = meta.replace("%creator%", author);
            }
            if (title != null) {
                title = TextUtils.htmlEncode(title);
                meta = meta.replace("%title%", title);
            }

            writeToZip(zos, "OEBPS/content.opf", meta);
            if (TxtUtils.isListNotEmpty(outline)) {
                writeToZip(zos, "OEBPS/fb2.ncx", genetateNCXbyOutline(outline));
            }

            for (File file : inputFolder.listFiles()) {
                writeToZip(zos, "OEBPS/" + file.getName(), new FileInputStream(file));
            }

            LOG.d("Fb2Context convert true");
            zos.close();
            return true;
        } catch (Exception e) {
            LOG.d("Fb2Context convert false error");
            LOG.e(e);
        } finally {
            try {
                if (zos != null) {
                    zos.close();
                }
            } catch (Exception ignore) {
            }
        }
        LOG.d("Fb2Context convert false");
        return false;
    }

    public static String accurateLine(String line) {
        if (BookCSS.get().documentStyle == BookCSS.STYLES_ONLY_USER) {
            line = line.replace(TxtUtils.NON_BREAKE_SPACE, " ");
            line = line.replace(">" + TxtUtils.LONG_DASH1 + " ", ">" + TxtUtils.LONG_DASH1 + TxtUtils.NON_BREAKE_SPACE);
            line = line.replace(">" + TxtUtils.LONG_DASH2 + " ", ">" + TxtUtils.LONG_DASH2 + TxtUtils.NON_BREAKE_SPACE);
            //line = line.replace("_", "_" + HypenUtils.SHY); //break image paths
        }
        return line;
    }

    public static void generateHyphenFileEpub(InputStreamReader inputStream, Map<String, String> notes, OutputStream out, String name, Map<String, String> svgs, int number, List<SimpleMeta> replacements) throws Exception {
        BufferedReader input = new BufferedReader(inputStream);


        PrintWriter writer = new PrintWriter(out);
        String line;

        HypenUtils.resetTokenizer();

        boolean isValidXML = false;
        boolean isValidXMLChecked = false;

        String svg = "";
        boolean findSVG = false;
        int svgNumbver = 0;
        String defs = "";

        int count = 0;
        int beginMath = 0;


        while ((line = input.readLine()) != null) {
            if (TempHolder.get().loadingCancelled.get()) {
               return;
            }
            if (!isValidXMLChecked && line.length() == 0) {
                continue;
            }

            if (!isValidXMLChecked && TxtUtils.isNotEmpty(line)) {
                isValidXMLChecked = true;
                isValidXML = line.contains("<");
                //isValidXML = line.indexOf(0) == '<';
                LOG.d("isValidXML", isValidXML);
                if (!isValidXML) {
                    writer.print("<html><body>");
                }
            }
            if (!isValidXML && TxtUtils.isEmpty(line)) {
                writer.println("<p></p>");
                continue;
            }


            if (AppState.get().isShowFooterNotesInText) {
                line = includeFooterNotes(line, notes, name);
            }

            // LOG.d("gen-in", line);
            line = accurateLine(line);

            if (AppState.get().isShowPageNumbers) {
                Matcher matcher = pattern.matcher(line);
                while (matcher.find()) {
                    int pageId = Integer.parseInt(matcher.group(1));
                    //LOG.d("epub3Pages 1", line);
                    line = line.replace("<a id=\"page" + pageId, "<br/><pn>page " + pageId + "</pn><br/><a id=\"page" + pageId);
                    epub3Pages.put(pageId, name + "#page" + pageId);
                    //LOG.d("epub3Pages 2", line);
                }
            }

            if (AppState.get().isReferenceMode) {

                int index = line.indexOf("<p");
                while (index >= 0) {
                    count++;
                    int p = line.indexOf(">", index) + 1;
                    line = line.substring(0, p) + "<x-small>|" + number + "." + count + "|" + TxtUtils.NON_BREAKE_SPACE + "</x-small>" + line.substring(p);
                    LOG.d("linep", line);
                    index = line.indexOf("<p", p + 10);
                }
            }

            line = processRemoteImages(line);


            if (AppState.get().isExperimental && svgs != null) {


                line = line.replace("<m:", "<");
                if (line.contains("<svg")) {
                    svgNumbver++;
                    findSVG = true;
                    svg = line.substring(line.indexOf("<svg"));
                } else if (line.contains("<math")) {
                    svgNumbver++;
                    findSVG = true;
                    beginMath = line.indexOf("<math");
                    //svg = line.substring(beginMath);
                } else if (line.contains("</svg>")) {
                    LOG.d("SVG", svg);
                    svg += line.substring(0, line.indexOf("</svg>") + "</svg>".length());


                    String defsCurrent = TxtUtils.getStringInTag(svg, "defs");

                    svg = svg.replace("<defs>", "<defs>" + defs);
                    svg = svg.replace("<defs/>", "<defs>" + defs + "</defs>");
                    if (TxtUtils.isNotEmpty(defsCurrent)) {
                        defs = defs + defsCurrent;
                    }
                    LOG.d("DEFS:", defs);

                    //LOG.d("DEFS:",name, TxtUtils.getStringInTag(svg, "defs"));

                    final String imageName = name + "-" + svgNumbver + ".png";
                    final String imageName2 = ExtUtils.getFileName(name) + "-" + svgNumbver + ".png";
                    svgs.put(imageName, svg);

                    LOG.d("SVG:", imageName, svg);

                    line += "<img src=\"" + imageName2 + "\" />";
                    //line += "[img " + "png" + "]<img src=\"" + imageName2 + "\" />";
                    //line += "[img " + "svg" + "]<img src=\"" + imageName2+".svg" + "\" />";

                    findSVG = false;
                    svg = "";
                }
                if (line.contains("</math>")) {

                    svg += line.substring(beginMath, line.indexOf("</math>") + "</math>".length());
                    beginMath = 0;


                    final String imageName = name + "-" + svgNumbver + ".png";
                    final String imageName2 = ExtUtils.getFileName(name) + "-" + svgNumbver + ".png";
                    svg = svg.replace(">math>", "></math>");
                    svgs.put(imageName, svg);

                    LOG.d("SVG-MATH:", imageName, svg);

                    line += "<img src=\"" + imageName2 + "\" />";

                    findSVG = false;
                    svg = "";
                }
                if (findSVG) {
                    svg += line;
                }

            }


            boolean isProcess = AppState.get().isEnableTextReplacement ||
                    (BookCSS.get().isAutoHypens && TxtUtils.isNotEmpty(AppSP.get().hypenLang));
            if (isProcess) {
                line = HypenUtils.applyHypnes(line, replacements);
            }

            if (!isValidXML) {
                writer.println("<p>");
            }
            if (!isValidXML && AppState.get().isCharacterEncoding) {
                line = new String(line.getBytes("windows-1252"), AppState.get().characterEncoding);
            }
            writer.println(line);

            if (!isValidXML) {
                writer.println("</p>");
            }

            // LOG.d("gen-ou", line);

        }
        if (!isValidXML) {
            writer.print("</body></html>");
        }

        writer.close();
    }

    public static synchronized String processRemoteImages(String line) {
        if ((BookCSS.get().documentStyle == BookCSS.STYLES_ONLY_USER || AppState.get().isExperimental) && line.contains("<img src=\"http")) {
            try {
                String imgScr = "<img src=\"";
                int i1 = line.indexOf(imgScr);
                int i2 = line.indexOf("\"", i1 + imgScr.length());
                String uri = line.substring(i1 + imgScr.length(), i2);
                LOG.d("remote-image-url", uri);

                Glide.with(LibreraApp.context).asFile().load(uri).diskCacheStrategy(DiskCacheStrategy.AUTOMATIC).submit().get();
                Bitmap submit = Glide.with(LibreraApp.context).asBitmap().diskCacheStrategy(DiskCacheStrategy.AUTOMATIC).load(uri).submit().get();


                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                submit.compress(Bitmap.CompressFormat.JPEG, 100, stream);
                byte[] byteArray = stream.toByteArray();
                //submit.recycle();


                StringBuffer buffer = new StringBuffer("data:image/jpeg;base64,");
                buffer.append(net.arnx.wmf2svg.util.Base64.encode(byteArray));
                String data = buffer.toString();

                line = line.substring(0, i1 + imgScr.length()) + data + line.substring(i2);
                LOG.d("remote-image-line", line);
            } catch (Exception e) {
                LOG.e(e);
            }


        }
        return line;
    }

    public static String includeFooterNotes(String line, Map<String, String> notes, String name) {
        if (notes == null) {
            return line;
        }

        int beginIndex = -1;
        int endIndex = -1;
        StringBuffer out = new StringBuffer();

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '[' || c == '{') {
                beginIndex = i;
            }
            if (c == ']' || c == '}') {
                endIndex = i;
            }
            out.append(c);

            if (beginIndex > 0 && endIndex > beginIndex && endIndex - beginIndex < 6) {
                String number = line.substring(beginIndex, endIndex + 1);
                beginIndex = -1;
                endIndex = -1;

                int end = line.indexOf('>', i);
                int k = end - i;
                if (end > i && k < 8) {
                    out.append(line.substring(i + 1, end + 1));
                    i += k;
                }

                LOG.d("includeFooterNotes", number, number + "#" + name);

                String value = notes.get(number + "#" + name);
                if (value != null) {
                    value = value.replace(TxtUtils.NON_BREAKE_SPACE, " ").trim();
                    value = value.replaceAll("^[\\[{][0-9]+[\\]}]", "").trim();
                    value = value.replaceAll("^[\\[{][0-9]+[\\]}]", "").trim();// two times!
                    value = value.replaceAll("^[0-9]+", "").trim();

                    out.append(" <t>[");
                    out.append(TxtUtils.escapeHtml(value));
                    out.append("]</t>");
                }
            }

        }
        return out.toString();
    }

    @Deprecated
    private static ByteArrayOutputStream generateHyphenFileEpubOld(InputStreamReader
                                                                           inputStream) throws Exception {
        BufferedReader input = new BufferedReader(inputStream);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(out);
        String line;

        HypenUtils.applyLanguage(AppSP.get().hypenLang);

        while ((line = input.readLine()) != null) {
            LOG.d("gen0-in", line);
            if (TempHolder.get().loadingCancelled.get()) {
                break;
            }

            if (!line.endsWith(" ")) {
                line = line + " ";
            }

            line = accurateLine(line);

            String subLine[] = line.split("</");

            for (int i = 0; i < subLine.length; i++) {
                if (i == 0) {
                    line = subLine[i];
                } else {
                    line = "</" + subLine[i];
                }

                line = HypenUtils.applyHypnesOld2(line);
                writer.print(line);
                LOG.d("gen0-ou", line);
            }
            writer.println();

        }
        writer.close();
        return out;
    }

    /** 单个切分结果：part 文件名 / part XML（校验通过）/ 每个 part 覆盖的
     * title 锚 id 区间（含端点）。 */
    public static class Fb2Split {
        final java.util.List<String> names = new ArrayList<String>();
        final java.util.List<String> parts = new ArrayList<String>();
        final java.util.List<int[]> anchorRanges = new ArrayList<int[]>();

        String partOfAnchor(long anchor) {
            for (int i = 0; i < anchorRanges.size(); i++) {
                int[] r = anchorRanges.get(i);
                if (r != null && r[0] >= 0 && anchor >= r[0] && anchor <= r[1]) {
                    return names.get(i);
                }
            }
            return null;
        }
    }

    private static final int FB2_SPLIT_BYTES = 256 * 1024;
    /** 目录锚点内容:NBSP(不可见,且保证 <a> 排版盒存在,可被 find_html_target 寻址) */
    public static final String NBSP_CHAR = String.valueOf((char) 160);

    /**
     * 把清洗后的 fb2 XML 按 256KB 切成多个完整 fb2 文档。切点：顶层或一级
     * section 关闭处；巨章无子 section 时取段落 &lt;/p&gt; 边界（新 part 重开
     * 一层裸 section）。binary 图片按 l:href 引用归属到引用它的 part；第二
     * body（脚注）随最后一个 part。逐 part 过 XmlPullParser 校验，异常返回
     * null（调用方回退单条目）。
     */
    static Fb2Split splitFb2Parts(byte[] xmlBytes) throws Exception {
        String xml = new String(xmlBytes, "utf-8");
        int fbStart = xml.indexOf("<FictionBook");
        if (fbStart < 0) {
            return null;
        }
        int fbTagEnd = xml.indexOf('>', fbStart);
        if (fbTagEnd < 0) {
            return null;
        }
        fbTagEnd += 1;
        int bodyStart = xml.indexOf("<body", fbTagEnd);
        int bodyEnd = xml.indexOf("</body>", bodyStart);
        if (bodyStart < 0 || bodyEnd < 0) {
            return null;
        }
        String header = xml.substring(0, fbTagEnd);
        String bodyOpen = xml.substring(bodyStart, xml.indexOf('>', bodyStart) + 1);
        String body = xml.substring(xml.indexOf('>', bodyStart) + 1, bodyEnd);
        String tail = xml.substring(bodyEnd + "</body>".length());

        // binary 块收集（id → 完整块）
        java.util.Map<String, String> binaries = new java.util.HashMap<String, String>();
        StringBuilder tailNoBinary = new StringBuilder();
        {
            int pos = 0;
            while (true) {
                int b0 = tail.indexOf("<binary", pos);
                if (b0 < 0) {
                    tailNoBinary.append(tail, pos, tail.length());
                    break;
                }
                int b1 = tail.indexOf("</binary>", b0);
                if (b1 < 0) {
                    tailNoBinary.append(tail, pos, tail.length());
                    break;
                }
                tailNoBinary.append(tail, pos, b0);
                String block = tail.substring(b0, b1 + "</binary>".length());
                String id = attrValue(block.substring(0, block.indexOf('>') + 1), "id");
                if (id != null) {
                    binaries.put(id, block);
                }
                pos = b1 + "</binary>".length();
            }
        }
        if (!tailNoBinary.toString().contains("</FictionBook>")) {
            return null; // 结构意外，回退
        }

        Fb2Split r = new Fb2Split();
        LOG.bench("fb2-split body=" + body.length() + " tail=" + tail.length()
                + " binaries=" + binaries.size());
        StringBuilder cur = new StringBuilder();
        int curAnchorMin = -1, curAnchorMax = -1;
        int depth = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<section|</section|<p[ >]|</p>|<a id=\"(\\d+)\"").matcher(body);
        int last = 0;
        while (m.find()) {
            String tk = m.group();
            boolean closeSection = tk.startsWith("</section");
            boolean closeP = tk.equals("</p>");
            if (tk.startsWith("<section")) {
                depth++;
            } else if (closeSection) {
                depth--;
            } else if (tk.startsWith("<a id=")) {
                long a = Long.parseLong(m.group(1));
                if (curAnchorMin < 0 || a < curAnchorMin) {
                    curAnchorMin = (int) a;
                }
                if (a > curAnchorMax) {
                    curAnchorMax = (int) a;
                }
            }
            // 切分点必须落在完整标签之后：正则里的 "</section" 不含 '>'，
            // 直接取 m.end() 会把 part 截在 "</section" 与 '>' 之间（校验器是
            // FEATURE_RELAXED 的宽容解析，照样放行，损坏 part 被写进缓存）。
            // 这里把闭合标签补全到 '>' 之后
            int tokenEnd = m.end();
            if (closeSection) {
                int gt = body.indexOf('>', tokenEnd);
                if (gt >= 0) {
                    boolean onlyWs = true;
                    for (int k = tokenEnd; k < gt; k++) {
                        if (!Character.isWhitespace(body.charAt(k))) {
                            onlyWs = false;
                            break;
                        }
                    }
                    if (onlyWs) {
                        tokenEnd = gt + 1;
                    }
                }
            }
            boolean boundary = (closeSection && (depth == 0 || depth == 1))
                    || (closeP && depth <= 1);
            if (boundary && cur.length() + (tokenEnd - last) >= FB2_SPLIT_BYTES) {
                cur.append(body, last, tokenEnd);
                last = tokenEnd;
                int reopen = depth > 0 ? 1 : 0;
                appendSplitPart(r, header, bodyOpen, cur, binaries, curAnchorMin, curAnchorMax);
                cur = new StringBuilder();
                for (int i = 0; i < reopen; i++) {
                    cur.append("<section>");
                }
                curAnchorMin = -1;
                curAnchorMax = -1;
            }
        }
        cur.append(body, last, body.length());
        // 收尾 part：body 剩余 + 第二 body 等（tailNoBinary 自带 </FictionBook>）
        StringBuilder lastPart = new StringBuilder();
        lastPart.append(header).append(bodyOpen).append("<body>").append(cur)
                .append("</body>").append(tailNoBinary);
        // 末段同样要回填引用到的 binary：此前只有中间 part 回填，末章与脚注
        // body（随 tail 进入本段）的图片全部丢失
        String tailStr = tailNoBinary.toString();
        String lastBinaries = collectReferencedBinaries(cur.toString() + tailStr, binaries);
        if (lastBinaries.length() > 0) {
            int end = lastPart.lastIndexOf("</FictionBook>");
            lastPart.insert(end >= 0 ? end : lastPart.length(), lastBinaries);
        }
        r.parts.add(validateFb2Part(lastPart.toString()));
        r.names.add(partName(r.parts.size() - 1));
        r.anchorRanges.add(new int[]{curAnchorMin, curAnchorMax});
        LOG.bench("fb2-split parts=" + r.parts.size()
                + " nullParts=" + java.util.Collections.frequency(r.parts, null));
        if (r.parts.size() < 2) {
            return null;
        }
        for (String part : r.parts) {
            if (part == null) {
                return null; // 任一 part 校验失败 → 整体回退
            }
        }
        return r;
    }

    private static String partName(int i) {
        return String.format(java.util.Locale.US, "fb2_%03d.fb2", i + 1);
    }

    /** 本段内容里 l:href="#id" 引用到的 binary 块（去重，按出现顺序拼接）。 */
    private static String collectReferencedBinaries(CharSequence content,
            java.util.Map<String, String> binaries) {
        StringBuilder out = new StringBuilder();
        java.util.regex.Matcher ref = java.util.regex.Pattern
                .compile("l:href=\"#([^\"]+)\"").matcher(content);
        java.util.Set<String> added = new java.util.HashSet<String>();
        while (ref.find()) {
            String block = binaries.get(ref.group(1));
            if (block != null && added.add(ref.group(1))) {
                out.append(block);
            }
        }
        return out.toString();
    }

    private static void appendSplitPart(Fb2Split r, String header, String bodyOpen,
            StringBuilder content, java.util.Map<String, String> binaries, int aMin, int aMax) {
        StringBuilder sb = new StringBuilder();
        sb.append(header).append(bodyOpen).append("<body>").append(content).append("</body>");
        // 本 part 引用到的 binary 追加进同文档（XML 模式锚点仅文档内解析）
        sb.append(collectReferencedBinaries(content, binaries));
        sb.append("</FictionBook>");
        r.parts.add(validateFb2Part(sb.toString()));
        r.names.add(partName(r.parts.size() - 1));
        r.anchorRanges.add(new int[]{aMin, aMax});
    }

    private static String attrValue(String tag, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(name + "=\"([^\"]+)\"").matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    private static String validateFb2Part(String partXml) {
        try {
            org.xmlpull.v1.XmlPullParser xpp = XmlParser.buildPullParser();
            xpp.setInput(new java.io.StringReader(partXml));
            int ev = xpp.getEventType();
            while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                ev = xpp.next();
            }
            return partXml;
        } catch (Throwable t) {
            LOG.bench("fb2-split validate FAIL: " + t);
            return null;
        }
    }

    private static String buildSplitOpf(java.util.List<String> names) {
        StringBuilder manifest = new StringBuilder();
        StringBuilder spine = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            manifest.append("  <item id=\"idFb2P").append(i).append("\" href=\"")
                    .append(names.get(i)).append("\" media-type=\"application/xhtml+xml\"/>\n");
            spine.append("  <itemref idref=\"idFb2P").append(i).append("\"/>\n");
        }
        return content_opf
                .replace("  <item id=\"idBookFb2\" href=\"fb2.fb2\" media-type=\"application/xhtml+xml\"/>\n",
                        manifest.toString())
                .replace("  <itemref idref=\"idBookFb2\"/>\n", spine.toString());
    }

    public static String genetateNCX(List<String> titles) {
        StringBuilder navs = new StringBuilder();
        for (int i = 0; i < titles.size(); i++) {
            navs.append(createNavPoint(i + 1, titles.get(i)));
        }
        return NCX.replace("%nav%", navs.toString());
    }

    public static String genetateNCXbyOutline(List<OutlineLink> titles) {
        StringBuilder navs = new StringBuilder();
        for (int i = 0; i < titles.size(); i++) {
            OutlineLink link = titles.get(i);
            String titleTxt = link.getTitle();
            if (TxtUtils.isNotEmpty(titleTxt)) {
                String createNavPoint = createNavPoint(OutlineLinkWrapper.getPageNumber(link.getLink()), link.getLevel() + DIVIDER + titleTxt);
                if (link.contentSrc != null) {
                    createNavPoint = createNavPoint.replace("fb2.fb2", link.contentSrc);
                } else {
                    createNavPoint = createNavPoint.replace("fb2.fb2", "temp-reflow.html");
                }
                navs.append(createNavPoint);
            }
        }
        return NCX.replace("%nav%", navs.toString());
    }

    public static String genetateNCXbyOutlineMd(List<OutlineLink> titles) {
        StringBuilder navs = new StringBuilder();
        for (int i = 0; i < titles.size(); i++) {
            OutlineLink link = titles.get(i);
            String createNavPoint = createNavPoint(link.getLevel(), link.getLevel() + DIVIDER + link.getTitle(), link.contentSrc);
            navs.append(createNavPoint);
        }
        return NCX.replace("%nav%", navs.toString());
    }



    public static List<String> getFb2Titles(String fb2, String encoding) throws Exception {
        XmlPullParser xpp = XmlParser.buildPullParser();

        final FileInputStream inputStream = new FileInputStream(fb2);
        xpp.setInput(inputStream, encoding);
        int eventType = xpp.getEventType();

        boolean isTitle = false;
        String title = "";
        List<String> titles = new ArrayList<String>();

        int section = 0;
        int dividerSection = -1;
        String dividerLine = null;
        boolean secondBody = false;
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (TempHolder.get().loadingCancelled.get()) {
                break;
            }
            if (eventType == XmlPullParser.START_TAG) {
                if (xpp.getName().equals("section")) {
                    section++;
                }
                if (xpp.getName().equals("title")) {
                    isTitle = true;
                }
                if (xpp.getName().equals("binary")) {
                    break;
                }

                if (xpp.getName().equals("body")) {
                    if (secondBody && xpp.getAttributeCount() > 0) {
                        break;
                    }
                    secondBody = true;
                }

            } else if (eventType == XmlPullParser.END_TAG) {
                if (xpp.getName().equals("title")) {
                    isTitle = false;
                    title = "[" + xpp.getName() + "]" + title;
                    titles.add(section + DIVIDER + title);
                    title = "";
                    if (section == dividerSection) {
                        titles.remove(dividerLine);
                    }
                }
                if (xpp.getName().equals("section")) {
                    section--;
                }

            } else if (eventType == XmlPullParser.TEXT) {
                if (isTitle) {
                    title = title + " " + xpp.getText().trim();
                }
            }
            eventType = xpp.next();
        }
        inputStream.close();
        if (!titles.isEmpty() && titles.get(titles.size() - 1).endsWith(DIVIDER)) {
            titles.remove(titles.size() - 1);
        }
        if (!titles.isEmpty() && titles.get(titles.size() - 1).endsWith(FOOTER_NOTES_SIGN)) {
            titles.remove(titles.size() - 1);
        }

        return titles;
    }

    public static String findHeaderEncoding(String fb2) {
        String encoding = "UTF-8";
        try {
            InputStream encodingCheck = new FileInputStream(fb2);
            byte[] header = new byte[80];
            encodingCheck.read(header);
            if (new String(header).toLowerCase(Locale.US).contains("windows-1251")) {
                encoding = "cp1251";
            } else if (new String(header).toLowerCase(Locale.US).contains("windows-1252")) {
                encoding = "cp1252";
            }
            encodingCheck.close();
            return encoding;
        } catch (Exception e) {
            return encoding;
        }
    }

    public static String createNavPoint(int id, String text) {
        return "\n\n<navPoint id=\"toc-" + id + "\" playOrder=\"" + id + "\">\n" + //
                "<navLabel>\n" + //
                "<text>" + TxtUtils.escapeHtml(text) + "</text>\n" + //
                "</navLabel>\n" + //
                "<content src=\"fb2.fb2#" + id + "\"/>\n" + //
                "</navPoint>"; //
    }

    public static String createNavPoint(int id, String text, String url) {
        return "\n\n<navPoint id=\"toc-" + id + "\" playOrder=\"" + id + "\">\n" + //
                "<navLabel>\n" + //
                "<text>" + TxtUtils.escapeHtml(text) + "</text>\n" + //
                "</navLabel>\n" + //
                "<content src=\"" + url + "\"/>\n" + //
                "</navPoint>"; //
    }

    public static void writeToZip(ZipOutputStream zos, String name, InputStream stream) throws
            IOException {
        zos.putNextEntry(new ZipEntry(name));
        zipCopy(stream, zos);
    }

    public static void writeToZipDir(ZipOutputStream zos, String name) throws
            IOException {
        if (!name.endsWith("/")) {
            name = name + "/";
        }
        zos.putNextEntry(new ZipEntry(name));
    }

    public static void writeToZipNoClose(ZipOutputStream zos, String name, InputStream stream) throws
            IOException {
        try {
            zos.putNextEntry(new ZipEntry(name));
            zipCopyNoClose(stream, zos);
        } catch (IOException e) {
            LOG.e(e);
        }
    }

    public static void writeToZip(ZipOutputStream zos, String name, String content) throws
            IOException {
        writeToZip(zos, name, new ByteArrayInputStream(content.getBytes()));
    }

    public static void zipCopy(InputStream inputStream, OutputStream zipStream) throws
            IOException {

        byte[] bytesIn = new byte[BUFFER_SIZE];
        int read = 0;
        while ((read = inputStream.read(bytesIn)) != -1) {
            zipStream.write(bytesIn, 0, read);
        }
        inputStream.close();
    }

    public static void zipCopyNoClose(InputStream inputStream, OutputStream zipStream) throws
            IOException {

        byte[] bytesIn = new byte[BUFFER_SIZE];
        int read = 0;
        while ((read = inputStream.read(bytesIn)) != -1) {
            zipStream.write(bytesIn, 0, read);
        }
    }

    public void loadGenres() {
        if (!genresRus.isEmpty()) {
            return;
        }
        try {
            {
                InputStream xmlStream = LibreraApp.context.getAssets().open("union_genres_ru_1.xml");
                XmlPullParser xpp = XmlParser.buildPullParser();
                xpp.setInput(xmlStream, "UTF-8");

                int eventType = xpp.getEventType();
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG) {
                        if (xpp.getName().equals("genre")) {
                            String name = xpp.getAttributeValue(0);
                            String code = xpp.getAttributeValue(1);
                            genresRus.put(code, name);
                            LOG.d("loadGenres-add-1", code, name);
                        }
                    }
                    eventType = xpp.next();
                }
                xmlStream.close();
            }
            {
                InputStream xmlStream = LibreraApp.context.getAssets().open("union_genres_ru_2.xml");
                XmlPullParser xpp = XmlParser.buildPullParser();
                xpp.setInput(xmlStream, "UTF-8");

                int eventType = xpp.getEventType();
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG) {
                        if (xpp.getName().equals("subgenres")) {
                            String name = xpp.getAttributeValue(1);
                            String code = xpp.getAttributeValue(2);
                            if (!genresRus.containsKey(code)) {
                                genresRus.put(code, name);
                            }
                            LOG.d("loadGenres-add-2", code, name);
                        }
                    }
                    eventType = xpp.next();
                }
                xmlStream.close();
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    public byte[] getBookCover(InputStream inputStream, String name) {
        byte[] decode = null;
        try {
            XmlPullParser xpp = XmlParser.buildPullParser();
            xpp.setInput(inputStream, "UTF-8");

            int eventType = xpp.getEventType();
            String imageID = null;
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {

                    if (imageID == null && xpp.getName().equals("image")) {
                        imageID = xpp.getAttributeValue(0);

                        if (TxtUtils.isNotEmpty(imageID)) {
                            imageID = imageID.replace("#", "");
                        }

                    }
                    if (imageID != null && xpp.getName().equals("binary") && imageID.equals(xpp.getAttributeValue(null, "id"))) {
                        decode = Base64.decode(xpp.nextText(), Base64.DEFAULT);
                        break;
                    }
                }
                eventType = xpp.next();
            }
        } catch (Exception e) {
            LOG.e(e);
        }
        return decode;
    }

    @Override
    public byte[] getBookCover(String path) {
        byte[] decode = null;
        try {
            XmlPullParser xpp = XmlParser.buildPullParser();
            try(final FileInputStream inputStream = new FileInputStream(path)) {
                xpp.setInput(inputStream, "UTF-8");

                int eventType = xpp.getEventType();
                String imageID = null;
                String imageCover = null;
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG) {

                        if (xpp.getName().equals("image")) {
                            if (imageID == null) {
                                imageID = xpp.getAttributeValue(0);
                                if (TxtUtils.isNotEmpty(imageID)) {
                                    imageID = imageID.replace("#", "");
                                }
                            }
                            if (imageCover == null) {
                                imageCover = xpp.getAttributeValue(0);
                                if (TxtUtils.isNotEmpty(imageCover) && imageCover.toLowerCase(Locale.US)
                                                                                 .contains("cover")) {
                                    imageCover = imageID = imageCover.replace("#", "");
                                } else {
                                    imageCover = null;
                                }
                            }
                        }

                        if (imageID != null && xpp.getName()
                                                  .equals("binary") && imageID.equals(xpp.getAttributeValue(null, "id"))) {
                            String text = xpp.nextText();
                            if (text != null) {
                                decode = Base64.decode(text, Base64.DEFAULT);
                            }
                            break;
                        }
                    }
                    eventType = xpp.next();
                }
            }

        } catch (Exception e) {
            LOG.e(e, path);
        }
        return decode;
    }

    @Override
    public String getBookOverview(String path) {
        String info = "";
        try {
            XmlPullParser xpp = XmlParser.buildPullParser();
            xpp.setInput(new FileInputStream(path), findHeaderEncoding(path));

            int eventType = xpp.getEventType();
            boolean findAnnotation = false;
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    if ("annotation".equals(xpp.getName())) {
                        findAnnotation = true;
                    }
                    if ("body".equals(xpp.getName())) {
                        break;
                    }
                }
                if (eventType == XmlPullParser.TEXT) {
                    if (findAnnotation) {
                        info = info + " " + xpp.getText();
                    }

                }
                if (eventType == XmlPullParser.END_TAG) {
                    if ("annotation".equals(xpp.getName())) {
                        break;
                    }
                }

                eventType = xpp.next();
            }
        } catch (Exception e) {
            LOG.e(e);
        }

        return info;
    }

    @Override
    public EbookMeta getBookMetaInformation(String inputFile) {
        try {
            // if (inputFile.contains(ExtUtils.REFLOW_FB2)) {
            // return new EbookMeta(new
            // File(inputFile).getName().replace(ExtUtils.REFLOW_FB2, ""), "Text Reflow",
            // "", "");
            // }

            XmlPullParser xpp = XmlParser.buildPullParser();
            final FileInputStream inputStream = new FileInputStream(inputFile);
            xpp.setInput(inputStream, findHeaderEncoding(inputFile));

            String bookTitle = null;

            String firstName = null;
            String lastName = null;

            String authors = "";

            String genre = "";
            String sequence = "";
            String lang = "";
            String number = "";
            String keywords = "";
            boolean titleInfo = false;
            boolean authorInfo = false;
            boolean publishInfo = false;
            String year = "";
            String publisher = "";
            String isbn = "";

            int eventType = xpp.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {

                if (eventType == XmlPullParser.START_TAG) {

                    if (xpp.getName().equals("title-info")) {
                        titleInfo = true;
                    }
                    if (xpp.getName().equals("author")) {
                        authorInfo = true;
                    }

                    if (xpp.getName().equals("publish-info")) {
                        publishInfo = true;
                    }

                    if (publishInfo) {
                        if (xpp.getName().equals("year")) {
                            year = xpp.nextText();
                        }
                        if (xpp.getName().equals("publisher")) {
                            publisher = xpp.nextText();
                        }
                        if (xpp.getName().equals("isbn")) {
                            isbn = xpp.nextText();
                        }
                    }

                    if (titleInfo) {
                        if (xpp.getName().equals("book-title")) {
                            bookTitle = xpp.nextText();
                        } else if (xpp.getName().equals("lang")) {
                            lang = xpp.nextText();
                        } else if (authorInfo && firstName == null && xpp.getName().equals("first-name")) {
                            firstName = xpp.nextText();
                        } else if (authorInfo && lastName == null && xpp.getName().equals("last-name")) {
                            lastName = xpp.nextText();
                        } else if (xpp.getName().equals("genre")) {
                            genre = xpp.nextText() + "," + genre;
                        } else if (xpp.getName().equals("keywords")) {
                            keywords = xpp.nextText();
                        } else if (xpp.getName().equals("sequence")) {
                            sequence = xpp.getAttributeValue(null, "name");
                            String current = xpp.getAttributeValue(null, "number");
                            if (TxtUtils.isNotEmpty(current) && !("0".equals(current) || "00".equals(current))) {
                                number = current;
                            }
                        }

                    }
                }
                if (eventType == XmlPullParser.END_TAG) {
                    if (titleInfo && firstName != null && lastName != null) {
                        firstName = TxtUtils.trim(firstName);
                        lastName = TxtUtils.trim(lastName);

                        authors = authors + ", " + firstName + " " + lastName;
                        firstName = null;
                        lastName = null;

                    }

                    if (xpp.getName().equals("description")) {
                        break;
                    }
                    if (xpp.getName().equals("title-info")) {
                        titleInfo = false;
                    }
                    if (xpp.getName().equals("author")) {
                        authorInfo = false;
                    }

                    if (xpp.getName().equals("publish-info")) {
                        publishInfo = false;
                    }
                }
                eventType = xpp.next();
            }
            inputStream.close();

            genre = genre.replace(",,", ",") + ",";
            authors = TxtUtils.replaceFirst(authors, ", ", "");

            loadGenres();
            for (String g : genre.split(",")) {
                String value = genresRus.get(g.trim());
                if (TxtUtils.isNotEmpty(value)) {
                    genre = genre.replace(g + ",", value + ",");
                    LOG.d("loadGenres-repalce", g, value);
                } else {
                    LOG.d("loadGenres-not-found", g);
                }
            }
            genre = TxtUtils.replaceLast(genre, ",", "");

            if (TxtUtils.isNotEmpty(number)) {
                EbookMeta ebookMeta = new EbookMeta(bookTitle, authors, sequence, genre);
                try {
                    ebookMeta.setLang(lang);
                    ebookMeta.setsIndex(Integer.parseInt(number));
                    ebookMeta.setKeywords(keywords);
                    ebookMeta.setYear(year);
                    ebookMeta.setPublisher(publisher);
                    ebookMeta.setIsbn(isbn);
                    // ebookMeta.setPagesCount((int) fileSize / 512);
                } catch (Exception e) {
                    LOG.e(e);
                }
                return ebookMeta;
            } else {
                EbookMeta ebookMeta = new EbookMeta(bookTitle, authors, sequence, genre);
                ebookMeta.setLang(lang);
                ebookMeta.setKeywords(keywords);
                ebookMeta.setYear(year);
                ebookMeta.setPublisher(publisher);
                ebookMeta.setIsbn(isbn);
                // ebookMeta.setPagesCount((int) fileSize / 512);
                return ebookMeta;
            }

        } catch (Exception e) {
            LOG.w(e, "!!!!", inputFile);
        }
        return EbookMeta.Empty();
    }

    @Override
    public Map<String, String> getFooterNotes(String inputFile) {
        Map<String, String> map = new HashMap<String, String>();
        try {

            XmlPullParser xpp = XmlParser.buildPullParser();
            final FileInputStream inputStream = new FileInputStream(inputFile);
            xpp.setInput(inputStream, findHeaderEncoding(inputFile));
            int eventType = xpp.getEventType();

            String sectionId = null;
            StringBuilder text = null;
            boolean isLink = false;
            String link = null;
            String key = "";

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (TempHolder.get().loadingCancelled.get()) {
                    break;
                }
                if (eventType == XmlPullParser.START_TAG) {
                    if (xpp.getName().equals("a")) {
                        // String type = xpp.getAttributeValue(null, "type");
                        // if ("note".equals(type)) {
                        isLink = true;

                        link = xpp.getAttributeValue(null, "l:href");
                        if (link == null) {
                            link = xpp.getAttributeValue(null, "xlink:href");
                        }

                        // }
                    } else if (xpp.getName().equals("section")) {
                        sectionId = xpp.getAttributeValue(null, "id");
                        text = new StringBuilder();
                    }
                } else if (eventType == XmlPullParser.TEXT) {
                    if (sectionId != null) {
                        String trim = xpp.getText().trim();
                        if (trim.length() > 0) {
                            text.append(trim + " ");
                        }
                    }
                    if (isLink) {
                        key = key + " " + xpp.getText();
                        LOG.d("key", key);
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    if (sectionId != null && xpp.getName().equals("section")) {
                        String keyEnd = StreamUtils.getKeyByValue(map, sectionId);

                        map.put(keyEnd, text.toString().trim());//1
                        keyEnd = keyEnd + "#OEBPS/fb2.fb2";
                        map.put(keyEnd, text.toString().trim());//2

                        LOG.d("getFooterNotes-section", sectionId, keyEnd, ">", text.toString());
                        LOG.d("getFooterNotesFb2-section", keyEnd, text.toString().trim());
                        sectionId = null;
                        text = null;
                    } else if (xpp.getName().equals("a")) {

                        if (isLink && link != null) {
                            key = key.trim();
                            if (!TxtUtils.isFooterNote(key)) {
                                key = "[" + link + "]";
                            }
                            link = link.replace("#", "");
                            map.put(key, link.trim());
                            LOG.d("getFooterNotes-link", key, ">", link);
                            LOG.d("getFooterNotesFb2-link", key, link);


                            key = "";
                        }
                        if (isLink) {
                            isLink = false;
                        }
                    }
                }

                eventType = xpp.next();
            }
            inputStream.close();
        } catch (Exception e) {
            LOG.e(e);
        }
        return map;
    }

    @Deprecated
    private boolean convertFB2(String inputFile, String toName) {
        FileOutputStream out = null;
        try {
            String encoding = findHeaderEncoding(inputFile);
            ByteArrayOutputStream generateFb2File = generateFb2File(inputFile, encoding, true, null, new ArrayList<>());
            out = new FileOutputStream(toName);
            out.write(generateFb2File.toByteArray());
            out.close();
        } catch (Exception e) {
            LOG.e(e);
            return false;
        } finally {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Exception ignore) {
            }
        }
        return true;

    }

    public boolean convert(String inputFile, String toName, boolean fixHTML, Map<String, String> notes) {

        FileOutputStream out = null;
        ZipOutputStream zos = null;
        try {
            out = new FileOutputStream(new File(toName));
            zos = new ZipOutputStream(out);
            zos.setLevel(0);

            writeToZip(zos, "mimetype", "application/epub+zip");
            writeToZip(zos, "META-INF/container.xml", container_xml);

            final long fb2T0 = android.os.SystemClock.elapsedRealtime();
            String encoding = findHeaderEncoding(inputFile);
            List<String> titles = getFb2Titles(inputFile, encoding);
            LOG.bench("fb2-titles " + (android.os.SystemClock.elapsedRealtime() - fb2T0) + "ms");

            List<SimpleMeta> replacements = AppData.get().getAllTextReplaces();
            final long tGen = android.os.SystemClock.elapsedRealtime();
            ByteArrayOutputStream generateFb2File = generateFb2File(inputFile, encoding, fixHTML, notes, replacements);
            LOG.bench("fb2-generate " + (android.os.SystemClock.elapsedRealtime() - tGen) + "ms "
                    + generateFb2File.size() + "b");
            // 章节切分：html 引擎的排版单位是 spine 条目，单巨章会让重开时的
            // 首页渲染触发整本书重排（万页书 8 秒级）。按 256KB 切成多个 part
            // 条目后首屏只排第一小章。任何不确定都回退单条目原路径。
            Fb2Split split = null;
            try {
                final long tSplit = android.os.SystemClock.elapsedRealtime();
                split = splitFb2Parts(generateFb2File.toByteArray());
                LOG.bench("fb2-split " + (android.os.SystemClock.elapsedRealtime() - tSplit) + "ms");
            } catch (Throwable t) {
                LOG.bench("fb2-split EXC: " + t);
                LOG.e(t);
            }
            final long tNcx = android.os.SystemClock.elapsedRealtime();
            String ncx = genetateNCX(titles);
            LOG.bench("fb2-ncx " + (android.os.SystemClock.elapsedRealtime() - tNcx) + "ms titles="
                    + titles.size() + " ncxB=" + ncx.length());
            if (split != null && split.names.size() > 1) {
                for (int i = 0; i < split.names.size(); i++) {
                    writeToZip(zos, "OEBPS/" + split.names.get(i), new java.io.ByteArrayInputStream(split.parts.get(i).getBytes("utf-8")));
                }
                writeToZip(zos, "OEBPS/content.opf", buildSplitOpf(split.names));
                // 目录锚点映射：src="fb2.fb2#N" → 所在 part 文件（锚随正文走）。
                // 旧实现逐条 String.replace 全文重扫重建（千章级 = O(N²) 秒级
                // 开销），改单遍正则重写。
                final long tMap = android.os.SystemClock.elapsedRealtime();
                final java.util.regex.Matcher am = java.util.regex.Pattern
                        .compile("src=\"fb2\\.fb2#(\\d+)\"").matcher(ncx);
                final StringBuilder ncxSb = new StringBuilder(ncx.length() + 64);
                int last = 0;
                int mapped = 0;
                while (am.find()) {
                    final int anchor = Integer.parseInt(am.group(1));
                    final String part = split.partOfAnchor(anchor);
                    ncxSb.append(ncx, last, am.start());
                    ncxSb.append("src=\"").append(part != null ? part : "fb2.fb2")
                            .append('#').append(anchor).append('"');
                    last = am.end();
                    if (part != null) {
                        mapped++;
                    }
                }
                ncxSb.append(ncx, last, ncx.length());
                ncx = ncxSb.toString();
                LOG.bench("fb2-ncx-map " + (android.os.SystemClock.elapsedRealtime() - tMap)
                        + "ms mapped=" + mapped);
                LOG.d("Fb2Context convert split parts", split.names.size());
            } else {
                writeToZip(zos, "OEBPS/fb2.fb2", new ByteArrayInputStream(generateFb2File.toByteArray()));
                writeToZip(zos, "OEBPS/content.opf", content_opf);
            }
            writeToZip(zos, "OEBPS/fb2.ncx", ncx);
            LOG.d("Fb2Context convert true");
            zos.close();
            out.close();
            LOG.bench("fb2-zip+write done");
            return true;
        } catch (Exception e) {
            LOG.d("Fb2Context convert false error");
            LOG.e(e);
        } catch (Throwable e) {
            LOG.e(e);
        } finally {
            // 异常路径也要关流：磁盘满/写失败时原实现泄漏句柄并留半成品缓存
            try {
                if (zos != null) {
                    zos.close();
                }
            } catch (Exception ignore) {
            }
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Exception ignore) {
            }
        }
        LOG.d("Fb2Context convert false");
        return false;
    }

    public ByteArrayOutputStream generateFb2File(String fb2, String encoding, boolean fixXML, Map<String, String> notes, List<SimpleMeta> replacements) throws Exception {
        BufferedReader input = new BufferedReader(new InputStreamReader(new FileInputStream(fb2), encoding));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(out);
        String line;

        int count = 0;

        if (BookCSS.get().isAutoHypens) {
            HypenUtils.applyLanguage(AppSP.get().hypenLang);
        }

        boolean isFindBodyEnd = false;

        long init = System.currentTimeMillis();

        boolean firstLine = true;

        boolean ready = true;
        HypenUtils.resetTokenizer();
        while ((line = input.readLine()) != null) {
            if (TempHolder.get().loadingCancelled.get()) {
                break;
            }
            if (BookCSS.get().documentStyle == BookCSS.STYLES_ONLY_USER || fixXML) {
                line = line.replace("<empty-line/>", "");
            }

            if (firstLine) {
                List<String> encodings = Arrays.asList("utf-8", "windows-1251", "Windows-1251", "windows-1252", "Windows-1252");
                for (String e : encodings) {
                    if (line.contains(e)) {
                        line = line.replace(e, "utf-8");
                        break;
                    }
                }
                firstLine = false;
                // 声明独占一行（常规多行 fb2）：本行无需清洗，直接放行。
                // 单行巨 XML（整书挤在第一行，如 Bench25 基准书）不能在这里
                // 直通——否则整书原样拷贝：无目录锚点注入、切章部件 3 倍胖
                // （实测 781KB×29）。落入下方常规清洗流程。
                if (line.trim().endsWith("?>")) {
                    writer.println(line);
                    continue;
                }
            }

            if (fixXML) {
                line = line.replace("l:href==", "l:href=");
            }

            line = accurateLine(line);


            if (AppState.get().isShowFooterNotesInText) {
                line = includeFooterNotes(line, notes, "OEBPS/fb2.fb2");
            }

            String subLine[] = line.split("</");

            line = null;

            for (int i = 0; i < subLine.length; i++) {
                if (i == 0) {
                    line = subLine[i];
                } else {
                    line = "</" + subLine[i];
                }

                if (!isFindBodyEnd) {

                    int indexOf = line.indexOf("</title>");
                    if (indexOf >= 0) {
                        ready = true;
                        count++;
                        // 锚点必须带内容:空 <a id=N></a> 不生成排版盒,目录/跳转找不到目标
                        // (实测 find_html_target 返回 -1)。NBSP 渲染不可见且保证流盒存在。
                        line = line.substring(0, indexOf) + "<a id=\"" + count + "\">" + Fb2Extractor.NBSP_CHAR + "</a>" + line.substring(indexOf);
                    }

                    if (BookCSS.get().isCapitalLetter && ready) {
                        int indexP = line.indexOf("<p");
                        if (indexP >= 0) {
                            line = capitalLetter(line, indexP);
                            ready = false;
                        }
                    }
                }

                if (!isFindBodyEnd && line.contains("<binary")) {
                    isFindBodyEnd = true;
                }

                if (!isFindBodyEnd) {
                    boolean isProcess = AppState.get().isEnableTextReplacement ||
                            (BookCSS.get().isAutoHypens && TxtUtils.isNotEmpty(AppSP.get().hypenLang));
                    if (isProcess) {
                        line = HypenUtils.applyHypnes(line, replacements);
                    }
                }
                writer.print(line);
            }

        }

        long delta = System.currentTimeMillis() - init;
        LOG.d("generateFb2File", delta / 1000.0);
        input.close();
        writer.close();

        return out;
    }

    public String capitalLetter(String line, int indexOf) {
        String anchor = "<p";
        if (indexOf >= 0) {
            anchor = ">";
            indexOf = line.indexOf('>', indexOf);
        }
        if (indexOf >= 0 && line.length() - anchor.length() - indexOf >= 3) {
            indexOf = indexOf + anchor.length();

            if (line.charAt(indexOf) != '<') {

                if (!Character.isLetter(line.charAt(indexOf))) {
                    indexOf++;
                }

                if (!Character.isLetter(line.charAt(indexOf))) {
                    indexOf++;
                }

                if (Character.isLetter(line.charAt(indexOf))) {
                    line = line.substring(0, indexOf) + "<letter>" + line.substring(indexOf, indexOf + 1) + "</letter>" + line.substring(indexOf + 1);
                    LOG.d("check-line-new", line);
                }
            }

        }
        return line;
    }

}
