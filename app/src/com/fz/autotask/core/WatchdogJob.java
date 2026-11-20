package com.fz.autotask.core;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/**
 * 闹钟看门狗：JobScheduler 的周期任务（6 小时一次，可跨重启持久化）。
 *
 * 存在的理由：国产 ROM（ColorOS/MIUI 等）在「强停 / 深度清理」时会把 AlarmManager 里
 * 的闹钟直接抹掉，而闹钟本身没有任何「我没了」的回调。JobScheduler 的 persisted job
 * 存活率明显高于 alarm，所以用它在后台定期检查：
 *   - 系统里查不到 ACTION_RUN 的 PendingIntent
 *   - 或者 nextRunAt 已经过期很久
 * 就重新排一次，避免「几天不开 App 就几天不跑」。
 */
public class WatchdogJob extends JobService {

    private static final String TAG = "AutoTask";
    private static final int JOB_ID = 4901;
    /** 6 小时。系统允许的最小周期是 15 分钟，这里没必要更密。 */
    private static final long PERIOD_MS = 6 * 60 * 60 * 1000L;

    /** 幂等注册：已经有这个 job 就什么都不做。 */
    public static void ensure(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) {
                return;
            }
            for (JobInfo j : js.getAllPendingJobs()) {
                if (j.getId() == JOB_ID) {
                    return;
                }
            }
            JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, WatchdogJob.class))
                    .setPersisted(true)
                    .setPeriodic(PERIOD_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                    .build();
            js.schedule(job);
        } catch (Throwable e) {
            Log.w(TAG, "watchdog schedule failed", e);
        }
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    check(WatchdogJob.this);
                } catch (Throwable e) {
                    Log.w(TAG, "watchdog check failed", e);
                } finally {
                    jobFinished(params, false);
                }
            }
        }, "autotask-watchdog").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;   // 被系统打断就重排
    }

    private static void check(Context ctx) {
        Config cfg = new Config(ctx);
        if (!cfg.autoOn()) {
            return;
        }
        long now = System.currentTimeMillis();
        // 正在跑（或刚跑完）的时候 alarm 是空的，别误判
        long lastRun = RunLog.lastRunAt(ctx);
        if (lastRun > 0 && now - lastRun < 10 * 60 * 1000L) {
            return;
        }
        long next = cfg.nextRunAt();
        boolean alive = AutoCheck.alarmScheduled(ctx);
        boolean overdue = next > 0 && next < now - 30 * 60 * 1000L;
        if (alive && !overdue) {
            return;
        }
        long when = AlarmScheduler.scheduleNext(ctx, cfg.times());
        Log.i(TAG, "watchdog rescheduled: alive=" + alive + " next=" + next + " -> " + when);
    }
}
