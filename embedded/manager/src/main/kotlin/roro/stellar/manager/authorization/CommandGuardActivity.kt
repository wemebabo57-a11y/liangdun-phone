// ==========================================================================
// 游龙安全护盾 —— 新增文件（非上游 Stellar 文件）
// --------------------------------------------------------------------------
// 用途：命令安全拦截的用户界面。
//
//   服务端 ProcessManager 在放行一条来自第三方应用的 shell 命令之前，
//   如果判定该命令危险，会拉起本 Activity：
//     · kind=block    → 只告知「某应用尝试某操作，已被拦截」，一个「好的」按钮；
//     · kind=confirm  → 询问用户是否允许（卸载 / 冻结 / 清数据类命令），
//                       用户选择后写答复文件，服务端据此决定放行或拒绝。
//
//   答复通过文件回传（/data/local/tmp/stellar_guard/<id>.resp）：
//   服务端与管理器约定用文件握手，避免改动上游 AIDL 接口。
//
// ==========================================================================

package roro.stellar.manager.authorization

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
// ⚠️ 本工程新增导入（2026-10）：告警框顶部改为应用封面所需。
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
// ⚠️ 本工程新增导入（2026-10）：Drawable -> ImageBitmap。
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import roro.stellar.manager.R
import roro.stellar.manager.ui.theme.AppShape
import roro.stellar.manager.ui.theme.StellarTheme
import roro.stellar.manager.util.Logger.Companion.LOGGER
import java.io.File

class CommandGuardActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CommandGuardActivity"

        /** 与服务端 CommandGuard 约定的握手目录。 */
        private const val HANDSHAKE_DIR = "/data/local/tmp/stellar_guard"

        private const val REPLY_ALLOW = "ALLOW"
        private const val REPLY_DENY = "DENY"
        private const val REPLY_ACK = "ACKNOWLEDGED"

        /** 兼容服务端"确认超时"的时长：即使用户不理会，也不无限占用服务端线程。 */
        private const val AUTO_DISMISS_MS = 30_000L
    }

    private var replyId: Long = -1
    private var replied = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val id = intent.getLongExtra("id", -1L)
        val kind = intent.getStringExtra("kind") ?: "block"
        val packageName = intent.getStringExtra("packageName")
        val command = intent.getStringExtra("command") ?: ""
        val reason = intent.getStringExtra("reason") ?: ""
        // 2026-10 新增：服务端命令分析得出的风险类型
        // system=动系统应用 / bulk=批量操作 / admin=设备管理员·策略 / normal=普通
        val riskKind = intent.getStringExtra("riskKind") ?: "normal"
        replyId = id

        if (id == -1L) {
            LOGGER.e("$TAG: 缺少请求 id，直接结束")
            finish()
            return
        }

        // 服务端在等待答复时会超时兜底；这里到点自动关闭，
        // 避免对话框长期驻留（用户没看到时也等同于拒绝）。
        window.decorView.postDelayed({
            if (!isFinishing && !replied) {
                LOGGER.w("$TAG: 用户未在 ${AUTO_DISMISS_MS}ms 内操作，按拒绝处理")
                reply(REPLY_DENY)
                finish()
            }
        }, AUTO_DISMISS_MS)

        val appLabel = resolveAppLabel(packageName)
        // 2026-10 改动（用户需求）：告警框上的图标改成**本应用自己的封面（启动图标）**。
        // 这里读的是本应用（rather than 被拦截应用）的图标 ——
        // 弹窗表达的是「游龙工具阻止了这个威胁」，所以应当是护盾自己的标识。
        val selfIcon = resolveSelfIcon()

        setContent {
            StellarTheme {
                if (kind == "confirm") {
                    CommandGuardConfirmDialog(
                        appLabel = appLabel,
                        packageName = packageName,
                        command = command,
                        reason = reason,
                        riskKind = riskKind,
                        onAllow = {
                            reply(REPLY_ALLOW)
                            finish()
                        },
                        onDeny = {
                            reply(REPLY_DENY)
                            finish()
                        }
                    )
                } else {
                    CommandGuardAlertDialog(
                        appLabel = appLabel,
                        packageName = packageName,
                        command = command,
                        reason = reason,
                        appIcon = selfIcon,
                        onDismiss = {
                            reply(REPLY_ACK)
                            finish()
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        // 若因系统原因（返回键、被回收）离开而没有答复，按拒绝处理，
        // 保证服务端不会一直等到超时。
        if (!replied && replyId != -1L) {
            reply(REPLY_DENY)
        }
        super.onDestroy()
    }

    /**
     * 本应用自己的启动图标（用作告警框上的标识）。
     *
     * ⚠️ 不能用 R.mipmap.ic_launcher：启动图标在 :app 模块里，
     *    :manager 库模块编译期看不到宿主的 mipmap 资源表（编译期会报
     *    Unresolved reference 'mipmap'）。所以运行时通过 PackageManager
     *    取自己的 applicationInfo.icon，再用 Drawable → Bitmap → ImageBitmap
     *    传给 Compose 的 Image。
     */
    private fun resolveSelfIcon(): ImageBitmap? {
        return try {
            val drawable = applicationInfo?.loadIcon(packageManager) ?: return null
            val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
            val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bmp.asImageBitmap()
        } catch (t: Throwable) {
            LOGGER.w(t, "$TAG: 读取自身图标失败（将退回默认标识）")
            null
        }
    }

    /**
     * 尽力把包名换成应用显示名，换不到就用包名本身。
     */
    private fun resolveAppLabel(packageName: String?): String {
        if (packageName.isNullOrEmpty()) return "未知应用"
        return try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: Throwable) {
            packageName
        }
    }

    /**
     * 把答复写回握手目录。写失败不重试：服务端本就有超时兜底（视为拒绝），
     * 而「写不进去」在安全语义上等于拒绝，不会造成误放行。
     */
    private fun reply(text: String) {
        if (replied || replyId == -1L) return
        replied = true
        try {
            val dir = File(HANDSHAKE_DIR)
            if (!dir.exists() && !dir.mkdirs()) {
                LOGGER.w("$TAG: 无法创建握手目录 $HANDSHAKE_DIR")
                return
            }
            File(dir, "$replyId.resp").writeText(text)
        } catch (t: Throwable) {
            LOGGER.w(t, "$TAG: 写答复失败")
        }
    }
}

// ======================================================================
// 对话框 UI
// ======================================================================

/**
 * 一个不带图标资源的「盾牌」标识，用字符拼出来，避免依赖具体 drawable。
 */
@Composable
private fun GuardBadge(container: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(AppShape.shapes.iconMedium18)
            .background(container),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "盾",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * 命令内容预览框：等宽字体、最多 4 行、可滚动。
 */
@Composable
private fun CommandPreview(command: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(R.string.command_guard_command_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            shape = AppShape.shapes.buttonSmall14,
            color = MaterialTheme.colorScheme.surfaceContainerHighest
        ) {
            Text(
                text = command,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 单行按钮（与权限确认弹窗保持一致的视觉语言）。
 */
@Composable
private fun DialogButton(
    text: String,
    container: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = AppShape.shapes.buttonSmall14,
        colors = CardDefaults.elevatedCardColors(containerColor = container)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * 「已被拦截」告警框：只有一个「好的」按钮，不阻塞服务端。
 *
 * 2026-10 改动（用户需求）：
 *   · 标题固定为「游龙工具已阻止此威胁」；
 *   · 顶部标识改为本应用自己的封面（启动图标），读不到时退回内置盾牌标识；
 *   · 正文改为「谁尝试了什么」，不再把「拦截原因」和「尝试」拼成一句话
 *     （原先是「X 尝试 Y，已被拦截」，标题已经是「已阻止」了，重复）。
 */
@Composable
fun CommandGuardAlertDialog(
    appLabel: String,
    packageName: String?,
    command: String,
    reason: String,
    onDismiss: () -> Unit,
    appIcon: ImageBitmap? = null
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .wrapContentHeight(),
            shape = AppShape.shapes.cardMedium24,
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 顶部标识：优先用应用封面
                if (appIcon != null) {
                    Image(
                        bitmap = appIcon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(AppShape.shapes.iconMedium18)
                    )
                } else {
                    GuardBadge(MaterialTheme.colorScheme.errorContainer)
                }

                Text(
                    // ⚠️ 本工程改动：固定文案「游龙工具已阻止此威胁」。
                    text = "游龙工具已阻止此威胁",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.error
                )

                Text(
                    // 「谁尝试了什么」
                    text = "$appLabel 尝试 $reason",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!packageName.isNullOrEmpty()) {
                    Text(
                        text = packageName,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (command.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    CommandPreview(command)
                }

                DialogButton(
                    text = stringResource(R.string.command_guard_ok),
                    container = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = onDismiss
                )
            }
        }
    }
}

/**
 * 按风险类型给出针对性的风险说明（2026-10 新增，用户需求）。
 *
 * <p>风险类型由服务端 CommandInterceptor.analyzeRiskKind() 通过命令分析得出：
 *   · system —— 目标是系统应用（设置、系统界面、包安装器…）
 *   · bulk   —— 一条命令里批量操作多个应用
 *   · admin  —— 动设备管理员 / 用户 / 设备策略
 *   · normal —— 普通卸载 / 冻结
 */
@Composable
private fun RiskHint(riskKind: String) {
    val text = when (riskKind) {
        "system" -> "⚠️ 目标是系统应用。冻结或卸载「设置」「系统界面」等组件，" +
                "可能导致系统无法正常使用（设置进不去、装不了应用），请谨慎允许。"
        "bulk" -> "⚠️ 这条命令会一次性影响多个应用，属于批量卸载/冻结操作，" +
                "请确认你确实要这样做。"
        "admin" -> "⚠️ 这条命令会修改设备管理员或用户策略，" +
                "属于系统级高敏感操作，可能影响设备安全策略。"
        else -> null
    }
    if (text == null) return

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShape.shapes.buttonSmall14,
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

/**
 * 「是否允许」确认框：允许一次 / 拒绝。
 */
@Composable
fun CommandGuardConfirmDialog(
    appLabel: String,
    packageName: String?,
    command: String,
    reason: String,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    riskKind: String = "normal"
) {
    Dialog(
        onDismissRequest = onDeny,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .wrapContentHeight(),
            shape = AppShape.shapes.cardMedium24,
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                GuardBadge(MaterialTheme.colorScheme.tertiaryContainer)

                Text(
                    text = stringResource(R.string.command_guard_confirm_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = stringResource(
                        R.string.command_guard_confirm_body,
                        appLabel,
                        reason
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 2026-10 新增（用户需求）：按命令分析结果给出针对性的风险说明，
                // 让用户明白"这次到底危险在哪"，而不是干巴巴问一句是否允许。
                RiskHint(riskKind)

                if (!packageName.isNullOrEmpty()) {
                    Text(
                        text = packageName,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (command.isNotBlank()) {
                    CommandPreview(command)
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DialogButton(
                        text = stringResource(R.string.command_guard_allow_once),
                        container = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        onClick = onAllow
                    )
                    DialogButton(
                        text = stringResource(R.string.command_guard_deny),
                        container = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        onClick = onDeny
                    )
                }
            }
        }
    }
}
