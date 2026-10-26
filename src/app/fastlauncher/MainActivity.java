package app.fastlauncher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements WallpaperUi.Host {

    // Layout shared with the wallpaper preview so it can show "your home" exactly.
    static final int CLOCK_TOP_DP = 112, DATE_TOP_DP = 196, DOCK_HEIGHT_DP = 190;
    private static final int DOCK_SLOTS = 10;
    private static final String[][] DEFAULT_DOCK = {
            {"com.google.android.apps.maps"}, {"com.wispr.flowapp"}, {"com.facebook.katana"},
            {"com.instagram.android"}, {"com.google.android.youtube"},
            {"com.google.android.dialer"}, {"com.google.android.apps.messaging"},
            {"com.hihonor.camera", "com.android.camera"}, {"com.whatsapp.w4b", "com.whatsapp"},
            {"com.microsoft.emmx", "com.android.chrome"}};

    private float dp;
    private int iconPx;
    private int insetTop, insetBottom;
    private boolean insetsApplied;

    private Root root;
    private FrameLayout home, drawer;
    private View topScrim;
    private BlurView blurView, coverView;
    private LinearLayout dateRow, weatherChip;
    private TextView agenda;
    private SearchExtras extras;
    private Weather.Glyph weatherGlyph;
    private TextView weatherText;
    private boolean weatherLoading, coverPending;
    private TextView clock, date, title, snackView;
    private EditText search;
    private Ui.Glyph btnSearch;
    private AppGridView dock, grid;

    /** Survives activity recreation (theme/wallpaper changes), so icons never blink. */
    private static final List<AppGridView.AppEntry> CACHE = new ArrayList<>();
    /** Which look (dark/light plates) and size the icons in CACHE were drawn with. */
    private static boolean cacheDark = Ui.dark;
    private static int cacheSize;
    private final List<AppGridView.AppEntry> all = new ArrayList<>();
    private List<AppGridView.AppEntry> drawerApps = new ArrayList<>();
    private List<AppGridView.AppEntry> shown = drawerApps;
    private final AppGridView.AppEntry[] dockEntries = new AppGridView.AppEntry[DOCK_SLOTS];
    private List<AppGridView.AppEntry> dockShown = new ArrayList<>();
    // Process-wide and never shut down: a late callback from an old activity instance
    // must never hit a dead executor.
    private static final ExecutorService iconWorker = Executors.newSingleThreadExecutor();
    private static final ExecutorService wallWorker = Executors.newSingleThreadExecutor();
    private Object colorsListener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private boolean drawerOpen, searching, brightWall;
    private float topLuma = -1f;
    private int wallColor = 0xFF2A2C31;
    private int loadGen;
    private AppGridView.AppEntry placing;
    private final List<WallpaperUi.Overlay> overlays = new ArrayList<>();

    // ================================================================ lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Diag.install(this);
        Ui.theme(this);
        builtDark = Ui.dark;
        builtAccent = Ui.ACCENT;
        builtConfig = new android.content.res.Configuration(getResources().getConfiguration());
        dp = getResources().getDisplayMetrics().density;
        iconPx = Math.round(48 * dp);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);

        root = new Root(this);
        setContentView(root);
        buildHome();
        buildDrawer();
        root.setOnApplyWindowInsetsListener((v, in) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = in.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = in.getSystemWindowInsetTop();
                bottom = Math.min(in.getSystemWindowInsetBottom(), in.getStableInsetBottom());
            }
            if (top != insetTop || bottom != insetBottom || !insetsApplied) {
                insetTop = top;
                insetBottom = bottom;
                insetsApplied = true;
                applyInsets();
            }
            return in;
        });
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);

        if (!CACHE.isEmpty()) {
            all.addAll(CACHE);
            resolveDock();
            refreshDrawerList();
            dock.setItems(dockShown);
        }
        IntentFilter dbg = new IntentFilter("app.fastlauncher.DEBUG");
        // only adb (which holds DUMP) may send these; other apps can't touch the wallpaper
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(debugReceiver, dbg, android.Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED);
        else registerReceiver(debugReceiver, dbg, android.Manifest.permission.DUMP, null);
        watchWallpaperColors();
        final Context appCtx = getApplicationContext();
        wallWorker.execute(() -> Walls.init(appCtx));
        loadBlur();
        loadApps(null);
    }

    /** adb shell am broadcast -a app.fastlauncher.DEBUG -p app.fastlauncher --es debug swap|restore|history|apply:ID */
    private final BroadcastReceiver debugReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String what = i.getStringExtra("debug");
            if ("report".equals(what)) {
                // the same report as "Report a problem", as a file adb can pull:
                // /sdcard/Android/data/app.fastlauncher/files/report.txt
                final Context app = getApplicationContext();
                wallWorker.execute(() -> {
                    try (java.io.FileWriter w = new java.io.FileWriter(new java.io.File(app.getExternalFilesDir(null), "report.txt"))) {
                        w.write(Diag.report(app));
                    } catch (Exception e) { Diag.log("report file: " + e); }
                });
                return;
            }
            if ("testnotif".equals(what)) { postTestNotification(); return; }
            if (what != null && what.startsWith("seed:")) { // adb: preview an accent from any colour
                seedPreview = true;
                if (Ui.setSeed(MainActivity.this, Ui.seedFrom(new int[]{(int) Long.parseLong(what.substring(5), 16) | 0xFF000000}))) recreate();
                return;
            }
            if ("seedreset".equals(what)) { // back to the wallpaper's own colour
                seedPreview = false;
                try { onWallColors(WallpaperManager.getInstance(MainActivity.this).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)); }
                catch (Exception ignored) {}
                return;
            }
            if ("tiles".equals(what)) { PanelService.dumpSystemTiles(); return; }
            if (what != null && what.startsWith("tile:")) {
                PanelService.pressSystemTile(what.substring(5), ok -> Diag.log("tile result " + ok));
                return;
            }
            if ("gc".equals(what)) { // leak checks: count only what's really still referenced
                for (int k = 0; k < 3; k++) { Runtime.getRuntime().gc(); System.runFinalization(); }
                return;
            }
            if (what != null) debugWallpaper(what);
        }
    };

    /**
     * adb-only test: a notification with Reply and Mark as read buttons, to check the panel's
     * notification actions end to end. The reply lands in the launcher log ("test reply: ...").
     */
    private void postTestNotification() {
        android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new android.app.NotificationChannel(NotifService.TEST_CHANNEL, "Tests",
                android.app.NotificationManager.IMPORTANCE_LOW));
        String act = getPackageName() + ".TEST_REPLY";
        if (!testReceiverOn) {
            testReceiverOn = true;
            IntentFilter f = new IntentFilter(act);
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    android.os.Bundle in = android.app.RemoteInput.getResultsFromIntent(i);
                    CharSequence text = in == null ? null : in.getCharSequence("text");
                    Diag.log("test " + (text != null ? "reply: " + text : "action: " + i.getStringExtra("which")));
                    ((android.app.NotificationManager) c.getSystemService(NOTIFICATION_SERVICE)).cancel(77);
                }
            };
            if (Build.VERSION.SDK_INT >= 33) getApplicationContext().registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
            else getApplicationContext().registerReceiver(r, f);
        }
        android.app.PendingIntent reply = android.app.PendingIntent.getBroadcast(this, 1,
                new Intent(act).setPackage(getPackageName()).putExtra("which", "reply"),
                android.app.PendingIntent.FLAG_MUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        android.app.PendingIntent read = android.app.PendingIntent.getBroadcast(this, 2,
                new Intent(act).setPackage(getPackageName()).putExtra("which", "mark read"),
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        android.app.Notification n = new android.app.Notification.Builder(this, NotifService.TEST_CHANNEL)
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle("Test message")
                .setContentText("Reply from the panel to test it")
                .addAction(new android.app.Notification.Action.Builder(null, "Reply", reply)
                        .addRemoteInput(new android.app.RemoteInput.Builder("text").setLabel("Reply").build())
                        .setSemanticAction(android.app.Notification.Action.SEMANTIC_ACTION_REPLY).build())
                .addAction(new android.app.Notification.Action.Builder(null, "Mark as read", read)
                        .setSemanticAction(android.app.Notification.Action.SEMANTIC_ACTION_MARK_AS_READ).build())
                .build();
        nm.notify(77, n);
    }

    private static boolean testReceiverOn;

    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        // light/dark switched: rebuild with the new palette (icons come from the cache instantly)
        // (compare with what this activity was built in: the panel service may flip the shared palette first)
        Ui.theme(this);
        // font size, display size or language changed too: everything is sized in onCreate
        android.content.res.Configuration was = builtConfig;
        if (builtDark != Ui.dark || was == null || was.fontScale != c.fontScale || was.densityDpi != c.densityDpi
                || !was.getLocales().equals(c.getLocales())) recreate();
    }

    private android.content.res.Configuration builtConfig;

    private boolean builtDark;
    private static boolean seedPreview; // adb colour preview in force: the wallpaper doesn't override it
    private int builtAccent;

    @Override protected void onDestroy() {
        unregisterReceiver(pkgReceiver);
        unregisterReceiver(debugReceiver);
        if (Build.VERSION.SDK_INT >= 27 && colorsListener != null) {
            try {
                WallpaperManager.getInstance(this).removeOnColorsChangedListener(
                        (WallpaperManager.OnColorsChangedListener) colorsListener);
            } catch (Exception ignored) {}
        }
        loadGen++; // abandon any in-flight icon load for this instance
        super.onDestroy();
    }

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            Uri d = i.getData();
            loadApps(d == null ? null : d.getSchemeSpecificPart());
        }
    };

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (!Intent.ACTION_MAIN.equals(i.getAction())) return;
        // Home pressed: unwind everything back to the plain home screen.
        closeAllOverlays();
        cancelPlacing();
        if (searching) endSearch();
        if (drawerOpen || root.progress > 0) root.animateTo(0f);
    }

    @Override public void onBackPressed() {
        if (!overlays.isEmpty()) { overlays.get(overlays.size() - 1).close(); return; }
        if (placing != null) { cancelPlacing(); return; }
        if (searching) { endSearch(); return; }
        if (root.progress > 0f) root.animateTo(0f);
    }

    /** adb: am start -n app.fastlauncher/.MainActivity --es debug swap|restore */
    private void debugWallpaper(String what) {
        final Context app = getApplicationContext();
        wallWorker.execute(() -> {
            try {
                SharedPreferences p = Walls.prefs(app);
                if ("swap".equals(what)) {
                    Walls.Wall cur = Walls.current(app);
                    if (cur != null) p.edit().putString("debugPrev", cur.toJson().toString()).apply();
                    Walls.next(app, false);
                } else if ("history".equals(what)) {
                    android.util.Log.i("FastLauncher", "history " + p.getString("history", ""));
                } else if (what.startsWith("apply:")) {
                    Walls.applyChosen(app, Walls.byId(what.substring(6)), null);
                } else if ("restore".equals(what)) {
                    String prev = p.getString("debugPrev", null);
                    if (prev != null) Walls.applyChosen(app, Walls.Wall.fromJson(new org.json.JSONObject(prev)), null);
                }
                android.util.Log.i("FastLauncher", "debug " + what + " done");
            } catch (Exception e) {
                Diag.log("debug " + what + " failed " + e);
            }
        });
    }

    private final NotifService.Changed notifChanged = this::pushBadges;

    private void pushBadges() {
        dock.setNotifCounts(NotifService.counts);
        grid.setNotifCounts(NotifService.counts);
    }

    @Override protected void onResume() {
        super.onResume();
        // a light/dark switch that happened while we were in the background
        Ui.theme(this);
        if (builtDark != Ui.dark || builtAccent != Ui.ACCENT) { recreate(); return; }
        NotifService.listener = notifChanged;
        // put the panel's service back if a phone cleaner force-stopped us (Android turns it off)
        final Context appCtx = getApplicationContext();
        wallWorker.execute(() -> Diag.repair(appCtx));
        // notification access granted but not connected (Honor may drop listeners): ask again
        if (NotifService.running == null) {
            String l = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            if (l != null && l.contains(getPackageName())) {
                try { android.service.notification.NotificationListenerService.requestRebind(
                        new ComponentName(this, NotifService.class)); } catch (Exception ignored) {}
            }
        }
        pushBadges();
        clock.removeCallbacks(tick);
        showWeather();
        updateHeader();
        tick.run();
        if (sheet == null) main.postDelayed(() -> { if (!isDestroyed()) sheet(); }, 1500);
        Walls.cover = cover;
        // Backstop for the scheduled job, which OEM battery managers like to delay.
        if (Walls.due(this) && !Walls.isBusy()) {
            final Context app = getApplicationContext();
            wallWorker.execute(() -> { try { if (Walls.due(app)) Walls.next(app, false); } catch (Exception ignored) {} });
        }
    }

    @Override protected void onPause() {
        if (NotifService.listener == notifChanged) NotifService.listener = null;
        if (Walls.cover == cover) Walls.cover = null;
        clock.removeCallbacks(tick);
        super.onPause();
    }

    // ================================================================ shared view factories

    static TextView makeClock(Context c) {
        TextView t = new TextView(c);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 76);
        t.setTypeface(Ui.font(c, 650));
        t.setIncludeFontPadding(false);
        t.setGravity(Gravity.CENTER);
        t.setShadowLayer(Ui.dp(c, 6), 0, Ui.dp(c, 2), 0x40000000);
        return t;
    }

    static TextView makeDate(Context c) {
        TextView t = new TextView(c);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 21);
        t.setTypeface(Ui.font(c, 600));
        t.setIncludeFontPadding(false);
        t.setGravity(Gravity.CENTER);
        t.setShadowLayer(Ui.dp(c, 4), 0, Ui.dp(c, 1), 0x40000000);
        return t;
    }

    static AppGridView makeDock(Context c) { return new AppGridView(c, 5, 17, 90, 48, 8, 0, false, false); }

    static String clockText(Calendar c) {
        int h = c.get(Calendar.HOUR);
        if (h == 0) h = 12;
        return h + ":" + String.format(Locale.US, "%02d", c.get(Calendar.MINUTE));
    }

    static String dateText(Calendar c) {
        return new SimpleDateFormat("EEE, d MMMM", Locale.getDefault()).format(c.getTime());
    }

    // ================================================================ home

    private int dpi(float v) { return Math.round(v * dp); }

    private void buildHome() {
        coverView = new BlurView(this);
        coverView.setVisibility(View.INVISIBLE);
        root.addView(coverView, new FrameLayout.LayoutParams(-1, -1));

        home = new FrameLayout(this);
        root.addView(home, new FrameLayout.LayoutParams(-1, -1));

        // Only visible on bright wallpapers, to keep the white clock legible.
        topScrim = new View(this);
        topScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x99000000, 0x66000000, 0x00000000}));
        topScrim.setAlpha(0f);
        home.addView(topScrim, new FrameLayout.LayoutParams(-1, dpi(340), Gravity.TOP));

        clock = makeClock(this);
        home.addView(clock, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        clock.setOnClickListener(v -> startSafely(new Intent(AlarmClock.ACTION_SHOW_ALARMS), v));
        Ui.pressable(clock);

        dateRow = new LinearLayout(this);
        dateRow.setGravity(Gravity.CENTER_VERTICAL);
        home.addView(dateRow, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        date = makeDate(this);
        dateRow.addView(date);
        date.setOnClickListener(v -> startSafely(
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR), v));
        Ui.pressable(date);

        weatherChip = new LinearLayout(this);
        weatherChip.setGravity(Gravity.CENTER_VERTICAL);
        weatherChip.setPadding(dpi(12), dpi(4), dpi(4), dpi(4));
        weatherGlyph = new Weather.Glyph(this);
        weatherChip.addView(weatherGlyph, new LinearLayout.LayoutParams(dpi(26), dpi(26)));
        weatherText = makeDate(this);
        LinearLayout.LayoutParams wt = new LinearLayout.LayoutParams(-2, -2);
        wt.leftMargin = dpi(6);
        weatherChip.addView(weatherText, wt);
        weatherChip.setClickable(true);
        weatherChip.setOnClickListener(v -> onWeatherTap(v));
        Ui.pressable(weatherChip);
        weatherChip.setVisibility(View.GONE);
        dateRow.addView(weatherChip);

        agenda = makeDate(this);
        agenda.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        agenda.setTypeface(Ui.font(this, 550));
        agenda.setSingleLine(true);
        agenda.setEllipsize(android.text.TextUtils.TruncateAt.END);
        agenda.setPadding(dpi(12), dpi(4), dpi(12), dpi(4));
        agenda.setVisibility(View.GONE);
        agenda.setOnClickListener(v -> startSafely(agendaHasEvent
                ? Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
                : new Intent(AlarmClock.ACTION_SHOW_ALARMS), v));
        Ui.pressable(agenda);
        FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        ap.leftMargin = ap.rightMargin = dpi(24);
        home.addView(agenda, ap);

        dock = makeDock(this);
        dock.setListener(new AppGridView.Listener() {
            @Override public void onTap(AppGridView.AppEntry e, int index) {
                if (placing != null) { placeAt(index); return; }
                if (!e.blank) launch(e, dock, index);
            }
            @Override public void onLongPress(AppGridView.AppEntry e, int index, float cx, float cy) {
                if (placing != null) return;
                dockMenu(e, index, cx, cy);
            }
            @Override public void onPressStart(AppGridView.AppEntry e) { prefetchShortcuts(e); }
        });
        home.addView(dock, new FrameLayout.LayoutParams(-1, dpi(DOCK_HEIGHT_DP), Gravity.BOTTOM));
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Calendar c = Calendar.getInstance();
            clock.setText(clockText(c));
            date.setText(dateText(c));
            if (Weather.stale(MainActivity.this)) refreshWeather();
            refreshAgenda();
            long now = System.currentTimeMillis();
            clock.postDelayed(this, 60000 - (now % 60000) + 50);
        }
    };

    private boolean agendaHasEvent;

    private void refreshAgenda() {
        Agenda.load(this, (text, hasEvent) -> {
            if (isDestroyed()) return;
            agendaHasEvent = hasEvent;
            if (!android.text.TextUtils.equals(agenda.getText(), text)) agenda.setText(text);
            int vis = text.isEmpty() ? View.GONE : View.VISIBLE;
            if (agenda.getVisibility() != vis) Ui.fadeVisible(agenda, vis == View.VISIBLE);
        });
    }

    private void buildDrawer() {
        blurView = new BlurView(this);
        blurView.setVisibility(View.INVISIBLE);
        root.addView(blurView, new FrameLayout.LayoutParams(-1, -1));

        // Fading a ViewGroup normally renders it offscreen every frame; ours has no
        // overlapping children, so let each child take the alpha directly.
        drawer = new FrameLayout(this) {
            @Override public boolean hasOverlappingRendering() { return false; }
        };
        drawer.setBackgroundColor(0x59000000);
        drawer.setVisibility(View.INVISIBLE);
        root.addView(drawer, new FrameLayout.LayoutParams(-1, -1));

        grid = new AppGridView(this, 5, 17, 97, 48, 12, 12, true, true);
        grid.setListener(new AppGridView.Listener() {
            @Override public void onTap(AppGridView.AppEntry e, int index) { launch(e, grid, index); }
            @Override public void onLongPress(AppGridView.AppEntry e, int index, float cx, float cy) {
                appMenu(e, cx, cy);
            }
            @Override public void onFooterTap(float cx, float cy) { hiddenMenu(cx, cy); }
            @Override public void onPressStart(AppGridView.AppEntry e) { prefetchShortcuts(e); }
        });
        drawer.addView(grid, new FrameLayout.LayoutParams(-1, -1, Gravity.TOP));

        title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setLineSpacing(dpi(4), 1f);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28);
        title.setTypeface(Ui.font(this, 700));
        title.setIncludeFontPadding(false);
        title.setShadowLayer(dpi(3), 0, dpi(1), 0x55000000);
        drawer.addView(title, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));

        btnSearch = new Ui.Glyph(this, Ui.G_SEARCH, Color.WHITE);
        btnSearch.setPadding(dpi(10), dpi(10), dpi(10), dpi(10));
        btnSearch.setClickable(true);
        btnSearch.setOnClickListener(v -> beginSearch());
        Ui.pressable(btnSearch);
        drawer.addView(btnSearch, new FrameLayout.LayoutParams(dpi(48), dpi(48), Gravity.TOP | Gravity.END));
    }

    private void applyInsets() {
        FrameLayout.LayoutParams cp = (FrameLayout.LayoutParams) clock.getLayoutParams();
        cp.topMargin = dpi(CLOCK_TOP_DP);
        clock.setLayoutParams(cp);
        FrameLayout.LayoutParams dpm = (FrameLayout.LayoutParams) dateRow.getLayoutParams();
        dpm.topMargin = dpi(DATE_TOP_DP) - dpi(4); // the chip's vertical padding
        dateRow.setLayoutParams(dpm);
        FrameLayout.LayoutParams apm = (FrameLayout.LayoutParams) agenda.getLayoutParams();
        apm.topMargin = dpi(DATE_TOP_DP) + dpi(30);
        agenda.setLayoutParams(apm);

        FrameLayout.LayoutParams dk = (FrameLayout.LayoutParams) dock.getLayoutParams();
        dk.bottomMargin = dockBottomMargin();
        dock.setLayoutParams(dk);

        int headerTop = insetTop + dpi(14);
        FrameLayout.LayoutParams tp = (FrameLayout.LayoutParams) title.getLayoutParams();
        tp.topMargin = headerTop + dpi(8);
        tp.leftMargin = dpi(23);
        title.setLayoutParams(tp);
        if (search != null) placeSearch();
        FrameLayout.LayoutParams bp = (FrameLayout.LayoutParams) btnSearch.getLayoutParams();
        bp.topMargin = headerTop;
        bp.rightMargin = dpi(14);
        btnSearch.setLayoutParams(bp);
        setGridTop(0);
        grid.setBottomInset(insetBottom);
    }

    // ================================================================ wallpaper colours

    private void watchWallpaperColors() {
        if (Build.VERSION.SDK_INT < 27) return;
        WallpaperManager wm = WallpaperManager.getInstance(this);
        try { onWallColors(wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)); } catch (Exception ignored) {}
        WallpaperManager.OnColorsChangedListener l = (colors, which) -> {
            if (isDestroyed()) return;
            if ((which & WallpaperManager.FLAG_SYSTEM) != 0) onWallColors(colors);
        };
        colorsListener = l;
        wm.addOnColorsChangedListener(l, main);
    }

    private void onWallColors(WallpaperColors wc) {
        if (wc == null) return;
        wallColor = wc.getPrimaryColor().toArgb();
        boolean bright;
        if (Build.VERSION.SDK_INT >= 31) {
            bright = (wc.getColorHints() & WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0;
        } else {
            bright = Color.luminance(wallColor) > 0.55f;
        }
        brightWall = bright;
        topLuma = -1f; // until the new blur has been analysed
        // the launcher's accent follows the wallpaper: rebuild with it when it changes
        int seed = Ui.seedFrom(new int[]{wc.getPrimaryColor().toArgb(),
                wc.getSecondaryColor() == null ? 0 : wc.getSecondaryColor().toArgb(),
                wc.getTertiaryColor() == null ? 0 : wc.getTertiaryColor().toArgb()});
        if (!seedPreview && Ui.setSeed(this, seed)) { main.post(this::recreate); return; }
        if (coverPending) {
            // the system has the new wallpaper; give it a beat to draw, then drop our copy
            main.removeCallbacks(fadeCover);
            main.postDelayed(fadeCover, 450);
        }
        updateTopScrim();
        updateDrawerScrim();
        updateBars();
        loadBlur();
    }

    // ================================================================ wallpaper cover

    private final Runnable fadeCover = () -> {
        coverPending = false;
        coverView.animate().alpha(0f).setDuration(220).withEndAction(() -> {
            coverView.setBitmap(null);
            coverView.setVisibility(View.INVISIBLE);
        }).start();
    };

    private final Walls.Cover cover = new Walls.Cover() {
        @Override public void show(android.graphics.Bitmap next, Runnable ready) {
            main.post(() -> {
                if (isDestroyed()) { ready.run(); return; }
                main.removeCallbacks(fadeCover);
                coverView.animate().cancel();
                coverView.setBitmap(next);
                coverView.setVisibility(View.VISIBLE);
                coverView.setAlpha(0f);
                // a soft cross-fade from the old wallpaper to the new one
                coverView.animate().alpha(1f).setDuration(260).setInterpolator(Ui.STANDARD).withEndAction(ready).start();
            });
        }

        @Override public void settle() {
            main.post(() -> {
                coverPending = true;
                main.removeCallbacks(fadeCover);
                main.postDelayed(fadeCover, 1800); // fallback if no colour callback arrives
            });
        }
    };

    // ================================================================ weather

    private static final int REQ_LOCATION = 7;

    private void showWeather() {
        if (!Weather.hasPermission(this)) {
            if (Weather.prefs(this).getBoolean("asked", false)) { weatherChip.setVisibility(View.GONE); return; }
            weatherGlyph.set(Weather.PARTLY, true);
            weatherText.setText("Weather");
            weatherChip.setAlpha(0.75f);
            weatherChip.setVisibility(View.VISIBLE);
            return;
        }
        weatherChip.setAlpha(1f);
        Weather.Now n = Weather.cached(this);
        if (n != null) {
            weatherGlyph.set(n.kind, n.day);
            weatherText.setText(n.tempC + "°C");
            weatherChip.setVisibility(View.VISIBLE);
        }
        if (Weather.stale(this)) refreshWeather();
    }

    private void refreshWeather() {
        if (weatherLoading || !Weather.hasPermission(this)) return;
        weatherLoading = true;
        final Context app = getApplicationContext();
        Weather.locate(app, wallWorker, loc -> wallWorker.execute(() -> {
            try {
                if (loc != null) Weather.fetch(app, loc.getLatitude(), loc.getLongitude());
            } catch (Exception ignored) {
                // offline or rate-limited: keep showing the cached reading
            }
            main.post(() -> {
                weatherLoading = false;
                if (isDestroyed()) return;
                Weather.Now n = Weather.cached(app);
                if (n == null) return;
                weatherGlyph.set(n.kind, n.day);
                weatherText.setText(n.tempC + "°C");
                if (weatherChip.getVisibility() != View.VISIBLE) {
                    weatherChip.setAlpha(0f);
                    weatherChip.setVisibility(View.VISIBLE);
                    weatherChip.animate().alpha(1f).setDuration(250).start();
                }
            });
        }));
    }

    private void onWeatherTap(View v) {
        if (!Weather.hasPermission(this)) {
            Weather.prefs(this).edit().putBoolean("asked", true).apply();
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        for (String pkg : new String[]{"com.hihonor.android.totemweather", "com.google.android.apps.weather"}) {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) { startSafely(i, v); return; }
        }
        startSafely(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=weather")), v);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_LOCATION) return;
        if (Weather.hasPermission(this)) {
            weatherText.setText("…");
            refreshWeather();
        } else {
            weatherChip.animate().alpha(0f).setDuration(200).withEndAction(() -> weatherChip.setVisibility(View.GONE)).start();
        }
    }

    /** With a blur behind it the drawer needs only a light tint; without, a stronger scrim. */
    /** Shade behind the clock in proportion to how bright the top of the wallpaper is. */
    private void updateTopScrim() {
        float a;
        if (topLuma >= 0f) a = Math.max(0f, Math.min(1f, (topLuma - 0.08f) / 0.25f));
        else a = brightWall ? 1f : 0f;
        topScrim.animate().alpha(a).setDuration(400).start();
    }

    private void updateDrawerScrim() {
        boolean blur = blurView.hasBlur();
        if (blur) {
            // the blur is already darkened band by band for legible white text
            drawer.setBackground(null);
            blurView.setDim(1f);
        } else {
            drawer.setBackgroundColor(brightWall ? 0x8C000000 : 0x59000000);
        }
    }

    private void loadBlur() {
        final Context app = getApplicationContext();
        wallWorker.execute(() -> {
            final Walls.Blur b = Walls.loadBlur(app);
            main.post(() -> {
                if (isDestroyed()) return;
                blurView.setBitmap(b == null ? null : b.bitmap);
                Ui.frostSource = b == null ? null : b.bitmap; // frosted glass for menus and sheets
                topLuma = b == null ? -1f : b.topLuma;
                updateDrawerScrim();
                updateTopScrim();
            });
        });
    }

    /** Dark status icons only on a bright wallpaper with nothing dark on top of it. */
    private void updateBars() {
        boolean darkIcons = brightWall && overlays.isEmpty() && root.progress < 0.5f;
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (darkIcons) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        View d = getWindow().getDecorView();
        if (d.getSystemUiVisibility() != flags) d.setSystemUiVisibility(flags);
    }

    // ================================================================ apps

    private void loadApps(final String changedPkg) {
        final int gen = ++loadGen;
        final List<AppGridView.AppEntry> previous = new ArrayList<>(all);
        iconWorker.execute(() -> {
            PackageManager pm = getPackageManager();
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ris = pm.queryIntentActivities(q, 0);
            final List<AppGridView.AppEntry> list = new ArrayList<>();
            for (ResolveInfo ri : ris) {
                if (ri.activityInfo.packageName.equals(getPackageName())) continue;
                AppGridView.AppEntry e = new AppGridView.AppEntry();
                e.label = String.valueOf(ri.loadLabel(pm)).trim();
                e.lower = e.label.toLowerCase(Locale.getDefault());
                e.ai = ri.activityInfo;
                e.cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
                list.add(e);
            }
            Collections.sort(list, (a, b) -> a.lower.compareTo(b.lower));
            // keep icons already in memory, except for the package that just changed
            for (AppGridView.AppEntry e : list) {
                if (e.cn.getPackageName().equals(changedPkg)) continue;
                for (AppGridView.AppEntry old : previous)
                    if (old.cn.equals(e.cn)) { e.icon = old.icon; break; }
            }
            main.post(() -> {
                if (gen != loadGen) return;
                all.clear();
                all.addAll(list);
                CACHE.clear();
                CACHE.addAll(list);
                resolveDock();
                refreshDrawerList();
                dock.setItems(dockShown);
            });
            // dock first so the home screen fills in before the drawer
            List<AppGridView.AppEntry> order = new ArrayList<>(list);
            Collections.sort(order, (a, b) -> Boolean.compare(!inDefaultDock(b), !inDefaultDock(a)));
            Set<String> used = new HashSet<>();
            int n = 0;
            // light/dark switched: swap each icon for the other look as it's ready (the old one
            // stays on screen meanwhile, so nothing blinks)
            // (display size changed: same thing, the icons are redrawn at the new size)
            final boolean dark = Ui.dark;
            final boolean restyle = cacheDark != dark || cacheSize != iconPx;
            for (AppGridView.AppEntry e : order) {
                if (gen != loadGen) return; // aborted: the next pass still sees the old style and redoes it
                if (e.icon == null || restyle) e.icon = Icons.load(this, pm, e.ai, iconPx, used, dark);
                else Icons.markUsed(this, e.ai, iconPx, used);
                if (++n % 8 == 0) { grid.postInvalidate(); dock.postInvalidate(); }
            }
            grid.postInvalidate();
            dock.postInvalidate();
            if (gen == loadGen) {
                cacheDark = dark; // the whole pass is done: now the cache really is this style
                cacheSize = iconPx;
                Icons.prune(this, used);
            }
            main.post(this::warmDrawer);
        });
    }

    private boolean drawerWarmed;

    /**
     * Draw the drawer once, practically invisible, so the first real swipe-up doesn't pay for
     * rasterising every label and uploading the blur on its first frame.
     */
    private void warmDrawer() {
        if (drawerWarmed || isDestroyed() || root.progress != 0f || !overlays.isEmpty()) return;
        drawerWarmed = true;
        drawer.setTranslationY(0);
        drawer.setAlpha(0.004f);
        drawer.setVisibility(View.VISIBLE);
        if (blurView.hasBlur()) { blurView.setAlpha(0.004f); blurView.setVisibility(View.VISIBLE); }
        root.postOnAnimation(() -> root.postOnAnimation(() -> {
            if (root.progress == 0f) {
                drawer.setVisibility(View.INVISIBLE);
                blurView.setVisibility(View.INVISIBLE);
            }
        }));
    }

    private boolean inDefaultDock(AppGridView.AppEntry e) {
        String saved = prefs.getString("dock", "");
        return saved.contains(e.cn.getPackageName());
    }

    private void resolveDock() {
        String saved = prefs.getString("dock", null);
        String[] parts = saved == null ? null : saved.split(",", -1);
        for (int i = 0; i < DOCK_SLOTS; i++) {
            AppGridView.AppEntry e = null;
            if (parts != null && i < parts.length && !parts[i].isEmpty()) {
                e = byComponent(ComponentName.unflattenFromString(parts[i]));
            } else if (parts == null) {
                for (String pkg : DEFAULT_DOCK[i]) { e = byPackage(pkg); if (e != null) break; }
            }
            dockEntries[i] = e;
        }
        if (parts == null) saveDock();
        rebuildDockList();
    }

    private void rebuildDockList() {
        List<AppGridView.AppEntry> l = new ArrayList<>();
        for (AppGridView.AppEntry e : dockEntries) {
            if (e != null) { l.add(e); continue; }
            AppGridView.AppEntry blank = new AppGridView.AppEntry();
            blank.blank = true;
            blank.label = "";
            l.add(blank);
        }
        dockShown = l;
    }

    private void saveDock() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < DOCK_SLOTS; i++) {
            if (i > 0) sb.append(',');
            if (dockEntries[i] != null) sb.append(dockEntries[i].cn.flattenToString());
        }
        prefs.edit().putString("dock", sb.toString()).apply();
    }

    private AppGridView.AppEntry byPackage(String pkg) {
        for (AppGridView.AppEntry e : all) if (e.cn.getPackageName().equals(pkg)) return e;
        return null;
    }

    private AppGridView.AppEntry byComponent(ComponentName cn) {
        if (cn == null) return null;
        for (AppGridView.AppEntry e : all) if (e.cn.equals(cn)) return e;
        return null;
    }

    /** Launch with the app zooming out of its icon. */
    private void launch(AppGridView.AppEntry e, AppGridView from, int index) {
        if (e.blank || e.cn == null) return;
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(e.cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        Bundle opts = null;
        if (from != null && index >= 0) {
            float[] c = from.cellCenter(index);
            int s = Math.round(from.iconSizePx());
            opts = ActivityOptions.makeScaleUpAnimation(from, Math.round(c[0] - s / 2f), Math.round(c[1] - s / 2f), s, s).toBundle();
        }
        try { startActivity(i, opts); } catch (Exception ex) { snack("Couldn't open " + e.label); return; }
        if (root.progress > 0f) {
            if (searching) endSearch();
            root.postDelayed(() -> root.snapTo(0f), 350);
        }
    }

    private void startSafely(Intent i, View from) {
        try {
            Bundle o = from == null ? null
                    : ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.getWidth(), from.getHeight()).toBundle();
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), o);
        } catch (Exception ignored) {}
    }

    // ================================================================ menus & dock placing

    private void appMenu(final AppGridView.AppEntry e, float cx, float cy) {
        int[] loc = new int[2];
        grid.getLocationInWindow(loc);
        List<Popup.Btn> btns = new ArrayList<>();
        btns.add(new Popup.Btn(Ui.G_INFO, Popup.NORMAL, () -> appInfo(e)));
        btns.add(new Popup.Btn(Ui.G_HIDE, Popup.NORMAL, () -> setHidden(e, true)));
        btns.add(new Popup.Btn(Ui.G_TRASH, Popup.DANGER, () -> startSafely(
                new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + e.cn.getPackageName())), null)));
        Popup.Item primary = new Popup.Item("Add to dock", null, () -> startPlacing(e));
        primary.glyph = Ui.G_DOCK;
        primary.tone = Popup.ACCENT;
        new Popup(this, e.label, e.icon, btns, shortcutItems(e), primary, loc[0] + cx, loc[1] + cy, grid.iconSizePx()).open();
    }

    private void dockMenu(final AppGridView.AppEntry e, final int index, float cx, float cy) {
        if (e.blank) return;
        int[] loc = new int[2];
        dock.getLocationInWindow(loc);
        List<Popup.Btn> btns = new ArrayList<>();
        btns.add(new Popup.Btn(Ui.G_INFO, Popup.NORMAL, () -> appInfo(e)));
        Popup.Item primary = new Popup.Item("Remove from dock", null, () -> {
            dockEntries[index] = null;
            saveDock();
            rebuildDockList();
            dock.setItems(dockShown);
        });
        primary.glyph = Ui.G_MINUS;
        primary.tone = Popup.DANGER;
        new Popup(this, e.label, e.icon, btns, shortcutItems(e), primary, loc[0] + cx, loc[1] + cy, dock.iconSizePx()).open();
    }

    private static final ExecutorService shortcutWorker = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ConcurrentHashMap<ComponentName, Object[]> shortcutCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Called on finger-down over an icon: by the time a long-press fires, shortcuts are ready. */
    private void prefetchShortcuts(final AppGridView.AppEntry e) {
        if (e == null || e.blank || e.cn == null) return;
        Object[] hit = shortcutCache.get(e.cn);
        if (hit != null && android.os.SystemClock.uptimeMillis() - (Long) hit[0] < 60_000) return;
        shortcutWorker.execute(() -> shortcutCache.put(e.cn,
                new Object[]{android.os.SystemClock.uptimeMillis(), loadShortcuts(e)}));
    }

    @SuppressWarnings("unchecked")
    private List<Popup.Item> shortcutItems(AppGridView.AppEntry e) {
        Object[] hit = shortcutCache.get(e.cn);
        if (hit != null && android.os.SystemClock.uptimeMillis() - (Long) hit[0] < 60_000)
            return new ArrayList<>((List<Popup.Item>) hit[1]);
        List<Popup.Item> fresh = loadShortcuts(e);
        shortcutCache.put(e.cn, new Object[]{android.os.SystemClock.uptimeMillis(), fresh});
        return new ArrayList<>(fresh);
    }

    /** The app's own shortcuts (chats, new tab, ...). Only a default launcher may read them. */
    private List<Popup.Item> loadShortcuts(AppGridView.AppEntry e) {
        List<Popup.Item> out = new ArrayList<>();
        try {
            final android.content.pm.LauncherApps la = getSystemService(android.content.pm.LauncherApps.class);
            if (la == null || !la.hasShortcutHostPermission()) return out;
            android.content.pm.LauncherApps.ShortcutQuery q = new android.content.pm.LauncherApps.ShortcutQuery();
            q.setPackage(e.cn.getPackageName());
            q.setQueryFlags(android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                    | android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST);
            List<android.content.pm.ShortcutInfo> list = la.getShortcuts(q, android.os.Process.myUserHandle());
            if (list == null) return out;
            List<android.content.pm.ShortcutInfo> sorted = new ArrayList<>(list);
            // manifest shortcuts first (stable), then dynamic ones by the app's own rank
            Collections.sort(sorted, (x, y) -> x.isDeclaredInManifest() != y.isDeclaredInManifest()
                    ? (x.isDeclaredInManifest() ? -1 : 1) : Integer.compare(x.getRank(), y.getRank()));
            int dpi = getResources().getDisplayMetrics().densityDpi;
            Set<String> seen = new HashSet<>();
            for (final android.content.pm.ShortcutInfo si : sorted) {
                if (out.size() == 4) break;
                if (!si.isEnabled()) continue;
                // only shortcuts for the icon that was pressed, and no repeated names
                if (si.getActivity() != null && !si.getActivity().equals(e.cn)) continue;
                CharSequence label = si.getShortLabel() != null ? si.getShortLabel() : si.getLongLabel();
                if (label == null || !seen.add(label.toString().trim().toLowerCase(Locale.ROOT))) continue;
                android.graphics.drawable.Drawable icon = null;
                try {
                    android.graphics.drawable.Drawable raw = la.getShortcutIconDrawable(si, dpi);
                    if (raw != null) icon = Ui.circle(this, raw, dpi(32));
                } catch (Exception ignored) {}
                out.add(new Popup.Item(label.toString(), icon, () -> {
                    try { la.startShortcut(si, null, null); }
                    catch (Exception ex) { snack("Couldn't open that shortcut"); }
                }));
            }
        } catch (Exception ignored) {}
        return out;
    }

    // ================================================================ hidden apps

    private Set<String> hiddenSet() { return new HashSet<>(prefs.getStringSet("hidden", new HashSet<>())); }

    private void refreshDrawerList() {
        Set<String> hidden = hiddenSet();
        List<AppGridView.AppEntry> l = new ArrayList<>();
        for (AppGridView.AppEntry e : all) if (!hidden.contains(e.cn.flattenToString())) l.add(e);
        drawerApps = l;
        // no pill in the drawer: hidden apps show up in search, and "hidden" lists them all
        grid.setFooter(null);
        if (searching) filter(search.getText().toString());
        else { shown = drawerApps; grid.setItems(drawerApps); }
    }

    private void setHidden(AppGridView.AppEntry e, boolean hide) {
        Set<String> s = hiddenSet();
        if (hide) s.add(e.cn.flattenToString()); else s.remove(e.cn.flattenToString());
        prefs.edit().putStringSet("hidden", s).apply();
        refreshDrawerList();
        updateHeader();
        snack(hide ? e.label + " hidden. Search \u201Chidden\u201D to see hidden apps." : e.label + " is back in the drawer");
    }

    private void hiddenMenu(float cx, float cy) {
        int[] loc = new int[2];
        grid.getLocationInWindow(loc);
        hiddenMenuAt(loc[0] + cx, loc[1] + cy);
    }

    private void hiddenMenuAt(float x, float y) {
        Set<String> hidden = hiddenSet();
        List<Popup.Item> items = new ArrayList<>();
        for (final AppGridView.AppEntry e : all) {
            if (!hidden.contains(e.cn.flattenToString())) continue;
            android.graphics.drawable.Drawable icon = e.icon == null ? null
                    : new android.graphics.drawable.BitmapDrawable(getResources(), e.icon);
            items.add(new Popup.Item(e.label, icon, () -> setHidden(e, false)));
        }
        if (items.isEmpty()) return;
        new Popup(this, "Hidden apps", null, new ArrayList<>(), items, null, x, y, 0).open();
    }

    private void appInfo(AppGridView.AppEntry e) {
        startSafely(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + e.cn.getPackageName())), null);
    }

    private void startPlacing(AppGridView.AppEntry e) {
        placing = e;
        if (searching) endSearch();
        root.animateTo(0f);
        dock.setSlotOutlines(true);
        snack("Tap a spot in the dock for " + e.label, 0);
    }

    private void placeAt(int index) {
        AppGridView.AppEntry e = placing;
        // if it's already in the dock, move it rather than duplicate it
        for (int i = 0; i < DOCK_SLOTS; i++) if (dockEntries[i] == e) dockEntries[i] = null;
        dockEntries[index] = e;
        saveDock();
        rebuildDockList();
        dock.setItems(dockShown);
        dock.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        cancelPlacing();
    }

    private void cancelPlacing() {
        if (placing == null) return;
        placing = null;
        dock.setSlotOutlines(false);
        hideSnack();
    }

    /** StatusBarManager.expandNotificationsPanel is hidden API, but launchers have used it for years. */
    @SuppressWarnings("JavaReflectionMemberAccess")
    private void expandNotifications(boolean notifications) {
        if (PanelService.openSide(notifications)) return;
        expandNotifications();
    }

    private void expandNotifications() {
        // our own notifications page when the panel service is on, otherwise the system shade
        if (PanelService.openNotifications()) return;
        try {
            Object sb = getSystemService("statusbar");
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb);
            root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        } catch (Throwable t) {
            Diag.log("couldn't open the notification shade: " + t);
        }
    }

    // ================================================================ drawer header

    /** A greeting for the time of day, with a quiet live line under it. */
    private void updateHeader() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String greet = h >= 5 && h < 12 ? "Good morning" : h >= 12 && h < 17 ? "Good afternoon"
                : h >= 17 && h < 22 ? "Good evening" : "Late night";
        int unread = 0;
        for (Integer n : NotifService.counts.values()) unread += n;
        StringBuilder sub = new StringBuilder();
        sub.append(drawerApps.size()).append(" apps");
        if (unread > 0) sub.append("  ·  ").append(unread).append(unread == 1 ? " notification" : " notifications");
        Weather.Now w = Weather.cached(this);
        if (w != null) sub.append("  ·  ").append(w.tempC).append("°C");
        android.text.SpannableString s = new android.text.SpannableString(greet + '\n' + sub);
        int from = greet.length() + 1;
        s.setSpan(new android.text.style.RelativeSizeSpan(0.5f), from, s.length(), 0);
        s.setSpan(new android.text.style.ForegroundColorSpan(0xB3FFFFFF), from, s.length(), 0);
        s.setSpan(new android.text.style.TypefaceSpan("sans-serif"), from, s.length(), 0);
        title.setText(s);
    }

    // ================================================================ search

    /** EditText is one of the heaviest stock views; it's only created when search is used. */
    private void ensureSearch() {
        if (search != null) return;
        search = new EditText(this);
        search.setHint("Search apps, contacts, maths");
        search.setHintTextColor(0x99FFFFFF);
        search.setTextColor(Color.WHITE);
        search.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_GO);
        search.setBackground(Ui.round(0x26FFFFFF, dpi(24)));
        search.setPadding(dpi(20), 0, dpi(20), 0);
        search.setVisibility(View.GONE);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) { filter(s.toString()); }
            public void afterTextChanged(Editable s) {}
        });
        search.setOnEditorActionListener((v, action, ev) -> {
            if (!shown.isEmpty()) launch(shown.get(0), grid, 0);
            else extras.runDefault();
            return true;
        });
        drawer.addView(search, new FrameLayout.LayoutParams(-1, dpi(48), Gravity.TOP));
        extras = new SearchExtras(this, new SearchExtras.Host() {
            @Override public void start(Intent i, View from) { startSafely(i, from); }
            @Override public void snack(String msg) { MainActivity.this.snack(msg); }
            @Override public void onExtrasHeight(int px) { setGridTop(px == 0 ? 0 : px + dpi(12)); }
            @Override public int hiddenCount() { return hiddenSet().size(); }
            @Override public void openHidden(View from) {
                int[] loc = new int[2];
                from.getLocationInWindow(loc);
                hiddenMenuAt(loc[0] + from.getWidth() / 2f, loc[1] + from.getHeight());
            }
        });
        drawer.addView(extras, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        placeSearch();
    }

    private void placeSearch() {
        FrameLayout.LayoutParams sp = (FrameLayout.LayoutParams) search.getLayoutParams();
        sp.topMargin = insetTop + dpi(14);
        sp.leftMargin = dpi(16);
        sp.rightMargin = dpi(16);
        search.setLayoutParams(sp);
        FrameLayout.LayoutParams ep = (FrameLayout.LayoutParams) extras.getLayoutParams();
        ep.topMargin = sp.topMargin + dpi(48 + 12);
        ep.leftMargin = ep.rightMargin = dpi(16);
        extras.setLayoutParams(ep);
    }

    /** Push the app grid down to make room for the search extras (0 = normal position). */
    private void setGridTop(int extra) {
        FrameLayout.LayoutParams gp = (FrameLayout.LayoutParams) grid.getLayoutParams();
        int top = insetTop + dpi(92) + extra;
        if (gp.topMargin == top) return;
        // the apps glide down to make room for the search extras (and back up): the layout
        // moves at once, the grid's translation carries it smoothly from where it was
        float from = gp.topMargin - top + grid.getTranslationY();
        gp.topMargin = top;
        grid.setLayoutParams(gp);
        grid.animate().cancel();
        if (searching && drawer.getVisibility() == View.VISIBLE) {
            grid.setTranslationY(from);
            grid.animate().translationY(0f).setDuration(200).setInterpolator(Ui.EMPHASIZED).start();
        } else {
            grid.setTranslationY(0f);
        }
    }

    private void beginSearch() {
        ensureSearch();
        searching = true;
        title.animate().alpha(0f).setDuration(120).withEndAction(() -> title.setVisibility(View.INVISIBLE)).start();
        btnSearch.setVisibility(View.GONE);
        search.setText("");
        search.setVisibility(View.VISIBLE);
        search.setAlpha(0f);
        search.animate().alpha(1f).setDuration(160).start();
        search.requestFocus();
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(search, 0);
    }

    private void endSearch() {
        searching = false;
        if (search != null) {
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(), 0);
            search.setVisibility(View.GONE);
        }
        if (extras != null) extras.update("");
        title.setVisibility(View.VISIBLE);
        title.setAlpha(1f);
        btnSearch.setVisibility(View.VISIBLE);
        shown = drawerApps;
        grid.setItems(drawerApps);
    }

    private void filter(String q) {
        if (extras != null) extras.update(q);
        q = q.trim().toLowerCase(Locale.getDefault());
        if (q.isEmpty()) { shown = drawerApps; grid.setItems(drawerApps); return; }
        // prefix matches (on any word) first, then anything containing the text
        List<AppGridView.AppEntry> starts = new ArrayList<>(), contains = new ArrayList<>();
        for (AppGridView.AppEntry e : all) {
            if (e.lower.startsWith(q) || e.lower.contains(" " + q)) starts.add(e);
            else if (e.lower.contains(q)) contains.add(e);
            // "whatsapp" should still find "WA Business" (com.whatsapp.w4b)
            else if (q.length() >= 3 && e.cn.getPackageName().contains(q)) contains.add(e);
        }
        starts.addAll(contains);
        shown = starts;
        grid.setItems(starts);
    }

    // ================================================================ snack

    private final Runnable hideSnackLater = this::hideSnack;

    @Override public void snack(String msg) { snack(msg, 2400); }

    private void snack(String msg, long ms) {
        if (snackView == null) {
            snackView = Ui.text(this, "", 14, Ui.FG, true);
            snackView.setBackground(Ui.frost(snackView, dpi(22)));
            snackView.setPadding(dpi(18), dpi(13), dpi(18), dpi(13));
            snackView.setGravity(Gravity.CENTER);
        }
        if (snackView.getParent() == null) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lp.bottomMargin = dockBottomMargin() + dpi(DOCK_HEIGHT_DP + 16);
            lp.leftMargin = lp.rightMargin = dpi(24);
            root.addView(snackView, lp);
        } else {
            snackView.bringToFront();
        }
        snackView.setText(msg);
        snackView.removeCallbacks(hideSnackLater);
        snackView.setAlpha(0f);
        snackView.setTranslationY(dpi(12));
        snackView.animate().alpha(1f).translationY(0).setDuration(220).setInterpolator(Ui.EMPHASIZED).start();
        if (ms > 0) snackView.postDelayed(hideSnackLater, ms);
    }

    private void hideSnack() {
        if (snackView == null || snackView.getParent() == null) return;
        snackView.animate().alpha(0f).translationY(dpi(8)).setDuration(180)
                .withEndAction(() -> { if (snackView.getAlpha() == 0f) root.removeView(snackView); }).start();
    }

    // ================================================================ WallpaperUi.Host

    @Override public Context context() { return this; }
    @Override public FrameLayout overlayParent() { return root; }
    @Override public int insetTop() { return insetTop; }
    @Override public int insetBottom() { return insetBottom; }
    @Override public ExecutorService wallWorker() { return wallWorker; }
    @Override public List<AppGridView.AppEntry> dockItems() { return dockShown; }
    @Override public int dockBottomMargin() { return Math.max(insetBottom, dpi(8)) + dpi(4) - dpi(25); }
    @Override public int wallpaperColor() { return wallColor; }
    @Override public void runOnUi(Runnable r) { main.post(r); }

    @Override public void overlayOpened(WallpaperUi.Overlay o) {
        overlays.add(o);
        if (snackView != null && snackView.getParent() != null) snackView.bringToFront();
        updateBars();
    }

    @Override public void overlayClosed(WallpaperUi.Overlay o) {
        overlays.remove(o);
        updateBars();
    }

    @Override public void closeAllOverlays() {
        for (int i = overlays.size() - 1; i >= 0; i--) overlays.get(i).close();
    }

    private WallpaperUi.Sheet sheet;

    private WallpaperUi.Sheet sheet() {
        if (sheet == null) {
            sheet = new WallpaperUi.Sheet(this);
            sheet.prepare();
        }
        return sheet;
    }

    private void openWallpaper() {
        if (!overlays.isEmpty()) return;
        cancelPlacing();
        sheet().open();
    }

    // ================================================================ popup menu

    /**
     * The long-press card: app icon and name with round action buttons on top, the app's
     * own shortcuts (round icons) in the middle, and one full-width action at the bottom.
     */
    static final class Popup extends WallpaperUi.Overlay {
        static final int NORMAL = 0, ACCENT = 1, DANGER = 2;

        static final class Item {
            final String label;
            final android.graphics.drawable.Drawable icon;
            final Runnable action;
            int glyph = -1, tone = NORMAL;

            Item(String label, android.graphics.drawable.Drawable icon, Runnable action) {
                this.label = label; this.icon = icon; this.action = action;
            }
        }

        static final class Btn {
            final int glyph, tone;
            final Runnable action;
            Btn(int glyph, int tone, Runnable action) { this.glyph = glyph; this.tone = tone; this.action = action; }
        }

        private final LinearLayout card;
        private final View dim;

        Popup(WallpaperUi.Host h, String heading, android.graphics.Bitmap appIcon, List<Btn> btns, List<Item> items,
              Item primary, final float ax, final float ay, final float iconSize) {
            super(h);
            Context c = getContext();
            dim = new View(c);
            dim.setBackgroundColor(0x40000000);
            dim.setAlpha(0f);
            dim.setOnClickListener(v -> close());
            addView(dim, new FrameLayout.LayoutParams(-1, -1));

            card = new LinearLayout(c) {
                @Override public boolean hasOverlappingRendering() { return false; }
            };
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(0, Ui.dpi(c, 6), 0, Ui.dpi(c, 10));
            card.setBackground(Ui.frost(card, Ui.dp(c, 28)));
            card.setClipToOutline(true);

            // header: icon + name + round buttons
            LinearLayout head = new LinearLayout(c);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(Ui.dpi(c, 14), Ui.dpi(c, 8), Ui.dpi(c, 10), Ui.dpi(c, 8));
            if (appIcon != null) {
                android.widget.ImageView iv = new android.widget.ImageView(c);
                iv.setImageBitmap(appIcon);
                LinearLayout.LayoutParams ip = Ui.lp(Ui.dpi(c, 40), Ui.dpi(c, 40));
                ip.rightMargin = Ui.dpi(c, 10);
                head.addView(iv, ip);
            }
            TextView name = Ui.text(c, heading, 16.5f, Ui.FG, true);
            name.setTypeface(Ui.font(c, 600));
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            head.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            for (final Btn b : btns) {
                Ui.Glyph g = new Ui.Glyph(c, b.glyph, b.tone == DANGER ? Ui.DANGER : Ui.FG);
                g.setBackground(Ui.round(b.tone == DANGER ? Ui.DANGER_BG : Ui.FILL, Ui.dp(c, 20)));
                g.setPadding(Ui.dpi(c, 10), Ui.dpi(c, 10), Ui.dpi(c, 10), Ui.dpi(c, 10));
                g.setClickable(true);
                Ui.pressable(g);
                g.setOnClickListener(v -> { close(); b.action.run(); });
                LinearLayout.LayoutParams bp = Ui.lp(Ui.dpi(c, 40), Ui.dpi(c, 40));
                bp.leftMargin = Ui.dpi(c, 6);
                head.addView(g, bp);
            }
            card.addView(head, Ui.lp(-1, -2));

            // shortcuts / list rows
            if (!items.isEmpty()) {
                View line = new View(c);
                line.setBackgroundColor(Ui.LINE);
                LinearLayout.LayoutParams dl = Ui.lp(-1, Math.max(1, Ui.dpi(c, 1)));
                dl.leftMargin = dl.rightMargin = Ui.dpi(c, 16);
                dl.bottomMargin = Ui.dpi(c, 4);
                card.addView(line, dl);
            }
            for (final Item it : items) {
                LinearLayout row = new LinearLayout(c);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Ui.dpi(c, 12), 0, Ui.dpi(c, 14), 0);
                row.setClickable(true);
                row.setBackground(Ui.round(0x00000000, Ui.dp(c, 16)));
                if (it.icon != null) {
                    android.widget.ImageView iv = new android.widget.ImageView(c);
                    iv.setImageDrawable(it.icon);
                    LinearLayout.LayoutParams ip = Ui.lp(Ui.dpi(c, 32), Ui.dpi(c, 32));
                    ip.rightMargin = Ui.dpi(c, 12);
                    row.addView(iv, ip);
                }
                TextView t = Ui.text(c, it.label, 15.5f, Ui.FG, false);
                t.setSingleLine(true);
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
                row.setOnTouchListener((v, ev) -> {
                    if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) v.setBackground(Ui.round(Ui.PRESS, Ui.dp(c, 16)));
                    else if (ev.getActionMasked() == MotionEvent.ACTION_UP || ev.getActionMasked() == MotionEvent.ACTION_CANCEL)
                        v.setBackground(Ui.round(0x00000000, Ui.dp(c, 16)));
                    return false;
                });
                row.setOnClickListener(v -> { close(); it.action.run(); });
                LinearLayout.LayoutParams rp = Ui.lp(-1, Ui.dpi(c, 50));
                rp.leftMargin = rp.rightMargin = Ui.dpi(c, 6);
                card.addView(row, rp);
            }

            // one full-width action
            if (primary != null) {
                LinearLayout pill = new LinearLayout(c);
                pill.setGravity(Gravity.CENTER);
                int bg = primary.tone == DANGER ? Ui.DANGER_BG : primary.tone == ACCENT ? Ui.ACCENT : Ui.FILL;
                int fg = primary.tone == DANGER ? Ui.DANGER : primary.tone == ACCENT ? Color.WHITE : Ui.FG;
                pill.setBackground(Ui.round(bg, Ui.dp(c, 23)));
                if (primary.glyph >= 0) {
                    pill.addView(new Ui.Glyph(c, primary.glyph, fg), Ui.lp(Ui.dpi(c, 20), Ui.dpi(c, 20)));
                }
                TextView pt = Ui.text(c, primary.label, 15, fg, true);
                pt.setTypeface(Ui.font(c, 600));
                LinearLayout.LayoutParams ptp = Ui.lp(-2, -2);
                ptp.leftMargin = Ui.dpi(c, 8);
                pill.addView(pt, ptp);
                pill.setClickable(true);
                Ui.pressable(pill);
                pill.setOnClickListener(v -> { close(); primary.action.run(); });
                LinearLayout.LayoutParams pp = Ui.lp(-1, Ui.dpi(c, 46), Ui.dpi(c, items.isEmpty() ? 4 : 8));
                pp.leftMargin = pp.rightMargin = Ui.dpi(c, 12);
                card.addView(pill, pp);
            }

            int w = Ui.dpi(c, 280);
            card.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
            int ch = card.getMeasuredHeight();
            int screenW = c.getResources().getDisplayMetrics().widthPixels;
            int screenH = c.getResources().getDisplayMetrics().heightPixels;
            int margin = Ui.dpi(c, 12);
            float gap = iconSize / 2f + Ui.dp(c, 14);
            boolean above = ay - gap - ch > h.insetTop() + margin;
            int x = Math.round(Math.max(margin, Math.min(screenW - margin - w, ax - w / 2f)));
            int y = Math.round(above ? ay - gap - ch : ay + gap);
            y = Math.max(h.insetTop() + margin, Math.min(screenH - h.insetBottom() - margin - ch, y));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, -2, Gravity.TOP | Gravity.START);
            lp.leftMargin = x;
            lp.topMargin = y;
            addView(card, lp);
            card.setPivotX(Math.max(0, Math.min(w, ax - x)));
            card.setPivotY(above ? ch : 0);
        }

        @Override void animateIn() {
            card.setAlpha(0f);
            card.setScaleX(0.86f);
            card.setScaleY(0.86f);
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170).setInterpolator(Ui.EMPHASIZED).start();
            dim.animate().alpha(1f).setDuration(180).start();
        }

        @Override void animateOut(Runnable done) {
            // Closing comes after an idle moment, when the GPU has clocked down; fading the card
            // then cost ~20 ms frames. Dropping it instantly feels snappier and stays in budget.
            card.setVisibility(INVISIBLE);
            dim.animate().alpha(0f).setDuration(120).withEndAction(done).start();
        }
    }

    // ================================================================ gestures

    final class Root extends FrameLayout {
        float progress; // 0 closed .. 1 open
        private float downX, downY, startProgress;
        private boolean dragging, homeLayered, barsDark = true, shadeFired;
        private VelocityTracker vt;
        private ValueAnimator anim;
        private final int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        private final Runnable homeLongPress = () -> {
            if (dragging || progress > 0f || !overlays.isEmpty()) return;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            openWallpaper();
        };

        Root(Context c) { super(c); }

        private float travel() { return Math.max(1, getHeight()); }

        // Home is static while it fades, so it rides a GPU layer. The drawer is not layered:
        // its rows cascade during the transition, and a layer would be redrawn every frame.
        private void beginLayers() {
            if (homeLayered) return;
            homeLayered = true;
            home.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }

        private void endLayers() {
            if (!homeLayered || dragging || (anim != null && anim.isRunning())) return;
            homeLayered = false;
            home.setLayerType(View.LAYER_TYPE_NONE, null);
        }

        void setProgress(float p) {
            progress = Math.max(0f, Math.min(1f, p));
            boolean half = progress >= 0.5f;
            if (half == barsDark) { barsDark = !half; updateBars(); }
            drawerOpen = half;
            grid.setReveal(progress);
            if (progress <= 0f) {
                drawer.setVisibility(View.INVISIBLE);
                blurView.setVisibility(View.INVISIBLE);
                home.setAlpha(1f);
                if (home.getVisibility() != View.VISIBLE) home.setVisibility(View.VISIBLE);
                home.setScaleX(1f);
                home.setScaleY(1f);
                return;
            }
            if (drawer.getVisibility() != View.VISIBLE) {
                updateHeader();
                drawer.setVisibility(View.VISIBLE);
            }
            if (blurView.hasBlur() && blurView.getVisibility() != View.VISIBLE) blurView.setVisibility(View.VISIBLE);
            // the sheet travels a third of the screen while the rows cascade in behind it
            float e = 1f - progress;
            drawer.setTranslationY(e * travel() * 0.33f);
            drawer.setAlpha(Math.min(1f, progress * 2.2f));
            blurView.setAlpha(Math.min(1f, progress * 1.6f));
            float ha = 1f - Math.min(1f, progress * 1.7f);
            home.setAlpha(ha);
            int hv = ha <= 0f ? View.INVISIBLE : View.VISIBLE;
            if (home.getVisibility() != hv) home.setVisibility(hv);
            float sc = 1f - 0.08f * progress;
            home.setScaleX(sc);
            home.setScaleY(sc);
        }

        void snapTo(float p) {
            if (anim != null) anim.cancel();
            setProgress(p);
            endLayers();
        }

        void animateTo(float target) { animateTo(target, 0f); }

        /** velocity: px/s toward the target. A flick finishes fast; a gentle release eases in. */
        void animateTo(final float target, float velocity) {
            if (anim != null) anim.cancel();
            if (target == 0f && searching) endSearch();
            float dist = Math.abs(target - progress);
            if (dist < 0.002f) { setProgress(target); endLayers(); return; }
            long dur = Math.round(130 + 170 * dist);
            if (velocity > 200f) {
                long byVel = Math.round(dist * travel() * 0.45f / velocity * 1000f * 1.6f);
                dur = Math.max(110, Math.min(dur, byVel));
            }
            beginLayers();
            anim = ValueAnimator.ofFloat(progress, target);
            anim.setDuration(dur);
            anim.setInterpolator(Ui.EMPHASIZED);
            anim.addUpdateListener(a -> setProgress((Float) a.getAnimatedValue()));
            anim.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator a) { post(Root.this::endLayers); }
            });
            anim.start();
        }

        private boolean animating() { return anim != null && anim.isRunning(); }

        @Override public boolean onInterceptTouchEvent(MotionEvent ev) {
            if (!overlays.isEmpty()) return false;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX(); downY = ev.getY();
                    dragging = false;
                    shadeFired = false;
                    if (vt != null) vt.recycle();
                    vt = VelocityTracker.obtain();
                    vt.addMovement(ev);
                    // Catch the drawer mid-flight: grab it where it is and follow the finger.
                    if (animating()) {
                        anim.cancel();
                        startProgress = progress;
                        dragging = true;
                        beginLayers();
                        return true;
                    }
                    startProgress = progress;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (vt != null) vt.addMovement(ev);
                    if (maybeStartDrag(ev)) return true;
                    break;
            }
            return false;
        }

        private boolean maybeStartDrag(MotionEvent ev) {
            if (placing != null) return false;
            float dy = ev.getY() - downY, dx = ev.getX() - downX;
            if (progress == 0f && dy > slop * 2 && Math.abs(dy) > Math.abs(dx) * 1.3f) {
                // home screen, pulling down: hand off to the notification shade, once per gesture
                if (!shadeFired) {
                    shadeFired = true;
                    removeCallbacks(homeLongPress);
                    // starting near the top: the same left/right rule as the status bar; lower: notifications
                    boolean top = downY < insetTop + dpi(160);
                    expandNotifications(!(top && downX > getWidth() * 0.45f));
                }
                return true;
            }
            if (Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx)) {
                boolean opening = progress < 0.5f && dy < 0;
                boolean closing = progress >= 0.5f && dy > 0 && grid.atTop() && !searching;
                if (opening || closing) {
                    dragging = true;
                    removeCallbacks(homeLongPress);
                    // start exactly where the finger is: no jump of `slop` pixels
                    downY = ev.getY() - Math.signum(dy) * slop * 0.5f;
                    startProgress = progress;
                    beginLayers();
                    return true;
                }
            }
            return false;
        }

        private long lastTapUp;
        private float lastTapX, lastTapY;

        /** Two quick taps on empty home space lock the phone. */
        private void checkDoubleTap(MotionEvent ev) {
            ViewConfiguration vc = ViewConfiguration.get(getContext());
            boolean tap = ev.getEventTime() - ev.getDownTime() < ViewConfiguration.getTapTimeout() * 3
                    && Math.hypot(ev.getX() - downX, ev.getY() - downY) < slop;
            if (!tap || placing != null) { lastTapUp = 0; return; }
            long now = ev.getEventTime();
            if (now - lastTapUp < ViewConfiguration.getDoubleTapTimeout() + 60
                    && Math.hypot(ev.getX() - lastTapX, ev.getY() - lastTapY) < vc.getScaledDoubleTapSlop()) {
                lastTapUp = 0;
                if (!PanelService.lockScreen()) snack("To lock with a double-tap, turn on Fast Launcher controls in Accessibility");
                return;
            }
            lastTapUp = now;
            lastTapX = ev.getX();
            lastTapY = ev.getY();
        }

        private float distanceFor(float dy) {
            // 1:1 with the finger over 45% of the screen: quick to complete, precise when slow
            return dy / (travel() * 0.45f);
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            if (!overlays.isEmpty()) return false;
            if (vt == null) vt = VelocityTracker.obtain();
            vt.addMovement(ev);
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX(); downY = ev.getY(); startProgress = progress;
                    shadeFired = false;
                    if (animating()) { anim.cancel(); dragging = true; beginLayers(); return true; }
                    if (placing != null) { cancelPlacing(); return true; }
                    if (progress == 0f) postDelayed(homeLongPress, ViewConfiguration.getLongPressTimeout() + 80);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging && (Math.abs(ev.getY() - downY) > slop || Math.abs(ev.getX() - downX) > slop))
                        removeCallbacks(homeLongPress);
                    if (!dragging) maybeStartDrag(ev);
                    if (dragging) setProgress(startProgress + distanceFor(downY - ev.getY()));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(homeLongPress);
                    if (!dragging && progress == 0f && ev.getActionMasked() == MotionEvent.ACTION_UP) checkDoubleTap(ev);
                    if (dragging) {
                        vt.computeCurrentVelocity(1000);
                        float vy = vt.getYVelocity();
                        float target;
                        if (vy < -500) target = 1f;
                        else if (vy > 500) target = 0f;
                        else target = progress > 0.5f ? 1f : 0f;
                        dragging = false;
                        animateTo(target, target == 1f ? -vy : vy);
                    } else if (progress > 0f && progress < 1f) {
                        animateTo(progress > 0.5f ? 1f : 0f);
                    }
                    dragging = false;
                    if (vt != null) { vt.recycle(); vt = null; }
                    return true;
            }
            return true;
        }
    }

    /** The current wallpaper, pre-blurred and tiny; scaled up it costs one textured quad. */
    static final class BlurView extends View {
        private android.graphics.Bitmap bmp;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        private final android.graphics.Matrix m = new android.graphics.Matrix();

        BlurView(Context c) { super(c); }

        @Override public boolean hasOverlappingRendering() { return false; }

        boolean hasBlur() { return bmp != null; }

        void setBitmap(android.graphics.Bitmap b) { bmp = b; invalidate(); }

        /** Darkens in the same draw call (a colour filter), so no extra full-screen pass. */
        void setDim(float f) {
            int v = Math.round(255 * f);
            paint.setColorFilter(f >= 1f ? null : new android.graphics.LightingColorFilter(Color.rgb(v, v, v), 0));
            invalidate();
        }

        @Override protected void onDraw(android.graphics.Canvas c) {
            if (bmp == null) return;
            float s = Math.max(getWidth() / (float) bmp.getWidth(), getHeight() / (float) bmp.getHeight());
            m.setScale(s, s);
            m.postTranslate((getWidth() - bmp.getWidth() * s) / 2f, (getHeight() - bmp.getHeight() * s) / 2f);
            c.drawBitmap(bmp, m, paint);
        }
    }
}
