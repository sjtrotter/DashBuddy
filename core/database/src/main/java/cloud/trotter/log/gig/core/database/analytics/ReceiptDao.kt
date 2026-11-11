package cloud.trotter.log.gig.core.database.analytics

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** The receipt evidence table (#1598): written by the projector, read by the matching sheet (#1599). */
@Dao
interface ReceiptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ReceiptRecordEntity)

    @Query("DELETE FROM receipt_records")
    suspend fun deleteAll()

    @Query("SELECT * FROM receipt_records WHERE sessionId = :sessionId ORDER BY firstSeenAt, evidenceId")
    suspend fun receiptsInSession(sessionId: String): List<ReceiptRecordEntity>
}
