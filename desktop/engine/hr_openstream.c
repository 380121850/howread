/* ===== HowRead desktop v0.34.0 - remote block-cache stream open =====
 *
 * Ported from Android Builder/jni/libmupdf-librera.c (RsStream): opens a
 * document from a Java com.artifex.mupdf.fitz.SeekableInputStream so MuPDF
 * reads remote books through the app-side block cache (read/seek per call).
 *
 * Why a dedicated entry instead of Document.openDocument(stream, magic):
 * the upstream glue caches the stream-callback method IDs at library load
 * time (mupdf_native.c mid_* globals), which is not populated in our
 * desktop build - the official path crashes deterministically. Here the
 * method IDs are bound at open time from the passed object; no global
 * cache state involved.
 *
 * Stream callbacks can fire from render threads, so the JNIEnv is attached
 * per call (same discipline as Android). Textually included at the end of
 * mupdf_native.c: needs this TU's statics (get_context,
 * to_Document_safe_own); compiled by desktop/engine CMake only. */

static JavaVM *hr_jvm = NULL;

typedef struct
{
    jobject stream;     /* GlobalRef to the SeekableInputStream */
    jbyteArray array;   /* GlobalRef scratch buffer handed to read() */
    jmethodID mid_read; /* SeekableInputStream.read([B)I */
    jmethodID mid_seek; /* SeekableStream.seek(JI)J */
    jbyte buffer[8192];
} HrStreamState;

static JNIEnv *hr_attach_thread(jboolean *detach)
{
    JNIEnv *env = NULL;
    if (!hr_jvm)
        return NULL;
    *detach = JNI_FALSE;
    if ((*hr_jvm)->GetEnv(hr_jvm, (void **)&env, JNI_VERSION_1_6) == JNI_EDETACHED)
    {
        *detach = JNI_TRUE;
        if ((*hr_jvm)->AttachCurrentThread(hr_jvm, (void **)&env, NULL) != JNI_OK)
            return NULL;
    }
    return env;
}

static void hr_detach_thread(jboolean detach)
{
    if (detach && hr_jvm)
        (*hr_jvm)->DetachCurrentThread(hr_jvm);
}

static int HrStream_next(fz_context *ctx, fz_stream *stm, size_t max)
{
    HrStreamState *state = stm->state;
    jboolean detach = JNI_FALSE;
    JNIEnv *env;
    int n, ch;

    (void)max;
    env = hr_attach_thread(&detach);
    if (env == NULL)
        fz_throw(ctx, FZ_ERROR_GENERIC, "cannot attach to JVM in HrStream_next");

    n = (*env)->CallIntMethod(env, state->stream, state->mid_read, state->array);
    if ((*env)->ExceptionCheck(env))
    {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        hr_detach_thread(detach);
        fz_throw(ctx, FZ_ERROR_GENERIC, "SeekableInputStream.read() failed");
    }

    if (n > 0)
    {
        (*env)->GetByteArrayRegion(env, state->array, 0, n, state->buffer);
        if ((*env)->ExceptionCheck(env))
        {
            (*env)->ExceptionClear(env);
            hr_detach_thread(detach);
            fz_throw(ctx, FZ_ERROR_GENERIC, "GetByteArrayRegion failed");
        }
        /* update stm->pos so fz_tell knows the current position */
        stm->rp = (unsigned char *)state->buffer;
        stm->wp = stm->rp + n;
        stm->pos += n;
        ch = *stm->rp++;
    }
    else if (n < 0)
    {
        ch = EOF;
    }
    else
    {
        hr_detach_thread(detach);
        fz_throw(ctx, FZ_ERROR_GENERIC, "no bytes read");
    }

    hr_detach_thread(detach);
    return ch;
}

static void HrStream_seek(fz_context *ctx, fz_stream *stm, int64_t offset, int whence)
{
    HrStreamState *state = stm->state;
    jboolean detach = JNI_FALSE;
    JNIEnv *env;
    int64_t pos;

    env = hr_attach_thread(&detach);
    if (env == NULL)
        fz_throw(ctx, FZ_ERROR_GENERIC, "cannot attach to JVM in HrStream_seek");

    pos = (*env)->CallLongMethod(env, state->stream, state->mid_seek, offset, whence);
    if ((*env)->ExceptionCheck(env))
    {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        hr_detach_thread(detach);
        fz_throw(ctx, FZ_ERROR_GENERIC, "SeekableStream.seek() failed");
    }

    stm->pos = pos;
    stm->rp = stm->wp = (unsigned char *)state->buffer;

    hr_detach_thread(detach);
}

