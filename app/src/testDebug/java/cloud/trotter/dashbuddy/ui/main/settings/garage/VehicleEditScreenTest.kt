package cloud.trotter.dashbuddy.ui.main.settings.garage

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.location.OdometerRepository
import cloud.trotter.dashbuddy.core.data.vehicle.DashVehicleRepository
import cloud.trotter.dashbuddy.core.data.vehicle.OdometerReadingRepository
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.core.data.vehicle.Garage
import cloud.trotter.dashbuddy.core.data.vehicle.GarageRepository
import cloud.trotter.dashbuddy.core.data.vehicle.VehicleRepository
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
class VehicleEditScreenTest {
    @get:Rule val compose = createComposeRule()
    private val repository: GarageRepository = mock()
    private val dashVehicles: DashVehicleRepository = mock { on { stamps } doReturn flowOf(emptyList()) }
    private val readings: OdometerReadingRepository = mock { on { readings } doReturn flowOf(emptyList()) }
    private val odometer: OdometerRepository = mock { on { totalMilesFlow } doReturn flowOf(null) }
    private var editCosts = false
    private val lock: VehicleSwapLock = mock()
    private val lookup: VehicleRepository = mock()
    private val preferences: AppPreferencesRepository = mock {
        on { userEconomy } doReturn flowOf(UserEconomy())
        on { sharedEconomy } doReturn flowOf(cloud.trotter.dashbuddy.domain.evaluation.SharedEconomy())
        on { vehicleLookupAllowed } doReturn flowOf(false)
    }
    private val vehicle = Vehicle("car", "Original", 1, GasCar(
        wear = WearProfile(tireSetCost = 800.0), fixed = FixedCosts(purchasePrice = 20000.0)))
    private val loaded = CompletableDeferred<Garage>()
    private lateinit var vm: VehicleEditViewModel
    private var done = false

    private fun showEditor(editing: Boolean = true) {
        whenever(repository.garage).thenReturn(flow { emit(loaded.await()) })
        compose.runOnIdle {
            vm = VehicleEditViewModel(SavedStateHandle(if (editing) mapOf(Screen.VehicleEdit.ARG to vehicle.id) else emptyMap()),
                repository, lookup, preferences, Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC), lock, dashVehicles, readings, odometer)
        }
        compose.setContent { DashBuddyTheme { VehicleEditScreen(onDone = { done = true }, onEditCosts = { editCosts = true }, viewModel = vm) } }
    }

    private fun finishLoading(retired: Boolean = false, active: Boolean = true) {
        compose.runOnIdle {
            loaded.complete(Garage(listOf(if (retired) vehicle.copy(retiredAt = 2) else vehicle),
                vehicle.id.takeIf { active && !retired }, true))
        }
        compose.waitUntil { vm.loaded.value && !vm.busy.value && (!vm.isEditing || vm.facts.value != null) }
        compose.waitForIdle()
    }

    @Test fun `details show this car and cost per mile above the form`() {
        showEditor()
        finishLoading()
        compose.onNodeWithText("This car").assertIsDisplayed()
        val cost = compose.onNodeWithText("Cost per mile").fetchSemanticsNode()
        val name = compose.onNodeWithText("Name").fetchSemanticsNode()
        assertTrue(cost.positionInRoot.y < name.positionInRoot.y)
        compose.onNodeWithText(Formats.money(vm.facts.value!!.costPerMile) + " est.").assertIsDisplayed()
        compose.onNodeWithText("Edit costs").performScrollTo().performClick()
        assertTrue(editCosts)
    }

    @Test fun `non active details have no edit costs`() {
        showEditor()
        finishLoading(active = false)
        compose.onNodeWithText("This car").assertIsDisplayed()
        compose.onNodeWithText("Edit costs").assertDoesNotExist()
        compose.onNodeWithText("Name").assertExists()
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

    @Test fun `Add has no retire button`() {
        showEditor(editing = false)
        finishLoading()
        compose.onNodeWithText("Retire vehicle").assertDoesNotExist()
        compose.onNodeWithText("This car").assertDoesNotExist()
    }

    @Test fun `unreadable editor disables retire`() {
        showEditor()
        compose.runOnIdle { loaded.complete(Garage(emptyList(), null, false)) }
        compose.waitUntil { vm.failure.value == GarageWriteFailure.UNREADABLE }
        compose.onNodeWithText("Retire vehicle").performScrollTo().assertIsNotEnabled()
    }

    @Test fun `type taps are inert before load and during save but work after load`() {
        val saved = CompletableDeferred<Boolean>()
        runBlocking {
            whenever(repository.updateVehicle(any(), anyOrNull(), any())).doSuspendableAnswer { saved.await() }
        }
        showEditor()
        compose.onNodeWithText("Bicycle").performScrollTo().assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(VehicleType.CAR, vm.draft.state.value.profile.vehicleType) }
        finishLoading()
        compose.onNodeWithText("Bicycle").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(VehicleType.BICYCLE, vm.draft.state.value.profile.vehicleType) }
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.waitUntil { vm.busy.value }
        compose.onNodeWithText("Car").performScrollTo().assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(VehicleType.BICYCLE, vm.draft.state.value.profile.vehicleType) }
        compose.onNodeWithText("Retire vehicle").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { saved.complete(true) }
        compose.waitUntil { done }
    }

    @Test fun `cost reset note appears only while type differs from stored type`() {
        showEditor()
        finishLoading()
        val note = "Changing the type resets the cost figures to the type's presets. Edit them in Economy."
        compose.onNodeWithText(note).assertDoesNotExist()
        compose.onNodeWithText("Bicycle").performScrollTo().performClick()
        compose.onNodeWithText(note).assertExists()
        compose.onNodeWithText("Car").performScrollTo().performClick()
        compose.onNodeWithText(note).assertDoesNotExist()
    }

    @Test fun `40 code point paste shows capped counter and saves successfully`() {
        runBlocking { whenever(repository.updateVehicle(any(), anyOrNull(), any())).thenReturn(true) }
        showEditor()
        finishLoading()
        compose.onNodeWithText("Name").performScrollTo().performTextReplacement("😀".repeat(40))
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
        compose.onNodeWithText("Bicycle").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Retire vehicle").performScrollTo().assertIsNotEnabled()
    }
}
