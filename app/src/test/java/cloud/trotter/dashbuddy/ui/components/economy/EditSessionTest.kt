package cloud.trotter.dashbuddy.ui.components.economy

import cloud.trotter.dashbuddy.ui.main.settings.garage.VehicleCostWriter
import cloud.trotter.dashbuddy.ui.main.settings.garage.WriteResult
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditSessionTest {
    @Test fun `focus Done blur and leave without typing submit nothing`() = runTest {
        val session = EditSession(backgroundScope)
        var saves = 0
        session.field("MPG", "30", { nonnegativeNumber(it) != null }) { saves++; CompletableDeferred(WriteResult.SAVED) }
        session.focus("MPG", true)
        session.finish("MPG").await()
        session.focus("MPG", false)
        session.finish("MPG").await()
        assertTrue(session.finishAll())
        assertEquals(0, saves)
    }

    @Test fun `typing does not submit and Done plus blur submit the edited value once`() = runTest {
        val session = EditSession(backgroundScope)
        val values = mutableListOf<String>()
        val acknowledged = CompletableDeferred<WriteResult>()
        val field = session.field("oil", "60", { nonnegativeNumber(it) != null }) { values += it; acknowledged }
        session.edit("oil", "2")
        session.edit("oil", "25")
        assertTrue(values.isEmpty())
        session.finish("oil")
        session.finish("oil")
        assertEquals(listOf("25"), values)
        assertEquals("25", field.text)
        assertEquals("25", field.awaiting)
        acknowledged.complete(WriteResult.SAVED)
        runCurrent()
        assertNull(field.awaiting)
    }

    @Test fun `equal-value acknowledgement releases buffer without a store emission`() = runTest {
        val session = EditSession(backgroundScope)
        val accepted = CompletableDeferred<WriteResult>()
        val save: (String) -> Deferred<WriteResult> = { accepted }
        val field = session.field("cost", "10", { true }, save)
        session.edit("cost", "10.0")
        session.finish("cost")
        session.field("cost", "10", { true }, save)
        assertEquals("10.0", field.text)
        accepted.complete(WriteResult.SAVED)
        runCurrent()
        session.field("cost", "99", { true }, save)
        assertEquals("99", field.text)
        assertFalse(field.edited)
    }

    @Test fun `invalid and empty text retain their error and submit nothing`() = runTest {
        val session = EditSession(backgroundScope)
        var saves = 0
        val field = session.field("cost", "60", { nonnegativeNumber(it) != null }) { saves++; CompletableDeferred(WriteResult.SAVED) }
        for (text in listOf("", "-1", "NaN", "Infinity", "no")) {
            session.edit("cost", text)
            assertFalse(session.finishAll())
            assertTrue(field.error)
            assertEquals(text, field.text)
        }
        assertEquals(0, saves)
    }

    @Test fun `clean focused fields follow outside changes and dirty fields keep their text`() = runTest {
        val session = EditSession(backgroundScope)
        val save: (String) -> Deferred<WriteResult> = { CompletableDeferred(WriteResult.SAVED) }
        val field = session.field("cost", "60", { true }, save)
        session.focus("cost", true)
        session.field("cost", "70", { true }, save)
        assertEquals("70", field.text)
        session.edit("cost", "25.")
        session.field("cost", "90", { true }, save)
        assertEquals("25.", field.text)
    }

    @Test fun `finish all accepts all fields before cancellation and awaits delayed writes`() = runTest {
        val owner = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val writer = VehicleCostWriter(owner)
        val session = EditSession(owner)
        val release = CompletableDeferred<Unit>()
        val saved = mutableListOf<String>()
        for (key in listOf("cost", "name")) {
            session.field(key, "old", { true }) { value -> writer.write { release.await(); saved += "$key=$value"; true } }
            session.edit(key, "new")
        }
        val finish = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { session.finishAll() }
        assertEquals(2, writer.pending.value)
        owner.cancel()
        release.complete(Unit)
        runCurrent()
        assertTrue(finish.await())
        assertEquals(listOf("cost=new", "name=new"), saved)
    }

    @Test fun `discard invalidates old callbacks and acknowledgements`() = runTest {
        val session = EditSession(backgroundScope)
        val release = CompletableDeferred<WriteResult>()
        var writes = 0
        val field = session.field("cost", "60", { true }) { writes++; release }
        session.edit("cost", "25")
        val lateBlur = session.finisher("cost")
        session.finish("cost")
        session.edit("cost", "45")
        session.discardAll()
        lateBlur()
        release.complete(WriteResult.SAVED)
        runCurrent()
        session.field("cost", "80", { true }) { writes++; release }
        assertTrue(session.finishAll())
        assertEquals(1, writes)
        assertEquals("80", field.text)
        assertNull(field.awaiting)
    }

    @Test fun `a failed submission is reported and retry can acknowledge it`() = runTest {
        val session = EditSession(backgroundScope)
        var result = WriteResult.FAILED
        val field = session.field("cost", "60", { true }) { CompletableDeferred(result) }
        session.edit("cost", "25")
        assertFalse(session.finishAll())
        assertTrue(field.edited)
        result = WriteResult.SAVED
        session.retryFailed()
        assertFalse(field.edited)
        assertTrue(session.finishAll())
        session.field("cost", "99", { true }) { CompletableDeferred(WriteResult.SAVED) }
        assertEquals("99", field.text)
    }
}
