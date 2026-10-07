// ==========================================================================
// 游龙安全护盾 —— 新增文件（非上游 Stellar 文件）
// --------------------------------------------------------------------------
// 用途：对「已授权应用」通过内置 Stellar 下发的 shell 命令做安全拦截与命令分析。
//
// 背景：内置 Stellar 是自包含的特权内核，本应用自己就是管理器。但一旦某个
// 第三方应用获得了 stellar 权限，它就能用 Stellar 的 shell 身份执行任意命令，
// 包括卸载/冻结/批量停止其它应用，甚至针对本产品自身。本文件把这类危险命令
// 拦在「真正 fork 进程」之前，位于 ProcessManager.newProcess() 这个唯一汇聚点。
//
// 与 MPL-2.0 的关系：本文件是**本工程新增**，不是上游派生文件；
// Stellar 侧被改动的文件（ProcessManager.kt）均已按 MPL-2.0 第 3.4 条
// 在文件内标注修改声明。
//
// ==========================================================================
// 判定总览（按优先级，先命中先返回）
// --------------------------------------------------------------------------
//   规则 1  针对自家应用（com.youlong.hd / com.youlong.tool）  → 直接拒绝 + 弹窗告警
//   规则 2  批量停止所有应用（am kill-all / pkill / killall /
//           枚举包后循环 force-stop）                          → 直接拒绝 + 弹窗告警
//   规则 3  卸载 / 冻结 / 清数据                              → 询问用户
//   规则 4  设备管理员 / 用户 / 设备策略（dpm 等）             → 询问用户
//
//   规则 3 命中后会再做一层**命令分析**，把风险类型标出来，供弹窗说清危害：
//     · admin  动设备管理员 / 策略（最敏感）
//     · system 目标是系统应用（设置、系统界面、包安装器…）——
//              冻结/卸载它们会让系统不可用（设置进不去、装不了应用）
//     · bulk   一条命令里批量动 3 个以上包（典型「一键卸载/冻结全部应用」）
//     · normal 普通卸载 / 冻结
//
//   注意：管理器自身（com.youlong.hd）不受这些规则约束 —— 它执行的就是本产品
//   的正常功能（日常模式批量卸载、病毒处置等），拦它等于把功能拦死。
// ==========================================================================

package roro.stellar.server.service.process

import roro.stellar.server.util.Logger

/**
 * 命令拦截结论。
 */
sealed class InterceptDecision {
    /** 放行。 */
    object Allow : InterceptDecision()

    /** 直接拒绝，不执行；并向用户弹窗告知「某软件尝试某操作已被拦截」。 */
    data class Block(val reason: String) : InterceptDecision()

    /**
     * 危险操作，需要用户在管理器界面确认后才执行。
     *
     * @param reason 一句人话说明这次要做什么（如「卸载应用（com.x.y）」）
     * @param kind   风险类型，用于弹窗给出针对性说明：
     *              system=动系统应用、bulk=批量操作、admin=设备管理员/策略、
     *              normal=普通卸载/冻结
     */
    data class NeedConfirm(val reason: String, val kind: String = "normal") : InterceptDecision()
}

/**
 * 命令安全拦截器 + 命令分析。
 */
object CommandInterceptor {

    private val LOGGER = Logger("CommandInterceptor")

    /** 本产品自身与配套应用；任何针对它们的危险命令都被拒绝。 */
    private val SELF_PACKAGES = listOf(
        "com.youlong.hd",    // 游龙安全护盾（本应用）
        "com.youlong.tool"   // 游龙工具（配套）
    )

    /**
     * 「批量停止所有应用」的特征。
     * 只匹配**全局性**停止动作；针对单个包名的 force-stop/kill 不在其列。
     */
    private val STOP_ALL_PATTERNS = listOf(
        Regex("""\bam\s+kill-all\b"""),
        Regex("""\bpm\s+disable-user\s+--user\s+\d+\s+-a\b"""),
        Regex("""\bpkill\b"""),
        Regex("""\bkillall\b"""),
        // am force-stop 后面跟通配符（部分 ROM 支持）
        Regex("""\bam\s+force-stop\s+["']?\*"""),
        // 对「所有包」的操作：pm uninstall/disable/force-stop 后直接跟 -a / --all
        Regex("""\bpm\s+(uninstall|disable-user|disable|force-stop|suspend)\b[^\n|;&]*\s(-a|--all)\b""")
    )

    /**
     * 「先枚举包、再批量操作」的组合特征：
     * 命令里同时出现「枚举第三方包」和「循环停止」两类元素时，视为批量停止。
     */
    private val ENUMERATE_PACKAGES = Regex("""\bpm\s+list\s+packages\b""")
    private val LOOP_STOP = Regex("""\b(am\s+force-stop|pm\s+disable-user|pm\s+disable|pm\s+suspend|pm\s+hide)\b""")

