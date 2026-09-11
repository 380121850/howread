package com.foobnix.ui2.fragment;

import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MenuItem.OnMenuItemClickListener;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.util.Pair;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.foobnix.android.utils.Keyboards;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.ResultResponse;
import com.foobnix.android.utils.ResultResponse2;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.dao2.FileMeta;
import com.foobnix.model.AppProfile;
import com.foobnix.model.AppState;
import com.foobnix.opds.Entry;
import com.foobnix.opds.Feed;
import com.foobnix.opds.Hrefs;
import com.foobnix.opds.Link;
import com.foobnix.opds.OPDS;
import com.foobnix.opds.SamlibOPDS;
import com.foobnix.pdf.info.ADS;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.TintUtil;
import com.foobnix.pdf.info.Urls;
import com.foobnix.pdf.info.model.BookCSS;
import com.foobnix.pdf.info.view.AlertDialogs;
import com.foobnix.pdf.info.view.MyProgressBar;
import com.foobnix.pdf.info.widget.AddCatalogDialog;
import com.foobnix.pdf.info.widget.ChooserDialogFragment;
import com.foobnix.pdf.search.view.ProgressTask;
import com.foobnix.sys.TempHolder;
import com.foobnix.ui2.AppDB;
import com.foobnix.ui2.adapter.EntryAdapter;
import com.foobnix.ui2.adapter.NetworkRootAdapter;
import com.foobnix.ui2.fast.FastScrollRecyclerView;
import com.foobnix.webdav.AddWebDavDialog;
import com.foobnix.webdav.WebDavAdapter;
import com.foobnix.webdav.WebDavClient;
import com.foobnix.webdav.WebDavCredentials;
import com.foobnix.webdav.WebDavItem;
import com.foobnix.webdav.WebDavServer;
import com.foobnix.webdav.WebDavStore;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Stack;

import okhttp3.CacheControl;
import okhttp3.Response;
import okio.BufferedSink;
import okio.Okio;

public class OpdsFragment2 extends UIFragment<Entry> {
    public static final Pair<Integer, Integer> PAIR = new Pair<Integer, Integer>(R.string.network, R.drawable.glyphicons_417_globe);
    public List<Entry> allCatalogs = new ArrayList<Entry>();
    EntryAdapter searchAdapter;
    TextView titleView;
    String url = "/";
    String urlRoot = "";
    String title;
    Stack<String> stack = new Stack<String>();
    View pathContainer;
    long enqueue;
    ImageView starIcon;
    boolean isNeedLoginPassword = false;

    // WebDAV browse mode, folded into this Network page (own storage,
    // credentials and client from the com.foobnix.webdav package).
    boolean webDavMode = false;
    // "smb" / "sftp" while browsing a remote:// server of that protocol,
    // "" for plain WebDAV / OPDS browsing
    String netType = "";
    boolean authFailed = false;
    boolean webDavLoadFailed = false;
    String currentServerUrl = "";
    List<WebDavItem> webDavItems = new ArrayList<WebDavItem>();
    List<WebDavItem> rootWebDavItems = new ArrayList<WebDavItem>();
    WebDavAdapter webDavAdapter;
    NetworkRootAdapter networkRootAdapter;

    public OpdsFragment2() {
        super();
    }

    public static OpdsFragment2 newInstance(Bundle bundle) {
        OpdsFragment2 br = new OpdsFragment2();
        br.setArguments(bundle);
        return br;
    }

    public List<Entry> getAllCatalogs() {

        if (false) {
            String test = "https://books.fbreader.org/opds";
            return Arrays.asList(new Entry(test, test));
        }

        String[] list = AppState.get().allOPDSLinks.split(";");
        List<Entry> res = new ArrayList<Entry>();
        boolean hasStars = false;
        for (String line : list) {
            if (TxtUtils.isEmpty(line)) {
                continue;
            }
            if (line.contains("star_1.png")) {
                hasStars = true;
                continue;
            }
            String[] it = line.split(",");
            try {
                final Entry e = new Entry(it[0], it[1], it[2], it[3], true);
                e.appState = line + ";";
                res.add(e);
            } catch (Exception e) {

                LOG.e(e, line);
            }


        }
        if (hasStars) {
            res.add(0, new Entry(SamlibOPDS.ROOT_FAVORITES, getString(R.string.favorites), getString(R.string.my_favorites_links), "assets://opds/star_1.png", true));
        }
        return res;

    }

    @Override
    public Pair<Integer, Integer> getNameAndIconRes() {
        return PAIR;
    }

    @Override
    public void onTintChanged() {
        TintUtil.setBackgroundFillColor(pathContainer, TintUtil.color);
        ((FastScrollRecyclerView) recyclerView).myConfiguration();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_opds2, container, false);

        recyclerView = (FastScrollRecyclerView) view.findViewById(R.id.recyclerView);

        titleView = (TextView) view.findViewById(R.id.titleView);
        starIcon = (ImageView) view.findViewById(R.id.starIcon);
        pathContainer = view.findViewById(R.id.pathContainer);
        MyProgressBar = (MyProgressBar) view.findViewById(R.id.MyProgressBarOPDS);
        MyProgressBar.setVisibility(View.GONE);
        TintUtil.setDrawableTint(MyProgressBar.getIndeterminateDrawable().getCurrent(), Color.WHITE);

