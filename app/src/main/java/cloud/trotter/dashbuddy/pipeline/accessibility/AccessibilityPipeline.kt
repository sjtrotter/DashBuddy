package cloud.trotter.dashbuddy.pipeline.accessibility

import android.os.Build
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.view.ViewPipeline
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.WindowPipeline
import cloud.trotter.dashbuddy.state.event.StateEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import javax.inject.Inject

class AccessibilityPipeline @Inject constructor(
    private val viewPipeline: ViewPipeline,
    private val windowPipeline: WindowPipeline
) {
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    fun output(): Flow<StateEvent> = merge(
        viewPipeline.output(),
        windowPipeline.output()
    )
}