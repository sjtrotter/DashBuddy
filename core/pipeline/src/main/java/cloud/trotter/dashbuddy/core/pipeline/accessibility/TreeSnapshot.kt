package cloud.trotter.dashbuddy.core.pipeline.accessibility

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode

/**
 * A captured UI tree annotated with pipeline metadata.
 */
data class TreeSnapshot(
    val tree: UiNode,
    /** Package name of the app that triggered this snapshot. */
    val packageName: String? = null,
    /**
     * Metadata of the window the tree was read from. Filled on every path since #1148 D4 (active
     * root, the event's own window, and WINDOWS_CHANGED enumeration); null only when the window
     * could not be located.
     */
    val windowContext: WindowContext? = null,
    /**
     * Why this snapshot was taken (#1148 D4): the event reason plus the coalesced burst it stands
     * for. Consumed by the DRIP log and available to the census (#1138). Deliberately NOT part of
     * the capture envelope (envelope byte-identity).
     */
    val trigger: Trigger? = null,
) {
    data class Trigger(
        val reason: Reason,
        /** OR of the `contentChangeTypes` bits across the coalesced burst (0 for non-content). */
        val changeTypes: Int,
        /** How many raw events this snapshot coalesced (1 for an immediate state change). */
        val coalescedEvents: Int,
        /** First-to-last raw event span of the burst, from the events' own timestamps. */
        val spanMs: Long,
    ) {
        enum class Reason { CONTENT, STATE, WINDOWS }
    }

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
