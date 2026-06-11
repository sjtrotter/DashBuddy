package cloud.trotter.dashbuddy.ui.main.setup.wizard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.fuel.FuelPriceRepository
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.state.AppStateRepository
import cloud.trotter.dashbuddy.core.data.strategy.StrategyRepository
import cloud.trotter.dashbuddy.core.data.vehicle.VehicleRepository
import cloud.trotter.dashbuddy.domain.config.DashStrategy
import cloud.trotter.dashbuddy.domain.evaluation.EconomyField
import cloud.trotter.dashbuddy.domain.evaluation.MetricType
import cloud.trotter.dashbuddy.domain.evaluation.ScoringRule
import cloud.trotter.dashbuddy.domain.model.vehicle.FuelType
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleOption
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass
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
import javax.inject.Inject

/** Wizard-layer sentinel for vehicle pickers (#364) — moved out of the data layer. */
const val VEHICLE_NOT_LISTED = "Not Listed"

@HiltViewModel
class WizardViewModel @Inject constructor(
    private val strategyRepository: StrategyRepository,
    private val appPreferencesRepository: AppPreferencesRepository,
    private val appStateRepository: AppStateRepository,
    private val vehicleRepository: VehicleRepository,
    private val gasPriceRepository: FuelPriceRepository
) : ViewModel() {

    val steps = MutableStateFlow(WizardStep.entries).asStateFlow()

    private val _state = MutableStateFlow(WizardState())
    val state = _state.asStateFlow()

    private val _availableYears = MutableStateFlow<List<String>>(emptyList())
    val availableYears = _availableYears.asStateFlow()

    private val _availableMakes = MutableStateFlow<List<String>>(emptyList())
    val availableMakes = _availableMakes.asStateFlow()

    private val _availableModels = MutableStateFlow<List<String>>(emptyList())
    val availableModels = _availableModels.asStateFlow()

    private val _availableTrims = MutableStateFlow<List<VehicleOption>>(emptyList())
    private val _availableTrimNames = MutableStateFlow<List<String>>(emptyList())
    val availableTrimNames = _availableTrimNames.asStateFlow()

    init {
        Timber.v("Initializing WizardViewModel")
        loadExistingSettings()
        fetchVehicleYears()
        attemptAutoGasPriceFetch()
    }

    private fun loadExistingSettings() {
        viewModelScope.launch {
            // Provide safe defaults for the nullable DataStore flows!
            val currentYear = appPreferencesRepository.vehicleYear.first() ?: ""
            val currentMake = appPreferencesRepository.vehicleMake.first() ?: ""
            val currentModel = appPreferencesRepository.vehicleModel.first() ?: ""
            val currentTrim = appPreferencesRepository.vehicleTrim.first() ?: ""
            val currentMpg = appPreferencesRepository.estimatedMpg.first() ?: 0.0f
            val currentFuelType =
                appPreferencesRepository.fuelType.first() // Already non-null in Repo
            val currentGasAuto = appPreferencesRepository.isGasPriceAuto.first()
            val currentGasPrice = appPreferencesRepository.gasPrice.first() ?: 0.0f

            val currentProtectMode = strategyRepository.protectStatsMode.first()
            val currentStrategy =
                if (currentProtectMode) DashStrategy.PROTECT_PLATINUM else DashStrategy.MANUAL

            val rules = strategyRepository.scoringRules.first()
            val minPayoutRule = rules.filterIsInstance<ScoringRule.MetricRule>()
                .find { it.metricType == MetricType.PAYOUT }
            val targetHourlyRule = rules.filterIsInstance<ScoringRule.MetricRule>()
                .find { it.metricType == MetricType.ACTIVE_HOURLY }
            val maxDistanceRule = rules.filterIsInstance<ScoringRule.MetricRule>()
                .find { it.metricType == MetricType.MAX_DISTANCE }
            val maxItemsRule = rules.filterIsInstance<ScoringRule.MetricRule>()
                .find { it.metricType == MetricType.ITEM_COUNT }

            // The currently-stored UserEconomy already merges class-derived defaults
            // with persisted user-set values, so just snapshot it for the wizard.
            val storedEconomy = appPreferencesRepository.userEconomy.first()

            _state.update { currentState ->
                currentState.copy(
                    vehicleClass = storedEconomy.vehicleClass,
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
                    maxItems = maxItemsRule?.targetValue ?: 15.0f,

                    // Personal Economy v2 (#145)
                    tireSetCost = storedEconomy.tireSetCost,
                    tireLifetimeMi = storedEconomy.tireLifetimeMi,
                    oilCost = storedEconomy.oilCost,
                    oilIntervalMi = storedEconomy.oilIntervalMi,
                    brakesCost = storedEconomy.brakesCost,
                    brakesIntervalMi = storedEconomy.brakesIntervalMi,
                    fluidsCost = storedEconomy.fluidsCost,
                    fluidsIntervalMi = storedEconomy.fluidsIntervalMi,
                    miscYearly = storedEconomy.miscYearly,
                    miscYearlyMi = storedEconomy.miscYearlyMi,
                    includeDepreciation = storedEconomy.includeDepreciation,
                    purchasePrice = storedEconomy.purchasePrice,
                    totalLifetimeMi = storedEconomy.totalLifetimeMi,
                    insuranceDeltaPerMonth = storedEconomy.insuranceDeltaPerMonth,
                    registrationDeltaPerYear = storedEconomy.registrationDeltaPerYear,
                    expectedAnnualDashMiles = storedEconomy.expectedAnnualDashMiles,
                    phonePlanTotal = storedEconomy.phonePlanTotal,
                    phonePlanLines = storedEconomy.phonePlanLines,
                    phoneDashPercent = storedEconomy.phoneDashPercent,
                    avgMinutesPerMile = storedEconomy.avgMinutesPerMile,
                    basePickupMinutes = storedEconomy.basePickupMinutes,
                    userSetEconomyFields = storedEconomy.userSetFields,
                )
            }
        }
    }

    private fun fetchVehicleYears() {
        viewModelScope.launch { _availableYears.value = vehicleRepository.getYears() }
    }

    /**
     * Sets the vehicle class. Any [EconomyField] still at its default (i.e. NOT in
     * [WizardState.userSetEconomyFields]) shifts to the new class's preset. User-set
     * values are preserved.
     */
    fun updateVehicleClass(type: VehicleClass) {
        _state.update { s ->
            val unset = EconomyField.entries.toSet() - s.userSetEconomyFields
            s.copy(
                vehicleClass = type,
                tireSetCost = if (EconomyField.TIRE_COST in unset) type.tireSetCost else s.tireSetCost,
                tireLifetimeMi = if (EconomyField.TIRE_LIFETIME in unset) type.tireLifetimeMi else s.tireLifetimeMi,
                oilCost = if (EconomyField.OIL_COST in unset) type.oilCost else s.oilCost,
                oilIntervalMi = if (EconomyField.OIL_INTERVAL in unset) type.oilIntervalMi else s.oilIntervalMi,
                brakesCost = if (EconomyField.BRAKES_COST in unset) type.brakesCost else s.brakesCost,
                brakesIntervalMi = if (EconomyField.BRAKES_INTERVAL in unset) type.brakesIntervalMi else s.brakesIntervalMi,
                fluidsCost = if (EconomyField.FLUIDS_COST in unset) type.fluidsCost else s.fluidsCost,
                fluidsIntervalMi = if (EconomyField.FLUIDS_INTERVAL in unset) type.fluidsIntervalMi else s.fluidsIntervalMi,
                miscYearly = if (EconomyField.MISC_YEARLY in unset) type.miscYearly else s.miscYearly,
                miscYearlyMi = if (EconomyField.MISC_YEARLY_MI in unset) type.miscYearlyMi else s.miscYearlyMi,
                purchasePrice = if (EconomyField.PURCHASE_PRICE in unset) type.purchasePrice else s.purchasePrice,
                totalLifetimeMi = if (EconomyField.TOTAL_LIFETIME_MI in unset) type.totalLifetimeMi else s.totalLifetimeMi,
                userSetEconomyFields = s.userSetEconomyFields + EconomyField.VEHICLE_CLASS,
            )
        }
    }

    // --- Personal Economy v2 setters (#145) ---
    fun updateTires(setCost: Double, lifetimeMi: Double) = _state.update {
        it.copy(
            tireSetCost = setCost, tireLifetimeMi = lifetimeMi,
            userSetEconomyFields = it.userSetEconomyFields +
                setOf(EconomyField.TIRE_COST, EconomyField.TIRE_LIFETIME),
        )
    }

    fun updateOilChange(cost: Double, intervalMi: Double) = _state.update {
        it.copy(
            oilCost = cost, oilIntervalMi = intervalMi,
            userSetEconomyFields = it.userSetEconomyFields +
                setOf(EconomyField.OIL_COST, EconomyField.OIL_INTERVAL),
        )
    }

    fun updateBrakes(cost: Double, intervalMi: Double) = _state.update {
        it.copy(
            brakesCost = cost, brakesIntervalMi = intervalMi,
            userSetEconomyFields = it.userSetEconomyFields +
                setOf(EconomyField.BRAKES_COST, EconomyField.BRAKES_INTERVAL),
        )
    }

    fun updateFluids(cost: Double, intervalMi: Double) = _state.update {
        it.copy(
            fluidsCost = cost, fluidsIntervalMi = intervalMi,
            userSetEconomyFields = it.userSetEconomyFields +
                setOf(EconomyField.FLUIDS_COST, EconomyField.FLUIDS_INTERVAL),
        )
    }

    fun updateMiscRepairs(yearly: Double, yearlyMi: Double) = _state.update {
        it.copy(
            miscYearly = yearly, miscYearlyMi = yearlyMi,
            userSetEconomyFields = it.userSetEconomyFields +
                setOf(EconomyField.MISC_YEARLY, EconomyField.MISC_YEARLY_MI),
        )
    }

    fun updateDepreciation(include: Boolean, price: Double, lifetimeMi: Double) = _state.update {
        it.copy(
            includeDepreciation = include, purchasePrice = price, totalLifetimeMi = lifetimeMi,
            userSetEconomyFields = it.userSetEconomyFields + setOf(
                EconomyField.INCLUDE_DEPRECIATION,
                EconomyField.PURCHASE_PRICE,
                EconomyField.TOTAL_LIFETIME_MI,
            ),
        )
    }

    fun updateInsuranceDelta(perMonth: Double) = _state.update {
        it.copy(
            insuranceDeltaPerMonth = perMonth,
            userSetEconomyFields = it.userSetEconomyFields + EconomyField.INSURANCE_DELTA,
        )
    }

    fun updateRegistrationDelta(perYear: Double) = _state.update {
        it.copy(
            registrationDeltaPerYear = perYear,
            userSetEconomyFields = it.userSetEconomyFields + EconomyField.REGISTRATION_DELTA,
        )
    }

    fun updateExpectedAnnualDashMiles(miles: Double) = _state.update {
        it.copy(
            expectedAnnualDashMiles = miles,
            userSetEconomyFields = it.userSetEconomyFields + EconomyField.EXPECTED_ANNUAL_DASH_MI,
        )
    }

    fun updatePhonePlan(total: Double, lines: Int, dashPercent: Double) = _state.update {
        it.copy(
            phonePlanTotal = total, phonePlanLines = lines, phoneDashPercent = dashPercent,
            userSetEconomyFields = it.userSetEconomyFields + setOf(
                EconomyField.PHONE_PLAN_TOTAL,
                EconomyField.PHONE_PLAN_LINES,
                EconomyField.PHONE_DASH_PERCENT,
            ),
        )
    }

    fun updateTimeConstants(avgMinutesPerMile: Double, basePickupMinutes: Double) = _state.update {
        it.copy(
            avgMinutesPerMile = avgMinutesPerMile, basePickupMinutes = basePickupMinutes,
            userSetEconomyFields = it.userSetEconomyFields + setOf(
                EconomyField.AVG_MIN_PER_MILE,
                EconomyField.BASE_PICKUP_MIN,
            ),
        )
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
            emptyList(); _availableTrims.value = emptyList(); _availableTrimNames.value = emptyList()
        viewModelScope.launch {
            _availableMakes.value =
                listOf(VEHICLE_NOT_LISTED) + vehicleRepository.getMakes(year)
        }
    }

    fun onMakeSelected(make: String) {
        _state.update { it.copy(vehicleMake = make, vehicleModel = "", vehicleTrim = "") }
        _availableModels.value = emptyList(); _availableTrims.value =
            emptyList(); _availableTrimNames.value = emptyList()

        // Skip API call if Not Listed
        if (make != VEHICLE_NOT_LISTED) {
            viewModelScope.launch {
                _availableModels.value =
                    listOf(VEHICLE_NOT_LISTED) + vehicleRepository.getModels(_state.value.vehicleYear, make)
            }
        }
    }

    fun onModelSelected(model: String) {
        _state.update { it.copy(vehicleModel = model, vehicleTrim = "") }
        _availableTrims.value = emptyList(); _availableTrimNames.value = emptyList()

        // Skip API call if Not Listed
        if (model != VEHICLE_NOT_LISTED) {
            viewModelScope.launch {
                val trims = listOf(
                    VehicleOption(id = "NOT_LISTED", displayName = VEHICLE_NOT_LISTED),
                ) + vehicleRepository.getVehicleOptions(
                    _state.value.vehicleYear,
                    _state.value.vehicleMake,
                    model,
                )
                _availableTrims.value = trims; _availableTrimNames.value =
                trims.map { it.displayName }
            }
        }
    }

    fun onTrimSelected(trimName: String) {
        _state.update { it.copy(vehicleTrim = trimName) }

        // Escape hatch! Don't look up MPG if Not Listed.
        if (trimName == VEHICLE_NOT_LISTED) return

        viewModelScope.launch {
            val vehicleId =
                _availableTrims.value.find { it.displayName == trimName }?.id ?: return@launch
            val details = vehicleRepository.getVehicleDetails(vehicleId)

            if (details != null) {
                _state.update { currentState ->
                    val cachedPrice = currentState.fetchedGasPrices[details.fuelType]
                    val newPrice =
                        if (currentState.isGasPriceAuto && cachedPrice != null) cachedPrice else currentState.gasPrice

                    currentState.copy(
                        estimatedMpg = details.combinedMpg ?: currentState.estimatedMpg,
                        fuelType = details.fuelType,
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

    fun updateFuelType(type: FuelType) {
        _state.update { currentState ->
            val cachedPrice = currentState.fetchedGasPrices[type]

            // If we have the price, swap instantly!
            if (currentState.isGasPriceAuto && cachedPrice != null) {
                currentState.copy(fuelType = type, gasPrice = cachedPrice)
            } else if (type == FuelType.ELECTRICITY && currentState.gasPrice > 1.0f) {
                // EV snap (#367, moved from GasPriceCard's LaunchedEffect): a
                // gas-level $/gal makes no sense as $/kWh — drop to a sane default.
                currentState.copy(fuelType = type, gasPrice = 0.30f)
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

                val fetched = result.getOrNull()
                if (fetched != null) {
                    val newPrice = fetched
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
            // awaitAll + associate (#367): the old code mutated a shared map
            // from parallel async blocks.
            val pricesMap = coroutineScope {
                FuelType.entries.map { fuel ->
                    async { fuel to gasPriceRepository.fetchGasPriceOnly(fuel).getOrNull() }
                }.awaitAll()
            }.mapNotNull { (fuel, price) -> price?.let { fuel to it } }.toMap()
            Timber.i("Fetched gas prices: %s", pricesMap)

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

    /**
     * Skip = a true skip (#347): mark first-run complete and write NOTHING else.
     * A later re-run + Skip must never reset tuned strategy/economy settings —
     * the old path funneled Skip through [saveAndFinish], silently restoring
     * hardcoded automation defaults.
     */
    fun skipAndFinish(onComplete: () -> Unit) {
        viewModelScope.launch {
            appStateRepository.setFirstRunComplete()
            onComplete()
        }
    }

    fun saveAndFinish(onComplete: () -> Unit) {
        viewModelScope.launch {
            val finalState = _state.value

            appPreferencesRepository.updateFuelType(finalState.fuelType)
            appPreferencesRepository.updateVehicleClass(finalState.vehicleClass)

            // Persist any economy fields the user explicitly set in the ECONOMY_COSTS step.
            // Each call atomically marks the corresponding EconomyField as user-set.
            val userSet = finalState.userSetEconomyFields
            if (EconomyField.TIRE_COST in userSet || EconomyField.TIRE_LIFETIME in userSet) {
                appPreferencesRepository.updateTireCost(finalState.tireSetCost, finalState.tireLifetimeMi)
            }
            if (EconomyField.OIL_COST in userSet || EconomyField.OIL_INTERVAL in userSet) {
                appPreferencesRepository.updateOilCost(finalState.oilCost, finalState.oilIntervalMi)
            }
            if (EconomyField.BRAKES_COST in userSet || EconomyField.BRAKES_INTERVAL in userSet) {
                appPreferencesRepository.updateBrakesCost(finalState.brakesCost, finalState.brakesIntervalMi)
            }
            if (EconomyField.FLUIDS_COST in userSet || EconomyField.FLUIDS_INTERVAL in userSet) {
                appPreferencesRepository.updateFluidsCost(finalState.fluidsCost, finalState.fluidsIntervalMi)
            }
            if (EconomyField.MISC_YEARLY in userSet || EconomyField.MISC_YEARLY_MI in userSet) {
                appPreferencesRepository.updateMiscMaintenance(finalState.miscYearly, finalState.miscYearlyMi)
            }
            if (EconomyField.INCLUDE_DEPRECIATION in userSet ||
                EconomyField.PURCHASE_PRICE in userSet ||
                EconomyField.TOTAL_LIFETIME_MI in userSet
            ) {
                appPreferencesRepository.updateDepreciation(
                    finalState.includeDepreciation,
                    finalState.purchasePrice,
                    finalState.totalLifetimeMi,
                )
            }
            if (EconomyField.INSURANCE_DELTA in userSet) {
                appPreferencesRepository.updateInsuranceDelta(finalState.insuranceDeltaPerMonth)
            }
            if (EconomyField.REGISTRATION_DELTA in userSet) {
                appPreferencesRepository.updateRegistrationDelta(finalState.registrationDeltaPerYear)
            }
            if (EconomyField.EXPECTED_ANNUAL_DASH_MI in userSet) {
                appPreferencesRepository.updateExpectedAnnualDashMi(finalState.expectedAnnualDashMiles)
            }
            if (EconomyField.PHONE_PLAN_TOTAL in userSet ||
                EconomyField.PHONE_PLAN_LINES in userSet ||
                EconomyField.PHONE_DASH_PERCENT in userSet
            ) {
                appPreferencesRepository.updatePhonePlan(
                    finalState.phonePlanTotal,
                    finalState.phonePlanLines,
                    finalState.phoneDashPercent,
                )
            }
            if (EconomyField.AVG_MIN_PER_MILE in userSet || EconomyField.BASE_PICKUP_MIN in userSet) {
                appPreferencesRepository.updateTimeConstants(
                    finalState.avgMinutesPerMile,
                    finalState.basePickupMinutes,
                )
            }

            if (finalState.vehicleClass == VehicleClass.E_BIKE) {
                appPreferencesRepository.updateEconomySettings(
                    "E-Bike",
                    "E-Bike",
                    "E-Bike",
                    "",
                    999f,
                    false,
                    0.0f
                )
            } else {
                appPreferencesRepository.updateEconomySettings(
                    finalState.vehicleYear, finalState.vehicleMake,
                    finalState.vehicleModel, finalState.vehicleTrim,
                    finalState.estimatedMpg, finalState.isGasPriceAuto, finalState.gasPrice
                )
            }

            val isCherryPicker = finalState.strategy == DashStrategy.CHERRY_PICKER
            val isPlatinum = finalState.strategy == DashStrategy.PROTECT_PLATINUM

            // Only write what the wizard actually collects (#347): the strategy-derived
            // toggles and the SHOPPING step's preference. The threshold values are NOT
            // wizard inputs — preserve their current values so a re-run + Finish
            // round-trips losslessly instead of resetting tuned automation config.
            val currentAutomation = strategyRepository.automationConfig.first()
            strategyRepository.setProtectStatsMode(isPlatinum)
            strategyRepository.setMasterAutomation(isCherryPicker)
            strategyRepository.updateAutomation(
                autoAccept = currentAutomation.autoAcceptEnabled,
                acceptMinPay = currentAutomation.autoAcceptMinPay,
                acceptMinRatio = currentAutomation.autoAcceptMinRatio,
                autoDecline = isCherryPicker,
                declineMaxPay = currentAutomation.autoDeclineMaxPay,
                declineMinRatio = currentAutomation.autoDeclineMinRatio,
            )
            // allowShopping is NOT collected by any wizard step (SHOPPING collects
            // maxItems only) — settings own that toggle; the wizard must not touch it.

            val currentRules = strategyRepository.scoringRules.first().toMutableList()
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

            strategyRepository.updateRules(updatedRules)
            appStateRepository.setFirstRunComplete()
            onComplete()
        }
    }
}