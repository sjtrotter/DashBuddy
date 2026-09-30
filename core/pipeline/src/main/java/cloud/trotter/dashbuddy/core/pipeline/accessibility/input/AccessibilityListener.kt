package cloud.trotter.dashbuddy.core.pipeline.accessibility.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import cloud.trotter.dashbuddy.core.pipeline.BuildConfig
import cloud.trotter.dashbuddy.domain.pipeline.LocaleBoundaryReporter
import cloud.trotter.dashbuddy.domain.settings.EventReceiptConsent
import cloud.trotter.dashbuddy.domain.settings.EventReceiptPreferences
import cloud.trotter.dashbuddy.domain.settings.PlatformPreferences
import cloud.trotter.dashbuddy.domain.state.Platform
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@AndroidEntryPoint
class AccessibilityListener : AccessibilityService() {

    @Inject
    lateinit var accessibilitySource: AccessibilitySource

    @Inject
    lateinit var platformPreferences: PlatformPreferences

    /**
     * #938 — reports the English-locale boundary of recognition + the marker privacy scrubs.
     * Injected as a `:domain` contract (the [PlatformPreferences] pattern) because the notice
     * itself needs `:app`-owned pieces this module must not depend on.
     */
    @Inject
    lateinit var localeBoundaryReporter: LocaleBoundaryReporter

    /**
     * #1151 — the dasher's wide-event-receipt consent (its one owner lives in `:core:data`). The
     * listener is where it is ENFORCED: [applyEventReceipt] maps it through [ServiceInfoPolicy] onto
     * `serviceInfo.packageNames`. No other code path widens the package subscription.
     */
    @Inject
    lateinit var eventReceiptPreferences: EventReceiptPreferences

    /** Service-scoped; created in [onServiceConnected], cancelled in [onUnbind] / [onDestroy]. */
    private var serviceScope: CoroutineScope? = null

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

        // #1148 D2: topology events (TYPE_WINDOWS_CHANGED) pass regardless of their package;
        // every other handled type keeps the enabled-package gate. See [ListenerGate].
        val admitted = ListenerGate.admit(
            type = event.eventType,
            pkg = pkg,
            enabledPackages = platformPreferences.enabledPackages.value,
            handledTypes = HANDLED_TYPES,
        )
        if (!admitted) return

        accessibilitySource.emit(event)
    }

    override fun onInterrupt() {
        Timber.d("Accessibility service interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cancelServiceScope()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cancelServiceScope()
        super.onDestroy()
        Timber.d("Accessibility service destroyed")
    }

    private fun cancelServiceScope() {
        serviceScope?.cancel()
        serviceScope = null
    }

    /**
     * #1151 — apply the consent to the live subscription. `eventTypes` keeps the debug-only
     * widening to every type (unhandled-type logging); `packageNames` is governed ONLY by the
     * consent, in every build type. A null [serviceInfo] (service not connected) is a no-op.
     */
    private fun applyEventReceipt(consent: EventReceiptConsent) {
        val info = serviceInfo ?: return
        val packageNames = try {
            ServiceInfoPolicy.packageNamesFor(consent, Platform.watchedPackages)
        } catch (e: IllegalArgumentException) {
            // LL9: refuse to apply — the manifest's package list stays in force (fail-closed).
            Timber.tag("Pipeline").e(e, "Event receipt: refused to apply an empty package list")
            return
        }
        if (BuildConfig.DEBUG) {
            info.eventTypes = AccessibilityServiceInfo.DEFAULT or AccessibilityEvent.TYPES_ALL_MASK
        }
        info.packageNames = packageNames
        serviceInfo = info
        Timber.tag("Pipeline").i("Event receipt: wide=%s", ServiceInfoPolicy.isWide(consent))
        if (ServiceInfoPolicy.shouldWarnUnreliable(consent, Build.VERSION.SDK_INT, unreliableWarned.get())) {
            if (unreliableWarned.compareAndSet(false, true)) {
                Timber.tag("Pipeline").w(
                    "wide event receipt may not take effect on Android 11 (framework filter is additive)",
                )
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        Timber.d("Accessibility service connected")

        // #1151: the package subscription follows the dasher's event-receipt consent — applied
        // now (Main.immediate runs the StateFlow's current value synchronously, before the
        // source registers) and re-applied on every change (Allow / revoke in Settings). Until
        // the store is read the value is UNDECIDED, i.e. the filtered footprint (fail-closed).
        // The debug build no longer clears packageNames unconditionally; only eventTypes widen.
        cancelServiceScope()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        serviceScope = scope
        scope.launch {
            // null = the store is not read yet (or unreadable) ⇒ UNDECIDED, the filtered footprint.
            eventReceiptPreferences.consent.collect { consent ->
                applyEventReceipt(consent ?: EventReceiptConsent.UNDECIDED)
            }
        }

        // Register with the source
        accessibilitySource.registerService(this)

        // #938: this is the moment recognition actually goes live on this device, so it is where
        // the English-only assumption of the anchors + marker scrubs is worth reporting. Fail-OPEN
        // — a diagnostic must never be able to stop sensing from starting.
        try {
            localeBoundaryReporter.onSensorServiceConnected()
        } catch (t: Throwable) {
            Timber.tag("Pipeline").e(t, "Locale boundary report failed (#938) — sensing unaffected")
        }
    }

    companion object {
        /** MM2 — the Android 11 caveat WARN fires once per process. */
        private val unreliableWarned = AtomicBoolean(false)

        /** Event types that have pipeline handlers — everything else is "unhandled". */
        internal val HANDLED_TYPES = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
        )
    }
}
