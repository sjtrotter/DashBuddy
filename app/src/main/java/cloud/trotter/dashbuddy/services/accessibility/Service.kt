package cloud.trotter.dashbuddy.services.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import cloud.trotter.dashbuddy.services.accessibility.Handler as EventHandler
import cloud.trotter.dashbuddy.log.Logger as Log

class Service : AccessibilityService() {

    private lateinit var eventHandler: EventHandler

    override fun onCreate() {
        super.onCreate()
        Log.d("Accessibility", "Accessibility service created")
        eventHandler = EventHandler
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) {
            eventHandler.handleEvent(event, this)
        }
    }

    override fun onInterrupt() {
        Log.d("Accessibility", "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("Accessibility", "Accessibility service destroyed")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            packageNames = arrayOf("com.doordash.driverapp")
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS

        }
        this.serviceInfo = info

        eventHandler.initializeStateManager(applicationContext)

        Log.d("Accessibility", "Accessibility service connected")
    }

}
