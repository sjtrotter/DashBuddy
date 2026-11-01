package cloud.trotter.log.gig.feature.bubble.hud

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.domain.analytics.DeliveryRecord
import cloud.trotter.log.gig.domain.analytics.ExpenseCategory
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.vehicle.LastOdometerReading
import cloud.trotter.log.gig.domain.model.vehicle.OdometerCheck
import cloud.trotter.log.gig.domain.model.vehicle.ReconciledMileage
import cloud.trotter.log.gig.domain.model.vehicle.Vehicle
import cloud.trotter.log.gig.domain.offer.story.OfferFixtures
import cloud.trotter.log.gig.domain.offer.story.StoryFixtures
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.PendingOffer
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.feature.bubble.R
import cloud.trotter.log.gig.feature.bubble.hud.cashtip.CashTipSheet
import cloud.trotter.log.gig.feature.bubble.hud.cashtip.CashTipSheetModel
import cloud.trotter.log.gig.feature.bubble.hud.expense.ExpensePick
import cloud.trotter.log.gig.feature.bubble.hud.expense.ExpenseSheet
import cloud.trotter.log.gig.feature.bubble.hud.expense.ExpenseSheetModel
import cloud.trotter.log.gig.feature.bubble.hud.expense.ExpenseSubcard
import cloud.trotter.log.gig.feature.bubble.hud.goal.GoalSheet
import cloud.trotter.log.gig.feature.bubble.hud.goal.GoalSheetModel
import cloud.trotter.log.gig.feature.bubble.hud.odometer.OdometerFooter
import cloud.trotter.log.gig.feature.bubble.hud.odometer.OdometerSheet
import cloud.trotter.log.gig.feature.bubble.hud.odometer.OdometerSheetModel
import cloud.trotter.log.gig.feature.bubble.hud.vehicle.VehicleChip
import cloud.trotter.log.gig.feature.bubble.hud.vehicle.VehicleChipModel
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.TimeZone

