package top.yukonga.mishka.platform.privileged.lifecycle

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

abstract class Recycler<T : Closeable> : Closeable {
    private val lock = ReentrantLock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var entity: T? = null

    private val referenceCountValue = AtomicInteger(0)

    @Volatile
    private var recycleJob: Job? = null

    @Volatile
    private var closed = false

    protected open val delayDuration: Long = 15_000L

    fun make(): Recyclable<T> = lock.withLock {
        check(!closed) { "Recycler is closed" }
        recycleJob?.cancel()
        recycleJob = null
        val localEntity = entity ?: onMake().also {
            entity = it
            Log.d(TAG, "Entity created")
        }
        referenceCountValue.incrementAndGet()
        Recyclable(localEntity) { decrementAndScheduleRecycle() }
    }

    protected abstract fun onMake(): T

    protected open fun onRecycle() = Unit

    private fun decrementAndScheduleRecycle() {
        lock.withLock {
            val count = referenceCountValue.decrementAndGet()
            if (count > 0 || closed) return
            recycleJob?.cancel()
            recycleJob = scope.launch {
                delay(delayDuration)
                doRecycle(force = false)
            }
        }
    }

    private fun doRecycle(force: Boolean) {
        lock.withLock {
            if (!force && referenceCountValue.get() > 0) return
            recycleJob?.cancel()
            recycleJob = null
            entity?.let {
                runCatching { it.close() }
                    .onFailure { error -> Log.e(TAG, "Error closing entity", error) }
                entity = null
            }
            if (force) referenceCountValue.set(0)
            runCatching { onRecycle() }
                .onFailure { error -> Log.e(TAG, "Error in onRecycle callback", error) }
        }
    }

    override fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            scope.cancel()
            doRecycle(force = true)
        }
    }

    private companion object {
        private const val TAG = "PrivilegedRecycler"
    }
}
