package cloud.trotter.dashbuddy.domain.evaluation

import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.state.Platform

/**
 * The Strategy Lab's "what would this offer score?" preview — a pure function of (pay, miles,
 * config), with no state of its own.
 *
 * It lived on `SettingsViewModel` as `simulateOffer`, which made the Lab screen call a ViewModel
 * *function* from composition to derive display state (#944 / review §4.8). It is not an action
 * and it owns nothing: [OfferEvaluator] is a stateless calculator, so the honest home for this is
 * `:domain` beside the evaluator, where the composable can memoize it on its own inputs
 * (`remember(pay, miles, config)`) exactly like any other derived value — and where it is
 * unit-testable without a ViewModel.
 */
object OfferSimulation {

    /** Stateless calculator — one instance is enough; nothing is carried between calls. */
    private val evaluator = OfferEvaluator()

    /**
     * Score a hypothetical [payDollars]/[milesDistance] offer against [config].
     *
     * A platformless preview explicitly resolves Unknown: generic seeds plus global overrides.
     * Non-shop offers also use learned time constants, so a previously resolved config is unsafe.
     */
    fun simulate(payDollars: Double, milesDistance: Double, config: EvaluationConfig): OfferEvaluation {
        val simulatedOffer = ParsedOffer(
            // Stable hash (#367): the caller memoizes on (pay, miles, config) — a wall-clock hash
            // would defeat structural equality.
            offerHash = "simulated-offer",
            payAmount = payDollars,
            distanceMiles = milesDistance,
            itemCount = SIMULATED_ITEM_COUNT,
            orders = emptyList(), // non-shop by construction (no SHOP order)
        )
        return evaluator.evaluate(simulatedOffer, config.forPlatform(Platform.Unknown))
    }

    /** Default average workload for the simulated offer (unchanged from the ViewModel original). */
    private const val SIMULATED_ITEM_COUNT = 5
}
