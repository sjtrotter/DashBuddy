package cloud.trotter.dashbuddy.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cloud.trotter.dashbuddy.model.config.EvidenceConfig
import cloud.trotter.dashbuddy.model.config.MetricType
import cloud.trotter.dashbuddy.model.config.OfferAutomationConfig
import cloud.trotter.dashbuddy.model.config.ScoringRule
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "dashbuddy_settings_v3")

@Singleton // <--- 1. Scope to App Lifecycle
class SettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private object Keys {
        // --- 1. Gates & General (KEEP) ---
        val IS_FIRST_RUN = booleanPreferencesKey("is_first_run")
        val IS_PRO_MODE = booleanPreferencesKey("is_pro_mode")
        val APP_THEME = stringPreferencesKey("app_theme")

        // --- 2. Evidence Locker (KEEP) ---
        val EVIDENCE_MASTER = booleanPreferencesKey("evidence_master_enabled")
        val EVIDENCE_OFFERS = booleanPreferencesKey("evidence_save_offers")
        val EVIDENCE_DELIVERY = booleanPreferencesKey("evidence_save_delivery_summary")
        val EVIDENCE_DASH = booleanPreferencesKey("evidence_save_dash_summary")

        // --- 3. Automation (KEEP) ---
        val AUTO_MASTER = booleanPreferencesKey("auto_master_enabled")
        val AUTO_ACCEPT = booleanPreferencesKey("auto_accept_enabled")
        val AUTO_ACCEPT_MIN_PAY = doublePreferencesKey("auto_accept_min_pay")
        val AUTO_DECLINE = booleanPreferencesKey("auto_decline_enabled")

        // --- 4. Simulation Memory (KEEP) ---
        val SIM_PAY = doublePreferencesKey("sim_test_pay")
        val SIM_DIST = doublePreferencesKey("sim_test_dist")

        // --- 5. NEW RULE ENGINE (REPLACES Old Weights/Baselines) ---
        // This single JSON blob replaces MAX_PAY, TARGET_HOURLY, W_PAY, W_DIST, etc.
        val RULE_LIST_JSON = stringPreferencesKey("rule_list_config_v1")
        val PROTECT_STATS_MODE = booleanPreferencesKey("protect_stats_mode")

        // We keep this specific toggle separate because it's a binary "Gate", not a math rule
        val ALLOW_SHOPPING = booleanPreferencesKey("allow_shopping")
    }

    // --- DEFAULTS ---
    // The starting "Rack and Stack" for a new user
    private val defaultRules = listOf(
        ScoringRule.MetricRule("pay", true, MetricType.PAYOUT, 7.0f),
        ScoringRule.MetricRule("dpm", true, MetricType.DOLLAR_PER_MILE, 1.5f),
        ScoringRule.MetricRule("dist", true, MetricType.MAX_DISTANCE, 10.0f),
        ScoringRule.MetricRule("hr", true, MetricType.ACTIVE_HOURLY, 22.0f),
        // "Bad" things start disabled or at bottom
        ScoringRule.MetricRule("items", false, MetricType.ITEM_COUNT, 50.0f)
    )

    // ============================================================================================
    // STREAMS (READ)
    // ============================================================================================

    // 1. Evidence Config (Unchanged)
    val evidenceConfig: Flow<EvidenceConfig> = context.dataStore.data.map { prefs ->
        EvidenceConfig(
            masterEnabled = prefs[Keys.EVIDENCE_MASTER] ?: false,
            saveOffers = prefs[Keys.EVIDENCE_OFFERS] ?: true,
            saveDeliverySummaries = prefs[Keys.EVIDENCE_DELIVERY] ?: true,
            saveDashSummaries = prefs[Keys.EVIDENCE_DASH] ?: true
        )
    }

    // 2. Automation Config (Unchanged)
    val automationConfig: Flow<OfferAutomationConfig> = context.dataStore.data.map { prefs ->
        OfferAutomationConfig(
            masterAutoPilotEnabled = prefs[Keys.AUTO_MASTER] ?: false,
            autoAcceptEnabled = prefs[Keys.AUTO_ACCEPT] ?: false,
            autoAcceptMinPay = prefs[Keys.AUTO_ACCEPT_MIN_PAY] ?: 10.0,
            autoDeclineEnabled = prefs[Keys.AUTO_DECLINE] ?: false
        )
    }

    // 3. New Rule Engine Streams
    val scoringRules: Flow<List<ScoringRule>> = context.dataStore.data.map { prefs ->
        val json = prefs[Keys.RULE_LIST_JSON]
        if (json.isNullOrBlank()) {
            defaultRules
        } else {
            try {
                Json.decodeFromString(json)
            } catch (e: Exception) {
                defaultRules // Fallback if data corrupted
            }
        }
    }

    val protectStatsMode: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.PROTECT_STATS_MODE] ?: false
    }

    val allowShopping: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.ALLOW_SHOPPING] ?: true
    }

    // 4. App State Flags
    val isProMode: Flow<Boolean> = context.dataStore.data.map { it[Keys.IS_PRO_MODE] ?: false }
    val isFirstRun: Flow<Boolean> = context.dataStore.data.map { it[Keys.IS_FIRST_RUN] ?: true }
    val appTheme: Flow<String> = context.dataStore.data.map { it[Keys.APP_THEME] ?: "system" }

    // ============================================================================================
    // ACTIONS (WRITE)
    // ============================================================================================

    // --- Rule Engine Updates ---

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

    // --- Evidence Locker ---

    suspend fun setEvidenceMaster(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.EVIDENCE_MASTER] = enabled
            if (enabled) {
                // UX: Auto-enable all sub-features when master is turned on
                prefs[Keys.EVIDENCE_OFFERS] = true
                prefs[Keys.EVIDENCE_DELIVERY] = true
                prefs[Keys.EVIDENCE_DASH] = true
            }
        }
    }

    suspend fun updateEvidenceConfig(offers: Boolean, delivery: Boolean, dash: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.EVIDENCE_OFFERS] = offers
            prefs[Keys.EVIDENCE_DELIVERY] = delivery
            prefs[Keys.EVIDENCE_DASH] = dash
        }
    }

    // --- Automation ---

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

    // --- General & Gates ---

    suspend fun setFirstRunComplete() {
        context.dataStore.edit { it[Keys.IS_FIRST_RUN] = false }
    }

    suspend fun setProMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.IS_PRO_MODE] = enabled }
    }

    suspend fun setTheme(theme: String) {
        context.dataStore.edit { it[Keys.APP_THEME] = theme }
    }

    // --- Simulation Memory ---

    suspend fun saveSimulationState(pay: Double, dist: Double) {
        context.dataStore.edit {
            it[Keys.SIM_PAY] = pay
            it[Keys.SIM_DIST] = dist
        }
    }

    suspend fun resetDefaults() {
        context.dataStore.edit { it.clear() }
    }
}