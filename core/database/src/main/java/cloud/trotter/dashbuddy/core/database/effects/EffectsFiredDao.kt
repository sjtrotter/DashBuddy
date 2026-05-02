package cloud.trotter.dashbuddy.core.database.effects

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EffectsFiredDao {

    /** Returns true if this effect key has already been fired. */
    @Query("SELECT COUNT(*) > 0 FROM effects_fired WHERE effectKey = :key")
    suspend fun hasBeenFired(key: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markFired(entity: EffectsFiredEntity)

    /** Prune old records. */
    @Query("DELETE FROM effects_fired WHERE firedAt < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long)
}
