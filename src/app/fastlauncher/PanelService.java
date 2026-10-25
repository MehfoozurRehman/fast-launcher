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

    @Override protected void onServiceConnected() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        statusH = id > 0 ? getResources().getDimensionPixelSize(id) : Ui.dpi(this, 32);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screen, f);

        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km == null || !km.isKeyguardLocked()) addStrip();
        ensurePanel();
    }

    // Nothing on the lock screen: the panel must never be reachable without unlocking.
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                if (panelOpen) finishHide();
                removeStrip();
            } else {
                addStrip();
            }
        }
    };

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}

    @Override protected boolean onKeyEvent(KeyEvent e) {
        if (panelOpen && e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) panel.close();
            return true;
        }
        return false;
    }

    @Override public void onDestroy() {
        try { unregisterReceiver(screen); } catch (Exception ignored) {}
        removeStrip();
        if (panel != null) {
            panel.unbind();
            try { wm.removeView(panel); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    // ================================================================ trigger strip

    private void addStrip() {
        if (strip != null || wm == null) return;
        strip = new Strip(this);
        int w = getResources().getDisplayMetrics().widthPixels;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                Math.round(w * 0.55f), statusH,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        lp.setTitle("FastLauncherPanelStrip");
        try { wm.addView(strip, lp); } catch (Exception e) { strip = null; }
    }

    private void removeStrip() {
        if (strip == null) return;
        try { wm.removeView(strip); } catch (Exception ignored) {}
        strip = null;
    }

    /** Swipe down on it to pull the panel; the same touch stream keeps driving the panel. */
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
                    if (!panelOpen) panel.refresh();
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
        panel.unbind();
        panelLp.alpha = 0f;
        panelLp.flags = hiddenFlags();
        try { wm.updateViewLayout(panel, panelLp); } catch (Exception ignored) {}
    }

    @Override public void launch(Intent i) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) {}
    }

    @Override public void globalAction(int action) { performGlobalAction(action); }

    @Override public int statusBarHeight() { return statusH; }
}
