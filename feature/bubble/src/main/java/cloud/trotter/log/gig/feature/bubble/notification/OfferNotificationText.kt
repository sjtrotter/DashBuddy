package cloud.trotter.log.gig.feature.bubble.notification

import kotlin.math.roundToInt
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.compose.ui.graphics.toArgb
import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.core.designsystem.theme.darkAppColors
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.feature.bubble.hud.GaugeTone

/** Android spans keep the same money parts as OfferHero, including the sign and currency symbol. */
fun offerNotificationHero(pay: Double?, expanded: Boolean, scaledDensity: Float = 1f): CharSequence {
    val colors = darkAppColors()
    val wholeSize = if (expanded) 44 else 24
    val unitSize = if (expanded) 24 else 15
    val parts = pay?.let(Formats::moneyParts)
    return SpannableStringBuilder().apply {
        if (parts == null) segment(EMPTY_VALUE, wholeSize, colors.text.toArgb(), true, scaledDensity)
        else {
            segment(parts.sign + parts.symbol, unitSize, colors.text2.toArgb(), true, scaledDensity)
            segment(parts.whole, wholeSize, colors.text.toArgb(), true, scaledDensity)
            segment(parts.fraction, unitSize, colors.text2.toArgb(), true, scaledDensity)
        }
    }
}

fun offerNotificationRate(rate: RateText, scaledDensity: Float = 1f): CharSequence {
    val colors = darkAppColors()
    val ink = when (rate.tone) {
        GaugeTone.GOOD -> colors.good
        GaugeTone.WARN -> colors.warn
        GaugeTone.NEUTRAL -> colors.text
    }
    return offerNotificationFigure(rate.value, rate.unit, 18, 12, ink.toArgb(), colors.neutral.toArgb(), scaledDensity)
}

fun offerNotificationFigure(value: String, label: String, valueSp: Int, labelSp: Int,
    valueArgb: Int, labelArgb: Int = valueArgb, scaledDensity: Float = 1f): CharSequence = SpannableStringBuilder().apply {
    segment(value, valueSp, valueArgb, true, scaledDensity)
    segment(label, labelSp, labelArgb, false, scaledDensity)
}

private fun SpannableStringBuilder.segment(value: String, size: Int, ink: Int, bold: Boolean, scaledDensity: Float) {
    val start = length
    append(value)
    if (length == start) return
    setSpan(AbsoluteSizeSpan((size * scaledDensity).roundToInt()), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    setSpan(ForegroundColorSpan(ink), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    setSpan(StyleSpan(if (bold) Typeface.BOLD else Typeface.NORMAL), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
}
