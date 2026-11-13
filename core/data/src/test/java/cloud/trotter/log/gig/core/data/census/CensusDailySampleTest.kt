package cloud.trotter.log.gig.core.data.census

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** #1620 (ADR-0011 §8): one sample per variant per UTC day, remembered across a process restart. */
class CensusDailySampleTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `the first sighting of a variant on a day is kept, later ones are not`() = runTest {
        val file = File(tmp.newFolder(), "sampled.json")
        val sample = CensusDailySample(file, Dispatchers.Unconfined)
        assertTrue(sample.admit("2026-11-13", "a"))
        assertFalse(sample.admit("2026-11-13", "a"))
        assertTrue(sample.admit("2026-11-13", "b"))
        assertTrue("a new UTC day samples again", sample.admit("2026-11-14", "a"))

        val restarted = CensusDailySample(file, Dispatchers.Unconfined)
        assertFalse("a restart keeps the day's presence", restarted.admit("2026-11-14", "a"))
        assertFalse(restarted.admit("2026-11-13", "b"))
    }

    @Test fun `only the two latest days are kept`() = runTest {
        val sample = CensusDailySample(File(tmp.newFolder(), "sampled.json"), Dispatchers.Unconfined)
        assertTrue(sample.admit("2026-11-12", "a"))
        assertTrue(sample.admit("2026-11-13", "a"))
        assertTrue(sample.admit("2026-11-14", "a"))
        assertFalse("older than both kept days: not kept", sample.admit("2026-11-12", "z"))
        assertFalse(sample.admit("2026-11-13", "a"))
    }

    @Test fun `an unreadable file starts the day afresh`() = runTest {
        val file = File(tmp.newFolder(), "sampled.json").apply { writeText("not json") }
        assertTrue(CensusDailySample(file, Dispatchers.Unconfined).admit("2026-11-13", "a"))
    }
}
