package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.accessibility.BoundingBox
import cloud.trotter.dashbuddy.domain.pipeline.NodeRef
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.settings.GraceConfig
import cloud.trotter.dashbuddy.domain.settings.GraceConfigProvider
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Regions
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #438 item 6 (B6) — the per-platform grace/timing seam. One [GraceConfig]
 * per [Platform] behind the injected [GraceConfigProvider] snapshot; a
 * platform absent from the override map resolves to [GraceConfig.DEFAULT]
 * (today's code constants), so overriding ONE platform's timing must never
 * move another platform's deadlines.
 *
 * Principle 8: the lookups are keyed by the REGION's / OBSERVATION's own
 * platform through the config map — the platform literals below are test
 * fixture data, not logic.
 */
class PerPlatformGraceConfigTest {

    /** Every field differs from the default so a wrong-field read can't pass. */
    private val ddOverride = GraceConfig(
        gracePeriodMs = 5_000L,
        authoritativeGraceMs = 1_200L,
        pauseResumeGraceMs = 4_000L,
        pauseTimeoutBufferMs = 2_500L,
        expandSettleMs = 900L,
    )

    /** Overrides DoorDash only — Uber must fall back to [GraceConfig.DEFAULT]. */
    private val provider = GraceConfigProvider { mapOf(Platform.DoorDash to ddOverride) }

    private val stepper = PlatformRegionStepper()
    private val policy = TransitionPolicy(provider)
    private val effectMap = EffectMap(provider)

    // ---- helpers ----

    private fun taskRegion(platform: Platform) = PlatformRegion(
        platform = platform,
        mode = Mode.Online,
        session = Session("sess-${platform.wire}", startedAt = 100L),
        activeJob = Job("job-1", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 200L),
        activeTask = Task(
            taskId = "task-1", jobId = "job-1", phase = TaskPhase.PICKUP,
            storeName = "Store", startedAt = 300L, arrivedAt = 800L,
        ),
    )

    private fun idleObs(platform: Platform, timestamp: Long) = Observation.Screen(
        timestamp = timestamp, captureId = null, ruleId = "${platform.wire}.screen.idle",
        metadata = ReplayMetadata.EMPTY, flow = Flow.Idle, modeHint = Mode.Online,
        parsed = ParsedFields.None,
    )

    private fun postTaskObs(platform: Platform, timestamp: Long) = Observation.Screen(
        timestamp = timestamp, captureId = null, ruleId = "${platform.wire}.screen.delivery_summary",
        metadata = ReplayMetadata.EMPTY, flow = Flow.PostTask, modeHint = Mode.Online,
        parsed = ParsedFields.None,
    )

    /** Arm the idle-flash TASK_RETIRE grace and return the pending deadline. */
    private fun idleGraceDeadline(platform: Platform): Long {
        val prevFlow = FlowRegion(flow = Flow.TaskPickupArrived)
        val obs = idleObs(platform, timestamp = 1_000L)
        val next = stepper.step(taskRegion(platform), prevFlow, FlowRegion(flow = Flow.Idle), obs, policy)
        assertEquals(DestructiveKind.TASK_RETIRE, next.pendingDestructive?.kind)
        return next.pendingDestructive!!.deadline
    }

    /** Arm the PostTask authoritative TASK_RETIRE grace and return the deadline. */
    private fun postTaskGraceDeadline(platform: Platform): Long {
        val prevFlow = FlowRegion(flow = Flow.TaskDropoffArrived)
        val obs = postTaskObs(platform, timestamp = 1_000L)
        val next = stepper.step(taskRegion(platform), prevFlow, FlowRegion(flow = Flow.PostTask), obs, policy)
        assertEquals(DestructiveKind.TASK_RETIRE, next.pendingDestructive?.kind)
        return next.pendingDestructive!!.deadline
    }

    // ---- stepper side: pendingDestructive deadlines key per platform ----

    @Test
    fun `overridden platform's pendingDestructive deadline uses ITS gracePeriodMs`() {
        assertEquals(1_000L + 5_000L, idleGraceDeadline(Platform.DoorDash))
    }

    @Test
    fun `the OTHER platform's grace deadline is unaffected by the override`() {
        assertEquals(1_000L + GraceConfig.DEFAULT_GRACE_MS, idleGraceDeadline(Platform.Uber))
    }

    @Test
    fun `authoritative grace keys per platform the same way`() {
        assertEquals(1_000L + 1_200L, postTaskGraceDeadline(Platform.DoorDash))
        assertEquals(1_000L + GraceConfig.AUTHORITATIVE_GRACE_MS, postTaskGraceDeadline(Platform.Uber))
    }

    @Test
    fun `pause-resume grace keys per platform`() {
        // Paused region sees an online-implying (non-offer) screen → arms
        // PendingModeResume with the platform's own pauseResumeGraceMs (#605).
        fun resumeDeadline(platform: Platform): Long {
            val paused = PlatformRegion(
                platform = platform, mode = Mode.Paused,
                session = Session("sess-1", startedAt = 100L),
            )
            val obs = idleObs(platform, timestamp = 2_000L).copy(flow = Flow.TaskPickupNavigation)
            val next = stepper.step(paused, FlowRegion(flow = Flow.Idle), FlowRegion(flow = Flow.TaskPickupNavigation), obs, policy)
            return next.pendingModeResume!!.deadline
        }
        assertEquals(2_000L + 4_000L, resumeDeadline(Platform.DoorDash))
        assertEquals(2_000L + GraceConfig.PAUSE_RESUME_GRACE_MS, resumeDeadline(Platform.Uber))
    }

    @Test
    fun `an unbound provider resolves every platform to the code-constant defaults`() {
        val defaults = TransitionPolicy()
        for (p in Platform.entries) {
            assertEquals(GraceConfig.DEFAULT_GRACE_MS, defaults.gracePeriodMs(p))
            assertEquals(GraceConfig.AUTHORITATIVE_GRACE_MS, defaults.authoritativeGraceMs(p))
            assertEquals(GraceConfig.PAUSE_RESUME_GRACE_MS, defaults.pauseResumeGraceMs(p))
        }
    }

    // ---- EffectMap side: pause buffer + expand settle key per platform ----

    private fun pauseTimeoutDuration(platform: Platform): Long {
        val online = PlatformRegion(
            platform = platform, mode = Mode.Online,
            session = Session("sess-1", startedAt = 100L),
        )
        val prev = AppState(regions = Regions(platforms = mapOf(platform to online)))
        val next = AppState(regions = Regions(platforms = mapOf(platform to online.copy(mode = Mode.Paused))))
        val obs = Observation.Screen(
            timestamp = 1_000L, captureId = null, ruleId = "${platform.wire}.screen.paused",
            metadata = ReplayMetadata.EMPTY, flow = null, modeHint = Mode.Paused,
            parsed = ParsedFields.PausedFields(remainingText = "5:00", remainingMillis = 300_000L),
        )
        val timeout = effectMap.diff(prev, next, obs)
            .filterIsInstance<AppEffect.ScheduleTimeout>()
            .single { it.type == TimeoutType.SESSION_PAUSED_SAFETY }
        return timeout.durationMs
    }

    @Test
    fun `pause safety-timeout buffer keys per platform`() {
        assertEquals(300_000L + 2_500L, pauseTimeoutDuration(Platform.DoorDash))
        assertEquals(300_000L + GraceConfig.PAUSE_TIMEOUT_BUFFER_MS, pauseTimeoutDuration(Platform.Uber))
    }

    private fun expandSettleDuration(platform: Platform): Long {
        val obs = Observation.Screen(
            timestamp = 1_000L, captureId = null,
            ruleId = "${platform.wire}.screen.delivery_summary_collapsed",
            metadata = ReplayMetadata.EMPTY, flow = Flow.PostTask, modeHint = Mode.Online,
            parsed = ParsedFields.PostTaskFields(totalPay = 25.0, isExpanded = false),
            targets = mapOf(
                "expandButton" to NodeRef(
                    viewIdSuffix = "id/btn", text = null, classNameHint = null,
                    boundsInScreen = BoundingBox(0, 0, 10, 10), pathFingerprint = "0/1",
                ),
            ),
        )
        val timeout = effectMap.diff(AppState(), AppState(), obs)
            .filterIsInstance<AppEffect.ScheduleTimeout>()
            .single { it.type == TimeoutType.SETTLE_UI }
        return timeout.durationMs
    }

    @Test
    fun `expand-settle delay keys per platform`() {
        assertEquals(900L, expandSettleDuration(Platform.DoorDash))
        assertEquals(GraceConfig.EXPAND_SETTLE_MS, expandSettleDuration(Platform.Uber))
    }
}
