package cloud.trotter.log.gig.domain.settings

import cloud.trotter.log.gig.domain.capability.ConsentReceipt
import kotlinx.coroutines.flow.StateFlow

/**
 * The dasher's decision about WIDE accessibility event receipt (#1151).
 *
 * The service's cold-start footprint (`accessibility_service_config.xml`) filters events to the
 * watched delivery-app packages. Android drops a `TYPE_WINDOWS_CHANGED` event (null package) before
 * the service sees it, so the #1148 topology path only works when the listener clears
 * `packageNames` at runtime. That widens the service's event footprint to every app, so it is a
 * user decision — a FEATURE consent, deliberately distinct from the per-action capability grants
 * (#417/#843): one value, one owner, opt-in, and a durable decline.
 */
enum class EventReceiptConsent {
    /** Never asked or deferred ("Not now"). Behaves exactly like [DECLINED] (filtered). */
    UNDECIDED,

    /** The dasher allowed wide receipt — the listener clears `packageNames`. */
    ALLOWED,

    /** The dasher declined — durable; filtered receipt, the topology path stays off. */
    DECLINED,
    ;

    companion object {
        /**
         * The ONE on/off → decision rule (a switch or an Allow / Don't allow pair): on ⇒ [ALLOWED],
         * off ⇒ a durable [DECLINED]. An explicit act never maps back to [UNDECIDED].
         */
        fun of(allowed: Boolean): EventReceiptConsent = if (allowed) ALLOWED else DECLINED

        /**
         * #1414 — the ONE rule for what a build enforces. [offered] is whether this build offers Screen
         * events at all (the Play build does not: Play allows the wide receipt only for a declared
         * accessibility tool). Not offered ⇒ [DECLINED] for every [stored] value, an unread `null` and
         * an earlier [ALLOWED] included, so the listener keeps the package filter and the permission
         * flow never asks. Offered ⇒ [stored] unchanged (`null` stays `null`). Read-side only: the
         * stored decision is never rewritten, [EventReceiptPreferences] stays its one owner.
         *
         * A build that does not offer the option is never a debug build (the build table in
         * `app/build.gradle.kts`, pinned by `AccessibilityToolFlagGuardTest`), so the [DECLINED] here
         * never reaches the debug build's decline handling.
         */
        fun enforced(stored: EventReceiptConsent?, offered: Boolean): EventReceiptConsent? =
            if (offered) stored else DECLINED

        /** Android 11 (API 30) — see [isWideReceiptReliable]. */
        const val UNRELIABLE_WIDE_RECEIPT_SDK = 30

        /**
         * #1151 review MM2 — the ONE rule for whether clearing `packageNames` at runtime is known to
         * take effect on [sdkInt]. On Android 11 the framework may treat the dynamic package filter as
         * additive, so an Allow can leave the manifest filter in force; the app cannot verify that
         * from its side, so it says so (a WARN from the listener, a caveat on the Settings switch).
         */
        fun isWideReceiptReliable(sdkInt: Int): Boolean = sdkInt != UNRELIABLE_WIDE_RECEIPT_SDK
    }
}

/**
 * Read/write contract for [EventReceiptConsent] (#1151) — the `PlatformPreferences` pattern: the
 * `:domain` interface lets `:core:pipeline` apply the value without depending on `:core:data`,
 * where the DataStore-backed implementation lives. This is the ONE owner of the value; the
 * listener, the prompt and the Settings switch all read [consent] and write through [set].
 */
interface EventReceiptPreferences {

    /**
     * The persisted decision, materialized once (#356). `null` means ONLY that the store has not
     * been read yet (an unreadable store settles on UNDECIDED, review NN2) — enforcement treats it as [EventReceiptConsent.UNDECIDED] (the
     * filtered, fail-closed footprint), while the prompt waits for a non-null value so a decided
     * dasher never sees it flash. ONE flow, so the two can never disagree for a frame.
     */
    val consent: StateFlow<EventReceiptConsent?>

    /** Latest decision's on-device receipt, absent until a decision is recorded (#170). */
    val receipt: StateFlow<ConsentReceipt?>

    /**
     * Persist a decision. Never throws a storage failure: returns `false` (logged by the
     * implementation) and leaves [consent] unchanged.
     */
    suspend fun set(consent: EventReceiptConsent): Boolean
}
