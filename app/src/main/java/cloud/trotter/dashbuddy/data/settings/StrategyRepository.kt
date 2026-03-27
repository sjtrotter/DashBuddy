package cloud.trotter.dashbuddy.data.settings

import cloud.trotter.dashbuddy.domain.config.EvidenceConfig
import cloud.trotter.dashbuddy.domain.config.MetricType
import cloud.trotter.dashbuddy.domain.config.OfferAutomationConfig
import cloud.trotter.dashbuddy.domain.config.ScoringRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StrategyRepository @Inject constructor(
    private val dataSource: StrategyDataSource
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val defaultRules = listOf(
        ScoringRule.MetricRule("pay", true, MetricType.PAYOUT, 7.0f),
        ScoringRule.MetricRule("dpm", true, MetricType.DOLLAR_PER_MILE, 1.5f),
        ScoringRule.MetricRule("dist", true, MetricType.MAX_DISTANCE, 10.0f),
        ScoringRule.MetricRule("hr", true, MetricType.ACTIVE_HOURLY, 22.0f),
        ScoringRule.MetricRule("items", false, MetricType.ITEM_COUNT, 50.0f)
    )

    // ============================================================================================
    // STREAMS
    // ============================================================================================
    val evidenceConfig = combine(
        dataSource.evidenceMaster, dataSource.evidenceOffers,
        dataSource.evidenceDelivery, dataSource.evidenceDash
    ) { master, offers, delivery, dash ->
        EvidenceConfig(master, offers, delivery, dash)
    }.stateIn(scope, SharingStarted.Eagerly, EvidenceConfig())

    // Using the List<> combine overload to support more than 5 parameters!
// 1. Explicitly type the list as <Flow<Any>>
    val automationConfig: Flow<OfferAutomationConfig> = combine(
        listOf<Flow<Any>>(
            dataSource.autoMaster,
            dataSource.autoAccept,
            dataSource.autoAcceptMinPay,
            dataSource.autoAcceptMinRatio,
            dataSource.autoDecline,
            dataSource.autoDeclineMaxPay,
            dataSource.autoDeclineMinRatio
        )
    ) { values -> // 'values' is now safely inferred as Array<Any>
        OfferAutomationConfig(
            masterAutoPilotEnabled = values[0] as Boolean,
            autoAcceptEnabled = values[1] as Boolean,
            autoAcceptMinPay = values[2] as Double,
            autoAcceptMinRatio = values[3] as Double,
            autoDeclineEnabled = values[4] as Boolean,
            autoDeclineMaxPay = values[5] as Double,
            autoDeclineMinRatio = values[6] as Double
        )
    }

    val scoringRules: Flow<List<ScoringRule>> = dataSource.ruleListJson.map { json ->
        if (json.isNullOrBlank()) defaultRules else {
            try {
                Json.decodeFromString(json)
            } catch (e: Exception) {
                defaultRules
            }
        }
    }

    val protectStatsMode = dataSource.protectStatsMode
    val allowShopping = dataSource.allowShopping

    // ============================================================================================
    // WRITE ACTIONS
    // ============================================================================================
    suspend fun setEvidenceMaster(enabled: Boolean) {
        dataSource.update { prefs ->
            prefs[StrategyDataSource.Keys.EVIDENCE_MASTER] = enabled
            if (enabled) {
                if (prefs[StrategyDataSource.Keys.EVIDENCE_OFFERS] == null) prefs[StrategyDataSource.Keys.EVIDENCE_OFFERS] =
                    true
                if (prefs[StrategyDataSource.Keys.EVIDENCE_DELIVERY] == null) prefs[StrategyDataSource.Keys.EVIDENCE_DELIVERY] =
                    true
            }
        }
    }

    suspend fun updateEvidenceConfig(offers: Boolean, delivery: Boolean, dash: Boolean) {
        dataSource.update { prefs ->
            prefs[StrategyDataSource.Keys.EVIDENCE_OFFERS] = offers
            prefs[StrategyDataSource.Keys.EVIDENCE_DELIVERY] = delivery
            prefs[StrategyDataSource.Keys.EVIDENCE_DASH] = dash
        }
    }

    suspend fun updateRules(newRules: List<ScoringRule>) {
        dataSource.update {
            it[StrategyDataSource.Keys.RULE_LIST_JSON] = Json.encodeToString(newRules)
        }
    }

    suspend fun setProtectStatsMode(enabled: Boolean) =
        dataSource.update { it[StrategyDataSource.Keys.PROTECT_STATS_MODE] = enabled }

    suspend fun setAllowShopping(allowed: Boolean) =
        dataSource.update { it[StrategyDataSource.Keys.ALLOW_SHOPPING] = allowed }

    suspend fun setMasterAutomation(enabled: Boolean) =
        dataSource.update { it[StrategyDataSource.Keys.AUTO_MASTER] = enabled }

    suspend fun updateAutomation(
        autoAccept: Boolean,
        acceptMinPay: Double,
        acceptMinRatio: Double,
        autoDecline: Boolean,
        declineMaxPay: Double,
        declineMinRatio: Double
    ) {
        dataSource.update { prefs ->
            prefs[StrategyDataSource.Keys.AUTO_ACCEPT] = autoAccept
            prefs[StrategyDataSource.Keys.AUTO_ACCEPT_MIN_PAY] = acceptMinPay
            prefs[StrategyDataSource.Keys.AUTO_ACCEPT_MIN_RATIO] = acceptMinRatio

            prefs[StrategyDataSource.Keys.AUTO_DECLINE] = autoDecline
            prefs[StrategyDataSource.Keys.AUTO_DECLINE_MAX_PAY] = declineMaxPay
            prefs[StrategyDataSource.Keys.AUTO_DECLINE_MIN_RATIO] = declineMinRatio
        }
    }

    suspend fun clearPreferences() {
        Timber.w("Clearing Strategy Preferences")
        dataSource.update { it.clear() }
    }
}