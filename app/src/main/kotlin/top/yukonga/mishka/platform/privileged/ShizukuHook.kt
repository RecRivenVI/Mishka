package top.yukonga.mishka.platform.privileged

import android.annotation.SuppressLint
import android.content.Context
import android.net.IConnectivityManager
import android.util.Log
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
object ShizukuHook {
    val hookedConnectivityManager: IConnectivityManager by lazy {
        Log.d(TAG, "Creating on-demand hooked IConnectivityManager")
        val originalBinder = SystemServiceHelper.getSystemService(Context.CONNECTIVITY_SERVICE)
        val originalManager = IConnectivityManager.Stub.asInterface(originalBinder)
        IConnectivityManager.Stub.asInterface(
            ShizukuBinderWrapper(originalManager.asBinder()),
        )
    }

    private const val TAG = "ShizukuHook"
}
