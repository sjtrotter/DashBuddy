package cloud.trotter.log.gig.core.database.analytics

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "expense_records", indices = [Index("sessionId")])
data class ExpenseRecordEntity(
    @PrimaryKey
    val eventSequenceId: Long,
    val sessionId: String?,
    val occurredAt: Long,
    val category: String,
    val amount: Double,
    val note: String?,
    val voidedAt: Long?,
    val vehicleId: String? = null,
)
