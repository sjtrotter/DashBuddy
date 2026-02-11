package cloud.trotter.dashbuddy.pipeline.accessibility.screen.filters

import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class EventDebouncer(
    private val delayMs: Long,
    private val maxWaitMs: Long = 300L,
    private val onStable: (AccessibilityEvent, AccessibilityNodeInfo) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var pendingEvent: AccessibilityEvent? = null
    private var pendingRoot: AccessibilityNodeInfo? = null
    private var firstEventTime: Long = 0L

    private val runnable = Runnable {
        processNow()
    }

    fun submit(event: AccessibilityEvent, rootNode: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()

        if (pendingEvent == null) {
            firstEventTime = now
        }

        pendingEvent = event
        pendingRoot = rootNode

        // STARVATION PROTECTION: If we've been waiting too long, force update
        if (now - firstEventTime > maxWaitMs) {
            handler.removeCallbacks(runnable)
            processNow()
        } else {
            handler.removeCallbacks(runnable)
            handler.postDelayed(runnable, delayMs)
        }
    }

    fun cancel() {
        handler.removeCallbacks(runnable)
        pendingEvent = null
        pendingRoot = null
        firstEventTime = 0L
    }

    private fun processNow() {
        val event = pendingEvent ?: return
        val root = pendingRoot ?: return

        onStable(event, root)

        pendingEvent = null
        pendingRoot = null
        firstEventTime = 0L
    }
}