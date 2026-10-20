package cloud.trotter.dashbuddy.ui.main.setup.wizard

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.domain.evaluation.EconomyField
import cloud.trotter.dashbuddy.feature.setup.wizard.WizardViewModel
import cloud.trotter.dashbuddy.feature.setup.wizard.model.WizardState
import cloud.trotter.dashbuddy.feature.setup.wizard.model.WizardStep
import cloud.trotter.dashbuddy.feature.setup.wizard.vehicle.VehicleDraft
import cloud.trotter.dashbuddy.feature.setup.wizard.vehicle.VehicleDraftState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WizardScreenTest {
    @get:Rule val compose = createComposeRule()
    private val state = MutableStateFlow(WizardState())
    private val vm: WizardViewModel = mock()
    private var savedCost: Double? = null
    private fun show() {
        val draft: VehicleDraft = mock()
        whenever(draft.state).thenReturn(MutableStateFlow(VehicleDraftState()))
        whenever(vm.vehicleDraft).thenReturn(draft)
        whenever(vm.state).thenReturn(state)
        whenever(vm.steps).thenReturn(MutableStateFlow(listOf(WizardStep.ECONOMY_COSTS)))
        whenever(vm.vehicleSaveFailed).thenReturn(MutableSharedFlow())
        whenever(vm.updateOilCost(any())).thenAnswer {
            state.value = state.value.copy(oilCost = it.getArgument(0),
                userSetEconomyFields = state.value.userSetEconomyFields + EconomyField.OIL_COST)
        }
        whenever(vm.saveAndFinish(any())).thenAnswer {
            savedCost = state.value.oilCost
            it.getArgument<() -> Unit>(0)()
        }
        compose.setContent { DashBuddyTheme { WizardScreen(vm, {}) } }
    }
    @Test fun `Finish includes the unfinished focused cost while edits remain staged`() {
        show()
        compose.onNodeWithText("Oil changes").performScrollTo().performClick()
        compose.onNodeWithText("Each").performScrollTo().performTextReplacement("65")
        compose.runOnIdle {
            assertNull(savedCost)
            verify(vm, never()).updateOilCost(any())
            verify(vm, never()).saveAndFinish(any())
        }
        compose.onNodeWithText("Save setup").performClick()
        compose.onNodeWithText("Done").performClick()
        compose.waitUntil { savedCost != null }
        assertEquals(65.0, savedCost)
    }
    @Test fun `Skip with focused text never saves economy`() {
        show()
        compose.onNodeWithText("Oil changes").performScrollTo().performClick()
        compose.onNodeWithText("Each").performScrollTo().performTextReplacement("65")
        compose.onNodeWithText("Skip setup").performClick()
        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()
        verify(vm).skipAndFinish(any())
        verify(vm, never()).saveAndFinish(any())
        verify(vm, never()).updateOilCost(any())
        assertNull(savedCost)
    }
}
