package cloud.trotter.dashbuddy.pipeline.accessibility

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode

/**
 * A captured UI tree annotated with pipeline metadata.
 *
 * Carries the source sub-pipe and the accumulated [contentChangeTypes] bitmask
 * from [android.view.accessibility.AccessibilityEvent.getContentChangeTypes].
 * This lets downstream stages (dedup, classification, capture) make smarter
 * decisions about whether a tree snapshot represents a meaningful change.
 */
data class TreeSnapshot(
    val tree: UiNode,
    val source: Source,
    /** OR-accumulated bitmask of AccessibilityEvent content change type flags. */
    val contentChangeTypes: Int = 0,
) {
    enum class Source { CONTENT_CHANGED, STATE_CHANGED }
}
