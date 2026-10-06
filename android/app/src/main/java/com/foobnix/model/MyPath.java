package com.foobnix.model;

import android.os.Environment;

import com.foobnix.pdf.info.ExtUtils;

import java.io.File;

public class MyPath {

    final public static String INTERNAL_ROOT = Environment.getExternalStorageDirectory().getPath();
    final public static String INTERNAL_PREFIX = "internal-storage:";

    private String path;

    public static MyPath InternalStorate() {
        return new MyPath(Environment.getExternalStorageDirectory());
    }


    public MyPath(File file) {
        this(file.getPath());
    }

    public MyPath(String path) {
        this.path = toRelative(path);
    }

    public String getPath() {
        return toAbsolute(path);
    }

    public String getPathRelative() {
        return path;
    }

    public static String toRelative(String path) {
        if (path == null) {
            return path;
        }
        return path.replace(INTERNAL_ROOT, INTERNAL_PREFIX);
    }

    public static String toAbsolute(String path) {
        if (path == null) {
            return path;
        }
        return path.replace(INTERNAL_PREFIX, INTERNAL_ROOT);
    }

    /** 任意形态的存储路径统一为书架原生相对形态 internal-storage:/...：
     *  /storage/emulated/0/、/sdcard/ 与 internal-storage: 是同一卷的三种引用，
     *  统一后同一物理文件在数据库/书架只占一行。其余形态原样返回。 */
    public static String canonicalize(String path) {
        if (path == null) {
            return path;
        }
        String p = path;
        if (p.startsWith(INTERNAL_PREFIX)) {
            // internal-storage:/x 是 DB/同步层的虚拟形态，File 层不可直接使用，
            // 统一还原为真实存在的 /sdcard/ 绝对路径
            p = "/sdcard" + p.substring(INTERNAL_PREFIX.length());
        } else if (p.startsWith("/storage/emulated/0/")) {
            p = "/sdcard/" + p.substring("/storage/emulated/0/".length());
        } else if (p.startsWith("/storage/self/primary/")) {
            p = "/sdcard/" + p.substring("/storage/self/primary/".length());
        }
        // intent/URI 来源的路径可能带 URL 编码（%20、%E5...），解码后再比较
        if (p.indexOf('%') >= 0) {
            try {
                final String dec = android.net.Uri.decode(p);
                if (dec != null && dec.length() > 0) {
                    p = dec;
                }
            } catch (final Throwable t) {
                // keep undecoded
            }
        }
        return p;
    }

    public static String getSyncPath(String path) {
        if (path == null) {
            return null;
        }
        final File syncBook = new File(AppProfile.SYNC_FOLDER_BOOKS, ExtUtils.getFileName(path));
        return syncBook.isFile() ? syncBook.getPath() : path;
    }


    public interface RelativePath {
        String getPath();

        void setPath(String path);
    }
}
