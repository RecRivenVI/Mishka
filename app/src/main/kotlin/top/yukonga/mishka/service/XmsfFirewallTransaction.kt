package top.yukonga.mishka.service

import top.yukonga.mishka.platform.privileged.PrivilegedOperations

internal data class XmsfBlockResult(
    val blocked: Boolean,
    val rollbackSucceeded: Boolean,
)

internal object XmsfFirewallTransaction {
    fun block(
        operations: PrivilegedOperations,
        uid: Int,
        chainEnabled: Boolean,
        hadActiveWindow: Boolean,
    ): XmsfBlockResult {
        if (!chainEnabled && !operations.setPackageFirewallChainEnabled(true)) {
            return XmsfBlockResult(blocked = false, rollbackSucceeded = true)
        }
        if (operations.setPackageNetworkingEnabled(uid, false)) {
            return XmsfBlockResult(blocked = true, rollbackSucceeded = true)
        }
        if (hadActiveWindow) {
            return XmsfBlockResult(blocked = false, rollbackSucceeded = true)
        }
        val uidRestored = operations.setPackageNetworkingEnabled(uid, true)
        val chainRestored = chainEnabled || operations.setPackageFirewallChainEnabled(false)
        return XmsfBlockResult(blocked = false, rollbackSucceeded = uidRestored && chainRestored)
    }

    fun restore(
        operations: PrivilegedOperations,
        uid: Int,
        chainWasEnabled: Boolean,
    ): Boolean {
        val uidRestored = operations.setPackageNetworkingEnabled(uid, true)
        val chainRestored = chainWasEnabled || operations.setPackageFirewallChainEnabled(false)
        return uidRestored && chainRestored
    }
}
