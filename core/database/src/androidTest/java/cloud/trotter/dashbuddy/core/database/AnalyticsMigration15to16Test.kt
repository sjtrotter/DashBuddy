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
 * #1033 layer 2 — execute the REAL committed `AutoMigration(15→16)` against a **populated** v15
 * database and prove it is additive-only: the pre-existing analytics rows survive, and the one new
 * nullable column (delivery_records.receiptRepricedAt) is present and NULL on existing rows.
 *
 * NULL is correct FOREVER for that history — unlike the #810 v14→v15 case there is no
 * `PROJECTOR_VERSION` bump, because `DELIVERY_RECEIPT_REPRICE` is a new event type that cannot exist
 * in already-folded history; only a fresh drain can produce one.
 *
 * Instrumented (androidTest) because [MigrationTestHelper] opens an on-disk SQLite DB via the
 * framework factory and replays the exported schema JSONs. Runs under
 * `:core:database:connectedAndroidTest` — it does NOT gate unit-only PR CI (the unit-level
 * [SchemaVersionGuardTest] is the CI-gating guard); needs one device/emulator run before merge.
 */
@RunWith(AndroidJUnit4::class)
class AnalyticsMigration15to16Test {

    private val dbName = "migration-15-to-16-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DashBuddyDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate15To16_preservesRows_addsReceiptRepricedAtColumnNull() {
        helper.createDatabase(dbName, 15).use { db ->
            db.execSQL(
                """INSERT INTO delivery_records
                   (eventSequenceId, sessionId, platform, jobId, taskId, storeName, customerHash,
                    addressHash, phaseStartedAt, arrivedAt, completedAt, deadlineMillis, realizedPay,
                    payBasis, tip, basePay, odometerAtCompletion, realizedMiles, realizedMinutes,
                    frozenCostPerMile, frozenFuelPerMile, frozenNonFuelPerMile, netProfit, costBasis,
                    cashTip, originalPayBasis, storeKey, payoutStoreForms, storeKeyPinned,
                    milesToStore, milesToDropoff, sessionAssigned)
                   VALUES (1, 'S1', 'doordash', 'job-1', 'task-1', 'H-E-B', NULL, NULL, 1000, 1500,
                           2000, NULL, 9.25, 'OFFER_PAY', NULL, NULL, 100.0, 4.0, 12.0, 0.25, 0.1,
                           0.15, 8.25, 'OFFER_FROZEN', NULL, 'OFFER_PAY', NULL, NULL, 0, NULL, NULL, 0)""",
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 16, true)

        // Pre-existing row survived (additive-only).
        db.query("SELECT COUNT(*) FROM delivery_records").use { c ->
            c.moveToFirst(); assertEquals(1, c.getInt(0))
        }
        // The row's own money is byte-identical across the migration.
        db.query("SELECT realizedPay, payBasis FROM delivery_records WHERE eventSequenceId = 1").use { c ->
            c.moveToFirst()
            assertEquals(9.25, c.getDouble(0), 0.0001)
            assertEquals("OFFER_PAY", c.getString(1))
        }
        // New receiptRepricedAt column exists and is NULL on the pre-existing row.
        db.query("SELECT receiptRepricedAt FROM delivery_records WHERE eventSequenceId = 1").use { c ->
            c.moveToFirst()
            assertTrue("receiptRepricedAt NULL post-migration", c.isNull(0))
        }
        db.close()
    }
}
