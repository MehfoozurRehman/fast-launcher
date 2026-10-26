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

    // Palette for every surface the launcher draws over its own background (panels, menus,
    // sheets). It matches the system's own pop-ups, sampled from the phone's Wi-Fi panel, and
    // follows light/dark mode. Anything drawn straight on the wallpaper stays white.
    static boolean dark = true;
    static int FG, FG2, FG3, CARD, GROUP, FILL, ICON_BG, LINE, PRESS, HANDLE, SCRIM, BG,
            TRACK, STRONG, ON_STRONG, INK, ACCENT, DANGER, DANGER_BG;

    static { apply(true); }

    /** Call before building any themed view, and again when the system switches modes. */
    static boolean theme(Context c) {
        int night = c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        boolean d = night != android.content.res.Configuration.UI_MODE_NIGHT_NO;
        boolean changed = d != dark;
        apply(d);
        return changed;
    }

    private static void apply(boolean d) {
        dark = d;
        if (d) {
            // surfaces inside cards are see-through, so frosted cards stay glassy
            CARD = 0xFF2E2E2E; GROUP = 0x1AFFFFFF; FILL = 0x24FFFFFF; ICON_BG = 0x2EFFFFFF;
            FG = 0xFFEAEAEA; FG2 = 0xFFA9A9A9; FG3 = 0xFF8C8C8C;
            LINE = 0x1AFFFFFF; PRESS = 0x1FFFFFFF; HANDLE = 0x52FFFFFF; SCRIM = 0xB3000000; BG = 0xFF1F1F1F;
            TRACK = 0xFF4E4E4E; STRONG = 0xFFEAEAEA; ON_STRONG = 0xFF191919;
            ACCENT = 0xFF3D7FFF; DANGER = 0xFFFF6B60; DANGER_BG = 0x33FF453A;
        } else {
            CARD = 0xFFFFFFFF; GROUP = 0x8CFFFFFF; FILL = 0xB3FFFFFF; ICON_BG = 0xE6FFFFFF;
            FG = 0xFF191919; FG2 = 0xFF5F6368; FG3 = 0xFF8A8F96;
            LINE = 0x14000000; PRESS = 0x14000000; HANDLE = 0x33000000; SCRIM = 0x73000000; BG = 0xFFF5F6F8;
            TRACK = 0xFFD5D8DD; STRONG = 0xFF256FFF; ON_STRONG = 0xFFFFFFFF;
            ACCENT = 0xFF256FFF; DANGER = 0xFFE5392F; DANGER_BG = 0x1FE5392F;
        }
        INK = ON_STRONG;
    }
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Interpolator EMPHASIZED = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    static final Interpolator STANDARD = new PathInterpolator(0.2f, 0f, 0f, 1f);

    private static final java.util.HashMap<Integer, Typeface> FONTS = new java.util.HashMap<>();

    /** Montserrat (bundled, OFL) at any weight from its variable font; system font as fallback. */
    static Typeface font(Context c, int weight) {
        Typeface t = FONTS.get(weight);
        if (t != null) return t;
        try {
            t = new Typeface.Builder(c.getAssets(), "fonts/Montserrat.ttf")
                    .setFontVariationSettings("'wght' " + weight).build();
        } catch (Exception e) {
            t = null;
        }
        if (t == null) t = weight >= 500 ? MEDIUM : REGULAR;
        FONTS.put(weight, t);
        return t;
    }

    /** A rounded inner card that groups related settings. */
    static LinearLayout group(Context c) {
        LinearLayout g = new LinearLayout(c);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setBackground(round(GROUP, dp(c, 22)));
        int p = dpi(c, 16);
        g.setPadding(p, p, p, p);
        return g;
    }

    /** Any drawable, rendered and cropped to a circle (contact photos, shortcut icons). */
    static android.graphics.drawable.Drawable circle(Context c, android.graphics.drawable.Drawable d, int px) {
        Bitmap src = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        if (d instanceof android.graphics.drawable.AdaptiveIconDrawable) {
            // draw both layers edge to edge, skipping the system mask, then crop round
            android.graphics.drawable.AdaptiveIconDrawable a = (android.graphics.drawable.AdaptiveIconDrawable) d;
            int ext = Math.round(px * 0.25f);
            Canvas cv = new Canvas(src);
            if (a.getBackground() != null) { a.getBackground().setBounds(-ext, -ext, px + ext, px + ext); a.getBackground().draw(cv); }
            if (a.getForeground() != null) { a.getForeground().setBounds(-ext, -ext, px + ext, px + ext); a.getForeground().draw(cv); }
        } else {
            d.setBounds(0, 0, px, px);
            d.draw(new Canvas(src));
            Bitmap glyph = glyphIcon(src, px);
            if (glyph != null) return new android.graphics.drawable.BitmapDrawable(c.getResources(), glyph);
        }
        Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        new Canvas(out).drawCircle(px / 2f, px / 2f, px / 2f, p);
        return new android.graphics.drawable.BitmapDrawable(c.getResources(), out);
    }

    /**
     * Shortcut icons that are just a drawing on a see-through background (Facebook's are black
     * line art meant to be tinted by the app's own theme) vanish on a dark card. Put them on a
     * soft accent circle; a one-colour drawing is tinted the accent, a colourful one kept as is.
     * Returns null for normal, filled icons.
     */
    private static Bitmap glyphIcon(Bitmap src, int px) {
        int[] p = new int[px * px];
        src.getPixels(p, 0, px, 0, 0, px, px);
        float r = px / 2f, r2 = r * r * 0.81f; // judge the inner circle, where a filled icon is solid
        int inside = 0, clear = 0, opaque = 0;
        long sr = 0, sg = 0, sb = 0;
        int minL = 255, maxL = 0, chroma = 0;
        for (int y = 0; y < px; y++) for (int x = 0; x < px; x++) {
            float dx = x + 0.5f - r, dy = y + 0.5f - r;
            if (dx * dx + dy * dy > r2) continue;
            inside++;
            int c = p[y * px + x], a = c >>> 24;
            if (a < 24) { clear++; continue; }
            if (a < 160) continue;
            opaque++;
            int R = (c >> 16) & 255, G = (c >> 8) & 255, B = c & 255;
            int l = (R * 3 + G * 6 + B) / 10;
            minL = Math.min(minL, l); maxL = Math.max(maxL, l);
            chroma = Math.max(chroma, Math.max(R, Math.max(G, B)) - Math.min(R, Math.min(G, B)));
        }
        if (inside == 0 || opaque == 0) return null;
        boolean mono = maxL - minL < 60 && chroma < 60;
        // a big near-black drawing (a solid bell, a filled square) is just as invisible on a
        // dark card even though it covers most of the circle: catch it by its see-through corners
        int clearAll = 0;
        for (int c : p) if ((c >>> 24) < 24) clearAll++;
        boolean darkGlyph = mono && maxL < 80 && clearAll > p.length * 0.12f;
        if (clear < inside * 0.25f && !darkGlyph) return null;
        Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(mono ? (ACCENT & 0x00FFFFFF) | (dark ? 0x33000000 : 0x24000000) : (dark ? 0xE6FFFFFF : 0xFFFFFFFF));
        cv.drawCircle(r, r, r, bg);
        Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (mono) fg.setColorFilter(new android.graphics.PorterDuffColorFilter(dark ? 0xFF8AB4FF : ACCENT, android.graphics.PorterDuff.Mode.SRC_IN));
        float inset = px * 0.2f; // the drawing sits inside the circle with some air around it
        cv.drawBitmap(src, null, new RectF(inset, inset, px - inset, px - inset), fg);
        return out;
    }

    static float dp(Context c, float v) { return v * c.getResources().getDisplayMetrics().density; }
    static int dpi(Context c, float v) { return Math.round(dp(c, v)); }

    /**
     * One shared motion for content changes (rows appearing, lists closing up, things making
     * room): fade and move together, on the launcher's emphasized curve.
     */
    static android.transition.Transition motion() {
        android.transition.TransitionSet t = new android.transition.TransitionSet();
        t.setOrdering(android.transition.TransitionSet.ORDERING_TOGETHER);
        t.addTransition(new android.transition.Fade());
        t.addTransition(new android.transition.ChangeBounds());
        t.setDuration(220);
        t.setInterpolator(EMPHASIZED);
        return t;
    }

    /** Rows that fade in/out while their neighbours slide, for lists that change as you type. */
    static android.animation.LayoutTransition rows() {
        android.animation.LayoutTransition t = new android.animation.LayoutTransition();
        t.enableTransitionType(android.animation.LayoutTransition.CHANGING);
        t.setDuration(180);
        t.setInterpolator(android.animation.LayoutTransition.CHANGE_APPEARING, EMPHASIZED);
        t.setInterpolator(android.animation.LayoutTransition.CHANGE_DISAPPEARING, EMPHASIZED);
        t.setInterpolator(android.animation.LayoutTransition.CHANGING, EMPHASIZED);
        t.setStartDelay(android.animation.LayoutTransition.APPEARING, 0);
        t.setStartDelay(android.animation.LayoutTransition.CHANGE_DISAPPEARING, 0);
        t.setStartDelay(android.animation.LayoutTransition.CHANGE_APPEARING, 0);
        return t;
    }

    /** Show or hide with a short fade instead of popping. */
    static void fadeVisible(final View v, boolean show) {
        if (show == (v.getVisibility() == View.VISIBLE) && v.getAlpha() == (show ? 1f : v.getAlpha())) return;
        v.animate().cancel();
        if (show) {
            if (v.getVisibility() != View.VISIBLE) { v.setAlpha(0f); v.setVisibility(View.VISIBLE); }
            v.animate().alpha(1f).setDuration(200).setInterpolator(STANDARD).start();
        } else {
            v.animate().alpha(0f).setDuration(160).setInterpolator(STANDARD).withEndAction(() -> v.setVisibility(View.GONE)).start();
        }
    }

    /** The blurred wallpaper (same picture the drawer uses); null when the wallpaper isn't ours. */
    static Bitmap frostSource;

    /**
     * Frosted glass for launcher cards: the blurred wallpaper that sits behind the card, lined
     * up with the screen, under a light tint. Falls back to a plain card colour without a blur.
     */
    static android.graphics.drawable.Drawable frost(final View owner, final float radius) {
        final int tint = dark ? 0x66000000 : 0xB8F5F6F8;
        return new android.graphics.drawable.Drawable() {
            private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.Path clip = new android.graphics.Path();
            private final Matrix m = new Matrix();
            private final int[] loc = new int[2];

            @Override public void draw(Canvas c) {
                android.graphics.Rect b = getBounds();
                clip.reset();
                clip.addRoundRect(b.left, b.top, b.right, b.bottom, radius, radius, android.graphics.Path.Direction.CW);
                Bitmap src = frostSource; // read at draw time: the blur may load after the card is built
                if (src == null) {
                    fill.setColor(CARD);
                    c.drawPath(clip, fill);
                    return;
                }
                int save = c.save();
                c.clipPath(clip);
                // map the screen-sized blur so the part under the card is what shows through
                android.util.DisplayMetrics dm = owner.getResources().getDisplayMetrics();
                // line up with where the card rests, not where an animation has it right now: the
                // first frame is drawn while it's still off-screen (and often cached in a layer),
                // which used to leave the glass empty until the card stopped moving
                int x = owner.getLeft(), y = owner.getTop();
                if (owner.getParent() instanceof View) {
                    ((View) owner.getParent()).getLocationOnScreen(loc);
                    x += loc[0];
                    y += loc[1];
                }
                float s = Math.max(dm.widthPixels / (float) src.getWidth(), dm.heightPixels / (float) src.getHeight());
                m.setScale(s, s);
                m.postTranslate(-x, -y);
                c.drawBitmap(src, m, bmp);
                fill.setColor(tint);
                c.drawRect(b, fill);
                c.restoreToCount(save);
            }

            @Override public void getOutline(android.graphics.Outline o) {
                o.setRoundRect(getBounds(), radius);
            }

            @Override public void setAlpha(int a) { bmp.setAlpha(a); fill.setAlpha(a); }
            @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }

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

    static final int G_SEARCH = 0, G_SHUFFLE = 1, G_GRID = 2, G_BACK = 3, G_CHECK = 4, G_SPIN = 5, G_EYE = 6,
            G_INFO = 7, G_HIDE = 8, G_TRASH = 9, G_DOCK = 10, G_MINUS = 11;

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
                case G_INFO:
                    c.drawCircle(12, 12, 9, p);
                    c.drawLine(12, 11, 12, 16.5f, p);
                    c.drawPoint(12, 7.6f, p);
                    break;
                case G_HIDE:
                    path.moveTo(2.5f, 12);
                    path.cubicTo(6, 5.5f, 18, 5.5f, 21.5f, 12);
                    path.cubicTo(18, 18.5f, 6, 18.5f, 2.5f, 12);
                    c.drawPath(path, p);
                    c.drawCircle(12, 12, 3, p);
                    c.drawLine(4, 20, 20, 4, p);
                    break;
                case G_TRASH:
                    path.moveTo(5, 7); path.lineTo(19, 7);
                    path.moveTo(9.5f, 7); path.lineTo(10, 4.5f); path.lineTo(14, 4.5f); path.lineTo(14.5f, 7);
                    path.moveTo(6.5f, 7); path.lineTo(7.5f, 19.5f); path.lineTo(16.5f, 19.5f); path.lineTo(17.5f, 7);
                    path.moveTo(10.2f, 10.5f); path.lineTo(10.2f, 16);
                    path.moveTo(13.8f, 10.5f); path.lineTo(13.8f, 16);
                    c.drawPath(path, p);
                    break;
                case G_DOCK: {
                    RectF r = new RectF(4, 13, 20, 19.5f);
                    c.drawRoundRect(r, 3, 3, p);
                    c.drawLine(12, 3.5f, 12, 10, p);
                    c.drawLine(9, 7, 12, 10, p);
                    c.drawLine(15, 7, 12, 10, p);
                    break;
                }
                case G_MINUS:
                    c.drawCircle(12, 12, 9, p);
                    c.drawLine(8, 12, 16, 12, p);
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
            setBackground(round(primary ? ACCENT : FILL, h / 2f));
            int fg = primary ? Color.WHITE : FG;
            glyph = new Glyph(c, glyphType, fg);
            addView(glyph, lp(dpi(c, 20), dpi(c, 20)));
            label = text(c, text, 15, fg, true);
            label.setTypeface(font(c, 600));
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
            setBackground(round(v ? ACCENT : FILL, r));
            setTextColor(v ? Color.WHITE : FG);
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
            thumb.setColor(ACCENT);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(font(c, 600));
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
                text.setColor(blend(FG2, Color.WHITE, d));
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
            p.setColor(blend(TRACK, ACCENT, pos));
            c.drawRoundRect(0, 0, w, h, r, r, p);
            float cx = r + (w - 2 * r) * pos;
            p.setColor(Color.WHITE);
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
            ph.setColor(GROUP);
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
