package cloud.trotter.dashbuddy.ui.main.setup.consent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.BuildConfig
import cloud.trotter.dashbuddy.domain.settings.EventReceiptConsent
import cloud.trotter.dashbuddy.domain.settings.EventReceiptPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
        combine(preferences.consent, preferences.loaded) { consent, loaded ->
            buildEventReceiptConsentState(consent, loaded, isDebugBuild)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = EventReceiptConsentUiState(),
        )

    /**
     * Allow ⇒ [EventReceiptConsent.ALLOWED], Don't allow ⇒ a durable [EventReceiptConsent.DECLINED].
     * "Not now" is NOT a decision — the composable defers locally and the value stays UNDECIDED.
     */
    fun onDecision(allow: Boolean) {
        viewModelScope.launch {
            preferences.set(if (allow) EventReceiptConsent.ALLOWED else EventReceiptConsent.DECLINED)
        }
    }
}

/** Immutable per-screen state (UDF). The defaults show nothing and block nothing. */
data class EventReceiptConsentUiState(
    /** Ask the dasher: the store has been read and nothing is decided yet. */
    val showPrompt: Boolean = false,
    /** Debug build AND declined: the Dashboard is replaced by the blocking notice. */
    val blocked: Boolean = false,
)

/**
 * The pure projection (testable without Android). The prompt waits for [loaded] so a decided dasher
 * never sees the sheet flash during the first store read; the block is reachable ONLY when
 * [isDebugBuild] AND the dasher explicitly DECLINED — a release decline keeps the app fully usable.
 */
fun buildEventReceiptConsentState(
    consent: EventReceiptConsent,
    loaded: Boolean,
    isDebugBuild: Boolean,
): EventReceiptConsentUiState = EventReceiptConsentUiState(
    showPrompt = loaded && consent == EventReceiptConsent.UNDECIDED,
    blocked = isDebugBuild && consent == EventReceiptConsent.DECLINED,
)
