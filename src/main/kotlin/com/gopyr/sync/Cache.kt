package com.gopyr.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Room-like in-memory cache.
 *
 * Why in-memory instead of real Room?
 * - Library module: avoids forcing `ksp` + `room-runtime` on consumers.
 * - Demo / portfolio: runs in plain JVM tests without Android instrumentation.
 * - Drop-in: interface mirrors a Room DAO so swapping to real Room is mechanical.
 *
 * Thread-safe via ConcurrentHashMap + Mutex for multi-key transactions.
 * NOT durable: process death wipes data. Host app should hydrate from
 * DataStore/Room if persistence across restarts is needed (see README limitations).
 */

interface VClassDao {
    // Course
    suspend fun upsertCourse(course: Course)
    suspend fun getCourse(id: String): Course?
    suspend fun getAllCourses(): List<Course>
    suspend fun deleteCourse(id: String)

    // Material
    suspend fun upsertMaterial(material: Material)
    suspend fun getMaterialsByCourse(courseId: String): List<Material>
    suspend fun getMaterial(id: String): Material?

    // Assignment
    suspend fun upsertAssignment(assignment: Assignment)
    suspend fun getAssignmentsByCourse(courseId: String): List<Assignment>

    // Submission
    suspend fun upsertSubmission(submission: Submission)
    suspend fun getSubmission(id: String): Submission?
    suspend fun getSubmissionsByAssignment(assignmentId: String): List<Submission>
    suspend fun getPendingSubmissions(): List<Submission>

    // Meta
    suspend fun clearAll()
    suspend fun count(): CacheStats
}

data class CacheStats(
    val courses: Int,
    val materials: Int,
    val assignments: Int,
    val submissions: Int
)

class InMemoryCache : VClassDao {

    private val courses = ConcurrentHashMap<String, Course>()
    private val materials = ConcurrentHashMap<String, Material>()
    private val assignments = ConcurrentHashMap<String, Assignment>()
    private val submissions = ConcurrentHashMap<String, Submission>()
    private val mutex = Mutex()

    // --- Course ---

    override suspend fun upsertCourse(course: Course) {
        courses[course.id] = course
    }

    override suspend fun getCourse(id: String): Course? = courses[id]

    override suspend fun getAllCourses(): List<Course> = courses.values.sortedBy { it.code }

    override suspend fun deleteCourse(id: String) {
        mutex.withLock {
            courses.remove(id)
            // cascade delete related rows (Room would do this via FK)
            materials.entries.removeIf { it.value.courseId == id }
            assignments.entries.removeIf { it.value.courseId == id }
        }
    }

    // --- Material ---

    override suspend fun upsertMaterial(material: Material) {
        materials[material.id] = material
    }

    override suspend fun getMaterialsByCourse(courseId: String): List<Material> =
        materials.values.filter { it.courseId == courseId }.sortedBy { it.title }

    override suspend fun getMaterial(id: String): Material? = materials[id]

    // --- Assignment ---

    override suspend fun upsertAssignment(assignment: Assignment) {
        assignments[assignment.id] = assignment
    }

    override suspend fun getAssignmentsByCourse(courseId: String): List<Assignment> =
        assignments.values.filter { it.courseId == courseId }.sortedBy { it.dueAt }

    // --- Submission ---

    override suspend fun upsertSubmission(submission: Submission) {
        submissions[submission.id] = submission
    }

    override suspend fun getSubmission(id: String): Submission? = submissions[id]

    override suspend fun getSubmissionsByAssignment(assignmentId: String): List<Submission> =
        submissions.values.filter { it.assignmentId == assignmentId }

    override suspend fun getPendingSubmissions(): List<Submission> =
        submissions.values.filter { it.status == SubmissionStatus.QUEUED || it.status == SubmissionStatus.FAILED }

    // --- Meta ---

    override suspend fun clearAll() {
        mutex.withLock {
            courses.clear()
            materials.clear()
            assignments.clear()
            submissions.clear()
        }
    }

    override suspend fun count(): CacheStats = CacheStats(
        courses = courses.size,
        materials = materials.size,
        assignments = assignments.size,
        submissions = submissions.size
    )
}
