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

class HudStateMapperTest {
    private fun map(rest: Boolean = false, net: SessionNet? = SessionNet("dash", 60.0, 5.0, 2.0, 7.0, 20.0),
        count: Boolean = false, last: SessionRecord? = lastDash()) = HudStateMapper.map(
        region = if (rest) null else PlatformRegion(platform = Platform.DoorDash, mode = Mode.Online,
            session = Session("dash", 1_000)),
        flow = Flow.Idle, platform = Platform.DoorDash, switchable = emptyList(),
        earnings = 90.0, miles = 20.0, lastSession = last, net = net,
        countExpenses = count, goal = null, todayProfit = null, hasActiveCard = !rest,
        logOpen = false, gas = GasRowModel(3.5f, true),
    )

    @Test
    fun `live hero reads the session net`() {
        assertEquals(67.0, map().hero.profit!!, 0.0)
    }

    @Test
    fun `resting hero reads the last dash net`() {
        val state = map(rest = true)
        assertEquals(HudMode.RESTING, state.mode)
        assertEquals(67.0, state.hero.profit!!, 0.0)
        assertEquals(Platform.DoorDash, state.platform)
    }

    @Test
    fun `null net shows the em dash hero not zero`() {
        assertNull(map(net = null).hero.profit)
        assertEquals(EMPTY_VALUE, moneyOrEmpty(map(net = null).hero.profit))
    }

    @Test
    fun `count expenses preference reaches kept`() {
        assertEquals(60.0, map(count = true).hero.profit!!, 0.0)
    }

    @Test
    fun `resting profit per hour is kept over online hours`() {
        assertEquals(Formats.money(30.0), map(rest = true, count = true).resting!!.profitPerHour)
    }

    @Test
    fun `resting profit per hour without an end is the em dash`() {
        assertEquals(EMPTY_VALUE, map(rest = true, last = lastDash().copy(endedAt = null)).resting!!.profitPerHour)
    }

    @Test
    fun `first run when no session and no last dash`() {
        val state = map(rest = true, net = null, last = null)
        assertTrue(state.firstRun)
        assertNull(state.resting)
        assertNull(state.platform)
        assertEquals(TopRowMapper.firstRun(), state.top)
    }
}
