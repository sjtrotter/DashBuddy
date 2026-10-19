package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.core.designsystem.time.LocalNowProvider
import cloud.trotter.dashbuddy.feature.bubble.MutableClock
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HudSheetHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val clock = MutableClock(1_700_000_000_000L)
    private var open by mutableStateOf(false)
    private var mode by mutableStateOf(HudMode.LIVE)
    private var composed = false
    private var dismissals = 0

    @Before fun setup() { compose.mainClock.autoAdvance = false }
    private fun settle() { compose.waitForIdle(); compose.mainClock.advanceTimeBy(100) }
    private fun show(reduced: Boolean = true, undo: Boolean = false) {
        compose.setContent {
            DashBuddyTheme {
                CompositionLocalProvider(LocalNowProvider provides clock, LocalReducedMotion provides reduced,
                    LocalHudSheetBackHandler provides { enabled, dismiss ->
                        val latest by rememberUpdatedState(dismiss)
                        DisposableEffect(enabled) {
                            val callback = object : OnBackPressedCallback(enabled) {
                                override fun handleOnBackPressed() { latest() }
                            }
                            compose.activity.onBackPressedDispatcher.addCallback(callback)
                            onDispose { callback.remove() }
                        }
                    }) {
                    Box(Modifier.width(400.dp).height(760.dp)) {
                        HudScreen(polishState(mode), null, {}, sheetOpen = open,
                            onSheetDismiss = { dismissals++; open = false },
                            sheet = { SideEffect { composed = true }; Text("Sheet content") },
                            undo = if (undo) ({ Text("Undo strip") }) else null,
                            stateCard = { Text("State content") })
                    }
                }
            }
        }
        settle()
    }
    @Test fun sheetClosed_composesNothing() {
        show()
        compose.onNodeWithTag("hud.sheet").assertDoesNotExist()
        assertFalse(composed)
    }
    @Test fun sheetOpen_spansBodyToTheDock() {
        open = true; show()
        val sheet = compose.onNodeWithTag("hud.sheet").getBoundsInRoot()
        val body = compose.onNodeWithTag("hud.body").getBoundsInRoot()
        val dock = compose.onNodeWithTag("hud.dock").getBoundsInRoot()
        assertEquals(body, sheet)
        assertEquals(dock.top, sheet.bottom)
        val hero = compose.onNodeWithTag("hud.hero").getBoundsInRoot()
        assertTrue(sheet.top >= hero.bottom)
    }
    @Test fun sheetOpen_hasDragHandle() {
        open = true; show()
        compose.onNodeWithTag("hud.sheet.handle", useUnmergedTree = true).assertIsDisplayed()
    }
    @Test fun sheetHiddenInOfferMode() {
        open = true; mode = HudMode.OFFER; show()
        compose.onNodeWithTag("hud.sheet").assertDoesNotExist()
        assertFalse(composed)
    }
    @Test fun back_callsOnSheetDismiss() {
        open = true; show()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }; settle()
        assertEquals(1, dismissals)
        compose.onNodeWithTag("hud.sheet").assertDoesNotExist()
    }
    @Test fun reducedMotion_sheetJumpCuts() {
        show()
        compose.runOnIdle { open = true }; settle()
        assertEquals(compose.onNodeWithTag("hud.body").getBoundsInRoot(),
            compose.onNodeWithTag("hud.sheet").getBoundsInRoot())
        compose.runOnIdle { open = false }; settle()
        compose.onNodeWithTag("hud.sheet").assertDoesNotExist()
    }
    @Test fun undoSlot_rendersInBandAboveDock() {
        show(undo = true)
        val band = compose.onNodeWithTag("hud.slot.undo")
        band.assertHeightIsEqualTo(48.dp)
        compose.onNodeWithText("Undo strip").assertIsDisplayed()
        assertTrue(band.getBoundsInRoot().bottom <= compose.onNodeWithTag("hud.dock").getBoundsInRoot().top)
    }
    @Test fun undoSlot_absentInLogMode() {
        mode = HudMode.LOG; show(undo = true)
        compose.onNodeWithTag("hud.slot.undo").assertDoesNotExist()
        compose.onNodeWithText("Undo strip").assertDoesNotExist()
    }
    @Test fun sheetHiddenInLogMode() {
        open = true; mode = HudMode.LOG; show()
        compose.onNodeWithTag("hud.sheet").assertDoesNotExist()
    }
    @Test fun undoSlot_liveReservedAndRestingConditional() {
        show()
        compose.onNodeWithTag("hud.slot.undo").assertHeightIsEqualTo(48.dp)
        compose.runOnIdle { mode = HudMode.RESTING }; settle()
        compose.onNodeWithTag("hud.slot.undo").assertDoesNotExist()
    }
    @Test fun `a stable undo slot follows notice visibility at rest`() {
        var noticeVisible by mutableStateOf(false)
        compose.setContent {
            DashBuddyTheme {
                val content: @Composable () -> Unit = remember { { Text("Saved reading") } }
                val visible: @Composable () -> Boolean = remember { { noticeVisible } }
                Column { UndoSlot(HudMode.RESTING, content, visible) }
            }
        }
        settle()
        compose.onNodeWithTag("hud.slot.undo").assertDoesNotExist()
        compose.runOnIdle { noticeVisible = true }; settle()
        compose.onNodeWithTag("hud.slot.undo").assertHeightIsEqualTo(48.dp)
        compose.onNodeWithText("Saved reading").assertIsDisplayed()
        compose.runOnIdle { noticeVisible = false }; settle()
        compose.onNodeWithTag("hud.slot.undo").assertDoesNotExist()
    }

    @Test fun sheetMotion_usesFrameClockAndDismissesAfterExit() {
        show(reduced = false)
        compose.runOnIdle { open = true }; settle()
        clock.millis += 60_000
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("hud.sheet").assertIsDisplayed()
        compose.runOnIdle { open = false }; settle()
        // At a mid-exit frame the visible bounds must remain inside the body, above the dock.
        compose.onNodeWithTag("hud.sheet").assertExists()
        val frame = compose.onNodeWithTag("hud.body").getBoundsInRoot()
        val exiting = compose.onNodeWithTag("hud.sheet").getBoundsInRoot()
        assertTrue(exiting.top > frame.top)
        assertTrue(exiting.top >= frame.top && exiting.bottom <= frame.bottom)
        assertTrue(exiting.left >= frame.left && exiting.right <= frame.right)
        assertTrue(exiting.bottom <= compose.onNodeWithTag("hud.dock").getBoundsInRoot().top)
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("hud.sheet").assertDoesNotExist()
    }
}
