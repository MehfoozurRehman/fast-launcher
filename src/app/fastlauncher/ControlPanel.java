package app.fastlauncher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.accessibilityservice.AccessibilityService;
import android.app.NotificationManager;
import android.app.UiModeManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * The pull-down control panel, shown by {@link PanelService} over any app.
 *
 *   header        time, date, settings
 *   tiles         Wi-Fi | Mobile data, Bluetooth | Torch
 *   media row     now-playing card (art, progress, controls) | brightness | volume
 *   grid          16 round toggles
 *
 * A floating card in the system's own pop-up colours, following light and dark mode.
 */
final class ControlPanel extends FrameLayout {

    interface Host {
        void launch(Intent i);
        void globalAction(int action);
        void finishHide();
        int statusBarHeight();
    }

    private final Host host;
    private final LinearLayout notifButton, notifList;
    private final PanelIcons notifBell;
    private final TextView notifCount, notifEmpty, clearAll;
    private final ScrollView notifScroll;
    private boolean notifsDirty = true;
    static final int CONTROLS = 0, NOTIFS = 1, BT = 2;
    private int page = CONTROLS;
    private final ScrollView btScroll;
    private final FrameLayout pager;
    private final Dots dots;
    private boolean paging, fromEdge;
    private int pagingTo = -1;
    private ValueAnimator pageAnim;
    private final LinearLayout btList;
    private final TextView btToggle;
    final boolean isDark = Ui.dark; // the palette this panel was built in
    private final FrameLayout scrim;
    private final ImageView backdrop;
    private final View backdropBase;

    /**
     * See-through surfaces over the blurred screen, like the system's control centre: tiles are
     * a shade darker (lighter in light mode) than the blur, round buttons a shade lighter.
     */
    static int TILE, ROUND;
    private final LinearLayout card;
    private final ScrollView scroll;
    private final TextView time, date;
    private final Item wifi, bluetooth, data, torch;
    private final VSlider brightness, volume;
    private final Round hotspot, rotate, plane, location, dark, dnd, sound, eye,
            saver, screenshot, record, cast, calc, recorder, lock, power;

    // media
    private final FrameLayout mediaFull;
    private final LinearLayout mediaEmpty;
    private final ImageView mediaArt;
    private final TextView mediaTitle, mediaArtist, mediaTime, mediaLeft;
    private final Progress mediaBar;
    private final PanelIcons mediaPlay;
    private MediaController controller;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final CameraManager cameras;
    private String torchId;
    private boolean torchOn;

    float progress;
    private ValueAnimator anim;
    private float downX, downY, startProgress;
    private boolean dragging;
    private VelocityTracker vt;
    private final int slop;

    ControlPanel(Context c, Host h) {
        super(c);
        host = h;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        cameras = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);

        TILE = Ui.dark ? 0x4D000000 : 0x99FFFFFF;
        ROUND = Ui.dark ? 0x26FFFFFF : 0x14000000;
        // the background: whatever was on screen, blurred (falls back to a plain shade until the
        // capture arrives), with a tint so text stays readable over any app
        scrim = new FrameLayout(c) {
            @Override public boolean hasOverlappingRendering() { return false; }
        };
        // the tint is baked into the blurred picture (see PanelService.tint): one full-screen draw
        backdropBase = new View(c);
        backdropBase.setBackgroundColor(Ui.dark ? 0xFF171717 : 0xFFEDEEF0);
        scrim.addView(backdropBase, new LayoutParams(-1, -1));
        backdrop = new ImageView(c);
        backdrop.setScaleType(ImageView.ScaleType.FIT_XY);
        scrim.addView(backdrop, new LayoutParams(-1, -1));
        scrim.setAlpha(0f);
        scrim.setOnClickListener(v -> close());
        addView(scrim, new LayoutParams(-1, -1));

