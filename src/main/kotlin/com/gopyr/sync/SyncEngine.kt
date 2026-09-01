package com.gopyr.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Offline-first Sync Engine.
 *
 * Responsibilities:
 * - Accept mutations while offline -> enqueue + update local cache immediately (optimistic)
 * - Drain the queue when connectivity returns, with retry + exponential backoff
 * - Surface conflicts via [onConflict] callback instead of silently overwriting
 * - Expose [state] / [snapshot] as StateFlow for UI
 *
 * Non-responsibilities (intentionally out of scope):
 * - Actual HTTP, auth, or file upload (delegated to [SyncTransport])
 * - Durable disk persistence (queue/cache are in-memory; see README)
 * - Connectivity detection (caller invokes [sync] or [startPeriodicSync])
 */
class SyncEngine(
    private val queue: SyncQueue = InMemorySyncQueue(),
    private val cache: VClassDao = InMemoryCache(),
    private val transport: SyncTransport,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val conflictStrategy: ConflictStrategy = ConflictStrategy.KEEP_SERVER,
    private val onConflict: (suspend (SyncConflict) -> ConflictStrategy)? = null
) {
    private val _state = MutableStateFlow(SyncState.IDLE)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val _snapshot = MutableStateFlow(SyncSnapshot(SyncState.IDLE, 0, 0, null))
    val snapshot: StateFlow<SyncSnapshot> = _snapshot.asStateFlow()

    private val mutex = Mutex()
    private var periodicJob: Job? = null

    // --- Public API ---

    suspend fun enqueue(type: SyncOperationType, payloadJson: String): SyncOperation {
        val op = SyncOperation(
            id = generateId(),
            type = type,
            payloadJson = payloadJson
        )
        queue.enqueue(op)
        refreshSnapshot()
        return op
    }

    /**
     * Enqueue and optimistically apply to cache.
     * Example: student submits assignment offline -> cache shows QUEUED immediately.
     */
    suspend fun submitAssignmentOffline(submission: Submission): SyncOperation {
        cache.upsertSubmission(submission.copy(status = SubmissionStatus.QUEUED))
        val payload = """{"submissionId":"${submission.id}","assignmentId":"${submission.assignmentId}"}"""
        return enqueue(SyncOperationType.CREATE_SUBMISSION, payload)
    }

    /**
     * Drain queue sequentially. Safe to call concurrently — guarded by Mutex.
     * Returns number of operations that succeeded in this run.
     */
    suspend fun sync(): Int = mutex.withLock {
        if (_state.value == SyncState.SYNCING) return@withLock 0
        _state.value = SyncState.SYNCING
        var succeeded = 0
        try {
            while (true) {
                val op = queue.peek() ?: break
                // mark in-progress
                queue.update(op.copy(status = SyncStatus.IN_PROGRESS))
                refreshSnapshot()

                val result = try {
                    // Optional conflict check before executing
                    val serverVersion = transport.fetchServerVersion(op)
                    if (serverVersion != null && serverVersion > op.createdAt) {
                        val conflict = SyncConflict(op, op.createdAt, serverVersion, "{}")
                        val strategy = onConflict?.invoke(conflict) ?: conflictStrategy
                        when (strategy) {
                            ConflictStrategy.KEEP_SERVER -> {
                                queue.dequeue() // drop local mutation
                                succeeded++ // counted as resolved
                                refreshSnapshot()
                                continue
                            }
                            ConflictStrategy.KEEP_LOCAL -> {} // fall through to execute
                            ConflictStrategy.MERGE_MANUAL -> {
                                queue.update(op.copy(status = SyncStatus.CONFLICT, lastError = "conflict"))
                                break
                            }
                        }
                    }
                    transport.execute(op)
                } catch (e: Exception) {
                    SyncResult(op.id, SyncStatus.FAILED, error = e.message)
                }

                when (result.status) {
                    SyncStatus.SUCCEEDED -> {
                        queue.dequeue()
                        succeeded++
                        // update cache submission status if applicable
                        maybeMarkSynced(op)
                    }
                    SyncStatus.FAILED -> {
                        val nextRetry = if (op.retryCount >= op.maxRetries) null
                        else System.currentTimeMillis() + backoffMs(op.retryCount)
                        val updated = op.copy(
                            status = if (op.retryCount >= op.maxRetries) SyncStatus.FAILED else SyncStatus.PENDING,
                            retryCount = op.retryCount + 1,
                            lastError = result.error,
                            nextRetryAt = nextRetry
                        )
                        if (op.retryCount >= op.maxRetries) {
                            queue.update(updated)
                            break // stop draining; backoff pending
                        } else {
                            queue.update(updated)
                            // re-queue to tail is handled by update(); continue to next op
                            // but if backoff is active, peek() will return null next iteration
                            queue.dequeue()
                            queue.enqueue(updated)
                        }
                    }
                    SyncStatus.CONFLICT -> {
                        queue.update(op.copy(status = SyncStatus.CONFLICT, lastError = result.error))
                        break
                    }
                    else -> {
                        queue.update(op.copy(status = result.status))
                        break
                    }
                }
                refreshSnapshot()
            }
            succeeded
        } finally {
            _state.value = if (succeeded > 0) SyncState.IDLE else {
                val failed = queue.getFailed().isNotEmpty()
                if (failed) SyncState.ERROR else SyncState.IDLE
            }
            refreshSnapshot(overrideLastSync = if (succeeded > 0) System.currentTimeMillis() else null)
        }
    }

    fun startPeriodicSync(intervalMs: Long = 30_000L) {
        periodicJob?.cancel()
        periodicJob = scope.launch {
            while (true) {
                delay(intervalMs)
                try { sync() } catch (_: Exception) { /* keep loop alive */ }
            }
        }
    }

    fun stopPeriodicSync() {
        periodicJob?.cancel()
        periodicJob = null
    }

    suspend fun pendingCount(): Int = queue.getPending().size
    suspend fun failedCount(): Int = queue.getFailed().size
    fun cache(): VClassDao = cache
    fun queue(): SyncQueue = queue

    // --- Internal ---

    private suspend fun refreshSnapshot(overrideLastSync: Long? = null) {
        _snapshot.value = SyncSnapshot(
            state = _state.value,
            pendingCount = queue.getPending().size,
            failedCount = queue.getFailed().size,
            lastSyncAt = overrideLastSync ?: _snapshot.value.lastSyncAt
        )
    }

    private suspend fun maybeMarkSynced(op: SyncOperation) {
        // best-effort: if payload contains submissionId, mark it SYNCED
        val id = Regex("\"submissionId\"\\s*:\\s*\"([^\"]+)\"").find(op.payloadJson)?.groupValues?.get(1)
        if (id != null) {
            cache.getSubmission(id)?.let {
                cache.upsertSubmission(it.copy(status = SubmissionStatus.SYNCED))
            }
        }
    }

    private fun backoffMs(retryCount: Int): Long {
        val exp = 1L shl retryCount.coerceAtMost(8)
        return (exp * 1000L).coerceAtMost(5 * 60 * 1000L)
    }

    private fun generateId(): String =
        "op_${System.currentTimeMillis()}_${(0..9999).random()}"
}
