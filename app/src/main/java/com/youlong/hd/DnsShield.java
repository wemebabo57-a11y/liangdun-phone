package com.youlong.hd;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * DNS 防污染模块（DnsShield）。
 *
 * <p>两档查询通道（DoT/TCP853 预留为第三档，见下备注）：
 * <ol>
 * <li><b>DoH 加密优先</b>：POST {@code application/dns-message} 到
 * {@code https://223.5.5.5/dns-query} 等加密端点，
 * HttpURLConnection 实现 DNS wire format 编码/解码，4.5s 超时；</li>
 * <li><b>PLAIN 兜底</b>：UDP 53 明文查询（2s 超时），仅在全部 DoH 不可用时使用。</li>
 * </ol>
 *
 * <p>污染检测（命中即 failover 到下一个通道）：
 * <ul>
 * <li>应答含 {@code 0.0.0.0} / {@code ::}；</li>
 * <li>NXDOMAIN 异常：主通道 NXDOMAIN 但对照通道有数据（或反之）；</li>
 * <li>多 IP 不一致：主备两路都有数据但 IP 集合完全不交，保留加密通道答案并标记 polluted。</li>
 * </ul>
 *
 * <p>优选结果存 SharedPreferences（{@code dns_shield_prefs}）。
 * 纯静态工具类，用法仿 {@link VirusDb}，不依赖 manifest 新增权限（已有 INTERNET）。
 */
public final class DnsShield {

    private static final String TAG = "DnsShield";
    private static final String PREF = "dns_shield_prefs";

    private static final String K_PRIMARY = "primary";           // 主：DoH URL 或 PLAIN IP
    private static final String K_PRIMARY_KIND = "primary_kind"; // "doh" / "plain"
    private static final String K_SECONDARY = "secondary";       // 备：DoH URL 或 PLAIN IP
    private static final String K_SECONDARY_KIND = "secondary_kind";
    private static final String K_RTT = "rtt";                   // 主通道 RTT（毫秒）
    private static final String K_SECURE = "secure";             // 主通道是否加密
    private static final String K_LAST = "lastCheck";            // 最近一次优选时间戳

    /** 探测域名（国内命中缓存率高，减少干扰；同 dns_optimizer.py 的 PROBE_DOMAIN） */
    private static final String PROBE_DOMAIN = "www.qq.com";
    /** DoH 单次超时 4.5s */
    private static final int DOH_TIMEOUT_MS = 4500;
    /** PLAIN UDP53 单次超时 2s */
    private static final int UDP_TIMEOUT_MS = 2000;

    // ================= 内置 DNS 池 =================
    /** {厂商, IP}：PLAIN UDP53 备用通道 */
    private static final String[][] PLAIN_SERVERS = {
            {"阿里 AliDNS", "223.5.5.5"},
            {"阿里 AliDNS", "223.6.6.6"},
            {"腾讯 DNSPod", "119.29.29.29"},
            {"腾讯 DNSPod", "119.28.28.28"},
            {"114DNS", "114.114.114.114"},
            {"114DNS", "114.114.115.115"},
            {"百度 BaiduDNS", "180.76.76.76"},
            {"Cloudflare", "1.1.1.1"},
            {"Quad9", "9.9.9.9"},
            {"Google(备选)", "8.8.8.8"},
    };

    /** {厂商, 参考IP, DoH URL}：加密优先通道（POST application/dns-message） */
    private static final String[][] DOH_ENDPOINTS = {
            {"阿里 AliDNS", "223.5.5.5", "https://223.5.5.5/dns-query"},
            {"阿里 AliDNS(域名)", "223.5.5.5", "https://dns.alidns.com/dns-query"},
            {"腾讯 DNSPod(DoH)", "119.29.29.29", "https://doh.pub/dns-query"},
            {"114DNS", "114.114.114.114", "https://114.114.114.114/dns-query"},
            {"Cloudflare", "1.1.1.1", "https://1.1.1.1/dns-query"},
            {"Cloudflare(域名)", "1.1.1.1", "https://cloudflare-dns.com/dns-query"},
            {"Quad9", "9.9.9.9", "https://9.9.9.9/dns-query"},
            {"Google(备选)", "8.8.8.8", "https://dns.google/dns-query"},
    };

    // 备注：DoT（TCP 853 直连）预留为第三档。若后续需要，在此处加 SSLSocket 实现即可，
    // 按“DoH → DoT → PLAIN”三档排序候选，不影响现有两档逻辑。

    private DnsShield() {
    }

    // ================= DNS wire format 编解码 =================

    private static void w16(ByteArrayOutputStream bos, int v) {
        bos.write((v >> 8) & 0xFF);
        bos.write(v & 0xFF);
    }

    private static int u16(byte[] b, int o) {
        return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
    }

    /** 构造 A/IN 查询报文 */
    private static byte[] buildQuery(String domain, int tid) throws Exception {
        String ascii = IDN.toASCII(domain, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.US);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64);
        w16(bos, tid);
        w16(bos, 0x0100); // RD
        w16(bos, 1);      // QDCOUNT
        w16(bos, 0);
        w16(bos, 0);
        w16(bos, 0);
        for (String part : ascii.split("\\.")) {
            byte[] lb = part.getBytes("US-ASCII");
            if (lb.length == 0 || lb.length > 63) throw new IllegalArgumentException("bad label");
            bos.write(lb.length);
            bos.write(lb, 0, lb.length);
        }
        bos.write(0);
        w16(bos, 1); // QTYPE A
        w16(bos, 1); // QCLASS IN
        return bos.toByteArray();
    }

    /** 跳过域名（含压缩指针 0xC0），返回下一字段偏移，失败返回 -1 */
    private static int skipName(byte[] b, int off) {
        int jumps = 0;
        while (true) {
            if (off >= b.length || jumps > 16) return -1;
            int len = b[off] & 0xFF;
            if (len == 0) return off + 1;
            if ((len & 0xC0) == 0xC0) return off + 2; // 压缩指针固定占 2 字节
            off += 1 + len;
            jumps++;
        }
    }

    /** 解析响应；tid 不符/结构非法返回 null（调用方视为错包继续等） */
    private static DnsResult parseResponse(byte[] resp, int expectTid) {
        if (resp == null || resp.length < 12) return null;
        if (u16(resp, 0) != expectTid) return null;
        int rcode = u16(resp, 2) & 0x000F;
        int qd = u16(resp, 4);
        int an = u16(resp, 6);
        int off = 12;
        for (int i = 0; i < qd; i++) {
            off = skipName(resp, off);
            if (off < 0 || off + 4 > resp.length) return null;
            off += 4;
        }
        DnsResult r = new DnsResult();
        r.rcode = rcode;
        for (int i = 0; i < an; i++) {
            off = skipName(resp, off);
            if (off < 0 || off + 10 > resp.length) return null;
            int type = u16(resp, off);
            int cls = u16(resp, off + 2);
            int rdlen = u16(resp, off + 8);
            off += 10;
            if (off + rdlen > resp.length) return null;
            if (type == 1 && cls == 1 && rdlen == 4) {
                r.ips.add((resp[off] & 0xFF) + "." + (resp[off + 1] & 0xFF)
                        + "." + (resp[off + 2] & 0xFF) + "." + (resp[off + 3] & 0xFF));
            }
            off += rdlen;
        }
        return r;
    }

    private static byte[] readAll(InputStream in, int cap) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int total = 0, n;
        try {
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > cap) throw new java.io.IOException("response too large");
                bos.write(buf, 0, n);
            }
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
        return bos.toByteArray();
    }

    // ================= 两档查询通道 =================

    /** 第一档：DoH（POST application/dns-message） */
    private static QueryOutcome dohQuery(String urlStr, String domain, int timeoutMs) {
        HttpURLConnection c = null;
        long t0 = SystemClock.elapsedRealtime();
        try {
            int tid = new Random().nextInt(0x10000);
            byte[] q = buildQuery(domain, tid);
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/dns-message");
            c.setRequestProperty("Accept", "application/dns-message");
            c.setFixedLengthStreamingMode(q.length);
            OutputStream os = c.getOutputStream();
            try {
                os.write(q);
                os.flush();
            } finally {
                try {
                    os.close();
                } catch (Exception ignored) {
                }
            }
            if (c.getResponseCode() != 200) return QueryOutcome.fail("http-" + c.getResponseCode());
            byte[] resp = readAll(c.getInputStream(), 4096);
            DnsResult r = parseResponse(resp, tid);
            if (r == null) return QueryOutcome.fail("bad-response");
            return QueryOutcome.ok(r, SystemClock.elapsedRealtime() - t0, true, urlStr);
        } catch (Exception e) {
            return QueryOutcome.fail(e.getClass().getSimpleName());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 第二档：PLAIN UDP53（来源 IP 校验，防伪造注入；同 dns_optimizer.py） */
    private static QueryOutcome udpQuery(String serverIp, String domain, int timeoutMs) {
        DatagramSocket s = null;
        try {
            int tid = new Random().nextInt(0x10000);
            byte[] q = buildQuery(domain, tid);
            InetAddress addr = InetAddress.getByName(serverIp);
            s = new DatagramSocket();
            s.setSoTimeout(timeoutMs);
            long t0 = SystemClock.elapsedRealtime();
            long deadline = t0 + timeoutMs;
            s.send(new DatagramPacket(q, q.length, addr, 53));
            byte[] buf = new byte[512];
            while (true) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (java.net.SocketTimeoutException ste) {
                    return QueryOutcome.fail("timeout");
                }
                if (p.getAddress() == null || !serverIp.equals(p.getAddress().getHostAddress())) continue;
                byte[] resp = new byte[p.getLength()];
                System.arraycopy(p.getData(), p.getOffset(), resp, 0, resp.length);
                DnsResult r = parseResponse(resp, tid);
                if (r == null) {
                    long rem = deadline - SystemClock.elapsedRealtime();
                    if (rem <= 0) return QueryOutcome.fail("timeout");
                    s.setSoTimeout((int) rem);
                    continue; // tid 不符/错包：继续等到超时
                }
                return QueryOutcome.ok(r, SystemClock.elapsedRealtime() - t0, false, serverIp);
            }
        } catch (Exception e) {
            return QueryOutcome.fail(e.getClass().getSimpleName());
        } finally {
            if (s != null) s.close();
        }
    }

    // ================= 污染判定 =================

    /** 污染特征 IP */
    private static boolean hasPoisonIp(List<String> ips) {
        for (String ip : ips) {
            if ("0.0.0.0".equals(ip) || "::".equals(ip) || "0:0:0:0:0:0:0:0".equals(ip)) return true;
        }
        return false;
    }

    private static boolean disjoint(List<String> a, List<String> b) {
        for (String x : a) {
            if (b.contains(x)) return false;
        }
        return true;
    }

    private static String normaliseDomain(String domain) {
        if (domain == null) return null;
        String d = domain.trim().toLowerCase(Locale.US);
        while (d.endsWith(".")) d = d.substring(0, d.length() - 1);
        if (d.isEmpty() || d.length() > 253 || !d.contains(".")) return null;
        try {
            String ascii = IDN.toASCII(d, IDN.ALLOW_UNASSIGNED);
            if (!ascii.matches("[A-Za-z0-9.-]+")) return null;
            return ascii;
        } catch (Exception e) {
            return null;
        }
    }

    // ================= 测速优选 =================

    private static final class Probe {
        boolean ok;
        String kind;   // "doh" / "plain"
        String label;
        String target; // DoH URL 或 PLAIN IP
        long rttMs;
        boolean secure;

        static Probe fail() {
            Probe p = new Probe();
            p.ok = false;
            return p;
        }

        static Probe ok(String kind, String label, String target, long rttMs, boolean secure) {
            Probe p = new Probe();
            p.ok = true;
            p.kind = kind;
            p.label = label;
            p.target = target;
            p.rttMs = rttMs;
            p.secure = secure;
            return p;
        }
    }

    /**
     * 并发测全部通道 RTT，选最快主备组合存 SharedPreferences。
     * 主优先选最快的 DoH（加密优先），备选与主不同目标的最快 PLAIN（通道多样性）。
     * 同步方法：调用方需在后台线程执行（见 {@link #refreshAsync}）。
     */
    public static void benchmark(Context ctx) {
        final long start = SystemClock.elapsedRealtime();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Probe>> fs = new ArrayList<Future<Probe>>();
        for (final String[] e : DOH_ENDPOINTS) {
            final String label = e[0];
            final String url = e[2];
            fs.add(pool.submit(new Callable<Probe>() {
                @Override
                public Probe call() {
                    QueryOutcome o = dohQuery(url, PROBE_DOMAIN, DOH_TIMEOUT_MS);
                    if (!o.ok || o.dns.rcode != 0 || o.dns.ips.isEmpty()
                            || hasPoisonIp(o.dns.ips)) return Probe.fail();
                    return Probe.ok("doh", label, url, o.rttMs, true);
                }
            }));
        }
        for (final String[] s : PLAIN_SERVERS) {
            final String label = s[0];
            final String ip = s[1];
            fs.add(pool.submit(new Callable<Probe>() {
                @Override
                public Probe call() {
                    QueryOutcome o = udpQuery(ip, PROBE_DOMAIN, UDP_TIMEOUT_MS);
                    if (!o.ok || o.dns.rcode != 0 || o.dns.ips.isEmpty()
                            || hasPoisonIp(o.dns.ips)) return Probe.fail();
                    return Probe.ok("plain", label, ip, o.rttMs, false);
                }
            }));
        }
        List<Probe> ok = new ArrayList<Probe>();
        for (Future<Probe> f : fs) {
            try {
                Probe p = f.get(20, TimeUnit.SECONDS);
                if (p.ok) ok.add(p);
            } catch (Exception ignored) {
            }
        }
        pool.shutdownNow();
        Collections.sort(ok, new Comparator<Probe>() {
            @Override
            public int compare(Probe a, Probe b) {
                return Long.compare(a.rttMs, b.rttMs);
            }
        });
        Probe primary = null;
        Probe secondary = null;
        for (Probe p : ok) {
            if ("doh".equals(p.kind)) {
                primary = p;
                break;
            }
        }
        if (primary == null && !ok.isEmpty()) primary = ok.get(0); // 无可用 DoH 才退到 PLAIN
        if (primary != null) {
            for (Probe p : ok) {
                if (!p.target.equals(primary.target) && "plain".equals(p.kind)) {
                    secondary = p;
                    break;
                }
            }
            if (secondary == null) {
                for (Probe p : ok) {
                    if (!p.target.equals(primary.target)) {
                        secondary = p;
                        break;
                    }
                }
            }
        }
        SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = sp.edit();
        long now = System.currentTimeMillis();
        if (primary != null) {
            ed.putString(K_PRIMARY, primary.target);
            ed.putString(K_PRIMARY_KIND, primary.kind);
            ed.putString(K_SECONDARY, secondary != null ? secondary.target : "");
            ed.putString(K_SECONDARY_KIND, secondary != null ? secondary.kind : "");
            ed.putLong(K_RTT, primary.rttMs);
            ed.putBoolean(K_SECURE, primary.secure);
            Log.i(TAG, "benchmark done: primary=" + primary.label + " " + primary.target
                    + " rtt=" + primary.rttMs + "ms secure=" + primary.secure
                    + (secondary != null
                    ? (" secondary=" + secondary.label + " " + secondary.target
                    + " rtt=" + secondary.rttMs + "ms") : " secondary=none")
                    + " cost=" + (SystemClock.elapsedRealtime() - start) + "ms");
        } else {
            Log.w(TAG, "benchmark: all servers unreachable, keep old prefs");
        }
        ed.putLong(K_LAST, now);
        ed.apply();
    }

    /** 后台触发一次测速优选（不阻塞调用线程） */
    public static void refreshAsync(Context ctx) {
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    benchmark(app);
                } catch (Exception e) {
                    Log.e(TAG, "refreshAsync error", e);
                }
            }
        }, "DnsShield-refresh").start();
    }

    // ================= 安全查询 =================

    private static final class Endpoint {
        String kind;   // "doh" / "plain"
        String target; // DoH URL 或 PLAIN IP
    }

    private static void addEndpoint(List<Endpoint> l, String kind, String target) {
        if (kind == null || target == null || target.isEmpty()) return;
        for (Endpoint e : l) {
            if (e.kind.equals(kind) && e.target.equals(target)) return;
        }
        Endpoint e = new Endpoint();
        e.kind = kind;
        e.target = target;
        l.add(e);
    }

    /**
     * 安全查询域名（加密优先、PLAIN 兜底；污染自动 failover）。
     * 返回 JSON：{domain, ips[], secure, server, polluted, rcode, crossChecked}，
     * 失败时返回 {domain, polluted, error}。
     */
    public static String secureQuery(Context ctx, String domain) {
        JSONObject out = new JSONObject();
        try {
            String d = normaliseDomain(domain);
            if (d == null) {
                out.put("error", "bad-domain");
                return out.toString();
            }
            out.put("domain", d);
            SharedPreferences sp = ctx.getApplicationContext()
                    .getSharedPreferences(PREF, Context.MODE_PRIVATE);
            // 有序候选：已存主 → 全部 DoH → 已存备 → 全部 PLAIN（去重）
            List<Endpoint> cands = new ArrayList<Endpoint>();
            addEndpoint(cands, sp.getString(K_PRIMARY_KIND, "doh"), sp.getString(K_PRIMARY, ""));
            for (String[] e : DOH_ENDPOINTS) addEndpoint(cands, "doh", e[2]);
            addEndpoint(cands, sp.getString(K_SECONDARY_KIND, "plain"), sp.getString(K_SECONDARY, ""));
            for (String[] s : PLAIN_SERVERS) addEndpoint(cands, "plain", s[1]);

            QueryOutcome ans = null;
            Endpoint ansEp = null;
            boolean sawPollution = false;
            for (Endpoint ep : cands) {
                QueryOutcome o = "doh".equals(ep.kind)
                        ? dohQuery(ep.target, d, DOH_TIMEOUT_MS)
                        : udpQuery(ep.target, d, UDP_TIMEOUT_MS);
                if (!o.ok) continue;
                if (o.dns.rcode == 0 && hasPoisonIp(o.dns.ips)) {
                    sawPollution = true;
                    Log.w(TAG, "secureQuery polluted answer from " + ep.target + ", failover");
                    continue;
                }
                ans = o;
                ansEp = ep;
                break; // 首个可用答案（含 NXDOMAIN，留待交叉验证）
            }
            if (ans == null) {
                out.put("polluted", sawPollution);
                out.put("error", sawPollution ? "all-polluted" : "all-failed");
                return out.toString();
            }
            // 交叉验证：换一个不同通道查一次，检测 NXDOMAIN 异常 / 多 IP 不一致
            boolean crossChecked = false;
            boolean polluted = sawPollution;
            for (Endpoint ep : cands) {
                if (ep.target.equals(ansEp.target)) continue;
                QueryOutcome o2 = "doh".equals(ep.kind)
                        ? dohQuery(ep.target, d, DOH_TIMEOUT_MS)
                        : udpQuery(ep.target, d, UDP_TIMEOUT_MS);
                if (!o2.ok) continue;
                crossChecked = true;
                if (o2.dns.rcode == 0 && hasPoisonIp(o2.dns.ips)) {
                    polluted = true; // 对照通道被污染，保留主答案
                    Log.w(TAG, "secureQuery cross-check polluted from " + ep.target);
                    break;
                }
                if (ans.dns.rcode == 3 && o2.dns.rcode == 0 && !o2.dns.ips.isEmpty()) {
                    // 主 NXDOMAIN 但对照通道有数据 → 主答案异常，failover
                    polluted = true;
                    Log.w(TAG, "secureQuery NXDOMAIN mismatch, failover "
                            + ansEp.target + " -> " + ep.target);
                    ans = o2;
                    ansEp = ep;
                    break;
                }
                if (ans.dns.rcode == 0 && !ans.dns.ips.isEmpty() && o2.dns.rcode == 3) {
                    // 主通道有数据但对照 NXDOMAIN → 对照侧异常，保留主答案并标记
                    polluted = true;
                    Log.w(TAG, "secureQuery reverse NXDOMAIN from " + ep.target);
                    break;
                }
                if (ans.dns.rcode == 0 && o2.dns.rcode == 0
                        && !ans.dns.ips.isEmpty() && !o2.dns.ips.isEmpty()
                        && disjoint(ans.dns.ips, o2.dns.ips)) {
                    // 两边都有数据但 IP 集合完全不交 → 至少一边被污染，保留加密通道答案
                    polluted = true;
                    Log.w(TAG, "secureQuery ip-set mismatch " + ans.dns.ips
                            + " vs " + o2.dns.ips);
                    if (!ans.secure && o2.secure) {
                        ans = o2;
                        ansEp = ep;
                    }
                    break;
                }
                break; // 对照通道正常且一致（或双 NXDOMAIN），结束验证
            }
            out.put("ips", new JSONArray(ans.dns.ips));
            out.put("secure", ans.secure);
            out.put("server", ansEp.target);
            out.put("polluted", polluted);
            out.put("rcode", ans.dns.rcode);
            out.put("crossChecked", crossChecked);
            return out.toString();
        } catch (Exception e) {
            Log.e(TAG, "secureQuery error", e);
            try {
                out.put("error", e.getClass().getSimpleName());
            } catch (Exception ignored) {
            }
            return out.toString();
        }
    }

    /**
     * VPN 数据面快速查询（单次 pass，不做交叉验证，低延迟）。
     * 返回 JSONObject：成功 {ips[], rcode, secure, server}；
     * 失败 {polluted, error}（全部被污染或全部失败，调用方应回 SERVFAIL）。
     */
    public static JSONObject queryFast(Context ctx, String domain) {
        JSONObject out = new JSONObject();
        try {
            String d = normaliseDomain(domain);
            if (d == null) {
                out.put("error", "bad-domain");
                return out;
            }
            out.put("domain", d);
            SharedPreferences sp = ctx.getApplicationContext()
                    .getSharedPreferences(PREF, Context.MODE_PRIVATE);
            List<Endpoint> cands = new ArrayList<Endpoint>();
            addEndpoint(cands, sp.getString(K_PRIMARY_KIND, "doh"), sp.getString(K_PRIMARY, ""));
            for (String[] e : DOH_ENDPOINTS) addEndpoint(cands, "doh", e[2]);
            addEndpoint(cands, sp.getString(K_SECONDARY_KIND, "plain"), sp.getString(K_SECONDARY, ""));
            for (String[] s : PLAIN_SERVERS) addEndpoint(cands, "plain", s[1]);
            boolean sawPollution = false;
            for (Endpoint ep : cands) {
                QueryOutcome o = "doh".equals(ep.kind)
                        ? dohQuery(ep.target, d, DOH_TIMEOUT_MS)
                        : udpQuery(ep.target, d, UDP_TIMEOUT_MS);
                if (!o.ok) continue;
                if (o.dns.rcode == 0 && hasPoisonIp(o.dns.ips)) {
                    sawPollution = true;
                    continue;
                }
                out.put("ips", new JSONArray(o.dns.ips));
                out.put("secure", o.secure);
                out.put("server", ep.target);
                out.put("rcode", o.dns.rcode);
                return out;
            }
            out.put("polluted", sawPollution);
            out.put("error", sawPollution ? "all-polluted" : "all-failed");
            return out;
        } catch (Exception e) {
            try { out.put("error", e.getClass().getSimpleName()); } catch (Exception ignored) {}
            return out;
        }
    }

    // ================= 状态 API =================

    /**
     * 返回当前优选状态 JSON：{primary, secondary, rtt, secure, lastCheck}。
     * 首次测速前 primary/secondary 为空串、rtt 为 -1。
     */
    public static String getStatus(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
            o.put("primary", sp.getString(K_PRIMARY, ""));
            o.put("secondary", sp.getString(K_SECONDARY, ""));
            o.put("rtt", sp.getLong(K_RTT, -1L));
            o.put("secure", sp.getBoolean(K_SECURE, false));
            o.put("lastCheck", sp.getLong(K_LAST, 0L));
        } catch (Exception e) {
            Log.e(TAG, "getStatus error", e);
            try {
                o.put("error", e.getClass().getSimpleName());
            } catch (Exception ignored) {
            }
        }
        return o.toString();
    }

    // ================= 内部结构 =================

    private static final class DnsResult {
        int rcode = -1;
        List<String> ips = new ArrayList<String>();
    }

    private static final class QueryOutcome {
        boolean ok;
        DnsResult dns;
        long rttMs;
        boolean secure;
        String server;
        String err;

        static QueryOutcome ok(DnsResult dns, long rttMs, boolean secure, String server) {
            QueryOutcome o = new QueryOutcome();
            o.ok = true;
            o.dns = dns;
            o.rttMs = rttMs;
            o.secure = secure;
            o.server = server;
            return o;
        }

        static QueryOutcome fail(String err) {
            QueryOutcome o = new QueryOutcome();
            o.ok = false;
            o.err = err;
            return o;
        }
    }
}
