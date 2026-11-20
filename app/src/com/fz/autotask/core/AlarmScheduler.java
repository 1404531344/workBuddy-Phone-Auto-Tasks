package com.fz.autotask.core;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;

/** 每天一次的精确闹钟（RTC_WAKEUP + setExactAndAllowWhileIdle）。 */
public final class AlarmScheduler {

    public static final int REQ = 4711;
    public static final int REQ_TRAVEL = 4712;
    public static final int REQ_SELFTEST = 4713;

    private AlarmScheduler() {
    }

    public static boolean canScheduleExact(Context ctx) {
        if (Build.VERSION.SDK_INT < 31) {
            return true;
        }
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }

    public static PendingIntent pending(Context ctx) {
        Intent i = new Intent(ctx, TaskReceiver.class);
        i.setAction(TaskReceiver.ACTION_RUN);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(ctx, REQ, i, flags);
    }

    /** 注册下一次触发；返回触发时间戳（ms），失败返回 -1。 */
    public static long schedule(Context ctx, String hhmm) {
        int[] t = parse(hhmm);
        if (t == null) {
            return -1L;
        }
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, t[0]);
        c.set(Calendar.MINUTE, t[1]);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long when = c.getTimeInMillis();
        long now = System.currentTimeMillis();
        if (when <= now + 5000L) {
            if (when + 120000L > now) {
                // 目标时间刚过不到 2 分钟：立刻补跑一次（8 秒后），不要顺延到明天
                when = now + 8000L;
            } else {
                c.add(Calendar.DAY_OF_YEAR, 1);
                when = c.getTimeInMillis();
            }
        }
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return -1L;
        }
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pending(ctx));
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, when, pending(ctx));
            }
        } catch (SecurityException e) {
            try {
                am.set(AlarmManager.RTC_WAKEUP, when, pending(ctx));
            } catch (Exception e2) {
                return -1L;
            }
        }
        return when;
    }

    public static PendingIntent travelPending(Context ctx) {
        Intent i = new Intent(ctx, TaskReceiver.class);
        i.setAction(TaskReceiver.ACTION_TRAVEL);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(ctx, REQ_TRAVEL, i, flags);
    }

    /** 到点回访一次：领奖 / 再出发。whenMs 已过则忽略。 */
    public static long scheduleTravel(Context ctx, long whenMs) {
        if (whenMs <= System.currentTimeMillis() + 30000L) {
            return -1L;
        }
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return -1L;
        }
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, travelPending(ctx));
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, whenMs, travelPending(ctx));
            }
        } catch (SecurityException e) {
            try {
                am.set(AlarmManager.RTC_WAKEUP, whenMs, travelPending(ctx));
            } catch (Exception e2) {
                return -1L;
            }
        }
        return whenMs;
    }

    public static PendingIntent selfTestPending(Context ctx) {
        Intent i = new Intent(ctx, TaskReceiver.class);
        i.setAction(TaskReceiver.ACTION_SELFTEST);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(ctx, REQ_SELFTEST, i, flags);
    }

    /** 2 分钟后的后台自检：到点能收到就说明不开 App 也能跑。 */
    public static long scheduleSelfTest(Context ctx, long whenMs) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return -1L;
        }
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, selfTestPending(ctx));
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, whenMs, selfTestPending(ctx));
            }
            return whenMs;
        } catch (Exception e) {
            try {
                am.set(AlarmManager.RTC_WAKEUP, whenMs, selfTestPending(ctx));
                return whenMs;
            } catch (Exception e2) {
                return -1L;
            }
        }
    }

    public static void cancelSelfTest(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.cancel(selfTestPending(ctx));
        }
    }

    public static void cancelTravel(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.cancel(travelPending(ctx));
        }
    }
    public static void cancel(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.cancel(pending(ctx));
        }
    }

    public static int[] parse(String hhmm) {
        try {
            String s = hhmm == null ? "" : hhmm.trim();
            // 容错：允许 0400 / 400 / 4:0 / 04:00 这几种写法
            if (s.indexOf(':') < 0 && s.indexOf('：') < 0) {
                String d = s.replaceAll("[^0-9]", "");
                if (d.length() == 3) {
                    d = "0" + d;
                }
                if (d.length() == 4) {
                    int hh = Integer.parseInt(d.substring(0, 2));
                    int mm = Integer.parseInt(d.substring(2));
                    if (hh > 23 || mm > 59) {
                        return null;
                    }
                    return new int[]{hh, mm};
                }
                return null;
            }
            int p = s.indexOf(':');
            if (p < 0) {
                p = s.indexOf('：');
            }
            if (p < 0) {
                return null;
            }
            int h = Integer.parseInt(s.substring(0, p).trim());
            int m = Integer.parseInt(s.substring(p + 1).trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) {
                return null;
            }
            return new int[]{h, m};
        } catch (Exception e) {
            return null;
        }
    }

    public static String fmt(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        Calendar t = Calendar.getInstance();
        boolean today = c.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR)
                && c.get(Calendar.YEAR) == t.get(Calendar.YEAR);
        String hm = String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
        // 不是今天就把日期带上，避免「设了 01:56 却排到明天」看不出来
        return today ? hm : (String.format("%02d-%02d ", c.get(Calendar.MONTH) + 1,
                c.get(Calendar.DAY_OF_MONTH)) + hm);
    }
}
