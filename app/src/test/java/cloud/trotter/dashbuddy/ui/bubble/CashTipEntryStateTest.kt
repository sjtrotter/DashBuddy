package cloud.trotter.dashbuddy.ui.bubble

import androidx.compose.runtime.mutableStateOf
import cloud.trotter.dashbuddy.feature.bubble.hud.cashtip.*
import org.junit.Assert.*
import org.junit.Test

class CashTipEntryStateTest {
    private fun entry() = CashTipEntryState(mutableStateOf(false), mutableStateOf(CashTipPage.COMPACT),
        mutableStateOf(null), mutableStateOf(0L), mutableStateOf(CashTipSign.PLUS), mutableStateOf(""))

    @Test fun `opening preselects latest and repeated dock taps preserve entry`() {
        val entry = entry()
        entry.show(9)
        entry.add(500)
        entry.show(8)
        assertEquals(9L, entry.selectedDeliveryId)
        assertEquals(500L, entry.totalCents)
    }
    @Test fun `back from each subpage returns compact then closes`() {
        for (page in listOf(CashTipPage.EXPANDED, CashTipPage.OTHER_AMOUNT)) {
            val entry = entry()
            entry.show(9)
            entry.page = page
            entry.back()
            assertTrue(entry.open)
            assertEquals(CashTipPage.COMPACT, entry.page)
            entry.back()
            assertFalse(entry.open)
        }
    }
    @Test fun `presets add or subtract and total floors at zero`() {
        val entry = entry()
        entry.show(9)
        entry.add(500); entry.add(100); entry.add(100)
        assertEquals(700L, entry.totalCents)
        entry.sign = CashTipSign.MINUS
        entry.add(500)
        assertEquals(200L, entry.totalCents)
        entry.add(2000)
        assertEquals(0L, entry.totalCents)
        entry.sign = CashTipSign.PLUS
        entry.add(100)
        assertEquals(100L, entry.totalCents)
    }
    @Test fun `Other adds fractional dollars exactly and limits cents to two digits`() {
        val entry = entry()
        entry.show(9); entry.add(100)
        entry.other(); entry.digit(2); entry.point(); entry.digit(3); entry.digit(5); entry.digit(9)
        assertEquals("2.35", entry.otherAmount)
        entry.doneOther()
        assertEquals(335L, entry.totalCents)
        assertEquals(CashTipPage.COMPACT, entry.page)
    }
    @Test fun `point first and backspace work without a system keyboard`() {
        val entry = entry()
        entry.show(null); entry.other(); entry.point(); entry.digit(7); entry.digit(5); entry.backspace(); entry.digit(0)
        assertEquals("0.70", entry.otherAmount)
        entry.doneOther()
        assertEquals(70L, entry.totalCents)
    }
    @Test fun `expanded selection returns to compact and preserves amount`() {
        val entry = entry()
        entry.show(9); entry.add(500); entry.page = CashTipPage.EXPANDED
        entry.select(4)
        assertEquals(4L, entry.selectedDeliveryId)
        assertEquals(CashTipPage.COMPACT, entry.page)
        assertEquals(500L, entry.totalCents)
    }
    @Test fun `a fresh open resets page total and sign`() {
        val entry = entry()
        entry.show(9); entry.add(500); entry.sign = CashTipSign.MINUS; entry.open = false
        entry.show(8)
        assertEquals(0L, entry.totalCents)
        assertEquals(CashTipSign.PLUS, entry.sign)
        assertEquals(8L, entry.selectedDeliveryId)
    }
    @Test fun `large entries remain bounded`() {
        val entry = entry()
        entry.show(9)
        repeat(11) { entry.add(10_000_000) }
        assertEquals(CashTipEntryState.MAX_CENTS, entry.totalCents)
    }
    @Test fun `a reopened entry has a new completion identity`() {
        val entry = entry()
        entry.show(9)
        val first = entry.entryId
        entry.show(9)
        assertEquals(first, entry.entryId)
        entry.back()
        entry.show(9)
        assertTrue(entry.entryId > first)
    }

}
