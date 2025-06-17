package cloud.trotter.dashbuddy.state

import android.content.Context as AndroidContext // Alias to avoid naming collision
import android.view.accessibility.AccessibilityNodeInfo
import cloud.trotter.dashbuddy.state.screens.Screen as DasherScreen
// You'll also need your AppState enum, e.g.:
// enum class AppState { UNKNOWN, STARTUP, LOGIN_SCREEN, MAIN_DASHBOARD, OFFER_POPUP, ON_DELIVERY, ... }

data class Context(
    val timestamp: Long,                        // When the event data was processed
    val androidAppContext: AndroidContext,      // Application context for global resources, SharedPreferences
    // Data from AccessibilityEvent
    val eventType: Int?,                        // e.g., AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
    val eventTypeString: String,                // For logging/debugging, e.g., "TYPE_WINDOW_CONTENT_CHANGED"
    val packageName: CharSequence?,             // e.g., "com.doordash.driverapp"
    val rootNode: AccessibilityNodeInfo?,       // The root node of the current window
    val rootNodeTexts: List<String>,            // All texts extracted from the current window (rootInActiveWindow)
    val sourceNode: AccessibilityNodeInfo?,     // The source node of the event (if any)
    val sourceClassName: CharSequence?,         // Class name of the view that fired the event
    val sourceNodeTexts: List<String>,          // Texts specifically from event.source (can be empty)
    // the current Dasher screen, if any
    val dasherScreen: DasherScreen? = null,
    val clickInfo: ClickInfo? = null,
    val screenInfo: ScreenInfo? = null,
)
