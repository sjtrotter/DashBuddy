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
    @Test fun `backspace to empty is null`() {
        assertNull(WholeNumberEntry().digit(1).backspace().value)
        assertNull(WholeNumberEntry().backspace().value)
    }
}
