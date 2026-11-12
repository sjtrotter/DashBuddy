package cloud.trotter.log.gig.core.database.analytics

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** The receipt match rows (#1599): written by the projector's match apply, read by the shift page and the checks. */
@Dao
interface ReceiptMatchDao {
    /** ABORT: a command id is applied once; the apply checks [commandRowCount] first. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(rows: List<ReceiptMatchRowEntity>)

    @Query("SELECT COUNT(*) FROM receipt_match_rows WHERE commandId = :commandId")
    suspend fun commandRowCount(commandId: String): Int

    @Query(
        """SELECT * FROM receipt_match_rows WHERE sessionId = :sessionId AND undoneAt IS NULL
           ORDER BY matchEventSequenceId, deliveryEventSequenceId""",
    )
    suspend fun activeRowsInSession(sessionId: String): List<ReceiptMatchRowEntity>

    @Query(
        """SELECT * FROM receipt_match_rows WHERE sessionId = :sessionId AND undoneAt IS NULL
           ORDER BY matchEventSequenceId, deliveryEventSequenceId""",
    )
    fun activeRowsInSessionFlow(sessionId: String): Flow<List<ReceiptMatchRowEntity>>

    @Query(
        """SELECT * FROM receipt_match_rows WHERE commandId = :commandId AND undoneAt IS NULL
           ORDER BY deliveryEventSequenceId""",
    )
    suspend fun activeRowsForCommand(commandId: String): List<ReceiptMatchRowEntity>

    @Query(
        """UPDATE receipt_match_rows SET undoneAt = :undoneAt, undoEventSequenceId = :undoSeq
           WHERE commandId = :commandId AND undoneAt IS NULL""",
    )
    suspend fun markUndone(commandId: String, undoneAt: Long, undoSeq: Long): Int

    @Query("DELETE FROM receipt_match_rows")
    suspend fun deleteAll()
}
