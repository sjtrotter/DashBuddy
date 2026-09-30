package cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.content_changed

import android.view.accessibility.AccessibilityEvent
import cloud.trotter.dashbuddy.core.pipeline.BuildConfig
import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.dashbuddy.core.pipeline.accessibility.event.coalesce.coalesceByKey
import cloud.trotter.dashbuddy.core.pipeline.PipelineStats
import cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.EventSnapshot
import cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.snapshotForEvent
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccEvent
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.settings.PlatformPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import timber.log.Timber
import javax.inject.Inject

/**
 * One coalesced content-change burst (#1148 D3): the accumulator of [coalesceByKey], owning the OR
 * of the `contentChangeTypes` bits the old pipeline logged and discarded. There is ONE burst across
 * all windows (review G2), so [windowId] is the LAST event's window — for the DRIP log only.
 */
data class CoalescedChange(
    /** The last event's window (DRIP log only — the resolver ignores the event's window). */
    val windowId: Int,
    val packageName: String?,
    /** OR of the `contentChangeTypes` bits across the burst. */
    val changeTypes: Int,
    val eventCount: Int,
    val firstEventTimeMs: Long,
    val lastEventTimeMs: Long,
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
            )
        } else {
            acc.copy(
                windowId = e.windowId,
                packageName = e.packageName ?: acc.packageName,
                changeTypes = acc.changeTypes or e.contentChangeTypes,
                eventCount = acc.eventCount + 1,
                lastEventTimeMs = e.eventTimeMs,
            )
        }
    }
}

class ContentChangedPipeline @Inject constructor(
    private val source: AccessibilitySource,
    private val platformPreferences: PlatformPreferences,
    private val stats: PipelineStats,
) {
    fun output(): Flow<TreeSnapshot> = source.events
        .filter { it.type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED }
        .onEach {
            Timber.v(
                "🌊 FLOOD: Content Change window=%d from %s  types=0x%02x",
                it.windowId, it.className, it.contentChangeTypes,
            )
        }
        // #1148 D3: ONE burst across all windows (review G2 — the resolver snapshots one window
        // per frame whatever fired, so a per-window key would only multiply maps of the same root;
        // the operator stays generic) — quiet 150 ms / scheduled max-wait 300 ms, trailing
        // emission guaranteed. Leading edge ON (review F5): the first change after idle is
        // snapshotted immediately, as the old operator did, so a transition's first frame is not
        // delayed (the #1104 click-before-screen race, `presentedAt`).
        .coalesceByKey(
            quietMs = QUIET_MS,
            maxWaitMs = MAX_WAIT_MS,
            keyOf = { CONTENT_BURST_KEY },
            merge = CoalescedChange::merge,
            leadingEdge = true,
        )
        .onEach {
            Timber.d(
                "💧 DRIP: window=%d types=0x%02x n=%d span=%dms",
                it.windowId, it.changeTypes, it.eventCount, it.spanMs,
            )
        }
        .mapNotNull { change ->
            // #1148 D4 (review F1/G5): the active ENABLED window is the ground truth; with a
            // non-enabled window active (bubble, launcher) the readable window in front is read,
            // or the frame is refused. Package-gated before and after the map.
            val resolved = source.snapshotForEvent(change.windowId, change.packageName) {
                it in platformPreferences.enabledPackages.value
            }
            val snapshot = when (resolved) {
                is EventSnapshot.Resolved -> resolved.snapshot
                is EventSnapshot.Skipped -> {
                    stats.onForegroundSkip(resolved.reason) // #1148 review H3
                    return@mapNotNull null
                }
            }
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

        /** The single coalesce key for content changes (review G2). */
        private const val CONTENT_BURST_KEY = 0
    }
}
