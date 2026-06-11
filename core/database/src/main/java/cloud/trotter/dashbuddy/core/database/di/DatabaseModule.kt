package cloud.trotter.dashbuddy.core.database.di

import android.content.Context
import androidx.room.Room
import cloud.trotter.dashbuddy.core.database.DashBuddyDatabase
import cloud.trotter.dashbuddy.core.database.chat.ChatDao
import cloud.trotter.dashbuddy.core.database.effects.EffectsFiredDao
import cloud.trotter.dashbuddy.core.database.event.AppEventDao
import cloud.trotter.dashbuddy.core.database.observation.ObservationDao
import cloud.trotter.dashbuddy.core.database.snapshot.AppStateSnapshotDao
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
            .fallbackToDestructiveMigration(true)
            .build()
    }

    @Provides
    fun provideAppEventDao(db: DashBuddyDatabase): AppEventDao {
        return db.appEventDao()
    }

    @Provides
    fun provideAppStateSnapshotDao(db: DashBuddyDatabase): AppStateSnapshotDao {
        return db.appStateSnapshotDao()
    }

    @Provides
    fun provideChatDao(db: DashBuddyDatabase): ChatDao {
        return db.chatDao()
    }

    @Provides
    fun provideEffectsFiredDao(db: DashBuddyDatabase): EffectsFiredDao {
        return db.effectsFiredDao()
    }

    @Provides
    fun provideObservationDao(db: DashBuddyDatabase): ObservationDao {
        return db.observationDao()
    }

}