package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.domain.model.state.StateEvent
import cloud.trotter.dashbuddy.core.pipeline.accessibility.AccessibilityPipeline
import cloud.trotter.dashbuddy.core.pipeline.notification.NotificationPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.merge
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PipelineV2 @Inject constructor(
    accessibilityPipeline: AccessibilityPipeline,
    notificationPipeline: NotificationPipeline,
) {
    // Both sub-pipelines now emit Observation subtypes, which extend StateEvent.
    val events: Flow<StateEvent> = merge(
        accessibilityPipeline.output(),
        notificationPipeline.output(),
    )
        .flowOn(Dispatchers.Default)
}
