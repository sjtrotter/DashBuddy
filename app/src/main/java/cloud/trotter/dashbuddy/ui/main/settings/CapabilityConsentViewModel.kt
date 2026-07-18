package cloud.trotter.dashbuddy.ui.main.settings

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
 */
@HiltViewModel
class CapabilityConsentViewModel @Inject constructor(
    private val grants: RuleCapabilityGrants,
) : ViewModel() {

    val uiState: StateFlow<ConsentUiState> =
        combine(grants.capabilities, grants.grantedKeys) { capabilities, grantedKeys ->
            buildConsentUiState(capabilities, grantedKeys)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ConsentUiState(),
        )

    /** Grant or revoke one capability. Revoking is fail-closed (persists a denial). */
    fun setGranted(key: String, granted: Boolean) {
        viewModelScope.launch { grants.setGranted(key, granted) }
    }
}

/** Immutable per-screen state (UDF). Empty [sources] ⇒ the empty-state copy. */
data class ConsentUiState(
    val sources: List<ConsentSourceGroup> = emptyList(),
)

/** One ruleset source (a bundled asset file, or a future downloaded source). */
data class ConsentSourceGroup(
    val source: String,
    /** The platform whose rules this source carries — display only. */
    val platform: Platform,
    /**
     * True for a bundled (asset) source — auto-granted by default (#417). False
     * for a downloaded (CDN/fork) source, which is never auto-granted: its
     * capabilities render pending until the user turns each on here.
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
)

/**
 * Pure UiState assembly (testable without Android): group the enumerated
 * capabilities by source, resolve each source's platform + bundled flag, and
 * join each capability's grant state from [grantedKeys]. Deterministic ordering:
 * bundled sources first, then by source string; within a source by action then
 * rule id, so the list never reshuffles between recompositions.
 */
fun buildConsentUiState(
    capabilities: List<RuleCapability>,
    grantedKeys: Set<String>,
): ConsentUiState {
    val groups = capabilities
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
                        )
                    },
            )
        }
        .sortedWith(compareByDescending<ConsentSourceGroup> { it.isBundled }.thenBy { it.source })
    return ConsentUiState(sources = groups)
}
