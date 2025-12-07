package cloud.trotter.dashbuddy.data.event

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import cloud.trotter.dashbuddy.data.dash.DashEntity

@Entity(
    tableName = "offer_events",
    foreignKeys = [
        ForeignKey(
            entity = DashEntity::class,
            parentColumns = ["id"],
            childColumns = ["dashId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["dashId"]),
        Index(value = ["offerHash"])
    ]
)
data class OfferEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** The dash session this event belongs to. */
    val dashId: Long?,

    /** When this offer was presented. */
    val timestamp: Long = System.currentTimeMillis(),

    /** Unique hash of the offer details (pay, distance, store) for linking later. */
    val offerHash: String,

    /** The pay amount shown. */
    val payAmount: Double?,

    /** The distance shown. */
    val distanceMiles: Double?,

    /** Raw text extracted from the screen for forensic analysis. */
    val rawText: String? = null,

    /** The odometer reading at the time of the event. */
    val odometerReading: Double? = null,
)