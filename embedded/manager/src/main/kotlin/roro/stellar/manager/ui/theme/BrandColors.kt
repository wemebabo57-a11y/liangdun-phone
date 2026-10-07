// ==========================================================================
// 说明：本文件是**本工程新增**（不是上游 Stellar 文件），因此不受 MPL-2.0
//       第 3.4 条「被修改文件需带修改声明」的约束；此处仅作来源与合规说明。
//
// 新增内容：内置管理器的护盾品牌配色（替代上游的 Material3 默认色板）。
// 合规相关：本文件不包含任何取自 Stellar 的代码或素材，色值取自本产品
//           自己网页界面的用色统计。
// ==========================================================================

// ==========================================================================
// 游龙安全护盾 —— 新增文件（非上游 Stellar 文件）
// --------------------------------------------------------------------------
// 用途：「ADB护盾版」内置管理器界面的品牌配色。
//
// 上游 Stellar 的 Theme.kt 只用了 Material3 的默认色板（lightColorScheme()/
// darkColorScheme() 不带参数），并默认跟随壁纸动态取色。本工程改为：
//   · 默认使用这里定义的护盾品牌配色（与宿主「游龙安全护盾」网页 UI 主色一致）；
//   · 设置页仍可切回「跟随壁纸」（见 ColorMode / ThemePreferences）。
//
// 取色依据（来自宿主 app/src/main/assets/index.html 内联样式的实际用色统计）：
//   #007AFF 主蓝   —— 宿主 UI 的主强调色
//   #FF3B30 警示红 —— 宿主 UI 的危险/告警色
//   #34C759 成功绿、#FF9500 提醒橙、#8E8E93 中性灰
// 这里把它们映射到 Material3 的 color roles 上，使内置管理器与宿主外观统一。
// ==========================================================================

package roro.stellar.manager.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 品牌色原始值。
 */
object BrandPalette {
    /** 主蓝：宿主 UI 的强调色。 */
    val Blue = Color(0xFF007AFF)
    /** 警示红：宿主 UI 的危险/告警色。 */
    val Red = Color(0xFFFF3B30)
    /** 成功绿。 */
    val Green = Color(0xFF34C759)
    /** 提醒橙。 */
    val Orange = Color(0xFFFF9500)
    /** 中性灰。 */
    val Gray = Color(0xFF8E8E93)
}

/**
 * 亮色护盾配色。
 *
 * 主色用较深的 #0060DF 而不是 #007AFF：Material3 的 primary 需要与白色
 * onPrimary 形成足够对比度（#007AFF + 纯白文字仅 3.0:1，达不到 4.5:1）。
 * #007AFF 保留给 primaryContainer / 图表等大面积色块使用。
 */
val ShieldLightColors = lightColorScheme(
    primary = Color(0xFF0060DF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E6FF),
    onPrimaryContainer = Color(0xFF001A41),

    secondary = Color(0xFF2F5C9E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDBE6F7),
    onSecondaryContainer = Color(0xFF0B1B33),

    tertiary = Color(0xFF00696E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFB6ECEF),
    onTertiaryContainer = Color(0xFF002022),

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF1A1B20),
    surface = Color(0xFFF6F7FB),
    onSurface = Color(0xFF1A1B20),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF43474E),

    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F2F8),
    surfaceContainer = Color(0xFFEAEDF4),
    surfaceContainerHigh = Color(0xFFE4E7EF),
    surfaceContainerHighest = Color(0xFFDEE2EA),

    outline = Color(0xFF73777F),
    outlineVariant = Color(0xFFC3C6CF),

    inverseSurface = Color(0xFF2F3035),
    inverseOnSurface = Color(0xFFF1F0F6),
    inversePrimary = Color(0xFFA9C7FF),
    scrim = Color(0xFF000000)
)

/**
 * 暗色护盾配色。
 */
val ShieldDarkColors = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF00306B),
    primaryContainer = Color(0xFF00458F),
    onPrimaryContainer = Color(0xFFD6E6FF),

    secondary = Color(0xFFB3C8EA),
    onSecondary = Color(0xFF1C314F),
    secondaryContainer = Color(0xFF334867),
    onSecondaryContainer = Color(0xFFDBE6F7),

    tertiary = Color(0xFF9AD0D4),
    onTertiary = Color(0xFF003739),
    tertiaryContainer = Color(0xFF004F53),
    onTertiaryContainer = Color(0xFFB6ECEF),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF111318),
    onBackground = Color(0xFFE3E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE3E2E9),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C6CF),

    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),

    outline = Color(0xFF8D9199),
    outlineVariant = Color(0xFF43474E),

    inverseSurface = Color(0xFFE3E2E9),
    inverseOnSurface = Color(0xFF2F3035),
    inversePrimary = Color(0xFF0060DF),
    scrim = Color(0xFF000000)
)
