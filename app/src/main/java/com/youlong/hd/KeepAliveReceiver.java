package com.youlong.hd;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * 保活闹钟接收器 — 每2分钟检查 ProtectService 是否存活，若被系统杀死则自动重启
 */
public class KeepAliveReceiver extends BroadcastReceiver {

    private static final String TAG = "KeepAliveReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = context.getSharedPreferences("shield_prefs", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("protect_on", false)) {
            return; // 用户已关闭守护，不重启
        }

        if (!isServiceRunning(context, ProtectService.class)) {
            Log.w(TAG, "ProtectService 已停止，自动重启...");
            Intent si = new Intent(context, ProtectService.class);
            ContextCompat.startForegroundService(context, si);
        }

        // 同时确保 ForegroundService 也在运行（双进程守护）
        if (!isServiceRunning(context, ForegroundService.class)) {
            Log.w(TAG, "ForegroundService 已停止，自动重启...");
            Intent fi = new Intent(context, ForegroundService.class);
            ContextCompat.startForegroundService(context, fi);
        }
    }

    private boolean isServiceRunning(Context ctx, Class<?> serviceClass) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            for (ActivityManager.RunningServiceInfo s : am.getRunningServices(Integer.MAX_VALUE)) {
                if (serviceClass.getName().equals(s.service.getClassName())) return true;
            }
        }
        return false;
    }
}
