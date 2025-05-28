package cloud.trotter.dashbuddy.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "offers",
    foreignKeys = [
        ForeignKey(
            entity = Session::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Delivery::class,
            parentColumns = ["id"],
            childColumns = ["deliveryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],

    indices = [Index("sessionId"), Index("deliveryId")]
)

data class Offer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hash: String = "",
    val payout: Double = 0.0,
    val deliveryTime: Long = 0,
    val score: Double = 0.0,
    val offerQuality: String = "",
    val type: String = "",
    val isHighPaying: Boolean = false,
    val accepted: Boolean = false,
    @ColumnInfo(name = "sessionId", index = true) val sessionId: Long,
    @ColumnInfo(name = "deliveryId", index = true) val deliveryId: Long,
)