package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPay
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPayItem
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingDestructive
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Regions
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Every deadline-bearing pending the STEPPERS create carries a usable generation** (#1054 round 6).
 *
 * `wakeId` keeps a kotlinx default of `0` so pre-round-5 snapshots decode, which means a forgotten
 * mint is silent: the pending looks fine, its arm carries id 0, and `isWakeFor` refuses id 0 on both
 * sides — so its own fire can never commit it and, since round 4 deleted the early-wake re-arm,
 * nothing rescues it. That is the original #1054 bug, and round 5 shipped it for `TASK_RETIRE`
 * because the two `TaskLifecycle` constructors were missed in the rollout.
 *
 * A grep cannot notice the next omission; this can. Each case drives a REAL stepper path to the
 * point where the pending exists, and asserts only that its generation is non-zero — the behaviour
 * of each window is pinned by its own suite. Anything new that arms a deadline belongs here.
 */
class WakeIdMintCoverageTest {

    private val machine = StateMachine(
        FlowRegionStepper(), PlatformRegionStepper(),
        CrossPlatformRegionStepper(), TransitionPolicy(), EffectMap(),
    )
    private val platform = Platform.DoorDash

    private fun state(region: PlatformRegion, flow: Flow = Flow.Idle) = AppState(
        regions = Regions(
            flow = FlowRegion(flow = flow, activePlatform = platform),
            platforms = mapOf(platform to region),
        ),
    )

    private fun region(mode: Mode = Mode.Online, session: Session? = Session("s1", startedAt = 100L)) =
        PlatformRegion(platform = platform, mode = mode, session = session, lastActedFlow = Flow.Idle)

    private fun dashing() = region().copy(
        activeJob = Job("job-1", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 200L),
        activeTask = Task(
            taskId = "task-1", jobId = "job-1", phase = TaskPhase.DROPOFF,
            storeName = "H-E-B", startedAt = 300L,
        ),
        lastActedFlow = Flow.TaskDropoffArrived,
    )

    private fun screen(
        at: Long,
        flow: Flow?,
        modeHint: Mode?,
        parsed: ParsedFields = ParsedFields.None,
        ruleId: String = "doordash.screen.test",
    ) = Observation.Screen(
        timestamp = at, captureId = null, ruleId = ruleId,
        metadata = ReplayMetadata.EMPTY, flow = flow, modeHint = modeHint, parsed = parsed,
    )

    private fun step(region: PlatformRegion, flow: Flow, obs: Observation) =
        machine.step(state(region, flow), obs).newState.regions.platforms.getValue(platform)

    // ---- pendingDestructive ----

    @Test
    fun `the Offline SESSION_END grace is minted`() {
        val armed = step(
            region(), Flow.Idle,
            screen(10_000L, Flow.Idle, Mode.Offline, ParsedFields.IdleFields()),
        )
        assertMinted(armed.pendingDestructive, "offline SESSION_END")
    }

    @Test
    fun `the dash-summary SESSION_END grace is minted`() {
        // NOTE (Astra, round 6): starting Online and supplying Offline reaches the summary arm via
        // the ordinary offline end, i.e. through the TIGHTEN branch. That is a real path and stays,
        // but it is NOT the fresh constructor — see the next case, which starts with nothing armed.
        val armed = step(
            region(), Flow.Idle,
            screen(
                10_000L, Flow.SessionEnded, Mode.Offline,
                ParsedFields.SessionEndedFields(totalEarnings = 25.0),
            ),
        )
        assertMinted(armed.pendingDestructive, "summary SESSION_END")
    }

    @Test
    fun `the FRESH summary constructor is minted`() {
        // The `else` branch of the summary arm — reached only when nothing destructive stands.
        // A region that is already Offline supplies no mode transition, so no offline end is armed
        // first and the summary constructs its pending outright.
        val armed = step(
            region(mode = Mode.Offline), Flow.Idle,
            screen(
                10_000L, Flow.SessionEnded, Mode.Offline,
                ParsedFields.SessionEndedFields(totalEarnings = 25.0),
            ),
        )
        assertMinted(armed.pendingDestructive, "fresh summary SESSION_END")
        assertEquals(
            "and it is authoritative — proving the summary constructor, not the offline one",
            true,
            armed.pendingDestructive!!.authoritative,
        )
    }

    @Test
    fun `a destructive KIND replacement is minted, not inherited`() {
        // Round 7. A summary leaves an authoritative SESSION_END; a receipt then replaces it with a
        // TASK_RETIRE. Even when the two deadlines coincide, the replacement must take a fresh
        // generation — otherwise the end's own timer commits the retire.
        val ended = step(
            dashing(), Flow.TaskDropoffArrived,
            screen(
                10_000L, Flow.SessionEnded, Mode.Offline,
                ParsedFields.SessionEndedFields(totalEarnings = 25.0),
            ),
        )
        val end = ended.pendingDestructive!!
        assertEquals(DestructiveKind.SESSION_END, end.kind)

        val replaced = step(
            ended.copy(mode = Mode.Online), Flow.SessionEnded,
            screen(
                end.deadline - 2_500L, Flow.PostTask, Mode.Online,
                ParsedFields.PostTaskFields(
                    totalPay = 12.00,
                    parsedPay = ParsedPay(listOf(ParsedPayItem("Base Pay", 12.0)), emptyList()),
                ),
                ruleId = "doordash.screen.delivery_summary",
            ),
        ).pendingDestructive!!

        assertEquals(DestructiveKind.TASK_RETIRE, replaced.kind)
        assertEquals("the deadlines really do coincide", end.deadline, replaced.deadline)
        assertTrue("and the replacement never inherits", replaced.wakeId != end.wakeId)
    }

    @Test
    fun `the PostTask receipt TASK_RETIRE is minted`() {
        // The site round 5 missed. Collapsed and expanded both, since they take different windows.
        for (parsedPay in listOf(null, ParsedPay(listOf(ParsedPayItem("Base Pay", 12.0)), emptyList()))) {
            val armed = step(
                dashing(), Flow.TaskDropoffArrived,
                screen(
                    10_000L, Flow.PostTask, Mode.Online,
                    ParsedFields.PostTaskFields(totalPay = 12.00, parsedPay = parsedPay),
                    ruleId = "doordash.screen.delivery_summary",
                ),
            )
            assertMinted(armed.pendingDestructive, "PostTask TASK_RETIRE (parsedPay=$parsedPay)")
        }
    }

    @Test
    fun `the idle-flash TASK_RETIRE is minted`() {
        val armed = step(
            dashing(), Flow.TaskDropoffArrived,
            screen(10_000L, Flow.Idle, Mode.Online, ParsedFields.IdleFields()),
        )
        assertMinted(armed.pendingDestructive, "idle-flash TASK_RETIRE")
    }

    @Test
    fun `a TIGHTENED destructive grace is re-minted`() {
        val collapsed = step(
            dashing(), Flow.TaskDropoffArrived,
            screen(
                10_000L, Flow.PostTask, Mode.Online,
                ParsedFields.PostTaskFields(totalPay = 12.00, parsedPay = null),
                ruleId = "doordash.screen.delivery_summary",
            ),
        )
        val tightened = step(
            collapsed, Flow.PostTask,
            screen(
                11_000L, Flow.PostTask, Mode.Online,
                ParsedFields.PostTaskFields(
                    totalPay = 12.00,
                    parsedPay = ParsedPay(listOf(ParsedPayItem("Base Pay", 12.0)), emptyList()),
                ),
                ruleId = "doordash.screen.delivery_summary",
            ),
        )
        assertMinted(tightened.pendingDestructive, "tightened TASK_RETIRE")
        assertTrue(
            "a moved deadline is a new pending to its timer",
            tightened.pendingDestructive!!.wakeId != collapsed.pendingDestructive!!.wakeId,
        )
    }

    // ---- pendingModeResume ----

    @Test
    fun `the graced resume out of Paused is minted`() {
        val paused = region(mode = Mode.Paused)
        val armed = step(paused, Flow.Idle, screen(10_000L, Flow.Idle, Mode.Online, ParsedFields.IdleFields()))

        assertNotNull("the resume grace was armed", armed.pendingModeResume)
        assertTrue("resume grace minted", armed.pendingModeResume!!.wakeId > 0L)
    }

    // ---- pauseSafety ----

    @Test
    fun `the pause-safety net is minted`() {
        val paused = step(
            region(), Flow.Idle,
            screen(
                10_000L, null, Mode.Paused,
                ParsedFields.PausedFields(remainingText = "5:00", remainingMillis = 300_000L),
            ),
        )
        assertNotNull("the net was armed", paused.pauseSafety)
        assertTrue("pause safety minted", paused.pauseSafety!!.wakeId > 0L)
    }

    // ---- pendingSessionPay ----

    @Test
    fun `a parked running-total read is minted`() {
        val parked = step(
            region(), Flow.Idle,
            screen(
                10_000L, Flow.Idle, Mode.Online, ParsedFields.IdleFields(sessionPay = 16.70),
                ruleId = "doordash.screen.waiting_for_offer",
            ),
        )
        assertNotNull("the park was created", parked.pendingSessionPay)
        assertTrue("park minted", parked.pendingSessionPay!!.wakeId > 0L)
    }

    @Test
    fun `a park RE-BASED on the way back Online is re-minted`() {
        // #1052: the resume hands a frozen park a brand-new window, which is a new pending.
        val parked = step(
            region(), Flow.Idle,
            screen(
                10_000L, Flow.Idle, Mode.Online, ParsedFields.IdleFields(sessionPay = 16.70),
                ruleId = "doordash.screen.waiting_for_offer",
            ),
        )
        val paused = step(
            parked, Flow.Idle,
            screen(
                11_000L, null, Mode.Paused,
                ParsedFields.PausedFields(remainingText = "5:00", remainingMillis = 300_000L),
            ),
        )
        assertNotNull("the park is frozen, not dropped, while Paused", paused.pendingSessionPay)

        // An OfferPresented screen is authoritative online evidence — it commits the resume at once.
        val resumed = step(
            paused, Flow.Idle,
            screen(12_000L, Flow.OfferPresented, Mode.Online, ParsedFields.None),
        )
        val rebased = resumed.pendingSessionPay
        assertNotNull("the park survived the resume", rebased)
        assertTrue("re-based park minted", rebased!!.wakeId > 0L)
        assertTrue(
            "and it is a NEW generation — the pre-pause arm must not land on it",
            rebased.wakeId != paused.pendingSessionPay!!.wakeId,
        )
    }

    private fun assertMinted(pend: PendingDestructive?, what: String) {
        assertNotNull("$what was armed", pend)
        assertTrue(
            "$what carries id 0 — its own wake could never commit it, and nothing re-arms",
            pend!!.wakeId > 0L,
        )
        assertTrue("$what is a destructive kind", pend.kind in DestructiveKind.entries)
    }
}