        // a floating card, like the system's own pop-ups
        card = new LinearLayout(c) {
            @Override public boolean hasOverlappingRendering() { return false; }
        };
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, Ui.dpi(c, 16), 0, Ui.dpi(c, 8));
        card.setClickable(true);
        LayoutParams clp = new LayoutParams(-1, -2, Gravity.TOP);
        int m = Ui.dpi(c, 8);
        clp.leftMargin = clp.rightMargin = m;
        clp.topMargin = h.statusBarHeight() + Ui.dpi(c, 6);
        clp.bottomMargin = Ui.dpi(c, 24); // a floating card, never running off the screen
        addView(card, clp);

        // ---- header
        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dpi(c, 22), 0, Ui.dpi(c, 14), Ui.dpi(c, 4));
        LinearLayout tcol = new LinearLayout(c);
        tcol.setOrientation(LinearLayout.VERTICAL);
        time = Ui.text(c, "", 28, Ui.FG, true);
        time.setTypeface(Ui.font(c, 650));
        date = Ui.text(c, "", 13.5f, Ui.FG2, false);
        tcol.addView(time);
        tcol.addView(date, Ui.lp(-2, -2, Ui.dpi(c, 4)));
        head.addView(tcol, new LinearLayout.LayoutParams(0, -2, 1f));
        // notifications: a pill with the bell and how many there are; tap to switch pages
        notifButton = new LinearLayout(c);
        notifButton.setGravity(Gravity.CENTER_VERTICAL);
        notifButton.setPadding(Ui.dpi(c, 11), 0, Ui.dpi(c, 14), 0);
        notifButton.setBackground(Ui.round(TILE, Ui.dp(c, 22)));
        notifBell = new PanelIcons(c, PanelIcons.BELL, Ui.FG);
        notifButton.addView(notifBell, Ui.lp(Ui.dpi(c, 22), Ui.dpi(c, 22)));
        notifCount = Ui.text(c, "", 14, Ui.FG, true);
        notifCount.setTypeface(Ui.font(c, 650));
        LinearLayout.LayoutParams ncp = Ui.lp(-2, -2);
        ncp.leftMargin = Ui.dpi(c, 6);
        notifButton.addView(notifCount, ncp);
        notifButton.setClickable(true);
        Ui.pressable(notifButton);
        notifButton.setOnClickListener(v -> showPage(page == NOTIFS ? CONTROLS : NOTIFS));
        LinearLayout.LayoutParams nbp = Ui.lp(-2, Ui.dpi(c, 44));
        nbp.rightMargin = Ui.dpi(c, 8);
        head.addView(circleButton(c, PanelIcons.GEAR, () -> open(new Intent(Settings.ACTION_SETTINGS))));
        card.addView(head, Ui.lp(-1, -2));

        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dpi(c, 12);
        body.setPadding(pad, 0, pad, Ui.dpi(c, 4));
        scroll.addView(body);
        // weight lets the list shrink (and scroll) when the card hits the screen's bottom margin
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1f);
        slp.topMargin = Ui.dpi(c, 10);
        // the pages sit side by side: notifications | controls | (Bluetooth, from its tile)
        pager = new FrameLayout(c);
        card.addView(pager, slp);
        pager.addView(scroll, new LayoutParams(-1, -2));

        // ---- the notifications page (swapped in for the controls)
        notifScroll = new ScrollView(c);
        notifScroll.setVerticalScrollBarEnabled(false);
        notifScroll.setOverScrollMode(OVER_SCROLL_NEVER);
        LinearLayout npage = new LinearLayout(c);
        npage.setOrientation(LinearLayout.VERTICAL);
        npage.setPadding(pad, 0, pad, Ui.dpi(c, 4));
        LinearLayout nhead = new LinearLayout(c);
        nhead.setGravity(Gravity.CENTER_VERTICAL);
        nhead.setPadding(Ui.dpi(c, 10), 0, 0, 0);
        TextView ntitle = Ui.text(c, "Notifications", 17, Ui.FG, true);
        ntitle.setTypeface(Ui.font(c, 650));
        nhead.addView(ntitle, new LinearLayout.LayoutParams(0, -2, 1f));
        clearAll = Ui.text(c, "Clear all", 13.5f, Ui.FG, true);
        clearAll.setTypeface(Ui.font(c, 600));
        clearAll.setGravity(Gravity.CENTER);
        clearAll.setPadding(Ui.dpi(c, 16), 0, Ui.dpi(c, 16), 0);
        clearAll.setBackground(Ui.round(TILE, Ui.dp(c, 18)));
        clearAll.setClickable(true);
        Ui.pressable(clearAll);
        clearAll.setOnClickListener(v -> { NotifService.dismissAll(); slideTo(CONTROLS); });
        nhead.addView(clearAll, Ui.lp(-2, Ui.dpi(c, 36)));
        npage.addView(nhead, Ui.lp(-1, -2));
        notifList = new LinearLayout(c);
        notifList.setOrientation(LinearLayout.VERTICAL);
        npage.addView(notifList, Ui.lp(-1, -2, Ui.dpi(c, 4)));
        notifEmpty = Ui.text(c, "No notifications", 14, Ui.FG2, false);
        notifEmpty.setGravity(Gravity.CENTER);
        npage.addView(notifEmpty, Ui.lp(-1, Ui.dpi(c, 120)));
        notifScroll.addView(npage);
        notifScroll.setVisibility(GONE);
        pager.addView(notifScroll, new LayoutParams(-1, -2));
        NotifService.panelListener = this::onNotifsChanged;

        // ---- the Bluetooth page
        btScroll = new ScrollView(c);
        btScroll.setVerticalScrollBarEnabled(false);
        btScroll.setOverScrollMode(OVER_SCROLL_NEVER);
        LinearLayout bpage = new LinearLayout(c);
        bpage.setOrientation(LinearLayout.VERTICAL);
        bpage.setPadding(pad, 0, pad, Ui.dpi(c, 4));
        LinearLayout bhead = new LinearLayout(c);
        bhead.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = Ui.text(c, "\u2039", 26, Ui.FG, false);
        back.setGravity(Gravity.CENTER);
        back.setPadding(0, 0, 0, Ui.dpi(c, 3));
        back.setBackground(Ui.round(TILE, Ui.dp(c, 18)));
        back.setClickable(true);
        Ui.pressable(back);
        back.setOnClickListener(v -> slideTo(CONTROLS));
        bhead.addView(back, Ui.lp(Ui.dpi(c, 36), Ui.dpi(c, 36)));
        TextView btitle = Ui.text(c, "Bluetooth", 17, Ui.FG, true);
        btitle.setTypeface(Ui.font(c, 650));
        btitle.setPadding(Ui.dpi(c, 12), 0, 0, 0);
        bhead.addView(btitle, new LinearLayout.LayoutParams(0, -2, 1f));
        btToggle = Ui.text(c, "On", 13.5f, Color.WHITE, true);
        btToggle.setTypeface(Ui.font(c, 600));
        btToggle.setGravity(Gravity.CENTER);
        btToggle.setPadding(Ui.dpi(c, 18), 0, Ui.dpi(c, 18), 0);
        btToggle.setClickable(true);
        Ui.pressable(btToggle);
        btToggle.setOnClickListener(v -> requestBt(!btOn()));
        bhead.addView(btToggle, Ui.lp(-2, Ui.dpi(c, 36)));
        bpage.addView(bhead, Ui.lp(-1, -2));
        btList = new LinearLayout(c);
        btList.setOrientation(LinearLayout.VERTICAL);
        bpage.addView(btList, Ui.lp(-1, -2, Ui.dpi(c, 12)));
        LinearLayout bfoot = new LinearLayout(c);
        bfoot.addView(pill(c, "Pair new device", () -> open(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS))),
                new LinearLayout.LayoutParams(0, Ui.dpi(c, 44), 1f));
        bpage.addView(bfoot, Ui.lp(-1, -2, Ui.dpi(c, 12)));
        btScroll.addView(bpage);
        btScroll.setVisibility(GONE);
        pager.addView(btScroll, new LayoutParams(-1, -2));

        // ---- four tiles, each its own button
        wifi = new Item(c, PanelIcons.WIFI, "Wi-Fi");
        data = new Item(c, PanelIcons.DATA, "Mobile data");
        bluetooth = new Item(c, PanelIcons.BLUETOOTH, "Bluetooth");
        torch = new Item(c, PanelIcons.TORCH, "Torch");
        body.addView(pair(c, wifi, data), Ui.lp(-1, Ui.dpi(c, 64)));
        body.addView(pair(c, bluetooth, torch), Ui.lp(-1, Ui.dpi(c, 64), Ui.dpi(c, 8)));
        wifi.setOnClickListener(v -> open(new Intent(Settings.Panel.ACTION_WIFI)));
        wifi.setOnLongClickListener(v -> { open(new Intent(Settings.ACTION_WIFI_SETTINGS)); return true; });
        // on: show paired devices here; off: Android's one-tap "turn on" prompt. Long-press: settings
        bluetooth.setOnClickListener(v -> { if (btOn()) slideTo(BT); else requestBt(true); });
        bluetooth.setOnLongClickListener(v -> { open(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); return true; });
        data.setOnClickListener(v -> open(new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)));
        torch.setOnClickListener(v -> safe(this::toggleTorch));

        // ---- media (half the width) beside the two sliders, all in one row
        LinearLayout mediaRow = new LinearLayout(c);
        FrameLayout media = new FrameLayout(c);
        mediaRow.addView(media, new LinearLayout.LayoutParams(0, -1, 2f));

        mediaFull = new FrameLayout(c);
        mediaFull.setBackground(Ui.round(TILE, Ui.dp(c, 24)));
        mediaFull.setClipToOutline(true);
        mediaArt = new ImageView(c);
        mediaArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mediaFull.addView(mediaArt, new LayoutParams(-1, -1));
        View shade = new View(c);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xC7000000, 0x80000000, 0xD9000000}));
        mediaFull.addView(shade, new LayoutParams(-1, -1));
        LinearLayout mcol = new LinearLayout(c);
        mcol.setOrientation(LinearLayout.VERTICAL);
        mcol.setPadding(Ui.dpi(c, 14), Ui.dpi(c, 13), Ui.dpi(c, 14), Ui.dpi(c, 8));
        mediaFull.addView(mcol, new LayoutParams(-1, -1));
        mediaTitle = Ui.text(c, "", 14.5f, Color.WHITE, true);
        mediaTitle.setTypeface(Ui.font(c, 650));
        mediaTitle.setMaxLines(2);
        mediaTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mediaArtist = Ui.text(c, "", 12, 0xCCFFFFFF, false);
        mediaArtist.setSingleLine(true);
        mediaArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mcol.addView(mediaTitle, Ui.lp(-1, -2));
        mcol.addView(mediaArtist, Ui.lp(-1, -2, Ui.dpi(c, 2)));
        mcol.addView(new View(c), new LinearLayout.LayoutParams(1, 0, 1f));
        mediaBar = new Progress(c);
        mediaBar.seek = this::seekTo;
        mcol.addView(mediaBar, Ui.lp(-1, Ui.dpi(c, 18)));
        LinearLayout times = new LinearLayout(c);
        mediaTime = Ui.text(c, "", 10.5f, 0xB3FFFFFF, false);
        mediaLeft = Ui.text(c, "", 10.5f, 0xB3FFFFFF, false);
        times.addView(mediaTime, new LinearLayout.LayoutParams(0, -2, 1f));
        times.addView(mediaLeft);
        mcol.addView(times, Ui.lp(-1, -2));
        mediaFull.setClickable(true);
        mediaFull.setOnClickListener(v -> openPlayer());
        LinearLayout controls = new LinearLayout(c);
        controls.setGravity(Gravity.CENTER);
        controls.addView(mediaButton(c, PanelIcons.PREV, false, () -> { if (controller != null) controller.getTransportControls().skipToPrevious(); }),
                Ui.lp(Ui.dpi(c, 40), Ui.dpi(c, 40)));
        mediaPlay = mediaButton(c, PanelIcons.PLAY, true, this::togglePlay);
        LinearLayout.LayoutParams pp = Ui.lp(Ui.dpi(c, 46), Ui.dpi(c, 46));
        pp.leftMargin = pp.rightMargin = Ui.dpi(c, 8);
        controls.addView(mediaPlay, pp);
        controls.addView(mediaButton(c, PanelIcons.NEXT, false, () -> { if (controller != null) controller.getTransportControls().skipToNext(); }),
                Ui.lp(Ui.dpi(c, 40), Ui.dpi(c, 40)));
        mcol.addView(controls, Ui.lp(-1, -2, Ui.dpi(c, 4)));
        mediaFull.setVisibility(GONE);
        media.addView(mediaFull, new LayoutParams(-1, -1));

        mediaEmpty = new LinearLayout(c);
        mediaEmpty.setOrientation(LinearLayout.VERTICAL);
        mediaEmpty.setGravity(Gravity.CENTER);
        mediaEmpty.setBackground(Ui.round(TILE, Ui.dp(c, 24)));
        mediaEmpty.setPadding(Ui.dpi(c, 14), 0, Ui.dpi(c, 14), 0);
        PanelIcons note = new PanelIcons(c, PanelIcons.PLAY, Ui.FG2);
        int np = Ui.dpi(c, 13);
        note.setPadding(np, np, np, np);
        note.setBackground(Ui.round(ROUND, Ui.dp(c, 23)));
        mediaEmpty.addView(note, Ui.lp(Ui.dpi(c, 46), Ui.dpi(c, 46)));
        TextView e1 = Ui.text(c, "Nothing playing", 14, Ui.FG, true);
        e1.setTypeface(Ui.font(c, 600));
        mediaEmpty.addView(e1, Ui.lp(-2, -2, Ui.dpi(c, 10)));
        TextView e2 = Ui.text(c, "Music and videos show up here", 11.5f, Ui.FG2, false);
        e2.setGravity(Gravity.CENTER);
        mediaEmpty.addView(e2, Ui.lp(-2, -2, Ui.dpi(c, 3)));
        media.addView(mediaEmpty, new LayoutParams(-1, -1));

        brightness = new VSlider(c, PanelIcons.SUN);
        volume = new VSlider(c, PanelIcons.SPEAKER);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, -1, 1f);
        bl.leftMargin = Ui.dpi(c, 8);
        mediaRow.addView(brightness, bl);
        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(0, -1, 1f);
        vl.leftMargin = Ui.dpi(c, 8);
        mediaRow.addView(volume, vl);
        brightness.listener = (v, d) -> safe(() -> setBrightness(v, d));
        volume.listener = (v, d) -> safe(() -> setVolume(v, d));
        body.addView(mediaRow, Ui.lp(-1, Ui.dpi(c, 168), Ui.dpi(c, 10)));

        // ---- the grid
        hotspot = new Round(c, PanelIcons.HOTSPOT, "Hotspot");
        rotate = new Round(c, PanelIcons.ROTATE, "Auto-rotate");
        plane = new Round(c, PanelIcons.PLANE, "Aeroplane");
        location = new Round(c, PanelIcons.LOCATION, "Location");
        dark = new Round(c, PanelIcons.DARK, "Dark mode");
        dnd = new Round(c, PanelIcons.DND, "Do not disturb");
        sound = new Round(c, PanelIcons.BELL, "Sound");
        eye = new Round(c, PanelIcons.EYE, "Eye comfort");
        saver = new Round(c, PanelIcons.BATTERY, "Battery saver");
        screenshot = new Round(c, PanelIcons.SCREENSHOT, "Screenshot");
        record = new Round(c, PanelIcons.RECORD, "Screen record");
        cast = new Round(c, PanelIcons.CAST, "Cast");
        calc = new Round(c, PanelIcons.CALC, "Calculator");
        recorder = new Round(c, PanelIcons.MIC, "Recorder");
        lock = new Round(c, PanelIcons.LOCK, "Lock");
        power = new Round(c, PanelIcons.POWER, "Power");
        // long-press any of them and drag to rearrange; the order is remembered
        RoundGrid grid = new RoundGrid(c, Ui.dpi(c, 10));
        grid.setBackground(Ui.round(TILE, Ui.dp(c, 24)));
        grid.setPadding(Ui.dpi(c, 4), Ui.dpi(c, 14), Ui.dpi(c, 4), Ui.dpi(c, 12));
        final android.content.SharedPreferences prefs = c.getSharedPreferences("panel", Context.MODE_PRIVATE);
        grid.setRounds(java.util.Arrays.asList(hotspot, rotate, plane, location, dark, dnd, sound, eye,
                saver, screenshot, record, cast, calc, recorder, lock, power), prefs.getString("order", ""));
        grid.saved = ids -> prefs.edit().putString("order", ids).apply();
        body.addView(grid, Ui.lp(-1, -2, Ui.dpi(c, 10)));

        hotspot.setOnClickListener(v -> open(new Intent(Settings.ACTION_WIRELESS_SETTINGS)));
        rotate.setOnClickListener(v -> safe(this::toggleRotation));
        plane.setOnClickListener(v -> open(new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)));
        location.setOnClickListener(v -> open(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        // Honor's dark-mode screen isn't open to other apps; its switch lives in Display settings
        dark.setOnClickListener(v -> open(new Intent(Settings.ACTION_DISPLAY_SETTINGS)));
        dnd.setOnClickListener(v -> safe(this::toggleDnd));
        sound.setOnClickListener(v -> safe(this::cycleSound));
        eye.setOnClickListener(v -> open(new Intent(Settings.ACTION_DISPLAY_SETTINGS)));
        saver.setOnClickListener(v -> open(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)));
        screenshot.setOnClickListener(v -> closeThen(380, () -> host.globalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)));
        record.setOnClickListener(v -> {
            boolean was = Recorder.recording;
            closeThen(was ? 0 : 300, () -> Recorder.toggle(getContext()));
        });
        Recorder.onChange = () -> record.setActive(Recorder.recording);
        cast.setOnClickListener(v -> open(new Intent(Settings.ACTION_CAST_SETTINGS)));
        calc.setOnClickListener(v -> openApp("com.google.android.calculator", "com.hihonor.calculator"));
        recorder.setOnClickListener(v -> openApp("com.hihonor.soundrecorder", "com.google.android.apps.recorder"));
        lock.setOnClickListener(v -> closeThen(250, () -> host.globalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)));
        power.setOnClickListener(v -> closeThen(200, () -> host.globalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)));

        dots = new Dots(c);
        View handle = dots;
        LinearLayout.LayoutParams hp = Ui.lp(Ui.dpi(c, 60), Ui.dpi(c, 6), Ui.dpi(c, 12));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(handle, hp);

        // The card stays a GPU layer: refreshed content is re-rendered into it while the window
        // is still invisible, and every visible frame of a pull is just a translated texture.
        card.setLayerType(LAYER_TYPE_HARDWARE, null);
        watchTorch();
        setProgress(0f);
    }

    private static LinearLayout row(Context c, View... views) {
        LinearLayout r = new LinearLayout(c);
        for (View v : views) r.addView(v, new LinearLayout.LayoutParams(0, -2, 1f));
        return r;
    }

    private static LinearLayout pair(Context c, View a, View b) {
        LinearLayout r = new LinearLayout(c);
        r.addView(a, new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        lp.leftMargin = Ui.dpi(c, 8);
        r.addView(b, lp);
        return r;
    }

    private PanelIcons circleButton(Context c, int type, Runnable action) {
        PanelIcons b = new PanelIcons(c, type, Ui.FG);
        b.setBackground(Ui.round(TILE, Ui.dp(c, 22)));
        int p = Ui.dpi(c, 11);
        b.setPadding(p, p, p, p);
        b.setClickable(true);
        Ui.pressable(b);
        b.setOnClickListener(v -> action.run());
        b.setLayoutParams(Ui.lp(Ui.dpi(c, 44), Ui.dpi(c, 44)));
        return b;
    }

    private PanelIcons mediaButton(Context c, int type, boolean big, Runnable action) {
        PanelIcons b = new PanelIcons(c, type, big ? 0xFF111111 : Color.WHITE);
        int p = Ui.dpi(c, big ? 15 : 13);
        b.setPadding(p, p, p, p);
        if (big) b.setBackground(Ui.round(Color.WHITE, Ui.dp(c, 27)));
        b.setClickable(true);
        Ui.pressable(b);
        b.setOnClickListener(v -> { action.run(); v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); });
        return b;
    }

    private static void safe(Runnable r) {
        try { r.run(); } catch (Throwable t) { Diag.log("panel: " + t); }
    }

    // ================================================================ state

    /** The blurred capture of the screen behind; fades in over the plain shade. */
    void setBackdrop(Bitmap b) {
        backdrop.setImageBitmap(b);
        if (progress <= 0f) {
            // not visible yet (the usual case): no fade needed, and the plain shade can go
            backdrop.setAlpha(1f);
            backdropBase.setVisibility(INVISIBLE);
            return;
        }
        backdrop.setAlpha(0f);
        backdrop.animate().alpha(1f).setDuration(140)
                .withEndAction(() -> backdropBase.setVisibility(INVISIBLE)).start();
    }

    /** Re-read every control's state; called the moment a finger lands on the pull strip. */
    void refresh(boolean notifications) {
        final Context c = getContext();
        scroll.scrollTo(0, 0);
        showPage(notifications ? NOTIFS : CONTROLS);
        showNotifCount();
        // Each reading is isolated: this runs in the launcher's process, and one OEM quirk or
        // missing permission must never take the home screen down with it.
        safe(() -> {
            Calendar cal = Calendar.getInstance();
            time.setText(MainActivity.clockText(cal));
            String d = new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(cal.getTime());
            android.os.BatteryManager bm = (android.os.BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
            int level = bm == null ? -1 : bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
            if (level >= 0) d += "  ·  " + level + "%" + (bm.isCharging() ? (level >= 100 ? " charged" : " charging") : "");
            setIfChanged(date, d);
        });
        final WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        safe(() -> {
            boolean on = wm != null && wm.isWifiEnabled();
            wifi.set(on, on ? wifiName(wm) : "Off");
        });
        safe(() -> {
            android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
            boolean on = bm != null && bm.getAdapter() != null && bm.getAdapter().isEnabled();
            bluetooth.set(on, on ? "On" : "Off");
        });
        safe(() -> {
            boolean on = Settings.Global.getInt(c.getContentResolver(), "mobile_data", 0) == 1;
            data.set(on, on ? "On" : "Off");
        });
        torch.set(torchOn, torchOn ? "On" : "Off");
        safe(() -> brightness.setValue(Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128) / 255f));
        safe(() -> {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            volume.setValue(am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)));
        });
        safe(() -> hotspot.setActive(hotspotOn(wm)));
        safe(() -> {
            boolean auto = Settings.System.getInt(c.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
            rotate.setActive(auto);
            rotate.icon.setType(auto ? PanelIcons.ROTATE : PanelIcons.ROTATE_LOCK);
        });
        safe(() -> plane.setActive(Settings.Global.getInt(c.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1));
        safe(() -> {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            location.setActive(lm != null && lm.isLocationEnabled());
        });
        safe(() -> dark.setActive(((UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE)).getNightMode() == UiModeManager.MODE_NIGHT_YES));
        safe(() -> {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            dnd.setActive(nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL);
        });
        safe(this::showSound);
        safe(() -> eye.setActive(Settings.System.getInt(c.getContentResolver(), "eyes_protection_mode", 0) == 1));
        safe(() -> saver.setActive(((PowerManager) c.getSystemService(Context.POWER_SERVICE)).isPowerSaveMode()));
        record.setActive(Recorder.recording);
        safe(this::bindMedia);
    }

    private String wifiName(WifiManager wm) {
        try {
            String ssid = wm.getConnectionInfo().getSSID();
            if (ssid != null && !ssid.contains("unknown ssid")) return ssid.replace("\"", "");
        } catch (Exception ignored) {}
        return "On";
    }

    /** WifiManager.isWifiApEnabled is hidden but readable; anything else just reads as off. */
    private boolean hotspotOn(WifiManager wm) {
        try {
            return (Boolean) WifiManager.class.getMethod("isWifiApEnabled").invoke(wm);
        } catch (Throwable t) {
            return false;
        }
    }

    // ================================================================ actions

    private void open(Intent i) {
        close();
        host.launch(i);
    }

    private void closeThen(long delay, Runnable r) {
        close();
        main.postDelayed(() -> safe(r), delay);
    }

    private void openApp(String... pkgs) {
        for (String p : pkgs) {
            Intent i = getContext().getPackageManager().getLaunchIntentForPackage(p);
            if (i != null) { open(i); return; }
        }
    }

    private void toggleRotation() {
        Context c = getContext();
        if (!Settings.System.canWrite(c)) {
            open(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + c.getPackageName())));
            return;
        }
        boolean auto = Settings.System.getInt(c.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
        Settings.System.putInt(c.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, auto ? 0 : 1);
        rotate.setActive(!auto);
        rotate.icon.setType(!auto ? PanelIcons.ROTATE : PanelIcons.ROTATE_LOCK);
    }

    private void watchTorch() {
        try {
            for (String id : cameras.getCameraIdList()) {
                CameraCharacteristics cc = cameras.getCameraCharacteristics(id);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))
                        && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    torchId = id;
                    break;
                }
            }
            cameras.registerTorchCallback(new CameraManager.TorchCallback() {
                @Override public void onTorchModeChanged(String id, boolean on) {
                    if (!id.equals(torchId)) return;
                    torchOn = on;
                    torch.set(on, on ? "On" : "Off");
                }
            }, main);
        } catch (Exception ignored) {}
    }

    private void toggleTorch() {
        if (torchId == null) return;
        try { cameras.setTorchMode(torchId, !torchOn); } catch (Exception ignored) {}
    }

    private void toggleDnd() {
        NotificationManager nm = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        if (!nm.isNotificationPolicyAccessGranted()) {
            open(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
            return;
        }
        boolean on = nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL;
        nm.setInterruptionFilter(on ? NotificationManager.INTERRUPTION_FILTER_ALL : NotificationManager.INTERRUPTION_FILTER_PRIORITY);
        dnd.setActive(!on);
    }

    /** Ring -> vibrate -> silent (silent needs Do-not-disturb access, otherwise ring <-> vibrate). */
    private void cycleSound() {
        AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        NotificationManager nm = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        int m = am.getRingerMode();
        int next;
        if (m == AudioManager.RINGER_MODE_NORMAL) next = AudioManager.RINGER_MODE_VIBRATE;
        else if (m == AudioManager.RINGER_MODE_VIBRATE && nm.isNotificationPolicyAccessGranted()) next = AudioManager.RINGER_MODE_SILENT;
        else next = AudioManager.RINGER_MODE_NORMAL;
        am.setRingerMode(next);
        showSound();
    }

    private void showSound() {
        AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        int m = am.getRingerMode();
        sound.icon.setType(m == AudioManager.RINGER_MODE_VIBRATE ? PanelIcons.VIBRATE
                : m == AudioManager.RINGER_MODE_SILENT ? PanelIcons.BELL_OFF : PanelIcons.BELL);
        sound.setLabel(m == AudioManager.RINGER_MODE_VIBRATE ? "Vibrate" : m == AudioManager.RINGER_MODE_SILENT ? "Silent" : "Ring");
        sound.setActive(m != AudioManager.RINGER_MODE_NORMAL);
    }

    private long lastBrightnessWrite;
    private boolean askedWrite;

    private void setBrightness(float v, boolean done) {
        Context c = getContext();
        if (!Settings.System.canWrite(c)) {
            if (!askedWrite) {
                askedWrite = true;
                open(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + c.getPackageName())));
            }
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (!done && now - lastBrightnessWrite < 32) return; // ~30 writes a second is plenty
        lastBrightnessWrite = now;
        Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, Math.max(1, Math.round(v * 255)));
    }

    private void setVolume(float v, boolean done) {
        AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(v * max), 0);
    }

    // ================================================================ media

    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { showMedia(); }
        @Override public void onPlaybackStateChanged(PlaybackState s) { showMedia(); }
    };

    /** Only a real track: it has a title and is playing, paused or loading (not an idle session). */
    private static boolean isReal(MediaController mc) {
        MediaMetadata m = mc.getMetadata();
        PlaybackState s = mc.getPlaybackState();
        if (m == null || s == null) return false;
        CharSequence t = m.getText(MediaMetadata.METADATA_KEY_TITLE);
        int st = s.getState();
        return t != null && t.length() > 0 && (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_PAUSED
                || st == PlaybackState.STATE_BUFFERING || st == PlaybackState.STATE_CONNECTING);
    }

    private void bindMedia() {
        MediaController next = null;
        try {
            MediaSessionManager msm = (MediaSessionManager) getContext().getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> list = msm.getActiveSessions(new ComponentName(getContext(), NotifService.class));
            for (MediaController mc : list) if (isReal(mc)) { next = mc; break; }
        } catch (Exception ignored) {
            // needs notification access; without it the card says "Nothing playing"
        }
        if (controller != null && controller != next) controller.unregisterCallback(mediaCallback);
        if (next != null && next != controller) next.registerCallback(mediaCallback, main);
        controller = next;
        showMedia();
    }

    private void showMedia() {
        boolean real = controller != null && isReal(controller);
        if (progress > 0f) {
            Ui.fadeVisible(mediaFull, real);
            Ui.fadeVisible(mediaEmpty, !real);
        } else {
            mediaFull.setVisibility(real ? VISIBLE : GONE);
            mediaEmpty.setVisibility(real ? GONE : VISIBLE);
        }
        main.removeCallbacks(tickProgress);
        if (!real) return;
        MediaMetadata m = controller.getMetadata();
        CharSequence artist = m.getText(MediaMetadata.METADATA_KEY_ARTIST);
        CharSequence title = m.getText(MediaMetadata.METADATA_KEY_TITLE);
        setIfChanged(mediaTitle, title);
        setIfChanged(mediaArtist, artist == null ? "" : artist);
        // Every getMetadata() hands over a fresh bitmap copy: keep the one already on the GPU
        // while the track is the same, and shrink new art to the card's size before upload.
        String key = controller.getPackageName() + '\n' + title + '\n' + artist;
        if (!key.equals(artKey)) {
            artKey = key;
            Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            mediaArt.setImageBitmap(art == null ? null : fit(art, getResources().getDisplayMetrics().widthPixels));
        }
        PlaybackState s = controller.getPlaybackState();
        boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
        mediaPlay.setType(playing ? PanelIcons.PAUSE : PanelIcons.PLAY);
        tickProgress.run();
    }

    /** Position = last reported position + time since, scaled by playback speed. */
    private final Runnable tickProgress = new Runnable() {
        @Override public void run() {
            if (controller == null) return;
            PlaybackState s = controller.getPlaybackState();
            MediaMetadata m = controller.getMetadata();
            long dur = m == null ? 0 : m.getLong(MediaMetadata.METADATA_KEY_DURATION);
            if (s == null || dur <= 0) {
                mediaBar.setFraction(0f);
                setIfChanged(mediaTime, "");
                setIfChanged(mediaLeft, "");
                return;
            }
            long pos = s.getPosition();
            if (s.getState() == PlaybackState.STATE_PLAYING) {
                pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
            }
            pos = Math.max(0, Math.min(dur, pos));
            mediaBar.setFraction(pos / (float) dur);
            setIfChanged(mediaTime, clock(pos));
            setIfChanged(mediaLeft, "-" + clock(dur - pos));
            if (s.getState() == PlaybackState.STATE_PLAYING && progress > 0f) main.postDelayed(this, 500);
        }
    };

    private String artKey;

    /** setText always re-lays out; skip it when nothing changed (it would redraw the whole card layer). */
    private static void setIfChanged(TextView t, CharSequence s) {
        if (!android.text.TextUtils.equals(t.getText(), s)) t.setText(s);
    }

    private static Bitmap fit(Bitmap b, int maxW) {
        b = trimBars(b);
        if (b.getWidth() <= maxW) return b;
        return Bitmap.createScaledBitmap(b, maxW, Math.max(1, Math.round(b.getHeight() * (maxW / (float) b.getWidth()))), true);
    }

    /** Video thumbnails often come letterboxed; cut the black bars so the art fills the card. */
    private static Bitmap trimBars(Bitmap b) {
        if (b.getConfig() == Bitmap.Config.HARDWARE) return b;
        int w = b.getWidth(), h = b.getHeight(), top = 0, bottom = h;
        int[] row = new int[w];
        while (top < h / 3 && dark(b, row, top)) top++;
        while (bottom > h * 2 / 3 && dark(b, row, bottom - 1)) bottom--;
        if (top == 0 && bottom == h) return b;
        return Bitmap.createBitmap(b, 0, top, w, bottom - top);
    }

    private static boolean dark(Bitmap b, int[] row, int y) {
        b.getPixels(row, 0, row.length, 0, y, row.length, 1);
        for (int i = 0; i < row.length; i += 8) {
            int p = row[i];
            if (((p >> 16) & 0xFF) > 24 || ((p >> 8) & 0xFF) > 24 || (p & 0xFF) > 24) return false;
        }
        return true;
    }

    private static String clock(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private void togglePlay() {
        if (controller == null) return;
        PlaybackState s = controller.getPlaybackState();
        if (s != null && s.getState() == PlaybackState.STATE_PLAYING) controller.getTransportControls().pause();
        else controller.getTransportControls().play();
    }

    void unbind() {
        backdrop.setImageDrawable(null); // never show one app's blur over the next
        backdropBase.setVisibility(VISIBLE);
        main.removeCallbacks(tickProgress);
        if (controller != null) controller.unregisterCallback(mediaCallback);
        controller = null;
    }

    // ================================================================ bluetooth

    private final java.util.Set<String> btConnected = new java.util.HashSet<>();
    private boolean btProxies;

    private android.bluetooth.BluetoothAdapter btAdapter() {
        android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        return bm == null ? null : bm.getAdapter();
    }

    private boolean btOn() {
        try { android.bluetooth.BluetoothAdapter a = btAdapter(); return a != null && a.isEnabled(); }
        catch (Exception e) { return false; }
    }

    /** Apps can't switch Bluetooth themselves any more; Android shows its own one-tap prompt. */
    private void requestBt(boolean on) {
        Intent i = new Intent(on ? android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE
                : "android.bluetooth.adapter.action.REQUEST_DISABLE");
        if (i.resolveActivity(getContext().getPackageManager()) == null) i = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
        open(i);
    }

    /** Which paired devices are connected: asked of the audio profiles (headphones, speakers, cars). */
    private void watchBtProfiles() {
        if (btProxies) return;
        android.bluetooth.BluetoothAdapter a = btAdapter();
        if (a == null) return;
        btProxies = true;
        android.bluetooth.BluetoothProfile.ServiceListener l = new android.bluetooth.BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, android.bluetooth.BluetoothProfile proxy) {
                try {
                    for (android.bluetooth.BluetoothDevice d : proxy.getConnectedDevices()) btConnected.add(d.getAddress());
                } catch (Exception ignored) {}
                if (page == BT) buildBt();
            }
            @Override public void onServiceDisconnected(int profile) {}
        };
        int[] profiles = {android.bluetooth.BluetoothProfile.A2DP, android.bluetooth.BluetoothProfile.HEADSET,
                android.bluetooth.BluetoothProfile.HEARING_AID, 22 /* LE_AUDIO */};
        for (int pr : profiles) try { a.getProfileProxy(getContext(), l, pr); } catch (Exception ignored) {}
    }

    private void buildBt() {
        Context c = getContext();
        boolean on = btOn();
        btToggle.setText(on ? "On" : "Off");
        btToggle.setTextColor(on ? Color.WHITE : Ui.FG);
        btToggle.setBackground(Ui.round(on ? Ui.ACCENT : TILE, Ui.dp(c, 18)));
        btList.removeAllViews();
        if (!on) { btList.addView(btMessage(c, "Bluetooth is off")); return; }
        java.util.List<android.bluetooth.BluetoothDevice> devices = new java.util.ArrayList<>();
        try {
            devices.addAll(btAdapter().getBondedDevices());
        } catch (SecurityException e) {
            btList.addView(btMessage(c, "Allow \u201CNearby devices\u201D for Fast Launcher to list them here"));
            return;
        }
        watchBtProfiles();
        java.util.Collections.sort(devices, (x, y) -> {
            boolean cx = btConnected.contains(x.getAddress()), cy = btConnected.contains(y.getAddress());
            if (cx != cy) return cx ? -1 : 1;
            return String.valueOf(btName(x)).compareToIgnoreCase(String.valueOf(btName(y)));
        });
        if (devices.isEmpty()) { btList.addView(btMessage(c, "No paired devices yet")); return; }
        for (android.bluetooth.BluetoothDevice d : devices) {
            boolean connected = btConnected.contains(d.getAddress());
            LinearLayout r = new LinearLayout(c);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setBackground(Ui.round(TILE, Ui.dp(c, 22)));
            int p = Ui.dpi(c, 10);
            r.setPadding(p, p, Ui.dpi(c, 16), p);
            PanelIcons icon = new PanelIcons(c, btAudio(d) ? PanelIcons.SPEAKER : PanelIcons.BLUETOOTH, connected ? Color.WHITE : Ui.FG);
            int ip = Ui.dpi(c, 11);
            icon.setPadding(ip, ip, ip, ip);
            icon.setBackground(Ui.round(connected ? Ui.ACCENT : ROUND, Ui.dp(c, 22)));
            r.addView(icon, Ui.lp(Ui.dpi(c, 44), Ui.dpi(c, 44)));
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(Ui.dpi(c, 12), 0, 0, 0);
            TextView name = Ui.text(c, btName(d), 14.5f, Ui.FG, true);
            name.setTypeface(Ui.font(c, 600));
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(name);
            col.addView(Ui.text(c, connected ? "Connected" : "Paired", 12, connected ? Ui.ACCENT : Ui.FG2, false), Ui.lp(-2, -2, Ui.dpi(c, 2)));
            r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
            r.setClickable(true);
            Ui.pressable(r);
            // connecting is reserved for system apps: Bluetooth settings connects it in one tap
            r.setOnClickListener(v -> open(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
            btList.addView(r, Ui.lp(-1, -2, btList.getChildCount() == 0 ? 0 : Ui.dpi(c, 8)));
        }
    }

    private static String btName(android.bluetooth.BluetoothDevice d) {
        try {
            String n = d.getAlias();
            if (n == null || n.isEmpty()) n = d.getName();
            return n == null || n.isEmpty() ? d.getAddress() : n;
        } catch (SecurityException e) {
            return "Device";
        }
    }

    private static boolean btAudio(android.bluetooth.BluetoothDevice d) {
        try {
            android.bluetooth.BluetoothClass k = d.getBluetoothClass();
            return k != null && k.getMajorDeviceClass() == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO;
        } catch (Exception e) {
            return false;
        }
    }

    private TextView btMessage(Context c, String msg) {
        TextView t = Ui.text(c, msg, 14, Ui.FG2, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dpi(c, 16), Ui.dpi(c, 28), Ui.dpi(c, 16), Ui.dpi(c, 28));
        return t;
    }

    private TextView pill(Context c, String label, Runnable r) {
        TextView t = Ui.text(c, label, 14, Ui.FG, true);
        t.setTypeface(Ui.font(c, 600));
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.round(TILE, Ui.dp(c, 22)));
        t.setClickable(true);
        Ui.pressable(t);
        t.setOnClickListener(v -> r.run());
        return t;
    }

    // ================================================================ notifications

    private final java.util.Map<String, CharSequence> appNames = new java.util.HashMap<>();
    private final java.util.Map<String, android.graphics.drawable.Drawable> appIcons = new java.util.HashMap<>();

    private void onNotifsChanged() {
        notifsDirty = true;
        showNotifCount();
        if (page == NOTIFS) buildNotifs();
    }

    private void showNotifCount() {
        int n = NotifService.list.size();
        setIfChanged(notifCount, n == 0 ? "" : n > 99 ? "99+" : String.valueOf(n));
        notifCount.setVisibility(n == 0 ? GONE : VISIBLE);
    }

    private ScrollView pageScroll() { return page == NOTIFS ? notifScroll : page == BT ? btScroll : scroll; }

    /** The card shows one page at a time: the controls, the notifications, or Bluetooth devices. */
    private void showPage(int p) {
        if (p == NOTIFS && notifsDirty) buildNotifs();
        if (p == BT) buildBt();
        if (pageAnim != null) pageAnim.cancel();
        for (ScrollView v : new ScrollView[]{scroll, notifScroll, btScroll}) v.setTranslationX(0f);
        if (p != page) {
            notifScroll.scrollTo(0, 0);
            btScroll.scrollTo(0, 0);
        }
        page = p;
        scroll.setVisibility(p == CONTROLS ? VISIBLE : GONE);
        notifScroll.setVisibility(p == NOTIFS ? VISIBLE : GONE);
        btScroll.setVisibility(p == BT ? VISIBLE : GONE);
        dots.setPosition(p == NOTIFS ? 0f : 1f);
    }

    // ---------------------------------------------------------------- swiping between pages

    private static int slot(int p) { return p == NOTIFS ? 0 : p == CONTROLS ? 1 : 2; }

    private ScrollView pageView(int p) { return p == NOTIFS ? notifScroll : p == BT ? btScroll : scroll; }

    /** Which page a sideways swipe leads to: notifications sit left of the controls. */
    private static int neighbour(int from, float dx) {
        if (from == CONTROLS) return dx > 0 ? NOTIFS : -1;
        if (from == NOTIFS) return dx < 0 ? CONTROLS : -1;
        return dx > 0 ? CONTROLS : -1; // Bluetooth: back to the controls
    }

    /** offset: how far the current page has moved; the neighbour rides alongside it. */
    private void layPages(int from, int to, float offset) {
        float w = Math.max(1, pager.getWidth());
        pageView(from).setTranslationX(offset);
        if (to >= 0) {
            View v = pageView(to);
            if (v.getVisibility() != VISIBLE) {
                if (to == NOTIFS && notifsDirty) buildNotifs();
                if (to == BT) buildBt();
                v.setVisibility(VISIBLE);
            }
            v.setTranslationX(offset + (slot(to) - slot(from)) * w);
            float f = Math.min(1f, Math.abs(offset) / w);
            float a = from == NOTIFS ? 0f : 1f, b = to == NOTIFS ? 0f : 1f;
            dots.setPosition(a + (b - a) * f);
        }
    }

    private void slideTo(int target) {
        if (target == page) return;
        animatePages(page, target, 0f, true, 0f);
    }

    private void animatePages(final int from, final int to, float start, final boolean commit, float velocity) {
        if (pageAnim != null) pageAnim.cancel();
        float w = Math.max(1, pager.getWidth());
        float end = commit && to >= 0 ? -(slot(to) - slot(from)) * w : 0f;
        long dur = Math.round(170 + 110 * Math.abs(end - start) / w);
        if (Math.abs(velocity) > 600) dur = Math.max(140, Math.min(dur, Math.round(Math.abs(end - start) / Math.abs(velocity) * 1000 * 1.6f)));
        card.setLayerType(LAYER_TYPE_NONE, null); // two pages moving: drawing them directly is cheaper
        pageAnim = ValueAnimator.ofFloat(start, end);
        pageAnim.setDuration(dur);
        pageAnim.setInterpolator(Ui.EMPHASIZED);
        pageAnim.addUpdateListener(a -> layPages(from, to, (Float) a.getAnimatedValue()));
        pageAnim.addListener(new AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationCancel(Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(Animator a) {
                card.setLayerType(LAYER_TYPE_HARDWARE, null);
                if (cancelled) return;
                pageAnim = null;
                showPage(commit && to >= 0 ? to : from);
            }
        });
        pageAnim.start();
    }

    private void buildNotifs() {
        notifsDirty = false;
        if (page == NOTIFS && progress > 0f) android.transition.TransitionManager.beginDelayedTransition(notifList, Ui.motion());
        notifList.removeAllViews();
        List<android.service.notification.StatusBarNotification> all = NotifService.list;
        int shown = Math.min(all.size(), 40);
        for (int i = 0; i < shown; i++) {
            View row = notifRow(all.get(i));
            if (row != null) notifList.addView(row, Ui.lp(-1, -2, i == 0 ? 0 : Ui.dpi(getContext(), 8)));
        }
        boolean any = notifList.getChildCount() > 0;
        notifEmpty.setVisibility(any ? GONE : VISIBLE);
        clearAll.setVisibility(any ? VISIBLE : INVISIBLE);
    }

    private View notifRow(final android.service.notification.StatusBarNotification s) {
        final Context c = getContext();
        final android.app.Notification n = s.getNotification();
        android.os.Bundle x = n.extras;
        CharSequence title = x == null ? null : x.getCharSequence(android.app.Notification.EXTRA_TITLE);
        CharSequence text = x == null ? null : x.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT);
        if (text == null && x != null) text = x.getCharSequence(android.app.Notification.EXTRA_TEXT);
        if (title == null && text == null) return null;
        String pkg = s.getPackageName();

        NotifRow row = new NotifRow(c, () -> {
            NotifService.dismiss(s.getKey());
        }, () -> openNotif(s));
        row.setGravity(Gravity.TOP);
        row.setBackground(Ui.round(TILE, Ui.dp(c, 22)));
        int p = Ui.dpi(c, 14);
        row.setPadding(p, p, p, p);

        // the sender's picture when there is one (a chat contact), otherwise the app's icon
        ImageView icon = new ImageView(c);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        android.graphics.drawable.Drawable d = null;
        try { if (n.getLargeIcon() != null) d = n.getLargeIcon().loadDrawable(c); } catch (Exception ignored) {}
        if (d != null) {
            icon.setBackground(Ui.round(ROUND, Ui.dp(c, 20)));
            icon.setClipToOutline(true);
        } else {
            d = appIcon(pkg);
        }
        icon.setImageDrawable(d);
        row.addView(icon, Ui.lp(Ui.dpi(c, 40), Ui.dpi(c, 40)));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dpi(c, 12), 0, 0, 0);
        TextView meta = Ui.text(c, appName(pkg) + "  ·  " + ago(s.getPostTime()), 11.5f, Ui.FG2, false);
        meta.setSingleLine(true);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(meta);
        if (title != null) {
            TextView t = Ui.text(c, title.toString().trim(), 14.5f, Ui.FG, true);
            t.setTypeface(Ui.font(c, 600));
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(t, Ui.lp(-1, -2, Ui.dpi(c, 3)));
        }
        if (text != null) {
            TextView b = Ui.text(c, text.toString().trim(), 13, Ui.FG2, false);
            b.setMaxLines(2);
            b.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(b, Ui.lp(-1, -2, Ui.dpi(c, 2)));
        }
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        row.dismissable = (n.flags & android.app.Notification.FLAG_NO_CLEAR) == 0;
        return row;
    }

    private void openNotif(android.service.notification.StatusBarNotification s) {
        android.app.Notification n = s.getNotification();
        if (!send(n.contentIntent)) { openApp(s.getPackageName()); return; }
        if ((n.flags & android.app.Notification.FLAG_AUTO_CANCEL) != 0) NotifService.dismiss(s.getKey());
        close();
    }

    /** Fire an app's PendingIntent from the panel (allowed to start its activity from the background). */
    private boolean send(android.app.PendingIntent pi) {
        if (pi == null) return false;
        try {
            android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
            if (android.os.Build.VERSION.SDK_INT >= 34)
                o.setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            pi.send(getContext(), 0, null, null, null, null, o.toBundle());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Tap the now-playing card: open the player that owns it. */
    private void openPlayer() {
        if (controller == null) return;
        if (send(controller.getSessionActivity())) { close(); return; }
        openApp(controller.getPackageName());
    }

    private void seekTo(float fraction) {
        if (controller == null || controller.getMetadata() == null) return;
        long dur = controller.getMetadata().getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (dur > 0) controller.getTransportControls().seekTo(Math.round(dur * fraction));
    }

    private CharSequence appName(String pkg) {
        CharSequence name = appNames.get(pkg);
        if (name == null) {
            try {
                android.content.pm.PackageManager pm = getContext().getPackageManager();
                name = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            } catch (Exception e) {
                name = pkg;
            }
            appNames.put(pkg, name);
        }
        return name;
    }

    private android.graphics.drawable.Drawable appIcon(String pkg) {
        android.graphics.drawable.Drawable d = appIcons.get(pkg);
        if (d == null) {
            try { d = getContext().getPackageManager().getApplicationIcon(pkg); } catch (Exception ignored) {}
            if (d != null) appIcons.put(pkg, d);
        }
        return d == null ? null : d.getConstantState() != null ? d.getConstantState().newDrawable() : d;
    }

    private static String ago(long t) {
        long m = (System.currentTimeMillis() - t) / 60000;
        if (m < 1) return "now";
        if (m < 60) return m + "m";
        if (m < 24 * 60) return (m / 60) + "h";
        return (m / (24 * 60)) + "d";
    }

    /** A notification card: tap to open, swipe sideways to dismiss. */
    static final class NotifRow extends LinearLayout {
        static boolean touching; // read by the panel: a right swipe here belongs to the card
        boolean dismissable = true;
        private final Runnable onDismiss, onOpen;
        private float downX, downY;
        private boolean swiping, cancelled;
        private VelocityTracker vt;
        private final int slop;

        NotifRow(Context c, Runnable dismiss, Runnable open) {
            super(c);
            onDismiss = dismiss;
            onOpen = open;
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (vt == null) vt = VelocityTracker.obtain();
            vt.addMovement(e);
            float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX(); downY = e.getRawY();
                    swiping = false; cancelled = false;
                    touching = true;
                    animate().scaleX(0.98f).scaleY(0.98f).setDuration(90).start();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!swiping && !cancelled && dismissable && dx > slop && Math.abs(dx) > Math.abs(dy)) {
                        swiping = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (!swiping && Math.hypot(dx, dy) > slop) cancelled = true;
                    if (swiping) {
                        setTranslationX(dx);
                        setAlpha(1f - Math.min(1f, Math.abs(dx) / Math.max(1, getWidth())));
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    touching = false;
                    animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                    vt.computeCurrentVelocity(1000);
                    float vx = vt.getXVelocity();
                    vt.recycle();
                    vt = null;
                    if (swiping) {
                        boolean out = Math.abs(dx) > getWidth() * 0.35f || (Math.abs(vx) > 1200 && Math.signum(vx) == Math.signum(dx));
                        if (out && e.getActionMasked() == MotionEvent.ACTION_UP) {
                            float to = Math.signum(dx == 0 ? vx : dx) * getWidth();
                            animate().translationX(to).alpha(0f).setDuration(160).withEndAction(() -> {
                                ViewGroup p = (ViewGroup) getParent();
                                if (p != null) {
                                    android.transition.TransitionManager.beginDelayedTransition(p, Ui.motion());
                                    p.removeView(this);
                                }
                                onDismiss.run();
                            }).start();
                        } else {
                            animate().translationX(0f).alpha(1f).setDuration(180).start();
                        }
                    } else if (!cancelled && e.getActionMasked() == MotionEvent.ACTION_UP) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        onOpen.run();
                    }
                    swiping = false;
                    return true;
            }
            return true;
        }
    }

    // ================================================================ motion

    private float cardTravel() {
        LayoutParams lp = (LayoutParams) card.getLayoutParams();
        return Math.max(1, card.getHeight() + lp.topMargin + Ui.dp(getContext(), 16));
    }

    void setProgress(float p) {
        progress = Math.max(0f, Math.min(1f, p));
        float h = card.getHeight() > 0 ? cardTravel() : getResources().getDisplayMetrics().heightPixels;
        card.setTranslationY(-(1f - progress) * h);
        scrim.setAlpha(progress);
    }

    /** Follow a pull from the status bar: dy is how far the finger has come down. */
    void dragFromTop(float dy) {
        if (anim != null) anim.cancel();
        boolean was = progress > 0f;
        setProgress(dy / cardTravel());
        if (!was && progress > 0f && controller != null) tickProgress.run();
    }

    void settle(float velocity) {
        float target = velocity > 600 ? 1f : velocity < -600 ? 0f : (progress > 0.4f ? 1f : 0f);
        animateTo(target, Math.abs(velocity));
    }

    void animateTo(final float target, float velocity) {
        if (anim != null) anim.cancel();
        float dist = Math.abs(target - progress);
        long dur = Math.round(150 + 170 * dist);
        if (velocity > 300) dur = Math.max(130, Math.min(dur, Math.round(dist * cardTravel() / velocity * 1000 * 1.5f)));
        anim = ValueAnimator.ofFloat(progress, target);
        anim.setDuration(dur);
        anim.setInterpolator(Ui.EMPHASIZED);
        anim.addUpdateListener(a -> setProgress((Float) a.getAnimatedValue()));
        anim.addListener(new AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationCancel(Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(Animator a) {
                if (cancelled) return;
                if (target == 0f) host.finishHide();
                else if (controller != null) tickProgress.run();
            }
        });
        anim.start();
    }

    void close() {
        if (progress <= 0f) { host.finishHide(); return; }
        animateTo(0f, 0f);
    }

    @Override public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (e.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == android.view.KeyEvent.ACTION_UP) close();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // swipe the card back up to close (once its content can't scroll further)
    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX(); downY = e.getRawY(); dragging = false;
                float edge = Ui.dp(getContext(), 24);
                fromEdge = downX < edge || downX > getWidth() - edge;
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
                vt.addMovement(e);
                break;
            case MotionEvent.ACTION_MOVE:
                if (vt != null) vt.addMovement(e);
                float dy = e.getRawY() - downY, dx = e.getRawX() - downX;
                if (dy < -slop && Math.abs(dy) > Math.abs(dx) && !pageScroll().canScrollVertically(1)) {
                    dragging = true;
                    downY = e.getRawY();
                    startProgress = progress;
                    if (anim != null) anim.cancel();
                    return true;
                }
                // from the screen edge it's the back gesture (the system doesn't always send Back
                // over the home screen): close, whichever page is showing
                if (fromEdge && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                    fromEdge = false;
                    close();
                    return true;
                }
                // sideways: switch pages (a right swipe on a notification card dismisses it instead)
                if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.5f
                        && !(NotifRow.touching && dx > 0) && (pageAnim == null || !pageAnim.isRunning())) {
                    paging = true;
                    pagingTo = -1;
                    downX = e.getRawX() - Math.signum(dx) * slop; // no jump of the slop distance
                    card.setLayerType(LAYER_TYPE_NONE, null);
                    return true;
                }
                break;
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (paging) {
            if (vt != null) vt.addMovement(e);
            float dx = e.getRawX() - downX;
            int to = neighbour(page, dx);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    if (pagingTo >= 0 && pagingTo != to) pageView(pagingTo).setVisibility(GONE);
                    pagingTo = to;
                    layPages(page, to, to < 0 ? dx * 0.2f : dx); // no page that way: just give a little
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    paging = false;
                    float vx = 0;
                    if (vt != null) { vt.computeCurrentVelocity(1000); vx = vt.getXVelocity(); vt.recycle(); vt = null; }
                    float w = Math.max(1, pager.getWidth());
                    boolean commit = to >= 0 && e.getActionMasked() == MotionEvent.ACTION_UP
                            && (Math.abs(dx) > w * 0.3f || (Math.abs(vx) > 800 && Math.signum(vx) == Math.signum(dx)));
                    animatePages(page, to, to < 0 ? dx * 0.2f : dx, commit, vx);
                    return true;
                }
            }
            return true;
        }
        if (!dragging) return super.onTouchEvent(e);
        if (vt != null) vt.addMovement(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                setProgress(startProgress + (e.getRawY() - downY) / cardTravel());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                vt.computeCurrentVelocity(1000);
                settle(vt.getYVelocity());
                vt.recycle();
                vt = null;
                return true;
        }
        return true;
    }

    // ================================================================ widgets

    /** Two dots under the pages (notifications, controls); the active one stretches into a pill. */
    static final class Dots extends View {
        private float pos = 1f;
        private final Paint on = new Paint(Paint.ANTI_ALIAS_FLAG), off = new Paint(Paint.ANTI_ALIAS_FLAG);

        Dots(Context c) {
            super(c);
            on.setColor(Ui.dark ? 0xE6FFFFFF : 0xCC000000);
            off.setColor(Ui.dark ? 0x59FFFFFF : 0x40000000);
        }

        void setPosition(float p) {
            if (Math.abs(p - pos) < 0.001f) return;
            pos = p;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float h = getHeight(), r = h / 2f, gap = h * 1.6f, pill = h * 3.2f;
            float w0 = h + (pill - h) * (1f - pos), w1 = h + (pill - h) * pos;
            float x = (getWidth() - (w0 + gap + w1)) / 2f;
            on.setAlpha(Math.round(255 * (Ui.dark ? 0.9f : 0.8f)));
            c.drawRoundRect(x, 0, x + w0, h, r, r, pos < 0.5f ? on : off);
            x += w0 + gap;
            c.drawRoundRect(x, 0, x + w1, h, r, r, pos >= 0.5f ? on : off);
        }
    }

    /** A standalone tile: icon in a circle, a name and a state; fully lit when on. */
    static final class Item extends LinearLayout {
        final PanelIcons icon;
        private final TextView title, state;

        Item(Context c, int type, String name) {
            super(c);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(Ui.dpi(c, 10), 0, Ui.dpi(c, 12), 0);
            icon = new PanelIcons(c, type, Ui.FG);
            int p = Ui.dpi(c, 11);
            icon.setPadding(p, p, p, p);
            addView(icon, Ui.lp(Ui.dpi(c, 44), Ui.dpi(c, 44)));
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(VERTICAL);
            col.setPadding(Ui.dpi(c, 10), 0, 0, 0);
            title = Ui.text(c, name, 14.5f, Ui.FG, true);
            title.setTypeface(Ui.font(c, 600));
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            state = Ui.text(c, "", 12, Ui.FG2, false);
            state.setSingleLine(true);
            state.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(title);
            col.addView(state, Ui.lp(-2, -2, Ui.dpi(c, 3)));
            addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
            setClickable(true);
            Ui.pressable(this);
            set(false, "");
        }

        private Boolean shown;

        void set(boolean on, String s) {
            if (shown != null && shown == on) { setIfChanged(state, s); return; }
            shown = on;
            Context c = getContext();
            setBackground(Ui.round(on ? Ui.ACCENT : TILE, Ui.dp(c, 22)));
            icon.setBackground(Ui.round(on ? 0x33FFFFFF : ROUND, Ui.dp(c, 22)));
            icon.setColor(on ? Color.WHITE : Ui.FG);
            title.setTextColor(on ? Color.WHITE : Ui.FG);
            state.setText(s);
            state.setTextColor(on ? 0xD9FFFFFF : Ui.FG2);
        }
    }

    /** A round toggle with a caption underneath. */
    static final class Round extends LinearLayout {
        final PanelIcons icon;
        final String id; // stays the same when the label changes (Sound -> Vibrate)
        private final TextView label;

        Round(Context c, int type, String name) {
            super(c);
            id = name;
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            icon = new PanelIcons(c, type, Ui.FG);
            int p = Ui.dpi(c, 15);
            icon.setPadding(p, p, p, p);
            addView(icon, Ui.lp(Ui.dpi(c, 54), Ui.dpi(c, 54)));
            label = Ui.text(c, name, 11.5f, Ui.FG2, false);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);
            label.setLineSpacing(0, 1.05f);
            addView(label, Ui.lp(-2, -2, Ui.dpi(c, 7)));
            setClickable(true);
            Ui.pressable(this);
            setActive(false);
        }

        private Boolean shown;

        void setActive(boolean on) {
            if (shown != null && shown == on) return;
            shown = on;
            icon.setBackground(Ui.round(on ? Ui.ACCENT : ROUND, Ui.dp(getContext(), 27)));
            icon.setColor(on ? Color.WHITE : Ui.FG);
            label.setTextColor(on ? Ui.FG : Ui.FG2);
        }

        void setLabel(String s) { setIfChanged(label, s); }
    }

    /**
     * Thin progress line for the media card, in a taller touch area: drag along it to seek
     * (it thickens while held), released position is sent to the player.
     */
    static final class Progress extends View {
        interface Seek { void seekTo(float fraction); }

        Seek seek;
        private float f;
        private boolean held;
        private final float bar, barHeld;
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);

        Progress(Context c) {
            super(c);
            bar = Ui.dp(c, 3);
            barHeld = Ui.dp(c, 6);
            track.setColor(0x4DFFFFFF);
            fill.setColor(Color.WHITE);
        }

        void setFraction(float v) {
            if (held || Math.abs(v - f) < 0.0005f) return; // the finger wins while seeking
            f = v;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float h = held ? barHeld : bar, r = h / 2f, top = (getHeight() - h) / 2f;
            c.drawRoundRect(0, top, getWidth(), top + h, r, r, track);
            c.drawRoundRect(0, top, Math.max(h, getWidth() * f), top + h, r, r, fill);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (seek == null) return false;
            float v = Math.max(0f, Math.min(1f, e.getX() / Math.max(1, getWidth())));
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    held = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    f = v;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    f = v;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    held = false;
                    f = v;
                    invalidate();
                    seek.seekTo(v);
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    held = false;
                    invalidate();
                    return true;
            }
            return true;
        }
    }

    /**
     * The 4x4 grid of round toggles. Long-press one to lift it, drag it over another slot and the
     * rest shift to make room; letting go saves the order.
     */
    static final class RoundGrid extends LinearLayout {
        interface Saved { void onOrder(String ids); }

        Saved saved;
        private final java.util.List<Round> order = new java.util.ArrayList<>();
        private final LinearLayout[] rows = new LinearLayout[4];
        private Round dragged;
        private float lastX, lastY, grabX, grabY;

        RoundGrid(Context c, int gap) {
            super(c);
            setOrientation(VERTICAL);
            setClipChildren(false);
            setChildrenDrawingOrderEnabled(true); // the lifted button's row draws on top
            for (int i = 0; i < rows.length; i++) {
                rows[i] = new LinearLayout(c);
                rows[i].setClipChildren(false);
                addView(rows[i], Ui.lp(-1, -2, i == 0 ? 0 : gap));
            }
        }

        void setRounds(java.util.List<Round> rounds, String savedIds) {
            order.clear();
            for (String id : savedIds.split(",")) {
                for (Round r : rounds) if (r.id.equals(id) && !order.contains(r)) order.add(r);
            }
            for (Round r : rounds) if (!order.contains(r)) order.add(r); // new ones go at the end
            for (final Round r : order) r.setOnLongClickListener(v -> { lift(r); return true; });
            place();
        }

        private final java.util.Map<Round, float[]> before = new java.util.HashMap<>();

        private void place() {
            before.clear();
            for (Round r : order) {
                if (r == dragged || r.getParent() == null) continue;
                LinearLayout row = (LinearLayout) r.getParent();
                before.put(r, new float[]{row.getLeft() + r.getLeft() + r.getTranslationX(), row.getTop() + r.getTop() + r.getTranslationY()});
            }
            for (LinearLayout row : rows) row.removeAllViews();
            for (int i = 0; i < order.size(); i++) {
                rows[Math.min(rows.length - 1, i / 4)].addView(order.get(i), new LinearLayout.LayoutParams(0, -2, 1f));
            }
        }

        private void lift(Round r) {
            dragged = r;
            getParent().requestDisallowInterceptTouchEvent(true);
            r.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            LinearLayout row = (LinearLayout) r.getParent();
            grabX = lastX - (row.getLeft() + r.getLeft() + r.getWidth() / 2f);
            grabY = lastY - (row.getTop() + r.getTop() + r.getHeight() / 2f);
            r.animate().cancel();
            r.animate().scaleX(1.15f).scaleY(1.15f).alpha(1f).setDuration(120).start();
            invalidate();
        }

        private int slotAt(float x, float y) {
            int ri = rows.length - 1;
            for (int i = 0; i < rows.length; i++) if (y < rows[i].getBottom()) { ri = i; break; }
            float cell = rows[ri].getWidth() / 4f;
            int col = Math.max(0, Math.min(3, (int) ((x - rows[ri].getLeft()) / Math.max(1f, cell))));
            return Math.min(order.size() - 1, ri * 4 + col);
        }

        /** Keep the lifted button under the finger, wherever the layout has put its slot. */
        private void follow() {
            if (dragged == null || dragged.getParent() == null) return;
            LinearLayout row = (LinearLayout) dragged.getParent();
            dragged.setTranslationX(lastX - grabX - (row.getLeft() + dragged.getLeft() + dragged.getWidth() / 2f));
            dragged.setTranslationY(lastY - grabY - (row.getTop() + dragged.getTop() + dragged.getHeight() / 2f));
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            follow();
            if (before.isEmpty()) return;
            // each moved button starts where it was and glides into its new slot
            for (java.util.Map.Entry<Round, float[]> e : before.entrySet()) {
                Round x = e.getKey();
                LinearLayout row = (LinearLayout) x.getParent();
                if (row == null) continue;
                float dx = e.getValue()[0] - (row.getLeft() + x.getLeft()), dy = e.getValue()[1] - (row.getTop() + x.getTop());
                if (dx == 0 && dy == 0) continue;
                x.animate().cancel();
                x.setTranslationX(dx);
                x.setTranslationY(dy);
                x.animate().translationX(0f).translationY(0f).setDuration(200).setInterpolator(Ui.EMPHASIZED).start();
            }
            before.clear();
        }

        @Override protected int getChildDrawingOrder(int count, int i) {
            int top = dragged == null ? -1 : indexOfChild((View) dragged.getParent());
            if (top < 0) return i;
            if (i == count - 1) return top;
            return i >= top ? i + 1 : i;
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent e) {
            lastX = e.getX();
            lastY = e.getY();
            return dragged != null;
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (dragged == null) return false;
            lastX = e.getX();
            lastY = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE: {
                    // the press animation of the button was cancelled when we took over: stay lifted
                    if (dragged.getScaleX() < 1.15f) {
                        dragged.animate().cancel();
                        dragged.setScaleX(1.15f);
                        dragged.setScaleY(1.15f);
                        dragged.setAlpha(1f);
                    }
                    int to = slotAt(lastX, lastY), from = order.indexOf(dragged);
                    if (to != from) {
                        order.remove(dragged);
                        order.add(to, dragged);
                        place();
                        dragged.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    } else {
                        follow();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    final Round r = dragged;
                    dragged = null;
                    r.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(180)
                            .setInterpolator(Ui.EMPHASIZED).start();
                    invalidate();
                    StringBuilder ids = new StringBuilder();
                    for (Round x : order) ids.append(ids.length() == 0 ? "" : ",").append(x.id);
                    if (saved != null) saved.onOrder(ids.toString());
                    return true;
                }
            }
            return true;
        }
    }

    /**
     * A tall slider whose fill is exactly the value (0% empty, 100% full), percentage at the top and
     * icon at the bottom. Drag up or down, relative to where you started; tap to jump.
     */
    static final class VSlider extends FrameLayout {
        interface Change { void onChange(float value, boolean done); }

        Change listener;
        private float value;
        private float downY, startValue;
        private boolean moving;
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pct = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path clip = new android.graphics.Path();
        private final PanelIcons icon;
        private final float iconSize;

        VSlider(Context c, int type) {
            super(c);
            setWillNotDraw(false);
            track.setColor(TILE);
            fill.setColor(Ui.STRONG);
            pct.setTextAlign(Paint.Align.CENTER);
            pct.setTypeface(Ui.font(c, 600));
            pct.setTextSize(Ui.dp(c, 13));
            iconSize = Ui.dp(c, 52);
            icon = new PanelIcons(c, type, Ui.FG);
            int p = Ui.dpi(c, 15);
            icon.setPadding(p, p, p, p);
            LayoutParams ip = new LayoutParams(Math.round(iconSize), Math.round(iconSize), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            ip.bottomMargin = Ui.dpi(c, 4);
            addView(icon, ip);
        }

        void setValue(float v) {
            value = Math.max(0f, Math.min(1f, v));
            icon.setColor(value * getHeight() > iconSize * 0.6f ? Ui.ON_STRONG : Ui.FG);
            invalidate();
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            clip.reset();
            float r = Ui.dp(getContext(), 24);
            clip.addRoundRect(0, 0, w, h, r, r, android.graphics.Path.Direction.CW);
            setValue(value);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            int save = c.save();
            c.clipPath(clip);
            c.drawRect(0, 0, w, h, track);
            float top = h * (1f - value);
            c.drawRect(0, top, w, h, fill);
            c.restoreToCount(save);
            float base = Ui.dp(getContext(), 26);
            pct.setColor(top < base - pct.getTextSize() * 0.5f ? Ui.ON_STRONG : Ui.FG2);
            c.drawText(Math.round(value * 100) + "%", w / 2f, base, pct);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY = e.getY();
                    startValue = value;
                    moving = false;
                    getParent().requestDisallowInterceptTouchEvent(true); // vertical drags belong to the slider
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!moving && Math.abs(e.getY() - downY) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) moving = true;
                    if (moving) {
                        setValue(startValue - (e.getY() - downY) / Math.max(1, getHeight()));
                        if (listener != null) listener.onChange(value, false);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moving) setValue(1f - e.getY() / Math.max(1, getHeight())); // tap jumps to that level
                    if (listener != null) listener.onChange(value, true);
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    return true;
            }
            return true;
        }
    }
}
