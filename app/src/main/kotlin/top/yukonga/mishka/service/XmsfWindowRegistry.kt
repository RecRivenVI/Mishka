package top.yukonga.mishka.service

internal data class XmsfWindowLease<T>(
    val token: Long,
    val value: T,
    val chainWasEnabled: Boolean,
    val ownerGeneration: Long,
)

internal class XmsfWindowRegistry<T> {
    private var nextToken = 0L
    private var active: XmsfWindowLease<T>? = null

    @Synchronized
    fun originalChainState(observedEnabled: Boolean): Boolean =
        active?.chainWasEnabled ?: observedEnabled

    @Synchronized
    fun activate(
        value: T,
        chainWasEnabled: Boolean,
        ownerGeneration: Long,
    ): XmsfWindowLease<T> =
        XmsfWindowLease(++nextToken, value, chainWasEnabled, ownerGeneration).also { active = it }

    @Synchronized
    fun takeIfActive(lease: XmsfWindowLease<T>): Boolean {
        if (active?.token != lease.token) return false
        active = null
        return true
    }

    @Synchronized
    fun takeActive(maxOwnerGeneration: Long = Long.MAX_VALUE): XmsfWindowLease<T>? {
        val current = active ?: return null
        if (current.ownerGeneration > maxOwnerGeneration) return null
        active = null
        return current
    }

    @Synchronized
    fun hasActive(): Boolean = active != null
}
