package top.yukonga.mishka.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import top.yukonga.mishka.data.api.MihomoConnectionManager
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.platform.TunMode
import top.yukonga.mishka.util.FormatUtils

/**
 * 动态通知管理器。
 * 通过共享的 [MihomoConnectionManager.repository] 拿 traffic 流，内容状态统一交给发布器。
 */
class DynamicNotificationManager(
    private val scope: CoroutineScope,
    private val connectionManager: MihomoConnectionManager,
    private val publisher: VpnNotificationPublisher,
) {
    private var trafficJob: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(profileName: String, tunMode: TunMode) {
        trafficJob?.cancel()
        trafficJob = scope.launch {
            connectionManager.repository
                .filterNotNull()
                .flatMapLatest { it.trafficFlow() }
                .collect { traffic ->
                    runCatching {
                        publisher.publish(
                            VpnNotificationContent.Dynamic(
                                profileName = profileName,
                                uploadTotal = FormatUtils.formatBytes(traffic.upTotal),
                                downloadTotal = FormatUtils.formatBytes(traffic.downTotal),
                                uploadSpeed = FormatUtils.formatSpeed(traffic.up),
                                downloadSpeed = FormatUtils.formatSpeed(traffic.down),
                                tunMode = tunMode,
                            ),
                        )
                    }.onFailure { Log.w(TAG, "Notify failed", it) }
                }
        }
    }

    /**
     * 根据设置启动动态通知或显示静态通知。
     *
     * ROOT 模式（RootTun / RootTproxy）下强制走静态：app 进程无 VpnService 系统 binding 加持，
     * 后台时 device idle 让 1 Hz `/traffic` WS 帧合并 + `notify()` 批处理，动态通知会冻结。
     */
    fun startOrFallbackStatic(storage: PlatformStorage, tunMode: TunMode = TunMode.Vpn) {
        val isDynamicEnabled = storage.getString(StorageKeys.DYNAMIC_NOTIFICATION, "true") == "true"
        if (isDynamicEnabled && tunMode == TunMode.Vpn) {
            val profileName = storage.getString(StorageKeys.ACTIVE_PROFILE_NAME, "Mishka")
            start(profileName, tunMode)
        } else {
            publisher.publish(VpnNotificationContent.Running(tunMode))
        }
    }

    fun stop() {
        trafficJob?.cancel()
        trafficJob = null
    }

    private companion object {
        private const val TAG = "DynamicNotification"
    }
}
