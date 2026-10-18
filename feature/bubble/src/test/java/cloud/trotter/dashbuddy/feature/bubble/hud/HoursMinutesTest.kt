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

class HoursMinutesTest {
    @Test
    fun `under an hour gives minutes only`() {
        assertEquals(HoursMinutes(0, 41), HoursMinutes.of(41 * 60_000L))
    }

    @Test
    fun `whole hours floor the minutes`() {
        assertEquals(HoursMinutes(2, 41), HoursMinutes.of(2 * 3_600_000L + 41 * 60_000 + 59_999))
    }

    @Test
    fun `fifty nine seconds is zero minutes`() {
        assertEquals(HoursMinutes(0, 0), HoursMinutes.of(59_000))
    }

    @Test
    fun `negative span clamps to zero`() {
        assertEquals(HoursMinutes(0, 0), HoursMinutes.of(-60_000))
    }
}
