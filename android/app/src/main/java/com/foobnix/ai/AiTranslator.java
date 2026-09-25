package com.foobnix.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AI translation helpers shared by the reader features: format gating,
 * target-language naming for prompts, and MuPDF page-HTML -> paragraph
 * splitting. The orchestration itself lives in {@link TranslateSession} (the
 * list panel) and {@link BilingualSession} (the in-page bilingual mode).
 */
public class AiTranslator {

    /** Formats that expose a text layer and are supported by translation. */
    public static boolean isSupportedFormat(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        String p = path.toLowerCase(Locale.US);
        return p.endsWith(".epub") || p.endsWith(".txt") || p.endsWith(".mobi")
                || p.endsWith(".azw") || p.endsWith(".azw3")
                || p.endsWith(".fb2") || p.endsWith(".fbd")
                || p.endsWith(".prc") || p.endsWith(".pdb")
                || p.endsWith(".html") || p.endsWith(".htm")
                || p.endsWith(".doc") || p.endsWith(".docx")
                || p.endsWith(".odt") || p.endsWith(".rtf");
    }

    /** Human name of the target language for the prompt (英文/中文/日文). */
    public static String targetLangName(String tgt) {
        if (LanguageDetector.JA.equals(tgt)) {
            return "日文";
        }
        if (LanguageDetector.ZH.equals(tgt)) {
            return "中文";
        }
        return "英文";
    }

    /**
     * Split a MuPDF page-HTML string into plain-text paragraphs.
     *
     * In this MuPDF build the page HTML (from the working {@code getPageAsHtml}
     * native) marks each paragraph boundary with {@code <pause>} and each line
     * within a paragraph with its own {@code <p>...</p>}; inline styling uses
     * {@code <b>/<i>/<tt>}. Lines are soft-hyphenated at the break (e.g.
     * "transla-" / "tion"), so when a line ends in a hyphen we drop it and join
     * without a space to recover the full word. We therefore split on
     * {@code <pause>} to get paragraphs, then join the {@code <p>} lines inside
     * each. This uses the working {@code getPageAsHtml} native — the
     * {@code MuPdfPage.text()} native is absent from the prebuilt libMuPDF.so
     * and throws {@code UnsatisfiedLinkError}, which is why the old paragraph
     * extraction silently returned nothing.
     */
    public static String[] htmlToParagraphs(String html) {
        if (html == null || html.isEmpty()) {
            return new String[0];
        }
        String[] chunks = html.split("<pause>");
        List<String> paras = new ArrayList<>();
        for (String chunk : chunks) {
            String t = joinLines(chunk);
            if (!t.isEmpty()) {
                paras.add(t);
            }
        }
        return paras.toArray(new String[0]);
    }

    /** Join the {@code <p>} lines of one paragraph chunk into a single clean string. */
    private static String joinLines(String chunk) {
        if (chunk == null) {
            return "";
        }
        // Soft-hyphenation: a line ending in "-" before the next line is a split
        // word (e.g. "transla-" + "tion" -> "translation"). Drop the hyphen and
        // join without a space.
        String s = chunk
                .replace("-</p>", "</p>")
                .replace("- </p>", "</p>")
                .replace("</p>", " ")
                .replace("<p>", " ")
                .replace("<b>", "")
                .replace("</b>", "")
                .replace("<i>", "")
                .replace("</i>", "")
                .replace("<tt>", "")
                .replace("</tt>", "");
        // Drop any residual tags (e.g. <div class="...">) as a safety net.
        s = s.replaceAll("<[^>]*>", " ");
        // Decode the few entities MuPDF may emit.
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'");
        return s.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }
}
