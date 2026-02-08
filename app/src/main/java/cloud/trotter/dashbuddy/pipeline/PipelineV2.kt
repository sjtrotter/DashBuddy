package cloud.trotter.dashbuddy.pipeline

import android.os.Build
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.pipeline.features.click.ClickPipeline
import cloud.trotter.dashbuddy.pipeline.features.notification.NotificationPipeline
import cloud.trotter.dashbuddy.pipeline.features.screen.ScreenPipeline
import cloud.trotter.dashbuddy.state.event.StateEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.merge
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PipelineV2 @Inject constructor(
    clickPipeline: ClickPipeline,
    screenPipeline: ScreenPipeline,
    notificationPipeline: NotificationPipeline
) {
    // The Master Stream
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    val events: Flow<StateEvent> = merge(
        clickPipeline.output(),
        screenPipeline.output(),
        notificationPipeline.output()
    )
        .flowOn(Dispatchers.Default)
}