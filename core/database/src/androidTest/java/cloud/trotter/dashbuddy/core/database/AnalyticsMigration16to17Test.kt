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
 * #1134 — execute AutoMigration(16→17) against a populated v16 database. The machine report
 * survives byte-identically and the three additive nullable override columns start NULL.
 * No PROJECTOR_VERSION bump: SESSION_REPORT_CORRECTION cannot exist in already-folded history.
 * Instrumented: requires the build-exported v17 schema and a device/emulator.
 */
@RunWith(AndroidJUnit4::class)
class AnalyticsMigration16to17Test {

    private val dbName = "migration-16-to-17-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DashBuddyDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate16To17_preservesReport_addsOverrideColumnsNull() {
        helper.createDatabase(dbName, 16).use { db ->
            db.execSQL(
                """INSERT INTO session_records
                   (sessionId, platform, startedAt, endedAt, lastEventAt, endSource, startSource,
                    startOdometer, lastOdometer, reportedEarnings, reportedDurationMillis,
                    offersReceived, offersAccepted, offersDeclined, offersTimeout, deliveries,
                    jobsCompleted, legStateJson)
                   VALUES ('483', 'doordash', 1000, 2000, 2000, 'early_offline', 'interaction',
                           100.0, 100.0, 40.14, NULL, 0, 0, 0, 0, 0, 0, NULL)""",
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 17, true)

        db.query("SELECT COUNT(*) FROM session_records").use { c ->
            c.moveToFirst(); assertEquals(1, c.getInt(0))
        }
        db.query("SELECT reportedEarnings, endSource FROM session_records WHERE sessionId = '483'").use { c ->
            c.moveToFirst()
            assertEquals(40.14.toRawBits(), c.getDouble(0).toRawBits())
            assertEquals("early_offline", c.getString(1))
        }
        db.query(
            "SELECT reportOverrideMode, reportOverride, reportCorrectedAt FROM session_records WHERE sessionId = '483'",
        ).use { c ->
            c.moveToFirst()
            assertTrue("reportOverrideMode NULL post-migration", c.isNull(0))
            assertTrue("reportOverride NULL post-migration", c.isNull(1))
            assertTrue("reportCorrectedAt NULL post-migration", c.isNull(2))
        }
        db.close()
    }
}
