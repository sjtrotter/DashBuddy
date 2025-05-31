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
    val sourceClassName: CharSequence?,    // Class name of the view that fired the event

    val screenTexts: List<String>,              // All texts extracted from the current window (rootInActiveWindow)
    val sourceNodeTexts: List<String>,          // Texts specifically from event.source (can be empty)

    // Optional: If you need to perform actions or more detailed inspection.
    // Be very careful with the lifecycle of AccessibilityNodeInfo if you pass it directly.
    // The component receiving it becomes responsible for recycling if it holds onto the reference.
    // For simplicity here, we'll assume the state machine processes it immediately if passed.
    val sourceNode: AccessibilityNodeInfo? = null,

    // the current Dasher screen, if any
    val dasherScreen: DasherScreen? = null,
)
