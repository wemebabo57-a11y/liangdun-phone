package com.youlong.hd;

import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 自研 · 特权服务设置页（**本应用自己的界面**）。
 *
 * <p>把"特权相关的管理动作"收在一处，替代第三方管理器里的设置界面：
 * <ul>
 *   <li>当前特权状态（连接 / 授权 / 运行身份）；</li>
 *   <li>申请或重新授权；</li>
 *   <li>跑一条特权命令自检，结果直接显示（用户自己能判断"到底通不通"）；</li>
 *   <li>快捷入口：应用列表、系统应用设置页。</li>
 * </ul>
 */
public class PrivSettingsActivity extends AppCompatActivity {

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final AtomicBoolean mBusy = new AtomicBoolean(false);

    private TextView mStatusTv;
    private TextView mAuthTv;
    private TextView mResultTv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("特权服务设置");
        setContentView(buildContentView());
        refreshStatus();
    }

    private View buildContentView() {
        final int pad = dp(20);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF5F5F7);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("特权服务");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF1C1C1E);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("以系统 shell 身份执行卸载 / 强制停止 / 冻结，用于清除顽固病毒。不需要 root。");
        sub.setTextSize(13);
        sub.setTextColor(0xFF6E6E73);
        sub.setPadding(0, dp(6), 0, dp(18));
        root.addView(sub);

        // 状态卡
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.WHITE);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        root.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mStatusTv = addRow(card, "连接状态", "检测中…");
        mAuthTv = addRow(card, "授权状态", "检测中…");

        // 操作按钮
        addButton(root, "申请 / 重新授权", () -> {
            try {
                roro.stellar.Stellar.INSTANCE.requestPermission("stellar", 1001);
                toast("已发起授权请求，请在弹出的窗口里选择");
            } catch (Throwable t) {
                CrashLogger.event("[特权设置] 发起授权失败", t);
                toast("发起授权失败：" + t.getMessage());
            }
            mMain.postDelayed(this::refreshStatus, 1500);
        });

        addButton(root, "重新连接特权服务", () -> {
            PrivStatus.requestReconnect(this);
            toast("已请求服务端重新投递，请稍候");
            mMain.postDelayed(this::refreshStatus, 2500);
        });
        addButton(root, "自检：跑一条特权命令", this::selfTest);

        addButton(root, "查看应用列表", () -> {
            try {
                startActivity(new Intent(this, AppListActivity.class));
            } catch (Throwable t) {
                CrashLogger.event("[特权设置] 打开应用列表失败", t);
            }
        });

        addButton(root, "打开系统应用设置", () -> {
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Throwable t) {
                CrashLogger.event("[特权设置] 打开系统应用设置失败", t);
            }
        });

        // 结果区
        TextView label = new TextView(this);
        label.setText("输出");
        label.setTextSize(13);
        label.setTextColor(0xFF6E6E73);
        label.setPadding(0, dp(20), 0, dp(8));
        root.addView(label);

        mResultTv = new TextView(this);
        mResultTv.setText("（点上面的自检按钮跑一条看看）");
        mResultTv.setTextSize(12);
        mResultTv.setTypeface(Typeface.MONOSPACE);
        mResultTv.setTextColor(0xFF3A3A3C);
        mResultTv.setTextIsSelectable(true);
        mResultTv.setBackgroundColor(Color.WHITE);
        mResultTv.setPadding(dp(14), dp(14), dp(14), dp(14));
        root.addView(mResultTv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return scroll;
    }

    private void addButton(LinearLayout parent, String text, Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(16);
        b.setAllCaps(false);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(12);
        parent.addView(b, p);
    }

    private TextView addRow(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(15);
        l.setTextColor(0xFF1C1C1E);
        row.addView(l, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.4f));

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(14);
        v.setTextColor(0xFF6E6E73);
        v.setGravity(Gravity.END);
        row.addView(v, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.6f));

        parent.addView(row);
        return v;
    }

    // ==================================================================
    // 行为
    // ==================================================================

    private void refreshStatus() {
        // 未连接时先请求服务端重投 Binder（应用进程重启后常见），再刷新
        if (!StellarUtils.isPrivilegeBinderAlive()) {
            PrivStatus.requestReconnect(this);
        }
        new Thread(() -> {
            // 轻量探活：不在界面上等内核冷启动（旧写法会卡几十秒）
            // 同样只用非阻塞探活（见 PrivilegeActivity.refresh 的说明）
            final boolean alive = StellarUtils.isPrivilegeBinderAlive();
            final boolean granted = alive && StellarUtils.hasStellarPermission();
            mMain.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                mStatusTv.setText(alive ? "已连接" : "未连接");
                mStatusTv.setTextColor(alive ? 0xFF34C759 : 0xFFFF3B30);
                mAuthTv.setText(granted ? "已授权" : "未授权");
                mAuthTv.setTextColor(granted ? 0xFF34C759 : 0xFFFF9500);
            });
        }, "priv-settings-refresh").start();
    }

    private void selfTest() {
        if (!mBusy.compareAndSet(false, true)) return;
        mResultTv.setText("执行中…");
        new Thread(() -> {
            PrivStatus.Snapshot s = PrivStatus.collect();
            mMain.post(() -> {
                mResultTv.setText(s.summary());
                mBusy.set(false);
                refreshStatus();
            });
        }, "priv-settings-selftest").start();
    }

    private void toast(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
