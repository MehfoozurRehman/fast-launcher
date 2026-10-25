package app.fastlauncher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small hand-drawn UI kit: everything is a plain View, no themes, no inflation. */
final class Ui {
    private Ui() {}

    static final int FG = 0xFFFFFFFF;
    static final int FG2 = 0xA6FFFFFF;
    static final int FG3 = 0x66FFFFFF;
    static final int CARD = 0xFF16171A;
    static final int FILL = 0x1AFFFFFF;
    static final int INK = 0xFF111214;
    static final int DANGER = 0xFFFF8A80;
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Interpolator EMPHASIZED = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    static final Interpolator STANDARD = new PathInterpolator(0.2f, 0f, 0f, 1f);

    static float dp(Context c, float v) { return v * c.getResources().getDisplayMetrics().density; }
    static int dpi(Context c, float v) { return Math.round(dp(c, v)); }

    static GradientDrawable round(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    static TextView text(Context c, CharSequence s, float sp, int color, boolean medium) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(medium ? MEDIUM : REGULAR);
        t.setIncludeFontPadding(false);
        return t;
    }

    static TextView label(Context c, String s) {
        TextView t = text(c, s.toUpperCase(), 11.5f, FG3, true);
        t.setLetterSpacing(0.09f);
        return t;
    }

