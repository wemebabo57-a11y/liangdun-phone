// ==========================================================================
// 说明：本文件是**本工程新增**（不是上游 Stellar 文件），因此不受 MPL-2.0
//       第 3.4 条「被修改文件需带修改声明」的约束；此处仅作来源与合规说明。
//
// 新增内容：命令安全拦截的弹窗投递与用户答复等待（详见文件内注释）。
// 合规相关：它属于「与 MPL 代码并置的独立文件」（MPL-2.0 第 3.3 条 Larger Work），
//           本产品以 Apache-2.0 发布本文件；它调用 MPL 覆盖的 Stellar 服务端代码
//           仅通过既有内部 API，未复制或修改 MPL 代码本身。
// ==========================================================================

// ==========================================================================
// 游龙安全护盾 —— 新增文件（非上游 Stellar 文件）
// --------------------------------------------------------------------------
// 用途：把「命令被拦截 / 需要用户确认」这件事变成用户看得见的一次弹窗，
//       并在需要确认时把用户在弹窗上的选择回传给正在等待的服务端线程。
//
// 通信方式（服务端 <-> 管理器）：
//   服务端（uid 2000 shell）把请求写进 /data/local/tmp/stellar_guard/，
//   再把该文件路径通过 Intent 交给管理器侧的 CommandGuardActivity；
//   管理器读完请求后，把 "ALLOW" / "DENY" / "ACKNOWLEDGED" 写回同目录的
//   同名 .resp 文件；服务端轮询该文件拿到答复。
//
//   为什么不走 Binder：Stellar 的 AIDL 接口是上游定义的，新增一个「回传确认
//   结果」的方法要改 AIDL 与协议（并影响兼容性）；用文件握手完全不触碰上游
//   接口，且 /data/local/tmp 对服务端(shell)始终可写。
//
// ==========================================================================

package roro.stellar.server.service.process

import android.content.Intent
import rikka.hidden.compat.ActivityManagerApis
import roro.stellar.server.ServerConstants
import roro.stellar.server.util.Logger
import java.io.File
import java.util.concurrent.atomic.AtomicLong

object CommandGuard {

    private val LOGGER = Logger("CommandGuard")

    /** 握手目录。shell 身份可读写；管理器侧用自己的 shell 权限写入答复。 */
    private val DIR: File = File("/data/local/tmp/stellar_guard")

    /** 用户确认的最长等待时间：超时即视为拒绝（安全默认）。 */
    private const val CONFIRM_TIMEOUT_MS = 30_000L

    /** 轮询间隔。 */
    private const val POLL_INTERVAL_MS = 150L

    /** 请求文件里「命令」字段的长度上限，避免超长脚本把文件撑爆。 */
    private const val MAX_CMD_LEN = 4000

    private val SEQ = AtomicLong(0)

    const val KIND_ALERT = "block"
    const val KIND_CONFIRM = "confirm"

    const val REPLY_ALLOW = "ALLOW"
    const val REPLY_DENY = "DENY"
    const val REPLY_ACK = "ACKNOWLEDGED"

    /**
     * 弹一个「已被拦截」告警框。不等待用户操作（命令已经被拒绝了，
     * 用户点不点「好的」都不影响结果）。
     */
    fun showBlockedAlert(
        packageName: String?,
        command: String,
        reason: String,
        userId: Int,
        // 2026-10 新增：命令分析得出的风险类型（system / bulk / admin / normal）
        riskKind: String = "normal"
    ) {
        val id = nextId()
        val request = writeRequest(id, KIND_ALERT, packageName, command, reason, riskKind)
        if (request == null) {
            LOGGER.w("告警框请求文件写入失败，跳过弹窗")
            return
        }
        if (!launch(id, KIND_ALERT, packageName, command, reason, request, userId)) {
            LOGGER.w("告警框拉起失败")
        }
    }

