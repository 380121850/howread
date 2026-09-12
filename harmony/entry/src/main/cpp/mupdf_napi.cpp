/*
 * MuPDF NAPI bindings for HarmonyOS.
 *
 * Adapted for MuPDF 1.23.7 API and OHOS NAPI:
 *   version()                      -> FZ_VERSION macro
 *   openDocument(path)             -> fz_open_document
 *   openDocumentByFd(fd)           -> fz_open_file_ptr_no_close
 *   pageCount(handle)              -> fz_count_pages
 *   renderPage(handle, no, zoom)   -> RGBA pixels as ArrayBuffer
 *   renderPageAsync(handle, no, o) -> Promise<RenderResult>, napi_async_worker
 *   getToc(handle)                 -> flat outline list with depth markers
 *   getPageSize(handle, page)      -> native media size in points (0 deg)
 *   getText(handle, page, zoom)    -> text content as UTF-8 string
 *   searchText(handle, text, page) -> array of {x0, y0, x1, y1} rects
 *   getDocumentInfo(handle)        -> object with title/author/creator etc.
 *   closeDocument(handle)          -> explicit drop (finalize also guards)
 *
 * Threading & lifetime:
 * - MuPDF contexts are not thread-safe. All native access is serialized
 *   through g_mu; the async render worker holds it for its whole job.
 * - DocumentHandle is reference-counted: the napi_external holds one
 *   reference, each queued RenderJob holds one. closeDocument only drops
 *   the MuPDF document and marks the handle closed (tombstone); the
 *   context and the handle itself are freed exactly once, when the last
 *   reference is released (GC finalizer or async-job completion).
 * - Every fz_try block follows MuPDF's fz_var() discipline: locals that
 *   are written inside the try and read after a potential longjmp are
 *   protected, so optimized (release -O2) builds stay well-defined.
 */
#include "napi/native_api.h"
#include "mupdf/fitz.h"
#include "mupdf/pdf.h"

#include <atomic>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <map>
#include <pthread.h>
#include <string>
#include <utility>
#include <vector>

namespace {

/* PATH_MAX is not reliably exposed by the OHOS musl headers */
constexpr size_t kMaxPath = 4096;

pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;

struct DocumentHandle {
    fz_context *ctx;          /* valid until the last reference is released */
    fz_document *doc;         /* nullptr once closed */
    std::atomic<int> refs;    /* 1 (napi_external) + N in-flight render jobs */
    std::atomic<bool> closed; /* set by closeDocument / finalizer */
    char customFontPath[512];
    char userCss[4096]; /* Sprint L: accumulated CSS (font-face + user rules) */ /* sprint H3: loaded font path (empty = none) */
};

static void AcquireHandle(DocumentHandle *h)
{
    h->refs.fetch_add(1);
}

/* Drop one reference; on the last reference free the MuPDF resources and
 * the handle itself. This is the ONLY place that deletes a DocumentHandle,
 * so a napi_external can never wrap freed memory after an explicit close. */
static void ReleaseHandle(DocumentHandle *h)
{
    if (h->refs.fetch_sub(1) != 1) {
        return;
    }
    pthread_mutex_lock(&g_mu);
    if (h->doc != nullptr) {
        fz_drop_document(h->ctx, h->doc);
        h->doc = nullptr;
    }
    fz_context *ctx = h->ctx;
    h->ctx = nullptr;
    h->closed = true;
    pthread_mutex_unlock(&g_mu);
    if (ctx != nullptr) {
        fz_drop_context(ctx);
    }
    delete h;
}

void FinalizeDocument(napi_env /*env*/, void *data, void * /*hint*/)
{
    auto *h = static_cast<DocumentHandle *>(data);
    if (h != nullptr) {
        /* Soft-close on GC: stop further use, then drop the external's ref. */
        pthread_mutex_lock(&g_mu);
        if (!h->closed) {
            h->closed = true;
            if (h->doc != nullptr) {
                fz_drop_document(h->ctx, h->doc);
                h->doc = nullptr;
            }
        }
        pthread_mutex_unlock(&g_mu);
        ReleaseHandle(h);
    }
}

napi_value MakeExternal(napi_env env, fz_context *ctx, fz_document *doc)
{
    auto *handle = new DocumentHandle();
    handle->ctx = ctx;
    handle->doc = doc;
    handle->refs = 1; /* the napi_external's reference */
    handle->closed = false;
    memset(handle->customFontPath, 0, sizeof(handle->customFontPath));
    napi_value external;
    napi_create_external(env, handle, FinalizeDocument, nullptr, &external);
    return external;
}

napi_value Version(napi_env env, napi_callback_info /*info*/)
{
    napi_value result;
    napi_create_string_utf8(env, FZ_VERSION, NAPI_AUTO_LENGTH, &result);
    return result;
}

napi_value OpenDocument(napi_env env, napi_callback_info info)
{
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 1) {
        napi_throw_type_error(env, nullptr, "path (string) required");
        return nullptr;
    }

    char path[kMaxPath] = {0};
    size_t pathLen = 0;
    if (napi_get_value_string_utf8(env, args[0], path, sizeof(path), &pathLen) != napi_ok) {
        napi_throw_type_error(env, nullptr, "path must be a string");
        return nullptr;
    }

    fz_context *ctx = fz_new_context(nullptr, nullptr, FZ_STORE_DEFAULT);
    if (ctx == nullptr) {
        napi_throw_error(env, "OOM", "cannot create mupdf context");
        return nullptr;
    }
    fz_register_document_handlers(ctx);

    fz_document *doc = nullptr;
    fz_try(ctx) {
        doc = fz_open_document(ctx, path);
    }
    fz_catch(ctx) {
        fz_drop_context(ctx);
        napi_throw_error(env, "OPEN_FAILED", "cannot open document (unsupported or corrupt file)");
        return nullptr;
    }

    return MakeExternal(env, ctx, doc);
}

DocumentHandle *GetHandle(napi_env env, napi_value value)
{
    DocumentHandle *handle = nullptr;
    if (napi_get_value_external(env, value, reinterpret_cast<void **>(&handle)) != napi_ok || handle == nullptr) {
        napi_throw_type_error(env, nullptr, "invalid document handle");
        return nullptr;
    }
    if (handle->closed) {
        napi_throw_error(env, "DOC_CLOSED", "document already closed");
        return nullptr;
    }
    return handle;
}

napi_value PageCount(napi_env env, napi_callback_info info)
{
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    auto *h = GetHandle(env, args[0]);
    if (h == nullptr) {
        return nullptr;
    }

    int count = 0;
    fz_var(count);
    pthread_mutex_lock(&g_mu);
    if (!fz_setjmp(*fz_push_try(h->ctx))) do {
        count = fz_count_pages(h->ctx, h->doc);
    } while (0);
    if (fz_do_catch(h->ctx)) {
        pthread_mutex_unlock(&g_mu);
        napi_throw_error(env, "COUNT_FAILED", "cannot count pages");
        return nullptr;
    }
    pthread_mutex_unlock(&g_mu);

    napi_value result;
    napi_create_int32(env, count, &result);
    return result;
}

/* Round 6: encrypted-document support. fz_open_document succeeds on encrypted
 * PDFs (structure is readable), but page rendering fails until the document is
 * authenticated. needsPassword(handle) queries the state; authenticateDocument
 * (handle, password) returns the fz_authenticate_password bitmask (0 = fail). */
napi_value NeedsPassword(napi_env env, napi_callback_info info)
{
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    auto *h = GetHandle(env, args[0]);
    if (h == nullptr) {
        return nullptr;
    }
    bool needed = false;
    pthread_mutex_lock(&g_mu);
    needed = fz_needs_password(h->ctx, h->doc) != 0;
    pthread_mutex_unlock(&g_mu);
    napi_value result;
    napi_get_boolean(env, needed, &result);
    return result;
}

napi_value AuthenticateDocument(napi_env env, napi_callback_info info)
{
    size_t argc = 2;
    napi_value args[2];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 2) {
        napi_throw_type_error(env, nullptr, "authenticateDocument(handle, password) required");
        return nullptr;
    }
    auto *h = GetHandle(env, args[0]);
    if (h == nullptr) {
        return nullptr;
    }
    char pwbuf[256];
    size_t pwlen = 0;
    napi_status st = napi_get_value_string_utf8(env, args[1], pwbuf, sizeof(pwbuf), &pwlen);
    if (st != napi_ok) {
        napi_throw_type_error(env, nullptr, "password must be a string");
        return nullptr;
    }

    int auth = 0;
    pthread_mutex_lock(&g_mu);
    auth = fz_authenticate_password(h->ctx, h->doc, pwbuf);
    pthread_mutex_unlock(&g_mu);

    napi_value result;
    napi_create_int32(env, auth, &result);
    return result;
}

napi_value RenderPage(napi_env env, napi_callback_info info)
{
    size_t argc = 3;
    napi_value args[3];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 3) {
        napi_throw_type_error(env, nullptr, "renderPage(handle, pageNumber, zoom) required");
        return nullptr;
    }
    auto *h = GetHandle(env, args[0]);
    if (h == nullptr) {
        return nullptr;
    }
    int32_t pageNumber = 0;
    double zoom = 1.0;
    napi_get_value_int32(env, args[1], &pageNumber);
    napi_get_value_double(env, args[2], &zoom);
    if (zoom <= 0.0 || zoom > 16.0) {
        zoom = 1.0;
    }

    int width = 0;
    int height = 0;
    napi_value dataBuffer = nullptr;
    napi_value result = nullptr;
    bool failed = false;
    fz_pixmap *pix = nullptr;
    fz_var(width);
    fz_var(height);
    fz_var(dataBuffer);
    fz_var(result);
    fz_var(failed);
    fz_var(pix);

    pthread_mutex_lock(&g_mu);
    if (!fz_setjmp(*fz_push_try(h->ctx))) do {
        pix = fz_new_pixmap_from_page_number(h->ctx, h->doc, pageNumber, fz_scale(zoom, zoom),
            fz_device_rgb(h->ctx), 1);
        width = fz_pixmap_width(h->ctx, pix);
        height = fz_pixmap_height(h->ctx, pix);
        int stride = fz_pixmap_stride(h->ctx, pix);

        void *data = nullptr;
        napi_create_arraybuffer(env, static_cast<size_t>(stride) * height, &data, &dataBuffer);
        if (data != nullptr && dataBuffer != nullptr) {
            memcpy(data, fz_pixmap_samples(h->ctx, pix), static_cast<size_t>(stride) * height);
        } else {
            failed = true;
        }
    } while (0);
    if (fz_do_always(h->ctx)) do {
        if (pix != nullptr) {
            fz_drop_pixmap(h->ctx, pix);
        }
    } while (0);
    if (fz_do_catch(h->ctx)) {
        pthread_mutex_unlock(&g_mu);
        napi_throw_error(env, "RENDER_FAILED", "page rendering failed");
        return nullptr;
    }
    pthread_mutex_unlock(&g_mu);

    if (failed || dataBuffer == nullptr) {
        napi_throw_error(env, "OOM", "cannot allocate pixel buffer");
        return nullptr;
    }

    napi_create_object(env, &result);
    napi_value wVal;
    napi_value hVal;
    napi_create_int32(env, width, &wVal);
    napi_create_int32(env, height, &hVal);
    napi_set_named_property(env, result, "width", wVal);
    napi_set_named_property(env, result, "height", hVal);
    napi_set_named_property(env, result, "data", dataBuffer);
    return result;
}

/* ---- renderPageAsync (napi_async_worker + pthread lock) ---- */

struct RenderJob {
    DocumentHandle *h; /* holds a reference for the lifetime of the job */
    int pageNumber;
    double zoom;
    int rotationDeg;
    bool invert;
    /* normalized (0..1) crop rect applied to the final rotated buffer */
    bool hasCrop;
    double cropX0, cropY0, cropX1, cropY1;

    int width = 0;
    int height = 0;
    std::vector<uint8_t> pixels; /* RGBA_8888, stride == width*4 */
    bool failed = false;
    napi_deferred deferred = nullptr;
};

static void RenderJobExecute(napi_env /*env*/, void *data)
{
    auto *job = static_cast<RenderJob *>(data);
    auto *h = job->h;

    fz_pixmap *pix = nullptr;
    uint8_t *out = nullptr;
    size_t outSize = 0;
    fz_var(pix);
    fz_var(out);
    fz_var(outSize);

    pthread_mutex_lock(&g_mu);
    if (h->closed) {
        /* Document was closed while this job was queued. */
        job->failed = true;
    } else if (!fz_setjmp(*fz_push_try(h->ctx))) do {
        pix = fz_new_pixmap_from_page_number(h->ctx, h->doc, job->pageNumber,
            fz_scale(job->zoom, job->zoom), fz_device_rgb(h->ctx), 1);

        int w = fz_pixmap_width(h->ctx, pix);
        int ht = fz_pixmap_height(h->ctx, pix);
        int stride = fz_pixmap_stride(h->ctx, pix);
        const uint8_t *src = fz_pixmap_samples(h->ctx, pix);

        /* rotation/inversion are post-process on the RGBA buffer;
         * buffers come from the MuPDF allocator so failures longjmp
         * into the catch below instead of throwing C++ bad_alloc
         * across the setjmp boundary */
        if (job->rotationDeg == 90 || job->rotationDeg == 270) {
            outSize = static_cast<size_t>(ht) * static_cast<size_t>(w) * 4;
            out = static_cast<uint8_t *>(fz_calloc(h->ctx, outSize, 1));
            for (int y = 0; y < ht; y++) {
                for (int x = 0; x < w; x++) {
                    const uint8_t *s;
                    if (job->rotationDeg == 90) {
                        /* new(x',y') = old(y', w-1-x'); byte offset = y'*stride + (w-1-x')*4 */
                        s = src + static_cast<size_t>(y) * stride + static_cast<size_t>(w - 1 - x) * 4;
                    } else {
                        /* new(x',y') = old(ht-1-y', x'); byte offset = (ht-1-y')*stride + x'*4 */
                        s = src + static_cast<size_t>(ht - 1 - y) * stride + static_cast<size_t>(x) * 4;
                    }
                    uint8_t *d = out + (static_cast<size_t>(y) * w + x) * 4;
                    d[0] = s[0]; d[1] = s[1]; d[2] = s[2]; d[3] = s[3];
                }
            }
            job->width = ht;
            job->height = w;
        } else {
            outSize = static_cast<size_t>(stride) * ht;
            out = static_cast<uint8_t *>(fz_calloc(h->ctx, outSize, 1));
            for (int y = 0; y < ht; y++) {
                int sy = (job->rotationDeg == 180) ? (ht - 1 - y) : y;
                const uint8_t *srow = src + static_cast<size_t>(sy) * stride;
                uint8_t *drow = out + static_cast<size_t>(y) * stride;
                for (int x = 0; x < w; x++) {
                    int sx = (job->rotationDeg == 180) ? (w - 1 - x) : x;
                    drow[x * 4 + 0] = srow[sx * 4 + 0];
                    drow[x * 4 + 1] = srow[sx * 4 + 1];
                    drow[x * 4 + 2] = srow[sx * 4 + 2];
                    drow[x * 4 + 3] = srow[sx * 4 + 3];
                }
            }
            job->width = w;
            job->height = ht;
        }

        if (job->invert) {
            /* invert RGB only — XORing alpha too would make the whole page
             * bitmap fully transparent (night mode showed as a blank page) */
            for (size_t i = 0; i + 3 < outSize; i += 4) {
                out[i] = static_cast<uint8_t>(out[i] ^ 0xFF);
                out[i + 1] = static_cast<uint8_t>(out[i + 1] ^ 0xFF);
                out[i + 2] = static_cast<uint8_t>(out[i + 2] ^ 0xFF);
            }
        }

        if (job->hasCrop) {
            /* crop is applied on the final (rotated) buffer, in normalized 0..1 coords */
            int cw = job->width;
            int cht = job->height;
            int cx0 = static_cast<int>(job->cropX0 * cw);
            int cy0 = static_cast<int>(job->cropY0 * cht);
            int cx1 = static_cast<int>(job->cropX1 * cw);
            int cy1 = static_cast<int>(job->cropY1 * cht);
            if (cx1 <= cx0) { cx1 = cx0 + 1; }
            if (cy1 <= cy0) { cy1 = cy0 + 1; }
            if (cx0 < 0) { cx0 = 0; }
            if (cy0 < 0) { cy0 = 0; }
            if (cx1 > cw) { cx1 = cw; }
            if (cy1 > cht) { cy1 = cht; }
            int cW = cx1 - cx0;
            int cH = cy1 - cy0;
            std::vector<uint8_t> cropped(static_cast<size_t>(cW) * cH * 4);
            for (int y = 0; y < cH; y++) {
                const uint8_t *srow = out + (static_cast<size_t>(cy0 + y) * cw + cx0) * 4;
                uint8_t *drow = cropped.data() + static_cast<size_t>(y) * cW * 4;
                memcpy(drow, srow, static_cast<size_t>(cW) * 4);
            }
            job->pixels.swap(cropped);
            job->width = cW;
            job->height = cH;
        } else {
            job->pixels.assign(out, out + outSize);
        }
    } while (0);
    if (fz_do_always(h->ctx)) do {
        if (out != nullptr) {
            fz_free(h->ctx, out);
        }
        if (pix != nullptr) {
            fz_drop_pixmap(h->ctx, pix);
        }
    } while (0);
    if (fz_do_catch(h->ctx)) {
        job->failed = true;
    }
    pthread_mutex_unlock(&g_mu);
}

