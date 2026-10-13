package cloud.trotter.dashbuddy.core.database.analytics

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class LedgerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertExpense(row: ExpenseRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertFuel(row: FuelRecordEntity)

    @Query("UPDATE expense_records SET voidedAt = :voidedAt WHERE eventSequenceId = :targetEventSequenceId")
    abstract suspend fun voidExpense(targetEventSequenceId: Long, voidedAt: Long): Int

    @Query("UPDATE fuel_records SET voidedAt = :voidedAt WHERE eventSequenceId = :targetEventSequenceId")
    abstract suspend fun voidFuel(targetEventSequenceId: Long, voidedAt: Long): Int

    @Transaction
    open suspend fun voidEntry(targetEventSequenceId: Long, voidedAt: Long): Int =
        voidExpense(targetEventSequenceId, voidedAt) + voidFuel(targetEventSequenceId, voidedAt)

    @Query("SELECT * FROM expense_records WHERE sessionId = :sessionId ORDER BY occurredAt, eventSequenceId")
    abstract fun expensesForSession(sessionId: String): Flow<List<ExpenseRecordEntity>>

    @Query("SELECT * FROM fuel_records WHERE sessionId = :sessionId ORDER BY occurredAt, eventSequenceId")
    abstract fun fuelForSession(sessionId: String): Flow<List<FuelRecordEntity>>

    @Query("SELECT * FROM expense_records WHERE sessionId IS NULL ORDER BY occurredAt, eventSequenceId")
    abstract fun expensesUnassigned(): Flow<List<ExpenseRecordEntity>>

    @Query("SELECT * FROM fuel_records WHERE sessionId IS NULL ORDER BY occurredAt, eventSequenceId")
    abstract fun fuelUnassigned(): Flow<List<FuelRecordEntity>>

    @Query("""
        SELECT COALESCE((SELECT SUM(amount) FROM expense_records
                         WHERE sessionId = :sessionId AND voidedAt IS NULL), 0)
             + COALESCE((SELECT SUM(totalAmount) FROM fuel_records
                         WHERE sessionId = :sessionId AND voidedAt IS NULL), 0)
    """)
    abstract fun realExpensesForSession(sessionId: String): Flow<Double>

    @Query("DELETE FROM expense_records")
    abstract suspend fun deleteAllExpenses()

    @Query("DELETE FROM fuel_records")
    abstract suspend fun deleteAllFuel()
}
