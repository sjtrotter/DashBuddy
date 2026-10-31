package cloud.trotter.log.gig.feature.bubble.formatters

import cloud.trotter.log.gig.feature.bubble.R
import cloud.trotter.log.gig.domain.model.chat.ChatPersona

fun ChatPersona.getIconResId(): Int {
    return when (this) {
        is ChatPersona.Dispatcher -> R.drawable.ic_chat_delivery_truck_speed
        is ChatPersona.System -> R.drawable.ic_chat_info
        is ChatPersona.Dasher -> R.drawable.ic_chat_person_pin_circle
        is ChatPersona.Merchant -> R.drawable.ic_chat_merchant
        is ChatPersona.Customer -> R.drawable.ic_chat_person_pin_circle
        is ChatPersona.Offer -> R.drawable.ic_chat_receipt_long
        is ChatPersona.Navigator -> R.drawable.ic_chat_navigation
        is ChatPersona.Dropoff -> R.drawable.ic_chat_navigation
        is ChatPersona.Shopper -> R.drawable.ic_chat_shopping_cart
        is ChatPersona.Earnings -> R.drawable.ic_chat_payments
    }
}
