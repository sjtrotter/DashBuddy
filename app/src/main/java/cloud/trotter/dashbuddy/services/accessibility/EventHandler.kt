package cloud.trotter.dashbuddy.services.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.data.current.CurrentEntity
import cloud.trotter.dashbuddy.services.LocationService
import cloud.trotter.dashbuddy.services.accessibility.click.ClickInfo
import cloud.trotter.dashbuddy.services.accessibility.click.ClickParser
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenRecognizerV2
import cloud.trotter.dashbuddy.util.AccNodeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import cloud.trotter.dashbuddy.services.accessibility.screen.Screen as DasherScreen
import cloud.trotter.dashbuddy.state.StateContext as StateContext
import cloud.trotter.dashbuddy.state.StateManager as StateManager
import java.util.Date
import java.util.Objects
import cloud.trotter.dashbuddy.log.Logger as Log

/**
 * Singleton object to handle accessibility events.
 * This allows for centralized processing and state management
 * related to detected events.
 */
object EventHandler {

    private const val TAG = "EventHandler"
    private const val DEBOUNCE_DELAY_MS = 50L // Delay for content changes

    // --- State for new debouncing and fingerprinting logic ---
    private val handler = Handler(Looper.getMainLooper())
    private var lastFingerprint: ScreenFingerprint? = null
    private var lastDasherScreen: DasherScreen? = null
    private var debouncedEvent: AccessibilityEvent? = null
    private var debouncedRootNode: AccessibilityNodeInfo? = null
    private var debouncedRootNodeTexts: List<String> = emptyList()
    private val _serviceFlow = MutableStateFlow<AccessibilityService?>(null)
    private val currentRepo = DashBuddyApplication.currentRepo
    private val eventScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // This runnable will contain the logic to process the event after the delay.
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private val debounceRunnable = Runnable {
        Log.d(TAG, "Processing debounced event.")
        _serviceFlow.value?.let { activeService ->
            debouncedEvent?.let { event ->
                eventScope.launch {
                    processEvent(event, activeService, debouncedRootNode, debouncedRootNodeTexts)
                }
            }
        }
    }

    fun setServiceInstance(service: AccessibilityService) {
        _serviceFlow.value = service
    }

    fun clearServiceInstance() {
        _serviceFlow.value = null
    }

    /**
     * A data class to hold a robust signature of the screen state.
     * It includes text, structure, and source node information for accurate comparison.
     */
    private data class ScreenFingerprint(
        val rootNodeTexts: List<String>,
        val rootTextsHash: Int,
        val rootStructureHash: Int,
        val sourceNodeClassName: CharSequence?,
        val sourceNodeViewId: String?,
        val sourceNodeText: CharSequence?
    )

    /** Initialize the state machine. */
    fun initializeStateManager() {
        val initialContext = StateContext(
            timestamp = Date().time,
            eventTypeString = "INITIALIZATION",
        )
        StateManager.initialize(initialContext)
    }

