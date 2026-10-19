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

    @Query("UPDATE expense_records SET voidedAt = :voidedAt WHERE eventSequenceId = :targetEventSequenceId AND voidedAt IS NULL")
    abstract suspend fun voidExpense(targetEventSequenceId: Long, voidedAt: Long): Int

    @Query("UPDATE fuel_records SET voidedAt = :voidedAt WHERE eventSequenceId = :targetEventSequenceId AND voidedAt IS NULL")
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
        SELECT COALESCE(SUM(amount), 0) FROM expense_records
        WHERE sessionId = :sessionId AND voidedAt IS NULL
    """)
    abstract fun expenseTotalForSession(sessionId: String): Flow<Double>

    @Query("""
        SELECT COALESCE(SUM(totalAmount), 0) FROM fuel_records
        WHERE sessionId = :sessionId AND voidedAt IS NULL
    """)
    abstract fun fuelTotalForSession(sessionId: String): Flow<Double>

    /** Deduplicate by note, ordered by its newest surviving occurrence (including tied times). */
    @Query("""
        SELECT e.note FROM expense_records e
        WHERE e.category = :category AND e.voidedAt IS NULL
          AND e.note IS NOT NULL AND TRIM(e.note, char(9,10,11,12,13,28,29,30,31,32,160,5760,8192,8193,8194,8195,8196,8197,8198,8199,8200,8201,8202,8232,8233,8239,8287,12288)) != ''
          AND NOT EXISTS (
              SELECT 1 FROM expense_records newer
              WHERE newer.category = e.category AND newer.note = e.note AND newer.voidedAt IS NULL
                AND (newer.occurredAt > e.occurredAt OR
                     (newer.occurredAt = e.occurredAt AND newer.eventSequenceId > e.eventSequenceId))
          )
        ORDER BY e.occurredAt DESC, e.eventSequenceId DESC LIMIT :limit
    """)
    abstract fun recentNotes(category: String, limit: Int): Flow<List<String>>

    @Query("DELETE FROM expense_records")
    abstract suspend fun deleteAllExpenses()

    @Query("DELETE FROM fuel_records")
    abstract suspend fun deleteAllFuel()
}
