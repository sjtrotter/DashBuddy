package cloud.trotter.log.gig.domain.model.vehicle

import org.junit.Assert.*
import org.junit.Test

class DashVehiclesTest {
    @Test fun `usage counts latest vehicles across four dashes and sums their own GPS spans`() {
        val stamps = listOf(stamp("a", "civic", 10.0), stamp("b", "civic", 30.0, sequence = 2),
            stamp("c", "malibu", 45.0, sequence = 3), stamp("d", "civic", 70.0, sequence = 4),
            stamp("a", "malibu", 99.0, sequence = 5, kind = DashVehicleKind.SWAP))
        val history = DashVehicles.perDash(stamps)
        assertEquals(VehicleUsage(2, 45.0), DashVehicles.usageFor(history, "malibu", 100.0))
        assertEquals(VehicleUsage(2, 45.0), DashVehicles.usageFor(history, "civic", 100.0))
    }

    @Test fun `usage unknown vehicle dash bounds the previous span`() {
        val history = listOf(DashVehicle("a", "car", 1, 10.0), DashVehicle("b", null, 2, 30.0))
        assertEquals(VehicleUsage(1, 20.0), DashVehicles.usageFor(history, "car", 100.0))
    }

    @Test fun `usage open last span uses GPS now and skips it when unknown`() {
        val history = listOf(DashVehicle("a", "car", 1, 10.0))
        assertEquals(VehicleUsage(1, 15.0), DashVehicles.usageFor(history, "car", 25.0))
        assertEquals(VehicleUsage(1, null), DashVehicles.usageFor(history, "car", null))
        assertEquals(VehicleUsage(1, 0.0), DashVehicles.usageFor(history, "car", 10.0))
    }

    @Test fun `usage skips unknown start and end without losing dash count or later known spans`() {
        val history = listOf(DashVehicle("a", "car", 1, 10.0), DashVehicle("b", "car", 2, null),
            DashVehicle("c", "car", 3, 40.0))
        assertEquals(VehicleUsage(3, 20.0), DashVehicles.usageFor(history, "car", 60.0))
        assertEquals(VehicleUsage(3, null), DashVehicles.usageFor(history, "car", null))
    }

    @Test fun `usage without stamped dashes has no count or known miles`() {
        assertEquals(VehicleUsage(0, null), DashVehicles.usageFor(emptyList(), "car", 100.0))
    }

