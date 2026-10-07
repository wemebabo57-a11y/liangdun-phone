package com.youlong.hd;

import android.util.Log;

/**
 * 特权工具类 —— 提供静态方法执行特权命令，供 ProtectService / MainActivity /
 * ShieldWarnActivity 等各处复用。
 *
 * <p><b>2026-10 内核选型（真机结论）</b>：完全自研的"应用进程内拉起 app_process"路线
 * 在 Android 14 非 root 设备上拿不到 shell 身份（子进程只能继承应用 uid，且卡死在
 * 启动早期）。因此内核改用工程内置的开源框架 Stellar（MPL-2.0 / Apache-2.0），
 * 用户可见的界面与授权流程才是本应用自研的部分。
 *
 * <p><b>对外契约保持不变</b>：下面 4 个方法签名与语义与替换前一致，
 * 工程内所有调用点无需改动：
 * <ul>
 *   <li>{@link #isStellarAvailable()} —— 特权服务是否在线</li>
 *   <li>{@link #hasStellarPermission()} —— 是否已获授权</li>
 *   <li>{@link #newPrivilegedProcess(String[], String[], String)} —— 起一个特权进程</li>
 *   <li>{@link #runCommand(String, long)} —— 跑一条命令并取回输出</li>
 * </ul>
 *
 * <p><b>⚠️ 主线程禁止阻塞（2026-10-05 修复"一弹提示就卡死"）</b>：
 * 下面这些方法**都会与特权服务端通信**，服务端没起来时可能等待数秒。
 * 之前 ProtectService 在**主线程**上调用它们，界面直接卡死。
 * 现在所有方法都加了主线程保护 —— 在主线程上只做毫秒级探活，
 * 需要真连接时由调用方放到后台线程（或由 {@link #requestReconnect} 异步拉起）。
 */
public class StellarUtils {

    private static final String TAG = "YlPrivUtils";

