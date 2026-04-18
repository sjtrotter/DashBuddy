package cloud.trotter.dashbuddy.ui.formatters

import android.graphics.Color
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

/**
 * For System Overlays, Notifications, and traditional Views (Effect Handlers)
 */
fun OfferEvaluation.toSpannableString(): SpannableString {
    val builder = SpannableStringBuilder()

    val color = when (this.action) {
        OfferAction.ACCEPT -> Color.GREEN
        OfferAction.DECLINE -> Color.RED
        OfferAction.MANUAL_REVIEW -> Color.YELLOW
        else -> Color.YELLOW
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
fun OfferEvaluation.toAnnotatedString(): AnnotatedString {
    return buildAnnotatedString {
        val composeColor = when (this@toAnnotatedString.action) {
            OfferAction.ACCEPT -> androidx.compose.ui.graphics.Color.Green
            OfferAction.DECLINE -> androidx.compose.ui.graphics.Color.Red
            OfferAction.MANUAL_REVIEW -> androidx.compose.ui.graphics.Color.Yellow
            else -> androidx.compose.ui.graphics.Color.Yellow
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