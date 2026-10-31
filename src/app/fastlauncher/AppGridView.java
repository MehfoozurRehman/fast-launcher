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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

final class AppGridView extends View {
    static final class AppEntry {
        String label, lower, drawLabel;
        ComponentName cn;
        ActivityInfo ai;
        volatile Bitmap icon;
        volatile Bitmap cell, cellFor;
        boolean blank;
    }

    interface Listener {
        void onTap(AppEntry e, int index);
        void onLongPress(AppEntry e, int index, float cx, float cy);
        default void onFooterTap(float cx, float cy) {}
        default void onPressStart(AppEntry e) {}
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
    private float reveal = 1f;
    private String footer;
    private final Paint footerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint footerText = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final boolean scroller;
    private boolean flinging;
    private float flingDist, flingDur, flingFrom;
    private long flingT0;
    private final float flingCoeff;
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
            if (pressedIndex < 0 || pressedIndex >= items.size() || dragging || listener == null) return;
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
        if (scrollable) {
            scroller = true;
            bottomEdge = new EdgeEffect(c);
        } else {
            scroller = false;
            bottomEdge = null;
        }
        flingCoeff = ViewConfiguration.getScrollFriction() * 0.7f * android.hardware.SensorManager.GRAVITY_EARTH * 39.37f * (density * 160f) * 0.84f;
        ViewConfiguration vc = ViewConfiguration.get(c);
        slop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity();
        maxFling = vc.getScaledMaximumFlingVelocity();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(11 * c.getResources().getDisplayMetrics().scaledDensity);
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
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
        footerPaint.setColor(0x26FFFFFF);
        footerText.setColor(0xCCFFFFFF);
        footerText.setTextAlign(Paint.Align.CENTER);
        footerText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        footerText.setTextSize(13 * c.getResources().getDisplayMetrics().scaledDensity);
    }

    void setFooter(String text) {
        footer = text;
        invalidate();
    }

    private float footerTop() {
        int rows = (items.size() + cols - 1) / cols;
        return topPad + rows * rowH + 6 * density;
    }

    private float footerH() { return footer == null ? 0 : 40 * density; }

    @Override public boolean hasOverlappingRendering() { return false; }

    void setListener(Listener l) { listener = l; }

    void setBottomInset(int px) { bottomPad = px + 14 * density; invalidate(); }

