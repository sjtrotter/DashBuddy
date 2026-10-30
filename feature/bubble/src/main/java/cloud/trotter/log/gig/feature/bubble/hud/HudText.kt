package cloud.trotter.log.gig.feature.bubble.hud

import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.domain.format.Formats

fun profitPerHour(profit: Double?, spanMs: Long?): Double? =
    if (spanMs != null && spanMs > 0) profit?.div(spanMs / 3_600_000.0) else null

/** Unknown money stays unknown; a parsed zero remains a real zero. */
fun sessionEarningsText(earnings: Double?): String = moneyOrEmpty(earnings)
fun moneyOrEmpty(amount: Double?): String = amount?.let { Formats.money(it) } ?: EMPTY_VALUE
