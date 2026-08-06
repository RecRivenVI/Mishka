package top.yukonga.mishka.platform.privileged.recycler

import android.content.Context
import android.os.IBinder
import com.rosan.app_process.AppProcess
import top.yukonga.mishka.platform.privileged.lifecycle.Recyclable
import top.yukonga.mishka.platform.privileged.lifecycle.Recycler
import top.yukonga.mishka.platform.privileged.lifecycle.RecyclerManager
import top.yukonga.mishka.platform.privileged.process.AppProcessTerminal
import java.io.Closeable

class ProcessHookRecycler(
    private val terminal: AppProcessTerminal,
    private val context: Context,
    private val appProcessRecyclerManager: RecyclerManager<AppProcessTerminal, AppProcessRecycler>,
) : Recycler<ProcessHookRecycler.HookedUserService>() {
    override val delayDuration: Long = 0L

    class HookedUserService(
        private val appProcessHandle: Recyclable<AppProcess>,
    ) : Closeable {
        fun binderWrapper(binder: IBinder): IBinder =
            appProcessHandle.entity.binderWrapper(binder)

        override fun close() = appProcessHandle.recycle()
    }

    override fun onMake(): HookedUserService {
        val appProcessHandle = appProcessRecyclerManager.get(terminal).make()
        if (!appProcessHandle.entity.init(context)) {
            throw IllegalStateException("Failed to initialize AppProcess for hook mode.")
        }
        return HookedUserService(appProcessHandle)
    }
}
