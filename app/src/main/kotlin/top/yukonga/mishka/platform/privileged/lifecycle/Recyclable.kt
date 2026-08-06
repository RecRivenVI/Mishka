package top.yukonga.mishka.platform.privileged.lifecycle

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

class Recyclable<T>(
    val entity: T,
    private val onRecycle: () -> Unit,
) : Closeable {
    private val recycled = AtomicBoolean(false)

    fun recycle() {
        if (recycled.compareAndSet(false, true)) onRecycle()
    }

    override fun close() = recycle()
}
