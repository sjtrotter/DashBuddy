package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #962 — `RatingsFields` → `PlatformRegion.ratings` must carry EVERY field.
 *
 * `updateRatings` is a hand-written 15-line field-by-field copy, which is exactly
 * the shape that silently drops a newly added field: the compiler is happy (every
 * `RatingsSnapshot` parameter has a default), the parse is correct, and the value
 * just never reaches the UI. This test pins the whole projection so a future
 * addition that forgets the copy line fails here rather than in the field.
 */
class RatingsSnapshotFlowThroughTest {

    private val stepper = PlatformRegionStepper()
    private val policy = TransitionPolicy()

    private val region = PlatformRegion(platform = Platform.DoorDash, mode = Mode.Online)
    private val flow = FlowRegion(flow = Flow.Idle, activePlatform = Platform.DoorDash)

    private fun ratingsObs(parsed: ParsedFields.RatingsFields) = Observation.Screen(
        timestamp = 1_000L,
        captureId = null,
        ruleId = "doordash.screen.ratings",
        metadata = ReplayMetadata.EMPTY,
        flow = null,
        modeHint = null,
        parsed = parsed,
    )

    @Test
    fun `every parsed ratings field reaches the platform region snapshot`() {
        val parsed = ParsedFields.RatingsFields(
            acceptanceRate = 23.0,
            completionRate = 99.0,
            onTimeRate = 100.0,
            customerRating = 5.0,
            deliveriesLast30Days = 80,
            lifetimeDeliveries = 6_150,
            originalItemsFoundRate = 100.0,
            totalItemsFoundRate = 99.0,
            substitutionIssuesRate = 0.5,
            itemsWithQualityIssuesRate = 0.25,
            itemsWrongOrMissingRate = 0.125,
            lifetimeShoppingOrders = 1_242,
            overallRatingPoints = 73,
            tierLabel = "Silver",
            qualityRate = 98.0,
        )

        val next = stepper.step(region, flow, flow, ratingsObs(parsed), policy)
        val snap = next.ratings
        assertNotNull("a ratings observation must stamp a snapshot", snap)
        requireNotNull(snap)

        assertEquals("capturedAt is the observation timestamp, never a wall clock", 1_000L, snap.capturedAt)
        assertEquals(23.0, snap.acceptanceRate!!, 1e-9)
        assertEquals(99.0, snap.completionRate!!, 1e-9)
        assertEquals(100.0, snap.onTimeRate!!, 1e-9)
        assertEquals(5.0, snap.customerRating!!, 1e-9)
        assertEquals(80, snap.deliveriesLast30Days)
        assertEquals(6_150, snap.lifetimeDeliveries)
        assertEquals(100.0, snap.originalItemsFoundRate!!, 1e-9)
        assertEquals(99.0, snap.totalItemsFoundRate!!, 1e-9)
        assertEquals(0.5, snap.substitutionIssuesRate!!, 1e-9)
        assertEquals(0.25, snap.itemsWithQualityIssuesRate!!, 1e-9)
        assertEquals(0.125, snap.itemsWrongOrMissingRate!!, 1e-9)
        assertEquals(1_242, snap.lifetimeShoppingOrders)
        // The #962 additions — the ones a hand-written copy would forget.
        assertEquals(73, snap.overallRatingPoints)
        assertEquals("Silver", snap.tierLabel)
        assertEquals(98.0, snap.qualityRate!!, 1e-9)
    }

    @Test
    fun `a partially rendered hub stamps only what it parsed`() {
        // The redesigned hub renders no lifetime counters at all, so a partial
        // parse must stay null rather than inheriting a previous snapshot's value.
        val parsed = ParsedFields.RatingsFields(overallRatingPoints = 78, tierLabel = "Gold")
        val next = stepper.step(region, flow, flow, ratingsObs(parsed), policy)
        val snap = requireNotNull(next.ratings)

        assertEquals(78, snap.overallRatingPoints)
        assertEquals("Gold", snap.tierLabel)
        assertNull(snap.lifetimeDeliveries)
        assertNull(snap.qualityRate)
        assertNull(snap.acceptanceRate)
    }
}
