package cloud.trotter.log.gig.ui.main.analytics.search

import cloud.trotter.log.gig.domain.state.Platform
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.motion.LocalReducedMotion
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.domain.format.formatClockTime
import cloud.trotter.log.gig.domain.format.formatWeekdayMonthDay
import cloud.trotter.log.gig.ui.ScreenshotData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.TimeZone

/**
 * #1540: the Search screen states its scope once the query has text, says "no match" only once the query's own answer
 * is in, keeps two shifts of one date (and two apps) as two groups, and opens a result at its row: a job with its
 * sheet, a passed offer at its thin row, a header at the top.
 */
@RunWith(RobolectricTestRunner::class)
/** A tall window, so both groups are composed. */
@Config(sdk = [35], qualifiers = "w411dp-h2000dp-420dpi")
class SearchScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)
    private val opened = mutableListOf<Pair<String, String?>>()
    private lateinit var locale: Locale
    private lateinit var zone: TimeZone

    @Before fun fix() {
        locale = Locale.getDefault()
        zone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After fun restore() {
        Locale.setDefault(locale)
        TimeZone.setDefault(zone)
    }

    /** The state shown; a test replaces it as the ViewModel would when the query's answer arrives. */
    private val shown = mutableStateOf(SearchUiState())

    private fun show(query: String, state: SearchUiState) {
        shown.value = state
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides ScreenshotData.clock, LocalReducedMotion provides true) {
                    SearchContent(query, shown.value, onQueryChange = {}, onBack = {}, onOpenSession = { id, row -> opened += id to row })
                }
            }
        }
        settle()
    }

    private fun settle() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    /** "Tue, Oct 27 · Uber, 11:10 AM to 1:40 PM", in the formatters' own spacing. */
    private fun header(app: String, start: String, end: String): String {
        val day = formatWeekdayMonthDay(LocalDate.of(2026, 10, 27))
        val times = string(R.string.shift_page_app_times_format, app, clock(start), clock(end))
        return string(R.string.search_group_header_format, day, times)
    }

    private fun clock(utc: String) = formatClockTime(Instant.parse("2026-10-27T${utc}:00Z").toEpochMilli())

    private fun SemanticsNodeInteraction.click() {
        performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    private fun count(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    @Test fun `an empty query lists nothing and states no scope`() {
        show("  ", SearchUiState())
        assertEquals(0, count(string(R.string.search_scope_all)))
    }

    @Test fun `the scope label sits above the results, grouped by shift and app`() {
        show("Chipotle", SearchFixtures.results())
        assertEquals(1, count(string(R.string.search_scope_all)))
        // Two shifts on Tue, Oct 27: two groups, each naming its app.
        assertEquals(1, count(header(Platform.DoorDash.displayName, "16:58", "23:04")))
        assertEquals(1, count(header(Platform.Uber.displayName, "11:10", "13:40")))
        // The stack shows whole: its card, not its Chipotle drop alone.
        assertEquals(1, count("Five Guys & Chipotle"))
        assertEquals(1, count("Chipotle · Declined · $5.25 · 2.2 mi"))
        assertEquals(0, count(string(R.string.search_no_match_format, "Chipotle")))
    }

    @Test fun `no match says so once the query's answer is in, and not while it loads`() {
        show("Wingstop", SearchUiState())
        assertEquals(1, count(string(R.string.search_scope_all)))
        assertEquals(0, count(string(R.string.search_no_match_format, "Wingstop")))

        shown.value = SearchFixtures.noMatch()
        settle()
        assertEquals(1, count("No offers or deliveries from \"Wingstop\""))
    }

    @Test fun `a result opens its shift at its row`() {
        show("chipotle", SearchFixtures.results())
        compose.onNodeWithText("Five Guys & Chipotle").click()
        compose.onNodeWithText("Chipotle · Declined", substring = true).click()
        compose.onNodeWithTag("search-group-${SearchFixtures.DOORDASH_SHIFT}").click()
        assertEquals(
            listOf(
                SearchFixtures.DOORDASH_SHIFT to SearchFixtures.STACK_KEY,
                SearchFixtures.UBER_SHIFT to SearchFixtures.UBER_OFFER_KEY,
                SearchFixtures.DOORDASH_SHIFT to null,
            ),
            opened,
        )
    }

    @Test fun `a failed read shows the couldn't-load pattern`() {
        show("chipotle", SearchUiState(failed = true))
        assertEquals(1, count(string(R.string.search_could_not_load_what)))
        assertEquals(0, count(string(R.string.search_scope_all)))
    }
}
