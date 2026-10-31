package cloud.trotter.log.gig.feature.bubble.hud

import org.junit.Test
import org.junit.Assert.*
import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.domain.analytics.SessionRecord
import cloud.trotter.log.gig.domain.state.*
import cloud.trotter.log.gig.domain.model.cards.*
import cloud.trotter.log.gig.domain.model.event.AppEventType

class TopRowMapperTest {
    @Test
    fun `live uses the session start and live miles`() {
        val model = TopRowMapper.live(1_000, 42.5)
        assertEquals(OnlineSpan.Ticking(1_000), model.online)
        assertEquals(Formats.decimal(42.5, 1), model.miles)
        assertFalse(model.dimmed)
    }

    @Test
    fun `live without a session start is unknown online`() {
        assertEquals(OnlineSpan.Unknown, TopRowMapper.live(null, 0.0).online)
    }

    @Test
    fun `resting uses the last dash and dims`() {
        val model = TopRowMapper.resting(lastDash())
        assertEquals(Formats.decimal(42.5, 1), model.miles)
        assertTrue(model.dimmed)
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
    fun `first run is unknown online and an em dash`() {
        assertEquals(TopRowModel(OnlineSpan.Unknown, EMPTY_VALUE, true), TopRowMapper.firstRun())
    }
}
