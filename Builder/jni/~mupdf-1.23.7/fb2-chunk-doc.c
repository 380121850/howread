/* HowRead (LibreraReader fork): chunked FictionBook2 (fb2) document.
 *
 * A large .fb2 used to be parsed into a single box tree in full at open
 * (fz_parse_fb2 walks the whole FictionBook DOM) and the first page count
 * forced a full-document layout, so a 22MB book paid seconds of parse plus
 * seconds of layout before the first page could render. This document
 * class splits the book at <section> boundaries into byte-range
 * pseudo-chapters (each re-wrapped as a small standalone FictionBook) that
 * are parsed and laid out on demand through the same chapter API the epub
 * document implements (count_chapters / count_pages(chapter) /
 * load_page(chapter, page)) — the reader's chapter-progressive machinery
 * (staged first-batch count + background phase two) then only pays for the
 * chapters it actually needs.
 *
 * Splits are adaptive: a section bigger than the chunk target is descended
 * into (its direct <section> children become candidates), so books with a
 * few huge top-level chapters still chunk finely. Files without usable
 * structure (no/too-few sections, utf-16 encodings, malformed nesting) or
 * below the chunk target size fall back to the original whole-file fb2
 * handler with byte-identical behaviour.
 *
 * Each chunk starts on a fresh page (like an epub chapter), so total page
 * counts can drift slightly versus the old single-tree rendering.
 */

#include "mupdf/fitz.h"
#include "html-imp.h"

#include <string.h>
#include <math.h>

/* page margin index order used by the html layer */
enum { T, R, B, L };

/* target size of one chunk (byte size of its raw <section> run) */
#define FB2CHUNK_TARGET (512 * 1024)
/* below this total size the legacy whole-file path is better */
#define FB2CHUNK_MIN_SIZE FB2CHUNK_TARGET
/* maximum nesting we descend through when splitting */
#define FB2CHUNK_MAX_DEPTH 48
/* safety cap on the number of chunks */
#define FB2CHUNK_MAX_SLICES 16384
/* safety cap on distinct image ids collected per chunk */
#define FB2CHUNK_MAX_REFS 96
/* safety cap on a single id/href length */
#define FB2CHUNK_MAX_ID 120

typedef struct fb2chunk_slice
{
	int64_t off;    /* byte offset of the first <section> of the run */
	int64_t len;    /* byte length of the run (last </section> included) */
	int pages;      /* page count after layout; -1 = not counted yet */
}
fb2chunk_slice;

typedef struct fb2chunk_section
{
	int64_t open;       /* offset of '<' of the open tag */
	int64_t close;      /* offset just past '>' of the close tag */
	int firstchild;
	int nextsib;
}
fb2chunk_section;

typedef struct fb2chunk_bin
{
	int64_t open;
	int64_t close;
	char *id;           /* id attribute value (or NULL) */
}
fb2chunk_bin;

typedef struct
{
	fz_document super;
	fz_buffer *buf;              /* the whole file (owned) */
	fz_html_font_set *set;

	int64_t decl_off, decl_len;  /* <?xml ...?> (0,0 when absent) */
	int64_t open_off, open_len;  /* the <FictionBook ...> open tag */
	int64_t desc_off, desc_len;  /* <description>...</description> (0,0 when absent) */

	fb2chunk_slice *slices;
	int nslices;

	fb2chunk_bin *bins;
	int nbins;

	float layout_w, layout_h, layout_em;
	int laid_out;
	fz_outline *outline;         /* built lazily on first load_outline */
	fz_html *hot;                /* most recently parsed chunk tree (pin) */
	int hot_chapter;
}
fb2chunk_document;

typedef struct
{
	fz_page super;
	fb2chunk_document *doc;
	fz_html *html;               /* kept: laid-out tree of this page's chunk */
	int number;
}
fb2chunk_page;

static int fb2chunk_split(fz_context *ctx, fb2chunk_document *doc, int p, int rdepth,
	fb2chunk_section *secs);

/* ------------------------------------------------------------------ */
/* byte scanner                                                        */
/* ------------------------------------------------------------------ */

static int64_t fb2chunk_find(const unsigned char *d, int64_t size, int64_t from, const char *needle)
{
	int64_t nlen = (int64_t)strlen(needle);
	int64_t i;
	for (i = from; i + nlen <= size; ++i)
		if (d[i] == (unsigned char)needle[0] && memcmp(d + i, needle, (size_t)nlen) == 0)
			return i;
	return -1;
}

