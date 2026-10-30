/* HowRead desktop 2026-10-29 (v0.46.0): DJVU 渲染桥（libdjvu-3.5.28 ddjvu API）。
 * 安卓参考 Builder/jni/DjvuDroidBridge.cpp——android/bitmap 输出替换为 jintArray（0xAARRGGBB）；
 * 消息等待改为"轮询 + 终态判定 + 次数上限"（桌面 UI 线程不允许无限阻塞；安卓版
 * while(status < DDJVU_JOB_OK) 在 FAILED 时会死等，这里按 >= DDJVU_JOB_OK 终结）。 */
#include <jni.h>
#include <string.h>
#include <ddjvuapi.h>

#ifdef _WIN32
#include <windows.h>
#define HR_SLEEP(ms) Sleep(ms)
#else
#include <unistd.h>
#define HR_SLEEP(ms) usleep((ms) * 1000)
#endif

#define HR_DJVU_TRIES 1500   /* 1500 x 10ms = 15s 上限 */

/* 泵空消息队列。 */
static void hr_djvu_pump(ddjvu_context_t *ctx)
{
    const ddjvu_message_t *msg;
    while ((msg = ddjvu_message_peek(ctx)) != NULL)
        ddjvu_message_pop(ctx);
}

/* 等待文档解码到终态；返回 status（== DDJVU_JOB_OK 才算成功）。 */
static int hr_djvu_wait_doc(ddjvu_context_t *ctx, ddjvu_document_t *doc)
{
    int i, st = DDJVU_JOB_NOTSTARTED;
    for (i = 0; i < HR_DJVU_TRIES; i++) {
        hr_djvu_pump(ctx);
        st = ddjvu_document_decoding_status(doc);
        if (st >= DDJVU_JOB_OK)
            return st;
        HR_SLEEP(10);
    }
    return st;
}

/* 等待页面解码到终态。 */
static int hr_djvu_wait_page(ddjvu_context_t *ctx, ddjvu_page_t *pg)
{
    int i, st = DDJVU_JOB_NOTSTARTED;
    for (i = 0; i < HR_DJVU_TRIES; i++) {
        hr_djvu_pump(ctx);
        st = ddjvu_page_decoding_status(pg);
        if (st >= DDJVU_JOB_OK)
            return st;
        HR_SLEEP(10);
    }
    return st;
}

JNIEXPORT jlong JNICALL
Java_howread_engine_HrDjvu_nativeCreate(JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    return (jlong) (intptr_t) ddjvu_context_create("HowRead");
}

JNIEXPORT jlong JNICALL
Java_howread_engine_HrDjvu_nativeOpen(JNIEnv *env, jclass clazz, jlong ctxH, jstring jpath)
{
    ddjvu_context_t *ctx = (ddjvu_context_t *) (intptr_t) ctxH;
    const char *path;
    ddjvu_document_t *doc;
    int st;
    (void)clazz;
    if (ctx == NULL || jpath == NULL)
        return 0;
    path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (path == NULL)
        return 0;
    doc = ddjvu_document_create_by_filename_utf8(ctx, path, TRUE);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (doc == NULL)
        return 0;
    st = hr_djvu_wait_doc(ctx, doc);
    if (st != DDJVU_JOB_OK || ddjvu_document_get_pagenum(doc) <= 0) {
        ddjvu_document_release(doc);
        return 0;
    }
    return (jlong) (intptr_t) doc;
}

JNIEXPORT jint JNICALL
Java_howread_engine_HrDjvu_nativePageCount(JNIEnv *env, jclass clazz, jlong docH)
{
    (void)env; (void)clazz;
    return (jint) ddjvu_document_get_pagenum((ddjvu_document_t *) (intptr_t) docH);
}

