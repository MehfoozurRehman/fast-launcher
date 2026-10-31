package app.fastlauncher;

import android.app.Activity;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.FrameMetrics;
import android.view.Window;

import java.util.ArrayList;
import java.util.List;

final class FrameMeter {
    private FrameMeter() {}

    private static HandlerThread thread;
    private static Window.OnFrameMetricsAvailableListener listener;
    private static Window window;
    private static final List<long[]> frames = new ArrayList<>();

    static synchronized void start(Activity a) {
        stopQuietly();
        frames.clear();
        window = a.getWindow();
        thread = new HandlerThread("framemeter");
        thread.start();
        listener = (w, fm, dropped) -> {
            long gpu = Build.VERSION.SDK_INT >= 31 ? fm.getMetric(FrameMetrics.GPU_DURATION) : 0;
            long deadline = Build.VERSION.SDK_INT >= 31 ? fm.getMetric(FrameMetrics.DEADLINE) : 0;
            synchronized (FrameMeter.class) {
                frames.add(new long[]{
                        fm.getMetric(FrameMetrics.VSYNC_TIMESTAMP),
                        fm.getMetric(FrameMetrics.TOTAL_DURATION),
                        fm.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) + fm.getMetric(FrameMetrics.ANIMATION_DURATION)
                                + fm.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) + fm.getMetric(FrameMetrics.DRAW_DURATION),
                        fm.getMetric(FrameMetrics.SYNC_DURATION) + fm.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION),
                        gpu, deadline, dropped});
            }
        };
        window.addOnFrameMetricsAvailableListener(listener, new Handler(thread.getLooper()));
    }

    private static void stopQuietly() {
        try { if (window != null && listener != null) window.removeOnFrameMetricsAvailableListener(listener); } catch (Exception ignored) {}
        if (thread != null) { thread.quitSafely(); thread = null; }
        listener = null;
        window = null;
    }

    static synchronized String stop() {
        stopQuietly();
        int n = frames.size();
        if (n < 3) return "FRAMES: too few (" + n + ")";
        int smooth = 0, one = 0, drops = 0, stalls = 0, intervals = 0, slow90 = 0, slow60 = 0;
        double sumTotal = 0, sumUi = 0, sumRt = 0, sumGpu = 0;
        double[] totals = new double[n];
        long activeNs = 0;
        for (int i = 0; i < n; i++) {
            long[] f = frames.get(i);
            double total = f[1] / 1e6;
            totals[i] = total;
            sumTotal += total;
            sumUi += f[2] / 1e6;
            sumRt += f[3] / 1e6;
            sumGpu += f[4] / 1e6;
            if (total > 11.1) slow90++;
            if (total > 16.7) slow60++;
            if (i > 0) {
                double gap = (f[0] - frames.get(i - 1)[0]) / 1e6;
                if (gap > 0 && gap < 100) {
                    intervals++;
                    activeNs += f[0] - frames.get(i - 1)[0];
                    if (gap <= 13.5) smooth++;
                    else if (gap <= 19) one++;
                    else if (gap <= 40) drops++;
                    else stalls++;
                }
            }
        }
        StringBuilder worst = new StringBuilder();
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (x, y) -> Double.compare(frames.get(y)[1], frames.get(x)[1]));
        for (int k = 0; k < Math.min(6, n); k++) {
            long[] f = frames.get(idx[k]);
            long t0 = frames.get(0)[0];
            worst.append(String.format(java.util.Locale.US, " [t=%.2fs total=%.1f ui=%.1f issue=%.1f gpu=%.1f]",
                    (f[0] - t0) / 1e9, f[1] / 1e6, f[2] / 1e6, f[3] / 1e6, f[4] / 1e6));
        }
        java.util.Arrays.sort(totals);
        double fps = activeNs > 0 ? intervals / (activeNs / 1e9) : 0;
        return String.format(java.util.Locale.US,
                "FRAMES: n=%d active-fps=%.1f | gaps: on-pace(<=13ms)=%.1f%% one-slot-late(13-19)=%.1f%% dropped(19-40)=%.1f%% stall(>40)=%.1f%% | "
                        + "total ms: avg=%.1f p50=%.1f p90=%.1f p99=%.1f max=%.1f | over 11.1ms=%.1f%% over 16.7ms=%.1f%% | avg ui=%.1f render-issue=%.1f gpu=%.1f",
                n, fps, 100.0 * smooth / Math.max(1, intervals), 100.0 * one / Math.max(1, intervals),
                100.0 * drops / Math.max(1, intervals), 100.0 * stalls / Math.max(1, intervals),
                sumTotal / n, totals[n / 2], totals[(int) (n * 0.9)], totals[(int) (n * 0.99)], totals[n - 1],
                100.0 * slow90 / n, 100.0 * slow60 / n, sumUi / n, sumRt / n, sumGpu / n) + " | WORST:" + worst;
    }
}
