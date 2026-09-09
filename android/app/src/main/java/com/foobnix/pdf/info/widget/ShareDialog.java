package com.foobnix.pdf.info.widget;

import static com.foobnix.android.utils.TxtUtils.iconText;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnDismissListener;
import android.graphics.Color;
import android.view.View;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.util.Pair;

import com.foobnix.StringResponse;
import com.foobnix.ai.AiClient;
import com.foobnix.ai.AiConfigDialog;
import com.foobnix.ai.AiCredentials;
import com.foobnix.android.utils.BaseItemLayoutAdapter;
import com.foobnix.android.utils.IO;
import com.foobnix.android.utils.Keyboards;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.dao2.FileMeta;
import com.foobnix.mobi.parser.IOUtils;
import com.foobnix.model.AppBookmark;
import com.foobnix.model.AppData;
import com.foobnix.model.AppProfile;
import com.foobnix.model.AppSP;
import com.foobnix.model.AppState;
import com.foobnix.model.BookStateStore;
import com.foobnix.model.MyPath;
import com.foobnix.model.SimpleMeta;
import com.foobnix.model.TagData;
import com.foobnix.model.Tags2;
import com.foobnix.pdf.info.AppsConfig;
import com.foobnix.pdf.info.BookmarksData;
import com.foobnix.pdf.info.Clouds;
import com.foobnix.pdf.info.DialogSpeedRead;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.pdf.info.IMG;
import com.foobnix.pdf.info.Playlists;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.TintUtil;
import com.foobnix.pdf.info.Urls;
import com.foobnix.pdf.info.view.AlertDialogs;
import com.foobnix.pdf.info.view.Dialogs;
import com.foobnix.pdf.info.view.DialogsPlaylist;
import com.foobnix.pdf.info.wrapper.DocumentController;
import com.foobnix.pdf.info.wrapper.UITab;
import com.foobnix.pdf.search.activity.HorizontalViewActivity;
import com.foobnix.pdf.search.activity.msg.NotifyAllFragments;
import com.foobnix.pdf.search.activity.msg.UpdateAllFragments;
import com.foobnix.sys.TempHolder;
import com.foobnix.ui2.AppDB;
import com.foobnix.ui2.FileMetaCore;
import com.foobnix.ui2.MainTabs2;
import com.foobnix.ui2.adapter.TabsAdapter2;
import com.foobnix.ui2.fragment.SearchFragment2;
import com.foobnix.ui2.fragment.UIFragment;

