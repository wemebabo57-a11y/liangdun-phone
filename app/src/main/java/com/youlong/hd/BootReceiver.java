package com.youlong.hd;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * 开机自启 - 检查 SharedPreferences 中的 protect_on 状态，若开启则启动 ProtectService
 * 同时设置 AlarmManager 保活闹钟（每2分钟检查一次）
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)) return;

        SharedPreferences prefs = context.getSharedPreferences("shield_prefs", Context.MODE_PRIVATE);
        boolean protectOn = prefs.getBoolean("protect_on", false);

        if (protectOn) {
            Intent serviceIntent = new Intent(context, ProtectService.class);
            ContextCompat.startForegroundService(context, serviceIntent);
            // 仅在守护开启时设置保活闹钟
            setupKeepAliveAlarm(context);
        } else {
            // 守护关闭时取消保活闹钟
            cancelKeepAliveAlarm(context);
        }
    }

    private void setupKeepAliveAlarm(Context context) {
        Intent keepIntent = new Intent(context, KeepAliveReceiver.class);
        PendingIntent keepPi = PendingIntent.getBroadcast(context, 0, keepIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            try {
                am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + 60_000, 120_000, keepPi);
                Log.i(TAG, "开机保活闹钟已设置（每2分钟）");
            } catch (Exception e) {
                Log.e(TAG, "设置保活闹钟失败", e);
            }
        }
    }

    private void cancelKeepAliveAlarm(Context context) {
        Intent keepIntent = new Intent(context, KeepAliveReceiver.class);
        PendingIntent keepPi = PendingIntent.getBroadcast(context, 0, keepIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.cancel(keepPi);
            Log.i(TAG, "保活闹钟已取消");
        }
    }
}
