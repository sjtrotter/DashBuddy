package cloud.trotter.log.gig.core.database.analytics

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The receipt evidence table (#1598): written by the projector; the receipt match apply reads one record by id, and
 * the shift page's receipt check and matching sheet (#1599) read a shift's records reactively.
 */
@Dao
interface ReceiptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ReceiptRecordEntity)

    @Query("DELETE FROM receipt_records")
    suspend fun deleteAll()

    @Query("SELECT * FROM receipt_records WHERE sessionId = :sessionId ORDER BY firstSeenAt, evidenceId")
    suspend fun receiptsInSession(sessionId: String): List<ReceiptRecordEntity>

    @Query("SELECT * FROM receipt_records WHERE sessionId = :sessionId ORDER BY firstSeenAt, evidenceId")
    fun receiptsInSessionFlow(sessionId: String): Flow<List<ReceiptRecordEntity>>

    @Query("SELECT * FROM receipt_records WHERE evidenceId = :evidenceId")
    suspend fun receipt(evidenceId: String): ReceiptRecordEntity?
}
