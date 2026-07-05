package cloud.trotter.dashbuddy.domain.format

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/**
 * #358/#467: ONE duration/countdown definition repo-wide, with unified zero/negative
 * semantics (the two old private copies disagreed) and ASCII digits regardless
 * of device locale (clock-like glance strings must not localize numerals).
 */
class TimeFormatsTest {

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
    fun `formatDuration tiers and floors`() {
        assertEquals("45s", formatDuration(45_000))
        assertEquals("3m 12s", formatDuration(192_000))
        assertEquals("2h 5m", formatDuration(2 * 3_600_000L + 5 * 60_000L))
        assertEquals("0s", formatDuration(0))
        assertEquals("0s", formatDuration(-5_000)) // unified: negatives floor, never render "-5s"
    }

    @Test
    fun `formatCountdown is m colon ss with absolute magnitude for negatives`() {
        assertEquals("1:05", formatCountdown(65_000))
        assertEquals("0:00", formatCountdown(0))
        assertEquals("1:05", formatCountdown(-65_000)) // caller adds the late label
        assertEquals("12:00", formatCountdown(12 * 60_000L))
    }

    @Test
    fun `digits stay ASCII under a non-ASCII-numeral locale`() {
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        assertEquals("3m 12s", formatDuration(192_000))
        assertEquals("1:05", formatCountdown(65_000))
    }

    @Test
    fun `formatClockTime renders localized SHORT time at a fixed zone and locale`() {
        // 2021-09-13 21:42:07 UTC → 5:42 PM in America/New_York (EDT, UTC−4), US locale.
        // CLDR separates the time from the day-period with a NARROW NO-BREAK SPACE (U+202F).
        val millis = 1_631_569_327_000L
        assertEquals(
            "5:42 PM",
            formatClockTime(millis, ZoneId.of("America/New_York"), Locale.US).replace('\u202F', ' '),
        )
        // Same instant is 21:42 in UTC under a 24-hour locale (UK).
        assertEquals(
            "21:42",
            formatClockTime(millis, ZoneId.of("UTC"), Locale.UK),
        )
    }
}
