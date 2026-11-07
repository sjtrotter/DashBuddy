package cloud.trotter.log.gig.ui.main.analytics

import androidx.activity.ComponentDialog
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.domain.analytics.timeline.JobCard
import cloud.trotter.log.gig.core.designsystem.motion.LocalReducedMotion
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
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
import org.robolectric.shadows.ShadowDialog
import java.util.Locale
import java.util.TimeZone

/**
 * The shift page over the fixtures: only paid money sits in a job card's trailing slot, a run of passed offers is
 * collapsed by default and opens in place, each delivery in the sheet opens its own correction, Back closes the
 * sheet before the page, and a `row` link lands on its item, inside a collapsed run too.
 */
@RunWith(RobolectricTestRunner::class)
/** A tall window, so the whole fixture page is composed. */
@Config(sdk = [35], qualifiers = "w411dp-h2000dp-420dpi")
class ShiftPageScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)
    private lateinit var locale: Locale
    private lateinit var zone: TimeZone
    private var backs = 0

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

    private fun show(state: SessionDetailUiState, row: String? = null) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides ScreenshotData.clock, LocalReducedMotion provides true) {
                    ShiftPageContent(state, row, ShiftPageActions(onBack = { backs++ }))
                }
            }
        }
        settle()
    }

    /** A state write from a click, published to the composition, then two seconds of frames. */
    private fun settle() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    /** A click as its semantics action: the frame clock is paused, so an injected gesture never completes. */
    private fun SemanticsNodeInteraction.click() = performSemanticsAction(SemanticsActions.OnClick)

    private fun count(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    @Test fun `a job card's trailing slot holds only paid money`() {
        show(ShiftPageFixtures.stackAndAddOn())
        val trailing = compose.onAllNodesWithTag(JOB_CARD_TRAILING_TAG, useUnmergedTree = true)
        trailing.assertCountEquals(2)
        // The stack's paid total (6.25 + 5.50, cash outside it) and the add-on job's, estimated in part.
        trailing[0].assertTextEquals("$11.75")
        trailing[1].assertTextEquals(string(R.string.shift_page_estimated_format, "$11.00"))
        // The offered amounts sit in the supporting text.
        assertEquals(1, count("Offered $9.75 · 3.1 mi"))
        assertEquals(1, count("Added +$4.00 · +1.8 mi"))
        assertEquals(1, count("+$5.00 cash"))
    }

    @Test fun `a run of passed offers is collapsed by default and opens in place`() {
        show(ShiftPageFixtures.stackAndAddOn())
        val header = string(R.string.shift_page_run_both_format, 2, 1)
        assertEquals(0, count("Chick-fil-A"))
        compose.onNodeWithText(header).click()
        settle()
        assertEquals(1, count("Chick-fil-A · Declined · $6.50 · 4.1 mi"))
        assertEquals(1, count("Wingstop · Expired"))
        compose.onNodeWithText(header).click()
        settle()
        assertEquals(0, count("Chick-fil-A"))
    }

    @Test fun `each delivery in the sheet opens its own correction`() {
        // The sheet alone, so the Fix it names is read off the callback (the dialog it opens is the existing one).
        val card = ShiftPageFixtures.stackAndAddOn().timeline.first { it.key == ShiftPageFixtures.STACK_KEY } as JobCard
        val fixed = mutableListOf<Long>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides ScreenshotData.clock, LocalReducedMotion provides true) {
                    ShiftJobSheet(card, day = "Tue, Oct 27", onFix = { fixed += it }, onDismiss = {})
                }
            }
        }
        settle()
        val fixes = compose.onAllNodesWithText(string(R.string.shift_page_fix_delivery))
        fixes.assertCountEquals(2)
        fixes[1].click()
        settle()
        fixes[0].click()
        settle()
        assertEquals(listOf(ShiftPageFixtures.STACK_SECOND_DROP, ShiftPageFixtures.STACK_FIRST_DROP), fixed)
    }

    @Test fun `the sheet lists recorded milestones and leaves out the rest`() {
        show(ShiftPageFixtures.stackAndAddOn())
        compose.onNodeWithText("Sample Burgers").click()
        settle()
        // job-2 has no recorded pickup: no "At store" or "Picked up" line.
        assertEquals(0, count(string(R.string.shift_page_milestone_at_store)))
        assertEquals(0, count(string(R.string.shift_page_milestone_picked_up)))
        assertEquals(2, count(string(R.string.shift_page_milestone_delivered)))
    }

    @Test fun `Back closes the sheet before the page`() {
        show(ShiftPageFixtures.stackAndAddOn())
        compose.onNodeWithText("Five Guys & Chipotle").click()
        settle()
        assertEquals(2, count(string(R.string.shift_page_fix_delivery)))
        compose.runOnUiThread { (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed() }
        settle()
        assertEquals(0, count(string(R.string.shift_page_fix_delivery)))
        assertEquals(0, backs)
    }

    @Test fun `a row link lands on a job and opens its sheet`() {
        show(ShiftPageFixtures.stackAndAddOn(), row = "offer:20")
        // offer:20 is the add-on of the Sample Burgers job: its sheet opens.
        assertEquals(1, count("Accepted · Added +$4.00 · +1.8 mi"))
    }

    @Test fun `a row link inside a collapsed run opens the run`() {
        // offer:1040 is a declined offer in the shift's third run.
        show(ShiftPageFixtures.heavyShift(), row = "offer:1040")
        assertEquals(1, count("Sample Store 5 · Declined · $3.00 · 7.0 mi"))
    }
}
