package cloud.trotter.log.gig.feature.bubble.hud

import org.junit.Test
import org.junit.Assert.*
import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.domain.analytics.SessionRecord
import cloud.trotter.log.gig.domain.state.*
import cloud.trotter.log.gig.domain.model.cards.*
import cloud.trotter.log.gig.domain.model.event.AppEventType

class HudStateMapperTest {
    private companion object { const val T_NOW = 99_000_000L }

    private fun liveShift(earnings: Double? = 67.0, cash: Double? = 5.0) =
        DisplayedShift(Platform.DoorDash, "dash", live = true, earnings = earnings, cashTips = cash, record = null)
    private fun restedShift(row: SessionRecord = lastDash(), earnings: Double? = 67.0, cash: Double? = 0.0) =
        DisplayedShift(row.platform, row.sessionId, live = false, earnings = earnings, cashTips = cash, record = row)

    private fun map(rest: Boolean = false, shift: DisplayedShift? = if (rest) restedShift() else liveShift(),
        last: SessionRecord? = lastDash(), cue: Boolean = false, logOpen: Boolean = false,
        odometerLoaded: Boolean = true, odometerCue: Boolean = false, odometerShown: Boolean = true,
        goal: Double? = null, today: Double? = null, goalSheetOpen: Boolean = false,
        editedGoalKey: String? = null, region: PlatformRegion? = if (rest) null else PlatformRegion(
            platform = Platform.DoorDash, mode = Mode.Online, session = Session("dash", 1_000)),
    ) = HudStateMapper.map(
        region = region,
        flow = Flow.Idle, platform = Platform.DoorDash, switchable = emptyList(),
        shift = shift, miles = 20.0, lastSession = last,
        goal = goal, todayEarned = today, hasActiveCard = !rest, hasOfferCard = false,
        logOpen = logOpen, gas = GasRowModel(3.5f, true), cashTipCue = cue,
        odometerLoaded = odometerLoaded, odometerCue = odometerCue, odometerShown = odometerShown,
        goalDay = java.time.LocalDate.of(2026, 10, 18), mark = MarkRead.Loaded(null),
        goalSheetOpen = goalSheetOpen, editedGoalKey = editedGoalKey,
    )

    @Test fun `odometer visibility reaches resting first run live and log docks`() {
        for (rest in listOf(false, true)) {
            for (last in listOf(null, lastDash())) {
                for (logOpen in listOf(false, true)) {
                    val hidden = map(rest = rest, last = last, logOpen = logOpen, odometerCue = true, odometerShown = false)
                    val shown = map(rest = rest, last = last, logOpen = logOpen, odometerCue = true)
                    assertFalse(hidden.dock.items.any { it.kind == DockKind.ODOMETER })
                    assertEquals(shown.dock.items.filter { it.kind != DockKind.ODOMETER }, hidden.dock.items)
                }
            }
        }
    }

    @Test fun `goal sheet suppression reaches the hero while Met remains visible`() {
        val open = map(goal = 90.0, today = 96.0, goalSheetOpen = true)
        assertEquals(GoalPillState.Met, open.hero.goal)
        assertNull(open.hero.celebrateKey)
        val closing = map(goal = 90.0, today = 96.0, editedGoalKey = "2026-10-18|earned|9000")
        assertEquals(GoalPillState.Met, closing.hero.goal)
        assertNull(closing.hero.celebrateKey)
        assertEquals("2026-10-18|earned|9000", map(goal = 90.0, today = 96.0).hero.celebrateKey)
    }

    @Test
    fun `live hero reads the displayed shift's earnings and cash apart`() {
        val hero = map().hero
        assertEquals(67.0, hero.earnings!!, 0.0)
        assertEquals(5.0, hero.cashTips!!, 0.0)
    }

    @Test
    fun `no cash mark without cash`() {
        assertNull(map(shift = liveShift(cash = 0.0)).hero.cashTips)
        assertNull(map(shift = liveShift(cash = null)).hero.cashTips)
    }

