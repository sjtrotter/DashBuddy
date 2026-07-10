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
 * #159 — execute the REAL committed `AutoMigration(11→12)` against a **populated** v11 database and
 * prove it is additive-only: the pre-existing analytics rows survive, the two new tables
 * (`stores` + `pickup_records`) exist, and the new nullable/defaulted columns are present
 * (delivery_records.{storeKey, payoutStoreForms, storeKeyPinned}, offer_records.{storeKey,
 * linkedJobId}) — left NULL / defaulted by the ADD COLUMN (the `PROJECTOR_VERSION` 4→5 refold
 * repopulates them from the immutable log).
 *
 * Instrumented (androidTest) because [MigrationTestHelper] opens an on-disk SQLite DB via the
 * framework factory and replays the exported schema JSONs. Runs under
 * `:core:database:connectedAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class AnalyticsMigration11to12Test {

    private val dbName = "migration-11-to-12-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DashBuddyDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate11To12_preservesRows_addsStoreTablesAndColumns() {
        helper.createDatabase(dbName, 11).use { db ->
            db.execSQL(
                """INSERT INTO delivery_records
                   (eventSequenceId, sessionId, platform, jobId, taskId, storeName, customerHash,
                    addressHash, phaseStartedAt, arrivedAt, completedAt, deadlineMillis, realizedPay,
                    payBasis, tip, basePay, odometerAtCompletion, realizedMiles, realizedMinutes,
                    frozenCostPerMile, frozenFuelPerMile, frozenNonFuelPerMile, netProfit, costBasis,
                    cashTip, originalPayBasis)
                   VALUES (1, 'S1', 'doordash', 'J1', 'T1', 'Target', 'ch', 'ah', 100, NULL, 3000,
                           NULL, 10.0, 'RECEIPT_TOTAL', NULL, NULL, 105.0, 5.0, 1.0, 0.25, 0.10, 0.15,
                           8.75, 'OFFER_FROZEN', NULL, 'RECEIPT_TOTAL')""",
            )
            db.execSQL(
                """INSERT INTO offer_records
                   (eventSequenceId, sessionId, platform, offerHash, outcome, presentedAt, decidedAt,
                    payAmount, distanceMiles, itemCount, merchantName, score, action, quality,
                    estNetPay, estDollarsPerHour, estDollarsPerMile, estTimeMinutes,
                    estOperatingCostPerMile, estFuelPerMile, estNonFuelPerMile)
                   VALUES (2, 'S1', 'doordash', 'HASH1', 'OFFER_ACCEPTED', 900, 950, 12.0, 3.0, 1,
                           'Target', 80.0, 'ACCEPT', 'GOOD', 9.0, 20.0, 3.0, 15.0, 0.25, 0.10, 0.15)""",
            )
            db.execSQL(
                """INSERT INTO session_records
                   (sessionId, platform, startedAt, endedAt, lastEventAt, endSource, startOdometer,
                    lastOdometer, reportedEarnings, reportedDurationMillis, offersReceived,
                    offersAccepted, offersDeclined, offersTimeout, deliveries, jobsCompleted, startSource)
                   VALUES ('S1', 'doordash', 1000, 5000, 5000, 'summary_screen', 100.0, 110.0, 25.0,
                           4000, 1, 1, 0, 0, 1, 1, 'interaction')""",
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 12, true)

        // Pre-existing rows survived (additive-only).
        db.query("SELECT COUNT(*) FROM delivery_records").use { c ->
            c.moveToFirst(); assertEquals(1, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM offer_records").use { c ->
            c.moveToFirst(); assertEquals(1, c.getInt(0))
        }
        // New delivery columns exist; storeKey/payoutStoreForms NULL, storeKeyPinned defaults 0.
        db.query("SELECT storeKey, payoutStoreForms, storeKeyPinned FROM delivery_records WHERE eventSequenceId = 1").use { c ->
            c.moveToFirst()
            assertTrue("storeKey NULL post-migration", c.isNull(0))
            assertTrue("payoutStoreForms NULL post-migration", c.isNull(1))
            assertEquals("storeKeyPinned defaults 0", 0, c.getInt(2))
        }
        // New offer columns exist and are NULL.
        db.query("SELECT storeKey, linkedJobId FROM offer_records WHERE eventSequenceId = 2").use { c ->
            c.moveToFirst()
            assertTrue("offer storeKey NULL post-migration", c.isNull(0))
            assertTrue("linkedJobId NULL post-migration", c.isNull(1))
        }
        // New tables exist and are empty (repopulated by the refold).
        db.query("SELECT COUNT(*) FROM stores").use { c ->
            c.moveToFirst(); assertEquals(0, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM pickup_records").use { c ->
            c.moveToFirst(); assertEquals(0, c.getInt(0))
        }
        db.close()
    }
}
