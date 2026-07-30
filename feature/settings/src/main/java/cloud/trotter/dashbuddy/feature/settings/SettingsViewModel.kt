package cloud.trotter.dashbuddy.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.strategy.StrategyRepository
import cloud.trotter.dashbuddy.domain.config.OfferAutomationConfig
import cloud.trotter.dashbuddy.domain.evaluation.EvaluationConfig
import cloud.trotter.dashbuddy.domain.evaluation.ScoringRule
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

    // The Strategy Lab's offer simulator is a PURE function of (pay, miles, config) and no longer
    // lives here (#944): `OfferSimulation.simulate` in :domain. It was never an action — having
    // the Lab screen call a ViewModel function from composition to derive display state inverted
    // UDF, and the evaluator it wrapped is stateless.
}