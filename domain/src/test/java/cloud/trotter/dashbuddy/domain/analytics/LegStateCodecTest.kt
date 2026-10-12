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
            dropoffArrivalOdometers = mapOf("DA" to ArrivalReading(1_000, 709.06), "DB" to ArrivalReading(2_000, 712.45)),
        )
        assertEquals(state, LegStateCodec.decode(LegStateCodec.encode(state)))
    }

    @Test
    fun `1310 legacy blob without arrival readings decodes with an empty map and keeps its other facts`() {
        val state = LegStateCodec.decode("""{"prevLegOdometer":112.0,"pendingDropoffLegs":{"D1":7.0}}""")
        assertEquals(LegState(prevLegOdometer = 112.0, pendingDropoffLegs = mapOf("D1" to 7.0)), state)
        assertEquals(emptyMap<String, ArrivalReading>(), state.dropoffArrivalOdometers)
    }

    @Test
    fun `1310 arrival reading with null odometer round-trips`() {
        val state = LegState(dropoffArrivalOdometers = mapOf("A" to ArrivalReading(1_000, null)))
        val encoded = LegStateCodec.encode(state)
        assertEquals("""{"dropoffArrivalOdometers":{"A":{"arrivedAt":1000,"odometer":null}}}""", encoded)
        assertEquals(state, LegStateCodec.decode(encoded))
    }

    @Test
    fun `1310 legacy numeric and null arrival readings fail closed to empty state`() {
        for (reading in listOf("112.0", "null")) {
            // Nonempty valid fields prove the serialization failure discards the WHOLE state.
            val blob = """{"prevLegOdometer":112.0,"pendingDropoffLegs":{"A":7.0},"dropoffArrivalOdometers":{"A":$reading}}"""
            assertEquals(LegState(), LegStateCodec.decode(blob))
        }
    }

    @Test
    fun `an empty leg state round-trips`() {
        assertEquals(LegState(), LegStateCodec.decode(LegStateCodec.encode(LegState())))
    }

    @Test
    fun `1310 empty arrival map still round-trips other allocation facts`() {
        val state = LegState(prevLegOdometer = 112.0, pendingDropoffLegs = mapOf("A" to 7.0))
        assertEquals(state, LegStateCodec.decode(LegStateCodec.encode(state)))
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
