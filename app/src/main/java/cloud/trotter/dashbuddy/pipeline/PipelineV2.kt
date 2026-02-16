package cloud.trotter.dashbuddy.pipeline

import android.os.Build
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.pipeline.accessibility.AccessibilityPipeline
import cloud.trotter.dashbuddy.pipeline.notification.NotificationPipeline
import cloud.trotter.dashbuddy.state.event.StateEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.merge
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PipelineV2 @Inject constructor(
    accessibilityPipeline: AccessibilityPipeline,
    notificationPipeline: NotificationPipeline
) {
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    val events: Flow<StateEvent> = merge(
        accessibilityPipeline.output(),
        notificationPipeline.output()
    )
        .flowOn(Dispatchers.Default)
}