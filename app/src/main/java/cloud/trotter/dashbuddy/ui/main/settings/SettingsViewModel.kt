package cloud.trotter.dashbuddy.ui.main.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.strategy.StrategyRepository
import cloud.trotter.dashbuddy.domain.config.OfferAutomationConfig
import cloud.trotter.dashbuddy.domain.evaluation.EvaluationConfig
import cloud.trotter.dashbuddy.domain.evaluation.ScoringRule
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluator
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val strategyRepository: StrategyRepository,
    private val offerEvaluator: OfferEvaluator,
) : ViewModel() {

    // --- STATE ---
    // The repo materializes the config (#436); the UI just narrows null
    // (pre-first-load) to a renderable default.
    val evaluationConfig: StateFlow<EvaluationConfig> = strategyRepository.evaluationConfig
        .filterNotNull()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), EvaluationConfig())

    // #577: the offer-automation flags (only quick-declines is live today).
    val automationConfig: StateFlow<OfferAutomationConfig> = strategyRepository.automationConfig
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OfferAutomationConfig())

    // --- ACTIONS ---

    fun toggleProtectStats(enabled: Boolean) = viewModelScope.launch {
        strategyRepository.setProtectStatsMode(enabled)
    }

    /** #577 quick-declines / single-click declines — auto-confirm DoorDash's 2nd decline button. */
    fun toggleQuickDeclines(enabled: Boolean) = viewModelScope.launch {
        strategyRepository.setQuickDeclines(enabled)
    }

    fun toggleAllowShopping(allowed: Boolean) = viewModelScope.launch {
        strategyRepository.setAllowShopping(allowed)
    }

    /**
     * Called when dragging rows to reorder priorities
     */
    fun reorderRules(newList: List<ScoringRule>) = viewModelScope.launch {
        strategyRepository.updateRules(newList)
    }

    /**
     * Called when changing a slider on a specific rule
     */
    fun updateRule(updatedRule: ScoringRule) = viewModelScope.launch {
        val currentList = evaluationConfig.value.rules.toMutableList()
        val index = currentList.indexOfFirst { it.id == updatedRule.id }

        if (index != -1) {
            currentList[index] = updatedRule
            strategyRepository.updateRules(currentList)
        }
    }

    // --- SIMULATOR ---

    fun simulateOffer(pay: Double, miles: Double): OfferEvaluation {
        val fakeOffer = ParsedOffer(
            // Stable hash (#367): the screen memoizes on (pay, dist, config) —
            // a wall-clock hash would defeat structural equality.
            offerHash = "simulated-offer",
            payAmount = pay,
            distanceMiles = miles,
            itemCount = 5, // Default average workload
            orders = emptyList() // non-shop by construction (no SHOP order) — never exercises shop pace
        )

        // #588: intentionally NOT calling evaluationConfig.value.forPlatform(...) — this simulator has
        // no platform to resolve against. Safe only because fakeOffer is never a shop offer (see
        // EvaluationConfig.userEconomy KDoc); a shop-capable simulator would need to pick a platform
        // and call forPlatform() first, or it would silently price off seed-only shop pace.
        val currentConfig = evaluationConfig.value
        return offerEvaluator.evaluate(fakeOffer, currentConfig)
    }
}