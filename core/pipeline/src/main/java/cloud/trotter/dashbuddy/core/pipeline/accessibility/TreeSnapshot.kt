package cloud.trotter.dashbuddy.core.pipeline.accessibility

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode

/**
 * A captured UI tree annotated with pipeline metadata.
 */
data class TreeSnapshot(
    val tree: UiNode,
    /** Package name of the app that triggered this snapshot. */
    val packageName: String? = null,
    /** Window metadata when captured via WINDOWS_CHANGED (multi-window enumeration). */
    val windowContext: WindowContext? = null,
) {
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
