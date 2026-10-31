package app.fastlauncher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.EdgeEffect;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.OverScroller;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;

final class WallpaperUi {
    private WallpaperUi() {}

    interface Host {
        Context context();
        FrameLayout overlayParent();
        int insetTop();
        int insetBottom();
        ExecutorService wallWorker();
        List<AppGridView.AppEntry> dockItems();
        int dockBottomMargin();
        int wallpaperColor();
        void snack(String msg);
        void overlayOpened(Overlay o);
        void overlayClosed(Overlay o);
        void closeAllOverlays();
        void runOnUi(Runnable r);
    }

    abstract static class Overlay extends FrameLayout {
        final Host host;
        boolean closing;

        boolean reusable;

        Overlay(Host h) {
            super(h.context());
            host = h;
            setClickable(true);
        }

        void open() {
            closing = false;
            if (getParent() == null) host.overlayParent().addView(this, new FrameLayout.LayoutParams(-1, -1));
            else bringToFront();
            setVisibility(VISIBLE);
            setAlpha(1f);
            shown = true;
            onOpen();
            host.overlayOpened(this);
            animateIn();
        }

        void prepare() {
            reusable = true;
            if (getParent() == null) {
                setAlpha(0.004f);
                setClickable(false);
                host.overlayParent().addView(this, 0, new FrameLayout.LayoutParams(-1, -1));
                postOnAnimation(() -> postOnAnimation(() -> {
                    if (host.overlayParent().indexOfChild(this) >= 0 && !isShownAsOverlay()) setVisibility(INVISIBLE);
                    setAlpha(1f);
                    setClickable(true);
                }));
            }
        }

        private boolean shown;

        boolean isShownAsOverlay() { return shown; }

        void onOpen() {}

        void close() {
            if (closing) return;
            closing = true;

            final boolean[] done = {false};
            final Runnable finish = () -> {
                if (done[0]) return;
                done[0] = true;
                shown = false;
                if (reusable) setVisibility(INVISIBLE);
                else if (getParent() != null) host.overlayParent().removeView(this);
                host.overlayClosed(this);
                onClosed();
            };
            postDelayed(finish, 700);
            animateOut(finish);
        }

        void onClosed() {}
        abstract void animateIn();
        abstract void animateOut(Runnable done);
    }

    static final class Sheet extends Overlay {
        private final View backdrop;
        private final LinearLayout card;
        private final Ui.RoundImage now;
        private final TextView nowTitle, nowSub, nextText;
        private final Ui.Glyph shuffle;
        private final View shuffleBtn, browse;
        private final LinearLayout picks;
        private final List<Walls.Wall> pickWalls = new ArrayList<>();
        private boolean picksLoading;
        private Thumbs pickThumbs;
        private float dragStartY, dragBase;
        private boolean dragging;
        private VelocityTracker vt;
        private final int slop;

        Sheet(Host h) {
            super(h);
            Context c = getContext();
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            backdrop = new View(c);
            backdrop.setBackgroundColor(Ui.dark ? 0x59000000 : 0x40000000);
            backdrop.setAlpha(0f);
            backdrop.setOnClickListener(v -> close());
            addView(backdrop, new FrameLayout.LayoutParams(-1, -1));

            card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Ui.frost(card, Ui.dp(c, 30)));
            int p = Ui.dpi(c, 18);
            card.setPadding(p, Ui.dpi(c, 10), p, Ui.dpi(c, 14));
            card.setClickable(true);
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            int m = Ui.dpi(c, 10);
            clp.leftMargin = m; clp.rightMargin = m;
            clp.bottomMargin = Math.max(h.insetBottom(), Ui.dpi(c, 4)) + m;
            addView(card, clp);

            View handle = new View(c);
            handle.setBackground(Ui.round(Ui.HANDLE, Ui.dp(c, 2)));
            LinearLayout.LayoutParams hp = Ui.lp(Ui.dpi(c, 36), Ui.dpi(c, 4));
            hp.gravity = Gravity.CENTER_HORIZONTAL;
            hp.bottomMargin = Ui.dpi(c, 12);
            card.addView(handle, hp);

            FrameLayout hero = new FrameLayout(c);
            hero.setBackground(Ui.round(Ui.GROUP, Ui.dp(c, 24)));
            hero.setClipToOutline(true);
            card.addView(hero, Ui.lp(-1, Ui.dpi(c, 210)));
            now = new Ui.RoundImage(c, Ui.dp(c, 24));
            hero.addView(now, new FrameLayout.LayoutParams(-1, -1));
            now.setClickable(true);
            now.setOnClickListener(v -> new Browser(host).open());

