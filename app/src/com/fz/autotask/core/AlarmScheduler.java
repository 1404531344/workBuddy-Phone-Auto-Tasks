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

    /**
     * 用「系统闹钟」方式排（setAlarmClock）：国产 ROM 的「自启动/严格模式」拦截器
     * 不会掐闹钟类 alarm，这是不依赖用户去开自启动权限也能按时跑的唯一通道。
     */
    private static boolean setClock(Context ctx, long when, PendingIntent pi) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return false;
        }
        try {
            Intent show = new Intent(ctx, com.fz.autotask.MainActivity.class);
            show.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int f = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                f |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent showPi = PendingIntent.getActivity(ctx, REQ + 100, show, f);
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(when, showPi), pi);
            return true;
        } catch (Throwable e) {
            return false;
        }
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
        return schedule(ctx, java.util.Collections.singletonList(hhmm), true);
    }

    /** 多时段版本：在 times 里挑最近的一次。 */
    public static long schedule(Context ctx, java.util.List<String> times) {
        return schedule(ctx, times, true);
    }

    /**
     * 供「刚跑完，排下一次」使用：不吃那个「刚过 2 分钟就 8 秒后补跑」的宽限，
     * 否则目标时间刚过时会被反复排成 8 秒后，形成连环跑。
     */
    public static long scheduleNext(Context ctx, String hhmm) {
        return schedule(ctx, java.util.Collections.singletonList(hhmm), false);
    }

    public static long scheduleNext(Context ctx, java.util.List<String> times) {
        return schedule(ctx, times, false);
    }

    private static long schedule(Context ctx, java.util.List<String> times, boolean allowGrace) {
        long when = nextTrigger(times, System.currentTimeMillis(), allowGrace);
        if (when <= 0) {
            return -1L;
        }
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return -1L;
        }
        new Config(ctx).setNextRunAt(when);
        if (setClock(ctx, when, pending(ctx))) {
            return when;
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

    /**
     * 从 times 里算出下一次触发时刻（ms），0 = 没有合法时间。
     *
     * 规则：先看「刚过不到 2 分钟的时段」要不要补跑（allowGrace，只在用户手动改时间时给），
     * 再取今天剩下最近的一个；今天都没了就是明天最早的那个。
     */
    public static long nextTrigger(java.util.List<String> times, long now, boolean allowGrace) {
        java.util.List<int[]> parsed = new java.util.ArrayList<>();
        long earliest = 0;
        if (times != null) {
            for (String s : times) {
                int[] t = parse(s);
                if (t == null) {
                    continue;
                }
                parsed.add(t);
                long m = t[0] * 60L + t[1];
                if (earliest == 0 || m < earliest) {
                    earliest = m;
                }
            }
        }
        if (parsed.isEmpty()) {
            return 0L;
        }
        long bestToday = Long.MAX_VALUE;
        long grace = Long.MAX_VALUE;
        for (int[] t : parsed) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(now);
            c.set(Calendar.HOUR_OF_DAY, t[0]);
            c.set(Calendar.MINUTE, t[1]);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            long at = c.getTimeInMillis();
            if (at > now + 5000L) {
                if (at < bestToday) {
                    bestToday = at;
                }
            } else if (allowGrace && at + 120000L > now) {
                // 目标时间刚过不到 2 分钟：立刻补跑一次（8 秒后），不要顺延到明天
                long g = now + 8000L;
                if (g < grace) {
                    grace = g;
                }
            }
        }
        if (grace != Long.MAX_VALUE) {
            return grace;
        }
        if (bestToday != Long.MAX_VALUE) {
            return bestToday;
        }
        // 今天全过了 → 明天最早的那个
        int hh = (int) (earliest / 60);
        int mm = (int) (earliest % 60);
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, hh);
        c.set(Calendar.MINUTE, mm);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_YEAR, 1);
        return c.getTimeInMillis();
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
        if (setClock(ctx, whenMs, travelPending(ctx))) {
            return whenMs;
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
        new Config(ctx).setNextRunAt(0L);
    }

    /** times 里至少有一个合法时间才算有效。 */
    public static boolean timesValid(java.util.List<String> times) {
        if (times == null || times.isEmpty()) {
            return false;
        }
        for (String s : times) {
            if (parse(s) == null) {
                return false;
            }
        }
        return true;
    }

    /** "01:40 · 09:00 · 21:00"，给界面显示用。 */
    public static String display(java.util.List<String> times) {
        if (times == null || times.isEmpty()) {
            return "";
        }
        return android.text.TextUtils.join(" · ", times);
    }

    public static int[] parse(String hhmm) {        try {
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
