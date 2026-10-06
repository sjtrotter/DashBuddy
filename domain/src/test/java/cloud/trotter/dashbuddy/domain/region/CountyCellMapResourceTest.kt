package cloud.trotter.dashbuddy.domain.region

import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CountyCellMapResourceTest {
    @Test
    fun `pinned resource covers all subdivisions counties and states with five digit CBSAs`() {
        val stream = requireNotNull(javaClass.getResourceAsStream("/geo/cousub_cells_2023.csv.gz"))
        GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use { reader ->
            val rows = reader.lineSequence().drop(1).map { it.split(',') }.toList()
            assertEquals(36_434, rows.size)
            assertEquals(3_222, rows.map { it[0].substring(0, 5) }.toSet().size)
            assertEquals(52, rows.map { it[1] }.toSet().size)
            rows.map { it[4] }.filter { it.isNotEmpty() }.forEach { cbsa ->
                assertTrue(cbsa, Regex("[0-9]{5}").matches(cbsa))
            }
        }
    }
}
