package cloud.trotter.dashbuddy.data.log.snapshots

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SnapshotDao {
    // 1. The Duplicate Check
    @Query("SELECT * FROM snapshots WHERE screenType = :type AND structuralHash = :hash LIMIT 1")
    suspend fun findByHash(type: String, hash: Int): SnapshotRecord?

    // 2. The Rotation Logic (Count)
    @Query("SELECT COUNT(*) FROM snapshots WHERE screenType = :type")
    suspend fun getCountByType(type: String): Int

    // 3. Find the "Stalest" one to delete (Oldest timestamp)
    @Query("SELECT * FROM snapshots WHERE screenType = :type ORDER BY timestamp ASC LIMIT 1")
    suspend fun getOldestByType(type: String): SnapshotRecord?

    // 4. Update timestamp (to keep frequently seen layouts "fresh" so they don't get deleted)
    @Query("UPDATE snapshots SET timestamp = :newTime WHERE id = :id")
    suspend fun updateTimestamp(id: Long, newTime: Long)

    @Insert
    suspend fun insert(record: SnapshotRecord)

    @Delete
    suspend fun delete(record: SnapshotRecord)
}