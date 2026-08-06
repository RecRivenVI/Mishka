package top.yukonga.mishka.platform.privileged.recycler

import com.rosan.app_process.AppProcess
import top.yukonga.mishka.platform.privileged.lifecycle.Recycler
import top.yukonga.mishka.platform.privileged.process.AppProcessTerminal

class AppProcessRecycler(
    private val terminal: AppProcessTerminal,
) : Recycler<AppProcess>() {
    override val delayDuration: Long = 0L

    override fun onMake(): AppProcess = AppProcess.Root().apply {
        if (init()) return@apply
        if (terminal == AppProcessTerminal.Root) {
            throw IllegalStateException("Cannot access su command")
        }
        throw IllegalStateException("AppProcess init failed")
    }
}