static void RenderJobReject(napi_env env, napi_deferred deferred, const char *code)
{
    napi_value e;
    if (napi_create_string_utf8(env, code, NAPI_AUTO_LENGTH, &e) == napi_ok) {
        napi_reject_deferred(env, deferred, e);
    }
}

static void RenderJobComplete(napi_env env, napi_status status, void *data)
{
    auto *job = static_cast<RenderJob *>(data);
    if (job->deferred != nullptr) {
        if (status == napi_cancelled) {
            RenderJobReject(env, job->deferred, "CANCELLED");
        } else if (job->failed || job->pixels.empty()) {
            RenderJobReject(env, job->deferred, "RENDER_FAILED");
        } else {
            napi_value dataBuffer = nullptr;
            void *bufData = nullptr;
            if (napi_create_arraybuffer(env, job->pixels.size(), &bufData, &dataBuffer) == napi_ok &&
                bufData != nullptr) {
                memcpy(bufData, job->pixels.data(), job->pixels.size());
                napi_value result = nullptr;
                napi_value wVal;
                napi_value hVal;
                napi_create_object(env, &result);
                napi_create_int32(env, job->width, &wVal);
                napi_create_int32(env, job->height, &hVal);
                napi_set_named_property(env, result, "width", wVal);
                napi_set_named_property(env, result, "height", hVal);
                napi_set_named_property(env, result, "data", dataBuffer);
                napi_resolve_deferred(env, job->deferred, result);
            } else {
                RenderJobReject(env, job->deferred, "OOM");
            }
        }
    }
    ReleaseHandle(job->h); /* job's reference */
    delete job;
}

napi_value RenderPageAsync(napi_env env, napi_callback_info info)
{
    size_t argc = 3;
    napi_value args[3];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 3) {
        napi_throw_type_error(env, nullptr, "renderPageAsync(handle, pageNumber, options) required");
        return nullptr;
    }
    auto *h = GetHandle(env, args[0]);
    if (h == nullptr) {
        return nullptr;
    }

    int32_t pageNumber = 0;
    napi_get_value_int32(env, args[1], &pageNumber);

    double zoom = 1.0;
    int32_t rot = 0;
    bool inv = false;
    bool hasCrop = false;
    double cropX0 = 0.0, cropY0 = 0.0, cropX1 = 1.0, cropY1 = 1.0;

    /* args[2] is either a plain number (zoom) or an options object { zoom, rotationDeg?, invert?, crop? } */
    napi_valuetype t = napi_undefined;
    if (napi_typeof(env, args[2], &t) == napi_ok && t == napi_number) {
        napi_get_value_double(env, args[2], &zoom);
    } else {
        napi_value v;
        if (napi_get_named_property(env, args[2], "zoom", &v) == napi_ok) {
            napi_valuetype vt = napi_undefined;
            if (napi_typeof(env, v, &vt) == napi_ok && vt == napi_number) {
                napi_get_value_double(env, v, &zoom);
            }
        }
        if (napi_get_named_property(env, args[2], "rotationDeg", &v) == napi_ok) {
            napi_valuetype vt = napi_undefined;
            if (napi_typeof(env, v, &vt) == napi_ok && vt == napi_number) {
                napi_get_value_int32(env, v, &rot);
            }
        }
        rot %= 360;
        if (rot < 0) {
            rot += 360;
        }
        if (rot != 0 && rot != 90 && rot != 180 && rot != 270) {
            napi_throw_type_error(env, nullptr, "rotationDeg must be 0/90/180/270");
            return nullptr;
        }
        if (napi_get_named_property(env, args[2], "invert", &v) == napi_ok) {
            napi_get_value_bool(env, v, &inv);
        }
        if (napi_get_named_property(env, args[2], "crop", &v) == napi_ok) {
            napi_valuetype vt = napi_undefined;
            if (napi_typeof(env, v, &vt) == napi_ok && vt == napi_object) {
                napi_value c;
                if (napi_get_named_property(env, v, "x0", &c) == napi_ok) {
                    napi_get_value_double(env, c, &cropX0);
                }
                if (napi_get_named_property(env, v, "y0", &c) == napi_ok) {
                    napi_get_value_double(env, c, &cropY0);
                }
                if (napi_get_named_property(env, v, "x1", &c) == napi_ok) {
                    napi_get_value_double(env, c, &cropX1);
                }
                if (napi_get_named_property(env, v, "y1", &c) == napi_ok) {
                    napi_get_value_double(env, c, &cropY1);
                }
                if (cropX1 > cropX0 && cropY1 > cropY0) {
                    hasCrop = true;
                }
            }
        }
    }

    if (zoom <= 0.0 || zoom > 16.0) {
        zoom = 1.0;
    }

    auto *job = new RenderJob{h, pageNumber, zoom, rot, inv, hasCrop, cropX0, cropY0, cropX1, cropY1,
        0, 0, {}, false, nullptr};
    AcquireHandle(h); /* job's reference, released in RenderJobComplete */

    napi_value deferredVal = nullptr;
    if (napi_create_promise(env, &job->deferred, &deferredVal) != napi_ok || job->deferred == nullptr) {
        ReleaseHandle(h);
        delete job;
        napi_throw_error(env, "PROMISE_FAILED", "cannot create promise");
        return nullptr;
    }

    napi_async_work work = nullptr;
    napi_value nameVal = nullptr;
    napi_create_string_utf8(env, "mupdf_render_page", NAPI_AUTO_LENGTH, &nameVal);
    if (napi_create_async_work(env, nullptr, nameVal, RenderJobExecute, RenderJobComplete, job, &work) != napi_ok ||
        work == nullptr) {
        RenderJobReject(env, job->deferred, "WORK_FAILED");
        ReleaseHandle(h);
        delete job;
        return deferredVal;
    }
    if (napi_queue_async_work(env, work) != napi_ok) {
        napi_delete_async_work(env, work);
        RenderJobReject(env, job->deferred, "QUEUE_FAILED");
        ReleaseHandle(h);
        delete job;
        return deferredVal;
    }
    return deferredVal;
}

napi_value CloseDocument(napi_env env, napi_callback_info info)
{
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    auto *h = GetHandle(env, args[0]);
    if (h == nullptr) {
        return nullptr;
    }
    /*
     * Drop the MuPDF document and mark the handle closed, but do NOT free
     * the handle: the napi_external still wraps it and its GC finalizer
     * (plus any in-flight render jobs) own references. The handle itself
     * is deleted exactly once in ReleaseHandle when the last ref drops.
     */
    pthread_mutex_lock(&g_mu);
    if (!h->closed) {
        h->closed = true;
        if (h->doc != nullptr) {
            fz_drop_document(h->ctx, h->doc);
            h->doc = nullptr;
        }
    }
    pthread_mutex_unlock(&g_mu);

    napi_value result;
    napi_get_undefined(env, &result);
    return result;
}

/* ---- OpenDocumentByFd ---- */
napi_value OpenDocumentByFd(napi_env env, napi_callback_info info)
{
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 1) {
        napi_throw_type_error(env, nullptr, "fd (int) required");
        return nullptr;
    }

    int32_t fdInt = 0;
    napi_get_value_int32(env, args[0], &fdInt);

    FILE *fp = fdopen(fdInt, "rb");
    if (fp == nullptr) {
        napi_throw_error(env, "FD_OPEN_FAILED", "cannot open fd as FILE*");
        return nullptr;
    }

    fz_context *ctx = fz_new_context(nullptr, nullptr, FZ_STORE_DEFAULT);
    if (ctx == nullptr) {
        fclose(fp);
        napi_throw_error(env, "OOM", "cannot create mupdf context");
        return nullptr;
    }
    fz_register_document_handlers(ctx);

    fz_stream *stm = fz_open_file_ptr_no_close(ctx, fp);
    if (stm == nullptr) {
        fz_drop_context(ctx);
        fclose(fp);
        napi_throw_error(env, "OOM", "cannot create stream from fd");
        return nullptr;
    }

    fz_document *doc = nullptr;
    fz_try(ctx) {
        doc = fz_open_document_with_stream(ctx, nullptr, stm);
    }
    fz_catch(ctx) {
        fz_drop_stream(ctx, stm);
        fz_drop_context(ctx);
        fclose(fp);
        napi_throw_error(env, "OPEN_FAILED", "cannot open document from fd");
        return nullptr;
    }

    return MakeExternal(env, ctx, doc);
}

/* ---- getText ---- */
napi_value GetText(napi_env env, napi_callback_info info)
{
    size_t argc = 3;
    napi_value args[3];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 3) {
        napi_throw_type_error(env, nullptr, "getText(handle, pageNumber, zoom) required");
        return nullptr;
    }
    auto *h = GetHandle(env, args[0]);
    if (h == nullptr) {
        return nullptr;
    }
    int32_t pageNumber = 0;
    double zoom = 1.0;
    napi_get_value_int32(env, args[1], &pageNumber);
    napi_get_value_double(env, args[2], &zoom);
    if (zoom <= 0.0 || zoom > 16.0) {
        zoom = 1.0;
    }

    fz_display_list *dl = nullptr;
    fz_stext_page *stext = nullptr;
    char *text = nullptr;
    napi_value result = nullptr;
    fz_var(dl);
    fz_var(stext);
    fz_var(text);
    fz_var(result);

    pthread_mutex_lock(&g_mu);
    if (!fz_setjmp(*fz_push_try(h->ctx))) do {
        dl = fz_new_display_list_from_page_number(h->ctx, h->doc, pageNumber);
        stext = fz_new_stext_page(h->ctx, fz_infinite_rect);
        fz_stext_options opts;
        memset(&opts, 0, sizeof(opts));
        fz_device *dev = fz_new_stext_device(h->ctx, stext, &opts);
        fz_try(h->ctx) {
            fz_run_display_list(h->ctx, dl, dev, fz_identity, fz_infinite_rect, nullptr);
        }
        fz_always(h->ctx) {
            fz_close_device(h->ctx, dev);
            fz_drop_device(h->ctx, dev);
        }
        fz_catch(h->ctx) {
            fz_rethrow(h->ctx);
        }
        text = fz_copy_rectangle(h->ctx, stext, stext->mediabox, 0);
        if (text != nullptr) {
            napi_create_string_utf8(env, text, strlen(text), &result);
        }
    } while (0);
    if (fz_do_always(h->ctx)) do {
        if (text != nullptr) {
            fz_free(h->ctx, text);
        }
        if (stext != nullptr) {
            fz_drop_stext_page(h->ctx, stext);
        }
        if (dl != nullptr) {
            fz_drop_display_list(h->ctx, dl);
        }
    } while (0);
    if (fz_do_catch(h->ctx)) {
        pthread_mutex_unlock(&g_mu);
        napi_throw_error(env, "TEXT_FAILED", "failed to extract text");
        return nullptr;
    }
    pthread_mutex_unlock(&g_mu);

    if (result == nullptr) {
        napi_throw_error(env, "TEXT_FAILED", "failed to copy text");
        return nullptr;
    }
    return result;
}

/* ---- searchText ---- */
napi_value SearchText(napi_env env, napi_callback_info info)
{
    size_t argc = 3;
    napi_value args[3];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 3) {
        napi_throw_type_error(env, nullptr, "searchText(handle, text, pageNumber) required");
        return nullptr;
    }
    auto *h = GetHandle(env, args[0]);
    if (h == nullptr) {
        return nullptr;
    }

    char pattern[1024] = {0};
    size_t patLen = 0;
    napi_get_value_string_utf8(env, args[1], pattern, sizeof(pattern), &patLen);
    if (patLen == 0 || patLen >= sizeof(pattern)) {
        napi_throw_type_error(env, nullptr, "text (string) required");
        return nullptr;
    }

    int32_t pageNumber = 0;
    napi_get_value_int32(env, args[2], &pageNumber);

    const int capacity = 64;
    fz_quad *hit_bbox = nullptr;
    int hit_count = 0;
    fz_var(hit_bbox);
    fz_var(hit_count);

    pthread_mutex_lock(&g_mu);
    if (!fz_setjmp(*fz_push_try(h->ctx))) do {
        hit_bbox = static_cast<fz_quad *>(fz_calloc(h->ctx, sizeof(fz_quad) * static_cast<size_t>(capacity), 1));
        fz_page *page = fz_load_page(h->ctx, h->doc, pageNumber);
        fz_try(h->ctx) {
            hit_count = fz_search_page(h->ctx, page, pattern, nullptr, hit_bbox, capacity);
        }
        fz_always(h->ctx) {
            fz_drop_page(h->ctx, page);
        }
        fz_catch(h->ctx) {
            fz_rethrow(h->ctx);
        }
    } while (0);
    if (fz_do_always(h->ctx)) do {
        if (hit_bbox != nullptr) {
            fz_free(h->ctx, hit_bbox);
        }
    } while (0);
    if (fz_do_catch(h->ctx)) {
        pthread_mutex_unlock(&g_mu);
        napi_throw_error(env, "SEARCH_FAILED", "search error");
        return nullptr;
    }
    pthread_mutex_unlock(&g_mu);

    if (hit_count > capacity) {
        hit_count = capacity;
    }

    /* Build result array */
    napi_value arr;
    napi_create_array(env, &arr);
    for (int i = 0; i < hit_count; i++) {
        fz_rect r = fz_rect_from_quad(hit_bbox[i]);
        napi_value obj;
        napi_create_object(env, &obj);
        napi_value x0Val, y0Val, x1Val, y1Val;
        napi_create_int64(env, static_cast<int64_t>(r.x0 * 1000), &x0Val);
        napi_create_int64(env, static_cast<int64_t>(r.y0 * 1000), &y0Val);
        napi_create_int64(env, static_cast<int64_t>(r.x1 * 1000), &x1Val);
        napi_create_int64(env, static_cast<int64_t>(r.y1 * 1000), &y1Val);
        napi_set_named_property(env, obj, "x0", x0Val);
        napi_set_named_property(env, obj, "y0", y0Val);
        napi_set_named_property(env, obj, "x1", x1Val);
        napi_set_named_property(env, obj, "y1", y1Val);
        napi_set_element(env, arr, static_cast<size_t>(i), obj);
    }
    return arr;
}

