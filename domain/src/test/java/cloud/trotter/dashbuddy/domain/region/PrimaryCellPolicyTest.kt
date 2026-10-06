package cloud.trotter.dashbuddy.domain.region

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrimaryCellPolicyTest {
    private val austin = RegionCell(RegionCell.Kind.METRO, "12420")
    private val rural = RegionCell(RegionCell.Kind.STATE, "ND")
    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    @Test
    fun `Pacific lunch days beat dinner days locally but UTC midnight biases dinner`() {
        val hour = 60L * 60 * 1000
        val pacificOffset = -7 * hour
        // Cell A: three lunch shifts at 12:00 PDT, each within one UTC day.
        val lunches = listOf(90L, 92L, 94L).map { localDay ->
            CellObservation(austin, localDay * day + 12 * hour - pacificOffset)
        }
        // Cell B: two separated 16:00–20:00 PDT shifts, each crossing UTC midnight (17:00 PDT).
        val dinners = listOf(96L, 98L).flatMap { localDay ->
            listOf(16L, 20L).map { localHour ->
                CellObservation(rural, localDay * day + localHour * hour - pacificOffset)
            }
        }
        val observations = lunches + dinners
        assertEquals(austin, PrimaryCellPolicy.primaryCell(observations, now, zoneOffsetMillis = pacificOffset))
        assertEquals(rural, PrimaryCellPolicy.primaryCell(observations, now)) // Documented UTC bias: 4 vs 3 days.
    }

    @Test
    fun `three distinct active days beat ten fixes on one day`() {
        val observations = (1L..10).map { CellObservation(austin, now - it) } +
            (1L..3).map { CellObservation(rural, now - it * day) }
        assertEquals(rural, PrimaryCellPolicy.primaryCell(observations, now))
    }

    @Test
    fun `old and future observations are ignored and window endpoints included`() {
        val start = now - PrimaryCellPolicy.WINDOW_MILLIS
        assertNull(PrimaryCellPolicy.primaryCell(listOf(CellObservation(rural, start - 1)), now))
        assertNull(PrimaryCellPolicy.primaryCell(listOf(CellObservation(rural, now + 1)), now))
        assertEquals(rural, PrimaryCellPolicy.primaryCell(listOf(CellObservation(rural, start)), now))
        assertEquals(austin, PrimaryCellPolicy.primaryCell(listOf(CellObservation(austin, now)), now))
        assertEquals(austin, PrimaryCellPolicy.primaryCell(listOf(
            CellObservation(rural, start - 1), CellObservation(rural, start - day),
            CellObservation(rural, now + 1), CellObservation(austin, now - 1),
        ), now))
    }

    @Test
    fun `equal active days choose most recent observation regardless of input order`() {
        val observations = listOf(CellObservation(austin, now - day), CellObservation(rural, now - 1))
        assertEquals(rural, PrimaryCellPolicy.primaryCell(observations, now))
        assertEquals(rural, PrimaryCellPolicy.primaryCell(observations.reversed(), now))
    }

    @Test
    fun `exact ties are stable regardless of input order`() {
        val observations = listOf(CellObservation(rural, now), CellObservation(austin, now))
        assertEquals(austin, PrimaryCellPolicy.primaryCell(observations, now))
        assertEquals(austin, PrimaryCellPolicy.primaryCell(observations.reversed(), now))
    }

    @Test
    fun `empty activity has no cell and a single observation selects its cell`() {
        assertNull(PrimaryCellPolicy.primaryCell(emptyList(), now))
        assertEquals(austin, PrimaryCellPolicy.primaryCell(listOf(CellObservation(austin, now - 1)), now))
    }

    @Test
    fun `UTC midnight separates active days even before epoch`() {
        assertEquals(rural, PrimaryCellPolicy.primaryCell(listOf(
            CellObservation(rural, -1), CellObservation(rural, 0),
            CellObservation(austin, 1), CellObservation(austin, 2),
        ), day))
    }

    @Test
    fun `window subtraction cannot overflow for extreme timestamps`() {
        assertEquals(rural, PrimaryCellPolicy.primaryCell(listOf(
            CellObservation(rural, Long.MIN_VALUE), CellObservation(austin, Long.MAX_VALUE),
        ), Long.MIN_VALUE))
        assertEquals(austin, PrimaryCellPolicy.primaryCell(listOf(
            CellObservation(rural, Long.MIN_VALUE), CellObservation(austin, Long.MAX_VALUE),
        ), Long.MAX_VALUE))
    }

    @Test
    fun `different vintages remain different cells`() {
        val newer = austin.copy(vintage = "2024")
        assertEquals(newer, PrimaryCellPolicy.primaryCell(listOf(
            CellObservation(austin, now - day), CellObservation(newer, now),
        ), now))
    }
}