/* 页面尺寸（旋转后像素），写入 out[0]=w out[1]=h。 */
JNIEXPORT jboolean JNICALL
Java_howread_engine_HrDjvu_nativePageInfo(JNIEnv *env, jclass clazz, jlong ctxH, jlong docH, jint page, jintArray out)
{
    ddjvu_context_t *ctx = (ddjvu_context_t *) (intptr_t) ctxH;
    ddjvu_document_t *doc = (ddjvu_document_t *) (intptr_t) docH;
    ddjvu_page_t *pg;
    jboolean ok = JNI_FALSE;
    (void)clazz;
    if (ctx == NULL || doc == NULL || out == NULL)
        return JNI_FALSE;
    pg = ddjvu_page_create_by_pageno(doc, page);
    if (pg == NULL)
        return JNI_FALSE;
    if (hr_djvu_wait_page(ctx, pg) == DDJVU_JOB_OK) {
        jint dims[2];
        dims[0] = (jint) ddjvu_page_get_width(pg);
        dims[1] = (jint) ddjvu_page_get_height(pg);
        if (dims[0] > 0 && dims[1] > 0) {
            (*env)->SetIntArrayRegion(env, out, 0, 2, dims);
            ok = JNI_TRUE;
        }
    }
    ddjvu_page_release(pg);
    return ok;
}

/* 渲染整页到 buf（targetW x targetH，RGBA 按 masks 打包 → jint 即 0xAARRGGBB）。 */
JNIEXPORT jboolean JNICALL
Java_howread_engine_HrDjvu_nativeRender(JNIEnv *env, jclass clazz, jlong ctxH, jlong docH, jint page,
                                        jint targetW, jint targetH, jintArray buf)
{
    ddjvu_context_t *ctx = (ddjvu_context_t *) (intptr_t) ctxH;
    ddjvu_document_t *doc = (ddjvu_document_t *) (intptr_t) docH;
    ddjvu_page_t *pg;
    jboolean ok = JNI_FALSE;
    (void)clazz;
    if (ctx == NULL || doc == NULL || buf == NULL || targetW <= 0 || targetH <= 0)
        return JNI_FALSE;
    pg = ddjvu_page_create_by_pageno(doc, page);
    if (pg == NULL)
        return JNI_FALSE;
    if (hr_djvu_wait_page(ctx, pg) == DDJVU_JOB_OK) {
        /* 安卓 DjvuDroidBridge 同款 masks（R=0x00FF0000 G=0x0000FF00 B=0x000000FF A=0xFF000000）
         * 小端内存 → jint = 0xAARRGGBB，与 BufferedImage TYPE_INT_RGB 直接对应。 */
        unsigned int masks[4] = { 0x00FF0000, 0x0000FF00, 0x000000FF, 0xFF000000 };
        ddjvu_format_t *fmt = ddjvu_format_create(DDJVU_FORMAT_RGBMASK32, 4, masks);
        if (fmt != NULL) {
            ddjvu_rect_t rect;
            jint n = (*env)->GetArrayLength(env, buf);
            rect.x = 0; rect.y = 0; rect.w = targetW; rect.h = targetH;
            ddjvu_format_set_row_order(fmt, TRUE);
            ddjvu_format_set_y_direction(fmt, TRUE);
            if (n >= (jint) targetW * (jint) targetH) {
                jint *px = (*env)->GetIntArrayElements(env, buf, NULL);
                if (px != NULL) {
                    jboolean r = ddjvu_page_render(pg, DDJVU_RENDER_COLOR, &rect, &rect, fmt,
                                                   targetW * 4, (char *) px);
                    hr_djvu_pump(ctx);
                    (*env)->ReleaseIntArrayElements(env, buf, px, 0);
                    ok = r;
                }
            }
            ddjvu_format_release(fmt);
        }
    }
    ddjvu_page_release(pg);
    return ok;
}

JNIEXPORT void JNICALL
Java_howread_engine_HrDjvu_nativeReleaseDoc(JNIEnv *env, jclass clazz, jlong ctxH, jlong docH)
{
    (void)env; (void)clazz; (void)ctxH;
    ddjvu_document_release((ddjvu_document_t *) (intptr_t) docH);
}

JNIEXPORT void JNICALL
Java_howread_engine_HrDjvu_nativeReleaseCtx(JNIEnv *env, jclass clazz, jlong ctxH)
{
    (void)env; (void)clazz;
    ddjvu_context_release((ddjvu_context_t *) (intptr_t) ctxH);
}
