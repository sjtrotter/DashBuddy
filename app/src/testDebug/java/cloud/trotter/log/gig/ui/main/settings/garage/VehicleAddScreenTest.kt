package cloud.trotter.log.gig.ui.main.settings.garage

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.data.vehicle.*
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.domain.evaluation.SharedEconomy
import cloud.trotter.log.gig.domain.model.vehicle.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VehicleAddScreenTest {
    @get:Rule val compose = createComposeRule()
    private val repository: GarageRepository = mock { on { garage } doReturn flowOf(Garage(emptyList(), null, true)) }
    private val lookup: VehicleRepository = mock()
    private val preferences: AppPreferencesRepository = mock {
        on { sharedEconomy } doReturn flowOf(SharedEconomy())
        on { vehicleLookupAllowed } doReturn flowOf(false)
    }

    @Test fun `Add keeps vehicle form and explicit button with capped name`() {
        runBlocking {
            whenever(repository.addVehicle(anyOrNull(), any(), any(), any())).thenReturn(Vehicle("new", null, 1))
        }
        lateinit var vm: VehicleAddViewModel
        var done = false
        compose.runOnIdle { vm = VehicleAddViewModel(repository, lookup, preferences, Clock.systemUTC()) }
        compose.setContent { GigLogTheme { VehicleAddScreen(onDone = { done = true }, viewModel = vm) } }
        compose.waitUntil { vm.loaded.value && !vm.busy.value }
        compose.onNodeWithText("Retire vehicle").assertDoesNotExist()
        compose.onNodeWithText("This car").assertDoesNotExist()
        compose.onNodeWithText("Name").performTextReplacement("😀".repeat(40))
        compose.onNodeWithText("30 / 30").assertIsDisplayed()
        compose.onNodeWithText("Bicycle").performScrollTo().performClick()
        compose.onNode(hasText("Add vehicle") and hasClickAction()).performScrollTo().performClick()
        compose.waitUntil { done }
        runBlocking {
            verify(repository).addVehicle(eq("😀".repeat(30)), any(), eq(Bicycle()), eq(ActivateOn.IF_NONE_ACTIVE))
            verify(repository, never()).updateVehicle(any(), anyOrNull(), any())
        }
    }
}
