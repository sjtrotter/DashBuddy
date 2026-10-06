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
    fun `map loads all county subdivisions`() {
        assertEquals(36_434, map.rows.size)
    }

    @Test
    fun `GPS probes resolve to the pinned cells`() {
        assertEquals("metro:12420@2023", map.cellAt(Coordinates(30.2672, -97.7431))?.wire)
        val manhattan = Coordinates(40.7128, -74.0060)
        assertEquals("metro:35620@2023", map.cellAt(manhattan)?.wire)
        assertEquals("34017", map.countyOf(manhattan)) // Hudson County NJ, same CBSA.
        assertEquals("state:ND@2023", map.cellAt(Coordinates(47.5, -100.9))?.wire)
        assertEquals("metro:46520@2023", map.cellAt(Coordinates(21.3069, -157.8583))?.wire)
        // Key West: 33.7 km from its own subdivision point.
        assertEquals("micro:28580@2023", map.cellAt(Coordinates(24.5551, -81.7800))?.wire)
        assertEquals("metro:40140@2023", map.cellAt(Coordinates(34.108, -117.289))?.wire) // San Bernardino
        assertEquals("metro:40140@2023", map.cellAt(Coordinates(33.9806, -117.3755))?.wire) // Riverside
        assertEquals("metro:40140@2023", map.cellAt(Coordinates(33.4936, -117.1484))?.wire) // Temecula
        assertEquals("metro:12620@2023", map.cellAt(Coordinates(44.80, -68.78))?.wire) // Bangor
        assertEquals("metro:38060@2023", map.cellAt(Coordinates(33.4484, -112.074))?.wire) // Phoenix
    }

    /**
     * KNOWN LIMITATION, pinned so a change is deliberate (Astra r1): a border city of a neighbouring country
     * resolves to the adjacent US cell — a wrong cell, never a privacy leak. The offline containment guard
     * that replaces the distance cutoff (#1194, before the `region` field ships) must flip these to null.
     */
    @Test
    fun `border cities of neighbouring countries currently resolve to the adjacent US cell`() {
        assertEquals("metro:19820@2023", map.cellAt(Coordinates(42.3149, -83.0364))?.wire) // Windsor ON → Detroit
        assertEquals("metro:41740@2023", map.cellAt(Coordinates(32.5149, -117.0382))?.wire) // Tijuana → San Diego
        assertNull(map.cellAt(Coordinates(51.88, -176.65))) // Adak AK, 256 km — Aleutian reach hole
        assertNull(map.cellAt(Coordinates(71.29, -156.79))) // Utqiagvik AK, 239 km — Arctic reach hole
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
    fun `distance guard accepts just inside 160 km and rejects just outside`() {
        val isolated = CountyCellMap.read(
            "geoid,state,lat,lon,cbsa,kind\n0100190000,AL,0,0,12420,metro\n".reader().buffered(),
        )
        val origin = Coordinates(0.0, 0.0)
        val degreesPerMeter = 1.0 / origin.distanceTo(Coordinates(1.0, 0.0))
        assertNotNull(isolated.cellAt(Coordinates(159_999.0 * degreesPerMeter, 0.0)))
        assertNull(isolated.cellAt(Coordinates(160_001.0 * degreesPerMeter, 0.0)))
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
    fun `direct construction and copy reject invalid identifiers and vintages`() {
        listOf(RegionCell.Kind.METRO, RegionCell.Kind.MICRO).forEach { kind ->
            listOf("ND", "00000", "1234", "123456", "12a45", "１２３４５").forEach { id ->
                assertThrows(IllegalArgumentException::class.java) { RegionCell(kind, id) }
            }
        }
        listOf("ZZ", "nd", "12420", "").forEach { id ->
            assertThrows(IllegalArgumentException::class.java) { RegionCell(RegionCell.Kind.STATE, id) }
        }
        listOf("0000", "", "123", "12345", "20a3").forEach { vintage ->
            assertThrows(IllegalArgumentException::class.java) {
                RegionCell(RegionCell.Kind.STATE, "ND", vintage)
            }
        }
        val valid = RegionCell(RegionCell.Kind.METRO, "12420")
        assertThrows(IllegalArgumentException::class.java) { valid.copy(id = "ND") }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(vintage = "0000") }
    }

    @Test
    fun `corrupt county map rows throw instead of being skipped`() {
        val header = "geoid,state,lat,lon,cbsa,kind\n"
        val valid = "0100190000,AL,32.5322,-86.6464,33860,metro"
        listOf(
            "bad header\n$valid", header, header + "\n", header + valid + ",extra",
            header + valid + "\n" + valid,
            header + "01001,AL,32.5322,-86.6464,33860,metro",
            header + "01001900000,AL,32.5322,-86.6464,33860,metro",
            header + "010019000X,AL,32.5322,-86.6464,33860,metro",
            header + "0100190000,AL,3e1,-86.6464,33860,metro",
            header + "0100190000,ZZ,32.5322,-86.6464,33860,metro",
            header + "0100190000,AL,NaN,-86.6464,33860,metro",
            header + "0100190000,AL,91,-86.6464,33860,metro",
            header + "0100190000,AL,32.5322,-181,33860,metro",
            header + "0100190000,AL,32.5322,-86.6464,,metro",
            header + "0100190000,AL,32.5322,-86.6464,33860,none",
            header + "0100190000,AL,32.5322,-86.6464,33860,state",
            header + "0100190000,AL,32.5322,-86.6464,3386,micro",
            header + "0100190000,AL,32.5322,-86.6464,00000,metro",
            header + "0100190000,AL,32.5322,-86.6464,ND,metro",
        ).forEach { csv ->
            assertThrows(IllegalArgumentException::class.java) {
                CountyCellMap.read(csv.reader().buffered())
            }
        }
    }
}
