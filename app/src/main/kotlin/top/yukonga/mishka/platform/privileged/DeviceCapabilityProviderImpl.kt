package top.yukonga.mishka.platform.privileged

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import top.yukonga.mishka.platform.isLiveActivitySupported
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DeviceCapabilityProviderImpl(
    private val context: Context,
) : DeviceCapabilityProvider {
    companion object {
        private const val TAG = "DeviceCapability"
        private const val ROOT_DETECTION_EXTRA_PATH =
            "/data/adb/ksu/bin:/data/adb/ap/bin:/data/adb/magisk/bin"
        private const val FOCUS_NOTIFICATION_PROVIDER = "content://miui.statusbar.notification.public"
        private const val NOTIFICATION_FOCUS_PROTOCOL = "notification_focus_protocol"
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val privilegeRefreshMutex = Mutex()
    private val notificationRefreshMutex = Mutex()

    private val rootModeFlow = MutableStateFlow(RootMode.None)

    private val shizukuModeFlow = MutableStateFlow(ShizukuMode.NotRunning)
    private val shizukuPermissionCallback = AtomicReference<((ShizukuPermissionResult) -> Unit)?>(null)

    private val _capabilityFlow = MutableStateFlow(
        DeviceCapability(
            miIslandProtocolSupported = detectMiIslandProtocolSupport(),
            liveActivityPlatformSupported = isLiveActivitySupported(),
        ),
    )
    override val capabilityFlow: StateFlow<DeviceCapability> = _capabilityFlow.asStateFlow()

    init {
        Shizuku.addBinderReceivedListenerSticky {
            updateShizukuMode()
        }
        Shizuku.addBinderDeadListener {
            shizukuModeFlow.value = ShizukuMode.NotRunning
            updateCapability()
            shizukuPermissionCallback.getAndSet(null)?.invoke(ShizukuPermissionResult.NotRunning)
        }
        Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != SHIZUKU_PERMISSION_REQUEST_CODE) return@addRequestPermissionResultListener
            updateShizukuMode()
            shizukuPermissionCallback.getAndSet(null)?.invoke(
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    ShizukuPermissionResult.Granted
                } else {
                    ShizukuPermissionResult.NotGranted
                },
            )
        }
    }

    override fun current(): DeviceCapability = capabilityFlow.value

    override fun refreshNotificationCapabilities() {
        scope.launch(Dispatchers.IO) {
            notificationRefreshMutex.withLock {
                val protocolSupported = detectMiIslandProtocolSupport()
                val liveActivitySupported = isLiveActivitySupported()
                val focusPermissionGranted = protocolSupported && detectFocusPermission()
                val liveActivityPermissionGranted = liveActivitySupported && detectLiveActivityPermission()
                _capabilityFlow.update {
                    it.copy(
                        miIslandProtocolSupported = protocolSupported,
                        focusPermissionGranted = focusPermissionGranted,
                        liveActivityPlatformSupported = liveActivitySupported,
                        liveActivityPermissionGranted = liveActivityPermissionGranted,
                    )
                }
                Log.d(
                    TAG,
                    "Notification capabilities: islandProtocol=$protocolSupported, " +
                        "focusPermission=$focusPermissionGranted, " +
                        "livePlatform=$liveActivitySupported, " +
                        "livePermission=$liveActivityPermissionGranted",
                )
            }
        }
    }

    override fun refreshPrivilegeStatus() {
        updateShizukuMode()
        refreshNotificationCapabilities()
        scope.launch(Dispatchers.IO) {
            privilegeRefreshMutex.withLock {
                rootModeFlow.value = detectRootMode()
                updateCapability()
            }
        }
    }

    override suspend fun refreshPrivilegeStatusNow(authorizer: Authorizer) {
        when (authorizer) {
            Authorizer.Shizuku -> {
                shizukuModeFlow.value = detectShizukuMode()
                updateCapability()
            }

            Authorizer.Root -> privilegeRefreshMutex.withLock {
                rootModeFlow.value = detectRootMode()
                updateCapability()
            }

            Authorizer.None -> Unit
        }
    }

    override fun requestShizukuPermission(onResult: (ShizukuPermissionResult) -> Unit) {
        if (shizukuModeFlow.value == ShizukuMode.Authorized) {
            onResult(ShizukuPermissionResult.Granted)
            return
        }
        if (!shizukuPermissionCallback.compareAndSet(null, onResult)) {
            onResult(ShizukuPermissionResult.NotGranted)
            return
        }
        runCatching {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        }.onFailure { error ->
            if (shizukuPermissionCallback.compareAndSet(onResult, null)) {
                onResult(
                    if (shizukuModeFlow.value == ShizukuMode.NotRunning) {
                        ShizukuPermissionResult.NotRunning
                    } else {
                        ShizukuPermissionResult.NotGranted
                    },
                )
            }
            Log.d(TAG, "Failed to request Shizuku permission", error)
        }
    }

    private fun updateShizukuMode() {
        scope.launch(Dispatchers.IO) {
            shizukuModeFlow.value = detectShizukuMode()
            updateCapability()
        }
    }

    private fun updateCapability() {
        _capabilityFlow.update {
            it.copy(
                rootMode = rootModeFlow.value,
                shizukuMode = shizukuModeFlow.value,
            )
        }
    }

    private fun detectMiIslandProtocolSupport(): Boolean =
        Settings.System.getInt(
            context.contentResolver,
            NOTIFICATION_FOCUS_PROTOCOL,
            0,
        ) == 3

    private fun detectFocusPermission(): Boolean =
        try {
            val extras = Bundle().apply { putString("package", context.packageName) }
            context.contentResolver.call(
                Uri.parse(FOCUS_NOTIFICATION_PROVIDER),
                "canShowFocus",
                null,
                extras,
            )?.getBoolean("canShowFocus", false) == true
        } catch (e: Exception) {
            Log.d(TAG, "Failed to query Focus notification permission", e)
            false
        }

    private fun detectLiveActivityPermission(): Boolean =
        try {
            NotificationManagerCompat.from(context).canPostPromotedNotifications()
        } catch (e: Exception) {
            Log.d(TAG, "Failed to query promoted notification permission", e)
            false
        }

    private fun detectShizukuMode(): ShizukuMode =
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    ShizukuMode.Authorized
                } else {
                    ShizukuMode.NotAuthorized
                }
            } else {
                ShizukuMode.NotRunning
            }
        } catch (e: Throwable) {
            Log.d(TAG, "Failed to detect Shizuku mode", e)
            ShizukuMode.NotRunning
        }

    private suspend fun detectRootMode(): RootMode = withContext(Dispatchers.IO) {
        if (runSuProbe("ksud -V")) return@withContext RootMode.KernelSU
        if (runSuProbe("magisk -v")) return@withContext RootMode.Magisk
        if (runSuProbe("apd -V")) return@withContext RootMode.APatch
        RootMode.None
    }

    private fun runSuProbe(command: String): Boolean =
        try {
            val shellCommand = "export PATH=\$PATH:$ROOT_DETECTION_EXTRA_PATH && $command"
            val process = ProcessBuilder("su", "-c", shellCommand)
                .redirectErrorStream(true)
                .start()
            val exited = process.waitFor(3, TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                return false
            }
            process.exitValue() == 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "Root probe failed: $command", e)
            false
        }
}
