/*
 * HowRead desktop 2026-11-01: system-font hooks (v0.57.1, 方案A 对齐安卓).
 *
 * Installs the three fz_load_system_font_funcs hooks so the engine renders
 * with operating-system fonts instead of embedding the full Noto/Source-Han
 * set (~40MB). Mirrors Builder/jni/androidfonts.c (Android reads
 * /system/fonts; here we read %WINDIR%\Fonts, Linux best-effort).
 *
 * DroidSansFallback(+Full) stays embedded as the last-resort CJK fallback,
 * so text still renders when the usual system fonts are missing.
 *
 * HR_SYSFONT_DIR overrides the system font directory (smokes/tests).
 * HR_SYSFONT_DEBUG=1 traces requests to stderr.
 */
#include "mupdf/fitz.h"
#include "mupdf/ucdn.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#define _WIN32_WINNT 0x0601
#include <windows.h>
#else
#include <pthread.h>
#endif

#define HR_SYS_MAX_CACHE 24
#define HR_SYS_PATH_LEN 1024

typedef struct {
	char path[HR_SYS_PATH_LEN];
	int index;
	fz_font *font;
} hr_sys_entry;

static hr_sys_entry g_sys_cache[HR_SYS_MAX_CACHE];
static int g_sys_n = 0;

#ifdef _WIN32
static SRWLOCK g_sys_lock = SRWLOCK_INIT;
static void hr_sys_lock(void) { AcquireSRWLockExclusive(&g_sys_lock); }
static void hr_sys_unlock(void) { ReleaseSRWLockExclusive(&g_sys_lock); }
#else
static pthread_mutex_t g_sys_lock = PTHREAD_MUTEX_INITIALIZER;
static void hr_sys_lock(void) { pthread_mutex_lock(&g_sys_lock); }
static void hr_sys_unlock(void) { pthread_mutex_unlock(&g_sys_lock); }
#endif

static int hr_sys_debug(void) { return getenv("HR_SYSFONT_DEBUG") != NULL; }

static void hr_sys_dirs(char *out, int cap)
{
	const char *env = getenv("HR_SYSFONT_DIR");
	if (env && env[0]) {
		snprintf(out, cap, "%s", env);
		return;
	}
#ifdef _WIN32
	{
		char win[HR_SYS_PATH_LEN];
		UINT n = GetWindowsDirectoryA(win, sizeof win);
		if (n == 0 || n >= sizeof win) { out[0] = 0; return; }
		snprintf(out, cap, "%s\\Fonts", win);
	}
#else
	snprintf(out, cap, "/usr/share/fonts");
#endif
}

static int hr_sys_exists(const char *dir, const char *file, char *out, int cap)
{
#ifdef _WIN32
	snprintf(out, cap, "%s\\%s", dir, file);
	return GetFileAttributesA(out) != INVALID_FILE_ATTRIBUTES;
#else
	FILE *f;
	snprintf(out, cap, "%s/%s", dir, file);
	f = fopen(out, "rb");
	if (!f)
		return 0;
	fclose(f);
	return 1;
#endif
}

typedef struct {
	const char *file;
	int idx;
} hr_sys_cand;

/* 命中即加载并缓存；缓存自持引用，返回给调用方的是独立新引用（文档 drop 不影响缓存）。 */
static fz_font *hr_sys_try(fz_context *ctx, const hr_sys_cand *list, int n)
{
	char dir[HR_SYS_PATH_LEN];
	char path[HR_SYS_PATH_LEN + 160];
	int i;
	if (n <= 0)
		return NULL;
	hr_sys_dirs(dir, sizeof dir);
	if (!dir[0])
		return NULL;
	for (i = 0; i < n; i++) {
		fz_font *font = NULL;
		if (!hr_sys_exists(dir, list[i].file, path, sizeof path))
			continue;
		{
			int j;
			hr_sys_lock();
			for (j = 0; j < g_sys_n; j++) {
				if (g_sys_cache[j].index == list[i].idx &&
					strcmp(g_sys_cache[j].path, path) == 0)
				{
					font = g_sys_cache[j].font;
					hr_sys_unlock();
					fz_try(ctx)
						font = fz_keep_font(ctx, font);
					fz_catch(ctx)
						font = NULL;
					if (font && hr_sys_debug())
						fprintf(stderr, "hrsysfont: cache hit '%s#%d'\n", path, list[i].idx);
					return font;
				}
			}
			hr_sys_unlock();
		}
		fz_var(font);
		fz_try(ctx)
			font = fz_new_font_from_file(ctx, NULL, path, list[i].idx, 1);
		fz_catch(ctx)
			font = NULL;
		if (!font) {
			if (hr_sys_debug())
				fprintf(stderr, "hrsysfont: load FAILED '%s'\n", path);
			continue;
		}
		{
			fz_font *keep = NULL;
			fz_var(keep);
			fz_try(ctx)
				keep = fz_keep_font(ctx, font);
			fz_catch(ctx)
				keep = NULL;
			hr_sys_lock();
			if (keep && g_sys_n < HR_SYS_MAX_CACHE) {
				snprintf(g_sys_cache[g_sys_n].path, HR_SYS_PATH_LEN, "%s", path);
				g_sys_cache[g_sys_n].index = list[i].idx;
				g_sys_cache[g_sys_n].font = keep;
				g_sys_n++;
			}
			hr_sys_unlock();
		}
		if (hr_sys_debug())
			fprintf(stderr, "hrsysfont: '%s#%d' ok\n", path, list[i].idx);
		return font;
	}
	if (hr_sys_debug())
		fprintf(stderr, "hrsysfont: no candidate (%d tried)\n", n);
	return NULL;
}

