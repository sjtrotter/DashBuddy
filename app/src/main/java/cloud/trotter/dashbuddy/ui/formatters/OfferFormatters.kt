package cloud.trotter.dashbuddy.ui.formatters

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import androidx.compose.ui.graphics.toArgb
import cloud.trotter.dashbuddy.R
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

/** Verdict color as an ARGB int (RemoteViews can't use Compose Color / theme attrs). */
fun offerVerdictArgb(action: OfferAction?): Int = darkAppColors().let { c ->
    when (action) {
        OfferAction.ACCEPT -> c.good
        OfferAction.DECLINE -> c.bad
        OfferAction.MANUAL_REVIEW -> c.warn
        else -> c.warn
    }
}.toArgb()

/**
 * Score-band color as an ARGB int for the notification gauge ring (#583) — mirrors the bubble offer
 * card's ring color (`OfferBody`): the evaluator's real decision boundaries (#400). good ≥ ACCEPT,
 * bad ≤ DECLINE, warn in between.
 */
fun offerScoreArgb(score: Double): Int = darkAppColors().let { c ->
    when {
        score >= OfferEvaluator.ACCEPT_THRESHOLD -> c.good
        score <= OfferEvaluator.DECLINE_THRESHOLD -> c.bad
        else -> c.warn
    }
}.toArgb()

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
 * Note: bold + size are reliable across devices; foreground color in a MessagingStyle notification
 * is device/version dependent (the system may re-theme it), so the line still reads cleanly without
 * it. `toString()` yields the plain text used for chat storage.
 */
fun OfferEvaluation.toNotificationSummary(): CharSequence {
    val verdict = offerVerdictLabel(action)
    val verdictColor = offerVerdictArgb(action)

    return SpannableStringBuilder().apply {
        val start = length
        append(verdict)
        setSpan(ForegroundColorSpan(verdictColor), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(RelativeSizeSpan(1.2f), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        append(" · ")
        append("${Formats.money0(dollarsPerHour)}/hr net")
        // Whole headline (verdict + rate) bold.
        setSpan(StyleSpan(Typeface.BOLD), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        append("\n")
        append(
            "Net ${Formats.money(netPayAmount)} · " +
                "${Formats.decimal(distanceMiles)} mi · " +
                "${Formats.money(dollarsPerMile)}/mi · " +
                "Score ${score.toInt()} · " +
                merchantName,
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
