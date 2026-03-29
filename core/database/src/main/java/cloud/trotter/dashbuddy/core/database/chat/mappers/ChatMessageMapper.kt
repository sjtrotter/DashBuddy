package cloud.trotter.dashbuddy.core.database.chat.mappers

import cloud.trotter.dashbuddy.core.database.chat.ChatMessageEntity
import cloud.trotter.dashbuddy.domain.model.chat.ChatMessage
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona

fun ChatMessageEntity.toDomain(): ChatMessage {
    val persona = when (this.personaType) {
        "DISPATCHER" -> ChatPersona.Dispatcher
        "SYSTEM" -> ChatPersona.System
        "DASHER" -> ChatPersona.Dasher
        "MERCHANT" -> ChatPersona.Merchant(this.personaName)
        "CUSTOMER" -> ChatPersona.Customer(this.personaName)
        "GOOD_OFFER" -> ChatPersona.GoodOffer
        "BAD_OFFER" -> ChatPersona.BadOffer
        "INSPECTOR" -> ChatPersona.Inspector
        "NAVIGATOR" -> ChatPersona.Navigator
        "SHOPPER" -> ChatPersona.Shopper
        "EARNINGS" -> ChatPersona.Earnings
        else -> ChatPersona.Dispatcher
    }

    return ChatMessage(
        id = this.id,
        dashId = this.dashId, // Mapped!
        text = this.text,
        timestamp = this.timestamp,
        persona = persona
    )
}

fun ChatMessage.toEntity(): ChatMessageEntity {
    val type = when (this.persona) {
        is ChatPersona.Dispatcher -> "DISPATCHER"
        is ChatPersona.System -> "SYSTEM"
        is ChatPersona.Dasher -> "DASHER"
        is ChatPersona.Merchant -> "MERCHANT"
        is ChatPersona.Customer -> "CUSTOMER"
        is ChatPersona.GoodOffer -> "GOOD_OFFER"
        is ChatPersona.BadOffer -> "BAD_OFFER"
        is ChatPersona.Inspector -> "INSPECTOR"
        is ChatPersona.Navigator -> "NAVIGATOR"
        is ChatPersona.Shopper -> "SHOPPER"
        is ChatPersona.Earnings -> "EARNINGS"
    }

    return ChatMessageEntity(
        id = this.id,
        dashId = this.dashId, // Mapped!
        text = this.text,
        timestamp = this.timestamp,
        personaType = type,
        personaName = this.persona.displayName
    )
}