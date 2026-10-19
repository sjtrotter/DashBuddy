package cloud.trotter.dashbuddy.feature.bubble.hud.keypad

import org.junit.Assert.*
import org.junit.Test

class WholeNumberEntryTest {
    @Test fun `no leading zero`() {
        assertNull(WholeNumberEntry().digit(0).value)
        assertEquals(10, WholeNumberEntry().digit(1).digit(0).value)
    }
    @Test fun `caps at six digits`() {
        var entry = WholeNumberEntry()
        repeat(7) { entry = entry.digit(9) }
        assertEquals(999_999, entry.value)
    }
    @Test fun `the cap is the caller's`() {
        var entry = WholeNumberEntry(maxDigits = 3)
        repeat(4) { entry = entry.digit(9) }
        assertEquals(999, entry.value)
        assertEquals(3, entry.maxDigits)
        entry = entry.backspace().digit(8).digit(7)
        assertEquals(998, entry.value)
        assertEquals(3, entry.maxDigits)
    }
    @Test fun `backspace to empty is null`() {
        assertNull(WholeNumberEntry().digit(1).backspace().value)
        assertNull(WholeNumberEntry().backspace().value)
    }
}
