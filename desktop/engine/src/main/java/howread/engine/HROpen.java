package howread.engine;

import com.artifex.mupdf.fitz.Document;
import com.artifex.mupdf.fitz.SeekableInputStream;

/**
 * v0.34.0 远程块缓存流式打开入口（安卓 MuPdfDocument.openStream 同思路）。
 * 引擎从 SeekableInputStream 按需 read/seek（回调可在引擎线程触发），
 * 上层用块缓存会话喂块——任意格式（含 PDF 尾部 xref）无需整本下载即可打开。
 */
public final class HROpen {

    private HROpen() {
    }

    private static volatile boolean ctxReady = false;

    public static Document openStream(String magic, SeekableInputStream stream) {
        EngineLib.INSTANCE.ensure();
        if (!ctxReady) {
            // fitz glue 的 base_context/类引用缓存由 Context 类静态块初始化（static { init(); }）。
            // 任何先走流入口的路径都必须先触发它，否则 get_context 内 jni_throw_* 会拿 NULL
            // 类引用 ThrowNew → jvm.dll 硬崩（v0.33 官方路径崩溃同根因）。
            try {
                Class.forName("com.artifex.mupdf.fitz.Context");
            } catch (ClassNotFoundException e) {
                throw new RuntimeException("fitz Context class missing", e);
            }
            ctxReady = true;
        }
        return openStreamNative(magic, stream);
    }

    private static native Document openStreamNative(String magic, SeekableInputStream stream);
}
