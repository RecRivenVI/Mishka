package top.yukonga.mishka.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yukonga.mishka.platform.privileged.DeviceCapability

class NotificationSettingsTest {
    @Test
    fun notificationAvailabilityRequiresPlatformAndPermission() {
        val denied = DeviceCapability(
            miIslandProtocolSupported = true,
            focusPermissionGranted = false,
            liveActivityPlatformSupported = true,
            liveActivityPermissionGranted = false,
        )
        val allowed = denied.copy(
            focusPermissionGranted = true,
            liveActivityPermissionGranted = true,
        )

        assertFalse(denied.miIslandAvailable)
        assertFalse(denied.liveActivityAvailable)
        assertTrue(allowed.miIslandAvailable)
        assertTrue(allowed.liveActivityAvailable)
    }

    @Test
    fun miIslandFallsBackOneStepWhenFocusPermissionIsDenied() {
        assertEquals(
            VpnNotificationStyle.LiveActivity,
            resolveEffectiveStyle(
                selectedStyle = VpnNotificationStyle.MiIsland,
                miIslandAvailable = false,
                liveActivityAvailable = true,
            ),
        )
    }

    @Test
    fun liveActivityFallsBackToStandardWhenPromotionIsDenied() {
        assertEquals(
            VpnNotificationStyle.Standard,
            resolveEffectiveStyle(
                selectedStyle = VpnNotificationStyle.LiveActivity,
                miIslandAvailable = true,
                liveActivityAvailable = false,
            ),
        )
    }
}
