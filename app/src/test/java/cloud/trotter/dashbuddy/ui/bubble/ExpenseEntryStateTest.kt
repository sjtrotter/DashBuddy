package cloud.trotter.dashbuddy.ui.bubble

import androidx.compose.runtime.saveable.SaverScope
import cloud.trotter.dashbuddy.domain.analytics.ExpenseCategory
import cloud.trotter.dashbuddy.feature.bubble.hud.expense.*
import org.junit.Assert.*
import org.junit.Test

class ExpenseEntryStateTest {
    @Test fun `label state refuses more than forty code points`() {
        val entry = ExpenseEntryState()
        val label = "🚗".repeat(40)
        entry.label = label
        assertEquals(label, entry.label)
        entry.label = label + "x"
        assertEquals(label, entry.label)
    }
    @Test fun `edit and New label preserve typed text but New clears a recent selection`() {
        val entry = ExpenseEntryState()
        entry.label = "tires"
        entry.editLabel()
        assertTrue(entry.keyboardOpen); assertEquals("tires", entry.label)
        entry.keyboardOpen = false
        entry.newLabel(listOf("wipers"))
        assertTrue(entry.keyboardOpen); assertEquals("tires", entry.label)
        entry.label = "wipers"; entry.keyboardOpen = false
        entry.newLabel(listOf("wipers"))
        assertTrue(entry.keyboardOpen); assertEquals("", entry.label)
        entry.keyboardOpen = false
        entry.newLabel(emptyList())
        assertTrue(entry.keyboardOpen); assertEquals("", entry.label)
    }
    @Test fun `back walks sheet subcard closed`() {
        val entry = ExpenseEntryState()
        entry.show(); assertTrue(entry.open); assertEquals(ExpensePage.SUBCARD, entry.page)
        entry.select(ExpensePick.Fuel); assertEquals(ExpenseField.TOTAL, entry.activeField)
        entry.back(); assertTrue(entry.open); assertEquals(ExpensePage.SUBCARD, entry.page)
        entry.back(); assertFalse(entry.open)
    }
    @Test fun `digits point and backspace affect only the active field`() {
        val entry = ExpenseEntryState()
        entry.show(); entry.select(ExpensePick.Fuel)
        entry.digit(4); entry.digit(2); entry.point(); entry.digit(0); entry.digit(0)
        entry.activeField = ExpenseField.GALLONS
        entry.digit(1); entry.digit(1); entry.point(); entry.digit(1); entry.digit(2); entry.digit(3); entry.digit(4)
        assertEquals(4200L, entry.totalCents)
        assertEquals("11.123", entry.gallonsDigits)
        entry.backspace(); assertEquals("11.12", entry.gallonsDigits)
        entry.activeField = ExpenseField.TOTAL
        entry.backspace(); assertEquals("42.0", entry.totalDigits)
        assertEquals("11.12", entry.gallonsDigits)
    }
    @Test fun `new pick clears the previous category and advances entry identity`() {
        val entry = ExpenseEntryState()
        entry.show(); entry.select(ExpensePick.Expense(ExpenseCategory.CAR_CARE))
        val old = entry.entryId
        entry.label = "wipers"; entry.keyboardOpen = true; entry.digit(8)
        entry.back(); entry.select(ExpensePick.Fuel)
        assertTrue(entry.entryId > old)
        assertEquals("", entry.label); assertEquals(0L, entry.totalCents)
        assertFalse(entry.keyboardOpen)
    }
    @Test fun `saved entry restores page pick buffers field label and keyboard`() {
        val entry = ExpenseEntryState().apply {
            show(); select(ExpensePick.Expense(ExpenseCategory.CAR_CARE))
            digit(1); digit(8); point(); digit(4)
            activeField = ExpenseField.GALLONS; digit(2); point(); digit(3)
            label = "wipers"; keyboardOpen = true
        }
        val saved = with(ExpenseEntryState.Saver) { object : SaverScope { override fun canBeSaved(value: Any) = true }.save(entry) }!!
        val restored = ExpenseEntryState.Saver.restore(saved)!!
        assertTrue(restored.open); assertEquals(ExpensePage.SHEET, restored.page)
        assertEquals(entry.pick, restored.pick); assertEquals(entry.activeField, restored.activeField)
        assertEquals("18.4", restored.totalDigits); assertEquals("2.3", restored.gallonsDigits)
        assertEquals("wipers", restored.label); assertTrue(restored.keyboardOpen)
        assertEquals(entry.entryId, restored.entryId)
    }
    @Test fun `dock open always returns to subcard with a new entry identity`() {
        val entry = ExpenseEntryState()
        entry.show(); entry.select(ExpensePick.Expense(ExpenseCategory.OTHER))
        entry.keyboardOpen = true
        val old = entry.entryId
        entry.show()
        assertEquals(ExpensePage.SUBCARD, entry.page)
        assertFalse(entry.keyboardOpen); assertTrue(entry.open)
        assertTrue(entry.entryId > old)
    }
}
