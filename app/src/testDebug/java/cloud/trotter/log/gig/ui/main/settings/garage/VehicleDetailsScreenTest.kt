package cloud.trotter.log.gig.ui.main.settings.garage

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.data.location.OdometerRepository
import cloud.trotter.log.gig.core.data.vehicle.DashVehicleRepository
import cloud.trotter.log.gig.core.data.vehicle.OdometerReadingRepository
import cloud.trotter.log.gig.core.data.vehicle.Garage
import cloud.trotter.log.gig.core.data.vehicle.GarageRepository
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.domain.evaluation.UserEconomy
import cloud.trotter.log.gig.domain.model.vehicle.*
import cloud.trotter.log.gig.state.vehicle.VehicleSwapLock
import cloud.trotter.log.gig.ui.main.navigation.Screen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
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
        on { sharedEconomy } doReturn flowOf(cloud.trotter.log.gig.domain.evaluation.SharedEconomy())
        on { vehicleLookupAllowed } doReturn flowOf(false)
    }
    private val vehicle = Vehicle("car", "Original", 1, GasCar(year = "2020", make = "Honda", model = "Civic",
        wear = WearProfile(tireSetCost = 800.0), fixed = FixedCosts(purchasePrice = 20000.0)))
    private val loaded = CompletableDeferred<Garage>()
    private lateinit var vm: VehicleDetailsViewModel
    private var done = false

    private fun showEditor(updates: MutableStateFlow<Garage>? = null) {
        whenever(repository.garage).thenReturn(updates ?: flow { emit(loaded.await()) })
        compose.runOnIdle {
            vm = VehicleDetailsViewModel(SavedStateHandle(mapOf(Screen.VehicleEdit.ARG to vehicle.id)),
                repository, preferences, Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC), lock, dashVehicles, readings, odometer)
        }
        compose.setContent { GigLogTheme { VehicleDetailsScreen(onDone = { done = true }, onAddVehicle = { addVehicle = true }, viewModel = vm) } }
    }

    private fun finishLoading(retired: Boolean = false, active: Boolean = true, profile: VehicleProfile = vehicle.profile) {
        compose.runOnIdle {
            loaded.complete(Garage(listOf(vehicle.copy(profile = profile, retiredAt = if (retired) 2L else null)),
                vehicle.id.takeIf { active && !retired }, true))
        }
        compose.waitUntil { vm.loaded.value && vm.facts.value != null }
        compose.waitForIdle()
    }

    @Test fun `retired details show facts and restore without the form`() {
        runBlocking { whenever(repository.restore(vehicle.id)).thenReturn(true) }
        showEditor()
        finishLoading(retired = true)
        compose.onNodeWithText("This vehicle").assertIsDisplayed()
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
        compose.onNodeWithText("Retire Original? Its odometer readings are kept, and you can restore it later.").assertIsDisplayed()
        runBlocking { verify(repository, never()).retire(any(), any()) }
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Retire Original? Its odometer readings are kept, and you can restore it later.").assertDoesNotExist()
        compose.onNodeWithText("Retire vehicle").performClick()
        compose.onNodeWithText("Retire").performClick()
        compose.waitForIdle(); compose.waitUntil(5_000) { done }
        runBlocking { verify(repository, times(1)).retire(vehicle.id, 1234) }
    }

    @Test fun `locked active retire shows shared toast and keeps details open`() {
        whenever(lock.locked()).thenReturn(true)
        showEditor()
        finishLoading()
        compose.onNodeWithText("Retire vehicle").performScrollTo().performClick()
        compose.onNodeWithText("Retire").performClick()
        compose.waitForIdle()
        assertEquals("Can't switch vehicles after the first 10 minutes of a shift", ShadowToast.getTextOfLatestToast())
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
        compose.onNodeWithText("This vehicle").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Costs").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Combined MPG").assertExists()
        compose.onNodeWithText("Oil changes").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Phone & data").assertDoesNotExist()
        compose.onNodeWithText("Add vehicle").performScrollTo().performClick()
        compose.waitForIdle()
        compose.waitUntil { addVehicle }
        compose.onNodeWithText("Name").assertIsEnabled()
    }

    @Test fun `failed write shows leave dialog and discard leaves`() {
        runBlocking {
            whenever(repository.updateProfile(any(), any<(VehicleProfile) -> VehicleProfile>()))
                .thenThrow(IllegalStateException("private name"))
        }
        showEditor()
        finishLoading()
        compose.runOnIdle { vm.setOilCost(25.0) }
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
                .thenThrow(IllegalStateException()).thenAnswer { it.getArgument<(VehicleProfile) -> VehicleProfile>(1)(GasCar()); true }
        }
        showEditor()
        finishLoading()
        compose.runOnIdle { vm.setOilCost(25.0) }
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
    @Test fun `odometer follows facts and toggles immediately without navigation`() {
        runBlocking { whenever(repository.updateOdometerShown(eq(vehicle.id), argThat { this(null) == false })).thenReturn(true) }
        showEditor()
        compose.onNodeWithText("Odometer").assertDoesNotExist()
        finishLoading()
        val toggle = compose.onNodeWithText("Odometer")
        toggle.performScrollTo().assertIsEnabled().assertIsOn()
        compose.onNodeWithText("Log odometer readings from the bubble").assertIsDisplayed()
        val fact = compose.onNodeWithText("Cost per mile").fetchSemanticsNode()
        assertTrue(fact.positionInRoot.y < toggle.fetchSemanticsNode().positionInRoot.y)
        toggle.performClick()
        compose.waitForIdle()
        runBlocking {
            verify(repository).updateOdometerShown(eq(vehicle.id), argThat { this(null) == false })
            verify(repository, never()).updateProfile(any(), any<(VehicleProfile) -> VehicleProfile>())
        }
        assertFalse(done)
    }

    @Test fun `retired page has no odometer switch`() {
        showEditor()
        finishLoading(retired = true)
        compose.onNodeWithText("Odometer").assertDoesNotExist()
    }

    @Test fun `retire finishes typed name and cost and awaits acceptance before retirement`() {
        val release = CompletableDeferred<Unit>()
        runBlocking {
            whenever(repository.updateName(eq(vehicle.id), argThat { this("Original") == "New name" })).doSuspendableAnswer { release.await(); true }
            whenever(repository.updateProfile(any(), any<(VehicleProfile) -> VehicleProfile>())).doAnswer { it.getArgument<(VehicleProfile) -> VehicleProfile>(1)(GasCar()); true }
            whenever(repository.retire(vehicle.id, 1234)).thenReturn(true)
        }
        showEditor()
        finishLoading()
        compose.onNodeWithText("Name").performTextReplacement("New name")
        compose.onNodeWithText("Oil changes").performScrollTo().performClick()
        compose.onNodeWithText("Each").performScrollTo().performTextReplacement("65")
        compose.onNodeWithText("Retire vehicle").performScrollTo().performClick()
        compose.onNodeWithText("Retire").performClick()
        compose.waitForIdle()
        assertFalse(done)
        runBlocking { verify(repository, never()).retire(any(), any()) }
        compose.runOnIdle { release.complete(Unit) }
        compose.waitForIdle(); compose.waitUntil(5_000) { done }
        runBlocking {
            inOrder(repository) {
                verify(repository).updateName(eq(vehicle.id), argThat { this("Original") == "New name" })
                verify(repository).updateProfile(eq(vehicle.id), any<(VehicleProfile) -> VehicleProfile>())
                verify(repository).retire(vehicle.id, 1234)
            }
        }
    }

    @Test fun `failed typed name prevents retire and shows unsaved dialog`() {
        runBlocking { whenever(repository.updateName(eq(vehicle.id), argThat { this("Original") == "New name" })).thenThrow(IllegalStateException()) }
        showEditor()
        finishLoading()
        compose.onNodeWithText("Name").performTextReplacement("New name")
        compose.onNodeWithText("Retire vehicle").performScrollTo().performClick()
        compose.onNodeWithText("Retire").performClick()
        compose.onNodeWithText("Some changes were not saved.").assertIsDisplayed()
        runBlocking { verify(repository, never()).retire(any(), any()) }
        assertFalse(done)
    }

    @Test fun `electricity price is read only and directs shared edits to Costs`() {
        showEditor()
        finishLoading(profile = ElectricCar())
        compose.onNodeWithText("Same for every vehicle. Change it on the Costs screen.").performScrollTo().assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction() and hasText("Electricity price")).assertCountEquals(0)
    }

    @Test fun `reset has the vehicle-only confirmation`() {
        showEditor()
        finishLoading()
        compose.onNodeWithText("Reset to estimates").performScrollTo().performClick()
        compose.onNodeWithText("Reset this vehicle's costs to the estimates? Its name, type and fuel economy stay.").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        runBlocking { verify(repository, never()).updateProfile(any(), any<(VehicleProfile) -> VehicleProfile>()) }
    }


    @Test fun `finished oil field offers snackbar undo and restores the shown estimate`() {
        val initial = vehicle.copy(profile = GasCar(wear = WearProfile(oilIntervalMi = 5000.25)))
        val updates = MutableStateFlow(Garage(listOf(initial), vehicle.id, true))
        runBlocking {
            whenever(repository.updateProfile(eq(vehicle.id), any<(VehicleProfile) -> VehicleProfile>())).doAnswer {
                val current = updates.value.vehicles.single()
                val changed = it.getArgument<(VehicleProfile) -> VehicleProfile>(1)(current.profile)
                updates.value = updates.value.copy(vehicles = listOf(current.copy(profile = changed)))
                true
            }
        }
        showEditor(updates)
        compose.waitUntil { vm.economy.value != null }
        compose.onNodeWithText("Oil changes").performScrollTo().performClick()
        val input = compose.onNode(hasSetTextAction() and hasText("Each"))
        input.performScrollTo().performTextReplacement("65")
        compose.onNodeWithText("Oil cost saved").assertDoesNotExist()
        input.performImeAction()
        compose.onNodeWithText("Oil cost saved").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil { updates.value.vehicles.single().profile.wear.oilCost == null }
        input.assertTextContains("60")
        compose.onNodeWithText("Oil cost saved").assertDoesNotExist()
        assertEquals(5000.25, updates.value.vehicles.single().profile.wear.oilIntervalMi)
    }

    @Test fun `name finished field offers Name saved and undo restores displayed name`() {
        val updates = MutableStateFlow(Garage(listOf(vehicle), vehicle.id, true))
        runBlocking {
            whenever(repository.updateName(eq(vehicle.id), any())).doAnswer {
                val current = updates.value.vehicles.single()
                val name = it.getArgument<(String?) -> String?>(1)(current.name)
                updates.value = updates.value.copy(vehicles = listOf(current.copy(name = name)))
                true
            }
        }
        showEditor(updates)
        compose.waitUntil { vm.vehicle.value != null }
        val input = compose.onNode(hasSetTextAction() and hasText("Name"))
        input.performTextReplacement("Renamed")
        input.performImeAction()
        compose.onNodeWithText("Name saved").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil { updates.value.vehicles.single().name == "Original" }
        input.assertTextContains("Original")
        compose.onNodeWithText("Name saved").assertDoesNotExist()
    }

}
