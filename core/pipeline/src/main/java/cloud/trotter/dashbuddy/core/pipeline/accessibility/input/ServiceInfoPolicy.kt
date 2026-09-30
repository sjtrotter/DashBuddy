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
 *
 * **An empty [watched] set is refused (LL9)** — for EVERY consent, so a broken registry fails on the
 * very first apply, before anything was ever widened. The framework treats an EMPTY `packageNames`
 * exactly like `null` (every package), so an empty list would be fail-OPEN; the registry is a
 * compile-time constant, so a refusal on the first apply is a refusal on every apply and the
 * manifest's list stays in force.
 */
object ServiceInfoPolicy {

    /** @throws IllegalArgumentException when [watched] is empty (see the class doc). */
    fun packageNamesFor(consent: EventReceiptConsent, watched: Set<String>): Array<String>? {
        require(watched.isNotEmpty()) {
            "empty watched-package registry: an empty packageNames would subscribe to every package"
        }
        return when (consent) {
            EventReceiptConsent.ALLOWED -> null
            EventReceiptConsent.UNDECIDED,
            EventReceiptConsent.DECLINED,
            -> watched.sorted().toTypedArray()
        }
    }

    /** True when [packageNamesFor] widens to every package — the one fact the INFO line reports. */
    fun isWide(consent: EventReceiptConsent): Boolean = consent == EventReceiptConsent.ALLOWED
}
