package com.gopyr.sync

import kotlinx.coroutines.flow.StateFlow

/**
 * Core domain models for VClass Offline Sync.
 *
 * Designed around a real Gunadarma e-learning use case:
 * courses, materials, assignments, and submissions that must work offline
 * in low-connectivity campus areas.
 */

// --- Cache entities ---

data class Course(
    val id: String,
    val code: String,
    val name: String,
    val semester: Int,
    val updatedAt: Long = System.currentTimeMillis()
)

data class Material(
    val id: String,
    val courseId: String,
    val title: String,
    val type: MaterialType,
    val url: String? = null,
    val localPath: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

enum class MaterialType { PDF, VIDEO, LINK, SLIDE }

data class Assignment(
    val id: String,
    val courseId: String,
    val title: String,
    val dueAt: Long,
    val description: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

data class Submission(
    val id: String,
    val assignmentId: String,
    val studentId: String,
    val content: String,
    val filePath: String? = null,
    val status: SubmissionStatus = SubmissionStatus.DRAFT,
    val updatedAt: Long = System.currentTimeMillis()
)

enum class SubmissionStatus { DRAFT, QUEUED, SYNCING, SYNCED, FAILED }

// --- Sync primitives ---

enum class SyncOperationType {
    CREATE_SUBMISSION,
    UPDATE_SUBMISSION,
    MARK_MATERIAL_READ,
    ENROLL_COURSE
}

enum class SyncStatus { PENDING, IN_PROGRESS, SUCCEEDED, FAILED, CONFLICT }

data class SyncOperation(
    val id: String,
    val type: SyncOperationType,
    val payloadJson: String,
    val createdAt: Long = System.currentTimeMillis(),
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val status: SyncStatus = SyncStatus.PENDING,
    val lastError: String? = null,
    val nextRetryAt: Long? = null
)

data class SyncResult(
    val operationId: String,
    val status: SyncStatus,
    val serverResponse: String? = null,
    val error: String? = null
)

/**
 * Conflict happens when server version is newer than the queued mutation.
 * Strategy is decided by caller; engine just surfaces it.
 */
data class SyncConflict(
    val operation: SyncOperation,
    val localVersion: Long,
    val serverVersion: Long,
    val serverPayload: String
)

enum class ConflictStrategy { KEEP_LOCAL, KEEP_SERVER, MERGE_MANUAL }
enum class SyncState { IDLE, SYNCING, PAUSED, ERROR }

/**
 * Interface the host app implements to actually hit the network.
 * Keeps the engine platform-agnostic and testable without Android deps.
 */
interface SyncTransport {
    suspend fun execute(operation: SyncOperation): SyncResult
    suspend fun fetchServerVersion(operation: SyncOperation): Long?
}

/**
 * Observable sync state for UI (ViewModel can collect this).
 */
data class SyncSnapshot(
    val state: SyncState,
    val pendingCount: Int,
    val failedCount: Int,
    val lastSyncAt: Long?
)
