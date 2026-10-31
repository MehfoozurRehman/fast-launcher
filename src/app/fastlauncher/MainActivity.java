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
    static final int CLOCK_TOP_DP = 100, DATE_TOP_DP = 204, DOCK_HEIGHT_DP = 190;
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
    private LinearLayout dateRow, weatherChip, chipRow;
    private TextView agenda;
    private SearchExtras extras;
    private Weather.Glyph weatherGlyph;
    private TextView weatherText;
    private boolean weatherLoading, coverPending;
    private TextView clock, date, title, snackView;
    private EditText search;
    private Ui.Glyph btnSearch;
    private AppGridView dock, grid;

    private static final List<AppGridView.AppEntry> CACHE = new ArrayList<>();

    private static boolean cacheDark = Ui.dark;
    private static int cacheSize;
    private final List<AppGridView.AppEntry> all = new ArrayList<>();
    private List<AppGridView.AppEntry> drawerApps = new ArrayList<>();
    private List<AppGridView.AppEntry> shown = drawerApps;
    private final AppGridView.AppEntry[] dockEntries = new AppGridView.AppEntry[DOCK_SLOTS];
    private List<AppGridView.AppEntry> dockShown = new ArrayList<>();

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

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Diag.install(this);
        Migrate.importIfFresh(this);
        if (HomeGuard.ACTION_SET_HOME.equals(getIntent().getAction())) HomeGuard.request(this, 77);
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

        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(debugReceiver, dbg, android.Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED);
        else registerReceiver(debugReceiver, dbg, android.Manifest.permission.DUMP, null);
        watchWallpaperColors();
        final Context appCtx = getApplicationContext();
        wallWorker.execute(() -> Walls.init(appCtx));
        loadBlur();
        loadApps(null);
    }

    private final BroadcastReceiver debugReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String what = i.getStringExtra("debug");
            if ("export".equals(what)) {
                final Context app = getApplicationContext();
                wallWorker.execute(() -> Migrate.export(app));
                return;
            }
            if ("report".equals(what)) {
                final Context app = getApplicationContext();
                wallWorker.execute(() -> {
                    try (java.io.FileWriter w = new java.io.FileWriter(new java.io.File(app.getExternalFilesDir(null), "report.txt"))) {
                        w.write(Diag.report(app));
                    } catch (Exception e) { Diag.log("report file: " + e); }
                });
                return;
            }
            if (what != null && what.startsWith("seed:")) {
                seedPreview = true;
                if (Ui.setSeed(MainActivity.this, Ui.seedFrom(new int[]{(int) Long.parseLong(what.substring(5), 16) | 0xFF000000}))) recreate();
                return;
            }
            if ("seedreset".equals(what)) {
                seedPreview = false;
                try { onWallColors(WallpaperManager.getInstance(MainActivity.this).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)); }
                catch (Exception ignored) {}
                return;
            }
            if ("gc".equals(what)) {
                for (int k = 0; k < 3; k++) { Runtime.getRuntime().gc(); System.runFinalization(); }
                return;
            }
            if (what != null) debugWallpaper(what);
        }
    };

    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);

        Ui.theme(this);

        android.content.res.Configuration was = builtConfig;
        if (builtDark != Ui.dark || was == null || was.fontScale != c.fontScale || was.densityDpi != c.densityDpi
                || !was.getLocales().equals(c.getLocales())) recreate();
    }

    private android.content.res.Configuration builtConfig;

    private boolean builtDark;
    private static boolean seedPreview;
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
        loadGen++;
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
        if (HomeGuard.ACTION_SET_HOME.equals(i.getAction())) { HomeGuard.request(this, 77); return; }
        if (!Intent.ACTION_MAIN.equals(i.getAction())) return;

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

    private void settleHome() {
        if (root == null) return;

        if (root.animating()) return;
        float p = root.progress;
        if (p > 0f && p < 1f) root.snapTo(p >= 0.5f ? 1f : 0f);
        else root.snapTo(p);
        if (root.progress == 0f) {
            home.setVisibility(View.VISIBLE);
            home.setAlpha(1f);
            home.setScaleX(1f);
            home.setScaleY(1f);
            home.setTranslationY(0f);
        }
        home.setLayerType(View.LAYER_TYPE_NONE, null);
        root.invalidate();
        home.invalidate();
        dock.invalidate();
        grid.invalidate();
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) settleHome();
    }

    private final NotifService.Changed notifChanged = this::pushBadges;

    private void pushBadges() {
        dock.setNotifCounts(NotifService.counts);
        grid.setNotifCounts(NotifService.counts);
    }

    @Override protected void onResume() {
        super.onResume();

        Ui.theme(this);
        if (builtDark != Ui.dark || builtAccent != Ui.ACCENT) { recreate(); return; }
        settleHome();
        NotifService.listener = notifChanged;
        HomeGuard.check(this);
        RefreshRate.apply(this);

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

    static TextView makeClock(Context c) {
        TextView t = new TextView(c);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 96);
        t.setTypeface(Ui.font(c, 700));
        t.setLetterSpacing(-0.04f);
        t.setIncludeFontPadding(false);
        t.setGravity(Gravity.CENTER);
        t.setShadowLayer(Ui.dp(c, 8), 0, Ui.dp(c, 2), 0x47000000);
        return t;
    }

    static TextView makeDate(Context c) {
        TextView t = new TextView(c);
        t.setTextColor(0xF2FFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        t.setTypeface(Ui.font(c, 650));
        t.setLetterSpacing(0.14f);
        t.setIncludeFontPadding(false);
        t.setGravity(Gravity.CENTER);
        t.setShadowLayer(Ui.dp(c, 4), 0, Ui.dp(c, 1), 0x40000000);
        return t;
    }

    static TextView makeChipText(Context c, float sp, int weight) {
        TextView t = new TextView(c);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sp);
        t.setTypeface(Ui.font(c, weight));
        t.setIncludeFontPadding(false);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    static AppGridView makeDock(Context c) { return new AppGridView(c, 5, 17, 90, 48, 8, 0, false, false); }

    static String clockText(Calendar c) {
        int h = c.get(Calendar.HOUR);
        if (h == 0) h = 12;
        return h + ":" + String.format(Locale.US, "%02d", c.get(Calendar.MINUTE));
    }

    static String dateText(Calendar c) {
        return new SimpleDateFormat("EEEE  ·  d MMMM", Locale.getDefault()).format(c.getTime()).toUpperCase(Locale.getDefault());
    }

    private int dpi(float v) { return Math.round(v * dp); }

    private void buildHome() {
        coverView = new BlurView(this);
        coverView.setVisibility(View.INVISIBLE);
        root.addView(coverView, new FrameLayout.LayoutParams(-1, -1));

        home = new FrameLayout(this);
        root.addView(home, new FrameLayout.LayoutParams(-1, -1));

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

        chipRow = new LinearLayout(this);
        chipRow.setGravity(Gravity.CENTER);
        home.addView(chipRow, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        weatherChip = new LinearLayout(this);
        weatherChip.setGravity(Gravity.CENTER_VERTICAL);
        weatherChip.setPadding(dpi(14), dpi(4), dpi(4), dpi(4));
        weatherGlyph = new Weather.Glyph(this);
        weatherChip.addView(weatherGlyph, new LinearLayout.LayoutParams(dpi(22), dpi(22)));
        weatherText = makeDate(this);
        LinearLayout.LayoutParams wt = new LinearLayout.LayoutParams(-2, -2);
        wt.leftMargin = dpi(5);
        weatherChip.addView(weatherText, wt);
        weatherChip.setClickable(true);
        weatherChip.setOnClickListener(v -> onWeatherTap(v));
        Ui.pressable(weatherChip);
        weatherChip.setVisibility(View.GONE);
        dateRow.addView(weatherChip);

        agenda = makeChipText(this, 15f, 600);
        agenda.setTextColor(0xE6FFFFFF);
        agenda.setShadowLayer(Ui.dp(this, 4), 0, Ui.dp(this, 1), 0x40000000);
        agenda.setSingleLine(true);
        agenda.setEllipsize(android.text.TextUtils.TruncateAt.END);
        agenda.setMaxWidth(dpi(300));
        agenda.setVisibility(View.GONE);
        agenda.setOnClickListener(v -> startSafely(agendaHasEvent
                ? Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
                : new Intent(AlarmClock.ACTION_SHOW_ALARMS), v));
        Ui.pressable(agenda);
        chipRow.addView(agenda, new LinearLayout.LayoutParams(-2, -2));

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

    private View dimView;

    private void buildDrawer() {
        dimView = new View(this);
        dimView.setBackgroundColor(Color.BLACK);
        dimView.setAlpha(0f);
        dimView.setVisibility(View.INVISIBLE);
        root.addView(dimView, new FrameLayout.LayoutParams(-1, -1));

        blurView = new BlurView(this);
        blurView.setVisibility(View.INVISIBLE);
        root.addView(blurView, new FrameLayout.LayoutParams(-1, -1));

        drawer = new FrameLayout(this) {
            @Override public boolean hasOverlappingRendering() { return false; }
        };
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
        dpm.topMargin = dpi(DATE_TOP_DP);
        dateRow.setLayoutParams(dpm);
        FrameLayout.LayoutParams chm = (FrameLayout.LayoutParams) chipRow.getLayoutParams();
        chm.topMargin = dpi(DATE_TOP_DP) + dpi(32);
        chipRow.setLayoutParams(chm);

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
        topLuma = -1f;

        int seed = Ui.seedFrom(new int[]{wc.getPrimaryColor().toArgb(),
                wc.getSecondaryColor() == null ? 0 : wc.getSecondaryColor().toArgb(),
                wc.getTertiaryColor() == null ? 0 : wc.getTertiaryColor().toArgb()});
        if (!seedPreview && Ui.setSeed(this, seed)) { main.post(this::recreate); return; }
        if (coverPending) {
            main.removeCallbacks(fadeCover);
            main.postDelayed(fadeCover, 450);
        }
        updateTopScrim();
        updateDrawerScrim();
        updateBars();
        loadBlur();
    }

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

                coverView.animate().alpha(1f).setDuration(260).setInterpolator(Ui.STANDARD).withEndAction(ready).start();
            });
        }

        @Override public void settle() {
            main.post(() -> {
                coverPending = true;
                main.removeCallbacks(fadeCover);
                main.postDelayed(fadeCover, 1800);
            });
        }
    };

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

    private void updateTopScrim() {
        float a;
        if (topLuma >= 0f) a = Math.max(0f, Math.min(1f, (topLuma - 0.08f) / 0.25f));
        else a = brightWall ? 1f : 0f;
        topScrim.animate().alpha(a).setDuration(400).start();
    }

    private void updateDrawerScrim() {
        boolean blur = blurView.hasBlur();
        if (blur) {
            drawer.setBackground(null);
            blurView.setDim(0.8f);
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
                Ui.frostSource = b == null ? null : b.bitmap;
                topLuma = b == null ? -1f : b.topLuma;
                updateDrawerScrim();
                updateTopScrim();
            });
        });
    }

    private void updateBars() {
        boolean darkIcons = brightWall && overlays.isEmpty() && root.progress < 0.5f;
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (darkIcons) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        View d = getWindow().getDecorView();
        if (d.getSystemUiVisibility() != flags) d.setSystemUiVisibility(flags);
    }

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

            List<AppGridView.AppEntry> order = new ArrayList<>(list);
            Collections.sort(order, (a, b) -> Boolean.compare(!inDefaultDock(b), !inDefaultDock(a)));
            Set<String> used = new HashSet<>();
            int n = 0;

            final boolean dark = Ui.dark;
            final boolean restyle = cacheDark != dark || cacheSize != iconPx;
            for (AppGridView.AppEntry e : order) {
                if (gen != loadGen) return;
                if (e.icon == null || restyle) e.icon = Icons.load(this, pm, e.ai, iconPx, used, dark);
                else Icons.markUsed(this, e.ai, iconPx, used);
                if (++n % 8 == 0) { grid.postInvalidate(); dock.postInvalidate(); }
            }
            grid.postInvalidate();
            dock.postInvalidate();
            if (gen == loadGen) {
                cacheDark = dark;
                cacheSize = iconPx;
                Icons.prune(this, used);
            }
            main.post(this::warmDrawer);
        });
    }

    private boolean drawerWarmed;

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

    private void appMenu(final AppGridView.AppEntry e, float cx, float cy) {
        int[] loc = new int[2];
        grid.getLocationInWindow(loc);
        List<Popup.Btn> btns = new ArrayList<>();
        btns.add(new Popup.Btn(Ui.G_DOCK, "Dock", Popup.NORMAL, () -> startPlacing(e)));
        btns.add(new Popup.Btn(Ui.G_INFO, "Info", Popup.NORMAL, () -> appInfo(e)));
        btns.add(new Popup.Btn(Ui.G_HIDE, "Hide", Popup.NORMAL, () -> setHidden(e, true)));
        btns.add(new Popup.Btn(Ui.G_TRASH, "Uninstall", Popup.DANGER, () -> startSafely(
                new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + e.cn.getPackageName())), null)));
        new Popup(this, e.label, e.icon, btns, shortcutItems(e), null, loc[0] + cx, loc[1] + cy, grid.iconSizePx()).open();
    }

    private void dockMenu(final AppGridView.AppEntry e, final int index, float cx, float cy) {
        if (e.blank) return;
        int[] loc = new int[2];
        dock.getLocationInWindow(loc);
        List<Popup.Btn> btns = new ArrayList<>();
        btns.add(new Popup.Btn(Ui.G_INFO, "Info", Popup.NORMAL, () -> appInfo(e)));
        btns.add(new Popup.Btn(Ui.G_MINUS, "Remove", Popup.DANGER, () -> {
            dockEntries[index] = null;
            saveDock();
            rebuildDockList();
            dock.setItems(dockShown);
        }));
        new Popup(this, e.label, e.icon, btns, shortcutItems(e), null, loc[0] + cx, loc[1] + cy, dock.iconSizePx()).open();
    }

    private static final ExecutorService shortcutWorker = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ConcurrentHashMap<ComponentName, Object[]> shortcutCache =
            new java.util.concurrent.ConcurrentHashMap<>();

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

            Collections.sort(sorted, (x, y) -> x.isDeclaredInManifest() != y.isDeclaredInManifest()
                    ? (x.isDeclaredInManifest() ? -1 : 1) : Integer.compare(x.getRank(), y.getRank()));
            int dpi = getResources().getDisplayMetrics().densityDpi;
            Set<String> seen = new HashSet<>();
            for (final android.content.pm.ShortcutInfo si : sorted) {
                if (out.size() == 4) break;
                if (!si.isEnabled()) continue;

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

    private Set<String> hiddenSet() { return new HashSet<>(prefs.getStringSet("hidden", new HashSet<>())); }

    private void refreshDrawerList() {
        Set<String> hidden = hiddenSet();
        List<AppGridView.AppEntry> l = new ArrayList<>();
        for (AppGridView.AppEntry e : all) if (!hidden.contains(e.cn.flattenToString())) l.add(e);
        drawerApps = l;

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

    @SuppressWarnings("JavaReflectionMemberAccess")
    private void expandNotifications() {
        try {
            Object sb = getSystemService("statusbar");
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb);
            root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        } catch (Throwable t) {
            Diag.log("couldn't open the notification shade: " + t);
        }
    }

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

    private void setGridTop(int extra) {
        FrameLayout.LayoutParams gp = (FrameLayout.LayoutParams) grid.getLayoutParams();
        int top = insetTop + dpi(92) + extra;
        if (gp.topMargin == top) return;

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

        List<AppGridView.AppEntry> starts = new ArrayList<>(), contains = new ArrayList<>();
        for (AppGridView.AppEntry e : all) {
            if (e.lower.startsWith(q) || e.lower.contains(" " + q)) starts.add(e);
            else if (e.lower.contains(q)) contains.add(e);

            else if (q.length() >= 3 && e.cn.getPackageName().contains(q)) contains.add(e);
        }
        starts.addAll(contains);
        shown = starts;
        grid.setItems(starts);
    }

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
            final String label;
            final Runnable action;
            Btn(int glyph, String label, int tone, Runnable action) { this.glyph = glyph; this.label = label; this.tone = tone; this.action = action; }
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
            int cp = Ui.dpi(c, 6);
            card.setPadding(cp, cp, cp, cp);

            card.setBackground(Ui.frost(card, Ui.dp(c, 24), Ui.dark ? 0x99000000 : 0xC8F5F6F8));
            card.setClipToOutline(true);

            if (appIcon == null && heading != null) {
                TextView name = Ui.text(c, heading, 15, Ui.FG, true);
                name.setTypeface(Ui.font(c, 600));
                name.setPadding(Ui.dpi(c, 12), Ui.dpi(c, 8), Ui.dpi(c, 12), Ui.dpi(c, 6));
                card.addView(name, Ui.lp(-1, -2));
            }

            for (final Item it : items) {
                LinearLayout row = new LinearLayout(c);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Ui.dpi(c, 10), 0, Ui.dpi(c, 12), 0);
                row.setClickable(true);
                row.setBackground(Ui.round(0x00000000, Ui.dp(c, 16)));
                if (it.icon != null) {
                    android.widget.ImageView iv = new android.widget.ImageView(c);
                    iv.setImageDrawable(it.icon);
                    LinearLayout.LayoutParams ip = Ui.lp(Ui.dpi(c, 26), Ui.dpi(c, 26));
                    ip.rightMargin = Ui.dpi(c, 12);
                    row.addView(iv, ip);
                }
                TextView t = Ui.text(c, it.label, 14.5f, Ui.FG, false);
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
                card.addView(row, Ui.lp(-1, Ui.dpi(c, 44)));
            }

            if (!btns.isEmpty()) {
                LinearLayout tray = new LinearLayout(c);
                tray.setBackground(Ui.round(Ui.FILL, Ui.dp(c, 18)));
                for (final Btn b : btns) {
                    LinearLayout cell = new LinearLayout(c);
                    cell.setOrientation(LinearLayout.VERTICAL);
                    cell.setGravity(Gravity.CENTER);
                    int fg = b.tone == DANGER ? Ui.DANGER : Ui.FG;
                    cell.addView(new Ui.Glyph(c, b.glyph, fg), Ui.lp(Ui.dpi(c, 20), Ui.dpi(c, 20)));
                    TextView cap = Ui.text(c, b.label, 11.5f, b.tone == DANGER ? Ui.DANGER : Ui.FG2, false);
                    cap.setSingleLine(true);
                    cell.addView(cap, Ui.lp(-2, -2, Ui.dpi(c, 5)));
                    cell.setClickable(true);
                    Ui.pressable(cell);
                    cell.setOnClickListener(v -> { close(); b.action.run(); });
                    tray.addView(cell, new LinearLayout.LayoutParams(0, Ui.dpi(c, 60), 1f));
                }
                card.addView(tray, Ui.lp(-1, -2, items.isEmpty() ? 0 : Ui.dpi(c, 6)));
            }

            if (primary != null) {
                LinearLayout pill = new LinearLayout(c);
                pill.setGravity(Gravity.CENTER);
                int bg = primary.tone == DANGER ? Ui.DANGER_BG : primary.tone == ACCENT ? Ui.ACCENT : Ui.FILL;
                int fg = primary.tone == DANGER ? Ui.DANGER : primary.tone == ACCENT ? Color.WHITE : Ui.FG;
                pill.setBackground(Ui.round(bg, Ui.dp(c, 18)));
                if (primary.glyph >= 0) pill.addView(new Ui.Glyph(c, primary.glyph, fg), Ui.lp(Ui.dpi(c, 18), Ui.dpi(c, 18)));
                TextView pt = Ui.text(c, primary.label, 14, fg, true);
                pt.setTypeface(Ui.font(c, 600));
                LinearLayout.LayoutParams ptp = Ui.lp(-2, -2);
                ptp.leftMargin = Ui.dpi(c, 8);
                pill.addView(pt, ptp);
                pill.setClickable(true);
                Ui.pressable(pill);
                pill.setOnClickListener(v -> { close(); primary.action.run(); });
                card.addView(pill, Ui.lp(-1, Ui.dpi(c, 42), Ui.dpi(c, 6)));
            }

            int w = Ui.dpi(c, btns.size() <= 2 && items.isEmpty() ? 160 : 236);
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
            card.setVisibility(INVISIBLE);
            dim.animate().alpha(0f).setDuration(120).withEndAction(done).start();
        }
    }

    final class Root extends FrameLayout {
        float progress;
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

        private void beginLayers() {
            if (homeLayered) return;
            homeLayered = true;
            home.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            drawer.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }

        private void endLayers() {
            if (!homeLayered || (anim != null && anim.isRunning())) return;
            if (dragging && hasWindowFocus()) return;
            homeLayered = false;
            home.setLayerType(View.LAYER_TYPE_NONE, null);
            drawer.setLayerType(View.LAYER_TYPE_NONE, null);
        }

        void setProgress(float p) {
            progress = Math.max(0f, Math.min(1f, p));
            boolean half = progress >= 0.5f;
            if (half == barsDark) { barsDark = !half; updateBars(); }
            drawerOpen = half;
            grid.setReveal(1f);
            if (progress <= 0f) {
                drawer.setVisibility(View.INVISIBLE);
                blurView.setVisibility(View.INVISIBLE);
                home.setAlpha(1f);
                if (home.getVisibility() != View.VISIBLE) home.setVisibility(View.VISIBLE);
                home.setScaleX(1f);
                home.setScaleY(1f);
                home.setTranslationY(0f);
                dock.setTranslationY(0f);
                dimView.setAlpha(0f);
                dimView.setVisibility(View.INVISIBLE);
                return;
            }
            if (drawer.getVisibility() != View.VISIBLE) {
                updateHeader();
                drawer.setVisibility(View.VISIBLE);
            }
            if (blurView.hasBlur() && blurView.getVisibility() != View.VISIBLE) blurView.setVisibility(View.VISIBLE);

            float act1 = span(progress, 0f, 0.42f), act2 = span(progress, 0.42f, 0.68f);
            layDrawer();
            drawer.setAlpha(act2);
            blurView.setAlpha(span(progress, 0.35f, 0.62f));
            int dv = progress < 0.62f ? View.VISIBLE : View.INVISIBLE;
            if (dimView.getVisibility() != dv) dimView.setVisibility(dv);
            dimView.setAlpha(0.3f * act1);
            if (!searching) {
                title.setAlpha(1f);
                btnSearch.setAlpha(1f);
            }

            float f = span(progress, 0f, 0.42f);
            float ha = 1f - f * f * (3f - 2f * f);
            home.setAlpha(ha);
            int hv = ha <= 0f ? View.INVISIBLE : View.VISIBLE;
            if (home.getVisibility() != hv) home.setVisibility(hv);
            home.setScaleX(1f);
            home.setScaleY(1f);
            home.setTranslationY(0f);
            dock.setTranslationY(0f);
        }

        private float span(float v, float from, float to) { return Math.max(0f, Math.min(1f, (v - from) / (to - from))); }

        private void layDrawer() {
            float rise = span(progress, 0.42f, 0.8f);
            drawer.setTranslationY((1f - rise) * (1f - rise) * 36 * dp - over);
        }

        private float over;
        private ValueAnimator overAnim;

        private void setOver(float px) {
            if (overAnim != null) overAnim.cancel();
            over = px;
            if (progress > 0f) layDrawer();
        }

        private void releaseOver() {
            if (over == 0f || (overAnim != null && overAnim.isRunning())) return;
            overAnim = ValueAnimator.ofFloat(over, 0f);
            overAnim.setDuration(320);
            overAnim.setInterpolator(Ui.EMPHASIZED);
            overAnim.addUpdateListener(a -> {
                over = (Float) a.getAnimatedValue();
                if (progress > 0f) layDrawer();
            });
            overAnim.start();
        }

        void snapTo(float p) {
            if (anim != null) anim.cancel();
            setOver(0f);
            setProgress(p);
            endLayers();
        }

        void animateTo(float target) { animateTo(target, 0f); }

        void animateTo(final float target, float velocity) {
            if (anim != null) anim.cancel();
            releaseOver();
            if (target == 0f && searching) endSearch();
            float dist = Math.abs(target - progress);
            if (dist < 0.002f) { setProgress(target); endLayers(); return; }
            long dur = Math.round(130 + 170 * dist);
            android.animation.TimeInterpolator curve = Ui.EMPHASIZED;
            if (velocity > 200f) {
                float px = dist * travel() * 0.45f;
                dur = Math.max(120, Math.min(420, Math.round(2f * px / velocity * 1000f)));
                curve = new android.view.animation.DecelerateInterpolator(1f);
            }
            beginLayers();
            anim = ValueAnimator.ofFloat(progress, target);
            anim.setDuration(dur);
            anim.setInterpolator(curve);
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
                if (!shadeFired) {
                    shadeFired = true;
                    removeCallbacks(homeLongPress);
                    expandNotifications();
                }
                return true;
            }
            if (Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx)) {
                boolean opening = progress < 0.5f && dy < 0;
                boolean closing = progress >= 0.5f && dy > 0 && grid.atTop() && !searching;
                if (opening || closing) {
                    dragging = true;
                    grid.setRevealOrigin(downX);
                    removeCallbacks(homeLongPress);

                    downY = ev.getY() - Math.signum(dy) * slop * 0.5f;
                    startProgress = progress;
                    beginLayers();
                    return true;
                }
            }
            return false;
        }

        private float distanceFor(float dy) {
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
                    if (dragging) {
                        float p = startProgress + distanceFor(downY - ev.getY());
                        setProgress(p);
                        setOver(p > 1f ? Ui.rubber((p - 1f) * travel() * 0.45f, dpi(70)) : 0f);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(homeLongPress);

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

    static final class BlurView extends View {
        private android.graphics.Bitmap bmp;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        private final android.graphics.Matrix m = new android.graphics.Matrix();

        BlurView(Context c) { super(c); }

        @Override public boolean hasOverlappingRendering() { return false; }

        boolean hasBlur() { return bmp != null; }

        void setBitmap(android.graphics.Bitmap b) { bmp = b; invalidate(); }

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
