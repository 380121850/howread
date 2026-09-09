package com.foobnix.ui2.fragment;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.cardview.widget.CardView;
import androidx.core.util.Pair;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.Dips;
import com.foobnix.android.utils.JsonDB;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.dao2.FileMeta;
import com.foobnix.model.AppBookmark;
import com.foobnix.model.AppData;
import com.foobnix.model.AppSP;
import com.foobnix.model.AppState;
import com.foobnix.model.BookStateStore;
import com.foobnix.opds.Entry;
import com.foobnix.pdf.info.BookmarksData;
import com.foobnix.pdf.info.Clouds;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.pdf.info.IMG;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.TintUtil;
import com.foobnix.pdf.info.model.BookCSS;
import com.foobnix.pdf.info.wrapper.UITab;
import com.foobnix.pdf.info.view.MonthlyBarsView;
import com.foobnix.ui2.AppDB;
import com.foobnix.ui2.AppRecycleAdapter;
import com.foobnix.ui2.MainTabs2;
import com.foobnix.webdav.WebDavServer;
import com.foobnix.webdav.WebDavStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Moon+ Reader style dashboard home: vertical sections (recent reading,
 * favorites, file/cloud sources, online library, bookmarks), each with a
 * horizontal cover carousel or icon row and a "more" link.
 */
public class DashboardFragment2 extends UIFragment<FileMeta> {

    public static final Pair<Integer, Integer> PAIR = new Pair<Integer, Integer>(R.string.moon_home, R.drawable.glyphicons_21_home);

    private static final int ROW_LIMIT = 8;

    List<FileMeta> recentBooks = new ArrayList<FileMeta>();
    List<FileMeta> favoriteBooks = new ArrayList<FileMeta>();
    List<AppBookmark> bookmarks = new ArrayList<AppBookmark>();
    List<Entry> opdsCatalogs = new ArrayList<Entry>();

    int totalBooks = 0;
    int readBooks = 0;
    long readTimeMs = 0;
    long readDayMs = 0;
    long readPages = 0;

    TextView statTotalValue;
    TextView statReadValue;
    TextView statHoursValue;
    TextView statTodayValue;
    TextView statSpeedValue;

    CoverAdapter recentAdapter;
    CoverAdapter favAdapter;
    SourceAdapter netAdapter;
    BookmarkAdapter bookmarkAdapter;

    // kept between layout passes: the 我的文件 quick sources are rebuilt on
    // every refresh so newly added WebDAV servers / library folders show up
    LinearLayout filesRowView;
    View filesHeader;
    View filesDivider;

    @Override
    public Pair<Integer, Integer> getNameAndIconRes() {
        return PAIR;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_dashboard, container, false);

        setupHeader(view.findViewById(R.id.recentHeader), R.string.moon_home_recent, UITab.RecentFragment);
        setupHeader(view.findViewById(R.id.favHeader), R.string.moon_home_favorites, UITab.StarsFragment);
        setupHeader(view.findViewById(R.id.filesHeader), R.string.moon_home_files, UITab.BrowseFragment);
        setupHeader(view.findViewById(R.id.netHeader), R.string.moon_home_net, UITab.OpdsFragment);
        setupHeader(view.findViewById(R.id.bookmarkHeader), R.string.bookmarks_and_notes, UITab.BookmarksFragment);

        // Reading stats: no "more" link (no dedicated stats settings page yet).
        // PRO feature: the title carries the (Pro) suffix; while locked the
        // history stays viewable but ReadingStats stops recording new data.
        View statsHeader = view.findViewById(R.id.statsHeader);
        if (statsHeader != null) {
            ((TextView) statsHeader.findViewById(R.id.sectionTitle))
                    .setText(getString(R.string.moon_home_stats) + " (Pro)");
            statsHeader.findViewById(R.id.sectionMore).setVisibility(View.GONE);
        }
        bindStat(view, R.id.statTotal, R.string.moon_stat_total);
        bindStat(view, R.id.statRead,  R.string.moon_stat_read);