/* Offset just past the '>' closing the tag that starts at from ('<'),
 * honouring quoted attribute values; -1 when the tag never ends. */
static int64_t fb2chunk_tag_end(const unsigned char *d, int64_t size, int64_t from)
{
	int64_t i = from + 1;
	while (i < size)
	{
		char c = d[i];
		if (c == '>')
			return i + 1;
		if (c == '"' || c == '\'')
		{
			char q = c;
			++i;
			while (i < size && d[i] != q)
				++i;
		}
		++i;
	}
	return -1;
}

/* End of the tag name following the '<' (and optional '/') at from. */
static int64_t fb2chunk_name_end(const unsigned char *d, int64_t size, int64_t from)
{
	int64_t i = from + 1;
	if (i < size && d[i] == '/')
		++i;
	while (i < size)
	{
		char c = d[i];
		int lc = c | 32;
		if ((lc >= 'a' && lc <= 'z') || (c >= '0' && c <= '9') ||
			c == ':' || c == '_' || c == '-' || c == '.')
			++i;
		else
			break;
	}
	return i;
}

static int fb2chunk_name_is(const unsigned char *d, int64_t s, int64_t e, const char *name)
{
	int64_t n = (int64_t)strlen(name);
	return e - s == n && memcmp(d + s, name, (size_t)n) == 0;
}

static int fb2chunk_is_utf16(fz_buffer *buf)
{
	const unsigned char *d = buf->data;
	int64_t n = buf->len;
	int64_t lim = n < 256 ? n : 256;
	int64_t i;
	if (n >= 2 && ((d[0] == 0xFF && d[1] == 0xFE) || (d[0] == 0xFE && d[1] == 0xFF)))
		return 1;
	for (i = 0; i + 6 < lim; ++i)
		if ((d[i] | 32) == 'u' && (d[i+1] | 32) == 't' && (d[i+2] | 32) == 'f' &&
			d[i+3] == '-' && d[i+4] == '1' && d[i+5] == '6')
			return 1;
	return 0;
}

/* Raw byte range of one element: offset just past the matching close tag
 * (simple search, valid for description which cannot nest). */
static int fb2chunk_element_range(const unsigned char *d, int64_t size, int64_t open, const char *name, int64_t *close)
{
	char needle[32];
	int64_t p;
	if (strlen(name) + 4 >= sizeof(needle))
		return -1;
	needle[0] = '<';
	needle[1] = '/';
	strcpy(needle + 2, name);
	strcat(needle, ">");
	p = fb2chunk_find(d, size, open + 1, needle);
	if (p < 0)
		return -1;
	*close = fb2chunk_tag_end(d, size, p);
	return *close < 0 ? -1 : 0;
}

/* Scans the whole file; fills decl/open/description ranges and the section
 * + binary element tables, then splits the section tree into chunk-size
 * runs. Returns 0 on success, -1 when the structure is unusable (caller
 * falls back to the legacy whole-file handler). */
