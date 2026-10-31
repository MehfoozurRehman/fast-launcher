package app.fastlauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;

/**
 * If Fast Launcher stops being the phone's home app (a crash, a system "restore default launcher",
 * a settings reset) there is no app icon to tap and nothing on screen to bring it back. The
 * notification listener (which feeds the icon badges) is still alive, so it leaves one quiet notification: tap it, say yes to the system's
 * "make this your home app?" question, and the launcher is back. It goes away by itself once it is.
 */
final class HomeGuard {
    private HomeGuard() {}

    static final String ACTION_SET_HOME = "app.fastlauncher.SET_HOME";
    private static final int ID = 7001;
    private static final String CHANNEL = "fl_home";

    static boolean isHome(Context c) {
        try {
            RoleManager rm = (RoleManager) c.getSystemService(Context.ROLE_SERVICE);
            if (rm != null) return rm.isRoleHeld(RoleManager.ROLE_HOME);
        } catch (Throwable ignored) {}
        return true; // can't tell: don't nag
    }

    /** Post the notification when we're not home; take it down when we are. Safe to call often. */
    static void check(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (isHome(c)) { nm.cancel(ID); return; }
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Home screen", NotificationManager.IMPORTANCE_LOW));
            Intent i = new Intent(c, MainActivity.class).setAction(ACTION_SET_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(ID, new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_revert)
                    .setContentTitle("Fast Launcher isn't your home screen")
                    .setContentText("Tap to switch back")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build());
            Diag.log("not the home app; left a notification to switch back");
        } catch (Throwable t) {
            Diag.log("home check: " + t);
        }
    }

    /** Ask the system to make us home again (one yes in its own dialog). */
    static void request(android.app.Activity a, int code) {
        try {
            RoleManager rm = (RoleManager) a.getSystemService(Context.ROLE_SERVICE);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME))
                a.startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), code);
        } catch (Throwable t) {
            Diag.log("asking for the home role: " + t);
        }
    }
}
