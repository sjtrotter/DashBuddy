package cloud.trotter.dashbuddy.feature.bubble.hud

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
        value("hud.top.online", "40").assertExists()
        tick(20_000)
        value("hud.top.online", "40").assertExists()
        tick(15_000)
        value("hud.top.online", "41").assertExists()
        value("hud.top.online", "40").assertDoesNotExist()
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
            value("hud.top.online", "40").assertExists()
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

    @Test fun `resting shows the last dash and four dock blocks`() {
        show({ state(resting = true) })
        compose.onNodeWithText("Last dash").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        value("hud.top.earned", "$100.00").assertExists()
        value("hud.top.online", "2").assertExists()
        value("hud.top.miles", "42.5").assertExists()
        DockKind.entries.filter { it != DockKind.NOW }.forEach { compose.onNodeWithTag("hud.dock.$it").assertIsDisplayed() }
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

    @Test fun `first run shows em dashes and a disabled cash tip`() {
        show({ state(first = true, profit = null) })
        listOf("hud.top.earned", "hud.top.online", "hud.top.miles").forEach { value(it, EMPTY_VALUE).assertExists() }
        compose.onNodeWithText("No dash yet").assertIsDisplayed()
        compose.onNodeWithTag("hud.dock.CASH_TIP").assertIsNotEnabled()
        compose.onNodeWithTag("hud.dock.EXPENSE").assertIsNotEnabled()
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
                        emptyList(), 100.0, 42.5, null, null, false, null, null,
                        hasActiveCard = snapshot != null, hasOfferCard = presented != null,
                        logOpen = false, gas = GasRowModel(3.5f, true))
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
}
