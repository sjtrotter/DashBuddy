package cloud.trotter.log.gig.ui.main.setup.wizard

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.domain.evaluation.EconomyField
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.feature.setup.wizard.WizardViewModel
import cloud.trotter.log.gig.feature.setup.wizard.model.SaveFailure
import cloud.trotter.log.gig.feature.setup.wizard.model.SaveStatus
import cloud.trotter.log.gig.feature.setup.wizard.model.WizardState
import cloud.trotter.log.gig.feature.setup.wizard.model.WizardStep
import cloud.trotter.log.gig.feature.setup.wizard.vehicle.VehicleDraft
import cloud.trotter.log.gig.feature.setup.wizard.vehicle.VehicleDraftState
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
    private var savedPayout: Float? = null
    private val saveStatus = MutableStateFlow<SaveStatus>(SaveStatus.Idle)
    /** What the mocked save reports once it starts; null leaves it running. */
    private var saveOutcome: SaveStatus? = SaveStatus.Saved
    private fun show(
        steps: List<WizardStep> = listOf(WizardStep.ECONOMY_COSTS),
        onExit: () -> Unit = {},
        onComplete: () -> Unit = {},
    ) {
        val draft: VehicleDraft = mock()
        whenever(draft.state).thenReturn(MutableStateFlow(VehicleDraftState()))
        whenever(vm.vehicleDraft).thenReturn(draft)
        whenever(vm.state).thenReturn(state)
        whenever(vm.steps).thenReturn(MutableStateFlow(steps))
        whenever(vm.saveStatus).thenReturn(saveStatus)
        whenever(vm.updateOilCost(any())).thenAnswer {
            state.value = state.value.copy(wear = state.value.wear.copy(oilCost = it.getArgument(0)),
                userSetEconomyFields = state.value.userSetEconomyFields + EconomyField.OIL_COST)
        }
        whenever(vm.save()).thenAnswer {
            savedCost = state.value.wear.oilCost
            savedPayout = state.value.minPayout
            saveStatus.value = saveOutcome ?: SaveStatus.Saving
        }
        whenever(vm.dismissSaveFailure()).thenAnswer { saveStatus.value = SaveStatus.Idle }
        compose.setContent { GigLogTheme { WizardScreen(vm, onComplete = onComplete, onExit = onExit) } }
    }
    @Test fun `Finish includes the unfinished focused cost while edits remain staged`() {
        show()
        compose.onNodeWithText("Oil changes").performScrollTo().performClick()
        compose.onNodeWithText("Each").performScrollTo().performTextReplacement("65")
        compose.runOnIdle {
            assertNull(savedCost)
            verify(vm, never()).updateOilCost(any())
            verify(vm, never()).save()
        }
        compose.onNodeWithText("Save setup").performClick()
        compose.waitUntil { savedCost != null }
        assertEquals(65.0, savedCost)
    }
    @Test fun `Skip with focused text never saves economy`() {
        show()
        compose.onNodeWithText("Oil changes").performScrollTo().performClick()
        compose.onNodeWithText("Each").performScrollTo().performTextReplacement("65")
        compose.onNodeWithText("Skip setup").performClick()
        compose.onNodeWithText("Setup skipped").assertIsDisplayed()
        compose.onNodeWithText("Setup saved").assertDoesNotExist()
        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()
        verify(vm).skipAndFinish(any())
        verify(vm, never()).save()
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

    /** #1473: nothing says saved while the save runs; the sheet opens on the outcome and Done finishes. */
    @Test fun `saved shows only after the save finishes and Done finishes`() {
        saveOutcome = null
        var completes = 0
        show(steps = listOf(WizardStep.MIN_PAYOUT), onComplete = { completes++ })
        compose.onNodeWithText("Save setup").performClick()
        compose.waitForIdle()
        verify(vm).save()
        compose.onNodeWithText("Saving").assertIsDisplayed()
        compose.onAllNodesWithText("saved", substring = true, ignoreCase = true).assertCountEquals(0)
        compose.onNodeWithText("Skip setup").assertIsNotEnabled()

        saveStatus.value = SaveStatus.Saved
        compose.onNodeWithText("Setup saved").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()
        assertEquals(1, completes)
    }

    /** #1473: a failed save names the failure and offers Retry; "saved" never shows. */
    @Test fun `a failed save shows the failure and Retry and never saved`() {
        saveOutcome = SaveStatus.Failed(SaveFailure.VEHICLE)
        var completes = 0
        show(steps = listOf(WizardStep.MIN_PAYOUT), onComplete = { completes++ })
        compose.onNodeWithText("Save setup").performClick()
        compose.onNodeWithText("Couldn't save your vehicle").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
        compose.onAllNodesWithText("saved", substring = true, ignoreCase = true).assertCountEquals(0)

        saveOutcome = SaveStatus.Saved
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Setup saved").assertIsDisplayed()
        verify(vm, times(2)).save()
        assertEquals(0, completes)
    }

    /** #1473: closing a failure returns to the last step with the dasher's answer still shown. */
    @Test fun `leaving a failure keeps the dasher on the last step with their answer`() {
        saveOutcome = SaveStatus.Failed(SaveFailure.SETTINGS)
        state.value = state.value.copy(minPayout = 7f)
        show(steps = listOf(WizardStep.GOAL, WizardStep.MIN_PAYOUT))
        compose.onNodeWithText("Next").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Save setup").performClick()
        compose.onNodeWithText("Couldn't save setup").assertIsDisplayed()

        compose.onNodeWithText("Back to setup").performClick()
        compose.waitForIdle()
        verify(vm).dismissSaveFailure()
        compose.onNodeWithText("Couldn't save setup").assertDoesNotExist()
        compose.onNodeWithText("Step 2 of 2").assertIsDisplayed()
        compose.onNodeWithText("$7.00").assertIsDisplayed()
        compose.onNodeWithText("Save setup").assertIsEnabled()
    }

    /** #1473: the save writes a snapshot of the answers, so a slider moved while it runs changes nothing. */
    @Config(qualifiers = "w411dp-h1600dp") // the default screen clips the slider under the bottom bar
    @Test fun `an answer cannot change while the save runs`() {
        saveOutcome = null
        whenever(vm.updateMinPayout(any())).thenAnswer {
            state.value = state.value.copy(minPayout = it.getArgument(0))
        }
        show(steps = listOf(WizardStep.MIN_PAYOUT))
        val slider = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
        slider.performTouchInput { click(percentOffset(0.9f, 0.5f)) } // a tap on the track moves the thumb
        compose.waitForIdle()
        val moved = state.value.minPayout
        assertNotEquals(5f, moved) // the slider takes a tap when no save runs
        val shown = Formats.money(moved.toDouble())
        compose.onNodeWithText(shown).assertIsDisplayed()
        val track = slider.fetchSemanticsNode().boundsInRoot

        compose.onNodeWithText("Save setup").performClick()
        compose.onNodeWithText("Saving").assertIsDisplayed()
        // No accessibility action reaches a step input while the save runs; TalkBack reads "Saving" there.
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).assertCountEquals(0)
        compose.onNodeWithContentDescription("Saving").assertExists()
        clearInvocations(vm)
        // The slider has no semantics node now, so tap where its track is.
        compose.onRoot().performTouchInput { click(Offset(track.left + track.width * 0.1f, track.center.y)) }
        compose.waitForIdle()
        verify(vm, never()).updateMinPayout(any())
        assertEquals(moved, state.value.minPayout)

        saveStatus.value = SaveStatus.Saved
        compose.onNodeWithText("Setup saved").assertIsDisplayed()
        assertEquals(moved, savedPayout)
        compose.onNodeWithText(shown).assertExists()
    }
}
