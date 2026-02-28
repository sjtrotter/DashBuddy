package cloud.trotter.dashbuddy.ui.main.setup.wizard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.data.gas.GasPriceRepository
import cloud.trotter.dashbuddy.data.settings.SettingsRepository
import cloud.trotter.dashbuddy.data.vehicle.VehicleRepository
import cloud.trotter.dashbuddy.data.vehicle.api.dto.MenuItem
import cloud.trotter.dashbuddy.model.config.MetricType
import cloud.trotter.dashbuddy.model.config.ScoringRule
import cloud.trotter.dashbuddy.model.vehicle.FuelType
import cloud.trotter.dashbuddy.ui.main.setup.wizard.model.DashStrategy
import cloud.trotter.dashbuddy.ui.main.setup.wizard.model.VehicleType
import cloud.trotter.dashbuddy.ui.main.setup.wizard.model.WizardState
import cloud.trotter.dashbuddy.ui.main.setup.wizard.model.WizardStep
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class WizardViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val vehicleRepository: VehicleRepository,
    private val gasPriceRepository: GasPriceRepository
) : ViewModel() {

    private val _steps = MutableStateFlow(WizardStep.entries)
    val steps = _steps.asStateFlow()

    private val _state = MutableStateFlow(WizardState())
    val state = _state.asStateFlow()

    private val _availableYears = MutableStateFlow<List<String>>(emptyList())
    val availableYears = _availableYears.asStateFlow()

    private val _availableMakes = MutableStateFlow<List<String>>(emptyList())
    val availableMakes = _availableMakes.asStateFlow()

    private val _availableModels = MutableStateFlow<List<String>>(emptyList())
    val availableModels = _availableModels.asStateFlow()

    private val _availableTrims = MutableStateFlow<List<MenuItem>>(emptyList())
    val availableTrimNames = MutableStateFlow<List<String>>(emptyList())

    init {
        Timber.v("Initializing WizardViewModel")
        loadExistingSettings()
        fetchVehicleYears()
        attemptAutoGasPriceFetch()
    }

    private fun loadExistingSettings() {
        viewModelScope.launch {
            val currentYear = settingsRepository.vehicleYear.first()
            val currentMake = settingsRepository.vehicleMake.first()
            val currentModel = settingsRepository.vehicleModel.first()
            val currentTrim = settingsRepository.vehicleTrim.first()
            val currentMpg = settingsRepository.estimatedMpg.first()
            val currentFuelType = settingsRepository.fuelType.first()
            val currentGasAuto = settingsRepository.isGasPriceAuto.first()
            val currentGasPrice = settingsRepository.gasPrice.first()
            val currentProtectMode = settingsRepository.protectStatsMode.first()
            val currentStrategy =
                if (currentProtectMode) DashStrategy.PROTECT_PLATINUM else DashStrategy.MANUAL

            val rules = settingsRepository.scoringRules.first()
            val minPayoutRule = rules.filterIsInstance<ScoringRule.MetricRule>()
                .find { it.metricType == MetricType.PAYOUT }
            val targetHourlyRule = rules.filterIsInstance<ScoringRule.MetricRule>()
                .find { it.metricType == MetricType.ACTIVE_HOURLY }
            val maxDistanceRule = rules.filterIsInstance<ScoringRule.MetricRule>()
                .find { it.metricType == MetricType.MAX_DISTANCE }
            val maxItemsRule = rules.filterIsInstance<ScoringRule.MetricRule>()
                .find { it.metricType == MetricType.ITEM_COUNT }

            val vehicleType = if (currentYear == "E-Bike") VehicleType.E_BIKE else VehicleType.CAR

            _state.update { currentState ->
                currentState.copy(
                    vehicleType = vehicleType,
                    vehicleYear = currentYear,
                    vehicleMake = currentMake,
                    vehicleModel = currentModel,
                    vehicleTrim = currentTrim,
                    estimatedMpg = currentMpg,
                    fuelType = currentFuelType,
                    isGasPriceAuto = currentGasAuto,
                    gasPrice = currentGasPrice,
                    strategy = currentStrategy,

                    enforceMinPayout = minPayoutRule?.autoDeclineOnFail ?: false,
                    minPayout = minPayoutRule?.targetValue ?: 5.0f,

                    enforceTargetHourly = targetHourlyRule?.autoDeclineOnFail ?: false,
                    targetHourly = targetHourlyRule?.targetValue ?: 20.0f,

                    enforceMaxDistance = maxDistanceRule?.autoDeclineOnFail ?: false,
                    maxDistance = maxDistanceRule?.targetValue ?: 10.0f,

                    maxItems = maxItemsRule?.targetValue ?: 15.0f
                )
            }
        }
    }

    private fun fetchVehicleYears() {
        viewModelScope.launch { _availableYears.value = vehicleRepository.getYears() }
    }

    fun updateVehicleType(type: VehicleType) {
        _state.update { it.copy(vehicleType = type) }
    }

    fun onYearSelected(year: String) {
        _state.update {
            it.copy(
                vehicleYear = year,
                vehicleMake = "",
                vehicleModel = "",
                vehicleTrim = ""
            )
        }
        _availableMakes.value = emptyList(); _availableModels.value =
            emptyList(); _availableTrims.value = emptyList(); availableTrimNames.value = emptyList()
        viewModelScope.launch { _availableMakes.value = vehicleRepository.getMakes(year) }
    }

    fun onMakeSelected(make: String) {
        _state.update { it.copy(vehicleMake = make, vehicleModel = "", vehicleTrim = "") }
        _availableModels.value = emptyList(); _availableTrims.value =
            emptyList(); availableTrimNames.value = emptyList()

        // Skip API call if Not Listed
        if (make != "Not Listed") {
            viewModelScope.launch {
                _availableModels.value = vehicleRepository.getModels(_state.value.vehicleYear, make)
            }
        }
    }

    fun onModelSelected(model: String) {
        _state.update { it.copy(vehicleModel = model, vehicleTrim = "") }
        _availableTrims.value = emptyList(); availableTrimNames.value = emptyList()

        // Skip API call if Not Listed
        if (model != "Not Listed") {
            viewModelScope.launch {
                val trims = vehicleRepository.getVehicleOptions(
                    _state.value.vehicleYear,
                    _state.value.vehicleMake,
                    model
                )
                _availableTrims.value = trims; availableTrimNames.value = trims.map { it.text }
            }
        }
    }

    fun onTrimSelected(trimName: String) {
        _state.update { it.copy(vehicleTrim = trimName) }

        // Escape hatch! Don't look up MPG if Not Listed.
        if (trimName == "Not Listed") return

        viewModelScope.launch {
            val vehicleId =
                _availableTrims.value.find { it.text == trimName }?.value ?: return@launch
            val details = vehicleRepository.getVehicleDetails(vehicleId)

            if (details != null) {
                val mappedFuelType = mapEpaFuelType(details.fuelType1)
                _state.update { currentState ->
                    val cachedPrice = currentState.fetchedGasPrices[mappedFuelType]
                    val newPrice =
                        if (currentState.isGasPriceAuto && cachedPrice != null) cachedPrice else currentState.gasPrice

                    currentState.copy(
                        estimatedMpg = details.combinedMpg ?: currentState.estimatedMpg,
                        fuelType = mappedFuelType,
                        gasPrice = newPrice
                    )
                }
            }
        }
    }

    // --- NEW: Manual MPG Slider update ---
    fun updateEstimatedMpg(mpg: Float) {
        _state.update { it.copy(estimatedMpg = mpg) }
    }

    private fun mapEpaFuelType(epaString: String?): FuelType {
        if (epaString == null) return FuelType.REGULAR
        val lower = epaString.lowercase(Locale.getDefault())
        return when {
            lower.contains("electricity") -> FuelType.ELECTRICITY // <-- NEW
            lower.contains("premium") -> FuelType.PREMIUM
            lower.contains("midgrade") -> FuelType.MIDGRADE
            lower.contains("diesel") -> FuelType.DIESEL
            else -> FuelType.REGULAR
        }
    }

    fun updateFuelType(type: FuelType) {
        _state.update { currentState ->
            val cachedPrice = currentState.fetchedGasPrices[type]

            // If we have the price, swap instantly!
            if (currentState.isGasPriceAuto && cachedPrice != null) {
                currentState.copy(fuelType = type, gasPrice = cachedPrice)
            } else {
                // If we DON'T have the price, just update the fuel type for now.
                currentState.copy(fuelType = type)
            }
        }

        // If auto is ON, but we didn't have the price cached, fetch it right now!
        if (_state.value.isGasPriceAuto && _state.value.fetchedGasPrices[type] == null) {
            viewModelScope.launch {
                _state.update { it.copy(isFetchingGasPrice = true) }
                val result = gasPriceRepository.fetchGasPriceOnly(type)

                if (result.isSuccess) {
                    val newPrice = result.getOrNull()!!
                    _state.update { currentState ->
                        // Update the map AND the current price
                        val newMap = currentState.fetchedGasPrices.toMutableMap()
                        newMap[type] = newPrice

                        currentState.copy(
                            fetchedGasPrices = newMap,
                            gasPrice = newPrice,
                            isFetchingGasPrice = false
                        )
                    }
                } else {
                    _state.update { it.copy(isFetchingGasPrice = false) }
                }
            }
        }
    }

    fun toggleAutoGasPrice(isAuto: Boolean) {
        _state.update { it.copy(isGasPriceAuto = isAuto) }
        if (isAuto) attemptAutoGasPriceFetch()
    }

    fun updateGasPrice(price: Float) {
        _state.update { it.copy(gasPrice = price) }
    }

    fun attemptAutoGasPriceFetch() {
        if (!_state.value.isGasPriceAuto) return

        viewModelScope.launch {
            _state.update { it.copy(isFetchingGasPrice = true) }
            val pricesMap = mutableMapOf<FuelType, Float>()

            coroutineScope {
                FuelType.entries.map { fuel ->
                    async {
                        val result = gasPriceRepository.fetchGasPriceOnly(fuel)
                        if (result.isSuccess) {
                            pricesMap[fuel] = result.getOrNull()!!
                        }
                    }
                }.awaitAll()
                Timber.i("Fetched gas prices: $pricesMap")
            }

            _state.update { currentState ->
                currentState.copy(
                    fetchedGasPrices = pricesMap,
                    gasPrice = pricesMap[currentState.fuelType] ?: currentState.gasPrice,
                    isFetchingGasPrice = false
                )
            }
        }
    }

    fun updateStrategy(strategy: DashStrategy) {
        _state.update { it.copy(strategy = strategy) }
    }

    fun toggleEnforcement(step: WizardStep, enforce: Boolean) {
        _state.update {
            when (step) {
                WizardStep.MIN_PAYOUT -> it.copy(enforceMinPayout = enforce)
                WizardStep.TARGET_HOURLY -> it.copy(enforceTargetHourly = enforce)
                WizardStep.MAX_DISTANCE -> it.copy(enforceMaxDistance = enforce)
                else -> it
            }
        }
    }

    fun updateMinPayout(amount: Float) {
        _state.update { it.copy(minPayout = amount) }
    }

    fun updateTargetHourly(amount: Float) {
        _state.update { it.copy(targetHourly = amount) }
    }

    fun updateMaxDistance(miles: Float) {
        _state.update { it.copy(maxDistance = miles) }
    }

    fun updateMaxItems(items: Float) {
        _state.update { it.copy(maxItems = items) }
    }

    fun saveAndFinish(onComplete: () -> Unit) {
        viewModelScope.launch {
            val finalState = _state.value

            settingsRepository.updateFuelType(finalState.fuelType)

            if (finalState.vehicleType == VehicleType.E_BIKE) {
                settingsRepository.updateEconomySettings(
                    "E-Bike",
                    "E-Bike",
                    "E-Bike",
                    "",
                    999f,
                    false,
                    0.0f
                )
            } else {
                settingsRepository.updateEconomySettings(
                    finalState.vehicleYear, finalState.vehicleMake,
                    finalState.vehicleModel, finalState.vehicleTrim,
                    finalState.estimatedMpg, finalState.isGasPriceAuto, finalState.gasPrice
                )
            }

            val isCherryPicker = finalState.strategy == DashStrategy.CHERRY_PICKER
            val isPlatinum = finalState.strategy == DashStrategy.PROTECT_PLATINUM

            settingsRepository.setProtectStatsMode(isPlatinum)
            settingsRepository.setMasterAutomation(isCherryPicker)
            settingsRepository.updateAutomation(
                autoAccept = false,
                minPay = 0.0,
                autoDecline = isCherryPicker
            )
            settingsRepository.setAllowShopping(true)

            val currentRules = settingsRepository.scoringRules.first().toMutableList()
            val updatedRules = currentRules.map { rule ->
                if (rule is ScoringRule.MetricRule) {
                    when (rule.metricType) {
                        MetricType.PAYOUT -> rule.copy(
                            isEnabled = true,
                            targetValue = finalState.minPayout,
                            autoDeclineOnFail = finalState.enforceMinPayout
                        )

                        MetricType.ACTIVE_HOURLY -> rule.copy(
                            isEnabled = true,
                            targetValue = finalState.targetHourly,
                            autoDeclineOnFail = finalState.enforceTargetHourly
                        )

                        MetricType.MAX_DISTANCE -> rule.copy(
                            isEnabled = true,
                            targetValue = finalState.maxDistance,
                            autoDeclineOnFail = finalState.enforceMaxDistance
                        )

                        MetricType.ITEM_COUNT -> rule.copy(
                            isEnabled = true,
                            targetValue = finalState.maxItems,
                            autoDeclineOnFail = false
                        )

                        else -> rule
                    }
                } else rule
            }

            settingsRepository.updateRules(updatedRules)
            settingsRepository.setFirstRunComplete()
            onComplete()
        }
    }
}