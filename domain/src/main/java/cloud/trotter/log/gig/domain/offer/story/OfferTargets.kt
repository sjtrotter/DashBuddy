package cloud.trotter.log.gig.domain.offer.story

import cloud.trotter.log.gig.domain.evaluation.EvaluationConfig
import cloud.trotter.log.gig.domain.evaluation.MetricType
import cloud.trotter.log.gig.domain.evaluation.ScoringRule
import cloud.trotter.log.gig.domain.state.Platform

/** The dasher's rate targets, and the limits an offer's own figures are judged against. */
data class Targets(
    val perHour: Double?, val perMile: Double?, val perItem: Double?,
    val minNet: Double? = null, val maxMiles: Double? = null, val maxItems: Double? = null,
)

object OfferTargets {
    fun from(config: EvaluationConfig?, platform: Platform, isShop: Boolean): Targets {
        fun target(type: MetricType) = config?.rules?.filterIsInstance<ScoringRule.MetricRule>()
            ?.firstOrNull { it.isEnabled && it.metricType == type }?.targetValue?.toDouble()
        val hour = target(MetricType.ACTIVE_HOURLY)
        val pace = config?.forPlatform(platform)?.userEconomy?.effectiveShopItemsPerMinute
        return Targets(hour, target(MetricType.DOLLAR_PER_MILE),
            if (isShop && hour != null && pace != null && pace > 0) hour / (60 * pace) else null,
            target(MetricType.PAYOUT), target(MetricType.MAX_DISTANCE), target(MetricType.ITEM_COUNT))
    }
}
