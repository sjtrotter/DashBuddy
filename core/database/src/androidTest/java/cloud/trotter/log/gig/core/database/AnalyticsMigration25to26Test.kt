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

/** #1119 S2: v25 → v26 adds the nullable `offer_records.ambiguousJobIds`; an existing offer row survives with it null. */
@RunWith(AndroidJUnit4::class)
class AnalyticsMigration25to26Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), GigLogDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun preservesOfferRowsWithAmbiguousJobIdsNull() {
        val name = "migration-25-to-26"
        helper.createDatabase(name, 25).use { db ->
            db.execSQL("""INSERT INTO app_events (sequenceId, aggregateId, eventType, eventPayload, occurredAt)
                VALUES (1, 'session', 'OFFER_DECLINED', '{}', 1000)""")
            db.execSQL("""INSERT INTO offer_records
                (eventSequenceId, sessionId, platform, offerHash, outcome, presentedAt, decidedAt, itemCount)
                VALUES (1, 'session', 'doordash', 'offer', 'OFFER_DECLINED', 900, 1000, 1)""")
        }
        helper.runMigrationsAndValidate(name, 26, true).use { db ->
            db.query("SELECT outcome, ambiguousJobIds FROM offer_records WHERE eventSequenceId = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("OFFER_DECLINED", c.getString(0))
                assertTrue("the new column is null on an existing row", c.isNull(1))
            }
            db.query("SELECT eventType FROM app_events WHERE sequenceId = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("OFFER_DECLINED", c.getString(0))
            }
        }
    }
}
