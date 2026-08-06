package top.yukonga.mishka.platform.privileged

import android.annotation.SuppressLint
import android.content.Context
import android.net.IConnectivityManager
import android.os.IBinder
import android.os.ServiceManager

@SuppressLint("PrivateApi")
internal sealed interface PrivilegedRuntime {
    val name: String

    fun connectivityManager(): IConnectivityManager

    data object ShizukuHooked : PrivilegedRuntime {
        override val name: String = "ShizukuHook"

        override fun connectivityManager(): IConnectivityManager =
            ShizukuHook.hookedConnectivityManager
    }

    class BinderWrapped(
        override val name: String,
        private val binderWrapper: (IBinder) -> IBinder,
    ) : PrivilegedRuntime {
        override fun connectivityManager(): IConnectivityManager {
            val original = ServiceManager.getService(Context.CONNECTIVITY_SERVICE)
            return IConnectivityManager.Stub.asInterface(binderWrapper(original))
        }
    }
}
