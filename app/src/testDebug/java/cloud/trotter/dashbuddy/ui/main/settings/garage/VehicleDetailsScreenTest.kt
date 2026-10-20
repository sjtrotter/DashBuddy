package cloud.trotter.dashbuddy.ui.main.settings.garage

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.location.OdometerRepository
import cloud.trotter.dashbuddy.core.data.vehicle.DashVehicleRepository
import cloud.trotter.dashbuddy.core.data.vehicle.OdometerReadingRepository
import cloud.trotter.dashbuddy.core.data.vehicle.Garage
import cloud.trotter.dashbuddy.core.data.vehicle.GarageRepository
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.model.vehicle.*
import cloud.trotter.dashbuddy.state.vehicle.VehicleSwapLock
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
import org.robolectric.shadows.ShadowToast
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VehicleDetailsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val repository: GarageRepository = mock()
    private val dashVehicles: DashVehicleRepository = mock { on { stamps } doReturn flowOf(emptyList()) }
    private val readings: OdometerReadingRepository = mock { on { readings } doReturn flowOf(emptyList()) }
    private val odometer: OdometerRepository = mock { on { totalMilesFlow } doReturn flowOf(null) }
    private var addVehicle = false
    private val lock: VehicleSwapLock = mock()
    private val preferences: AppPreferencesRepository = mock {
        on { userEconomy } doReturn flowOf(UserEconomy())
        on { sharedEconomy } doReturn flowOf(cloud.trotter.dashbuddy.domain.evaluation.SharedEconomy())
        on { vehicleLookupAllowed } doReturn flowOf(false)
    }
    private val vehicle = Vehicle("car", "Original", 1, GasCar(year = "2020", make = "Honda", model = "Civic",
        wear = WearProfile(tireSetCost = 800.0), fixed = FixedCosts(purchasePrice = 20000.0)))
    private val loaded = CompletableDeferred<Garage>()
    private lateinit var vm: VehicleDetailsViewModel
    private var done = false

    private fun showEditor() {
        whenever(repository.garage).thenReturn(flow { emit(loaded.await()) })
        compose.runOnIdle {
            vm = VehicleDetailsViewModel(SavedStateHandle(mapOf(Screen.VehicleEdit.ARG to vehicle.id)),
                repository, preferences, Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC), lock, dashVehicles, readings, odometer)
        }
        compose.setContent { DashBuddyTheme { VehicleDetailsScreen(onDone = { done = true }, onAddVehicle = { addVehicle = true }, viewModel = vm) } }
    }

    private fun finishLoading(retired: Boolean = false, active: Boolean = true) {
        compose.runOnIdle {
            loaded.complete(Garage(listOf(if (retired) vehicle.copy(retiredAt = 2) else vehicle),
                vehicle.id.takeIf { active && !retired }, true))
        }
        compose.waitUntil { vm.loaded.value && vm.facts.value != null }
        compose.waitForIdle()
    }

    @Test fun `retired details show facts and restore without the form`() {
        runBlocking { whenever(repository.restore(vehicle.id)).thenReturn(true) }
        showEditor()
        finishLoading(retired = true)
        compose.onNodeWithText("This car").assertIsDisplayed()
        compose.onNodeWithText("Edit costs").assertDoesNotExist()
        compose.onNodeWithText("Name").assertDoesNotExist()
        compose.onNodeWithText("Bicycle").assertDoesNotExist()
        compose.onNodeWithText("Save").assertDoesNotExist()
        compose.onNodeWithText("Retire vehicle").assertDoesNotExist()
        compose.onNodeWithText("Restore vehicle").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil { done }
        runBlocking { verify(repository).restore(vehicle.id) }
    }

    @Test fun `retire is in edit details with cancel and confirmation returning to list`() {
        runBlocking { whenever(repository.retire(vehicle.id, 1234)).thenReturn(true) }
        showEditor()
        compose.onNodeWithText("Retire vehicle").performScrollTo().assertIsNotEnabled()
        finishLoading()
        compose.onNodeWithText("Retire vehicle").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Retire Original? Its readings stay.").assertIsDisplayed()
        runBlocking { verify(repository, never()).retire(any(), any()) }
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Retire Original? Its readings stay.").assertDoesNotExist()
        compose.onNodeWithText("Retire vehicle").performClick()
        compose.onNodeWithText("Retire").performClick()
        compose.waitUntil { done }
        runBlocking { verify(repository, times(1)).retire(vehicle.id, 1234) }
    }

    @Test fun `locked active retire shows shared toast and keeps details open`() {
        whenever(lock.locked()).thenReturn(true)
        showEditor()
        finishLoading()
        compose.onNodeWithText("Retire vehicle").performScrollTo().performClick()
        compose.onNodeWithText("Retire").performClick()
        compose.waitForIdle()
        assertEquals("Vehicle can't change during a dash", ShadowToast.getTextOfLatestToast())
        runBlocking { verify(repository, never()).retire(any(), any()) }
        assertFalse(done)
        compose.onNodeWithText("Retire vehicle").assertIsEnabled()
        compose.runOnIdle { assertEquals(GarageWriteFailure.NONE, vm.failure.value) }
    }

    @Test fun `unreadable editor disables retire`() {
        showEditor()
        compose.runOnIdle { loaded.complete(Garage(emptyList(), null, false)) }
        compose.waitUntil { vm.failure.value == GarageWriteFailure.UNREADABLE }
        compose.onNodeWithText("Retire vehicle").performScrollTo().assertIsNotEnabled()
    }

    @Test fun `heading fixes identity and type and hint opens Add vehicle`() {
        showEditor()
        finishLoading()
        // The name is both the title and the name field's text.
        compose.onAllNodesWithText("Original")[0].assertIsDisplayed()
        compose.onNodeWithText("2020 Honda Civic · Car").assertIsDisplayed()
        compose.onNodeWithText("To drive a different vehicle, add it as a new one.").assertIsDisplayed()
        compose.onNodeWithText("Bicycle").assertDoesNotExist()
        compose.onNodeWithText("Look up my vehicle").assertDoesNotExist()
        compose.onNodeWithText("Year").assertDoesNotExist()
        compose.onNodeWithText("Make").assertDoesNotExist()
        compose.onNodeWithText("Model").assertDoesNotExist()
        compose.onNodeWithText("Save").assertDoesNotExist()
        compose.onNodeWithText("Edit costs").assertDoesNotExist()
        compose.onNodeWithText("This car").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("This car's costs").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Combined MPG").assertExists()
        compose.onNodeWithText("Oil changes").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Phone & data").assertDoesNotExist()
        compose.onNodeWithText("Add vehicle").performScrollTo().performClick()
        compose.waitForIdle()
        compose.waitUntil { addVehicle }
    }

    @Test fun `failed write shows leave dialog and discard leaves`() {
        runBlocking {
            whenever(repository.updateProfile(any(), any<(VehicleProfile) -> VehicleProfile>()))
                .thenThrow(IllegalStateException("private name"))
        }
        showEditor()
        finishLoading()
        compose.runOnIdle { vm.setOil(25.0, 5000.0) }
        compose.waitUntil { vm.failure.value == GarageWriteFailure.RETRY }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Some changes were not saved.").assertIsDisplayed()
        assertFalse(done)
        compose.onNodeWithText("Discard").performClick()
        compose.waitUntil { done }
    }

    @Test fun `leave dialog retry waits then navigates`() {
        runBlocking {
            whenever(repository.updateProfile(any(), any<(VehicleProfile) -> VehicleProfile>()))
                .thenThrow(IllegalStateException()).thenReturn(true)
        }
        showEditor()
        finishLoading()
        compose.runOnIdle { vm.setOil(25.0, 5000.0) }
        compose.waitUntil { vm.failure.value == GarageWriteFailure.RETRY }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNode(hasText("Try again") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil { done }
    }

    @Test fun `stale route shows gone copy and disables edits`() {
        showEditor()
        compose.runOnIdle { loaded.complete(Garage(emptyList(), null, true)) }
        compose.waitUntil { vm.failure.value == GarageWriteFailure.GONE }
        compose.onNodeWithText("This vehicle was retired.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Name").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Add vehicle").assertIsNotEnabled()
        compose.onNodeWithText("Retire vehicle").performScrollTo().assertIsNotEnabled()
    }
}
