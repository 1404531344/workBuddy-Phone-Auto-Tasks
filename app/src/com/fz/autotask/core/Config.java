package com.fz.autotask.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** 配置读写（SharedPreferences 是私有目录，root 才能看）。 */
public final class Config {

    public static final String SP = "autotask";

    public static final String K_TIME = "time";            // "04:00"
    public static final String K_ENABLED = "enabled";      // JSONArray of module id
    public static final String K_LAST = "last_result";     // String
    public static final String K_LAST_AT = "last_at";      // long
    public static final String K_WB_ACCOUNTS = "wb_accounts"; // JSONArray
    public static final String K_WB_BACKUP = "wb_accounts_backup"; // 账号数组的副本，主数据坏了自动回退
    public static final String K_PENDING = "pending_state";
    public static final String K_IMPORT_STAMP = "import_stamp"; // import.json 的长度:修改时间
    public static final String K_TRAVEL_NEXT = "travel_next"; // 下一次旅行到点的 epoch 秒
    public static final String K_AUTO = "auto_on";           // 是否开启「每天自动运行」，默认关
    public static final String K_SELFTEST_AT = "selftest_at";     // 自检闹钟计划时间
    public static final String K_SELFTEST_FIRED = "selftest_fired"; // 自检实际触发时间

    private final SharedPreferences sp;

    public Config(Context ctx) {
        this.sp = ctx.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    /** 下一次旅行到点时间（epoch 秒），0 = 不需要回访。 */
    public long travelNextAt() {
        return sp.getLong(K_TRAVEL_NEXT, 0L);
    }

    public void setTravelNextAt(long sec) {
        sp.edit().putLong(K_TRAVEL_NEXT, sec).apply();
    }
    /** 自动任务开关（默认关：只手动点）。 */
    public boolean autoOn() {
        return sp.getBoolean(K_AUTO, false);
    }

    public void setAutoOn(boolean on) {
        sp.edit().putBoolean(K_AUTO, on).apply();
    }

    public long selfTestAt() {
        return sp.getLong(K_SELFTEST_AT, 0L);
    }

    public void setSelfTestAt(long ms) {
        sp.edit().putLong(K_SELFTEST_AT, ms).apply();
    }

    public long selfTestFired() {
        return sp.getLong(K_SELFTEST_FIRED, 0L);
    }

    public void setSelfTestFired(long ms) {
        sp.edit().putLong(K_SELFTEST_FIRED, ms).apply();
    }

    public String time() {
        return sp.getString(K_TIME, "04:00");
    }

    public void setTime(String t) {
        sp.edit().putString(K_TIME, t).apply();
    }

    public boolean isEnabled(String moduleId) {
        JSONArray a = enabledArray();
        for (int i = 0; i < a.length(); i++) {
            if (moduleId.equals(a.optString(i))) {
                return true;
            }
        }
        return false;
    }

    public void setEnabled(String moduleId, boolean on) {
        JSONArray a = enabledArray();
        JSONArray out = new JSONArray();
        boolean has = false;
        for (int i = 0; i < a.length(); i++) {
            String id = a.optString(i);
            if (moduleId.equals(id)) {
                has = true;
                if (on) {
                    out.put(id);
                }
            } else {
                out.put(id);
            }
        }
        if (on && !has) {
            out.put(moduleId);
        }
        sp.edit().putString(K_ENABLED, out.toString()).apply();
    }

    private JSONArray enabledArray() {
        try {
            return new JSONArray(sp.getString(K_ENABLED, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public String lastResult() {
        return sp.getString(K_LAST, "");
    }

    public long lastAt() {
        return sp.getLong(K_LAST_AT, 0L);
    }

    public void setLast(String text) {
        sp.edit().putString(K_LAST, text).putLong(K_LAST_AT, System.currentTimeMillis()).apply();
    }

    /** 正在等浏览器授权的 OAuth state（登录回来之后靠它把 token 取回来）。 */
    public String pendingState() {
        String s = sp.getString(K_PENDING, "");
        return s == null ? "" : s;
    }

    /** 最近几次授权的 state（最多 3 个）：浏览器可能停在旧页面，登录完成的是旧 state。 */
    public java.util.List<String> pendingStates() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        String s = sp.getString(K_PENDING, "");
        if (s == null) {
            return out;
        }
        for (String p : s.split(",")) {
            String v = p.trim();
            if (!v.isEmpty() && !out.contains(v)) {
                out.add(v);
            }
        }
        return out;
    }

    /** 追加一个 state，保留最近 3 个。 */
    public void addPendingState(String state) {
        if (state == null || state.isEmpty()) {
            return;
        }
        java.util.List<String> cur = pendingStates();
        cur.remove(state);
        cur.add(0, state);
        while (cur.size() > 3) {
            cur.remove(cur.size() - 1);
        }
        sp.edit().putString(K_PENDING, android.text.TextUtils.join(",", cur)).apply();
    }

    /** 只清掉指定 state（登录成功后）。 */
    public void removePendingState(String state) {
        java.util.List<String> cur = pendingStates();
        cur.remove(state);
        sp.edit().putString(K_PENDING, android.text.TextUtils.join(",", cur)).apply();
    }
    public void setPendingState(String s) {
        sp.edit().putString(K_PENDING, s == null ? "" : s).apply();
    }
    public JSONArray wbAccounts() {
        String s = sp.getString(K_WB_ACCOUNTS, "[]");
        try {
            return new JSONArray(s);
        } catch (Exception e) {
            // 主数据损坏/被清空 → 回退到副本，避免"账号凭空消失"
            try {
                JSONArray b = new JSONArray(sp.getString(K_WB_BACKUP, "[]"));
                sp.edit().putString(K_WB_ACCOUNTS, b.toString()).apply();
                return b;
            } catch (Exception e2) {
                return new JSONArray();
            }
        }
    }
    /** 首页被「删除」掉的模块 id（不删数据，只是不在首页显示，可再从「添加模块」加回）。 */
    public java.util.Set<String> hiddenModules() {
        java.util.Set<String> out = new java.util.HashSet<>();
        String s = sp.getString("modules_hidden", "[]");
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                out.add(a.optString(i));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public void setHiddenModules(java.util.Set<String> ids) {
        JSONArray a = new JSONArray();
        for (String id : ids) {
            a.put(id);
        }
        sp.edit().putString("modules_hidden", a.toString()).apply();
    }
    public void saveWbAccounts(JSONArray arr) {
        sp.edit()
                .putString(K_WB_ACCOUNTS, arr.toString())
                .putString(K_WB_BACKUP, arr.toString())
                .apply();
    }
    public String importStamp() {
        return sp.getString(K_IMPORT_STAMP, "");
    }

    public void setImportStamp(String s) {
        sp.edit().putString(K_IMPORT_STAMP, s).apply();
    }

    public long importAt() {
        return sp.getLong("import_at", 0L);
    }

    public void setImportAt(long t) {
        sp.edit().putLong("import_at", t).apply();
    }

    /** 找到 uid 对应账号的下标，-1 = 没有。 */    public int indexOfAccount(String uid) {
        JSONArray a = wbAccounts();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && uid.equals(o.optString("uid"))) {
                return i;
            }
        }
        return -1;
    }
}
