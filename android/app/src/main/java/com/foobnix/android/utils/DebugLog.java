package com.foobnix.android.utils;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Environment;

import com.foobnix.model.AppProfile;
import com.foobnix.model.AppState;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 常规设置"调试日志"开关的后端：打开后 LOG.* 与 BENCH/REMOTE 定位日志同步写入
 * <存储根>/debug-log.txt（超过 2MB 自动重建，保留最近一段），供"导出调试日志"
 * 拷到 Download 分享/取证。debug 构建默认打开、release 默认关闭；用户手动改过
 * （isDebugLogEnabledUserSet）后以用户为准，不再随构建类型回切。
 */
public class DebugLog {
    public static volatile boolean enabled = false;
    private static final Object LOCK = new Object();
    private static final long MAX_BYTES = 2 * 1024 * 1024;
    private static volatile File file;

    /** Application onCreate：先按构建类型给默认（用户改过则以保存值为准）。 */
    public static void init(final Context c) {
        try {
            final AppState st = AppState.get();
            if (!st.isDebugLogEnabledUserSet) {
                st.isDebugLogEnabled = isDebugBuild(c);
            }
            enabled = st.isDebugLogEnabled;
        } catch (final Throwable t) {
            enabled = isDebugBuild(c);
        }
        synchronized (LOCK) {
            resolve(c);
        }
    }

    /** Profile/State 就绪后（每次 Activity attach 都会走到）以保存值收敛。 */
    public static void syncFromState() {
        try {
            enabled = AppState.get().isDebugLogEnabled;
        } catch (final Throwable ignored) {
        }
        // Profile 就绪后把日志文件迁到存储根（启动早期根目录未就绪时会先落在
        // 外部缓存目录）：rename 迁移，保留已捕获内容
        synchronized (LOCK) {
            try {
                if (AppProfile.SYNC_FOLDER_ROOT == null) {
                    return;
                }
                final File target = new File(AppProfile.SYNC_FOLDER_ROOT, "debug-log.txt");
                if (file == null) {
                    file = target;
                } else if (!file.equals(target)) {
                    if (file.isFile() && !target.exists()) {
                        file.renameTo(target);
                    }
                    file = target;
                }
            } catch (final Throwable ignored) {
            }
        }
    }

    public static void setEnabled(final boolean on) {
        enabled = on;
    }

    /** debug 包 = FLAG_DEBUGGABLE（运行时判定，无需构建脚本改动）。 */
    public static boolean isDebugBuild(final Context c) {
        try {
            return (c.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        } catch (final Throwable t) {
            return false;
        }
    }

    private static File resolve(final Context c) {
        if (file != null) {
            return file;
        }
        try {
            File root = AppProfile.SYNC_FOLDER_ROOT;
            if (root == null && c != null) {
                root = c.getExternalFilesDir(null);
            }
            if (root != null) {
                file = new File(root, "debug-log.txt");
            }
        } catch (final Throwable ignored) {
        }
        return file;
    }

    /** 纯生命周期噪音标签（状态加载/上下文包装，每次开屏数百行）：不落盘，
     *  否则会淹没现场信号并让日志体积膨胀到文本分享被截断。logcat 不受影响。 */
    private static boolean isNoise(final String tag) {
        return tag != null && (tag.startsWith("Objects") || tag.startsWith("lib-IO")
                || tag.startsWith("Context-SF"));
    }

    public static void write(final String tag, final String msg) {
        if (!enabled || isNoise(tag)) {
            return;
        }
        synchronized (LOCK) {
            try {
                File f = file;
                if (f == null) {
                    f = resolve(null);
                }
                if (f == null) {
                    return;
                }
                if (f.length() > MAX_BYTES) {
                    f.delete();
                }
                final String ts = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
                final FileWriter fw = new FileWriter(f, true);
                fw.write(ts + " " + tag + ": " + msg + "\n");
                fw.flush();
                fw.close();
            } catch (final Throwable ignored) {
            }
        }
    }

    /** 导出：拷到 Download/HowRead-debug-<时间>.log；无内容返回 null。 */
    public static File exportTo(final Context c) {
        synchronized (LOCK) {
            try {
                final File f = resolve(c);
                if (f == null || !f.isFile() || f.length() == 0) {
                    return null;
                }
                final File out = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS),
                        "HowRead-debug-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".log");
                final InputStream in = new FileInputStream(f);
                final OutputStream os = new FileOutputStream(out);
                try {
                    final byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                    }
                } finally {
                    in.close();
                    os.close();
                }
                return out;
            } catch (final Throwable t) {
                return null;
            }
        }
    }

    /** 当前日志文件（供导出到用户选择的目录）；内容是否为空由调用方判断。 */
    public static File logFile(final Context c) {
        synchronized (LOCK) {
            return resolve(c);
        }
    }

    /** 清零：删除日志文件重新开始，避免历史日志干扰现场分析。 */
    public static void clear() {
        synchronized (LOCK) {
            try {
                if (file != null && file.isFile()) {
                    file.delete();
                }
                file = null;
            } catch (final Throwable ignored) {
            }
        }
    }
}
