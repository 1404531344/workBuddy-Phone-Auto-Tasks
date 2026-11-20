package com.fz.autotask.modules.workbuddy;

import com.fz.autotask.core.Config;
import com.fz.autotask.core.Http;
import com.fz.autotask.core.Module;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;
import java.util.Map;

/**
 * 模块 3：WorkBuddy「活跃地图」——把「完成一次对话」这件事替你在服务端记一笔。
 *
 * 为什么需要它：签到（daily-checkin）和活跃（/v2/report）在服务端是两套账。
 * 签到只写 checkin_dates（积分到账），活跃地图只看 /v2/report 上报的对话事件 ——
 * 所以「签到成功但活跃地图一片空白」是正常的，两件事互不影响。
 *
 * 接口（对照桌面端 BuddySwitch 的 activity.rs）：
 *   POST {billing}/v2/report                       上报一条 chat_request_send
 *   GET  {chat}/activity/growth/streak             连登天数 / 补签卡
 *   GET  {chat}/activity/growth/heatmap            活跃格（date + score）
 *
 * 约定：userId 必填，缺了服务端回 200 但静默丢弃（连登不点亮）；同一次上报会话共用
 * 一个 conversationId，requestId 各自独立；两条之间隔 1.5 秒，避免被风控。
 */
public final class ActivityModule implements Module {

    private static final int TIMEOUT = 20000;
    /** 同一账号内两条上报之间的间隔（与桌面端 ACTIVITY_REPORT_GAP 一致）。 */
    private static final long GAP_MS = 1500L;

    @Override
    public String id() {
        return "workbuddy-activity";
    }

    @Override
    public String name() {
        return "WorkBuddy 活跃";
    }

    @Override
    public String description() {
        return "上报对话活跃，点亮活跃地图；回读连登天数";
    }

