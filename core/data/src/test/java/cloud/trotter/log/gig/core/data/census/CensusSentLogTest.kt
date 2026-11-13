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

/** #1622: the phone's record of what the census sent: summaries only, one entry per batch, 7 days, bounded. */
class CensusSentLogTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_700_000_000_000L
    private fun log(file: File) = CensusSentLog(file, Dispatchers.Unconfined) { now }

    private val code = "0123456789abcdef"
    private fun body(batchId: String) = """{"batchId":"$batchId","items":[{"schemaId":"uinode.skeleton.v2","platform":"doordash",""" +
        """"day":"2026-11-13","root":{"text":{"text":{"h":"$code","kind":"words:1"}},"children":[{"text":{"text":{"kind":"shape","shape":"$#.##"}}}]}}]}"""

    @Test fun `a sent batch is kept as counts, survives a restart and holds no code`() = runTest {
        val file = File(tmp.newFolder(), "sent.json")
        log(file).record(CensusSentBatch.SKELETONS, body("b1").toByteArray())

        val restarted = log(file).batches.first().single()
        assertEquals(CensusSentKind.SCREEN, restarted.items.single().kind)
        assertEquals(2, restarted.items.single().nodes)
        assertEquals(1, restarted.items.single().codes)
        assertEquals(1, restarted.items.single().formats)
        assertFalse("the record never keeps a word code", file.readText().contains(code))
        assertFalse("nor a format template", file.readText().contains("$#.##"))
    }

    @Test fun `the same batch sent again is one entry, the latest`() = runTest {
        val sent = log(File(tmp.newFolder(), "sent.json"))
        sent.record(CensusSentBatch.SKELETONS, body("b1").toByteArray())
        now += 1_000
        sent.record(CensusSentBatch.SKELETONS, body("b1").toByteArray())
        sent.record(CensusSentBatch.SKELETONS, body("b2").toByteArray())
        assertEquals(listOf("b2", "b1"), sent.batches.first().map { it.batchId })
        assertEquals(now, sent.batches.first().last().sentAtMillis)
    }

    @Test fun `entries older than 7 days drop, and the count is bounded`() = runTest {
        val file = File(tmp.newFolder(), "sent.json")
        val sent = log(file)
        sent.record(CensusSentBatch.SKELETONS, body("old").toByteArray())
        now += CensusSentLog.MAX_AGE_MILLIS + 1
        repeat(CensusSentLog.MAX_BATCHES + 5) { sent.record(CensusSentBatch.SKELETONS, body("b$it").toByteArray()) }
        val kept = log(file).batches.first()
        assertEquals(CensusSentLog.MAX_BATCHES, kept.size)
        assertTrue(kept.none { it.batchId == "old" })
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
