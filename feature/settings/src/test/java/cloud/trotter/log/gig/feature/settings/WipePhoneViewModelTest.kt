package cloud.trotter.log.gig.feature.settings

import cloud.trotter.log.gig.core.data.wipe.PhoneWipe
import cloud.trotter.log.gig.core.data.wipe.WipeFlow
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
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** #1579: the dialog is [PhoneWipe]'s state plus the live census disclosure; every button is one event. */
@OptIn(ExperimentalCoroutinesApi::class)
class WipePhoneViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val progress = MutableStateFlow<WipeFlow.State?>(null)
    private val disclosed = MutableStateFlow(false)
    private val wipe: PhoneWipe = mock()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        whenever(wipe.progress).thenReturn(progress)
        whenever(wipe.censusDisclosed).thenReturn(disclosed)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `the dialog follows the flow and the census disclosure live`() = runTest(dispatcher) {
        val vm = WipePhoneViewModel(wipe)
        backgroundScope.launch { vm.dialog.collect {} }
        assertEquals(WipeDialog.CLOSED, vm.dialog.value)

        progress.value = WipeFlow.State.Confirming
        assertEquals(WipeDialog(WipeFlow.State.Confirming, censusDisclosed = false), vm.dialog.value)
        disclosed.value = true // the census turned on while the confirmation is open
        assertEquals(WipeDialog(WipeFlow.State.Confirming, censusDisclosed = true), vm.dialog.value)
    }

    @Test fun `each button sends its own event`() {
        val vm = WipePhoneViewModel(wipe)
        vm.open(); verify(wipe).open()
        vm.confirm(); verify(wipe).confirm()
        vm.cancel(); verify(wipe).cancel()
        vm.deleteNow(); verify(wipe).deleteNow()
        vm.waitForServer(); verify(wipe).waitForServer()
    }
}