static int fb2chunk_scan(fz_context *ctx, fb2chunk_document *doc)
{
	const unsigned char *d = doc->buf->data;
	int64_t size = doc->buf->len;
	int64_t i;
	int64_t fbopen;
	int depth = 0;
	int64_t *stack = NULL;
	int *lastchild = NULL;
	int scap = 0;
	int sec_cap = 0, nsec = 0;
	fb2chunk_section *secs = NULL;
	int bin_cap = 0;
	int64_t bopen = -1;
	int root_head = -1, root_tail = -1;
	int rc = -1;

	/* xml declaration */
	doc->decl_off = doc->decl_len = 0;
	if (size > 4 && d[0] == '<' && d[1] == '?')
	{
		for (i = 3; i + 1 < size; ++i)
			if (d[i] == '?' && d[i+1] == '>')
			{
				doc->decl_len = i + 2;
				break;
			}
		if (doc->decl_len == 0)
			return -1;
	}

	/* FictionBook root element */
	fbopen = fb2chunk_find(d, size, doc->decl_len, "<FictionBook");
	if (fbopen < 0)
		return -1;
	i = fb2chunk_tag_end(d, size, fbopen);
	if (i < 0)
		return -1;
	doc->open_off = fbopen;
	doc->open_len = i - fbopen;

	i = doc->open_off + doc->open_len;
	while (i < size)
	{
		int64_t ne, te;
		int closing, selfclosing;

		if (d[i] != '<')
		{
			++i;
			continue;
		}
		if (!memcmp(d + i, "<!--", 4))
		{
			i = fb2chunk_find(d, size, i + 4, "-->");
			if (i < 0)
				goto fail;
			continue;
		}
		if (!memcmp(d + i, "<![CDATA[", 9))
		{
			i = fb2chunk_find(d, size, i + 9, "]]>");
			if (i < 0)
				goto fail;
			continue;
		}
		if (d[i+1] == '?')
		{
			i = fb2chunk_find(d, size, i + 2, "?>");
			if (i < 0)
				goto fail;
			continue;
		}
		if (d[i+1] == '!')
		{
			i = fb2chunk_tag_end(d, size, i);
			if (i < 0)
				goto fail;
			continue;
		}

		ne = fb2chunk_name_end(d, size, i);
		te = fb2chunk_tag_end(d, size, i);
		if (ne <= i + 1 || te < 0)
			goto fail;
		closing = d[i+1] == '/';
		selfclosing = d[te-2] == '/';

		if (!closing && fb2chunk_name_is(d, i + 1, ne, "section") && !selfclosing)
		{
			int idx, parentslot;
			if (depth == scap)
			{
				int nc = scap ? scap * 2 : 64;
				stack = fz_realloc(ctx, stack, (size_t)nc * sizeof(*stack));
				lastchild = fz_realloc(ctx, lastchild, (size_t)nc * sizeof(*lastchild));
				scap = nc;
			}
			if (nsec == sec_cap)
			{
				int nc = sec_cap ? sec_cap * 2 : 256;
				secs = fz_realloc(ctx, secs, (size_t)nc * sizeof(*secs));
				sec_cap = nc;
			}
			idx = nsec++;
			secs[idx].open = i;
			secs[idx].close = -1;
			secs[idx].firstchild = -1;
			secs[idx].nextsib = -1;
			parentslot = depth - 1;
			if (parentslot >= 0)
			{
				int parent = stack[parentslot];
				if (lastchild[parentslot] < 0)
					secs[parent].firstchild = idx;
				else
					secs[lastchild[parentslot]].nextsib = idx;
				lastchild[parentslot] = idx;
			}
			else
			{
				if (root_tail >= 0)
					secs[root_tail].nextsib = idx;
				else
					root_head = idx;
				root_tail = idx;
			}
			stack[depth] = idx;
			lastchild[depth] = -1;
			depth++;
		}
		else if (closing && fb2chunk_name_is(d, i + 2, ne, "section"))
		{
			if (depth == 0)
				goto fail;
			secs[stack[depth-1]].close = te;
			depth--;
		}
		else if (!closing && !selfclosing && fb2chunk_name_is(d, i + 1, ne, "binary"))
		{
			bopen = i;
		}
		else if (closing && fb2chunk_name_is(d, i + 2, ne, "binary"))
		{
			if (bopen >= 0)
			{
				if (doc->nbins == bin_cap)
				{
					int nc = bin_cap ? bin_cap * 2 : 16;
					doc->bins = fz_realloc(ctx, doc->bins, (size_t)nc * sizeof(*doc->bins));
					bin_cap = nc;
				}
				doc->bins[doc->nbins].open = bopen;
				doc->bins[doc->nbins].close = te;
				/* id attribute value from the open tag */
				{
					int64_t s = -1, sl = 0;
					int64_t j;
					for (j = bopen + 1; j + 3 < i && s < 0; ++j)
					{
						if ((d[j] == ' ' || d[j] == '\t' || d[j] == '\n' || d[j] == '\r') &&
							(d[j+1] == 'i' || d[j+1] == 'I') &&
							(d[j+2] == 'd' || d[j+2] == 'D'))
						{
							int64_t k2 = j + 3;
							while (k2 < i && (d[k2] == ' ' || d[k2] == '\t'))
								++k2;
							if (k2 < i && d[k2] == '=')
							{
								++k2;
								while (k2 < i && (d[k2] == ' ' || d[k2] == '\t'))
									++k2;
								if (k2 < i && (d[k2] == '"' || d[k2] == '\''))
								{
									char q = d[k2];
									int64_t s2 = ++k2;
									while (k2 < te && d[k2] != q)
										++k2;
									if (k2 > s2 && k2 < te && k2 - s2 < FB2CHUNK_MAX_ID)
									{
										s = s2;
										sl = k2 - s2;
									}
								}
							}
						}
					}
					doc->bins[doc->nbins].id = NULL;
					if (s >= 0)
					{
						doc->bins[doc->nbins].id = fz_malloc(ctx, (size_t)sl + 1);
						memcpy(doc->bins[doc->nbins].id, d + s, (size_t)sl);
						doc->bins[doc->nbins].id[sl] = 0;
					}
				}
				doc->nbins++;
			}
			bopen = -1;
		}
		else if (!closing && !selfclosing && fb2chunk_name_is(d, i + 1, ne, "description") &&
				 doc->desc_len == 0 && depth == 0)
		{
			int64_t close = 0;
			if (fb2chunk_element_range(d, size, i, "description", &close) == 0)
			{
				doc->desc_off = i;
				doc->desc_len = close - i;
			}
		}

		i = te;
	}

	if (depth != 0 || nsec == 0 || root_head < 0)
		goto fail;

	rc = fb2chunk_split(ctx, doc, root_head, 0, secs);

fail:
	fz_free(ctx, stack);
	fz_free(ctx, lastchild);
	fz_free(ctx, secs);
	return rc;
}

