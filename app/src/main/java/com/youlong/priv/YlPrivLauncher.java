package com.youlong.priv;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.youlong.hd.YouLongApp;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 自研特权内核 · 服务端启动器（**完全自研**）。
 *
 * <p>作用只有一个：以 <b>shell(uid 2000)</b> 身份把自研服务端跑起来。
 * 用的是 AOSP 公开的 {@code app_process} 机制，不引用、不复制任何第三方框架代码。
 *
 * <pre>
 *   CLASSPATH=&lt;本应用 APK&gt; /system/bin/app_process[64|32] /system/bin \
 *       --nice-name=youlong_priv com.youlong.priv.server.YlServerMain
 * </pre>
 *
 * <p>为什么 CLASSPATH 指向本应用 APK：服务端的类（{@code com.youlong.priv.*}）
 * 就在这个 APK 里，指向它服务端才加载得到。
 *
 * <p>为什么 shell 身份就是特权：adb 的 shell 用户持有
 * {@code FORCE_STOP_PACKAGES} / {@code READ_LOGS} / {@code PACKAGE_USAGE_STATS} 等权限，
 * 足以执行 {@code pm}、{@code am}、{@code dumpsys} —— 这正是"免 root 强停/卸载/冻结"的来源。
 */
public final class YlPrivLauncher {

    private static final String TAG = "YlPrivLauncher";

    /** 服务端入口类。 */
    public static final String SERVER_ENTRY_CLASS =
            "com.youlong.priv.server.YlServerMain";

    /** 服务端进程名（便于在 ps / 日志里辨认）。 */
    public static final String SERVER_NICE_NAME = "youlong_priv";

    private YlPrivLauncher() {}

    /** 启动结果。 */
    public static final class SpawnResult {
        public final boolean ok;
        public final long pid;
        public final String message;

        SpawnResult(boolean ok, long pid, String message) {
            this.ok = ok;
            this.pid = pid;
            this.message = message;
        }
    }

    /** 本应用 APK 路径（app_process 的 CLASSPATH 目标）。 */
    public static String apkPath() {
        try {
            YouLongApp app = YouLongApp.instance();
            if (app != null) {
                android.content.pm.ApplicationInfo ai = app.getApplicationInfo();
                if (ai.publicSourceDir != null) return ai.publicSourceDir;
                if (ai.sourceDir != null) return ai.sourceDir;
            }
        } catch (Throwable t) {
            Log.w(TAG, "从 YouLongApp 取 APK 路径失败", t);
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof android.app.Application) {
                android.content.pm.ApplicationInfo ai =
                        ((android.app.Application) app).getApplicationInfo();
                if (ai.publicSourceDir != null) return ai.publicSourceDir;
                if (ai.sourceDir != null) return ai.sourceDir;
            }
        } catch (Throwable t) {
            Log.w(TAG, "反射 ActivityThread 取 APK 路径失败", t);
        }
        return null;
    }

    /** 与当前进程位数一致的 app_process 可执行文件。 */
    public static String appProcessPath() {
        boolean is64 = "64".equals(System.getProperty("sun.arch.data.model"))
                || isAbi64(System.getProperty("os.arch"));
        if (!is64 && Build.SUPPORTED_64_BIT_ABIS != null) {
            is64 = Build.SUPPORTED_64_BIT_ABIS.length > 0;
        }
        String path = is64 ? "/system/bin/app_process64" : "/system/bin/app_process32";
        if (new File(path).exists()) return path;
        String fallback = is64 ? "/system/bin/app_process32" : "/system/bin/app_process64";
        if (new File(fallback).exists()) return fallback;
        return "/system/bin/app_process";
    }

    private static boolean isAbi64(String abi) {
        return abi != null && abi.contains("64");
    }

    /**
     * 拉起服务端。
     *
     * @return 启动结果；{@code ok=false} 时 {@code message} 里带原因
     */
    public static SpawnResult spawnServer(Context ctx) {
        String apk = apkPath();
        if (apk == null) {
            return new SpawnResult(false, -1, "无法定位本应用 APK 路径");
        }
        String appProcess = appProcessPath();
        List<String> argv = new ArrayList<>();
        argv.add("sh");
        argv.add("-c");
        // nohup + & ：让服务端脱离本进程存活；日志进 logcat 方便排查
        String cmd = "CLASSPATH=" + shellQuote(apk) + " "
                + appProcess + " /system/bin"
                + " --nice-name=" + SERVER_NICE_NAME
                + " " + SERVER_ENTRY_CLASS
                + " >/dev/null 2>&1 &";
        argv.add(cmd);
        try {
            Process p = new ProcessBuilder(argv).start();
            int code = p.waitFor();
            if (code != 0) {
                return new SpawnResult(false, -1, "启动命令退出码=" + code);
            }
            long pid = waitForServerPid(6000L);
            Log.i(TAG, "已拉起特权服务端 pid=" + pid + " app_process=" + appProcess);
            return new SpawnResult(true, pid, "启动命令已下发");
        } catch (IOException e) {
            return new SpawnResult(false, -1, "启动失败: " + e);
        } catch (Throwable t) {
            return new SpawnResult(false, -1, "启动异常: " + t);
        }
    }

    /**
     * 等服务端进程出现在进程表里（拿 pid 只为诊断与去重）。
     *
     * <p>用 {@code pidof} 而不是轮询 ps：toybox 都带 pidof，且输出最省事。
     */
    private static long waitForServerPid(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                Process p = new ProcessBuilder("sh", "-c", "pidof " + SERVER_NICE_NAME)
                        .redirectErrorStream(true).start();
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
                String line = r.readLine();
                r.close();
                p.waitFor();
                if (line != null && !line.trim().isEmpty()) {
                    String first = line.trim().split("\\s+")[0];
                    return Long.parseLong(first);
                }
            } catch (Throwable ignored) {
            }
            try { Thread.sleep(200); } catch (InterruptedException e) { break; }
        }
        return -1;
    }

    private static String shellQuote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
