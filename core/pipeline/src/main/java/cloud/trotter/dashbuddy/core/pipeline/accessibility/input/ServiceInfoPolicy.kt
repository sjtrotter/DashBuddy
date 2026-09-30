package cloud.trotter.dashbuddy.core.pipeline.accessibility.input

import cloud.trotter.dashbuddy.domain.settings.EventReceiptConsent

/**
 * #1151 — which packages the accessibility service subscribes to, as a pure function of the
 * dasher's [EventReceiptConsent]. The listener applies the result to `serviceInfo.packageNames`.
 *
 * - [EventReceiptConsent.ALLOWED] ⇒ `null` (every package). This is the ONLY way topology events
 *   (`TYPE_WINDOWS_CHANGED`, which arrive with a null package) reach [ListenerGate]: the framework
 *   filters by package BEFORE `onAccessibilityEvent`.
 * - anything else ⇒ exactly the [watched] registry (`Platform.watchedPackages`, the list the
 *   manifest XML carries as the cold-start default). Fail-closed: UNDECIDED behaves as DECLINED.
 *
 * Build type plays no part: since #1151 no build widens `packageNames` unconditionally.
 */
object ServiceInfoPolicy {

    fun packageNamesFor(consent: EventReceiptConsent, watched: Set<String>): Array<String>? =
        when (consent) {
            EventReceiptConsent.ALLOWED -> null
            EventReceiptConsent.UNDECIDED,
            EventReceiptConsent.DECLINED,
            -> watched.sorted().toTypedArray()
        }

    /** True when [packageNamesFor] widens to every package — the one fact the INFO line reports. */
    fun isWide(consent: EventReceiptConsent): Boolean = consent == EventReceiptConsent.ALLOWED
}