/* ------------------------------------------------------------------ */
/* chunk grouping                                                      */
/* ------------------------------------------------------------------ */

static int fb2chunk_oversized(fb2chunk_section *secs, int p)
{
	return secs[p].close - secs[p].open > FB2CHUNK_TARGET && secs[p].firstchild >= 0;
}

/* Emits slices covering the sibling chain starting at p, descending into
 * oversized sections (their children become the candidates). Runs stay in
 * document order and only ever contain complete <section> elements. */
static int fb2chunk_split(fz_context *ctx, fb2chunk_document *doc, int p, int rdepth,
	fb2chunk_section *secs)
{
	while (p >= 0)
	{
		if (rdepth < FB2CHUNK_MAX_DEPTH && fb2chunk_oversized(secs, p))
		{
			if (fb2chunk_split(ctx, doc, secs[p].firstchild, rdepth + 1, secs) < 0)
				return -1;
			p = secs[p].nextsib;
		}
		else
		{
			int end = p;
			for (;;)
			{
				int n = secs[end].nextsib;
				if (n < 0)
					break;
				if (secs[n].close - secs[p].open > FB2CHUNK_TARGET)
					break;
				if (fb2chunk_oversized(secs, n))
					break;
				end = n;
			}
			if (doc->nslices >= FB2CHUNK_MAX_SLICES)
				return -1;
			doc->slices = fz_realloc(ctx, doc->slices,
				((size_t)doc->nslices + 1) * sizeof(*doc->slices));
			doc->slices[doc->nslices].off = secs[p].open;
			doc->slices[doc->nslices].len = secs[end].close - secs[p].open;
			doc->slices[doc->nslices].pages = -1;
			doc->nslices++;
			p = secs[end].nextsib;
		}
	}
	return 0;
}

/* ------------------------------------------------------------------ */
/* per-chunk parsing                                                   */
/* ------------------------------------------------------------------ */

typedef struct
{
	char *ids[FB2CHUNK_MAX_REFS];
	int n;
}
fb2chunk_refs;

static void fb2chunk_add_ref(fz_context *ctx, fb2chunk_refs *refs, const char *s, int64_t n)
{
	int k;
	if (n <= 0 || n >= FB2CHUNK_MAX_ID || refs->n >= FB2CHUNK_MAX_REFS)
		return;
	for (k = 0; k < refs->n; ++k)
		if (strlen(refs->ids[k]) == (size_t)n && !memcmp(refs->ids[k], s, (size_t)n))
			return;
	refs->ids[refs->n] = fz_malloc(ctx, (size_t)n + 1);
	memcpy(refs->ids[refs->n], s, (size_t)n);
	refs->ids[refs->n][n] = 0;
	refs->n++;
}

static void fb2chunk_drop_refs(fz_context *ctx, fb2chunk_refs *refs)
{
	int k;
	for (k = 0; k < refs->n; ++k)
		fz_free(ctx, refs->ids[k]);
	refs->n = 0;
}

/* collect href="#..." targets inside a byte range (the internal image /
 * note references that need their <binary> definitions along) */
