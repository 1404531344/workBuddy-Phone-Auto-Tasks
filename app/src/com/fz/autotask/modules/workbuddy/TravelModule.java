package com.fz.autotask.modules.workbuddy;

import com.fz.autotask.core.Config;
import com.fz.autotask.core.Module;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * 模块 2：WorkBuddy「派猫猫旅行」。
 *
 * 每天跟签到一起跑一遍，按服务端状态决定动作：
 *   arrived   → 领奖
 *   traveling → 只报告预计到达时间（等下一次运行再领）
 *   idle      → 没到每日上限就挑一个地点派出去
 */
public final class TravelModule implements Module {

    @Override
    public String id() {
        return "workbuddy-travel";
    }

    @Override
    public String name() {
        return "WorkBuddy 旅行";
    }

    @Override
    public String description() {
        return "派猫猫出门旅行，到点自动领奖励";
    }

    @Override
    public String run(Config cfg) throws Exception {
        JSONArray arr = cfg.wbAccounts();
        if (arr.length() == 0) {
            throw new Exception("未配置账号");
        }

        int done = 0;
        int claimedCredit = 0;
        int departed = 0;
        int waiting = 0;
        long nextAt = 0;

        // 记下最早的「到点时间」，供闹钟在到达后回访一次

        long now = System.currentTimeMillis() / 1000L;
        String waitInfo = "";
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
                WorkBuddyTravel.State st;
                try {
                    st = WorkBuddyTravel.status(a);
                } catch (WorkBuddyClient.Unauthorized ue) {
                    WorkBuddyClient.refresh(a);
                    st = WorkBuddyTravel.status(a);
                }

                if (WorkBuddyTravel.ARRIVED.equals(st.state)) {
                    int got;
                    try {
                        got = WorkBuddyTravel.claim(a, st.recordId);
                    } catch (WorkBuddyClient.Unauthorized ue) {
                        WorkBuddyClient.refresh(a);
                        got = WorkBuddyTravel.claim(a, st.recordId);
                    }
                    if (got > 0) {
                        claimedCredit += got;
                    }
                    done++;
                    // 领完奖立刻再派一轮：否则要等到下一次 04:00 才重新出发
                    try {
                        WorkBuddyTravel.State st2 = WorkBuddyTravel.status(a);
                        if (WorkBuddyTravel.IDLE.equals(st2.state) && !st2.dailyLimitReached) {
                            WorkBuddyTravel.Spot spot = pickSpot(a);
                            if (spot != null) {
                                WorkBuddyTravel.depart(a, spot.id, spot.name);
                                departed++;
                                try {
                                    WorkBuddyTravel.State st4 = WorkBuddyTravel.status(a);
                                    if (st4.arriveAt > now && (nextAt == 0 || st4.arriveAt < nextAt)) {
                                        nextAt = st4.arriveAt;
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                        // 再派失败不影响领奖结果
                    }
                } else if (WorkBuddyTravel.TRAVELING.equals(st.state)) {
                    waiting++;
                    if (st.arriveAt > now && (nextAt == 0 || st.arriveAt < nextAt)) {
                        nextAt = st.arriveAt;
                    }
                    done++;
                    if (waitInfo.isEmpty() && st.arriveAt > 0) {
                        waitInfo = "预计 " + WorkBuddyTravel.fmtArrive(st.arriveAt) + " 到达";
                    }
                } else if (WorkBuddyTravel.IDLE.equals(st.state)) {
                    if (st.dailyLimitReached) {
                        // 今天已经派过一轮了，等明天
                        done++;
                    } else {
                        WorkBuddyTravel.Spot spot = pickSpot(a);
                        if (spot == null) {
                            throw new Exception("没有可选地点");
                        }
                        try {
                            WorkBuddyTravel.depart(a, spot.id, spot.name);
                        } catch (WorkBuddyClient.Unauthorized ue) {
                            WorkBuddyClient.refresh(a);
                            WorkBuddyTravel.depart(a, spot.id, spot.name);
                        }
                        departed++;
                        done++;
                        try {
                            WorkBuddyTravel.State st3 = WorkBuddyTravel.status(a);
                            if (st3.arriveAt > now && (nextAt == 0 || st3.arriveAt < nextAt)) {
                                nextAt = st3.arriveAt;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                } else {
                    throw new Exception("未知状态 " + st.state);
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

        cfg.saveWbAccounts(updated);
        cfg.setTravelNextAt(nextAt);

        StringBuilder sb = new StringBuilder();
        sb.append(done).append('/').append(arr.length()).append(" 账号");
        if (claimedCredit > 0) {
            sb.append("，领到 +").append(claimedCredit);
        }
        if (departed > 0) {
            sb.append("，新派出 ").append(departed).append(" 只");
        }
        if (waiting > 0) {
            sb.append("，").append(waiting).append(" 只在旅行中");
            if (!waitInfo.isEmpty()) {
                sb.append("（").append(waitInfo).append("）");
            }
        }
        if (err.length() > 0) {
            sb.append("，失败：").append(err);
        }
        return sb.toString();
    }

    /** 选一个地点：优先耗时最长的（奖励区间一样，路上久一点没关系）。 */
    private static WorkBuddyTravel.Spot pickSpot(WorkBuddyAccount a) throws Exception {
        List<WorkBuddyTravel.Spot> list = WorkBuddyTravel.spots(a);
        if (list.isEmpty()) {
            return null;
        }
        WorkBuddyTravel.Spot best = list.get(0);
        for (WorkBuddyTravel.Spot s : list) {
            if (s.maxHours > best.maxHours) {
                best = s;
            }
        }
        return best;
    }
}
