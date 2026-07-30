package cloud.trotter.dashbuddy.feature.bubble.formatters

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import cloud.trotter.dashbuddy.feature.bubble.R
import cloud.trotter.dashbuddy.core.designsystem.theme.AppColors
import cloud.trotter.dashbuddy.core.designsystem.theme.darkAppColors
import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluator
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.evaluation.OfferQuality
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona

/**
 * SSOT (#578) for the offer verdict word + color, shared by [toNotificationSummary], the rich offer
 * notification's custom views, and (the intent is) the bubble offer card — so the verdict can't drift
 * between surfaces.
 */
fun offerVerdictLabel(action: OfferAction?): String = when (action) {
    OfferAction.ACCEPT -> "ACCEPT"
    OfferAction.DECLINE -> "DECLINE"
    OfferAction.MANUAL_REVIEW -> "REVIEW"
    else -> "OFFER"
}

/**
 * Verdict foreground color — the companion of [offerVerdictLabel], so the word and its tint can't
 * drift (#942). The bubble offer card's verdict banner re-implemented this keyed on the raw enum
 * NAME (`"ACCEPT" -> c.good`), the #283 stringly-typed shape.
 */
fun offerVerdictColor(action: OfferAction?, c: AppColors): Color = when (action) {
    OfferAction.ACCEPT -> c.good
    OfferAction.DECLINE -> c.bad
    else -> c.warn
}

/** The [offerVerdictColor] container tint for a filled verdict surface (#942). */
fun offerVerdictContainer(action: OfferAction?, c: AppColors): Color = when (action) {
    OfferAction.ACCEPT -> c.goodBg
    OfferAction.DECLINE -> c.badBg
    else -> c.warnBg
}

/** Verdict color as an ARGB int (RemoteViews can't use Compose Color / theme attrs). */
fun offerVerdictArgb(action: OfferAction?): Int =
    offerVerdictColor(action, darkAppColors()).toArgb()

/**
 * Score-band color: the evaluator's REAL decision boundaries (#400) — good ≥ ACCEPT, bad ≤ DECLINE,
 * warn in between. One owner (#942) for the bubble offer card's gauge ring and the notification
 * gauge ring (#583), which held a verbatim copy of this `when`.
 */
fun offerScoreColor(score: Double, c: AppColors): Color = when {
    score >= OfferEvaluator.ACCEPT_THRESHOLD -> c.good
    score <= OfferEvaluator.DECLINE_THRESHOLD -> c.bad
    else -> c.warn
}

/** [offerScoreColor] as an ARGB int for the notification gauge ring (#583). */
fun offerScoreArgb(score: Double): Int =
    offerScoreColor(score, darkAppColors()).toArgb()

/**
 * SSOT (#461/#578) badge enum name → `ic_badge_*` drawable, shared by the bubble offer card and the
 * rich offer notification. null = no icon (the SHOP cart + item count is handled by each caller).
 */
fun offerBadgeIcon(name: String): Int? = when (name) {
    "HIGH_PAYING" -> R.drawable.ic_badge_dollar_plus
    "PRIORITY_ACCESS" -> R.drawable.ic_badge_priority_access
    "COLLECT_CASH" -> R.drawable.ic_badge_collect_cash
    "RED_CARD" -> R.drawable.ic_badge_red_card
    "LARGE_ORDER" -> R.drawable.ic_badge_large_order
    "PIZZA_BAG" -> R.drawable.ic_badge_pizza_bag
    "ALCOHOL", "INCLUDES_ALCOHOL" -> R.drawable.ic_badge_alcohol
    "CHECK_RECIPIENT_ID", "AGE_RESTRICTED_18_PLUS", "AGE_RESTRICTED_21_PLUS",
    "CONTAINS_RESTRICTED_ITEMS" -> R.drawable.ic_badge_id_check
    else -> null
}

/** The persona that voices an offer verdict's notification (moved from the engine, #436). */
fun OfferEvaluation.notificationPersona(): ChatPersona = when (action) {
    OfferAction.ACCEPT -> ChatPersona.GoodOffer
    OfferAction.DECLINE -> ChatPersona.BadOffer
    OfferAction.MANUAL_REVIEW -> ChatPersona.Inspector
    OfferAction.NOTHING -> ChatPersona.Inspector
}

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
 * When the offer's distance never parsed (#936) every rate here would be a `0.0` placeholder, so
 * the summary states the gross pay and says there is no verdict instead of quoting `$0/hr net`:
 *
 * ```
 * OFFER · no verdict
 * Pay $12.50 · distance didn't parse · H-E-B
 * ```
 *
 * Note: bold + size are reliable across devices; foreground color in a MessagingStyle notification
 * is device/version dependent (the system may re-theme it), so the line still reads cleanly without
 * it. `toString()` yields the plain text used for chat storage.
 */
fun OfferEvaluation.toNotificationSummary(): CharSequence {
    val verdict = offerVerdictLabel(action)
    val verdictColor = offerVerdictArgb(action)
    val scored = hasDistanceMetrics

    return SpannableStringBuilder().apply {
        val start = length
        append(verdict)
        setSpan(ForegroundColorSpan(verdictColor), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(RelativeSizeSpan(1.2f), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        append(" · ")
        append(if (scored) "${Formats.money0(dollarsPerHour)}/hr net" else "no verdict")
        // Whole headline (verdict + rate) bold.
        setSpan(StyleSpan(Typeface.BOLD), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        append("\n")
        append(
            if (scored) {
                "Net ${Formats.money(netPayAmount)} · " +
                    "${Formats.decimal(distanceMiles)} mi · " +
                    "${Formats.money(dollarsPerMile)}/mi · " +
                    "Score ${score.toInt()} · " +
                    merchantName
            } else {
                // Gross pay is real (parsed); every other figure would be invented.
                "Pay ${Formats.money(payAmount)} · distance didn't parse · $merchantName"
            },
        )
    }
}


/** UI label for a typed [OfferQuality] (#366) — display copy stays out of :domain. */
fun OfferQuality.displayLabel(): String = when (this) {
    OfferQuality.AWESOME -> "AWESOME OFFER"
    OfferQuality.GREAT -> "GREAT OFFER"
    OfferQuality.GOOD -> "GOOD OFFER"
    OfferQuality.DECENT -> "DECENT OFFER"
    OfferQuality.BAD -> "BAD OFFER"
    OfferQuality.PROTECTED -> "Protected!"
    OfferQuality.BLOCKED -> "Blocked"
    OfferQuality.SHOP_DECLINED -> "Shopping off"
    OfferQuality.UNKNOWN -> "No verdict"
}
