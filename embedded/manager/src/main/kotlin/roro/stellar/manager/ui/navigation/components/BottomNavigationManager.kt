// ==========================================================================
// 本文件来自 https://github.com/roro2239/Stellar 的
//   manager/src/main/kotlin/roro/stellar/manager/ui/navigation/components/
//   BottomNavigationManager.kt （MPL-2.0；其中源自 Shizuku 的部分为 Apache-2.0）
//
// 【本工程修改声明】—— 按 MPL-2.0 第 3.4 条标注
//   改动：为底部导航栏 / 侧边导航栏显式指定品牌配色与指示器外观。
//   原因：上游直接用 NavigationBar / NavigationRail 的默认配色——默认
//         containerColor 取 surfaceContainer，指示器取 secondaryContainer，
//         在护盾品牌色板下对比偏弱、和页面背景几乎同色。这里改为：
//           · 容器用 surfaceContainer，并加一层顶部细分割线，让导航区与
//             内容区有明确边界；
//           · 选中项指示器用 primaryContainer，选中图标/文字用 primary，
//             未选中用 onSurfaceVariant，层级更清楚。
//   除配色与非选中态文字样式外，本文件其余结构与上游一致。
// ==========================================================================

package roro.stellar.manager.ui.navigation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import roro.stellar.manager.ui.navigation.routes.MainScreen

/**
 * 底部导航项的统一配色。
 */
@Composable
private fun navigationItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.primary,
    selectedTextColor = MaterialTheme.colorScheme.primary,
    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
)

@Composable
fun StandardBottomNavigation(
    selectedIndex: Int,
    onItemClick: (Int) -> Unit
) {
    Column {
        // ⚠️ 本工程新增：导航区与内容区之间的分割线。
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ) {
            MainScreen.entries.forEachIndexed { index, screen ->
                val isSelected = selectedIndex == index

                NavigationBarItem(
                    icon = {
                        Icon(
                            imageVector = if (isSelected) screen.iconFilled else screen.icon,
                            contentDescription = stringResource(screen.labelRes),
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    label = {
                        Text(
                            text = stringResource(screen.labelRes),
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    },
                    selected = isSelected,
                    onClick = { onItemClick(index) },
                    colors = navigationItemColors()
                )
            }
        }
    }
}

@Composable
fun StandardNavigationRail(
    selectedIndex: Int,
    onItemClick: (Int) -> Unit
) {
    NavigationRail(
        modifier = Modifier.fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MainScreen.entries.forEachIndexed { index, screen ->
                val isSelected = selectedIndex == index

                NavigationRailItem(
                    icon = {
                        Icon(
                            imageVector = if (isSelected) screen.iconFilled else screen.icon,
                            contentDescription = stringResource(screen.labelRes),
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    label = {
                        Text(
                            text = stringResource(screen.labelRes),
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    },
                    selected = isSelected,
                    onClick = { onItemClick(index) },
                    colors = NavigationRailItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
    }
}
