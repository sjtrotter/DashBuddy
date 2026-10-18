package cloud.trotter.dashbuddy.feature.bubble.hud

import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.dashbuddy.domain.format.Formats

/** Unknown money stays unknown; a parsed zero remains a real zero. */
fun sessionEarningsText(earnings: Double?): String = moneyOrEmpty(earnings)
fun moneyOrEmpty(amount: Double?): String = amount?.let { Formats.money(it) } ?: EMPTY_VALUE
