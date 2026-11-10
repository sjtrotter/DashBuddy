package cloud.trotter.log.gig.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.ui.main.analytics.ShiftPageActions
import cloud.trotter.log.gig.ui.main.analytics.ShiftPageContent
import cloud.trotter.log.gig.ui.main.analytics.ShiftPageFixtures
import cloud.trotter.log.gig.ui.main.analytics.search.SearchContent
import cloud.trotter.log.gig.ui.main.analytics.search.SearchFixtures
import cloud.trotter.log.gig.ui.main.analytics.search.SearchUiState
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import java.util.TimeZone

/**
 * The store search for PR CI's screenshots (#1463's procedure, [renderShot]): every shift's results (two groups on one
 * date, one of them a stack), no match, and a shift page filtered in place, at 100 % and 200 % text, light and dark.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = RobolectricDeviceQualifiers.Pixel7)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class SearchScreenshotsTest(private val theme: String) {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var locale: Locale
    private lateinit var timeZone: TimeZone

    @Before fun fixLocaleAndTimeZone() {
        locale = Locale.getDefault()
        timeZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After fun restoreLocaleAndTimeZone() {
        Locale.setDefault(locale)
        TimeZone.setDefault(timeZone)
    }

    private val allShifts get() = context.getString(R.string.search_scope_all)
    private val thisShift get() = context.getString(R.string.search_scope_this)
    private val earned get() = context.getString(R.string.shift_page_earned_label)
    private val noMatch get() = context.getString(R.string.search_no_match_format, "Wingstop")

    @Test fun results() = search("search/results", "Chipotle", SearchFixtures.results(), expect = "Five Guys & Chipotle")

    @Test @Config(fontScale = 2f)
    fun resultsFont200() = search("search/results-font-200", "Chipotle", SearchFixtures.results(), expect = allShifts)

    @Test fun noMatch() = search("search/no-match", "Wingstop", SearchFixtures.noMatch(), expect = noMatch)

    @Test @Config(fontScale = 2f)
    fun noMatchFont200() = search("search/no-match-font-200", "Wingstop", SearchFixtures.noMatch(), expect = noMatch)

    @Test fun thisShift() = shiftPage("search/this-shift", expect = thisShift)

    /** At 200 % text the filtered timeline starts below the fold; the shot asserts the page's first figure instead. */
    @Test @Config(fontScale = 2f)
    fun thisShiftFont200() = shiftPage("search/this-shift-font-200", expect = earned)

    private fun search(path: String, query: String, state: SearchUiState, expect: String) =
        compose.renderShot(theme, path, window = false, assertion = { assertShows(expect) }) {
            SearchContent(query, state, onQueryChange = {}, onBack = {}, onOpenSession = { _, _ -> })
        }

    private fun shiftPage(path: String, expect: String) =
        compose.renderShot(theme, path, window = false, assertion = { assertShows(expect) }) { FilteredShift() }

    @Composable
    private fun FilteredShift() = ShiftPageContent(
        ShiftPageFixtures.stackAndAddOn(), null, ShiftPageActions(onBack = {}), searchOpen = true, query = "Chipotle",
    )

    private fun assertShows(text: String) {
        compose.onAllNodesWithText(text, substring = true)[0].assertExists()
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes(): List<Array<Any>> = listOf(arrayOf<Any>("light"), arrayOf<Any>("dark"))
    }
}
