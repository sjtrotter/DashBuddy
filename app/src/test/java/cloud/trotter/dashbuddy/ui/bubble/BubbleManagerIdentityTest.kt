package cloud.trotter.dashbuddy.ui.bubble

import android.content.Intent
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #438 B4 (actuation identity): the pure identity helpers on [BubbleManager] — the per-offer
 * notification id and the per-offer intent-data URI — so two concurrent offers (multi-platform or
 * fast replacement) get distinct banners AND distinct Accept/Decline PendingIntents. The
 * distinctness is proved via [Intent.filterEquals], the exact equality PendingIntent identity uses
 * (extras are excluded from it; the URI is what makes the intents distinct). Robolectric supplies
 * `Uri`/`Intent`.
 */
@RunWith(RobolectricTestRunner::class)
class BubbleManagerIdentityTest {

    // --- notification id ---

    @Test
    fun `distinct offer hashes yield distinct notification ids`() {
        assertNotEquals(
            BubbleManager.offerNotificationId("dd-1"),
            BubbleManager.offerNotificationId("uber-2"),
        )
    }

    @Test
    fun `notification id is stable for the same hash`() {
        assertEquals(
            BubbleManager.offerNotificationId("dd-1"),
            BubbleManager.offerNotificationId("dd-1"),
        )
    }

    @Test
    fun `a null hash maps to the reserved legacy offer id`() {
        assertEquals(BubbleManager.OFFER_NOTIFICATION_ID, BubbleManager.offerNotificationId(null))
    }

    @Test
    fun `an offer id can never equal ANY app fixed id - the disjoint-namespace property`() {
        // Adversarial-review MED-1: an enumerated "nudge" list (1, 2) missed the odometer's 101 and
        // would rot as ids are added. The property, not magic values: every derived id lives in
        // [2^30, 2^31), disjoint from every small fixed constant — including hashes that land
        // exactly ON a fixed id.
        val hashTo1 = String(charArrayOf(0.toChar(), 1.toChar())) // hashCode == 1
        val hashTo2 = String(charArrayOf(0.toChar(), 2.toChar())) // hashCode == 2
        assertEquals(BubbleManager.BUBBLE_NOTIFICATION_ID, hashTo1.hashCode()) // guard the fixture
        assertEquals(BubbleManager.OFFER_NOTIFICATION_ID, hashTo2.hashCode())
        for (hash in listOf(hashTo1, hashTo2, "dd-1", "uber-2", "x")) {
            val id = BubbleManager.offerNotificationId(hash)
            assertTrue(
                "derived id $id for '$hash' must sit in the disjoint [2^30, 2^31) namespace",
                id >= 0x4000_0000,
            )
        }
    }

    // --- intent identity (data URI) ---

    private fun actionIntent(platform: Platform, hash: String, action: String): Intent =
        // Only `data` differs across these intents; filterEquals then reduces to URI equality —
        // exactly what distinguishes two offers' PendingIntents under FLAG_UPDATE_CURRENT.
        Intent().apply { data = BubbleManager.offerActionUri(platform, hash, action) }

    @Test
    fun `two offers accept intents are filterEquals-distinct`() {
        val a = actionIntent(Platform.DoorDash, "dd-1", OfferIntent.ACCEPT)
        val b = actionIntent(Platform.Uber, "uber-2", OfferIntent.ACCEPT)
        assertFalse("distinct offers must not filterEquals", a.filterEquals(b))
    }

    @Test
    fun `same offer accept vs decline are filterEquals-distinct`() {
        val accept = actionIntent(Platform.DoorDash, "dd-1", OfferIntent.ACCEPT)
        val decline = actionIntent(Platform.DoorDash, "dd-1", OfferIntent.DECLINE)
        assertFalse("accept and decline must not filterEquals", accept.filterEquals(decline))
    }

    @Test
    fun `identical offer and action filterEquals the same`() {
        val one = actionIntent(Platform.DoorDash, "dd-1", OfferIntent.ACCEPT)
        val two = actionIntent(Platform.DoorDash, "dd-1", OfferIntent.ACCEPT)
        assertTrue("same offer+action must be identity-equal", one.filterEquals(two))
    }

    @Test
    fun `the uri carries platform hash and action`() {
        val uri = BubbleManager.offerActionUri(Platform.DoorDash, "dd-1", OfferIntent.ACCEPT)
        assertEquals("dashbuddy", uri.scheme)
        assertEquals("offer", uri.authority)
        assertEquals(listOf(Platform.DoorDash.wire, "dd-1"), uri.pathSegments)
        assertEquals(OfferIntent.ACCEPT, uri.getQueryParameter("action"))
    }
}
