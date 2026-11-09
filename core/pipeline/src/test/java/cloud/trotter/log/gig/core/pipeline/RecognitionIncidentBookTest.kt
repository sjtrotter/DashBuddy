package cloud.trotter.log.gig.core.pipeline

import cloud.trotter.log.gig.domain.pipeline.ParseRotCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1560 — the pure incident ledger: one incident per platform for both causes, closure on the last
 * clear, trips before clears, and the one-notice-a-day limit (seeded, and failing open on a clock that
 * moved back).
 */
class RecognitionIncidentBookTest {

    private val dd = "doordash"
    private val ruleA = "doordash.screen.offer_card"
    private val ruleB = "doordash.screen.delivery_receipt"
    private val hour = 60 * 60 * 1000L
    private val day = RecognitionIncidentBook.NOTICE_INTERVAL_MS

    private fun cause(rule: String = ruleA, at: Long) =
        ParseRotCause(ruleId = rule, shape = "offer", nullFields = listOf("payAmount"), trippedAt = at)

    // 1
    @Test
    fun `the first rot trip opens an incident whose notice is due once`() {
        val book = RecognitionIncidentBook()
        assertTrue(book.rotTripped(dd, cause(at = 1_000)))

        val notice = book.takeDueNotice(dd, 2_000)
        assertNotNull(notice)
        assertEquals(2_000L, notice?.notifiedAt)
        assertEquals(1_000L, notice?.openedAt)
        assertNull(book.takeDueNotice(dd, 3_000))
    }

    // 2
    @Test
    fun `both causes share one incident`() {
        val book = RecognitionIncidentBook()
        book.rotTripped(dd, cause(at = 1_000))
        assertNotNull(book.takeDueNotice(dd, 1_000))

        assertTrue(book.unknownTripped(dd, 50_000))

        val open = book.open().single()
        assertTrue(open.screensUnrecognized)
        assertEquals(listOf(ruleA), open.parseRot.map { it.ruleId })
        assertEquals(1_000L, open.openedAt)
        assertNull("the incident already posted", book.takeDueNotice(dd, 50_000))
    }

    // 3
    @Test
    fun `an incident closes only when its last cause clears`() {
        val withUnknown = RecognitionIncidentBook()
        withUnknown.unknownTripped(dd, 1_000)
        withUnknown.rotTripped(dd, cause(at = 2_000))
        assertTrue(withUnknown.rotCleared(dd, ruleA))
        assertTrue("the UNKNOWN cause keeps it open", withUnknown.hasIncident(dd))
        assertEquals(emptyList<String>(), withUnknown.closedSinceLastTake())

        val rotOnly = RecognitionIncidentBook()
        rotOnly.rotTripped(dd, cause(at = 1_000))
        rotOnly.takeDueNotice(dd, 1_000)
        assertTrue(rotOnly.rotCleared(dd, ruleA))
        assertFalse(rotOnly.hasIncident(dd))
        assertEquals(listOf(dd), rotOnly.closedSinceLastTake())
        assertEquals(emptyList<String>(), rotOnly.closedSinceLastTake())
        assertEquals(1_000L, rotOnly.lastNoticeAt()[dd])
    }

    // 4
    @Test
    fun `a platform posts at most one notice a day`() {
        val book = RecognitionIncidentBook()
        val t0 = 1_000L
        book.rotTripped(dd, cause(at = t0))
        assertNotNull(book.takeDueNotice(dd, t0))
        book.rotCleared(dd, ruleA)

        book.rotTripped(dd, cause(at = t0 + 2 * hour))
        assertNull(book.takeDueNotice(dd, t0 + 2 * hour))
        assertNull(book.takeDueNotice(dd, t0 + day - 1))
        assertEquals(t0 + day, book.takeDueNotice(dd, t0 + day)?.notifiedAt)
    }

    // 5
    @Test
    fun `a seeded last notice holds the limit across a restart`() {
        val book = RecognitionIncidentBook()
        val t0 = 5_000_000L
        book.seedLastNoticeAt(mapOf(dd to t0))
        book.rotTripped(dd, cause(at = t0 + 2 * hour))

        assertNull(book.takeDueNotice(dd, t0 + 2 * hour))
        assertNotNull(book.takeDueNotice(dd, t0 + 25 * hour))
    }

    // 6
    @Test
    fun `a last notice in the future fails open`() {
        val book = RecognitionIncidentBook()
        val t0 = 5_000_000L
        book.seedLastNoticeAt(mapOf(dd to t0 + hour))
        book.rotTripped(dd, cause(at = t0))

        assertNotNull(book.takeDueNotice(dd, t0))
    }

    // 7
    @Test
    fun `a trip applied before a clear keeps the incident`() {
        val book = RecognitionIncidentBook()
        book.rotTripped(dd, cause(at = 1_000))
        assertNotNull(book.takeDueNotice(dd, 5_000))

        book.rotTripped(dd, cause(rule = ruleB, at = 9_000))
        book.rotCleared(dd, ruleA)

        val open = book.open().single()
        assertEquals(1_000L, open.openedAt)
        assertEquals(5_000L, open.notifiedAt)
        assertEquals(listOf(ruleB), open.parseRot.map { it.ruleId })
        assertEquals(emptyList<String>(), book.closedSinceLastTake())
        assertNull(book.takeDueNotice(dd, 9_000))
    }
}
