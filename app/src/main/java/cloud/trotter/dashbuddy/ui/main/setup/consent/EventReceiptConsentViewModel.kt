package cloud.trotter.dashbuddy.ui.main.setup.consent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.BuildConfig
import cloud.trotter.dashbuddy.domain.settings.EventReceiptConsent
import cloud.trotter.dashbuddy.domain.settings.EventReceiptPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the wide-event-receipt consent prompt and the debug block (#1151). A FEATURE consent, not
 * a capability grant: it reads and writes ONLY [EventReceiptPreferences] (the value's one owner)
 * and never touches `RuleCapabilityGrants`. Enforcement lives in the accessibility listener; this
 * is an acquisition surface, never a second gate.
 */
@HiltViewModel
class EventReceiptConsentViewModel internal constructor(
    private val preferences: EventReceiptPreferences,
    isDebugBuild: Boolean,
) : ViewModel() {

    @Inject
    constructor(preferences: EventReceiptPreferences) : this(preferences, BuildConfig.DEBUG)

    val uiState: StateFlow<EventReceiptConsentUiState> =
        preferences.consent.map { consent ->
            buildEventReceiptConsentState(consent, isDebugBuild)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            // MM7: seeded from the synchronously readable value — a DECLINED debug build never
            // composes a frame of the NavHost before the shell replaces it.
            initialValue = buildEventReceiptConsentState(preferences.consent.value, isDebugBuild),
        )

    /**
     * Allow ⇒ [EventReceiptConsent.ALLOWED], Don't allow ⇒ a durable [EventReceiptConsent.DECLINED].
     * "Not now" is NOT a decision — the composable defers locally and the value stays UNDECIDED.
     */
    fun onDecision(allow: Boolean) {
        viewModelScope.launch {
            // NN3: a failed write is logged by the repository; the prompt simply stays up.
            preferences.set(EventReceiptConsent.of(allow))
        }
    }
}

/** Immutable per-screen state (UDF). The defaults show nothing and block nothing. */
data class EventReceiptConsentUiState(
    /** Ask the dasher: the store has been read and nothing is decided yet. */
    val showPrompt: Boolean = false,
    /** Debug build AND declined: every destination is replaced by the blocking shell. */
    val blocked: Boolean = false,
    /**
     * Debug build AND the store not read yet (review MM3): the shell renders a neutral gate — no
     * NavHost, no deep-link consumption — until the value is known, so a persisted DECLINED can
     * never be raced by a frame of the real app. Always false in release.
     */
    val loading: Boolean = false,
) {
    /** The NavHost (and deep-link delivery) may run. */
    val navigable: Boolean get() = !blocked && !loading
}

/**
 * The pure projection (testable without Android). A `null` [consent] (store not read yet) shows no
 * prompt, so a decided dasher never sees the sheet flash; in a DEBUG build it is [loading] — the
 * shell fails CLOSED until the value is known. The block is reachable ONLY when [isDebugBuild] AND
 * the dasher explicitly DECLINED — a release build is never blocked and never gated.
 */
fun buildEventReceiptConsentState(
    consent: EventReceiptConsent?,
    isDebugBuild: Boolean,
): EventReceiptConsentUiState = EventReceiptConsentUiState(
    showPrompt = consent == EventReceiptConsent.UNDECIDED,
    blocked = isDebugBuild && consent == EventReceiptConsent.DECLINED,
    loading = isDebugBuild && consent == null,
)
