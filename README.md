# 量盾手机版（liangdun-phone）

> 本产品基于 [游龙安全护盾](https://github.com/iill392/youlong-security) 进行二次开发。
> 本产品已开源，协议与游龙安全护盾相同（AGPL-3.0）。

免 Root 的 Android 安全防护工具：DNS 防污染全局接管 + 病毒扫描（自研引擎 + 开放病毒库）
+ YARA 规则扫描 + 浏览器指纹伪装。混合架构单包 APK：WebView 单页前端 + 原生 Java/Kotlin + NDK/C++，
源码级内置开源特权内核 [Stellar](https://github.com/roro2239/Stellar)。

## 下载

- 到 [Releases](../../releases) 下载 `*.apk`（debug 包，如 `app-debug-20261007-155145.apk`，约 72MB），
  用文件管理器安装；打不开请换管理器或 `adb install`。
- 包信息：`com.youlong.hd` / versionName 9.1.1 / 全语言应用名「量盾」/ 桌面图标可进 MainActivity。

## 本版新增（相对上游）

1. **DNS 防污染全局接管 VPN**（`app/src/main/java/com/youlong/hd/DnsVpnService.java` + `DnsShield.java`）
   - DNS-only VPN：TUN `10.0.0.2` MTU1500，只路由系统 DNS + 12 个公共 DNS /32，只解析 IPv4/UDP 53；
   - 查询走 `DnsShield.queryFast`（DoH 加密优先：阿里 223.5.5.5 / 腾讯 doh.pub / 114 / Cloudflare / Quad9 /
     Google 等 8 端点，PLAIN UDP53 仅兜底），污染检测（0.0.0.0 / NXDOMAIN 异常 / 多路不一致）自动切通道；
   - `addDisallowedApplication` 防回流；DNS 页有「全局接管VPN」开关，开走系统 VPN 授权弹框，
     状态 `{running, startedAt, queries, blocked, lastDomain}`。
   - 局限：首版只接管 UDP53/IPv4 明文 DNS，TCP53/DoT inside tunnel 未做，其它流量直接放行。
2. **病毒扫描收紧**（`LdVirusEngine.java`：VirusDb → MD5 `assets/md5sums.txt` → 启发式）
   - `SCORE_MALICIOUS 4→6`，新增 `SCORE_SUSPECT=3`；系统应用跳过启发式；
   - 白名单前缀（com.google./com.android./miui/xiaomi/huawei/honor/samsung/oppo/oplus/vivo/oneplus/meizu/lenovo/zte/腾讯系/阿里系/百度/京东/com.youlong.hd）只报高危；
     可疑须同时 `score>=3 && hits>=2`；boot+悬浮窗 2 分→1 分。
3. **浏览器指纹伪装**（`FpShield.java`，仅应用内 WebView，不碰系统浏览器）
   - 固定预设 iOS 17 + Chrome 123（UA iPhone CriOS/123.0.6312.52），`applyTo()` 换 UA +
     `onPageStarted` 注入覆盖 `userAgent/appVersion/platform/vendor/hardwareConcurrency/deviceMemory`；
     高级设置开关默认开，持久化 `fp_shield_prefs.enabled`。
4. **YARA 扫描**（`LdYaraEngine.java` + `assets/yara_ld10.yar`，LD10-v1，10 条规则）。
5. **桌面入口 + 改名 + 底部声明**：MainActivity 补 LAUNCHER，应用名「量盾」，首页底部常驻二改/AGPL-3.0 声明。

## 编译

必需：JDK 21 / Gradle 8.14 / Android SDK（platform android-37 系列 + build-tools 37.0.0 +
NDK 29.0.13113456 + cmake 3.22.1）。本工程构建输出目录为 `build_out/`（见根 `build.gradle` 自定义 buildDir），
dex 采用 STORED，release 默认未签名。

```powershell
$env:JAVA_HOME='D:\jdk21'
$env:ANDROID_HOME='D:\Android\sdk'
$env:ANDROID_SDK_ROOT='D:\Android\sdk'
D:\gradle\gradle-8.14\bin\gradle.bat :app:assembleDebug --console=plain
# 产物：liangdun-mobile/build_out/_app/outputs/apk/debug/app-debug-*.apk
```

注意：仓库根在 ASCII 路径下编译（AIDL GBK 问题），不要放到含中文的目录。

## 目录

- `app/src/main/java/com/youlong/hd/DnsShield.java` — DoH/PLAIN 两档查询 + 污染检测
- `app/src/main/java/com/youlong/hd/DnsVpnService.java` — DNS-only 全局接管 VPN
- `app/src/main/java/com/youlong/hd/LdVirusEngine.java` — 病毒引擎（白名单+收紧阈值）
- `app/src/main/java/com/youlong/hd/LdYaraEngine.java` — YARA 扫描
- `app/src/main/java/com/youlong/hd/FpShield.java` — 指纹伪装
- `app/src/main/assets/index.html` — 四页前端（首页/病毒库/安全扫描/DNS防护）+ 高级设置
- `app/src/main/assets/md5sums.txt` / `yara_ld10.yar` — 本地病毒库 / YARA 规则

## 协议与署名

- 本仓库 License：**AGPL-3.0**（见 `LICENSE`，与上游相同）。
- 上游：游龙安全护盾（AGPL-3.0）+ 内置 Stellar / Shizuku / AOSP adb / BoringSSL 等，开源文本见
  `app/src/main/res/raw/open_source_licenses.txt` 与应用内「高级设置 → 开源许可」入口。
- 二改修改点见首页底部声明与本 README「本版新增」。
