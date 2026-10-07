package com.youlong.priv;

import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * 自研特权内核 · 客户端回调接口（服务端 → 客户端方向）。
 *
 * <p>为什么需要它：服务端被拉起后，需要把"我起来了""你的授权结果是什么"这两类事件
 * 推回客户端。同时它也是**服务端 Binder 的回传通道** —— 客户端在启动请求里带上这个
 * 回调的 Binder，服务端起来后通过它把主服务接口交回客户端（见 {@link YlService}）。
 */
public final class YlApplication {

    private final IBinder mRemote;

    public YlApplication(IBinder remote) {
        this.mRemote = remote;
    }

    /** 服务端就绪通知。 */
    public void onServerReady() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            YlProtocol.writeDescriptor(data, YlProtocol.DESCRIPTOR_APPLICATION);
            mRemote.transact(YlProtocol.TX_APP_SERVER_READY, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /** 授权结果通知。 */
    public void onPermissionResult(int requestCode, String permission, boolean allowed)
            throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            YlProtocol.writeDescriptor(data, YlProtocol.DESCRIPTOR_APPLICATION);
            data.writeInt(requestCode);
            data.writeString(permission);
            data.writeInt(allowed ? 1 : 0);
            mRemote.transact(YlProtocol.TX_APP_PERM_RESULT, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    // ==================================================================
    // 客户端桩：客户端只需实现下面这两个回调
    // ==================================================================

    /** 客户端实现接口。 */
    public interface Stub {
        void onServerReady();

        void onPermissionResult(int requestCode, String permission, boolean allowed);
    }

    /** 把客户端实现包成 Binder，交给服务端。 */
    public static IBinder asBinder(final Stub impl) {
        return new android.os.Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                YlProtocol.checkDescriptor(data, YlProtocol.DESCRIPTOR_APPLICATION);
                switch (code) {
                    case YlProtocol.TX_APP_SERVER_READY:
                        impl.onServerReady();
                        reply.writeNoException();
                        return true;
                    case YlProtocol.TX_APP_PERM_RESULT: {
                        int requestCode = data.readInt();
                        String permission = data.readString();
                        boolean allowed = data.readInt() != 0;
                        impl.onPermissionResult(requestCode, permission, allowed);
                        reply.writeNoException();
                        return true;
                    }
                    default:
                        return false;
                }
            }
        };
    }

    /** 便捷方法：从 Bundle 里取出回调 Binder 并包装（服务端侧用）。 */
    public static YlApplication fromBundle(Bundle bundle) {
        if (bundle == null) return null;
        bundle.setClassLoader(YlApplication.class.getClassLoader());
        IBinder b = bundle.getBinder("callback");
        return b == null ? null : new YlApplication(b);
    }
}
