package cloud.trotter.dashbuddy.state.effects

import android.content.Intent
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.ui.bubble.BubbleManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #438 item 8a: the notification-action broadcast maps its identity extras onto the dispatched
 * [Observation.UiInput], so an Accept/Decline from the heads-up carries a real target platform +
 * offerHash (an Unknown-platform tap steps no region post-#682). Robolectric supplies the Intent.
 */
@RunWith(RobolectricTestRunner::class)
class OfferActionReceiverTest {

    private fun intentWith(action: String?, platformWire: String?, offerHash: String?) =
        Intent(OfferActionReceiver.ACTION).apply {
            action?.let { putExtra(OfferActionReceiver.EXTRA_ACTION, it) }
            platformWire?.let { putExtra(OfferActionReceiver.EXTRA_PLATFORM, it) }
            offerHash?.let { putExtra(OfferActionReceiver.EXTRA_OFFER_HASH, it) }
        }

    @Test
    fun `accept extras yield a UiInput with a real platform and offer hash`() {
        val ui = OfferActionReceiver.uiInputFrom(
            intentWith(OfferIntent.ACCEPT, Platform.Uber.wire, "offer-U"),
        )!!

        assertEquals(OfferIntent.ACCEPT, ui.action)
        assertEquals(Platform.Uber, ui.targetPlatform)
        assertEquals(Platform.Uber, ui.platform) // the derived override honours the stamp
        assertEquals("offer-U", ui.offerHash)
    }

    @Test
    fun `decline extras carry the DoorDash identity`() {
        val ui = OfferActionReceiver.uiInputFrom(
            intentWith(OfferIntent.DECLINE, Platform.DoorDash.wire, "offer-D"),
        )!!

        assertEquals(OfferIntent.DECLINE, ui.action)
        assertEquals(Platform.DoorDash, ui.targetPlatform)
        assertEquals("offer-D", ui.offerHash)
    }

    @Test
    fun `a broadcast with no action is dropped`() {
        assertNull(OfferActionReceiver.uiInputFrom(intentWith(null, Platform.Uber.wire, "x")))
    }

    /**
     * #438 B4: the production PendingIntent now also carries a per-offer identity `data` URI. The
     * receiver still reads identity from the extras (the URI is PendingIntent identity only), so a
     * full production-shaped intent round-trips to the same UiInput — and the per-offer cancel id
     * derived from the tap's own carried hash targets that offer's banner alone.
     */
    @Test
    fun `a full production intent round-trips identity and yields the per-offer cancel id`() {
        val intent = Intent(OfferActionReceiver.ACTION).apply {
            data = BubbleManager.offerActionUri(Platform.DoorDash, "dd-7", OfferIntent.ACCEPT)
            putExtra(OfferActionReceiver.EXTRA_ACTION, OfferIntent.ACCEPT)
            putExtra(OfferActionReceiver.EXTRA_PLATFORM, Platform.DoorDash.wire)
            putExtra(OfferActionReceiver.EXTRA_OFFER_HASH, "dd-7")
        }
        val ui = OfferActionReceiver.uiInputFrom(intent)!!

        assertEquals(OfferIntent.ACCEPT, ui.action)
        assertEquals(Platform.DoorDash, ui.targetPlatform)
        assertEquals("dd-7", ui.offerHash)
        // The receiver's inline dismiss uses this id — offer dd-7's banner, not any other offer's.
        assertEquals(
            BubbleManager.offerNotificationId("dd-7"),
            BubbleManager.offerNotificationId(ui.offerHash),
        )
        assertNotEquals(
            BubbleManager.offerNotificationId("dd-7"),
            BubbleManager.offerNotificationId("uber-9"),
        )
    }
}
