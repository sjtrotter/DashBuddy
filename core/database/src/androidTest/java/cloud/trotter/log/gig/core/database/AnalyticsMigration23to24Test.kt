package cloud.trotter.log.gig.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #1517: execute AutoMigration(23→24) against a populated v23 database. The log and every existing column survive
 * unchanged; offer_records.unassignedAt and delivery_records.sourceOfferSeq start NULL and nullable (the
 * PROJECTOR_VERSION 19 refold fills them). Instrumented: requires the exported v24 schema.
 */
@RunWith(AndroidJUnit4::class)
class AnalyticsMigration23to24Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), GigLogDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun preservesLogAndRowsAndAddsTwoNullableColumns() {
        val name = "migration-23-to-24"
        val platform = Platform.entries.first { it != Platform.Unknown }.wire
        val before = mutableMapOf<String, Map<String, String?>>()
        val newColumns = mapOf(
            "delivery_records" to listOf("sourceOfferSeq"),
            "offer_records" to listOf("unassignedAt"),
            "app_events" to emptyList(),
        )
        helper.createDatabase(name, 23).use { db ->
            db.execSQL("""INSERT INTO app_events (sequenceId, aggregateId, eventType, eventPayload, occurredAt)
                VALUES (1, 'session', ?, '{}', 1200000)""", arrayOf(AppEventType.MANUAL_DELIVERY.name))
            db.execSQL("""INSERT INTO delivery_records
                (eventSequenceId, sessionId, platform, jobId, taskId, phaseStartedAt, completedAt,
                 payBasis, costBasis, realizedPay, jobOfferHashes)
                VALUES (1, 'session', ?, 'manual:1', 'manual:1', 1200000, 1200000, 'MANUAL', 'NONE', 7.5, NULL)""", arrayOf(platform))
            db.execSQL("""INSERT INTO offer_records
                (eventSequenceId, sessionId, platform, offerHash, outcome, presentedAt, decidedAt, itemCount, outcomeResolved, offerScope)
                VALUES (3, 'session', ?, 'offer', ?, 1000, 60000, 1, NULL, 'STANDALONE')""", arrayOf(platform, AppEventType.OFFER_ACCEPTED.name))
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
        // Validates all table/index definitions against the exported v24 schema, not just values.
        helper.runMigrationsAndValidate(name, 24, true).use { db ->
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
