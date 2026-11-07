/* HowRead Qt 分支 v0.59.0：DJVU 后端（libdjvu-3.5.28 ddjvu API，纯 C，无 JNI）。
 * 桌面 hr_djvu.c 同款语义：轮询+终态判定+次数上限（1500x10ms=15s）；
 * RGBMASK32 masks 与安卓 DjvuDroidBridge/桌面 HrDjvuSession 一致（0xAARRGGBB）。
 * ddjvu API 无 document→context 反查——ctx 由 hr_bridge 显式传入各函数。 */
#include <ddjvuapi.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#include <windows.h>
#define HR_SLEEP(ms) Sleep(ms)
#else
#include <unistd.h>
#define HR_SLEEP(ms) usleep((ms) * 1000)
#endif

#define HR_DJVU_TRIES 1500 /* 1500 x 10ms = 15s 上限 */

static void hr_djv_pump(ddjvu_context_t *ctx)
{
	const ddjvu_message_t *msg;
	while ((msg = ddjvu_message_peek(ctx)) != NULL)
		ddjvu_message_pop(ctx);
}

static int hr_djv_wait_doc(ddjvu_context_t *ctx, ddjvu_document_t *doc)
{
	int i, st = DDJVU_JOB_NOTSTARTED;
	for (i = 0; i < HR_DJVU_TRIES; i++)
	{
		hr_djv_pump(ctx);
		st = ddjvu_document_decoding_status(doc);
		if (st >= DDJVU_JOB_OK)
			return st;
		HR_SLEEP(10);
	}
	return st;
}

static int hr_djv_wait_page(ddjvu_context_t *ctx, ddjvu_page_t *pg)
{
	int i, st = DDJVU_JOB_NOTSTARTED;
	for (i = 0; i < HR_DJVU_TRIES; i++)
	{
		hr_djv_pump(ctx);
		st = ddjvu_page_decoding_status(pg);
		if (st >= DDJVU_JOB_OK)
			return st;
		HR_SLEEP(10);
	}
	return st;
}

void *hr_djv_create(void)
{
	return (void *)ddjvu_context_create("HowRead");
}

void *hr_djv_open(void *ctxv, const char *path)
{
	ddjvu_context_t *ctx = (ddjvu_context_t *)ctxv;
	ddjvu_document_t *doc;
	int st;
	if (!ctx || !path)
		return NULL;
	doc = ddjvu_document_create_by_filename_utf8(ctx, path, TRUE);
	if (!doc)
		return NULL;
	st = hr_djv_wait_doc(ctx, doc);
	if (st != DDJVU_JOB_OK || ddjvu_document_get_pagenum(doc) <= 0)
	{
		ddjvu_document_release(doc);
		return NULL;
	}
	return (void *)doc;
}

int hr_djv_count(void *docv)
{
	return docv ? (int)ddjvu_document_get_pagenum((ddjvu_document_t *)docv) : 0;
}

int hr_djv_page_size(void *ctxv, void *docv, int page, int *w, int *h)
{
	ddjvu_context_t *ctx = (ddjvu_context_t *)ctxv;
	ddjvu_document_t *doc = (ddjvu_document_t *)docv;
	ddjvu_page_t *pg;
	int rc = -1;
	if (!ctx || !doc || !w || !h)
		return -1;
	pg = ddjvu_page_create_by_pageno(doc, page);
	if (!pg)
		return -1;
	if (hr_djv_wait_page(ctx, pg) == DDJVU_JOB_OK)
	{
		int pw = (int)ddjvu_page_get_width(pg);
		int ph = (int)ddjvu_page_get_height(pg);
		if (pw > 0 && ph > 0)
		{
			*w = pw;
			*h = ph;
			rc = 0;
		}
	}
	ddjvu_page_release(pg);
	return rc;
}

int hr_djv_render(void *ctxv, void *docv, int page, int width,
                  unsigned int **outPx, int *outW, int *outH)
{
	ddjvu_context_t *ctx = (ddjvu_context_t *)ctxv;
	ddjvu_document_t *doc = (ddjvu_document_t *)docv;
	ddjvu_page_t *pg;
	int pw, ph, tw, th;
	unsigned int *px = NULL;
	int rc = -1;
	if (!ctx || !doc || !outPx || !outW || !outH || width <= 0)
		return -1;
	pg = ddjvu_page_create_by_pageno(doc, page);
	if (!pg)
		return -1;
	if (hr_djv_wait_page(ctx, pg) == DDJVU_JOB_OK)
	{
		pw = (int)ddjvu_page_get_width(pg);
		ph = (int)ddjvu_page_get_height(pg);
		if (pw > 0 && ph > 0)
		{
			tw = width < 16 ? 16 : width;
			th = (int)((long long)ph * tw / pw);
			if (th < 1) th = 1;
			if ((long long)tw * th > 40000000LL) /* 检视加固：40M 像素上限（HrDjvuSession 同款） */
			{
				double k = sqrt(40000000.0 / ((double)tw * th));
				tw = (int)(tw * k);
				if (tw < 16) tw = 16;
				th = (int)((long long)ph * tw / pw);
				if (th < 1) th = 1;
			}
			px = (unsigned int *)malloc((size_t)tw * th * 4);
			if (px)
			{
				unsigned int masks[4] = {0x00FF0000, 0x0000FF00, 0x000000FF, 0xFF000000};
				ddjvu_format_t *fmt = ddjvu_format_create(DDJVU_FORMAT_RGBMASK32, 4, masks);
				if (fmt)
				{
					ddjvu_rect_t rect;
					rect.x = 0; rect.y = 0; rect.w = tw; rect.h = th;
					ddjvu_format_set_row_order(fmt, TRUE);
					ddjvu_format_set_y_direction(fmt, TRUE);
					if (ddjvu_page_render(pg, DDJVU_RENDER_COLOR, &rect, &rect, fmt,
					                      tw * 4, (char *)px))
					{
						hr_djv_pump(ctx);
						*outPx = px;
						*outW = tw;
						*outH = th;
						px = NULL;
						rc = 0;
					}
					ddjvu_format_release(fmt);
				}
			}
		}
	}
	if (px) free(px);
	ddjvu_page_release(pg);
	return rc;
}

void hr_djv_release_doc(void *ctxv, void *docv)
{
	(void)ctxv;
	if (docv) ddjvu_document_release((ddjvu_document_t *)docv);
}

void hr_djv_release_ctx(void *ctxv)
{
	if (ctxv) ddjvu_context_release((ddjvu_context_t *)ctxv);
}