            LinearLayout heroBar = new LinearLayout(c);
            heroBar.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            heroBar.setPadding(0, 0, Ui.dpi(c, 12), Ui.dpi(c, 12));
            hero.addView(heroBar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

            shuffle = new Ui.Glyph(c, Ui.G_SHUFFLE, Color.WHITE);
            shuffleBtn = glassButton(c, shuffle, Ui.ACCENT);
            shuffleBtn.setOnClickListener(v -> shuffle());
            heroBar.addView(shuffleBtn, Ui.lp(Ui.dpi(c, 48), Ui.dpi(c, 48)));
            Ui.Glyph grid = new Ui.Glyph(c, Ui.G_GRID, Color.WHITE);
            browse = glassButton(c, grid, 0x8C000000);
            browse.setOnClickListener(v -> new Browser(host).open());
            LinearLayout.LayoutParams brp = Ui.lp(Ui.dpi(c, 48), Ui.dpi(c, 48));
            brp.leftMargin = Ui.dpi(c, 10);
            heroBar.addView(browse, brp);

            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            card.addView(col, Ui.lp(-1, -2, Ui.dpi(c, 12)));
            nowTitle = Ui.text(c, "", 19, Ui.FG, true);
            nowTitle.setTypeface(Ui.font(c, 650));
            nowTitle.setSingleLine(true);
            nowTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(nowTitle);
            nowSub = Ui.text(c, "", 12, Ui.FG2, false);
            nowSub.setSingleLine(true);
            nowSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(nowSub, Ui.lp(-1, -2, Ui.dpi(c, 2)));

            LinearLayout pickHead = new LinearLayout(c);
            pickHead.setGravity(Gravity.CENTER_VERTICAL);
            card.addView(pickHead, Ui.lp(-1, -2, Ui.dpi(c, 18)));
            pickHead.addView(Ui.label(c, "Fresh picks"), new LinearLayout.LayoutParams(0, -2, 1f));
            TextView refresh = Ui.text(c, "Refresh", 13, Ui.ACCENT, true);
            refresh.setTypeface(Ui.font(c, 600));
            refresh.setPadding(0, 0, Ui.dpi(c, 18), 0);
            refresh.setClickable(true);
            Ui.pressable(refresh);
            refresh.setOnClickListener(v -> fetchPicks());
            pickHead.addView(refresh);
            TextView all = Ui.text(c, "See all", 13, Ui.ACCENT, true);
            all.setTypeface(Ui.font(c, 600));
            all.setClickable(true);
            Ui.pressable(all);
            all.setOnClickListener(v -> new Browser(host).open());
            pickHead.addView(all);
            HorizontalScrollView pickScroll = new HorizontalScrollView(c);
            pickScroll.setHorizontalScrollBarEnabled(false);
            pickScroll.setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
            pickScroll.setClipToPadding(false);
            int edge = Ui.dpi(c, 18);
            pickScroll.setPadding(edge, 0, edge, 0);
            picks = new LinearLayout(c);
            pickScroll.addView(picks);
            LinearLayout.LayoutParams psp = Ui.lp(-1, Ui.dpi(c, 132), Ui.dpi(c, 10));
            psp.leftMargin = psp.rightMargin = -edge;
            card.addView(pickScroll, psp);
            card.setClipToPadding(false);
            for (int i = 0; i < PICKS; i++) picks.addView(pickTile(c, null), pickLp(c, i));
            Thumbs.disk = new java.io.File(c.getCacheDir(), "thumbs");

            LinearLayout autoHead = new LinearLayout(c);
            autoHead.setGravity(Gravity.CENTER_VERTICAL);
            card.addView(autoHead, Ui.lp(-1, -2, Ui.dpi(c, 18)));
            autoHead.addView(Ui.label(c, "Change automatically"), new LinearLayout.LayoutParams(0, -2, 1f));
            nextText = Ui.text(c, "", 11.5f, Ui.FG3, false);
            autoHead.addView(nextText);
            Ui.Segmented seg = new Ui.Segmented(c, Walls.INTERVAL_LABELS, Walls.interval(c));
            card.addView(seg, Ui.lp(-1, -2, Ui.dpi(c, 8)));
            seg.setListener(i -> { Walls.setInterval(getContext(), i); refreshNext(); });

            LinearLayout rateHead = new LinearLayout(c);
            rateHead.setGravity(Gravity.CENTER_VERTICAL);
            card.addView(rateHead, Ui.lp(-1, -2, Ui.dpi(c, 18)));
            rateHead.addView(Ui.label(c, "Screen refresh"), new LinearLayout.LayoutParams(0, -2, 1f));
            rateHead.addView(Ui.text(c, "Phone-wide, while unplugged", 11.5f, Ui.FG3, false));
            Ui.Segmented rate = new Ui.Segmented(c, new String[]{"90 Hz always", "60 Hz below " + RefreshRate.LIMIT + "%"},
                    RefreshRate.saveBattery(c) ? 1 : 0);
            card.addView(rate, Ui.lp(-1, -2, Ui.dpi(c, 8)));
            rate.setListener(i -> RefreshRate.setSaveBattery(getContext(), i == 1));

            TextView report = Ui.text(c, "Something not working?  Report a problem", 12.5f, Ui.FG3, false);
            report.setGravity(Gravity.CENTER);
            report.setPadding(0, Ui.dpi(c, 10), 0, 0);
            report.setClickable(true);
            Ui.pressable(report);
            report.setOnClickListener(v -> shareReport());
            card.addView(report, Ui.lp(-1, -2, Ui.dpi(c, 8)));

            refreshNow();
            refreshNext();
        }

