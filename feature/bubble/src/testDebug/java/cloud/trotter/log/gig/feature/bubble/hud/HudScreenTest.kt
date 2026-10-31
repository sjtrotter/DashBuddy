package cloud.trotter.log.gig.feature.bubble.hud

import cloud.trotter.log.gig.domain.offer.story.OfferFixtures
import cloud.trotter.log.gig.feature.bubble.hud.undo.*
import cloud.trotter.log.gig.feature.bubble.hud.cashtip.*
import cloud.trotter.log.gig.domain.analytics.DeliveryRecord
import cloud.trotter.log.gig.feature.bubble.hud.expense.*
import cloud.trotter.log.gig.feature.bubble.hud.goal.*
import androidx.compose.ui.platform.testTag
import org.junit.Assert.assertTrue
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import cloud.trotter.log.gig.feature.bubble.MutableClock
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.domain.analytics.SessionRecord
import cloud.trotter.log.gig.domain.model.cards.FlowCardSnapshot
import cloud.trotter.log.gig.domain.state.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import org.junit.Before
import org.junit.After

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
internal class HudScreenTest : HudScreenFixture() {
    @Test fun `online ticks once a minute under the test clock`() {
        show()
        value("hud.top.online", "2 hr 40 min").assertExists()
        tick(20_000)
        value("hud.top.online", "2 hr 40 min").assertExists()
        tick(15_000)
        value("hud.top.online", "2 hr 41 min").assertExists()
        value("hud.top.online", "2 hr 40 min").assertDoesNotExist()
    }

    @Test fun `top row keeps its slots across live states`() {
        var flow by mutableStateOf(Flow.Idle)
        show({ state(flow = flow) })
        val tags = listOf("hud.top.online", "hud.top.miles")
        val bounds = tags.map { compose.onNodeWithTag(it).getBoundsInRoot() }
        for (next in listOf(Flow.TaskPickupArrived, Flow.TaskDropoffNavigation)) {
            compose.runOnIdle { flow = next }
            settle()
            assertEquals(bounds, tags.map { compose.onNodeWithTag(it).getBoundsInRoot() })
            value("hud.top.online", "2 hr 40 min").assertExists()
            value("hud.top.miles", "42.5").assertExists()
        }
    }

    @Test fun `goal pill shows set goal with no goal`() {
        show()
        compose.onNodeWithText("Set goal").assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithTag("hud.goal").assertHasNoClickAction()
        compose.onNodeWithText("to go").assertDoesNotExist()
    }

    @Test fun `goal pill shows the remainder in progress`() {
        show({ state(goal = 150.0, today = 71.2) })
        compose.onNodeWithText("$79").assertIsDisplayed()
        compose.onNodeWithText("to go").assertIsDisplayed()
    }

