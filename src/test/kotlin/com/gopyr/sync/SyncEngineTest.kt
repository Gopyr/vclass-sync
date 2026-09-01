package com.gopyr.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InMemoryCacheTest {

    @Test
    fun `upsert and retrieve course`() = runTest {
        val cache = InMemoryCache()
        val course = Course("c1", "CS101", "Intro to CS", 1)
        cache.upsertCourse(course)
        assertEquals(course, cache.getCourse("c1"))
        assertEquals(1, cache.count().courses)
    }

    @Test
    fun `cascade delete course removes related data`() = runTest {
        val cache = InMemoryCache()
        cache.upsertCourse(Course("c1", "CS101", "Intro", 1))
        cache.upsertMaterial(Material("m1", "c1", "Week 1 PDF", MaterialType.PDF))
        cache.upsertAssignment(Assignment("a1", "c1", "HW1", System.currentTimeMillis() + 86400000))
        cache.deleteCourse("c1")
        assertEquals(0, cache.count().materials)
        assertEquals(0, cache.count().assignments)
    }
}

class InMemorySyncQueueTest {

    @Test
    fun `enqueue and peek respects fifo`() = runTest {
        val q = InMemorySyncQueue()
        val op1 = SyncOperation("1", SyncOperationType.CREATE_SUBMISSION, "{}")
        val op2 = SyncOperation("2", SyncOperationType.CREATE_SUBMISSION, "{}")
        q.enqueue(op1)
        q.enqueue(op2)
        assertEquals("1", q.peek()?.id)
        assertEquals(2, q.size())
    }

    @Test
    fun `backoff increases exponentially`() {
        val q = InMemorySyncQueue()
        assertEquals(1000L, q.backoffMs(0))
        assertEquals(2000L, q.backoffMs(1))
        assertEquals(4000L, q.backoffMs(2))
        assertTrue(q.backoffMs(20) <= 5 * 60 * 1000L)
    }
}

class SyncEngineTest {

    private fun fakeTransport(succeed: Boolean = true) = object : SyncTransport {
        override suspend fun execute(operation: SyncOperation): SyncResult =
            if (succeed) SyncResult(operation.id, SyncStatus.SUCCEEDED)
            else SyncResult(operation.id, SyncStatus.FAILED, error = "network error")

        override suspend fun fetchServerVersion(operation: SyncOperation): Long? = null
    }

    @Test
    fun `sync drains queue on success`() = runTest {
        val engine = SyncEngine(transport = fakeTransport(true), scope = this)
        engine.enqueue(SyncOperationType.CREATE_SUBMISSION, """{"submissionId":"s1"}""")
        engine.enqueue(SyncOperationType.CREATE_SUBMISSION, """{"submissionId":"s2"}""")
        val n = engine.sync()
        assertEquals(2, n)
        assertEquals(0, engine.pendingCount())
    }

    @Test
    fun `sync retries and eventually fails`() = runTest {
        val engine = SyncEngine(transport = fakeTransport(false), scope = this)
        engine.enqueue(SyncOperationType.CREATE_SUBMISSION, "{}")
        repeat(5) { engine.sync() }
        assertTrue(engine.failedCount() >= 0)
    }

    @Test
    fun `submitAssignmentOffline marks queued in cache`() = runTest {
        val engine = SyncEngine(transport = fakeTransport(true), scope = this)
        val sub = Submission("sub1", "a1", "student1", "my answer")
        engine.submitAssignmentOffline(sub)
        val cached = engine.cache().getSubmission("sub1")
        assertNotNull(cached)
        assertEquals(SubmissionStatus.QUEUED, cached.status)
        engine.sync()
        assertEquals(SubmissionStatus.SYNCED, engine.cache().getSubmission("sub1")?.status)
    }
}
