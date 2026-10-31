package app.fastlauncher;

import android.app.Notification;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.HashMap;
import java.util.Map;

public class NotifService extends NotificationListenerService {
    interface Changed { void onNotifCountsChanged(); }

    static volatile Map<String, Integer> counts = new HashMap<>();
    static Changed listener;
    static NotifService running;

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override public void onListenerConnected() {
        Diag.install(this);
        running = this;
        HomeGuard.check(this);
        refresh();
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) { refresh(); }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) { refresh(); }

    @Override public void onListenerDisconnected() {
        if (running == this) running = null;
        main.post(() -> {
            counts = new HashMap<>();
            if (listener != null) listener.onNotifCountsChanged();
        });
        try { requestRebind(new android.content.ComponentName(this, NotifService.class)); } catch (Exception ignored) {}
    }

    private void refresh() {
        final Map<String, Integer> next = new HashMap<>();
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification s : active) {
                    Notification n = s.getNotification();
                    boolean ongoing = (n.flags & Notification.FLAG_ONGOING_EVENT) != 0;
                    boolean summary = (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0;

                    if (summary || ongoing || getPackageName().equals(s.getPackageName())) continue;
                    Integer c = next.get(s.getPackageName());
                    next.put(s.getPackageName(), c == null ? 1 : c + 1);
                }
            }
        } catch (Exception ignored) {}
        main.post(() -> {
            counts = next;
            if (listener != null) listener.onNotifCountsChanged();
        });
    }
}
