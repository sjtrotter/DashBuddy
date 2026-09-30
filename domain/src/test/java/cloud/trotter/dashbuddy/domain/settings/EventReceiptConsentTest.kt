package cloud.trotter.dashbuddy.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1151 — the consent's two pure rules: on/off → decision (LL3) and the Android 11 caveat (MM2). */
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
}
