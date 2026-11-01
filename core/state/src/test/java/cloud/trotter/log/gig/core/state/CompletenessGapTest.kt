package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.state.Job
import cloud.trotter.log.gig.domain.state.Task
import cloud.trotter.log.gig.domain.state.TaskPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1458: [completenessGap] — why a job is not judged physically complete — is the diagnostic sibling
 * of [isJobPhysicallyComplete]. One table row per gate of each arm (the strict arm's outstanding
 * placeholder, unevidenced finish, unassigned drop and zero-dropoff job; every #749 coverage gate in
 * evaluation order), then an exhaustive agreement sweep: the gap is null exactly when the predicate is
 * true.
 */
class CompletenessGapTest {

    private val jobId = "job"

    private fun drop(
        id: String,
        customer: String? = null,
        completedAt: Long? = null,
        arrivedAt: Long? = null,
        completionStepsAt: Long? = null,
        unassignedAt: Long? = null,
    ) = Task(
        taskId = id, jobId = jobId, phase = TaskPhase.DROPOFF, customerNameHash = customer, startedAt = 100L,
        completedAt = completedAt, arrivedAt = arrivedAt, completionStepsAt = completionStepsAt,
        unassignedAt = unassignedAt,
    )

    private fun delivered(id: String, customer: String) = drop(id, customer, completedAt = 900L, arrivedAt = 800L)

    private fun pickup(id: String, customer: String?, confirmed: Boolean = true) = Task(
        taskId = id, jobId = jobId, phase = TaskPhase.PICKUP, customerNameHash = customer, startedAt = 100L,
        arrivedAt = 200L, completedAt = if (confirmed) 300L else null,
    )

    private fun job(vararg tasks: Task) = Job(
        jobId = jobId, offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 50L, tasks = tasks.toList(),
    )

    private data class Case(
        val name: String,
        val job: Job,
        val recent: List<Task>,
        val justRetired: Task? = null,
        val expected: CompletenessGap?,
    )

    private val tbd = drop("d2") // a customer-TBD placeholder that never activated

    private val cases = listOf(
        // ---- complete: the gap is null ----
        Case("strict arm holds", job(delivered("d1", "c1")), recent = listOf(delivered("d1", "c1")), expected = null),
        Case(
            "strict arm holds on the just-retired drop over a stale mirror",
            job(drop("d1", "c1")), recent = emptyList(), justRetired = delivered("d1", "c1"), expected = null,
        ),
        Case(
            "coverage arm holds (#749 same-customer two-store job)",
            job(drop("d1", "c1"), tbd, pickup("p1", "c1"), pickup("p2", "c1")),
            recent = listOf(delivered("d1", "c1"), pickup("p1", "c1"), pickup("p2", "c1")),
            expected = null,
        ),
        // ---- strict-arm gates (coverage also fails, on its first gate) ----
        Case(
            "outstanding: a placeholder never activated",
            job(drop("d1", "c1"), tbd), recent = listOf(delivered("d1", "c1")),
            expected = CompletenessGap(1, 0, 1, CoverageGate.NO_PICKUP),
        ),
        Case(
            "outstanding: the drop is still live (not retired)",
            job(drop("d1", "c1")), recent = emptyList(),
            expected = CompletenessGap(1, 0, 0, CoverageGate.NO_PICKUP),
        ),
        Case(
            "unevidenced: retired with no arrival and no completion tap",
            job(drop("d1", "c1")), recent = listOf(drop("d1", "c1", completedAt = 900L)),
            justRetired = drop("d1", "c1", completedAt = 900L),
            expected = CompletenessGap(1, 1, 0, CoverageGate.NO_PICKUP),
        ),
        Case(
            "a completion tap is evidence (#1315)",
            job(drop("d1", "c1")), recent = listOf(drop("d1", "c1", completedAt = 900L, completionStepsAt = 850L)),
            expected = null,
        ),
        Case(
            "unassigned: a retro-marked drop is never delivered (#752)",
            job(drop("d1", "c1"), drop("d2", "c2")),
            recent = listOf(
                delivered("d1", "c1"),
                drop("d2", "c2", completedAt = 900L, arrivedAt = 800L, unassignedAt = 950L),
            ),
            expected = CompletenessGap(1, 0, 1, CoverageGate.NO_PICKUP),
        ),
        Case(
            "zero dropoffs: a pickup-only job never qualifies",
            job(pickup("p1", "c1")), recent = listOf(pickup("p1", "c1")),
            expected = CompletenessGap(0, 0, 0, CoverageGate.PICKUP_DROPOFF_MISMATCH),
        ),
        // ---- coverage-arm gates (the strict arm fails on the TBD placeholder) ----
        Case(
            "coverage: no pickup placeholder",
            job(drop("d1", "c1"), tbd), recent = listOf(delivered("d1", "c1")),
            expected = CompletenessGap(1, 0, 1, CoverageGate.NO_PICKUP),
        ),
        Case(
            "coverage: one pickup for two drops (same-store stack)",
            job(drop("d1", "c1"), tbd, pickup("p1", "c1")), recent = listOf(delivered("d1", "c1"), pickup("p1", "c1")),
            expected = CompletenessGap(1, 0, 1, CoverageGate.PICKUP_DROPOFF_MISMATCH),
        ),
        Case(
            "coverage: a pickup resolved no customer hash",
            job(drop("d1", "c1"), tbd, pickup("p1", "c1"), pickup("p2", null)),
            recent = listOf(delivered("d1", "c1"), pickup("p1", "c1"), pickup("p2", null)),
            expected = CompletenessGap(1, 0, 1, CoverageGate.PICKUP_UNHASHED),
        ),
        Case(
            "coverage: a pickup never confirmed",
            job(drop("d1", "c1"), tbd, pickup("p1", "c1"), pickup("p2", "c1", confirmed = false)),
            recent = listOf(delivered("d1", "c1"), pickup("p1", "c1")),
            expected = CompletenessGap(1, 0, 1, CoverageGate.PICKUP_UNCONFIRMED),
        ),
        Case(
            "coverage: no drop finished with evidence",
            job(drop("d1", "c1"), tbd, pickup("p1", "c1"), pickup("p2", "c1")),
            recent = listOf(pickup("p1", "c1"), pickup("p2", "c1")),
            expected = CompletenessGap(2, 0, 0, CoverageGate.NO_FINISHED_DROP),
        ),
        Case(
            "coverage: a pickup's customer has no finished drop",
            job(drop("d1", "c1"), tbd, pickup("p1", "c1"), pickup("p2", "c2")),
            recent = listOf(delivered("d1", "c1"), pickup("p1", "c1"), pickup("p2", "c2")),
            expected = CompletenessGap(1, 0, 1, CoverageGate.CUSTOMER_NOT_FINISHED),
        ),
        Case(
            "coverage: an activated drop's customer has no finished drop",
            job(drop("d1", "c1"), drop("d2", "c9"), pickup("p1", "c1"), pickup("p2", "c1")),
            recent = listOf(delivered("d1", "c1"), pickup("p1", "c1"), pickup("p2", "c1")),
            expected = CompletenessGap(1, 0, 1, CoverageGate.DROP_NOT_FINISHED),
        ),
    )

