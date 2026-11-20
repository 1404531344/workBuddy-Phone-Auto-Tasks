package com.fz.autotask.core;

import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * 「开启后能不能在不开 App 的情况下自己跑」的自检。
 * 只做能查的项目；ColorOS 的自启动白名单没有公开 API，只能给指引 + 用 2 分钟后的自检闹钟实测。
 */
public final class AutoCheck {

    private AutoCheck() {
    }

    /** 通知权限（Android 13+ 需要用户授权，否则收不到汇总通知）。 */
    public static boolean notifOk(Context ctx) {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            return nm != null && nm.areNotificationsEnabled();
        } catch (Exception e) {
            return true;
        }
    }

    /** 精确闹钟权限（Android 12+）。没有的话系统会把闹钟放宽到 1 小时窗口。 */
    public static boolean exactOk(Context ctx) {
        return AlarmScheduler.canScheduleExact(ctx);
    }

    /** 电池优化是否已豁免（Doze 下才敢放行精确唤醒）。 */
    public static boolean batteryOk(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    /** 闹钟是否真的排上了（能取回同一个 PendingIntent 即视为已注册）。 */
    public static boolean alarmScheduled(Context ctx) {
        try {
            Intent i = new Intent(ctx, TaskReceiver.class);
            i.setAction(TaskReceiver.ACTION_RUN);
            int flags = PendingIntentFlags.noCreate();
            android.app.PendingIntent pi =
                    android.app.PendingIntent.getBroadcast(ctx, AlarmScheduler.REQ, i, flags);
            return pi != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** 生成给用户看的多行报告。 */
    public static String report(Context ctx) {
        StringBuilder b = new StringBuilder();
        b.append("下面这些都会影响「不开 App 也能自动跑」：\n\n");

        b.append(notifOk(ctx) ? "✅ " : "❌ ").append("通知权限");
        b.append(notifOk(ctx) ? "（已开启，跑完能收到汇总）\n" : "（未开启，跑完你收不到结果）\n");

        b.append(exactOk(ctx) ? "✅ " : "⚠️ ").append("精确闹钟");
        b.append(exactOk(ctx)
                ? "（可以准点）\n"
                : "（未授权，系统会把 04:00 放宽到 04:00–05:00 之间）\n");

        b.append(batteryOk(ctx) ? "✅ " : "⚠️ ").append("电池优化豁免");
        b.append(batteryOk(ctx)
                ? "（不会被省电策略掐掉）\n"
                : "（未豁免，深度睡眠时可能被推迟或跳过）\n");

        b.append(alarmScheduled(ctx) ? "✅ " : "❌ ").append("闹钟已注册");
        b.append(alarmScheduled(ctx) ? "（系统里能查到这条定时）\n" : "（没查到，开关会重排一次）\n");

        b.append("✅ 重启后自动恢复（收到开机广播会重新排闹钟）\n\n");

        b.append("⚠️ 还需要你在系统里手动允许两项（App 无法自己开）：\n");
        b.append("1）手机管家/设置 → 应用管理 → 自动任务 → 自启动 允许\n");
        b.append("2）同页 → 耗电管理/后台运行 → 允许后台活动（或「无限制」）\n\n");

        b.append("开启后 2 分钟会有一次「后台自检」通知：收到了说明不开 App 也能跑；");
        b.append("没收到就按上面两条设置一下。");
        return b.toString();
    }

    public static void openBatterySettings(Context ctx) {
        try {
            Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            openAppDetails(ctx);
        }
    }

    public static void openExactAlarmSettings(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                i.setData(Uri.parse("package:" + ctx.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } else {
                openAppDetails(ctx);
            }
        } catch (Exception e) {
            openAppDetails(ctx);
        }
    }

    public static void openAppDetails(Context ctx) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception ignored) {
        }
    }

    /** PendingIntent flag 兼容（FLAG_NO_CREATE 从 API 23 起才有常量，低版本用原值 0x20000000）。 */
    private static final class PendingIntentFlags {
        static int noCreate() {
            int f = 0x20000000; // FLAG_NO_CREATE
            if (Build.VERSION.SDK_INT >= 23) {
                f |= 0x04000000; // FLAG_IMMUTABLE
            }
            return f;
        }
    }
}
