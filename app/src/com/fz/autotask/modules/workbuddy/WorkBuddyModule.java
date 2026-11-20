package com.fz.autotask.modules.workbuddy;

import com.fz.autotask.core.Config;
import com.fz.autotask.core.Module;

import org.json.JSONArray;
import org.json.JSONObject;

/** 模块 1：WorkBuddy / CodeBuddy 每日自动签到（支持多账号）。 */
public final class WorkBuddyModule implements Module {

    @Override
    public String id() {
        return "workbuddy-checkin";
    }

    @Override
    public String name() {
        return "WorkBuddy 模块";
    }

    @Override
    public String description() {
        return "签到 + 旅行，多账号，token 过期自动刷新";
    }

    @Override
    public String run(Config cfg) throws Exception {
        JSONArray arr = cfg.wbAccounts();
        if (arr.length() == 0) {
            throw new Exception("未配置账号");
        }

        int ok = 0;
        int signed = 0;
        int already = 0;
        int credits = 0;
        StringBuilder err = new StringBuilder();

        JSONArray updated = new JSONArray();

        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            WorkBuddyAccount a = WorkBuddyAccount.from(o);
            try {
                if (WorkBuddyClient.needRefresh(a)) {
                    WorkBuddyClient.refresh(a);
                }
                WorkBuddyClient.CheckinState st;
                try {
                    st = WorkBuddyClient.status(a);
                } catch (WorkBuddyClient.Unauthorized ue) {
                    WorkBuddyClient.refresh(a);
                    st = WorkBuddyClient.status(a);
                }

                if (st.checkedIn) {
                    already++;
                    ok++;
                } else {
                    String msg;
                    try {
                        msg = WorkBuddyClient.checkin(a);
                    } catch (WorkBuddyClient.Unauthorized ue) {
                        WorkBuddyClient.refresh(a);
                        msg = WorkBuddyClient.checkin(a);
                    }
                    if (msg.contains("已签到")) {
                        already++;
                    } else {
                        signed++;
                    }
                    credits += st.dailyCredit;
                    ok++;
                }
            } catch (Throwable e) {
                String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                if (err.length() > 0) {
                    err.append("；");
                }
                err.append(a.display()).append(' ').append(m);
            }
            updated.put(a.toJson());
        }

        // token 变了就写回（刷新后的新 token）
        cfg.saveWbAccounts(updated);

        StringBuilder sb = new StringBuilder();
        sb.append(ok).append('/').append(arr.length()).append(" 账号");
        if (signed > 0) {
            sb.append("，新签 ").append(signed).append(" 个 +").append(credits);
        } else if (already > 0) {
            sb.append("，均已签");
        }
        if (err.length() > 0) {
            sb.append("，失败：").append(err);
        }

        // 旅行是同一模块的子任务：一起跑、合成一行，通知里不出现「两个模块」
        try {
            String tr = new TravelModule().run(cfg);
            if (tr != null && tr.length() > 0) {
                sb.append("；旅行：").append(tr);
            }
        } catch (Throwable e) {
            sb.append("；旅行失败：").append(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        return sb.toString();
    }
}
