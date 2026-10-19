package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.offer.OfferQuoteBasis
import cloud.trotter.dashbuddy.domain.model.offer.OfferScope
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.*
import org.junit.Assert.*
import org.junit.Test

class AddOnStopsReplayTest {
    @Test fun `add on mint count replays through the relocated rule`() {
        val platform = Platform.DoorDash
        val machine = StateMachine(FlowRegionStepper(), PlatformRegionStepper(),
            CrossPlatformRegionStepper(), TransitionPolicy(), EffectMap())
        data class Case(val stops: Int?, val drops: Int, val hint: String = "Old", val resolved: String? = null,
            val listed: String = "Old", val pickups: Int = 1)
        for ((stops, drops, hint, resolved, listed, pickups) in listOf(Case(3, 2), Case(null, 1),
            Case(3, 2, hint = "Chick-fil-A", resolved = "Chick Fil A", listed = " chick fil a "),
            Case(3, 1, hint = " Old ", resolved = " Old ", pickups = 2))) {
            val parsed = ParsedOffer("addon", quoteBasis = OfferQuoteBasis.INCREMENTAL,
                offerScope = OfferScope.ADD_ON, payAmount = 7.0, distanceMiles = 2.1, stopCount = stops,
                orders = listOf(listed, "New").mapIndexed { i, store ->
                    ParsedOrder(i, OrderType.PICKUP, store, 1, false, emptySet())
                })
            val pending = PendingOffer("addon", ParsedFields.OfferFields(parsedOffer = parsed),
                presentedAt = 800, returnFlow = Flow.TaskDropoffNavigation,
                lastClickIntent = OfferIntent.ACCEPT, acceptClickAt = 900, acceptedAt = 900)
            val old = Job("job", listOf(hint), "before", startedAt = 100,
                acceptedOffers = listOf(AcceptedOfferEconomics("before", acceptedAt = 100)),
                tasks = listOf(Task("pickup", "job", TaskPhase.PICKUP, expectedStoreHint = hint, storeName = resolved, startedAt = 100),
                    Task("drop", "job", TaskPhase.DROPOFF, startedAt = 100)))
            val initial = AppState(regions = Regions(
                flow = FlowRegion(flow = Flow.Idle, activePlatform = platform),
                platforms = mapOf(platform to PlatformRegion(platform, Mode.Online, Session("dash", 0),
                    activeJob = old, pendingOffers = listOf(pending), mintCounter = 10))))
            val frame = Observation.Screen(1_000, captureId = null, ruleId = "doordash.screen.synthetic",
                metadata = ReplayMetadata.EMPTY, flow = Flow.TaskPickupNavigation, modeHint = Mode.Online,
                parsed = ParsedFields.TaskFields(phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.NAVIGATION,
                    storeName = "New"))
            val result = machine.step(initial, frame)
            // Replaying the same immutable input produces the same ids and mint counter.
            assertEquals(result.newState, machine.step(initial, frame).newState)
            val region = result.newState.regions.platforms.getValue(platform)
            val job = requireNotNull(region.activeJob)
            assertEquals("job", job.jobId)
            assertEquals(drops, job.tasks.count { it.phase == TaskPhase.DROPOFF && it.mintedByOfferHash == "addon" })
            assertEquals(1, job.tasks.count { it.phase == TaskPhase.PICKUP && it.expectedStoreHint == "New" })
            assertEquals(pickups, job.tasks.count { it.phase == TaskPhase.PICKUP } - old.tasks.count { it.phase == TaskPhase.PICKUP })
            assertEquals(10L + drops + pickups, region.mintCounter)
            assertEquals(listOf("before", "addon"), job.acceptedOffers.map { it.offerHash })
        }
    }
}
