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
        last: SessionRecord? = lastDash(), cue: Boolean = false, logOpen: Boolean = false,
        odometerLoaded: Boolean = true, odometerCue: Boolean = false) = HudStateMapper.map(
        region = if (rest) null else PlatformRegion(platform = Platform.DoorDash, mode = Mode.Online,
            session = Session("dash", 1_000)),
        flow = Flow.Idle, platform = Platform.DoorDash, switchable = emptyList(),
        earnings = 90.0, miles = 20.0, lastSession = last, net = net,
        goal = null, todayProfit = null, hasActiveCard = !rest, hasOfferCard = false,
        logOpen = logOpen, gas = GasRowModel(3.5f, true), cashTipCue = cue,
        odometerLoaded = odometerLoaded, odometerCue = odometerCue,
    )

    @Test
    fun `live hero reads the session net`() {
        assertEquals(67.0, map().hero.profit!!, 0.0)
    }

    @Test fun `odometer cue reaches both docks and respects loading`() {
        for (rest in listOf(false, true)) {
            for (logOpen in listOf(false, true)) {
                for (loaded in listOf(false, true)) {
                    assertEquals(if (loaded) DockItemState.ACCENT else DockItemState.DISABLED,
                        map(rest = rest, logOpen = logOpen, odometerLoaded = loaded, odometerCue = true)
                            .dock.items.single { it.kind == DockKind.ODOMETER }.state)
                }
            }
        }
    }

    @Test fun `odometer is disabled until loaded`() {
        for (rest in listOf(false, true)) {
            for (logOpen in listOf(false, true)) {
                for (loaded in listOf(false, true)) {
                    val item = map(rest = rest, logOpen = logOpen, odometerLoaded = loaded)
                        .dock.items.single { it.kind == DockKind.ODOMETER }
                    assertEquals(if (loaded) DockItemState.NORMAL else DockItemState.DISABLED, item.state)
                }
            }
        }
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
    fun `resting profit per hour is kept over online hours`() {
        assertEquals(Formats.money(33.5), map(rest = true).resting!!.profitPerHour)
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
    @Test fun `cash tip cue reaches the dock`() {
        assertEquals(DockItemState.ACCENT, map(cue = true).dock.items.single { it.kind == DockKind.CASH_TIP }.state)
        assertEquals(DockItemState.NORMAL, map(cue = false).dock.items.single { it.kind == DockKind.CASH_TIP }.state)
        assertFalse(map(rest = true, last = null, cue = true).dock.items.any { it.kind == DockKind.CASH_TIP })
    }
    @Test fun `profit per hour preserves unknowns and real zero`() {
        assertNull(profitPerHour(null, 3_600_000))
        assertNull(profitPerHour(10.0, null))
        assertNull(profitPerHour(10.0, 0))
        assertNull(profitPerHour(10.0, -1))
        assertEquals(0.0, profitPerHour(0.0, 3_600_000)!!, 0.0)
        assertEquals(20.0, profitPerHour(10.0, 1_800_000)!!, 0.0)
    }

    @Test fun `log open at rest keeps resting and shows Now`() {
        val state = map(rest = true, logOpen = true)
        assertEquals(HudMode.RESTING, state.mode)
        assertTrue(state.logOpen)
        assertEquals(DockItemState.LIT, state.dock.items.single { it.kind == DockKind.NOW }.state)
        assertFalse(state.dock.items.any { it.kind == DockKind.LOG })
        assertTrue(state.dock.items.any { it.kind == DockKind.SETTINGS })
    }

}
