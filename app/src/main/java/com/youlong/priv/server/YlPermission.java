package com.youlong.priv.server;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import com.youlong.priv.YlApplication;
import com.youlong.priv.YlProtocol;

import java.io.File;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自研特权内核 · 授权裁决（服务端侧）。
 *
 * <p>授权模型：**按客户端 uid 记录**，只有被允许过的 uid 才能发起特权命令。
 * 授权界面在应用进程（服务端在 shell 身份下没有 UI），所以服务端只做两件事：
 * 记录裁决结果、把请求转达给客户端回调。
 *
 * <p>存储：优先 Context 的 {@code SharedPreferences}；系统 Context 不可用时
 * 退回 {@code /data/local/tmp/ylpriv/auth.properties}（shell 身份自己可写），
 * 保证授权记录一定存得下，不会因为取不到 Context 就退化成每次都问。
 */
final class YlPermission {

    private static final String TAG = "YlPermission";
    private static final String PREFS_CTX = "yl_priv_auth";
    private static final String AUTH_FILE = "auth.properties";
    private static final String KEY_PREFIX = "granted_uid_";

    /** 客户端在 attach 时报上来的「应用自身 uid」，用于免弹窗放行。 */
    private static volatile int sClientAppUid = -1;

    private static final Set<YlApplication> CALLBACKS =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<YlApplication, Boolean>());

    private YlPermission() {}

    private static int serverUid() {
        return android.os.Process.myUid();
    }

    /** 判定 uid 是否有特权：shell 自己 / root / 本应用自身 → 放行；其余查记录。 */
    static boolean checkUid(YlServerMain server, int uid) {
        if (uid == serverUid()) return true;
        if (uid == 0) return true;
        if (sClientAppUid > 0 && uid == sClientAppUid) return true;
        Context ctx = server.context();
        if (ctx != null) {
            try {
                if (uid == ctx.getApplicationInfo().uid) return true;
            } catch (Throwable ignored) {
            }
        }
        return readGrant(server, uid);
    }

    static boolean check(YlServerMain server, String permission) {
        return checkUid(server, android.os.Binder.getCallingUid());
    }

    /** 记录一次授权结果。 */
    static void grant(YlServerMain server, int uid, boolean allowed) {
        Context ctx = server.context();
        if (ctx != null) {
            try {
                ctx.getSharedPreferences(PREFS_CTX, Context.MODE_PRIVATE)
                        .edit().putBoolean(KEY_PREFIX + uid, allowed).apply();
                Log.i(TAG, "授权记录写入 prefs uid=" + uid + " allowed=" + allowed);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "写 prefs 失败，退回文件", t);
            }
        }
        Properties p = YlSystemContext.loadProps(authFile(server));
        p.setProperty(KEY_PREFIX + uid, String.valueOf(allowed));
        YlSystemContext.saveProps(authFile(server), p);
        Log.i(TAG, "授权记录写入文件 uid=" + uid + " allowed=" + allowed);
    }

    /** 客户端挂载：记录其 uid / 包名，并回调"服务端就绪"。 */
    static void attachApplication(YlServerMain server, YlApplication app, Bundle args) {
        int uid = android.os.Binder.getCallingUid();
        if (app == null) return;
        CALLBACKS.add(app);
        if (args != null) {
            int reported = args.getInt(YlProtocol.KEY_CLIENT_UID, -1);
            if (reported > 0) sClientAppUid = reported;
        }
        if (sClientAppUid <= 0 && uid > 0) sClientAppUid = uid;
        try {
            app.onServerReady();
        } catch (Throwable t) {
            Log.w(TAG, "回调 onServerReady 失败", t);
        }
        Log.i(TAG, "客户端已挂载 uid=" + uid
                + " 记录的应用uid=" + sClientAppUid
                + " pkg=" + (args == null ? "?" : args.getString(YlProtocol.KEY_PACKAGE_NAME)));
    }

    /** 客户端请求授权：转达给已注册的客户端回调（由应用进程弹窗）。 */
    static void request(YlServerMain server, int requestCode, String permission) {
        int uid = android.os.Binder.getCallingUid();
        Log.i(TAG, "收到授权请求 uid=" + uid + " permission=" + permission);
        for (YlApplication cb : CALLBACKS) {
            try {
                cb.onPermissionResult(requestCode, permission, true);
            } catch (Throwable t) {
                CALLBACKS.remove(cb);
            }
        }
    }

    private static boolean readGrant(YlServerMain server, int uid) {
        Context ctx = server.context();
        if (ctx != null) {
            try {
                return ctx.getSharedPreferences(PREFS_CTX, Context.MODE_PRIVATE)
                        .getBoolean(KEY_PREFIX + uid, false);
            } catch (Throwable ignored) {
            }
        }
        Properties p = YlSystemContext.loadProps(authFile(server));
        return Boolean.parseBoolean(p.getProperty(KEY_PREFIX + uid, "false"));
    }

    private static File authFile(YlServerMain server) {
        return new File(YlSystemContext.storeDir(server.context()), AUTH_FILE);
    }

    /** 授权记录位置（诊断用）。 */
    static String describeStore(YlServerMain server) {
        Context ctx = server.context();
        if (ctx != null) {
            try {
                return new File(ctx.getDataDir(),
                        "shared_prefs/" + PREFS_CTX + ".xml").getAbsolutePath();
            } catch (Throwable ignored) {
            }
        }
        return authFile(server).getAbsolutePath();
    }
}
