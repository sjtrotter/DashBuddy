package cloud.trotter.dashbuddy.domain.evaluation

import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #944 — the Strategy Lab's simulator, moved off `SettingsViewModel` into `:domain` so the Lab
 * screen derives it purely instead of calling a ViewModel function from composition. These pin
 * the properties the screen's `remember(pay, miles, config)` memoization depends on: the result
 * is a pure function of its three inputs, and the simulated offer stays non-shop (the #588
 * precondition for skipping `forPlatform`).
 */
class OfferSimulationTest {

    private val config = EvaluationConfig(
        rules = listOf(
            ScoringRule.MetricRule(
                id = "payout",
                metricType = MetricType.PAYOUT,
                targetValue = 7.0f,
            ),
        ),
        userEconomy = UserEconomy(vehicleClass = VehicleClass.E_BIKE),
    )

    @Test
    fun `simulating matches evaluating the equivalent offer directly`() {
        val simulated = OfferSimulation.simulate(payDollars = 6.5, milesDistance = 3.2, config = config)

        val direct = OfferEvaluator().evaluate(
            ParsedOffer(
                offerHash = "simulated-offer",
                payAmount = 6.5,
                distanceMiles = 3.2,
                itemCount = 5,
                orders = emptyList(),
            ),
            config,
        )

        assertEquals(direct, simulated)
    }

    @Test
    fun `the same inputs always produce the same result`() {
        // What the screen's remember(pay, miles, config) key assumes: no wall clock, no state
        // carried between calls (#367's stable offer hash).
        assertEquals(
            OfferSimulation.simulate(6.5, 3.2, config),
            OfferSimulation.simulate(6.5, 3.2, config),
        )
    }

    @Test
    fun `more pay over the same distance scores no worse`() {
        val lean = OfferSimulation.simulate(3.0, 3.2, config)
        val rich = OfferSimulation.simulate(12.0, 3.2, config)

        assertTrue(rich.score >= lean.score)
    }

    @Test
    fun `the simulated offer is never a shop offer`() {
        // #588: the simulator deliberately skips forPlatform(), which is only safe while no
        // shop-pace field is ever exercised. Proven from the outside: the time estimate is the
        // non-shop branch (drive + flat base overhead), NOT the items-over-pace shop branch —
        // which for the 5 simulated items would land somewhere else entirely.
        val economy = config.userEconomy
        val result = OfferSimulation.simulate(6.5, 3.2, config)

        assertEquals(
            3.2 * economy.avgMinutesPerMile + economy.basePickupMinutes,
            result.estimatedTimeMinutes,
            0.0001,
        )
    }
}
