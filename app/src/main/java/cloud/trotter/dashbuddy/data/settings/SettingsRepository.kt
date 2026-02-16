package cloud.trotter.dashbuddy.data.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cloud.trotter.dashbuddy.BuildConfig
import cloud.trotter.dashbuddy.model.config.EvidenceConfig
import cloud.trotter.dashbuddy.model.config.MetricType
import cloud.trotter.dashbuddy.model.config.OfferAutomationConfig
import cloud.trotter.dashbuddy.model.config.ScoringRule
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
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "dashbuddy_settings_v3")

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
        val LOG_LEVEL = intPreferencesKey("app_log_level") // <--- Storing as Int now

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
    }

    private val defaultRules = listOf(
        ScoringRule.MetricRule("pay", true, MetricType.PAYOUT, 7.0f),
        ScoringRule.MetricRule("dpm", true, MetricType.DOLLAR_PER_MILE, 1.5f),
        ScoringRule.MetricRule("dist", true, MetricType.MAX_DISTANCE, 10.0f),
        ScoringRule.MetricRule("hr", true, MetricType.ACTIVE_HOURLY, 22.0f),
        ScoringRule.MetricRule("items", false, MetricType.ITEM_COUNT, 50.0f)
    )

    // --- DEVELOPER SETTINGS ---

    // 2. Whitelist: Default to "Everything" if Debugging, "Nothing" if Release
    private val defaultSnapshotWhitelist = if (BuildConfig.DEBUG) {
        // Debug: Capture everything for analysis (except Sensitive)
        Screen.entries.toSet()
    } else {
        // Release: Default to empty. (Or maybe just UNKNOWN if you want beta-tester snapshots)
        emptySet()
    }

    // 2. The StateFlow holds the "Allowed" list
    private val _snapshotWhitelist = MutableStateFlow(defaultSnapshotWhitelist)
    val snapshotWhitelist = _snapshotWhitelist.asStateFlow()

    // 3. The Master Toggle (Global On/Off)
    private val _devSnapshotsEnabled = MutableStateFlow(BuildConfig.DEBUG)
    val devSnapshotsEnabled = _devSnapshotsEnabled.asStateFlow()

    // --- ACTIONS ---

    fun toggleSnapshotScreen(screen: Screen, isEnabled: Boolean) {
        val current = _snapshotWhitelist.value.toMutableSet()
        if (isEnabled) current.add(screen) else current.remove(screen)
        _snapshotWhitelist.value = current
    }

    fun enableSensitiveSnapshots(enabled: Boolean) {
        toggleSnapshotScreen(Screen.SENSITIVE, enabled)
    }

    // ============================================================================================
    // HOT STREAMS
    // ============================================================================================

    val evidenceConfig = context.dataStore.data.map { prefs ->
        EvidenceConfig(
            masterEnabled = prefs[Keys.EVIDENCE_MASTER] ?: false,
            saveOffers = prefs[Keys.EVIDENCE_OFFERS] ?: true,
            saveDeliverySummaries = prefs[Keys.EVIDENCE_DELIVERY] ?: true,
            saveDashSummaries = prefs[Keys.EVIDENCE_DASH] ?: true
        )
    }.stateIn(scope, SharingStarted.Eagerly, EvidenceConfig())

    /**
     * Holds the current Log Level Priority (e.g., Log.INFO or Log.DEBUG).
     */
// Default to DEBUG level for you, but INFO (or WARN) for users.
    val minLogLevel = context.dataStore.data.map { prefs ->
        // Logic: "Use the saved setting. If null, use the Build Type default."
        prefs[Keys.LOG_LEVEL] ?: if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        // Initial value while loading from disk
        if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO
    )

    // ============================================================================================
    // STANDARD STREAMS (Pass-through)
    // ============================================================================================

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
            } catch (_: Exception) {
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

    suspend fun setLogLevel(priority: Int) {
        context.dataStore.edit { it[Keys.LOG_LEVEL] = priority }
    }

    suspend fun setEvidenceMaster(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.EVIDENCE_MASTER] = enabled
            if (enabled) {
                if (prefs[Keys.EVIDENCE_OFFERS] == null) prefs[Keys.EVIDENCE_OFFERS] = true
                if (prefs[Keys.EVIDENCE_DELIVERY] == null) prefs[Keys.EVIDENCE_DELIVERY] = true
            }
        }
    }

    suspend fun updateRules(newRules: List<ScoringRule>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.RULE_LIST_JSON] = Json.encodeToString(newRules)
        }
    }

    suspend fun setProtectStatsMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.PROTECT_STATS_MODE] = enabled }
    }

    suspend fun setAllowShopping(allowed: Boolean) {
        context.dataStore.edit { it[Keys.ALLOW_SHOPPING] = allowed }
    }

    suspend fun updateEvidenceConfig(offers: Boolean, delivery: Boolean, dash: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.EVIDENCE_OFFERS] = offers
            prefs[Keys.EVIDENCE_DELIVERY] = delivery
            prefs[Keys.EVIDENCE_DASH] = dash
        }
    }

    suspend fun setMasterAutomation(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_MASTER] = enabled }
    }

    suspend fun updateAutomation(autoAccept: Boolean, minPay: Double, autoDecline: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.AUTO_ACCEPT] = autoAccept
            prefs[Keys.AUTO_ACCEPT_MIN_PAY] = minPay
            prefs[Keys.AUTO_DECLINE] = autoDecline
        }
    }

    suspend fun setFirstRunComplete() {
        context.dataStore.edit { it[Keys.IS_FIRST_RUN] = false }
    }

    suspend fun setProMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.IS_PRO_MODE] = enabled }
    }

    suspend fun setTheme(theme: String) {
        context.dataStore.edit { it[Keys.APP_THEME] = theme }
    }

    suspend fun saveSimulationState(pay: Double, dist: Double) {
        context.dataStore.edit { it[Keys.SIM_PAY] = pay; it[Keys.SIM_DIST] = dist }
    }

    suspend fun resetDefaults() {
        context.dataStore.edit { it.clear() }
    }
}