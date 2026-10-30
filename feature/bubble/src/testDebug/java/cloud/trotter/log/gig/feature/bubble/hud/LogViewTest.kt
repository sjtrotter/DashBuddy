package cloud.trotter.log.gig.feature.bubble.hud

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.feature.bubble.MutableClock
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LogViewTest {
    @get:Rule val compose = createComposeRule()
    private val clock = MutableClock(T0 + 239_000)
    private lateinit var locale: Locale
    @Before fun setup() {
        locale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        compose.mainClock.autoAdvance = false
    }
    @After fun cleanup() { Locale.setDefault(locale) }

    private fun card(lines: List<JobLine> = listOf(JobLine.Now(NowPhase.DROPOFF_ARRIVED, T0, T0 + 240_000, 8.1)),
        live: Boolean = true) = JobCardModel("job", "Panera Bread", 81, ScoreTone.GOOD,
        10.6, 4.2, lines, live, T0)
    private fun row(id: Int = 1) = DispatcherRow("chat:$id", T0 + id, "Dash Paused!")
    private fun show(items: List<LogItem>, onNow: () -> Unit = {}, onUndo: (UndoTarget) -> Unit = {}) {
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    Box(Modifier.size(360.dp, 400.dp)) { LogView(items, onNow, onUndo) }
                }
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
    private fun nowText(text: String) = compose.onNode(hasText(text, substring = true) and hasAnyAncestor(hasTestTag("hud.log.now")),
        useUnmergedTree = true)

    @Test fun `short list sits against the bottom edge`() {
        show(listOf(row()))
        val list = compose.onNodeWithTag("hud.log.list").getBoundsInRoot()
        val row = compose.onNodeWithTag("hud.log.chat:1").getBoundsInRoot()
        assertEquals(list.bottom.value, row.bottom.value, 1f)
        assertTrue(row.top > list.top)
    }
    @Test fun `live job card is the bottom item`() {
        show(listOf(row(), card()))
        val list = compose.onNodeWithTag("hud.log.list").getBoundsInRoot()
        val card = compose.onNodeWithTag("hud.log.job:job").getBoundsInRoot()
        val row = compose.onNodeWithTag("hud.log.chat:1").getBoundsInRoot()
        assertEquals(list.bottom.value, card.bottom.value, 1f)
        assertTrue(row.bottom <= card.top)
    }
    @Test fun `Now line ticks under the test clock`() {
        show(listOf(card()))
        nowText("3:59 at the door").assertExists()
        tick(T0 + 239_999)
        nowText("3:59 at the door").assertExists()
        tick(T0 + 240_000)
        nowText("4 min at the door").assertExists()
        tick(T0 + 300_000)
        nowText("5 min at the door").assertExists()
    }
    @Test fun `tapping the Now line returns to the live surface`() {
        val open = mutableStateOf(true)
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    Box(Modifier.size(360.dp, 400.dp)) {
                        if (open.value) LogView(listOf(card()), onNow = { open.value = false }, onUndo = {})
                        else Text("Live surface")
                    }
                }
            }
        }
        settle()
        compose.onNodeWithTag("hud.log.now").performClick()
        settle()
        compose.onNodeWithText("Live surface").assertIsDisplayed()
        compose.onNodeWithTag("hud.log").assertDoesNotExist()
    }
    @Test fun `fade shows when history overflows the top`() {
        show((1..40).map { row(it) } + card())
        compose.onNodeWithTag("hud.log.fade").assertIsDisplayed()
    }
    @Test fun `no fade when everything fits`() {
        show(listOf(row()))
        compose.onNodeWithTag("hud.log.fade").assertDoesNotExist()
    }
    @Test fun `Undo is visible while the window is open and gone after it closes`() {
        val window = UndoWindow(UndoTarget.CashTip(null, "drop"), 5.0, clock.millis + 10_000)
        val items = mutableStateOf<List<LogItem>>(listOf(card()))
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) {
                    Box(Modifier.size(360.dp, 400.dp)) { LogView(items.value, {}, {}) }
                }
            }
        }
        settle()
        compose.runOnIdle { items.value = listOf(card(listOf(JobLine.CashTip(T0, 5.0, true, window)))) }
        settle() // Deliver the state write before moving either clock.
        compose.onNodeWithTag("hud.log.undo").assertIsDisplayed()
        tick(window.closesAt - 1)
        compose.onNodeWithTag("hud.log.undo").assertIsDisplayed()
        tick(window.closesAt)
        compose.onNodeWithTag("hud.log.undo").assertDoesNotExist()
        compose.onNodeWithText("pending").assertIsDisplayed()
    }
    @Test fun `Undo click reports its target`() {
        val target = UndoTarget.CashTip(10, "drop")
        val window = UndoWindow(target, 5.0, clock.millis + 10_000)
        var clicked: UndoTarget? = null
        show(listOf(card(listOf(JobLine.CashTip(T0, 5.0, false, window)), false)), onUndo = { clicked = it })
        compose.onNodeWithContentDescription("Undo cash tip $5.00").performClick()
        settle()
        assertEquals(target, clicked)
    }
    @Test fun `fuel renders the price or total without a minus`() {
        show(listOf(FuelRow(1, T0, 31.9, 3.19), FuelRow(2, T0 + 1, 31.9, null)))
        compose.onNodeWithText("Gas price $3.19/gal").assertIsDisplayed()
        compose.onNodeWithText("Gas $31.90").assertIsDisplayed()
        compose.onNodeWithText("-", substring = true).assertDoesNotExist()
    }
    @Test fun `declined offer renders pay and miles`() {
        show(listOf(OfferRow("offer:decline", T0, "Panera Bread", 3.25, 6.8, AppEventType.OFFER_DECLINED)))
        compose.onNodeWithText("Declined Panera Bread").assertIsDisplayed()
        compose.onNodeWithText("$3.25").assertIsDisplayed()
        compose.onNodeWithText("6.8 mi").assertIsDisplayed()
    }
    private companion object { const val T0 = 1_700_000_000_000L }
}
