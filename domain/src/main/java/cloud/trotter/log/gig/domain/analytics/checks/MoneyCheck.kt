package cloud.trotter.log.gig.domain.analytics.checks

import cloud.trotter.log.gig.domain.analytics.ANALYTICS_MONEY_EPSILON
import cloud.trotter.log.gig.domain.analytics.ReportSource
import cloud.trotter.log.gig.domain.analytics.SessionDetail
import cloud.trotter.log.gig.domain.analytics.SessionReportRule
import cloud.trotter.log.gig.domain.analytics.timeline.PaySource

/** Where this shift's effective total came from; [ReportSource] owns the rule. */
fun SessionDetail.reportSource(): ReportSource = with(session) {
    ReportSource.of(machineReportedEarnings, endSource, reportOverrideMode, reportOverride, recordedPay)
}

/** This shift's effective total; [SessionReportRule] owns the rule. */
fun SessionDetail.effectiveReport(): Double? = with(session) {
    SessionReportRule.effectiveReported(machineReportedEarnings, endSource, reportOverrideMode, reportOverride, recordedPay)
}

/**
 * The money check: the shift's independent total against its delivery pay. Null unless the total is
 * [ReportSource.independent], so the fallback that equals delivery pay is never checked.
 */
data class MoneyCheck(
    val appTotal: Double,
    /** Cash-free delivery pay ([SessionDetail.deliveredPay]). */
    val deliveryPay: Double,
    /** True when [deliveryPay] includes an estimated delivery pay. */
    val deliveryPayEstimated: Boolean,
) {
    /** The higher side, or null when the two agree within half a cent. */
    val higher: Higher? = when {
        appTotal - deliveryPay > ANALYTICS_MONEY_EPSILON -> Higher.APP_TOTAL
        deliveryPay - appTotal > ANALYTICS_MONEY_EPSILON -> Higher.DELIVERY_PAY
        else -> null
    }

    val difference: Double get() = kotlin.math.abs(appTotal - deliveryPay)

    enum class Higher { APP_TOTAL, DELIVERY_PAY }

    companion object {
        fun of(detail: SessionDetail): MoneyCheck? {
            if (!detail.reportSource().independent) return null
            val total = detail.effectiveReport() ?: return null
            return MoneyCheck(
                appTotal = total,
                deliveryPay = detail.deliveredPay,
                deliveryPayEstimated = detail.deliveries.any { PaySource.of(it.payBasis, it.realizedPay, 1).estimated },
            )
        }
    }
}
