package cloud.trotter.dashbuddy.ui.formatters

import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.core.designsystem.theme.DashColors
import cloud.trotter.dashbuddy.core.designsystem.theme.darkDashColors
import androidx.compose.ui.graphics.toArgb

/**
 * For System Overlays, Notifications, and traditional Views (Effect Handlers)
 */
fun OfferEvaluation.toSpannableString(): SpannableString {
    val builder = SpannableStringBuilder()

    val c = darkDashColors()
    val color = when (this.action) {
        OfferAction.ACCEPT -> c.good.toArgb()
        OfferAction.DECLINE -> c.bad.toArgb()
        OfferAction.MANUAL_REVIEW -> c.warn.toArgb()
        else -> c.warn.toArgb()
    }

    val start = builder.length
    builder.append(this.recommendationText)
    builder.setSpan(
        ForegroundColorSpan(color),
        start,
        builder.length,
        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
    )

    builder.append(" | Score: ${this.score.toInt()}")

    // TODO: Paste any of your other old Spannable logic here using `this.propertyName`

    return SpannableString.valueOf(builder)
}

/**
 * For Jetpack Compose UIs (FakeOfferCard)
 */
fun OfferEvaluation.toAnnotatedString(colors: DashColors = darkDashColors()): AnnotatedString {
    return buildAnnotatedString {
        val composeColor = when (this@toAnnotatedString.action) {
            OfferAction.ACCEPT -> colors.good
            OfferAction.DECLINE -> colors.bad
            OfferAction.MANUAL_REVIEW -> colors.warn
            else -> colors.warn
        }

        withStyle(style = SpanStyle(color = composeColor)) {
            append(this@toAnnotatedString.recommendationText)
        }

        append(" | Score: ${this@toAnnotatedString.score.toInt()}")

        if (this@toAnnotatedString.fuelCostEstimate > 0.0) {
            append(" | Net: $%.2f".format(this@toAnnotatedString.netPayAmount))
        }
    }
}

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