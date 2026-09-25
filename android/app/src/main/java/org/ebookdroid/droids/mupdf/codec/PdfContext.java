package org.ebookdroid.droids.mupdf.codec;

import com.foobnix.ai.BilingualBuilder;
import com.foobnix.ai.TranslationCache;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.model.AppState;

import org.ebookdroid.core.codec.CodecDocument;

import java.io.File;
import java.util.Locale;

public class PdfContext extends MuPdfContext {

    @Override
    public CodecDocument openDocumentInner(String fileName, final String password) {
        MuPdfDocument muPdfDocument = new MuPdfDocument(this, MuPdfDocument.FORMAT_PDF, fileName, password);
        return muPdfDocument;
    }

    /**
     * Open a text-format document (epub/fb2/txt/html chains produce an epub the
     * native MuPDF renders). When the AI in-page bilingual mode is active for
     * this book, first (re)build the bilingual edition from the final path and
     * open that instead, so cached translations render under their source
     * paragraphs. The bilingual file's name embeds a snapshot hash of the
     * translated paragraph set, so reopening after a new translation yields a
     * new file + MuPDF accelerator and shows the updated layout.
     */
    protected MuPdfDocument openTextDoc(String originalFileName, String finalPath, String password) {
        String open = finalPath;
        AppState st = AppState.get();
        if (st.aiBilingual && TxtUtils.isNotEmpty(st.aiBilingualBook)
                && TxtUtils.isNotEmpty(originalFileName)
                && sameBilingualBook(st.aiBilingualBook, originalFileName)
                && TxtUtils.isNotEmpty(finalPath)) {
            // base selection: local chains rewrite their converted epub/html
            // cache; remote books (reached only from the engine's remote
            // pass-through branch, where finalPath IS the remote path) rewrite
            // the fully-cached offline copy assembled by RemoteBilingualBase
            boolean remote = com.foobnix.remote.RemoteBook.isRemotePath(originalFileName);
            File base = null;
            if (remote) {
                if (finalPath.equals(originalFileName)) {
                    base = com.foobnix.remote.RemoteBilingualBase
                            .resolveLocalBaseForBilingual(originalFileName);
                }
            } else if (finalPath.toLowerCase(Locale.US).endsWith(".epub")
                    || finalPath.toLowerCase(Locale.US).endsWith(".html")) {
                base = new File(finalPath);
            }
            if (base != null) {
                try {
                    // publish FIRST (also when ensure has nothing to build
                    // yet): the translation session keys its paragraphs to
                    // this file
                    BilingualBuilder.noteOpenEdition(originalFileName, finalPath);
                    // cache key: the original book for local chains, the local
                    // base for remote books (a remote path is not a real file)
                    File cacheKey = remote ? base : new File(originalFileName);
                    File bi = BilingualBuilder.ensure(new File(originalFileName), base,
                            new TranslationCache(cacheKey), st.aiBilingualSrc, st.aiBilingualTgt);
                    if (bi != null) {
                        LOG.d("openTextDoc bilingual", bi.getPath());
                        android.util.Log.i("BENCH", "openTextDoc bilingual base=" + base.getPath()
                                + " open=" + bi.getPath());
                        open = bi.getPath();
                    }
                } catch (Throwable t) {
                    LOG.e(t);
                }
            }
            android.util.Log.i("BENCH", "openTextDoc bilingual-final open=" + open);
        }
        return new MuPdfDocument(this, MuPdfDocument.FORMAT_PDF, open, password);
    }

    /** aiBilingualBook was saved through File.getPath() (which collapses
     * "remote://a" to "remote:/a") while the open chain carries the canonical
     * form — compare collapse-tolerantly. */
    private static boolean sameBilingualBook(String saved, String path) {
        if (saved.equals(path)) {
            return true;
        }
        return com.foobnix.remote.RemoteBook.fixCollapsed(saved)
                .equals(com.foobnix.remote.RemoteBook.fixCollapsed(path));
    }


}
