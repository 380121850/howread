package com.foobnix.ui2.fragment;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MenuItem.OnMenuItemClickListener;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import com.foobnix.opds.Entry;
import com.foobnix.remote.AddRemoteDialog;
import com.foobnix.remote.RemoteBook;
import com.foobnix.remote.RemoteServer;
import com.foobnix.remote.RemoteStore;
import com.foobnix.webdav.WebDavCredentials;
import com.foobnix.webdav.WebDavServer;
import com.foobnix.webdav.WebDavStore;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.util.Pair;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.cloudrail.si.interfaces.CloudStorage;
import com.cloudrail.si.types.CloudMetaData;
import com.foobnix.StringResponse;
import com.foobnix.android.utils.Apps;
import com.foobnix.android.utils.Dips;
import com.foobnix.android.utils.JsonDB;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.ResultResponse;
import com.foobnix.android.utils.ResultResponse2;
import com.foobnix.android.utils.StringDB;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.android.utils.Views;
import com.foobnix.dao2.FileMeta;
import com.foobnix.model.AppData;
import com.foobnix.model.AppProfile;
import com.foobnix.model.AppState;
import com.foobnix.model.SimpleMeta;
import com.foobnix.pdf.info.Android6;
import com.foobnix.pdf.info.AppsConfig;
import com.foobnix.pdf.info.Clouds;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.pdf.info.FileMetaComparators;
import com.foobnix.pdf.info.IMG;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.TintUtil;
import com.foobnix.pdf.info.io.SearchCore;
import com.foobnix.pdf.info.model.BookCSS;
import com.foobnix.pdf.info.view.AlertDialogs;
import com.foobnix.pdf.info.view.Dialogs;
import com.foobnix.pdf.info.view.MultyDocSearchDialog;
import com.foobnix.pdf.info.view.MyPopupMenu;
import com.foobnix.pdf.info.view.MyProgressBar;
import com.foobnix.pdf.info.wrapper.UITab;
import com.foobnix.pdf.info.widget.ShareDialog;
import com.foobnix.pdf.info.widget.AddCatalogDialog;
import com.foobnix.pdf.search.activity.msg.UpdateAllFragments;
import com.foobnix.pdf.info.widget.ChooserDialogFragment;
import com.foobnix.webdav.AddWebDavDialog;
import com.foobnix.pdf.info.wrapper.PopupHelper;
import com.foobnix.pdf.search.view.AsyncProgressTask;
import com.foobnix.work.SearchAllBooksWorker;
import com.foobnix.sys.TempHolder;
import com.foobnix.ui2.AppDB;
import com.foobnix.ui2.FileMetaCore;
import com.foobnix.ui2.MainTabs2;
import com.foobnix.ui2.adapter.DefaultListeners;
import com.foobnix.ui2.adapter.FileMetaAdapter;
import com.foobnix.ui2.fast.FastScrollRecyclerView;

