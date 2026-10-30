/*
 * HowRead desktop 2026-10-26: external user-font loader (v0.23.0).
 *
 * Serves the HTML/EPUB layout engine (html-font.c fallback) and the PDF
 * interpreter (installed as the system-font hook in platform/java/jni/context.c)
 * with fonts from a user directory:
 *   1. $HR_FONTS_DIR           (override, used by smokes/tests)
 *   2. %LOCALAPPDATA%\HowRead\fonts   (Windows)
 *   3. ~/.howread/fonts        (Linux)
 * A file matches when its base name (case/space/dash/underscore-insensitive,
 * extension stripped) equals the requested font-family name. Matched fonts are
 * loaded with fz_new_font_from_file and cached for the process lifetime.
 * Returns NULL when nothing matches, so built-in fonts still apply.
 * Note: matching works on raw bytes; non-ASCII filenames need a matching
 * system codepage -- ASCII filenames are the supported path.
 */
#include "mupdf/fitz.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#define _WIN32_WINNT 0x0601
#include <windows.h>
#else
#include <dirent.h>
#include <pthread.h>
#endif

#define HR_MAX_CACHE 32
#define HR_NAME_LEN 128
#define HR_PATH_LEN 1024

typedef struct {
	char norm[HR_NAME_LEN];
	char path[HR_PATH_LEN];
	fz_font *font;
} hr_entry;

static hr_entry g_cache[HR_MAX_CACHE];
static int g_n = 0;

#ifdef _WIN32
static SRWLOCK g_lock = SRWLOCK_INIT;
static void hr_lock(void) { AcquireSRWLockExclusive(&g_lock); }
static void hr_unlock(void) { ReleaseSRWLockExclusive(&g_lock); }
#else
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static void hr_lock(void) { pthread_mutex_lock(&g_lock); }
static void hr_unlock(void) { pthread_mutex_unlock(&g_lock); }
#endif

static void hr_fonts_dir(char *out, int cap)
{
	const char *env = getenv("HR_FONTS_DIR");
	if (env && env[0]) {
		snprintf(out, cap, "%s", env);
		return;
	}
#ifdef _WIN32
	{
		const char *la = getenv("LOCALAPPDATA");
		if (la && la[0])
			snprintf(out, cap, "%s\\HowRead\\fonts", la);
		else
			snprintf(out, cap, "C:\\HowRead\\fonts");
	}
#else
	{
		const char *home = getenv("HOME");
		if (home && home[0])
			snprintf(out, cap, "%s/.howread/fonts", home);
		else
			snprintf(out, cap, "/tmp/howread-fonts");
	}
#endif
}

/* lowercase A-Z, drop ' ' '-' '_' and CSS quotes; other bytes pass through */
static void hr_normalize(const char *in, char *out, int cap)
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

static int hr_font_ext(const char *name)
{
	size_t n = strlen(name);
	if (n < 4)
		return 0;
	const char *e = name + n - 4;
	return strcmp(e, ".ttf") == 0 || strcmp(e, ".otf") == 0 ||
		strcmp(e, ".ttc") == 0 || strcmp(e, ".TTF") == 0 ||
		strcmp(e, ".OTF") == 0 || strcmp(e, ".TTC") == 0;
}

static int hr_find(const char *dir, const char *norm, char *out, int cap)
{
	char candidate[HR_NAME_LEN];
#ifdef _WIN32
	{
		char pattern[HR_PATH_LEN + 8];
		WIN32_FIND_DATAA fd;
		HANDLE h;
		snprintf(pattern, sizeof pattern, "%s\\*", dir);
		h = FindFirstFileA(pattern, &fd);
		if (h == INVALID_HANDLE_VALUE)
			return 0;
		do {
			if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)
				continue;
			if (!hr_font_ext(fd.cFileName))
				continue;
			hr_normalize(fd.cFileName, candidate, sizeof candidate);
			/* strip extension (already normalized without dots) */
			{
				char *dot = strrchr(candidate, '.');
				/* normalized name keeps the dot; cut it */
				if (dot) *dot = 0;
			}
			if (strcmp(candidate, norm) == 0) {
				snprintf(out, cap, "%s\\%s", dir, fd.cFileName);
				FindClose(h);
				return 1;
			}
		} while (FindNextFileA(h, &fd));
		FindClose(h);
	}
