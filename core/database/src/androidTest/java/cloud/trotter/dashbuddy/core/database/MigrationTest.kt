package cloud.trotter.dashbuddy.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #690 — the destructive fallback is retired, so the committed migration chain is the ONLY thing
 * standing between an upgrade and the durable `app_events` log. This proves the **full chained**
 * upgrade path (v8 → … → the current [DashBuddyDatabase.VERSION]) is non-destructive: a populated v8
 * DB survives the real `AutoMigration`s all the way to the current version with its `app_events` rows
 * intact and legible. The target is [DashBuddyDatabase.VERSION] (not a hardcoded number), so every
 * future version bump automatically extends the chain this test validates.
 *
 * The per-step tests ([AnalyticsMigration8to9Test], [AnalyticsMigration9to10Test]) cover each edge;
 * this test exercises them *in sequence* the way a real device upgrading across two versions would,
 * which is the scenario the retired fallback used to paper over.
 *
 * **Instrumented / androidTest** because [MigrationTestHelper] opens an on-disk SQLite DB via the
 * framework factory and replays the exported schema JSONs (surfaced to test assets by the
 * `androidTest` sourceSet in `build.gradle.kts`). Runs under `:core:database:connectedAndroidTest`.
 * NOTE: the PR CI runs unit tests only, so this does NOT gate PRs — the unit-level
 * [SchemaVersionGuardTest] is the CI-gating guard. Run this locally / in an instrumented job.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-chained-to-current-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DashBuddyDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate8ToCurrent_chained_preservesAppEventsIntact() {
        // Create the schema at v8 and seed app_events rows that MUST survive the whole chain.
        helper.createDatabase(dbName, 8).use { db ->
            db.execSQL(
                """INSERT INTO app_events (sequenceId, aggregateId, eventType, eventPayload, occurredAt, metadata)
                   VALUES (1, 'S1', 'DASH_START', '{}', 1000, NULL)""",
            )
            db.execSQL(
                """INSERT INTO app_events (sequenceId, aggregateId, eventType, eventPayload, occurredAt, metadata)
                   VALUES (2, 'S1', 'DELIVERY_COMPLETED', '{"dropRealizedPay":8.5}', 2000, '{"odometer":5.0}')""",
            )
        }

        // Run every REAL committed auto-migration edge at once (8 → … → VERSION) and validate against
        // the current exported schema. Using VERSION means the validate step demands the FULL chain,
        // and a future bump extends it automatically.
        val db = helper.runMigrationsAndValidate(dbName, DashBuddyDatabase.VERSION, true)

        // app_events rows survived the full chain — the source of truth is intact, not wiped.
        db.query("SELECT COUNT(*) FROM app_events").use { c ->
            c.moveToFirst()
            assertEquals("app_events rows preserved across the chained upgrade to current", 2, c.getInt(0))
        }
        // And the payload/columns are legible, not just present.
        db.query("SELECT eventType, eventPayload, metadata FROM app_events WHERE sequenceId = 2").use { c ->
            c.moveToFirst()
            assertEquals("DELIVERY_COMPLETED", c.getString(0))
            assertEquals("""{"dropRealizedPay":8.5}""", c.getString(1))
            assertEquals("""{"odometer":5.0}""", c.getString(2))
        }
        db.close()
    }
}
