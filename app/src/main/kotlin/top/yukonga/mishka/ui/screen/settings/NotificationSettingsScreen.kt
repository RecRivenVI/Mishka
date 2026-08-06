package top.yukonga.mishka.ui.screen.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import top.yukonga.mishka.R
import top.yukonga.mishka.platform.resolveEffectiveStyle
import top.yukonga.mishka.platform.VpnNotificationStyle
import top.yukonga.mishka.platform.privileged.Authorizer
import top.yukonga.mishka.platform.privileged.RootMode
import top.yukonga.mishka.platform.privileged.ShizukuMode
import top.yukonga.mishka.platform.privileged.ShizukuPermissionResult
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.CardItem
import top.yukonga.mishka.ui.component.CardSegment
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.component.groupedCardItems
import top.yukonga.mishka.ui.util.horizontalCutoutPadding
import top.yukonga.mishka.viewmodel.NotificationSettingsUiState
import top.yukonga.mishka.viewmodel.NotificationSettingsViewModel
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 通知设置页按样式、偏好两段组织；超级岛绕过直接选择授权方式，外发光随生效样式展开。
 */
@Composable
fun NotificationSettingsScreen(
    viewModel: NotificationSettingsViewModel,
    onBack: () -> Unit = {},
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
    val snackbarHostState = remember { SnackbarHostState() }
    val shizukuNotRunningMessage = stringResource(R.string.notification_shizuku_not_running_snackbar)
    val shizukuNotGrantedMessage = stringResource(R.string.notification_shizuku_not_granted_snackbar)

    LaunchedEffect(Unit) {
        viewModel.refreshTunMode()
        viewModel.refreshCapability()
    }
    LaunchedEffect(viewModel, shizukuNotRunningMessage, shizukuNotGrantedMessage) {
        viewModel.shizukuPermissionFailures.collect { failure ->
            // showSnackbar 会挂起到该条结束；每条独立启动才能交给 miuix Host 并发堆叠。
            launch {
                snackbarHostState.showSnackbar(
                    message = if (failure == ShizukuPermissionResult.NotRunning) {
                        shizukuNotRunningMessage
                    } else {
                        shizukuNotGrantedMessage
                    },
                    withDismissAction = true,
                    duration = SnackbarDuration.Long,
                )
            }
        }
    }

    val styleOptions = buildStyleOptions(uiState)
    val styleItems = styleOptions.map { it.label }
    val effectiveStyle = resolveEffectiveStyle(
        selectedStyle = uiState.style,
        miIslandAvailable = uiState.miIslandAvailable,
        liveActivityAvailable = uiState.liveActivityAvailable,
    )
    val styleIndex = styleOptions.indexOfFirst { it.style == effectiveStyle }.takeIf { it >= 0 } ?: 0
    val styleSelectionEnabled = styleOptions.size > 1

    val selectedBypassAuthorizer = uiState.authorizer.takeIf {
        uiState.miIslandBypassRestriction
    } ?: Authorizer.None
    val rootManager = rootManagerName(uiState.rootMode)
    val selectedAuthorizerAvailable = when (selectedBypassAuthorizer) {
        Authorizer.None -> false
        Authorizer.Shizuku -> uiState.shizukuMode == ShizukuMode.Authorized
        Authorizer.Root -> rootManager != null
    }
    val authorizerOptions = listOf(
        AuthorizerOption(
            Authorizer.None,
            stringResource(R.string.notification_authorizer_none),
            true,
        ),
        AuthorizerOption(
            Authorizer.Shizuku,
            stringResource(R.string.notification_authorizer_shizuku),
            true,
        ),
        AuthorizerOption(
            Authorizer.Root,
            if (rootManager == null) {
                stringResource(R.string.notification_authorizer_root)
            } else {
                stringResource(R.string.notification_authorizer_root_with_manager, rootManager)
            },
            rootManager != null,
        ),
    )
    val authorizerItems = authorizerOptions.map {
        DropdownItem(text = it.label, enabled = it.enabled)
    }
    val authorizerIndex =
        authorizerOptions.indexOfFirst { it.authorizer == selectedBypassAuthorizer }.takeIf { it >= 0 } ?: 0

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.notification_settings),
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            val layoutDirection = LocalLayoutDirection.current
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(R.string.common_back),
                                tint = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.graphicsLayer {
                                    scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                                },
                            )
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .horizontalCutoutPadding()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(top = innerPadding.calculateTopPadding()),
        ) {
            item { SmallTitle(text = stringResource(R.string.notification_style)) }
            item(key = "notification_style:picker") {
                val bottomCornerRadius by animateDpAsState(
                    targetValue = if (effectiveStyle == VpnNotificationStyle.MiIsland) 0.dp else 16.dp,
                    animationSpec = tween(300),
                    label = "notification-style-bottom-corner",
                )
                CardSegment(
                    isFirst = true,
                    isLast = false,
                    outerBottomPadding = 0.dp,
                    bottomCornerRadius = bottomCornerRadius,
                ) {
                    OverlayDropdownPreference(
                        items = styleItems,
                        selectedIndex = styleIndex,
                        title = stringResource(R.string.notification_style),
                        summary = if (styleSelectionEnabled) {
                            styleOptions.getOrNull(styleIndex)?.label
                        } else {
                            stringResource(
                                if (uiState.miIslandProtocolSupported || uiState.liveActivityPlatformSupported) {
                                    R.string.notification_style_permission_required_desc
                                } else {
                                    R.string.notification_style_unsupported_desc
                                },
                            )
                        },
                        enabled = styleSelectionEnabled,
                        onSelectedIndexChange = { index ->
                            styleOptions.getOrNull(index)?.style?.let(viewModel::setStyle)
                        },
                    )
                }
            }
            item(key = "notification_style:miIslandOptions") {
                CardSegment(
                    isFirst = false,
                    isLast = true,
                    outerBottomPadding = 12.dp,
                ) {
                    AnimatedVisibility(
                        visible = effectiveStyle == VpnNotificationStyle.MiIsland,
                        enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                        exit = fadeOut(tween(300)) + shrinkVertically(tween(300)),
                    ) {
                        Column {
                            OverlaySpinnerPreference(
                                items = authorizerItems,
                                selectedIndex = authorizerIndex,
                                title = stringResource(R.string.notification_mi_island_bypass),
                                summary = stringResource(
                                    if (selectedAuthorizerAvailable) {
                                        R.string.notification_mi_island_bypass_summary
                                    } else {
                                        R.string.notification_mi_island_bypass_summary_no_privilege
                                    },
                                ),
                                onSelectedIndexChange = { index ->
                                    authorizerOptions.getOrNull(index)?.let {
                                        viewModel.selectBypassAuthorizer(it.authorizer)
                                    }
                                },
                            )
                            SwitchPreference(
                                title = stringResource(R.string.notification_mi_island_outer_glow),
                                summary = stringResource(R.string.notification_mi_island_outer_glow_summary),
                                checked = uiState.miIslandOuterGlow,
                                onCheckedChange = viewModel::setMiIslandOuterGlow,
                            )
                        }
                    }
                }
            }

            item { SmallTitle(text = stringResource(R.string.notification_section_preferences)) }
            groupedCardItems(
                keyPrefix = "notification_preferences",
                outerBottomPadding = 12.dp,
                items = listOf(
                    CardItem("dynamicNotification") {
                        SwitchPreference(
                            title = stringResource(R.string.settings_dynamic_notification),
                            summary = stringResource(
                                if (uiState.isVpnMode) {
                                    R.string.settings_dynamic_notification_summary
                                } else {
                                    R.string.settings_dynamic_notification_summary_root_unsupported
                                },
                            ),
                            checked = uiState.dynamicNotification && uiState.isVpnMode,
                            enabled = uiState.isVpnMode,
                            onCheckedChange = viewModel::setDynamicNotification,
                        )
                    },
                ),
            )
            item {
                Spacer(
                    Modifier
                        .height(24.dp)
                        .navigationBarsPadding(),
                )
            }
        }
    }
}

private data class StyleOption(
    val style: VpnNotificationStyle,
    val label: String,
)

private data class AuthorizerOption(
    val authorizer: Authorizer,
    val label: String,
    val enabled: Boolean,
)

@Composable
private fun buildStyleOptions(uiState: NotificationSettingsUiState): List<StyleOption> =
    buildList {
        add(StyleOption(VpnNotificationStyle.Standard, stringResource(R.string.notification_style_standard)))
        if (uiState.liveActivityAvailable) {
            add(StyleOption(VpnNotificationStyle.LiveActivity, stringResource(R.string.notification_style_live_activity)))
        }
        if (uiState.miIslandAvailable) {
            add(StyleOption(VpnNotificationStyle.MiIsland, stringResource(R.string.notification_style_mi_island)))
        }
    }

private fun rootManagerName(rootMode: RootMode): String? = when (rootMode) {
    RootMode.None -> null
    RootMode.Magisk -> "Magisk"
    RootMode.KernelSU -> "KernelSU"
    RootMode.APatch -> "APatch"
}
