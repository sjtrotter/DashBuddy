package cloud.trotter.dashbuddy.feature.bubble.hud.expense

import cloud.trotter.dashbuddy.feature.bubble.hud.lastDash
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ExpenseOwnerTest {
    private val zone = ZoneId.of("America/Chicago")
    private fun at(text: String) = Instant.parse(text).toEpochMilli()
    @Test fun `active wins over every last dash`() {
        assertEquals("live", owner("live", lastDash(), 0, zone))
        assertEquals("live", owner("live", null, 0, zone))
    }
    @Test fun `last dash must have ended on the current local date`() {
        val now = at("2026-10-19T06:00:00Z")
        assertEquals("last", owner(null, lastDash().copy(endedAt = at("2026-10-19T05:00:00Z")), now, zone))
        assertNull(owner(null, lastDash().copy(endedAt = at("2026-10-19T04:59:59Z")), now, zone))
        assertNull(owner(null, lastDash().copy(endedAt = null), now, zone))
        assertNull(owner(null, null, now, zone))
    }
    @Test fun `local day can span two UTC dates and daylight saving transitions`() {
        assertEquals("last", owner(null, lastDash().copy(endedAt = at("2026-10-19T23:00:00Z")),
            at("2026-10-20T04:59:59Z"), zone))
        assertEquals("last", owner(null, lastDash().copy(endedAt = at("2026-11-01T06:30:00Z")),
            at("2026-11-01T07:30:00Z"), zone))
    }
}
