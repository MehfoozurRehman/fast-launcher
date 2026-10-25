package app.fastlauncher;

import android.app.Notification;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps a per-package count of visible notifications for the badges, and the list itself for
 * the control panel. Both are only ever replaced on the main thread.
 */
public class NotifService extends NotificationListenerService {

    interface Changed { void onNotifCountsChanged(); }

    static volatile Map<String, Integer> counts = new HashMap<>();
    /** What the panel lists: ongoing ones first, then newest first (no media, no covered group summaries). */
    static volatile List<StatusBarNotification> list = new ArrayList<>();
    static Changed listener;
    static Runnable panelListener;
    static NotifService running;

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override public void onListenerConnected() { Diag.install(this); running = this; refresh(); }
    @Override public void onListenerDisconnected() { if (running == this) running = null; }
    @Override public void onNotificationPosted(StatusBarNotification sbn) { refresh(); }
    @Override public void onNotificationRemoved(StatusBarNotification sbn) { refresh(); }

    static void dismiss(String key) {
        NotifService s = running;
        if (s != null) try { s.cancelNotification(key); } catch (Exception ignored) {}
    }

    static void dismissAll() {
        NotifService s = running;
        if (s != null) try { s.cancelAllNotifications(); } catch (Exception ignored) {}
    }

    private void refresh() {
        final Map<String, Integer> next = new HashMap<>();
        final List<StatusBarNotification> shown = new ArrayList<>();
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                // a group summary is only worth showing when none of its children are here
                Set<String> groupsWithChildren = new HashSet<>();
                for (StatusBarNotification s : active) {
                    if ((s.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) == 0 && s.getGroupKey() != null)
                        groupsWithChildren.add(s.getGroupKey());
                }
                for (StatusBarNotification s : active) {
                    Notification n = s.getNotification();
                    boolean ongoing = (n.flags & Notification.FLAG_ONGOING_EVENT) != 0;
                    boolean summary = (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
                    // badges count only real messages; the panel also lists ongoing ones (USB mode,
                    // charging, downloads...), since our pull-down replaces the system shade
                    if (!summary && !ongoing) {
                        String pkg = s.getPackageName();
                        Integer c = next.get(pkg);
                        next.put(pkg, c == null ? 1 : c + 1);
                    }
                    if (summary && groupsWithChildren.contains(s.getGroupKey())) continue;
                    if (n.extras != null && n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) continue;
                    if (getPackageName().equals(s.getPackageName())) continue;
                    shown.add(s);
                }
                // ongoing ones first (like the system shade), then newest first
                Collections.sort(shown, (a, b) -> {
                    boolean oa = (a.getNotification().flags & Notification.FLAG_ONGOING_EVENT) != 0;
                    boolean ob = (b.getNotification().flags & Notification.FLAG_ONGOING_EVENT) != 0;
                    if (oa != ob) return oa ? -1 : 1;
                    return Long.compare(b.getPostTime(), a.getPostTime());
                });
            }
        } catch (Exception ignored) {}
        main.post(new Runnable() {
            @Override public void run() {
                counts = next;
                list = shown;
                if (listener != null) listener.onNotifCountsChanged();
                if (panelListener != null) panelListener.run();
            }
        });
    }
}
