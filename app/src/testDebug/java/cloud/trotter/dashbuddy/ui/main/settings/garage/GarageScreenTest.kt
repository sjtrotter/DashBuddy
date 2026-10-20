package cloud.trotter.dashbuddy.ui.main.settings.garage

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cloud.trotter.dashbuddy.core.data.vehicle.Garage
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.domain.model.vehicle.Vehicle
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GarageScreenTest {
    @get:Rule val compose = createComposeRule()
    private val vm: GarageViewModel = mock()
    private val selected = MutableStateFlow<Garage?>(Garage(
        listOf(Vehicle("active", "Commute", 1), Vehicle("other", "Other", 2)), "active", true,
    ))

    private fun show(onEdit: (String) -> Unit = {}) {
        whenever(vm.garage).thenReturn(selected)
        whenever(vm.busy).thenReturn(MutableStateFlow(false))
        whenever(vm.failure).thenReturn(MutableStateFlow(GarageWriteFailure.NONE))
        ShadowToast.reset()
        compose.setContent {
            DashBuddyTheme { GarageScreen(onBack = {}, onAdd = {}, onEdit = onEdit, viewModel = vm) }
        }
    }

    @Test fun `retired section starts closed expands and opens retired details without selection`() {
        val retired = Vehicle("old", "Old car", 1, retiredAt = 2)
        selected.value = selected.value!!.copy(vehicles = selected.value!!.vehicles + retired)
        val edited = mutableListOf<String>()
        show { edited += it }
        compose.onNodeWithTag("garage.retired.header").assertIsDisplayed()
        compose.onNodeWithText("Retired (1)").assertIsDisplayed()
        compose.onNodeWithTag("garage.retired.old").assertDoesNotExist()
        compose.onNodeWithTag("garage.retired.header").performClick()
        compose.onNodeWithTag("garage.retired.old").assertIsDisplayed().performClick()
        assertEquals(listOf("old"), edited)
        compose.onNodeWithContentDescription("Old car settings").assertDoesNotExist()
        compose.onAllNodes(isSelectable()).assertCountEquals(2)
        verify(vm, never()).select(any())
        verify(vm, never()).swapLocked()
        compose.onNodeWithTag("garage.retired.header").performClick()
        compose.onNodeWithTag("garage.retired.old").assertDoesNotExist()
    }

    @Test fun `locked select shows shared toast and calls no select`() {
        whenever(vm.swapLocked()).thenReturn(true)
        show()
        compose.onNodeWithText("Other").performClick()
        assertEquals("Vehicle can't change during a dash", ShadowToast.getTextOfLatestToast())
        verify(vm, never()).select(any())
    }

    @Test fun `selecting active vehicle calls nothing even while locked`() {
        whenever(vm.swapLocked()).thenReturn(true)
        show()
        compose.onNodeWithText("Commute").performClick()
        verify(vm, never()).select(any())
        verify(vm, never()).swapLocked()
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test fun `unlocked name selects once`() {
        whenever(vm.swapLocked()).thenReturn(false)
        show()
        compose.onNodeWithText("Other").performClick()
        verify(vm, times(1)).select("other")
        verify(vm, times(1)).select(any())
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test fun `gear still opens details while locked without selecting`() {
        whenever(vm.swapLocked()).thenReturn(true)
        val edited = mutableListOf<String>()
        show(onEdit = { edited += it })
        compose.onNodeWithContentDescription("Other settings").performClick()
        assertEquals(listOf("other"), edited)
        verify(vm, never()).select(any())
        verify(vm, never()).swapLocked()
        assertNull(ShadowToast.getTextOfLatestToast())
    }
}
