package com.fz.autotask.modules.workbuddy;

import com.fz.autotask.core.Http;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * WorkBuddy / CodeBuddy 服务端契约（从桌面端 BuddySwitch 源码逐条抄来）：
 *   状态  POST {billing}/v2/billing/meter/checkin-activity-status   （回退 /checkin-status）
 *   签到  POST {billing}/v2/billing/meter/daily-checkin
 *   刷新  POST {chat}/v2/plugin/auth/token/refresh
 */
public final class WorkBuddyClient {

    public static final String CN_BILLING = "https://www.codebuddy.cn";
    public static final String CN_CHAT = "https://copilot.tencent.com";
    public static final String GLOBAL_BASE = "https://www.workbuddy.ai";
    public static final String PREFIX = "/v2/billing/meter";
    public static final String UA = "CLI/2.63.2 CodeBuddy/2.63.2";

    public static final int TIMEOUT = 20000;

    public static final class CheckinState {
        public boolean checkedIn;
        public int dailyCredit;
        public int todayCredit;
        public int totalCredits;
        public int streakDays;
        public String raw = "";
    }

    private WorkBuddyClient() {
    }

    public static String billingBase(String region) {
        return "global".equals(region) ? GLOBAL_BASE : CN_BILLING;
    }

    public static String chatBase(String region) {
        return "global".equals(region) ? GLOBAL_BASE : CN_CHAT;
    }

    /** 通用头（Origin/Referer/UA 必须与官方客户端一致）。 */
    private static Map<String, String> common(String region) {
        String base = billingBase(region);
        Map<String, String> h = new HashMap<String, String>();
        h.put("Accept", "application/json, text/plain, */*");
        h.put("X-Requested-With", "XMLHttpRequest");
        h.put("Origin", base);
        h.put("Referer", base + "/");
        h.put("User-Agent", UA);
        return h;
    }

    /** 带鉴权的头。 */
    private static Map<String, String> auth(WorkBuddyAccount a) {
        Map<String, String> h = common(a.region);
        h.put("Authorization", "Bearer " + a.accessToken);
        if (!a.uid.isEmpty()) {
            h.put("X-User-Id", a.uid);
        }
        if (!a.enterpriseId.isEmpty()) {
            h.put("X-Enterprise-Id", a.enterpriseId);
            h.put("X-Tenant-Id", a.enterpriseId);
        }
        return h;
    }

    /** 旅行接口复用鉴权头，但换成 web 端的 Origin/Referer 与平台标记。 */
    static Map<String, String> travelHeaders(WorkBuddyAccount a) {
        Map<String, String> h = auth(a);
        String base = billingBase(a.region);
        h.put("x-client-platform", "web");
        h.put("Origin", base);
        h.put("Referer", base + "/profile/growth-center");
        return h;
    }

    // ---------- 1. 签到状态 ----------

    public static CheckinState status(WorkBuddyAccount a) throws Exception {
        String base = billingBase(a.region) + PREFIX;
        Http.Resp r = Http.postJson(base + "/checkin-activity-status", auth(a), "{}", TIMEOUT);
        if (!r.ok()) {
            // 回退端点只会给全 0（实测 200 但 today_checked_in=false），
            // 只有它真的带数据时才采纳，否则把主端点的真实错误抛出去。
            Http.Resp r2 = Http.postJson(base + "/checkin-status", auth(a), "{}", TIMEOUT);
            boolean usable = false;
            if (r2.ok()) {
                try {
                    JSONObject d2 = new JSONObject(r2.body).optJSONObject("data");
                    usable = d2 != null && (d2.optInt("total_credits", 0) > 0
                            || d2.optInt("daily_credit", 0) > 0
                            || d2.optBoolean("today_checked_in", false));
                } catch (Exception ignored) {
                }
            }
            if (usable) {
                r = r2;
            } else {
                throw new Exception("状态查询 http=" + r.status + " " + snippet(r));
            }
        }
        JSONObject doc = new JSONObject(r.body);
        int code = doc.optInt("code", -1);
        if (r.status == 401 || r.status == 403 || code == 401 || code == 403) {
            throw new Unauthorized(r.body);
        }
        if (code != 0 && code != 200) {
            throw new Exception("状态查询失败 code=" + code + " msg=" + doc.optString("msg"));
        }
        JSONObject d = doc.optJSONObject("data");
        CheckinState s = new CheckinState();
        s.raw = r.body;
        if (d != null) {
            s.checkedIn = d.optBoolean("today_checked_in", false);
            s.dailyCredit = d.optInt("daily_credit", 0);
            s.todayCredit = d.optInt("today_credit", 0);
            s.totalCredits = d.optInt("total_credits", 0);
            s.streakDays = d.optInt("streak_days", 0);
        }
        return s;
    }

