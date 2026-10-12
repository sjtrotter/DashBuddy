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
            dropoffArrivalOdometers = mapOf("DA" to 709.06, "DB" to 712.45),
        )
        assertEquals(state, LegStateCodec.decode(LegStateCodec.encode(state)))
    }

    @Test
    fun `1310 legacy blob without arrival odometers decodes to an empty map`() {
        val state = LegStateCodec.decode("""{"prevLegOdometer":112.0,"pendingDropoffLegs":{"D1":7.0}}""")
        assertEquals(LegState(prevLegOdometer = 112.0, pendingDropoffLegs = mapOf("D1" to 7.0)), state)
        assertEquals(emptyMap<String, Double?>(), state.dropoffArrivalOdometers)
    }

    @Test
    fun `1310 null first arrival odometer round-trips with its key present`() {
        val state = LegState(dropoffArrivalOdometers = mapOf("A" to null))
        val encoded = LegStateCodec.encode(state)
        assertEquals("""{"dropoffArrivalOdometers":{"A":null}}""", encoded)
        assertEquals(state, LegStateCodec.decode(encoded))
    }

    @Test
    fun `1310 legacy blob with non-null arrival odometers still decodes`() {
        val state = LegStateCodec.decode("""{"dropoffArrivalOdometers":{"A":112.0}}""")
        assertEquals(LegState(dropoffArrivalOdometers = mapOf("A" to 112.0)), state)
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
