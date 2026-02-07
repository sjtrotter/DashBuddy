package cloud.trotter.dashbuddy.pipeline.inputs

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.pipeline.Pipeline
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class AccessibilityListener : AccessibilityService() {

    @Inject
    lateinit var pipeline: Pipeline

    override fun onCreate() {
        super.onCreate()
        Timber.d("Accessibility service created")
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // set here for expandability later. i.e. if we decide to do Uber or Grubhub, etc.
        val validPackages = setOf("com.doordash.driverapp")
        if (event.packageName?.toString() !in validPackages) {
            Timber.d("Ignoring event from ${event.packageName}")
            return
        }

        pipeline.onAccessibilityEvent(event, this)
    }

    override fun onInterrupt() {
        Timber.d("Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        _instance = null
        Timber.d("Accessibility service destroyed")
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override fun onServiceConnected() {
        super.onServiceConnected()

        _instance = this
        Timber.d("Accessibility service connected")
    }

    companion object {
        @Volatile
        private var _instance: AccessibilityListener? = null

        val instance: AccessibilityListener?
            get() = _instance
    }
}