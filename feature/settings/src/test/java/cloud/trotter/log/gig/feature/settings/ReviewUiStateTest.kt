package cloud.trotter.log.gig.feature.settings

import cloud.trotter.census.contract.contributor.ContributorRole
import cloud.trotter.census.contract.contributor.SlotClass
import cloud.trotter.log.gig.core.data.census.contributor.AskOutcome
import cloud.trotter.log.gig.core.data.census.contributor.AskRefusal
import cloud.trotter.log.gig.core.data.census.contributor.ClassesState
import cloud.trotter.log.gig.core.data.census.contributor.ReviewLoad
import cloud.trotter.log.gig.core.data.census.contributor.ReviewRow
import cloud.trotter.log.gig.core.data.census.contributor.ScreenReview
import cloud.trotter.log.gig.core.data.census.contributor.SendOutcome
import cloud.trotter.log.gig.core.data.census.contributor.SlotValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1626 (Sol S6): the review screen's state when the grant stops standing. Every value is fictional. */
class ReviewUiStateTest {
    private val review = ScreenReview(
        "r", "2026-11-13",
        listOf(ReviewRow("root/0/text", "android.widget.TextView", "text", SlotValue.Hashed, SlotClass.CONSTANT, "Pick up by")),
        ClassesState.LOADED, wordsKept = true,
    )
    private val ready = ReviewUiState(
        phase = ReviewPhase.READY, review = review, confirmed = setOf("root/0/text"), roles = mapOf("root/0/text" to ContributorRole.OTHER),
    )

    @Test fun `a grant that stops standing clears the rows, words and choices and offers nothing`() {
        val ended = ready.grantEnded()
        assertEquals(ReviewPhase.NOT_ALLOWED, ended.phase)
        assertNull(ended.review)
        assertEquals(emptySet<String>(), ended.confirmed)
        assertEquals(emptyMap<String, ContributorRole>(), ended.roles)
        assertFalse(ended.canSend)
        assertEquals(ReviewPhase.NOT_ALLOWED, ready.copy(phase = ReviewPhase.SENDING).grantEnded().phase)
    }

    @Test fun `a session or Send that finishes after the grant ended does not bring the rows back`() {
        val ended = ReviewUiState().grantEnded()
        assertEquals(ended, ended.loaded(ReviewLoad.Ready(review)))
        assertEquals(ended, ended.sent(SendOutcome.SENT))
        assertEquals(ReviewPhase.READY, ReviewUiState().loaded(ReviewLoad.Ready(review)).phase)
    }

    @Test fun `a revocation on Send clears the screen like a grant end`() {
        val sending = ready.copy(phase = ReviewPhase.SENDING)
        listOf(SendOutcome.NOT_ALLOWED, SendOutcome.INSTALL_REVOKED).forEach {
            val after = sending.sent(it)
            assertEquals(ReviewPhase.NOT_ALLOWED, after.phase)
            assertNull(after.review)
        }
        assertEquals(SendOutcome.LATER, sending.sent(SendOutcome.LATER).failure)
        assertNull(sending.sent(SendOutcome.SENT).review)
    }

    @Test fun `Ask the developer leaves the screen with the deletion day, or keeps the review and says why`() {
        val asking = ready.copy(phase = ReviewPhase.ASKING)
        val sent = asking.asked(AskOutcome.Sent("2026-11-17"))
        assertEquals(ReviewPhase.ASKED, sent.phase)
        assertEquals("2026-11-17", sent.askedExpiresDay)
        assertFalse(sent.askUnconfirmed)
        assertNull(sent.review)
        assertEquals(emptySet<String>(), sent.confirmed)
        assertEquals(true, asking.asked(AskOutcome.Unconfirmed("2026-11-17")).askUnconfirmed)
        listOf(AskRefusal.LATER, AskRefusal.UNREACHABLE, AskRefusal.REFUSED, AskRefusal.UNCONFIGURED).forEach {
            val kept = asking.asked(AskOutcome.NotSent(it))
            assertEquals(ReviewPhase.READY, kept.phase)
            assertEquals(it, kept.askFailure)
            assertEquals(review, kept.review)
        }
        listOf(AskRefusal.NOT_ALLOWED, AskRefusal.INSTALL_REVOKED).forEach {
            assertNull(asking.asked(AskOutcome.NotSent(it)).review)
        }
        assertEquals(ReviewPhase.NOT_FOUND, asking.asked(AskOutcome.NotSent(AskRefusal.NOT_FOUND)).phase)
        // A grant that ended while it ran stays ended; a sent ask keeps its message once the grant ends.
        val ended = ReviewUiState().grantEnded()
        assertEquals(ended, ended.asked(AskOutcome.Sent("2026-11-17")))
        assertEquals(ReviewPhase.ASKED, sent.grantEnded().phase)
        assertFalse(asking.canAsk)
        assertTrue(ready.canAsk)
    }
}