    @Override
    public String run(Config cfg) throws Exception {
        JSONArray arr = cfg.wbAccounts();
        if (arr.length() == 0) {
            throw new Exception("未配置账号");
        }
        final int count = cfg.reportCount();
        int okCount = 0;
        int sentTotal = 0;
        int streak = 0;
        int monthDays = 0;
        StringBuilder err = new StringBuilder();
        JSONArray updated = new JSONArray();

        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;   // 坏条目原样保留在下面
            }
            WorkBuddyAccount a = WorkBuddyAccount.from(o);
            R r = runOne(a, count);
            sentTotal += r.sent;
            if (r.sent > 0) {
                okCount++;
            }
            if (r.streak > streak) {
                streak = r.streak;
            }
            if (r.monthDays > monthDays) {
                monthDays = r.monthDays;
            }
            if (!r.err.isEmpty()) {
                if (err.length() > 0) {
                    err.append("；");
                }
                err.append(a.display()).append(' ').append(r.err);
            }
            updated.put(a.toJson());
        }

        cfg.saveWbAccounts(updated);

        StringBuilder sb = new StringBuilder();
        sb.append(okCount).append('/').append(arr.length()).append(" 账号，上报 ").append(sentTotal).append(" 条");
        if (streak > 0) {
            sb.append("，连登 ").append(streak).append(" 天");
        }
        if (monthDays > 0) {
            sb.append("，本月活跃 ").append(monthDays).append(" 天");
        }
        if (err.length() > 0) {
            sb.append("，失败：").append(err);
        }
        return sb.toString();
    }

    /** 单账号跑一次活跃上报的结果。 */
    public static final class R {
        public int sent;        // 成功上报的条数
        public int streak;      // 连续活跃天数
        public int monthDays;   // 本月活跃天数
        public String err = "";
    }

    /**
     * 跑一个账号：连发 count 条上报，再回读连登 / 本月活跃天数自证。
     * 「一键签到」（账号页）也用它。
     */
    public static R runOne(WorkBuddyAccount a, int count) {
        R r = new R();
        try {
            if (WorkBuddyClient.needRefresh(a)) {
                WorkBuddyClient.refresh(a);
            }
            int sent;
            try {
                sent = report(a, count);
            } catch (WorkBuddyClient.Unauthorized ue) {
                WorkBuddyClient.refresh(a);
                sent = report(a, count);
            }
            r.sent = sent;
            if (sent > 0) {
                // 回读：report 200 不等于计分，用连登 + 热力图自证闭环
                r.streak = streakDays(a);
                r.monthDays = monthActiveDays(a);
            }
        } catch (Throwable e) {
            r.err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        return r;
    }

    /** 连发 count 条上报，返回成功条数。401/403 抛 Unauthorized 让上层刷新 token 后重试。 */
    private static int report(WorkBuddyAccount a, int count) throws Exception {
        String url = WorkBuddyClient.billingBase(a.region) + "/v2/report";
        String cid = "wb2api-" + System.currentTimeMillis();
        int sent = 0;
        for (int i = 1; i <= count; i++) {
            long now = System.currentTimeMillis();
            JSONObject ev = event(cid, cid + "-r" + i, a.uid, now);
            JSONArray body = new JSONArray().put(ev);
            Http.Resp r = Http.postJson(url, WorkBuddyClient.authHeaders(a), body.toString(), TIMEOUT);
            if (r.status == 401 || r.status == 403) {
                throw new WorkBuddyClient.Unauthorized(r.body);
            }
            boolean ok = r.status == 200;
            if (ok) {
                try {
                    ok = new JSONObject(r.body).optInt("code", 0) == 0;
                } catch (Exception ignored) {
                }
            }
            if (!ok) {
                throw new Exception("活跃上报 http=" + r.status + " " + trim(r.body));
            }
            sent++;
            if (i < count) {
                try {
                    Thread.sleep(GAP_MS);
                } catch (InterruptedException ignored) {
                }
            }
        }
        return sent;
    }

    /** 一条 chat_request_send 事件（字段与桌面端 build_report_event 对齐）。 */
    private static JSONObject event(String cid, String rid, String uid, long at) throws Exception {
        JSONObject o = new JSONObject();
        o.put("eventCode", "chat_request_send");
        o.put("timestamp", at);
        o.put("reportDelay", 0);
        o.put("mode", "craft");
        o.put("conversationId", cid);
        o.put("requestId", rid);
        o.put("inputLength", 12);
        o.put("requestModelId", "deepseek-v4-flash");
        o.put("requestModelName", "DeepSeek V4 Flash");
        o.put("presentAt", at);
        o.put("rootRequestId", cid);
        o.put("parentConversationId", cid);
        o.put("agentName", "default");
        o.put("agentType", "conversation");
        o.put("userId", uid);
        return o;
    }

    /** data.streak.days；读不到返回 0。 */
    public static int streakDays(WorkBuddyAccount a) {
        try {
            Http.Resp r = Http.get(WorkBuddyClient.chatBase(a.region) + "/activity/growth/streak",
                    growthHeaders(a), TIMEOUT);
            if (r.status != 200) {
                return 0;
            }
            JSONObject d = new JSONObject(r.body).optJSONObject("data");
            if (d == null) {
                return 0;
            }
            JSONObject s = d.optJSONObject("streak");
            return s == null ? 0 : s.optInt("days", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 本月（自然月）score > 0 的天数；读不到返回 0。 */
    private static int monthActiveDays(WorkBuddyAccount a) {
        int[] hm = heat(a);
        return hm == null ? 0 : hm[1];
    }

    /** 单个账号的活跃度明细（长按火焰时展示）。 */
    public static final class Act {
        public int todayScore;
        public int monthDays;
        public int streak;
        public int makeupCards;
        public boolean ok;
    }

    /** 只读一次热力图，拿「今日活跃分 + 本月活跃天数」，给卡片上的火焰用。返回 null = 读失败。 */
    public static int[] todayAndMonth(WorkBuddyAccount a) {
        return heat(a);
    }

    /** 火焰 + 长按详情的完整数据：热力图两次之外再读一次连登。 */
    public static Act detail(WorkBuddyAccount a) {
        Act act = new Act();
        int[] hm = heat(a);
        if (hm != null) {
            act.todayScore = hm[0];
            act.monthDays = hm[1];
            act.ok = true;
        }
        try {
            Http.Resp r = Http.get(WorkBuddyClient.chatBase(a.region) + "/activity/growth/streak",
                    growthHeaders(a), TIMEOUT);
            if (r.status == 200) {
                JSONObject d = new JSONObject(r.body).optJSONObject("data");
                if (d != null) {
                    JSONObject s = d.optJSONObject("streak");
                    if (s != null) {
                        act.streak = s.optInt("days", 0);
                    }
                    JSONObject mk = d.optJSONObject("makeup_cards");
                    if (mk != null) {
                        act.makeupCards = mk.optInt("balance", 0);
                    }
                    act.ok = true;
                }
            }
        } catch (Exception ignored) {
        }
        return act;
    }

    /** @return {今日 score, 本月活跃天数}；null = 读不到。 */
    private static int[] heat(WorkBuddyAccount a) {
        try {
            Http.Resp r = Http.get(WorkBuddyClient.chatBase(a.region) + "/activity/growth/heatmap",
                    growthHeaders(a), TIMEOUT);
            if (r.status != 200) {
                return null;
            }
            JSONObject d = new JSONObject(r.body).optJSONObject("data");
            if (d == null) {
                return null;
            }
            JSONArray cells = d.optJSONArray("cells");
            if (cells == null) {
                return null;
            }
            Calendar c = Calendar.getInstance();
            String prefix = String.format(Locale.US, "%04d-%02d", c.get(Calendar.YEAR),
                    c.get(Calendar.MONTH) + 1);
            String today = String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR),
                    c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
            int days = 0;
            int todayScore = 0;
            for (int i = 0; i < cells.length(); i++) {
                JSONObject cell = cells.optJSONObject(i);
                if (cell == null) {
                    continue;
                }
                String date = cell.optString("date", "");
                int score = cell.optInt("score", 0);
                if (date.startsWith(prefix) && score > 0) {
                    days++;
                }
                if (date.equals(today)) {
                    todayScore = score;
                }
            }
            return new int[]{todayScore, days};
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, String> growthHeaders(WorkBuddyAccount a) {
        return WorkBuddyClient.growthHeaders(a);
    }

    private static String trim(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').trim();
        return t.length() > 120 ? t.substring(0, 120) + "…" : t;
    }
}
