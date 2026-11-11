package cloud.trotter.log.gig.feature.setup.wizard.cards

import cloud.trotter.log.gig.feature.setup.wizard.model.GasPriceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** #1478: "Regional average" only for a figure a lookup returned, with its day; a failure says so. */
class GasPriceLineTest {
    private val zone = ZoneId.of("America/Chicago")
    private val oct20 = LocalDate.of(2026, 10, 20)
    private fun at(date: LocalDate, hour: Int, minute: Int = 0) =
        date.atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun line(source: GasPriceSource, failed: Boolean = false, now: Long = at(oct20, 18)) =
        GasPriceLine.of(source, failed, 3.29f, now, zone)

    @Test
    fun `a lookup from today reads today and one from an earlier day reads its date`() {
        assertEquals(GasPriceLine.Today, line(GasPriceSource.Lookup(at(oct20, 6))))
        assertEquals(GasPriceLine.FetchedOn(oct20), line(GasPriceSource.Lookup(at(oct20, 6)), now = at(oct20.plusDays(1), 0, 1)))
    }

    @Test
    fun `today is the device's day, not UTC's`() {
        // 20:00 in Chicago is already the next day in UTC.
        assertEquals(GasPriceLine.Today, line(GasPriceSource.Lookup(at(oct20, 20)), now = at(oct20, 23, 59)))
    }

    @Test
    fun `a failed lookup names the kept figure's day, or asks for a price when none was looked up`() {
        assertEquals(GasPriceLine.FailedUsing(3.29f, oct20), line(GasPriceSource.Lookup(at(oct20, 6)), failed = true))
        assertEquals(GasPriceLine.FailedNoLookup, line(GasPriceSource.Saved, failed = true))
        assertEquals(GasPriceLine.FailedNoLookup, line(GasPriceSource.Typed, failed = true))
    }

    @Test
    fun `typed and saved figures say so`() {
        assertEquals(GasPriceLine.Typed, line(GasPriceSource.Typed))
        assertEquals(GasPriceLine.Saved, line(GasPriceSource.Saved))
    }

    @Test
    fun `only a failure offers retry`() {
        assertTrue(line(GasPriceSource.Saved, failed = true).offersRetry)
        assertTrue(line(GasPriceSource.Lookup(at(oct20, 6)), failed = true).offersRetry)
        assertFalse(line(GasPriceSource.Lookup(at(oct20, 6))).offersRetry)
        assertFalse(line(GasPriceSource.Typed).offersRetry)
    }

    @Test
    fun `a stored price loads by its auto flag and stamp`() {
        assertEquals(GasPriceSource.Typed, GasPriceSource.stored(isAuto = false, fetchedAtMillis = 1L))
        assertEquals(GasPriceSource.Lookup(1L), GasPriceSource.stored(isAuto = true, fetchedAtMillis = 1L))
        assertEquals(GasPriceSource.Saved, GasPriceSource.stored(isAuto = true, fetchedAtMillis = null))
    }
}
