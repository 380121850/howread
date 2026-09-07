/**
 * MuPDF NAPI bindings (libmupdf_napi.so).
 */
export interface RenderResult {
  width: number;
  height: number;
  /** RGBA_8888 pixels, width * height * 4 bytes */
  data: ArrayBuffer;
}

export interface TextRect {
  x0: number;
  y0: number;
  x1: number;
  y1: number;
}

export interface DocumentInfo {
  title?: string;
  author?: string;
  subject?: string;
  creator?: string;
  producer?: string;
  creationDate?: string;
  modDate?: string;
}

export interface TocEntry {
  title: string;
  /** target page, -1 if the link is not page-based */
  page: number;
  depth: number;
}

/** Normalized (0..1) rectangle within the page, used for cropping */
export interface CropRect {
  x0: number;
  y0: number;
  x1: number;
  y1: number;
}

/** An annotation on a page. Coordinates are normalized (0..1). */
export interface AnnotationInfo {
  /** 0-based index within the page's annotation list */
  index: number;
  /** 'highlight' | 'underline' | 'strikeout' | 'ink' | 'text' | 'unknown' */
  type: string;
  x0: number;
  y0: number;
  x1: number;
  y1: number;
  /** text content for text notes / popup text */
  contents: string;
}

/**
 * Render options for renderPageAsync.
 * rotationDeg must be a multiple of 90 (0/90/180/270).
 * invert flips R/G/B channels after rendering.
 * crop selects a normalized sub-rectangle of the rendered page (0..1).
 */
export interface RenderOptions {
  zoom: number;
  rotationDeg?: number;
  invert?: boolean;
  crop?: CropRect;
}

export interface MupdfDocument {
  /** opaque native handle; only pass back into this module */
  handle: ESObject;
}

export function version(): string;
export function openDocument(path: string): ESObject;
/** Open document via file descriptor (from @ohos.file.fs.openSync) */
export function openDocumentByFd(fd: number): ESObject;
export function pageCount(handle: ESObject): number;
export function renderPage(handle: ESObject, pageNumber: number, zoom: number): RenderResult;
/** Async render: returns Promise<RenderResult>, renders on worker thread with pthread-locked fz_context */
export function renderPageAsync(handle: ESObject, pageNumber: number, options: RenderOptions): Promise<RenderResult>;
/** Get table of contents (outline) as a flat list with depth markers */
export function getToc(handle: ESObject): TocEntry[];
/** Native media size in points at 0 degrees rotation */
export function getPageSize(handle: ESObject, pageNumber: number): TextRect;
/**
 * Re-layout a reflowable document (EPUB/HTML/TXT) to the given page size.
 * widthPx/heightPx are in points; em is the base font size in points.
 * css (optional) is user CSS applied before layout (e.g. body margin /
 * line-height) — reflowable docs re-parse with it when parameters change.
 * No-op for fixed-layout documents (PDF). Call before rendering.
 */
export function layoutDocument(handle: ESObject, widthPx: number, heightPx: number, em: number, css?: string): void;
/** Whether the document is reflowable (EPUB/HTML/TXT) vs fixed-layout (PDF). */
export function isReflowable(handle: ESObject): boolean;
/** List annotations on a page (PDF only). Coordinates normalized 0..1. */
export function getAnnotations(handle: ESObject, pageNumber: number): AnnotationInfo[];
/**
 * Add a highlight annotation on a PDF page.
 * Coordinates are normalized (0..1); color is '#rrggbb' hex string.
 */
export function addHighlight(handle: ESObject, pageNumber: number, x0: number, y0: number,
  x1: number, y1: number, color: string): void;
/** Add an ink (freehand) annotation from a stroke of normalized points. */
export function addInkStroke(handle: ESObject, pageNumber: number, points: TextRect[]): void;
/**
 * Add a markup annotation (underline / strikeout / squiggly / highlight) from
 * a list of normalized rects — one quad per rect. type is one of
 * 'highlight' | 'underline' | 'strikeout' | 'squiggly'.
 */
export function addMarkupAnnotation(handle: ESObject, pageNumber: number, rects: TextRect[],
  type: string, color: string): void;
/** Add a text (sticky-note) annotation at a normalized point with contents. */
export function addTextNote(handle: ESObject, pageNumber: number, x: number, y: number,
  text: string, color: string): void;
/** Delete the annotation at `index` on the page. */
export function deleteAnnotation(handle: ESObject, pageNumber: number, index: number): void;
/** Save the (modified) document back to `path` (PDF only). */
export function saveDocument(handle: ESObject, path: string): void;
/** Extract text content from a page */
export function getText(handle: ESObject, pageNumber: number, zoom: number): string;
/** Search text and return bounding rectangles */
export function searchText(handle: ESObject, text: string, pageNumber: number): TextRect[];

/** Sprint O1: search entire document. Returns JSON string: {"pages":[{"page":N,"count":M}],"totalHits":T} */
export function searchDocument(handle: ESObject, text: string): string;
/** Get document metadata */
export function getDocumentInfo(handle: ESObject): DocumentInfo;
/** A text line on a page (normalized 0..1): bbox + line text + per-char x bounds. */
export interface TextLine extends TextRect {
  text: string;
  /** per-char [x0, x1] pairs flattened, same order as `text` */
  chars: number[];
}

/** Get text lines on a page (normalized 0..1). Returns JSON string: TextLine[]. */
export function getTextRects(handle: ESObject, pageNumber: number): string;
/** Load a custom font file for reflowable documents. Returns true if applied. */
export function loadFont(handle: ESObject, fontPath: string): boolean;
export function closeDocument(handle: ESObject): void;
