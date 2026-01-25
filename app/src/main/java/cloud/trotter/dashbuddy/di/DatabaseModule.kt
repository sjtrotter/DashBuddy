package cloud.trotter.dashbuddy.di

import android.content.Context
import androidx.room.Room
import cloud.trotter.dashbuddy.data.base.DashBuddyDatabase
import cloud.trotter.dashbuddy.data.event.AppEventDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DashBuddyDatabase {
        return Room.databaseBuilder(
            context,
            DashBuddyDatabase::class.java,
            "dashbuddy-v2.db"
        )
            .fallbackToDestructiveMigration(false)
            .build()
    }

    @Provides
    fun provideAppEventDao(db: DashBuddyDatabase): AppEventDao {
        return db.appEventDao()
    }
}