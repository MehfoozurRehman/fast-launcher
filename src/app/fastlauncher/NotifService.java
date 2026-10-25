package app.fastlauncher;

import android.app.Notification;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.HashMap;
import java.util.Map;

/**
 * Keeps a per-package count of visible notifications. The launcher reads {@link #counts}
 * on the main thread while drawing; this service only ever replaces it there.
 */
public class NotifService extends NotificationListenerService {

    interface Changed { void onNotifCountsChanged(); }

    static volatile Map<String, Integer> counts = new HashMap<>();
    static Changed listener;

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override public void onListenerConnected() { refresh(); }
    @Override public void onNotificationPosted(StatusBarNotification sbn) { refresh(); }
    @Override public void onNotificationRemoved(StatusBarNotification sbn) { refresh(); }

    private void refresh() {
        final Map<String, Integer> next = new HashMap<>();
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification s : active) {
                    Notification n = s.getNotification();
                    if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
                    if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) continue;
                    String pkg = s.getPackageName();
                    Integer c = next.get(pkg);
                    next.put(pkg, c == null ? 1 : c + 1);
                }
            }
        } catch (Exception ignored) {}
        main.post(new Runnable() {
            @Override public void run() {
                counts = next;
                if (listener != null) listener.onNotifCountsChanged();
            }
        });
    }
}
