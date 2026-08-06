package top.yukonga.mishka.platform.privileged

import android.annotation.SuppressLint
import android.net.IConnectivityManager
import android.os.IBinder
import android.util.Log

@SuppressLint("PrivateApi")
class DefaultPrivilegedService private constructor(
    private val runtime: PrivilegedRuntime,
) : PrivilegedOperations {
    companion object {
        private const val TAG = "PrivilegedService"
        private const val FIREWALL_CHAIN_OEM_DENY_3 = 9
        private const val FIREWALL_RULE_DEFAULT = 0
        private const val FIREWALL_RULE_DENY = 2

        fun shizukuHook(): DefaultPrivilegedService =
            DefaultPrivilegedService(PrivilegedRuntime.ShizukuHooked)

        fun binderWrapped(
            name: String,
            binderWrapper: (IBinder) -> IBinder,
        ): DefaultPrivilegedService =
            DefaultPrivilegedService(PrivilegedRuntime.BinderWrapped(name, binderWrapper))
    }

    private val connectivityManager: IConnectivityManager by lazy {
        runtime.connectivityManager()
    }

    override fun isPackageFirewallChainEnabled(): Boolean? =
        try {
            connectivityManager.getFirewallChainEnabled(FIREWALL_CHAIN_OEM_DENY_3)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query firewall chain via ${runtime.name}", e)
            null
        }

    override fun setPackageFirewallChainEnabled(enabled: Boolean): Boolean =
        try {
            connectivityManager.setFirewallChainEnabled(FIREWALL_CHAIN_OEM_DENY_3, enabled)
            Log.d(TAG, "Set package firewall chain to $enabled via ${runtime.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set firewall chain via ${runtime.name}", e)
            false
        }

    override fun setPackageNetworkingEnabled(uid: Int, enabled: Boolean): Boolean =
        try {
            val rule = if (enabled) FIREWALL_RULE_DEFAULT else FIREWALL_RULE_DENY
            connectivityManager.setUidFirewallRule(FIREWALL_CHAIN_OEM_DENY_3, uid, rule)
            Log.d(TAG, "Set UID $uid networking to $enabled via ${runtime.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set networking via ${runtime.name}", e)
            false
        }
}
