package cloud.trotter.dashbuddy.pipeline.inputs

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.log.Logger
import cloud.trotter.dashbuddy.pipeline.Pipeline

class AccessibilityListener : AccessibilityService() {

    private val tag = "DashBuddyAccessibility"

    override fun onCreate() {
        super.onCreate()
        Logger.d(tag, "Accessibility service created")
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val validPackages = setOf("com.doordash.driverapp")
        if (event.packageName?.toString() !in validPackages) {
            Logger.d(tag, "Ignoring event from ${event.packageName}")
            return
        }

        Pipeline.onAccessibilityEvent(event, this)
    }

    override fun onInterrupt() {
        Logger.d(tag, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        _instance = null
        Logger.d(tag, "Accessibility service destroyed")
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override fun onServiceConnected() {
        super.onServiceConnected()

        _instance = this
        Logger.d(tag, "Accessibility service connected")
    }

    companion object {
        @Volatile
        private var _instance: AccessibilityListener? = null

        val instance: AccessibilityListener?
            get() = _instance
    }
}