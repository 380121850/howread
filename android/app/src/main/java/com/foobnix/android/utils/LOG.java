package com.foobnix.android.utils;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.foobnix.model.AppProfile;
import com.foobnix.pdf.info.AppsConfig;
import com.foobnix.pdf.info.R;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

public class LOG {
    public static String TAG = "DEBUG";
    public static String DELIMITER = "|";

    /** crash.txt 的写入锁：多线程同时 LOG.e 时追加写会互相交错损坏 */
    private static final Object CRASH_LOCK = new Object();

    public static boolean writeCrashTofile = false;

    public static String toString(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    public static void d(Object msg1, Object... statement) {
        // LOG.d 不再写入 debug-log.txt：状态加载/界面生命周期的 d 级日志每次
        // 开屏数百行，会把现场复现段挤出 256KB 传输窗口。文件只保留
        // ERR/WARN/INFO + BENCH/REMOTE 定位日志；d 级仍进 logcat（开关打开时）。
        if (AppsConfig.IS_LOG || DebugLog.enabled) {
            if (statement.length == 0) {
                Log.d(TAG, msg1.toString());
                return;
            }
            String msg = asString(statement);
            if (msg != null && msg.length() > 4000) {
                Log.d(msg1 + "[part1]", msg.substring(0, 4000));
                Log.d(msg1 + "[part2]", msg.substring(4000));
            } else {
                Log.d(msg1.toString(), msg);
            }
        }
    }

    public static void dMeta(Object... statement) {
        String meta = null;
        StackTraceElement[] stackTrace = Thread.currentThread()
                                               .getStackTrace();
        if (stackTrace.length > 3) {
            meta = asString(stackTrace[3].getClassName(), stackTrace[3].getMethodName(), stackTrace[3].getLineNumber());
        }

        d(meta, asString(statement));

    }

    public static void e(Throwable e, Object... statement) {
        e(e, false, statement);
    }

    public static void uncaughtException(Throwable e, Object... statement) {
        e(e, true, statement);
    }

    private static void e(Throwable e, Boolean uncaughtException, Object... statement) {

        if (DebugLog.enabled) {
            DebugLog.write("ERR", asString(statement) + (e == null ? "" : "\n" + toString(e)));
        }
        if (AppsConfig.IS_LOG || DebugLog.enabled) {
            String string = asString(statement);
            Log.e(TAG, string, e);
//            new Handler(Looper.getMainLooper()).post(() -> {
//                throw new RuntimeException(string, e);
//            });
        }
        if (writeCrashTofile) {
            synchronized (CRASH_LOCK) {
                try {
                    java.io.File crashFile = new File(AppProfile.SYNC_FOLDER_ROOT, "crash.txt");
                    // 防膨胀：历史异常记录超过 1MB 直接重建，保留最近一段即可
                    if (crashFile.length() > 1024 * 1024) {
                        crashFile.delete();
                    }
                    FileWriter fw = new FileWriter(crashFile, true);
                    if (uncaughtException) {
                        fw.write("\n ======== uncaughtException =========== \n");
                    }
                    fw.write(toString(e));
                    fw.write("\n =================== \n");

                    fw.flush();
                    fw.close();
                } catch (Exception e1) {
                    Log.e(TAG,asString(statement),e1);
                }
            }
        }
    }

    public static void w(Throwable e, Object... statement) {
        if (DebugLog.enabled) {
            DebugLog.write("WARN", asString(statement) + (e == null ? "" : " " + toString(e)));
        }
        if (AppsConfig.IS_LOG || DebugLog.enabled) {
            Log.w(TAG, asString(statement), e);
        }
    }

    public static void i(Throwable e, Object... statement) {
        if (DebugLog.enabled) {
            DebugLog.write("INFO", asString(statement) + (e == null ? "" : " " + toString(e)));
        }
        if (AppsConfig.IS_LOG || DebugLog.enabled) {
            Log.i(TAG, asString(statement), e);
        }
    }

    /** BENCH 定位日志：logcat 照常输出；调试日志开关打开时同步写入 debug-log.txt。 */
    public static void bench(Object msg) {
        if (DebugLog.enabled) {
            DebugLog.write("BENCH", String.valueOf(msg));
        }
        Log.i("BENCH", String.valueOf(msg));
    }

    public static void benchW(Object msg, Throwable t) {
        if (DebugLog.enabled) {
            DebugLog.write("BENCH", String.valueOf(msg) + "\n" + (t == null ? "" : toString(t)));
        }
        Log.w("BENCH", String.valueOf(msg), t);
    }

    public static void bench(Object msg, Throwable t) {
        if (DebugLog.enabled) {
            DebugLog.write("BENCH", String.valueOf(msg) + "\n" + (t == null ? "" : toString(t)));
        }
        Log.i("BENCH", String.valueOf(msg), t);
    }

    public static void remote(Object msg, Throwable t) {
        if (DebugLog.enabled) {
            DebugLog.write("REMOTE", String.valueOf(msg) + "\n" + (t == null ? "" : toString(t)));
        }
        Log.i("REMOTE", String.valueOf(msg), t);
    }

    public static void remote(Object msg) {
        if (DebugLog.enabled) {
            DebugLog.write("REMOTE", String.valueOf(msg));
        }
        Log.i("REMOTE", String.valueOf(msg));
    }

    private static String asString(Object... statements) {
        return TxtUtils.join(DELIMITER, statements) + "|";
    }

    public static String ojectAsString(Object obj) {
        if (!AppsConfig.IS_LOG) {
            return null;
        }
        StringBuffer out = new StringBuffer();

        out.append("======== [ Begin ] ======== \n");
        for (Field f : obj.getClass()
                          .getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || Modifier.isTransient(f.getModifiers())) {
                continue;
            }
            f.setAccessible(true);
            out.append(f.getName());
            out.append(":");
            try {
                Object v = f.get(obj);
                if (v == null) {
                    out.append("@null");
                } else {
                    out.append(v);
                }
                out.append("|\n");
            } catch (Exception e) {
                LOG.e(e);
            }
        }
        out.append("======== [ End ] ========");
        return out.toString();
    }

}