    /**
     * 弹一个「是否允许」确认框，并阻塞等待用户选择。
     *
     * <p>调用方是 binder 线程；等待期间该线程被占用，这是有意为之——
     * 命令必须等用户答复后才能决定是否执行。
     *
     * @return true 表示用户允许；超时、异常、管理器未响应一律返回 false。
     */
    fun askForConfirmation(
        packageName: String?,
        command: String,
        reason: String,
        userId: Int,
        // 2026-10 新增：命令分析得出的风险类型（system / bulk / admin / normal）
        riskKind: String = "normal"
    ): Boolean {
        val id = nextId()
        val request = writeRequest(id, KIND_CONFIRM, packageName, command, reason, riskKind)
        if (request == null) {
            LOGGER.w("确认框请求文件写入失败，按拒绝处理")
            return false
        }
        if (!launch(id, KIND_CONFIRM, packageName, command, reason, request, userId, riskKind)) {
            LOGGER.w("确认框拉起失败，按拒绝处理")
            cleanup(id)
            return false
        }

        val deadline = System.currentTimeMillis() + CONFIRM_TIMEOUT_MS
        val response = File(DIR, "$id.resp")
        while (System.currentTimeMillis() < deadline) {
            try {
                if (response.isFile) {
                    val text = runCatching { response.readText() }.getOrDefault("")
                    val allow = text.trim().uppercase().startsWith(REPLY_ALLOW)
                    LOGGER.i("用户%s了对 %s 的命令", if (allow) "允许" else "拒绝", packageName)
                    return allow
                }
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                LOGGER.w("等待用户确认被中断，按拒绝处理")
                return false
            }
        }
        LOGGER.w("等待用户确认超时（%d ms），按拒绝处理", CONFIRM_TIMEOUT_MS)
        cleanup(id)
        return false
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private fun nextId(): Long {
        // 时间戳 + 进程内自增，保证同一毫秒内多次请求也互不覆盖。
        // 用字符串拼接而不是位运算，避免 "shl 10" 触发 Long 溢出告警。
        val seq = SEQ.incrementAndGet()
        return "${System.currentTimeMillis()}$seq".toLong()
    }

    /**
     * 把请求写成 key=value 文本文件。命令原文可能含换行，统一把换行替换成
     * " ⏎ " 以便单行存放（只影响展示，不影响判定——判定早已完成）。
     */
    private fun writeRequest(
        id: Long,
        kind: String,
        packageName: String?,
        command: String,
        reason: String,
        // 2026-10 新增：风险类型，写进请求文件供弹窗使用
        riskKind: String = "normal"
    ): String? {
        return try {
            if (!DIR.exists() && !DIR.mkdirs()) {
                LOGGER.w("无法创建握手目录 %s", DIR.absolutePath)
                return null
            }
            // 收紧目录权限：0711 = 属主(shell)可读写、其它身份只能穿过不能列目录。
            // /data/local/tmp 本身也是 0711，普通应用连目录项都看不到，
            // 因此第三方应用无法读取请求内容或伪造答复文件。
            runCatching {
                Runtime.getRuntime().exec(arrayOf("chmod", "0711", DIR.absolutePath)).waitFor()
            }.onFailure { LOGGER.w(it, "设置握手目录权限失败（不影响功能）") }
            val req = File(DIR, "$id.req")
            val body = buildString {
                append("id=").append(id).append('\n')
                append("kind=").append(kind).append('\n')
                append("package=").append(packageName ?: "").append('\n')
                append("reason=").append(reason.replace('\n', ' ')).append('\n')
                // 2026-10 新增：命令分析得出的风险类型，供弹窗给出针对性说明
                append("riskKind=").append(riskKind).append('\n')
                append("command=")
                    .append(
                        command.take(MAX_CMD_LEN).replace('\n', ' ').replace('\r', ' ')
                    )
                    .append('\n')
            }
            req.writeText(body)
            // 清掉可能残留的同名答复（极小概率的 id 复用）
            File(DIR, "$id.resp").delete()
            req.absolutePath
        } catch (t: Throwable) {
            LOGGER.w(t, "写请求文件失败")
            null
        }
    }

    private fun launch(
        id: Long,
        kind: String,
        packageName: String?,
        command: String,
        reason: String,
        requestPath: String,
        userId: Int,
        // 2026-10 新增：风险类型，放进 Intent 给弹窗
        riskKind: String = "normal"
    ): Boolean {
        return try {
            val intent = Intent(ServerConstants.COMMAND_GUARD_ACTION)
                .setPackage(ServerConstants.MANAGER_APPLICATION_ID)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                .putExtra("id", id)
                .putExtra("kind", kind)
                .putExtra("packageName", packageName)
                .putExtra("command", command.take(MAX_CMD_LEN))
                .putExtra("reason", reason)
                .putExtra("requestPath", requestPath)
                // 2026-10 新增：风险类型，弹窗据此给出针对性说明
                .putExtra("riskKind", riskKind)
            ActivityManagerApis.startActivityNoThrow(intent, null, userId)
            true
        } catch (t: Throwable) {
            LOGGER.w(t, "拉起命令拦截对话框失败")
            false
        }
    }

    private fun cleanup(id: Long) {
        runCatching { File(DIR, "$id.req").delete() }
        runCatching { File(DIR, "$id.resp").delete() }
    }

    /**
     * 管理器侧写答复用的目录路径（供管理器代码引用，保证两端一致）。
     */
    fun handshakeDirPath(): String = DIR.absolutePath
}