    /**
     * 系统关键组件（包名）。命中即视为高风险，弹窗里必须明确提示。
     *
     * 这里只列**最容易被恶意软件拿来搞破坏**的那些：设置、系统界面、包安装器、
     * 电话短信、权限控制器等。冻结/卸载它们会导致系统不可用 ——
     * 设置进不去、装不了应用、连病毒都卸不掉。
     */
    private val CRITICAL_SYSTEM_PACKAGES = listOf(
        "com.android.settings",
        "com.android.systemui",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.providers.settings",
        "com.android.shell",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.providers.telephony",
        "com.android.mms",
        "com.android.providers.contacts",
        "com.android.providers.media",
        "com.android.documentsui",
        "com.android.vending",
        "com.android.launcher3",
        "com.android.bluetooth",
        "com.android.nfc",
        "com.android.wifi",
        "com.android.se",
        "com.android.networkstack",
        "com.android.inputmethod.latin",
        "com.android.keychain"
    )

    /**
     * 系统应用的**通用**包名前缀（厂商框架），用于兜底识别非关键系统应用。
     */
    private val SYSTEM_PACKAGE_PATTERN = Regex(
        "^(android|com\\.android|com\\.google\\.android|com\\.qualcomm|" +
            "com\\.mediatek|com\\.miui|com\\.xiaomi|com\\.huawei|com\\.honor|" +
            "com\\.oppo|com\\.coloros|com\\.vivo|com\\.bbk|com\\.oneplus|" +
            "com\\.oplus|com\\.realme|com\\.meizu|com\\.smartisan|com\\.sony|" +
            "com\\.samsung|com\\.sec|com\\.lg|com\\.htc|com\\.motorola|" +
            "com\\.lenovo|com\\.asus|com\\.nokia|com\\.zte)\\."
    )

    /** 设备管理员 / 用户 / 设备策略类命令：非常敏感，必须确认。 */
    private val ADMIN_PATTERNS = listOf(
        Regex("""\bdpm\b"""),
        Regex("""\bpm\s+set-user-restriction\b"""),
        Regex("""\bpm\s+remove-user\b"""),
        Regex("""\bpm\s+create-user\b"""),
        Regex("""\bam\s+set-user-restriction\b"""),
        Regex("""\bcmd\s+device_policy\b"""),
        Regex("""\bcpm\s+(install|remove)\b""")
    )

    /** 卸载 / 冻结 / 清数据：需要用户确认。 */
    private val NEED_CONFIRM_PATTERNS = listOf(
        Regex("""\bpm\s+uninstall\b"""),
        Regex("""\bpm\s+disable-user\b"""),
        Regex("""\bpm\s+disable\b"""),
        Regex("""\bpm\s+suspend\b"""),
        Regex("""\bpm\s+hide\b"""),
        Regex("""\bpm\s+clear\b"""),
        Regex("""\bpm\s+set-user-restriction\b"""),
        Regex("""\bcmd\s+package\s+(uninstall|suspend|disable)\b""")
    )

    /**
     * 对一条即将执行的命令做判定。
     *
     * @param cmd           原始命令数组（如 ["sh","-c","pm uninstall xxx"]）
     * @param callerPackage 调用方包名（由 uid 反查得到）
     * @param isManager     调用方是否就是管理器（本应用自己）
     */
    fun inspect(cmd: Array<String?>, callerPackage: String?, isManager: Boolean): InterceptDecision {
        // 管理器自己（本应用）不受拦截约束 —— 它执行的就是本产品的正常功能
        if (isManager) return InterceptDecision.Allow

        val flat = flatten(cmd)
        if (flat.isBlank()) return InterceptDecision.Allow

        val who = callerPackage ?: "未知应用"

        // ---- 规则 1：保护自家应用 ----------------------------------------
        // 只要命令里出现自家包名，无论什么动作都拒绝。
        // 例外：纯只读查询不拦 —— 否则连「列出已安装应用」都会被挡住。
        if (SELF_PACKAGES.any { flat.contains(it) }) {
            if (!isReadOnly(flat)) {
                LOGGER.w("拦截针对自家应用的命令: caller=%s, cmd=%s", who, flat)
                return InterceptDecision.Block("尝试操作本应用或配套应用")
            }
        }

        // ---- 规则 2：批量停止所有应用 ------------------------------------
        if (STOP_ALL_PATTERNS.any { it.containsMatchIn(flat) }
            || (ENUMERATE_PACKAGES.containsMatchIn(flat) && LOOP_STOP.containsMatchIn(flat))
        ) {
            LOGGER.w("拦截批量停止命令: caller=%s, cmd=%s", who, flat)
            return InterceptDecision.Block("尝试停止所有应用")
        }

        // ---- 规则 3：卸载 / 冻结 / 清数据 → 需要用户确认 ------------------
        if (NEED_CONFIRM_PATTERNS.any { it.containsMatchIn(flat) }) {
            // 2026-10 增强：再做一层命令分析，把风险类型标出来，
            // 让弹窗能说清"这次到底危险在哪"。
            val kind = analyzeRiskKind(flat)
            LOGGER.w("卸载/冻结命令需用户确认: caller=%s, kind=%s, cmd=%s", who, kind, flat)
            return InterceptDecision.NeedConfirm(describeDanger(flat), kind)
        }

        // ---- 规则 4：设备管理员 / 用户 / 策略类 → 需要用户确认 ------------
        // 不一定伴随卸载（例如只调 dpm 摘管理员），单独再兜一层。
        if (ADMIN_PATTERNS.any { it.containsMatchIn(flat) }) {
            LOGGER.w("设备管理员/策略类命令需用户确认: caller=%s, cmd=%s", who, flat)
            return InterceptDecision.NeedConfirm(describeDanger(flat), "admin")
        }

        return InterceptDecision.Allow
    }

