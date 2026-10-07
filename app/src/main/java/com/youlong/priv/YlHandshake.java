package com.youlong.priv;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * 自研特权内核 · 握手信道（客户端 → 进程内，服务端 → 客户端）。
 *
 * <p>服务端跑在 shell 身份、不往 ServiceManager 里注册名字（普通应用也没这个权限），
 * 所以需要一条"把 Binder 送回来"的通道。做法：
 * <ol>
 *   <li>客户端 {@link YlBootProvider#call} 时把自己实现的这个 Binder 作为参数传出；
 *   <li>Provider 在**应用进程内**拉起服务端进程，并把同一个 Binder 实例交给它；
 *   <li>服务端用 {@link #deliver} 把 {@code IYlService} 的 Binder 写回来 ——
 *       同一个进程，拿到即用，不经过任何系统注册表。</li>
 * </ol>
 */
public final class YlHandshake {

    /** 事务码：服务端 → 客户端，交付主服务 Binder。 */
    public static final int TX_DELIVER = 1;

    public static final String DESCRIPTOR = "com.youlong.priv.IYlHandshake";

    /** Bundle 里的 key（Provider 参数）。 */
    public static final String KEY_HANDSHAKE = "handshake";

    private YlHandshake() {}

    /** 客户端实现：收到服务端 Binder。 */
    public interface Stub {
        void onServiceBinder(IBinder service);
    }

    /** 把客户端实现包成 Binder（作为 Provider 参数传给服务端）。 */
    public static IBinder asBinder(final Stub impl) {
        return new android.os.Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                YlProtocol.checkDescriptor(data, DESCRIPTOR);
                if (code == TX_DELIVER) {
                    IBinder service = data.readStrongBinder();
                    impl.onServiceBinder(service);
                    reply.writeNoException();
                    return true;
                }
                return false;
            }
        };
    }

    /** 服务端实现：把主服务 Binder 交付给客户端。 */
    public static void deliver(IBinder handshake, IBinder service) {
        if (handshake == null || service == null) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            YlProtocol.writeDescriptor(data, DESCRIPTOR);
            data.writeStrongBinder(service);
            handshake.transact(TX_DELIVER, data, reply, 0);
            reply.readException();
        } catch (Throwable ignored) {
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /** 从 Bundle 里取出手握 Binder（Provider 侧用）。 */
    public static IBinder fromBundle(android.os.Bundle bundle) {
        if (bundle == null) return null;
        bundle.setClassLoader(YlHandshake.class.getClassLoader());
        return bundle.getBinder(KEY_HANDSHAKE);
    }
}
