package org.ebookdroid.common.settings;

import com.foobnix.android.utils.LOG;
import com.foobnix.model.AppBook;

import org.ebookdroid.common.settings.books.SharedBooks;
import org.ebookdroid.common.settings.listeners.IBookSettingsChangeListener;
import org.emdev.utils.listeners.ListenerProxy;

import java.util.concurrent.locks.ReentrantReadWriteLock;

public class SettingsManager {

    static final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile static AppBook current;
    static ListenerProxy listeners = new ListenerProxy(IBookSettingsChangeListener.class);


    public static AppBook getBookSettings(final String fileName) {
        lock.writeLock().lock();
        try {

            current = SharedBooks.load(fileName);
            LOG.d("load-getBookSettings", current.p, fileName);
            return current;
        } catch (Exception e) {
            // 加载失败（进度文件被异常退出写坏等）不能回落到 current——那是
            // 另一本书的设置，错误的 pg/zoom/split 会让这本书按错误参数打开。
            // 返回全新默认设置，等价于"无进度"打开。
            LOG.e(e, "getBookSettings load failed, fallback to fresh", fileName);
            current = new AppBook(fileName);
            return current;
        } finally {
            lock.writeLock().unlock();
        }
    }


    public static AppBook getBookSettings() {
        lock.readLock().lock();
        try {
            // LOG.d("load current");
            return current;
        } finally {
            lock.readLock().unlock();
        }
    }




    public static void zoomChanged(final float zoom, final boolean committed) {
        lock.readLock().lock();
        try {
            if (current != null) {
                LOG.d("zoom-chaged", zoom);
                current.setZoom(zoom);
                SharedBooks.save(current);
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    public static void positionChanged(final float offsetX, final float offsetY) {
        lock.readLock().lock();
        try {
            if (current != null) {
                current.x = offsetX;
                current.y = offsetY;
            }
        } finally {
            lock.readLock().unlock();
        }
    }


    public static void addListener(final Object l) {
        listeners.addListener(l);
    }

    public static void removeListener(final Object l) {
        listeners.removeListener(l);
    }

}
