package cloud.trotter.dashbuddy.pipeline.accessibility

import cloud.trotter.dashbuddy.domain.pipeline.Observation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Merges the window and click sub-pipes into a single accessibility observation flow.
 *
 * Adding a new sub-pipe (e.g., long-click, scroll, focus) means creating
 * the sub-pipe class and adding it as a constructor parameter here.
 */
@Singleton
class AccessibilityPipeline @Inject constructor(
    private val windowSubPipe: WindowSubPipe,
    private val clickSubPipe: ClickSubPipe,
) {
    fun output(): Flow<Observation> = merge(
        windowSubPipe.output(),
        clickSubPipe.output(),
    )
}
