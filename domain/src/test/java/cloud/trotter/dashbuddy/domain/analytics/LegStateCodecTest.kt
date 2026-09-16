package cloud.trotter.dashbuddy.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
            // #1108: the job scope for those legs + a span-basis reservation awaiting its drop.
            dropoffLegJobIds = mapOf("DA" to "J1", "DB" to "J1"),
            spanApportionedMiles = mapOf("DC" to 4.4),
        )
        assertEquals(state, LegStateCodec.decode(LegStateCodec.encode(state)))
    }

    @Test
    fun `a pre-#1108 blob decodes with empty scope and reservation maps`() {
        // The compatibility that made two PARALLEL maps the right shape rather than widening
        // `pendingDropoffLegs` into a record: an in-flight session's persisted blob still decodes,
        // legs and all, instead of failing closed to an empty state.
        val legacyBlob =
            """{"prevLegOdometer":712.45,"pendingStoreLegs":[],"pendingDropoffLegs":{"DA":2.98}}"""
        val decoded = LegStateCodec.decode(legacyBlob)

        assertEquals(mapOf("DA" to 2.98), decoded.pendingDropoffLegs)
        assertEquals(emptyMap<String, String>(), decoded.dropoffLegJobIds)
        assertEquals(emptyMap<String, Double>(), decoded.spanApportionedMiles)
        // And an unknown job reads as "this job's" — the conservative direction.
        assertTrue(decoded.dropoffLegInJob("DA", "J1"))
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
