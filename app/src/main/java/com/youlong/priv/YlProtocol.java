package com.youlong.priv;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * 自研特权内核 · 传输层协议（手写 Binder，不依赖 AIDL 代码生成）。
 *
 * <p><b>为什么手写而不写 .aidl</b>：
 * <ol>
 *   <li>本工程的 AIDL 编译对路径非 ASCII 敏感（历史上踩过 {@code MalformedInputException}），
 *       手写可彻底绕开；</li>
 *   <li>协议完全自持，事务码、字段顺序一看就懂，便于排障；</li>
 *   <li>不引入任何第三方框架，符合"完全自研"的要求。</li>
 * </ol>
 *
 * <p><b>协议总览</b>：客户端把命令交给服务端，服务端以 shell(uid 2000) 身份
 * {@code fork/exec} 出真正的进程，再把三个管道（stdin/stdout/stderr）的文件描述符
 * 交回客户端 —— 于是客户端拿到的是一个普通的 {@link java.lang.Process}，可以流式读写。
 *
 * <pre>
 *   IYlService       服务端主接口：探活 / 权限 / 发起进程
 *   IYlRemoteProcess 单个远程进程：三条管道 + 等待 / 退出码 / 销毁
 *   IYlApplication   客户端回调：服务端就绪通知、授权结果通知
 * </pre>
 */
public final class YlProtocol {

    private YlProtocol() {}

    /** 服务端 API 版本：用于客户端与服务端握手比对。 */
    public static final int SERVER_VERSION = 1;

    /** 权限名：与用户的授权记录绑定（后续可扩展多权限）。 */
    public static final String PERMISSION_PRIVILEGED = "privileged";

    /**
     * 客户端传给服务端的上下文参数（握手用）。字段名沿用通用命名，
     * 语义由本协议自行定义，不依赖任何第三方框架的常量。
     */
    public static final String KEY_API_VERSION = "api_version";
    public static final String KEY_PACKAGE_NAME = "package_name";
    public static final String KEY_CLIENT_UID = "client_uid";
    public static final String KEY_PERMISSION_GRANTED = "permission_granted";
    public static final String KEY_SERVER_UID = "server_uid";
    public static final String KEY_SERVER_VERSION = "server_version";
    public static final String KEY_PERMISSION = "permission";
    public static final String KEY_ALLOWED = "allowed";

    // ==================== IYlService 事务码 ====================
    /** boolean ping() */
    public static final int TX_PING = 1;
    /** boolean checkPermission(String) */
    public static final int TX_CHECK_PERMISSION = 2;
    /** void requestPermission(int requestCode, String permission) */
    public static final int TX_REQUEST_PERMISSION = 3;
    /** void attachApplication(IYlApplication, Bundle) */
    public static final int TX_ATTACH = 4;
    /**
     * IYlRemoteProcess newProcess(String[] cmd, String[] env, String dir,
     *                             PFD stdinRead, PFD stdoutWrite, PFD stderrWrite)
     *
     * <p>三个 PFD 由客户端通过 {@link ParcelFileDescriptor#createReliablePipe()} 建好并传进来：
     * 客户端留另一端自己读写，服务端把它们接到子进程的 stdin/stdout/stderr。
     * 这样不必做 fd 传递、也不碰任何隐藏 API。
     */
    public static final int TX_NEW_PROCESS = 5;
    /** int getServerUid() */
    public static final int TX_GET_SERVER_UID = 6;

    // ==================== IYlRemoteProcess 事务码 ====================
    /** ParcelFileDescriptor getOutputStream() */
    public static final int TX_PROC_OUT = 20;
    /** ParcelFileDescriptor getInputStream() */
    public static final int TX_PROC_IN = 21;
    /** ParcelFileDescriptor getErrorStream() */
    public static final int TX_PROC_ERR = 22;
    /** int waitFor() */
    public static final int TX_PROC_WAIT = 23;
    /** int exitValue() */
    public static final int TX_PROC_EXIT = 24;
    /** void destroy() */
    public static final int TX_PROC_DESTROY = 25;
    /** boolean alive() */
    public static final int TX_PROC_ALIVE = 26;

    // ==================== IYlApplication 事务码（服务端 → 客户端）====================
    /** void onServerReady() */
    public static final int TX_APP_SERVER_READY = 40;
    /** void onPermissionResult(int requestCode, String permission, boolean allowed) */
    public static final int TX_APP_PERM_RESULT = 41;

    /** 统一的接口描述符，写进 Parcel 供 {@code onTransact} 校验。 */
    public static final String DESCRIPTOR_SERVICE = "com.youlong.priv.IYlService";
    public static final String DESCRIPTOR_PROCESS = "com.youlong.priv.IYlRemoteProcess";
    public static final String DESCRIPTOR_APPLICATION = "com.youlong.priv.IYlApplication";

    /** 写接口描述符（Parcel 协议第一步）。 */
    public static void writeDescriptor(Parcel data, String descriptor) {
        data.writeInterfaceToken(descriptor);
    }

    /**
     * 校验接口描述符，不匹配直接抛（防止把别的服务端返回的 Binder 误当自家用）。
     *
     * <p>用公开的 {@code enforceInterface}（它内部完成"读 token + 比对 + 抛异常"），
     * 而不是隐藏 API {@code readInterfaceToken} —— 后者不在公开 SDK 里，编译期取不到。
     */
    public static void checkDescriptor(Parcel data, String descriptor) throws RemoteException {
        data.enforceInterface(descriptor);
    }

    /** 探活：向 Binder 发一个 ping，通即认为服务端在跑。 */
    public static boolean pingBinder(IBinder binder) {
        if (binder == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            writeDescriptor(data, DESCRIPTOR_SERVICE);
            binder.transact(TX_PING, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0;
        } catch (Throwable t) {
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
