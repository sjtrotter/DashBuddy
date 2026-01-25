package cloud.trotter.dashbuddy.di

import android.content.Context
import androidx.room.Room
import cloud.trotter.dashbuddy.data.base.DashBuddyDatabase
import cloud.trotter.dashbuddy.data.event.AppEventDao
// ... import all other DAOs here ...
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class) // This module lives as long as the App lives
object DatabaseModule {

    @Provides
    @Singleton // Make sure we only ever have ONE database instance
    fun provideDatabase(@ApplicationContext context: Context): DashBuddyDatabase {
        return Room.databaseBuilder(
            context,
            DashBuddyDatabase::class.java,
            "dashbuddy-database"
        )
            .fallbackToDestructiveMigration(false)
            .build()
    }

    // --- PROVIDE DAOs ---
    // Hilt needs to know how to find the DAO. It asks the Database for it.

    @Provides
    fun provideAppEventDao(database: DashBuddyDatabase): AppEventDao {
        return database.appEventDao()
    }

    // ... Repeat for EVERY DAO in your system (OrderDao, StoreDao, etc) ...
}