/* ---- searchDocument (sprint O1: whole-book search) ---- */
napi_value SearchDocument(napi_env env, napi_callback_info info)
{
    size_t argc = 2;
    napi_value args[2];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 2) {
        napi_throw_type_error(env, nullptr, "searchDocument(handle, text) required");
        return nullptr;
    }
    auto *h = GetHandle(env, args[0]);
    if (h == nullptr) {
        return nullptr;
    }

    char pattern[1024] = {0};
    size_t patLen = 0;
    napi_get_value_string_utf8(env, args[1], pattern, sizeof(pattern), &patLen);
    if (patLen == 0 || patLen >= sizeof(pattern)) {
        napi_throw_type_error(env, nullptr, "text (string) required");
        return nullptr;
    }

    const int maxTotal = 500;
    int totalHits = 0;
    int pageCount_ = fz_count_pages(h->ctx, h->doc);

    /* Collect per-page hit counts */
    struct PageHit { int page; int count; };
    std::vector<PageHit> hits;

    pthread_mutex_lock(&g_mu);
    for (int p = 0; p < pageCount_ && totalHits < maxTotal; p++) {
        fz_quad *bbox = nullptr;
        fz_var(bbox);
        int count = 0;
        if (!fz_setjmp(*fz_push_try(h->ctx))) do {
            bbox = static_cast<fz_quad *>(fz_calloc(h->ctx, sizeof(fz_quad) * 64, 1));
            fz_page *page = fz_load_page(h->ctx, h->doc, p);
            fz_try(h->ctx) {
                count = fz_search_page(h->ctx, page, pattern, nullptr, bbox, 64);
            }
            fz_always(h->ctx) {
                fz_drop_page(h->ctx, page);
            }
            fz_catch(h->ctx) {
                count = 0;
            }
        } while (0);
        if (fz_do_always(h->ctx)) do {
            if (bbox != nullptr) fz_free(h->ctx, bbox);
        } while (0);
        if (fz_do_catch(h->ctx)) {
            pthread_mutex_unlock(&g_mu);
            napi_throw_error(env, "SEARCH_FAILED", "search error");
            return nullptr;
        }
        if (count > 0) {
            hits.push_back({p, count});
            totalHits += count;
        }
    }
    pthread_mutex_unlock(&g_mu);

    /* Build JSON result */
    std::string json;
    json += "{";
    json += "\"pages\":[";
    for (size_t i = 0; i < hits.size(); i++) {
        if (i > 0) json += ",";
        json += "{\"page\":";
        json += std::to_string(hits[i].page);
        json += ",\"count\":";
        json += std::to_string(hits[i].count);
        json += "}";
    }
    json += "],\"totalHits\":";
    json += std::to_string(totalHits);
    json += "}";
    napi_value result;
    napi_create_string_utf8(env, json.c_str(), NAPI_AUTO_LENGTH, &result);
    return result;
}

/* ---- getDocumentInfo ---- */
napi_value GetDocumentInfo(napi_env env, napi_callback_info info)
{
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    auto *h = GetHandle(env, args[0]);
    if (h == nullptr) {
        return nullptr;
    }

    static const char *kKeys[] = {"title", "author", "subject", "creator", "producer", "creationDate", "modDate"};
    std::vector<std::pair<std::string, std::string>> collected;

    pthread_mutex_lock(&g_mu);
    if (!fz_setjmp(*fz_push_try(h->ctx))) do {
        /* Only PDF documents have a pdf_document: casting h->doc blindly (as
         * we did before) reads past the struct of EPUB/HTML/CBZ documents and
         * segfaults (heap-layout dependent). Guard with pdf_specifics. */
        pdf_document *pdf = pdf_specifics(h->ctx, h->doc);
        if (pdf != nullptr) {
            pdf_obj *meta = pdf_metadata(h->ctx, pdf);
            if (meta != nullptr) {
                for (const char *key : kKeys) {
                    pdf_obj *val = pdf_dict_gets(h->ctx, meta, key);
                    if (val == nullptr || pdf_is_null(h->ctx, val)) {
                        continue;
                    }
                    const char *str = pdf_to_name(h->ctx, val);
                    if (str == nullptr || str[0] == '\0') {
                        str = pdf_to_text_string(h->ctx, val);
                    }
                    if (str != nullptr && str[0] != '\0') {
                        /* copy under the lock: the pdf memory goes away at close */
                        collected.emplace_back(key, str);
                    }
                }
            }
        } else {
            /* generic metadata for reflowable / image documents */
            const char *genKeys[] = {FZ_META_INFO_TITLE, FZ_META_INFO_AUTHOR};
            const char *genNames[] = {"title", "author"};
            char buf[512];
            fz_var(buf);
            for (int i = 0; i < 2; i++) {
                buf[0] = '\0';
                int r = fz_lookup_metadata(h->ctx, h->doc, genKeys[i], buf, sizeof(buf));
                if (r >= 0 && buf[0] != '\0') {
                    collected.emplace_back(genNames[i], buf);
                }
            }
        }
    } while (0);
    if (fz_do_catch(h->ctx)) {
        /* damaged metadata is not fatal: return whatever we collected */
    }
    pthread_mutex_unlock(&g_mu);

    napi_value result;
    napi_create_object(env, &result);
    for (size_t i = 0; i < collected.size(); i++) {
        napi_value v;
        napi_create_string_utf8(env, collected[i].second.c_str(), NAPI_AUTO_LENGTH, &v);
        napi_set_named_property(env, result, collected[i].first.c_str(), v);
    }
    return result;
}

/* ---- getToc ---- */
napi_value GetToc(napi_env env, napi_callback_info info)
{
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    auto *h = GetHandle(env, args[0]);
    if (h == nullptr) {
        return nullptr;
    }

    fz_outline *toc = nullptr;
    fz_var(toc);
    pthread_mutex_lock(&g_mu);
    if (!fz_setjmp(*fz_push_try(h->ctx))) do {
        toc = fz_load_outline(h->ctx, h->doc);
    } while (0);
    if (fz_do_catch(h->ctx)) {
        /* no outline is not an error */
    }
    pthread_mutex_unlock(&g_mu);

    napi_value arr;
    napi_create_array(env, &arr);

    /* DFS to flatten the outline tree (->next / ->down), recording depth per node.
     * Safe outside the lock: the outline is owned by this handle and only this
     * (JS) thread touches it between load and drop. */
    int index = 0;
    struct Frame { fz_outline *o; int depth; };
    std::vector<Frame> stack;
    if (toc != nullptr) {
        stack.push_back({toc, 0});
    }
    while (!stack.empty()) {
        auto frame = stack.back();
        stack.pop_back();

        const char *title = frame.o->title ? frame.o->title : "";
        int32_t page = -1;
        if (frame.o->uri != nullptr && frame.o->uri[0] != '\0') {
            /* uri may be "#page=N" style or a bare number; extract N only if it parses as one */
            const char *p = strstr(frame.o->uri, "page=");
            if (p == nullptr) {
                p = frame.o->uri;
            }
            int v = atoi(p + (p != frame.o->uri ? 5 : 0));
            if (v > 0) {
                page = v - 1;
            }
        }

        napi_value obj, tVal, pVal, dVal;
        napi_create_object(env, &obj);
        napi_create_string_utf8(env, title, NAPI_AUTO_LENGTH, &tVal);
        napi_create_int32(env, page, &pVal);
        napi_create_int32(env, frame.depth, &dVal);
        napi_set_named_property(env, obj, "title", tVal);
        napi_set_named_property(env, obj, "page", pVal);
        napi_set_named_property(env, obj, "depth", dVal);
        napi_set_element(env, arr, static_cast<size_t>(index++), obj);

        /* push children in reverse so the first child is processed next */
        if (frame.o->down != nullptr) {
            std::vector<fz_outline *> children;
            for (fz_outline *c = frame.o->down; c != nullptr; c = c->next) {
                children.push_back(c);
            }
            for (auto it = children.rbegin(); it != children.rend(); ++it) {
                stack.push_back({*it, frame.depth + 1});
            }
        }
    }

    if (toc != nullptr) {
        pthread_mutex_lock(&g_mu);
        fz_drop_outline(h->ctx, toc);
        pthread_mutex_unlock(&g_mu);
    }
    return arr;
}

/* ---- getPageSize ---- */
napi_value GetPageSize(napi_env env, napi_callback_info info)
{
    size_t argc = 2;
    napi_value args[2];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    auto *h = GetHandle(env, args[0]);
    if (h == nullptr) {
        return nullptr;
    }
    int32_t pageNumber = 0;
    napi_get_value_int32(env, args[1], &pageNumber);

    fz_rect media = fz_infinite_rect;
    fz_var(media);
    pthread_mutex_lock(&g_mu);
    if (!fz_setjmp(*fz_push_try(h->ctx))) do {
        fz_page *page = fz_load_page(h->ctx, h->doc, pageNumber);
        fz_try(h->ctx) {
            media = fz_bound_page(h->ctx, page);
        }
        fz_always(h->ctx) {
            fz_drop_page(h->ctx, page);
        }
        fz_catch(h->ctx) {
            fz_rethrow(h->ctx);
        }
    } while (0);
    if (fz_do_catch(h->ctx)) {
        pthread_mutex_unlock(&g_mu);
        napi_throw_error(env, "PAGE_FAILED", "cannot read page bounds");
        return nullptr;
    }
    pthread_mutex_unlock(&g_mu);

    napi_value result, x0Val, y0Val, x1Val, y1Val;
    napi_create_object(env, &result);
    napi_create_int64(env, static_cast<int64_t>(media.x0 * 1000), &x0Val);
    napi_create_int64(env, static_cast<int64_t>(media.y0 * 1000), &y0Val);
    napi_create_int64(env, static_cast<int64_t>(media.x1 * 1000), &x1Val);
    napi_create_int64(env, static_cast<int64_t>(media.y1 * 1000), &y1Val);
    napi_set_named_property(env, result, "x0", x0Val);
    napi_set_named_property(env, result, "y0", y0Val);
    napi_set_named_property(env, result, "x1", x1Val);
    napi_set_named_property(env, result, "y1", y1Val);
    return result;
}

/* ---- layoutDocument (reflow) ---- */
napi_value LayoutDocument(napi_env env, napi_callback_info info)
{
    size_t argc = 5;
    napi_value args[5];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 4) {
        napi_throw_type_error(env, nullptr, "layoutDocument(handle, widthPx, heightPx, em, css?) required");
        return nullptr;
    }
    auto *h = GetHandle(env, args[0]);
    if (h == nullptr) {
        return nullptr;
    }
    double w = 595.0;
    double ht = 842.0;
    double em = 12.0;
    napi_get_value_double(env, args[1], &w);
    napi_get_value_double(env, args[2], &ht);
    napi_get_value_double(env, args[3], &em);
    if (w <= 0.0 || ht <= 0.0 || em <= 0.0) {
        napi_throw_type_error(env, nullptr, "width/height/em must be positive");
        return nullptr;
    }

    /* Optional 5th arg: user CSS (body margin / line-height / font-size).
     * fz_set_user_css affects the context; MuPDF's html/epub docs re-parse
     * with it when layout parameters change, which is exactly the "font,
     * line-height, margin" setting pipeline for reflowable documents. */
    std::string css;
    if (argc >= 5) {
        napi_valuetype t = napi_undefined;
        napi_typeof(env, args[4], &t);
        if (t == napi_string) {
            size_t len = 0;
            napi_get_value_string_utf8(env, args[4], nullptr, 0, &len);
            if (len > 0) {
                css.resize(len);
                napi_get_value_string_utf8(env, args[4], &css[0], len + 1, &len);
            }
        }
    }

    pthread_mutex_lock(&g_mu);
    if (!fz_setjmp(*fz_push_try(h->ctx))) do {
        if (!css.empty()) {
            /* Sprint L3: preserve @font-face from previous loadFont */
            if (h->userCss[0] != '\0') {
                const char *face = strstr(h->userCss, "@font-face");
                if (face) {
                    const char *end = strchr(face, '}');
                    if (end) {
                        size_t faceLen = (size_t)(end - face) + 1;
                        std::string combined(css);
                        combined += " ";
                        combined.append(face, faceLen);
                        fz_set_user_css(h->ctx, combined.c_str());
                        snprintf(h->userCss, sizeof h->userCss, "%s", combined.c_str());
                    } else {
                        fz_set_user_css(h->ctx, css.c_str());
                    }
                } else {
                    fz_set_user_css(h->ctx, css.c_str());
                }
            } else {
                fz_set_user_css(h->ctx, css.c_str());
            }
        }
        /* no-op for fixed-layout docs; reflows HTML/EPUB/TXT docs */
        fz_layout_document(h->ctx, h->doc, static_cast<float>(w), static_cast<float>(ht), static_cast<float>(em));
    } while (0);
    if (fz_do_catch(h->ctx)) {
        pthread_mutex_unlock(&g_mu);
        napi_throw_error(env, "LAYOUT_FAILED", "cannot layout document");
        return nullptr;
    }
    pthread_mutex_unlock(&g_mu);

    napi_value result;
    napi_get_undefined(env, &result);
    return result;
}

/* ---- isReflowable ---- */
napi_value IsReflowable(napi_env env, napi_callback_info info)
{
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 1) {
        napi_throw_type_error(env, nullptr, "isReflowable(handle) required");
        return nullptr;
    }
    auto *h = GetHandle(env, args[0]);
    if (h == nullptr) {
        return nullptr;
    }
    bool reflowable = false;
    pthread_mutex_lock(&g_mu);
    if (!fz_setjmp(*fz_push_try(h->ctx))) do {
        reflowable = fz_is_document_reflowable(h->ctx, h->doc);
    } while (0);
    if (fz_do_catch(h->ctx)) {
        pthread_mutex_unlock(&g_mu);
        napi_throw_error(env, "REFLLOW_FAILED", "cannot query reflowability");
        return nullptr;
    }
    pthread_mutex_unlock(&g_mu);

    napi_value result;
    napi_get_boolean(env, reflowable, &result);
    return result;
}

/* ---- PDF annotations (pdf layer, MuPDF 1.23.7) ---- */

/* Get the pdf_document for a handle, or nullptr (with a JS error) if not PDF. */
static pdf_document *GetPdfDoc(napi_env env, DocumentHandle *h)
{
    pdf_document *pdf = pdf_specifics(h->ctx, h->doc);
    if (pdf == nullptr) {
        napi_throw_error(env, "NOT_PDF", "annotations require a PDF document");
    }
    return pdf;
}

/* Load a pdf_page by number under the lock. Caller drops it. */
static pdf_page *LoadPdfPageLocked(fz_context *ctx, pdf_document *pdf, int pageNumber)
{
    pdf_page *page = nullptr;
    if (!fz_setjmp(*fz_push_try(ctx))) do {
        page = pdf_load_page(ctx, pdf, pageNumber);
    } while (0);
    if (fz_do_catch(ctx)) {
        return nullptr;
    }
    return page;
}

static const char *AnnotTypeName(enum pdf_annot_type t)
{
    switch (t) {
        case PDF_ANNOT_TEXT: return "text";
        case PDF_ANNOT_HIGHLIGHT: return "highlight";
        case PDF_ANNOT_UNDERLINE: return "underline";
        case PDF_ANNOT_STRIKE_OUT: return "strikeout";
        case PDF_ANNOT_INK: return "ink";
        default: return "unknown";
    }
}