    // ------------------------------------------------------------------
    // 命令分析
    // ------------------------------------------------------------------

    /**
     * 分析这条命令属于哪种风险类型。
     * 优先级：admin > system > bulk > normal。
     */
    private fun analyzeRiskKind(flat: String): String {
        if (ADMIN_PATTERNS.any { it.containsMatchIn(flat) }) return "admin"
        if (mentionsSystemPackage(flat)) return "system"
        if (affectedPackageCount(flat) >= 3) return "bulk"
        return "normal"
    }

    /** 命令里是否点到了系统应用（关键组件清单 + 厂商系统包名前缀） */
    private fun mentionsSystemPackage(flat: String): Boolean {
        if (CRITICAL_SYSTEM_PACKAGES.any { flat.contains(it) }) return true
        return extractPackages(flat).any { SYSTEM_PACKAGE_PATTERN.containsMatchIn(it) }
    }

    /**
     * 这条命令大概会影响多少个包。
     * 「一键卸载/冻结全部应用」这类命令通常会在一条命令里串起很多包名。
     */
    private fun affectedPackageCount(flat: String): Int = extractPackages(flat).size

    // ------------------------------------------------------------------
    // 通用工具
    // ------------------------------------------------------------------

    /**
     * 把命令数组压平成一个字符串用于匹配。
     *
     * 调用方通常传 ["sh","-c","<真实脚本>"]，也可能直接传 ["pm","uninstall","x"]。
     * 统一用空格连接即可覆盖这几种形态；多余空格会被归一化，
     * 避免「pm   空格   uninstall」这种绕过。
     */
    private fun flatten(cmd: Array<String?>): String {
        val joined = cmd.filterNotNull().joinToString(" ")
        return joined.replace(Regex("""\s+"""), " ").trim()
    }

    /**
     * 是否是只读查询命令。
     *
     * 只读命令允许包含自家包名：例如授权应用做「已安装应用清单」时
     * 会执行 pm list packages，或 dumpsys package com.youlong.hd。
     * 这些不会改变任何状态，拦下来只会误伤正常功能。
     * 只要出现任何写操作特征，就不算只读。
     */
    private fun isReadOnly(flat: String): Boolean {
        val writeOps = Regex(
            """\b(uninstall|disable-user|disable|suspend|hide|clear|force-stop|kill|kill-all|pkill|killall|rm|mv|chmod|chown|setprop|svc|reboot|grant|revoke)\b"""
        )
        if (writeOps.containsMatchIn(flat)) return false
        val readOps = Regex("""\b(pm\s+list|pm\s+path|dumpsys|ps|pidof|getprop|ls|cat|which|id|echo|grep)\b""")
        return readOps.containsMatchIn(flat)
    }

    /**
     * 为用户确认弹窗生成一句人话说明：这次到底是什么危险动作、针对什么。
     */
    private fun describeDanger(flat: String): String {
        val action = when {
            Regex("""\bpm\s+uninstall\b""").containsMatchIn(flat) -> "卸载应用"
            Regex("""\bpm\s+suspend\b""").containsMatchIn(flat) -> "冻结应用"
            Regex("""\bpm\s+hide\b""").containsMatchIn(flat) -> "隐藏应用"
            Regex("""\bpm\s+clear\b""").containsMatchIn(flat) -> "清除应用数据"
            Regex("""\bpm\s+disable""").containsMatchIn(flat) -> "停用应用"
            Regex("""\bdpm\b""").containsMatchIn(flat) -> "修改设备管理员"
            Regex("""\bpm\s+set-user-restriction\b""").containsMatchIn(flat) -> "修改用户限制策略"
            else -> "执行危险操作"
        }
        val targets = extractPackages(flat)
        return if (targets.isEmpty()) action else "$action（${targets.joinToString("、")}）"
    }

    /**
     * 从命令里粗略提取被操作的包名，仅用于弹窗展示与风险统计（不参与"是否拦"的判定）。
     */
    private fun extractPackages(flat: String): List<String> {
        val pkgRegex = Regex("""\b([a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+){2,})\b""")
        return pkgRegex.findAll(flat)
            .map { it.groupValues[1] }
            .filter {
                !it.startsWith("android.") &&
                    !it.startsWith("java.") &&
                    !it.startsWith("com.android.internal")
            }
            .distinct()
            .take(8)
            .toList()
    }
}
