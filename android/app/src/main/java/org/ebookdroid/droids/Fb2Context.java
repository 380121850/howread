package org.ebookdroid.droids;

import com.foobnix.android.utils.Dips;
import com.foobnix.android.utils.LOG;
import com.foobnix.ext.CacheZipUtils;
import com.foobnix.ext.Fb2Extractor;
import com.foobnix.model.AppSP;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.JsonHelper;
import com.foobnix.pdf.info.model.BookCSS;

import org.ebookdroid.core.codec.CodecDocument;
import org.ebookdroid.droids.mupdf.codec.MuPdfDocument;
import org.ebookdroid.droids.mupdf.codec.PdfContext;

import java.io.File;
import java.util.Map;

public class Fb2Context extends PdfContext {

    File cacheFile;

    @Override
    public File getCacheFileName(String fileNameOriginal) {
        fileNameOriginal = fileNameOriginal +
                AppState.get().isShowFooterNotesInText +
                BookCSS.get().isAutoHypens +
                AppSP.get().hypenLang +
                AppState.get().isBionicMode +
                AppState.get().enableImageScale +
                AppSP.get().isDouble +
                //AppState.get().isAccurateFontSize +
                BookCSS.get().documentStyle +
                BookCSS.get().isCapitalLetter +
                "fb2split2"; // 切分产物版本：旧单巨章缓存自动失效重建
        cacheFile = new File(CacheZipUtils.CACHE_BOOK_DIR, fileNameOriginal.hashCode() + ".epub");
        return cacheFile;
    }

    MuPdfDocument muPdfDocument;

    @Override
    public CodecDocument openDocumentInner(final String fileName, String password) {
        if (com.foobnix.remote.RemoteBook.isRemotePath(fileName)) {
            // Remote FB2 (round 12): engine-native stream open; the local
            // Fb2Extractor chain (epub + footer notes) needs a real file.
            return openTextDoc(fileName, fileName, password);
        }
        if(cacheFile==null){
            cacheFile = getCacheFileName(fileName);
        }
        String outName = null;

        Map<String, String> notes = null;
        if (AppState.get().isShowFooterNotesInText) {
            notes = getNotes(fileName);

        }

        if (cacheFile.isFile()) {
            outName = cacheFile.getPath();
        } else if (AppState.get().isShowFooterNotesInText) {
            // 脚注注入依赖转换链的脚注抽取：保持同步转换
            outName = cacheFile.getPath();
            Fb2Extractor.get().convert(fileName, outName, false, notes);
            LOG.d("Fb2Context create", fileName, "to", outName);
        } else {
            // 方案 B+A：首开免整本转换等待——引擎 fb2 分章直读源文件即时渲染
            // （与远程 fb2 直开同一引擎流），同时低优先级后台线程做完整转换
            // 入缓存；二次打开命中缓存即用转换产物（目录/脚注更全）。缓存被
            // 清理（上限）时同样回退直读，打开速度不回退。
            outName = fileName;
            LOG.bench("fb2 direct open (bg convert) " + fileName);
            final File bgOut = new File(cacheFile.getPath() + ".tmp");
            final Thread bg = new Thread("@T fb2-bg-convert") {
                @Override public void run() {
                    try {
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                        Fb2Extractor.get().convert(fileName, bgOut.getPath(), false, null);
                        if (bgOut.renameTo(cacheFile)) {
                            LOG.bench("fb2 bg-convert done " + cacheFile.getPath());
                        } else {
                            LOG.bench("fb2 bg-convert rename failed");
                        }
                    } catch (final Throwable t) {
                        LOG.w(t);
                    }
                }
            };
            bg.setPriority(Thread.MIN_PRIORITY);
            bg.start();
        }

        LOG.d("Fb2Context open", outName);

        try {
            muPdfDocument = openTextDoc(fileName, outName, password);
            // Corruption probe: lay out only the first chapter (full count
            // would force the whole-document layout and defeat fast-open).
            muPdfDocument.getPageCountProgressive(Dips.screenWidth(), Dips.screenHeight(),
                    BookCSS.get().fontSizeSp, 1);
        } catch (Exception e) {
            LOG.e(e);
            LOG.d("Fb2Context Fix XML true");
            // recycle the first (probe-failed) document: overwriting the field
            // used to abandon its native store
            try {
                if (muPdfDocument != null) {
                    muPdfDocument.recycle();
                }
            } catch (Exception ignore) {
            }
            muPdfDocument = null;
            if (cacheFile.isFile()) {
                cacheFile.delete();
            }
            // 直开形态 outName 就是源文件本身：convert 先开输出再读输入，
            // 把源文件当输出会把用户的书截断成空壳。重试输出一律改走缓存
            // 路径（后台转换写 .tmp 再改名，互不冲突）
            String retryOut = outName.equals(fileName) ? cacheFile.getPath() : outName;
            Fb2Extractor.get().convert(fileName, retryOut, true, notes);
            LOG.d("Fb2Context create 2", retryOut);
            muPdfDocument = openTextDoc(fileName, retryOut, password);
        }

        if (notes != null) {
            muPdfDocument.setFootNotes(notes);
        } else {
            new Thread("@T fb2 set footnotes") {
                @Override
                public void run() {
                    try {
                        muPdfDocument.setFootNotes(getNotes(fileName));
                        removeTempFilesIfCancel();
                    } catch (Throwable e) {
                        LOG.e(e);
                    }
                }

                ;
            }.start();
        }

        return muPdfDocument;
    }

    public Map<String, String> getNotes(String fileName) {
        Map<String, String> notes = null;
        final File jsonFile = new File(cacheFile + ".json");
        if (jsonFile.isFile()) {
            notes = JsonHelper.fileToMap(jsonFile);
        } else {
            notes = Fb2Extractor.get().getFooterNotes(fileName);
            JsonHelper.mapToFile(jsonFile, notes);
            LOG.d("save notes to file", jsonFile);
        }
        return notes;
    }

}