        // count cards deep-link into the library: "total books" shows every
        // book (clearing any leftover state filter), "read books" pre-selects
        // the read filter chip
        View statTotal = view.findViewById(R.id.statTotal);
        if (statTotal != null) {
            statTotal.setOnClickListener(v -> openLibraryWithFilter(""));
        }
        View statRead = view.findViewById(R.id.statRead);
        if (statRead != null) {
            statRead.setOnClickListener(v -> openLibraryWithFilter("read"));
        }

        // the total-time card opens the per-month reading-time bar chart
        View statHours = view.findViewById(R.id.statHours);
        if (statHours != null) {
            statHours.setOnClickListener(v -> showMonthlyReading());
        }

        bindStat(view, R.id.statHours, R.string.moon_stat_time_total);
        bindStat(view, R.id.statToday, R.string.moon_stat_today);
        bindStat(view, R.id.statSpeed, R.string.moon_stat_speed);

        recentAdapter = new CoverAdapter();
        favAdapter = new CoverAdapter();
        netAdapter = new SourceAdapter();
        bookmarkAdapter = new BookmarkAdapter();

        bindRow((RecyclerView) view.findViewById(R.id.recentRow), recentAdapter);
        bindRow((RecyclerView) view.findViewById(R.id.favRow), favAdapter);
        bindRow((RecyclerView) view.findViewById(R.id.netRow), netAdapter);
        bindRow((RecyclerView) view.findViewById(R.id.bookmarkRow), bookmarkAdapter);

        filesRowView = (LinearLayout) view.findViewById(R.id.filesRow);
        filesHeader = view.findViewById(R.id.filesHeader);
        filesDivider = view.findViewById(R.id.filesDivider);
        buildFileSources(filesRowView);

