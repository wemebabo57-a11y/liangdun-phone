package roro.stellar.manager.ui.features.home

import android.annotation.SuppressLint
import roro.stellar.manager.compat.BuildUtils.atLeast30
import android.widget.Toast
// ⚠️ 本工程新增导入：首页品牌卡所需的布局/图标组件。
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adb
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import roro.stellar.Stellar
import roro.stellar.manager.R
import roro.stellar.manager.compat.ClipboardUtils
import roro.stellar.manager.startup.command.Starter
import roro.stellar.manager.ui.components.BrandLargeTopAppBar
import roro.stellar.manager.ui.components.LocalScreenConfig
import roro.stellar.manager.ui.components.ShieldHeroCard
import roro.stellar.manager.ui.components.StellarDialog
import roro.stellar.manager.ui.features.home.InfoRow
import roro.stellar.manager.ui.navigation.components.createTopAppBarScrollBehavior
import roro.stellar.manager.ui.theme.AppShape
import roro.stellar.manager.ui.theme.AppSpacing
import roro.stellar.manager.util.EnvironmentUtils
import roro.stellar.manager.util.UserHandleCompat

@SuppressLint("LocalContextGetResourceValueCall")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    topAppBarState: TopAppBarState,
    homeViewModel: HomeViewModel,
    onNavigateToStarter: (isRoot: Boolean, host: String?, port: Int, hasSecureSettings: Boolean) -> Unit = { _, _, _, _ -> }
) {
    val scrollBehavior = createTopAppBarScrollBehavior(topAppBarState)
    val context = LocalContext.current
    val serviceStatusResource by homeViewModel.serviceStatus.observeAsState()
    val screenConfig = LocalScreenConfig.current

    val serviceStatus = serviceStatusResource?.data

    val isRunning = serviceStatus?.isRunning ?: false
    val isRoot = serviceStatus?.uid == 0
    val isPrimaryUser = UserHandleCompat.myUserId() == 0
    val hasRoot = EnvironmentUtils.isRooted()

    var showPowerDialog by remember { mutableStateOf(false) }
    var showAdbCommandDialog by remember { mutableStateOf(false) }
    var showAdbRestrictedFeaturesDialog by remember { mutableStateOf(false) }

    val gridColumns = screenConfig.gridColumns

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            // ⚠️ 本工程改动：改用带盾牌标识的品牌顶栏（原为 StandardLargeTopAppBar）。
            BrandLargeTopAppBar(
                title = "游龙安全ADB",
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(gridColumns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding() + AppSpacing.topBarContentSpacing,
                bottom = AppSpacing.screenBottomPadding,
                start = AppSpacing.screenHorizontalPadding,
                end = AppSpacing.screenHorizontalPadding
            ),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemSpacing),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.itemSpacing)
        ) {
            item(span = { GridItemSpan(gridColumns) }) {
                // ⚠️ 本工程改动：首页主状态卡改为品牌渐变「护盾卡」，
                //    服务版本 / 运行方式两行信息照旧由 InfoRow 呈现。
                ShieldHeroCard(
                    appName = "游龙安全ADB",
                    isRunning = isRunning,
                    statusText = if (isRunning) {
                        stringResource(R.string.service_running)
                    } else {
                        stringResource(R.string.service_not_running)
                    },
                    trailing = if (isRunning) {
                        {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(AppShape.shapes.iconSmall)
                                    .background(MaterialTheme.colorScheme.errorContainer)
                                    .clickable(onClick = { showPowerDialog = true }),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PowerSettingsNew,
                                    contentDescription = stringResource(R.string.stop_service),
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    } else null
                ) {
                    if (isRunning) {
                        val api = serviceStatus?.apiVersion ?: 0
                        Spacer(modifier = Modifier.height(14.dp))
                        InfoRow(
                            label = stringResource(R.string.version),
                            value = "${api / 100}.${(api % 100) / 10}.${api % 10}",
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            icon = Icons.Default.Info
                        )
                        InfoRow(
                            label = stringResource(R.string.run_mode),
                            value = if (isRoot) "Root" else "ADB",
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            icon = if (isRoot) Icons.Default.Security else Icons.Default.Adb
                        )
                    }
                }
            }

            if (isRunning && !serviceStatus.permission) {
                item(span = { GridItemSpan(gridColumns) }) {
                    AdbRestrictedHintCard(
                        onViewClick = { showAdbRestrictedFeaturesDialog = true }
                    )
                }
            }

            if (isPrimaryUser) {
                if (hasRoot) {
                    item {
                        StartRootCard(
                            isRestart = isRunning && isRoot,
                            onStartClick = { onNavigateToStarter(true, null, 0, false) }
                        )
                    }
                }

                if (atLeast30 || EnvironmentUtils.getAdbTcpPort() > 0) {
                    item {
                        StartWirelessAdbCard(
                            onStartClick = { onNavigateToStarter(false, "127.0.0.1", 0, false) }
                        )
                    }
                }

                item {
                    StartWiredAdbCard(
                        onButtonClick = { showAdbCommandDialog = true }
                    )
                }

                if (!hasRoot) {
                    item {
                        StartRootCard(
                            isRestart = isRunning && isRoot,
                            onStartClick = {
                                Toast.makeText(context, context.getString(R.string.no_root_permission), Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }
    }

    if (showPowerDialog) {
        StellarDialog(
            onDismissRequest = { showPowerDialog = false },
            title = stringResource(R.string.stop_service),
            confirmText = stringResource(R.string.stop),
            dismissText = stringResource(R.string.restart),
            onConfirm = {
                if (Stellar.pingBinder()) {
                    try { Stellar.exit() } catch (_: Throwable) {}
                }
                showPowerDialog = false
            },
            onDismiss = {
                homeViewModel.restartService()
                showPowerDialog = false
            }
        ) {
            Text(
                text = stringResource(R.string.stop_service_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showAdbCommandDialog) {
        StellarDialog(
            onDismissRequest = { showAdbCommandDialog = false },
            title = stringResource(R.string.view_command),
            confirmText = stringResource(R.string.copy),
            dismissText = stringResource(R.string.close),
            onConfirm = {
                ClipboardUtils.put(context, Starter.adbCommand)
                Toast.makeText(context, context.getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showAdbCommandDialog = false }
        ) {
            Text(
                text = Starter.adbCommand,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showAdbRestrictedFeaturesDialog) {
        StellarDialog(
            onDismissRequest = { showAdbRestrictedFeaturesDialog = false },
            title = stringResource(R.string.adb_restricted_features_title),
            confirmText = stringResource(R.string.close),
            onConfirm = { showAdbRestrictedFeaturesDialog = false },
            showDismissButton = false
        ) {
            RestrictedFeatureList(
                features = serviceStatus?.featureStates ?: emptyList()
            )
        }
    }
}
