package cloud.trotter.log.gig.core.data.census

import cloud.trotter.log.gig.domain.census.CensusSentBatch
import cloud.trotter.log.gig.domain.census.CensusSentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** #1622: the phone's record of what the census sent: counts only, 7 days, bounded. */
class CensusSentLogTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_700_000_000_000L
    private fun log(file: File) = CensusSentLog(file, Dispatchers.Unconfined) { now }

    private val code = "0123456789abcdef"
    private val batchId = "a".repeat(8) + "0123456789abcdef0123456789abcdef".take(24)
    private val fingerprint = "f".repeat(64)
    private fun body(id: String = batchId) = """{"batchId":"$id","items":[{"schemaId":"uinode.skeleton.v2",""" +
        """"fingerprint":"$fingerprint","platform":"doordash","day":"2026-11-13","root":{"text":{"text":{"h":"$code",""" +
        """"kind":"words:1"}},"children":[{"text":{"text":{"kind":"shape","shape":"$#.##"}}}]}}]}"""

    @Test fun `a sent batch is kept as counts, survives a restart and holds no hash, id or template`() = runTest {
        val file = File(tmp.newFolder(), "sent.json")
        log(file).record(CensusSentBatch.SKELETONS, body().toByteArray())

        val restarted = log(file).batches.first().single()
        assertEquals(CensusSentKind.SCREEN, restarted.items.single().kind)
        assertEquals(2, restarted.items.single().nodes)
        assertEquals(1, restarted.items.single().codes)
        assertEquals(1, restarted.items.single().formats)
        val stored = file.readText()
        assertFalse("no word code", stored.contains(code))
        assertFalse("no batch id", stored.contains(batchId))
        assertFalse("no fingerprint", stored.contains(fingerprint))
        assertFalse("no format template", stored.contains("$#.##"))
        assertFalse("no long hex string at all", Regex("[0-9a-f]{16,}").containsMatchIn(stored))
    }

    @Test fun `entries older than 7 days drop on read, from the file too, and the count is bounded`() = runTest {
        val file = File(tmp.newFolder(), "sent.json")
        val sent = log(file)
        sent.record(CensusSentBatch.SKELETONS, body().toByteArray())
        now += CensusSentLog.MAX_AGE_MILLIS + 1
        assertTrue("a read with no write prunes", sent.batches.first().isEmpty())
        assertFalse("and rewrites the file", file.readText().contains("doordash"))

        repeat(CensusSentLog.MAX_BATCHES + 5) { sent.record(CensusSentBatch.SKELETONS, body().toByteArray()) }
        assertEquals(CensusSentLog.MAX_BATCHES, log(file).batches.first().size)
    }

    @Test fun `a failed pruning rewrite is retried by the next read until the file is pruned`() = runTest {
        val dir = tmp.newFolder()
        val file = File(dir, "sent.json")
        val sent = log(file)
        sent.record(CensusSentBatch.SKELETONS, body().toByteArray())
        now += CensusSentLog.MAX_AGE_MILLIS + 1
        assertTrue(dir.setWritable(false))
        try {
            assertTrue("the shown list is pruned even when the rewrite fails", sent.batches.first().isEmpty())
            assertTrue("the file still holds it", file.readText().contains("doordash"))
        } finally {
            dir.setWritable(true)
        }
        assertTrue(sent.batches.first().isEmpty())
        assertFalse("the next read persisted the pruning", file.readText().contains("doordash"))
    }

    @Test fun `a path with no census data and an unreadable file record nothing and fail open`() = runTest {
        val file = File(tmp.newFolder(), "sent.json").apply { writeText("not json") }
        val sent = log(file)
        assertTrue(sent.batches.first().isEmpty())
        sent.record("/v1/enroll", """{"installId":"x"}""".toByteArray())
        assertTrue(sent.batches.first().isEmpty())
        sent.clear()
        assertFalse(file.exists())
    }
}
