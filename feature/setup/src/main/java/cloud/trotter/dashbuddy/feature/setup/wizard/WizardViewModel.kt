package cloud.trotter.dashbuddy.feature.setup.wizard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.fuel.FuelPriceRepository
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.state.AppStateRepository
import cloud.trotter.dashbuddy.core.data.strategy.StrategyRepository
import cloud.trotter.dashbuddy.core.data.vehicle.GarageRepository
import cloud.trotter.dashbuddy.core.data.vehicle.VehiclesUnreadableException
import cloud.trotter.dashbuddy.core.data.vehicle.VehicleRepository
import cloud.trotter.dashbuddy.domain.config.OfferStrategy
import cloud.trotter.dashbuddy.domain.evaluation.EconomyField
import cloud.trotter.dashbuddy.domain.evaluation.MetricType
import cloud.trotter.dashbuddy.domain.evaluation.ScoringRule
import cloud.trotter.dashbuddy.domain.evaluation.EconomyFieldGroups
import cloud.trotter.dashbuddy.domain.model.vehicle.FuelType
import cloud.trotter.dashbuddy.domain.model.vehicle.*
import cloud.trotter.dashbuddy.feature.setup.wizard.model.WizardState
import cloud.trotter.dashbuddy.feature.setup.wizard.model.WizardStep
import cloud.trotter.dashbuddy.feature.setup.wizard.model.toUserEconomy
import cloud.trotter.dashbuddy.feature.setup.wizard.model.toVehicleProfile
import cloud.trotter.dashbuddy.feature.setup.wizard.model.withTypeDefaults
import cloud.trotter.dashbuddy.feature.setup.wizard.vehicle.VehicleDraft
import cloud.trotter.dashbuddy.feature.setup.wizard.vehicle.VehicleDraftState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
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
    private val gasPriceRepository: FuelPriceRepository,
    private val garageRepository: GarageRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(WizardState())
    val state = _state.asStateFlow()
    val vehicleDraft = VehicleDraft(
        vehicleRepository, viewModelScope, VehicleDraftState(),
        onShapeChanged = ::applyVehicleShapeDefaults,
        onTrimDetails = ::applyTrimDetails,
        onTypeChanged = ::applyVehicleTypeDefaults,
    )
    val steps = vehicleDraft.state.map { vehicle ->
        WizardStep.entries.filter { it != WizardStep.GAS_PRICE || EconomyField.GAS_PRICE in EconomyFieldGroups.fieldsFor(vehicle.profile) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, WizardStep.entries)

    private fun applyVehicleShapeDefaults() {
        _state.update { it.withTypeDefaults(vehicleDraft.state.value) }
    }

    private fun applyVehicleTypeDefaults() {
        _state.update {
            it.copy(userSetEconomyFields = it.userSetEconomyFields -
                (EconomyFieldGroups.vehicleFields - VehicleDraftState.fields))
                .withTypeDefaults(vehicleDraft.state.value)
        }
    }

    private val _vehicleSaveFailed = MutableSharedFlow<Unit>(replay = 1)
    val vehicleSaveFailed = _vehicleSaveFailed.asSharedFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun vehicleSaveFailureHandled() = _vehicleSaveFailed.resetReplayCache()

    private var garageDraftLoaded = false

    init {
        Timber.tag("WizardViewModel").v("Initializing WizardViewModel")
        loadExistingSettings()
    }

    private fun loadExistingSettings() {
        viewModelScope.launch {
            val garage = garageRepository.garage.first()
            val activeProfile = garage.active?.profile
            val storedEconomy = appPreferencesRepository.userEconomy.first()
            // Provide safe defaults for the nullable DataStore flows!
            val currentYear = if (activeProfile != null) activeProfile.year.orEmpty() else appPreferencesRepository.vehicleYear.first().orEmpty()
            val currentMake = if (activeProfile != null) activeProfile.make.orEmpty() else appPreferencesRepository.vehicleMake.first().orEmpty()
            val currentModel = if (activeProfile != null) activeProfile.model.orEmpty() else appPreferencesRepository.vehicleModel.first().orEmpty()
            val currentTrim = if (activeProfile != null) "" else appPreferencesRepository.vehicleTrim.first().orEmpty()
            val currentMpg = if (activeProfile != null) {
                (activeProfile.gasMpg?.takeIf { it > 0 } ?: storedEconomy.vehicleMpg).toFloat()
            } else {
                appPreferencesRepository.estimatedMpg.first()?.takeIf { it > 0f }
                    ?: storedEconomy.vehicleMpg.toFloat()
            }
            val currentFuelType =
                activeProfile?.gasFuel ?: appPreferencesRepository.fuelType.first()
            val currentGasAuto = appPreferencesRepository.isGasPriceAuto.first()
            val currentGasPrice = appPreferencesRepository.gasPrice.first() ?: WizardState().gasPrice

            val currentProtectMode = strategyRepository.protectStatsMode.first()
            val currentStrategy =
                if (currentProtectMode) OfferStrategy.PROTECT_STATUS else OfferStrategy.MANUAL

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
            vehicleDraft.load(VehicleDraftState.from(
                profile = activeProfile, economy = storedEconomy,
                year = currentYear, make = currentMake, model = currentModel, trim = currentTrim,
                mpg = currentMpg, lookupAllowed = vehicleDraft.state.value.lookupAllowed,
            ))
            _state.update { currentState ->
                currentState.copy(
                    chainCost = storedEconomy.chainCost, chainLifeMi = storedEconomy.chainLifeMi,
                    batteryCost = storedEconomy.batteryCost, batteryLifeMi = storedEconomy.batteryLifeMi,
                    beltCost = storedEconomy.beltCost, beltLifeMi = storedEconomy.beltLifeMi,
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
                    expectedAnnualMiles = storedEconomy.expectedAnnualMiles,
                    phonePlanTotal = storedEconomy.phonePlanTotal,
                    phonePlanLines = storedEconomy.phonePlanLines,
                    phoneBusinessPercent = storedEconomy.phoneBusinessPercent,
                    avgMinutesPerMile = storedEconomy.avgMinutesPerMile,
                    basePickupMinutes = storedEconomy.basePickupMinutes,
                    userSetEconomyFields = storedEconomy.userSetFields - VehicleDraftState.fields,
                )
            }
            garageDraftLoaded = true
            // Wait for saved preferences before any startup gas/location request.
            attemptAutoGasPriceFetch()
            val vehicleLookupAllowed = appPreferencesRepository.vehicleLookupAllowed.first()
            if (vehicleLookupAllowed) vehicleDraft.allowLookup()
        }
    }

    fun allowVehicleLookup() {
        viewModelScope.launch {
            appPreferencesRepository.setVehicleLookupAllowed(true)
            vehicleDraft.allowLookup()
        }
    }

    fun updateChainCost(value: Double) = _state.update {
        it.copy(chainCost = value, userSetEconomyFields = it.userSetEconomyFields + EconomyField.CHAIN_COST)
    }

    fun updateChainLifetime(value: Double) = _state.update {
        it.copy(chainLifeMi = value, userSetEconomyFields = it.userSetEconomyFields + EconomyField.CHAIN_LIFETIME)
    }

    fun updateBatteryCost(value: Double) = _state.update {
        it.copy(batteryCost = value, userSetEconomyFields = it.userSetEconomyFields + EconomyField.BATTERY_COST)
    }

    fun updateBatteryLifetime(value: Double) = _state.update {
        it.copy(batteryLifeMi = value, userSetEconomyFields = it.userSetEconomyFields + EconomyField.BATTERY_LIFETIME)
    }

    fun updateBeltCost(value: Double) = _state.update {
        it.copy(beltCost = value, userSetEconomyFields = it.userSetEconomyFields + EconomyField.BELT_COST)
    }

    fun updateBeltLifetime(value: Double) = _state.update {
        it.copy(beltLifeMi = value, userSetEconomyFields = it.userSetEconomyFields + EconomyField.BELT_LIFETIME)
    }

    fun updateTireCost(value: Double) = _state.update {
        it.copy(tireSetCost = value, userSetEconomyFields = it.userSetEconomyFields + EconomyField.TIRE_COST)
    }

    fun updateTireLifetime(value: Double) = _state.update {
        it.copy(tireLifetimeMi = value, userSetEconomyFields = it.userSetEconomyFields + EconomyField.TIRE_LIFETIME)
    }

    fun updateOilCost(value: Double) = _state.update {
        it.copy(oilCost = value, userSetEconomyFields = it.userSetEconomyFields + EconomyField.OIL_COST)
    }

    fun updateOilInterval(value: Double) = _state.update {
        it.copy(oilIntervalMi = value, userSetEconomyFields = it.userSetEconomyFields + EconomyField.OIL_INTERVAL)
    }

    fun updateBrakesCost(value: Double) = _state.update {
        it.copy(brakesCost = value, userSetEconomyFields = it.userSetEconomyFields + EconomyField.BRAKES_COST)
    }

    fun updateBrakesInterval(value: Double) = _state.update {
        it.copy(brakesIntervalMi = value, userSetEconomyFields = it.userSetEconomyFields + EconomyField.BRAKES_INTERVAL)
    }

    fun updateFluidsCost(value: Double) = _state.update {
        it.copy(fluidsCost = value, userSetEconomyFields = it.userSetEconomyFields + EconomyField.FLUIDS_COST)
    }

    fun updateFluidsInterval(value: Double) = _state.update {
        it.copy(fluidsIntervalMi = value, userSetEconomyFields = it.userSetEconomyFields + EconomyField.FLUIDS_INTERVAL)
    }

    fun updateMiscYearly(value: Double) = _state.update {
        it.copy(miscYearly = value, userSetEconomyFields = it.userSetEconomyFields + EconomyField.MISC_YEARLY)
    }

    fun updateMiscMiles(value: Double) = _state.update {
        it.copy(miscYearlyMi = value, userSetEconomyFields = it.userSetEconomyFields + EconomyField.MISC_YEARLY_MI)
    }

    fun updateDepreciationInclude(value: Boolean) = _state.update {
        it.copy(includeDepreciation = value, userSetEconomyFields = it.userSetEconomyFields + EconomyField.INCLUDE_DEPRECIATION)
    }

    fun updatePurchasePrice(value: Double) = _state.update {
        it.copy(purchasePrice = value, userSetEconomyFields = it.userSetEconomyFields + EconomyField.PURCHASE_PRICE)
    }

    fun updateLifetimeMiles(value: Double) = _state.update {
        it.copy(totalLifetimeMi = value, userSetEconomyFields = it.userSetEconomyFields + EconomyField.TOTAL_LIFETIME_MI)
    }

    fun updateInsuranceDelta(value: Double) = _state.update {
        it.copy(insuranceDeltaPerMonth = value, userSetEconomyFields = it.userSetEconomyFields + EconomyField.INSURANCE_DELTA)
    }

    fun updateRegistrationDelta(value: Double) = _state.update {
        it.copy(registrationDeltaPerYear = value, userSetEconomyFields = it.userSetEconomyFields + EconomyField.REGISTRATION_DELTA)
    }

    fun updateExpectedAnnualMiles(value: Double) = _state.update {
        it.copy(expectedAnnualMiles = value, userSetEconomyFields = it.userSetEconomyFields + EconomyField.EXPECTED_ANNUAL_MI)
    }

    fun updatePhoneTotal(value: Double) = _state.update {
        it.copy(phonePlanTotal = value, userSetEconomyFields = it.userSetEconomyFields + EconomyField.PHONE_PLAN_TOTAL)
    }

    fun updatePhoneLines(value: Int) = _state.update {
        it.copy(phonePlanLines = value, userSetEconomyFields = it.userSetEconomyFields + EconomyField.PHONE_PLAN_LINES)
    }

    fun updatePhoneShare(value: Double) = _state.update {
        it.copy(phoneBusinessPercent = value, userSetEconomyFields = it.userSetEconomyFields + EconomyField.PHONE_BUSINESS_PERCENT)
    }

    fun updatePace(value: Double) = _state.update {
        it.copy(avgMinutesPerMile = value, userSetEconomyFields = it.userSetEconomyFields + EconomyField.AVG_MIN_PER_MILE)
    }

    fun updatePickup(value: Double) = _state.update {
        it.copy(basePickupMinutes = value, userSetEconomyFields = it.userSetEconomyFields + EconomyField.BASE_PICKUP_MIN)
    }

    private fun applyTrimDetails(details: VehicleDetails) {
        _state.update { currentState ->
            val cachedPrice = currentState.fetchedGasPrices[details.fuelType]
            val newPrice =
                if (currentState.isGasPriceAuto && cachedPrice != null) cachedPrice else currentState.gasPrice
            currentState.copy(fuelType = details.fuelType, gasPrice = newPrice)
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
        if (!_state.value.isGasPriceAuto || EconomyField.GAS_PRICE !in EconomyFieldGroups.fieldsFor(vehicleDraft.state.value.profile)) return

        viewModelScope.launch {
            _state.update { it.copy(isFetchingGasPrice = true) }
            // awaitAll + associate (#367): the old code mutated a shared map
            // from parallel async blocks.
            val pricesMap = coroutineScope {
                FuelType.entries.filter { it != FuelType.ELECTRICITY }.map { fuel ->
                    async { fuel to gasPriceRepository.fetchGasPriceOnly(fuel).getOrNull() }
                }.awaitAll()
            }.mapNotNull { (fuel, price) -> price?.let { fuel to it } }.toMap()
            Timber.tag("WizardViewModel").i("Fetched gas prices: %s", pricesMap)

            _state.update { currentState ->
                currentState.copy(
                    fetchedGasPrices = pricesMap,
                    gasPrice = pricesMap[currentState.fuelType] ?: currentState.gasPrice,
                    isFetchingGasPrice = false
                )
            }
        }
    }

    fun updateStrategy(strategy: OfferStrategy) {
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
            if (!garageDraftLoaded) return@launch
            val finalState = _state.value
            val vehicle = vehicleDraft.state.value

            val economy = finalState.toUserEconomy(vehicle)
            try {
                // Capture the save target now; later switches do not redirect this draft.
                val garage = garageRepository.garage.first()
                if (!garage.readable) throw VehiclesUnreadableException()
                val activeVehicle = garage.active
                val profile = finalState.toVehicleProfile(vehicle).withGas(fuelType = finalState.fuelType)
                val previous = activeVehicle?.profile
                if (previous == null || previous.vehicleType != profile.vehicleType ||
                    previous.year != profile.year || previous.make != profile.make || previous.model != profile.model) {
                    garageRepository.addVehicle(null, System.currentTimeMillis(), profile, activateOn = cloud.trotter.dashbuddy.core.data.vehicle.ActivateOn.ALWAYS)
                } else {
                    check(garageRepository.updateProfile(activeVehicle.id, profile))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.tag("Wizard").w("%s", error.javaClass.simpleName)
                _vehicleSaveFailed.emit(Unit)
            }

            appPreferencesRepository.mirrorFuelType(finalState.fuelType)
            appPreferencesRepository.mirrorVehicleClass(vehicle.vehicleClass)
            appPreferencesRepository.mirrorUserSetEconomy(economy)

            appPreferencesRepository.mirrorEconomySettings(
                vehicle.year, vehicle.make,
                vehicle.model, vehicle.trim,
                vehicle.mpg, finalState.isGasPriceAuto, finalState.gasPrice
            )

            val isProtectStatus = finalState.strategy == OfferStrategy.PROTECT_STATUS

            strategyRepository.setProtectStatsMode(isProtectStatus)
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