static void fb2chunk_scan_refs(fz_context *ctx, fb2chunk_document *doc, int64_t off, int64_t len, fb2chunk_refs *refs)
{
	const unsigned char *d = doc->buf->data;
	int64_t end = off + len;
	int64_t i;
	for (i = off; i + 6 < end; ++i)
	{
		if (d[i] == 'h' && !memcmp(d + i, "href", 4))
		{
			int64_t j = i + 4;
			while (j < end && (d[j] == ' ' || d[j] == '\t' || d[j] == '\r' || d[j] == '\n'))
				++j;
			if (j < end && d[j] == '=')
			{
				++j;
				while (j < end && (d[j] == ' ' || d[j] == '\t' || d[j] == '\r' || d[j] == '\n'))
					++j;
				if (j < end && (d[j] == '"' || d[j] == '\''))
				{
					char q = d[j];
					int64_t s = ++j;
					while (j < end && d[j] != q)
						++j;
					if (j > s && j < end && d[s] == '#')
						fb2chunk_add_ref(ctx, refs, (const char *)d + s + 1, j - s - 1);
					i = j;
				}
			}
		}
	}
}

/* Assemble chunk k as a small standalone FictionBook: original declaration
 * + root open tag + (chunk 0 only) the description + <body> with the
 * chunk's raw <section> run + the <binary> elements its hrefs point at. */
static fz_buffer *fb2chunk_build_slice(fz_context *ctx, fb2chunk_document *doc, int k)
{
	fb2chunk_refs refs;
	const unsigned char *d = doc->buf->data;
	fb2chunk_slice *sl = &doc->slices[k];
	fz_buffer *buf;
	int j;

	refs.n = 0;
	if (k == 0 && doc->desc_len > 0)
		fb2chunk_scan_refs(ctx, doc, doc->desc_off, doc->desc_len, &refs);
	fb2chunk_scan_refs(ctx, doc, sl->off, sl->len, &refs);

	buf = fz_new_buffer(ctx, (size_t)sl->len + 8192);
	fz_try(ctx)
	{
		if (doc->decl_len > 0)
			fz_append_data(ctx, buf, d + doc->decl_off, (size_t)doc->decl_len);
		fz_append_data(ctx, buf, d + doc->open_off, (size_t)doc->open_len);
		if (k == 0 && doc->desc_len > 0)
			fz_append_data(ctx, buf, d + doc->desc_off, (size_t)doc->desc_len);
		fz_append_string(ctx, buf, "<body>");
		fz_append_data(ctx, buf, d + sl->off, (size_t)sl->len);
		fz_append_string(ctx, buf, "</body>");
		for (j = 0; j < doc->nbins; ++j)
		{
			int r;
			if (!doc->bins[j].id)
				continue;
			for (r = 0; r < refs.n; ++r)
				if (!strcmp(refs.ids[r], doc->bins[j].id))
				{
					fz_append_data(ctx, buf, d + doc->bins[j].open,
						(size_t)(doc->bins[j].close - doc->bins[j].open));
					break;
				}
		}
		fz_append_string(ctx, buf, "</FictionBook>");
		fz_append_byte(ctx, buf, 0);
	}
	fz_always(ctx)
	{
		fb2chunk_drop_refs(ctx, &refs);
	}
	fz_catch(ctx)
	{
		fz_drop_buffer(ctx, buf);
		fz_rethrow(ctx);
	}
	return buf;
}

/* Parses chunk k and lays it out with the stored geometry; the returned
 * reference is kept for the caller. Mirrors epub_get_laid_out_html. */
static fz_html *fb2chunk_get_laid_out_html(fz_context *ctx, fb2chunk_document *doc, int k)
{
	fz_html *html = fz_find_html(ctx, (fz_document *)doc, k);
	if (!html)
	{
		fz_buffer *buf = fb2chunk_build_slice(ctx, doc, k);
		fz_try(ctx)
			html = fz_parse_fb2(ctx, doc->set, NULL, ".", buf, fz_user_css(ctx));
		fz_always(ctx)
			fz_drop_buffer(ctx, buf);
		fz_catch(ctx)
			fz_rethrow(ctx);
	}
	fz_try(ctx)
		fz_layout_html(ctx, html, doc->layout_w, doc->layout_h, doc->layout_em);
	fz_catch(ctx)
	{
		fz_drop_html(ctx, html);
		fz_rethrow(ctx);
	}

	html = fz_store_html(ctx, html, (fz_document *)doc, k);
	fz_drop_html(ctx, doc->hot);
	doc->hot = fz_keep_html(ctx, html);
	doc->hot_chapter = k;
	return html;
}

