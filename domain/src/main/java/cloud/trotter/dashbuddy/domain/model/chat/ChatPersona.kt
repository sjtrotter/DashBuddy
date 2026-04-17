package cloud.trotter.dashbuddy.domain.model.chat

sealed class ChatPersona {
    abstract val id: String
    abstract val displayName: String

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
    }

    data class Customer(val customerName: String) : ChatPersona() {
        override val id = "customer_${customerName.lowercase().replace(" ", "_")}"
        override val displayName = customerName
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
}