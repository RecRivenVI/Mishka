package top.yukonga.mishka.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import top.yukonga.mishka.platform.NotificationSettingsSnapshot
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.TunMode
import top.yukonga.mishka.platform.VpnNotificationStyle
import top.yukonga.mishka.platform.resolveEffectiveStyle
import top.yukonga.mishka.platform.privileged.DeviceCapabilityProvider
import java.util.IdentityHashMap

sealed interface VpnNotificationContent {
    data object Loading : VpnNotificationContent

    data class Running(val tunMode: TunMode) : VpnNotificationContent

    data class Dynamic(
        val profileName: String,
        val uploadTotal: String,
        val downloadTotal: String,
        val uploadSpeed: String,
        val downloadSpeed: String,
        val tunMode: TunMode,
    ) : VpnNotificationContent
}

/**
 * mishka_vpn 通知路由：读取设置并解析生效样式，构建通知后选择热窗口、冷会话补发或普通发布。
 */
class VpnNotificationPublisher(
    private val context: Context,
    private val storage: PlatformStorage,
    private val capabilityProvider: DeviceCapabilityProvider,
    private val bypass: SuperIslandBypass,
) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val serviceGenerations = IdentityHashMap<Service, Long>()

    fun startForeground(service: Service, content: VpnNotificationContent) {
        val generation = bypass.beginService()
        synchronized(serviceGenerations) { serviceGenerations[service] = generation }
        val (settings, style) = readNotificationState()
        val notification = NotificationHelper.buildVpnNotification(
            context = context,
            content = content,
            style = style,
            outerGlow = settings.miIslandOuterGlow,
        )
        val applies = bypass.appliesTo(style, settings)
        if (applies && bypass.runWindowIfWarm(settings.authorizer) {
                service.startForeground(NotificationHelper.NOTIFICATION_ID_VPN, notification)
            }
        ) {
            return
        }

        service.startForeground(NotificationHelper.NOTIFICATION_ID_VPN, notification)
        if (applies) bypass.postWithWindow(notification)
    }

    fun publish(content: VpnNotificationContent) {
        val (settings, style) = readNotificationState()
        val notification = NotificationHelper.buildVpnNotification(
            context = context,
            content = content,
            style = style,
            outerGlow = settings.miIslandOuterGlow,
        )
        if (bypass.appliesTo(style, settings)) {
            bypass.postWithWindow(notification)
        } else {
            notificationManager.notify(NotificationHelper.NOTIFICATION_ID_VPN, notification)
        }
    }

    fun releaseService(service: Service) {
        val generation = synchronized(serviceGenerations) {
            serviceGenerations.remove(service)
        } ?: return
        bypass.release(generation)
    }

    fun onRefreshRequested(clearBypassCooldown: Boolean) {
        if (clearBypassCooldown) bypass.onSettingsChanged()
    }

    private fun readNotificationState(): Pair<NotificationSettingsSnapshot, VpnNotificationStyle> {
        val settings = NotificationSettingsSnapshot.readFrom(storage)
        val capability = capabilityProvider.current()
        val style = resolveEffectiveStyle(
            selectedStyle = settings.style,
            miIslandAvailable = capability.miIslandAvailable,
            liveActivityAvailable = capability.liveActivityAvailable,
        )
        return settings to style
    }
}
