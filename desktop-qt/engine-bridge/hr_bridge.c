/* HowRead Qt 分支 M0：引擎 C 桥接实现（渲染管线逐行复刻 DocumentHandle.render）。 */
#include "hr_bridge.h"
#include "mupdf/fitz.h"
#include "mupdf/pdf.h"
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <ctype.h>
#include <string.h>

#ifdef _WIN32
#define _WIN32_WINNT 0x0601
#include <windows.h>
static SRWLOCK g_lock = SRWLOCK_INIT;
static void hr_lock(void) { AcquireSRWLockExclusive(&g_lock); }
static void hr_unlock(void) { ReleaseSRWLockExclusive(&g_lock); }
#else
#include <pthread.h>
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static void hr_lock(void) { pthread_mutex_lock(&g_lock); }
static void hr_unlock(void) { pthread_mutex_unlock(&g_lock); }
#endif

/* 来自 hr_sysfont.c（同 target）：与桌面现版同一套系统字体钩子 */
extern fz_font *hr_sysfont_generic(fz_context *ctx, const char *name, int bold, int italic, int needs_exact_metrics);
extern fz_font *hr_sysfont_cjk(fz_context *ctx, const char *name, int ordering, int serif);
extern fz_font *hr_sysfont_fallback(fz_context *ctx, int script, int language, int serif, int bold, int italic);

static fz_context *g_ctx = NULL;
static char g_err[512] = {0};

static void hr_set_err(const char *m) { snprintf(g_err, sizeof g_err, "%s", m ? m : ""); }
const char *hr_qt_last_error(void) { return g_err; }

/* ---- v0.59.0：统一句柄（mupdf 文档 / DJVU 会话二选一，DocumentHandle.isDjvu 同构） ---- */
struct hr_doc
{
	int isDjvu;
	fz_document *fz;
	void *dctx; /* ddjvu_context_t* */
	void *ddoc; /* ddjvu_document_t* */
};

/* DJVU 后端（hr_qt_djvu.c，同 target 内部符号，不走 .def 导出） */
void *hr_djv_create(void);
void *hr_djv_open(void *ctx, const char *path);
int hr_djv_count(void *doc);
int hr_djv_page_size(void *ctx, void *doc, int page, int *w, int *h);
int hr_djv_render(void *ctx, void *doc, int page, int width, unsigned int **outPx, int *outW, int *outH);
void hr_djv_release_doc(void *ctx, void *doc);
void hr_djv_release_ctx(void *ctx);

/* 扩展名小写比较（.djvu/.djv 判定；免 stricmp 平台差异） */
static int hr_ext_is(const char *path, const char *ext)
{
	size_t n = strlen(ext);
	const char *dot = strrchr(path, '.');
	if (!dot || strlen(dot + 1) != n) return 0;
	for (size_t i = 0; i < n; i++)
		if (tolower((unsigned char)dot[i + 1]) != (unsigned char)ext[i]) return 0;
	return 1;
}

int hr_qt_init(void)
{
	if (g_ctx) return 0;
	fz_context *ctx = fz_new_context(NULL, NULL, FZ_STORE_UNLIMITED);
	if (!ctx) { hr_set_err("fz_new_context failed"); return -1; }
	fz_try(ctx)
	{
		fz_register_document_handlers(ctx);
		/* 与现版桌面一致：用户字体目录 + 系统字体钩子（hr_user_fonts.c / hr_sysfont.c） */
		fz_install_load_system_font_funcs(ctx, hr_sysfont_generic, hr_sysfont_cjk, hr_sysfont_fallback);
	}
	fz_catch(ctx)
	{
		hr_set_err(fz_caught_message(ctx));
		fz_drop_context(ctx);
		return -2;
	}
	g_ctx = ctx;
	return 0;
}

void hr_qt_shutdown(void)
{
	hr_lock(); /* v0.99.0 W20：与在途 worker 互斥后再销毁上下文 */
	if (g_ctx) { fz_drop_context(g_ctx); g_ctx = NULL; }
	hr_unlock();
}

void hr_qt_set_css(const char *css, int use_document_css)
{
	if (!g_ctx) return;
	hr_lock(); /* v0.99.0 W20：此前无锁——与扫描/封面线程的引擎调用并发即踩 g_ctx */
	fz_set_user_css(g_ctx, css ? css : "");
	fz_set_use_document_css(g_ctx, use_document_css != 0);
	hr_unlock();
}

