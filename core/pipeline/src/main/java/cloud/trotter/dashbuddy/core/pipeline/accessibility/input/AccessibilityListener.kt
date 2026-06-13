package cloud.trotter.dashbuddy.core.pipeline.accessibility.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import cloud.trotter.dashbuddy.core.pipeline.BuildConfig
import cloud.trotter.dashbuddy.domain.settings.PlatformPreferences
import cloud.trotter.dashbuddy.domain.state.Platform
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class AccessibilityListener : AccessibilityService() {

    @Inject
    lateinit var accessibilitySource: AccessibilitySource

    @Inject
    lateinit var platformPreferences: PlatformPreferences



    override fun onCreate() {
        super.onCreate()
        Timber.d("Accessibility service created")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString()

        // In debug builds we receive ALL event types from ALL packages.
        // Log unhandled types so we can see what fires, then return.
        if (BuildConfig.DEBUG && event.eventType !in HANDLED_TYPES) {
            if (pkg in platformPreferences.enabledPackages.value) {
                Timber.d(
                    "🔎 Unhandled event: type=0x%04x (%s) pkg=%s class=%s",
                    event.eventType,
                    AccessibilityEvent.eventTypeToString(event.eventType),
                    pkg,
                    event.className,
                )
            }
            return
        }

        if (pkg !in platformPreferences.enabledPackages.value) return

        accessibilitySource.emit(event)
    }

    override fun onInterrupt() {
        Timber.d("Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("Accessibility service destroyed")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        Timber.d("Accessibility service connected")

        // In debug builds, widen to ALL event types and ALL packages so we can
        // observe what fires without needing handlers for every type.
        if (BuildConfig.DEBUG) {
            serviceInfo = serviceInfo.apply {
                eventTypes = AccessibilityServiceInfo.DEFAULT or AccessibilityEvent.TYPES_ALL_MASK
                packageNames = null // all packages — code-level filter still gates the pipeline
            }
            Timber.i("Debug: accessibility service widened to typeAllMask, all packages")
        }

        // Register with the source
        accessibilitySource.registerService(this)

    }

    companion object {
        /** Event types that have pipeline handlers — everything else is "unhandled". */
        private val HANDLED_TYPES = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
        )
    }
}
