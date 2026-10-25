package app.fastlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.EdgeEffect;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One view that draws a whole grid of pre-rendered icon bitmaps. No child views, no
 * layouts: scrolling is a translate + drawBitmap on the visible rows only.
 */
final class AppGridView extends View {

    static final class AppEntry {
        String label, lower, drawLabel;
        ComponentName cn;
        ActivityInfo ai;
        volatile Bitmap icon;
        boolean blank;
    }

    interface Listener {
        void onTap(AppEntry e, int index);
        void onLongPress(AppEntry e, int index, float cx, float cy);
    }

    private List<AppEntry> items = new ArrayList<>();
    private Listener listener;

    private final int cols;
    private final boolean scrollable, labels;
    private final float padX, rowH, iconSize, topPad, labelGap, density;
    private float bottomPad;

    private final Paint iconPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint slotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float badgeR;
    private Map<String, Integer> notifs = Collections.emptyMap();
    private boolean slots;
    private long slotsSince;

    private final OverScroller scroller;
    private final EdgeEffect bottomEdge;
    private VelocityTracker vt;
    private final int slop, minFling, maxFling;
    private float off;
    private float downX, downY, lastY;
    private boolean dragging, longFired;
    private int pressedIndex = -1;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable longPress = new Runnable() {
        @Override public void run() {
            if (pressedIndex < 0 || dragging || listener == null) return;
            longFired = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            float[] c = cellCenter(pressedIndex);
            listener.onLongPress(items.get(pressedIndex), pressedIndex, c[0], c[1]);
        }
    };

