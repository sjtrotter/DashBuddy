package cloud.trotter.dashbuddy.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

/** #688 phase B — the [LegStateCodec] round-trip + fail-closed decode SSOT for `legStateJson`. */
class LegStateCodecTest {

    @Test
    fun `round-trips a populated leg state`() {
        val state = LegState(
            prevLegOdometer = 712.45,
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