    /** Gentle press-to-shrink feedback for anything tappable. */
    static void pressable(final View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        view.animate().scaleX(0.96f).scaleY(0.96f).alpha(0.85f).setDuration(90).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        view.animate().scaleX(1f).scaleY(1f).alpha(view.isEnabled() ? 1f : 0.5f)
                                .setDuration(160).setInterpolator(STANDARD).start();
                        break;
                }
                return false;
            }
        });
    }

    static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    static LinearLayout.LayoutParams lp(int w, int h, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = top;
        return p;
    }

    // ------------------------------------------------------------------ glyphs

    static final int G_SEARCH = 0, G_SHUFFLE = 1, G_GRID = 2, G_BACK = 3, G_CHECK = 4, G_SPIN = 5, G_EYE = 6;

    /** Line-art glyphs drawn on a 24-unit grid. */
    static final class Glyph extends View {
        int type;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Glyph(Context c, int type, int color) {
            super(c);
            this.type = type;
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        void setColor(int color) { p.setColor(color); invalidate(); }
        void setType(int t) { type = t; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth() - getPaddingLeft() - getPaddingRight();
            float h = getHeight() - getPaddingTop() - getPaddingBottom();
            float u = Math.min(w, h) / 24f;
            c.save();
            c.translate(getPaddingLeft() + (w - 24 * u) / 2f, getPaddingTop() + (h - 24 * u) / 2f);
            c.scale(u, u);
            p.setStrokeWidth(2f);
            path.reset();
            switch (type) {
                case G_SEARCH:
                    c.drawCircle(10.5f, 10.5f, 6.5f, p);
                    c.drawLine(15.5f, 15.5f, 20f, 20f, p);
                    break;
                case G_SHUFFLE:
                    path.moveTo(4, 7); path.lineTo(8, 7);
                    path.cubicTo(13, 7, 11, 17, 16, 17); path.lineTo(20, 17);
                    path.moveTo(4, 17); path.lineTo(8, 17);
                    path.cubicTo(13, 17, 11, 7, 16, 7); path.lineTo(20, 7);
                    path.moveTo(17, 4); path.lineTo(20, 7); path.lineTo(17, 10);
                    path.moveTo(17, 14); path.lineTo(20, 17); path.lineTo(17, 20);
                    c.drawPath(path, p);
                    break;
                case G_GRID: {
                    RectF r = new RectF();
                    for (int i = 0; i < 4; i++) {
                        float x = 4 + (i % 2) * 9, y = 4 + (i / 2) * 9;
                        r.set(x, y, x + 7, y + 7);
                        c.drawRoundRect(r, 2, 2, p);
                    }
                    break;
                }
                case G_BACK:
                    path.moveTo(14.5f, 5); path.lineTo(7.5f, 12); path.lineTo(14.5f, 19);
                    c.drawPath(path, p);
                    break;
                case G_CHECK:
                    path.moveTo(5, 12.5f); path.lineTo(10, 17.5f); path.lineTo(19, 7);
                    c.drawPath(path, p);
                    break;
                case G_EYE:
                    path.moveTo(2.5f, 12);
                    path.cubicTo(6, 5.5f, 18, 5.5f, 21.5f, 12);
                    path.cubicTo(18, 18.5f, 6, 18.5f, 2.5f, 12);
                    c.drawPath(path, p);
                    c.drawCircle(12, 12, 3, p);
                    break;
                case G_SPIN: {
                    float a = (android.os.SystemClock.uptimeMillis() % 900) / 900f * 360f;
                    c.drawArc(new RectF(4, 4, 20, 20), a, 270, false, p);
                    postInvalidateOnAnimation();
                    break;
                }
            }
            c.restore();
        }
    }

    // ------------------------------------------------------------------ pill button

    static final class Pill extends LinearLayout {
        final Glyph glyph;
        final TextView label;
        private final int glyphType;
        private final String text;

        Pill(Context c, int glyphType, String text, boolean primary) {
            super(c);
            this.glyphType = glyphType;
            this.text = text;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER);
            int h = dpi(c, 48);
            setMinimumHeight(h);
            setPadding(dpi(c, 18), 0, dpi(c, 20), 0);
            setBackground(round(primary ? Color.WHITE : 0x24FFFFFF, h / 2f));
            int fg = primary ? INK : FG;
            glyph = new Glyph(c, glyphType, fg);
            addView(glyph, lp(dpi(c, 20), dpi(c, 20)));
            label = text(c, text, 15, fg, true);
            LinearLayout.LayoutParams tp = lp(-2, -2);
            tp.leftMargin = dpi(c, 8);
            addView(label, tp);
            setClickable(true);
            pressable(this);
        }

        void setBusy(boolean busy, String busyText) {
            glyph.setType(busy ? G_SPIN : glyphType);
            label.setText(busy ? busyText : text);
            setEnabled(!busy);
            setAlpha(busy ? 0.8f : 1f);
        }
    }

    // ------------------------------------------------------------------ chip

    static final class Chip extends TextView {
        private boolean on;

        Chip(Context c, String text) {
            super(c);
            setText(text);
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            setTypeface(MEDIUM);
            setIncludeFontPadding(false);
            setGravity(Gravity.CENTER);
            setPadding(dpi(c, 16), 0, dpi(c, 16), 0);
            setMinHeight(dpi(c, 38));
            setClickable(true);
            pressable(this);
            setOn(false);
        }

        boolean isOn() { return on; }

        void setOn(boolean v) {
            on = v;
            float r = dp(getContext(), 19);
            setBackground(round(v ? Color.WHITE : FILL, r));
            setTextColor(v ? INK : FG);
        }
    }

    /** Wraps children onto rows. */
    static final class Flow extends ViewGroup {
        private final int gap;

        Flow(Context c, int gapPx) { super(c); gap = gapPx; }

        @Override protected void onMeasure(int ws, int hs) {
            int maxW = MeasureSpec.getSize(ws);
            int x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                ch.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED);
                int w = ch.getMeasuredWidth(), h = ch.getMeasuredHeight();
                if (x > 0 && x + w > maxW) { x = 0; y += rowH + gap; rowH = 0; }
                x += w + gap;
                rowH = Math.max(rowH, h);
            }
            setMeasuredDimension(maxW, y + rowH);
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int maxW = r - l, x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                int w = ch.getMeasuredWidth(), h = ch.getMeasuredHeight();
                if (x > 0 && x + w > maxW) { x = 0; y += rowH + gap; rowH = 0; }
                ch.layout(x, y, x + w, y + h);
                x += w + gap;
                rowH = Math.max(rowH, h);
            }
        }
    }

    // ------------------------------------------------------------------ segmented control

    interface OnPick { void onPick(int index); }

    static final class Segmented extends View {
        private final String[] options;
        private int selected;
        private float pos; // animated indicator position, in segments
        private OnPick listener;
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private ValueAnimator anim;

        Segmented(Context c, String[] options, int selected) {
            super(c);
            this.options = options;
            this.selected = selected;
            this.pos = selected;
            track.setColor(FILL);
            thumb.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(MEDIUM);
            text.setTextSize(dp(c, 14));
        }

        void setListener(OnPick l) { listener = l; }

        @Override protected void onMeasure(int ws, int hs) {
            setMeasuredDimension(MeasureSpec.getSize(ws), dpi(getContext(), 44));
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2f, inset = dp(getContext(), 4);
            c.drawRoundRect(0, 0, w, h, r, r, track);
            float seg = w / options.length;
            float x = pos * seg;
            c.drawRoundRect(x + inset, inset, x + seg - inset, h - inset, r - inset, r - inset, thumb);
            for (int i = 0; i < options.length; i++) {
                float d = Math.max(0f, 1f - Math.abs(pos - i));
                text.setColor(blend(FG2, INK, d));
                c.drawText(options[i], seg * (i + 0.5f), h / 2f + text.getTextSize() * 0.36f, text);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) return true;
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                int i = (int) (e.getX() / (getWidth() / (float) options.length));
                i = Math.max(0, Math.min(options.length - 1, i));
                if (i != selected) {
                    selected = i;
                    if (anim != null) anim.cancel();
                    anim = ValueAnimator.ofFloat(pos, i);
                    anim.setDuration(260);
                    anim.setInterpolator(EMPHASIZED);
                    anim.addUpdateListener(a -> { pos = (Float) a.getAnimatedValue(); invalidate(); });
                    anim.start();
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                    if (listener != null) listener.onPick(i);
                }
                return true;
            }
            return true;
        }
    }

    // ------------------------------------------------------------------ switch

    static final class Toggle extends View {
        private boolean on;
        private float pos;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private OnPick listener;

        Toggle(Context c, boolean on) {
            super(c);
            this.on = on;
            this.pos = on ? 1 : 0;
            setClickable(true);
            setOnClickListener(v -> {
                this.on = !this.on;
                ValueAnimator a = ValueAnimator.ofFloat(pos, this.on ? 1 : 0);
                a.setDuration(220);
                a.setInterpolator(EMPHASIZED);
                a.addUpdateListener(an -> { pos = (Float) an.getAnimatedValue(); invalidate(); });
                a.start();
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                if (listener != null) listener.onPick(this.on ? 1 : 0);
            });
        }

        void setListener(OnPick l) { listener = l; }

        @Override protected void onMeasure(int ws, int hs) {
            setMeasuredDimension(dpi(getContext(), 52), dpi(getContext(), 32));
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2f, in = dp(getContext(), 3);
            p.setColor(blend(0x33FFFFFF, Color.WHITE, pos));
            c.drawRoundRect(0, 0, w, h, r, r, p);
            float cx = r + (w - 2 * r) * pos;
            p.setColor(blend(Color.WHITE, INK, pos));
            c.drawCircle(cx, r, r - in, p);
        }
    }

    // ------------------------------------------------------------------ rounded image

    /** Shows a bitmap centre-cropped into a rounded rect, over a placeholder colour. */
    static final class RoundImage extends View {
        private Bitmap bmp;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint ph = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix m = new Matrix();
        private final float radius;
        private long shownAt;

        RoundImage(Context c, float radiusPx) {
            super(c);
            radius = radiusPx;
            ph.setColor(0xFF24262B);
        }

        void setPlaceholder(int color) { ph.setColor(color); invalidate(); }

        void setBitmap(Bitmap b) {
            bmp = b;
            if (b != null) {
                paint.setShader(new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                shownAt = android.os.SystemClock.uptimeMillis();
            } else paint.setShader(null);
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            c.drawRoundRect(0, 0, w, h, radius, radius, ph);
            if (bmp == null) return;
            float s = Math.max(w / bmp.getWidth(), h / bmp.getHeight());
            m.setScale(s, s);
            m.postTranslate((w - bmp.getWidth() * s) / 2f, (h - bmp.getHeight() * s) / 2f);
            paint.getShader().setLocalMatrix(m);
            float t = Math.min(1f, (android.os.SystemClock.uptimeMillis() - shownAt) / 220f);
            paint.setAlpha(Math.round(255 * t));
            c.drawRoundRect(0, 0, w, h, radius, radius, paint);
            if (t < 1f) postInvalidateOnAnimation();
        }
    }

    static int blend(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = Color.alpha(a), ar = Color.red(a), ag = Color.green(a), ab = Color.blue(a);
        return Color.argb(Math.round(aa + (Color.alpha(b) - aa) * t), Math.round(ar + (Color.red(b) - ar) * t),
                Math.round(ag + (Color.green(b) - ag) * t), Math.round(ab + (Color.blue(b) - ab) * t));
    }

    static int darken(int color, float f) {
        return Color.rgb(Math.round(Color.red(color) * f), Math.round(Color.green(color) * f), Math.round(Color.blue(color) * f));
    }
}