import org.ebookdroid.BookType;
import org.ebookdroid.common.settings.books.SharedBooks;
import org.ebookdroid.ui.viewer.VerticalViewActivity;
import org.greenrobot.eventbus.EventBus;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ShareDialog {

    /** Menu-item suffix for PRO-gated features, e.g. "AI 简介(Pro)". */
    public static final String PRO_SUFFIX = "(Pro)";

    public static void showArchive(final Activity a, final File file, final Runnable onDeleteAction) {
        if (ExtUtils.isNotValidFile(file)) {
            Toast.makeText(a, R.string.file_not_found, Toast.LENGTH_LONG)
                 .show();
            return;
        }

        List<String> items = new ArrayList<String>();

        if (!ExtUtils.isImageOrEpub(file)) {
            items.add(a.getString(R.string.convert_to) + " EPUB");
            items.add(a.getString(R.string.convert_to) + " PDF");
        }
        final boolean canDelete = ExtUtils.isExteralSD(file.getPath()) ? true : file.canWrite();
        final boolean isShowInfo = !ExtUtils.isExteralSD(file.getPath());

        items.add(a.getString(R.string.open_with));
        items.add(a.getString(R.string.send_file));

        if (canDelete) {
            items.add(a.getString(R.string.delete));
        }

        if (isShowInfo) {
            items.add(a.getString(R.string.file_info));
        }

        final String chooseTitle = file != null ? file.getPath() : a.getString(R.string.choose_);

        final AlertDialog.Builder builder = new AlertDialog.Builder(a);
        builder.setTitle(R.string.choose_)//
               .setItems(items.toArray(new String[items.size()]), new DialogInterface.OnClickListener() {
                   @Override public void onClick(final DialogInterface dialog, final int which) {
                       int i = 0;
                       if (!ExtUtils.isImageOrEpub(file)) {
                           if (which == i++) {
                               showsItemsDialog(a, chooseTitle, AppState.CONVERTERS.get("EPUB"));
                           }
                           if (which == i++) {
                               showsItemsDialog(a, chooseTitle, AppState.CONVERTERS.get("PDF"));
                           }
                       }
                       if (which == i++) {
                           ExtUtils.openWith(a, file);
                       } else if (which == i++) {
                           ExtUtils.sendFileTo(a, file);
                       } else if (canDelete && which == i++) {
                           FileInformationDialog.dialogDelete(a, file, onDeleteAction);
                       } else if (isShowInfo && which == i++) {
                           FileInformationDialog.showFileInfoDialog(a, file, onDeleteAction);
                       }

                   }
               });
        builder.show();
    }

    ;

    public static void showsItemsDialog(final Activity a, String title, final String[] items) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(a);
        builder.setTitle(title)//
               .setItems(items, new DialogInterface.OnClickListener() {
                   @Override public void onClick(final DialogInterface dialog, final int which) {
                       Urls.open(a, items[which]);
                   }
               });

        AlertDialog dialog = builder.show();

    }

    public static void dirLongPress(final Activity a, final String to, final Runnable onRefresh) {
        List<String> items = new ArrayList<String>();

        items.add(a.getString(R.string.paste));
        items.add(a.getString(R.string.move));
        items.add(a.getString(R.string.cancel));

        final AlertDialog.Builder builder = new AlertDialog.Builder(a);
        builder.setItems(items.toArray(new String[items.size()]), new DialogInterface.OnClickListener() {
            @Override public void onClick(final DialogInterface dialog, final int which) {
                int i = 0;
                if (which == i++) {
                    try {
                        String from = TempHolder.get().copyFromPath;
                        File fromFile = new File(from);
                        File toFile = new File(to, fromFile.getName());

                        if (toFile.exists()) {
                            Toast.makeText(a, R.string.the_file_already_exists_, Toast.LENGTH_SHORT)
                                 .show();
                            return;
                        }

                        LOG.d("Copy from to", from, ">>", to);

                        InputStream input = new BufferedInputStream(new FileInputStream(from));
                        OutputStream output = new BufferedOutputStream(new FileOutputStream(toFile));

                        IOUtils.copyClose(input, output);

                        TempHolder.get().listHash++;

                        Toast.makeText(a, R.string.success, Toast.LENGTH_SHORT)
                             .show();
                        TempHolder.get().copyFromPath = null;
                        onRefresh.run();
                    } catch (Exception e) {
                        LOG.e(e);
                        Toast.makeText(a, R.string.msg_unexpected_error, Toast.LENGTH_SHORT)
                             .show();
                    }
                }
                if (which == i++) {
                    try {
                        String from = TempHolder.get().copyFromPath;
                        File fromFile = new File(from);
                        File toFile = new File(to, fromFile.getName());
                        LOG.d("Copy from to", from, ">>", to);

                        if (toFile.exists()) {
                            Toast.makeText(a, R.string.the_file_already_exists_, Toast.LENGTH_SHORT)
                                 .show();
                            return;
                        }

                        InputStream input = new BufferedInputStream(new FileInputStream(from));
                        OutputStream output = new BufferedOutputStream(new FileOutputStream(toFile));

                        IOUtils.copyClose(input, output);

                        fromFile.delete();
                        AppDB.get()
                             .delete(new FileMeta(fromFile.getPath()));
                        TempHolder.get().listHash++;

                        Toast.makeText(a, R.string.success, Toast.LENGTH_SHORT)
                             .show();
                        TempHolder.get().copyFromPath = null;
                        onRefresh.run();
                    } catch (Exception e) {
                        LOG.e(e);
                        Toast.makeText(a, R.string.msg_unexpected_error, Toast.LENGTH_SHORT)
                             .show();
                    }
                }
                if (which == i++) {
                    TempHolder.get().copyFromPath = null;
                    onRefresh.run();
                }
            }
        });
        AlertDialog create = builder.create();
        create.setOnDismissListener(new OnDismissListener() {

            @Override public void onDismiss(DialogInterface dialog) {
                Keyboards.hideNavigation(a);
            }
        });
        create.show();
    }

    public static void show(final Activity a, final File file, final Runnable onDeleteAction, final int page,
                            final DocumentController dc, final Runnable hideShow) {

        if (file == null) {
            Toast.makeText(a, R.string.file_not_found, Toast.LENGTH_LONG)
                 .show();
            return;
        }

        if (!ExtUtils.isExteralSD(file.getPath()) && ExtUtils.isNotValidFile(file)) {
            Toast.makeText(a, R.string.file_not_found, Toast.LENGTH_LONG)
                 .show();
            return;
        }
        final boolean isPDF = BookType.PDF.is(file.getPath());
        final boolean isLibrary = false;// a instanceof MainTabs2 ? false :
        // true;
        final boolean isMainTabs = a instanceof MainTabs2;

        List<String> items = new ArrayList<String>();

        final boolean isTxt = BookType.TXT.is(file.getPath());
        if (isTxt) {
            items.add(a.getString(R.string.edit));
        }

        if (isLibrary) {
            items.add(a.getString(R.string.library));
        }

        if (dc != null) {
            if (a instanceof VerticalViewActivity || dc.isMusicianMode()) {
                items.add("② "+AppState.get().nameHorizontalMode);
            }
            if (a instanceof HorizontalViewActivity || dc.isMusicianMode()) {
                items.add("① "+AppState.get().nameVerticalMode);
            }

            if (dc.isMusicianMode() == false) {
                items.add("Ⓜ "+AppState.get().nameMusicianMode);
            }
        }

        if (isPDF) {
           // items.add(a.getString(R.string.make_text_reflow));
            items.add(iconText(a, "⌘", R.string.make_text_reflow));
        }

        if (dc != null) {
            //items.add(a.getString(R.string.fast_reading));
            items.add(iconText(a, "▶▶", R.string.fast_reading));
        }

        //items.add(a.getString(R.string.open_with));
        //items.add(a.getString(R.string.send_file));

        items.add(iconText(a, "⎘", R.string.open_with));
        items.add(iconText(a, "➥", R.string.send_file));

        boolean canDelete1 =
                ExtUtils.isExteralSD(file.getPath()) || Clouds.isCloud(file.getPath()) ? true : file.canWrite();
        final boolean isShowInfo = !ExtUtils.isExteralSD(file.getPath());

        final boolean isRemovedFromLibrary = AppData.get()
                                                    .getAllExcluded()
                                                    .contains(new SimpleMeta(file.getPath()));

        if (file.getPath()
                .contains(AppProfile.PROFILE_PREFIX)) {
            canDelete1 = false;
        }
        final boolean canDelete = canDelete1;

        if (isMainTabs) {
            if (canDelete) {
                //items.add(a.getString(R.string.delete));
                items.add(iconText(a, "⊗", R.string.delete));
            }
            if (!isRemovedFromLibrary) {
                items.add(iconText(a,"-",R.string.remove_from_library));
            } else {
                items.add(iconText(a,"+",R.string.add_to_library));
            }
        }

        if (AppsConfig.isCloudsEnable) {
            items.add(a.getString(R.string.upload_to_cloud));
        }
        final boolean isPlaylist = file.getName()
                                       .endsWith(Playlists.L_PLAYLIST);
        if (!isPlaylist) {
            items.add(iconText(a, "\uD834\uDD1E", R.string.add_to_playlist));
        }

        if (isMainTabs) {
            // explicit reading-status setters (Moon+ style)
            items.add(iconText(a, "✓", R.string.moon_mark_read));
            items.add(iconText(a, "◌", R.string.moon_mark_unread));
            items.add(iconText(a, "◐", R.string.moon_mark_reading));
        }

        if (isShowInfo) {
            // PRO feature: the menu item text carries the (Pro) suffix
            // (iconText only takes a resource id, so build the line here)
            items.add("\u2728 " + a.getString(R.string.ai_intro_book) + PRO_SUFFIX);
            items.add(iconText(a, "ⓘ", R.string.file_info));
        }

        final AlertDialog.Builder builder = new AlertDialog.Builder(a);
        builder.setItems(items.toArray(new String[items.size()]), new DialogInterface.OnClickListener() {
            @Override public void onClick(final DialogInterface dialog, final int which) {
                int i = 0;

                if (isTxt && which == i++) {
                    AlertDialogs.editFileTxt(a, file, AppProfile.DOWNLOADS_DIR, new StringResponse() {
                        @Override public boolean onResultRecive(String string) {
                            if ((a instanceof HorizontalViewActivity || a instanceof VerticalViewActivity) && dc != null) {
                                dc.onCloseActivityFinal(new Runnable() {

                                    @Override public void run() {
                                        ExtUtils.openFile(a, new FileMeta(string));

                                    }
                                });
                            } else {
                                ExtUtils.openFile(a, new FileMeta(string));
                            }
                            return false;
                        }
                    });
                }
                if (isLibrary && which == i++) {
                    a.finish();
                    MainTabs2.startActivity(a, UITab.getCurrentTabIndex(UITab.SearchFragment));
                }

                if (dc != null && (a instanceof HorizontalViewActivity || dc.isMusicianMode()) && which == i++) {
                    dc.onCloseActivityFinal(new Runnable() {

                        @Override public void run() {
                            if (dc.isMusicianMode()) {
                                AppSP.get().readingMode = AppState.READING_MODE_BOOK;
                            } else {
                                AppSP.get().readingMode = AppState.READING_MODE_SCROLL;
                            }
                            ExtUtils.showDocumentWithoutDialog(a, file, a.getIntent()
                                                                         .getStringExtra(
                                                                                 DocumentController.EXTRA_PLAYLIST));

                        }
                    });

                }
                if (dc != null && (a instanceof VerticalViewActivity || dc.isMusicianMode()) && which == i++) {
                    if (dc != null) {
                        dc.onCloseActivityFinal(new Runnable() {

                            @Override public void run() {
                                if (dc.isMusicianMode()) {
                                    AppSP.get().readingMode = AppState.READING_MODE_SCROLL;
                                } else {
                                    AppSP.get().readingMode = AppState.READING_MODE_BOOK;
                                }
                                ExtUtils.showDocumentWithoutDialog(a, file, a.getIntent()
                                                                             .getStringExtra(
                                                                                     DocumentController.EXTRA_PLAYLIST));
                            }
                        });
                    }
                }
                if (dc != null && dc.isMusicianMode() == false && which == i++) {
                    dc.onCloseActivityFinal(new Runnable() {

                        @Override public void run() {
                            AppSP.get().readingMode = AppState.READING_MODE_MUSICIAN;
                            ExtUtils.showDocumentWithoutDialog(a, file, a.getIntent()
                                                                         .getStringExtra(
                                                                                 DocumentController.EXTRA_PLAYLIST));
                        }
                    });
                }
                if (isPDF && which == i++) {
                    ExtUtils.openPDFInTextReflow(a, file, page + 1, dc);
                }
                if (dc != null && which == i++) {
                    if (hideShow != null) {
                        AppState.get().isEditMode = false;
                        hideShow.run();
                    }
                    DialogSpeedRead.show(a, dc);
                } else if (which == i++) {
                    ExtUtils.openWith(a, file);
                } else if (which == i++) {
                    ExtUtils.sendFileTo(a, file);
                } else if (isMainTabs && canDelete && which == i++) {
                    FileInformationDialog.dialogDelete(a, file, onDeleteAction);
                } else if (isMainTabs && which == i++) {
                    if (isRemovedFromLibrary) {

                        FileMeta load = AppDB.get()
                                             .load(file.getPath());
                        if (load != null) {
                            load.setIsSearchBook(true);
                            AppDB.get()
                                 .update(load);
                            AppData.get()
                                   .removeExcluded(load);
                        }

                    } else {
                        FileMeta load = AppDB.get()
                                             .load(file.getPath());
                        if (load != null) {
                            load.setIsSearchBook(false);
                            load.setIsStar(false);

                            AppDB.get()
                                 .update(load);

                            AppData.get()
                                   .removeFavorite(load);
                            AppData.get()
                                   .addExclue(load.getPath());

                            Tags2.updateTagsDB();

                        }
                    }

                    EventBus.getDefault()
                            .post(new UpdateAllFragments());
                } else if (AppsConfig.isCloudsEnable && which == i++) {
                    showAddToCloudDialog(a, file);
                } else if (!isPlaylist && which == i++) {
                    DialogsPlaylist.showPlaylistsDialog(a, null, file);
                } else if (isMainTabs && which == i++) {
                    BookStateStore.markRead(file.getPath());
                    Toast.makeText(a, R.string.moon_state_applied, Toast.LENGTH_SHORT).show();
                    EventBus.getDefault()
                            .post(new UpdateAllFragments());
                } else if (isMainTabs && which == i++) {
                    BookStateStore.markUnread(file.getPath());
                    Toast.makeText(a, R.string.moon_state_applied, Toast.LENGTH_SHORT).show();
                    EventBus.getDefault()
                            .post(new UpdateAllFragments());
                } else if (isMainTabs && which == i++) {
                    BookStateStore.markReading(file.getPath());
                    Toast.makeText(a, R.string.moon_state_applied, Toast.LENGTH_SHORT).show();
                    EventBus.getDefault()
                            .post(new UpdateAllFragments());
                } else if (isShowInfo && which == i++) {
                    // order matters: this chain must match the items order
                    // (AI intro is added BEFORE 文件信息 in the list)
                    showAiIntro(a, file);
                } else if (isShowInfo && which == i++) {
                    FileInformationDialog.showFileInfoDialog(a, file, onDeleteAction);
                }

            }

        });
        AlertDialog create = builder.create();

        IMG.pauseRequests(a);
        create.setOnDismissListener(new OnDismissListener() {

            @Override public void onDismiss(DialogInterface dialog) {
                IMG.resumeRequests(a);
                Keyboards.hideNavigation(a);
            }

        });
        create.show();

    }

    /**
     * "AI book overview" from the book menu: sends the file name plus the
     * metadata title/author/notes to the configured AI model and shows the
     * reply with an "add to notes" action.
     * PRO feature: locked/fdroid builds get a toast instead.
     */
    public static void showAiIntro(final Activity a, final File file) {
        if (!com.foobnix.pdf.info.AppsConfig.isProFeaturesEnabled()) {
            com.foobnix.ui2.fragment.PrefFragment2.proLockedToast(a);
            return;
        }
        if (TxtUtils.isEmpty(AppState.get().aiBaseUrl) || TxtUtils.isEmpty(AppState.get().aiModel)
                || TxtUtils.isEmpty(AiCredentials.load(a))) {
            Toast.makeText(a, R.string.ai_ask_not_configured, Toast.LENGTH_LONG).show();
            return;
        }

        String title = "";
        String author = "";
        String notes = "";
        try {
            FileMeta meta = AppDB.get().load(file.getPath());
            if (meta != null) {
                title = TxtUtils.nullToEmpty(meta.getTitle()).trim();
                author = TxtUtils.nullToEmpty(meta.getAuthor()).trim();
                notes = TxtUtils.nullToEmpty(meta.getAnnotation()).trim();
            }
        } catch (Exception e) {
            LOG.e(e);
        }
        if (TxtUtils.isEmpty(notes)) {
            try {
                notes = TxtUtils.nullToEmpty(FileMetaCore.getBookOverview(file.getPath())).trim();
            } catch (Exception e) {
                LOG.e(e);
            }
        }
        if (TxtUtils.isEmpty(title)) {
            title = ExtUtils.getFileName(file.getPath());
        }

        final String metaLine = a.getString(R.string.ai_intro_meta_line, title, author, notes);
        final String prompt = a.getString(R.string.ai_intro_prompt, ExtUtils.getFileName(file.getPath()), metaLine);

        final TextView replyView = new TextView(a);
        replyView.setTextIsSelectable(true);
        replyView.setPadding(40, 40, 40, 40);
        replyView.setText(R.string.ai_ask_thinking);

        final ScrollView scroll = new ScrollView(a);
        scroll.addView(replyView, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

        final AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle(R.string.ai_intro_book)
                .setView(scroll)
                .setPositiveButton(R.string.save_as_note, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        AppBookmark note = new AppBookmark();
                        note.setPath(file.getPath());
                        note.text = ExtUtils.getFileName(file.getPath());
                        note.aiAnswer = replyView.getText().toString().trim();
                        note.isAiNote = true;
                        note.p = 0;
                        note.t = System.currentTimeMillis();
                        BookmarksData.get().add(note);
                        Toast.makeText(a, R.string.note_saved, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.close, null)
                .create();
        dialog.show();
        // enabled once the reply arrives
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);

        new android.os.AsyncTask<Void, Void, AiClient.TestResult>() {
            @Override protected AiClient.TestResult doInBackground(Void... params) {
                return AiClient.ask(a, prompt);
            }

            @Override protected void onPostExecute(AiClient.TestResult res) {
                if (res != null && res.ok && TxtUtils.isNotEmpty(res.reply)) {
                    replyView.setText(res.truncated
                            ? res.reply + "\n\n" + a.getString(R.string.ai_reply_truncated)
                            : res.reply);
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                } else if (res != null) {
                    replyView.setText(AiConfigDialog.resultErrorText(a, res.error, res.detail));
                }
            }
        }.execute();
    }

    public static void showAddToCloudDialog(final Activity a, final File file) {
        final AlertDialog.Builder inner = new AlertDialog.Builder(a);
        inner.setTitle(R.string.upload_to_cloud);

        List<Pair<Integer, Integer>> list = Arrays.asList(//
                new Pair<Integer, Integer>(R.string.dropbox, R.drawable.dropbox), //
                new Pair<Integer, Integer>(R.string.google_drive, R.drawable.gdrive), //
                new Pair<Integer, Integer>(R.string.one_drive, R.drawable.onedrive)//
                                                         );

        inner.setAdapter(new BaseItemLayoutAdapter<Pair<Integer, Integer>>(a, R.layout.item_dict_line, list) {
            @Override public void populateView(View layout, int position, Pair<Integer, Integer> item) {
                ((TextView) layout.findViewById(R.id.text1)).setText(item.first);
                ImageView imageView = (ImageView) layout.findViewById(R.id.image1);
                imageView.setImageResource(item.second);

                TintUtil.setNoTintImage(imageView);

                if (R.string.dropbox == item.first && !Clouds.get()
                                                             .isDropbox()) {
                    TintUtil.setTintImageNoAlpha(imageView, Color.LTGRAY);
                }

                if (R.string.google_drive == item.first && !Clouds.get()
                                                                  .isGoogleDrive()) {
                    TintUtil.setTintImageNoAlpha(imageView, Color.LTGRAY);

                }
                if (R.string.one_drive == item.first && !Clouds.get()
                                                               .isOneDrive()) {
                    TintUtil.setTintImageNoAlpha(imageView, Color.LTGRAY);

                }

            }
        }, new DialogInterface.OnClickListener() {

            @Override public void onClick(DialogInterface dialog, int which) {
                if (which == 0) {
                    if (Clouds.get()
                              .isDropbox()) {
                        Clouds.get()
                              .syncronizeAdd(a, file, Clouds.get().dropbox);
                    } else {
                        Clouds.get()
                              .loginToDropbox(a, new Runnable() {
                                  @Override public void run() {
                                      Clouds.get()
                                            .syncronizeAdd(a, file, Clouds.get().dropbox);
                                  }
                              });
                    }
                } else if (which == 1) {

                    if (Clouds.get()
                              .isGoogleDrive()) {
                        Clouds.get()
                              .syncronizeAdd(a, file, Clouds.get().googleDrive);
                    } else {
                        Clouds.get()
                              .loginToDropbox(a, new Runnable() {
                                  @Override public void run() {
                                      Clouds.get()
                                            .syncronizeAdd(a, file, Clouds.get().googleDrive);
                                  }
                              });
                    }

                } else if (which == 2) {
                    if (Clouds.get()
                              .isOneDrive()) {
                        Clouds.get()
                              .syncronizeAdd(a, file, Clouds.get().oneDrive);
                    } else {
                        Clouds.get()
                              .loginToDropbox(a, new Runnable() {

                                  @Override public void run() {
                                      Clouds.get()
                                            .syncronizeAdd(a, file, Clouds.get().oneDrive);
                                  }
                              });
                    }
                }

            }

        });
        inner.show();
    }

}