/* v0.103.0（P1-2）：排版期延迟图解（ctx 级开关；对齐安卓 deferHtml Round 12） */
void hr_qt_defer_images(int defer)
{
	if (!g_ctx) return;
	hr_lock();
	fz_set_defer_html_images(g_ctx, defer != 0);
	hr_unlock();
}

void *hr_qt_open(const char *path)
{
	if (!g_ctx || !path) { hr_set_err("not init"); return NULL; }
	struct hr_doc *hd = (struct hr_doc *)calloc(1, sizeof *hd);
	if (!hd) { hr_set_err("oom"); return NULL; }
	/* DJVU：引擎内 libdjvu 渲染（DocumentHandle.isDjvu 同口径，含 .djv），不经 mupdf */
	if (hr_ext_is(path, "djvu") || hr_ext_is(path, "djv"))
	{
		hd->isDjvu = 1;
		hd->dctx = hr_djv_create();
		hd->ddoc = hd->dctx ? hr_djv_open(hd->dctx, path) : NULL;
		if (!hd->ddoc)
		{
			if (hd->dctx) hr_djv_release_ctx(hd->dctx);
			free(hd);
			hr_set_err("djvu open failed");
			return NULL;
		}
		return hd;
	}
	fz_document *doc = NULL;
	hr_lock();
	fz_try(g_ctx)
		doc = fz_open_document(g_ctx, path);
	fz_catch(g_ctx)
	{
		hr_set_err(fz_caught_message(g_ctx));
		doc = NULL;
	}
	hr_unlock();
	if (!doc) { free(hd); return NULL; }
	hd->fz = doc;
	return hd;
}

int hr_qt_count(void *docv)
{
	struct hr_doc *hd = (struct hr_doc *)docv;
	if (!g_ctx || !hd) return 0;
	if (hd->isDjvu) return hr_djv_count(hd->ddoc);
	int n = 0;
	hr_lock();
	fz_try(g_ctx)
		n = fz_count_pages(g_ctx, hd->fz);
	fz_catch(g_ctx)
		n = 0;
	hr_unlock();
	return n;
}

/* P2 适配（B4）：分章计数（mupdf 标准 chapter API；djvu 恒 1 章） */
int hr_qt_count_chapters(void *docv)
{
	struct hr_doc *hd = (struct hr_doc *)docv;
	if (!g_ctx || !hd) return 0;
	if (hd->isDjvu) return 1;
	int n = 0;
	hr_lock();
	fz_try(g_ctx)
		n = fz_count_chapters(g_ctx, hd->fz);
	fz_catch(g_ctx)
		n = 0;
	hr_unlock();
	return n > 0 ? n : 1;
}

int hr_qt_count_pages_of(void *docv, int chapter)
{
	struct hr_doc *hd = (struct hr_doc *)docv;
	if (!g_ctx || !hd || hd->isDjvu) return 0;
	int n = 0;
	hr_lock();
	fz_try(g_ctx)
		n = fz_count_chapter_pages(g_ctx, hd->fz, chapter);
	fz_catch(g_ctx)
		n = 0;
	hr_unlock();
	return n;
}

/* P2 适配（B3）：排版加速器保存（fz_save_accelerator；0=ok） */
int hr_qt_save_accel(void *docv, const char *accelPath)
{
	struct hr_doc *hd = (struct hr_doc *)docv;
	if (!g_ctx || !hd || hd->isDjvu || !accelPath) return -1;
	int rc = -2;
	/* v0.110.1：锁内只写本地临时文件——加速器常落在 SMB/NFS 数据目录，
	 * 网络写一旦停顿会长期持有全局引擎锁，渲染/后台计数全部阻塞（滚动
	 * 模式整场空白页根因）；先落 %TEMP% 再在锁外移动到目标路径。 */
	char tm[520], tmp[1100];
	DWORD tn = GetTempPathA(sizeof(tm), tm);
	if (tn == 0 || tn >= sizeof(tm)) { lstrcpynA(tm, ".", 2); }
	snprintf(tmp, sizeof(tmp), "%shr_accel_%u.tmp", tm, (unsigned)GetCurrentProcessId());
	hr_lock();
	fz_try(g_ctx)
	{
		fz_save_accelerator(g_ctx, hd->fz, tmp);
		rc = 0;
	}
	fz_catch(g_ctx)
	{
		rc = -3;
	}
	hr_unlock();
	if (rc == 0)
	{
		DeleteFileA(accelPath);
		if (!MoveFileA(tmp, accelPath))
		{
			/* 跨卷移动失败→锁外复制（慢也不挡引擎） */
			if (!CopyFileA(tmp, accelPath, FALSE))
				rc = -4;
			DeleteFileA(tmp);
		}
	}
	return rc;
}

