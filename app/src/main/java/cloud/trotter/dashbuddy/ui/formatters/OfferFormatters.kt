package cloud.trotter.dashbuddy.ui.formatters

import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation

/**
 * Condensed offer summary for the heads-up notification — the top of the offer card as plain
 * text (verdict + net $/hr, then net pay · miles · $/mi · score · store).
 */
fun OfferEvaluation.toNotificationSummary(): String {
    val verdict = when (action) {
        OfferAction.ACCEPT -> "ACCEPT"
        OfferAction.DECLINE -> "DECLINE"
        OfferAction.MANUAL_REVIEW -> "REVIEW"
        else -> "OFFER"
    }
    return "%s · $%.0f/hr net\nNet $%.2f · %.1f mi · $%.2f/mi · Score %d · %s".format(
        verdict, dollarsPerHour, netPayAmount, distanceMiles, dollarsPerMile, score.toInt(), merchantName,
    )
}
