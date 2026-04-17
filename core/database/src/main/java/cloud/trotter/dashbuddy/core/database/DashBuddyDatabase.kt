package cloud.trotter.dashbuddy.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import cloud.trotter.dashbuddy.core.database.chat.ChatDao
import cloud.trotter.dashbuddy.core.database.chat.ChatMessageEntity
import cloud.trotter.dashbuddy.core.database.event.AppEventDao
import cloud.trotter.dashbuddy.core.database.event.AppEventEntity
import cloud.trotter.dashbuddy.core.database.log.snapshot.SnapshotDao
import cloud.trotter.dashbuddy.core.database.log.snapshot.SnapshotRecord

@Database(
    entities = [
        AppEventEntity::class,
        ChatMessageEntity::class,
        SnapshotRecord::class,
    ],
    version = 4,
    exportSchema = true
)
@TypeConverters(DataTypeConverters::class)
abstract class DashBuddyDatabase : RoomDatabase() {

    // Abstract methods for each of your DAOs
    abstract fun appEventDao(): AppEventDao
    abstract fun chatDao(): ChatDao
    abstract fun snapshotDao(): SnapshotDao

    companion object {
        @Volatile
        private var INSTANCE: DashBuddyDatabase? = null
    }
}