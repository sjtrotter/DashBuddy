package cloud.trotter.dashbuddy.core.pipeline.accessibility.input

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Immutable envelope for one accessibility callback (#1148 D1).
 *
 * The framework owns the [AccessibilityEvent] handed to `onAccessibilityEvent` and may reuse it
 * once the callback returns (the event pool still exists at minSdk 30). The shared event flow is
 * BUFFERED and read later by the sub-pipelines, so the scalars they need are copied here, at the
 * callback, in ONE place ([from]) — the TalkBack pattern (copy before queuing).
 *
 * [source] is resolved ONLY for `TYPE_VIEW_CLICKED` (the click pipeline maps the clicked node);
 * every other type carries `null`, so no live node handle rides the flow needlessly.
 */
data class AccEvent(
    val type: Int,
    val windowId: Int,
    val packageName: String?,
    val className: String?,
    val contentChangeTypes: Int,
    val windowChanges: Int,
    val eventTimeMs: Long,
    val source: SourceNodeRef? = null,
) {
    companion object {
        /** Copies the event's scalars at the callback. The ONE AccessibilityEvent → AccEvent site. */
        fun from(event: AccessibilityEvent): AccEvent {
            val type = event.eventType
            return AccEvent(
                type = type,
                windowId = event.windowId,
                packageName = event.packageName?.toString(),
                className = event.className?.toString(),
                contentChangeTypes = event.contentChangeTypes,
                windowChanges = event.windowChanges,
                eventTimeMs = event.eventTime,
                source = if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                    event.source?.let(::SourceNodeRef)
                } else {
                    null
                },
            )
        }
    }
}

/**
 * Thin holder for the clicked node, fetched at emit time. The click pipeline maps it with
 * `toUiNode()` exactly as it mapped `event.source` before (#1148 D1). Deliberately NOT a data
 * class: node identity is not part of the envelope's value equality.
 */
class SourceNodeRef(val node: AccessibilityNodeInfo)