    // ---------- 2. 执行签到 ----------

    /** @return 结果描述（"已签到过" / "+5" 等） */
    /** 可用积分（get-user-resource-summary 的 data.TotalCredits），拿不到返回 -1。 */
    public static double balance(WorkBuddyAccount a) {
        try {
            String base = billingBase(a.region);
            String url = base + "/billing/meter/get-user-resource-summary";
            Http.Resp r = Http.postJson(url, auth(a), "{\"productCode\":\"p_tcaca\"}", TIMEOUT);
            if (r.status >= 400 || r.body == null) {
                return -1;
            }
            JSONObject o = new JSONObject(r.body);
            JSONObject d = o.optJSONObject("data");
            if (d == null) {
                return -1;
            }
            String s = d.optString("TotalCredits", "");
            if (s.isEmpty()) {
                s = d.optString("totalCredits", "");
            }
            if (s.isEmpty()) {
                return -1;
            }
            return Double.parseDouble(s);
        } catch (Exception e) {
            return -1;
        }
    }
    public static String checkin(WorkBuddyAccount a) throws Exception {
        String url = billingBase(a.region) + PREFIX + "/daily-checkin";
        Http.Resp r = Http.postJson(url, auth(a), "{}", TIMEOUT);
        JSONObject doc = new JSONObject(r.body);
        int code = doc.optInt("code", -1);
        if (r.status == 401 || r.status == 403 || code == 401 || code == 403) {
            throw new Unauthorized(r.body);
        }
        String msg = doc.optString("msg", doc.optString("message", ""));
        if (code == 0 || code == 200) {
            JSONObject d = doc.optJSONObject("data");
            int got = d == null ? 0 : d.optInt("credit", d.optInt("daily_credit", 0));
            return got > 0 ? "签到 +" + got : "签到成功";
        }
        String low = msg.toLowerCase();
        if (msg.contains("已签到") || low.contains("repeat")) {
            return "今日已签到";
        }
        throw new Exception("签到失败 code=" + code + " " + msg + " raw=" + snippet(r));
    }

    // ---------- 3. 刷新 token ----------

    /** 刷新成功后直接写回 account 对象。 */
    public static void refresh(WorkBuddyAccount a) throws Exception {
        if (a.refreshToken.isEmpty()) {
            throw new Exception("缺少 refresh token");
        }
        String url = chatBase(a.region) + "/v2/plugin/auth/token/refresh";
        Map<String, String> h = common(a.region);
        h.put("X-Refresh-Token", a.refreshToken);
        h.put("X-Auth-Refresh-Source", "workbuddy");
        if (!a.enterpriseId.isEmpty()) {
            h.put("X-Enterprise-Id", a.enterpriseId);
        }
        Http.Resp r = Http.postJson(url, h, "{}", TIMEOUT);
        JSONObject doc = new JSONObject(r.body);
        int code = doc.optInt("code", -1);
        if (!r.ok() || code != 0) {
            throw new Exception("刷新失败 http " + r.status + " code=" + code + " " + doc.optString("msg"));
        }
        JSONObject d = doc.optJSONObject("data");
        String at = d == null ? "" : d.optString("accessToken", "");
        if (at.isEmpty()) {
            throw new Exception("刷新返回空 accessToken，请在客户端重新登录");
        }
        String rt = d.optString("refreshToken", "");
        long expIn = d.optLong("expiresIn", 0L);

        a.accessToken = at;
        if (!rt.isEmpty()) {
            a.refreshToken = rt;
        }
        if (expIn > 0) {
            a.expiresAt = System.currentTimeMillis() + expIn * 1000L;
        }
    }

