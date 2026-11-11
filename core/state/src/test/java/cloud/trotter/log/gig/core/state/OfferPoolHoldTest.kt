package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.state.AppState
import cloud.trotter.log.gig.domain.state.TaskPhase
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1119 council round 6 (addendum R6-3, R6-4 test 6): a store-less first work frame with a live pool is HELD, not
 * minted bare. The first store-bearing task frame inside the hold (10 s or 3 task frames) links from the held frame,
 * which starts the job and its one task; the cap, or any non-task frame, mints bare exactly as before from the held
 * frame.
 *
 * Non-vacuity (shown against the old production behaviour, see the PR report): on the old code the store-less frame
 * mints a bare job at once, so the store-bearing frame a second later finds a live job and nothing links — the first
 * case fails on `acceptedOffers`.
 */
class OfferPoolHoldTest {

    private fun pooled(): PoolDriver = PoolDriver().step(PF.card(1_000, "hA"), PF.idle(5_000))

    @Test fun `a store-bearing frame 1 s after a store-less one links, the task starting at the held frame`() {
        val held = pooled().step(PF.pickup(10_000, store = null))
        val heldJob = held.region.activeJob
        val heldFrame = held.region.heldWorkFrame
        val d = held.step(PF.pickup(11_000))
        val job = d.region.activeJob!!
        assertEquals(listOf("hA"), job.acceptedOffers.map { it.offerHash })
        assertNull("held, not minted", heldJob)
        assertNotNull(heldFrame)
        assertEquals(10_000L, job.startedAt)
        val task = d.region.activeTask!!
        assertEquals(TaskPhase.PICKUP, task.phase)
        assertEquals(10_000L, task.startedAt)
        assertEquals("one job, one pickup", 1, job.tasks.count { it.phase == TaskPhase.PICKUP })
        assertEquals(10_000L, job.tasks.single { it.taskId == task.taskId }.startedAt)
        assertNull(d.region.heldWorkFrame)
        assertEquals(listOf(AppEventType.OFFER_ACCEPTED), d.outcomes().map { it.type })
    }

    @Test fun `the time cap mints bare from the held frame and the offer keeps waiting`() {
        val d = pooled().step(PF.pickup(10_000, store = null), PF.pickup(20_001))
        val job = d.region.activeJob!!
        assertTrue(job.acceptedOffers.isEmpty())
        assertEquals(10_000L, job.startedAt)
        assertTrue(job.jobId.contains("-10000-"))
        assertEquals(1, d.region.offerPool.size)
        assertTrue(d.outcomes().isEmpty())
    }

    @Test fun `the frame cap mints bare from the held frame`() {
        val d = pooled().step(PF.pickup(10_000, store = null), PF.pickup(11_000, store = null))
        assertNull("two frames still held", d.region.activeJob)
        d.step(PF.pickup(12_000, store = null))
        val job = d.region.activeJob!!
        assertTrue(job.acceptedOffers.isEmpty())
        assertEquals(10_000L, job.startedAt)
        assertEquals(10_000L, d.region.activeTask!!.startedAt)
        assertEquals(1, d.region.offerPool.size)
    }

    @Test fun `a non-task frame during the hold mints bare from the held frame`() {
        val d = pooled().step(PF.pickup(10_000, store = null), PF.idle(11_000))
        assertEquals(10_000L, d.region.activeJob!!.startedAt)
        assertTrue(d.region.activeJob!!.acceptedOffers.isEmpty())
        assertEquals(1, d.region.offerPool.size)
    }

    @Test fun `a hold survives recovery inside its cap and is dropped past it`() {
        val d = pooled().step(PF.pickup(10_000, store = null))
        fun restored(now: Long): AppState =
            StateJson.decodeFromString<AppState>(StateJson.encodeToString(d.state)).recoveryHygiene(now)

        val inside = PoolDriver(restored(now = 10_500)).step(PF.pickup(11_000))
        assertEquals(10_000L, inside.region.activeJob!!.startedAt)
        assertEquals(listOf("hA"), inside.region.activeJob!!.acceptedOffers.map { it.offerHash })

        val past = restored(now = 25_000)
        assertNull(past.regions.platforms.getValue(PF.platform).heldWorkFrame)
        val after = PoolDriver(past).step(PF.pickup(26_000))
        assertEquals(26_000L, after.region.activeJob!!.startedAt)
        assertEquals(listOf("hA"), after.region.activeJob!!.acceptedOffers.map { it.offerHash })
    }
}
