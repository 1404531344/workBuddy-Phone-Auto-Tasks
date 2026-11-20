package com.fz.autotask.core;

import android.content.Context;

import com.fz.autotask.modules.workbuddy.WorkBuddyAccount;

import org.json.JSONArray;
import org.json.JSONObject;

/** 账号落库：Activity 与后台轮询共用同一份逻辑，按 uid 覆盖。 */
public final class AccountStore {

    private AccountStore() {
    }

    /** 写入（同 uid 覆盖），返回 true = 新增，false = 覆盖更新。 */
    public static boolean save(Context ctx, WorkBuddyAccount a) {
        if (a == null || a.uid == null || a.uid.isEmpty()) {
            return false;
        }
        Config cfg = new Config(ctx);
        JSONArray cur = cfg.wbAccounts();
        JSONArray out = new JSONArray();
        boolean replaced = false;
        for (int i = 0; i < cur.length(); i++) {
            JSONObject o = cur.optJSONObject(i);
            if (o == null) {
                continue;
            }
            if (a.uid.equals(o.optString("uid"))) {
                out.put(a.toJson());
                replaced = true;
            } else {
                out.put(o);
            }
        }
        if (!replaced) {
            out.put(a.toJson());
        }
        cfg.saveWbAccounts(out);
        return !replaced;
    }
}
