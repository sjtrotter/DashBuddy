package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.domain.di.ApplicationScope
import cloud.trotter.dashbuddy.domain.model.state.StateEvent
import cloud.trotter.dashbuddy.core.pipeline.accessibility.AccessibilityPipeline
import cloud.trotter.dashbuddy.core.pipeline.notification.NotificationPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PipelineV2 @Inject constructor(
    accessibilityPipeline: AccessibilityPipeline,
    notificationPipeline: NotificationPipeline,
    @param:ApplicationScope scope: CoroutineScope,
) {
    /**
     * HOT shared stream (#361). The upstream chain runs side effects — frame
     * dedup state and disk captures — inside its operators; as a cold flow,
     * every additional collector (test harness, debug surface) would re-run
     * them, double-writing captures and racing the dedup state. One shared
     * upstream collector now feeds all subscribers; both sub-pipelines emit
     * Observation subtypes, which extend StateEvent.
     */
    val events: SharedFlow<StateEvent> = merge(
        accessibilityPipeline.output(),
        notificationPipeline.output(),
    )
        // No flowOn: shareIn runs the single upstream collector in [scope],
        // whose dispatcher is Default in production and virtual in tests.
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 0)
}
