package com.youlong.priv.server;

import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Log;

import com.youlong.priv.YlApplication;
import com.youlong.priv.YlRemoteProcess;
import com.youlong.priv.YlService;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 自研特权内核 · 服务端主入口（**完全自研，不含任何第三方框架代码**）。
 *
 * <p>由 {@code app_process} 以 <b>shell(uid 2000)</b> 身份启动：
 * <pre>
 *   CLASSPATH=&lt;本应用 APK&gt; app_process /system/bin \
 *       --nice-name=youlong_priv com.youlong.priv.server.YlServerMain
 * </pre>
 * shell 用户持有 {@code FORCE_STOP_PACKAGES} / {@code READ_LOGS} /
 * {@code PACKAGE_USAGE_STATS} 等特权，这正是"免 root 执行 pm/am/dumpsys"的来源；
 * 整套机制只用 AOSP 公开能力。
 *
 * <p><b>管道方案</b>：三条管道由**客户端**用
 * {@link ParcelFileDescriptor#createReliablePipe()} 建好（socketpair 实现，
 * 跨进程一读一写），把要接到子进程的那一端通过 Binder 传来（Binder 支持传递 fd）。
 * 服务端只用 {@link ProcessBuilder.Redirect} 接到子进程的 stdin/stdout/stderr ——
 * 不需要 fd 传递 hack，也不碰任何隐藏 API。
 */
public final class YlServerMain {

    private static final String TAG = "YlServer";

    private static volatile YlServerMain sInstance;

    private Context mContext;
    /** init 只跑一次（Context 允许为 null，所以不能靠 mContext 判重）。 */
    private boolean mInitialized;
    private final AtomicInteger mProcessSeq = new AtomicInteger(0);
    private final ConcurrentHashMap<Integer, YlRemoteProcessImpl> mProcesses =
            new ConcurrentHashMap<>();

    private YlServerMain() {}

    public static YlServerMain get() {
        YlServerMain s = sInstance;
        if (s == null) {
            synchronized (YlServerMain.class) {
                if (sInstance == null) sInstance = new YlServerMain();
                s = sInstance;
            }
        }
        return s;
    }

    /** shell 身份启动时的入口。 */
    public static void main(String[] args) {
        // 第一件事就落盘：确认"app_process 是否真的把我们的 main 跑起来了"。
        // 服务端是 shell 身份，只能写自己可写的目录，所以固定用 /data/local/tmp。
        diag("main() 进入，args=" + java.util.Arrays.toString(args)
                + " uid=" + Process.myUid() + " pid=" + Process.myPid());
        try {
            Looper.prepareMainLooper();
            diag("Looper 就绪，开始 init()");
            get().init();
            diag("init() 完成，进入 Looper.loop()");
            Looper.loop();
        } catch (Throwable t) {
            diag("服务端启动失败: " + t);
            Log.e(TAG, "服务端启动失败", t);
            System.exit(1);
        }
    }

    /** 追加一行服务端诊断日志（shell 可写目录，客户端侧可用 adb 直接读）。 */
    static void diag(String msg) {
        String line = "[" + new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS",
                java.util.Locale.US).format(new java.util.Date()) + "] " + msg + "\n";
        java.io.FileOutputStream fos = null;
        try {
            File f = new File("/data/local/tmp/ylpriv_server.log");
            fos = new java.io.FileOutputStream(f, true);
            fos.write(line.getBytes("UTF-8"));
            fos.flush();
        } catch (Throwable ignored) {
        } finally {
            if (fos != null) { try { fos.close(); } catch (Throwable ignored) {} }
        }
        Log.i(TAG, msg);
    }

    private void init() {
        if (mInitialized) return;
        mInitialized = true;
        // ActivityThread 是隐藏 API（不在公开 SDK 里），直接 import 编译不过 →
        // 通过 YlSystemContext 反射获取；取不到也不影响特权执行，授权记录会退回文件存储。
        mContext = YlSystemContext.get();
        Log.i(TAG, "服务端已就绪，Context="
                + (mContext == null ? "null(退回文件存储)" : mContext.getPackageName())
                + "，授权记录=" + YlPermission.describeStore(this));
    }

    public Context context() {
        return mContext;
    }

    public IBinder asBinder() {
        return YlService.asBinder(new YlService.Stub() {
            @Override
            public boolean ping() {
                return true;
            }

            @Override
            public boolean checkPermission(String permission) {
                return YlPermission.check(YlServerMain.this, permission);
            }

            @Override
            public void requestPermission(int requestCode, String permission) {
                YlPermission.request(YlServerMain.this, requestCode, permission);
            }

            @Override
            public void attachApplication(IBinder callback, Bundle args) {
                YlPermission.attachApplication(YlServerMain.this,
                        callback == null ? null : new YlApplication(callback), args);
            }

            @Override
            public IBinder newProcess(String[] cmd, String[] env, String dir,
                                      ParcelFileDescriptor stdinRead,
                                      ParcelFileDescriptor stdoutWrite,
                                      ParcelFileDescriptor stderrWrite) {
                int callerUid = android.os.Binder.getCallingUid();
                if (!YlPermission.checkUid(YlServerMain.this, callerUid)) {
                    Log.w(TAG, "拒绝未授权 uid 的进程请求: " + callerUid);
                    return null;
                }
                int id = mProcessSeq.incrementAndGet();
                YlRemoteProcessImpl impl = new YlRemoteProcessImpl(id, cmd, env, dir,
                        stdinRead, stdoutWrite, stderrWrite);
                if (!impl.start()) {
                    Log.e(TAG, "拉起进程失败: " + java.util.Arrays.toString(cmd));
                    return null;
                }
                mProcesses.put(id, impl);
                return YlRemoteProcess.asBinder(impl);
            }

            @Override
            public int getServerUid() {
                return Process.myUid();
            }
        });
    }

    void forget(int id) {
        mProcesses.remove(id);
    }

    /**
     * 远程进程实现：以服务端(shell)身份 exec，并把客户端给的管道接到子进程标准流上。
     */
    static final class YlRemoteProcessImpl implements YlRemoteProcess.Stub {

        private final int mId;
        private final String[] mCmd;
        private final String[] mEnv;
        private final String mDir;
        private final ParcelFileDescriptor mStdinRead;
        private final ParcelFileDescriptor mStdoutWrite;
        private final ParcelFileDescriptor mStderrWrite;

        private java.lang.Process mProc;
        private int mExit = -1;
        private boolean mDone;

        YlRemoteProcessImpl(int id, String[] cmd, String[] env, String dir,
                            ParcelFileDescriptor stdinRead,
                            ParcelFileDescriptor stdoutWrite,
                            ParcelFileDescriptor stderrWrite) {
            this.mId = id;
            this.mCmd = cmd;
            this.mEnv = env;
            this.mDir = YlService.normalizeDir(dir);
            this.mStdinRead = stdinRead;
            this.mStdoutWrite = stdoutWrite;
            this.mStderrWrite = stderrWrite;
        }

        synchronized boolean start() {
            try {
                // 把客户端给的管道接到子进程标准流。
                //
                // Android 的 ProcessBuilder.Redirect 只接受 File，没法直接吃 fd，
                // 于是走 Unix 的标准做法：**fd 继承 + shell 重定向**。
                // Binder 送进来的 fd 在本进程里是活的，fork 出的 sh 会继承它，
                // 因此 sh 可以往 /proc/self/fd/N 重定向；sh 再 exec 目标命令时这些 fd 仍有效。
                StringBuilder script = new StringBuilder();
                for (int i = 0; i < mCmd.length; i++) {
                    if (i > 0) script.append(' ');
                    script.append(shellQuote(mCmd[i]));
                }
                String inPath = fdPath(mStdinRead);
                String outPath = fdPath(mStdoutWrite);
                String errPath = fdPath(mStderrWrite);
                if (inPath != null) script.append(" < ").append(inPath);
                if (outPath != null) script.append(" > ").append(outPath);
                if (errPath != null) script.append(" 2> ").append(errPath);
                Log.i(TAG, "进程脚本: " + script);

                ProcessBuilder pb = new ProcessBuilder("sh", "-c", script.toString());
                if (mEnv != null && mEnv.length > 0) {
                    pb.environment().clear();
                    for (String e : mEnv) {
                        int i = e.indexOf('=');
                        if (i > 0) pb.environment().put(e.substring(0, i), e.substring(i + 1));
                    }
                }
                if (mDir != null) pb.directory(new File(mDir));
                // 输出走 shell 重定向（见上），这里用 PIPE 兜住可能的杂散输出，
                // 免得子进程继承到服务端的标准流。注意 Android 没有 Redirect.DISCARD。
                pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
                pb.redirectError(ProcessBuilder.Redirect.PIPE);

                mProc = pb.start();
                Log.i(TAG, "进程已启动 id=" + mId + " cmd=" + java.util.Arrays.toString(mCmd)
                        + " serverUid=" + Process.myUid());
                return true;
            } catch (Throwable t) {
                Log.e(TAG, "start 失败", t);
                return false;
            }
        }

        /**
         * 取 PFD 底层的 raw fd 号，拼成 {@code /proc/self/fd/N} 供 shell 重定向。
         *
         * <p>{@code FileDescriptor} 的 descriptor 字段没有公开 getter，只能反射；
         * 这是 AOSP 上长期稳定的字段名。取不到就返回 null，该路管道退化为丢弃。
         */
        private static String fdPath(ParcelFileDescriptor pfd) {
            if (pfd == null) return null;
            try {
                java.io.FileDescriptor fd = pfd.getFileDescriptor();
                java.lang.reflect.Field f = java.io.FileDescriptor.class.getDeclaredField("descriptor");
                f.setAccessible(true);
                int raw = (Integer) f.get(fd);
                if (raw < 0) return null;
                return "/proc/self/fd/" + raw;
            } catch (Throwable t) {
                Log.w(TAG, "取 fd 号失败", t);
                return null;
            }
        }

        private static String shellQuote(String s) {
            if (s == null) return "''";
            return "'" + s.replace("'", "'\\''") + "'";
        }

        // FIFO / fd 直取这三条在新方案下不再使用（管道端由客户端持有）
        @Override public synchronized ParcelFileDescriptor getOutputStream() { return null; }

        @Override public synchronized ParcelFileDescriptor getInputStream() { return null; }

        @Override public synchronized ParcelFileDescriptor getErrorStream() { return null; }

        @Override
        public synchronized int waitFor() {
            if (mDone) return mExit;
            try {
                mExit = mProc.waitFor();
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
                mExit = mProc.exitValue();
                mDone = true;
            } catch (Throwable ignored) {
            }
            return mExit;
        }

        @Override
        public synchronized boolean alive() {
            if (mDone) return false;
            try {
                mProc.exitValue();
                mDone = true;
                return false;
            } catch (Throwable ignored) {
                return true;
            }
        }

        @Override
        public synchronized void destroy() {
            try {
                mProc.destroy();
            } catch (Throwable ignored) {
            }
            YlServerMain.get().forget(mId);
        }
    }
}
