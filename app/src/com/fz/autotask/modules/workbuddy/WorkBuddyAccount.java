package com.fz.autotask.modules.workbuddy;

import org.json.JSONObject;

/** 一个 WorkBuddy 账号的凭据。字段名与桌面端 accounts.json 对齐。 */
public final class WorkBuddyAccount {

    public String uid = "";
    public String label = "";
    public String accessToken = "";
    public String refreshToken = "";
    public String enterpriseId = "";
    public String region = "cn";        // cn | global
    public long expiresAt = 0L;         // access token 过期时间（ms）
    public long refreshExpiresAt = 0L;

    public static WorkBuddyAccount from(JSONObject o) {
        WorkBuddyAccount a = new WorkBuddyAccount();
        a.uid = nn(o.optString("uid", ""));
        a.label = nn(o.optString("label", ""));
        a.accessToken = nn(o.optString("accessToken", ""));
        a.refreshToken = nn(o.optString("refreshToken", ""));
        a.enterpriseId = nn(o.optString("enterpriseId", ""));
        a.region = nn(o.optString("region", "cn"));
        if (a.region.isEmpty()) {
            a.region = "cn";
        }
        a.expiresAt = o.optLong("expiresAt", 0L);
        a.refreshExpiresAt = o.optLong("refreshExpiresAt", 0L);
        return a;
    }

    /** org.json 把 JSON null 读成字符串 "null"，这里统一清掉。 */
    private static String nn(String s) {
        if (s == null || s.equals("null")) {
            return "";
        }
        return s;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("uid", uid);
            o.put("label", label);
            o.put("accessToken", accessToken);
            o.put("refreshToken", refreshToken);
            o.put("enterpriseId", enterpriseId);
            o.put("region", region);
            o.put("expiresAt", expiresAt);
            o.put("refreshExpiresAt", refreshExpiresAt);
        } catch (Exception ignored) {
        }
        return o;
    }

    public String display() {
        String n = label == null || label.isEmpty() ? uid : label;
        if (n.length() > 4) {
            n = n.substring(0, 4) + "…" + n.substring(n.length() - 4);
        }
        return n;
    }
}
