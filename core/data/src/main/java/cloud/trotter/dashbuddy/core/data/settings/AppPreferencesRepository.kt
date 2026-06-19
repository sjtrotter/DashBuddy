package cloud.trotter.dashbuddy.core.data.settings

import cloud.trotter.dashbuddy.core.datastore.settings.AppPreferencesDataSource
import cloud.trotter.dashbuddy.domain.evaluation.EconomyField
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.model.vehicle.FuelType
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppPreferencesRepository @Inject constructor(
    private val dataSource: AppPreferencesDataSource
) {
    // ============================================================================================
    // STREAMS
    // ============================================================================================
    val vehicleYear = dataSource.vehicleYear
    val vehicleMake = dataSource.vehicleMake
    val vehicleModel = dataSource.vehicleModel
    val vehicleTrim = dataSource.vehicleTrim
    val estimatedMpg = dataSource.estimatedMpg
    val isGasPriceAuto = dataSource.isGasPriceAuto
    val gasPrice = dataSource.gasPrice
    val isProMode = dataSource.isProMode
    val appTheme = dataSource.appTheme

    val fuelType: Flow<FuelType> = dataSource.fuelType.map { savedType ->
        try {
            FuelType.valueOf(savedType ?: FuelType.REGULAR.name)
        } catch (_: Exception) {
            FuelType.REGULAR
        }
    }

    val vehicleClass: Flow<VehicleClass> = dataSource.vehicleClass.map { saved ->
        try {
            VehicleClass.valueOf(saved ?: VehicleClass.SEDAN.name)
        } catch (_: Exception) {
            VehicleClass.SEDAN
        }
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
            vehicleClass,
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
            vehicleMpg = id.mpg ?: cls.defaultMpg.coerceAtLeast(1.0),
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
    suspend fun updateFuelType(type: FuelType) = dataSource.updateFuelType(type.name)
    suspend fun updateVehicleClass(type: VehicleClass) = dataSource.updateVehicleClass(type.name)
    suspend fun setProMode(enabled: Boolean) = dataSource.setProMode(enabled)
    suspend fun setTheme(theme: String) = dataSource.setTheme(theme)

    suspend fun updateEconomySettings(
        year: String, make: String, model: String, trim: String,
        mpg: Float, isGasAuto: Boolean, price: Float,
    ) = dataSource.updateEconomySettings(year, make, model, trim, mpg, isGasAuto, price)


    // --- Personal Economy v2 writes (#145) ---
    suspend fun updateTireCost(setCost: Double, lifetimeMi: Double) =
        dataSource.updateTireCost(setCost, lifetimeMi)

    suspend fun updateOilCost(cost: Double, intervalMi: Double) =
        dataSource.updateOilCost(cost, intervalMi)

    suspend fun updateBrakesCost(cost: Double, intervalMi: Double) =
        dataSource.updateBrakesCost(cost, intervalMi)

    suspend fun updateFluidsCost(cost: Double, intervalMi: Double) =
        dataSource.updateFluidsCost(cost, intervalMi)

    suspend fun updateMiscMaintenance(yearly: Double, yearlyMi: Double) =
        dataSource.updateMiscMaintenance(yearly, yearlyMi)

    suspend fun updateDepreciation(include: Boolean, purchasePrice: Double, totalLifetimeMi: Double) =
        dataSource.updateDepreciation(include, purchasePrice, totalLifetimeMi)

    suspend fun updateInsuranceDelta(perMonth: Double) =
        dataSource.updateInsuranceDelta(perMonth)

    suspend fun updateRegistrationDelta(perYear: Double) =
        dataSource.updateRegistrationDelta(perYear)

    suspend fun updateExpectedAnnualMi(miles: Double) =
        dataSource.updateExpectedAnnualMi(miles)

    suspend fun updatePhonePlan(total: Double, lines: Int, dashPercent: Double) =
        dataSource.updatePhonePlan(total, lines, dashPercent)

    suspend fun updateTimeConstants(avgMinPerMile: Double, basePickupMin: Double) =
        dataSource.updateTimeConstants(avgMinPerMile, basePickupMin)

    /**
     * Persist a [UserEconomy] snapshot through the user-set write path, for ONLY
     * the fields the user explicitly set in [economy]'s [UserEconomy.userSetFields].
     *
     * This is the single owner of the `EconomyField → grouped write` mapping
     * (#357 SSOT): a deferred-commit editor (the setup wizard) collects edits in
     * memory, then hands its snapshot here so each user-set field routes through
     * the exact same atomic write — and atomic user-set marker — the immediate
     * Personal Economy settings screen uses. Callers no longer re-derive the
     * mapping with a parallel `if (field in userSet)` chain. Fields the user did
     * not touch are left untouched, so they keep tracking class defaults.
     */
    suspend fun persistUserSetEconomy(economy: UserEconomy) {
        val userSet = economy.userSetFields
        // Grouped writes mirror the data-source write methods, which each mark
        // their whole group user-set atomically — so any one member of a group
        // being user-set commits (and re-marks) the whole pair/triple.
        if (EconomyField.TIRE_COST in userSet || EconomyField.TIRE_LIFETIME in userSet) {
            updateTireCost(economy.tireSetCost, economy.tireLifetimeMi)
        }
        if (EconomyField.OIL_COST in userSet || EconomyField.OIL_INTERVAL in userSet) {
            updateOilCost(economy.oilCost, economy.oilIntervalMi)
        }
        if (EconomyField.BRAKES_COST in userSet || EconomyField.BRAKES_INTERVAL in userSet) {
            updateBrakesCost(economy.brakesCost, economy.brakesIntervalMi)
        }
        if (EconomyField.FLUIDS_COST in userSet || EconomyField.FLUIDS_INTERVAL in userSet) {
            updateFluidsCost(economy.fluidsCost, economy.fluidsIntervalMi)
        }
        if (EconomyField.MISC_YEARLY in userSet || EconomyField.MISC_YEARLY_MI in userSet) {
            updateMiscMaintenance(economy.miscYearly, economy.miscYearlyMi)
        }
        if (EconomyField.INCLUDE_DEPRECIATION in userSet ||
            EconomyField.PURCHASE_PRICE in userSet ||
            EconomyField.TOTAL_LIFETIME_MI in userSet
        ) {
            updateDepreciation(
                economy.includeDepreciation,
                economy.purchasePrice,
                economy.totalLifetimeMi,
            )
        }
        if (EconomyField.INSURANCE_DELTA in userSet) {
            updateInsuranceDelta(economy.insuranceDeltaPerMonth)
        }
        if (EconomyField.REGISTRATION_DELTA in userSet) {
            updateRegistrationDelta(economy.registrationDeltaPerYear)
        }
        if (EconomyField.EXPECTED_ANNUAL_MI in userSet) {
            updateExpectedAnnualMi(economy.expectedAnnualMiles)
        }
        if (EconomyField.PHONE_PLAN_TOTAL in userSet ||
            EconomyField.PHONE_PLAN_LINES in userSet ||
            EconomyField.PHONE_BUSINESS_PERCENT in userSet
        ) {
            updatePhonePlan(
                economy.phonePlanTotal,
                economy.phonePlanLines,
                economy.phoneBusinessPercent,
            )
        }
        if (EconomyField.AVG_MIN_PER_MILE in userSet || EconomyField.BASE_PICKUP_MIN in userSet) {
            updateTimeConstants(economy.avgMinutesPerMile, economy.basePickupMinutes)
        }
    }

    suspend fun resetEconomyDefaults() = dataSource.resetEconomyDefaults()

    suspend fun clearPreferences() {
        Timber.Forest.w("Clearing App Preferences")
        dataSource.clear()
    }
}
