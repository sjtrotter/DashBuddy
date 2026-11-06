package cloud.trotter.log.gig.core.database.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1535: a shift's miles have one SQL owner, [SessionMilesSql.EFFECTIVE_MILES_SQL]. This source scan fails on any
 * `:core:database` main source that computes the odometer span itself, so a new read cannot skip the driver's
 * override. Whether the SQL agrees with `SessionMilesRule` is proved on real query results in `:core:data`.
 */
class SessionMilesSqlGuardTest {

    private val span = Regex("""(?:\w+\.)?lastOdometer\s*-\s*(?:\w+\.)?startOdometer""")

    private fun mainSources(): List<File> {
        val rel = "src/main/java"
        val root = listOf(File(rel), File("core/database", rel)).firstOrNull { it.isDirectory }
            ?: error("Could not locate core/database main sources (cwd=${File(".").absolutePath})")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `no DAO SQL computes the odometer span outside the twin constant`() {
        val sources = mainSources()
        assertTrue("the scan found the DAO", sources.any { it.name == "AnalyticsDao.kt" })
        val offenders = sources.filter { it.name != "SessionMilesSql.kt" && span.containsMatchIn(it.readText()) }
        assertEquals("compute shift miles through SessionMilesSql.EFFECTIVE_MILES_SQL", emptyList<String>(), offenders.map { it.name })
    }

    @Test
    fun `the twin lets an override win before the floored span`() {
        assertEquals("COALESCE(milesOverride, MAX(lastOdometer - startOdometer, 0))", SessionMilesSql.EFFECTIVE_MILES_SQL)
    }
}
