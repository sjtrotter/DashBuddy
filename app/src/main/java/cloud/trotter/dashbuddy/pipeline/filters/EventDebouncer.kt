package cloud.trotter.dashbuddy.pipeline.filters

import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class EventDebouncer(
    private val delayMs: Long,
    private val onStable: (AccessibilityEvent, AccessibilityNodeInfo) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var pendingEvent: AccessibilityEvent? = null
    private var pendingRoot: AccessibilityNodeInfo? = null

    private val runnable = Runnable {
        val event = pendingEvent ?: return@Runnable
        val root = pendingRoot ?: return@Runnable
        onStable(event, root)

        pendingEvent = null
        pendingRoot = null
    }

    fun submit(event: AccessibilityEvent, rootNode: AccessibilityNodeInfo) {
        pendingEvent = event
        pendingRoot = rootNode // We hold the ref so we can pass it down
        handler.removeCallbacks(runnable)
        handler.postDelayed(runnable, delayMs)
    }

    fun cancel() {
        handler.removeCallbacks(runnable)
        pendingEvent = null
        pendingRoot = null
    }
}