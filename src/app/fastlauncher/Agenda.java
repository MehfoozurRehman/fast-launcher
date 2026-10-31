package app.fastlauncher;

import android.Manifest;
import android.app.AlarmManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.CalendarContract;

import java.util.Calendar;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Agenda {
    private Agenda() {}

    interface Done { void onAgenda(String text, boolean hasEvent); }

    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final long DAY = 24 * 3600_000L;

    static void load(Context c, Done done) {
        final Context app = c.getApplicationContext();
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        worker.execute(() -> {
            long now = System.currentTimeMillis();
            StringBuilder sb = new StringBuilder();
            try {
                AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
                AlarmManager.AlarmClockInfo next = am.getNextAlarmClock();
                if (next != null && next.getTriggerTime() - now < DAY) sb.append("Alarm ").append(time(next.getTriggerTime()));
            } catch (Exception ignored) {}
            String event = null;
            try { event = nextEvent(app, now); } catch (Exception ignored) {}
            if (event != null) {
                if (sb.length() > 0) sb.append("  ·  ");
                sb.append(event);
            }
            final String text = sb.toString();
            final boolean hasEvent = event != null;
            main.post(() -> done.onAgenda(text, hasEvent));
        });
    }

    private static String nextEvent(Context c, long now) {
        if (c.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null;
        String[] cols = {CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.STATUS};
        try (Cursor cur = CalendarContract.Instances.query(c.getContentResolver(), cols, now - DAY, now + DAY)) {
            String best = null;
            long bestBegin = Long.MAX_VALUE;
            if (cur == null) return null;
            while (cur.moveToNext()) {
                long begin = cur.getLong(1), end = cur.getLong(2);
                if (end <= now || begin > now + DAY || begin >= bestBegin) continue;
                if (cur.getInt(3) != 0 || cur.getInt(4) == CalendarContract.Instances.STATUS_CANCELED) continue;
                String title = cur.getString(0);
                if (title == null || title.trim().isEmpty()) title = "Event";
                bestBegin = begin;
                best = (begin <= now ? "Now: " : time(begin) + " ") + title.trim();
            }
            return best;
        }
    }

    private static String time(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);

        String t = MainActivity.clockText(c) + (c.get(Calendar.AM_PM) == Calendar.AM ? " am" : " pm");
        Calendar today = Calendar.getInstance();
        if (c.get(Calendar.DAY_OF_YEAR) != today.get(Calendar.DAY_OF_YEAR)) t = "tomorrow " + t;
        return t;
    }
}
