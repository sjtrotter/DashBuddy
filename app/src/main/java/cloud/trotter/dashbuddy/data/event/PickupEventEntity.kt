package cloud.trotter.dashbuddy.data.event

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import cloud.trotter.dashbuddy.data.dash.DashEntity
import cloud.trotter.dashbuddy.data.event.status.PickupStatus

@Entity(
    tableName = "pickup_events",
    foreignKeys = [
        ForeignKey(
            entity = DashEntity::class,
            parentColumns = ["id"],
            childColumns = ["dashId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["dashId"])]
)
data class PickupEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** The dash session this event belongs to. Nullable for crash recovery safety. */
    val dashId: Long?,

    /** When this event was observed. */
    val timestamp: Long = System.currentTimeMillis(),

    /** The name of the store as it appeared on the screen. */
    val rawStoreName: String,

    /** The address if available. */
    val rawAddress: String? = null,

    /** The state of the pickup: "NAVIGATING", "ARRIVED", "SHOPPING", etc. */
    val status: PickupStatus = PickupStatus.UNKNOWN,

    /** The odometer reading at the time of the event. */
    val odometerReading: Double? = null,
)