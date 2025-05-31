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
 * @property id The unique identifier for the current dash.
 * @property dashId The ID of the dash associated with the current state.
 * @property zoneId The ID of the zone associated with the current state.
 * @property lastOfferId The ID of the last offer associated with the current state.
 * @property dashStartTime The timestamp when the dash started.
 * @property isActive Whether the dash is currently active.
 * @property isPaused Whether the dash is currently paused.
 * @property dashMode The mode of the dash (e.g., Earn By Time, Earn By Offer).
 * @property dashEarnings The running total of earnings for the dash.
 * @property deliveriesReceived The running total of deliveries received parsed from Offers.
 * @property deliveriesCompleted The running total of deliveries completed.
 * @property lastOfferValue The value of the last offer associated with the current state.
 * @property offersReceived The running total of offers received.
 * @property offersAccepted The running total of offers accepted.
 * @property offersDeclined The running total of offers declined.
 * @property lastUpdate The timestamp of the last update to the current state.
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
    ],

    )
data class CurrentEntity(
    @PrimaryKey val id: Long = 1,
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
)
