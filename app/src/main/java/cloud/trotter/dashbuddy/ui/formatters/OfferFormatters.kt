package cloud.trotter.dashbuddy.ui.formatters

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import androidx.compose.ui.graphics.toArgb
import cloud.trotter.dashbuddy.core.designsystem.theme.darkDashColors
import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation

/**
 * Formatted offer summary for the heads-up notification. Built with **Android** text spans — the
 * kind notifications actually honor (Compose `AnnotatedString` spans don't render in a
 * notification). The verdict word is bold, larger, and colored good / warn / bad; the headline
 * `$/hr` is bold. Two lines:
 *
 * ```
 * ACCEPT · $22/hr net          <- verdict colored + larger, whole line bold
 * Net $22.48 · 12.9 mi · $1.74/mi · Score 74 · H-E-B
 * ```
 *
 * Note: bold + size are reliable across devices; foreground color in a MessagingStyle notification
 * is device/version dependent (the system may re-theme it), so the line still reads cleanly without
 * it. `toString()` yields the plain text used for chat storage.
 */
fun OfferEvaluation.toNotificationSummary(): CharSequence {
    val colors = darkDashColors()
    val verdict = when (action) {
        OfferAction.ACCEPT -> "ACCEPT"
        OfferAction.DECLINE -> "DECLINE"
        OfferAction.MANUAL_REVIEW -> "REVIEW"
        else -> "OFFER"
    }
    val verdictColor = when (action) {
        OfferAction.ACCEPT -> colors.good
        OfferAction.DECLINE -> colors.bad
        OfferAction.MANUAL_REVIEW -> colors.warn
        else -> colors.warn
    }.toArgb()

    return SpannableStringBuilder().apply {
        val start = length
        append(verdict)
        setSpan(ForegroundColorSpan(verdictColor), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(RelativeSizeSpan(1.2f), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        append(" · ")
        append("$%.0f/hr net".format(dollarsPerHour))
        // Whole headline (verdict + rate) bold.
        setSpan(StyleSpan(Typeface.BOLD), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        append("\n")
        append(
            "Net $%.2f · %.1f mi · $%.2f/mi · Score %d · %s".format(
                netPayAmount, distanceMiles, dollarsPerMile, score.toInt(), merchantName,
            ),
        )
    }
}
