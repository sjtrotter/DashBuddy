package cloud.trotter.dashbuddy.data.order

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import cloud.trotter.dashbuddy.data.customer.CustomerEntity
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
        ForeignKey(
            entity = StoreEntity::class,
            parentColumns = ["id"],
            childColumns = ["storeId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customerId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index(value = ["offerId"]), Index(value = ["storeId"]), Index(value = ["customerId"])]
)
data class OrderEntity(
    /**
     * Represents an individual task from pickup to delivery, belonging to a specific [OfferEntity].
     */
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // --- Offer & Pickup Details (known at offer time) ---
    /** The ID of the [OfferEntity] this order belongs to. */
    val offerId: Long,
    /** The index of this order within the offer's list (e.g., for stacked orders). */
    val orderIndex: Int,
    /** The parsed store name from the offer screen. */
    val storeName: String,
    /** The ID of the specific [StoreEntity]. Null until identified and linked. */
    val storeId: Long? = null,
    /** The type of order (e.g., "Pickup", "Shop for items"). */
    val orderType: String,
    /** The number of items reported for this order. */
    val itemCount: Int = 0,
    /** Indicates if the item count was estimated (e.g., from a multi-order pattern). */
    val isItemCountEstimated: Boolean = false,
    /** A set of badges specific to this order/pickup (e.g., RED_CARD, LARGE_ORDER). */
    val badges: Set<OrderBadge> = emptySet(),

    // --- Delivery Details (populated after pickup/during dropoff) ---
    /** The hash of the parsed customer name from the pickup/delivery screen. */
    val customerNameHash: String? = null,
    /** The ID of the specific [CustomerEntity]. Null until identified and linked. */
    val customerId: Long? = null,
    /** The calculated mileage from the mileage tracker service. */
    val mileage: Double? = null,
    /** Timestamp (milliseconds since epoch) when the delivery was completed. Null until completion. */
    val completionTimestamp: Long? = null,
    /** The current status of this order's lifecycle. */
    val status: OrderStatus = OrderStatus.PENDING,
)
