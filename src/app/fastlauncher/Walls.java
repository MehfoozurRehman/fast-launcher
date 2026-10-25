package app.fastlauncher;

import android.app.WallpaperManager;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.view.Display;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Wallpapers from wallhaven.cc: search, pick, download, crop to the screen, set, remember. */
final class Walls {
    private Walls() {}

    static final class Theme {
        final String key, label, query, categories;
        Theme(String key, String label, String query, String categories) {
            this.key = key; this.label = label; this.query = query; this.categories = categories;
        }
    }

    static final Theme[] THEMES = {
            new Theme("nature", "Nature", "nature", "100"),
            new Theme("landscape", "Landscapes", "landscape", "100"),
            new Theme("ocean", "Ocean", "ocean", "100"),
            new Theme("mountains", "Mountains", "mountains", "100"),
            new Theme("forest", "Forest", "forest", "100"),
            new Theme("minimal", "Minimal", "minimalism", "110"),
            new Theme("dark", "Dark", "dark", "110"),
            new Theme("space", "Space", "space", "110"),
            new Theme("city", "City", "city", "100"),
            new Theme("architecture", "Architecture", "architecture", "100"),
            new Theme("abstract", "Abstract", "abstract", "110"),
            new Theme("digital", "Digital art", "digital art", "110"),
            new Theme("cars", "Cars", "cars", "100"),
            new Theme("anime", "Anime", "", "010"),
    };
    static final Theme EVERYTHING = new Theme("all", "Everything", "", "110");
    static final Set<String> DEFAULT_THEMES = new HashSet<>(Arrays.asList("nature", "landscape", "ocean", "mountains", "minimal"));
    static final String[] INTERVAL_LABELS = {"Off", "Daily", "2 days", "3 days"};

    static final class Wall {
        String id, thumb, full, page, theme;
        int width, height, color = 0xFF2A2C31;

        JSONObject toJson() throws Exception {
            return new JSONObject().put("id", id).put("thumb", thumb).put("full", full).put("page", page)
                    .put("theme", theme).put("w", width).put("h", height).put("color", color);
        }

        static Wall fromJson(JSONObject o) {
            Wall w = new Wall();
            w.id = o.optString("id"); w.thumb = o.optString("thumb"); w.full = o.optString("full");
            w.page = o.optString("page"); w.theme = o.optString("theme");
            w.width = o.optInt("w"); w.height = o.optInt("h"); w.color = o.optInt("color", w.color);
            return w;
        }
    }

    static final class Page {
        final List<Wall> walls = new ArrayList<>();
        String seed;
        int lastPage = 1;
    }

    // ------------------------------------------------------------------ network

