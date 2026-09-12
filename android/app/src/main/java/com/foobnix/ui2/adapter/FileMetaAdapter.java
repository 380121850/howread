package com.foobnix.ui2.adapter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
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
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.cardview.widget.CardView;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.ViewHolder;

import com.foobnix.android.utils.Apps;
import com.foobnix.android.utils.Dips;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.ResultResponse;
import com.foobnix.android.utils.ResultResponse2;
import com.foobnix.android.utils.StringDB;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.dao2.FileMeta;
import com.foobnix.model.AppData;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.Clouds;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.pdf.info.IMG;
import com.foobnix.pdf.info.Playlists;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.TintUtil;
import com.foobnix.pdf.info.view.Dialogs;
import com.foobnix.pdf.info.view.MyPopupMenu;
import com.foobnix.pdf.info.wrapper.PopupHelper;
import com.foobnix.ui2.AppDB;
import com.foobnix.ui2.AppDB.SEARCH_IN;
import com.foobnix.ui2.AppDB.SORT_BY;
import com.foobnix.ui2.AppRecycleAdapter;
import com.foobnix.ui2.MainTabs2;
import com.foobnix.ui2.adapter.AuthorsAdapter2.AuthorViewHolder;
import com.foobnix.ui2.fast.FastScroller;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class FileMetaAdapter extends AppRecycleAdapter<FileMeta, RecyclerView.ViewHolder> implements FastScroller.SectionIndexer {

    public static final int DISPLAY_TYPE_FILE = 2;
    public static final int DISPLAY_TYPE_DIRECTORY = 3;
    public static final int DISPALY_TYPE_LAYOUT_STARS = 4;
    public static final int DISPLAY_TYPE_PLAYLIST = 11;
    public static final int DISPLAY_TYPE_NONE = -1;
    public static final int DISPALY_TYPE_LAYOUT_TITLE_FOLDERS = 5;
    public static final int DISPALY_TYPE_LAYOUT_TITLE_BOOKS = 6;
    public static final int DISPALY_TYPE_SERIES = 7;
    public static final int DISPALY_TYPE_LAYOUT_TITLE_NONE = 8;
    public static final int DISPALY_TYPE_LAYOUT_TAG = 9;
    public static final int DISPALY_TYPE_LAYOUT_TITLE_DIVIDER = 10;
    public static final int ADAPTER_LIST = 0;
    public static final int ADAPTER_GRID = 1;
    public static final int ADAPTER_COVERS = 3;
    public static final int ADAPTER_LIST_COMPACT = 4;
    public static final int ADAPTER_SHELF = 5;
    public static final int TEMP_VALUE_NONE = 0;
    public static final int TEMP_VALUE_FOLDER_PATH = 1;
    public static final int TEMP_VALUE_STAR_GRID_ITEM = 2;
    public static final int TEMP_VALUE_SERIES = 3;
    public static final int TEMP2_NONE = 0;
    public static final int TEMP2_RECENT_FROM_BOOK = 1;
    public Fragment fragment;
    public int tempValue = TEMP_VALUE_NONE;
    public int tempValue2 = TEMP2_NONE;

    /**
     * Batch-selection mode of the library shelf (full book paths). Null means
     * selection is off and rows keep their normal look; when set, rows whose
     * path is in the set get a translucent accent background.
     */
    public Set<String> selectionPaths = null;

    private int adapterType = ADAPTER_LIST;
    private ResultResponse<FileMeta> onMenuClickListener;
    private ResultResponse<FileMeta> onDeleteClickListener;
    private ResultResponse<String> onAuthorClickListener;
    private ResultResponse<String> onSeriesClickListener;
    private ResultResponse<String> onTagClickListner;
    private ResultResponse2<FileMeta, FileMetaAdapter> onStarClickListener;
    private Runnable clearAllStarredFolders;
    private Runnable clearAllStarredBooks;
    private ResultResponse<ImageView> onGridOrList;


    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View itemView;

        if (viewType == DISPALY_TYPE_SERIES) {
            return new AuthorsAdapter2().onCreateViewHolder(parent, viewType);
        }

        if (viewType == DISPALY_TYPE_LAYOUT_STARS) {
            itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.layout_stars, parent, false);
            return new StarsLayoutViewHolder(itemView);
        }

        if (viewType == DISPALY_TYPE_LAYOUT_TITLE_FOLDERS) {
            itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.fragment_starred_title_folders, parent, false);
            return new StarsTitleViewHolder(itemView);
        }

        if (viewType == DISPALY_TYPE_LAYOUT_TITLE_BOOKS) {
            itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.fragment_starred_title_books, parent, false);
            return new StarsTitleViewHolder(itemView);
        }
        if (viewType == DISPALY_TYPE_LAYOUT_TITLE_NONE) {
            itemView = new View(parent.getContext());
            return new NoneHolder(itemView);
        }

        if (viewType == DISPLAY_TYPE_DIRECTORY) {
            itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.browse_dir, parent, false);
            return new DirectoryViewHolder(itemView);
        }
        if (viewType == DISPLAY_TYPE_PLAYLIST) {
            itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.browse_dir, parent, false);
            return new DirectoryViewHolder(itemView);
        }

        if (viewType == DISPALY_TYPE_LAYOUT_TAG) {
            itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.browse_tag, parent, false);
            return new TagViewHolder(itemView);
        }

        if (viewType == DISPALY_TYPE_LAYOUT_TITLE_DIVIDER) {
            itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.layout_meta_item_divider, parent, false);
            return new NameDividerViewHolder(itemView);
        }

        if (viewType == DISPLAY_TYPE_FILE) {
            if (adapterType == ADAPTER_LIST || adapterType == ADAPTER_LIST_COMPACT) {
                itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.browse_item_list, parent, false);
            } else if (adapterType == ADAPTER_SHELF) {
                itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.browse_item_shelf, parent, false);
            } else {
                itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.browse_item_grid, parent, false);
                if (tempValue == TEMP_VALUE_STAR_GRID_ITEM || tempValue == TEMP_VALUE_SERIES) {
                    itemView.getLayoutParams().width = ViewGroup.LayoutParams.WRAP_CONTENT;
                    // itemView.getLayoutParams().height = itemView.getLayoutParams().width * 2;
                }
            }
            return new FileMetaViewHolder(itemView);
        }
        return null;
    }

    @Override
    public void onViewRecycled(ViewHolder holderAll) {
        super.onViewRecycled(holderAll);
        if (holderAll instanceof FileMetaViewHolder) {
            final FileMetaViewHolder holder = (FileMetaViewHolder) holderAll;
            //ImageLoader.getInstance().cancelDisplayTask(holder.image);
            //ImageLoader.getInstance().cancelDisplayTask(holder.image);
            //LOG.d("onViewRecycled");
            IMG.clear(holder.image);
        }

    }

    @Override
    public void onBindViewHolder(final RecyclerView.ViewHolder holderAll, final int position) {
        final FileMeta fileMeta = getItem(position);


        if (holderAll instanceof StarsTitleViewHolder) {
            final StarsTitleViewHolder holder = (StarsTitleViewHolder) holderAll;
            if (holder.clearAllFolders != null) {
                TxtUtils.underlineTextView(holder.clearAllFolders).setOnClickListener(new OnClickListener() {

                    @Override
                    public void onClick(View v) {
                        clearAllStarredFolders.run();
                    }
                });
            }

            if (holder.clearAllBooks != null) {
                TxtUtils.underlineTextView(holder.clearAllBooks).setOnClickListener(new OnClickListener() {

                    @Override
                    public void onClick(View v) {
                        clearAllStarredBooks.run();
                    }
                });
            }

            if (holder.onGridList != null) {
                PopupHelper.updateGridOrListIcon(holder.onGridList, AppState.get().starsMode);
                holder.onGridList.setOnClickListener(new OnClickListener() {

                    @Override
                    public void onClick(View v) {
                        onGridOrList.onResultRecive(holder.onGridList);
                    }
                });
            }

            TintUtil.setBackgroundFillColor(holder.parent, TintUtil.color);
        }
        if (holderAll instanceof FileMetaViewHolder) {

            final FileMetaViewHolder holder = (FileMetaViewHolder) holderAll;

            holder.parent.setContentDescription(holder.getString(R.string.book) + " " + fileMeta.getAuthor() + " " + fileMeta.getTitle() + " " + fileMeta.getExt());

            if (!AppState.get().isShowImages && adapterType == ADAPTER_COVERS) {
                adapterType = ADAPTER_GRID;
            }

            LOG.d("bindFileMetaView-1", items.get(position).getTitle());
            bindFileMetaView(holder, position);

            boolean needRefresh = TxtUtils.isEmpty(fileMeta.getPathTxt());

            //if (needRefresh) {
            //FileMetaCore.reUpdateIfNeed(fileMeta);
            // TempHolder.listHash++;
            //AppDB.get().getDao().detach(fileMeta);
            //}

            IMG.getCoverPageWithEffect(holder.image.getContext(), fileMeta.getPath(), bitmap -> {

                try {
                    if (position < items.size() && needRefresh) {
                        FileMeta it = AppDB.get().load(fileMeta.getPath());

                        if (it != null) {
                            items.set(position, it);
                            bindFileMetaView(holder, position);
                        }
                    }
                } catch (Exception e) {
                    LOG.e(e);
                }

            }).into(holder.image);

            holder.imageParent.setVisibility(AppState.get().isShowImages ? View.VISIBLE : View.GONE);

            String path = fileMeta.getPath();
            Clouds.showHideCloudImage(holder.cloudImage, path);

        } else if (holderAll instanceof TagViewHolder) {
            final TagViewHolder holder = (TagViewHolder) holderAll;
            holder.title.setText(fileMeta.getPathTxt());
            TintUtil.setTintImageWithAlpha(holder.image);
            bindItemClickAndLongClickListeners(holder.parent, fileMeta);

            TxtUtils.setInkTextView(holder.title);

            if (AppState.get().appTheme == AppState.THEME_DARK_OLED && tempValue2 != TEMP2_RECENT_FROM_BOOK) {
                holder.parent.setBackgroundColor(Color.BLACK);
            }


        } else if (holderAll instanceof NameDividerViewHolder) {
            final NameDividerViewHolder holder = (NameDividerViewHolder) holderAll;
            holder.title.setText(TxtUtils.formatTitle(fileMeta));
            // bindItemClickAndLongClickListeners(holder.parent, fileMeta);

        } else if (holderAll instanceof DirectoryViewHolder) {
            final DirectoryViewHolder holder = (DirectoryViewHolder) holderAll;
            holder.parent.setContentDescription(holder.getString(R.string.folder) + " " + fileMeta.getTitle());

            holder.play.setVisibility(View.GONE);
            holder.title.setText(fileMeta.getPathTxt());
            holder.path.setText(fileMeta.getPath());

            holder.starIcon.setVisibility(ExtUtils.isExteralSD(fileMeta.getPath()) ? View.GONE : View.VISIBLE);

            holder.imageCloud.setVisibility(View.GONE);

            // My Files root: bigger 书库 folder rows (restored when the same
            // adapter navigates into a subfolder)
            if (holder.defTitleSize < 0) {
                holder.defTitleSize = holder.title.getTextSize();
                holder.defPathSize = holder.path.getTextSize();
                holder.defImageSize = holder.image.getLayoutParams().width;
            }
            if (myFilesRoot) {
                holder.title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
                holder.path.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                int big = Dips.dpToPx(44);
                holder.image.getLayoutParams().width = big;
                holder.image.getLayoutParams().height = big;
            } else {
                holder.title.setTextSize(TypedValue.COMPLEX_UNIT_PX, holder.defTitleSize);
                holder.path.setTextSize(TypedValue.COMPLEX_UNIT_PX, holder.defPathSize);
                holder.image.getLayoutParams().width = holder.defImageSize;
                holder.image.getLayoutParams().height = holder.defImageSize;
            }
            holder.image.requestLayout();

            //holder.imageCloud.setImageResource(R.drawable.glyphicons_sync);
            //TintUtil.setTintImageNoAlpha(holder.imageCloud, TintUtil.color);

            //holder.imageCloud.setVisibility(Clouds.isLibreraSyncFile(fileMeta.getPath()) && !fileMeta.getPath().endsWith(Playlists.L_PLAYLIST) ? View.VISIBLE : View.GONE);

            //Clouds.showHideCloudImage(holder.imageCloud, fileMeta.getPath());


            bindItemClickAndLongClickListeners(holder.parent, fileMeta);

            if (!AppState.get().isBorderAndShadow) {
                holder.parent.setBackgroundColor(Color.TRANSPARENT);
            }

            if (fileMeta.getIsStar() != null && fileMeta.getIsStar()) {
                holder.starIcon.setImageResource(R.drawable.glyphicons_49_star);
            } else {
                holder.starIcon.setImageResource(R.drawable.glyphicons_50_star_empty);
            }

            TintUtil.setNoTintImage(holder.image);

            if (AppState.get().isFolderPreview && fileMeta.getFilesCount() != null && fileMeta.getFilesCount() > 0) {
                holder.count.setVisibility(View.VISIBLE);

                holder.count.setText("" + fileMeta.getFilesCount());
                if (AppState.get().isHideReadBook) {
                    holder.count.setText(fileMeta.getFilesCount() + "/" + fileMeta.getReadCount());
                }
            } else {
                holder.count.setVisibility(View.GONE);
            }

            if (new File(fileMeta.getPath(), "Fonts").isDirectory()) {
                holder.image.setImageDrawable(Apps.getApplicationImage(holder.image.getContext()));
            } else {
                if (AppState.get().isFolderPreview) {
                    IMG.updateImageSizeSmallDir(holder.image);
                    IMG.getCoverPageWithEffect(holder.image.getContext(), fileMeta.getPath(),  new IMG.ResourceReady() {
                        @Override
                        public void onResourceReady(Bitmap bitmap) {
                            try {
                                FileMeta meta = AppDB.get().load(fileMeta.getPath());

                                if (meta != null && position < items.size()) {
                                    items.set(position, meta);
                                    if (AppState.get().isFolderPreview && meta.getFilesCount() != null && meta.getFilesCount() > 0) {
                                        holder.count.setVisibility(View.VISIBLE);
                                        holder.count.setText("" + meta.getFilesCount());
                                        if (AppState.get().isHideReadBook) {
                                            holder.count.setText("" + meta.getFilesCount() + "/" + meta.getReadCount());
                                        }
                                    } else {
                                        holder.count.setVisibility(View.GONE);
                                    }
                                }
                            } catch (Exception e) {
                                LOG.e(e);
                            }


                        }
                    }).into(holder.image);
                } else {
                    holder.image.setImageResource(R.drawable.glyphicons_145_folder_open);
                    TintUtil.setTintImageWithAlpha(holder.image, holder.image.getContext() instanceof MainTabs2 ? TintUtil.getColorInDayNighth() : TintUtil.getColorInDayNighthBook());
                }

            }

            TintUtil.setTintImageWithAlpha(holder.starIcon, holder.starIcon.getContext() instanceof MainTabs2 ? TintUtil.getColorInDayNighth() : TintUtil.getColorInDayNighthBook());


            if (onStarClickListener != null) {
                holder.starIcon.setOnClickListener(new OnClickListener() {

                    @Override
                    public void onClick(View v) {
                        onStarClickListener.onResultRecive(fileMeta, FileMetaAdapter.this);
                    }
                });
            }

            if (adapterType == ADAPTER_GRID || adapterType == ADAPTER_COVERS) {
                // holder.image.setVisibility(View.GONE);
                holder.path.setVisibility(View.GONE);
            } else {
                // holder.image.setVisibility(View.VISIBLE);
                if (tempValue == TEMP_VALUE_FOLDER_PATH) {
                    holder.path.setVisibility(View.VISIBLE);
                } else {
                    holder.path.setVisibility(View.GONE);
                }
            }

            if (fileMeta.getCusType() != null && fileMeta.getCusType() == DISPLAY_TYPE_PLAYLIST) {
                holder.image.setImageResource(R.drawable.glyphicons_160_playlist);
                holder.starIcon.setVisibility(View.GONE);
                holder.path.setVisibility(View.GONE);
                holder.play.setVisibility(View.VISIBLE);
                holder.count.setVisibility(View.VISIBLE);

                holder.play.setText(holder.play.getText().toString().toUpperCase(Locale.US));
                int i1 = fileMeta.getPathTxt().indexOf("(");
                int i2 = fileMeta.getPathTxt().indexOf(")");

                if (i1 > 0 && i2 > i1) {
                    holder.count.setText(fileMeta.getPathTxt().subSequence(i1 + 1, i2));
                    holder.title.setText(fileMeta.getPathTxt().subSequence(0, i1));
                    holder.count.setVisibility(View.VISIBLE);
                } else {
                    holder.count.setVisibility(View.GONE);
                }

                TxtUtils.underlineTextView(holder.play);

                holder.play.setOnClickListener(new OnClickListener() {

                    @Override
                    public void onClick(View v) {
                        onItemLongClickListener.onResultRecive(fileMeta);
                    }
                });
                if (TEMP_VALUE_STAR_GRID_ITEM == tempValue || new File(fileMeta.getPath()).length() == 0) {
                    holder.play.setVisibility(View.GONE);
                }
            }

            if (AppState.get().appTheme == AppState.THEME_DARK_OLED && tempValue2 != TEMP2_RECENT_FROM_BOOK) {
                holder.parent.setBackgroundColor(Color.BLACK);
            }

            TxtUtils.setInkTextView(holder.title, holder.path, holder.play, holder.count);

        } else if (holderAll instanceof StarsLayoutViewHolder) {
            final StarsLayoutViewHolder holder = (StarsLayoutViewHolder) holderAll;
            final FileMetaAdapter adapter = new FileMetaAdapter();
            adapter.setOnItemClickListener(onItemClickListener);
            adapter.setOnItemLongClickListener(onItemLongClickListener);

            adapter.setOnMenuClickListener(onMenuClickListener);
            adapter.setOnStarClickListener(onStarClickListener);

            adapter.setOnAuthorClickListener(onAuthorClickListener);
            adapter.setOnSeriesClickListener(onSeriesClickListener);

            adapter.setAdapterType(FileMetaAdapter.ADAPTER_GRID);
            adapter.tempValue = TEMP_VALUE_STAR_GRID_ITEM;
            adapter.tempValue2 = tempValue2;
            holder.recyclerView.setAdapter(adapter);

            adapter.getItemsList().clear();

            TintUtil.setBackgroundFillColor(holder.panelRecent, TintUtil.color);
            TintUtil.setBackgroundFillColor(holder.panelStars, TintUtil.color);

            List<FileMeta> allStars = AppData.get().getAllFavoriteFiles(false);

            final List<FileMeta> playlists = Playlists.getAllPlaylistsMeta(holder.c);

            final String STARRED = holder.getString(R.string.starred).toUpperCase(Locale.US) + " (" + allStars.size() + ")";

            holder.recentName.setText(holder.getString(R.string.recent) + " (" + (getItemCount() - 1) + ")");
            holder.starredNameIcon.setImageResource(R.drawable.glyphicons_49_star);
            TintUtil.setTintImageNoAlpha(holder.starredNameIcon, Color.WHITE);

            TxtUtils.underlineTextView(holder.starredName);
            holder.starredName.setOnClickListener(new OnClickListener() {

                @Override
                public void onClick(final View v) {
                    MyPopupMenu menu = new MyPopupMenu(v.getContext(), v);

                    menu.getMenu().add(STARRED).setIcon(R.drawable.glyphicons_49_star).setOnMenuItemClickListener(new OnMenuItemClickListener() {

                        @Override
                        public boolean onMenuItemClick(MenuItem item) {
                            AppState.get().recentTag = "";
                            holder.starredNameIcon.setImageResource(R.drawable.glyphicons_49_star);
                            TintUtil.setTintImageNoAlpha(holder.starredNameIcon, Color.WHITE);

                            TxtUtils.underline(holder.starredName, STARRED);

                            adapter.getItemsList().clear();
                            List<FileMeta> allStars = AppData.get().getAllFavoriteFiles(false);
                            adapter.getItemsList().addAll(allStars);
                            adapter.notifyDataSetChanged();

                            return false;
                        }
                    });
                    List<String> tags = AppDB.get().getAll(SEARCH_IN.TAGS);
                    Collections.sort(tags);
                    for (final String tag : tags) {
                        int count = AppDB.get().getAllWithTag(tag).size();
                        final String nameName = tag + " (" + count + ")";
                        menu.getMenu().add(nameName).setIcon(R.drawable.glyphicons_67_tags).setOnMenuItemClickListener(new OnMenuItemClickListener() {

                            @Override
                            public boolean onMenuItemClick(MenuItem item) {
                                AppState.get().recentTag = tag;
                                holder.starredNameIcon.setImageResource(R.drawable.glyphicons_67_tags);
                                TintUtil.setTintImageNoAlpha(holder.starredNameIcon, Color.WHITE);

                                TxtUtils.underline(holder.starredName, nameName);

                                adapter.getItemsList().clear();
                                List<FileMeta> allTags = AppDB.get().searchBy("@tags " + tag, SORT_BY.FILE_NAME, false);
                                adapter.getItemsList().addAll(allTags);
                                adapter.notifyDataSetChanged();

                                return false;
                            }
                        });
                    }

                    final String nameName = holder.getString(R.string.playlists) + " (" + playlists.size() + ")";
                    menu.getMenu().add(nameName).setIcon(R.drawable.glyphicons_160_playlist).setOnMenuItemClickListener(new OnMenuItemClickListener() {

                        @Override
                        public boolean onMenuItemClick(MenuItem item) {
                            AppState.get().recentTag = Playlists.L_PLAYLIST;
                            holder.starredNameIcon.setImageResource(R.drawable.glyphicons_160_playlist);
                            TintUtil.setTintImageNoAlpha(holder.starredNameIcon, Color.WHITE);
                            TxtUtils.underline(holder.starredName, nameName);

                            adapter.getItemsList().clear();
                            adapter.getItemsList().addAll(playlists);
                            adapter.notifyDataSetChanged();

                            return false;
                        }
                    });

                    menu.show();

                }
            });
            adapter.getItemsList().clear();
            if (TxtUtils.isEmpty(AppState.get().recentTag)) {
                holder.starredNameIcon.setImageResource(R.drawable.glyphicons_49_star);
                TintUtil.setTintImageNoAlpha(holder.starredNameIcon, Color.WHITE);

                TxtUtils.underline(holder.starredName, STARRED);
                adapter.getItemsList().addAll(allStars);

            } else if (Playlists.L_PLAYLIST.equals(AppState.get().recentTag)) {
                final String nameName = holder.getString(R.string.playlists) + " (" + playlists.size() + ")";

                holder.starredNameIcon.setImageResource(R.drawable.glyphicons_160_playlist);
                TintUtil.setTintImageNoAlpha(holder.starredNameIcon, Color.WHITE);

                TxtUtils.underline(holder.starredName, nameName);
                adapter.getItemsList().addAll(playlists);

            } else {

                holder.starredNameIcon.setImageResource(R.drawable.glyphicons_67_tags);
                TintUtil.setTintImageNoAlpha(holder.starredNameIcon, Color.WHITE);

                List<FileMeta> allTags = AppDB.get().searchBy("@tags " + AppState.get().recentTag, SORT_BY.FILE_NAME, false);
                adapter.getItemsList().addAll(allTags);

                TxtUtils.underline(holder.starredName, AppState.get().recentTag + " (" + allTags.size() + ")");


            }
            adapter.notifyDataSetChanged();

        } else if (holderAll instanceof AuthorViewHolder) {
            AuthorViewHolder aHolder = (AuthorViewHolder) holderAll;
            final String sequence = fileMeta.getSequence().replace(",", "");
            aHolder.onBindViewHolder(aHolder, sequence);
            aHolder.parent.setOnClickListener(new OnClickListener() {

                @Override
                public void onClick(View v) {
                    if (onSeriesClickListener != null) {
                        onSeriesClickListener.onResultRecive(sequence);
                    }
                }
            });
        }

    }


    private FileMeta bindFileMetaView(final FileMetaViewHolder holder, final int position) {


        if (position >= items.size()) {
            return new FileMeta();
        }
        final FileMeta fileMeta = getItem(position);

        if (fileMeta == null) {
            return new FileMeta();
        }

        holder.title.setText(TxtUtils.formatTitle(fileMeta));

        holder.author.setText(fileMeta.getAuthor());

        if (AppState.get().isUiTextColor) {
            TintUtil.setUITextColor(holder.author, AppState.get().uiTextColor);
            TintUtil.setUITextColor(holder.series, AppState.get().uiTextColor);
        }

        if (TxtUtils.isEmpty(fileMeta.getAuthor())) {
            if (adapterType == ADAPTER_GRID) {
                holder.author.setVisibility(View.INVISIBLE);
            } else {
                holder.author.setVisibility(View.GONE);
            }
        } else {
            holder.author.setVisibility(View.VISIBLE);
        }

        if (holder.series != null && onSeriesClickListener != null) {
            String sequence = fileMeta.getSequence();
            if (TxtUtils.isNotEmpty(sequence)) {
                sequence = sequence.replace(",", "");
                holder.series.setVisibility(View.VISIBLE);
                holder.series.setText(sequence);
                holder.series.setOnClickListener(new OnClickListener() {

                    @Override
                    public void onClick(View v) {
                        if (onSeriesClickListener != null) {
                            onSeriesClickListener.onResultRecive(fileMeta.getSequence().replace(",", ""));
                        }
                    }
                });
            } else {
                holder.series.setText("");
                holder.series.setVisibility(View.GONE);
            }

        }

        holder.author.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                if (onAuthorClickListener != null) {
                    onAuthorClickListener.onResultRecive(fileMeta.getAuthor());
                }

            }
        });

        if (holder.tags != null) {
            if (TxtUtils.isNotEmpty(fileMeta.getTag())) {
                holder.tags.setVisibility(View.VISIBLE);
                holder.tags.removeAllViews();
                for (final String tag : StringDB.asList(fileMeta.getTag())) {
                    TextView t = new TextView(holder.tags.getContext());
                    t.setTextAppearance(R.style.textLink);
                    TxtUtils.bold(t);
                    t.setText(tag + " ");
                    t.setSingleLine();
                    t.setTextSize(12);
                    t.setGravity(Gravity.CENTER_VERTICAL);

                    TypedValue outValue = new TypedValue();
                    holder.tags.getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
                    t.setBackgroundResource(outValue.resourceId);

                    if (AppState.get().isUiTextColor) {
                        TintUtil.setUITextColor(t, AppState.get().uiTextColor);
                    }
                    t.setOnClickListener(new OnClickListener() {

                        @Override
                        public void onClick(View v) {
                            if (onTagClickListner != null) {
                                onTagClickListner.onResultRecive(tag);
                            }

                        }
                    });
                    t.setOnLongClickListener(new OnLongClickListener() {

                        @Override
                        public boolean onLongClick(View v) {

                            Dialogs.showTagsDialog((FragmentActivity) v.getContext(), new File(fileMeta.getPath()), false, new Runnable() {

                                @Override
                                public void run() {
                                    notifyDataSetChanged();
                                }
                            });

                            return true;
                        }
                    });

                    holder.tags.addView(t);
                }

            } else {
                holder.tags.setVisibility(View.GONE);
            }
        }
        if (holder.path != null) {
            if (AppState.get().isDisplayAnnotation) {
                holder.path.setText(fileMeta.getAnnotation());
                holder.path.setSingleLine(false);
                holder.path.setLines(3);
            } else {
                holder.path.setText(fileMeta.getPathTxt());
                holder.path.setSingleLine();
            }
        }

        String bookExt = fileMeta.getChild() != null ? fileMeta.getChild() : fileMeta.getExt();
        holder.browserExt.setText(bookExt);

        if (holder.size != null) {
            if (fileMeta.getPages() != null && fileMeta.getPages() != 0) {
                holder.size.setText(fileMeta.getSizeTxt() + " (" + fileMeta.getPages() + ")");
            } else {
                holder.size.setText(fileMeta.getSizeTxt());

            }
        }
        if (holder.date != null) {
            holder.date.setText(fileMeta.getDateTxt());
        }

        double recentProgress = fileMeta.getIsRecentProgress() == null ? 0 : fileMeta.getIsRecentProgress();


        if (holder.idProgressColor != null && recentProgress > 0f) {
            LOG.d("getIsRecentProgress", recentProgress);
            holder.progresLayout.setVisibility(View.VISIBLE);
            holder.idPercentText.setVisibility(View.VISIBLE);
            holder.idProgressColor.setBackgroundColor(TintUtil.color);
            int width = adapterType == ADAPTER_LIST_COMPACT ? Dips.dpToPx(100) : Dips.dpToPx(200);

            holder.idProgressBg.getLayoutParams().width = width;
            holder.idProgressColor.getLayoutParams().width = (int) Math.round((float) width * recentProgress);
            holder.idProgressColor.setLayoutParams(holder.idProgressColor.getLayoutParams());
            holder.idPercentText.setText("" + Math.round(100f * recentProgress) + "%");

        } else if (holder.progresLayout != null) {
            holder.progresLayout.setVisibility(View.INVISIBLE);
            holder.idPercentText.setVisibility(View.INVISIBLE);
        }

        if (adapterType == ADAPTER_GRID && recentProgress > 0f) {
            holder.idPercentText.setText("" + (int) (100 * recentProgress) + "%");
            if (AppState.get().coverBigSize < IMG.TWO_LINE_COVER_SIZE) {
                holder.browserExt.setVisibility(View.GONE);
            } else {
                holder.browserExt.setVisibility(View.VISIBLE);
            }
        } else if (adapterType == ADAPTER_GRID) {
            holder.idPercentText.setText("");
            holder.browserExt.setVisibility(View.VISIBLE);
        }

        if (adapterType == ADAPTER_GRID && holder.gridProgressLayout != null) {
            if (recentProgress > 0f) {
                holder.gridProgressLayout.setVisibility(View.VISIBLE);
                holder.gridProgressFill.setBackgroundColor(TintUtil.color);
                LinearLayout.LayoutParams fillParams = (LinearLayout.LayoutParams) holder.gridProgressFill.getLayoutParams();
                fillParams.weight = (float) (recentProgress * 100.0);
                holder.gridProgressFill.setLayoutParams(fillParams);
                LinearLayout.LayoutParams restParams = (LinearLayout.LayoutParams) holder.gridProgressRest.getLayoutParams();
                restParams.weight = (float) (100.0 - recentProgress * 100.0);
                holder.gridProgressRest.setLayoutParams(restParams);
            } else {
                holder.gridProgressLayout.setVisibility(View.GONE);
            }
        }


        if (fileMeta.getIsStar() == null || fileMeta.getIsStar() == false) {
            holder.star.setImageResource(R.drawable.glyphicons_50_star_empty);
        } else {
            holder.star.setImageResource(R.drawable.glyphicons_49_star);
        }
        TintUtil.setTintImageWithAlpha(holder.star, holder.parent.getContext() instanceof MainTabs2 ? TintUtil.getColorInDayNighth() : TintUtil.getColorInDayNighthBook());

        if (onStarClickListener != null) {
            holder.star.setContentDescription(holder.c.getString(fileMeta.getIsStar() != null && fileMeta.getIsStar() ? R.string.remove_from_favorites : R.string.add_to_favorites));
            holder.star.setOnClickListener(new OnClickListener() {

                @Override
                public void onClick(View v) {
                    onStarClickListener.onResultRecive(fileMeta, FileMetaAdapter.this);
                }
            });

            holder.star.setOnLongClickListener(new OnLongClickListener() {

                @Override
                public boolean onLongClick(View v) {
                    Dialogs.showTagsDialog((FragmentActivity) v.getContext(), new File(fileMeta.getPath()), false, new Runnable() {

                        @Override
                        public void run() {
                            notifyDataSetChanged();
                        }
                    });
                    return true;
                }
            });
        }
        holder.star.setVisibility(ExtUtils.isExteralSD(fileMeta.getPath()) ? View.GONE : View.VISIBLE);

        if (holder.signIcon != null) {
            holder.signIcon.setOnClickListener(new OnClickListener() {

                @Override
                public void onClick(View v) {
                    Dialogs.showTagsDialog((FragmentActivity) v.getContext(), new File(fileMeta.getPath()), false, new Runnable() {

                        @Override
                        public void run() {
                            TintUtil.setTintImageWithAlpha(holder.signIcon, TxtUtils.isEmpty(fileMeta.getTag()) ? TintUtil.COLOR_TINT_GRAY : TintUtil.color);
                        }
                    });
                }
            });
            TintUtil.setTintImageWithAlpha(holder.signIcon, TxtUtils.isEmpty(fileMeta.getTag()) ? TintUtil.COLOR_TINT_GRAY : TintUtil.color);
        }

        bindItemClickAndLongClickListeners(holder.parent, fileMeta);

        if (adapterType == ADAPTER_GRID || adapterType == ADAPTER_COVERS || adapterType == ADAPTER_SHELF) {
            if (holder.path != null) {
                holder.path.setVisibility(View.GONE);
            }
            if (holder.size != null) {
                holder.size.setVisibility(View.GONE);
            }

            int sizeDP = AppState.get().coverBigSize;
            if (tempValue == TEMP_VALUE_STAR_GRID_ITEM) {
                sizeDP = Math.max(80, AppState.get().coverSmallSize);
            }

            IMG.updateImageSizeBig(holder.imageParent, sizeDP);

            LayoutParams lp = holder.image.getLayoutParams();


            if (AppState.get().isCropBookCovers) {
                lp.width = Dips.dpToPx(sizeDP);
                lp.height = (int) (lp.width * IMG.WIDTH_DK);
            } else {
                lp.width = LayoutParams.WRAP_CONTENT;
                lp.height = LayoutParams.WRAP_CONTENT;
            }

        } else {
            holder.path.setVisibility(View.VISIBLE);
            holder.size.setVisibility(View.VISIBLE);

            IMG.updateImageSizeSmall(holder.imageParent);

            LayoutParams lp = holder.image.getLayoutParams();

            if (AppState.get().isCropBookCovers) {
                lp.width = Dips.dpToPx(AppState.get().coverSmallSize);
                lp.height = (int) (lp.width * IMG.WIDTH_DK);

            } else {
                lp.width = LayoutParams.WRAP_CONTENT;
                lp.height = LayoutParams.WRAP_CONTENT;

            }
        }
        if (adapterType == ADAPTER_SHELF) {
            // Moon+ bookshelf: fixed book slot per column, 1:1.4 book ratio
            int columns = Math.max(3, Dips.screenWidthDP() / 120);
            int slotW = (Dips.screenWidth() - Dips.dpToPx(30)) / columns;
            holder.imageParent.getLayoutParams().width = slotW;
            holder.imageParent.getLayoutParams().height = (int) (slotW * IMG.WIDTH_DK);
            holder.imageParent.setLayoutParams(holder.imageParent.getLayoutParams());
            LayoutParams lp = holder.image.getLayoutParams();
            lp.width = LayoutParams.MATCH_PARENT;
            lp.height = LayoutParams.MATCH_PARENT;
            holder.image.setLayoutParams(lp);
        }
        if (holder.date != null) {
            holder.date.setVisibility(View.VISIBLE);
            holder.size.setVisibility(View.VISIBLE);
            if (adapterType == ADAPTER_LIST_COMPACT) {
                holder.date.setVisibility(View.GONE);
                holder.size.setVisibility(View.GONE);
            }
        }

        if (AppState.get().isBorderAndShadow || !AppState.get().isCropBookCovers) {
            holder.imageParent.setBackgroundColor(Color.TRANSPARENT);
            // LinearLayout.LayoutParams layoutParams = (LinearLayout.LayoutParams)
            // holder.imageParent.getLayoutParams();
            // layoutParams.setMargins(0, 0, 0, 0);
        }

        if (AppState.get().isCropBookCovers) {
            holder.image.setScaleType(ScaleType.CENTER_CROP);
        } else {
            holder.image.setScaleType(ScaleType.FIT_CENTER);
        }

        if (holder.layoutBootom != null) {
            if (adapterType == ADAPTER_COVERS || adapterType == ADAPTER_SHELF) {
                holder.layoutBootom.setVisibility(View.GONE);
                holder.infoLayout.setVisibility(View.GONE);
            } else {
                holder.layoutBootom.setVisibility(View.VISIBLE);
                holder.infoLayout.setVisibility(View.VISIBLE);
            }
        }

        holder.authorParent.setVisibility(View.VISIBLE);

        if (adapterType == ADAPTER_LIST || adapterType == ADAPTER_LIST_COMPACT) {
            if (AppState.get().coverSmallSize >= IMG.TWO_LINE_COVER_SIZE) {
                holder.title.setSingleLine(false);
                holder.title.setLines(2);
                holder.path.setVisibility(View.VISIBLE);
                holder.title.setTextSize(16);
            } else {
                holder.title.setSingleLine(false);
                holder.title.setLines(1);
                holder.title.setTextSize(14);
                //holder.authorParent.setVisibility(View.GONE);
                holder.path.setVisibility(View.GONE);
                holder.infoLayout.setVisibility(View.VISIBLE);
                //holder.title.setText(fileMeta.getPathTxt());
            }
        }

        TintUtil.setTintImageWithAlpha(holder.menu, holder.parent.getContext() instanceof MainTabs2 ? TintUtil.getColorInDayNighth() : TintUtil.getColorInDayNighthBook());

        if (holder.remove != null) {
            holder.remove.setOnClickListener(new OnClickListener() {

                @Override
                public void onClick(View v) {
                    onDeleteClickListener.onResultRecive(fileMeta);
                }
            });

            if (onDeleteClickListener == null) {
                holder.remove.setVisibility(View.GONE);
            }
        }

        holder.menu.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                if (onMenuClickListener != null && fileMeta != null) {
                    onMenuClickListener.onResultRecive(fileMeta);
                }
            }

        });
        holder.parent.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                onItemClickListener.onResultRecive(fileMeta);
            }
        });
        holder.parent.setOnLongClickListener(new OnLongClickListener() {

            @Override
            public boolean onLongClick(View v) {
                if(onItemLongClickListener!=null) {
                    onItemLongClickListener.onResultRecive(fileMeta);
                }
                return true;
            }
        });
        // shelf mode: covers/grid cards go transparent so the wooden bookshelf
        // of the library page shows through; every bind sets it explicitly so
        // recycled holders always match the current mode
        boolean shelfItem = shelfMode && (adapterType == ADAPTER_COVERS || adapterType == ADAPTER_GRID || adapterType == ADAPTER_SHELF);
        if (holder.defaultCardElevation < 0) {
            holder.defaultCardElevation = ((CardView) holder.parent).getCardElevation();
            holder.defaultCardColor = ((CardView) holder.parent).getCardBackgroundColor();
        }
        if (shelfItem) {
            ((CardView) holder.parent).setCardBackgroundColor(Color.TRANSPARENT);
            ((CardView) holder.parent).setCardElevation(0);
        } else {
            ((CardView) holder.parent).setCardBackgroundColor(holder.defaultCardColor);
            ((CardView) holder.parent).setCardElevation(holder.defaultCardElevation);
        }

        // The row background must be re-derived from the model on EVERY bind:
        // a recycled view holder would otherwise keep the selection accent of
        // the previously bound book (phantom selection after entering
        // multi-select). The XML ripple background is restored as the base.
        if (holder.defaultBackground == null) {
            holder.defaultBackground = holder.parent.getBackground();
        }
        boolean selected = selectionPaths != null && fileMeta.getPath() != null
                && selectionPaths.contains(fileMeta.getPath());
        if (selected) {
            holder.parent.setBackgroundColor((TintUtil.color & 0x00FFFFFF) | 0x50000000);
        } else if (!AppState.get().isBorderAndShadow) {
            holder.parent.setBackgroundColor(Color.TRANSPARENT);
        } else if (AppState.get().appTheme == AppState.THEME_DARK_OLED && tempValue2 != TEMP2_RECENT_FROM_BOOK) {
            holder.parent.setBackgroundColor(Color.BLACK);
        } else {
            holder.parent.setBackground(holder.defaultBackground);
        }
        // the translucent row background hides behind opaque covers, so a
        // selected book additionally gets an accent overlay + border ON TOP of
        // its content (card foreground) — visible in every display mode
        if (selected && Build.VERSION.SDK_INT >= 23) {
            if (holder.selectionForeground == null) {
                android.graphics.drawable.GradientDrawable overlay = new android.graphics.drawable.GradientDrawable();
                overlay.setColor((TintUtil.color & 0x00FFFFFF) | 0x38000000);
                overlay.setStroke(Dips.dpToPx(3), TintUtil.color | 0xFF000000);
                holder.selectionForeground = overlay;
            }
            holder.selectionForeground.setAlpha(255);
            ((android.view.ViewGroup) holder.parent).setForeground(holder.selectionForeground);
        } else if (Build.VERSION.SDK_INT >= 23) {
            ((android.view.ViewGroup) holder.parent).setForeground(null);
        }

        if (tempValue == TEMP_VALUE_SERIES) {
            holder.menu.setVisibility(View.GONE);
            holder.star.setVisibility(View.GONE);
            if (holder.tags != null) {
                holder.tags.setVisibility(View.GONE);
            }
            holder.title.setText(TxtUtils.formatTitle(fileMeta));
        }

        if (AppState.get().isShowOnlyOriginalFileNames) {
            if (holder.path != null) {
                holder.path.setVisibility(View.INVISIBLE);
            }
            holder.author.setVisibility(View.GONE);
            if (holder.series != null) {
                holder.series.setVisibility(View.GONE);
            }
        }

        TxtUtils.setInkTextView(holder.title, holder.author, holder.path, holder.browserExt, holder.size, holder.date, holder.series, holder.idPercentText);

        Apps.accessibilityButtonSize(holder.star, holder.menu);

        if (adapterType == ADAPTER_SHELF) {
            // Moon+ bookshelf: circular progress badge on the cover corner;
            // placed after setInkTextView, which would paint the text dark again
            if (holder.shelfBadge != null) {
                int shelfPct = (int) Math.round(100.0 * recentProgress);
                // books never opened (0%) show no badge, like Moon+ Reader
                holder.shelfBadge.setVisibility(shelfPct > 0 ? View.VISIBLE : View.GONE);
                holder.idPercentText.setText("" + shelfPct + "%");
                // finished books stand out in green on the badge
                holder.idPercentText.setTextColor(shelfPct >= 100 ? Color.parseColor("#4CAF50") : Color.WHITE);
            }
            if (holder.shelfStar != null) {
                holder.shelfStar.setVisibility(View.VISIBLE);
                holder.shelfStar.setImageResource(fileMeta.getIsStar() != null && fileMeta.getIsStar()
                        ? R.drawable.glyphicons_49_star : R.drawable.glyphicons_50_star_empty);
                holder.shelfStar.setOnClickListener(new OnClickListener() {

                    @Override
                    public void onClick(View v) {
                        if (onStarClickListener != null) {
                            onStarClickListener.onResultRecive(fileMeta, FileMetaAdapter.this);
                        }
                    }
                });
            }
            if (holder.shelfMenu != null) {
                holder.shelfMenu.setOnClickListener(new OnClickListener() {

                    @Override
                    public void onClick(View v) {
                        if (onMenuClickListener != null && fileMeta != null) {
                            onMenuClickListener.onResultRecive(fileMeta);
                        }
                    }
                });
            }
        }

        // network-book badge on the cover: cloud glyph + cached percent,
        // independent of the adapter type (shelf / grid / list)
        if (holder.remoteBadge != null) {
            if (fileMeta.getPath() != null && com.foobnix.remote.RemoteBook.isRemotePath(fileMeta.getPath())) {
                holder.remoteBadge.setVisibility(View.VISIBLE);
                bindRemotePercent(holder, fileMeta.getPath());
            } else {
                holder.remoteBadge.setVisibility(View.GONE);
            }
        }

        return fileMeta;
    }

    /** remotePath → percent memo (BlockCacheStore reads are disk I/O). */
    private static class RemotePercent {
        final int percent;
        final long at;

        RemotePercent(int percent, long at) {
            this.percent = percent;
            this.at = at;
        }
    }

    private static final long REMOTE_PERCENT_TTL = 30_000L;
    private static final java.util.concurrent.ConcurrentHashMap<String, RemotePercent> REMOTE_PERCENT_CACHE =
            new java.util.concurrent.ConcurrentHashMap<String, RemotePercent>();
    private static final java.util.concurrent.ExecutorService REMOTE_PERCENT_POOL =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "RemoteBadge");
                t.setDaemon(true);
                return t;
            });

    /**
     * Fills the cloud badge with the cached share of the remote book.
     * Computed off the UI thread, memoized briefly so scrolling does not
     * hammer the disk; the result is posted back only when the badge still
     * shows the same book (holder recycling guard via the badge tag).
     */
    private void bindRemotePercent(final FileMetaViewHolder holder, final String remotePath) {
        holder.remoteBadge.setTag(remotePath);
        final long now = System.currentTimeMillis();
        RemotePercent memo = REMOTE_PERCENT_CACHE.get(remotePath);
        if (memo != null && now - memo.at < REMOTE_PERCENT_TTL) {
            applyRemotePercent(holder, remotePath, memo.percent);
            return;
        }
        holder.remoteBadgeText.setText("");
        REMOTE_PERCENT_POOL.execute(new Runnable() {
            @Override
            public void run() {
                final int pct = com.foobnix.remote.BlockCacheStore.cachedPercent(remotePath);
                REMOTE_PERCENT_CACHE.put(remotePath, new RemotePercent(pct, System.currentTimeMillis()));
                holder.remoteBadge.post(new Runnable() {
                    @Override
                    public void run() {
                        applyRemotePercent(holder, remotePath, pct);
                    }
                });
            }
        });
    }

    private void applyRemotePercent(FileMetaViewHolder holder, String remotePath, int pct) {
        if (holder.remoteBadge == null || !remotePath.equals(holder.remoteBadge.getTag())) {
            return; // the holder moved on to another book meanwhile
        }
        holder.remoteBadgeText.setText(pct < 0 ? "" : pct + "%");
    }

    @Override
    public String getSectionText(int position) {
        return " " + (position + 1) + " ";
    }

    @Override
    public int getItemViewType(int position) {
        FileMeta item = getItem(position);
        if (item == null) {
            return DISPLAY_TYPE_FILE;
        }
        Integer cusType = item.getCusType();
        if (cusType == null) {
            return DISPLAY_TYPE_FILE;
        }
        return cusType;
    }

    public void setOnMenuClickListener(ResultResponse<FileMeta> onMenuClickListener) {
        this.onMenuClickListener = onMenuClickListener;
    }

    public void setOnDeleteClickListener(ResultResponse<FileMeta> onDeleteClickListener) {
        this.onDeleteClickListener = onDeleteClickListener;
    }

    public void setOnAuthorClickListener(ResultResponse<String> onAuthorClickListener) {
        this.onAuthorClickListener = onAuthorClickListener;
    }

    public void setOnSeriesClickListener(ResultResponse<String> onSeriesClickListener) {
        this.onSeriesClickListener = onSeriesClickListener;
    }

    public void setAdapterType(int adapterType) {
        this.adapterType = adapterType;
    }

    public void setOnStarClickListener(ResultResponse2<FileMeta, FileMetaAdapter> onStarClickListener) {
        this.onStarClickListener = onStarClickListener;
    }

    public void setClearAllStarredFolders(Runnable clearAllStarredFolders) {
        this.clearAllStarredFolders = clearAllStarredFolders;
    }

    public void setClearAllStarredBooks(Runnable clearAllStarredBooks) {
        this.clearAllStarredBooks = clearAllStarredBooks;
    }

    public void setOnGridOrList(ResultResponse<ImageView> onGridOrList) {
        this.onGridOrList = onGridOrList;
    }

    public void setOnTagClickListner(ResultResponse<String> onTagClickListner) {
        this.onTagClickListner = onTagClickListner;
    }

    public class ContextViewHolder extends RecyclerView.ViewHolder {
        final Context c;
        public View parent;

        public ContextViewHolder(View itemView) {
            super(itemView);
            parent = itemView;
            c = itemView.getContext();
        }

        public String getString(int resId) {
            return c.getString(resId);
        }

    }

    public class FileMetaViewHolder extends ContextViewHolder {
        public TextView title, author, path, browserExt, size, date, series, idPercentText;
        public LinearLayout tags;
        public ImageView image, star, signIcon, menu, cloudImage;
        public View authorParent, progresLayout, parent, remove, layoutBootom, infoLayout, idProgressColor, idProgressBg, imageParent;
        public View gridProgressLayout, gridProgressFill, gridProgressRest;
        // Moon+ bookshelf extras (present only in browse_item_shelf.xml)
        public View shelfBadge, shelfMenu;
        public ImageView shelfStar;
        // network-book badge (cloud + cached percent), present in all three
        // book layouts; the badge view also carries the bound remote:// path
        // as its tag so async percent updates never leak into recycled rows
        public View remoteBadge;
        public TextView remoteBadgeText;
        // XML ripple background captured on first bind, so the recycled row
        // can always be restored to it (selection accent must not leak)
        public android.graphics.drawable.Drawable defaultBackground;
        // original card look, so shelf mode (transparent cards on wood) can
        // be undone when the same holder is reused in a list mode
        public float defaultCardElevation = -1;
        public android.content.res.ColorStateList defaultCardColor;
        // accent overlay drawn over the cover while the book is multi-selected
        public android.graphics.drawable.Drawable selectionForeground;

        public FileMetaViewHolder(View view) {
            super(view);
            title = (TextView) view.findViewById(R.id.title1);
            author = (TextView) view.findViewById(R.id.title2);
            authorParent = view.findViewById(R.id.title2Parent);
            path = (TextView) view.findViewById(R.id.browserPath);
            tags = (LinearLayout) view.findViewById(R.id.browserTags);
            size = (TextView) view.findViewById(R.id.browserSize);
            browserExt = (TextView) view.findViewById(R.id.browserExt);
            date = (TextView) view.findViewById(R.id.browseDate);
            series = (TextView) view.findViewById(R.id.series);
            idPercentText = (TextView) view.findViewById(R.id.idPercentText);

            image = (ImageView) view.findViewById(R.id.browserItemIcon);
            cloudImage = (ImageView) view.findViewById(R.id.cloudImage);
            star = (ImageView) view.findViewById(R.id.starIcon);
            //signIcon = (ImageView) view.findViewById(R.id.signIcon);
            idProgressColor = view.findViewById(R.id.idProgressColor);
            idProgressBg = view.findViewById(R.id.idProgressBg);
            infoLayout = view.findViewById(R.id.infoLayout);
            imageParent = view.findViewById(R.id.imageParent);

            progresLayout = view.findViewById(R.id.progresLayout);
            layoutBootom = view.findViewById(R.id.layoutBootom);

            gridProgressLayout = view.findViewById(R.id.gridProgressLayout);
            gridProgressFill = view.findViewById(R.id.gridProgressFill);
            gridProgressRest = view.findViewById(R.id.gridProgressRest);
            shelfBadge = view.findViewById(R.id.shelfBadge);
            shelfMenu = view.findViewById(R.id.shelfMenu);
            shelfStar = (ImageView) view.findViewById(R.id.shelfStar);
            remoteBadge = view.findViewById(R.id.remoteBadge);
            remoteBadgeText = (TextView) view.findViewById(R.id.remoteBadgeText);

            menu = (ImageView) view.findViewById(R.id.itemMenu);
            remove = view.findViewById(R.id.delete);

            parent = view;


        }
    }

    /** Library page shelf mode: covers/grid rows sit transparent on the wood. */
    public boolean shelfMode = false;

    /** My Files root: the 书库 folder rows render bigger. */
    public boolean myFilesRoot = false;

    public class DirectoryViewHolder extends ContextViewHolder {
        public TextView title, path, play, count;
        public ImageView image, starIcon, imageCloud;
        public View parent;
        // XML defaults captured on first bind, so the enlarged My-Files-root
        // rendering can be undone when the same adapter navigates deeper
        public float defTitleSize = -1, defPathSize = -1;
        public int defImageSize = -1;

        public DirectoryViewHolder(View view) {
            super(view);
            title = (TextView) view.findViewById(R.id.text1);
            count = (TextView) view.findViewById(R.id.count);
            play = (TextView) view.findViewById(R.id.play);
            path = (TextView) view.findViewById(R.id.text2);
            image = (ImageView) view.findViewById(R.id.image1);
            starIcon = (ImageView) view.findViewById(R.id.starIcon);
            imageCloud = (ImageView) view.findViewById(R.id.imageCloud);
            parent = view;
        }
    }

    public class TagViewHolder extends ContextViewHolder {
        public TextView title;
        public ImageView image;
        public View parent;

        public TagViewHolder(View view) {
            super(view);
            title = (TextView) view.findViewById(R.id.text1);
            image = (ImageView) view.findViewById(R.id.image1);
            parent = view;
        }
    }

    public class NameDividerViewHolder extends ContextViewHolder {
        public TextView title;
        public ImageView image;
        public View parent;

        public NameDividerViewHolder(View view) {
            super(view);
            title = (TextView) view.findViewById(R.id.text1);
            image = (ImageView) view.findViewById(R.id.image1);
            parent = view;
        }
    }

    public class StarsLayoutViewHolder extends ContextViewHolder {
        public RecyclerView recyclerView;
        public TextView clearAllRecent, clearAllStars, starredName, recentName;
        public View panelStars, panelRecent;
        public ImageView starredNameIcon;

        public StarsLayoutViewHolder(View view) {
            super(view);
            recyclerView = (RecyclerView) view.findViewById(R.id.recyclerViewStars);
            starredName = (TextView) view.findViewById(R.id.starredName);
            starredNameIcon = (ImageView) view.findViewById(R.id.starredNameIcon);
            recentName = (TextView) view.findViewById(R.id.recentName);
            panelStars = view.findViewById(R.id.panelStars);
            panelRecent = view.findViewById(R.id.panelRecent);
        }
    }

    public class StarsTitleViewHolder extends ContextViewHolder {
        public TextView clearAllFolders, clearAllBooks;
        public View parent;
        public ImageView onGridList;

        public StarsTitleViewHolder(View view) {
            super(view);
            clearAllFolders = (TextView) view.findViewById(R.id.clearAllFolders);
            clearAllBooks = (TextView) view.findViewById(R.id.clearAllBooks);
            onGridList = (ImageView) view.findViewById(R.id.onGridList);
            parent = view.findViewById(R.id.parent);
        }
    }

    public class NoneHolder extends ContextViewHolder {

        public NoneHolder(View view) {
            super(view);
        }
    }

}