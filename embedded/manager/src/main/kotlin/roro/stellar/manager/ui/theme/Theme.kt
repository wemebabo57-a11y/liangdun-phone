// ==========================================================================
// 本文件来自 https://github.com/roro2239/Stellar 的
//   manager/src/main/kotlin/roro/stellar/manager/ui/theme/Theme.kt
// （MPL-2.0；其中源自 Shizuku 的部分为 Apache-2.0）
//
// 【本工程修改声明】—— 按 MPL-2.0 第 3.4 条标注
//   改动 1：色板来源由上游的 lightColorScheme() / darkColorScheme()（Material3
//         默认色）改为 BrandColors.kt 里的 ShieldLightColors / ShieldDarkColors
//         （护盾品牌配色，主色取自宿主「游龙安全护盾」网页 UI 的 #007AFF）。
//   改动 2：dynamicColor 的默认值由 true 改为 false，并且实际取值改由
//         ThemePreferences.colorMode 决定（BRAND / DYNAMIC），
//         使「跟随壁纸」变成用户可选的设置项而不是默认行为。
//   改动 3：状态栏图标明暗跟随主题；并让导航栏底色与 surface 一致，
//         避免默认黑色导航栏与品牌配色割裂。
//   原因：本工程要求内置管理器界面默认呈现护盾品牌外观（更统一、更「自家」），
//         同时保留上游的动态取色能力供用户自行切换。
//   除以上三处外，本文件其余内容与上游一致。
// ==========================================================================

package roro.stellar.manager.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import roro.stellar.manager.compat.BuildUtils.atLeast31

@Composable
fun StellarTheme(
    themeMode: ThemeMode = ThemePreferences.themeMode.value,
    // ⚠️ 本工程改动：默认不再跟随壁纸；真正的取值见下方 colorMode。
    dynamicColor: Boolean = ThemePreferences.colorMode.value == ColorMode.DYNAMIC,
    content: @Composable () -> Unit
) {
    val systemInDarkTheme = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.AUTO -> systemInDarkTheme
    }

    val colorScheme = when {
        dynamicColor && atLeast31 -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        // ⚠️ 本工程改动：默认走护盾品牌配色，而不是 Material3 默认色。
        darkTheme -> ShieldDarkColors
        else -> ShieldLightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            // ⚠️ 本工程新增：导航栏图标明暗同步跟随主题，否则亮色主题下
            //    透明导航栏上的白色手势条/按键会看不清。
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
