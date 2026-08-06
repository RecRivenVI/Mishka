package top.yukonga.mishka.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XmsfWindowRegistryTest {
    @Test
    fun staleWindowCannotRestoreNewWindow() {
        val registry = XmsfWindowRegistry<String>()
        val first = registry.activate("first", chainWasEnabled = false, ownerGeneration = 1)
        val second = registry.activate("second", chainWasEnabled = false, ownerGeneration = 1)

        assertFalse(registry.takeIfActive(first))
        assertTrue(registry.takeIfActive(second))
        assertFalse(registry.hasActive())
    }

    @Test
    fun overlappingWindowInheritsOriginalChainState() {
        val registry = XmsfWindowRegistry<String>()
        registry.activate("first", chainWasEnabled = false, ownerGeneration = 1)

        assertFalse(registry.originalChainState(observedEnabled = true))
    }

    @Test
    fun forceRestoreTakesOnlyLatestWindow() {
        val registry = XmsfWindowRegistry<String>()
        registry.activate("first", chainWasEnabled = true, ownerGeneration = 1)
        val second = registry.activate("second", chainWasEnabled = true, ownerGeneration = 2)

        assertEquals(second, registry.takeActive())
        assertNull(registry.takeActive())
    }

    @Test
    fun oldReleaseCannotTakeNewGenerationWindow() {
        val registry = XmsfWindowRegistry<String>()
        val current = registry.activate("new", chainWasEnabled = true, ownerGeneration = 2)

        assertNull(registry.takeActive(maxOwnerGeneration = 1))
        assertEquals(current, registry.takeActive(maxOwnerGeneration = 2))
    }
}
