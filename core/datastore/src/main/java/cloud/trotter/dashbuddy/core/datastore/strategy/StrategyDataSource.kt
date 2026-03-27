package cloud.trotter.dashbuddy.core.datastore.strategy

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cloud.trotter.dashbuddy.core.datastore.di.StrategyPreferences
import cloud.trotter.dashbuddy.core.datastore.strategy.dto.ScoringRuleDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StrategyDataSource @Inject constructor(
    @param:StrategyPreferences private val ds: DataStore<Preferences>
) {
    private object Keys {
        val EVIDENCE_MASTER = booleanPreferencesKey("evidence_master_enabled")
        val EVIDENCE_OFFERS = booleanPreferencesKey("evidence_save_offers")
        val EVIDENCE_DELIVERY = booleanPreferencesKey("evidence_save_delivery_summary")
        val EVIDENCE_DASH = booleanPreferencesKey("evidence_save_dash_summary")

        val AUTO_MASTER = booleanPreferencesKey("auto_master_enabled")

        val AUTO_ACCEPT = booleanPreferencesKey("auto_accept_enabled")
        val AUTO_ACCEPT_MIN_PAY = doublePreferencesKey("auto_accept_min_pay")
        val AUTO_ACCEPT_MIN_RATIO = doublePreferencesKey("auto_accept_min_ratio")

        val AUTO_DECLINE = booleanPreferencesKey("auto_decline_enabled")
        val AUTO_DECLINE_MAX_PAY = doublePreferencesKey("auto_decline_max_pay")
        val AUTO_DECLINE_MIN_RATIO = doublePreferencesKey("auto_decline_min_ratio")

        val RULE_LIST_JSON = stringPreferencesKey("rule_list_config_v1")
        val PROTECT_STATS_MODE = booleanPreferencesKey("protect_stats_mode")
        val ALLOW_SHOPPING = booleanPreferencesKey("allow_shopping")
    }

    val evidenceMaster: Flow<Boolean> = ds.data.map { it[Keys.EVIDENCE_MASTER] ?: false }
    val evidenceOffers: Flow<Boolean> = ds.data.map { it[Keys.EVIDENCE_OFFERS] ?: true }
    val evidenceDelivery: Flow<Boolean> = ds.data.map { it[Keys.EVIDENCE_DELIVERY] ?: true }
    val evidenceDash: Flow<Boolean> = ds.data.map { it[Keys.EVIDENCE_DASH] ?: true }

    val autoMaster: Flow<Boolean> = ds.data.map { it[Keys.AUTO_MASTER] ?: false }

    val autoAccept: Flow<Boolean> = ds.data.map { it[Keys.AUTO_ACCEPT] ?: false }
    val autoAcceptMinPay: Flow<Double> = ds.data.map { it[Keys.AUTO_ACCEPT_MIN_PAY] ?: 10.0 }
    val autoAcceptMinRatio: Flow<Double> = ds.data.map { it[Keys.AUTO_ACCEPT_MIN_RATIO] ?: 2.0 }

    val autoDecline: Flow<Boolean> = ds.data.map { it[Keys.AUTO_DECLINE] ?: false }
    val autoDeclineMaxPay: Flow<Double> = ds.data.map { it[Keys.AUTO_DECLINE_MAX_PAY] ?: 3.50 }
    val autoDeclineMinRatio: Flow<Double> = ds.data.map { it[Keys.AUTO_DECLINE_MIN_RATIO] ?: 0.50 }

    @OptIn(InternalSerializationApi::class)
    val scoringRules: Flow<List<ScoringRuleDto>> = ds.data.map { prefs ->
        val json = prefs[Keys.RULE_LIST_JSON]
        if (json.isNullOrBlank()) {
            emptyList()
        } else {
            try {
                Json.decodeFromString(json)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    val protectStatsMode: Flow<Boolean> = ds.data.map { it[Keys.PROTECT_STATS_MODE] ?: false }
    val allowShopping: Flow<Boolean> = ds.data.map { it[Keys.ALLOW_SHOPPING] ?: true }

    suspend fun setEvidenceMaster(enabled: Boolean) {
        ds.edit { prefs ->
            prefs[Keys.EVIDENCE_MASTER] = enabled
            if (enabled) {
                if (prefs[Keys.EVIDENCE_OFFERS] == null) prefs[Keys.EVIDENCE_OFFERS] = true
                if (prefs[Keys.EVIDENCE_DELIVERY] == null) prefs[Keys.EVIDENCE_DELIVERY] = true
            }
        }
    }

    suspend fun updateEvidenceConfig(offers: Boolean, delivery: Boolean, dash: Boolean) {
        ds.edit { prefs ->
            prefs[Keys.EVIDENCE_OFFERS] = offers
            prefs[Keys.EVIDENCE_DELIVERY] = delivery
            prefs[Keys.EVIDENCE_DASH] = dash
        }
    }

    @OptIn(InternalSerializationApi::class)
    suspend fun updateRules(rules: List<ScoringRuleDto>) {
        ds.edit { it[Keys.RULE_LIST_JSON] = Json.encodeToString(rules) }
    }

    suspend fun setProtectStatsMode(enabled: Boolean) =
        ds.edit { it[Keys.PROTECT_STATS_MODE] = enabled }

    suspend fun setAllowShopping(allowed: Boolean) = ds.edit { it[Keys.ALLOW_SHOPPING] = allowed }
    suspend fun setMasterAutomation(enabled: Boolean) = ds.edit { it[Keys.AUTO_MASTER] = enabled }

    suspend fun updateAutomation(
        autoAccept: Boolean, acceptMinPay: Double, acceptMinRatio: Double,
        autoDecline: Boolean, declineMaxPay: Double, declineMinRatio: Double
    ) {
        ds.edit { prefs ->
            prefs[Keys.AUTO_ACCEPT] = autoAccept
            prefs[Keys.AUTO_ACCEPT_MIN_PAY] = acceptMinPay
            prefs[Keys.AUTO_ACCEPT_MIN_RATIO] = acceptMinRatio
            prefs[Keys.AUTO_DECLINE] = autoDecline
            prefs[Keys.AUTO_DECLINE_MAX_PAY] = declineMaxPay
            prefs[Keys.AUTO_DECLINE_MIN_RATIO] = declineMinRatio
        }
    }

    suspend fun clear() = ds.edit { it.clear() }
}