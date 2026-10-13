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

@RunWith(AndroidJUnit4::class)
class AnalyticsMigration19to20Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), DashBuddyDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun preservesLogAndAddsLedgerTablesWithEventPrimaryKeys() {
        val name = "migration-19-to-20"
        helper.createDatabase(name, 19).use { db ->
            db.execSQL("""INSERT INTO app_events (sequenceId, aggregateId, eventType, eventPayload, occurredAt)
                VALUES (1, 'session', 'DASH_START', '{}', 1000)""")
        }
        helper.runMigrationsAndValidate(name, 20, true).use { db ->
            db.query("SELECT eventType FROM app_events WHERE sequenceId = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("DASH_START", c.getString(0))
            }
            for (table in listOf("expense_records", "fuel_records")) {
                db.query("PRAGMA table_info($table)").use { c ->
                    var foundPrimaryKey = false
                    while (c.moveToNext()) {
                        if (c.getString(c.getColumnIndexOrThrow("name")) == "eventSequenceId") {
                            assertEquals(1, c.getInt(c.getColumnIndexOrThrow("pk")))
                            foundPrimaryKey = true
                        }
                    }
                    assertTrue("$table has eventSequenceId as its primary key", foundPrimaryKey)
                }
                db.query("SELECT COUNT(*) FROM $table").use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals(0, c.getInt(0))
                }
            }
        }
    }
}
