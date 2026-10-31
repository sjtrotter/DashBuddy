package cloud.trotter.log.gig.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.gig.domain.action.RuleAction
import cloud.trotter.log.gig.domain.capability.ConsentReceipt
import cloud.trotter.log.gig.domain.capability.RuleCapability
import cloud.trotter.log.gig.domain.capability.RuleCapabilityGrants
import cloud.trotter.log.gig.domain.settings.EventReceiptConsent
import cloud.trotter.log.gig.domain.settings.EventReceiptPreferences
import cloud.trotter.log.gig.domain.state.Platform
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the capability-consent surface (#422 PR 3): the per-source list of the
 * automation taps a loaded ruleset enables, each with its grant state and a
 * grant/revoke control. State flows down as [ConsentUiState] (UDF); the only
 * write is [setGranted], which routes THROUGH [RuleCapabilityGrants] — the same
 * grant store the fail-closed engine gate (#417) reads at fire time. This screen
 * is a consent *record*, never a second enforcement point (SSOT: enforcement
 * stays at the `PerformRuleAction` seam in `SideEffectEngine`).
 *
 * The ViewModel holds no Android resources — human disclosure copy is resolved
 * from the app-owned [RuleAction] vocabulary in the composable (never from
 * rule-supplied text; see `docs/design/rule-capability-consent.md`).
 *
 * #1151 — the screen also records the wide-event-receipt FEATURE consent ("Screen events"). That
 * value is owned by [EventReceiptPreferences] (never the grant store) and enforced by the
 * accessibility listener; the switch here only writes through [setEventReceiptAllowed].
 */
@HiltViewModel
class CapabilityConsentViewModel @Inject constructor(
    private val grants: RuleCapabilityGrants,
    private val eventReceipt: EventReceiptPreferences,
) : ViewModel() {

    val uiState: StateFlow<ConsentUiState> =
        combine(
            combine(grants.capabilities, grants.grantedKeys, grants.deniedKeys, ::Triple),
            eventReceipt.consent,
            grants.receipts,
            eventReceipt.receipt,
        ) { (capabilities, grantedKeys, deniedKeys), consent, receipts, eventRecord ->
            buildConsentUiState(capabilities, grantedKeys, deniedKeys)
                .withEventReceipt(consent, receipts, eventRecord)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            // MM7: seeded from the synchronously readable values — the switch never renders OFF
            // for a frame for an ALLOWED dasher.
            initialValue = buildConsentUiState(
                grants.capabilities.value,
                grants.grantedKeys.value,
                grants.deniedKeys.value,
            ).withEventReceipt(eventReceipt.consent.value, grants.receipts.value, eventReceipt.receipt.value),
        )

    /** Grant or revoke one capability. Revoking is fail-closed (persists a denial). */
    fun setGranted(key: String, granted: Boolean) {
        viewModelScope.launch { grants.setGranted(key, granted) }
    }

    /**
     * #1151 — on ⇒ ALLOWED, off ⇒ a durable DECLINED (an explicit act, never back to UNDECIDED).
     * #1457: the screen calls this only from the disclosure dialog's Allow / Don't allow and from
     * turning the switch off; closing the dialog any other way writes nothing.
     */
    fun setEventReceiptAllowed(allowed: Boolean) {
        viewModelScope.launch {
            // NN3: a failed write is logged by the repository; the switch reads back the old value.
            eventReceipt.set(EventReceiptConsent.of(allowed))
        }
    }
}

/** Immutable per-screen state (UDF). Empty [sources] ⇒ the empty-state copy. */
data class ConsentUiState(
    val sources: List<ConsentSourceGroup> = emptyList(),
    /** #1151 — the "Screen events" switch: true only when the consent is ALLOWED. */
    val eventReceiptAllowed: Boolean = false,
    /** #1457 — the Screen events status line, from the decision itself (never from a receipt). */
    val eventReceiptStatus: ConsentStatus = ConsentStatus.UNDECIDED,
    val receipts: Map<String, ConsentReceipt> = emptyMap(),
    val eventReceipt: ConsentReceipt? = null,
)

/**
 * #1457 — what a status line says: the decision the store holds. A receipt, when present, only adds
 * its date, app version and privacy notice version after it.
 */
enum class ConsentStatus { ALLOWED, NOT_ALLOWED, UNDECIDED }

/** The Screen events decision as a status; an unread consent shows as not decided. */
fun EventReceiptConsent?.toConsentStatus(): ConsentStatus = when (this) {
    EventReceiptConsent.ALLOWED -> ConsentStatus.ALLOWED
    EventReceiptConsent.DECLINED -> ConsentStatus.NOT_ALLOWED
    EventReceiptConsent.UNDECIDED, null -> ConsentStatus.UNDECIDED
}

private fun ConsentUiState.withEventReceipt(
    consent: EventReceiptConsent?,
    receipts: Map<String, ConsentReceipt>,
    eventRecord: ConsentReceipt?,
): ConsentUiState = copy(
    eventReceiptAllowed = consent == EventReceiptConsent.ALLOWED,
    eventReceiptStatus = consent.toConsentStatus(),
    receipts = receipts,
    eventReceipt = eventRecord,
)

/** One ruleset source (a bundled asset file, or a future downloaded source). */
data class ConsentSourceGroup(
    val source: String,
    /** The platform whose rules this source carries — display only. */
    val platform: Platform,
    /**
     * True for a bundled (asset) source — shipped inside the APK. Since #843
     * killed auto-grant this is display-only provenance: bundled and downloaded
     * sources are BOTH off until the user turns each on (here or via the consent
     * prompt). Only a downloaded source is labelled ("…, downloaded").
     */
    val isBundled: Boolean,
    val capabilities: List<ConsentCapabilityRow>,
)

/** One (rule, action) capability the user may grant or revoke. */
data class ConsentCapabilityRow(
    /** The grant key the store keys on — the write target for [CapabilityConsentViewModel.setGranted]. */
    val key: String,
    /** The app-owned action; the composable maps it to disclosure copy. */
    val action: RuleAction,
    val granted: Boolean,
    /** #1457 — the decision the store holds for [key]: granted, denied, or neither. */
    val status: ConsentStatus,
) {
    /**
     * #1457 — false for an action with no automatic trigger (Accept, Decline): the row is listed
     * only because it holds an earlier decision, and its switch can only turn off.
     */
    val active: Boolean get() = action.hasAutomaticTrigger
}

/**
 * Pure UiState assembly (testable without Android): group the enumerated
 * capabilities by source, resolve each source's platform + bundled flag, and
 * join each capability's decision from [grantedKeys] and [deniedKeys]. Deterministic ordering:
 * bundled sources first, then by source string; within a source by action then
 * rule id, so the list never reshuffles between recompositions.
 *
 * #1457: an action with no automatic trigger is listed only while its key holds a decision, so a
 * dasher can still turn an earlier Allow off; an undecided one is left out, as in the prompt.
 */
fun buildConsentUiState(
    capabilities: List<RuleCapability>,
    grantedKeys: Set<String>,
    deniedKeys: Set<String>,
): ConsentUiState {
    val groups = capabilities
        .filter { it.action.hasAutomaticTrigger || it.key in grantedKeys || it.key in deniedKeys }
        .groupBy { it.source }
        .map { (source, caps) ->
            val isBundled = source.startsWith(RuleCapabilityGrants.ASSET_SOURCE_PREFIX)
            ConsentSourceGroup(
                source = source,
                platform = Platform.fromRuleId(caps.firstOrNull()?.ruleId),
                isBundled = isBundled,
                capabilities = caps
                    .sortedWith(compareBy({ it.action.ordinal }, { it.ruleId }))
                    .map { cap ->
                        ConsentCapabilityRow(
                            key = cap.key,
                            action = cap.action,
                            granted = cap.key in grantedKeys,
                            status = when (cap.key) {
                                in grantedKeys -> ConsentStatus.ALLOWED
                                in deniedKeys -> ConsentStatus.NOT_ALLOWED
                                else -> ConsentStatus.UNDECIDED
                            },
                        )
                    },
            )
        }
        .sortedWith(compareByDescending<ConsentSourceGroup> { it.isBundled }.thenBy { it.source })
    return ConsentUiState(sources = groups)
}