    public static boolean needRefresh(WorkBuddyAccount a) {
        if (a.accessToken == null || a.accessToken.isEmpty()) {
            return true;
        }
        if (a.expiresAt <= 0) {
            return false;     // 没有过期信息就先按有效处理
        }
        return System.currentTimeMillis() > a.expiresAt - 120_000L;
    }


    private static String snippet(Http.Resp r) {
        String b = r.body == null ? "" : r.body.replace('\n', ' ').trim();
        if (b.length() > 160) {
            b = b.substring(0, 160);
        }
        return r.error.isEmpty() ? b : (r.error + " " + b);
    }

    private static String one12(String url, Map<String, String> h) throws Exception {
        javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLSv1.2");
        ctx.init(null, null, null);
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        if (c instanceof javax.net.ssl.HttpsURLConnection) {
            ((javax.net.ssl.HttpsURLConnection) c).setSSLSocketFactory(ctx.getSocketFactory());
        }
        c.setRequestMethod("POST");
        c.setConnectTimeout(TIMEOUT);
        c.setReadTimeout(TIMEOUT);
        c.setDoOutput(true);
        c.setUseCaches(false);
        c.setRequestProperty("Content-Type", "application/json");
        for (Map.Entry<String, String> e : h.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                c.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        byte[] out = "{}".getBytes("UTF-8");
        c.setFixedLengthStreamingMode(out.length);
        java.io.OutputStream os = c.getOutputStream();
        os.write(out);
        os.flush();
        os.close();
        int st = c.getResponseCode();
        java.io.InputStream is = st >= 400 ? c.getErrorStream() : c.getInputStream();
        StringBuilder sbd = new StringBuilder();
        byte[] buf = new byte[4096];
        int n;
        while (is != null && (n = is.read(buf)) > 0) {
            sbd.append(new String(buf, 0, n, "UTF-8"));
        }
        if (is != null) { is.close(); }
        c.disconnect();
        try {
            JSONObject d = new JSONObject(sbd.toString()).optJSONObject("data");
            return "http" + st + "/season=" + (d == null ? -1 : d.optInt("season", -1))
                    + "/active=" + (d != null && d.optBoolean("active"));
        } catch (Exception e) {
            return "http" + st + "/parse-err";
        }
    }

    /** 临时诊断：同一 token 用三套头各打一次状态端点，比较 season/start。 */

    public static String diagnose(WorkBuddyAccount a) {
        String url = billingBase(a.region) + PREFIX + "/checkin-activity-status";
        StringBuilder sb = new StringBuilder();
        sb.append("url=").append(url);
        try {
            java.net.InetAddress[] ips = java.net.InetAddress.getAllByName("www.codebuddy.cn");
            StringBuilder d = new StringBuilder();
            for (java.net.InetAddress ip : ips) {
                if (d.length() > 0) { d.append(','); }
                d.append(ip.getHostAddress());
            }
            sb.append(" dns=").append(d);
        } catch (Exception e) {
            sb.append(" dns=ERR");
        }
        try {
            StringBuilder p = new StringBuilder();
            p.append("ph=").append(System.getProperty("http.proxyHost", "-"))
                    .append(":").append(System.getProperty("http.proxyPort", "-"));
            try {
                java.net.Proxy px = java.net.ProxySelector.getDefault()
                        .select(new java.net.URI(url)).get(0);
                p.append(" ps=").append(px.type());
                if (px.address() != null) {
                    p.append('@').append(px.address());
                }
            } catch (Exception pe) {
                p.append(" ps=ERR");
            }
            sb.append(" [").append(p).append(']');
        } catch (Exception e) {
            sb.append(" [px err]");
        }
        try {
            Map<String, String> full = auth(a);
            sb.append(" | full:").append(one(url, full));
        } catch (Exception e) {
            sb.append(" | full:ERR ").append(e.getMessage());
        }
        try {
            Map<String, String> alt = auth(a);
            alt.put("Connection", "close");
            alt.put("Cache-Control", "no-store");
            alt.put("Accept-Encoding", "identity");
            sb.append(" | alt:").append(one(url, alt));
        } catch (Exception e) {
            sb.append(" | alt:ERR ").append(e.getMessage());
        }
        try {
            Map<String, String> ua = new HashMap<String, String>();
            ua.put("User-Agent", UA);
            sb.append(" | uaonly:").append(one(url, ua));
        } catch (Exception e) {
            sb.append(" | uaonly:ERR ").append(e.getMessage());
        }
        try {
            Map<String, String> idn = auth(a);
            idn.put("Accept-Encoding", "identity");
            sb.append(" | idn:").append(one(url, idn));
        } catch (Exception e) {
            sb.append(" | idn:ERR ").append(e.getMessage());
        }
        try {
            java.net.HttpURLConnection gc = (java.net.HttpURLConnection) new java.net.URL("https://myip.ipip.net").openConnection();
            gc.setConnectTimeout(8000);
            gc.setReadTimeout(8000);
            java.io.InputStream gis = gc.getInputStream();
            StringBuilder gsb = new StringBuilder();
            byte[] gb = new byte[512];
            int gn;
            while ((gn = gis.read(gb)) > 0) {
                gsb.append(new String(gb, 0, gn, "UTF-8"));
            }
            gis.close();
            gc.disconnect();
            sb.append(" pubip=").append(gsb.toString().trim());
        } catch (Exception e) {
            sb.append(" pubip=ERR");
        }
        try {
            Map<String, String> nu = auth(a);
            nu.remove("X-User-Id");
            sb.append(" | nouserid:").append(one(url, nu));
        } catch (Exception e) {
            sb.append(" | nouserid:ERR ").append(e.getMessage());
        }
        try {
            Map<String, String> wd = auth(a);
            wd.put("X-Domain", "www.codebuddy.cn");
            sb.append(" | withdomain:").append(one(url, wd));
        } catch (Exception e) {
            sb.append(" | withdomain:ERR ").append(e.getMessage());
        }
        try {
            Map<String, String> rh = auth(a);
            RawHttps.Resp rr2 = RawHttps.post(url, rh, "{}", TIMEOUT);
            String rb = rr2.body == null ? "" : rr2.body;
            String rs = "raw:http" + rr2.status;
            try {
                JSONObject dj = new JSONObject(rb).optJSONObject("data");
                rs = rs + "/season=" + (dj == null ? -1 : dj.optInt("season", -1))
                        + "/active=" + (dj != null && dj.optBoolean("active"));
            } catch (Exception ej) {
                rs = rs + "/err " + rr2.error;
            }
            sb.append(" | ").append(rs);
        } catch (Exception e) {
            sb.append(" | raw:ERR ").append(e.getMessage());
        }
        try {
            Map<String, String> t12 = auth(a);
            sb.append(" | t12:").append(one12(url, t12));
        } catch (Exception e) {
            sb.append(" | t12:ERR ").append(e.getMessage());
        }
        return sb.toString();
    }

    private static String one(String url, Map<String, String> h) throws Exception {
        Http.Resp r = Http.postJson(url, h, "{}", TIMEOUT);
        String b = r.body == null ? "" : r.body;
        StringBuilder hd = new StringBuilder();
        try {
            for (Map.Entry<String, String> e : r.headers.entrySet()) {
                String k = e.getKey() == null ? "" : e.getKey().toLowerCase();
                if (k.contains("tenant") || k.contains("env") || k.contains("cluster")
                        || k.contains("region") || k.contains("ver") || k.contains("route")) {
                    if (hd.length() > 0) { hd.append(','); }
                    hd.append(k).append('=').append(e.getValue());
                }
            }
        } catch (Exception ignored) {
        }
        try {
            JSONObject d = new JSONObject(b).optJSONObject("data");
            if (d == null) {
                return "http" + r.status + "/nodata";
            }
            return "http" + r.status + "/season=" + d.optInt("season", -1)
                    + "/active=" + d.optBoolean("active")
                    + "/start=" + d.optString("start_time")
                    + (hd.length() > 0 ? "/hdr=" + hd : "");
        } catch (Exception e) {
            return "http" + r.status + "/parse-err:" + (b.length() > 60 ? b.substring(0, 60) : b);
        }
    }

    /** 401/403 专用异常。 */
    public static final class Unauthorized extends Exception {
        public Unauthorized(String m) {
            super("未授权：" + m);
        }
    }
}
