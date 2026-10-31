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

    fun limitResource(kind: LimitKind): Int = when (kind) {
        LimitKind.NET -> R.string.offer_limit_net
        LimitKind.DISTANCE -> R.string.offer_limit_distance
        LimitKind.ITEMS -> R.string.offer_limit_items
    }

    /** The dasher's own limit as the note names it, the argument of [limitResource]. */
    fun limitArg(hit: LimitHit): Any = when (hit.kind) {
        LimitKind.NET -> Formats.money(hit.target)
        LimitKind.DISTANCE -> Formats.decimal(hit.target)
        LimitKind.ITEMS -> hit.target.roundToInt()
    }

    fun preference(hit: PreferenceHit, res: Resources): String = when (hit) {
        is PreferenceHit.Rule -> res.getString(ruleResource(hit.hit))
        is PreferenceHit.Limit -> res.getString(limitResource(hit.hit.kind), limitArg(hit.hit))
    }

    /** The pop-up's one note: the first preference in [OfferStory.preferences] order, else a missing figure. */
    fun note(s: OfferStory, res: Resources): String = s.preferences().firstOrNull()?.let { preference(it, res) }
        ?: when (s.missing.firstOrNull()) {
            Missing.PAY -> res.getString(R.string.offer_missing_pay)
            Missing.DISTANCE -> res.getString(R.string.offer_missing_distance)
            Missing.ESTIMATE -> res.getString(R.string.offer_missing_estimate)
            null -> ""
        }

    fun line(s: OfferStory, res: Resources): String = buildList {
        s.store?.let(::add)
        s.preferences().forEach { add(preference(it, res)) }
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
