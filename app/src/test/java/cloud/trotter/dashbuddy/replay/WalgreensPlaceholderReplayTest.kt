package cloud.trotter.dashbuddy.replay

import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.test.util.SessionReplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SessionReplay] regression for the #565 "customer-less dropoff husk" (06-21 Walgreens noon job).
 *
 * Real field sequence (redacted), screen-only — the dropoff placeholder is pre-created on the
 * `OfferPresented → taskFlow` screen transition, so no click injection is needed:
 *  1. `offer_popup`        — the Walgreens shop offer (1 order) → on accept, pre-creates the pickup
 *                            + a customer-TBD placeholder DROPOFF.
 *  2. `pickup_navigation`  — job + placeholder formed; pickup active.
 *  3. `pickup_pre_arrival` — pickup updates.
 *  4. `dropoff_handoff`    — a GENUINE "hand it to customer" handoff screen whose customer name is a
 *                            bare TextView (no resource id) → unparseable → the placeholder dropoff is
 *                            activated **customer-less** (the bubble shows the "the customer" fallback).
 *  5. `dropoff_navigation` — the first frame that parses the customer.
 *
 * The bug: at frame 5 the customer-less placeholder is already the *active* task, so the slice-3
 * resolve path (which excludes the active task) and the resume path (which needs a name match a null
 * placeholder can't have) both miss — and a FRESH dropoff task was minted, orphaning the placeholder
 * as a dead customer-less husk. The fix makes `isStackedDropoffTransition` require a *prior* customer
 * on the active task (a null→present customer is a placeholder's first resolution, not a transition to
 * a different customer), so frame 5 falls through to the same-phase update and resolves onto the
 * placeholder.
 *
 * Earnings were never affected — this job completed exactly once ($8.50). The defect is the orphan
 * task + the transient "the customer" label.
 */
class WalgreensPlaceholderReplayTest {

    private val session = "snapshots/sessions/walgreens_placeholder_2026_06_21"

    @Test
    fun `the dropoff customer resolves onto the pre-created placeholder, no second dropoff minted (#565)`() {
        val steps = SessionReplay.reduce(session)
        println(SessionReplay.trace(steps))

        fun regionAfter(file: String) = steps.first { it.frame?.file == file }
            .stateAfter.regions.platforms[Platform.DoorDash]!!

        val placeholder = regionAfter("04_dropoff_handoff.json").activeTask
        assertNotNull("the handoff frame should activate the pre-created dropoff placeholder", placeholder)
        assertEquals(TaskPhase.DROPOFF, placeholder!!.phase)

        val afterNav = regionAfter("05_dropoff_navigation.json")
        val resolved = afterNav.activeTask
        assertNotNull("a dropoff task is active after the navigation frame", resolved)

        // PRIMARY invariant: the customer-bearing nav frame RESOLVES onto the same placeholder task
        // (same taskId), it does NOT mint a fresh task.
        assertEquals(
            "the nav frame must resolve onto the placeholder, not re-mint a new dropoff",
            placeholder.taskId, resolved!!.taskId,
        )
        assertNotNull("the resolved placeholder now carries the real customer", resolved.customerNameHash)

        // No dead customer-less dropoff husk left behind (the orphan the bug created in recentTasks).
        val husks = (listOfNotNull(afterNav.activeTask) + afterNav.recentTasks)
            .filter { it.phase == TaskPhase.DROPOFF && it.customerNameHash == null }
        assertTrue(
            "no customer-less dropoff husk should linger (found: ${husks.map { it.taskId }})",
            husks.isEmpty(),
        )
    }
}
