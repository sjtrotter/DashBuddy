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
 * #688/#703 — execute the REAL committed `AutoMigration(10→11)` against a **populated** v10 database
 * and prove it is additive-only: the pre-existing analytics rows survive and the two new nullable
 * columns exist afterward (delivery_records.cashTip + delivery_records.originalPayBasis), left NULL
 * by the ADD COLUMN (the `PROJECTOR_VERSION` 3→4 refold repopulates originalPayBasis from the
 * immutable log; cashTip stays null in history — no cash events exist).
 *
 * Seeds a `USER_CORRECTED` row specifically: that basis is the #703 wrinkle (a re-priced row whose
 * ORIGINAL fold basis was receipt evidence), and the migration must preserve it intact so the refold
 * can re-stamp its originalPayBasis.
 *
 * Instrumented (androidTest) because [MigrationTestHelper] opens an on-disk SQLite DB via the
 * framework factory and replays the exported schema JSONs. Runs under
 * `:core:database:connectedAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class AnalyticsMigration10to11Test {

    private val dbName = "migration-10-to-11-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DashBuddyDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate10To11_preservesAnalyticsRows_andAddsCashTipAndOriginalPayBasisColumns() {
        // Create the schema at v10 and seed delivery rows (v10 column set) — one plain RECEIPT_TOTAL
        // row and one USER_CORRECTED row (the #703 re-priced shape).
        helper.createDatabase(dbName, 10).use { db ->
            db.execSQL(
                """INSERT INTO delivery_records
                   (eventSequenceId, sessionId, platform, jobId, taskId, storeName, customerHash,
                    addressHash, phaseStartedAt, arrivedAt, completedAt, deadlineMillis, realizedPay,
                    payBasis, tip, basePay, odometerAtCompletion, realizedMiles, realizedMinutes,
                    frozenCostPerMile, frozenFuelPerMile, frozenNonFuelPerMile, netProfit, costBasis)
                   VALUES (1, 'S1', 'doordash', 'J1', 'T1', 'StoreX', NULL, NULL, 100, NULL, 3000,
                           NULL, 10.0, 'RECEIPT_TOTAL', NULL, NULL, 105.0, 5.0, 1.0, 0.25, 0.10, 0.15,
                           8.75, 'OFFER_FROZEN')""",
            )
            db.execSQL(
                """INSERT INTO delivery_records
                   (eventSequenceId, sessionId, platform, jobId, taskId, storeName, customerHash,
                    addressHash, phaseStartedAt, arrivedAt, completedAt, deadlineMillis, realizedPay,
                    payBasis, tip, basePay, odometerAtCompletion, realizedMiles, realizedMinutes,
                    frozenCostPerMile, frozenFuelPerMile, frozenNonFuelPerMile, netProfit, costBasis)
                   VALUES (2, 'S1', 'doordash', 'J2', 'T2', 'StoreY', NULL, NULL, 100, NULL, 4000,
                           NULL, 15.0, 'USER_CORRECTED', NULL, NULL, 110.0, 5.0, 1.0, 0.25, 0.10, 0.15,
                           13.75, 'OFFER_FROZEN')""",
            )
            db.execSQL(
                """INSERT INTO session_records
                   (sessionId, platform, startedAt, endedAt, lastEventAt, endSource, startOdometer,
                    lastOdometer, reportedEarnings, reportedDurationMillis, offersReceived,
                    offersAccepted, offersDeclined, offersTimeout, deliveries, jobsCompleted,
                    startSource)
                   VALUES ('S1', 'doordash', 1000, 5000, 5000, 'summary_screen', 100.0, 110.0, 25.0,
                           4000, 1, 1, 0, 0, 2, 2, 'interaction')""",
            )
        }

        // Run the real AutoMigration(10→11) and validate against the exported 11.json schema.
        val db = helper.runMigrationsAndValidate(dbName, 11, true)

        // The pre-existing rows survived (additive-only, not destructive).
        db.query("SELECT COUNT(*) FROM delivery_records").use { c ->
            c.moveToFirst()
            assertEquals("delivery_records preserved across 10→11", 2, c.getInt(0))
        }
        // Data intact — the USER_CORRECTED row keeps its pay/basis (the #703 refold anchor).
        db.query("SELECT payBasis, realizedPay FROM delivery_records WHERE eventSequenceId = 2").use { c ->
            c.moveToFirst()
            assertEquals("USER_CORRECTED", c.getString(0))
            assertEquals(15.0, c.getDouble(1), 1e-9)
        }

        // The new nullable columns exist and are NULL on the migrated rows (repopulated by the refold).
        db.query("SELECT cashTip, originalPayBasis FROM delivery_records WHERE eventSequenceId = 1").use { c ->
            c.moveToFirst()
            assertTrue("cashTip is NULL post-migration", c.isNull(0))
            assertTrue("originalPayBasis is NULL post-migration", c.isNull(1))
        }
        db.query("SELECT cashTip, originalPayBasis FROM delivery_records WHERE eventSequenceId = 2").use { c ->
            c.moveToFirst()
            assertTrue("cashTip is NULL post-migration", c.isNull(0))
            assertTrue("originalPayBasis is NULL post-migration (refold re-stamps it)", c.isNull(1))
        }
        db.close()
    }
}
