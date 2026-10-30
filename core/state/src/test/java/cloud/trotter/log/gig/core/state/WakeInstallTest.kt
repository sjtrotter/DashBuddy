package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.state.DestructiveKind
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.PendingDestructive
import cloud.trotter.log.gig.domain.state.PendingModeResume
import cloud.trotter.log.gig.domain.state.PendingSessionPay
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeInstallTest {
    private val region = PlatformRegion(Platform.DoorDash)

    private fun initial() = region.withWakeIdIfDeadlineMoved(null, WindowStart.Live(100L)) { id ->
        PendingDestructive(DestructiveKind.SESSION_END, since = 100L, deadline = 200L, wakeId = id)
    }

    @Test
    fun `live keep move and kind replacement preserve mint counts and accounting`() {
        val first = initial()
        val restored = first.withWakeIdIfDeadlineMoved(first.pendingDestructive, WindowStart.Restored(120L)) { id ->
            first.pendingDestructive!!.copy(wakeId = id)
        }
        val previous = restored.pendingDestructive!!
        val kept = restored.withWakeIdIfDeadlineMoved(previous, WindowStart.Live(130L)) { id ->
            previous.copy(authoritative = true, wakeId = id)
        }
        assertEquals(2L, kept.wakeSeq)
        assertEquals(2L, kept.pendingDestructive!!.wakeId.value)
        assertEquals(100L, kept.pendingDestructive!!.windowFrom)
        assertEquals(120L, kept.pendingDestructive!!.servedFrom)

        val moved = kept.withWakeIdIfDeadlineMoved(kept.pendingDestructive, WindowStart.Live(140L)) { id ->
            kept.pendingDestructive!!.copy(deadline = 180L, wakeId = id)
        }
        assertEquals(3L, moved.wakeSeq)
        assertEquals(3L, moved.pendingDestructive!!.wakeId.value)
        assertEquals(140L, moved.pendingDestructive!!.windowFrom)
        assertNull(moved.pendingDestructive!!.servedFrom)
        assertEquals(100L, moved.pendingDestructive!!.since)

        val replaced = moved.withWakeIdIfDeadlineMoved(moved.pendingDestructive, WindowStart.Live(150L)) { id ->
            PendingDestructive(DestructiveKind.TASK_RETIRE, since = 150L, deadline = 180L, wakeId = id)
        }
        assertEquals(4L, replaced.wakeSeq)
        assertEquals(4L, replaced.pendingDestructive!!.wakeId.value)
        assertEquals(150L, replaced.pendingDestructive!!.windowFrom)
    }

    @Test
    fun `restored equal deadline always mints and retains the live anchor`() {
        val first = initial()
        val before = first.pendingDestructive!!
        val restored = first.withWakeIdIfDeadlineMoved(before, WindowStart.Restored(100L)) { id ->
            before.copy(deadline = before.deadline, wakeId = id)
        }
        assertEquals(2L, restored.wakeSeq)
        assertEquals(2L, restored.pendingDestructive!!.wakeId.value)
        assertEquals(before.deadline, restored.pendingDestructive!!.deadline)
        assertEquals(before.windowFrom, restored.pendingDestructive!!.windowFrom)
        assertEquals(100L, restored.pendingDestructive!!.servedFrom)
    }

    @Test
    fun `mode resume keeps equal deadlines and mints moved deadlines`() {
        val (first, pending) = region.modeResumeWindow(null) { id ->
            PendingModeResume(since = 100L, deadline = 200L, wakeId = id)
        }
        val (kept, same) = first.modeResumeWindow(pending) { id -> pending.copy(wakeId = id) }
        assertEquals(1L, kept.wakeSeq)
        assertEquals(1L, same.wakeId.value)
        val (moved, next) = kept.modeResumeWindow(same) { id -> same.copy(deadline = 250L, wakeId = id) }
        assertEquals(2L, moved.wakeSeq)
        assertEquals(2L, next.wakeId.value)
    }

    @Test
    fun `session pay and pause safety always mint in call order at equal deadlines`() {
        val (first, park) = region.sessionPayWindow { id ->
            PendingSessionPay(12.0, since = 100L, deadline = 200L, flow = Flow.Idle, wakeId = id)
        }
        val (second, replacement) = first.sessionPayWindow { id -> park.copy(value = 15.0, wakeId = id) }
        val (third, safety) = second.pauseSafetyWindow(200L)
        val (fourth, replacementSafety) = third.pauseSafetyWindow(200L)
        assertEquals(listOf(1L, 2L, 3L, 4L), listOf(park, replacement, safety, replacementSafety).map { it.wakeId.value })
        assertEquals(4L, fourth.wakeSeq)
        assertNull("the gate still owns the slot", fourth.pendingSessionPay)
    }
}
