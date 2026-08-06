package top.yukonga.mishka.platform.privileged

enum class Authorizer(val storageValue: String) {
    None("none"),
    Shizuku("shizuku"),
    Root("root");

    companion object {
        fun fromStorage(value: String): Authorizer =
            entries.firstOrNull { it.storageValue == value } ?: None
    }
}

enum class RootMode {
    None,
    Magisk,
    KernelSU,
    APatch,
}

enum class ShizukuMode {
    NotRunning,
    NotAuthorized,
    Authorized,
}

data class DeviceCapability(
    val rootMode: RootMode = RootMode.None,
    val shizukuMode: ShizukuMode = ShizukuMode.NotRunning,
    val miIslandProtocolSupported: Boolean = false,
    val focusPermissionGranted: Boolean = false,
    val liveActivityPlatformSupported: Boolean = false,
    val liveActivityPermissionGranted: Boolean = false,
) {
    val miIslandAvailable: Boolean
        get() = miIslandProtocolSupported && focusPermissionGranted

    val liveActivityAvailable: Boolean
        get() = liveActivityPlatformSupported && liveActivityPermissionGranted
}

fun DeviceCapability.isAuthorizerAvailable(authorizer: Authorizer): Boolean =
    when (authorizer) {
        Authorizer.Shizuku -> shizukuMode == ShizukuMode.Authorized
        Authorizer.Root -> rootMode != RootMode.None
        Authorizer.None -> false
    }