/**
 * #1463 — the HUD's key screens, light and dark, rendered into `bubble/<name>-<theme>.png` for PR CI's
 * `screenshots-<pr>` artifact. Recorded only, never compared. Runs only with `-PscreenShots=true`;
 * the normal gate compiles it and excludes it.
 *
 * Every shot is the real [HudScreen] with the real slots, over one coherent fixture: the state card
 * gets the hero's earnings and the shift's start (as `BubbleScreen` does), and every offer is
 * presented around the fixture clock so its countdown is live. The window is the expanded bubble's
 * 600 dp height (`BubbleManager.BUBBLE_DESIRED_HEIGHT_DP`) at the Pixel 7's width and density.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h600dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class HudScreenshotsTest(private val theme: String) : HudScreenFixture() {

    private lateinit var timeZone: TimeZone
    private val f = OfferFixtures
    private val car = Vehicle("civic", "Civic", 0)
    private val chip = VehicleChipModel(car, listOf(car), car.id, false)
    private val facts = JobFacts("Store", T0 + 240_000, 7.9, 5.4)

    @Before fun fixTimeZone() {
        timeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }
    @After fun restoreTimeZone() { TimeZone.setDefault(timeZone) }

    // ── Live ────────────────────────────────────────────────────────────

    @Test fun liveWaiting() = live("live-waiting", Flow.Idle, RibbonKind.WAIT,
        StateCardModel.Waiting(T0 - 239_000, SESSION_START, 5, 5, 10, LastOffer(4.25, LastOfferOutcome.DECLINED))) {
        compose.onNodeWithText("Last offer").assertExists()
    }

    @Test fun livePickupNavigating() = live("live-pickup-navigating", Flow.TaskPickupNavigation, RibbonKind.PICKUP,
        StateCardModel.PickupNav(facts, T0 - 120_000)) { store() }

    @Test fun livePickupArrived() = live("live-pickup-arrived", Flow.TaskPickupArrived, RibbonKind.PICKUP,
        StateCardModel.PickupArrived(facts, T0 - 120_000)) { store() }

    @Test fun liveShopping() = live("live-shopping", Flow.TaskPickupArrived, RibbonKind.PICKUP,
        StateCardModel.Shopping(facts.copy(deadlineAt = T0 + 280_000), T0 - 620_000, 14, 22)) {
        value("state.figure", "of 22").assertExists()
    }

    @Test fun liveDropoffNavigating() = live("live-dropoff-navigating", Flow.TaskDropoffNavigation, RibbonKind.DROPOFF,
        StateCardModel.DropoffNav(facts, T0 - 120_000)) { store() }

    @Test fun liveDropoffArrived() = live("live-dropoff-arrived", Flow.TaskDropoffArrived, RibbonKind.DROPOFF,
        StateCardModel.DropoffArrived(facts, T0 - 120_000), cashTipCue = true) { store() }

    @Test fun liveReceipt() = live("live-receipt", Flow.PostTask, RibbonKind.DROPOFF,
        StateCardModel.Receipt("Store", T0 - 60_000,
            listOf(ReceiptLine("Base pay", 3.5), ReceiptLine("Peak pay", 1.0)), tip = 6.1, total = 10.6),
        cashTipCue = true, pendingCashTip = 500L) {
        compose.onNodeWithText("Cash tip $5.00 pending").assertExists()
    }

    /** #1443: the shift's cash tips beside, never inside, its earnings. */
    @Test fun liveCashTips() = live("live-cash-tips", Flow.Idle, RibbonKind.WAIT,
        StateCardModel.Waiting(T0 - 239_000, SESSION_START, 5, 5, 10, LastOffer(4.25, LastOfferOutcome.DECLINED)),
        cash = 12.0) {
        value("hud.hero", "+$12.00 cash").assertExists()
    }

    // ── Offers ──────────────────────────────────────────────────────────

    @Test fun offerStandard() = offer("offer-standard", mapOffer(presentedNow(f.pending()), null, f.config)) {
        compose.onNodeWithTag("offer.accept").assertExists()
    }

    /**
     * Net below the $7.00 minimum and distance past the 10-mile maximum. The evaluation is stated here
     * so its figures agree with each other ($9.00 pay, $3.60 car cost, 12 mi, 36 min); the shared
     * fixture's evaluation pins a net of $8.29, which no limit would flag.
     */
    @Test fun offerLimitNotes() {
        val o = f.offer().copy(payAmount = 9.0, distanceMiles = 12.0)
        val e = f.evaluation(o).copy(payAmount = 9.0, totalOperatingCost = 3.6, netPayAmount = 5.4,
            distanceMiles = 12.0, estimatedTimeMinutes = 36.0, dollarsPerHour = 9.0, dollarsPerMile = 0.45)
        offer("offer-limit-notes", mapOffer(presentedNow(f.pending(o, e)), null, StoryFixtures.limitConfig(net = 7f, miles = 10f))) {
            compose.onNodeWithText("Net under $7.00").assertExists()
            compose.onNodeWithText("Over 10.0 mi").assertExists()
        }
    }

    @Test fun offerAddOnJob() = offer("offer-addon-job", mapOffer(presentedNow(f.pending(f.addon())), f.job(), f.config)) {
        compose.onNodeWithText("+$7.00 added").assertExists()
    }

    /** The add-on with no known job: only the deltas the delivery app shows. */
    @Test fun offerAddOnRaw() = offer("offer-addon-raw", mapOffer(presentedNow(f.pending(f.addon())), null, f.config)) {
        compose.onNodeWithText("Added to your current job. The delivery app shows only what it adds.").assertExists()
    }

    // ── Log, rest, first run ────────────────────────────────────────────

    @Test fun log() {
        val s = state(vehicleChip = chip, flow = Flow.TaskDropoffArrived, logOpen = true)
        shot("log", { compose.onNodeWithTag("hud.log.now").assertExists() }) {
            HudScreen(s, ribbon(RibbonKind.DROPOFF), {}, onGoalClick = {},
                log = { LogView(logItems(), {}, {}) })
        }
    }

    @Test fun resting() {
        val s = state(vehicleChip = chip, resting = true)
        shot("resting", { compose.onNodeWithText("Last shift").assertExists() }) {
            HudScreen(s, null, {}, onGoalClick = {}, restingFooter = { odometerFooter() })
        }
    }

    /** #1443: a shift whose row has not been projected yet rests with em dashes. */
    @Test fun restingPending() {
        val s = state(vehicleChip = chip, resting = true, pending = true)
        shot("resting-pending", { compose.onNodeWithContentDescription("Earned $EMPTY_VALUE").assertExists() }) {
            HudScreen(s, null, {}, onGoalClick = {}, restingFooter = { odometerFooter() })
        }
    }

    @Test fun firstRun() {
        val s = state(first = true, earnings = null)
        shot("first-run", { compose.onNodeWithText("No shift yet").assertExists() }) {
            HudScreen(s, null, {}, onGoalClick = {})
        }
    }

    // ── Sheets ──────────────────────────────────────────────────────────

    @Test fun sheetCashTip() {
        val rows = (1L..5L).map { id ->
            DeliveryRecord(id, "live", Platform.DoorDash, "job$id", "task$id", "Store $id",
                T0 - (6 - id) * 600_000, 8.0, "OFFER_PAY", 6.0, null, null, null, null, cashTip = 10.0)
        }
        sheet("sheet-cash-tip", { compose.onNodeWithTag("cashTip.title").assertIsDisplayed() }) {
            CashTipSheet(CashTipSheetModel(rows, 5), onSelectDelivery = {}, onExpand = {}, onBack = {}, onSign = {},
                onAmount = {}, onOther = {}, onDigit = {}, onPoint = {}, onBackspace = {}, onDoneOther = {}, onCommit = {})
        }
    }

    /** The six-tile subcard, compact over the live HUD as `ExpenseHud` hosts it. */
    @Test fun sheetExpensePick() {
        val gas = RuntimeEnvironment.getApplication().getString(R.string.hud_expense_gas)
        sheet("sheet-expense-pick", { compose.onAllNodesWithText(gas)[0].assertIsDisplayed() }, compact = true) {
            ExpenseSubcard {}
        }
    }

    @Test fun sheetExpenseFuel() = sheet("sheet-expense-fuel",
        { compose.onNodeWithTag("expense.title").assertIsDisplayed() }) {
        ExpenseSheet(ExpenseSheetModel(ExpensePick.Fuel, totalText = "42.00", gallonsText = "11.125"),
            onBack = {}, onField = {}, onLabel = {}, onSelectLabel = {}, onNewLabel = {}, onEditLabel = {},
            onKeyboardDone = {}, onDigit = {}, onPoint = {}, onBackspace = {}, onLog = {})
    }

    @Test fun sheetOdometer() {
        val model = OdometerSheetModel(car, listOf(car), 52_388, null, OdometerCheck.First("Civic"), gpsLoaded = true)
        sheet("sheet-odometer", { compose.onNodeWithText("Save 52,388 mi").assertIsDisplayed() }) {
            OdometerSheet(model, onDigit = {}, onBackspace = {}, onSave = {},
                vehicleChip = { VehicleChip(chip, false, {}, {}, {}, {}) }, onShowAddVehicle = {}, onBack = {})
        }
    }

    @Test fun sheetGoal() {
        val title = RuntimeEnvironment.getApplication().getString(R.string.hud_goal_title)
        val assertion = {
            compose.onNodeWithTag("goal.sheet").assertIsDisplayed()
            compose.onAllNodesWithText(title)[0].assertIsDisplayed()
            Unit
        }
        sheet("sheet-goal", assertion, hud = state(vehicleChip = chip, goal = 150.0, today = 71.2)) {
            GoalSheet(GoalSheetModel(150, 71.20), {}, {}, {})
        }
    }

    // ── Builders ────────────────────────────────────────────────────────

    private fun store() { compose.onAllNodesWithText("Store")[0].assertExists() }

    /** Presented three seconds ago with 27 seconds left on the fixture clock. */
    private fun presentedNow(pending: PendingOffer) = pending.copy(presentedAt = T0 - 3_000, countdownExpiresAt = T0 + 27_000)

    private fun live(
        name: String, flow: Flow, tail: RibbonKind, card: StateCardModel,
        cash: Double? = null, cashTipCue: Boolean = false, pendingCashTip: Long? = null,
        assertion: () -> Unit,
    ) {
        val s = state(vehicleChip = chip, flow = flow, cash = cash, cashTipCue = cashTipCue)
        shot(name, assertion) {
            HudScreen(s, ribbon(tail), {}, onGoalClick = {},
                stateCard = { StateCard(card, s.hero.earnings, pendingCashTip) })
        }
    }

    private fun offer(name: String, model: OfferCardModel, assertion: () -> Unit) {
        val s = state(vehicleChip = chip, flow = Flow.OfferPresented)
        shot(name, assertion) {
            HudScreen(s, ribbon(RibbonKind.WAIT), {}, onGoalClick = {}, offer = { OfferCard(model, {}, {}) })
        }
    }

    /** A full sheet (or the compact subcard) over the live waiting HUD, in the HUD's own sheet frame. */
    private fun sheet(
        name: String, assertion: () -> Unit, compact: Boolean = false,
        hud: HudUiState = state(vehicleChip = chip),
        content: @Composable ColumnScope.() -> Unit,
    ) {
        val waiting = StateCardModel.Waiting(T0 - 239_000, SESSION_START, 5, 5, 10, LastOffer(4.25, LastOfferOutcome.DECLINED))
        shot(name, assertion, settleTwice = true) {
            HudScreen(hud, ribbon(RibbonKind.WAIT), {}, onGoalClick = {}, sheetOpen = true, sheetCompact = compact,
                sheet = content, stateCard = { StateCard(waiting, hud.hero.earnings) })
        }
    }

    /** Two jobs, an offer, an expense and the dispatcher lines of the live shift, oldest first. */
    private fun logItems(): List<LogItem> = listOf(
        DispatcherRow("chat:1", SESSION_START + 60_000, "Dash Paused!"),
        DispatcherRow("chat:2", SESSION_START + 300_000, "Dash Resumed!"),
        JobCardModel("job1", "Store", 74, ScoreTone.GOOD, 9.25, 3.8, listOf(
            JobLine.Accepted(T0 - 5_400_000, 18.5, 1.4, false),
            JobLine.PickedUp(T0 - 4_800_000, T0 - 4_500_000, 300_000),
            JobLine.Delivered(T0 - 3_600_000, 9.25),
            JobLine.CashTip(T0 - 3_500_000, 5.0, false, null)), false, T0 - 5_400_000),
        OfferRow("offer:1", T0 - 2_400_000, "Store", 4.25, 6.8, AppEventType.OFFER_DECLINED),
        ExpenseRow(3, T0 - 2_000_000, ExpenseCategory.FOOD, null, 8.5),
        JobCardModel("job2", "Store", 81, ScoreTone.GOOD, 10.6, 4.2, listOf(
            JobLine.Accepted(T0 - 1_500_000, 21.0, 1.6, false),
            JobLine.PickedUp(T0 - 900_000, T0 - 600_000, 300_000),
            JobLine.Now(NowPhase.DROPOFF_ARRIVED, T0 - 239_000, T0 + 1_000, 8.1)), true, T0 - 1_500_000),
    )

    /** The live shift's ribbon: two finished jobs, then [tail] running now. */
    private fun ribbon(tail: RibbonKind): RibbonModel {
        val s = SESSION_START
        val m = 60_000L
        val done = listOf(
            RibbonSegment(RibbonKind.WAIT, s, s + 12 * m), RibbonSegment(RibbonKind.PICKUP, s + 12 * m, s + 30 * m),
            RibbonSegment(RibbonKind.DROPOFF, s + 30 * m, s + 52 * m), RibbonSegment(RibbonKind.WAIT, s + 52 * m, s + 70 * m),
            RibbonSegment(RibbonKind.PICKUP, s + 70 * m, s + 95 * m), RibbonSegment(RibbonKind.DROPOFF, s + 95 * m, s + 120 * m))
        return RibbonModel(s, done + RibbonSegment(tail, s + 120 * m, null),
            listOf(OfferMark(s + 11 * m, true), OfferMark(s + 60 * m, false), OfferMark(s + 69 * m, true)), tail)
    }

    @Composable
    private fun odometerFooter() {
        val reading = LastOdometerReading(52_388, 157.3, T0 - 3_600_000)
        OdometerFooter(car, reading, ReconciledMileage(59, 57.0, reading.occurredAt - 86_400_000)) {}
    }

    /**
     * strength.log's procedure: a frozen frame clock, two seconds of frames, then the assertion and
     * the capture. A sheet runs a second round so the capture never lands mid-slide.
     */
    private fun shot(name: String, assertion: () -> Unit, settleTwice: Boolean = false, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GigLogTheme(darkTheme = theme == "dark") {
                CompositionLocalProvider(LocalNowProvider provides clock, LocalReducedMotion provides true, content = content)
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        if (settleTwice) {
            compose.mainClock.advanceTimeBy(2_000)
            compose.waitForIdle()
        }
        assertion()
        if (System.getProperty("roborazzi.test.record") == "true") {
            val file = File(System.getProperty("giglog.screenshots.dir"), "bubble/$name-$theme.png")
            file.parentFile?.mkdirs()
            compose.onRoot().captureRoboImage(file)
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes(): List<Array<Any>> = listOf(arrayOf<Any>("light"), arrayOf<Any>("dark"))
    }
}
