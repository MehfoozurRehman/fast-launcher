package app.fastlauncher;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.view.View;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

final class Weather {
    private Weather() {}

    static final int CLEAR = 0, PARTLY = 1, CLOUDY = 2, FOG = 3, RAIN = 4, SNOW = 5, STORM = 6;
    private static final long FRESH_MS = 30 * 60 * 1000L;

    static final class Now {
        int tempC, kind;
        boolean day;
        long at;
    }

    static String label(int kind, boolean day) {
        switch (kind) {
            case CLEAR: return day ? "Sunny" : "Clear";
            case PARTLY: return "Partly cloudy";
            case CLOUDY: return "Cloudy";
            case FOG: return "Fog";
            case RAIN: return "Rain";
            case SNOW: return "Snow";
            case STORM: return "Storm";
        }
        return "";
    }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("weather", Context.MODE_PRIVATE); }

    static boolean hasPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    static Now cached(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.contains("at")) return null;
        Now n = new Now();
        n.tempC = p.getInt("temp", 0);
        n.kind = p.getInt("kind", CLEAR);
        n.day = p.getBoolean("day", true);
        n.at = p.getLong("at", 0);

        return System.currentTimeMillis() - n.at > 6 * 3600_000L ? null : n;
    }

    static boolean stale(Context c) {
        return System.currentTimeMillis() - prefs(c).getLong("at", 0) > FRESH_MS;
    }

    static int kindFor(int code) {
        if (code == 0) return CLEAR;
        if (code == 1 || code == 2) return PARTLY;
        if (code == 3) return CLOUDY;
        if (code == 45 || code == 48) return FOG;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return SNOW;
        if (code >= 95) return STORM;
        return RAIN;
    }

    static void locate(Context c, Executor ex, Consumer<Location> done) {
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null || !hasPermission(c)) { done.accept(null); return; }
        Location best = null;
        try {
            for (String p : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER}) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (SecurityException ignored) {}
        boolean recent = best != null && System.currentTimeMillis() - best.getTime() < 3 * 3600_000L;
        if (recent || Build.VERSION.SDK_INT < 30) { done.accept(best); return; }
        final Location fallback = best;
        try {
            if (!lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) { done.accept(fallback); return; }
            lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, ex,
                    l -> done.accept(l != null ? l : fallback));
        } catch (Exception e) {
            done.accept(fallback);
        }
    }

    static Now fetch(Context c, double lat, double lon) throws Exception {
        String url = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code,is_day",
                lat, lon);
        JSONObject cur = new JSONObject(new String(Walls.get(url, 12000), "UTF-8")).getJSONObject("current");
        Now n = new Now();
        n.tempC = (int) Math.round(cur.getDouble("temperature_2m"));
        n.kind = kindFor(cur.getInt("weather_code"));
        n.day = cur.optInt("is_day", 1) == 1;
        n.at = System.currentTimeMillis();
        prefs(c).edit().putInt("temp", n.tempC).putInt("kind", n.kind).putBoolean("day", n.day)
                .putLong("at", n.at).apply();
        return n;
    }

    static final class Glyph extends View {
        int kind = CLOUDY;
        boolean day = true;
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF r = new RectF();

        Glyph(Context c) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setColor(0xFFFFFFFF);
            stroke.setShadowLayer(3 * d, 0, d, 0x40000000);
            fill.setColor(0xFFFFFFFF);
            fill.setShadowLayer(3 * d, 0, d, 0x40000000);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        void set(int kind, boolean day) {
            this.kind = kind;
            this.day = day;
            invalidate();
        }

        private void sun(Canvas c, float cx, float cy, float rad) {
            c.drawCircle(cx, cy, rad, stroke);
            for (int i = 0; i < 8; i++) {
                double a = Math.PI / 4 * i;
                float x1 = cx + (float) Math.cos(a) * rad * 1.55f, y1 = cy + (float) Math.sin(a) * rad * 1.55f;
                float x2 = cx + (float) Math.cos(a) * rad * 2.05f, y2 = cy + (float) Math.sin(a) * rad * 2.05f;
                c.drawLine(x1, y1, x2, y2, stroke);
            }
        }

        private final Path bite = new Path();

        private void moon(Canvas c, float cx, float cy, float rad) {
            path.reset();
            bite.reset();
            path.addCircle(cx, cy, rad, Path.Direction.CW);
            bite.addCircle(cx + rad * 0.55f, cy - rad * 0.38f, rad * 0.86f, Path.Direction.CW);
            path.op(bite, Path.Op.DIFFERENCE);
            c.drawPath(path, stroke);
        }

        private void cloud(Canvas c, float x, float y, float w) {
            float u = w / 16f;
            path.reset();
            path.moveTo(x + 3 * u, y + 10 * u);
            path.cubicTo(x, y + 10 * u, x, y + 5.5f * u, x + 3.2f * u, y + 5.5f * u);
            path.cubicTo(x + 3.5f * u, y + 1.5f * u, x + 9 * u, y + 0.5f * u, x + 10.4f * u, y + 4.2f * u);
            path.cubicTo(x + 13.6f * u, y + 3.6f * u, x + 16 * u, y + 6.2f * u, x + 14.6f * u, y + 8.6f * u);
            path.cubicTo(x + 14.2f * u, y + 9.6f * u, x + 13.4f * u, y + 10 * u, x + 12.6f * u, y + 10 * u);
            path.close();
            c.drawPath(path, stroke);
        }

        @Override protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight());
            float u = s / 24f;
            stroke.setStrokeWidth(1.9f * u);
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            switch (kind) {
                case CLEAR:
                    if (day) sun(c, cx, cy, 4.2f * u); else moon(c, cx, cy, 7.5f * u);
                    break;
                case PARTLY:
                    if (day) sun(c, cx - 3.5f * u, cy - 4 * u, 3.2f * u); else moon(c, cx - 3 * u, cy - 4 * u, 5 * u);
                    cloud(c, cx - 7 * u, cy - 1 * u, 17 * u);
                    break;
                case CLOUDY:
                    cloud(c, cx - 9 * u, cy - 6 * u, 19 * u);
                    break;
                case FOG:
                    cloud(c, cx - 9 * u, cy - 9 * u, 19 * u);
                    c.drawLine(cx - 8 * u, cy + 5 * u, cx + 8 * u, cy + 5 * u, stroke);
                    c.drawLine(cx - 5 * u, cy + 8.5f * u, cx + 6 * u, cy + 8.5f * u, stroke);
                    break;
                case RAIN:
                    cloud(c, cx - 9 * u, cy - 9 * u, 19 * u);
                    for (int i = -1; i <= 1; i++)
                        c.drawLine(cx + i * 4.5f * u + u, cy + 4 * u, cx + i * 4.5f * u - u, cy + 9 * u, stroke);
                    break;
                case SNOW:
                    cloud(c, cx - 9 * u, cy - 9 * u, 19 * u);
                    for (int i = -1; i <= 1; i++) c.drawCircle(cx + i * 4.5f * u, cy + 6.5f * u + (i == 0 ? 2 * u : 0), 1.2f * u, fill);
                    break;
                case STORM:
                    cloud(c, cx - 9 * u, cy - 9 * u, 19 * u);
                    path.reset();
                    path.moveTo(cx + u, cy + 2.5f * u);
                    path.lineTo(cx - 2.5f * u, cy + 7.5f * u);
                    path.lineTo(cx + 1.5f * u, cy + 7.5f * u);
                    path.lineTo(cx - 1.5f * u, cy + 11.5f * u);
                    c.drawPath(path, stroke);
                    break;
            }
        }
    }
}
