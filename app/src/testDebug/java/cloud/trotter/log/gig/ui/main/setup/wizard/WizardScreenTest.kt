package cloud.trotter.log.gig.ui.main.setup.wizard

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.domain.evaluation.EconomyField
import cloud.trotter.log.gig.feature.setup.wizard.WizardViewModel
import cloud.trotter.log.gig.feature.setup.wizard.model.WizardState
import cloud.trotter.log.gig.feature.setup.wizard.model.WizardStep
import cloud.trotter.log.gig.feature.setup.wizard.vehicle.VehicleDraft
import cloud.trotter.log.gig.feature.setup.wizard.vehicle.VehicleDraftState
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
    private fun show(steps: List<WizardStep> = listOf(WizardStep.ECONOMY_COSTS), onExit: () -> Unit = {}) {
        val draft: VehicleDraft = mock()
        whenever(draft.state).thenReturn(MutableStateFlow(VehicleDraftState()))
        whenever(vm.vehicleDraft).thenReturn(draft)
        whenever(vm.state).thenReturn(state)
        whenever(vm.steps).thenReturn(MutableStateFlow(steps))
        whenever(vm.vehicleSaveFailed).thenReturn(MutableSharedFlow())
        whenever(vm.updateOilCost(any())).thenAnswer {
            state.value = state.value.copy(wear = state.value.wear.copy(oilCost = it.getArgument(0)),
                userSetEconomyFields = state.value.userSetEconomyFields + EconomyField.OIL_COST)
        }
        whenever(vm.saveAndFinish(any())).thenAnswer {
            savedCost = state.value.wear.oilCost
            it.getArgument<() -> Unit>(0)()
        }
        compose.setContent { GigLogTheme { WizardScreen(vm, onComplete = {}, onExit = onExit) } }
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

    /** #1447 N5: the title bar's arrow runs system Back's handler — exit on step 1, the previous step after. */
    @Test fun `the back arrow exits on step 1 and goes to the previous step after`() {
        var exits = 0
        show(steps = listOf(WizardStep.GOAL, WizardStep.SHOPPING), onExit = { exits++ })
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        assertEquals(1, exits)

        compose.onNodeWithText("Next").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Step 2 of 2").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Step 1 of 2").assertExists()
        assertEquals(1, exits)
    }
}
