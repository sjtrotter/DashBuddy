package cloud.trotter.dashbuddy.ui.bubble

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.core.designsystem.time.LocalNowProvider
import cloud.trotter.dashbuddy.domain.state.*
import cloud.trotter.dashbuddy.feature.bubble.cards.BubbleCards
import cloud.trotter.dashbuddy.feature.bubble.hud.*
import cloud.trotter.dashbuddy.feature.bubble.hud.odometer.OdometerWriteFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
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
// A phone-height window: the sheet frame keeps its label row and field on screen for the clicks below.
@Config(sdk = [35], qualifiers = "w411dp-h914dp")
class BubbleScreenTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000), ZoneOffset.UTC)
    @After fun cleanup() { scope.cancel() }

    private fun model(): BubbleViewModel {
        val vm: BubbleViewModel = mock()
        val tip = CashTipActions(scope, mock(), mock(), clock)
        val expense = ExpenseActions(scope, mock(), clock)
        whenever(vm.cashTipActions).thenReturn(tip)
        whenever(vm.expenseActions).thenReturn(expense)
        whenever(vm.expenseOwner()).thenReturn("dash") // the live dash owns an entry; a mocked owner would be null and grey the block
        whenever(vm.appState).thenReturn(MutableStateFlow(AppState()))
        whenever(vm.focusedPlatform).thenReturn(MutableStateFlow(Platform.DoorDash))
        whenever(vm.focusedRegion).thenReturn(MutableStateFlow(
            PlatformRegion(Platform.DoorDash, Mode.Online, Session("dash", clock.millis()))))
        whenever(vm.sessionMiles).thenReturn(MutableStateFlow(0.0))
        whenever(vm.sessionEarnings).thenReturn(MutableStateFlow(0.0))
        whenever(vm.lastSession).thenReturn(MutableStateFlow(null))
        whenever(vm.sessionNet).thenReturn(MutableStateFlow(null))
        whenever(vm.countExpensesInKept).thenReturn(MutableStateFlow(false))
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
        whenever(vm.garage).thenReturn(MutableStateFlow(null))
        whenever(vm.odometerReadings).thenReturn(MutableStateFlow(emptyList()))
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

    private fun show(vm: BubbleViewModel = model()) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DashBuddyTheme {
                CompositionLocalProvider(LocalNowProvider provides clock, LocalReducedMotion provides true) {
                    BubbleScreen(vm)
                }
            }
        }
        settle()
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
        show()
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

    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(100) }
}