/* P2 适配（B3）：带加速器打开（损坏/缺失时返回 NULL，调用方回退普通打开） */
void *hr_qt_open_accel(const char *path, const char *accelPath)
{
	if (!g_ctx || !path || !accelPath) { hr_set_err("not init"); return NULL; }
	struct hr_doc *hd = (struct hr_doc *)calloc(1, sizeof *hd);
	if (!hd) { hr_set_err("oom"); return NULL; }
	fz_document *doc = NULL;
	hr_lock();
	fz_try(g_ctx)
		doc = fz_open_accelerated_document(g_ctx, path, accelPath);
	fz_catch(g_ctx)
	{
		hr_set_err(fz_caught_message(g_ctx));
		doc = NULL;
	}
	hr_unlock();
	if (!doc) { free(hd); return NULL; }
	hd->fz = doc;
	return hd;
}

int hr_qt_layout(void *docv, float w, float h, float em)
{
	struct hr_doc *hd = (struct hr_doc *)docv;
	if (!g_ctx || !hd) return -1;
	if (hd->isDjvu) return 0; /* djvu 无重排 */
	hr_lock();
	fz_try(g_ctx)
		fz_layout_document(g_ctx, hd->fz, w, h, em);
	fz_catch(g_ctx)
	{
		hr_set_err(fz_caught_message(g_ctx));
		hr_unlock();
		return -2;
	}
	hr_unlock();
	return 0;
}

/* 渲染管线与 DocumentHandle.render 逐行同构：fit-width + 40M 像素上限 + RGB 无 alpha
 * + 白底清屏 + draw device + NIGHT 反相 / tint 乘色（列序 = 源码同款）。 */
int hr_qt_render(void *docv, int page, int width, int mode, int tint,
                 int *outW, int *outH, unsigned char **outRGB)
{
	if (!g_ctx || !docv || !outW || !outH || !outRGB) return -1;
	struct hr_doc *hd = (struct hr_doc *)docv;
	if (hd->isDjvu)
	{
		unsigned int *px = NULL;
		int w = 0, h = 0;
		if (hr_djv_render(hd->dctx, hd->ddoc, page, width, &px, &w, &h) != 0 || !px)
		{
			hr_set_err("djvu render failed");
			return -2;
		}
		unsigned char *buf = (unsigned char *)malloc((size_t)w * h * 3);
		if (!buf) { free(px); return -3; }
		for (long long i = 0; i < (long long)w * h; i++)
		{
			int r = (int)((px[i] >> 16) & 255), g = (int)((px[i] >> 8) & 255), b = (int)(px[i] & 255);
			if (mode == 2) { r = 255 - r; g = 255 - g; b = 255 - b; }
			else if (tint != 0)
			{
				int tr = (tint >> 16) & 255, tg = (tint >> 8) & 255, tb2 = tint & 255;
				r = r * tr / 255; g = g * tg / 255; b = b * tb2 / 255;
			}
			buf[i * 3] = (unsigned char)r; buf[i * 3 + 1] = (unsigned char)g; buf[i * 3 + 2] = (unsigned char)b;
		}
		free(px);
		*outW = w; *outH = h; *outRGB = buf;
		return 0;
	}
	fz_page *p = NULL;
	fz_pixmap *pm = NULL;
	fz_device *dev = NULL;
	unsigned char *buf = NULL;
	int rc = 0;
	hr_lock();
	fz_var(p); fz_var(pm); fz_var(dev); fz_var(buf);
	fz_try(g_ctx)
	{
		p = fz_load_page(g_ctx, hd->fz, page);
		fz_rect b = fz_bound_page(g_ctx, p);
		float pw = b.x1 - b.x0, ph = b.y1 - b.y0;
		if (pw < 1.0f) pw = 1.0f;
		float scale = width / pw;
		if (pw > 0 && ph > 0 && (pw * scale) * (ph * scale) > 40000000.0f)
			scale *= sqrtf(40000000.0f / ((pw * scale) * (ph * scale)));
		fz_matrix ctm = fz_scale(scale, scale);
		fz_rect tb = fz_transform_rect(b, ctm);
		fz_irect ir = fz_round_rect(tb);
		pm = fz_new_pixmap_with_bbox(g_ctx, fz_device_rgb(g_ctx), ir, NULL, 0);
		fz_clear_pixmap_with_value(g_ctx, pm, 0xff);
		dev = fz_new_draw_device(g_ctx, ctm, pm);
		fz_run_page(g_ctx, p, dev, fz_identity, NULL);
		int w = pm->w, h = pm->h, n = pm->n;
		buf = (unsigned char *)malloc((size_t)w * h * 3);
		if (!buf) { rc = -3; }
		else
		{
			for (int y = 0; y < h; y++)
			{
				const unsigned char *s = pm->samples + (size_t)y * pm->stride;
				unsigned char *d = buf + (size_t)y * w * 3;
				for (int x = 0; x < w; x++)
				{
					int r = s[x * n], g = s[x * n + 1], bl = s[x * n + 2];
					if (mode == 2) { r = 255 - r; g = 255 - g; bl = 255 - bl; }
					else if (tint != 0)
					{
						int tr = (tint >> 16) & 255, tg = (tint >> 8) & 255, tb2 = tint & 255;
						r = r * tr / 255; g = g * tg / 255; bl = bl * tb2 / 255;
					}
					d[x * 3] = (unsigned char)r; d[x * 3 + 1] = (unsigned char)g; d[x * 3 + 2] = (unsigned char)bl;
				}
			}
			*outW = w; *outH = h; *outRGB = buf; buf = NULL;
		}
	}
	fz_always(g_ctx)
	{
		if (dev) fz_close_device(g_ctx, dev);
		if (dev) fz_drop_device(g_ctx, dev);
		if (pm) fz_drop_pixmap(g_ctx, pm);
		if (p) fz_drop_page(g_ctx, p);
	}
	fz_catch(g_ctx)
	{
		hr_set_err(fz_caught_message(g_ctx));
		rc = -2;
	}
	hr_unlock();
	if (buf) free(buf);
	return rc;
}

