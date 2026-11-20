package com.fz.autotask.modules.workbuddy;

import com.fz.autotask.core.Http;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * WorkBuddy 账号授权（从桌面端 BuddySwitch 的 oauth.rs 抄来）。
 *
 *   1. 申请 state  POST {base}/v2/plugin/auth/state?platform=workbuddy   → data.state / data.authUrl
 *   2. 用户在浏览器（或应用内 WebView）里打开 authUrl 登录并同意
 *   3. 后端轮询   GET  {base}/v2/plugin/auth/token?state=xxx
 *        未完成时回 {code:11217, msg:"login ing..."}
 *        完成后回 data.accessToken / refreshToken / expiresIn / domain
 *   4. 取资料     GET  {base}/v2/plugin/login/account?state=xxx   （Bearer accessToken）
 *        → data.uid / nickname / enterpriseId
 *
 * 关键点：授权结果只靠「后端轮询」拿到，不需要浏览器回调、不需要自定义 scheme，
 * 所以手机端只要把 authUrl 丢给系统浏览器，然后一直轮询即可。
 */
public final class WorkBuddyLogin {

    public static final String BASE = "https://www.codebuddy.cn";
    public static final String PLUGIN = "/v2/plugin";
    public static final String PLATFORM = "workbuddy";
    /** 授权页是给浏览器看的，用移动 Chrome 的 UA 更稳（别用 CLI UA）。 */
    public static final String UA =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/131.0.0.0 Mobile Safari/537.36";

    private static final int TIMEOUT = 15000;

    public static final class Session {
        public String state = "";
        public String authUrl = "";
    }

    private WorkBuddyLogin() {
    }

    private static Map<String, String> baseHeaders() {
        Map<String, String> h = new HashMap<String, String>();
        h.put("Accept", "application/json, text/plain, */*");
        h.put("X-Requested-With", "XMLHttpRequest");
        h.put("Origin", BASE);
        h.put("Referer", BASE + "/");
        h.put("User-Agent", UA);
        return h;
    }

    /** 第 1 步：申请 state，拿到授权页 URL。 */
    public static Session start() throws Exception {
        String url = BASE + PLUGIN + "/auth/state?platform=" + PLATFORM;
        Http.Resp r = Http.postJson(url, baseHeaders(), "{}", TIMEOUT);
        if (!r.ok()) {
            throw new Exception("auth/state http=" + r.status + " " + brief(r.body));
        }
        JSONObject doc = new JSONObject(r.body);
        JSONObject d = doc.optJSONObject("data");
        if (d == null) {
            throw new Exception("auth/state 返回无 data: " + brief(r.body));
        }
        Session s = new Session();
        s.state = d.optString("state", "");
        s.authUrl = d.optString("authUrl", d.optString("auth_url", d.optString("url", "")));
        if (s.state.isEmpty()) {
            throw new Exception("auth/state 缺 state: " + brief(r.body));
        }
        if (s.authUrl.isEmpty()) {
            s.authUrl = BASE + "/login?platform=" + PLATFORM + "&state=" + s.state;
        }
        return s;
    }

    /** 原始轮询响应（诊断用）。返回 null 表示网络异常，否则返回 body 或 "HTTP <status> <body>"。 */
    public static String pollRaw(String state) {
        String url = BASE + PLUGIN + "/auth/token?state=" + state;
        Http.Resp r = Http.get(url, baseHeaders(), TIMEOUT);
        if (r.status != 200) {
            return "HTTP " + r.status + " " + brief(r.body == null ? r.error : r.body);
        }
        return r.body;
    }

    /** 第 3 步：轮询。返回 null = 用户还没登录完（code 11217）。 */
    public static WorkBuddyAccount poll(String state) throws Exception {
        String url = BASE + PLUGIN + "/auth/token?state=" + state;
        Http.Resp r = Http.get(url, baseHeaders(), TIMEOUT);
        if (!r.ok()) {
            return null;
        }
        JSONObject doc = new JSONObject(r.body);
        JSONObject d = doc.optJSONObject("data");
        if (d == null) {
            return null;
        }
        String at = d.optString("accessToken", d.optString("access_token", ""));
        if (at.isEmpty()) {
            return null;
        }
        WorkBuddyAccount a = new WorkBuddyAccount();
        a.region = "cn";
        a.accessToken = at;
        a.refreshToken = d.optString("refreshToken", d.optString("refresh_token", ""));
        long expAt = d.optLong("expiresAt", d.optLong("expires_at", 0L));
        long expIn = d.optLong("expiresIn", d.optLong("expires_in", 0L));
        if (expAt > 0) {
            // 兼容秒级时间戳
            a.expiresAt = expAt < 100000000000L ? expAt * 1000L : expAt;
        } else if (expIn > 0) {
            a.expiresAt = System.currentTimeMillis() + expIn * 1000L;
        }
        // 第 4 步：拿 uid / 昵称 / 企业
        try {
            Map<String, String> h = baseHeaders();
            h.put("Authorization", "Bearer " + at);
            Http.Resp ar = Http.get(BASE + PLUGIN + "/login/account?state=" + state, h, TIMEOUT);
            if (ar.ok()) {
                JSONObject ad = new JSONObject(ar.body).optJSONObject("data");
                if (ad != null) {
                    a.uid = ad.optString("uid", "");
                    String nick = ad.optString("nickname", "");
                    String mail = ad.optString("email", "");
                    a.label = nick.isEmpty() ? mail : nick;
                    a.enterpriseId = ad.optString("enterpriseId", ad.optString("enterprise_id", ""));
                }
            }
        } catch (Exception ignored) {
        }
        if (a.uid.isEmpty()) {
            // 拿不到 uid 也不能丢凭据：用 token 尾部做临时标识，等下次签到成功再说
            a.uid = "pending-" + Integer.toHexString(at.hashCode());
        }
        return a;
    }

    private static String brief(String s) {
        if (s == null) {
            return "";
        }
        s = s.replace('\n', ' ').trim();
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }
}
