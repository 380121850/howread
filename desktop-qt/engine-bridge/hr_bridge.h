/* HowRead Qt 分支 M0：引擎 C 桥接（Qt 直绑 fitz；与 DocumentHandle 同一渲染管线）。 */
#ifndef HR_BRIDGE_H
#define HR_BRIDGE_H

#ifdef __cplusplus
extern "C" {
#endif

/* mode 对齐 howread.engine.ReadMode 枚举序：0=NORMAL 1=SEPIA 2=NIGHT；tint 为 0xRRGGBB */
int  hr_qt_init(void);                 /* 0=ok；创建全局 context+注册处理器+装系统字体钩子 */
void hr_qt_shutdown(void);
void hr_qt_set_css(const char *css, int use_document_css);
void *hr_qt_open(const char *path);    /* fz_document*；NULL=失败 */
int  hr_qt_count(void *doc);
int  hr_qt_layout(void *doc, float w, float h, float em);  /* reflowable；0=ok */
int  hr_qt_render(void *doc, int page, int width, int mode, int tint,
                  int *outW, int *outH, unsigned char **outRGB);  /* 0=ok；malloc RGB，调用方 free */
void hr_qt_close(void *doc);
const char *hr_qt_last_error(void);

/* ---- M1 起新增（与 DocumentHandle 同语义） ---- */

/* 目录：拍平回调，level 从 0 起；page 为 0 基绝对页码（解析失败 -1）。返回条目数。 */
typedef int (*hr_outline_cb)(void *ud, int level, const char *title, int page);
int  hr_qt_outline(void *doc, hr_outline_cb cb, void *ud);

/* 页面盒子尺寸（pt，去偏移后宽高）；0=ok */
int  hr_qt_page_size(void *doc, int page, float *w, float *h);

/* 元数据：key 0=Title 1=Author；返回内部缓冲（UTF-8，下次调用前有效），空串=无 */
const char *hr_qt_meta(void *doc, int key);

/* ---- M3 远程流式打开 ---- */

/* 读回调：从 pos 起取 len 字节到 buf，返回实取字节数（<len=提前结束），-1=错误。
 * 线程模型：mupdf 任意线程回调；由实现方保证线程安全（块缓存自带锁）。 */
typedef int (*hr_stream_read)(void *ud, long long pos, unsigned char *buf, int len);

/* 经自定义流打开文档（AndroidSync 远程书块缓存流式打开同思路）。
 * magic=格式小写扩展名（pdf/epub/txt/mobi/fb2/xps/cbz…）。size=总字节数。 */
void *hr_qt_open_stream(const char *magic, long long size, hr_stream_read cb, void *ud);

/* ---- M4 划词 / 搜索 / 段落 / PDF 注解 ---- */

/* 划词：页坐标两点 → 选中文字（UTF-8，调用方 free）+ 高亮矩形（页坐标，返回条数）。
 * rects 每条 {x0,y0,x1,y1}；rectsBuf 由调用方 malloc float[4*n]（n=rects 出参）。返回 0=ok。 */
int hr_qt_selection(void *doc, int page, float ax, float ay, float bx, float by,
                    char **outText, float **outRects, int *outRectN);

/* 页盒子（x0,y0,x1,y1）；0=ok */
int hr_qt_page_box(void *doc, int page, float box[4]);

/* 指定页搜索命中数（无文字层=0） */
int hr_qt_search_page(void *doc, int page, const char *needle);

/* 页面段落（stext block 级，UTF-8；回调整段返回 1 继续）；返回段数 */
typedef int (*hr_paras_cb)(void *ud, const char *para);
int hr_qt_page_paragraphs(void *doc, int page, hr_paras_cb cb, void *ud);

/* PDF 持久高亮写回并另存副本（items: 页/颜色ARGB/四边形页坐标 float[4n]）；1=ok */
int hr_qt_pdf_export_highlights(void *doc, const char *destPath,
                                const int *pages, const int *argbs,
                                const float *const *quadsArr, const int *quadNs, int n);

/* 某页 PDF 注解数（非 PDF=-1） */
int hr_qt_pdf_annotation_count(void *doc, int page);


/* ---- v0.59.0 转换层 ---- */

/* DOC(Word 6/95/97) → DocBook XML 正文（antiword 编入 dll；UTF-8；pOutFile 直写不碰 stdout）。
 * 返回 1=ok（调用方读文件内容当 HTML body 缓存，DocConvert 同语义）；0=失败。 */
int hr_qt_doc_to_html(const char *input, const char *output);
/* v0.61.0 批次三：docx/odt/rtf → HTML 片段（同 doc 链语义，应用侧包 HTML 缓存） */
int hr_qt_docx_to_html(const char *input, const char *output);
int hr_qt_odt_to_html(const char *input, const char *output);
int hr_qt_rtf_to_html(const char *input, const char *output);

#ifdef __cplusplus
/* P2 适配 2026-11-04（B3/B4/B2）：加速器/分章计数/zip 首书（对齐 WIN DocumentHandle） */
int hr_qt_count_chapters(void *doc);                       /* fz_count_chapters；djvu=1 */
int hr_qt_count_pages_of(void *doc, int chapter);          /* fz_count_pages_in_chapter */
int hr_qt_save_accel(void *doc, const char *accelPath);    /* fz_save_accelerator；0=ok */
void *hr_qt_open_accel(const char *path, const char *accelPath); /* 损坏回退由调用方做 */
int hr_qt_zip_first_book(const char *zipPath, const char *extsCsv,
                         const char *destDir, char *outName, int outLen);
/* v0.105.0（P1-5）：CBR→解包全部条目到 destDir（应用以 <key>.ldir 目录交给引擎 cbz
 * 处理器直开）；0=ok。防穿越条目跳过；加密 rar 返回 4；未编 unrar 返回 100。 */
int hr_qt_rar_extract(const char *rarPath, const char *destDir,
                      char *outName, int outLen);

/* v0.103.0（P1-2）：HTML 排版期延迟图解开关（fz_set_defer_html_images，ctx 级）。
 * 大 EPUB（>=100MB）layout 前开、排版完关：图片不在排版期解码，渲染时按需解码。
 * 对齐安卓 deferHtml（MuPdfDocument Round 12）。 */
void hr_qt_defer_images(int defer);
}
#endif

#endif /* HR_BRIDGE_H */
