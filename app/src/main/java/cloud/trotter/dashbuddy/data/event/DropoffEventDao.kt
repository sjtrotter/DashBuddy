package cloud.trotter.dashbuddy.data.event

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DropoffEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: DropoffEventEntity): Long

    @Query("SELECT * FROM dropoff_events WHERE dashId = :dashId ORDER BY timestamp ASC")
    fun getEventsForDashFlow(dashId: Long): Flow<List<DropoffEventEntity>>

    @Query("SELECT * FROM dropoff_events ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestEvent(): DropoffEventEntity?

    @Query("DELETE FROM dropoff_events WHERE dashId = :dashId")
    suspend fun deleteEventsForDash(dashId: Long)
}