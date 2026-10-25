package app.fastlauncher;

import android.Manifest;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Keeps the launcher healthy and reportable without a computer attached:
 *  - a small on-phone log of crashes and warnings (last ~64 KB),
 *  - a plain-text report (version, what's switched on, recent log) to share from the phone,
 *  - self-repair: Android switches off the panel's accessibility service whenever the app is
 *    force-stopped (phone cleaners do this); with WRITE_SECURE_SETTINGS (granted once over adb)
 *    the launcher puts it back.
 */
final class Diag {
    private Diag() {}

    private static final long MAX = 64 * 1024;
    private static Context app;

    static void install(Context c) {
        if (app != null) return;
        app = c.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                write("CRASH on " + t.getName() + "\n" + sw);
            } catch (Throwable ignored) {}
            if (previous != null) previous.uncaughtException(t, e);
        });
    }

    /** A warning worth seeing in a report; also goes to logcat. */
    static void log(String msg) {
        android.util.Log.w("FastLauncher", msg);
        write(msg);
    }

    private static File file() { return new File(app.getFilesDir(), "diag.log"); }

    private static synchronized void write(String msg) {
        if (app == null) return;
        try {
            File f = file();
            if (f.length() > MAX) trim(f);
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date()) + "  " + msg + "\n");
            }
        } catch (Exception ignored) {}
    }

    /** Keep the newer half. */
    private static void trim(File f) throws Exception {
        byte[] tail;
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            long keep = MAX / 2;
            tail = new byte[(int) keep];
            r.seek(r.length() - keep);
            r.readFully(tail);
        }
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f, false)) { out.write(tail); }
    }

    // ---------------------------------------------------------------- self-repair

    static final ComponentName PANEL = new ComponentName("app.fastlauncher", "app.fastlauncher.PanelService");

    /** Switch the panel's service back on if Android turned it off (only possible with the adb grant). */
    static void repair(Context c) {
        try {
            if (c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return;
            // switched off by the user in Settings (not by a force-stop): leave it off
            if (c.getSharedPreferences("panel", Context.MODE_PRIVATE).getBoolean("userOff", false)) return;
            String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String me = PANEL.flattenToString();
            if (list != null && (list.contains(me) || list.contains(PANEL.flattenToShortString()))) return;
            String next = list == null || list.isEmpty() ? me : list + ":" + me;
            Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
            Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            log("panel service had been switched off; switched it back on");
        } catch (Exception e) {
            log("repair: " + e);
        }
    }

    // ---------------------------------------------------------------- report

    static String report(Context c) {
        StringBuilder sb = new StringBuilder(32 * 1024);
        String version = "?";
        try { version = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception ignored) {}
        sb.append("Fast Launcher report\n");
        sb.append("version ").append(version).append("  |  ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append("  |  Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("time ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()))
                .append("  |  phone up ").append(SystemClock.elapsedRealtime() / 3600000).append(" h\n\n");

        sb.append("Status\n");
        line(sb, "default home app", isHome(c));
        line(sb, "panel service running", PanelService.running != null);
        String a11y = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        line(sb, "panel service switched on", a11y != null && a11y.contains("app.fastlauncher"));
        line(sb, "notification access", NotifService.running != null);
        try {
            line(sb, "do-not-disturb access", ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).isNotificationPolicyAccessGranted());
        } catch (Exception ignored) {}
        line(sb, "modify system settings", Settings.System.canWrite(c));
        line(sb, "calendar", granted(c, Manifest.permission.READ_CALENDAR));
        line(sb, "contacts", granted(c, Manifest.permission.READ_CONTACTS));
        line(sb, "nearby devices (Bluetooth)", granted(c, Manifest.permission.BLUETOOTH_CONNECT));
        line(sb, "location (weather)", granted(c, Manifest.permission.ACCESS_COARSE_LOCATION));
        line(sb, "self-repair allowed", granted(c, Manifest.permission.WRITE_SECURE_SETTINGS));
        line(sb, "notifications (recorder's Stop)", granted(c, Manifest.permission.POST_NOTIFICATIONS));
        line(sb, "can read any wallpaper (blur)", Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager());
        try {
            line(sb, "not battery-restricted", ((PowerManager) c.getSystemService(Context.POWER_SERVICE)).isIgnoringBatteryOptimizations(c.getPackageName()));
        } catch (Exception ignored) {}

        sb.append("\nLauncher log (newest last)\n");
        sb.append(tail(readLog(), 12 * 1024));
        sb.append("\nSystem log for the launcher (newest last)\n");
        sb.append(tail(logcat(), 14 * 1024));
        return sb.toString();
    }

    private static void line(StringBuilder sb, String what, boolean ok) {
        sb.append(ok ? "  [ok]  " : "  [NO]  ").append(what).append('\n');
    }

    private static boolean granted(Context c, String p) { return c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    private static boolean isHome(Context c) {
        ResolveInfo ri = c.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        return ri != null && ri.activityInfo != null && c.getPackageName().equals(ri.activityInfo.packageName);
    }

    private static String readLog() {
        try {
            byte[] b = java.nio.file.Files.readAllBytes(file().toPath());
            return b.length == 0 ? "(empty: nothing has gone wrong)\n" : new String(b, "UTF-8");
        } catch (Exception e) {
            return "(empty: nothing has gone wrong)\n";
        }
    }

    /** The launcher's own lines from the system log (an app may always read its own). */
    private static String logcat() {
        StringBuilder out = new StringBuilder();
        try {
            java.lang.Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-t", "400", "--pid=" + Process.myPid()});
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String l;
                while ((l = r.readLine()) != null) {
                    if (l.contains("SurfaceControl") || l.contains("InputMethodManager")) continue; // noise
                    out.append(l).append('\n');
                }
            }
        } catch (Exception e) {
            out.append("(couldn't read: ").append(e).append(")\n");
        }
        return out.toString();
    }

    private static String tail(String s, int max) {
        return s.length() <= max ? s : "...\n" + s.substring(s.length() - max);
    }
}
