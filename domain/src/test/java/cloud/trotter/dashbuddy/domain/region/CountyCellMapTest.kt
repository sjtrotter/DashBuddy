package cloud.trotter.dashbuddy.domain.region

import cloud.trotter.dashbuddy.domain.model.location.Coordinates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CountyCellMapTest {
    private val map = CountyCellMap.V2023

    @Test
    fun `map loads all counties and every CBSA has a matching kind and title`() {
        assertEquals(3_222, map.rows.size)
        val cbsas = map.rows.map { it.cell }.filter { it.kind != RegionCell.Kind.STATE }.toSet()
        assertEquals(935, cbsas.size) // Titles export also contains two Census footer rows.
        cbsas.forEach { assertNotNull(it.wire, CellTitles.titleOf(it)) }
        assertEquals(1_252, map.rows.count { it.cell.kind == RegionCell.Kind.METRO })
        assertEquals(663, map.rows.count { it.cell.kind == RegionCell.Kind.MICRO })
        assertEquals(1_307, map.rows.count { it.cell.kind == RegionCell.Kind.STATE })
    }

    @Test
    fun `GPS probes resolve to the pinned cells`() {
        assertEquals("metro:12420@2023", map.cellAt(Coordinates(30.2672, -97.7431))?.wire)
        val manhattan = Coordinates(40.7128, -74.0060)
        assertEquals("metro:35620@2023", map.cellAt(manhattan)?.wire)
        assertEquals("34017", map.countyOf(manhattan)) // Hudson County NJ, same CBSA.
        assertEquals("state:ND@2023", map.cellAt(Coordinates(47.5, -100.9))?.wire)
        assertEquals("metro:46520@2023", map.cellAt(Coordinates(21.3069, -157.8583))?.wire)
    }

    @Test
    fun `remote and invalid fixes fail null`() {
        listOf(
            Coordinates(51.5, -0.12), Coordinates(0.0, 0.0),
            Coordinates(Double.NaN, -97.7431), Coordinates(30.2672, Double.NaN),
            Coordinates(90.1, 0.0), Coordinates(-90.1, 0.0),
            Coordinates(0.0, 180.1), Coordinates(0.0, -180.1),
            Coordinates(Double.POSITIVE_INFINITY, 0.0), Coordinates(0.0, Double.NEGATIVE_INFINITY),
        ).forEach {
            assertNull(map.cellAt(it))
            assertNull(map.countyOf(it))
        }
    }

    @Test
    fun `distance guard accepts just inside 120 km and rejects just outside`() {
        val isolated = CountyCellMap.read(
            "county_fips,state,lat,lon,cbsa,kind\n01001,AL,0,0,12420,metro\n".reader().buffered(),
        )
        val origin = Coordinates(0.0, 0.0)
        val degreesPerMeter = 1.0 / origin.distanceTo(Coordinates(1.0, 0.0))
        assertNotNull(isolated.cellAt(Coordinates(119_999.0 * degreesPerMeter, 0.0)))
        assertNull(isolated.cellAt(Coordinates(120_001.0 * degreesPerMeter, 0.0)))
    }

    @Test
    fun `cell wire round trips each kind and preserves vintage`() {
        listOf(
            RegionCell(RegionCell.Kind.METRO, "12420"),
            RegionCell(RegionCell.Kind.MICRO, "10100"),
            RegionCell(RegionCell.Kind.STATE, "ND"),
            RegionCell(RegionCell.Kind.STATE, "PR", "2024"),
        ).forEach { assertEquals(it, RegionCell.parse(it.wire)) }
    }

    @Test
    fun `malformed wire forms fail null`() {
        listOf(
            "metro:12420", "foo:1@2023", "", "metro:ND@2023", "state:12420@2023",
            "state:ZZ@2023", "state:nd@2023", "micro:1234@2023", "metro:00000@2023",
            "metro:12420@", "metro:12420@0000", "metro:12420@2023@2024", "metro:12420@2023\n",
            " metro:12420@2023", "metro:12420@2023 ",
        ).forEach { assertNull(it, RegionCell.parse(it)) }
    }

    @Test
    fun `titles decode CSV commas and stay specific to kind and vintage`() {
        assertEquals("Austin-Round Rock-San Marcos, TX", CellTitles.titleOf(RegionCell(RegionCell.Kind.METRO, "12420")))
        assertEquals("Aberdeen, SD", CellTitles.titleOf(RegionCell(RegionCell.Kind.MICRO, "10100")))
        assertNull(CellTitles.titleOf(RegionCell(RegionCell.Kind.STATE, "ND")))
        assertNull(CellTitles.titleOf(RegionCell(RegionCell.Kind.MICRO, "12420")))
        assertNull(CellTitles.titleOf(RegionCell(RegionCell.Kind.METRO, "12420", "2024")))
    }

    @Test
    fun `corrupt county map rows throw instead of being skipped`() {
        val header = "county_fips,state,lat,lon,cbsa,kind\n"
        val valid = "01001,AL,32.5322,-86.6464,33860,metro"
        listOf(
            "bad header\n$valid", header, header + "\n", header + valid + ",extra",
            header + valid + "\n" + valid,
            header + "1001,AL,32.5322,-86.6464,33860,metro",
            header + "01001,ZZ,32.5322,-86.6464,33860,metro",
            header + "01001,AL,NaN,-86.6464,33860,metro",
            header + "01001,AL,91,-86.6464,33860,metro",
            header + "01001,AL,32.5322,-181,33860,metro",
            header + "01001,AL,32.5322,-86.6464,,metro",
            header + "01001,AL,32.5322,-86.6464,33860,none",
            header + "01001,AL,32.5322,-86.6464,33860,state",
            header + "01001,AL,32.5322,-86.6464,3386,micro",
        ).forEach { csv ->
            assertThrows(IllegalArgumentException::class.java) {
                CountyCellMap.read(csv.reader().buffered())
            }
        }
    }
}
