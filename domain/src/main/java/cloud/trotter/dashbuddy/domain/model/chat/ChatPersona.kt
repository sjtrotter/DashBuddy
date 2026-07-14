package cloud.trotter.dashbuddy.domain.model.chat

import java.util.Locale

sealed class ChatPersona {
    abstract val id: String
    abstract val displayName: String

    /**
     * Principle-7 label for INFO+ (shareable) log lines: the persona KIND, never raw
     * merchant/customer text. Abstract on purpose (#772 review MED-1): a default of
     * `displayName` would let a future name-bearing persona silently leak — every
     * subclass must consciously declare its shareable label.
     */
    abstract val logLabel: String

    data object Dispatcher : ChatPersona() {
        override val id = "bot_dispatcher"
        override val displayName = "Dispatch"
        override val logLabel get() = displayName
    }

    data object System : ChatPersona() {
        override val id = "bot_system"
        override val displayName = "System"
        override val logLabel get() = displayName
    }

    data object Dasher : ChatPersona() {
        override val id = "dasher_self"
        override val displayName = "You"
        override val logLabel get() = displayName
    }

    data class Merchant(val merchantName: String) : ChatPersona() {
        override val id = "merchant_${merchantName.lowercase().replace(" ", "_")}"
        override val displayName = merchantName
        override val logLabel = "Merchant"
    }

    data class Customer(val customerName: String) : ChatPersona() {
        override val id = "customer_${customerName.lowercase().replace(" ", "_")}"
        override val displayName = customerName
        override val logLabel = "Customer"
    }

    data object GoodOffer : ChatPersona() {
        override val id = "good_offer"
        override val displayName = "Good Offer"
        override val logLabel get() = displayName
    }

    data object BadOffer : ChatPersona() {
        override val id = "bad_offer"
        override val displayName = "Bad Offer"
        override val logLabel get() = displayName
    }

    data object Inspector : ChatPersona() {
        override val id = "inspector"
        override val displayName = "Inspector"
        override val logLabel get() = displayName
    }

    data object Navigator : ChatPersona() {
        override val id = "navigator"
        override val displayName = "Navigator"
        override val logLabel get() = displayName
    }

    data object Shopper : ChatPersona() {
        override val id = "shopper"
        override val displayName = "Shopper"
        override val logLabel get() = displayName
    }

    data object Earnings : ChatPersona() {
        override val id = "earnings"
        override val displayName = "Earnings"
        override val logLabel get() = displayName
    }

    companion object {
        /**
         * SSOT for parsing a wire/string persona token into a [ChatPersona] (#audit-2).
         *
         * Case-INSENSITIVE (`Locale.ROOT`), so it accepts both the rule-engine
         * lowercase tokens (`"dispatcher"`, `"good_offer"`, …) and the persisted
         * UPPERCASE persisted-entity tokens (`"DISPATCHER"`, `"GOOD_OFFER"`, …)
         * — the union of the two former hand-maintained `when`-tables. Exhaustive
         * over every persona constant; an unknown/null token falls back to
         * [Dispatcher]. The name-bearing personas ([Dasher] aside) carry the
         * supplied [name]; callers that have no name (e.g. the rule-engine bubble
         * verb, whose schema can't produce a Merchant/Customer) pass the default.
         */
        fun fromWire(wire: String?, name: String = ""): ChatPersona =
            when (wire?.uppercase(Locale.ROOT)) {
                "DISPATCHER" -> Dispatcher
                "SYSTEM" -> System
                "DASHER" -> Dasher
                "MERCHANT" -> Merchant(name)
                "CUSTOMER" -> Customer(name)
                "GOOD_OFFER" -> GoodOffer
                "BAD_OFFER" -> BadOffer
                "INSPECTOR" -> Inspector
                "NAVIGATOR" -> Navigator
                "SHOPPER" -> Shopper
                "EARNINGS" -> Earnings
                else -> Dispatcher
            }
    }
}