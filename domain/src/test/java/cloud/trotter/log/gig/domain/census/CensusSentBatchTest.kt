package cloud.trotter.log.gig.domain.census

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #1622: "What gig.log sent" reads each request body's own bytes into kinds, apps and counts. */
class CensusSentBatchTest {
    private fun read(path: String, body: String) = CensusSentBatch.read(path, body.toByteArray(), 7L)

    @Test fun `a screen counts its nodes and each value by how it went`() {
        val batch = read(CensusSentBatch.SKELETONS, """{"batchId":"b","items":[{"schemaId":"uinode.skeleton.v2",""" +
            """"platform":"uber","day":"2026-11-13","windowTitle":{"kind":"withheld"},"root":{"text":{""" +
            """"text":{"h":"0123456789abcdef","kind":"words:2"},"desc":{"kind":"digits"}},"children":[""" +
            """{"text":{"text":{"kind":"shape","shape":"$#.##"}}},{"children":[{"text":{"text":{"kind":"words:8+"}}}]}]}}]}""")!!
        assertEquals("b", batch.batchId)
        assertEquals(7L, batch.sentAtMillis)
        assertEquals(
            CensusSentItem(CensusSentKind.SCREEN, "uber", "2026-11-13", nodes = 4, codes = 1, formats = 1, typeOnly = 2, withheld = 1),
            batch.items.single(),
        )
    }

    @Test fun `a notification counts its five slots and its actions`() {
        val slot = """{"kind":"withheld"}"""
        val batch = read(CensusSentBatch.SKELETONS, """{"batchId":"b","items":[{"kind":"notification",""" +
            """"schemaId":"notification.skeleton.v2","platform":"doordash","day":"2026-11-13","channelId":"c",""" +
            """"slots":{"title":{"h":"0123456789abcdef","kind":"words:1"},"text":$slot,"bigText":$slot,""" +
            """"tickerText":{"kind":"mixed"},"subText":{"kind":"shape","shape":"# min(s)"}},""" +
            """"actions":[{"h":"fedcba9876543210","kind":"words:1"}]}]}""")!!
        assertEquals(
            CensusSentItem(CensusSentKind.NOTIFICATION, "doordash", "2026-11-13", codes = 2, formats = 1, typeOnly = 1, withheld = 2),
            batch.items.single(),
        )
    }

    @Test fun `daily counts and screen text keep the app and no batch id for health`() {
        val health = read(CensusSentBatch.HEALTH, """{"reports":[{"day":"2026-11-12","platform":"doordash","admitted":3}]}""")!!
        assertNull(health.batchId)
        assertEquals(listOf(CensusSentItem(CensusSentKind.DAILY_COUNTS, "doordash", "2026-11-12")), health.items)
        val text = read(CensusSentBatch.ENVELOPES, """{"batchId":"env-1","items":[{"platform":"doordash","payload":{}}]}""")!!
        assertEquals(listOf(CensusSentItem(CensusSentKind.SCREEN_TEXT, "doordash")), text.items)
    }

    @Test fun `a request that carries no census data, or no JSON object, is not a sent batch`() {
        assertNull(read("/v1/enroll", """{"installId":"x"}"""))
        assertNull(read(CensusSentBatch.SKELETONS, "not json"))
        assertNull(read(CensusSentBatch.SKELETONS, "[]"))
    }
}
