package cloud.trotter.dashbuddy.ui.bubble

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.data.analytics.LedgerRepository
import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.domain.analytics.ExpenseCategory
import cloud.trotter.dashbuddy.domain.model.event.AppEvent
import cloud.trotter.dashbuddy.domain.model.event.payload.ExpenseLoggedPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.FuelFillupPayload
import cloud.trotter.dashbuddy.domain.model.vehicle.Vehicle
import cloud.trotter.dashbuddy.feature.bubble.hud.expense.*
import cloud.trotter.dashbuddy.feature.bubble.hud.vehicle.VehicleChipModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp")
class ExpenseHudSheetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC)
    private val civic = Vehicle("civic", "Civic", 0)
    private val bike = Vehicle("bike", "Bike", 1)
    // The dash chip may differ from the active vehicle; receipts default to active.
    private val model = MutableStateFlow<VehicleChipModel?>(VehicleChipModel(bike, listOf(civic, bike), civic.id, false))
    private val vm: BubbleViewModel = mock()
    private val events: AppEventRepo = mock()
    private val entry = ExpenseEntryState()
    private var adds = 0
    @After fun cleanup() { scope.cancel() }

    private fun show(pick: ExpensePick = ExpensePick.Fuel) {
        runBlocking { whenever(events.appendUserEvent(any(), anyOrNull())).thenReturn(71L) }
        whenever(vm.expenseActions).thenReturn(ExpenseActions(scope, LedgerRepository(events, mock(), mock()), clock))
        whenever(vm.vehicleChip).thenReturn(model)
        whenever(vm.gpsTotalMiles).thenReturn(MutableStateFlow(1234.5))
        whenever(vm.expenseOwner()).thenReturn("dash")
        whenever(vm.recentLabels(any())).thenReturn(flowOf(emptyList()))
        whenever(vm.vehicleSwapLocked()).thenReturn(true)
        entry.show(); entry.select(pick)
        entry.digit(4); entry.digit(2); entry.point(); entry.digit(0); entry.digit(0)
        entry.activeField = ExpenseField.GALLONS; entry.digit(1); entry.digit(1)
        entry.activeField = ExpenseField.TOTAL
        val hud = ExpenseHud(vm, entry, closeLog = {}, openVehicleAdd = { adds++ })
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DashBuddyTheme {
                Column(Modifier.size(411.dp, 500.dp)) { hud.Sheet() }
            }
        }
        settle()
    }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(100) }
    private fun pickBike() {
        compose.onNodeWithTag("hud.vehicle.chip").performClick(); settle()
        compose.onNodeWithTag("hud.vehicle.check.civic", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("hud.vehicle.option.bike").performClick(); settle()
    }
    private fun appended(): AppEvent = runBlocking {
        val captor = argumentCaptor<AppEvent>()
        verify(events).appendUserEvent(captor.capture(), anyOrNull())
        captor.firstValue
    }
    private fun log() { compose.onNodeWithTag("expense.log").assertIsDisplayed().performClick(); settle() }
    private fun neverSwitched() {
        verify(vm, never()).selectVehicle(any())
        // Receipt picks do not consult the active-vehicle swap lock either.
        verify(vm, never()).vehicleSwapLocked()
        assertEquals(civic.id, model.value?.activeId)
    }

    @Test fun `gas defaults to active and logs active without a pick`() {
        show()
        compose.onNodeWithTag("hud.vehicle.chip").assertIsDisplayed().assertTextContains("Civic")
        log()
        assertEquals(FuelFillupPayload("dash", 42.0, 11.0, 1234.5, civic.id), appended().payload)
        neverSwitched()
    }
    @Test fun `gas pick changes only this entry and logs the picked vehicle even while swaps are locked`() {
        show(); pickBike()
        compose.onNodeWithTag("hud.vehicle.chip").assertTextContains("Bike")
        compose.onNodeWithTag("hud.vehicle.chip").performClick(); settle()
        compose.onNodeWithTag("hud.vehicle.option.bike").assertIsSelected().performClick(); settle()
        log()
        assertEquals(FuelFillupPayload("dash", 42.0, 11.0, 1234.5, bike.id), appended().payload)
        neverSwitched()
    }
    @Test fun `car care pick logs its vehicle without switching active`() {
        show(ExpensePick.Expense(ExpenseCategory.CAR_CARE)); pickBike(); log()
        assertEquals(ExpenseLoggedPayload("dash", ExpenseCategory.CAR_CARE, 42.0, null, bike.id), appended().payload)
        neverSwitched()
    }
    @Test fun `food has no chip and logs no vehicle even with a stale selection`() {
        show(ExpensePick.Expense(ExpenseCategory.FOOD))
        compose.runOnIdle { entry.vehicleId = bike.id }; settle()
        compose.onNodeWithTag("hud.vehicle.chip").assertDoesNotExist()
        log()
        assertEquals(ExpenseLoggedPayload("dash", ExpenseCategory.FOOD, 42.0), appended().payload)
        neverSwitched()
    }
    @Test fun `retired pick falls back to current active vehicle at Log`() {
        show(); pickBike()
        compose.runOnIdle { model.value = model.value!!.copy(vehicles = listOf(civic)) }; settle()
        compose.onNodeWithTag("hud.vehicle.chip").assertTextContains("Civic")
        log()
        assertEquals(civic.id, (appended().payload as FuelFillupPayload).vehicleId)
        neverSwitched()
    }
    @Test fun `default follows a new active vehicle while the sheet is open`() {
        show()
        compose.runOnIdle { model.value = model.value!!.copy(activeId = bike.id) }; settle()
        compose.onNodeWithTag("hud.vehicle.chip").assertTextContains("Bike")
        log()
        assertEquals(bike.id, (appended().payload as FuelFillupPayload).vehicleId)
        verify(vm, never()).selectVehicle(any())
    }
    @Test fun `unreadable garage shows unavailable chip and logs no vehicle`() {
        model.value = VehicleChipModel(null, emptyList(), null, true)
        show()
        compose.onNodeWithTag("hud.vehicle.chip").assertIsDisplayed().assertHasNoClickAction()
        log()
        assertNull((appended().payload as FuelFillupPayload).vehicleId)
        verify(vm, never()).selectVehicle(any())
    }
    @Test fun `Add opens the existing vehicle form without a selection`() {
        show()
        compose.onNodeWithTag("hud.vehicle.chip").performClick(); settle()
        compose.onNodeWithTag("hud.vehicle.add").performClick(); settle()
        assertEquals(1, adds)
        assertNull(entry.vehicleId)
        verifyNoInteractions(events)
        neverSwitched()
    }
}
