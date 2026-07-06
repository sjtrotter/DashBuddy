package cloud.trotter.dashbuddy.domain.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * #526 swap primitive — the pure capability the pickup-placeholder swap guard is built on.
 * [swapTaskAccumulation] / [Job.withSwappedAccumulation] exchange two tasks' accumulated,
 * screen-derived observations while preserving each task's durable slot identity (taskId, jobId,
 * phase, [Task.expectedStoreHint], startedAt), so a mis-bound order slot can be repaired WITHOUT
 * re-minting (ids stay stable for effects/db).
 */
class TaskSwapTest {

    @Test
    fun `swapTaskAccumulation exchanges screen data but preserves slot identity`() {
        val a = Task(
            taskId = "t-A", jobId = "job-1", phase = TaskPhase.PICKUP,
            expectedStoreHint = "Sprouts", storeName = "CVS Pharmacy",
            arrivedAt = 900L, itemsShopped = 3, startedAt = 100L,
        )
        val b = Task(
            taskId = "t-B", jobId = "job-1", phase = TaskPhase.PICKUP,
            expectedStoreHint = "CVS", storeName = "Sprouts Market",
            arrivedAt = null, itemsShopped = 10, startedAt = 200L,
        )

        val (na, nb) = swapTaskAccumulation(a, b)

        // Slot identity is preserved on each side.
        assertEquals("t-A", na.taskId); assertEquals("Sprouts", na.expectedStoreHint); assertEquals(100L, na.startedAt)
        assertEquals("t-B", nb.taskId); assertEquals("CVS", nb.expectedStoreHint); assertEquals(200L, nb.startedAt)
        // Accumulated screen data is exchanged.
        assertEquals("Sprouts Market", na.storeName); assertEquals(10, na.itemsShopped); assertNull(na.arrivedAt)
        assertEquals("CVS Pharmacy", nb.storeName); assertEquals(3, nb.itemsShopped); assertEquals(900L, nb.arrivedAt)
        // phase is slot identity — untouched.
        assertEquals(TaskPhase.PICKUP, na.phase); assertEquals(TaskPhase.PICKUP, nb.phase)
    }

    @Test
    fun `withSwappedAccumulation re-attributes mis-bound data to the correct order slot`() {
        // The Sprouts slot accidentally holds the CVS pickup's data, and vice versa.
        val sproutsSlot = Task(taskId = "t-A", jobId = "job-1", phase = TaskPhase.PICKUP, expectedStoreHint = "Sprouts", storeName = "CVS", startedAt = 100L)
        val cvsSlot = Task(taskId = "t-B", jobId = "job-1", phase = TaskPhase.PICKUP, expectedStoreHint = "CVS", storeName = "Sprouts", startedAt = 100L)
        val job = Job(jobId = "job-1", offerStoreHint = listOf("Sprouts", "CVS"), parentOfferHash = null, startedAt = 100L, tasks = listOf(sproutsSlot, cvsSlot))

        val fixed = job.withSwappedAccumulation("t-A", "t-B")

        assertEquals("Sprouts slot now holds Sprouts data", "Sprouts", fixed.tasks.single { it.taskId == "t-A" }.storeName)
        assertEquals("CVS slot now holds CVS data", "CVS", fixed.tasks.single { it.taskId == "t-B" }.storeName)
        // The slot ids are unchanged (stable for effects/db).
        assertEquals(setOf("t-A", "t-B"), fixed.tasks.mapTo(HashSet()) { it.taskId })
    }

    @Test
    fun `withSwappedAccumulation is a no-op for identical or missing ids`() {
        val t = Task(taskId = "t-A", jobId = "job-1", phase = TaskPhase.PICKUP, startedAt = 100L)
        val job = Job(jobId = "job-1", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 100L, tasks = listOf(t))
        assertSame(job, job.withSwappedAccumulation("t-A", "t-A"))
        assertSame(job, job.withSwappedAccumulation("t-A", "missing"))
    }
}
