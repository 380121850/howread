package com.foobnix.model;

import com.foobnix.android.utils.LOG;

import java.io.File;
import java.util.List;
import java.util.Objects;

public class AppBookmark implements MyPath.RelativePath {
    public String path;
    public String text;

    public float p;
    // absolute 0-based page captured at add time: independent of the page
    // count, so it survives the fast-open partial window. -1 = legacy
    // entry (percent fallback).
    public int pg = -1;
    // total page count of the layout era pg was captured in. A font-size
    // change repaginates the book and the same index holds different
    // content, so the anchor only applies while pgs == current count;
    // across eras the percent anchor is the stable one.
    public int pgs = -1;
    public long t;
    public boolean isF = false;

    // AI reading-note fields (reuses the same JSON storage as bookmarks)
    public boolean isAiNote = false;
    public String aiAnswer = "";

    transient public File file;

    // Per-note list carried by a merged note entry (display only, never serialized)
    transient public List<AppBookmark> notes;

    // True for the synthetic "book summary" rows in the by-book view
    // (display only, never serialized — must NOT be matched by text content)
    transient public boolean isBookHeader;

    public AppBookmark() {

    }

    public AppBookmark(String path, String text, float percent) {
        super();
        this.path = MyPath.toRelative(path);
        this.text = text;
        this.p = percent;
        t = System.currentTimeMillis();

    }

    public int getPage(int pages) {
        LOG.d("MyMath getPage",p, pages);
        if (pages <= 0) {
            return 1;
        }
        // Absolute anchor wins ONLY within its layout era (pgs == pages):
        // within one era it is exact even while the layout is still
        // growing; across eras percent is the stable anchor.
        if (pg >= 0 && pgs == pages && pg < pages) {
            return pg + 1;
        }
        // Clamp p to [0, 1] range to handle negative values and NaN
        float clampedP = Math.max(0f, Math.min(1f, p));
        return Math.max(1, Math.round(clampedP * pages));
    }

    public String getText() {
        return text;
    }

    public String getPath() {
        return MyPath.toAbsolute(path);
    }

    public void setPath(String path) {
        this.path = MyPath.toRelative(path);
    }

    public float getPercent() {
        return p;
    }

    public long getTime() {
        return t;
    }

    @Override
    public int hashCode() {
        // Must match equals(): same fields (t, path, text), so two equal
        // objects always share a hash even when p differs.
        return java.util.Objects.hash(t, path, text);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        AppBookmark a = (AppBookmark) obj;
        return t == a.t && Objects.equals(path, a.path) && Objects.equals(text, a.text);
    }


}
