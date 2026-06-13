package cloud.trotter.dashbuddy.domain.format

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * #358 locale policy: display formatting follows the DEVICE locale —
 * explicitly, not as an invisible side effect of bare `.format`.
 */
class FormatsTest {

    private lateinit var original: Locale

    @Before
    fun saveLocale() {
        original = Locale.getDefault()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(original)
    }

    @Test
    fun `US locale formats with dot decimals and comma grouping`() {
        Locale.setDefault(Locale.US)
        assertEquals("$7.50", Formats.money(7.5))
        assertEquals("$23", Formats.money0(23.4))
        assertEquals("$0.165", Formats.money3(0.1649))
        assertEquals("4.2", Formats.decimal(4.23))
        assertEquals("12,500", Formats.commaInt(12_500))
    }

    @Test
    fun `comma-decimal locale renders ITS convention - deliberately`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("$7,50", Formats.money(7.5))
        assertEquals("12.500", Formats.commaInt(12_500))
    }
}
