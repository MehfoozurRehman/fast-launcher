package app.fastlauncher;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.provider.Settings;

final class RefreshRate {
    private RefreshRate() {}

    private static final String KEY = "hw_screen_freq";
    private static final int STANDARD = 0, DYNAMIC = 2;
    static final int LIMIT = 50;

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("refresh", Context.MODE_PRIVATE); }

    static boolean saveBattery(Context c) { return prefs(c).getBoolean("save", true); }

    static void setSaveBattery(Context c, boolean on) {
        prefs(c).edit().putBoolean("save", on).apply();
        apply(c);
    }

    static void apply(Context c) {
        try {
            Intent b = c.getApplicationContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) return;
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) * 100 / Math.max(1, b.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
            boolean charging = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            boolean low = saveBattery(c) && level < LIMIT && !charging;
            ContentResolver cr = c.getContentResolver();
            int cur = Settings.Secure.getInt(cr, KEY, -1);
            SharedPreferences p = prefs(c);
            if (low && cur == DYNAMIC) {
                if (Settings.Secure.putInt(cr, KEY, STANDARD)) {
                    p.edit().putBoolean("lowered", true).apply();
                    Diag.log("battery " + level + "%: screen refresh rate lowered to 60 Hz");
                }
            } else if (!low && cur == STANDARD && p.getBoolean("lowered", false)) {
                if (Settings.Secure.putInt(cr, KEY, DYNAMIC)) {
                    p.edit().putBoolean("lowered", false).apply();
                    Diag.log("battery " + level + "%" + (charging ? " charging" : "") + ": screen refresh rate back to dynamic");
                }
            } else if (cur != STANDARD && p.getBoolean("lowered", false)) {
                p.edit().putBoolean("lowered", false).apply();
            }
        } catch (SecurityException e) {
            Diag.log("refresh rate: needs WRITE_SECURE_SETTINGS (adb pm grant)");
        } catch (Throwable t) {
            Diag.log("refresh rate: " + t);
        }
    }
}
