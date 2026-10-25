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
    private boolean onNotifs, notifsDirty = true;
    final boolean isDark = Ui.dark; // the palette this panel was built in
    private final View scrim;
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

        scrim = new View(c);
        scrim.setBackgroundColor(Ui.SCRIM);
        scrim.setAlpha(0f);
        scrim.setOnClickListener(v -> close());
        addView(scrim, new LayoutParams(-1, -1));

        // a floating card, like the system's own pop-ups
        card = new LinearLayout(c) {
            @Override public boolean hasOverlappingRendering() { return false; }
        };
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(Ui.CARD, Ui.dp(c, 30)));
        card.setPadding(0, Ui.dpi(c, 16), 0, Ui.dpi(c, 8));
        card.setClipToOutline(true);
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
        notifButton.setBackground(Ui.round(Ui.GROUP, Ui.dp(c, 22)));
        notifBell = new PanelIcons(c, PanelIcons.BELL, Ui.FG);
        notifButton.addView(notifBell, Ui.lp(Ui.dpi(c, 22), Ui.dpi(c, 22)));
        notifCount = Ui.text(c, "", 14, Ui.FG, true);
        notifCount.setTypeface(Ui.font(c, 650));
        LinearLayout.LayoutParams ncp = Ui.lp(-2, -2);
        ncp.leftMargin = Ui.dpi(c, 6);
        notifButton.addView(notifCount, ncp);
        notifButton.setClickable(true);
        Ui.pressable(notifButton);
        notifButton.setOnClickListener(v -> showNotifications(!onNotifs));
        LinearLayout.LayoutParams nbp = Ui.lp(-2, Ui.dpi(c, 44));
        nbp.rightMargin = Ui.dpi(c, 8);
        head.addView(notifButton, nbp);
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
        card.addView(scroll, slp);

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
        clearAll.setBackground(Ui.round(Ui.GROUP, Ui.dp(c, 18)));
        clearAll.setClickable(true);
        Ui.pressable(clearAll);
        clearAll.setOnClickListener(v -> { NotifService.dismissAll(); showNotifications(false); });
        nhead.addView(clearAll, Ui.lp(-2, Ui.dpi(c, 36)));
        npage.addView(nhead, Ui.lp(-1, -2));
        notifList = new LinearLayout(c);
        notifList.setOrientation(LinearLayout.VERTICAL);
        npage.addView(notifList, Ui.lp(-1, -2, Ui.dpi(c, 4)));
        notifEmpty = Ui.text(c, "No notifications", 14, Ui.FG2, false);
        notifEmpty.setGravity(Gravity.CENTER);
        npage.addView(notifEmpty, Ui.lp(-1, Ui.dpi(c, 120)));
        notifScroll.addView(npage);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, 0, 1f);
        nlp.topMargin = Ui.dpi(c, 12);
        notifScroll.setVisibility(GONE);
        card.addView(notifScroll, nlp);
        NotifService.panelListener = this::onNotifsChanged;

        // ---- four tiles, each its own button
        wifi = new Item(c, PanelIcons.WIFI, "Wi-Fi");
        data = new Item(c, PanelIcons.DATA, "Mobile data");
        bluetooth = new Item(c, PanelIcons.BLUETOOTH, "Bluetooth");
        torch = new Item(c, PanelIcons.TORCH, "Torch");
        body.addView(pair(c, wifi, data), Ui.lp(-1, Ui.dpi(c, 64)));
        body.addView(pair(c, bluetooth, torch), Ui.lp(-1, Ui.dpi(c, 64), Ui.dpi(c, 8)));
        wifi.setOnClickListener(v -> open(new Intent(Settings.Panel.ACTION_WIFI)));
        wifi.setOnLongClickListener(v -> { open(new Intent(Settings.ACTION_WIFI_SETTINGS)); return true; });
        bluetooth.setOnClickListener(v -> open(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        data.setOnClickListener(v -> open(new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)));
        torch.setOnClickListener(v -> safe(this::toggleTorch));

        // ---- media (half the width) beside the two sliders, all in one row
        LinearLayout mediaRow = new LinearLayout(c);
        FrameLayout media = new FrameLayout(c);
        mediaRow.addView(media, new LinearLayout.LayoutParams(0, -1, 2f));

        mediaFull = new FrameLayout(c);
        mediaFull.setBackground(Ui.round(Ui.GROUP, Ui.dp(c, 24)));
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
        mcol.addView(mediaBar, Ui.lp(-1, Ui.dpi(c, 3)));
        LinearLayout times = new LinearLayout(c);
        mediaTime = Ui.text(c, "", 10.5f, 0xB3FFFFFF, false);
        mediaLeft = Ui.text(c, "", 10.5f, 0xB3FFFFFF, false);
        times.addView(mediaTime, new LinearLayout.LayoutParams(0, -2, 1f));
        times.addView(mediaLeft);
        mcol.addView(times, Ui.lp(-1, -2, Ui.dpi(c, 4)));
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
        mediaEmpty.setBackground(Ui.round(Ui.GROUP, Ui.dp(c, 24)));
        mediaEmpty.setPadding(Ui.dpi(c, 14), 0, Ui.dpi(c, 14), 0);
        PanelIcons note = new PanelIcons(c, PanelIcons.PLAY, Ui.FG2);
        int np = Ui.dpi(c, 13);
        note.setPadding(np, np, np, np);
        note.setBackground(Ui.round(Ui.ICON_BG, Ui.dp(c, 23)));
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
        LinearLayout grid = new LinearLayout(c);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setBackground(Ui.round(Ui.GROUP, Ui.dp(c, 24)));
        grid.setPadding(Ui.dpi(c, 4), Ui.dpi(c, 14), Ui.dpi(c, 4), Ui.dpi(c, 12));
        grid.addView(row(c, hotspot, rotate, plane, location));
        grid.addView(row(c, dark, dnd, sound, eye), Ui.lp(-1, -2, Ui.dpi(c, 10)));
        grid.addView(row(c, saver, screenshot, record, cast), Ui.lp(-1, -2, Ui.dpi(c, 10)));
        grid.addView(row(c, calc, recorder, lock, power), Ui.lp(-1, -2, Ui.dpi(c, 10)));
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

        View handle = new View(c);
        handle.setBackground(Ui.round(Ui.HANDLE, Ui.dp(c, 2)));
        LinearLayout.LayoutParams hp = Ui.lp(Ui.dpi(c, 36), Ui.dpi(c, 4), Ui.dpi(c, 10));
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
        b.setBackground(Ui.round(Ui.GROUP, Ui.dp(c, 22)));
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
        try { r.run(); } catch (Throwable t) { android.util.Log.w("FastLauncher", "panel: " + t); }
    }

    // ================================================================ state

    /** Re-read every control's state; called the moment a finger lands on the pull strip. */
    void refresh(boolean notifications) {
        final Context c = getContext();
        scroll.scrollTo(0, 0);
        showNotifications(notifications);
        showNotifCount();
        // Each reading is isolated: this runs in the launcher's process, and one OEM quirk or
        // missing permission must never take the home screen down with it.
        safe(() -> {
            Calendar cal = Calendar.getInstance();
            time.setText(MainActivity.clockText(cal));
            date.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(cal.getTime()));
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
        mediaFull.setVisibility(real ? VISIBLE : GONE);
        mediaEmpty.setVisibility(real ? GONE : VISIBLE);
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
        main.removeCallbacks(tickProgress);
        if (controller != null) controller.unregisterCallback(mediaCallback);
        controller = null;
    }

    // ================================================================ notifications

    private final java.util.Map<String, CharSequence> appNames = new java.util.HashMap<>();
    private final java.util.Map<String, android.graphics.drawable.Drawable> appIcons = new java.util.HashMap<>();

    private void onNotifsChanged() {
        notifsDirty = true;
        showNotifCount();
        if (onNotifs) buildNotifs();
    }

    private void showNotifCount() {
        int n = NotifService.list.size();
        setIfChanged(notifCount, n == 0 ? "" : n > 99 ? "99+" : String.valueOf(n));
        notifCount.setVisibility(n == 0 ? GONE : VISIBLE);
    }

    private void showNotifications(boolean show) {
        if (show && notifsDirty) buildNotifs();
        if (show == onNotifs) return;
        onNotifs = show;
        Context c = getContext();
        notifScroll.scrollTo(0, 0);
        notifScroll.setVisibility(show ? VISIBLE : GONE);
        scroll.setVisibility(show ? GONE : VISIBLE);
        notifButton.setBackground(Ui.round(show ? Ui.ACCENT : Ui.GROUP, Ui.dp(c, 22)));
        notifBell.setColor(show ? Color.WHITE : Ui.FG);
        notifCount.setTextColor(show ? Color.WHITE : Ui.FG);
    }

    private void buildNotifs() {
        notifsDirty = false;
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
        row.setBackground(Ui.round(Ui.GROUP, Ui.dp(c, 22)));
        int p = Ui.dpi(c, 14);
        row.setPadding(p, p, p, p);

        // the sender's picture when there is one (a chat contact), otherwise the app's icon
        ImageView icon = new ImageView(c);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        android.graphics.drawable.Drawable d = null;
        try { if (n.getLargeIcon() != null) d = n.getLargeIcon().loadDrawable(c); } catch (Exception ignored) {}
        if (d != null) {
            icon.setBackground(Ui.round(Ui.ICON_BG, Ui.dp(c, 20)));
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
        if (n.contentIntent != null) {
            try {
                android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
                if (android.os.Build.VERSION.SDK_INT >= 34)
                    o.setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                n.contentIntent.send(getContext(), 0, null, null, null, null, o.toBundle());
            } catch (Exception e) {
                openApp(s.getPackageName());
                return;
            }
        } else {
            openApp(s.getPackageName());
            return;
        }
        if ((n.flags & android.app.Notification.FLAG_AUTO_CANCEL) != 0) NotifService.dismiss(s.getKey());
        close();
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
                    animate().scaleX(0.98f).scaleY(0.98f).setDuration(90).start();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!swiping && !cancelled && dismissable && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
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
                                if (p != null) p.removeView(this);
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
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
                vt.addMovement(e);
                break;
            case MotionEvent.ACTION_MOVE:
                if (vt != null) vt.addMovement(e);
                float dy = e.getRawY() - downY, dx = e.getRawX() - downX;
                if (dy < -slop && Math.abs(dy) > Math.abs(dx) && !(onNotifs ? notifScroll : scroll).canScrollVertically(1)) {
                    dragging = true;
                    downY = e.getRawY();
                    startProgress = progress;
                    if (anim != null) anim.cancel();
                    return true;
                }
                break;
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
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
            setBackground(Ui.round(on ? Ui.ACCENT : Ui.GROUP, Ui.dp(c, 22)));
            icon.setBackground(Ui.round(on ? 0x33FFFFFF : Ui.ICON_BG, Ui.dp(c, 22)));
            icon.setColor(on ? Color.WHITE : Ui.FG);
            title.setTextColor(on ? Color.WHITE : Ui.FG);
            state.setText(s);
            state.setTextColor(on ? 0xD9FFFFFF : Ui.FG2);
        }
    }

    /** A round toggle with a caption underneath. */
    static final class Round extends LinearLayout {
        final PanelIcons icon;
        private final TextView label;

        Round(Context c, int type, String name) {
            super(c);
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
            icon.setBackground(Ui.round(on ? Ui.ACCENT : Ui.ICON_BG, Ui.dp(getContext(), 27)));
            icon.setColor(on ? Color.WHITE : Ui.FG);
            label.setTextColor(on ? Ui.FG : Ui.FG2);
        }

        void setLabel(String s) { setIfChanged(label, s); }
    }

    /** Thin progress line for the media card. */
    static final class Progress extends View {
        private float f;
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);

        Progress(Context c) {
            super(c);
            track.setColor(0x4DFFFFFF);
            fill.setColor(Color.WHITE);
        }

        void setFraction(float v) {
            if (Math.abs(v - f) < 0.0005f) return;
            f = v;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float h = getHeight(), r = h / 2f;
            c.drawRoundRect(0, 0, getWidth(), h, r, r, track);
            c.drawRoundRect(0, 0, Math.max(h, getWidth() * f), h, r, r, fill);
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
            track.setColor(Ui.GROUP);
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
