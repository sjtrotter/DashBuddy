package cloud.trotter.dashbuddy.pipeline.filters

import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import cloud.trotter.dashbuddy.log.Logger as Log

class EventDebouncer(
    private val delayMs: Long,
    private val maxWaitMs: Long = 300L, // Added: Safety valve
    private val onStable: (AccessibilityEvent, AccessibilityNodeInfo) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var pendingEvent: AccessibilityEvent? = null
    private var pendingRoot: AccessibilityNodeInfo? = null
    private var firstEventTime: Long = 0L // Track start of burst

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

        // CHECK STARVATION: Has it been too long since we started waiting?
        if (now - firstEventTime > maxWaitMs) {
            Log.w("EventDebouncer", "Starvation detected. Forcing update.")
            handler.removeCallbacks(runnable)
            processNow()
        } else {
            // Standard debounce reset
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