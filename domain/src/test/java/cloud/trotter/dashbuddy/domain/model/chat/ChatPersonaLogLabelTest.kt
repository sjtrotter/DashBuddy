package cloud.trotter.dashbuddy.domain.model.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The #772 / Principle-7 gate: [ChatPersona.logLabel] is the ONLY persona field allowed into
 * INFO+ (shareable) log lines — [ChatPersona.displayName] carries raw merchant/customer text for
 * name-bearing personas and must never reach that stream. Store-name-shaped fixtures (with real
 * punctuation — a hyphen, a curly apostrophe, an ampersand) stand in for the raw text a recognized
 * merchant/customer name actually carries.
 */
class ChatPersonaLogLabelTest {

    /** One seed instance per sealed subclass — fed through [fixtureFor] below. */
    private val seedPersonas: List<ChatPersona> = listOf(
        ChatPersona.Dispatcher,
        ChatPersona.System,
        ChatPersona.Dasher,
        ChatPersona.Merchant("seed"),
        ChatPersona.Customer("seed"),
        ChatPersona.GoodOffer,
        ChatPersona.BadOffer,
        ChatPersona.Inspector,
        ChatPersona.Navigator,
        ChatPersona.Shopper,
        ChatPersona.Earnings,
    )

    /**
     * Builds the store-name-shaped fixture for [persona]'s kind. The `when` is exhaustive over
     * the sealed [ChatPersona] hierarchy with NO `else` branch — adding a new subclass breaks
     * compilation of this test until a fixture is supplied for it, so a new persona can't
     * silently skip the leak check below.
     */
    private fun fixtureFor(persona: ChatPersona): ChatPersona = when (persona) {
        is ChatPersona.Dispatcher -> ChatPersona.Dispatcher
        is ChatPersona.System -> ChatPersona.System
        is ChatPersona.Dasher -> ChatPersona.Dasher
        is ChatPersona.Merchant -> ChatPersona.Merchant("H-E-B")
        is ChatPersona.Customer -> ChatPersona.Customer("Willie's Grill & Icehouse's customer")
        is ChatPersona.GoodOffer -> ChatPersona.GoodOffer
        is ChatPersona.BadOffer -> ChatPersona.BadOffer
        is ChatPersona.Inspector -> ChatPersona.Inspector
        is ChatPersona.Navigator -> ChatPersona.Navigator
        is ChatPersona.Shopper -> ChatPersona.Shopper
        is ChatPersona.Earnings -> ChatPersona.Earnings
    }

    // Store-name-shaped tokens with real punctuation (hyphen, curly apostrophe U+2019, ampersand).
    private val storeNameTokens = listOf(
        "H-E-B",
        "Parry’s Pizzeria & Taphouse",
        "Willie's Grill & Icehouse's customer",
    )

    @Test
    fun `no persona logLabel leaks a store-name-shaped fixture token`() {
        val fixtures = seedPersonas.map { fixtureFor(it) } +
            ChatPersona.Merchant("Parry’s Pizzeria & Taphouse")
        for (persona in fixtures) {
            for (token in storeNameTokens) {
                assertFalse(
                    "logLabel for $persona leaked '$token': ${persona.logLabel}",
                    persona.logLabel.contains(token),
                )
            }
        }
    }

    @Test
    fun `Merchant logLabel is the constant kind label, never the merchant name`() {
        assertEquals("Merchant", ChatPersona.Merchant("H-E-B").logLabel)
    }

    @Test
    fun `Customer logLabel is the constant kind label, never the customer name`() {
        assertEquals("Customer", ChatPersona.Customer("H-E-B's customer").logLabel)
    }
}
