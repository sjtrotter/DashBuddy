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
 * #810 B2 — execute the REAL committed `AutoMigration(14→15)` against a **populated** v14 database and
 * prove it is additive-only: the pre-existing analytics rows survive, and the one new nullable column
 * (offer_records.outcomeResolved) is present and NULL on existing rows. The `PROJECTOR_VERSION` 7→8
 * refold this bump triggers re-resolves the `JOB_ACCEPT_MISMATCH` events already in the log, so the
 * NULL post-migration value is transient for a cross-store orphan (stamped `UNASSIGNED_INFERRED`) and
 * correct-forever for the same-store residue (left for Tier-2 attestation).
 *
 * Instrumented (androidTest) because [MigrationTestHelper] opens an on-disk SQLite DB via the framework
 * factory and replays the exported schema JSONs. Runs under `:core:database:connectedAndroidTest` — it
 * does NOT gate unit-only PR CI (the unit-level [SchemaVersionGuardTest] is the CI-gating guard); needs
 * one device/emulator run before merge.
 */
@RunWith(AndroidJUnit4::class)
class AnalyticsMigration14to15Test {

    private val dbName = "migration-14-to-15-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DashBuddyDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate14To15_preservesRows_addsOutcomeResolvedColumnNull() {
        helper.createDatabase(dbName, 14).use { db ->
            db.execSQL(
                """INSERT INTO offer_records
                   (eventSequenceId, sessionId, platform, offerHash, outcome, presentedAt, decidedAt,
                    payAmount, distanceMiles, itemCount, merchantName, score, action, quality, estNetPay,
                    estDollarsPerHour, estDollarsPerMile, estTimeMinutes, estOperatingCostPerMile,
                    estFuelPerMile, estNonFuelPerMile, storeKey, linkedJobId)
                   VALUES (1, 'S1', 'doordash', 'h1', 'OFFER_ACCEPTED', 1900, 2000, 12.0, 3.0, 1, 'H-E-B',
                           80.0, 'ACCEPT', 'GOOD', 11.25, 20.0, 4.0, 15.0, 0.25, 0.0, 0.75,
                           'doordash|h-e-b|', NULL)""",
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 15, true)

        // Pre-existing row survived (additive-only).
        db.query("SELECT COUNT(*) FROM offer_records").use { c ->
            c.moveToFirst(); assertEquals(1, c.getInt(0))
        }
        // New outcomeResolved column exists and is NULL on the pre-existing row.
        db.query("SELECT outcomeResolved FROM offer_records WHERE eventSequenceId = 1").use { c ->
            c.moveToFirst()
            assertTrue("outcomeResolved NULL post-migration", c.isNull(0))
        }
        db.close()
    }
}
