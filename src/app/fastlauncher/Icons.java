package app.fastlauncher;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Set;

/**
 * Renders every app icon as a pebble: a white organic blob plate with the app's own artwork
 * set into a smaller, slightly turned blob inside it. Each app gets its own gentle variation
 * of the shape, seeded from its name, so the grid feels hand-made but stays calm.
 * Shadows and all are baked into the bitmap, so drawing an icon is a single drawBitmap.
 */
final class Icons {

    /** Bump when the look changes so cached PNGs are re-rendered. */
    private static final int VERSION = 7;
    private static final float INNER = 0.72f; // artwork blob radius relative to the plate

    private Icons() {}

    /** Shadow + rim padding around the shape, in px. */
    static int pad(Context c) { return Math.round(4 * c.getResources().getDisplayMetrics().density); }

    /**
     * A smooth closed blob: a circle whose radius is nudged by a few low-frequency sine
     * lobes. Normalised so its widest point touches radius r.
     */
    static Path blob(float cx, float cy, float r, long seed, float turn) {
        java.util.Random rnd = new java.util.Random(seed);
        double p2 = rnd.nextDouble() * Math.PI * 2, p3 = rnd.nextDouble() * Math.PI * 2, p5 = rnd.nextDouble() * Math.PI * 2;
        double a2 = 0.026 + rnd.nextDouble() * 0.020; // oval-ness
        double a3 = 0.028 + rnd.nextDouble() * 0.020; // three soft lobes: the pebble look
        double a5 = 0.008 + rnd.nextDouble() * 0.010; // a little wobble
        int n = 180;
        float[] xs = new float[n], ys = new float[n];
        double max = 0;
        double[] rad = new double[n];
        for (int i = 0; i < n; i++) {
            double t = 2 * Math.PI * i / n + turn;
            rad[i] = 1 + a2 * Math.sin(2 * t + p2) + a3 * Math.sin(3 * t + p3) + a5 * Math.sin(5 * t + p5);
            max = Math.max(max, rad[i]);
        }
        for (int i = 0; i < n; i++) {
            double t = 2 * Math.PI * i / n;
            float rr = (float) (r * rad[i] / max);
            xs[i] = cx + rr * (float) Math.cos(t);
            ys[i] = cy + rr * (float) Math.sin(t);
        }
        // quadratic smoothing through midpoints: no visible facets at any size
        Path p = new Path();
        p.moveTo((xs[n - 1] + xs[0]) / 2f, (ys[n - 1] + ys[0]) / 2f);
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            p.quadTo(xs[i], ys[i], (xs[i] + xs[j]) / 2f, (ys[i] + ys[j]) / 2f);
        }
        p.close();
        return p;
    }

    /** Disk-cached icon. Returns a GPU (HARDWARE) bitmap when possible. */
    static Bitmap load(Context c, PackageManager pm, ActivityInfo ai, int size, Set<String> used) {
        File f = cacheFile(c, ai, size);
        if (used != null) used.add(f.getName());
        if (f.exists()) {
            Bitmap b = BitmapFactory.decodeFile(f.getPath());
            if (b != null) return toHardware(b);
        }
        Bitmap b = render(c, pm, ai, size);
        if (b == null) return null;
        try (FileOutputStream out = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception ignored) {}
        return toHardware(b);
    }

    /** Records that an in-memory icon's cache file is still wanted, without decoding it. */
    static void markUsed(Context c, ActivityInfo ai, int size, Set<String> used) {
        used.add(cacheFile(c, ai, size).getName());
    }

    static void prune(Context c, Set<String> used) {
        File[] files = dir(c).listFiles();
        if (files == null) return;
        for (File f : files) if (!used.contains(f.getName())) f.delete();
    }

    private static File dir(Context c) {
        File d = new File(c.getCacheDir(), "icons");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private static File cacheFile(Context c, ActivityInfo ai, int size) {
        long stamp = 0;
        try { stamp = new File(ai.applicationInfo.sourceDir).lastModified(); } catch (Exception ignored) {}
        String key = ai.packageName + "/" + ai.name + "|" + stamp + "|" + size + "|v" + VERSION;
        return new File(dir(c), md5(key) + ".png");
    }

    private static String md5(String s) {
        try {
            byte[] h = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    static Bitmap toHardware(Bitmap b) {
        Bitmap hw = b.copy(Bitmap.Config.HARDWARE, false);
        if (hw == null) return b;
        b.recycle();
        return hw;
    }

    /**
     * The app's own icon, skipping the OEM icon theme. Honor swaps launcher icons inside
     * Resources.getDrawable, so adaptive icons are inflated from their XML directly and
     * bitmap icons are decoded from the raw PNG bytes.
     */
    private static Drawable raw(PackageManager pm, ActivityInfo ai) {
        int id = ai.getIconResource();
        if (id == 0) return null;
        Resources r;
        try { r = pm.getResourcesForApplication(pm.getApplicationInfo(ai.packageName, 0)); }
        catch (Throwable t) { return null; }
        try (android.content.res.XmlResourceParser p = r.getXml(id)) {
            Drawable d = Drawable.createFromXml(r, p);
            if (d != null) return d;
        } catch (Throwable ignored) {
            // not an XML drawable: a plain bitmap icon
        }
        try (java.io.InputStream in = r.openRawResource(id)) {
            Bitmap b = BitmapFactory.decodeStream(in);
            if (b != null) return new android.graphics.drawable.BitmapDrawable(r, b);
        } catch (Throwable ignored) {}
        return null;
    }

    static Bitmap render(Context c, PackageManager pm, ActivityInfo ai, int size) {
        Drawable d = raw(pm, ai);
        if (d == null) {
            try { d = ai.loadIcon(pm); } catch (Throwable t) { return null; }
        }
        float density = c.getResources().getDisplayMetrics().density;
        int pad = pad(c);
        int full = size + pad * 2;
        float cx = full / 2f, cy = full / 2f, plateR = size / 2f;
        long seed = (ai.packageName + "/" + ai.name).hashCode();

        float innerR = plateR * INNER;
        int inner = Math.round(innerR * 2);
        Bitmap content;
        try { content = content(d, inner); } catch (Throwable t) { return null; }

        Bitmap out = Bitmap.createBitmap(full, full, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);

        // the white plate, with a soft drop shadow
        Path plate = blob(cx, cy, plateR - density * 0.5f, seed, 0f);
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(Color.WHITE);
        shadow.setShadowLayer(2.8f * density, 0, 1.3f * density, 0x3D000000);
        cv.drawPath(plate, shadow);
        Paint plateFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        plateFill.setShader(new LinearGradient(0, cy - plateR, 0, cy + plateR,
                0xFFFFFFFF, 0xFFEDEFF3, Shader.TileMode.CLAMP));
        cv.drawPath(plate, plateFill);
        // a hair of shade along the plate's lower edge, so it reads as a raised pebble
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(0.9f * density);
        edge.setShader(new LinearGradient(0, cy - plateR, 0, cy + plateR,
                new int[]{0x00000000, 0x00000000, 0x1C000000}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        cv.drawPath(plate, edge);

        // the artwork, set into an echo of the plate's shape, a little turned, so the white
        // rim stays even all the way round while still feeling hand-placed
        Path art = blob(cx, cy, innerR, seed, 0.22f);
        Paint lift = new Paint(Paint.ANTI_ALIAS_FLAG);
        lift.setColor(Color.WHITE);
        lift.setShadowLayer(1.8f * density, 0, 0.9f * density, 0x33000000);
        cv.drawPath(art, lift);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        BitmapShader bs = new BitmapShader(content, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setTranslate(cx - inner / 2f, cy - inner / 2f);
        bs.setLocalMatrix(m);
        fill.setShader(bs);
        cv.drawPath(art, fill);
        content.recycle();

        // faint top highlight on the artwork
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(0.9f * density);
        rim.setShader(new LinearGradient(0, cy - innerR, 0, cy + innerR,
                new int[]{0x59FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.45f}, Shader.TileMode.CLAMP));
        cv.drawPath(art, rim);
        return out;
    }

    private static Bitmap content(Drawable d, int s) {
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        if (d instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) d;
            // layers are 108dp with the visible 72dp in the middle
            int ext = Math.round(s * 0.25f);
            Rect r = new Rect(-ext, -ext, s + ext, s + ext);
            Drawable bg = a.getBackground(), fg = a.getForeground();
            if (bg != null) { bg.setBounds(r); bg.draw(c); } else c.drawColor(Color.WHITE);
            if (fg != null) { fg.setBounds(r); fg.draw(c); }
            return b;
        }
        // Legacy icon: if it already fills its square, let it bleed to the edge.
        d.setBounds(0, 0, s, s);
        d.draw(c);
        if (fullBleed(b)) return b;
        // A pre-shaped icon (its own rounded square or circle): extend its background
        // colour into our corners so it reads as one shape, not a tile inside a tile.
        if (shaped(b)) {
            int bg = b.getPixel(s / 2, Math.round(s * 0.07f)) | 0xFF000000;
            b.eraseColor(bg);
            int in = Math.round(s * 0.02f);
            d.setBounds(-in, -in, s + in, s + in);
            d.draw(c);
            return b;
        }
        // Otherwise sit it on a soft paper tile.
        b.eraseColor(Color.TRANSPARENT);
        Paint tile = new Paint();
        tile.setShader(new LinearGradient(0, 0, 0, s, 0xFFFFFFFF, 0xFFECEEF2, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, s, s, tile);
        int in = Math.round(s * 0.19f);
        d.setBounds(in, in, s - in, s - in);
        d.draw(c);
        return b;
    }

    private static boolean shaped(Bitmap b) {
        int s = b.getWidth(), e = Math.max(2, s / 30);
        int[][] pts = {{s / 2, e}, {e, s / 2}, {s - 1 - e, s / 2}, {s / 2, s - 1 - e}};
        for (int[] p : pts) if (Color.alpha(b.getPixel(p[0], p[1])) < 230) return false;
        return true;
    }

    private static boolean fullBleed(Bitmap b) {
        int s = b.getWidth(), e = Math.max(2, s / 14);
        int[][] pts = {{e, e}, {s - 1 - e, e}, {e, s - 1 - e}, {s - 1 - e, s - 1 - e}, {s / 2, 1}, {1, s / 2}};
        for (int[] p : pts) if (Color.alpha(b.getPixel(p[0], p[1])) < 230) return false;
        return true;
    }
}
