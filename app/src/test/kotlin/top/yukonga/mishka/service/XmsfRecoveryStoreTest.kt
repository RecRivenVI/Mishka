package top.yukonga.mishka.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yukonga.mishka.platform.privileged.Authorizer

class XmsfRecoveryStoreTest {
    @Test
    fun journalRoundTripAndClear() {
        val journal = FakeJournal()
        val store = XmsfRecoveryStore(journal)

        assertTrue(store.arm(Authorizer.Root, chainWasEnabled = false))
        assertEquals(XmsfRecoveryRecord(Authorizer.Root, false), store.read())
        assertTrue(store.clear())
        assertNull(store.read())
    }

    @Test
    fun baselineIsWrittenOnlyWhenItChanges() {
        val journal = FakeJournal()
        val store = XmsfRecoveryStore(journal)
        store.arm(Authorizer.Shizuku, chainWasEnabled = false)
        val writesAfterArm = journal.writeCount

        assertTrue(store.updateBaselineIfChanged(Authorizer.Shizuku, chainWasEnabled = false))
        assertEquals(writesAfterArm, journal.writeCount)
        assertTrue(store.updateBaselineIfChanged(Authorizer.Shizuku, chainWasEnabled = true))
        assertEquals(writesAfterArm + 1, journal.writeCount)
        assertEquals(XmsfRecoveryRecord(Authorizer.Shizuku, true), store.read())
        assertTrue(store.updateBaselineIfChanged(Authorizer.Root, chainWasEnabled = true))
        assertEquals(writesAfterArm + 2, journal.writeCount)
        assertEquals(XmsfRecoveryRecord(Authorizer.Root, true), store.read())
    }

    @Test
    fun corruptJournalUsesConservativeChainState() {
        val journal = FakeJournal(content = byteArrayOf(1, 2, 3))

        assertEquals(
            XmsfRecoveryRecord(Authorizer.None, chainWasEnabled = true),
            XmsfRecoveryStore(journal).read(),
        )
    }

    private class FakeJournal(
        var content: ByteArray? = null,
    ) : XmsfRecoveryJournal {
        var writeCount = 0

        override fun read(): ByteArray? = content

        override fun write(content: ByteArray): Boolean {
            this.content = content
            writeCount += 1
            return true
        }

        override fun clear(): Boolean {
            content = null
            return true
        }
    }
}