void hr_qt_close(void *docv)
{
	struct hr_doc *hd = (struct hr_doc *)docv;
	if (!hd) return;
	if (hd->isDjvu)
	{
		hr_djv_release_doc(hd->dctx, hd->ddoc);
		hr_djv_release_ctx(hd->dctx);
		free(hd);
		return;
	}
	if (g_ctx)
	{
		hr_lock();
		fz_drop_document(g_ctx, hd->fz);
		hr_unlock();
	}
	free(hd);
}

/* ---- M1 起新增 ---- */

/* outline：fz_load_outline 递归拍平；uri→resolve_link→page_number_from_location
 * （与 DocumentHandle.outline 同语义；uri 空且 o->page>=0 时直用）。 */
static int hr_outline_walk(fz_context *ctx, fz_document *doc, fz_outline *o, int level,
                           hr_outline_cb cb, void *ud)
{
	int n = 0;
	for (; o; o = o->next)
	{
		int page = -1;
		if (o->uri && o->uri[0])
		{
			fz_location loc = { -1, -1 };
			float xp = 0, yp = 0;
			fz_try(ctx)
				loc = fz_resolve_link(ctx, doc, o->uri, &xp, &yp);
			fz_catch(ctx) { loc.page = -1; }
			if (loc.page >= 0)
			{
				fz_try(ctx)
					page = fz_page_number_from_location(ctx, doc, loc);
				fz_catch(ctx) { page = -1; }
			}
		}
		else if (o->page.chapter == 0 && o->page.page >= 0)
		{
			page = o->page.page;
		}
		if (cb(ud, level, o->title ? o->title : "", page) == 0) return n;
		n++;
		if (o->down) n += hr_outline_walk(ctx, doc, o->down, level + 1, cb, ud);
	}
	return n;
}

int hr_qt_outline(void *docv, hr_outline_cb cb, void *ud)
{
	struct hr_doc *hd = (struct hr_doc *)docv;
	if (!g_ctx || !hd || !cb) return 0;
	if (hd->isDjvu) return 0; /* djvu 无目录（DocumentHandle 同 stub） */
	fz_outline *ol = NULL;
	int n = 0;
	hr_lock();
	fz_var(ol);
	fz_try(g_ctx)
	{
		ol = fz_load_outline(g_ctx, hd->fz);
		if (ol) n = hr_outline_walk(g_ctx, hd->fz, ol, 0, cb, ud);
	}
	fz_always(g_ctx)
	{
		if (ol) fz_drop_outline(g_ctx, ol);
	}
	fz_catch(g_ctx)
	{
		hr_set_err(fz_caught_message(g_ctx));
	}
	hr_unlock();
	return n;
}

