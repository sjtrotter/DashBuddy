package cloud.trotter.dashbuddy.data.order

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import cloud.trotter.dashbuddy.data.offer.OfferEntity
import cloud.trotter.dashbuddy.data.store.StoreEntity

@Entity(
    tableName = "orders",
    foreignKeys = [
        ForeignKey(
            entity = OfferEntity::class,
            parentColumns = ["id"],
            childColumns = ["offerId"],
            onDelete = ForeignKey.CASCADE
        ),
//        ForeignKey(
//            entity = StoreEntity::class,
//            parentColumns = ["id"],
//            childColumns = ["storeId"],
//            onDelete = ForeignKey.CASCADE
//        )
    ],
    indices = [Index(value = ["offerId"])]
)
data class OrderEntity(
    /**
     * Represents an individual Order belonging to a specific [OfferEntity].
     * @property id The unique identifier for the order.
     * @property orderType The type of the order (e.g., "Pickup", "Shop for items", "Retail pickup").
     * @property storeId The ID of the [StoreEntity] associated with this order.
     * @property offerId The ID of the [OfferEntity] this order belongs to.
     * @property itemCount The number of items reported in this order. Defaults to 0.
     * @property isItemCountEstimated Indicates if the item count is estimated.
     * @property badges Indicates the badges present on the order.
     */
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** The ID of the store associated with this order. Null until user matches store to order. */
    val storeId: Long? = null,
    /** the ID of the offer this order belongs to. */
    val offerId: Long,

    /** The index of this order in the offer's order list. */
    val orderIndex: Int,
    /** The order type (e.g., "Pickup", "Shop for items", "Retail pickup"). */
    val orderType: String,
    /** The store name from the original parsed order. */
    val storeName: String? = null,
    /**
     * The number of items reported in this order. Defaults to 0.
     * edge case: dasher app may bundle two shop and pays together and report (2 orders, X items)
     * combining their items. If so, we will divide by 2, use that number,
     * and use isItemCountEstimated to indicate this.
     */
    val itemCount: Int = 0,
    /** Indicates if the item count is estimated (see above). */
    val isItemCountEstimated: Boolean = false,

    /** List of badges that are assigned to this order. */
    val badges: Set<OrderBadge> = emptySet(),
)