static void HrStream_drop(fz_context *ctx, void *state_)
{
    HrStreamState *state = state_;
    jboolean detach = JNI_FALSE;
    JNIEnv *env;

    env = hr_attach_thread(&detach);
    if (env == NULL)
    {
        fz_warn(ctx, "cannot attach to JVM in HrStream_drop; leaking stream refs");
        return;
    }

    (*env)->DeleteGlobalRef(env, state->stream);
    (*env)->DeleteGlobalRef(env, state->array);
    free(state);

    hr_detach_thread(detach);
}

/* howread.engine.HROpen.openStreamNative(String magic, SeekableInputStream stream) -> fitz Document */
JNIEXPORT jobject JNICALL
Java_howread_engine_HROpen_openStreamNative(JNIEnv *env, jclass cls, jstring jmagic, jobject jstream)
{
    fz_context *ctx = get_context(env);
    fz_document *doc = NULL;
    fz_stream *docstream = NULL;
    HrStreamState *state;
    jclass clz_in = NULL, clz_seek = NULL;
    jbyteArray array = NULL;
    const char *magic = NULL;

    (void)cls;
    fz_var(doc);
    fz_var(docstream);

    if (!ctx)
        jni_throw_run(env, "no fitz context");   /* macro throws + returns */
    if (!hr_jvm && (*env)->GetJavaVM(env, &hr_jvm) != JNI_OK)
        jni_throw_run(env, "cannot get JavaVM");

    magic = (*env)->GetStringUTFChars(env, jmagic, NULL);
    if (!magic)
        jni_throw_run(env, "cannot get characters in magic string");

    clz_in = (*env)->FindClass(env, "com/artifex/mupdf/fitz/SeekableInputStream");
    if ((*env)->ExceptionCheck(env))
        (*env)->ExceptionClear(env);
    if (clz_in != NULL)
    {
        clz_seek = (*env)->FindClass(env, "com/artifex/mupdf/fitz/SeekableStream");
        if ((*env)->ExceptionCheck(env))
            (*env)->ExceptionClear(env);
    }

    state = (HrStreamState *)calloc(1, sizeof(HrStreamState));
    if (clz_in == NULL || clz_seek == NULL || state == NULL)
    {
        free(state);
        (*env)->ReleaseStringUTFChars(env, jmagic, magic);
        jni_throw_run(env, "cannot find fitz stream classes");
    }

    state->mid_read = (*env)->GetMethodID(env, clz_in, "read", "([B)I");
    state->mid_seek = (*env)->GetMethodID(env, clz_seek, "seek", "(JI)J");
    if (state->mid_read == NULL || state->mid_seek == NULL)
    {
        (*env)->ExceptionClear(env);
        free(state);
        (*env)->ReleaseStringUTFChars(env, jmagic, magic);
        jni_throw_run(env, "cannot bind SeekableInputStream methods");
    }

    state->stream = (*env)->NewGlobalRef(env, jstream);
    array = (*env)->NewByteArray(env, sizeof(state->buffer));
    state->array = array ? (*env)->NewGlobalRef(env, array) : NULL;
    if (state->stream == NULL || array == NULL || state->array == NULL)
    {
        (*env)->ExceptionClear(env);
        if (state->stream) (*env)->DeleteGlobalRef(env, state->stream);
        if (state->array) (*env)->DeleteGlobalRef(env, state->array);
        free(state);
        (*env)->ReleaseStringUTFChars(env, jmagic, magic);
        jni_throw_run(env, "out of memory (stream refs)");
    }

    fz_try(ctx)
    {
        /* the stream owns state; the document keeps its own reference, our
         * initial one is dropped in fz_always (same ownership as upstream) */
        docstream = fz_new_stream(ctx, state, HrStream_next, HrStream_drop);
        docstream->seek = HrStream_seek;

        /* streamed reflowables: defer <img> payloads with explicit w/h so
         * the first layout pass only needs the text (Android openStream) */
        fz_set_defer_html_images(ctx, 1);

        doc = fz_open_accelerated_document_with_stream(ctx, magic, docstream, NULL);
    }
    fz_always(ctx)
    {
        fz_drop_stream(ctx, docstream);
        /* 检视修复：defer 是全局上下文标志，打开完成后复位（mupdf 默认 0），
         * 否则此后所有本地 EPUB/HTML 打开也被永久切换到 defer 模式 */
        fz_set_defer_html_images(ctx, 0);
        if (magic)
        {
            (*env)->ReleaseStringUTFChars(env, jmagic, magic);
            magic = NULL;
        }
    }
    fz_catch(ctx)
    {
        jni_rethrow(env, ctx);
    }

    return to_Document_safe_own(ctx, env, doc);
}