    static byte[] get(String url, int timeoutMs) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("User-Agent", "FastLauncher/0.3 (github.com/MehfoozurRehman/fast-launcher)");
            try {
                int code = c.getResponseCode();
                if (code == 429) { // wallhaven allows 45 calls a minute
                    last = new IOException("wallhaven is busy, try again in a moment");
                    sleep(1500L * (attempt + 1));
                    continue;
                }
                if (code != 200) throw new IOException("wallhaven replied " + code);
                try (InputStream in = new BufferedInputStream(c.getInputStream())) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
                    byte[] buf = new byte[16 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    return out.toByteArray();
                }
            } catch (java.net.UnknownHostException | java.net.ConnectException e) {
                throw new IOException("No internet connection");
            } catch (java.net.SocketTimeoutException e) {
                last = new IOException("Connection timed out");
            } finally {
                c.disconnect();
            }
        }
        throw last != null ? last : new IOException("Couldn't reach wallhaven");
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

    static Wall byId(String id) throws Exception {
        JSONObject d = new JSONObject(new String(get("https://wallhaven.cc/api/v1/w/" + id, 15000), "UTF-8")).getJSONObject("data");
        Wall w = new Wall();
        w.id = d.getString("id");
        w.full = d.getString("path");
        w.page = d.optString("short_url");
        w.thumb = d.getJSONObject("thumbs").optString("original");
        w.width = d.optInt("dimension_x");
        w.height = d.optInt("dimension_y");
        w.theme = "Wallhaven";
        JSONArray colors = d.optJSONArray("colors");
        if (colors != null && colors.length() > 0) {
            try { w.color = Color.parseColor(colors.getString(0)); } catch (Exception ignored) {}
        }
        return w;
    }

    static Page search(Theme t, boolean popular, int page, String seed) throws Exception {
        StringBuilder u = new StringBuilder("https://wallhaven.cc/api/v1/search?purity=100&ratios=portrait&atleast=1080x1920");
        u.append("&categories=").append(t.categories);
        u.append(popular ? "&sorting=toplist&topRange=1M" : "&sorting=random");
        if (!t.query.isEmpty()) u.append("&q=").append(URLEncoder.encode(t.query, "UTF-8"));
        u.append("&page=").append(page);
        if (seed != null && !popular) u.append("&seed=").append(seed);
        JSONObject o = new JSONObject(new String(get(u.toString(), 15000), "UTF-8"));
        Page p = new Page();
        JSONObject meta = o.optJSONObject("meta");
        if (meta != null) {
            p.seed = meta.isNull("seed") ? null : meta.optString("seed", null);
            p.lastPage = meta.optInt("last_page", 1);
        }
        JSONArray data = o.getJSONArray("data");
        for (int i = 0; i < data.length(); i++) {
            JSONObject d = data.getJSONObject(i);
            Wall w = new Wall();
            w.id = d.getString("id");
            w.full = d.getString("path");
            w.page = d.optString("short_url", "https://wallhaven.cc/w/" + w.id);
            w.thumb = d.getJSONObject("thumbs").optString("original");
            w.width = d.optInt("dimension_x");
            w.height = d.optInt("dimension_y");
            w.theme = t.label;
            JSONArray colors = d.optJSONArray("colors");
            if (colors != null && colors.length() > 0) {
                try { w.color = Color.parseColor(colors.getString(0)); } catch (Exception ignored) {}
            }
            p.walls.add(w);
        }
        return p;
    }

    // ------------------------------------------------------------------ preferences

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("walls", Context.MODE_PRIVATE); }

    static Set<String> themes(Context c) {
        return new HashSet<>(prefs(c).getStringSet("themes", DEFAULT_THEMES));
    }

    static void setThemes(Context c, Set<String> keys) { prefs(c).edit().putStringSet("themes", new HashSet<>(keys)).apply(); }

    static List<Theme> selectedThemes(Context c) {
        Set<String> keys = themes(c);
        List<Theme> out = new ArrayList<>();
        for (Theme t : THEMES) if (keys.contains(t.key)) out.add(t);
        if (out.isEmpty()) out.add(EVERYTHING);
        return out;
    }

    /** 0 = off, otherwise days. */
    static int interval(Context c) { return prefs(c).getInt("interval", 2); }

    static void setInterval(Context c, int days) {
        prefs(c).edit().putInt("interval", days).apply();
        schedule(c);
    }

    static boolean lockToo(Context c) { return prefs(c).getBoolean("lock", true); }
    static void setLockToo(Context c, boolean v) { prefs(c).edit().putBoolean("lock", v).apply(); }

    static Wall current(Context c) {
        String s = prefs(c).getString("current", null);
        if (s == null) return null;
        try { return Wall.fromJson(new JSONObject(s)); } catch (Exception e) { return null; }
    }

    static long lastChange(Context c) { return prefs(c).getLong("lastChange", 0); }

    /** First run: start the clock now instead of replacing the user's wallpaper on install. */
    static void init(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.contains("lastChange")) p.edit().putLong("lastChange", System.currentTimeMillis()).apply();
        schedule(c);
    }

    static boolean due(Context c) {
        int days = interval(c);
        return days > 0 && System.currentTimeMillis() - lastChange(c) >= TimeUnit.DAYS.toMillis(days);
    }

    static long nextChangeAt(Context c) {
        int days = interval(c);
        return days == 0 ? 0 : lastChange(c) + TimeUnit.DAYS.toMillis(days);
    }

    private static Set<String> idSet(Context c, String key) {
        String s = prefs(c).getString(key, "");
        return s.isEmpty() ? new LinkedHashSet<String>() : new LinkedHashSet<>(Arrays.asList(s.split(",")));
    }

    private static void addId(Context c, String key, String id, int keep) {
        List<String> l = new ArrayList<>(idSet(c, key));
        l.remove(id);
        l.add(id);
        while (l.size() > keep) l.remove(0);
        prefs(c).edit().putString(key, android.text.TextUtils.join(",", l)).apply();
    }

    // ------------------------------------------------------------------ scheduling

    private static final int JOB_ID = 4411;

    static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        if (interval(c) == 0) { js.cancel(JOB_ID); return; }
        // Wake every few hours and only act when due; survives the OEM delaying jobs.
        JobInfo ji = new JobInfo.Builder(JOB_ID, new ComponentName(c, WallJob.class))
                .setPeriodic(TimeUnit.HOURS.toMillis(4))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setRequiresBatteryNotLow(true)
                .setPersisted(true)
                .build();
        try { js.schedule(ji); } catch (Exception e) {
            android.util.Log.w("FastLauncher", "couldn't schedule wallpaper job: " + e);
        }
    }

    // ------------------------------------------------------------------ picking and applying

    private static final AtomicBoolean busy = new AtomicBoolean();
    private static final Random rnd = new Random();

    static boolean isBusy() { return busy.get(); }

    /** A fresh random wallpaper from the chosen themes, skipping history and dislikes. */
    static Wall pickRandom(Context c) throws Exception {
        List<Theme> pool = selectedThemes(c);
        Set<String> skip = idSet(c, "history");
        skip.addAll(idSet(c, "disliked"));
        Collections.shuffle(pool, rnd);
        Exception last = null;
        for (int attempt = 0; attempt < Math.max(3, pool.size()); attempt++) {
            Theme t = pool.get(attempt % pool.size());
            try {
                Page p = search(t, false, 1, null);
                for (Wall w : p.walls) if (!skip.contains(w.id)) return w;
            } catch (Exception e) {
                last = e;
                if (e.getMessage() != null && e.getMessage().startsWith("No internet")) throw e;
            }
        }
        throw last != null ? last : new Exception("Couldn't find a new wallpaper");
    }

    static int[] screenSize(Context c) {
        DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        DisplayMetrics m = new DisplayMetrics();
        d.getRealMetrics(m);
        int w = Math.min(m.widthPixels, m.heightPixels), h = Math.max(m.widthPixels, m.heightPixels);
        return new int[]{w, h};
    }

    /** Decodes with subsampling, then centre-crops to exactly w x h. */
    static Bitmap decodeForScreen(byte[] data, int w, int h) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) throw new IOException("Not an image");
        // halve while the decoded image would still fully cover the screen
        int sample = 1;
        while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        Bitmap src = BitmapFactory.decodeByteArray(data, 0, data.length, d);
        if (src == null) throw new IOException("Couldn't decode the image");
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        float s = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        Matrix m = new Matrix();
        m.setScale(s, s);
        m.postTranslate((w - src.getWidth() * s) / 2f, (h - src.getHeight() * s) / 2f);
        new Canvas(out).drawBitmap(src, m, new Paint(Paint.FILTER_BITMAP_FLAG));
        src.recycle();
        return out;
    }

    static Bitmap download(Context c, Wall w) throws IOException {
        int[] s = screenSize(c);
        return decodeForScreen(get(w.full, 30000), s[0], s[1]);
    }

    /**
     * Lets the visible launcher hide the system's wallpaper swap: Android tears down and
     * rebinds its wallpaper window when a new image is set, which shows as a black blink.
     */
    interface Cover {
        void show(Bitmap next, Runnable ready);
        void settle();
    }

    static volatile Cover cover;

    /** Sets the wallpaper. Pass a ready bitmap (from a preview) to skip the download. */
    static void apply(Context c, Wall w, Bitmap ready) throws Exception {
        Bitmap b = ready != null ? ready : download(c, w);
        WallpaperManager wm = WallpaperManager.getInstance(c);
        int which = WallpaperManager.FLAG_SYSTEM | (lockToo(c) ? WallpaperManager.FLAG_LOCK : 0);
        Cover cv = cover;
        if (cv != null) {
            java.util.concurrent.CountDownLatch shown = new java.util.concurrent.CountDownLatch(1);
            cv.show(b, shown::countDown);
            try { shown.await(700, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        }
        try {
            wm.setBitmap(b, null, true, which);
        } finally {
            if (cv != null) cv.settle();
        }
        prefs(c).edit().putString("current", w.toJson().toString())
                .putLong("lastChange", System.currentTimeMillis()).apply();
        addId(c, "history", w.id, 150);
        saveBlur(c, w, b);
    }

    // ------------------------------------------------------------------ drawer blur

    private static java.io.File blurFile(Context c) { return new java.io.File(c.getFilesDir(), "blur.png"); }

    private static int wallpaperId(Context c) {
        try { return WallpaperManager.getInstance(c).getWallpaperId(WallpaperManager.FLAG_SYSTEM); }
        catch (Throwable t) { return -1; }
    }

    /** A tiny, heavily blurred copy of the wallpaper we just set, for behind the drawer. */
    static void saveBlur(Context c, Wall w, Bitmap full) {
        try {
            Bitmap src = full;
            if (src == null) {
                byte[] d = get(w.thumb, 15000);
                src = BitmapFactory.decodeByteArray(d, 0, d.length);
            }
            if (src == null) return;
            int[] scr = screenSize(c);
            int bw = scr[0] / 10, bh = scr[1] / 10;
            Bitmap small = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            float s = Math.max(bw / (float) src.getWidth(), bh / (float) src.getHeight());
            Matrix m = new Matrix();
            m.setScale(s, s);
            m.postTranslate((bw - src.getWidth() * s) / 2f, (bh - src.getHeight() * s) / 2f);
            new Canvas(small).drawBitmap(src, m, new Paint(Paint.FILTER_BITMAP_FLAG));
            boxBlur(small, 5);
            boxBlur(small, 5);
            boxBlur(small, 5);
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(blurFile(c))) {
                small.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            prefs(c).edit().putInt("blurFor", wallpaperId(c)).apply();
        } catch (Exception ignored) {}
    }

    /** The blur for the current wallpaper, or null if the wallpaper wasn't set by us. */
    static Bitmap loadBlur(Context c) {
        SharedPreferences p = prefs(c);
        int id = wallpaperId(c);
        if (!blurFile(c).exists() || p.getInt("blurFor", -2) != id) {
            // first run after an update: the current wallpaper may be ours but unblurred
            Wall cur = current(c);
            if (cur == null || p.contains("blurFor")) return null;
            saveBlur(c, cur, null);
            if (!blurFile(c).exists()) return null;
        }
        Bitmap b = BitmapFactory.decodeFile(blurFile(c).getPath());
        return b == null ? null : Icons.toHardware(b);
    }

    /** Separable box blur in place; three passes approximate a gaussian. */
    private static void boxBlur(Bitmap b, int r) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h], tmp = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        blurPass(px, tmp, w, h, r, true);
        blurPass(tmp, px, w, h, r, false);
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void blurPass(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        int div = r * 2 + 1;
        for (int l = 0; l < lines; l++) {
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int k = -r; k <= r; k++) {
                int i = Math.max(0, Math.min(len - 1, k));
                int p = in[horizontal ? l * w + i : i * w + l];
                sa += p >>> 24; sr += (p >> 16) & 255; sg += (p >> 8) & 255; sb += p & 255;
            }
            for (int i = 0; i < len; i++) {
                out[horizontal ? l * w + i : i * w + l] = ((sa / div) << 24) | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int add = Math.min(len - 1, i + r + 1), rem = Math.max(0, i - r);
                int pa = in[horizontal ? l * w + add : add * w + l], pr = in[horizontal ? l * w + rem : rem * w + l];
                sa += (pa >>> 24) - (pr >>> 24);
                sr += ((pa >> 16) & 255) - ((pr >> 16) & 255);
                sg += ((pa >> 8) & 255) - ((pr >> 8) & 255);
                sb += (pa & 255) - (pr & 255);
            }
        }
    }

    /** Picks and applies a new random wallpaper; optionally marks the current one disliked. */
    static Wall next(Context c, boolean dislikeCurrent) throws Exception {
        if (!busy.compareAndSet(false, true)) throw new Exception("Already changing the wallpaper");
        try {
            Wall cur = current(c);
            if (dislikeCurrent && cur != null) addId(c, "disliked", cur.id, 500);
            Wall w = pickRandom(c);
            apply(c, w, null);
            return w;
        } finally {
            busy.set(false);
        }
    }

    static void applyChosen(Context c, Wall w, Bitmap ready) throws Exception {
        if (!busy.compareAndSet(false, true)) throw new Exception("Already changing the wallpaper");
        try { apply(c, w, ready); } finally { busy.set(false); }
    }
}
