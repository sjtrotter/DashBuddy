package cloud.trotter.dashbuddy.data.base

//import androidx.room.migration.Migration
//import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import cloud.trotter.dashbuddy.data.chat.ChatDao
import cloud.trotter.dashbuddy.data.chat.ChatMessageEntity
import cloud.trotter.dashbuddy.data.event.AppEventDao
import cloud.trotter.dashbuddy.data.event.AppEventEntity

@Database(
    entities = [
        AppEventEntity::class,
        ChatMessageEntity::class,
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(DataTypeConverters::class)
abstract class DashBuddyDatabase : RoomDatabase() {

    // Abstract methods for each of your DAOs
    abstract fun appEventDao(): AppEventDao
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile
        private var INSTANCE: DashBuddyDatabase? = null
    }
}