    @Test fun `usage skips reset and non finite GPS spans`() {
        for (end in listOf(5.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(VehicleUsage(1, null), DashVehicles.usageFor(listOf(DashVehicle("a", "car", 1, 10.0)), "car", end))
        }
    }

    @Test fun `swap is unlocked without live dashes`() {
        assertFalse(DashVehicles.swapLocked(emptyList(), 600_101, 600_000))
    }
    @Test fun `swap is unlocked at the inclusive window edge`() {
        assertFalse(DashVehicles.swapLocked(listOf(LiveDash("dash", 100)), 600_100, 600_000))
    }
    @Test fun `swap is locked past the window`() {
        assertTrue(DashVehicles.swapLocked(listOf(LiveDash("dash", 100)), 600_101, 600_000))
    }
    @Test fun `one old dash locks both live dashes`() {
        assertTrue(DashVehicles.swapLocked(listOf(LiveDash("new", 600_100), LiveDash("old", 100)), 600_101, 600_000))
    }

    private fun stamp(session: String = "dash", vehicle: String? = "malibu", gps: Double? = 10.0,
        at: Long = 100, sequence: Long = 1, kind: DashVehicleKind = DashVehicleKind.START) =
        DashVehicleStamp(session, vehicle, gps, at, sequence, kind)
    private val live = listOf(LiveDash("dash", 100))
    private fun decide(active: String? = "malibu", stamps: List<DashVehicleStamp> = emptyList(), now: Long = 100) =
        DashVehicles.decide(live, active, stamps, now, 600_000)
    private val dashes = listOf(DashVehicle("a", "malibu", 100, 10.0),
        DashVehicle("b", "civic", 200, 30.0), DashVehicle("c", "malibu", 300, 50.0))

    @Test fun `latest sequence wins while first time gps and ordering survive swaps and clock rollback`() {
        val stamps = listOf(stamp(vehicle = "civic", gps = 99.0, at = 50, sequence = 3, kind = DashVehicleKind.SWAP),
            stamp(session = "second", at = 90, sequence = 2), stamp())
        assertEquals(listOf(DashVehicle("dash", "civic", 100, 10.0), DashVehicle("second", "malibu", 90, 10.0)),
            DashVehicles.perDash(stamps))
    }
    @Test fun `miles clip three dash spans and keep the last open`() {
        assertEquals(30.0, DashVehicles.milesFor(dashes, "malibu", 20.0, 70.0)!!, 0.0)
        assertEquals(20.0, DashVehicles.milesFor(dashes, "civic", 20.0, 70.0)!!, 0.0)
        assertEquals(0.0, DashVehicles.milesFor(dashes, "unknown", 20.0, 70.0)!!, 0.0)
        assertEquals(20.0, DashVehicles.milesFor(dashes, "civic", 30.0, 50.0)!!, 0.0)
    }
    @Test fun `before first stamp and missing history are uncovered`() {
        assertNull(DashVehicles.milesFor(dashes, "malibu", 9.0, 70.0))
        assertNull(DashVehicles.milesFor(emptyList(), "malibu", 20.0, 70.0))
    }
    @Test fun `Sol retired only vehicle then added B after eleven minutes leaves its readings uncovered`() {
        val history = listOf(stamp(session = "a", vehicle = "A", gps = 100.0, at = 0),
            stamp(vehicle = null, gps = 120.0, at = 100, sequence = 2))
        assertEquals(listOf(DashVehicleAction.NextDash("dash", "B")),
            decide(active = "B", stamps = history, now = 100 + 11 * 60_000))
        val dashes = DashVehicles.perDash(history)
        assertNull(DashVehicles.milesFor(dashes, "B", 130.0, 140.0))
        assertNull(DashVehicles.milesFor(dashes, "A", 130.0, 140.0))
    }
    @Test fun `unknown vehicle bounds the previous dash and leaves later known spans usable`() {
        val unknown = dashes.map { if (it.sessionId == "b") it.copy(vehicleId = null) else it }
        assertEquals(20.0, DashVehicles.milesFor(unknown, "malibu", 10.0, 30.0)!!, 0.0)
        assertNull(DashVehicles.milesFor(unknown, "malibu", 20.0, 40.0))
        assertNull(DashVehicles.milesFor(unknown, "malibu", 30.0, 50.0))
        assertNull(DashVehicles.milesFor(unknown, "malibu", 40.0, 60.0))
        assertEquals(20.0, DashVehicles.milesFor(unknown, "malibu", 50.0, 70.0)!!, 0.0)
        assertEquals(0.0, DashVehicles.milesFor(unknown, "civic", 50.0, 70.0)!!, 0.0)
    }
    @Test fun `unknown start inside the window is uncovered but a later known span works`() {
        val missing = dashes.map { if (it.sessionId == "b") it.copy(startGps = null) else it }
        assertNull(DashVehicles.milesFor(missing, "malibu", 20.0, 70.0))
        assertEquals(20.0, DashVehicles.milesFor(missing, "malibu", 50.0, 70.0)!!, 0.0)
    }
    @Test fun `non increasing gps starts mean uncovered history`() {
        for (gps in listOf(10.0, 5.0)) {
            assertNull(DashVehicles.milesFor(listOf(dashes[0], dashes[1].copy(startGps = gps)), "malibu", 20.0, 70.0))
        }
    }
    @Test fun `invalid windows and gps are uncovered`() {
        assertNull(DashVehicles.milesFor(dashes, "malibu", 70.0, 20.0))
        assertNull(DashVehicles.milesFor(dashes, "malibu", Double.NaN, 70.0))
        assertNull(DashVehicles.milesFor(listOf(dashes[0].copy(startGps = Double.POSITIVE_INFINITY)), "malibu", 20.0, 70.0))
    }
    @Test fun `new dash within the inclusive window starts once`() {
        assertEquals(listOf(DashVehicleAction.Stamp("dash", "malibu", DashVehicleKind.START)), decide(now = 600_100))
    }
    @Test fun `no active vehicle starts with unknown vehicle`() {
        for (now in listOf(100L, 600_100L, 600_101L)) {
            assertEquals(listOf(DashVehicleAction.Stamp("dash", null, DashVehicleKind.START)), decide(active = null, now = now))
        }
    }
    @Test fun `no active vehicle on a stamped dash does nothing`() {
        for (vehicle in listOf(null, "malibu")) for (now in listOf(100L, 600_101L)) {
            assertTrue(decide(active = null, stamps = listOf(stamp(vehicle = vehicle)), now = now).isEmpty())
        }
    }
    @Test fun `unknown to named vehicle inside first stamp window swaps and announces this dash`() {
        assertEquals(listOf(DashVehicleAction.Stamp("dash", "civic", DashVehicleKind.SWAP),
            DashVehicleAction.ThisDash("dash", "civic")), decide("civic", listOf(stamp(vehicle = null)), now = 600_100))
    }
    @Test fun `unknown to named vehicle after first stamp window announces next dash only`() {
        assertEquals(listOf(DashVehicleAction.NextDash("dash", "civic")),
            decide("civic", listOf(stamp(vehicle = null)), now = 600_101))
    }
    @Test fun `swap inside window stamps and announces this dash`() {
        assertEquals(listOf(DashVehicleAction.Stamp("dash", "civic", DashVehicleKind.SWAP),
            DashVehicleAction.ThisDash("dash", "civic")), decide("civic", listOf(stamp()), now = 600_100))
    }
    @Test fun `swap outside window announces next dash without a stamp`() {
        assertEquals(listOf(DashVehicleAction.NextDash("dash", "civic")), decide("civic", listOf(stamp()), now = 600_101))
    }
    @Test fun `latest swap does not extend the first stamp window`() {
        assertEquals(listOf(DashVehicleAction.NextDash("dash", "malibu")), decide("malibu", listOf(stamp(),
            stamp(vehicle = "civic", at = 600_000, sequence = 2, kind = DashVehicleKind.SWAP)), now = 600_101))
    }
    /** Review r2 (Sol): first seen at minute 11 → START(null) stamped then; a restart at minute 12 with B active must not swap. */
    @Test fun `a late unknown start never reopens the swap window after a restart`() {
        val late = stamp(vehicle = null, at = 660_100)
        assertEquals(listOf(DashVehicleAction.NextDash("dash", "civic")), decide("civic", listOf(late), now = 720_100))
    }
    @Test fun `same vehicle does nothing`() { assertTrue(decide(stamps = listOf(stamp())).isEmpty()) }
    @Test fun `unstamped past window starts unknown never with todays active vehicle`() {
        assertEquals(listOf(DashVehicleAction.Stamp("dash", null, DashVehicleKind.START)), decide(now = 600_101))
    }
    @Test fun `two live dashes each get a start`() {
        assertEquals(listOf(DashVehicleAction.Stamp("dash", "malibu", DashVehicleKind.START),
            DashVehicleAction.Stamp("second", "malibu", DashVehicleKind.START)),
            DashVehicles.decide(live + LiveDash("second", 110), "malibu", emptyList(), 120, 600_000))
    }
    @Test fun `unknown kind falls back to swap`() {
        assertEquals(DashVehicleKind.SWAP, DashVehicleKind.fromName("FUTURE"))
        assertEquals(DashVehicleKind.START, DashVehicleKind.fromName("START"))
    }
}