    /**
     * Main entry point for handling events from the AccessibilityService.
     * It decides whether to process an event immediately or to debounce it.
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    fun handleEvent(
        event: AccessibilityEvent,
        service: AccessibilityService,
        rootNode: AccessibilityNodeInfo
    ) {
        if (event.packageName != "com.doordash.driverapp") {
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                Log.v(
                    TAG,
                    "High-priority event '${
                        AccessibilityEvent.eventTypeToString(event.eventType)
                    }' received. Processing immediately."
                )
                // Cancel any pending debounced event because this new event is more important.
                handler.removeCallbacks(debounceRunnable)
                debouncedEvent = null
                // Process this high-priority event right away.
                val rootNodeTexts = mutableListOf<String>()
                AccNodeUtils.extractTexts(rootNode, rootNodeTexts)
                eventScope.launch {
                    processEvent(event, service, rootNode, rootNodeTexts)
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val rootNodeTexts = mutableListOf<String>()
                AccNodeUtils.extractTexts(rootNode, rootNodeTexts)
                val currentFingerprint =
                    createScreenFingerprint(rootNode, rootNodeTexts, event.source)

                if (currentFingerprint == lastFingerprint) {
                    // This is a truly redundant update, ignore it.
                    Log.v(TAG, "Content changed but fingerprint is identical. Ignoring.")
                    return
                }
                // The screen has changed, update the fingerprint.
                lastFingerprint = currentFingerprint

                // The screen is different, so we want to process this event.
                // However, we'll wait briefly to batch any other rapid-fire changes.
                Log.v(TAG, "Content changed with new fingerprint. Debouncing...")
                handler.removeCallbacks(debounceRunnable)
                debouncedEvent = event // Store a copy of the new event
                debouncedRootNode = rootNode // Store a copy of the root node
                debouncedRootNodeTexts = rootNodeTexts
                handler.postDelayed(debounceRunnable, DEBOUNCE_DELAY_MS)
            }

            else -> {
                // For any other event type, we can choose to ignore or handle as needed.
                // For now, we ignore to reduce noise.
            }
        }
    }

    /**
     * This is the core logic that extracts data, recognizes the screen, and dispatches the state.
     * It's called either immediately (for clicks) or after a delay (for content changes).
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private suspend fun processEvent(
        event: AccessibilityEvent,
        service: AccessibilityService,
        rootNodePassed: AccessibilityNodeInfo?,
        rootNodeTexts: List<String>
    ) {
        var rootNode = rootNodePassed
        if (rootNode == null) {
            rootNode = service.rootInActiveWindow ?: return
        }

        val uiNodeTree = UiNode.from(rootNode)
        Log.d(TAG, "UI Node Tree: $uiNodeTree")

        val currentDashState: CurrentEntity? = currentRepo.getCurrentDashState()

        val currentEventType = event.eventType
        Log.d(
            TAG,
            "--- Processing Event: ${AccessibilityEvent.eventTypeToString(currentEventType)} ---"
        )

        val sourceNodeTexts = mutableListOf<String>()
        event.source?.let { AccNodeUtils.extractTexts(it, sourceNodeTexts) }

        if (rootNodeTexts.isNotEmpty()) {
            Log.d(TAG, "Screen texts: ${rootNodeTexts.joinToString(" | ")}")
        } else {
            Log.d(TAG, "No screen texts extracted.")
        }
        if (sourceNodeTexts.isNotEmpty()) {
            Log.d(TAG, "Source texts: ${sourceNodeTexts.joinToString(" | ")}")
        }

        var clickInfo: ClickInfo = ClickInfo.NoClick
        if (currentEventType == AccessibilityEvent.TYPE_VIEW_CLICKED)
            clickInfo = ClickParser.parse(sourceNodeTexts)
        val tempContext = StateContext(
            timestamp = Date().time,
            odometerReading = LocationService.getCurrentOdometer(DashBuddyApplication.context),
            eventType = currentEventType,
            eventTypeString = AccessibilityEvent.eventTypeToString(currentEventType),
            packageName = event.packageName,
            rootNode = rootNode,
            rootUiNode = uiNodeTree,
            sourceClassName = event.className,
            sourceNode = event.source,
            rootNodeTexts = rootNodeTexts,
            sourceNodeTexts = sourceNodeTexts,
            clickInfo = clickInfo,
            currentDashState = currentDashState,
        )
        val finalContext = tempContext.copy(
            screenInfo = ScreenRecognizerV2.identify(tempContext)
        )

        Log.d(TAG, "Sending event to StateManager with context: $finalContext")
        StateManager.dispatchEvent(finalContext)

        // Update the last known screen for context in the next recognition
        lastDasherScreen = finalContext.screenInfo?.screen
        // Make sure to clean up the event if it was the one we held onto
        if (event == debouncedEvent) {
            debouncedEvent = null
        }
    }

    /**
     * Creates a detailed fingerprint of the current screen state.
     */
    private fun createScreenFingerprint(
        rootNode: AccessibilityNodeInfo,
        rootNodeTexts: List<String>,
        sourceNode: AccessibilityNodeInfo?
    ): ScreenFingerprint {

        val structureInfo = StringBuilder()
        AccNodeUtils.extractStructure(rootNode, structureInfo)

        return ScreenFingerprint(
            rootNodeTexts = rootNodeTexts,
            rootTextsHash = Objects.hash(rootNodeTexts),
            rootStructureHash = structureInfo.toString().hashCode(),
            sourceNodeClassName = sourceNode?.className,
            sourceNodeViewId = sourceNode?.viewIdResourceName,
            sourceNodeText = sourceNode?.text ?: sourceNode?.contentDescription
        )
    }
}