package app.fastlauncher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

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
    private TextView clock, date, title;
    private EditText search;
    private AppGridView dock, grid;
    private View btnSearch, btnStore, btnMenu;

    private final List<AppGridView.AppEntry> all = new ArrayList<>();
    private final AppGridView.AppEntry[] dockEntries = new AppGridView.AppEntry[DOCK_SLOTS];
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private boolean drawerOpen;
    private boolean searching;
    private int loadGen;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        iconPx = Math.round(44 * dp);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | (Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0)
                        | (Build.VERSION.SDK_INT >= 23 ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0));

        root = new Root(this);
        setContentView(root);
        buildHome();
        buildDrawer();
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                insetTop = in.getSystemWindowInsetTop();
                insetBottom = in.getSystemWindowInsetBottom();
                applyInsets();
                return in;
            }
        });

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);

        loadApps();
    }

    @Override protected void onDestroy() {
        unregisterReceiver(pkgReceiver);
        bg.shutdownNow();
        super.onDestroy();
    }

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { loadApps(); }
    };

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (Intent.ACTION_MAIN.equals(i.getAction())) {
            if (searching) endSearch();
            if (drawerOpen) root.animateTo(0f);
        }
    }

    @Override public void onBackPressed() {
        if (searching) { endSearch(); return; }
        if (drawerOpen) root.animateTo(0f);
    }

    // ---------------------------------------------------------------- views

    private int dpi(float v) { return Math.round(v * dp); }

    private void buildHome() {
        home = new FrameLayout(this);
        root.addView(home, new FrameLayout.LayoutParams(-1, -1));

        clock = new TextView(this);
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 74);
        clock.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        clock.setIncludeFontPadding(false);
        clock.setGravity(Gravity.CENTER);
        clock.setShadowLayer(dpi(6), 0, dpi(2), 0x40000000);
        home.addView(clock, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        date = new TextView(this);
        date.setTextColor(Color.WHITE);
        date.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 21);
        date.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        date.setIncludeFontPadding(false);
        date.setGravity(Gravity.CENTER);
        date.setShadowLayer(dpi(4), 0, dpi(1), 0x40000000);
        home.addView(date, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        dock = new AppGridView(this, 5, 17, 90, 44, 0, 0, false, false);
        dock.setListener(new AppGridView.Listener() {
            @Override public void onTap(AppGridView.AppEntry e) { launch(e); }
            @Override public void onLongPress(AppGridView.AppEntry e) { dockMenu(e); }
        });
        home.addView(dock, new FrameLayout.LayoutParams(-1, dpi(180), Gravity.BOTTOM));

        updateClock();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateClock();
            long now = System.currentTimeMillis();
            clock.postDelayed(this, 60000 - (now % 60000) + 50);
        }
    };

    private void updateClock() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        int h = c.get(java.util.Calendar.HOUR);
        if (h == 0) h = 12;
        clock.setText(h + ":" + String.format(Locale.US, "%02d", c.get(java.util.Calendar.MINUTE)));
        date.setText(new java.text.SimpleDateFormat("EEE, d MMMM", Locale.getDefault()).format(c.getTime()));
    }

    private final NotifService.Changed notifChanged = new NotifService.Changed() {
        @Override public void onNotifCountsChanged() { pushBadges(); }
    };

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
    }

    @Override protected void onPause() {
        if (NotifService.listener == notifChanged) NotifService.listener = null;
        clock.removeCallbacks(tick);
        super.onPause();
    }

    private void buildDrawer() {
        drawer = new FrameLayout(this);
        drawer.setBackgroundColor(0x55000000);
        drawer.setVisibility(View.INVISIBLE);
        root.addView(drawer, new FrameLayout.LayoutParams(-1, -1));

        title = new TextView(this);
        title.setText("All apps");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setIncludeFontPadding(false);
        title.setShadowLayer(dpi(3), 0, dpi(1), 0x55000000);
        drawer.addView(title, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));

        search = new EditText(this);
        search.setHint("Search apps");
        search.setHintTextColor(0xBBFFFFFF);
        search.setTextColor(Color.WHITE);
        search.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        search.setSingleLine(true);
        search.setBackground(null);
        search.setVisibility(View.GONE);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) { filter(s.toString()); }
            public void afterTextChanged(Editable s) {}
        });
        drawer.addView(search, new FrameLayout.LayoutParams(-1, dpi(48), Gravity.TOP));

        btnSearch = new HeaderBtn(this, 0);
        btnStore = new HeaderBtn(this, 1);
        btnMenu = new HeaderBtn(this, 2);
        for (View v : new View[]{btnSearch, btnStore, btnMenu})
            drawer.addView(v, new FrameLayout.LayoutParams(dpi(44), dpi(44), Gravity.TOP | Gravity.END));
        btnSearch.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { beginSearch(); }
        });
        btnStore.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openStore(); }
        });
        btnMenu.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { headerMenu(); }
        });

        grid = new AppGridView(this, 5, 17, 97, 44, 8, 12, true, true);
        grid.setListener(new AppGridView.Listener() {
            @Override public void onTap(AppGridView.AppEntry e) { launch(e); }
            @Override public void onLongPress(AppGridView.AppEntry e) { appMenu(e); }
        });
        drawer.addView(grid, new FrameLayout.LayoutParams(-1, -1, Gravity.TOP));
    }

    private void applyInsets() {
        float d = dp;
        // clock digits centre ~ 150dp from the top, date just below
        FrameLayout.LayoutParams cp = (FrameLayout.LayoutParams) clock.getLayoutParams();
        cp.topMargin = dpi(112);
        clock.setLayoutParams(cp);
        FrameLayout.LayoutParams dpm = (FrameLayout.LayoutParams) date.getLayoutParams();
        dpm.topMargin = dpi(112 + 84);
        date.setLayoutParams(dpm);

        FrameLayout.LayoutParams dk = (FrameLayout.LayoutParams) dock.getLayoutParams();
        dk.bottomMargin = Math.max(insetBottom, dpi(8)) + dpi(4) - dpi(25);
        dock.setLayoutParams(dk);

        int headerTop = insetTop + dpi(14);
        FrameLayout.LayoutParams tp = (FrameLayout.LayoutParams) title.getLayoutParams();
        tp.topMargin = headerTop + dpi(4);
        tp.leftMargin = dpi(23);
        title.setLayoutParams(tp);
        FrameLayout.LayoutParams sp = (FrameLayout.LayoutParams) search.getLayoutParams();
        sp.topMargin = headerTop;
        sp.leftMargin = dpi(23);
        sp.rightMargin = dpi(23);
        search.setLayoutParams(sp);
        int[] rights = {dpi(40 + 44 + 44), dpi(40 + 44), dpi(40)};
        View[] bs = {btnSearch, btnStore, btnMenu};
        for (int i = 0; i < 3; i++) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bs[i].getLayoutParams();
            lp.topMargin = headerTop - dpi(2);
            lp.rightMargin = rights[i] - dpi(40) + dpi(6) + (i == 0 ? dpi(4) : 0);
            bs[i].setLayoutParams(lp);
        }
        FrameLayout.LayoutParams gp = (FrameLayout.LayoutParams) grid.getLayoutParams();
        gp.topMargin = insetTop + dpi(78);
        grid.setLayoutParams(gp);
        grid.setBottomInset(insetBottom);
        root.requestLayout();
    }

    // ---------------------------------------------------------------- apps

    private void loadApps() {
        final int gen = ++loadGen;
        bg.execute(new Runnable() {
            @Override public void run() {
                PackageManager pm = getPackageManager();
                Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> ris = pm.queryIntentActivities(q, 0);
                final List<AppGridView.AppEntry> list = new ArrayList<>();
                for (ResolveInfo ri : ris) {
                    AppGridView.AppEntry e = new AppGridView.AppEntry();
                    e.label = String.valueOf(ri.loadLabel(pm));
                    e.lower = e.label.toLowerCase(Locale.getDefault());
                    e.cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
                    list.add(e);
                }
                Collections.sort(list, new Comparator<AppGridView.AppEntry>() {
                    @Override public int compare(AppGridView.AppEntry a, AppGridView.AppEntry b) {
                        return a.lower.compareTo(b.lower);
                    }
                });
                // reuse already-rendered icons so reloads are instant
                for (AppGridView.AppEntry e : list)
                    for (AppGridView.AppEntry old : all)
                        if (old.cn.equals(e.cn)) { e.icon = old.icon; break; }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (gen != loadGen) return;
                        all.clear();
                        all.addAll(list);
                        resolveDock();
                        if (!searching) grid.setItems(all);
                        dock.setItems(dockList());
                    }
                });
                // render missing icons off the UI thread, drawer order first
                int n = 0;
                for (AppGridView.AppEntry e : list) {
                    if (gen != loadGen) return;
                    if (e.icon == null) {
                        e.icon = safeIcon(pm, e);
                        if (++n % 10 == 0) { grid.postInvalidate(); dock.postInvalidate(); }
                    }
                }
                grid.postInvalidate();
                dock.postInvalidate();
            }
        });
    }

    private Bitmap safeIcon(PackageManager pm, AppGridView.AppEntry e) {
        try { return render(pm.getActivityIcon(e.cn)); } catch (Throwable t) {
            android.util.Log.w("FastLauncher", "icon failed " + e.cn + ": " + t);
        }
        try { return render(pm.getApplicationIcon(e.cn.getPackageName())); } catch (Throwable t) {
            android.util.Log.w("FastLauncher", "app icon failed " + e.cn + ": " + t);
        }
        try { return render(pm.getDefaultActivityIcon()); } catch (Throwable t) { return null; }
    }

    private Bitmap render(Drawable d) {
        int s = iconPx;
        Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        boolean adaptive = Build.VERSION.SDK_INT >= 26 && d instanceof android.graphics.drawable.AdaptiveIconDrawable;
        if (adaptive) {
            d.setBounds(0, 0, s, s);
            d.draw(c);
        } else {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.WHITE);
            c.drawRoundRect(new RectF(0, 0, s, s), s * 0.30f, s * 0.30f, p);
            int in = Math.round(s * 0.17f);
            d.setBounds(in, in, s - in, s - in);
            d.draw(c);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            Bitmap hw = bmp.copy(Bitmap.Config.HARDWARE, false);
            if (hw != null) { bmp.recycle(); return hw; }
        }
        return bmp;
    }

    private void resolveDock() {
        String saved = prefs.getString("dock", null);
        String[] parts = saved == null ? null : saved.split(",", -1);
        for (int i = 0; i < DOCK_SLOTS; i++) {
            AppGridView.AppEntry e = null;
            if (parts != null && i < parts.length && !parts[i].isEmpty()) {
                ComponentName cn = ComponentName.unflattenFromString(parts[i]);
                e = byComponent(cn);
            } else if (parts == null) {
                for (String pkg : DEFAULT_DOCK[i]) {
                    e = byPackage(pkg);
                    if (e != null) break;
                }
            }
            dockEntries[i] = e;
        }
        if (parts == null) saveDock();
    }

    private void saveDock() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < DOCK_SLOTS; i++) {
            if (i > 0) sb.append(',');
            if (dockEntries[i] != null) sb.append(dockEntries[i].cn.flattenToString());
        }
        prefs.edit().putString("dock", sb.toString()).apply();
    }

    private List<AppGridView.AppEntry> dockList() {
        List<AppGridView.AppEntry> l = new ArrayList<>();
        for (AppGridView.AppEntry e : dockEntries) {
            if (e != null) l.add(e);
            else l.add(blank());
        }
        return l;
    }

    private AppGridView.AppEntry blank() {
        AppGridView.AppEntry e = new AppGridView.AppEntry();
        e.label = ""; e.lower = ""; e.cn = new ComponentName("app.fastlauncher", "blank");
        return e;
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

    private void launch(AppGridView.AppEntry e) {
        if (e.cn.getClassName().equals("blank")) return;
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(e.cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { startActivity(i); } catch (Exception ignored) {}
        if (drawerOpen) {
            if (searching) endSearch();
            root.postDelayed(new Runnable() { @Override public void run() { root.snapTo(0f); } }, 300);
        }
    }

    // ---------------------------------------------------------------- menus

    private void appMenu(final AppGridView.AppEntry e) {
        new AlertDialog.Builder(this).setTitle(e.label)
                .setItems(new String[]{"Add to home dock", "App info", "Uninstall"},
                        new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                if (w == 0) pickSlot(e);
                                else if (w == 1) startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:" + e.cn.getPackageName())));
                                else startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + e.cn.getPackageName())));
                            }
                        }).show();
    }

    private void pickSlot(final AppGridView.AppEntry e) {
        String[] names = new String[DOCK_SLOTS];
        for (int i = 0; i < DOCK_SLOTS; i++)
            names[i] = "Row " + (i / 5 + 1) + ", slot " + (i % 5 + 1)
                    + (dockEntries[i] != null ? "  (" + dockEntries[i].label + ")" : "");
        new AlertDialog.Builder(this).setTitle("Put " + e.label + " in…")
                .setItems(names, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        dockEntries[w] = e;
                        saveDock();
                        dock.setItems(dockList());
                    }
                }).show();
    }

    private void dockMenu(final AppGridView.AppEntry e) {
        if (e.cn.getClassName().equals("blank")) return;
        new AlertDialog.Builder(this).setTitle(e.label)
                .setItems(new String[]{"Remove from dock", "App info"},
                        new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                if (w == 0) {
                                    for (int i = 0; i < DOCK_SLOTS; i++) if (dockEntries[i] == e) dockEntries[i] = null;
                                    saveDock();
                                    dock.setItems(dockList());
                                } else startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:" + e.cn.getPackageName())));
                            }
                        }).show();
    }

    private void headerMenu() {
        new AlertDialog.Builder(this)
                .setItems(new String[]{"Change wallpaper", "Display settings", "Default home app"},
                        new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                try {
                                    if (w == 0) startActivity(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper"));
                                    else if (w == 1) startActivity(new Intent(Settings.ACTION_DISPLAY_SETTINGS));
                                    else startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
                                } catch (Exception ignored) {}
                            }
                        }).show();
    }

    private void openStore() {
        for (String pkg : new String[]{"com.android.vending", "com.hihonor.appmarket"}) {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) { startActivity(i); return; }
        }
    }

    // ---------------------------------------------------------------- search

    private void beginSearch() {
        searching = true;
        title.setVisibility(View.INVISIBLE);
        btnSearch.setVisibility(View.GONE);
        btnStore.setVisibility(View.GONE);
        btnMenu.setVisibility(View.GONE);
        search.setText("");
        search.setVisibility(View.VISIBLE);
        search.requestFocus();
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(search, 0);
    }

    private void endSearch() {
        searching = false;
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.setVisibility(View.GONE);
        title.setVisibility(View.VISIBLE);
        btnSearch.setVisibility(View.VISIBLE);
        btnStore.setVisibility(View.VISIBLE);
        btnMenu.setVisibility(View.VISIBLE);
        grid.setItems(all);
    }

    private void filter(String q) {
        q = q.trim().toLowerCase(Locale.getDefault());
        List<AppGridView.AppEntry> l = new ArrayList<>();
        for (AppGridView.AppEntry e : all) if (q.isEmpty() || e.lower.contains(q)) l.add(e);
        grid.setItems(l);
    }

    // ---------------------------------------------------------------- drawer gesture

    final class Root extends FrameLayout {
        private float progress; // 0 closed .. 1 open
        private float downX, downY, startProgress;
        private boolean dragging, layered;
        private VelocityTracker vt;
        private ValueAnimator anim;
        private final int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        private final android.view.animation.Interpolator settle =
                new android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f);

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
            anim.setInterpolator(settle);
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) { setProgress((Float) a.getAnimatedValue()); }
            });
            anim.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator a) {
                    post(new Runnable() { @Override public void run() { endLayers(); } });
                }
            });
            anim.start();
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX(); downY = ev.getY();
                    startProgress = progress;
                    dragging = false;
                    if (anim != null && anim.isRunning()) { anim.cancel(); }
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
            float dy = ev.getY() - downY, dx = ev.getX() - downX;
            if (Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx) * 1.2f) {
                boolean opening = !drawerOpen && dy < 0;
                boolean closing = drawerOpen && dy > 0 && grid.atTop() && !searching;
                if (opening || closing) {
                    dragging = true;
                    downY = ev.getY();
                    startProgress = progress;
                    beginLayers();
                    return true;
                }
            }
            return false;
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            if (vt == null) { vt = VelocityTracker.obtain(); }
            vt.addMovement(ev);
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX(); downY = ev.getY(); startProgress = progress;
                    if (anim != null && anim.isRunning()) anim.cancel();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging) maybeStartDrag(ev);
                    if (dragging) setProgress(startProgress + (downY - ev.getY()) / travel());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) {
                        vt.computeCurrentVelocity(1000);
                        float vy = vt.getYVelocity();
                        float target;
                        if (vy < -700) target = 1f;
                        else if (vy > 700) target = 0f;
                        else target = progress > 0.5f ? 1f : 0f;
                        dragging = false;
                        float toward = (target == 1f) ? -vy : vy;
                        animateTo(target, toward);
                    }
                    dragging = false;
                    vt.recycle(); vt = null;
                    return true;
            }
            return true;
        }
    }

    // ---------------------------------------------------------------- header icons

    static final class HeaderBtn extends View {
        private final int type;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        HeaderBtn(Context c, int type) {
            super(c);
            this.type = type;
            float d = c.getResources().getDisplayMetrics().density;
            p.setColor(Color.WHITE);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.2f * d);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setShadowLayer(3 * d, 0, d, 0x55000000);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), d = w / 44f;
            float cx = w / 2, cy = h / 2;
            if (type == 0) {
                c.drawCircle(cx - 2 * d, cy - 2 * d, 7.5f * d, p);
                c.drawLine(cx + 3.5f * d, cy + 3.5f * d, cx + 9 * d, cy + 9 * d, p);
            } else if (type == 1) {
                RectF r = new RectF(cx - 8 * d, cy - 4 * d, cx + 8 * d, cy + 10 * d);
                c.drawRoundRect(r, 3 * d, 3 * d, p);
                Path hnd = new Path();
                hnd.moveTo(cx - 4 * d, cy - 4 * d);
                hnd.cubicTo(cx - 4 * d, cy - 11 * d, cx + 4 * d, cy - 11 * d, cx + 4 * d, cy - 4 * d);
                c.drawPath(hnd, p);
            } else {
                Paint f = new Paint(p);
                f.setStyle(Paint.Style.FILL);
                for (int i = -1; i <= 1; i++) c.drawCircle(cx, cy + i * 7 * d, 2f * d, f);
            }
        }
    }
}
