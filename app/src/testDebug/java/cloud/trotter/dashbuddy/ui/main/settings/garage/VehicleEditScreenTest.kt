package cloud.trotter.dashbuddy.ui.main.settings.garage

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.vehicle.Garage
import cloud.trotter.dashbuddy.core.data.vehicle.GarageRepository
import cloud.trotter.dashbuddy.core.data.vehicle.VehicleRepository
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.model.vehicle.*
import cloud.trotter.dashbuddy.ui.main.navigation.Screen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
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
class VehicleEditScreenTest {
    @get:Rule val compose = createComposeRule()
    private val repository: GarageRepository = mock()
    private val lookup: VehicleRepository = mock()
    private val preferences: AppPreferencesRepository = mock {
        on { userEconomy } doReturn flowOf(UserEconomy())
        on { vehicleLookupAllowed } doReturn flowOf(false)
    }
    private val vehicle = Vehicle("car", "Original", 1, GasCar(
        wear = WearProfile(tireSetCost = 800.0), fixed = FixedCosts(purchasePrice = 20000.0)))
    private val loaded = CompletableDeferred<Garage>()
    private lateinit var vm: VehicleEditViewModel
    private var done = false

    private fun showEditor() {
        whenever(repository.garage).thenReturn(flow { emit(loaded.await()) })
        compose.runOnIdle {
            vm = VehicleEditViewModel(SavedStateHandle(mapOf(Screen.VehicleEdit.ARG to vehicle.id)),
                repository, lookup, preferences, Clock.systemUTC())
        }
        compose.setContent { DashBuddyTheme { VehicleEditScreen(onDone = { done = true }, viewModel = vm) } }
    }

    private fun finishLoading() {
        compose.runOnIdle { loaded.complete(Garage(listOf(vehicle), vehicle.id, true)) }
        compose.waitUntil { vm.loaded.value && !vm.busy.value }
        compose.waitForIdle()
    }

    @Test fun `type taps are inert before load and during save but work after load`() {
        val saved = CompletableDeferred<Boolean>()
        runBlocking {
            whenever(repository.updateVehicle(any(), anyOrNull(), any())).doSuspendableAnswer { saved.await() }
        }
        showEditor()
        compose.onNodeWithText("Bicycle").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(VehicleType.CAR, vm.draft.state.value.profile.vehicleType) }
        finishLoading()
        compose.onNodeWithText("Bicycle").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(VehicleType.BICYCLE, vm.draft.state.value.profile.vehicleType) }
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.waitUntil { vm.busy.value }
        compose.onNodeWithText("Car").performScrollTo().assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(VehicleType.BICYCLE, vm.draft.state.value.profile.vehicleType) }
        compose.runOnIdle { saved.complete(true) }
        compose.waitUntil { done }
    }

    @Test fun `cost reset note appears only while type differs from stored type`() {
        showEditor()
        finishLoading()
        val note = "Changing the type resets the cost figures to the type's presets. Edit them in Economy."
        compose.onNodeWithText(note).assertDoesNotExist()
        compose.onNodeWithText("Bicycle").performClick()
        compose.onNodeWithText(note).assertExists()
        compose.onNodeWithText("Car").performClick()
        compose.onNodeWithText(note).assertDoesNotExist()
    }

    @Test fun `40 code point paste shows capped counter and saves successfully`() {
        runBlocking { whenever(repository.updateVehicle(any(), anyOrNull(), any())).thenReturn(true) }
        showEditor()
        finishLoading()
        compose.onNodeWithText("Name").performTextReplacement("😀".repeat(40))
        compose.onNodeWithText("30 / 30").assertIsDisplayed()
        compose.runOnIdle { assertEquals("😀".repeat(30), vm.name.value) }
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.waitUntil { done }
        runBlocking { verify(repository).updateVehicle(vehicle.id, "😀".repeat(30), vehicle.profile) }
        compose.runOnIdle { assertEquals(GarageWriteFailure.NONE, vm.failure.value) }
    }

    @Test fun `stale edit route shows retired copy and disables save`() {
        showEditor()
        compose.runOnIdle { loaded.complete(Garage(emptyList(), null, true)) }
        compose.waitUntil { vm.failure.value == GarageWriteFailure.GONE }
        compose.onNodeWithText("This vehicle was retired.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Bicycle").assertIsNotEnabled()
    }
}
