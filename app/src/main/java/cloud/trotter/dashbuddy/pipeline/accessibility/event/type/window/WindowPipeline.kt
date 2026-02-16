package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window

import android.os.Build
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.content_changed.ContentChangedPipeline
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.state_changed.StateChangedPipeline
import cloud.trotter.dashbuddy.state.event.StateEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import javax.inject.Inject

class WindowPipeline @Inject constructor(
    private val contentChangedPipeline: ContentChangedPipeline,
    private val stateChangedPipeline: StateChangedPipeline,
    private val factory: ScreenFactory,
    private val differ: ScreenDiffer,
) {
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    fun output(): Flow<StateEvent> = merge(
        contentChangedPipeline.output(),
        stateChangedPipeline.output()
    )
        .filter { node ->
            differ.hasChanged(node)
        }
        .map { node ->
            factory.create(node)
        }
}