package cloud.trotter.log.gig.ui.bubble

import androidx.compose.runtime.saveable.SaverScope
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import timber.log.Timber

@OptIn(ExperimentalCoroutinesApi::class)
class GoalActionsTest {
    private val preferences: AppPreferencesRepository = mock()
    private fun consume(actions: GoalActions, entry: GoalEntryState) {
        actions.committed.replayCache.forEach(entry::saved)
        actions.committedHandled()
    }

    @Test fun `set writes the Double and clear writes null only after persistence`() = runTest {
        val gate = CompletableDeferred<Unit>()
        doSuspendableAnswer { gate.await() }.whenever(preferences).setDailyGoalDollars(anyOrNull())
        val actions = GoalActions(backgroundScope, preferences)
        val entry = GoalEntryState(true)
        actions.set(150, entry.entryId)
        assertTrue(actions.busy.value); assertTrue(entry.open)
        gate.complete(Unit); runCurrent(); consume(actions, entry)
        verify(preferences).setDailyGoalDollars(150.0)
        assertFalse(entry.open); assertFalse(actions.busy.value)
        entry.show()
        actions.clear(entry.entryId); runCurrent(); consume(actions, entry)
        verify(preferences).setDailyGoalDollars(null)
        assertFalse(entry.open)
        assertFalse(actions.failed.value)
    }

    @Test fun `stepper writes leave the entry open`() = runTest {
        val actions = GoalActions(backgroundScope, preferences)
        val entry = GoalEntryState(true)
        actions.set(155); runCurrent()
        verify(preferences).setDailyGoalDollars(155.0)
        verifyNoMoreInteractions(preferences) // #1443: the earnings goal key only; the old profit goal is never written
        assertTrue(entry.open)
        assertTrue(actions.committed.replayCache.isEmpty())
    }

    @Test fun `one write at a time and persistence finishes after scope cancellation`() = runTest {
        doSuspendableAnswer { delay(100) }.whenever(preferences).setDailyGoalDollars(anyOrNull())
        val scope = CoroutineScope(coroutineContext + Job())
        val actions = GoalActions(scope, preferences)
        val entry = GoalEntryState(true)
        actions.set(100, entry.entryId)
        actions.set(125); actions.clear()
        assertTrue(actions.busy.value)
        scope.cancel(); advanceUntilIdle(); consume(actions, entry)
        verify(preferences, times(1)).setDailyGoalDollars(100.0)
        verifyNoMoreInteractions(preferences)
        assertFalse(actions.busy.value); assertFalse(entry.open)
    }

    @Test fun `failed set and clear retain the sheet and retry clears failure`() = runTest {
        whenever(preferences.setDailyGoalDollars(anyOrNull()))
            .thenThrow(IllegalStateException("private goal 150"))
            .thenThrow(IllegalStateException("private goal 150"))
            .thenReturn(Unit)
        val actions = GoalActions(backgroundScope, preferences)
        val entry = GoalEntryState(true)
        val messages = mutableListOf<Triple<Int, String?, String>>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                messages += Triple(priority, tag, message)
                assertNull(t)
            }
        }
        Timber.plant(tree)
        try {
            actions.set(150, entry.entryId); runCurrent(); consume(actions, entry)
            assertTrue(actions.failed.value); assertTrue(entry.open); assertFalse(actions.busy.value)
            actions.clear(entry.entryId); runCurrent(); consume(actions, entry)
            assertTrue(actions.failed.value); assertTrue(entry.open)
            actions.set(150, entry.entryId); runCurrent(); consume(actions, entry)
            assertFalse(actions.failed.value); assertFalse(entry.open)
            assertEquals(listOf(Triple(5, "Goal", "IllegalStateException"),
                Triple(5, "Goal", "IllegalStateException")), messages)
        } finally { Timber.uproot(tree) }
    }

    @Test fun `out of bounds set is inert and reopen resets failure`() = runTest {
        val actions = GoalActions(backgroundScope, preferences)
        for (dollars in listOf(Int.MIN_VALUE, 0, 4, 1000, Int.MAX_VALUE)) actions.set(dollars)
        verifyNoInteractions(preferences)
        whenever(preferences.setDailyGoalDollars(5.0)).thenThrow(IllegalStateException())
        actions.set(5); runCurrent()
        assertTrue(actions.failed.value)
        actions.reopen(); assertFalse(actions.failed.value)
    }

    @Test fun `second pill open during a preset write preserves its close receipt`() = runTest {
        val gate = CompletableDeferred<Unit>()
        doSuspendableAnswer { gate.await() }.whenever(preferences).setDailyGoalDollars(125.0)
        val actions = GoalActions(backgroundScope, preferences)
        val vm: BubbleViewModel = mock()
        whenever(vm.goalActions).thenReturn(actions)
        val entry = GoalEntryState()
        val hud = GoalHud(vm, entry) {}
        hud.openSheet()
        val id = entry.entryId
        actions.set(125, id)
        hud.openSheet()
        assertEquals(id, entry.entryId)
        assertTrue(actions.busy.value)
        gate.complete(Unit); runCurrent(); consume(actions, entry)
        assertFalse(hud.open)
        assertFalse(actions.busy.value)
        verify(preferences, times(1)).setDailyGoalDollars(125.0)
    }

    @Test fun `second pill open preserves failure until the entry is reopened`() = runTest {
        whenever(preferences.setDailyGoalDollars(125.0)).thenThrow(IllegalStateException())
        val actions = GoalActions(backgroundScope, preferences)
        val vm: BubbleViewModel = mock()
        whenever(vm.goalActions).thenReturn(actions)
        val hud = GoalHud(vm, GoalEntryState()) {}
        hud.openSheet()
        actions.set(125); runCurrent()
        assertTrue(actions.failed.value)
        hud.openSheet()
        assertTrue(actions.failed.value)
        hud.dismiss(); hud.openSheet()
        assertFalse(actions.failed.value)
    }

    @Test fun `slow preset cannot dismiss a newly opened goal entry`() = runTest {
        doSuspendableAnswer { delay(100) }.whenever(preferences).setDailyGoalDollars(100.0)
        val entry = GoalEntryState(true)
        val actions = GoalActions(backgroundScope, preferences)
        actions.set(100, entry.entryId)
        entry.dismiss(); entry.show()
        advanceTimeBy(100); runCurrent(); consume(actions, entry)
        assertTrue(entry.open)
        assertFalse(actions.busy.value)
    }

    @Test fun `successful preset closes the restored entry and receipt is consumed once`() = runTest {
        val gate = CompletableDeferred<Unit>()
        doSuspendableAnswer { gate.await() }.whenever(preferences).setDailyGoalDollars(125.0)
        val entry = GoalEntryState().apply { show() }
        val actions = GoalActions(backgroundScope, preferences)
        actions.set(125, entry.entryId)
        val saved = with(GoalEntryState.Saver) {
            object : SaverScope { override fun canBeSaved(value: Any) = true }.save(entry)
        }!!
        val restored = GoalEntryState.Saver.restore(saved)!!
        assertTrue(restored.open)
        assertTrue(actions.committed.replayCache.isEmpty())
        gate.complete(Unit); runCurrent()
        assertEquals(listOf(restored.entryId), actions.committed.replayCache)
        consume(actions, restored)
        assertFalse(restored.open)
        assertTrue(actions.committed.replayCache.isEmpty())
        restored.show(); consume(actions, restored)
        assertTrue(restored.open)
    }

}
