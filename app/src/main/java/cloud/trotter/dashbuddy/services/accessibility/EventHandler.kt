package cloud.trotter.dashbuddy.services.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.state.ClickInfo
import cloud.trotter.dashbuddy.state.parsers.ClickParser
import cloud.trotter.dashbuddy.state.screens.ScreenRecognizerV2
import cloud.trotter.dashbuddy.util.AccNodeUtils
import kotlinx.coroutines.flow.MutableStateFlow
import cloud.trotter.dashbuddy.state.screens.Screen as DasherScreen
import cloud.trotter.dashbuddy.state.screens.Recognizer as ScreenRecognizer
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
    private const val DEBOUNCE_DELAY_MS = 200L // Delay for content changes

    // --- State for new debouncing and fingerprinting logic ---
    private val handler = Handler(Looper.getMainLooper())
    private var lastFingerprint: ScreenFingerprint? = null
    private var lastDasherScreen: DasherScreen? = null
    private var debouncedEvent: AccessibilityEvent? = null
    private var debouncedRootNode: AccessibilityNodeInfo? = null
    private var debouncedRootNodeTexts: List<String> = emptyList()
    private val _serviceFlow = MutableStateFlow<AccessibilityService?>(null)

    // This runnable will contain the logic to process the event after the delay.
    private val debounceRunnable = Runnable {
        Log.d(TAG, "Processing debounced event.")
        _serviceFlow.value?.let { activeService ->
            debouncedEvent?.let { event ->
                processEvent(event, activeService, debouncedRootNode, debouncedRootNodeTexts)
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
    fun initializeStateManager(context: Context) {
        val initialContext = StateContext(
            timestamp = Date().time,
            androidAppContext = context,
            eventType = null,
            eventTypeString = "INITIALIZATION",
            packageName = null,
            rootNode = null,
            sourceClassName = null,
            rootNodeTexts = emptyList(),
            sourceNodeTexts = emptyList(),
            sourceNode = null,
            dasherScreen = null
        )
        StateManager.initialize(initialContext)
    }

    /**
     * Main entry point for handling events from the AccessibilityService.
     * It decides whether to process an event immediately or to debounce it.
     */
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
                processEvent(event, service, rootNode, rootNodeTexts)
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
    private fun processEvent(
        event: AccessibilityEvent,
        service: AccessibilityService,
        rootNodePassed: AccessibilityNodeInfo?,
        rootNodeTexts: List<String>
    ) {
        var rootNode = rootNodePassed
        if (rootNode == null) {
            rootNode = service.rootInActiveWindow ?: return
        }

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
            androidAppContext = DashBuddyApplication.context,
            eventType = currentEventType,
            eventTypeString = AccessibilityEvent.eventTypeToString(currentEventType),
            packageName = event.packageName,
            rootNode = rootNode,
            sourceClassName = event.className,
            sourceNode = event.source,
            rootNodeTexts = rootNodeTexts,
            sourceNodeTexts = sourceNodeTexts,
            clickInfo = clickInfo
        )
        val finalContext = tempContext.copy(
            dasherScreen = ScreenRecognizer.identify(tempContext, lastDasherScreen),
            screenInfo = ScreenRecognizerV2.identify(tempContext)
        )

        StateManager.dispatchEvent(finalContext)

        // Update the last known screen for context in the next recognition
        lastDasherScreen = finalContext.dasherScreen
        // Make sure to clean up the event if it was the one we held onto
        if (event == debouncedEvent) {
            debouncedEvent = null
        }

        // Testing ScreenRecognizerV2
        try {
            val screenInfoV2 = ScreenRecognizerV2.identify(finalContext)
            Log.i(TAG, "[V2-TEST] Recognized ScreenInfo: $screenInfoV2")
        } catch (e: Exception) {
            Log.e(TAG, "[V2-TEST] Exception while recognizing screen", e)
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