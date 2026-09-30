package cloud.trotter.dashbuddy.core.pipeline.accessibility

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode

/**
 * A captured UI tree annotated with pipeline metadata.
 */
data class TreeSnapshot(
    val tree: UiNode,
    /** Package owning the window the tree was read from (never the triggering event's, #4). */
    val packageName: String? = null,
    /**
     * Metadata of the window the tree was read from — filled only on the paths that already hold
     * the window object (#1148 D4: the foreground-window read when a non-enabled window is active,
     * and WINDOWS_CHANGED enumeration); null on the active-root path (review H4).
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
