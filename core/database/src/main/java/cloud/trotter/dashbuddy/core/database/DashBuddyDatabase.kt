package cloud.trotter.dashbuddy.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsDao
import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsProjectionStateEntity
import cloud.trotter.dashbuddy.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.OfferRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.PickupRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.SessionRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.StoreEntity
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
        // Store entity resolution (#159) — additive: identity table + per-pickup visits table.
        StoreEntity::class,
        PickupRecordEntity::class,
    ],
    version = DashBuddyDatabase.VERSION,
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
    // v10→v11 (#688/#703) is additive-only: two new nullable columns on delivery_records
    // — cashTip (driver-entered cash tip, added to gross/net only at read sites) and
    // originalPayBasis (the first-fold payBasis, never rewritten by a correction — the
    // #703 receipt-evidence hydration anchor). Both repopulate from the immutable log on
    // the PROJECTOR_VERSION 3→4 refold this bump triggers (cashTip stays null in history —
    // no cash events exist; originalPayBasis is stamped for every row). Additive ⇒ never
    // wipes app_events or the existing analytics rows.
    // v11→v12 (#159) is additive-only: two new tables (`stores` + `pickup_records`) plus new
    // nullable/defaulted columns — delivery_records.{storeKey, payoutStoreForms, storeKeyPinned},
    // offer_records.{storeKey, linkedJobId} — and new indices (delivery_records.jobId/storeKey,
    // offer_records.linkedJobId). The `PROJECTOR_VERSION` 4→5 refold this bump triggers populates the
    // two new tables + the storeKey columns for ALL history (rebuild ≡ backfill; the backfill is just
    // the first drain). Additive ⇒ never wipes app_events or the existing analytics rows.
    autoMigrations = [
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 11, to = 12),
    ],
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

    companion object {
        /**
         * On-disk database file name. SSOT for both the Room builder and the pre-open backup
         * belt ([cloud.trotter.dashbuddy.core.database.di.DatabaseBackup]).
         */
        const val NAME = "dashbuddy-v2.db"

        /**
         * The schema version this build ships. SSOT: consumed by the `@Database` annotation
         * above, by the pre-open backup's "is an upgrade pending?" check, and asserted equal to
         * the newest exported schema JSON by the unit-level `SchemaVersionGuardTest` (#690). Bump
         * this in lockstep with a new `schemas/**/<N>.json`, an `AutoMigration(N-1 → N)`, and its
         * `MigrationTestHelper` case — see the release checklist in CLAUDE.md.
         */
        const val VERSION = 12
    }

}