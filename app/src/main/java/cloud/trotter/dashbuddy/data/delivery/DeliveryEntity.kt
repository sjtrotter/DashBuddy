package cloud.trotter.dashbuddy.data.delivery

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import cloud.trotter.dashbuddy.data.dash.DashEntity
import cloud.trotter.dashbuddy.data.offer.OfferEntity

/** Represents a delivery in the database.
 * @property id The unique identifier for the delivery.
 * @property dashId The ID of the [DashEntity] session this delivery belongs to.
 * @property offerId The ID of the [OfferEntity] associated with this delivery.
 */
@Entity(
    tableName = "deliveries",
    foreignKeys = [
        ForeignKey(
            entity = DashEntity::class,
            parentColumns = ["id"],
            childColumns = ["dashId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = OfferEntity::class,
            parentColumns = ["id"],
            childColumns = ["offerId"],
            onDelete = ForeignKey.CASCADE
        ),
    ],
    indices = [Index(value = ["dashId"])]
)
data class DeliveryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val dashId: Long,
    val offerId: Long,
)