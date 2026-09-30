package cloud.trotter.dashbuddy.core.pipeline.census.diagnostics

import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.state.Platform
import java.time.LocalDate

/**
 * TEST / DIAGNOSTIC ONLY — NEVER WIRED INTO PRODUCTION (#1160 reviews OO2, UU7).
 *
 * Builds a census skeleton with the FRAME-LEVEL duplicate / containment rule SWITCHED OFF (the per-field
 * filter is unchanged). It exists solely so the `:app` corpus test can measure exactly which slots the
 * frame-level rule flips to `withheld` and pin that none of them is chrome. A skeleton built here can
 * hash a value the production builder withholds; it must never reach a sink, a queue or an uploader. The
 * production [SkeletonBuilder] exposes no off-switch; this object is the only way to get one.
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
        SkeletonBuilder.FrameFilter(SkeletonBuilder::withholdingStep, frameLevel = false),
    )
}