        private void shareReport() {
            final Context c = getContext();
            host.snack("Preparing the report…");
            host.wallWorker().execute(() -> {
                final String text = Diag.report(c);
                host.runOnUi(() -> {
                    Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "Fast Launcher report")
                            .putExtra(Intent.EXTRA_TEXT, text);
                    try {
                        c.startActivity(Intent.createChooser(send, "Send the report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception e) {
                        host.snack("Couldn't open sharing");
                    }
                });
            });
        }

        private View glassButton(Context c, Ui.Glyph g, int color) {
            int p = Ui.dpi(c, 13);
            g.setPadding(p, p, p, p);
            g.setBackground(Ui.round(color, Ui.dp(c, 24)));
            g.setClickable(true);
            Ui.pressable(g);
            return g;
        }

        private LinearLayout.LayoutParams pickLp(Context c, int i) {
            LinearLayout.LayoutParams lp = Ui.lp(Ui.dpi(c, 76), -1);
            if (i > 0) lp.leftMargin = Ui.dpi(c, 8);
            return lp;
        }

        private Ui.RoundImage pickTile(Context c, Walls.Wall w) {
            Ui.RoundImage t = new Ui.RoundImage(c, Ui.dp(c, 16));
            t.setPlaceholder(w == null ? Ui.GROUP : Ui.darken(w.color, 0.75f));
            t.setClickable(true);
            Ui.pressable(t);
            return t;
        }

        static final int PICKS = 8;
        private android.animation.ValueAnimator pulse;

        private void setLoading(boolean on) {
            if (on) {
                if (pulse != null) return;
                pulse = android.animation.ValueAnimator.ofFloat(1f, 0.45f);
                pulse.setDuration(750);
                pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                pulse.addUpdateListener(a -> {
                    float v = (Float) a.getAnimatedValue();

                    for (int i = 0; i < picks.getChildCount(); i++) {
                        Ui.RoundImage t = (Ui.RoundImage) picks.getChildAt(i);
                        t.setAlpha(t.bitmap() == null ? v : 1f);
                    }
                });
                pulse.start();
            } else if (pulse != null) {
                pulse.cancel();
                pulse = null;
                for (int i = 0; i < picks.getChildCount(); i++) picks.getChildAt(i).setAlpha(1f);
            }
        }

        private void loadPicks() {
            if (!pickWalls.isEmpty() || picksLoading) return;
            try {
                org.json.JSONArray a = new org.json.JSONArray(Walls.prefs(getContext()).getString("picks", "[]"));
                for (int i = 0; i < a.length() && i < PICKS; i++) pickWalls.add(Walls.Wall.fromJson(a.getJSONObject(i)));
            } catch (Exception ignored) {}
            if (!pickWalls.isEmpty()) { showPicks(); return; }
            fetchPicks();
        }

        private void fetchPicks() {
            if (picksLoading) return;
            picksLoading = true;
            for (int i = 0; i < picks.getChildCount(); i++) ((Ui.RoundImage) picks.getChildAt(i)).setBitmap(null);
            pickWalls.clear();
            setLoading(true);
            final Context app = getContext().getApplicationContext();
            host.wallWorker().execute(() -> {
                List<Walls.Wall> got = new ArrayList<>();
                try {
                    List<Walls.Theme> pool = Walls.selectedThemes(app);
                    java.util.Collections.shuffle(pool);
                    Walls.Wall cur = Walls.current(app);
                    for (Walls.Theme t : pool) {
                        for (Walls.Wall w : Walls.search(t, false, 1, null).walls) {
                            if (cur != null && cur.id.equals(w.id)) continue;
                            got.add(w);
                            if (got.size() >= 4 * Math.max(1, Math.min(3, pool.size())) / Math.max(1, Math.min(3, pool.size()))) break;
                        }
                        if (got.size() >= 10) break;
                    }
                    java.util.Collections.shuffle(got);
                } catch (Exception ignored) {}
                final List<Walls.Wall> result = got.size() > PICKS ? got.subList(0, PICKS) : got;
                host.runOnUi(() -> {
                    picksLoading = false;
                    setLoading(false);
                    if (result.isEmpty()) { host.snack("Couldn't load new picks"); loadPicks(); return; }
                    pickWalls.clear();
                    pickWalls.addAll(result);
                    try {
                        org.json.JSONArray a = new org.json.JSONArray();
                        for (Walls.Wall w : result) a.put(w.toJson());
                        Walls.prefs(getContext()).edit().putString("picks", a.toString()).apply();
                    } catch (Exception ignored) {}
                    showPicks();
                });
            });
        }

        private void showPicks() {
            if (pickThumbs == null) pickThumbs = new Thumbs(this::showPickImages);
            for (int i = 0; i < picks.getChildCount(); i++) {
                Ui.RoundImage t = (Ui.RoundImage) picks.getChildAt(i);
                if (i >= pickWalls.size()) { t.setVisibility(GONE); continue; }
                final Walls.Wall w = pickWalls.get(i);
                t.setVisibility(VISIBLE);
                t.setBitmap(null);
                t.setPlaceholder(Ui.darken(w.color, 0.75f));
                t.setOnClickListener(v -> new Preview(host, w, pickThumbs.get(w.thumb)).open());
            }
            showPickImages();
        }

        private void showPickImages() {
            boolean waiting = false;
            for (int i = 0; i < picks.getChildCount() && i < pickWalls.size(); i++) {
                Bitmap b = pickThumbs.get(pickWalls.get(i).thumb);
                Ui.RoundImage t = (Ui.RoundImage) picks.getChildAt(i);
                if (b != null && t.bitmap() != b) t.setBitmap(b);
                if (t.bitmap() == null) waiting = true;
            }
            setLoading(waiting);
        }

        private Thumbs nowThumbs;

        void refreshNow() {
            Walls.Wall w = Walls.current(getContext());
            if (w == null) {
                now.setPlaceholder(host.wallpaperColor());
                nowTitle.setText("Your wallpaper");
                nowSub.setText("Tap New one for a fresh pick");
                return;
            }
            now.setPlaceholder(Ui.darken(w.color, 0.6f));
            nowTitle.setText(w.theme == null || w.theme.isEmpty() ? "Wallhaven" : w.theme);
            nowSub.setText("wallhaven.cc  ·  " + w.width + " × " + w.height);
            if (nowThumbs == null) nowThumbs = new Thumbs(() -> refreshNow());
            Bitmap b = nowThumbs.get(w.thumb);
            if (b != null) now.setBitmap(b);
        }

        void refreshNext() {
            Context c = getContext();
            if (Walls.interval(c) == 0) { nextText.setText("Off"); return; }
            long at = Walls.nextChangeAt(c), now = System.currentTimeMillis();
            if (at <= now) nextText.setText("Due now");
            else {
                String rel = DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS).toString();
                nextText.setText("Changes " + Character.toLowerCase(rel.charAt(0)) + rel.substring(1));
            }
        }

