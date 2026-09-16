package com.foobnix.android.utils;

import com.foobnix.mobi.parser.IOUtils;

import com.foobnix.LibreraApp;
import com.foobnix.pdf.info.AppsConfig;

import org.librera.JSONArray;
import org.librera.JSONException;
import org.librera.LinkedJSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.concurrent.ConcurrentHashMap;

public class IO {

    // must be thread-safe: getLock() is called from the UI thread, the sync
    // worker and TTS/binder threads concurrently; a plain HashMap could hand
    // out different monitors for the same file and silently break exclusion
    static final ConcurrentHashMap<Integer, Object> locks = new ConcurrentHashMap<Integer, Object>();
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static Object getLock(File file) {
        return locks.computeIfAbsent(file.hashCode(), k -> new Object());
    }


    public static void writeObj(File file, Object o) {
        //new Thread(() -> writeObjAsync(file, o), "@T writeObj").start();
        AppsConfig.executorServiceSingle.execute(() -> writeObjSync(file, o));
    }

    public static void writeObjSync(File file, Object o) {
        LOG.d("writeObjAsync", file.getPath());
        if (o instanceof LinkedJSONObject || o instanceof JSONArray) {
            LOG.d("writeObjAsync", "LinkedJSONObject");
            IO.writeString(file, o.toString());
        } else if (o instanceof String) {
            LOG.d("writeObjAsync", "String");
            IO.writeString(file, (String) o);
        } else {
            //LOG.d("writeObjAsync", "Class", o.getClass().getName());
            IO.writeString(file, Objects.toJSONString(o));
        }
    }

    /**
     * Blocks (up to 10 s) until every write queued through {@link #writeObj}
     * has been flushed to disk. Config-change restarts (day/night toggle,
     * theme, language, font size) re-read the JSON config synchronously in
     * the freshly started activity: without this drain the restart can race
     * the async writer and bring the PREVIOUS values back on screen
     * (e.g. the sidebar night-mode toggle sometimes "not applying").
     */
    public static void awaitWrites() {
        try {
            java.util.concurrent.Future<?> f = AppsConfig.executorServiceSingle.submit((Runnable) () -> {
            });
            f.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    public static void readObj(File file, Object o) {

        try {
            if (!file.exists()) {
                LOG.d("readObj not exsits", file.getPath());
                return;
            }

            Objects.loadFromJson(o, readString(file));

        } catch (Exception e) {
            LOG.e(e);
        }

    }


    public static LinkedJSONObject readJsonObject(File file) {

        final String s = readString(file);
        if (TxtUtils.isEmpty(s)) {
            return new LinkedJSONObject();
        }

        try {
            return new LinkedJSONObject(s);
        } catch (JSONException e) {
            // corrupted content (e.g. a write interrupted by process death):
            // keep the broken text for diagnosis instead of letting the next
            // save silently overwrite it — returning an empty object here is
            // what turns one truncated file into full data loss downstream
            LOG.e(e, "corrupt JSON in " + file.getPath());
            try {
                final File bad = new File(file.getParentFile(), file.getName() + ".corrupt");
                writeString(bad, s);
            } catch (Exception ex) {
                LOG.e(ex);
            }
            return new LinkedJSONObject();
        }

    }

    /**
     * Single-slot content cache published as ONE immutable path+content pair.
     * The previous two volatile fields were updated in two separate steps and
     * could interleave between concurrent writers of different files, so a
     * reader got the NEW content under the OLD file name — one list (recent)
     * read the other's (favorite) content and wrote it back, destroying data.
     */
    private static volatile String[] cachePair = new String[]{null, null};

    private static void invalidateCache(String path) {
        final String[] cached = cachePair;
        if (path.equals(cached[0])) {
            cachePair = new String[]{null, null};
        }
    }

    public static String readString(File file) {
        return readString(file, false);
    }

    public static String readStringFromAsset(String assetName) throws IOException {
        InputStream open = LibreraApp.context.getAssets().open(assetName);
        return readString(open);
    }

    public static String readString(InputStream open) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        IOUtils.copy(open, out);
        return out.toString().trim();
    }


    public static String readString(File file, boolean withSeparator) {
        // the flag changes the returned content, so it must be part of the
        // cache key — otherwise a plain read poisons the text editor (all
        // lines joined) and the editor read poisons plain consumers
        final String path = file.getPath() + (withSeparator ? "#sep" : "");
        final String[] cached = cachePair;
        if (path.equals(cached[0])) {
            LOG.d("lib-IO", "read cache", file);
            return cached[1];
        }
        synchronized (getLock(file)) {

            String content;
            try {
                if (!file.exists()) {
                    content = "";
                } else {
                    LOG.d("lib-IO", "read file", file);
                    StringBuilder builder = new StringBuilder();
                    String aux = "";
                    BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), UTF8));
                    String separator = System.getProperty("line.separator");

                    while ((aux = reader.readLine()) != null) {
                        builder.append(aux);
                        if (withSeparator) {
                            builder.append(separator);
                        }
                    }
                    reader.close();
                    content = builder.toString();
                }
            } catch (Exception e) {
                LOG.e(e);
                content = "";
            }
            cachePair = new String[]{path, content};
            return content;
        }
    }

    public static boolean writeString(File file, String string) {

        synchronized (getLock(file)) {

            OutputStream out = null;
            File tmp = null;
            try {
                if (string == null) {
                    string = "";
                }
                LOG.d("lib-IO", "write file", file);
                new File(file.getParent()).mkdirs();

                // atomic replace: a crash or power loss mid-write must never
                // leave a truncated config/progress file behind (a truncated
                // JSON reads back as an empty object → full data loss)
                tmp = new File(file.getParentFile(), file.getName() + ".tmp");
                out = new BufferedOutputStream(new FileOutputStream(tmp));
                out.write(string.getBytes(UTF8));
                out.flush();
                out.close();
                out = null;

                if (tmp.renameTo(file)) {
                    cachePair = new String[]{file.getPath(), string};
                } else {
                    // same-volume rename can only fail if the target cannot be
                    // replaced; fall back to an in-place overwrite
                    LOG.e(new IOException("rename failed, fallback write " + file.getPath()));
                    out = new BufferedOutputStream(new FileOutputStream(file));
                    out.write(string.getBytes(UTF8));
                    out.flush();
                    out.close();
                    out = null;
                    tmp.delete();
                    cachePair = new String[]{file.getPath(), string};
                }

            } catch (Exception e) {
                LOG.e(e);
                if (tmp != null) {
                    tmp.delete();
                }
                return false;
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException e) {
                        LOG.e(e);
                    }
                }
            }
            return true;
        }
    }

    public static boolean copyFile(File from, File to) {
        try {
            new File(to.getParent()).mkdirs();

            InputStream input = new BufferedInputStream(new FileInputStream(from));
            OutputStream output = new BufferedOutputStream(new FileOutputStream(to));

            IOUtils.copyClose(input, output);

            invalidateCache(to.getPath());
            LOG.d("Copy file form to", from, to);
        } catch (IOException e) {
            LOG.e(e);
            return false;
        }
        return true;
    }

    public static boolean copyFile(InputStream from, File to) {
        try {
            new File(to.getParent()).mkdirs();

            InputStream input = new BufferedInputStream(from);
            OutputStream output = new BufferedOutputStream(new FileOutputStream(to));

            IOUtils.copyClose(input, output);

            invalidateCache(to.getPath());
            LOG.d("Copy file form to", from, to);
        } catch (IOException e) {
            LOG.e(e);
            return false;
        }
        return true;
    }
}
