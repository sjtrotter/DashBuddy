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
        val items = DockMapper.map(HudMode.LIVE, false).items
        assertEquals(listOf(DockKind.CASH_TIP, DockKind.EXPENSE, DockKind.LOG), items.map { it.kind })
        assertEquals(listOf(DockItemState.DISABLED, DockItemState.DISABLED, DockItemState.NORMAL), items.map { it.state })
        for (mode in HudMode.entries) {
            DockMapper.map(mode, false).items.filter { it.kind == DockKind.CASH_TIP || it.kind == DockKind.EXPENSE }
                .forEach { assertEquals(DockItemState.DISABLED, it.state) }
        }
    }

    @Test
    fun `resting dock adds settings`() {
        assertEquals(DockKind.entries, DockMapper.map(HudMode.RESTING, false).items.map { it.kind })
    }

    @Test
    fun `first run disables cash tip`() {
        assertEquals(DockItemState.DISABLED, DockMapper.map(HudMode.RESTING, true).items.first().state)
    }

    @Test
    fun `offer hides the dock`() {
        assertTrue(DockMapper.map(HudMode.OFFER, false).items.isEmpty())
    }

    @Test
    fun `log mode lights the log block`() {
        assertEquals(DockItemState.LIT, DockMapper.map(HudMode.LOG, false).items.single { it.kind == DockKind.LOG }.state)
    }
}