    @Test fun `goal pill shows no text when met`() {
        show({ state(goal = 150.0, today = 170.0) })
        compose.onNodeWithText("to go").assertDoesNotExist()
        compose.onNodeWithText("over goal", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Goal met").assertIsDisplayed().assertHasNoClickAction()
    }

    @Test fun `hero shows dollars large and cents small`() {
        show()
        value("hud.hero", "71").assertIsDisplayed()
        value("hud.hero", ".20").assertIsDisplayed()
        // The nodes expose the actual text layouts, so this fails if both use one text size.
        val dollars = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        val cents = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        value("hud.hero", "71").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(dollars) }
        value("hud.hero", ".20").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(cents) }
        org.junit.Assert.assertTrue(dollars.single().layoutInput.style.fontSize > cents.single().layoutInput.style.fontSize)
    }

    @Test fun `null earnings show the em dash hero`() {
        show({ state(earnings = null) })
        value("hud.hero", EMPTY_VALUE).assertIsDisplayed()
        compose.onNodeWithText("$0.00").assertDoesNotExist()
    }

    @Test fun `the hero is unlabelled, named Earned for TalkBack, with no earnings cell in the top row`() {
        show()
        compose.onNodeWithContentDescription("Earned $71.20").assertIsDisplayed()
        compose.onNodeWithText("Profit").assertDoesNotExist()
        compose.onNodeWithText("Earned").assertDoesNotExist()
        compose.onNodeWithTag("hud.top.earned").assertDoesNotExist()
        compose.onNodeWithTag("hud.hero.cash").assertDoesNotExist()
    }

    @Test fun `cash tips show apart from the earnings under the hero`() {
        show({ state(cash = 5.0) })
        value("hud.hero", "+$5.00 cash").assertIsDisplayed()
        value("hud.hero", "71").assertIsDisplayed()
    }

    @Test fun `the compact hero names its figure Earned and keeps the cash mark`() {
        show({ state(cash = 5.0, logOpen = true) })
        compose.onNodeWithContentDescription("Earned $71.20").assertIsDisplayed()
        val cash = value("hud.hero.compact", "+$5.00 cash").assertIsDisplayed().getBoundsInRoot()
        // Under the figure, as on the full hero, not beside it.
        val figure = value("hud.hero.compact", "$71").getBoundsInRoot()
        assertTrue(cash.top >= figure.bottom)
    }

    @Test fun `offer mode shows the offer slot and hides the chrome below the head`() {
        show({ state(flow = Flow.OfferPresented) })
        compose.onNodeWithTag("hud.slot.offer").assertIsDisplayed()
        compose.onNodeWithText("Offer slot").assertIsDisplayed()
        listOf("hud.top.online", "hud.top.miles", "hud.hero", "hud.goal", "hud.ribbon")
            .forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        DockKind.entries.forEach { compose.onNodeWithTag("hud.dock.$it").assertDoesNotExist() }
    }

    @Test fun `log block calls back and log mode shows the log slot with Now lit`() {
        var open by mutableStateOf(false)
        var calls = 0
        show({ state(logOpen = open) }, { if (it == DockKind.LOG || it == DockKind.NOW) { calls++; open = !open } })
        compose.onNodeWithTag("hud.dock.LOG").performClick()
        settle()
        assertEquals(1, calls)
        compose.onNodeWithTag("hud.slot.log").assertIsDisplayed()
        compose.onNodeWithTag("hud.dock.NOW").assertIsSelected()
        compose.onNodeWithTag("hud.dock.NOW").performClick()
        settle()
        compose.onNodeWithTag("hud.slot.state").assertIsDisplayed()
    }

    @Test fun `resting log opens and closes while keeping settings reachable`() {
        var open by mutableStateOf(false)
        show({ state(resting = true, logOpen = open) }, { if (it == DockKind.LOG || it == DockKind.NOW) open = !open })
        compose.onNodeWithTag("hud.dock.LOG").performClick()
        settle()
        compose.onNodeWithTag("hud.slot.log").assertIsDisplayed()
        compose.onNodeWithTag("hud.dock.NOW").assertIsSelected()
        compose.onNodeWithTag("hud.dock.SETTINGS").assertIsDisplayed()
        compose.onNodeWithTag("hud.dock.NOW").performClick()
        settle()
        compose.onNodeWithText("Last shift").assertIsDisplayed()
    }

    @Test fun `resting shows the last dash and five dock blocks`() {
        show({ state(resting = true) })
        compose.onNodeWithText("Last shift").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        value("hud.top.online", "2 hr 0 min").assertExists()
        value("hud.top.miles", "42.5").assertExists()
        listOf(DockKind.CASH_TIP, DockKind.EXPENSE, DockKind.ODOMETER, DockKind.LOG, DockKind.SETTINGS).forEach { compose.onNodeWithTag("hud.dock.$it").assertIsDisplayed() }
        compose.onNodeWithTag("hud.ribbon").assertDoesNotExist()
        org.junit.Assert.assertTrue(state(resting = true).top.dimmed)
    }

    @Test fun `resting ended time ticks under the test clock`() {
        show({ state(resting = true) })
        compose.onNodeWithText("38").assertExists()
        tick(60_000)
        compose.onNodeWithText("39").assertExists()
        compose.onNodeWithText("38").assertDoesNotExist()
    }

    @Test fun `first run shows em dashes and the resting dock`() {
        show({ state(first = true, earnings = null) })
        listOf("hud.top.online", "hud.top.miles").forEach { value(it, EMPTY_VALUE).assertExists() }
        compose.onNodeWithText("No shift yet").assertIsDisplayed()
        compose.onNodeWithTag("hud.dock.CASH_TIP").assertDoesNotExist()
        compose.onNodeWithTag("hud.dock.EXPENSE").assertIsEnabled()
        compose.onNodeWithTag("hud.dock.ODOMETER").assertIsEnabled()
    }

    @Test fun `a pending shift at rest shows em dashes, never the first-run copy`() {
        show({ state(resting = true, pending = true) })
        compose.onNodeWithText("Last shift").assertIsDisplayed()
        compose.onNodeWithText("No shift yet").assertDoesNotExist()
        compose.onNodeWithText("earned/hr").assertIsDisplayed()
        compose.onNodeWithTag("hud.hero.cash").assertDoesNotExist()
        compose.onNodeWithContentDescription("Earned $EMPTY_VALUE").assertIsDisplayed()
    }

    @Test fun `an end grace rests with a ticking Earned per hour`() {
        // One hour online at $41, then two: the rate ticks without a state change.
        val start = T0 - 3_600_000
        compose.setContent {
            GigLogTheme { CompositionLocalProvider(LocalNowProvider provides clock) {
                RestingCard(RestingModel(start, null, 41.0, OnlineSpan.Ticking(start), 2, 1, 2))
            } }
        }
        settle()
        compose.onNodeWithText("$41.00").assertIsDisplayed()
        tick(3_600_000)
        compose.onNodeWithText("$20.50").assertIsDisplayed()
    }

    @Test fun `ribbon is present live and absent at rest`() {
        var resting by mutableStateOf(false)
        show({ state(resting = resting) })
        compose.onNodeWithTag("hud.ribbon").assertIsDisplayed()
        compose.runOnIdle { resting = true }
        settle()
        compose.onNodeWithTag("hud.ribbon").assertDoesNotExist()
    }

    @Test fun `paused dash says so in the head`() {
        show({ state(flow = Flow.Idle, paused = true) })
        compose.onNodeWithText("paused").assertIsDisplayed()
        compose.onNodeWithText("DoorDash").assertIsDisplayed()
        compose.onNodeWithTag("hud.ribbon").assertIsDisplayed()
    }

    @Test fun `door state accents the cash tip block`() {
        val door = StateCardModel.DropoffArrived(JobFacts("Store", null, null, null), T0)
        var clicked: DockKind? = null
        show({ state(flow = Flow.TaskDropoffArrived, cashTipCue = door.cuesCashTip) }, { clicked = it })
        compose.onNodeWithTag("hud.dock.CASH_TIP").assertIsEnabled()
            .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Suggested"))
            .performClick()
        settle()
        assertEquals(DockKind.CASH_TIP, clicked)
    }

    @Test fun `offer mode and offer content come from the region's pending offer`() {
        val pending = OfferFixtures.pending()
        var region by mutableStateOf(PlatformRegion(Platform.DoorDash, Mode.Online, Session("live", T0)))
        // The independently collected stack can lag both the arrival and removal of the pending offer.
        var snapshot by mutableStateOf<FlowCardSnapshot?>(
            FlowCardSnapshot.Offer(T0, offerHash = "stale"))
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    val presented = region.presentedOffer()
                    val model = HudStateMapper.map(region, Flow.OfferPresented, Platform.DoorDash,
                        emptyList(), null, 42.5, null, null, null,
                        hasActiveCard = snapshot != null, hasOfferCard = presented != null,
                        logOpen = false, gas = GasRowModel(3.5f, true), odometerLoaded = true)
                    HudScreen(model, ribbon = null, onDockClick = {}, stateCard = { Text("State slot") },
                        offer = { presented?.let { Text("Offer " + it.offerHash) } })
                }
            }
        }
        settle()
        compose.onNodeWithTag("hud.slot.offer").assertDoesNotExist()
        compose.runOnIdle {
            snapshot = FlowCardSnapshot.Awaiting("waiting", T0)
            region = region.copy(pendingOffers = listOf(pending))
        }
        settle()
        compose.onNodeWithTag("hud.slot.offer").assertIsDisplayed()
        compose.onNodeWithText("Offer real").assertIsDisplayed()
        compose.runOnIdle {
            snapshot = FlowCardSnapshot.Offer(T0, offerHash = "stale")
            region = region.copy(pendingOffers = listOf(pending.copy(acceptedAt = T0)))
        }
        settle()
        compose.onNodeWithTag("hud.slot.offer").assertDoesNotExist()
        compose.onNodeWithText("Offer real").assertDoesNotExist()
    }

    @Test fun `log shows the compressed hero with the goal pill`() {
        show({ state(logOpen = true) })
        settle()
        compose.onNodeWithTag("hud.hero.compact").assertIsDisplayed()
        compose.onNodeWithTag("hud.goal").assertIsDisplayed()
        compose.onNodeWithTag("hud.hero").assertDoesNotExist()
    }
    @Test fun `log hides the ribbon`() {
        show({ state(logOpen = true) })
        settle()
        compose.onNodeWithTag("hud.ribbon").assertDoesNotExist()
    }
    @Test fun `dock reads Now in the log and reports NOW`() {
        var clicked: DockKind? = null
        show({ state(logOpen = true) }, { clicked = it })
        settle()
        compose.onNodeWithText("Now").assertIsDisplayed()
        compose.onNodeWithTag("hud.dock.LOG").assertDoesNotExist()
        compose.onNodeWithTag("hud.dock.NOW").performClick()
        settle()
        assertEquals(DockKind.NOW, clicked)
    }

    @Test fun offerUp_suppressesSequence_thenPlaysAfterResolve() {
        var mode by mutableStateOf(HudMode.OFFER)
        var mark by mutableStateOf<MarkRead>(MarkRead.Loaded(null))
        val calls = mutableListOf<String>()
        val day = java.time.LocalDate.of(2026, 10, 18)
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides clock, LocalReducedMotion provides false) {
                    val key = GoalCelebrationResolver.resolve(GoalPillState.Met, mode, day, 79.0, mark)
                    HudScreen(polishState(mode, HeroModel(80.0, GoalPillState.Met, key)), null, {},
                        onGoalCelebrated = { calls += it; mark = MarkRead.Loaded(it) })
                }
            }
        }
        settle()
        compose.onNodeWithTag("hud.goal").assertDoesNotExist()
        assertEquals(emptyList<String>(), calls)
        compose.runOnIdle { mode = HudMode.LIVE }; settle()
        compose.onNodeWithTag("hud.goal").assert(SemanticsMatcher.expectValue(GoalMetPhaseKey, GoalMetPhase.CLOSE))
        assertEquals(listOf("2026-10-18|earned|7900"), calls)
    }
    @Test fun offerMidSequence_returnsToEndState() {
        var mode by mutableStateOf(HudMode.LIVE)
        var mark by mutableStateOf<MarkRead>(MarkRead.Loaded(null))
        val calls = mutableListOf<String>()
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides clock, LocalReducedMotion provides false) {
                    val key = GoalCelebrationResolver.resolve(GoalPillState.Met, mode,
                        java.time.LocalDate.of(2026, 10, 18), 79.0, mark)
                    HudScreen(polishState(mode, HeroModel(80.0, GoalPillState.Met, key)), null, {},
                        onGoalCelebrated = { calls += it; mark = MarkRead.Loaded(it) })
                }
            }
        }
        settle()
        compose.onNodeWithTag("hud.goal").assert(SemanticsMatcher.expectValue(GoalMetPhaseKey, GoalMetPhase.CLOSE))
        compose.runOnIdle { mode = HudMode.OFFER }; settle()
        compose.onNodeWithTag("hud.goal").assertDoesNotExist()
        compose.runOnIdle { mode = HudMode.LIVE }; settle()
        compose.onNodeWithTag("hud.goal").assert(SemanticsMatcher.expectValue(GoalMetPhaseKey, GoalMetPhase.DONE))
        assertEquals(1, calls.size)
    }
    private companion object { const val T0 = 1_700_000_000_000L }
    @Test fun `cash tip strip occupies the undo band live and at rest`() {
        var resting by mutableStateOf(false)
        val strip = cloud.trotter.log.gig.feature.bubble.hud.undo.UndoStripModel(
            700, "Panera Bread", 71, T0 + 10_000, 2)
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    HudScreen(state(resting = resting), null, {},
                        undo = { cloud.trotter.log.gig.feature.bubble.hud.undo.UndoStrip(strip) {} },
                        undoVisible = { cloud.trotter.log.gig.feature.bubble.hud.undo.undoStripVisible(strip) })
                }
            }
        }
        settle()
        compose.onNodeWithText("Cash tip $7.00, Panera Bread").assertIsDisplayed()
        compose.runOnIdle { resting = true }
        settle()
        compose.onNodeWithText("Cash tip $7.00, Panera Bread").assertIsDisplayed()
        compose.runOnIdle { clock.millis += 10_000 }
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(10_000)
        compose.onNodeWithTag("undo.strip").assertDoesNotExist()
    }

    @Test fun `the newer notice wins the shared undo slot`() {
        val strip = cloud.trotter.log.gig.feature.bubble.hud.undo.UndoStripModel(
            700, "Panera Bread", 71, T0 + 10_000, 2)
        var notice by mutableStateOf(cloud.trotter.log.gig.feature.bubble.hud.odometer.OdometerNoticeModel(52_388, 1))
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    HudScreen(state(), null, {}, undo = {
                        if (newestBand(mapOf("notice" to notice.token, "tip" to strip.token.takeIf { strip.visible(clock.millis) })) == "tip")
                            cloud.trotter.log.gig.feature.bubble.hud.undo.UndoStrip(strip) {}
                        else cloud.trotter.log.gig.feature.bubble.hud.odometer.OdometerNotice(notice)
                    })
                }
            }
        }
        settle()
        compose.onNodeWithTag("undo.strip").assertIsDisplayed()
        compose.onNodeWithText("Odometer saved, 52,388 mi").assertDoesNotExist()
        compose.runOnIdle { notice = notice.copy(token = 3) }
        settle()
        compose.onNodeWithText("Odometer saved, 52,388 mi").assertIsDisplayed()
        compose.onNodeWithTag("undo.strip").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `live cash tip sheet reaches the body bottom and the dock with no gap`() {
        var undoShown by mutableStateOf(false)
        val delivery = DeliveryRecord(71, "live", Platform.DoorDash, "job", "task", "Panera Bread",
            T0, 8.0, "OFFER_PAY", 6.0, null, null, null, null, cashTip = 10.0)
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides clock, LocalReducedMotion provides true) {
                    Box(Modifier.width(400.dp).height(760.dp)) {
                        HudScreen(state(), null, {}, sheetOpen = true, sheet = {
                            CashTipSheet(CashTipSheetModel(listOf(delivery), 71, totalCents = 700),
                                onSelectDelivery = {}, onExpand = {}, onBack = {}, onSign = {}, onAmount = {},
                                onOther = {}, onDigit = {}, onPoint = {}, onBackspace = {}, onDoneOther = {}, onCommit = {})
                        }, undo = {
                            if (undoShown) UndoStrip(UndoStripModel(700, "Panera Bread", 71, T0 + 10_000, 2)) {}
                        }, undoVisible = { undoShown })
                    }
                }
            }
        }
        for (visible in listOf(false, true)) {
            compose.runOnIdle { undoShown = visible }
            settle()
            compose.onNodeWithText("Add $7.00 cash tip").assertIsDisplayed()
            val sheet = compose.onNodeWithTag("hud.sheet").getBoundsInRoot()
            val body = compose.onNodeWithTag("hud.body").getBoundsInRoot()
            val dock = compose.onNodeWithTag("hud.dock").getBoundsInRoot()
            assertEquals(body.bottom, sheet.bottom)
            assertEquals(body.bottom, dock.top)
        }
    }

    @Test fun `compact expense subcard rests above dock with HUD visible behind it`() {
        var open by mutableStateOf(true)
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides clock, LocalReducedMotion provides true) {
                    Box(Modifier.width(400.dp).height(760.dp)) {
                        HudScreen(state(), null, {}, sheetOpen = open, sheetCompact = true,
                            onSheetDismiss = { open = false }, sheet = { ExpenseSubcard {} },
                            stateCard = { Text("Waiting behind subcard") })
                    }
                }
            }
        }
        settle()
        val sheet = compose.onNodeWithTag("hud.sheet").getBoundsInRoot()
        val dock = compose.onNodeWithTag("hud.dock").getBoundsInRoot()
        val body = compose.onNodeWithTag("hud.body").getBoundsInRoot()
        assertEquals(dock.top, sheet.bottom)
        org.junit.Assert.assertTrue(sheet.top > body.top)
        assertEquals(176.dp, sheet.height)
        compose.onNodeWithTag("hud.hero").assertIsDisplayed()
        compose.onNodeWithText("Waiting behind subcard").assertIsDisplayed()
        compose.onNodeWithTag("hud.sheet.outside.header").performClick()
        settle()
        compose.onNodeWithTag("expense.subcard").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h900dp")
    fun `compact expense intercepts platform pointer taps and lets the goal pill replace it`() {
        var route by mutableStateOf<String?>("EXPENSE")
        var selected by mutableStateOf(Platform.DoorDash)
        var switches = 0
        val callbacks = mutableListOf<String>()
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides clock, LocalReducedMotion provides true) {
                    Box(Modifier.width(400.dp).height(760.dp)) {
                        HudScreen(state().copy(platform = selected,
                            switchable = listOf(Platform.DoorDash, Platform.Uber)), null, {},
                            onSelectPlatform = { selected = it; switches++ },
                            onGoalClick = { callbacks += "goal"; route = "GOAL" },
                            sheetOpen = route != null, sheetCompact = route == "EXPENSE",
                            onSheetOutsideDismiss = { callbacks += "dismiss"; route = null },
                            sheet = {
                                when (route) {
                                    "EXPENSE" -> ExpenseSubcard {}
                                    "GOAL" -> GoalSheet(GoalSheetModel(150, 71.2), {}, {}, {})
                                    else -> Unit
                                }
                            })
                    }
                }
            }
        }
        settle()
        compose.onNodeWithText(Platform.Uber.shortName, ignoreCase = true).performTouchInput { click() }; settle()
        compose.onNodeWithTag("expense.subcard").assertDoesNotExist()
        assertEquals(Platform.DoorDash, selected)
        assertEquals(0, switches)
        assertEquals(listOf("dismiss"), callbacks)
        compose.runOnIdle { route = "EXPENSE"; callbacks.clear() }; settle()
        compose.onNodeWithTag("hud.goal").performTouchInput { click() }; settle()
        compose.onNodeWithTag("expense.subcard").assertDoesNotExist()
        compose.onNodeWithTag("goal.sheet").assertIsDisplayed()
        assertEquals(listOf("dismiss", "goal"), callbacks)
        assertEquals(0, switches)
    }

    @Test fun `gas Undo stays displayed and tappable below the compact subcard live and at rest`() {
        var resting by mutableStateOf(false)
        var open by mutableStateOf(true)
        var undos = 0
        var strip by mutableStateOf(UndoStripModel(4200, null, 72, T0 + 10_000, 3,
            whatLabel = cloud.trotter.log.gig.feature.bubble.R.string.hud_expense_gas, whereFallback = null))
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides clock, LocalReducedMotion provides true) {
                    Box(Modifier.width(400.dp).height(760.dp)) {
                        HudScreen(state(resting = resting), null, {}, sheetOpen = open, sheetCompact = true,
                            onSheetDismiss = { open = false }, sheet = { ExpenseSubcard {} },
                            undo = { UndoStrip(strip) { undos++ } }, undoVisible = { undoStripVisible(strip) })
                    }
                }
            }
        }
        settle()
        for (atRest in listOf(false, true)) {
            compose.runOnIdle { resting = atRest; strip = strip.copy(token = strip.token + 1) }
            settle()
            compose.onNodeWithText("Gas $42.00").assertIsDisplayed()
            val band = compose.onNodeWithTag("hud.slot.undo").getBoundsInRoot()
            assertEquals(band.top, compose.onNodeWithTag("hud.sheet").getBoundsInRoot().bottom)
            assertEquals(band.top, compose.onNodeWithTag("hud.sheet.outside").getBoundsInRoot().bottom)
            // Pointer input catches an overlay intercepting the strip; semantics clicks would bypass it.
            compose.onNodeWithText("Undo").assertIsDisplayed().assertIsEnabled().performTouchInput { click() }
            settle()
            assertEquals(if (atRest) 2 else 1, undos)
            compose.onNodeWithTag("expense.subcard").assertIsDisplayed()
        }
    }

    @Test fun `undo band selects newest of three and falls back when expense expires`() {
        val tip = UndoStripModel(700, "Panera Bread", 71, T0 + 20_000, 2)
        var expense by mutableStateOf(UndoStripModel(4200, null, 72, T0 + 10_000, 3,
            whatLabel = cloud.trotter.log.gig.feature.bubble.R.string.hud_expense_gas, whereFallback = null))
        var notice by mutableStateOf(cloud.trotter.log.gig.feature.bubble.hud.odometer.OdometerNoticeModel(52_388, 1))
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    HudScreen(state(), null, {}, undo = {
                        val tipVisible = undoStripVisible(tip)
                        val expenseVisible = undoStripVisible(expense)
                        when (newestBand(mapOf("notice" to notice.token, "tip" to tip.token.takeIf { tipVisible }, "expense" to expense.token.takeIf { expenseVisible }))) {
                            "tip" -> UndoStrip(tip) {}
                            "expense" -> UndoStrip(expense) {}
                            "notice" -> cloud.trotter.log.gig.feature.bubble.hud.odometer.OdometerNotice(notice)
                            else -> Unit
                        }
                    })
                }
            }
        }
        settle()
        compose.onNodeWithText("Gas $42.00").assertIsDisplayed()
        compose.onNodeWithText("Delivery", substring = true).assertDoesNotExist()
        compose.runOnIdle { notice = notice.copy(token = 4) }; settle()
        compose.onNodeWithText("Odometer saved, 52,388 mi").assertIsDisplayed()
        compose.runOnIdle { expense = expense.copy(token = 5) }; settle()
        compose.onNodeWithText("Gas $42.00").assertIsDisplayed()
        compose.runOnIdle { notice = notice.copy(token = 1); clock.millis += 10_000 }
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(10_000)
        compose.onNodeWithText("Cash tip $7.00, Panera Bread").assertIsDisplayed()
        compose.onNodeWithText("Gas $42.00").assertDoesNotExist()
    }


    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h900dp")
    fun `goal pill opens full frame and progress takes the additional height`() {
        var open by mutableStateOf(false)
        var height by mutableStateOf(600.dp)
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides clock, LocalReducedMotion provides true) {
                    Box(Modifier.width(400.dp).height(height)) {
                        HudScreen(state(goal = 150.0, today = 71.2), null, {},
                            onGoalClick = { open = true }, sheetOpen = open,
                            onSheetDismiss = { open = false }, sheet = {
                                GoalSheet(GoalSheetModel(150, 71.2), {}, {}, {})
                            })
                    }
                }
            }
        }
        settle()
        compose.onNodeWithTag("hud.goal").performTouchInput { click() }
        settle()
        compose.onNodeWithTag("goal.sheet").assertIsDisplayed()
        val frame = compose.onNodeWithTag("hud.sheet").getBoundsInRoot()
        assertEquals(compose.onNodeWithTag("hud.body").getBoundsInRoot(), frame)
        assertEquals(compose.onNodeWithTag("hud.dock").getBoundsInRoot().top, frame.bottom)
        assertEquals(frame.bottom, compose.onNodeWithTag("goal.sheet").getBoundsInRoot().bottom)
        val progress = compose.onNodeWithTag("goal.progress").getBoundsInRoot()
        val bar = compose.onNodeWithTag("goal.bar").getBoundsInRoot()
        val preset = compose.onNodeWithTag("goal.preset.100").getBoundsInRoot()
        assertTrue(progress.top >= compose.onNodeWithTag("goal.figure").getBoundsInRoot().bottom)
        assertTrue(bar.top >= progress.top && bar.bottom <= progress.bottom)
        assertTrue(preset.bottom <= progress.top)
        compose.runOnIdle { height = 760.dp }; settle()
        assertEquals(progress.height + 160.dp, compose.onNodeWithTag("goal.progress").getBoundsInRoot().height)
        assertEquals(bar.top + 160.dp, compose.onNodeWithTag("goal.bar").getBoundsInRoot().top)
        assertEquals(preset.top, compose.onNodeWithTag("goal.preset.100").getBoundsInRoot().top)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h900dp")
    fun `compact log goal pill opens the same sheet and leaves the log`() {
        var log by mutableStateOf(true)
        var open by mutableStateOf(false)
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides clock, LocalReducedMotion provides true) {
                    HudScreen(state(logOpen = log), null, {},
                        onGoalClick = { log = false; open = true }, sheetOpen = open,
                        sheet = { GoalSheet(GoalSheetModel(null, 71.2), {}, {}, {}) })
                }
            }
        }
        settle()
        compose.onNodeWithTag("hud.slot.log").assertIsDisplayed()
        compose.onNodeWithTag("hud.goal").performTouchInput { click() }; settle()
        compose.onNodeWithTag("hud.slot.log").assertDoesNotExist()
        compose.onNodeWithTag("goal.sheet").assertIsDisplayed()
        compose.onNodeWithTag("hud.hero").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h900dp")
    fun `goal sheet shares one slot with odometer cash tip and compact expense`() {
        var route by mutableStateOf<String?>(null)
        var back: () -> Unit = {}
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides clock, LocalReducedMotion provides true,
                    LocalHudSheetBackHandler provides { enabled, dismiss ->
                        SideEffect { back = if (enabled) dismiss else ({}) }
                    }) {
                    Box(Modifier.width(400.dp).height(760.dp)) {
                        HudScreen(state(), null, { kind -> route = kind.name },
                            onGoalClick = { route = "GOAL" }, sheetOpen = route != null,
                            sheetCompact = route == "EXPENSE", onSheetDismiss = { route = null },
                            sheet = {
                                when (route) {
                                    "GOAL" -> GoalSheet(GoalSheetModel(150, 71.2), {}, {}, {})
                                    "EXPENSE" -> ExpenseSubcard {}
                                    "CASH_TIP", "ODOMETER" -> Text(route!!, Modifier.testTag("other.sheet"))
                                    else -> Unit
                                }
                            })
                    }
                }
            }
        }
        settle()
        for (kind in listOf(DockKind.ODOMETER, DockKind.CASH_TIP, DockKind.EXPENSE)) {
            compose.onNodeWithTag("hud.dock.$kind").performTouchInput { click() }; settle()
            compose.onNodeWithTag(if (kind == DockKind.EXPENSE) "expense.subcard" else "other.sheet").assertIsDisplayed()
            // A real pointer tap proves the compact subcard's header scrim does not eat the pill tap.
            compose.onNodeWithTag("hud.goal").performTouchInput { click() }; settle()
            compose.onAllNodesWithTag("hud.sheet").assertCountEquals(1)
            compose.onNodeWithTag("goal.sheet").assertIsDisplayed()
            compose.onNodeWithTag("other.sheet").assertDoesNotExist()
            compose.onNodeWithTag("expense.subcard").assertDoesNotExist()
            compose.onNodeWithTag("hud.dock.$kind").performTouchInput { click() }; settle()
            compose.onNodeWithTag("goal.sheet").assertDoesNotExist()
        }
        compose.onNodeWithTag("hud.goal").performTouchInput { click() }; settle()
        compose.runOnIdle { back() }; settle()
        compose.onNodeWithTag("goal.sheet").assertDoesNotExist()
    }

}