        searchAdapter = new EntryAdapter();
        webDavAdapter = new WebDavAdapter();

        networkRootAdapter = new NetworkRootAdapter(searchAdapter, webDavAdapter);
        networkRootAdapter.setOnAddOpds(new Runnable() {

            @Override
            public void run() {
                AddCatalogDialog.showDialog(getActivity(), new Runnable() {

                    @Override
                    public void run() {
                        populate();
                    }
                }, null, true);
            }
        });
        networkRootAdapter.setOnAddWebDav(new Runnable() {

            @Override
            public void run() {
                AddWebDavDialog.showDialog(getActivity(), new Runnable() {

                    @Override
                    public void run() {
                        populate();
                    }
                }, null);
            }
        });
        networkRootAdapter.setOnOpdsSettings(new Runnable() {

            @Override
            public void run() {
                showProxySettings();
            }
        });
        networkRootAdapter.setOnRestoreDefaults(new Runnable() {

            @Override
            public void run() {
                AlertDialogs.showOkDialog(getActivity(), getActivity().getString(R.string.restore_defaults_full), new Runnable() {

                    @Override
                    public void run() {
                        AppState.get().allOPDSLinks = AppState.OPDS_DEFAULT;
                        url = "/";
                        populate();
                    }
                });

            }
        });
        networkRootAdapter.setOnOpenFaq(new Runnable() {

            @Override
            public void run() {
                Urls.open(getActivity(), "https://wiki.mobileread.com/wiki/OPDS");

            }
        });

        webDavAdapter.setOnClick(new ResultResponse<WebDavItem>() {

            @Override
            public boolean onResultRecive(WebDavItem item) {
                onClickWebDav(item);
                return false;
            }
        });
        webDavAdapter.setOnLongClick(new ResultResponse<WebDavItem>() {

            @Override
            public boolean onResultRecive(WebDavItem item) {
                if (isRoot() && item.isServer) {
                    WebDavServer srv = new WebDavServer(item.href, item.name);
                    srv.appState = item.appState;
                    AddWebDavDialog.showDialog(getActivity(), new Runnable() {

                        @Override
                        public void run() {
                            populate();
                        }
                    }, srv);
                }
                return true;
            }
        });
        webDavAdapter.setOnRemove(new ResultResponse<WebDavItem>() {

            @Override
            public boolean onResultRecive(final WebDavItem item) {
                if (!isRoot() || !item.isServer) {
                    return false;
                }
                AlertDialogs.showDialog(getActivity(), getActivity().getString(R.string.do_you_want_to_delete_) + " " + item.name, getString(R.string.delete), new Runnable() {

                    @Override
                    public void run() {
                        WebDavServer srv = new WebDavServer(item.href, item.name);
                        srv.appState = item.appState;
                        WebDavStore.remove(srv);
                        WebDavCredentials.clear(getContext(), item.href);
                        populate();
                    }
                });
                return false;
            }
        });

        onGridList();

        searchAdapter.setOnItemClickListener(new ResultResponse<Entry>() {

            @Override
            public boolean onResultRecive(Entry result) {
                for (Link link : result.links) {
                    if (link.isOpdsLink()) {
                        onClickLink(link);
                        break;
                    }
                }

                return false;
            }
        });

        searchAdapter.setOnRemoveLinkClickListener(new ResultResponse<Entry>() {

            @Override
            public boolean onResultRecive(final Entry result) {
                AlertDialogs.showDialog(getActivity(), getActivity().getString(R.string.do_you_want_to_delete_) + " " + result.title, getString(R.string.delete), new Runnable() {

                    @Override
                    public void run() {
                        AppState.get().allOPDSLinks = AppState.get().allOPDSLinks.replace(result.appState, "");
                        url = "/";
                        populate();
                    }
                });

                return false;
            }
        });

        searchAdapter.setOnLinkClickListener(new ResultResponse<Link>() {

            @Override
            public boolean onResultRecive(Link link) {
                onClickLink(link);
                return false;
            }
        });

        searchAdapter.setOnItemLongClickListener(new ResultResponse<Entry>() {
            @Override
            public boolean onResultRecive(Entry result) {
                if (url.equals("/")) {
                    AddCatalogDialog.showDialog(getActivity(), new Runnable() {

                        @Override
                        public void run() {
                            populate();
                        }
                    }, result, SamlibOPDS.isSamlibUrl(result.homeUrl) ? false : true);
                }
                return false;
            }
        });

        starIcon.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                final Entry entry = new Entry();
                String url2 = url;
                if (url2.contains("?")) {
                    url2 = url2.substring(0, url2.indexOf("?"));
                }
                entry.setAppState(url, title, url2, "assets://opds/star_1.png");