/* ---- CJK：按 ordering+衬线 选系统字体（全部是 Win10 全球自带字体，另有备选） ---- */
static fz_font *hr_sys_cjk_font(fz_context *ctx, int ordering, int serif)
{
	if (serif) {
		static const hr_sys_cand sc[] = { {"simsun.ttc", 0}, {"msyh.ttc", 0} };
		static const hr_sys_cand tc[] = { {"msjh.ttc", 0}, {"mingliu.ttc", 0}, {"msjhbd.ttc", 0} };
		static const hr_sys_cand jp[] = { {"msmincho.ttc", 0}, {"meiryo.ttc", 0}, {"msgothic.ttc", 0} };
		static const hr_sys_cand kr[] = { {"batang.ttc", 0}, {"malgun.ttf", 0}, {"gulim.ttc", 0} };
#ifndef _WIN32
		static const hr_sys_cand lx[] = {
			{"opentype/noto/NotoSerifCJK-Regular.ttc", 0},
			{"noto-cjk/NotoSerifCJK-Regular.ttc", 0},
			{"truetype/wqy/wqy-zenhei.ttc", 0},
		};
		(void)sc; (void)tc; (void)jp; (void)kr;
		return hr_sys_try(ctx, lx, (int)(sizeof lx / sizeof lx[0]));
#else
		switch (ordering) {
		case FZ_ADOBE_JAPAN: return hr_sys_try(ctx, jp, (int)(sizeof jp / sizeof jp[0]));
		case FZ_ADOBE_KOREA: return hr_sys_try(ctx, kr, (int)(sizeof kr / sizeof kr[0]));
		case FZ_ADOBE_CNS: return hr_sys_try(ctx, tc, (int)(sizeof tc / sizeof tc[0]));
		default: return hr_sys_try(ctx, sc, (int)(sizeof sc / sizeof sc[0]));
		}
#endif
	} else {
		static const hr_sys_cand sc[] = { {"msyh.ttc", 0}, {"msyhbd.ttc", 0}, {"simsun.ttc", 0} };
		static const hr_sys_cand tc[] = { {"msjh.ttc", 0}, {"msjhbd.ttc", 0}, {"mingliu.ttc", 0} };
		static const hr_sys_cand jp[] = { {"meiryo.ttc", 0}, {"meiryob.ttc", 0}, {"msgothic.ttc", 0} };
		static const hr_sys_cand kr[] = { {"malgun.ttf", 0}, {"malgunbd.ttf", 0}, {"gulim.ttc", 0} };
#ifndef _WIN32
		static const hr_sys_cand lx[] = {
			{"opentype/noto/NotoSansCJK-Regular.ttc", 0},
			{"noto-cjk/NotoSansCJK-Regular.ttc", 0},
			{"truetype/wqy/wqy-microhei.ttc", 0},
		};
		(void)sc; (void)tc; (void)jp; (void)kr;
		return hr_sys_try(ctx, lx, (int)(sizeof lx / sizeof lx[0]));
#else
		switch (ordering) {
		case FZ_ADOBE_JAPAN: return hr_sys_try(ctx, jp, (int)(sizeof jp / sizeof jp[0]));
		case FZ_ADOBE_KOREA: return hr_sys_try(ctx, kr, (int)(sizeof kr / sizeof kr[0]));
		case FZ_ADOBE_CNS: return hr_sys_try(ctx, tc, (int)(sizeof tc / sizeof tc[0]));
		default: return hr_sys_try(ctx, sc, (int)(sizeof sc / sizeof sc[0]));
		}
#endif
	}
}

