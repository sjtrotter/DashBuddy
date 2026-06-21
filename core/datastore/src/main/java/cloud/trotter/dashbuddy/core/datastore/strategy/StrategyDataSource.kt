package cloud.trotter.dashbuddy.core.datastore.strategy

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import cloud.trotter.dashbuddy.core.datastore.di.StrategyPreferences
import cloud.trotter.dashbuddy.core.datastore.strategy.dto.ScoringRuleDto
import cloud.trotter.dashbuddy.domain.evaluation.LearnedShopRate
import cloud.trotter.dashbuddy.domain.evaluation.ShopRate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber
import cloud.trotter.dashbuddy.domain.config.EvidenceConfig
import cloud.trotter.dashbuddy.domain.config.OfferAutomationConfig

@Singleton
class StrategyDataSource @Inject constructor(
    @param:StrategyPreferences private val ds: DataStore<Preferences>
) {
    private object Keys {
        val EVIDENCE_MASTER = booleanPreferencesKey("evidence_master_enabled")
        val EVIDENCE_OFFERS = booleanPreferencesKey("evidence_save_offers")
        val EVIDENCE_DELIVERY = booleanPreferencesKey("evidence_save_delivery_summary")
        val EVIDENCE_SESSION = booleanPreferencesKey("evidence_save_dash_summary")

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

        // #556: learned overall shopping pace (running mean items/min + sample count). Not a
        // user-set economy field — a measured value, kept here so a vehicle-class reseed never
        // touches it; folded into the evaluator's UserEconomy by StrategyRepository.
        val LEARNED_SHOP_RATE = doublePreferencesKey("learned_shop_items_per_min")
        val SHOP_RATE_SAMPLES = intPreferencesKey("shop_rate_sample_count")
    }

    val evidenceMaster: Flow<Boolean> = ds.data.map { it[Keys.EVIDENCE_MASTER] ?: EvidenceConfig.DEFAULT_MASTER }
    val evidenceOffers: Flow<Boolean> = ds.data.map { it[Keys.EVIDENCE_OFFERS] ?: EvidenceConfig.DEFAULT_SAVE_OFFERS }
    val evidenceDelivery: Flow<Boolean> = ds.data.map { it[Keys.EVIDENCE_DELIVERY] ?: EvidenceConfig.DEFAULT_SAVE_DELIVERIES }
    val evidenceSession: Flow<Boolean> = ds.data.map { it[Keys.EVIDENCE_SESSION] ?: EvidenceConfig.DEFAULT_SAVE_SESSIONS }

    val autoMaster: Flow<Boolean> = ds.data.map { it[Keys.AUTO_MASTER] ?: OfferAutomationConfig.DEFAULT_MASTER }

    val autoAccept: Flow<Boolean> = ds.data.map { it[Keys.AUTO_ACCEPT] ?: OfferAutomationConfig.DEFAULT_AUTO_ACCEPT }
    val autoAcceptMinPay: Flow<Double> = ds.data.map { it[Keys.AUTO_ACCEPT_MIN_PAY] ?: OfferAutomationConfig.DEFAULT_ACCEPT_MIN_PAY }
    val autoAcceptMinRatio: Flow<Double> = ds.data.map { it[Keys.AUTO_ACCEPT_MIN_RATIO] ?: OfferAutomationConfig.DEFAULT_ACCEPT_MIN_RATIO }

    val autoDecline: Flow<Boolean> = ds.data.map { it[Keys.AUTO_DECLINE] ?: OfferAutomationConfig.DEFAULT_AUTO_DECLINE }
    val autoDeclineMaxPay: Flow<Double> = ds.data.map { it[Keys.AUTO_DECLINE_MAX_PAY] ?: OfferAutomationConfig.DEFAULT_DECLINE_MAX_PAY }
    val autoDeclineMinRatio: Flow<Double> = ds.data.map { it[Keys.AUTO_DECLINE_MIN_RATIO] ?: OfferAutomationConfig.DEFAULT_DECLINE_MIN_RATIO }

    @OptIn(InternalSerializationApi::class)
    val scoringRules: Flow<List<ScoringRuleDto>> = ds.data.map { prefs ->
        val json = prefs[Keys.RULE_LIST_JSON]
        if (json.isNullOrBlank()) {
            emptyList()
        } else {
            try {
                Json.decodeFromString(json)
            } catch (e: Exception) {
                Timber.w(e, "Corrupt scoring-rules JSON — falling back to defaults: %s", json.take(120))
                emptyList()
            }
        }
    }

    val protectStatsMode: Flow<Boolean> = ds.data.map { it[Keys.PROTECT_STATS_MODE] ?: false }
    val allowShopping: Flow<Boolean> = ds.data.map { it[Keys.ALLOW_SHOPPING] ?: true }

    /** #556: the learned overall shopping pace + how many shops back it (null pace until first sample). */
    val learnedShopRate: Flow<LearnedShopRate> = ds.data.map {
        LearnedShopRate(it[Keys.LEARNED_SHOP_RATE], it[Keys.SHOP_RATE_SAMPLES] ?: 0)
    }

    /**
     * #556: fold one measured shop ([items] over [minutes] in-store) into the running mean, atomically
     * (read-modify-write inside one edit, so back-to-back stacked shops can't race). Out-of-band
     * samples (below the [ShopRate] floors) are no-ops.
     */
    suspend fun recordShopRate(items: Int, minutes: Double) {
        ds.edit { p ->
            val (avg, n) = ShopRate.fold(p[Keys.LEARNED_SHOP_RATE], p[Keys.SHOP_RATE_SAMPLES] ?: 0, items, minutes)
            if (avg != null) {
                p[Keys.LEARNED_SHOP_RATE] = avg
                p[Keys.SHOP_RATE_SAMPLES] = n
            }
        }
    }

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
            prefs[Keys.EVIDENCE_SESSION] = dash
        }
    }

    @OptIn(InternalSerializationApi::class)
    suspend fun updateRules(rules: List<ScoringRuleDto>) {
        ds.edit { it[Keys.RULE_LIST_JSON] = Json.encodeToString(rules) }
    }

    suspend fun setProtectStatsMode(enabled: Boolean) {
        ds.edit { it[Keys.PROTECT_STATS_MODE] = enabled }
    }

    suspend fun setAllowShopping(allowed: Boolean) {
        ds.edit { it[Keys.ALLOW_SHOPPING] = allowed }
    }

    suspend fun setMasterAutomation(enabled: Boolean) {
        ds.edit { it[Keys.AUTO_MASTER] = enabled }
    }

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

    suspend fun clear() {
        ds.edit { it.clear() }
    }
}