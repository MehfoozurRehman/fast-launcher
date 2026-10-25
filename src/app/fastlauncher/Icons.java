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
 * Renders every app icon into one house shape: a superellipse ("squircle") with continuous
 * curvature, a baked soft shadow and a faint rim light. Everything is baked into the bitmap,
 * so drawing an icon at runtime is a single drawBitmap.
 */
final class Icons {

    /** Bump when the look changes so cached PNGs are re-rendered. */
    private static final int VERSION = 5;
    private static final float EXPONENT = 5f; // 2 = ellipse, higher = squarer

    private Icons() {}

    /** Shadow + rim padding around the shape, in px. */
    static int pad(Context c) { return Math.round(4 * c.getResources().getDisplayMetrics().density); }

    static Path squircle(float left, float top, float size) {
        Path p = new Path();
        float r = size / 2f, cx = left + r, cy = top + r;
        int steps = 96;
        for (int i = 0; i <= steps; i++) {
            double t = 2 * Math.PI * i / steps;
            double c = Math.cos(t), s = Math.sin(t);
            float x = (float) (cx + r * Math.signum(c) * Math.pow(Math.abs(c), 2 / EXPONENT));
            float y = (float) (cy + r * Math.signum(s) * Math.pow(Math.abs(s), 2 / EXPONENT));
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
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
        Bitmap content;
        try { content = content(d, size); } catch (Throwable t) { return null; }

        float density = c.getResources().getDisplayMetrics().density;
        int pad = pad(c);
        int full = size + pad * 2;
        Bitmap out = Bitmap.createBitmap(full, full, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Path shape = squircle(pad, pad, size);

        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(Color.WHITE);
        shadow.setShadowLayer(2.6f * density, 0, 1.2f * density, 0x3A000000);
        cv.drawPath(shape, shadow);

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        BitmapShader bs = new BitmapShader(content, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setTranslate(pad, pad);
        bs.setLocalMatrix(m);
        fill.setShader(bs);
        cv.drawPath(shape, fill);
        content.recycle();

        // rim light: bright along the top edge, a hair of shade along the bottom
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1f * density);
        rim.setShader(new LinearGradient(0, pad, 0, pad + size,
                new int[]{0x66FFFFFF, 0x00FFFFFF, 0x00000000, 0x1A000000},
                new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        Path inner = squircle(pad + 0.5f * density, pad + 0.5f * density, size - density);
        cv.drawPath(inner, rim);
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