int hr_qt_page_size(void *docv, int page, float *w, float *h)
{
	struct hr_doc *hd = (struct hr_doc *)docv;
	if (!g_ctx || !hd || !w || !h) return -1;
	if (hd->isDjvu)
	{
		int pw = 0, ph = 0;
		if (hr_djv_page_size(hd->dctx, hd->ddoc, page, &pw, &ph) != 0) return -2;
		*w = (float)pw; *h = (float)ph; /* djvu 页面像素≈pt（fit-width 同语义） */
		return 0;
	}
	fz_page *p = NULL;
	int rc = 0;
	hr_lock();
	fz_var(p);
	fz_try(g_ctx)
	{
		p = fz_load_page(g_ctx, hd->fz, page);
		fz_rect b = fz_bound_page(g_ctx, p);
		*w = b.x1 - b.x0;
		*h = b.y1 - b.y0;
	}
	fz_always(g_ctx)
	{
		if (p) fz_drop_page(g_ctx, p);
	}
	fz_catch(g_ctx)
	{
		hr_set_err(fz_caught_message(g_ctx));
		rc = -2;
	}
	hr_unlock();
	return rc;
}

const char *hr_qt_meta(void *docv, int key)
{
	static __thread char buf[512]; /* v0.107.0（P2-12）：线程局部，多线程桥调用不互踩 */
	buf[0] = 0;
	struct hr_doc *hd = (struct hr_doc *)docv;
	if (!g_ctx || !hd || hd->isDjvu) return buf; /* djvu 无元数据 */
	const char *k = (key == 0) ? "info:Title" : (key == 1) ? "info:Author" : NULL;
	if (!k) return buf;
	hr_lock();
	fz_try(g_ctx)
	{
		char tmp[512] = {0};
		int n = fz_lookup_metadata(g_ctx, hd->fz, k, tmp, (int)sizeof tmp);
		if (n > 0) snprintf(buf, sizeof buf, "%s", tmp);
	}
	fz_catch(g_ctx) { buf[0] = 0; }
	hr_unlock();
	return buf;
}

/* ---- M3 远程流式打开 ---- */

struct hr_stream_state
{
	hr_stream_read cb;
	void *ud;
	long long size;
	long long pos;
	unsigned char *bp; /* 自持缓冲（mupdf 1.23 fz_stream 无内建缓冲指针） */
	int cap;
};

static int hr_stream_next(fz_context *ctx, fz_stream *stm, size_t max)
{
	struct hr_stream_state *s = (struct hr_stream_state *)stm->state;
	static int dbg = -1;
	if (dbg < 0) dbg = getenv("HR_STREAM_DEBUG") ? 1 : 0;
	if (s->pos >= s->size) { if (dbg) fprintf(stderr, "[hstream] next EOF pos=%lld\n", (long long)s->pos); return EOF; }
	if (max > (size_t)s->cap) max = (size_t)s->cap;
	if (max > (size_t)(s->size - s->pos)) max = (size_t)(s->size - s->pos);
	if (max == 0) return EOF;
	int n = s->cb(s->ud, s->pos, s->bp, (int)max);
	if (dbg)
		fprintf(stderr, "[hstream] next pos=%lld max=%d n=%d b0=%02x b1=%02x b2=%02x b3=%02x\n",
				(long long)s->pos, (int)max, n, s->bp[0], s->bp[1],
				n > 2 ? s->bp[2] : 0, n > 3 ? s->bp[3] : 0);
	if (n <= 0) return EOF;
	stm->rp = s->bp;
	stm->wp = s->bp + n;
	s->pos += n;
	stm->pos += n; /* fz_tell = pos - (wp-rp)：与 hr_openstream.c 同款（pos=缓冲末端） */
	return *stm->rp++; /* 契约：返回首字节且 rp 前移一位（fz_available 靠 rp-- 回点计数） */
}

static void hr_stream_seek(fz_context *ctx, fz_stream *stm, int64_t offset, int whence)
{
	struct hr_stream_state *s = (struct hr_stream_state *)stm->state;
	switch (whence)
	{
	case SEEK_SET: s->pos = offset; break;
	case SEEK_CUR: s->pos += offset; break;
	case SEEK_END: s->pos = s->size + offset; break;
	default: break;
	}
	if (s->pos < 0) s->pos = 0;
	stm->pos = s->pos;
	/* 关键：清掉旧缓冲指针，否则 seek 后 mupdf 仍在读 stale 字节（magic 识别必错） */
	stm->rp = NULL;
	stm->wp = NULL;
	stm->avail = 0;
	stm->eof = 0;
}

static void hr_stream_drop(fz_context *ctx, void *state)
{
	struct hr_stream_state *s = (struct hr_stream_state *)state;
	if (s) free(s->bp);
	free(state);
}

