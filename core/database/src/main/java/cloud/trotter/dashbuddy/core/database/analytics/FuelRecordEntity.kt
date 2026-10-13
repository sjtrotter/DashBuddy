package cloud.trotter.dashbuddy.core.database.analytics

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "fuel_records", indices = [Index("sessionId")])
data class FuelRecordEntity(
    @PrimaryKey
    val eventSequenceId: Long,
    val sessionId: String?,
    val occurredAt: Long,
    val totalAmount: Double,
    val gallons: Double?,
    val odometerMiles: Double?,
    val voidedAt: Long?,
)
