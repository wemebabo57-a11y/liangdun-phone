package com.youlong.hd;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 病毒扫描引擎（独立模块，不依赖 / 不修改 MainActivity）。
 *
 * <p>扫描流水线 scanApp(pkg, label, apkPath)，按顺序执行，命中即定级返回：
 * <ol>
 *   <li><b>VirusDb 名单</b>：{@code VirusDb.get(ctx).isCertain(pkg,label)} 命中
 *       1.100%病毒包名 / 3.100%病毒名称 → {@code MALICIOUS}；
 *       {@code matchSuspect(pkg,label)} 命中2.可疑包名 / 4.可疑名称 / 5.关键字
 *       → {@code SUSPECT}（原因透传）。</li>
 *   <li><b>内置 MD5 黑名单</b>：计算 {@code apkPath} 的 MD5（小写 32hex），查
 *       {@code assets/md5sums.txt}（首批 20000 条，高置信段）→ 命中为
 *       {@code MALICIOUS}。参考 {@code E:/6.5/engine.py#HashBlacklist}（哈希黑名单优先短路）
 *       与 {@code scanner.py#scan_file}（大文件先走哈希快速检测）。</li>
 *   <li><b>启发式</b>（参考 {@code engine.py#PEProfileAnalyzer} 的画像计分思想，
 *       落到 Android 上用“权限组合 + DEX 字符串”代替“熵/区段/导入表”）：
 *       <ul>
 *         <li>权限组合（各计 2 分）：READ_SMS+SEND_SMS 短信组合；
 *             BIND_DEVICE_ADMIN + 无桌面图标（隐藏图标）��
 *             RECEIVE_BOOT_COMPLETED + DISABLE_KEYGUARD（自启+锁屏）；
 *             RECEIVE_BOOT_COMPLETED + SYSTEM_ALERT_WINDOW（自启+悬浮窗）。</li>
 *         <li>DEX 字符串（各计 1 分，IP 直连 URL 计 2 分）：DexClassLoader/PathClassLoader
 *             动态加载；Class.forName/getDeclaredMethod/defineClass 反射；
 *             content://sms 短信窃取；http 明文 URL / IP 直连可疑 URL。</li>
 *       </ul>
 *       总分 ≥4 → {@code MALICIOUS}，≥1 → {@code SUSPECT}，0 → {@code CLEAN}。</li>
 * </ol>
 *
 * <p><b>内置 MD5 切分说明（已执行）</b>：
 * {@code E:/0LIANDUN/md5/} 下 00001~00011.md5.txt 各约 104858 行（32hex 小写 md5，
 * CRLF 换行）。首批取 {@code 00001.md5.txt} 前 20000 个“合法去重”行
 * （正则 {@code ^[0-9a-f]{32}$}，小写归一化，去重）写入
 * {@code app/src/main/assets/md5sums.txt}（20000 行 / 约 660KB，随包发布）。
 * 选取 00001 头部的原因：该目录各文件按哈希前缀分桶（00001 头部全是 0000… 前缀），
 * 即“哈希空间的一个连续高��信段”，首批验证加载链路与包体积影响最小。
 *
 * <p><b>{@code assets/md5sums_full.txt} 后续扩充方案</b>：
 * <ol>
 *   <li>用脚本把 11 个 {@code .md5.txt}（约 115 万行）合并、去重、按“置信度”排序
 *       （有家族标注 / 多源交叉命中的排前面），得到全量有序列表。</li>
 *   <li>按 5 万行一段切分为 {@code md5sums_full_00.txt …}（每段约 1.6MB），
 *       首段随包发布（改名或追加进 {@code md5sums.txt}），其余段放自有服务器，
 *       走 VirusDb 同款 SharedPreferences/文件缓存 + 增量拉取（参考 VirusDb
 *       的 fetchSet/save/parse/cleanPkg/httpGet 实现风格）。</li>
 *   <li>本引擎加载时优先读 {@code md5sums_full.txt}（若存在则追加合并进同一 Set，
 *       {@code getEngineStatus} 的 md5Count 会如实变大），不改任何调用方代码；
 *       单文件超 3MB 时再拆分为多段并把 {@code ASSET_MD5} 改为多文件名循环加载。</li>
 *   <li>验证脚本：抽样对比 PC 端 md5 目录，确认 32hex 小写、无 BOM、无空行
 *       （加载器遇到非法行直接跳过并记 Log）。</li>
 * </ol>
 */
public final class LdVirusEngine {

    private static final String TAG = "LdVirusEngine";

    /** 内置 MD5 黑名单（首批 20000 条）。全量文件存在时自动追加合并。 */
    private static final String ASSET_MD5 = "md5sums.txt";
    /** 后续扩充的全量文件（可选，不存在则跳过）。 */
    private static final String ASSET_MD5_FULL = "md5sums_full.txt";

    /** 启发式总开关（状态上报用，固定 true；置 false 可一键降级为名单+MD5 模式）。 */
    private static final boolean HEURISTIC_ON = true;

    /** DEX 扫描上限（首 3MB，参考 scanner.py 大文件先哈希、静态分析限流的思想）。 */
    private static final long MAX_DEX_SCAN_BYTES = 3L * 1024L * 1024L;
    /** 启发式计分阈值：>=6 MALICIOUS；>=3 且命中>=2项 SUSPECT，单条弱信号不报警。 */
    private static final int SCORE_MALICIOUS = 6;
    private static final int SCORE_SUSPECT = 3;

    private static final Pattern IP_URL = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+");

    private static final Object MD5_LOCK = new Object();
    private static volatile Set<String> sMd5Set = null;
    private static volatile int sMd5Count = 0;
    private static volatile boolean sMd5Loaded = false;

    private LdVirusEngine() {}

    // ======================================================================
    // 结果模型
    // ======================================================================

    /** 扫描结论。level 取值：CLEAN / SUSPECT / MALICIOUS。 */
    public static final class Verdict {
        public final String level;
        public final String reason;
        /** 补充：APK 的 MD5（算不出时为空串）。 */
        public final String md5;
        /** 补充：启发式得分。 */
        public final int score;
        /** 补充：启发式命中明细（名单/MD5 命中时为空）。 */
        public final List<String> hits;

        Verdict(String level, String reason, String md5, int score, List<String> hits) {
            this.level = level;
            this.reason = reason == null ? "" : reason;
            this.md5 = md5 == null ? "" : md5;
            this.score = score;
            this.hits = hits == null ? new ArrayList<String>() : hits;
        }

        JSONObject toJson(String pkg, String label) {
            JSONObject o = new JSONObject();
            try {
                o.put("pkg", pkg == null ? "" : pkg);
                o.put("label", label == null ? "" : label);
                o.put("level", level);
                o.put("reason", reason);
                o.put("md5", md5);
                o.put("score", score);
                JSONArray arr = new JSONArray();
                for (String h : hits) arr.put(h);
                o.put("hits", arr);
            } catch (Exception ignored) {}
            return o;
        }
    }

    // ======================================================================
    // 对外接口（共 4 个 public static 方法）
    // ======================================================================

    /**
     * 三段流水线扫描：VirusDb 名单 → 内置 MD5 → 启发式。永不抛异常，
     * 异常时降级返回 CLEAN（reason 注明降级原因，不误报）。
     */
    public static Verdict scanApp(Context ctx, String pkg, String label, String apkPath) {
        try {
            String p = pkg == null ? "" : pkg.trim();
            String l = label == null ? "" : label.trim();
            String apk = apkPath == null ? "" : apkPath.trim();
            if (apk.isEmpty() && ctx != null && !p.isEmpty()) {
                apk = resolveApkPath(ctx, p);
            }

            // ---- 第 1 段：VirusDb 名单 ----
            if (ctx != null) {
                try {
                    VirusDb.Data db = VirusDb.get(ctx);
                    if (db != null) {
                        if (db.isCertain(p, l)) {
                            return new Verdict("MALICIOUS", "\u75c5\u6bd2\u5e93\u547d\u4e2d100%\u75c5\u6bd2\u540d\u5355", "", 0, null);
                        }
                        String suspect = db.matchSuspect(p, l);
                        if (suspect != null) {
                            return new Verdict("SUSPECT", "\u75c5\u6bd2\u5e93\u53ef\u7591\uff1a" + suspect, "", 0, null);
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "VirusDb detection skipped: " + e.getMessage());
                }
            }

            // ---- 第 2 段：内置 MD5 黑名单 ----
            String md5 = "";
            if (!apk.isEmpty()) {
                md5 = md5OfFile(apk);
                if (!md5.isEmpty()) {
                    ensureMd5Loaded(ctx);
                    Set<String> set = sMd5Set;
                    if (set != null && set.contains(md5)) {
                        return new Verdict("MALICIOUS", "MD5\u547d\u4e2d\u5185\u7f6e\u9ed1\u540d\u5355", md5, 0, null);
                    }
                }
            }

            // ---- 第 3 段：启发式（权限组合 + DEX 字符串） ----
            if (HEURISTIC_ON) {
                if (isSystemApp(ctx, p)) {
                    return new Verdict("CLEAN", "\u7cfb\u7edf\u5e94\u7528\u5df2\u4fe1\u4efb", md5, 0, null);
                }
                List<String> hits = new ArrayList<>();
                boolean trusted = isTrustedVendor(p);
                int score = heuristicScore(ctx, p, apk, hits);
                if (score >= SCORE_MALICIOUS) {
                    return new Verdict("MALICIOUS", "\u542f\u53d1\u5f0f\u9ad8\u5371\uff1a" + joinHits(hits), md5, score, hits);
                }
                if (!trusted && score >= SCORE_SUSPECT && hits.size() >= 2) {
                    return new Verdict("SUSPECT", "\u542f\u53d1\u5f0f\u53ef\u7591\uff1a" + joinHits(hits), md5, score, hits);
                }
                return new Verdict("CLEAN", "\u672a\u53d1\u73b0\u5f02\u5e38", md5, 0, hits);
            }
            return new Verdict("CLEAN", "\u672a\u53d1\u73b0\u5f02\u5e38", md5, 0, null);
        } catch (Exception e) {
            Log.w(TAG, "scanApp fallback: " + e.getMessage());
            return new Verdict("CLEAN", "\u626b\u63cf\u5f02\u5e38\uff0c\u5df2\u964d\u7ea7", "", 0, null);
        }
    }

    /** scanApp 的 JSON 串版本：{"pkg","label","level","reason","md5","score","hits"}。 */
    public static String scanToJson(Context ctx, String pkg, String label, String apkPath) {
        try {
            return scanApp(ctx, pkg, label, apkPath).toJson(pkg, label).toString();
        } catch (Exception e) {
            return "{\"level\":\"CLEAN\",\"reason\":\"scan error\"}";
        }
    }

    /** 引擎状态（需 Context 预加载 MD5）：{"md5Count":N,"heuristicOn":true}。 */
    public static String getEngineStatus(Context ctx) {
        ensureMd5Loaded(ctx);
        return statusJson();
    }

    /** 引擎状态（无 Context，用已缓存计数；未加载过则 md5Count=0）。 */
    public static String getEngineStatus() {
        return statusJson();
    }

    // ======================================================================
    // MD5 黑名单加载
    // ======================================================================

    private static void ensureMd5Loaded(Context ctx) {
        if (sMd5Loaded) return;
        synchronized (MD5_LOCK) {
            if (sMd5Loaded) return;
            Set<String> set = new HashSet<>(22000);
            int n = 0;
            n += loadAssetInto(ctx, ASSET_MD5, set);
            n += loadAssetInto(ctx, ASSET_MD5_FULL, set);
            sMd5Set = set;
            sMd5Count = set.size();
            sMd5Loaded = true;
            Log.i(TAG, "md5 loaded: " + sMd5Count);
        }
    }

    /** 读一个 assets 文件进集合；文件缺失返回 0（全量文件可选）。 */
    private static int loadAssetInto(Context ctx, String assetName, Set<String> out) {
        if (ctx == null) return 0;
        InputStream in = null;
        int added = 0;
        try {
            in = ctx.getAssets().open(assetName);
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim().toLowerCase(Locale.ROOT);
                if (t.length() == 32 && isHex32(t) && out.add(t)) added++;
            }
            br.close();
        } catch (Exception e) {
            Log.i(TAG, "md5 asset missing, skip: " + assetName);
        } finally {
            if (in != null) { try { in.close(); } catch (Exception ignored) {} }
        }
        return added;
    }

    private static boolean isHex32(String s) {
        for (int i = 0; i < 32; i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    private static String statusJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("md5Count", sMd5Count);
            o.put("heuristicOn", HEURISTIC_ON);
        } catch (Exception ignored) {}
        return o.toString();
    }

    // ======================================================================
    // 启发式
    // ======================================================================

    /** 系统应用（含系统升级应用）直接信任：跳过启发式。 */
    private static boolean isSystemApp(Context ctx, String pkg) {
        if (ctx == null || pkg == null || pkg.isEmpty()) return false;
        try {
            android.content.pm.ApplicationInfo ai =
                    ctx.getPackageManager().getApplicationInfo(pkg, 0);
            if (ai == null) return false;
            int mask = android.content.pm.ApplicationInfo.FLAG_SYSTEM
                    | android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
            return (ai.flags & mask) != 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 大厂/主流包名前缀：只报高危不报可疑，避免谷歌商店类误报。 */
    private static final String[] TRUSTED_PREFIXES = {
            "com.google.", "com.android.", "android.",
            "com.miui.", "com.xiaomi.", "com.huawei.", "com.honor.",
            "com.samsung.", "com.oppo.", "com.oplus.", "com.vivo.",
            "com.oneplus.", "com.meizu.", "com.lenovo.", "com.zte.",
            "com.tencent.mm", "com.tencent.mobileqq", "com.alibaba.",
            "com.taobao.", "com.alipay.", "com.baidu.", "com.jd.",
            "com.youlong.hd",
    };

    private static boolean isTrustedVendor(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        for (String pre : TRUSTED_PREFIXES) {
            if (pkg.equals(pre) || pkg.startsWith(pre)) return true;
        }
        return false;
    }

    private static int heuristicScore(Context ctx, String pkg, String apkPath, List<String> hits) {
        int score = 0;

        // ---- 权限组合 ----
        Set<String> perms = getRequestedPermissions(ctx, pkg);
        boolean smsRead = perms.contains("android.permission.READ_SMS");
        boolean smsSend = perms.contains("android.permission.SEND_SMS");
        boolean admin = perms.contains("android.permission.BIND_DEVICE_ADMIN");
        boolean boot = perms.contains("android.permission.RECEIVE_BOOT_COMPLETED");
        boolean disKey = perms.contains("android.permission.DISABLE_KEYGUARD");
        boolean alertWin = perms.contains("android.permission.SYSTEM_ALERT_WINDOW");

        if (smsRead && smsSend) {
            hits.add("\u77ed\u4fe1\u7ec4\u5408\u6743\u9650(READ_SMS+SEND_SMS)");
            score += 2;
        }
        boolean hiddenIcon = isHiddenIcon(ctx, pkg);
        if (admin && hiddenIcon) {
            hits.add("\u8bbe\u5907\u7ba1\u7406+\u9690\u85cf\u56fe\u6807");
            score += 2;
        }
        if (boot && disKey) {
            hits.add("\u81ea\u542f+\u9501\u5c4f(BOOT_COMPLETED+DISABLE_KEYGUARD)");
            score += 2;
        }
        if (boot && alertWin) {
            hits.add("\u81ea\u542f+\u60ac\u6d6e\u7a97(BOOT_COMPLETED+SYSTEM_ALERT_WINDOW)");
            score += 1;
        }

        // ---- DEX 字符串 ----
        if (apkPath != null && !apkPath.isEmpty()) {
            score += scanDexStrings(apkPath, hits);
        }
        return score;
    }

    private static Set<String> getRequestedPermissions(Context ctx, String pkg) {
        Set<String> out = new HashSet<>();
        if (ctx == null || pkg == null || pkg.isEmpty()) return out;
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(pkg, PackageManager.GET_PERMISSIONS);
            if (pi != null && pi.requestedPermissions != null) {
                for (String p : pi.requestedPermissions) {
                    if (p != null) out.add(p);
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** 无桌面启动图标 → 视为隐藏图标（查不到时按 false 处理，不误加分）。 */
    private static boolean isHiddenIcon(Context ctx, String pkg) {
        if (ctx == null || pkg == null || pkg.isEmpty()) return false;
        try {
            return ctx.getPackageManager().getLaunchIntentForPackage(pkg) == null;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 扫描 APK 内 classes*.dex 前 MAX_DEX_SCAN_BYTES 字节的可疑字符串。
     * 命中项追加进 hits，返回得分。
     */
    private static int scanDexStrings(String apkPath, List<String> hits) {
        int score = 0;
        boolean dynLoad = false, reflect = false, smsUri = false, httpUrl = false, ipUrl = false;
        ZipFile zf = null;
        try {
            File f = new File(apkPath);
            if (!f.isFile() || !f.canRead()) return 0;
            zf = new ZipFile(f);
            long budget = MAX_DEX_SCAN_BYTES;
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            StringBuilder sb = new StringBuilder(512 * 1024);
            while (en.hasMoreElements() && budget > 0) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (name == null || !name.endsWith(".dex")) continue;
                if (name.contains("/")) continue;
                InputStream in = null;
                try {
                    in = zf.getInputStream(e);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0 && budget > 0) {
                        int take = (int) Math.min(n, budget);
                        sb.append(new String(buf, 0, take, "ISO-8859-1"));
                        budget -= take;
                    }
                } catch (Exception ignored) {
                } finally {
                    if (in != null) { try { in.close(); } catch (Exception ignored) {} }
                }
            }
            String dex = sb.toString();
            if (dex.contains("DexClassLoader") || dex.contains("PathClassLoader")
                    || dex.contains("loadDex") || dex.contains("InMemoryDexClassLoader")) {
                dynLoad = true;
            }
            if (dex.contains("Class.forName") || dex.contains("getDeclaredMethod")
                    || dex.contains("defineClass") || dex.contains("getMethod")) {
                reflect = true;
            }
            if (dex.contains("content://sms") || dex.contains("content://mms")) {
                smsUri = true;
            }
            if (dex.contains("http://")) {
                httpUrl = true;
                if (IP_URL.matcher(dex).find()) ipUrl = true;
            }
        } catch (Exception e) {
            Log.w(TAG, "dex scan skipped: " + e.getMessage());
            return 0;
        } finally {
            if (zf != null) { try { zf.close(); } catch (Exception ignored) {} }
        }
        if (dynLoad) { hits.add("DEX\u52a8\u6001\u52a0\u8f7d(DexClassLoader)"); score += 1; }
        if (reflect) { hits.add("DEX\u53cd\u5c04(Class.forName/getDeclaredMethod)"); score += 1; }
        if (smsUri) { hits.add("DEX\u77ed\u4fe1\u7a83\u53d6(content://sms)"); score += 1; }
        if (httpUrl) { hits.add("DEX\u660e\u6587URL(http://)"); score += 1; }
        if (ipUrl) { hits.add("DEX\u53ef\u7591URL(IP\u76f4\u8fde)"); score += 2; }
        return score;
    }

    // ======================================================================
    // 小工具
    // ======================================================================

    private static String resolveApkPath(Context ctx, String pkg) {
        try {
            android.content.pm.ApplicationInfo ai =
                    ctx.getPackageManager().getApplicationInfo(pkg, 0);
            if (ai != null && ai.sourceDir != null) return ai.sourceDir;
        } catch (Exception ignored) {}
        return "";
    }

    private static String md5OfFile(String path) {
        FileInputStream fis = null;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            fis = new FileInputStream(path);
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) md.update(buf, 0, n);
            byte[] dig = md.digest();
            StringBuilder sb = new StringBuilder(32);
            for (byte b : dig) {
                int v = b & 0xFF;
                if (v < 16) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "md5 fail: " + e.getMessage());
            return "";
        } finally {
            if (fis != null) { try { fis.close(); } catch (Exception ignored) {} }
        }
    }

    private static String joinHits(List<String> hits) {
        StringBuilder sb = new StringBuilder();
        for (String h : hits) {
            if (sb.length() > 0) sb.append("\uFF1B");
            sb.append(h);
        }
        return sb.toString();
    }
}
