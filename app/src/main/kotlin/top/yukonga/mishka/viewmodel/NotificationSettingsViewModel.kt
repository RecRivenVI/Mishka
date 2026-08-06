package top.yukonga.mishka.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import top.yukonga.mishka.platform.NotificationSettingsSnapshot
import top.yukonga.mishka.platform.NotificationRefreshReason
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProxyServiceBridge
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.platform.TunMode
import top.yukonga.mishka.platform.VpnNotificationStyle
import top.yukonga.mishka.platform.privileged.Authorizer
import top.yukonga.mishka.platform.privileged.DeviceCapabilityProvider
import top.yukonga.mishka.platform.privileged.RootMode
import top.yukonga.mishka.platform.privileged.ShizukuMode
import top.yukonga.mishka.platform.privileged.ShizukuPermissionResult

class NotificationSettingsViewModel(
    private val storage: PlatformStorage,
    private val capabilityProvider: DeviceCapabilityProvider,
) : ViewModel() {
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<NotificationSettingsUiState> = _state.asStateFlow()
    private val _shizukuPermissionFailures = MutableSharedFlow<ShizukuPermissionResult>(extraBufferCapacity = 1)
    val shizukuPermissionFailures: SharedFlow<ShizukuPermissionResult> =
        _shizukuPermissionFailures.asSharedFlow()
    private var shizukuPermissionPending = false

    init {
        viewModelScope.launch {
            capabilityProvider.capabilityFlow.collect { capability ->
                _state.value = _state.value.copy(
                    rootMode = capability.rootMode,
                    shizukuMode = capability.shizukuMode,
                    miIslandProtocolSupported = capability.miIslandProtocolSupported,
                    miIslandAvailable = capability.miIslandAvailable,
                    liveActivityPlatformSupported = capability.liveActivityPlatformSupported,
                    liveActivityAvailable = capability.liveActivityAvailable,
                )
            }
        }
    }

    fun refreshCapability() {
        capabilityProvider.refreshPrivilegeStatus()
    }

    fun refreshTunMode() {
        _state.value = _state.value.copy(tunMode = currentTunMode())
    }

    fun setStyle(style: VpnNotificationStyle) {
        update { it.copy(style = style) }
        ProxyServiceBridge.requestNotificationRefresh(NotificationRefreshReason.Settings)
    }

    fun selectBypassAuthorizer(authorizer: Authorizer) {
        when (authorizer) {
            Authorizer.None -> setBypassAuthorizer(Authorizer.None)
            Authorizer.Root -> {
                if (_state.value.rootMode != RootMode.None) setBypassAuthorizer(Authorizer.Root)
            }

            Authorizer.Shizuku -> {
                setBypassAuthorizer(Authorizer.Shizuku)
                if (_state.value.shizukuMode == ShizukuMode.Authorized || shizukuPermissionPending) return
                shizukuPermissionPending = true
                capabilityProvider.requestShizukuPermission { result ->
                    viewModelScope.launch {
                        shizukuPermissionPending = false
                        val current = _state.value
                        val shizukuStillSelected = current.miIslandBypassRestriction &&
                            current.authorizer == Authorizer.Shizuku
                        if (result != ShizukuPermissionResult.Granted && shizukuStillSelected) {
                            _shizukuPermissionFailures.tryEmit(result)
                        }
                    }
                }
            }
        }
    }

    fun setMiIslandOuterGlow(enabled: Boolean) {
        update { it.copy(miIslandOuterGlow = enabled) }
        ProxyServiceBridge.requestNotificationRefresh(NotificationRefreshReason.Content)
    }

    fun setDynamicNotification(enabled: Boolean) {
        update { it.copy(dynamicNotification = enabled) }
        ProxyServiceBridge.requestNotificationRefresh(NotificationRefreshReason.Content)
    }

    private fun update(transform: (NotificationSettingsSnapshot) -> NotificationSettingsSnapshot) {
        val current = _state.value
        val next = transform(current.toSnapshot())
        next.writeTo(storage)
        _state.value = current.copy(
            style = next.style,
            authorizer = next.authorizer,
            miIslandBypassRestriction = next.miIslandBypassRestriction,
            miIslandOuterGlow = next.miIslandOuterGlow,
            dynamicNotification = next.dynamicNotification,
        )
    }

    private fun setBypassAuthorizer(authorizer: Authorizer) {
        update {
            it.copy(
                authorizer = authorizer,
                miIslandBypassRestriction = authorizer != Authorizer.None,
            )
        }
        ProxyServiceBridge.requestNotificationRefresh(NotificationRefreshReason.Settings)
    }

    private fun initialState(): NotificationSettingsUiState {
        val capability = capabilityProvider.current()
        return NotificationSettingsSnapshot.readFrom(storage).toUiState(
            rootMode = capability.rootMode,
            shizukuMode = capability.shizukuMode,
            miIslandProtocolSupported = capability.miIslandProtocolSupported,
            miIslandAvailable = capability.miIslandAvailable,
            liveActivityPlatformSupported = capability.liveActivityPlatformSupported,
            liveActivityAvailable = capability.liveActivityAvailable,
            tunMode = currentTunMode(),
        )
    }

    private fun currentTunMode(): TunMode = when (
        storage.getString(StorageKeys.TUN_MODE, "vpn")
    ) {
        "root_tun" -> TunMode.RootTun
        "root_tproxy" -> TunMode.RootTproxy
        else -> TunMode.Vpn
    }

    private fun NotificationSettingsUiState.toSnapshot() = NotificationSettingsSnapshot(
        style = style,
        authorizer = authorizer,
        miIslandBypassRestriction = miIslandBypassRestriction,
        miIslandOuterGlow = miIslandOuterGlow,
        dynamicNotification = dynamicNotification,
    )
}

@Immutable
data class NotificationSettingsUiState(
    val style: VpnNotificationStyle = VpnNotificationStyle.Standard,
    val authorizer: Authorizer = Authorizer.None,
    val miIslandBypassRestriction: Boolean = true,
    val miIslandOuterGlow: Boolean = false,
    val dynamicNotification: Boolean = true,
    val rootMode: RootMode = RootMode.None,
    val shizukuMode: ShizukuMode = ShizukuMode.NotRunning,
    val miIslandProtocolSupported: Boolean = false,
    val miIslandAvailable: Boolean = false,
    val liveActivityPlatformSupported: Boolean = false,
    val liveActivityAvailable: Boolean = false,
    val tunMode: TunMode = TunMode.Vpn,
) {
    val isVpnMode: Boolean
        get() = tunMode == TunMode.Vpn
}

private fun NotificationSettingsSnapshot.toUiState(
    rootMode: RootMode,
    shizukuMode: ShizukuMode,
    miIslandProtocolSupported: Boolean,
    miIslandAvailable: Boolean,
    liveActivityPlatformSupported: Boolean,
    liveActivityAvailable: Boolean,
    tunMode: TunMode,
): NotificationSettingsUiState = NotificationSettingsUiState(
    style = style,
    authorizer = authorizer,
    miIslandBypassRestriction = miIslandBypassRestriction,
    miIslandOuterGlow = miIslandOuterGlow,
    dynamicNotification = dynamicNotification,
    rootMode = rootMode,
    shizukuMode = shizukuMode,
    miIslandProtocolSupported = miIslandProtocolSupported,
    miIslandAvailable = miIslandAvailable,
    liveActivityPlatformSupported = liveActivityPlatformSupported,
    liveActivityAvailable = liveActivityAvailable,
    tunMode = tunMode,
)
