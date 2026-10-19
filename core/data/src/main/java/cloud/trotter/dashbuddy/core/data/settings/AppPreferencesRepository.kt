package cloud.trotter.dashbuddy.core.data.settings

import cloud.trotter.dashbuddy.core.datastore.settings.AppPreferencesDataSource
import cloud.trotter.dashbuddy.core.data.vehicle.GarageRepository
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsWindowSelection
import cloud.trotter.dashbuddy.domain.analytics.WindowGranularity
import cloud.trotter.dashbuddy.domain.evaluation.EconomyFieldGroups
import cloud.trotter.dashbuddy.domain.evaluation.EconomyField
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.evaluation.VehicleEconomyInputs
import cloud.trotter.dashbuddy.domain.evaluation.SharedEconomy
import cloud.trotter.dashbuddy.domain.model.vehicle.FuelType
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass
import cloud.trotter.dashbuddy.domain.model.vehicle.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppPreferencesRepository @Inject constructor(
    private val dataSource: AppPreferencesDataSource,
    /**
     * S2f removed an unused garage write dependency. The economy now consumes the active vehicle
     * on every read, and vehicle edits use the same source; the garage never reads settings.
     */
    private val garageRepository: GarageRepository,
) {
    // ============================================================================================
    // STREAMS
    // ============================================================================================
    val vehicleYear = dataSource.vehicleYear.combine(garageRepository.garage) { old, garage -> garage.active?.profile?.year ?: if (garage.active == null) old else null }
    val vehicleMake = dataSource.vehicleMake.combine(garageRepository.garage) { old, garage -> garage.active?.profile?.make ?: if (garage.active == null) old else null }
    val vehicleModel = dataSource.vehicleModel.combine(garageRepository.garage) { old, garage -> garage.active?.profile?.model ?: if (garage.active == null) old else null }
    val vehicleTrim = dataSource.vehicleTrim
    val estimatedMpg = dataSource.estimatedMpg.combine(garageRepository.garage) { old, garage -> garage.active?.profile?.gasMpg?.toFloat() ?: if (garage.active == null) old else null }
    val isGasPriceAuto = dataSource.isGasPriceAuto
    val gasPrice = dataSource.gasPrice
    val odometerPrompts = dataSource.odometerPrompts
    val countRealExpensesInKept = dataSource.countRealExpensesInKept
    val dailyKeptGoalDollars = dataSource.dailyKeptGoalDollars
    val hudPillShowsEarnings = dataSource.hudPillShowsEarnings
    val analyticsShowMore = dataSource.analyticsShowMore
    val vehicleLookupAllowed = dataSource.vehicleLookupAllowed
    val appTheme = dataSource.appTheme
    /** Driving / glance mode (#318) — the HUD honors this, the main app window never does. */
    val glanceMode = dataSource.glanceMode
    /** Spoken-offer language override (#428 Half B); null ⇒ follow the system locale. */
    val ttsLanguageTag = dataSource.ttsLanguageTag

    private val legacyFuelType: Flow<FuelType> = dataSource.fuelType.map { savedType ->
        try {
            FuelType.valueOf(savedType ?: FuelType.REGULAR.name)
        } catch (_: Exception) {
            FuelType.REGULAR
        }
    }
    val fuelType: Flow<FuelType> = legacyFuelType.combine(garageRepository.garage) { old, garage ->
        garage.active?.profile?.gasFuel ?: old
    }

    /**
     * The analytics hub's persisted review window (#970) — the `‹ ›` pager's selection, restored
     * across tab switches AND app restarts.
     *
     * **Fail-closed decode:** any unrecognized granularity, a relative selection with no offset, a
     * custom selection missing an endpoint, or a backwards custom range all fall back to
     * [AnalyticsWindowSelection.DEFAULT] (the current pay week) rather than throwing — a corrupt
     * preference must never be able to crash the hub open. A **positive** stored offset is clamped to
     * `0`: the pager can never select a future window, so a future offset can only be corruption.
     */
    val analyticsWindow: Flow<AnalyticsWindowSelection> =
        dataSource.analyticsWindow.map { prefs -> decodeAnalyticsWindow(prefs) }

    private fun decodeAnalyticsWindow(
        prefs: AppPreferencesDataSource.AnalyticsWindowPrefs,
    ): AnalyticsWindowSelection {
        val granularity = prefs.granularity
            ?.let { name -> WindowGranularity.entries.firstOrNull { it.name == name } }
            ?: return AnalyticsWindowSelection.DEFAULT
        return if (granularity == WindowGranularity.CUSTOM) {
            val start = prefs.startEpochDay ?: return AnalyticsWindowSelection.DEFAULT
            val end = prefs.endEpochDay ?: return AnalyticsWindowSelection.DEFAULT
            if (end < start) {
                AnalyticsWindowSelection.DEFAULT
            } else {
                AnalyticsWindowSelection.Custom(LocalDate.ofEpochDay(start), LocalDate.ofEpochDay(end))
            }
        } else {
            AnalyticsWindowSelection.Relative(granularity, (prefs.offset ?: 0).coerceAtMost(0))
        }
    }

    private val legacyVehicleClass: Flow<VehicleClass> = dataSource.vehicleClass.map { saved ->
        try {
            VehicleClass.valueOf(saved ?: VehicleClass.SEDAN.name)
        } catch (_: Exception) {
            VehicleClass.SEDAN
        }
    }
    val vehicleClass: Flow<VehicleClass> = legacyVehicleClass.combine(garageRepository.garage) { old, garage ->
        garage.active?.profile?.legacyClass ?: old
    }

    /**
     * Combines stored prefs into a [UserEconomy] ready for [EvaluationConfig].
     *
     * Each cost field falls back to the current [VehicleClass]'s preset value if
     * the DataStore key is missing — so switching class shifts any field NOT in
     * [userSetEconomyFields] to the new class's defaults without overwriting
     * user-set values. The `userSetFields` set on the returned [UserEconomy]
     * tracks which fields the user has explicitly written.
     *
     * Combine fans into 4 sub-tuples (DataStore's `combine` is limited to ~5 args).
     */
    val userEconomy: Flow<UserEconomy> = combine(
        // Identity + fuel + time
        combine(
            legacyVehicleClass,
            dataSource.estimatedMpg,
            dataSource.gasPrice,
            dataSource.avgMinPerMile,
            dataSource.basePickupMin,
        ) { c, mpg, price, minPerMi, basePickup ->
            IdentityTuple(c, mpg?.toDouble(), price?.toDouble(), minPerMi, basePickup)
        },
        // Maintenance (paired)
        combine(
            dataSource.tireSetCost, dataSource.tireLifetimeMi,
            dataSource.oilCost, dataSource.oilIntervalMi,
        ) { tireCost, tireLife, oilCost, oilInt ->
            MaintenanceTupleA(tireCost, tireLife, oilCost, oilInt)
        },
        combine(
            dataSource.brakesCost, dataSource.brakesIntervalMi,
            dataSource.fluidsCost, dataSource.fluidsIntervalMi,
            dataSource.miscYearly,
        ) { brakesCost, brakesInt, fluidsCost, fluidsInt, misc ->
            MaintenanceTupleB(brakesCost, brakesInt, fluidsCost, fluidsInt, misc)
        },
        // Depreciation + fixed-amortized + phone
        combine(
            dataSource.miscYearlyMi,
            dataSource.includeDepreciation,
            dataSource.purchasePrice,
            dataSource.totalLifetimeMi,
        ) { miscMi, includeDepr, price, lifetimeMi ->
            DepreciationTuple(miscMi, includeDepr, price, lifetimeMi)
        },
        combine(
            dataSource.insuranceDeltaPerMonth,
            dataSource.registrationDeltaPerYear,
            dataSource.expectedAnnualMi,
            dataSource.phonePlanTotal,
            dataSource.phonePlanLines,
        ) { ins, reg, annualMi, phoneTotal, phoneLines ->
            FixedAndPhoneTuple(ins, reg, annualMi, phoneTotal, phoneLines)
        },
    ) { id, maintA, maintB, depr, fixedPhone ->
        val cls = id.vehicleClass
        UserEconomy(
            vehicleClass = cls,
            vehicleMpg = id.mpg?.takeIf { it > 0 } ?: cls.defaultMpg,
            gasPricePerGallon = id.gasPrice ?: UserEconomy.DEFAULT_GAS_PRICE_PER_GALLON,
            avgMinutesPerMile = id.avgMinPerMi ?: UserEconomy.DEFAULT_MINUTES_PER_MILE,
            basePickupMinutes = id.basePickupMin ?: UserEconomy.DEFAULT_BASE_PICKUP_MINUTES,
            tireSetCost = maintA.tireCost ?: cls.tireSetCost,
            tireLifetimeMi = maintA.tireLife ?: cls.tireLifetimeMi,
            oilCost = maintA.oilCost ?: cls.oilCost,
            oilIntervalMi = maintA.oilInt ?: cls.oilIntervalMi,
            brakesCost = maintB.brakesCost ?: cls.brakesCost,
            brakesIntervalMi = maintB.brakesInt ?: cls.brakesIntervalMi,
            fluidsCost = maintB.fluidsCost ?: cls.fluidsCost,
            fluidsIntervalMi = maintB.fluidsInt ?: cls.fluidsIntervalMi,
            miscYearly = maintB.misc ?: cls.miscYearly,
            miscYearlyMi = depr.miscMi ?: cls.miscYearlyMi,
            includeDepreciation = depr.includeDepr ?: true,
            purchasePrice = depr.price ?: cls.purchasePrice,
            totalLifetimeMi = depr.lifetimeMi ?: cls.totalLifetimeMi,
            insuranceDeltaPerMonth = fixedPhone.insurance ?: 0.0,
            registrationDeltaPerYear = fixedPhone.registration ?: 0.0,
            expectedAnnualMiles = fixedPhone.annualMi ?: UserEconomy.DEFAULT_ANNUAL_MI,
            phonePlanTotal = fixedPhone.phoneTotal ?: UserEconomy.DEFAULT_PHONE_PLAN_TOTAL,
            phonePlanLines = fixedPhone.phoneLines ?: UserEconomy.DEFAULT_PHONE_PLAN_LINES,
            phoneBusinessPercent = UserEconomy.DEFAULT_PHONE_BUSINESS_PERCENT, // wired below via separate read
            userSetFields = emptySet(), // populated by mergeUserSetFields below
        )
    }
    // Wire userSetFields + phoneBusinessPercent on top — combine has a 5-arg max so we
    // do it as a final transform.
    .combine(dataSource.phoneBusinessPercent) { eco, phoneBusinessPct ->
        eco.copy(phoneBusinessPercent = phoneBusinessPct ?: UserEconomy.DEFAULT_PHONE_BUSINESS_PERCENT)
    }
    .combine(dataSource.userSetEconomyFields) { eco, savedNames ->
        eco.copy(userSetFields = parseUserSetFields(savedNames))
    }
    .combine(dataSource.electricityPricePerKwh) { base, tariff ->
        base.copy(electricityPricePerKwh = tariff ?: SharedEconomy.DEFAULT_ELECTRICITY_PRICE_PER_KWH)
    }
    .combine(dataSource.legacyVehicleProfile) { base, legacy -> base to legacy }
    .combine(garageRepository.garage) { (base, legacy), garage ->
        val shared = SharedEconomy.from(base)
        val profile = garage.active?.profile ?: legacy ?: GasCar()
        UserEconomy.from(VehicleEconomyInputs.from(profile, shared), shared).copy(activeVehicleId = garage.active?.id)
    }

    val activeVehicleId: Flow<String?> = garageRepository.garage.map { it.active?.id }


    private data class IdentityTuple(
        val vehicleClass: VehicleClass,
        val mpg: Double?,
        val gasPrice: Double?,
        val avgMinPerMi: Double?,
        val basePickupMin: Double?,
    )

    private data class MaintenanceTupleA(
        val tireCost: Double?, val tireLife: Double?,
        val oilCost: Double?, val oilInt: Double?,
    )

    private data class MaintenanceTupleB(
        val brakesCost: Double?, val brakesInt: Double?,
        val fluidsCost: Double?, val fluidsInt: Double?,
        val misc: Double?,
    )

    private data class DepreciationTuple(
        val miscMi: Double?,
        val includeDepr: Boolean?,
        val price: Double?,
        val lifetimeMi: Double?,
    )

    private data class FixedAndPhoneTuple(
        val insurance: Double?,
        val registration: Double?,
        val annualMi: Double?,
        val phoneTotal: Double?,
        val phoneLines: Int?,
    )

    // EconomyField owns its own legacy-name aliasing (#469) — see
    // EconomyField.fromPersistedName.
    private fun parseUserSetFields(names: Set<String>): Set<EconomyField> =
        names.mapNotNull(EconomyField::fromPersistedName).toSet()

    // ============================================================================================
    // WRITE ACTIONS
    // ============================================================================================
    suspend fun updateGasPrice(price: Float) = dataSource.updateGasPrice(price)

    /**
     * Manual pump-price override that also disables auto (EIA) fetching (#693) — the bubble's
     * between-dash quick edit routes here so the value survives the daily worker. Frozen-economics
     * invariant (#314): this only affects FUTURE evaluations; already-recorded deliveries keep their
     * frozen cost basis.
     */
    suspend fun updateGasPriceManual(price: Float) = dataSource.updateGasPriceManual(price)

    /**
     * "Resume auto" (#722) — re-enables auto (EIA) fetching AND applies a freshly-fetched price in
     * one atomic write; the bubble's MANUAL-mode "Resume auto" chip routes here. The inverse of
     * [updateGasPriceManual]. Frozen-economics invariant (#314): affects only FUTURE evaluations.
     */
    suspend fun updateGasPriceAuto(price: Float) = dataSource.updateGasPriceAuto(price)
    suspend fun updateFuelType(type: FuelType) {
        updateActiveProfile { it.withGas(fuelType = type) }
        mirrorFuelType(type)
    }
    suspend fun updateVehicleClass(type: VehicleClass) {
        updateActiveProfile { when (it) {
            is HybridCar -> it.copy(vehicleClass = GasCarClass.entries.firstOrNull { cls -> cls.name == type.name } ?: it.vehicleClass)
            is GasCar -> LegacyProfile(vehicleClass = type, mpg = it.mpg, fuelType = it.fuelType,
                year = it.year, make = it.make, model = it.model).toTyped().withCosts(it.wear, it.fixed)
            else -> it
        } }
        mirrorVehicleClass(type)
    }
    // Deferred saves commit their captured garage profile once, then mirror legacy preferences.
    suspend fun mirrorFuelType(type: FuelType) = dataSource.updateFuelType(type.name)
    suspend fun mirrorVehicleClass(type: VehicleClass) = dataSource.updateVehicleClass(type.name)

    suspend fun updateEstimatedMpg(mpg: Float) {
        require(mpg.isFinite() && mpg > 0f)
        updateActiveProfile { it.withGas(mpg = mpg.toDouble()) }
        dataSource.updateEstimatedMpg(mpg)
    }
    suspend fun setDailyKeptGoalDollars(value: Double?) = dataSource.setDailyKeptGoalDollars(value)

    suspend fun setOdometerPrompts(enabled: Boolean) = dataSource.setOdometerPrompts(enabled)

    suspend fun setCountRealExpensesInKept(enabled: Boolean) = dataSource.setCountRealExpensesInKept(enabled)
    suspend fun setHudPillShowsEarnings(enabled: Boolean) = dataSource.setHudPillShowsEarnings(enabled)
    suspend fun setAnalyticsShowMore(enabled: Boolean) = dataSource.setAnalyticsShowMore(enabled)
    suspend fun setVehicleLookupAllowed(allowed: Boolean) = dataSource.setVehicleLookupAllowed(allowed)
    suspend fun setTheme(theme: String) = dataSource.setTheme(theme)
    suspend fun setGlanceMode(enabled: Boolean) = dataSource.setGlanceMode(enabled)

    /**
     * #970 — persist the analytics review window. The DataStore is the single source of truth for the
     * selection: the hub writes here and reads [analyticsWindow] back reactively (no optimistic local
     * copy that could drift, Principle 5).
     */
    suspend fun setAnalyticsWindow(selection: AnalyticsWindowSelection) = when (selection) {
        is AnalyticsWindowSelection.Relative -> dataSource.setAnalyticsWindow(
            granularity = selection.granularity.name,
            offset = selection.offset,
            startEpochDay = null,
            endEpochDay = null,
        )
        is AnalyticsWindowSelection.Custom -> dataSource.setAnalyticsWindow(
            granularity = WindowGranularity.CUSTOM.name,
            offset = null,
            startEpochDay = selection.startDate.toEpochDay(),
            endEpochDay = selection.endDateInclusive.toEpochDay(),
        )
    }

    /**
     * #428 Half B — set (or clear, null) the spoken-offer language override (BCP-47 tag). Null
     * follows the system locale. The [TtsEffectHandler] observes [ttsLanguageTag] reactively, so
     * the change re-languages the live engine + spoken copy without a restart.
     */
    suspend fun setTtsLanguageTag(tag: String?) = dataSource.setTtsLanguageTag(tag)

    suspend fun updateEconomySettings(
        year: String, make: String, model: String, trim: String,
        mpg: Float, isGasAuto: Boolean, price: Float,
    ) {
        updateActiveProfile {
            when (it) {
                is GasCar -> it.copy(year = year, make = make, model = model, mpg = mpg.toDouble())
                is HybridCar -> it.copy(year = year, make = make, model = model, mpg = mpg.toDouble())
                else -> it
            }
        }
        mirrorEconomySettings(year, make, model, trim, mpg, isGasAuto, price)
    }


    suspend fun mirrorEconomySettings(
        year: String, make: String, model: String, trim: String,
        mpg: Float, isGasAuto: Boolean, price: Float,
    ) = dataSource.updateEconomySettings(year, make, model, trim, mpg, isGasAuto, price)

    // --- Personal Economy v2 writes (#145) ---
    suspend fun updateTireCost(setCost: Double, lifetimeMi: Double) {
        updateActiveProfile { it.withCosts(wear = it.wear.copy(tireSetCost = setCost, tireLifetimeMi = lifetimeMi)) }
        dataSource.updateTireCost(setCost, lifetimeMi)
    }

    suspend fun updateOilCost(cost: Double, intervalMi: Double) {
        updateActiveProfile { it.withCosts(wear = it.wear.copy(oilCost = cost, oilIntervalMi = intervalMi)) }
        dataSource.updateOilCost(cost, intervalMi)
    }

    suspend fun updateBrakesCost(cost: Double, intervalMi: Double) {
        updateActiveProfile { it.withCosts(wear = it.wear.copy(brakesCost = cost, brakesIntervalMi = intervalMi)) }
        dataSource.updateBrakesCost(cost, intervalMi)
    }

    suspend fun updateFluidsCost(cost: Double, intervalMi: Double) {
        updateActiveProfile { it.withCosts(wear = it.wear.copy(fluidsCost = cost, fluidsIntervalMi = intervalMi)) }
        dataSource.updateFluidsCost(cost, intervalMi)
    }

    suspend fun updateMiscMaintenance(yearly: Double, yearlyMi: Double) {
        updateActiveProfile { it.withCosts(wear = it.wear.copy(miscYearly = yearly, miscYearlyMi = yearlyMi)) }
        dataSource.updateMiscMaintenance(yearly, yearlyMi)
    }

    suspend fun updateDepreciation(include: Boolean, purchasePrice: Double, totalLifetimeMi: Double) {
        updateActiveProfile { it.withCosts(fixed = it.fixed.copy(includeDepreciation = include, purchasePrice = purchasePrice, totalLifetimeMi = totalLifetimeMi)) }
        dataSource.updateDepreciation(include, purchasePrice, totalLifetimeMi)
    }

    suspend fun updateInsuranceDelta(perMonth: Double) {
        updateActiveProfile { it.withCosts(fixed = it.fixed.copy(insuranceDeltaPerMonth = perMonth)) }
        dataSource.updateInsuranceDelta(perMonth)
    }

    suspend fun updateRegistrationDelta(perYear: Double) {
        updateActiveProfile { it.withCosts(fixed = it.fixed.copy(registrationDeltaPerYear = perYear)) }
        dataSource.updateRegistrationDelta(perYear)
    }

    private val warnedVehicleWrite = AtomicBoolean(false)

    private suspend fun updateActiveProfile(transform: (VehicleProfile) -> VehicleProfile) {
        try {
            garageRepository.updateOrCreateActiveProfile(System.currentTimeMillis(), transform)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (warnedVehicleWrite.compareAndSet(false, true)) {
                Timber.tag("Garage").w("%s", error.javaClass.simpleName)
            }
        }
    }

    suspend fun updateExpectedAnnualMi(miles: Double) {
        updateActiveProfile { it.withCosts(fixed = it.fixed.copy(expectedAnnualMiles = miles)) }
        dataSource.updateExpectedAnnualMi(miles)
    }

    suspend fun updatePhonePlan(total: Double, lines: Int, dashPercent: Double) =
        dataSource.updatePhonePlan(total, lines, dashPercent)

    suspend fun updateElectricityPrice(value: Double) = dataSource.updateElectricityPrice(value)
    suspend fun updateConsumption(value: Double) {
        require(value.isFinite() && value >= 0)
        updateActiveProfile { profile -> when (profile) {
            is ElectricCar -> profile.copy(kwhPer100Mi = value)
            is ScooterMoped -> if (profile.powertrain == Powertrain.ELECTRIC) profile.copy(kwhPer100Mi = value) else profile
            is EBike -> profile.copy(whPerMile = value)
            else -> profile
        } }
    }
    suspend fun updateChain(cost: Double, life: Double) = updateActiveProfile {
        it.withCosts(wear = it.wear.copy(chainCost = cost, chainLifeMi = life))
    }
    suspend fun updateBattery(cost: Double, life: Double) = updateActiveProfile {
        it.withCosts(wear = it.wear.copy(batteryCost = cost, batteryLifeMi = life))
    }
    suspend fun updateBelt(cost: Double, life: Double) = updateActiveProfile {
        it.withCosts(wear = it.wear.copy(beltCost = cost, beltLifeMi = life))
    }

    suspend fun setTimeConstantOverride(field: EconomyField, value: Double?) =
        dataSource.setTimeConstantOverride(field, value)

    suspend fun updateTimeConstants(avgMinPerMile: Double, basePickupMin: Double) =
        dataSource.updateTimeConstants(avgMinPerMile, basePickupMin)

    /** Commits explicit vehicle overrides together, then mirrors all explicit economy fields. */
    suspend fun persistUserSetEconomy(economy: UserEconomy) {
        if (EconomyFieldGroups.hasCostOverrides(economy)) {
            updateActiveProfile { EconomyFieldGroups.updateProfile(it, economy) }
        }
        mirrorUserSetEconomy(economy)
    }

    /** Legacy app_prefs writes only; deferred editors own their targeted garage write. */
    suspend fun mirrorUserSetEconomy(economy: UserEconomy) {
        for (group in EconomyFieldGroups.entries) {
            if (group.fields.none { it in economy.userSetFields }) continue
            when (val value = group.readFromEconomy(economy)) {
                // These have dedicated mirror methods used by deferred editors.
                is EconomyFieldGroups.VehicleClassValue,
                is EconomyFieldGroups.Mpg,
                is EconomyFieldGroups.GasPrice -> Unit
                is EconomyFieldGroups.Tires -> dataSource.updateTireCost(value.cost, value.lifetime)
                is EconomyFieldGroups.Oil -> dataSource.updateOilCost(value.cost, value.interval)
                is EconomyFieldGroups.Brakes -> dataSource.updateBrakesCost(value.cost, value.interval)
                is EconomyFieldGroups.Fluids -> dataSource.updateFluidsCost(value.cost, value.interval)
                is EconomyFieldGroups.Misc -> dataSource.updateMiscMaintenance(value.yearly, value.miles)
                is EconomyFieldGroups.Depreciation -> dataSource.updateDepreciation(value.include, value.price, value.lifetime)
                is EconomyFieldGroups.Insurance -> dataSource.updateInsuranceDelta(value.value)
                is EconomyFieldGroups.Registration -> dataSource.updateRegistrationDelta(value.value)
                is EconomyFieldGroups.AnnualMiles -> dataSource.updateExpectedAnnualMi(value.value)
                is EconomyFieldGroups.Phone -> dataSource.updatePhonePlan(value.total, value.lines, value.percent)
                is EconomyFieldGroups.Pace -> dataSource.setTimeConstantOverride(EconomyField.AVG_MIN_PER_MILE, value.value)
                is EconomyFieldGroups.Chain, is EconomyFieldGroups.Battery,
                is EconomyFieldGroups.Belt, is EconomyFieldGroups.Consumption -> Unit
                is EconomyFieldGroups.Tariff -> dataSource.updateElectricityPrice(value.value)
                is EconomyFieldGroups.Pickup -> dataSource.setTimeConstantOverride(EconomyField.BASE_PICKUP_MIN, value.value)
            }
        }
    }

    suspend fun resetEconomyDefaults() {
        updateActiveProfile { it.withCosts(WearProfile(), FixedCosts()) }
        dataSource.resetEconomyDefaults()
    }

    suspend fun clearPreferences() {
        Timber.tag(TAG).w("Clearing App Preferences")
        dataSource.clear()
    }

    private companion object {
        private const val TAG = "AppPrefs"
    }
}
