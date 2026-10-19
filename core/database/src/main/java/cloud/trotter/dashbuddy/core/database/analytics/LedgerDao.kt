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

    /**
     * Distinct notes, ordered by each note's newest surviving occurrence (time, then sequence id of
     * THAT occurrence — two independent maxima could pair a time and an id from different rows).
     * One pass over the category; blank notes never reach a row (the fold stores them as null).
     */
    @Query("""
        SELECT note FROM (
            SELECT note, occurredAt, eventSequenceId,
                ROW_NUMBER() OVER (PARTITION BY note ORDER BY occurredAt DESC, eventSequenceId DESC) AS rn
            FROM expense_records
            WHERE category = :category AND voidedAt IS NULL AND note IS NOT NULL
        ) WHERE rn = 1
        ORDER BY occurredAt DESC, eventSequenceId DESC LIMIT :limit
    """)
    abstract fun recentNotes(category: String, limit: Int): Flow<List<String>>

    /** All projected rows, including voids, for the export trail. */
    @Query("SELECT * FROM expense_records ORDER BY occurredAt, eventSequenceId")
    abstract suspend fun allExpenses(): List<ExpenseRecordEntity>

    @Query("SELECT * FROM fuel_records ORDER BY occurredAt, eventSequenceId")
    abstract suspend fun allFuel(): List<FuelRecordEntity>

    @Query("DELETE FROM expense_records")
    abstract suspend fun deleteAllExpenses()

    @Query("DELETE FROM fuel_records")
    abstract suspend fun deleteAllFuel()
}
