package com.youlong.hd;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 自研 · 特权状态自检入口（**调试用，正式包默认关闭**）。
 *
 * <p>为什么要它：自研的界面页（面板 / 应用列表 / 设置）都是 {@code exported=false}
 * —— 只有本应用能打开，外部（含 adb）启动会被系统拒绝。这对安全是对的，
 * 但自动化测试就没法核对界面里的数据。这个 receiver 用与界面**完全相同**的只读逻辑
 * 跑一次自检并写进运行轨迹，供自动化核对事实。
 *
 * <p><b>安全设计</b>：默认**关闭**。必须先把调试开关打开才会执行：
 * <pre>
 *   # 1) 打开调试开关
 *   adb shell am broadcast -a com.youlong.hd.PRIV_SELFTEST_ENABLE \
 *       -n com.youlong.hd/.PrivSelfTestReceiver
 *   # 2) 触发自检
 *   adb shell am broadcast -a com.youlong.hd.PRIV_SELFTEST \
 *       -n com.youlong.hd/.PrivSelfTestReceiver
 * </pre>
 * 开关状态存在 {@code shield_prefs/priv_selftest_enabled}，随时可关掉。
 *
 * <p>它不接受任何外部参数，只跑固定的 {@code id} / {@code id -u} 与一次应用统计，
 * 结果仅写入本应用自己的日志、不返回给调用方。
 */
public class PrivSelfTestReceiver extends BroadcastReceiver {

    /** 触发自检。 */
    public static final String ACTION = "com.youlong.hd.PRIV_SELFTEST";
    /** 打开/关闭自检开关（调试用）。 */
    public static final String ACTION_ENABLE = "com.youlong.hd.PRIV_SELFTEST_ENABLE";
    /** 关闭自检开关（调试用）。 */
    public static final String ACTION_DISABLE = "com.youlong.hd.PRIV_SELFTEST_DISABLE";
    /** 调试用：从应用内部发起一次特权授权请求。 */
    public static final String ACTION_REQUEST = "com.youlong.hd.PRIV_SELFTEST_REQUEST";
    /** 调试用：从应用内部打开自研应用列表页（该页 exported=false）。 */
    public static final String ACTION_OPEN_APPLIST = "com.youlong.hd.PRIV_SELFTEST_OPEN_APPLIST";

    static final String PREFS = "shield_prefs";
    static final String KEY_SELFTEST = "priv_selftest_enabled";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        final String action = intent.getAction();

        if (ACTION_ENABLE.equals(action)) {
            setEnabled(context, true);
            return;
        }
        if (ACTION_DISABLE.equals(action)) {
            setEnabled(context, false);
            return;
        }
        if (ACTION_REQUEST.equals(action)) {
            if (!isDebugEnabled(context)) {
                CrashLogger.event("[特权面板] 授权请求被忽略（调试开关未开）");
                return;
            }
            // 调试用：从应用内部发起一次特权授权请求。
            // 服务端收到后会拉起授权弹窗（由自研的 PrivAuthActivity 呈现）。
            // 之所以不能在外部用 am start 直接拉起授权页：服务端会校验
            // "这次授权请求是否由它自己发起"，外部伪造的请求拿不到授权。
            CrashLogger.event("[特权面板] 调试发起授权请求");
            try {
                roro.stellar.Stellar.INSTANCE.requestPermission("stellar", 1001);
            } catch (Throwable t) {
                CrashLogger.event("[特权面板] 发起授权失败", t);
            }
            return;
        }
        if (ACTION_OPEN_APPLIST.equals(action)) {
            if (!isDebugEnabled(context)) {
                CrashLogger.event("[特权面板] 打开应用列表被忽略（调试开关未开）");
                return;
            }
            // 调试用：自研页面 exported=false，外部无法 startActivity，
            // 这里从应用内部打开，便于自动化核对界面是否正常（含滑动性能）。
            CrashLogger.event("[特权面板] 调试打开应用列表页");
            try {
                android.content.Intent i = new android.content.Intent(context, AppListActivity.class);
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(i);
            } catch (Throwable t) {
                CrashLogger.event("[特权面板] 打开应用列表失败", t);
            }
            return;
        }
        if (!ACTION.equals(action)) return;

        if (!isDebugEnabled(context)) {
            CrashLogger.event("[特权面板] 自检广播被忽略（调试开关未开）");
            return;
        }
        CrashLogger.event("[特权面板] 收到自检广播，开始采集状态");
        new Thread(() -> {
            try {
                PrivStatus.Snapshot s = PrivStatus.collect();
                CrashLogger.event("[特权面板] " + s.summary());
                CrashLogger.event("[应用列表] " + AppListActivity.summarize(context));
                // 内核层直连诊断：绕过客户端的前置权限判断，看内核到底怎么回
                CrashLogger.event("[内核诊断] ping=" + safePing()
                        + " checkSelf=" + safeCheck()
                        + " rawExec(id)=" + PrivStatus.rawExec("id"));
                android.app.Application app = YouLongApp.instance();
                if (app != null) {
                    CrashLogger.dumpEventsToFile(app, "probe.txt", "=== 自研特权面板 自检 ===");
                }
            } catch (Throwable t) {
                CrashLogger.event("[特权面板] 自检失败: " + t);
            }
        }, "priv-selftest-rx").start();
    }

    /** 自检是否允许（正式包默认关闭）。 */
    static boolean isDebugEnabled(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_SELFTEST, false);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void setEnabled(Context ctx, boolean enabled) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_SELFTEST, enabled).apply();
            CrashLogger.event("[特权面板] 自检调试开关 = " + enabled);
        } catch (Throwable ignored) {
        }
    }
private static String safePing() {
        try { return String.valueOf(roro.stellar.Stellar.INSTANCE.pingBinder()); }
        catch (Throwable t) { return "EX:" + t; }
    }

    private static String safeCheck() {
        try { return String.valueOf(roro.stellar.Stellar.INSTANCE.checkSelfPermission("stellar")); }
        catch (Throwable t) { return "EX:" + t; }
    }
}