    /** 无条件探活：只查 Binder 在不在，毫秒级返回、绝不触发拉起。 */
    public static boolean isPrivilegeBinderAlive() {
        try {
            return roro.stellar.Stellar.INSTANCE.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 当前是否运行在主线程。 */
    private static boolean onMainThread() {
        try {
            return android.os.Looper.myLooper() == android.os.Looper.getMainLooper();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 请求特权服务端重新投递 Binder（异步、立即返回）。
     *
     * <p>服务端是独立进程，应用进程重启后进程内的 Binder 就没了，而服务端不会自己再投
     * —— 表现是"未连接但服务端明明在跑"。这条广播让服务端重投一次。
     */
    public static void requestReconnect(android.content.Context ctx) {
        try {
            if (ctx == null) return;
            android.content.Intent i = new android.content.Intent(
                    "roro.stellar.intent.action.REQUEST_BINDER");
            i.setComponent(new android.content.ComponentName(ctx.getPackageName(),
                    "roro.stellar.manager.receiver.StellarReceiver"));
            ctx.sendBroadcast(i);
            Log.i(TAG, "已请求服务端重新投递 Binder");
        } catch (Throwable t) {
            Log.w(TAG, "请求重投 Binder 失败", t);
        }
    }

    /**
     * 检查特权服务是否可用。
     *
     * <p><b>主线程调用时只做毫秒级探活</b>：连不上就返回 false，并异步请服务端重投一次，
     * 绝不在这里等 —— 否则界面会卡死（这是真机上踩过的坑）。
     * 后台线程调用时才允许"等一会儿"。
     */
    public static boolean isStellarAvailable() {
        try {
            if (isPrivilegeBinderAlive()) return true;
            if (onMainThread()) {
                // 主线程：只报告现状 + 异步拉起，绝不阻塞
                YouLongApp app = YouLongApp.instance();
                requestReconnect(app);
                Log.i(TAG, "主线程探活：当前未连接（已异步请求重投，不阻塞界面）");
                return false;
            }
            // 后台线程：允许等服务端冷启动（仍由 PingBinder 判定，不空等）
            long deadline = System.currentTimeMillis() + 8000L;
            while (System.currentTimeMillis() < deadline) {
                if (isPrivilegeBinderAlive()) return true;
                Thread.sleep(200L);
            }
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "特权服务不可用", t);
            return false;
        }
    }

    /** 是否已获得特权授权（同样带主线程保护）。 */
    public static boolean hasStellarPermission() {
        try {
            if (!isPrivilegeBinderAlive()) {
                if (onMainThread()) {
                    requestReconnect(YouLongApp.instance());
                    return false;
                }
                if (!isStellarAvailable()) return false;
            }
            return roro.stellar.Stellar.INSTANCE.checkSelfPermission("stellar");
        } catch (Throwable t) {
            Log.w(TAG, "权限查询失败", t);
            return false;
        }
    }

    /**
     * 通过内核创建特权进程。
     *
     * <p>返回的是普通的 {@link Process}：命令由内核以 **shell(uid 2000)** 身份执行。
     */
    public static Process newPrivilegedProcess(String[] cmd, String[] env, String dir) {
        try {
            Process p = roro.stellar.Stellar.INSTANCE.newProcess(cmd, env, dir);
            if (p == null) throw new IllegalStateException("内核未返回进程（可能未授权）");
            return p;
        } catch (Throwable t) {
            throw new RuntimeException("特权进程创建失败: " + t, t);
        }
    }

    /**
     * 执行命令并返回输出（静态方法，可在任何地方调用）。
     *
     * <p>实现：拉起 {@code sh}，把命令从 stdin 灌进去（支持管道/重定向），
     * 轮询 exitValue 避免 waitFor 死锁。
     *
     * <p>⚠️ 本方法会阻塞到命令结束或超时，**必须在后台线程调用**；
     * 在主线程调用且特权不可用时会立刻返回错误（不做任何等待），避免卡界面。
     *
     * @param command   要执行的 shell 命令
     * @param timeoutMs 超时毫秒，&lt;=0 不设超时
     * @return 命令执行的标准输出；失败返回 "ERROR:xxx"
     */
    public static String runCommand(final String command, long timeoutMs) {
        if (onMainThread()) {
            // 主线程只做"能不能跑"的快速判断，不做任何等待
            if (!isPrivilegeBinderAlive() || !hasStellarPermission()) {
                requestReconnect(YouLongApp.instance());
                return "ERROR:特权服务不可用（主线程不做等待，已请求重连）";
            }
        } else {
            if (!isStellarAvailable()) return "ERROR:特权服务未启动";
            if (!hasStellarPermission()) {
                requestReconnect(YouLongApp.instance());
                return "ERROR:特权服务未授权";
            }
        }

        final StringBuilder stdout = new StringBuilder();
        final StringBuilder stderr = new StringBuilder();
        Process process = null;

        try {
            String cleanCmd = command.trim();
            Log.d(TAG, "Exec: " + cleanCmd);

            process = newPrivilegedProcess(new String[]{"sh"}, null, null);
            final Process p = process;

            java.io.OutputStream stdin = p.getOutputStream();
            stdin.write((cleanCmd + "\nexit\n").getBytes("UTF-8"));
            stdin.flush();
            stdin.close();

            Thread outReader = new Thread(() -> {
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    java.io.InputStream in = p.getInputStream();
                    while ((n = in.read(buf)) != -1)
                        stdout.append(new String(buf, 0, n));
                } catch (Exception e) {
                    Log.e(TAG, "out read err", e);
                }
            });
            Thread errReader = new Thread(() -> {
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    java.io.InputStream in = p.getErrorStream();
                    while ((n = in.read(buf)) != -1)
                        stderr.append(new String(buf, 0, n));
                } catch (Exception e) {
                    Log.e(TAG, "err read err", e);
                }
            });
            outReader.start();
            errReader.start();

            long deadline = System.currentTimeMillis() + timeoutMs;
            boolean done = false;
            while (!done) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                try {
                    done = p.waitFor(Math.min(remaining, 200L),
                            java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    Log.w(TAG, "waitFor err: " + e.getMessage());
                    done = true;
                }
            }

            if (!done) {
                p.destroyForcibly();
                outReader.join(500);
                errReader.join(500);
                return "ERROR:执行超时（" + timeoutMs + "ms）";
            }

            outReader.join(2000);
            errReader.join(2000);

            int exitCode = 0;
            boolean exitOk = false;
            for (int i = 0; i < 5; i++) {
                try {
                    exitCode = p.exitValue();
                    exitOk = true;
                    break;
                } catch (Exception e) {
                    if (i < 4)
                        try { Thread.sleep(100); } catch (InterruptedException ie) { break; }
                }
            }

            String out = stdout.toString().trim();
            String err = stderr.toString().trim();

            Log.d(TAG, "exitOk=" + exitOk + " exitCode=" + exitCode
                    + " outLen=" + out.length() + " errLen=" + err.length());

            if (out.length() > 0) return out;
            if (!exitOk) {
                if (err.length() > 0) return err;
                return "OK";
            }
            if (err.length() > 0) {
                if (exitCode != 0 && exitCode != -1)
                    return "ERROR:执行失败(code:" + exitCode + ") " + err;
                return err;
            }
            if (exitCode != 0 && exitCode != -1)
                return "ERROR:执行失败(code:" + exitCode + ")，无输出";

        } catch (Exception e) {
            Log.e(TAG, "Exception", e);
            return "ERROR:" + e.getClass().getSimpleName() + ": " + e.getMessage();
        }

        return "OK";
    }
}
