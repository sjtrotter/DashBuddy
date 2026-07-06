package cloud.trotter.dashbuddy.core.database.di

import android.content.Context
import androidx.room.Room
import cloud.trotter.dashbuddy.core.database.DashBuddyDatabase
import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsDao
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

    /**
     * Opens the durable database with **no destructive fallback** — a deliberate data-safety choice
     * (#690, the long-noted #314 follow-up).
     *
     * `app_events` is the analytics **source of truth**: the read-model tables (delivery/session/
     * offer records) are a rebuildable projection folded from it, so losing `app_events` loses the
     * driver's history irrecoverably. `.fallbackToDestructiveMigration(true)` would let Room silently
     * DROP-and-recreate every table on any version it lacks a path for — a silent wipe of that
     * source of truth. Omitting it means such an upgrade throws `IllegalStateException` at open: a
     * **loud crash instead of a silent wipe**. For a single-user alpha that is the correct posture —
     * the dev can back up + resolve rather than lose data unknowingly. The committed
     * `AutoMigration`s (8→9, 9→10) provide the real upgrade paths, so this never fires on a supported
     * upgrade; the guard exists for a *future* forgotten migration.
     *
     * Belt: [DatabaseBackup.backupIfUpgradePending] snapshots the current DB files just before Room
     * opens them when an upgrade is pending, so even a botched future migration is recoverable. It is
     * failure-tolerant — a backup failure can never prevent the app from starting.
     */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DashBuddyDatabase {
        DatabaseBackup.backupIfUpgradePending(context, DashBuddyDatabase.NAME, DashBuddyDatabase.VERSION)
        return Room.databaseBuilder(
            context,
            DashBuddyDatabase::class.java,
            DashBuddyDatabase.NAME
        )
            // Intentionally NO .fallbackToDestructiveMigration(...) — see KDoc above (#690).
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

    @Provides
    fun provideAnalyticsDao(db: DashBuddyDatabase): AnalyticsDao {
        return db.analyticsDao()
    }

}