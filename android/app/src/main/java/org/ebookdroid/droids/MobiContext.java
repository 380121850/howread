package org.ebookdroid.droids;

import com.foobnix.android.utils.LOG;
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

    @Override
    public CodecDocument openDocumentInner(String fileName, String password) {

        LOG.d("Context", "MobiContext", fileName);

        if (!cacheFile.isFile()) {
            try {
                if (BookCSS.get().isAutoHypens) {
                    // Convert to a temporary name first, then rewrite with
                    // hyphenation/replacements into the cached file.
                    FooterNote extract = MobiExtract.extract(fileName, CacheZipUtils.CACHE_BOOK_DIR.getPath(), "temp");
                    EpubExtractor.proccessHypens(extract.path, cacheFile.getPath(), null);
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
