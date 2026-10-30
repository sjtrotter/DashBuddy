package cloud.trotter.log.gig.domain.offer.story

import cloud.trotter.log.gig.domain.evaluation.*
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.domain.model.offer.*
import cloud.trotter.log.gig.domain.model.order.CountUnit
import cloud.trotter.log.gig.domain.state.*
import java.util.Locale
import kotlin.math.abs

enum class FactKind { PAY, NET, MILES, MINUTES, PER_MILE, PER_ITEM, PER_HOUR, STOPS, ITEMS }
enum class Versus { ABOVE, BELOW }
enum class StoryBasis { TOTAL, ADD_ON_JOB, ADD_ON_DELTA }
enum class Missing { PAY, DISTANCE, ESTIMATE }
enum class RuleHit { SHOPPING_OFF, BLOCKED_STORE, REVIEW_STORE, PROTECT_RATE }
enum class AddOnSpeech { JOB_TOTALS, DELTAS_ONLY }

data class Fact(
    val kind: FactKind,
    val value: Double?,
    val target: Double? = null,
    val versus: Versus? = null,
    val previous: Double? = null,
    val delta: Double? = null,
)

data class OfferStory(
    val appName: String,
    val addOn: Boolean,
    val store: String?,
    val basis: StoryBasis,
    val facts: List<Fact>,
    val added: List<Fact>,
    val missing: Set<Missing>,
    val rules: List<RuleHit>,
    val requote: Boolean,
) {
    fun fact(k: FactKind) = facts.firstOrNull { it.kind == k }
    /** One presentation policy for every renderer; the source retains both sets of figures. */
    fun forPresentation(policy: AddOnSpeech): OfferStory =
        if (basis == StoryBasis.ADD_ON_JOB && policy == AddOnSpeech.DELTAS_ONLY)
            copy(basis = StoryBasis.ADD_ON_DELTA, facts = added) else this
}

object OfferStories {
    fun build(pending: PendingOffer, job: Job?, config: EvaluationConfig?, platform: Platform,
        requote: Boolean = false,
        evaluate: (ParsedOffer, EvaluationConfig) -> OfferEvaluation = OfferEvaluator()::evaluate,
    ): OfferStory {
        val o = pending.offerFields.parsedOffer
        val added = listOf(Fact(FactKind.PAY, o.payAmount), Fact(FactKind.MILES, o.distanceMiles),
            Fact(FactKind.MINUTES, o.timeToCompleteMinutes?.toDouble()),
            Fact(FactKind.STOPS, o.stopCount?.toDouble()), Fact(FactKind.ITEMS, o.itemCount.toDouble()))
        val rules = config?.let { OfferRules.hits(o, it.forPlatform(platform)) }.orEmpty()
        fun story(basis: StoryBasis, facts: List<Fact>, missing: Set<Missing>) = OfferStory(
            platform.appName, o.effectiveScope == OfferScope.ADD_ON,
            o.displayStores.firstOrNull { it.isNotBlank() }, basis, facts,
            if (o.effectiveScope == OfferScope.ADD_ON) added else emptyList(), missing, rules, requote)
        if (o.quoteBasis == OfferQuoteBasis.TOTAL) {
            val e = pending.evaluation?.takeIf { it.hasDistanceMetrics && o.payAmount != null }
            return story(StoryBasis.TOTAL, facts(o, e, OfferTargets.from(config, platform, o.isShop)),
                buildSet {
                    if (o.payAmount == null) add(Missing.PAY)
                    if (o.distanceMiles == null || o.distanceMiles <= 0) add(Missing.DISTANCE)
                    if (pending.evaluation == null) add(Missing.ESTIMATE)
                })
        }
        val before = job?.let(CombinedOffer::quote)
        val summed = job?.let { CombinedOffer.build(it, o) }
        if (job != null && before != null && summed != null && config != null &&
            before.units == o.itemCountIsUnits && CombinedOffer.newOrders(job, o)
                .filter { it.orderType.isShoppingOrder && it.itemCount > 0 }.all {
                    it.countUnit == if (before.units) CountUnit.UNITS else CountUnit.ITEMS
                }) {
            val resolvedConfig = config.forPlatform(platform)
            val previousOffer = before.beforeOffer(o.offerHash)
            val previous = evaluate(previousOffer, resolvedConfig)
            val e = evaluate(summed, resolvedConfig)
            if (e.hasDistanceMetrics && previous.hasDistanceMetrics) {
                val targets = OfferTargets.from(config, platform, summed.isShop)
                val old = facts(previousOffer, previous, targets).associateBy { it.kind }
                return story(StoryBasis.ADD_ON_JOB, facts(summed, e, targets).map { fact ->
                    val was = old[fact.kind]?.value ?: if (fact.kind == FactKind.ITEMS) previousOffer.itemCount.toDouble() else null
                    fact.copy(previous = was, delta = was?.let { fact.value?.minus(it) })
                }, emptySet())
            }
        }
        return story(StoryBasis.ADD_ON_DELTA, added, buildSet {
            if (o.payAmount == null) add(Missing.PAY)
            if (o.distanceMiles == null) add(Missing.DISTANCE)
        })
    }

    private fun facts(o: ParsedOffer, e: OfferEvaluation?, t: Targets): List<Fact> {
        fun rate(k: FactKind, value: Double?, target: Double?): Fact {
            fun precision(n: Double) = if (k == FactKind.PER_HOUR)
                String.format(Locale.ROOT, "%.0f", abs(n)).toDouble() * if (n < 0) -1 else 1
                else Formats.cents(n).toDouble()
            return Fact(k, value, target, if (value != null && target != null)
                if (precision(value) >= precision(target)) Versus.ABOVE else Versus.BELOW else null)
        }
        val items = o.itemCount.takeIf { it > 0 }?.toDouble()
        return listOfNotNull(Fact(FactKind.PAY, o.payAmount), Fact(FactKind.NET, e?.netPayAmount),
            Fact(FactKind.MILES, e?.distanceMiles), Fact(FactKind.MINUTES, e?.estimatedTimeMinutes),
            rate(FactKind.PER_MILE, e?.dollarsPerMile, t.perMile),
            if (o.isShop) rate(FactKind.PER_ITEM, items?.let { e?.netPayAmount?.div(it) }, t.perItem) else null,
            rate(FactKind.PER_HOUR, e?.dollarsPerHour, t.perHour),
            Fact(FactKind.STOPS, o.stopCount?.toDouble()),
            if (o.isShop) Fact(FactKind.ITEMS, items) else null)
    }
}
