package cloud.trotter.log.gig.feature.bubble.offer

import android.content.res.Resources
import cloud.trotter.log.gig.domain.offer.story.*
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.feature.bubble.R
import kotlin.math.roundToInt

object OfferStoryText {
    fun title(s: OfferStory, res: Resources): String =
        res.getString(if (s.addOn) R.string.offer_title_addon else R.string.offer_title, s.appName)

    fun ruleResource(hit: RuleHit): Int = when (hit) {
        RuleHit.SHOPPING_OFF -> R.string.offer_rule_shopping_off
        RuleHit.BLOCKED_STORE -> R.string.offer_rule_blocked
        RuleHit.REVIEW_STORE -> R.string.offer_rule_review
        RuleHit.PROTECT_RATE -> R.string.offer_rule_protect
    }

    fun note(s: OfferStory, res: Resources): String = s.rules.firstOrNull()?.let { res.getString(ruleResource(it)) }
        ?: when (s.missing.firstOrNull()) {
            Missing.PAY -> res.getString(R.string.offer_missing_pay)
            Missing.DISTANCE -> res.getString(R.string.offer_missing_distance)
            Missing.ESTIMATE -> res.getString(R.string.offer_missing_estimate)
            null -> ""
        }

    fun line(s: OfferStory, res: Resources): String = buildList {
        s.store?.let(::add)
        s.rules.forEach { add(res.getString(ruleResource(it))) }
        val pay = (if (s.basis == StoryBasis.ADD_ON_JOB) s.added.firstOrNull { it.kind == FactKind.PAY }
            else s.fact(FactKind.PAY))?.value
        if (pay == null) add(res.getString(R.string.offer_missing_pay))
        else add(res.getString(R.string.offer_line_pay, (if (s.basis != StoryBasis.TOTAL) "+" else "") + Formats.money(pay)))
        s.fact(FactKind.NET)?.value?.let {
            add(res.getString(if (s.basis == StoryBasis.ADD_ON_JOB) R.string.offer_line_job_net else R.string.offer_line_net,
                Formats.money(it)))
        }
        val prefix = if (s.basis == StoryBasis.ADD_ON_DELTA) "+" else ""
        s.fact(FactKind.MILES)?.value?.let { add(res.getString(R.string.offer_line_miles, prefix + Formats.decimal(it))) }
        s.fact(FactKind.MINUTES)?.value?.let { add(res.getString(R.string.offer_line_minutes, prefix + it.roundToInt())) }
        if (Missing.DISTANCE in s.missing && pay != null) add(res.getString(R.string.offer_missing_distance))
        s.fact(FactKind.PER_HOUR)?.value?.let { add(res.getString(R.string.offer_line_rate, Formats.money0(it))) }
    }.joinToString(" · ")
}
