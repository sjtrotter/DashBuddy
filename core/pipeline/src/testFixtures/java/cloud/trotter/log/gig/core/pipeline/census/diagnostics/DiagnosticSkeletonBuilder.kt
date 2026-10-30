package cloud.trotter.log.gig.core.pipeline.census.diagnostics

import cloud.trotter.log.gig.core.pipeline.census.FrameFilter
import cloud.trotter.log.gig.core.pipeline.census.SkeletonBuilder
import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.state.Platform
import java.time.LocalDate

/**
 * TEST / DIAGNOSTIC ONLY — NEVER WIRED INTO PRODUCTION (#1160 reviews OO2, UU7).
 *
 * Builds a census skeleton with the FRAME-LEVEL duplicate / containment rule SWITCHED OFF (the per-field
 * filter is unchanged). It exists solely so the `:app` corpus test can measure exactly which slots the
 * frame-level rule flips to `withheld` and pin that none of them is chrome. A skeleton built here can
 * hash a value the production builder withholds; it must never reach a sink, a queue or an uploader. The
 * production [SkeletonBuilder] exposes no off-switch; this object is the only way to get one. It lives in
 * `:core:pipeline`'s TEST FIXTURES (review AD4): nothing with the frame rule off exists in `main`, so it can
 * never ship in release bytecode.
 */
object DiagnosticSkeletonBuilder {

    fun outcomeWithoutFrameRule(
        tree: UiNode,
        windowTitle: String?,
        meta: ReplayMetadata,
        platform: Platform,
        day: LocalDate,
    ): SkeletonBuilder.Outcome = SkeletonBuilder.outcome(
        tree, windowTitle, meta, platform, day,
        FrameFilter(SkeletonBuilder::withholdingStep, frameLevel = false),
    )
}
