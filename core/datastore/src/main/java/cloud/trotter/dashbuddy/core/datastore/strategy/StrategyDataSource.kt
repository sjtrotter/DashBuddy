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
import cloud.trotter.dashbuddy.domain.state.Platform
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

        val QUICK_DECLINES = booleanPreferencesKey("quick_declines_enabled") // #577

        val RULE_LIST_JSON = stringPreferencesKey("rule_list_config_v1")
        val PROTECT_STATS_MODE = booleanPreferencesKey("protect_stats_mode")
        val ALLOW_SHOPPING = booleanPreferencesKey("allow_shopping")

    }

    // #588: learned shopping pace, now keyed **per platform** (was a single global pair — a
    // DoorDash-learned pace could price an Instacart/Uber shop and pollute the shared mean). Not a
    // user-set economy field — a measured value, kept in the strategy store so a vehicle-class reseed
    // never touches it; folded into the evaluator's UserEconomy by StrategyRepository. The keys derive
    // from the registry [Platform.wire], so a new platform needs zero datastore change (P8).
    private fun shopRateKey(platform: Platform) =
        doublePreferencesKey("learned_shop_items_per_min:${platform.wire}")
    private fun shopSamplesKey(platform: Platform) =
        intPreferencesKey("shop_rate_sample_count:${platform.wire}")

    // #588 FIX 3b: the pre-#588 GLOBAL un-suffixed keys (one shared mean before shop-rate learning
    // went per-platform). Deliberately DROPPED, not migrated (#588 design decision — an old global
    // mean isn't reliably any one platform's pace, so it's discarded rather than guessed onto one).
    // Kept here only so [recordShopRate] can tidy them off disk; no reader ever consults them again.
    private val legacyGlobalShopRateKey = doublePreferencesKey("learned_shop_items_per_min")
    private val legacyGlobalShopSamplesKey = intPreferencesKey("shop_rate_sample_count")

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
    val quickDeclines: Flow<Boolean> = ds.data.map { it[Keys.QUICK_DECLINES] ?: OfferAutomationConfig.DEFAULT_QUICK_DECLINES } // #577

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

    /**
     * #588: the learned shopping pace per platform (running mean items/min + sample count). A platform
     * with no recorded shops is omitted from the map — the eval seam ([EvaluationConfig.forPlatform])
     * owns the per-platform seed fallback, so a missing entry is the seed, never another platform's
     * rate.
     */
    val learnedShopRates: Flow<Map<Platform, LearnedShopRate>> = ds.data.map { prefs ->
        Platform.entries.mapNotNull { platform ->
            val avg = prefs[shopRateKey(platform)]
            val n = prefs[shopSamplesKey(platform)] ?: 0
            if (avg == null && n == 0) null else platform to LearnedShopRate(avg, n)
        }.toMap()
    }

    /**
     * #556/#588: fold one measured shop ([items] over [minutes] in-store) into [platform]'s running
     * mean, atomically (read-modify-write inside one edit, so back-to-back stacked shops can't race).
     * Out-of-band samples (below the [ShopRate] floors) are no-ops. Only [platform]'s key pair moves.
     *
     * Returns the post-fold [LearnedShopRate] (#731 desk-observability) — the running mean lives
     * ONLY in this datastore, which a post-dash data pull never includes, so surfacing it lets the
     * caller log the relearn trajectory instead of it being invisible to desk analysis.
     */
    suspend fun recordShopRate(platform: Platform, items: Int, minutes: Double): LearnedShopRate {
        val prefs = ds.edit { p ->
            val (avg, n) = ShopRate.fold(p[shopRateKey(platform)], p[shopSamplesKey(platform)] ?: 0, items, minutes)
            if (avg != null) {
                p[shopRateKey(platform)] = avg
                p[shopSamplesKey(platform)] = n
            }
            // #588 FIX 3b: one-time tidy — drop the old un-suffixed global keys now that shop-rate
            // learning is per-platform. Idempotent (a no-op once they're gone); rides the next fold
            // rather than a dedicated migration step since a fold happens on every real shop anyway.
            p.remove(legacyGlobalShopRateKey)
            p.remove(legacyGlobalShopSamplesKey)
        }
        // edit() returns the post-transform snapshot, so this read-back cannot desync from what was
        // persisted: an in-band fold just wrote these keys; an out-of-band fold is a no-op and the
        // untouched keys ARE the prior pair ShopRate.fold would have returned.
        return LearnedShopRate(prefs[shopRateKey(platform)], prefs[shopSamplesKey(platform)] ?: 0)
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

    suspend fun setQuickDeclines(enabled: Boolean) { // #577
        ds.edit { it[Keys.QUICK_DECLINES] = enabled }
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