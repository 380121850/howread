package org.ebookdroid.droids;

import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.ext.CacheZipUtils;
import com.foobnix.hypen.HypenUtils;
import com.foobnix.mobi.parser.IOUtils;
import com.foobnix.model.AppSP;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.model.BookCSS;

import org.ebookdroid.core.codec.CodecDocument;
import org.ebookdroid.droids.mupdf.codec.MuPdfDocument;
import org.ebookdroid.droids.mupdf.codec.PdfContext;
import org.zwobble.mammoth.DocumentConverter;
import org.zwobble.mammoth.Result;
import org.zwobble.mammoth.images.Image;
import org.zwobble.mammoth.images.ImageConverter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class DocxContext extends PdfContext {

    File cacheFile;

    @Override
    public File getCacheFileName(String fileNameOriginal) {
        fileNameOriginal = fileNameOriginal +
                BookCSS.get().isAutoHypens +
                AppSP.get().hypenLang +
                AppSP.get().isDouble +
                AppState.get().isAccurateFontSize +
                BookCSS.get().documentStyle+
                BookCSS.get().isCapitalLetter;
        cacheFile = new File(CacheZipUtils.CACHE_BOOK_DIR, fileNameOriginal.hashCode() + ".html");
        return cacheFile;
    }

    @Override
    public CodecDocument openDocumentInner(String fileName, String password) {
        File src = new File(fileName);
        // a replaced/updated book (e.g. the remote docx fetched anew) must
        // invalidate the derived html — key on the source's mtime, not just
        // the cache file's existence
        if (!cacheFile.isFile() || cacheFile.lastModified() < src.lastModified()) {
            DocumentConverter converter = new DocumentConverter().
                    imageConverter(new ImageConverter.ImgElement() {
                        @Override
                        public Map<String, String> convert(Image image) throws IOException {


                            String imageName = cacheFile.getName() + "+" + image.hashCode() + "." + image.getContentType().replace("image/", "");
                            LOG.d("ImageConverter name", imageName);

                            FileOutputStream out = new FileOutputStream(new File(cacheFile.getParent(), imageName));
                            IOUtils.copyClose(image.getInputStream(), out);


                            Map<String, String> map = new HashMap<>();
                            map.put("src", imageName);
                            return map;
                        }
                    });


            Result<String> result = null;
            try {
                result = converter.convertToHtml(new File(fileName));

                String html = result.getValue();
                html = html.replace("<br /><br />", "<empty-line />");
                if (BookCSS.get().isAutoHypens && TxtUtils.isNotEmpty(AppSP.get().hypenLang)) {
                    LOG.d("docx-isAutoHypens", BookCSS.get().isAutoHypens);
                    HypenUtils.applyLanguage(AppSP.get().hypenLang);
                    HypenUtils.resetTokenizer();
                    html = HypenUtils.applyHypnes(html);
                }

                // write via a temp file + rename: a truncated cache (process
                // killed / disk full) used to be adopted forever after
                File tmpHtml = new File(cacheFile.getParent(), cacheFile.getName() + ".tmp");
                FileOutputStream out = new FileOutputStream(tmpHtml);
                try {
                    out.write("<html><head></head><body>".getBytes());
                    out.write(html.getBytes());
                    out.write("</body></html>".getBytes());
                } finally {
                    out.close();
                }
                if (!tmpHtml.renameTo(cacheFile)) {
                    tmpHtml.delete();
                    throw new IOException("cannot move the converted html into place");
                }

                return openTextDoc(fileName, cacheFile.getPath(), password);

            } catch (IOException e) {
                LOG.e(e);
                return null;
            }

        }
        return openTextDoc(fileName, cacheFile.getPath(), password);


    }
}
