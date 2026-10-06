package cloud.trotter.dashbuddy.domain.region

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CountyCellMapResourceTest {
    @Test
    fun `CSV has unique five digit FIPS valid coordinates and consistent CBSA presence`() {
        val stream = requireNotNull(javaClass.getResourceAsStream("/geo/county_cells_2023.csv"))
        stream.bufferedReader(Charsets.UTF_8).use { reader ->
            assertEquals("county_fips,state,lat,lon,cbsa,kind", reader.readLine())
            val seen = mutableSetOf<String>()
            val states = mutableSetOf<String>()
            reader.lineSequence().forEach { line ->
                val fields = line.split(',')
                assertEquals(6, fields.size)
                val (fips, state, lat, lon, cbsa) = fields
                assertTrue(fips, Regex("[0-9]{5}").matches(fips))
                assertTrue("Duplicate FIPS $fips", seen.add(fips))
                assertTrue(fips, lat.toDouble() in -90.0..90.0)
                assertTrue(fips, lon.toDouble() in -180.0..180.0)
                assertTrue(state, state in RegionCell.STATE_CODES)
                states.add(state)
                if (cbsa.isEmpty()) {
                    assertEquals("none", fields[5])
                } else {
                    assertTrue(cbsa, Regex("[0-9]{5}").matches(cbsa))
                    assertTrue(fields[5] == "metro" || fields[5] == "micro")
                }
            }
            assertEquals(3_222, seen.size)
            assertEquals(52, states.size)
        }
    }
}