    @Test
    fun `each gate of each arm reports its own reason`() {
        cases.forEach { c ->
            assertEquals(c.name, c.expected, completenessGap(c.job, c.recent, c.justRetired))
        }
    }

    @Test
    fun `the table agrees with the predicate`() {
        cases.forEach { c ->
            assertEquals(
                c.name,
                isJobPhysicallyComplete(c.job, c.recent, c.justRetired),
                completenessGap(c.job, c.recent, c.justRetired) == null,
            )
        }
    }

    /**
     * Exhaustive over a small space: two dropoff slots (each TBD, live, delivered, finished without
     * evidence, tapped or unassigned, over two customers), zero to two pickups (each hashed to one of the
     * customers or unhashed, confirmed or not), with and without the first drop as the just-retired copy.
     */
    @Test
    fun `the gap is null exactly when the predicate is true, across every small job shape`() {
        val customers = listOf("c1", "c2")
        fun dropStates(id: String): List<Pair<Task, Task?>> = buildList {
            add(drop(id) to null) // TBD placeholder, never active
            customers.forEach { c ->
                add(drop(id, c) to null) // live, never retired
                add(drop(id, c) to delivered(id, c))
                add(drop(id, c) to drop(id, c, completedAt = 900L))
                add(drop(id, c) to drop(id, c, completedAt = 900L, completionStepsAt = 850L))
                add(drop(id, c) to drop(id, c, completedAt = 900L, arrivedAt = 800L, unassignedAt = 950L))
            }
        }
        val pickupStates: List<Task?> = listOf(null) + listOf("c1", "c2", null).flatMap { c ->
            listOf(true, false).map { confirmed -> pickup("p", c, confirmed) }
        }
        var shapes = 0
        var complete = 0
        for ((m1, r1) in dropStates("d1")) for ((m2, r2) in dropStates("d2")) {
            for (pa in pickupStates) for (pb in pickupStates) {
                val pickups = listOfNotNull(pa?.copy(taskId = "p1"), pb?.copy(taskId = "p2"))
                val job = job(*(listOf(m1, m2) + pickups).toTypedArray())
                val recent = listOfNotNull(r1, r2) + pickups.filter { it.completedAt != null }
                for (justRetired in listOf(null, r1)) {
                    val predicate = isJobPhysicallyComplete(job, recent, justRetired)
                    val gap = completenessGap(job, recent, justRetired)
                    assertEquals("shape #$shapes: $job / $recent / $justRetired", predicate, gap == null)
                    shapes++
                    if (predicate) complete++
                }
            }
        }
        assertTrue("the sweep reaches complete shapes ($complete of $shapes)", complete > 0)
        assertTrue("the sweep reaches open shapes", complete < shapes)
        assertNull(completenessGap(job(delivered("d1", "c1")), listOf(delivered("d1", "c1")), null))
    }
}
