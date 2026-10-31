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

final class Icons {
    private static final int VERSION = 13;
    private static final float LOGO = 0.80f;
    private static final float SHAPE = 0.82f;

    private Icons() {}

    static int pad(Context c) { return Math.round(4 * c.getResources().getDisplayMetrics().density); }

    static Path blob(float cx, float cy, float r, long seed, float turn) {
        java.util.Random rnd = new java.util.Random(seed);
        double p2 = rnd.nextDouble() * Math.PI * 2, p3 = rnd.nextDouble() * Math.PI * 2, p5 = rnd.nextDouble() * Math.PI * 2;
        double a2 = 0.026 + rnd.nextDouble() * 0.020;
        double a3 = 0.028 + rnd.nextDouble() * 0.020;
        double a5 = 0.008 + rnd.nextDouble() * 0.010;
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

        Path p = new Path();
        p.moveTo((xs[n - 1] + xs[0]) / 2f, (ys[n - 1] + ys[0]) / 2f);
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            p.quadTo(xs[i], ys[i], (xs[i] + xs[j]) / 2f, (ys[i] + ys[j]) / 2f);
        }
        p.close();
        return p;
    }

    static Bitmap load(Context c, PackageManager pm, ActivityInfo ai, int size, Set<String> used, boolean dark) {
        File f = cacheFile(c, ai, size, dark);
        if (used != null) markUsed(c, ai, size, used);
        if (f.exists()) {
            Bitmap b = readRaw(f);
            if (b != null) return toHardware(b);
        }
        Bitmap b = render(c, pm, ai, size, dark);
        if (b == null) return null;
        writeRaw(f, b);
        return toHardware(b);
    }

    static void markUsed(Context c, ActivityInfo ai, int size, Set<String> used) {
        used.add(cacheFile(c, ai, size, false).getName());
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

    private static File cacheFile(Context c, ActivityInfo ai, int size, boolean dark) {
        long stamp = 0;
        try { stamp = new File(ai.applicationInfo.sourceDir).lastModified(); } catch (Exception ignored) {}
        String key = ai.packageName + "/" + ai.name + "|" + stamp + "|" + size + "|v" + VERSION;
        return new File(dir(c), md5(key) + ".px");
    }

    private static void writeRaw(File f, Bitmap b) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(b.getByteCount());
        b.copyPixelsToBuffer(buf);
        buf.rewind();
        try (FileOutputStream out = new FileOutputStream(f);
             java.nio.channels.FileChannel ch = out.getChannel()) {
            java.nio.ByteBuffer head = java.nio.ByteBuffer.allocate(8);
            head.putInt(b.getWidth()).putInt(b.getHeight()).flip();
            ch.write(head);
            ch.write(buf);
        } catch (Exception e) {
            f.delete();
        }
    }

    private static Bitmap readRaw(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f);
             java.nio.channels.FileChannel ch = in.getChannel()) {
            java.nio.ByteBuffer head = java.nio.ByteBuffer.allocate(8);
            if (ch.read(head) != 8) return null;
            head.flip();
            int w = head.getInt(), h = head.getInt();
            if (w <= 0 || h <= 0 || w > 1024 || h > 1024) return null;
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(w * h * 4);
            while (buf.hasRemaining()) if (ch.read(buf) < 0) return null;
            buf.rewind();
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            b.copyPixelsFromBuffer(buf);
            return b;
        } catch (Exception e) {
            return null;
        }
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
        }
        try (java.io.InputStream in = r.openRawResource(id)) {
            Bitmap b = BitmapFactory.decodeStream(in);
            if (b != null) return new android.graphics.drawable.BitmapDrawable(r, b);
        } catch (Throwable ignored) {}
        return null;
    }

    static Bitmap render(Context c, PackageManager pm, ActivityInfo ai, int size, boolean dark) {
        Drawable d = raw(pm, ai);
        if (d == null) {
            try { d = ai.loadIcon(pm); } catch (Throwable t) { return null; }
        }
        float density = c.getResources().getDisplayMetrics().density;
        int pad = pad(c);
        int full = size + pad * 2;
        float cx = full / 2f, cy = full / 2f, r = size / 2f * SHAPE - density * 0.5f;
        long seed = (ai.packageName + "/" + ai.name).hashCode();

        int inner = Math.round(r * 2);
        Bitmap content;
        try { content = content(d, inner); } catch (Throwable t) { return null; }

        Bitmap out = Bitmap.createBitmap(full, full, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        BitmapShader bs = new BitmapShader(content, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setTranslate(cx - inner / 2f, cy - inner / 2f);
        bs.setLocalMatrix(m);
        fill.setShader(bs);
        cv.drawPath(blob(cx, cy, r, seed, 0f), fill);
        content.recycle();
        return out;
    }

    private static Bitmap content(Drawable d, int s) {
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        if (d instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) d;

            int ext = Math.round(s * 0.25f);
            Rect r = new Rect(-ext, -ext, s + ext, s + ext);
            Drawable bg = a.getBackground(), fg = a.getForeground();
            if (bg != null) { bg.setBounds(r); bg.draw(c); } else c.drawColor(Color.WHITE);
            if (fg != null) {
                fg.setBounds(r);
                c.save();
                c.scale(LOGO, LOGO, s / 2f, s / 2f);

                Path keep = new Path();
                keep.addCircle(s / 2f, s / 2f, s * 0.5f, Path.Direction.CW);
                c.clipPath(keep);
                fg.draw(c);
                c.restore();
            }
            return b;
        }

        d.setBounds(0, 0, s, s);
        d.draw(c);
        if (fullBleed(b)) return b;

        if (shaped(b)) {
            int bg = b.getPixel(s / 2, Math.round(s * 0.07f)) | 0xFF000000;
            b.eraseColor(bg);
            int in = Math.round(s * 0.02f);
            d.setBounds(-in, -in, s + in, s + in);
            d.draw(c);
            return b;
        }

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
