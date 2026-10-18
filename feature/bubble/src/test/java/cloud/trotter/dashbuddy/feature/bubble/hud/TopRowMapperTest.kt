package cloud.trotter.dashbuddy.feature.bubble.hud

import org.junit.Test
import org.junit.Assert.*
import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.analytics.SessionNet
import cloud.trotter.dashbuddy.domain.state.*
import cloud.trotter.dashbuddy.domain.model.cards.*
import cloud.trotter.dashbuddy.domain.model.event.AppEventType

class TopRowMapperTest {
    @Test
    fun `live uses the counter earnings the session start and live miles`() {
        val model = TopRowMapper.live(71.2, 1_000, 42.5)
        assertEquals(Formats.money(71.2), model.earned)
        assertEquals(OnlineSpan.Ticking(1_000), model.online)
        assertEquals(Formats.decimal(42.5, 1), model.miles)
        assertFalse(model.dimmed)
    }

    @Test
    fun `live null earnings shows the em dash not zero`() {
        assertEquals(EMPTY_VALUE, TopRowMapper.live(null, 1_000, 0.0).earned)
    }

    @Test
    fun `live without a session start is unknown online`() {
        assertEquals(OnlineSpan.Unknown, TopRowMapper.live(0.0, null, 0.0).online)
    }

    @Test
    fun `resting uses the last dash and dims`() {
        val model = TopRowMapper.resting(lastDash())
        assertEquals(Formats.money(100.0), model.earned)
        assertEquals(Formats.decimal(42.5, 1), model.miles)
        assertTrue(model.dimmed)
    }

    @Test
    fun `resting null reported earnings shows the em dash`() {
        assertEquals(EMPTY_VALUE, TopRowMapper.resting(lastDash().copy(reportedEarnings = null)).earned)
    }

    @Test
    fun `resting online is end minus start`() {
        assertEquals(OnlineSpan.Fixed(7_200_000), TopRowMapper.resting(lastDash()).online)
    }

    @Test
    fun `resting with no end is unknown online`() {
        assertEquals(OnlineSpan.Unknown, TopRowMapper.resting(lastDash().copy(endedAt = null)).online)
    }

    @Test
    fun `resting null miles shows the em dash`() {
        assertEquals(EMPTY_VALUE, TopRowMapper.resting(lastDash().copy(miles = null)).miles)
    }

    @Test
    fun `first run is three em dashes`() {
        assertEquals(TopRowModel(EMPTY_VALUE, OnlineSpan.Unknown, EMPTY_VALUE, true), TopRowMapper.firstRun())
    }
}