/* ------------------------------------------------------------------ */
/* document methods                                                    */
/* ------------------------------------------------------------------ */

static void
fb2chunk_drop_document(fz_context *ctx, fz_document *doc_)
{
	fb2chunk_document *doc = (fb2chunk_document *)doc_;
	int k;
	fz_drop_buffer(ctx, doc->buf);
	fz_drop_html_font_set(ctx, doc->set);
	fz_drop_html(ctx, doc->hot);
	fz_purge_stored_html(ctx, doc);
	fz_drop_outline(ctx, doc->outline);
	for (k = 0; k < doc->nbins; ++k)
		fz_free(ctx, doc->bins[k].id);
	fz_free(ctx, doc->bins);
	fz_free(ctx, doc->slices);
}

static int
fb2chunk_count_chapter_pages(fz_context *ctx, fb2chunk_document *doc, int k)
{
	fb2chunk_slice *sl = &doc->slices[k];
	if (sl->pages < 0)
	{
		fz_html *html = fb2chunk_get_laid_out_html(ctx, doc, k);
		sl->pages = html->tree.root->s.layout.b > 0
			? (int)ceilf(html->tree.root->s.layout.b / html->page_h)
			: 1;
		if (sl->pages < 1)
			sl->pages = 1;
		fz_drop_html(ctx, html);
	}
	return sl->pages;
}

static int
fb2chunk_count_chapters(fz_context *ctx, fz_document *doc_)
{
	return ((fb2chunk_document *)doc_)->nslices;
}

/* chapter < 0 (the full-count fallback) parses every chunk once; the page
 * counts stay cached, so it happens at most once per geometry. */
static int
fb2chunk_count_pages(fz_context *ctx, fz_document *doc_, int chapter)
{
	fb2chunk_document *doc = (fb2chunk_document *)doc_;
	int k, total = 0;
	if (chapter >= 0)
	{
		if (chapter >= doc->nslices)
			return 0;
		return fb2chunk_count_chapter_pages(ctx, doc, chapter);
	}
	for (k = 0; k < doc->nslices; k++)
		total += fb2chunk_count_chapter_pages(ctx, doc, k);
	return total;
}

static void
fb2chunk_layout(fz_context *ctx, fz_document *doc_, float w, float h, float em)
{
	fb2chunk_document *doc = (fb2chunk_document *)doc_;
	int k;
	if (doc->laid_out && doc->layout_w == w && doc->layout_h == h && doc->layout_em == em)
		return;
	doc->layout_w = w;
	doc->layout_h = h;
	doc->layout_em = em;
	doc->laid_out = 1;
	/* parsed chunk trees carry the previous geometry: drop them */
	fz_drop_html(ctx, doc->hot);
	doc->hot = NULL;
	doc->hot_chapter = -1;
	fz_purge_stored_html(ctx, doc);
	for (k = 0; k < doc->nslices; k++)
		doc->slices[k].pages = -1;
	fz_drop_outline(ctx, doc->outline);
	doc->outline = NULL;
}

static void
fb2chunk_drop_page(fz_context *ctx, fz_page *page_)
{
	fb2chunk_page *page = (fb2chunk_page *)page_;
	fz_drop_html(ctx, page->html);
}

static fz_rect
fb2chunk_bound_page(fz_context *ctx, fz_page *page_, fz_box_type box)
{
	fb2chunk_page *page = (fb2chunk_page *)page_;
	fz_rect bbox;
	bbox.x0 = 0;
	bbox.y0 = 0;
	bbox.x1 = page->html->page_w + page->html->page_margin[L] + page->html->page_margin[R];
	bbox.y1 = page->html->page_h + page->html->page_margin[T] + page->html->page_margin[B];
	return bbox;
}

static void
fb2chunk_run_page(fz_context *ctx, fz_page *page_, fz_device *dev, fz_matrix ctm, fz_cookie *cookie)
{
	fb2chunk_page *page = (fb2chunk_page *)page_;
	fz_draw_html(ctx, dev, ctm, page->html, page->number);
}

static fz_link *
fb2chunk_load_links(fz_context *ctx, fz_page *page_)
{
	fb2chunk_page *page = (fb2chunk_page *)page_;
	return fz_load_html_links(ctx, page->html, page->number, "");
}

