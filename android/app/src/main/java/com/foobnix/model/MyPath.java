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
        if (path.startsWith(INTERNAL_PREFIX)) {
            return path;
        }
        String abs = path;
        if (abs.startsWith("/sdcard/")) {
            abs = INTERNAL_ROOT + abs.substring("/sdcard".length());
        }
        if (abs.startsWith(INTERNAL_ROOT + "/")) {
            return toRelative(abs);
        }
        return path;
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
