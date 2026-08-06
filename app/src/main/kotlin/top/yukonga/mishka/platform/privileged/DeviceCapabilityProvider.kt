package top.yukonga.mishka.platform.privileged

import kotlinx.coroutines.flow.StateFlow

interface DeviceCapabilityProvider {
    val capabilityFlow: StateFlow<DeviceCapability>

    fun current(): DeviceCapability
    fun refreshNotificationCapabilities()
    fun refreshPrivilegeStatus()
    suspend fun refreshPrivilegeStatusNow(authorizer: Authorizer)
    fun requestShizukuPermission(onResult: (ShizukuPermissionResult) -> Unit)
}

enum class ShizukuPermissionResult {
    Granted,
    NotGranted,
    NotRunning,
}
