package cloud.trotter.log.gig.domain.analytics

import cloud.trotter.log.gig.domain.model.event.AppEvent
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.EventMetadata
import cloud.trotter.log.gig.domain.model.event.SequencedAppEvent
import cloud.trotter.log.gig.domain.model.event.payload.AppEventPayload
import cloud.trotter.log.gig.domain.model.event.payload.ExpenseLoggedPayload
import cloud.trotter.log.gig.domain.model.event.payload.FuelFillupPayload
import cloud.trotter.log.gig.domain.model.event.payload.LedgerEntryVoidedPayload
import cloud.trotter.log.gig.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CorrectionFoldsTest {
    private val context = SessionFoldContext(
        sessionId = "S", platform = Platform.Unknown, startedAt = 10, lastEventAt = 20,
        startOdometer = 100.0, lastOdometer = 102.0,
        legState = LegState(sessionMeasured = 2.0),
    )

    private fun fold(type: AppEventType, payload: AppEventPayload): FoldOutcome = RecordFolds.foldEvent(
        SequencedAppEvent(
            sequenceId = 42,
            event = AppEvent(type = type, occurredAt = 30, sessionId = "S", payload = payload),
            metadata = EventMetadata(odometer = 999.0),
        ),
        context,
        currentCostPerMile = null,
    )

    @Test fun `expense fold normalizes blank notes to null`() {
        for (note in listOf(null, "", " \t\n\r", "\u2003\u00a0")) {
            val result = fold(AppEventType.EXPENSE_LOGGED, ExpenseLoggedPayload(null, ExpenseCategory.OTHER, 1.0, note))
            assertNull(result.expenseLogged!!.note)
        }
        val result = fold(AppEventType.EXPENSE_LOGGED, ExpenseLoggedPayload(null, ExpenseCategory.OTHER, 1.0, " wipers "))
        assertEquals(" wipers ", result.expenseLogged!!.note)
    }

    @Test
    fun `ledger folds emit payload fields and do not measure odometer stamps`() {
        val expense = fold(AppEventType.EXPENSE_LOGGED, ExpenseLoggedPayload("payload-session", ExpenseCategory.PARKING, 4.5, "meter"))
        val fuel = fold(AppEventType.FUEL_FILLUP, FuelFillupPayload(null, 30.0, 10.0, 500.0))
        val void = fold(AppEventType.LEDGER_ENTRY_VOIDED, LedgerEntryVoidedPayload(12))
        assertEquals(ExpenseLoggedFold(42, "payload-session", 30, ExpenseCategory.PARKING, 4.5, "meter"), expense.expenseLogged)
        assertEquals(FuelFillupFold(42, null, 30, 30.0, 10.0, 500.0), fuel.fuelFillup)
        assertEquals(LedgerVoidFold(12, 30), void.ledgerVoid)
        for (result in listOf(expense, fuel, void)) {
            assertNull(result.context)
            assertNull(result.delivery)
            assertNull(result.skip)
        }
    }

    @Test
    fun `wrong ledger payload classes are counted skips`() {
        val cases = listOf(
            AppEventType.EXPENSE_LOGGED to LedgerEntryVoidedPayload(12),
            AppEventType.FUEL_FILLUP to LedgerEntryVoidedPayload(12),
            AppEventType.LEDGER_ENTRY_VOIDED to ExpenseLoggedPayload(null, ExpenseCategory.OTHER, 1.0),
        )
        for ((type, payload) in cases) {
            val result = fold(type, payload)
            assertNotNull(result.skip)
            assertNull(result.context)
            assertNull(result.expenseLogged)
            assertNull(result.fuelFillup)
            assertNull(result.ledgerVoid)
        }
    }
    @Test fun `receipt folds carry the entry vehicle and older entries have none`() {
        val expense = ExpenseLoggedPayload("S", ExpenseCategory.CAR_CARE, 18.4, "wipers", "other-car")
        val fuel = FuelFillupPayload("S", 42.0, 11.125, 1234.5, "other-car")
        assertEquals("other-car", fold(AppEventType.EXPENSE_LOGGED, expense).expenseLogged!!.vehicleId)
        assertEquals("other-car", fold(AppEventType.FUEL_FILLUP, fuel).fuelFillup!!.vehicleId)
        assertNull(fold(AppEventType.EXPENSE_LOGGED, expense.copy(vehicleId = null)).expenseLogged!!.vehicleId)
        assertNull(fold(AppEventType.FUEL_FILLUP, fuel.copy(vehicleId = null)).fuelFillup!!.vehicleId)
    }

}
