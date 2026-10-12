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
class AnalyticsMigration18to19Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), DashBuddyDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun preservesLogAndFrozenRowsAndAddsUnknownMileageBasis() {
        val name = "migration-18-to-19"
        val before = mutableMapOf<String, Map<String, String?>>()
        helper.createDatabase(name, 18).use { db ->
            db.execSQL("""INSERT INTO app_events (sequenceId, aggregateId, eventType, eventPayload, occurredAt)
                VALUES (1, 'session', 'DELIVERY_COMPLETED', '{}', 1200000)""")
            db.execSQL("""INSERT INTO delivery_records
                (eventSequenceId, sessionId, platform, jobId, taskId, phaseStartedAt, completedAt,
                 payBasis, costBasis, realizedPay, realizedMiles, realizedMinutes, netProfit,
                 frozenCostPerMile, milesToStore, milesToDropoff, driverAdjustedAt)
                VALUES (1, 'session', 'doordash', 'job', 'drop', 540000, 1200000,
                 'USER_CORRECTED', 'OFFER_FROZEN', 12.5, 8, 20, 10.1, 0.3, 2, 4, 1300000)""")
            listOf("app_events", "delivery_records").forEach { table ->
                db.query("SELECT * FROM $table").use { c ->
                    assertTrue(c.moveToFirst())
                    before[table] = c.columnNames.associateWith { column ->
                        val i = c.getColumnIndexOrThrow(column)
                        if (c.isNull(i)) null else c.getString(i)
                    }
                }
            }
        }
        helper.runMigrationsAndValidate(name, 19, true).use { db ->
            before.forEach { (table, row) ->
                db.query("SELECT * FROM $table").use { c ->
                    assertEquals(1, c.count)
                    assertTrue(c.moveToFirst())
                    row.forEach { (column, value) ->
                        val i = c.getColumnIndexOrThrow(column)
                        assertEquals("$table.$column", value, if (c.isNull(i)) null else c.getString(i))
                    }
                    if (table == "delivery_records") {
                        assertEquals("UNKNOWN", c.getString(c.getColumnIndexOrThrow("milesBasis")))
                    }
                }
            }
        }
    }
}
