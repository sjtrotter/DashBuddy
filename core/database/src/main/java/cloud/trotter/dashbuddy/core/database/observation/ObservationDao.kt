package cloud.trotter.dashbuddy.core.database.observation

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ObservationDao {

    @Insert
    suspend fun insert(entity: ObservationEntity): Long

    /**
     * All observations after a given correlationVersion, in PROCESSING order for
     * replay (#352). correlationVersion is assigned by the reducer; sequenceId is
     * insert-landing order, which concurrent writers could scramble.
     */
    @Query("SELECT * FROM observations WHERE correlationVersion > :afterVersion ORDER BY correlationVersion ASC")
    suspend fun since(afterVersion: Long): List<ObservationEntity>

    /** Latest observation by correlationVersion. */
    @Query("SELECT * FROM observations ORDER BY correlationVersion DESC LIMIT 1")
    suspend fun latest(): ObservationEntity?

    /** Prune observations older than [cutoff] millis. */
    @Query("DELETE FROM observations WHERE occurredAt < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long)
}
