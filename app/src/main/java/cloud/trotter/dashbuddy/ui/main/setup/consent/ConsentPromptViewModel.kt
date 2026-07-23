package cloud.trotter.dashbuddy.ui.main.setup.consent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.domain.action.RuleAction
import cloud.trotter.dashbuddy.domain.capability.RuleCapability
import cloud.trotter.dashbuddy.domain.capability.RuleCapabilityGrants
import cloud.trotter.dashbuddy.domain.state.Platform
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the prompted per-capability consent sheet (#843): the app's front-door
 * acquisition surface for automation consent, joining the a11y/notification
 * permission chain rather than hiding in a settings menu. It lists ONLY the
 * *undecided* capabilities — enumerated by a loaded ruleset but neither granted
 * nor denied — one row each, so the user opts into EACH automation individually
 * (Google Play policy). State flows down as [ConsentPromptUiState] (UDF); the
 * only write is [onDecision], routing THROUGH [RuleCapabilityGrants] — the same
 * grant store the fail-closed engine gate (#417) reads at fire time. This is an
 * acquisition surface, never a second enforcement point.
 */
@HiltViewModel
class ConsentPromptViewModel @Inject constructor(
    private val grants: RuleCapabilityGrants,
) : ViewModel() {

    val uiState: StateFlow<ConsentPromptUiState> =
        combine(
            grants.capabilities,
            grants.grantedKeys,
            grants.deniedKeys,
        ) { capabilities, granted, denied ->
            buildConsentPromptState(capabilities, granted, denied)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ConsentPromptUiState(),
        )

    /**
     * Record the user's per-row decision: Allow ⇒ grant, Don't allow ⇒ a durable
     * denial. Either answer removes the row from [uiState] reactively (both leave
     * the undecided set). "Not now" is NOT a decision — it defers the whole sheet
     * (composable-local), leaving the capability undecided to re-prompt next
     * foreground.
     */
    fun onDecision(key: String, allow: Boolean) {
        viewModelScope.launch { grants.setGranted(key, allow) }
    }
}

/** Immutable per-screen state (UDF). Empty [rows] ⇒ nothing to ask, sheet stays closed. */
data class ConsentPromptUiState(
    val rows: List<ConsentPromptRow> = emptyList(),
)

/** One undecided capability the user must Allow or Don't-allow. */
data class ConsentPromptRow(
    /** The grant key — the write target for [ConsentPromptViewModel.onDecision]. */
    val key: String,
    /** The app-owned action; the composable maps it to disclosure copy. */
    val action: RuleAction,
    /** The platform whose rules enable this — display only (copy + source label). */
    val platform: Platform,
    /** True for a bundled (asset) source — selects the "Built-in" source label. */
    val isBundled: Boolean,
)

/**
 * The prompt-trigger projection (pure, testable without Android): the undecided
 * set is `capabilities − granted − denied`. A capability is undecided when its
 * content-pinned [RuleCapability.key] is in neither the granted nor the denied
 * set. One row per undecided key ([distinctBy] guards a rare duplicate key
 * across sources), deterministically ordered (action then rule id) so the sheet
 * never reshuffles between recompositions. Empty rows ⇒ the trigger predicate is
 * false and the sheet stays closed.
 */
fun buildConsentPromptState(
    capabilities: List<RuleCapability>,
    grantedKeys: Set<String>,
    deniedKeys: Set<String>,
): ConsentPromptUiState {
    val rows = capabilities
        .filter { it.key !in grantedKeys && it.key !in deniedKeys }
        .distinctBy { it.key }
        .sortedWith(compareBy({ it.action.ordinal }, { it.ruleId }))
        .map { cap ->
            ConsentPromptRow(
                key = cap.key,
                action = cap.action,
                platform = Platform.fromRuleId(cap.ruleId),
                isBundled = cap.source.startsWith(RuleCapabilityGrants.ASSET_SOURCE_PREFIX),
            )
        }
    return ConsentPromptUiState(rows = rows)
}
