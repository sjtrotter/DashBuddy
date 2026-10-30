package cloud.trotter.log.gig.feature.bubble.hud.keypad

import org.junit.Assert.*
import org.junit.Test

class DecimalEntryTest {
    @Test fun `point leading zero trailing zero and backspace preserve the buffer`() {
        var entry = DecimalEntry().point().digit(0).digit(1).digit(0)
        assertEquals("0.010", entry.digits)
        assertTrue(entry.positive)
        assertEquals(entry, entry.point())
        entry = entry.backspace().backspace().backspace()
        assertEquals("0.", entry.digits)
        assertFalse(entry.positive)
        assertEquals("", entry.backspace().backspace().backspace().digits)
    }
    @Test fun `gallons cap at five integers and three fractional digits`() {
        var entry = DecimalEntry()
        repeat(6) { entry = entry.digit(9) }
        assertEquals("99999", entry.digits)
        entry = entry.point()
        repeat(4) { entry = entry.digit(9) }
        assertEquals("99999.999", entry.digits)
        assertEquals("99999.991", entry.backspace().digit(1).digits)
    }
    @Test fun `zeros normalize and never make a positive gallon`() {
        val entry = DecimalEntry().digit(0).digit(0)
        assertEquals("0", entry.digits)
        assertFalse(entry.positive)
        assertEquals("4", entry.digit(4).digits)
        assertFalse(entry.point().digit(0).positive)
    }
    @Test fun `money uses six integers and two decimals with exact Long cents`() {
        var money = MoneyEntry()
        for (n in listOf(1, 8)) money = money.digit(n)
        money = money.point().digit(4).digit(0).digit(9)
        assertEquals("18.40", money.digits)
        assertEquals(1840L, money.cents)
        assertEquals(184L, MoneyEntry("1.84").cents)
        assertEquals(99_999_999L, MoneyEntry("999999.99").digit(1).cents)
        assertEquals(0L, MoneyEntry().cents)
    }
    @Test fun `malformed and out of bounds gallons are rejected`() {
        for (digits in listOf("-1", "1e3", "1.2.3", "100000", "1.0001", "NaN")) {
            assertTrue(runCatching { DecimalEntry(digits) }.isFailure)
        }
    }
}
