package cloud.trotter.dashbuddy.domain.model.event

import cloud.trotter.dashbuddy.domain.model.event.payload.AppEventPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryAdjustmentPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliverySessionAssignPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.ManualDeliveryPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.PayAdjustmentPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferReceivedPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.PickupPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionPausedPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStartPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStopPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.TaskUnassignedPayload
import kotlinx.serialization.json.Json

/**
 * The event-payload wire codec (#354). The domain owns the eventType→payload-class
 * mapping, so both sides of the storage boundary (the repo writing entities, the
 * repo reading them back) agree on one encoding with no per-consumer JSON code.
 *
 * Encoding dispatches on the CONCRETE payload class so the wire shape is a plain
 * field object — byte-compatible with rows written before this codec existed
 * (no polymorphic discriminator).
 */
object AppEventCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodePayload(payload: AppEventPayload): String = when (payload) {
        is OfferReceivedPayload -> json.encodeToString(payload)
        is OfferPayload -> json.encodeToString(payload)
        is PickupPayload -> json.encodeToString(payload)
        is DeliveryPayload -> json.encodeToString(payload)
        is SessionStartPayload -> json.encodeToString(payload)
        is SessionPausedPayload -> json.encodeToString(payload)
        is SessionStopPayload -> json.encodeToString(payload)
        is ManualDeliveryPayload -> json.encodeToString(payload)
        is PayAdjustmentPayload -> json.encodeToString(payload)
        is DeliveryAdjustmentPayload -> json.encodeToString(payload)
        is DeliverySessionAssignPayload -> json.encodeToString(payload)
        is TaskUnassignedPayload -> json.encodeToString(payload)
    }

    /**
     * Decode a persisted payload for [type]. Returns null for event types that
     * carry no typed payload and for empty payloads; THROWS on malformed JSON —
     * the caller (repo edge) decides how loudly to degrade (#353: no silent
     * corruption-eating in the codec itself).
     */
    fun decodePayload(type: AppEventType, payloadJson: String): AppEventPayload? {
        if (payloadJson.isBlank() || payloadJson == "{}") return null
        return when (type) {
            AppEventType.OFFER_RECEIVED ->
                json.decodeFromString<OfferReceivedPayload>(payloadJson)

            AppEventType.OFFER_ACCEPTED,
            AppEventType.OFFER_DECLINED,
            AppEventType.OFFER_TIMEOUT ->
                json.decodeFromString<OfferPayload>(payloadJson)

            AppEventType.PICKUP_NAV_STARTED,
            AppEventType.PICKUP_ARRIVED,
            AppEventType.PICKUP_CONFIRMED ->
                json.decodeFromString<PickupPayload>(payloadJson)

            AppEventType.DELIVERY_NAV_STARTED,
            AppEventType.DELIVERY_ARRIVED,
            AppEventType.DELIVERY_CONFIRMED,
            AppEventType.DELIVERY_COMPLETED ->
                json.decodeFromString<DeliveryPayload>(payloadJson)

            AppEventType.DASH_START ->
                json.decodeFromString<SessionStartPayload>(payloadJson)

            AppEventType.DASH_PAUSED ->
                json.decodeFromString<SessionPausedPayload>(payloadJson)

            AppEventType.DASH_STOP ->
                json.decodeFromString<SessionStopPayload>(payloadJson)

            AppEventType.MANUAL_DELIVERY ->
                json.decodeFromString<ManualDeliveryPayload>(payloadJson)

            AppEventType.PAY_ADJUSTMENT ->
                json.decodeFromString<PayAdjustmentPayload>(payloadJson)

            AppEventType.DELIVERY_ADJUSTMENT ->
                json.decodeFromString<DeliveryAdjustmentPayload>(payloadJson)

            AppEventType.DELIVERY_SESSION_ASSIGN ->
                json.decodeFromString<DeliverySessionAssignPayload>(payloadJson)

            AppEventType.TASK_UNASSIGNED ->
                json.decodeFromString<TaskUnassignedPayload>(payloadJson)

            AppEventType.ZONE_SWITCH,
            AppEventType.NOTIFICATION_RECEIVED,
            AppEventType.SCREEN_VIEWED,
            AppEventType.ERROR_OCCURRED -> null
        }
    }
}
