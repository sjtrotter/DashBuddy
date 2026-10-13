package cloud.trotter.dashbuddy.domain.analytics

import cloud.trotter.dashbuddy.domain.model.event.AppEvent
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.EventMetadata
import cloud.trotter.dashbuddy.domain.model.event.SequencedAppEvent
import cloud.trotter.dashbuddy.domain.model.event.payload.AppEventPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.ExpenseLoggedPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.FuelFillupPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.LedgerEntryVoidedPayload
import cloud.trotter.dashbuddy.domain.state.Platform
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

    @Test
    fun `ledger folds emit payload fields and do not measure odometer stamps`() {
        val expense = fold(AppEventType.EXPENSE_LOGGED, ExpenseLoggedPayload("payload-session", ExpenseCategory.PARKING, 4.5, "meter"))
        val fuel = fold(AppEventType.FUEL_FILLUP, FuelFillupPayload(null, 30.0, 10.0, 500.0))
        val void = fold(AppEventType.LEDGER_ENTRY_VOIDED, LedgerEntryVoidedPayload(12))
        assertEquals(ExpenseLoggedFold(42, "payload-session", 30, ExpenseCategory.PARKING, 4.5, "meter"), expense.expenseLogged)
        assertEquals(FuelFillupFold(42, null, 30, 30.0, 10.0, 500.0), fuel.fuelFillup)
        assertEquals(LedgerVoidFold(12, 30), void.ledgerVoid)
        for (result in listOf(expense, fuel, void)) {
            assertEquals(context, result.context)
            assertEquals(2.0, result.context!!.legState.sessionMeasured, 0.0)
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
            assertEquals(context, result.context)
            assertNull(result.expenseLogged)
            assertNull(result.fuelFillup)
            assertNull(result.ledgerVoid)
        }
    }
}
