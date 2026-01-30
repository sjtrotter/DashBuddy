package cloud.trotter.dashbuddy.domain.chat

import androidx.annotation.DrawableRes
import cloud.trotter.dashbuddy.R

sealed class ChatPersona(
    val id: String,
    val name: String,
    @param:DrawableRes val iconResId: Int
) {
    // System / Dispatcher
    data object Dispatcher : ChatPersona("bot_dispatcher", "DashBuddy", R.drawable.bag_red_idle)

    data object System : ChatPersona("sys_internal", "System", R.drawable.ic_chat_info)

    // Dynamic Actors
    class Merchant(name: String) :
        ChatPersona("merchant_${name.hashCode()}", name, R.drawable.ic_chat_merchant)

    class Customer(name: String) :
        ChatPersona("cust_${name.hashCode()}", name, R.drawable.ic_chat_person_pin_circle)

    // 3. The Analyst (Offer Evaluation)
    data object GoodOffer : ChatPersona(
        id = "bot_offer_good",
        name = "Great Catch!",
        iconResId = R.drawable.ic_chat_check_circle // Or R.drawable.ic_check_circle
    )

    data object BadOffer : ChatPersona(
        id = "bot_offer_bad",
        name = "Hard Pass",
        iconResId = R.drawable.ic_chat_cancel_circle
    )

    // 4. The Co-Pilot (Travel Phase)
    data object Navigator : ChatPersona(
        id = "bot_nav",
        name = "Navigation",
        iconResId = R.drawable.ic_chat_navigation // Or R.drawable.ic_navigation
    )

    // 5. The Accountant (Reward Phase)
    data object Earnings : ChatPersona(
        id = "bot_earnings",
        name = "Earnings",
        iconResId = R.drawable.ic_chat_payments // Or R.drawable.ic_payments
    )

    //6. The Inspector (no action on offer)
    data object Inspector : ChatPersona(
        id = "bot_inspector",
        name = "Inspector",
        iconResId = R.drawable.ic_chat_visibility //
    )

    //7. The Shopper
    data object Shopper : ChatPersona(
        id = "bot_shopper",
        name = "Shopper",
        iconResId = R.drawable.ic_chat_shopping_cart
    )
//    // 6. Contextual Actors (Arrival Phase)
//    class Merchant(name: String) : ChatPersona(
//        id = "merchant_${name.hashCode()}",
//        name = name,
//        iconResId = R.drawable.ic_shopping_bag // Or R.drawable.ic_storefront
//    )
//
//    class Customer(name: String) : ChatPersona(
//        id = "cust_${name.hashCode()}",
//        name = name,
//        iconResId = R.drawable.ic_accessibility // Or R.drawable.ic_person_pin
//    )
}