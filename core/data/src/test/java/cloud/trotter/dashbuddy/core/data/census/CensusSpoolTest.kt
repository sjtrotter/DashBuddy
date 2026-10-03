package cloud.trotter.dashbuddy.core.data.census

import cloud.trotter.dashbuddy.domain.census.CensusUploadStats
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CensusSpoolTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `clear removes every record the in flight marker and temp files and the queued flow reports zero`() = runTest {
        val dir = File(tmp.newFolder(), "spool")
        val stats = CensusUploadStats()
        val spool = CensusSpool(dir, stats, StandardTestDispatcher(testScheduler), now = { 10L })
        repeat(3) { spool.append(censusRecord(it)) }
        spool.markInFlight(spool.take(2, 100_000).map { it.id }, "reset-batch")
        File(dir, "x.tmp").writeText("interrupted")
        File(dir.parentFile, "inflight.json.tmp").writeText("interrupted") // Process death mid markInFlight.
        assertEquals(3, spool.queued.first())

        spool.clear()

        assertEquals(0, spool.count())
        assertEquals(0, spool.queued.first())
        assertNull(spool.inFlight())
        assertTrue(dir.listFiles()!!.isEmpty())
        assertFalse(File(dir.parentFile, "inflight.json").exists())
        assertFalse(File(dir.parentFile, "inflight.json.tmp").exists())
        assertEquals(0L, stats.spoolDropped.get())
        assertTrue(spool.take(100, 100_000).isEmpty())
        spool.append(censusRecord(3))
        assertEquals(1, spool.queued.first())
    }

    @Test fun `in flight survives reopening with original order and batch id ahead of new arrivals`() = runTest {
        val dir = File(tmp.newFolder(), "spool")
        val stats = CensusUploadStats()
        val io = StandardTestDispatcher(testScheduler)
        val spool = CensusSpool(dir, stats, io, now = { 10L })
        repeat(2) { spool.append(censusRecord(it)) }
        val original = spool.take(100, 100_000).reversed()
        val ids = original.map { it.id }
        spool.markInFlight(ids, "original-batch")
        val state = File(dir.parentFile, "inflight.json")
        assertEquals("{\"batchId\":\"original-batch\",\"ids\":[\"10-2.json\",\"10-1.json\"]}", state.readText())
        assertFalse(File(dir.parentFile, "inflight.json.tmp").exists())
        spool.append(censusRecord(2))

        val reopened = CensusSpool(dir, stats, io, now = { 10L })
        assertEquals(CensusSpool.InFlight("original-batch", ids), reopened.inFlight())
        // A retried set cannot change even if the caller's fresh-batch limits change.
        assertEquals(original, reopened.take(1, 1))
        reopened.remove(ids)
        reopened.clearInFlight()
        reopened.clearInFlight()
        assertNull(reopened.inFlight())
        assertFalse(state.exists())
        assertEquals(listOf(censusRecord(2).fingerprint), reopened.take(100, 100_000).map { it.fingerprint })
    }

    @Test fun `in flight skips missing files and only takes fresh items when none remain`() = runTest {
        val dir = File(tmp.newFolder(), "spool")
        val stats = CensusUploadStats()
        val io = StandardTestDispatcher(testScheduler)
        val spool = CensusSpool(dir, stats, io, now = { 10L })
        repeat(3) { spool.append(censusRecord(it)) }
        val items = spool.take(100, 100_000)
        spool.markInFlight(items.take(2).map { it.id }, "retained-batch")
        assertTrue(File(dir, items[0].id).delete())

        val reopened = CensusSpool(dir, stats, io)
        assertEquals(listOf(items[1]), reopened.take(100, 100_000))
        assertEquals("retained-batch", reopened.inFlight()?.batchId)
        assertTrue(File(dir, items[1].id).delete())
        assertEquals(listOf(items[2]), reopened.take(100, 100_000))
        assertNull(reopened.inFlight())
    }

    @Test fun `corrupt in flight is removed counted once and fresh items remain drainable`() = runTest {
        for (corrupt in listOf("broken", "{}", "{\"batchId\":\"bad\",\"ids\":[\"../escape\"]}")) {
            val dir = File(tmp.newFolder(), "spool")
            val stats = CensusUploadStats()
            val io = StandardTestDispatcher(testScheduler)
            val spool = CensusSpool(dir, stats, io)
            repeat(2) { spool.append(censusRecord(it)) }
            val expected = spool.take(100, 100_000)
            val state = File(dir.parentFile, "inflight.json")
            state.writeText(corrupt)
            val reopened = CensusSpool(dir, stats, io)
            assertEquals(expected, reopened.take(100, 100_000))
            assertFalse(state.exists())
            assertNull(reopened.inFlight())
            assertEquals(1L, stats.inflightCorrupt.get())
            assertEquals(0L, stats.spoolDropped.get())
            assertEquals(2, reopened.count())
        }
    }

    @Test fun `atomic append preserves exact item bytes and survives reopening`() = runTest {
        val dir = tmp.newFolder()
        val stats = CensusUploadStats()
        val io = StandardTestDispatcher(testScheduler)
        val spool = CensusSpool(dir, stats, io, now = { 10L })
        val record = censusRecord()
        spool.append(record)
        assertEquals(listOf("10-1.json"), dir.listFiles()!!.map { it.name })
        assertTrue(File(dir, "10-1.json").readText().contains("\"skeleton\":${record.skeletonJson},\"captureId\":null"))
        val reopened = CensusSpool(dir, stats, io, now = { 10L })
        assertEquals(1, reopened.count())
        assertEquals(record.skeletonJson, reopened.take(1, 1024).single().itemJson)
        reopened.append(censusRecord(1))
        assertEquals(2, reopened.count())
        assertFalse(dir.listFiles()!!.any { it.extension == "tmp" })
    }

    @Test fun `epoch then numeric sequence determine ordering and file cap drops oldest`() = runTest {
        val stats = CensusUploadStats()
        val spool = CensusSpool(tmp.newFolder(), stats, StandardTestDispatcher(testScheduler), maxFiles = 10, now = { 1L })
        repeat(12) { spool.append(censusRecord(it)) }
        assertEquals((2..11).map { censusRecord(it).fingerprint }, spool.take(100, 100_000).map { it.fingerprint })
        assertEquals(2L, stats.spoolDropped.get())
        assertEquals(10, spool.count())
    }

    @Test fun `byte cap drops oldest even below file cap`() = runTest {
        val dir = tmp.newFolder()
        val stats = CensusUploadStats()
        val io = StandardTestDispatcher(testScheduler)
        CensusSpool(dir, stats, io).append(censusRecord())
        val recordBytes = dir.listFiles()!!.single().length()
        val spool = CensusSpool(dir, stats, io, maxDiskBytes = recordBytes * 2)
        spool.append(censusRecord(1))
        spool.append(censusRecord(2))
        assertEquals(listOf(censusRecord(1).fingerprint, censusRecord(2).fingerprint), spool.take(10, 10_000).map { it.fingerprint })
        assertEquals(1L, stats.spoolDropped.get())
        assertTrue(dir.listFiles()!!.sumOf { it.length() } <= recordBytes * 2)
    }

    @Test fun `corrupt file is deleted and counted and temporary file is recovered`() = runTest {
        val dir = tmp.newFolder()
        File(dir, "1-1.json").writeText("broken")
        File(dir, "2-2.json.tmp").writeText("interrupted")
        val stats = CensusUploadStats()
        val spool = CensusSpool(dir, stats, StandardTestDispatcher(testScheduler))
        assertTrue(spool.take(100, 1024).isEmpty())
        assertEquals(1L, stats.spoolCorrupt.get())
        assertEquals(1L, stats.spoolDropped.get())
        assertEquals(0, spool.count())
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun `take respects both bounds removes oversized and removal is idempotent`() = runTest {
        val stats = CensusUploadStats()
        val spool = CensusSpool(tmp.newFolder(), stats, StandardTestDispatcher(testScheduler))
        repeat(3) { spool.append(censusRecord(it)) }
        val bytes = censusRecord().itemBytes
        assertEquals(1, spool.take(1, bytes * 3).size)
        val pair = spool.take(3, bytes * 2)
        assertEquals(2, pair.size)
        spool.remove(pair.map { it.id })
        spool.remove(pair.map { it.id })
        assertEquals(1, spool.count())
        assertTrue(spool.take(10, bytes - 1).isEmpty())
        assertEquals(1L, stats.spoolOversized.get())
        assertEquals(1L, stats.spoolDropped.get())
    }
}
