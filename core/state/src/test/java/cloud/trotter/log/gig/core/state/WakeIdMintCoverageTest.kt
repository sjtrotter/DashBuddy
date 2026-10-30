package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.model.pay.ParsedPay
import cloud.trotter.log.gig.domain.model.pay.ParsedPayItem
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.state.AppState
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.FlowRegion
import cloud.trotter.log.gig.domain.state.Job
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.PendingDestructive
import cloud.trotter.log.gig.domain.state.DestructiveKind
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.Regions
import cloud.trotter.log.gig.domain.state.Session
import cloud.trotter.log.gig.domain.state.Task
import cloud.trotter.log.gig.domain.state.TaskPhase
import cloud.trotter.log.gig.domain.state.IdleSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every deadline-bearing pending the steppers create carries a minted generation (#1427).
 * Construction requires a WakeId; these cases pin the reachable installation paths too.
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
        assertTrue("and the replacement never inherits", replaced.wakeId.value != end.wakeId.value)
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
            tightened.pendingDestructive!!.wakeId.value != collapsed.pendingDestructive!!.wakeId.value,
        )
    }

    // ---- pendingModeResume ----

    @Test
    fun `the graced resume out of Paused is minted`() {
        val paused = region(mode = Mode.Paused)
        val armed = step(paused, Flow.Idle, screen(10_000L, Flow.Idle, Mode.Online, ParsedFields.IdleFields()))

        assertNotNull("the resume grace was armed", armed.pendingModeResume)
        assertTrue("resume grace minted", armed.pendingModeResume!!.wakeId.value > 0L)
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
        assertTrue("pause safety minted", paused.pauseSafety!!.wakeId.value > 0L)
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
        assertTrue("park minted", parked.pendingSessionPay!!.wakeId.value > 0L)
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
        assertTrue("re-based park minted", rebased!!.wakeId.value > 0L)
        assertTrue(
            "and it is a NEW generation — the pre-pause arm must not land on it",
            rebased.wakeId.value != paused.pendingSessionPay!!.wakeId.value,
        )
    }

    // The JOB_ABANDON installer's mint is pinned in UnassignIdleClosesJobTest (its first case asserts the
    // armed grace carries the region's newest generation), where the full unassign-then-idle setup lives.

    @Test
    fun `recovery re-base mints even when the deadline stays equal`() {
        val armed = step(
            region(), Flow.Idle,
            screen(10_000L, Flow.Idle, Mode.Offline, ParsedFields.IdleFields()),
        )
        val before = armed.pendingDestructive!!
        val recovered = state(armed).copy(timestamp = 10_000L)
            .recoveryHygiene(nowMs = 10_000L).regions.platforms.getValue(platform)
        val after = recovered.pendingDestructive!!
        assertMinted(after, "recovery re-base")
        assertEquals(before.deadline, after.deadline)
        assertEquals(armed.wakeSeq + 1L, recovered.wakeSeq)
        assertEquals(recovered.wakeSeq, after.wakeId.value)
        assertEquals(before.windowFrom, after.windowFrom)
        assertEquals(10_000L, after.servedFrom)
    }

    private fun assertMinted(pend: PendingDestructive?, what: String) {
        assertNotNull("$what was armed", pend)
        assertTrue(
            "$what carries a positive minted generation",
            pend!!.wakeId.value > 0L,
        )
        assertTrue("$what is a destructive kind", pend.kind in DestructiveKind.entries)
    }
}
