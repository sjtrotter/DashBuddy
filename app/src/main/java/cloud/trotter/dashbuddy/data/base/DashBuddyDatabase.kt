package cloud.trotter.dashbuddy.data.base

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import cloud.trotter.dashbuddy.data.current.CurrentDao
import cloud.trotter.dashbuddy.data.current.CurrentEntity
import cloud.trotter.dashbuddy.data.dash.DashDao
import cloud.trotter.dashbuddy.data.dash.DashEntity
import cloud.trotter.dashbuddy.data.links.dashZone.DashZoneDao
import cloud.trotter.dashbuddy.data.links.dashZone.DashZoneEntity
import cloud.trotter.dashbuddy.data.offer.OfferDao
import cloud.trotter.dashbuddy.data.offer.OfferEntity
import cloud.trotter.dashbuddy.data.order.OrderDao
import cloud.trotter.dashbuddy.data.order.OrderEntity
import cloud.trotter.dashbuddy.data.zone.ZoneDao
import cloud.trotter.dashbuddy.data.zone.ZoneEntity

@Database(
    entities = [
        ZoneEntity::class,
        DashEntity::class,
        OfferEntity::class,
        OrderEntity::class,
        DashZoneEntity::class,
        CurrentEntity::class // Your entity for current dash state
    ],
    version = 4, // Start with version 1
    exportSchema = false // Set to true if you plan to use schema for testing migrations
// For production, schema export is recommended.
)
@TypeConverters(DataTypeConverters::class)
abstract class DashBuddyDatabase : RoomDatabase() {
    // Abstract methods for each of your DAOs
    abstract fun zoneDao(): ZoneDao
    abstract fun dashDao(): DashDao
    abstract fun dashZoneDao(): DashZoneDao
    abstract fun currentDashDao(): CurrentDao
    abstract fun offerDao(): OfferDao
    abstract fun orderDao(): OrderDao

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