/* HowRead (LibreraReader fork): chunked plain-text document.
 *
 * A plain .txt used to be slurped whole at open (fz_read_all + full parse
 * + full box tree), so a big remote text file had to be downloaded in its
 * entirety before the first page could render. This document class serves
 * the text as N newline-aligned byte-range pseudo-chapters that are
 * parsed and laid out on demand through the exact same chapter API the
 * epub document implements (count_chapters / count_pages(chapter) /
 * load_page(chapter, page)) — opening a remote .txt now only reads its
 * first chunk (~256KB), and the chapter-progressive machinery already
 * present in the reader drives the rest.
 *
 * Only the stream variant is chunked: local txt files keep the original
 * whole-file handler (they are local, and the app converts them to
 * chapterized epubs anyway).
 */

#include "mupdf/fitz.h"
#include "html-imp.h"

#include <string.h>
#include <math.h>

/* page margin index order used by the html layer */
enum { T, R, B, L };

#define TXTCHUNK_SIZE (256 * 1024)
/* a chapter may be extended up to this many bytes so it ends on a newline */
#define TXTCHUNK_EXTEND (64 * 1024)

typedef struct txtchunk_chapter
{
    int64_t off;    /* byte offset in the stream */
    int64_t len;    /* base length (extended at read time to a newline) */
    int pages;      /* page count after layout; -1 = not counted yet */
}
txtchunk_chapter;

typedef struct
{
    fz_document super;
    fz_stream *stream;
    int64_t filesize;
    fz_html_font_set *set;
    int nchapters;
    txtchunk_chapter *chapters;
    float layout_w, layout_h, layout_em;
    int laid_out;
    fz_html *hot;        /* most recently parsed chapter tree (or NULL) */
    int hot_chapter;     /* chapter index of hot, or -1 */
}
txtchunk_document;

typedef struct
{
    fz_page super;
    txtchunk_document *doc;
    fz_html *html;       /* kept: laid-out tree of this page's chapter */
    int number;
}
txtchunk_page;

static void
txtchunk_drop_document(fz_context *ctx, fz_document *doc_)
{
    txtchunk_document *doc = (txtchunk_document *)doc_;
    fz_drop_stream(ctx, doc->stream);
    fz_drop_html_font_set(ctx, doc->set);
    fz_drop_html(ctx, doc->hot);
    fz_purge_stored_html(ctx, doc);
    fz_free(ctx, doc->chapters);
}

/* Reads chapter k's byte range (extended to the next newline when one is
 * found within TXTCHUNK_EXTEND) from the stream. */
static fz_buffer *
txtchunk_read_chapter(fz_context *ctx, txtchunk_document *doc, int k)
{
    txtchunk_chapter *ch = &doc->chapters[k];
    fz_buffer *buf;
    unsigned char scratch[8192];
    int64_t want = ch->len;
    int64_t got = 0;

    fz_seek(ctx, doc->stream, ch->off, 0 /* SEEK_SET */);
    buf = fz_new_buffer(ctx, (size_t)want + 1);
    fz_try(ctx)
    {
        while (got < want)
        {
            int64_t left = want - got;
            size_t n = fz_read(ctx, doc->stream, scratch,
                    left < (int64_t)sizeof(scratch) ? (size_t)left : sizeof(scratch));
            if (n == 0)
                break;
            fz_append_data(ctx, buf, scratch, n);
            got += n;
        }
        while (got < want + TXTCHUNK_EXTEND)
        {
            int c = fz_peek_byte(ctx, doc->stream);
            if (c == EOF)
                break;
            (void)fz_read_byte(ctx, doc->stream);
            fz_append_byte(ctx, buf, (unsigned char)c);
            got++;
            if (c == '\n')
                break;
        }
        fz_append_byte(ctx, buf, 0);
    }
    fz_catch(ctx)
    {
        fz_drop_buffer(ctx, buf);
        fz_rethrow(ctx);
    }
    return buf;
}

/* Parses chapter k and lays it out with the stored geometry; the returned
 * reference is kept for the caller. Mirrors epub_get_laid_out_html. */
