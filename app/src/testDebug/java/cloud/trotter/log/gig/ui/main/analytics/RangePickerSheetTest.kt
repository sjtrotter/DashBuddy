package cloud.trotter.log.gig.ui.main.analytics

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.R as DesignR
import cloud.trotter.log.gig.core.designsystem.motion.LocalReducedMotion
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.domain.analytics.AnalyticsWindows
import cloud.trotter.log.gig.domain.analytics.DailyEarnings
import cloud.trotter.log.gig.domain.analytics.WindowGranularity
import cloud.trotter.log.gig.ui.ScreenshotData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.YearMonth

/**
 * The range picker's earning dots are records figures: they and their legend show only when the
 * records are ready, and a failed month read offers Retry in the legend's place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RangePickerSheetTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private val today = ScreenshotData.today
    private val days = listOf(
        DailyEarnings(today.minusDays(1), gross = 80.0),
        DailyEarnings(today.minusDays(2), gross = 40.0),
    )

    @Test fun `earning dots and their legend follow the marks`() {
        var marks by mutableStateOf(PickerMarks.SHOWN)
        var retries = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(
                    LocalNowProvider provides ScreenshotData.clock,
                    LocalReducedMotion provides true,
                ) {
                    RangePickerSheet(
                        window = AnalyticsWindows.current(WindowGranularity.WEEK, today),
                        today = today,
                        month = YearMonth.from(today),
                        monthDays = days,
                        marks = marks,
                        onRetry = { retries++ },
                        onMonthChange = {}, onPickPreset = { _, _ -> }, onCommitRange = { _, _ -> }, onDismiss = {},
                    )
                }
            }
        }
        settle()
        val legend = context.getString(R.string.analytics_picker_dot_legend)
        val failed = context.getString(
            DesignR.string.app_could_not_load,
            context.getString(R.string.analytics_picker_could_not_load_what),
        )

        // Shown: the fixture's two earning days carry dots, under the legend.
        compose.onAllNodesWithTag(EARNING_DOT_TAG, useUnmergedTree = true).assertCountEquals(days.size)
        compose.onNodeWithText(legend).assertExists()

        // Rebuilding: no dot and no legend, though the month read still holds days.
        marks = PickerMarks.HELD_BACK
        settle()
        compose.onAllNodesWithTag(EARNING_DOT_TAG, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText(legend).assertCountEquals(0)

        // Failed: no dot; Couldn't load in the legend's place, and Retry asks again.
        marks = PickerMarks.FAILED
        settle()
        compose.onAllNodesWithTag(EARNING_DOT_TAG, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText(failed).assertExists()
        compose.onNodeWithText(context.getString(DesignR.string.app_retry)).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, retries)
        assertTrue(compose.onAllNodesWithText(legend).fetchSemanticsNodes().isEmpty())
    }

    /** Publishes test-thread state writes, then runs two seconds of frames (the sheet slides in). */
    private fun settle() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }
}
