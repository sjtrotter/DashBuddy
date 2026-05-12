package cloud.trotter.dashbuddy.core.pipeline.accessibility.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import cloud.trotter.dashbuddy.core.pipeline.BuildConfig
import cloud.trotter.dashbuddy.core.data.settings.PlatformPreferencesRepository
import cloud.trotter.dashbuddy.domain.state.Platform
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class AccessibilityListener : AccessibilityService() {

    @Inject
    lateinit var accessibilitySource: AccessibilitySource

    @Inject
    lateinit var platformPreferences: PlatformPreferencesRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Cached set of enabled package names — updated reactively from preferences. */
    @Volatile
    private var enabledPackages: Set<String> = Platform.watchedPackages()

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
            if (pkg in enabledPackages) {
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

        if (pkg !in enabledPackages) return

        accessibilitySource.emit(event)
    }

    override fun onInterrupt() {
        Timber.d("Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        _instance = null
        serviceScope.cancel()
        Timber.d("Accessibility service destroyed")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        _instance = this
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

        // Start collecting enabled platforms preference
        serviceScope.launch {
            platformPreferences.enabledPackages.collect { packages ->
                enabledPackages = packages
                Timber.d("Accessibility filter updated: %s", packages)
            }
        }
    }

    companion object {
        @Volatile
        private var _instance: AccessibilityListener? = null

        val instance: AccessibilityListener?
            get() = _instance

        /** Event types that have pipeline handlers — everything else is "unhandled". */
        private val HANDLED_TYPES = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
        )
    }
}
