package cloud.trotter.dashbuddy.data.event

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OfferEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: OfferEventEntity): Long

    @Query("SELECT * FROM offer_events WHERE dashId = :dashId ORDER BY timestamp ASC")
    fun getEventsForDashFlow(dashId: Long): Flow<List<OfferEventEntity>>

    @Query("DELETE FROM offer_events WHERE dashId = :dashId")
    suspend fun deleteEventsForDash(dashId: Long)
}