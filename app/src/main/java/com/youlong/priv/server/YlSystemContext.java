package com.youlong.priv.server;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

/**
 * 自研特权内核 · 服务端 Context 获取（**不用隐藏 API 编译期符号**）。
 *
 * <p>服务端跑在 shell 身份的 {@code app_process} 里，没有普通 {@code Application}，
 * 但需要 Context 来做两件事：拿自己的 uid 做权限归属判断、读写授权记录。
 *
 * <p>{@code android.app.ActivityThread} 不在公开 SDK 里（直接 import 会编译不过），
 * 所以这里**用反射**调用 {@code ActivityThread.systemMain().getSystemContext()} ——
 * 这是 AOSP 长期稳定存在的入口；反射失败也不致命，调用方会退回文件存储。
 */
final class YlSystemContext {

    private static final String TAG = "YlSystemContext";

    private YlSystemContext() {}

    /** 取系统 Context；取不到返回 null（调用方必须能容忍）。 */
    static Context get() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("systemMain").invoke(null);
            if (thread == null) return null;
            Object ctx = at.getMethod("getSystemContext").invoke(thread);
            if (ctx instanceof Context) {
                return (Context) ctx;
            }
        } catch (Throwable t) {
            Log.w(TAG, "反射取系统 Context 失败（不影响特权执行）: " + t);
        }
        return null;
    }

    /**
     * 服务端可用的持久化目录：优先 Context 私有目录，退回 shell 可写目录。
     */
    static File storeDir(Context ctx) {
        if (ctx != null) {
            try {
                File dir = new File(ctx.getFilesDir(), "ylpriv");
                if (dir.exists() || dir.mkdirs()) return dir;
            } catch (Throwable ignored) {
            }
        }
        File fallback = new File("/data/local/tmp/ylpriv");
        if (!fallback.exists()) fallback.mkdirs();
        return fallback;
    }

    /** 读一个属性文件（不存在返回空 Properties）。 */
    static Properties loadProps(File f) {
        Properties p = new Properties();
        if (f == null || !f.exists()) return p;
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            p.load(in);
        } catch (Throwable t) {
            Log.w(TAG, "读属性失败: " + f, t);
        } finally {
            closeQuietly(in);
        }
        return p;
    }

    /** 写属性文件（失败只记日志）。 */
    static void saveProps(File f, Properties p) {
        if (f == null || p == null) return;
        OutputStream out = null;
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            out = new FileOutputStream(f);
            p.store(out, "youlong privilege auth");
        } catch (Throwable t) {
            Log.w(TAG, "写属性失败: " + f, t);
        } finally {
            closeQuietly(out);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (Throwable ignored) {}
    }
}
