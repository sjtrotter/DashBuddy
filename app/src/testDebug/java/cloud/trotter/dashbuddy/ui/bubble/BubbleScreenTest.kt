package cloud.trotter.dashbuddy.ui.bubble

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cloud.trotter.dashbuddy.core.data.vehicle.Garage
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.model.vehicle.Vehicle
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.core.designsystem.time.LocalNowProvider
import cloud.trotter.dashbuddy.domain.state.*
import cloud.trotter.dashbuddy.feature.bubble.cards.BubbleCards
import cloud.trotter.dashbuddy.feature.bubble.hud.*
import cloud.trotter.dashbuddy.feature.bubble.hud.odometer.OdometerWriteFailure
import cloud.trotter.dashbuddy.ui.main.MainActivity
import cloud.trotter.dashbuddy.ui.main.navigation.Screen
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
// A phone-height window: the sheet frame keeps its label row and field on screen for the clicks below.
@Config(sdk = [35], qualifiers = "w411dp-h914dp")
class BubbleScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000), ZoneOffset.UTC)
    @After fun cleanup() { scope.cancel() }

    private fun model(): BubbleViewModel {
        val vm: BubbleViewModel = mock()
        val tip = CashTipActions(scope, mock(), mock(), clock)
        val expense = ExpenseActions(scope, mock(), clock)
        whenever(vm.cashTipActions).thenReturn(tip)
        whenever(vm.expenseActions).thenReturn(expense)
        whenever(vm.goalActions).thenReturn(GoalActions(scope, mock()))
        whenever(vm.expenseOwner()).thenReturn("dash") // the live dash owns this entry; a null owner logs unassigned and keeps Expense enabled
        whenever(vm.appState).thenReturn(MutableStateFlow(AppState()))
        whenever(vm.focusedPlatform).thenReturn(MutableStateFlow(Platform.DoorDash))
        whenever(vm.focusedRegion).thenReturn(MutableStateFlow(
            PlatformRegion(Platform.DoorDash, Mode.Online, Session("dash", clock.millis()))))
        whenever(vm.sessionMiles).thenReturn(MutableStateFlow(0.0))
        whenever(vm.sessionEarnings).thenReturn(MutableStateFlow(0.0))
        whenever(vm.lastSession).thenReturn(MutableStateFlow(null))
        whenever(vm.sessionNet).thenReturn(MutableStateFlow(null))
        whenever(vm.dailyGoal).thenReturn(MutableStateFlow(null))
        whenever(vm.goalDay).thenReturn(MutableStateFlow(null))
        whenever(vm.goalMark).thenReturn(MutableStateFlow(MarkRead.Loaded(null)))
        whenever(vm.gasPrice).thenReturn(MutableStateFlow(null))
        whenever(vm.isGasPriceAuto).thenReturn(MutableStateFlow(true))
        whenever(vm.isGasPriceRefreshing).thenReturn(MutableStateFlow(false))
        whenever(vm.cards).thenReturn(MutableStateFlow(BubbleCards.Empty))
        whenever(vm.evaluationConfig).thenReturn(MutableStateFlow(null))
        whenever(vm.switchablePlatforms).thenReturn(MutableStateFlow(emptyList()))
        whenever(vm.odometerLoaded).thenReturn(MutableStateFlow(true))
        whenever(vm.odometerPrompts).thenReturn(MutableStateFlow(false))
        whenever(vm.garage).thenReturn(MutableStateFlow(Garage(emptyList(), null, readable = true)))
        whenever(vm.odometerReadings).thenReturn(MutableStateFlow(emptyList()))
        whenever(vm.stamps).thenReturn(MutableStateFlow(emptyList()))
        whenever(vm.dashVehicle).thenReturn(MutableStateFlow(null))
        whenever(vm.odometerNotice).thenReturn(MutableStateFlow(null))
        whenever(vm.odometerSaving).thenReturn(MutableStateFlow(false))
        whenever(vm.odometerWriteFailed).thenReturn(MutableStateFlow(OdometerWriteFailure.NONE))
        whenever(vm.gpsTotalMiles).thenReturn(MutableStateFlow(null))
        whenever(vm.odometerSaveCompleted).thenReturn(MutableSharedFlow())
        whenever(vm.odometerVehicleChanged).thenReturn(MutableSharedFlow())
        whenever(vm.projectionWatermark).thenReturn(flowOf(null))
        whenever(vm.cashTipDeliveries("dash")).thenReturn(flowOf(emptyList()))
        whenever(vm.recentLabels(any())).thenReturn(flowOf(emptyList()))
        whenever(vm.collapse).thenReturn(MutableSharedFlow())
        whenever(vm.gasPriceRefreshFailed).thenReturn(MutableSharedFlow())
        whenever(vm.liveCardShown).thenReturn(MutableStateFlow(false))
        whenever(vm.logData).thenReturn(MutableStateFlow(LogData.Empty))
        whenever(vm.messages).thenReturn(MutableStateFlow(emptyList()))
        return vm
    }

    private fun show(vm: BubbleViewModel = model(), reducedMotion: Boolean = true) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DashBuddyTheme {
                CompositionLocalProvider(LocalNowProvider provides clock, LocalReducedMotion provides reducedMotion) {
                    BubbleScreen(vm)
                }
            }
        }
        settle()
    }

    @Test fun `row swap follows loaded readings and available vehicle alternatives`() {
        val civic = Vehicle("civic", "Civic", 0)
        val bike = Vehicle("bike", "Bike", 1)
        val garage = MutableStateFlow<Garage?>(Garage(listOf(civic, bike), civic.id, readable = true))
        val loaded = MutableStateFlow(false)
        val vm = model()
        whenever(vm.focusedRegion).thenReturn(MutableStateFlow(null))
        whenever(vm.garage).thenReturn(garage)
        whenever(vm.odometerLoaded).thenReturn(loaded)
        show(vm)
        compose.onNodeWithTag("hud.vehicle.row").assertIsDisplayed()
        compose.onNodeWithTag("hud.vehicle.swap").assertDoesNotExist()
        compose.runOnIdle { loaded.value = true }; settle()
        compose.onNodeWithTag("hud.vehicle.swap").assertIsDisplayed()
        compose.runOnIdle { garage.value = Garage(listOf(civic), civic.id, readable = true) }; settle()
        compose.onNodeWithTag("hud.vehicle.swap").assertDoesNotExist()
        compose.runOnIdle {
            garage.value = Garage(listOf(civic, bike.copy(retiredAt = 2)), civic.id, readable = true)
        }; settle()
        compose.onNodeWithTag("hud.vehicle.swap").assertDoesNotExist()
        compose.runOnIdle { garage.value = Garage(listOf(civic), null, readable = true) }; settle()
        compose.onNodeWithText("No vehicle").assertIsDisplayed()
        compose.onNodeWithTag("hud.vehicle.swap").assertIsDisplayed()
        compose.runOnIdle { loaded.value = false }; settle()
        compose.onNodeWithTag("hud.vehicle.swap").assertDoesNotExist()
        compose.runOnIdle {
            loaded.value = true
            garage.value = Garage(emptyList(), null, readable = true)
        }; settle()
        compose.onNodeWithTag("hud.vehicle.swap").assertDoesNotExist()
    }

    /** The sheet host covers the row while a sheet is open, so the swap can only follow a CLOSED sheet;
     *  the route step keeps the host from re-showing that earlier sheet's content. */
    @Test fun `row swap after another sheet was last open shows the switcher, and Back leaves all closed`() {
        val civic = Vehicle("civic", "Civic", 0)
        val vm = model()
        whenever(vm.focusedRegion).thenReturn(MutableStateFlow(null))
        whenever(vm.lastSession).thenReturn(MutableStateFlow(SessionRecord(
            "dash", Platform.DoorDash, clock.millis() - 3_600_000, clock.millis() - 60_000,
            reportedEarnings = 20.0, reportedDurationMillis = null, miles = 5.0,
            deliveries = 1, jobsCompleted = 1, offersReceived = 1, offersAccepted = 1, offersDeclined = 0, offersTimeout = 0,
        )))
        whenever(vm.garage).thenReturn(MutableStateFlow(
            Garage(listOf(civic, Vehicle("bike", "Bike", 1)), civic.id, readable = true)))
        whenever(vm.todayProfit).thenReturn(MutableStateFlow(20.0))
        show(vm)
        for (tag in listOf("hud.dock.CASH_TIP", "hud.dock.EXPENSE", "hud.goal")) {
            compose.onNodeWithTag(tag).performClick(); settle()
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithTag("hud.sheet").assertIsDisplayed()
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }; settle()
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithTag("hud.sheet").assertDoesNotExist()
            compose.onNodeWithTag("hud.vehicle.swap").performClick(); settle()
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithTag("vehicle.bike").assertIsDisplayed()
            compose.onNodeWithTag("goal.sheet").assertDoesNotExist()
            compose.onNodeWithTag("expense.tile.car_care").assertDoesNotExist()
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }; settle()
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithTag("hud.sheet").assertDoesNotExist()
        }
    }

    @Test fun `row selection closes for both the active vehicle and a completed switch`() {
        val civic = Vehicle("civic", "Civic", 0)
        val vm = model()
        val changes = MutableSharedFlow<VehicleChange>(extraBufferCapacity = 1)
        whenever(vm.focusedRegion).thenReturn(MutableStateFlow(null))
        whenever(vm.garage).thenReturn(MutableStateFlow(
            Garage(listOf(civic, Vehicle("bike", "Bike", 1)), civic.id, readable = true)))
        whenever(vm.odometerVehicleChanged).thenReturn(changes)
        show(vm)
        compose.onNodeWithTag("hud.vehicle.swap").performClick(); settle()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("vehicle.civic").performClick(); settle()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("hud.sheet").assertDoesNotExist()
        verify(vm, never()).selectVehicle(any())
        compose.onNodeWithTag("hud.vehicle.swap").performClick(); settle()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("vehicle.bike").performClick(); settle()
        verify(vm).selectVehicle("bike")
        compose.onNodeWithTag("hud.sheet").assertIsDisplayed()
        compose.runOnIdle { assertTrue(changes.tryEmit(VehicleChange.SWITCHED)) }; settle()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("hud.sheet").assertDoesNotExist()
    }

    @Test fun `typed expense chip reopens the actual entry field prefilled`() {
        show()
        compose.onNodeWithTag("hud.dock.EXPENSE").performClick(); settle()
        compose.onNodeWithTag("expense.tile.car_care").performClick(); settle()
        compose.onNodeWithTag("expense.label.new").performClick(); settle()
        compose.onNodeWithTag("expense.label.input").performTextReplacement("wipers")
        compose.onNodeWithTag("expense.label.input").performImeAction()
        settle()
        compose.onNodeWithTag("expense.label.input").assertDoesNotExist()
        // No performScrollTo: it animates a scroll on the frozen test clock and never returns; the chip is in view.
        compose.onNodeWithTag("expense.label.edit").performClick(); settle()
        // The field sits below the fixed frame's fold on the test window; the prefilled text is the point.
        compose.onNodeWithTag("expense.label.input").assertExists().assertTextEquals("wipers")
    }

    @Test fun `cash tip odometer and expense stay closed after Log then Now`() {
        val civic = Vehicle("civic", "Civic", 0)
        val vm = model()
        whenever(vm.garage).thenReturn(MutableStateFlow(Garage(listOf(civic), civic.id, readable = true)))
        show(vm)
        for (kind in listOf(DockKind.CASH_TIP, DockKind.ODOMETER, DockKind.EXPENSE)) {
            compose.onNodeWithTag("hud.dock.$kind").performClick()
            settle()
            compose.onNodeWithTag("hud.sheet").assertIsDisplayed()
            compose.onNodeWithTag("hud.dock.LOG").performClick()
            settle()
            compose.onNodeWithTag("hud.slot.log").assertIsDisplayed()
            compose.onNodeWithTag("hud.sheet").assertDoesNotExist()
            compose.onNodeWithTag("hud.dock.NOW").performClick()
            settle()
            compose.onNodeWithTag("hud.slot.log").assertDoesNotExist()
            compose.onNodeWithTag("hud.sheet").assertDoesNotExist()
        }
    }

    @Test fun `the live dock with no vehicle opens the Garage Add form without a sheet`() {
        val vm = model()
        show(vm)
        compose.onNodeWithTag("hud.dock.ODOMETER").performClick(); settle()
        assertVehicleAddStarted()
        compose.onNodeWithTag("hud.sheet").assertDoesNotExist()
        verify(vm, never()).reopenOdometer()
    }

    @Test fun `the resting row Add and empty garage dock open the same Garage Add form`() {
        val vm = model()
        whenever(vm.focusedRegion).thenReturn(MutableStateFlow(null))
        show(vm)
        for (tag in listOf("hud.vehicle.add", "hud.dock.ODOMETER")) {
            compose.onNodeWithTag(tag).performClick(); settle()
            assertVehicleAddStarted()
            compose.onNodeWithTag("hud.sheet").assertDoesNotExist()
        }
        verify(vm, never()).reopenOdometer()
    }

    @Test fun `the odometer switcher Add opens the Garage Add form and leaves the switcher open`() {
        val civic = Vehicle("civic", "Civic", 0)
        val vm = model()
        whenever(vm.garage).thenReturn(MutableStateFlow(Garage(listOf(civic), civic.id, readable = true)))
        show(vm)
        compose.onNodeWithTag("hud.dock.ODOMETER").performClick(); settle()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithContentDescription("Switch vehicle").performClick(); settle()
        compose.onNodeWithTag("vehicle.add").performClick(); settle()
        assertVehicleAddStarted()
        compose.onNodeWithTag("vehicle.civic").assertIsDisplayed()
        compose.onNodeWithTag("hud.sheet").assertIsDisplayed()
    }

    private fun assertVehicleAddStarted() {
        val intent = shadowOf(compose.activity).nextStartedActivity
        assertNotNull(intent)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(Screen.VehicleAdd.route, intent.getStringExtra(MainActivity.EXTRA_ROUTE))
        assertNull(shadowOf(compose.activity).nextStartedActivity)
    }

    @Test fun `Back during a pending preset write marks the new goal once without celebrating`() {
        val day = LocalDate.now(clock)
        val oldKey = GoalMetMark.key(day, 100.0)
        val newKey = GoalMetMark.key(day, 125.0)
        val stored = MutableStateFlow<Double?>(100.0)
        val mark = MutableStateFlow<MarkRead>(MarkRead.Loaded(oldKey))
        val releaseWrite = CompletableDeferred<Unit>()
        val preferences: AppPreferencesRepository = mock()
        runBlocking {
            doSuspendableAnswer { invocation ->
                releaseWrite.await()
                stored.value = invocation.getArgument<Double?>(0)
            }.whenever(preferences).setDailyKeptGoalDollars(anyOrNull())
        }
        val vm = model()
        val actions = GoalActions(scope, preferences)
        whenever(vm.goalActions).thenReturn(actions)
        whenever(vm.dailyGoal).thenReturn(stored)
        whenever(vm.goalDay).thenReturn(MutableStateFlow(GoalDay(day, 200.0)))
        whenever(vm.todayProfit).thenReturn(MutableStateFlow(200.0))
        whenever(vm.goalMark).thenReturn(mark)
        doAnswer { invocation ->
            mark.value = MarkRead.Loaded(invocation.getArgument<String>(0))
            Unit
        }.whenever(vm).goalCelebrated(any())

        try {
            // Motion must be enabled: reduced motion makes a celebration look like a silent mark.
            show(vm, reducedMotion = false)
            goalPhase(GoalMetPhase.DONE)
            compose.onNodeWithTag("hud.goal").performClick(); settle()
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithTag("goal.preset.125").performClick(); settle()
            compose.runOnIdle {
                assertTrue(actions.busy.value)
                assertEquals(100.0, stored.value)
                verify(vm, never()).goalCelebrated(any())
                compose.activity.onBackPressedDispatcher.onBackPressed()
            }
            settle()
            // Let the closing frame and sheet exit finish before publishing the new preference.
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithTag("hud.sheet").assertDoesNotExist()
            compose.runOnIdle {
                assertTrue(actions.busy.value)
                assertEquals(100.0, stored.value)
                assertEquals(MarkRead.Loaded(oldKey), mark.value)
                verify(vm, never()).goalCelebrated(any())
                releaseWrite.complete(Unit)
            }
            settle()
            // Any celebration key reaching GoalPill starts CLOSE here, even if its mark echoes
            // immediately. DONE throughout distinguishes the host's silent request from that path.
            goalPhase(GoalMetPhase.DONE)
            compose.runOnIdle {
                assertFalse(actions.busy.value)
                assertEquals(125.0, stored.value)
                assertEquals(MarkRead.Loaded(newKey), mark.value)
                verify(vm, times(1)).goalCelebrated(newKey)
                verify(vm, times(1)).goalCelebrated(any())
            }
            repeat(12) {
                compose.mainClock.advanceTimeBy(100)
                goalPhase(GoalMetPhase.DONE)
            }
            compose.runOnIdle { verify(vm, times(1)).goalCelebrated(any()) }
            runBlocking { verify(preferences, times(1)).setDailyKeptGoalDollars(125.0) }
        } finally {
            // EntryWriter deliberately keeps writes alive across scope cancellation.
            releaseWrite.complete(Unit)
        }
    }

    @Test fun `Back without a pending write allows a later closed sheet crossing to celebrate once`() {
        val day = LocalDate.now(clock)
        val key = GoalMetMark.key(day, 125.0)
        val goalDay = MutableStateFlow<GoalDay?>(GoalDay(day, 100.0))
        val mark = MutableStateFlow<MarkRead>(MarkRead.Loaded(GoalMetMark.key(day, 100.0)))
        val vm = model()
        whenever(vm.dailyGoal).thenReturn(MutableStateFlow(125.0))
        whenever(vm.goalDay).thenReturn(goalDay)
        whenever(vm.todayProfit).thenReturn(MutableStateFlow(100.0))
        whenever(vm.goalMark).thenReturn(mark)
        doAnswer { invocation ->
            mark.value = MarkRead.Loaded(invocation.getArgument<String>(0))
            Unit
        }.whenever(vm).goalCelebrated(any())

        show(vm, reducedMotion = false)
        compose.onNodeWithTag("hud.goal").performClick(); settle()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("goal.sheet").assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(vm.goalActions.busy.value)
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        settle()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("hud.sheet").assertDoesNotExist()
        compose.runOnIdle {
            assertFalse(vm.goalActions.busy.value)
            verify(vm, never()).goalCelebrated(any())
            goalDay.value = GoalDay(day, 200.0)
        }
        settle()
        goalPhase(GoalMetPhase.COLLAPSE)
        compose.runOnIdle {
            assertEquals(MarkRead.Loaded(key), mark.value)
            verify(vm, times(1)).goalCelebrated(key)
            verify(vm, times(1)).goalCelebrated(any())
        }
        compose.mainClock.advanceTimeBy(GoalMetSequence.DURATION_MS.toLong())
        goalPhase(GoalMetPhase.DONE)
        compose.runOnIdle { goalDay.value = GoalDay(day, 210.0) }
        settle()
        goalPhase(GoalMetPhase.DONE)
        compose.runOnIdle { verify(vm, times(1)).goalCelebrated(any()) }
    }

    private fun goalPhase(expected: GoalMetPhase) = compose.onNodeWithTag("hud.goal")
        .assert(SemanticsMatcher("goal phase is $expected") { node ->
            // The feature module's key is internal; inspect its existing public semantics output.
            node.config.any { (key, value) -> key.name == "GoalMetPhase" && value == expected }
        })

    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(100) }
}