void *hr_qt_open_stream(const char *magic, long long size, hr_stream_read cb, void *ud)
{
	if (!g_ctx || !magic || !cb || size <= 0) { hr_set_err("open_stream: bad args"); return NULL; }
	struct hr_doc *hd = (struct hr_doc *)calloc(1, sizeof *hd);
	if (!hd) { hr_set_err("open_stream: oom"); return NULL; }
	struct hr_stream_state *s = (struct hr_stream_state *)malloc(sizeof *s);
	if (!s) { free(hd); hr_set_err("open_stream: oom"); return NULL; }
	s->cb = cb; s->ud = ud; s->size = size; s->pos = 0;
	s->cap = 4096;
	s->bp = (unsigned char *)malloc(s->cap);
	if (!s->bp) { free(s); free(hd); hr_set_err("open_stream: oom2"); return NULL; }
	fz_stream *stm = NULL;
	fz_document *doc = NULL;
	hr_lock();
	fz_var(stm);
	fz_try(g_ctx)
	{
		stm = fz_new_stream(g_ctx, s, hr_stream_next, hr_stream_drop);
		stm->seek = hr_stream_seek;
		doc = fz_open_document_with_stream(g_ctx, magic, stm);
	}
	fz_always(g_ctx)
	{
		if (stm) fz_drop_stream(g_ctx, stm);
	}
	fz_catch(g_ctx)
	{
		hr_set_err(fz_caught_message(g_ctx));
		doc = NULL;
	}
	hr_unlock();
	if (!doc) { free(hd); return NULL; }
	hd->fz = doc;
	return hd;
}

/* ---- M4 划词 / 搜索 / 段落 / PDF 注解（mupdf 1.23 结构名） ---- */

static int hr_is_cjk_cp(unsigned int cp)
{
	return (cp >= 0x2E80 && cp <= 0x9FFF) || (cp >= 0x3000 && cp <= 0x30FF) ||
		   (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0xFF00 && cp <= 0xFFEF);
}

static int hr_put_utf8(char *buf, unsigned int cp)
{
	if (cp < 0x80) { buf[0] = (char)cp; return 1; }
	if (cp < 0x800) { buf[0] = (char)(0xC0 | (cp >> 6)); buf[1] = (char)(0x80 | (cp & 63)); return 2; }
	if (cp < 0x10000)
	{
		buf[0] = (char)(0xE0 | (cp >> 12));
		buf[1] = (char)(0x80 | ((cp >> 6) & 63));
		buf[2] = (char)(0x80 | (cp & 63));
		return 3;
	}
	buf[0] = (char)(0xF0 | (cp >> 18));
	buf[1] = (char)(0x80 | ((cp >> 12) & 63));
	buf[2] = (char)(0x80 | ((cp >> 6) & 63));
	buf[3] = (char)(0x80 | (cp & 63));
	return 4;
}

static int hr_stext_fill(fz_context *ctx, fz_document *docv, int page, fz_stext_page **outSt,
                         fz_page **outPage)
{
	fz_page *p = fz_load_page(ctx, docv, page);
	fz_stext_page *st = fz_new_stext_page(ctx, fz_bound_page(ctx, p));
	fz_device *dev = fz_new_stext_device(ctx, st, NULL);
	fz_run_page(ctx, p, dev, fz_identity, NULL);
	fz_close_device(ctx, dev);
	fz_drop_device(ctx, dev);
	*outSt = st;
	*outPage = p;
	return 0;
}

