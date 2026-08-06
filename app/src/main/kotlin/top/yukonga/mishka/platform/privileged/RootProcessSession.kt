package top.yukonga.mishka.platform.privileged

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.yukonga.mishka.platform.privileged.lifecycle.Recyclable
import top.yukonga.mishka.platform.privileged.lifecycle.RecyclerManager
import top.yukonga.mishka.platform.privileged.process.AppProcessTerminal
import top.yukonga.mishka.platform.privileged.recycler.ProcessHookRecycler
import java.util.concurrent.atomic.AtomicLong

const val PROCESS_HOOK_RECYCLER_MANAGER_QUALIFIER = "processHookRecyclerManager"

class RootProcessSession(
    private val processHookRecyclerManager: RecyclerManager<AppProcessTerminal, ProcessHookRecycler>,
) {
    private val mutex = Mutex()
    private val nextOwner = AtomicLong(0L)
    private val owners = mutableSetOf<Long>()
    private var handle: Recyclable<ProcessHookRecycler.HookedUserService>? = null
    private var serviceOwnsHandle = false

    fun newOwner(): Long = nextOwner.incrementAndGet()

    suspend fun warmUp(owner: Long): Boolean = mutex.withLock {
        owners += owner
        if (handle != null) return@withLock true
        try {
            handle = rootRecycler().make()
            Log.i(TAG, "Root app_process warmed for notification bypass")
            true
        } catch (e: Exception) {
            owners -= owner
            Log.e(TAG, "Failed to warm Root app_process", e)
            false
        }
    }

    suspend fun handOffToService(owner: Long): Boolean = mutex.withLock {
        owners -= owner
        if (handle == null) {
            try {
                handle = rootRecycler().make()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to acquire Root app_process for service", e)
                return@withLock false
            }
        }
        serviceOwnsHandle = true
        Log.i(TAG, "Root app_process ownership transferred to service")
        true
    }

    suspend fun releaseShortcut(owner: Long) = mutex.withLock {
        owners -= owner
        releaseIfUnowned("owner $owner")
    }

    suspend fun releaseService() = mutex.withLock {
        serviceOwnsHandle = false
        releaseIfUnowned("service")
    }

    private fun releaseIfUnowned(releasedBy: String) {
        if (owners.isNotEmpty() || serviceOwnsHandle) return
        handle?.close()
        handle = null
        Log.i(TAG, "Root app_process released after $releasedBy ended")
    }

    private fun rootRecycler(): ProcessHookRecycler =
        processHookRecyclerManager.get(AppProcessTerminal.Root)

    private companion object {
        private const val TAG = "RootProcessSession"
    }
}
