package top.yukonga.mishka.platform

import android.os.Build
import top.yukonga.mishka.platform.privileged.Authorizer

enum class VpnNotificationStyle(val storageValue: String) {
    Standard("standard"),
    LiveActivity("live_activity"),
    MiIsland("mi_island");

    companion object {
        fun fromStorage(value: String): VpnNotificationStyle =
            entries.firstOrNull { it.storageValue == value } ?: Standard
    }
}

fun resolveEffectiveStyle(
    selectedStyle: VpnNotificationStyle,
    miIslandAvailable: Boolean,
    liveActivityAvailable: Boolean,
): VpnNotificationStyle = when (selectedStyle) {
    VpnNotificationStyle.MiIsland -> when {
        miIslandAvailable -> VpnNotificationStyle.MiIsland
        liveActivityAvailable -> VpnNotificationStyle.LiveActivity
        else -> VpnNotificationStyle.Standard
    }

    VpnNotificationStyle.LiveActivity ->
        if (liveActivityAvailable) VpnNotificationStyle.LiveActivity
        else VpnNotificationStyle.Standard

    VpnNotificationStyle.Standard -> VpnNotificationStyle.Standard
}

fun isLiveActivitySupported(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA

data class NotificationSettingsSnapshot(
    val style: VpnNotificationStyle = VpnNotificationStyle.Standard,
    val authorizer: Authorizer = Authorizer.None,
    val miIslandBypassRestriction: Boolean = true,
    val miIslandOuterGlow: Boolean = false,
    val dynamicNotification: Boolean = true,
) {
    fun writeTo(storage: PlatformStorage) {
        storage.putString(StorageKeys.NOTIFICATION_STYLE, style.storageValue)
        storage.putString(StorageKeys.NOTIFICATION_AUTHORIZER, authorizer.storageValue)
        storage.putString(
            StorageKeys.MI_ISLAND_BYPASS_RESTRICTION,
            miIslandBypassRestriction.toString(),
        )
        storage.putString(StorageKeys.MI_ISLAND_OUTER_GLOW, miIslandOuterGlow.toString())
        storage.putString(StorageKeys.DYNAMIC_NOTIFICATION, dynamicNotification.toString())
    }

    companion object {
        fun readFrom(storage: PlatformStorage): NotificationSettingsSnapshot =
            NotificationSettingsSnapshot(
                style = VpnNotificationStyle.fromStorage(
                    storage.getString(
                        StorageKeys.NOTIFICATION_STYLE,
                        VpnNotificationStyle.Standard.storageValue,
                    ),
                ),
                authorizer = Authorizer.fromStorage(
                    storage.getString(
                        StorageKeys.NOTIFICATION_AUTHORIZER,
                        Authorizer.None.storageValue,
                    ),
                ),
                miIslandBypassRestriction = storage.getString(
                    StorageKeys.MI_ISLAND_BYPASS_RESTRICTION,
                    "true",
                ) == "true",
                miIslandOuterGlow = storage.getString(
                    StorageKeys.MI_ISLAND_OUTER_GLOW,
                    "false",
                ) == "true",
                dynamicNotification = storage.getString(
                    StorageKeys.DYNAMIC_NOTIFICATION,
                    "true",
                ) == "true",
            )
    }
}