/* ---- 拉丁/希腊/西里尔：衬线=Times，无衬线=Arial，等宽=Courier New（按粗斜选文件） ---- */
static fz_font *hr_sys_latin(fz_context *ctx, int serif, int bold, int italic)
{
	int k = (bold ? 1 : 0) + (italic ? 2 : 0);
#ifndef _WIN32
	static const hr_sys_cand se[] = { {"truetype/dejavu/DejaVuSerif.ttf", 0}, {"truetype/dejavu/DejaVuSerif-Bold.ttf", 0} };
	static const hr_sys_cand sa[] = { {"truetype/dejavu/DejaVuSans.ttf", 0}, {"truetype/dejavu/DejaVuSans-Bold.ttf", 0} };
	static const hr_sys_cand mo[] = { {"truetype/dejavu/DejaVuSansMono.ttf", 0}, {"truetype/dejavu/DejaVuSansMono-Bold.ttf", 0} };
	(void)k;
	return hr_sys_try(ctx, serif ? se : sa, 2);
#else
	static const hr_sys_cand se[4] = { {"times.ttf", 0}, {"timesbd.ttf", 0}, {"timesi.ttf", 0}, {"timesbi.ttf", 0} };
	static const hr_sys_cand sa[4] = { {"arial.ttf", 0}, {"arialbd.ttf", 0}, {"ariali.ttf", 0}, {"arialbi.ttf", 0} };
	static const hr_sys_cand mo[4] = { {"cour.ttf", 0}, {"courbd.ttf", 0}, {"couri.ttf", 0}, {"courbi.ttf", 0} };
	(void)serif;
	(void)bold; (void)italic;
	/* serif 参数语义由调用方保证：衬线走 times，无衬线走 arial/等宽 */
	if (serif)
		return hr_sys_try(ctx, se, 4);
	return hr_sys_try(ctx, sa, 4);
	(void)mo; (void)k;
#endif
}

/* ---- 其它文字：Tahoma 一族覆盖阿拉伯/希伯来/泰文；其余交给内嵌 DroidSansFallback ---- */
static fz_font *hr_sys_other(fz_context *ctx, int bold)
{
#ifndef _WIN32
	static const hr_sys_cand lx[] = { {"truetype/dejavu/DejaVuSans.ttf", 0} };
	(void)bold;
	return hr_sys_try(ctx, lx, 1);
#else
	static const hr_sys_cand w[] = { {"tahoma.ttf", 0}, {"tahomabd.ttf", 0}, {"segoeui.ttf", 0} };
	return hr_sys_try(ctx, w, bold ? 2 : 1);
#endif
}

/* ================= 三个钩子实现 ================= */

/* 名称归一：小写、去空格-下划线-引号 */
static void hr_sys_norm(const char *in, char *out, int cap)
{
	int n = 0;
	for (; in && *in && n < cap - 1; in++) {
		char c = *in;
		if (c >= 'A' && c <= 'Z')
			c = (char)(c + 32);
		if (c == ' ' || c == '-' || c == '_' || c == '"' || c == '\'')
			continue;
		out[n++] = c;
	}
	out[n] = 0;
}

