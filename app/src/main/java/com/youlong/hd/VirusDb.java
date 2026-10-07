package com.youlong.hd;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 病毒库（远程 5 个列表 + 本地缓存）—【开源版：不含任何病毒库数据与地址】
 *
 * <p>正式发布版的 5 个远程列表（① 100% 病毒包名 ② 可能病毒包名
 * ③ 100% 病毒名称 ④ 可能病毒名称 ⑤ 可能病毒关键字）由作者自己的服务器提供，
 * 域名与列表路径以 XOR 字节常量存放在 {@link StrX} 中，运行期才还原。
 *
 * <p><b>按作者要求，本开源包不包含病毒库</b>：
 * <ul>
 *   <li>{@code StrX} 里的病毒库域名与路径常量已整体删除；</li>
 *   <li>本类不再发起任何网络请求，{@link #refreshAsync}/{@link #refreshNow}
 *       为空实现（只记一条日志），{@link #get} 返回空库
 *       —— 也就是"自动拦截 / 病毒库检测"这条功能在开源版里是**哑的**，
 *       不会误报、也不会误删用户的应用；</li>
 *   <li>本地缓存（SharedPreferences: virus_db_prefs）的读取逻辑保留，
 *       方便接入自有数据源后直接复用。</li>
 * </ul>
 *
 * <p>接入自有病毒库的方法：把 {@link #SERVER_HOST_D} 与 5 个 URL 常量
 * 换成你自己的地址，并恢复 {@link #fetchOne} 的调用即可（见 TODO 注释）。
 */
public final class VirusDb {

    private static final String TAG = "VirusDb";
    private static final String PREF = "virus_db_prefs";

    // ========================================================================
    // 【开源版已移除】病毒库服务器地址与 5 个列表路径
    // ------------------------------------------------------------------------
    // 原版：
    //   private static final String SERVER_HOST_D = stripScheme(StrX.d(StrX.VDB_HOST));
    //   private static final String URL_CERTAIN_PKG = SERVER_BASE + StrX.d(StrX.VDB_PATH_CERTAIN_PKG);
    //   ... 其余 4 个列表同理
    // 开源版统一置空，并在 refresh 系列方法里直接返回，不发起任何请求。
    // ========================================================================
    private static final String SERVER_HOST_D = "";
    private static final String SERVER_BASE = "";

    /** 防御：无论字节常量里是否误带协议头，都保证最终 URL 只有一个 https:// */
    private static String stripScheme(String h) {
        if (h == null) return "";
        String s = h.trim();
        if (s.regionMatches(true, 0, "https://", 0, 8)) return s.substring(8);
        if (s.regionMatches(true, 0, "http://", 0, 7)) return s.substring(7);
        return s;
    }

    /** 开源版：以下 5 个地址均为空串（原版指向作者自建的病毒库列表） */
    private static final String URL_CERTAIN_PKG = "";
    private static final String URL_SUSPECT_PKG = "";
    private static final String URL_CERTAIN_NAME = "";
    private static final String URL_SUSPECT_NAME = "";
    private static final String URL_KEYS = "";

    /** 服务器域名（病毒库页面展示用）。开源版为空 → 页面显示"未配置"。 */
    public static String getServerHost() { return SERVER_HOST_D; }

    private static final String K_CERTAIN_PKG = "certain_pkgs";
    private static final String K_CERTAIN_NAME = "certain_names";
    private static final String K_SUSPECT_PKG = "suspect_pkgs";
    private static final String K_SUSPECT_NAME = "suspect_names";
    private static final String K_KEYS = "suspect_keys";
    private static final String K_TIME = "fetch_time";

    /** 两次拉取之间的最小间隔（防止频繁请求服务器） */
    private static final long MIN_FETCH_INTERVAL_MS = 30000L;

    /** 内存缓存（volatile：后台线程整体替换，读取时天然拿到一致快照） */
    private static volatile Data cache = Data.EMPTY;
    private static volatile boolean loadedFromDisk = false;
    private static volatile boolean fetching = false;
    private static volatile long lastFetchTime = 0L;

    // ======================================================================
    // 服务器连接状态（供「病毒库」页面展示实时连接情况）
    // ======================================================================
    public static final class ListStatus {
        public final String key;
        public final String label;
        public final String url;
        /** 最近一次拉取是否成功 */
        public volatile boolean ok = false;
        /** 成功拿到多少条 */
        public volatile int count = 0;
        /** 最近一次成功时间（毫秒） */
        public volatile long updatedAt = 0L;

        ListStatus(String key, String label, String url) {
            this.key = key;
            this.label = label;
            this.url = url;
        }
    }

    private static final ListStatus LS_CERTAIN_PKG = new ListStatus("certainPkg", "100% 病毒包名", URL_CERTAIN_PKG);
    private static final ListStatus LS_SUSPECT_PKG = new ListStatus("suspectPkg", "可能病毒包名", URL_SUSPECT_PKG);
    private static final ListStatus LS_CERTAIN_NAME = new ListStatus("certainName", "100% 病毒名称", URL_CERTAIN_NAME);
    private static final ListStatus LS_SUSPECT_NAME = new ListStatus("suspectName", "可能病毒名称", URL_SUSPECT_NAME);
    private static final ListStatus LS_KEYS = new ListStatus("keys", "可能病毒关键字", URL_KEYS);
    private static final ListStatus[] ALL_LISTS = {
            LS_CERTAIN_PKG, LS_SUSPECT_PKG, LS_CERTAIN_NAME, LS_SUSPECT_NAME, LS_KEYS};

    /** 服务器是否可达（最近一轮至少有一个列表拉取成功） */
    private static volatile boolean serverReachable = false;
    /** 最近一次发起拉取的时间 */
    private static volatile long lastAttempt = 0L;
    /** 最近一次拉取成功的时间 */
    private static volatile long lastSuccess = 0L;
    /** 最近一次失败原因（成功则为空串） */
    private static volatile String lastError = "";
    /** 最近一次 HTTP 失败的细节（状态码 / 异常名），用于拼进 lastError 便于排查 */
    private static volatile String lastHttpDetail = "";
    /** 是否正在拉取中 */
    private static volatile boolean refreshing = false;

    public static java.util.List<ListStatus> getListStatus() {
        return java.util.Arrays.asList(ALL_LISTS);
    }

    public static boolean isServerReachable() { return serverReachable; }

    public static long getLastAttempt() { return lastAttempt; }

    public static long getLastSuccess() { return lastSuccess; }

    public static String getLastError() { return lastError; }

    public static boolean isRefreshing() { return refreshing; }

    /** 仅允许本地缓存的“上次同步时间”给页面用 */
    public static long getCachedFetchTime() { return lastFetchTime; }

    private VirusDb() {}

    // ======================================================================
    // 数据模型
    // ======================================================================
    public static final class Data {
        public static final Data EMPTY = new Data(new HashSet<String>(), new HashSet<String>(),
                new HashSet<String>(), new HashSet<String>(), new HashSet<String>());

        /** ① 100% 病毒包名（精确匹配） */
        public final Set<String> certainPkgs;
        /** ③ 100% 病毒应用名称（精确匹配，忽略大小写） */
        public final Set<String> certainNames;
        /** ② 可能病毒包名（精确匹配） */
        public final Set<String> suspectPkgs;
        /** ④ 可能病毒应用名称（精确匹配，忽略大小写） */
        public final Set<String> suspectNames;
        /** ⑤ 可能病毒关键字（包含匹配，忽略大小写） */
        public final Set<String> keys;

        // 大小写无关的辅助集合（构建后只读）
        private final Set<String> certainNamesLower = new HashSet<>();
        private final Set<String> suspectNamesLower = new HashSet<>();
        private final java.util.List<String> keysLower = new java.util.ArrayList<>();

        Data(Set<String> certainPkgs, Set<String> certainNames, Set<String> suspectPkgs,
             Set<String> suspectNames, Set<String> keys) {
            this.certainPkgs = certainPkgs;
            this.certainNames = certainNames;
            this.suspectPkgs = suspectPkgs;
            this.suspectNames = suspectNames;
            this.keys = keys;
            for (String s : certainNames) certainNamesLower.add(s.toLowerCase(Locale.ROOT));
            for (String s : suspectNames) suspectNamesLower.add(s.toLowerCase(Locale.ROOT));
            for (String s : keys) {
                String t = s.trim().toLowerCase(Locale.ROOT);
                if (!t.isEmpty()) keysLower.add(t);
            }
        }

        /** 库为空（尚未拉到任何数据）→ 无需检测 */
        public boolean isEmpty() {
            return certainPkgs.isEmpty() && certainNames.isEmpty()
                    && suspectPkgs.isEmpty() && suspectNames.isEmpty() && keys.isEmpty();
        }

        /** 是否命中 100% 病毒库（①包名 或 ③应用名称） */
        public boolean isCertain(String pkg, String label) {
            if (pkg != null && !pkg.isEmpty() && certainPkgs.contains(pkg)) return true;
            String l = label == null ? "" : label.trim();
            if (l.isEmpty()) return false;
            return certainNames.contains(l) || certainNamesLower.contains(l.toLowerCase(Locale.ROOT));
        }

        /** 是否命中可能病毒库（②包名 / ④名称 / ⑤关键字），命中返回原因，未命中返回 null */
        public String matchSuspect(String pkg, String label) {
            String l = label == null ? "" : label.trim();
            String ll = l.toLowerCase(Locale.ROOT);
            if (pkg != null && !pkg.isEmpty()) {
                if (suspectPkgs.contains(pkg)) return "包名命中可疑库";
                String pl = pkg.toLowerCase(Locale.ROOT);
                for (int i = 0; i < keysLower.size(); i++) {
                    if (pl.contains(keysLower.get(i))) return "包名含关键字";
                }
            }
            if (!l.isEmpty()) {
                if (suspectNames.contains(l) || suspectNamesLower.contains(ll)) return "应用名称命中可疑库";
                for (int i = 0; i < keysLower.size(); i++) {
                    if (ll.contains(keysLower.get(i))) return "名称含关键字";
                }
            }
            return null;
        }

        public int total() {
            return certainPkgs.size() + certainNames.size()
                    + suspectPkgs.size() + suspectNames.size() + keys.size();
        }
    }

    // ======================================================================
    // 对外接口
    // ======================================================================

    /** 取当前病毒库（首次调用自动从本地缓存加载） */
    public static Data get(Context ctx) {
        if (!loadedFromDisk) loadFromCache(ctx);
        return cache;
    }

    /** 从本地缓存加载（断网也能继续检测） */
    public static void loadFromCache(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            Set<String> cp = splitToSet(sp.getString(K_CERTAIN_PKG, ""));
            Set<String> cn = splitToSet(sp.getString(K_CERTAIN_NAME, ""));
            Set<String> spk = splitToSet(sp.getString(K_SUSPECT_PKG, ""));
            Set<String> sn = splitToSet(sp.getString(K_SUSPECT_NAME, ""));
            Set<String> ks = splitToSet(sp.getString(K_KEYS, ""));
            cache = new Data(cp, cn, spk, sn, ks);
            lastFetchTime = sp.getLong(K_TIME, 0L);
            loadedFromDisk = true;
            Log.i(TAG, "病毒库本地缓存已加载: " + cache.total() + " 条");
        } catch (Exception e) {
            Log.e(TAG, "读取病毒库缓存失败", e);
            loadedFromDisk = true;
        }
    }

    /**
     * 后台异步拉取服务器最新数据（带节流，重复调用会被忽略）
     *
     * <p>【开源版】不含病毒库地址 → 直接返回，不发起任何网络请求。
     */
    public static void refreshAsync(final Context ctx) {
        // 开源版：没有内置病毒库地址，什么都不做（保留方法签名，调用方无需改动）
        Log.i(TAG, "开源版未内置病毒库：跳过拉取（详见 VirusDb 类注释）");
    }

    /**
     * 立即拉取（阻塞，必须在后台线程调用）
     *
     * <p>【开源版】不含病毒库地址 → 直接返回，不发起任何网络请求。
     *
     * <p>TODO（接入自有病毒库）：
     * 1. 把上面的 SERVER_HOST_D 与 5 个 URL_* 常量换成你自己的地址；
     * 2. 恢复下面被注释掉的拉取主体（fetchSet / save / 统计日志）；
     * 3. 其余逻辑（本地缓存、匹配、页面展示）都是现成的，无需改动。
     */
    public static void refreshNow(Context ctx) {
        // 开源版：不拉取、不写缓存、不改动 cache
        Log.i(TAG, "开源版未内置病毒库：refreshNow 为空实现");
    }

    /** 【开源版保留但不再调用】原版的拉取主体，接入自有数据源后可恢复 */
    @SuppressWarnings("unused")
    private static void refreshNowLegacy(Context ctx) {
        loadFromCache(ctx);
        final Data old = cache;

        refreshing = true;
        lastAttempt = System.currentTimeMillis();
        lastError = "";
        try {
            // 逐个拉取：某个文件 404/超时 → 保留旧数据（不清空）
            Set<String> cPkg = fetchSet(LS_CERTAIN_PKG, true);
            Set<String> sPkg = fetchSet(LS_SUSPECT_PKG, true);
            Set<String> cName = fetchSet(LS_CERTAIN_NAME, false);
            Set<String> sName = fetchSet(LS_SUSPECT_NAME, false);
            Set<String> keys = fetchSet(LS_KEYS, false);

            Data nd = new Data(
                    cPkg != null ? cPkg : old.certainPkgs,
                    cName != null ? cName : old.certainNames,
                    sPkg != null ? sPkg : old.suspectPkgs,
                    sName != null ? sName : old.suspectNames,
                    keys != null ? keys : old.keys);

            cache = nd;
            lastFetchTime = System.currentTimeMillis();

            int okCount = 0;
            for (ListStatus ls : ALL_LISTS) if (ls.ok) okCount++;
            if (okCount > 0) {
                serverReachable = true;
                lastSuccess = lastFetchTime;
            } else {
                serverReachable = false;
                if (lastError.isEmpty()) lastError = "所有列表均拉取失败";
            }

            save(ctx, nd);
            Log.i(TAG, "病毒库已更新(" + okCount + "/" + ALL_LISTS.length + "): 100%包名=" + nd.certainPkgs.size()
                    + " 100%名称=" + nd.certainNames.size()
                    + " 可疑包名=" + nd.suspectPkgs.size()
                    + " 可疑名称=" + nd.suspectNames.size()
                    + " 关键字=" + nd.keys.size());
        } finally {
            refreshing = false;
        }
    }

    // ======================================================================
    // 内部实现
    // ======================================================================

    private static void save(Context ctx, Data d) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            sp.edit()
                    .putString(K_CERTAIN_PKG, join(d.certainPkgs))
                    .putString(K_CERTAIN_NAME, join(d.certainNames))
                    .putString(K_SUSPECT_PKG, join(d.suspectPkgs))
                    .putString(K_SUSPECT_NAME, join(d.suspectNames))
                    .putString(K_KEYS, join(d.keys))
                    .putLong(K_TIME, lastFetchTime)
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "写入病毒库缓存失败", e);
        }
    }

    /** 拉取单个列表；失败返回 null（表示保留旧数据） */
    private static Set<String> fetchSet(ListStatus st, boolean isPkg) {
        lastHttpDetail = "";
        String body = httpGet(st.url);
        if (body == null) {
            st.ok = false;
            if (lastError == null || lastError.isEmpty()) {
                lastError = st.label + " 拉取失败"
                        + (lastHttpDetail.isEmpty() ? "" : "（" + lastHttpDetail + "）");
            }
            return null;
        }
        Set<String> set = parse(body, isPkg);
        st.ok = true;
        st.count = set.size();
        st.updatedAt = System.currentTimeMillis();
        return set;
    }

    /** 解析列表：兼容「一行一条」纯文本与 JSON 数组两种写法 */
    private static Set<String> parse(String body, boolean isPkg) {
        Set<String> out = new LinkedHashSet<>();
        if (body == null) return out;
        String s = body.replace("\uFEFF", "")
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace("[", "\n").replace("]", "\n")
                .replace("\"", "").replace(",", "\n");
        for (String line : s.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (isPkg) t = cleanPkg(t);
            if (t.isEmpty()) continue;
            out.add(t);
        }
        return out;
    }

    /** 清理包名（去 BOM/空白/非法字符） */
    private static String cleanPkg(String raw) {
        String s = raw.replace("\uFEFF", "").trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String join(Set<String> set) {
        StringBuilder sb = new StringBuilder();
        for (String s : set) {
            if (s == null || s.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        return sb.toString();
    }

    private static Set<String> splitToSet(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** 简单 HTTP GET（UTF-8），失败/非 200 返回 null */
    private static String httpGet(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(12000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "YulongShield/9.1.1");
            conn.setRequestProperty("Accept-Charset", "UTF-8");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                lastHttpDetail = "HTTP " + code;
                Log.w(TAG, "拉取失败(" + code + "): " + url);
                return null;
            }
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            br.close();
            return sb.toString();
        } catch (Exception e) {
            lastHttpDetail = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            Log.w(TAG, "拉取异常: " + url + " → " + e.getMessage());
            return null;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }
}