static fz_page *
fb2chunk_load_page(fz_context *ctx, fz_document *doc_, int chapter, int number)
{
	fb2chunk_document *doc = (fb2chunk_document *)doc_;
	fb2chunk_page *page;
	fz_html *html;
	if (chapter < 0 || chapter >= doc->nslices)
		return NULL;
	html = fb2chunk_get_laid_out_html(ctx, doc, chapter);
	page = fz_new_derived_page(ctx, fb2chunk_page, doc_);
	page->super.bound_page = fb2chunk_bound_page;
	page->super.run_page_contents = fb2chunk_run_page;
	page->super.load_links = fb2chunk_load_links;
	page->super.drop_page = fb2chunk_drop_page;
	page->doc = doc;
	page->html = html;
	page->number = number;
	return (fz_page *)page;
}

static fz_bookmark
fb2chunk_make_bookmark(fz_context *ctx, fz_document *doc_, fz_location loc)
{
	fb2chunk_document *doc = (fb2chunk_document *)doc_;
	fz_html *html = fb2chunk_get_laid_out_html(ctx, doc, loc.chapter);
	fz_bookmark mark = fz_make_html_bookmark(ctx, html, loc.page);
	fz_drop_html(ctx, html);
	return mark;
}

static fz_location
fb2chunk_lookup_bookmark(fz_context *ctx, fz_document *doc_, fz_bookmark mark)
{
	fb2chunk_document *doc = (fb2chunk_document *)doc_;
	int k;
	for (k = 0; k < doc->nslices; k++)
	{
		fz_html *html = fb2chunk_get_laid_out_html(ctx, doc, k);
		int p = fz_lookup_html_bookmark(ctx, html, mark);
		fz_drop_html(ctx, html);
		if (p != -1)
			return fz_make_location(k, p);
	}
	return fz_make_location(-1, -1);
}

/* Internal links resolve only against chunks that are already laid out, so
 * a footnote jump can never trigger a full-book layout stall. Once the
 * background phase two has laid the whole book (the common case by the
 * time the user navigates), all targets resolve. */
static fz_link_dest
fb2chunk_resolve_link(fz_context *ctx, fz_document *doc_, const char *dest)
{
	fb2chunk_document *doc = (fb2chunk_document *)doc_;
	const char *s = strchr(dest, '#');
	if (s && s[1] != 0)
	{
		int k;
		for (k = 0; k < doc->nslices; ++k)
		{
			fz_html *html;
			if (doc->slices[k].pages < 0)
				continue;
			html = fz_find_html(ctx, (fz_document *)doc, k);
			if (html)
			{
				float y = fz_find_html_target(ctx, html, s + 1);
				int ph = html->page_h;
				fz_drop_html(ctx, html);
				if (y >= 0)
				{
					int page = (int)(y / ph);
					return fz_make_link_dest_xyz(k, page, 0, y - page * ph, 0);
				}
			}
		}
	}
	return fz_make_link_dest_none();
}

static void
fb2chunk_set_outline_pages(fz_context *ctx, fb2chunk_document *doc, fz_outline *node, int k, int ph, int base)
{
	while (node)
	{
		int local = 0;
		if (node->uri && node->uri[0] == '#' && node->uri[1] != 0)
		{
			fz_html *h2 = fb2chunk_get_laid_out_html(ctx, doc, k);
			float y = fz_find_html_target(ctx, h2, node->uri + 1);
			fz_drop_html(ctx, h2);
			if (y >= 0)
				local = (int)(y / ph);
		}
		node->page = fz_make_location(k, local);
		/* The reader derives TOC pages from a "#page=N" uri (1-based global
		 * page); rewrite the heading-fragment uri so entries stay jumpable. */
		fz_free(ctx, node->uri);
		node->uri = fz_asprintf(ctx, "#page=%d", base + local + 1);
		fb2chunk_set_outline_pages(ctx, doc, node->down, k, ph, base);
		node = node->next;
	}
}

/* Concatenated per-chunk outlines: every entry's page becomes
 * (chunk, local page); the generic location mapping turns that into the
 * global page index. Lays out every chunk once — called lazily, after the
 * reader's background phase two has normally already done the work. */
