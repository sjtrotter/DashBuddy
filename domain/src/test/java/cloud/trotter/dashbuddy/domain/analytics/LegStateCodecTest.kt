package cloud.trotter.dashbuddy.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

/** #688 phase B — the [LegStateCodec] round-trip + fail-closed decode SSOT for `legStateJson`. */
class LegStateCodecTest {

    @Test
    fun `round-trips a populated leg state`() {
        val state = LegState(
            prevLegOdometer = 712.45,
            completionHighWater = 1234567L,
            sessionMeasured = 9.0,
            sessionAllocated = 3.2,
            jobMileageBudgets = mapOf(
                "J1" to JobMileageBudget(712.45, 7.45, 3.2),
                "J2" to JobMileageBudget(712.45, anchored = false),
            ),
            pendingStoreLegs = listOf(
                PendingStoreLeg("PA", "J1", "Bill Miller BBQ", 0.22),
                PendingStoreLeg("PB", "J1", null, 0.16),
            ),
            pendingDropoffLegs = mapOf("DA" to 2.98, "DB" to 3.39),
        )
        assertEquals(state, LegStateCodec.decode(LegStateCodec.encode(state)))
    }

    @Test
    fun `an empty leg state round-trips`() {
        assertEquals(LegState(), LegStateCodec.decode(LegStateCodec.encode(LegState())))
    }

    @Test
    fun `decode fails closed to empty on null, blank, and garbage`() {
        assertEquals(LegState(), LegStateCodec.decode(null))
        assertEquals(LegState(), LegStateCodec.decode(""))
        assertEquals(LegState(), LegStateCodec.decode("   "))
        assertEquals(LegState(), LegStateCodec.decode("not valid json {{"))
        assertEquals(LegState(), LegStateCodec.decode("""{"prevLegOdometer":"oops"}"""))
    }
}
