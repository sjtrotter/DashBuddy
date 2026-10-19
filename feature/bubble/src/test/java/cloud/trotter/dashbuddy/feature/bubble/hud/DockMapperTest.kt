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

class DockMapperTest {
    @Test fun `odometer lights while the cue holds`() {
        for (mode in HudMode.entries.filter { it != HudMode.OFFER }) {
            for (cue in listOf(false, true)) {
                val item = DockMapper.map(mode, odometerLoaded = true, odometerCue = cue)
                    .items.single { it.kind == DockKind.ODOMETER }
                assertEquals(if (cue) DockItemState.ACCENT else DockItemState.NORMAL, item.state)
            }
        }
        assertTrue(DockMapper.map(HudMode.OFFER, odometerLoaded = true, odometerCue = true).items.isEmpty())
    }

    @Test fun `disabled wins over the cue`() {
        for (mode in HudMode.entries.filter { it != HudMode.OFFER }) {
            assertEquals(DockItemState.DISABLED,
                DockMapper.map(mode, odometerLoaded = false, odometerCue = true)
                    .items.single { it.kind == DockKind.ODOMETER }.state)
        }
    }

    @Test fun `odometer is disabled until loaded`() {
        for (mode in HudMode.entries.filter { it != HudMode.OFFER }) {
            for (loaded in listOf(false, true)) {
                val item = DockMapper.map(mode, odometerLoaded = loaded).items.single { it.kind == DockKind.ODOMETER }
                assertEquals(if (loaded) DockItemState.NORMAL else DockItemState.DISABLED, item.state)
            }
        }
    }

    @Test
    fun `live dock is cash tip expense odometer log`() {
        val items = DockMapper.map(HudMode.LIVE, odometerLoaded = true).items
        assertEquals(listOf(DockKind.CASH_TIP, DockKind.EXPENSE, DockKind.ODOMETER, DockKind.LOG), items.map { it.kind })
        assertEquals(listOf(DockItemState.NORMAL, DockItemState.NORMAL, DockItemState.NORMAL, DockItemState.NORMAL), items.map { it.state })
        for (mode in HudMode.entries) {
            DockMapper.map(mode, odometerLoaded = true).items.filter { it.kind == DockKind.CASH_TIP || it.kind == DockKind.EXPENSE }
                .forEach { assertEquals(DockItemState.NORMAL, it.state) }
        }
    }

    @Test
    fun `resting dock adds settings`() {
        assertEquals(listOf(DockKind.CASH_TIP, DockKind.EXPENSE, DockKind.ODOMETER, DockKind.LOG, DockKind.SETTINGS), DockMapper.map(HudMode.RESTING, odometerLoaded = true).items.map { it.kind })
    }

    @Test
    fun `offer hides the dock`() {
        assertTrue(DockMapper.map(HudMode.OFFER, odometerLoaded = true).items.isEmpty())
    }

    @Test
    fun `log shown replaces Log with a lit Now`() {
        assertEquals(DockItemState.LIT, DockMapper.map(HudMode.LIVE, logShown = true, odometerLoaded = true).items.single { it.kind == DockKind.NOW }.state)
    }
    @Test fun `door and receipt accent the cash tip`() {
        val facts = JobFacts("Store", null, null, null)
        val cards = listOf(StateCardModel.DropoffArrived(facts, 1_000),
            StateCardModel.Receipt("Store", 1_000, emptyList(), null, null))
        cards.forEach { card ->
            val items = DockMapper.map(HudMode.LIVE, firstRun = false, cashTipCue = card.cuesCashTip, odometerLoaded = true).items
            assertEquals(DockItemState.ACCENT, items.single { it.kind == DockKind.CASH_TIP }.state)
            assertEquals(DockItemState.NORMAL, items.single { it.kind == DockKind.EXPENSE }.state)
        }
    }
    @Test fun `first run disables cash tip even with the cue`() {
        assertEquals(DockItemState.DISABLED, DockMapper.map(HudMode.LIVE, firstRun = true, cashTipCue = true, odometerLoaded = true)
            .items.single { it.kind == DockKind.CASH_TIP }.state)
    }
    @Test fun `offer hides the dock regardless of the cue`() {
        assertTrue(DockMapper.map(HudMode.OFFER, firstRun = false, cashTipCue = true, odometerLoaded = true).items.isEmpty())
        assertTrue(DockMapper.map(HudMode.OFFER, firstRun = true, cashTipCue = true, odometerLoaded = true).items.isEmpty())
    }

    @Test fun `Now never appears outside the log`() {
        HudMode.entries.forEach { mode ->
            assertFalse(DockMapper.map(mode, logShown = false, odometerLoaded = true).items.any { it.kind == DockKind.NOW })
        }
        assertFalse(DockMapper.map(HudMode.LIVE, logShown = true, odometerLoaded = true).items.any { it.kind == DockKind.LOG })
    }

    @Test fun `resting cash tip opens the last dash and keeps its pending cue`() {
        assertEquals(DockItemState.NORMAL, DockMapper.map(HudMode.RESTING, odometerLoaded = true)
            .items.single { it.kind == DockKind.CASH_TIP }.state)
        assertEquals(DockItemState.ACCENT, DockMapper.map(HudMode.RESTING, cashTipCue = true, odometerLoaded = true)
            .items.single { it.kind == DockKind.CASH_TIP }.state)
        assertFalse(DockMapper.map(HudMode.RESTING, firstRun = true, odometerLoaded = true)
            .items.any { it.kind == DockKind.CASH_TIP })
    }

    @Test fun `expense accents only while its strip is visible live and at rest`() {
        for (mode in listOf(HudMode.LIVE, HudMode.RESTING)) {
            assertEquals(DockItemState.ACCENT, DockMapper.map(mode, odometerLoaded = true, expenseCue = true)
                .items.single { it.kind == DockKind.EXPENSE }.state)
            assertEquals(DockItemState.NORMAL, DockMapper.map(mode, odometerLoaded = true, expenseCue = false)
                .items.single { it.kind == DockKind.EXPENSE }.state)
        }
    }
    @Test fun `first resting dock offers unassigned expense before odometer and log`() {
        val items = DockMapper.map(HudMode.RESTING, firstRun = true, odometerLoaded = true).items
        assertEquals(listOf(DockKind.EXPENSE, DockKind.ODOMETER, DockKind.LOG, DockKind.SETTINGS), items.map { it.kind })
        assertEquals(DockItemState.NORMAL, items.first().state)
    }
}
