package top.yukonga.mishka.platform.privileged

interface PrivilegedOperations {
    fun isPackageFirewallChainEnabled(): Boolean?
    fun setPackageFirewallChainEnabled(enabled: Boolean): Boolean
    fun setPackageNetworkingEnabled(uid: Int, enabled: Boolean): Boolean
}
