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
 * #688 phase B — execute the REAL committed `AutoMigration(12→13)` against a **populated** v12 database
 * and prove it is additive-only: the pre-existing analytics rows survive, and the three new
 * nullable columns are present (delivery_records.{milesToStore, milesToDropoff},
 * session_records.legStateJson) — left NULL by the ADD COLUMN (the `PROJECTOR_VERSION` 5→6 refold
 * repopulates them from the immutable log).
 *
 * Instrumented (androidTest) because [MigrationTestHelper] opens an on-disk SQLite DB via the
 * framework factory and replays the exported schema JSONs. Runs under
 * `:core:database:connectedAndroidTest` — it does NOT gate unit-only PR CI (the unit-level
 * [SchemaVersionGuardTest] is the CI-gating guard); needs one device/emulator run before merge.
 */
@RunWith(AndroidJUnit4::class)
class AnalyticsMigration12to13Test {

    private val dbName = "migration-12-to-13-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DashBuddyDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate12To13_preservesRows_addsPerLegMileageColumns() {
        helper.createDatabase(dbName, 12).use { db ->
            db.execSQL(
                """INSERT INTO delivery_records
                   (eventSequenceId, sessionId, platform, jobId, taskId, storeName, customerHash,
                    addressHash, phaseStartedAt, arrivedAt, completedAt, deadlineMillis, realizedPay,
                    payBasis, tip, basePay, odometerAtCompletion, realizedMiles, realizedMinutes,
                    frozenCostPerMile, frozenFuelPerMile, frozenNonFuelPerMile, netProfit, costBasis,
                    cashTip, originalPayBasis, storeKey, payoutStoreForms, storeKeyPinned)
                   VALUES (1, 'S1', 'doordash', 'J1', 'T1', 'Target', 'ch', 'ah', 100, NULL, 3000,
                           NULL, 10.0, 'RECEIPT_TOTAL', NULL, NULL, 105.0, 5.0, 1.0, 0.25, 0.10, 0.15,
                           8.75, 'OFFER_FROZEN', NULL, 'RECEIPT_TOTAL', 'doordash|target|1', NULL, 0)""",
            )
            db.execSQL(
                """INSERT INTO session_records
                   (sessionId, platform, startedAt, endedAt, lastEventAt, endSource, startSource,
                    startOdometer, lastOdometer, reportedEarnings, reportedDurationMillis, offersReceived,
                    offersAccepted, offersDeclined, offersTimeout, deliveries, jobsCompleted)
                   VALUES ('S1', 'doordash', 1000, 5000, 5000, 'summary_screen', 'interaction', 100.0,
                           110.0, 25.0, 4000, 1, 1, 0, 0, 1, 1)""",
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 13, true)

        // Pre-existing rows survived (additive-only).
        db.query("SELECT COUNT(*) FROM delivery_records").use { c ->
            c.moveToFirst(); assertEquals(1, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM session_records").use { c ->
            c.moveToFirst(); assertEquals(1, c.getInt(0))
        }
        // New delivery leg columns exist and are NULL post-migration.
        db.query("SELECT milesToStore, milesToDropoff FROM delivery_records WHERE eventSequenceId = 1").use { c ->
            c.moveToFirst()
            assertTrue("milesToStore NULL post-migration", c.isNull(0))
            assertTrue("milesToDropoff NULL post-migration", c.isNull(1))
        }
        // New session leg-state column exists and is NULL post-migration.
        db.query("SELECT legStateJson FROM session_records WHERE sessionId = 'S1'").use { c ->
            c.moveToFirst()
            assertTrue("legStateJson NULL post-migration", c.isNull(0))
        }
        db.close()
    }
}
