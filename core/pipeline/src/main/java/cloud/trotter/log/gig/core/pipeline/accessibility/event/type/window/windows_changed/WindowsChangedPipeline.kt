package cloud.trotter.log.gig.core.pipeline.accessibility.event.type.window.windows_changed

import android.view.accessibility.AccessibilityEvent
import cloud.trotter.log.gig.core.pipeline.PipelineStats
import cloud.trotter.log.gig.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.log.gig.core.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.log.gig.domain.settings.PlatformPreferences
import cloud.trotter.log.gig.core.pipeline.accessibility.event.coalesce.coalesceByKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transform
import timber.log.Timber
import javax.inject.Inject

/** Coalesces topology triggers; the shared frame resolver owns acquisition and selection. */
class WindowsChangedPipeline @Inject constructor(
    private val source: AccessibilitySource,
    private val platformPreferences: PlatformPreferences,
    private val stats: PipelineStats,
) {
    fun output(): Flow<TreeSnapshot> = source.events
        .filter { it.type == AccessibilityEvent.TYPE_WINDOWS_CHANGED }
        // #1148 D3: the same bounded shape as content changes — quiet 100 ms, scheduled max-wait
        // 300 ms — on a single key (topology is one global stream). The accumulator is the count.
        .coalesceByKey(
            quietMs = 100L,
            maxWaitMs = 300L,
            keyOf = { 0 },
            merge = { acc: Int?, _ -> (acc ?: 0) + 1 },
        )
        .onEach { n -> Timber.tag("Pipeline").d("\uD83E\uDE9F WINDOWS_CHANGED (coalesced n=%d)", n) }
        .transform { coalesced ->
            source.frames.topology { platformPreferences.enabledPackages.value }?.let {
                emit(TreeSnapshot(
                    it.tree, it.packageName, it.windowContext,
                    TreeSnapshot.Trigger(
                        reason = TreeSnapshot.Trigger.Reason.WINDOWS,
                        changeTypes = 0,
                        coalescedEvents = coalesced,
                        spanMs = 0L,
                    ),
                ))
            }
        }
}