/* ---- getAnnotations ---- */
napi_value GetAnnotations(napi_env env, napi_callback_info info)
{
    size_t argc = 2;
    napi_value args[2];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    auto *h = GetHandle(env, args[0]);
    if (h == nullptr) {
        return nullptr;
    }
    int32_t pageNumber = 0;
    napi_get_value_int32(env, args[1], &pageNumber);

    /* page size in points for normalization */
    fz_rect media = fz_infinite_rect;
    pdf_page *page = nullptr;
    pdf_document *pdf = GetPdfDoc(env, h);
    if (pdf == nullptr) {
        return nullptr;
    }

    napi_value arr;
    napi_create_array(env, &arr);

    pthread_mutex_lock(&g_mu);
    if (!fz_setjmp(*fz_push_try(h->ctx))) do {
        page = pdf_load_page(h->ctx, pdf, pageNumber);
        if (page != nullptr) {
            media = fz_bound_page(h->ctx, (fz_page *)page);
        }
    } while (0);
    if (fz_do_catch(h->ctx)) {
        /* ignore: empty result */
    }
    pthread_mutex_unlock(&g_mu);

    float pw = (media.x1 - media.x0) > 0 ? (media.x1 - media.x0) : 1.0f;
    float ph = (media.y1 - media.y0) > 0 ? (media.y1 - media.y0) : 1.0f;
    if (page == nullptr) {
        return arr;
    }

    int index = 0;
    pthread_mutex_lock(&g_mu);
    if (!fz_setjmp(*fz_push_try(h->ctx))) do {
        pdf_annot *annot = pdf_first_annot(h->ctx, page);
        while (annot != nullptr) {
            enum pdf_annot_type t = pdf_annot_type(h->ctx, annot);
            fz_rect r = pdf_bound_annot(h->ctx, annot);
            const char *contents = pdf_annot_contents(h->ctx, annot);

            napi_value obj;
            napi_value v;
            napi_create_object(env, &obj);
            napi_create_int32(env, index, &v);
            napi_set_named_property(env, obj, "index", v);
            napi_create_string_utf8(env, AnnotTypeName(t), NAPI_AUTO_LENGTH, &v);
            napi_set_named_property(env, obj, "type", v);
            napi_create_double(env, (r.x0 - media.x0) / pw, &v);
            napi_set_named_property(env, obj, "x0", v);
            napi_create_double(env, (r.y0 - media.y0) / ph, &v);
            napi_set_named_property(env, obj, "y0", v);
            napi_create_double(env, (r.x1 - media.x0) / pw, &v);
            napi_set_named_property(env, obj, "x1", v);
            napi_create_double(env, (r.y1 - media.y0) / ph, &v);
            napi_set_named_property(env, obj, "y1", v);
            napi_create_string_utf8(env, contents ? contents : "", NAPI_AUTO_LENGTH, &v);
            napi_set_named_property(env, obj, "contents", v);
            napi_set_element(env, arr, static_cast<size_t>(index), obj);

            index++;
            annot = pdf_next_annot(h->ctx, annot);
        }
    } while (0);
    if (fz_do_catch(h->ctx)) {
        /* partial results ok */
    }
    if (page != nullptr) {
        fz_drop_page(h->ctx, (fz_page *)page);
    }
    pthread_mutex_unlock(&g_mu);
    return arr;
}

/* Parse a '#rrggbb' hex color into rgb 0..1 floats. Defaults to yellow. */
static void ParseHexColor(const char *hex, float *rgb)
{
    rgb[0] = 1.0f; rgb[1] = 1.0f; rgb[2] = 0.0f; /* default yellow */
    if (hex == nullptr) {
        return;
    }
    if (hex[0] == '#') {
        hex++;
    }
    int r = 0, g = 0, b = 0;
    if (sscanf(hex, "%2x%2x%2x", &r, &g, &b) == 3) {
        rgb[0] = r / 255.0f;
        rgb[1] = g / 255.0f;
        rgb[2] = b / 255.0f;
    }
}

/* ---- addHighlight ---- */
napi_value AddHighlight(napi_env env, napi_callback_info info)
{
    size_t argc = 7;
    napi_value args[7];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 7) {
        napi_throw_type_error(env, nullptr, "addHighlight(handle, page, x0, y0, x1, y1, color) required");
        return nullptr;
    }
    auto *h = GetHandle(env, args[0]);
    if (h == nullptr) {
        return nullptr;
    }
    int32_t pageNumber = 0;
    double x0 = 0, y0 = 0, x1 = 0, y1 = 0;
    napi_get_value_int32(env, args[1], &pageNumber);
    napi_get_value_double(env, args[2], &x0);
    napi_get_value_double(env, args[3], &y0);
    napi_get_value_double(env, args[4], &x1);
    napi_get_value_double(env, args[5], &y1);
    char color[16] = {0};
    size_t colorLen = 0;
    napi_get_value_string_utf8(env, args[6], color, sizeof(color), &colorLen);

    pdf_document *pdf = GetPdfDoc(env, h);
    if (pdf == nullptr) {
        return nullptr;
    }

    /* page size in points for conversion */
    fz_rect media = fz_infinite_rect;
    pdf_page *page = nullptr;
    pthread_mutex_lock(&g_mu);
    if (!fz_setjmp(*fz_push_try(h->ctx))) do {
        page = pdf_load_page(h->ctx, pdf, pageNumber);
        if (page != nullptr) {
            media = fz_bound_page(h->ctx, (fz_page *)page);
        }
    } while (0);
    if (fz_do_catch(h->ctx)) {
        page = nullptr;
    }
    if (page != nullptr) {
        fz_drop_page(h->ctx, (fz_page *)page);
    }
    pthread_mutex_unlock(&g_mu);

    if (fz_is_infinite_rect(media)) {
        napi_throw_error(env, "PAGE_FAILED", "cannot load page");
        return nullptr;
    }
    float pw = media.x1 - media.x0;
    float ph = media.y1 - media.y0;
    if (pw <= 0.0f || ph <= 0.0f) {
        napi_throw_error(env, "PAGE_FAILED", "bad page size");
        return nullptr;
    }

    /* convert normalized rect to page points */
    fz_rect pr;
    pr.x0 = media.x0 + static_cast<float>(x0) * pw;
    pr.y0 = media.y0 + static_cast<float>(y0) * ph;
    pr.x1 = media.x0 + static_cast<float>(x1) * pw;
    pr.y1 = media.y0 + static_cast<float>(y1) * ph;

    float rgb[3];
    ParseHexColor(color, rgb);

    pthread_mutex_lock(&g_mu);
    if (!fz_setjmp(*fz_push_try(h->ctx))) do {
        pdf_page *pg = pdf_load_page(h->ctx, pdf, pageNumber);
        pdf_annot *annot = pdf_create_annot(h->ctx, pg, PDF_ANNOT_HIGHLIGHT);
        pdf_set_annot_color(h->ctx, annot, 3, rgb);
        /* a quad has 4 corners: bottom-left, bottom-right, top-right, top-left */
        fz_quad q;
        q.ll = fz_make_point(pr.x0, pr.y1);
        q.lr = fz_make_point(pr.x1, pr.y1);
        q.ur = fz_make_point(pr.x1, pr.y0);
        q.ul = fz_make_point(pr.x0, pr.y0);
        pdf_add_annot_quad_point(h->ctx, annot, q);
        pdf_update_annot(h->ctx, annot);
        fz_drop_page(h->ctx, (fz_page *)pg);
    } while (0);
    if (fz_do_catch(h->ctx)) {
        pthread_mutex_unlock(&g_mu);
        napi_throw_error(env, "ANNOT_FAILED", "cannot create highlight");
        return nullptr;
    }
    pthread_mutex_unlock(&g_mu);

    napi_value result;
    napi_get_undefined(env, &result);
    return result;
}

/* ---- addInkStroke ---- */
napi_value AddInkStroke(napi_env env, napi_callback_info info)
{
    size_t argc = 3;
    napi_value args[3];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 3) {
        napi_throw_type_error(env, nullptr, "addInkStroke(handle, page, points) required");
        return nullptr;
    }
    auto *h = GetHandle(env, args[0]);
    if (h == nullptr) {
        return nullptr;
    }
    int32_t pageNumber = 0;
    napi_get_value_int32(env, args[1], &pageNumber);

    pdf_document *pdf = GetPdfDoc(env, h);
    if (pdf == nullptr) {
        return nullptr;
    }

    /* collect normalized points from the array arg (each {x0,y0,x1,y1}) */
    uint32_t n = 0;
    napi_get_array_length(env, args[2], &n);
    if (n == 0) {
        napi_throw_type_error(env, nullptr, "points array required");
        return nullptr;
    }
    std::vector<fz_point> pts;
    for (uint32_t i = 0; i < n; i++) {
        napi_value item;
        napi_get_element(env, args[2], i, &item);
        napi_value cx, cy;
        double px = 0, py = 0;
        if (napi_get_named_property(env, item, "x0", &cx) == napi_ok) {
            napi_get_value_double(env, cx, &px);
        }
        if (napi_get_named_property(env, item, "y0", &cy) == napi_ok) {
            napi_get_value_double(env, cy, &py);
        }
        pts.push_back(fz_make_point(static_cast<float>(px), static_cast<float>(py)));
    }

    /* page size for normalization */
    fz_rect media = fz_infinite_rect;
    pdf_page *page = nullptr;
    pthread_mutex_lock(&g_mu);
    if (!fz_setjmp(*fz_push_try(h->ctx))) do {
        page = pdf_load_page(h->ctx, pdf, pageNumber);
        if (page != nullptr) {
            media = fz_bound_page(h->ctx, (fz_page *)page);
        }
    } while (0);
    if (fz_do_catch(h->ctx)) {
        page = nullptr;
    }
    if (page != nullptr) {
        fz_drop_page(h->ctx, (fz_page *)page);
    }
    pthread_mutex_unlock(&g_mu);

    if (fz_is_infinite_rect(media)) {
        napi_throw_error(env, "PAGE_FAILED", "cannot load page");
        return nullptr;
    }
    float pw = media.x1 - media.x0;
    float ph = media.y1 - media.y0;
    if (pw <= 0.0f || ph <= 0.0f) {
        napi_throw_error(env, "PAGE_FAILED", "bad page size");
        return nullptr;
    }

    pthread_mutex_lock(&g_mu);
    if (!fz_setjmp(*fz_push_try(h->ctx))) do {
        pdf_page *pg = pdf_load_page(h->ctx, pdf, pageNumber);
        pdf_annot *annot = pdf_create_annot(h->ctx, pg, PDF_ANNOT_INK);
        pdf_set_annot_color(h->ctx, annot, 3, (const float[]){0.0f, 0.0f, 0.0f});
        pdf_add_annot_ink_list(h->ctx, annot, static_cast<int>(pts.size()), pts.data());
        pdf_update_annot(h->ctx, annot);
        fz_drop_page(h->ctx, (fz_page *)pg);
    } while (0);
    if (fz_do_catch(h->ctx)) {
        pthread_mutex_unlock(&g_mu);
        napi_throw_error(env, "ANNOT_FAILED", "cannot create ink stroke");
        return nullptr;
    }
    pthread_mutex_unlock(&g_mu);

    napi_value result;
    napi_get_undefined(env, &result);
    return result;
}

/* ---- addMarkupAnnotation: underline / strikeout / squiggly / highlight from a rect list ---- */
napi_value AddMarkupAnnotation(napi_env env, napi_callback_info info)
{
    size_t argc = 5;
    napi_value args[5];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 5) {
        napi_throw_type_error(env, nullptr, "addMarkupAnnotation(handle, page, rects, type, color) required");
        return nullptr;
    }
    auto *h = GetHandle(env, args[0]);
    if (h == nullptr) {
        return nullptr;
    }
    int32_t pageNumber = 0;
    napi_get_value_int32(env, args[1], &pageNumber);
    char type[16] = {0};
    napi_get_value_string_utf8(env, args[3], type, sizeof(type), nullptr);
    char color[16] = {0};
    napi_get_value_string_utf8(env, args[4], color, sizeof(color), nullptr);

    enum pdf_annot_type atype;
    if (strcmp(type, "highlight") == 0) {
        atype = PDF_ANNOT_HIGHLIGHT;
    } else if (strcmp(type, "underline") == 0) {
        atype = PDF_ANNOT_UNDERLINE;
    } else if (strcmp(type, "strikeout") == 0) {
        atype = PDF_ANNOT_STRIKE_OUT;
    } else if (strcmp(type, "squiggly") == 0) {
        atype = PDF_ANNOT_SQUIGGLY;
    } else {
        napi_throw_type_error(env, "BAD_TYPE", "type must be highlight|underline|strikeout|squiggly");
        return nullptr;
    }

    pdf_document *pdf = GetPdfDoc(env, h);
    if (pdf == nullptr) {
        return nullptr;
    }

    /* collect normalized rects from the array arg (each {x0,y0,x1,y1}) */
    uint32_t n = 0;
    napi_get_array_length(env, args[2], &n);
    if (n == 0) {
        napi_throw_type_error(env, nullptr, "rects array required");
        return nullptr;
    }
    std::vector<fz_rect> rects;
    for (uint32_t i = 0; i < n; i++) {
        napi_value item;
        napi_get_element(env, args[2], i, &item);
        double rx0 = 0, ry0 = 0, rx1 = 0, ry1 = 0;
        napi_value v;
        if (napi_get_named_property(env, item, "x0", &v) == napi_ok) napi_get_value_double(env, v, &rx0);
        if (napi_get_named_property(env, item, "y0", &v) == napi_ok) napi_get_value_double(env, v, &ry0);
        if (napi_get_named_property(env, item, "x1", &v) == napi_ok) napi_get_value_double(env, v, &rx1);
        if (napi_get_named_property(env, item, "y1", &v) == napi_ok) napi_get_value_double(env, v, &ry1);
        rects.push_back(fz_make_rect(static_cast<float>(rx0), static_cast<float>(ry0),
            static_cast<float>(rx1), static_cast<float>(ry1)));
    }

    /* page size in points for conversion */
    fz_rect media = fz_infinite_rect;
    pdf_page *page = nullptr;
    pthread_mutex_lock(&g_mu);
    if (!fz_setjmp(*fz_push_try(h->ctx))) do {
        page = pdf_load_page(h->ctx, pdf, pageNumber);
        if (page != nullptr) {
            media = fz_bound_page(h->ctx, (fz_page *)page);
        }
    } while (0);
    if (fz_do_catch(h->ctx)) {
        page = nullptr;
    }
    if (page != nullptr) {
        fz_drop_page(h->ctx, (fz_page *)page);
    }
    pthread_mutex_unlock(&g_mu);

    if (fz_is_infinite_rect(media)) {
        napi_throw_error(env, "PAGE_FAILED", "cannot load page");
        return nullptr;
    }
    float pw = media.x1 - media.x0;
    float ph = media.y1 - media.y0;
    if (pw <= 0.0f || ph <= 0.0f) {
        napi_throw_error(env, "PAGE_FAILED", "bad page size");
        return nullptr;
    }

    /* one quad per rect: bottom-left, bottom-right, top-right, top-left */
    std::vector<fz_quad> quads;
    for (size_t i = 0; i < rects.size(); i++) {
        fz_rect pr = rects[i];
        pr.x0 = media.x0 + pr.x0 * pw;
        pr.y0 = media.y0 + pr.y0 * ph;
        pr.x1 = media.x0 + pr.x1 * pw;
        pr.y1 = media.y0 + pr.y1 * ph;
        fz_quad q;
        q.ll = fz_make_point(pr.x0, pr.y1);
        q.lr = fz_make_point(pr.x1, pr.y1);
        q.ur = fz_make_point(pr.x1, pr.y0);
        q.ul = fz_make_point(pr.x0, pr.y0);
        quads.push_back(q);
    }

    float rgb[3];
    ParseHexColor(color, rgb);

    pthread_mutex_lock(&g_mu);
    if (!fz_setjmp(*fz_push_try(h->ctx))) do {
        pdf_page *pg = pdf_load_page(h->ctx, pdf, pageNumber);
        pdf_annot *annot = pdf_create_annot(h->ctx, pg, atype);
        pdf_set_annot_color(h->ctx, annot, 3, rgb);
        /* Android parity: translucent highlight, opaque line markups */
        pdf_set_annot_opacity(h->ctx, annot, atype == PDF_ANNOT_HIGHLIGHT ? 0.4f : 1.0f);
        pdf_set_annot_quad_points(h->ctx, annot, static_cast<int>(quads.size()), quads.data());
        pdf_update_annot(h->ctx, annot);
        fz_drop_page(h->ctx, (fz_page *)pg);
    } while (0);
    if (fz_do_catch(h->ctx)) {
        pthread_mutex_unlock(&g_mu);
        napi_throw_error(env, "ANNOT_FAILED", "cannot create markup annotation");
        return nullptr;
    }
    pthread_mutex_unlock(&g_mu);

    napi_value result;
    napi_get_undefined(env, &result);
    return result;
}