    void setItems(List<AppEntry> list) {
        handler.removeCallbacks(longPress);
        pressedIndex = -1;
        items = list;
        off = 0;
        flinging = false;
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

    void setSlotOutlines(boolean on) {
        slots = on;
        slotsSince = SystemClock.uptimeMillis();
        invalidate();
    }

    boolean atTop() { return off <= 0.5f; }

    void setReveal(float p) {
        if (p == reveal) return;
        reveal = p;
        invalidate();
    }

    private float originX = -1f;

    void setRevealOrigin(float x) { originX = x; }

    private static float settle(float t) {
        float c1 = 1.35f, c3 = c1 + 1f, u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    private String fit(String s, float maxW) {
        if (textPaint.measureText(s) <= maxW) return s;
        int n = textPaint.breakText(s, true, maxW - textPaint.measureText("…"), null);
        return s.substring(0, Math.max(1, n)).trim() + "…";
    }

    static boolean debugNoCells;
    private boolean needCells, buildingCells;
    private static final java.util.concurrent.ExecutorService cellWorker = java.util.concurrent.Executors.newSingleThreadExecutor();

    private void buildCells() {
        if (!labels || buildingCells || getWidth() <= 0) return;
        buildingCells = true;
        final List<AppEntry> snapshot = new ArrayList<>(items);
        final int w = Math.round(cellW());
        final Paint tp = new Paint(textPaint);
        final float gap = labelGap, size = iconSize;
        cellWorker.execute(() -> {
            try {
                int made = 0;
                for (AppEntry e : snapshot) {
                    Bitmap ic = e.icon;
                    if (ic == null || e.blank || e.drawLabel == null || (e.cell != null && e.cellFor == ic)) continue;
                    Bitmap soft = ic.copy(Bitmap.Config.ARGB_8888, false);
                    if (soft == null) continue;
                    float pad = (ic.getWidth() - size) / 2f;
                    float base = pad + size + gap + tp.getTextSize();
                    int h = (int) Math.ceil(base + tp.descent() + 2);
                    Bitmap cell = Bitmap.createBitmap(Math.max(w, ic.getWidth()), Math.max(h, ic.getHeight()), Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(cell);
                    c.drawBitmap(soft, (cell.getWidth() - ic.getWidth()) / 2f, 0, null);
                    c.drawText(e.drawLabel, cell.getWidth() / 2f, base, tp);
                    soft.recycle();
                    e.cell = Icons.toHardware(cell);
                    e.cellFor = ic;
                    if (++made % 24 == 0) postInvalidate();
                }
            } catch (Throwable t) {
                Diag.log("cells: " + t);
            } finally {
                buildingCells = false;
                postInvalidate();
            }
        });
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
        float content = topPad + rows * rowH + (footer == null ? 0 : footerH() + 12 * density) + bottomPad;
        return Math.max(0, content - getHeight());
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (labels) {
            float maxW = (w - 2 * padX) / cols - 6;
            for (AppEntry e : items) { e.drawLabel = fit(e.label, maxW); e.cell = null; e.cellFor = null; }
            needCells = true;
            invalidate();
        }
    }

    private static final float FLING_POWER = 2.86f;

    private void startFling(float v) {
        float av = Math.abs(v);
        double l = Math.log(0.35f * av / flingCoeff);
        flingDist = (float) (Math.signum(v) * flingCoeff * Math.exp(2.358 / 1.358 * l));
        flingDur = (float) (1000.0 * Math.exp(l / 1.358));
        flingFrom = off;
        flingT0 = android.view.animation.AnimationUtils.currentAnimationTimeMillis();
        flinging = true;
        postInvalidateOnAnimation();
    }

    @Override public void computeScroll() {
        if (flinging) {
            float sec = flingDur / 1000f;
            float t = (android.view.animation.AnimationUtils.currentAnimationTimeMillis() - flingT0) / flingDur;
            boolean done = t >= 1f;
            if (done) t = 1f;
            float left = 1f - t;
            float next = flingFrom + flingDist * (1f - (float) Math.pow(left, FLING_POWER));
            float v = Math.abs(flingDist) * FLING_POWER * (float) Math.pow(left, FLING_POWER - 1f) / sec;
            float max = maxOff();
            if (flingDist > 0 && next >= max && max > 0) {
                if (bottomEdge.isFinished()) bottomEdge.onAbsorb((int) v);
                next = max;
                done = true;
            } else if (next <= 0 && flingDist < 0) {
                next = 0;
                done = true;
            }
            off = Math.max(0, Math.min(max, next));
            if (done) {
                flinging = false;
                off = Math.round(off);
            }
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
        final boolean revealing = reveal < 1f;
        outer:
        for (int r = firstRow; r <= lastRow; r++) {
            float top = topPad + r * rowH - off;
            int rowAlpha = 255;
            iconPaint.setAlpha(slots ? 150 : 255);
            textPaint.setAlpha(255);
            for (int c = 0; c < cols; c++) {
                int i = r * cols + c;
                if (i >= n) break outer;
                AppEntry e = items.get(i);
                float cx = padX + (c + 0.5f) * cw;
                int save = -1;
                if (revealing) {
                    float ox = originX < 0 ? getWidth() / 2f : originX, oy = h + 40 * density;
                    float iy = top + iconSize / 2f;
                    float dist = (float) Math.hypot(cx - ox, iy - oy) / (float) Math.hypot(getWidth(), h + 40 * density);
                    float local = Math.max(0f, Math.min(1f, (reveal - dist * 0.3f) / 0.7f));
                    if (local <= 0f) continue;
                    float ease = settle(local);
                    rowAlpha = Math.round(255 * Math.min(1f, local * 1.5f));
                    iconPaint.setAlpha(slots ? 150 * rowAlpha / 255 : rowAlpha);
                    textPaint.setAlpha(rowAlpha);
                    float sc = 0.8f + 0.2f * ease;
                    save = canvas.save();
                    canvas.scale(sc, sc, cx, iy);
                }
                if (slots) {
                    float half = iconSize / 2f + 3 * density;
                    slotPaint.setAlpha(Math.round(200 * slotAlpha));
                    canvas.drawRoundRect(cx - half, top - 3 * density, cx + half, top + iconSize + 3 * density,
                            half * 0.62f, half * 0.62f, slotPaint);
                }
                Bitmap b = e.icon;
                boolean cellDrawn = false;
                if (b != null && !e.blank) {
                    float pad = (b.getWidth() - iconSize) / 2f;
                    Bitmap cell = e.cell;
                    if (labels && !debugNoCells && cell != null && e.cellFor == b) {
                        canvas.drawBitmap(cell, cx - cell.getWidth() / 2f, top - pad, iconPaint);
                        cellDrawn = true;
                    } else {
                        canvas.drawBitmap(b, cx - b.getWidth() / 2f, top - pad, iconPaint);
                        if (labels) needCells = true;
                    }
                    Integer cnt = e.cn == null ? null : notifs.get(e.cn.getPackageName());
                    if (cnt != null && !slots && rowAlpha == 255) drawBadge(canvas, cx + iconSize * 0.36f, top + iconSize * 0.12f, cnt);
                }
                if (labels && !cellDrawn && e.drawLabel != null) {
                    canvas.drawText(e.drawLabel, cx, top + iconSize + labelGap + textPaint.getTextSize(), textPaint);
                }
                if (save >= 0) canvas.restoreToCount(save);
            }
        }
        if (footer != null && !revealing) {
            float y = footerTop() - off;
            if (y < h && y + footerH() > 0) {
                float w = footerText.measureText(footer) + 36 * density, cx = getWidth() / 2f;
                canvas.drawRoundRect(cx - w / 2, y, cx + w / 2, y + footerH(), footerH() / 2, footerH() / 2, footerPaint);
                canvas.drawText(footer, cx, y + footerH() / 2 + footerText.getTextSize() * 0.35f, footerText);
            }
        }
        if (needCells) { needCells = false; buildCells(); }
        if (slots && slotAlpha < 1f) postInvalidateOnAnimation();
        if (bottomEdge != null && !bottomEdge.isFinished()) {
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
                flinging = false;
                downX = ev.getX(); downY = lastY = ev.getY();
                dragging = false; longFired = false;
                pressedIndex = hit(downX, downY);
                if (pressedIndex >= 0 && !items.get(pressedIndex).blank) {
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                    if (listener != null) listener.onPressStart(items.get(pressedIndex));
                }
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
                        startFling(-v);
                    }
                    if (!flinging) off = Math.round(off);
                } else if (!longFired && pressedIndex >= 0 && pressedIndex < items.size() && listener != null
                        && Math.abs(ev.getX() - downX) < slop * 2 && Math.abs(ev.getY() - downY) < slop * 2) {
                    listener.onTap(items.get(pressedIndex), pressedIndex);
                } else if (pressedIndex < 0 && footer != null && listener != null
                        && Math.abs(ev.getY() - downY) < slop * 2) {
                    float y = footerTop() - off;
                    if (ev.getY() >= y && ev.getY() <= y + footerH()) listener.onFooterTap(getWidth() / 2f, y + footerH() / 2);
                }
                if (bottomEdge != null) bottomEdge.onRelease();
                postInvalidateOnAnimation();
                recycle();
                return true;
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPress);
                if (bottomEdge != null) bottomEdge.onRelease();
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
