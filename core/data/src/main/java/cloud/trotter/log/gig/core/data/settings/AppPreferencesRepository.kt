package cloud.trotter.log.gig.core.data.settings

import cloud.trotter.log.gig.core.datastore.settings.StoredPreference
import cloud.trotter.log.gig.core.datastore.settings.StoredGasPrice
import cloud.trotter.log.gig.core.datastore.settings.SaveCapture
import cloud.trotter.log.gig.core.datastore.settings.AppPreferencesDataSource
import cloud.trotter.log.gig.core.data.vehicle.GarageRepository
import cloud.trotter.log.gig.domain.analytics.AnalyticsWindowSelection
import cloud.trotter.log.gig.domain.analytics.WindowGranularity
import cloud.trotter.log.gig.domain.evaluation.EconomyFieldGroups
import cloud.trotter.log.gig.domain.evaluation.EconomyDraft
import cloud.trotter.log.gig.domain.evaluation.EconomyField
import cloud.trotter.log.gig.domain.evaluation.UserEconomy
import cloud.trotter.log.gig.domain.evaluation.VehicleEconomyInputs
import cloud.trotter.log.gig.domain.evaluation.SharedEconomy
import cloud.trotter.log.gig.domain.model.vehicle.FuelType
import cloud.trotter.log.gig.domain.model.vehicle.VehicleClass
import cloud.trotter.log.gig.domain.model.vehicle.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.time.LocalDate
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
    /**
     * The settings every vehicle shares (gas price, electricity tariff, time model, phone plan, the
     * user-set marks), BEFORE any vehicle is laid on top. One owner: [userEconomy] (the offer
     * evaluator's economy) and the Garage details page both build a vehicle's figures from it, so one
     * car's overrides never leak into another's (#1383 review).
     */
    val sharedEconomy: Flow<SharedEconomy> = combine(
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
    .map { SharedEconomy.from(it) }

    val userEconomy: Flow<UserEconomy> = sharedEconomy
    .combine(dataSource.legacyVehicleProfile) { shared, legacy -> shared to legacy }
    .combine(garageRepository.garage) { (shared, legacy), garage ->
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
    suspend fun updateGasPriceManual(price: Float) = updateGasPriceManual(price, null)
    suspend fun updateGasPriceManual(price: Float, capture: SaveCapture<StoredGasPrice>?) = dataSource.updateGasPriceManual(price, capture)

    /**
     * "Resume auto" (#722) — re-enables auto (EIA) fetching AND applies a freshly-fetched price in
     * one atomic write; the bubble's MANUAL-mode "Resume auto" chip routes here. The inverse of
     * [updateGasPriceManual]. Frozen-economics invariant (#314): affects only FUTURE evaluations.
     */
    suspend fun updateGasPriceAuto(price: Float) = dataSource.updateGasPriceAuto(price)
    // Deferred saves commit their captured garage profile once, then mirror legacy preferences.
    suspend fun mirrorFuelType(type: FuelType) = dataSource.updateFuelType(type.name)
    suspend fun mirrorVehicleClass(type: VehicleClass) = dataSource.updateVehicleClass(type.name)

    suspend fun setDailyKeptGoalDollars(value: Double?) = dataSource.setDailyKeptGoalDollars(value)

    suspend fun setOdometerPrompts(enabled: Boolean) = dataSource.setOdometerPrompts(enabled)

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

    suspend fun mirrorEconomySettings(
        year: String, make: String, model: String, trim: String,
        mpg: Float, isGasAuto: Boolean, price: Float,
    ) = dataSource.updateEconomySettings(year, make, model, trim, mpg, isGasAuto, price)

    suspend fun updatePhoneTotal(value: Double) = updatePhoneTotal(value, null)
    suspend fun updatePhoneTotal(value: Double, capture: SaveCapture<StoredPreference<Double>>?) = dataSource.updatePhoneTotal(value, capture)
    suspend fun updatePhoneLines(value: Int) = updatePhoneLines(value, null)
    suspend fun updatePhoneLines(value: Int, capture: SaveCapture<StoredPreference<Int>>?) = dataSource.updatePhoneLines(value, capture)
    suspend fun updatePhoneShare(value: Double) = updatePhoneShare(value, null)
    suspend fun updatePhoneShare(value: Double, capture: SaveCapture<StoredPreference<Double>>?) = dataSource.updatePhoneShare(value, capture)
    suspend fun updateElectricityPrice(value: Double) = updateElectricityPrice(value, null)
    suspend fun updateElectricityPrice(value: Double, capture: SaveCapture<StoredPreference<Double>>?) = dataSource.updateElectricityPrice(value, capture)
    suspend fun setGasPriceAuto(value: Boolean) = setGasPriceAuto(value, null)
    suspend fun setGasPriceAuto(value: Boolean, capture: SaveCapture<StoredPreference<Boolean>>?) = dataSource.setGasPriceAuto(value, capture)
    suspend fun setTimeConstantOverride(field: EconomyField, value: Double?) = setTimeConstantOverride(field, value, null)
    suspend fun setTimeConstantOverride(field: EconomyField, value: Double?, capture: SaveCapture<StoredPreference<Double>>?) = dataSource.setTimeConstantOverride(field, value, capture)

    suspend fun restoreElectricityPrice(previous: StoredPreference<Double>, expected: StoredPreference<Double>, current: () -> Boolean = { true }) = dataSource.restoreElectricityPrice(previous, expected, current)
    suspend fun restorePhoneTotal(previous: StoredPreference<Double>, expected: StoredPreference<Double>, current: () -> Boolean = { true }) = dataSource.restorePhoneTotal(previous, expected, current)
    suspend fun restorePhoneLines(previous: StoredPreference<Int>, expected: StoredPreference<Int>, current: () -> Boolean = { true }) = dataSource.restorePhoneLines(previous, expected, current)
    suspend fun restorePhoneShare(previous: StoredPreference<Double>, expected: StoredPreference<Double>, current: () -> Boolean = { true }) = dataSource.restorePhoneShare(previous, expected, current)
    suspend fun restoreGasPriceAuto(previous: StoredPreference<Boolean>, expected: StoredPreference<Boolean>, current: () -> Boolean = { true }) = dataSource.restoreGasPriceAuto(previous, expected, current)
    suspend fun restoreGasPriceManual(previous: StoredGasPrice, expected: StoredGasPrice, current: () -> Boolean = { true }) = dataSource.restoreGasPriceManual(previous, expected, current)
    suspend fun restoreTimeConstantOverride(field: EconomyField, previous: StoredPreference<Double>, expected: StoredPreference<Double>, current: () -> Boolean = { true }) = dataSource.restoreTimeConstantOverride(field, previous, expected, current)

    /** Legacy app_prefs writes only; deferred editors own their targeted garage write. */
    suspend fun mirrorUserSetEconomy(draft: EconomyDraft) {
        fun edited(field: EconomyField, value: Double): Double? = value.takeIf { field in draft.userSetFields }
        for (group in EconomyFieldGroups.entries) {
            if (group.fields.none { it in draft.userSetFields }) continue
            when (val value = group.read(draft)) {
                // These have dedicated mirror methods used by deferred editors.
                is EconomyFieldGroups.VehicleClassValue,
                is EconomyFieldGroups.Mpg,
                is EconomyFieldGroups.GasPrice -> Unit
                is EconomyFieldGroups.Tires -> dataSource.updateTireCost(
                    edited(EconomyField.TIRE_COST, value.cost),
                    edited(EconomyField.TIRE_LIFETIME, value.lifetime),
                )
                is EconomyFieldGroups.Oil -> dataSource.updateOilCost(
                    edited(EconomyField.OIL_COST, value.cost),
                    edited(EconomyField.OIL_INTERVAL, value.interval),
                )
                is EconomyFieldGroups.Brakes -> dataSource.updateBrakesCost(
                    edited(EconomyField.BRAKES_COST, value.cost),
                    edited(EconomyField.BRAKES_INTERVAL, value.interval),
                )
                is EconomyFieldGroups.Fluids -> dataSource.updateFluidsCost(
                    edited(EconomyField.FLUIDS_COST, value.cost),
                    edited(EconomyField.FLUIDS_INTERVAL, value.interval),
                )
                is EconomyFieldGroups.Misc -> dataSource.updateMiscMaintenance(
                    edited(EconomyField.MISC_YEARLY, value.yearly),
                    edited(EconomyField.MISC_YEARLY_MI, value.miles),
                )
                is EconomyFieldGroups.Depreciation -> dataSource.updateDepreciation(
                    value.include.takeIf { EconomyField.INCLUDE_DEPRECIATION in draft.userSetFields },
                    edited(EconomyField.PURCHASE_PRICE, value.price),
                    edited(EconomyField.TOTAL_LIFETIME_MI, value.lifetime),
                )
                is EconomyFieldGroups.Insurance -> dataSource.updateInsuranceDelta(value.value)
                is EconomyFieldGroups.Registration -> dataSource.updateRegistrationDelta(value.value)
                is EconomyFieldGroups.AnnualMiles -> dataSource.updateExpectedAnnualMi(value.value)
                is EconomyFieldGroups.Phone -> {
                    if (EconomyField.PHONE_PLAN_TOTAL in draft.userSetFields) dataSource.updatePhoneTotal(value.total)
                    if (EconomyField.PHONE_PLAN_LINES in draft.userSetFields) dataSource.updatePhoneLines(value.lines)
                    if (EconomyField.PHONE_BUSINESS_PERCENT in draft.userSetFields) dataSource.updatePhoneShare(value.percent)
                }
                is EconomyFieldGroups.Pace -> dataSource.setTimeConstantOverride(EconomyField.AVG_MIN_PER_MILE, value.value)
                is EconomyFieldGroups.Chain, is EconomyFieldGroups.Battery,
                is EconomyFieldGroups.Belt, is EconomyFieldGroups.Consumption -> Unit
                is EconomyFieldGroups.Tariff -> dataSource.updateElectricityPrice(value.value)
                is EconomyFieldGroups.Pickup -> dataSource.setTimeConstantOverride(EconomyField.BASE_PICKUP_MIN, value.value)
            }
        }
    }

    suspend fun resetSharedEconomyDefaults() = dataSource.resetSharedEconomyDefaults()

    suspend fun clearPreferences() {
        Timber.tag(TAG).w("Clearing App Preferences")
        dataSource.clear()
    }

    private companion object {
        private const val TAG = "AppPrefs"
    }
}