        private void shuffle() {
            shuffle.type = Ui.G_SPIN;
            shuffle.invalidate();
            shuffle.animate().rotationBy(3600).setDuration(10000).setInterpolator(new android.view.animation.LinearInterpolator()).start();
            shuffleBtn.setEnabled(false);
            browse.setEnabled(false);
            final Context app = getContext().getApplicationContext();
            host.wallWorker().execute(() -> {
                String err = null;
                try { Walls.next(app, true); } catch (Exception e) { err = e.getMessage(); }
                final String error = err;
                host.runOnUi(() -> {
                    shuffle.animate().cancel();
                    shuffle.setRotation(0f);
                    shuffle.type = Ui.G_SHUFFLE;
                    shuffle.invalidate();
                    shuffleBtn.setEnabled(true);
                    browse.setEnabled(true);
                    if (error != null) host.snack(error);
                    else { refreshNow(); refreshNext(); }
                });
            });
        }

        @Override void animateIn() {
            card.setVisibility(INVISIBLE);
            post(() -> {
                if (closing) return;
                card.setTranslationY(card.getHeight() + Ui.dp(getContext(), 40));
                card.setVisibility(VISIBLE);
                card.setLayerType(LAYER_TYPE_HARDWARE, null);
                card.animate().translationY(0).setDuration(300).setInterpolator(Ui.EMPHASIZED)
                        .withEndAction(() -> card.setLayerType(LAYER_TYPE_NONE, null)).start();
                backdrop.animate().alpha(1f).setDuration(220).start();
            });
        }

        @Override void animateOut(Runnable done) {
            card.setLayerType(LAYER_TYPE_HARDWARE, null);
            card.animate().translationY(card.getHeight() + Ui.dp(getContext(), 40)).setDuration(190)
                    .setInterpolator(Ui.STANDARD).withEndAction(done).start();
            backdrop.animate().alpha(0f).setDuration(220).start();
        }

