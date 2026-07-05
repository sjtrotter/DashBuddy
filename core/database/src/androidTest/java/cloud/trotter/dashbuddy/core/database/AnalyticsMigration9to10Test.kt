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
 * #659 — execute the REAL committed `AutoMigration(9→10)` against a **populated** v9 database and
 * prove it is additive-only: the pre-existing analytics rows survive and the three new nullable
 * columns exist afterward (frozen fuel/non-fuel split + startSource), left NULL by the ADD COLUMN
 * (the `PROJECTOR_VERSION` 1→2 refold repopulates them from the immutable log).
 *
 * Instrumented (androidTest) because [MigrationTestHelper] opens an on-disk SQLite DB via the
 * framework factory and replays the exported schema JSONs. Runs under
 * `:core:database:connectedAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class AnalyticsMigration9to10Test {

    private val dbName = "migration-9-to-10-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DashBuddyDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate9To10_preservesAnalyticsRows_andAddsSplitColumns() {
        // Create the schema at v9 and seed one row per analytics table (v9 column sets).
        helper.createDatabase(dbName, 9).use { db ->
            db.execSQL(
                """INSERT INTO delivery_records
                   (eventSequenceId, sessionId, platform, jobId, taskId, storeName, customerHash,
                    addressHash, phaseStartedAt, arrivedAt, completedAt, deadlineMillis, realizedPay,
                    payBasis, tip, basePay, odometerAtCompletion, realizedMiles, realizedMinutes,
                    frozenCostPerMile, netProfit, costBasis)
                   VALUES (1, 'S1', 'doordash', 'J1', 'T1', 'StoreX', NULL, NULL, 100, NULL, 3000,
                           NULL, 10.0, 'RECEIPT_TOTAL', NULL, NULL, 105.0, 5.0, 1.0, 0.25, 8.75,
                           'OFFER_FROZEN')""",
            )
            db.execSQL(
                """INSERT INTO session_records
                   (sessionId, platform, startedAt, endedAt, lastEventAt, endSource, startOdometer,
                    lastOdometer, reportedEarnings, reportedDurationMillis, offersReceived,
                    offersAccepted, offersDeclined, offersTimeout, deliveries, jobsCompleted)
                   VALUES ('S1', 'doordash', 1000, 4000, 4000, 'summary_screen', 100.0, 110.0, 10.0,
                           3000, 1, 1, 0, 0, 1, 1)""",
            )
            db.execSQL(
                """INSERT INTO offer_records
                   (eventSequenceId, sessionId, platform, offerHash, outcome, presentedAt, decidedAt,
                    payAmount, distanceMiles, itemCount, merchantName, score, action, quality,
                    estNetPay, estDollarsPerHour, estDollarsPerMile, estTimeMinutes,
                    estOperatingCostPerMile)
                   VALUES (2, 'S1', 'doordash', 'h1', 'OFFER_ACCEPTED', 1970, 2000, 12.0, 3.0, 1,
                           'StoreX', 80.0, 'ACCEPT', 'GOOD', 11.25, 20.0, 3.0, 15.0, 0.25)""",
            )
        }

        // Run the real AutoMigration(9→10) and validate against the exported 10.json schema.
        val db = helper.runMigrationsAndValidate(dbName, 10, true)

        // The pre-existing rows survived (additive-only, not destructive).
        db.query("SELECT COUNT(*) FROM delivery_records").use { c ->
            c.moveToFirst()
            assertEquals("delivery_records preserved across 9→10", 1, c.getInt(0))
        }

        // The new nullable columns exist and are NULL on the migrated rows (repopulated by the refold).
        db.query("SELECT frozenFuelPerMile, frozenNonFuelPerMile FROM delivery_records WHERE eventSequenceId = 1").use { c ->
            c.moveToFirst()
            assertTrue("frozenFuelPerMile is NULL post-migration", c.isNull(0))
            assertTrue("frozenNonFuelPerMile is NULL post-migration", c.isNull(1))
        }
        db.query("SELECT estFuelPerMile, estNonFuelPerMile FROM offer_records WHERE eventSequenceId = 2").use { c ->
            c.moveToFirst()
            assertTrue("estFuelPerMile is NULL post-migration", c.isNull(0))
            assertTrue("estNonFuelPerMile is NULL post-migration", c.isNull(1))
        }
        db.query("SELECT startSource FROM session_records WHERE sessionId = 'S1'").use { c ->
            c.moveToFirst()
            assertTrue("startSource is NULL post-migration", c.isNull(0))
        }
        db.close()
    }
}
