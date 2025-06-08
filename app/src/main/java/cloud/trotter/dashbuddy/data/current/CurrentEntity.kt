package cloud.trotter.dashbuddy.data.current

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import cloud.trotter.dashbuddy.data.dash.DashEntity
import cloud.trotter.dashbuddy.data.offer.OfferEntity
import cloud.trotter.dashbuddy.data.zone.ZoneEntity

/**
 * Represents the current state of a dash.
 * This table should only ever have one row with id = 1.
 */
@Entity(
    tableName = "current_dash",
    foreignKeys = [
        ForeignKey(
            entity = DashEntity::class,
            parentColumns = ["id"],
            childColumns = ["dashId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = ZoneEntity::class,
            parentColumns = ["id"],
            childColumns = ["zoneId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = OfferEntity::class,
            parentColumns = ["id"],
            childColumns = ["lastOfferId"],
            onDelete = ForeignKey.SET_NULL
        ),
        // A foreign key for activeOrderId is also possible if desired
        // ForeignKey(entity = OrderEntity::class, ...)
    ]
)
data class CurrentEntity(
    @PrimaryKey val id: Long = 1, // Singleton row
    @ColumnInfo(index = true, name = "dashId") val dashId: Long? = null,
    @ColumnInfo(index = true, name = "zoneId") val zoneId: Long? = null,
    @ColumnInfo(index = true, name = "lastOfferId") val lastOfferId: Long? = null,
    val dashStartTime: Long? = null,
    val isActive: Boolean = false,
    val isPaused: Boolean = false,
    val dashMode: String? = null,
    val dashEarnings: Double? = null,
    val deliveriesReceived: Int? = null,
    val deliveriesCompleted: Int? = null,
    val lastOfferValue: Double? = null,
    val offersReceived: Int? = null,
    val offersAccepted: Int? = null,
    val offersDeclined: Int? = null,
    val lastUpdate: Long? = null,

    /** The ID of the specific OrderEntity the dasher is currently working on. Null if between tasks. */
    @ColumnInfo(index = true, name = "activeOrderId")
    val activeOrderId: Long? = null,

    /** A queue of OrderEntity IDs that have been accepted but are not yet complete. */
    val activeOrderQueue: List<Long> = emptyList()
)
