package cloud.trotter.log.gig.ui.main.analytics

import cloud.trotter.log.gig.domain.analytics.ANALYTICS_MONEY_EPSILON
import cloud.trotter.log.gig.domain.analytics.RecordsStatus
import cloud.trotter.log.gig.domain.analytics.SessionDetail
import cloud.trotter.log.gig.domain.analytics.ShiftEarnings
import cloud.trotter.log.gig.domain.analytics.checks.ShiftCheck
import cloud.trotter.log.gig.domain.analytics.checks.ShiftChecks
import cloud.trotter.log.gig.domain.analytics.checks.effectiveReport
import cloud.trotter.log.gig.domain.analytics.timeline.JobCard
import cloud.trotter.log.gig.domain.analytics.timeline.PassedRun
import cloud.trotter.log.gig.domain.analytics.timeline.TimelineItem
import cloud.trotter.log.gig.domain.evaluation.NetProfit

/**
 * The shift page's state: one shift's [detail] (its record and deliveries) above its folded [timeline].
 * [loading] is the pre-first-emission state; once loaded, a null [detail] means no such shift.
 */
data class SessionDetailUiState(
    val detail: SessionDetail? = null,
    val timeline: List<TimelineItem> = emptyList(),
    val loading: Boolean = true,
    /** A read failed: the page shows the failure pattern and Retry instead of the shift. */
    val failed: Boolean = false,
    /** Where the records stand; REBUILDING holds the figures, the timeline and the corrections back. */
    val records: RecordsStatus = RecordsStatus.UP_TO_DATE,
    /** What this shift needs checked ([ShiftChecks]); empty while it runs. */
    val checks: List<ShiftCheck> = emptyList(),
)

/** True when the page may show the shift's figures, timeline and corrections. */
internal fun SessionDetailUiState.figuresShown(): Boolean =
    !loading && !failed && records != RecordsStatus.REBUILDING

/**
 * The shift's money for the top of the page. [earned] is the shift's earnings ([ShiftEarnings.rested]) plus its
 * cash tips, null when nothing is known; [cash] is the cash inside it. [profit] is Σ the deliveries' recorded
 * profit plus pay the app reported beyond them plus cash, the same sum Recap uses; null when nothing is known.
 */
internal data class ShiftMoney(val earned: Double?, val cash: Double, val profit: Double?) {
    /** [profit] over [onlineMillis], null when either is unknown. */
    fun profitPerHour(onlineMillis: Long?): Double? {
        val p = profit ?: return null
        val ms = onlineMillis ?: return null
        return NetProfit.perHour(p, ms / 3_600_000.0)
    }

    companion object {
        fun of(detail: SessionDetail): ShiftMoney {
            val cash = detail.cashTips
            val base = ShiftEarnings.rested(detail.effectiveReport(), detail.recordedPay)
            val earned = if (base == null && cash <= ANALYTICS_MONEY_EPSILON) null else (base ?: 0.0) + cash
            val nets = detail.deliveries.mapNotNull { it.netProfit }
            val unattributed = detail.unattributedPay
            // No delivery net means no vehicle cost was ever taken off, so a profit would only be pay.
            val profit = if (nets.isEmpty()) null else nets.sum() + unattributed + cash
            return ShiftMoney(earned, cash, profit)
        }
    }
}

/** The shift's online time: ended, its span; running, up to [now]. */
internal fun onlineMillis(detail: SessionDetail, now: Long): Long =
    ((detail.session.endedAt ?: now) - detail.session.startedAt).coerceAtLeast(0L)

/**
 * Where a `row` link lands: the item to scroll to, the run to open first, and the job whose sheet opens.
 * A row is an item key (`offer:<seq>`, `job:<id>`, `run:<seq>`); an `offer:<seq>` also lands on the card that
 * holds that offer as an add-on, and on the run that holds that passed offer (whose thin row has the same key).
 */
internal data class RowLanding(val scrollKey: String, val expandRun: String?, val openJob: String?) {
    companion object {
        fun of(items: List<TimelineItem>, row: String): RowLanding? {
            val seq = row.removePrefix(OFFER_KEY_PREFIX).takeIf { row.startsWith(OFFER_KEY_PREFIX) }?.toLongOrNull()
            for (item in items) {
                when (item) {
                    is JobCard -> if (item.key == row || (seq != null && seq in item.offerEventSequenceIds) ||
                        (item.jobId != null && row == jobRowKey(item.jobId))
                    ) {
                        return RowLanding(item.key, null, item.key)
                    }
                    is PassedRun -> {
                        if (item.key == row) return RowLanding(item.key, null, null)
                        if (seq != null && item.offers.any { it.eventSequenceId == seq }) {
                            return RowLanding(passedRowKey(seq), item.key, null)
                        }
                    }
                }
            }
            return null
        }
    }
}

private const val OFFER_KEY_PREFIX = "offer:"

/** A passed offer's thin-row key; distinct from every card key, which names an accepted offer's sequence. */
internal fun passedRowKey(eventSequenceId: Long): String = offerRowKey(eventSequenceId)

/** The `row` link that lands on the offer [eventSequenceId] (its card, or the run that holds it). */
internal fun offerRowKey(eventSequenceId: Long): String = "$OFFER_KEY_PREFIX$eventSequenceId"

/** A link to a job's card by its job id, whatever the card is keyed by (Recap's "more accepts" check, #1515). */
internal fun jobRowKey(jobId: String): String = "job:$jobId"
