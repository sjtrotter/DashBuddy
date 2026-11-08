package cloud.trotter.log.gig.ui.main.analytics

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.ui.ScreenshotData
import cloud.trotter.log.gig.ui.main.analytics.recap.DayTap
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * #1515: Recap's day rows route as the issue says. A one-shift day opens that shift, a two-shift day opens the day
 * page, a day with no shift does nothing, and a day ahead draws no value. The fixture is a Wednesday
 * ([ScreenshotData.today]) under a controlled clock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecapDayTapTest {
    @get:Rule val compose = createComposeRule()

    private val taps = mutableListOf<DayTap>()

    private fun show(state: AnalyticsUiState) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides ScreenshotData.clock) {
                    AnalyticsContent(
                        uiState = state, onOpenSettings = {}, onStep = {}, onSelectMeasure = {}, onOpenDay = { taps += it },
                        onOpenShift = { _, _ -> }, onOpenNoSession = {}, onRetry = {},
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
    }

    /**
     * `performScrollTo` animates its scroll, and on the frozen frame clock that animation never ends (the test hung
     * until CI's job timeout). Frames advance only for the scroll; the wall clock stays [ScreenshotData.clock].
     */
    private fun row(date: String): SemanticsNodeInteraction {
        compose.mainClock.autoAdvance = true
        val node = compose.onNodeWithTag("recap-day-$date").performScrollTo()
        compose.mainClock.autoAdvance = false
        return node
    }

    @Test fun `a one-shift day opens that shift`() {
        show(ScreenshotData.recap())
        row("2026-10-26").assertHasClickAction().performClick()
        compose.mainClock.advanceTimeBy(2_000)
        assertEquals(listOf<DayTap>(DayTap.Shift("s1")), taps)
    }

    @Test fun `a two-shift day opens the day page`() {
        show(ScreenshotData.recap())
        row("2026-10-27").performClick()
        compose.mainClock.advanceTimeBy(2_000)
        assertEquals(listOf<DayTap>(DayTap.Day(LocalDate.of(2026, 10, 27))), taps)
    }

    @Test fun `a day with no shift does nothing`() {
        // Last week's Thursday had no shift.
        show(ScreenshotData.recapPastWeek())
        row("2026-10-22").assertHasNoClickAction()
        assertEquals(emptyList<DayTap>(), taps)
    }

    @Test fun `a day ahead draws no value and keeps last week's`() {
        show(ScreenshotData.recap())
        val friday = row("2026-10-30").assertHasNoClickAction().fetchSemanticsNode()
        assertEquals(
            listOf("Friday, last week ${Formats.money(74.40)}"),
            friday.config[SemanticsProperties.ContentDescription],
        )
    }
}
