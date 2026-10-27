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
        /** Press the phone's own Control Centre tile with this label, out of sight. */
        void pressTile(String label, java.util.function.Consumer<Boolean> done);
        void finishHide();
        int statusBarHeight();
    }

    private final Host host;
    private final LinearLayout notifButton, notifList;
    private final PanelIcons notifBell;
    private final TextView notifCount, notifEmpty, clearAll;
    private final ScrollView notifScroll;
    private boolean notifsDirty = true;
    private TextView historyButton;
    private LinearLayout historyList;
    private boolean historyOpen;
    /** Apps whose grouped notifications are expanded (reset each time the panel opens). */
    private final java.util.Set<String> expanded = new java.util.HashSet<>();
    static final int CONTROLS = 0, NOTIFS = 1, BT = 2;
    private int page = CONTROLS;
    private final ScrollView btScroll;
    /** The detail page (slot after the controls) shows Wi-Fi networks or Bluetooth devices. */
    static final int D_WIFI = 0, D_BT = 1, D_DATA = 2, D_HOTSPOT = 3, D_LOCATION = 4, D_SOUND = 5,
            D_DND = 6, D_BATTERY = 7, D_TORCH = 8;
    /** Which page it is (opened by long-pressing a control). */
    private int detail = D_BT;
    private TextView detailTitle, detailMore;
    private final FrameLayout pager;
    private final Dots dots;
    private boolean paging, fromEdge;
    private int pagingTo = -1;
    private ValueAnimator pageAnim;
    private final LinearLayout btList;
    private final Ui.Toggle btToggle;
    private long switchedAt; // the page's switch was just flipped: hold it while the phone catches up
    final boolean isDark = Ui.dark; // the palette this panel was built in
    final int builtAccent = Ui.ACCENT;
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
        clp.bottomMargin = Ui.dpi(c, 56); // a floating card, never running off the screen (set exactly below)
        addView(card, clp);
        // our window covers the real status bar, so the panel carries its own copy of it
        statusRow = new StatusRow(c);
        addView(statusRow, new LayoutParams(-1, h.statusBarHeight(), Gravity.TOP));

        // ---- header
        head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dpi(c, 22), 0, Ui.dpi(c, 14), Ui.dpi(c, 4));
        LinearLayout tcol = new LinearLayout(c);
        tcol.setOrientation(LinearLayout.VERTICAL);
        // the page's name and the date (time and battery are already in the status row above)
        time = Ui.text(c, "Controls", 24, Ui.FG, true);
        time.setTypeface(Ui.font(c, 700));
        date = Ui.text(c, "", 13, Ui.FG2, false);
        tcol.addView(time);
        tcol.addView(date, Ui.lp(-2, -2, Ui.dpi(c, 3)));
        head.addView(tcol, new LinearLayout.LayoutParams(0, -2, 1f));
        // notifications: a pill with the bell and how many there are; tap to switch pages
        notifButton = new LinearLayout(c);
        notifButton.setGravity(Gravity.CENTER_VERTICAL);
        notifButton.setPadding(Ui.dpi(c, 11), 0, Ui.dpi(c, 14), 0);
        notifButton.setBackground(Ui.glass(TILE, Ui.dp(c, 22)));
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
        // a way out to the phone's own panel, for this one time (the next pull opens ours again):
        // the notifications page leads to its notifications, the controls to its control centre
        TextView original = Ui.text(c, "Original", 13.5f, Ui.FG, true);
        original.setTypeface(Ui.font(c, 600));
        original.setGravity(Gravity.CENTER);
        original.setPadding(Ui.dpi(c, 16), 0, Ui.dpi(c, 16), 0);
        original.setBackground(Ui.glass(TILE, Ui.dp(c, 22)));
        original.setClickable(true);
        Ui.pressable(original);
        original.setOnClickListener(v -> {
            final int action = page == NOTIFS ? AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                    : AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS;
            closeThen(200, () -> host.globalAction(action));
        });
        LinearLayout.LayoutParams op = Ui.lp(-2, Ui.dpi(c, 44));
        op.rightMargin = Ui.dpi(c, 8);
        head.addView(original, op);
        head.addView(circleButton(c, PanelIcons.GEAR, () -> open(new Intent(Settings.ACTION_SETTINGS))));
        card.addView(head, Ui.lp(-1, -2));

        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_ALWAYS); // stretches at either end, short or long
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
        notifScroll.setOverScrollMode(OVER_SCROLL_ALWAYS); // stretches at either end, short or long
        LinearLayout npage = new LinearLayout(c);
        npage.setOrientation(LinearLayout.VERTICAL);
        npage.setPadding(pad, 0, pad, Ui.dpi(c, 4));
        LinearLayout nhead = new LinearLayout(c);
        nhead.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        // recently dismissed: a small, quiet link in the title row, not a bar in the list
        historyButton = Ui.text(c, "History", 13, Ui.FG3, false);
        historyButton.setPadding(Ui.dpi(c, 12), Ui.dpi(c, 8), Ui.dpi(c, 10), Ui.dpi(c, 8));
        historyButton.setClickable(true);
        Ui.pressable(historyButton);
        historyButton.setOnClickListener(v -> {
            historyOpen = !historyOpen;
            buildHistory();
            if (historyOpen) notifScroll.post(() -> notifScroll.smoothScrollTo(0, historyList.getTop()));
        });
        nhead.addView(historyButton);
        // Clear all lives at the bottom of the card (added below), within thumb's reach
        clearAll = Ui.text(c, "Clear all", 13.5f, Ui.FG, true);
        clearAll.setTypeface(Ui.font(c, 600));
        clearAll.setGravity(Gravity.CENTER);
        clearAll.setPadding(Ui.dpi(c, 22), 0, Ui.dpi(c, 22), 0);
        clearAll.setBackground(Ui.glass(TILE, Ui.dp(c, 20)));
        clearAll.setClickable(true);
        Ui.pressable(clearAll);
        clearAll.setOnClickListener(v -> clearAllNotifications());
        clearAll.setVisibility(GONE);
        npage.addView(nhead, Ui.lp(-1, -2));
        notifList = new LinearLayout(c);
        notifList.setOrientation(LinearLayout.VERTICAL);
        // a card swiped aside slides out past the list's margin instead of being cut square there
        notifList.setClipChildren(false);
        npage.setClipToPadding(false);
        npage.setClipChildren(false);
        npage.addView(notifList, Ui.lp(-1, -2, Ui.dpi(c, 4)));
        notifEmpty = Ui.text(c, "No notifications", 14, Ui.FG2, false);
        notifEmpty.setGravity(Gravity.CENTER);
        npage.addView(notifEmpty, Ui.lp(-1, Ui.dpi(c, 120)));
        // recently dismissed: a quiet button that opens the last day's dismissed notifications
        // (the history link lives in the title row above, quietly)
        historyList = new LinearLayout(c);
        historyList.setOrientation(LinearLayout.VERTICAL);
        npage.addView(historyList, Ui.lp(-1, -2, Ui.dpi(c, 8)));
        notifScroll.addView(npage);
        notifScroll.setVisibility(GONE);
        pager.addView(notifScroll, new LayoutParams(-1, -2));
        NotifService.panelListener = panelListener;

        // ---- the Bluetooth page
        btScroll = new ScrollView(c);
        btScroll.setVerticalScrollBarEnabled(false);
        btScroll.setOverScrollMode(OVER_SCROLL_ALWAYS); // stretches at either end, short or long
        LinearLayout bpage = new LinearLayout(c);
        bpage.setOrientation(LinearLayout.VERTICAL);
        bpage.setPadding(pad, 0, pad, Ui.dpi(c, 4));
        LinearLayout bhead = new LinearLayout(c);
        bhead.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = Ui.text(c, "\u2039", 26, Ui.FG, false);
        back.setGravity(Gravity.CENTER);
        back.setPadding(0, 0, 0, Ui.dpi(c, 3));
        back.setBackground(Ui.glass(TILE, Ui.dp(c, 18)));
        back.setClickable(true);
        Ui.pressable(back);
        back.setOnClickListener(v -> slideTo(CONTROLS));
        bhead.addView(back, Ui.lp(Ui.dpi(c, 36), Ui.dpi(c, 36)));
        TextView btitle = detailTitle = Ui.text(c, "Bluetooth", 17, Ui.FG, true);
        btitle.setTypeface(Ui.font(c, 650));
        btitle.setPadding(Ui.dpi(c, 12), 0, 0, 0);
        btitle.setVisibility(INVISIBLE); // the header already names the page
        bhead.addView(btitle, new LinearLayout.LayoutParams(0, -2, 1f));
        // a real switch: it slides when tapped and holds while the phone does it
        btToggle = new Ui.Toggle(c, true);
        btToggle.setListener(this::onDetailSwitch);
        LinearLayout.LayoutParams tgp = Ui.lp(-2, -2);
        tgp.rightMargin = Ui.dpi(c, 6);
        bhead.addView(btToggle, tgp);
        bpage.addView(bhead, Ui.lp(-1, -2));
        btList = new LinearLayout(c);
        btList.setOrientation(LinearLayout.VERTICAL);
        btList.setLayoutTransition(Ui.rows()); // rows fade in and out as it turns on and off
        bpage.addView(btList, Ui.lp(-1, -2, Ui.dpi(c, 12)));
        LinearLayout bfoot = new LinearLayout(c);
        detailMore = pill(c, "More Bluetooth settings", () -> open(detailIntent()));
        bfoot.addView(detailMore, new LinearLayout.LayoutParams(0, Ui.dpi(c, 44), 1f));
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
        // tap switches it right here; long-press opens our own page (networks / devices)
        wifi.setOnClickListener(v -> systemToggle("Wi-Fi", wifi, null));
        wifi.setOnLongClickListener(v -> { openDetail(D_WIFI); return true; });
        bluetooth.setOnClickListener(v -> systemToggle("Bluetooth", bluetooth, null));
        bluetooth.setOnLongClickListener(v -> { openDetail(D_BT); return true; });
        data.setOnClickListener(v -> systemToggle("Mobile data", data, new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)));
        torch.setOnClickListener(v -> safe(this::toggleTorch));
        data.setOnLongClickListener(v -> { openDetail(D_DATA); return true; });
        torch.setOnLongClickListener(v -> { openDetail(D_TORCH); return true; });

        // ---- media (half the width) beside the two sliders, all in one row
        LinearLayout mediaRow = new LinearLayout(c);
        FrameLayout media = new FrameLayout(c);
        mediaRow.addView(media, new LinearLayout.LayoutParams(0, -1, 2f));

        mediaFull = new FrameLayout(c);
        mediaFull.setBackground(Ui.glass(TILE, Ui.dp(c, 24)));
        mediaFull.setClipToOutline(true);
        mediaArt = new ImageView(c);
        mediaArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mediaFull.addView(mediaArt, new LayoutParams(-1, -1));
        View shade = new View(c);
        shade.setBackgroundColor(0x9E000000); // flat dim so the white text reads over any art
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
        // where the sound goes (this phone, earbuds, a speaker): Android's own output picker
        PanelIcons output = new PanelIcons(c, PanelIcons.SPEAKER, Color.WHITE);
        int outPad = Ui.dpi(c, 7);
        output.setPadding(outPad, outPad, outPad, outPad);
        output.setBackground(Ui.round(0x40FFFFFF, Ui.dp(c, 16)));
        output.setClickable(true);
        Ui.pressable(output);
        output.setOnClickListener(v -> pickOutput());
        LayoutParams olp = new LayoutParams(Ui.dpi(c, 32), Ui.dpi(c, 32), Gravity.TOP | Gravity.END);
        olp.topMargin = olp.rightMargin = Ui.dpi(c, 10);
        mediaFull.addView(output, olp);
        mediaTitle.setPadding(0, 0, Ui.dpi(c, 34), 0);
        mediaArtist.setPadding(0, 0, Ui.dpi(c, 34), 0);
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
        mediaEmpty.setBackground(Ui.glass(TILE, Ui.dp(c, 24)));
        mediaEmpty.setPadding(Ui.dpi(c, 14), 0, Ui.dpi(c, 14), 0);
        PanelIcons note = new PanelIcons(c, PanelIcons.PLAY, Ui.FG2);
        int np = Ui.dpi(c, 13);
        note.setPadding(np, np, np, np);
        note.setBackground(Ui.glass(ROUND, Ui.dp(c, 23)));
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
        brightness.listener = (v, d) -> safe(() -> {
            setBrightness(v, d);
            brightness.setLabel(null); // dragging means an exact level: auto is off
        });
        brightness.onLongPress = () -> safe(this::toggleAutoBrightness);
        volume.onLongPress = () -> openDetail(D_SOUND); // every volume, and ring / vibrate / silent
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
        grid.setBackground(Ui.glass(TILE, Ui.dp(c, 24)));
        grid.setPadding(Ui.dpi(c, 4), Ui.dpi(c, 14), Ui.dpi(c, 4), Ui.dpi(c, 12));
        final android.content.SharedPreferences prefs = c.getSharedPreferences("panel", Context.MODE_PRIVATE);
        grid.setRounds(java.util.Arrays.asList(hotspot, rotate, plane, location, dark, dnd, sound, eye,
                saver, screenshot, record, cast, calc, recorder, lock, power), prefs.getString("order", ""));
        grid.saved = ids -> prefs.edit().putString("order", ids).apply();
        // hold one and let go: its own page; hold and drag: move it
        grid.pages.put(hotspot, () -> openDetail(D_HOTSPOT));
        grid.pages.put(location, () -> openDetail(D_LOCATION));
        grid.pages.put(sound, () -> openDetail(D_SOUND));
        grid.pages.put(dnd, () -> openDetail(D_DND));
        grid.pages.put(saver, () -> openDetail(D_BATTERY));
        body.addView(grid, Ui.lp(-1, -2, Ui.dpi(c, 10)));

        // these switch in place by pressing the phone's own tile out of sight; long-press lifts
        // them to rearrange like the rest (the full settings are behind "Original")
        hotspot.setOnClickListener(v -> systemToggle("Hotspot", hotspot, new Intent(Settings.ACTION_WIRELESS_SETTINGS)));
        rotate.setOnClickListener(v -> safe(this::toggleRotation));
        plane.setOnClickListener(v -> systemToggle("Aeroplane mode", plane, new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)));
        location.setOnClickListener(v -> systemToggle("Location", location, new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        // Honor's dark-mode screen isn't open to other apps; its switch lives in Display settings
        dark.setOnClickListener(v -> systemToggle("Dark mode", dark, new Intent(Settings.ACTION_DISPLAY_SETTINGS)));
        dnd.setOnClickListener(v -> safe(this::toggleDnd));
        sound.setOnClickListener(v -> safe(this::cycleSound));
        eye.setOnClickListener(v -> systemToggle("Eye Comfort", eye, new Intent(Settings.ACTION_DISPLAY_SETTINGS)));
        saver.setOnClickListener(v -> systemToggle("Power Saving", saver, new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)));
        screenshot.setOnClickListener(v -> closeThen(380, () -> host.globalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)));
        record.setOnClickListener(v -> {
            if (Recorder.recording) { closeThen(0, () -> Recorder.toggle(getContext())); return; }
            // the phone's own recorder: no "start recording?" pop-up, and it records sound
            close();
            main.postDelayed(() -> host.pressTile("Screen Recorder", ok -> {
                if (!ok) Recorder.toggle(getContext());
            }), 320);
        });
        Recorder.onChange = recorderListener;
        cast.setOnClickListener(v -> open(new Intent(Settings.ACTION_CAST_SETTINGS)));
        calc.setOnClickListener(v -> openApp("com.google.android.calculator", "com.hihonor.calculator"));
        recorder.setOnClickListener(v -> openApp("com.hihonor.soundrecorder", "com.google.android.apps.recorder"));
        lock.setOnClickListener(v -> closeThen(250, () -> host.globalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)));
        power.setOnClickListener(v -> closeThen(200, () -> host.globalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)));

        // near the bottom edge of the screen, where the thumb is, whatever the length of the list
        int nav = 0;
        int navId = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (navId > 0) nav = getResources().getDimensionPixelSize(navId);
        // bottom of the screen, from the edge up: page dots, then Clear all, then the card
        dotsReserve = nav + Ui.dpi(c, 14 + 6 + 14);       // the card always stops above the dots
        clearReserve = dotsReserve + Ui.dpi(c, 40 + 12);  // and above Clear all when it shows
        LayoutParams cap = new LayoutParams(-2, Ui.dpi(c, 40), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cap.bottomMargin = dotsReserve;
        addView(clearAll, cap);

        // the page dots stay in one place near the bottom edge whatever the page's height
        dots = new Dots(c);
        LayoutParams dp = new LayoutParams(Ui.dpi(c, 60), Ui.dpi(c, 6), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dp.bottomMargin = nav + Ui.dpi(c, 14);
        addView(dots, dp);

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
        b.setBackground(Ui.glass(TILE, Ui.dp(c, 22)));
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
    private final StatusRow statusRow;

    private final Runnable statusTick = new Runnable() {
        @Override public void run() {
            statusRow.refresh();
            if (page == BT && detail != D_SOUND) safe(ControlPanel.this::rebuildDetail); // live numbers
            if (progress > 0f) main.postDelayed(this, 5000);
        }
    };

    void refresh(boolean notifications) {
        statusRow.refresh();
        main.removeCallbacks(statusTick);
        main.postDelayed(statusTick, 5000);
        final Context c = getContext();
        notifsDirty = true; // "5m ago" and friends are rebuilt fresh on every open
        expanded.clear();
        historyOpen = false;
        scroll.scrollTo(0, 0);
        showPage(notifications ? NOTIFS : CONTROLS);
        showNotifCount();
        // Each reading is isolated: this runs in the launcher's process, and one OEM quirk or
        // missing permission must never take the home screen down with it.
        safe(() -> {
            Calendar cal = Calendar.getInstance();
            setIfChanged(date, new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(cal.getTime()));
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
        safe(() -> {
            brightness.setValue(Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128) / 255f);
            brightness.setLabel(autoBrightness() ? "Auto" : null);
        });
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
        // Honor's power saving is its own mode (SmartModeStatus 4), not always Android's battery saver
        safe(() -> saver.setActive(((PowerManager) c.getSystemService(Context.POWER_SERVICE)).isPowerSaveMode()
                || Settings.System.getInt(c.getContentResolver(), "SmartModeStatus", 1) == 4));
        record.setActive(Recorder.recording || honorRecording());
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
            cameras.registerTorchCallback(torchCallback, main);
        } catch (Exception ignored) {}
    }

    private final CameraManager.TorchCallback torchCallback = new CameraManager.TorchCallback() {
        @Override public void onTorchModeChanged(String id, boolean on) {
            if (!id.equals(torchId)) return;
            torchOn = on;
            torch.set(on, on ? "On" : "Off");
        }
    };

    /**
     * The panel is being thrown away (rebuilt for a new theme or font size, or the service is
     * stopping): let go of everything registered with the system, so no old copy stays alive.
     */
    void destroy() {
        unbind();
        try { cameras.unregisterTorchCallback(torchCallback); } catch (Exception ignored) {}
        android.bluetooth.BluetoothAdapter a = btAdapter();
        for (java.util.Map.Entry<Integer, android.bluetooth.BluetoothProfile> e : btProfiles.entrySet()) {
            try { if (a != null) a.closeProfileProxy(e.getKey(), e.getValue()); } catch (Exception ignored) {}
        }
        btProfiles.clear();
        if (NotifService.panelListener == panelListener) NotifService.panelListener = null;
        if (Recorder.onChange == recorderListener) Recorder.onChange = null;
        main.removeCallbacksAndMessages(null);
    }

    private void toggleTorch() {
        if (torchId == null) return;
        try { cameras.setTorchMode(torchId, !torchOn); } catch (Exception ignored) {}
    }

    private void toggleDnd() {
        setDnd(!dndOn(), NotificationManager.INTERRUPTION_FILTER_PRIORITY);
    }

    /** Ring -> vibrate -> silent (silent needs Do-not-disturb access, otherwise ring <-> vibrate). */
    private void cycleSound() {
        AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        NotificationManager nm = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        int m = ringerMode();
        int next;
        if (m == AudioManager.RINGER_MODE_NORMAL) next = AudioManager.RINGER_MODE_VIBRATE;
        else if (m == AudioManager.RINGER_MODE_VIBRATE && nm.isNotificationPolicyAccessGranted()) next = AudioManager.RINGER_MODE_SILENT;
        else next = AudioManager.RINGER_MODE_NORMAL;
        am.setRingerMode(next);
        showSound();
    }

    /**
     * The sound mode you chose. Android reports "silent" while Do not disturb is on, whatever
     * was chosen; the setting underneath still holds the real choice.
     */
    private int ringerMode() {
        AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        if (!dndOn()) return am.getRingerMode(); // exact, and current the moment it's set
        return Settings.Global.getInt(getContext().getContentResolver(), "mode_ringer", am.getRingerMode());
    }

    private void showSound() {
        int m = ringerMode();
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

    private boolean autoBrightness() {
        return Settings.System.getInt(getContext().getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
    }

    /** Long-press the brightness slider: auto-brightness on/off (dragging sets an exact level). */
    private void toggleAutoBrightness() {
        Context c = getContext();
        if (!Settings.System.canWrite(c)) {
            open(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + c.getPackageName())));
            return;
        }
        boolean on = !autoBrightness();
        Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                on ? Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC : Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        brightness.setLabel(on ? "Auto" : null);
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
        replying = false;
        backdrop.setImageDrawable(null); // never show one app's blur over the next
        backdropBase.setVisibility(VISIBLE);
        main.removeCallbacks(tickProgress);
        if (controller != null) controller.unregisterCallback(mediaCallback);
        controller = null;
    }

    // ================================================================ bluetooth

    private final Runnable panelListener = this::onNotifsChanged;
    private final Runnable recorderListener = this::onRecorderChanged;

    private void onRecorderChanged() { record.setActive(Recorder.recording); }
    private final java.util.Set<String> btConnected = new java.util.HashSet<>();
    private final java.util.Map<Integer, android.bluetooth.BluetoothProfile> btProfiles = new java.util.HashMap<>();
    private boolean btProxies;

    /** Who's connected right now, asked fresh of each audio profile (devices come and go). */
    private void readBtConnected() {
        btConnected.clear();
        for (android.bluetooth.BluetoothProfile p : btProfiles.values()) {
            try { for (android.bluetooth.BluetoothDevice d : p.getConnectedDevices()) btConnected.add(d.getAddress()); }
            catch (Exception ignored) {}
        }
    }

    private android.bluetooth.BluetoothAdapter btAdapter() {
        android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        return bm == null ? null : bm.getAdapter();
    }

    private boolean btOn() {
        try { android.bluetooth.BluetoothAdapter a = btAdapter(); return a != null && a.isEnabled(); }
        catch (Exception e) { return false; }
    }

    /**
     * Switch something Android doesn't let apps switch (Wi-Fi, hotspot, aeroplane mode...): our
     * button flips at once, the phone's own Control Centre tile is pressed out of sight, and the
     * real state is read back. If the tile can't be found, the old settings screen opens instead.
     */
    private void systemToggle(final String label, final View button, final Intent fallback) {
        if (button instanceof Round) ((Round) button).flip();
        else if (button instanceof Item) ((Item) button).flip();
        if (button != null) button.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        final java.util.function.BooleanSupplier now = stateOf(label);
        // what you asked for is what the button shows now (the phone may still be mid-switch)
        final boolean want = button instanceof Round ? ((Round) button).showing()
                : button instanceof Item ? ((Item) button).showing() : now != null && !now.getAsBoolean();
        wants.put(label, want);
        checks.put(label, (checks.containsKey(label) ? checks.get(label) : 0) + 1);
        if (now != null) setHold(button, SystemClock.uptimeMillis() + 15_000); // until the check below settles it
        host.pressTile(label, ok -> {
            if (now == null) setHold(button, SystemClock.uptimeMillis() + HOLD_MS);
            // no error straight away: the result is checked, and pressed again if it didn't take
            if (now != null) checkSwitched(label, button, now, want, SystemClock.uptimeMillis(), false);
            else if (!ok) say(label + " didn't switch. Try again");
            // read the real state back as the system acts; a reading that still disagrees with the
            // tap is ignored until HOLD_MS, then the last one shows the truth (e.g. a failed switch)
            for (long t : new long[]{400, 900, 1600, HOLD_MS + 100}) main.postDelayed(this::refreshStates, t);
        });
    }

    /**
     * The phone's tile ignores a press while the radio is still settling (e.g. Bluetooth a moment
     * after it was turned on). Once it has settled the other way, press it once more.
     */
    /**
     * Make sure a switch ends where it was asked to. The phone can read "off" for a moment while
     * something is still turning on, and its tile ignores presses until it has settled, so the
     * answer only counts once it has stayed the same for a while. Wrong then: press once more.
     * Meanwhile the button keeps showing what was asked for.
     */
    private void checkSwitched(String label, View button, java.util.function.BooleanSupplier now, boolean want, long since, boolean retried) {
        Integer g = checks.get(label);
        int gen = g == null ? 1 : g + 1;
        checks.put(label, gen);
        watch(gen, label, button, now, want, since, retried, now.getAsBoolean(), SystemClock.uptimeMillis());
    }

    /** The newest check per control; an older one stops when it sees it's been replaced. */
    private final java.util.Map<String, Integer> checks = new java.util.HashMap<>();

    private void watch(final int gen, final String label, final View button, final java.util.function.BooleanSupplier now, final boolean want,
                       final long since, final boolean retried, final boolean last, final long lastChange) {
        main.postDelayed(() -> {
            if (!Boolean.valueOf(want).equals(wants.get(label))) return; // a newer tap took over
            if (!Integer.valueOf(gen).equals(checks.get(label))) return;  // or a newer check
            boolean v = now.getAsBoolean();
            long t = SystemClock.uptimeMillis(), changed = v != last ? t : lastChange;
            boolean settled = t - changed >= 1500 && !settling(label);
            if (!settled && t - since < 9000) {
                watch(gen, label, button, now, want, since, retried, v, changed);
                return;
            }
            if (v == want) { setHold(button, 0); refreshStates(); return; }
            if (retried) { // pressed twice and still not there: now it's worth a word
                say(label + " didn't switch. Try again");
                setHold(button, 0);
                refreshStates();
                return;
            }
            Diag.log("'" + label + "' didn't switch; pressing again");
            host.pressTile(label, ok -> checkSwitched(label, button, now, want, SystemClock.uptimeMillis(), true));
        }, 300);
    }

    /** Until then, readings that contradict the button are ignored (0: show the truth now). */
    private static void setHold(View b, long until) {
        if (b instanceof Round) ((Round) b).holdUntil = until;
        if (b instanceof Item) ((Item) b).holdUntil = until;
    }

    /** The last switch asked for, per control: an older check never undoes a newer tap. */
    private final java.util.Map<String, Boolean> wants = new java.util.HashMap<>();

    /** How to read back each control the phone's own tile switches. */
    private java.util.function.BooleanSupplier stateOf(String label) {
        final Context c = getContext();
        switch (label) {
            case "Wi-Fi": return this::wifiOn;
            case "Bluetooth": return this::btOn;
            case "Mobile data": return this::dataOn;
            case "Hotspot": return () -> hotspotOn((WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE));
            case "Location": return this::locationOn;
            case "Aeroplane mode": return () -> Settings.Global.getInt(c.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
            case "Power Saving": return this::saverOn;
            case "Eye Comfort": return () -> Settings.System.getInt(c.getContentResolver(), "eyes_protection_mode", 0) == 1;
            case "Dark mode": return () -> ((UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE)).getNightMode() == UiModeManager.MODE_NIGHT_YES;
            default: return null;
        }
    }

    /** Wi-Fi, Bluetooth or the hotspot is half-way through turning on or off. */
    private boolean settling(String label) {
        try {
            if ("Hotspot".equals(label)) {
                WifiManager wm = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                int st = (Integer) WifiManager.class.getMethod("getWifiApState").invoke(wm);
                return st == 10 || st == 12; // DISABLING, ENABLING
            }
            if (!"Wi-Fi".equals(label) && !"Bluetooth".equals(label)) return false;
            if ("Bluetooth".equals(label)) {
                int st = btAdapter().getState();
                return st == android.bluetooth.BluetoothAdapter.STATE_TURNING_ON || st == android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF;
            }
            int st = ((WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE)).getWifiState();
            return st == WifiManager.WIFI_STATE_ENABLING || st == WifiManager.WIFI_STATE_DISABLING;
        } catch (Exception e) { return false; }
    }

    /** The phone's own screen recorder is running (it shows an ongoing notification while it does). */
    private static boolean honorRecording() {
        for (android.service.notification.StatusBarNotification sb : NotifService.list)
            if ("com.hihonor.screenrecorder".equals(sb.getPackageName())) return true;
        return false;
    }

    private TextView message;

    /** A short message near the bottom of the panel (instead of opening anything). */
    void say(String text) {
        Context c = getContext();
        if (message == null) {
            message = Ui.text(c, "", 13.5f, Ui.FG, true);
            message.setGravity(Gravity.CENTER);
            message.setPadding(Ui.dpi(c, 18), Ui.dpi(c, 10), Ui.dpi(c, 18), Ui.dpi(c, 10));
            message.setBackground(Ui.glass(Ui.dark ? 0xF0303030 : 0xF0FFFFFF, Ui.dp(c, 20)));
            LayoutParams lp = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lp.bottomMargin = clearReserve + Ui.dpi(c, 8);
            addView(message, lp);
        }
        message.setText(text);
        message.animate().cancel();
        message.setAlpha(0f);
        message.setVisibility(VISIBLE);
        message.animate().alpha(1f).setDuration(160).start();
        main.removeCallbacks(hideMessage);
        main.postDelayed(hideMessage, 2600);
    }

    private final Runnable hideMessage = () -> {
        if (message != null) message.animate().alpha(0f).setDuration(200)
                .withEndAction(() -> message.setVisibility(GONE)).start();
    };

    /** Re-read every control's state without disturbing the page or scroll position. */
    static final long HOLD_MS = 2600;

    private void refreshStates() {
        if (progress <= 0f) return;
        int y = scroll.getScrollY();
        int was = page;
        refresh(was == NOTIFS);
        if (was == BT) showPage(BT);
        scroll.scrollTo(0, y);
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
                btProfiles.put(profile, proxy);
                // who's connected is only known now: let the first order use it (before any tap)
                if (btBusy == null && detail == D_BT) rowOrder.clear();
                if (page == BT && detail == D_BT) buildBt();
            }
            @Override public void onServiceDisconnected(int profile) { btProfiles.remove(profile); }
        };
        int[] profiles = {android.bluetooth.BluetoothProfile.A2DP, android.bluetooth.BluetoothProfile.HEADSET,
                android.bluetooth.BluetoothProfile.HEARING_AID, 22 /* LE_AUDIO */};
        for (int pr : profiles) try { a.getProfileProxy(getContext(), l, pr); } catch (Exception ignored) {}
    }

    /** Rows keep their places while a page is open (sorted once, when it opens). */
    private final List<String> rowOrder = new java.util.ArrayList<>();

    private void keepOrder(List<String> keys) {
        java.util.List<String> fresh = new java.util.ArrayList<>(keys);
        if (rowOrder.isEmpty()) { rowOrder.addAll(fresh); return; }
        for (String k : fresh) if (!rowOrder.contains(k)) rowOrder.add(k); // new ones at the end
        keys.clear();
        for (String k : rowOrder) if (fresh.contains(k)) keys.add(k);
    }

    private String rowsSig;

    /**
     * The rows already show exactly this: leave them (a rebuild makes the list blink). Otherwise
     * clear them, ready for the caller to add the new ones.
     */
    private boolean sameRows(String sig) {
        if (sig.equals(rowsSig) && btList.getChildCount() > 0) return true;
        rowsSig = sig;
        btList.removeAllViews();
        return false;
    }

    // ---------------------------------------------------------------- the detail page, any kind

    private void onDetailSwitch(int v) {
        switchedAt = SystemClock.uptimeMillis();
        String tile = null;
        View button = null;
        switch (detail) {
            case D_WIFI: tile = "Wi-Fi"; button = wifi; break;
            case D_BT: tile = "Bluetooth"; button = bluetooth; break;
            case D_DATA: tile = "Mobile data"; button = data; break;
            case D_HOTSPOT: tile = "Hotspot"; button = hotspot; break;
            case D_LOCATION: tile = "Location"; button = location; break;
            case D_BATTERY: tile = "Power Saving"; button = saver; break;
            case D_DND: safe(() -> setDnd(v == 1, NotificationManager.INTERRUPTION_FILTER_PRIORITY)); break;
            case D_TORCH: safe(this::toggleTorch); break;
        }
        if (tile == null) { main.postDelayed(this::rebuildDetail, 120); return; } // switched at once
        // the phone's own switch takes a moment: say so instead of showing a stale list
        rowsSig = null;
        btList.removeAllViews();
        btList.addView(btMessage(getContext(), v == 1 ? "Turning on…" : "Turning off…"));
        systemToggle(tile, button, null);
    }

    private void rebuildDetail() { if (page == BT && progress > 0f) buildDetail(); }

    /** Just flipped: readings that still show the old state are the phone catching up. */
    private boolean holdingSwitch() {
        return SystemClock.uptimeMillis() - switchedAt < HOLD_MS && detailOn() != btToggle.isOn();
    }

    private void openDetail(int kind) {
        if (detail != kind || page != BT) { rowOrder.clear(); rowsSig = null; allApps = false; btScroll.scrollTo(0, 0); }
        detail = kind;
        if (page == BT) buildDetail();
        slideTo(BT);
    }

    private String detailName() {
        switch (detail) {
            case D_WIFI: return "Wi-Fi";
            case D_DATA: return "Mobile data";
            case D_HOTSPOT: return "Hotspot";
            case D_LOCATION: return "Location";
            case D_SOUND: return "Sound";
            case D_DND: return "Do not disturb";
            case D_BATTERY: return "Battery";
            case D_TORCH: return "Torch";
            default: return "Bluetooth";
        }
    }

    /** The link at the bottom: the full settings screen for what the page can't do itself. */
    private String detailLink() {
        switch (detail) {
            case D_WIFI: return "More Wi-Fi settings";
            case D_DATA: return "SIM and data settings";
            case D_HOTSPOT: return "Hotspot name and password";
            case D_LOCATION: return "More location settings";
            case D_SOUND: return "Ringtones and more";
            case D_DND: return "Who can reach you";
            case D_BATTERY: return "Battery usage by app";
            case D_TORCH: return null;
            default: return "Pair a new device";
        }
    }

    private Intent detailIntent() {
        switch (detail) {
            case D_WIFI: return new Intent(Settings.ACTION_WIFI_SETTINGS);
            case D_DATA: return new Intent(Settings.ACTION_DATA_USAGE_SETTINGS);
            case D_HOTSPOT: return new Intent(Settings.ACTION_WIRELESS_SETTINGS);
            case D_LOCATION: return new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            case D_SOUND: return new Intent(Settings.ACTION_SOUND_SETTINGS);
            case D_DND: return new Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS);
            case D_BATTERY: return new Intent(Intent.ACTION_POWER_USAGE_SUMMARY);
            default: return new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
        }
    }

    private boolean detailOn() {
        switch (detail) {
            case D_WIFI: return wifiOn();
            case D_BT: return btOn();
            case D_DATA: return dataOn();
            case D_HOTSPOT: return hotspotOn((WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE));
            case D_LOCATION: return locationOn();
            case D_DND: return dndOn();
            case D_BATTERY: return saverOn();
            case D_TORCH: return torchOn;
            default: return false;
        }
    }

    private void buildDetail() {
        detailTitle.setText(detailName());
        String link = detailLink();
        detailMore.setVisibility(link == null ? GONE : VISIBLE);
        if (link != null) detailMore.setText(link);
        btToggle.setVisibility(detail == D_SOUND ? INVISIBLE : VISIBLE); // Sound has three choices, not on/off
        switch (detail) {
            case D_WIFI: buildWifi(); break;
            case D_DATA: buildData(); break;
            case D_HOTSPOT: buildHotspot(); break;
            case D_LOCATION: buildLocation(); break;
            case D_SOUND: buildSound(); break;
            case D_DND: buildDnd(); break;
            case D_BATTERY: buildBattery(); break;
            case D_TORCH: buildTorch(); break;
            default: buildBt();
        }
    }

    // ---- rows shared by the pages

    private void addRow(View row) {
        btList.addView(row, Ui.lp(-1, -2, btList.getChildCount() == 0 ? 0 : Ui.dpi(getContext(), 8)));
    }

    /** A row that only tells you something (not pressable). */
    private void info(int icon, String title, String sub, boolean lit) {
        View r = deviceRow(getContext(), icon, title, sub, lit);
        r.setOnTouchListener(null);
        r.setClickable(false);
        addRow(r);
    }

    /** One of a few choices: the chosen one is lit; tap another to pick it. */
    private void choice(int icon, String title, String sub, boolean chosen, Runnable pick) {
        View r = deviceRow(getContext(), icon, title, sub, chosen);
        r.setOnClickListener(v -> {
            if (chosen) return;
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            safe(pick);
            rowsSig = null;
            main.postDelayed(this::rebuildDetail, 60);
            main.postDelayed(this::rebuildDetail, 500); // and once the phone has settled
        });
        addRow(r);
    }

    /** A small heading between groups of rows. */
    private void heading(String text) {
        Context c = getContext();
        TextView t = Ui.text(c, text, 13, Ui.FG2, true);
        t.setPadding(Ui.dpi(c, 6), Ui.dpi(c, 10), 0, Ui.dpi(c, 2));
        addRow(t);
    }

    private String size(long bytes) { return android.text.format.Formatter.formatShortFileSize(getContext(), Math.max(0, bytes)); }

    private static final java.util.concurrent.ExecutorService pageWorker = java.util.concurrent.Executors.newSingleThreadExecutor();

    /** Work too slow for the main thread (data totals, an address): runs once, then the page refreshes. */
    private void slow(Runnable job) {
        pageWorker.execute(() -> {
            try { job.run(); } catch (Throwable ignored) {}
            main.post(this::rebuildDetail);
        });
    }

    // ---------------------------------------------------------------- Mobile data

    private boolean dataOn() { return Settings.Global.getInt(getContext().getContentResolver(), "mobile_data", 0) == 1; }

    /** Mobile data used today and this month, and shared by the hotspot today; null until counted. */
    private volatile long[] usage;
    private long usageAt;

    private boolean usageAllowed() {
        android.app.AppOpsManager ao = (android.app.AppOpsManager) getContext().getSystemService(Context.APP_OPS_SERVICE);
        return ao.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(),
                getContext().getPackageName()) == android.app.AppOpsManager.MODE_ALLOWED;
    }

    private void countUsage() {
        if (SystemClock.uptimeMillis() - usageAt < 30_000 || !usageAllowed()) return;
        usageAt = SystemClock.uptimeMillis();
        final Context c = getContext().getApplicationContext();
        slow(() -> {
            android.app.usage.NetworkStatsManager ns = (android.app.usage.NetworkStatsManager) c.getSystemService(Context.NETWORK_STATS_SERVICE);
            Calendar day = Calendar.getInstance();
            day.set(Calendar.HOUR_OF_DAY, 0); day.set(Calendar.MINUTE, 0); day.set(Calendar.SECOND, 0); day.set(Calendar.MILLISECOND, 0);
            Calendar month = (Calendar) day.clone();
            month.set(Calendar.DAY_OF_MONTH, 1);
            long now = System.currentTimeMillis() + 60_000;
            long today = 0, thisMonth = 0, shared = 0;
            try {
                android.app.usage.NetworkStats.Bucket b = ns.querySummaryForDevice(android.net.ConnectivityManager.TYPE_MOBILE, null, day.getTimeInMillis(), now);
                today = b.getRxBytes() + b.getTxBytes();
                b = ns.querySummaryForDevice(android.net.ConnectivityManager.TYPE_MOBILE, null, month.getTimeInMillis(), now);
                thisMonth = b.getRxBytes() + b.getTxBytes();
            } catch (Exception ignored) {}
            // what the hotspot passed on today: the "tethering" bucket, on mobile and on Wi-Fi
            for (int type : new int[]{android.net.ConnectivityManager.TYPE_MOBILE, android.net.ConnectivityManager.TYPE_WIFI}) {
                try (android.app.usage.NetworkStats s = ns.querySummary(type, null, day.getTimeInMillis(), now)) {
                    android.app.usage.NetworkStats.Bucket b = new android.app.usage.NetworkStats.Bucket();
                    while (s.hasNextBucket()) {
                        s.getNextBucket(b);
                        if (b.getUid() == android.app.usage.NetworkStats.Bucket.UID_TETHERING) shared += b.getRxBytes() + b.getTxBytes();
                    }
                } catch (Exception ignored) {}
            }
            usage = new long[]{today, thisMonth, shared};
        });
    }

    private static String networkType(int t) {
        switch (t) {
            case 20: return "5G"; // NETWORK_TYPE_NR
            case 13: case 18: return "4G";
            case 3: case 8: case 9: case 10: case 15: case 17: case 5: case 6: case 12: case 14: return "3G";
            case 1: case 2: case 4: case 7: case 11: case 16: return "2G";
            default: return null;
        }
    }

    private void buildData() {
        Context c = getContext();
        boolean on = dataOn();
        if (holdingSwitch()) return;
        btToggle.setOn(on, true);
        countUsage();
        android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
        String carrier = tm.getNetworkOperatorName();
        if (carrier == null || carrier.isEmpty()) carrier = "No SIM";
        String net = null;
        try { net = networkType(tm.getDataNetworkType()); } catch (SecurityException ignored) {}
        int level = -1;
        try { android.telephony.SignalStrength ss = tm.getSignalStrength(); if (ss != null) level = ss.getLevel(); } catch (Exception ignored) {}
        boolean roaming = tm.isNetworkRoaming();
        // every SIM, the data one first
        java.util.List<String[]> sims = new java.util.ArrayList<>();
        try {
            android.telephony.SubscriptionManager sm = (android.telephony.SubscriptionManager) c.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            int dataSub = android.telephony.SubscriptionManager.getDefaultDataSubscriptionId();
            List<android.telephony.SubscriptionInfo> subs = sm.getActiveSubscriptionInfoList();
            if (subs != null) for (android.telephony.SubscriptionInfo s : subs) {
                String name = String.valueOf(s.getCarrierName() != null ? s.getCarrierName() : s.getDisplayName());
                sims.add(new String[]{"SIM " + (s.getSimSlotIndex() + 1) + "  ·  " + name, s.getSubscriptionId() == dataSub ? "1" : "0"});
            }
        } catch (SecurityException ignored) {}
        long[] u = usage;
        StringBuilder sig = new StringBuilder("d:").append(on).append(carrier).append(net).append(level).append(roaming);
        for (String[] s : sims) sig.append(s[0]).append(s[1]);
        if (u != null) sig.append(u[0]).append(',').append(u[1]);
        if (sameRows(sig.toString())) return;
        String bars = level < 0 ? null : new String[]{"no signal", "weak signal", "fair signal", "good signal", "strong signal"}[Math.min(4, level)];
        String sub = !on ? "Mobile data is off" : (net == null ? "Connected" : net) + (bars == null ? "" : "  ·  " + bars);
        info(PanelIcons.DATA, carrier, sub, on);
        if (roaming) info(PanelIcons.PLANE, "Roaming", "You're on another network; data may cost more", false);
        if (sims.size() > 1) {
            heading("SIMs");
            for (String[] s : sims) info(PanelIcons.DATA, s[0], s[1].equals("1") ? "Used for mobile data" : "Calls and texts", s[1].equals("1"));
        }
        heading("Data used");
        if (u != null) {
            info(PanelIcons.DATA, size(u[0]), "Today", false);
            info(PanelIcons.DATA, size(u[1]), "This month, since the 1st", false);
        } else if (!usageAllowed()) {
            choice(PanelIcons.DATA, "See how much data you've used", "Give Fast Launcher usage access", false,
                    () -> open(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        } else {
            addRow(btMessage(c, "Counting…"));
        }
    }

    // ---------------------------------------------------------------- Hotspot

    private void buildHotspot() {
        Context c = getContext();
        boolean on = detailOn();
        if (holdingSwitch()) return;
        btToggle.setOn(on, true);
        countUsage();
        boolean mobile = dataOn();
        long[] u = usage;
        String sig = "h:" + on + mobile + (u == null ? "" : u[2]);
        if (sameRows(sig)) return;
        info(PanelIcons.HOTSPOT, on ? "Hotspot is on" : "Hotspot is off",
                on ? "Other devices can join and use your connection" : "Switch it on to share your connection", on);
        if (on && !mobile && !wifiOn())
            info(PanelIcons.DATA, "No connection to share", "Mobile data is off, so devices that join won't get internet", false);
        else info(PanelIcons.DATA, "Sharing mobile data", mobile ? "Mobile data is on" : "Mobile data is off", mobile);
        if (u != null) info(PanelIcons.HOTSPOT, size(u[2]), "Shared with other devices today", false);
    }

    // ---------------------------------------------------------------- Location

    private boolean locationOn() {
        LocationManager lm = (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
        return lm != null && lm.isLocationEnabled();
    }

    private volatile String place, placeSub;
    private volatile java.util.List<String[]> locationApps; // label, how
    private long placeAt, appsAt;
    private boolean allApps;

    private void findPlace() {
        if (SystemClock.uptimeMillis() - placeAt < 60_000) return;
        placeAt = SystemClock.uptimeMillis();
        final Context c = getContext().getApplicationContext();
        slow(() -> {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            android.location.Location best = null;
            for (String p : lm.getProviders(true)) {
                try {
                    android.location.Location l = lm.getLastKnownLocation(p);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                } catch (SecurityException ignored) {}
            }
            if (best == null) { place = null; return; }
            String name = null;
            try {
                List<android.location.Address> a = new android.location.Geocoder(c).getFromLocation(best.getLatitude(), best.getLongitude(), 1);
                if (a != null && !a.isEmpty()) {
                    android.location.Address ad = a.get(0);
                    String area = ad.getSubLocality() != null ? ad.getSubLocality() : ad.getThoroughfare();
                    String city = ad.getLocality() != null ? ad.getLocality() : ad.getAdminArea();
                    name = area != null && city != null ? area + ", " + city : city != null ? city : ad.getAddressLine(0);
                }
            } catch (Exception ignored) {}
            if (name == null) name = String.format(Locale.US, "%.4f, %.4f", best.getLatitude(), best.getLongitude());
            long mins = Math.max(0, (System.currentTimeMillis() - best.getTime()) / 60_000);
            String ago = mins < 1 ? "just now" : mins < 60 ? mins + " min ago" : (mins / 60) + " h ago";
            placeSub = "Within about " + Math.round(best.getAccuracy()) + " m  ·  " + ago;
            place = name;
        });
    }

    private void findLocationApps() {
        if (SystemClock.uptimeMillis() - appsAt < 60_000) return;
        appsAt = SystemClock.uptimeMillis();
        final Context c = getContext().getApplicationContext();
        slow(() -> {
            android.content.pm.PackageManager pm = c.getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            java.util.List<String[]> out = new java.util.ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (!seen.add(pkg) || pkg.equals(c.getPackageName())) continue;
                boolean fine = pm.checkPermission(android.Manifest.permission.ACCESS_FINE_LOCATION, pkg) == android.content.pm.PackageManager.PERMISSION_GRANTED;
                boolean coarse = fine || pm.checkPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION, pkg) == android.content.pm.PackageManager.PERMISSION_GRANTED;
                if (!coarse) continue;
                boolean always = pm.checkPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION, pkg) == android.content.pm.PackageManager.PERMISSION_GRANTED;
                out.add(new String[]{String.valueOf(ri.loadLabel(pm)), (fine ? "Precise" : "Approximate") + (always ? "  ·  all the time" : "  ·  while in use"), pkg});
            }
            java.util.Collections.sort(out, (a, b) -> a[0].compareToIgnoreCase(b[0]));
            locationApps = out;
        });
    }

    private void buildLocation() {
        Context c = getContext();
        boolean on = locationOn();
        if (holdingSwitch()) return;
        btToggle.setOn(on, true);
        if (on) findPlace();
        findLocationApps();
        java.util.List<String[]> apps = locationApps;
        String sig = "l:" + on + place + placeSub + (apps == null ? -1 : apps.size()) + allApps;
        if (sameRows(sig)) return;
        if (!on) info(PanelIcons.LOCATION, "Location is off", "Apps and maps can't tell where you are", false);
        else if (place != null) info(PanelIcons.LOCATION, place, placeSub, true);
        else info(PanelIcons.LOCATION, "Location is on", "Finding where you are…", true);
        if (apps == null) return;
        heading(apps.size() + (apps.size() == 1 ? " app can use your location" : " apps can use your location"));
        int shown = 0;
        for (String[] a : apps) {
            if (!allApps && shown == 6) break;
            shown++;
            addRow(appRow(c, a[2], a[0], a[1]));
        }
        if (!allApps && apps.size() > 6) {
            TextView more = pill(c, "Show all " + apps.size(), () -> { allApps = true; rowsSig = null; rebuildDetail(); });
            btList.addView(more, Ui.lp(-1, Ui.dpi(c, 44), Ui.dpi(c, 8)));
        }
    }

    /** A row with an app's own icon (loaded off the main thread). */
    private View appRow(Context c, String pkg, String name, String sub) {
        View r = deviceRow(c, PanelIcons.LOCATION, name, sub, false);
        r.setOnTouchListener(null);
        r.setClickable(false);
        final ImageView icon = new ImageView(c);
        int p = Ui.dpi(c, 4);
        icon.setPadding(p, p, p, p);
        LinearLayout row = (LinearLayout) r;
        row.removeViewAt(0);
        row.addView(icon, 0, Ui.lp(Ui.dpi(c, 44), Ui.dpi(c, 44)));
        final Context app = c.getApplicationContext();
        pageWorker.execute(() -> {
            try {
                final android.graphics.drawable.Drawable d = app.getPackageManager().getApplicationIcon(pkg);
                main.post(() -> icon.setImageDrawable(d));
            } catch (Exception ignored) {}
        });
        return r;
    }

    // ---------------------------------------------------------------- Sound

    private void buildSound() {
        Context c = getContext();
        final AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        final NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        int m = ringerMode();
        boolean quiet = dndOn();
        if (sameRows("s:" + m + quiet)) return;
        if (quiet) info(PanelIcons.DND, "Do not disturb is on", "Calls and notifications stay quiet until it's off", true);
        choice(PanelIcons.BELL, "Ring", "Calls and notifications make a sound", m == AudioManager.RINGER_MODE_NORMAL,
                () -> { am.setRingerMode(AudioManager.RINGER_MODE_NORMAL); showSound(); });
        choice(PanelIcons.VIBRATE, "Vibrate", "The phone vibrates instead", m == AudioManager.RINGER_MODE_VIBRATE,
                () -> { am.setRingerMode(AudioManager.RINGER_MODE_VIBRATE); showSound(); });
        choice(PanelIcons.BELL_OFF, "Silent", "No sound and no vibration", m == AudioManager.RINGER_MODE_SILENT, () -> {
            if (!nm.isNotificationPolicyAccessGranted()) { open(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)); return; }
            am.setRingerMode(AudioManager.RINGER_MODE_SILENT);
            showSound();
        });
        heading("Volume");
        volumeRow(am, AudioManager.STREAM_MUSIC, "Media");
        volumeRow(am, AudioManager.STREAM_RING, "Ringtone");
        volumeRow(am, AudioManager.STREAM_NOTIFICATION, "Notifications");
        volumeRow(am, AudioManager.STREAM_ALARM, "Alarms");
        volumeRow(am, AudioManager.STREAM_VOICE_CALL, "Calls");
    }

    private void volumeRow(final AudioManager am, final int stream, String name) {
        Context c = getContext();
        final int max = Math.max(1, am.getStreamMaxVolume(stream));
        final HSlider s = new HSlider(c, name);
        s.setValue(am.getStreamVolume(stream) / (float) max);
        s.listener = (v, done) -> {
            int to = Math.round(v * max);
            if (to == am.getStreamVolume(stream) && !done) return;
            try {
                // the media and alarm volumes play a short sound when let go, so you hear the level
                am.setStreamVolume(stream, to, done && stream != AudioManager.STREAM_VOICE_CALL ? AudioManager.FLAG_PLAY_SOUND : 0);
            } catch (SecurityException e) {
                say("Do not disturb is keeping this one down");
            }
            if (stream == AudioManager.STREAM_MUSIC) volume.setValue(to / (float) max);
            if (done) { s.setValue(am.getStreamVolume(stream) / (float) max); showSound(); }
        };
        btList.addView(s, Ui.lp(-1, Ui.dpi(c, 52), Ui.dpi(c, 8)));
    }

    /**
     * A wide slider filling with the accent colour, its name on the left and the level on the
     * right (both turn white where the fill passes under them). Drag from anywhere; tap to jump.
     */
    static final class HSlider extends View {
        interface Change { void onChange(float value, boolean done); }

        Change listener;
        private final String name;
        private float value, downX, startValue;
        private boolean moved;
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
                text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF r = new android.graphics.RectF();
        private final android.graphics.Path clip = new android.graphics.Path();

        HSlider(Context c, String name) {
            super(c);
            this.name = name;
            track.setColor(TILE);
            fill.setColor(Ui.ACCENT);
            text.setTextSize(Ui.dp(c, 14));
            text.setTypeface(Ui.font(c, 600));
        }

        void setValue(float v) {
            v = Math.max(0f, Math.min(1f, v));
            if (Math.abs(v - value) < 0.001f) return;
            value = v;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), rad = Ui.dp(getContext(), 22);
            clip.reset();
            r.set(0, 0, w, h);
            clip.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawRect(r, track);
            float fx = w * value;
            c.drawRect(0, 0, fx, h, fill);
            float pad = Ui.dp(getContext(), 18), base = h / 2f - (text.descent() + text.ascent()) / 2f;
            String pct = Math.round(value * 100) + "%";
            float px = w - pad - text.measureText(pct);
            // drawn twice: dark over the track, white over the fill
            for (int pass = 0; pass < 2; pass++) {
                c.save();
                if (pass == 0) c.clipRect(fx, 0, w, h); else c.clipRect(0, 0, fx, h);
                text.setColor(pass == 0 ? Ui.FG : Color.WHITE);
                c.drawText(name, pad, base, text);
                text.setColor(pass == 0 ? Ui.FG2 : 0xD9FFFFFF);
                c.drawText(pct, px, base, text);
                c.restore();
            }
            c.restore();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true); // sideways drags belong to the slider
                    downX = e.getX();
                    startValue = value;
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!moved && Math.abs(e.getX() - downX) < ViewConfiguration.get(getContext()).getScaledTouchSlop()) return true;
                    moved = true;
                    setValue(startValue + (e.getX() - downX) / Math.max(1, getWidth()));
                    if (listener != null) listener.onChange(value, false);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved) setValue(e.getX() / Math.max(1, getWidth())); // a tap jumps there
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    if (listener != null) listener.onChange(value, true);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (listener != null) listener.onChange(value, true);
                    return true;
            }
            return true;
        }
    }

    // ---------------------------------------------------------------- Do not disturb

    private boolean dndOn() {
        NotificationManager nm = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        return nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL;
    }

    /** Do not disturb on (in the given mode) or off; switching it off also drops any timer. */
    private void setDnd(boolean on, int filter) {
        Context c = getContext();
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (!nm.isNotificationPolicyAccessGranted()) { open(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)); return; }
        nm.setInterruptionFilter(on ? filter : NotificationManager.INTERRUPTION_FILTER_ALL);
        if (!on) DndEnd.set(c, 0);
        dnd.setActive(on);
    }

    private void buildDnd() {
        Context c = getContext();
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        boolean on = dndOn();
        if (holdingSwitch()) return;
        btToggle.setOn(on, true);
        int f = nm.getCurrentInterruptionFilter();
        long until = on ? DndEnd.until(c) : 0;
        if (sameRows("n:" + on + f + until)) return;
        heading("Mode");
        choice(PanelIcons.DND, "Priority only", "Alarms, and calls and messages from the people you choose",
                on && f == NotificationManager.INTERRUPTION_FILTER_PRIORITY, () -> setDnd(true, NotificationManager.INTERRUPTION_FILTER_PRIORITY));
        choice(PanelIcons.BELL, "Alarms only", "Only alarms make a sound",
                on && f == NotificationManager.INTERRUPTION_FILTER_ALARMS, () -> setDnd(true, NotificationManager.INTERRUPTION_FILTER_ALARMS));
        choice(PanelIcons.BELL_OFF, "Total silence", "Nothing makes a sound, not even alarms",
                on && f == NotificationManager.INTERRUPTION_FILTER_NONE, () -> setDnd(true, NotificationManager.INTERRUPTION_FILTER_NONE));
        heading("For how long");
        final int mode = on ? f : NotificationManager.INTERRUPTION_FILTER_PRIORITY;
        java.text.DateFormat tf = android.text.format.DateFormat.getTimeFormat(c);
        choice(PanelIcons.DND, "Until you turn it off", "No timer", on && until == 0, () -> { setDnd(true, mode); DndEnd.set(getContext(), 0); });
        for (int hours : new int[]{1, 2}) {
            long end = System.currentTimeMillis() + hours * 3_600_000L;
            boolean chosen = on && until > 0 && hoursChosen == hours;
            choice(PanelIcons.DND, hours == 1 ? "For 1 hour" : "For " + hours + " hours", "Until " + tf.format(new java.util.Date(end)), chosen,
                    () -> { setDnd(true, mode); hoursChosen = hours; DndEnd.set(getContext(), System.currentTimeMillis() + hours * 3_600_000L); });
        }
        Calendar morning = Calendar.getInstance();
        morning.add(Calendar.DAY_OF_YEAR, morning.get(Calendar.HOUR_OF_DAY) < 7 ? 0 : 1);
        morning.set(Calendar.HOUR_OF_DAY, 7); morning.set(Calendar.MINUTE, 0); morning.set(Calendar.SECOND, 0);
        final long am7 = morning.getTimeInMillis();
        choice(PanelIcons.DND, "Until morning", "Until " + tf.format(new java.util.Date(am7)), on && until == am7 && hoursChosen == 0,
                () -> { setDnd(true, mode); hoursChosen = 0; DndEnd.set(getContext(), am7); });
        if (on && until > 0) info(PanelIcons.DND, "Ends at " + tf.format(new java.util.Date(until)), "Then everything rings again", false);
    }

    private int hoursChosen;

    /** Switches Do not disturb off when its timer runs out (the alarm survives the panel). */
    public static final class DndEnd extends android.content.BroadcastReceiver {
        static long until(Context c) { return c.getSharedPreferences("panel", Context.MODE_PRIVATE).getLong("dnd_until", 0); }

        /** at: when to switch it off (0: no timer). */
        static void set(Context c, long at) {
            c.getSharedPreferences("panel", Context.MODE_PRIVATE).edit().putLong("dnd_until", at).apply();
            android.app.AlarmManager alarms = (android.app.AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(c, 7, new Intent(c, DndEnd.class),
                    android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            alarms.cancel(pi);
            if (at <= 0) return;
            // on time to the minute when exact alarms are allowed (granted over adb); otherwise
            // the closest Android allows apps: within ten minutes
            if (android.os.Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms())
                alarms.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi);
            else alarms.setWindow(android.app.AlarmManager.RTC_WAKEUP, at, 10 * 60_000L, pi);
        }

        @Override public void onReceive(Context c, Intent i) {
            long at = until(c);
            if (at == 0 || at > System.currentTimeMillis() + 60_000) return; // cancelled, or moved later
            c.getSharedPreferences("panel", Context.MODE_PRIVATE).edit().putLong("dnd_until", 0).apply();
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm.isNotificationPolicyAccessGranted()) nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
        }
    }

    // ---------------------------------------------------------------- Battery

    private boolean saverOn() {
        Context c = getContext();
        return ((PowerManager) c.getSystemService(Context.POWER_SERVICE)).isPowerSaveMode()
                || Settings.System.getInt(c.getContentResolver(), "SmartModeStatus", 1) == 4;
    }

    private void buildBattery() {
        Context c = getContext();
        boolean on = saverOn();
        if (holdingSwitch()) return;
        btToggle.setOn(on, true);
        Intent b = c.getApplicationContext().registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return;
        int level = Math.round(100f * b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, 0) / Math.max(1, b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)));
        int status = b.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, 0);
        int plug = b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0);
        float temp = b.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
        int health = b.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, 0);
        boolean charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING;
        boolean full = status == android.os.BatteryManager.BATTERY_STATUS_FULL || level >= 100;
        long left = -1;
        try { left = ((android.os.BatteryManager) c.getSystemService(Context.BATTERY_SERVICE)).computeChargeTimeRemaining(); } catch (Exception ignored) {}
        String sig = "y:" + on + level + status + plug + Math.round(temp) + health + (left / 60_000);
        if (sameRows(sig)) return;
        String how = plug == android.os.BatteryManager.BATTERY_PLUGGED_AC ? "charger"
                : plug == android.os.BatteryManager.BATTERY_PLUGGED_USB ? "USB"
                : plug == android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS ? "wireless charger" : null;
        String sub;
        if (full && how != null) sub = "Full  ·  on " + how;
        else if (charging) sub = "Charging" + (how != null ? " on " + how : "") + (left > 0 ? "  ·  full in " + duration(left) : "");
        else if (how != null) sub = "Plugged in, not charging";
        else sub = "On battery";
        info(PanelIcons.BATTERY, level + "%", sub, charging || full);
        if (on) info(PanelIcons.BATTERY, "Power saving is on", "Background activity and effects are reduced to last longer", true);
        heading("Health");
        String hs = health == android.os.BatteryManager.BATTERY_HEALTH_GOOD ? "Good"
                : health == android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT ? "Too hot"
                : health == android.os.BatteryManager.BATTERY_HEALTH_COLD ? "Too cold"
                : health == android.os.BatteryManager.BATTERY_HEALTH_DEAD ? "Worn out" : "Normal";
        info(PanelIcons.BATTERY, hs, "Battery condition", false);
        info(PanelIcons.SUN, String.format(Locale.getDefault(), "%.1f°C", temp),
                temp >= 40 ? "Warm: let it cool before heavy use" : "Temperature", temp >= 40);
    }

    private static String duration(long ms) {
        long m = Math.max(1, ms / 60_000);
        return m < 60 ? m + " min" : (m / 60) + " h " + (m % 60) + " min";
    }

    // ---------------------------------------------------------------- Torch

    private int torchMax() {
        if (torchId == null || android.os.Build.VERSION.SDK_INT < 33) return 1;
        try {
            Integer m = cameras.getCameraCharacteristics(torchId).get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL);
            return m == null ? 1 : m;
        } catch (Exception e) { return 1; }
    }

    private void buildTorch() {
        Context c = getContext();
        boolean on = torchOn;
        if (holdingSwitch()) return;
        btToggle.setOn(on, true);
        final int max = torchMax();
        if (sameRows("t:" + on + max)) return;
        info(PanelIcons.TORCH, on ? "Torch is on" : "Torch is off", on ? "Using the back camera's light" : "Switch it on above", on);
        if (max <= 1) { addRow(btMessage(c, "This phone's torch has one brightness")); return; }
        heading("Brightness");
        final HSlider s = new HSlider(c, "Brightness");
        int lv = max;
        try { if (on && android.os.Build.VERSION.SDK_INT >= 33) lv = cameras.getTorchStrengthLevel(torchId); } catch (Exception ignored) {}
        s.setValue(lv / (float) max);
        s.listener = (v, done) -> {
            if (android.os.Build.VERSION.SDK_INT < 33) return;
            int to = Math.max(1, Math.round(v * max)); // 0 would be off: the switch does that
            try { cameras.turnOnTorchWithStrengthLevel(torchId, to); } catch (Exception ignored) {}
        };
        btList.addView(s, Ui.lp(-1, Ui.dpi(c, 52), Ui.dpi(c, 8)));
    }

    // ---------------------------------------------------------------- Wi-Fi

    private String connecting; // the network being joined right now
    private long lastScan;

    private boolean wifiOn() {
        WifiManager wm = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        return wm != null && wm.isWifiEnabled();
    }

    private String connectedSsid() {
        try {
            WifiManager wm = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            String s = wm.getConnectionInfo().getSSID();
            if (s == null || s.contains("unknown ssid")) return null;
            return s.replace("\"", "");
        } catch (Exception e) { return null; }
    }

    /** Nearby networks, strongest first, the one you're on at the top; tap one to join it. */
    private void buildWifi() {
        Context c = getContext();
        boolean on = wifiOn();
        if (holdingSwitch()) return;
        btToggle.setOn(on, true);
        if (!on) { if (!sameRows("w:off")) btList.addView(btMessage(c, "Wi-Fi is off")); return; }
        WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (SystemClock.uptimeMillis() - lastScan > 20_000) {
            lastScan = SystemClock.uptimeMillis();
            try { wm.startScan(); } catch (Exception ignored) {}
            main.postDelayed(() -> { if (page == BT && detail == D_WIFI && progress > 0f) buildWifi(); }, 3500);
        }
        List<android.net.wifi.ScanResult> results;
        try { results = wm.getScanResults(); } catch (SecurityException e) { results = null; }
        if (results == null) {
            if (!sameRows("w:perm")) btList.addView(btMessage(c, "Allow precise location for Fast Launcher to list networks here"));
            return;
        }
        final String current = connectedSsid();
        java.util.LinkedHashMap<String, android.net.wifi.ScanResult> best = new java.util.LinkedHashMap<>();
        for (android.net.wifi.ScanResult sr : results) {
            String name = sr.SSID;
            if (name == null || name.trim().isEmpty()) continue;
            android.net.wifi.ScanResult had = best.get(name);
            if (had == null || sr.level > had.level) best.put(name, sr);
        }
        List<android.net.wifi.ScanResult> list = new java.util.ArrayList<>(best.values());
        java.util.Collections.sort(list, (a, b) -> {
            boolean ca = a.SSID.equals(current), cb = b.SSID.equals(current);
            if (ca != cb) return ca ? -1 : 1;
            return Integer.compare(b.level, a.level);
        });
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (android.net.wifi.ScanResult sr : list) keys.add(sr.SSID);
        keepOrder(keys);
        java.util.Map<String, android.net.wifi.ScanResult> byName = new java.util.HashMap<>();
        for (android.net.wifi.ScanResult sr : list) byName.put(sr.SSID, sr);
        list.clear();
        for (String k : keys) list.add(byName.get(k));
        StringBuilder sig = new StringBuilder("w:").append(current).append(connecting);
        for (android.net.wifi.ScanResult sr : list) sig.append('|').append(sr.SSID).append(WifiManager.calculateSignalLevel(sr.level, 4));
        if (sameRows(sig.toString())) return;
        if (list.isEmpty()) { btList.addView(btMessage(c, "Looking for networks\u2026")); return; }
        int shown = 0;
        for (final android.net.wifi.ScanResult sr : list) {
            if (shown++ == 12) break;
            final boolean here = sr.SSID.equals(current), joining = sr.SSID.equals(connecting);
            boolean locked = sr.capabilities != null && (sr.capabilities.contains("WPA") || sr.capabilities.contains("WEP") || sr.capabilities.contains("SAE"));
            int bars = WifiManager.calculateSignalLevel(sr.level, 4);
            String sub = here ? "Connected" : joining ? "Connecting\u2026"
                    : (locked ? "Secured" : "Open") + "  \u00b7  " + new String[]{"weak", "fair", "good", "strong"}[Math.max(0, Math.min(3, bars))];
            View row = deviceRow(c, PanelIcons.WIFI, sr.SSID, sub, here || joining);
            row.setOnClickListener(v -> {
                if (here || connecting != null) return;
                joinWifi(sr.SSID);
            });
            btList.addView(row, Ui.lp(-1, -2, btList.getChildCount() == 0 ? 0 : Ui.dpi(c, 8)));
        }
    }

    /** Join a network by tapping it in the system's Wi-Fi list, out of sight. */
    private void joinWifi(final String ssid) {
        connecting = ssid;
        buildWifi();
        PanelService.pressInSettings(new Intent(Settings.ACTION_WIFI_SETTINGS), ssid, result -> {
            if (result == PanelService.NEEDS_YOU) {
                connecting = null;
                close(); // it wants a password: the system's own box is up, let the user type it
                return;
            }
            if (result == PanelService.NOT_FOUND) say(ssid + " is out of range");
            // give it a few seconds to connect, then show the truth
            main.postDelayed(() -> { connecting = null; if (page == BT && detail == D_WIFI) buildWifi(); refreshStates(); }, 4000);
        });
    }

    /** A row on the Wi-Fi or Bluetooth page: icon, name, a line under it; lit when active. */
    private View deviceRow(Context c, int iconType, String name, String sub, boolean active) {
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(Ui.glass(TILE, Ui.dp(c, 22)));
        int p = Ui.dpi(c, 10);
        r.setPadding(p, p, Ui.dpi(c, 16), p);
        PanelIcons icon = new PanelIcons(c, iconType, active ? Color.WHITE : Ui.FG);
        int ip = Ui.dpi(c, 11);
        icon.setPadding(ip, ip, ip, ip);
        icon.setBackground(Ui.glass(active ? Ui.ACCENT : ROUND, Ui.dp(c, 22)));
        r.addView(icon, Ui.lp(Ui.dpi(c, 44), Ui.dpi(c, 44)));
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dpi(c, 12), 0, 0, 0);
        TextView t = Ui.text(c, name, 14.5f, Ui.FG, true);
        t.setTypeface(Ui.font(c, 600));
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(t);
        col.addView(Ui.text(c, sub, 12, active ? Ui.ACCENT : Ui.FG2, false), Ui.lp(-2, -2, Ui.dpi(c, 2)));
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        r.setClickable(true);
        Ui.pressable(r);
        return r;
    }

    // ---------------------------------------------------------------- Bluetooth

    private String btBusy; // the device being connected/disconnected right now

    private void buildBt() {
        Context c = getContext();
        boolean on = btOn();
        if (holdingSwitch()) return;
        btToggle.setOn(on, true);
        if (!on) { if (!sameRows("b:off")) btList.addView(btMessage(c, "Bluetooth is off")); return; }
        java.util.List<android.bluetooth.BluetoothDevice> devices = new java.util.ArrayList<>();
        try {
            devices.addAll(btAdapter().getBondedDevices());
        } catch (SecurityException e) {
            if (!sameRows("b:perm")) btList.addView(btMessage(c, "Allow \u201CNearby devices\u201D for Fast Launcher to list them here"));
            return;
        }
        watchBtProfiles();
        readBtConnected();
        java.util.Collections.sort(devices, (x, y) -> {
            boolean cx = btConnected.contains(x.getAddress()), cy = btConnected.contains(y.getAddress());
            if (cx != cy) return cx ? -1 : 1;
            return String.valueOf(btName(x)).compareToIgnoreCase(String.valueOf(btName(y)));
        });
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (android.bluetooth.BluetoothDevice d : devices) keys.add(d.getAddress());
        keepOrder(keys);
        java.util.Map<String, android.bluetooth.BluetoothDevice> byAddr = new java.util.HashMap<>();
        for (android.bluetooth.BluetoothDevice d : devices) byAddr.put(d.getAddress(), d);
        devices.clear();
        for (String k : keys) devices.add(byAddr.get(k));
        StringBuilder sig = new StringBuilder("b:").append(btBusy);
        for (android.bluetooth.BluetoothDevice d : devices) sig.append('|').append(d.getAddress()).append(btConnected.contains(d.getAddress()));
        if (sameRows(sig.toString())) return;
        if (devices.isEmpty()) { btList.addView(btMessage(c, "No paired devices yet")); return; }
        for (android.bluetooth.BluetoothDevice d : devices) {
            boolean connected = btConnected.contains(d.getAddress());
            LinearLayout r = new LinearLayout(c);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setBackground(Ui.glass(TILE, Ui.dp(c, 22)));
            int p = Ui.dpi(c, 10);
            r.setPadding(p, p, Ui.dpi(c, 16), p);
            PanelIcons icon = new PanelIcons(c, btAudio(d) ? PanelIcons.SPEAKER : PanelIcons.BLUETOOTH, connected ? Color.WHITE : Ui.FG);
            int ip = Ui.dpi(c, 11);
            icon.setPadding(ip, ip, ip, ip);
            icon.setBackground(Ui.glass(connected ? Ui.ACCENT : ROUND, Ui.dp(c, 22)));
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
            // apps may not connect devices themselves: tap it in the system's Bluetooth list, out of sight
            final String dn = btName(d);
            if (dn.equals(btBusy)) { ((TextView) col.getChildAt(1)).setText(connected ? "Disconnecting\u2026" : "Connecting\u2026"); }
            r.setOnClickListener(v -> {
                if (btBusy != null) return;
                btBusy = dn;
                buildBt();
                PanelService.pressInSettings(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS), dn, "OK", res -> {
                    if (res == PanelService.NEEDS_YOU) { btBusy = null; close(); return; }
                    if (res == PanelService.NOT_FOUND) say(dn + " isn't nearby");
                    main.postDelayed(() -> { btBusy = null; if (page == BT && detail == D_BT) buildBt(); }, 3500);
                });
            });
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
        t.setBackground(Ui.glass(TILE, Ui.dp(c, 22)));
        t.setClickable(true);
        Ui.pressable(t);
        t.setOnClickListener(v -> r.run());
        return t;
    }

    // ================================================================ notifications

    private final java.util.Map<String, CharSequence> appNames = new java.util.HashMap<>();
    private final java.util.Map<String, android.graphics.drawable.Drawable> appIcons = new java.util.HashMap<>();

    /** True while a reply is being typed: the list waits, so the text isn't wiped by a refresh. */
    private boolean replying;

    private void onNotifsChanged() {
        notifsDirty = true;
        showNotifCount();
        if (page == NOTIFS && !replying) buildNotifs();
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
        if (p == BT) buildDetail();
        if (pageAnim != null) pageAnim.cancel();
        for (ScrollView v : new ScrollView[]{scroll, notifScroll, btScroll}) v.setTranslationX(0f);
        if (p != page) {
            notifScroll.scrollTo(0, 0);
            btScroll.scrollTo(0, 0);
        }
        page = p;
        resetPageLooks();
        setIfChanged(time, p == NOTIFS ? "Notifications" : p == BT ? detailName() : "Controls");
        scroll.setVisibility(p == CONTROLS ? VISIBLE : GONE);
        notifScroll.setVisibility(p == NOTIFS ? VISIBLE : GONE);
        btScroll.setVisibility(p == BT ? VISIBLE : GONE);
        dots.setPosition(p == NOTIFS ? 0f : 1f);
        updateClearAll();
    }

    private boolean hasNotifs;
    private int dotsReserve;

    /**
     * Clear all: the cards that can go sweep off one after another, then everything clearable is
     * dismissed, and the panel closes (ongoing ones like USB mode remain for next time).
     */
    private void clearAllNotifications() {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        int n = notifList.getChildCount(), delay = 0;
        float w = notifList.getWidth();
        for (int i = 0; i < n; i++) {
            View v = notifList.getChildAt(i);
            if (!clearable(v)) continue;
            v.animate().translationX(w).alpha(0f).setStartDelay(delay).setDuration(200)
                    .setInterpolator(Ui.EMPHASIZED).start();
            delay += 35;
        }
        main.postDelayed(NotifService::dismissAll, delay + 180);
        main.postDelayed(this::close, delay + 260); // all cleared: the panel goes too
    }

    /** A card (or a group of them) that Clear all removes: not an ongoing one. */
    private static boolean clearable(View v) {
        if (v instanceof NotifRow) return ((NotifRow) v).dismissable;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) if (clearable(g.getChildAt(i))) return true;
        }
        return false;
    }
    private int clearReserve;
    private float clearFade = 1f; // fades while the notifications page slides away

    /** Clear all: on the notifications page when there's something to clear, else out of the way. */
    private void updateClearAll() {
        boolean on = page == NOTIFS && hasNotifs;
        int vis = on ? VISIBLE : GONE;
        if (clearAll.getVisibility() != vis) clearAll.setVisibility(vis);
        clearFade = 1f;
        clearAll.setAlpha(progress * clearFade);
        // the card (and its list) stops above the button, so nothing hides behind it
        LayoutParams lp = (LayoutParams) card.getLayoutParams();
        int bottom = on ? clearReserve : dotsReserve;
        if (lp.bottomMargin != bottom) { lp.bottomMargin = bottom; card.setLayoutParams(lp); }
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
            if (from == NOTIFS) { clearFade = 1f - f; clearAll.setAlpha(progress * clearFade); } // fades as the notifications slide away
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
        NotifRow.open = null;
        notifList.removeAllViews();
        List<android.service.notification.StatusBarNotification> all = NotifService.list;
        // ongoing ones stay single; the rest are gathered per app, in order of each app's newest
        java.util.LinkedHashMap<String, List<android.service.notification.StatusBarNotification>> byApp = new java.util.LinkedHashMap<>();
        List<Object> order = new java.util.ArrayList<>();
        for (int i = 0; i < Math.min(all.size(), 60); i++) {
            android.service.notification.StatusBarNotification sb = all.get(i);
            if ((sb.getNotification().flags & android.app.Notification.FLAG_ONGOING_EVENT) != 0) { order.add(sb); continue; }
            List<android.service.notification.StatusBarNotification> g = byApp.get(sb.getPackageName());
            if (g == null) {
                g = new java.util.ArrayList<>();
                byApp.put(sb.getPackageName(), g);
                order.add(g);
            }
            g.add(sb);
        }
        for (Object o : order) {
            View v;
            if (o instanceof android.service.notification.StatusBarNotification) v = notifRow((android.service.notification.StatusBarNotification) o, false);
            else {
                @SuppressWarnings("unchecked") List<android.service.notification.StatusBarNotification> g = (List<android.service.notification.StatusBarNotification>) o;
                v = g.size() == 1 ? notifRow(g.get(0), false) : groupCard(g);
            }
            if (v != null) notifList.addView(v, Ui.lp(-1, -2, notifList.getChildCount() == 0 ? 0 : Ui.dpi(getContext(), 8)));
        }
        boolean any = notifList.getChildCount() > 0;
        notifEmpty.setVisibility(any ? GONE : VISIBLE);
        // Clear all only when there's something it can clear (ongoing ones like USB mode stay)
        boolean clearableAny = false;
        for (int i = 0; i < notifList.getChildCount(); i++) if (clearable(notifList.getChildAt(i))) clearableAny = true;
        hasNotifs = clearableAny;
        updateClearAll();
        buildHistory();
    }

    /**
     * Several notifications from one app: one card showing the newest, with "N more" to expand
     * the rest in place and Clear to dismiss the whole app's lot.
     */
    private View groupCard(final List<android.service.notification.StatusBarNotification> g) {
        final Context c = getContext();
        final String pkg = g.get(0).getPackageName();
        final boolean open = expanded.contains(pkg);
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.glass(TILE, Ui.dp(c, 22)));
        int shown = open ? g.size() : 1;
        for (int i = 0; i < shown; i++) {
            View row = notifRow(g.get(i), true);
            if (row == null) continue;
            if (card.getChildCount() > 0) {
                View line = new View(c);
                line.setBackgroundColor(Ui.dark ? 0x14FFFFFF : 0x14000000);
                LinearLayout.LayoutParams lp = Ui.lp(-1, Math.max(1, Ui.dpi(c, 1)));
                lp.leftMargin = Ui.dpi(c, 66);
                lp.rightMargin = Ui.dpi(c, 14);
                card.addView(line, lp);
            }
            card.addView(row, Ui.lp(-1, -2));
        }
        LinearLayout bar = new LinearLayout(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dpi(c, 66), 0, Ui.dpi(c, 8), Ui.dpi(c, 8));
        TextView more = Ui.text(c, open ? "Show less" : (g.size() - 1) + " more from " + appName(pkg), 13, Ui.ACCENT, true);
        more.setTypeface(Ui.font(c, 600));
        more.setSingleLine(true);
        more.setEllipsize(android.text.TextUtils.TruncateAt.END);
        more.setPadding(0, Ui.dpi(c, 8), 0, Ui.dpi(c, 8));
        more.setClickable(true);
        Ui.pressable(more);
        more.setOnClickListener(v -> {
            if (!expanded.remove(pkg)) expanded.add(pkg);
            buildNotifs();
        });
        bar.addView(more, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView clear = Ui.text(c, "Clear", 13, Ui.FG2, true);
        clear.setGravity(Gravity.CENTER);
        clear.setPadding(Ui.dpi(c, 14), Ui.dpi(c, 8), Ui.dpi(c, 14), Ui.dpi(c, 8));
        clear.setClickable(true);
        Ui.pressable(clear);
        clear.setOnClickListener(v -> {
            for (android.service.notification.StatusBarNotification sb : g) {
                if ((sb.getNotification().flags & android.app.Notification.FLAG_NO_CLEAR) == 0) NotifService.dismiss(sb.getKey());
            }
            expanded.remove(pkg);
        });
        bar.addView(clear);
        card.addView(bar, Ui.lp(-1, -2));
        return card;
    }

    /** "Recently dismissed (n)": the last day's dismissed notifications, tap one to open it. */
    private void buildHistory() {
        final Context c = getContext();
        List<NotifService.Gone> h = NotifService.history(c);
        historyButton.setVisibility(h.isEmpty() ? GONE : VISIBLE);
        ((View) historyButton.getParent()).setVisibility(h.isEmpty() ? GONE : VISIBLE);
        historyButton.setText(historyOpen ? "Hide history" : "History");
        historyButton.setTextColor(historyOpen ? Ui.ACCENT : Ui.FG3);
        historyList.removeAllViews();
        if (!historyOpen || h.isEmpty()) return;
        for (final NotifService.Gone g : h) {
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(Ui.glass(TILE, Ui.dp(c, 20)));
            int p = Ui.dpi(c, 12);
            row.setPadding(p, Ui.dpi(c, 10), p, Ui.dpi(c, 10));
            row.setAlpha(0.8f);
            ImageView icon = new ImageView(c);
            icon.setImageDrawable(appIcon(g.pkg));
            row.addView(icon, Ui.lp(Ui.dpi(c, 32), Ui.dpi(c, 32)));
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(Ui.dpi(c, 12), 0, 0, 0);
            TextView meta = Ui.text(c, appName(g.pkg) + "  \u00b7  " + ago(g.posted), 11, Ui.FG2, false);
            meta.setSingleLine(true);
            col.addView(meta);
            String line = g.title.isEmpty() ? g.text : g.text.isEmpty() ? g.title : g.title + ": " + g.text;
            TextView t = Ui.text(c, line.trim(), 13, Ui.FG, false);
            t.setMaxLines(2);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(t, Ui.lp(-1, -2, Ui.dpi(c, 2)));
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
            row.setClickable(true);
            Ui.pressable(row);
            row.setOnClickListener(v -> { if (send(g.open)) close(); else openApp(g.pkg); });
            historyList.addView(row, Ui.lp(-1, -2, historyList.getChildCount() == 0 ? 0 : Ui.dpi(c, 6)));
        }
        TextView clear = Ui.text(c, "Clear history", 12.5f, Ui.FG2, false);
        clear.setGravity(Gravity.CENTER);
        clear.setPadding(0, Ui.dpi(c, 10), 0, Ui.dpi(c, 4));
        clear.setClickable(true);
        clear.setOnClickListener(v -> { NotifService.clearHistory(getContext()); historyOpen = false; buildHistory(); });
        historyList.addView(clear, Ui.lp(-1, -2));
    }

    private View notifRow(final android.service.notification.StatusBarNotification s) { return notifRow(s, false); }

    private View notifRow(final android.service.notification.StatusBarNotification s, boolean inGroup) {
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
        if (!inGroup) row.setBackground(Ui.glass(TILE, Ui.dp(c, 22))); // in a group the card draws it
        int p = Ui.dpi(c, 14);
        row.setPadding(p, p, p, p);

        // the sender's picture when there is one (a chat contact), otherwise the app's icon
        ImageView icon = new ImageView(c);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        android.graphics.drawable.Drawable d = null;
        try { if (n.getLargeIcon() != null) d = n.getLargeIcon().loadDrawable(c); } catch (Exception ignored) {}
        if (d != null) {
            icon.setBackground(Ui.glass(ROUND, Ui.dp(c, 20)));
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
        // a download or upload: its progress, as the system shade would show it
        if (x != null && (x.getInt(android.app.Notification.EXTRA_PROGRESS_MAX, 0) > 0
                || x.getBoolean(android.app.Notification.EXTRA_PROGRESS_INDETERMINATE, false))) {
            android.widget.ProgressBar pb = new android.widget.ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
            int max = x.getInt(android.app.Notification.EXTRA_PROGRESS_MAX, 0);
            pb.setIndeterminate(max <= 0);
            if (max > 0) { pb.setMax(max); pb.setProgress(x.getInt(android.app.Notification.EXTRA_PROGRESS, 0)); }
            pb.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
            col.addView(pb, Ui.lp(-1, Ui.dpi(c, 6), Ui.dpi(c, 8)));
        }
        View actions = actionsRow(c, s, col);
        if (actions != null) col.addView(actions, Ui.lp(-1, -2, Ui.dpi(c, 8)));
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        // ongoing ones (USB mode, charging, a download) stay put, like in the system shade
        row.dismissable = (n.flags & (android.app.Notification.FLAG_NO_CLEAR | android.app.Notification.FLAG_ONGOING_EVENT)) == 0;
        FrameLayout holder = new FrameLayout(c);
        holder.setTag(HOLDER);
        holder.setClipChildren(false);
        View acts = swipeActions(c, s, row);
        holder.addView(acts, new LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        holder.addView(row, new LayoutParams(-1, -2));
        row.actions = acts;
        return holder;
    }

    /**
     * The notification's own buttons (Reply, Mark as read, Call back...), like the system shade.
     * A button that takes text (Reply) opens a reply field right in the card.
     */
    private View actionsRow(final Context c, final android.service.notification.StatusBarNotification s, final LinearLayout col) {
        android.app.Notification.Action[] all = s.getNotification().actions;
        if (all == null || all.length == 0) return null;
        final LinearLayout bar = new LinearLayout(c);
        int shown = 0;
        for (final android.app.Notification.Action a : all) {
            if (a == null || a.title == null || a.actionIntent == null || shown == 3) continue;
            TextView b = Ui.text(c, a.title.toString(), 13, Ui.ACCENT, true);
            b.setTypeface(Ui.font(c, 600));
            b.setSingleLine(true);
            b.setEllipsize(android.text.TextUtils.TruncateAt.END);
            b.setGravity(Gravity.CENTER);
            b.setPadding(Ui.dpi(c, 14), 0, Ui.dpi(c, 14), 0);
            b.setBackground(Ui.glass(ROUND, Ui.dp(c, 16)));
            b.setClickable(true);
            Ui.pressable(b);
            final android.app.RemoteInput[] inputs = a.getRemoteInputs();
            b.setOnClickListener(v -> {
                if (inputs != null && inputs.length > 0) { replyField(c, s, a, inputs, col, bar); return; }
                if (!send(a.actionIntent)) { openApp(s.getPackageName()); return; }
                // buttons that just do something (mark read, mute...) leave the panel open;
                // anything that may open a screen closes it so the screen isn't hidden
                int sem = a.getSemanticAction();
                if (sem != android.app.Notification.Action.SEMANTIC_ACTION_MARK_AS_READ
                        && sem != android.app.Notification.Action.SEMANTIC_ACTION_MUTE
                        && sem != android.app.Notification.Action.SEMANTIC_ACTION_DELETE
                        && sem != android.app.Notification.Action.SEMANTIC_ACTION_ARCHIVE
                        && sem != android.app.Notification.Action.SEMANTIC_ACTION_MARK_AS_UNREAD) close();
            });
            LinearLayout.LayoutParams lp = Ui.lp(-2, Ui.dpi(c, 32));
            if (shown > 0) lp.leftMargin = Ui.dpi(c, 8);
            bar.addView(b, lp);
            shown++;
        }
        return shown == 0 ? null : bar;
    }

    /** Type a reply without leaving the panel; Send hands the text to the app, like the system shade. */
    private void replyField(Context c, final android.service.notification.StatusBarNotification s, final android.app.Notification.Action a,
                            final android.app.RemoteInput[] inputs, LinearLayout col, View bar) {
        bar.setVisibility(GONE);
        replying = true;
        final LinearLayout box = new LinearLayout(c);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(Ui.glass(ROUND, Ui.dp(c, 20)));
        final android.widget.EditText field = new android.widget.EditText(c);
        field.setHint(inputs[0].getLabel() != null ? inputs[0].getLabel() : "Reply");
        field.setTextColor(Ui.FG);
        field.setHintTextColor(Ui.FG2);
        field.setTextSize(14);
        field.setBackground(null);
        field.setPadding(Ui.dpi(c, 14), Ui.dpi(c, 8), Ui.dpi(c, 8), Ui.dpi(c, 8));
        field.setMaxLines(4);
        box.addView(field, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView sendBtn = Ui.text(c, "Send", 13.5f, Color.WHITE, true);
        sendBtn.setTypeface(Ui.font(c, 600));
        sendBtn.setGravity(Gravity.CENTER);
        sendBtn.setPadding(Ui.dpi(c, 16), 0, Ui.dpi(c, 16), 0);
        sendBtn.setBackground(Ui.round(Ui.ACCENT, Ui.dp(c, 16)));
        sendBtn.setClickable(true);
        Ui.pressable(sendBtn);
        LinearLayout.LayoutParams sp = Ui.lp(-2, Ui.dpi(c, 32));
        sp.rightMargin = Ui.dpi(c, 4);
        box.addView(sendBtn, sp);
        col.addView(box, Ui.lp(-1, -2, Ui.dpi(c, 8)));
        final android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
        field.requestFocus();
        field.post(() -> imm.showSoftInput(field, 0));
        sendBtn.setOnClickListener(v -> {
            CharSequence txt = field.getText();
            if (txt == null || txt.toString().trim().isEmpty()) return;
            Intent fill = new Intent();
            android.os.Bundle results = new android.os.Bundle();
            for (android.app.RemoteInput ri : inputs) results.putCharSequence(ri.getResultKey(), txt);
            android.app.RemoteInput.addResultsToIntent(inputs, fill, results);
            try {
                a.actionIntent.send(getContext(), 0, fill);
                field.setText("");
                field.setHint("Sent");
            } catch (Exception e) {
                Diag.log("reply: " + e);
                field.setHint("Couldn't send");
            }
            imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
            replying = false;
            if (notifsDirty) postDelayed(this::buildNotifs, 900); // let "Sent" show, then catch up
        });
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

    /** The system's media output dialog for the playing app (falls back to Bluetooth settings). */
    private void pickOutput() {
        final String pkg = controller != null ? controller.getPackageName() : null;
        closeThen(150, () -> {
            try {
                Intent i = new Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
                        .setComponent(new ComponentName("com.android.systemui", "com.android.systemui.media.dialog.MediaOutputDialogReceiver"));
                if (pkg != null) i.putExtra("package_name", pkg);
                getContext().sendBroadcast(i);
            } catch (Exception e) {
                Diag.log("media output: " + e);
                host.launch(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            }
        });
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
    /**
     * A notification card. Tap opens it; swipe right dismisses; swipe left slides it aside to
     * reveal its actions (Snooze, Settings) sitting behind it. One card open at a time.
     */
    static final class NotifRow extends LinearLayout {
        static boolean touching; // read by the panel: sideways swipes here belong to the card
        static NotifRow open;    // the card whose actions are showing
        boolean dismissable = true;
        View actions;            // the buttons behind the card (set by the builder)
        private final Runnable onDismiss, onOpen;
        private float downX, downY, base;
        private boolean swiping, cancelled;
        private VelocityTracker vt;
        private final int slop;

        NotifRow(Context c, Runnable dismiss, Runnable open) {
            super(c);
            onDismiss = dismiss;
            onOpen = open;
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
        }

        private float reveal() { return actions == null ? 0 : actions.getWidth() + Ui.dp(getContext(), 10); }

        /** Place the card at x (0 = closed, -reveal = actions showing) and fade the actions in with it. */
        private void at(float x) {
            setTranslationX(x);
            if (actions != null) {
                float f = reveal() <= 0 ? 0 : Math.min(1f, -x / reveal());
                actions.setAlpha(Math.max(0f, f));
                actions.setScaleX(0.85f + 0.15f * Math.max(0f, f));
                actions.setScaleY(0.85f + 0.15f * Math.max(0f, f));
            }
        }

        void close() {
            if (open == this) open = null;
            ValueAnimator va = ValueAnimator.ofFloat(getTranslationX(), 0f);
            va.setDuration(200);
            va.setInterpolator(Ui.EMPHASIZED);
            va.addUpdateListener(a -> at((Float) a.getAnimatedValue()));
            va.start();
        }

        private void showActions() {
            if (open != null && open != this) open.close();
            open = this;
            ValueAnimator va = ValueAnimator.ofFloat(getTranslationX(), -reveal());
            va.setDuration(220);
            va.setInterpolator(Ui.EMPHASIZED);
            va.addUpdateListener(a -> at((Float) a.getAnimatedValue()));
            va.start();
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }

        /** Slide away and run the action once out of sight (dismiss, snooze). */
        void leave(int direction, Runnable then) {
            if (open == this) open = null;
            if (actions != null) actions.animate().alpha(0f).setDuration(120).start();
            animate().translationX(direction * getWidth()).alpha(0f).setDuration(170).withEndAction(() -> {
                View self = getParent() instanceof FrameLayout && ((View) getParent()).getTag() == HOLDER ? (View) getParent() : this;
                ViewGroup p = (ViewGroup) self.getParent();
                if (p != null) {
                    android.transition.TransitionManager.beginDelayedTransition(p, Ui.motion());
                    p.removeView(self);
                }
                then.run();
            }).start();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (vt == null) vt = VelocityTracker.obtain();
            vt.addMovement(e);
            float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX(); downY = e.getRawY();
                    base = getTranslationX();
                    swiping = false; cancelled = false;
                    touching = true;
                    if (open != null && open != this) open.close();
                    animate().scaleX(0.98f).scaleY(0.98f).setDuration(90).start();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!swiping && !cancelled && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)
                            && (dx < 0 || base < 0 || dismissable)) {
                        swiping = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                        downX = e.getRawX() - Math.signum(dx) * slop;
                        dx = e.getRawX() - downX;
                    }
                    if (!swiping && Math.hypot(dx, dy) > slop) cancelled = true;
                    if (swiping) {
                        float x = base + dx;
                        float rv = reveal();
                        if (x < -rv) x = -rv + (x + rv) * 0.3f;          // past the actions: resist
                        if (x > 0 && (base < 0 || !dismissable)) x = x * 0.3f; // closing or can't dismiss
                        if (x > 0) { setTranslationX(x); setAlpha(1f - Math.min(1f, x / Math.max(1, getWidth()))); }
                        else { setAlpha(1f); at(x); }
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
                    boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                    if (swiping) {
                        float x = getTranslationX();
                        if (x > 0) {
                            boolean out = base >= 0 && dismissable && up
                                    && (x > getWidth() * 0.35f || vx > 1200);
                            if (out) leave(1, onDismiss);
                            else { animate().translationX(0f).alpha(1f).setDuration(180).start(); if (open == this) open = null; }
                        } else {
                            boolean show = up && actions != null && (x < -reveal() * 0.45f || vx < -900) && vx < 900;
                            if (show) showActions(); else close();
                        }
                    } else if (!cancelled && up) {
                        if (getTranslationX() < 0) { close(); return true; } // tap on an open card closes it
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        onOpen.run();
                    }
                    swiping = false;
                    return true;
            }
            return true;
        }
    }

    static final Object HOLDER = new Object();

    /** The buttons behind a card: Snooze (an hour) for normal ones, and the app's notification settings. */
    private View swipeActions(final Context c, final android.service.notification.StatusBarNotification s, final NotifRow row) {
        LinearLayout box = new LinearLayout(c);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(0, 0, Ui.dpi(c, 4), 0);
        boolean ongoing = (s.getNotification().flags & android.app.Notification.FLAG_ONGOING_EVENT) != 0;
        if (!ongoing) box.addView(actionButton(c, PanelIcons.BELL_OFF, "Snooze", () -> {
            row.leave(-1, () -> NotifService.snooze(s.getKey(), 60 * 60 * 1000L));
        }));
        box.addView(actionButton(c, PanelIcons.GEAR, "Settings", () -> {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, s.getPackageName());
            String ch = s.getNotification().getChannelId();
            if (ch != null) i = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, s.getPackageName()).putExtra(Settings.EXTRA_CHANNEL_ID, ch);
            open(i);
        }));
        box.setAlpha(0f);
        return box;
    }

    private View actionButton(Context c, int icon, String label, Runnable r) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        PanelIcons ic = new PanelIcons(c, icon, Ui.FG);
        int p = Ui.dpi(c, 12);
        ic.setPadding(p, p, p, p);
        ic.setBackground(Ui.glass(TILE, Ui.dp(c, 23)));
        b.addView(ic, Ui.lp(Ui.dpi(c, 46), Ui.dpi(c, 46)));
        TextView t = Ui.text(c, label, 11.5f, Ui.FG2, false);
        b.addView(t, Ui.lp(-2, -2, Ui.dpi(c, 5)));
        b.setClickable(true);
        Ui.pressable(b);
        b.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); r.run(); });
        LinearLayout.LayoutParams lp = Ui.lp(Ui.dpi(c, 66), -2);
        b.setLayoutParams(lp);
        return b;
    }

    // ================================================================ motion

    /** How far a finger travels for the panel to open fully (and to close it again). */
    private float cardTravel() {
        return Ui.dp(getContext(), 340);
    }

    void setProgress(float p) {
        progress = Math.max(0f, Math.min(1f, p));
        // the card stays where it lands: it fades in, drifting down a little and settling to full
        // size, like the system's own panel (sliding the whole card down showed its bottom first)
        card.setAlpha(Math.min(1f, progress * 1.6f));
        layCard();
        scrim.setAlpha(progress);
        statusRow.setAlpha(progress);
        // Clear all rises from the bottom as the panel comes down
        clearAll.setAlpha(progress * clearFade);
        clearAll.setTranslationY((1f - progress) * Ui.dp(getContext(), 48));
        dots.setAlpha(progress);
        dots.setTranslationY((1f - progress) * Ui.dp(getContext(), 24));
    }

    /** Pulled past where it stops: the card gives a little, stretching down (pixels). */
    private float stretch;
    private ValueAnimator stretchAnim;

    private void layCard() {
        float d = Ui.dp(getContext(), 1);
        float sc = 0.94f + 0.06f * progress;
        card.setPivotX(card.getWidth() / 2f);
        card.setPivotY(0f);
        card.setScaleX(sc);
        // half the give moves it, half stretches it, so its bottom edge follows the finger
        card.setScaleY(sc + (card.getHeight() > 0 ? stretch * 0.5f / card.getHeight() : 0f));
        card.setTranslationY(-(1f - progress) * 56 * d + stretch * 0.5f);
    }

    private void setStretch(float px) {
        if (stretchAnim != null) stretchAnim.cancel();
        stretch = px;
        layCard();
    }

    /** Pull `over` pixels past the end: rubber band. */
    private void overPull(float over) { setStretch(Ui.rubber(Math.max(0f, over), Ui.dp(getContext(), 110))); }

    /** Let go: the card springs back to its place. */
    private void releaseStretch() {
        if (stretch == 0f || (stretchAnim != null && stretchAnim.isRunning())) return;
        stretchAnim = ValueAnimator.ofFloat(stretch, 0f);
        stretchAnim.setDuration(Math.round(260 + stretch / Ui.dp(getContext(), 1)));
        stretchAnim.setInterpolator(Ui.EMPHASIZED);
        stretchAnim.addUpdateListener(a -> { stretch = (Float) a.getAnimatedValue(); layCard(); });
        stretchAnim.start();
    }

    /** Follow a pull from the status bar: dy is how far the finger has come down. */
    void dragFromTop(float dy) {
        if (anim != null) anim.cancel();
        boolean was = progress > 0f;
        setProgress(dy / cardTravel());
        overPull(dy - cardTravel());
        if (!was && progress > 0f && controller != null) tickProgress.run();
        armWatchdog();
    }

    /**
     * Safety net for a gesture that never ends (the touch stream cut off without a release: the
     * screen went off, a window changed mid-pull, the system took the touch): if nothing has moved
     * the panel for a moment and it's left half open, or a page change is left half done, finish it.
     */
    private final Runnable watchdog = this::checkStuck;

    private void checkStuck() {
        boolean animating = (anim != null && anim.isRunning())
                || (dropAnim != null && dropAnim.isRunning()) || (pageAnim != null && pageAnim.isRunning());
        if (animating || dragging || paging) return;
        if (dropTo >= 0 && !topPulling) settleDrop(0f, false);
        if (progress > 0f && progress < 1f && !reopening) settle(0f);
    }

    /** A finger is on the status-bar strip (set by PanelService): a still finger sends no events. */
    boolean fingerDown;

    void armWatchdog() {
        main.removeCallbacks(watchdog);
        // a finger resting mid-pull is fine; only a gesture that's truly gone quiet is finished
        main.postDelayed(watchdog, fingerDown ? 4000 : 700);
    }

    void settle(float velocity) {
        float target = velocity > 600 ? 1f : velocity < -600 ? 0f : (progress > 0.4f ? 1f : 0f);
        animateTo(target, Math.abs(velocity));
    }

    private float animTarget = -1f;

    /** On its way out (the close animation is running). */
    private boolean closing() { return anim != null && anim.isRunning() && animTarget == 0f; }

    /** Finish any page change in flight, so "which page is showing" is the real answer. */
    private void settlePages() {
        if (dropAnim != null && dropAnim.isRunning()) dropAnim.end();
        if (pageAnim != null && pageAnim.isRunning()) pageAnim.end();
    }

    /** Open on a given page even if the panel is already showing (or closing): used by the home swipe. */
    void openPage(boolean notifications) {
        settlePages();
        showPage(notifications ? NOTIFS : CONTROLS);
    }

    void animateTo(final float target, float velocity) {
        if (anim != null) anim.cancel();
        releaseStretch();
        animTarget = target;
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
                // a pull from the status bar area while open: switch pages, like a pull when closed
                // the whole top: the status row and the header with the page's name (a thumb rarely
                // starts on the very top pixels)
                topPull = downY < card.getTop() + head.getBottom() + Ui.dp(getContext(), 20);
                topPulling = false;
                reopening = false;
                stretching = false;
                releaseStretch(); // a gesture cut off mid-stretch never leaves it stretched
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
                vt.addMovement(e);
                settlePages(); // a quick second pull must judge sides against the page really showing
                if (topPull && closing()) {
                    // pulled again while it was closing: stop, show the side that was pulled, follow
                    anim.cancel();
                    showPage(downX < getWidth() * 0.45f ? NOTIFS : CONTROLS);
                    reopening = true;
                    startProgress = progress;
                    return true;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (vt != null) vt.addMovement(e);
                float dy = e.getRawY() - downY, dx = e.getRawX() - downX;
                if (topPull && dy > slop && dy > Math.abs(dx)) {
                    topPulling = true;
                    int target = downX < getWidth() * 0.45f ? NOTIFS : CONTROLS;
                    dropTo = page != target && (pageAnim == null || !pageAnim.isRunning()) ? target : -1;
                    downY = e.getRawY() - slop; // follow from here, no jump
                    if (dropTo >= 0) {
                        View v = pageView(dropTo);
                        if (dropTo == NOTIFS && notifsDirty) buildNotifs();
                        v.setVisibility(VISIBLE);
                        card.setLayerType(LAYER_TYPE_NONE, null);
                        layDrop(0f);
                    }
                    return true;
                }
                // pulling down on a page already at its top: it stretches and springs back
                if (dy > slop && dy > Math.abs(dx) && !pageScroll().canScrollVertically(-1)) {
                    stretching = true;
                    downY = e.getRawY() - slop;
                    return true;
                }
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
                        && !NotifRow.touching && (pageAnim == null || !pageAnim.isRunning())) {
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

    private boolean topPull, topPulling, reopening, stretching;
    private LinearLayout head;
    private int dropTo = -1;
    private ValueAnimator dropAnim;

    /**
     * Pulling down from the other side while open: the page you're on fades and sinks a little,
     * the other drops in from above, both following the finger (f = 0 nothing yet, 1 done).
     */
    private void layDrop(float f) {
        float d = Ui.dp(getContext(), 1);
        View from = pageView(page), to = pageView(dropTo);
        float out = Math.min(1f, f * 1.6f); // the old page is mostly gone by the middle
        from.setAlpha(1f - out);
        from.setTranslationY(out * 28 * d);
        from.setScaleX(1f - out * 0.04f);
        from.setScaleY(1f - out * 0.04f);
        float in = Math.max(0f, (f - 0.15f) / 0.85f);
        to.setAlpha(in);
        to.setTranslationY(-(1f - in) * 48 * d);
        to.setScaleX(0.97f + in * 0.03f);
        to.setScaleY(0.97f + in * 0.03f);
        float a = page == NOTIFS ? 0f : 1f, b = dropTo == NOTIFS ? 0f : 1f;
        dots.setPosition(a + (b - a) * f);
        if (page == NOTIFS) { clearFade = 1f - out; clearAll.setAlpha(progress * clearFade); }
        else if (dropTo == NOTIFS && hasNotifs) { // Clear all arrives with the notifications
            if (clearAll.getVisibility() != VISIBLE) clearAll.setVisibility(VISIBLE);
            clearFade = in;
            clearAll.setAlpha(progress * clearFade);
        }
    }

    private void settleDrop(float from, final boolean commit) {
        if (dropAnim != null) dropAnim.cancel();
        final int target = dropTo;
        dropAnim = ValueAnimator.ofFloat(from, commit ? 1f : 0f);
        dropAnim.setDuration(Math.round(160 + 140 * Math.abs((commit ? 1f : 0f) - from)));
        dropAnim.setInterpolator(Ui.EMPHASIZED);
        dropAnim.addUpdateListener(an -> { if (dropTo >= 0) layDrop((Float) an.getAnimatedValue()); });
        dropAnim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator an) {
                dropTo = -1;
                resetPageLooks();
                card.setLayerType(LAYER_TYPE_HARDWARE, null);
                if (commit) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    showPage(target);
                } else {
                    pageView(target).setVisibility(GONE);
                    showPage(page);
                }
            }
        });
        dropAnim.start();
    }

    private void resetPageLooks() {
        for (ScrollView v : new ScrollView[]{scroll, notifScroll, btScroll}) {
            v.setAlpha(1f);
            v.setTranslationY(0f);
            v.setScaleX(1f);
            v.setScaleY(1f);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (reopening) { // a pull that caught the panel while closing: it follows the finger down again
            if (vt != null) vt.addMovement(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    float pulled = startProgress * cardTravel() + Math.max(0f, e.getRawY() - downY);
                    setProgress(pulled / cardTravel());
                    overPull(pulled - cardTravel());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    reopening = false;
                    float vy = 0;
                    if (vt != null) { vt.computeCurrentVelocity(1000); vy = vt.getYVelocity(); vt.recycle(); vt = null; }
                    // a tap or any downward pull reopens; only a clear flick up lets it close
                    animateTo(vy < -600 ? 0f : 1f, Math.abs(vy));
                    return true;
            }
            return true;
        }
        armWatchdog();
        if (stretching) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_MOVE) overPull(e.getRawY() - downY);
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) { stretching = false; releaseStretch(); }
            return true;
        }
        if (topPulling) { // the rest of a top pull is ours; it never closes the panel
            if (vt != null) vt.addMovement(e);
            int a = e.getActionMasked();
            float f = Math.max(0f, Math.min(1f, (e.getRawY() - downY) / Ui.dp(getContext(), 220)));
            if (a == MotionEvent.ACTION_MOVE) {
                if (dropTo >= 0) layDrop(f);
                else overPull(e.getRawY() - downY); // this side is already showing: it just gives
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                topPulling = false;
                releaseStretch();
                float vy = 0;
                if (vt != null) { vt.computeCurrentVelocity(1000); vy = vt.getYVelocity(); vt.recycle(); vt = null; }
                if (dropTo >= 0) settleDrop(f, a == MotionEvent.ACTION_UP && (f > 0.35f || vy > 900));
            }
            return true;
        }
        if (paging) {
            if (vt != null) vt.addMovement(e);
            float dx = e.getRawX() - downX;
            int to = neighbour(page, dx);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    if (pagingTo >= 0 && pagingTo != to) pageView(pagingTo).setVisibility(GONE);
                    pagingTo = to;
                    layPages(page, to, to < 0 ? Ui.rubber(dx, Ui.dp(getContext(), 60)) : dx); // no page that way: it gives, less and less
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    paging = false;
                    float vx = 0;
                    if (vt != null) { vt.computeCurrentVelocity(1000); vx = vt.getXVelocity(); vt.recycle(); vt = null; }
                    float w = Math.max(1, pager.getWidth());
                    boolean commit = to >= 0 && e.getActionMasked() == MotionEvent.ACTION_UP
                            && (Math.abs(dx) > w * 0.3f || (Math.abs(vx) > 800 && Math.signum(vx) == Math.signum(dx)));
                    animatePages(page, to, to < 0 ? Ui.rubber(dx, Ui.dp(getContext(), 60)) : dx, commit, vx);
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

        long holdUntil;

        boolean showing() { return shown != null && shown; }

        void flip() {
            holdUntil = 0; // a new tap always flips it, whatever an earlier one was holding
            boolean on = shown == null || !shown;
            set(on, on ? "Turning on\u2026" : "Turning off\u2026");
            holdUntil = SystemClock.uptimeMillis() + HOLD_MS;
        }

        void set(boolean on, String s) {
            if (shown != null && shown != on && SystemClock.uptimeMillis() < holdUntil) return;
            if (shown != null && shown == on) { setIfChanged(state, s); return; }
            boolean first = shown == null;
            shown = on;
            state.setText(s);
            if (first) { paint(on ? 1f : 0f); return; }
            // the colours flow from one state to the other instead of snapping
            fade = Ui.crossfade(fade, f -> paint(on ? f : 1f - f));
        }

        private android.graphics.drawable.Drawable bg, iconBg;
        private ValueAnimator fade;

        /** t: 0 off .. 1 on. */
        private void paint(float t) {
            Context c = getContext();
            if (bg == null) {
                setBackground(bg = Ui.glass(TILE, Ui.dp(c, 22)));
                icon.setBackground(iconBg = Ui.glass(ROUND, Ui.dp(c, 22)));
            }
            bg.setTint(Ui.mix(TILE, Ui.ACCENT, t));
            iconBg.setTint(Ui.mix(ROUND, 0x33FFFFFF, t));
            icon.setColor(Ui.mix(Ui.FG, Color.WHITE, t));
            title.setTextColor(Ui.mix(Ui.FG, Color.WHITE, t));
            state.setTextColor(Ui.mix(Ui.FG2, 0xD9FFFFFF, t));
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

        /** Until this time a reading that contradicts the tap is the phone not having caught up. */
        long holdUntil;

        boolean showing() { return shown != null && shown; }

        void flip() {
            holdUntil = 0; // a new tap always flips it, whatever an earlier one was holding
            setActive(shown == null || !shown);
            holdUntil = SystemClock.uptimeMillis() + HOLD_MS;
        }

        void setActive(boolean on) {
            if (shown != null && shown != on && SystemClock.uptimeMillis() < holdUntil) return;
            if (shown != null && shown == on) return;
            boolean first = shown == null;
            shown = on;
            if (first) { paint(on ? 1f : 0f); return; }
            fade = Ui.crossfade(fade, f -> paint(on ? f : 1f - f));
        }

        private android.graphics.drawable.Drawable iconBg;
        private ValueAnimator fade;

        /** t: 0 off .. 1 on. */
        private void paint(float t) {
            if (iconBg == null) icon.setBackground(iconBg = Ui.glass(ROUND, Ui.dp(getContext(), 27)));
            iconBg.setTint(Ui.mix(ROUND, Ui.ACCENT, t));
            icon.setColor(Ui.mix(Ui.FG, Color.WHITE, t));
            label.setTextColor(Ui.mix(Ui.FG2, Ui.FG, t));
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
        /** Buttons with a page of their own: held and let go without moving, it opens. */
        final java.util.Map<Round, Runnable> pages = new java.util.HashMap<>();
        private float liftX, liftY;
        private boolean moved;
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
            liftX = lastX;
            liftY = lastY;
            moved = false;
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
                    // a held finger always shakes a little: it only starts moving past the touch slop
                    if (!moved && Math.hypot(lastX - liftX, lastY - liftY) < ViewConfiguration.get(getContext()).getScaledTouchSlop() * 1.5f) {
                        follow();
                        return true;
                    }
                    moved = true;
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
                    Runnable page = pages.get(r);
                    if (!moved && page != null && e.getActionMasked() == MotionEvent.ACTION_UP) { page.run(); return true; }
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
        Runnable onLongPress;
        private String label; // shown instead of the percentage (e.g. "Auto")
        private final Runnable longPress = () -> {
            if (onLongPress == null) return;
            longPressed = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            onLongPress.run();
        };
        private boolean longPressed;

        void setLabel(String l) {
            if (java.util.Objects.equals(l, label)) return;
            label = l;
            invalidate();
        }
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
            c.drawText(label != null ? label : Math.round(value * 100) + "%", w / 2f, base, pct);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY = e.getY();
                    startValue = value;
                    moving = false;
                    longPressed = false;
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                    getParent().requestDisallowInterceptTouchEvent(true); // vertical drags belong to the slider
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (longPressed) return true;
                    if (!moving && Math.abs(e.getY() - downY) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                        moving = true;
                        removeCallbacks(longPress);
                    }
                    if (moving) {
                        setValue(startValue - (e.getY() - downY) / Math.max(1, getHeight()));
                        if (listener != null) listener.onChange(value, false);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    removeCallbacks(longPress);
                    if (longPressed) return true; // the long-press did its thing (auto on/off)
                    if (!moving) setValue(1f - e.getY() / Math.max(1, getHeight())); // tap jumps to that level
                    if (listener != null) listener.onChange(value, true);
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(longPress);
                    return true;
            }
            return true;
        }
    }
}
