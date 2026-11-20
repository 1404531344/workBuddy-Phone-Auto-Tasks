package com.fz.autotask;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import com.fz.autotask.core.AlarmScheduler;
import com.fz.autotask.core.Config;
import com.fz.autotask.core.Module;
import com.fz.autotask.core.ModuleRegistry;
import com.fz.autotask.core.Runner;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {

    private Config cfg;

    private EditText timeEdit;
    private TextView statusText;
    private TextView lastText;
    private TextView accountInfo;
    private TextView loginStatus;
    private Button selAllBtn;
    private Button sortBtn;
    private boolean onHome = true;
    private boolean sortByCredit = true;
    private final java.util.Set<String> selected = new java.util.HashSet<>();
    private EditText searchEdit;
    private final java.util.Map<String, String[]> statCache = new java.util.HashMap<>();
    /** uid -> {state, 展示文案}（旅行任务） */
    private final java.util.Map<String, String[]> travelCache = new java.util.HashMap<>();
    /** uid -> {今日活跃分, 本月活跃天数}（火焰图标用） */
    private final java.util.Map<String, int[]> actCache = new java.util.HashMap<>();
    private boolean refreshingHome = false;
    private EditText homeSearch;
    private Button homeSelAll;
    private final java.util.Set<String> modSelected = new java.util.HashSet<>();
    private LinearLayout accountsBox;
    /** 调试入口（runmod / runall）在一次进程里只执行一次。 */
    private static boolean debugHookConsumed = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        cfg = new Config(this);
        // 调试入口（排障用，不影响正常使用）：
        //   adb shell am start -n com.fz.autotask/.MainActivity --es settime 0410   → 加一个时段并重排
        //   adb shell am start -n com.fz.autotask/.MainActivity --es runmod workbuddy-activity → 只跑这个模块
        try {
            String st = getIntent() == null ? null : getIntent().getStringExtra("settime");
            if (st != null && AlarmScheduler.parse(st) != null) {
                java.util.List<String> list = cfg.times();
                int[] t = AlarmScheduler.parse(st);
                String norm = String.format("%02d:%02d", t[0], t[1]);
                if (!list.contains(norm)) {
                    list.add(norm);
                    java.util.Collections.sort(list);
                    cfg.setTimes(list);
                }
                cfg.setAutoOn(true);
                AlarmScheduler.schedule(this, cfg.times());
            }
        } catch (Throwable ignored) {
        }
        try {
            final String only = getIntent() == null ? null : getIntent().getStringExtra("runmod");
            final boolean runAll = getIntent() != null && getIntent().hasExtra("runall");
            // 一次性：抽掉 extra，避免 Activity 被重建（旋转 / 从最近任务恢复）时又跑一遍
            if (getIntent() != null) {
                getIntent().removeExtra("runmod");
                getIntent().removeExtra("runall");
            }
            if (!debugHookConsumed && (runAll || (only != null && only.length() > 0))) {
                debugHookConsumed = true;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        if (runAll) {
                            com.fz.autotask.core.Runner.runAll(MainActivity.this);
                        } else {
                            com.fz.autotask.core.Runner.runModules(MainActivity.this,
                                    java.util.Collections.singletonList(only));
                        }
                    }
                }, "autotask-debug-run").start();
            }
        } catch (Throwable ignored) {
        }
        ensurePermissions();
        autoImportIfChanged();
        migrateTimesOnce();
        // 看门狗（JobScheduler 周期任务）：闹钟被 ROM 抹掉后能自己补回来
        com.fz.autotask.core.WatchdogJob.ensure(this);
        if (cfg.autoOn() && !AlarmScheduler.timesValid(cfg.times())) {
            AlarmScheduler.cancel(this);
            AlarmScheduler.cancelTravel(this);
        } else if (cfg.autoOn()) {
            AlarmScheduler.schedule(this, cfg.times());
        } else {
            AlarmScheduler.cancel(this);
            AlarmScheduler.cancelTravel(this);
        }
        showHome();
    }

    /**
     * 一次性把老的单个时间扩成「对齐 BuddySwitch」的三个时段：
     * 保留用户原来的点，再补上桌面端签到 / 旅行用的 09:00 与 21:00。
     * 只跑一次；之后用户在界面上改成什么就是什么。
     */
    private void migrateTimesOnce() {
        if (cfg.timesMigrated()) {
            return;
        }
        cfg.setTimesMigrated(true);
        java.util.List<String> cur = cfg.times();
        if (cur.size() != 1) {
            return;   // 已经是多时段，说明配过了
        }
        java.util.List<String> next = new java.util.ArrayList<>(cur);
        for (String s : new String[]{"09:00", "21:00"}) {
            if (!next.contains(s)) {
                next.add(s);
            }
        }
        java.util.Collections.sort(next);
        cfg.setTimes(next);
    }

    /** 首页：标题 + 时间 + 一键签到 + 模块卡片（对应手绘稿）。 */
    private void showHome() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(14), dp(20), dp(20));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = new TextView(this);
        title.setText("自动任务");
        title.setTextSize(22);
        head.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView dot = new TextView(this);
        boolean on = cfg.autoOn();
        dot.setText("● " + (on ? "已开启" : "已关闭"));
        dot.setTextSize(11);
        dot.setTextColor(on ? 0xFF16AA5A : 0xFFB0B4BB);
        dot.setPadding(dp(6), dp(10), 0, 0);
        head.addView(dot);

        View gap2 = new View(this);
        head.addView(gap2, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView timeTop = new TextView(this);
        timeTop.setText("时间 " + AlarmScheduler.display(cfg.times()));
        timeTop.setTextSize(14);
        timeTop.setPadding(dp(8), dp(6), 0, dp(6));
        timeTop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editTime();
            }
        });
        head.addView(timeTop);


        root.addView(head);

        TextView sub = new TextView(this);
        sub.setText(cfg.autoOn()
                ? ("每天 " + AlarmScheduler.display(cfg.times()) + " 各跑一遍，结束后只发一条通知")
                : "已关闭自动运行：点右上角齿轮开启，也可以自己点「一键签到」");
        sub.setTextSize(11);
        sub.setPadding(0, dp(4), 0, dp(2));
        root.addView(sub);

        // 上次运行：通知可能被系统折叠/静音，这里给一条本地可数的记录
        TextView lastRun = new TextView(this);
        String lr = com.fz.autotask.core.RunLog.last(this);
        if (lr == null) {
            lastRun.setText("还没运行过");
        } else {
            int bar = lr.indexOf(" | ");
            String headPart = bar > 0 ? lr.substring(0, bar) : lr;
            int cut = headPart.indexOf("  ");
            if (cut > 0) {
                headPart = headPart.substring(cut + 2);
            }
            String first = bar > 0 ? lr.substring(bar + 3) : "";
            lastRun.setText("上次运行 " + headPart + (first.length() > 0 ? "  ·  " + first : ""));
        }
        lastRun.setTextColor(0xFF7A818C);
        lastRun.setTextSize(11);
        lastRun.setPadding(0, dp(2), 0, dp(14));
        root.addView(lastRun);

        // 漏跑检测：计划时间过了 10 分钟还没跑过 —— 基本就是被系统拦了
        long nextAt = cfg.nextRunAt();
        long lastAt = com.fz.autotask.core.RunLog.lastRunAt(this);
        if (nextAt > 0 && System.currentTimeMillis() > nextAt + 10 * 60 * 1000L && lastAt < nextAt) {
            TextView warn = new TextView(this);
            warn.setText("⚠ 定时任务被系统拦了："
                    + com.fz.autotask.core.AlarmScheduler.fmt(nextAt)
                    + " 那次没跑成。点这里开「自启动 / 允许后台运行」"
                    + "（设置 → 电池 → 应用耗电管理 → 自动任务）");
            warn.setTextColor(0xFFB42318);
            warn.setTextSize(12);
            warn.setPadding(dp(10), dp(8), dp(10), dp(8));
            android.graphics.drawable.GradientDrawable bgw = new android.graphics.drawable.GradientDrawable();
            bgw.setColor(0xFFFFF1F0);
            bgw.setCornerRadius(dp(8));
            warn.setBackground(bgw);
            warn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // 先自己救一次：重排闹钟（顺便刷新 nextRunAt），再去设置页开权限
                    long w = AlarmScheduler.schedule(MainActivity.this, cfg.times());
                    toast(w > 0 ? ("已重新排上，下次 " + AlarmScheduler.fmt(w))
                            : "重排失败，去设置里允许「自启动 / 后台运行」");
                    try {
                        android.content.Intent ai = new android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        ai.setData(android.net.Uri.parse("package:" + getPackageName()));
                        startActivity(ai);
                    } catch (Exception ignored) {
                    }
                }
            });
            root.addView(warn);
        }

        // 首页两个入口：添加模块 / 一键签到（暂不绑定功能）
        LinearLayout homeActions = new LinearLayout(this);
        homeActions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams halp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        halp.bottomMargin = dp(16);
        homeActions.setLayoutParams(halp);

        Button addModuleBtn = new Button(this);
        addModuleBtn.setText("+ 添加模块");
        addModuleBtn.setTextColor(0xFFFFFFFF);
        addModuleBtn.setTextSize(14);
        android.graphics.drawable.GradientDrawable bmg =
                new android.graphics.drawable.GradientDrawable();
        bmg.setColor(0xFF12B76A);
        bmg.setCornerRadius(dp(10));
        addModuleBtn.setBackground(bmg);
        LinearLayout.LayoutParams bmlp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        bmlp.rightMargin = dp(10);
        addModuleBtn.setLayoutParams(bmlp);
        homeActions.addView(addModuleBtn);

        Button signBtn = new Button(this);
        signBtn.setText("✓ 一键签到");
        signBtn.setTextColor(0xFFFFFFFF);
        signBtn.setTextSize(14);
        android.graphics.drawable.GradientDrawable sbg =
                new android.graphics.drawable.GradientDrawable();
        sbg.setColor(0xFF3B82F6);
        sbg.setCornerRadius(dp(10));
        signBtn.setBackground(sbg);
        signBtn.setLayoutParams(new LinearLayout.LayoutParams(0, dp(40), 1f));
        homeActions.addView(signBtn);

        root.addView(homeActions);
        addModuleBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addModuleDialog();
            }
        });

        // 首页「一键签到」= 跑所有模块里的签到/领奖动作（等同长按模块卡）
        signBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (modSelected.isEmpty()) {
                    toast("先在下面勾选要跑的模块（或者勾「全选」）");
                    return;
                }
                final java.util.ArrayList<String> picked = new java.util.ArrayList<>(modSelected);
                toast("已开始跑选中的 " + picked.size() + " 个模块");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        com.fz.autotask.core.Runner.runModules(MainActivity.this, picked);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                toast("跑完了");
                                loadStats(true);   // 跑完必须强制刷新，否则卡片上的签到/积分还是旧值
                            }
                        });
                    }
                }).start();
            }
        });

        // ---- 搜索 / 排序 / 全选 / 删除（与账号页同一套） ----
        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(android.view.Gravity.CENTER_VERTICAL);
        tools.setPadding(0, 0, 0, dp(10));

        homeSearch = new EditText(this);
        homeSearch.setHint("搜索模块名");
        homeSearch.setTextSize(13);
        homeSearch.setSingleLine(true);
        homeSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                if (!refreshingHome) {
                    refreshingHome = true;
                    String keep = s.toString();
                    showHome();
                    refreshingHome = false;
                    if (homeSearch != null) {
                        homeSearch.setText(keep);
                        homeSearch.setSelection(keep.length());
                    }
                }
            }
        });
        tools.addView(homeSearch, new LinearLayout.LayoutParams(0, dp(36), 1f));

        Button sortM = new Button(this);
        sortM.setText("名称排序 ▾");
        sortM.setTextSize(12);
        sortM.setTextColor(0xFF4A4F57);
        sortM.setBackground(null);
        sortM.setMinWidth(0);
        sortM.setMinimumWidth(0);
        sortM.setPadding(dp(8), dp(4), dp(8), dp(4));
        sortM.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toast("模块暂时只有按名称排序");
            }
        });
        tools.addView(sortM);

        homeSelAll = new Button(this);
        homeSelAll.setText("全选");
        homeSelAll.setTextSize(12);
        homeSelAll.setTextColor(0xFF4A4F57);
        homeSelAll.setBackground(null);
        homeSelAll.setMinWidth(0);
        homeSelAll.setMinimumWidth(0);
        homeSelAll.setPadding(dp(8), dp(4), dp(8), dp(4));
        homeSelAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                java.util.List<String> ids = visibleModuleIds();
                if (modSelected.containsAll(ids) && !ids.isEmpty()) {
                    modSelected.clear();
                } else {
                    modSelected.clear();
                    modSelected.addAll(ids);
                }
                refreshingHome = true;
                showHome();
                refreshingHome = false;
            }
        });
        tools.addView(homeSelAll);

        Button delM = new Button(this);
        delM.setText("🗑");
        delM.setTextSize(15);
        delM.setTextColor(0xFF4A4F57);
        delM.setBackground(null);
        delM.setMinWidth(0);
        delM.setMinimumWidth(0);
        delM.setPadding(dp(10), dp(4), dp(6), dp(4));
        delM.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (modSelected.isEmpty()) {
                    toast("先勾选要删除的模块");
                    return;
                }
                final int n = modSelected.size();
                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("从首页删除 " + n + " 个模块？")
                        .setMessage("只是不在首页显示，数据不会删；之后可以用「+ 添加模块」加回来。")
                        .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                java.util.Set<String> hid = cfg.hiddenModules();
                                hid.addAll(modSelected);
                                cfg.setHiddenModules(hid);
                                modSelected.clear();
                                refreshingHome = true;
                                showHome();
                                refreshingHome = false;
                                toast("已删除 " + n + " 个");
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        tools.addView(delM);
        root.addView(tools);

        section(root, "我的模块");
        String q = homeSearch.getText().toString().trim();
        java.util.Set<String> hidden = cfg.hiddenModules();
        java.util.List<String> shown = visibleModuleIds();
        if (shown.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(q.isEmpty() ? "还没有模块，点上面「+ 添加模块」" : "没有匹配的模块");
            empty.setTextSize(12);
            empty.setTextColor(0xFF8A8F98);
            empty.setPadding(0, dp(6), 0, dp(6));
            root.addView(empty);
        }
        for (final Module m : ModuleRegistry.all()) {
            if ("demo".equals(m.id()) || isSubModule(m.id()) || hidden.contains(m.id())) {
                continue;
            }
            if (!q.isEmpty() && !m.name().contains(q) && !m.description().contains(q)) {
                continue;
            }
            LinearLayout rowL = new LinearLayout(this);
            rowL.setOrientation(LinearLayout.HORIZONTAL);
            rowL.setGravity(android.view.Gravity.TOP);

            android.widget.CheckBox cb = new android.widget.CheckBox(this);
            cb.setChecked(modSelected.contains(m.id()));
            cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                    if (checked) {
                        modSelected.add(m.id());
                    } else {
                        modSelected.remove(m.id());
                    }
                }
            });
            rowL.addView(cb);

            View card = buildModuleCard(m);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            clp.bottomMargin = dp(10);
            rowL.addView(card, clp);

            root.addView(rowL);
        }
        if (homeSelAll != null) {
            java.util.List<String> vis = visibleModuleIds();
            homeSelAll.setText(!vis.isEmpty() && modSelected.containsAll(vis) ? "取消全选" : "全选");
        }
        scroll.addView(root);
        setContentView(scroll);
        onHome = true;
        setTitle("自动任务");
        loadStats();
    }

    @Override
    public void onBackPressed() {
        if (!onHome) {
            showHome();
            return;
        }
        super.onBackPressed();
    }

    /** 改时间（首页右上角「时间」）。 */
    /** 改运行时刻：支持多个，用逗号隔开（对齐 BuddySwitch 的 09:00 / 21:00 双时段）。 */
    private void editTime() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_DATETIME);
        input.setText(android.text.TextUtils.join(",", cfg.times()));
        input.setHint("01:40,09:00,21:00");
        new android.app.AlertDialog.Builder(this)
                .setTitle("每天几点执行（可写多个）")
                .setMessage("多个时间用逗号隔开，例如：\n01:40,09:00,21:00\n到点各跑一遍，跑完只发一条通知。")
                .setView(input)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        String raw = input.getText().toString();
                        java.util.List<String> list = new java.util.ArrayList<>();
                        for (String p : raw.split("[,，;；\\s]+")) {
                            if (p.trim().isEmpty()) {
                                continue;
                            }
                            int[] t = AlarmScheduler.parse(p);
                            if (t == null) {
                                toast("「" + p.trim() + "」不是合法时间，写成 04:00 这样");
                                return;
                            }
                            String norm = String.format("%02d:%02d", t[0], t[1]);
                            if (!list.contains(norm)) {
                                list.add(norm);
                            }
                        }
                        if (list.isEmpty()) {
                            toast("至少写一个时间");
                            return;
                        }
                        java.util.Collections.sort(list);
                        cfg.setTimes(list);
                        String joined = android.text.TextUtils.join(" · ", list);
                        if (!cfg.autoOn()) {
                            toast("已保存 " + joined + "（自动任务未开启，不会自动跑）");
                            showHome();
                            return;
                        }
                        long when = AlarmScheduler.schedule(MainActivity.this, list);
                        if (when < 0) {
                            toast("注册失败，请检查「闹钟和提醒」权限");
                        } else {
                            toast("已保存 " + joined + "，下次 " + AlarmScheduler.fmt(when));
                        }
                        showHome();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showDetail() {
        onHome = false;
        setTitle("WorkBuddy 模块");
        setContentView(buildDetailView());
        renderAccounts();
        refreshAccountInfo();
        loadStats();
    }

    /** WorkBuddy 模块页：只有头部（搜索 / 全选 / 删除 / 添加账号）+ 账号卡片。 */
    private View buildDetailView() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, dp(6), p, p);

        Button back = new Button(this);
        back.setText("‹ 返回");
        back.setTextSize(14);
        back.setTextColor(0xFF4A4F57);
        back.setBackground(null);
        back.setPadding(0, 0, 0, dp(2));
        back.setMinWidth(0);
        back.setMinimumWidth(0);
        back.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showHome();
            }
        });
        root.addView(back);

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(android.view.Gravity.CENTER_VERTICAL);
        tools.setPadding(0, dp(6), 0, dp(6));

        searchEdit = new EditText(this);
        searchEdit.setSingleLine(true);
        searchEdit.setHint("搜索账号名、UID、备注");
        searchEdit.setTextSize(13);
        tools.addView(searchEdit, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        sortBtn = new Button(this);
        sortBtn.setText("积分排序 ▾");
        sortBtn.setTextSize(12);
        sortBtn.setTextColor(0xFF4A4F57);
        sortBtn.setBackground(null);
        sortBtn.setPadding(dp(6), dp(4), dp(6), dp(4));
        sortBtn.setMinWidth(0);
        sortBtn.setMinimumWidth(0);
        sortBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("排序")
                        .setItems(new String[]{"积分排序（高 → 低）"}, new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int which) {
                                sortByCredit = true;
                                renderAccounts();
                            }
                        })
                        .show();
            }
        });
        tools.addView(sortBtn);

        selAllBtn = new Button(this);
        selAllBtn.setText("全选");
        selAllBtn.setTextSize(12);
        selAllBtn.setTextColor(0xFF4A4F57);
        selAllBtn.setBackground(null);
        selAllBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
        selAllBtn.setMinWidth(0);
        selAllBtn.setMinimumWidth(0);
        tools.addView(selAllBtn);

        Button delBtn = new Button(this);
        delBtn.setText("🗑");
        delBtn.setTextSize(15);
        delBtn.setTextColor(0xFF4A4F57);
        delBtn.setBackground(null);
        delBtn.setPadding(dp(10), dp(4), dp(6), dp(4));
        delBtn.setMinWidth(0);
        delBtn.setMinimumWidth(0);
        tools.addView(delBtn);
        root.addView(tools);

        // 一行两个按钮：一键签到 + 添加账号
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams alpRow = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alpRow.bottomMargin = dp(12);
        actions.setLayoutParams(alpRow);

        Button signAll = new Button(this);
        signAll.setText("✓ 一键签到");
        signAll.setTextColor(0xFFFFFFFF);
        signAll.setTextSize(14);
        android.graphics.drawable.GradientDrawable gs =
                new android.graphics.drawable.GradientDrawable();
        gs.setColor(0xFF3B82F6);
        gs.setCornerRadius(dp(10));
        signAll.setBackground(gs);
        LinearLayout.LayoutParams alpSign = new LinearLayout.LayoutParams(dp(124), dp(40));
        alpSign.rightMargin = dp(10);
        signAll.setLayoutParams(alpSign);
        actions.addView(signAll);

        Button addTop = new Button(this);
        addTop.setText("+ 添加账号");
        addTop.setTextColor(0xFFFFFFFF);
        addTop.setTextSize(14);
        android.graphics.drawable.GradientDrawable gb =
                new android.graphics.drawable.GradientDrawable();
        gb.setColor(0xFF12B76A);
        gb.setCornerRadius(dp(10));
        addTop.setBackground(gb);
        LinearLayout.LayoutParams alp2 = new LinearLayout.LayoutParams(0, dp(40), 1f);
        addTop.setLayoutParams(alp2);
        addTop.setPadding(dp(16), 0, dp(16), 0);
        actions.addView(addTop);

        root.addView(actions);
        accountInfo = new TextView(this);
        accountInfo.setTextSize(11);
        accountInfo.setTextColor(0xFF8A8F98);
        accountInfo.setPadding(0, dp(8), 0, dp(6));
        root.addView(accountInfo);

        signAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                signAllNow();
            }
        });

        accountsBox = new LinearLayout(this);
        accountsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(accountsBox);


        loginStatus = new TextView(this);
        loginStatus.setTextSize(11);
        loginStatus.setTextColor(0xFF8A8F98);
        loginStatus.setPadding(0, dp(4), 0, dp(4));
        root.addView(loginStatus);

        searchEdit.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                renderAccounts();
            }
        });

        selAllBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                JSONArray arr = cfg.wbAccounts();
                boolean all = arr.length() > 0;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    if (!selected.contains(str(o, "uid"))) {
                        all = false;
                        break;
                    }
                }
                selected.clear();
                if (!all) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o != null) {
                            selected.add(str(o, "uid"));
                        }
                    }
                }
                renderAccounts();
            }
        });

        delBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selected.isEmpty()) {
                    toast("先勾选要删的账号");
                    return;
                }
                final int n = selected.size();
                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setMessage("删除选中的 " + n + " 个账号？")
                        .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                JSONArray arr = cfg.wbAccounts();
                                JSONArray out = new JSONArray();
                                for (int i = 0; i < arr.length(); i++) {
                                    JSONObject o = arr.optJSONObject(i);
                                    if (o != null && !selected.contains(str(o, "uid"))) {
                                        out.put(o);
                                    }
                                }
                                cfg.saveWbAccounts(out);
                                for (String u : selected) {
                                    statCache.remove(u);
                                }
                                selected.clear();
                                renderAccounts();
                                toast("已删除 " + n + " 个");
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });

        addTop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 只支持手机号 + 验证码登录：先给一次明确提醒，再开浏览器
                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("添加 WorkBuddy 账号")
                        .setMessage("登录页会列出「微信登录 / 手机号 / SSO / QQ / 邮箱」几种方式。\n\n"
                                + "请务必选「手机号 + 验证码」——其他方式（微信、QQ、邮箱、SSO）即使显示登录成功，"
                                + "也常常拿不到账号信息，列表里不会出现新账号。\n\n"
                                + "登录完成后按返回键回到本 App，账号会自动补上（不需要在网页里点任何额外的按钮）。")
                        .setPositiveButton("去浏览器登录", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                startLogin(loginStatus);
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });

        scroll.addView(root);
        return scroll;
    }
    private void renderAccounts() {
        if (accountsBox == null) {
            return;
        }
        accountsBox.removeAllViews();
        JSONArray arr = cfg.wbAccounts();
        String q = searchEdit == null ? "" : searchEdit.getText().toString().trim().toLowerCase();

        // 勾选集合只保留仍然存在的账号，避免导入/删除后残留
        java.util.Set<String> alive = new java.util.HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) {
                alive.add(str(o, "uid"));
            }
        }
        selected.retainAll(alive);

        java.util.ArrayList<JSONObject> list = new java.util.ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String uid = str(o, "uid");
            String lb = str(o, "label");
            String name = lb.isEmpty() ? ("账号 " + (i + 1)) : lb;
            if (!q.isEmpty()) {
                String hay = (name + " " + uid + " " + str(o, "email")).toLowerCase();
                if (!hay.contains(q)) {
                    continue;
                }
            }
            list.add(o);
        }

        if (sortByCredit) {
            java.util.Collections.sort(list, new java.util.Comparator<JSONObject>() {
                @Override
                public int compare(JSONObject a, JSONObject b) {
                    double ca = creditOf(str(a, "uid"));
                    double cb = creditOf(str(b, "uid"));
                    return Double.compare(cb, ca);
                }
            });
        }

        int shown = 0;
        for (int i = 0; i < list.size(); i++) {
            JSONObject o = list.get(i);
            String uid = str(o, "uid");
            String lb = str(o, "label");
            String name = lb.isEmpty() ? ("账号 " + (i + 1)) : lb;
            accountsBox.addView(buildAccountCard(uid, name, lb));
            shown++;
        }
        updateSelAllLabel();
        if (shown == 0) {
            TextView t = new TextView(this);
            t.setText(arr.length() == 0 ? "（还没有账号，点「+ 添加账号」用浏览器登录）" : "（没有匹配的账号）");
            t.setTextSize(12);
            t.setTextColor(0xFF8A8F98);
            t.setPadding(0, dp(8), 0, dp(8));
            accountsBox.addView(t);
        }
    }

    /** 排序用：取缓存里的积分，没有就排到最后。 */
    private double creditOf(String uid) {
        String[] st = statCache.get(uid);
        if (st == null || "-1".equals(st[2])) {
            return -1;
        }
        try {
            return Double.parseDouble(st[0]);
        } catch (Exception e) {
            return -1;
        }
    }
    private void updateSelAllLabel() {
        if (selAllBtn == null) {
            return;
        }
        JSONArray arr = cfg.wbAccounts();
        boolean all = arr.length() > 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            if (!selected.contains(str(o, "uid"))) {
                all = false;
                break;
            }
        }
        selAllBtn.setText(all ? "取消全选" : "全选");
    }

    /** 手动添加账号（对话框，不再占页面）。 */
    private void manualAddDialog() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = dp(18);
        col.setPadding(p, p, p, 0);

        TextView tip = new TextView(this);
        tip.setText("从电脑端 BuddySwitch 的账号里复制 uid 与 refresh token（access token 可留空，会自动刷新）。");
        tip.setTextSize(11);
        tip.setPadding(0, 0, 0, dp(8));
        col.addView(tip);

        final EditText uidE = new EditText(this);
        uidE.setHint("uid（8ef48ca7-…）");
        uidE.setSingleLine(true);
        col.addView(uidE);

        final EditText rtE = new EditText(this);
        rtE.setHint("refresh token");
        rtE.setMinLines(2);
        rtE.setMaxLines(4);
        col.addView(rtE);

        final EditText lbE = new EditText(this);
        lbE.setHint("备注（可空，例如 海关法）");
        lbE.setSingleLine(true);
        col.addView(lbE);

        new android.app.AlertDialog.Builder(this)
                .setTitle("手动添加账号")
                .setView(col)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        String uid = uidE.getText().toString().trim();
                        String rt = rtE.getText().toString().trim();
                        String lb = lbE.getText().toString().trim();
                        if (uid.isEmpty() || rt.isEmpty()) {
                            toast("uid 和 refresh token 都要填");
                            return;
                        }
                        JSONArray arr = cfg.wbAccounts();
                        JSONArray out = new JSONArray();
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.optJSONObject(i);
                            if (o != null && !uid.equals(o.optString("uid"))) {
                                out.put(o);
                            }
                        }
                        JSONObject n = new JSONObject();
                        try {
                            n.put("uid", uid);
                            n.put("refreshToken", rt);
                            n.put("label", lb);
                            n.put("region", "cn");
                        } catch (Exception ignored) {
                        }
                        out.put(n);
                        cfg.saveWbAccounts(out);
                        statCache.remove(uid);
                        renderAccounts();
                        loadStats();
                        toast("已添加");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
    /** 一张账号卡片：勾选 + 头像 + 名字/UID + 签到状态 + 积分/今日/时间 + 标签行。 */
    private View buildAccountCard(final String uid, String name, final String label) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), 0xFFE3E6EA);
        card.setBackground(bg);
        int cq = dp(14);
        card.setPadding(cq, cq, cq, cq);

        String[] st = statCache.get(uid);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        CheckBox cb = new CheckBox(this);
        cb.setChecked(selected.contains(uid));
        cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                if (checked) {
                    selected.add(uid);
                } else {
                    selected.remove(uid);
                }
                updateSelAllLabel();
            }
        });
        row.addView(cb);

        TextView av = new TextView(this);
        String initial = name.isEmpty() ? "?" : name.substring(0, 1);
        av.setText(initial);
        av.setTextSize(17);
        av.setTextColor(0xFFFFFFFF);
        av.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable abg =
                new android.graphics.drawable.GradientDrawable();
        abg.setColor(avatarColor(uid));
        abg.setCornerRadius(dp(9));
        av.setBackground(abg);
        LinearLayout.LayoutParams alp =
                new LinearLayout.LayoutParams(dp(36), dp(36));
        alp.rightMargin = dp(10);
        row.addView(av, alp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView nm = new TextView(this);
        nm.setText(name);
        nm.setTextSize(16);
        col.addView(nm);
        TextView idv = new TextView(this);
        idv.setText("UID: " + uid);
        idv.setTextSize(10);
        idv.setTextColor(0xFF8A8F98);
        idv.setSingleLine(true);
        idv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        col.addView(idv);
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        String badgeText;
        int badgeFg;
        int badgeBg;
        if (st == null) {
            badgeText = "查询中";
            badgeFg = 0xFF8A8F98;
            badgeBg = 0xFFF2F3F5;
        } else if ("-1".equals(st[2])) {
            badgeText = "查询失败";
            badgeFg = 0xFFB4453C;
            badgeBg = 0xFFFCEDEC;
        } else if ("1".equals(st[2])) {
            badgeText = "今日已签到";
            badgeFg = 0xFF1E9E58;
            badgeBg = 0xFFE8F6EE;
        } else {
            badgeText = "未签到";
            badgeFg = 0xFFB4453C;
            badgeBg = 0xFFFCEDEC;
        }
        row.addView(pill(badgeText, badgeFg, badgeBg));
        card.addView(row);

        TextView stat = new TextView(this);
        if (st == null) {
            stat.setText("积分 —　今日 —");
        } else if ("-1".equals(st[2])) {
            stat.setText("积分 —　今日 —　" + st[3]);
        } else {
            stat.setText("积分 " + st[0] + "　今日 " + st[1] + "　" + st[3]);
        }
        stat.setTextSize(12);
        stat.setTextColor(0xFF4A4F57);
        stat.setPadding(dp(40), dp(10), 0, dp(6));
        card.addView(stat);

        // 两个任务的小标签并排：签到 / ✈ 旅行（和参考图里那排任务角标一样）
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(dp(40), 0, 0, dp(2));

        boolean signed = st != null && "1".equals(st[2]);
        chips.addView(pill(signed ? "✓ 签到" : "签到",
                signed ? 0xFF1E9E58 : 0xFF8A8F98,
                signed ? 0xFFE8F6EE : 0xFFF2F3F5));

        String[] tv = travelCache.get(uid);
        boolean travelOn = tv != null && ("traveling".equals(tv[0]) || "arrived".equals(tv[0]));
        String tLabel = (tv == null) ? "🛫 旅行" : ("🛫 " + tv[1]);
        TextView travelPill = pill(tLabel,
                travelOn ? 0xFF1E9E58 : 0xFF8A8F98,
                travelOn ? 0xFFE8F6EE : 0xFFF2F3F5);
        travelPill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTravelInfo(uid, label);
            }
        });
        chips.addView(travelPill);

        // 活跃火焰：紧贴飞机右边。灰色 → 越活跃越红，从底部往上填；长按看明细。
        // 火焰以「一个月」为满：本月活跃天数 ÷ 当月天数，月初开始慢慢往上填
        int[] act = actCache.get(uid);
        int daysInMonth = java.util.Calendar.getInstance()
                .getActualMaximum(java.util.Calendar.DAY_OF_MONTH);
        float prog = (act == null) ? 0f : Math.min(1f, act[1] / (float) daysInMonth);
        FlameView flame = new FlameView(this);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(dp(20), dp(22));
        flp.leftMargin = dp(8);
        flame.setLayoutParams(flp);
        flame.setProgress(prog);
        flame.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showActivityInfo(uid, label);
                return true;
            }
        });
        flame.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showActivityInfo(uid, label);
            }
        });
        chips.addView(flame);

        card.addView(chips);




        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(10);
        card.setLayoutParams(clp);
        return card;
    }

    private com.fz.autotask.modules.workbuddy.WorkBuddyAccount findAccount(String uid) {
        org.json.JSONArray arr = cfg.wbAccounts();
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject o = arr.optJSONObject(i);
            if (o != null && uid.equals(str(o, "uid"))) {
                return com.fz.autotask.modules.workbuddy.WorkBuddyAccount.from(o);
            }
        }
        return null;
    }

    /** 一键签到：对当前所有账号立刻执行一次签到 */
    private void signAllNow() {
        final java.util.ArrayList<com.fz.autotask.modules.workbuddy.WorkBuddyAccount> list =
                new java.util.ArrayList<>();
        org.json.JSONArray arr = cfg.wbAccounts();
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject o = arr.optJSONObject(i);
            if (o != null) {
                list.add(com.fz.autotask.modules.workbuddy.WorkBuddyAccount.from(o));
            }
        }
        if (list.isEmpty()) {
            toast("还没有账号，先点「添加账号」");
            return;
        }
        // 只签被勾选的账号；没勾就提示，不再全签
        java.util.ArrayList<com.fz.autotask.modules.workbuddy.WorkBuddyAccount> picked =
                new java.util.ArrayList<>();
        for (com.fz.autotask.modules.workbuddy.WorkBuddyAccount a : list) {
            if (selected.contains(a.uid)) {
                picked.add(a);
            }
        }
        if (picked.isEmpty()) {
            toast("先在卡片左侧勾选要签到的账号（或者点「全选」）");
            return;
        }
        list.clear();
        list.addAll(picked);
        toast("正在跑选中的 " + list.size() + " 个账号（签到 + 旅行 + 活跃）…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final StringBuilder detail = new StringBuilder();
                int signed = 0, traveled = 0, active = 0;
                long nextTravelAt = 0;
                final java.util.LinkedHashMap<String, org.json.JSONObject> fresh =
                        new java.util.LinkedHashMap<>();
                for (com.fz.autotask.modules.workbuddy.WorkBuddyAccount a : list) {
                    String nm = (a.label == null || a.label.isEmpty()) ? a.uid : a.label;

                    // 1) 签到
                    String signRes;
                    try {
                        if (com.fz.autotask.modules.workbuddy.WorkBuddyClient.needRefresh(a)) {
                            com.fz.autotask.modules.workbuddy.WorkBuddyClient.refresh(a);
                        }
                        signRes = com.fz.autotask.modules.workbuddy.WorkBuddyClient.checkin(a);
                        signed++;
                    } catch (com.fz.autotask.modules.workbuddy.WorkBuddyClient.Unauthorized ue) {
                        signRes = "登录过期，请重新添加账号";
                    } catch (Exception e) {
                        signRes = "失败：" + (e.getMessage() == null
                                ? e.getClass().getSimpleName() : e.getMessage());
                    }

                    // 2) 旅行：到点领奖 / 未派出就出发
                    com.fz.autotask.modules.workbuddy.TravelModule.R tr =
                            com.fz.autotask.modules.workbuddy.TravelModule.runOne(a);
                    String travelRes;
                    if (!tr.err.isEmpty()) {
                        travelRes = "失败：" + tr.err;
                    } else if (tr.claimed > 0) {
                        travelRes = "领到 +" + tr.claimed + (tr.departed ? "，已再派出" : "");
                        traveled++;
                    } else if (tr.departed) {
                        travelRes = "已派出";
                        traveled++;
                    } else if (tr.waiting) {
                        travelRes = "旅行中（" + com.fz.autotask.modules.workbuddy.WorkBuddyTravel
                                .fmtArrive(tr.arriveAt) + " 到）";
                        traveled++;
                    } else {
                        travelRes = "今日已派完";
                        traveled++;
                    }
                    if (tr.nextAt > 0 && (nextTravelAt == 0 || tr.nextAt < nextTravelAt)) {
                        nextTravelAt = tr.nextAt;
                    }

                    // 3) 活跃：连发上报，点亮活跃地图
                    com.fz.autotask.modules.workbuddy.ActivityModule.R ar =
                            com.fz.autotask.modules.workbuddy.ActivityModule.runOne(a, cfg.reportCount());
                    String actRes;
                    if (!ar.err.isEmpty()) {
                        actRes = "失败：" + ar.err;
                    } else {
                        actRes = "上报 " + ar.sent + " 条";
                        if (ar.streak > 0) {
                            actRes += "，连登 " + ar.streak + " 天";
                        }
                        active++;
                    }

                    detail.append(nm).append(" → 签到 ").append(signRes)
                            .append(" · 旅行 ").append(travelRes)
                            .append(" · 活跃 ").append(actRes).append("\n");

                    // 用刷新后的 token 覆盖同 uid 的旧条目，其它账号原样保留
                    fresh.put(a.uid, a.toJson());
                }
                // 单次合并：读一遍当前账号，按 uid 覆盖，顺手去掉历史遗留的重复条目
                org.json.JSONArray cur = cfg.wbAccounts();
                org.json.JSONArray out = new org.json.JSONArray();
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (int i = 0; i < cur.length(); i++) {
                    org.json.JSONObject o = cur.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    String uid = str(o, "uid");
                    if (uid.isEmpty() || !seen.add(uid)) {
                        continue;
                    }
                    out.put(fresh.containsKey(uid) ? fresh.get(uid) : o);
                }
                for (java.util.Map.Entry<String, org.json.JSONObject> e : fresh.entrySet()) {
                    if (!seen.contains(e.getKey())) {
                        out.put(e.getValue());
                    }
                }
                cfg.saveWbAccounts(out);
                if (nextTravelAt > 0) {
                    cfg.setTravelNextAt(nextTravelAt);
                }
                dbg("signAllNow\n" + detail);

                final String brief = "完成：签到 " + signed + "/" + list.size()
                        + " · 旅行 " + traveled + "/" + list.size()
                        + " · 活跃 " + active + "/" + list.size();
                final String lines = detail.toString();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        toast(brief);
                        new android.app.AlertDialog.Builder(MainActivity.this)
                                .setTitle("本次结果")
                                .setMessage(lines)
                                .setPositiveButton("知道了", null)
                                .show();
                        // 跑完必须强制刷新：loadStats() 默认命中缓存会直接跳过
                        loadStats(true);
                    }
                });
            }
        }).start();
    }

    /** 点击旅行角标：查一次实时状态并显示 */
    private void showTravelInfo(final String uid, String label) {
        final com.fz.autotask.modules.workbuddy.WorkBuddyAccount a = findAccount(uid);
        if (a == null) {
            toast("账号已不在列表里");
            return;
        }
        final TextView body = new TextView(this);
        body.setTextSize(13);
        body.setPadding(dp(18), dp(14), dp(18), dp(4));
        body.setText("查询中…");
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("🛫 旅行 · " + ((label == null || label.isEmpty()) ? uid : label))
                .setView(body)
                .setPositiveButton("关闭", null)
                .setNegativeButton("刷新", null)
                .create();
        dlg.show();
        dlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        body.setText("查询中…");
                        fetchTravelInto(body, uid, a);
                    }
                });
        fetchTravelInto(body, uid, a);
    }

    /** 点 / 长按火焰：查一次活跃明细。 */
    private void showActivityInfo(final String uid, String label) {
        final com.fz.autotask.modules.workbuddy.WorkBuddyAccount a = findAccount(uid);
        if (a == null) {
            toast("账号已不在列表里");
            return;
        }
        final TextView body = new TextView(this);
        body.setTextSize(13);
        body.setPadding(dp(18), dp(14), dp(18), dp(4));
        body.setText("查询中…");
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("🔥 活跃 · " + ((label == null || label.isEmpty()) ? uid : label))
                .setView(body)
                .setPositiveButton("关闭", null)
                .setNegativeButton("刷新", null)
                .create();
        dlg.show();
        dlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        body.setText("查询中…");
                        fetchActivityInto(body, uid, a);
                    }
                });
        fetchActivityInto(body, uid, a);
    }

    /** 火焰明细的取数与渲染。 */
    private void fetchActivityInto(final TextView body, final String uid,
                                   final com.fz.autotask.modules.workbuddy.WorkBuddyAccount a) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String txt;
                int todayScore = 0;
                int monthDays = 0;
                try {
                    com.fz.autotask.modules.workbuddy.ActivityModule.Act d =
                            com.fz.autotask.modules.workbuddy.ActivityModule.detail(a);
                    todayScore = d.todayScore;
                    monthDays = d.monthDays;
                    StringBuilder b = new StringBuilder();
                    if (!d.ok) {
                        b.append("⚠ 拿不到活跃数据（网络或登录态问题）\n\n");
                    }
                    b.append("今日活跃：").append(d.todayScore).append(" 分");
                    b.append(d.todayScore <= 0 ? "（今天还没点亮）" : "").append("\n");
                    b.append("本月活跃：").append(d.monthDays).append(" / ")
                            .append(java.util.Calendar.getInstance()
                                    .getActualMaximum(java.util.Calendar.DAY_OF_MONTH)).append(" 天\n");
                    b.append("连续活跃：").append(d.streak).append(" 天（昨天没活跃就会断）\n");
                    b.append("补签卡：").append(d.makeupCards).append(" 张\n\n");
                    b.append("火焰 = 本月活跃天数 ÷ 当月天数，从月中开始慢慢往上填，满一个月全红。\n");
                    b.append("「今日活跃」由对话上报（/v2/report）打点；\n");
                    b.append("签到只加积分，不会点亮活跃地图 —— 两件事互不影响。");
                    txt = b.toString();
                } catch (Throwable e) {
                    txt = "查询失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                }
                final String out = txt;
                final int fs = todayScore;
                final int fm = monthDays;
                actCache.put(uid, new int[]{fs, fm});
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        body.setText(out);
                        renderAccounts();
                    }
                });
            }
        }).start();
    }

    private void fetchTravelInto(final TextView body, final String uid,
                                 final com.fz.autotask.modules.workbuddy.WorkBuddyAccount a) {        new Thread(new Runnable() {
            @Override
            public void run() {
                String txt;
                try {
                    com.fz.autotask.modules.workbuddy.WorkBuddyTravel.State s =
                            com.fz.autotask.modules.workbuddy.WorkBuddyTravel.status(a);
                    StringBuilder b = new StringBuilder();
                    if (com.fz.autotask.modules.workbuddy.WorkBuddyTravel.ARRIVED.equals(s.state)) {
                        b.append("状态：已到达，等待领奖\n");
                    } else if (com.fz.autotask.modules.workbuddy.WorkBuddyTravel.TRAVELING.equals(s.state)) {
                        b.append("状态：旅行中\n");
                    } else {
                        b.append("状态：未派出\n");
                    }
                    if (s.locationName != null && !s.locationName.isEmpty()) {
                        b.append("地点：").append(s.locationName).append("\n");
                    }
                    if (s.departAt > 0) {
                        b.append("出发：").append(com.fz.autotask.modules.workbuddy.WorkBuddyTravel.fmtArrive(s.departAt)).append("\n");
                    }
                    if (s.arriveAt > 0) {
                        b.append("到达：").append(com.fz.autotask.modules.workbuddy.WorkBuddyTravel.fmtArrive(s.arriveAt)).append("\n");
                    }
                    if (s.rewardCredit > 0) {
                        b.append("奖励：").append(s.rewardCredit).append(" 积分\n");
                    }
                    b.append("今日已派：").append(s.dailyLimitReached ? "是" : "否").append("\n");
                    b.append(cfg.autoOn()
                            ? ("\n每次 " + AlarmScheduler.display(cfg.times()) + " 自动跑：已到达会领奖，未派出会挑时长最长的地点出发。")
                            : "\n自动任务已关闭：点「一键签到」时才会领奖/出发。");
                    b.append("\n旅行到点还会自动回访：领奖并立刻再派出。");
                    txt = b.toString();
                } catch (Exception e) {
                    txt = "查询失败：" + e.getMessage();
                }
                final String out = txt;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        body.setText(out);
                    }
                });
            }
        }).start();
    }
    /** 首页的模块卡片：外观与账号卡片一致（标题/副标题/状态角标/进入） */
    private View buildModuleCard(final Module m) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), 0xFFE3E5E9);
        card.setBackground(bg);
        int cq = dp(14);
        card.setPadding(cq, cq, cq, cq);

        TextView nm = new TextView(this);
        nm.setText(m.name());
        nm.setTextSize(17);
        card.addView(nm);

        TextView ds = new TextView(this);
        ds.setText(m.description());
        ds.setTextSize(11);
        ds.setTextColor(0xFF8A8F98);
        ds.setPadding(0, dp(4), 0, dp(8));
        card.addView(ds);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        if ("workbuddy-activity".equals(m.id())) {
            // 活跃模块跟签到/旅行无关，别套那两颗角标，免得看着像「已签到」
            chips.addView(pill("📊 每次上报 " + cfg.reportCount() + " 条", 0xFF8A8F98, 0xFFF2F3F5));
            card.addView(chips);
        } else {
            int total = 0, signed = 0, traveling = 0, loaded = 0;
            org.json.JSONArray arr = cfg.wbAccounts();
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                total++;
                String uid = str(o, "uid");
                String[] st = statCache.get(uid);
                if (st != null) {
                    loaded++;
                    if ("1".equals(st[2])) {
                        signed++;
                    }
                }
                String[] tv = travelCache.get(uid);
                if (tv != null && ("traveling".equals(tv[0]) || "arrived".equals(tv[0]))) {
                    traveling++;
                }
            }
            String signText;
            if (total == 0) {
                signText = "还没加账号";
            } else if (loaded == 0) {
                signText = "签到 查询中";
            } else {
                signText = "✓ 签到 " + signed + "/" + total;
            }
            boolean signOk = total > 0 && loaded > 0 && signed == total;
            chips.addView(pill(signText,
                    signOk ? 0xFF1E9E58 : 0xFF8A8F98,
                    signOk ? 0xFFE8F6EE : 0xFFF2F3F5));

            TextView gap = new TextView(this);
            gap.setWidth(dp(8));
            chips.addView(gap);

            boolean travelOn = traveling > 0;
            chips.addView(pill(travelOn ? ("🛫 旅行 " + traveling + " 只") : "🛫 未出行",
                    travelOn ? 0xFF1E9E58 : 0xFF8A8F98,
                    travelOn ? 0xFFE8F6EE : 0xFFF2F3F5));
            card.addView(chips);
        }

        TextView go = new TextView(this);
        go.setText("进入 ›");
        go.setTextSize(12);
        go.setTextColor(0xFF4A4F57);
        go.setPadding(0, dp(8), 0, 0);
        card.addView(go);

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDetail();
            }
        });
        // 隐藏手势：长按卡片 = 立刻跑一遍
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                toast("开始运行…");
                final java.util.ArrayList<String> only = new java.util.ArrayList<>();
                only.add(m.id());
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Runner.runModules(MainActivity.this, only);
                        } catch (Throwable ignored) {
                        }
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                String last = cfg.lastResult();
                                toast(last.isEmpty() ? "运行结束" : last.split("\n")[0]);
                            }
                        });
                    }
                }).start();
                return true;
            }
        });
        return card;
    }

    /** 添加模块（目前只有 WorkBuddy 一个） */
    /** 首页当前显示的模块 id（排除 demo / 旅行子模块 / 被删除的） */
    /** 旅行 / 活跃是 WorkBuddy 模块的子任务，不出现在首页模块列表里。 */
    private static boolean isSubModule(String id) {
        return "workbuddy-travel".equals(id) || "workbuddy-activity".equals(id);
    }

    private java.util.List<String> visibleModuleIds() {
        java.util.Set<String> hidden = cfg.hiddenModules();
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (Module m : ModuleRegistry.all()) {
            if ("demo".equals(m.id()) || isSubModule(m.id())) {
                continue;
            }
            if (hidden.contains(m.id())) {
                continue;
            }
            out.add(m.id());
        }
        return out;
    }
    /** 添加模块：把被删除 / 未显示的模块加回首页（目前只有 WorkBuddy 一个）。 */
    private void addModuleDialog() {
        final java.util.Set<String> hidden = cfg.hiddenModules();
        final java.util.ArrayList<Module> cands = new java.util.ArrayList<>();
        final java.util.ArrayList<String> names = new java.util.ArrayList<>();
        for (Module m : ModuleRegistry.all()) {
            if ("demo".equals(m.id()) || isSubModule(m.id())) {
                continue;
            }
            if (!hidden.contains(m.id())) {
                continue;
            }
            cands.add(m);
            names.add(m.name());
        }
        if (cands.isEmpty()) {
            toast("模块都已经在首页了");
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("添加模块")
                .setItems(names.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        java.util.Set<String> hid = cfg.hiddenModules();
                        hid.remove(cands.get(which).id());
                        cfg.setHiddenModules(hid);
                        refreshingHome = true;
                        showHome();
                        refreshingHome = false;
                        toast("已添加 " + names.get(which));
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }
    private TextView pill(String text, int fg, int bgColor) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(11);
        t.setTextColor(fg);
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(bgColor);
        g.setCornerRadius(dp(9));
        t.setBackground(g);
        t.setPadding(dp(8), dp(3), dp(8), dp(3));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private static int avatarColor(String uid) {
        int h = uid == null ? 0 : uid.hashCode();
        int[] colors = {0xFFC0554B, 0xFF7E57C2, 0xFF3E7BFA, 0xFF1E9E58, 0xFFB26A00, 0xFF00897B};
        return colors[Math.abs(h) % colors.length];
    }

    /** 一个账号能做的动作。 */
    private void accountMenu(final String uid, final String label) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(label.isEmpty() ? uid : label)
                .setItems(new String[]{"改名", "单独签这个账号", "删除这个账号"}, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) {
                            renameAccount(uid, label);
                        } else if (which == 1) {
                            signOne(uid);
                        } else {
                            JSONArray arr = cfg.wbAccounts();
                            JSONArray out = new JSONArray();
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject o = arr.optJSONObject(i);
                                if (o != null && !uid.equals(str(o, "uid"))) {
                                    out.put(o);
                                }
                            }
                            cfg.saveWbAccounts(out);
                            statCache.remove(uid);
                            renderAccounts();
                            toast("已删除");
                        }
                    }
                })
                .show();
    }

    /** 只给这一个账号签到。 */
    private void signOne(final String uid) {
        JSONArray arr = cfg.wbAccounts();
        com.fz.autotask.modules.workbuddy.WorkBuddyAccount target = null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && uid.equals(str(o, "uid"))) {
                target = com.fz.autotask.modules.workbuddy.WorkBuddyAccount.from(o);
            }
        }
        if (target == null) {
            return;
        }
        final com.fz.autotask.modules.workbuddy.WorkBuddyAccount a = target;
        toast("正在签 " + (labelOf(uid)) + "…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    String raw = com.fz.autotask.modules.workbuddy.WorkBuddyClient.checkin(a);
                    msg = "结果：" + raw;
                    statCache.remove(uid);
                    loadStats();
                } catch (Exception e) {
                    msg = "失败：" + e.getMessage();
                }
                final String m = msg;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        toast(m);
                    }
                });
            }
        }).start();
    }

    private String labelOf(String uid) {
        JSONArray arr = cfg.wbAccounts();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && uid.equals(str(o, "uid"))) {
                String lb = str(o, "label");
                return lb.isEmpty() ? uid : lb;
            }
        }
        return uid;
    }

    /** 后台拉每个账号的积分/今日/签到状态，填进卡片。 */
    private void loadStats() {
        loadStats(false);
    }

    /** force=true 时忽略缓存重新拉一遍：签到/刷新后必须走这条路，否则命中 statCache 直接跳过，界面永远不变。 */
    private void loadStats(final boolean force) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                JSONArray arr = cfg.wbAccounts();
                boolean any = false;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    com.fz.autotask.modules.workbuddy.WorkBuddyAccount a =
                            com.fz.autotask.modules.workbuddy.WorkBuddyAccount.from(o);
                    if (a.uid.isEmpty() || (!force && statCache.containsKey(a.uid))) {
                        continue;
                    }
                    // 卡片的"积分"= 可用余额（resource-summary 的 TotalCredits），
                    // 不是签到活动里的累计获得，后者会和官方 App 对不上。
                    double bal = com.fz.autotask.modules.workbuddy.WorkBuddyClient.balance(a);
                    // 同一张卡上还要显示旅行任务的状态
                    try {
                        com.fz.autotask.modules.workbuddy.WorkBuddyTravel.State ts =
                                com.fz.autotask.modules.workbuddy.WorkBuddyTravel.status(a);
                        String label;
                        if (com.fz.autotask.modules.workbuddy.WorkBuddyTravel.ARRIVED.equals(ts.state)) {
                            label = "待领奖";
                        } else if (com.fz.autotask.modules.workbuddy.WorkBuddyTravel.TRAVELING.equals(ts.state)) {
                            label = "旅行中";
                        } else {
                            label = "未派出";
                        }
                        travelCache.put(a.uid, new String[]{ts.state, label});
                    } catch (Exception e) {
                        travelCache.put(a.uid, new String[]{"error", "未知"});
                    }
                    // 火焰：今日活跃分 / 本月活跃天数（一次热力图，读不到就保持灰色）
                    try {
                        int[] hm = com.fz.autotask.modules.workbuddy.ActivityModule.todayAndMonth(a);
                        if (hm != null) {
                            actCache.put(a.uid, hm);
                        }
                    } catch (Exception ignored) {
                    }
                    try {
                        com.fz.autotask.modules.workbuddy.WorkBuddyClient.CheckinState s =
                                com.fz.autotask.modules.workbuddy.WorkBuddyClient.status(a);
                        statCache.put(a.uid, new String[]{
                                bal < 0 ? String.valueOf(s.totalCredits) : trimNum(bal),
                                String.valueOf(s.todayCredit),
                                s.checkedIn ? "1" : "0",
                                "刚刚更新"});
                    } catch (Exception e) {
                        statCache.put(a.uid, new String[]{
                                bal < 0 ? "—" : trimNum(bal), "—", "-1", "查询失败"});
                    }
                    any = true;
                }
                if (any) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (onHome) {
                                if (!refreshingHome) {
                                    refreshingHome = true;
                                    showHome();
                                    refreshingHome = false;
                                }
                            } else {
                                renderAccounts();
                                refreshAccountInfo();
                            }
                        }
                    });
                }
            }
        }).start();
    }

    /** 315.0 → "315"，314.5 → "314.5"。 */
    private static String trimNum(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.001) {
            return String.valueOf((long) Math.rint(v));
        }
        return String.valueOf(Math.round(v * 10) / 10.0);
    }

    /** org.json 把 JSON null 读成字符串 "null"，这里统一清掉。 */
    private static String str(JSONObject o, String k) {
        String v = o.optString(k, "");
        return v.equals("null") ? "" : v;
    }

    /** 点账号行 → 改备注名。 */
    private void renameAccount(final String uid, String oldLabel) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(oldLabel);
        input.setHint("给它起个名字，例如 海关法");
        new android.app.AlertDialog.Builder(this)
                .setTitle("备注名称")
                .setView(input)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        String name = input.getText().toString().trim();
                        JSONArray cur = cfg.wbAccounts();
                        for (int i = 0; i < cur.length(); i++) {
                            JSONObject o = cur.optJSONObject(i);
                            if (o != null && uid.equals(str(o, "uid"))) {
                                try {
                                    o.put("label", name);
                                } catch (Exception ignored) {
                                }
                            }
                        }
                        cfg.saveWbAccounts(cur);
                        renderAccounts();
                        toast("已改名：" + (name.isEmpty() ? uid : name));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 是否开启「每天自动运行」。关闭会撤掉日常闹钟与旅行回访闹钟。 */
    private void setAuto(boolean on) {
        cfg.setAutoOn(on);
        if (on) {
            long when = AlarmScheduler.schedule(this, cfg.times());
            toast(when > 0 ? ("已开启，下次 " + AlarmScheduler.fmt(when)) : "已开启（时间无效）");
            // 2 分钟后的后台自检：验证「不开 App 也能跑」
            long st = System.currentTimeMillis() + 120000L;
            long ok = AlarmScheduler.scheduleSelfTest(this, st);
            if (ok > 0) {
                cfg.setSelfTestAt(ok);
                cfg.setSelfTestFired(0L);
            }
            showReadiness();
        } else {
            AlarmScheduler.cancel(this);
            AlarmScheduler.cancelTravel(this);
            toast("已关闭，不再自动运行");
        }
        refreshingHome = true;
        showHome();
        refreshingHome = false;
    }

    /** 开启后的自检报告：能不能不开 App 也跑、重启能不能恢复。 */
    private void showReadiness() {
        final String rep = com.fz.autotask.core.AutoCheck.report(this);
        new android.app.AlertDialog.Builder(this)
                .setTitle("开启前自检")
                .setMessage(rep)
                .setPositiveButton("打开电池设置", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        com.fz.autotask.core.AutoCheck.openBatterySettings(MainActivity.this);
                    }
                })
                .setNeutralButton("打开闹钟设置", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        com.fz.autotask.core.AutoCheck.openExactAlarmSettings(MainActivity.this);
                    }
                })
                .setNegativeButton("知道了", null)
                .show();
    }

    /** 上次的自检闹钟如果到点没跑起来，回来提醒用户去开自启动。 */
    private void checkSelfTestResult() {
        long at = cfg.selfTestAt();
        long fired = cfg.selfTestFired();
        if (at <= 0 || fired >= at) {
            return;
        }
        if (System.currentTimeMillis() < at + 5 * 60 * 1000L) {
            return; // 还没到点（或刚过点，等一会儿再看）
        }
        cfg.setSelfTestAt(0L);
        new android.app.AlertDialog.Builder(this)
                .setTitle("后台自检没跑起来")
                .setMessage("刚才那次 2 分钟自检的定时任务没有触发，说明系统限制了后台运行。\n\n"
                        + "请在手机管家/设置里给「自动任务」允许：\n"
                        + "1）自启动\n2）后台运行 / 耗电管理设为「无限制」\n\n"
                        + "改完再点一次齿轮 → 开启，会有第二次自检。")
                .setPositiveButton("去应用设置", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        com.fz.autotask.core.AutoCheck.openAppDetails(MainActivity.this);
                    }
                })
                .setNegativeButton("知道了", null)
                .show();
    }

    /** 齿轮：自动任务开关。 */
    /** ActionBar 右上角的齿轮：打开自动任务开关。 */
    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        android.view.MenuItem it = menu.add(0, 1001, 0, "设置");
        it.setIcon(R.drawable.ic_gear);
        it.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == 1001) {
            autoDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
    private void autoDialog() {
        final boolean on = cfg.autoOn();
        new android.app.AlertDialog.Builder(this)
                .setTitle("自动任务")
                .setMessage("开启后每天 " + AlarmScheduler.display(cfg.times()) + " 自动把所有模块跑一遍（每个模块做什么由它自己决定）。\n\n"
                        + "当前：" + (on ? "已开启" : "已关闭")
                        + "\n关闭时不会排任何闹钟，只在你自己点「一键签到」时才跑。")
                .setPositiveButton("开启", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        setAuto(true);
                    }
                })
                .setNegativeButton("关闭", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        setAuto(false);
                    }
                })
                .setNeutralButton("取消", null)
                .show();
    }

    private void refreshStatus() {
        if (statusText == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        boolean exact = AlarmScheduler.canScheduleExact(this);
        boolean on = cfg.autoOn();

        sb.append("自动任务：").append(on ? "已开启" : "已关闭").append("　");
        sb.append("时间：").append(AlarmScheduler.display(cfg.times()));
        if (on) {
            sb.append("　").append(exact ? "精确闹钟可用" : "⚠ 精确闹钟被关闭");
        }
        if (!exact) {
            sb.append("\n点这里去开启「闹钟和提醒」");
        }
        statusText.setText(sb.toString());
        statusText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openExactAlarmSettings();
            }
        });

        String last = cfg.lastResult();
        if (last == null || last.isEmpty()) {
            lastText.setText("上次结果：还没跑过");
        } else {
            lastText.setText("上次结果：\n" + last);
        }
    }

    private void ensurePermissions() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        if (!AlarmScheduler.canScheduleExact(this)) {
            openExactAlarmSettings();
        }
    }

    private void openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < 31) {
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }

    /** 从 Android/data/<pkg>/files/import.json 导入。返回新增条数；-1 = 没有文件或读失败。 */
    private int importFromFile(boolean quiet) {
        File dir = getExternalFilesDir(null);
        File f = dir == null ? null : new File(dir, "import.json");
        if (f == null || !f.isFile()) {
            if (!quiet) {
                toast("没找到 Android/data/" + getPackageName() + "/files/import.json");
            }
            refreshAccountInfo();
            return -1;
        }
        try {
            JSONArray src = new JSONArray(readText(f));
            JSONArray cur = cfg.wbAccounts();
            int added = 0;
            for (int i = 0; i < src.length(); i++) {
                JSONObject o = src.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String uid = o.optString("uid", "");
                String rt = o.optString("refresh_token", o.optString("refreshToken", ""));
                String at = o.optString("access_token", o.optString("accessToken", ""));
                String lb = o.optString("label", o.optString("email", ""));
                String eid = o.optString("enterpriseId", o.optString("enterprise_id", ""));
                if (uid.isEmpty() || rt.isEmpty()) {
                    continue;
                }
                int exists = -1;
                for (int k = 0; k < cur.length(); k++) {
                    JSONObject c = cur.optJSONObject(k);
                    if (c != null && uid.equals(c.optString("uid"))) {
                        exists = k;
                        break;
                    }
                }
                JSONObject n = new JSONObject();
                n.put("uid", uid);
                n.put("refreshToken", rt);
                n.put("accessToken", at);
                n.put("label", lb);
                n.put("enterpriseId", eid);
                n.put("region", "cn");
                if (exists >= 0) {
                    cur.put(exists, n);
                } else {
                    cur.put(n);
                    added++;
                }
            }
            cfg.saveWbAccounts(cur);
            cfg.setImportStamp(f.length() + ":" + f.lastModified());
            cfg.setImportAt(System.currentTimeMillis());
            renderAccounts();
            refreshAccountInfo();
            return added;
        } catch (Exception e) {
            if (!quiet) {
                toast("导入失败：" + e);
            }
            refreshAccountInfo();
            return -1;
        }
    }

    /** 启动时若 import.json 变了（重新 push 过）就自动同步一次，不用手点。 */
    private void autoImportIfChanged() {
        File dir = getExternalFilesDir(null);
        File f = dir == null ? null : new File(dir, "import.json");
        if (f == null || !f.isFile()) {
            refreshAccountInfo();
            return;
        }
        String stamp = f.length() + ":" + f.lastModified();
        if (!stamp.equals(cfg.importStamp())) {
            int n = importFromFile(true);
            if (n > 0) {
                toast("已自动同步，新增 " + n + " 个账号");
            }
        }
        refreshAccountInfo();
    }

    private void refreshAccountInfo() {
        if (accountInfo == null) {
            return;
        }
        JSONArray arr = cfg.wbAccounts();
        int n = arr.length();
        int signed = 0, traveling = 0, done = 0;
        for (int i = 0; i < n; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String uid = str(o, "uid");
            String[] st = statCache.get(uid);
            if (st != null) {
                done++;
                if ("1".equals(st[2])) {
                    signed++;
                }
            }
            String[] tv = travelCache.get(uid);
            if (tv != null && ("traveling".equals(tv[0]) || "arrived".equals(tv[0]))) {
                traveling++;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("账号信息：").append(n).append(" 个账号");
        if (done > 0) {
            sb.append(" · 今日已签 ").append(signed).append("/").append(n);
        }
        if (traveling > 0) {
            sb.append(" · 旅行中 ").append(traveling);
        }
        long at = cfg.importAt();
        if (at > 0) {
            java.text.SimpleDateFormat sdf =
                    new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA);
            sb.append(" · 上次同步 ").append(sdf.format(new java.util.Date(at)));
        }
        accountInfo.setText(sb.toString());
    }

    /** 浏览器授权添加账号：申请 state → 打开授权页 → 后台轮询直到拿到 token。 */
    private void startLogin(final TextView status) {
        dbg("startLogin begin");
        if (status != null) {
            status.setText("正在申请登录…");
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final com.fz.autotask.modules.workbuddy.WorkBuddyLogin.Session s;
                try {
                    s = com.fz.autotask.modules.workbuddy.WorkBuddyLogin.start();
                } catch (Exception e) {
                    uiStatus(status, "申请登录失败：" + e.getMessage());
                    return;
                }
                cfg.addPendingState(s.state);
                dbg("start state=" + s.state + " url=" + s.authUrl);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        uiStatus(status, "请在浏览器里完成登录，回到本 App 会自动补上");
                        try {
                            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(s.authUrl));
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i);
                        } catch (Exception e) {
                            toast("打不开浏览器：" + e);
                        }
                    }
                });
                long deadline = System.currentTimeMillis() + 5 * 60 * 1000L;
                int tick = 0;
                while (System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(2500);
                    } catch (InterruptedException ignored) {
                    }
                    tick++;
                    com.fz.autotask.modules.workbuddy.WorkBuddyAccount a = null;
                    try {
                        a = com.fz.autotask.modules.workbuddy.WorkBuddyLogin.poll(s.state);
                    } catch (Exception ignored) {
                    }
                    if (a != null) {
                        saveAccount(a);
                        cfg.removePendingState(s.state);
                        dbg("saved uid=" + a.uid + " label=" + a.label);
                        uiStatus(status, "登录成功，已添加账号");
                        loadStats();
                        return;
                    }
                    final int t = tick;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!MainActivity.this.isFinishing()) {
                                status.setText("等待浏览器里完成登录…（" + (t * 3) + "s）");
                            }
                        }
                    });
                }
                uiStatus(status, "登录超时（5 分钟），可以再点一次");
            }
        }).start();
    }
    @Override
    protected void onResume() {
        dbg("onResume pending=[" + cfg.pendingState() + "]");
        super.onResume();
        checkSelfTestResult();
        // 从浏览器回来（或 App 被系统回收后重进）时补一次取 token —— 轮询线程可能已经没了
        final java.util.List<String> states = cfg.pendingStates();
        if (states.isEmpty()) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    com.fz.autotask.modules.workbuddy.WorkBuddyAccount a = null;
                    String hit = null;
                    // 把最近的 state 都试一遍（浏览器可能停在旧页面），每个连试 4 次、每次间隔 2s
                    for (int round = 0; round < 4 && a == null; round++) {
                        for (String st : states) {
                            try {
                                a = com.fz.autotask.modules.workbuddy.WorkBuddyLogin.poll(st);
                            } catch (Exception pe) {
                                dbg("resume poll err " + pe);
                            }
                            if (a != null) {
                                hit = st;
                                break;
                            }
                        }
                        if (a == null) {
                            dbg("resume round" + round + " still pending n=" + states.size());
                            try {
                                Thread.sleep(2000);
                            } catch (InterruptedException ignored) {
                            }
                        }
                    }
                    if (a == null) {
                        return;
                    }
                    cfg.removePendingState(hit);
                    saveAccount(a);
                    dbg("resume saved uid=" + a.uid);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            toast("登录成功，已添加账号");
                            loadStats();
                        }
                    });
                } catch (Exception e) {
                    dbg("resume poll err " + e);
                }
            }
        }).start();
    }
    private void uiStatus(final TextView t, final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                t.setText(msg);
                toast(msg);
            }
        });
    }

    private void saveAccount(com.fz.autotask.modules.workbuddy.WorkBuddyAccount a) {
        JSONArray cur = cfg.wbAccounts();
        JSONArray out = new JSONArray();
        boolean replaced = false;
        for (int i = 0; i < cur.length(); i++) {
            JSONObject o = cur.optJSONObject(i);
            if (o == null) {
                continue;
            }
            if (a.uid.equals(o.optString("uid", ""))) {
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
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                renderAccounts();
                refreshAccountInfo();
            }
        });
    }

    /** 登录诊断日志：写到外部私有目录，adb 可直接 pull（不需要 root）。 */
    private void dbg(String s) {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File f = new File(dir, "login_dbg.txt");
            if (f.length() > 200000) {
                f.delete();
            }
            FileOutputStream out = new FileOutputStream(f, true);
            try {
                String line = new java.text.SimpleDateFormat("HH:mm:ss",
                        java.util.Locale.CHINA).format(new java.util.Date()) + " " + s + "\n";
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private void section(LinearLayout root, String text) {        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(16);
        t.setPadding(0, dp(16), 0, dp(8));
        root.addView(t);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
    private static String readText(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            try { in.close(); } catch (Exception ignored) { }
        }
    }
}
