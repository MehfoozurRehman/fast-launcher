package app.fastlauncher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;

import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Thumbnail loader for the wallpaper grid. Newest requests run first (LIFO), so when you
 * fling through the grid the tiles you stop on load before the ones you flew past.
 */
final class Thumbs {
    interface Ready { void onThumbReady(); }

    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getAllocationByteCount(); }
    };
    private final HashSet<String> inflight = new HashSet<>();
    private final HashSet<String> failed = new HashSet<>();
    private final HashMap<String, Long> loadedAt = new HashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor pool;
    private final Ready ready;
    private boolean closed;

    Thumbs(Ready ready) {
        this.ready = ready;
        LinkedBlockingDeque<Runnable> lifo = new LinkedBlockingDeque<Runnable>() {
            @Override public boolean offer(Runnable r) { return offerFirst(r); }
        };
        pool = new ThreadPoolExecutor(3, 3, 20, TimeUnit.SECONDS, lifo);
        pool.allowCoreThreadTimeOut(true);
    }

    /** Thumbnails kept on disk too (set once from a context), so they come back instantly later. */
    static volatile java.io.File disk;
    private static final long DISK_MAX = 30L * 1024 * 1024;

    private static java.io.File diskFile(String url) {
        java.io.File dir = disk;
        if (dir == null) return null;
        if (!dir.isDirectory()) dir.mkdirs();
        return new java.io.File(dir, Integer.toHexString(url.hashCode()) + "_" + url.length() + ".img");
    }

    /** Keep the disk cache under its cap: the oldest files go first. */
    private static void trimDisk() {
        java.io.File dir = disk;
        java.io.File[] fs = dir == null ? null : dir.listFiles();
        if (fs == null) return;
        long total = 0;
        for (java.io.File f : fs) total += f.length();
        if (total <= DISK_MAX) return;
        java.util.Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (java.io.File f : fs) {
            if (total <= DISK_MAX * 3 / 4) break;
            total -= f.length();
            f.delete();
        }
    }

    Bitmap get(final String url) {
        if (url == null || url.isEmpty()) return null;
        Bitmap b = cache.get(url);
        if (b != null || failed.contains(url) || !inflight.add(url)) return b;
        pool.execute(() -> {
            Bitmap out = null;
            try {
                java.io.File f = diskFile(url);
                byte[] data = null;
                if (f != null && f.exists()) {
                    try { data = java.nio.file.Files.readAllBytes(f.toPath()); f.setLastModified(System.currentTimeMillis()); }
                    catch (Exception e) { data = null; }
                }
                if (data == null) {
                    data = Walls.get(url, 15000);
                    if (f != null) {
                        try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f)) { fo.write(data); } catch (Exception ignored) {}
                        trimDisk();
                    }
                }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap src = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                if (src != null) out = Icons.toHardware(src);
            } catch (Exception ignored) {}
            final Bitmap res = out;
            main.post(() -> {
                inflight.remove(url);
                if (closed) return;
                if (res == null) { failed.add(url); return; }
                cache.put(url, res);
                loadedAt.put(url, SystemClock.uptimeMillis());
                ready.onThumbReady();
            });
        });
        return null;
    }

    /** 0..1 fade-in progress for a tile that just arrived. */
    float fade(String url) {
        Long t = loadedAt.get(url);
        if (t == null) return 1f;
        float f = (SystemClock.uptimeMillis() - t) / 200f;
        if (f >= 1f) { loadedAt.remove(url); return 1f; }
        return f;
    }

    void retryFailed() { failed.clear(); }

    void close() {
        closed = true;
        pool.shutdownNow();
        cache.evictAll();
    }
}