/* ---- addTextNote: a PDF text annotation (sticky note icon) at a normalized point ---- */
napi_value AddTextNote(napi_env env, napi_callback_info info)
{
    size_t argc = 6;
    napi_value args[6];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 6) {
        napi_throw_type_error(env, nullptr, "addTextNote(handle, page, x, y, text, color) required");
        return nullptr;
    }
    auto *h = GetHandle(env, args[0]);
    if (h == nullptr) {
        return nullptr;
    }
    int32_t pageNumber = 0;
    double x = 0, y = 0;
    napi_get_value_int32(env, args[1], &pageNumber);
    napi_get_value_double(env, args[2], &x);
    napi_get_value_double(env, args[3], &y);
    char note[2048] = {0};
    napi_get_value_string_utf8(env, args[4], note, sizeof(note) - 1, nullptr);
    char color[16] = {0};
    napi_get_value_string_utf8(env, args[5], color, sizeof(color), nullptr);

    pdf_document *pdf = GetPdfDoc(env, h);
    if (pdf == nullptr) {
        return nullptr;
    }

    fz_rect media = fz_infinite_rect;
    pdf_page *page = nullptr;
    pthread_mutex_lock(&g_mu);
    if (!fz_setjmp(*fz_push_try(h->ctx))) do {
        page = pdf_load_page(h->ctx, pdf, pageNumber);
        if (page != nullptr) {
            media = fz_bound_page(h->ctx, (fz_page *)page);
        }
    } while (0);
    if (fz_do_catch(h->ctx)) {
        page = nullptr;
    }
    if (page != nullptr) {
        fz_drop_page(h->ctx, (fz_page *)page);
    }
    pthread_mutex_unlock(&g_mu);

    if (fz_is_infinite_rect(media)) {
        napi_throw_error(env, "PAGE_FAILED", "cannot load page");
        return nullptr;
    }
    float pw = media.x1 - media.x0;
    float ph = media.y1 - media.y0;
    if (pw <= 0.0f || ph <= 0.0f) {
        napi_throw_error(env, "PAGE_FAILED", "bad page size");
        return nullptr;
    }

    /* anchor point + 24pt icon rect (Android addTextNoteInternal pattern) */
    float ux = media.x0 + static_cast<float>(x) * pw;
    float uy = media.y0 + static_cast<float>(y) * ph;
    float iconSize = 24.0f;
    fz_rect r = fz_make_rect(ux, uy - iconSize, ux + iconSize, uy);

    float rgb[3];
    ParseHexColor(color, rgb);

    pthread_mutex_lock(&g_mu);
    if (!fz_setjmp(*fz_push_try(h->ctx))) do {
        pdf_page *pg = pdf_load_page(h->ctx, pdf, pageNumber);
        pdf_annot *annot = pdf_create_annot(h->ctx, pg, PDF_ANNOT_TEXT);
        pdf_set_annot_rect(h->ctx, annot, r);
        if (note[0] != '\0') {
            pdf_set_annot_contents(h->ctx, annot, note);
        }
        pdf_set_annot_color(h->ctx, annot, 3, rgb);
        pdf_update_annot(h->ctx, annot);
        fz_drop_page(h->ctx, (fz_page *)pg);
    } while (0);
    if (fz_do_catch(h->ctx)) {
        pthread_mutex_unlock(&g_mu);
        napi_throw_error(env, "ANNOT_FAILED", "cannot create text note");
        return nullptr;
    }
    pthread_mutex_unlock(&g_mu);

    napi_value result;
    napi_get_undefined(env, &result);
    return result;
}

/* ---- deleteAnnotation ---- */
napi_value DeleteAnnotation(napi_env env, napi_callback_info info)
{
    size_t argc = 3;
    napi_value args[3];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 3) {
        napi_throw_type_error(env, nullptr, "deleteAnnotation(handle, page, index) required");
        return nullptr;
    }
    auto *h = GetHandle(env, args[0]);
    if (h == nullptr) {
        return nullptr;
    }
    int32_t pageNumber = 0;
    int32_t index = 0;
    napi_get_value_int32(env, args[1], &pageNumber);
    napi_get_value_int32(env, args[2], &index);

    pdf_document *pdf = GetPdfDoc(env, h);
    if (pdf == nullptr) {
        return nullptr;
    }

    bool deleted = false;
    pthread_mutex_lock(&g_mu);
    if (!fz_setjmp(*fz_push_try(h->ctx))) do {
        pdf_page *pg = pdf_load_page(h->ctx, pdf, pageNumber);
        int i = 0;
        pdf_annot *annot = pdf_first_annot(h->ctx, pg);
        while (annot != nullptr) {
            if (i == index) {
                pdf_delete_annot(h->ctx, pg, annot);
                deleted = true;
                break;
            }
            i++;
            annot = pdf_next_annot(h->ctx, annot);
        }
        fz_drop_page(h->ctx, (fz_page *)pg);
    } while (0);
    if (fz_do_catch(h->ctx)) {
        deleted = false;
    }
    pthread_mutex_unlock(&g_mu);

    if (!deleted) {
        napi_throw_error(env, "ANNOT_NOT_FOUND", "annotation index out of range");
        return nullptr;
    }
    napi_value result;
    napi_get_undefined(env, &result);
    return result;
}

/* ---- saveDocument ---- */
napi_value SaveDocument(napi_env env, napi_callback_info info)
{
    size_t argc = 2;
    napi_value args[2];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 2) {
        napi_throw_type_error(env, nullptr, "saveDocument(handle, path) required");
        return nullptr;
    }
    auto *h = GetHandle(env, args[0]);
    if (h == nullptr) {
        return nullptr;
    }
    char path[kMaxPath] = {0};
    size_t pathLen = 0;
    napi_get_value_string_utf8(env, args[1], path, sizeof(path), &pathLen);
    if (pathLen == 0) {
        napi_throw_type_error(env, nullptr, "path required");
        return nullptr;
    }

    pdf_document *pdf = GetPdfDoc(env, h);
    if (pdf == nullptr) {
        return nullptr;
    }

    pthread_mutex_lock(&g_mu);
    if (!fz_setjmp(*fz_push_try(h->ctx))) do {
        pdf_save_document(h->ctx, pdf, path, &pdf_default_write_options);
    } while (0);
    if (fz_do_catch(h->ctx)) {
        pthread_mutex_unlock(&g_mu);
        napi_throw_error(env, "SAVE_FAILED", "cannot save document");
        return nullptr;
    }
    pthread_mutex_unlock(&g_mu);

    napi_value result;
    napi_get_undefined(env, &result);
    return result;
}


/* Append one unicode code point to buf as JSON-escaped UTF-8.
 * Returns the new offset, or -1 when the buffer is full. */
static int AppendJsonChar(char *buf, int bufSize, int offset, int c)
{
    char tmp[8];
    int n = 0;
    if (c == '"' || c == '\\') {
        tmp[n++] = '\\';
        tmp[n++] = (char)c;
    } else if (c == '\n') {
        tmp[n++] = '\\'; tmp[n++] = 'n';
    } else if (c == '\r') {
        tmp[n++] = '\\'; tmp[n++] = 'r';
    } else if (c == '\t') {
        tmp[n++] = '\\'; tmp[n++] = 't';
    } else if (c < 0x20) {
        n = snprintf(tmp, sizeof(tmp), "\\u%04x", c);
    } else if (c < 0x80) {
        tmp[n++] = (char)c;
    } else if (c < 0x800) {
        tmp[n++] = (char)(0xC0 | (c >> 6));
        tmp[n++] = (char)(0x80 | (c & 0x3F));
    } else if (c < 0x10000) {
        tmp[n++] = (char)(0xE0 | (c >> 12));
        tmp[n++] = (char)(0x80 | ((c >> 6) & 0x3F));
        tmp[n++] = (char)(0x80 | (c & 0x3F));
    } else {
        tmp[n++] = (char)(0xF0 | (c >> 18));
        tmp[n++] = (char)(0x80 | ((c >> 12) & 0x3F));
        tmp[n++] = (char)(0x80 | ((c >> 6) & 0x3F));
        tmp[n++] = (char)(0x80 | (c & 0x3F));
    }
    if (offset + n >= bufSize) {
        return -1;
    }
    memcpy(buf + offset, tmp, n);
    return offset + n;
}

/* ---- getTextRects: line-level text boxes with line text + per-char x bounds ---- */
napi_value GetTextRects(napi_env env, napi_callback_info info)
{
    size_t argc = 2;
    napi_value args[2];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 2) {
        napi_throw_type_error(env, nullptr, "getTextRects(handle, pageNumber) required");
        return nullptr;
    }
    auto *h = GetHandle(env, args[0]);
    if (h == nullptr) {
        return nullptr;
    }
    int32_t pageNumber = 0;
    napi_get_value_int32(env, args[1], &pageNumber);

    fz_display_list *dl = nullptr;
    fz_stext_page *stext = nullptr;
    napi_value result = nullptr;
    fz_var(dl);
    fz_var(stext);
    fz_var(result);

    pthread_mutex_lock(&g_mu);
    if (!fz_setjmp(*fz_push_try(h->ctx))) do {
        dl = fz_new_display_list_from_page_number(h->ctx, h->doc, pageNumber);
        stext = fz_new_stext_page(h->ctx, fz_infinite_rect);
        fz_stext_options opts;
        memset(&opts, 0, sizeof(opts));
        opts.flags = FZ_STEXT_PRESERVE_WHITESPACE | FZ_STEXT_MEDIABOX_CLIP;
        fz_device *dev = fz_new_stext_device(h->ctx, stext, &opts);
        fz_try(h->ctx) {
            fz_run_display_list(h->ctx, dl, dev, fz_identity, fz_infinite_rect, nullptr);
        }
        fz_always(h->ctx) {
            fz_close_device(h->ctx, dev);
            fz_drop_device(h->ctx, dev);
        }
        fz_catch(h->ctx) {
            fz_rethrow(h->ctx);
        }

        /* Page dimensions from mediabox (fz_rect, not fz_irect) */
        float pw = stext->mediabox.x1 - stext->mediabox.x0;
        float ph = stext->mediabox.y1 - stext->mediabox.y0;
        if (pw <= 0.0f) pw = 612.0f;
        if (ph <= 0.0f) ph = 792.0f;

        /* Count total text lines and chars for buffer sizing */
        size_t lineCount = 0;
        size_t charCount = 0;
        for (const fz_stext_block *block = stext->first_block; block != nullptr; block = block->next) {
            if (block->type != FZ_STEXT_BLOCK_TEXT) continue;
            for (const fz_stext_line *line = block->u.t.first_line; line != nullptr; line = line->next) {
                lineCount++;
                for (const fz_stext_char *ch = line->first_char; ch != nullptr; ch = ch->next) {
                    charCount++;
                }
            }
        }

        /* Build JSON array: [{x0,y0,x1,y1,text,chars:[x0,x1,...]},...] normalized 0..1 */
        size_t bufSize = 256 + lineCount * 160 + charCount * 32;
        char *json = static_cast<char *>(fz_calloc(h->ctx, bufSize, 1));
        if (json == nullptr) break;

        int offset = snprintf(json, bufSize, "[");
        bool first = true;
        for (const fz_stext_block *block = stext->first_block; block != nullptr && offset < (int)(bufSize - 128); block = block->next) {
            if (block->type != FZ_STEXT_BLOCK_TEXT) continue;
            for (const fz_stext_line *line = block->u.t.first_line; line != nullptr && offset < (int)(bufSize - 128); line = line->next) {
                if (!first) json[offset++] = ',';
                first = false;
                float x0 = (line->bbox.x0 - stext->mediabox.x0) / pw;
                float y0 = (line->bbox.y0 - stext->mediabox.y0) / ph;
                float x1 = (line->bbox.x1 - stext->mediabox.x0) / pw;
                float y1 = (line->bbox.y1 - stext->mediabox.y0) / ph;
                if (x0 < 0.0f) x0 = 0.0f; if (x1 > 1.0f) x1 = 1.0f;
                if (y0 < 0.0f) y0 = 0.0f; if (y1 > 1.0f) y1 = 1.0f;
                int n = snprintf(json + offset, 100,
                    "{\"x0\":%.4f,\"y0\":%.4f,\"x1\":%.4f,\"y1\":%.4f,\"text\":\"",
                    x0, y0, x1, y1);
                if (n > 0) offset += n;
                for (const fz_stext_char *ch = line->first_char; ch != nullptr; ch = ch->next) {
                    offset = AppendJsonChar(json, (int)bufSize, offset, ch->c);
                    if (offset < 0) break;
                }
                if (offset < 0) break;
                if (offset + 16 >= (int)bufSize) break;
                json[offset++] = '"';
                json[offset++] = ',';
                json[offset++] = '"';
                json[offset++] = 'c';
                json[offset++] = 'h';
                json[offset++] = 'a';
                json[offset++] = 'r';
                json[offset++] = 's';
                json[offset++] = '"';
                json[offset++] = ':';
                json[offset++] = '[';
                for (const fz_stext_char *ch = line->first_char; ch != nullptr; ch = ch->next) {
                    float cx0 = ch->quad.ll.x, cx1 = ch->quad.lr.x;
                    if (ch->quad.ul.x < cx0) cx0 = ch->quad.ul.x;
                    if (ch->quad.ur.x > cx1) cx1 = ch->quad.ur.x;
                    cx0 = (cx0 - stext->mediabox.x0) / pw;
                    cx1 = (cx1 - stext->mediabox.x0) / pw;
                    if (cx0 < 0.0f) cx0 = 0.0f;
                    if (cx1 > 1.0f) cx1 = 1.0f;
                    n = snprintf(json + offset, 32, "%.4f,%.4f,", cx0, cx1);
                    if (n > 0) offset += n;
                    if (offset + 16 >= (int)bufSize) break;
                }
                if (offset > 0 && json[offset - 1] == ',') offset--;
                json[offset++] = ']';
                json[offset++] = '}';
            }
        }
        json[offset++] = ']';
        json[offset] = '\0';

        napi_create_string_utf8(env, json, strlen(json), &result);
        fz_free(h->ctx, json);
    } while (0);
    if (fz_do_always(h->ctx)) do {
        if (stext != nullptr) fz_drop_stext_page(h->ctx, stext);
        if (dl != nullptr) fz_drop_display_list(h->ctx, dl);
    } while (0);
    if (fz_do_catch(h->ctx)) {
        pthread_mutex_unlock(&g_mu);
        napi_throw_error(env, "TEXT_RECTS_FAILED", "failed to extract text rects");
        return nullptr;
    }
    pthread_mutex_unlock(&g_mu);

    if (result == nullptr) {
        napi_create_string_utf8(env, "[]", 2, &result);
    }
    return result;
}

/* ---- loadFont (sprint H3): register a custom font file for reflowable docs ---- */
napi_value LoadFont(napi_env env, napi_callback_info info)
{
    size_t argc = 2;
    napi_value args[2];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 2) {
        napi_throw_type_error(env, nullptr, "loadFont(handle, fontPath) required");
        return nullptr;
    }
    auto *h = GetHandle(env, args[0]);
    if (h == nullptr) {
        return nullptr;
    }
    char pathBuf[512] = {0};
    size_t len = 0;
    napi_get_value_string_utf8(env, args[1], pathBuf, sizeof(pathBuf) - 1, &len);
    if (len == 0) {
        napi_throw_type_error(env, nullptr, "loadFont: empty font path");
        return nullptr;
    }

    fz_var(h->customFontPath);
    pthread_mutex_lock(&g_mu);
    bool ok = false;
    if (!fz_setjmp(*fz_push_try(h->ctx))) do {
        /* Store the font path for use in CSS @font-face injection */
        strncpy(h->customFontPath, pathBuf, sizeof(h->customFontPath) - 1);
        h->customFontPath[sizeof(h->customFontPath) - 1] = '\0';

        /* For reflowable documents, inject a CSS rule referencing the font file.
         * MuPDF's fz_set_user_css can include @font-face with local() src. */
        if (fz_is_document_reflowable(h->ctx, h->doc)) {
            char cssBuf[1024];
            snprintf(cssBuf, sizeof(cssBuf),
                "@font-face { font-family: 'custom'; src: url('%s'); } "
                "body { font-family: 'custom', serif; }", pathBuf);
            /* Sprint L3: accumulate into userCss buffer */
            if (h->userCss[0] != '\0') {
                char *oldFace = strstr(h->userCss, "@font-face");
                if (oldFace) {
                    size_t keepLen = (size_t)(oldFace - h->userCss);
                    memcpy(h->userCss + keepLen, cssBuf, strlen(cssBuf) + 1);
                } else {
                    strncat(h->userCss, " ", sizeof(h->userCss) - strlen(h->userCss) - 1);
                    strncat(h->userCss, cssBuf, sizeof(h->userCss) - strlen(h->userCss) - 1);
                }
            } else {
                strncpy(h->userCss, cssBuf, sizeof(h->userCss) - 1);
            }
            fz_set_user_css(h->ctx, h->userCss);
            ok = true;
        } else {
            /* PDF: store for potential future use (MuPDF doesn't support
             * runtime font substitution in PDFs without full re-render) */
            ok = false;
        }
    } while (0);
    if (fz_do_always(h->ctx)) do {
    } while (0);
    if (fz_do_catch(h->ctx)) {
        pthread_mutex_unlock(&g_mu);
        napi_throw_error(env, "FONT_FAILED", "failed to load font");
        return nullptr;
    }
    pthread_mutex_unlock(&g_mu);

    napi_value boolResult;
    napi_get_boolean(env, ok, &boolResult);
    return boolResult;
}

