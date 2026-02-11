package cloud.trotter.dashbuddy.pipeline.accessibility.screen

import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.pipeline.accessibility.screen.filters.ScreenDiffer
import cloud.trotter.dashbuddy.state.event.StateEvent
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import javax.inject.Inject

class ScreenPipeline @Inject constructor(
    private val source: AccessibilitySource,
    private val differ: ScreenDiffer,
    private val factory: ScreenFactory
) {

    // --- FLOW A: IMMEDIATE PRIORITY (State Changes) ---
    // "The context just changed! Process NOW!"
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private val windowStateFlow = source.events
        .filter { it.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED }
        .mapNotNull {
            // FETCH IMMEDIATE: We don't wait. We grab the screen as it is right now.
            source.getCurrentRootNode()
        }

    // --- FLOW B: DEBOUNCED BACKGROUND (Content Changes) ---
    // "Something moved... wait to see if it settles."
    @OptIn(FlowPreview::class)
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private val contentChangeFlow = source.events
        .filter { it.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED }
        .debounce(100L) // <--- The Time Filter (Wait for 100ms silence)
        .mapNotNull {
            // FETCH DELAYED: 100ms has passed. The screen is stable.
            // NOW we pay the cost to fetch the root node.
            // This gets the *latest* state, effectively skipping the 9 frames we ignored.
            source.getCurrentRootNode()
        }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    fun output(): Flow<StateEvent> {
        // Merge the streams -> Diff against last known screen -> Create Event
        return merge(windowStateFlow, contentChangeFlow)
            .filter { node -> differ.hasChanged(node) } // Content Filter
            .map { node -> factory.create(node) }       // Factory
    }
}