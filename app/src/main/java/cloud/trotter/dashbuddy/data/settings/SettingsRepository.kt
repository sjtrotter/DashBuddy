package cloud.trotter.dashbuddy.data.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cloud.trotter.dashbuddy.BuildConfig
import cloud.trotter.dashbuddy.model.config.EvidenceConfig
import cloud.trotter.dashbuddy.model.config.MetricType
import cloud.trotter.dashbuddy.model.config.OfferAutomationConfig
import cloud.trotter.dashbuddy.model.config.ScoringRule
import cloud.trotter.dashbuddy.model.vehicle.FuelType
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.Screen
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "dashbuddy_settings_v3")

/**
 * Single source of truth for all user preferences and application configuration.
 * Backed by AndroidX DataStore for asynchronous, transactional disk reads/writes.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private object Keys {
        // Gates
        val IS_FIRST_RUN = booleanPreferencesKey("is_first_run")
        val IS_PRO_MODE = booleanPreferencesKey("is_pro_mode")
        val APP_THEME = stringPreferencesKey("app_theme")
        val LOG_LEVEL = intPreferencesKey("app_log_level")

        // Economy & Vehicle
        val VEHICLE_YEAR = stringPreferencesKey("vehicle_year")
        val VEHICLE_MAKE = stringPreferencesKey("vehicle_make")
        val VEHICLE_MODEL = stringPreferencesKey("vehicle_model")
        val VEHICLE_TRIM = stringPreferencesKey("vehicle_trim")
        val ESTIMATED_MPG = floatPreferencesKey("estimated_mpg")
        val FUEL_TYPE = stringPreferencesKey("vehicle_fuel_type") // <-- NEW
        val IS_GAS_PRICE_AUTO = booleanPreferencesKey("is_gas_price_auto")
        val GAS_PRICE = floatPreferencesKey("gas_price")

        // Evidence Locker
        val EVIDENCE_MASTER = booleanPreferencesKey("evidence_master_enabled")
        val EVIDENCE_OFFERS = booleanPreferencesKey("evidence_save_offers")
        val EVIDENCE_DELIVERY = booleanPreferencesKey("evidence_save_delivery_summary")
        val EVIDENCE_DASH = booleanPreferencesKey("evidence_save_dash_summary")

        // Automation
        val AUTO_MASTER = booleanPreferencesKey("auto_master_enabled")
        val AUTO_ACCEPT = booleanPreferencesKey("auto_accept_enabled")
        val AUTO_ACCEPT_MIN_PAY = doublePreferencesKey("auto_accept_min_pay")
        val AUTO_DECLINE = booleanPreferencesKey("auto_decline_enabled")

        // Rules
        val RULE_LIST_JSON = stringPreferencesKey("rule_list_config_v1")
        val PROTECT_STATS_MODE = booleanPreferencesKey("protect_stats_mode")
        val ALLOW_SHOPPING = booleanPreferencesKey("allow_shopping")

        // Sim
        val SIM_PAY = doublePreferencesKey("sim_test_pay")
        val SIM_DIST = doublePreferencesKey("sim_test_dist")

        // Dev Mode
        val IS_DEV_MODE_UNLOCKED = booleanPreferencesKey("is_dev_mode_unlocked")
    }

    private val defaultRules = listOf(
        ScoringRule.MetricRule("pay", true, MetricType.PAYOUT, 7.0f),
        ScoringRule.MetricRule("dpm", true, MetricType.DOLLAR_PER_MILE, 1.5f),
        ScoringRule.MetricRule("dist", true, MetricType.MAX_DISTANCE, 10.0f),
        ScoringRule.MetricRule("hr", true, MetricType.ACTIVE_HOURLY, 22.0f),
        ScoringRule.MetricRule("items", false, MetricType.ITEM_COUNT, 50.0f)
    )

    // ============================================================================================
    // DEVELOPER SETTINGS
    // ============================================================================================

    private val defaultSnapshotWhitelist = if (BuildConfig.DEBUG) {
        Screen.entries.toSet()
    } else {
        emptySet()
    }

    private val _snapshotWhitelist = MutableStateFlow(defaultSnapshotWhitelist)
    val snapshotWhitelist = _snapshotWhitelist.asStateFlow()

    private val _devSnapshotsEnabled = MutableStateFlow(BuildConfig.DEBUG)
    val devSnapshotsEnabled = _devSnapshotsEnabled.asStateFlow()

    fun toggleSnapshotScreen(screen: Screen, isEnabled: Boolean) {
        Timber.v("Toggling snapshot screen override: %s = %b", screen.name, isEnabled)
        val current = _snapshotWhitelist.value.toMutableSet()
        if (isEnabled) current.add(screen) else current.remove(screen)
        _snapshotWhitelist.value = current
    }

    fun enableSensitiveSnapshots(enabled: Boolean) {
        toggleSnapshotScreen(Screen.SENSITIVE, enabled)
    }

    // ============================================================================================
    // HOT STREAMS (Eagerly evaluated for rapid pipeline access)
    // ============================================================================================

    val evidenceConfig = context.dataStore.data.map { prefs ->
        EvidenceConfig(
            masterEnabled = prefs[Keys.EVIDENCE_MASTER] ?: false,
            saveOffers = prefs[Keys.EVIDENCE_OFFERS] ?: true,
            saveDeliverySummaries = prefs[Keys.EVIDENCE_DELIVERY] ?: true,
            saveDashSummaries = prefs[Keys.EVIDENCE_DASH] ?: true
        )
    }.stateIn(scope, SharingStarted.Eagerly, EvidenceConfig())

    val minLogLevel = context.dataStore.data.map { prefs ->
        prefs[Keys.LOG_LEVEL] ?: if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO
    }.stateIn(scope, SharingStarted.Eagerly, if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO)

    // ============================================================================================
    // STANDARD STREAMS (Pass-through)
    // ============================================================================================

    // Dev Mode
    val isDevModeUnlocked: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.IS_DEV_MODE_UNLOCKED] ?: BuildConfig.DEBUG
    }

    // Economy Streams
    val vehicleYear: Flow<String> = context.dataStore.data.map { it[Keys.VEHICLE_YEAR] ?: "" }
    val vehicleMake: Flow<String> = context.dataStore.data.map { it[Keys.VEHICLE_MAKE] ?: "" }
    val vehicleModel: Flow<String> = context.dataStore.data.map { it[Keys.VEHICLE_MODEL] ?: "" }
    val vehicleTrim: Flow<String> = context.dataStore.data.map { it[Keys.VEHICLE_TRIM] ?: "" }
    val estimatedMpg: Flow<Float> = context.dataStore.data.map { it[Keys.ESTIMATED_MPG] ?: 25.0f }
    val isGasPriceAuto: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.IS_GAS_PRICE_AUTO] ?: true }
    val gasPrice: Flow<Float> = context.dataStore.data.map { it[Keys.GAS_PRICE] ?: 3.50f }

    // NEW: Fuel Type
    val fuelType: Flow<FuelType> = context.dataStore.data.map { prefs ->
        val savedType = prefs[Keys.FUEL_TYPE] ?: FuelType.REGULAR.name
        try {
            FuelType.valueOf(savedType)
        } catch (_: IllegalArgumentException) {
            FuelType.REGULAR
        }
    }

    // Other Configs
    val automationConfig: Flow<OfferAutomationConfig> = context.dataStore.data.map { prefs ->
        OfferAutomationConfig(
            masterAutoPilotEnabled = prefs[Keys.AUTO_MASTER] ?: false,
            autoAcceptEnabled = prefs[Keys.AUTO_ACCEPT] ?: false,
            autoAcceptMinPay = prefs[Keys.AUTO_ACCEPT_MIN_PAY] ?: 10.0,
            autoDeclineEnabled = prefs[Keys.AUTO_DECLINE] ?: false
        )
    }

    val scoringRules: Flow<List<ScoringRule>> = context.dataStore.data.map { prefs ->
        val json = prefs[Keys.RULE_LIST_JSON]
        if (json.isNullOrBlank()) defaultRules else {
            try {
                Json.decodeFromString(json)
            } catch (e: Exception) {
                Timber.e(e, "Failed to decode scoring rules JSON, falling back to defaults.")
                defaultRules
            }
        }
    }

    val protectStatsMode: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.PROTECT_STATS_MODE] ?: false }
    val allowShopping: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.ALLOW_SHOPPING] ?: true }
    val isProMode: Flow<Boolean> = context.dataStore.data.map { it[Keys.IS_PRO_MODE] ?: false }
    val isFirstRun: Flow<Boolean> = context.dataStore.data.map { it[Keys.IS_FIRST_RUN] ?: true }
    val appTheme: Flow<String> = context.dataStore.data.map { it[Keys.APP_THEME] ?: "system" }

    // ============================================================================================
    // WRITE ACTIONS
    // ============================================================================================

    // Dev Mode
    suspend fun setDevModeUnlocked(unlocked: Boolean) {
        Timber.w("Developer Mode Unlocked: %b", unlocked)
        context.dataStore.edit { it[Keys.IS_DEV_MODE_UNLOCKED] = unlocked }
    }

    suspend fun updateEconomySettings(
        year: String, make: String, model: String, trim: String,
        mpg: Float, isGasAuto: Boolean, gasPrice: Float
    ) {
        Timber.d("Updating economy config: $year $make $model $trim | $mpg MPG | Gas=$gasPrice (Auto=$isGasAuto)")
        context.dataStore.edit { prefs ->
            prefs[Keys.VEHICLE_YEAR] = year
            prefs[Keys.VEHICLE_MAKE] = make
            prefs[Keys.VEHICLE_MODEL] = model
            prefs[Keys.VEHICLE_TRIM] = trim
            prefs[Keys.ESTIMATED_MPG] = mpg
            prefs[Keys.IS_GAS_PRICE_AUTO] = isGasAuto
            prefs[Keys.GAS_PRICE] = gasPrice
        }
    }

    // --- NEW GAS METHODS ---
    suspend fun updateGasPrice(price: Float) {
        Timber.i("Gas price updated in DataStore to: $%.2f", price)
        context.dataStore.edit { it[Keys.GAS_PRICE] = price }
    }

    suspend fun updateFuelType(type: FuelType) {
        Timber.i("Fuel type updated in DataStore to: %s", type.name)
        context.dataStore.edit { it[Keys.FUEL_TYPE] = type.name }
    }
    // -----------------------

    suspend fun setLogLevel(priority: Int) {
        Timber.v("Log level overridden to: %d", priority)
        context.dataStore.edit { it[Keys.LOG_LEVEL] = priority }
    }

    suspend fun setEvidenceMaster(enabled: Boolean) {
        Timber.d("Evidence master toggle set to: %b", enabled)
        context.dataStore.edit { prefs ->
            prefs[Keys.EVIDENCE_MASTER] = enabled
            if (enabled) {
                if (prefs[Keys.EVIDENCE_OFFERS] == null) prefs[Keys.EVIDENCE_OFFERS] = true
                if (prefs[Keys.EVIDENCE_DELIVERY] == null) prefs[Keys.EVIDENCE_DELIVERY] = true
            }
        }
    }

    suspend fun updateRules(newRules: List<ScoringRule>) {
        Timber.v("Updating strategy rules: %d rules saved", newRules.size)
        context.dataStore.edit { prefs ->
            prefs[Keys.RULE_LIST_JSON] = Json.encodeToString(newRules)
        }
    }

    suspend fun setProtectStatsMode(enabled: Boolean) {
        Timber.d("Protect Platinum mode set to: %b", enabled)
        context.dataStore.edit { it[Keys.PROTECT_STATS_MODE] = enabled }
    }

    suspend fun setAllowShopping(allowed: Boolean) {
        Timber.d("Allow Shopping mode set to: %b", allowed)
        context.dataStore.edit { it[Keys.ALLOW_SHOPPING] = allowed }
    }

    suspend fun updateEvidenceConfig(offers: Boolean, delivery: Boolean, dash: Boolean) {
        Timber.v(
            "Evidence granular config updated: Offers=%b, Delivery=%b, Dash=%b",
            offers,
            delivery,
            dash
        )
        context.dataStore.edit { prefs ->
            prefs[Keys.EVIDENCE_OFFERS] = offers
            prefs[Keys.EVIDENCE_DELIVERY] = delivery
            prefs[Keys.EVIDENCE_DASH] = dash
        }
    }

    suspend fun setMasterAutomation(enabled: Boolean) {
        Timber.w("Automation master switch set to: %b", enabled)
        context.dataStore.edit { it[Keys.AUTO_MASTER] = enabled }
    }

    suspend fun updateAutomation(autoAccept: Boolean, minPay: Double, autoDecline: Boolean) {
        Timber.v(
            "Automation updated: Accept=%b (Min: $%.2f), Decline=%b",
            autoAccept,
            minPay,
            autoDecline
        )
        context.dataStore.edit { prefs ->
            prefs[Keys.AUTO_ACCEPT] = autoAccept
            prefs[Keys.AUTO_ACCEPT_MIN_PAY] = minPay
            prefs[Keys.AUTO_DECLINE] = autoDecline
        }
    }

    suspend fun setFirstRunComplete() {
        Timber.i("First run wizard completed by user.")
        context.dataStore.edit { it[Keys.IS_FIRST_RUN] = false }
    }

    suspend fun setProMode(enabled: Boolean) {
        Timber.i("Pro Mode status changed: %b", enabled)
        context.dataStore.edit { it[Keys.IS_PRO_MODE] = enabled }
    }

    suspend fun setTheme(theme: String) {
        Timber.v("App theme changed to: %s", theme)
        context.dataStore.edit { it[Keys.APP_THEME] = theme }
    }

    suspend fun saveSimulationState(pay: Double, dist: Double) {
        context.dataStore.edit { it[Keys.SIM_PAY] = pay; it[Keys.SIM_DIST] = dist }
    }

    suspend fun resetDefaults() {
        Timber.w("FACTORY RESET TRIGGERED. Erasing all DataStore preferences.")
        context.dataStore.edit { it.clear() }
    }
}