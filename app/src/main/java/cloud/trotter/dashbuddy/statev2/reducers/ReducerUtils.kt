package cloud.trotter.dashbuddy.statev2.reducers

import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.data.event.AppEventEntity
import cloud.trotter.dashbuddy.data.event.AppEventType
import com.google.gson.Gson

object ReducerUtils {
    val gson = Gson()

    fun createEvent(
        dashId: String?,
        type: AppEventType,
        payload: String,
        odometer: Double?
    ): AppEventEntity {
        return AppEventEntity(
            aggregateId = dashId,
            eventType = type,
            eventPayload = payload,
            occurredAt = System.currentTimeMillis(),
            metadata = DashBuddyApplication.createMetadata(odometer)
        )
    }
}