/* ====================================================================
 * Remote document streaming (online cache reading).
 *
 * Port of Android's RemoteSeekableStream + MuPdfDocument.openRemoteFile:
 * MuPDF opens the document over a custom fz_stream whose next/seek
 * callbacks pull bytes through a block cache living in ArkTS. The C++
 * side bridges to ArkTS with a napi_threadsafe_function; each read
 * posts (seq, offset, len) to the JS dispatcher, which fetches the
 * range (block cache + HTTP Range) and reports back via remoteReadDone.
 *
 * Threading:
 * - The stream callbacks run on MuPDF worker threads (async open /
 *   async render) and BLOCK on a condvar until the JS side answers.
 *   This is safe because every entry point that can trigger stream
 *   reads runs on a napi_async_work worker, so the JS thread stays
 *   free to service the threadsafe-function callbacks.
 * - Remote handles MUST NOT be passed to the synchronous document
 *   accessors (pageCount/getToc/...): those run on the JS thread and
 *   would self-deadlock waiting for their own reads. Use docOpAsync.
 * - The RemoteReader is refcounted: the registry holds one reference,
 *   every fz_stream holds one. unregisterRemoteReader marks it closed
 *   and wakes pending reads; the struct is freed when the last
 *   reference goes away.
 * ==================================================================== */

constexpr size_t kRemoteStreamBuf = 64 * 1024;
constexpr int kRemoteReadTimeoutSec = 60;

struct RemotePending {
    uint64_t seq;
    std::vector<uint8_t> data;
    bool done;
    bool failed;
};

struct RemoteReader {
    int64_t id;
    napi_threadsafe_function tsfn;
    pthread_mutex_t mu;
    pthread_cond_t cv;
    std::map<uint64_t, RemotePending *> pending;
    uint64_t nextSeq;
    int refs;    /* guarded by mu: registry + one per fz_stream */
    bool closed; /* guarded by mu */
};

static pthread_mutex_t g_remoteMu = PTHREAD_MUTEX_INITIALIZER;
static std::map<int64_t, RemoteReader *> g_remoteReaders;
static int64_t g_remoteNextId = 1;

struct RemoteReadRequest {
    uint64_t seq;
    int64_t offset;
    size_t len;
};

/* JS-thread callback: invoke the ArkTS dispatcher (seq, offset, len).
 * The dispatcher fetches the range asynchronously and reports the
 * result back through remoteReadDone(id, seq, buffer). */
static void RemoteReadCallJs(napi_env env, napi_value jsCb, void * /*context*/, void *data)
{
    auto *req = static_cast<RemoteReadRequest *>(data);
    if (env != nullptr && jsCb != nullptr && req != nullptr) {
        napi_value undefinedVal = nullptr;
        napi_value args[3];
        napi_get_undefined(env, &undefinedVal);
        napi_create_int64(env, static_cast<int64_t>(req->seq), &args[0]);
        napi_create_int64(env, req->offset, &args[1]);
        napi_create_int64(env, static_cast<int64_t>(req->len), &args[2]);
        napi_call_function(env, undefinedVal, jsCb, 3, args, nullptr);
    }
    delete req;
}

static void RemoteReaderWakeAll(RemoteReader *r)
{
    /* caller holds r->mu */
    for (std::map<uint64_t, RemotePending *>::iterator it = r->pending.begin(); it != r->pending.end(); ++it) {
        it->second->failed = true;
        it->second->done = true;
    }
    pthread_cond_broadcast(&r->cv);
}

static void RemoteReaderRetain(RemoteReader *r)
{
    pthread_mutex_lock(&r->mu);
    r->refs++;
    pthread_mutex_unlock(&r->mu);
}

static void RemoteReaderRelease(RemoteReader *r)
{
    bool del = false;
    pthread_mutex_lock(&r->mu);
    r->refs--;
    if (r->refs <= 0 && r->closed) {
        del = true;
    }
    pthread_mutex_unlock(&r->mu);
    if (del) {
        napi_release_threadsafe_function(r->tsfn, napi_tsfn_release);
        pthread_mutex_destroy(&r->mu);
        pthread_cond_destroy(&r->cv);
        delete r;
    }
}

/* Register the ArkTS dispatcher. Returns the new reader id. */
napi_value RegisterRemoteReader(napi_env env, napi_callback_info info)
{
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 1) {
        napi_throw_type_error(env, nullptr, "dispatcher (function) required");
        return nullptr;
    }
    napi_valuetype t = napi_undefined;
    napi_typeof(env, args[0], &t);
    if (t != napi_function) {
        napi_throw_type_error(env, nullptr, "dispatcher must be a function");
        return nullptr;
    }

    auto *r = new RemoteReader();
    r->id = 0;
    r->tsfn = nullptr;
    r->nextSeq = 0;
    r->refs = 1; /* registry reference */
    r->closed = false;
    pthread_mutex_init(&r->mu, nullptr);
    pthread_cond_init(&r->cv, nullptr);

    napi_value name = nullptr;
    napi_create_string_utf8(env, "mupdf_remote_read", NAPI_AUTO_LENGTH, &name);
    if (napi_create_threadsafe_function(env, args[0], nullptr, name, 0, 1, nullptr, nullptr, nullptr,
        RemoteReadCallJs, &r->tsfn) != napi_ok || r->tsfn == nullptr) {
        pthread_mutex_destroy(&r->mu);
        pthread_cond_destroy(&r->cv);
        delete r;
        napi_throw_error(env, "TSFN_FAILED", "cannot create threadsafe function");
        return nullptr;
    }

    pthread_mutex_lock(&g_remoteMu);
    r->id = g_remoteNextId++;
    g_remoteReaders[r->id] = r;
    pthread_mutex_unlock(&g_remoteMu);

    napi_value result;
    napi_create_int64(env, r->id, &result);
    return result;
}

napi_value UnregisterRemoteReader(napi_env env, napi_callback_info info)
{
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    int64_t id = 0;
    if (argc < 1 || napi_get_value_int64(env, args[0], &id) != napi_ok) {
        napi_throw_type_error(env, nullptr, "readerId (number) required");
        return nullptr;
    }

    pthread_mutex_lock(&g_remoteMu);
    std::map<int64_t, RemoteReader *>::iterator it = g_remoteReaders.find(id);
    RemoteReader *r = (it != g_remoteReaders.end()) ? it->second : nullptr;
    if (it != g_remoteReaders.end()) {
        g_remoteReaders.erase(it);
    }
    pthread_mutex_unlock(&g_remoteMu);

    if (r != nullptr) {
        pthread_mutex_lock(&r->mu);
        r->closed = true;
        RemoteReaderWakeAll(r);
        pthread_mutex_unlock(&r->mu);
        RemoteReaderRelease(r); /* drop the registry reference */
    }

    napi_value result;
    napi_get_undefined(env, &result);
    return result;
}

/* ArkTS reports a completed read: remoteReadDone(id, seq, data?) */
napi_value RemoteReadDone(napi_env env, napi_callback_info info)
{
    size_t argc = 3;
    napi_value args[3];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 2) {
        napi_throw_type_error(env, nullptr, "remoteReadDone(id, seq, data?) required");
        return nullptr;
    }
    int64_t id = 0;
    int64_t seq = 0;
    napi_get_value_int64(env, args[0], &id);
    napi_get_value_int64(env, args[1], &seq);

    RemoteReader *r = nullptr;
    pthread_mutex_lock(&g_remoteMu);
    std::map<int64_t, RemoteReader *>::iterator it = g_remoteReaders.find(id);
    if (it != g_remoteReaders.end()) {
        r = it->second;
    }
    pthread_mutex_unlock(&g_remoteMu);

    napi_value result;
    napi_get_undefined(env, &result);
    if (r == nullptr) {
        return result; /* reader already unregistered: ignore late answers */
    }

    pthread_mutex_lock(&r->mu);
    std::map<uint64_t, RemotePending *>::iterator pit = r->pending.find(static_cast<uint64_t>(seq));
    if (pit != r->pending.end()) {
        RemotePending *p = pit->second;
        p->failed = true;
        if (argc >= 3 && args[2] != nullptr) {
            napi_valuetype t = napi_undefined;
            napi_typeof(env, args[2], &t);
            if (t == napi_object) {
                void *data = nullptr;
                size_t len = 0;
                if (napi_get_arraybuffer_info(env, args[2], &data, &len) == napi_ok && data != nullptr && len > 0) {
                    p->data.assign(static_cast<uint8_t *>(data), static_cast<uint8_t *>(data) + len);
                    p->failed = false;
                }
            }
        }
        p->done = true;
        pthread_cond_broadcast(&r->cv);
    }
    pthread_mutex_unlock(&r->mu);
    return result;
}

/* Blocking read executed on MuPDF worker threads (called with the
 * document's g_mu held). Returns bytes read or -1. */
static int RemoteRead(RemoteReader *r, int64_t offset, size_t len, uint8_t *out, size_t outCap)
{
    if (r == nullptr || len == 0) {
        return -1;
    }
    pthread_mutex_lock(&r->mu);
    if (r->closed) {
        pthread_mutex_unlock(&r->mu);
        return -1;
    }
    RemotePending *p = new RemotePending();
    p->seq = ++r->nextSeq;
    p->done = false;
    p->failed = false;
    r->pending[p->seq] = p;
    pthread_mutex_unlock(&r->mu);

    auto *req = new RemoteReadRequest();
    req->seq = p->seq;
    req->offset = offset;
    req->len = len;
    if (napi_call_threadsafe_function(r->tsfn, req, napi_tsfn_blocking) != napi_ok) {
        delete req;
        pthread_mutex_lock(&r->mu);
        p->failed = true;
        p->done = true;
        pthread_mutex_unlock(&r->mu);
    }

    pthread_mutex_lock(&r->mu);
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    ts.tv_sec += kRemoteReadTimeoutSec;
    while (!p->done) {
        if (pthread_cond_timedwait(&r->cv, &r->mu, &ts) != 0) {
            p->failed = true;
            break;
        }
    }
    r->pending.erase(p->seq);
    int result = -1;
    if (!p->failed && p->data.size() > 0 && p->data.size() <= outCap) {
        memcpy(out, p->data.data(), p->data.size());
        result = static_cast<int>(p->data.size());
    }
    pthread_cond_broadcast(&r->cv);
    pthread_mutex_unlock(&r->mu);
    delete p;
    return result;
}

/* The blocking read above never touches r->refs: its lifetime is covered
 * by the calling fz_stream (see RemoteStreamDrop / RemoteReaderRelease). */
struct RemoteStreamState {
    RemoteReader *reader;
    int64_t size;
    uint8_t *buf;
};

static int RemoteStreamNext(fz_context *ctx, fz_stream *stm, size_t /*max*/)
{
    auto *st = static_cast<RemoteStreamState *>(stm->state);
    if (stm->pos >= st->size) {
        stm->eof = 1;
        return EOF;
    }
    int64_t remaining = st->size - stm->pos;
    size_t want = (static_cast<int64_t>(kRemoteStreamBuf) < remaining) ? kRemoteStreamBuf
        : static_cast<size_t>(remaining);
    int n = RemoteRead(st->reader, stm->pos, want, st->buf, kRemoteStreamBuf);
    if (n <= 0) {
        stm->eof = 1;
        return EOF;
    }
    stm->rp = st->buf;
    stm->wp = st->buf + n;
    stm->pos += static_cast<int64_t>(n);
    return *stm->rp++;
}

static void RemoteStreamSeek(fz_context * /*ctx*/, fz_stream *stm, int64_t offset, int whence)
{
    auto *st = static_cast<RemoteStreamState *>(stm->state);
    int64_t target = offset;
    if (whence == SEEK_END) {
        target = st->size + offset;
    }
    if (target < 0) {
        target = 0;
    }
    if (target > st->size) {
        target = st->size;
    }
    stm->pos = target;
    /* invalidate the read buffer, mirroring seek_file — a stale rp/wp makes
     * fz_tell() return pos minus the unconsumed bytes (pdf xref parsing
     * relies on tell right after a backward seek) */
    stm->rp = st->buf;
    stm->wp = st->buf;
}

static void RemoteStreamDrop(fz_context *ctx, void *state)
{
    auto *st = static_cast<RemoteStreamState *>(state);
    if (st != nullptr) {
        fz_free(ctx, st->buf);
        if (st->reader != nullptr) {
            RemoteReaderRelease(st->reader);
        }
        delete st;
    }
}

/* ---- JSON helpers for the async ops ---- */

static void JsonAppendEscaped(std::string &out, const char *s)
{
    for (const unsigned char *p = reinterpret_cast<const unsigned char *>(s ? s : ""); *p; ++p) {
        unsigned char c = *p;
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            default:
                if (c < 0x20) {
                    char b[8];
                    snprintf(b, sizeof(b), "\\u%04x", c);
                    out += b;
                } else {
                    out += static_cast<char>(c);
                }
        }
    }
}

static std::string OpTocJson(fz_context *ctx, fz_document *doc)
{
    std::string json = "[";
    fz_outline *toc = nullptr;
    fz_var(toc);
    if (!fz_setjmp(*fz_push_try(ctx))) do {
        toc = fz_load_outline(ctx, doc);
    } while (0);
    if (fz_do_catch(ctx)) {
        toc = nullptr; /* no outline is not fatal */
    }
    if (toc != nullptr) {
        struct Frame { fz_outline *o; int depth; };
        std::vector<Frame> stack;
        stack.push_back({toc, 0});
        bool first = true;
        while (!stack.empty()) {
            Frame frame = stack.back();
            stack.pop_back();
            std::string title = (frame.o->title != nullptr) ? frame.o->title : "";
            int page = -1;
            if (frame.o->uri != nullptr && frame.o->uri[0] != '\0') {
                const char *p = strstr(frame.o->uri, "page=");
                if (p == nullptr) {
                    p = frame.o->uri;
                }
                int v = atoi(p + (p != frame.o->uri ? 5 : 0));
                if (v > 0) {
                    page = v - 1;
                }
            }
            if (!first) {
                json += ",";
            }
            first = false;
            json += "{\"title\":\"";
            JsonAppendEscaped(json, title.c_str());
            json += "\",\"page\":";
            json += std::to_string(page);
            json += ",\"depth\":";
            json += std::to_string(frame.depth);
            json += "}";
            if (frame.o->down != nullptr) {
                std::vector<fz_outline *> children;
                for (fz_outline *c = frame.o->down; c != nullptr; c = c->next) {
                    children.push_back(c);
                }
                for (auto rit = children.rbegin(); rit != children.rend(); ++rit) {
                    stack.push_back({*rit, frame.depth + 1});
                }
            }
        }
        fz_drop_outline(ctx, toc);
    }
    json += "]";
    return json;
}

static std::string OpPageSizeJson(fz_context *ctx, fz_document *doc, int page)
{
    fz_rect media = fz_infinite_rect;
    fz_var(media);
    fz_page *pg = nullptr;
    if (!fz_setjmp(*fz_push_try(ctx))) do {
        pg = fz_load_page(ctx, doc, page);
        if (pg != nullptr) {
            media = fz_bound_page(ctx, pg);
        }
    } while (0);
    if (fz_do_always(ctx)) do {
        if (pg != nullptr) {
            fz_drop_page(ctx, pg);
        }
    } while (0);
    if (fz_do_catch(ctx)) {
        return "{\"x0\":0,\"y0\":0,\"x1\":612000,\"y1\":792000}";
    }
    char buf[128];
    snprintf(buf, sizeof(buf), "{\"x0\":%lld,\"y0\":%lld,\"x1\":%lld,\"y1\":%lld}",
        static_cast<long long>(media.x0 * 1000), static_cast<long long>(media.y0 * 1000),
        static_cast<long long>(media.x1 * 1000), static_cast<long long>(media.y1 * 1000));
    return std::string(buf);
}

