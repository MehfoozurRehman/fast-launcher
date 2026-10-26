package app.fastlauncher;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Puts the control panel one pull away in every app: an invisible strip over the right half of
 * the status bar catches the swipe, and the panel follows the finger down.
 *
 * Accessibility is used only for the overlay window, the Back key while the panel is open, and
 * the screenshot / lock actions. It reads no screen content.
 */
public class PanelService extends AccessibilityService implements ControlPanel.Host {

    private WindowManager wm;
    private View strip;
    private ControlPanel panel;
    private WindowManager.LayoutParams panelLp;
    private boolean panelOpen;
    private int statusH;

    /** The connected service, so the launcher can lock the screen (double-tap on home). */
    static PanelService running;

    // ================================================================ system tiles (experiment)

    /**
     * Press one of the phone's own Control Centre tiles (found by its label), without showing it:
     * open the system panel behind ours, click the tile through accessibility, close it again.
     * done gets true if the tile was found and clicked.
     */
    private static boolean tileBusy;
    /** Presses asked for while one is running: done in order, never dropped. */
    private static final java.util.ArrayDeque<Object[]> tileQueue = new java.util.ArrayDeque<>();

    @SuppressWarnings("unchecked")
    static void pressSystemTile(final String label, final java.util.function.Consumer<Boolean> done0) {
        final PanelService s = running;
        if (s == null) { done0.accept(false); return; }
        if (tileBusy) { tileQueue.add(new Object[]{label, done0}); return; }
        tileBusy = true;
        final java.util.function.Consumer<Boolean> done = ok -> {
            tileBusy = false;
            done0.accept(ok);
            Object[] next = tileQueue.poll();
            if (next != null) s.main.post(() -> pressSystemTile((String) next[0], (java.util.function.Consumer<Boolean>) next[1]));
        };
        s.stepAside(true);
        s.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
        final long start = android.os.SystemClock.uptimeMillis();
        s.main.postDelayed(new Runnable() {
            @Override public void run() {
                android.view.accessibility.AccessibilityNodeInfo tile = s.findTile(label);
                if (tile != null) {
                    boolean ok = tile.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
                    Diag.log("tile '" + label + "' clicked=" + ok + " after " + (android.os.SystemClock.uptimeMillis() - start) + "ms");
                    s.main.postDelayed(() -> s.closeSystemPanel(() -> done.accept(ok)), 180);
                    return;
                }
                if (android.os.SystemClock.uptimeMillis() - start > 1500) {
                    Diag.log("tile '" + label + "' not found");
                    s.closeSystemPanel(() -> done.accept(false));
                    return;
                }
                s.main.postDelayed(this, 60);
            }
        }, 120);
    }

    /** Honor ignores "dismiss the shade"; Back closes it. Checked: only when it's still there. */
    private void closeSystemPanel(Runnable after) {
        performGlobalAction(15 /* GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE */);
        main.postDelayed(() -> {
            if (systemPanelShowing()) performGlobalAction(GLOBAL_ACTION_BACK);
            main.postDelayed(() -> { stepAside(false); after.run(); }, 150);
        }, 120);
    }

    /**
     * While pressing a system tile behind our panel: our window gives up focus (so the Back that
     * closes the system panel reaches it, not us), and our Back handling stands down.
     */
    private boolean asideForTile;

    private void stepAside(boolean aside) {
        asideForTile = aside;
        if (!panelOpen || panel == null) return;
        if (aside) panelLp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        else panelLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        try { wm.updateViewLayout(panel, panelLp); } catch (Exception ignored) {}
    }

