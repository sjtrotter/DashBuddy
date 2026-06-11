package cloud.trotter.dashbuddy.core.designsystem.format

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * #358 locale policy: display formatting follows the DEVICE locale —
 * explicitly, not as an invisible side effect of bare `.format`.
 */
class DashFormatsTest {

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
        assertEquals("$7.50", DashFormats.money(7.5))
        assertEquals("$23", DashFormats.money0(23.4))
        assertEquals("$0.165", DashFormats.money3(0.1649))
        assertEquals("4.2", DashFormats.decimal(4.23))
        assertEquals("12,500", DashFormats.commaInt(12_500))
    }

    @Test
    fun `comma-decimal locale renders ITS convention - deliberately`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("$7,50", DashFormats.money(7.5))
        assertEquals("12.500", DashFormats.commaInt(12_500))
    }
}
