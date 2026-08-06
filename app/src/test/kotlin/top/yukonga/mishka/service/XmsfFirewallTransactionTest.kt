package top.yukonga.mishka.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yukonga.mishka.platform.privileged.PrivilegedOperations

class XmsfFirewallTransactionTest {
    @Test
    fun disabledChainIsEnabledForWindowAndRestoredAfterward() {
        val operations = FakeOperations()

        val blocked = XmsfFirewallTransaction.block(
            operations = operations,
            uid = UID,
            chainEnabled = false,
            hadActiveWindow = false,
        )
        val restored = XmsfFirewallTransaction.restore(operations, UID, chainWasEnabled = false)

        assertTrue(blocked.blocked)
        assertTrue(restored)
        assertEquals(
            listOf("chain:true", "uid:$UID:false", "uid:$UID:true", "chain:false"),
            operations.calls,
        )
    }

    @Test
    fun enabledChainRemainsEnabledAfterRestore() {
        val operations = FakeOperations()

        assertTrue(XmsfFirewallTransaction.restore(operations, UID, chainWasEnabled = true))
        assertEquals(listOf("uid:$UID:true"), operations.calls)
    }

    @Test
    fun failedDenyRollsBackWhenNoWindowWasActive() {
        val operations = FakeOperations(denyResult = false)

        val result = XmsfFirewallTransaction.block(
            operations = operations,
            uid = UID,
            chainEnabled = false,
            hadActiveWindow = false,
        )

        assertFalse(result.blocked)
        assertTrue(result.rollbackSucceeded)
        assertEquals(
            listOf("chain:true", "uid:$UID:false", "uid:$UID:true", "chain:false"),
            operations.calls,
        )
    }

    @Test
    fun failedOverlappingDenyDoesNotRestoreActiveWindow() {
        val operations = FakeOperations(denyResult = false)

        val result = XmsfFirewallTransaction.block(
            operations = operations,
            uid = UID,
            chainEnabled = true,
            hadActiveWindow = true,
        )

        assertFalse(result.blocked)
        assertTrue(result.rollbackSucceeded)
        assertEquals(listOf("uid:$UID:false"), operations.calls)
    }

    private class FakeOperations(
        private val denyResult: Boolean = true,
    ) : PrivilegedOperations {
        val calls = mutableListOf<String>()

        override fun isPackageFirewallChainEnabled(): Boolean = true

        override fun setPackageFirewallChainEnabled(enabled: Boolean): Boolean {
            calls += "chain:$enabled"
            return true
        }

        override fun setPackageNetworkingEnabled(uid: Int, enabled: Boolean): Boolean {
            calls += "uid:$uid:$enabled"
            return enabled || denyResult
        }
    }

    private companion object {
        private const val UID = 10_042
    }
}
