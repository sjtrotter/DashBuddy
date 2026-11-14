package cloud.trotter.log.gig.feature.settings

import cloud.trotter.log.gig.core.data.wipe.PhoneWipe
import cloud.trotter.log.gig.core.data.wipe.WipeProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** #1579: the confirmation is the screen's; once confirmed, [PhoneWipe]'s progress drives the dialog. */
@OptIn(ExperimentalCoroutinesApi::class)
class WipePhoneViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val progress = MutableStateFlow<WipeProgress>(WipeProgress.Idle)
    private val wipe: PhoneWipe = mock()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        whenever(wipe.progress).thenReturn(progress)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun runWith(enrolled: Boolean, block: suspend (WipePhoneViewModel) -> Unit) = runTest(dispatcher) {
        whenever(wipe.censusEnrolled()).thenReturn(enrolled)
        val vm = WipePhoneViewModel(wipe)
        val collector = backgroundScope.launch { vm.dialog.collect {} }
        block(vm)
        collector.cancel()
    }

    @Test fun `the confirmation names the census request only for an enrolled phone`() {
        for (enrolled in listOf(true, false)) runWith(enrolled) { vm ->
            assertEquals(WipeDialog.Closed, vm.dialog.value)
            vm.open()
            assertEquals(WipeDialog.Confirm(census = enrolled), vm.dialog.value)
        }
    }

    @Test fun `Cancel closes the confirmation and starts nothing`() = runWith(enrolled = true) { vm ->
        vm.open()
        vm.dismiss()
        assertEquals(WipeDialog.Closed, vm.dialog.value)
        verify(wipe, never()).start()
    }

    @Test fun `Delete everything starts the wipe and the dialog follows its progress`() = runWith(enrolled = true) { vm ->
        vm.open()
        vm.confirm()
        verify(wipe).start()
        progress.value = WipeProgress.AskingCensus
        assertEquals(WipeDialog.Running(WipeProgress.AskingCensus), vm.dialog.value)
        val pending = WipeProgress.CensusNotConfirmed(WipeProgress.Reason.NOT_YET)
        progress.value = pending
        assertEquals(WipeDialog.Running(pending), vm.dialog.value)

        vm.wipeNow()
        verify(wipe).wipeNow()
        vm.dismiss()
        verify(wipe).dismiss()
    }

    @Test fun `the row does not reopen the confirmation over a wipe under way`() = runWith(enrolled = false) { vm ->
        progress.value = WipeProgress.Wiping(censusConfirmed = false)
        vm.open()
        assertEquals(WipeDialog.Running(WipeProgress.Wiping(censusConfirmed = false)), vm.dialog.value)
        verify(wipe, never()).censusEnrolled()
    }
}
