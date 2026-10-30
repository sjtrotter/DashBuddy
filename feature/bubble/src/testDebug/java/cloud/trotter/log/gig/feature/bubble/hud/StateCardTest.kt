package cloud.trotter.log.gig.feature.bubble.hud

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.domain.model.cards.CardStack
import cloud.trotter.log.gig.domain.model.cards.FlowCardSnapshot
import cloud.trotter.log.gig.domain.model.pay.ParsedPay
import cloud.trotter.log.gig.domain.model.pay.ParsedPayItem
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.feature.bubble.MutableClock
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StateCardTest {
    @get:Rule val compose = createComposeRule()
    private val clock = MutableClock(T0 + 239_000)
    private val facts = JobFacts("Store", T0 + 240_000, 7.9, 5.4)
    private lateinit var locale: Locale

    @Before fun setup() {
        locale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        compose.mainClock.autoAdvance = false
    }
    @After fun cleanup() { Locale.setDefault(locale) }

    private fun show(model: StateCardModel, kept: Double? = null) {
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) { StateCard(model, kept) }
            }
        }
        settle()
    }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(100) }
    private fun tick(at: Long) {
        compose.runOnIdle { clock.millis = at }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
    }
    private fun value(tag: String, text: String) = compose.onNode(
        hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
    private fun figure(text: String) = value("state.figure", text)

    @Test fun `dwell flips from seconds to whole minutes at the deadline edge`() {
        show(StateCardModel.PickupArrived(facts, T0))
        figure("3:59").assertExists()
        figure("at store").assertExists()
        tick(T0 + 240_000)
        figure("4").assertExists()
        figure("min at store").assertExists()
        compose.onNode(hasText(":", substring = true) and hasAnyAncestor(hasTestTag("state.figure")),
            useUnmergedTree = true).assertDoesNotExist()
        value("state.foot.late", "1").assertExists()
        value("state.foot.late", "min late").assertExists()
    }
    @Test fun `dwell ticks once a second`() {
        show(StateCardModel.PickupArrived(facts.copy(deadlineAt = T0 + 900_000), T0))
        figure("3:59").assertExists()
        tick(T0 + 239_999)
        figure("3:59").assertExists()
        tick(T0 + 240_000)
        figure("4:00").assertExists()
    }
    @Test fun `late dwell ticks once a minute`() {
        clock.millis = T0 + 599_000
        show(StateCardModel.PickupArrived(facts, T0))
        figure("9").assertExists()
        tick(T0 + 599_999)
        figure("9").assertExists()
        tick(T0 + 600_000)
        figure("10").assertExists()
        figure("9").assertDoesNotExist()
    }
    @Test fun `late foot line shows whole minutes late`() {
        clock.millis = T0 + 360_000
        show(StateCardModel.PickupArrived(facts, T0))
        value("state.foot.late", "2").assertExists()
        value("state.foot.late", "min late").assertExists()
        compose.onNodeWithTag("state.track").assert(hasContentDescription("2 min late", substring = true))
        tick(T0 + 419_999)
        value("state.foot.late", "2").assertExists()
        tick(T0 + 420_000)
        value("state.foot.late", "3").assertExists()
    }
    @Test fun `door timer flips at the delivery deadline`() {
        show(StateCardModel.DropoffArrived(facts, T0))
        figure("3:59").assertExists()
        figure("at door").assertExists()
        tick(T0 + 240_000)
        figure("4").assertExists()
        figure("min at door").assertExists()
        value("state.foot.late", "1").assertExists()
    }
    @Test fun `minutes left count down per minute`() {
        clock.millis = T0 + 119_999
        show(StateCardModel.PickupNav(facts, T0))
        figure("3").assertExists()
        figure("min left").assertExists()
        tick(T0 + 120_000)
        figure("2").assertExists()
        tick(T0 + 180_000)
        figure("1").assertExists()
    }
    @Test fun `navigation without a deadline shows elapsed en route and no due`() {
        clock.millis = T0 + 252_000
        show(StateCardModel.DropoffNav(facts.copy(deadlineAt = null), T0))
        figure("4:12").assertExists()
        figure("en route").assertExists()
        compose.onNodeWithText("due").assertDoesNotExist()
        compose.onNodeWithText("min left").assertDoesNotExist()
        compose.onNodeWithTag("state.track").assert(hasContentDescription("No deadline"))
        tick(T0 + 253_000)
        figure("4:13").assertExists()
    }
    @Test fun `waiting ticks and shows the tally and last offer`() {
        show(StateCardModel.Waiting(T0, T0, 5, 5, 10, LastOffer(4.25, LastOfferOutcome.DECLINED)), 20.0)
        figure("3:59").assertExists()
        tick(T0 + 240_000)
        figure("4:00").assertExists()
        compose.onNodeWithText("5").assertExists()
        compose.onNodeWithText("deliveries").assertExists()
        compose.onNodeWithText("5 of 10").assertExists()
        compose.onNodeWithText("accepted").assertExists()
        compose.onNodeWithText("Last offer").assertExists()
        compose.onNodeWithText("$4.25").assertExists()
        compose.onNodeWithText("declined").assertExists()
        compose.onNodeWithText("Profit/hr").assertExists()
    }
    @Test fun `waiting at dash start shows no offer yet and no profit per hour`() {
        clock.millis = T0
        show(StateCardModel.Waiting(T0, T0, 0, 0, 0, null))
        figure("0:00").assertExists()
        compose.onNodeWithText("0").assertExists()
        compose.onNodeWithText("No offer yet").assertExists()
        compose.onNodeWithText("Profit/hr").assertDoesNotExist()
        compose.onNodeWithText("$0.00").assertDoesNotExist()
    }
    @Test fun `shopping shows count of total and items per minute`() {
        clock.millis = T0 + 620_000
        show(StateCardModel.Shopping(facts.copy(deadlineAt = T0 + 900_000), T0, 14, 22))
        figure("14").assertExists()
        figure("of 22").assertExists()
        figure("shopped").assertExists()
        compose.onNodeWithText("1.4").assertExists()
        compose.onNodeWithText("items/min").assertExists()
        compose.onNodeWithText("10:20").assertExists()
        compose.onNodeWithText("at store").assertExists()
        compose.onNodeWithTag("state.items").assertExists()
        compose.onNodeWithTag("state.track").assertDoesNotExist()
    }
    private fun receipt(total: Double? = 10.6) = StateCardMapper.map(Flow.PostTask, Mode.Online,
        FlowCardSnapshot.PostTask(T0, jobId = "job", storeName = "Store", totalPay = total,
            parsedPay = ParsedPay(listOf(ParsedPayItem("Base pay", 3.5), ParsedPayItem("Peak pay", 1.0)),
                listOf(ParsedPayItem("Store", 4.0), ParsedPayItem("Other", 2.1)))),
        CardStack.Empty, null, null)!!

    @Test fun `receipt shows platform lines and the total with no cash line`() {
        show(receipt())
        listOf("Base pay", "$3.50", "Peak pay", "$1.00", "Tip", "$6.10", "Delivery total", "$10.60")
            .forEach { compose.onNodeWithText(it).assertExists() }
        val labels = listOf("Base pay", "Peak pay", "Tip", "Delivery total").map {
            compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top
        }
        assertTrue(labels.zipWithNext().all { (a, b) -> a < b })
        compose.onNodeWithText("cash", substring = true, ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithText("Add", substring = true).assertDoesNotExist()
    }
    @Test fun `receipt renders without a clock`() {
        compose.setContent {
            GigLogTheme { ReceiptCard(StateCardModel.Receipt("Store", T0, emptyList(), 6.1, 10.6)) }
        }
        settle()
        compose.onNodeWithText("Delivery total").assertExists()
        compose.onNodeWithText("$10.60").assertExists()
        compose.onNodeWithTag("state.figure").assertDoesNotExist()
    }
    @Test fun `receipt with no total shows the em dash`() {
        show(receipt(null))
        compose.onNodeWithText(EMPTY_VALUE).assertExists()
        compose.onNodeWithText("Delivery total").assertExists()
        compose.onNodeWithText("$0.00").assertDoesNotExist()
    }
    @Test fun `navigation late keeps elapsed minutes and the late foot`() {
        clock.millis = T0 + 1_439_999
        show(StateCardModel.DropoffNav(facts.copy(deadlineAt = T0 + 1_320_000), T0))
        figure("23").assertExists()
        figure("min en route").assertExists()
        value("state.foot.late", "1").assertExists()
        compose.onNodeWithTag("state.track").assert(hasContentDescription("1 min late", substring = true))
    }
    @Test fun `shopping late swaps pace for lateness and keeps the count`() {
        clock.millis = T0 + 720_000
        show(StateCardModel.Shopping(facts.copy(deadlineAt = T0 + 600_000), T0, 14, 22))
        figure("14").assertExists()
        figure("of 22").assertExists()
        compose.onNodeWithText("12").assertExists()
        compose.onNodeWithText("min at store").assertExists()
        compose.onNodeWithText("items/min").assertDoesNotExist()
        value("state.foot.late", "2").assertExists()
        compose.onNodeWithTag("state.items").assertExists()
    }
    @Test fun `model writes settle before the next tick`() {
        val model = mutableStateOf<StateCardModel>(StateCardModel.PickupNav(facts, T0))
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) { StateCard(model.value, null) }
            }
        }
        settle()
        compose.runOnIdle { model.value = StateCardModel.PickupArrived(facts, T0) }
        settle()
        figure("at store").assertExists()
        tick(T0 + 240_000)
        figure("min at store").assertExists()
    }
    private companion object { const val T0 = 1_700_000_000_000L }

    @Test fun `arriving after the deadline keeps the anchor pin clear of the stopwatch`() {
        show(StateCardModel.PickupArrived(JobFacts("Store", T0 - 10 * 60_000, 7.9, 5.4), T0 - 5 * 60_000))
        val anchor = compose.onNodeWithTag("track.pin.anchor", useUnmergedTree = true).getBoundsInRoot()
        val mark = compose.onNodeWithTag("track.pin.mark", useUnmergedTree = true).getBoundsInRoot()
        org.junit.Assert.assertTrue("mark ${'$'}mark overlaps anchor ${'$'}anchor", mark.left >= anchor.right)
    }
    @Test fun `the pending foot shows the logged amount, not the total`() {
        val strip = cloud.trotter.log.gig.feature.bubble.hud.undo.UndoStripModel(
            700L, "Store", 71L, clock.millis + 10_000L, 1L)
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    // Delivery total includes $2 already recorded plus the $7 just logged.
                    StateCard(StateCardModel.Receipt("Store", T0, emptyList(), null, 9.0), null, strip.amountCents)
                }
            }
        }
        settle()
        compose.onNodeWithText("Cash tip $7.00 pending").assertIsDisplayed()
        compose.onNodeWithText("Cash tip $9.00 pending").assertDoesNotExist()
    }
    @Test fun `pending cash tip foot follows confirmed projection lag`() {
        val pending = mutableStateOf<Long?>(700L)
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    StateCard(StateCardModel.DropoffArrived(facts, T0), null, pending.value)
                }
            }
        }
        settle()
        compose.onNodeWithText("Cash tip $7.00 pending").assertIsDisplayed()
        compose.runOnIdle { pending.value = null }
        settle()
        compose.onNodeWithTag("state.foot.cashTip").assertDoesNotExist()
    }

}
