package com.youlong.hd;

import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 自研 · 特权服务面板（**本应用的界面，不是第三方管理器的界面**）。
 *
 * <p>替代原先"打开特权服务管理器"跳转到内置第三方管理器 UI 的做法：
 * 这里用本应用自己的配色、排版与文案，把用户真正需要的东西放在一屏里：
 * 连接状态、特权身份、授权状态与授权入口、以及一条可当场验证的特权命令。
 *
 * <p>它只调用特权内核的**公开 API**（{@code StellarUtils} / {@code Stellar}），
 * 不碰内核内部实现，因此内核换实现时本页无需改动。
 */
public class PrivilegeActivity extends AppCompatActivity {

    private static final String TAG = "PrivilegePanel";

    /** 授权记录（与 MainActivity 里授权相关流程共用同一份偏好，便于状态一致）。 */
    private static final String PREFS = "shield_prefs";
    private static final String KEY_GRANTED = "priv_granted";

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final AtomicBoolean mBusy = new AtomicBoolean(false);

    private TextView mStatusValue;
    private TextView mIdentityValue;
    private TextView mAuthValue;
    private TextView mResultValue;
    private Button mBtnRequest;
    private Button mBtnTest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("特权服务");
        setContentView(buildContentView());
        refresh();
    }

    /**
     * 只读自检：把面板要显示的三行状态直接输出到日志。
     *
     * <p>为什么需要它：面板本身 {@code exported=false}（只有本应用能打开），
     * 自动化测试没法从外部 startActivity 去核对界面内容；
     * 这个方法让同一套逻辑可以被"调用一次 + 读日志"验证，
     * 不需要为了测试把界面对外开放。
     *
     * <p>调用方式（仅调试用）：
     * {@code adb shell am broadcast -a com.youlong.hd.PRIV_SELFTEST -n com.youlong.hd/.PrivSelfTestReceiver}
     */
    public void runSelfTestAndLog() {
        new Thread(() -> {
            PrivStatus.Snapshot s = PrivStatus.collect();
            CrashLogger.event("[特权面板] " + s.summary());
            YouLongApp app = YouLongApp.instance();
            if (app != null) {
                CrashLogger.dumpEventsToFile(app, "probe.txt", "=== 自研特权面板 自检 ===");
            }
        }, "priv-panel-selftest").start();
    }

    // ==================================================================
    // 界面（全部由本应用自己搭建，不引用任何第三方界面代码）
    // ==================================================================

    private View buildContentView() {
        final int pad = dp(20);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF5F5F7);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 标题区
        TextView title = new TextView(this);
        title.setText("特权服务");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF1C1C1E);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("免 root 以系统 shell 身份执行卸载 / 强停 / 冻结等操作");
        subtitle.setTextSize(13);
        subtitle.setTextColor(0xFF6E6E73);
        subtitle.setPadding(0, dp(6), 0, dp(18));
        root.addView(subtitle);

        // 状态卡片
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.WHITE);
        card.setPadding(pad, dp(16), pad, dp(16));
        root.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mStatusValue = addRow(card, "连接状态", "检测中…");
        mIdentityValue = addRow(card, "运行身份", "检测中…");
        mAuthValue = addRow(card, "授权状态", "检测中…");

        // 按钮区
        mBtnRequest = new Button(this);
        mBtnRequest.setText("申请特权授权");
        mBtnRequest.setTextSize(16);
        mBtnRequest.setOnClickListener(v -> onRequestPermission());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(18);
        mBtnRequest.setLayoutParams(rp);
        root.addView(mBtnRequest);

        mBtnTest = new Button(this);
        mBtnTest.setText("自检：跑一条特权命令");
        mBtnTest.setTextSize(16);
        mBtnTest.setOnClickListener(v -> onSelfTest());
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(10);
        mBtnTest.setLayoutParams(tp);
        root.addView(mBtnTest);

        Button btnReconnect = new Button(this);
        btnReconnect.setText("重新连接特权服务");
        btnReconnect.setTextSize(16);
        btnReconnect.setOnClickListener(v -> {
            PrivStatus.requestReconnect(this);
            mMain.postDelayed(this::refresh, 2500);
        });
        LinearLayout.LayoutParams rcp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rcp.topMargin = dp(10);
        btnReconnect.setLayoutParams(rcp);
        root.addView(btnReconnect);
        Button btnList = new Button(this);
        btnList.setText("查看已安装应用");
        btnList.setTextSize(16);
        btnList.setOnClickListener(v -> {
            try { startActivity(new Intent(this, AppListActivity.class)); }
            catch (Throwable t) { CrashLogger.event("[特权面板] 打开应用列表失败", t); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        btnList.setLayoutParams(lp);
        root.addView(btnList);

        Button btnSettings = new Button(this);
        btnSettings.setText("特权服务设置");
        btnSettings.setTextSize(16);
        btnSettings.setOnClickListener(v -> {
            try { startActivity(new Intent(this, PrivSettingsActivity.class)); }
            catch (Throwable t) { CrashLogger.event("[特权面板] 打开设置页失败", t); }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(10);
        btnSettings.setLayoutParams(sp);
        root.addView(btnSettings);
        // 结果区
        TextView resultLabel = new TextView(this);
        resultLabel.setText("命令输出");
        resultLabel.setTextSize(13);
        resultLabel.setTextColor(0xFF6E6E73);
        resultLabel.setPadding(0, dp(22), 0, dp(8));
        root.addView(resultLabel);

        mResultValue = new TextView(this);
        mResultValue.setText("（点上面的按钮跑一条看看）");
        mResultValue.setTextSize(12);
        mResultValue.setTextColor(0xFF3A3A3C);
        mResultValue.setTypeface(Typeface.MONOSPACE);
        mResultValue.setTextIsSelectable(true);
        mResultValue.setBackgroundColor(Color.WHITE);
        mResultValue.setPadding(dp(14), dp(14), dp(14), dp(14));
        root.addView(mResultValue, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tip = new TextView(this);
        tip.setText("说明：特权由系统 shell 用户（uid 2000）提供，不需要 root，"
                + "也不需要安装任何第三方应用。");
        tip.setTextSize(12);
        tip.setTextColor(0xFF8E8E93);
        tip.setPadding(0, dp(16), 0, 0);
        root.addView(tip);

        return scroll;
    }

    /** 加一行"标签 + 值"，返回值那一栏方便后续刷新。 */
    private TextView addRow(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(15);
        l.setTextColor(0xFF1C1C1E);
        row.addView(l, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.42f));

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(14);
        v.setTextColor(0xFF6E6E73);
        v.setGravity(Gravity.END);
        row.addView(v, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.58f));

        parent.addView(row);
        return v;
    }

    // ==================================================================
    // 行为
    // ==================================================================

    /**
     * 刷新三行状态。
     *
     * <p><b>性能红线（2026-10-05 修复"打开就卡死"）</b>：这里**只能**用
     * {@link StellarUtils#isPrivilegeBinderAlive()} 这种毫秒级探活。
     * 之前用的是 {@link StellarUtils#isStellarAvailable()} —— 它内部会等内核冷启动
     * （最长十几秒），一旦服务端没起，界面就会一直转圈、滑动直接卡死。
     * 需要真正连服务端时，走 {@link #onSelfTest()} / 「重新连接」按钮的异步路径。
     */
    private void refresh() {
        // 未连接时请服务端重投一次 Binder（应用进程重启后常见）；这是发广播，立即返回
        if (!StellarUtils.isPrivilegeBinderAlive()) {
            PrivStatus.requestReconnect(this);
        }
        new Thread(() -> {
            // 全部用非阻塞探活，任何一步都不许等
            final boolean available = StellarUtils.isPrivilegeBinderAlive();
            final boolean granted = available && StellarUtils.hasStellarPermission();
            final String identity;
            if (!available) {
                identity = "未连接（点下方按钮重连）";
            } else if (!granted) {
                identity = "已连接，待授权";
            } else {
                // 只有连上且已授权时，才去跑一条命令确认身份（此时是快的）
                String out = StellarUtils.runCommand("id -u", 3000);
                identity = "uid=" + (TextUtils.isEmpty(out) ? "?" : out.trim()) + "（shell 为 2000）";
            }
            mMain.post(() -> {
                mStatusValue.setText(available ? "已连接" : "未连接");
                mStatusValue.setTextColor(available ? 0xFF34C759 : 0xFFFF3B30);
                mIdentityValue.setText(identity);
                mAuthValue.setText(granted ? "已授权" : "未授权");
                mAuthValue.setTextColor(granted ? 0xFF34C759 : 0xFFFF9500);
                mBtnRequest.setEnabled(!granted);
                mBtnRequest.setText(granted ? "已授权" : "申请特权授权");
            });
        }, "priv-refresh").start();
    }

    /** 申请授权：走内核的公开授权入口。 */
    private void onRequestPermission() {
        try {
            // 记录一次"用户发起过授权"，便于主界面提示
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            sp.edit().putBoolean(KEY_GRANTED, false).apply();

            // 内核的授权入口：未授权时服务端会拉起授权弹窗（现在由本应用自研的
            // PrivAuthActivity 接管呈现，见 app/src/main/AndroidManifest.xml）。
            roro.stellar.Stellar.INSTANCE.requestPermission("stellar", 1001);
        } catch (Throwable t) {
            CrashLogger.event("[特权面板] 申请授权失败: " + t);
        }
        mMain.postDelayed(this::refresh, 1200);
    }

    /** 自检：跑一条特权命令并把原始输出显示出来。 */
    private void onSelfTest() {
        if (!mBusy.compareAndSet(false, true)) return;
        mResultValue.setText("执行中…");
        new Thread(() -> {
            final String out = StellarUtils.runCommand("id", 12000);
            CrashLogger.event("[特权面板] 自检 id => " + out.replace('\n', ' '));
            mMain.post(() -> {
                mResultValue.setText(out);
                mBusy.set(false);
                refresh();
            });
        }, "priv-selftest").start();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
