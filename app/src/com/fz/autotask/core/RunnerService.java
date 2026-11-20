package com.fz.autotask.core;

import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * 短时前台服务：只活到所有模块跑完（几十秒），跑完立刻 stopSelf。
 * 不是常驻服务：没有定时器、没有 socket、没有轮询。
 */
public class RunnerService extends Service {

    private PowerManager.WakeLock wake;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                startForeground(Notifier.ID_RUN, Notifier.running(this));
            } catch (Exception ignored) {
            }
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "autotask:run");
            wake.setReferenceCounted(false);
            wake.acquire(5 * 60 * 1000L);
        }
        final String act = intent == null || intent.getAction() == null
                ? TaskReceiver.ACTION_RUN : intent.getAction();
        final RunnerService self = this;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (TaskReceiver.ACTION_SELFTEST.equals(act)) {
                        new Config(self).setSelfTestFired(System.currentTimeMillis());
                        Notifier.summary(self, "后台自检通过", new String[]{
                                "不开 App 也能按时跑（这条是定时任务自己发的）"});
                    } else if (TaskReceiver.ACTION_TRAVEL.equals(act)) {
                        Runner.runTravelOnly(self);
                    } else {
                        Runner.runAll(self);
                    }
                } finally {
                    try {
                        if (wake != null && wake.isHeld()) {
                            wake.release();
                        }
                    } catch (Exception ignored) {
                    }
                    Notifier.clearRunning(self);
                    if (Build.VERSION.SDK_INT >= 24) {
                        stopForeground(STOP_FOREGROUND_REMOVE);
                    } else {
                        stopForeground(true);
                    }
                    stopSelf();
                }
            }
        }, "autotask-runner").start();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
