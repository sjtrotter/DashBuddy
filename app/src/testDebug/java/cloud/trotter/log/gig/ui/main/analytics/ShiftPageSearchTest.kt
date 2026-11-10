package cloud.trotter.log.gig.ui.main.analytics

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.motion.LocalReducedMotion
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.ui.ScreenshotData
import cloud.trotter.log.gig.ui.main.analytics.search.SEARCH_FIELD_TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.TimeZone

/**
 * #1540: the shift page's search filters the timeline in place. The summary stays, "This shift" sits above the
 * matching rows, a passed offer comes out of its run alone, no match says so, the last row searches every shift, and
 * Back closes the search before it leaves the page.
 */
@RunWith(RobolectricTestRunner::class)
/** A tall window, so the whole fixture page is composed. */
@Config(sdk = [35], qualifiers = "w411dp-h2000dp-420dpi")
class ShiftPageSearchTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)
    private lateinit var locale: Locale
    private lateinit var zone: TimeZone
    private var backs = 0
    private val searchedAll = mutableListOf<String>()

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

    private fun show(searchOpen: Boolean = false, query: String = "") {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides ScreenshotData.clock, LocalReducedMotion provides true) {
                    ShiftPageContent(
                        ShiftPageFixtures.stackAndAddOn(), null,
                        ShiftPageActions(onBack = { backs++ }, onSearchAll = { searchedAll += it }),
                        searchOpen = searchOpen, query = query,
                    )
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

    private fun SemanticsNodeInteraction.click() {
        performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    private fun type(text: String) {
        compose.onNodeWithTag(SEARCH_FIELD_TAG).performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(text)) }
        settle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
    }

    private fun count(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    @Test fun `the filter keeps the summary, states its scope and offers every shift`() {
        show(searchOpen = true, query = "chipotle ")
        assertEquals(1, count(string(R.string.shift_page_earned_label)))
        assertEquals(1, count(string(R.string.search_scope_this)))
        assertEquals(1, count("Five Guys & Chipotle"))
        assertEquals(0, count("Sample Burgers"))
        compose.onNodeWithText("Search all shifts for \"chipotle\"").click()
        assertEquals(listOf("chipotle"), searchedAll)
    }

    @Test fun `the search button opens the field, and a passed offer comes out of its run alone`() {
        show()
        compose.onNode(hasContentDescription(string(R.string.search_this_shift)) and hasClickAction()).click()
        type("WINGSTOP")
        // The expired Wingstop offer sits in a collapsed run on the full page; here it is its own row.
        assertEquals(1, count("Wingstop · Expired"))
        assertEquals(0, count("Chick-fil-A"))
        assertEquals(0, count("Five Guys & Chipotle"))
        type("thai")
        assertEquals(1, count("No offers or deliveries from \"thai\""))
        // A blank query with search open shows the full timeline.
        type(" ")
        assertEquals(1, count("Sample Burgers"))
        assertEquals(0, count(string(R.string.search_scope_this)))
    }

    @Test fun `Back closes the search first and restores the full timeline`() {
        show(searchOpen = true, query = "chipotle")
        assertEquals(0, count("Sample Burgers"))
        // System Back closes the search, not the page.
        back()
        assertEquals(0, backs)
        assertEquals(1, count("Sample Burgers"))
        assertEquals(0, count(string(R.string.search_scope_this)))

        // The bar's Back does the same while searching, then leaves the page.
        compose.onNode(hasContentDescription(string(R.string.search_this_shift)) and hasClickAction()).click()
        type("chipotle")
        assertEquals(0, count("Sample Burgers"))
        val barBack = hasContentDescription(string(R.string.common_content_desc_back)) and hasClickAction()
        compose.onNode(barBack).click()
        assertEquals(0, backs)
        assertEquals(1, count("Sample Burgers"))
        compose.onNode(barBack).click()
        assertEquals(1, backs)
    }
}
