package cloud.trotter.dashbuddy.data.event

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import cloud.trotter.dashbuddy.data.dash.DashEntity
import cloud.trotter.dashbuddy.data.event.status.DropoffStatus

@Entity(
    tableName = "dropoff_events",
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
data class DropoffEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** The dash session this event belongs to. */
    val dashId: Long?,

    /** When this event was observed. */
    val timestamp: Long = System.currentTimeMillis(),

    /** The customer name (or "Deliver to...") as seen on screen. */
    val customerNameHash: String,

    /** The address if available. */
    val addressHash: String? = null,

    /** The state of the drop-off: "NAVIGATING", "ARRIVED", "CONFIRMING", etc. */
    val status: DropoffStatus = DropoffStatus.UNKNOWN,

    /** The odometer reading at the time of the event. */
    val odometerReading: Double? = null,
)