int hr_qt_selection(void *docv, int page, float ax, float ay, float bx, float by,
                    char **outText, float **outRects, int *outRectN)
{
	if (!g_ctx || !docv || !outText || !outRects || !outRectN) return -1;
	*outText = NULL; *outRects = NULL; *outRectN = 0;
	struct hr_doc *hd = (struct hr_doc *)docv;
	if (hd->isDjvu)
	{
		*outText = (char *)calloc(1, 1); /* djvu 无文字层（DocumentHandle 同 stub） */
		return 0;
	}
	fz_page *p = NULL;
	fz_stext_page *st = NULL;
	char *text = NULL;
	float *rects = NULL;
	int rn = 0, rc = 0;
	hr_lock();
	fz_var(p); fz_var(st); fz_var(text); fz_var(rects);
	fz_try(g_ctx)
	{
		hr_stext_fill(g_ctx, hd->fz, page, &st, &p);
		fz_point a = { ax, ay }, b = { bx, by };
		char *sel = fz_copy_selection(g_ctx, st, a, b, 1);
		text = sel ? sel : (char *)calloc(1, 1);
		int cap = 16;
		rects = (float *)malloc(sizeof(float) * 4 * cap);
		for (fz_stext_block *blk = st->first_block; blk; blk = blk->next)
		{
			if (blk->type != FZ_STEXT_BLOCK_TEXT) continue;
			for (fz_stext_line *ln = blk->u.t.first_line; ln; ln = ln->next)
			{
				if (rn == cap)
				{
					cap *= 2;
					rects = (float *)realloc(rects, sizeof(float) * 4 * cap);
				}
				rects[rn * 4 + 0] = ln->bbox.x0; rects[rn * 4 + 1] = ln->bbox.y0;
				rects[rn * 4 + 2] = ln->bbox.x1; rects[rn * 4 + 3] = ln->bbox.y1;
				rn++;
			}
		}
		*outText = text; text = NULL;
		*outRects = rects; rects = NULL;
		*outRectN = rn;
	}
	fz_always(g_ctx)
	{
		if (st) fz_drop_stext_page(g_ctx, st);
		if (p) fz_drop_page(g_ctx, p);
		if (text) free(text);
		if (rects) free(rects);
	}
	fz_catch(g_ctx)
	{
		hr_set_err(fz_caught_message(g_ctx));
		rc = -2;
	}
	hr_unlock();
	return rc;
}

int hr_qt_page_box(void *docv, int page, float box[4])
{
	if (!g_ctx || !docv || !box) return -1;
	struct hr_doc *hd = (struct hr_doc *)docv;
	if (hd->isDjvu) { box[0] = box[1] = box[2] = box[3] = 0; return -1; }
	fz_page *p = NULL;
	int rc = 0;
	hr_lock();
	fz_var(p);
	fz_try(g_ctx)
	{
		p = fz_load_page(g_ctx, hd->fz, page);
		fz_rect b = fz_bound_page(g_ctx, p);
		box[0] = b.x0; box[1] = b.y0; box[2] = b.x1; box[3] = b.y1;
	}
	fz_always(g_ctx)
	{
		if (p) fz_drop_page(g_ctx, p);
	}
	fz_catch(g_ctx)
	{
		hr_set_err(fz_caught_message(g_ctx));
		rc = -2;
	}
	hr_unlock();
	return rc;
}

int hr_qt_search_page(void *docv, int page, const char *needle)
{
	struct hr_doc *hd = (struct hr_doc *)docv;
	if (!g_ctx || !hd || !needle || !needle[0] || hd->isDjvu) return 0;
	fz_page *p = NULL;
	fz_stext_page *st = NULL;
	int hits = 0;
	hr_lock();
	fz_var(p); fz_var(st);
	fz_try(g_ctx)
	{
		hr_stext_fill(g_ctx, hd->fz, page, &st, &p);
		fz_quad quads[64];
		int n = fz_search_stext_page(g_ctx, st, needle, NULL, quads, nelem(quads));
		hits = n > 0 ? n : 0;
	}
	fz_always(g_ctx)
	{
		if (st) fz_drop_stext_page(g_ctx, st);
		if (p) fz_drop_page(g_ctx, p);
	}
	fz_catch(g_ctx)
	{
		hits = 0;
	}
	hr_unlock();
	return hits;
}

int hr_qt_page_paragraphs(void *docv, int page, hr_paras_cb cb, void *ud)
{
	struct hr_doc *hd = (struct hr_doc *)docv;
	if (!g_ctx || !hd || !cb || hd->isDjvu) return 0; /* djvu 无段落 */
	fz_page *p = NULL;
	fz_stext_page *st = NULL;
	int count = 0;
	hr_lock();
	fz_var(p); fz_var(st);
	fz_try(g_ctx)
	{
		hr_stext_fill(g_ctx, hd->fz, page, &st, &p);
		for (fz_stext_block *blk = st->first_block; blk; blk = blk->next)
		{
			if (blk->type != FZ_STEXT_BLOCK_TEXT) continue;
			size_t cap = 4096, len = 0;
			char *buf = (char *)malloc(cap);
			for (fz_stext_line *ln = blk->u.t.first_line; ln; ln = ln->next)
			{
				for (fz_stext_char *ch = ln->first_char; ch; ch = ch->next)
				{
					char u8[5];
					int m = hr_put_utf8(u8, ch->c);
					if (len + (size_t)m + 1 > cap)
					{
						cap *= 2;
						buf = (char *)realloc(buf, cap);
					}
					memcpy(buf + len, u8, (size_t)m);
					len += (size_t)m;
				}
				/* 行间空格：行尾字符非 CJK 时补（行拼接语义同 DocumentHandle） */
				if (ln->next && len > 0)
				{
					const unsigned char *tail = (const unsigned char *)buf + len - 1;
					while (tail > (const unsigned char *)buf && (*tail & 0xC0) == 0x80) tail--;
					unsigned int cp = (unsigned int)*tail;
					if (!hr_is_cjk_cp(cp) && cp < 0x80)
					{
						if (len + 1 > cap)
						{
							cap *= 2;
							buf = (char *)realloc(buf, cap);
						}
						buf[len++] = ' ';
					}
				}
			}
			buf[len] = 0;
			count++;
			int stop = cb(ud, buf);
			free(buf);
			if (!stop) break;
		}
	}
	fz_always(g_ctx)
	{
		if (st) fz_drop_stext_page(g_ctx, st);
		if (p) fz_drop_page(g_ctx, p);
	}
	fz_catch(g_ctx)
	{
		hr_set_err(fz_caught_message(g_ctx));
	}
	hr_unlock();
	return count;
}

