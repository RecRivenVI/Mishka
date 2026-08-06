package top.yukonga.mishka.platform.privileged

import android.util.Log
import org.koin.core.context.GlobalContext
import org.koin.core.qualifier.named
import top.yukonga.mishka.platform.privileged.lifecycle.RecyclerManager
import top.yukonga.mishka.platform.privileged.process.AppProcessTerminal
import top.yukonga.mishka.platform.privileged.recycler.ProcessHookRecycler
import top.yukonga.mishka.platform.privileged.recycler.ShizukuHookRecycler
import java.io.Closeable

private const val DIRECT_TAG = "DirectPrivileged"

class DirectPrivilegedSession internal constructor(
    val operations: PrivilegedOperations,
    private val handle: Closeable,
) : Closeable {
    override fun close() = handle.close()
}

fun openDirectPrivileged(authorizer: Authorizer): DirectPrivilegedSession? {
    val koin = GlobalContext.get()
    return when (authorizer) {
        Authorizer.Root -> {
            val recyclerManager = koin.get<
                RecyclerManager<AppProcessTerminal, ProcessHookRecycler>
                >(named(PROCESS_HOOK_RECYCLER_MANAGER_QUALIFIER))
            val handle = recyclerManager.get(AppProcessTerminal.Root).make()
            DirectPrivilegedSession(
                operations = DefaultPrivilegedService.binderWrapped("Root") { binder ->
                    handle.entity.binderWrapper(binder)
                },
                handle = handle,
            )
        }

        Authorizer.Shizuku -> {
            val handle = koin.get<ShizukuHookRecycler>().make()
            DirectPrivilegedSession(
                operations = DefaultPrivilegedService.shizukuHook(),
                handle = handle,
            )
        }

        Authorizer.None -> null
    }
}

fun <T> useDirectPrivileged(
    authorizer: Authorizer,
    action: (PrivilegedOperations) -> T,
): T? {
    val session = openDirectPrivileged(authorizer) ?: return null
    return session.use { action(session.operations) }
}

fun <T> runDirectPrivilegedOrNull(
    authorizer: Authorizer,
    action: (PrivilegedOperations) -> T,
): T? =
    try {
        useDirectPrivileged(authorizer = authorizer, action = action)
    } catch (e: Exception) {
        Log.e(DIRECT_TAG, "Privileged action failed for $authorizer", e)
        null
    }
