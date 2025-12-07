package cloud.trotter.dashbuddy.data.event

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import cloud.trotter.dashbuddy.data.zone.ZoneEntity

@Entity(
    tableName = "dash_events",
    foreignKeys = [
        ForeignKey(
            entity = ZoneEntity::class,
            parentColumns = ["id"],
            childColumns = ["zoneId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index(value = ["zoneId"])]
)
data class DashEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** The type of event: "START" or "STOP". */
    val type: String,

    /** Timestamp of the event. */
    val timestamp: Long = System.currentTimeMillis(),

    /** The zone ID associated with this dash (critical for START events). */
    val zoneId: Long?,

    /** The dash type (e.g., "Dash Now", "Scheduled") if available. */
    val dashType: String? = null,

    /** Odometer reading at the time of the event. */
    val odometerReading: Double? = null
)