    private boolean systemPanelShowing() {
        for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
            android.view.accessibility.AccessibilityNodeInfo r = w.getRoot();
            if (r != null && r.getPackageName() != null && "com.android.systemui".contentEquals(r.getPackageName())
                    && w.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM
                    && r.getChildCount() > 0 && !r.findAccessibilityNodeInfosByText("Open settings").isEmpty()) return true;
        }
        return false;
    }

    /**
     * The system tile for a label: a clickable node described as "Label" or "Label,state..."
     * ("Hotspot,Enabled"), in the system UI only. Exact, so "Wi-Fi" can't hit another tile.
     */
    private android.view.accessibility.AccessibilityNodeInfo findTile(String label) {
        String want = label.toLowerCase(java.util.Locale.ROOT);
        for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
            android.view.accessibility.AccessibilityNodeInfo root = w.getRoot();
            if (root == null || root.getPackageName() == null || !"com.android.systemui".contentEquals(root.getPackageName())) continue;
            for (android.view.accessibility.AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText(label)) {
                CharSequence d = n.getContentDescription() != null ? n.getContentDescription() : n.getText();
                if (d == null) continue;
                String t = d.toString().replace("\u200b", "").toLowerCase(java.util.Locale.ROOT);
                if (!(t.equals(want) || t.startsWith(want + ","))) continue;
                android.view.accessibility.AccessibilityNodeInfo c = n;
                while (c != null && !c.isClickable()) c = c.getParent();
                if (c != null) return c;
            }
        }
        return null;
    }

    /** Debug: list what the system panel offers (texts of clickable nodes). */
    static void dumpSystemTiles() {
        final PanelService s = running;
        if (s == null) return;
        s.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
        s.main.postDelayed(() -> {
            for (android.view.accessibility.AccessibilityWindowInfo w : s.getWindows()) {
                android.view.accessibility.AccessibilityNodeInfo root = w.getRoot();
                if (root == null || root.getPackageName() == null || !"com.android.systemui".contentEquals(root.getPackageName())) continue;
                s.dump(root, 0);
            }
            s.performGlobalAction(15);
        }, 700);
    }

    private void dump(android.view.accessibility.AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 25) return;
        CharSequence t = n.getText(), d = n.getContentDescription();
        if ((t != null || d != null) && (n.isClickable() || (n.getParent() != null && n.getParent().isClickable())))
            android.util.Log.i("FLTiles", "text=" + t + " desc=" + d + " click=" + n.isClickable() + " checked=" + n.isChecked());
        for (int i = 0; i < n.getChildCount(); i++) dump(n.getChild(i), depth + 1);
    }

    static boolean lockScreen() {
        PanelService s = running;
        return s != null && s.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    @Override protected void onServiceConnected() {
        Diag.install(this);
        running = this;
        // connected: whatever turned us off before, the user wants the panel now
        getSharedPreferences("panel", MODE_PRIVATE).edit().putBoolean("userOff", false).apply();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        statusH = id > 0 ? getResources().getDimensionPixelSize(id) : Ui.dpi(this, 32);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screen, f);

        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        lastConfig = new android.content.res.Configuration(getResources().getConfiguration());
        if (km == null || !km.isKeyguardLocked()) addStrip();
        ensurePanel();
    }

    // Nothing on the lock screen: the panel must never be reachable without unlocking.
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                if (panelOpen) finishHide();
                removeStrip();
            } else if (Intent.ACTION_USER_PRESENT.equals(i.getAction()) || !locked()) {
                // woken within the lock grace period there's no unlock (and no USER_PRESENT):
                // screen on while not locked counts too
                addStrip();
            }
        }
    };

    private boolean locked() {
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        return km != null && km.isKeyguardLocked();
    }

    /**
     * Unbound while the process is alive means the user switched "Fast Launcher controls" off
     * in Settings: remember it, so self-repair doesn't switch it back on behind their back.
     * (A force-stop kills the process without this call, and that case does get repaired.)
     */
    @Override public boolean onUnbind(Intent intent) {
        // updates and shutdowns unbind too, but leave the setting on: only an actual switch-off counts
        String on = android.provider.Settings.Secure.getString(getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (on == null || !on.contains(getPackageName())) {
            getSharedPreferences("panel", MODE_PRIVATE).edit().putBoolean("userOff", true).commit();
        }
        return super.onUnbind(intent);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}

    @Override protected boolean onKeyEvent(KeyEvent e) {
        if (panelOpen && !asideForTile && e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) panel.close();
            return true;
        }
        return false;
    }

    @Override public void onDestroy() {
        if (running == this) running = null;
        try { unregisterReceiver(screen); } catch (Exception ignored) {}
        removeStrip();
        if (panel != null) {
            panel.destroy();
            try { wm.removeView(panel); } catch (Exception ignored) {}
            panel = null;
        }
        super.onDestroy();
    }

    // ================================================================ trigger strip

    private android.content.res.Configuration lastConfig;

    private boolean landscape() {
        return getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    private void addStrip() {
        // in landscape (games, videos) the system's own pull-down stays in charge: our strip is
        // sized for portrait, and full-screen apps keep their top edge
        if (strip != null || wm == null || landscape()) return;
        strip = new Strip(this);
        int w = getResources().getDisplayMetrics().widthPixels;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, statusH,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        lp.setTitle("FastLauncherPanelStrip");
        // nothing to show: a nearly invisible window keeps the compositor on its fast path (at
        // full alpha this layer over the status bar pushed the screen to GPU composition); alpha
        // must stay above 0, or the window stops receiving touches
        lp.alpha = 0.01f;
        try { wm.addView(strip, lp); } catch (Exception e) { strip = null; }
    }

    private void removeStrip() {
        if (strip == null) return;
        try { wm.removeView(strip); } catch (Exception ignored) {}
        strip = null;
    }

    // ================================================================ blurred backdrop

    private static final java.util.concurrent.ExecutorService blurWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private long lastCapture;

    /**
     * Like the system's control centre: capture what's on screen right now (the panel is still
     * invisible), shrink it to a tenth and blur it on a worker thread, then hand it to the panel
     * as its background. The capture never leaves memory.
     */
    private void captureBehind() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastCapture < 350) return; // Android allows about three captures a second
        lastCapture = now;
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, blurWorker, new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult r) {
                    android.graphics.Bitmap blurred = null;
                    try (android.hardware.HardwareBuffer buf = r.getHardwareBuffer()) {
                        android.graphics.Bitmap hw = android.graphics.Bitmap.wrapHardwareBuffer(buf, r.getColorSpace());
                        if (hw != null) {
                            android.graphics.Bitmap soft = hw.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                            hw.recycle();
                            if (soft != null) {
                                android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(soft,
                                        Math.max(1, soft.getWidth() / 10), Math.max(1, soft.getHeight() / 10), true);
                                soft.recycle();
                                if (!small.isMutable()) small = small.copy(android.graphics.Bitmap.Config.ARGB_8888, true);
                                Walls.boxBlur(small, 4);
                                Walls.boxBlur(small, 4);
                                Walls.boxBlur(small, 4);
                                tint(small, Ui.dark);
                                blurred = Icons.toHardware(small);
                            }
                        }
                    } catch (Exception e) {
                        Diag.log("panel blur: " + e);
                    }
                    final android.graphics.Bitmap b = blurred;
                    if (b != null) main.post(() -> { if (panel != null) panel.setBackdrop(b); });
                }
                @Override public void onFailure(int code) {
                    Diag.log("panel capture failed: " + code);
                }
            });
        } catch (Exception e) {
            Diag.log("panel capture: " + e);
        }
    }

    /**
     * Bakes the panel's tint into the blur (dimmed in dark mode, milky in light mode), so the
     * background is one full-screen draw per frame instead of a picture plus a tint layer.
     */
    private static void tint(android.graphics.Bitmap b, boolean dark) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < px.length; i++) {
            int p = px[i], r = (p >> 16) & 255, g = (p >> 8) & 255, bl = p & 255;
            if (dark) { r = r * 6 / 10; g = g * 6 / 10; bl = bl * 6 / 10; }
            else { r = (r * 35 + 0xF2 * 65) / 100; g = (g * 35 + 0xF3 * 65) / 100; bl = (bl * 35 + 0xF5 * 65) / 100; }
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | bl;
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Opens the notifications page from the launcher (swipe down on home). */
    static boolean openNotifications() {
        PanelService s = running;
        if (s == null) return false;
        s.ensurePanel();
        if (!s.panelOpen) { s.captureBehind(); s.panel.refresh(true); }
        s.showPanel();
        s.panel.animateTo(1f, 0f);
        return true;
    }

    /**
     * Swipe down on it to pull the panel; the same touch stream keeps driving the panel.
     * It spans the status bar: the left side opens notifications, the right side the controls.
     */
    private final class Strip extends View {
        private float downY;
        private boolean pulling;
        private VelocityTracker vt;
        private final int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();

        Strip(Context c) { super(c); }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (vt == null) vt = VelocityTracker.obtain();
            vt.addMovement(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY = e.getRawY();
                    pulling = false;
                    // read every control's state now, while the finger hasn't moved yet: this
                    // frame draws nothing visible, so the panel's first visible frame is cheap
                    ensurePanel();
                    if (!panelOpen) {
                        captureBehind();
                        panel.refresh(e.getRawX() < getWidth() * 0.45f);
                    }
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dy = e.getRawY() - downY;
                    if (!pulling && dy > slop) {
                        pulling = true;
                        showPanel();
                    }
                    if (pulling) panel.dragFromTop(dy);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (pulling) {
                        vt.computeCurrentVelocity(1000);
                        panel.settle(vt.getYVelocity());
                    } else if (!panelOpen) {
                        panel.unbind(); // just a tap on the status bar: don't keep following the media session
                    }
                    pulling = false;
                    vt.recycle();
                    vt = null;
                    return true;
            }
            return true;
        }
    }

    // ================================================================ panel window

    private void ensurePanel() {
        if (panel != null) return;
        Ui.theme(this);
        panel = new ControlPanel(this, this);
        // Full-size from the start but fully transparent and untouchable while hidden: the
        // compositor skips a window with alpha 0, and a pull never has to resize or re-lay out.
        panelLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                hiddenFlags(), PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.TOP | Gravity.START;
        panelLp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        panelLp.setTitle("FastLauncherPanel");
        panelLp.alpha = 0f;
        wm.addView(panel, panelLp);
    }

    private static int hiddenFlags() {
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
    }

    private void showPanel() {
        ensurePanel();
        if (panelOpen) return;
        panelOpen = true;
        panelLp.alpha = 1f;
        // focusable while open, so Back (button or gesture) reaches the panel itself
        panelLp.flags = hiddenFlags() & ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                & ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        wm.updateViewLayout(panel, panelLp);
    }

    /** Hidden = transparent and untouchable: nothing to composite, nothing to rebuild next time. */
    @Override public void finishHide() {
        if (panel == null) return;
        panelOpen = false;
        panel.setProgress(0f);
        // the theme flipped while the panel was open (its own Dark mode button): rebuild it now
        if (panel.isDark != Ui.dark) main.post(() -> { if (!panelOpen) onConfigurationChanged(getResources().getConfiguration()); });
        panel.unbind();
        panelLp.alpha = 0f;
        panelLp.flags = hiddenFlags();
        try { wm.updateViewLayout(panel, panelLp); } catch (Exception ignored) {}
    }

    @Override public void launch(Intent i) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) {}
    }

    @Override public void pressTile(String label, java.util.function.Consumer<Boolean> done) { pressSystemTile(label, done); }

    @Override public void globalAction(int action) {
        performGlobalAction(action);
    }

    /** Light/dark switched: rebuild the (hidden) panel in the new palette. */
    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        Ui.theme(this); // the launcher may have flipped the shared palette first
        android.content.res.Configuration was = lastConfig;
        lastConfig = new android.content.res.Configuration(c);
        // rotated: the strip goes (landscape) or comes back, re-measured for the new width
        if (was == null || was.orientation != c.orientation || was.screenWidthDp != c.screenWidthDp) {
            if (panelOpen) finishHide();
            removeStrip();
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) statusH = getResources().getDimensionPixelSize(id);
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (!locked() && (pm == null || pm.isInteractive())) addStrip();
        }
        // light/dark, font size, display size or language changed: rebuild the (hidden) panel
        boolean restyle = panel != null && (panel.isDark != Ui.dark || was == null
                || was.fontScale != c.fontScale || was.densityDpi != c.densityDpi || !was.getLocales().equals(c.getLocales()));
        if (!restyle) return;
        if (panelOpen) finishHide();
        try { wm.removeView(panel); } catch (Exception ignored) {}
        panel.destroy();
        panel = null;
        ensurePanel();
    }

    /** An incoming call: get out of the way of the call screen. */
    static void closeForCall() {
        PanelService s = running;
        if (s != null && s.panelOpen && s.panel != null) s.panel.close();
    }

    @Override public int statusBarHeight() { return statusH; }
}
