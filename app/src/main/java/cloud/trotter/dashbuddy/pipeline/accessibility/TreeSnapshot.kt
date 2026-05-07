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
    /** Package name of the app that triggered this snapshot. */
    val packageName: String? = null,
    /** Window metadata when captured via WINDOWS_CHANGED (multi-window enumeration). */
    val windowContext: WindowContext? = null,
) {
    enum class Source { CONTENT_CHANGED, STATE_CHANGED, WINDOWS_CHANGED }

    data class WindowContext(
        val windowId: Int,
        /** TYPE_APPLICATION=1, TYPE_INPUT_METHOD=2, TYPE_SYSTEM=3, TYPE_ACCESSIBILITY_OVERLAY=4 */
        val windowType: Int,
        val windowTitle: String?,
        val windowLayer: Int,
        val isActive: Boolean,
        val isFocused: Boolean,
        val totalWindowCount: Int,
    )
}