        @Override void onOpen() {
            loadPicks();
            refreshNow();
            refreshNext();
            backdrop.setAlpha(0f);
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent e) {
            if (closing) return true;
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                dragStartY = e.getRawY(); dragging = false;
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
            }
            if (vt != null) vt.addMovement(e);
            if (e.getActionMasked() == MotionEvent.ACTION_MOVE && !dragging
                    && e.getRawY() - dragStartY > slop * 1.5f && e.getY() > card.getTop()) {
                dragging = true;
                dragStartY = e.getRawY();
                dragBase = card.getTranslationY();
                return true;
            }
            return false;
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (closing) return true;
            if (!dragging) return super.onTouchEvent(e);
            if (vt != null) vt.addMovement(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE: {
                    float dy = e.getRawY() - dragStartY;
                    card.setTranslationY(Math.max(0, dragBase + dy));
                    backdrop.setAlpha(1f - Math.min(1f, card.getTranslationY() / card.getHeight()));
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    dragging = false;
                    vt.computeCurrentVelocity(1000);
                    float v = vt.getYVelocity();
                    if (v > 900 || card.getTranslationY() > card.getHeight() * 0.3f) close();
                    else {
                        card.animate().translationY(0).setDuration(280).setInterpolator(Ui.EMPHASIZED).start();
                        backdrop.animate().alpha(1f).setDuration(200).start();
                    }
                    return true;
                }
            }
            return true;
        }
    }

    static final class Browser extends Overlay {
        private ThumbGrid grid;
        private final Thumbs thumbs;
        private final TextView status;
        private final Ui.Pill retry;
        private final List<Ui.Chip> chips = new ArrayList<>();
        private final List<Walls.Wall> items = new ArrayList<>();
        private final HashMap<String, String> seeds = new HashMap<>();
        private final HashMap<String, Integer> pages = new HashMap<>();
        private final HashMap<String, Integer> lastPages = new HashMap<>();
        private final java.util.HashSet<String> seen = new java.util.HashSet<>();
        private int mode;
        private int gen;
        private boolean loading, exhausted;
        private int forYouTurn;

        Browser(Host h) {
            super(h);
            Context c = getContext();
            setBackground(Ui.frost(this, 0));
            thumbs = new Thumbs(() -> { if (grid != null) grid.invalidate(); });

            LinearLayout top = new LinearLayout(c);
            top.setOrientation(LinearLayout.VERTICAL);
            top.setPadding(0, h.insetTop() + Ui.dpi(c, 6), 0, Ui.dpi(c, 10));
            top.setBackground(Ui.frost(top, 0));

            LinearLayout bar = new LinearLayout(c);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(Ui.dpi(c, 8), 0, Ui.dpi(c, 16), 0);
            top.addView(bar, Ui.lp(-1, Ui.dpi(c, 52)));
            Ui.Glyph back = new Ui.Glyph(c, Ui.G_BACK, Ui.FG);
            back.setBackground(Ui.round(Ui.FILL, Ui.dp(c, 22)));
            back.setPadding(Ui.dpi(c, 11), Ui.dpi(c, 11), Ui.dpi(c, 11), Ui.dpi(c, 11));
            back.setClickable(true);
            back.setOnClickListener(v -> close());
            Ui.pressable(back);
            bar.addView(back, Ui.lp(Ui.dpi(c, 44), Ui.dpi(c, 44)));
            TextView title = Ui.text(c, "Wallpapers", 22, Ui.FG, true);
            title.setTypeface(Ui.font(c, 700));
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
            tp.leftMargin = Ui.dpi(c, 4);
            bar.addView(title, tp);
            bar.addView(Ui.text(c, "wallhaven.cc", 12.5f, Ui.FG3, false));

            HorizontalScrollView hs = new HorizontalScrollView(c);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
            LinearLayout chipRow = new LinearLayout(c);
            chipRow.setPadding(Ui.dpi(c, 16), 0, Ui.dpi(c, 16), 0);
            hs.addView(chipRow);
            top.addView(hs, Ui.lp(-1, -2, Ui.dpi(c, 6)));
            List<String> names = new ArrayList<>();
            names.add("For you");
            names.add("Popular");
            for (Walls.Theme t : Walls.THEMES) names.add(t.label);
            for (int i = 0; i < names.size(); i++) {
                final int idx = i;
                Ui.Chip chip = new Ui.Chip(c, names.get(i));
                chip.setOn(i == 0);
                chip.setOnClickListener(v -> select(idx));
                LinearLayout.LayoutParams cp = Ui.lp(-2, -2);
                cp.rightMargin = Ui.dpi(c, 8);
                chipRow.addView(chip, cp);
                chips.add(chip);
            }

            grid = new ThumbGrid(c);
            addView(grid, new FrameLayout.LayoutParams(-1, -1));
            addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
            top.post(() -> grid.setTopInset(top.getHeight()));
            grid.setBottomInset(h.insetBottom());

            status = Ui.text(c, "", 15, Ui.FG2, false);
            status.setGravity(Gravity.CENTER);
            status.setVisibility(GONE);
            FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
            sp.leftMargin = sp.rightMargin = Ui.dpi(c, 32);
            addView(status, sp);
            retry = new Ui.Pill(c, Ui.G_SHUFFLE, "Try again", true);
            retry.setVisibility(GONE);
            retry.setOnClickListener(v -> { thumbs.retryFailed(); select(mode); });
            FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
            rp.topMargin = Ui.dpi(c, 72);
            addView(retry, rp);

            select(0);
        }

        private void select(int m) {
            mode = m;
            for (int i = 0; i < chips.size(); i++) chips.get(i).setOn(i == m);
            gen++;
            items.clear();
            seen.clear();
            seeds.clear();
            pages.clear();
            lastPages.clear();
            exhausted = false;
            loading = false;
            forYouTurn = 0;
            grid.reset();
            loadMore();
        }

        private Walls.Theme themeFor(int m) {
            if (m == 1) return Walls.EVERYTHING;
            if (m >= 2) return Walls.THEMES[m - 2];
            List<Walls.Theme> pool = Walls.selectedThemes(getContext());
            return pool.get(forYouTurn++ % pool.size());
        }

        void loadMore() {
            if (loading || exhausted) return;
            loading = true;
            showStatus(items.isEmpty() ? "" : null, false);
            final int g = gen;
            final Walls.Theme t = themeFor(mode);
            final boolean popular = mode == 1;
            final int page = pages.containsKey(t.key) ? pages.get(t.key) + 1 : 1;
            final String seed = seeds.get(t.key);
            host.wallWorker().execute(() -> {
                Walls.Page p = null;
                String err = null;
                try { p = Walls.search(t, popular, page, seed); } catch (Exception e) { err = e.getMessage(); }
                final Walls.Page res = p;
                final String error = err;
                host.runOnUi(() -> {
                    if (g != gen) return;
                    loading = false;
                    if (res == null) {
                        if (items.isEmpty()) showStatus(error == null ? "Couldn't load wallpapers" : error, true);
                        else host.snack(error == null ? "Couldn't load more" : error);
                        return;
                    }
                    pages.put(t.key, page);
                    lastPages.put(t.key, res.lastPage);
                    if (res.seed != null) seeds.put(t.key, res.seed);
                    for (Walls.Wall w : res.walls) if (seen.add(w.id)) items.add(w);
                    if (mode != 0 && page >= res.lastPage) exhausted = true;
                    if (items.isEmpty()) showStatus("Nothing here yet. Try another theme.", false);
                    else showStatus(null, false);
                    grid.invalidate();
                });
            });
        }

        private void showStatus(String msg, boolean canRetry) {
            status.setVisibility(msg == null || msg.isEmpty() ? GONE : VISIBLE);
            if (msg != null) status.setText(msg);
            retry.setVisibility(canRetry ? VISIBLE : GONE);
            grid.setSkeleton(msg != null && msg.isEmpty());
        }

        @Override void animateIn() {
            setAlpha(0f);
            setTranslationY(Ui.dp(getContext(), 48));
            setLayerType(LAYER_TYPE_HARDWARE, null);
            animate().alpha(1f).translationY(0).setDuration(260).setInterpolator(Ui.EMPHASIZED)
                    .withEndAction(() -> setLayerType(LAYER_TYPE_NONE, null)).start();
        }

        @Override void animateOut(Runnable done) {
            setLayerType(LAYER_TYPE_HARDWARE, null);
            animate().alpha(0f).translationY(Ui.dp(getContext(), 48)).setDuration(220)
                    .setInterpolator(Ui.STANDARD).withEndAction(done).start();
        }

        @Override void onClosed() { gen++; thumbs.close(); }

        final class ThumbGrid extends View {
            private final int cols = 3;
            private final float gap, side, radius;
            private float topInset, bottomInset, off;
            private boolean skeleton;
            private final Paint ph = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint img = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            private final HashMap<String, BitmapShader> shaders = new HashMap<>();
            private final Matrix m = new Matrix();
            private final OverScroller scroller;
            private final EdgeEffect topEdge, bottomEdge;
            private VelocityTracker vt;
            private float downX, downY, lastY;
            private boolean dragging;
            private final int slop, minFling, maxFling;

            ThumbGrid(Context c) {
                super(c);
                gap = Ui.dp(c, 8);
                side = Ui.dp(c, 12);
                radius = Ui.dp(c, 16);
                scroller = new OverScroller(c);
                topEdge = new EdgeEffect(c);
                bottomEdge = new EdgeEffect(c);
                ViewConfiguration vc = ViewConfiguration.get(c);
                slop = vc.getScaledTouchSlop();
                minFling = vc.getScaledMinimumFlingVelocity();
                maxFling = vc.getScaledMaximumFlingVelocity();
            }

            void setTopInset(int px) { topInset = px + gap; invalidate(); }
            void setBottomInset(int px) { bottomInset = px + Ui.dp(getContext(), 16); }
            void setSkeleton(boolean s) { skeleton = s; invalidate(); }
            void reset() { off = 0; scroller.forceFinished(true); shaders.clear(); invalidate(); }

            private float cellW() { return (getWidth() - 2 * side - (cols - 1) * gap) / cols; }
            private float cellH() { return cellW() * 16f / 9f; }
            private float rowH() { return cellH() + gap; }

            private float maxOff() {
                int rows = (items.size() + cols - 1) / cols;
                return Math.max(0, topInset + rows * rowH() + bottomInset - getHeight());
            }

            @Override public void computeScroll() {
                if (scroller.computeScrollOffset()) {
                    off = scroller.getCurrY();
                    if (scroller.isOverScrolled() || off <= 0 || off >= maxOff()) {
                        float v = scroller.getCurrVelocity();
                        if (off <= 0 && topEdge.isFinished()) topEdge.onAbsorb((int) v);
                        else if (off >= maxOff() && bottomEdge.isFinished() && maxOff() > 0) bottomEdge.onAbsorb((int) v);
                        if (off <= 0 || off >= maxOff()) scroller.forceFinished(true);
                    }
                    off = Math.max(0, Math.min(maxOff(), off));
                    postInvalidateOnAnimation();
                }
            }

            @Override protected void onDraw(Canvas c) {
                float cw = cellW(), ch = cellH(), rh = rowH();
                if (cw <= 0) return;
                int count = skeleton && items.isEmpty() ? 12 : items.size();
                int first = Math.max(0, (int) ((off - topInset) / rh));
                int last = (int) ((off + getHeight() - topInset) / rh);
                boolean animating = false;
                long now = SystemClock.uptimeMillis();
                for (int r = first; r <= last; r++) {
                    for (int col = 0; col < cols; col++) {
                        int i = r * cols + col;
                        if (i >= count) break;
                        float x = side + col * (cw + gap), y = topInset + r * rh - off;
                        if (skeleton && items.isEmpty()) {
                            float pulse = 0.5f + 0.5f * (float) Math.sin((now + i * 90) / 260.0);
                            ph.setColor(Ui.blend(Ui.GROUP, Ui.FILL, pulse));
                            c.drawRoundRect(x, y, x + cw, y + ch, radius, radius, ph);
                            animating = true;
                            continue;
                        }
                        Walls.Wall w = items.get(i);
                        ph.setColor(Ui.darken(w.color, 0.55f));
                        c.drawRoundRect(x, y, x + cw, y + ch, radius, radius, ph);
                        Bitmap b = thumbs.get(w.thumb);
                        if (b == null) continue;
                        BitmapShader s = shaders.get(w.thumb);
                        if (s == null) {
                            s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                            shaders.put(w.thumb, s);
                        }
                        float sc = Math.max(cw / b.getWidth(), ch / b.getHeight());
                        m.setScale(sc, sc);
                        m.postTranslate(x + (cw - b.getWidth() * sc) / 2f, y + (ch - b.getHeight() * sc) / 2f);
                        s.setLocalMatrix(m);
                        img.setShader(s);
                        float f = thumbs.fade(w.thumb);
                        img.setAlpha(Math.round(255 * f));
                        if (f < 1f) animating = true;
                        c.drawRoundRect(x, y, x + cw, y + ch, radius, radius, img);
                    }
                }
                if (!loading && !exhausted && !items.isEmpty() && (last + 3) * cols >= items.size()) post(Browser.this::loadMore);
                if (!topEdge.isFinished()) {
                    int save = c.save();
                    c.translate(0, topInset - gap);
                    topEdge.setSize(getWidth(), getHeight());
                    if (topEdge.draw(c)) animating = true;
                    c.restoreToCount(save);
                }
                if (!bottomEdge.isFinished()) {
                    int save = c.save();
                    c.rotate(180, getWidth() / 2f, getHeight() / 2f);
                    bottomEdge.setSize(getWidth(), getHeight());
                    if (bottomEdge.draw(c)) animating = true;
                    c.restoreToCount(save);
                }
                if (animating) postInvalidateOnAnimation();
            }

            private int hit(float x, float y) {
                float cw = cellW(), rh = rowH();
                int col = (int) ((x - side) / (cw + gap));
                if (col < 0 || col >= cols) return -1;
                if (x - side - col * (cw + gap) > cw) return -1;
                float yy = y + off - topInset;
                if (yy < 0) return -1;
                int r = (int) (yy / rh);
                if (yy - r * rh > cellH()) return -1;
                int i = r * cols + col;
                return i < items.size() ? i : -1;
            }

            @Override public boolean onTouchEvent(MotionEvent e) {
                if (vt == null) vt = VelocityTracker.obtain();
                vt.addMovement(e);
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        scroller.forceFinished(true);
                        downX = e.getX(); downY = lastY = e.getY();
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float y = e.getY();
                        if (!dragging && Math.abs(y - downY) > slop) dragging = true;
                        if (dragging) {
                            float next = off - (y - lastY);
                            if (next < 0) {
                                topEdge.onPull(-next / getHeight(), e.getX() / getWidth());
                                next = 0;
                            } else if (next > maxOff()) {
                                bottomEdge.onPull((next - maxOff()) / getHeight(), 1f - e.getX() / getWidth());
                                next = maxOff();
                            }
                            off = next;
                            invalidate();
                        }
                        lastY = y;
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (dragging) {
                            vt.computeCurrentVelocity(1000, maxFling);
                            float v = vt.getYVelocity();
                            if (Math.abs(v) > minFling)
                                scroller.fling(0, (int) off, 0, (int) -v, 0, 0, 0, (int) maxOff(), 0, Ui.dpi(getContext(), 40));
                        } else {
                            int i = hit(e.getX(), e.getY());
                            if (i >= 0) {
                                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                                new Preview(host, items.get(i), thumbs.get(items.get(i).thumb)).open();
                            }
                        }
                        topEdge.onRelease();
                        bottomEdge.onRelease();
                        vt.recycle(); vt = null;
                        postInvalidateOnAnimation();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        topEdge.onRelease();
                        bottomEdge.onRelease();
                        vt.recycle(); vt = null;
                        return true;
                }
                return true;
            }
        }
    }

    static final class Preview extends Overlay {
        private final Walls.Wall wall;
        private final PreviewImage image;
        private final FrameLayout mock;
        private final Ui.Pill set;
        private final TextView info;
        private Bitmap full;
        private boolean mockShown = true, applying;

        Preview(Host h, Walls.Wall w, Bitmap thumb) {
            super(h);
            wall = w;
            Context c = getContext();
            setBackgroundColor(Color.BLACK);
            image = new PreviewImage(c, w.color, thumb);
            addView(image, new FrameLayout.LayoutParams(-1, -1));
            image.setOnClickListener(v -> toggleMock());

            mock = new FrameLayout(c);
            addView(mock, new FrameLayout.LayoutParams(-1, -1));
            TextView clock = MainActivity.makeClock(c);
            TextView date = MainActivity.makeDate(c);
            java.util.Calendar cal = java.util.Calendar.getInstance();
            clock.setText(MainActivity.stackedTime(cal));
            date.setText(MainActivity.dateText(cal));
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            cp.leftMargin = Ui.dpi(c, MainActivity.SIDE_DP);
            cp.topMargin = Ui.dpi(c, MainActivity.CLOCK_TOP_DP);
            mock.addView(clock, cp);
            FrameLayout.LayoutParams dp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            dp.leftMargin = Ui.dpi(c, MainActivity.SIDE_DP);
            dp.topMargin = Ui.dpi(c, MainActivity.DATE_TOP_DP);
            mock.addView(date, dp);
            AppGridView dock = MainActivity.makeDock(c);
            dock.setItems(h.dockItems());
            FrameLayout.LayoutParams dk = new FrameLayout.LayoutParams(-1, Ui.dpi(c, MainActivity.DOCK_HEIGHT_DP), Gravity.BOTTOM);
            dk.bottomMargin = h.dockBottomMargin();
            mock.addView(dock, dk);
            dock.setEnabled(false);
            mock.setClickable(false);
            dock.setClickable(false);
            dock.setOnTouchListener((v, ev) -> false);

            LinearLayout bar = new LinearLayout(c);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(Ui.dpi(c, 8), h.insetTop() + Ui.dpi(c, 6), Ui.dpi(c, 8), 0);
            Ui.Glyph back = roundGlyph(c, Ui.G_BACK);
            back.setOnClickListener(v -> close());
            bar.addView(back, Ui.lp(Ui.dpi(c, 44), Ui.dpi(c, 44)));
            bar.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
            Ui.Glyph eye = roundGlyph(c, Ui.G_EYE);
            eye.setOnClickListener(v -> toggleMock());
            bar.addView(eye, Ui.lp(Ui.dpi(c, 44), Ui.dpi(c, 44)));
            addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

            LinearLayout action = new LinearLayout(c);
            action.setOrientation(LinearLayout.VERTICAL);
            action.setGravity(Gravity.CENTER_HORIZONTAL);
            set = new Ui.Pill(c, Ui.G_CHECK, "Set wallpaper", true);
            set.setOnClickListener(v -> apply());
            action.addView(set, Ui.lp(-2, -2));
            info = Ui.text(c, w.theme + "  ·  " + w.width + " × " + w.height, 12.5f, 0xE6FFFFFF, true);
            info.setBackground(Ui.round(0x66000000, Ui.dp(c, 12)));
            info.setPadding(Ui.dpi(c, 12), Ui.dpi(c, 6), Ui.dpi(c, 12), Ui.dpi(c, 6));
            LinearLayout.LayoutParams ip = Ui.lp(-2, -2, Ui.dpi(c, 10));
            ip.gravity = Gravity.CENTER_HORIZONTAL;
            action.addView(info, ip);
            FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            ap.bottomMargin = h.dockBottomMargin() + Ui.dpi(c, MainActivity.DOCK_HEIGHT_DP + 28);
            addView(action, ap);

            loadFull();
        }

        private static Ui.Glyph roundGlyph(Context c, int type) {
            Ui.Glyph g = new Ui.Glyph(c, type, Color.WHITE);
            g.setBackground(Ui.round(0x59000000, Ui.dp(c, 22)));
            g.setPadding(Ui.dpi(c, 10), Ui.dpi(c, 10), Ui.dpi(c, 10), Ui.dpi(c, 10));
            g.setClickable(true);
            Ui.pressable(g);
            return g;
        }

        private void toggleMock() {
            mockShown = !mockShown;
            mock.animate().alpha(mockShown ? 1f : 0f).setDuration(200).start();
        }

        private void loadFull() {
            final Context app = getContext().getApplicationContext();
            host.wallWorker().execute(() -> {
                Bitmap b = null;
                try { b = Walls.download(app, wall); } catch (Exception ignored) {}
                final Bitmap res = b;
                host.runOnUi(() -> {
                    if (closing) return;
                    full = res;
                    if (res != null) image.setFull(res);
                });
            });
        }

        private void apply() {
            if (applying) return;
            applying = true;
            set.setBusy(true, "Setting…");
            final Context app = getContext().getApplicationContext();
            final Bitmap ready = full;
            host.wallWorker().execute(() -> {
                String err = null;
                try { Walls.applyChosen(app, wall, ready); } catch (Exception e) { err = e.getMessage(); }
                final String error = err;
                host.runOnUi(() -> {
                    applying = false;
                    if (error != null) {
                        set.setBusy(false, null);
                        host.snack(error);
                        return;
                    }
                    host.closeAllOverlays();
                    host.snack("Wallpaper updated");
                });
            });
        }

        @Override void animateIn() {
            setAlpha(0f);
            setScaleX(0.96f); setScaleY(0.96f);
            setLayerType(LAYER_TYPE_HARDWARE, null);
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(Ui.EMPHASIZED)
                    .withEndAction(() -> setLayerType(LAYER_TYPE_NONE, null)).start();
        }

        @Override void animateOut(Runnable done) {
            setLayerType(LAYER_TYPE_HARDWARE, null);
            animate().alpha(0f).scaleX(0.97f).scaleY(0.97f).setDuration(200)
                    .setInterpolator(Ui.STANDARD).withEndAction(done).start();
        }

        static final class PreviewImage extends View {
            private final Bitmap thumb;
            private Bitmap full;
            private long fullAt;
            private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            private final Paint shade = new Paint();
            private final Matrix m = new Matrix();
            private final int color;

            PreviewImage(Context c, int color, Bitmap thumb) {
                super(c);
                this.color = color;
                this.thumb = thumb;
                setClickable(true);
            }

            void setFull(Bitmap b) { full = b; fullAt = SystemClock.uptimeMillis(); invalidate(); }

            private void drawCover(Canvas c, Bitmap b, int alpha) {
                float s = Math.max(getWidth() / (float) b.getWidth(), getHeight() / (float) b.getHeight());
                m.setScale(s, s);
                m.postTranslate((getWidth() - b.getWidth() * s) / 2f, (getHeight() - b.getHeight() * s) / 2f);
                p.setAlpha(alpha);
                c.drawBitmap(b, m, p);
            }

            @Override protected void onDraw(Canvas c) {
                c.drawColor(Ui.darken(color, 0.6f));
                if (thumb != null) drawCover(c, thumb, 255);
                if (full != null) {
                    float t = Math.min(1f, (SystemClock.uptimeMillis() - fullAt) / 260f);
                    drawCover(c, full, Math.round(255 * t));
                    if (t < 1f) postInvalidateOnAnimation();
                }

                if (shade.getShader() == null) {
                    shade.setShader(new LinearGradient(0, 0, 0, getHeight() * 0.18f, 0x66000000, 0x00000000, Shader.TileMode.CLAMP));
                }
                c.drawRect(0, 0, getWidth(), getHeight() * 0.18f, shade);
            }
        }
    }
}
