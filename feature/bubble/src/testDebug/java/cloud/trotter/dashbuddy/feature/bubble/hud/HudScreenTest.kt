package cloud.trotter.dashbuddy.feature.bubble.hud

import cloud.trotter.dashbuddy.feature.bubble.hud.undo.*
import cloud.trotter.dashbuddy.feature.bubble.hud.expense.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import cloud.trotter.dashbuddy.feature.bubble.MutableClock
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.core.designsystem.time.LocalNowProvider
import cloud.trotter.dashbuddy.domain.analytics.SessionNet
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot
import cloud.trotter.dashbuddy.domain.state.*
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
        val tags = listOf("hud.top.earned", "hud.top.online", "hud.top.miles")
        val bounds = tags.map { compose.onNodeWithTag(it).getBoundsInRoot() }
        for (next in listOf(Flow.TaskPickupArrived, Flow.TaskDropoffNavigation)) {
            compose.runOnIdle { flow = next }
            settle()
            assertEquals(bounds, tags.map { compose.onNodeWithTag(it).getBoundsInRoot() })
            value("hud.top.earned", "$100.00").assertExists()
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

    @Test fun `null profit shows the em dash hero`() {
        show({ state(profit = null) })
        value("hud.hero", EMPTY_VALUE).assertIsDisplayed()
        compose.onNodeWithText("$0.00").assertDoesNotExist()
    }

    @Test fun `offer mode shows the offer slot and hides the chrome below the head`() {
        show({ state(flow = Flow.OfferPresented) })
        compose.onNodeWithTag("hud.slot.offer").assertIsDisplayed()
        compose.onNodeWithText("Offer slot").assertIsDisplayed()
        listOf("hud.top.earned", "hud.top.online", "hud.top.miles", "hud.hero", "hud.goal", "hud.ribbon")
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
        compose.onNodeWithText("Last dash").assertIsDisplayed()
    }

    @Test fun `resting shows the last dash and five dock blocks`() {
        show({ state(resting = true) })
        compose.onNodeWithText("Last dash").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        value("hud.top.earned", "$100.00").assertExists()
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
        show({ state(first = true, profit = null) })
        listOf("hud.top.earned", "hud.top.online", "hud.top.miles").forEach { value(it, EMPTY_VALUE).assertExists() }
        compose.onNodeWithText("No dash yet").assertIsDisplayed()
        compose.onNodeWithTag("hud.dock.CASH_TIP").assertDoesNotExist()
        compose.onNodeWithTag("hud.dock.EXPENSE").assertIsEnabled()
        compose.onNodeWithTag("hud.dock.ODOMETER").assertIsEnabled()
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
            DashBuddyTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    val presented = region.presentedOffer()
                    val model = HudStateMapper.map(region, Flow.OfferPresented, Platform.DoorDash,
                        emptyList(), 100.0, 42.5, null, null, null, null,
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
            DashBuddyTheme {
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
        assertEquals(listOf("2026-10-18|7900"), calls)
    }
    @Test fun offerMidSequence_returnsToEndState() {
        var mode by mutableStateOf(HudMode.LIVE)
        var mark by mutableStateOf<MarkRead>(MarkRead.Loaded(null))
        val calls = mutableListOf<String>()
        compose.setContent {
            DashBuddyTheme {
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
        val strip = cloud.trotter.dashbuddy.feature.bubble.hud.undo.UndoStripModel(
            700, "Panera Bread", 71, T0 + 10_000, 2)
        compose.setContent {
            DashBuddyTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    HudScreen(state(resting = resting), null, {},
                        undo = { cloud.trotter.dashbuddy.feature.bubble.hud.undo.UndoStrip(strip) {} },
                        undoVisible = { cloud.trotter.dashbuddy.feature.bubble.hud.undo.undoStripVisible(strip) })
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
        val strip = cloud.trotter.dashbuddy.feature.bubble.hud.undo.UndoStripModel(
            700, "Panera Bread", 71, T0 + 10_000, 2)
        var notice by mutableStateOf(cloud.trotter.dashbuddy.feature.bubble.hud.odometer.OdometerNoticeModel(52_388, 1))
        compose.setContent {
            DashBuddyTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    HudScreen(state(), null, {}, undo = {
                        if (undoBandContent(notice.token, strip.token.takeIf { strip.visible(clock.millis) }, null) == UndoBandContent.CASH_TIP)
                            cloud.trotter.dashbuddy.feature.bubble.hud.undo.UndoStrip(strip) {}
                        else cloud.trotter.dashbuddy.feature.bubble.hud.odometer.OdometerNotice(notice)
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

    @Test fun `compact expense subcard rests above dock with HUD visible behind it`() {
        var open by mutableStateOf(true)
        compose.setContent {
            DashBuddyTheme {
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

    @Test fun `gas Undo stays displayed and tappable below the compact subcard live and at rest`() {
        var resting by mutableStateOf(false)
        var open by mutableStateOf(true)
        var undos = 0
        var strip by mutableStateOf(UndoStripModel(4200, null, 72, T0 + 10_000, 3,
            whatLabel = cloud.trotter.dashbuddy.feature.bubble.R.string.hud_expense_gas, whereFallback = null))
        compose.setContent {
            DashBuddyTheme {
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
            whatLabel = cloud.trotter.dashbuddy.feature.bubble.R.string.hud_expense_gas, whereFallback = null))
        var notice by mutableStateOf(cloud.trotter.dashbuddy.feature.bubble.hud.odometer.OdometerNoticeModel(52_388, 1))
        compose.setContent {
            DashBuddyTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    HudScreen(state(), null, {}, undo = {
                        val tipVisible = undoStripVisible(tip)
                        val expenseVisible = undoStripVisible(expense)
                        when (undoBandContent(notice.token, tip.token.takeIf { tipVisible }, expense.token.takeIf { expenseVisible })) {
                            UndoBandContent.CASH_TIP -> UndoStrip(tip) {}
                            UndoBandContent.EXPENSE -> UndoStrip(expense) {}
                            UndoBandContent.ODOMETER -> cloud.trotter.dashbuddy.feature.bubble.hud.odometer.OdometerNotice(notice)
                            null -> Unit
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

}