    AppGridView(Context c, int cols, float padXdp, float rowHdp, float iconDp, float topPadDp,
                float bottomPadDp, boolean scrollable, boolean labels) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        float d = density;
        this.cols = cols;
        this.padX = padXdp * d;
        this.rowH = rowHdp * d;
        this.iconSize = iconDp * d;
        this.topPad = topPadDp * d;
        this.bottomPad = bottomPadDp * d;
        this.scrollable = scrollable;
        this.labels = labels;
        this.labelGap = 7 * d;
        scroller = new OverScroller(c);
        bottomEdge = new EdgeEffect(c);
        ViewConfiguration vc = ViewConfiguration.get(c);
        slop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity();
        maxFling = vc.getScaledMaximumFlingVelocity();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(11 * c.getResources().getDisplayMetrics().scaledDensity);
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textPaint.setShadowLayer(3 * d, 0, d, 0x73000000);
        badgeR = 9.5f * d;
        badgePaint.setColor(0xFFFF3B30);
        badgeRing.setColor(Color.WHITE);
        badgeText.setColor(Color.WHITE);
        badgeText.setTextAlign(Paint.Align.CENTER);
        badgeText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        badgeText.setTextSize(11.5f * d);
        slotPaint.setStyle(Paint.Style.STROKE);
        slotPaint.setStrokeWidth(1.6f * d);
        slotPaint.setColor(0xCCFFFFFF);
        slotPaint.setPathEffect(new DashPathEffect(new float[]{6 * d, 5 * d}, 0));
    }

    void setListener(Listener l) { listener = l; }

    void setBottomInset(int px) { bottomPad = px + 14 * density; invalidate(); }

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

    void setNotifCounts(Map<String, Integer> m) {
        notifs = m;
        invalidate();
    }

    /** Dashed outlines on every slot, used when choosing where to put an app. */
    void setSlotOutlines(boolean on) {
        slots = on;
        slotsSince = SystemClock.uptimeMillis();
        invalidate();
    }

    boolean atTop() { return off <= 0.5f; }

    private String fit(String s, float maxW) {
        if (textPaint.measureText(s) <= maxW) return s;
        int n = textPaint.breakText(s, true, maxW - textPaint.measureText("…"), null);
        return s.substring(0, Math.max(1, n)).trim() + "…";
    }

    private float cellW() { return (getWidth() - 2 * padX) / cols; }

    float[] cellCenter(int i) {
        float cx = padX + ((i % cols) + 0.5f) * cellW();
        float cy = topPad + (i / cols) * rowH - off + iconSize / 2f;
        return new float[]{cx, cy};
    }

    float iconSizePx() { return iconSize; }

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
            if (off >= maxOff() && maxOff() > 0 && bottomEdge.isFinished()) {
                bottomEdge.onAbsorb((int) scroller.getCurrVelocity());
                scroller.forceFinished(true);
            }
            off = Math.max(0, Math.min(maxOff(), off));
            postInvalidateOnAnimation();
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        final float cw = cellW();
        final int h = getHeight();
        final int n = items.size();
        int firstRow = Math.max(0, (int) ((off - topPad) / rowH));
        int lastRow = (int) ((off + h - topPad) / rowH);
        float slotAlpha = slots ? Math.min(1f, (SystemClock.uptimeMillis() - slotsSince) / 180f) : 0f;
        outer:
        for (int r = firstRow; r <= lastRow; r++) {
            float top = topPad + r * rowH - off;
            for (int c = 0; c < cols; c++) {
                int i = r * cols + c;
                if (i >= n) break outer;
                AppEntry e = items.get(i);
                float cx = padX + (c + 0.5f) * cw;
                if (slots) {
                    float half = iconSize / 2f + 3 * density;
                    slotPaint.setAlpha(Math.round(200 * slotAlpha));
                    canvas.drawRoundRect(cx - half, top - 3 * density, cx + half, top + iconSize + 3 * density,
                            half * 0.62f, half * 0.62f, slotPaint);
                }
                Bitmap b = e.icon;
                if (b != null && !e.blank) {
                    float pad = (b.getWidth() - iconSize) / 2f;
                    iconPaint.setAlpha(slots ? 150 : 255);
                    canvas.drawBitmap(b, cx - b.getWidth() / 2f, top - pad, iconPaint);
                    Integer cnt = e.cn == null ? null : notifs.get(e.cn.getPackageName());
                    if (cnt != null && !slots) drawBadge(canvas, cx + iconSize / 2f - badgeR * 0.45f, top + badgeR * 0.45f, cnt);
                }
                if (labels && e.drawLabel != null) {
                    canvas.drawText(e.drawLabel, cx, top + iconSize + labelGap + textPaint.getTextSize(), textPaint);
                }
            }
        }
        if (slots && slotAlpha < 1f) postInvalidateOnAnimation();
        if (!bottomEdge.isFinished()) {
            int save = canvas.save();
            canvas.rotate(180, getWidth() / 2f, getHeight() / 2f);
            bottomEdge.setSize(getWidth(), getHeight());
            if (bottomEdge.draw(canvas)) postInvalidateOnAnimation();
            canvas.restoreToCount(save);
        }
    }

    private void drawBadge(Canvas c, float x, float y, int n) {
        String t = n > 99 ? "99+" : String.valueOf(n);
        float w = Math.max(badgeR * 2, badgeText.measureText(t) + badgeR * 0.9f);
        float ring = 1.6f * density;
        c.drawRoundRect(x - w / 2 - ring, y - badgeR - ring, x + w / 2 + ring, y + badgeR + ring,
                badgeR + ring, badgeR + ring, badgeRing);
        c.drawRoundRect(x - w / 2, y - badgeR, x + w / 2, y + badgeR, badgeR, badgeR, badgePaint);
        c.drawText(t, x, y + badgeText.getTextSize() * 0.36f, badgeText);
    }

    private int hit(float x, float y) {
        float cw = cellW();
        int c = (int) ((x - padX) / cw);
        if (x < padX || c < 0 || c >= cols) return -1;
        float yy = y + off - topPad;
        if (yy < -6 * density) return -1;
        int r = (int) (Math.max(0, yy) / rowH);
        int i = r * cols + c;
        return i < items.size() ? i : -1;
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scroller.forceFinished(true);
                downX = ev.getX(); downY = lastY = ev.getY();
                dragging = false; longFired = false;
                pressedIndex = hit(downX, downY);
                if (pressedIndex >= 0 && !items.get(pressedIndex).blank)
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE: {
                float y = ev.getY();
                if (!dragging && scrollable && Math.abs(y - downY) > slop) {
                    dragging = true;
                    handler.removeCallbacks(longPress);
                }
                if (!dragging && (Math.abs(y - downY) > slop || Math.abs(ev.getX() - downX) > slop)) {
                    handler.removeCallbacks(longPress);
                }
                if (dragging) {
                    float next = off - (y - lastY);
                    if (next > maxOff()) {
                        bottomEdge.onPull((next - maxOff()) / getHeight(), 1f - ev.getX() / getWidth());
                        next = maxOff();
                    }
                    off = Math.max(0, next);
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
                    }
                } else if (!longFired && pressedIndex >= 0 && listener != null
                        && Math.abs(ev.getX() - downX) < slop * 2 && Math.abs(ev.getY() - downY) < slop * 2) {
                    listener.onTap(items.get(pressedIndex), pressedIndex);
                }
                bottomEdge.onRelease();
                postInvalidateOnAnimation();
                recycle();
                return true;
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPress);
                bottomEdge.onRelease();
                recycle();
                return true;
        }
        return true;
    }

    private void recycle() {
        if (vt != null) { vt.recycle(); vt = null; }
        pressedIndex = -1;
    }
}
