package cloud.trotter.dashbuddy.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #660 piece 2 — execute the REAL committed `AutoMigration(13→14)` against a **populated** v13 database
 * and prove it is additive-only: the pre-existing analytics rows survive, and the one new
 * nullable-defaulted column (delivery_records.sessionAssigned) is present and DEFAULT 0 on existing
 * rows (there is NO `PROJECTOR_VERSION` bump — a fresh drain folds new `DELIVERY_SESSION_ASSIGN`
 * events; nothing needs refolding).
 *
 * Instrumented (androidTest) because [MigrationTestHelper] opens an on-disk SQLite DB via the
 * framework factory and replays the exported schema JSONs. Runs under
 * `:core:database:connectedAndroidTest` — it does NOT gate unit-only PR CI (the unit-level
 * [SchemaVersionGuardTest] is the CI-gating guard); needs one device/emulator run before merge (three
 * are now pending a device pass: v11→v12, v12→v13, v13→v14).
 */
@RunWith(AndroidJUnit4::class)
class AnalyticsMigration13to14Test {

    private val dbName = "migration-13-to-14-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DashBuddyDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate13To14_preservesRows_addsSessionAssignedColumnDefaultZero() {
        helper.createDatabase(dbName, 13).use { db ->
            db.execSQL(
                """INSERT INTO delivery_records
                   (eventSequenceId, sessionId, platform, jobId, taskId, storeName, customerHash,
                    addressHash, phaseStartedAt, arrivedAt, completedAt, deadlineMillis, realizedPay,
                    payBasis, tip, basePay, odometerAtCompletion, realizedMiles, realizedMinutes,
                    frozenCostPerMile, frozenFuelPerMile, frozenNonFuelPerMile, netProfit, costBasis,
                    cashTip, originalPayBasis, storeKey, payoutStoreForms, storeKeyPinned,
                    milesToStore, milesToDropoff)
                   VALUES (1, NULL, 'doordash', 'J1', 'T1', 'Target', 'ch', 'ah', 100, NULL, 3000,
                           NULL, 10.0, 'RECEIPT_TOTAL', NULL, NULL, 105.0, 5.0, 1.0, 0.25, 0.10, 0.15,
                           8.75, 'OFFER_FROZEN', NULL, 'RECEIPT_TOTAL', 'doordash|target|1', NULL, 0,
                           NULL, NULL)""",
            )
            db.execSQL(
                """INSERT INTO session_records
                   (sessionId, platform, startedAt, endedAt, lastEventAt, endSource, startSource,
                    startOdometer, lastOdometer, reportedEarnings, reportedDurationMillis, offersReceived,
                    offersAccepted, offersDeclined, offersTimeout, deliveries, jobsCompleted, legStateJson)
                   VALUES ('S1', 'doordash', 1000, 5000, 5000, 'summary_screen', 'interaction', 100.0,
                           110.0, 25.0, 4000, 1, 1, 0, 0, 1, 1, NULL)""",
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 14, true)

        // Pre-existing rows survived (additive-only).
        db.query("SELECT COUNT(*) FROM delivery_records").use { c ->
            c.moveToFirst(); assertEquals(1, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM session_records").use { c ->
            c.moveToFirst(); assertEquals(1, c.getInt(0))
        }
        // New sessionAssigned column exists and is 0 (DEFAULT) on the pre-existing row.
        db.query("SELECT sessionAssigned FROM delivery_records WHERE eventSequenceId = 1").use { c ->
            c.moveToFirst()
            assertEquals("sessionAssigned DEFAULT 0 post-migration", 0, c.getInt(0))
        }
        db.close()
    }
}