static std::string OpLayoutJson(fz_context *ctx, fz_document *doc, DocumentHandle *h,
    double w, double ht, double em, const std::string &css)
{
    std::string out = "-1";
    if (!fz_setjmp(*fz_push_try(ctx))) do {
        if (!css.empty()) {
            /* preserve a previously injected @font-face rule */
            std::string combined = css;
            if (h->userCss[0] != '\0') {
                const char *face = strstr(h->userCss, "@font-face");
                if (face != nullptr) {
                    const char *end = strchr(face, '}');
                    if (end != nullptr) {
                        combined += " ";
                        combined.append(face, static_cast<size_t>(end - face) + 1);
                    }
                }
            }
            fz_set_user_css(ctx, combined.c_str());
            snprintf(h->userCss, sizeof h->userCss, "%s", combined.c_str());
        }
        fz_layout_document(ctx, doc, static_cast<float>(w), static_cast<float>(ht), static_cast<float>(em));
        out = std::to_string(fz_count_pages(ctx, doc));
    } while (0);
    if (fz_do_catch(ctx)) {
        out = "-1";
    }
    return out;
}

static std::string OpTextJson(fz_context *ctx, fz_document *doc, int page, double zoom)
{
    std::string out = "\"\"";
    fz_stext_page *stext = nullptr;
    char *text = nullptr;
    fz_var(stext);
    fz_var(text);
    if (!fz_setjmp(*fz_push_try(ctx))) do {
        fz_stext_options opts;
        memset(&opts, 0, sizeof(opts));
        stext = fz_new_stext_page_from_page_number(ctx, doc, page, &opts);
        if (stext != nullptr) {
            text = fz_copy_rectangle(ctx, stext, stext->mediabox, 0);
        }
    } while (0);
    if (fz_do_always(ctx)) do {
        if (stext != nullptr) {
            fz_drop_stext_page(ctx, stext);
        }
    } while (0);
    if (fz_do_catch(ctx)) {
        return "\"\"";
    }
    out = "\"";
    JsonAppendEscaped(out, text != nullptr ? text : "");
    out += "\"";
    if (text != nullptr) {
        fz_free(ctx, text);
    }
    return out;
}

static std::string OpSearchPageJson(fz_context *ctx, fz_document *doc, const char *pattern, int page)
{
    std::string json = "[";
    fz_quad *bbox = nullptr;
    int count = 0;
    fz_var(bbox);
    fz_var(count);
    if (!fz_setjmp(*fz_push_try(ctx))) do {
        bbox = static_cast<fz_quad *>(fz_calloc(ctx, sizeof(fz_quad) * 64, 1));
        fz_page *pg = fz_load_page(ctx, doc, page);
        fz_try(ctx) {
            count = fz_search_page(ctx, pg, pattern, nullptr, bbox, 64);
        }
        fz_always(ctx) {
            fz_drop_page(ctx, pg);
        }
        fz_catch(ctx) {
            fz_rethrow(ctx);
        }
    } while (0);
    if (fz_do_always(ctx)) do {
        if (bbox != nullptr) {
            fz_free(ctx, bbox);
        }
    } while (0);
    if (fz_do_catch(ctx)) {
        return "[]";
    }
    if (count > 64) {
        count = 64;
    }
    for (int i = 0; i < count; i++) {
        fz_rect r = fz_rect_from_quad(bbox[i]);
        if (i > 0) {
            json += ",";
        }
        char buf[128];
        snprintf(buf, sizeof(buf), "{\"x0\":%lld,\"y0\":%lld,\"x1\":%lld,\"y1\":%lld}",
            static_cast<long long>(r.x0 * 1000), static_cast<long long>(r.y0 * 1000),
            static_cast<long long>(r.x1 * 1000), static_cast<long long>(r.y1 * 1000));
        json += buf;
    }
    json += "]";
    return json;
}

static std::string OpSearchDocJson(fz_context *ctx, fz_document *doc, const char *pattern)
{
    const int maxTotal = 500;
    int totalHits = 0;
    int pages = fz_count_pages(ctx, doc);
    std::string json = "{\"pages\":[";
    bool first = true;
    for (int p = 0; p < pages && totalHits < maxTotal; p++) {
        int count = 0;
        fz_quad *bbox = nullptr;
        fz_var(bbox);
        if (!fz_setjmp(*fz_push_try(ctx))) do {
            bbox = static_cast<fz_quad *>(fz_calloc(ctx, sizeof(fz_quad) * 64, 1));
            fz_page *pg = fz_load_page(ctx, doc, p);
            fz_try(ctx) {
                count = fz_search_page(ctx, pg, pattern, nullptr, bbox, 64);
            }
            fz_always(ctx) {
                fz_drop_page(ctx, pg);
            }
            fz_catch(ctx) {
                count = 0;
            }
        } while (0);
        if (fz_do_always(ctx)) do {
            if (bbox != nullptr) {
                fz_free(ctx, bbox);
            }
        } while (0);
        if (fz_do_catch(ctx)) {
            count = 0;
        }
        if (count > 0) {
            if (!first) {
                json += ",";
            }
            first = false;
            json += "{\"page\":";
            json += std::to_string(p);
            json += ",\"count\":";
            json += std::to_string(count);
            json += "}";
            totalHits += count;
        }
    }
    json += "],\"totalHits\":";
    json += std::to_string(totalHits);
    json += "}";
    return json;
}

static std::string OpInfoJson(fz_context *ctx, fz_document *doc)
{
    static const char *kKeys[] = {"title", "author", "subject", "creator", "producer", "creationDate", "modDate"};
    std::vector<std::pair<std::string, std::string>> collected;
    if (!fz_setjmp(*fz_push_try(ctx))) do {
        pdf_document *pdf = pdf_specifics(ctx, doc);
        if (pdf != nullptr) {
            pdf_obj *meta = pdf_metadata(ctx, pdf);
            if (meta != nullptr) {
                for (const char *key : kKeys) {
                    pdf_obj *val = pdf_dict_gets(ctx, meta, key);
                    if (val == nullptr || pdf_is_null(ctx, val)) {
                        continue;
                    }
                    const char *str = pdf_to_name(ctx, val);
                    if (str == nullptr || str[0] == '\0') {
                        str = pdf_to_text_string(ctx, val);
                    }
                    if (str != nullptr && str[0] != '\0') {
                        collected.emplace_back(key, str);
                    }
                }
            }
        } else {
            const char *genKeys[] = {FZ_META_INFO_TITLE, FZ_META_INFO_AUTHOR};
            const char *genNames[] = {"title", "author"};
            for (int i = 0; i < 2; i++) {
                char buf[512];
                buf[0] = '\0';
                int r = fz_lookup_metadata(ctx, doc, genKeys[i], buf, sizeof(buf));
                if (r >= 0 && buf[0] != '\0') {
                    collected.emplace_back(genNames[i], buf);
                }
            }
        }
    } while (0);
    if (fz_do_catch(ctx)) {
        /* damaged metadata is not fatal */
    }
    std::string json = "{";
    for (size_t i = 0; i < collected.size(); i++) {
        if (i > 0) {
            json += ",";
        }
        json += "\"";
        json += collected[i].first;
        json += "\":\"";
        JsonAppendEscaped(json, collected[i].second.c_str());
        json += "\"";
    }
    json += "}";
    return json;
}

/* ---- openDocumentRemoteAsync + docOpAsync (napi_async_work) ---- */

struct OpenRemoteJob {
    RemoteReader *reader;
    char magic[32];
    int64_t size;
    bool doLayout;
    double layoutW;
    double layoutH;
    double em;
    std::string css;

    fz_context *ctx = nullptr;
    fz_document *doc = nullptr;
    std::string json;
    std::string failMsg;
    bool failed = false;
    napi_deferred deferred = nullptr;
};

static void OpenRemoteExecute(napi_env /*env*/, void *data)
{
    auto *job = static_cast<OpenRemoteJob *>(data);
    RemoteReader *reader = job->reader;
    if (reader == nullptr || reader->closed) {
        job->failed = true;
        return;
    }

    job->ctx = fz_new_context(nullptr, nullptr, FZ_STORE_DEFAULT);
    if (job->ctx == nullptr) {
        job->failed = true;
        return;
    }
    fz_register_document_handlers(job->ctx);

    fz_stream *stm = nullptr;
    fz_document *doc = nullptr;
    fz_var(stm);
    fz_var(doc);
    if (!fz_setjmp(*fz_push_try(job->ctx))) do {
        auto *st = new RemoteStreamState();
        st->reader = reader;
        st->size = job->size;
        st->buf = static_cast<uint8_t *>(fz_malloc(job->ctx, kRemoteStreamBuf));
        RemoteReaderRetain(reader); /* the stream's reference */
        stm = fz_new_stream(job->ctx, st, RemoteStreamNext, RemoteStreamDrop);
        stm->seek = RemoteStreamSeek;
        doc = fz_open_document_with_stream(job->ctx, job->magic[0] != '\0' ? job->magic : nullptr, stm);
        job->doc = doc;

        /* Gather the open-time metadata Reader needs in one pass. */
        bool needsPassword = false;
        if (!fz_setjmp(*fz_push_try(job->ctx))) do {
            needsPassword = fz_needs_password(job->ctx, doc) != 0;
        } while (0);
        if (fz_do_catch(job->ctx)) {
            needsPassword = false;
        }

        bool isReflow = false;
        if (!fz_setjmp(*fz_push_try(job->ctx))) do {
            isReflow = fz_is_document_reflowable(job->ctx, doc);
        } while (0);
        if (fz_do_catch(job->ctx)) {
            isReflow = false;
        }

        int pages = 0;
        if (!fz_setjmp(*fz_push_try(job->ctx))) do {
            pages = fz_count_pages(job->ctx, doc);
        } while (0);
        if (fz_do_catch(job->ctx)) {
            pages = 0;
        }

        std::string toc = "[]";
        if (pages > 0) {
            toc = OpTocJson(job->ctx, doc);
        }

        char meta[128];
        snprintf(meta, sizeof(meta), "{\"pageCount\":%d,\"isReflow\":%s,\"needsPassword\":%s,\"toc\":",
            pages, isReflow ? "true" : "false", needsPassword ? "true" : "false");
        job->json = std::string(meta) + toc + "}";
    } while (0);
    if (fz_do_catch(job->ctx)) {
        job->failed = true;
        job->failMsg = job->ctx->error.message;
        /* on failure the stream is not owned by a document: drop it */
        if (doc == nullptr && stm != nullptr) {
            fz_drop_stream(job->ctx, stm);
        }
    }
    if (job->failed && job->failMsg.empty() && reader->closed) {
        job->failMsg = "reader closed";
    }
}

static void OpenRemoteComplete(napi_env env, napi_status status, void *data)
{
    auto *job = static_cast<OpenRemoteJob *>(data);
    if (job->deferred != nullptr) {
        if (status == napi_cancelled || job->failed || job->doc == nullptr || job->ctx == nullptr) {
            if (job->ctx != nullptr) {
                if (job->doc != nullptr) {
                    fz_drop_document(job->ctx, job->doc);
                }
                fz_drop_context(job->ctx);
            }
            std::string code = "REMOTE_OPEN_FAILED";
            if (!job->failMsg.empty()) {
                code += ": " + job->failMsg;
            }
            napi_value e;
            napi_create_string_utf8(env, code.c_str(), NAPI_AUTO_LENGTH, &e);
            napi_reject_deferred(env, job->deferred, e);
        } else {
            napi_value obj;
            napi_create_object(env, &obj);
            napi_value ext = MakeExternal(env, job->ctx, job->doc); /* external owns ctx+doc */
            napi_set_named_property(env, obj, "handle", ext);
            napi_value meta;
            napi_create_string_utf8(env, job->json.c_str(), NAPI_AUTO_LENGTH, &meta);
            napi_set_named_property(env, obj, "meta", meta);
            napi_resolve_deferred(env, job->deferred, obj);
        }
    }
    delete job;
}

napi_value OpenDocumentRemoteAsync(napi_env env, napi_callback_info info)
{
    size_t argc = 8;
    napi_value args[8];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 5) {
        napi_throw_type_error(env, nullptr,
            "openDocumentRemoteAsync(readerId, magic, size, doLayout, layoutW, layoutH, em, css?) required");
        return nullptr;
    }
    int64_t readerId = 0;
    if (napi_get_value_int64(env, args[0], &readerId) != napi_ok) {
        napi_throw_type_error(env, nullptr, "readerId must be a number");
        return nullptr;
    }

    RemoteReader *reader = nullptr;
    pthread_mutex_lock(&g_remoteMu);
    std::map<int64_t, RemoteReader *>::iterator it = g_remoteReaders.find(readerId);
    if (it != g_remoteReaders.end()) {
        reader = it->second;
    }
    pthread_mutex_unlock(&g_remoteMu);
    if (reader == nullptr) {
        napi_throw_error(env, "NO_READER", "remote reader not registered");
        return nullptr;
    }

    auto *job = new OpenRemoteJob();
    job->reader = reader;
    job->magic[0] = '\0';
    size_t magicLen = 0;
    napi_get_value_string_utf8(env, args[1], job->magic, sizeof(job->magic) - 1, &magicLen);
    napi_get_value_int64(env, args[2], &job->size);
    bool doLayout = false;
    napi_get_value_bool(env, args[3], &doLayout);
    job->doLayout = doLayout;
    napi_get_value_double(env, args[4], &job->layoutW);
    napi_get_value_double(env, args[5], &job->layoutH);
    napi_get_value_double(env, args[6], &job->em);
    if (argc >= 8) {
        napi_valuetype t = napi_undefined;
        napi_typeof(env, args[7], &t);
        if (t == napi_string) {
            size_t len = 0;
            napi_get_value_string_utf8(env, args[7], nullptr, 0, &len);
            if (len > 0) {
                job->css.resize(len);
                napi_get_value_string_utf8(env, args[7], &job->css[0], len + 1, &len);
            }
        }
    }

    napi_value deferredVal = nullptr;
    if (napi_create_promise(env, &job->deferred, &deferredVal) != napi_ok || job->deferred == nullptr) {
        delete job;
        napi_throw_error(env, "PROMISE_FAILED", "cannot create promise");
        return nullptr;
    }

    napi_async_work work = nullptr;
    napi_value nameVal = nullptr;
    napi_create_string_utf8(env, "mupdf_open_remote", NAPI_AUTO_LENGTH, &nameVal);
    if (napi_create_async_work(env, nullptr, nameVal, OpenRemoteExecute, OpenRemoteComplete, job, &work) != napi_ok ||
        work == nullptr) {
        delete job;
        napi_throw_error(env, "WORK_FAILED", "cannot create async work");
        return nullptr;
    }
    if (napi_queue_async_work(env, work) != napi_ok) {
        napi_delete_async_work(env, work);
        delete job;
        napi_throw_error(env, "QUEUE_FAILED", "cannot queue async work");
        return nullptr;
    }
    return deferredVal;
}

/* Per-page text lines with per-char bounds (mirrors GetTextRects but
 * builds a std::string). Returns "[]" on error. */
