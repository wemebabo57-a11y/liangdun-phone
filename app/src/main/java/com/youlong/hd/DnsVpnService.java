package com.youlong.hd;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * DNS 全局接管（DnsVpnService：DNS-only VPN）。
 *
 * <p>只把 DNS 流量引进 TUN，其余流量不走 VPN（不断网、不降速）：
 * <ul>
 * <li>系统当前 DNS + 12 个常用公共 DNS 的 /32 路由进 TUN；</li>
 * <li>TUN 内只处理 IPv4/UDP dport=53，其它包直接丢弃（本来也不该出现）；</li>
 * <li>DNS 查询用 {@link DnsShield#queryFast} 走加密通道解析（含 0.0.0.0 污染检测），
 * 本地组装 DNS 应答写回 TUN；</li>
 * <li>本应用自身排除在 VPN 之外（addDisallowedApplication），上游 DoH/UDP 不会回流进 TUN。</li>
 * </ul>
 *
 * <p>局限：应用内建 DoH（直连 443，非 53 端口）无法在 DNS 层拦截，如实告知用户。
 */
public class DnsVpnService extends VpnService {

    private static final String TAG = "DnsVpn";
    private static final String PREF = "dns_vpn_prefs";
    private static final String K_STARTED = "startedAt";
    private static final String K_QUERIES = "queries";
    private static final String K_BLOCKED = "blocked";
    private static final String K_LAST = "lastDomain";

    private static volatile boolean sLive = false;

    /** 常用公共 DNS：除系统 DNS 外一并路由进 TUN（/32）。 */
    private static final String[] PUBLIC_DNS = {
            "223.5.5.5", "223.6.6.6",
            "119.29.29.29", "119.28.28.28",
            "114.114.114.114", "114.114.115.115",
            "180.76.76.76",
            "1.1.1.1", "1.0.0.1",
            "8.8.8.8", "8.8.4.4",
            "9.9.9.9",
    };

    private volatile boolean running = false;
    private Thread worker;
    private ParcelFileDescriptor tun;

    public static void start(Context ctx) {
        try {
            ctx.startService(new Intent(ctx, DnsVpnService.class));
        } catch (Exception e) {
            Log.e(TAG, "start failed", e);
        }
    }

    public static void stop(Context ctx) {
        try {
            ctx.stopService(new Intent(ctx, DnsVpnService.class));
        } catch (Exception ignored) {
        }
    }

    /** 状态 JSON：{running, startedAt, queries, blocked, lastDomain}。 */
    public static String getStatus(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            SharedPreferences sp = ctx.getApplicationContext()
                    .getSharedPreferences(PREF, Context.MODE_PRIVATE);
            o.put("running", sLive);
            o.put("startedAt", sp.getLong(K_STARTED, 0L));
            o.put("queries", sp.getLong(K_QUERIES, 0L));
            o.put("blocked", sp.getLong(K_BLOCKED, 0L));
            o.put("lastDomain", sp.getString(K_LAST, ""));
        } catch (Exception e) {
            try { o.put("error", e.getClass().getSimpleName()); } catch (Exception ignored) {}
        }
        return o.toString();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running) return START_STICKY;
        running = true;
        sLive = true;
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putLong(K_STARTED, System.currentTimeMillis()).apply();
        worker = new Thread(this::loop, "dns-vpn");
        worker.start();
        Log.i(TAG, "DNS VPN started");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        sLive = false;
        try { if (tun != null) tun.close(); } catch (Exception ignored) {}
        tun = null;
        if (worker != null) worker.interrupt();
        Log.i(TAG, "DNS VPN stopped");
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        running = false;
        sLive = false;
        try { if (tun != null) tun.close(); } catch (Exception ignored) {}
        tun = null;
        stopSelf();
        super.onRevoke();
    }

    // ================= TUN 主循环 =================

    private void loop() {
        Builder b = new Builder();
        try {
            b.setSession("LDNS");
            b.setMtu(1500);
            b.addAddress("10.0.0.2", 32);
            for (String ip : collectDnsServers()) {
                try { b.addRoute(ip, 32); } catch (Exception ignored) {}
            }
            try { b.addDnsServer("223.5.5.5"); } catch (Exception ignored) {}
            try { b.addDnsServer("119.29.29.29"); } catch (Exception ignored) {}
            try { b.addDisallowedApplication(getPackageName()); } catch (Exception e) {
                Log.w(TAG, "disallow-self failed: " + e.getMessage());
            }
            tun = b.establish();
        } catch (Exception e) {
            Log.e(TAG, "establish failed", e);
            running = false;
            sLive = false;
            stopSelf();
            return;
        }
        if (tun == null) {
            running = false;
            sLive = false;
            stopSelf();
            return;
        }
        FileInputStream in = new FileInputStream(tun.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(tun.getFileDescriptor());
        byte[] buf = new byte[32767];
        while (running) {
            int len;
            try {
                len = in.read(buf);
            } catch (Exception e) {
                break;
            }
            if (len <= 0) continue;
            try {
                byte[] resp = handlePacket(buf, len);
                if (resp != null) out.write(resp);
            } catch (Exception e) {
                Log.w(TAG, "handle: " + e.getMessage());
            }
        }
        try { in.close(); } catch (Exception ignored) {}
        try { out.close(); } catch (Exception ignored) {}
    }

    /** 系统 DNS + 公共 DNS 去重。 */
    private List<String> collectDnsServers() {
        Set<String> set = new LinkedHashSet<String>();
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network net = cm.getActiveNetwork();
                if (net != null) {
                    LinkProperties lp = cm.getLinkProperties(net);
                    if (lp != null) {
                        for (InetAddress a : lp.getDnsServers()) {
                            if (a != null && a.getAddress() != null
                                    && a.getAddress().length == 4) {
                                set.add(a.getHostAddress());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "system dns: " + e.getMessage());
        }
        for (String s : PUBLIC_DNS) set.add(s);
        return new ArrayList<String>(set);
    }

    // ================= 包处理 =================

    /** 只处理 IPv4/UDP/53；返回待写回的完整 IP 包，无需应答返回 null。 */
    private byte[] handlePacket(byte[] b, int len) throws Exception {
        if (len < 28) return null;
        int ver = (b[0] >> 4) & 0xF;
        if (ver != 4) return null;
        int ihl = (b[0] & 0xF) * 4;
        if (ihl < 20 || len < ihl + 8) return null;
        if ((b[9] & 0xFF) != 17) return null; // 只处理 UDP
        int udpOff = ihl;
        int dstPort = ((b[udpOff + 2] & 0xFF) << 8) | (b[udpOff + 3] & 0xFF);
        if (dstPort != 53) return null;
        int dnsOff = udpOff + 8;
        int dnsLen = len - dnsOff;
        if (dnsLen < 17) return null; // 头12 + 至少1字节QNAME + 4
        int qd = ((b[dnsOff + 4] & 0xFF) << 8) | (b[dnsOff + 5] & 0xFF);
        if (qd != 1) return null;
        String domain = parseQname(b, dnsOff + 12, dnsOff + dnsLen);
        if (domain == null || domain.isEmpty()) return null;
        int qEnd = qnameEnd(b, dnsOff + 12, dnsOff + dnsLen);
        if (qEnd < 0 || qEnd + 4 > dnsOff + dnsLen) return null;
        int qtype = ((b[qEnd] & 0xFF) << 8) | (b[qEnd + 1] & 0xFF);
        int tid = ((b[dnsOff] & 0xFF) << 8) | (b[dnsOff + 1] & 0xFF);

        List<String> ips = new ArrayList<String>();
        int rcode = 0;
        boolean blocked = false;
        if (qtype == 1) {
            JSONObject r = DnsShield.queryFast(this, domain);
            rcode = r.optInt("rcode", 2);
            if (r.has("ips")) {
                for (int i = 0; i < r.optJSONArray("ips").length(); i++) {
                    ips.add(r.optJSONArray("ips").optString(i));
                }
            }
            if (r.has("error")) {
                blocked = r.optBoolean("polluted", false);
                rcode = 2; // 全部被污染/失败：SERVFAIL，不给毒答案
                ips.clear();
            }
            if (rcode != 0 && rcode != 3) rcode = 2;
            if (rcode == 0 && ips.isEmpty()) rcode = 3;
        } else {
            rcode = 0; // 非 A 记录：NOERROR 空应答，不拦截
        }
        bumpStats(blocked, domain);
        return buildResponse(b, dnsOff, dnsLen, qEnd, tid, rcode, ips);
    }

    private void bumpStats(boolean blocked, String domain) {
        try {
            SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
            SharedPreferences.Editor ed = sp.edit();
            ed.putLong(K_QUERIES, sp.getLong(K_QUERIES, 0L) + 1);
            if (blocked) ed.putLong(K_BLOCKED, sp.getLong(K_BLOCKED, 0L) + 1);
            if (domain != null) ed.putString(K_LAST, domain);
            ed.apply();
        } catch (Exception ignored) {
        }
    }

    // ================= DNS 编解码 =================

    private static String parseQname(byte[] b, int off, int end) {
        try {
            StringBuilder sb = new StringBuilder();
            int p = off;
            int jumps = 0;
            while (true) {
                if (p >= end) return null;
                int lb = b[p++] & 0xFF;
                if (lb == 0) break;
                if ((lb & 0xC0) != 0) return null;
                if (lb > 63 || p + lb > end) return null;
                if (sb.length() > 0) sb.append('.');
                sb.append(new String(b, p, lb, StandardCharsets.US_ASCII));
                p += lb;
                if (++jumps > 16) return null;
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static int qnameEnd(byte[] b, int off, int end) {
        int p = off;
        int jumps = 0;
        while (true) {
            if (p >= end || ++jumps > 16) return -1;
            int lb = b[p++] & 0xFF;
            if (lb == 0) return p;
            if ((lb & 0xC0) != 0) return -1;
            if (lb > 63 || p + lb > end) return -1;
            p += lb;
        }
    }

    private static void w16(ByteArrayOutputStream bos, int v) {
        bos.write((v >> 8) & 0xFF);
        bos.write(v & 0xFF);
    }

    private static void w32(ByteArrayOutputStream bos, long v) {
        bos.write((int) ((v >> 24) & 0xFF));
        bos.write((int) ((v >> 16) & 0xFF));
        bos.write((int) ((v >> 8) & 0xFF));
        bos.write((int) (v & 0xFF));
    }

    private static byte[] ip4(String s) {
        String[] p = s.split("\\.");
        byte[] r = new byte[4];
        for (int i = 0; i < 4; i++) r[i] = (byte) (Integer.parseInt(p[i]) & 0xFF);
        return r;
    }

    /** 组装完整 IPv4+UDP+DNS 应答包。 */
    private static byte[] buildResponse(byte[] req, int dnsOff, int dnsLen,
                                        int qEnd, int tid, int rcode,
                                        List<String> ips) throws Exception {
        ByteArrayOutputStream dns = new ByteArrayOutputStream(64);
        w16(dns, tid);
        w16(dns, (rcode == 0 || rcode == 3) ? (0x8180 | rcode) : 0x8182);
        w16(dns, 1);
        int an = (rcode == 0) ? ips.size() : 0;
        w16(dns, an);
        w16(dns, 0);
        w16(dns, 0);
        dns.write(req, dnsOff + 12, (qEnd + 4) - (dnsOff + 12)); // 问题段原样回显
        for (String ip : ips) {
            dns.write(0xC0);
            dns.write(0x0C);
            w16(dns, 1);
            w16(dns, 1);
            w32(dns, 60); // TTL 60s
            w16(dns, 4);
            dns.write(ip4(ip), 0, 4);
        }
        byte[] dnsBytes = dns.toByteArray();

        int ihl = (req[0] & 0xF) * 4;
        byte[] pkt = new byte[20 + 8 + dnsBytes.length];
        // IP 头
        pkt[0] = 0x45;
        pkt[1] = 0;
        int total = pkt.length;
        pkt[2] = (byte) ((total >> 8) & 0xFF);
        pkt[3] = (byte) (total & 0xFF);
        pkt[4] = req[4]; // 沿用请求 IP 标识
        pkt[5] = req[5];
        pkt[6] = 0x40; // DF
        pkt[7] = 0;
        pkt[8] = 64; // TTL
        pkt[9] = 17; // UDP
        // src/dst 互换：应答源 = 请求目的（被查询的 DNS），应答目的 = 请求源
        System.arraycopy(req, ihl + 16, pkt, 12, 4);
        System.arraycopy(req, ihl + 12, pkt, 16, 4);
        pkt[10] = 0;
        pkt[11] = 0;
        int c = ipChecksum(pkt, 0, 20);
        pkt[10] = (byte) ((c >> 8) & 0xFF);
        pkt[11] = (byte) (c & 0xFF);
        // UDP 头（源=53，目的=请求源端口）
        int srcPortReq = ((req[ihl] & 0xFF) << 8) | (req[ihl + 1] & 0xFF);
        pkt[20] = 0;
        pkt[21] = 53;
        pkt[22] = (byte) ((srcPortReq >> 8) & 0xFF);
        pkt[23] = (byte) (srcPortReq & 0xFF);
        int ulen = 8 + dnsBytes.length;
        pkt[24] = (byte) ((ulen >> 8) & 0xFF);
        pkt[25] = (byte) (ulen & 0xFF);
        pkt[26] = 0;
        pkt[27] = 0;
        System.arraycopy(dnsBytes, 0, pkt, 28, dnsBytes.length);
        int u = udpChecksum(pkt, 12, 16, pkt, 20, ulen);
        if (u == 0) u = 0xFFFF;
        pkt[26] = (byte) ((u >> 8) & 0xFF);
        pkt[27] = (byte) (u & 0xFF);
        return pkt;
    }

    private static int ipChecksum(byte[] b, int off, int len) {
        long sum = 0;
        for (int i = 0; i < len; i += 2) {
            int hi = b[off + i] & 0xFF;
            int lo = (i + 1 < len) ? (b[off + i + 1] & 0xFF) : 0;
            sum += (hi << 8) | lo;
        }
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum) & 0xFFFF;
    }

    private static int udpChecksum(byte[] srcIp4, int srcOff, int dstOff,
                                   byte[] udp, int udpOff, int udpLen) {
        long sum = 0;
        sum += ((srcIp4[srcOff] & 0xFF) << 8) | (srcIp4[srcOff + 1] & 0xFF);
        sum += ((srcIp4[srcOff + 2] & 0xFF) << 8) | (srcIp4[srcOff + 3] & 0xFF);
        sum += ((srcIp4[dstOff] & 0xFF) << 8) | (srcIp4[dstOff + 1] & 0xFF);
        sum += ((srcIp4[dstOff + 2] & 0xFF) << 8) | (srcIp4[dstOff + 3] & 0xFF);
        sum += 17; // UDP
        sum += udpLen;
        for (int i = 0; i < udpLen; i += 2) {
            int hi = udp[udpOff + i] & 0xFF;
            int lo = (i + 1 < udpLen) ? (udp[udpOff + i + 1] & 0xFF) : 0;
            sum += (hi << 8) | lo;
        }
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum) & 0xFFFF;
    }
}
