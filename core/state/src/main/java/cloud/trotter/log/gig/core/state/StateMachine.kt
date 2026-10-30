package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.core.state.model.StepDecisions
import cloud.trotter.log.gig.core.state.model.RegionDecisions
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.state.AppState
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.Regions
import cloud.trotter.log.gig.core.state.model.Transition
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Multi-region state machine — the Layer 3 orchestrator.
 *
 * Steps observations through three region tiers in order:
 * 1. **R0 (Flow)** — ground-truth screen interpretation
 * 2. **R2+ (Platform)** — per-platform mode/session/job/task with healing
 * 3. **R1 (CrossPlatform)** — derived aggregation
 *
 * Produces a [Transition] containing the new [AppState] and a list of
 * [AppEffect]s derived from the region snapshots and this step's decisions.
 */
@Singleton
class StateMachine @Inject constructor(
    private val flowStepper: FlowRegionStepper,
    private val platformStepper: PlatformRegionStepper,
    private val crossPlatformStepper: CrossPlatformRegionStepper,
    private val transitionPolicy: TransitionPolicy,
    private val effectMap: EffectMap,
) {

    fun step(prev: AppState, obs: Observation): Transition {
        // R0 — observation-driven, no plausibility gating
        val nextFlow = flowStepper.step(prev.regions.flow, obs)

        // R2+ — per-platform; only the matching platform steps
        val (nextPlatforms, decisions) = stepPlatforms(
            prev.regions.platforms, prev.regions.flow, nextFlow, obs,
        )

        // R1 — derived from post-step platform snapshots
        val nextCrossPlatform = crossPlatformStepper.step(
            prev.regions.crossPlatform, prev.regions.platforms, nextPlatforms, obs,
        )

        val next = prev.copy(
            regions = Regions(nextFlow, nextCrossPlatform, nextPlatforms),
            timestamp = obs.timestamp,
            correlationVersion = prev.correlationVersion + 1,
        )

        return Transition(next, effectMap.diff(prev, next, obs, decisions), decisions)
    }

    private fun stepPlatforms(
        prev: Map<Platform, PlatformRegion>,
        prevFlow: cloud.trotter.log.gig.domain.state.FlowRegion,
        nextFlow: cloud.trotter.log.gig.domain.state.FlowRegion,
        obs: Observation,
    ): Pair<Map<Platform, PlatformRegion>, StepDecisions> {
        val platform = obs.platform
        // #438 item 4: an observation whose platform can't be resolved
        // (Platform.Unknown — e.g. an offer Accept/Decline UiInput/Loopback that
        // carries ruleId=null) must NOT mint or step a PlatformRegion(Unknown). R0
        // (FlowRegion) already saw it — it was stepped in step() before this — and
        // CrossPlatformRegion derives only from real platform regions; only this
        // per-platform tier is skipped. Before this, the first such observation
        // planted a permanent Unknown region into state + every snapshot, and it
        // polluted the CrossPlatform aggregates (anyPlatformOnline / activeSessionCount).
        //
        // Decode-compat: an OLD snapshot that persisted an Unknown region before this
        // fix still decodes fine (Platform is a by-name enum); it is simply DROPPED on
        // the next step here (the `- Platform.Unknown` below runs on every observation,
        // whatever its platform), never fed to a stepper — no recovery crash.
        if (platform == Platform.Unknown) return (prev - Platform.Unknown) to StepDecisions.NONE
        val prevRegion = prev[platform] ?: PlatformRegion(platform)
        val stepped = platformStepper.stepWithDecisions(prevRegion, prevFlow, nextFlow, obs, transitionPolicy)
        val decisions = if (stepped.decisions == RegionDecisions.NONE) StepDecisions.NONE
            else StepDecisions(mapOf(platform to stepped.decisions))
        return ((prev + (platform to stepped.region)) - Platform.Unknown) to decisions
    }
}
