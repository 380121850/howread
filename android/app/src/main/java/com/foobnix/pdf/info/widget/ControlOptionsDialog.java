package com.foobnix.pdf.info.widget;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.foobnix.android.utils.Dips;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.TintUtil;
import com.foobnix.pdf.info.view.MyPopupMenu;

import java.util.ArrayList;
import java.util.List;

public class ControlOptionsDialog {

    public static void show(final Context c) {
        ScrollView scroll = new ScrollView(c);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Dips.dpToPx(14);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        final List<String> tapNames = new ArrayList<String>();
        tapNames.add(c.getString(R.string.next_page));
        tapNames.add(c.getString(R.string.previous_page));
        tapNames.add(c.getString(R.string.db_do_nothing));
        tapNames.add(c.getString(R.string.moon_tap_show_hide_ui));
        tapNames.add(c.getString(R.string.moon_tap_toc));
        tapNames.add(c.getString(R.string.moon_tap_bookmarks));
        tapNames.add(c.getString(R.string.moon_tap_night_mode));
        final List<Integer> tapIds = new ArrayList<Integer>();
        tapIds.add(AppState.TAP_NEXT_PAGE);
        tapIds.add(AppState.TAP_PREV_PAGE);
        tapIds.add(AppState.TAP_DO_NOTHING);
        tapIds.add(AppState.TAP_SHOW_HIDE_UI);
        tapIds.add(AppState.TAP_TOC);
        tapIds.add(AppState.TAP_BOOKMARKS);
        tapIds.add(AppState.TAP_NIGHT_MODE);

        final List<String> doubleNames = new ArrayList<String>();
        final List<Integer> doubleIds = new ArrayList<Integer>();
        int[] doubleConstants = {AppState.DOUBLE_CLICK_AUTOSCROLL, AppState.DOUBLE_CLICK_ADJUST_PAGE, AppState.DOUBLE_CLICK_CENTER_HORIZONTAL,
                AppState.DOUBLE_CLICK_ZOOM_IN_OUT, AppState.DOUBLE_CLICK_CLOSE_BOOK, AppState.DOUBLE_CLICK_CLOSE_BOOK_AND_APP,
                AppState.DOUBLE_CLICK_CLOSE_HIDE_APP, AppState.DOUBLE_CLICK_NOTHING, AppState.DOUBLE_CLICK_START_STOP_TTS,
                AppState.DOUBLE_CLICK_SHARE_PAGE, AppState.DOUBLE_CLICK_SHOW_HIDE_UI, AppState.DOUBLE_CLICK_CROP};
        int[] doubleStrings = {R.string.db_auto_scroll, R.string.db_auto_alignemnt, R.string.db_auto_center_horizontally, R.string.zoom_in_zoom_out,
                R.string.close_book, R.string.close_book_and_application, R.string.hide_app, R.string.db_do_nothing,
                R.string.read_out_loud_with_tts, R.string.share_as_image, R.string.moon_tap_show_hide_ui, R.string.crop_white_borders};
        for (int i = 0; i < doubleConstants.length; i++) {
            doubleIds.add(doubleConstants[i]);
            doubleNames.add(c.getString(doubleStrings[i]));
        }

        addZoneRow(c, root, R.string.left_side, tapNames, tapIds, AppState.get().tapZoneLeft, new ValueSetter() {
            @Override
            public void set(int value) {
                AppState.get().tapZoneLeft = value;
            }
        });
        addZoneRow(c, root, R.string.right_side, tapNames, tapIds, AppState.get().tapZoneRight, new ValueSetter() {
            @Override
            public void set(int value) {
                AppState.get().tapZoneRight = value;
            }
        });
        addZoneRow(c, root, R.string.top_side, tapNames, tapIds, AppState.get().tapZoneTop, new ValueSetter() {
            @Override
            public void set(int value) {
                AppState.get().tapZoneTop = value;
            }
        });
        addZoneRow(c, root, R.string.bottom_side, tapNames, tapIds, AppState.get().tapZoneBottom, new ValueSetter() {
            @Override
            public void set(int value) {
                AppState.get().tapZoneBottom = value;
            }
        });
        addZoneRow(c, root, R.string.action_on_double_tap, doubleNames, doubleIds, AppState.get().doubleClickAction1, new ValueSetter() {
            @Override
            public void set(int value) {
                AppState.get().doubleClickAction1 = value;
            }
        });

        final TextView volumeValue = addToggleRow(c, root, R.string.enable_volume_keys, AppState.get().isUseVolumeKeys, new ToggleSetter() {
            @Override
            public void set(boolean value) {
                AppState.get().isUseVolumeKeys = value;
            }
        });
        addToggleRow(c, root, R.string.reverse_keys, AppState.get().isReverseKeys, new ToggleSetter() {
            @Override
            public void set(boolean value) {
                AppState.get().isReverseKeys = value;
            }
        });

        final AlertDialog.Builder builder = new AlertDialog.Builder(c);
        builder.setTitle(R.string.moon_control_options);
        builder.setView(scroll);
        builder.setNegativeButton(R.string.close, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
            }
        });
        builder.show();
    }

    private interface ValueSetter {
        void set(int value);
    }

    private interface ToggleSetter {
        void set(boolean value);
    }

    private static void addZoneRow(final Context c, LinearLayout root, int labelRes, final List<String> names, final List<Integer> ids, int currentValue, final ValueSetter setter) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Dips.dpToPx(48));

        TextView label = new TextView(c);
        label.setText(labelRes);
        label.setTextSize(15);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView value = new TextView(c);
        int index = ids.indexOf(currentValue);
        if (index < 0) {
            index = 0;
        }
        value.setText(names.get(index));
        value.setTextColor(TintUtil.color);
        TxtUtils.underlineTextView(value);
        value.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MyPopupMenu popup = new MyPopupMenu(c, v);
                for (int i = 0; i < names.size(); i++) {
                    final int j = i;
                    popup.getMenu().add(names.get(i)).setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                        @Override
                        public boolean onMenuItemClick(MenuItem item) {
                            setter.set(ids.get(j));
                            value.setText(names.get(j));
                            TxtUtils.underlineTextView(value);
                            return false;
                        }
                    });
                }
                popup.show();
            }
        });
        row.addView(value);
        root.addView(row);
    }

    private static TextView addToggleRow(final Context c, LinearLayout root, int labelRes, boolean currentValue, final ToggleSetter setter) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Dips.dpToPx(48));

        TextView label = new TextView(c);
        label.setText(labelRes);
        label.setTextSize(15);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView value = new TextView(c);
        final String[] states = {c.getString(R.string.on), c.getString(R.string.off)};
        value.setText(currentValue ? states[0] : states[1]);
        value.setTextColor(TintUtil.color);
        TxtUtils.underlineTextView(value);
        value.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean next = !states[0].equals(value.getText().toString());
                setter.set(next);
                value.setText(next ? states[0] : states[1]);
            }
        });
        row.addView(value);
        root.addView(row);
        return value;
    }
}
