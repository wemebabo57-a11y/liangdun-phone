package com.youlong.hd;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * AdSkipService v5
 *
 * 诊断 v4 不工作的根因：
 * 1. mEnabled 永远为 false（toggle 关着时 add app，prefs 写 enabled=false）
 * 2. findSkipInTree 先 recycle 候选再点击，obtain() 拷贝随源节点一起失效
 * 3. findAccessibilityNodeInfosByText 不搜 contentDescription
 *
 * v5：点击前不 recycle，直接持节点引用；自动启用；dump 日志
 */
public class AdSkipService extends AccessibilityService {

    private static final String TAG = "AdSkip";
    private static final String PREFS_NAME = "adskip_config";
    private static final long COOLDOWN_MS = 2000;

    private static volatile boolean sRunning = false;
    private static AdSkipService sInstance;

    /** 前台应用变化回调接口 */
    public interface ForegroundChangeListener {
        void onForegroundChanged(String pkg);
    }
    private static ForegroundChangeListener sFgListener = null;

    /** 注册前台变化监听 */
    public static void setForegroundChangeListener(ForegroundChangeListener l) {
        sFgListener = l;
    }

    /** ===== 音量键回调接口（无障碍服务 onVolumeChanged / onKeyEvent 触发）===== */
    public interface VolumeChangeListener {
        /**
         * @param volumeType 变化的音频流编号（-1 表示来自物理按键层）
         * @param direction  按键方向：-1 = 音量减，+1 = 音量加，0 = 无法判断（由上层结合音量变化推断）
         */
        void onVolumeChanged(int volumeType, int direction);
    }
    private static VolumeChangeListener sVolListener = null;

    /** 注册音量变化监听 */
    public static void setVolumeChangeListener(VolumeChangeListener l) {
        sVolListener = l;
    }

    /**
     * 音量监听是否还挂着。
     *
     * <p>2026-10 改动（修 bug「紧急逃生只能触发一次」）：灵敏度触发靠的是静态回调
     * {@link #sVolListener}，而 {@code ProtectService.onDestroy()} 会把它清成 null，
     * 一旦哪个路径（服务被系统回收后重建、异常退出、其它代码误清）清掉它却没重新注册，
     * 音量键逃生就会"静默失效"——按键有反应、界面全无、日志里什么都看不到，
     * 用户只能重开 App 或重开守护才能恢复。这里提供探针 + 自愈入口。
     */
    public static boolean isVolumeListenerRegistered() {
        return sVolListener != null;
    }

    /**
     * 自愈：音量监听被清掉且无障碍服务仍在运行时，重新挂上。
     *
     * @return true = 本次调用把监听重新挂上了（说明此前确实处于失效状态）
     */
    public static boolean ensureVolumeListener(VolumeChangeListener l) {
        if (l == null) return false;
        if (sVolListener != null) return false;
        sVolListener = l;
        Log.w(TAG, "音量监听缺失，已自愈重新注册（音量键逃生恢复可用）");
        return true;
    }

    /** 获取当前前台应用包名 */
    public static String getForegroundPkg() {
        if (sInstance != null) return sInstance.mCurrentPkg;
        return null;
    }

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mScanPending = false;
    private long mLastScanTime = 0;
    private static final long MIN_SCAN_INTERVAL = 1000;
    private Set<String> mMonitorPkgs = new HashSet<>();
    private Set<String> mBlacklistPkgs = new HashSet<>();
    private Set<String> mCustomKeywords = new HashSet<>();
    private boolean mEnabled = false;
    private String mCurrentPkg = "";
    private int mTotalSkipped = 0;
    private long mLastClickTime = 0;
    private String mLastClickPkg = "";

    private int mScreenW = 1080;
    private int mScreenH = 2400;

    private static final String[] SKIP_TEXTS = {
            "跳过广告", "点击跳过", "跳过",
            "Skip Ad", "Skip ad", "Skip",
    };

    /** 广告特征词：页面出现任一即判定为广告页 */
    private static final String[] AD_INDICATOR_TEXTS = {
            "点击跳转", "摇一摇", "广告",
    };

