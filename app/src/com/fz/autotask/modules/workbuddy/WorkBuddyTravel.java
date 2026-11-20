package com.fz.autotask.modules.workbuddy;

import com.fz.autotask.core.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 「派猫猫旅行」接口（从桌面端 BuddySwitch 的 travel.rs 逐条抄来）：
 *   GET  /activity/growth/buddy/travel/config   → 可去的地点
 *   GET  /activity/growth/buddy/travel/status   → state / 到达时间 / 记录 id
 *   POST /activity/growth/buddy/travel/depart   {"location_id": n}
 *   POST /activity/growth/buddy/travel/claim    {} 或 {"record_id": n}
 *
 * 状态机：idle --depart--> traveling --到点--> arrived --claim--> idle
 */
public final class WorkBuddyTravel {

    public static final String PREFIX = "/activity/growth/buddy/travel";
    public static final int TIMEOUT = 20000;

    public static final String IDLE = "idle";
    public static final String TRAVELING = "traveling";
    public static final String ARRIVED = "arrived";

    /** 一个地点的最小信息。 */
    public static final class Spot {
        public int id;
        public String name = "";
        public int minHours;
        public int maxHours;
    }

    /** 当前旅行状态。 */
    public static final class State {
        public String state = "";
        public int locationId;
        public String locationName = "";
        public long departAt;
        public long arriveAt;
        public boolean dailyLimitReached;
        public long recordId;
        public int rewardCredit;
        public String raw = "";
    }

    private WorkBuddyTravel() {
    }

    private static String base(WorkBuddyAccount a) {
        return WorkBuddyClient.billingBase(a.region) + PREFIX;
    }

    /** 旅行接口用 web 端的头（x-client-platform + growth-center 来源页）。 */
    private static Map<String, String> headers(WorkBuddyAccount a) {
        Map<String, String> h = WorkBuddyClient.travelHeaders(a);
        return h;
    }

    // ---------- 1. 地点列表 ----------

    public static List<Spot> spots(WorkBuddyAccount a) throws Exception {
        Http.Resp r = Http.get(base(a) + "/config", headers(a), TIMEOUT);
        JSONObject doc = new JSONObject(r.body == null ? "{}" : r.body);
        int code = doc.optInt("code", -1);
        if (r.status == 401 || r.status == 403 || code == 401 || code == 403) {
            throw new WorkBuddyClient.Unauthorized(r.body);
        }
        if (code != 0 && code != 200) {
            throw new Exception("地点查询失败 code=" + code + " " + doc.optString("msg"));
        }
        List<Spot> out = new ArrayList<Spot>();
        JSONObject d = doc.optJSONObject("data");
        if (d == null) {
            return out;
        }
        JSONArray arr = d.optJSONArray("locations");
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            Spot s = new Spot();
            s.id = o.optInt("id", 0);
            s.name = o.optString("name", "");
            s.minHours = o.optInt("duration_hours_min", 0);
            s.maxHours = o.optInt("duration_hours_max", 0);
            if (s.id > 0) {
                out.add(s);
            }
        }
        return out;
    }

    // ---------- 2. 当前状态 ----------

    public static State status(WorkBuddyAccount a) throws Exception {
        Http.Resp r = Http.get(base(a) + "/status", headers(a), TIMEOUT);
        JSONObject doc = new JSONObject(r.body == null ? "{}" : r.body);
        int code = doc.optInt("code", -1);
        if (r.status == 401 || r.status == 403 || code == 401 || code == 403) {
            throw new WorkBuddyClient.Unauthorized(r.body);
        }
        if (code != 0 && code != 200) {
            throw new Exception("旅行状态查询失败 code=" + code + " " + doc.optString("msg"));
        }
        JSONObject d = doc.optJSONObject("data");
        if (d == null) {
            throw new Exception("旅行状态返回空 data");
        }
        State s = new State();
        s.raw = r.body;
        s.state = d.optString("state", "").toLowerCase();
        s.departAt = d.optLong("depart_at", 0L);
        s.arriveAt = d.optLong("arrive_at", 0L);
        s.dailyLimitReached = d.optBoolean("daily_limit_reached", false);
        s.recordId = d.optLong("record_id", 0L);
        s.rewardCredit = d.optInt("reward_credit", 0);
        JSONObject loc = d.optJSONObject("location");
        if (loc != null) {
            s.locationId = loc.optInt("id", 0);
            s.locationName = loc.optString("name", "");
        }
        if (s.state.isEmpty()) {
            throw new Exception("旅行状态缺 state");
        }
        return s;
    }

    // ---------- 3. 派出 ----------

    /** @return "已派往 咖啡馆" 之类，失败抛异常。 */
    public static String depart(WorkBuddyAccount a, int locationId, String locationName) throws Exception {
        Http.Resp r = Http.postJson(base(a) + "/depart",
                headers(a), "{\"location_id\":" + locationId + "}", TIMEOUT);
        JSONObject doc = new JSONObject(r.body == null ? "{}" : r.body);
        int code = doc.optInt("code", -1);
        if (r.status == 401 || r.status == 403 || code == 401 || code == 403) {
            throw new WorkBuddyClient.Unauthorized(r.body);
        }
        if (code != 0 && code != 200) {
            throw new Exception("派出失败 code=" + code + " " + doc.optString("msg"));
        }
        return "已派往" + (locationName.isEmpty() ? "目的地" : locationName);
    }

    // ---------- 4. 领奖 ----------

    /** @return 领到的积分，-1 = 接口没说具体数。 */
    public static int claim(WorkBuddyAccount a, long recordId) throws Exception {
        String body = recordId > 0 ? "{\"record_id\":" + recordId + "}" : "{}";
        Http.Resp r = Http.postJson(base(a) + "/claim", headers(a), body, TIMEOUT);
        JSONObject doc = new JSONObject(r.body == null ? "{}" : r.body);
        int code = doc.optInt("code", -1);
        if (r.status == 401 || r.status == 403 || code == 401 || code == 403) {
            throw new WorkBuddyClient.Unauthorized(r.body);
        }
        if (code != 0 && code != 200) {
            throw new Exception("领奖失败 code=" + code + " " + doc.optString("msg"));
        }
        JSONObject d = doc.optJSONObject("data");
        if (d == null) {
            return -1;
        }
        return d.has("reward_credit") ? d.optInt("reward_credit", -1) : -1;
    }

    /** 把到达时间戳格式化成「今天 14:30」/「11-20 08:00」。 */
    public static String fmtArrive(long sec) {
        if (sec <= 0) {
            return "";
        }
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA);
        return f.format(new java.util.Date(sec * 1000L));
    }
}
