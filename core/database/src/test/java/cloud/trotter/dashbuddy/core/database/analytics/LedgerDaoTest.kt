package cloud.trotter.dashbuddy.core.database.analytics

import androidx.room.Room
import cloud.trotter.dashbuddy.core.database.DashBuddyDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real Room query under Robolectric, like the module's existing SQLite unit tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LedgerDaoTest {
    private lateinit var db: DashBuddyDatabase
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), DashBuddyDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun cleanup() { db.close() }

    @Test fun `recent notes are distinct nonnull surviving notes in latest occurrence order`() = runBlocking {
        val dao = db.ledgerDao()
        suspend fun row(id: Long, at: Long, note: String?, category: String = "CAR_CARE", voided: Long? = null) {
            dao.upsertExpense(ExpenseRecordEntity(id, null, at, category, 1.0, note, voided))
        }
        row(1, 10, "wipers"); row(2, 20, "wash"); row(3, 30, "wipers")
        row(4, 40, "tires", voided = 50); row(5, 50, "bags", category = "SUPPLIES")
        row(6, 60, null)
        row(10, 30, "oil")
        assertEquals(listOf("oil", "wipers", "wash"), dao.recentNotes("CAR_CARE", 6).first())
        assertEquals(listOf("oil", "wipers"), dao.recentNotes("CAR_CARE", 2).first())
        assertEquals(listOf("bags"), dao.recentNotes("SUPPLIES", 6).first())
        assertEquals(emptyList<String>(), dao.recentNotes("OTHER", 6).first())
        dao.voidExpense(3, 100)
        assertEquals(listOf("oil", "wash", "wipers"), dao.recentNotes("CAR_CARE", 6).first())
        dao.voidExpense(1, 101)
        assertEquals(listOf("oil", "wash"), dao.recentNotes("CAR_CARE", 6).first())
        row(11, 110, "wash")
        assertEquals(listOf("wash", "oil"), dao.recentNotes("CAR_CARE", 6).first())
    }
    @Test fun `export reads every projected row in occurrence order with stored attribution and voids`() = runBlocking {
        val dao = db.ledgerDao()
        assertTrue(dao.allExpenses().isEmpty())
        assertTrue(dao.allFuel().isEmpty())
        val expenses = listOf(
            ExpenseRecordEntity(1, "old-dash", 10, "PARKING", 4.0, "meter", 30),
            ExpenseRecordEntity(2, null, 20, "FOOD", 5.0, null, null),
        )
        val fuel = listOf(
            FuelRecordEntity(3, null, 10, 30.0, null, null, 30),
            FuelRecordEntity(4, "old-dash", 20, 40.0, 10.0, 100.0, null),
        )
        expenses.reversed().forEach { dao.upsertExpense(it) }
        fuel.reversed().forEach { dao.upsertFuel(it) }
        assertEquals(expenses, dao.allExpenses())
        assertEquals(fuel, dao.allFuel())
    }

    @Test fun `unassigned queries include start exclude end and retain voids in occurrence order`() = runBlocking {
        val dao = db.ledgerDao()
        val times = listOf(99L, 100L, 199L, 200L)
        times.reversed().forEach { at ->
            dao.upsertExpense(ExpenseRecordEntity(at, null, at, "PARKING", 4.0, null, if (at == 199L) 210 else null))
            dao.upsertFuel(FuelRecordEntity(at, null, at, 30.0, 10.0, null, if (at == 199L) 210 else null))
        }
        dao.upsertExpense(ExpenseRecordEntity(300, "dash", 150, "FOOD", 5.0, null, null))
        dao.upsertFuel(FuelRecordEntity(300, "dash", 150, 40.0, 10.0, null, null))
        // Same timestamp is ordered by sequence id, independent of insertion order.
        dao.upsertExpense(ExpenseRecordEntity(1, null, 100, "FOOD", 5.0, null, null))
        dao.upsertFuel(FuelRecordEntity(1, null, 100, 40.0, 10.0, null, null))
        val expenses = dao.expensesUnassignedBetween(100, 200).first()
        val fuel = dao.fuelUnassignedBetween(100, 200).first()
        assertEquals(listOf(1L, 100L, 199L), expenses.map { it.eventSequenceId })
        assertEquals(listOf(1L, 100L, 199L), fuel.map { it.eventSequenceId })
        assertEquals(210L, expenses.last().voidedAt)
        assertEquals(210L, fuel.last().voidedAt)
        assertTrue(dao.expensesUnassignedBetween(200, 200).first().isEmpty())
        assertTrue(dao.fuelUnassignedBetween(200, 200).first().isEmpty())
    }

}
