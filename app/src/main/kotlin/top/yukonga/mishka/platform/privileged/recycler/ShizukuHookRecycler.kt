package top.yukonga.mishka.platform.privileged.recycler

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import rikka.shizuku.Shizuku
import top.yukonga.mishka.platform.privileged.lifecycle.Recycler
import top.yukonga.mishka.platform.privileged.requireShizukuPermissionGranted
import java.io.Closeable
import kotlin.time.Duration.Companion.milliseconds

class ShizukuHookRecycler : Recycler<ShizukuHookRecycler.HookedUserService>() {
    class HookedUserService : Closeable {
        override fun close() {
            Log.d(TAG, "close() called, no action needed in hook mode")
        }
    }

    override fun onMake(): HookedUserService = runBlocking {
        requireShizukuPermissionGranted {
            ensureBinderReady()
            HookedUserService()
        }
    }

    private suspend fun ensureBinderReady() {
        repeat(5) {
            if (Shizuku.pingBinder()) return
            delay(100.milliseconds)
        }
    }

    private companion object {
        private const val TAG = "ShizukuHookRecycler"
    }
}
