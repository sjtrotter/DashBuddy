package cloud.trotter.dashbuddy.data.pay

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import cloud.trotter.dashbuddy.data.offer.OfferEntity

/**
 * Represents a single component of the application's payout for a given offer.
 * For example, one row could be for "Base Pay" and another for "Peak Pay" for the same offer.
 */
@Entity(
    tableName = "app_pays",
    foreignKeys = [
        ForeignKey(
            entity = OfferEntity::class,
            parentColumns = ["id"],
            childColumns = ["offerId"],
            onDelete = ForeignKey.CASCADE // If the offer is deleted, its pay components are also deleted.
        ),
        ForeignKey(
            entity = AppPayType::class,
            parentColumns = ["id"],
            childColumns = ["payTypeId"],
            onDelete = ForeignKey.RESTRICT // Don't allow a pay type to be deleted if it's in use.
        )
    ],
    indices = [Index(value = ["offerId"]), Index(value = ["payTypeId"])]
)
data class AppPayEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** The ID of the parent [OfferEntity] this pay component belongs to. */
    val offerId: Long,

    /** The ID of the [AppPayType] that this pay component represents. */
    val payTypeId: Long,

    /** The monetary amount for this specific pay component. */
    val amount: Double,

    /** Timestamp of when this pay component was recorded. */
    val timestamp: Long = System.currentTimeMillis()
)