int hr_qt_pdf_export_highlights(void *docv, const char *destPath,
                                const int *pages, const int *argbs,
                                const float *const *quadsArr, const int *quadNs, int nItems)
{
	struct hr_doc *hd = (struct hr_doc *)docv;
	if (!g_ctx || !hd || !destPath || !pages || !argbs || !quadsArr || !quadNs) return 0;
	if (hd->isDjvu) return 0; /* djvu 非 PDF */
	int ok = 0;
	hr_lock();
	fz_try(g_ctx)
	{
		pdf_document *pdf = pdf_specifics(g_ctx, hd->fz);
		if (pdf)
		{
			for (int i = 0; i < nItems; i++)
			{
				fz_page *pg = fz_load_page(g_ctx, hd->fz, pages[i]);
				pdf_page *pp = pdf_page_from_fz_page(g_ctx, pg);
				if (pp)
				{
					pdf_annot *ann = pdf_create_annot(g_ctx, pp, PDF_ANNOT_HIGHLIGHT);
					const float *q = quadsArr[i];
					fz_quad *fqs =
						(fz_quad *)malloc(sizeof(fz_quad) * (size_t)(quadNs[i] > 0 ? quadNs[i] : 1));
					for (int k = 0; k < quadNs[i]; k++)
					{
						fqs[k].ul = fz_make_point(q[k * 4 + 0], q[k * 4 + 3]);
						fqs[k].ur = fz_make_point(q[k * 4 + 2], q[k * 4 + 3]);
						fqs[k].ll = fz_make_point(q[k * 4 + 0], q[k * 4 + 1]);
						fqs[k].lr = fz_make_point(q[k * 4 + 2], q[k * 4 + 1]);
					}
					pdf_set_annot_quad_points(g_ctx, ann, quadNs[i], fqs);
					free(fqs);
					float rgb[3] = { ((argbs[i] >> 16) & 255) / 255.0f,
									 ((argbs[i] >> 8) & 255) / 255.0f,
									 (argbs[i] & 255) / 255.0f };
					pdf_set_annot_color(g_ctx, ann, 3, rgb);
					pdf_set_annot_opacity(g_ctx, ann, 0.5f);
					pdf_update_annot(g_ctx, ann);
				}
				if (pg) fz_drop_page(g_ctx, pg);
			}
			pdf_save_document(g_ctx, pdf, destPath, NULL);
			ok = 1;
		}
	}
	fz_catch(g_ctx)
	{
		hr_set_err(fz_caught_message(g_ctx));
		ok = 0;
	}
	hr_unlock();
	return ok;
}

int hr_qt_pdf_annotation_count(void *docv, int page)
{
	struct hr_doc *hd = (struct hr_doc *)docv;
	if (!g_ctx || !hd) return -1;
	if (hd->isDjvu) return -1;
	int n = -1;
	hr_lock();
	fz_try(g_ctx)
	{
		pdf_document *pdf = pdf_specifics(g_ctx, hd->fz);
		if (!pdf)
		{
			n = -1;
		}
		else
		{
			fz_page *pg = fz_load_page(g_ctx, hd->fz, page);
			pdf_page *pp = pdf_page_from_fz_page(g_ctx, pg);
			n = 0;
			if (pp)
				for (pdf_annot *a = pdf_first_annot(g_ctx, pp); a; a = pdf_next_annot(g_ctx, a))
					n++;
			if (pg) fz_drop_page(g_ctx, pg);
		}
	}
	fz_catch(g_ctx)
	{
		n = -1;
	}
	hr_unlock();
	return n;
}
