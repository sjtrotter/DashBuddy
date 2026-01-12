package cloud.trotter.dashbuddy.pipeline

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.pipeline.filters.EventDebouncer
import cloud.trotter.dashbuddy.pipeline.filters.ScreenDiffer
import cloud.trotter.dashbuddy.pipeline.processing.StateContextFactory
import cloud.trotter.dashbuddy.services.accessibility.UiNode
import cloud.trotter.dashbuddy.statev2.StateManagerV2
import cloud.trotter.dashbuddy.statev2.model.NotificationInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import cloud.trotter.dashbuddy.log.Logger as Log

object Pipeline {

    private const val TAG = "Pipeline"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val differ = ScreenDiffer()

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private val debouncer = EventDebouncer(delayMs = 50L) { event, rootNode ->
        processAccessibility(event, rootNode)
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    fun onAccessibilityEvent(event: AccessibilityEvent, service: AccessibilityService) {
        if (event.packageName?.toString() != "com.doordash.driverapp") return
        val rootNode = service.rootInActiveWindow ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                debouncer.cancel()
                processAccessibility(event, rootNode)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                debouncer.submit(event, rootNode)
            }

            else -> {} // Ignore other events
        }
    }

    fun onNotificationPosted(info: NotificationInfo) {
        scope.launch {
            try {
                Log.d(TAG, "Notification: ${info.title}")
                val event = StateContextFactory.createFromNotification(info)
                StateManagerV2.dispatch(event)
            } catch (e: Exception) {
                Log.e(TAG, "Notification Pipeline Error", e)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun processAccessibility(event: AccessibilityEvent, rootNode: AccessibilityNodeInfo) {
        scope.launch {
            try {
                // 1. CONVERT (Fast)
                // We create the UiNode wrapper immediately.
                Log.d(TAG, "Processing Accessibility Event: ${event.eventType}")
                val uiNode = UiNode.from(rootNode) ?: return@launch

                // 2. DIFF (Optimization)
                // We check the hash of the UiNode BEFORE we do any heavy recognition logic.
                val isClick = event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
                Log.d(TAG, "Is Click: $isClick")

                if (!isClick && !differ.hasChanged(uiNode)) {
                    // Screen is identical to the last one processed.
                    // Stop here to save CPU and Battery.
                    Log.d(TAG, "Screen is identical to the last one processed. Skipping...")
                    return@launch
                }

                // 3. PROCESS (Heavy)
                // Now that we know it's new, we ask the Factory to analyze it.
                val updateEvent = StateContextFactory.createFromAccessibility(uiNode)

                Log.i(TAG, "Sending event to StateManager: ${updateEvent.screenInfo?.screen}")

                // 4. DISPATCH
                StateManagerV2.dispatch(updateEvent)

            } catch (e: Exception) {
                Log.e(TAG, "Accessibility Pipeline Error", e)
            }
        }
    }
}