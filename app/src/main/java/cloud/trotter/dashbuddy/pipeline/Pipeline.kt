package cloud.trotter.dashbuddy.pipeline

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.pipeline.filters.EventDebouncer
import cloud.trotter.dashbuddy.pipeline.filters.ScreenDiffer
import cloud.trotter.dashbuddy.pipeline.model.UiNode
import cloud.trotter.dashbuddy.pipeline.processing.StateContextFactory
import cloud.trotter.dashbuddy.state.StateManagerV2
import cloud.trotter.dashbuddy.state.model.NotificationInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Pipeline @Inject constructor(
    private val stateManagerV2: StateManagerV2,
    private val stateContextFactory: StateContextFactory,
    private val screenDiffer: ScreenDiffer,
) {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private val debouncer = EventDebouncer(delayMs = 50L) { event, rootNode ->
        processAccessibility(event, rootNode)
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    fun onAccessibilityEvent(event: AccessibilityEvent, service: AccessibilityService) {
        if (event.packageName?.toString() != "com.doordash.driverapp") return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val sourceNode = event.source
                if (sourceNode == null) {
                    Timber.w("👻 Ghost Click detected (Source was null)")
                    return
                }
                processAccessibility(event, sourceNode)
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                debouncer.cancel()
                val rootNode = service.rootInActiveWindow ?: return
                processAccessibility(event, rootNode)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val rootNode = service.rootInActiveWindow ?: return
                debouncer.submit(event, rootNode)
            }

            else -> {} // Ignore other events
        }
    }

    fun onNotificationPosted(info: NotificationInfo) {
        scope.launch {
            try {
                Timber.d("Notification: ${info.title}")
                val event = stateContextFactory.createFromNotification(info)
                stateManagerV2.dispatch(event)
            } catch (e: Exception) {
                Timber.e(e, "Notification Pipeline Error")
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun processAccessibility(event: AccessibilityEvent, rootNode: AccessibilityNodeInfo) {
        scope.launch {
            try {
                // 1. CONVERT (Fast)
                // We create the UiNode wrapper immediately.
                Timber.d("Processing Accessibility Event: ${event.eventType}")
                val uiNode = UiNode.from(rootNode) ?: return@launch

                // 2. DIFF (Optimization)
                // We check the hash of the UiNode BEFORE we do any heavy recognition logic.
                val isClick = event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED

                if (!isClick) {
                    if (!screenDiffer.hasChanged(uiNode)) {
                        // Screen is identical to the last one processed.
                        // Stop here to save CPU and Battery.
                        Timber.d("Screen is identical to the last one processed. Skipping...")
                        return@launch
                    }
                } else {
                    Timber.d("Clicked sourceNode: $uiNode")
                    val clickEvent = stateContextFactory.createFromClick(uiNode)
//                    stateManagerV2.dispatch(clickEvent)
                    return@launch
                }

                // 3. PROCESS (Heavy)
                // Now that we know it's new, we ask the Factory to analyze it.
                val updateEvent = stateContextFactory.createFromAccessibility(uiNode)

                Timber.i("Sending event to StateManager: ${updateEvent.screenInfo?.screen}")

                // 4. DISPATCH
                stateManagerV2.dispatch(updateEvent)

            } catch (e: Exception) {
                Timber.e(e, "Accessibility Pipeline Error")
            }
        }
    }
}