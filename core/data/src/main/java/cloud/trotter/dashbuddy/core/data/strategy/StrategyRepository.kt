package cloud.trotter.dashbuddy.core.data.strategy

import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.datastore.strategy.StrategyDataSource
import cloud.trotter.dashbuddy.core.datastore.strategy.dto.ScoringRuleDto
import cloud.trotter.dashbuddy.domain.config.EvidenceConfig
import cloud.trotter.dashbuddy.domain.config.OfferAutomationConfig
import cloud.trotter.dashbuddy.domain.evaluation.EvaluationConfig
import cloud.trotter.dashbuddy.domain.evaluation.MerchantAction
import cloud.trotter.dashbuddy.domain.evaluation.MetricType
import cloud.trotter.dashbuddy.domain.evaluation.ScoringRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.InternalSerializationApi
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import cloud.trotter.dashbuddy.domain.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher

@Singleton
class StrategyRepository @Inject constructor(
    private val dataSource: StrategyDataSource,
    private val appPreferencesRepository: AppPreferencesRepository,
    @param:IoDispatcher ioDispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())

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

    // Nested TYPED combines (#364): the old positional Flow<Any> + index casts
    // turned any reorder into a runtime ClassCastException.
    private data class AcceptHalf(val enabled: Boolean, val minPay: Double, val minRatio: Double)
    private data class DeclineHalf(val enabled: Boolean, val maxPay: Double, val minRatio: Double)

    val automationConfig: Flow<OfferAutomationConfig> = combine(
        dataSource.autoMaster,
        combine(
            dataSource.autoAccept, dataSource.autoAcceptMinPay, dataSource.autoAcceptMinRatio,
        ) { enabled, minPay, minRatio -> AcceptHalf(enabled, minPay, minRatio) },
        combine(
            dataSource.autoDecline, dataSource.autoDeclineMaxPay, dataSource.autoDeclineMinRatio,
        ) { enabled, maxPay, minRatio -> DeclineHalf(enabled, maxPay, minRatio) },
    ) { master, accept, decline ->
        OfferAutomationConfig(
            masterAutoPilotEnabled = master,
            autoAcceptEnabled = accept.enabled,
            autoAcceptMinPay = accept.minPay,
            autoAcceptMinRatio = accept.minRatio,
            autoDeclineEnabled = decline.enabled,
            autoDeclineMaxPay = decline.maxPay,
            autoDeclineMinRatio = decline.minRatio,
        )
    }

    // Maps the incoming DTOs to pure Domain Models
    @OptIn(InternalSerializationApi::class)
    val scoringRules: Flow<List<ScoringRule>> = dataSource.scoringRules.map { dtoList ->
        if (dtoList.isEmpty()) return@map defaultRules

        dtoList.map { dto ->
            when (dto) {
                is ScoringRuleDto.MetricRuleDto -> ScoringRule.MetricRule(
                    id = dto.id,
                    isEnabled = dto.isEnabled,
                    metricType = MetricType.valueOf(dto.metricType),
                    targetValue = dto.targetValue,
                    autoDeclineOnFail = dto.autoDeclineOnFail
                )

                is ScoringRuleDto.MerchantRuleDto -> ScoringRule.MerchantRule(
                    id = dto.id,
                    isEnabled = dto.isEnabled,
                    storeName = dto.storeName,
                    action = runCatching { MerchantAction.valueOf(dto.action) }.getOrElse {
                        // Migrate legacy enum names (BAN→BLOCK, BOOST/PENALIZE→SCORE_MODIFIER)
                        when (dto.action) {
                            "BAN" -> MerchantAction.BLOCK
                            "BOOST", "PENALIZE" -> MerchantAction.SCORE_MODIFIER
                            else -> MerchantAction.MANUAL_REVIEW
                        }
                    }
                )
            }
        }
    }

    val protectStatsMode = dataSource.protectStatsMode
    val allowShopping = dataSource.allowShopping

    // ============================================================================================
    // WRITE ACTIONS
    // ============================================================================================
    suspend fun setEvidenceMaster(enabled: Boolean) = dataSource.setEvidenceMaster(enabled)
    suspend fun updateEvidenceConfig(offers: Boolean, delivery: Boolean, dash: Boolean) =
        dataSource.updateEvidenceConfig(offers, delivery, dash)

    suspend fun setProtectStatsMode(enabled: Boolean) = dataSource.setProtectStatsMode(enabled)
    suspend fun setAllowShopping(allowed: Boolean) = dataSource.setAllowShopping(allowed)
    suspend fun setMasterAutomation(enabled: Boolean) = dataSource.setMasterAutomation(enabled)

    // Maps Domain Models to DTOs before saving
    @OptIn(InternalSerializationApi::class)
    suspend fun updateRules(newRules: List<ScoringRule>) {
        val dtos = newRules.map { rule ->
            when (rule) {
                is ScoringRule.MetricRule -> ScoringRuleDto.MetricRuleDto(
                    id = rule.id,
                    isEnabled = rule.isEnabled,
                    metricType = rule.metricType.name,
                    targetValue = rule.targetValue,
                    autoDeclineOnFail = rule.autoDeclineOnFail
                )

                is ScoringRule.MerchantRule -> ScoringRuleDto.MerchantRuleDto(
                    id = rule.id,
                    isEnabled = rule.isEnabled,
                    storeName = rule.storeName,
                    action = rule.action.name
                )
            }
        }
        dataSource.updateRules(dtos)
    }

    suspend fun updateAutomation(
        autoAccept: Boolean, acceptMinPay: Double, acceptMinRatio: Double,
        autoDecline: Boolean, declineMaxPay: Double, declineMinRatio: Double
    ) = dataSource.updateAutomation(
        autoAccept,
        acceptMinPay,
        acceptMinRatio,
        autoDecline,
        declineMaxPay,
        declineMinRatio
    )

    val evaluationConfigFlow: Flow<EvaluationConfig> = combine(
        scoringRules,
        protectStatsMode,
        allowShopping,
        appPreferencesRepository.userEconomy,
    ) { rules, protect, shop, economy ->
        EvaluationConfig(
            protectStatsMode = protect,
            rules = rules,
            allowShopping = shop,
            userEconomy = economy,
        )
    }


    suspend fun clearPreferences() {
        Timber.w("Clearing Strategy Preferences")
        dataSource.clear()
    }
}