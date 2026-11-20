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

    /** 单账号跑一次的结果。 */
    public static final class R {
        public boolean ok;          // 状态机走完了（没抛异常）
        public boolean waiting;     // 正在旅行中
        public boolean departed;    // 这次新派出了
        public int claimed;         // 这次领到的积分
        public long arriveAt;       // 当前这一趟的到点时间（epoch 秒）
        public long nextAt;         // 下一次需要回访的时间（epoch 秒）
        public String where = "";
        public String err = "";
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
        String waitInfo = "";
        StringBuilder err = new StringBuilder();
        JSONArray updated = new JSONArray();

        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            WorkBuddyAccount a = WorkBuddyAccount.from(o);
            R r = runOne(a);
            if (r.ok) {
                done++;
            }
            claimedCredit += r.claimed;
            if (r.departed) {
                departed++;
            }
            if (r.waiting) {
                waiting++;
                if (waitInfo.isEmpty() && r.arriveAt > 0) {
                    waitInfo = "预计 " + WorkBuddyTravel.fmtArrive(r.arriveAt) + " 到达";
                }
            }
            if (r.nextAt > 0 && (nextAt == 0 || r.nextAt < nextAt)) {
                nextAt = r.nextAt;
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

    /**
     * 跑一个账号的旅行状态机：到点就领奖并接着派下一趟，未派出就挑最远的地点出发。
     * 「一键签到」（账号页）也用它，保证签到、旅行、活跃三件事一次做完。
     */
    public static R runOne(WorkBuddyAccount a) {
        R r = new R();
        long now = System.currentTimeMillis() / 1000L;
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
            r.where = st.locationName;

            if (WorkBuddyTravel.ARRIVED.equals(st.state)) {
                int got;
                try {
                    got = WorkBuddyTravel.claim(a, st.recordId);
                } catch (WorkBuddyClient.Unauthorized ue) {
                    WorkBuddyClient.refresh(a);
                    got = WorkBuddyTravel.claim(a, st.recordId);
                }
                if (got > 0) {
                    r.claimed = got;
                }
                r.ok = true;
                // 领完奖立刻再派一轮：否则要等到下一次运行才重新出发
                try {
                    WorkBuddyTravel.State st2 = WorkBuddyTravel.status(a);
                    if (WorkBuddyTravel.IDLE.equals(st2.state) && !st2.dailyLimitReached) {
                        WorkBuddyTravel.Spot spot = pickSpot(a);
                        if (spot != null) {
                            WorkBuddyTravel.depart(a, spot.id, spot.name);
                            r.departed = true;
                            try {
                                WorkBuddyTravel.State st4 = WorkBuddyTravel.status(a);
                                if (st4.arriveAt > now) {
                                    r.arriveAt = st4.arriveAt;
                                    r.nextAt = st4.arriveAt;
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // 再派失败不影响领奖结果
                }
            } else if (WorkBuddyTravel.TRAVELING.equals(st.state)) {
                r.ok = true;
                r.waiting = true;
                r.arriveAt = st.arriveAt;
                if (st.arriveAt > now) {
                    r.nextAt = st.arriveAt;
                }
            } else if (WorkBuddyTravel.IDLE.equals(st.state)) {
                if (st.dailyLimitReached) {
                    // 今天已经派过一轮了，等明天
                    r.ok = true;
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
                    r.ok = true;
                    r.departed = true;
                    try {
                        WorkBuddyTravel.State st3 = WorkBuddyTravel.status(a);
                        if (st3.arriveAt > now) {
                            r.arriveAt = st3.arriveAt;
                            r.nextAt = st3.arriveAt;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } else {
                throw new Exception("未知状态 " + st.state);
            }
        } catch (Throwable e) {
            r.err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        return r;
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
