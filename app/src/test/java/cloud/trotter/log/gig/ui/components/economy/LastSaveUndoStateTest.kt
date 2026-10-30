package cloud.trotter.log.gig.ui.components.economy

import cloud.trotter.log.gig.ui.main.settings.garage.VehicleCostWriter
import cloud.trotter.log.gig.ui.main.settings.garage.WriteResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import androidx.compose.material3.SnackbarHostState
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
    @Test fun `submission invalidates offer immediately and suppresses earlier acknowledgements`() = runTest {
        val writer = VehicleCostWriter(backgroundScope)
        val state = LastSaveUndoState(writer, EditSession(backgroundScope))
        val first = LastSaveUndo.captured("oil", 1, 50.0) { true }
        state.write { first }; runCurrent()
        val release = CompletableDeferred<Unit>()
        state.write { release.await(); LastSaveUndo.captured("oil", 1, 65.0) { true } }
        assertNull(state.lastSave.value)
        val latest = LastSaveUndo.captured("phone", 2, null as Double?) { true }
        state.write { latest }
        release.complete(Unit); runCurrent()
        assertSame(latest, state.lastSave.value)
    }

    @Test fun `queued undo invalidated by submission reports changed with no snackbar action`() = runTest {
        val writer = VehicleCostWriter(backgroundScope)
        val state = LastSaveUndoState(writer, EditSession(backgroundScope))
        var restored = false
        state.write { LastSaveUndo.captured("oil", 1, 50.0) { restored = true; true } }
        runCurrent()
        val undo = state.undo(requireNotNull(state.lastSave.value))
        state.write { LastSaveUndo.captured("oil", 1, 65.0) { true } }
        val snackbar = SnackbarHostState()
        backgroundScope.launch { showUndoResult(undo, snackbar) }
        runCurrent()
        assertFalse(restored)
        assertEquals(WriteResult.CHANGED, undo.await())
        assertEquals("Couldn't undo. The value changed.", snackbar.currentSnackbarData?.visuals?.message)
        assertNull(snackbar.currentSnackbarData?.visuals?.actionLabel)
        assertEquals(cloud.trotter.log.gig.ui.main.settings.garage.GarageWriteFailure.NONE, writer.failure.value)
    }

}
