package com.fz.autotask.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.fz.autotask.MainActivity;

/** 通知：一个「运行中」的低优先级常驻通知（只在跑的那几十秒存在）+ 一条汇总通知。 */
public final class Notifier {

    public static final String CH_RUN = "run";
    public static final String CH_DONE = "done2";
    public static final int ID_RUN = 1001;
    public static final int ID_DONE = 1002;

    private Notifier() {
    }

    public static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel run = new NotificationChannel(CH_RUN, "正在执行任务", NotificationManager.IMPORTANCE_MIN);
        run.setShowBadge(false);
        run.setSound(null, null);
        run.enableVibration(false);
        nm.createNotificationChannel(run);

        // 旧通道（DEFAULT，不会弹横幅）删掉，换成高优先级：系统才给横幅 + 声音 + 震动
        try {
            nm.deleteNotificationChannel("done");
        } catch (Exception ignored) {
        }
        NotificationChannel done = new NotificationChannel(CH_DONE, "任务结果（提醒）", NotificationManager.IMPORTANCE_HIGH);
        done.setShowBadge(true);
        done.setDescription("每天自动任务跑完后弹一条汇总");
        done.enableVibration(true);
        done.setVibrationPattern(new long[]{0, 250, 150, 250});
        done.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(done);
    }

    @SuppressWarnings("deprecation")
    private static Notification.Builder base(Context ctx, String channel) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(ctx, channel);
        } else {
            b = new Notification.Builder(ctx);
        }
        return b.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setPriority(Notification.PRIORITY_HIGH);
    }

    /** 运行中的常驻通知（IMPORTANCE_MIN，不响不弹）。 */
    public static Notification running(Context ctx) {
        ensureChannels(ctx);
        return base(ctx, CH_RUN)
                .setContentTitle("自动任务执行中")
                .setContentText("正在依次运行模块…")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    /** 一条汇总通知（InboxStyle 展开成多行）。 */
    public static void summary(Context ctx, String title, String[] lines) {
        ensureChannels(ctx);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        Intent i = new Intent(ctx, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        Notification.Builder b = base(ctx, CH_DONE)
                .setContentTitle(title)
                .setContentText(lines.length > 0 ? lines[0] : "")
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                // 同一 id 重复 post 时只更新不重复响铃：手动连点、补跑、看门狗重排都不会连环炸
                .setOnlyAlertOnce(true)
                .setContentIntent(pi);

        if (lines.length > 1) {
            Notification.InboxStyle st = new Notification.InboxStyle();
            for (int k = 0; k < lines.length && k < 8; k++) {
                st.addLine(lines[k]);
            }
            st.setSummaryText(lines.length + " 个模块");
            b.setStyle(st);
        }
        nm.notify(ID_DONE, b.build());
    }

    public static void clearRunning(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(ID_RUN);
        }
    }
}