static fz_html *
txtchunk_get_laid_out_html(fz_context *ctx, txtchunk_document *doc, int k)
{
    fz_html *html = fz_find_html(ctx, (fz_document *)doc, k);
    if (html)
        return html;

    {
        fz_buffer *buf = txtchunk_read_chapter(ctx, doc, k);
        fz_try(ctx)
            html = fz_parse_txt(ctx, doc->set, NULL, ".", buf, fz_user_css(ctx));
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

static int
txtchunk_count_chapter_pages(fz_context *ctx, txtchunk_document *doc, int k)
{
    txtchunk_chapter *ch = &doc->chapters[k];
    if (ch->pages < 0)
    {
        fz_html *html = txtchunk_get_laid_out_html(ctx, doc, k);
        if (html->tree.root->s.layout.b > 0)
            ch->pages = (int)ceilf(html->tree.root->s.layout.b / html->page_h);
        else
            ch->pages = 1;
        fz_drop_html(ctx, html);
    }
    return ch->pages;
}

static int
txtchunk_count_chapters(fz_context *ctx, fz_document *doc_)
{
    txtchunk_document *doc = (txtchunk_document *)doc_;
    return doc->nchapters;
}

/* chapter < 0 (the full-count fallback) parses every chapter once; the
 * page counts stay cached, so it happens at most once per geometry. */
static int
txtchunk_count_pages(fz_context *ctx, fz_document *doc_, int chapter)
{
    txtchunk_document *doc = (txtchunk_document *)doc_;
    int k, total = 0;
    if (chapter >= 0)
    {
        if (chapter >= doc->nchapters)
            return 0;
        return txtchunk_count_chapter_pages(ctx, doc, chapter);
    }
    for (k = 0; k < doc->nchapters; k++)
        total += txtchunk_count_chapter_pages(ctx, doc, k);
    return total;
}

static void
txtchunk_layout(fz_context *ctx, fz_document *doc_, float w, float h, float em)
{
    txtchunk_document *doc = (txtchunk_document *)doc_;
    int k;
    if (doc->laid_out && doc->layout_w == w && doc->layout_h == h && doc->layout_em == em)
        return;
    doc->layout_w = w;
    doc->layout_h = h;
    doc->layout_em = em;
    doc->laid_out = 1;
    /* parsed chapter trees carry the previous geometry: drop them */
    fz_drop_html(ctx, doc->hot);
    doc->hot = NULL;
    doc->hot_chapter = -1;
    fz_purge_stored_html(ctx, doc);
    for (k = 0; k < doc->nchapters; k++)
        doc->chapters[k].pages = -1;
}

static void
txtchunk_drop_page(fz_context *ctx, fz_page *page_)
{
    txtchunk_page *page = (txtchunk_page *)page_;
    fz_drop_html(ctx, page->html);
}

static fz_rect
txtchunk_bound_page(fz_context *ctx, fz_page *page_, fz_box_type box)
{
    txtchunk_page *page = (txtchunk_page *)page_;
    txtchunk_document *doc = page->doc;
    fz_rect bbox;
    bbox.x0 = 0;
    bbox.y0 = 0;
    bbox.x1 = page->html->page_w + page->html->page_margin[L] + page->html->page_margin[R];
    bbox.y1 = page->html->page_h + page->html->page_margin[T] + page->html->page_margin[B];
    (void)doc;
    return bbox;
}

static void
txtchunk_run_page(fz_context *ctx, fz_page *page_, fz_device *dev, fz_matrix ctm, fz_cookie *cookie)
{
    txtchunk_page *page = (txtchunk_page *)page_;
    fz_draw_html(ctx, dev, ctm, page->html, page->number);
}

static fz_link *
txtchunk_load_links(fz_context *ctx, fz_page *page_)
{
    txtchunk_page *page = (txtchunk_page *)page_;
    return fz_load_html_links(ctx, page->html, page->number, "");
}

static fz_page *
txtchunk_load_page(fz_context *ctx, fz_document *doc_, int chapter, int number)
{
    txtchunk_document *doc = (txtchunk_document *)doc_;
    txtchunk_page *page;
    fz_html *html;
    if (chapter < 0 || chapter >= doc->nchapters)
        return NULL;
    html = txtchunk_get_laid_out_html(ctx, doc, chapter);
    page = fz_new_derived_page(ctx, txtchunk_page, doc_);
    page->super.bound_page = txtchunk_bound_page;
    page->super.run_page_contents = txtchunk_run_page;
    page->super.load_links = txtchunk_load_links;
    page->super.drop_page = txtchunk_drop_page;
    page->doc = doc;
    page->html = html;
    page->number = number;
    return (fz_page *)page;
}

static fz_outline *
txtchunk_load_outline(fz_context *ctx, fz_document *doc_)
{
    return NULL; /* plain text has no outline */
}

static fz_link_dest
txtchunk_resolve_link(fz_context *ctx, fz_document *doc_, const char *dest)
{
    return fz_make_link_dest_none();
}

static fz_bookmark
txtchunk_make_bookmark(fz_context *ctx, fz_document *doc_, fz_location loc)
{
    txtchunk_document *doc = (txtchunk_document *)doc_;
    fz_html *html = txtchunk_get_laid_out_html(ctx, doc, loc.chapter);
    fz_bookmark mark = fz_make_html_bookmark(ctx, html, loc.page);
    fz_drop_html(ctx, html);
    return mark;
}

static fz_location
txtchunk_lookup_bookmark(fz_context *ctx, fz_document *doc_, fz_bookmark mark)
{
    txtchunk_document *doc = (txtchunk_document *)doc_;
    int k;
    for (k = 0; k < doc->nchapters; k++)
    {
        fz_html *html = txtchunk_get_laid_out_html(ctx, doc, k);
        int p = fz_lookup_html_bookmark(ctx, html, mark);
        fz_drop_html(ctx, html);
        if (p != -1)
            return fz_make_location(k, p);
    }
    return fz_make_location(-1, -1);
}

static int
txtchunk_lookup_metadata(fz_context *ctx, fz_document *doc_, const char *key, char *buf, size_t size)
{
    if (!strcmp(key, FZ_META_FORMAT))
        return (int)fz_strlcpy(buf, "TXT", size);
    return -1;
}

static fz_document *
txtchunk_open_document_with_stream(fz_context *ctx, fz_stream *file)
{
    txtchunk_document *doc = fz_new_derived_document(ctx, txtchunk_document);
    int64_t size;
    int k;

    doc->super.drop_document = txtchunk_drop_document;
    doc->super.layout = txtchunk_layout;
    doc->super.load_outline = txtchunk_load_outline;
    doc->super.resolve_link_dest = txtchunk_resolve_link;
    doc->super.make_bookmark = txtchunk_make_bookmark;
    doc->super.lookup_bookmark = txtchunk_lookup_bookmark;
    doc->super.count_pages = txtchunk_count_pages;
    doc->super.count_chapters = txtchunk_count_chapters;
    doc->super.load_page = txtchunk_load_page;
    doc->super.lookup_metadata = txtchunk_lookup_metadata;
    doc->super.is_reflowable = 1;

    fz_try(ctx)
    {
        doc->stream = fz_keep_stream(ctx, file);
        fz_seek(ctx, file, 0, 2 /* SEEK_END */);
        size = fz_tell(ctx, file);
        fz_seek(ctx, file, 0, 0 /* SEEK_SET */);
        if (size <= 0)
            fz_throw(ctx, FZ_ERROR_GENERIC, "text file is empty");
        doc->filesize = size;
        doc->set = fz_new_html_font_set(ctx);
        doc->nchapters = (int)((size + TXTCHUNK_SIZE - 1) / TXTCHUNK_SIZE);
        if (doc->nchapters < 1)
            doc->nchapters = 1;
        doc->chapters = fz_malloc_array(ctx, doc->nchapters, txtchunk_chapter);
        memset(doc->chapters, 0, doc->nchapters * sizeof(txtchunk_chapter));
        doc->hot_chapter = -1;
        for (k = 0; k < doc->nchapters; k++)
        {
            int64_t left;
            doc->chapters[k].off = (int64_t)k * TXTCHUNK_SIZE;
            left = size - doc->chapters[k].off;
            doc->chapters[k].len = left < TXTCHUNK_SIZE ? left : TXTCHUNK_SIZE;
            doc->chapters[k].pages = -1;
        }
    }
    fz_catch(ctx)
    {
        fz_drop_document(ctx, &doc->super);
        fz_rethrow(ctx);
    }
    return (fz_document *)doc;
}

fz_document *
fz_open_txtchunk_document_with_stream(fz_context *ctx, fz_stream *file)
{
    return txtchunk_open_document_with_stream(ctx, file);
}