#else
	{
		DIR *d = opendir(dir);
		struct dirent *de;
		if (!d)
			return 0;
		while ((de = readdir(d)) != NULL) {
			if (!hr_font_ext(de->d_name))
				continue;
			hr_normalize(de->d_name, candidate, sizeof candidate);
			{
				char *dot = strrchr(candidate, '.');
				if (dot) *dot = 0;
			}
			if (strcmp(candidate, norm) == 0) {
				snprintf(out, cap, "%s/%s", dir, de->d_name);
				closedir(d);
				return 1;
			}
		}
		closedir(d);
	}
#endif
	return 0;
}

fz_font *hr_load_user_font(fz_context *ctx, const char *name, int bold, int italic, int needs_exact_metrics)
{
	char dir[HR_PATH_LEN];
	char norm[HR_NAME_LEN];
	char path[HR_PATH_LEN];
	fz_font *font = NULL;
	int i;

	(void)bold;
	(void)italic;
	(void)needs_exact_metrics;

	if (!ctx || !name || !name[0])
		return NULL;
	hr_fonts_dir(dir, sizeof dir);
	if (!dir[0])
		return NULL;
	hr_normalize(name, norm, sizeof norm);
	if (!norm[0])
		return NULL;

	{
		const char *dbg = getenv("HR_FONTS_DEBUG");
		if (dbg)
			fprintf(stderr, "hrfont: request '%s' dir '%s'\n", name, dir);
	}
	hr_lock();
	for (i = 0; i < g_n; i++)
		if (strcmp(g_cache[i].norm, norm) == 0) {
			font = g_cache[i].font;
			hr_unlock();
			/* 文档关闭会 drop 返回引用：命中也要移交一个新引用 */
			fz_try(ctx)
				return fz_keep_font(ctx, font);
			fz_catch(ctx)
				return NULL;
		}
	if (!hr_find(dir, norm, path, sizeof path)) {
		hr_unlock();
		if (getenv("HR_FONTS_DEBUG"))
			fprintf(stderr, "hrfont: miss '%s' (norm '%s')\n", name, norm);
		return NULL;
	}
	if (getenv("HR_FONTS_DEBUG"))
		fprintf(stderr, "hrfont: hit '%s' -> '%s'\n", name, path);
	fz_var(font);
	fz_try(ctx)
	{
		font = fz_new_font_from_file(ctx, name, path, 0, 1);
		if (getenv("HR_FONTS_DEBUG"))
			fprintf(stderr, "hrfont: loaded '%s' ok font=%p\n", path, (void *)font);
	}
	fz_catch(ctx)
	{
		if (getenv("HR_FONTS_DEBUG"))
			fprintf(stderr, "hrfont: load FAILED '%s': %s\n", path, fz_caught_message(ctx));
		font = NULL;
	}
	fz_var(font);
	if (font && g_n < HR_MAX_CACHE) {
		fz_font *keep = NULL;
		fz_var(keep);
		fz_try(ctx)
			keep = fz_keep_font(ctx, font); /* 缓存自持引用，永不 drop */
		fz_catch(ctx)
			keep = NULL;
		if (keep) {
			snprintf(g_cache[g_n].norm, HR_NAME_LEN, "%s", norm);
			snprintf(g_cache[g_n].path, HR_PATH_LEN, "%s", path);
			g_cache[g_n].font = keep;
			g_n++;
		}
	}
	hr_unlock();
	return font; /* 调用方引用（会被 drop），与缓存自持引用分离 */
}
