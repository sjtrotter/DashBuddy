package cloud.trotter.dashbuddy.services.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
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
        if (event == null) return

        if (event.packageName != "com.doordash.driverapp") {
            Log.d(tag, "Ignoring event from ${event.packageName}")
            return
        }
        eventHandler.handleEvent(event, this)
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

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override fun onServiceConnected() {
        super.onServiceConnected()

        // REMOVED: Manual AccessibilityServiceInfo configuration.
        // We now rely entirely on accessibility_service_config.xml.
        // This ensures flags like flagReportViewIds are not overwritten.

        eventHandler.initializeStateManager()
        eventHandler.setServiceInstance(this)

        Log.d(tag, "Accessibility service connected")
    }
}