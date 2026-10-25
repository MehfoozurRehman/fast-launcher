package app.fastlauncher;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Periodic check; changes the wallpaper only when the chosen interval has passed. */
public class WallJob extends JobService {

    @Override public boolean onStartJob(final JobParameters params) {
        Diag.install(this);
        if (!Walls.due(this) || Walls.isBusy()) return false;
        new Thread(() -> {
            boolean retry = false;
            try { if (Walls.due(getApplicationContext())) Walls.next(getApplicationContext(), false); }
            catch (Exception e) { retry = true; }
            jobFinished(params, retry);
        }, "wall-job").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) { return true; }
}
