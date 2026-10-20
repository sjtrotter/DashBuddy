package cloud.trotter.dashbuddy.ui.components.economy

import cloud.trotter.dashbuddy.ui.main.settings.garage.VehicleCostWriter
import cloud.trotter.dashbuddy.ui.main.settings.garage.WriteResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LastSaveUndoStateTest {
    @Test fun `screen disposal clears current offer and suppresses late acknowledgement even on reentry`() = runTest {
        val writer = VehicleCostWriter(backgroundScope)
        val state = LastSaveUndoState(writer, EditSession(backgroundScope))
        val first = LastSaveUndo.captured("oil", 1, null as Double?) { true }
        state.write { first }; runCurrent()
        assertSame(first, state.lastSave.value)
        val release = CompletableDeferred<Unit>()
        val second = state.write { release.await(); LastSaveUndo.captured("insurance", 2, 50.0) { true } }
        runCurrent()
        state.clear()
        assertNull(state.lastSave.value)
        state.resume()
        release.complete(Unit); runCurrent()
        assertEquals(WriteResult.SAVED, second.await())
        assertNull(state.lastSave.value)
        val third = LastSaveUndo.captured("name", 3, "Old") { true }
        state.write { third }; runCurrent()
        assertSame(third, state.lastSave.value)
        assertEquals(WriteResult.REJECTED, state.undo(first).await())
        // A cancelled snackbar's dismissal cannot remove its replacement.
        state.dismiss(first)
        assertSame(third, state.lastSave.value)
    }
}
