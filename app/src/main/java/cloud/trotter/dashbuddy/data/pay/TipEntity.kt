package cloud.trotter.dashbuddy.data.pay

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import cloud.trotter.dashbuddy.data.order.OrderEntity

/**
 * Represents a single tip event associated with a specific delivery.
 * This allows for multiple tips (e.g., initial + post-delivery) for one delivery.
 */
@Entity(
    tableName = "customer_tips",
    foreignKeys = [
        ForeignKey(
            entity = OrderEntity::class,
            parentColumns = ["id"],
            childColumns = ["orderId"],
            onDelete = ForeignKey.CASCADE // If the delivery is deleted, its tips are also deleted.
        )
    ],
    indices = [Index(value = ["orderId"])]
)
data class TipEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** The ID of the parent [OrderEntity] this tip is for. */
    val orderId: Long,

    /** The amount of this specific tip instance. */
    val amount: Double,

    /** The type of tip (e.g., initial, post-delivery, cash), using the TipType enum. */
    val type: TipType,

    /**
     * Timestamp (milliseconds since epoch) of when this tip was recorded.
     * Crucial for differentiating initial tips from ones added later.
     */
    val timestamp: Long
)
