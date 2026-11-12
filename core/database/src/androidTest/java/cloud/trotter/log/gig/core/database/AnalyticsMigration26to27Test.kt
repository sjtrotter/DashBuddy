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

/** #1599: v26 → v27 adds the empty `receipt_match_rows` table; an existing receipt record survives. */
@RunWith(AndroidJUnit4::class)
class AnalyticsMigration26to27Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), GigLogDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun preservesReceiptRecordsAndAddsAnEmptyMatchTable() {
        val name = "migration-26-to-27"
        helper.createDatabase(name, 26).use { db ->
            db.execSQL("""INSERT INTO app_events (sequenceId, aggregateId, eventType, eventPayload, occurredAt)
                VALUES (1, 'session', 'RECEIPT_OBSERVED', '{}', 1000)""")
            db.execSQL("""INSERT INTO receipt_records
                (evidenceId, sessionId, platform, jobId, subjectTaskId, firstSeenAt, lastSeenAt, totalCents,
                 basePayCents, tipCents, isExpanded, scope, proofGap, revision, lastEventSequenceId)
                VALUES ('receipt:t1:900', 'session', 'doordash', 'J1', 't1', 900, 1000, 2050,
                 NULL, NULL, 0, 'UNPROVEN', 'OFFER_COUNT', 1, 1)""")
        }
        helper.runMigrationsAndValidate(name, 27, true).use { db ->
            db.query("SELECT totalCents, scope FROM receipt_records WHERE evidenceId = 'receipt:t1:900'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(2050L, c.getLong(0))
                assertEquals("UNPROVEN", c.getString(1))
            }
            db.query("SELECT COUNT(*) FROM receipt_match_rows").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
        }
    }
}
