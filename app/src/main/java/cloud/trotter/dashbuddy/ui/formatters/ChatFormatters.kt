package cloud.trotter.dashbuddy.ui.formatters

import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona

fun ChatPersona.getIconResId(): Int {
    return when (this) {
        is ChatPersona.Dispatcher -> R.drawable.ic_chat_delivery_truck_speed
        is ChatPersona.System -> R.drawable.ic_chat_info
        is ChatPersona.Dasher -> R.drawable.ic_chat_person_pin_circle
        is ChatPersona.Merchant -> R.drawable.ic_chat_merchant
        is ChatPersona.Customer -> R.drawable.ic_chat_person_pin_circle
        is ChatPersona.GoodOffer -> R.drawable.ic_chat_receipt_long_check_circle
        is ChatPersona.BadOffer -> R.drawable.ic_chat_receipt_long_cancel_circle
        is ChatPersona.Inspector -> R.drawable.ic_chat_receipt_long_search
        is ChatPersona.Navigator -> R.drawable.ic_chat_navigation
        is ChatPersona.Shopper -> R.drawable.ic_chat_shopping_cart
        is ChatPersona.Earnings -> R.drawable.ic_chat_payments
    }
}