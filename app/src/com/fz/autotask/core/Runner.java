package com.fz.autotask.core;

import android.content.Context;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 依次跑所有已启用模块，最后发一条汇总通知，并把下一次闹钟排好。 */
public final class Runner {

    private static final String TAG = "AutoTask";

    private Runner() {
    }

    public static void runAll(Context ctx) {
        runAll(ctx, false);
    }

    /**
     * @param auto true = 定时任务/补跑触发：同一个时段只跑一次，跑成功才登记；
     *             false = 用户手动点（一键签到 / 长按模块），永远真跑，不参与去重。
     */
    public static void runAll(Context ctx, boolean auto) {
        if (!auto) {
            runModules(ctx, null);
            return;
        }
        final Config cfg = new Config(ctx);
        final String slot = SlotGuard.currentSlot(cfg.times(), System.currentTimeMillis());
        if (!SlotGuard.begin(cfg, slot)) {
            Log.i(TAG, "skip auto run: slot " + slot + " already done or running");
            return;
        }
        boolean ok = false;
        try {
            ok = runModules(ctx, null);
        } finally {
            SlotGuard.end(cfg, ok);
        }
    }

    /** 只跑指定 id 的模块；only 为 null 时跑全部。返回 true = 没有模块失败。 */
    public static boolean runModules(Context ctx, java.util.Collection<String> only) {
        Config cfg = new Config(ctx);
        final java.util.Set<String> sel = only == null ? null : new java.util.HashSet<>(only);
        List<String> lines = new ArrayList<String>();
        int okCount = 0;
        int failCount = 0;

        for (Module m : ModuleRegistry.all()) {
            // 模块页已不再提供开关，除示例模块外一律执行；该模块自己有账号才会真正签到
            if ("demo".equals(m.id())) {
                continue;
            }
            if (sel != null && !sel.contains(m.id())) {
                continue;
            }
            // 旅行 / 活跃都是 WorkBuddy 模块的子任务，已在模块内部跑，不当独立模块再跑一遍
            if ("workbuddy-travel".equals(m.id()) || "workbuddy-activity".equals(m.id())) {
                continue;
            }
            long t0 = System.currentTimeMillis();
            try {
                String r = m.run(cfg);
                long ms = System.currentTimeMillis() - t0;
                // 通知里不显示耗时，保持一行纯结果
                lines.add(m.name() + "：" + (r == null ? "完成" : r));
                okCount++;
            } catch (Throwable e) {
                lines.add(m.name() + "：失败 - " + shortMsg(e));
                failCount++;
                Log.w(TAG, "module " + m.id() + " failed", e);
            }
        }

        if (lines.isEmpty()) {
            lines.add("没有启用任何模块");
        }

        SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        String head = f.format(new Date()) + "  " + okCount + " 成功"
                + (failCount > 0 ? " / " + failCount + " 失败" : "");

        StringBuilder sb = new StringBuilder(head);
        for (String s : lines) {
            sb.append('\n').append(s);
        }
        cfg.setLast(sb.toString());
        RunLog.append(ctx, head, lines);

        // 手动跑成功也登记这个时段，否则看门狗/打开 App 补跑会紧接着再跑一遍
        if (failCount == 0 && cfg.autoOn()) {
            cfg.setDoneSlot(SlotGuard.currentSlot(cfg.times(), System.currentTimeMillis()));
        }

        Notifier.summary(ctx, "自动任务完成", lines.toArray(new String[0]));

        // 自动任务开着才排下一次；关闭状态啥都不排
        if (cfg.autoOn()) {
            // 这一轮已经跑掉了，先把 10 分钟后的备用唤醒撤掉，再排下一轮（下一轮会重新挂上）
            AlarmScheduler.cancelBackup(ctx);
            AlarmScheduler.scheduleNext(ctx, cfg.times());
        } else {
            AlarmScheduler.cancel(ctx);
        }
        scheduleTravelCheck(ctx, cfg);
        return failCount == 0;
    }

    /** 只跑「旅行」模块（到点回访）：不弹汇总通知，避免一天多次刷屏。 */
    public static void runTravelOnly(Context ctx) {
        Config cfg = new Config(ctx);
        for (Module m : ModuleRegistry.all()) {
            if (!"workbuddy-travel".equals(m.id())) {
                continue;
            }
            try {
                String r = m.run(cfg);
                Log.i(TAG, "travel check: " + r);
            } catch (Throwable e) {
                Log.w(TAG, "travel check failed", e);
            }
        }
        scheduleTravelCheck(ctx, cfg);
    }

    /** 按模块写回的「下一次到点时间」排一次回访闹钟；没有就不再回访。 */
    private static void scheduleTravelCheck(Context ctx, Config cfg) {
        if (!cfg.autoOn()) {
            AlarmScheduler.cancelTravel(ctx);
            return;
        }
        long at = cfg.travelNextAt();
        if (at <= 0) {
            AlarmScheduler.cancelTravel(ctx);
            return;
        }
        long when = at * 1000L + 120000L;   // 到点后 2 分钟再跑，给服务端一点余量
        long now = System.currentTimeMillis();
        if (when < now + 60000L) {
            when = now + 60000L;
        }
        AlarmScheduler.scheduleTravel(ctx, when);
    }
    private static String shortMsg(Throwable e) {
        String s = e.getMessage();
        if (s == null || s.trim().isEmpty()) {
            s = e.getClass().getSimpleName();
        }
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }
}
