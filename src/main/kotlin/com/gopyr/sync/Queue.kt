package com.gopyr.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent-queue abstraction.
 *
 * Current implementation is in-memory + thread-safe (suitable for tests and
 * for apps that persist via their own DataStore/Room layer and re-enqueue on launch).
 * The interface is intentionally storage-agnostic so a Room-backed implementation
 * can replace this without touching SyncEngine.
 */

interface SyncQueue {
    suspend fun enqueue(operation: SyncOperation)
    suspend fun peek(): SyncOperation?
    suspend fun dequeue(): SyncOperation?
    suspend fun update(operation: SyncOperation)
    suspend fun remove(id: String): Boolean
    suspend fun getAll(): List<SyncOperation>
    suspend fun getPending(): List<SyncOperation>
    suspend fun getFailed(): List<SyncOperation>
    suspend fun clear()
    suspend fun size(): Int
}

class InMemorySyncQueue : SyncQueue {

    private val store = ConcurrentHashMap<String, SyncOperation>()
    private val order: ArrayDeque<String> = ArrayDeque()
    private val mutex = Mutex()

    override suspend fun enqueue(operation: SyncOperation) = mutex.withLock {
        store[operation.id] = operation
        if (!order.contains(operation.id)) order.addLast(operation.id)
    }

    override suspend fun peek(): SyncOperation? = mutex.withLock {
        while (order.isNotEmpty()) {
            val id = order.first()
            val op = store[id]
            if (op != null) {
                if (op.nextRetryAt != null && op.nextRetryAt > System.currentTimeMillis()) {
                    return@withLock null // backoff not elapsed
                }
                return@withLock op
            } else {
                order.removeFirst()
            }
        }
        null
    }

    override suspend fun dequeue(): SyncOperation? = mutex.withLock {
        while (order.isNotEmpty()) {
            val id = order.removeFirst()
            val op = store.remove(id)
            if (op != null) return@withLock op
        }
        null
    }

    override suspend fun update(operation: SyncOperation) {
        store[operation.id] = operation
        // maintain order if re-queued after failure
        mutex.withLock {
            if (!order.contains(operation.id) && operation.status == SyncStatus.FAILED) {
                order.addLast(operation.id)
            }
        }
    }

    override suspend fun remove(id: String): Boolean = mutex.withLock {
        store.remove(id) != null && order.remove(id)
    }

    override suspend fun getAll(): List<SyncOperation> = store.values.sortedBy { it.createdAt }

    override suspend fun getPending(): List<SyncOperation> =
        store.values.filter { it.status == SyncStatus.PENDING }.sortedBy { it.createdAt }

    override suspend fun getFailed(): List<SyncOperation> =
        store.values.filter { it.status == SyncStatus.FAILED }

    override suspend fun clear() = mutex.withLock {
        store.clear()
        order.clear()
    }

    override suspend fun size(): Int = store.size

    /**
     * Compute exponential backoff: 2^retryCount seconds, capped at 5 minutes.
     */
    fun backoffMs(retryCount: Int): Long {
        val exp = 1L shl retryCount.coerceAtMost(8) // cap shift
        return (exp * 1000L).coerceAtMost(5 * 60 * 1000L)
    }
}
