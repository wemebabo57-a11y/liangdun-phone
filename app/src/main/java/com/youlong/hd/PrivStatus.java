package com.youlong.hd;

/**
 * 自研 · 特权状态自检（界面与自动化测试共用同一套只读逻辑）。
 *
 * <p>抽成独立类的理由：界面（{@code PrivilegeActivity}）与自检入口
 * （{@code PrivSelfTestReceiver}）都要"查连接 / 查授权 / 跑一条特权命令"，
 * 逻辑重复会出现"界面显示已授权、日志里却是未授权"这种对不上的情况。
 */
final class PrivStatus {

    private PrivStatus() {}

    /** 自检结果快照。 */
    static final class Snapshot {
        final boolean available;
        final boolean granted;
        final String uidOutput;
        final String idOutput;

        Snapshot(boolean available, boolean granted, String uidOutput, String idOutput) {
            this.available = available;
            this.granted = granted;
            this.uidOutput = uidOutput;
            this.idOutput = idOutput;
        }

        /** 一行摘要（给日志/界面复用）。 */
        String summary() {
            return "连接=" + available + " 授权=" + granted
                    + " uid=" + uidOutput.replace('\n', ' ').trim()
                    + " id=" + idOutput.replace('\n', ' ').trim();
        }
    }

    /**
     * 直接调内核底层执行一条命令，**绕过 {@code hasStellarPermission()} 这道客户端前置判断**。
     *
     * <p>为什么要这样一个诊断：{@code StellarUtils.runCommand} 在未授权时会直接返回
     * "未授权"，这样"到底是没有权限，还是有权限但执行失败"就分不清了。
     * 这里直接调 {@code Stellar.newProcess}，把内核抛出的真实异常打出来，
     * 排障时能一眼看出卡在哪一环。
     */
    static String rawExec(String cmd) {
        try {
            java.lang.Process p = roro.stellar.Stellar.INSTANCE.newProcess(
                    new String[]{"sh", "-c", cmd}, null, null);
            if (p == null) return "(newProcess 返回 null)";
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
            StringBuilder out = new StringBuilder();
            String line;
            int n = 0;
            while ((line = r.readLine()) != null && n < 20) {
                out.append(line).append(' ');
                n++;
            }
            r.close();
            p.waitFor();
            String s = out.toString().trim();
            return s.isEmpty() ? "(无输出)" : s;
        } catch (Throwable t) {
            return "EX:" + t.getClass().getSimpleName() + ":" + t.getMessage();
        }
    }

    /**
     * 请求特权服务端把 Binder 重新投递一次。
     *
     * <p><b>为什么需要它</b>：服务端的 Binder 是通过一条 {@code REQUEST_BINDER} 广播
     * 触发的投递流程送进本应用的。一旦**应用进程重启**，进程内的静态 Binder 就没了，
     * 而服务端（uid 2000 的独立进程）不会自己再投 —— 表现就是界面一直显示"未连接"，
     * 但服务端其实好好地在跑（{@code ps} 能看到 {@code stellar_server}）。
     * 这里重发那条广播，让服务端把 Binder 再送一次。
     *
     * @return true = 已发出请求（不代表已连上，连上是异步的）
     */
    static boolean requestReconnect(android.content.Context ctx) {
        try {
            android.content.Intent i = new android.content.Intent(
                    "roro.stellar.intent.action.REQUEST_BINDER");
            i.setComponent(new android.content.ComponentName(ctx.getPackageName(),
                    "roro.stellar.manager.receiver.StellarReceiver"));
            ctx.sendBroadcast(i);
            CrashLogger.event("[特权面板] 已请求服务端重新投递 Binder");
            return true;
        } catch (Throwable t) {
            CrashLogger.event("[特权面板] 请求重投 Binder 失败", t);
            return false;
        }
    }

    /** 采集一次状态（会在未授权时跳过命令，避免把"未授权"误报成"命令失败"）。 */
    static Snapshot collect() {
        boolean available = false;
        boolean granted = false;
        String uid = "(未取到)";
        String id = "(未取到)";
        try {
            // 只做"轻量探活"：直接查 Binder，未连接就如实显示，不在界面线程上等内核冷启动
            // （旧写法 isStellarAvailable() 内部会等 15~45 秒，面板会一直转圈）。
            available = StellarUtils.isPrivilegeBinderAlive();
            granted = available && StellarUtils.hasStellarPermission();
            if (granted) {
                uid = StellarUtils.runCommand("id -u", 8000);
                id = StellarUtils.runCommand("id", 12000);
            } else {
                uid = "(未授权)";
                id = "(未授权)";
            }
        } catch (Throwable t) {
            id = "ERR:" + t;
        }
        return new Snapshot(available, granted, uid, id);
    }
}
