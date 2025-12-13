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
import cloud.trotter.dashbuddy.statev2.StateManagerV2
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
import java.util.concurrent.atomic.AtomicInteger

object EventHandler {

    private const val TAG = "EventHandler"
    private const val DEBOUNCE_DELAY_MS = 50L
    private const val MAX_DEBOUNCE_WAIT_MS = 300L

    private val handler = Handler(Looper.getMainLooper())
    private var lastFingerprint: ScreenFingerprint? = null

    private var lastProcessTimestamp: Long = 0L
    private var lastDasherScreen: DasherScreen? = null

    // Debug counter to track queue depth
    private val pendingTaskCount = AtomicInteger(0)

    private var debouncedEvent: AccessibilityEvent? = null
    private var debouncedRootNode: AccessibilityNodeInfo? = null
    private var debouncedRootNodeTexts: List<String> = emptyList()

    private val _serviceFlow = MutableStateFlow<AccessibilityService?>(null)
    private val currentRepo = DashBuddyApplication.currentRepo

    // *** FIX: Use Default (Background) dispatcher to prevent Main Thread starvation ***
    private val eventScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private val debounceRunnable = Runnable {
        Log.d(TAG, "DEBUG: Debounce Runnable executing on Main Thread.")
        processDebouncedEvent()
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun processDebouncedEvent() {
        _serviceFlow.value?.let { activeService ->
            debouncedEvent?.let { event ->
                lastProcessTimestamp = System.currentTimeMillis()

                val count = pendingTaskCount.incrementAndGet()
                Log.d(TAG, "DEBUG: Launching background process. Queue depth: $count")

                eventScope.launch {
                    try {
                        processEvent(
                            event,
                            activeService,
                            debouncedRootNode,
                            debouncedRootNodeTexts
                        )
                    } finally {
                        pendingTaskCount.decrementAndGet()
                    }
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

    fun getServiceInstance(): AccessibilityService? {
        return _serviceFlow.value
    }

    private data class ScreenFingerprint(
        val rootNodeTexts: List<String>,
        val rootTextsHash: Int,
        val rootStructureHash: Int,
        val sourceNodeClassName: CharSequence?,
        val sourceNodeViewId: String?,
        val sourceNodeText: CharSequence?
    )

    fun initializeStateManager() {
        val initialContext = StateContext(
            timestamp = Date().time,
            eventTypeString = "INITIALIZATION",
        )
        StateManager.initialize(initialContext)
        StateManagerV2.initialize()
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    fun handleEvent(
        event: AccessibilityEvent,
        service: AccessibilityService,
        // rootNode is passed via manual call in Service
    ) {
        // Log immediately to prove event reception
        // Log.v(TAG, "VERBOSE :: EVENT RECEIVED :: ${AccessibilityEvent.eventTypeToString(event.eventType)}")

        if (event.packageName?.toString() != "com.doordash.driverapp") return

        // Capture immediately as requested
        val rootNode = service.rootInActiveWindow

        if (rootNode == null) {
            Log.w(TAG, "DEBUG: rootInActiveWindow returned NULL. Ignoring event.")
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                Log.i(TAG, "DEBUG: High Priority Event. Processing Immediately.")
                handler.removeCallbacks(debounceRunnable)

                lastProcessTimestamp = System.currentTimeMillis()

                val rootNodeTexts = mutableListOf<String>()
                AccNodeUtils.extractTexts(rootNode, rootNodeTexts)

                pendingTaskCount.incrementAndGet()
                eventScope.launch {
                    try {
                        processEvent(event, service, rootNode, rootNodeTexts)
                    } finally {
                        pendingTaskCount.decrementAndGet()
                    }
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

                val now = System.currentTimeMillis()
                val timeSinceLastProcess = now - lastProcessTimestamp

                debouncedEvent = event
                debouncedRootNode = rootNode
                debouncedRootNodeTexts = rootNodeTexts

                if (timeSinceLastProcess > MAX_DEBOUNCE_WAIT_MS) {
                    Log.w(
                        TAG,
                        "DEBUG: Starvation detected (Wait > ${timeSinceLastProcess}ms). Forcing Runnable."
                    )
                    handler.removeCallbacks(debounceRunnable)
                    debounceRunnable.run() // Runs synchronously on Main Thread, but launches coroutine on Default
                } else {
                    // Log.d(TAG, "DEBUG: Debouncing (Wait: ${timeSinceLastProcess}ms)")
                    handler.removeCallbacks(debounceRunnable)
                    handler.postDelayed(debounceRunnable, DEBOUNCE_DELAY_MS)
                }
            }

            else -> {}
        }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private suspend fun processEvent(
        event: AccessibilityEvent,
        service: AccessibilityService,
        rootNodePassed: AccessibilityNodeInfo?,
        rootNodeTexts: List<String>
    ) {
        Log.d(TAG, "DEBUG: processEvent STARTED on ${Thread.currentThread().name}")

        var rootNode = rootNodePassed
        if (rootNode == null) {
            Log.w(TAG, "DEBUG: RootNode passed was null. Attempting fetch...")
            rootNode = service.rootInActiveWindow ?: run {
                Log.e(TAG, "DEBUG: Fetch failed. Aborting processEvent.")
                return
            }
        }

        // Just confirming we got here
        val nodeCount = rootNode.childCount
        Log.v(TAG, "DEBUG: RootNode valid. Child count: $nodeCount")

        val uiNodeTree = UiNode.from(rootNode)
        Log.d(TAG, "UI Node Tree: $uiNodeTree")

        // ... rest of your logic ...
        val currentDashState: CurrentEntity? = currentRepo.getCurrentDashState()
        val currentEventType = event.eventType

        val sourceNodeTexts = mutableListOf<String>()
        event.source?.let { AccNodeUtils.extractTexts(it, sourceNodeTexts) }

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

        Log.d(TAG, "Sending event to StateManager: ${finalContext.screenInfo?.screen}")
        StateManager.dispatchEvent(finalContext)
        StateManagerV2.dispatch(finalContext)

        lastDasherScreen = finalContext.screenInfo?.screen
        if (event == debouncedEvent) {
            debouncedEvent = null
        }
    }

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