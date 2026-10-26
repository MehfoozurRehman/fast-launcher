package app.fastlauncher;

import android.app.Notification;
import android.content.Context;
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
    static final String TEST_CHANNEL = "fl_test";

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override public void onListenerConnected() { Diag.install(this); running = this; refresh(); }
    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        // a ringing call (full-screen alarm or call screen): the panel must not cover it
        if (n.fullScreenIntent != null || Notification.CATEGORY_CALL.equals(n.category) && (n.flags & Notification.FLAG_ONGOING_EVENT) == 0
                || Notification.CATEGORY_ALARM.equals(n.category) && n.fullScreenIntent != null) {
            main.post(PanelService::closeForCall);
        }
        refresh();
    }

    // Honor may unbind listeners under memory pressure; ask to be bound again rather than
    // leaving badges and the notifications page silently empty
    @Override public void onListenerDisconnected() {
        if (running == this) running = null;
        // nothing we show may outlive the connection: no stale badges, no undismissable rows
        main.post(() -> {
            counts = new HashMap<>();
            list = new ArrayList<>();
            if (listener != null) listener.onNotifCountsChanged();
            if (panelListener != null) panelListener.run();
        });
        try { requestRebind(new android.content.ComponentName(this, NotifService.class)); } catch (Exception ignored) {}
    }
    @Override public void onNotificationRemoved(StatusBarNotification sbn, RankingMap ranking, int reason) {
        // swiped away (here or in the system shade) or cleared: keep a copy for the history
        if (reason == REASON_CANCEL || reason == REASON_CANCEL_ALL
                || reason == REASON_LISTENER_CANCEL || reason == REASON_LISTENER_CANCEL_ALL) {
            final Gone g = Gone.of(this, sbn);
            if (g != null) main.post(() -> remember(g));
        }
        refresh();
    }

    // ---------------------------------------------------------------- history

    /** A notification that was dismissed: enough to show it again and to open it. */
    static final class Gone {
        String pkg, title, text;
        long posted, removed;
        android.app.PendingIntent open; // only while the launcher's process lives; else the app opens

        static Gone of(Context c, StatusBarNotification s) {
            Notification n = s.getNotification();
            if ((n.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_GROUP_SUMMARY)) != 0) return null;
            if (n.extras != null && n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return null;
            if (c.getPackageName().equals(s.getPackageName()) && !TEST_CHANNEL.equals(n.getChannelId())) return null;
            CharSequence t = n.extras == null ? null : n.extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence x = n.extras == null ? null : n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
            if (x == null && n.extras != null) x = n.extras.getCharSequence(Notification.EXTRA_TEXT);
            if (t == null && x == null) return null;
            Gone g = new Gone();
            g.pkg = s.getPackageName();
            g.title = t == null ? "" : t.toString();
            g.text = x == null ? "" : x.toString();
            g.posted = s.getPostTime();
            g.removed = System.currentTimeMillis();
            g.open = n.contentIntent;
            return g;
        }
    }

    private static final long KEEP = 24 * 3600_000L;
    private static final int MAX = 50;
    private static List<Gone> history;

    /** Dismissed in the last day, newest first. Main thread only. */
    static List<Gone> history(Context c) {
        if (history == null) history = load(c);
        long cutoff = System.currentTimeMillis() - KEEP;
        for (java.util.Iterator<Gone> it = history.iterator(); it.hasNext(); ) if (it.next().removed < cutoff) it.remove();
        return history;
    }

    static void clearHistory(Context c) {
        history(c).clear();
        save(c);
    }

    private void remember(Gone g) {
        List<Gone> h = history(this);
        h.add(0, g);
        while (h.size() > MAX) h.remove(h.size() - 1);
        save(this);
        if (panelListener != null) panelListener.run();
    }

    // the text survives restarts (the open-intents can't): a small JSON list in preferences
    private static void save(Context c) {
        try {
            org.json.JSONArray a = new org.json.JSONArray();
            for (Gone g : history) {
                a.put(new org.json.JSONObject().put("p", g.pkg).put("t", g.title).put("x", g.text)
                        .put("at", g.posted).put("rm", g.removed));
            }
            c.getSharedPreferences("notif_history", Context.MODE_PRIVATE).edit().putString("list", a.toString()).apply();
        } catch (Exception ignored) {}
    }

    private static List<Gone> load(Context c) {
        List<Gone> out = new ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(
                    c.getSharedPreferences("notif_history", Context.MODE_PRIVATE).getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.getJSONObject(i);
                Gone g = new Gone();
                g.pkg = o.optString("p");
                g.title = o.optString("t");
                g.text = o.optString("x");
                g.posted = o.optLong("at");
                g.removed = o.optLong("rm");
                out.add(g);
            }
        } catch (Exception ignored) {}
        return out;
    }

    static void dismiss(String key) {
        NotifService s = running;
        if (s != null) try { s.cancelNotification(key); } catch (Exception ignored) {}
    }

    /** Hide a notification for a while; the system brings it back afterwards. */
    static void snooze(String key, long ms) {
        NotifService s = running;
        if (s != null) try { s.snoozeNotification(key, ms); } catch (Exception ignored) {}
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
                    // our own (the screen-recording one) stay out, except the adb test notification
                    if (getPackageName().equals(s.getPackageName()) && !TEST_CHANNEL.equals(s.getNotification().getChannelId())) continue;
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
