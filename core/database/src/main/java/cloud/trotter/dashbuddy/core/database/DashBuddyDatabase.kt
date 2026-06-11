package cloud.trotter.dashbuddy.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import cloud.trotter.dashbuddy.core.database.chat.ChatDao
import cloud.trotter.dashbuddy.core.database.chat.ChatMessageEntity
import cloud.trotter.dashbuddy.core.database.effects.EffectsFiredDao
import cloud.trotter.dashbuddy.core.database.effects.EffectsFiredEntity
import cloud.trotter.dashbuddy.core.database.event.AppEventDao
import cloud.trotter.dashbuddy.core.database.event.AppEventEntity
import cloud.trotter.dashbuddy.core.database.log.snapshot.SnapshotDao
import cloud.trotter.dashbuddy.core.database.log.snapshot.SnapshotRecord
import cloud.trotter.dashbuddy.core.database.observation.ObservationDao
import cloud.trotter.dashbuddy.core.database.observation.ObservationEntity
import cloud.trotter.dashbuddy.core.database.snapshot.AppStateSnapshotDao
import cloud.trotter.dashbuddy.core.database.snapshot.AppStateSnapshotEntity

@Database(
    entities = [
        AppEventEntity::class,
        AppStateSnapshotEntity::class,
        ChatMessageEntity::class,
        EffectsFiredEntity::class,
        ObservationEntity::class,
        SnapshotRecord::class,
    ],
    version = 7,
    exportSchema = true
)
@TypeConverters(DataTypeConverters::class)
abstract class DashBuddyDatabase : RoomDatabase() {

    // Abstract methods for each of your DAOs
    abstract fun appEventDao(): AppEventDao
    abstract fun appStateSnapshotDao(): AppStateSnapshotDao
    abstract fun chatDao(): ChatDao
    abstract fun effectsFiredDao(): EffectsFiredDao
    abstract fun observationDao(): ObservationDao
    abstract fun snapshotDao(): SnapshotDao

    companion object {
        @Volatile
        private var INSTANCE: DashBuddyDatabase? = null
    }
}