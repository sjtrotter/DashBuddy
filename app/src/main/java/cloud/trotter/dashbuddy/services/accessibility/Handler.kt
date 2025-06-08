package cloud.trotter.dashbuddy.services.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import cloud.trotter.dashbuddy.DashBuddyApplication
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
 */
object Handler {

    private const val TAG = "AccessibilityHandler"

    // Variables to store details of the last processed event for debouncing
    private var lastEventType: Int = -1
    private var lastPackageName: CharSequence? = null
    private var lastClassName: CharSequence? = null
    private var lastScreenTextsHash: Int = 0
    private var lastSourceTextsHash: Int = 0
    private var lastDasherScreen: DasherScreen? = null

    /** Initialize the state machine.
     * @param context The application context.
     */
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
     * Handles the raw accessibility event.
     * The service can call this method, passing the event and itself (as context if needed,
     * or for further actions like performing gestures or finding nodes).
     *
     * For now, it will just log basic event information.
     * Later, this is where you'd extract detailed information from the event's source node.
     */
    fun handleEvent(event: AccessibilityEvent, service: AccessibilityService) {
        val currentEventType = event.eventType
        val currentPackageName = event.packageName
        val currentClassName = event.className
        val currentEventSource: AccessibilityNodeInfo? = event.source
// Log raw event for debugging if needed, but it is noisy.
//        Log.d(TAG, "Received Event: ${AccessibilityEvent.eventTypeToString(event.eventType)}, Package: ${event.packageName}, Class: ${event.className}")
        // Log.d(TAG, "Event Text: ${event.text.joinToString()}") // event.text is often empty, need to traverse nodes

        // Only process events from DoorDash Driver App
        if (currentPackageName == null || currentPackageName != "com.doordash.driverapp") {
            return
        }


        val rootNode: AccessibilityNodeInfo? = service.rootInActiveWindow
        if (rootNode == null) {
            if (lastPackageName != null || lastClassName != null) {
                Log.w(
                    TAG,
                    "Root node is null for event: ${AccessibilityEvent.eventTypeToString(event.eventType)}"
                )
            }
            return
        }

        val currentScreenTexts = mutableListOf<String>()
        extractTextsFromNode(rootNode, currentScreenTexts)

        val currentSourceTexts = mutableListOf<String>()
        if (currentEventSource != null) {
            extractTextsFromNode(currentEventSource, currentSourceTexts)
        }

        // if source text and screen text equal, clear source text.
//        if (currentSourceTexts == currentScreenTexts) {
//            currentSourceTexts.clear()
//        }

        val currentExtractedTextsHash = Objects.hash(currentScreenTexts)
        val currentEventSourceHash = Objects.hash(currentSourceTexts)

        // create temp state context
        val tempContext = StateContext(
            timestamp = Date().time,
            androidAppContext = DashBuddyApplication.context,
            eventType = currentEventType,
            eventTypeString = AccessibilityEvent.eventTypeToString(currentEventType),
            packageName = currentPackageName,
            rootNode = rootNode,
            sourceClassName = currentClassName,
            screenTexts = currentScreenTexts,
            sourceNodeTexts = currentSourceTexts,
        )

        // determine current Doordash screen
        val currentDasherScreen = ScreenRecognizer.identify(tempContext, lastDasherScreen)
//        Log.d(TAG, "Current Dasher Screen: $currentDasherScreen")

        // Debouncing logic.
        if (
            ( // currentEventType == lastEventType &&
//                currentPackageName == lastPackageName &&
//                currentClassName == lastClassName &&
                    currentExtractedTextsHash == lastScreenTextsHash &&
                            currentEventSourceHash == lastSourceTextsHash
                    )
            || // specific screens check for skip
            (currentDasherScreen == lastDasherScreen &&
                    currentDasherScreen == DasherScreen.NAVIGATION_VIEW
                    )
            ||
            (currentDasherScreen == lastDasherScreen &&
                    currentDasherScreen == DasherScreen.OFFER_POPUP &&
                    currentEventSourceHash == lastSourceTextsHash
                    )
        //            currentDasherScreen == lastDasherScreen
        ) {
//            Log.d(TAG, "Event is a duplicate. Ignoring.") // verbose logging for duplicate events
            return
        }

        Log.d(
            TAG,
            "Processing Event: ${AccessibilityEvent.eventTypeToString(currentEventType)}, Pkg: $currentPackageName, Class: $currentClassName"
        )
        // for debug, send screen name to bubble.
//        DashBuddyApplication.sendBubbleMessage("${currentDasherScreen.screenName} Screen")

        if (currentScreenTexts.isNotEmpty()) {
            Log.d(TAG, "Screen texts: [${currentScreenTexts.joinToString(" | ")}]")
        } else {
            Log.d(TAG, "No texts extracted.")
        }

        if (currentSourceTexts.isNotEmpty()) {
            Log.d(TAG, "Source texts: [${currentSourceTexts.joinToString(" | ")}]")
        } else {
            Log.d(TAG, "No source texts extracted.")
        }

        // Update StateContext with DasherScreen
        val finalContext = tempContext.copy(
            dasherScreen = currentDasherScreen
        )

        // Send to StateManager
        StateManager.dispatchEvent(finalContext)

        // Update last event details for debouncing
        lastEventType = currentEventType
        lastPackageName = currentPackageName
        lastClassName = currentClassName
        lastScreenTextsHash = currentExtractedTextsHash
        lastSourceTextsHash = currentEventSourceHash
        lastDasherScreen = currentDasherScreen
    }

    /**
     * Recursively extracts text from a node and its children.
     * @param nodeInfo The current node to process.
     * @param texts A mutable list to accumulate the extracted texts.
     */
    private fun extractTextsFromNode(nodeInfo: AccessibilityNodeInfo?, texts: MutableList<String>) {
        if (nodeInfo == null) {
            return
        }
        nodeInfo.text?.let {
            if (it.isNotEmpty()) {
                texts.add(it.toString().trim())
            }
        }
        nodeInfo.contentDescription?.let {
            val desc = it.toString().trim()
            if (desc.isNotEmpty() && (nodeInfo.text == null || desc != nodeInfo.text.toString()
                    .trim())
            ) {
                texts.add(desc)
            }
        }
        for (i in 0 until nodeInfo.childCount) {
            extractTextsFromNode(nodeInfo.getChild(i), texts)
        }
    }
}
