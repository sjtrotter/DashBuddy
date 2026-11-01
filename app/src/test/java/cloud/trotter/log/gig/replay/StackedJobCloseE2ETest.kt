package cloud.trotter.log.gig.replay

import cloud.trotter.log.gig.domain.analytics.ShiftEarnings
import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.model.offer.OfferScope
import cloud.trotter.log.gig.domain.model.offer.ParsedOffer
import cloud.trotter.log.gig.domain.model.order.OrderType
import cloud.trotter.log.gig.domain.model.order.ParsedOrder
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.OfferIntent
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.TaskPhase
import cloud.trotter.log.gig.domain.state.TaskSubFlow
import cloud.trotter.log.gig.test.util.E2ESessionReplay
import cloud.trotter.log.gig.test.util.ReplayApplication
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #1458 J2, end to end: a $20 two-drop job whose first drop is displaced by the second drop's
 * pre-arrival screen reads no recorded pay until the second drop's retire closes the job, then the
 * whole $20 — through the real engine (its own retire timer), Room, the projector and the bubble's
 * shift read (`HudShiftDao.latestSession` → `ShiftEarnings.live`). The reducer-level twin, with the
 * other October shapes, is `StackedJobCloseTimingTest` in `:core:state`.
 *
 * The recognizer's output is synthesized ([E2ESessionReplay.observe]): synthetic store names, hashes and
 * quote, no field text. Everything downstream of the sensor is production code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ReplayApplication::class)
class StackedJobCloseE2ETest {

    private val platform = Platform.DoorDash

    private fun screen(at: Long, flow: Flow, parsed: ParsedFields = ParsedFields.None) = Observation.Screen(
        timestamp = at, captureId = null, ruleId = "${platform.wire}.screen.synthetic",
        metadata = ReplayMetadata.EMPTY, flow = flow, modeHint = Mode.Online, parsed = parsed,
    )

    private fun task(at: Long, flow: Flow, phase: TaskPhase, sub: TaskSubFlow, store: String? = null, customer: String? = null) =
        screen(at, flow, ParsedFields.TaskFields(
            phase = phase, subFlow = sub, storeName = store, customerNameHash = customer,
            customerAddressHash = customer?.let { "$it-address" },
        ))

    private fun offerCard(at: Long) = screen(at, Flow.OfferPresented, ParsedFields.OfferFields(
        parsedOffer = ParsedOffer(
            offerHash = "offer-a", offerScope = OfferScope.STANDALONE, payAmount = 20.0, distanceMiles = 6.0,
            timeToCompleteMinutes = 35L,
            orders = listOf(0, 1).map { ParsedOrder(it, OrderType.PICKUP, "Store A", 1, false, emptySet()) },
        ),
    ))

    private fun acceptTap(at: Long) = Observation.Click(
        timestamp = at, captureId = null, ruleId = "${platform.wire}.click.${OfferIntent.ACCEPT}",
        metadata = ReplayMetadata.EMPTY, flow = null, modeHint = null,
        parsed = ParsedFields.ClickFields(intent = OfferIntent.ACCEPT), target = OfferIntent.ACCEPT,
        screenTarget = "offer_popup",
    )

    private fun E2ESessionReplay.headline(): Double? {
        drain()
        val row = await { db.hudShiftDao().latestSession(platform.wire).first() }
        return ShiftEarnings.live(running = null, recordedPay = row?.recordedPay)
    }

    @Test
    fun `J2 the whole job's quote reaches the headline at the last drop's retire, not before`() {
        val app = RuntimeEnvironment.getApplication() as ReplayApplication
        val replay = E2ESessionReplay(app, EPOCH)
        try {
            replay.start()
            replay.observe(screen(EPOCH + 1_000L, Flow.Idle))
            replay.observe(offerCard(EPOCH + 30_000L))
            replay.observe(acceptTap(EPOCH + 40_000L))
            replay.observe(task(EPOCH + 41_000L, Flow.TaskPickupNavigation, TaskPhase.PICKUP, TaskSubFlow.NAVIGATION, "Store A"))
            val job = replay.manager.state.value.regions.platforms[platform]?.activeJob
            assertNotNull("the accept minted the job\n${replay.trace()}", job)
            replay.observe(task(EPOCH + 400_000L, Flow.TaskPickupArrived, TaskPhase.PICKUP, TaskSubFlow.ARRIVED, "Store A"))
            replay.observe(task(EPOCH + 700_000L, Flow.TaskDropoffNavigation, TaskPhase.DROPOFF, TaskSubFlow.NAVIGATION, customer = "cust-1"))
            replay.observe(task(EPOCH + 1_000_000L, Flow.TaskDropoffArrived, TaskPhase.DROPOFF, TaskSubFlow.ARRIVED, customer = "cust-1"))
            // D2's pre-arrival displaces D1: no retire grace, no row.
            replay.observe(task(EPOCH + 1_001_900L, Flow.TaskDropoffNavigation, TaskPhase.DROPOFF, TaskSubFlow.NAVIGATION, customer = "cust-2"))
            assertNull("D1 displaced: nothing recorded\n${replay.trace()}", replay.headline())
            replay.observe(task(EPOCH + 1_500_000L, Flow.TaskDropoffArrived, TaskPhase.DROPOFF, TaskSubFlow.ARRIVED, customer = "cust-2"))
            replay.observe(screen(EPOCH + 1_502_000L, Flow.Idle))
            replay.advanceTo(EPOCH + 1_502_000L + 9_000L)
            assertNull("inside D2's retire grace: still nothing\n${replay.trace()}", replay.headline())

            replay.advanceTo(EPOCH + 1_502_000L + 10_500L)
            assertNull(
                "the engine's retire timer closed the job",
                replay.manager.state.value.regions.platforms[platform]?.activeJob,
            )
            val headline = replay.headline()
            assertEquals("the whole job, once\n${replay.trace()}", 2000L, Math.round((headline ?: 0.0) * 100.0))
            assertEquals(
                "one row per drop",
                2L, replay.rowCount("delivery_records"),
            )
        } finally {
            replay.close()
        }
    }

    private companion object {
        const val EPOCH = 1_790_000_000_000L
    }
}
