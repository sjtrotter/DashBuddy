package cloud.trotter.dashbuddy.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #314 (PR1-review finding 1) — execute the REAL committed `AutoMigration(8→9)` against a
 * **populated** v8 database and prove it is non-destructive: the pre-existing `app_events` rows
 * survive and the four new analytics tables exist afterward.
 *
 * Instrumented (androidTest) because [MigrationTestHelper] opens an on-disk SQLite DB via the
 * framework factory and replays the exported schema JSONs (surfaced to the test assets by the
 * `androidTest` sourceSet in `build.gradle.kts`). Runs under `:core:database:connectedAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class AnalyticsMigration8to9Test {

    private val dbName = "migration-8-to-9-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DashBuddyDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate8To9_preservesAppEvents_andAddsAnalyticsTables() {
        // Create the schema at v8 and seed two app_events rows.
        helper.createDatabase(dbName, 8).use { db ->
            db.execSQL(
                """INSERT INTO app_events (sequenceId, aggregateId, eventType, eventPayload, occurredAt, metadata)
                   VALUES (1, 'S1', 'DASH_START', '{}', 1000, NULL)""",
            )
            db.execSQL(
                """INSERT INTO app_events (sequenceId, aggregateId, eventType, eventPayload, occurredAt, metadata)
                   VALUES (2, 'S1', 'DELIVERY_COMPLETED', '{}', 2000, '{"odometer":5.0}')""",
            )
        }

        // Run the real AutoMigration(8→9) and validate against the exported 9.json schema.
        val db = helper.runMigrationsAndValidate(dbName, 9, true)

        // The pre-existing event rows survived the migration (additive-only, not destructive).
        db.query("SELECT COUNT(*) FROM app_events").use { c ->
            c.moveToFirst()
            assertEquals("app_events rows preserved across 8→9", 2, c.getInt(0))
        }
        db.query("SELECT eventType FROM app_events WHERE sequenceId = 2").use { c ->
            c.moveToFirst()
            assertEquals("DELIVERY_COMPLETED", c.getString(0))
        }

        // The four new analytics tables exist after the migration.
        val expected = setOf("delivery_records", "session_records", "offer_records", "analytics_projection_state")
        val found = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='table'").use { c ->
            while (c.moveToNext()) found += c.getString(0)
        }
        assertTrue("new analytics tables present: $expected in $found", found.containsAll(expected))
        db.close()
    }
}