static std::string OpTextRectsJson(fz_context *ctx, fz_document *doc, int page)
{
    fz_display_list *dl = nullptr;
    fz_stext_page *stext = nullptr;
    fz_var(dl);
    fz_var(stext);
    if (!fz_setjmp(*fz_push_try(ctx))) do {
        dl = fz_new_display_list_from_page_number(ctx, doc, page);
        stext = fz_new_stext_page(ctx, fz_infinite_rect);
        fz_stext_options opts;
        memset(&opts, 0, sizeof(opts));
        opts.flags = FZ_STEXT_PRESERVE_WHITESPACE | FZ_STEXT_MEDIABOX_CLIP;
        fz_device *dev = fz_new_stext_device(ctx, stext, &opts);
        fz_try(ctx) {
            fz_run_display_list(ctx, dl, dev, fz_identity, fz_infinite_rect, nullptr);
        }
        fz_always(ctx) {
            fz_close_device(ctx, dev);
            fz_drop_device(ctx, dev);
        }
        fz_catch(ctx) {
            fz_rethrow(ctx);
        }
    } while (0);
    if (fz_do_always(ctx)) do {
        if (dl != nullptr) {
            fz_drop_display_list(ctx, dl);
        }
    } while (0);
    if (fz_do_catch(ctx)) {
        if (stext != nullptr) {
            fz_drop_stext_page(ctx, stext);
        }
        return "[]";
    }
    if (stext == nullptr) {
        return "[]";
    }

    float pw = stext->mediabox.x1 - stext->mediabox.x0;
    float ph = stext->mediabox.y1 - stext->mediabox.y0;
    if (pw <= 0.0f) pw = 612.0f;
    if (ph <= 0.0f) ph = 792.0f;

    std::string json = "[";
    bool first = true;
    for (const fz_stext_block *block = stext->first_block; block != nullptr; block = block->next) {
        if (block->type != FZ_STEXT_BLOCK_TEXT) continue;
        for (const fz_stext_line *line = block->u.t.first_line; line != nullptr; line = line->next) {
            if (!first) json += ",";
            first = false;
            float x0 = (line->bbox.x0 - stext->mediabox.x0) / pw;
            float y0 = (line->bbox.y0 - stext->mediabox.y0) / ph;
            float x1 = (line->bbox.x1 - stext->mediabox.x0) / pw;
            float y1 = (line->bbox.y1 - stext->mediabox.y0) / ph;
            if (x0 < 0.0f) x0 = 0.0f;
            if (x1 > 1.0f) x1 = 1.0f;
            if (y0 < 0.0f) y0 = 0.0f;
            if (y1 > 1.0f) y1 = 1.0f;
            char buf[128];
            snprintf(buf, sizeof(buf), "{\"x0\":%.4f,\"y0\":%.4f,\"x1\":%.4f,\"y1\":%.4f,\"text\":\"",
                x0, y0, x1, y1);
            json += buf;
            for (const fz_stext_char *ch = line->first_char; ch != nullptr; ch = ch->next) {
                char tmp[8];
                unsigned int c = static_cast<unsigned int>(ch->c);
                if (c == '"') {
                    json += "\\\"";
                } else if (c == '\\') {
                    json += "\\\\";
                } else if (c == '\n') {
                    json += "\\n";
                } else if (c < 0x20 || c > 0x10FFFF) {
                    snprintf(tmp, sizeof(tmp), "\\u%04x", c & 0xFFFF);
                    json += tmp;
                } else if (c < 0x80) {
                    json += static_cast<char>(c);
                } else {
                    /* UTF-8 encode (mirrors AppendJsonChar) */
                    if (c < 0x800) {
                        tmp[0] = static_cast<char>(0xC0 | (c >> 6));
                        tmp[1] = static_cast<char>(0x80 | (c & 0x3F));
                        json.append(tmp, 2);
                    } else if (c < 0x10000) {
                        tmp[0] = static_cast<char>(0xE0 | (c >> 12));
                        tmp[1] = static_cast<char>(0x80 | ((c >> 6) & 0x3F));
                        tmp[2] = static_cast<char>(0x80 | (c & 0x3F));
                        json.append(tmp, 3);
                    } else {
                        tmp[0] = static_cast<char>(0xF0 | (c >> 18));
                        tmp[1] = static_cast<char>(0x80 | ((c >> 12) & 0x3F));
                        tmp[2] = static_cast<char>(0x80 | ((c >> 6) & 0x3F));
                        tmp[3] = static_cast<char>(0x80 | (c & 0x3F));
                        json.append(tmp, 4);
                    }
                }
            }
            json += "\",\"chars\":[";
            bool fc = true;
            for (const fz_stext_char *ch = line->first_char; ch != nullptr; ch = ch->next) {
                float cx0 = ch->quad.ll.x, cx1 = ch->quad.lr.x;
                if (ch->quad.ul.x < cx0) cx0 = ch->quad.ul.x;
                if (ch->quad.ur.x > cx1) cx1 = ch->quad.ur.x;
                cx0 = (cx0 - stext->mediabox.x0) / pw;
                cx1 = (cx1 - stext->mediabox.x0) / pw;
                if (cx0 < 0.0f) cx0 = 0.0f;
                if (cx1 > 1.0f) cx1 = 1.0f;
                if (!fc) json += ",";
                fc = false;
                snprintf(buf, sizeof(buf), "%.4f,%.4f", cx0, cx1);
                json += buf;
            }
            json += "]}";
        }
    }
    json += "]";
    fz_drop_stext_page(ctx, stext);
    return json;
}

/* PDF annotations as JSON (mirrors GetAnnotations). Returns "[]" when not PDF / no annots. */
static std::string OpAnnotsJson(fz_context *ctx, fz_document *doc, int page)
{
    pdf_document *pdf = pdf_specifics(ctx, doc);
    if (pdf == nullptr) {
        return "[]";
    }
    fz_rect media = fz_infinite_rect;
    pdf_page *pg = nullptr;
    fz_var(media);
    fz_var(pg);
    if (!fz_setjmp(*fz_push_try(ctx))) do {
        pg = pdf_load_page(ctx, pdf, page);
        if (pg != nullptr) {
            media = fz_bound_page(ctx, (fz_page *)pg);
        }
    } while (0);
    if (fz_do_catch(ctx)) {
        pg = nullptr;
    }
    if (pg == nullptr) {
        return "[]";
    }

    float pw = (media.x1 - media.x0) > 0 ? (media.x1 - media.x0) : 1.0f;
    float ph = (media.y1 - media.y0) > 0 ? (media.y1 - media.y0) : 1.0f;

    std::string json = "[";
    int index = 0;
    if (!fz_setjmp(*fz_push_try(ctx))) do {
        pdf_annot *annot = pdf_first_annot(ctx, pg);
        while (annot != nullptr) {
            enum pdf_annot_type t = pdf_annot_type(ctx, annot);
            fz_rect r = pdf_bound_annot(ctx, annot);
            const char *contents = pdf_annot_contents(ctx, annot);
            if (index > 0) {
                json += ",";
            }
            char buf[160];
            snprintf(buf, sizeof(buf),
                "{\"index\":%d,\"type\":\"%s\",\"x0\":%.4f,\"y0\":%.4f,\"x1\":%.4f,\"y1\":%.4f,\"contents\":\"",
                index, AnnotTypeName(t), (r.x0 - media.x0) / pw, (r.y0 - media.y0) / ph,
                (r.x1 - media.x0) / pw, (r.y1 - media.y0) / ph);
            json += buf;
            JsonAppendEscaped(json, contents != nullptr ? contents : "");
            json += "\"}";
            index++;
            annot = pdf_next_annot(ctx, annot);
        }
    } while (0);
    if (fz_do_catch(ctx)) {
        /* partial results ok */
    }
    json += "]";
    fz_drop_page(ctx, (fz_page *)pg);
    return json;
}

/* Generic async document op. ops:
 * 0 pageCount | 1 toc | 2 pageSize(a=page) | 3 layout(a=w,b=h,c=em,s=css)
 * 4 text(a=page,b=zoom) | 5 search(s=text,a=page) | 6 searchdoc(s=text)
 * 7 textrects(a=page) | 8 info | 9 isReflow | 10 needsPassword
 * 11 annots(a=page) | 12 authenticate(s=password)
 * All results are JSON strings (document accessors are unsafe to call
 * synchronously on remote-streamed documents). */
struct DocOpJob {
    DocumentHandle *h; /* holds a reference for the job lifetime */
    int op;
    double a;
    double b;
    double c;
    std::string s;
    std::string json;
    bool failed = false;
    napi_deferred deferred = nullptr;
};

static void DocOpExecute(napi_env /*env*/, void *data)
{
    auto *job = static_cast<DocOpJob *>(data);
    DocumentHandle *h = job->h;
    pthread_mutex_lock(&g_mu);
    if (h->closed) {
        job->failed = true;
        pthread_mutex_unlock(&g_mu);
        return;
    }
    fz_context *ctx = h->ctx;
    fz_document *doc = h->doc;
    bool opErr = false;
    if (!fz_setjmp(*fz_push_try(ctx))) do {
        switch (job->op) {
            case 0:
                job->json = std::to_string(fz_count_pages(ctx, doc));
                break;
            case 1:
                job->json = OpTocJson(ctx, doc);
                break;
            case 2:
                job->json = OpPageSizeJson(ctx, doc, static_cast<int>(job->a));
                break;
            case 3:
                job->json = OpLayoutJson(ctx, doc, h, job->a, job->b, job->c, job->s);
                break;
            case 4:
                job->json = OpTextJson(ctx, doc, static_cast<int>(job->a), job->b);
                break;
            case 5:
                job->json = OpSearchPageJson(ctx, doc, job->s.c_str(), static_cast<int>(job->a));
                break;
            case 6:
                job->json = OpSearchDocJson(ctx, doc, job->s.c_str());
                break;
            case 7:
                job->json = OpTextRectsJson(ctx, doc, static_cast<int>(job->a));
                break;
            case 8:
                job->json = OpInfoJson(ctx, doc);
                break;
            case 9:
                job->json = fz_is_document_reflowable(ctx, doc) ? "true" : "false";
                break;
            case 10:
                job->json = fz_needs_password(ctx, doc) ? "true" : "false";
                break;
            case 11:
                job->json = OpAnnotsJson(ctx, doc, static_cast<int>(job->a));
                break;
            case 12:
                job->json = std::to_string(fz_authenticate_password(ctx, doc, job->s.c_str()));
                break;
            default:
                job->failed = true;
                break;
        }
    } while (0);
    if (fz_do_catch(ctx)) {
        opErr = true;
    }
    pthread_mutex_unlock(&g_mu);
    if (opErr && job->json.empty()) {
        job->failed = true;
    }
}

static void DocOpComplete(napi_env env, napi_status status, void *data)
{
    auto *job = static_cast<DocOpJob *>(data);
    if (job->deferred != nullptr) {
        if (status == napi_cancelled || job->failed) {
            napi_value e;
            napi_create_string_utf8(env, "DOC_OP_FAILED", NAPI_AUTO_LENGTH, &e);
            napi_reject_deferred(env, job->deferred, e);
        } else {
            napi_value result;
            napi_create_string_utf8(env, job->json.c_str(), NAPI_AUTO_LENGTH, &result);
            napi_resolve_deferred(env, job->deferred, result);
        }
    }
    ReleaseHandle(job->h);
    delete job;
}

napi_value DocOpAsync(napi_env env, napi_callback_info info)
{
    size_t argc = 6;
    napi_value args[6];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    if (argc < 2) {
        napi_throw_type_error(env, nullptr, "docOpAsync(handle, op, a?, b?, c?, s?) required");
        return nullptr;
    }
    auto *h = GetHandle(env, args[0]);
    if (h == nullptr) {
        return nullptr;
    }
    int32_t op = 0;
    napi_get_value_int32(env, args[1], &op);

    auto *job = new DocOpJob();
    job->h = h;
    job->op = op;
    job->a = 0;
    job->b = 0;
    job->c = 0;
    if (argc >= 3) {
        napi_valuetype t = napi_undefined;
        napi_typeof(env, args[2], &t);
        if (t == napi_number) {
            napi_get_value_double(env, args[2], &job->a);
        } else if (t == napi_string) {
            size_t len = 0;
            napi_get_value_string_utf8(env, args[2], nullptr, 0, &len);
            if (len > 0) {
                job->s.resize(len);
                napi_get_value_string_utf8(env, args[2], &job->s[0], len + 1, &len);
            }
        }
    }
    if (argc >= 4) {
        napi_get_value_double(env, args[3], &job->b);
    }
    if (argc >= 5) {
        napi_get_value_double(env, args[4], &job->c);
    }
    if (argc >= 6) {
        napi_valuetype t = napi_undefined;
        napi_typeof(env, args[5], &t);
        if (t == napi_string) {
            std::string extra;
            size_t len = 0;
            napi_get_value_string_utf8(env, args[5], nullptr, 0, &len);
            if (len > 0) {
                extra.resize(len);
                napi_get_value_string_utf8(env, args[5], &extra[0], len + 1, &len);
            }
            if (job->s.empty()) {
                job->s = extra;
            }
        }
    }
    AcquireHandle(h);

    napi_value deferredVal = nullptr;
    if (napi_create_promise(env, &job->deferred, &deferredVal) != napi_ok || job->deferred == nullptr) {
        ReleaseHandle(h);
        delete job;
        napi_throw_error(env, "PROMISE_FAILED", "cannot create promise");
        return nullptr;
    }

    napi_async_work work = nullptr;
    napi_value nameVal = nullptr;
    napi_create_string_utf8(env, "mupdf_doc_op", NAPI_AUTO_LENGTH, &nameVal);
    if (napi_create_async_work(env, nullptr, nameVal, DocOpExecute, DocOpComplete, job, &work) != napi_ok ||
        work == nullptr) {
        ReleaseHandle(h);
        delete job;
        napi_throw_error(env, "WORK_FAILED", "cannot create async work");
        return nullptr;
    }
    if (napi_queue_async_work(env, work) != napi_ok) {
        napi_delete_async_work(env, work);
        ReleaseHandle(h);
        delete job;
        napi_throw_error(env, "QUEUE_FAILED", "cannot queue async work");
        return nullptr;
    }
    return deferredVal;
}

napi_value Init(napi_env env, napi_value exports)
{
    napi_property_descriptor desc[] = {
        {"version", nullptr, Version, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"openDocument", nullptr, OpenDocument, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"openDocumentByFd", nullptr, OpenDocumentByFd, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"registerRemoteReader", nullptr, RegisterRemoteReader, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"unregisterRemoteReader", nullptr, UnregisterRemoteReader, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"remoteReadDone", nullptr, RemoteReadDone, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"openDocumentRemoteAsync", nullptr, OpenDocumentRemoteAsync, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"docOpAsync", nullptr, DocOpAsync, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"pageCount", nullptr, PageCount, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"renderPage", nullptr, RenderPage, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"renderPageAsync", nullptr, RenderPageAsync, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"getToc", nullptr, GetToc, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"getPageSize", nullptr, GetPageSize, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"layoutDocument", nullptr, LayoutDocument, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"isReflowable", nullptr, IsReflowable, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"getAnnotations", nullptr, GetAnnotations, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"addHighlight", nullptr, AddHighlight, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"addInkStroke", nullptr, AddInkStroke, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"addMarkupAnnotation", nullptr, AddMarkupAnnotation, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"addTextNote", nullptr, AddTextNote, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"deleteAnnotation", nullptr, DeleteAnnotation, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"saveDocument", nullptr, SaveDocument, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"getText", nullptr, GetText, nullptr, nullptr, nullptr, napi_default, nullptr},
    {"getTextRects", nullptr, GetTextRects, nullptr, nullptr, nullptr, napi_default, nullptr},
    {"loadFont", nullptr, LoadFont, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"searchText", nullptr, SearchText, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"searchDocument", nullptr, SearchDocument, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"getDocumentInfo", nullptr, GetDocumentInfo, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"closeDocument", nullptr, CloseDocument, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"needsPassword", nullptr, NeedsPassword, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"authenticateDocument", nullptr, AuthenticateDocument, nullptr, nullptr, nullptr, napi_default, nullptr},
    };
    napi_define_properties(env, exports, sizeof(desc) / sizeof(desc[0]), desc);
    return exports;
}

} // namespace

static napi_module mupdfNapiModule = {
    .nm_version = 1,
    .nm_flags = 0,
    .nm_filename = nullptr,
    .nm_register_func = Init,
    .nm_modname = "mupdf_napi",
    .nm_priv = nullptr,
    .reserved = {0},
};

extern "C" __attribute__((constructor)) void RegisterMupdfNapiModule(void)
{
    napi_module_register(&mupdfNapiModule);
}
