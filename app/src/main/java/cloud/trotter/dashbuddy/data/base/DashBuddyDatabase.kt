package cloud.trotter.dashbuddy.data.base

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
//import androidx.room.TypeConverters // If you use Date type converters later
import cloud.trotter.dashbuddy.data.current.CurrentDao
import cloud.trotter.dashbuddy.data.dash.DashDao
import cloud.trotter.dashbuddy.data.links.dashZone.DashZoneDao
//import cloud.trotter.dashbuddy.data.dao.OfferDao
import cloud.trotter.dashbuddy.data.zone.ZoneDao
import cloud.trotter.dashbuddy.data.current.CurrentEntity
import cloud.trotter.dashbuddy.data.dash.DashEntity
import cloud.trotter.dashbuddy.data.links.dashZone.DashZoneEntity
import cloud.trotter.dashbuddy.data.offer.OfferEntity
import cloud.trotter.dashbuddy.data.zone.ZoneEntity

// If you end up using Date objects directly in entities and need type converters:
// import cloud.trotter.dashbuddy.data.converters.DateConverter

@Database(
    entities = [
        ZoneEntity::class,
        DashEntity::class,
        OfferEntity::class,
        DashZoneEntity::class,
        CurrentEntity::class // Your entity for current dash state
    ],
    version = 3, // Start with version 1
    exportSchema = false // Set to true if you plan to use schema for testing migrations
// For production, schema export is recommended.
)
// @TypeConverters(DateConverter::class) // Uncomment if you use a DateConverter for 'start'/'stop' in Dash
abstract class DashBuddyDatabase : RoomDatabase() {
    // Abstract methods for each of your DAOs
    abstract fun zoneDao(): ZoneDao
    abstract fun dashDao(): DashDao
    abstract fun dashZoneDao(): DashZoneDao
    abstract fun currentDashDao(): CurrentDao
//    abstract fun offerDao(): OfferDao // You'll need to create this DAO interface

    companion object {
        @Volatile
        private var INSTANCE: DashBuddyDatabase? = null
        private const val DATABASE_NAME = "dashbuddy_database"

        fun getDatabase(context: Context): DashBuddyDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DashBuddyDatabase::class.java,
                    DATABASE_NAME
                )
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