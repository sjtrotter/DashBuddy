package cloud.trotter.dashbuddy.state

import android.view.accessibility.AccessibilityNodeInfo
import cloud.trotter.dashbuddy.data.current.CurrentEntity
import cloud.trotter.dashbuddy.dasher.screen.ScreenInfo
import cloud.trotter.dashbuddy.dasher.click.ClickInfo

data class StateContext(
    val timestamp: Long,                                // When the event data was processed
    val eventType: Int? = null,                         // e.g., AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
    val eventTypeString: String,                        // For logging/debugging, e.g., "TYPE_WINDOW_CONTENT_CHANGED"
    val packageName: CharSequence? = null,              // e.g., "com.doordash.driverapp"
    val rootNode: AccessibilityNodeInfo? = null,        // The root node of the current window
    val rootNodeTexts: List<String> = emptyList(),      // All texts extracted from the current window (rootInActiveWindow)
    val sourceNode: AccessibilityNodeInfo? = null,      // The source node of the event (if any)
    val sourceClassName: CharSequence? = null,          // Class name of the view that fired the event
    val sourceNodeTexts: List<String> = emptyList(),    // Texts specifically from event.source (can be empty)
    val clickInfo: ClickInfo? = null,                   // Click info for the event
    val screenInfo: ScreenInfo? = null,                 // Screen info for the event
    val currentDashState: CurrentEntity? = null         // The current dash state according to the database
)
