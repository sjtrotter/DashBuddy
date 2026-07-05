package cloud.trotter.dashbuddy.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsDao
import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsProjectionStateEntity
import cloud.trotter.dashbuddy.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.OfferRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.SessionRecordEntity
import cloud.trotter.dashbuddy.core.database.chat.ChatDao
import cloud.trotter.dashbuddy.core.database.chat.ChatMessageEntity
import cloud.trotter.dashbuddy.core.database.effects.EffectsFiredDao
import cloud.trotter.dashbuddy.core.database.effects.EffectsFiredEntity
import cloud.trotter.dashbuddy.core.database.event.AppEventDao
import cloud.trotter.dashbuddy.core.database.event.AppEventEntity
import cloud.trotter.dashbuddy.core.database.observation.ObservationDao
import cloud.trotter.dashbuddy.core.database.observation.ObservationEntity
import cloud.trotter.dashbuddy.core.database.snapshot.AppStateSnapshotDao
import cloud.trotter.dashbuddy.core.database.snapshot.AppStateSnapshotEntity

@Database(
    entities = [
        AppEventEntity::class,
        AppStateSnapshotEntity::class,
        ChatMessageEntity::class,
        EffectsFiredEntity::class,
        ObservationEntity::class,
        // Analytics read-model (#314) — purely additive; folded from app_events.
        DeliveryRecordEntity::class,
        SessionRecordEntity::class,
        OfferRecordEntity::class,
        AnalyticsProjectionStateEntity::class,
    ],
    version = 10,
    exportSchema = true,
    // v8→v9 adds only new tables (the analytics read-model). AutoMigration is a
    // no-op for existing tables, so it CANNOT wipe app_events (unlike the
    // destructive fallback, which is left untouched — the auto-migration provides
    // the path so the fallback never fires on this upgrade).
    // v9→v10 (#659) is additive-only: three new nullable columns —
    // delivery_records.frozenFuelPerMile/frozenNonFuelPerMile (frozen fuel/non-fuel
    // split for the 4-step true-net waterfall), offer_records.estFuelPerMile/
    // estNonFuelPerMile (rebuild-stable split hydration), session_records.startSource
    // (retro finding 2 — `started` provenance). Every value repopulates from the
    // immutable log on the PROJECTOR_VERSION 1→2 refold, so the ADD COLUMN nulls are
    // transient. Additive ⇒ never wipes app_events or the existing analytics rows.
    autoMigrations = [AutoMigration(from = 8, to = 9), AutoMigration(from = 9, to = 10)],
)
@TypeConverters(DataTypeConverters::class)
abstract class DashBuddyDatabase : RoomDatabase() {

    // Abstract methods for each of your DAOs
    abstract fun appEventDao(): AppEventDao
    abstract fun appStateSnapshotDao(): AppStateSnapshotDao
    abstract fun chatDao(): ChatDao
    abstract fun effectsFiredDao(): EffectsFiredDao
    abstract fun observationDao(): ObservationDao
    abstract fun analyticsDao(): AnalyticsDao

}