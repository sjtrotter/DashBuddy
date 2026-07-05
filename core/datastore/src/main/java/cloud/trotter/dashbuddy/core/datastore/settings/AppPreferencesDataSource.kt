package cloud.trotter.dashbuddy.core.datastore.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import cloud.trotter.dashbuddy.core.datastore.di.AppPreferences
import cloud.trotter.dashbuddy.domain.evaluation.EconomyField
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppPreferencesDataSource @Inject constructor(
    @param:AppPreferences private val ds: DataStore<Preferences>
) {
    private object Keys {
        val VEHICLE_YEAR = stringPreferencesKey("vehicle_year")
        val VEHICLE_MAKE = stringPreferencesKey("vehicle_make")
        val VEHICLE_MODEL = stringPreferencesKey("vehicle_model")
        val VEHICLE_TRIM = stringPreferencesKey("vehicle_trim")
        val ESTIMATED_MPG = floatPreferencesKey("estimated_mpg")
        val IS_GAS_PRICE_AUTO = booleanPreferencesKey("is_gas_price_auto")
        val GAS_PRICE = floatPreferencesKey("gas_price")
        val IS_PRO_MODE = booleanPreferencesKey("is_pro_mode")
        val APP_THEME = stringPreferencesKey("app_theme")
        // Driving / glance mode (#318) — bumps the HUD's LocalGlance multiplier.
        val GLANCE_MODE = booleanPreferencesKey("glance_mode")
        val FUEL_TYPE = stringPreferencesKey("fuel_type")
        // Legacy key (pre-v2 schema, "CAR"/"E_BIKE"). VEHICLE_CLASS supersedes it.
        val VEHICLE_TYPE = stringPreferencesKey("vehicle_type")
        val VEHICLE_CLASS = stringPreferencesKey("vehicle_class")

        // ----- Personal Economy v2 (#145) -----
        // Maintenance (paired)
        val TIRE_SET_COST = doublePreferencesKey("tire_set_cost")
        val TIRE_LIFETIME_MI = doublePreferencesKey("tire_lifetime_mi")
        val OIL_COST = doublePreferencesKey("oil_cost")
        val OIL_INTERVAL_MI = doublePreferencesKey("oil_interval_mi")
        val BRAKES_COST = doublePreferencesKey("brakes_cost")
        val BRAKES_INTERVAL_MI = doublePreferencesKey("brakes_interval_mi")
        val FLUIDS_COST = doublePreferencesKey("fluids_cost")
        val FLUIDS_INTERVAL_MI = doublePreferencesKey("fluids_interval_mi")
        val MISC_YEARLY = doublePreferencesKey("misc_yearly")
        val MISC_YEARLY_MI = doublePreferencesKey("misc_yearly_mi")

        // Depreciation
        val INCLUDE_DEPRECIATION = booleanPreferencesKey("include_depreciation")
        val PURCHASE_PRICE = doublePreferencesKey("purchase_price")
        val TOTAL_LIFETIME_MI = doublePreferencesKey("total_lifetime_mi")

        // Fixed costs (amortized via expected annual dash miles)
        val INSURANCE_DELTA_PER_MONTH = doublePreferencesKey("insurance_delta_per_month")
        val REGISTRATION_DELTA_PER_YEAR = doublePreferencesKey("registration_delta_per_year")
        val EXPECTED_ANNUAL_MI = doublePreferencesKey("expected_annual_dash_mi")

        // Phone & data (not vehicle-driven)
        val PHONE_PLAN_TOTAL = doublePreferencesKey("phone_plan_total")
        val PHONE_PLAN_LINES = intPreferencesKey("phone_plan_lines")
        val PHONE_BUSINESS_PERCENT = doublePreferencesKey("phone_dash_percent")

        // Time constants (newly persisted)
        val AVG_MIN_PER_MILE = doublePreferencesKey("avg_min_per_mile")
        val BASE_PICKUP_MIN = doublePreferencesKey("base_pickup_min")

        // Tracks which EconomyField enum values the user has explicitly set
        val USER_SET_ECONOMY_FIELDS = stringSetPreferencesKey("user_set_economy_fields")
    }

    // ============================================================================================
    // STREAMS
    // ============================================================================================
    val vehicleYear: Flow<String?> = ds.data.map { it[Keys.VEHICLE_YEAR] }
    val vehicleMake: Flow<String?> = ds.data.map { it[Keys.VEHICLE_MAKE] }
    val vehicleModel: Flow<String?> = ds.data.map { it[Keys.VEHICLE_MODEL] }
    val vehicleTrim: Flow<String?> = ds.data.map { it[Keys.VEHICLE_TRIM] }
    val estimatedMpg: Flow<Float?> = ds.data.map { it[Keys.ESTIMATED_MPG] }
    val isGasPriceAuto: Flow<Boolean> = ds.data.map { it[Keys.IS_GAS_PRICE_AUTO] ?: true }
    val gasPrice: Flow<Float?> = ds.data.map { it[Keys.GAS_PRICE] }
    val isProMode: Flow<Boolean> = ds.data.map { it[Keys.IS_PRO_MODE] ?: false }
    val appTheme: Flow<String?> = ds.data.map { it[Keys.APP_THEME] }
    val glanceMode: Flow<Boolean> = ds.data.map { it[Keys.GLANCE_MODE] ?: false }
    val fuelType: Flow<String?> = ds.data.map { it[Keys.FUEL_TYPE] }
    /**
     * Reads `vehicle_class` first; falls back to legacy `vehicle_type` (mapping
     * "CAR" → "SEDAN") for in-place migration. The first write of [updateVehicleClass]
     * persists the new key so the legacy fallback only fires once.
     */
    val vehicleClass: Flow<String?> = ds.data.map { prefs ->
        prefs[Keys.VEHICLE_CLASS]
            ?: when (prefs[Keys.VEHICLE_TYPE]) {
                "CAR" -> "SEDAN"
                "E_BIKE" -> "E_BIKE"
                else -> null
            }
    }

    val tireSetCost: Flow<Double?> = ds.data.map { it[Keys.TIRE_SET_COST] }
    val tireLifetimeMi: Flow<Double?> = ds.data.map { it[Keys.TIRE_LIFETIME_MI] }
    val oilCost: Flow<Double?> = ds.data.map { it[Keys.OIL_COST] }
    val oilIntervalMi: Flow<Double?> = ds.data.map { it[Keys.OIL_INTERVAL_MI] }
    val brakesCost: Flow<Double?> = ds.data.map { it[Keys.BRAKES_COST] }
    val brakesIntervalMi: Flow<Double?> = ds.data.map { it[Keys.BRAKES_INTERVAL_MI] }
    val fluidsCost: Flow<Double?> = ds.data.map { it[Keys.FLUIDS_COST] }
    val fluidsIntervalMi: Flow<Double?> = ds.data.map { it[Keys.FLUIDS_INTERVAL_MI] }
    val miscYearly: Flow<Double?> = ds.data.map { it[Keys.MISC_YEARLY] }
    val miscYearlyMi: Flow<Double?> = ds.data.map { it[Keys.MISC_YEARLY_MI] }

    val includeDepreciation: Flow<Boolean?> = ds.data.map { it[Keys.INCLUDE_DEPRECIATION] }
    val purchasePrice: Flow<Double?> = ds.data.map { it[Keys.PURCHASE_PRICE] }
    val totalLifetimeMi: Flow<Double?> = ds.data.map { it[Keys.TOTAL_LIFETIME_MI] }

    val insuranceDeltaPerMonth: Flow<Double?> = ds.data.map { it[Keys.INSURANCE_DELTA_PER_MONTH] }
    val registrationDeltaPerYear: Flow<Double?> = ds.data.map { it[Keys.REGISTRATION_DELTA_PER_YEAR] }
    val expectedAnnualMi: Flow<Double?> = ds.data.map { it[Keys.EXPECTED_ANNUAL_MI] }

    val phonePlanTotal: Flow<Double?> = ds.data.map { it[Keys.PHONE_PLAN_TOTAL] }
    val phonePlanLines: Flow<Int?> = ds.data.map { it[Keys.PHONE_PLAN_LINES] }
    val phoneBusinessPercent: Flow<Double?> = ds.data.map { it[Keys.PHONE_BUSINESS_PERCENT] }

    val avgMinPerMile: Flow<Double?> = ds.data.map { it[Keys.AVG_MIN_PER_MILE] }
    val basePickupMin: Flow<Double?> = ds.data.map { it[Keys.BASE_PICKUP_MIN] }

    val userSetEconomyFields: Flow<Set<String>> =
        ds.data.map { it[Keys.USER_SET_ECONOMY_FIELDS] ?: emptySet() }

    // ============================================================================================
    // ENCAPSULATED WRITE ACTIONS
    // ============================================================================================

    /**
     * Adds [fields] to the persisted user-set marker set. Takes [EconomyField] values
     * (not raw strings) so the marker names derive from the `:domain` enum — the SSOT
     * the read path already resolves through [EconomyField.fromPersistedName] — and a
     * field rename is caught by the compiler instead of silently writing a name no read
     * can resolve. Must be called from inside a [DataStore.edit] block.
     */
    private fun MutablePreferences.markUserSet(vararg fields: EconomyField) {
        this[Keys.USER_SET_ECONOMY_FIELDS] =
            (this[Keys.USER_SET_ECONOMY_FIELDS] ?: emptySet()) + fields.map { it.name }
    }

    suspend fun updateGasPrice(price: Float) {
        ds.edit { it[Keys.GAS_PRICE] = price }
    }

    suspend fun updateFuelType(type: String) {
        ds.edit { it[Keys.FUEL_TYPE] = type }
    }

    suspend fun updateVehicleClass(type: String) {
        ds.edit { it[Keys.VEHICLE_CLASS] = type }
    }

    suspend fun setProMode(enabled: Boolean) {
        ds.edit { it[Keys.IS_PRO_MODE] = enabled }
    }

    suspend fun setTheme(theme: String) {
        ds.edit { it[Keys.APP_THEME] = theme }
    }

    suspend fun setGlanceMode(enabled: Boolean) {
        ds.edit { it[Keys.GLANCE_MODE] = enabled }
    }

    suspend fun updateEconomySettings(
        year: String, make: String, model: String, trim: String,
        mpg: Float, isGasAuto: Boolean, price: Float
    ) {
        ds.edit { prefs ->
            prefs[Keys.VEHICLE_YEAR] = year
            prefs[Keys.VEHICLE_MAKE] = make
            prefs[Keys.VEHICLE_MODEL] = model
            prefs[Keys.VEHICLE_TRIM] = trim
            prefs[Keys.ESTIMATED_MPG] = mpg
            prefs[Keys.IS_GAS_PRICE_AUTO] = isGasAuto
            prefs[Keys.GAS_PRICE] = price
        }
    }


    // --- Personal Economy v2 writes (#145) ---
    // Each write atomically marks the affected EconomyField(s) as user-set so the UI
    // can show a "(default)" badge on still-default fields.

    suspend fun updateTireCost(setCost: Double, lifetimeMi: Double) {
        ds.edit {
            it[Keys.TIRE_SET_COST] = setCost
            it[Keys.TIRE_LIFETIME_MI] = lifetimeMi
            it.markUserSet(EconomyField.TIRE_COST, EconomyField.TIRE_LIFETIME)
        }
    }

    suspend fun updateOilCost(cost: Double, intervalMi: Double) {
        ds.edit {
            it[Keys.OIL_COST] = cost
            it[Keys.OIL_INTERVAL_MI] = intervalMi
            it.markUserSet(EconomyField.OIL_COST, EconomyField.OIL_INTERVAL)
        }
    }

    suspend fun updateBrakesCost(cost: Double, intervalMi: Double) {
        ds.edit {
            it[Keys.BRAKES_COST] = cost
            it[Keys.BRAKES_INTERVAL_MI] = intervalMi
            it.markUserSet(EconomyField.BRAKES_COST, EconomyField.BRAKES_INTERVAL)
        }
    }

    suspend fun updateFluidsCost(cost: Double, intervalMi: Double) {
        ds.edit {
            it[Keys.FLUIDS_COST] = cost
            it[Keys.FLUIDS_INTERVAL_MI] = intervalMi
            it.markUserSet(EconomyField.FLUIDS_COST, EconomyField.FLUIDS_INTERVAL)
        }
    }

    suspend fun updateMiscMaintenance(yearly: Double, yearlyMi: Double) {
        ds.edit {
            it[Keys.MISC_YEARLY] = yearly
            it[Keys.MISC_YEARLY_MI] = yearlyMi
            it.markUserSet(EconomyField.MISC_YEARLY, EconomyField.MISC_YEARLY_MI)
        }
    }

    suspend fun updateDepreciation(include: Boolean, purchasePrice: Double, totalLifetimeMi: Double) {
        ds.edit {
            it[Keys.INCLUDE_DEPRECIATION] = include
            it[Keys.PURCHASE_PRICE] = purchasePrice
            it[Keys.TOTAL_LIFETIME_MI] = totalLifetimeMi
            it.markUserSet(
                EconomyField.INCLUDE_DEPRECIATION,
                EconomyField.PURCHASE_PRICE,
                EconomyField.TOTAL_LIFETIME_MI,
            )
        }
    }

    suspend fun updateInsuranceDelta(perMonth: Double) {
        ds.edit {
            it[Keys.INSURANCE_DELTA_PER_MONTH] = perMonth
            it.markUserSet(EconomyField.INSURANCE_DELTA)
        }
    }

    suspend fun updateRegistrationDelta(perYear: Double) {
        ds.edit {
            it[Keys.REGISTRATION_DELTA_PER_YEAR] = perYear
            it.markUserSet(EconomyField.REGISTRATION_DELTA)
        }
    }

    suspend fun updateExpectedAnnualMi(miles: Double) {
        ds.edit {
            it[Keys.EXPECTED_ANNUAL_MI] = miles
            it.markUserSet(EconomyField.EXPECTED_ANNUAL_MI)
        }
    }

    suspend fun updatePhonePlan(total: Double, lines: Int, dashPercent: Double) {
        ds.edit {
            it[Keys.PHONE_PLAN_TOTAL] = total
            it[Keys.PHONE_PLAN_LINES] = lines
            it[Keys.PHONE_BUSINESS_PERCENT] = dashPercent
            it.markUserSet(
                EconomyField.PHONE_PLAN_TOTAL,
                EconomyField.PHONE_PLAN_LINES,
                EconomyField.PHONE_BUSINESS_PERCENT,
            )
        }
    }

    suspend fun updateTimeConstants(avgMinPerMile: Double, basePickupMin: Double) {
        ds.edit {
            it[Keys.AVG_MIN_PER_MILE] = avgMinPerMile
            it[Keys.BASE_PICKUP_MIN] = basePickupMin
            it.markUserSet(EconomyField.AVG_MIN_PER_MILE, EconomyField.BASE_PICKUP_MIN)
        }
    }

    /** Removes all user-set markers, falling all economy fields back to class defaults. */
    suspend fun resetEconomyDefaults() {
        ds.edit {
            it.remove(Keys.USER_SET_ECONOMY_FIELDS)
            it.remove(Keys.TIRE_SET_COST); it.remove(Keys.TIRE_LIFETIME_MI)
            it.remove(Keys.OIL_COST); it.remove(Keys.OIL_INTERVAL_MI)
            it.remove(Keys.BRAKES_COST); it.remove(Keys.BRAKES_INTERVAL_MI)
            it.remove(Keys.FLUIDS_COST); it.remove(Keys.FLUIDS_INTERVAL_MI)
            it.remove(Keys.MISC_YEARLY); it.remove(Keys.MISC_YEARLY_MI)
            it.remove(Keys.INCLUDE_DEPRECIATION)
            it.remove(Keys.PURCHASE_PRICE); it.remove(Keys.TOTAL_LIFETIME_MI)
            it.remove(Keys.INSURANCE_DELTA_PER_MONTH)
            it.remove(Keys.REGISTRATION_DELTA_PER_YEAR)
            it.remove(Keys.EXPECTED_ANNUAL_MI)
            it.remove(Keys.PHONE_PLAN_TOTAL)
            it.remove(Keys.PHONE_PLAN_LINES)
            it.remove(Keys.PHONE_BUSINESS_PERCENT)
            it.remove(Keys.AVG_MIN_PER_MILE)
            it.remove(Keys.BASE_PICKUP_MIN)
        }
    }

    suspend fun clear() {
        ds.edit { it.clear() }
    }
}
