package cloud.trotter.dashbuddy.core.pipeline.accessibility.input

import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import timber.log.Timber

/**
 * Immutable envelope for one accessibility callback (#1148 D1).
 *
 * The framework owns the [AccessibilityEvent] handed to `onAccessibilityEvent` and may reuse it
 * once the callback returns (the event pool still exists at minSdk 30). The shared event flow is
 * BUFFERED and read later by the sub-pipelines, so the scalars they need are copied here, at the
 * callback, in ONE place ([from]) — the TalkBack pattern (copy before queuing).
 *
 * [source] is carried ONLY for `TYPE_VIEW_CLICKED` (the click pipeline maps the clicked node);
 * every other type carries `null`. It holds an OWNED COPY of the event, not the resolved node:
 * `event.source` is a binder call (up to seconds against an unresponsive target) and must not run
 * on the accessibility callback thread (#1148 review F3) — the click pipeline resolves it on its
 * collector.
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
                    SourceNodeRef(copyOf(event))
                } else {
                    null
                },
            )
        }

        /** An owned copy of a framework event (local copy of its fields — no binder call). */
        @Suppress("DEPRECATION")
        private fun copyOf(event: AccessibilityEvent): AccessibilityEvent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                AccessibilityEvent(event)
            } else {
                AccessibilityEvent.obtain(event)
            }
    }
}

/**
 * Deferred handle on a click's source node (#1148 review F3): an OWNED copy of the click event,
 * taken on the callback thread without touching the binder. [resolve] performs the `getSource()`
 * binder fetch on the CALLER's (collector's) thread; [release] returns the copy to the pool below
 * API 33 and must be called once the click has been mapped. Deliberately NOT a data class: node
 * identity is not part of the envelope's value equality.
 */
class SourceNodeRef(private val eventCopy: AccessibilityEvent) {
    /** The clicked node, fetched now (binder). Null when the node is gone. */
    fun resolve(): AccessibilityNodeInfo? = eventCopy.source

    /** Recycles the owned copy below API 33 (a no-op above). Never throws. */
    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        try {
            @Suppress("DEPRECATION")
            eventCopy.recycle()
        } catch (e: Exception) {
            Timber.tag("Pipeline").v(e, "Click event copy already recycled")
        }
    }
}
