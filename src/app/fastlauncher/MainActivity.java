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

    private Root root;
    private FrameLayout home, drawer;
    private View topScrim;
    private TextView clock, date, title, snackView;
    private EditText search;
    private Ui.Glyph btnSearch;
    private AppGridView dock, grid;

    private final List<AppGridView.AppEntry> all = new ArrayList<>();
    private List<AppGridView.AppEntry> shown = all;
    private final AppGridView.AppEntry[] dockEntries = new AppGridView.AppEntry[DOCK_SLOTS];
    private List<AppGridView.AppEntry> dockShown = new ArrayList<>();
    private final ExecutorService iconWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService wallWorker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private boolean drawerOpen, searching, brightWall;
    private int wallColor = 0xFF2A2C31;
    private int loadGen;
    private AppGridView.AppEntry placing;
    private final List<WallpaperUi.Overlay> overlays = new ArrayList<>();

    // ================================================================ lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        iconPx = Math.round(44 * dp);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);

        root = new Root(this);
        setContentView(root);
        buildHome();
        buildDrawer();
        root.setOnApplyWindowInsetsListener((v, in) -> {
            insetTop = in.getSystemWindowInsetTop();
            insetBottom = in.getSystemWindowInsetBottom();
            applyInsets();
            return in;
        });

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);

        watchWallpaperColors();
        Walls.init(this);
        loadApps(null);
    }

    @Override protected void onDestroy() {
        unregisterReceiver(pkgReceiver);
        iconWorker.shutdownNow();
        wallWorker.shutdownNow();
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
        if (drawerOpen) root.animateTo(0f);
    }

    private final NotifService.Changed notifChanged = this::pushBadges;

    private void pushBadges() {
        dock.setNotifCounts(NotifService.counts);
        grid.setNotifCounts(NotifService.counts);
    }

    @Override protected void onResume() {
        super.onResume();
        NotifService.listener = notifChanged;
        pushBadges();
        clock.removeCallbacks(tick);
        tick.run();
        // Backstop for the scheduled job, which OEM battery managers like to delay.
        if (Walls.due(this) && !Walls.isBusy()) {
            final Context app = getApplicationContext();
            wallWorker.execute(() -> { try { Walls.next(app, false); } catch (Exception ignored) {} });
        }
    }

    @Override protected void onPause() {
        if (NotifService.listener == notifChanged) NotifService.listener = null;
        clock.removeCallbacks(tick);
        super.onPause();
    }

    // ================================================================ shared view factories

    static TextView makeClock(Context c) {
        TextView t = new TextView(c);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 74);
        t.setTypeface(Ui.MEDIUM);
        t.setIncludeFontPadding(false);
        t.setGravity(Gravity.CENTER);
        t.setShadowLayer(Ui.dp(c, 6), 0, Ui.dp(c, 2), 0x40000000);
        return t;
    }

    static TextView makeDate(Context c) {
        TextView t = new TextView(c);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 21);
        t.setTypeface(Ui.MEDIUM);
        t.setIncludeFontPadding(false);
        t.setGravity(Gravity.CENTER);
        t.setShadowLayer(Ui.dp(c, 4), 0, Ui.dp(c, 1), 0x40000000);
        return t;
    }

    static AppGridView makeDock(Context c) { return new AppGridView(c, 5, 17, 90, 44, 10, 0, false, false); }

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
        home = new FrameLayout(this);
        root.addView(home, new FrameLayout.LayoutParams(-1, -1));

        // Only visible on bright wallpapers, to keep the white clock legible.
        topScrim = new View(this);
        topScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x55000000, 0x22000000, 0x00000000}));
        topScrim.setAlpha(0f);
        home.addView(topScrim, new FrameLayout.LayoutParams(-1, dpi(340), Gravity.TOP));

        clock = makeClock(this);
        home.addView(clock, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        clock.setOnClickListener(v -> startSafely(new Intent(AlarmClock.ACTION_SHOW_ALARMS), v));
        Ui.pressable(clock);

        date = makeDate(this);
        home.addView(date, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        date.setOnClickListener(v -> startSafely(
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR), v));
        Ui.pressable(date);

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
        });
        home.addView(dock, new FrameLayout.LayoutParams(-1, dpi(DOCK_HEIGHT_DP), Gravity.BOTTOM));
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Calendar c = Calendar.getInstance();
            clock.setText(clockText(c));
            date.setText(dateText(c));
            long now = System.currentTimeMillis();
            clock.postDelayed(this, 60000 - (now % 60000) + 50);
        }
    };

    private void buildDrawer() {
        drawer = new FrameLayout(this);
        drawer.setBackgroundColor(0x59000000);
        drawer.setVisibility(View.INVISIBLE);
        root.addView(drawer, new FrameLayout.LayoutParams(-1, -1));

        grid = new AppGridView(this, 5, 17, 97, 44, 12, 12, true, true);
        grid.setListener(new AppGridView.Listener() {
            @Override public void onTap(AppGridView.AppEntry e, int index) { launch(e, grid, index); }
            @Override public void onLongPress(AppGridView.AppEntry e, int index, float cx, float cy) {
                appMenu(e, cx, cy);
            }
        });
        drawer.addView(grid, new FrameLayout.LayoutParams(-1, -1, Gravity.TOP));

        title = new TextView(this);
        title.setText("All apps");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28);
        title.setTypeface(Ui.MEDIUM);
        title.setIncludeFontPadding(false);
        title.setShadowLayer(dpi(3), 0, dpi(1), 0x55000000);
        drawer.addView(title, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));

        search = new EditText(this);
        search.setHint("Search apps");
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
            return true;
        });
        drawer.addView(search, new FrameLayout.LayoutParams(-1, dpi(48), Gravity.TOP));

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
        FrameLayout.LayoutParams dpm = (FrameLayout.LayoutParams) date.getLayoutParams();
        dpm.topMargin = dpi(DATE_TOP_DP);
        date.setLayoutParams(dpm);

        FrameLayout.LayoutParams dk = (FrameLayout.LayoutParams) dock.getLayoutParams();
        dk.bottomMargin = dockBottomMargin();
        dock.setLayoutParams(dk);

        int headerTop = insetTop + dpi(14);
        FrameLayout.LayoutParams tp = (FrameLayout.LayoutParams) title.getLayoutParams();
        tp.topMargin = headerTop + dpi(8);
        tp.leftMargin = dpi(23);
        title.setLayoutParams(tp);
        FrameLayout.LayoutParams sp = (FrameLayout.LayoutParams) search.getLayoutParams();
        sp.topMargin = headerTop;
        sp.leftMargin = dpi(16);
        sp.rightMargin = dpi(16);
        search.setLayoutParams(sp);
        FrameLayout.LayoutParams bp = (FrameLayout.LayoutParams) btnSearch.getLayoutParams();
        bp.topMargin = headerTop;
        bp.rightMargin = dpi(14);
        btnSearch.setLayoutParams(bp);
        FrameLayout.LayoutParams gp = (FrameLayout.LayoutParams) grid.getLayoutParams();
        gp.topMargin = insetTop + dpi(74);
        grid.setLayoutParams(gp);
        grid.setBottomInset(insetBottom);
    }

    // ================================================================ wallpaper colours

    private void watchWallpaperColors() {
        if (Build.VERSION.SDK_INT < 27) return;
        WallpaperManager wm = WallpaperManager.getInstance(this);
        try { onWallColors(wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)); } catch (Exception ignored) {}
        wm.addOnColorsChangedListener((colors, which) -> {
            if ((which & WallpaperManager.FLAG_SYSTEM) != 0) onWallColors(colors);
        }, main);
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
        topScrim.animate().alpha(bright ? 1f : 0f).setDuration(400).start();
        drawer.setBackgroundColor(bright ? 0x8C000000 : 0x59000000);
        updateBars();
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
                resolveDock();
                if (searching) filter(search.getText().toString()); else { shown = all; grid.setItems(all); }
                dock.setItems(dockShown);
            });
            // dock first so the home screen fills in before the drawer
            List<AppGridView.AppEntry> order = new ArrayList<>(list);
            Collections.sort(order, (a, b) -> Boolean.compare(!inDefaultDock(b), !inDefaultDock(a)));
            Set<String> used = new HashSet<>();
            int n = 0;
            for (AppGridView.AppEntry e : order) {
                if (gen != loadGen) return;
                if (e.icon == null) e.icon = Icons.load(this, pm, e.ai, iconPx, used);
                else Icons.markUsed(this, e.ai, iconPx, used);
                if (++n % 8 == 0) { grid.postInvalidate(); dock.postInvalidate(); }
            }
            grid.postInvalidate();
            dock.postInvalidate();
            if (gen == loadGen) Icons.prune(this, used);
        });
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
        if (drawerOpen) {
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
        new Popup(this, e.label, new String[]{"Add to dock", "App info", "Uninstall"}, loc[0] + cx, loc[1] + cy,
                grid.iconSizePx(), which -> {
            if (which == 0) startPlacing(e);
            else if (which == 1) appInfo(e);
            else startSafely(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + e.cn.getPackageName())), null);
        }).open();
    }

    private void dockMenu(final AppGridView.AppEntry e, final int index, float cx, float cy) {
        if (e.blank) return;
        int[] loc = new int[2];
        dock.getLocationInWindow(loc);
        new Popup(this, e.label, new String[]{"Remove from dock", "App info"}, loc[0] + cx, loc[1] + cy,
                dock.iconSizePx(), which -> {
            if (which == 0) {
                dockEntries[index] = null;
                saveDock();
                rebuildDockList();
                dock.setItems(dockShown);
            } else appInfo(e);
        }).open();
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

    // ================================================================ search

    private void beginSearch() {
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
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.setVisibility(View.GONE);
        title.setVisibility(View.VISIBLE);
        title.setAlpha(1f);
        btnSearch.setVisibility(View.VISIBLE);
        shown = all;
        grid.setItems(all);
    }

    private void filter(String q) {
        q = q.trim().toLowerCase(Locale.getDefault());
        if (q.isEmpty()) { shown = all; grid.setItems(all); return; }
        // prefix matches (on any word) first, then anything containing the text
        List<AppGridView.AppEntry> starts = new ArrayList<>(), contains = new ArrayList<>();
        for (AppGridView.AppEntry e : all) {
            if (e.lower.startsWith(q) || e.lower.contains(" " + q)) starts.add(e);
            else if (e.lower.contains(q)) contains.add(e);
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
            snackView.setBackground(Ui.round(0xF0202226, dpi(22)));
            snackView.setPadding(dpi(18), dpi(13), dpi(18), dpi(13));
            snackView.setGravity(Gravity.CENTER);
            snackView.setElevation(dpi(6));
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

    private void openWallpaper() {
        if (!overlays.isEmpty()) return;
        cancelPlacing();
        new WallpaperUi.Sheet(this).open();
    }

    // ================================================================ popup menu

    interface OnItem { void onItem(int which); }

    /** A small card anchored to an icon, instead of a system dialog. */
    static final class Popup extends WallpaperUi.Overlay {
        private final LinearLayout card;
        private final View dim;

        Popup(WallpaperUi.Host h, String heading, String[] items, final float ax, final float ay, final float iconSize,
              final OnItem cb) {
            super(h);
            Context c = getContext();
            dim = new View(c);
            dim.setBackgroundColor(0x33000000);
            dim.setAlpha(0f);
            dim.setOnClickListener(v -> close());
            addView(dim, new FrameLayout.LayoutParams(-1, -1));

            card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Ui.round(0xF7202227, Ui.dp(c, 22)));
            card.setPadding(0, Ui.dpi(c, 8), 0, Ui.dpi(c, 8));
            card.setElevation(Ui.dp(c, 12));
            TextView head = Ui.text(c, heading, 12.5f, Ui.FG3, true);
            head.setPadding(Ui.dpi(c, 20), Ui.dpi(c, 8), Ui.dpi(c, 20), Ui.dpi(c, 6));
            head.setSingleLine(true);
            head.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(head);
            for (int i = 0; i < items.length; i++) {
                final int which = i;
                TextView row = Ui.text(c, items[i], 16, Ui.FG, false);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Ui.dpi(c, 20), 0, Ui.dpi(c, 20), 0);
                row.setClickable(true);
                row.setBackground(Ui.round(0x00000000, Ui.dp(c, 14)));
                row.setOnTouchListener((v, ev) -> {
                    if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) v.setBackground(Ui.round(0x1FFFFFFF, Ui.dp(c, 14)));
                    else if (ev.getActionMasked() == MotionEvent.ACTION_UP || ev.getActionMasked() == MotionEvent.ACTION_CANCEL)
                        v.setBackground(Ui.round(0x00000000, Ui.dp(c, 14)));
                    return false;
                });
                row.setOnClickListener(v -> { close(); cb.onItem(which); });
                LinearLayout.LayoutParams rp = Ui.lp(-1, Ui.dpi(c, 48));
                rp.leftMargin = rp.rightMargin = Ui.dpi(c, 6);
                card.addView(row, rp);
            }
            int w = Ui.dpi(c, 230);
            card.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
            int ch = card.getMeasuredHeight();
            int screenW = c.getResources().getDisplayMetrics().widthPixels;
            int margin = Ui.dpi(c, 12);
            float gap = iconSize / 2f + Ui.dp(c, 14);
            boolean above = ay - gap - ch > h.insetTop() + margin;
            int x = Math.round(Math.max(margin, Math.min(screenW - margin - w, ax - w / 2f)));
            int y = Math.round(above ? ay - gap - ch : ay + gap);
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
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(Ui.EMPHASIZED).start();
            dim.animate().alpha(1f).setDuration(180).start();
        }

        @Override void animateOut(Runnable done) {
            card.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(130).setInterpolator(Ui.STANDARD).start();
            dim.animate().alpha(0f).setDuration(130).withEndAction(done).start();
        }
    }

    // ================================================================ gestures

    final class Root extends FrameLayout {
        float progress; // 0 closed .. 1 open
        private float downX, downY, startProgress;
        private boolean dragging, layered, barsDark = true;
        private VelocityTracker vt;
        private ValueAnimator anim;
        private final int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        private final Runnable homeLongPress = () -> {
            if (dragging || drawerOpen || !overlays.isEmpty()) return;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            openWallpaper();
        };

        Root(Context c) { super(c); }

        private float travel() { return Math.max(1, getHeight()); }

        // Both halves become GPU layers while they move, so a frame is two quads, not a redraw.
        private void beginLayers() {
            if (layered) return;
            layered = true;
            drawer.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            home.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }

        private void endLayers() {
            if (!layered || dragging || (anim != null && anim.isRunning())) return;
            layered = false;
            drawer.setLayerType(View.LAYER_TYPE_NONE, null);
            home.setLayerType(View.LAYER_TYPE_NONE, null);
        }

        void setProgress(float p) {
            progress = Math.max(0f, Math.min(1f, p));
            boolean half = progress >= 0.5f;
            if (half == barsDark) { barsDark = !half; updateBars(); }
            if (progress <= 0f) {
                drawer.setVisibility(View.INVISIBLE);
                home.setAlpha(1f);
                home.setScaleX(1f);
                home.setScaleY(1f);
                drawerOpen = false;
                return;
            }
            if (drawer.getVisibility() != View.VISIBLE) drawer.setVisibility(View.VISIBLE);
            drawer.setTranslationY((1f - progress) * travel());
            home.setAlpha(1f - Math.min(1f, progress * 1.5f));
            float sc = 1f - 0.07f * progress;
            home.setScaleX(sc);
            home.setScaleY(sc);
            drawerOpen = progress >= 1f;
        }

        void snapTo(float p) {
            if (anim != null) anim.cancel();
            setProgress(p);
            endLayers();
        }

        void animateTo(float target) { animateTo(target, 0f); }

        void animateTo(final float target, float velocity) {
            if (anim != null) anim.cancel();
            if (target == 0f && searching) endSearch();
            float dist = Math.abs(target - progress);
            if (dist < 0.002f) { setProgress(target); endLayers(); return; }
            long dur = Math.round(170 + 190 * dist);
            if (velocity > 300f) {
                long byVel = Math.round(dist * travel() / velocity * 1000f * 1.35f);
                dur = Math.max(170, Math.min(dur, byVel));
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

        @Override public boolean onInterceptTouchEvent(MotionEvent ev) {
            if (!overlays.isEmpty()) return false;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX(); downY = ev.getY();
                    startProgress = progress;
                    dragging = false;
                    if (anim != null && anim.isRunning()) anim.cancel();
                    if (vt != null) vt.recycle();
                    vt = VelocityTracker.obtain();
                    vt.addMovement(ev);
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
            if (Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx) * 1.2f) {
                boolean opening = !drawerOpen && dy < 0;
                boolean closing = drawerOpen && dy > 0 && grid.atTop() && !searching;
                if (opening || closing) {
                    dragging = true;
                    removeCallbacks(homeLongPress);
                    downY = ev.getY();
                    startProgress = progress;
                    beginLayers();
                    return true;
                }
            }
            return false;
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            if (!overlays.isEmpty()) return false;
            if (vt == null) vt = VelocityTracker.obtain();
            vt.addMovement(ev);
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX(); downY = ev.getY(); startProgress = progress;
                    if (anim != null && anim.isRunning()) anim.cancel();
                    if (placing != null) { cancelPlacing(); return true; }
                    if (!drawerOpen && progress == 0f)
                        postDelayed(homeLongPress, ViewConfiguration.getLongPressTimeout() + 80);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging && (Math.abs(ev.getY() - downY) > slop || Math.abs(ev.getX() - downX) > slop))
                        removeCallbacks(homeLongPress);
                    if (!dragging) maybeStartDrag(ev);
                    if (dragging) setProgress(startProgress + (downY - ev.getY()) / travel());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(homeLongPress);
                    if (dragging) {
                        vt.computeCurrentVelocity(1000);
                        float vy = vt.getYVelocity();
                        float target;
                        if (vy < -700) target = 1f;
                        else if (vy > 700) target = 0f;
                        else target = progress > 0.5f ? 1f : 0f;
                        dragging = false;
                        animateTo(target, target == 1f ? -vy : vy);
                    }
                    dragging = false;
                    if (vt != null) { vt.recycle(); vt = null; }
                    return true;
            }
            return true;
        }
    }
}
