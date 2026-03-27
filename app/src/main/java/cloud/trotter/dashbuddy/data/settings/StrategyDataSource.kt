package cloud.trotter.dashbuddy.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.strategyDataStore by preferencesDataStore(name = "strategy_prefs")

@Singleton
class StrategyDataSource @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val ds = context.strategyDataStore

    object Keys {
        val EVIDENCE_MASTER = booleanPreferencesKey("evidence_master_enabled")
        val EVIDENCE_OFFERS = booleanPreferencesKey("evidence_save_offers")
        val EVIDENCE_DELIVERY = booleanPreferencesKey("evidence_save_delivery_summary")
        val EVIDENCE_DASH = booleanPreferencesKey("evidence_save_dash_summary")

        val AUTO_MASTER = booleanPreferencesKey("auto_master_enabled")

        // Auto-Accept
        val AUTO_ACCEPT = booleanPreferencesKey("auto_accept_enabled")
        val AUTO_ACCEPT_MIN_PAY = doublePreferencesKey("auto_accept_min_pay")
        val AUTO_ACCEPT_MIN_RATIO = doublePreferencesKey("auto_accept_min_ratio")

        // Auto-Decline
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

    val ruleListJson: Flow<String?> = ds.data.map { it[Keys.RULE_LIST_JSON] }
    val protectStatsMode: Flow<Boolean> = ds.data.map { it[Keys.PROTECT_STATS_MODE] ?: false }
    val allowShopping: Flow<Boolean> = ds.data.map { it[Keys.ALLOW_SHOPPING] ?: true }

    suspend fun update(transform: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        ds.edit { transform(it) }
    }
}