package cloud.trotter.dashbuddy.core.data.analytics

import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.core.database.event.AppEventDao
import cloud.trotter.dashbuddy.domain.analytics.ExpenseCategory
import cloud.trotter.dashbuddy.domain.model.event.AppEvent
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.ExpenseLoggedPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.FuelFillupPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.LedgerEntryVoidedPayload
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import cloud.trotter.dashbuddy.core.database.analytics.LedgerDao
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions

class LedgerRepositoryTest {
    private val events: AppEventRepo = mock()
    private val repo = LedgerRepository(events, mock(), mock())

    @Test
    fun `each ledger write appends exactly one typed event without metadata`() = runTest {
        whenever(events.appendUserEvent(any(), isNull())).thenReturn(41L, 42L, 43L)
        val expenseId = repo.logExpense("S", ExpenseCategory.PARKING, 4.5, "meter", 100)
        val fuelId = repo.logFuel(null, 30.0, 10.0, 500.0, 200)
        assertEquals(41L, expenseId)
        assertEquals(42L, fuelId)
        repo.void(42, 300)
        val captor = argumentCaptor<AppEvent>()
        verify(events, times(3)).appendUserEvent(captor.capture(), isNull())
        assertEquals(listOf(
            AppEvent(AppEventType.EXPENSE_LOGGED, 100, "S", ExpenseLoggedPayload("S", ExpenseCategory.PARKING, 4.5, "meter")),
            AppEvent(AppEventType.FUEL_FILLUP, 200, null, FuelFillupPayload(null, 30.0, 10.0, 500.0)),
            AppEvent(AppEventType.LEDGER_ENTRY_VOIDED, 300, null, LedgerEntryVoidedPayload(42)),
        ), captor.allValues)
    }

    @Test
    fun `invalid amounts and fuel measurements append nothing`() {
        for (amount in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -1.0)) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repo.logExpense(null, ExpenseCategory.OTHER, amount, null, 100) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repo.logFuel(null, amount, null, null, 100) }
            }
        }
        for (gallons in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0)) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repo.logFuel(null, 30.0, gallons, null, 100) }
            }
        }
        for (odometer in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repo.logFuel(null, 30.0, null, odometer, 100) }
            }
        }
        verifyNoInteractions(events)
    }

    @Test
    fun `fuel accepts null measurements and a zero odometer`() = runTest {
        repo.logFuel("S", 30.0, null, null, 100)
        repo.logFuel("S", 30.0, 10.0, 0.0, 200)
        val captor = argumentCaptor<AppEvent>()
        verify(events, times(2)).appendUserEvent(captor.capture(), isNull())
        assertEquals(FuelFillupPayload("S", 30.0), captor.firstValue.payload)
        assertEquals(FuelFillupPayload("S", 30.0, 10.0, 0.0), captor.secondValue.payload)
    }
    @Test
    fun `expense and fuel return the sequence ids assigned by the DAO`() = runTest {
        val dao: AppEventDao = mock()
        whenever(dao.insert(any())).thenReturn(101L, 205L)
        val appEvents = AppEventRepo(mock(), dao, mock())
        val ledger = LedgerRepository(appEvents, mock(), mock())

        assertEquals(101L, ledger.logExpense("S", ExpenseCategory.FOOD, 5.0, "lunch", 100))
        assertEquals(205L, ledger.logFuel("S", 30.0, null, null, 200))
    }

    @Test fun `recent labels use the category name and default six from the projection`() = runTest {
        val dao: LedgerDao = mock()
        val notes = MutableStateFlow(listOf("wipers", "wash"))
        whenever(dao.recentNotes("CAR_CARE", 6)).thenReturn(notes)
        whenever(dao.recentNotes("CAR_CARE", 2)).thenReturn(notes)
        val ledger = LedgerRepository(events, dao, mock())
        assertEquals(listOf("wipers", "wash"), ledger.recentLabels(ExpenseCategory.CAR_CARE).first())
        notes.value = listOf("wash")
        assertEquals(listOf("wash"), ledger.recentLabels(ExpenseCategory.CAR_CARE, 2).first())
        verify(dao).recentNotes("CAR_CARE", 6)
        verify(dao).recentNotes("CAR_CARE", 2)
        verifyNoInteractions(events)
    }
}
