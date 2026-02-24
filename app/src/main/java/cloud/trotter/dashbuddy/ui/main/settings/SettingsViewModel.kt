package cloud.trotter.dashbuddy.ui.main.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.data.offer.ParsedOffer
import cloud.trotter.dashbuddy.data.settings.SettingsRepository
import cloud.trotter.dashbuddy.model.config.EvaluationConfig
import cloud.trotter.dashbuddy.model.config.ScoringRule
import cloud.trotter.dashbuddy.state.logic.OfferEvaluatorV2
import cloud.trotter.dashbuddy.state.model.OfferEvaluation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository
) : ViewModel() {

    // --- STATE ---
    // Combine multiple data sources into one Config object for the UI
    val evaluationConfig: StateFlow<EvaluationConfig> = combine(
        repository.scoringRules,
        repository.protectStatsMode,
        repository.allowShopping
    ) { rules, protect, shop ->
        EvaluationConfig(
            protectStatsMode = protect,
            rules = rules,
            allowShopping = shop
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), EvaluationConfig())

    // --- ACTIONS ---

    fun toggleProtectStats(enabled: Boolean) = viewModelScope.launch {
        repository.setProtectStatsMode(enabled)
    }

    fun toggleAllowShopping(allowed: Boolean) = viewModelScope.launch {
        repository.setAllowShopping(allowed)
    }

    /**
     * Called when dragging rows to reorder priorities
     */
    fun reorderRules(newList: List<ScoringRule>) = viewModelScope.launch {
        repository.updateRules(newList)
    }

    /**
     * Called when changing a slider on a specific rule
     */
    fun updateRule(updatedRule: ScoringRule) = viewModelScope.launch {
        val currentList = evaluationConfig.value.rules.toMutableList()
        val index = currentList.indexOfFirst { it.id == updatedRule.id }

        if (index != -1) {
            currentList[index] = updatedRule
            repository.updateRules(currentList)
        }
    }

    // --- SIMULATOR ---

    fun simulateOffer(pay: Double, miles: Double): OfferEvaluation {
        val fakeOffer = ParsedOffer(
            offerHash = "sim_${System.currentTimeMillis()}",
            payAmount = pay,
            distanceMiles = miles,
            itemCount = 5, // Default average workload
            orders = emptyList()
        )

        val currentConfig = evaluationConfig.value
        val evaluator = OfferEvaluatorV2(currentConfig)
        return evaluator.evaluate(fakeOffer)
    }
}