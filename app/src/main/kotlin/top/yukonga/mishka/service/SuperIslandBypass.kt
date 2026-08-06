package top.yukonga.mishka.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.yukonga.mishka.platform.NotificationSettingsSnapshot
import top.yukonga.mishka.platform.NotificationRefreshReason
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.ProxyServiceBridge
import top.yukonga.mishka.platform.VpnNotificationStyle
import top.yukonga.mishka.platform.resolveEffectiveStyle
import top.yukonga.mishka.platform.privileged.Authorizer
import top.yukonga.mishka.platform.privileged.DeviceCapability
import top.yukonga.mishka.platform.privileged.DeviceCapabilityProvider
import top.yukonga.mishka.platform.privileged.DirectPrivilegedSession
import top.yukonga.mishka.platform.privileged.PrivilegedOperationProvider
import top.yukonga.mishka.platform.privileged.PrivilegedOperations
import top.yukonga.mishka.platform.privileged.RootProcessSession
import top.yukonga.mishka.platform.privileged.isAuthorizerAvailable
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.milliseconds

/**
 * 管理小米超级岛的特权会话与 XMSF 窗口：token 隔离重叠恢复，持久标志兜底进程死亡。
 */
class SuperIslandBypass(
    private val context: Context,
    private val capabilityProvider: DeviceCapabilityProvider,
    private val privilegedOperationProvider: PrivilegedOperationProvider,
    private val rootProcessSession: RootProcessSession,
    private val appScope: CoroutineScope,
) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val storage = PlatformStorage(context)
    private val recoveryStore = XmsfRecoveryStore(context)
    private val windowMutex = Mutex()
    private val sessionMutex = Mutex()
    private val windowOperationLock = ReentrantLock()
    private val windowRegistry = XmsfWindowRegistry<DirectPrivilegedSession>()

    @Volatile
    private var pendingRestore: PendingRestore? = null

    @Volatile
    private var warmAuthorizer: Authorizer? = null

    private val serviceGenerationCounter = AtomicLong(0L)

    @Volatile
    private var activeServiceGeneration = 0L

    @Volatile
    private var bypassFailureAtElapsedRealtime = 0L

    @Volatile
    private var retryJob: Job? = null

    private var recoveryMarkerOwnedByProcess = false
    private var recoveryNeeded = false
    private var recoveryOwnerGeneration = 0L

    // 通知同一个 id 本来就是覆盖语义，CONFLATED 丢掉积压中的中间帧是正确的；否则 1 Hz
    // 更新在 125 ms 窗口变慢时会在 appScope 上无界堆积。
    private val bypassQueue = Channel<Notification>(Channel.CONFLATED)

    private val xmsfUid: Int? by lazy {
        runCatching {
            context.packageManager.getPackageUid(XMSF_PACKAGE, 0)
        }.getOrNull()
    }

    init {
        appScope.launch {
            var previous = capabilityProvider.current()
            capabilityProvider.capabilityFlow.drop(1).collect { capability ->
                val priorCapability = previous
                previous = capability
                val settings = NotificationSettingsSnapshot.readFrom(storage)
                val priorStyle = resolveStyle(settings, priorCapability)
                val style = resolveStyle(settings, capability)
                if (priorStyle != style) {
                    ProxyServiceBridge.requestNotificationRefresh(NotificationRefreshReason.Capability)
                }
                if (!appliesTo(style, settings)) return@collect
                val wasAvailable = priorCapability.isAuthorizerAvailable(settings.authorizer)
                val isAvailable = capability.isAuthorizerAvailable(settings.authorizer)
                if (wasAvailable == isAvailable) return@collect
                if (isAvailable) clearFailureCooldown()
                sessionMutex.withLock {
                    val authorizer = warmAuthorizer
                    if (authorizer != null && !capability.isAuthorizerAvailable(authorizer)) {
                        warmAuthorizer = null
                    }
                }
                ProxyServiceBridge.requestNotificationRefresh(NotificationRefreshReason.Capability)
            }
        }
        appScope.launch(Dispatchers.IO) {
            for (notification in bypassQueue) {
                try {
                    publishWithWindow(notification)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Super Island notification publish failed", e)
                }
            }
        }
    }

    fun recoverPending() {
        appScope.launch(Dispatchers.IO) {
            windowOperationLock.withLock {
                recoverPersistedWindowIfNeeded()
            }
        }
    }

    fun beginService(): Long = serviceGenerationCounter.incrementAndGet().also {
        activeServiceGeneration = it
    }

    /**
     * 生效样式已经蕴含协议与焦点权限：resolveEffectiveStyle 只有实际可展示时才返回 MiIsland，
     * 因而这里不再重复查询能力。授权方式以用户选择为准；“无特权”不进入绕过队列。
     */
    fun appliesTo(
        style: VpnNotificationStyle,
        settings: NotificationSettingsSnapshot,
    ): Boolean =
        style == VpnNotificationStyle.MiIsland &&
                settings.miIslandBypassRestriction &&
                settings.authorizer != Authorizer.None

    suspend fun ensureReady(): Authorizer? = sessionMutex.withLock {
        val (settings, style) = readBypassState()
        if (!appliesTo(style, settings)) {
            warmAuthorizer = null
            return@withLock null
        }
        val requestedAuthorizer = settings.authorizer
        val cachedAuthorizer = warmAuthorizer
        if (cachedAuthorizer == requestedAuthorizer &&
            capabilityProvider.current().isAuthorizerAvailable(requestedAuthorizer)
        ) {
            return@withLock cachedAuthorizer
        }
        // 这里只让缓存失效，不提前释放 Root ownership：主线程快路径可能刚读到旧缓存，立即释放会让
        // 它在 runBlocking 内重新握手并形成 ANR；旧 handle 由服务结束时的代次门控统一回收。
        warmAuthorizer = null
        if (isCoolingDown()) return@withLock null

        try {
            capabilityProvider.refreshPrivilegeStatusNow(requestedAuthorizer)
            if (!isStillRequested(requestedAuthorizer)) return@withLock null
            if (!capabilityProvider.current().isAuthorizerAvailable(requestedAuthorizer)) {
                rememberBypassFailure("Selected authorizer is unavailable: $requestedAuthorizer")
                return@withLock null
            }
            if (!warmUp(requestedAuthorizer)) {
                rememberBypassFailure("Failed to warm $requestedAuthorizer for notification bypass")
                return@withLock null
            }
            if (!isStillRequested(requestedAuthorizer)) {
                if (requestedAuthorizer == Authorizer.Root) rootProcessSession.releaseService()
                return@withLock null
            }
            warmAuthorizer = requestedAuthorizer
            clearFailureCooldown()
            requestedAuthorizer
        } catch (e: CancellationException) {
            warmAuthorizer = null
            throw e
        } catch (e: Exception) {
            warmAuthorizer = null
            rememberBypassFailure("Notification privilege warm-up failed with $requestedAuthorizer")
            Log.e(TAG, "Notification privilege warm-up failed", e)
            null
        }
    }

    /**
     * 主线程调用的快路径只允许使用已热会话：冷会话会进入 AppProcess.init()，等待只能由主线程投递的
     * 广播，runBlocking 会把这个广播一并堵住并形成 15 秒 ANR；冷会话必须返回 false 交给调用方普通发布。
     */
    fun runWindowIfWarm(expectedAuthorizer: Authorizer, post: () -> Unit): Boolean {
        val authorizer = warmAuthorizer?.takeIf { it == expectedAuthorizer } ?: return false
        val window = runBlocking(Dispatchers.IO) {
            blockXmsfNetwork(authorizer)
        }
        if (window == null) return false
        try {
            post()
        } finally {
            scheduleRestore(window)
        }
        return true
    }

    fun postWithWindow(notification: Notification) {
        if (bypassQueue.trySend(notification).isFailure) {
            Log.e(TAG, "Bypass queue unavailable; publishing without bypass")
            notificationManager.notify(NotificationHelper.NOTIFICATION_ID_VPN, notification)
        }
    }

    fun onSettingsChanged() {
        clearFailureCooldown()
    }

    fun release(generation: Long) {
        if (activeServiceGeneration == generation) activeServiceGeneration = 0L
        appScope.launch(Dispatchers.IO) {
            withContext(NonCancellable) {
                pendingRestore?.takeIf { it.ownerGeneration <= generation }?.job?.cancelAndJoin()
                // 锁序只允许「会话锁 → 窗口锁 → 窗口操作锁」；反向嵌套会死锁。
                sessionMutex.withLock {
                    // XMSF 恢复无条件执行，只有 root handle 的释放受代次门控；停止后立刻启动不能被旧释放误伤。
                    windowMutex.withLock {
                        windowOperationLock.withLock {
                            forceRestoreXmsfNetwork(generation)
                        }
                    }
                    if (activeServiceGeneration == 0L) {
                        warmAuthorizer = null
                        rootProcessSession.releaseService()
                    }
                }
            }
        }
    }

    private suspend fun warmUp(authorizer: Authorizer): Boolean {
        if (authorizer != Authorizer.Root) {
            return privilegedOperationProvider.warmUp(authorizer)
        }
        val owner = rootProcessSession.newOwner()
        if (!rootProcessSession.warmUp(owner)) return false
        // ProcessHookRecycler.delayDuration = 0；没有长期 ownership 时，每次 open/close
        // 都会回收 app_process，1 Hz 动态通知就会退化成每秒 fork 一个 su。
        if (!rootProcessSession.handOffToService(owner)) {
            rootProcessSession.releaseShortcut(owner)
            return false
        }
        if (privilegedOperationProvider.warmUp(authorizer)) return true
        rootProcessSession.releaseService()
        return false
    }

    private fun rememberBypassFailure(message: String) {
        // Shizuku binder/授权和 su 弹窗都可能晚一步，所以必须重试；同时需要冷却，避免每秒弹框。
        bypassFailureAtElapsedRealtime = SystemClock.elapsedRealtime()
        Log.w(TAG, message)
        scheduleRetry()
    }

    private fun isCoolingDown(): Boolean =
        bypassFailureAtElapsedRealtime != 0L &&
                SystemClock.elapsedRealtime() - bypassFailureAtElapsedRealtime < BYPASS_FAILURE_COOLDOWN_MS

    private fun clearFailureCooldown() {
        bypassFailureAtElapsedRealtime = 0L
        retryJob?.cancel()
        retryJob = null
    }

    private fun scheduleRetry() {
        retryJob?.cancel()
        retryJob = appScope.launch(Dispatchers.IO) {
            val now = SystemClock.elapsedRealtime()
            val failedAt = bypassFailureAtElapsedRealtime.takeIf { it != 0L } ?: return@launch
            val retryAt = failedAt + BYPASS_FAILURE_COOLDOWN_MS
            delay((retryAt - now).coerceAtLeast(0L).milliseconds)
            ProxyServiceBridge.requestNotificationRefresh(NotificationRefreshReason.Retry)
        }
    }

    private fun blockXmsfNetwork(authorizer: Authorizer): XmsfWindowLease<DirectPrivilegedSession>? {
        var session: DirectPrivilegedSession? = null
        return runCatching {
            val targetUid = xmsfUid ?: return@runCatching null
            session = privilegedOperationProvider.openSession(authorizer)
            val activeSession = session ?: run {
                Log.e(TAG, "Unable to open privileged session for XMSF bypass")
                rememberBypassFailure("Unable to open $authorizer session for XMSF bypass")
                if (warmAuthorizer == authorizer) warmAuthorizer = null
                return@runCatching null
            }
            val window = windowOperationLock.withLock {
                val ownerGeneration = activeServiceGeneration
                if (ownerGeneration == 0L) return@withLock null
                if (!recoverPersistedWindowIfNeeded(activeSession)) return@withLock null
                val chainEnabled = activeSession.operations.isPackageFirewallChainEnabled()
                    ?: return@withLock null
                val chainWasEnabled = windowRegistry.originalChainState(chainEnabled)
                val hadActiveWindow = windowRegistry.hasActive()
                if (!hadActiveWindow) {
                    val journalReady = if (recoveryMarkerOwnedByProcess) {
                        updateRecoveryBaseline(authorizer, chainWasEnabled)
                    } else {
                        armRecoveryMarker(authorizer, chainWasEnabled, ownerGeneration)
                    }
                    if (!journalReady) return@withLock null
                }
                val result = XmsfFirewallTransaction.block(
                    operations = activeSession.operations,
                    uid = targetUid,
                    chainEnabled = chainEnabled,
                    hadActiveWindow = hadActiveWindow,
                )
                if (!result.blocked) {
                    if (!result.rollbackSucceeded) recoveryNeeded = true
                    return@withLock null
                }
                recoveryNeeded = true
                recoveryOwnerGeneration = ownerGeneration
                windowRegistry.activate(activeSession, chainWasEnabled, ownerGeneration)
            }
            if (window == null) {
                activeSession.close()
                rememberBypassFailure("Unable to block XMSF network with $authorizer")
                if (warmAuthorizer == authorizer) warmAuthorizer = null
                return@runCatching null
            }
            window
        }.onFailure { error ->
            session?.close()
            rememberBypassFailure("XMSF bypass failed with $authorizer")
            if (warmAuthorizer == authorizer) warmAuthorizer = null
            Log.e(TAG, "XMSF bypass failed; publishing without bypass", error)
        }.getOrNull()
    }

    private fun scheduleRestore(window: XmsfWindowLease<DirectPrivilegedSession>) {
        val job = appScope.launch(Dispatchers.IO) {
            try {
                delay(SUPER_ISLAND_BLOCKING_INTERVAL_MS.milliseconds)
            } finally {
                windowMutex.withLock {
                    withContext(NonCancellable) {
                        restoreXmsfWindow(window)
                    }
                }
            }
        }
        pendingRestore = PendingRestore(window.ownerGeneration, job)
    }

    private suspend fun publishWithWindow(notification: Notification) {
        val (settings, style) = readBypassState()
        if (!appliesTo(style, settings)) {
            notificationManager.notify(NotificationHelper.NOTIFICATION_ID_VPN, notification)
            return
        }
        val authorizer = ensureReady()
        if (authorizer == null) {
            notificationManager.notify(NotificationHelper.NOTIFICATION_ID_VPN, notification)
            return
        }

        pendingRestore?.job?.join()
        windowMutex.withLock {
            val window = blockXmsfNetwork(authorizer)
            if (window == null) {
                notificationManager.notify(NotificationHelper.NOTIFICATION_ID_VPN, notification)
                return@withLock
            }
            try {
                notificationManager.notify(NotificationHelper.NOTIFICATION_ID_VPN, notification)
                delay(SUPER_ISLAND_BLOCKING_INTERVAL_MS.milliseconds)
            } finally {
                withContext(NonCancellable) {
                    restoreXmsfWindow(window)
                }
            }
        }
    }

    private fun readBypassState(): Pair<NotificationSettingsSnapshot, VpnNotificationStyle> {
        val settings = NotificationSettingsSnapshot.readFrom(storage)
        return settings to resolveStyle(settings, capabilityProvider.current())
    }

    private fun resolveStyle(
        settings: NotificationSettingsSnapshot,
        capability: DeviceCapability,
    ): VpnNotificationStyle = resolveEffectiveStyle(
        selectedStyle = settings.style,
        miIslandAvailable = capability.miIslandAvailable,
        liveActivityAvailable = capability.liveActivityAvailable,
    )

    private fun armRecoveryMarker(
        authorizer: Authorizer,
        chainWasEnabled: Boolean,
        ownerGeneration: Long,
    ): Boolean {
        val committed = recoveryStore.arm(authorizer, chainWasEnabled)
        if (committed) {
            recoveryMarkerOwnedByProcess = true
            recoveryNeeded = false
            recoveryOwnerGeneration = ownerGeneration
        } else {
            Log.e(TAG, "Unable to persist XMSF recovery marker")
        }
        return committed
    }

    private fun clearRecoveryMarker(): Boolean {
        val committed = recoveryStore.clear()
        if (committed) {
            recoveryMarkerOwnedByProcess = false
            recoveryNeeded = false
            recoveryOwnerGeneration = 0L
        } else {
            Log.e(TAG, "Unable to clear XMSF recovery marker")
        }
        return committed
    }

    private fun updateRecoveryBaseline(authorizer: Authorizer, chainWasEnabled: Boolean): Boolean {
        val updated = recoveryStore.updateBaselineIfChanged(authorizer, chainWasEnabled)
        if (!updated) Log.e(TAG, "Unable to update XMSF recovery baseline")
        return updated
    }

    private fun recoverPersistedWindowIfNeeded(session: DirectPrivilegedSession? = null): Boolean {
        val recovery = recoveryStore.read() ?: return true
        if (windowRegistry.hasActive()) return true
        if (recoveryMarkerOwnedByProcess && !recoveryNeeded) return true

        val recoverySession = session ?: openRecoverySession(recovery.authorizer) ?: return false
        val closeSession = recoverySession !== session
        val restored = restoreNetworking(recoverySession.operations, recovery.chainWasEnabled)
        if (closeSession) recoverySession.close()
        if (!restored) return false

        return if (recoveryMarkerOwnedByProcess) {
            recoveryNeeded = false
            true
        } else {
            clearRecoveryMarker()
        }
    }

    private fun openRecoverySession(stored: Authorizer): DirectPrivilegedSession? {
        val current = NotificationSettingsSnapshot.readFrom(storage).authorizer
        for (authorizer in listOf(stored, current).distinct()) {
            if (authorizer == Authorizer.None) continue
            val session = runCatching { privilegedOperationProvider.openSession(authorizer) }
                .onFailure { Log.e(TAG, "Unable to open $authorizer session for XMSF recovery", it) }
                .getOrNull()
            if (session != null) {
                Log.i(TAG, "Retrying XMSF restore with a new privileged session")
                return session
            }
        }
        Log.e(TAG, "Unable to open privileged session to restore XMSF")
        return null
    }

    private fun forceRestoreXmsfNetwork(maxOwnerGeneration: Long) {
        val active = windowRegistry.takeActive(maxOwnerGeneration)
        if (active == null && windowRegistry.hasActive()) return
        if (active != null) {
            recoveryNeeded = !restoreNetworking(active.value.operations, active.chainWasEnabled)
            active.value.close()
        }
        if (recoveryMarkerOwnedByProcess && recoveryOwnerGeneration > maxOwnerGeneration) return
        if (!recoveryNeeded && recoveryMarkerOwnedByProcess) {
            clearRecoveryMarker()
            return
        }
        recoverPersistedWindowIfNeeded()
        if (!recoveryNeeded && recoveryMarkerOwnedByProcess) clearRecoveryMarker()
    }

    private fun restoreNetworking(
        operations: PrivilegedOperations,
        chainWasEnabled: Boolean,
    ): Boolean {
        val targetUid = xmsfUid ?: return false
        val restored = XmsfFirewallTransaction.restore(operations, targetUid, chainWasEnabled)
        if (restored) {
            Log.d(TAG, "Restored XMSF network after Super Island bypass")
        } else {
            Log.e(TAG, "Failed to restore XMSF network after Super Island bypass")
        }
        return restored
    }

    private fun isStillRequested(authorizer: Authorizer): Boolean {
        val (settings, style) = readBypassState()
        return appliesTo(style, settings) && settings.authorizer == authorizer
    }

    private fun restoreXmsfWindow(window: XmsfWindowLease<DirectPrivilegedSession>) {
        windowOperationLock.withLock {
            if (!windowRegistry.takeIfActive(window)) {
                window.value.close()
                return
            }
            recoveryNeeded = !restoreNetworking(window.value.operations, window.chainWasEnabled)
            window.value.close()
        }
    }

    private companion object {
        private const val TAG = "SuperIslandBypass"
        private const val XMSF_PACKAGE = "com.xiaomi.xmsf"

        // 125 ms 是实测定值；竞态根因在发布结构，不靠延长窗口掩盖，不能当作可调旋钮。
        private const val SUPER_ISLAND_BLOCKING_INTERVAL_MS = 125L
        private const val BYPASS_FAILURE_COOLDOWN_MS = 30_000L
    }

    private data class PendingRestore(
        val ownerGeneration: Long,
        val job: Job,
    )
}
