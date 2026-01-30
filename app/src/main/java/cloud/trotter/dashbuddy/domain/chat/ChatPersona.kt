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

    data object System : ChatPersona("sys_internal", "System", R.drawable.ic_info)

    // Dynamic Actors
    class Merchant(name: String) :
        ChatPersona("merchant_${name.hashCode()}", name, R.drawable.ic_shopping_bag)

    class Customer(name: String) :
        ChatPersona("cust_${name.hashCode()}", name, R.drawable.ic_accessibility)
}