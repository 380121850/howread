package com.foobnix.webdav;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.foobnix.android.utils.ResultResponse;
import com.foobnix.pdf.info.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Recycler adapter for the WebDAV list (servers at the root view, directories
 * and files when browsing). Self contained - no OPDS classes involved.
 */
public class WebDavAdapter extends RecyclerView.Adapter<WebDavAdapter.Holder> {

    private List<WebDavItem> items = new ArrayList<WebDavItem>();
    private ResultResponse<WebDavItem> onClick;
    private ResultResponse<WebDavItem> onLongClick;
    private ResultResponse<WebDavItem> onRemove;

    public void setItems(List<WebDavItem> items) {
        this.items = items;
        notifyDataSetChanged();
    }

    public void setOnClick(ResultResponse<WebDavItem> onClick) {
        this.onClick = onClick;
    }

    public void setOnLongClick(ResultResponse<WebDavItem> onLongClick) {
        this.onLongClick = onLongClick;
    }

    public void setOnRemove(ResultResponse<WebDavItem> onRemove) {
        this.onRemove = onRemove;
    }

    @Override
    public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.webdav_item, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(final Holder h, int position) {
        final WebDavItem item = items.get(position);
        h.title.setText(item.name);
        if (item.isServer) {
            h.subtitle.setText(item.href);
            h.icon.setImageResource(R.drawable.my_nas_webdav);
            h.remove.setVisibility(View.VISIBLE);
        } else if (item.isDir) {
            h.subtitle.setText("");
            h.icon.setImageResource(R.drawable.glyphicons_145_folder_open);
            h.remove.setVisibility(View.GONE);
        } else {
            h.subtitle.setText(sizeText(item.size));
            h.icon.setImageResource(R.drawable.glyphicons_72_book);
            h.remove.setVisibility(View.GONE);
        }
        h.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (onClick != null) {
                    onClick.onResultRecive(item);
                }
            }
        });
        h.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                return onLongClick != null && onLongClick.onResultRecive(item);
            }
        });
        h.remove.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (onRemove != null) {
                    onRemove.onResultRecive(item);
                }
            }
        });
    }

    private static String sizeText(long size) {
        if (size < 0) {
            return "";
        }
        if (size < 1024) {
            return size + " B";
        }
        if (size < 1024 * 1024) {
            return (size / 1024) + " KB";
        }
        return String.format("%.1f MB", size / (1024.0 * 1024.0));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public static class Holder extends RecyclerView.ViewHolder {
        TextView title, subtitle;
        ImageView icon, remove;

        Holder(View v) {
            super(v);
            title = (TextView) v.findViewById(R.id.title);
            subtitle = (TextView) v.findViewById(R.id.subtitle);
            icon = (ImageView) v.findViewById(R.id.icon);
            remove = (ImageView) v.findViewById(R.id.remove);
        }
    }
}
