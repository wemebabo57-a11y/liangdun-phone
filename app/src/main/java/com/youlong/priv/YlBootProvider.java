package com.youlong.priv;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 自研特权内核 · 启动入口（ContentProvider）。
 *
 * <p><b>为什么要一个 ContentProvider</b>：{@code app_process} 拉起服务端时，
 * 服务端的 CLASSPATH 指向本应用 APK，因此它天然能读到这里的所有类；
 * 而"客户端 → 服务端"的第一跳需要一个**双方都在场的会合点**。
 * Provider 跑在应用进程里（因此能读到客户端的 Binder 实例），
 * 又能 fork 出 shell 身份的服务端进程，正好承担这个角色。
 *
 * <p>调用方式（客户端）：{@link #call}
 * <pre>
 *   Bundle args = new Bundle();
 *   args.putBinder(YlHandshake.KEY_HANDSHAKE, YlHandshake.asBinder(...));
 *   resolver.call(YlBootProvider.URI, "start", null, args);
 * </pre>
 *
 * <p>本类被调用时返回服务端 uid 等信息，便于诊断"到底起没起来、是不是 shell"。
 */
public final class YlBootProvider extends ContentProvider {

    private static final String TAG = "YlBootProvider";

    /** Provider 的 authority（与清单里声明必须一致）。 */
    public static final String AUTHORITY = "com.youlong.hd.ylpriv";

    public static final Uri URI = Uri.parse("content://" + AUTHORITY);

    /** 方法名：拉起服务端并完成握手。 */
    public static final String METHOD_START = "start";

    /** 方法名：只查状态，不起进程。 */
    public static final String METHOD_STATUS = "status";

    /** 服务端启动 + 握手的总超时。 */
    private static final long READY_TIMEOUT_MS = 15_000L;

    /** 防止并发重复拉起。 */
    private static final Object SPAWN_LOCK = new Object();

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        if (METHOD_STATUS.equals(method)) {
            out.putString("state", YlKernel.get().describeState());
            return out;
        }
        if (!METHOD_START.equals(method)) {
            out.putString("error", "未知方法: " + method);
            return out;
        }

        IBinder handshake = YlHandshake.fromBundle(extras);
        if (handshake == null) {
            out.putString("error", "缺少握手 Binder");
            return out;
        }

        // 已经连上 → 直接交付，不重复起进程
        YlKernel kernel = YlKernel.get();
        if (kernel.pingBinder()) {
            YlHandshake.deliver(handshake, kernel.serviceBinder());
            out.putString("result", "already-running");
            out.putInt("serverUid", kernel.getServerUid());
            return out;
        }

        synchronized (SPAWN_LOCK) {
            if (kernel.pingBinder()) {
                YlHandshake.deliver(handshake, kernel.serviceBinder());
                out.putString("result", "already-running");
                out.putInt("serverUid", kernel.getServerUid());
                return out;
            }
            // 在应用进程内注册握手回调：服务端起来后会把服务 Binder 送回这里
            final CountDownLatch latch = new CountDownLatch(1);
            kernel.armHandshake(handshake, latch);

            YlPrivLauncher.SpawnResult r = YlPrivLauncher.spawnServer(getContext());
            Log.i(TAG, "spawnServer ok=" + r.ok + " pid=" + r.pid + " msg=" + r.message);
            if (!r.ok) {
                out.putString("error", "拉起服务端失败: " + r.message);
                Log.e(TAG, "拉起服务端失败: " + r.message);
                return out;
            }
            try {
                boolean ok = latch.await(READY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (!ok) {
                    out.putString("error", "等待服务端握手超时");
                    Log.e(TAG, "等待服务端握手超时");
                    return out;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                out.putString("error", "握手被中断");
                return out;
            }
            out.putString("result", "started");
            out.putInt("serverUid", kernel.getServerUid());
            out.putString("pid", String.valueOf(r.pid));
            Log.i(TAG, "服务端握手完成 serverUid=" + kernel.getServerUid() + " pid=" + r.pid);
            return out;
        }
    }

    /** 顺带保证一个可写目录（服务端/诊断用）。 */
    public static File cacheDir(android.content.Context ctx) {
        return YlKernel.ensureDir(new File(ctx.getCacheDir(), "ylpriv"));
    }

    // ==================== ContentProvider 其余抽象方法（本 Provider 只做 IPC）====================

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
