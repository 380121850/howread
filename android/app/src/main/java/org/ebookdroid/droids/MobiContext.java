package org.ebookdroid.droids;

import com.foobnix.android.utils.LOG;
import com.foobnix.libmobi.MobiFastConvert;
import com.foobnix.ext.CacheZipUtils;
import com.foobnix.ext.EpubExtractor;
import com.foobnix.ext.FooterNote;
import com.foobnix.ext.MobiExtract;
import com.foobnix.model.AppSP;
import com.foobnix.pdf.info.AppsConfig;
import com.foobnix.pdf.info.JsonHelper;
import com.foobnix.pdf.info.model.BookCSS;

import org.ebookdroid.core.codec.CodecDocument;
import org.ebookdroid.droids.mupdf.codec.MuPdfDocument;
import org.ebookdroid.droids.mupdf.codec.PdfContext;

import java.io.File;
import java.util.Map;

public class MobiContext extends PdfContext {

    String fileNameEpub = null;

    public int originalHashCode;
    File cacheFile;

    @Override
    public File getCacheFileName(String fileName) {
        originalHashCode = (fileName + BookCSS.get().isAutoHypens + AppSP.get().hypenLang).hashCode();
        cacheFile = new File(CacheZipUtils.CACHE_BOOK_DIR, originalHashCode + "" + originalHashCode + ".epub");
        return cacheFile;
    }

    /** 断词语言是否有真实断词模式（对齐 HypenUtils.applyLanguage 的归一化） */
    private static boolean hypenUseful() {
        String lang = AppSP.get().hypenLang;
        if (lang == null || lang.length() == 0) {
            return false;
        }
        lang = lang.substring(0, Math.min(2, lang.length())).toLowerCase(java.util.Locale.US);
        if ("sp".equals(lang)) {
            lang = "es";
        }
        return "en".equals(lang) || "sr".equals(lang) || "lv".equals(lang);
    }

    @Override
    public CodecDocument openDocumentInner(String fileName, String password) {

        LOG.d("Context", "MobiContext", fileName);

        if (!cacheFile.isFile()) {
            // 方案 B 落地（2026-10-05）：可流式解压的 MOBI（UTF-8/PalmDoc/正文无
            // 内嵌图片）先走 Java 快速转换——只解压 + 按 <mbp:pagebreak> 切章 +
            // 原样 STORED 写盘，不做整体 deflate，首开转换明显缩短。产物与原转换
            // 同一缓存文件，排版加速器/体检/脚注/双语全部原样继承。
            // 断词开启时沿用"临时文件 + proccessHypens 重写"原链，仅把第一步换
            // 成快速转换；快速转换任一门不满足或失败，原 LibMobi 转换原样兜底。
            final long fastT0 = android.os.SystemClock.elapsedRealtime();
            String fastInput = null;
            try {
                if (MobiFastConvert.convertToEpub(fileName,
                        new File(CacheZipUtils.CACHE_BOOK_DIR, "temp_fast"))) {
                    fastInput = new File(CacheZipUtils.CACHE_BOOK_DIR, "temp_fast.epub").getPath();
                    LOG.bench("mobi-fastconvert "
                            + (android.os.SystemClock.elapsedRealtime() - fastT0) + "ms " + fileName);
                }
            } catch (final Throwable t) {
                LOG.e(t);
            }
            if (fastInput == null) {
                LOG.bench("mobi-fastconvert unavailable -> libmobi");
            }
            try {
                if (BookCSS.get().isAutoHypens && hypenUseful()) {
                    // 断词开启且断词语言有真实断词模式（仅 en/sr/lv）：转换到临时
                    // 名，再经断词/替换重写进缓存文件（原链）。中文等无断词语义的
                    // 语言跳过这 2.6s 的全文重写——断词库对它们是纯空转。
                    if (fastInput == null) {
                        final FooterNote extract = MobiExtract.extract(fileName,
                                CacheZipUtils.CACHE_BOOK_DIR.getPath(), "temp");
                        fastInput = extract.path;
                    }
                    final long hypT0 = android.os.SystemClock.elapsedRealtime();
                    EpubExtractor.proccessHypens(fastInput, cacheFile.getPath(), null);
                    LOG.bench("mobi-hypens " + (android.os.SystemClock.elapsedRealtime() - hypT0) + "ms");
                    if (fastInput.endsWith("temp_fast.epub")) {
                        new File(fastInput).delete();
                    }
                } else if (fastInput != null) {
                    // 断词关闭：快速产物直接就位（rename 失败极罕见，原转换兜底）
                    if (!new File(fastInput).renameTo(cacheFile)) {
                        LOG.bench("mobi-fastconvert rename failed -> libmobi");
                        final String base = cacheFile.getName();
                        MobiExtract.extract(fileName, CacheZipUtils.CACHE_BOOK_DIR.getPath(),
                                base.substring(0, base.length() - ".epub".length()));
                    }
                } else {
                    // Convert straight into the cached file name (the
                    // extractor appends ".epub"). The old code wrote a
                    // differently-named file, so the cached branch below
                    // never triggered and every open re-converted the book.
                    final String base = cacheFile.getName();
                    MobiExtract.extract(fileName, CacheZipUtils.CACHE_BOOK_DIR.getPath(),
                            base.substring(0, base.length() - ".epub".length()));
                }
            } catch (Exception e) {
                LOG.e(e);
            }
        }
        fileNameEpub = cacheFile.getPath();
        LOG.d("Context", "MobiContext file", fileNameEpub);

        // open through the shared text-chain entry (PdfContext.openTextDoc):
        // the libmobi product IS an epub cache, so when the AI in-page
        // bilingual mode is active for this book the bilingual edition is
        // swapped in here; otherwise this behaves exactly as before
        final MuPdfDocument muPdfDocument = openTextDoc(fileName, fileNameEpub, password);

        final File jsonFile = new File(cacheFile + ".json");
        if (jsonFile.isFile()) {
            muPdfDocument.setFootNotes(JsonHelper.fileToMap(jsonFile));
            LOG.d("Load notes from file", jsonFile);
        } else {

            new Thread("@T mobi set footernotes") {
                @Override
                public void run() {
                    Map<String, String> notes = null;
                    try {
                        notes = EpubExtractor.get().getFooterNotes(fileNameEpub);
                        LOG.d("new file name", fileNameEpub);
                        muPdfDocument.setFootNotes(notes);

                        JsonHelper.mapToFile(jsonFile, notes);
                        LOG.d("save notes to file", jsonFile);

                        removeTempFilesIfCancel();

                    } catch (OutOfMemoryError e) {
                        System.gc();
                        notes = null;
                        LOG.e(e);
                    } catch (Exception e) {
                        notes = null;
                        LOG.e(e);
                    }
                }

                ;
            }.start();
        }


        return muPdfDocument;
    }

}
