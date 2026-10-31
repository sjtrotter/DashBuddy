package cloud.trotter.log.gig.feature.bubble.hud

import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.domain.format.Formats

/** An amount over an online span, per hour (#1443: Earned/hr); null without a positive span or an amount. */
fun perHour(amount: Double?, spanMs: Long?): Double? =
    if (spanMs != null && spanMs > 0) amount?.div(spanMs / 3_600_000.0) else null

/** Unknown money stays unknown; a parsed zero remains a real zero. */
fun moneyOrEmpty(amount: Double?): String = amount?.let { Formats.money(it) } ?: EMPTY_VALUE
