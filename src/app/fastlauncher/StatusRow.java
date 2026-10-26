package app.fastlauncher;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.telephony.TelephonyManager;
import android.view.View;

import java.util.Calendar;

/**
 * Our own status bar row along the top of the panel. The panel's window sits above the real
 * status bar (Android puts accessibility overlays on top), so it draws the essentials itself:
 * time on the left; Wi-Fi strength, mobile signal and battery on the right.
 */
final class StatusRow extends View {
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private final float d;

    private String time = "", pct = "";
    private int wifi = -1;   // 0..4, -1 = no Wi-Fi
    private int cell = -1;   // 0..4, -1 = no SIM/service
    private int battery = -1;
    private boolean charging;

    StatusRow(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        text.setColor(Ui.FG);
        text.setTypeface(Ui.font(c, 600));
        text.setTextSize(15f * d);
        fill.setColor(Ui.FG);
        dim.setColor((Ui.FG & 0x00FFFFFF) | 0x4D000000);
        line.setColor(Ui.FG);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.2f * d);
    }

    @Override public boolean hasOverlappingRendering() { return false; }

    /** Read everything fresh (cheap: no waiting on the system). */
    void refresh() {
        Context c = getContext();
        time = MainActivity.clockText(Calendar.getInstance());
        try {
            // connected? asked of the network stack (Wi-Fi details like the network id are hidden from apps)
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean onWifi = false;
            for (android.net.Network n : cm.getAllNetworks()) {
                android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) onWifi = true;
            }
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (onWifi && wm != null) {
                int rssi = wm.getConnectionInfo().getRssi();
                // three arcs: -67 dBm or better is full, like the system's own icon
                wifi = rssi >= -67 ? 3 : rssi >= -78 ? 2 : rssi > -127 ? 1 : 3;
            } else wifi = -1;
        } catch (Exception e) { wifi = -1; }
        try {
            TelephonyManager tm = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
            android.telephony.SignalStrength ss = tm == null ? null : tm.getSignalStrength();
            cell = ss == null || tm.getSimState() != TelephonyManager.SIM_STATE_READY ? -1 : Math.min(4, ss.getLevel());
        } catch (Exception e) { cell = -1; }
        try {
            Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                battery = level < 0 ? -1 : Math.round(level * 100f / Math.max(1, scale));
                int st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
            }
        } catch (Exception ignored) {}
        pct = battery < 0 ? "" : battery + "%";
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        float h = getHeight(), cy = h / 2f + 2 * d, pad = 26 * d; // the system centres a touch low
        float base = cy - (text.descent() + text.ascent()) / 2f;
        c.drawText(time, pad, base, text);

        // right to left: battery, percentage, mobile bars, Wi-Fi fan
        float x = getWidth() - 24 * d;
        if (battery >= 0) {
            float tw0 = text.measureText(pct);
            c.drawText(pct, x - tw0, base, text);
            x -= tw0 + 4 * d;
            float bw = 22 * d, bh = 11 * d;
            r.set(x - bw, cy - bh / 2, x - 2 * d, cy + bh / 2);
            c.drawRoundRect(r, 3 * d, 3 * d, line);
            c.drawRect(x - 2 * d, cy - 2.5f * d, x, cy + 2.5f * d, fill);           // the nub
            float inner = (r.width() - 3.2f * d) * Math.max(0.06f, battery / 100f);
            RectF in = new RectF(r.left + 1.6f * d, r.top + 1.6f * d, r.left + 1.6f * d + inner, r.bottom - 1.6f * d);
            Paint p = battery <= 15 && !charging ? red() : fill;
            c.drawRoundRect(in, 1.5f * d, 1.5f * d, p);
            if (charging) {                                                          // a small bolt over it
                path.reset();
                float bx = r.centerX(), s = 3.6f * d;
                path.moveTo(bx + s * 0.25f, cy - s);
                path.lineTo(bx - s * 0.55f, cy + s * 0.15f);
                path.lineTo(bx - s * 0.05f, cy + s * 0.15f);
                path.lineTo(bx - s * 0.25f, cy + s);
                path.lineTo(bx + s * 0.55f, cy - s * 0.15f);
                path.lineTo(bx + s * 0.05f, cy - s * 0.15f);
                path.close();
                Paint bolt = new Paint(Paint.ANTI_ALIAS_FLAG);
                bolt.setColor(Ui.dark ? 0xFF000000 : 0xFFFFFFFF);
                c.drawPath(path, bolt);
            }
            x = r.left - 10 * d;
        }
        if (cell >= 0) {
            float bar = 3 * d, gap = 1.6f * d, top = cy - 6 * d, bottom = cy + 6 * d;
            for (int i = 3; i >= 0; i--) {
                float bx = x - (4 - i) * bar - (3 - i) * gap;
                float bt = bottom - (bottom - top) * (i + 1) / 4f;
                c.drawRoundRect(bx, bt, bx + bar, bottom, d, d, i < cell ? fill : dim);
            }
            x -= 4 * bar + 3 * gap + 8 * d;
        }
        if (wifi >= 0) {
            // a dot and two arcs above it, lit up to the signal level (like the system's icon)
            float ox = x - 9 * d, oy = cy + 5.5f * d;
            c.drawCircle(ox, oy - 1.4f * d, 1.9f * d, fill);
            Paint arc = new Paint(line);
            arc.setStrokeWidth(2.1f * d);
            arc.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 1; i <= 2; i++) {
                float rr = (4.6f + 4.4f * (i - 1)) * d;
                arc.setColor(wifi >= i + 1 ? Ui.FG : (Ui.FG & 0x00FFFFFF) | 0x4D000000);
                c.drawArc(new RectF(ox - rr, oy - 1.4f * d - rr, ox + rr, oy - 1.4f * d + rr), 225, 90, false, arc);
            }
        }
    }

    private Paint red;

    private Paint red() {
        if (red == null) { red = new Paint(Paint.ANTI_ALIAS_FLAG); red.setColor(0xFFFF453A); }
        return red;
    }
}
