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
    @Test
    fun `live dock is cash tip expense log`() {
        val items = DockMapper.map(HudMode.LIVE).items
        assertEquals(listOf(DockKind.CASH_TIP, DockKind.EXPENSE, DockKind.LOG), items.map { it.kind })
        assertEquals(listOf(DockItemState.DISABLED, DockItemState.DISABLED, DockItemState.NORMAL), items.map { it.state })
        for (mode in HudMode.entries) {
            DockMapper.map(mode).items.filter { it.kind == DockKind.CASH_TIP || it.kind == DockKind.EXPENSE }
                .forEach { assertEquals(DockItemState.DISABLED, it.state) }
        }
    }

    @Test
    fun `resting dock adds settings`() {
        assertEquals(DockKind.entries.filter { it != DockKind.NOW }, DockMapper.map(HudMode.RESTING).items.map { it.kind })
    }

    @Test
    fun `offer hides the dock`() {
        assertTrue(DockMapper.map(HudMode.OFFER).items.isEmpty())
    }

    @Test
    fun `log shown replaces Log with a lit Now`() {
        assertEquals(DockItemState.LIT, DockMapper.map(HudMode.LIVE, logShown = true).items.single { it.kind == DockKind.NOW }.state)
    }
    @Test fun `door and receipt accent the cash tip`() {
        val facts = JobFacts("Store", null, null, null)
        val cards = listOf(StateCardModel.DropoffArrived(facts, 1_000),
            StateCardModel.Receipt("Store", 1_000, emptyList(), null, null))
        cards.forEach { card ->
            val items = DockMapper.map(HudMode.LIVE, firstRun = false, cashTipCue = card.cuesCashTip).items
            assertEquals(DockItemState.ACCENT, items.single { it.kind == DockKind.CASH_TIP }.state)
            assertEquals(DockItemState.DISABLED, items.single { it.kind == DockKind.EXPENSE }.state)
        }
    }
    @Test fun `first run disables cash tip even with the cue`() {
        assertEquals(DockItemState.DISABLED, DockMapper.map(HudMode.RESTING, firstRun = true, cashTipCue = true)
            .items.single { it.kind == DockKind.CASH_TIP }.state)
    }
    @Test fun `offer hides the dock regardless of the cue`() {
        assertTrue(DockMapper.map(HudMode.OFFER, firstRun = false, cashTipCue = true).items.isEmpty())
        assertTrue(DockMapper.map(HudMode.OFFER, firstRun = true, cashTipCue = true).items.isEmpty())
    }

    @Test fun `Now never appears outside the log`() {
        HudMode.entries.forEach { mode ->
            assertFalse(DockMapper.map(mode, logShown = false).items.any { it.kind == DockKind.NOW })
        }
        assertFalse(DockMapper.map(HudMode.LIVE, logShown = true).items.any { it.kind == DockKind.LOG })
    }

}
