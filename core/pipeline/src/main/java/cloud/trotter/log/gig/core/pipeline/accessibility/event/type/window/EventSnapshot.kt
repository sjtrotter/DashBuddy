package cloud.trotter.log.gig.core.pipeline.accessibility.event.type.window

import cloud.trotter.log.gig.core.pipeline.accessibility.ForegroundSkipReason
import cloud.trotter.log.gig.core.pipeline.accessibility.input.AccessibilitySource

/** The resolver's verdict (#1148 review H3): a frame, or a counted reason for none. */
internal sealed interface EventSnapshot {
    data class Resolved(val snapshot: AccessibilitySource.RootSnapshot) : EventSnapshot
    data class Skipped(val reason: ForegroundSkipReason) : EventSnapshot
}

/** Event identifiers are diagnostics only; the resolver chooses the live frame. */
internal fun AccessibilitySource.snapshotForEvent(
    windowId: Int,
    eventPackage: String?,
    isEnabled: (String?) -> Boolean,
): EventSnapshot = frames.event(windowId, eventPackage, isEnabled)
