package com.foobnix.pdf.info.wrapper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.foobnix.android.utils.Keyboards;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.model.AppBookmark;
import com.foobnix.pdf.info.BookmarksData;
import com.foobnix.pdf.info.model.OutlineLinkWrapper;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.presentation.BookmarksAdapter;

import java.util.List;
import com.foobnix.sys.TempHolder;

public class ListBoxHelper {

    /**
     * HowRead 章节锚（B锚）捕获：加签时记录当前页所属章节的目录身份——
     * 章节序号（本机目录索引）+ 段内比例 + 标题指纹。章节是内容固有结构，
     * 与设备无关：跨设备（手机/平板页数不同）或跨排版纪元（改字号重排）跳转时，
     * 按对端本机目录把段内比例折算回页码，精度从"全书百分比"提升到"章节内比例"。
     * 目录未加载/无目录/首章之前不捕获，一切保持原有百分比回落。
     */
    public static void captureSectionAnchor(final DocumentController controller, final AppBookmark bookmark) {
        try {
            if (controller == null || bookmark == null) {
                return;
            }
            final List<OutlineLinkWrapper> outline = controller.getCurrentOutline();
            final int pages = controller.getPageCount();
            final int cur1 = controller.getCurentPageFirst1(); // 1 基，与 targetPage 同空间（TOC 同款）
            if (TxtUtils.isListEmpty(outline) || pages <= 0 || cur1 < 1) {
                return;
            }
            // 目录按文档序排列：取最后一个起始页 <= 当前页的条目（同页多级取最后一条=更深一级）
            int best = -1;
            for (int i = 0; i < outline.size(); i++) {
                final int tp = outline.get(i).targetPage;
                if (tp >= 1 && tp <= cur1) {
                    best = i;
                }
            }
            if (best < 0) {
                return; // 首章之前（封面/前言/版权页）：不捕获
            }
            final String title = outline.get(best).getTitleAsString();
            if (TxtUtils.isEmpty(title)) {
                return; // 无标题就没有可信指纹，宁可不用章节锚
            }
            final int start1 = outline.get(best).targetPage;
            int end1 = pages + 1; // 最后一章：段尾=书尾
            for (int j = best + 1; j < outline.size(); j++) {
                final int tpj = outline.get(j).targetPage;
                if (tpj > start1) {
                    end1 = tpj;
                    break;
                }
            }
            if (end1 <= start1 || cur1 >= end1) {
                return; // 目录页号异常，放弃
            }
            bookmark.sec = best;
            bookmark.sr = (cur1 - start1) / (float) (end1 - start1);
            bookmark.st = title;
        } catch (final Throwable t) {
            LOG.e(t);
        }
    }

    /**
     * 章节锚解析：标题指纹与本书目录匹配时，按段内比例折算本机页码（1 基，
     * 与 TOC 跳转同空间）；不可验证（目录未加载/条目不足/指纹失配）返回 -1，
     * 调用方回落百分比。指纹校验是安全边界：两端目录条目数可能不同
     * （启发式目录/格式差异），只有标题对得上才允许按序号取区间。
     */
    public static int resolveSectionLanding(final DocumentController controller, final AppBookmark bookmark, final int pages) {
        try {
            if (controller == null || bookmark == null || pages <= 0) {
                return -1;
            }
            if (bookmark.sec < 0 || bookmark.sr < 0f || bookmark.sr >= 1f) {
                return -1;
            }
            final List<OutlineLinkWrapper> outline = controller.getCurrentOutline();
            if (TxtUtils.isListEmpty(outline) || bookmark.sec >= outline.size()) {
                return -1;
            }
            final OutlineLinkWrapper item = outline.get(bookmark.sec);
            if (bookmark.st == null || !bookmark.st.equals(item.getTitleAsString())) {
                return -1;
            }
            final int start1 = item.targetPage;
            if (start1 < 1 || start1 > pages) {
                return -1;
            }
            int end1 = pages + 1;
            for (int j = bookmark.sec + 1; j < outline.size(); j++) {
                final int tpj = outline.get(j).targetPage;
                if (tpj > start1) {
                    end1 = tpj;
                    break;
                }
            }
            if (end1 <= start1) {
                return -1;
            }
            final int target1 = start1 + Math.round(bookmark.sr * (end1 - start1));
            return Math.max(start1, Math.min(end1 - 1, Math.min(target1, pages)));
        } catch (final Throwable t) {
            LOG.e(t);
            return -1;
        }
    }

