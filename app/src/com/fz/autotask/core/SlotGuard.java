package com.fz.autotask.core;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 时段槽（slot）：「最近一个已经到点的计划时间」，形如 "2026-11-22 00:00"。
 *
 * 存在的理由：ColorOS 等国产 ROM 在 App 长时间退到后台后会把整个进程冻结，
 * 闹钟到点虽然被系统记了一次唤醒，但进程刚起来就被冻回去，模块实际没跑，
 * 而且不会有任何回调告诉你「这次没跑成」——run.log 里就是整段空白。
 *
 * 所以这里不再赌「闹钟一定成功」，改成记录「哪个槽跑过了」：
 *   1) 去重：闹钟、看门狗、打开 App 补跑 可能同时/先后触发，同一个槽只真跑一次；
 *   2) 补跑：槽没跑过就说明被系统吞了，之后任何一次唤醒（含用户打开 App）立刻补。
 */
public final class SlotGuard {

    private static final Object LOCK = new Object();
    private static boolean running;
    private static String runningSlot = "";

    private SlotGuard() {
    }

    /** 最近一个已到点的计划时间；times 里没有合法时间返回 null。 */
    public static String currentSlot(java.util.List<String> times, long now) {
        if (times == null || times.isEmpty()) {
            return null;
        }
        long best = Long.MIN_VALUE;
        for (String s : times) {
            int[] t = AlarmScheduler.parse(s);
            if (t == null) {
                continue;
            }
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(now);
            c.set(Calendar.HOUR_OF_DAY, t[0]);
            c.set(Calendar.MINUTE, t[1]);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            long at = c.getTimeInMillis();
            // 30 秒内的算「刚到点」，超过就是还没到点 → 取昨天那一轮
            if (at > now + 30000L) {
                at -= 24 * 60 * 60 * 1000L;
            }
            if (at > best) {
                best = at;
            }
        }
        if (best == Long.MIN_VALUE) {
            return null;
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date(best));
    }

    public static boolean done(Config cfg, String slot) {
        return slot != null && slot.equals(cfg.doneSlot());
    }

    /** 自动任务开着，且最近一个时段没跑过（= 被系统吞了）。 */
    public static boolean missed(Config cfg, long now) {
        if (!cfg.autoOn()) {
            return false;
        }
        String s = currentSlot(cfg.times(), now);
        return s != null && !s.equals(cfg.doneSlot());
    }

    /** 抢执行权：已经在跑、或这个时段已经跑过，都返回 false。 */
    public static boolean begin(Config cfg, String slot) {
        synchronized (LOCK) {
            if (running) {
                return false;
            }
            if (slot != null && slot.equals(cfg.doneSlot())) {
                return false;
            }
            running = true;
            runningSlot = slot == null ? "" : slot;
            return true;
        }
    }

    /** 跑完登记；ok=false 不登记，留给下一次补跑。 */
    public static void end(Config cfg, boolean ok) {
        synchronized (LOCK) {
            if (ok && runningSlot.length() > 0) {
                cfg.setDoneSlot(runningSlot);
            }
            running = false;
            runningSlot = "";
        }
    }

    public static boolean isRunning() {
        synchronized (LOCK) {
            return running;
        }
    }
}
