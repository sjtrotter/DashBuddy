package cloud.trotter.dashbuddy.pipeline

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.pipeline.filters.EventDebouncer
import cloud.trotter.dashbuddy.pipeline.processing.StateContextFactory
import cloud.trotter.dashbuddy.statev2.model.NotificationInfo
import cloud.trotter.dashbuddy.statev2.StateManagerV2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import cloud.trotter.dashbuddy.log.Logger as Log

/**
 * The Central Nervous System.
 * All inputs (Screen updates, Notifications) must pass through here.
 */
object Pipeline {

    private const val TAG = "Pipeline"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // --- 1. ACCESSIBILITY STREAM ---

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private val debouncer = EventDebouncer(delayMs = 50L) { event, rootNode ->
        processAccessibility(event, rootNode)
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    fun onAccessibilityEvent(event: AccessibilityEvent, service: AccessibilityService) {
        if (event.packageName?.toString() != "com.doordash.driverapp") return
        val rootNode = service.rootInActiveWindow ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                debouncer.cancel()
                processAccessibility(event, rootNode)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                debouncer.submit(event, rootNode)
            }

            else -> {} // Ignore other events
        }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun processAccessibility(event: AccessibilityEvent, rootNode: AccessibilityNodeInfo) {
        scope.launch {
            try {
                // Transform & Dispatch
                val context = StateContextFactory.createFromAccessibility(rootNode)
                if (context != null) {
                    StateManagerV2.dispatch(context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Accessibility Pipeline Error", e)
            }
        }
    }

    // --- 2. NOTIFICATION STREAM ---

    fun onNotificationPosted(info: NotificationInfo) {
        // Notifications are low frequency, so we don't need to debounce them.
        // We just dispatch them immediately on the background thread.
        scope.launch {
            try {
                Log.d(TAG, "Pipeline received notification: ${info.title}")
                val context = StateContextFactory.createFromNotification(info)
                StateManagerV2.dispatch(context)
            } catch (e: Exception) {
                Log.e(TAG, "Notification Pipeline Error", e)
            }
        }
    }
}