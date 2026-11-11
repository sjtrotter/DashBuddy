package cloud.trotter.log.gig.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** #1598: v24 → v25 adds the empty `receipt_records` table and leaves the event log alone. */
@RunWith(AndroidJUnit4::class)
class AnalyticsMigration24to25Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), GigLogDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun preservesLogAndAddsEmptyReceiptRecordsKeyedByEvidenceId() {
        val name = "migration-24-to-25"
        helper.createDatabase(name, 24).use { db ->
            db.execSQL("""INSERT INTO app_events (sequenceId, aggregateId, eventType, eventPayload, occurredAt)
                VALUES (1, 'session', 'DASH_START', '{}', 1000)""")
        }
        helper.runMigrationsAndValidate(name, 25, true).use { db ->
            db.query("SELECT eventType FROM app_events WHERE sequenceId = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("DASH_START", c.getString(0))
            }
            db.query("PRAGMA table_info(receipt_records)").use { c ->
                var foundPrimaryKey = false
                while (c.moveToNext()) {
                    if (c.getString(c.getColumnIndexOrThrow("name")) == "evidenceId") {
                        assertEquals(1, c.getInt(c.getColumnIndexOrThrow("pk")))
                        foundPrimaryKey = true
                    }
                }
                assertTrue("receipt_records has evidenceId as its primary key", foundPrimaryKey)
            }
            db.query("SELECT COUNT(*) FROM receipt_records").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
        }
    }
}
