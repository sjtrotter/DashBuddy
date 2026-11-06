package cloud.trotter.log.gig.ui.main.dashboard

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.domain.format.formatClockTime
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.ui.ScreenshotData
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZoneId

/** #1533: the end-shift sheet's confirm switch, bounds and prefill, on a clock the test owns. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EndShiftSheetTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private val now = ScreenshotData.clock.millis()
    private val candidate = EndShiftCandidate(
        platform = Platform.DoorDash,
        sessionId = "session-A",
        startedAt = now - 3_600_000L,
        lastActivityAt = now - 1_200_000L,
    )
    private val confirms = mutableListOf<Pair<String, Long>>()

    private fun show(initial: EndShiftForm) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            var form by remember { mutableStateOf(initial) }
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides ScreenshotData.clock) {
                    EndShiftContent(
                        candidates = listOf(candidate),
                        form = form,
                        onFormChange = { form = it },
                        onConfirm = { shift, endAt -> confirms += shift.sessionId to endAt },
                        onDismiss = {},
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
    }

    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun endButton() = compose.onNodeWithText("End shift at", substring = true)

    @Test
    fun `the end button is disabled until the switch is on and the suggested time is prefilled`() {
        show(EndShiftForm(sessionId = candidate.sessionId))
        compose.onNodeWithText(formatClockTime(candidate.suggestedEnd, ZoneId.systemDefault())).assertExists()
        compose.onNodeWithText(string(R.string.end_shift_suggested)).assertExists()
        endButton().assertIsNotEnabled()

        compose.onNodeWithText(string(R.string.end_shift_confirm_switch, "DoorDash"))
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(2_000)
        endButton().assertIsEnabled()

        endButton().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(candidate.sessionId to candidate.suggestedEnd), confirms)
    }

    @Test
    fun `a time before the shift started shows the error and disables the button`() {
        show(EndShiftForm(sessionId = candidate.sessionId, confirmed = true, endAt = candidate.startedAt - 60_000L))
        compose.onNodeWithText("Pick a time after the shift started", substring = true).assertExists()
        compose.onNodeWithText(string(R.string.end_shift_suggested)).assertDoesNotExist()
        endButton().assertIsNotEnabled()
    }

    @Test
    fun `a time after now shows the error and disables the button`() {
        show(EndShiftForm(sessionId = candidate.sessionId, confirmed = true, endAt = now + 3_600_000L))
        compose.onNodeWithText("Pick a time before now", substring = true).assertExists()
        endButton().assertIsNotEnabled()
    }

    @Test
    fun `a time before the last recorded activity warns but allows the end`() {
        show(EndShiftForm(sessionId = candidate.sessionId, confirmed = true, endAt = candidate.startedAt + 60_000L))
        compose.onNodeWithText(string(R.string.end_shift_warning_activity)).assertExists()
        endButton().assertIsEnabled()
    }
}
