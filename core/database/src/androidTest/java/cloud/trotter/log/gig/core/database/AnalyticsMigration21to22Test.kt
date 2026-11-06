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

/**
 * #1535: execute AutoMigration(21→22) against a populated v21 database. The event log, the machine odometer anchors,
 * the earnings override, the leg state and the watermark survive unchanged, and the new `milesOverride` column
 * starts NULL. No PROJECTOR_VERSION bump: SESSION_MILES_CORRECTION cannot exist in already-folded history.
 * Instrumented: requires the build-exported v22 schema and a device/emulator.
 */
@RunWith(AndroidJUnit4::class)
class AnalyticsMigration21to22Test {

    private val dbName = "migration-21-to-22-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        GigLogDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate21To22_preservesShiftAndLog_addsMilesOverrideNull() {
        helper.createDatabase(dbName, 21).use { db ->
            db.execSQL(
                """INSERT INTO app_events (sequenceId, aggregateId, eventType, eventPayload, occurredAt, metadata)
                   VALUES (7, '1152', 'DASH_STOP', '{"sessionId":"1152"}', 2000, NULL)""",
            )
            db.execSQL(
                """INSERT INTO session_records
                   (sessionId, platform, startedAt, endedAt, lastEventAt, endSource, startSource,
                    startOdometer, lastOdometer, reportedEarnings, reportOverrideMode, reportOverride,
                    reportCorrectedAt, reportedDurationMillis, offersReceived, offersAccepted, offersDeclined,
                    offersTimeout, deliveries, jobsCompleted, legStateJson)
                   VALUES ('1152', 'doordash', 1000, 2000, 2000, 'manual', 'interaction',
                           100.0, 159.4, 40.14, 'SET', 42.5, 1500, NULL, 1, 1, 0, 0, 1, 1, '{"anchor":100.0}')""",
            )
            db.execSQL(
                "INSERT INTO analytics_projection_state (id, watermarkSequenceId, projectorVersion) VALUES (1, 7, 17)",
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 22, true)

        db.query("SELECT sequenceId, aggregateId, eventType, eventPayload FROM app_events").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(7L, c.getLong(0))
            assertEquals("1152", c.getString(1))
            assertEquals("DASH_STOP", c.getString(2))
            assertEquals("""{"sessionId":"1152"}""", c.getString(3))
        }
        db.query(
            """SELECT startOdometer, lastOdometer, reportOverrideMode, reportOverride, reportCorrectedAt,
                      legStateJson, milesOverride FROM session_records WHERE sessionId = '1152'""",
        ).use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(100.0.toRawBits(), c.getDouble(0).toRawBits())
            assertEquals(159.4.toRawBits(), c.getDouble(1).toRawBits())
            assertEquals("SET", c.getString(2))
            assertEquals(42.5, c.getDouble(3), 0.0)
            assertEquals(1500L, c.getLong(4))
            assertEquals("""{"anchor":100.0}""", c.getString(5))
            assertTrue("milesOverride NULL post-migration", c.isNull(6))
        }
        db.query("SELECT watermarkSequenceId, projectorVersion FROM analytics_projection_state").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(7L, c.getLong(0))
            assertEquals(17, c.getInt(1))
        }
        db.close()
    }
}