    // ==================== 生命周期 ====================

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm != null) {
            Point size = new Point();
            Display display = wm.getDefaultDisplay();
            if (display != null) {
                display.getRealSize(size);
                mScreenW = size.x;
                mScreenH = size.y;
            }
        }
        applyPendingConfig();
        loadConfig();
        Log.d(TAG, "onCreate screen=" + mScreenW + "x" + mScreenH
                + " enabled=" + mEnabled + " monitors=" + mMonitorPkgs);
    }

    @Override
    public void onDestroy() {
        sRunning = false;
        sInstance = null;
        super.onDestroy();
    }
    @Override public void onInterrupt() {}

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        sRunning = true;
        // 启用按键过滤（必须！否则 onKeyEvent 不会收到事件）
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
                setServiceInfo(info);
                Log.d(TAG, "serviceConnected + FLAG_REQUEST_FILTER_KEY_EVENTS");
            }
        } catch (Exception e) {
            Log.e(TAG, "设置FLAG_REQUEST_FILTER_KEY_EVENTS失败", e);
        }
    }

    /**
     * 音量键变化回调（API 26+）
     * 只要有音量变化（无论铃声还是媒体）立即通知 ProtectService。
     * 系统只告知"哪个音频流变了"，不告知是加还是减 → direction 传 0，
     * 由 ProtectService 对比前后音量数值推断方向。
     * minSdk=24 无法用 @Override，但系统在 API 26+ 会通过虚方法分派正确调用此方法
     */
    public void onVolumeChanged(int volumeType) {
        Log.v(TAG, "onVolumeChanged type=" + volumeType);
        if (sVolListener != null) {
            sVolListener.onVolumeChanged(volumeType, 0);
        }
    }

    /**
     * 物理按键拦截（API 18+）— 音量最低/最高时的救命通道
     *
     * 当音量已是 0（或已是最大）时，系统不发送 onVolumeChanged / VOLUME_CHANGED_ACTION，
     * 但物理按键事件始终会传递到这里。这一层是唯一能**准确分辨音量+ / 音量-**的地方，
     * 所以必须把方向一并上报，供用户自定义"按音量- 还是音量+"触发。
     * 不消费事件，让系统正常处理音量。
     */
    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            final int code = event.getKeyCode();
            int dir = 0;
            if (code == KeyEvent.KEYCODE_VOLUME_DOWN) dir = -1;
            else if (code == KeyEvent.KEYCODE_VOLUME_UP) dir = 1;
            if (dir != 0) {
                Log.v(TAG, "onKeyEvent 音量" + (dir > 0 ? "+" : "-") + " 物理按键(不消费)");
                // 通过音量回调通道通知 ProtectService（-1 = 物理按键层）
                if (sVolListener != null) {
                    sVolListener.onVolumeChanged(-1, dir);
                }
            }
            // 不消费事件，让系统正常处理音量
        }
        return super.onKeyEvent(event);
    }

    /**
     * 兼容 API 24/25：通过 AccessibilityEvent.TYPE_TOUCH_INTERACTION_END 无法监听音量，
     * 这部分设备由 ProtectService 的 120ms 轮询 + VOLUME_CHANGED_ACTION 广播兜底
     */

    // ==================== 事件 ====================

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!mEnabled) {
            mHandler.removeCallbacksAndMessages(null);
            return;
        }
        if (mMonitorPkgs.isEmpty()) return;

        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null) return;
        String pkg = pkgCs.toString();
        int type = event.getEventType();

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.d(TAG, "WINDOW_STATE_CHANGED pkg=" + pkg + " current=" + mCurrentPkg);
            mCurrentPkg = pkg;
            // 通知前台应用变化
            if (sFgListener != null) {
                sFgListener.onForegroundChanged(pkg);
            }
            if (shouldMonitor(pkg)) {
                Log.d(TAG, "monitor " + pkg + " → scan at 1500/4000ms");
                scheduleScan(1500);
                scheduleScan(4000);
            }
        }
        // 注意：不响应 TYPE_WINDOW_CONTENT_CHANGED——它一秒触发几十次，极度耗电卡顿
    }

    private boolean shouldMonitor(String pkg) {
        return pkg != null && mMonitorPkgs.contains(pkg) && !mBlacklistPkgs.contains(pkg);
    }

    // ==================== 扫描 ====================

    private void scheduleScan(long delayMs) {
        mScanPending = true;
        mHandler.postDelayed(() -> {
            mScanPending = false;
            doScan();
        }, delayMs);
    }

    private void doScan() {
        // 限速：至少间隔 MIN_SCAN_INTERVAL
        long now = System.currentTimeMillis();
        if (now - mLastScanTime < MIN_SCAN_INTERVAL) return;
        mLastScanTime = now;

        // 关键守卫：只扫描监控列表中的应用
        if (!mEnabled || mMonitorPkgs.isEmpty()) return;
        if (!shouldMonitor(mCurrentPkg)) return;
        if (mBlacklistPkgs.contains(mCurrentPkg)) return;

        if (mLastClickTime > 0 && mCurrentPkg != null && mCurrentPkg.equals(mLastClickPkg)
                && (now - mLastClickTime) < COOLDOWN_MS) {
            return;
        }

        // 存储所有待回收的节点
        List<AccessibilityNodeInfo> toRecycle = new ArrayList<>();

        try {
            AccessibilityNodeInfo target = findSkipButton(toRecycle);
            if (target == null) {
                Log.d(TAG, "doScan: no target");
                return;
            }

            Log.d(TAG, "TARGET: cls=" + target.getClassName()
                    + " txt='" + target.getText() + "'"
                    + " desc='" + target.getContentDescription() + "'"
                    + " clk=" + target.isClickable());

            Rect b = new Rect();
            target.getBoundsInScreen(b);
            Log.d(TAG, "TARGET bounds=" + b.toShortString());

            // 尝试 performAction
            boolean ok = false;
            if (target.isClickable()) {
                ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Log.d(TAG, "performAction direct: " + ok);
            }
            if (!ok) {
                ok = clickParent(target);
                Log.d(TAG, "clickParent: " + ok);
            }
            if (!ok) {
                ok = gestureClick(b.centerX(), b.centerY());
                Log.d(TAG, "gestureClick: " + ok);
            }

            // 最终兜底：有覆盖层 + 全失败 → 点右上角（X按钮典型位置）
            if (!ok && hasOverlayWindow()) {
                int cx = (int)(mScreenW * 0.95);
                int cy = (int)(mScreenH * 0.05);
                ok = gestureClick(cx, cy);
                Log.d(TAG, "cornerTap(" + cx + "," + cy + "): " + ok);
                if (!ok) {
                    // 偏左一点再试一次
                    cx = (int)(mScreenW * 0.88);
                    ok = gestureClick(cx, cy);
                    Log.d(TAG, "cornerTap2(" + cx + "," + cy + "): " + ok);
                }
            }

            if (ok) {
                mLastClickTime = now;
                mLastClickPkg = mCurrentPkg;
                mTotalSkipped++;
                saveStats();
                Log.d(TAG, "=== CLICK OK #" + mTotalSkipped + " in " + mCurrentPkg + " ===");
            } else {
                Log.d(TAG, "ALL CLICK METHODS FAILED");
            }
        } catch (Exception e) {
            Log.w(TAG, "doScan err", e);
        } finally {
            // 统一回收所有节点
            for (AccessibilityNodeInfo n : toRecycle) {
                try { n.recycle(); } catch (Exception ignored) {}
            }
        }
    }

    // ==================== 查找跳过按钮（延迟 recycle） ====================

    private AccessibilityNodeInfo findSkipButton(List<AccessibilityNodeInfo> toRecycle) {
        List<AccessibilityWindowInfo> windows = getWindows();

        if (windows != null && !windows.isEmpty()) {
            for (AccessibilityWindowInfo win : windows) {
                if (win == null) continue;
                AccessibilityNodeInfo root = win.getRoot();
                if (root == null) { win.recycle(); continue; }
                toRecycle.add(root);

                String wpkg = root.getPackageName() != null ? root.getPackageName().toString() : "";
                int winType = win.getType();
                Log.d(TAG, "win type=" + winType + " pkg=" + wpkg);

                // 修复：不按包名过滤——广告 SDK 窗口包名和应用不同
                // 所有窗口统一暴力搜索
                AccessibilityNodeInfo r = scanWindow(root, toRecycle);
                win.recycle();
                if (r != null) return r;
            }
        } else {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                toRecycle.add(root);
                Log.d(TAG, "fallback root: " + root.getPackageName());
                return scanWindow(root, toRecycle);
            }
        }
        return null;
    }

    /** 统一窗口扫描：广告特征词检测 → 文本搜 → 递归搜 → X按钮探测 */
    private AccessibilityNodeInfo scanWindow(AccessibilityNodeInfo root,
                                             List<AccessibilityNodeInfo> toRecycle) {
        // 0）新规则：检测"点击跳转"/"摇一摇"/"广告" → 有则找"跳过"/X
        AccessibilityNodeInfo adSkipResult = adKeywordTriggeredScan(root, toRecycle);
        if (adSkipResult != null) return adSkipResult;

        // 1）系统 API 文本搜索——所有关键词
        String[] searchWords = {"跳过广告", "点击跳过", "跳过",
                               "关闭广告", "关闭",
                               "Skip Ad", "Skip ad", "Skip",
                               "close", "dismiss",
                               "忽略", "不再提示", "忽略提醒"};
        for (String w : searchWords) {
            List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText(w);
            if (hits == null) continue;
            toRecycle.addAll(hits);
            for (AccessibilityNodeInfo n : hits) {
                if (n == null || !n.isVisibleToUser()) continue;
                AccessibilityNodeInfo clk = findClickableUp(n, toRecycle);
                if (clk != null && isGoodSizeForOverlay(clk)) return clk;
            }
        }

        // 2）递归全树搜（关键词、倒计时、viewId、自定义关键词）
        AccessibilityNodeInfo found = searchTree(root, toRecycle);
        if (found != null) return found;

        // 3）X 按钮探测
        AccessibilityNodeInfo xBtn = findXButton(root, toRecycle);
        if (xBtn != null) return xBtn;

        return null;
    }

    /** 目标是否在屏幕下半部（广告跳过按钮典型区域） */
    private boolean isBottomArea(AccessibilityNodeInfo n) {
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        return b.top >= mScreenH * 0.45;
    }

    // ==================== 新规则：广告特征词触发跳过/X ====================

    /**
     * 两步检测：先扫描整棵树是否有"点击跳转"/"摇一摇"/"广告"，
     * 有则再搜索"跳过"文字或 X 按钮，找到返回可点击节点。
     */
    private AccessibilityNodeInfo adKeywordTriggeredScan(AccessibilityNodeInfo root,
                                                        List<AccessibilityNodeInfo> toRecycle) {
        // Step 1: 遍历全树检测广告特征词
        if (!hasAdIndicators(root, toRecycle)) return null;

        Log.d(TAG, "adKeywordTriggeredScan: ad indicator detected, hunting skip/X");

        // Step 2: 搜索"跳过"文字
        AccessibilityNodeInfo skip = findTextInTree(root, "跳过", toRecycle);
        if (skip != null) {
            AccessibilityNodeInfo clk = skip.isClickable() ? skip
                    : findClickableUp(skip, toRecycle);
            if (clk != null && isGoodSizeForOverlay(clk)) {
                Log.d(TAG, "adKeywordTriggeredScan: found '跳过' → click");
                return clk;
            }
        }

        // Step 3: 搜索 X 按钮
        AccessibilityNodeInfo xBtn = findXButton(root, toRecycle);
        if (xBtn != null) {
            Log.d(TAG, "adKeywordTriggeredScan: found X button → click");
            return xBtn;
        }

        Log.d(TAG, "adKeywordTriggeredScan: ad detected but no skip/X found, skip");
        return null;
    }

    /** 遍历整棵树，检测是否包含任意广告特征词 */
    private boolean hasAdIndicators(AccessibilityNodeInfo node,
                                    List<AccessibilityNodeInfo> toRecycle) {
        if (node == null) return false;

        String text = nodeText(node);
        for (String kw : AD_INDICATOR_TEXTS) {
            if (text.contains(kw)) {
                Log.d(TAG, "hasAdIndicators: found '" + kw + "' in text='" + text + "'");
                return true;
            }
        }

        // contentDescription 也检查
        CharSequence desc = node.getContentDescription();
        if (desc != null) {
            String d = desc.toString();
            for (String kw : AD_INDICATOR_TEXTS) {
                if (d.contains(kw)) {
                    Log.d(TAG, "hasAdIndicators: found '" + kw + "' in desc='" + d + "'");
                    return true;
                }
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            toRecycle.add(child);
            if (hasAdIndicators(child, toRecycle)) return true;
        }
        return false;
    }

    /** 在树中搜索包含指定文字的可见节点，返回找到的第一个 */
    private AccessibilityNodeInfo findTextInTree(AccessibilityNodeInfo node, String keyword,
                                                  List<AccessibilityNodeInfo> toRecycle) {
        if (node == null) return null;

        if (node.isVisibleToUser() && isGoodSizeForOverlay(node)) {
            String text = nodeText(node);
            if (text.contains(keyword)) {
                Log.d(TAG, "findTextInTree: found '" + keyword + "' in '" + text + "'");
                AccessibilityNodeInfo clk = node.isClickable() ? node
                        : findClickableUp(node, toRecycle);
                return (clk != null) ? clk : node;
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            toRecycle.add(child);
            AccessibilityNodeInfo found = findTextInTree(child, keyword, toRecycle);
            if (found != null) return found;
        }
        return null;
    }

    /** 检测是否有非活动窗口（广告弹窗通常在此） */
    private boolean hasOverlayWindow() {
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null) return false;
        for (AccessibilityWindowInfo w : windows) {
            if (w == null) continue;
            boolean active = w.isActive();
            w.recycle();
            if (!active) return true;
        }
        return false;
    }

    /** 覆盖层专用尺寸校验——比普通 isGoodSize 更宽松，允许小尺寸按钮 */
    private boolean isGoodSizeForOverlay(AccessibilityNodeInfo n) {
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        int w = b.width(), h = b.height();
        if (w <= 2 || h <= 2) return false;          // 2px 以下无视
        if (w > mScreenW * 0.95 && h > mScreenH * 0.85) return false; // 全屏巨物跳过
        return true;
    }

    /** X 按钮探测：扫描覆盖层树中角落小尺寸点击节点（无文本的 ImageView 等） */
    private AccessibilityNodeInfo findXButton(AccessibilityNodeInfo root,
                                              List<AccessibilityNodeInfo> toRecycle) {
        return scanForX(root, toRecycle);
    }

    private AccessibilityNodeInfo scanForX(AccessibilityNodeInfo node,
                                           List<AccessibilityNodeInfo> toRecycle) {
        if (node == null) return null;

        // 候选：可见 + 可点（或可点父节点）+ 小尺寸 + 在窗口顶部区域
        if (node.isVisibleToUser() && node.isClickable()) {
            Rect b = new Rect();
            node.getBoundsInScreen(b);
            int w = b.width(), h = b.height();
            // X 按钮典型：10~80px 正方形，位于屏幕顶部区域
            boolean isSmall = (w >= 10 && w <= 90 && h >= 10 && h <= 90);
            boolean isTopArea = (b.top < mScreenH * 0.15);
            // 无文本（纯图标的 X）
            boolean noText = (node.getText() == null || node.getText().toString().trim().isEmpty());
            if (isSmall && isTopArea && noText) {
                Log.d(TAG, "scanForX: small clickable in top area bounds=" + b.toShortString());
                return node;
            }
            // 放宽：右半侧 + 顶部（X 通常在右上角）
            if (isSmall && isTopArea && b.left > mScreenW * 0.5) {
                Log.d(TAG, "scanForX: right-top small bounds=" + b.toShortString());
                return node;
            }
        }

        // 即使不可点，子节点可能有 X
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            toRecycle.add(child);
            AccessibilityNodeInfo found = scanForX(child, toRecycle);
            if (found != null) {
                AccessibilityNodeInfo clk = node.isClickable() ? node
                        : findClickableUp(node, toRecycle);
                return (clk != null) ? clk : found;
            }
        }
        return null;
    }

    // ==================== 递归搜索 ====================

    /** 覆盖层专用的搜索关键词（比全局 SKIP_TEXTS 更广泛） */
    private static final String[] OVERLAY_KEYWORDS = {
            "关闭", "close", "dismiss", "取消", "忽略",
            "x", "×", "不再提示", "忽略提醒",
    };

    private AccessibilityNodeInfo searchTree(AccessibilityNodeInfo node,
                                              List<AccessibilityNodeInfo> toRecycle) {
        if (node == null) return null;

        if (node.isVisibleToUser() && isGoodSizeForOverlay(node)) {
            String text = nodeText(node);
            // 全局跳过词
            for (String kw : SKIP_TEXTS) {
                if (text.contains(kw)) {
                    Log.d(TAG, "searchTree found by text: '" + text + "'");
                    AccessibilityNodeInfo clk = node.isClickable() ? node
                            : findClickableUp(node, toRecycle);
                    return (clk != null) ? clk : node;
                }
            }
            // 覆盖层专用词（关闭/close/dismiss/X等）
            for (String kw : OVERLAY_KEYWORDS) {
                if (text.contains(kw)) {
                    Log.d(TAG, "searchTree found by overlay kw: '" + text + "'");
                    AccessibilityNodeInfo clk = node.isClickable() ? node
                            : findClickableUp(node, toRecycle);
                    return (clk != null) ? clk : node;
                }
            }
            // 倒计时: "跳过 3s", "3秒后关闭"
            if (text.matches(".*跳过\\s*\\d+\\s*[s秒]?.*") || text.matches(".*\\d+\\s*[s秒]\\s*(后|跳过|关闭).*")) {
                Log.d(TAG, "searchTree found by countdown: '" + text + "'");
                AccessibilityNodeInfo clk = node.isClickable() ? node
                        : findClickableUp(node, toRecycle);
                return (clk != null) ? clk : node;
            }
            // viewId 含 skip/close/dismiss
            String vid = viewId(node);
            if (vid != null && (vid.contains("skip") || vid.contains("close")
                    || vid.contains("dismiss"))) {
                Log.d(TAG, "searchTree found by id: " + vid);
                AccessibilityNodeInfo clk = node.isClickable() ? node
                        : findClickableUp(node, toRecycle);
                return (clk != null) ? clk : node;
            }
            // 自定义关键词匹配
            for (String kw : mCustomKeywords) {
                if (!kw.isEmpty() && text.contains(kw.toLowerCase())) {
                    Log.d(TAG, "searchTree found by custom keyword: '" + kw + "'");
                    AccessibilityNodeInfo clk = node.isClickable() ? node
                            : findClickableUp(node, toRecycle);
                    return (clk != null) ? clk : node;
                }
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            toRecycle.add(child);
            AccessibilityNodeInfo found = searchTree(child, toRecycle);
            if (found != null) return found;
        }
        return null;
    }

    // ==================== 上溯找可点击父节点 ====================

    /** 从 node 往上找可点击父节点，沿途节点加入 toRecycle */
    private AccessibilityNodeInfo findClickableUp(AccessibilityNodeInfo node,
                                                   List<AccessibilityNodeInfo> toRecycle) {
        if (node == null) return null;
        if (node.isClickable()) return node;

        AccessibilityNodeInfo cur = node.getParent();
        int depth = 0;
        while (cur != null && depth < 6) {
            toRecycle.add(cur);
            if (cur.isClickable()) return cur;
            AccessibilityNodeInfo p = cur.getParent();
            cur = p;
            depth++;
        }
        return null;
    }

    // ==================== 点击 ====================

    private boolean clickParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node.getParent();
        int depth = 0;
        try {
            while (cur != null && depth < 6) {
                if (cur.isClickable()) {
                    return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
                cur = cur.getParent();
                depth++;
            }
        } catch (Exception e) {
            Log.w(TAG, "clickParent err", e);
        }
        return false;
    }

    private boolean gestureClick(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 50));

        final boolean[] result = {false};
        final Object lock = new Object();

        boolean dispatched = dispatchGesture(builder.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gd) {
                synchronized (lock) { result[0] = true; lock.notify(); }
            }
            @Override
            public void onCancelled(GestureDescription gd) {
                synchronized (lock) { result[0] = false; lock.notify(); }
            }
        }, null);

        if (!dispatched) return false;
        synchronized (lock) {
            try { lock.wait(500); } catch (InterruptedException ignored) {}
        }
        return result[0];
    }

    // ==================== 调试 dump ====================

    private long mLastDumpTime = 0;

    private void dumpTreeSample(AccessibilityNodeInfo root) {
        long now = System.currentTimeMillis();
        if (now - mLastDumpTime < 10000) return;
        mLastDumpTime = now;

        StringBuilder sb = new StringBuilder();
        sb.append("=== TREE DUMP ===\n");
        dumpNode(root, 0, sb, 80);
        Log.d(TAG, sb.toString());
    }

    private void dumpNode(AccessibilityNodeInfo node, int depth, StringBuilder sb, int maxLines) {
        if (node == null || sb.length() > maxLines * 200) return;
        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < depth; i++) indent.append("  ");

        String cls = node.getClassName() != null ? node.getClassName().toString() : "?";
        String txt = node.getText() != null ? node.getText().toString() : "";
        String desc = node.getContentDescription() != null
                ? node.getContentDescription().toString() : "";
        String vid = node.getViewIdResourceName() != null
                ? node.getViewIdResourceName().toString() : "";
        Rect b = new Rect();
        node.getBoundsInScreen(b);
        boolean clk = node.isClickable();
        boolean vis = node.isVisibleToUser();

        sb.append(indent).append(cls);
        if (!txt.isEmpty()) sb.append(" text='").append(txt).append("'");
        if (!desc.isEmpty()) sb.append(" desc='").append(desc).append("'");
        if (!vid.isEmpty()) sb.append(" id='").append(vid).append("'");
        sb.append(" ").append(b.toShortString());
        if (clk) sb.append(" CLICKABLE");
        if (!vis) sb.append(" INVISIBLE");
        sb.append("\n");

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                dumpNode(child, depth + 1, sb, maxLines);
                child.recycle();
            }
        }
    }

    // ==================== 工具 ====================

    private boolean isGoodSize(AccessibilityNodeInfo n) {
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        int w = b.width(), h = b.height();
        if (w <= 5 || h <= 5) return false;
        if (w > mScreenW * 0.9 && h > mScreenH * 0.7) return false;
        return true;
    }

    private String nodeText(AccessibilityNodeInfo n) {
        if (n == null) return "";
        StringBuilder sb = new StringBuilder();
        CharSequence t = n.getText();
        CharSequence d = n.getContentDescription();
        if (t != null) sb.append(t);
        if (d != null) sb.append(" ").append(d);
        return sb.toString().toLowerCase().trim();
    }

    private String viewId(AccessibilityNodeInfo n) {
        if (n == null || n.getViewIdResourceName() == null) return null;
        return n.getViewIdResourceName().toString().toLowerCase();
    }

    // ==================== 统计 ====================

    private void saveStats() {
        SharedPreferences p = getSharedPreferences("adskip_stats", Context.MODE_PRIVATE);
        String td = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(new java.util.Date());
        String prev = p.getString("date", "");
        int today = td.equals(prev) ? p.getInt("today", 0) + 1 : 1;
        int total = p.getInt("total", 0) + 1;
        p.edit().putInt("today", today).putInt("total", total).putString("date", td).apply();
    }

    // ==================== 配置 ====================

    private void loadConfig() {
        SharedPreferences p = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean storedEnabled = p.getBoolean("enabled", false);
        mMonitorPkgs = parsePkg(p.getString("monitor_apps", "[]"));
        mBlacklistPkgs = parsePkg(p.getString("blacklist_apps", "[]"));
        mCustomKeywords = parseKeywords(p.getString("custom_keywords", "[]"));
        // 完全尊重用户开关——用户关就是关
        mEnabled = storedEnabled;
        Log.d(TAG, "loadConfig storedEnabled=" + storedEnabled
                + " monitors=" + mMonitorPkgs + " keywords=" + mCustomKeywords
                + " → mEnabled=" + mEnabled);
    }

    private Set<String> parsePkg(String json) {
        Set<String> r = new HashSet<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (o.has("pkg")) r.add(o.getString("pkg"));
            }
        } catch (JSONException ignored) {}
        return r;
    }

    private Set<String> parseKeywords(String json) {
        Set<String> r = new HashSet<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                String kw = arr.getString(i).trim().toLowerCase();
                if (!kw.isEmpty()) r.add(kw);
            }
        } catch (JSONException ignored) {}
        return r;
    }

    public static void updateConfig(String configJson) {
        Log.d(TAG, "updateConfig: " + configJson);
        try {
            JSONObject cfg = new JSONObject(configJson);
            boolean enabled = cfg.optBoolean("enabled", false);
            String monitor = cfg.optJSONArray("monitorApps") != null
                    ? cfg.getJSONArray("monitorApps").toString() : "[]";
            String blacklist = cfg.optJSONArray("blacklistApps") != null
                    ? cfg.getJSONArray("blacklistApps").toString() : "[]";
            String keywords = cfg.optJSONArray("customKeywords") != null
                    ? cfg.getJSONArray("customKeywords").toString() : "[]";

            if (sInstance != null) {
                SharedPreferences p = sInstance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                p.edit().putBoolean("enabled", enabled)
                        .putString("monitor_apps", monitor)
                        .putString("blacklist_apps", blacklist)
                        .putString("custom_keywords", keywords)
                        .apply();
                sInstance.loadConfig();
            } else {
                sPendingEnabled = enabled;
                sPendingMonitorJson = monitor;
                sPendingBlacklistJson = blacklist;
                sPendingKeywordsJson = keywords;
                Log.d(TAG, "config → pending (instance==null)");
            }
        } catch (JSONException e) {
            Log.w(TAG, "updateConfig err", e);
        }
    }

    private static String sPendingMonitorJson;
    private static String sPendingBlacklistJson;
    private static String sPendingKeywordsJson;
    private static boolean sPendingEnabled;

    private void applyPendingConfig() {
        if (sPendingMonitorJson != null || sPendingBlacklistJson != null
                || sPendingKeywordsJson != null) {
            SharedPreferences p = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            p.edit().putBoolean("enabled", sPendingEnabled)
                    .putString("monitor_apps",
                            sPendingMonitorJson != null ? sPendingMonitorJson : "[]")
                    .putString("blacklist_apps",
                            sPendingBlacklistJson != null ? sPendingBlacklistJson : "[]")
                    .putString("custom_keywords",
                            sPendingKeywordsJson != null ? sPendingKeywordsJson : "[]")
                    .apply();
            sPendingMonitorJson = null;
            sPendingBlacklistJson = null;
            sPendingKeywordsJson = null;
            loadConfig();
        }
    }

    public static boolean isRunning() { return sRunning; }

    public static String getStats() {
        if (sInstance == null) return "{\"today\":0,\"total\":0}";
        SharedPreferences p = sInstance.getSharedPreferences("adskip_stats", Context.MODE_PRIVATE);
        String td = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(new java.util.Date());
        String prev = p.getString("date", "");
        int today = td.equals(prev) ? p.getInt("today", 0) : 0;
        int total = p.getInt("total", 0);
        return "{\"today\":" + today + ",\"total\":" + total + "}";
    }
}
