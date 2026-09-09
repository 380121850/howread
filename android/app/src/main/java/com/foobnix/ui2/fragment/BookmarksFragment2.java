package com.foobnix.ui2.fragment;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MenuItem.OnMenuItemClickListener;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.util.Pair;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.foobnix.android.utils.Dips;
import com.foobnix.android.utils.Keyboards;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.ResultResponse;
import com.foobnix.android.utils.ResultResponse2;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.dao2.FileMeta;
import com.foobnix.model.AppBookmark;
import com.foobnix.model.AppState;
import com.foobnix.model.MyPath;
import com.foobnix.pdf.info.AppsConfig;
import com.foobnix.pdf.info.BookmarksData;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.TintUtil;
import com.foobnix.pdf.info.view.AlertDialogs;
import com.foobnix.pdf.info.view.MyPopupMenu;
import com.foobnix.pdf.info.widget.ChooserDialogFragment;
import com.foobnix.pdf.info.widget.FileInformationDialog;
import com.foobnix.pdf.info.wrapper.PopupHelper;
import com.foobnix.ui2.AppDB;
import com.foobnix.ui2.adapter.BookmarksAdapter2;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class BookmarksFragment2 extends UIFragment<AppBookmark> {
    public static final Pair<Integer, Integer> PAIR = new Pair<Integer, Integer>(R.string.bookmarks, R.drawable.glyphicons_73_bookmark);
    private static final String BOOK_PREFIX = "@book";

    BookmarksAdapter2 bookmarksAdapter;
    View bookmarksSearchContainer, bookmarksClearFilter, topPanel;
    TextView exportBookmarks, importBookmarks, allBookmarks;
    EditText bookmarksEditSearch;
    String readingNoteLabel;
    ImageView onListGrid, search;

    @Override
    public Pair<Integer, Integer> getNameAndIconRes() {
        return PAIR;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_bookmarks2, container, false);
        readingNoteLabel = getString(R.string.reading_note);
        recyclerView = (RecyclerView) view.findViewById(R.id.recyclerView);

        topPanel = view.findViewById(R.id.topPanel);
        bookmarksSearchContainer = view.findViewById(R.id.bookmarksSearchContainer);
        bookmarksClearFilter = view.findViewById(R.id.bookmarksClearFilter);
        bookmarksEditSearch = (EditText) view.findViewById(R.id.bookmarksEditSearch);
        bookmarksEditSearch.addTextChangedListener(filterTextWatcher);

        onListGrid = (ImageView) view.findViewById(R.id.onListGrid);
        exportBookmarks = (TextView) view.findViewById(R.id.exportBookmarks);
        importBookmarks = (TextView) view.findViewById(R.id.importBookmarks);
        search = view.findViewById(R.id.search);
        allBookmarks = (TextView) view.findViewById(R.id.allBookmarks);
        TxtUtils.underlineTextView(allBookmarks).setOnClickListener(onCleanSearch);

        TxtUtils.underlineTextView(exportBookmarks).setOnClickListener(exportBookmarksClickListener);
        TxtUtils.underlineTextView(importBookmarks).setOnClickListener(importBookmarksClickListener);
        search.setOnClickListener(searchBookmarks);
        bookmarksSearchContainer.setVisibility(View.GONE);

        bookmarksClearFilter.setOnClickListener(onCleanSearch);

        bookmarksAdapter = new BookmarksAdapter2();

        RecyclerView.LayoutManager mLayoutManager = new LinearLayoutManager(getActivity());
        recyclerView.setLayoutManager(mLayoutManager);
        recyclerView.setAdapter(bookmarksAdapter);
        bookmarksAdapter.setOnDeleteClickListener(onDeleteResponse);
        bookmarksAdapter.setOnMoreClickListener((item, anchor) -> showNoteExportMenu(item, anchor));

        bookmarksAdapter.setOnItemClickListener(onItemClickListener);
        bookmarksAdapter.setOnItemLongClickListener(new ResultResponse<AppBookmark>() {

            @Override
            public boolean onResultRecive(AppBookmark result) {
                if (result == null || result.getPath() == null) {
                    return true;
                }
                FileInformationDialog.showFileInfoDialog(getActivity(), new File(result.getPath()), null);
                return true;
            }
        });

        onListGrid.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                popupMenu(onListGrid);
            }
        });

        view.findViewById(R.id.onSettings).setOnClickListener(v -> {
            MyPopupMenu menu = new MyPopupMenu(v);
            menu.getMenu().addCheckbox(getString(R.string.show_quick_bookmarks), AppState.get().isShowFastBookmarks, (a, is) -> {
                AppState.get().isShowFastBookmarks = is;
                populate();
                LOG.d("show--show_quick_bookmarks");

            });
            menu.getMenu().addCheckbox(getString(R.string.show_only_available_books), AppState.get().isShowOnlyAvailabeBooks, (a, is) -> {
                AppState.get().isShowOnlyAvailabeBooks = is;
                populate();
                LOG.d("show--_only_available_books");
            });

            menu.getMenu(R.drawable.glyphicons_578_share, R.string.share,
                    () -> ExtUtils.sendAllBookmarksTo(getActivity()));


            menu.show();

        });

        populate();
        onTintChanged();
        return view;
    }


    private void popupMenu(final ImageView onGridList) {
        MyPopupMenu p = new MyPopupMenu(getActivity(), onGridList);
        PopupHelper.addPROIcon(p, getActivity());

        List<Integer> names = Arrays.asList(R.string.bookmark_by_date, R.string.bookmark_by_book);
        final List<Integer> icons = Arrays.asList(R.drawable.my_glyphicons_114_paragraph_justify, R.drawable.glyphicons_159_thumbnails_list);
        final List<Integer> actions = Arrays.asList(AppState.BOOKMARK_MODE_BY_DATE, AppState.BOOKMARK_MODE_BY_BOOK);

        for (int i = 0; i < names.size(); i++) {
            final int index = i;
            p.getMenu().add(names.get(i)).setIcon(icons.get(i)).setOnMenuItemClickListener(new OnMenuItemClickListener() {

                @Override
                public boolean onMenuItemClick(MenuItem item) {
                    AppState.get().bookmarksMode = actions.get(index);
                    onGridList.setImageResource(icons.get(index));
                    bookmarksEditSearch.setText("");
                    bookmarksSearchContainer.setVisibility(View.GONE);
                    populate();
                    return false;
                }
            });
        }
        p.show();
    }

    @Override
    public void onTintChanged() {
        TintUtil.setBackgroundFillColor(topPanel, TintUtil.color);
        TintUtil.setStrokeColor(bookmarksEditSearch, TintUtil.color);
    }

    private final TextWatcher filterTextWatcher = new TextWatcher() {

        @Override
        public void afterTextChanged(final Editable s) {
        }

        @Override
        public void beforeTextChanged(final CharSequence s, final int start, final int count, final int after) {
        }

        @Override
        public void onTextChanged(final CharSequence s, final int start, final int before, final int count) {
            handler.removeCallbacks(timer);
            handler.postDelayed(timer, 500);
        }
    };
    Runnable timer = new Runnable() {

        @Override
        public void run() {
            populate();
        }
    };

    OnClickListener exportBookmarksClickListener = new OnClickListener() {

        @Override
        public void onClick(View v) {
            final PopupMenu popupMenu = new PopupMenu(getActivity(), exportBookmarks);

            final MenuItem toEmail = popupMenu.getMenu().add(R.string.email);
            toEmail.setOnMenuItemClickListener(new OnMenuItemClickListener() {

                @Override
                public boolean onMenuItemClick(final MenuItem item) {
                    ExtUtils.exportAllBookmarksToGmail(getActivity());
                    return false;
                }
            });

            final MenuItem toFile = popupMenu.getMenu().add(R.string.file);
            toFile.setOnMenuItemClickListener(new OnMenuItemClickListener() {

                @Override
                public boolean onMenuItemClick(final MenuItem item) {
                    ExtUtils.exportAllBookmarksToFile(getActivity());
                    return false;
                }
            });

            final MenuItem toJson = popupMenu.getMenu().add("JSON");
            toJson.setOnMenuItemClickListener(new OnMenuItemClickListener() {

                @Override
                public boolean onMenuItemClick(final MenuItem item) {

                    ExtUtils.exportAllBookmarksToJson(getActivity(), null);
                    return false;
                }
            });
            popupMenu.show();

        }
    };

    OnClickListener onCleanSearch = new OnClickListener() {

        @Override
        public void onClick(View v) {
            bookmarksEditSearch.setText("");
            populate();
        }
    };

    OnClickListener importBookmarksClickListener = new OnClickListener() {

        @Override
        public void onClick(View v) {
            final PopupMenu popupMenu = new PopupMenu(importBookmarks.getContext(), importBookmarks);

            final MenuItem toJson = popupMenu.getMenu().add("JSON");
            toJson.setOnMenuItemClickListener(new OnMenuItemClickListener() {

                @Override
                public boolean onMenuItemClick(final MenuItem item) {

                    ExtUtils.importAllBookmarksFromJson(getActivity(), new Runnable() {

                        @Override
                        public void run() {
                            populate();
                        }
                    });

                    return false;
                }
            });
            popupMenu.show();

        }
    };

    OnClickListener searchBookmarks = new OnClickListener() {

        @Override
        public void onClick(View v) {
            boolean isVisible = bookmarksSearchContainer.getVisibility() == View.VISIBLE;
            if (isVisible) {
                bookmarksSearchContainer.setVisibility(View.GONE);
                Keyboards.close(bookmarksEditSearch);
            } else {
                bookmarksSearchContainer.setVisibility(View.VISIBLE);
            }
            if (TxtUtils.isNotEmpty(bookmarksEditSearch.getText().toString())) {
                bookmarksEditSearch.setText("");
                // filterByText();
            }

        }
    };

    @Override
    public boolean isBackPressed() {
        if (bookmarksEditSearch != null && TxtUtils.isNotEmpty(bookmarksEditSearch.getText().toString())) {
            bookmarksEditSearch.setText("");
            populate();
            return true;
        }
        return false;
    }

    ResultResponse<AppBookmark> onTitleClickListener = new ResultResponse<AppBookmark>() {

        @Override
        public boolean onResultRecive(AppBookmark result) {
            // bookmarksSearchContainer.setVisibility(View.VISIBLE);
            bookmarksEditSearch.setText(BOOK_PREFIX + " " + result.getPath());
            populate();
            return false;
        }
    };

    ResultResponse<AppBookmark> onItemClickListener = new ResultResponse<AppBookmark>() {

        @Override
        public boolean onResultRecive(AppBookmark result) {
            String text = bookmarksEditSearch.getText().toString().toLowerCase(Locale.US).trim();

            // Check if this is a book header entry (summary line with count)
            boolean isBookHeader = result != null && result.isBookHeader;

            if (isBookHeader) {
                // Clicking a book header: filter to show all bookmarks for this book
                String fileName = ExtUtils.getFileName(result.getPath());
                bookmarksEditSearch.setText(BOOK_PREFIX + " " + fileName);
                populate();
            } else if (result != null && result.isAiNote) {
                // AI notes show their full content in a dialog instead of jumping
                // to the book like a bookmark does.
                showNoteDialog(result);
            } else if (TxtUtils.isNotEmpty(text) || AppState.get().bookmarksMode == AppState.BOOKMARK_MODE_BY_DATE) {
                // Clicking a regular bookmark: open the book at that page
                if (ExtUtils.doifFileExists(getContext(), result.getPath())) {
                    final File file = new File(result.getPath());
                    ExtUtils.showDocumentWithoutDialog2(getActivity(), Uri.fromFile(file), result.getPercent(), null);
                }
            } else {
                // BY_BOOK mode, no search text: filter to this book
                String fileName = ExtUtils.getFileName(result.getPath());
                bookmarksEditSearch.setText(BOOK_PREFIX + " " + fileName);
                populate();
            }
            return false;
        }
    };

    /** Shows an AI reading note (or merged notes) in a scrollable, selectable dialog */
    private void showNoteDialog(AppBookmark note) {
        final Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        // Merged entries carry the full content (all notes, newest first) in aiAnswer
        if (note.notes == null || note.notes.isEmpty()) {
            String content = TxtUtils.isNotEmpty(note.aiAnswer) ? note.aiAnswer : note.text;
            AlertDialogs.showOkDialog(activity, content, null);
            return;
        }
        // Per-note view: the timestamp of each note is a link that jumps to the
        // reading position recorded when the note was saved.
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        final LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(Dips.DP_5, Dips.DP_5, Dips.DP_5, Dips.DP_5);
        final AlertDialog[] dialogRef = new AlertDialog[1];
        int idx = 0;
        for (final AppBookmark n : note.notes) {
            TextView time = new TextView(activity);
            time.setText("[" + fmt.format(n.getTime()) + "]");
            time.setTextSize(14);
            time.setTypeface(null, Typeface.BOLD);
            time.setTextColor(activity.getResources().getColor(R.color.tint_blue));
            time.setPaintFlags(time.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
            time.setPadding(0, Dips.DP_5, 0, Dips.DP_5);
            time.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (dialogRef[0] != null) {
                        dialogRef[0].dismiss();
                    }
                    if (n.getPath() != null && n.getPercent() >= 0f && ExtUtils.doifFileExists(activity, n.getPath())) {
                        final File file = new File(n.getPath());
                        ExtUtils.showDocumentWithoutDialog2(activity, Uri.fromFile(file), n.getPercent(), null);
                    }
                }
            });
            container.addView(time);

            TextView body = new TextView(activity);
            StringBuilder sb = new StringBuilder();
            if (TxtUtils.isNotEmpty(n.text)) {
                sb.append(n.text);
            }
            if (TxtUtils.isNotEmpty(n.aiAnswer)) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append("AI: ").append(n.aiAnswer);
            }
            body.setText(sb.toString());
            body.setTextSize(15);
            body.setTextIsSelectable(true);
            container.addView(body);

            idx++;
            if (idx < note.notes.size()) {
                View divider = new View(activity);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1);
                lp.topMargin = Dips.DP_10;
                lp.bottomMargin = Dips.DP_10;
                divider.setLayoutParams(lp);
                divider.setBackgroundColor(activity.getResources().getColor(R.color.lt_grey_dima));
                container.addView(divider);
            }
        }
        dialogRef[0] = AlertDialogs.showViewDialog(activity, container);
    }

    ResultResponse<AppBookmark> onDeleteResponse = new ResultResponse<AppBookmark>() {

        @Override
        public boolean onResultRecive(AppBookmark result) {
            // Book header entries: remove all bookmarks for that book
            if (result != null && result.isBookHeader) {
                String path = result.getPath();
                List<AppBookmark> all = BookmarksData.get().getAll(getActivity());
                int removedCount = 0;
                for (AppBookmark b : all) {
                    if (path != null && path.equals(b.getPath())) {
                        BookmarksData.get().remove(b);
                        removedCount++;
                    }
                }
                LOG.d("onDeleteResponse", "Removed " + removedCount + " bookmarks for " + path);
                populate();
            } else if (result != null && result.isAiNote && result.notes != null) {
                // Merged-notes entry (mergeNotes): a synthetic object without a
                // backing file/key — delete each real note it carries instead.
                for (AppBookmark n : result.notes) {
                    BookmarksData.get().remove(n);
                }
                populate();
            } else if (bookmarksAdapter.withPageNumber) {
                BookmarksData.get().remove(result);
                populate();
            } else if (result != null && result.getPath() != null) {
                ExtUtils.sendBookmarksTo(getActivity(), new File(result.getPath()));
            }
            return false;
        }
    };

    /** "⋮" on a merged-notes row: open a small menu to export the notes as TXT (default) or Markdown.
     *  PRO feature: locked/fdroid builds get a toast — existing notes stay viewable. */
    private void showNoteExportMenu(final AppBookmark notes, View anchor) {
        if (notes == null) {
            return;
        }
        if (!AppsConfig.isProFeaturesEnabled()) {
            PrefFragment2.proLockedToast(anchor);
            return;
        }
        MyPopupMenu menu = new MyPopupMenu(anchor);
        menu.getMenu(R.drawable.glyphicons_302_square_download,
                anchor.getContext().getString(R.string.notes_export_txt) + " (Pro)",
                () -> exportNotesToFile(notes, "txt"));
        menu.getMenu(R.drawable.glyphicons_302_square_download,
                anchor.getContext().getString(R.string.notes_export_md) + " (Pro)",
                () -> exportNotesToFile(notes, "md"));
        menu.show();
    }

    /**
     * Exports all notes of a book (the merged-notes entry) to a user-chosen file
     * in the given format ("txt" or "md"). Reuses the same "pick folder +
     * filename" picker as the bookmarks export; each note is rendered with its
     * timestamp, a position line (page / percent where the note was taken), the
     * note body and the AI answer.
     */
    private void exportNotesToFile(final AppBookmark merged, final String format) {
        final FragmentActivity activity = getActivity();
        if (activity == null) {
            return;
        }
        String bookName = ExtUtils.getFileName(merged.getPath());
        String sampleName = TxtUtils.fixFileName(bookName + "-notes." + format);
        final String content = renderNotesForExport(merged, format);

        ChooserDialogFragment.createFile(activity, sampleName).setOnSelectListener(new ResultResponse2<String, Dialog>() {
            @Override
            public boolean onResultRecive(String nPath, Dialog dialog) {
                File toFile = new File(nPath);
                if (toFile == null || toFile.getName().trim().length() == 0) {
                    Toast.makeText(activity, "Invalid File name", Toast.LENGTH_LONG).show();
                    return false;
                }
                // The picker's filename is fully editable: if the user dropped or
                // changed the extension, force the one of the chosen format.
                String name = toFile.getName();
                if (!name.toLowerCase(Locale.US).endsWith("." + format)) {
                    toFile = new File(toFile.getParent(), name + "." + format);
                }
                try (FileWriter writer = new FileWriter(toFile)) {
                    writer.write(content);
                    writer.flush();
                    Toast.makeText(activity, R.string.success, Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    LOG.e(e);
                    Toast.makeText(activity, e.getLocalizedMessage(), Toast.LENGTH_LONG).show();
                }
                dialog.dismiss();
                return false;
            }
        });
    }

    /**
     * Renders all notes of the merged entry (newest first) for export. TXT keeps
     * the plain style of {@link #mergeNotes}; MD maps it to light standard
     * Markdown (## heading, > position quote, **AI:** bold, --- separator).
     * Each note gets a position line under its timestamp: "page X / Y (P%)" when
     * the book's page count is known from the AppDB cache, "P%" otherwise.
     */
    private String renderNotesForExport(AppBookmark merged, String format) {
        boolean md = "md".equals(format);
        if (merged.notes == null || merged.notes.isEmpty()) {
            // Defensive fallback: no per-note list, reuse the pre-rendered content
            return TxtUtils.isNotEmpty(merged.aiAnswer) ? merged.aiAnswer : merged.text;
        }
        Integer pages = null;
        try {
            FileMeta m = AppDB.get().load(MyPath.toAbsolute(merged.getPath()));
            if (m != null && m.getPages() != null && m.getPages() > 0) {
                pages = m.getPages();
            }
        } catch (Exception e) {
            LOG.e(e);
        }
        // Local instance on purpose: export may run off the UI thread and
        // SimpleDateFormat is not thread-safe when shared.
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        StringBuilder sb = new StringBuilder();
        for (AppBookmark n : merged.notes) {
            if (sb.length() > 0) {
                sb.append(md ? "\n\n---\n\n" : "\n\n-----------------\n\n");
            }
            String time = "[" + fmt.format(n.getTime()) + "]";
            String percent = TxtUtils.percentFormatInt(n.getPercent());
            String position;
            if (pages != null) {
                int page = Math.max(1, Math.round(n.getPercent() * pages));
                position = getString(R.string.note_export_position_page, page, pages, percent);
            } else {
                position = getString(R.string.note_export_position_percent, percent);
            }
            if (md) {
                sb.append("## ").append(time).append("\n");
                sb.append("> ").append(position).append("\n\n");
            } else {
                sb.append(time).append("\n");
                sb.append(position).append("\n\n");
            }
            boolean hasBody = false;
            if (TxtUtils.isNotEmpty(n.text)) {
                sb.append(n.text);
                hasBody = true;
            }
            if (TxtUtils.isNotEmpty(n.aiAnswer)) {
                if (hasBody) {
                    sb.append("\n\n");
                }
                sb.append(md ? "**AI:** " : "AI: ").append(n.aiAnswer);
            }
        }
        return sb.toString();
    }

    @Override
    public List<AppBookmark> prepareDataInBackground() {
        LOG.d("AppBookmark- prepareDataInBackground");
        handler.removeCallbacks(timer);

        String text = bookmarksEditSearch.getText().toString().toLowerCase(Locale.US).trim();
        if (TxtUtils.isEmpty(text)) {
            List<AppBookmark> bookmarks = BookmarksData.get().getAll(getActivity());

            if (AppState.get().bookmarksMode == AppState.BOOKMARK_MODE_BY_BOOK) {
                // By-book view: show one row per book (its cover + name + item count).
                // Clicking a book row then filters to all bookmarks and notes of that book.
                List<AppBookmark> filtered = new ArrayList<AppBookmark>();

                // Add a "book summary" entry for each unique path
                List<String> uniquePaths = new ArrayList<>();
                for (AppBookmark bookmark : bookmarks) {
                    String p = bookmark.getPath();
                    if (!uniquePaths.contains(p)) {
                        uniquePaths.add(p);
                        // Create a summary entry: copy the first occurrence, mark as book header
                        AppBookmark summary = new AppBookmark();
                        summary.path = bookmark.path;
                        summary.text = ExtUtils.getFileName(p) + "  (" + countBookmarksForPath(bookmarks, p) + " items)";
                        summary.p = 0;
                        summary.t = bookmark.t;
                        summary.isF = false;
                        summary.isAiNote = false; // not an AI note, just a book header
                        summary.isBookHeader = true; // identifies the row reliably (text content is user data)
                        filtered.add(summary);
                    }
                }
                return filtered;
            } else {
                return bookmarks;
            }
        } else {
            List<AppBookmark> filtered = new ArrayList<AppBookmark>();
            List<AppBookmark> bookmarks = BookmarksData.get().getAll(getActivity());

            if (text.startsWith(BOOK_PREFIX)) {
                // Filtering by book (clicked a book header): compare file names so
                // notes and bookmarks of the same book always match, regardless of
                // path prefix differences.
                text = text.replace(BOOK_PREFIX, "").trim().toLowerCase(Locale.US);
                List<AppBookmark> notes = new ArrayList<AppBookmark>();
                List<AppBookmark> marks = new ArrayList<AppBookmark>();
                for (final AppBookmark bookmark : bookmarks) {
                    String file = ExtUtils.getFileName(bookmark.getPath()).toLowerCase(Locale.US);
                    if (file.contains(text) || text.contains(file)) {
                        if (bookmark.isAiNote) {
                            notes.add(bookmark);
                        } else {
                            marks.add(bookmark);
                        }
                    }
                }
                // Merge all AI notes of this book into a single entry
                if (!notes.isEmpty()) {
                    filtered.add(mergeNotes(notes));
                }
                // Bookmarks newest first
                Collections.sort(marks, NOTES_FIRST);
                filtered.addAll(marks);


            } else {
                for (AppBookmark bookmark : bookmarks) {
                    if (bookmark.getText().toLowerCase(Locale.US).contains(text)) {
                        filtered.add(bookmark);
                    }
                }
            }

            return filtered;
        }

    }

    /** Count how many bookmarks belong to a specific path */
    private int countBookmarksForPath(List<AppBookmark> all, String path) {
        int count = 0;
        for (AppBookmark b : all) {
            if (java.util.Objects.equals(b.getPath(), path)) {
                count++;
            }
        }
        return Math.max(count, 1);
    }

    // Sort within one book: AI notes first (newest first), then bookmarks (newest first)
    private static final Comparator<AppBookmark> NOTES_FIRST = new Comparator<AppBookmark>() {

        @Override
        public int compare(AppBookmark o1, AppBookmark o2) {
            if (o1.isAiNote != o2.isAiNote) {
                return o1.isAiNote ? -1 : 1;
            }
            return Long.compare(o2.getTime(), o1.getTime());
        }
    };

    /** Merges all AI notes of one book into a single entry (newest first) */
    private AppBookmark mergeNotes(List<AppBookmark> notes) {
        Collections.sort(notes, new Comparator<AppBookmark>() {
            @Override
            public int compare(AppBookmark o1, AppBookmark o2) {
                return Long.compare(o2.getTime(), o1.getTime());
            }
        });
        // Local instance on purpose: mergeNotes runs on the populate executor
        // (a 2-thread pool) and SimpleDateFormat is not thread-safe when shared.
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        StringBuilder content = new StringBuilder();
        AppBookmark first = notes.get(0);
        for (AppBookmark n : notes) {
            if (content.length() > 0) {
                content.append("\n\n-----------------\n\n");
            }
            content.append("[").append(fmt.format(n.getTime())).append("]\n\n");
            if (TxtUtils.isNotEmpty(n.text)) {
                content.append(n.text);
            }
            if (TxtUtils.isNotEmpty(n.aiAnswer)) {
                content.append("\n\nAI: ").append(n.aiAnswer);
            }
        }
        AppBookmark merged = new AppBookmark();
        merged.path = first.path;
        merged.isAiNote = true;
        merged.p = 0;
        merged.t = first.t;
        merged.text = readingNoteLabel + " (" + notes.size() + ")";
        merged.aiAnswer = content.toString();
        // Carry the per-note entries (newest first) so the dialog can render
        // each note with a clickable timestamp that jumps to its position.
        merged.notes = notes;
        return merged;
    }

    public boolean isPrefixText() {
        String text = bookmarksEditSearch.getText().toString().toLowerCase(Locale.US).trim();
        return text.startsWith(BOOK_PREFIX);
    }

    @Override
    public void populateDataInUI(List<AppBookmark> items) {
        if (AppState.get().bookmarksMode == AppState.BOOKMARK_MODE_BY_DATE) {
            onListGrid.setImageResource(R.drawable.my_glyphicons_114_paragraph_justify);
            bookmarksAdapter.withPageNumber = true;
        } else if (AppState.get().bookmarksMode == AppState.BOOKMARK_MODE_BY_BOOK) {
            // In BY_BOOK mode, show page numbers for regular bookmarks but hide them for book headers.
            // The adapter handles this by checking the item text for "items" suffix.
            onListGrid.setImageResource(R.drawable.glyphicons_159_thumbnails_list);
        }
        if (TxtUtils.isNotEmpty(bookmarksEditSearch.getText().toString().toLowerCase(Locale.US).trim())) {
            bookmarksAdapter.withPageNumber = true;
        }

        bookmarksAdapter.getItemsList().clear();
        if (items != null) {
            bookmarksAdapter.getItemsList().addAll(items);
        }
        bookmarksAdapter.notifyDataSetChanged();

        if (isPrefixText()) {
            allBookmarks.setVisibility(View.VISIBLE);
        } else {
            allBookmarks.setVisibility(View.GONE);
        }

    }

    @Override
    public void notifyFragment() {
        populate();
    }

    @Override
    public void resetFragment() {
        populate();
    }

}
