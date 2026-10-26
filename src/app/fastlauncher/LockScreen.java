package app.fastlauncher;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.provider.Settings;

/**
 * The lock screen customisations a launcher can safely make. The lock screen itself (PIN,
 * fingerprint, its clock) belongs to the system and is never replaced or covered; this only
 * picks its wallpaper and flips Android's own two notification settings for it.
 */
final class LockScreen {
    private LockScreen() {}

    // ---------------------------------------------------------------- wallpaper style

    static final int OFF = 0, SAME = 1, DIMMED = 2, BLURRED = 3;
    static final String[] LABELS = {"Off", "Same", "Dimmed", "Blurred"};

    /** OFF leaves the lock screen's wallpaper alone; the rest follow the home wallpaper. */
    static int style(Context c) {
        android.content.SharedPreferences p = Walls.prefs(c);
        if (p.contains("lockStyle")) return p.getInt("lockStyle", SAME);
        return p.getBoolean("lock", true) ? SAME : OFF; // the old on/off switch
    }

    static void setStyle(Context c, int s) { Walls.prefs(c).edit().putInt("lockStyle", s).apply(); }

    /** The lock screen's version of a home wallpaper: as is, darker, or softly blurred. */
    static Bitmap variant(Bitmap home, int style) {
        if (style == SAME) return home;
        Bitmap out;
        if (style == BLURRED) {
            // blur small (cheap), then scale back up: smooth, and the clock reads on anything
            int w = Math.max(1, home.getWidth() / 8), h = Math.max(1, home.getHeight() / 8);
            Bitmap small = Bitmap.createScaledBitmap(home, w, h, true);
            if (!small.isMutable() || small.getConfig() != Bitmap.Config.ARGB_8888) {
                Bitmap m = small.copy(Bitmap.Config.ARGB_8888, true);
                if (small != home) small.recycle();
                small = m;
            }
            Walls.boxBlur(small, 6);
            Walls.boxBlur(small, 6);
            Walls.boxBlur(small, 6);
            out = Bitmap.createScaledBitmap(small, home.getWidth(), home.getHeight(), true);
            small.recycle();
            if (!out.isMutable()) out = out.copy(Bitmap.Config.ARGB_8888, true);
        } else {
            out = home.copy(Bitmap.Config.ARGB_8888, true);
        }
        Paint dim = new Paint();
        dim.setColor(style == DIMMED ? 0x61000000 : 0x26000000); // a flat dim, no gradient
        new Canvas(out).drawRect(0, 0, out.getWidth(), out.getHeight(), dim);
        return out;
    }

    // ---------------------------------------------------------------- notifications

    /** Android's own lock screen notification settings need the one-time adb grant to change. */
    static boolean canChangeNotifications(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean showNotifications(Context c) {
        return Settings.Secure.getInt(c.getContentResolver(), "lock_screen_show_notifications", 1) != 0;
    }

    static void setShowNotifications(Context c, boolean on) {
        try { Settings.Secure.putInt(c.getContentResolver(), "lock_screen_show_notifications", on ? 1 : 0); }
        catch (Exception e) { Diag.log("lock notifications: " + e); }
    }

    /** True when the lock screen shows only "new message", not what it says. */
    static boolean hideContents(Context c) {
        return Settings.Secure.getInt(c.getContentResolver(), "lock_screen_allow_private_notifications", 1) == 0;
    }

    static void setHideContents(Context c, boolean hide) {
        try { Settings.Secure.putInt(c.getContentResolver(), "lock_screen_allow_private_notifications", hide ? 0 : 1); }
        catch (Exception e) { Diag.log("lock contents: " + e); }
    }
}
