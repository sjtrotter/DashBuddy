package cloud.trotter.dashbuddy.statev2.reducers

import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.data.event.AppEventEntity
import cloud.trotter.dashbuddy.data.event.AppEventType
import com.google.gson.Gson

object ReducerUtils {

    private val gson = Gson()

    /**
     * Creates a standardized event entity with "Fresh" sensor data.
     * This bypasses the StateContext and grabs the odometer right now.
     */
    fun createEvent(
        dashId: String?,
        type: AppEventType,
        payload: Any, // Can be String or Object (will be JSON-ified)
        timestamp: Long = System.currentTimeMillis()
    ): AppEventEntity {

        val metadataJson = DashBuddyApplication.createMetadata()

        // 3. SERIALIZE PAYLOAD
        val payloadStr = payload as? String ?: gson.toJson(payload)

        return AppEventEntity(
            aggregateId = dashId,
            eventType = type,
            eventPayload = payloadStr,
            occurredAt = timestamp,
            metadata = metadataJson
        )
    }
}