        populate();
        return view;
    }

    private void setupHeader(View header, int titleRes, UITab targetTab) {
        if (header == null) {
            return;
        }
        TextView title = (TextView) header.findViewById(R.id.sectionTitle);
        TextView more = (TextView) header.findViewById(R.id.sectionMore);
        title.setText(titleRes);
        more.setTextColor(TintUtil.color);
        Runnable go = new Runnable() {
            @Override
            public void run() {
                goToTab(targetTab);
            }
        };
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                go.run();
            }
        });
    }

    private void bindRow(RecyclerView row, RecyclerView.Adapter adapter) {
        row.setLayoutManager(new LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false));
        row.setAdapter(adapter);
    }

    // ------------------------------------------------------------------ stats

    /** "Xh Ym" above an hour, plain minutes below — minute precision. */
    private CharSequence formatMinutes(long ms) {
        long minutes = ms / 60000;
        if (minutes >= 60) {
            return getString(R.string.moon_stat_hm, minutes / 60, minutes % 60);
        }
        return getString(R.string.moon_stat_min, minutes);
    }

    private void bindStat(View view, int cardId, int labelRes) {
        View card = view.findViewById(cardId);
        if (card == null) {
            return;
        }
        TextView value = card.findViewById(R.id.statValue);
        TextView label = card.findViewById(R.id.statLabel);
        // framework themes do not define cardViewStyle, so the CardView would
        // keep its bright light default on the dark dashboard — theme it here
        boolean dark = AppState.get().appTheme == AppState.THEME_DARK || AppState.get().appTheme == AppState.THEME_DARK_OLED;
        if (card instanceof CardView) {
            ((CardView) card).setCardBackgroundColor(dark ? 0xFF232326 : 0xFFFFFFFF);
        }
        label.setTextColor(dark ? 0xFF9E9E9E : 0xFF757575);
        value.setTextColor(TintUtil.color);
        label.setText(labelRes);
        if (cardId == R.id.statTotal) {
            statTotalValue = value;
        } else if (cardId == R.id.statRead) {
            statReadValue = value;
        } else if (cardId == R.id.statHours) {
            statHoursValue = value;
        } else if (cardId == R.id.statToday) {
            statTodayValue = value;
        } else if (cardId == R.id.statSpeed) {
            statSpeedValue = value;
        }
    }

    // ------------------------------------------------------------------ files

    private static class FileSource {
        String label;
        int iconRes;
        int color;
        Runnable action;
    }

    private void buildFileSources(LinearLayout row) {
        row.removeAllViews();

        final Activity a = getActivity();
        if (a == null) {
            return;
        }

        // the sources actually configured in 我的文件: every added WebDAV
        // server opens its own network page …
        for (final WebDavServer srv : WebDavStore.load()) {
            addSource(row, srv.title, R.drawable.glyphicons_544_cloud, Color.parseColor("#80cbc4"), new Runnable() {
                @Override
                public void run() {
                    if (a instanceof MainTabs2) {
                        ((MainTabs2) a).openNetworkPage(true, srv.url, srv.title);
                    }
                }
            });
        }
        // … followed by the 书库文件夹 entries
        for (final String folder : JsonDB.get(BookCSS.get().searchPathsJson)) {
            if (TxtUtils.isEmpty(folder)) {
                continue;
            }
            File f = new File(folder);
            String name = TxtUtils.isEmpty(f.getName()) ? folder : f.getName();
            addSource(row, name, R.drawable.glyphicons_145_folder_open, Color.parseColor("#bdbdbd"), new Runnable() {
                @Override
                public void run() {
                    if (a instanceof MainTabs2) {
                        ((MainTabs2) a).openFolderPage(folder);
                    }
                }
            });
        }
        // only the real configured sources are shown (no catch-all entry);
        // with none of them the whole section stays hidden
        final boolean empty = row.getChildCount() == 0;
        final View scroll = (View) row.getParent();
        if (scroll != null) {
            scroll.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
        if (filesHeader != null) {
            filesHeader.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
        if (filesDivider != null) {
            filesDivider.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
    }

    private void addSource(LinearLayout row, String label, int iconRes, int color, final Runnable action) {
        View item = LayoutInflater.from(getContext()).inflate(R.layout.dashboard_source_item, row, false);
        View iconBg = item.findViewById(R.id.sourceIconBg);
        ImageView icon = (ImageView) item.findViewById(R.id.sourceIcon);
        TextView text = (TextView) item.findViewById(R.id.sourceLabel);
        icon.setImageResource(iconRes);
        // brand pngs are colored; only tint the monochrome glyphs
        if (iconRes == R.drawable.glyphicons_544_cloud || iconRes == R.drawable.glyphicons_145_folder_open) {
            icon.setColorFilter(Color.WHITE);
        } else {
            icon.clearColorFilter();
        }
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(color);
        iconBg.setBackground(bg);
        text.setText(label);
        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        row.addView(item);
    }

    // ------------------------------------------------------------------ nav

    private void goToTab(UITab tab) {
        Activity a = getActivity();
        if (a == null) {
            return;
        }
        // direct call handles both visible tabs and (via the temporary
        // overlay) pages disabled in the tab bar
        if (a instanceof MainTabs2) {
            ((MainTabs2) a).navigateToTab(tab);
        }
    }

    private void openLibraryWithFilter(String readState) {
        Activity a = getActivity();
        if (a instanceof MainTabs2) {
            ((MainTabs2) a).openLibraryWithReadState(readState);
        }
    }

    /**
     * "Total reading time" card: bar chart of the reading time booked per
     * day (last week / last month) or per month (last year). Data recorded
     * by ReadingStats into AppSP.readDailyJson/readMonthlyJson; time before
     * this feature shipped shows 0.
     */
    private void showMonthlyReading() {
        Activity a = getActivity();
        if (a == null) {
            return;
        }
        final MonthlyBarsView bars = new MonthlyBarsView(a);
        bars.setPadding(Dips.dpToPx(8), Dips.dpToPx(6), Dips.dpToPx(8), 0);

        // custom title: range switcher (week / month / year)
        LinearLayout switcher = new LinearLayout(a);
        switcher.setOrientation(LinearLayout.HORIZONTAL);
        switcher.setPadding(Dips.dpToPx(20), Dips.dpToPx(14), Dips.dpToPx(20), 0);
        final int[][] defs = {
                {R.string.moon_range_week, 7, 0},
                {R.string.moon_range_month, 30, 1},
                {R.string.moon_range_year, 12, 2},
        };
        final TextView[] tabs = new TextView[defs.length];
        final int[] current = {0}; // default: last week, per day
        for (int i = 0; i < defs.length; i++) {
            final int idx = i;
            TextView t = new TextView(a);
            t.setText(defs[i][0]);
            t.setTextSize(15);
            t.setPadding(Dips.dpToPx(14), 0, Dips.dpToPx(14), Dips.dpToPx(6));
            t.setOnClickListener(v -> {
                current[0] = defs[idx][2];
                fillReadingBars(bars, defs[idx][2]);
                for (int k = 0; k < tabs.length; k++) {
                    tabs[k].setTextColor(k == idx ? TintUtil.color : 0xFF757575);
                    tabs[k].setPaintFlags(tabs[k].getPaintFlags() & ~android.graphics.Paint.UNDERLINE_TEXT_FLAG);
                    if (k == idx) {
                        tabs[k].setPaintFlags(tabs[k].getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
                    }
                }
            });
            tabs[i] = t;
            switcher.addView(t);
        }
        tabs[0].setTextColor(TintUtil.color);
        tabs[0].setPaintFlags(tabs[0].getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        fillReadingBars(bars, 0);

        new AlertDialog.Builder(a)//
                .setCustomTitle(switcher)//
                .setView(bars)//
                .setPositiveButton(R.string.ok, null)//
                .show();
    }

    /** mode: 0 = last 7 days, 1 = last 30 days, 2 = last 12 months */
    private void fillReadingBars(MonthlyBarsView bars, int mode) {
        if (mode == 2) {
            JSONObject months = parseBuckets(AppSP.get().readMonthlyJson);
            long[] values = new long[12];
            String[] labels = new String[12];
            Calendar cal = Calendar.getInstance();
            int thisYear = cal.get(Calendar.YEAR);
            for (int i = 11; i >= 0; i--) {
                Calendar month = (Calendar) cal.clone();
                month.add(Calendar.MONTH, -i);
                String key = String.format(Locale.US, "%1$d-%2$02d",
                        month.get(Calendar.YEAR), month.get(Calendar.MONTH) + 1);
                values[11 - i] = months.optLong(key, 0);
                int m = month.get(Calendar.MONTH) + 1;
                labels[11 - i] = month.get(Calendar.YEAR) == thisYear
                        ? String.valueOf(m)
                        : String.format(Locale.US, "%1$d/%2$d", month.get(Calendar.YEAR) % 100, m);
            }
            bars.setData(values, labels, 1);
            return;
        }

        int days = mode == 0 ? 7 : 30;
        JSONObject daily = parseBuckets(AppSP.get().readDailyJson);
        long[] values = new long[days];
        String[] labels = new String[days];
        Calendar cal = Calendar.getInstance();
        for (int i = days - 1; i >= 0; i--) {
            Calendar day = (Calendar) cal.clone();
            day.add(Calendar.DAY_OF_MONTH, -i);
            String key = String.format(Locale.US, "%1$d-%2$02d-%3$02d",
                    day.get(Calendar.YEAR), day.get(Calendar.MONTH) + 1, day.get(Calendar.DAY_OF_MONTH));
            values[days - 1 - i] = daily.optLong(key, 0);
            labels[days - 1 - i] = String.format(Locale.US, "%1$d/%2$d",
                    day.get(Calendar.MONTH) + 1, day.get(Calendar.DAY_OF_MONTH));
        }
        bars.setData(values, labels, mode == 0 ? 1 : 5);
    }

    private static JSONObject parseBuckets(String json) {
        try {
            return new JSONObject(json == null ? "{}" : json);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    // ------------------------------------------------------------------ data

    @Override
    public List<FileMeta> prepareDataInBackground() {
        try {
            recentBooks = limit(AppData.get().getAllRecent(true), ROW_LIMIT);
        } catch (Exception e) {
            LOG.e(e);
        }
        try {
            favoriteBooks = limit(AppData.get().getAllFavoriteFiles(true), ROW_LIMIT);
        } catch (Exception e) {
            LOG.e(e);
        }
        try {
            bookmarks = limit(BookmarksData.get().getAll(), ROW_LIMIT);
        } catch (Exception e) {
            LOG.e(e);
        }
        opdsCatalogs = parseOpdsLinks();

        // Reading stats: library size, finished books, cumulative reading time.
        // prepareDataInBackground re-runs on every populate; reset the counters
        // first or the totals double on each refresh.
        totalBooks = 0;
        readBooks = 0;
        try {
            // The DB column IsRecentProgress is only written back for the 3
            // most-recent books, so counting "read" from the DB misses books
            // that just hit 100%. Count from the live progress store instead.
            // searchBy("") is the library's own base list (IsSearchBook=1,
            // no text filter), so the card numbers match what the user sees
            // after tapping into the library.
            BookStateStore.invalidate();
            for (FileMeta m : AppDB.get().searchBy("", AppDB.SORT_BY.DATA, false)) {
                if (!AppDB.get().isFolder(m) && new File(m.getPath()).exists()) {
                    totalBooks++;
                    if (BookStateStore.effective(m.getPath()) == BookStateStore.READ) {
                        readBooks++;
                    }
                }
            }
        } catch (Exception e) {
            LOG.e(e);
        }
        readTimeMs = AppSP.get().readTimeMs;
        readDayMs = AppSP.get().readDayMs;
        readPages = AppSP.get().readPages;

        return new ArrayList<FileMeta>();
    }

    private static <T> List<T> limit(List<T> list, int n) {
        if (list == null) {
            return new ArrayList<T>();
        }
        if (list.size() <= n) {
            return list;
        }
        return new ArrayList<T>(list.subList(0, n));
    }

    private List<Entry> parseOpdsLinks() {
        List<Entry> res = new ArrayList<Entry>();
        try {
            String[] list = AppState.get().allOPDSLinks.split(";");
            for (String line : list) {
                if (TxtUtils.isEmpty(line)) {
                    continue;
                }
                if (line.contains("star_1.png")) {
                    continue;
                }
                String[] it = line.split(",");
                try {
                    res.add(new Entry(it[0], it[1], it[2], it[3], true));
                } catch (Exception e) {
                    LOG.e(e, line);
                }
            }
        } catch (Exception e) {
            LOG.e(e);
        }
        return res;
    }

    @Override
    public void populateDataInUI(List<FileMeta> items) {
        if (recentAdapter != null) {
            recentAdapter.getItemsList().clear();
            recentAdapter.getItemsList().addAll(recentBooks);
            recentAdapter.notifyDataSetChanged();
        }
        if (favAdapter != null) {
            favAdapter.getItemsList().clear();
            favAdapter.getItemsList().addAll(favoriteBooks);
            favAdapter.notifyDataSetChanged();
        }
        if (netAdapter != null) {
            netAdapter.getItemsList().clear();
            netAdapter.getItemsList().addAll(opdsCatalogs);
            netAdapter.notifyDataSetChanged();
        }
        if (bookmarkAdapter != null) {
            bookmarkAdapter.getItemsList().clear();
            bookmarkAdapter.getItemsList().addAll(bookmarks);
            bookmarkAdapter.notifyDataSetChanged();
        }

        // the quick sources mirror the 我的文件 configuration; rebuild so
        // newly added WebDAV servers / library folders appear immediately
        if (filesRowView != null) {
            buildFileSources(filesRowView);
        }

        if (statTotalValue != null) {
            statTotalValue.setText(String.valueOf(totalBooks));
        }
        if (statReadValue != null) {
            statReadValue.setText(String.valueOf(readBooks));
        }
        if (statHoursValue != null) {
            statHoursValue.setText(formatMinutes(readTimeMs));
        }
        if (statTodayValue != null) {
            statTodayValue.setText(formatMinutes(readDayMs));
        }
        if (statSpeedValue != null) {
            long minutes = readTimeMs / 60000;
            float speed = minutes > 0 ? readPages / (float) minutes : 0f;
            statSpeedValue.setText(String.format(Locale.US, "%.1f", speed));
        }
    }

    // ---------------------------------------------------------------- adapters

    private class CoverAdapter extends AppRecycleAdapter<FileMeta, RecyclerView.ViewHolder> {

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.dashboard_cover_item, parent, false);
            return new RecyclerView.ViewHolder(v) {
            };
        }

        @Override
        public void onBindViewHolder(final RecyclerView.ViewHolder holder, int position) {
            final FileMeta meta = getItem(position);
            if (meta == null) {
                return;
            }
            ImageView cover = (ImageView) holder.itemView.findViewById(R.id.coverImage);
            TextView badge = (TextView) holder.itemView.findViewById(R.id.percentBadge);

            float progress = meta.getIsRecentProgress() == null ? 0 : meta.getIsRecentProgress();
            if (progress > 0f) {
                badge.setVisibility(View.VISIBLE);
                badge.setText(Math.round(100f * progress) + "%");
            } else {
                badge.setVisibility(View.GONE);
            }

            IMG.getCoverPageWithEffect(holder.itemView.getContext(), meta.getPath(), null).into(cover);

            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Activity a = getActivity();
                    if (a != null) {
                        ExtUtils.openFile(a, meta);
                    }
                }
            });
        }
    }

    private class SourceAdapter extends AppRecycleAdapter<Entry, RecyclerView.ViewHolder> {

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.dashboard_source_item, parent, false);
            return new RecyclerView.ViewHolder(v) {
            };
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            final Entry entry = getItem(position);
            if (entry == null) {
                return;
            }
            View iconBg = holder.itemView.findViewById(R.id.sourceIconBg);
            ImageView icon = (ImageView) holder.itemView.findViewById(R.id.sourceIcon);
            TextView label = (TextView) holder.itemView.findViewById(R.id.sourceLabel);

            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            bg.setColor(TintUtil.color);
            iconBg.setBackground(bg);

            if (TxtUtils.isNotEmpty(entry.tempLogo)) {
                Glide.with(holder.itemView.getContext()).load(entry.tempLogo).into(icon);
            } else {
                icon.setImageResource(R.drawable.glyphicons_417_globe);
                icon.setColorFilter(Color.WHITE);
            }
            label.setText(TxtUtils.isEmpty(entry.title) ? entry.homeUrl : entry.title);

            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Activity a = getActivity();
                    if (a instanceof MainTabs2) {
                        // open the tapped catalog directly, not just the tab
                        ((MainTabs2) a).openNetworkPage(false, entry.homeUrl, entry.title);
                    } else {
                        goToTab(UITab.OpdsFragment);
                    }
                }
            });
        }
    }

    private class BookmarkAdapter extends AppRecycleAdapter<AppBookmark, RecyclerView.ViewHolder> {

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.dashboard_bookmark_item, parent, false);
            return new RecyclerView.ViewHolder(v) {
            };
        }

        @Override
        public void onBindViewHolder(final RecyclerView.ViewHolder holder, int position) {
            final AppBookmark bm = getItem(position);
            if (bm == null) {
                return;
            }
            ImageView cover = (ImageView) holder.itemView.findViewById(R.id.bookmarkCover);
            TextView title = (TextView) holder.itemView.findViewById(R.id.bookmarkTitle);
            TextView text = (TextView) holder.itemView.findViewById(R.id.bookmarkText);
            TextView page = (TextView) holder.itemView.findViewById(R.id.bookmarkPage);

            String path = bm.getPath();
            title.setText(ExtUtils.getFileName(path));
            text.setText(bm.getText());
            page.setText(Math.round(100f * bm.p) + "%");

            IMG.getCoverPageWithEffect(holder.itemView.getContext(), path, null).into(cover);

            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Activity a = getActivity();
                    if (a == null || !new java.io.File(path).isFile()) {
                        return;
                    }
                    FileMeta meta = AppDB.get().getOrCreate(path);
                    ExtUtils.openFile(a, meta);
                }
            });
        }
    }

    // ----------------------------------------------------------------- UIFragment

    @Override
    public void notifyFragment() {
        populate();
    }

    @Override
    public void resetFragment() {
        populate();
    }

    @Override
    public boolean isBackPressed() {
        return false;
    }
}