    @Test
    fun `the top row has no earnings cell and keeps the live span`() {
        assertEquals(TopRowMapper.live(1_000, 20.0), map().top)
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
    fun `resting hero reads the displayed shift`() {
        val state = map(rest = true)
        assertEquals(HudMode.RESTING, state.mode)
        assertEquals(67.0, state.hero.earnings!!, 0.0)
        assertEquals(Platform.DoorDash, state.platform)
    }

    @Test
    fun `resting chip stats and top row are the displayed shift's, not the newer cross-platform last shift`() {
        val uber = lastDash().copy(sessionId = "uber", platform = Platform.Uber, startedAt = 9_000_000,
            endedAt = 9_600_000, miles = 3.0, deliveries = 1, offersAccepted = 1, offersReceived = 1)
        val doordash = lastDash().copy(deliveries = 7)
        val state = map(rest = true, shift = restedShift(doordash), last = uber)
        assertEquals(Platform.DoorDash, state.platform)
        val resting = state.resting!!
        assertEquals(7, resting.deliveries)
        assertEquals(doordash.startedAt, resting.startedAt)
        assertEquals(TopRowMapper.resting(doordash), state.top)
    }

    @Test
    fun `resting with no acceptable row shows unknowns, never another shift`() {
        val pending = DisplayedShift(Platform.DoorDash, null, live = false, earnings = null, cashTips = null, record = null)
        val state = map(rest = true, shift = pending)
        assertNull(state.hero.earnings)
        assertNull(state.hero.cashTips)
        // Pending shift data is em dashes, never the first-run "No shift yet" (a null model).
        assertFalse(state.firstRun)
        assertEquals(RestingModel(null, null, null, OnlineSpan.Unknown, null, null, null), state.resting)
        assertNull(state.resting!!.earnedPerHour(T_NOW))
        assertEquals(TopRowMapper.firstRun(), state.top)
        assertEquals(Platform.DoorDash, state.platform)
    }

    @Test
    fun `resting during an end grace shows the live shift`() {
        val graced = PlatformRegion(platform = Platform.DoorDash, mode = Mode.Offline, session = Session("dash", 1_000))
        val open = lastDash().copy(sessionId = "dash", endedAt = null)
        val state = map(rest = true, region = graced,
            shift = liveShift(earnings = 41.0).copy(record = open), last = lastDash().copy(sessionId = "older"))
        assertEquals(HudMode.RESTING, state.mode)
        assertEquals(41.0, state.hero.earnings!!, 0.0)
        assertEquals(TopRowMapper.live(1_000, 20.0), state.top)
        val resting = state.resting!!
        assertEquals(open.startedAt, resting.startedAt)
        assertNull(resting.endedAt)
        // Earned/hr ticks from the live start until the shift ends: $41 over one hour, then two.
        assertEquals(OnlineSpan.Ticking(1_000), resting.online)
        assertEquals(41.0, resting.earnedPerHour(1_000 + 3_600_000)!!, 1e-9)
        assertEquals(20.5, resting.earnedPerHour(1_000 + 7_200_000)!!, 1e-9)
    }

    @Test
    fun `an end grace with the row still loading keeps the live start and earnings`() {
        val graced = PlatformRegion(platform = Platform.DoorDash, mode = Mode.Offline, session = Session("dash", 1_000))
        val resting = map(rest = true, region = graced, shift = liveShift(earnings = 41.0)).resting!!
        assertEquals(1_000L, resting.startedAt)
        assertEquals(41.0, resting.earnedPerHour(1_000 + 3_600_000)!!, 1e-9)
        assertNull(resting.deliveries)
    }

    @Test
    fun `null earnings show the em dash hero not zero`() {
        assertNull(map(shift = liveShift(earnings = null)).hero.earnings)
        assertEquals(EMPTY_VALUE, moneyOrEmpty(map(shift = liveShift(earnings = null)).hero.earnings))
    }

    @Test
    fun `resting earned per hour is the shift's earnings over online hours`() {
        val resting = map(rest = true).resting!!
        assertEquals(OnlineSpan.Fixed(7_200_000), resting.online)
        assertEquals(33.5, resting.earnedPerHour(T_NOW)!!, 1e-9)
    }

    @Test
    fun `resting earned per hour without an end or earnings is unknown`() {
        assertNull(map(rest = true, shift = restedShift(lastDash().copy(endedAt = null))).resting!!.earnedPerHour(T_NOW))
        assertNull(map(rest = true, shift = restedShift(earnings = null)).resting!!.earnedPerHour(T_NOW))
    }

    @Test
    fun `first run when no session and no last dash`() {
        val state = map(rest = true, shift = null, last = null)
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
    @Test fun `per hour preserves unknowns and real zero`() {
        assertNull(perHour(null, 3_600_000))
        assertNull(perHour(10.0, null))
        assertNull(perHour(10.0, 0))
        assertNull(perHour(10.0, -1))
        assertEquals(0.0, perHour(0.0, 3_600_000)!!, 0.0)
        assertEquals(20.0, perHour(10.0, 1_800_000)!!, 0.0)
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
