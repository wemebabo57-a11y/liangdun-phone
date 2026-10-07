package com.youlong.priv;

import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

/**
 * 自研特权内核 · 远程进程接口（手写 Binder 客户端桩）。
 *
 * <p>一个"远程进程"= 服务端以 shell 身份 exec 出来的真实进程，加上它的三条管道。
 * 客户端通过 {@link #getInputStream()} 等拿到 {@link ParcelFileDescriptor} 后自建
 * {@code FileInputStream} 读取，因此可以直接套进 {@link java.lang.Process} 的语义里。
 */
public final class YlRemoteProcess {

    private final IBinder mRemote;

    public YlRemoteProcess(IBinder remote) {
        this.mRemote = remote;
    }

    /** 底层 Binder（需要做死亡监听时用）。 */
    public IBinder asBinder() {
        return mRemote;
    }

    /** 进程的 stdin（客户端 → 进程）。 */
    public ParcelFileDescriptor getOutputStream() throws RemoteException {
        return transactFd(YlProtocol.TX_PROC_OUT);
    }

    /** 进程的 stdout（进程 → 客户端）。 */
    public ParcelFileDescriptor getInputStream() throws RemoteException {
        return transactFd(YlProtocol.TX_PROC_IN);
    }

    /** 进程的 stderr（进程 → 客户端）。 */
    public ParcelFileDescriptor getErrorStream() throws RemoteException {
        return transactFd(YlProtocol.TX_PROC_ERR);
    }

    /** 阻塞等待进程结束，返回退出码。 */
    public int waitFor() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            YlProtocol.writeDescriptor(data, YlProtocol.DESCRIPTOR_PROCESS);
            mRemote.transact(YlProtocol.TX_PROC_WAIT, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /** 取退出码；进程还在跑时返回 -1（与 {@code java.lang.Process} 语义一致：仅在结束时有效）。 */
    public int exitValue() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            YlProtocol.writeDescriptor(data, YlProtocol.DESCRIPTOR_PROCESS);
            mRemote.transact(YlProtocol.TX_PROC_EXIT, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /** 是否有权限读退出码（未结束时 {@code exitValue()} 会抛的预检，避免拿异常当流程）。 */
    public boolean alive() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            YlProtocol.writeDescriptor(data, YlProtocol.DESCRIPTOR_PROCESS);
            mRemote.transact(YlProtocol.TX_PROC_ALIVE, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /** 强制结束进程。 */
    public void destroy() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            YlProtocol.writeDescriptor(data, YlProtocol.DESCRIPTOR_PROCESS);
            mRemote.transact(YlProtocol.TX_PROC_DESTROY, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private ParcelFileDescriptor transactFd(int code) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            YlProtocol.writeDescriptor(data, YlProtocol.DESCRIPTOR_PROCESS);
            mRemote.transact(code, data, reply, 0);
            reply.readException();
            // ParcelFileDescriptor.CREATOR 会 dup 一份 fd 交给客户端持有
            return reply.readInt() != 0
                    ? ParcelFileDescriptor.CREATOR.createFromParcel(reply)
                    : null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    // ==================================================================
    // 服务端桩：服务端只需实现下面这几个方法
    // ==================================================================

    /** 服务端实现接口。 */
    public interface Stub {
        ParcelFileDescriptor getOutputStream();

        ParcelFileDescriptor getInputStream();

        ParcelFileDescriptor getErrorStream();

        int waitFor();

        int exitValue();

        boolean alive();

        void destroy();
    }

    /** 把服务端实现包成 Binder。 */
    public static IBinder asBinder(final Stub impl) {
        return new android.os.Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                YlProtocol.checkDescriptor(data, YlProtocol.DESCRIPTOR_PROCESS);
                switch (code) {
                    case YlProtocol.TX_PROC_OUT:
                        writeFd(reply, impl.getOutputStream());
                        return true;
                    case YlProtocol.TX_PROC_IN:
                        writeFd(reply, impl.getInputStream());
                        return true;
                    case YlProtocol.TX_PROC_ERR:
                        writeFd(reply, impl.getErrorStream());
                        return true;
                    case YlProtocol.TX_PROC_WAIT:
                        reply.writeNoException();
                        reply.writeInt(impl.waitFor());
                        return true;
                    case YlProtocol.TX_PROC_EXIT:
                        reply.writeNoException();
                        reply.writeInt(impl.exitValue());
                        return true;
                    case YlProtocol.TX_PROC_ALIVE:
                        reply.writeNoException();
                        reply.writeInt(impl.alive() ? 1 : 0);
                        return true;
                    case YlProtocol.TX_PROC_DESTROY:
                        impl.destroy();
                        reply.writeNoException();
                        return true;
                    default:
                        return false;
                }
            }
        };
    }

    private static void writeFd(Parcel reply, ParcelFileDescriptor pfd) {
        reply.writeNoException();
        if (pfd == null) {
            reply.writeInt(0);
        } else {
            reply.writeInt(1);
            pfd.writeToParcel(reply, 0);
        }
    }
}
