package cloud.trotter.dashbuddy.ui.bubble

import cloud.trotter.dashbuddy.core.data.analytics.LedgerRepository
import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.domain.analytics.ExpenseCategory
import cloud.trotter.dashbuddy.domain.model.event.AppEvent
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.*
import cloud.trotter.dashbuddy.feature.bubble.hud.expense.ExpenseFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseActionsTest {
    private val events: AppEventRepo = mock { on { appendUserEvent(any(), anyOrNull()) } doReturn 71L }
    private val clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC)
    private fun actions(scope: CoroutineScope) = ExpenseActions(scope, LedgerRepository(events, mock(), mock()), clock)
    private suspend fun appended(): List<AppEvent> {
        val captor = argumentCaptor<AppEvent>()
        verify(events, atLeastOnce()).appendUserEvent(captor.capture(), anyOrNull())
        return captor.allValues
    }
    @Test fun `expense trims before capping the durable note at forty code points`() = runTest {
        val actions = actions(backgroundScope)
        val labels = listOf("x".repeat(40), "🚗".repeat(40), "x".repeat(39) + "🚗")
        for (label in labels) {
            actions.logExpense(null, ExpenseCategory.CAR_CARE, 100, "  $label extra  ")
            runCurrent()
            assertEquals(label, (appended().last().payload as ExpenseLoggedPayload).note)
            assertEquals(label, actions.undo.value!!.strip.where)
        }
    }
    @Test fun `expense writes exact cents and existing note then arms durable undo`() = runTest {
        val actions = actions(backgroundScope)
        actions.logExpense("dash", ExpenseCategory.CAR_CARE, 1840, "wipers", 12); runCurrent()
        val event = appended().single()
        assertEquals(AppEventType.EXPENSE_LOGGED, event.type)
        assertEquals("dash", event.sessionId); assertEquals(100L, event.occurredAt)
        assertEquals(ExpenseLoggedPayload("dash", ExpenseCategory.CAR_CARE, 18.40, "wipers"), event.payload)
        val commit = actions.undo.value!!
        assertEquals(71L, commit.strip.committedSequenceId); assertEquals(1840L, commit.strip.amountCents)
        assertEquals("wipers", commit.strip.where); assertNull(commit.strip.whereFallback)
        assertEquals(10_100L, commit.strip.deadline); assertEquals(12L, commit.entryId)
        assertEquals(ExpenseFailure.NONE, actions.failed.value)
    }
    @Test fun `fuel writes gallons and GPS stamp as a fillup never an expense`() = runTest {
        val actions = actions(backgroundScope)
        actions.logFuel("dash", 4200, "11.125", 1234.567); runCurrent()
        assertEquals(AppEventType.FUEL_FILLUP, appended().single().type)
        assertEquals(FuelFillupPayload("dash", 42.0, 11.125, 1234.567), appended().single().payload)
        assertNull(actions.undo.value!!.strip.where)
    }
    @Test fun `fuel permits unloaded GPS and unassigned owner but requires positive gallons`() = runTest {
        val actions = actions(backgroundScope)
        for (text in listOf("", "0", "0.000", "-1", "1e3", "100000", "1.1234")) {
            actions.logFuel(null, 100, text, null)
        }
        actions.logFuel(null, 0, "1", null)
        actions.logExpense(null, ExpenseCategory.FOOD, 0, null)
        verifyNoInteractions(events)
        actions.logFuel(null, 10, "0.001", null); runCurrent()
        assertEquals(FuelFillupPayload(null, 0.1, 0.001, null), appended().single().payload)
    }
    @Test fun `undo appends exactly one void targeting committed sequence`() = runTest {
        val actions = actions(backgroundScope)
        actions.logExpense(null, ExpenseCategory.SUPPLIES, 80, "bags"); runCurrent()
        val commit = actions.undo.value!!
        doSuspendableAnswer { delay(1); 72L }.whenever(events).appendUserEvent(any(), anyOrNull())
        actions.undo(commit); actions.undo(commit)
        assertTrue(actions.undo.value!!.strip.busy)
        advanceTimeBy(1); runCurrent()
        assertNull(actions.undo.value)
        assertEquals(listOf(AppEventType.EXPENSE_LOGGED, AppEventType.LEDGER_ENTRY_VOIDED), appended().map { it.type })
        assertEquals(LedgerEntryVoidedPayload(71), appended().last().payload)
    }
    @Test fun `failed append sets WRITE without a commit and can be retried`() = runTest {
        whenever(events.appendUserEvent(any(), anyOrNull())).thenThrow(IllegalStateException("private label"))
            .thenReturn(71)
        val actions = actions(backgroundScope)
        actions.logExpense(null, ExpenseCategory.OTHER, 100, "private label"); runCurrent()
        assertEquals(ExpenseFailure.WRITE, actions.failed.value)
        assertFalse(actions.busy.value); assertNull(actions.undo.value)
        assertTrue(actions.committed.replayCache.isEmpty())
        actions.logExpense(null, ExpenseCategory.OTHER, 100, "private label"); runCurrent()
        assertEquals(ExpenseFailure.NONE, actions.failed.value)
        assertNotNull(actions.undo.value)
        actions.committedHandled(); assertTrue(actions.committed.replayCache.isEmpty())
    }
    @Test fun `busy suppresses duplicate and append finishes through cancellation`() = runTest {
        doSuspendableAnswer { delay(100); 71L }.whenever(events).appendUserEvent(any(), anyOrNull())
        val scope = CoroutineScope(coroutineContext + Job())
        val actions = actions(scope)
        actions.logFuel("dash", 4200, "11.125", 80.0)
        actions.logFuel("dash", 4200, "11.125", 80.0)
        assertTrue(actions.busy.value)
        scope.cancel(); advanceUntilIdle()
        assertEquals(1, appended().size); assertFalse(actions.busy.value)
        assertNotNull(actions.undo.value)
    }
    @Test fun `failed undo stays retryable and expiry cannot clear a newer commit`() = runTest {
        whenever(events.appendUserEvent(any(), anyOrNull())).thenReturn(71)
            .thenThrow(IllegalStateException()).thenReturn(72).thenReturn(73)
        val actions = actions(backgroundScope)
        actions.logExpense("dash", ExpenseCategory.FOOD, 600, null); runCurrent()
        val old = actions.undo.value!!
        actions.undo(old); runCurrent()
        val retry = actions.undo.value!!
        assertTrue(retry.strip.failed); assertEquals(ExpenseFailure.NONE, actions.failed.value)
        actions.undoExpired(retry); assertEquals(retry, actions.undo.value)
        actions.undo(retry); runCurrent(); assertNull(actions.undo.value)
        actions.logExpense("dash", ExpenseCategory.PARKING, 100, null); runCurrent()
        actions.undoExpired(old); assertEquals(73L, actions.undo.value!!.strip.committedSequenceId)
        actions.undo(old); runCurrent(); assertEquals(4, appended().size)
    }
    @Test fun `blank note is omitted and expiry clears only its strip`() = runTest {
        val actions = actions(backgroundScope)
        actions.logExpense(null, ExpenseCategory.OTHER, 10, "  \t"); runCurrent()
        assertNull((appended().single().payload as ExpenseLoggedPayload).note)
        actions.undoExpired(actions.undo.value!!)
        assertNull(actions.undo.value)
    }
}