    /**
     * 书签落点统一解析（1 基）：纪元内绝对页 → 章节锚 → 百分比回落。
     * 跳转（jumpToBookmark）与页码显示（书签列表/快速导航条）共用，
     * 保证显示页码与实际落点一致。
     */
    public static int resolveLandingPage(final DocumentController controller, final AppBookmark bookmark, final int pages) {
        if (bookmark.pg >= 0 && bookmark.pgs == pages && pages > 0 && bookmark.pg < pages) {
            return bookmark.pg + 1; // 同一排版纪元内：绝对页精确
        }
        final int bySection = resolveSectionLanding(controller, bookmark, pages);
        if (bySection > 0) {
            return bySection; // 跨纪元/跨设备：章节锚折算
        }
        return bookmark.getPage(pages); // 百分比回落（原语义）
    }

    public static void showAddDialog(final DocumentController controller,
                                     final List<AppBookmark> objects,
                                     final List<AppBookmark> allData,
                                     final BookmarksAdapter bookmarksAdapter,
                                     String text,
                                     final Runnable onRefresh) {
        final Activity a = controller.getActivity();

        final AlertDialog.Builder builder = new AlertDialog.Builder(a);

        builder.setTitle(a.getString(R.string.bookmark_on_page_) + " " + controller.getCurentPageFirst1());

        LinearLayout layout = new LinearLayout(a);
        layout.setOrientation(LinearLayout.VERTICAL);

        final EditText editText = new EditText(a);
        // editText.setInputType(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES |
        // InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        // editText.setHorizontallyScrolling(false);
        editText.setLines(6);
        editText.setGravity(Gravity.TOP);
        editText.setText(text);
        editText.requestFocus();

        CheckBox isFloat = new CheckBox(a);
        isFloat.setText(a.getString(R.string.floating_bookmark));

        layout.addView(editText);
        layout.addView(isFloat);

        builder.setView(layout);

        builder.setPositiveButton(R.string.add, new DialogInterface.OnClickListener() {
            @Override public void onClick(final DialogInterface dialog, final int id) {
                try {
                    final String text = editText.getText().toString();
                    if (text != null && !text.trim().equals("")) {
                        // keep the raw remote:// form (File collapses it to
                        // "remote:/", which no remote check would recognize)
                        final AppBookmark bookmark = new AppBookmark(
                                com.foobnix.remote.RemoteBook.fixCollapsed(controller.getCurrentBook().getPath()),
                                                                     text,
                                                                     controller.getPercentage());
                        // capture the absolute page now: percent alone breaks
                        // when the full layout is still growing
                        bookmark.pg = Math.max(0, controller.getCurentPageFirst1() - 1);
                        // pgs = PGS_WINDOW marks a fast-open window capture: the
                        // phase-two finisher rewrites pgs/p once the count is final
                        bookmark.pgs = com.foobnix.sys.TempHolder.layoutComplete
                                ? controller.getPageCount() : AppBookmark.PGS_WINDOW;
                        bookmark.isF = isFloat.isChecked();
                        captureSectionAnchor(controller, bookmark);
                        BookmarksData.get().add(bookmark);
                        if (objects != null) {
                            objects.add(0, bookmark);
                            allData.add(0, bookmark);
                        }
                        if (bookmark != null) {
                            bookmarksAdapter.notifyDataSetChanged();
                        }
                    }
                } catch (final Exception e) {
                    // not important
                }
                Keyboards.close(editText);
                Keyboards.hideNavigation(controller.getActivity());

                if (onRefresh != null) {
                    onRefresh.run();
                }
            }
        });

        builder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
            @Override public void onClick(final DialogInterface dialog, final int id) {
                Keyboards.close(editText);
                Keyboards.hideNavigation(controller.getActivity());

                dialog.dismiss();
            }
        });

        builder.show();
    }

    public static void addBookmark(DocumentController controller, String text) {
        final AppBookmark bookmark = new AppBookmark(
                com.foobnix.remote.RemoteBook.fixCollapsed(controller.getCurrentBook().getPath()),
                                                     text,
                                                     controller.getPercentage());
        bookmark.pg = Math.max(0, controller.getCurentPageFirst1() - 1);
        // pgs = PGS_WINDOW marks a fast-open window capture: the phase-two
        // finisher rewrites pgs/p once the count is final
        bookmark.pgs = com.foobnix.sys.TempHolder.layoutComplete
                ? controller.getPageCount() : AppBookmark.PGS_WINDOW;
        captureSectionAnchor(controller, bookmark);
        BookmarksData.get().add(bookmark);
    }

    /**
     * Jump to a bookmark. Landing priority: era-exact absolute page →
     * section (chapter) anchor, which survives page-count changes across
     * devices and layout eras → percent fallback. Percent-only legacy
     * bookmarks wait for the fast-open background layout to settle:
     * resolving p against the partial count lands hundreds of pages off
     * in big reflow books.
     */
    public static void jumpToBookmark(final DocumentController controller, final AppBookmark bookmark) {
        final android.app.Activity act = controller.getActivity();
        final int count = controller.getPageCount();
        // era-bound: the anchor is only exact while the page count matches
        // the one at add time (a font change repaginates the book)
        final boolean anchored = bookmark.pg >= 0 && bookmark.pgs == count && count > 0 && bookmark.pg < count;
        if (anchored || com.foobnix.sys.TempHolder.layoutComplete) {
            // 落点优先级：纪元内绝对页 → 章节锚 → 百分比（resolveLandingPage 内部处理）
            controller.onGoToPage(resolveLandingPage(controller, bookmark, count));
            return;
        }
        if (act == null || act.isDestroyed() || act.isFinishing()) {
            return;
        }
        TempHolder.pendingLayoutJump = () -> {
            TempHolder.pendingLayoutJump = null;
            if (!act.isDestroyed() && !act.isFinishing()) {
                controller.onGoToPage(resolveLandingPage(controller, bookmark, controller.getPageCount()));
            }
        };
        final Runnable mine = TempHolder.pendingLayoutJump;
        // safety net: if the background layout stalls, fall back to the
        // old best-effort jump instead of swallowing the tap
        act.getWindow().getDecorView().postDelayed(new Runnable() {
            @Override public void run() {
                if (TempHolder.pendingLayoutJump == mine) {
                    mine.run();
                }
            }
        }, 15000);
    }

    public static void showEditDeleteDialog(final AppBookmark bookmark,
                                            DocumentController controller,
                                            final BookmarksAdapter bookmarksAdapter,
                                            final List<AppBookmark> objects,
                                            final List<AppBookmark> allData,
                                            Runnable onRefresh) {

        final AlertDialog.Builder builder = new AlertDialog.Builder(controller.getActivity());
        builder.setTitle(
                controller.getActivity().getString(R.string.bookmark_on_page_) + " " + controller.getCurentPage());

        LinearLayout layout = new LinearLayout(controller.getActivity());
        layout.setOrientation(LinearLayout.VERTICAL);

        final EditText editText = new EditText(controller.getActivity());
        editText.setLines(6);
        editText.setGravity(Gravity.TOP);
        editText.setText(bookmark.getText());
        editText.requestFocus();

        CheckBox isFloat = new CheckBox(controller.getActivity());
        isFloat.setText(controller.getString(R.string.floating_bookmark));
        isFloat.setChecked(bookmark.isF);

        layout.addView(editText);
        layout.addView(isFloat);

        builder.setView(layout);

        builder.setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
            @Override public void onClick(final DialogInterface dialog, final int id) {
                try {
                    final String text = editText.getText().toString();
                    if (text != null && !text.trim().equals("")) {
                        bookmark.text = text;
                        //BookmarksData.get().remove(bookmark);
                        bookmark.isF = isFloat.isChecked();
                        BookmarksData.get().add(bookmark);

                        bookmarksAdapter.notifyDataSetChanged();
                        Keyboards.close(editText);

                        if (onRefresh != null) {
                            onRefresh.run();
                        }
                    }
                } catch (final Exception e) {
                }
            }
        });

        builder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
            @Override public void onClick(final DialogInterface dialog, final int id) {
                Keyboards.close(editText);
                dialog.dismiss();
            }
        });
        builder.setNeutralButton(R.string.delete, new DialogInterface.OnClickListener() {
            @Override public void onClick(final DialogInterface dialog, final int id) {
                Keyboards.close(editText);
                BookmarksData.get().remove(bookmark);
                objects.remove(bookmark);
                allData.remove(bookmark);
                bookmarksAdapter.notifyDataSetChanged();

                if (onRefresh != null) {
                    onRefresh.run();
                }

                dialog.dismiss();
            }
        });

        builder.show();
    }
}
