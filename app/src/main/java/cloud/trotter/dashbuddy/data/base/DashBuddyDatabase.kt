package cloud.trotter.dashbuddy.data.base

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
//import androidx.room.migration.Migration
//import androidx.sqlite.db.SupportSQLiteDatabase
import cloud.trotter.dashbuddy.data.event.AppEventDao
import cloud.trotter.dashbuddy.data.event.AppEventEntity

@Database(
    entities = [
        AppEventEntity::class,
    ],
    version = 23,
    exportSchema = false // Set to true if you plan to use schema for testing migrations
// For production, schema export is recommended.
)
@TypeConverters(DataTypeConverters::class)
abstract class DashBuddyDatabase : RoomDatabase() {
    // Abstract methods for each of your DAOs
    abstract fun appEventDao(): AppEventDao

    companion object {
        @Volatile
        private var INSTANCE: DashBuddyDatabase? = null
        private const val DATABASE_NAME = "dashbuddy_database"

        val MIGRATION_22_23 = object : androidx.room.migration.Migration(22, 23) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // This SQL matches perfectly because Enums are stored as TEXT
                db.execSQL(
                    """
            CREATE TABLE IF NOT EXISTS `app_events` (
                `sequenceId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                `aggregateId` TEXT, 
                `eventType` TEXT NOT NULL, 
                `eventPayload` TEXT NOT NULL, 
                `occurredAt` INTEGER NOT NULL, 
                `metadata` TEXT
            )
            """.trimIndent()
                )

                // Indices
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_app_events_aggregateId` ON `app_events` (`aggregateId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_app_events_eventType` ON `app_events` (`eventType`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_app_events_occurredAt` ON `app_events` (`occurredAt`)")
            }
        }

        fun getDatabase(context: Context): DashBuddyDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DashBuddyDatabase::class.java,
                    DATABASE_NAME
                )
                    // .addMigrations(MIGRATION_15_16)
                    .addMigrations(MIGRATION_22_23)
                    // For now, if a migration is needed, destroy and rebuild the database.
                    // TODO: Implement proper migrations for production releases.
                    .fallbackToDestructiveMigration(true)
                    // .addMigrations(MIGRATION_1_2, MIGRATION_2_3) // Example for later
                    // .allowMainThreadQueries() // Avoid this in production code! For testing only if necessary.
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}