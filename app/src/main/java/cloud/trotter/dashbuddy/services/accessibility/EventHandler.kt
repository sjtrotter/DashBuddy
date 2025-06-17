package cloud.trotter.dashbuddy.services.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import cloud.trotter.dashbuddy.DashBuddyApplication
import kotlinx.coroutines.flow.MutableStateFlow
import cloud.trotter.dashbuddy.state.screens.Screen as DasherScreen
import cloud.trotter.dashbuddy.state.screens.Recognizer as ScreenRecognizer
import cloud.trotter.dashbuddy.state.Context as StateContext
import cloud.trotter.dashbuddy.state.Manager as StateManager
import java.util.Date
import java.util.Objects
import cloud.trotter.dashbuddy.log.Logger as Log

/**
 * Singleton object to handle accessibility events.
 * This allows for centralized processing and state management
 * related to detected events.
 *
 * REFINED VERSION with advanced debouncing and screen fingerprinting.
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
    private val _serviceFlow = MutableStateFlow<AccessibilityService?>(null)

    // This runnable will contain the logic to process the event after the delay.
    private val debounceRunnable = Runnable {
        Log.d(TAG, "Processing debounced event.")
        _serviceFlow.value?.let { activeService ->
            debouncedEvent?.let { event ->
                processEvent(event, activeService, debouncedRootNode)
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
            screenTexts = emptyList(),
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
                    "High-priority event '${AccessibilityEvent.eventTypeToString(event.eventType)}' received. Processing immediately."
                )
                // Cancel any pending debounced event because this new event is more important.
                handler.removeCallbacks(debounceRunnable)
                debouncedEvent = null
                // Process this high-priority event right away.
                processEvent(event, service, rootNode)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val currentFingerprint = createScreenFingerprint(rootNode, event.source)

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
        rootNodePassed: AccessibilityNodeInfo?
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

        val currentScreenTexts = mutableListOf<String>()
        extractTextsFromNode(rootNode, currentScreenTexts)

        val currentSourceTexts = mutableListOf<String>()
        event.source?.let { extractTextsFromNode(it, currentSourceTexts) }

        if (currentScreenTexts.isNotEmpty()) {
            Log.d(TAG, "Screen texts: ${currentScreenTexts.joinToString(" | ")}")
        } else {
            Log.d(TAG, "No screen texts extracted.")
        }
        if (currentSourceTexts.isNotEmpty()) {
            Log.d(TAG, "Source texts: ${currentSourceTexts.joinToString(" | ")}")
        }

        val tempContext = StateContext(
            timestamp = Date().time,
            androidAppContext = DashBuddyApplication.context,
            eventType = currentEventType,
            eventTypeString = AccessibilityEvent.eventTypeToString(currentEventType),
            packageName = event.packageName,
            rootNode = rootNode,
            sourceClassName = event.className,
            screenTexts = currentScreenTexts,
            sourceNodeTexts = currentSourceTexts,
        )
        val finalContext = tempContext.copy(
            dasherScreen = ScreenRecognizer.identify(tempContext, lastDasherScreen)
        )

        StateManager.dispatchEvent(finalContext)

        // Update the last known screen for context in the next recognition
        lastDasherScreen = finalContext.dasherScreen
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
        sourceNode: AccessibilityNodeInfo?
    ): ScreenFingerprint {
        val screenTexts = mutableListOf<String>()
        extractTextsFromNode(rootNode, screenTexts)

        val structureInfo = StringBuilder()
        extractStructureInfo(rootNode, structureInfo)

        return ScreenFingerprint(
            rootTextsHash = Objects.hash(screenTexts),
            rootStructureHash = structureInfo.toString().hashCode(),
            sourceNodeClassName = sourceNode?.className,
            sourceNodeViewId = sourceNode?.viewIdResourceName,
            sourceNodeText = sourceNode?.text ?: sourceNode?.contentDescription
        )
    }

    /**
     * Recursively extracts identifying structural info (class name, view ID) from nodes.
     * This helps differentiate screens with identical text but different layouts.
     */
    private fun extractStructureInfo(nodeInfo: AccessibilityNodeInfo?, builder: StringBuilder) {
        if (nodeInfo == null || !nodeInfo.isVisibleToUser) return

        builder.append(nodeInfo.className)
        builder.append(nodeInfo.viewIdResourceName)
        // Add other stable attributes if needed (e.g., isClickable)
        // builder.append(nodeInfo.isClickable)

        for (i in 0 until nodeInfo.childCount) {
            extractStructureInfo(nodeInfo.getChild(i), builder)
        }
    }

    /**
     * Recursively extracts visible text from a node and its children.
     */
    private fun extractTextsFromNode(nodeInfo: AccessibilityNodeInfo?, texts: MutableList<String>) {
        if (nodeInfo == null || !nodeInfo.isVisibleToUser) return

        nodeInfo.text?.let {
            if (it.isNotEmpty()) texts.add(it.toString().trim())
        }
        nodeInfo.contentDescription?.let {
            val desc = it.toString().trim()
            if (desc.isNotEmpty() && !texts.contains(desc)) {
                texts.add(desc)
            }
        }
        for (i in 0 until nodeInfo.childCount) {
            extractTextsFromNode(nodeInfo.getChild(i), texts)
        }
    }
}