package app.fastlauncher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.NotificationManager;
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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * The pull-down control panel: big tiles for the things used most, media, brightness and
 * volume, and a grid of quick toggles. Shown by {@link PanelService} over any app.
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
    private final TextView time, date;
    private final Tile wifi, hotspot, rotate, torch;
    private final Slider brightness, volume;
    private final Round bluetooth, dnd, data, location, plane, saver, screenshot, lock;
    private final LinearLayout media;
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
        bg.setColor(0xFA15161A);
        float r = Ui.dp(c, 34);
        bg.setCornerRadii(new float[]{0, 0, 0, 0, r, r, r, r});
        card.setBackground(bg);
        int pad = Ui.dpi(c, 16);
        card.setPadding(pad, h.statusBarHeight() + Ui.dpi(c, 10), pad, Ui.dpi(c, 12));
        card.setClickable(true);
        addView(card, new LayoutParams(-1, -2, Gravity.TOP));

        // header: time + date, settings
        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dpi(c, 6), 0, 0, 0);
        LinearLayout tcol = new LinearLayout(c);
        tcol.setOrientation(LinearLayout.VERTICAL);
        time = Ui.text(c, "", 30, Ui.FG, true);
        time.setTypeface(Ui.font(c, 650));
        date = Ui.text(c, "", 13.5f, Ui.FG2, false);
        tcol.addView(time);
        tcol.addView(date, Ui.lp(-2, -2, Ui.dpi(c, 4)));
        head.addView(tcol, new LinearLayout.LayoutParams(0, -2, 1f));
        PanelIcons gear = new PanelIcons(c, PanelIcons.GEAR, Ui.FG);
        gear.setBackground(Ui.round(0x1FFFFFFF, Ui.dp(c, 22)));
        int gp = Ui.dpi(c, 11);
        gear.setPadding(gp, gp, gp, gp);
        gear.setClickable(true);
        Ui.pressable(gear);
        gear.setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));
        head.addView(gear, Ui.lp(Ui.dpi(c, 44), Ui.dpi(c, 44)));
        card.addView(head, Ui.lp(-1, -2));

        // big tiles: the everyday ones
        hotspot = new Tile(c, PanelIcons.HOTSPOT, "Hotspot");
        rotate = new Tile(c, PanelIcons.ROTATE, "Auto-rotate");
        wifi = new Tile(c, PanelIcons.WIFI, "Wi-Fi");
        torch = new Tile(c, PanelIcons.TORCH, "Torch");
        card.addView(row(c, hotspot, rotate), Ui.lp(-1, -2, Ui.dpi(c, 16)));
        card.addView(row(c, wifi, torch), Ui.lp(-1, -2, Ui.dpi(c, 10)));
        hotspot.setOnClickListener(v -> open(new Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings")));
        wifi.setOnClickListener(v -> open(new Intent(Settings.Panel.ACTION_WIFI)));
        wifi.setOnLongClickListener(v -> { open(new Intent(Settings.ACTION_WIFI_SETTINGS)); return true; });
        rotate.setOnClickListener(v -> toggleRotation());
        torch.setOnClickListener(v -> toggleTorch());

        // media (only when something is playing or paused)
        media = new LinearLayout(c);
        media.setGravity(Gravity.CENTER_VERTICAL);
        media.setBackground(Ui.round(0x1AFFFFFF, Ui.dp(c, 22)));
        media.setPadding(Ui.dpi(c, 10), Ui.dpi(c, 10), Ui.dpi(c, 10), Ui.dpi(c, 10));
        mediaArt = new ImageView(c);
        mediaArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mediaArt.setBackground(Ui.round(0x26FFFFFF, Ui.dp(c, 14)));
        mediaArt.setClipToOutline(true);
        media.addView(mediaArt, Ui.lp(Ui.dpi(c, 52), Ui.dpi(c, 52)));
        LinearLayout mt = new LinearLayout(c);
        mt.setOrientation(LinearLayout.VERTICAL);
        mt.setPadding(Ui.dpi(c, 12), 0, Ui.dpi(c, 6), 0);
        mediaTitle = Ui.text(c, "", 15, Ui.FG, true);
        mediaTitle.setSingleLine(true);
        mediaTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mediaArtist = Ui.text(c, "", 12.5f, Ui.FG2, false);
        mediaArtist.setSingleLine(true);
        mediaArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mt.addView(mediaTitle);
        mt.addView(mediaArtist, Ui.lp(-1, -2, Ui.dpi(c, 4)));
        media.addView(mt, new LinearLayout.LayoutParams(0, -2, 1f));
        media.addView(mediaButton(c, PanelIcons.PREV, () -> { if (controller != null) controller.getTransportControls().skipToPrevious(); }));
        mediaPlay = mediaButton(c, PanelIcons.PLAY, this::togglePlay);
        media.addView(mediaPlay);
        media.addView(mediaButton(c, PanelIcons.NEXT, () -> { if (controller != null) controller.getTransportControls().skipToNext(); }));
        media.setVisibility(GONE);
        card.addView(media, Ui.lp(-1, -2, Ui.dpi(c, 10)));

        // sliders
        brightness = new Slider(c, PanelIcons.SUN);
        volume = new Slider(c, PanelIcons.SPEAKER);
        card.addView(brightness, Ui.lp(-1, Ui.dpi(c, 54), Ui.dpi(c, 10)));
        card.addView(volume, Ui.lp(-1, Ui.dpi(c, 54), Ui.dpi(c, 10)));
        brightness.listener = this::setBrightness;
        volume.listener = this::setVolume;

        // quick toggles
        bluetooth = new Round(c, PanelIcons.BLUETOOTH, "Bluetooth");
        dnd = new Round(c, PanelIcons.DND, "Do not\ndisturb");
        data = new Round(c, PanelIcons.DATA, "Mobile\ndata");
        location = new Round(c, PanelIcons.LOCATION, "Location");
        plane = new Round(c, PanelIcons.PLANE, "Aeroplane");
        saver = new Round(c, PanelIcons.BATTERY, "Battery\nsaver");
        screenshot = new Round(c, PanelIcons.SCREENSHOT, "Screenshot");
        lock = new Round(c, PanelIcons.LOCK, "Lock");
        card.addView(row(c, bluetooth, dnd, data, location), Ui.lp(-1, -2, Ui.dpi(c, 16)));
        card.addView(row(c, plane, saver, screenshot, lock), Ui.lp(-1, -2, Ui.dpi(c, 12)));
        bluetooth.setOnClickListener(v -> open(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        dnd.setOnClickListener(v -> toggleDnd());
        data.setOnClickListener(v -> open(new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)));
        location.setOnClickListener(v -> open(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        plane.setOnClickListener(v -> open(new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)));
        saver.setOnClickListener(v -> open(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)));
        screenshot.setOnClickListener(v -> {
            close();
            // let the panel get out of the picture first
            main.postDelayed(() -> host.globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT), 380);
        });
        lock.setOnClickListener(v -> {
            close();
            main.postDelayed(() -> host.globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN), 250);
        });

        View handle = new View(c);
        handle.setBackground(Ui.round(0x40FFFFFF, Ui.dp(c, 2)));
        LinearLayout.LayoutParams hp = Ui.lp(Ui.dpi(c, 36), Ui.dpi(c, 4), Ui.dpi(c, 14));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(handle, hp);

        watchTorch();
        setProgress(0f);
    }

    private static LinearLayout row(Context c, View... views) {
        LinearLayout r = new LinearLayout(c);
        int gap = Ui.dpi(c, views.length > 2 ? 4 : 10);
        for (int i = 0; i < views.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) lp.leftMargin = gap;
            r.addView(views[i], lp);
        }
        return r;
    }

    private PanelIcons mediaButton(Context c, int type, Runnable action) {
        PanelIcons b = new PanelIcons(c, type, Ui.FG);
        int p = Ui.dpi(c, 10);
        b.setPadding(p, p, p, p);
        b.setClickable(true);
        Ui.pressable(b);
        b.setOnClickListener(v -> { action.run(); v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); });
        b.setLayoutParams(Ui.lp(Ui.dpi(c, 42), Ui.dpi(c, 42)));
        return b;
    }

    // ================================================================ state

    /** Re-read every control's state; called each time the panel is pulled down. */
    void refresh() {
        Context c = getContext();
        Calendar cal = Calendar.getInstance();
        time.setText(MainActivity.clockText(cal));
        date.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(cal.getTime()));

        WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        boolean wifiOn = wm != null && wm.isWifiEnabled();
        wifi.set(wifiOn, wifiOn ? wifiName(wm) : "Off");
        boolean ap = hotspotOn(wm);
        hotspot.set(ap, ap ? "On" : "Off");
        boolean auto = Settings.System.getInt(c.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
        rotate.set(auto, auto ? "On" : "Locked");
        rotate.icon.setType(auto ? PanelIcons.ROTATE : PanelIcons.ROTATE_LOCK);
        torch.set(torchOn, torchOn ? "On" : "Off");

        int b = Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128);
        brightness.setValue((float) Math.sqrt(b / 255f));
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        volume.setValue(am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)));

        try {
            android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
            bluetooth.setActive(bm != null && bm.getAdapter() != null && bm.getAdapter().isEnabled());
        } catch (SecurityException e) {
            bluetooth.setActive(false);
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        dnd.setActive(nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL);
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        location.setActive(lm != null && lm.isLocationEnabled());
        plane.setActive(Settings.Global.getInt(c.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1);
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        saver.setActive(pm.isPowerSaveMode());
        data.setActive(Settings.Global.getInt(c.getContentResolver(), "mobile_data", 0) == 1);

        bindMedia();
    }

    private String wifiName(WifiManager wm) {
        try {
            String ssid = wm.getConnectionInfo().getSSID();
            if (ssid != null && !ssid.contains("unknown ssid")) return ssid.replace("\"", "");
        } catch (Exception ignored) {}
        return "On";
    }

    /** WifiManager.isWifiApEnabled is hidden but readable; fall back to "unknown" quietly. */
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

    private void toggleRotation() {
        Context c = getContext();
        if (!Settings.System.canWrite(c)) {
            open(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + c.getPackageName())));
            return;
        }
        boolean auto = Settings.System.getInt(c.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
        Settings.System.putInt(c.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, auto ? 0 : 1);
        rotate.set(!auto, !auto ? "On" : "Locked");
        rotate.icon.setType(!auto ? PanelIcons.ROTATE : PanelIcons.ROTATE_LOCK);
    }

    private void watchTorch() {
        try {
            for (String id : cameras.getCameraIdList()) {
                Boolean flash = cameras.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = cameras.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash) && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
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
        Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, Math.max(1, Math.round(v * v * 255)));
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

    private void bindMedia() {
        MediaController next = null;
        try {
            MediaSessionManager msm = (MediaSessionManager) getContext().getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> list = msm.getActiveSessions(new ComponentName(getContext(), NotifService.class));
            for (MediaController mc : list) {
                if (mc.getMetadata() != null) { next = mc; break; }
            }
        } catch (Exception ignored) {
            // needs notification access; without it the media card simply stays hidden
        }
        if (controller != null && controller != next) controller.unregisterCallback(mediaCallback);
        if (next != null && next != controller) next.registerCallback(mediaCallback, main);
        controller = next;
        showMedia();
    }

    private void showMedia() {
        MediaMetadata m = controller == null ? null : controller.getMetadata();
        if (m == null) { media.setVisibility(GONE); return; }
        media.setVisibility(VISIBLE);
        CharSequence title = m.getText(MediaMetadata.METADATA_KEY_TITLE);
        CharSequence artist = m.getText(MediaMetadata.METADATA_KEY_ARTIST);
        mediaTitle.setText(title == null ? "Playing" : title);
        mediaArtist.setText(artist == null ? "" : artist);
        Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        mediaArt.setImageBitmap(art);
        PlaybackState s = controller.getPlaybackState();
        boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
        mediaPlay.setType(playing ? PanelIcons.PAUSE : PanelIcons.PLAY);
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
        card.setLayerType(LAYER_TYPE_HARDWARE, null);
        setProgress(dy / cardTravel());
    }

    void settle(float velocity) {
        float target = velocity > 600 ? 1f : velocity < -600 ? 0f : (progress > 0.4f ? 1f : 0f);
        animateTo(target, Math.abs(velocity));
    }

    void animateTo(final float target, float velocity) {
        if (anim != null) anim.cancel();
        card.setLayerType(LAYER_TYPE_HARDWARE, null);
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
                card.setLayerType(LAYER_TYPE_NONE, null);
                if (target == 0f) host.finishHide();
            }
        });
        anim.start();
    }

    void close() {
        if (progress <= 0f) { host.finishHide(); return; }
        animateTo(0f, 0f);
    }

    // swipe the card back up to close
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
                if (dy < -slop && Math.abs(dy) > Math.abs(dx)) {
                    dragging = true;
                    downY = e.getRawY();
                    startProgress = progress;
                    if (anim != null) anim.cancel();
                    card.setLayerType(LAYER_TYPE_HARDWARE, null);
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

    /** A wide tile: icon in a circle, a name and a state line. */
    static final class Tile extends LinearLayout {
        final PanelIcons icon;
        private final TextView state;
        private final View circle;

        Tile(Context c, int type, String name) {
            super(c);
            setGravity(Gravity.CENTER_VERTICAL);
            setBackground(Ui.round(0x14FFFFFF, Ui.dp(c, 24)));
            setPadding(Ui.dpi(c, 10), 0, Ui.dpi(c, 12), 0);
            setMinimumHeight(Ui.dpi(c, 68));
            icon = new PanelIcons(c, type, Ui.FG);
            int p = Ui.dpi(c, 10);
            icon.setPadding(p, p, p, p);
            circle = icon;
            addView(icon, Ui.lp(Ui.dpi(c, 44), Ui.dpi(c, 44)));
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(VERTICAL);
            col.setPadding(Ui.dpi(c, 10), 0, 0, 0);
            TextView t = Ui.text(c, name, 15, Ui.FG, true);
            t.setTypeface(Ui.font(c, 600));
            t.setSingleLine(true);
            state = Ui.text(c, "", 12.5f, Ui.FG2, false);
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
            circle.setBackground(Ui.round(on ? Ui.ACCENT : 0x26FFFFFF, Ui.dp(getContext(), 22)));
            state.setText(s);
        }
    }

    /** A round toggle with a two-line caption. */
    static final class Round extends LinearLayout {
        private final PanelIcons icon;

        Round(Context c, int type, String name) {
            super(c);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            icon = new PanelIcons(c, type, Ui.FG);
            int p = Ui.dpi(c, 15);
            icon.setPadding(p, p, p, p);
            addView(icon, Ui.lp(Ui.dpi(c, 56), Ui.dpi(c, 56)));
            TextView t = Ui.text(c, name, 11.5f, Ui.FG2, false);
            t.setGravity(Gravity.CENTER);
            t.setLineSpacing(0, 1.05f);
            addView(t, Ui.lp(-2, -2, Ui.dpi(c, 7)));
            setClickable(true);
            Ui.pressable(this);
            setActive(false);
        }

        void setActive(boolean on) {
            icon.setBackground(Ui.round(on ? Ui.ACCENT : 0x1FFFFFFF, Ui.dp(getContext(), 28)));
        }
    }

    /** A wide pill slider; drag anywhere on it, relative to where you started. */
    static final class Slider extends FrameLayout {
        interface Change { void onChange(float value, boolean done); }

        Change listener;
        private float value;
        private float downX, startValue;
        private boolean moving;
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final PanelIcons icon;

        Slider(Context c, int type) {
            super(c);
            setWillNotDraw(false);
            track.setColor(0x1FFFFFFF);
            fill.setColor(0xF0FFFFFF);
            icon = new PanelIcons(c, type, Ui.INK);
            int p = Ui.dpi(c, 15);
            icon.setPadding(p, p, p, p);
            addView(icon, new LayoutParams(Ui.dpi(c, 54), Ui.dpi(c, 54), Gravity.START | Gravity.CENTER_VERTICAL));
        }

        void setValue(float v) {
            value = Math.max(0f, Math.min(1f, v));
            icon.setColor(value > 0.12f ? Ui.INK : Ui.FG);
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2f;
            c.drawRoundRect(0, 0, w, h, r, r, track);
            float fw = h + (w - h) * value; // never thinner than a circle
            c.drawRoundRect(0, 0, fw, h, r, r, fill);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    startValue = value;
                    moving = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!moving && Math.abs(e.getX() - downX) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                        moving = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (moving) {
                        setValue(startValue + (e.getX() - downX) / Math.max(1, getWidth() - getHeight()));
                        if (listener != null) listener.onChange(value, false);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moving) setValue(Math.max(0f, (e.getX() - getHeight() / 2f) / Math.max(1, getWidth() - getHeight())));
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
