package cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.content_changed

import android.view.accessibility.AccessibilityEvent
import cloud.trotter.dashbuddy.core.pipeline.BuildConfig
import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.dashbuddy.core.pipeline.accessibility.event.coalesce.coalesceByKey
import cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.snapshotForEventWindow
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccEvent
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import timber.log.Timber
import javax.inject.Inject

/**
 * One coalesced content-change burst for one window (#1148 D3): the accumulator of
 * [coalesceByKey], owning the OR of the `contentChangeTypes` bits the old pipeline logged and
 * discarded.
 */
data class CoalescedChange(
    val windowId: Int,
    val packageName: String?,
    /** OR of the `contentChangeTypes` bits across the burst. */
    val changeTypes: Int,
    val eventCount: Int,
    val firstEventTimeMs: Long,
    val lastEventTimeMs: Long,
    val lastClassName: String?,
) {
    val spanMs: Long get() = lastEventTimeMs - firstEventTimeMs

    companion object {
        /** The [coalesceByKey] merge: open on the first event, fold every later one in. */
        fun merge(acc: CoalescedChange?, e: AccEvent): CoalescedChange = if (acc == null) {
            CoalescedChange(
                windowId = e.windowId,
                packageName = e.packageName,
                changeTypes = e.contentChangeTypes,
                eventCount = 1,
                firstEventTimeMs = e.eventTimeMs,
                lastEventTimeMs = e.eventTimeMs,
                lastClassName = e.className,
            )
        } else {
            acc.copy(
                packageName = e.packageName ?: acc.packageName,
                changeTypes = acc.changeTypes or e.contentChangeTypes,
                eventCount = acc.eventCount + 1,
                lastEventTimeMs = e.eventTimeMs,
                lastClassName = e.className,
            )
        }
    }
}

class ContentChangedPipeline @Inject constructor(
    private val source: AccessibilitySource
) {
    fun output(): Flow<TreeSnapshot> = source.events
        .filter { it.type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED }
        .onEach {
            Timber.v(
                "🌊 FLOOD: Content Change window=%d from %s  types=0x%02x",
                it.windowId, it.className, it.contentChangeTypes,
            )
        }
        // #1148 D3: coalesced PER WINDOW — quiet 150 ms / scheduled max-wait 300 ms, trailing
        // emission guaranteed, no leading edge.
        .coalesceByKey(
            quietMs = QUIET_MS,
            maxWaitMs = MAX_WAIT_MS,
            keyOf = { it.windowId },
            merge = CoalescedChange::merge,
        )
        .onEach {
            Timber.d(
                "💧 DRIP: window=%d types=0x%02x n=%d span=%dms",
                it.windowId, it.changeTypes, it.eventCount, it.spanMs,
            )
        }
        .mapNotNull { change ->
            // #1148 D4: snapshot the EVENT's window (active-root fallback), package-gated
            // before and after the map.
            val snapshot = source.snapshotForEventWindow(change.windowId, change.packageName)
                ?: return@mapNotNull null
            if (BuildConfig.DEBUG) {
                Timber.d("🌳 Tree snapshot: %d nodes, pkg=%s", countNodes(snapshot.tree), snapshot.packageName)
            }
            TreeSnapshot(
                tree = snapshot.tree,
                packageName = snapshot.packageName,
                windowContext = snapshot.windowContext,
                trigger = TreeSnapshot.Trigger(
                    reason = TreeSnapshot.Trigger.Reason.CONTENT,
                    changeTypes = change.changeTypes,
                    coalescedEvents = change.eventCount,
                    spanMs = change.spanMs,
                ),
            )
        }

    private fun countNodes(node: UiNode): Int =
        1 + node.children.sumOf { countNodes(it) }

    companion object {
        const val QUIET_MS = 150L
        const val MAX_WAIT_MS = 300L
    }
}
