package com.youlong.priv;

import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 自研特权内核 · 服务端主接口（手写 Binder）。
 *
 * <p>职责：探活、权限、发起特权进程。客户端拿到服务端 Binder 后包成
 * {@link Proxy}，即可像调本地方法一样使用。
 *
 * <p>关于"进程"这一层：{@link #newProcess} 返回的 {@link Process} 是一个**本地适配器**，
 * 它的三条管道来自服务端（{@link java.io.FileInputStream}/{@link FileOutputStream}），
 * 阻塞语义与 {@link java.lang.Process} 保持一致，因此上层代码（比如
 * {@code StellarUtils.runCommand}）不需要任何改动。
 */
public final class YlService {

    private YlService() {}

    /** 服务端 API 版本。 */
    public static final int SERVER_VERSION = YlProtocol.SERVER_VERSION;

    /** 特权权限名。 */
    public static final String PERMISSION_PRIVILEGED = YlProtocol.PERMISSION_PRIVILEGED;

    /** 授权请求码（客户端发起、服务端回传结果时原样带回）。 */
    public static final int REQ_PERMISSION = 1001;

    /** 打包参数用的 Bundle key（客户端 → 服务端握手）。 */
    public static final String KEY_CALLBACK = "callback";

    // ==================================================================
    // 客户端
    // ==================================================================

    /** 服务端接口的客户端代理。 */
    public static final class Proxy {
        private final IBinder mRemote;

        public Proxy(IBinder remote) {
            this.mRemote = remote;
        }

        public IBinder asBinder() {
            return mRemote;
        }

        /** 服务端是否存活。 */
        public boolean ping() {
            return YlProtocol.pingBinder(mRemote);
        }

        /** 查询是否已被授权。 */
        public boolean checkPermission(String permission) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                YlProtocol.writeDescriptor(data, YlProtocol.DESCRIPTOR_SERVICE);
                data.writeString(permission);
                mRemote.transact(YlProtocol.TX_CHECK_PERMISSION, data, reply, 0);
                reply.readException();
                return reply.readInt() != 0;
            } catch (Throwable t) {
                return false;
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        /** 发起授权请求（服务端会弹出授权 UI，结果通过 {@link YlApplication} 回调）。 */
        public void requestPermission(int requestCode, String permission) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                YlProtocol.writeDescriptor(data, YlProtocol.DESCRIPTOR_SERVICE);
                data.writeInt(requestCode);
                data.writeString(permission);
                mRemote.transact(YlProtocol.TX_REQUEST_PERMISSION, data, reply, 0);
                reply.readException();
            } catch (Throwable ignored) {
                // 授权是"尽力而为"的用户交互，失败不该崩调用方
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        /** 注册客户端回调（服务端就绪/授权结果）。 */
        public void attachApplication(IBinder callback, String packageName) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                YlProtocol.writeDescriptor(data, YlProtocol.DESCRIPTOR_SERVICE);
                data.writeStrongBinder(callback);
                Bundle args = new Bundle();
                args.putInt(YlProtocol.KEY_API_VERSION, SERVER_VERSION);
                args.putString(YlProtocol.KEY_PACKAGE_NAME, packageName);
                args.writeToParcel(data, 0);
                mRemote.transact(YlProtocol.TX_ATTACH, data, reply, 0);
                reply.readException();
            } catch (Throwable ignored) {
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        /** 服务端自身 uid（正常应为 2000 = shell）。 */
        public int getServerUid() {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                YlProtocol.writeDescriptor(data, YlProtocol.DESCRIPTOR_SERVICE);
                mRemote.transact(YlProtocol.TX_GET_SERVER_UID, data, reply, 0);
                reply.readException();
                return reply.readInt();
            } catch (Throwable t) {
                return -1;
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        /**
         * 在服务端拉起一个进程，返回可直接使用的 {@link Process}。
         *
         * <p>管道建在**客户端**这一侧（{@link android.os.ParcelFileDescriptor#createReliablePipe()}，
         * 底层 socketpair，跨进程一读一写），把要接到子进程的那一端交给服务端。
         * 服务端只用公开 API 把它接到 stdin/stdout/stderr，不需要 fd 传递、不碰隐藏接口。
         *
         * @param cmd 命令数组，例如 {@code new String[]{"sh"}}
         * @param env 环境变量（可为 null）
         * @param dir 工作目录（可为 null）
         */
        public Process newProcess(String[] cmd, String[] env, String dir)
                throws RemoteException {
            ParcelFileDescriptor[] stdinPipe = null;
            ParcelFileDescriptor[] stdoutPipe = null;
            ParcelFileDescriptor[] stderrPipe = null;
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                stdinPipe = ParcelFileDescriptor.createReliablePipe();
                stdoutPipe = ParcelFileDescriptor.createReliablePipe();
                stderrPipe = ParcelFileDescriptor.createReliablePipe();

                YlProtocol.writeDescriptor(data, YlProtocol.DESCRIPTOR_SERVICE);
                data.writeStringArray(cmd);
                data.writeStringArray(env);
                data.writeString(dir);
                stdinPipe[0].writeToParcel(data, 0);    // 子进程 stdin 读端
                stdoutPipe[1].writeToParcel(data, 0);   // 子进程 stdout 写端
                stderrPipe[1].writeToParcel(data, 0);   // 子进程 stderr 写端

                mRemote.transact(YlProtocol.TX_NEW_PROCESS, data, reply, 0);
                reply.readException();
                IBinder procBinder = reply.readStrongBinder();
                if (procBinder == null) {
                    throw new RemoteException("服务端未返回远程进程（可能未授权）");
                }
                // 交给服务端的那一端可以关了：子进程起来后已持有自己的副本
                closeQuietly(stdinPipe[0]);
                closeQuietly(stdoutPipe[1]);
                closeQuietly(stderrPipe[1]);
                return new YlProcess(new YlRemoteProcess(procBinder),
                        stdinPipe[1], stdoutPipe[0], stderrPipe[0]);
            } catch (RemoteException e) {
                closeQuietly(stdinPipe == null ? null : stdinPipe[0]);
                closeQuietly(stdoutPipe == null ? null : stdoutPipe[1]);
                closeQuietly(stderrPipe == null ? null : stderrPipe[1]);
                closeQuietly(stdinPipe == null ? null : stdinPipe[1]);
                closeQuietly(stdoutPipe == null ? null : stdoutPipe[0]);
                closeQuietly(stderrPipe == null ? null : stderrPipe[0]);
                throw e;
            } catch (Throwable t) {
                throw new RemoteException("建管道/发起进程失败: " + t);
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        private static void closeQuietly(ParcelFileDescriptor p) {
            if (p == null) return;
            try { p.close(); } catch (Throwable ignored) {}
        }
    }

    // ==================================================================
    // 本地 Process 适配器
    // ==================================================================

    /**
     * 把远程进程包装成 {@link Process}，行为与本地进程一致：
     * 管道可流式读写、{@code waitFor} 阻塞、{@code destroy} 强制结束。
     */
    public static final class YlProcess extends Process {
        private final YlRemoteProcess mRemote;
        private final android.os.ParcelFileDescriptor mStdinWrite;   // 客户端写 → 进程 stdin
        private final android.os.ParcelFileDescriptor mStdoutRead;   // 进程 stdout → 客户端读
        private final android.os.ParcelFileDescriptor mStderrRead;   // 进程 stderr → 客户端读
        private InputStream mIn;
        private OutputStream mOut;
        private InputStream mErr;
        private int mExit = -1;
        private boolean mDone;

        YlProcess(YlRemoteProcess remote,
                  android.os.ParcelFileDescriptor stdinWrite,
                  android.os.ParcelFileDescriptor stdoutRead,
                  android.os.ParcelFileDescriptor stderrRead) {
            this.mRemote = remote;
            this.mStdinWrite = stdinWrite;
            this.mStdoutRead = stdoutRead;
            this.mStderrRead = stderrRead;
        }

        @Override
        public synchronized OutputStream getOutputStream() {
            if (mOut == null) {
                mOut = (mStdinWrite == null) ? nullOut()
                        : new android.os.ParcelFileDescriptor.AutoCloseOutputStream(mStdinWrite);
            }
            return mOut;
        }

        @Override
        public synchronized InputStream getInputStream() {
            if (mIn == null) {
                mIn = (mStdoutRead == null) ? emptyIn()
                        : new android.os.ParcelFileDescriptor.AutoCloseInputStream(mStdoutRead);
            }
            return mIn;
        }

        @Override
        public synchronized InputStream getErrorStream() {
            if (mErr == null) {
                mErr = (mStderrRead == null) ? emptyIn()
                        : new android.os.ParcelFileDescriptor.AutoCloseInputStream(mStderrRead);
            }
            return mErr;
        }

        @Override
        public synchronized int waitFor() throws InterruptedException {
            if (mDone) return mExit;
            try {
                mExit = mRemote.waitFor();
                mDone = true;
            } catch (Throwable ignored) {
                mDone = true;
            }
            return mExit;
        }

        @Override
        public synchronized int exitValue() {
            if (mDone) return mExit;
            try {
                if (!mRemote.alive()) {
                    mExit = mRemote.exitValue();
                    mDone = true;
                    return mExit;
                }
            } catch (Throwable ignored) {
            }
            // 与 java.lang.Process 一致：没结束就抛
            throw new IllegalThreadStateException("进程尚未结束");
        }

        @Override
        public synchronized void destroy() {
            try {
                mRemote.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    // ==================================================================
    // 服务端桩
    // ==================================================================

    /** 服务端实现接口。 */
    public interface Stub {
        boolean ping();

        boolean checkPermission(String permission);

        void requestPermission(int requestCode, String permission);

        void attachApplication(IBinder callback, Bundle args);

        /**
         * 拉起进程。
         *
         * @param stdinRead  子进程 stdin 的读端（客户端建好传进来）
         * @param stdoutWrite 子进程 stdout 的写端
         * @param stderrWrite 子进程 stderr 的写端
         */
        IBinder newProcess(String[] cmd, String[] env, String dir,
                           android.os.ParcelFileDescriptor stdinRead,
                           android.os.ParcelFileDescriptor stdoutWrite,
                           android.os.ParcelFileDescriptor stderrWrite) throws RemoteException;

        int getServerUid();
    }

    /** 把服务端实现包成 Binder。 */
    public static IBinder asBinder(final Stub impl) {
        return new android.os.Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                YlProtocol.checkDescriptor(data, YlProtocol.DESCRIPTOR_SERVICE);
                switch (code) {
                    case YlProtocol.TX_PING:
                        reply.writeNoException();
                        reply.writeInt(impl.ping() ? 1 : 0);
                        return true;
                    case YlProtocol.TX_CHECK_PERMISSION: {
                        String permission = data.readString();
                        reply.writeNoException();
                        reply.writeInt(impl.checkPermission(permission) ? 1 : 0);
                        return true;
                    }
                    case YlProtocol.TX_REQUEST_PERMISSION: {
                        int requestCode = data.readInt();
                        String permission = data.readString();
                        impl.requestPermission(requestCode, permission);
                        reply.writeNoException();
                        return true;
                    }
                    case YlProtocol.TX_ATTACH: {
                        IBinder callback = data.readStrongBinder();
                        Bundle args = data.readInt() != 0
                                ? Bundle.CREATOR.createFromParcel(data) : null;
                        impl.attachApplication(callback, args);
                        reply.writeNoException();
                        return true;
                    }
                    case YlProtocol.TX_NEW_PROCESS: {
                        String[] cmd = data.createStringArray();
                        String[] env = data.createStringArray();
                        String dir = data.readString();
                        android.os.ParcelFileDescriptor stdinRead =
                                android.os.ParcelFileDescriptor.CREATOR.createFromParcel(data);
                        android.os.ParcelFileDescriptor stdoutWrite =
                                android.os.ParcelFileDescriptor.CREATOR.createFromParcel(data);
                        android.os.ParcelFileDescriptor stderrWrite =
                                android.os.ParcelFileDescriptor.CREATOR.createFromParcel(data);
                        IBinder proc = null;
                        try {
                            proc = impl.newProcess(cmd, env, dir,
                                    stdinRead, stdoutWrite, stderrWrite);
                        } finally {
                            // 服务端不持有这三端：子进程已经 dup 走了自己的副本
                            closeQuietly(stdinRead);
                            closeQuietly(stdoutWrite);
                            closeQuietly(stderrWrite);
                        }
                        reply.writeNoException();
                        reply.writeStrongBinder(proc);
                        return true;
                    }
                    case YlProtocol.TX_GET_SERVER_UID:
                        reply.writeNoException();
                        reply.writeInt(impl.getServerUid());
                        return true;
                    default:
                        return false;
                }
            }
        };
    }

    /** 供服务端实现类使用的小工具：拼一个进程的工作目录（不存在就用 null）。 */
    public static String normalizeDir(String dir) {
        if (dir == null || dir.isEmpty()) return null;
        File f = new File(dir);
        return f.isDirectory() ? dir : null;
    }

    /** 空输入流（管道缺失时的兜底）。 */
    public static InputStream emptyIn() {
        return new java.io.ByteArrayInputStream(new byte[0]);
    }

    /** 丢弃输出（管道缺失时的兜底）。 */
    public static OutputStream nullOut() {
        return new OutputStream() {
            @Override public void write(int b) {}
            @Override public void write(byte[] b, int off, int len) {}
        };
    }

    /** 静默关闭 PFD（多处在用，统一收口）。 */
    public static void closeQuietly(android.os.ParcelFileDescriptor p) {
        if (p == null) return;
        try { p.close(); } catch (Throwable ignored) {}
    }
}
