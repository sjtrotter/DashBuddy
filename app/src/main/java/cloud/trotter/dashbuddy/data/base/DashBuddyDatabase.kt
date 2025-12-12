package cloud.trotter.dashbuddy.data.base

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
//import androidx.room.migration.Migration
//import androidx.sqlite.db.SupportSQLiteDatabase
import cloud.trotter.dashbuddy.data.current.CurrentDao
import cloud.trotter.dashbuddy.data.current.CurrentEntity
import cloud.trotter.dashbuddy.data.customer.CustomerDao
import cloud.trotter.dashbuddy.data.customer.CustomerEntity
import cloud.trotter.dashbuddy.data.dash.DashDao
import cloud.trotter.dashbuddy.data.dash.DashEntity
import cloud.trotter.dashbuddy.data.event.DashEventDao
import cloud.trotter.dashbuddy.data.event.DashEventEntity
import cloud.trotter.dashbuddy.data.event.DropoffEventDao
import cloud.trotter.dashbuddy.data.event.DropoffEventEntity
import cloud.trotter.dashbuddy.data.event.OfferEventDao
import cloud.trotter.dashbuddy.data.event.OfferEventEntity
import cloud.trotter.dashbuddy.data.event.PickupEventDao
import cloud.trotter.dashbuddy.data.event.PickupEventEntity
import cloud.trotter.dashbuddy.data.links.dashZone.DashZoneDao
import cloud.trotter.dashbuddy.data.links.dashZone.DashZoneEntity
import cloud.trotter.dashbuddy.data.offer.OfferDao
import cloud.trotter.dashbuddy.data.offer.OfferEntity
import cloud.trotter.dashbuddy.data.order.OrderDao
import cloud.trotter.dashbuddy.data.order.OrderEntity
import cloud.trotter.dashbuddy.data.pay.AppPayEntity
import cloud.trotter.dashbuddy.data.pay.AppPayType
import cloud.trotter.dashbuddy.data.pay.AppPayDao
import cloud.trotter.dashbuddy.data.pay.TipEntity
import cloud.trotter.dashbuddy.data.pay.TipDao
import cloud.trotter.dashbuddy.data.store.StoreEntity
import cloud.trotter.dashbuddy.data.store.StoreDao
import cloud.trotter.dashbuddy.data.zone.ZoneDao
import cloud.trotter.dashbuddy.data.zone.ZoneEntity

@Database(
    entities = [
        AppPayEntity::class,
        AppPayType::class,
        CurrentEntity::class,
        CustomerEntity::class,
        DashEntity::class,
        DashEventEntity::class,
        DashZoneEntity::class,
        DropoffEventEntity::class,
        OfferEntity::class,
        OfferEventEntity::class,
        OrderEntity::class,
        PickupEventEntity::class,
        StoreEntity::class,
        TipEntity::class,
        ZoneEntity::class,
    ],
    version = 22,
    exportSchema = false // Set to true if you plan to use schema for testing migrations
// For production, schema export is recommended.
)
@TypeConverters(DataTypeConverters::class)
abstract class DashBuddyDatabase : RoomDatabase() {
    // Abstract methods for each of your DAOs
    abstract fun appPayDao(): AppPayDao
    abstract fun currentDashDao(): CurrentDao
    abstract fun customerDao(): CustomerDao
    abstract fun dashDao(): DashDao
    abstract fun dashEventDao(): DashEventDao
    abstract fun dashZoneDao(): DashZoneDao
    abstract fun dropoffEventDao(): DropoffEventDao
    abstract fun offerDao(): OfferDao
    abstract fun offerEventDao(): OfferEventDao
    abstract fun orderDao(): OrderDao
    abstract fun pickupEventDao(): PickupEventDao
    abstract fun storeDao(): StoreDao
    abstract fun tipDao(): TipDao
    abstract fun zoneDao(): ZoneDao

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
                    // .addMigrations(MIGRATION_15_16)
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