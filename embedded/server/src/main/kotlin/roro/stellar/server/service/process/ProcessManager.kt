// ==========================================================================
// 本文件来自 https://github.com/roro2239/Stellar 的
//   server/src/main/kotlin/roro/stellar/server/service/process/ProcessManager.kt
// （MPL-2.0；其中源自 Shizuku 的部分为 Apache-2.0）
//
// 【本工程修改声明】—— 按 MPL-2.0 第 3.4 条标注
//   改动：newProcess() 与 newPtyProcess() 在真正 fork 进程之前，新增调用
//         CommandGuard.guard(...) 做一次命令安全拦截。
//   原因：内置 Stellar 让本应用自己就是管理器，但任何获得 stellar 权限的第三方
//         应用都能借 Stellar 的 shell 身份执行任意命令，包括卸载/冻结/批量停止
//         其它应用，甚至针对本产品自身。这两个方法是「一切远程命令执行」的唯一
//         汇聚点（Stellar API 与 Shizuku 兼容层都汇聚到这里），因此拦截放在这里
//         最完整、也最难绕过。
//   新增逻辑：
//     · 调用方是管理器自身（com.youlong.hd）→ 完全放行，本应用功能不受影响；
//     · 命令针对 com.youlong.tool / com.youlong.hd → 直接拒绝并弹窗告警；
//     · 命令属于「停止所有应用」→ 直接拒绝并弹窗告警；
//     · 命令属于「卸载 / 冻结 / 清数据」→ 弹窗询问用户，用户拒绝或超时即拒绝。
//   规则实现见本包新增文件 CommandInterceptor.kt（本工程新增，非上游文件）。
// ==========================================================================

package roro.stellar.server.service.process

import com.stellar.server.IRemoteProcess
import com.stellar.server.IRemotePtyProcess
import rikka.hidden.compat.PackageManagerApis
import rikka.rish.RishConfig
import rikka.rish.RishConstants
import rikka.rish.RishHost
import roro.stellar.server.ServerConstants.MANAGER_APPLICATION_ID
import roro.stellar.server.bootstrap.ServerBootstrap
import roro.stellar.server.ClientManager
import roro.stellar.server.api.RemoteProcessHolder
import roro.stellar.server.api.RemotePtyProcessHolder
import roro.stellar.server.shizuku.ShizukuApiConstants
import roro.stellar.server.util.Logger
import java.io.File
import java.io.IOException

class ProcessManager(
    private val clientManager: ClientManager
) {
    companion object {
        private val LOGGER = Logger("ProcessManager")
    }

    fun newProcess(
        uid: Int,
        pid: Int,
        cmd: Array<String?>,
        env: Array<String?>?,
        dir: String?
    ): IRemoteProcess {
        LOGGER.d(
            "newProcess: uid=$uid, cmd=${cmd.contentToString()}, env=${env.contentToString()}, dir=$dir"
        )

        // ⚠️ 本工程新增：命令安全拦截（见文件头部修改声明）。
        guard(uid, pid, cmd)

        val process: Process = try {
            Runtime.getRuntime().exec(cmd, env, if (dir != null) File(dir) else null)
        } catch (e: IOException) {
            throw IllegalStateException(e.message)
        }

        val clientRecord = clientManager.findClient(uid, pid)
        val token = clientRecord?.client?.asBinder()

        return RemoteProcessHolder(process, token)
    }

    fun newPtyProcess(
        uid: Int,
        pid: Int,
        cmd: Array<String?>,
        env: Array<String?>?,
        dir: String?
    ): IRemotePtyProcess {
        // ⚠️ 本工程新增：命令安全拦截。PTY 通道同样是完整的 shell，必须一并把关，
        //    否则 rish 之类的调用可以绕过 newProcess 的检查。
        guard(uid, pid, cmd)

        ServerBootstrap.managerApplicationInfo?.nativeLibraryDir?.let {
            RishConfig.setLibraryPath(it)
        }
        RishConfig.init(ShizukuApiConstants.BINDER_DESCRIPTOR, 30000)
        val tty = (RishConstants.ATTY_IN or RishConstants.ATTY_OUT or RishConstants.ATTY_ERR).toByte()
        val host = RishHost(cmd.filterNotNull().toTypedArray(), env?.filterNotNull()?.toTypedArray(), dir ?: "", tty, null, null, null)
        host.start()
        val token = clientManager.findClient(uid, pid)?.client?.asBinder()
        return RemotePtyProcessHolder(host, token)
    }

    // ======================================================================
    // ⚠️ 本工程新增方法（上游没有）—— 命令安全拦截
    // ======================================================================

    /**
     * 对一条即将执行的命令做安全判定；不允许时抛出 SecurityException，
     * 该异常会沿 binder 调用栈返回给发起方。
     */
    private fun guard(uid: Int, pid: Int, cmd: Array<String?>) {
        val callerPackage = resolvePackageName(uid)
        val isManager = callerPackage == MANAGER_APPLICATION_ID

        val decision = CommandInterceptor.inspect(cmd, callerPackage, isManager)
        if (decision is InterceptDecision.Allow) return

        val flat = cmd.filterNotNull().joinToString(" ")
        val userId = userIdOf(uid)

        when (decision) {
            is InterceptDecision.Block -> {
                CommandGuard.showBlockedAlert(callerPackage, flat, decision.reason, userId)
                throw SecurityException(
                    "命令已被「游龙安全护盾」拦截：${decision.reason}"
                )
            }

            is InterceptDecision.NeedConfirm -> {
                val allowed = CommandGuard.askForConfirmation(
                    callerPackage, flat, decision.reason, userId, decision.kind
                )
                if (!allowed) {
                    throw SecurityException(
                        "命令已被用户拒绝：${decision.reason}"
                    )
                }
                LOGGER.i("用户已允许 %s 执行：%s", callerPackage, decision.reason)
            }

            is InterceptDecision.Allow -> Unit
        }
    }

    /**
     * 由 uid 反查调用方包名。
     *
     * <p>优先用客户端记录（那是应用自己声明的包名，最准）；取不到时退回
     * PackageManager 查询该 uid 下的包列表，取第一个。查不到返回 null，
     * 此时按「未知应用」处理——非管理器一律受拦截规则约束。
     */
    private fun resolvePackageName(uid: Int): String? {
        clientManager.findClients(uid).firstOrNull()?.packageName?.let { return it }
        return runCatching {
            PackageManagerApis.getPackagesForUidNoThrow(uid)?.firstOrNull()
        }.getOrNull()
    }

    /**
     * 由 uid 求用户 id。
     *
     * <p>不用 UserHandle.getUserId()：它在 compileSdk 的公开 stub 里是被 @hide 的，
     * 编译期解析不到。Android 的用户 id 定义就是 uid / 100000（PER_USER_RANGE），
     * 这里直接按同一规则计算，等价且不依赖隐藏 API。
     */
    private fun userIdOf(uid: Int): Int = uid / 100000
}
