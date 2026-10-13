package cloud.trotter.dashbuddy.core.data.analytics

import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.domain.analytics.ExpenseCategory
import cloud.trotter.dashbuddy.domain.model.event.AppEvent
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.ExpenseLoggedPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.FuelFillupPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.LedgerEntryVoidedPayload
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions

class LedgerRepositoryTest {
    private val events: AppEventRepo = mock()
    private val repo = LedgerRepository(events, mock())

    @Test
    fun `each ledger write appends exactly one typed event without metadata`() = runTest {
        repo.logExpense("S", ExpenseCategory.PARKING, 4.5, "meter", 100)
        repo.logFuel(null, 30.0, 10.0, 500.0, 200)
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
}
