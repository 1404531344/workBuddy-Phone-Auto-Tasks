package com.fz.autotask.core;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/**
 * 闹钟看门狗：JobScheduler 的周期任务（30 分钟一次，可跨重启持久化）。
 *
 * 存在的理由：ColorOS/MIUI 这类 ROM 在 App 长时间退到后台后会整进程冻结，
 * 闹钟到点虽然被系统记一次唤醒，但进程刚起来就被冻回去 —— run.log 里就是整段空白，
 * 而且没有任何回调告诉你「这次没跑成」。JobScheduler 的存活率明显高于 alarm，所以：
 *   1) 发现最近一个时段没跑过 → 直接补跑（这是唯一能兜住「闹钟被吞」的办法）；
 *   2) 没漏跑 → 只检查闹钟还在不在，不在就补排。
 */
public class WatchdogJob extends JobService {

    private static final String TAG = "AutoTask";
    private static final int JOB_ID = 4901;
    /** 30 分钟。系统允许的最小周期是 15 分钟；再密对省电没好处。 */
    private static final long PERIOD_MS = 30 * 60 * 1000L;

    /** 幂等注册：周期没变就什么都不做，变了（老版本 6 小时）就重排成新的。 */
    public static void ensure(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) {
                return;
            }
            for (JobInfo j : js.getAllPendingJobs()) {
                if (j.getId() == JOB_ID) {
                    if (j.getIntervalMillis() == PERIOD_MS) {
                        return;
                    }
                    js.cancel(JOB_ID);
                    break;
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

        // 1) 最近一个时段没跑过 → 说明闹钟被系统吞了，这里直接补跑
        if (SlotGuard.missed(cfg, now)) {
            String slot = SlotGuard.currentSlot(cfg.times(), now);
            Log.i(TAG, "watchdog catch-up for slot " + slot);
            Runner.runAll(ctx, true);
            return;
        }

        // 2) 没有漏跑，只管把下一轮闹钟补回来
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
