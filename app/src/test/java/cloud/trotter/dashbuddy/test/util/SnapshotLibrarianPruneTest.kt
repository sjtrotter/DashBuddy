package cloud.trotter.dashbuddy.test.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Guards [SnapshotLibrarian.pruneFolder] — the one piece of test infrastructure that
 * **deletes committed corpus** (#929).
 *
 * It had no test of its own, and the two footguns it shipped with were only caught by a
 * human noticing a suspicious `git status`: PR #926's pass deleted 8 `dropoff_pre_arrival`
 * + 1 `ratings` fixture because the corpus carries two capture naming conventions whose
 * lexical order is inverted against their chronology.
 */
class SnapshotLibrarianPruneTest {

    @get:Rule
    val temp = TemporaryFolder()

    // A snapshot file distinct only in its text content — that is what the
    // fingerprint keys on (dynamic price/time tokens are stripped).
    private fun write(name: String, content: String): File =
        temp.root.resolve(name).apply {
            writeText(
                """{"text":"$content","bounds":{"left":0,"top":0,"right":1,"bottom":1}}""",
            )
        }

    /** A well-formed current-convention name whose capture day is [day]. */
    private fun dayName(day: Int): String =
        "2026-07-%02d_10-00-00-000__doordash__accessibility.window__idle_map__%06x.json"
            .format(day, day)

    private fun surviving(): Set<String> =
        temp.root.listFiles()!!.map { it.name }.toSet()

    // =========================================================================
    // Timestamp ordering (the #929 sighting)
    // =========================================================================

    @Test
    fun `the newer capture survives a duplicate pair spanning both naming conventions`() {
        // Legacy `yyyyMMdd_HHmmss_SSS` sorts ABOVE current `yyyy-MM-dd_HH-mm-ss-SSS`
        // lexically ('0' > '-'), so a filename sort kept the January file and deleted the
        // July one. Same content ⇒ exactly one survives; it must be the newer capture.
        val legacyJan = write("20260128_155954_491_MAIN_MAP_IDLE.json", "Chipotle")
        val currentJul = write(
            "2026-07-17_18-08-53-720__doordash__accessibility.window__idle_map__81c084.json",
            "Chipotle",
        )

        SnapshotLibrarian.pruneFolder(temp.root)

        assertEquals(setOf(currentJul.name), surviving())
        assertTrue("the older legacy-named capture should have been pruned", !legacyJan.exists())
    }

    @Test
    fun `capture timestamps parse from both conventions and are ordered chronologically`() {
        val legacy = SnapshotLibrarian.captureTimestampOf("20260128_155954_491_MAIN_MAP_IDLE.json")
        val current = SnapshotLibrarian.captureTimestampOf(
            "2026-07-17_18-08-53-720__doordash__accessibility.window__idle_map__81c084.json",
        )
        val undated = SnapshotLibrarian.captureTimestampOf("c46728d0.json")

        assertTrue("July 2026 must sort after January 2026", current > legacy)
        assertEquals("an un-timestamped name sorts as oldest", Long.MIN_VALUE, undated)
    }

    // =========================================================================
    // The at-cap safe
    // =========================================================================

    @Test
    fun `a full folder keeps every incumbent and drops the newcomer`() {
        val incumbents = (1..15).map { write(dayName(it), "store$it") }
        val newcomer = write(dayName(30), "brand-new-store")

        SnapshotLibrarian.pruneFolder(temp.root, addedFilename = newcomer.name)

        assertEquals(
            "every incumbent must survive — the newcomer is the one that has not earned a slot",
            incumbents.map { it.name }.toSet(),
            surviving(),
        )
    }

    @Test
    fun `a full folder drops the newcomer even when it duplicates an incumbent`() {
        val incumbents = (1..15).map { write(dayName(it), "store$it") }
        val newcomer = write(dayName(30), "store1")

        SnapshotLibrarian.pruneFolder(temp.root, addedFilename = newcomer.name)

        assertEquals(incumbents.map { it.name }.toSet(), surviving())
    }

    @Test
    fun `below cap the newcomer is admitted and nothing is deleted`() {
        val incumbents = (1..3).map { write(dayName(it), "store$it") }
        val newcomer = write(dayName(30), "brand-new-store")

        SnapshotLibrarian.pruneFolder(temp.root, addedFilename = newcomer.name)

        assertEquals((incumbents + newcomer).map { it.name }.toSet(), surviving())
    }

    @Test
    fun `below cap a fresher capture of identical content still replaces the incumbent`() {
        // The safe is scoped to the at-cap case on purpose: the newest-per-fingerprint
        // refresh is the retention policy's whole point and must survive #929's fix.
        val stale = write(dayName(1), "store1")
        val other = write(dayName(2), "store2")
        val fresh = write(dayName(30), "store1")

        SnapshotLibrarian.pruneFolder(temp.root, addedFilename = fresh.name)

        assertEquals(setOf(other.name, fresh.name), surviving())
        assertTrue("the stale duplicate should have been pruned", !stale.exists())
    }
}
