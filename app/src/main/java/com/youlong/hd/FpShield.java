package com.youlong.hd;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.webkit.WebSettings;

import org.json.JSONObject;

/**
 * 浏览器指纹伪装（FpShield：固定 iOS + Chrome 预设，仅作用于应用内 WebView）。
 *
 * <p>两层伪装：
 * <ol>
 * <li>UA 层：{@link #applyTo} 把 WebView 默认 UA 替换为固定 iOS-Chrome UA；</li>
 * <li>JS 层：{@link #spoofScript} 在每次页面开始加载时注入，
 * 用不可枚举 getter 覆盖 navigator.userAgent / platform / vendor /
 * hardwareConcurrency / deviceMemory，回到固定值。</li>
 * </ol>
 *
 * <p>开关持久化在 fp_shield_prefs.enabled，默认开启。
 */
public final class FpShield {

    private static final String TAG = "FpShield";
    private static final String PREF = "fp_shield_prefs";
    private static final String K_ENABLED = "enabled";

    /** 固定预设名（用户选定：iOS + Chrome，不做多预设切换）。 */
    public static final String PRESET_NAME = "iOS 17 + Chrome 123";

    /** 固定预设 UA：iPhone + CriOS（Chrome for iOS）。 */
    public static final String PRESET_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X)"
            + " AppleWebKit/605.1.15 (KHTML, like Gecko)"
            + " CriOS/123.0.6312.52 Mobile/15E148 Safari/604.1";

    private FpShield() {
    }

    public static boolean isEnabled(Context ctx) {
        try {
            return ctx.getApplicationContext()
                    .getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getBoolean(K_ENABLED, true);
        } catch (Exception e) {
            return true;
        }
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        try {
            SharedPreferences sp = ctx.getApplicationContext()
                    .getSharedPreferences(PREF, Context.MODE_PRIVATE);
            sp.edit().putBoolean(K_ENABLED, enabled).apply();
        } catch (Exception e) {
            Log.w(TAG, "setEnabled: " + e.getMessage());
        }
    }

    /**
     * 把伪装应用到 WebView 配置。开启则设固定 UA，关闭则恢复系统默认 UA。
     * 必须在 UI 线程调用（WebView 要求）。
     */
    public static void applyTo(Context ctx, WebSettings settings) {
        if (ctx == null || settings == null) return;
        try {
            if (isEnabled(ctx)) {
                settings.setUserAgentString(PRESET_UA);
            } else {
                try {
                    settings.setUserAgentString(WebSettings.getDefaultUserAgent(ctx));
                } catch (Throwable t) {
                    // 极老 WebView 没有 getDefaultUserAgent：退回空串让系统重填默认
                    try { settings.setUserAgentString(null); } catch (Throwable ignored) {}
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "applyTo: " + e.getMessage());
        }
    }

    /**
     * navigator 指纹覆盖脚本（onPageStarted 注入）。
     * UA 内无单引号，可直接拼进 JS 单引号字符串。
     */
    public static String spoofScript() {
        return "(function(){try{"
                + "var __ldUa='" + PRESET_UA + "';"
                + "try{Object.defineProperty(navigator,'userAgent',"
                + "{get:function(){return __ldUa;},configurable:true});}catch(e){}"
                + "try{Object.defineProperty(navigator,'appVersion',"
                + "{get:function(){return __ldUa;},configurable:true});}catch(e){}"
                + "try{Object.defineProperty(navigator,'platform',"
                + "{get:function(){return 'iPhone';},configurable:true});}catch(e){}"
                + "try{Object.defineProperty(navigator,'vendor',"
                + "{get:function(){return 'Google Inc.';},configurable:true});}catch(e){}"
                + "try{Object.defineProperty(navigator,'hardwareConcurrency',"
                + "{get:function(){return 4;},configurable:true});}catch(e){}"
                + "try{Object.defineProperty(navigator,'deviceMemory',"
                + "{get:function(){return 4;},configurable:true});}catch(e){}"
                + "}catch(e){}})();";
    }

    /** 状态 JSON：{enabled, preset, ua}。 */
    public static String getStatusJson(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            boolean enabled = isEnabled(ctx);
            o.put("enabled", enabled);
            o.put("preset", PRESET_NAME);
            o.put("ua", enabled ? PRESET_UA : "");
        } catch (Exception e) {
            try { o.put("error", e.getClass().getSimpleName()); } catch (Exception ignored) {}
        }
        return o.toString();
    }
}
