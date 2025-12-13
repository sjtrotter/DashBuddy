package cloud.trotter.dashbuddy.data.event

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "app_events",
    indices = [
        Index(value = ["aggregateId"]), // Fast lookup by Dash ID
        Index(value = ["eventType"]),   // Fast lookup by Event Type
        Index(value = ["occurredAt"])   // Fast sorting by Time
    ]
)
data class AppEventEntity(
    /** Auto-incrementing primary key. Defines the absolute order of events. */
    @PrimaryKey(autoGenerate = true)
    val sequenceId: Long = 0,

    /**
     * A String representing the specific Dash session (UUID or Stringified Long).
     * Using String allows flexibility if you switch from Long IDs to UUIDs later.
     */
    val aggregateId: String?,

    /** A discriminator string (e.g., "OFFER_RECEIVED", "ORDER_PICKED_UP"). */
    val eventType: AppEventType,

    /** The JSON serialization of the specific event data class. */
    val eventPayload: String,

    /** The Unix timestamp of when the event happened. */
    val occurredAt: Long = System.currentTimeMillis(),

    /** Optional JSON for device state (battery, GPS, etc.) at the time of event. */
    val metadata: String? = null
)