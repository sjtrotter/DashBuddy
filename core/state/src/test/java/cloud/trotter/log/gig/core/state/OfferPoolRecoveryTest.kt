package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.state.AppState
import cloud.trotter.log.gig.domain.state.Mode
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1119 S2: the offer pool survives recovery. The link stream (spec case 7) and the tie stream (case 10) run through
 * `StateMachine`; at every step boundary the state is encoded with `StateJson`, decoded, scrubbed by
 * `recoveryHygiene`, and the run continues. The offer outcomes logged (type and effect key) equal the uninterrupted
 * run's, and the pool round-trips intact.
 *
 * Non-vacuity: on master `77c089dac` there is no pool to round-trip and both streams log `OFFER_TIMEOUT`, so the
 * expected outcome sets (an `OFFER_ACCEPTED` keyed by instance; two `OFFER_NOT_TAKEN`) are not produced.
 */
class OfferPoolRecoveryTest {

    private val linkStream = listOf(PF.card(1_000, "hA"), PF.idle(5_000), PF.pickup(10_000))
    private val tieStream = listOf(PF.card(1_000, "hA"), PF.idle(5_000), PF.card(6_000, "hB"), PF.idle(9_000), PF.pickup(10_000))

    private fun outcomeKeys(stream: List<Observation>, crashAfter: Int?): Set<Pair<AppEventType, String>> {
        val machine = PF.machine()
        var state = PF.online()
        val logged = mutableSetOf<Pair<AppEventType, String>>()
        val all = stream + listOf(PF.summary(100_000), PF.idle(160_000).copy(modeHint = Mode.Offline))
        all.forEachIndexed { i, obs ->
            val t = machine.step(state, obs)
            t.effects.filterIsInstance<AppEffect.LogEvent>().filter { it.event.type in PF.OUTCOMES }
                .forEach { logged += it.event.type to it.effectKey }
            state = t.newState
            if (crashAfter == i) {
                val decoded = StateJson.decodeFromString<AppState>(StateJson.encodeToString(state))
                assertEquals("the pool round-trips intact", state.regions.platforms.getValue(PF.platform).offerPool,
                    decoded.regions.platforms.getValue(PF.platform).offerPool)
                state = decoded.recoveryHygiene(state.timestamp)
            }
        }
        return logged
    }

    private fun check(stream: List<Observation>, expected: Set<AppEventType>) {
        val baseline = outcomeKeys(stream, crashAfter = null)
        assertEquals(expected, baseline.map { it.first }.toSet())
        assertTrue(baseline.all { it.second.startsWith("log:${it.first}:doordash:") })
        for (k in stream.indices) assertEquals("crash after step $k", baseline, outcomeKeys(stream, crashAfter = k))
    }

    @Test fun `the link stream logs the same outcomes across a crash at every step`() {
        check(linkStream, setOf(AppEventType.OFFER_ACCEPTED))
    }

    @Test fun `the tie stream logs the same outcomes across a crash at every step`() {
        check(tieStream, setOf(AppEventType.OFFER_NOT_TAKEN))
    }
}
