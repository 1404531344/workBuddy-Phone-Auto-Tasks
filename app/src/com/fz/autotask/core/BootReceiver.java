package com.fz.autotask.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机 / 应用更新后重新注册闹钟（闹钟不会跨重启存活）。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        if (a == null) {
            return;
        }
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                || "android.intent.action.QUICKBOOT_POWERON".equals(a)) {
            Config cfg = new Config(context);
            if (!cfg.autoOn()) {
                return;
            }
            AlarmScheduler.schedule(context, cfg.time());
            long at = cfg.travelNextAt();
            if (at * 1000L > System.currentTimeMillis()) {
                AlarmScheduler.scheduleTravel(context, at * 1000L + 120000L);
            }
        }
    }
}
