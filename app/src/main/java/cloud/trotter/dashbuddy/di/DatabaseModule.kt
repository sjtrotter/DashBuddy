package cloud.trotter.dashbuddy.di

import android.content.Context
import androidx.room.Room
import cloud.trotter.dashbuddy.data.base.DashBuddyDatabase
import cloud.trotter.dashbuddy.data.chat.ChatDao
import cloud.trotter.dashbuddy.data.event.AppEventDao
import cloud.trotter.dashbuddy.data.log.snapshots.SnapshotDao
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

    @Provides
    fun provideChatDao(db: DashBuddyDatabase): ChatDao {
        return db.chatDao()
    }

    @Provides
    fun provideSnapshotDao(db: DashBuddyDatabase): SnapshotDao {
        return db.snapshotDao()
    }
}