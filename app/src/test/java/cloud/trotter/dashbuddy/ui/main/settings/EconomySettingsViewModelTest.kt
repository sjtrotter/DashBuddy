package cloud.trotter.dashbuddy.ui.main.settings

import cloud.trotter.dashbuddy.core.datastore.settings.StoredPreference
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.strategy.StrategyRepository
import cloud.trotter.dashbuddy.core.data.vehicle.*
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.model.vehicle.*
import cloud.trotter.dashbuddy.ui.main.settings.garage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class EconomySettingsViewModelTest {
    private val preferences: AppPreferencesRepository = mock()
    private val garage: GarageRepository = mock()
    private val strategy: StrategyRepository = mock()
    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        whenever(preferences.userEconomy).thenReturn(flowOf(UserEconomy()))
        whenever(preferences.isGasPriceAuto).thenReturn(flowOf(true))
        whenever(garage.garage).thenReturn(flowOf(Garage(emptyList(), null, true)))
        whenever(strategy.evaluationConfig).thenReturn(MutableStateFlow(null))
    }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun model() = EconomySettingsViewModel(preferences, strategy, garage)

    @Test fun `vehicle row follows outside switches without any writes`() = runTest {
        val a = Vehicle("a", "A", 1)
        val b = Vehicle("b", "B", 2, Bicycle())
        val updates = MutableStateFlow(Garage(listOf(a, b), a.id, true))
        whenever(garage.garage).thenReturn(updates)
        val vm = model()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.activeVehicle.collect {} }
        runCurrent()
        assertEquals(a, vm.activeVehicle.value)
        updates.value = updates.value.copy(activeId = b.id)
        runCurrent()
        assertEquals(b, vm.activeVehicle.value)
        updates.value = updates.value.copy(activeId = null)
        runCurrent()
        assertNull(vm.activeVehicle.value)
        verify(garage, never()).updateProfile(any(), any<(VehicleProfile) -> VehicleProfile>())
    }

    @Test fun `phone total then lines keeps both while the displayed total is stale`() = runTest {
        val release = CompletableDeferred<Unit>()
        var total = 80.0
        var lines = 1
        whenever(preferences.updatePhoneTotal(eq(125.0), any())).doSuspendableAnswer { release.await(); val old = total; total = 125.0; StoredPreference(old, true) }
        whenever(preferences.updatePhoneLines(eq(3), any())).doSuspendableAnswer { val old = lines; lines = 3; StoredPreference(old, true) }
        val vm = model()
        val first = vm.setPhoneTotal(125.0)
        val second = vm.setPhoneLines(3)
        runCurrent()
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        release.complete(Unit)
        runCurrent()
        assertEquals(WriteResult.SAVED, first.await())
        assertEquals(WriteResult.SAVED, second.await())
        assertEquals(125.0, total, 0.0)
        assertEquals(3, lines)
        verify(preferences, never()).updatePhoneShare(any(), any())
    }

    @Test fun `finished field and unfinished leave field survive owner cancellation`() = runTest {
        val release = CompletableDeferred<Unit>()
        val writes = mutableListOf<Double>()
        whenever(preferences.updatePhoneTotal(any(), any())).doSuspendableAnswer {
            release.await(); writes += it.getArgument<Double>(0); StoredPreference(80.0, true)
        }
        whenever(preferences.updateElectricityPrice(any(), any())).doSuspendableAnswer {
            writes += it.getArgument<Double>(0); StoredPreference(0.15, false)
        }
        val vm = model()
        vm.edits.field("total", "80", { true }) { vm.setPhoneTotal(it.toDouble()) }
        vm.edits.field("tariff", "0.15", { true }) { vm.setElectricityPrice(it.toDouble()) }
        vm.edits.edit("total", "125")
        vm.edits.finish("total")
        vm.edits.edit("tariff", "0.25")
        val leave = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { vm.finishAndAwait() }
        runCurrent()
        vm.viewModelScope.cancel()
        release.complete(Unit)
        runCurrent()
        assertEquals(GarageWriteFailure.NONE, leave.await())
        assertEquals(listOf(125.0, .25), writes)
        assertEquals(WriteResult.REJECTED, vm.setPhoneTotal(90.0).await())
    }

    @Test fun `reset discards typed text and runs after accepted writes despite a late blur`() = runTest {
        val release = CompletableDeferred<Unit>()
        var stored = 80.0
        whenever(preferences.updatePhoneTotal(any(), any())).doSuspendableAnswer {
            release.await(); val old = stored; stored = it.getArgument(0); StoredPreference(old, true)
        }
        whenever(preferences.resetSharedEconomyDefaults()).doSuspendableAnswer { stored = 80.0 }
        val vm = model()
        vm.setPhoneTotal(125.0)
        vm.edits.field("total", "80", { true }) { vm.setPhoneTotal(it.toDouble()) }
        vm.edits.edit("total", "999")
        val lateBlur = vm.edits.finisher("total")
        val reset = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { vm.resetDefaults() }
        lateBlur()
        release.complete(Unit)
        runCurrent()
        assertEquals(GarageWriteFailure.NONE, reset.await())
        lateBlur()
        vm.edits.finishAll()
        runCurrent()
        assertEquals(80.0, stored, 0.0)
        verify(preferences, times(1)).updatePhoneTotal(any(), any())
        verify(garage, never()).updateProfile(any(), any<(VehicleProfile) -> VehicleProfile>())
    }

    @Test fun `a failed shared write prevents leave until retry or discard`() = runTest {
        whenever(preferences.updatePhoneTotal(eq(125.0), any())).thenThrow(IllegalStateException())
        val vm = model()
        vm.edits.field("total", "80", { true }) { vm.setPhoneTotal(it.toDouble()) }
        vm.edits.edit("total", "125")
        val leave = async { vm.finishAndAwait() }
        runCurrent()
        assertEquals(GarageWriteFailure.RETRY, leave.await())
        vm.discardChanges()
        assertEquals(GarageWriteFailure.NONE, vm.awaitIdle())
    }

    @Test fun `phone lines undo uses captured two lines and never writes total or share`() = runTest {
        whenever(preferences.updatePhoneLines(eq(3), any())).thenReturn(StoredPreference(2, true))
        whenever(preferences.restorePhoneLines(any(), any(), any())).thenReturn(true)
        val vm = model()
        vm.setPhoneLines(3); runCurrent()
        val undo = requireNotNull(vm.lastSaveUndo.value)
        assertEquals("PHONE_PLAN_LINES", undo.key)
        vm.edits.field("PHONE_PLAN_LINES", "3", { true }) { vm.setPhoneLines(it.toInt()) }
        vm.edits.edit("PHONE_PLAN_LINES", "9")
        val lateBlur = vm.edits.finisher("PHONE_PLAN_LINES")
        val restore = vm.undo(undo)
        lateBlur(); runCurrent()
        assertEquals(WriteResult.SAVED, restore.await())
        verify(preferences).restorePhoneLines(eq(StoredPreference(2, true)), eq(StoredPreference(3, true)), any())
        verify(preferences, never()).updatePhoneTotal(any(), any())
        verify(preferences, never()).updatePhoneShare(any(), any())
        verify(preferences, times(1)).updatePhoneLines(any(), any())
        assertNull(vm.lastSaveUndo.value)
    }

    @Test fun `shared latest offer is replaced and reset and leave suppress queued offers`() = runTest {
        whenever(preferences.updatePhoneLines(any(), any())).thenReturn(StoredPreference(2, true))
        whenever(preferences.updatePhoneTotal(any(), any())).thenReturn(StoredPreference(80.0, true))
        val vm = model()
        vm.setPhoneLines(3); runCurrent()
        val first = requireNotNull(vm.lastSaveUndo.value)
        vm.setPhoneTotal(125.0); runCurrent()
        assertEquals("PHONE_PLAN_TOTAL", vm.lastSaveUndo.value?.key)
        assertEquals(WriteResult.REJECTED, vm.undo(first).await())
        assertEquals(GarageWriteFailure.NONE, vm.resetDefaults())
        assertNull(vm.lastSaveUndo.value)
        val release = CompletableDeferred<Unit>()
        whenever(preferences.updatePhoneLines(eq(4), any())).doSuspendableAnswer { release.await(); StoredPreference(3, true) }
        vm.setPhoneLines(4); runCurrent()
        val leave = async(start = CoroutineStart.UNDISPATCHED) { vm.finishAndAwait() }
        release.complete(Unit); runCurrent()
        assertEquals(GarageWriteFailure.NONE, leave.await())
        assertNull(vm.lastSaveUndo.value)
    }

    @Test fun `phone unset retry writes exactly twice and undo restores the single capture`() = runTest {
        var stored = StoredPreference<Double>(null, false)
        var writes = 0
        var captures = 0
        val release = CompletableDeferred<Unit>()
        whenever(preferences.updatePhoneTotal(eq(100.0), any())).doSuspendableAnswer {
            val capture = it.getArgument<cloud.trotter.dashbuddy.core.datastore.settings.SaveCapture<StoredPreference<Double>>>(1)
            val previous = capture.capture { captures++; stored }
            if (++writes == 1) error("first write failed")
            release.await()
            stored = StoredPreference(100.0, true)
            previous
        }
        whenever(preferences.restorePhoneTotal(any(), any(), any())).doAnswer {
            val current = it.getArgument<() -> Boolean>(2)
            if (current() && stored == it.getArgument<StoredPreference<Double>>(1)) {
                stored = it.getArgument(0); true
            } else false
        }
        val vm = model()
        val field = vm.edits.field("PHONE_PLAN_TOTAL", "80", { true }) { vm.setPhoneTotal(it.toDouble()) }
        vm.edits.edit("PHONE_PLAN_TOTAL", "100")
        val first = vm.edits.finish("PHONE_PLAN_TOTAL")
        runCurrent()
        assertEquals(WriteResult.FAILED, first.await())
        assertEquals(GarageWriteFailure.RETRY, vm.awaitIdle())
        assertTrue(field.edited)
        assertNull(vm.lastSaveUndo.value)
        vm.retry(); runCurrent()
        assertEquals("100", field.text)
        assertTrue(field.edited)
        release.complete(Unit); runCurrent()
        assertEquals(2, writes)
        assertEquals(1, captures)
        assertFalse(field.edited)
        val undo = vm.undo(requireNotNull(vm.lastSaveUndo.value))
        runCurrent()
        assertEquals(WriteResult.SAVED, undo.await())
        assertEquals(StoredPreference<Double>(null, false), stored)
    }

    @Test fun `manual gas undo restores both price and auto and reports changed state`() = runTest {
        val prior = cloud.trotter.dashbuddy.core.datastore.settings.StoredGasPrice(StoredPreference(3.50f, false), StoredPreference(true, false))
        val expected = cloud.trotter.dashbuddy.core.datastore.settings.StoredGasPrice(StoredPreference(3.75f, false), StoredPreference(false, false))
        whenever(preferences.updateGasPriceManual(eq(3.75f), any())).thenReturn(prior)
        whenever(preferences.restoreGasPriceManual(eq(prior), eq(expected), any())).thenReturn(true)
        val vm = model()
        vm.setGasPrice(3.75); runCurrent()
        val undo = vm.undo(requireNotNull(vm.lastSaveUndo.value)); runCurrent()
        assertEquals(WriteResult.SAVED, undo.await())
        verify(preferences).restoreGasPriceManual(eq(prior), eq(expected), any())
        vm.setGasPrice(3.75); runCurrent()
        whenever(preferences.restoreGasPriceManual(any(), any(), any())).thenReturn(false)
        val changed = vm.undo(requireNotNull(vm.lastSaveUndo.value)); runCurrent()
        assertEquals(WriteResult.CHANGED, changed.await())
        assertEquals(GarageWriteFailure.NONE, vm.failure.value)
    }

}