import org.ebookdroid.BookType;
import org.ebookdroid.droids.FolderContext;
import org.ebookdroid.droids.MdContext;
import org.greenrobot.eventbus.EventBus;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@TargetApi(Build.VERSION_CODES.LOLLIPOP) public class BrowseFragment2 extends UIFragment<FileMeta> {

    public static final Pair<Integer, Integer> PAIR =
    new Pair<Integer, Integer>(R.string.moon_my_files, R.drawable.glyphicons_145_folder_open);

    /**
     * Pseudo-path of the "My files" root view: lists the configured library
     * folders (and the standard quick dirs) as closed entries. The network
     * section (OPDS + WebDAV) is shown above the list only in this mode.
     */
    public static final String ROOT_PATH = "my-files:";
    LinearLayout netSection;
    /** 搜索 block of the root view, below the library-folder list */
    LinearLayout searchSection;
    View quickDirChipsRow;

    /**
     * Detached folder page opened from the "My files" root: this instance
     * keeps its own browse path so the tab's AppState.displayPath stays on
     * the root view (decoupled), and BACK at the entry folder closes the page.
     */
    private String browsePath;
    private String startFolder;

    /** New detached folder page (shown in the overlay by MainTabs2). */
    public static BrowseFragment2 newFolderInstance(String folderPath) {
        BrowseFragment2 fragment = new BrowseFragment2();
        Bundle args = new Bundle();
        args.putString("folderPath", folderPath);
        fragment.setArguments(args);
        return fragment;
    }

    /** The path this fragment browses (fragment-local on the detached page). */
    String path() {
        return browsePath != null ? browsePath : AppState.get().displayPath;
    }

    private void setPath(String p) {
        if (browsePath != null) {
            browsePath = p;
        } else {
            AppState.get().displayPath = p;
        }
    }

    /**
     * Directory a file/folder pick resolves against. On a detached chooser page
     * (browsePath != null) that is the page's own current directory; the shared
     * BookCSS.dirLastPath is intentionally NOT updated for detached pages (see
     * displayAnyPath), so it must not be used here or the result would be
     * "null/<name>". On the regular "My files" page keep the historical
     * dirLastPath behaviour.
     */
    private String chooserDir() {
        return browsePath != null ? browsePath : BookCSS.get().dirLastPath;
    }

    public static final String EXTRA_INIT_PATH = "EXTRA_PATH";
    public static final String EXTRA_TYPE = "EXTRA_TYPE";
    public static final String EXTRA_TEXT = "EXTRA_TEXT";
    public static int TYPE_DEFAULT = 0;
    public static int TYPE_SELECT_FOLDER = 1;
    public static int TYPE_SELECT_FILE = 2;
    public static int TYPE_SELECT_FILE_OR_FOLDER = 4;

    public static int TYPE_CREATE_FILE = 3;
    public CloudStorage cloudStorage;
    FileMetaAdapter searchAdapter;
    HorizontalScrollView scroller;
    Map<String, Integer> rememberPos = new HashMap<String, Integer>();

    boolean isRestorePos = false;
    int itemsCount;

    int readCount;
    private LinearLayout paths;
    private TextView stub;
    private ImageView onListGrid, starIcon, onSort, starIconDir, sortOrder, createFolder, pasteFrom;
    private EditText editPath;
    private View pathContainer, onClose, onAction, openAsBook;
    private int fragmentType = TYPE_DEFAULT;
    private String fragmentText = "";
    private ResultResponse<String> onPositiveAction;
    OnClickListener onSelectAction = new OnClickListener() {

        @Override public void onClick(View v) {
            final String typed = editPath.getText().toString().trim();
            if (fragmentType == TYPE_SELECT_FOLDER) {
                // a manually typed path wins over the browsed directory; both
                // may be unset on a fresh install (dirLastPath == null, which
                // used to NPE right here) — validate instead of crashing
                final String dir = TxtUtils.isNotEmpty(typed) ? typed : chooserDir();
                if (TxtUtils.isNotEmpty(dir) && new File(dir).isDirectory()
                        && new File(dir).canRead() && !ExtUtils.isExteralSD(dir)) {
                    onPositiveAction.onResultRecive(dir);
                } else {
                    Toast.makeText(getContext(), R.string.incorrect_value, Toast.LENGTH_SHORT)
                         .show();
                }
            } else if (fragmentType == TYPE_SELECT_FILE) {
                onPositiveAction.onResultRecive(joinChooserPath(typed));
            } else if (fragmentType == TYPE_SELECT_FILE_OR_FOLDER) {
                onPositiveAction.onResultRecive(typed);
            } else if (fragmentType == TYPE_CREATE_FILE) {
                onPositiveAction.onResultRecive(joinChooserPath(typed));
            }

        }
    };

    /** The chooser's directory joined with the typed name, null-safe on both parts. */
    private String joinChooserPath(String typed) {
        final String dir = chooserDir();
        if (TxtUtils.isEmpty(dir)) {
            return typed;
        }
        return dir + "/" + typed;
    }
    private ResultResponse<String> onCloseAction;
    OnClickListener onCloseButtonActoin = new OnClickListener() {

        @Override public void onClick(View v) {
            if (onCloseAction != null) {
                onCloseAction.onResultRecive("");
            }
        }
    };
    private ImageView openAsbookImage;

    public BrowseFragment2() {
        super();
    }

    public static BrowseFragment2 newInstance(Bundle bundle) {
        BrowseFragment2 br = new BrowseFragment2();
        br.setArguments(bundle);
        return br;
    }

    public static void sortItems(List<FileMeta> items) {
        if (AppState.get().sortByBrowse == AppState.BR_SORT_BY_PATH) {
            Collections.sort(items, FileMetaComparators.BY_PATH_NUMBER);
        } else if (AppState.get().sortByBrowse == AppState.BR_SORT_BY_DATE) {
            Collections.sort(items, FileMetaComparators.BY_DATE);
        } else if (AppState.get().sortByBrowse == AppState.BR_SORT_BY_SIZE) {
            Collections.sort(items, FileMetaComparators.BY_SIZE);
        } else if (AppState.get().sortByBrowse == AppState.BR_SORT_BY_NUMBER) {
            Collections.sort(items, FileMetaComparators.BR_BY_NUMBER1);
        } else if (AppState.get().sortByBrowse == AppState.BR_SORT_BY_PAGES) {
            Collections.sort(items, FileMetaComparators.BR_BY_PAGES);
        } else if (AppState.get().sortByBrowse == AppState.BR_SORT_BY_TITLE) {
            Collections.sort(items, FileMetaComparators.BR_BY_TITLE);
        } else if (AppState.get().sortByBrowse == AppState.BR_SORT_BY_EXT) {
            Collections.sort(items, FileMetaComparators.BR_BY_EXT);
        } else if (AppState.get().sortByBrowse == AppState.BR_SORT_BY_AUTHOR) {
            Collections.sort(items, FileMetaComparators.BR_BY_AUTHOR);
        }
        if (AppState.get().sortByReverse) {
            Collections.reverse(items);
        }
    }

    @Override public Pair<Integer, Integer> getNameAndIconRes() {
        return PAIR;
    }

    @Override public void onTintChanged() {
        TintUtil.setBackgroundFillColor(pathContainer, TintUtil.color);
        TintUtil.setBackgroundFillColor(onClose, TintUtil.color);
        TintUtil.setBackgroundFillColor(onAction, TintUtil.color);
        TintUtil.setTintImageWithAlpha(openAsbookImage,
                getActivity() instanceof MainTabs2 ? TintUtil.getColorInDayNighth() :
                        TintUtil.getColorInDayNighthBook());

    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP) @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_browse2, container, false);

        Bundle arguments = getArguments();

        LOG.d("displayPath-start", path());
        LOG.d("displayPath-start2", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));

        pathContainer = view.findViewById(R.id.pathContainer);
        View onCloseActionPaner = view.findViewById(R.id.onCloseActionPaner);
        onClose = view.findViewById(R.id.onClose);
        openAsBook = view.findViewById(R.id.openAsBook);
        openAsbookImage = (ImageView) view.findViewById(R.id.openAsbookImage);
        TintUtil.setTintImageWithAlpha(openAsbookImage,
                getActivity() instanceof MainTabs2 ? TintUtil.getColorInDayNighth() :
                        TintUtil.getColorInDayNighthBook());

        starIcon = (ImageView) view.findViewById(R.id.starIcon);
        starIconDir = (ImageView) view.findViewById(R.id.starIconDir);
        onSort = (ImageView) view.findViewById(R.id.onSort);
        sortOrder = (ImageView) view.findViewById(R.id.sortOrder);
        pasteFrom = (ImageView) view.findViewById(R.id.pasteFrom);

        View layoutOnGrant = view.findViewById(R.id.layoutOnGrant);
        Views.visible(layoutOnGrant, !Android6.canWrite(getContext()));
        layoutOnGrant.setOnClickListener(v -> Android6.checkPermissions(getActivity(),false));

        sortOrder.setOnClickListener(new OnClickListener() {

            @Override public void onClick(View v) {
                AppState.get().sortByReverse = !AppState.get().sortByReverse;
                onSort.setImageResource(AppState.get().sortByReverse ? R.drawable.glyphicons_477_sort_attributes_alt :
                        R.drawable.glyphicons_476_sort_attributes);
                sortOrder.setImageResource(AppState.get().sortByReverse ? R.drawable.glyphicons_222_chevron_up :
                        R.drawable.glyphicons_221_chevron_down);

                populate();

            }
        });

        sortOrder.setOnLongClickListener(new OnLongClickListener() {

            @Override public boolean onLongClick(View v) {
                AppState.get().isVisibleSorting = !AppState.get().isVisibleSorting;
                sortOrder.setVisibility(TxtUtils.visibleIf(AppState.get().isVisibleSorting));
                return true;
            }
        });

        onSort.setOnLongClickListener(new OnLongClickListener() {

            @Override public boolean onLongClick(View v) {
                AppState.get().isVisibleSorting = !AppState.get().isVisibleSorting;
                sortOrder.setVisibility(TxtUtils.visibleIf(AppState.get().isVisibleSorting));
                return true;
            }
        });
        sortOrder.setVisibility(TxtUtils.visibleIf(AppState.get().isVisibleSorting));

        openAsBook.setOnClickListener(new OnClickListener() {

            @Override public void onClick(View v) {
                File file = new File(path(), MdContext.SUMMARY_MD);
                if (file.isFile()) {
                    ExtUtils.openFile(getActivity(), new FileMeta(file.getPath()));
                } else {
                    File lxml =
                            FolderContext.genarateXML(searchAdapter.getItemsList(), path(), true);
                    ExtUtils.showDocumentWithoutDialog2(getActivity(), lxml);
                }
            }
        });
        openAsBook.setVisibility(View.GONE);

        onSort.setImageResource(AppState.get().sortByReverse ? R.drawable.glyphicons_477_sort_attributes_alt :
                R.drawable.glyphicons_476_sort_attributes);
        sortOrder.setImageResource(AppState.get().sortByReverse ? R.drawable.glyphicons_222_chevron_up :
                R.drawable.glyphicons_221_chevron_down);

        sortOrder.setContentDescription(getString(R.string.ascending) + " " + getString(R.string.descending));
        onSort.setContentDescription(getString(R.string.cd_sort_results));

        onAction = view.findViewById(R.id.onAction);
        editPath = (EditText) view.findViewById(R.id.editPath);

        fragmentType = TYPE_DEFAULT;
        if (arguments != null) {
            fragmentType = arguments.getInt(EXTRA_TYPE, TYPE_DEFAULT);
            fragmentText = arguments.getString(EXTRA_TEXT);
            editPath.setText(fragmentText);
        }

        onClose.setOnClickListener(onCloseButtonActoin);
        onAction.setOnClickListener(onSelectAction);

        createFolder = view.findViewById(R.id.createFolder);

        if (TYPE_DEFAULT == fragmentType) {
            editPath.setVisibility(View.GONE);
            onCloseActionPaner.setVisibility(View.GONE);
        }
        if (TYPE_SELECT_FOLDER == fragmentType) {
            editPath.setVisibility(View.VISIBLE);
            editPath.setEnabled(false);
            onCloseActionPaner.setVisibility(View.VISIBLE);

        }
        if (TYPE_SELECT_FILE == fragmentType) {
            editPath.setVisibility(View.VISIBLE);
            editPath.setEnabled(false);
            onCloseActionPaner.setVisibility(View.VISIBLE);
        }
        if (TYPE_CREATE_FILE == fragmentType) {
            editPath.setVisibility(View.VISIBLE);
            editPath.setEnabled(true);
            onCloseActionPaner.setVisibility(View.VISIBLE);
        }
        if (TYPE_SELECT_FILE_OR_FOLDER == fragmentType) {
            editPath.setVisibility(View.VISIBLE);
            editPath.setEnabled(false);
            onCloseActionPaner.setVisibility(View.VISIBLE);
        }

        View onBack = view.findViewById(R.id.onBack);
        recyclerView = (FastScrollRecyclerView) view.findViewById(R.id.recyclerView);

        paths = (LinearLayout) view.findViewById(R.id.paths);
        scroller = (HorizontalScrollView) view.findViewById(R.id.scroller);
        final View onHome = view.findViewById(R.id.onHome);
        onListGrid = (ImageView) view.findViewById(R.id.onListGrid);
        onListGrid.setOnClickListener(new OnClickListener() {

            @Override public void onClick(View v) {
                popupMenu(onListGrid);
            }
        });

        searchAdapter = new FileMetaAdapter();
        bindAdapter(searchAdapter);
        bindAuthorsSeriesAdapter(searchAdapter);

        onGridList();

        onHome.setOnClickListener(new OnClickListener() {

            @Override public void onClick(View v) {
                displayAnyPath(Environment.getExternalStorageDirectory().getPath());
            }
        });
        if (TYPE_DEFAULT == fragmentType || TYPE_SELECT_FOLDER == fragmentType) {
            buildQuickDirChips(view);
        }
        onHome.setOnLongClickListener(new OnLongClickListener() {

            @Override public boolean onLongClick(View v) {
                List<String> extFolders = ExtUtils.getAllExternalStorages(getActivity());

                MyPopupMenu menu = new MyPopupMenu(getActivity(), onHome);

                menu.getMenu()
                    .add(R.string.memory)
                    .setOnMenuItemClickListener(new OnMenuItemClickListener() {

                        @Override public boolean onMenuItemClick(MenuItem item) {
                            displayAnyPath(Environment.getExternalStorageDirectory()
                                                      .getPath());
                            return false;
                        }
                    })
                    .setIcon(R.drawable.glyphicons_336_folder);

                String pathDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                                                  .getPath();
                if (new File(pathDownloads).isDirectory()) {
                    menu.getMenu()
                        .add(getString(R.string.memory) + "/" + ExtUtils.getFileName(pathDownloads))
                        .setOnMenuItemClickListener(new OnMenuItemClickListener() {

                            @Override public boolean onMenuItemClick(MenuItem item) {

                                displayAnyPath(pathDownloads);
                                return false;
                            }
                        })
                        .setIcon(R.drawable.glyphicons_336_folder);
                }

                for (final String info : extFolders) {

                    String name;

                    if (ExtUtils.isExteralSD(info)) {
                        name = ExtUtils.getExtSDDisplayName(getContext(), info);
                    } else {
                        name = new File(info).getName();
                    }

                    menu.getMenu()
                        .add(name)
                        .setOnMenuItemClickListener(new OnMenuItemClickListener() {

                            @Override public boolean onMenuItemClick(MenuItem item) {
                                displayAnyPath(info);
                                return false;
                            }
                        })
                        .setIcon(R.drawable.glyphicons_336_folder);

                }

                if (new File(BookCSS.get().downlodsPath).isDirectory()) {
                    menu.getMenu()
                        .add("HowRead/" + getString(R.string.downloads))
                        .setOnMenuItemClickListener(new OnMenuItemClickListener() {

                            @Override public boolean onMenuItemClick(MenuItem item) {
                                displayAnyPath(BookCSS.get().downlodsPath);
                                return false;
                            }
                        })
                        .setIcon(R.drawable.glyphicons_336_folder);
                }

//                menu.getMenu().add(R.string.sync).setOnMenuItemClickListener(new OnMenuItemClickListener() {
//
//                    @Override
//                    public boolean onMenuItemClick(MenuItem item) {
//                        displayAnyPath(AppProfile.SYNC_FOLDER_ROOT.getPath());
//                        return false;
//                    }
//                }).setIcon(R.drawable.glyphicons_sync);

                // resources

                if (Build.VERSION.SDK_INT >= 21 && getActivity() instanceof MainTabs2) {
                    List<String> safs = StringDB.asList(BookCSS.get().pathSAF);

                    for (final String saf : safs) {
                        LOG.d("saf", saf);
                        if (TxtUtils.isEmpty(saf)) {
                            continue;
                        }
                        String fileName = DocumentsContract.getTreeDocumentId(Uri.parse(saf));
                        menu.getMenu()
                            .add(fileName)
                            .setOnMenuItemClickListener(new OnMenuItemClickListener() {

                                @Override public boolean onMenuItemClick(MenuItem item) {
                                    displayAnyPath(saf);
                                    return false;
                                }
                            })
                            .setOnMenuItemLongClickListener(new OnMenuItemClickListener() {

                                @Override public boolean onMenuItemClick(MenuItem item) {
                                    StringDB.delete(BookCSS.get().pathSAF, saf,
                                            (String db) -> BookCSS.get().pathSAF = db);
                                    return false;
                                }
                            })
                            .setIcon(R.drawable.glyphicons_336_folder);

                    }

                }

                // stars
                List<FileMeta> starFolders = AppData.get()
                                                    .getAllFavoriteFolders();
                List<String> names = new ArrayList<String>();
                for (FileMeta f : starFolders) {
                    names.add(f.getPath());
                }

                Collections.sort(names, String.CASE_INSENSITIVE_ORDER);

                for (final String info : names) {

                    String name;

                    if (ExtUtils.isExteralSD(info)) {
                        name = ExtUtils.getExtSDDisplayName(getContext(), info);
                    } else {
                        name = new File(info).getName();
                    }

                    menu.getMenu()
                        .add(name)
                        .setOnMenuItemClickListener(new OnMenuItemClickListener() {

                            @Override public boolean onMenuItemClick(MenuItem item) {
                                displayAnyPath(info);
                                return false;
                            }
                        })
                        .setIcon(R.drawable.glyphicons_150_folder_star);

                }

                if (Build.VERSION.SDK_INT >= 21 && getActivity() instanceof MainTabs2) {
                    menu.getMenu()
                        .add(R.string.add_resource)
                        .setOnMenuItemClickListener(new OnMenuItemClickListener() {

                            @Override public boolean onMenuItemClick(MenuItem item) {
                                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);

                                intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION//
                                                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION//
                                                | Intent.FLAG_GRANT_READ_URI_PERMISSION//
                                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION//
                                               );

                                ActivityCompat.startActivityForResult(getActivity(), intent,
                                        MainTabs2.REQUEST_CODE_ADD_RESOURCE, new Bundle());

                                return true;
                            }
                        })
                        .setIcon(R.drawable.glyphicons_145_folder_open);

                }

                if (AppsConfig.isCloudsEnable) {

                    menu.getMenu()
                        .add(R.string.dropbox)
                        .active(Clouds.get()
                                      .isDropbox())
                        .setOnMenuItemClickListener(new OnMenuItemClickListener() {

                            @Override public boolean onMenuItemClick(MenuItem item) {

                                Clouds.get()
                                      .loginToDropbox(getActivity(), new Runnable() {

                                          @Override public void run() {
                                              displayAnyPath(Clouds.PREFIX_CLOUD_DROPBOX + "/");
                                          }
                                      });

                                return true;
                            }
                        })
                        .setIcon(R.drawable.dropbox);

                    menu.getMenu()
                        .add(R.string.google_drive)
                        .active(Clouds.get()
                                      .isGoogleDrive())
                        .setOnMenuItemClickListener(new OnMenuItemClickListener() {

                            @Override public boolean onMenuItemClick(MenuItem item) {
                                Clouds.get()
                                      .loginToGoogleDrive(getActivity(), new Runnable() {

                                          @Override public void run() {
                                              displayAnyPath(Clouds.PREFIX_CLOUD_GDRIVE + "/");
                                          }
                                      });

                                return true;
                            }
                        })
                        .setIcon(R.drawable.gdrive);

                    menu.getMenu()
                        .add(R.string.one_drive)
                        .active(Clouds.get()
                                      .isOneDrive())
                        .setOnMenuItemClickListener(new OnMenuItemClickListener() {

                            @Override public boolean onMenuItemClick(MenuItem item) {
                                Clouds.get()
                                      .loginToOneDrive(getActivity(), new Runnable() {

                                          @Override public void run() {
                                              displayAnyPath(Clouds.PREFIX_CLOUD_ONEDRIVE + "/");
                                          }
                                      });

                                return true;
                            }
                        })
                        .setIcon(R.drawable.onedrive);
                }

                menu.show();
                return true;
            }
        });
        onBack.setOnClickListener(new OnClickListener() {

            @Override public void onClick(View v) {
                onBackAction();
            }
        });

        searchAdapter.setOnItemClickListener(new ResultResponse<FileMeta>() {

            @Override public boolean onResultRecive(FileMeta result) {
                if (recyclerView.getLayoutManager() instanceof LinearLayoutManager) {
                    int pos = ((LinearLayoutManager) recyclerView.getLayoutManager()).findFirstVisibleItemPosition();
                    rememberPos.put(path(), pos);
                    LOG.d("rememberPos LinearLayoutManager", path(), pos);
                } else if (recyclerView.getLayoutManager() instanceof StaggeredGridLayoutManager) {
                    int pos =
                            ((StaggeredGridLayoutManager) recyclerView.getLayoutManager()).findFirstVisibleItemPositions(
                                    null)[0];
                    rememberPos.put(path(), pos);
                    LOG.d("rememberPos StaggeredGridLayoutManager", path(), pos);
                }

                if (result.getCusType() != null && result.getCusType() == FileMetaAdapter.DISPLAY_TYPE_DIRECTORY) {
                    if (browsePath == null && fragmentType == TYPE_DEFAULT
                            && ROOT_PATH.equals(path())) {
                        // library folder on the root view: open a detached
                        // folder page, the tab keeps its root view
                        ((MainTabs2) getActivity()).openFolderPage(result.getPath());
                    } else {
                        displayAnyPath(result.getPath());
                    }
                    if (fragmentType == TYPE_SELECT_FOLDER) {
                        editPath.setText(fragmentText);
                    }
                } else {
                    if (fragmentType == TYPE_DEFAULT) {
                        DefaultListeners.getOnItemClickListener(getActivity())
                                        .onResultRecive(result);
                    } else if (fragmentType == TYPE_SELECT_FILE) {
                        editPath.setText(ExtUtils.getFileName(result.getPath()));
                    } else if (fragmentType == TYPE_SELECT_FILE_OR_FOLDER) {
                        editPath.setText(result.getPath());
                    }

                }
                return false;
            }
        });

        searchAdapter.setOnItemLongClickListener(new ResultResponse<FileMeta>() {
            @Override public boolean onResultRecive(FileMeta result) {
                if (result.getCusType() != null && result.getCusType() == FileMetaAdapter.DISPLAY_TYPE_DIRECTORY) {
                    // displayAnyPath(result.getPath());
                    if (ROOT_PATH.equals(path())) {
                        // the root list shows the configured library folders:
                        // long press removes the entry from the list only, the
                        // folder on disk is never touched
                        AlertDialogs.showDialog(getActivity(),
                                getString(R.string.moon_remove_folder_hint) + "\n[" + result.getPath() + "]",
                                getString(R.string.delete), new Runnable() {
                                    @Override public void run() {
                                        BookCSS.get().searchPathsJson =
                                                JsonDB.remove(BookCSS.get().searchPathsJson, result.getPath());
                                        // remember the removal: if this was an
                                        // empty-list fallback default (storage
                                        // root / Downloads) the fallbacks below
                                        // and on next start would re-add it and
                                        // the delete would look like a no-op
                                        BookCSS.get().searchPathsHiddenJson =
                                                JsonDB.add(BookCSS.get().searchPathsHiddenJson, result.getPath());
                                        AppProfile.save(getActivity());
                                        populate();
                                    }
                                });
                        return false;
                    }
                    if (TxtUtils.isNotEmpty(TempHolder.get().copyFromPath)) {
                        ShareDialog.dirLongPress(getActivity(), result.getPath(), new Runnable() {

                            @Override public void run() {
                                resetFragment();
                            }
                        });
                    } else {
                        deleteFolderPopup(getActivity(), result.getPath());
                    }
                } else {
                    DefaultListeners.onLongClickChooser(getActivity(),searchAdapter).onResultRecive(result);

                }
                return false;
            }
        });

        onSort.setOnClickListener(new OnClickListener() {

            @Override public void onClick(View v) {

                List<String> names = Arrays.asList(//
                        getActivity().getString(R.string.by_file_name), //
                        getActivity().getString(R.string.by_date), //
                        getActivity().getString(R.string.by_size), //
                        getActivity().getString(R.string.by_title), //
                        getActivity().getString(R.string.by_author), //
                        getActivity().getString(R.string.by_number_in_serie), //
                        getActivity().getString(R.string.by_number_of_pages), //
                        getActivity().getString(R.string.by_extension) //
                                                  );//

                final List<Integer> ids = Arrays.asList(//
                        AppState.BR_SORT_BY_PATH, //
                        AppState.BR_SORT_BY_DATE, //
                        AppState.BR_SORT_BY_SIZE, //
                        AppState.BR_SORT_BY_TITLE, //
                        AppState.BR_SORT_BY_AUTHOR, //
                        AppState.BR_SORT_BY_NUMBER, //
                        AppState.BR_SORT_BY_PAGES, //
                        AppState.BR_SORT_BY_EXT//
                                                       );//

                MyPopupMenu menu = new MyPopupMenu(getActivity(), v);
                for (int i = 0; i < names.size(); i++) {
                    String name = names.get(i);
                    final int j = i;
                    menu.getMenu()
                        .add(name)
                        .setOnMenuItemClickListener(new OnMenuItemClickListener() {

                            @Override public boolean onMenuItemClick(MenuItem item) {
                                AppState.get().sortByBrowse = ids.get(j);
                                populate();
                                return false;
                            }
                        });
                }
                menu.show();
            }
        });

        netSection = view.findViewById(R.id.netSection);
        searchSection = (LinearLayout) view.findViewById(R.id.searchSection);
        quickDirChipsRow = (View) view.findViewById(R.id.quickDirChips).getParent();
        buildNetSections();

        String folderArg = getArguments() != null ? getArguments().getString("folderPath") : null;
        if (TxtUtils.isNotEmpty(folderArg)) {
            // detached folder page: browse this path, the tab keeps its root
            browsePath = folderArg;
            startFolder = folderArg;
            displayAnyPath(folderArg);
        } else {
            displayAnyPath(getInitPath());
        }
        onTintChanged();

        MyProgressBar = (MyProgressBar) view.findViewById(R.id.MyProgressBarBrowse);
        MyProgressBar.setVisibility(View.GONE);
        TintUtil.setDrawableTint(MyProgressBar.getIndeterminateDrawable()
                                              .getCurrent(), Color.WHITE);

        final View bankSpace = view.findViewById(R.id.bankSpace);

        bankSpace.setOnClickListener(new OnClickListener() {

            @Override public void onClick(View v) {
                if (TxtUtils.isNotEmpty(TempHolder.get().copyFromPath)) {
                    ShareDialog.dirLongPress(getActivity(), path(), new Runnable() {

                        @Override public void run() {
                            resetFragment();
                        }
                    });
                }
            }
        });

        if (AppState.get().appTheme == AppState.THEME_DARK_OLED) {
            view.findViewById(R.id.openAsBookBg)
                .setBackgroundColor(Color.BLACK);

            if (fragmentType == TYPE_DEFAULT) {
                searchAdapter.tempValue2 = FileMetaAdapter.TEMP2_NONE;
            } else {
                searchAdapter.tempValue2 = FileMetaAdapter.TEMP2_RECENT_FROM_BOOK;
            }
        }

        createFolder.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {

                MyPopupMenu menu = new MyPopupMenu(getActivity(), v);

                boolean isStarFolder = AppDB.get()
                                            .isStarFolderByFiles(path());
                if (!isStarFolder) {
                    menu.getMenu()
                        .add("★ "+getString(R.string.add_to_favorites))
                        .setOnMenuItemClickListener(item -> {
                            AppData.get()
                                   .addFavorite(new SimpleMeta(path(), System.currentTimeMillis()));
                            TempHolder.listHash++;

                            return false;
                        });
                } else {
                    menu.getMenu()
                        .add("☆ "+getString(R.string.remove_from_favorites))
                        //.add(R.string.remove_from_favorites)
                        .setOnMenuItemClickListener(item -> {
                            AppData.get().removeFavorite(path());
                                   TempHolder.listHash++;

                            return false;
                        });
                }

                menu.getMenu()
                    .add(R.string.new_file_txt)
                    .setOnMenuItemClickListener(item -> {
                        AlertDialogs.editFileTxt(getActivity(), null, new File(path()),
                                new StringResponse() {
                                    @Override public boolean onResultRecive(String string) {
                                        //ExtUtils.openFile(getActivity(), new FileMeta(string));
                                        resetFragment();
                                        return false;
                                    }
                                });
                        return false;
                    });
                menu.getMenu()
                    .add(R.string.create_folder)
                    .setOnMenuItemClickListener(item -> {
                        AlertDialogs.showEditDialog(getActivity(), getString(R.string.create_folder),
                                getString(R.string.name), new StringResponse() {

                                    @Override public boolean onResultRecive(String string) {
                                        AppState.get().isDisplayAllFilesInFolder = true;
                                        File folder = new File(path(), string);
                                        LOG.d("create_folder", folder);
                                        if (!folder.mkdirs()) {
                                            Toast.makeText(getContext(), R.string.fail, Toast.LENGTH_SHORT)
                                                 .show();

                                            return false;
                                        }
                                        Toast.makeText(getContext(), R.string.success, Toast.LENGTH_SHORT)
                                             .show();
                                        populate();
                                        return true;
                                    }
                                });
                        return false;
                    });

                menu.getMenu()
                    .add(R.string.go_to_the_folder)
                    .setOnMenuItemClickListener(new OnMenuItemClickListener() {
                        @Override public boolean onMenuItemClick(MenuItem item) {
                            String displayPath1 = path();

                            Dialogs.showEditDialog2(getActivity(), getString(R.string.go_to_the_folder), displayPath1,
                                    new ResultResponse<String>() {
                                        @Override public boolean onResultRecive(String path1) {
                                            displayAnyPath(path1);
                                            return false;
                                        }
                                    });
                            return false;
                        }
                    });
                menu.show();

            }
        });

        return view;
    }

    @Override public void onReviceOpenDir(String path) {
        displayAnyPath(path);
    }

    public String getInitPath() {
        try {
            String pathArgument = getArguments() != null ? getArguments().getString(EXTRA_INIT_PATH, null) : "";
            if (TxtUtils.isNotEmpty(pathArgument)) {
                return pathArgument;
            }
        } catch (Exception e) {
            LOG.e(e);
        }
        // The "My files" tab (no explicit init path) opens on the root view:
        // the configured library folders listed closed, not expanded into the
        // last browsed directory.
        if (TYPE_DEFAULT == fragmentType) {
            return ROOT_PATH;
        }
        for (String scanPath : JsonDB.get(BookCSS.get().searchPathsJson)) {
            if (TxtUtils.isNotEmpty(scanPath) && new File(scanPath).isDirectory()) {
                return scanPath;
            }
        }
        String path =
                BookCSS.get().dirLastPath == null ? AppProfile.DOWNLOADS_DIR.getPath() : BookCSS.get().dirLastPath;
        if (ExtUtils.isExteralSD(path)) {
            return path;
        }
        if (new File(path).isDirectory()) {
            return path;
        }
        return AppProfile.DOWNLOADS_DIR.getPath();
    }

    @Override public List<FileMeta> prepareDataInBackground() {

        try {

            if (ROOT_PATH.equals(path())) {
                // "My files" root: the configured library folders, listed
                // closed (not expanded); standard quick dirs when none.
                List<FileMeta> roots = new ArrayList<FileMeta>();
                for (String path : JsonDB.get(BookCSS.get().searchPathsJson)) {
                    if (TxtUtils.isNotEmpty(path) && new File(path).isDirectory()) {
                        roots.add(rootDirMeta(path));
                    }
                }
                if (roots.isEmpty()) {
                    // standard quick dirs for an empty list, minus the ones the
                    // user explicitly removed from this list before
                    final List<String> hidden = JsonDB.get(BookCSS.get().searchPathsHiddenJson);
                    final List<String> fallback = new ArrayList<String>();
                    fallback.add(Environment.getExternalStorageDirectory().getPath());
                    final String pathDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getPath();
                    if (new File(pathDownloads).isDirectory()) {
                        fallback.add(pathDownloads);
                    }
                    for (String fallbackPath : fallback) {
                        if (!hidden.contains(fallbackPath) && new File(fallbackPath).isDirectory()) {
                            roots.add(rootDirMeta(fallbackPath));
                        }
                    }
                }
                return roots;
            }

            if (path().startsWith(Clouds.PREFIX_CLOUD)) {
                cloudStorage = Clouds.get()
                                     .cloud(path());
                String cloudPath = Clouds.getPath(path());
                if (TxtUtils.isEmpty(cloudPath)) {
                    cloudPath = "/";
                }
                LOG.d("Open clound path", cloudPath);
                List<CloudMetaData> items = cloudStorage.getChildren(cloudPath);

                List<FileMeta> result = new ArrayList<FileMeta>();

                for (CloudMetaData cl : items) {
                    String path = cl.getPath();
                    String name = cl.getName();
                    Long modifiedAt = cl.getModifiedAt();
                    long size = cl.getSize();

                    LOG.d("CloudMetaData", path, name, modifiedAt, size, cl.getImageMetaData());

                    FileMeta meta = new FileMeta(Clouds.getPrefix(path()) + path);

                    if (cl.getFolder()) {
                        meta.setCusType(FileMetaAdapter.DISPLAY_TYPE_DIRECTORY);
                    }

                    meta.setTitle(name);
                    meta.setPathTxt(name);

                    meta.setSize(size);
                    meta.setSizeTxt(ExtUtils.readableFileSize(size));

                    if (modifiedAt != null) {
                        meta.setDate(modifiedAt);
                        meta.setDateTxt(ExtUtils.getDateFormat(modifiedAt));
                    }
                    meta.setState(FileMetaCore.STATE_FULL);

                    result.add(meta);
                }

                return result;
            }

            if (ExtUtils.isExteralSD(getInitPath())) {

                List<FileMeta> items = new ArrayList<FileMeta>();

                Uri uri = Uri.parse(path());

                ContentResolver contentResolver = getActivity().getContentResolver();
                Uri childrenUri = null;

                childrenUri = ExtUtils.getChildUri(getContext(), uri);

                if (childrenUri != null) {

                    LOG.d("newNode uri >> ", uri);
                    LOG.d("newNode childrenUri >> ", childrenUri);

                    Cursor childCursor = contentResolver.query(childrenUri, new String[]{ //
                                    Document.COLUMN_DISPLAY_NAME, //
                                    Document.COLUMN_DOCUMENT_ID, //
                                    Document.COLUMN_ICON, //
                                    Document.COLUMN_LAST_MODIFIED, //
                                    Document.COLUMN_MIME_TYPE, //
                                    Document.COLUMN_SIZE, //
                                    Document.COLUMN_SUMMARY, //
                            }, //
                            null, null, null); //
                    try {
                        while (childCursor.moveToNext()) {
                            String COLUMN_DISPLAY_NAME = childCursor.getString(0);
                            String COLUMN_DOCUMENT_ID = childCursor.getString(1);
                            String COLUMN_ICON = childCursor.getString(2);
                            String COLUMN_LAST_MODIFIED = childCursor.getString(3);
                            String COLUMN_MIME_TYPE = childCursor.getString(4);
                            String COLUMN_SIZE = childCursor.getString(5);
                            String COLUMN_SUMMARY = childCursor.getString(6);

                            LOG.d("found- child 2=", COLUMN_DISPLAY_NAME, COLUMN_DOCUMENT_ID, COLUMN_ICON);

                            FileMeta meta = new FileMeta();
                            meta.setAuthor(SearchFragment2.EMPTY_ID);

                            final Uri newNode = DocumentsContract.buildDocumentUriUsingTree(uri, COLUMN_DOCUMENT_ID);
                            meta.setPath(newNode.toString());
                            LOG.d("newNode", newNode);

                            if (Document.MIME_TYPE_DIR.equals(COLUMN_MIME_TYPE)) {
                                meta.setCusType(FileMetaAdapter.DISPLAY_TYPE_DIRECTORY);
                                meta.setPathTxt(COLUMN_DISPLAY_NAME);
                                meta.setTitle(COLUMN_DISPLAY_NAME);

                            } else {
                                try {
                                    if (COLUMN_SIZE != null) {
                                        long size = Long.parseLong(COLUMN_SIZE);
                                        meta.setSize(size);
                                        meta.setSizeTxt(ExtUtils.readableFileSize(size));
                                    }
                                    if (COLUMN_LAST_MODIFIED != null) {
                                        meta.setDateTxt(ExtUtils.getDateFormat(Long.parseLong(COLUMN_LAST_MODIFIED)));
                                    }
                                } catch (Exception e) {
                                    LOG.e(e);
                                }
                                meta.setExt(ExtUtils.getFileExtension(COLUMN_DISPLAY_NAME));

                                if (BookType.FB2.is(COLUMN_DISPLAY_NAME)) {
                                    meta.setTitle(TxtUtils.encode1251(COLUMN_DISPLAY_NAME));
                                } else {
                                    meta.setTitle(COLUMN_DISPLAY_NAME);
                                }

                            }
                            items.add(meta);

                        }
                    } finally {
                        closeQuietly(childCursor);
                    }
                }
                return items;

            } else {
                boolean isDisplayAllFilesInFolder = AppProfile.DOWNLOADS_DIR.getPath()
                                                                            .equals(path()) || AppState.get().isDisplayAllFilesInFolder;
                LOG.d("isDisplayAllFilesInFolder1", isDisplayAllFilesInFolder);
                List<FileMeta> filesAndDirs =
                        SearchCore.getFilesAndDirs(path(), fragmentType == TYPE_DEFAULT,
                                isDisplayAllFilesInFolder);
                int allCount = filesAndDirs.size();
                ExtUtils.removeReadBooks(filesAndDirs);
                readCount = allCount - filesAndDirs.size();
                return filesAndDirs;
            }
        } catch (Exception e) {
            LOG.e(e);
        }
        return Collections.emptyList();
    }

    @Override public void populateDataInUI(List<FileMeta> items) {
        displayItems(items);
        showPathHeader();

        if (isRestorePos) {
            final int pos = rememberPos.get(path()) == null ? 0 :
                    rememberPos.get(path());
            recyclerView.getLayoutManager()
                        .scrollToPosition(pos);
            LOG.d("rememberPos go", path(), pos);
        }

    }

    private FileMeta rootDirMeta(String path) {
        FileMeta m = new FileMeta(path);
        m.setCusType(FileMetaAdapter.DISPLAY_TYPE_DIRECTORY);
        // folder list entry: name as the title, full path as the subtitle
        m.setTitle(ExtUtils.getFileName(path));
        m.setPathTxt(ExtUtils.getFileName(path));
        return m;
    }

    /**
     * One remote-protocol server block (SMB or SFTP): header + "add" button
     * (PRO gated) and the saved server list; tapping a server opens the
     * detached network browse page on its remote:// root.
     */
    private void buildRemoteNetSection(String type, LinearLayout netSection, final Activity a, final Runnable rebuild) {
        final boolean isSftp = RemoteBook.TYPE_SFTP.equals(type);
        netSection.addView(netSectionDivider());
        final View header = netSectionHeader(getString(isSftp ? R.string.moon_net_section_sftp : R.string.moon_net_section_smb),
                new OnClickListener() {
                    @Override public void onClick(View v) {
                        if (!AppsConfig.isProFeaturesEnabled()) {
                            PrefFragment2.proLockedToast(v);
                            return;
                        }
                        AddRemoteDialog.showDialog(a, isSftp ? RemoteBook.TYPE_SFTP : RemoteBook.TYPE_SMB, rebuild, null);
                    }
                });
        PrefFragment2.alphaIfProLocked(header);
        applyHeaderProLock(header);
        netSection.addView(header);
        for (final RemoteServer srv : RemoteStore.load(type)) {
            netSection.addView(netListItem(R.drawable.glyphicons_544_cloud, srv.title, new OnClickListener() {
                @Override public void onClick(View v) {
                    ((MainTabs2) a).openNetworkPage(true, srv.browseRoot(), srv.title);
                }
            }, new OnClickListener() {
                @Override public void onClick(View v) {
                    AlertDialogs.showDialog(a,
                            a.getString(R.string.do_you_want_to_delete_) + " " + srv.title,
                            a.getString(R.string.delete), new Runnable() {
                                @Override public void run() {
                                    RemoteStore.remove(srv);
                                    WebDavCredentials.clear(a, RemoteStore.credentialsKey(srv.id));
                                    WebDavCredentials.clear(a, RemoteStore.keyPassKey(srv.id));
                                    AppProfile.save(a);
                                    rebuild.run();
                                }
                            });
                }
            }, new OnClickListener() {
                @Override public void onClick(View v) {
                    if (!AppsConfig.isProFeaturesEnabled()) {
                        PrefFragment2.proLockedToast(v);
                        return;
                    }
                    AddRemoteDialog.showDialog(a, srv.getTypeStored(), rebuild, srv);
                }
            }, new OnClickListener() {
                @Override public void onClick(View v) {
                    // scan the server into the shelf (PRO feature)
                    if (!AppsConfig.isProFeaturesEnabled()) {
                        PrefFragment2.proLockedToast(v);
                        return;
                    }
                    com.foobnix.remote.RemoteScanner.scan(a, srv);
                }
            }));
        }
    }

    /**
     * Network section of the "My files" root view: three separated blocks     * (OPDS catalogs, WebDAV servers, library folders), each with its own
     * header + "add" button and a vertical list. Tapping an OPDS/WebDAV entry
     * opens a detached network page on that target; every entry can be
     * removed individually with its own delete icon.
     */
    private void buildNetSections() {
        if (netSection == null) {
            return;
        }
        final Activity a = getActivity();
        if (!(a instanceof MainTabs2)) {
            return;
        }
        netSection.removeAllViews();
        if (searchSection != null) {
            searchSection.removeAllViews();
        }
        final Runnable rebuild = new Runnable() {
            @Override public void run() {
                buildNetSections();
                // the home screen mirrors these entries (filesRow / netRow);
                // bump the global hash so the dashboard reloads on next select
                EventBus.getDefault().post(new UpdateAllFragments());
            }
        };

        // --- OPDS catalogs ---
        netSection.addView(netSectionDivider());
        netSection.addView(netSectionHeader(getString(R.string.moon_net_section_opds), new OnClickListener() {
            @Override public void onClick(View v) {
                AddCatalogDialog.showDialog(a, rebuild, null, true);
            }
        }));
        for (final String[] cat : getOpdsCatalogs()) {
            netSection.addView(netListItem(R.drawable.glyphicons_417_globe, cat[1], new OnClickListener() {
                @Override public void onClick(View v) {
                    ((MainTabs2) a).openNetworkPage(false, cat[0], cat[1]);
                }
            }, new OnClickListener() {
                @Override public void onClick(View v) {
                    AlertDialogs.showDialog(a,
                            a.getString(R.string.do_you_want_to_delete_) + " " + cat[1],
                            a.getString(R.string.delete), new Runnable() {
                                @Override public void run() {
                                    AppState.get().allOPDSLinks =
                                            AppState.get().allOPDSLinks.replace(cat[2], "");
                                    AppProfile.save(a);
                                    rebuild.run();
                                }
                            });
                }
            }, new OnClickListener() {
                @Override public void onClick(View v) {
                    // reuse the add dialog's edit mode (drops the old line, saves the new one)
                    Entry e = new Entry();
                    e.appState = cat[2];
                    String[] seg = cat[2].replace(";", "").split(",");
                    if (seg.length >= 4) {
                        e.logo = seg[3];
                    }
                    AddCatalogDialog.showDialog(a, rebuild, e, true);
                }
            }));
        }

        // --- WebDAV servers (block always visible, list may be empty) ---
        // PRO feature: adding/editing servers is locked (fdroid / pro without
        // the IAP unlock); already-saved servers stay browsable and removable
        netSection.addView(netSectionDivider());
        final View webdavHeader = netSectionHeader(getString(R.string.moon_net_section_webdav), new OnClickListener() {
            @Override public void onClick(View v) {
                if (!AppsConfig.isProFeaturesEnabled()) {
                    PrefFragment2.proLockedToast(v);
                    return;
                }
                AddWebDavDialog.showDialog(a, rebuild, null);
            }
        });
        // PRO 置灰：未解锁/fdroid 标题半透明 + 小锁图标，点击弹升级提示
        PrefFragment2.alphaIfProLocked(webdavHeader);
        applyHeaderProLock(webdavHeader);
        netSection.addView(webdavHeader);
        for (final WebDavServer srv : WebDavStore.load()) {
            netSection.addView(netListItem(R.drawable.glyphicons_544_cloud, srv.title, new OnClickListener() {
                @Override public void onClick(View v) {
                    ((MainTabs2) a).openNetworkPage(true, srv.url, srv.title);
                }
            }, new OnClickListener() {
                @Override public void onClick(View v) {
                    AlertDialogs.showDialog(a,
                            a.getString(R.string.do_you_want_to_delete_) + " " + srv.title,
                            a.getString(R.string.delete), new Runnable() {
                                @Override public void run() {
                                    WebDavStore.remove(srv);
                                    WebDavCredentials.clear(a, srv.url);
                                    AppProfile.save(a);
                                    rebuild.run();
                                }
                            });
                }
            }, new OnClickListener() {
                @Override public void onClick(View v) {
                    // edit mode: the dialog prefills and replaces the old entry
                    // (PRO feature: locked when the IAP unlock is absent)
                    if (!AppsConfig.isProFeaturesEnabled()) {
                        PrefFragment2.proLockedToast(v);
                        return;
                    }
                    AddWebDavDialog.showDialog(a, rebuild, srv);
                }
            }, new OnClickListener() {
                @Override public void onClick(View v) {
                    // scan the WebDAV server into the shelf (PRO feature)
                    if (!AppsConfig.isProFeaturesEnabled()) {
                        PrefFragment2.proLockedToast(v);
                        return;
                    }
                    com.foobnix.remote.RemoteScanner.scanWebDav(a, srv);
                }
            }));
        }

        // --- SMB / SFTP servers (online reading, same PRO policy as WebDAV) ---
        buildRemoteNetSection(RemoteBook.TYPE_SMB, netSection, a, rebuild);
        buildRemoteNetSection(RemoteBook.TYPE_SFTP, netSection, a, rebuild);

        // --- library folders (the list itself lives in the RecyclerView) ---
        netSection.addView(netSectionDivider());
        netSection.addView(netSectionHeader(getString(R.string.moon_section_folders), new OnClickListener() {
            @Override public void onClick(View v) {
                PopupMenu p = new PopupMenu(a, v);
                p.getMenu()
                 .add(R.string.add_folder)
                 .setOnMenuItemClickListener(new OnMenuItemClickListener() {
                     @Override public boolean onMenuItemClick(MenuItem item) {
                         addLibraryFolder(rebuild);
                         return false;
                     }
                 });
                p.getMenu()
                 .add(R.string.add_file)
                 .setOnMenuItemClickListener(new OnMenuItemClickListener() {
                     @Override public boolean onMenuItemClick(MenuItem item) {
                         addLibraryFile(rebuild);
                         return false;
                     }
                 });
                p.getMenu()
                 .add(R.string.search)
                 .setOnMenuItemClickListener(new OnMenuItemClickListener() {
                     @Override public boolean onMenuItemClick(MenuItem item) {
                         scanLibrary();
                         return false;
                     }
                 });
                p.show();
            }
        }));

        // --- search tools: rendered BELOW the library-folder list (see the
        // searchSection container in the layout), so the folders block
        // (header + list) stays one visual unit
        if (searchSection != null) {
            searchSection.addView(netSectionDivider());
            searchSection.addView(netSectionHeader(getString(R.string.search), null));
            // tools moved here from the preferences "file search" category
            searchSection.addView(netListItem(R.drawable.glyphicons_144_database_search,
                    getString(R.string.search_for_text_in_multiple_documents), new OnClickListener() {
                        @Override public void onClick(View v) {
                            MultyDocSearchDialog.show((androidx.fragment.app.FragmentActivity) a);
                        }
                    }, null));
        }
    }

    @Override public void onResume() {
        super.onResume();
        // the network sources may have changed behind this page (WebDAV sync
        // applies them to AppState/BookCSS in the background): rebuild on
        // every foreground visit so synced config is visible immediately
        buildNetSections();
    }

    /** "add a library folder" flow, same checks as the preferences page. */
    private void addLibraryFolder(final Runnable refresh) {
        final androidx.fragment.app.FragmentActivity fa = (androidx.fragment.app.FragmentActivity) getActivity();
        ChooserDialogFragment.chooseFolder(fa, BookCSS.get().dirLastPath)
                .setOnSelectListener(new ResultResponse2<String, Dialog>() {
                    @Override public boolean onResultRecive(String nPath, Dialog dialog) {
                        boolean isExists = false;
                        for (String str : JsonDB.get(BookCSS.get().searchPathsJson)) {
                            if (TxtUtils.isNotEmpty(str) && nPath.equals(str)) {
                                isExists = true;
                                break;
                            }
                        }
                        if (nPath.equals("/") || ExtUtils.isExteralSD(nPath)) {
                            Toast.makeText(fa, R.string.incorrect_value, Toast.LENGTH_SHORT).show();
                        } else if (isExists) {
                            Toast.makeText(fa, R.string.this_directory_is_already_in_the_list, Toast.LENGTH_LONG).show();
                        } else {
                            BookCSS.get().searchPathsJson = JsonDB.add(BookCSS.get().searchPathsJson, nPath);
                            // an explicitly added folder is wanted again: lift
                            // any earlier fallback-exclusion of it
                            BookCSS.get().searchPathsHiddenJson = JsonDB.remove(BookCSS.get().searchPathsHiddenJson, nPath);
                        }
                        dialog.dismiss();
                        AppProfile.save(fa);
                        refresh.run();
                        // the chooser's embedded browser rewrote the shared
                        // displayPath — go back to the root view (folders list)
                        displayAnyPath(ROOT_PATH);
                        return false;
                    }
                });
    }

    /** "add a single file to the library" flow, from the old preferences row. */
    private void addLibraryFile(final Runnable refresh) {
        final androidx.fragment.app.FragmentActivity fa = (androidx.fragment.app.FragmentActivity) getActivity();
        ChooserDialogFragment.chooseFile(fa, "")
                .setOnSelectListener(new ResultResponse2<String, Dialog>() {
                    @Override public boolean onResultRecive(String nPath, Dialog dialog) {
                        if (!new File(nPath).isFile()) {
                            Toast.makeText(fa, R.string.incorrect_value, Toast.LENGTH_SHORT).show();
                        } else if (JsonDB.contains(BookCSS.get().searchPathsJson, nPath)) {
                            Toast.makeText(fa, R.string.this_directory_is_already_in_the_list, Toast.LENGTH_LONG).show();
                        } else {
                            BookCSS.get().searchPathsJson = JsonDB.add(BookCSS.get().searchPathsJson, nPath);
                            // an explicitly added folder is wanted again: lift
                            // any earlier fallback-exclusion of it
                            BookCSS.get().searchPathsHiddenJson = JsonDB.remove(BookCSS.get().searchPathsHiddenJson, nPath);
                        }
                        dialog.dismiss();
                        AppProfile.save(fa);
                        refresh.run();
                        // see addLibraryFolder: restore the root view
                        displayAnyPath(ROOT_PATH);
                        return false;
                    }
                });
    }

    /** Runs the library scan (the "search" action of the old preferences row). */
    private void scanLibrary() {
        final Activity a = getActivity();
        if (a == null) {
            return;
        }
        AppProfile.save(a);
        SearchAllBooksWorker.run(a);
        Intent intent = new Intent(UIFragment.INTENT_TINT_CHANGE)//
                .putExtra(MainTabs2.EXTRA_PAGE_NUMBER, UITab.getCurrentTabIndex(UITab.SearchFragment));//
        LocalBroadcastManager.getInstance(a).sendBroadcast(intent);
    }

    /** Pro 门控区块标题：未解锁时在标题旁显示小锁图标 */
    private void applyHeaderProLock(View header) {
        if (header instanceof LinearLayout && ((LinearLayout) header).getChildAt(0) instanceof TextView) {
            PrefFragment2.applyProLock((TextView) ((LinearLayout) header).getChildAt(0));
        }
    }

    /** Section header row: title on the left, an "add" link on the right. */
    private View netSectionHeader(String title, OnClickListener onAdd) {
        LinearLayout row = new LinearLayout(getActivity());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Dips.dpToPx(14), Dips.dpToPx(12), Dips.dpToPx(14), Dips.dpToPx(6));

        TextView t = new TextView(getActivity());
        t.setText(title);
        t.setTextSize(19);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(TintUtil.getColorInDayNighth());
        row.addView(t, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        TextView add = new TextView(getActivity());
        if (onAdd == null) {
            add.setVisibility(View.GONE);
        } else {
            add.setText("+ " + getString(R.string.add));
            add.setTextSize(17);
            add.setTextColor(TintUtil.color);
            add.setOnClickListener(onAdd);
        }
        row.addView(add, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** Vertical list entry with a leading icon and an optional delete icon. */
    private View netListItem(int iconRes, String text, OnClickListener onClick, OnClickListener onRemove) {
        return netListItem(iconRes, text, onClick, onRemove, null);
    }

    /** As above with an optional pencil edit icon (OPDS / WebDAV entries). */
    private View netListItem(int iconRes, String text, OnClickListener onClick, OnClickListener onRemove, OnClickListener onEdit) {
        return netListItem(iconRes, text, onClick, onRemove, onEdit, null);
    }

    /** As above with an optional library-scan icon (remote server entries). */
    private View netListItem(int iconRes, String text, OnClickListener onClick, OnClickListener onRemove,
                             OnClickListener onEdit, OnClickListener onScan) {
        LinearLayout row = new LinearLayout(getActivity());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Dips.dpToPx(14), Dips.dpToPx(6), Dips.dpToPx(14), Dips.dpToPx(6));
        row.setOnClickListener(onClick);

        ImageView icon = new ImageView(getActivity());
        icon.setImageResource(iconRes);
        icon.setColorFilter(TintUtil.getColorInDayNighth());
        icon.setPadding(0, 0, Dips.dpToPx(10), 0);
        row.addView(icon, new LinearLayout.LayoutParams(Dips.dpToPx(40), Dips.dpToPx(40)));

        TextView t = new TextView(getActivity());
        t.setText(text);
        t.setTextSize(18);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        t.setTextColor(TintUtil.getColorInDayNighth());
        row.addView(t, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        if (onScan != null) {
            ImageView scan = new ImageView(getActivity());
            scan.setImageResource(R.drawable.glyphicons_144_database_search);
            scan.setColorFilter(TintUtil.getColorInDayNighth());
            scan.setPadding(Dips.dpToPx(8), Dips.dpToPx(2), Dips.dpToPx(2), Dips.dpToPx(2));
            scan.setOnClickListener(onScan);
            row.addView(scan, new LinearLayout.LayoutParams(Dips.dpToPx(40), Dips.dpToPx(40)));
        }

        if (onEdit != null) {
            ImageView edit = new ImageView(getActivity());
            edit.setImageResource(R.drawable.my_glyphicons_pen);
            edit.setColorFilter(TintUtil.getColorInDayNighth());
            edit.setPadding(Dips.dpToPx(8), Dips.dpToPx(2), Dips.dpToPx(2), Dips.dpToPx(2));
            edit.setOnClickListener(onEdit);
            row.addView(edit, new LinearLayout.LayoutParams(Dips.dpToPx(40), Dips.dpToPx(40)));
        }

        if (onRemove != null) {
            ImageView del = new ImageView(getActivity());
            del.setImageResource(R.drawable.glyphicons_599_menu_close);
            del.setColorFilter(TintUtil.getColorInDayNighth());
            del.setPadding(Dips.dpToPx(8), Dips.dpToPx(2), Dips.dpToPx(2), Dips.dpToPx(2));
            del.setOnClickListener(onRemove);
            row.addView(del, new LinearLayout.LayoutParams(Dips.dpToPx(40), Dips.dpToPx(40)));
        }
        return row;
    }

    private View netSectionDivider() {
        View line = new View(getActivity());
        line.setBackgroundColor(0x1e999999);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Dips.dpToPx(1));
        lp.topMargin = Dips.dpToPx(12);
        lp.bottomMargin = Dips.dpToPx(6);
        line.setLayoutParams(lp);
        return line;
    }

    /** Saved OPDS catalogs as {url, title, rawLine} triples (favorites entry skipped). */
    private List<String[]> getOpdsCatalogs() {
        List<String[]> res = new ArrayList<>();
        for (String line : AppState.get().allOPDSLinks.split(";")) {
            if (TxtUtils.isEmpty(line) || line.contains("star_1.png")) {
                continue;
            }
            String[] it = line.split(",");
            if (it.length >= 2) {
                res.add(new String[]{it[0], it[1], line + ";"});
            }
        }
        return res;
    }

    public boolean onBackAction() {
        if (AppState.get().isHideReadBook) {
            //IMG.clearMemoryCache();
            //IMG.clearDiscCache();
        }
        // detached folder page: BACK at the entry folder closes the page,
        // above it the normal up-navigation applies
        if (browsePath != null) {
            if (browsePath.equals(startFolder)) {
                return false;
            }
            String parent = new File(browsePath).getParent();
            if (TxtUtils.isEmpty(parent)) {
                return false;
            }
            displayAnyPath(parent);
            isRestorePos = true;
            return true;
        }
        // leaving one of the root entries goes back to the closed list, not
        // up into the storage tree
        if (!ROOT_PATH.equals(path())) {
            for (String scanPath : JsonDB.get(BookCSS.get().searchPathsJson)) {
                if (TxtUtils.isNotEmpty(scanPath) && scanPath.equals(path())) {
                    displayAnyPath(ROOT_PATH);
                    return true;
                }
            }
        }
        if (ROOT_PATH.equals(path())) {
            return false;
        }
        if (ExtUtils.isExteralSD(BookCSS.get().dirLastPath)) {
            String path = BookCSS.get().dirLastPath;
            LOG.d("pathBack before", path);
            if (path.contains("%2F")) {
                path = path.substring(0, path.lastIndexOf("%2F"));
            } else {
                path = path.substring(0, path.lastIndexOf("%3A") + 3);
            }
            LOG.d("pathBack after", path);

            if (path.endsWith("%3A")) {
                displayAnyPath(path);
                return false;
            } else {
                displayAnyPath(path);
                return true;
            }

        } else {
            File file = new File(path());
            String path = file.getParent();

            if (TxtUtils.isEmpty(path)) {
                return false;
            }

            if (TxtUtils.isEmpty(path) || !path.contains("/")) {
                path = Clouds.getPrefix(path()) + "/";
            }

            LOG.d("parent", path);

            displayAnyPath(path);
            isRestorePos = true;
            return true;
        }
    }

    private TextView makeQuickDirChip(String text) {
        TextView chip = new TextView(getActivity());
        chip.setText(text);
        chip.setTextSize(14);
        chip.setSingleLine(true);
        chip.setAllCaps(false);
        chip.setPadding(Dips.dpToPx(14), Dips.dpToPx(6), Dips.dpToPx(14), Dips.dpToPx(6));
        chip.setBackgroundResource(R.drawable.bg_search_edit);
        chip.setTextColor(getResources().getColor(R.color.tint_gray));
        return chip;
    }

    private void buildQuickDirChips(View view) {
        LinearLayout chips = view.findViewById(R.id.quickDirChips);
        if (chips == null) {
            return;
        }
        List<String[]> entries = new ArrayList<>();
        entries.add(new String[]{getString(R.string.memory), Environment.getExternalStorageDirectory().getPath()});
        String pathDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getPath();
        if (new File(pathDownloads).isDirectory()) {
            entries.add(new String[]{ExtUtils.getFileName(pathDownloads), pathDownloads});
        }
        for (String info : ExtUtils.getAllExternalStorages(getActivity())) {
            String name = ExtUtils.isExteralSD(info) ? ExtUtils.getExtSDDisplayName(getContext(), info) : new File(info).getName();
            entries.add(new String[]{name, info});
        }
        if (new File(BookCSS.get().downlodsPath).isDirectory()) {
            entries.add(new String[]{"HowRead/" + getString(R.string.downloads), BookCSS.get().downlodsPath});
        }
        List<FileMeta> starFolders = AppData.get().getAllFavoriteFolders();
        List<String> names = new ArrayList<>();
        for (FileMeta f : starFolders) {
            names.add(f.getPath());
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        for (final String info : names) {
            String name = ExtUtils.isExteralSD(info) ? ExtUtils.getExtSDDisplayName(getContext(), info) : new File(info).getName();
            entries.add(new String[]{name, info});
        }
        for (final String[] e : entries) {
            TextView chip = makeQuickDirChip(e[0]);
            chip.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) {
                    displayAnyPath(e[1]);
                }
            });
            chips.addView(chip);
        }
    }

    public void displayAnyPath(String path) {
        if (TxtUtils.isEmpty(path)) {
            path = "/";
        }
        LOG.d("Display-path", path);
        isRestorePos = false;
        setPath(path);
        if (browsePath == null && new File(path).isDirectory()) {
            // only real local dirs may become the last chooser dir;
            // "my-files:" / OPDS / content paths would poison folder pickers
            BookCSS.get().dirLastPath = path;
        }
        if (netSection != null) {
            netSection.setVisibility(ROOT_PATH.equals(path) ? View.VISIBLE : View.GONE);
        }
        if (searchSection != null) {
            searchSection.setVisibility(ROOT_PATH.equals(path) ? View.VISIBLE : View.GONE);
        }
        // the 书库 folder rows are rendered bigger on the root page only
        if (searchAdapter != null) {
            searchAdapter.myFilesRoot = ROOT_PATH.equals(path);
        }
        // the quick-dir chip strip only makes sense inside a directory; on
        // the root view it would break the OPDS / WebDAV / folders grouping
        if (quickDirChipsRow != null) {
            quickDirChipsRow.setVisibility(ROOT_PATH.equals(path) ? View.GONE : View.VISIBLE);
        }
        // the browse toolbar (back / paste / sort / ...) belongs to directory
        // browsing; the "My files" root page is a plain configuration list
        if (pathContainer != null) {
            pathContainer.setVisibility(ROOT_PATH.equals(path) ? View.GONE : View.VISIBLE);
        }
        populate();
    }

    @TargetApi(Build.VERSION_CODES.KITKAT) public void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (RuntimeException rethrown) {
                throw rethrown;
            } catch (Exception ignored) {
            }
        }
    }

    public void displayItems(List<FileMeta> items) {
        itemsCount = items.size();
        if (searchAdapter == null) {
            return;
        }

        searchAdapter.clearItems();

        try {
            sortItems(items);
            Collections.sort(items, FileMetaComparators.DIRS);

        } catch (Exception e) {
            LOG.e(e);
        }

        for (int i = 0; i < items.size(); i++) {
            FileMeta m = items.get(i);
            if (m.getCusType() == null) {// directory
                LOG.d("DISPALY_TYPE_LAYOUT_TITLE_FOLDERS", i);
                FileMeta it = new FileMeta();
                it.setCusType(FileMetaAdapter.DISPALY_TYPE_LAYOUT_TITLE_NONE);
                items.add(i, it);
                break;
            }
        }

        searchAdapter.getItemsList()
                     .addAll(items);
        recyclerView.setAdapter(searchAdapter);

        scroller.postDelayed(new Runnable() {

            @Override public void run() {
                scroller.fullScroll(HorizontalScrollView.FOCUS_RIGHT);
            }

        }, 100);

        if (new File(path(), MdContext.SUMMARY_MD).isFile()) {
            openAsBook.setVisibility(View.VISIBLE);
        } else if (FolderContext.isFolderWithImage(items)) {
            openAsBook.setVisibility(View.VISIBLE);
        } else {
            openAsBook.setVisibility(View.GONE);
        }

    }

    public void showPathHeader() {
        paths.removeAllViews();

        if (ROOT_PATH.equals(path())) {
            TextView nameView = new TextView(getActivity());
            nameView.setText(R.string.moon_my_files);
            nameView.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
            paths.addView(nameView);
            return;
        }

        if (TYPE_SELECT_FILE_OR_FOLDER == fragmentType) {
            editPath.setText(path());
        }

        if (ExtUtils.isExteralSD(path())) {
            String id = ExtUtils.getExtSDDisplayName(getContext(), path());

            TextView slash = new TextView(getActivity());
            slash.setText(id);
            slash.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
            paths.addView(slash);
        } else {

            final String prefix = Clouds.getPrefix(path());
            final String path = Clouds.getPath(path());
            final String name = Clouds.getPrefixName(path());

            final String[] split = path.split("/");

            TextView nameView = new TextView(getActivity());
            if (split.length == 0) {
                nameView.setText(name + ": " + Clouds.get()
                                                     .getUserLogin(path()) + " ");
            } else {
                nameView.setText(name + ":");
            }
            nameView.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
            nameView.setOnClickListener(new OnClickListener() {

                @Override public void onClick(View v) {
                    displayAnyPath(prefix + "/");
                }
            });
            paths.addView(nameView);

            if (split.length == 0 && Clouds.isCloud(path())) {
                TextView logout = new TextView(getActivity());
                logout.setText(TxtUtils.underline(getActivity().getString(R.string.logout)));
                logout.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
                logout.setOnClickListener(new OnClickListener() {

                    @Override public void onClick(View v) {
                        new AsyncProgressTask<Boolean>() {

                            @Override public Context getContext() {
                                return getActivity();
                            }

                            @Override protected Boolean doInBackground(Object... params) {
                                try {
                                    Clouds.get()
                                          .logout(path());
                                } catch (Exception e) {
                                    return false;
                                }
                                return true;
                            }

                            @Override protected void onPostExecute(Boolean result) {
                                super.onPostExecute(result);
                                displayAnyPath(AppProfile.DOWNLOADS_DIR.getPath());
                            }

                        }.execute();
                    }
                });
                paths.addView(logout);
            }

            pasteFrom.setOnClickListener(
                    v -> ShareDialog.dirLongPress(getActivity(), path(), () -> resetFragment()));
            Views.visible(pasteFrom, TempHolder.get().copyFromPath != null);

            for (int i = 0; i < split.length; i++) {
                final int index = i;
                String part = split[i];
                if (TxtUtils.isEmpty(part)) {
                    continue;
                }
                TextView slash = new TextView(getActivity());
                slash.setText(" / ");
                slash.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));

                TextView item = new TextView(getActivity());
                item.setText(part);
                item.setGravity(Gravity.CENTER);
                item.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
                item.setSingleLine();
                TypedValue outValue = new TypedValue();
                getContext().getTheme()
                            .resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
                item.setBackgroundResource(outValue.resourceId);

                if (i == split.length - 1) {
                    item.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                    item.setMinimumWidth(Dips.DP_40);
                    item.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
                }

                item.setOnClickListener(new OnClickListener() {

                    @Override public void onClick(View v) {

                        IMG.clearMemoryCache();
                        IMG.clearDiscCache();

                        StringBuilder builder = new StringBuilder();
                        for (int j = 0; j <= index; j++) {
                            builder.append("/");
                            builder.append(split[j]);
                        }
                        String itemPath = builder.toString();
                        itemPath = TxtUtils.replaceFirst(itemPath, "//", "/");
                        String pathFull = prefix + itemPath;
                        pathFull = pathFull.replace("://", ":/");
                        displayAnyPath(pathFull);
                        isRestorePos = true;
                    }
                });

                item.setOnLongClickListener(new OnLongClickListener() {

                    @Override public boolean onLongClick(View v) {
                        StringBuilder builder = new StringBuilder();
                        for (int j = 0; j <= index; j++) {
                            builder.append("/");
                            builder.append(split[j]);
                        }
                        String itemPath = builder.toString();
                        itemPath = TxtUtils.replaceFirst(itemPath, "//", "/");
                        String pathFull = prefix + itemPath;
                        pathFull = pathFull.replace("://", ":/");

                        //deleteFolderPopup(getActivity(), pathFull);
                        Dialogs.showEditDialog2(getActivity(), getString(R.string.go_to_the_folder), pathFull,
                                new ResultResponse<String>() {
                                    @Override public boolean onResultRecive(String path1) {
                                        displayAnyPath(path1);
                                        return false;
                                    }
                                });

                        return false;
                    }
                });

                paths.addView(slash);
                paths.addView(item, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));

            }

            TextView stub = new TextView(getActivity());

            if (AppState.get().isHideReadBook) {
                stub.setText(" (" + (itemsCount + readCount) + "/" + readCount + ") ");
            } else {
                stub.setText(" (" + itemsCount + ") ");
            }
            stub.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
            stub.setSingleLine();
            paths.addView(stub);

            Apps.accessibilityText(getActivity(), split[split.length - 1], getString(R.string.folder_selected),
                    "" + itemsCount);

        }

        if (AppDB.get()
                 .isStarFolder(path())) {
            starIcon.setImageResource(R.drawable.glyphicons_49_star);
        } else {
            starIcon.setImageResource(R.drawable.glyphicons_50_star_empty);
        }
        TintUtil.setTintImageWithAlpha(starIcon, Color.WHITE);

        starIcon.setOnClickListener(new OnClickListener() {

            @Override public void onClick(View v) {
                FileMeta fileMeta = AppDB.get()
                                         .getOrCreate(path());
                fileMeta.setCusType(FileMetaAdapter.DISPLAY_TYPE_DIRECTORY);
                fileMeta.setPathTxt(ExtUtils.getFileName(path()));
                DefaultListeners.getOnStarClick(getActivity())
                                .onResultRecive(fileMeta, null);
                if (AppDB.get()
                         .isStarFolder(path())) {
                    starIcon.setImageResource(R.drawable.glyphicons_49_star);
                } else {
                    starIcon.setImageResource(R.drawable.glyphicons_50_star_empty);
                }
            }
        });

        final String ldir = FolderContext.genarateXML(searchAdapter.getItemsList(), path(), false)
                                         .toString();
        if (AppDB.get()
                 .isStarFolder(ldir)) {
            starIconDir.setImageResource(R.drawable.glyphicons_49_star);
        } else {
            starIconDir.setImageResource(R.drawable.glyphicons_50_star_empty);
        }
        TintUtil.setTintImageWithAlpha(starIconDir,
                getActivity() instanceof MainTabs2 ? TintUtil.getColorInDayNighth() :
                        TintUtil.getColorInDayNighthBook());

        starIconDir.setOnClickListener(new OnClickListener() {

            @Override public void onClick(View v) {
                File genarateXMLBook =
                        FolderContext.genarateXML(searchAdapter.getItemsList(), path(), true);

                FileMeta fileMeta = AppDB.get()
                                         .getOrCreate(genarateXMLBook.getPath());
                FileMetaCore.createMetaIfNeed(genarateXMLBook.getPath(), false);
                DefaultListeners.getOnStarClick(getActivity())
                                .onResultRecive(fileMeta, null);
                if (AppDB.get()
                         .isStarFolder(ldir)) {
                    starIconDir.setImageResource(R.drawable.glyphicons_49_star);
                } else {
                    starIconDir.setImageResource(R.drawable.glyphicons_50_star_empty);
                }
                TintUtil.setTintImageWithAlpha(starIconDir,
                        getActivity() instanceof MainTabs2 ? TintUtil.getColorInDayNighth() :
                                TintUtil.getColorInDayNighthBook());
            }
        });

    }

    private void deleteFolderPopup(Activity a, String path) {

        AlertDialogs.showOkDialog(a, getString(R.string.delete_the_directory_all_the_files_in_the_directory_),
                new Runnable() {
                    @Override public void run() {
                        final boolean result = ExtUtils.deleteRecursive(new File(path));
                        AlertDialogs.showResultToasts(a, result);
                        resetFragment();
                    }
                });

    }

    public void onGridList() {
        onGridList(AppState.get().broseMode, onListGrid, searchAdapter, null);

    }

    private void popupMenu(final ImageView onGridList) {
        MyPopupMenu p = new MyPopupMenu(getActivity(), onGridList);
        PopupHelper.addPROIcon(p, getActivity());

        List<Integer> names = Arrays.asList(R.string.list, R.string.compact, R.string.grid, R.string.cover);
        final List<Integer> icons = Arrays.asList(R.drawable.my_glyphicons_114_paragraph_justify,
                R.drawable.my_glyphicons_114_justify_compact, R.drawable.glyphicons_157_thumbnails,
                R.drawable.glyphicons_158_thumbnails_small);
        final List<Integer> actions =
                Arrays.asList(AppState.MODE_LIST, AppState.MODE_LIST_COMPACT, AppState.MODE_GRID, AppState.MODE_COVERS);

        p.getMenu()
         .addCheckbox(getString(R.string.folder_preview), AppState.get().isFolderPreview,
                 new CompoundButton.OnCheckedChangeListener() {
                     @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                         AppState.get().isFolderPreview = isChecked;

                         populate();
                     }
                 });

        p.getMenu()
         .addCheckbox(getString(R.string.show_hidden), AppState.get().isDisplayAllFilesInFolder,
                 new CompoundButton.OnCheckedChangeListener() {
                     @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                         AppState.get().isDisplayAllFilesInFolder = isChecked;

                         populate();
                     }
                 });
        p.getMenu()
         .addCheckbox(getString(R.string.hide_read_books), AppState.get().isHideReadBook,
                 new CompoundButton.OnCheckedChangeListener() {
                     @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                         AppState.get().isHideReadBook = isChecked;
                         TempHolder.listHash++;

                         populate();
                     }
                 });

        for (int i = 0; i < names.size(); i++) {
            final int index = i;
            p.getMenu()
             .add(names.get(i))
             .setIcon(icons.get(i))
             .setOnMenuItemClickListener(new OnMenuItemClickListener() {

                 @Override public boolean onMenuItemClick(MenuItem item) {
                     AppState.get().broseMode = actions.get(index);
                     onGridList.setImageResource(icons.get(index));
                     onGridList();
                     return false;
                 }
             });
        }

        p.show();

    }

    @Override public boolean isBackPressed() {
        return onBackAction();
    }

    public void setOnPositiveAction(ResultResponse<String> onPositiveAction) {
        this.onPositiveAction = onPositiveAction;
    }

    public void setOnCloseAction(ResultResponse<String> onCloseAction) {
        this.onCloseAction = onCloseAction;
    }

    @Override public void notifyFragment() {
        if (searchAdapter != null) {
            searchAdapter.notifyDataSetChanged();
            sortOrder.setVisibility(TxtUtils.visibleIf(AppState.get().isVisibleSorting));
        }

    }

    @Override public void resetFragment() {
        LOG.d("Browse resetFragment");
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                onGridList();
                populate();
            }
        }, 150);

    }

}
