package app.fastlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.List;

/**
 * One view that draws a whole grid of pre-rendered icon bitmaps. No child views, no
 * layouts: scrolling is just a translate + drawBitmap on the visible rows.
 */
final class AppGridView extends View {

    static final class AppEntry {
        String label, lower, drawLabel;
        ComponentName cn;
        volatile Bitmap icon;
    }

    interface Listener {
        void onTap(AppEntry e);
        void onLongPress(AppEntry e);
    }

    private List<AppEntry> items = new ArrayList<>();
    private Listener listener;

    private final int cols;
    private final boolean scrollable, labels;
    private final float padX, rowH, iconSize, topPad, labelGap;
    private float bottomPad;

    private final Paint iconPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float badgeR;

    private final OverScroller scroller;
    private VelocityTracker vt;
    private final int slop, minFling, maxFling;
    private float off;
    private float downX, downY, lastY;
    private boolean dragging, longFired;
    private AppEntry pressed;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable longPress = new Runnable() {
        @Override public void run() {
            if (pressed != null && !dragging && listener != null) {
                longFired = true;
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                listener.onLongPress(pressed);
            }
        }
    };

    AppGridView(Context c, int cols, float padXdp, float rowHdp, float iconDp, float topPadDp,
                float bottomPadDp, boolean scrollable, boolean labels) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        this.cols = cols;
        this.padX = padXdp * d;
        this.rowH = rowHdp * d;
        this.iconSize = iconDp * d;
        this.topPad = topPadDp * d;
        this.bottomPad = bottomPadDp * d;
        this.scrollable = scrollable;
        this.labels = labels;
        this.labelGap = 6 * d;
        scroller = new OverScroller(c);
        ViewConfiguration vc = ViewConfiguration.get(c);
        slop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity();
        maxFling = vc.getScaledMaximumFlingVelocity();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(11 * c.getResources().getDisplayMetrics().scaledDensity);
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textPaint.setShadowLayer(3 * d, 0, d, 0x66000000);
        badgeR = 9 * d;
        badgePaint.setColor(0xFFF03E3E);
        badgeText.setColor(Color.WHITE);
        badgeText.setTextAlign(Paint.Align.CENTER);
        badgeText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        badgeText.setTextSize(11 * d);
        setLayerType(LAYER_TYPE_NONE, null);
    }

    void setListener(Listener l) { listener = l; }

    void setBottomInset(int px) { bottomPad = px + 12 * getResources().getDisplayMetrics().density; invalidate(); }

    void setItems(List<AppEntry> list) {
        items = list;
        off = 0;
        scroller.forceFinished(true);
        if (labels) {
            float maxW = (getWidth() > 0 ? (getWidth() - 2 * padX) / cols : 200) - 6;
            for (AppEntry e : items) if (e.drawLabel == null) e.drawLabel = fit(e.label, maxW);
        }
        invalidate();
    }

    boolean atTop() { return off <= 0.5f; }

    private String fit(String s, float maxW) {
        if (textPaint.measureText(s) <= maxW) return s;
        int n = textPaint.breakText(s, true, maxW - textPaint.measureText("…"), null);
        return s.substring(0, Math.max(1, n)).trim() + "…";
    }

    private float cellW() { return (getWidth() - 2 * padX) / cols; }

    private float maxOff() {
        int rows = (items.size() + cols - 1) / cols;
        float content = topPad + rows * rowH + bottomPad;
        return Math.max(0, content - getHeight());
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (labels) {
            float maxW = (w - 2 * padX) / cols - 6;
            for (AppEntry e : items) e.drawLabel = fit(e.label, maxW);
        }
    }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            off = scroller.getCurrY();
            postInvalidateOnAnimation();
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        final float cw = cellW();
        final int h = getHeight();
        final int n = items.size();
        int firstRow = Math.max(0, (int) ((off - topPad) / rowH));
        int lastRow = (int) ((off + h - topPad) / rowH);
        for (int r = firstRow; r <= lastRow; r++) {
            float top = topPad + r * rowH - off;
            for (int c = 0; c < cols; c++) {
                int i = r * cols + c;
                if (i >= n) return;
                AppEntry e = items.get(i);
                float cx = padX + (c + 0.5f) * cw;
                Bitmap b = e.icon;
                if (b != null) {
                    canvas.drawBitmap(b, cx - b.getWidth() / 2f, top, iconPaint);
                }
                Integer cnt = notifs.get(e.cn.getPackageName());
                if (cnt != null && b != null) drawBadge(canvas, cx + iconSize / 2f - badgeR * 0.35f, top + badgeR * 0.35f, cnt);
                if (labels && e.drawLabel != null) {
                    canvas.drawText(e.drawLabel, cx, top + iconSize + labelGap + textPaint.getTextSize(), textPaint);
                }
            }
        }
    }

    private java.util.Map<String, Integer> notifs = java.util.Collections.emptyMap();

    void setNotifCounts(java.util.Map<String, Integer> m) {
        notifs = m;
        invalidate();
    }

    private void drawBadge(Canvas c, float x, float y, int n) {
        String t = n > 99 ? "99+" : String.valueOf(n);
        float w = Math.max(badgeR * 2, badgeText.measureText(t) + badgeR * 0.8f);
        c.drawRoundRect(x - w / 2, y - badgeR, x + w / 2, y + badgeR, badgeR, badgeR, badgePaint);
        c.drawText(t, x, y + badgeText.getTextSize() * 0.35f, badgeText);
    }

    private AppEntry hit(float x, float y) {
        float cw = cellW();
        int c = (int) ((x - padX) / cw);
        if (c < 0 || c >= cols) return null;
        int r = (int) ((y + off - topPad) / rowH);
        if (r < 0) return null;
        int i = r * cols + c;
        return i < items.size() ? items.get(i) : null;
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scroller.forceFinished(true);
                downX = ev.getX(); downY = lastY = ev.getY();
                dragging = false; longFired = false;
                pressed = hit(downX, downY);
                if (pressed != null) handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE: {
                float y = ev.getY();
                if (!dragging && scrollable && Math.abs(y - downY) > slop) {
                    dragging = true;
                    handler.removeCallbacks(longPress);
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                if (dragging) {
                    off = Math.max(0, Math.min(maxOff(), off - (y - lastY)));
                    invalidate();
                }
                lastY = y;
                return true;
            }
            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPress);
                if (dragging) {
                    vt.computeCurrentVelocity(1000, maxFling);
                    float v = vt.getYVelocity();
                    if (Math.abs(v) > minFling) {
                        scroller.fling(0, (int) off, 0, (int) -v, 0, 0, 0, (int) maxOff());
                        postInvalidateOnAnimation();
                    }
                } else if (!longFired && pressed != null && listener != null
                        && Math.abs(ev.getX() - downX) < slop * 2 && Math.abs(ev.getY() - downY) < slop * 2) {
                    listener.onTap(pressed);
                }
                recycle();
                return true;
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPress);
                recycle();
                return true;
        }
        return true;
    }

    private void recycle() {
        if (vt != null) { vt.recycle(); vt = null; }
        pressed = null;
    }
}
