package com.fz.autotask.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 闹钟到点 → 拉起短时前台服务执行全部模块。 */
public class TaskReceiver extends BroadcastReceiver {

    public static final String ACTION_RUN = "com.fz.autotask.RUN";
    public static final String ACTION_TRAVEL = "com.fz.autotask.TRAVEL";
    public static final String ACTION_SELFTEST = "com.fz.autotask.SELFTEST";

    @Override
    public void onReceive(Context context, Intent intent) {
        final String act = intent.getAction() == null ? ACTION_RUN : intent.getAction();
        Intent svc = new Intent(context, RunnerService.class);
        svc.setAction(act);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Exception e) {
            // 系统拒绝（极少见）时退化为普通后台线程
            final Context app = context.getApplicationContext();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    if (ACTION_TRAVEL.equals(act)) {
                        Runner.runTravelOnly(app);
                    } else {
                        Runner.runAll(app);
                    }
                }
            }).start();
        }
    }
}
