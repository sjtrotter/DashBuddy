package cloud.trotter.dashbuddy.domain.settings

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
}

/**
 * Read/write contract for [EventReceiptConsent] (#1151) — the `PlatformPreferences` pattern: the
 * `:domain` interface lets `:core:pipeline` apply the value without depending on `:core:data`,
 * where the DataStore-backed implementation lives. This is the ONE owner of the value; the
 * listener, the prompt and the Settings switch all read [consent] and write through [set].
 */
interface EventReceiptPreferences {

    /**
     * The persisted decision, materialized once (#356). Its value BEFORE the store has been read is
     * [EventReceiptConsent.UNDECIDED] — the filtered, fail-closed footprint.
     */
    val consent: StateFlow<EventReceiptConsent>

    /**
     * True once [consent] reflects the persisted store (false only for the brief window before the
     * first DataStore read). UI surfaces that act on [EventReceiptConsent.UNDECIDED] (the prompt)
     * wait on it so a decided dasher never sees the sheet flash; enforcement does not need it,
     * because the pre-load value is already the filtered footprint.
     */
    val loaded: StateFlow<Boolean>

    /** Persist a decision. */
    suspend fun set(consent: EventReceiptConsent)
}