                if (!AppState.get().allOPDSLinks.contains(url)) {

                    AddCatalogDialog.showDialog(getActivity(), new Runnable() {

                        @Override
                        public void run() {
                            starIcon.setImageResource(R.drawable.glyphicons_49_star);
                            TintUtil.setTintImageWithAlpha(starIcon, Color.WHITE);
                        }
                    }, entry, false);
                } else {
                    AppState.get().allOPDSLinks = AppState.get().allOPDSLinks.replace(entry.appState, "");
                    starIcon.setImageResource(R.drawable.glyphicons_50_star_empty);
                    TintUtil.setTintImageWithAlpha(starIcon, Color.WHITE);
                    // AlertDialogs.showOkDialog(getActivity(),
                    // getActivity().getString(R.string.do_you_want_to_delete_), new Runnable() {
                    //
                    // @Override
                    // public void run() {
                    //
                    // // url = "/";
                    // }
                    // });
                }

            }
        });

        view.findViewById(R.id.onBack).setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                onBackAction();
            }
        });

        view.findViewById(R.id.onHome).setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                stack.clear();
                webDavMode = false;
                netType = "";
                authFailed = false;
                currentServerUrl = "";
                url = getHome();
                LOG.d("URLAction", "ADD", url);
                urlRoot = "";
                populate();
            }
        });

        view.findViewById(R.id.onHome).setOnLongClickListener(new OnLongClickListener() {

            @Override
            public boolean onLongClick(View v) {
                AlertDialogs.showOkDialog(getActivity(), getActivity().getString(R.string.restore_defaults_full), new Runnable() {

                    @Override
                    public void run() {
                        AppState.get().allOPDSLinks = AppState.OPDS_DEFAULT;
                        populate();
                    }
                });
                return true;
            }
        });

        OPDS.buildProxy();

        if (pendingOpenUrl != null) {
            String target = pendingOpenUrl;
            boolean webDav = pendingOpenWebDav;
            pendingOpenUrl = null;
            applyExternalTarget(webDav, target);
        } else {
            populate();
        }
        onTintChanged();

        return view;
    }

    /**
     * OPDS / proxy settings dialog. Lives in the OPDS sub-item of the Network
     * root view (was the gear button in the old top bar).
     */
    private void showProxySettings() {
        ADS.hideAdsTemp(getActivity());

        final AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        View view = LayoutInflater.from(getActivity()).inflate(R.layout.dialog_proxy_server, null, false);

        final CheckBox proxyEnable = (CheckBox) view.findViewById(R.id.proxyEnable);
        final CheckBox opdsLargeCovers = (CheckBox) view.findViewById(R.id.opdsLargeCovers);
        final CheckBox createBookNameFolder = (CheckBox) view.findViewById(R.id.createBookNameFolder);
        final EditText proxyServer = (EditText) view.findViewById(R.id.proxyServer);
        final EditText proxyPort = (EditText) view.findViewById(R.id.proxyPort);
        final EditText proxyUser = (EditText) view.findViewById(R.id.proxyUser);
        final EditText proxyPassword = (EditText) view.findViewById(R.id.proxyPassword);

        final TextView proxyType = (TextView) view.findViewById(R.id.proxyType);

        TintUtil.setBackgroundFillColor(view.findViewById(R.id.section1), TintUtil.color);
        TintUtil.setBackgroundFillColor(view.findViewById(R.id.section2), TintUtil.color);

        proxyEnable.setChecked(AppState.get().proxyEnable);
        proxyServer.setText(AppState.get().proxyServer);
        proxyPort.setText(AppState.get().proxyPort == 0 ? "" : "" + AppState.get().proxyPort);
        proxyUser.setText(AppState.get().proxyUser);
        proxyPassword.setText(AppState.get().proxyPassword);

        proxyEnable.setOnCheckedChangeListener(new OnCheckedChangeListener() {

            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    if (TxtUtils.isEmpty(proxyServer.getText().toString())) {
                        proxyServer.requestFocus();
                        proxyEnable.setChecked(false);
                        Toast.makeText(getContext(), R.string.incorrect_value, Toast.LENGTH_SHORT).show();
                    } else if ("0".equals(proxyPort.getText().toString()) || TxtUtils.isEmpty(proxyPort.getText().toString())) {
                        proxyPort.requestFocus();
                        proxyEnable.setChecked(false);
                        Toast.makeText(getContext(), R.string.incorrect_value, Toast.LENGTH_SHORT).show();
                    }
                }

            }
        });

        TxtUtils.underline(proxyType, AppState.get().proxyType);

        proxyType.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                PopupMenu menu = new PopupMenu(v.getContext(), v);
                menu.getMenu().add(AppState.PROXY_HTTP).setOnMenuItemClickListener(new OnMenuItemClickListener() {

                    @Override
                    public boolean onMenuItemClick(MenuItem item) {
                        AppState.get().proxyType = AppState.PROXY_HTTP;
                        TxtUtils.underline(proxyType, AppState.get().proxyType);
                        return false;
                    }
                });
                menu.getMenu().add(AppState.PROXY_SOCKS).setOnMenuItemClickListener(new OnMenuItemClickListener() {

                    @Override
                    public boolean onMenuItemClick(MenuItem item) {
                        AppState.get().proxyType = AppState.PROXY_SOCKS;
                        TxtUtils.underline(proxyType, AppState.get().proxyType);
                        return false;
                    }
                });
                menu.show();
            }
        });

        builder.setPositiveButton(R.string.apply, new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {
                AppState.get().proxyEnable = proxyEnable.isChecked();
                AppState.get().proxyServer = proxyServer.getText().toString();

                try {
                    AppState.get().proxyPort = Integer.parseInt(proxyPort.getText().toString());
                    if (AppState.get().proxyPort >= 65535) {
                        AppState.get().proxyPort = 0;
                        proxyUser.setText("0");
                    }
                } catch (Exception e) {
                    AppState.get().proxyPort = 0;
                }

                AppState.get().proxyUser = proxyUser.getText().toString().trim();
                AppState.get().proxyPassword = proxyPassword.getText().toString().trim();

                OPDS.buildProxy();

                AppProfile.save(getActivity());
                Keyboards.close(proxyServer);

            }
        });

        builder.setNeutralButton(R.string.cancel, new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {
            }
        });

        opdsLargeCovers.setChecked(AppState.get().opdsLargeCovers);
        opdsLargeCovers.setOnCheckedChangeListener(new OnCheckedChangeListener() {

            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                AppState.get().opdsLargeCovers = isChecked;
            }
        });

        createBookNameFolder.setChecked(AppState.get().createBookNameFolder);
        createBookNameFolder.setOnCheckedChangeListener(new OnCheckedChangeListener() {

            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                AppState.get().createBookNameFolder = isChecked;
            }
        });

        final TextView downlodsPath = (TextView) view.findViewById(R.id.downlodsPath);
        TxtUtils.underline(downlodsPath, TxtUtils.lastTwoPath(BookCSS.get().downlodsPath));
        downlodsPath.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(final View v) {
                ChooserDialogFragment.chooseFolder(getActivity(), BookCSS.get().downlodsPath).setOnSelectListener(new ResultResponse2<String, Dialog>() {
                    @Override
                    public boolean onResultRecive(String nPath, Dialog dialog) {
                        BookCSS.get().downlodsPath = nPath;
                        TxtUtils.underline(downlodsPath, TxtUtils.lastTwoPath(BookCSS.get().downlodsPath));
                        dialog.dismiss();
                        return false;
                    }
                });
            }
        });

        builder.setView(view);
        builder.show();
    }

    public boolean onBackAction() {
        // detached page opened from "My files": BACK at the entry target
        // closes the page instead of unwinding into the network root list
        if (externalMode && stack.size() <= 1) {
            return false;
        }
        String last = popStack();

        boolean res = !getHome().equals(last);

        LOG.d("URLAction", last, url);

        if (last.equals(url)) {
            last = popStack();// two times
        }
        if (webDavMode && getHome().equals(last)) {
            // back to the combined root view of the Network page
            webDavMode = false;
            netType = "";
            authFailed = false;
            webDavLoadFailed = false;
            currentServerUrl = "";
            url = last;
            stack.push(url);
            populate();
            return res;
        }
        url = last;
        stack.push(url);
        LOG.d("URLAction", "ADD", url);

        populate();
        return res;
    }

    public String popStack() {
        if (stack.isEmpty()) {
            return getHome();
        }
        return stack.pop();
    }

    public String getHome() {
        return "/";
    }

    public void onClickLink(final Link link) {
        LOG.d("onClickLink", link.type, link.href);
        if (link.filePath != null) {
            FileMeta meta = new FileMeta(link.filePath);
            meta.setTitle(link.getDownloadName());
            ExtUtils.openFile(getActivity(), meta);
        } else if (link.isDisabled()) {
            Toast.makeText(getActivity(), R.string.can_t_download, Toast.LENGTH_SHORT).show();
        } else if (link.isWebLink()) {
            Urls.open(getActivity(), link.href);
        } else if (link.isOpdsLink()) {
            if (url.equals("/")) {
                urlRoot = link.href;
            }
            url = link.href;
            stack.push(url);
            LOG.d("URLAction", "ADD", url);
            populate();

        } else if (link.isImageLink()) {
        } else {
            LOG.d("Download >>", link.href);
            if (isInProgress()) {
                Toast.makeText(getContext(), R.string.please_wait, Toast.LENGTH_SHORT).show();
                return;
            }

            AlertDialogs.showDialog(getActivity(), link.getDownloadName(), getActivity().getString(R.string.download), new Runnable() {
                String bookPath;

                @Override
                public void run() {

                    new ProgressTask<>() {
                        @Override
                        public Context getContext() {
                            return OpdsFragment2.this.getContext();
                        }

                        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
                        @Override
                        protected Object doInBackground(Object... params) {

                            try {
                                OutputStream outStream = null;
                                String displayName = link.getDownloadName();
                                if (ExtUtils.isExteralSD(BookCSS.get().downlodsPath)) {
                                    String mimeType = ExtUtils.getMimeType(displayName);

                                    Uri uri = Uri.parse(BookCSS.get().downlodsPath);
                                    Uri childrenUri = ExtUtils.getChildUri(getContext(), uri);
                                    Uri createDocument = DocumentsContract.createDocument(getActivity().getContentResolver(), childrenUri, mimeType, displayName);

                                    bookPath = createDocument.toString();
                                    outStream = getActivity().getContentResolver().openOutputStream(createDocument);
                                } else {
                                    File LIRBI_DOWNLOAD_DIR;
                                    if (AppState.get().createBookNameFolder) {
                                        LIRBI_DOWNLOAD_DIR = new File(BookCSS.get().downlodsPath, displayName);
                                    } else {
                                        if (TxtUtils.isNotEmpty(link.author)) {
                                            LIRBI_DOWNLOAD_DIR = new File(BookCSS.get().downlodsPath, TxtUtils.fixFileName(link.author));
                                        } else {
                                            LIRBI_DOWNLOAD_DIR = new File(BookCSS.get().downlodsPath);
                                        }
                                    }

                                    if (!LIRBI_DOWNLOAD_DIR.exists()) {
                                        LIRBI_DOWNLOAD_DIR.mkdirs();
                                    }

                                    File file = null;
                                    try {
                                        file = new File(LIRBI_DOWNLOAD_DIR, displayName);
                                        file.delete();
                                        outStream = new FileOutputStream(file);
                                    } catch (FileNotFoundException e1) {
                                        try {
                                            file = new File(LIRBI_DOWNLOAD_DIR, TxtUtils.substringSmart(displayName, 50) + "." + ExtUtils.getFileExtension(displayName));
                                            file.delete();
                                            outStream = new FileOutputStream(file);
                                        } catch (FileNotFoundException e2) {
                                            file = new File(LIRBI_DOWNLOAD_DIR, displayName.hashCode() + "." + ExtUtils.getFileExtension(displayName));
                                            file.delete();
                                            outStream = new FileOutputStream(file);
                                        }

                                    }


                                    bookPath = file.getPath();
                                }

                                String href = link.href;

                                // fix manybooks
                                okhttp3.Request request = new okhttp3.Request.Builder()//
                                        .header("User-Agent", OPDS.USER_AGENT)
                                        .header("Accept-Language", AppState.get().getAppLang())
                                        .cacheControl(new CacheControl.Builder().noCache().build()).url(href)//
                                        .build();//

                                Response response = OPDS.client//
                                        .newCall(request)//
                                        .execute();

                                BufferedSink sink = Okio.buffer(Okio.sink(outStream));
                                sink.writeAll(response.body().source());
                                sink.close();

                                outStream.close();

                                LOG.d("Download finish");

                            } catch (Exception e) {
                                LOG.e(e);
                                return false;
                            }
                            return true;

                        }

                        @Override
                        protected void onPreExecute() {
                            MyProgressBar.setVisibility(View.VISIBLE);
                        }

                        ;

                        @Override
                        protected void onPostExecute(Object result) {
                            MyProgressBar.setVisibility(View.GONE);
                            if ((Boolean) result == false) {
                                Toast.makeText(getContext(), R.string.loading_error, Toast.LENGTH_LONG).show();
                                // Urls.openWevView(getActivity(), link.href, null);
                            } else {
                                link.filePath = bookPath;

                                if (!ExtUtils.isExteralSD(bookPath)) {
                                    FileMeta meta = AppDB.get().getOrCreate(bookPath);
                                    meta.setIsSearchBook(true);
                                    AppDB.get().save(meta);
                                    //IMG.loadCoverPageWithEffect(meta.getPath(), IMG.getImageSize());
                                }
                                TempHolder.listHash++;

                            }
                            clearEmpty();
                        }

                        ;

                    }.execute();

                }
            });

        }
    }

    public boolean isRoot() {
        return "/".equals(url) && !webDavMode;
    }

    // external entry from the "My files" page: applied on onCreateView when
    // the fragment sits detached beyond the pager's offscreen limit
    String pendingOpenUrl;
    boolean pendingOpenWebDav;
    // true while this instance is the detached page opened from "My files":
    // a dead catalog shows an error instead of falling back to the network
    // root list, and BACK at the entry target closes the page
    boolean externalMode = false;

    /** Open a specific OPDS catalog (or the root list when url is "/"). */
    public void openExternal(boolean webDav, String targetUrl) {
        externalMode = true;
        if (isAdded() && recyclerView != null) {
            applyExternalTarget(webDav, targetUrl);
        } else {
            pendingOpenUrl = targetUrl;
            pendingOpenWebDav = webDav;
        }
    }

    private void applyExternalTarget(boolean webDav, String targetUrl) {
        webDavMode = webDav;
        authFailed = false;
        webDavLoadFailed = false;
        netType = com.foobnix.remote.RemoteBook.getType(targetUrl);
        currentServerUrl = webDav ? targetUrl : "";
        url = TxtUtils.isEmpty(targetUrl) ? "/" : targetUrl;
        stack.clear();
        stack.push(url);
        populate();
    }

    public void onClickWebDav(WebDavItem item) {
        if (isRoot()) {
            currentServerUrl = item.href;
            webDavMode = true;
            netType = com.foobnix.remote.RemoteBook.getType(item.href);
            url = item.href;
            stack.push(url);
            populate();
        } else if (item.isDir) {
            url = item.href;
            stack.push(url);
            populate();
        } else if (com.foobnix.remote.RemoteBook.isRemotePath(item.href)) {
            // remote book: online open (Pro + "online first" switch) or download
            com.foobnix.remote.RemoteBookOpener.openOrDownload(getActivity(), item.href, item.size);
        } else if (toRemoteWebDav(item) != null) {
            // plain WebDAV listing returns raw http hrefs: convert to the
            // remote:// identity so the online cache-open path applies
            com.foobnix.remote.RemoteBookOpener.openOrDownload(getActivity(), toRemoteWebDav(item), item.size);
        } else {
            downloadWebDav(item);
        }
    }

    /**
     * Converts a plain WebDAV file item (raw http href) to its remote:// URI
     * using the server the browse session was entered through. Returns null
     * when the href does not belong to a known WebDAV server — the caller
     * then falls back to the legacy download flow.
     */
    private String toRemoteWebDav(WebDavItem item) {
        try {
            WebDavServer srv = WebDavStore.findForUrl(currentServerUrl);
            if (srv == null) {
                return null;
            }
            String root = WebDavStore.trimSlash(srv.url);
            String href = item.href;
            if (TxtUtils.isEmpty(href) || !href.startsWith(root)) {
                return null;
            }
            // Uri.decode (NOT URLDecoder) keeps "+" intact, only %XX expands
            String path = Uri.decode(href.substring(root.length()));
            String id = com.foobnix.remote.RemoteSessionFactory.webdavId(srv.url);
            return com.foobnix.remote.RemoteBook.build(com.foobnix.remote.RemoteBook.TYPE_WEBDAV, id, path);
        } catch (Exception e) {
            LOG.e(e);
            return null;
        }
    }

    public void downloadWebDav(final WebDavItem item) {
        if (com.foobnix.remote.RemoteBook.isRemotePath(item.href)) {
            // SMB / SFTP download: block-cache aware full fetch into the
            // downloads folder, then open the local copy
            com.foobnix.remote.RemoteBookOpener.downloadAndOpen(getActivity(), item.href, item.size);
            return;
        }
        if (isInProgress()) {
            Toast.makeText(getContext(), R.string.please_wait, Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialogs.showDialog(getActivity(), item.name, getActivity().getString(R.string.download), new Runnable() {
            String bookPath;

            @Override
            public void run() {
                MyProgressBar.setVisibility(View.VISIBLE);
                new ProgressTask<>() {
                    @Override
                    public Context getContext() {
                        return OpdsFragment2.this.getContext();
                    }

                    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
                    @Override
                    protected Object doInBackground(Object... params) {
                        InputStream in = null;
                        OutputStream out = null;
                        File file = null;
                        try {
                            WebDavServer srv = WebDavStore.findForUrl(currentServerUrl);
                            String login = "", password = "";
                            boolean trustAll = false;
                            if (srv != null) {
                                String[] creds = WebDavCredentials.load(getContext(), srv.url);
                                if (creds != null) {
                                    login = creds[0];
                                    password = creds[1];
                                }
                                trustAll = WebDavCredentials.isTrustAll(getContext(), srv.url);
                            }

                            in = WebDavClient.openStream(item.href, login, password, trustAll);

                            String fileName = TxtUtils.fixFileName(item.name);
                            if (TxtUtils.isEmpty(fileName)) {
                                String ext = ExtUtils.getFileExtension(item.href).replace(".", "");
                                fileName = item.href.hashCode() + (TxtUtils.isEmpty(ext) ? "" : "." + ext);
                            }

                            if (ExtUtils.isExteralSD(BookCSS.get().downlodsPath)) {
                                // External/removable SD card: write through SAF
                                // (same path as OPDS downloads in onClickLink).
                                String mimeType = ExtUtils.getMimeType(fileName);
                                Uri baseUri = Uri.parse(BookCSS.get().downlodsPath);
                                Uri childrenUri = ExtUtils.getChildUri(getContext(), baseUri);
                                Uri docUri = DocumentsContract.createDocument(
                                        getActivity().getContentResolver(), childrenUri, mimeType, fileName);
                                out = getActivity().getContentResolver().openOutputStream(docUri);
                                bookPath = docUri.toString();
                            } else {
                                File dir = new File(BookCSS.get().downlodsPath);
                                if (!dir.exists()) {
                                    dir.mkdirs();
                                }
                                file = new File(dir, fileName);
                                file.delete();
                                out = new FileOutputStream(file);
                                bookPath = file.getPath();
                            }

                            byte[] buf = new byte[16 * 1024];
                            int n;
                            while ((n = in.read(buf)) != -1) {
                                out.write(buf, 0, n);
                            }
                            return true;
                        } catch (Exception e) {
                            LOG.e(e);
                            // Remove a partial file so failed retries don't
                            // accumulate bad data on disk.
                            if (file != null) {
                                file.delete();
                            }
                            return false;
                        } finally {
                            try {
                                if (in != null) {
                                    in.close();
                                }
                            } catch (Exception ignored) {
                            }
                            try {
                                if (out != null) {
                                    out.close();
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }

                    @Override
                    protected void onPostExecute(Object result) {
                        MyProgressBar.setVisibility(View.GONE);
                        if (!isAdded()) {
                            return;
                        }
                        if ((Boolean) result == false) {
                            Toast.makeText(getContext(), R.string.loading_error, Toast.LENGTH_LONG).show();
                        } else {
                            FileMeta meta = AppDB.get().getOrCreate(bookPath);
                            meta.setIsSearchBook(true);
                            AppDB.get().save(meta);
                            TempHolder.listHash++;
                            ExtUtils.openFile(getActivity(), meta);
                        }
                    }
                }.execute();
            }
        });
    }

    private static String decodeName(String href) {
        String last = WebDavClient.lastName(href);
        try {
            return URLDecoder.decode(last, "UTF-8");
        } catch (Exception e) {
            return last;
        }
    }

    public void clearEmpty() {
        if (ExtUtils.isExteralSD(BookCSS.get().downlodsPath)) {
            searchAdapter.notifyDataSetChanged();
            return;
        }

        try {
            File LIRBI_DOWNLOAD_DIR = new File(BookCSS.get().downlodsPath);

            if (!LIRBI_DOWNLOAD_DIR.exists()) {
                LIRBI_DOWNLOAD_DIR.mkdirs();
            }

            for (String file : LIRBI_DOWNLOAD_DIR.list()) {
                File f = new File(LIRBI_DOWNLOAD_DIR, file);
                if (f.length() == 0) {
                    LOG.d("Delete file", f.getPath());
                    f.delete();
                }
            }

            searchAdapter.notifyDataSetChanged();
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    @Override
    public List<Entry> prepareDataInBackground() {
        try {
            LOG.d("OPDS URL", url, "webDavMode", webDavMode);
            if (webDavMode && com.foobnix.remote.RemoteBook.isRemotePath(url)) {
                // SMB / SFTP browsing: same WebDavItem rows, own clients
                List<WebDavItem> items = com.foobnix.remote.RemoteBook.TYPE_SFTP.equals(netType)
                        ? com.foobnix.remote.SftpClient.list(url)
                        : com.foobnix.remote.SmbClient.list(url);
                if (items == null) {
                    authFailed = com.foobnix.remote.RemoteBook.TYPE_SFTP.equals(netType)
                            ? com.foobnix.remote.SftpClient.lastErrorWasAuth
                            : com.foobnix.remote.SmbClient.lastErrorWasAuth;
                    webDavLoadFailed = true;
                    webDavItems = new ArrayList<WebDavItem>();
                    return Collections.emptyList();
                }
                authFailed = false;
                webDavLoadFailed = false;
                webDavItems = items;
                com.foobnix.remote.RemoteServer srv = com.foobnix.remote.RemoteStore.find(netType,
                        com.foobnix.remote.RemoteBook.getServerId(url));
                title = srv != null ? srv.title : decodeName(url);
                return Collections.emptyList();
            }
            if (webDavMode) {
                WebDavServer srv = WebDavStore.findForUrl(url);
                String login = "", password = "";
                boolean trustAll = false;
                if (srv != null) {
                    currentServerUrl = srv.url;
                    String[] creds = WebDavCredentials.load(getContext(), srv.url);
                    if (creds != null) {
                        login = creds[0];
                        password = creds[1];
                    }
                    trustAll = WebDavCredentials.isTrustAll(getContext(), srv.url);
                }
                List<WebDavItem> items = WebDavClient.list(url, login, password, trustAll);
                if (items == null) {
                    authFailed = WebDavClient.lastErrorWasAuth;
                    webDavLoadFailed = true;
                    webDavItems = new ArrayList<WebDavItem>();
                    return Collections.emptyList();
                }
                authFailed = false;
                webDavLoadFailed = false;
                webDavItems = items;
                title = srv != null ? srv.title : decodeName(url);
                return Collections.emptyList();
            }
            if ("/".equals(url)) {
                title = getString(R.string.network);
                rootWebDavItems = new ArrayList<WebDavItem>();
                for (WebDavServer s : WebDavStore.load()) {
                    WebDavItem item = new WebDavItem(s.url, s.title, true);
                    item.appState = s.appState;
                    rootWebDavItems.add(item);
                }
                return allCatalogs = getAllCatalogs();
            }

            if (SamlibOPDS.isSamlibUrl(url)) {
                Pair<List<Entry>, String> pair = SamlibOPDS.getSamlibResult(url);
                List<Entry> samlibResult = pair.first;
                title = pair.second.replace(SamlibOPDS.ROOT_FAVORITES, getString(R.string.favorites)).replace(SamlibOPDS.ROOT_AWARDS, getString(R.string.awards));
                return samlibResult;
            }

            Feed feed = OPDS.getFeed(url, getContext());
            if (feed == null) {
                return Collections.emptyList();
            }
            isNeedLoginPassword = feed.isNeedLoginPassword;

            LOG.d("Load: >>>", feed.title, url);

            feed.updateLinksForUI();

            if (urlRoot.contains("My:")) {
                urlRoot = url;
            }

            updateLinks(feed.title, urlRoot, feed.links);

            for (Link link : feed.links) {
                if ("next".equals(link.rel)) {
                    feed.entries.add(new Entry("Next", link));
                    break;
                }
            }

            for (Entry e : feed.entries) {
                updateLinks(e.getTitle(), urlRoot, e.links);
                if (e.authorUrl != null) {
                    e.authorUrl = Hrefs.fixHref(e.authorUrl, urlRoot);
                }
            }
            title = TxtUtils.nullToEmpty(feed.title).replace("\n", "").replace("\r", "").trim();
            return feed.entries;
        } catch (Exception e) {
            LOG.e(e);
            return Collections.emptyList();

        }
    }

    public void updateLinks(String parentTitle, String homeUrl, List<Link> links) {
        Link alternative = null;
        for (Link l : links) {
            Hrefs.fixHref(l, homeUrl);
            l.parentTitle = parentTitle;
            new File(BookCSS.get().downlodsPath).mkdirs();
            File book = new File(BookCSS.get().downlodsPath, l.getDownloadName());
            if (book.isFile()) {
                l.filePath = book.getPath();
            }
            if (l.href != null) {

                if (l.href.startsWith("http://manybooks.net/opds/")) {
                    l.type = Link.APPLICATION_ATOM_XML;
                }
                String manyUrl = "http://manybooks.net/send/1:epub:.epub:epub/";
                if (l.href.startsWith(manyUrl)) {
                    String url = l.href.replace(manyUrl, "http://idownload.manybooks.net/");
                    alternative = new Link(url, l.type);
                    alternative.rel = l.rel;
                    alternative.parentTitle = "2." + parentTitle;
                    new File(BookCSS.get().downlodsPath).mkdirs();
                    File book1 = new File(BookCSS.get().downlodsPath, alternative.getDownloadName());
                    if (book1.isFile()) {
                        alternative.filePath = book1.getPath();
                    }

                }
            }

        }
        if (alternative != null) {
            links.add(alternative);
        }
    }

    @Override
    public void populateDataInUI(List<Entry> entries) {
        if (webDavMode) {
            if (authFailed) {
                authFailed = false;
                Toast.makeText(getContext(), R.string.webdav_auth_failed, Toast.LENGTH_LONG).show();
            } else if (webDavLoadFailed) {
                webDavLoadFailed = false;
                Toast.makeText(getContext(), R.string.loading_error, Toast.LENGTH_LONG).show();
            }
            webDavAdapter.setItems(webDavItems);
            recyclerView.setAdapter(webDavAdapter);
            if (title != null) {
                titleView.setText("" + title.replaceAll("[\n\r\t ]+", " ").trim());
            }
            starIcon.setVisibility(View.GONE);
            return;
        }
        if ("/".equals(url)) {
            // combined root view: OPDS + WebDAV sub-items
            networkRootAdapter.setOpdsEntries(entries);
            networkRootAdapter.setWebDavServers(rootWebDavItems);
            recyclerView.setAdapter(networkRootAdapter);
            titleView.setText(getString(R.string.network));
            starIcon.setVisibility(View.GONE);
            return;
        }
        if (isNeedLoginPassword) {
            AddCatalogDialog.showDialogLogin(getActivity(), url, () -> populate());
            return;
        }

        if (entries == null || entries.isEmpty()) {
            if (externalMode) {
                // detached page opened from "My files": a dead catalog shows an
                // error here — it must NOT reset to the network root list
                searchAdapter.clearItems();
                recyclerView.setAdapter(searchAdapter);
                if (title != null) {
                    titleView.setText("" + title.replaceAll("[\n\r\t ]+", " ").trim());
                }
                starIcon.setVisibility(View.GONE);
                Toast.makeText(getContext(), R.string.loading_error, Toast.LENGTH_LONG).show();
                return;
            }
            Urls.openWevView(getActivity(), url, new Runnable() {

                @Override
                public void run() {
                    url = popStack();
                }
            });
            // unreachable catalog: fall back to the parent (or the root when
            // the page opened straight on the target) and rebind, so BACK
            // doesn't retry the dead url forever
            String parent = popStack();
            if (parent.equals(url) || stack.isEmpty()) {
                stack.clear();
                url = "/";
            } else {
                url = parent;
            }
            populate();
            return;
        }

        searchAdapter.clearItems();
        searchAdapter.getItemsList().addAll(entries);
        recyclerView.setAdapter(searchAdapter);

        if (title != null) {
            titleView.setText("" + title.replaceAll("[\n\r\t ]+", " ").trim());
        }

        starIcon.setVisibility(View.VISIBLE);
        for (Entry cat : allCatalogs) {
            if (url.equals(cat.homeUrl)) {
                starIcon.setVisibility(View.GONE);
                break;
            }
        }

        if (AppState.get().allOPDSLinks.contains(url)) {
            starIcon.setImageResource(R.drawable.glyphicons_49_star);
        } else {
            starIcon.setImageResource(R.drawable.glyphicons_50_star_empty);
        }
        TintUtil.setTintImageWithAlpha(starIcon, Color.WHITE);
    }

    public void onGridList() {
        if (searchAdapter == null || webDavAdapter == null || networkRootAdapter == null) {
            return;
        }

        RecyclerView.LayoutManager mLayoutManager = new LinearLayoutManager(getActivity());
        recyclerView.setLayoutManager(mLayoutManager);
        if (webDavMode) {
            recyclerView.setAdapter(webDavAdapter);
        } else if (isRoot()) {
            recyclerView.setAdapter(networkRootAdapter);
        } else {
            recyclerView.setAdapter(searchAdapter);
        }

    }

    @Override
    public boolean isBackPressed() {
        if (isInProgress()) {
            Toast.makeText(getContext(), R.string.please_wait, Toast.LENGTH_SHORT).show();
            return true;
        }
        return onBackAction();
    }

    @Override
    public void onTabReselect() {
        // Single-tap of the already-active Network tab while deep inside an
        // OPDS feed or a WebDAV folder jumps back to the Network root,
        // mirroring the onHome button. The ViewPager makes a tap on the
        // already-active tab a no-op, so SlidingTabLayout routes such a tap
        // to this reselect hook instead.
        if (isInProgress()) {
            return;
        }
        stack.clear();
        webDavMode = false;
        netType = "";
        authFailed = false;
        webDavLoadFailed = false;
        currentServerUrl = "";
        url = getHome();
        urlRoot = "";
        populate();
    }

    @Override
    public void notifyFragment() {
        if (searchAdapter != null) {
            searchAdapter.notifyDataSetChanged();
        }
        if (webDavAdapter != null) {
            webDavAdapter.notifyDataSetChanged();
        }
        if (networkRootAdapter != null) {
            networkRootAdapter.notifyDataSetChanged();
        }
    }

    @Override
    public void resetFragment() {
        onGridList();
    }

}
