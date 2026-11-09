package cloud.trotter.log.gig.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1151 — the consent's pure rules: on/off → decision (LL3) and the Android 11 caveat (MM2); #1414 — what a
 * build that does or does not offer Screen events enforces.
 */
class EventReceiptConsentTest {

    @Test
    fun `of maps on to ALLOWED and off to a durable DECLINED`() {
        assertEquals(EventReceiptConsent.ALLOWED, EventReceiptConsent.of(true))
        assertEquals(EventReceiptConsent.DECLINED, EventReceiptConsent.of(false))
    }

    @Test
    fun `wide receipt is flagged unreliable on API 30 only`() {
        assertFalse(EventReceiptConsent.isWideReceiptReliable(30))
        (31..36).forEach { assertTrue("sdk $it", EventReceiptConsent.isWideReceiptReliable(it)) }
    }

    @Test
    fun `a build that does not offer Screen events enforces the filter for every stored value`() {
        val stored = EventReceiptConsent.entries + listOf<EventReceiptConsent?>(null)
        stored.forEach { value ->
            assertEquals("stored $value", EventReceiptConsent.DECLINED, EventReceiptConsent.enforced(value, offered = false))
        }
    }

    @Test
    fun `a build that offers Screen events enforces the stored value unchanged`() {
        EventReceiptConsent.entries.forEach { value ->
            assertEquals(value, EventReceiptConsent.enforced(value, offered = true))
        }
        assertNull("an unread store stays unread", EventReceiptConsent.enforced(null, offered = true))
    }
}
