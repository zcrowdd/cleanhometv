package de.cleanhome.launcher;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Sicherheitsnetz fuer den Neustart: Fire OS liefert das "Start fertig"-Signal manchmal sehr spaet
 * oder gar nicht an fremde Apps. Dieser Job ist beim System gespeichert (ueberlebt Neustarts),
 * laeuft kurz nach dem Hochfahren von selbst und dann jede Minute:
 *  - startet den Home-Waechter, falls er nicht laeuft
 *  - holt CleanHome nach vorne, wenn es seit dem Einschalten noch gar nicht offen war
 */
public class KeepAliveJob extends JobService {
    private static final int ID = 4711;

    public static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            JobInfo ji = new JobInfo.Builder(ID, new ComponentName(c, KeepAliveJob.class))
                    .setPersisted(true)                 // bleibt nach dem Neustart erhalten
                    .setMinimumLatency(60 * 1000L)
                    .setOverrideDeadline(90 * 1000L)
                    .build();
            js.schedule(ji);
        } catch (Exception ignored) { }
    }

    @Override
    public boolean onStartJob(JobParameters p) {
        HomeWatchService.start(this);
        if (!MainActivity.createdThisBoot && SystemClock.elapsedRealtime() < 15 * 60 * 1000L) {
            HomeMask.show(this);
            try {
                startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) { }
        }
        schedule(this);                                 // naechste Runde
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters p) { return true; }
}
