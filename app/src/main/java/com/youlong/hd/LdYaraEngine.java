package com.youlong.hd;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * LdYaraEngine —— YARA {@code LD_*} 内置规则的纯 Java 子集实现,无原生 yara 依赖。
 *
 * <p>规则来源:桌面端 {@code yara_rules.py} 内 {@code BUILTIN_RULES} 的 10 条原创规则
 * (YARA 原文同步存放在 {@code assets/yara_ld10.yar},仅供展示/导出/后续转原生引擎用,
 * 运行期扫描只走本类的纯 Java 匹配,不加载该文件)。
 *
 * <p>YARA 语义到本类的映射:
 * <ul>
 *   <li>{@code "str" nocase ascii wide} —— 按 ASCII 与 UTF-16LE 双重字节搜索,
 *       大小写折叠仅针对 ASCII A-Z(与 YARA nocase 一致);</li>
 *   <li>{@code { 4D 5A }} 等 hex —— 按原始字节序列搜索;</li>
 *   <li>{@code /.../} regex(如 base64 长串)—— 对文件字节按 ISO-8859-1 解码后用
 *       {@link Pattern} 匹配,保证字节与字符 1:1 对应;</li>
 *   <li>condition 计数语义({@code 3 of them}/{@code 2 of them}/{@code #mz}/{@code @mz[1]})
 *       ——逐条按原规则条件实现,见各匹配器注释。</li>
 * </ul>
 *
 * <p>社区规则下载器(桌面端 {@code CommunityRulesDownloader})的合规约束
 * (需用户 consent、单文件 4MB 上限、部署前 {@code yara.compile} 校验)在本移动端引擎中延续:
 * 本引擎只内置上述 10 条原创规则,不下载、不执行任何外部规则文本。
 *
 * <p>线程安全:本类无可变静态状态,可多线程并发调用。
 */
public final class LdYaraEngine {

    /** 规则版本,与 {@code assets/yara_ld10.yar} 头部版本保持一致。 */
    public static final String VERSION = "LD10-v1";

    /** 单文件 / 单 zip 条目最多读取字节数(8MB)。 */
    public static final int MAX_BYTES_PER_FILE = 8 * 1024 * 1024;

    /** {@link #scanApk} 单次最多扫描的 zip 条目数。 */
    public static final int MAX_APK_ENTRIES = 200;

    /** APK 权限组合加分阈值:manifest 中危险权限个数达到该值则追加一条加分命中。 */
    public static final int PERM_COMBO_THRESHOLD = 3;

    private LdYaraEngine() {
    }

    // ========================================================================
    // 规则 / 命中模型
    // ========================================================================

    /**
     * 规则匹配器。
     *
     * @param data   文件原始字节
     * @param len    有效长度(data[0,len) 范围内搜索)
     * @param latin1 data 按 ISO-8859-1 解码的文本(供 regex 使用)
     */
    public interface RuleMatcher {
        boolean matches(byte[] data, int len, String latin1);
    }

    /** 单条规则:元信息 + 匹配器。 */
    public static final class Rule {
        /** 规则名,如 LD_PS_DownloadCradle。 */
        public final String name;
        /** 严重度:critical / high / medium。 */
        public final String severity;
        /** 规则描述(与 YARA meta.description 一致)。 */
        public final String description;
        /** 匹配器。 */
        public final RuleMatcher matcher;

        Rule(String name, String severity, String description, RuleMatcher matcher) {
            this.name = name;
            this.severity = severity;
            this.description = description;
            this.matcher = matcher;
        }

        @Override
        public String toString() {
            return name + "/" + severity;
        }
    }

    /** 单条命中记录。 */
    public static final class Hit {
        /** 命中的规则名。 */
        public final String rule;
        /** 严重度。 */
        public final String severity;
        /** 规则描述。 */
        public final String description;
        /** 命中来源:文件路径;APK 内条目形如 "apk路径!条目名"。 */
        public final String source;

        Hit(String rule, String severity, String description, String source) {
            this.rule = rule;
            this.severity = severity;
            this.description = description;
            this.source = source == null ? "" : source;
        }

        /** 本条命中的 JSON 对象字符串。 */
        public String toJson() {
            return "{\"rule\":\"" + escapeJson(rule)
                    + "\",\"severity\":\"" + escapeJson(severity)
                    + "\",\"description\":\"" + escapeJson(description)
                    + "\",\"source\":\"" + escapeJson(source) + "\"}";
        }

        @Override
        public String toString() {
            return rule + "/" + severity + " @ " + source;
        }
    }

    // ========================================================================
    // 字节匹配原语
    // ========================================================================

    /** 仅折叠 ASCII A-Z(与 YARA nocase 语义一致)。 */
    private static int lowerByte(int c) {
        return (c >= 'A' && c <= 'Z') ? (c + ('a' - 'A')) : c;
    }

    private static boolean bytesContain(byte[] data, int len, byte[] pat, boolean nocase) {
        if (pat.length == 0 || len < pat.length) {
            return false;
        }
        outer:
        for (int i = 0; i <= len - pat.length; i++) {
            for (int j = 0; j < pat.length; j++) {
                int d = data[i + j] & 0xFF;
                int p = pat[j] & 0xFF;
                if (nocase) {
                    d = lowerByte(d);
                    p = lowerByte(p);
                }
                if (d != p) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /** 纯 ASCII 字节搜索。 */
    private static boolean ascii(byte[] data, int len, String s, boolean nocase) {
        return bytesContain(data, len, s.getBytes(StandardCharsets.ISO_8859_1), nocase);
    }

    /** UTF-16LE(对应 YARA wide)字节搜索。 */
    private static boolean wide(byte[] data, int len, String s, boolean nocase) {
        return bytesContain(data, len, s.getBytes(StandardCharsets.UTF_16LE), nocase);
    }

    /**
     * 对应 YARA {@code "str" nocase ascii wide}:ASCII 或 UTF-16LE 任一命中即命中。
     * 大小写敏感版本传 {@code nocase=false} 即可。
     */
    private static boolean either(byte[] data, int len, String s, boolean nocase) {
        return ascii(data, len, s, nocase) || wide(data, len, s, nocase);
    }

    private static int countBytes(byte[] data, int len, byte[] pat) {
        if (pat.length == 0 || len < pat.length) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i <= len - pat.length; i++) {
            boolean same = true;
            for (int j = 0; j < pat.length; j++) {
                if (data[i + j] != pat[j]) {
                    same = false;
                    break;
                }
            }
            if (same) {
                count++;
            }
        }
        return count;
    }

    /** 首次出现偏移,未找到返回 -1。 */
    private static int firstOffsetOf(byte[] data, int len, byte[] pat) {
        if (pat.length == 0 || len < pat.length) {
            return -1;
        }
        outer:
        for (int i = 0; i <= len - pat.length; i++) {
            for (int j = 0; j < pat.length; j++) {
                if (data[i + j] != pat[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** 对应原规则 {@code $b64 = /[A-Za-z0-9+\/]{200,}={0,2}/ ascii}。 */
    private static final Pattern B64 =
            Pattern.compile("[A-Za-z0-9+/]{200,}={0,2}");

    // ========================================================================
    // 10 条 LD_* 内置规则(条件语义与 YARA 原文逐条对应)
    // ========================================================================

    private static final List<Rule> RULES;

    static {
        List<Rule> r = new ArrayList<Rule>();

        // condition: ($a1 and $a2) or ($b1 and $b2 and $b3) or ($a1 and $b1)
        r.add(new Rule("LD_PS_DownloadCradle", "high",
                "PowerShell download-and-execute cradle", new RuleMatcher() {
                    @Override
                    public boolean matches(byte[] data, int len, String latin1) {
                        boolean a1 = either(data, len, "DownloadString", true);
                        boolean a2 = either(data, len, "IEX", false);
                        boolean b1 = either(data, len, "Invoke-Expression", true);
                        boolean b2 = either(data, len, "Net.WebClient", true);
                        boolean b3 = either(data, len, "Start-Process", true);
                        return (a1 && a2) || (b1 && b2 && b3) || (a1 && b1);
                    }
                }));

        // condition: ($e1 or $e2) and $b64
        // ($e1 仅 ascii;$e2 ascii wide;先判 -enc/-e 标志再跑 regex,避免大文本无谓回溯)
        r.add(new Rule("LD_PS_EncodedCommand", "high",
                "PowerShell -enc/-e with long base64 blob", new RuleMatcher() {
                    @Override
                    public boolean matches(byte[] data, int len, String latin1) {
                        boolean e = ascii(data, len, "-enc ", true)
                                || either(data, len, "-e ", true);
                        if (!e) {
                            return false;
                        }
                        Matcher m = B64.matcher(latin1);
                        return m.find();
                    }
                }));

        // condition: #mz > 1 and #pe > 0 and @mz[1] > 512
        // (@mz[1] 为首次出现偏移:要求首个 MZ 在 512 字节之后,避免把普通 PE 自身误报)
        r.add(new Rule("LD_Embedded_PE_In_Text", "medium",
                "PE header embedded inside script/text blob", new RuleMatcher() {
                    @Override
                    public boolean matches(byte[] data, int len, String latin1) {
                        byte[] mz = new byte[]{0x4D, 0x5A};
                        byte[] pe = new byte[]{0x50, 0x45, 0x00, 0x00};
                        if (countBytes(data, len, mz) <= 1) {
                            return false;
                        }
                        if (countBytes(data, len, pe) <= 0) {
                            return false;
                        }
                        return firstOffsetOf(data, len, mz) > 512;
                    }
                }));

        // condition: 3 of them
        r.add(new Rule("LD_Ransomware_Note_Markers", "high",
                "Ransom note common field combo", new RuleMatcher() {
                    @Override
                    public boolean matches(byte[] data, int len, String latin1) {
                        int n = 0;
                        if (either(data, len, "bitcoin", true)) {
                            n++;
                        }
                        if (either(data, len, "decrypt", true)) {
                            n++;
                        }
                        if (either(data, len, "personal id", true)) {
                            n++;
                        }
                        if (either(data, len, ".onion", true)) {
                            n++;
                        }
                        return n >= 3;
                    }
                }));

        // condition: any of ($asp*) or any of ($php*) or $jsp1 (均为 ascii)
        r.add(new Rule("LD_Webshell_ASPX_PHP", "critical",
                "one-line webshell dynamic-exec patterns", new RuleMatcher() {
                    @Override
                    public boolean matches(byte[] data, int len, String latin1) {
                        if (ascii(data, len, "eval(request", true)) {
                            return true;
                        }
                        if (ascii(data, len, "execute(request", true)) {
                            return true;
                        }
                        if (ascii(data, len, "eval($_", true)) {
                            return true;
                        }
                        if (ascii(data, len, "assert($_", true)) {
                            return true;
                        }
                        if (ascii(data, len, "system($_", true)) {
                            return true;
                        }
                        return ascii(data, len, "Runtime.getRuntime().exec", true);
                    }
                }));

        // condition: $a and ($b or $c)
        r.add(new Rule("LD_Certutil_Decode_Abuse", "medium",
                "certutil -decode / -urlcache abuse", new RuleMatcher() {
                    @Override
                    public boolean matches(byte[] data, int len, String latin1) {
                        if (!either(data, len, "certutil", true)) {
                            return false;
                        }
                        return either(data, len, "-decode", true)
                                || either(data, len, "-urlcache", true);
                    }
                }));

        // condition: $a and any of ($b*)
        r.add(new Rule("LD_Rundll32_Script_Abuse", "high",
                "rundll32 javascript/vbscript/mshtml abuse", new RuleMatcher() {
                    @Override
                    public boolean matches(byte[] data, int len, String latin1) {
                        if (!either(data, len, "rundll32", true)) {
                            return false;
                        }
                        return either(data, len, "javascript:", true)
                                || either(data, len, "mshtml", true)
                                || either(data, len, "vbscript:", true);
                    }
                }));

        // condition: $a and ($b or $c) (原文均为 ascii)
        r.add(new Rule("LD_Autorun_Inf_Persist", "medium",
                "autorun.inf persistence marker", new RuleMatcher() {
                    @Override
                    public boolean matches(byte[] data, int len, String latin1) {
                        if (!ascii(data, len, "[autorun]", true)) {
                            return false;
                        }
                        return ascii(data, len, "open=", true)
                                || ascii(data, len, "shellexecute=", true);
                    }
                }));

        // condition: 2 of them (原文大小写敏感 ascii wide)
        r.add(new Rule("LD_Injector_API_Trio", "high",
                "classic remote-injection API trio", new RuleMatcher() {
                    @Override
                    public boolean matches(byte[] data, int len, String latin1) {
                        int n = 0;
                        if (either(data, len, "VirtualAllocEx", false)) {
                            n++;
                        }
                        if (either(data, len, "CreateRemoteThread", false)) {
                            n++;
                        }
                        if (either(data, len, "WriteProcessMemory", false)) {
                            n++;
                        }
                        return n >= 2;
                    }
                }));

        // condition: $a or $c (均为 nocase ascii;
        // $c 原文 "\\\\ADMIN$"(YARA 转义后为双反斜杠 + ADMIN$,即 UNC 的 \\ADMIN$);此处单/双反斜杠两种写法都认,防漏报)
        r.add(new Rule("LD_Lateral_Psexec_Markers", "medium",
                "psexec-style lateral movement markers", new RuleMatcher() {
                    @Override
                    public boolean matches(byte[] data, int len, String latin1) {
                        if (ascii(data, len, "PSEXESVC", true)) {
                            return true;
                        }
                        return ascii(data, len, "\\\\ADMIN$", true)
                                || ascii(data, len, "\\ADMIN$", true);
                    }
                }));

        RULES = Collections.unmodifiableList(r);
    }

    // ========================================================================
    // 对外扫描 API
    // ========================================================================

    /** 返回 10 条内置规则的元信息与匹配器(只读)。 */
    public static List<Rule> getRules() {
        return RULES;
    }

    /**
     * 对内存字节做全规则扫描。
     *
     * @param data   待扫字节
     * @param length 有效长度(只扫 data[0,length))
     * @return 命中列表(无命中返回空列表,不返回 null)
     */
    public static List<Hit> scanBytes(byte[] data, int length) {
        return scanBytes(data, length, "");
    }

    /**
     * 对内存字节做全规则扫描并标注来源。
     *
     * @param source 来源标注,写入 {@link Hit#source}
     */
    public static List<Hit> scanBytes(byte[] data, int length, String source) {
        List<Hit> hits = new ArrayList<Hit>();
        if (data == null || length <= 0) {
            return hits;
        }
        int len = Math.min(length, data.length);
        String latin1 = new String(data, 0, len, StandardCharsets.ISO_8859_1);
        for (int i = 0; i < RULES.size(); i++) {
            Rule rule = RULES.get(i);
            boolean hit;
            try {
                hit = rule.matcher.matches(data, len, latin1);
            } catch (Exception e) {
                hit = false;
            }
            if (hit) {
                hits.add(new Hit(rule.name, rule.severity, rule.description,
                        source == null ? "" : source));
            }
        }
        return hits;
    }

    /**
     * 扫描单个文件(默认只读前 {@link #MAX_BYTES_PER_FILE} 字节)。
     *
     * @param path 文件路径
     */
    public static List<Hit> scanFile(String path) {
        return scanFile(path, MAX_BYTES_PER_FILE);
    }

    /**
     * 扫描单个文件。
     *
     * @param path     文件路径
     * @param maxBytes 最多读取字节数(默认 8MB,见 {@link #MAX_BYTES_PER_FILE})
     * @return 命中列表(文件不存在/不可读返回空列表)
     */
    public static List<Hit> scanFile(String path, int maxBytes) {
        List<Hit> hits = new ArrayList<Hit>();
        if (path == null || maxBytes <= 0) {
            return hits;
        }
        File f = new File(path);
        if (!f.exists() || !f.isFile()) {
            return hits;
        }
        byte[] buf;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            buf = readCapped(in, maxBytes);
        } catch (IOException e) {
            return hits;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // 忽略关闭异常
                }
            }
        }
        return scanBytes(buf, buf.length, path);
    }

    /**
     * 扫描 APK:解 zip 遍历条目,对 {@code classes.dex} / {@code AndroidManifest.xml} /
     * 其余条目逐项做 {@link #scanFile} 等价扫描(每项只读前 8MB,总条目上限 200),
     * 命中来源记为 {@code "apk路径!条目名"};最后做权限组合加分。
     *
     * @param apkPath APK 文件路径
     * @return 命中列表(含可能的权限组合加分项 {@code LD_APK_PermCombo_Suspicious})
     */
    public static List<Hit> scanApk(String apkPath) {
        List<Hit> hits = new ArrayList<Hit>();
        ZipFile zip = null;
        try {
            zip = new ZipFile(apkPath);
            Enumeration<? extends ZipEntry> en = zip.entries();
            int scanned = 0;
            String manifestText = null;
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                if (scanned >= MAX_APK_ENTRIES) {
                    break;
                }
                String name = e.getName();
                InputStream entryIn = null;
                try {
                    entryIn = zip.getInputStream(e);
                    byte[] buf = readCapped(entryIn, MAX_BYTES_PER_FILE);
                    hits.addAll(scanBytes(buf, buf.length, apkPath + "!" + name));
                    if (manifestText == null && "AndroidManifest.xml".equals(name)) {
                        manifestText = new String(buf, 0, buf.length,
                                StandardCharsets.ISO_8859_1);
                    }
                } catch (IOException e2) {
                    // 单条目读失败跳过,不影响其余条目
                } finally {
                    if (entryIn != null) {
                        try {
                            entryIn.close();
                        } catch (IOException ignored) {
                            // 忽略关闭异常
                        }
                    }
                }
                scanned++;
            }
            Hit combo = permComboHit(manifestText, apkPath);
            if (combo != null) {
                hits.add(combo);
            }
        } catch (IOException e) {
            // 打不开 / 非 zip:回退按普通文件扫描
            hits.addAll(scanFile(apkPath, MAX_BYTES_PER_FILE));
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                    // 忽略关闭异常
                }
            }
        }
        return hits;
    }

    /** APK 权限组合加分:manifest 中危险权限达到阈值则追加一条 medium 命中。 */
    private static final String[] DANGEROUS_PERMS = new String[]{
            "ANDROID.PERMISSION.SEND_SMS",
            "ANDROID.PERMISSION.READ_SMS",
            "ANDROID.PERMISSION.RECEIVE_SMS",
            "ANDROID.PERMISSION.READ_CONTACTS",
            "ANDROID.PERMISSION.READ_CALL_LOG",
            "ANDROID.PERMISSION.RECORD_AUDIO",
            "ANDROID.PERMISSION.CAMERA",
            "ANDROID.PERMISSION.ACCESS_FINE_LOCATION",
            "ANDROID.PERMISSION.SYSTEM_ALERT_WINDOW",
            "ANDROID.PERMISSION.BIND_DEVICE_ADMIN",
            "ANDROID.PERMISSION.REQUEST_INSTALL_PACKAGES",
            "ANDROID.PERMISSION.INSTALL_PACKAGES",
            "ANDROID.PERMISSION.READ_PHONE_STATE",
            "ANDROID.PERMISSION.PROCESS_OUTGOING_CALLS",
    };

    private static Hit permComboHit(String manifestLatin1, String apkPath) {
        if (manifestLatin1 == null || manifestLatin1.length() == 0) {
            return null;
        }
        String up = manifestLatin1.toUpperCase(Locale.US);
        int count = 0;
        for (int i = 0; i < DANGEROUS_PERMS.length; i++) {
            if (up.indexOf(DANGEROUS_PERMS[i]) >= 0) {
                count++;
            }
        }
        if (count >= PERM_COMBO_THRESHOLD) {
            return new Hit("LD_APK_PermCombo_Suspicious", "medium",
                    "APK declares " + count + " dangerous permissions"
                            + " (permission-combo bonus, threshold "
                            + PERM_COMBO_THRESHOLD + ")",
                    (apkPath == null ? "" : apkPath) + "!AndroidManifest.xml");
        }
        return null;
    }

    /**
     * 一键扫描并返回 JSON 字符串。APK(扩展名或 zip 魔数判定)走 {@link #scanApk},
     * 其余走 {@link #scanFile}。
     *
     * <p>格式:
     * {@code {"engine":"LdYaraEngine LD10-v1","path":"...","hitCount":N,
     * "hits":[{"rule":...,"severity":...,"description":...,"source":...}],...}}
     *
     * @param path 文件/APK 路径
     */
    public static String scanToJson(String path) {
        List<Hit> hits;
        String error = null;
        if (path == null || !(new File(path).exists())) {
            hits = new ArrayList<Hit>();
            error = "file_not_found";
        } else if (looksLikeZip(path)) {
            hits = scanApk(path);
        } else {
            hits = scanFile(path, MAX_BYTES_PER_FILE);
        }
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"engine\":\"LdYaraEngine ").append(VERSION).append("\"");
        sb.append(",\"path\":\"").append(escapeJson(path == null ? "" : path)).append("\"");
        sb.append(",\"hitCount\":").append(hits.size());
        sb.append(",\"hits\":[");
        for (int i = 0; i < hits.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(hits.get(i).toJson());
        }
        sb.append(']');
        if (error != null) {
            sb.append(",\"error\":\"").append(error).append("\"");
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * 引擎规则状态 JSON:{@code {"ruleCount":10,"version":"LD10-v1"}}。
     * (权限组合加分为引擎附加项,不计入 ruleCount。)
     */
    public static String getRulesStatus() {
        return "{\"ruleCount\":" + RULES.size() + ",\"version\":\"" + VERSION + "\"}";
    }

    // ========================================================================
    // 内部工具
    // ========================================================================

    private static byte[] readCapped(InputStream in, int cap) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int total = 0;
        while (total < cap) {
            int want = Math.min(buf.length, cap - total);
            int n = in.read(buf, 0, want);
            if (n < 0) {
                break;
            }
            out.write(buf, 0, n);
            total += n;
        }
        return out.toByteArray();
    }

    /** 扩展名(.apk/.zip/.jar/.xapk/.apks)或 zip 魔数 PK\x03\x04 判定。 */
    private static boolean looksLikeZip(String path) {
        String l = path.toLowerCase(Locale.US);
        if (l.endsWith(".apk") || l.endsWith(".zip") || l.endsWith(".jar")
                || l.endsWith(".xapk") || l.endsWith(".apks")) {
            return true;
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(path);
            byte[] m = new byte[4];
            int n = 0;
            while (n < 4) {
                int c = in.read(m, n, 4 - n);
                if (c < 0) {
                    break;
                }
                n += c;
            }
            return n == 4 && (m[0] & 0xFF) == 0x50 && (m[1] & 0xFF) == 0x4B
                    && (m[2] & 0xFF) == 0x03 && (m[3] & 0xFF) == 0x04;
        } catch (IOException e) {
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // 忽略关闭异常
                }
            }
        }
    }

    static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        return sb.toString();
    }
}
