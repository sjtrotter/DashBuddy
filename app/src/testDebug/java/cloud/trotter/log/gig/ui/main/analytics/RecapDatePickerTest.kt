package cloud.trotter.log.gig.ui.main.analytics

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.ui.ScreenshotData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Locale

/**
 * #1515: the date picker's content under a controlled clock (Wednesday 2026-10-28, [ScreenshotData.clock]). Future
 * dates are disabled, the period lines follow the selection, and each choice hands back what it opens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecapDatePickerTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private val today = ScreenshotData.today
    private val weeks = mutableListOf<LocalDate>()
    private val months = mutableListOf<YearMonth>()
    private lateinit var locale: Locale

    @Before fun fixLocale() {
        locale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After fun restoreLocale() = Locale.setDefault(locale)

    private fun show(contextDate: LocalDate, choices: List<DatePickerChoice>) {
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides ScreenshotData.clock) {
                    RecapDatePickerContent(
                        state = rememberRecapDatePickerState(contextDate, today),
                        choices = choices,
                        today = today,
                        onCancel = {},
                        onWeek = { weeks += it },
                        onMonth = { months += it },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** A day cell: Material names it with the full date ("Thursday, October 29, 2026"). */
    private fun day(date: String) = compose.onNode(hasText(date, substring = true) or hasContentDescription(date, substring = true))

    private fun button(id: Int) = compose.onNode(hasText(context.getString(id)) and hasClickAction())

    @Test fun `dates after today are disabled`() {
        show(LocalDate.of(2026, 10, 26), listOf(DatePickerChoice.WEEK, DatePickerChoice.MONTH))
        day("October 27, 2026").assertIsEnabled()
        day("October 29, 2026").assertIsNotEnabled()
        day("October 31, 2026").assertIsNotEnabled()
        val dates = UpToToday(today)
        val millis = { date: LocalDate -> date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
        assertTrue(dates.isSelectableDate(millis(today)))
        assertFalse(dates.isSelectableDate(millis(today.plusDays(1))))
        assertFalse(dates.isSelectableYear(today.year + 1))
    }

    @Test fun `the period lines follow the selection and each choice hands back its period`() {
        show(LocalDate.of(2026, 10, 26), listOf(DatePickerChoice.WEEK, DatePickerChoice.MONTH))
        val periods = compose.onNodeWithTag(PICKER_PERIODS_TAG)
        periods.assert(hasText("Oct 26 to Nov 1", substring = true)).assert(hasText("October 2026", substring = true))

        day("October 14, 2026").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        periods.assert(hasText("Oct 12 to 18", substring = true))

        button(R.string.recap_picker_week).performSemanticsAction(SemanticsActions.OnClick)
        button(R.string.recap_picker_month).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(listOf(LocalDate.of(2026, 10, 14)), weeks)
        assertEquals(listOf(YearMonth.of(2026, 10)), months)
    }

    @Test fun `only the offered choices show`() {
        show(LocalDate.of(2026, 10, 1), listOf(DatePickerChoice.MONTH))
        button(R.string.recap_picker_week).assertDoesNotExist()
        button(R.string.recap_picker_month).assertIsEnabled()
        compose.onNodeWithTag(PICKER_PERIODS_TAG).assert(hasText("October 2026", substring = true))
            .assert(hasText(context.getString(R.string.recap_picker_week)).not())
    }

    @Test fun `a context date after today opens on today`() {
        show(LocalDate.of(2026, 11, 20), listOf(DatePickerChoice.WEEK))
        compose.onNodeWithTag(PICKER_PERIODS_TAG).assert(hasText("Oct 26 to Nov 1", substring = true))
    }
}
