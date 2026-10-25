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
 *   cards         Wi-Fi + Bluetooth | Mobile data + Torch
 *   media row     now playing | brightness | volume (tall sliders)
 *   grid          16 round toggles
 *
 * Everything that Android lets an app switch is switched in place; the rest opens the exact
 * system page for it.
 */
final class ControlPanel extends FrameLayout {

    interface Host {
        void launch(Intent i);
        void globalAction(int action);
        void finishHide();
        int statusBarHeight();
    }

    private final Host host;
    private final View scrim;
    private final LinearLayout card;
    private final ScrollView scroll;
    private final TextView time, date;
    private final Item wifi, bluetooth, data, torch;
    private final VSlider brightness, volume;
    private final Round hotspot, rotate, plane, location, dark, dnd, sound, eye,
            saver, screenshot, record, cast, calc, recorder, lock, power;
    private final ImageView mediaArt;
    private final TextView mediaTitle, mediaArtist;
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
        scrim.setBackgroundColor(0xB3000000);
        scrim.setAlpha(0f);
        scrim.setOnClickListener(v -> close());
        addView(scrim, new LayoutParams(-1, -1));

        card = new LinearLayout(c) {
            @Override public boolean hasOverlappingRendering() { return false; }
        };
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFA131417);
        float r = Ui.dp(c, 34);
        bg.setCornerRadii(new float[]{0, 0, 0, 0, r, r, r, r});
        card.setBackground(bg);
        card.setPadding(0, h.statusBarHeight() + Ui.dpi(c, 8), 0, Ui.dpi(c, 10));
        card.setClickable(true);
        addView(card, new LayoutParams(-1, -2, Gravity.TOP));

        // ---- header
        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dpi(c, 22), 0, Ui.dpi(c, 16), Ui.dpi(c, 6));
        LinearLayout tcol = new LinearLayout(c);
        tcol.setOrientation(LinearLayout.VERTICAL);
        time = Ui.text(c, "", 30, Ui.FG, true);
        time.setTypeface(Ui.font(c, 650));
        date = Ui.text(c, "", 13.5f, Ui.FG2, false);
        tcol.addView(time);
        tcol.addView(date, Ui.lp(-2, -2, Ui.dpi(c, 4)));
        head.addView(tcol, new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(circleButton(c, PanelIcons.GEAR, () -> open(new Intent(Settings.ACTION_SETTINGS))));
        card.addView(head, Ui.lp(-1, -2));

        // everything below the header scrolls if a small screen ever needs it
        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dpi(c, 14);
        body.setPadding(pad, 0, pad, 0);
        scroll.addView(body);
        card.addView(scroll, Ui.lp(-1, -2, Ui.dpi(c, 8)));

        // ---- connectivity cards, Honor-style: two items per card
        wifi = new Item(c, PanelIcons.WIFI, "Wi-Fi");
        bluetooth = new Item(c, PanelIcons.BLUETOOTH, "Bluetooth");
        data = new Item(c, PanelIcons.DATA, "Mobile data");
        torch = new Item(c, PanelIcons.TORCH, "Torch");
        body.addView(pair(c, wifi, data), Ui.lp(-1, Ui.dpi(c, 68), Ui.dpi(c, 6)));
        body.addView(pair(c, bluetooth, torch), Ui.lp(-1, Ui.dpi(c, 68), Ui.dpi(c, 10)));
        wifi.setOnClickListener(v -> open(new Intent(Settings.Panel.ACTION_WIFI)));
        wifi.setOnLongClickListener(v -> { open(new Intent(Settings.ACTION_WIFI_SETTINGS)); return true; });
        bluetooth.setOnClickListener(v -> open(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        data.setOnClickListener(v -> open(new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)));
        torch.setOnClickListener(v -> safe(this::toggleTorch));

        // ---- media + tall sliders
        LinearLayout mid = new LinearLayout(c);
        LinearLayout mediaCard = new LinearLayout(c);
        mediaCard.setOrientation(LinearLayout.VERTICAL);
        mediaCard.setBackground(Ui.round(0x14FFFFFF, Ui.dp(c, 26)));
        mediaCard.setPadding(Ui.dpi(c, 14), Ui.dpi(c, 14), Ui.dpi(c, 14), Ui.dpi(c, 8));
        LinearLayout mtop = new LinearLayout(c);
        mtop.setGravity(Gravity.CENTER_VERTICAL);
        mediaArt = new ImageView(c);
        mediaArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mediaArt.setBackground(Ui.round(0x1FFFFFFF, Ui.dp(c, 12)));
        mediaArt.setClipToOutline(true);
        mtop.addView(mediaArt, Ui.lp(Ui.dpi(c, 44), Ui.dpi(c, 44)));
        LinearLayout mt = new LinearLayout(c);
        mt.setOrientation(LinearLayout.VERTICAL);
        mt.setPadding(Ui.dpi(c, 10), 0, 0, 0);
        mediaTitle = Ui.text(c, "Not playing", 14.5f, Ui.FG, true);
        mediaTitle.setSingleLine(true);
        mediaTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mediaArtist = Ui.text(c, "", 12, Ui.FG2, false);
        mediaArtist.setSingleLine(true);
        mediaArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mt.addView(mediaTitle);
        mt.addView(mediaArtist, Ui.lp(-1, -2, Ui.dpi(c, 3)));
        mtop.addView(mt, new LinearLayout.LayoutParams(0, -2, 1f));
        mediaCard.addView(mtop, Ui.lp(-1, -2));
        mediaCard.addView(new View(c), new LinearLayout.LayoutParams(1, 0, 1f));
        LinearLayout controls = new LinearLayout(c);
        controls.setGravity(Gravity.CENTER);
        controls.addView(mediaButton(c, PanelIcons.PREV, () -> { if (controller != null) controller.getTransportControls().skipToPrevious(); }),
                new LinearLayout.LayoutParams(0, Ui.dpi(c, 44), 1f));
        mediaPlay = mediaButton(c, PanelIcons.PLAY, this::togglePlay);
        controls.addView(mediaPlay, new LinearLayout.LayoutParams(0, Ui.dpi(c, 44), 1f));
        controls.addView(mediaButton(c, PanelIcons.NEXT, () -> { if (controller != null) controller.getTransportControls().skipToNext(); }),
                new LinearLayout.LayoutParams(0, Ui.dpi(c, 44), 1f));
        mediaCard.addView(controls, Ui.lp(-1, -2));
        int midH = Ui.dpi(c, 150);
        mid.addView(mediaCard, new LinearLayout.LayoutParams(0, midH, 2f));
        brightness = new VSlider(c, PanelIcons.SUN);
        volume = new VSlider(c, PanelIcons.SPEAKER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, midH, 1f);
        sp.leftMargin = Ui.dpi(c, 10);
        mid.addView(brightness, sp);
        LinearLayout.LayoutParams sp2 = new LinearLayout.LayoutParams(0, midH, 1f);
        sp2.leftMargin = Ui.dpi(c, 10);
        mid.addView(volume, sp2);
        body.addView(mid, Ui.lp(-1, -2, Ui.dpi(c, 10)));
        brightness.listener = (v, d) -> safe(() -> setBrightness(v, d));
        volume.listener = (v, d) -> safe(() -> setVolume(v, d));

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
        grid.setBackground(Ui.round(0x14FFFFFF, Ui.dp(c, 26)));
        grid.setPadding(Ui.dpi(c, 6), Ui.dpi(c, 16), Ui.dpi(c, 6), Ui.dpi(c, 14));
        grid.addView(row(c, hotspot, rotate, plane, location));
        grid.addView(row(c, dark, dnd, sound, eye), Ui.lp(-1, -2, Ui.dpi(c, 12)));
        grid.addView(row(c, saver, screenshot, record, cast), Ui.lp(-1, -2, Ui.dpi(c, 12)));
        grid.addView(row(c, calc, recorder, lock, power), Ui.lp(-1, -2, Ui.dpi(c, 12)));
        body.addView(grid, Ui.lp(-1, -2, Ui.dpi(c, 10)));

        hotspot.setOnClickListener(v -> open(new Intent(Settings.ACTION_WIRELESS_SETTINGS)));
        rotate.setOnClickListener(v -> safe(this::toggleRotation));
        plane.setOnClickListener(v -> open(new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)));
        location.setOnClickListener(v -> open(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        dark.setOnClickListener(v -> open(new Intent("android.settings.DARK_THEME_SETTINGS")));
        dnd.setOnClickListener(v -> safe(this::toggleDnd));
        sound.setOnClickListener(v -> safe(this::cycleSound));
        eye.setOnClickListener(v -> open(new Intent(Settings.ACTION_DISPLAY_SETTINGS)));
        saver.setOnClickListener(v -> open(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)));
        screenshot.setOnClickListener(v -> closeThen(380, () -> host.globalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)));
        record.setOnClickListener(v -> closeThen(300, () -> host.launch(
                new Intent("com.hihonor.screenrecorder.intent.action.LMT_ScreenRecorder"))));
        cast.setOnClickListener(v -> open(new Intent(Settings.ACTION_CAST_SETTINGS)));
        calc.setOnClickListener(v -> openApp("com.google.android.calculator", "com.hihonor.calculator"));
        recorder.setOnClickListener(v -> openApp("com.hihonor.soundrecorder", "com.google.android.apps.recorder"));
        lock.setOnClickListener(v -> closeThen(250, () -> host.globalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)));
        power.setOnClickListener(v -> closeThen(200, () -> host.globalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)));

        View handle = new View(c);
        handle.setBackground(Ui.round(0x40FFFFFF, Ui.dp(c, 2)));
        LinearLayout.LayoutParams hp = Ui.lp(Ui.dpi(c, 36), Ui.dpi(c, 4), Ui.dpi(c, 12));
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

    /** Two separate tiles side by side. */
    private static LinearLayout pair(Context c, View a, View b) {
        LinearLayout r = new LinearLayout(c);
        r.addView(a, new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        lp.leftMargin = Ui.dpi(c, 10);
        r.addView(b, lp);
        return r;
    }

    private PanelIcons circleButton(Context c, int type, Runnable action) {
        PanelIcons b = new PanelIcons(c, type, Ui.FG);
        b.setBackground(Ui.round(0x1FFFFFFF, Ui.dp(c, 22)));
        int p = Ui.dpi(c, 11);
        b.setPadding(p, p, p, p);
        b.setClickable(true);
        Ui.pressable(b);
        b.setOnClickListener(v -> action.run());
        b.setLayoutParams(Ui.lp(Ui.dpi(c, 44), Ui.dpi(c, 44)));
        return b;
    }

    private PanelIcons mediaButton(Context c, int type, Runnable action) {
        PanelIcons b = new PanelIcons(c, type, Ui.FG);
        int p = Ui.dpi(c, 11);
        b.setPadding(p, p, p, p);
        b.setClickable(true);
        Ui.pressable(b);
        b.setOnClickListener(v -> { action.run(); v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); });
        return b;
    }

    private static void safe(Runnable r) {
        try { r.run(); } catch (Throwable t) { android.util.Log.w("FastLauncher", "panel: " + t); }
    }

    // ================================================================ state

    /** Re-read every control's state; called each time the panel is pulled down. */
    void refresh() {
        final Context c = getContext();
        scroll.scrollTo(0, 0);
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
        safe(() -> {
            int b = Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128);
            brightness.setValue(b / 255f);
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
        safe(() -> saver.setActive(((PowerManager) c.getSystemService(Context.POWER_SERVICE)).isPowerSaveMode()));
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
            // needs notification access; without it the card just says "Not playing"
        }
        if (controller != null && controller != next) controller.unregisterCallback(mediaCallback);
        if (next != null && next != controller) next.registerCallback(mediaCallback, main);
        controller = next;
        showMedia();
    }

    private void showMedia() {
        if (controller == null || !isReal(controller)) {
            mediaTitle.setText("Not playing");
            mediaArtist.setText("");
            mediaArt.setImageBitmap(null);
            mediaPlay.setType(PanelIcons.PLAY);
            return;
        }
        MediaMetadata m = controller.getMetadata();
        CharSequence artist = m.getText(MediaMetadata.METADATA_KEY_ARTIST);
        mediaTitle.setText(m.getText(MediaMetadata.METADATA_KEY_TITLE));
        mediaArtist.setText(artist == null ? "" : artist);
        Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        mediaArt.setImageBitmap(art);
        PlaybackState s = controller.getPlaybackState();
        mediaPlay.setType(s != null && s.getState() == PlaybackState.STATE_PLAYING ? PanelIcons.PAUSE : PanelIcons.PLAY);
    }

    private void togglePlay() {
        if (controller == null) return;
        PlaybackState s = controller.getPlaybackState();
        if (s != null && s.getState() == PlaybackState.STATE_PLAYING) controller.getTransportControls().pause();
        else controller.getTransportControls().play();
    }

    void unbind() {
        if (controller != null) controller.unregisterCallback(mediaCallback);
        controller = null;
    }

    // ================================================================ motion

    private float cardTravel() {
        return Math.max(1, card.getHeight() + Ui.dp(getContext(), 24));
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
        setProgress(dy / cardTravel());
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
                if (dy < -slop && Math.abs(dy) > Math.abs(dx) && !scroll.canScrollVertically(1)) {
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

    /** One row inside a connectivity card: icon in a circle, name, state. */
    static final class Item extends LinearLayout {
        final PanelIcons icon;
        private final TextView state;

        private final TextView title;

        Item(Context c, int type, String name) {
            super(c);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(Ui.dpi(c, 11), 0, Ui.dpi(c, 12), 0);
            icon = new PanelIcons(c, type, Ui.FG);
            int p = Ui.dpi(c, 11);
            icon.setPadding(p, p, p, p);
            addView(icon, Ui.lp(Ui.dpi(c, 46), Ui.dpi(c, 46)));
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(VERTICAL);
            col.setPadding(Ui.dpi(c, 10), 0, 0, 0);
            TextView t = Ui.text(c, name, 14.5f, Ui.FG, true);
            t.setTypeface(Ui.font(c, 600));
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title = t;
            state = Ui.text(c, "", 12, Ui.FG2, false);
            state.setSingleLine(true);
            state.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(t);
            col.addView(state, Ui.lp(-2, -2, Ui.dpi(c, 3)));
            addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
            setClickable(true);
            Ui.pressable(this);
            set(false, "");
        }

        void set(boolean on, String s) {
            Context c = getContext();
            setBackground(Ui.round(on ? Ui.ACCENT : 0x14FFFFFF, Ui.dp(c, 24)));
            icon.setBackground(Ui.round(on ? 0x33FFFFFF : 0x1FFFFFFF, Ui.dp(c, 23)));
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

        void setActive(boolean on) {
            icon.setBackground(Ui.round(on ? Ui.ACCENT : 0x1FFFFFFF, Ui.dp(getContext(), 27)));
            label.setTextColor(on ? Ui.FG : Ui.FG2);
        }

        void setLabel(String s) { label.setText(s); }
    }

    /**
     * A tall slider whose fill is exactly the value (0% empty, 100% full), with the percentage
     * at the top. Drag up and down, relative to where you started.
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
            track.setColor(0x14FFFFFF);
            fill.setColor(0xF2FFFFFF);
            pct.setTextAlign(Paint.Align.CENTER);
            pct.setTypeface(Ui.font(c, 600));
            pct.setTextSize(Ui.dp(c, 13));
            iconSize = Ui.dp(c, 52);
            icon = new PanelIcons(c, type, Ui.FG);
            int p = Ui.dpi(c, 14);
            icon.setPadding(p, p, p, p);
            addView(icon, new LayoutParams(Math.round(iconSize), Math.round(iconSize), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        }

        void setValue(float v) {
            value = Math.max(0f, Math.min(1f, v));
            // the icon flips to dark once the white fill reaches its middle
            icon.setColor(value * getHeight() > iconSize / 2f ? Ui.INK : Ui.FG);
            invalidate();
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            clip.reset();
            float r = Ui.dp(getContext(), 26);
            clip.addRoundRect(0, 0, w, h, r, r, android.graphics.Path.Direction.CW);
            setValue(value);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            int save = c.save();
            c.clipPath(clip);
            c.drawRect(0, 0, w, h, track);
            float top = h - h * value;
            c.drawRect(0, top, w, h, fill);
            c.restoreToCount(save);
            String t = Math.round(value * 100) + "%";
            pct.setColor(top < Ui.dp(getContext(), 30) ? Ui.INK : Ui.FG2);
            c.drawText(t, w / 2f, Ui.dp(getContext(), 22), pct);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY = e.getY();
                    startValue = value;
                    moving = false;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!moving && Math.abs(e.getY() - downY) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) moving = true;
                    if (moving) {
                        setValue(startValue + (downY - e.getY()) / Math.max(1, getHeight()));
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
