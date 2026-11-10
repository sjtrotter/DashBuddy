package cloud.trotter.log.gig.ui.main.analytics.shifts

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.domain.format.formatWeekdayMonthDay
import cloud.trotter.log.gig.ui.ScreenshotData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * #1515: the Shifts list opens at its week, shows an empty target week as its header and "No shifts", opens a shift
 * on a tap (at the check's row when it has one), and keeps two shifts on one date as two rows. The fixture is
 * [ScreenshotData.shifts] (a Wednesday, 2026-10-28) under a controlled clock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShiftsListTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private val thisWeek = LocalDate.of(2026, 10, 26)
    private val opened = mutableListOf<Pair<String, String?>>()
    private val reported = mutableListOf<Set<LocalDate>>()

    /** The state shown; a test replaces it as the ViewModel would (the date picker's jump). */
    private val shown = mutableStateOf(ScreenshotData.shifts())

    private fun show(state: ShiftsUiState) {
        shown.value = state
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides ScreenshotData.clock) {
                    ShiftsContent(
                        uiState = shown.value,
                        onBack = {},
                        onOpenSession = { id, row -> opened.add(id to row) },
                        onVisibleWeeks = { reported.add(it) },
                        onRetry = {},
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        // The visible-weeks collector resumes after the first layout; let it run on the frozen clock.
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    /** A lazy row is composed only once in view; scrolling to its key jumps there (no animation on the frozen clock). */
    private fun scrollTo(key: String) {
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag(SHIFTS_LIST_TAG).performScrollToKey(key)
        compose.mainClock.autoAdvance = false
    }

    @Test fun `it opens with the target week's header first in view and reports that week`() {
        val lastWeek = thisWeek.minusWeeks(1)
        show(ScreenshotData.shifts(target = lastWeek))
        compose.onNodeWithTag("shifts-week-$lastWeek").assertIsDisplayed()
        // The newer week's header sits above the opening position, so the list never composed it. Its last rows can
        // show above the target when too little follows the target to scroll it to the very top (LazyColumn clamps at
        // the end; an accepted limit, #1515 PR B review): the target header is in view either way.
        compose.onNodeWithTag("shifts-week-$thisWeek").assertDoesNotExist()
        assertTrue("reported: $reported", reported.lastOrNull()?.contains(lastWeek) == true)
    }

    @Test fun `an empty target week shows its header and No shifts`() {
        val empty = thisWeek.minusWeeks(2)
        show(ScreenshotData.shifts(target = empty))
        compose.onNodeWithTag("shifts-week-$empty").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.shifts_week_none)).assertIsDisplayed()
    }

    @Test fun `the date picker's jump puts the picked week's header first, an empty week showing No shifts`() {
        show(ScreenshotData.shifts())
        compose.onNodeWithTag("shifts-week-$thisWeek").assertIsDisplayed()
        val empty = thisWeek.minusWeeks(2)
        shown.value = ScreenshotData.shifts(target = empty).copy(jumps = 1)
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithTag("shifts-week-$empty").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.shifts_week_none)).assertIsDisplayed()
        // The header is the first item on screen: the newer weeks sit above it, out of view.
        compose.onNodeWithTag("shifts-week-$thisWeek").assertDoesNotExist()
        assertTrue("reported: $reported", reported.lastOrNull()?.contains(empty) == true)
    }

    @Test fun `a new target without a jump does not move the list`() {
        show(ScreenshotData.shifts())
        shown.value = ScreenshotData.shifts(target = thisWeek.minusWeeks(2))
        settle()
        compose.onNodeWithTag("shifts-week-$thisWeek").assertIsDisplayed()
    }

    @Test fun `a second jump to the week already picked scrolls back to its header`() {
        show(ScreenshotData.shifts())
        scrollTo("shift:o1")
        compose.onNodeWithTag("shifts-week-$thisWeek").assertDoesNotExist()
        shown.value = shown.value.copy(jumps = 1)
        settle()
        compose.onNodeWithTag("shifts-week-$thisWeek").assertIsDisplayed()
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    @Test fun `a tap opens the shift, at the check's row when it has one`() {
        show(ScreenshotData.shifts())
        scrollTo("shift:s1")
        compose.onNodeWithTag("shifts-row-s1").performClick()
        compose.mainClock.advanceTimeBy(2_000)
        scrollTo("shift:s2")
        compose.onNodeWithTag("shifts-row-s2").performClick()
        compose.mainClock.advanceTimeBy(2_000)
        assertEquals(listOf("s1" to "offer:77", "s2" to null), opened)
    }

    @Test fun `two shifts on one date, two apps, are two rows`() {
        show(ScreenshotData.shifts())
        scrollTo("shift:u1")
        compose.onNodeWithTag("shifts-row-u1").assertIsDisplayed()
        compose.onNodeWithTag("shifts-row-s1").assertIsDisplayed()
        compose.onAllNodesWithText(formatWeekdayMonthDay(thisWeek)).assertCountEquals(2)
    }

    @Test fun `a running shift reads to now, and a shift with no pay found reads the em dash`() {
        show(ScreenshotData.shifts())
        compose.onNodeWithTag("shifts-row-s4").assert(hasText("to now", substring = true))
        scrollTo("shift:l5")
        compose.onNodeWithTag("shifts-row-l5").assert(hasText(EMPTY_VALUE)).assert(hasText("$0.00").not())
    }

    @Test fun `a week with no shift other than the target does not appear`() {
        val entries = shiftsEntries(ScreenshotData.shifts().weeks)
        assertEquals(
            listOf(thisWeek, thisWeek.minusWeeks(1), thisWeek.minusWeeks(3)),
            entries.filterIsInstance<ShiftsEntry.Header>().map { it.week },
        )
        assertTrue(entries.none { it is ShiftsEntry.NoShifts })
        show(ScreenshotData.shifts())
        scrollTo("shift:o1")
        compose.onNodeWithTag("shifts-row-o1").assertIsDisplayed()
    }
}
