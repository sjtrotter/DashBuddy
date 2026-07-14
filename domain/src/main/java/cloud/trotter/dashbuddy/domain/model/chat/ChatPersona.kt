package cloud.trotter.dashbuddy.domain.model.chat

import java.util.Locale

sealed class ChatPersona {
    abstract val id: String
    abstract val displayName: String

    /**
     * Principle-7 label for INFO+ (shareable) log lines: the persona KIND, never raw
     * merchant/customer text. Name-bearing personas MUST override this to a constant.
     */
    open val logLabel: String get() = displayName

    data object Dispatcher : ChatPersona() {
        override val id = "bot_dispatcher"
        override val displayName = "Dispatch"
    }

    data object System : ChatPersona() {
        override val id = "bot_system"
        override val displayName = "System"
    }

    data object Dasher : ChatPersona() {
        override val id = "dasher_self"
        override val displayName = "You"
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
    }

    data object BadOffer : ChatPersona() {
        override val id = "bad_offer"
        override val displayName = "Bad Offer"
    }

    data object Inspector : ChatPersona() {
        override val id = "inspector"
        override val displayName = "Inspector"
    }

    data object Navigator : ChatPersona() {
        override val id = "navigator"
        override val displayName = "Navigator"
    }

    data object Shopper : ChatPersona() {
        override val id = "shopper"
        override val displayName = "Shopper"
    }

    data object Earnings : ChatPersona() {
        override val id = "earnings"
        override val displayName = "Earnings"
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