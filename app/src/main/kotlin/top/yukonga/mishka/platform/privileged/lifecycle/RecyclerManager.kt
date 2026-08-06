package top.yukonga.mishka.platform.privileged.lifecycle

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class RecyclerManager<K, V : Closeable>(
    private val factory: (K) -> V,
) : Closeable {
    private val map = ConcurrentHashMap<K, V>()
    private val lock = ReentrantReadWriteLock()

    fun get(key: K): V {
        lock.read { map[key]?.let { return it } }
        return lock.write { map.getOrPut(key) { factory(key) } }
    }

    private fun clear() = lock.write {
        map.values.forEach { runCatching { it.close() } }
        map.clear()
    }

    override fun close() = clear()
}