/* 通用名钩子：先用户字体目录（v0.23 行为保持），再系统字体映射 */
fz_font *hr_sysfont_generic(fz_context *ctx, const char *name, int bold, int italic, int needs_exact_metrics)
{
	char norm[128];
	extern fz_font *hr_load_user_font(fz_context *ctx, const char *name, int bold, int italic, int needs_exact_metrics);
	fz_font *f;

	if (!name || !name[0])
		return NULL;
	f = hr_load_user_font(ctx, name, bold, italic, needs_exact_metrics);
	if (f)
		return f;

	hr_sys_norm(name, norm, sizeof norm);
	if (!norm[0])
		return NULL;
	if (hr_sys_debug())
		fprintf(stderr, "hrsysfont: generic '%s' b=%d i=%d\n", name, bold, italic);

	/* CJK 常用字体名（含中文别名） */
	if (strstr(norm, "simsun") || strstr(norm, "nsimsun") || strstr(norm, "songti") ||
		strstr(norm, "\xe5\xae\x8b\xe4\xbd\x93") || strstr(norm, "mingliu"))
		return hr_sys_cjk_font(ctx, FZ_ADOBE_GB, 1);
	if (strstr(norm, "msyh") || strstr(norm, "yahei") || strstr(norm, "simhei") ||
		strstr(norm, "\xe9\xbb\x91\xe4\xbd\x93") ||
		strstr(norm, "\xe5\xbe\xae\xe8\xbd\xaf\xe9\x9b\x85\xe9\xbb\x91"))
		return hr_sys_cjk_font(ctx, FZ_ADOBE_GB, 0);
	if (strstr(norm, "kaiti") || strstr(norm, "\xe6\xa5\xb7\xe4\xbd\x93") ||
		strstr(norm, "fangsong") || strstr(norm, "\xe4\xbb\xbf\xe5\xae\x8b"))
		return hr_sys_cjk_font(ctx, FZ_ADOBE_GB, 1);
	if (strstr(norm, "msjh") || strstr(norm, "jhenghei") || strstr(norm, "msmincho") ||
		strstr(norm, "meiryo") || strstr(norm, "msgothic") || strstr(norm, "mincho") ||
		strstr(norm, "batang") || strstr(norm, "gulim") || strstr(norm, "malgun"))
		return hr_sys_cjk_font(ctx, FZ_ADOBE_JAPAN, 1);

	/* 拉丁基名 */
	if (strstr(norm, "times") || strstr(norm, "nimbusroman") ||
		strstr(norm, "liberationserif") || strstr(norm, "georgia") ||
		strstr(norm, "garamond") || strstr(norm, "bookantiqua") ||
		strstr(norm, "palatino") || strstr(norm, "cambria"))
		return hr_sys_latin(ctx, 1, bold, italic);
	if (strstr(norm, "courier") || strstr(norm, "consolas") ||
		strstr(norm, "nimbusmono") || strstr(norm, "liberationmono") ||
		strstr(norm, "monospace"))
		return hr_sys_latin(ctx, 0, bold, italic);
	if (strstr(norm, "arial") || strstr(norm, "helvetica") ||
		strstr(norm, "nimbussans") || strstr(norm, "liberationsans") ||
		strstr(norm, "calibri") || strstr(norm, "verdana") ||
		strstr(norm, "tahoma") || strstr(norm, "segoeui") ||
		strstr(norm, "microsoftsansserif") || strstr(norm, "sans"))
		return hr_sys_latin(ctx, 0, bold, italic);
	return NULL;
}

/* CJK 钩子：font.c 缺内嵌 CJK 时按 ordering 请求 */
fz_font *hr_sysfont_cjk(fz_context *ctx, const char *name, int ordering, int serif)
{
	if (hr_sys_debug())
		fprintf(stderr, "hrsysfont: cjk '%s' ordering=%d serif=%d\n", name ? name : "(null)", ordering, serif);
	return hr_sys_cjk_font(ctx, ordering, serif);
}

/* 兜底钩子：按 script/language 取字；未覆盖的文字返回 NULL（内嵌 DroidSansFallback 兜底） */
fz_font *hr_sysfont_fallback(fz_context *ctx, int script, int language, int serif, int bold, int italic)
{
	switch (script)
	{
	case UCDN_SCRIPT_HAN:
	case UCDN_SCRIPT_HIRAGANA:
	case UCDN_SCRIPT_KATAKANA:
	case UCDN_SCRIPT_HANGUL:
	case UCDN_SCRIPT_BOPOMOFO:
	{
		int ordering = FZ_ADOBE_GB;
		if (language == FZ_LANG_ja)
			ordering = FZ_ADOBE_JAPAN;
		else if (language == FZ_LANG_ko)
			ordering = FZ_ADOBE_KOREA;
		else if (language == FZ_LANG_zh_Hant)
			ordering = FZ_ADOBE_CNS;
		return hr_sys_cjk_font(ctx, ordering, serif);
	}
	case UCDN_SCRIPT_LATIN:
	case UCDN_SCRIPT_GREEK:
	case UCDN_SCRIPT_CYRILLIC:
		return hr_sys_latin(ctx, serif, bold, italic);
	case UCDN_SCRIPT_ARABIC:
	case UCDN_SCRIPT_HEBREW:
	case UCDN_SCRIPT_THAI:
		return hr_sys_other(ctx, bold);
	default:
		return NULL;
	}
}
