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
    private const val MAX_DEBOUNCE_WAIT_MS = 300L // Force update after this time

    // --- State for new debouncing and fingerprinting logic ---
    private val handler = Handler(Looper.getMainLooper())
    private var lastFingerprint: ScreenFingerprint? = null

    // NEW: Track when we last actually processed a frame
    private var lastProcessTimestamp: Long = 0L
    private var lastDasherScreen: DasherScreen? = null
    private var debouncedEvent: AccessibilityEvent? = null
    private var debouncedRootNode: AccessibilityNodeInfo? = null
    private var debouncedRootNodeTexts: List<String> = emptyList()
    private val _serviceFlow = MutableStateFlow<AccessibilityService?>(null)
    private val currentRepo = DashBuddyApplication.currentRepo
    private val eventScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private val debounceRunnable = Runnable {
        Log.d(TAG, "Processing debounced event.")
        processDebouncedEvent() // Extracted to a helper function
    }

    // Helper to actually run the logic and update the timestamp
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun processDebouncedEvent() {
        _serviceFlow.value?.let { activeService ->
            debouncedEvent?.let { event ->
                lastProcessTimestamp = System.currentTimeMillis() // Reset timer
                eventScope.launch {
                    processEvent(event, activeService, debouncedRootNode, debouncedRootNodeTexts)
                }
            }
        }
        debouncedEvent = null
        debouncedRootNode = null
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
//        rootNode: AccessibilityNodeInfo
    ) {
        if (event.packageName?.toString() != "com.doordash.driverapp") return

        // Safe fetch
        val rootNode = service.rootInActiveWindow ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                Log.v(TAG, "High-priority event. Processing immediately.")
                handler.removeCallbacks(debounceRunnable)

                // Update timestamp so we don't double-process immediately after
                lastProcessTimestamp = System.currentTimeMillis()

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
                    return
                }
                lastFingerprint = currentFingerprint

                // --- THE FIX IS HERE ---
                val now = System.currentTimeMillis()
                val timeSinceLastProcess = now - lastProcessTimestamp

                // Store event data for the runnable
                debouncedEvent = event
                debouncedRootNode = rootNode
                debouncedRootNodeTexts = rootNodeTexts

                if (timeSinceLastProcess > MAX_DEBOUNCE_WAIT_MS) {
                    // It has been too long since we looked at the screen.
                    // Force an update NOW, ignoring the debounce delay.
                    Log.d(
                        TAG,
                        "Debounce starvation detected (Wait > ${MAX_DEBOUNCE_WAIT_MS}ms). Forcing update."
                    )
                    handler.removeCallbacks(debounceRunnable)
                    debounceRunnable.run()
                } else {
                    // Standard Debounce
                    handler.removeCallbacks(debounceRunnable)
                    handler.postDelayed(debounceRunnable, DEBOUNCE_DELAY_MS)
                }
            }

            else -> {

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