static void
fb2chunk_build_outline(fz_context *ctx, fb2chunk_document *doc)
{
	fz_outline *head = NULL, *tail = NULL;
	int base = 0;
	int k;
	for (k = 0; k < doc->nslices; ++k)
	{
		fz_html *html = fb2chunk_get_laid_out_html(ctx, doc, k);
		fz_outline *ol = fz_load_html_outline(ctx, html);
		int ph = html->page_h;
		fz_drop_html(ctx, html);
		if (!ol)
			continue;
		fb2chunk_set_outline_pages(ctx, doc, ol, k, ph, base);
		if (tail)
			tail->next = ol;
		else
			head = ol;
		for (tail = ol; tail->next; tail = tail->next)
			;
		base += doc->slices[k].pages > 0 ? doc->slices[k].pages : 1;
	}
	doc->outline = head;
}

static fz_outline *
fb2chunk_load_outline(fz_context *ctx, fz_document *doc_)
{
	fb2chunk_document *doc = (fb2chunk_document *)doc_;
	if (!doc->outline)
		fb2chunk_build_outline(ctx, doc);
	return fz_keep_outline(ctx, doc->outline);
}

static int
fb2chunk_lookup_metadata(fz_context *ctx, fz_document *doc_, const char *key, char *buf, size_t size)
{
	fb2chunk_document *doc = (fb2chunk_document *)doc_;
	if (!strcmp(key, FZ_META_FORMAT))
		return 1 + (int)fz_strlcpy(buf, "FictionBook2", size);
	if (!strcmp(key, FZ_META_INFO_TITLE))
	{
		int n = -1;
		fz_html *html = fb2chunk_get_laid_out_html(ctx, doc, 0);
		if (html->title)
			n = 1 + (int)fz_strlcpy(buf, html->title, size);
		fz_drop_html(ctx, html);
		return n;
	}
	return -1;
}

/* ------------------------------------------------------------------ */
/* open                                                                */
/* ------------------------------------------------------------------ */

extern fz_document *fz_open_fb2legacy_document_with_buffer(fz_context *ctx, fz_archive *zip, fz_buffer *buf);

/* Takes ownership of buf on every path. Small files, utf-16 encodings and
 * files without a usable <section> structure return the legacy whole-file
 * fb2 document (byte-identical to the old behaviour). */
static fz_document *
fb2chunk_open_document_with_buffer(fz_context *ctx, fz_buffer *buf)
{
	fb2chunk_document *doc = NULL;

	if (!buf || buf->len < FB2CHUNK_MIN_SIZE || fb2chunk_is_utf16(buf))
		return fz_open_fb2legacy_document_with_buffer(ctx, NULL, buf);

	fz_try(ctx)
	{
		doc = fz_new_derived_document(ctx, fb2chunk_document);
		doc->super.drop_document = fb2chunk_drop_document;
		doc->super.layout = fb2chunk_layout;
		doc->super.load_outline = fb2chunk_load_outline;
		doc->super.resolve_link_dest = fb2chunk_resolve_link;
		doc->super.make_bookmark = fb2chunk_make_bookmark;
		doc->super.lookup_bookmark = fb2chunk_lookup_bookmark;
		doc->super.count_pages = fb2chunk_count_pages;
		doc->super.count_chapters = fb2chunk_count_chapters;
		doc->super.load_page = fb2chunk_load_page;
		doc->super.lookup_metadata = fb2chunk_lookup_metadata;
		doc->super.is_reflowable = 1;

		doc->buf = buf;          /* takes ownership */
		doc->set = fz_new_html_font_set(ctx);
		doc->bins = NULL;
		doc->nbins = 0;
		doc->slices = NULL;
		doc->nslices = 0;
		doc->outline = NULL;
		doc->hot = NULL;
		doc->hot_chapter = -1;
		if (fb2chunk_scan(ctx, doc) < 0)
		{
			/* structure unusable: hand the buffer back to the legacy path */
			doc->buf = NULL;
			fz_drop_document(ctx, &doc->super);
			return fz_open_fb2legacy_document_with_buffer(ctx, NULL, buf);
		}
	}
	fz_catch(ctx)
	{
		if (doc)
		{
			if (doc->buf == buf)
				doc->buf = NULL;
			fz_drop_document(ctx, &doc->super);
		}
		return fz_open_fb2legacy_document_with_buffer(ctx, NULL, buf);
	}
	return (fz_document *)doc;
}

fz_document *
fz_open_fb2chunk_document_with_buffer(fz_context *ctx, fz_buffer *buf)
{
	return fb2chunk_open_document_with_buffer(ctx, buf);
}
