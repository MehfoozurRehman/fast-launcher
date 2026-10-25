package app.fastlauncher;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.DisplayMetrics;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The control panel's own screen recorder (Honor's recorder only accepts system callers).
 * Android asks for consent each time; video goes to Movies/Screen recordings in the gallery.
 */
final class Recorder {
    private Recorder() {}

    static volatile boolean recording;
    static Runnable onChange;

    static void toggle(Context c) {
        if (recording) {
            c.startService(new Intent(c, Service_.class).setAction(Service_.STOP));
        } else {
            c.startActivity(new Intent(c, Consent.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    private static void changed() {
        Runnable r = onChange;
        if (r != null) new Handler(Looper.getMainLooper()).post(r);
    }

    /** Invisible activity that only shows Android's "start recording?" dialog. */
    public static class Consent extends Activity {
        @Override protected void onCreate(Bundle b) {
            super.onCreate(b);
            MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(m.createScreenCaptureIntent(), 1);
        }

        @Override protected void onActivityResult(int code, int result, Intent data) {
            if (result == RESULT_OK && data != null) {
                Intent i = new Intent(this, Service_.class).setAction(Service_.START)
                        .putExtra("result", result).putExtra("data", data);
                startForegroundService(i);
            }
            finish();
            overridePendingTransition(0, 0);
        }
    }

    /** Foreground service that owns the capture while it runs. */
    public static class Service_ extends Service {
        static final String START = "app.fastlauncher.REC_START", STOP = "app.fastlauncher.REC_STOP";
        private MediaProjection projection;
        private VirtualDisplay display;
        private MediaRecorder rec;
        private Uri out;
        private ParcelFileDescriptor pfd;

        @Override public IBinder onBind(Intent i) { return null; }

        @Override public int onStartCommand(Intent i, int flags, int id) {
            if (i == null || STOP.equals(i.getAction())) { stop(); return START_NOT_STICKY; }
            if (START.equals(i.getAction()) && !recording) start(i);
            return START_NOT_STICKY;
        }

        private void start(Intent i) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("rec", "Screen recording", NotificationManager.IMPORTANCE_LOW));
            PendingIntent stop = PendingIntent.getService(this, 0, new Intent(this, Service_.class).setAction(STOP),
                    PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(this, "rec")
                    .setSmallIcon(android.R.drawable.presence_video_online)
                    .setContentTitle("Recording the screen")
                    .setContentText("Tap Stop, or the Screen record button in the control panel")
                    .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                    .setOngoing(true)
                    .build();
            startForeground(41, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

            try {
                DisplayMetrics dm = new DisplayMetrics();
                ((DisplayManager) getSystemService(DISPLAY_SERVICE)).getDisplay(0).getRealMetrics(dm);
                // 720p-class keeps encoding light on this SoC; even dimensions for the encoder
                float scale = Math.min(1f, 1280f / Math.max(dm.widthPixels, dm.heightPixels) * 1.5f);
                int w = (Math.round(dm.widthPixels * scale) / 2) * 2, h = (Math.round(dm.heightPixels * scale) / 2) * 2;

                ContentValues v = new ContentValues();
                v.put(MediaStore.Video.Media.DISPLAY_NAME, "Screen_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4");
                v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                v.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Screen recordings");
                v.put(MediaStore.Video.Media.IS_PENDING, 1);
                out = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
                pfd = getContentResolver().openFileDescriptor(out, "w");

                rec = new MediaRecorder(this);
                rec.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                rec.setVideoSize(w, h);
                rec.setVideoFrameRate(30);
                rec.setVideoEncodingBitRate(6_000_000);
                rec.setOutputFile(pfd.getFileDescriptor());
                rec.prepare();

                MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                projection = m.getMediaProjection(i.getIntExtra("result", 0), i.getParcelableExtra("data"));
                projection.registerCallback(new MediaProjection.Callback() {
                    @Override public void onStop() { stop(); }
                }, new Handler(Looper.getMainLooper()));
                display = projection.createVirtualDisplay("FastLauncherRecord", w, h, dm.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rec.getSurface(), null, null);
                rec.start();
                recording = true;
                changed();
            } catch (Exception e) {
                Diag.log("recording failed: " + e);
                stop();
            }
        }

        private void stop() {
            // a recording that never started, or was stopped before the first frame, isn't a
            // video: delete it instead of leaving a broken file in the gallery
            boolean good = recording;
            if (rec != null) {
                try { rec.stop(); } catch (Exception e) { good = false; }
                rec.release();
                rec = null;
            }
            if (display != null) { display.release(); display = null; }
            if (projection != null) { MediaProjection p = projection; projection = null; p.stop(); }
            try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
            pfd = null;
            if (out != null) {
                try {
                    if (good) {
                        ContentValues v = new ContentValues();
                        v.put(MediaStore.Video.Media.IS_PENDING, 0);
                        getContentResolver().update(out, v, null, null);
                    } else {
                        getContentResolver().delete(out, null, null);
                    }
                } catch (Exception ignored) {}
                out = null;
            }
            recording = false;
            changed();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }
}
