package cloud.trotter.dashbuddy.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalyticsMigration17to18Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), DashBuddyDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun preservesLogAndRowsAndAddsSixNullableColumns() {
        val name = "migration-17-to-18"
        val platform = Platform.entries.first { it != Platform.Unknown }.wire
        val before = mutableMapOf<String, Map<String, String?>>()
        val newColumns = mapOf(
            "delivery_records" to listOf("jobOfferCount", "soleOfferHash", "odometerAtArrival"),
            "pickup_records" to listOf("odometerAtConfirmation"),
            "offer_records" to listOf("orderCount", "isShop"),
            "app_events" to emptyList(),
        )
        helper.createDatabase(name, 17).use { db ->
            db.execSQL("""INSERT INTO app_events (sequenceId, aggregateId, eventType, eventPayload, occurredAt)
                VALUES (1, 'session', ?, '{}', 1200000)""", arrayOf(AppEventType.DELIVERY_COMPLETED.name))
            db.execSQL("""INSERT INTO delivery_records
                (eventSequenceId, sessionId, platform, jobId, taskId, phaseStartedAt, completedAt,
                 payBasis, costBasis, realizedPay, realizedMinutes, milesToStore, milesToDropoff)
                VALUES (1, 'session', ?, 'job', 'drop', 540000, 1200000, 'NONE', 'NONE', 12.5, 20, 2, 4)""", arrayOf(platform))
            db.execSQL("""INSERT INTO pickup_records
                (eventSequenceId, sessionId, platform, jobId, taskId, storeName, phaseStartedAt, arrivedAt, confirmedAt)
                VALUES (2, 'session', ?, 'job', 'pickup', 'Store', 120000, 240000, 540000)""", arrayOf(platform))
            db.execSQL("""INSERT INTO offer_records
                (eventSequenceId, sessionId, platform, offerHash, outcome, presentedAt, decidedAt, itemCount, linkedJobId)
                VALUES (3, 'session', ?, 'offer', ?, 1000, 60000, 1, 'job')""", arrayOf(platform, AppEventType.OFFER_ACCEPTED.name))
            newColumns.keys.forEach { table ->
                db.query("SELECT * FROM $table").use { c ->
                    assertTrue(c.moveToFirst())
                    before[table] = c.columnNames.associateWith { column ->
                        val i = c.getColumnIndexOrThrow(column)
                        if (c.isNull(i)) null else c.getString(i)
                    }
                }
            }
        }
        // Validates all table/index definitions against the exported v18 schema, not just values.
        helper.runMigrationsAndValidate(name, 18, true).use { db ->
            newColumns.forEach { (table, columns) ->
                db.query("SELECT * FROM $table").use { c ->
                    assertEquals(1, c.count)
                    assertTrue(c.moveToFirst())
                    before.getValue(table).forEach { (column, value) ->
                        val i = c.getColumnIndexOrThrow(column)
                        assertEquals("$table.$column", value, if (c.isNull(i)) null else c.getString(i))
                    }
                    columns.forEach { assertTrue("$table.$it", c.isNull(c.getColumnIndexOrThrow(it))) }
                }
                db.query("PRAGMA table_info(`$table`)").use { c ->
                    while (c.moveToNext()) {
                        if (c.getString(c.getColumnIndexOrThrow("name")) in columns) {
                            assertEquals(0, c.getInt(c.getColumnIndexOrThrow("notnull")))
                        }
                    }
                }
            }
        }
    }
}
