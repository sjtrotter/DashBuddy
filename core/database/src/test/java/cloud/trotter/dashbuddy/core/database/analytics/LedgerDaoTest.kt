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
}
