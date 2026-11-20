package com.fz.autotask.core;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 运行流水账：每次跑完都往外部私有目录 run.log 追加一行，
 * 事后可以直接用 adb 查「到底几点跑过、结果是什么」，不依赖通知有没有弹出来。
 */
public final class RunLog {

    private static final String FILE = "run.log";
    private static final long MAX = 64 * 1024;

    private RunLog() {
    }

    public static void append(Context ctx, String head, java.util.List<String> lines) {
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            File f = new File(dir, FILE);
            if (f.exists() && f.length() > MAX) {
                f.delete();
            }
            StringBuilder sb = new StringBuilder();
            sb.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date()))
                    .append("  ").append(head == null ? "" : head);
            if (lines != null) {
                for (String s : lines) {
                    sb.append(" | ").append(s);
                }
            }
            sb.append('\n');
            FileOutputStream out = new FileOutputStream(f, true);
            try {
                out.write(sb.toString().getBytes("UTF-8"));
                out.flush();
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 最后一条运行记录（没有则返回 null）。 */
    public static String last(Context ctx) {
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) {
                return null;
            }
            File f = new File(dir, FILE);
            if (!f.exists()) {
                return null;
            }
            byte[] buf = new byte[(int) Math.min(f.length(), 8192)];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                int n = in.read(buf);
                if (n <= 0) {
                    return null;
                }
                String all = new String(buf, 0, n, "UTF-8").trim();
                int k = all.lastIndexOf('\n');
                return k >= 0 ? all.substring(k + 1) : all;
            } finally {
                in.close();
            }
        } catch (Throwable e) {
            return null;
        }
    }
    /** 上次运行的时间（run.log 的最后修改时间），没有则 0。 */
    public static long lastRunAt(Context ctx) {
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) {
                return 0L;
            }
            File f = new File(dir, FILE);
            return f.exists() ? f.lastModified() : 0L;
        } catch (Throwable e) {
            return 0L;
        }
    }
}