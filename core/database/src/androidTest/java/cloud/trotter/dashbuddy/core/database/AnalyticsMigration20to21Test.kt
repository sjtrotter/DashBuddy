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

@RunWith(AndroidJUnit4::class)
class AnalyticsMigration20to21Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), DashBuddyDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun preservesReceiptsWithNoVehicle() {
        val name = "migration-20-to-21"
        helper.createDatabase(name, 20).use { db ->
            db.execSQL("""INSERT INTO expense_records
                (eventSequenceId, sessionId, occurredAt, category, amount, note, voidedAt)
                VALUES (1, 'dash', 1000, 'CAR_CARE', 18.4, 'wipers', 3000)""")
            db.execSQL("""INSERT INTO fuel_records
                (eventSequenceId, sessionId, occurredAt, totalAmount, gallons, odometerMiles, voidedAt)
                VALUES (2, 'dash', 2000, 42.0, 11.125, 1234.5, NULL)""")
        }
        helper.runMigrationsAndValidate(name, 21, true).use { db ->
            db.query("SELECT * FROM expense_records").use { c ->
                assertEquals(1, c.count)
                assertTrue(c.moveToFirst())
                assertEquals(1L, c.getLong(c.getColumnIndexOrThrow("eventSequenceId")))
                assertEquals("dash", c.getString(c.getColumnIndexOrThrow("sessionId")))
                assertEquals(1000L, c.getLong(c.getColumnIndexOrThrow("occurredAt")))
                assertEquals("CAR_CARE", c.getString(c.getColumnIndexOrThrow("category")))
                assertEquals(18.4, c.getDouble(c.getColumnIndexOrThrow("amount")), 0.0)
                assertEquals("wipers", c.getString(c.getColumnIndexOrThrow("note")))
                assertEquals(3000L, c.getLong(c.getColumnIndexOrThrow("voidedAt")))
                assertTrue(c.isNull(c.getColumnIndexOrThrow("vehicleId")))
            }
            db.query("SELECT * FROM fuel_records").use { c ->
                assertEquals(1, c.count)
                assertTrue(c.moveToFirst())
                assertEquals(2L, c.getLong(c.getColumnIndexOrThrow("eventSequenceId")))
                assertEquals("dash", c.getString(c.getColumnIndexOrThrow("sessionId")))
                assertEquals(2000L, c.getLong(c.getColumnIndexOrThrow("occurredAt")))
                assertEquals(42.0, c.getDouble(c.getColumnIndexOrThrow("totalAmount")), 0.0)
                assertEquals(11.125, c.getDouble(c.getColumnIndexOrThrow("gallons")), 0.0)
                assertEquals(1234.5, c.getDouble(c.getColumnIndexOrThrow("odometerMiles")), 0.0)
                assertTrue(c.isNull(c.getColumnIndexOrThrow("voidedAt")))
                assertTrue(c.isNull(c.getColumnIndexOrThrow("vehicleId")))
            }
        }
    }
}
