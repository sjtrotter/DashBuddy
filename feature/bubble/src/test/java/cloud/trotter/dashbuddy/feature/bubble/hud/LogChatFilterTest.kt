package cloud.trotter.dashbuddy.feature.bubble.hud

import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.domain.privacy.PiiShapes
import org.junit.Assert.*
import org.junit.Test

class LogChatFilterTest {
    @Test fun `offer outcome lines are dropped as redundant`() {
        listOf("Offer Accepted", "Offer Declined", "Offer Timed Out!").forEach { text ->
            assertFalse(LogChatFilter.keep(logChat(text)))
            assertFalse(LogChatFilter.keep(logChat("$text (offer replaced)")))
        }
    }
    @Test fun `accept and decline acknowledgements are dropped`() {
        listOf("Accepting…", "Declining…", "Decline already submitted…", "Decline already submitted \u2014 Accept won't take").forEach { assertFalse(LogChatFilter.keep(logChat(it))) }
    }
    @Test fun `pickup dropoff and earnings personas are dropped`() {
        listOf(ChatPersona.Navigator, ChatPersona.Dropoff, ChatPersona.Shopper, ChatPersona.Earnings,
            ChatPersona.Merchant("Panera Bread"), ChatPersona.GoodOffer, ChatPersona.BadOffer,
            ChatPersona.Inspector, ChatPersona.Dasher).forEach { assertFalse(LogChatFilter.keep(logChat("Store", it))) }
    }
    @Test fun `customer persona is dropped for privacy`() {
        assertFalse(LogChatFilter.keep(logChat("Order ready", ChatPersona.Customer(""))))
    }
    @Test fun `item count note is kept`() {
        assertTrue(LogChatFilter.keep(logChat("Panera Bread: 12 items")))
    }
    @Test fun `tip notification line is kept`() {
        assertTrue(LogChatFilter.keep(logChat("$5.00 tip from Panera Bread")))
    }
    @Test fun `pause and resume lines are kept`() {
        listOf("Dash Paused!", "Session resumed (grace)").forEach { assertTrue(LogChatFilter.keep(logChat(it))) }
    }
    @Test fun `session start and done lines are kept`() {
        listOf("DoorDash shift started", "DoorDash shift ended").forEach { assertTrue(LogChatFilter.keep(logChat(it))) }
        assertFalse(LogChatFilter.keep(logChat("Shift ended. Earned $20.00")))
        assertFalse(LogChatFilter.keep(logChat("Session Ended: $20.00")))
    }
    @Test fun `unknown dispatcher text is kept`() {
        assertTrue(LogChatFilter.keep(logChat("New dispatch status")))
        assertTrue(LogChatFilter.keep(logChat("New system status", ChatPersona.System)))
    }
    @Test fun `encoded private text and hashes are dropped`() {
        assertFalse(LogChatFilter.keep(logChat("Store &Eacute;")))
        assertFalse(LogChatFilter.keep(logChat("status " + "a".repeat(64))))
    }
    @Test fun `a line carrying a customer name pattern is never kept`() {
        // Synthesize the shape from merchant-only vocabulary; no customer literal in a fixture.
        val shaped = "Store " + ('P' + 1)
        assertTrue(PiiShapes.hasNameShape(shaped))
        val candidates = listOf(logChat(shaped), logChat("<b>$shaped</b>"),
            logChat("Store<br>" + ('P' + 1)),
            logChat("Store &#${('P' + 1).code};"), logChat("$shaped is waiting", ChatPersona.System),
            logChat("Dash Paused!"), logChat("Panera Bread: 12 items"))
        val kept = candidates.filter(LogChatFilter::keep)
        assertEquals(2, kept.size)
        kept.forEach { assertFalse(PiiShapes.hasNameShape(LogChatFilter.plainText(it.text))) }
    }
    @Test fun `a name split by a run of spaces is never kept`() {
        listOf("     ", "\t", "\u00a0").forEach { gap ->
            val html = "<b>Store${gap}" + ('P' + 1) + "</b>"
            assertNull(LogChatFilter.kept(logChat(html)))
            assertFalse(LogChatFilter.keep(logChat(html)))
        }
    }
    @Test fun `the kept text is the collapsed plain text`() {
        val html = "  <b>Panera     Bread</b>\t&amp;\u00a0status:&nbsp;12&#32;items  "
        assertEquals("Panera Bread & status: 12 items", LogChatFilter.kept(logChat(html)))
    }

}
