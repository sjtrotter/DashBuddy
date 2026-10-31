package cloud.trotter.log.gig.state.effects

import android.content.res.Resources
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.domain.offer.story.*
import cloud.trotter.log.gig.domain.format.Formats
import kotlin.math.abs
import kotlin.math.roundToInt

object OfferSpeech {
    fun speak(s: OfferStory, res: Resources): String = buildList {
        fun dollars(id: Int, amount: Double) {
            val n = Formats.wholeDollars(amount)
            add(res.getQuantityString(id, abs(n).toInt(), n))
        }
        add(res.getString(if (s.addOn) R.string.tts_lead_addon else R.string.tts_lead_offer) + ".")
        s.store?.let { add("$it.") }
        // One preference clause at most: the first one spoken in the shared order. Protect is a standing
        // mode and item counts are not spoken, so neither has a clause.
        s.preferences().firstNotNullOfOrNull { hit -> when (hit) {
            is PreferenceHit.Rule -> when (hit.hit) {
                RuleHit.SHOPPING_OFF -> R.string.tts_rule_shopping_off
                RuleHit.BLOCKED_STORE -> R.string.tts_rule_blocked
                RuleHit.REVIEW_STORE -> R.string.tts_rule_review
                RuleHit.PROTECT_RATE -> null
            }
            is PreferenceHit.Limit -> when (hit.hit.kind) {
                LimitKind.NET -> R.string.tts_limit_net
                LimitKind.DISTANCE -> R.string.tts_limit_distance
                LimitKind.ITEMS -> null
            }
        } }?.let { add(res.getString(it)) }
        val pay = (if (s.basis == StoryBasis.ADD_ON_JOB) s.added.firstOrNull { it.kind == FactKind.PAY }
            else s.fact(FactKind.PAY))?.value
        val net = s.fact(FactKind.NET)?.value
        if (s.basis != StoryBasis.TOTAL && pay != null) dollars(R.plurals.tts_adds, pay)
        if (net != null) {
            if (net < 0) {
                if (Formats.wholeDollars(net) == 0L) add(res.getString(R.string.tts_loss_under_one))
                else dollars(R.plurals.tts_loss, abs(net))
            } else dollars(if (s.basis == StoryBasis.ADD_ON_JOB) R.plurals.tts_job_net else R.plurals.tts_net, net)
        } else if (s.basis == StoryBasis.TOTAL && pay != null) dollars(R.plurals.tts_pay, pay)
        if (pay == null) add(res.getString(R.string.tts_pay_unknown))
        val delta = s.basis == StoryBasis.ADD_ON_DELTA
        s.fact(FactKind.MILES)?.value?.let {
            add(res.getString(if (delta) R.string.tts_more_miles else R.string.tts_miles, Formats.decimal(it)))
        }
        s.fact(FactKind.MINUTES)?.value?.roundToInt()?.let {
            add(res.getQuantityString(if (delta) R.plurals.tts_more_minutes else R.plurals.tts_minutes, it, it))
        }
        if (Missing.DISTANCE in s.missing && pay != null) add(res.getString(R.string.tts_distance_unknown))
        s.fact(FactKind.PER_HOUR)?.value?.let {
            // Every spoken dollar figure truncates the displayed cents.
            val n = Formats.wholeDollars(it)
            add(res.getQuantityString(R.plurals.tts_per_hour, abs(n).toInt(), n))
        }
    }.joinToString(" ")
}
