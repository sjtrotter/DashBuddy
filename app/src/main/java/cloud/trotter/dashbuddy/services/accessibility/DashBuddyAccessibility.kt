package cloud.trotter.dashbuddy.services.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.services.LocationService
import cloud.trotter.dashbuddy.log.Logger as Log

class DashBuddyAccessibility : AccessibilityService() {

    private lateinit var eventHandler: EventHandler
    private val tag = "DashBuddyAccessibility"

    override fun onCreate() {
        super.onCreate()
        Log.d(tag, "Accessibility service created")
        eventHandler = EventHandler
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // --- TRIGGER: Keep the Odometer Service Alive ---
        try {
            val keepAliveIntent = Intent(this, LocationService::class.java).apply {
                action = LocationService.ACTION_KEEP_ALIVE
            }
            startService(keepAliveIntent)
        } catch (e: Exception) {
            Log.e(tag, "Failed to send Keep Alive to LocationService", e)
        }
        val rootNode = rootInActiveWindow
        if (event != null && rootNode != null) {
            eventHandler.handleEvent(event, this, rootNode)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        eventHandler.clearServiceInstance()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        Log.d(tag, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(tag, "Accessibility service destroyed")
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

        eventHandler.initializeStateManager()
        eventHandler.setServiceInstance(this)

        Log.d(tag, "Accessibility service connected")
    }

}
